package com.repeaterx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TabData {
    private String id;
    private String name;
    private RequestData currentRequest;
    private ResponseData latestResponse;
    private List<HistoryEntry> history;
    private String notes;
    private Map<String, Object> metadata;
    private long createdAt;
    private long updatedAt;

    public TabData() {
        this.history = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.notes = "";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public TabData(String id, String name) {
        this();
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public RequestData getCurrentRequest() { return currentRequest; }
    public void setCurrentRequest(RequestData currentRequest) { this.currentRequest = currentRequest; }
    public ResponseData getLatestResponse() { return latestResponse; }
    public void setLatestResponse(ResponseData latestResponse) { this.latestResponse = latestResponse; }
    public List<HistoryEntry> getHistory() { return history; }
    public void setHistory(List<HistoryEntry> history) { this.history = history; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public void addHistoryEntry(HistoryEntry entry) {
        if (history == null) history = new ArrayList<>();
        history.add(entry);
        this.updatedAt = System.currentTimeMillis();
    }
}
