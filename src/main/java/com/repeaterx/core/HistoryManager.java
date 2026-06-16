package com.repeaterx.core;

import com.repeaterx.model.HistoryEntry;
import com.repeaterx.model.RequestData;
import com.repeaterx.model.ResponseData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class HistoryManager {
    private final List<HistoryEntry> globalHistory = new CopyOnWriteArrayList<>();
    private final Map<String, List<HistoryEntry>> tabHistory = new LinkedHashMap<>();
    private static final int MAX_GLOBAL_HISTORY = 10000;

    public HistoryEntry addEntry(String tabId, RequestData request, ResponseData response) {
        String id = UUID.randomUUID().toString();
        HistoryEntry entry = new HistoryEntry(id, tabId, request, response);
        globalHistory.add(0, entry);
        if (globalHistory.size() > MAX_GLOBAL_HISTORY) {
            globalHistory.remove(globalHistory.size() - 1);
        }
        synchronized (tabHistory) {
            tabHistory.computeIfAbsent(tabId, k -> new CopyOnWriteArrayList<>()).add(0, entry);
        }
        return entry;
    }

    public List<HistoryEntry> getAllHistory() {
        return Collections.unmodifiableList(new ArrayList<>(globalHistory));
    }

    public List<HistoryEntry> getTabHistory(String tabId) {
        synchronized (tabHistory) {
            List<HistoryEntry> list = tabHistory.get(tabId);
            return list != null ? Collections.unmodifiableList(new ArrayList<>(list)) : Collections.emptyList();
        }
    }

    public Optional<HistoryEntry> getEntryById(String id) {
        return globalHistory.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    public List<HistoryEntry> search(String query) {
        if (query == null || query.isBlank()) return getAllHistory();
        String q = query.toLowerCase();
        return globalHistory.stream()
            .filter(e -> {
                RequestData req = e.getRequest();
                ResponseData resp = e.getResponse();
                if (req != null && req.getUrl() != null && req.getUrl().toLowerCase().contains(q)) return true;
                if (req != null && req.getMethod() != null && req.getMethod().toLowerCase().contains(q)) return true;
                if (req != null && req.getBody() != null && req.getBody().toLowerCase().contains(q)) return true;
                if (resp != null && resp.getBody() != null && resp.getBody().toLowerCase().contains(q)) return true;
                if (e.getNotes() != null && e.getNotes().toLowerCase().contains(q)) return true;
                return false;
            })
            .collect(Collectors.toList());
    }

    public List<HistoryEntry> filterByStatus(int statusCode) {
        return globalHistory.stream()
            .filter(e -> e.getResponse() != null && e.getResponse().getStatusCode() == statusCode)
            .collect(Collectors.toList());
    }

    public List<HistoryEntry> filterByMethod(String method) {
        return globalHistory.stream()
            .filter(e -> e.getRequest() != null && method.equalsIgnoreCase(e.getRequest().getMethod()))
            .collect(Collectors.toList());
    }

    public void clearTabHistory(String tabId) {
        synchronized (tabHistory) {
            tabHistory.remove(tabId);
        }
        globalHistory.removeIf(e -> tabId.equals(e.getTabId()));
    }

    public void loadFromList(List<HistoryEntry> entries) {
        globalHistory.clear();
        synchronized (tabHistory) {
            tabHistory.clear();
        }
        for (HistoryEntry entry : entries) {
            globalHistory.add(entry);
            if (entry.getTabId() != null) {
                synchronized (tabHistory) {
                    tabHistory.computeIfAbsent(entry.getTabId(), k -> new CopyOnWriteArrayList<>()).add(entry);
                }
            }
        }
    }

    public int size() {
        return globalHistory.size();
    }
}
