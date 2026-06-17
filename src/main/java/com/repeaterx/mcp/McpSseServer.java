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
    private static final String SERVER_NAME    = "repeaterx";
    private static final String SERVER_VERSION = "1.0.0";

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
        info.put("name",    SERVER_NAME);
        info.put("version", SERVER_VERSION);
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
            case "get_status"          -> toolGetStatus();
            case "list_repeater_tabs"  -> toolListTabs();
            case "create_repeater_tab" -> toolCreateTab(a);
            case "delete_repeater_tab" -> toolDeleteTab(a);
            case "send_repeater_tab"   -> toolSendTab(a);
            case "get_tab_history"     -> toolGetTabHistory(a);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private String toolGetStatus() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",      "running");
        m.put("version",     SERVER_VERSION);
        m.put("historySize", historyManager.size());
        m.put("openTabs",    tabsSupplier != null ? tabsSupplier.get().size() : 0);
        return pretty(m);
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
        TabData td = tabOps.createTab(a.path("tab_name").asText("New Tab"),
                                      a.path("raw_request").asText(""));
        if (td == null) return "{\"error\":\"Failed to create tab\"}";

        // auto_send: paste request into editor then click Send — response visible in the tab
        if (a.path("auto_send").asBoolean(false)) {
            Future<RequestSender.SendResult> future = tabOps.sendInTab(td.getId());
            if (future != null) {
                RequestSender.SendResult result = future.get();
                if (result != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tab_id",   td.getId());
                    m.put("tab_name", td.getName());
                    m.put("sent",     true);
                    if (result.isSuccess()) {
                        m.put("statusCode",   result.getResponse().getStatusCode());
                        m.put("statusMessage",result.getResponse().getStatusMessage());
                        m.put("responseTime", result.getElapsed());
                        m.put("responseSize", result.getResponse().getResponseSize());
                        m.put("body",         result.getResponse().getBody());
                    } else {
                        m.put("error", result.getError());
                    }
                    return pretty(m);
                }
            }
        }
        return pretty(td);
    }

    private String toolDeleteTab(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        boolean ok = tabOps.deleteTab(a.path("tab_id").asText());
        return pretty(Map.of("success", ok));
    }

    private String toolSendTab(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        String tabId = a.path("tab_id").asText();
        if (tabId.isBlank()) return "{\"error\":\"tab_id is required\"}";

        // Triggers the exact same code path as clicking the Send button in the UI.
        // The request is read from the tab's editor; the response is displayed there.
        Future<RequestSender.SendResult> future = tabOps.sendInTab(tabId);
        if (future == null) return pretty(Map.of("success", false, "error", "Tab not found: " + tabId));

        RequestSender.SendResult result = future.get(); // wait for UI + history update to complete
        if (result == null) return pretty(Map.of("success", false, "error", "Tab busy or unavailable"));

        if (result.isSuccess()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success",      true);
            m.put("statusCode",   result.getResponse().getStatusCode());
            m.put("statusMessage",result.getResponse().getStatusMessage());
            m.put("responseTime", result.getElapsed());
            m.put("responseSize", result.getResponse().getResponseSize());
            m.put("body",         result.getResponse().getBody());
            return pretty(m);
        }
        return pretty(Map.of("success", false, "error", result.getError()));
    }

    private String toolGetTabHistory(JsonNode a) throws Exception {
        String tabId = a.path("tab_id").asText();
        if (tabId.isBlank()) return "{\"error\":\"tab_id is required\"}";
        List<HistoryEntry> entries = historyManager.getTabHistory(tabId);
        int limit = a.path("limit").asInt(entries.size());
        int end   = Math.min(limit, entries.size());

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < end; i++) {
            HistoryEntry e = entries.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entry_id",  e.getId());
            entry.put("timestamp", e.getTimestamp());

            if (e.getRequest() != null) {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("method",  e.getRequest().getMethod());
                req.put("url",     e.getRequest().getUrl());
                req.put("raw",     e.getRequest().getRawRequest());
                entry.put("request", req);
            }

            if (e.getResponse() != null) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("statusCode",    e.getResponse().getStatusCode());
                resp.put("statusMessage", e.getResponse().getStatusMessage());
                resp.put("responseTime",  e.getResponse().getResponseTime());
                resp.put("responseSize",  e.getResponse().getResponseSize());
                resp.put("body",          e.getResponse().getBody());
                resp.put("raw",           e.getResponse().getRawResponse());
                entry.put("response", resp);
            }

            out.add(entry);
        }
        return pretty(Map.of("tab_id", tabId, "total", entries.size(), "entries", out));
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
        ToolDef.of("get_status",
            "Get RepeaterX server status, number of open tabs, and total history size.",
            Map.of()),

        ToolDef.of("list_repeater_tabs",
            "List all open RepeaterX tabs with their names, history counts, notes, and last response status.",
            Map.of()),

        ToolDef.of("create_repeater_tab",
            "Create a new RepeaterX tab, paste the raw HTTP request into the editor, and optionally "
            + "click Send immediately (auto_send=true). With auto_send the request is visible in the "
            + "Burp tab and the response appears in the response panel — same as a manual send.",
            Map.of(
                "tab_name",    prop("string",  "Tab label, e.g. 'IDOR Test'. Defaults to 'New Tab'.", false),
                "raw_request", prop("string",  "Full raw HTTP request (\\r\\n line endings).", false),
                "auto_send",   prop("boolean", "If true, click Send immediately after loading the request. Response is shown in the tab.", false)
            )),

        ToolDef.of("delete_repeater_tab",
            "Close and remove a RepeaterX tab.",
            Map.of("tab_id", prop("string", "UUID of the tab to close.", true))),

        ToolDef.of("send_repeater_tab",
            "Click Send on a RepeaterX tab — identical to pressing the Send button in the UI. "
            + "Reads the current request from the tab editor, sends it via Burp's HTTP engine, "
            + "and updates the response panel and history in the tab.",
            Map.of("tab_id", prop("string", "UUID of the tab to send.", true))),

        ToolDef.of("get_tab_history",
            "Return the full send history for a specific tab — every request+response pair in order. "
            + "Use this to inspect what was sent and received in a tab.",
            Map.of(
                "tab_id", prop("string",  "UUID of the tab.", true),
                "limit",  prop("integer", "Max entries to return. Defaults to all.", false)
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
