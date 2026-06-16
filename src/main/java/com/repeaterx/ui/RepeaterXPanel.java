package com.repeaterx.ui;

import burp.api.montoya.MontoyaApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repeaterx.api.ApiServer;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.ProjectManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.model.ProjectData;
import com.repeaterx.model.TabData;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RepeaterXPanel extends JPanel implements ApiServer.TabOperations {
    private final MontoyaApi api;
    private final HistoryManager historyManager;
    private final RequestSender requestSender;
    private final ProjectManager projectManager;

    private JTabbedPane tabbedPane;
    private final Map<String, RepeaterTab> tabs = new LinkedHashMap<>();
    private int tabCounter = 1;

    public RepeaterXPanel(MontoyaApi api, HistoryManager historyManager, RequestSender requestSender,
                          ProjectManager projectManager, ApiServer apiServer) {
        this.api = api;
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        this.projectManager = projectManager;
        apiServer.setTabOperations(this);
        apiServer.setTabsSupplier(() -> new ArrayList<>(getAllTabData()));
        initUI();
        tryAutoLoadProject();
        if (tabs.isEmpty()) createNewTab("Tab 1", null);
    }

    private void initUI() {
        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabbedPane, BorderLayout.CENTER);
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        bar.setBorder(new EmptyBorder(2, 5, 2, 5));

        JButton newTabBtn = new JButton("+ New Tab");
        newTabBtn.addActionListener(e -> createNewTab("Tab " + tabCounter, null));

        JButton dupTabBtn = new JButton("Duplicate");
        dupTabBtn.addActionListener(e -> duplicateCurrentTab());

        JButton renameTabBtn = new JButton("Rename");
        renameTabBtn.addActionListener(e -> renameCurrentTab());

        JButton saveBtn = new JButton("Save Project");
        saveBtn.addActionListener(e -> saveProject());

        JButton loadBtn = new JButton("Load Project");
        loadBtn.addActionListener(e -> loadProject());

        JButton exportBtn = new JButton("Export");
        exportBtn.addActionListener(e -> showExportMenu(exportBtn));

        JButton searchBtn = new JButton("Search");
        searchBtn.addActionListener(e -> showSearchDialog());

        JLabel apiLabel = new JLabel("  API: 127.0.0.1:7331");
        apiLabel.setForeground(new Color(0, 150, 0));
        apiLabel.setFont(apiLabel.getFont().deriveFont(Font.BOLD, 11f));

        bar.add(newTabBtn);
        bar.add(dupTabBtn);
        bar.add(renameTabBtn);
        bar.add(new JSeparator(JSeparator.VERTICAL));
        bar.add(saveBtn);
        bar.add(loadBtn);
        bar.add(exportBtn);
        bar.add(searchBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(apiLabel);
        return bar;
    }

    RepeaterTab createNewTab(String name, String rawRequest) {
        String id = UUID.randomUUID().toString();
        TabData tabData = new TabData(id, name);
        RepeaterTab tab = new RepeaterTab(api, tabData, historyManager, requestSender);
        tabs.put(id, tab);
        JPanel tabHeader = buildTabHeader(name, id);
        tabbedPane.addTab(name, tab);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, tabHeader);
        tabbedPane.setSelectedIndex(idx);
        tabCounter++;
        if (rawRequest != null && !rawRequest.isBlank()) {
            tab.setRequest(rawRequest, "", 443, true);
        }
        return tab;
    }

    private JPanel buildTabHeader(String name, String tabId) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(name);
        JButton close = new JButton("x");
        close.setFont(close.getFont().deriveFont(9f));
        close.setBorder(new EmptyBorder(0, 3, 0, 3));
        close.setContentAreaFilled(false);
        close.setFocusPainted(false);
        close.addActionListener(e -> closeTab(tabId));
        header.add(label);
        header.add(close);
        return header;
    }

    void closeTab(String tabId) {
        if (tabs.size() <= 1) return;
        RepeaterTab tab = tabs.remove(tabId);
        if (tab != null) {
            int idx = tabbedPane.indexOfComponent(tab);
            if (idx >= 0) tabbedPane.removeTabAt(idx);
        }
    }

    private void duplicateCurrentTab() {
        RepeaterTab current = getCurrentTab();
        if (current == null) return;
        TabData orig = current.getTabData();
        String newName = orig.getName() + " (copy)";
        String newId = UUID.randomUUID().toString();
        TabData copy = new TabData(newId, newName);
        copy.setCurrentRequest(orig.getCurrentRequest());
        copy.setNotes(orig.getNotes() != null ? orig.getNotes() : "");
        if (orig.getMetadata() != null) copy.setMetadata(new java.util.HashMap<>(orig.getMetadata()));
        RepeaterTab newTab = new RepeaterTab(api, copy, historyManager, requestSender);
        tabs.put(newId, newTab);
        JPanel header = buildTabHeader(newName, newId);
        tabbedPane.addTab(newName, newTab);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, header);
        tabbedPane.setSelectedIndex(idx);
        tabCounter++;
    }

    private void renameCurrentTab() {
        RepeaterTab current = getCurrentTab();
        if (current == null) return;
        String newName = JOptionPane.showInputDialog(this, "New tab name:", current.getTabData().getName());
        if (newName != null && !newName.isBlank()) {
            current.getTabData().setName(newName);
            int idx = tabbedPane.indexOfComponent(current);
            if (idx >= 0) {
                tabbedPane.setTabComponentAt(idx, buildTabHeader(newName, current.getTabId()));
                tabbedPane.setTitleAt(idx, newName);
            }
        }
    }

    private RepeaterTab getCurrentTab() {
        Component selected = tabbedPane.getSelectedComponent();
        return (selected instanceof RepeaterTab) ? (RepeaterTab) selected : null;
    }

    private void saveProject() {
        try {
            ProjectData data = buildProjectData();
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("repeaterx-project.json"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                projectManager.saveProject(data, fc.getSelectedFile().toPath());
                JOptionPane.showMessageDialog(this, "Project saved to: " + fc.getSelectedFile().getPath());
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadProject() {
        try {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                ProjectData data = projectManager.loadProject(fc.getSelectedFile().toPath());
                loadProjectData(data);
                JOptionPane.showMessageDialog(this, "Project loaded successfully.");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Load failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showExportMenu(JButton source) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem exportJson = new JMenuItem("Export Tab as JSON");
        JMenuItem exportTxt = new JMenuItem("Export Tab as TXT");
        JMenuItem exportHistory = new JMenuItem("Export History as JSON");
        exportJson.addActionListener(e -> exportCurrentTab("json"));
        exportTxt.addActionListener(e -> exportCurrentTab("txt"));
        exportHistory.addActionListener(e -> exportHistory());
        menu.add(exportJson);
        menu.add(exportTxt);
        menu.addSeparator();
        menu.add(exportHistory);
        menu.show(source, 0, source.getHeight());
    }

    private void exportCurrentTab(String format) {
        RepeaterTab current = getCurrentTab();
        if (current == null) { JOptionPane.showMessageDialog(this, "No tab selected."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("repeaterx-tab." + format));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                TabData tab = current.getTabData();
                String content;
                if ("json".equals(format)) {
                    ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                    content = om.writeValueAsString(tab);
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== RepeaterX Tab: ").append(tab.getName()).append(" ===\n\n");
                    if (tab.getCurrentRequest() != null)
                        sb.append("--- REQUEST ---\n").append(tab.getCurrentRequest().getRawRequest()).append("\n\n");
                    if (tab.getLatestResponse() != null)
                        sb.append("--- RESPONSE ---\n").append(tab.getLatestResponse().getRawResponse()).append("\n");
                    if (tab.getNotes() != null && !tab.getNotes().isBlank())
                        sb.append("\n--- NOTES ---\n").append(tab.getNotes()).append("\n");
                    content = sb.toString();
                }
                Files.writeString(fc.getSelectedFile().toPath(), content);
                JOptionPane.showMessageDialog(this, "Exported successfully.");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportHistory() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("repeaterx-history.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                String content = om.writeValueAsString(historyManager.getAllHistory());
                Files.writeString(fc.getSelectedFile().toPath(), content);
                JOptionPane.showMessageDialog(this, "History exported successfully.");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void showSearchDialog() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Global Search", false);
        dialog.setSize(800, 500);
        dialog.setLocationRelativeTo(this);
        dialog.add(new com.repeaterx.ui.SearchPanel(historyManager));
        dialog.setVisible(true);
    }

    private ProjectData buildProjectData() {
        ProjectData data = new ProjectData();
        data.setTabs(getAllTabData());
        return data;
    }

    List<TabData> getAllTabData() {
        List<TabData> result = new ArrayList<>();
        for (RepeaterTab t : tabs.values()) result.add(t.getTabData());
        return result;
    }

    private void loadProjectData(ProjectData data) {
        tabbedPane.removeAll();
        tabs.clear();
        tabCounter = 1;
        if (data.getTabs() != null && !data.getTabs().isEmpty()) {
            for (TabData tabData : data.getTabs()) {
                RepeaterTab tab = new RepeaterTab(api, tabData, historyManager, requestSender);
                tabs.put(tabData.getId(), tab);
                JPanel header = buildTabHeader(tabData.getName(), tabData.getId());
                tabbedPane.addTab(tabData.getName(), tab);
                int idx = tabbedPane.getTabCount() - 1;
                tabbedPane.setTabComponentAt(idx, header);
                tabCounter++;
            }
        } else {
            createNewTab("Tab 1", null);
        }
    }

    private void tryAutoLoadProject() {
        if (projectManager.hasExistingProject()) {
            try {
                ProjectData data = projectManager.loadProject();
                if (data != null && data.getTabs() != null && !data.getTabs().isEmpty()) {
                    loadProjectData(data);
                }
            } catch (Exception e) {
                // ignore auto-load errors
            }
        }
    }

    public void sendToRepeaterX(String rawRequest, String host, int port, boolean https) {
        String name = "Tab " + tabCounter;
        RepeaterTab tab = createNewTab(name, null);
        tab.setRequest(rawRequest, host, port, https);
    }

    public void autoSave() {
        try {
            ProjectData data = buildProjectData();
            projectManager.saveProject(data);
        } catch (Exception e) {
            // ignore auto-save errors
        }
    }

    // ── ApiServer.TabOperations ──────────────────────────────────────────────

    @Override
    public TabData createTab(String name, String rawRequest) {
        final RepeaterTab[] result = new RepeaterTab[1];
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                result[0] = createNewTab(name, rawRequest);
            } else {
                SwingUtilities.invokeAndWait(() -> result[0] = createNewTab(name, rawRequest));
            }
        } catch (Exception e) {
            return null;
        }
        return result[0] != null ? result[0].getTabData() : null;
    }

    @Override
    public boolean deleteTab(String id) {
        if (!tabs.containsKey(id)) return false;
        SwingUtilities.invokeLater(() -> closeTab(id));
        return true;
    }

    @Override
    public TabData getTab(String id) {
        RepeaterTab tab = tabs.get(id);
        return tab != null ? tab.getTabData() : null;
    }

    @Override
    public void sendInTab(String id, Runnable callback) {
        // placeholder for future programmatic send
    }

    @Override
    public List<TabData> getAllTabs() {
        return getAllTabData();
    }

    @Override
    public TabData duplicateTab(String id) {
        RepeaterTab orig = tabs.get(id);
        if (orig == null) return null;
        SwingUtilities.invokeLater(this::duplicateCurrentTab);
        return orig.getTabData();
    }
}
