package com.repeaterx.ui;

import com.repeaterx.core.HistoryManager;
import com.repeaterx.model.HistoryEntry;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class SearchPanel extends JPanel {
    private final HistoryManager historyManager;
    private JTextField searchField;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;

    private static final String[] COLS = {"Method", "URL", "Status", "Time(ms)", "Size", "Timestamp"};

    public SearchPanel(HistoryManager historyManager) {
        this.historyManager = historyManager;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Global Search"));

        searchField = new JTextField();
        searchField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        searchField.addActionListener(e -> performSearch());

        JButton searchBtn = new JButton("Search");
        searchBtn.addActionListener(e -> performSearch());

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            searchField.setText("");
            tableModel.setRowCount(0);
            statusLabel.setText(" ");
        });

        JPanel searchBar = new JPanel(new BorderLayout(5, 0));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        btns.add(searchBtn);
        btns.add(clearBtn);
        searchBar.add(new JLabel("  Search: "), BorderLayout.WEST);
        searchBar.add(searchField, BorderLayout.CENTER);
        searchBar.add(btns, BorderLayout.EAST);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));

        JPanel topPanel = new JPanel(new BorderLayout(0, 3));
        topPanel.add(searchBar, BorderLayout.NORTH);
        topPanel.add(statusLabel, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(80);
        resultsTable.getColumnModel().getColumn(2).setMaxWidth(70);
        resultsTable.getColumnModel().getColumn(3).setMaxWidth(90);
        resultsTable.getColumnModel().getColumn(4).setMaxWidth(90);
        resultsTable.getColumnModel().getColumn(5).setMaxWidth(90);
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(resultsTable), BorderLayout.CENTER);
    }

    private void performSearch() {
        String q = searchField.getText().trim();
        tableModel.setRowCount(0);
        List<HistoryEntry> results = historyManager.search(q);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        for (HistoryEntry e : results) {
            tableModel.addRow(new Object[]{
                e.getRequest() != null ? e.getRequest().getMethod() : "-",
                e.getRequest() != null ? e.getRequest().getUrl() : "-",
                e.getResponse() != null ? e.getResponse().getStatusCode() : "-",
                e.getResponse() != null ? e.getResponse().getResponseTime() : "-",
                e.getResponse() != null ? e.getResponse().getResponseSize() : "-",
                sdf.format(new Date(e.getTimestamp()))
            });
        }
        statusLabel.setText("  Found " + results.size() + " result(s)" + (q.isEmpty() ? " (all history)" : " for: \"" + q + "\""));
    }
}
