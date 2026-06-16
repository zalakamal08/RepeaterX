package com.repeaterx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectData {
    private String version;
    private List<TabData> tabs;
    private Map<String, Object> settings;
    private long lastSaved;
    private String projectName;

    public ProjectData() {
        this.version = "1.0.0";
        this.tabs = new ArrayList<>();
        this.settings = new HashMap<>();
        this.lastSaved = System.currentTimeMillis();
        this.projectName = "RepeaterX Project";
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<TabData> getTabs() { return tabs; }
    public void setTabs(List<TabData> tabs) { this.tabs = tabs; }
    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> settings) { this.settings = settings; }
    public long getLastSaved() { return lastSaved; }
    public void setLastSaved(long lastSaved) { this.lastSaved = lastSaved; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
}
