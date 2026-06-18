package com.repeaterx.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.http.HttpExchange;
import com.repeaterx.http.SimpleHttpServer;
import com.repeaterx.model.HistoryEntry;
import com.repeaterx.model.TabData;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class McpSseServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER_NAME        = "repeaterx";
    private static final String SERVER_VERSION     = "1.0.0";
    private static final String SERVER_DESCRIPTION =
        "RepeaterX is a Burp Suite extension that lets you send and replay HTTP requests. " +
        "Workflow: use create_repeater_tab to open a tab and fire a request, " +
        "update_tab_request to modify and resend it on the same tab, " +
        "get_tab_history to read every request/response pair sent from that tab, " +
        "list_repeater_tabs to see all open tabs, delete_repeater_tab to close one. " +
        "All requests go through Burp's engine — proxy rules, TLS, and upstream proxies apply.";

    private final HistoryManager historyManager;
    private final RequestSender  requestSender;
    private Supplier<List<TabData>> tabsSupplier;
    private TabOperations           tabOps;

    private volatile String serverHost = "127.0.0.1";
    private volatile int    serverPort = 7331;

    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public interface TabOperations {
        TabData createTab(String name, String rawRequest);
        boolean deleteTab(String id);
        List<TabData> getAllTabs();
        boolean updateTabRequest(String tabId, String rawRequest);
        Future<RequestSender.SendResult> sendInTab(String tabId);
    }

    public McpSseServer(HistoryManager historyManager, RequestSender requestSender) {
        this.historyManager = historyManager;
        this.requestSender  = requestSender;
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, 25, 25, TimeUnit.SECONDS);
    }

    public void setTabOperations(TabOperations ops)         { this.tabOps = ops; }
    public void setTabsSupplier(Supplier<List<TabData>> s)  { this.tabsSupplier = s; }
    public void setServerAddress(String host, int port) {
        this.serverHost = "0.0.0.0".equals(host) ? "127.0.0.1" : host;
        this.serverPort = port;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerHandlers(SimpleHttpServer server) {
        server.createContext("/mcp",     this::handleStreamable); // MCP Streamable HTTP (2025-03-26)
        server.createContext("/sse",     this::handleSse);         // legacy SSE transport
        server.createContext("/",        this::handleSse);         // proxy compat (bare host:port)
        server.createContext("/message", this::handleMessage);
    }

    public void shutdown() {
        heartbeat.shutdownNow();
        sessions.values().forEach(SseSession::close);
        sessions.clear();
        streamableSessions.clear();
    }

    // ── SSE session ───────────────────────────────────────────────────────────

    private static final class SseSession {
        final String id;
        final OutputStream out;
        final CountDownLatch latch = new CountDownLatch(1);

        SseSession(String id, OutputStream out) {
            this.id  = id;
            this.out = out;
        }

        synchronized void send(String eventType, String data) throws IOException {
            String msg = "event: " + eventType + "\ndata: " + data + "\n\n";
            out.write(msg.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        synchronized void sendComment(String text) throws IOException {
            out.write((":" + text + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void close() { latch.countDown(); }
    }

    // ── HTTP handlers ─────────────────────────────────────────────────────────

    private void handleSse(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }
        ex.getResponseHeaders().set("Content-Type",       "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control",      "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering",  "no");
        addCors(ex);
        ex.sendResponseHeaders(200, 0); // 0 = streaming

        String     sessionId = UUID.randomUUID().toString();
        OutputStream out     = ex.getResponseBody();
        SseSession session   = new SseSession(sessionId, out);
        sessions.put(sessionId, session);

        // Build absolute message URL — proxy requires a full URI, not a relative path.
        // Prefer the Host request header (reflects the real address the client used).
        String hostHeader = ex.getRequestHeaders().getOrDefault("host", "");
        String baseUrl = hostHeader.isEmpty()
            ? "http://" + serverHost + ":" + serverPort
            : "http://" + hostHeader;

        try {
            session.send("endpoint", baseUrl + "/message?sessionId=" + sessionId);
            session.latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessions.remove(sessionId);
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    private void handleMessage(HttpExchange ex) throws IOException {
        // CORS pre-flight
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }

        Map<String, String> params    = parseQuery(ex.getRequestURI().getQuery());
        String              sessionId = params.get("sessionId");
        SseSession          session   = sessionId != null ? sessions.get(sessionId) : null;

        if (session == null) {
            addCors(ex);
            ex.sendResponseHeaders(404, -1);
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        // Acknowledge immediately — response goes via SSE stream
        addCors(ex);
        ex.sendResponseHeaders(202, -1);
        ex.close();

        // Dispatch and reply via SSE
        try {
            JsonNode req    = MAPPER.readTree(body);
            String   method = req.path("method").asText("");
            if (method.startsWith("notifications/")) return;

            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", req.get("id"));
            resp.set("result", MAPPER.valueToTree(
                processRequest(method, req.path("params"))
            ));
            session.send("message", MAPPER.writeValueAsString(resp));

        } catch (Exception e) {
            try {
                ObjectNode err = MAPPER.createObjectNode();
                err.put("jsonrpc", "2.0");
                err.putNull("id");
                ObjectNode errBody = err.putObject("error");
                errBody.put("code",    -32603);
                errBody.put("message", e.getMessage() != null ? e.getMessage() : "internal error");
                session.send("message", MAPPER.writeValueAsString(err));
            } catch (Exception ignored) {}
        }
    }

    // ── Streamable HTTP transport (MCP 2025-03-26) ───────────────────────────
    //
    // Single endpoint: POST /mcp
    // Client sends JSON-RPC 2.0 body; server responds with application/json.
    // No proxy, no long-lived connections, no SSE sessions.
    // Config in Claude Desktop / Claude Code:
    //   { "type": "http", "url": "http://HOST:7331/mcp" }

    private final Map<String, Long> streamableSessions = new ConcurrentHashMap<>();

    private void handleStreamable(HttpExchange ex) throws IOException {
        addCors(ex);

        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Mcp-Session-Id, Accept");
            ex.sendResponseHeaders(204, -1);
            return;
        }

        // DELETE /mcp — client closing session
        if ("DELETE".equals(ex.getRequestMethod())) {
            String sid = ex.getRequestHeaders().getOrDefault("mcp-session-id", "");
            if (!sid.isEmpty()) streamableSessions.remove(sid);
            ex.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId = ex.getRequestHeaders().getOrDefault("mcp-session-id", "");

        try {
            JsonNode req    = MAPPER.readTree(body);
            String   method = req.path("method").asText("");
            JsonNode id     = req.path("id");

            // notifications/initialized and other one-way notifications: 202, no body
            if (method.startsWith("notifications/")) {
                ex.sendResponseHeaders(202, -1);
                return;
            }

            // initialize — create a new session
            if ("initialize".equals(method)) {
                sessionId = UUID.randomUUID().toString();
                streamableSessions.put(sessionId, System.currentTimeMillis());
            }

            ObjectNode resp = MAPPER.createObjectNode();
            resp.put("jsonrpc", "2.0");
            if (!id.isMissingNode()) resp.set("id", id);
            resp.set("result", MAPPER.valueToTree(processRequest(method, req.path("params"))));

            byte[] bytes = MAPPER.writeValueAsBytes(resp);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            if (!sessionId.isEmpty()) ex.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }

        } catch (Exception e) {
            ObjectNode err     = MAPPER.createObjectNode();
            ObjectNode errBody = err.putObject("error");
            errBody.put("code",    -32603);
            errBody.put("message", e.getMessage() != null ? e.getMessage() : "internal error");
            err.put("jsonrpc", "2.0");
            err.putNull("id");
            byte[] bytes = MAPPER.writeValueAsBytes(err);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // ── JSON-RPC dispatcher ───────────────────────────────────────────────────

    private static final List<String> SUPPORTED_VERSIONS = List.of("2025-03-26", "2024-11-05");

    private Object processRequest(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "initialize" -> handleInitialize(params);
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            default -> throw new IllegalArgumentException("Method not found: " + method);
        };
    }

    private ObjectNode handleInitialize(JsonNode params) {
        // Negotiate protocol version — use client's requested version if we support it,
        // otherwise fall back to our highest supported version.
        String requested = params.path("protocolVersion").asText("2024-11-05");
        String version   = SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);
        ObjectNode r = MAPPER.createObjectNode();
        r.put("protocolVersion", version);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name",        SERVER_NAME);
        info.put("version",     SERVER_VERSION);
        info.put("description", SERVER_DESCRIPTION);
        r.putObject("capabilities").putObject("tools");
        return r;
    }

    private ObjectNode handleToolsList() {
        ObjectNode r = MAPPER.createObjectNode();
        ArrayNode  a = r.putArray("tools");
        for (ToolDef t : TOOLS) a.add(t.toJson(MAPPER));
        return r;
    }

    private ObjectNode handleToolsCall(JsonNode params) throws Exception {
        String   name = params.path("name").asText();
        JsonNode args = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();
        String   text = dispatchTool(name, args);
        ObjectNode r  = MAPPER.createObjectNode();
        ArrayNode  c  = r.putArray("content");
        ObjectNode item = c.addObject();
        item.put("type", "text");
        item.put("text", text);
        return r;
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    private String dispatchTool(String name, JsonNode a) throws Exception {
        return switch (name) {
            case "list_repeater_tabs"  -> toolListTabs();
            case "create_repeater_tab" -> toolCreateTab(a);
            case "delete_repeater_tab" -> toolDeleteTab(a);
            case "update_tab_request"  -> toolUpdateTabRequest(a);
            case "get_tab_history"     -> toolGetTabHistory(a);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private String toolListTabs() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TabData t : tabs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",           t.getId());
            m.put("name",         t.getName());
            m.put("historyCount", t.getHistory() != null ? t.getHistory().size() : 0);
            m.put("notes",        t.getNotes());
            if (t.getLatestResponse() != null) m.put("lastStatus", t.getLatestResponse().getStatusCode());
            out.add(m);
        }
        return pretty(out);
    }

    private String toolCreateTab(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        String tabName = a.path("tab_name").asText("").trim();
        if (tabName.isEmpty()) tabName = nameFromRequest(a.path("raw_request").asText(""));
        TabData td = tabOps.createTab(tabName, a.path("raw_request").asText(""));
        if (td == null) return "{\"error\":\"Failed to create tab\"}";

        Future<RequestSender.SendResult> future = tabOps.sendInTab(td.getId());
        if (future == null) return pretty(Map.of("tab_id", td.getId(), "tab_name", td.getName(), "error", "Send failed"));
        RequestSender.SendResult result = future.get(30, TimeUnit.SECONDS);
        if (result == null) return pretty(Map.of("tab_id", td.getId(), "tab_name", td.getName(), "error", "Tab busy"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tab_id",   td.getId());
        m.put("tab_name", td.getName());
        if (result.isSuccess()) {
            m.put("statusCode",    result.getResponse().getStatusCode());
            m.put("statusMessage", result.getResponse().getStatusMessage());
            m.put("responseTime",  result.getElapsed());
            m.put("responseSize",  result.getResponse().getResponseSize());
            m.put("body",          result.getResponse().getBody());
        } else {
            m.put("error", result.getError());
        }
        return pretty(m);
    }

    private String toolDeleteTab(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        boolean ok = tabOps.deleteTab(a.path("tab_id").asText());
        return pretty(Map.of("success", ok));
    }

    private String toolUpdateTabRequest(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        String tabId  = a.path("tab_id").asText();
        String rawReq = a.path("raw_request").asText();
        if (tabId.isBlank())  return "{\"error\":\"tab_id is required\"}";
        if (rawReq.isBlank()) return "{\"error\":\"raw_request is required\"}";
        boolean ok = tabOps.updateTabRequest(tabId, rawReq);
        if (!ok) return pretty(Map.of("success", false, "error", "Tab not found: " + tabId));

        Future<RequestSender.SendResult> future = tabOps.sendInTab(tabId);
        if (future == null) return pretty(Map.of("success", false, "error", "Send failed"));
        RequestSender.SendResult result = future.get(30, TimeUnit.SECONDS);
        if (result == null) return pretty(Map.of("success", false, "error", "Tab busy"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        if (result.isSuccess()) {
            m.put("statusCode",    result.getResponse().getStatusCode());
            m.put("statusMessage", result.getResponse().getStatusMessage());
            m.put("responseTime",  result.getElapsed());
            m.put("responseSize",  result.getResponse().getResponseSize());
            m.put("body",          result.getResponse().getBody());
        } else {
            m.put("error", result.getError());
        }
        return pretty(m);
    }

    private String toolGetTabHistory(JsonNode a) throws Exception {
        String tabId = a.path("tab_id").asText();
        if (tabId.isBlank()) return "{\"error\":\"tab_id is required\"}";
        List<HistoryEntry> entries = historyManager.getTabHistory(tabId);

        // No index supplied → return summary so agent knows what's available
        if (!a.has("index")) {
            List<Map<String, Object>> summary = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                HistoryEntry e = entries.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index",     i);
                row.put("timestamp", e.getTimestamp());
                if (e.getRequest()  != null) { row.put("method", e.getRequest().getMethod()); row.put("url", e.getRequest().getUrl()); }
                if (e.getResponse() != null) { row.put("statusCode", e.getResponse().getStatusCode()); row.put("responseTime", e.getResponse().getResponseTime()); }
                summary.add(row);
            }
            return pretty(Map.of("tab_id", tabId, "count", entries.size(), "entries", summary));
        }

        // Index supplied → return full request + response for that entry
        int idx = a.path("index").asInt(-1);
        if (idx < 0 || idx >= entries.size())
            return pretty(Map.of("error", "index " + idx + " out of range, count is " + entries.size()));

        HistoryEntry e = entries.get(idx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tab_id",    tabId);
        out.put("index",     idx);
        out.put("timestamp", e.getTimestamp());

        if (e.getRequest() != null) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("method", e.getRequest().getMethod());
            req.put("url",    e.getRequest().getUrl());
            req.put("raw",    e.getRequest().getRawRequest());
            out.put("request", req);
        }

        if (e.getResponse() != null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("statusCode",    e.getResponse().getStatusCode());
            resp.put("statusMessage", e.getResponse().getStatusMessage());
            resp.put("responseTime",  e.getResponse().getResponseTime());
            resp.put("responseSize",  e.getResponse().getResponseSize());
            resp.put("body",          e.getResponse().getBody());
            resp.put("raw",           e.getResponse().getRawResponse());
            out.put("response", resp);
        }

        return pretty(out);
    }

    private static String nameFromRequest(String raw) {
        if (raw == null || raw.isBlank()) return "New Tab";
        String method = "", host = "";
        String[] lines = raw.split("\r\n|\n", 10);
        if (lines.length > 0) { String[] p = lines[0].trim().split("\\s+", 3); if (p.length >= 1) method = p[0]; }
        for (String l : lines) { if (l.toLowerCase().startsWith("host:")) { host = l.substring(5).trim(); break; } }
        return (method + " " + host).trim().isEmpty() ? "New Tab" : (method + " " + host).trim();
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private void sendHeartbeat() {
        sessions.values().removeIf(s -> {
            try { s.sendComment("ping"); return false; }
            catch (IOException e) { s.close(); return true; }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<TabData> tabs() {
        return tabsSupplier != null ? tabsSupplier.get() : Collections.emptyList();
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID");
    }

    private String pretty(Object obj) throws Exception {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                               URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                } catch (Exception e) { params.put(kv[0], kv[1]); }
            }
        }
        return params;
    }

    // ── Tool definitions (MCP schema) ─────────────────────────────────────────

    private static final List<ToolDef> TOOLS = List.of(
        ToolDef.of("list_repeater_tabs",
            "List all open RepeaterX tabs. Each entry includes the tab id, name, historyCount (how many "
            + "requests have been sent from that tab), and the last response status code. "
            + "Call this first to discover tab ids and check how many history entries each tab has "
            + "before calling get_tab_history.",
            Map.of()),

        ToolDef.of("create_repeater_tab",
            "Open a new Burp Repeater tab, load the given raw HTTP request into the editor, and send it "
            + "immediately. Returns the tab_id you will need for update_tab_request and get_tab_history, "
            + "plus the full response (statusCode, body, responseTime). "
            + "Use this for the first request in a test sequence.",
            Map.of(
                "tab_name",    prop("string", "Label shown on the tab, e.g. 'IDOR - user_id test'. Defaults to 'New Tab'.", false),
                "raw_request", prop("string", "Complete raw HTTP request including request-line, headers, blank line, and body. Use \\r\\n as line endings.", false)
            )),

        ToolDef.of("delete_repeater_tab",
            "Close and permanently remove a Burp Repeater tab. Use this to clean up tabs you no longer need.",
            Map.of("tab_id", prop("string", "UUID of the tab returned by list_repeater_tabs or create_repeater_tab.", true))),

        ToolDef.of("update_tab_request",
            "Replace the raw HTTP request in an existing tab and send it immediately. "
            + "Returns the full response (statusCode, body, responseTime). "
            + "Both the updated request and response are visible in Burp's UI. "
            + "PARALLEL-SAFE: call this simultaneously on multiple tabs (each with a different tab_id) "
            + "to run parallel requests — e.g. IDOR across 10 user IDs, same payload on different endpoints, "
            + "or fuzzing multiple parameters at once. Each tab sends independently; results come back "
            + "as each tab finishes (30s timeout per tab). "
            + "Typical pattern: create N tabs with create_repeater_tab, then call update_tab_request "
            + "on all N tabs in parallel for each test round.",
            Map.of(
                "tab_id",      prop("string", "UUID of the tab to update — each parallel call must target a different tab.", true),
                "raw_request", prop("string", "Complete modified raw HTTP request to load and send.", true)
            )),

        ToolDef.of("get_tab_history",
            "Inspect the send history of a specific tab. "
            + "Without 'index': returns a lightweight summary — count of entries plus index, timestamp, method, URL, "
            + "and status code for each. Call this first to see how many sends have happened and pick the entries you care about. "
            + "With 'index': returns the full request (method, URL, raw) and full response (statusCode, body, raw) "
            + "for that single entry. Index is 0-based; use the count from list_repeater_tabs or the summary "
            + "to know the valid range. Example: call without index to see 5 entries, then call with index=2 to read the third one.",
            Map.of(
                "tab_id", prop("string",  "UUID of the tab.", true),
                "index",  prop("integer", "0-based position of the history entry to fetch in full. Omit to get the summary list.", false)
            ))
    );

    private static Map<String, Object> prop(String type, String description, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        m.put("required", required);
        return m;
    }

    private record ToolDef(String name, String description, Map<String, Map<String, Object>> properties) {

        static ToolDef of(String name, String desc, Map<String, Map<String, Object>> props) {
            return new ToolDef(name, desc, props);
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node   = mapper.createObjectNode();
            node.put("name",        name);
            node.put("description", description);
            ObjectNode schema = node.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode propsNode = schema.putObject("properties");
            ArrayNode  reqArray  = schema.putArray("required");
            for (Map.Entry<String, Map<String, Object>> e : properties.entrySet()) {
                ObjectNode p = propsNode.putObject(e.getKey());
                p.put("type",        (String) e.getValue().get("type"));
                p.put("description", (String) e.getValue().get("description"));
                if (Boolean.TRUE.equals(e.getValue().get("required"))) reqArray.add(e.getKey());
            }
            return node;
        }
    }
}
