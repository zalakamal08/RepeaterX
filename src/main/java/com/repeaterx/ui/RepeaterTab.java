package com.repeaterx.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.repeaterx.core.HistoryManager;
import com.repeaterx.core.RequestSender;
import com.repeaterx.model.HistoryEntry;
import com.repeaterx.model.RequestData;
import com.repeaterx.model.ResponseData;
import com.repeaterx.model.TabData;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class RepeaterTab extends JPanel {
    private final MontoyaApi api;
    private final TabData tabData;
    private final HistoryManager historyManager;
    private final RequestSender requestSender;

    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;
    private JButton sendButton;
    private JLabel statusLabel;
    private JLabel timeLabel;
    private JLabel sizeLabel;
    private JTextArea notesArea;
    private DefaultTableModel historyModel;
    private JTable historyTable;
    private boolean isSending = false;

    private static final String[] HISTORY_COLS = {"#", "Method", "URL", "Status", "Time(ms)", "Size", "Time"};

    public RepeaterTab(MontoyaApi api, TabData tabData, HistoryManager historyManager, RequestSender requestSender) {
        this.api = api;
        this.tabData = tabData;
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        initUI();
        loadFromTabData();
    }

    private void initUI() {
        setLayout(new BorderLayout());

        requestEditor = api.userInterface().createHttpRequestEditor();
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        JPanel topBar = buildTopBar();
        add(topBar, BorderLayout.NORTH);

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        editorSplit.setResizeWeight(0.5);

        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.setBorder(BorderFactory.createTitledBorder("Request"));
        reqPanel.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        JPanel respPanel = new JPanel(new BorderLayout());
        respPanel.setBorder(BorderFactory.createTitledBorder("Response"));
        JPanel respInfoBar = buildResponseInfoBar();
        respPanel.add(respInfoBar, BorderLayout.NORTH);
        respPanel.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        editorSplit.setLeftComponent(reqPanel);
        editorSplit.setRightComponent(respPanel);

        JTabbedPane bottomTabs = new JTabbedPane(JTabbedPane.BOTTOM);
        bottomTabs.addTab("Editors", editorSplit);
        bottomTabs.addTab("History", buildHistoryPanel());
        bottomTabs.addTab("Notes", buildNotesPanel());

        add(bottomTabs, BorderLayout.CENTER);
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        bar.setBorder(new EmptyBorder(2, 5, 2, 5));

        sendButton = new JButton("Send");
        sendButton.setBackground(new Color(255, 102, 0));
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        sendButton.addActionListener(e -> sendRequest());

        statusLabel = new JLabel("-");
        timeLabel = new JLabel("- ms");
        sizeLabel = new JLabel("- bytes");

        bar.add(sendButton);
        bar.add(new JSeparator(JSeparator.VERTICAL));
        bar.add(new JLabel("Status:"));
        bar.add(statusLabel);
        bar.add(new JLabel("|"));
        bar.add(new JLabel("Time:"));
        bar.add(timeLabel);
        bar.add(new JLabel("|"));
        bar.add(new JLabel("Size:"));
        bar.add(sizeLabel);

        return bar;
    }

    private JPanel buildResponseInfoBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        // reuse instance labels
        statusLabel = new JLabel("-");
        timeLabel = new JLabel("- ms");
        sizeLabel = new JLabel("- bytes");
        bar.add(new JLabel("Status: "));
        bar.add(statusLabel);
        bar.add(new JLabel(" | Time: "));
        bar.add(timeLabel);
        bar.add(new JLabel(" | Size: "));
        bar.add(sizeLabel);
        return bar;
    }

    private JPanel buildHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        historyModel = new DefaultTableModel(HISTORY_COLS, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        historyTable = new JTable(historyModel);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.getColumnModel().getColumn(0).setMaxWidth(50);
        historyTable.getColumnModel().getColumn(1).setMaxWidth(80);
        historyTable.getColumnModel().getColumn(3).setMaxWidth(70);
        historyTable.getColumnModel().getColumn(4).setMaxWidth(90);
        historyTable.getColumnModel().getColumn(5).setMaxWidth(90);
        historyTable.getColumnModel().getColumn(6).setMaxWidth(80);
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) loadHistoryEntry(historyTable.getSelectedRow());
            }
        });

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        JButton replayBtn = new JButton("Replay");
        JButton loadBtn = new JButton("Load");
        JButton clearBtn = new JButton("Clear");
        replayBtn.addActionListener(e -> replaySelected());
        loadBtn.addActionListener(e -> loadHistoryEntry(historyTable.getSelectedRow()));
        clearBtn.addActionListener(e -> {
            historyModel.setRowCount(0);
            if (tabData.getHistory() != null) tabData.getHistory().clear();
        });
        buttonsPanel.add(replayBtn);
        buttonsPanel.add(loadBtn);
        buttonsPanel.add(clearBtn);

        panel.add(buttonsPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildNotesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        notesArea = new JTextArea();
        notesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.setText(tabData.getNotes() != null ? tabData.getNotes() : "");
        notesArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
        });
        panel.add(new JLabel("  Tab notes:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(notesArea), BorderLayout.CENTER);
        return panel;
    }

    private void saveNotes() {
        if (notesArea != null) tabData.setNotes(notesArea.getText());
    }

    private void sendRequest() {
        if (isSending) return;
        isSending = true;
        sendButton.setEnabled(false);
        sendButton.setText("Sending...");
        statusLabel.setText("Sending...");

        byte[] reqBytes = requestEditor.getRequest().toByteArray().getBytes();
        String rawReq = new String(reqBytes);
        RequestData reqData = parseRawRequest(rawReq);
        tabData.setCurrentRequest(reqData);

        requestSender.sendAsync(reqData, result -> SwingUtilities.invokeLater(() -> {
            isSending = false;
            sendButton.setEnabled(true);
            sendButton.setText("Send");
            if (result.isSuccess()) {
                ResponseData resp = result.getResponse();
                tabData.setLatestResponse(resp);
                try {
                    responseEditor.setResponse(
                        HttpResponse.httpResponse(ByteArray.byteArray(resp.getRawResponse().getBytes()))
                    );
                } catch (Exception e) {
                    // ignore render errors
                }
                statusLabel.setText(String.valueOf(resp.getStatusCode()));
                timeLabel.setText(resp.getResponseTime() + " ms");
                sizeLabel.setText(resp.getResponseSize() + " bytes");
                HistoryEntry entry = historyManager.addEntry(tabData.getId(), reqData, resp);
                tabData.addHistoryEntry(entry);
                addHistoryRow(entry, tabData.getHistory().size());
            } else {
                statusLabel.setText("Error");
                JOptionPane.showMessageDialog(this, "Send error: " + result.getError(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private RequestData parseRawRequest(String raw) {
        String id = UUID.randomUUID().toString();
        String method = "GET";
        String path = "/";
        String host = "";
        int port = 443;
        boolean https = true;
        List<String[]> headers = new ArrayList<>();

        if (tabData.getMetadata() != null) {
            Object h = tabData.getMetadata().get("host");
            Object p = tabData.getMetadata().get("port");
            Object s = tabData.getMetadata().get("https");
            if (h != null) host = h.toString();
            if (p != null) { try { port = Integer.parseInt(p.toString()); } catch (NumberFormatException e) {} }
            if (s != null) https = Boolean.parseBoolean(s.toString());
        }

        String[] lines = raw.split("\r\n|\n", -1);
        if (lines.length > 0 && !lines[0].isBlank()) {
            String[] parts = lines[0].trim().split("\\s+");
            if (parts.length >= 2) {
                method = parts[0];
                path = parts[1];
            }
        }
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty() && !inBody) { inBody = true; continue; }
            if (!inBody) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    String name = lines[i].substring(0, colon).trim();
                    String value = lines[i].substring(colon + 1).trim();
                    headers.add(new String[]{name, value});
                    if ("host".equalsIgnoreCase(name)) {
                        String[] hp = value.split(":", 2);
                        host = hp[0].trim();
                        if (hp.length > 1) {
                            try { port = Integer.parseInt(hp[1].trim()); } catch (NumberFormatException e) {}
                        }
                    }
                }
            } else {
                if (body.length() > 0) body.append("\n");
                body.append(lines[i]);
            }
        }

        String url = (https ? "https" : "http") + "://" + host + path;
        return new RequestData(id, method, url, host, port, https, headers, body.toString(), raw);
    }

    private void addHistoryRow(HistoryEntry entry, int num) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String ts = sdf.format(new Date(entry.getTimestamp()));
        historyModel.insertRow(0, new Object[]{
            num,
            entry.getRequest() != null ? entry.getRequest().getMethod() : "-",
            entry.getRequest() != null ? entry.getRequest().getUrl() : "-",
            entry.getResponse() != null ? entry.getResponse().getStatusCode() : "-",
            entry.getResponse() != null ? entry.getResponse().getResponseTime() : "-",
            entry.getResponse() != null ? entry.getResponse().getResponseSize() : "-",
            ts
        });
    }

    private void loadHistoryEntry(int row) {
        if (row < 0) return;
        List<HistoryEntry> history = tabData.getHistory();
        if (history == null || row >= history.size()) return;
        HistoryEntry entry = history.get(history.size() - 1 - row);
        if (entry.getRequest() != null && entry.getRequest().getRawRequest() != null) {
            try {
                requestEditor.setRequest(
                    HttpRequest.httpRequest(ByteArray.byteArray(entry.getRequest().getRawRequest().getBytes()))
                );
            } catch (Exception e) {
                // ignore
            }
        }
        if (entry.getResponse() != null && entry.getResponse().getRawResponse() != null) {
            try {
                responseEditor.setResponse(
                    HttpResponse.httpResponse(ByteArray.byteArray(entry.getResponse().getRawResponse().getBytes()))
                );
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void replaySelected() {
        int row = historyTable.getSelectedRow();
        if (row < 0) return;
        List<HistoryEntry> history = tabData.getHistory();
        if (history == null || row >= history.size()) return;
        HistoryEntry entry = history.get(history.size() - 1 - row);
        if (entry.getRequest() != null && entry.getRequest().getRawRequest() != null) {
            try {
                requestEditor.setRequest(
                    HttpRequest.httpRequest(ByteArray.byteArray(entry.getRequest().getRawRequest().getBytes()))
                );
            } catch (Exception e) {
                // ignore
            }
            sendRequest();
        }
    }

    private void loadFromTabData() {
        if (tabData.getCurrentRequest() != null && tabData.getCurrentRequest().getRawRequest() != null) {
            try {
                requestEditor.setRequest(
                    HttpRequest.httpRequest(ByteArray.byteArray(tabData.getCurrentRequest().getRawRequest().getBytes()))
                );
            } catch (Exception e) {
                // ignore
            }
        }
        if (tabData.getLatestResponse() != null && tabData.getLatestResponse().getRawResponse() != null) {
            try {
                responseEditor.setResponse(
                    HttpResponse.httpResponse(ByteArray.byteArray(tabData.getLatestResponse().getRawResponse().getBytes()))
                );
            } catch (Exception e) {
                // ignore
            }
        }
        if (tabData.getHistory() != null) {
            for (int i = tabData.getHistory().size() - 1; i >= 0; i--) {
                addHistoryRow(tabData.getHistory().get(i), tabData.getHistory().size() - i);
            }
        }
        if (notesArea != null && tabData.getNotes() != null) {
            notesArea.setText(tabData.getNotes());
        }
    }

    public void setRequest(String rawRequest, String host, int port, boolean https) {
        if (tabData.getMetadata() == null) tabData.setMetadata(new HashMap<>());
        tabData.getMetadata().put("host", host);
        tabData.getMetadata().put("port", port);
        tabData.getMetadata().put("https", https);
        if (rawRequest != null && !rawRequest.isBlank()) {
            try {
                requestEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(rawRequest.getBytes())));
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public TabData getTabData() { return tabData; }
    public String getTabId() { return tabData.getId(); }
}
