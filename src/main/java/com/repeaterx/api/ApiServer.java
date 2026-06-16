package com.repeaterx.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.ProjectManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.mcp.McpSseServer;
import com.repeaterx.model.TabData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Hosts the MCP SSE server (GET /sse, POST /message) and a lightweight
 * health-check endpoint (GET /status). All agent interaction goes through
 * the MCP tools — no separate REST API needed.
 */
public class ApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private HttpServer server;
    private String currentHost = "0.0.0.0";
    private int    currentPort = 7331;

    private final HistoryManager historyManager;
    private final RequestSender  requestSender;
    private final ProjectManager projectManager;
    private final McpSseServer   mcpSseServer;

    private Supplier<List<TabData>> tabsSupplier;
    private TabOperations           tabOps;
    private StartListener           startListener;

    // ── Interfaces ────────────────────────────────────────────────────────────

    public interface TabOperations {
        TabData createTab(String name, String rawRequest);
        boolean deleteTab(String id);
        TabData getTab(String id);
        void    sendInTab(String id, Runnable callback);
        List<TabData> getAllTabs();
        TabData duplicateTab(String id);
    }

    public interface StartListener {
        void onStart(String host, int port);
        void onStop();
    }

    // ── Construction ──────────────────────────────────────────────────────────

    public ApiServer(HistoryManager historyManager, RequestSender requestSender, ProjectManager projectManager) {
        this.historyManager = historyManager;
        this.requestSender  = requestSender;
        this.projectManager = projectManager;
        this.mcpSseServer   = new McpSseServer(historyManager, requestSender);
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    public void setTabOperations(TabOperations ops) {
        this.tabOps = ops;
        mcpSseServer.setTabOperations(new McpSseServer.TabOperations() {
            @Override public TabData       createTab(String name, String raw) { return ops.createTab(name, raw); }
            @Override public boolean       deleteTab(String id)               { return ops.deleteTab(id); }
            @Override public List<TabData> getAllTabs()                        { return ops.getAllTabs(); }
        });
    }

    public void setTabsSupplier(Supplier<List<TabData>> supplier) {
        this.tabsSupplier = supplier;
        mcpSseServer.setTabsSupplier(supplier);
    }

    public void setStartListener(StartListener l) { this.startListener = l; }

    public McpSseServer getMcpSseServer() { return mcpSseServer; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(currentHost, currentPort), 20);
        server.createContext("/status", this::handleStatus);   // health check
        mcpSseServer.registerHandlers(server);                 // /sse + /message
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        if (startListener != null) startListener.onStart(currentHost, currentPort);
    }

    public synchronized void stop() {
        if (server != null) {
            mcpSseServer.shutdown();
            server.stop(1);
            server = null;
            if (startListener != null) startListener.onStop();
        }
    }

    public synchronized void restart(String host, int port) throws IOException {
        stop();
        this.currentHost = host;
        this.currentPort = port;
        start();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getCurrentHost() { return currentHost; }
    public int     getCurrentPort() { return currentPort; }
    public boolean isRunning()      { return server != null; }

    // ── Health check ──────────────────────────────────────────────────────────

    private void handleStatus(HttpExchange ex) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",      "running");
        body.put("version",     "1.0.0");
        body.put("host",        currentHost);
        body.put("port",        currentPort);
        body.put("historySize", historyManager.size());
        body.put("mcp_sse",     "GET /sse");
        body.put("mcp_post",    "POST /message?sessionId=<id>");
        sendJson(ex, 200, body);
    }

    private void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(obj);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
