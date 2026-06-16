package com.repeaterx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponseData {
    private String id;
    private int statusCode;
    private String statusMessage;
    private List<String[]> headers;
    private String body;
    private String rawResponse;
    private long responseTime;
    private int responseSize;
    private long timestamp;

    public ResponseData() {}

    public ResponseData(String id, int statusCode, String statusMessage, List<String[]> headers,
                        String body, String rawResponse, long responseTime) {
        this.id = id;
        this.statusCode = statusCode;
        this.statusMessage = statusMessage != null ? statusMessage : "";
        this.headers = headers != null ? headers : new ArrayList<>();
        this.body = body != null ? body : "";
        this.rawResponse = rawResponse != null ? rawResponse : "";
        this.responseTime = responseTime;
        this.responseSize = rawResponse != null ? rawResponse.getBytes().length : 0;
        this.timestamp = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public List<String[]> getHeaders() { return headers; }
    public void setHeaders(List<String[]> headers) { this.headers = headers; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }
    public long getResponseTime() { return responseTime; }
    public void setResponseTime(long responseTime) { this.responseTime = responseTime; }
    public int getResponseSize() { return responseSize; }
    public void setResponseSize(int responseSize) { this.responseSize = responseSize; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
