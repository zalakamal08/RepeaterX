package com.repeaterx.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.model.HistoryEntry;
import com.repeaterx.model.RequestData;
import com.repeaterx.model.TabData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Embedded MCP SSE server.
 *
 * Architecture follows PortSwigger's burp-mcp extension:
 *   - GET  /sse      → long-lived SSE stream; sends "endpoint" event on connect
 *   - POST /message  → JSON-RPC 2.0 requests; responses sent back via the SSE stream
 *
 * Claude Desktop (stdio-only) can connect via PortSwigger's proxy JAR:
 *   java -jar mcp-proxy-all.jar --sse-url http://127.0.0.1:7331
 *
 * Tools follow PortSwigger's snake_case naming convention.
 */
public class McpSseServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER_NAME    = "repeaterx";
    private static final String SERVER_VERSION = "1.0.0";

    private final HistoryManager historyManager;
    private final RequestSender  requestSender;
    private Supplier<List<TabData>>  tabsSupplier;
    private TabOperations            tabOps;

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
    }

    public McpSseServer(HistoryManager historyManager, RequestSender requestSender) {
        this.historyManager = historyManager;
        this.requestSender  = requestSender;
        // Send a keep-alive comment every 25 seconds to prevent proxy timeouts
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, 25, 25, TimeUnit.SECONDS);
    }

    public void setTabOperations(TabOperations ops)          { this.tabOps = ops; }
    public void setTabsSupplier(Supplier<List<TabData>> s)   { this.tabsSupplier = s; }

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerHandlers(HttpServer server) {
        server.createContext("/sse",     this::handleSse);
        server.createContext("/message", this::handleMessage);
    }

    public void shutdown() {
        heartbeat.shutdownNow();
        sessions.values().forEach(SseSession::close);
        sessions.clear();
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

        synchronized void sendComment(String comment) throws IOException {
            out.write((":" + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
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

        ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection",    "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        addCorsHeaders(ex);
        ex.sendResponseHeaders(200, 0);

        String    sessionId = UUID.randomUUID().toString();
        OutputStream out   = ex.getResponseBody();
        SseSession session  = new SseSession(sessionId, out);
        sessions.put(sessionId, session);

        try {
            // Tell the MCP client where to POST messages (PortSwigger pattern)
            session.send("endpoint", "/message?sessionId=" + sessionId);
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
            sendHttpJson(ex, 404, "{\"error\":\"Session not found\"}");
            return;
        }

        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Acknowledge immediately (PortSwigger pattern)
        addCorsHeaders(ex);
        ex.sendResponseHeaders(202, -1);
        ex.close();

        // Dispatch and respond via SSE
        try {
            JsonNode request = MAPPER.readTree(body);
            String   method  = request.path("method").asText("");

            // Notifications have no id and need no response
            if (method.startsWith("notifications/")) return;

            JsonNode responseNode = buildJsonRpcResponse(
                request.get("id"),
                processRequest(method, request.path("params"))
            );
            session.send("message", MAPPER.writeValueAsString(responseNode));
        } catch (Exception e) {
            try {
                ObjectNode err = MAPPER.createObjectNode();
                err.put("jsonrpc", "2.0");
                err.putNull("id");
                ObjectNode errBody = err.putObject("error");
                errBody.put("code", -32603);
                errBody.put("message", e.getMessage() != null ? e.getMessage() : "Internal error");
                session.send("message", MAPPER.writeValueAsString(err));
            } catch (Exception ignored) {}
        }
    }

    // ── JSON-RPC dispatcher ───────────────────────────────────────────────────

    private Object processRequest(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "initialize"  -> handleInitialize(params);
            case "tools/list"  -> handleToolsList();
            case "tools/call"  -> handleToolsCall(params);
            default -> throw new IllegalArgumentException("Method not found: " + method);
        };
    }

    private ObjectNode buildJsonRpcResponse(JsonNode id, Object result) throws Exception {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        resp.set("result", MAPPER.valueToTree(result));
        return resp;
    }

    // ── MCP method handlers ───────────────────────────────────────────────────

    private ObjectNode handleInitialize(JsonNode params) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", SERVER_VERSION);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        return result;
    }

    private ObjectNode handleToolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode  tools  = result.putArray("tools");
        for (ToolDef t : TOOLS) tools.add(t.toJson(MAPPER));
        return result;
    }

    private ObjectNode handleToolsCall(JsonNode params) throws Exception {
        String   toolName = params.path("name").asText();
        JsonNode args     = params.has("arguments") ? params.get("arguments") : MAPPER.createObjectNode();

        String text = dispatchTool(toolName, args);

        ObjectNode result  = MAPPER.createObjectNode();
        ArrayNode  content = result.putArray("content");
        ObjectNode item    = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        return result;
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    private String dispatchTool(String name, JsonNode a) throws Exception {
        return switch (name) {
            case "get_status"            -> toolGetStatus();
            case "list_repeater_tabs"    -> toolListTabs();
            case "create_repeater_tab"   -> toolCreateTab(a);
            case "delete_repeater_tab"   -> toolDeleteTab(a);
            case "send_http_request"     -> toolSendRequest(a);
            case "get_request_history"   -> toolGetHistory(a);
            case "search_history"        -> toolSearchHistory(a);
            case "replay_history_entry"  -> toolReplayEntry(a);
            case "get_history_request"   -> toolGetRequest(a);
            case "get_history_response"  -> toolGetResponse(a);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private String toolGetStatus() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "running");
        m.put("version", SERVER_VERSION);
        m.put("historySize", historyManager.size());
        m.put("openTabs", tabsSupplier != null ? tabsSupplier.get().size() : 0);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
    }

    private String toolListTabs() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        List<TabData> tabs = tabsSupplier != null ? tabsSupplier.get() : Collections.emptyList();
        for (TabData t : tabs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("historyCount", t.getHistory() != null ? t.getHistory().size() : 0);
            m.put("notes", t.getNotes());
            if (t.getLatestResponse() != null) m.put("lastStatus", t.getLatestResponse().getStatusCode());
            out.add(m);
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    private String toolCreateTab(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        String name = a.path("tab_name").asText("New Tab");
        String raw  = a.path("raw_request").asText("");
        TabData td  = tabOps.createTab(name, raw);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(td);
    }

    private String toolDeleteTab(JsonNode a) throws Exception {
        if (tabOps == null) return "{\"error\":\"Panel not ready\"}";
        String id = a.path("tab_id").asText();
        boolean ok = tabOps.deleteTab(id);
        return MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(Map.of("success", ok));
    }

    private String toolSendRequest(JsonNode a) throws Exception {
        String host    = a.path("target_hostname").asText();
        int    port    = a.path("target_port").asInt(443);
        boolean https  = a.path("uses_https").asBoolean(true);
        String method  = a.path("method").asText("GET");
        String raw     = a.path("content").asText();
        String url     = (https ? "https" : "http") + "://" + host + "/";

        RequestData req = new RequestData(
            UUID.randomUUID().toString(), method, url, host, port, https, null, null, raw);
        RequestSender.SendResult result = requestSender.send(req);

        if (result.isSuccess()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("statusCode", result.getResponse().getStatusCode());
            m.put("statusMessage", result.getResponse().getStatusMessage());
            m.put("responseTime", result.getElapsed());
            m.put("responseSize", result.getResponse().getResponseSize());
            m.put("body", result.getResponse().getBody());
            m.put("rawResponse", result.getResponse().getRawResponse());
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } else {
            return MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("success", false, "error", result.getError()));
        }
    }

    private String toolGetHistory(JsonNode a) throws Exception {
        List<HistoryEntry> entries;
        if (a.has("query"))  entries = historyManager.search(a.get("query").asText());
        else if (a.has("status_code")) entries = historyManager.filterByStatus(a.get("status_code").asInt());
        else if (a.has("method"))      entries = historyManager.filterByMethod(a.get("method").asText());
        else if (a.has("tab_id"))      entries = historyManager.getTabHistory(a.get("tab_id").asText());
        else                           entries = historyManager.getAllHistory();

        int limit  = a.path("count").asInt(50);
        int offset = a.path("offset").asInt(0);
        int end    = Math.min(offset + limit, entries.size());

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = offset; i < end; i++) {
            HistoryEntry e = entries.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("tabId", e.getTabId());
            m.put("timestamp", e.getTimestamp());
            if (e.getRequest()  != null) { m.put("method", e.getRequest().getMethod()); m.put("url", e.getRequest().getUrl()); }
            if (e.getResponse() != null) { m.put("status", e.getResponse().getStatusCode()); m.put("responseTime", e.getResponse().getResponseTime()); }
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", entries.size());
        result.put("offset", offset);
        result.put("count", out.size());
        result.put("entries", out);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    private String toolSearchHistory(JsonNode a) throws Exception {
        String query = a.path("query").asText();
        int    limit = a.path("count").asInt(20);
        List<HistoryEntry> results = historyManager.search(query);
        List<HistoryEntry> paged   = results.subList(0, Math.min(limit, results.size()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("total", results.size());
        out.put("results", paged);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }

    private String toolReplayEntry(JsonNode a) throws Exception {
        String entryId = a.path("entry_id").asText();
        Optional<HistoryEntry> opt = historyManager.getEntryById(entryId);
        if (opt.isEmpty() || opt.get().getRequest() == null)
            return "{\"error\":\"History entry not found\"}";

        RequestSender.SendResult result = requestSender.send(opt.get().getRequest());
        if (result.isSuccess()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("statusCode", result.getResponse().getStatusCode());
            m.put("responseTime", result.getElapsed());
            m.put("body", result.getResponse().getBody());
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } else {
            return MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("success", false, "error", result.getError()));
        }
    }

    private String toolGetRequest(JsonNode a) throws Exception {
        String entryId = a.path("entry_id").asText();
        Optional<HistoryEntry> opt = historyManager.getEntryById(entryId);
        if (opt.isEmpty() || opt.get().getRequest() == null)
            return "{\"error\":\"Not found\"}";
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(opt.get().getRequest());
    }

    private String toolGetResponse(JsonNode a) throws Exception {
        String entryId = a.path("entry_id").asText();
        Optional<HistoryEntry> opt = historyManager.getEntryById(entryId);
        if (opt.isEmpty() || opt.get().getResponse() == null)
            return "{\"error\":\"Not found\"}";
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(opt.get().getResponse());
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private void sendHeartbeat() {
        sessions.values().removeIf(s -> {
            try {
                s.sendComment("ping");
                return false;
            } catch (IOException e) {
                s.close();
                return true;
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID");
    }

    private void sendHttpJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        addCorsHeaders(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, String> parseQuery(String query) {
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

    // ── Tool definitions (schema) ─────────────────────────────────────────────

    private static final List<ToolDef> TOOLS = List.of(
        ToolDef.of("get_status",
            "Get RepeaterX server status, number of open tabs, and total history size.",
            Map.of()),

        ToolDef.of("list_repeater_tabs",
            "List all open RepeaterX tabs with their names, history counts, notes, and last response status.",
            Map.of()),

        ToolDef.of("create_repeater_tab",
            "Create a new RepeaterX tab. Optionally pre-load it with a raw HTTP request.",
            Map.of(
                "tab_name",    prop("string",  "Tab label, e.g. 'IDOR Test'. Defaults to 'New Tab'.", false),
                "raw_request", prop("string",  "Full raw HTTP request to pre-load, using \\r\\n line endings.", false)
            )),

        ToolDef.of("delete_repeater_tab",
            "Close and remove a RepeaterX tab.",
            Map.of("tab_id", prop("string", "UUID of the tab to close.", true))),

        ToolDef.of("send_http_request",
            "Send an HTTP/1.1 request through Burp's engine. Respects Burp proxy config, TLS settings, and upstream proxies. Returns status, body, and timing.",
            Map.of(
                "content",          prop("string",  "Full raw HTTP request (request line + headers + blank line + body, \\r\\n separated).", true),
                "target_hostname",  prop("string",  "Target hostname, e.g. 'api.example.com'.", true),
                "target_port",      prop("integer", "Target port. Defaults to 443.", false),
                "uses_https",       prop("boolean", "Whether to use TLS. Defaults to true.", false),
                "method",           prop("string",  "HTTP method. Defaults to 'GET'.", false)
            )),

        ToolDef.of("get_request_history",
            "Return paginated request/response history. Filter by search query, status code, HTTP method, or tab ID.",
            Map.of(
                "query",       prop("string",  "Full-text search across URL, method, host, response body.", false),
                "status_code", prop("integer", "Filter by HTTP status code, e.g. 403.", false),
                "method",      prop("string",  "Filter by HTTP method, e.g. 'POST'.", false),
                "tab_id",      prop("string",  "Filter by tab UUID.", false),
                "count",       prop("integer", "Page size. Default 50.", false),
                "offset",      prop("integer", "Page offset. Default 0.", false)
            )),

        ToolDef.of("search_history",
            "Full-text search across all history entries (URL, method, host, response body).",
            Map.of(
                "query", prop("string",  "Search term.", true),
                "count", prop("integer", "Max results. Default 20.", false)
            )),

        ToolDef.of("replay_history_entry",
            "Re-send a previously captured request from history.",
            Map.of("entry_id", prop("string", "UUID of the history entry to replay.", true))),

        ToolDef.of("get_history_request",
            "Get full request details (headers, body, raw bytes) for a history entry.",
            Map.of("entry_id", prop("string", "UUID of the history entry.", true))),

        ToolDef.of("get_history_response",
            "Get full response details (status, headers, body, raw bytes) for a history entry.",
            Map.of("entry_id", prop("string", "UUID of the history entry.", true)))
    );

    private static Map<String, Object> prop(String type, String description, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        m.put("required", required);
        return m;
    }

    private record ToolDef(String name, String description, Map<String, Map<String, Object>> properties) {

        static ToolDef of(String name, String description, Map<String, Map<String, Object>> props) {
            return new ToolDef(name, description, props);
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("description", description);
            ObjectNode schema   = node.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode propsNode  = schema.putObject("properties");
            ArrayNode  reqArray   = schema.putArray("required");
            for (Map.Entry<String, Map<String, Object>> e : properties.entrySet()) {
                ObjectNode p = propsNode.putObject(e.getKey());
                p.put("type", (String) e.getValue().get("type"));
                p.put("description", (String) e.getValue().get("description"));
                if (Boolean.TRUE.equals(e.getValue().get("required"))) reqArray.add(e.getKey());
            }
            return node;
        }
    }
}
