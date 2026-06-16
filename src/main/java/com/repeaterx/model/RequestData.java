package com.repeaterx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestData {
    private String id;
    private String method;
    private String url;
    private String host;
    private int port;
    private boolean https;
    private List<String[]> headers;
    private String body;
    private String rawRequest;
    private long timestamp;

    public RequestData() {}

    public RequestData(String id, String method, String url, String host, int port, boolean https,
                       List<String[]> headers, String body, String rawRequest) {
        this.id = id;
        this.method = method;
        this.url = url;
        this.host = host;
        this.port = port;
        this.https = https;
        this.headers = headers != null ? headers : new ArrayList<>();
        this.body = body != null ? body : "";
        this.rawRequest = rawRequest != null ? rawRequest : "";
        this.timestamp = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public boolean isHttps() { return https; }
    public void setHttps(boolean https) { this.https = https; }
    public List<String[]> getHeaders() { return headers; }
    public void setHeaders(List<String[]> headers) { this.headers = headers; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getRawRequest() { return rawRequest; }
    public void setRawRequest(String rawRequest) { this.rawRequest = rawRequest; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
