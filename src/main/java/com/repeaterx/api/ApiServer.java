package com.repeaterx.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.http.HttpExchange;
import com.repeaterx.http.SimpleHttpServer;
import com.repeaterx.mcp.McpSseServer;
import com.repeaterx.model.TabData;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ApiServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private SimpleHttpServer server;
    private String           currentHost = "0.0.0.0";
    private int              currentPort = 7331;

    private final HistoryManager historyManager;
    private final McpSseServer   mcpSseServer;

    public interface TabOperations {
        TabData       createTab(String name, String rawRequest);
        boolean       deleteTab(String id);
        List<TabData> getAllTabs();
    }

    public ApiServer(HistoryManager historyManager, RequestSender requestSender) {
        this.historyManager = historyManager;
        this.mcpSseServer   = new McpSseServer(historyManager, requestSender);
    }

    public void setTabOperations(TabOperations ops) {
        mcpSseServer.setTabOperations(new McpSseServer.TabOperations() {
            @Override public TabData       createTab(String name, String raw) { return ops.createTab(name, raw); }
            @Override public boolean       deleteTab(String id)               { return ops.deleteTab(id); }
            @Override public List<TabData> getAllTabs()                        { return ops.getAllTabs(); }
        });
    }

    public void setTabsSupplier(Supplier<List<TabData>> supplier) {
        mcpSseServer.setTabsSupplier(supplier);
    }

    public synchronized void start() throws IOException {
        mcpSseServer.setServerAddress(currentHost, currentPort);
        server = SimpleHttpServer.create(currentHost, currentPort);
        server.createContext("/status", this::handleStatus);
        mcpSseServer.registerHandlers(server);
        server.start();
    }

    public synchronized void stop() {
        if (server != null) {
            mcpSseServer.shutdown();
            server.stop(1);
            server = null;
        }
    }

    public synchronized void restart(String host, int port) throws IOException {
        stop();
        this.currentHost = host;
        this.currentPort = port;
        start();
    }

    public String  getCurrentHost() { return currentHost; }
    public int     getCurrentPort() { return currentPort; }
    public boolean isRunning()      { return server != null; }

    private void handleStatus(HttpExchange ex) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",         "running");
        body.put("version",        "1.0.0");
        body.put("host",           currentHost);
        body.put("port",           currentPort);
        body.put("historySize",    historyManager.size());
        body.put("mcp_streamable", "POST /mcp");
        body.put("mcp_sse",        "GET /sse  (legacy)");
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type",               "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
