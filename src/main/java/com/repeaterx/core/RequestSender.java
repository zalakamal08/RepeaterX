package com.repeaterx.core;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.repeaterx.model.RequestData;
import com.repeaterx.model.ResponseData;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RequestSender {
    private final MontoyaApi api;
    private final ExecutorService executor;

    public RequestSender(MontoyaApi api) {
        this.api = api;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "RepeaterX-Sender");
            t.setDaemon(true);
            return t;
        });
    }

    public Future<SendResult> sendAsync(RequestData requestData, SendCallback callback) {
        return executor.submit(() -> {
            SendResult result = send(requestData);
            if (callback != null) callback.onResult(result);
            return result;
        });
    }

    public SendResult send(RequestData requestData) {
        long startTime = System.currentTimeMillis();
        try {
            HttpService service = HttpService.httpService(
                requestData.getHost(),
                requestData.getPort(),
                requestData.isHttps()
            );
            HttpRequest request = buildHttpRequest(requestData, service);
            HttpRequestResponse reqResp = api.http().sendRequest(request);
            long elapsed = System.currentTimeMillis() - startTime;
            HttpResponse httpResp = reqResp.response();
            if (httpResp == null) {
                return SendResult.error("No response received", elapsed);
            }
            ResponseData responseData = convertResponse(httpResp, elapsed);
            return SendResult.success(responseData, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return SendResult.error("Error: " + e.getMessage(), elapsed);
        }
    }

    private HttpRequest buildHttpRequest(RequestData req, HttpService service) {
        String raw = req.getRawRequest();
        if (raw != null && !raw.isBlank()) {
            return HttpRequest.httpRequest(service, ByteArray.byteArray(raw.getBytes()));
        }
        // Build raw request from components
        String path = "/";
        String urlStr = req.getUrl();
        if (urlStr != null && !urlStr.isBlank()) {
            try {
                URL u = new URL(urlStr);
                path = u.getFile();
                if (path == null || path.isEmpty()) path = "/";
            } catch (Exception e) {
                if (urlStr.startsWith("/")) path = urlStr;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(req.getMethod() != null ? req.getMethod() : "GET").append(" ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(req.getHost());
        if ((req.isHttps() && req.getPort() != 443) || (!req.isHttps() && req.getPort() != 80)) {
            sb.append(":").append(req.getPort());
        }
        sb.append("\r\n");
        if (req.getHeaders() != null) {
            for (String[] header : req.getHeaders()) {
                if (header.length >= 2 && !"host".equalsIgnoreCase(header[0])) {
                    sb.append(header[0]).append(": ").append(header[1]).append("\r\n");
                }
            }
        }
        String body = req.getBody() != null ? req.getBody() : "";
        if (!body.isEmpty()) {
            sb.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
        }
        sb.append("\r\n").append(body);
        return HttpRequest.httpRequest(service, ByteArray.byteArray(sb.toString().getBytes()));
    }

    private ResponseData convertResponse(HttpResponse resp, long elapsed) {
        String id = UUID.randomUUID().toString();
        List<String[]> headers = new ArrayList<>();
        if (resp.headers() != null) {
            resp.headers().forEach(h -> headers.add(new String[]{h.name(), h.value()}));
        }
        String body = resp.bodyToString();
        byte[] rawBytes = resp.toByteArray().getBytes();
        String rawResponse = new String(rawBytes);
        String statusMessage = resp.reasonPhrase() != null ? resp.reasonPhrase() : "";
        return new ResponseData(id, resp.statusCode(), statusMessage, headers, body, rawResponse, elapsed);
    }

    public void shutdown() {
        executor.shutdown();
    }

    public interface SendCallback {
        void onResult(SendResult result);
    }

    public static class SendResult {
        private final ResponseData response;
        private final String error;
        private final long elapsed;
        private final boolean success;

        private SendResult(ResponseData response, String error, long elapsed, boolean success) {
            this.response = response;
            this.error = error;
            this.elapsed = elapsed;
            this.success = success;
        }

        public static SendResult success(ResponseData resp, long elapsed) {
            return new SendResult(resp, null, elapsed, true);
        }

        public static SendResult error(String err, long elapsed) {
            return new SendResult(null, err, elapsed, false);
        }

        public boolean isSuccess() { return success; }
        public ResponseData getResponse() { return response; }
        public String getError() { return error; }
        public long getElapsed() { return elapsed; }
    }
}
