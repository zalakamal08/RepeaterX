package com.repeaterx;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repeaterx.api.ApiServer;
import com.repeaterx.burp.ContextMenuHandler;
import com.repeaterx.core.ApiConfig;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.ProjectManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.ui.RepeaterXPanel;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RepeaterXExtension implements BurpExtension {
    private static MontoyaApi montoyaApi;
    private RepeaterXPanel panel;
    private ApiServer apiServer;
    private ProjectManager projectManager;
    private RequestSender requestSender;
    private HistoryManager historyManager;

    public static MontoyaApi api() { return montoyaApi; }

    @Override
    public void initialize(MontoyaApi api) {
        montoyaApi = api;
        api.extension().setName("RepeaterX");

        historyManager = new HistoryManager();
        requestSender = new RequestSender(api);
        projectManager = new ProjectManager();
        apiServer = new ApiServer(historyManager, requestSender);

        ApiConfig cfg = loadApiConfig(api);
        try { apiServer.restart(cfg.getHost(), cfg.getPort()); }
        catch (Exception e) { api.logging().logToError("RepeaterX API start failed: " + e.getMessage()); }

        SwingUtilities.invokeLater(() -> {
            try {
                panel = new RepeaterXPanel(api, historyManager, requestSender, projectManager, apiServer);
                api.userInterface().registerSuiteTab("RepeaterX", panel);
                api.userInterface().applyThemeToComponent(panel);
                api.userInterface().registerContextMenuItemsProvider(new ContextMenuHandler(panel));
                api.logging().logToOutput("RepeaterX UI initialized.");
            } catch (Exception e) {
                api.logging().logToError("RepeaterX UI init failed: " + e.getMessage());
            }
        });

        projectManager.startAutoSave(() -> {
            if (panel != null) SwingUtilities.invokeLater(() -> panel.autoSave());
        });

        api.extension().registerUnloadingHandler(() -> {
            // Auto-save project so no work is lost when Burp closes
            if (panel != null) {
                try {
                    SwingUtilities.invokeAndWait(() -> panel.autoSave());
                } catch (Exception ignored) {}
            }
            saveApiConfig(api, new ApiConfig(apiServer.getCurrentHost(), apiServer.getCurrentPort()));
            apiServer.stop();
            requestSender.shutdown();
            projectManager.shutdown();
            api.logging().logToOutput("RepeaterX: project auto-saved and unloaded.");
        });

        api.logging().logToOutput("RepeaterX v1.0.0 loaded. API on " + cfg.getHost() + ":" + cfg.getPort());
    }

    private Path configPath(MontoyaApi api) {
        return Paths.get(System.getProperty("user.home"), ".repeaterx", "api-config.json");
    }

    private ApiConfig loadApiConfig(MontoyaApi api) {
        try {
            Path p = configPath(api);
            if (Files.exists(p)) {
                return new ObjectMapper().readValue(p.toFile(), ApiConfig.class);
            }
        } catch (Exception ignored) {}
        return new ApiConfig();
    }

    private void saveApiConfig(MontoyaApi api, ApiConfig cfg) {
        try {
            Path p = configPath(api);
            Files.createDirectories(p.getParent());
            new ObjectMapper().writeValue(p.toFile(), cfg);
        } catch (Exception ignored) {}
    }
}
