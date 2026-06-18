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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class RepeaterTab extends JPanel {

    public interface TitleListener {
        void onTitleChange(String tabId, String newTitle);
    }

    private final MontoyaApi      api;
    private final TabData         tabData;
    private final HistoryManager  historyManager;
    private final RequestSender   requestSender;
    private TitleListener         titleListener;

    private HttpRequestEditor  requestEditor;
    private HttpResponseEditor responseEditor;

    private JButton    sendButton;
    private JButton    prevButton;
    private JButton    nextButton;
    private JLabel     navLabel;
    private JLabel     statusLabel;
    private JLabel     timeLabel;
    private JLabel     sizeLabel;
    private JTextField targetField;
    private JLabel     bottomStatusLabel;

    private boolean isSending  = false;
    private int     historyPos = -1; // -1 = live (current), indices are oldest(0)..newest(size-1)


    public RepeaterTab(MontoyaApi api, TabData tabData,
                       HistoryManager historyManager, RequestSender requestSender) {
        this.api            = api;
        this.tabData        = tabData;
        this.historyManager = historyManager;
        this.requestSender  = requestSender;
        initUI();
        loadFromTabData();
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void initUI() {
        setLayout(new BorderLayout(0, 0));

        requestEditor  = api.userInterface().createHttpRequestEditor();
        responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY);

        add(buildToolbar(), BorderLayout.NORTH);

        JPanel reqWrapper = new JPanel(new BorderLayout(0, 0));
        reqWrapper.add(makeHeader("Request"), BorderLayout.NORTH);
        reqWrapper.add(requestEditor.uiComponent(), BorderLayout.CENTER);

        JPanel respWrapper = new JPanel(new BorderLayout(0, 0));
        respWrapper.add(makeHeader("Response"), BorderLayout.NORTH);
        respWrapper.add(responseEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqWrapper, respWrapper);
        editorSplit.setResizeWeight(0.5);
        editorSplit.setBorder(null);
        editorSplit.setContinuousLayout(true);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorSplit, buildInspector());
        mainSplit.setResizeWeight(0.72);
        mainSplit.setBorder(null);
        mainSplit.setContinuousLayout(true);

        add(mainSplit, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);
    }

    private JLabel makeHeader(String text) {
        JLabel lbl = new JLabel("  " + text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 12f));
        lbl.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, sep()),
            new EmptyBorder(4, 6, 4, 6)
        ));
        return lbl;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBorder(new MatteBorder(0, 0, 1, 0, sep()));

        // Left: Send | ◄ ► counter
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        left.setBorder(new EmptyBorder(0, 6, 0, 0));

        sendButton = makeSendButton();

        prevButton = makeNavButton("◄", "Older request");
        nextButton = makeNavButton("►", "Newer request");
        prevButton.addActionListener(e -> navigateHistory(-1));
        nextButton.addActionListener(e -> navigateHistory(1));

        navLabel = new JLabel("");
        navLabel.setFont(navLabel.getFont().deriveFont(Font.PLAIN, 11f));
        navLabel.setForeground(new Color(120, 120, 120));
        navLabel.setPreferredSize(new Dimension(68, 22));
        navLabel.setHorizontalAlignment(SwingConstants.CENTER);

        left.add(sendButton);
        left.add(vSep());
        left.add(prevButton);
        left.add(nextButton);
        left.add(navLabel);

        // Right: Target | Status  Time  Size
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
        bar.setBorder(new MatteBorder(1, 0, 0, 0, sep()));

        bottomStatusLabel = new JLabel("  Ready");
        bottomStatusLabel.setFont(bottomStatusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        bottomStatusLabel.setForeground(new Color(120, 120, 120));
        bottomStatusLabel.setBorder(new EmptyBorder(2, 8, 2, 8));
        bar.add(bottomStatusLabel, BorderLayout.WEST);
        return bar;
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

    private Color sep() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(200, 200, 200);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Single entry point for sending — used by both the UI button and programmatic
     * callers (MCP). If called from a non-EDT thread the action is marshalled to the
     * EDT automatically. The returned Future completes (with the SendResult) after
     * all UI state has been updated, so callers that need to wait can call .get().
     */
    public Future<RequestSender.SendResult> triggerSend() {
        CompletableFuture<RequestSender.SendResult> future = new CompletableFuture<>();

        Runnable action = () -> {
            if (isSending) { future.complete(null); return; }
            isSending = true;
            sendButton.setEnabled(false);
            sendButton.setText("…");
            setStatusChip("-", null);
            bottomStatusLabel.setText("  Sending…");

            syncTargetToMetadata();
            byte[] rawBytes = requestEditor.getRequest().toByteArray().getBytes();
            RequestData reqData = parseRawRequest(new String(rawBytes, StandardCharsets.UTF_8));
            tabData.setCurrentRequest(reqData);
            historyPos = -1;

            requestSender.sendAsync(reqData, result -> SwingUtilities.invokeLater(() -> {
                isSending = false;
                sendButton.setEnabled(true);
                sendButton.setText("Send");

                if (result.isSuccess()) {
                    ResponseData resp = result.getResponse();
                    tabData.setLatestResponse(resp);
                    try {
                        responseEditor.setResponse(HttpResponse.httpResponse(
                            ByteArray.byteArray(resp.getRawResponse().getBytes(StandardCharsets.UTF_8))));
                    } catch (Exception ignored) {}
                    applyStatus(resp.getStatusCode());
                    timeLabel.setText(resp.getResponseTime() + " ms");
                    sizeLabel.setText(formatSize(resp.getResponseSize()));
                    bottomStatusLabel.setText("  " + formatSize(resp.getResponseSize())
                        + "  |  " + resp.getResponseTime() + " ms");
                    HistoryEntry entry = historyManager.addEntry(tabData.getId(), reqData, resp);
                    tabData.addHistoryEntry(entry);
                    updateNavButtons();
                    updateInspector(reqData, resp);
                } else {
                    setStatusChip("ERR", Color.RED);
                    bottomStatusLabel.setText("  Error: " + result.getError());
                    JOptionPane.showMessageDialog(RepeaterTab.this, result.getError(),
                        "Send Error", JOptionPane.ERROR_MESSAGE);
                }
                future.complete(result); // notify any waiting caller
            }));
        };

        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
        return future;
    }

    private void navigateHistory(int direction) {
        List<HistoryEntry> history = tabData.getHistory();
        if (history == null || history.isEmpty()) return;
        if (direction == -1) {
            // ◄ Older: from live jump to most recent history (size-1), then walk toward 0
            historyPos = (historyPos == -1) ? history.size() - 1 : Math.max(historyPos - 1, 0);
        } else {
            // ► Newer: walk toward size-1, then back to live
            if (historyPos == history.size() - 1) historyPos = -1;
            else if (historyPos >= 0)             historyPos++;
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
            updateInspector(tabData.getCurrentRequest(), tabData.getLatestResponse());
        } else if (history != null && historyPos < history.size()) {
            HistoryEntry entry = history.get(historyPos);
            if (entry.getRequest()  != null) setEditorReq(entry.getRequest().getRawRequest());
            if (entry.getResponse() != null) {
                setEditorResp(entry.getResponse().getRawResponse());
                applyStatus(entry.getResponse().getStatusCode());
                timeLabel.setText(entry.getResponse().getResponseTime() + " ms");
                sizeLabel.setText(formatSize(entry.getResponse().getResponseSize()));
                int total = history.size();
                bottomStatusLabel.setText("  " + formatSize(entry.getResponse().getResponseSize())
                    + "  |  " + entry.getResponse().getResponseTime() + " ms"
                    + "  [" + (total - historyPos) + " of " + total + "]");
            }
            updateInspector(entry.getRequest(), entry.getResponse());
        }
    }

    // ── Status helpers ────────────────────────────────────────────────────────

    private void applyStatus(int code) {
        Color bg, fg;
        if      (code >= 200 && code < 300) { bg = new Color(210, 245, 210); fg = new Color(0, 120, 0); }
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
        // ◄ goes older (toward index 0) — disabled when already at oldest or no history
        prevButton.setEnabled(total > 0 && historyPos != 0);
        // ► goes newer (toward live) — disabled when already at live
        nextButton.setEnabled(historyPos != -1);
        if (total == 0)            navLabel.setText("");
        else if (historyPos == -1) navLabel.setText(total + " sent");
        else                       navLabel.setText((total - historyPos) + " / " + total);
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
        StringBuilder body  = new StringBuilder();
        boolean       inBody = false;
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

    // ── Inspector (Encoder / Decoder) ─────────────────────────────────────────

    private JPanel buildInspector() {
        JTextArea input  = new JTextArea(3, 60);
        JTextArea output = new JTextArea(3, 60);

        input.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setEditable(false);
        output.setBackground(UIManager.getColor("TextArea.background") != null
            ? UIManager.getColor("TextArea.background").darker() : new Color(245, 245, 245));

        JScrollPane inScroll  = new JScrollPane(input);
        JScrollPane outScroll = new JScrollPane(output);
        inScroll.setBorder(new MatteBorder(0, 0, 1, 0, sep()));
        outScroll.setBorder(null);

        // Button row
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        buttons.setBorder(new EmptyBorder(0, 4, 0, 4));

        addCodecBtn(buttons, "URL Decode",     input, output, s -> URLDecoder.decode(s, StandardCharsets.UTF_8));
        addCodecBtn(buttons, "URL Encode",     input, output, s -> URLEncoder.encode(s, StandardCharsets.UTF_8));
        addSep(buttons);
        addCodecBtn(buttons, "Base64 Decode",  input, output, s -> new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8));
        addCodecBtn(buttons, "Base64 Encode",  input, output, s -> Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
        addSep(buttons);
        addCodecBtn(buttons, "HTML Decode",    input, output, RepeaterTab::htmlDecode);
        addCodecBtn(buttons, "HTML Encode",    input, output, RepeaterTab::htmlEncode);
        addSep(buttons);
        addCodecBtn(buttons, "Hex Decode",     input, output, s -> new String(hexToBytes(s.trim()), StandardCharsets.UTF_8));
        addCodecBtn(buttons, "Hex Encode",     input, output, s -> bytesToHex(s.getBytes(StandardCharsets.UTF_8)));

        JButton clearBtn = new JButton("Clear");
        clearBtn.setFont(clearBtn.getFont().deriveFont(Font.PLAIN, 11f));
        clearBtn.addActionListener(e -> { input.setText(""); output.setText(""); });
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(clearBtn);

        // Labels
        JLabel inLabel  = sectionLabel("Input");
        JLabel outLabel = sectionLabel("Output");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inScroll, outScroll);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        split.setContinuousLayout(true);

        JPanel top = new JPanel(new BorderLayout(0, 0));
        top.add(inLabel,  BorderLayout.NORTH);
        top.add(inScroll, BorderLayout.CENTER);

        JPanel bot = new JPanel(new BorderLayout(0, 0));
        bot.add(outLabel,  BorderLayout.NORTH);
        bot.add(outScroll, BorderLayout.CENTER);

        JSplitPane textSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bot);
        textSplit.setResizeWeight(0.5);
        textSplit.setBorder(null);
        textSplit.setContinuousLayout(true);

        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBorder(new MatteBorder(1, 0, 0, 0, sep()));
        panel.add(buttons,   BorderLayout.NORTH);
        panel.add(textSplit, BorderLayout.CENTER);
        return panel;
    }

    private JLabel sectionLabel(String text) {
        JLabel lbl = new JLabel("  " + text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11f));
        lbl.setForeground(new Color(100, 100, 100));
        lbl.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, sep()), new EmptyBorder(2, 4, 2, 4)));
        return lbl;
    }

    private void addCodecBtn(JPanel bar, String label, JTextArea input, JTextArea output,
                             java.util.function.Function<String, String> fn) {
        JButton btn = new JButton(label);
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
        btn.setFocusPainted(false);
        btn.addActionListener(e -> {
            String in = input.getSelectedText();
            if (in == null || in.isEmpty()) in = input.getText();
            if (in.isEmpty()) return;
            try {
                output.setText(fn.apply(in));
            } catch (Exception ex) {
                output.setText("Error: " + ex.getMessage());
            }
        });
        bar.add(btn);
    }

    private void addSep(JPanel bar) {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 20));
        bar.add(s);
    }

    private void updateInspector(RequestData req, ResponseData resp) { /* no-op: inspector is stateless */ }

    // ── Codec helpers ──────────────────────────────────────────────────────────

    private static String htmlEncode(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#x27;");
    }

    private static String htmlDecode(String s) {
        return s.replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
                .replace("&quot;","\"").replace("&#x27;","'").replace("&#39;","'");
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "").replaceAll("0x", "").replaceAll("%", "");
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
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
            tabData.getMetadata().put("host",  u.getHost());
            tabData.getMetadata().put("port",  p);
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
        int     pNum = p != null ? Integer.parseInt(p.toString()) : (sec ? 443 : 80);
        boolean std  = (sec && pNum == 443) || (!sec && pNum == 80);
        return (sec ? "https" : "http") + "://" + h + (std ? "" : ":" + pNum);
    }

    private String extractPath(String urlStr) {
        if (urlStr == null) return "/";
        try { String p = new URL(urlStr).getPath(); return (p == null || p.isEmpty()) ? "/" : p; }
        catch (Exception e) { return urlStr.startsWith("/") ? urlStr : "/" + urlStr; }
    }

    private String formatSize(int bytes) {
        if (bytes < 1024)       return bytes + " B";
        if (bytes < 1024*1024)  return String.format("%.1f kB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // Normalize to CRLF: handles literal \r\n text (some MCP clients send 4-char sequences)
    // and LF-only line endings that Burp's HTTP parser doesn't accept.
    private static String crlf(String raw) {
        if (raw.contains("\\r\\n")) raw = raw.replace("\\r\\n", "\r\n");
        return raw.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
    }

    private void setEditorReq(String raw) {
        if (raw == null || raw.isBlank()) return;
        try { requestEditor.setRequest(HttpRequest.httpRequest(
                ByteArray.byteArray(crlf(raw).getBytes(StandardCharsets.UTF_8)))); }
        catch (Exception ignored) {}
    }

    private void setEditorResp(String raw) {
        if (raw == null || raw.isBlank()) return;
        try { responseEditor.setResponse(HttpResponse.httpResponse(
                ByteArray.byteArray(crlf(raw).getBytes(StandardCharsets.UTF_8)))); }
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
        String target = buildTargetUrl();
        if (targetField != null && !target.isEmpty()) targetField.setText(target);
        updateNavButtons();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setRequest(String rawRequest, String host, int port, boolean https) {
        // When called from MCP (host is empty), extract from the Host: header
        if (host == null || host.isBlank()) {
            RequestData parsed = parseRawRequest(crlf(rawRequest));
            host  = parsed.getHost();
            port  = parsed.getPort();
            https = parsed.isHttps();
        }
        if (tabData.getMetadata() == null) tabData.setMetadata(new HashMap<>());
        tabData.getMetadata().put("host",  host);
        tabData.getMetadata().put("port",  port);
        tabData.getMetadata().put("https", https);
        boolean std = (https && port == 443) || (!https && port == 80);
        if (targetField != null)
            targetField.setText((https ? "https" : "http") + "://" + host + (std ? "" : ":" + port));
        setEditorReq(rawRequest);
    }

    public void setTitleListener(TitleListener l) { this.titleListener = l; }
    public TabData getTabData()                    { return tabData; }
    public String  getTabId()                      { return tabData.getId(); }
}
