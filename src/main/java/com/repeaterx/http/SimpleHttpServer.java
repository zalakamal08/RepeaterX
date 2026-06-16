package com.repeaterx.http;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Minimal HTTP server backed by ServerSocket — no JDK-internal APIs.
 * Replaces com.sun.net.httpserver.HttpServer, which Burp's class loader
 * cannot see at runtime.
 */
public class SimpleHttpServer {

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private ServerSocket      serverSocket;
    private ExecutorService   executor;
    private volatile boolean  running;

    // LinkedHashMap preserves registration order for prefix matching
    private final Map<String, Handler> routes = new LinkedHashMap<>();

    private SimpleHttpServer() {}

    public static SimpleHttpServer create(String host, int port) throws IOException {
        SimpleHttpServer s = new SimpleHttpServer();
        s.serverSocket = new ServerSocket();
        s.serverSocket.setReuseAddress(true);
        InetAddress addr = "0.0.0.0".equals(host) ? null : InetAddress.getByName(host);
        s.serverSocket.bind(new InetSocketAddress(addr, port), 32);
        s.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "repeaterx-http");
            t.setDaemon(true);
            return t;
        });
        return s;
    }

    public void createContext(String path, Handler handler) {
        routes.put(path, handler);
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "repeaterx-http-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop(int ignored) {
        running = false;
        try { serverSocket.close(); } catch (IOException e) { /* shutting down */ }
        executor.shutdownNow();
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                executor.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) System.err.println("[RepeaterX] accept error: " + e.getMessage());
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            InputStream raw = socket.getInputStream();

            // ── Parse request line ────────────────────────────────────────────
            String requestLine = readLine(raw);
            if (requestLine == null || requestLine.isBlank()) { socket.close(); return; }

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) { socket.close(); return; }
            String method = parts[0];
            String rawUri = parts[1];

            // ── Parse headers ─────────────────────────────────────────────────
            Map<String, String> reqHeaders = new LinkedHashMap<>();
            String line;
            while ((line = readLine(raw)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) reqHeaders.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT),
                                           line.substring(c + 1).trim());
            }

            // ── Read body (Content-Length only) ───────────────────────────────
            int contentLength = 0;
            String clStr = reqHeaders.get("content-length");
            if (clStr != null) try { contentLength = Integer.parseInt(clStr.trim()); } catch (NumberFormatException ignored) {}
            byte[] bodyBytes = readExact(raw, contentLength);

            // ── Route ─────────────────────────────────────────────────────────
            URI uri;
            try { uri = new URI(rawUri); } catch (URISyntaxException e) { uri = URI.create("/"); }

            Handler handler = findHandler(uri.getPath());
            if (handler == null) { write404(socket); return; }

            HttpExchange ex = new HttpExchange(method, uri, reqHeaders, bodyBytes, socket);
            try {
                handler.handle(ex);
            } finally {
                if (!ex.isStreaming()) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }

        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private Handler findHandler(String path) {
        Handler exact = routes.get(path);
        if (exact != null) return exact;
        // Longest-prefix match
        String best = null;
        for (String key : routes.keySet()) {
            if (path.startsWith(key) && (best == null || key.length() > best.length())) best = key;
        }
        return best != null ? routes.get(best) : null;
    }

    // ── I/O helpers ───────────────────────────────────────────────────────────

    /** Reads a CRLF- or LF-terminated line. Returns null on EOF. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        return (b == -1 && sb.length() == 0) ? null : sb.toString();
    }

    private static byte[] readExact(InputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n == -1) break;
            off += n;
        }
        return buf;
    }

    private static void write404(Socket socket) throws IOException {
        String body = "{\"error\":\"not found\"}";
        String resp = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n"
            + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
        try (socket) { socket.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8)); }
    }
}
