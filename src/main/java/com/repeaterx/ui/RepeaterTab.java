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

    private JButton sendButton;
    private JButton cancelButton;
    private JButton prevButton;
    private JButton nextButton;
    private JLabel navLabel;
    private JLabel statusLabel;
    private JLabel timeLabel;
    private JLabel sizeLabel;
    private JTextField targetField;
    private JTextArea notesArea;
    private JPanel notesPanel;
    private JSplitPane outerSplit;
    private JToggleButton notesToggle;

    // Bottom status bar (mirroring Burp's "N bytes | N millis")
    private JLabel bottomStatusLabel;

    private boolean isSending = false;
    private int historyPos = -1; // -1 = live, 0 = most recent, etc.

    public RepeaterTab(MontoyaApi api, TabData tabData, HistoryManager historyManager, RequestSender requestSender) {
        this.api = api;
        this.tabData = tabData;
        this.historyManager = historyManager;
        this.requestSender = requestSender;
        initUI();
        loadFromTabData();
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void initUI() {
        setLayout(new BorderLayout(0, 0));

        requestEditor = api.userInterface().createHttpRequestEditor();
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        add(buildToolbar(), BorderLayout.NORTH);

        // Request panel
        JPanel reqWrapper = new JPanel(new BorderLayout(0, 0));
        reqWrapper.add(makeHeader("Request"), BorderLayout.NORTH);
        reqWrapper.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        // Response panel
        JPanel respWrapper = new JPanel(new BorderLayout(0, 0));
        respWrapper.add(makeHeader("Response"), BorderLayout.NORTH);
        respWrapper.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqWrapper, respWrapper);
        editorSplit.setResizeWeight(0.5);
        editorSplit.setBorder(null);
        editorSplit.setContinuousLayout(true);

        notesPanel = buildNotesPanel();
        notesPanel.setVisible(false);

        outerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorSplit, notesPanel);
        outerSplit.setResizeWeight(1.0);
        outerSplit.setDividerSize(0);
        outerSplit.setBorder(null);
        outerSplit.setContinuousLayout(true);

        add(outerSplit, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);
    }

    private JLabel makeHeader(String text) {
        JLabel lbl = new JLabel("  " + text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 12f));
        lbl.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, separator()),
            new EmptyBorder(4, 6, 4, 6)
        ));
        return lbl;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBorder(new MatteBorder(0, 0, 1, 0, separator()));

        // Left: Send, Cancel, |, ◄ ►, nav counter, |, Notes toggle
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        left.setBorder(new EmptyBorder(0, 6, 0, 0));

        sendButton = makeSendButton();
        cancelButton = makeCancelButton();
        prevButton = makeNavButton("◄", "Previous request in history (older)");
        nextButton = makeNavButton("►", "Next request in history (newer)");
        prevButton.addActionListener(e -> navigateHistory(-1));
        nextButton.addActionListener(e -> navigateHistory(1));

        navLabel = new JLabel("");
        navLabel.setFont(navLabel.getFont().deriveFont(Font.PLAIN, 11f));
        navLabel.setForeground(new Color(120, 120, 120));
        navLabel.setPreferredSize(new Dimension(68, 22));
        navLabel.setHorizontalAlignment(SwingConstants.CENTER);

        notesToggle = new JToggleButton("Notes");
        notesToggle.setFont(notesToggle.getFont().deriveFont(Font.PLAIN, 11f));
        notesToggle.setFocusPainted(false);
        notesToggle.setPreferredSize(new Dimension(58, 26));
        notesToggle.setToolTipText("Toggle per-tab notes panel");
        notesToggle.addActionListener(e -> toggleNotes(notesToggle.isSelected()));

        left.add(sendButton);
        left.add(cancelButton);
        left.add(vSep());
        left.add(prevButton);
        left.add(nextButton);
        left.add(navLabel);
        left.add(vSep());
        left.add(notesToggle);

        // Right: Target URL, status chip
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 3));
        right.setBorder(new EmptyBorder(0, 0, 0, 8));

        targetField = new JTextField(32);
        targetField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        targetField.setToolTipText("Target (e.g. https://api.example.com)");
        targetField.setBorder(new CompoundBorder(
            UIManager.getBorder("TextField.border"),
            new EmptyBorder(1, 4, 1, 4)
        ));

        statusLabel = makeStatusChip("-");
        timeLabel = new JLabel("–");
        timeLabel.setFont(timeLabel.getFont().deriveFont(Font.PLAIN, 11f));
        sizeLabel = new JLabel("–");
        sizeLabel.setFont(sizeLabel.getFont().deriveFont(Font.PLAIN, 11f));

        right.add(new JLabel("Target:"));
        right.add(targetField);
        right.add(vSep());
        right.add(new JLabel("Status:"));
        right.add(statusLabel);
        right.add(new JLabel("  Time:"));
        right.add(timeLabel);
        right.add(new JLabel("  Size:"));
        right.add(sizeLabel);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBorder(new MatteBorder(1, 0, 0, 0, separator()));
        bar.setBackground(UIManager.getColor("Panel.background"));

        bottomStatusLabel = new JLabel("  Ready");
        bottomStatusLabel.setFont(bottomStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        bottomStatusLabel.setForeground(new Color(120, 120, 120));
        bottomStatusLabel.setBorder(new EmptyBorder(2, 8, 2, 8));
        bar.add(bottomStatusLabel, BorderLayout.WEST);
        return bar;
    }

    private JPanel buildNotesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setPreferredSize(new Dimension(0, 160));
        panel.setBorder(new MatteBorder(1, 0, 0, 0, separator()));

        JLabel header = new JLabel("  Notes");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, separator()),
            new EmptyBorder(3, 6, 3, 6)
        ));

        notesArea = new JTextArea();
        notesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        notesArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
        });

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(notesArea), BorderLayout.CENTER);
        return panel;
    }

    // ── Button factories ──────────────────────────────────────────────────────

    private JButton makeSendButton() {
        JButton btn = new JButton("Send");
        btn.setBackground(new Color(230, 90, 30));
        btn.setForeground(Color.WHITE);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 12f));
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setPreferredSize(new Dimension(72, 26));
        btn.setToolTipText("Send request  [Ctrl+K]");
        btn.addActionListener(e -> triggerSend());
        // Hover effect
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(new Color(210, 70, 10));
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setBackground(new Color(230, 90, 30));
            }
        });
        return btn;
    }

    private JButton makeCancelButton() {
        JButton btn = new JButton("Cancel");
        btn.setEnabled(false);
        btn.setFocusPainted(false);
        btn.setPreferredSize(new Dimension(68, 26));
        btn.addActionListener(e -> cancelSend());
        return btn;
    }

    private JButton makeNavButton(String text, String tip) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        btn.setFocusPainted(false);
        btn.setEnabled(false);
        btn.setPreferredSize(new Dimension(30, 26));
        btn.setToolTipText(tip);
        return btn;
    }

    private JLabel makeStatusChip(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11f));
        lbl.setOpaque(true);
        lbl.setBackground(new Color(220, 220, 220));
        lbl.setForeground(new Color(60, 60, 60));
        lbl.setBorder(new EmptyBorder(1, 6, 1, 6));
        return lbl;
    }

    private JSeparator vSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 22));
        return s;
    }

    private Color separator() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(200, 200, 200);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    public void triggerSend() {
        if (isSending) return;
        isSending = true;
        sendButton.setEnabled(false);
        cancelButton.setEnabled(true);
        sendButton.setText("…");
        setStatusChip("-", null);
        bottomStatusLabel.setText("  Sending…");

        syncTargetToMetadata();
        byte[] rawBytes = requestEditor.getRequest().toByteArray().getBytes();
        RequestData reqData = parseRawRequest(new String(rawBytes));
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
                } catch (Exception ignored) {}

                applyStatus(resp.getStatusCode());
                timeLabel.setText(resp.getResponseTime() + " ms");
                sizeLabel.setText(formatSize(resp.getResponseSize()));
                bottomStatusLabel.setText("  " + formatSize(resp.getResponseSize())
                    + "  |  " + resp.getResponseTime() + " ms");

                HistoryEntry entry = historyManager.addEntry(tabData.getId(), reqData, resp);
                tabData.addHistoryEntry(entry);
                updateNavButtons();
                updateTabTitle(reqData);
            } else {
                setStatusChip("ERR", Color.RED);
                bottomStatusLabel.setText("  Error: " + result.getError());
                JOptionPane.showMessageDialog(this, result.getError(), "Send Error", JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private void cancelSend() {
        isSending = false;
        sendButton.setEnabled(true);
        cancelButton.setEnabled(false);
        sendButton.setText("Send");
        setStatusChip("-", null);
        bottomStatusLabel.setText("  Cancelled");
    }

    private void navigateHistory(int direction) {
        List<HistoryEntry> history = tabData.getHistory();
        if (history == null || history.isEmpty()) return;
        if (direction == -1) {
            historyPos = (historyPos == -1) ? 0 : Math.min(historyPos + 1, history.size() - 1);
        } else {
            if (historyPos > 0) historyPos--;
            else historyPos = -1;
        }
        loadHistoryPosition();
        updateNavButtons();
    }

    private void loadHistoryPosition() {
        List<HistoryEntry> history = tabData.getHistory();
        if (historyPos == -1) {
            if (tabData.getCurrentRequest() != null) setEditorReq(tabData.getCurrentRequest().getRawRequest());
            if (tabData.getLatestResponse() != null) setEditorResp(tabData.getLatestResponse().getRawResponse());
            if (tabData.getLatestResponse() != null) applyStatus(tabData.getLatestResponse().getStatusCode());
        } else if (history != null && historyPos < history.size()) {
            HistoryEntry entry = history.get(historyPos);
            if (entry.getRequest() != null) setEditorReq(entry.getRequest().getRawRequest());
            if (entry.getResponse() != null) {
                setEditorResp(entry.getResponse().getRawResponse());
                applyStatus(entry.getResponse().getStatusCode());
                timeLabel.setText(entry.getResponse().getResponseTime() + " ms");
                sizeLabel.setText(formatSize(entry.getResponse().getResponseSize()));
                bottomStatusLabel.setText("  " + formatSize(entry.getResponse().getResponseSize())
                    + "  |  " + entry.getResponse().getResponseTime() + " ms"
                    + "  [history " + (historyPos + 1) + "]");
            }
        }
    }

    private void toggleNotes(boolean show) {
        notesPanel.setVisible(show);
        outerSplit.setDividerSize(show ? 5 : 0);
        outerSplit.setResizeWeight(show ? 0.78 : 1.0);
        if (show) outerSplit.setDividerLocation((int) (getHeight() * 0.78));
        revalidate();
        repaint();
    }

    // ── Status helpers ────────────────────────────────────────────────────────

    private void applyStatus(int code) {
        Color bg, fg;
        if (code >= 200 && code < 300)      { bg = new Color(210, 245, 210); fg = new Color(0, 120, 0); }
        else if (code >= 300 && code < 400) { bg = new Color(210, 230, 255); fg = new Color(0, 80, 180); }
        else if (code >= 400 && code < 500) { bg = new Color(255, 235, 200); fg = new Color(180, 80, 0); }
        else if (code >= 500)               { bg = new Color(255, 210, 210); fg = new Color(180, 0, 0); }
        else                                { bg = new Color(220, 220, 220); fg = new Color(60, 60, 60); }
        setStatusChip(String.valueOf(code), fg);
        statusLabel.setBackground(bg);
    }

    private void setStatusChip(String text, Color fg) {
        statusLabel.setText(text);
        if (fg != null) statusLabel.setForeground(fg);
        else { statusLabel.setForeground(new Color(60, 60, 60)); statusLabel.setBackground(new Color(220, 220, 220)); }
    }

    private void updateNavButtons() {
        List<HistoryEntry> history = tabData.getHistory();
        int total = (history != null) ? history.size() : 0;
        prevButton.setEnabled(total > 0 && (historyPos == -1 || historyPos < total - 1));
        nextButton.setEnabled(historyPos > 0 || (historyPos == 0 && total > 0));
        if (total == 0)          navLabel.setText("");
        else if (historyPos == -1) navLabel.setText(total + " sent");
        else                       navLabel.setText((historyPos + 1) + " / " + total);
    }

    private void updateTabTitle(RequestData req) {
        String path = extractPath(req.getUrl());
        String title = req.getMethod() + " " + path;
        tabData.setName(title);
        if (titleListener != null) titleListener.onTitleChange(tabData.getId(), title);
    }

    // ── Request parsing ───────────────────────────────────────────────────────

    private RequestData parseRawRequest(String raw) {
        String id = UUID.randomUUID().toString();
        String method = "GET", path = "/", host = "";
        int port = 443;
        boolean https = true;
        List<String[]> headers = new ArrayList<>();

        if (tabData.getMetadata() != null) {
            Object h = tabData.getMetadata().get("host");
            Object p = tabData.getMetadata().get("port");
            Object s = tabData.getMetadata().get("https");
            if (h != null) host = h.toString();
            if (p != null) try { port = Integer.parseInt(p.toString()); } catch (NumberFormatException ignored) {}
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
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    String name = lines[i].substring(0, c).trim();
                    String val  = lines[i].substring(c + 1).trim();
                    headers.add(new String[]{name, val});
                    if ("host".equalsIgnoreCase(name)) {
                        String[] hp = val.split(":", 2);
                        host = hp[0].trim();
                        if (hp.length > 1) try { port = Integer.parseInt(hp[1].trim()); } catch (NumberFormatException ignored) {}
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void syncTargetToMetadata() {
        if (targetField == null) return;
        String t = targetField.getText().trim();
        if (t.isEmpty()) return;
        if (!t.contains("://")) t = "https://" + t;
        try {
            URL u = new URL(t);
            if (tabData.getMetadata() == null) tabData.setMetadata(new HashMap<>());
            boolean sec = "https".equalsIgnoreCase(u.getProtocol());
            int p = u.getPort() > 0 ? u.getPort() : (sec ? 443 : 80);
            tabData.getMetadata().put("host", u.getHost());
            tabData.getMetadata().put("port", p);
            tabData.getMetadata().put("https", sec);
        } catch (Exception ignored) {}
    }

    private String buildTargetUrl() {
        if (tabData.getMetadata() == null) return "";
        Object h = tabData.getMetadata().get("host");
        Object p = tabData.getMetadata().get("port");
        Object s = tabData.getMetadata().get("https");
        if (h == null || h.toString().isEmpty()) return "";
        boolean sec = s != null && Boolean.parseBoolean(s.toString());
        int pNum = p != null ? Integer.parseInt(p.toString()) : (sec ? 443 : 80);
        boolean std = (sec && pNum == 443) || (!sec && pNum == 80);
        return (sec ? "https" : "http") + "://" + h + (std ? "" : ":" + pNum);
    }

    private String extractPath(String urlStr) {
        if (urlStr == null) return "/";
        try { String p = new URL(urlStr).getPath(); return (p == null || p.isEmpty()) ? "/" : p; }
        catch (Exception e) { return urlStr.startsWith("/") ? urlStr : "/" + urlStr; }
    }

    private String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f kB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private void setEditorReq(String raw) {
        if (raw == null || raw.isBlank()) return;
        try { requestEditor.setRequest(HttpRequest.httpRequest(ByteArray.byteArray(raw.getBytes()))); }
        catch (Exception ignored) {}
    }

    private void setEditorResp(String raw) {
        if (raw == null || raw.isBlank()) return;
        try { responseEditor.setResponse(HttpResponse.httpResponse(ByteArray.byteArray(raw.getBytes()))); }
        catch (Exception ignored) {}
    }

    private void loadFromTabData() {
        if (tabData.getCurrentRequest() != null) setEditorReq(tabData.getCurrentRequest().getRawRequest());
        if (tabData.getLatestResponse() != null) {
            setEditorResp(tabData.getLatestResponse().getRawResponse());
            applyStatus(tabData.getLatestResponse().getStatusCode());
            timeLabel.setText(tabData.getLatestResponse().getResponseTime() + " ms");
            sizeLabel.setText(formatSize(tabData.getLatestResponse().getResponseSize()));
            bottomStatusLabel.setText("  " + formatSize(tabData.getLatestResponse().getResponseSize())
                + "  |  " + tabData.getLatestResponse().getResponseTime() + " ms");
        }
        if (notesArea != null && tabData.getNotes() != null) notesArea.setText(tabData.getNotes());
        String target = buildTargetUrl();
        if (targetField != null && !target.isEmpty()) targetField.setText(target);
        updateNavButtons();
    }

    private void saveNotes() {
        if (notesArea != null) tabData.setNotes(notesArea.getText());
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setRequest(String rawRequest, String host, int port, boolean https) {
        if (tabData.getMetadata() == null) tabData.setMetadata(new HashMap<>());
        tabData.getMetadata().put("host", host);
        tabData.getMetadata().put("port", port);
        tabData.getMetadata().put("https", https);
        boolean std = (https && port == 443) || (!https && port == 80);
        if (targetField != null)
            targetField.setText((https ? "https" : "http") + "://" + host + (std ? "" : ":" + port));
        setEditorReq(rawRequest);
    }

    public void setTitleListener(TitleListener l) { this.titleListener = l; }
    public TabData getTabData() { return tabData; }
    public String getTabId() { return tabData.getId(); }
}
