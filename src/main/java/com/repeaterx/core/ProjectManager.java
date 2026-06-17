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
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /** ~/Documents/RepeaterX/ — created on first use */
    private static final Path DEFAULT_DIR = resolveDefaultDir();

    private static Path resolveDefaultDir() {
        Path docs = Paths.get(System.getProperty("user.home"), "Documents", "RepeaterX");
        try { Files.createDirectories(docs); } catch (IOException ignored) {}
        return docs;
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RepeaterX-AutoSave");
        t.setDaemon(true);
        return t;
    });

    private String      currentProjectName = "default";
    private final List<SaveListener> saveListeners = new ArrayList<>();

    public interface SaveListener {
        void onSave(Path path);
        void onLoad(ProjectData data);
    }

    /** Path for a given project name inside the default directory. */
    public static Path pathFor(String projectName) {
        String safe = projectName.replaceAll("[^a-zA-Z0-9 _\\-]", "_").trim();
        if (safe.isEmpty()) safe = "default";
        return DEFAULT_DIR.resolve(safe + ".json");
    }

    public Path getCurrentProjectPath() {
        return pathFor(currentProjectName);
    }

    public String getCurrentProjectName() { return currentProjectName; }
    public void   setCurrentProjectName(String name) { this.currentProjectName = name; }
    public Path   getDefaultDir()         { return DEFAULT_DIR; }

    /** Start auto-saving every 5 minutes. */
    public void startAutoSave(Runnable save) {
        scheduler.scheduleAtFixedRate(() -> {
            try { save.run(); }
            catch (Exception ignored) {}
        }, 5, 5, TimeUnit.MINUTES);
    }

    public void saveProject(ProjectData data) throws IOException {
        saveProject(data, currentProjectName);
    }

    public void saveProject(ProjectData data, String projectName) throws IOException {
        currentProjectName = projectName;
        data.setProjectName(projectName);
        data.setLastSaved(System.currentTimeMillis());
        Path path = pathFor(projectName);
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), data);
        saveListeners.forEach(l -> l.onSave(path));
    }

    /** Save to an explicit path (used by legacy file-chooser flow). */
    public void saveProject(ProjectData data, Path path) throws IOException {
        data.setLastSaved(System.currentTimeMillis());
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), data);
        saveListeners.forEach(l -> l.onSave(path));
    }

    public ProjectData loadProject() throws IOException {
        return loadProject(getCurrentProjectPath());
    }

    public ProjectData loadProject(Path path) throws IOException {
        if (!Files.exists(path)) return new ProjectData();
        ProjectData data = MAPPER.readValue(path.toFile(), ProjectData.class);
        // Derive project name from filename if not stored
        String stem = path.getFileName().toString().replaceAll("\\.json$", "");
        if (data.getProjectName() == null || data.getProjectName().isBlank())
            data.setProjectName(stem);
        currentProjectName = data.getProjectName();
        saveListeners.forEach(l -> l.onLoad(data));
        return data;
    }

    public boolean hasExistingProject() {
        return Files.exists(getCurrentProjectPath());
    }

    public void addSaveListener(SaveListener l) { saveListeners.add(l); }

    public void shutdown() { scheduler.shutdown(); }
}
