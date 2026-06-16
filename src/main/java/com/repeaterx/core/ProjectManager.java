package com.repeaterx.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repeaterx.model.ProjectData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ProjectManager {
    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RepeaterX-AutoSave");
        t.setDaemon(true);
        return t;
    });

    private Path currentProjectPath;
    private boolean autoSaveEnabled = true;
    private final List<SaveListener> saveListeners = new ArrayList<>();

    public interface SaveListener {
        void onSave(Path path);
        void onLoad(ProjectData data);
    }

    public ProjectManager() {
        this.currentProjectPath = getDefaultProjectPath();
    }

    private Path getDefaultProjectPath() {
        String userHome = System.getProperty("user.home");
        Path repeaterDir = Paths.get(userHome, ".repeaterx");
        try {
            Files.createDirectories(repeaterDir);
        } catch (IOException e) {
            // ignore
        }
        return repeaterDir.resolve("project.json");
    }

    public void startAutoSave(Runnable dataProvider) {
        scheduler.scheduleAtFixedRate(() -> {
            if (autoSaveEnabled && dataProvider != null) {
                try {
                    dataProvider.run();
                } catch (Exception e) {
                    // ignore auto-save errors silently
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void saveProject(ProjectData data) throws IOException {
        saveProject(data, currentProjectPath);
    }

    public void saveProject(ProjectData data, Path path) throws IOException {
        data.setLastSaved(System.currentTimeMillis());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        mapper.writeValue(path.toFile(), data);
        currentProjectPath = path;
        saveListeners.forEach(l -> l.onSave(path));
    }

    public ProjectData loadProject() throws IOException {
        return loadProject(currentProjectPath);
    }

    public ProjectData loadProject(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new ProjectData();
        }
        ProjectData data = mapper.readValue(path.toFile(), ProjectData.class);
        saveListeners.forEach(l -> l.onLoad(data));
        return data;
    }

    public String serializeProject(ProjectData data) throws IOException {
        return mapper.writeValueAsString(data);
    }

    public void addSaveListener(SaveListener listener) {
        saveListeners.add(listener);
    }

    public Path getCurrentProjectPath() { return currentProjectPath; }
    public void setCurrentProjectPath(Path path) { this.currentProjectPath = path; }
    public boolean isAutoSaveEnabled() { return autoSaveEnabled; }
    public void setAutoSaveEnabled(boolean enabled) { this.autoSaveEnabled = enabled; }

    public void shutdown() {
        scheduler.shutdown();
    }

    public boolean hasExistingProject() {
        return currentProjectPath != null && Files.exists(currentProjectPath);
    }
}
