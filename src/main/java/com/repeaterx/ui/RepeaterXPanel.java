package com.repeaterx.ui;

import burp.api.montoya.MontoyaApi;
import com.repeaterx.api.ApiServer;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.ProjectManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.model.ProjectData;
import com.repeaterx.model.TabData;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RepeaterXPanel extends JPanel implements ApiServer.TabOperations {
    private final MontoyaApi api;
    private final HistoryManager historyManager;
    private final RequestSender requestSender;
    private final ProjectManager projectManager;
    private final ApiServer apiServer;

    private JTabbedPane tabbedPane;
    private final Map<String, RepeaterTab> tabs = new LinkedHashMap<>();
    private int tabCounter = 1;

    private JLabel apiStatusLabel;

    public RepeaterXPanel(MontoyaApi api, HistoryManager historyManager, RequestSender requestSender,
                          ProjectManager projectManager, ApiServer apiServer) {
        this.api = api;
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        this.projectManager = projectManager;
        this.apiServer = apiServer;
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
        KeyStroke ctrlK = KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ctrlK, "sendCurrentTab");
        getActionMap().put("sendCurrentTab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                RepeaterTab current = getCurrentTab();
                if (current != null) current.triggerSend();
            }
        });
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBorder(new MatteBorder(0, 0, 1, 0, separatorColor()));

        // Left section: tab management
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        left.setBorder(new EmptyBorder(0, 6, 0, 0));

        JButton newTabBtn  = toolbarButton("+ New Tab",  "Open a new repeater tab  [Ctrl+T]");
        JButton dupTabBtn  = toolbarButton("Duplicate",  "Duplicate current tab");
        JButton renameBtn  = toolbarButton("Rename",     "Rename current tab");

        newTabBtn.addActionListener(e -> createNewTab("Tab " + tabCounter, null));
        dupTabBtn.addActionListener(e -> duplicateCurrentTab());
        renameBtn.addActionListener(e -> renameCurrentTab());

        left.add(newTabBtn);
        left.add(dupTabBtn);
        left.add(renameBtn);
        left.add(vSep());

        JButton saveBtn = toolbarButton("Save", "Save project to file");
        JButton loadBtn = toolbarButton("Load", "Load project from file");

        saveBtn.addActionListener(e -> saveProject());
        loadBtn.addActionListener(e -> loadProject());

        left.add(saveBtn);
        left.add(loadBtn);

        // Right section: API status + settings
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
        right.setBorder(new EmptyBorder(0, 0, 0, 8));

        apiStatusLabel = new JLabel();
        apiStatusLabel.setFont(apiStatusLabel.getFont().deriveFont(Font.BOLD, 11f));
        apiStatusLabel.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, new Color(0, 160, 80)),
            new EmptyBorder(1, 6, 1, 6)
        ));
        refreshApiLabel();

        JButton apiSettingsBtn = new JButton("⚙");
        apiSettingsBtn.setFont(apiSettingsBtn.getFont().deriveFont(Font.PLAIN, 13f));
        apiSettingsBtn.setFocusPainted(false);
        apiSettingsBtn.setContentAreaFilled(false);
        apiSettingsBtn.setBorderPainted(false);
        apiSettingsBtn.setPreferredSize(new Dimension(28, 22));
        apiSettingsBtn.setToolTipText("Configure API server host/port");
        apiSettingsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        apiSettingsBtn.addActionListener(e -> showApiSettingsDialog());

        right.add(apiStatusLabel);
        right.add(apiSettingsBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JButton toolbarButton(String text, String tip) {
        JButton btn = new JButton(text);
        btn.setFocusPainted(false);
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
        btn.setPreferredSize(new Dimension(btn.getPreferredSize().width, 26));
        btn.setToolTipText(tip);
        return btn;
    }

    private void refreshApiLabel() {
        if (apiStatusLabel == null) return;
        boolean running = apiServer.isRunning();
        String addr = apiServer.getCurrentHost() + ":" + apiServer.getCurrentPort();
        if (running) {
            apiStatusLabel.setText("  API  " + addr + "  ");
            apiStatusLabel.setForeground(new Color(0, 140, 60));
            apiStatusLabel.setBorder(new CompoundBorder(
                new MatteBorder(1, 1, 1, 1, new Color(0, 160, 80)),
                new EmptyBorder(1, 6, 1, 6)
            ));
        } else {
            apiStatusLabel.setText("  API  stopped  ");
            apiStatusLabel.setForeground(new Color(180, 0, 0));
            apiStatusLabel.setBorder(new CompoundBorder(
                new MatteBorder(1, 1, 1, 1, new Color(200, 0, 0)),
                new EmptyBorder(1, 6, 1, 6)
            ));
        }
    }

    private void showApiSettingsDialog() {
        JDialog dlg = new JDialog(
            (Frame) SwingUtilities.getWindowAncestor(this),
            "API Server Settings", true);
        dlg.setSize(400, 220);
        dlg.setLocationRelativeTo(this);
        dlg.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(16, 20, 12, 20));

        // Form
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField hostField = new JTextField(apiServer.getCurrentHost(), 16);
        JTextField portField = new JTextField(String.valueOf(apiServer.getCurrentPort()), 8);
        JLabel noteLabel = new JLabel(
            "<html><font color='gray' size='3'>Use <b>0.0.0.0</b> to bind all interfaces or <b>127.0.0.1</b> for local only.</font></html>");

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        form.add(new JLabel("Host:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(hostField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        form.add(new JLabel("Port:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(portField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        form.add(noteLabel, gbc);

        // Buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton applyBtn  = new JButton("Apply & Restart");
        JButton cancelBtn = new JButton("Cancel");
        applyBtn.setBackground(new Color(230, 90, 30));
        applyBtn.setForeground(Color.WHITE);
        applyBtn.setOpaque(true);
        applyBtn.setBorderPainted(false);
        applyBtn.setFocusPainted(false);
        buttons.add(cancelBtn);
        buttons.add(applyBtn);

        cancelBtn.addActionListener(e -> dlg.dispose());
        applyBtn.addActionListener(e -> {
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();
            if (host.isEmpty()) { JOptionPane.showMessageDialog(dlg, "Host cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE); return; }
            int port;
            try {
                port = Integer.parseInt(portStr);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dlg, "Port must be 1–65535.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            dlg.dispose();
            try {
                apiServer.restart(host, port);
                refreshApiLabel();
                JOptionPane.showMessageDialog(this,
                    "API server restarted on " + host + ":" + port,
                    "API Settings", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                refreshApiLabel();
                JOptionPane.showMessageDialog(this,
                    "Failed to restart API server:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dlg.add(content);
        dlg.getRootPane().setDefaultButton(applyBtn);
        dlg.setVisible(true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JSeparator vSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 22));
        return s;
    }

    private Color separatorColor() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(200, 200, 200);
    }

    // ── Tab lifecycle ─────────────────────────────────────────────────────────

    RepeaterTab createNewTab(String name, String rawRequest) {
        String id = UUID.randomUUID().toString();
        TabData tabData = new TabData(id, name);
        RepeaterTab tab = new RepeaterTab(api, tabData, historyManager, requestSender);
        wireTitleListener(tab);
        tabs.put(id, tab);
        tabbedPane.addTab(name, tab);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, buildTabHeader(name, id));
        tabbedPane.setSelectedIndex(idx);
        tabCounter++;
        if (rawRequest != null && !rawRequest.isBlank()) tab.setRequest(rawRequest, "", 443, true);
        return tab;
    }

    private void wireTitleListener(RepeaterTab tab) {
        tab.setTitleListener((tabId, newTitle) -> SwingUtilities.invokeLater(() -> {
            RepeaterTab t = tabs.get(tabId);
            if (t == null) return;
            int idx = tabbedPane.indexOfComponent(t);
            if (idx >= 0) {
                tabbedPane.setTabComponentAt(idx, buildTabHeader(newTitle, tabId));
                tabbedPane.setTitleAt(idx, newTitle);
            }
        }));
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
        close.setForeground(new Color(140, 140, 140));
        close.setToolTipText("Close tab");
        close.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { close.setForeground(new Color(200, 0, 0)); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { close.setForeground(new Color(140, 140, 140)); }
        });
        close.addActionListener(e -> closeTab(tabId));
        header.add(label);
        header.add(close);
        return header;
    }

    void closeTab(String tabId) {
        RepeaterTab tab = tabs.get(tabId);
        if (tab == null) return;
        int idx = tabbedPane.indexOfComponent(tab);
        if (idx < 0) return;
        tabs.remove(tabId);
        tabbedPane.removeTabAt(idx);
        // Always keep at least one tab open
        if (tabs.isEmpty()) createNewTab("Tab " + tabCounter, null);
    }

    private void duplicateCurrentTab() {
        RepeaterTab current = getCurrentTab();
        if (current == null) return;
        TabData orig = current.getTabData();
        String newId = UUID.randomUUID().toString();
        TabData copy = new TabData(newId, orig.getName() + " (copy)");
        copy.setCurrentRequest(orig.getCurrentRequest());
        if (orig.getMetadata() != null) copy.setMetadata(new java.util.HashMap<>(orig.getMetadata()));

        RepeaterTab newTab = new RepeaterTab(api, copy, historyManager, requestSender);
        wireTitleListener(newTab);
        tabs.put(newId, newTab);
        tabbedPane.addTab(copy.getName(), newTab);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setTabComponentAt(idx, buildTabHeader(copy.getName(), newId));
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
                JOptionPane.showMessageDialog(this, "Project saved successfully.");
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
                JOptionPane.showMessageDialog(this, "Project loaded successfully.");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Load failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
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
                RepeaterTab tab = new RepeaterTab(api, td, historyManager, requestSender);
                wireTitleListener(tab);
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
                if (data != null && data.getTabs() != null && !data.getTabs().isEmpty())
                    loadProjectData(data);
            } catch (Exception ignored) {}
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

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
    public List<TabData> getAllTabs() { return getAllTabData(); }

    @Override
    public boolean updateTabRequest(String tabId, String rawRequest) {
        RepeaterTab tab = tabs.get(tabId);
        if (tab == null) return false;
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                tab.setRequest(rawRequest, "", 0, false);
            } else {
                SwingUtilities.invokeAndWait(() -> tab.setRequest(rawRequest, "", 0, false));
            }
        } catch (Exception ignored) {}
        return true;
    }

    @Override
    public Future<com.repeaterx.core.RequestSender.SendResult> sendInTab(String tabId) {
        RepeaterTab tab = tabs.get(tabId);
        if (tab == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return tab.triggerSend(); // same code path as clicking Send
    }
}
