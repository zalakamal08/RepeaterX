package com.repeaterx;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.repeaterx.api.ApiServer;
import com.repeaterx.burp.ContextMenuHandler;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.ProjectManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.ui.RepeaterXPanel;
import javax.swing.SwingUtilities;

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
        apiServer = new ApiServer(historyManager, requestSender, projectManager);

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

        startApiServer(api);

        projectManager.startAutoSave(() -> {
            if (panel != null) {
                SwingUtilities.invokeLater(() -> panel.autoSave());
            }
        });

        api.extension().registerUnloadingHandler(() -> {
            apiServer.stop();
            requestSender.shutdown();
            projectManager.shutdown();
            api.logging().logToOutput("RepeaterX unloaded.");
        });

        api.logging().logToOutput("RepeaterX v1.0.0 loaded. API listening on 127.0.0.1:7331");
    }

    private void startApiServer(MontoyaApi api) {
        Thread apiThread = new Thread(() -> {
            try {
                apiServer.start();
            } catch (Exception e) {
                api.logging().logToError("RepeaterX API server failed to start: " + e.getMessage());
            }
        }, "RepeaterX-API");
        apiThread.setDaemon(true);
        apiThread.start();
    }
}
