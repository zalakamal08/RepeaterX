package com.repeaterx.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.ProjectManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.model.HistoryEntry;
import com.repeaterx.model.RequestData;
import com.repeaterx.model.TabData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class ApiServer {
    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private HttpServer server;
    private String currentHost = "0.0.0.0";
    private int currentPort = 7331;
    private final HistoryManager historyManager;
    private final RequestSender requestSender;
    private final ProjectManager projectManager;
    private Supplier<List<TabData>> tabsSupplier;
    private TabOperations tabOps;
    private StartListener startListener;

    public interface TabOperations {
        TabData createTab(String name, String rawRequest);
        boolean deleteTab(String id);
        TabData getTab(String id);
        void sendInTab(String id, Runnable callback);
        List<TabData> getAllTabs();
        TabData duplicateTab(String id);
    }

    public interface StartListener {
        void onStart(String host, int port);
        void onStop();
    }

    public ApiServer(HistoryManager historyManager, RequestSender requestSender, ProjectManager projectManager) {
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        this.projectManager = projectManager;
    }

    public void setTabOperations(TabOperations ops) { this.tabOps = ops; }
    public void setTabsSupplier(Supplier<List<TabData>> supplier) { this.tabsSupplier = supplier; }
    public void setStartListener(StartListener l) { this.startListener = l; }

    public synchronized void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(currentHost, currentPort), 20);
        server.createContext("/tabs", this::handleTabs);
        server.createContext("/history", this::handleHistory);
        server.createContext("/request/", this::handleRequest);
        server.createContext("/response/", this::handleResponse);
        server.createContext("/send", this::handleSend);
        server.createContext("/replay", this::handleReplay);
        server.createContext("/save-project", this::handleSaveProject);
        server.createContext("/load-project", this::handleLoadProject);
        server.createContext("/search", this::handleSearch);
        server.createContext("/status", this::handleStatus);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        if (startListener != null) startListener.onStart(currentHost, currentPort);
    }

    public synchronized void stop() {
        if (server != null) {
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

    public String getCurrentHost() { return currentHost; }
    public int getCurrentPort() { return currentPort; }
    public boolean isRunning() { return server != null; }

    public int getPort() { return currentPort; }

    private void handleTabs(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        try {
            if ("GET".equals(method) && "/tabs".equals(path)) {
                List<TabData> tabs = tabOps != null ? tabOps.getAllTabs() : Collections.emptyList();
                List<Map<String, Object>> result = new ArrayList<>();
                for (TabData t : tabs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("name", t.getName());
                    m.put("historyCount", t.getHistory() != null ? t.getHistory().size() : 0);
                    m.put("notes", t.getNotes());
                    m.put("updatedAt", t.getUpdatedAt());
                    if (t.getLatestResponse() != null) m.put("lastStatus", t.getLatestResponse().getStatusCode());
                    result.add(m);
                }
                sendJson(ex, 200, result);
            } else if ("POST".equals(method) && "/tabs/create".equals(path)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = mapper.readValue(readBody(ex), Map.class);
                String name = (String) body.getOrDefault("name", "New Tab");
                String raw = (String) body.getOrDefault("rawRequest", "");
                TabData tab = tabOps != null ? tabOps.createTab(name, raw) : null;
                sendJson(ex, 201, tab != null ? tab : Map.of("error", "Panel not ready"));
            } else if ("POST".equals(method) && "/tabs/delete".equals(path)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = mapper.readValue(readBody(ex), Map.class);
                String id = (String) body.get("id");
                boolean ok = id != null && tabOps != null && tabOps.deleteTab(id);
                sendJson(ex, ok ? 200 : 404, Map.of("success", ok));
            } else if ("POST".equals(method) && "/tabs/duplicate".equals(path)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = mapper.readValue(readBody(ex), Map.class);
                String id = (String) body.get("id");
                TabData dup = (id != null && tabOps != null) ? tabOps.duplicateTab(id) : null;
                sendJson(ex, dup != null ? 201 : 404, dup != null ? dup : Map.of("error", "Tab not found"));
            } else {
                sendJson(ex, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleHistory(HttpExchange ex) throws IOException {
        try {
            String query = ex.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            List<HistoryEntry> entries;
            if (params.containsKey("q")) {
                entries = historyManager.search(params.get("q"));
            } else if (params.containsKey("status")) {
                entries = historyManager.filterByStatus(Integer.parseInt(params.get("status")));
            } else if (params.containsKey("method")) {
                entries = historyManager.filterByMethod(params.get("method"));
            } else if (params.containsKey("tab")) {
                entries = historyManager.getTabHistory(params.get("tab"));
            } else {
                entries = historyManager.getAllHistory();
            }
            int limit = Integer.parseInt(params.getOrDefault("limit", "100"));
            int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
            List<Map<String, Object>> result = new ArrayList<>();
            int end = Math.min(offset + limit, entries.size());
            for (int i = offset; i < end; i++) {
                HistoryEntry e = entries.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("tabId", e.getTabId());
                m.put("timestamp", e.getTimestamp());
                if (e.getRequest() != null) {
                    m.put("method", e.getRequest().getMethod());
                    m.put("url", e.getRequest().getUrl());
                }
                if (e.getResponse() != null) {
                    m.put("status", e.getResponse().getStatusCode());
                    m.put("responseTime", e.getResponse().getResponseTime());
                    m.put("responseSize", e.getResponse().getResponseSize());
                }
                result.add(m);
            }
            sendJson(ex, 200, Map.of("total", entries.size(), "offset", offset, "limit", limit, "entries", result));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleRequest(HttpExchange ex) throws IOException {
        try {
            String id = ex.getRequestURI().getPath().replaceFirst("/request/", "");
            Optional<HistoryEntry> entry = historyManager.getEntryById(id);
            if (entry.isPresent() && entry.get().getRequest() != null) {
                sendJson(ex, 200, entry.get().getRequest());
            } else {
                sendJson(ex, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleResponse(HttpExchange ex) throws IOException {
        try {
            String id = ex.getRequestURI().getPath().replaceFirst("/response/", "");
            Optional<HistoryEntry> entry = historyManager.getEntryById(id);
            if (entry.isPresent() && entry.get().getResponse() != null) {
                sendJson(ex, 200, entry.get().getResponse());
            } else {
                sendJson(ex, 404, Map.of("error", "Not found"));
            }
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleSend(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, Map.of("error", "Method not allowed"));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(readBody(ex), Map.class);
            String host = (String) body.getOrDefault("host", "");
            int port = body.containsKey("port") ? ((Number) body.get("port")).intValue() : 443;
            boolean https = Boolean.TRUE.equals(body.get("https"));
            String rawRequest = (String) body.getOrDefault("rawRequest", "");
            String method = (String) body.getOrDefault("method", "GET");
            String url = (String) body.getOrDefault("url", "/");

            String reqId = UUID.randomUUID().toString();
            RequestData req = new RequestData(reqId, method, url, host, port, https, null, null, rawRequest);

            RequestSender.SendResult result = requestSender.send(req);
            if (result.isSuccess()) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("requestId", reqId);
                resp.put("status", result.getResponse().getStatusCode());
                resp.put("responseTime", result.getElapsed());
                resp.put("responseSize", result.getResponse().getResponseSize());
                resp.put("response", result.getResponse());
                sendJson(ex, 200, resp);
            } else {
                sendJson(ex, 500, Map.of("success", false, "error", result.getError()));
            }
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleReplay(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                sendJson(ex, 405, Map.of("error", "Method not allowed"));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(readBody(ex), Map.class);
            String historyId = (String) body.get("historyId");
            Optional<HistoryEntry> entryOpt = historyManager.getEntryById(historyId);
            if (entryOpt.isEmpty() || entryOpt.get().getRequest() == null) {
                sendJson(ex, 404, Map.of("error", "History entry not found"));
                return;
            }
            RequestData req = entryOpt.get().getRequest();
            RequestSender.SendResult result = requestSender.send(req);
            if (result.isSuccess()) {
                sendJson(ex, 200, Map.of(
                    "success", true,
                    "status", result.getResponse().getStatusCode(),
                    "responseTime", result.getElapsed(),
                    "response", result.getResponse()
                ));
            } else {
                sendJson(ex, 500, Map.of("success", false, "error", result.getError()));
            }
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleSaveProject(HttpExchange ex) throws IOException {
        try {
            sendJson(ex, 200, Map.of("success", true, "message", "Use the Save Project button in the UI"));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleLoadProject(HttpExchange ex) throws IOException {
        try {
            sendJson(ex, 200, Map.of("success", true, "message", "Use the Load Project button in the UI"));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleSearch(HttpExchange ex) throws IOException {
        try {
            String query = ex.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);
            String q = params.getOrDefault("q", "");
            List<HistoryEntry> results = historyManager.search(q);
            int limit = Integer.parseInt(params.getOrDefault("limit", "50"));
            List<HistoryEntry> paged = results.subList(0, Math.min(limit, results.size()));
            sendJson(ex, 200, Map.of("query", q, "count", results.size(), "results", paged));
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        try {
            Map<String, Object> statusMap = new LinkedHashMap<>();
            statusMap.put("status", "running");
            statusMap.put("version", "1.0.0");
            statusMap.put("host", currentHost);
            statusMap.put("port", currentPort);
            statusMap.put("historySize", historyManager.size());
            sendJson(ex, 200, statusMap);
        } catch (Exception e) {
            sendJson(ex, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
        }
    }

    private void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = mapper.writeValueAsBytes(obj);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    params.put(
                        java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                    );
                } catch (Exception e) {
                    params.put(kv[0], kv[1]);
                }
            }
        }
        return params;
    }

}
