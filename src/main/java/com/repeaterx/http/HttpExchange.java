package com.repeaterx.http;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Request/response abstraction for SimpleHttpServer.
 * Mirrors the API of com.sun.net.httpserver.HttpExchange so existing
 * handler code needs minimal changes.
 */
public class HttpExchange {

    private final String              method;
    private final URI                 uri;
    private final Map<String, String> requestHeaders;
    private final InputStream         requestBody;
    private final Socket              socket;
    private final ResponseHeaders     responseHeaders = new ResponseHeaders();

    private OutputStream responseBody;
    private boolean      streaming;
    private boolean      responseSent;

    HttpExchange(String method, URI uri, Map<String, String> requestHeaders,
                 byte[] bodyBytes, Socket socket) {
        this.method         = method;
        this.uri            = uri;
        this.requestHeaders = requestHeaders;
        this.requestBody    = new ByteArrayInputStream(bodyBytes);
        this.socket         = socket;
    }

    // ── Request ───────────────────────────────────────────────────────────────

    public String              getRequestMethod()  { return method; }
    public URI                 getRequestURI()     { return uri; }
    public InputStream         getRequestBody()    { return requestBody; }
    public Map<String, String> getRequestHeaders() { return requestHeaders; }

    // ── Response ──────────────────────────────────────────────────────────────

    public ResponseHeaders getResponseHeaders() { return responseHeaders; }
    public OutputStream    getResponseBody()    { return responseBody; }
    public boolean         isStreaming()        { return streaming; }

    /**
     * Send HTTP status line + headers.
     *
     * contentLength == -1 → no body (204, 202 ack, etc.), auto-close
     * contentLength ==  0 → streaming (SSE), keep connection open
     * contentLength >   0 → fixed-size body
     */
    public void sendResponseHeaders(int status, long contentLength) throws IOException {
        if (responseSent) return;
        responseSent = true;
        streaming = (contentLength == 0);

        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");

        for (Map.Entry<String, String> h : responseHeaders.entries()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }

        if (contentLength == -1) {
            sb.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
        } else if (contentLength == 0) {
            sb.append("Connection: keep-alive\r\n\r\n");
        } else {
            sb.append("Content-Length: ").append(contentLength).append("\r\nConnection: close\r\n\r\n");
        }

        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        responseBody = out;
    }

    /** Close the underlying socket (only used for non-streaming responses). */
    public void close() throws IOException {
        if (!streaming) socket.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default  -> "Status " + code;
        };
    }
}
