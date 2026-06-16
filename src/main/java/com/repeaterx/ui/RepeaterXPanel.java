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
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
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

    // Burp-style tab "+" button index sentinel
    private static final String PLUS_ID = "__plus__";

    public RepeaterXPanel(MontoyaApi api, HistoryManager historyManager, RequestSender requestSender,
                          ProjectManager projectManager, ApiServer apiServer) {
        this.api = api;
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        this.projectManager = projectManager;
        apiServer.setTabOperations(this);
        apiServer.setTabsSupplier(() -> new ArrayList<>(getAllTabData()));
        initUI();
        registerKeyBindings();
        tryAutoLoadProject();
        if (tabs.isEmpty()) createNewTab("Tab 1", null);
    }

    private void initUI() {
        setLayout(new BorderLayout(0, 0));
        add(buildToolbar(), BorderLayout.NORTH);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        add(tabbedPane, BorderLayout.CENTER);
    }

    private void registerKeyBindings() {
        // Ctrl+K → send current tab
        KeyStroke ctrlK = KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ctrlK, "sendCurrentTab");
        getActionMap().put("sendCurrentTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                RepeaterTab current = getCurrentTab();
                if (current != null) current.triggerSend();
            }
        });
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        bar.setBorder(new EmptyBorder(2, 5, 2, 5));

        JButton newTabBtn = new JButton("+ New Tab");
        newTabBtn.addActionListener(e -> createNewTab("Tab " + tabCounter, null));

        JButton dupTabBtn = new JButton("Duplicate");
        dupTabBtn.addActionListener(e -> duplicateCurrentTab());

        JButton renameBtn = new JButton("Rename");
        renameBtn.addActionListener(e -> renameCurrentTab());

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
        apiLabel.setToolTipText("REST API for AI agents — see README for endpoints");

        bar.add(newTabBtn);
        bar.add(dupTabBtn);
        bar.add(renameBtn);
        bar.add(new JSeparator(JSeparator.VERTICAL));
        bar.add(saveBtn);
        bar.add(loadBtn);
        bar.add(exportBtn);
        bar.add(searchBtn);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(apiLabel);
        return bar;
    }

    // ── Tab lifecycle ─────────────────────────────────────────────────────────

    RepeaterTab createNewTab(String name, String rawRequest) {
        String id = UUID.randomUUID().toString();
        TabData tabData = new TabData(id, name);
        RepeaterTab tab = new RepeaterTab(api, tabData, historyManager, requestSender);

        // Wire title listener so tab updates its own header after first send
        tab.setTitleListener((tabId, newTitle) -> SwingUtilities.invokeLater(() -> {
            int idx = tabbedPane.indexOfComponent(tabs.get(tabId));
            if (idx >= 0) {
                tabbedPane.setTabComponentAt(idx, buildTabHeader(newTitle, tabId));
                tabbedPane.setTitleAt(idx, newTitle);
            }
        }));

        tabs.put(id, tab);
        tabbedPane.addTab(name, tab);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, buildTabHeader(name, id));
        tabbedPane.setSelectedIndex(idx);
        tabCounter++;

        if (rawRequest != null && !rawRequest.isBlank()) {
            tab.setRequest(rawRequest, "", 443, true);
        }
        return tab;
    }

    private JPanel buildTabHeader(String name, String tabId) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
        header.setOpaque(false);

        JLabel label = new JLabel(name);
        label.setFont(label.getFont().deriveFont(11f));

        JButton close = new JButton("×");
        close.setFont(close.getFont().deriveFont(Font.BOLD, 11f));
        close.setBorder(new EmptyBorder(0, 4, 0, 2));
        close.setContentAreaFilled(false);
        close.setFocusPainted(false);
        close.setForeground(Color.GRAY);
        close.setToolTipText("Close tab");
        close.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { close.setForeground(Color.RED); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { close.setForeground(Color.GRAY); }
        });
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
        String newId = UUID.randomUUID().toString();
        String newName = orig.getName() + " (copy)";
        TabData copy = new TabData(newId, newName);
        copy.setCurrentRequest(orig.getCurrentRequest());
        copy.setNotes(orig.getNotes() != null ? orig.getNotes() : "");
        if (orig.getMetadata() != null) copy.setMetadata(new java.util.HashMap<>(orig.getMetadata()));

        RepeaterTab newTab = new RepeaterTab(api, copy, historyManager, requestSender);
        newTab.setTitleListener((tabId, newTitle) -> SwingUtilities.invokeLater(() -> {
            int idx = tabbedPane.indexOfComponent(tabs.get(tabId));
            if (idx >= 0) {
                tabbedPane.setTabComponentAt(idx, buildTabHeader(newTitle, tabId));
                tabbedPane.setTitleAt(idx, newTitle);
            }
        }));
        tabs.put(newId, newTab);
        tabbedPane.addTab(newName, newTab);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, buildTabHeader(newName, newId));
        tabbedPane.setSelectedIndex(idx);
        tabCounter++;
    }

    private void renameCurrentTab() {
        RepeaterTab current = getCurrentTab();
        if (current == null) return;
        String newName = JOptionPane.showInputDialog(this, "Tab name:", current.getTabData().getName());
        if (newName != null && !newName.isBlank()) {
            current.getTabData().setName(newName);
            int idx = tabbedPane.indexOfComponent(current);
            if (idx >= 0) {
                tabbedPane.setTabComponentAt(idx, buildTabHeader(newName, current.getTabId()));
                tabbedPane.setTitleAt(idx, newName);
            }
        }
    }

    RepeaterTab getCurrentTab() {
        Component c = tabbedPane.getSelectedComponent();
        return (c instanceof RepeaterTab) ? (RepeaterTab) c : null;
    }

    // ── Project I/O ───────────────────────────────────────────────────────────

    private void saveProject() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("repeaterx-project.json"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                projectManager.saveProject(buildProjectData(), fc.getSelectedFile().toPath());
                JOptionPane.showMessageDialog(this, "Project saved.");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void loadProject() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                loadProjectData(projectManager.loadProject(fc.getSelectedFile().toPath()));
                JOptionPane.showMessageDialog(this, "Project loaded.");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Load failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void showExportMenu(JButton source) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem json = new JMenuItem("Export Tab as JSON");
        JMenuItem txt = new JMenuItem("Export Tab as TXT");
        JMenuItem hist = new JMenuItem("Export History as JSON");
        json.addActionListener(e -> exportTab("json"));
        txt.addActionListener(e -> exportTab("txt"));
        hist.addActionListener(e -> exportHistory());
        menu.add(json); menu.add(txt); menu.addSeparator(); menu.add(hist);
        menu.show(source, 0, source.getHeight());
    }

    private void exportTab(String fmt) {
        RepeaterTab current = getCurrentTab();
        if (current == null) { JOptionPane.showMessageDialog(this, "No tab selected."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("repeaterx-tab." + fmt));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            TabData tab = current.getTabData();
            String content;
            if ("json".equals(fmt)) {
                content = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(tab);
            } else {
                StringBuilder sb = new StringBuilder("=== Tab: ").append(tab.getName()).append(" ===\n\n");
                if (tab.getCurrentRequest() != null)
                    sb.append("--- REQUEST ---\n").append(tab.getCurrentRequest().getRawRequest()).append("\n\n");
                if (tab.getLatestResponse() != null)
                    sb.append("--- RESPONSE ---\n").append(tab.getLatestResponse().getRawResponse()).append("\n");
                if (tab.getNotes() != null && !tab.getNotes().isBlank())
                    sb.append("\n--- NOTES ---\n").append(tab.getNotes());
                content = sb.toString();
            }
            Files.writeString(fc.getSelectedFile().toPath(), content);
            JOptionPane.showMessageDialog(this, "Exported.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportHistory() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("repeaterx-history.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String content = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(historyManager.getAllHistory());
            Files.writeString(fc.getSelectedFile().toPath(), content);
            JOptionPane.showMessageDialog(this, "History exported.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showSearchDialog() {
        JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Global Search", false);
        dlg.setSize(860, 520);
        dlg.setLocationRelativeTo(this);
        dlg.add(new SearchPanel(historyManager));
        dlg.setVisible(true);
    }

    // ── Project data helpers ──────────────────────────────────────────────────

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
            for (TabData td : data.getTabs()) {
                final String tid = td.getId();
                RepeaterTab tab = new RepeaterTab(api, td, historyManager, requestSender);
                tab.setTitleListener((tabId, newTitle) -> SwingUtilities.invokeLater(() -> {
                    int idx = tabbedPane.indexOfComponent(tabs.get(tabId));
                    if (idx >= 0) {
                        tabbedPane.setTabComponentAt(idx, buildTabHeader(newTitle, tabId));
                        tabbedPane.setTitleAt(idx, newTitle);
                    }
                }));
                tabs.put(td.getId(), tab);
                tabbedPane.addTab(td.getName(), tab);
                int idx = tabbedPane.getTabCount() - 1;
                tabbedPane.setTabComponentAt(idx, buildTabHeader(td.getName(), td.getId()));
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
            } catch (Exception ignored) {}
        }
    }

    public void sendToRepeaterX(String rawRequest, String host, int port, boolean https) {
        RepeaterTab tab = createNewTab("Tab " + tabCounter, null);
        tab.setRequest(rawRequest, host, port, https);
    }

    public void autoSave() {
        try { projectManager.saveProject(buildProjectData()); } catch (Exception ignored) {}
    }

    // ── ApiServer.TabOperations ───────────────────────────────────────────────

    @Override
    public TabData createTab(String name, String rawRequest) {
        final RepeaterTab[] result = {null};
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                result[0] = createNewTab(name, rawRequest);
            } else {
                SwingUtilities.invokeAndWait(() -> result[0] = createNewTab(name, rawRequest));
            }
        } catch (Exception ignored) {}
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
        // reserved for future programmatic send
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
