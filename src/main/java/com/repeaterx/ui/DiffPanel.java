package com.repeaterx.ui;

import com.repeaterx.model.HistoryEntry;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class DiffPanel extends JPanel {
    private JTextPane leftPane;
    private JTextPane rightPane;
    private JComboBox<String> leftSelector;
    private JComboBox<String> rightSelector;
    private final List<HistoryEntry> entries;

    private static final Color ADD_COLOR = new Color(200, 255, 200);
    private static final Color DEL_COLOR = new Color(255, 200, 200);
    private static final Color CHG_COLOR = new Color(200, 220, 255);

    public DiffPanel(List<HistoryEntry> entries) {
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Request / Response Diff"));

        String[] entryNames = buildEntryNames();
        leftSelector = new JComboBox<>(entryNames);
        rightSelector = new JComboBox<>(entryNames);
        if (entryNames.length > 1) rightSelector.setSelectedIndex(1);

        JPanel selectorPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        JPanel leftSel = new JPanel(new BorderLayout(3, 0));
        JPanel rightSel = new JPanel(new BorderLayout(3, 0));
        leftSel.add(new JLabel("Left:"), BorderLayout.WEST);
        leftSel.add(leftSelector, BorderLayout.CENTER);
        rightSel.add(new JLabel("Right:"), BorderLayout.WEST);
        rightSel.add(rightSelector, BorderLayout.CENTER);
        selectorPanel.add(leftSel);
        selectorPanel.add(rightSel);

        JButton compareBtn = new JButton("Compare");
        compareBtn.addActionListener(e -> runDiff());

        JPanel topPanel = new JPanel(new BorderLayout(5, 0));
        topPanel.add(selectorPanel, BorderLayout.CENTER);
        topPanel.add(compareBtn, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        leftPane = new JTextPane();
        rightPane = new JTextPane();
        leftPane.setEditable(false);
        rightPane.setEditable(false);
        leftPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        rightPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(leftPane), new JScrollPane(rightPane));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        legend.add(colorChip(ADD_COLOR)); legend.add(new JLabel("Added"));
        legend.add(Box.createHorizontalStrut(10));
        legend.add(colorChip(DEL_COLOR)); legend.add(new JLabel("Removed"));
        legend.add(Box.createHorizontalStrut(10));
        legend.add(colorChip(CHG_COLOR)); legend.add(new JLabel("Changed"));
        add(legend, BorderLayout.SOUTH);
    }

    private JPanel colorChip(Color c) {
        JPanel p = new JPanel();
        p.setBackground(c);
        p.setPreferredSize(new Dimension(14, 14));
        p.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        return p;
    }

    private String[] buildEntryNames() {
        if (entries.isEmpty()) return new String[]{"(no entries)"};
        return entries.stream().map(e -> {
            String method = e.getRequest() != null ? e.getRequest().getMethod() : "?";
            String url = e.getRequest() != null ? e.getRequest().getUrl() : "?";
            int status = e.getResponse() != null ? e.getResponse().getStatusCode() : 0;
            return method + " " + url + " [" + status + "]";
        }).toArray(String[]::new);
    }

    private void runDiff() {
        int li = leftSelector.getSelectedIndex();
        int ri = rightSelector.getSelectedIndex();
        if (li < 0 || ri < 0 || li >= entries.size() || ri >= entries.size()) {
            leftPane.setText("No entries selected");
            rightPane.setText("No entries selected");
            return;
        }
        HistoryEntry left = entries.get(li);
        HistoryEntry right = entries.get(ri);
        String leftText = left.getRequest() != null && left.getRequest().getRawRequest() != null
            ? left.getRequest().getRawRequest() : "(empty)";
        String rightText = right.getRequest() != null && right.getRequest().getRawRequest() != null
            ? right.getRequest().getRawRequest() : "(empty)";
        diffText(leftPane, rightPane, leftText, rightText);
    }

    private void diffText(JTextPane lp, JTextPane rp, String leftText, String rightText) {
        lp.setText("");
        rp.setText("");
        String[] leftLines = leftText.split("\n", -1);
        String[] rightLines = rightText.split("\n", -1);
        int maxLen = Math.max(leftLines.length, rightLines.length);
        StyledDocument ld = lp.getStyledDocument();
        StyledDocument rd = rp.getStyledDocument();
        for (int i = 0; i < maxLen; i++) {
            String l = i < leftLines.length ? leftLines[i] : "";
            String r = i < rightLines.length ? rightLines[i] : "";
            Color lc, rc;
            if (l.equals(r)) {
                lc = rc = Color.WHITE;
            } else if (l.isEmpty()) {
                lc = rc = ADD_COLOR;
            } else if (r.isEmpty()) {
                lc = rc = DEL_COLOR;
            } else {
                lc = rc = CHG_COLOR;
            }
            appendLine(ld, l + "\n", lc);
            appendLine(rd, r + "\n", rc);
        }
    }

    private void appendLine(StyledDocument doc, String text, Color bg) {
        Style style = doc.addStyle("s" + doc.getLength(), null);
        StyleConstants.setBackground(style, bg);
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException e) {
            // ignore
        }
    }

    public void setEntries(List<HistoryEntry> newEntries) {
        entries.clear();
        if (newEntries != null) entries.addAll(newEntries);
        String[] names = buildEntryNames();
        leftSelector.setModel(new DefaultComboBoxModel<>(names));
        rightSelector.setModel(new DefaultComboBoxModel<>(names));
        if (names.length > 1) rightSelector.setSelectedIndex(1);
    }
}
