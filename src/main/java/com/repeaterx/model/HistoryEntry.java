package com.repeaterx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryEntry {
    private String id;
    private String tabId;
    private RequestData request;
    private ResponseData response;
    private long timestamp;
    private String notes;

    public HistoryEntry() {}

    public HistoryEntry(String id, String tabId, RequestData request, ResponseData response) {
        this.id = id;
        this.tabId = tabId;
        this.request = request;
        this.response = response;
        this.timestamp = System.currentTimeMillis();
        this.notes = "";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTabId() { return tabId; }
    public void setTabId(String tabId) { this.tabId = tabId; }
    public RequestData getRequest() { return request; }
    public void setRequest(RequestData request) { this.request = request; }
    public ResponseData getResponse() { return response; }
    public void setResponse(ResponseData response) { this.response = response; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
