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
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class RepeaterTab extends JPanel {

    public interface TitleListener {
        void onTitleChange(String tabId, String newTitle);
    }

    private final MontoyaApi api;
    private final TabData tabData;
    private final HistoryManager historyManager;
    private final RequestSender requestSender;
    private TitleListener titleListener;

    private HttpRequestEditor requestEditor;
    private HttpResponseEditor responseEditor;

    // Toolbar controls
    private JButton sendButton;
    private JButton cancelButton;
    private JButton prevButton;
    private JButton nextButton;
    private JLabel navLabel;
    private JLabel statusLabel;
    private JLabel timeLabel;
    private JLabel sizeLabel;
    private JTextField targetField;

    // Notes (per-tab, collapsible bottom panel)
    private JTextArea notesArea;
    private JPanel notesPanel;
    private JSplitPane outerSplit;
    private JToggleButton notesToggle;

    private boolean isSending = false;
    // -1 = live view, 0 = most recent history entry, N = Nth entry back
    private int historyPos = -1;

    public RepeaterTab(MontoyaApi api, TabData tabData, HistoryManager historyManager, RequestSender requestSender) {
        this.api = api;
        this.tabData = tabData;
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        initUI();
        loadFromTabData();
    }

    // ── UI Construction ──────────────────────────────────────────────────────

    private void initUI() {
        setLayout(new BorderLayout(0, 0));

        requestEditor = api.userInterface().createHttpRequestEditor();
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        add(buildToolbar(), BorderLayout.NORTH);

        // Request panel
        JPanel reqWrapper = new JPanel(new BorderLayout(0, 0));
        JLabel reqHeader = makeHeader("Request");
        reqWrapper.add(reqHeader, BorderLayout.NORTH);
        reqWrapper.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        // Response panel
        JPanel respWrapper = new JPanel(new BorderLayout(0, 0));
        JLabel respHeader = makeHeader("Response");
        respWrapper.add(respHeader, BorderLayout.NORTH);
        respWrapper.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqWrapper, respWrapper);
        editorSplit.setResizeWeight(0.5);
        editorSplit.setBorder(null);

        notesPanel = buildNotesPanel();
        notesPanel.setVisible(false);

        outerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorSplit, notesPanel);
        outerSplit.setResizeWeight(1.0);
        outerSplit.setDividerSize(0);
        outerSplit.setBorder(null);

        add(outerSplit, BorderLayout.CENTER);
    }

    private JLabel makeHeader(String text) {
        JLabel lbl = new JLabel("  " + text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 12f));
        lbl.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(3, 4, 3, 4)
        ));
        return lbl;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        // ── Left section ──────────────────────────────────────────────────
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        left.setBorder(new EmptyBorder(0, 4, 0, 0));

        sendButton = new JButton("Send");
        sendButton.setBackground(new Color(255, 102, 0));
        sendButton.setForeground(Color.WHITE);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD, 12f));
        sendButton.setOpaque(true);
        sendButton.setBorderPainted(false);
        sendButton.setFocusPainted(false);
        sendButton.setPreferredSize(new Dimension(70, 26));
        sendButton.setToolTipText("Send request (Ctrl+K)");
        sendButton.addActionListener(e -> triggerSend());

        cancelButton = new JButton("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.setPreferredSize(new Dimension(70, 26));
        cancelButton.addActionListener(e -> cancelSend());

        prevButton = new JButton("◄");
        prevButton.setFont(new Font("Dialog", Font.PLAIN, 10));
        prevButton.setPreferredSize(new Dimension(30, 26));
        prevButton.setToolTipText("Previous request in history");
        prevButton.setEnabled(false);
        prevButton.addActionListener(e -> navigateHistory(-1));

        nextButton = new JButton("►");
        nextButton.setFont(new Font("Dialog", Font.PLAIN, 10));
        nextButton.setPreferredSize(new Dimension(30, 26));
        nextButton.setToolTipText("Next request in history");
        nextButton.setEnabled(false);
        nextButton.addActionListener(e -> navigateHistory(1));

        navLabel = new JLabel("");
        navLabel.setFont(navLabel.getFont().deriveFont(11f));
        navLabel.setPreferredSize(new Dimension(65, 22));
        navLabel.setHorizontalAlignment(SwingConstants.CENTER);

        notesToggle = new JToggleButton("Notes");
        notesToggle.setFont(notesToggle.getFont().deriveFont(11f));
        notesToggle.setPreferredSize(new Dimension(60, 26));
        notesToggle.addActionListener(e -> toggleNotes(notesToggle.isSelected()));

        left.add(sendButton);
        left.add(cancelButton);
        left.add(vSep());
        left.add(prevButton);
        left.add(nextButton);
        left.add(navLabel);
        left.add(vSep());
        left.add(notesToggle);

        // ── Right section ─────────────────────────────────────────────────
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        right.setBorder(new EmptyBorder(0, 0, 0, 8));

        targetField = new JTextField(30);
        targetField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        targetField.setToolTipText("Target host (e.g. https://example.com)");

        statusLabel = new JLabel("-");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11f));
        timeLabel = new JLabel("-");
        timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
        sizeLabel = new JLabel("-");
        sizeLabel.setFont(sizeLabel.getFont().deriveFont(11f));

        right.add(new JLabel("Target:"));
        right.add(targetField);
        right.add(vSep());
        right.add(new JLabel("Status:"));
        right.add(statusLabel);
        right.add(new JLabel("  "));
        right.add(new JLabel("Time:"));
        right.add(timeLabel);
        right.add(new JLabel("  "));
        right.add(new JLabel("Size:"));
        right.add(sizeLabel);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JSeparator vSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 22));
        return s;
    }

    private JPanel buildNotesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setPreferredSize(new Dimension(0, 160));
        panel.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));

        JLabel header = new JLabel("  Notes");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(3, 4, 3, 4)
        ));
        panel.add(header, BorderLayout.NORTH);

        notesArea = new JTextArea();
        notesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
        });
        panel.add(new JScrollPane(notesArea), BorderLayout.CENTER);
        return panel;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    public void triggerSend() {
        if (isSending) return;
        isSending = true;
        sendButton.setEnabled(false);
        cancelButton.setEnabled(true);
        sendButton.setText("...");
        statusLabel.setText("...");
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));

        syncTargetToMetadata();
        byte[] rawBytes = requestEditor.getRequest().toByteArray().getBytes();
        String rawReq = new String(rawBytes);
        RequestData reqData = parseRawRequest(rawReq);
        tabData.setCurrentRequest(reqData);
        historyPos = -1;

        requestSender.sendAsync(reqData, result -> SwingUtilities.invokeLater(() -> {
            isSending = false;
            sendButton.setEnabled(true);
            cancelButton.setEnabled(false);
            sendButton.setText("Send");

            if (result.isSuccess()) {
                ResponseData resp = result.getResponse();
                tabData.setLatestResponse(resp);
                try {
                    responseEditor.setResponse(
                        HttpResponse.httpResponse(ByteArray.byteArray(resp.getRawResponse().getBytes()))
                    );
                } catch (Exception ex) { /* ignore */ }

                applyStatus(resp.getStatusCode());
                timeLabel.setText(resp.getResponseTime() + " ms");
                sizeLabel.setText(formatSize(resp.getResponseSize()));

                HistoryEntry entry = historyManager.addEntry(tabData.getId(), reqData, resp);
                tabData.addHistoryEntry(entry);
                updateNavButtons();
                updateTabTitle(reqData);
            } else {
                statusLabel.setText("ERR");
                statusLabel.setForeground(Color.RED);
                JOptionPane.showMessageDialog(this, "Error: " + result.getError(), "Send Error", JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private void cancelSend() {
        isSending = false;
        sendButton.setEnabled(true);
        cancelButton.setEnabled(false);
        sendButton.setText("Send");
        statusLabel.setText("-");
    }

    /**
     * direction: -1 = navigate to older entry, +1 = navigate to newer entry
     */
    private void navigateHistory(int direction) {
        List<HistoryEntry> history = tabData.getHistory();
        if (history == null || history.isEmpty()) return;
        // history index 0 = most recent
        if (direction == -1) {
            // go older
            historyPos = (historyPos == -1) ? 0 : Math.min(historyPos + 1, history.size() - 1);
        } else {
            // go newer
            if (historyPos > 0) historyPos--;
            else historyPos = -1;
        }
        loadHistoryPosition();
        updateNavButtons();
    }

    private void loadHistoryPosition() {
        List<HistoryEntry> history = tabData.getHistory();
        if (historyPos == -1) {
            // restore live
            if (tabData.getCurrentRequest() != null) setEditorRequest(tabData.getCurrentRequest().getRawRequest());
            if (tabData.getLatestResponse() != null) setEditorResponse(tabData.getLatestResponse().getRawResponse());
        } else if (history != null && historyPos < history.size()) {
            HistoryEntry entry = history.get(historyPos);
            if (entry.getRequest() != null) setEditorRequest(entry.getRequest().getRawRequest());
            if (entry.getResponse() != null) setEditorResponse(entry.getResponse().getRawResponse());
        }
    }

    private void toggleNotes(boolean show) {
        notesPanel.setVisible(show);
        outerSplit.setDividerSize(show ? 5 : 0);
        outerSplit.setResizeWeight(show ? 0.78 : 1.0);
        if (show) {
            outerSplit.setDividerLocation((int)(getHeight() * 0.78));
        }
        revalidate();
        repaint();
    }

    private void applyStatus(int code) {
        statusLabel.setText(String.valueOf(code));
        if (code >= 200 && code < 300) statusLabel.setForeground(new Color(0, 160, 0));
        else if (code >= 300 && code < 400) statusLabel.setForeground(new Color(0, 120, 200));
        else if (code >= 400 && code < 500) statusLabel.setForeground(new Color(210, 110, 0));
        else if (code >= 500) statusLabel.setForeground(new Color(200, 0, 0));
        else statusLabel.setForeground(UIManager.getColor("Label.foreground"));
    }

    private void updateNavButtons() {
        List<HistoryEntry> history = tabData.getHistory();
        int total = (history != null) ? history.size() : 0;
        prevButton.setEnabled(total > 0 && (historyPos == -1 || historyPos < total - 1));
        nextButton.setEnabled(historyPos >= 0);
        if (total == 0) {
            navLabel.setText("");
        } else if (historyPos == -1) {
            navLabel.setText(total + " sent");
        } else {
            navLabel.setText((historyPos + 1) + " / " + total);
        }
    }

    private void updateTabTitle(RequestData req) {
        String path = extractPath(req.getUrl());
        String title = req.getMethod() + " " + path;
        tabData.setName(title);
        if (titleListener != null) titleListener.onTitleChange(tabData.getId(), title);
    }

    private String extractPath(String urlStr) {
        if (urlStr == null) return "/";
        try {
            URL u = new URL(urlStr);
            String p = u.getPath();
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (Exception e) {
            return urlStr.startsWith("/") ? urlStr : "/" + urlStr;
        }
    }

    private String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f kB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ── Request parsing ──────────────────────────────────────────────────────

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
            if (p != null) { try { port = Integer.parseInt(p.toString()); } catch (NumberFormatException ignored) {} }
            if (s != null) https = Boolean.parseBoolean(s.toString());
        }

        String[] lines = raw.split("\r\n|\n", -1);
        if (lines.length > 0 && !lines[0].isBlank()) {
            String[] parts = lines[0].trim().split("\\s+");
            if (parts.length >= 2) { method = parts[0]; path = parts[1]; }
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
                            try { port = Integer.parseInt(hp[1].trim()); } catch (NumberFormatException ignored) {}
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

    // ── Target field sync ────────────────────────────────────────────────────

    private void syncTargetToMetadata() {
        if (targetField == null) return;
        String target = targetField.getText().trim();
        if (target.isEmpty()) return;
        if (!target.contains("://")) target = "https://" + target;
        try {
            URL u = new URL(target);
            if (tabData.getMetadata() == null) tabData.setMetadata(new HashMap<>());
            tabData.getMetadata().put("host", u.getHost());
            boolean isHttps = "https".equalsIgnoreCase(u.getProtocol());
            int p = u.getPort();
            tabData.getMetadata().put("port", p > 0 ? p : (isHttps ? 443 : 80));
            tabData.getMetadata().put("https", isHttps);
        } catch (Exception ignored) {}
    }

    private String buildTargetUrl() {
        if (tabData.getMetadata() == null) return "";
        Object h = tabData.getMetadata().get("host");
        Object p = tabData.getMetadata().get("port");
        Object s = tabData.getMetadata().get("https");
        if (h == null || h.toString().isEmpty()) return "";
        boolean isHttps = s != null && Boolean.parseBoolean(s.toString());
        int portNum = p != null ? Integer.parseInt(p.toString()) : (isHttps ? 443 : 80);
        String scheme = isHttps ? "https" : "http";
        boolean standardPort = (isHttps && portNum == 443) || (!isHttps && portNum == 80);
        return scheme + "://" + h + (standardPort ? "" : ":" + portNum);
    }

    // ── Editor helpers ───────────────────────────────────────────────────────

    private void setEditorRequest(String raw) {
        if (raw == null || raw.isBlank()) return;
        try { requestEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(raw.getBytes()))); }
        catch (Exception ignored) {}
    }

    private void setEditorResponse(String raw) {
        if (raw == null || raw.isBlank()) return;
        try { responseEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(raw.getBytes()))); }
        catch (Exception ignored) {}
    }

    // ── Load / restore ───────────────────────────────────────────────────────

    private void loadFromTabData() {
        if (tabData.getCurrentRequest() != null)
            setEditorRequest(tabData.getCurrentRequest().getRawRequest());
        if (tabData.getLatestResponse() != null)
            setEditorResponse(tabData.getLatestResponse().getRawResponse());
        if (tabData.getLatestResponse() != null)
            applyStatus(tabData.getLatestResponse().getStatusCode());
        if (notesArea != null && tabData.getNotes() != null)
            notesArea.setText(tabData.getNotes());
        String target = buildTargetUrl();
        if (targetField != null && !target.isEmpty())
            targetField.setText(target);
        updateNavButtons();
    }

    private void saveNotes() {
        if (notesArea != null) tabData.setNotes(notesArea.getText());
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setRequest(String rawRequest, String host, int port, boolean https) {
        if (tabData.getMetadata() == null) tabData.setMetadata(new HashMap<>());
        tabData.getMetadata().put("host", host);
        tabData.getMetadata().put("port", port);
        tabData.getMetadata().put("https", https);
        boolean standardPort = (https && port == 443) || (!https && port == 80);
        targetField.setText((https ? "https" : "http") + "://" + host + (standardPort ? "" : ":" + port));
        setEditorRequest(rawRequest);
    }

    public void setTitleListener(TitleListener l) { this.titleListener = l; }
    public TabData getTabData() { return tabData; }
    public String getTabId() { return tabData.getId(); }
}
