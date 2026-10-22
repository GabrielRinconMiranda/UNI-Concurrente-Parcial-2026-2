package server;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * AdminMonitorGui: Dedicated cluster administration and monitoring interface.
 * Provides real-time visibility into worker node allocation, active client sessions,
 * cumulative task completion metrics, and cluster audit events.
 */
public class AdminMonitorGui extends JFrame {
    private final String serverBaseUrl;

    // Header Metrics
    private JLabel statusBadge;
    private JLabel uptimeLabel;
    private JLabel totalRequestsLabel;
    private JLabel activeWorkersLabel;
    private JLabel activeClientsLabel;
    private JComboBox<String> modeSelector;

    // Tables
    private DefaultTableModel workersModel;
    private DefaultTableModel clientsModel;
    private DefaultTableModel eventsModel;

    private Timer pollTimer;

    public AdminMonitorGui(String serverBaseUrl) {
        super("Open AI Cubes | Cluster Administration & Telemetry Console");
        this.serverBaseUrl = (serverBaseUrl != null) ? serverBaseUrl.replaceAll("/+$", "") : "http://localhost:8080";

        initComponents();
        startPolling();
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1240, 780);
        setMinimumSize(new Dimension(1080, 680));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Top Header
        JPanel topHeader = new JPanel(new BorderLayout());
        topHeader.setBackground(new Color(15, 23, 42));
        topHeader.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setOpaque(false);

        JLabel titleLabel = new JLabel("Open AI Cubes | Cluster Administration Console");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 19));
        titleLabel.setForeground(new Color(96, 165, 250));

        JLabel subLabel = new JLabel("Real-time distributed telemetry, worker node assignment, and client auditing");
        subLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subLabel.setForeground(new Color(148, 163, 184));

        titleBox.add(titleLabel);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(subLabel);
        topHeader.add(titleBox, BorderLayout.WEST);

        // Header Right: Execution Mode & Status
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 4));
        headerRight.setOpaque(false);

        JLabel modeLbl = new JLabel("Active Cluster Mode:");
        modeLbl.setForeground(new Color(226, 232, 240));
        modeLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
        headerRight.add(modeLbl);

        String[] modes = {
                "PARALLEL_DISTRIBUTED",
                "DISTRIBUTED",
                "PARALLEL",
                "SEQUENTIAL"
        };
        modeSelector = new JComboBox<>(modes);
        modeSelector.setBackground(new Color(30, 41, 59));
        modeSelector.setForeground(Color.WHITE);
        modeSelector.addActionListener(e -> onModeSelected());
        headerRight.add(modeSelector);

        statusBadge = new JLabel("  CONNECTED  ");
        statusBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
        statusBadge.setOpaque(true);
        statusBadge.setBackground(new Color(16, 185, 129));
        statusBadge.setForeground(Color.WHITE);
        statusBadge.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        headerRight.add(statusBadge);

        topHeader.add(headerRight, BorderLayout.EAST);
        add(topHeader, BorderLayout.NORTH);

        // Subheader: Quick KPI Cards
        JPanel kpiRow = new JPanel(new GridLayout(1, 4, 12, 12));
        kpiRow.setBorder(BorderFactory.createEmptyBorder(10, 16, 6, 16));
        kpiRow.setBackground(new Color(241, 245, 249));

        totalRequestsLabel = new JLabel("0", SwingConstants.CENTER);
        activeWorkersLabel = new JLabel("0", SwingConstants.CENTER);
        activeClientsLabel = new JLabel("0", SwingConstants.CENTER);
        uptimeLabel = new JLabel("0s", SwingConstants.CENTER);

        kpiRow.add(createKpiCard("Total Inferences Handled", totalRequestsLabel, new Color(37, 99, 235)));
        kpiRow.add(createKpiCard("Active Worker Cubes", activeWorkersLabel, new Color(16, 185, 129)));
        kpiRow.add(createKpiCard("Active Client Sessions", activeClientsLabel, new Color(147, 51, 234)));
        kpiRow.add(createKpiCard("Cluster Uptime", uptimeLabel, new Color(71, 85, 105)));
        add(kpiRow, BorderLayout.SOUTH);

        // Center Content: Split into Top (Workers + Clients) and Bottom (Live Dispatch Event Log)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplit.setResizeWeight(0.52);
        mainSplit.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));

        // Top Split: Left (Workers Table), Right (Clients Table)
        JPanel tablesPanel = new JPanel(new GridLayout(1, 2, 12, 12));

        // 1. Worker Cubes Table
        JPanel workersCard = new JPanel(new BorderLayout(6, 6));
        workersCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                "Worker Nodes & Completed Task Metrics",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                new Color(30, 41, 59)));

        String[] workerCols = {"Worker ID", "Language", "Threads", "Completed Tasks", "Status", "Endpoint"};
        workersModel = new DefaultTableModel(workerCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable workersTable = new JTable(workersModel);
        workersTable.setRowHeight(24);
        workersTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        workersTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        workersTable.getTableHeader().setBackground(new Color(241, 245, 249));
        workersCard.add(new JScrollPane(workersTable), BorderLayout.CENTER);
        tablesPanel.add(workersCard);

        // 2. Active Client Sessions Table
        JPanel clientsCard = new JPanel(new BorderLayout(6, 6));
        clientsCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                "Active Client Sessions (Multi-Client Concurrency)",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                new Color(30, 41, 59)));

        String[] clientCols = {"Client ID", "Remote Address", "Inferences", "Last Prediction", "Latency (ms)"};
        clientsModel = new DefaultTableModel(clientCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable clientsTable = new JTable(clientsModel);
        clientsTable.setRowHeight(24);
        clientsTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        clientsTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        clientsTable.getTableHeader().setBackground(new Color(241, 245, 249));
        clientsCard.add(new JScrollPane(clientsTable), BorderLayout.CENTER);
        tablesPanel.add(clientsCard);

        mainSplit.setTopComponent(tablesPanel);

        // Bottom Split: Live Dispatch Event Stream
        JPanel eventsCard = new JPanel(new BorderLayout(6, 6));
        eventsCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                "Live Dispatch & Cluster Audit Event Log",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                new Color(30, 41, 59)));

        String[] eventCols = {"Time", "Event Type", "Origin / Client", "Event Details"};
        eventsModel = new DefaultTableModel(eventCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable eventsTable = new JTable(eventsModel);
        eventsTable.setRowHeight(22);
        eventsTable.setFont(new Font("Monospaced", Font.PLAIN, 11));
        eventsTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        eventsTable.getColumnModel().getColumn(1).setPreferredWidth(130);
        eventsTable.getColumnModel().getColumn(2).setPreferredWidth(140);
        eventsTable.getColumnModel().getColumn(3).setPreferredWidth(700);

        JPanel eventToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        JButton clearLogsBtn = new JButton("Clear Log Buffer");
        clearLogsBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        clearLogsBtn.addActionListener(e -> clearAuditLogs());
        eventToolbar.add(clearLogsBtn);

        eventsCard.add(new JScrollPane(eventsTable), BorderLayout.CENTER);
        eventsCard.add(eventToolbar, BorderLayout.SOUTH);

        mainSplit.setBottomComponent(eventsCard);
        add(mainSplit, BorderLayout.CENTER);
    }

    private JPanel createKpiCard(String title, JLabel valueLabel, Color accent) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(226, 232, 240)),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));

        JLabel tLabel = new JLabel(title);
        tLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        tLabel.setForeground(new Color(100, 116, 139));

        valueLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        valueLabel.setForeground(accent);

        card.add(tLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private void onModeSelected() {
        String selected = (String) modeSelector.getSelectedItem();
        if (selected == null) return;
        new Thread(() -> {
            try {
                URL url = new URL(serverBaseUrl + "/api/admin/mode");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.getOutputStream().write(selected.getBytes(StandardCharsets.UTF_8));
                conn.getResponseCode();
            } catch (Exception ignored) {}
        }).start();
    }

    private void clearAuditLogs() {
        new Thread(() -> {
            try {
                URL url = new URL(serverBaseUrl + "/api/admin/clear-logs");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.getResponseCode();
                SwingUtilities.invokeLater(() -> eventsModel.setRowCount(0));
            } catch (Exception ignored) {}
        }).start();
    }

    private void startPolling() {
        pollTimer = new Timer(1500, e -> queryClusterOverview());
        pollTimer.start();
        queryClusterOverview();
    }

    private void queryClusterOverview() {
        new Thread(() -> {
            try {
                URL url = new URL(serverBaseUrl + "/api/admin/overview");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    parseOverviewJson(sb.toString());
                    SwingUtilities.invokeLater(() -> {
                        statusBadge.setText("  CONNECTED  ");
                        statusBadge.setBackground(new Color(16, 185, 129));
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        statusBadge.setText("  HTTP " + code + "  ");
                        statusBadge.setBackground(new Color(239, 68, 68));
                    });
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    statusBadge.setText("  OFFLINE  ");
                    statusBadge.setBackground(new Color(239, 68, 68));
                });
            }
        }).start();
    }

    private void parseOverviewJson(String json) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Extract metrics
                String mode = extractJsonField(json, "mode");
                if (mode != null && !mode.isEmpty() && !modeSelector.getSelectedItem().equals(mode)) {
                    modeSelector.setSelectedItem(mode);
                }

                String reqs = extractJsonField(json, "total_requests");
                if (reqs != null) totalRequestsLabel.setText(reqs);

                String uptime = extractJsonField(json, "uptime_seconds");
                if (uptime != null) {
                    long sec = Long.parseLong(uptime);
                    uptimeLabel.setText(String.format("%02dh %02dm %02ds", sec / 3600, (sec % 3600) / 60, sec % 60));
                }

                // Parse Workers
                int wStart = json.indexOf("\"workers\":[");
                if (wStart != -1) {
                    int wEnd = json.indexOf("],\"clients\":[", wStart);
                    if (wEnd == -1) wEnd = json.indexOf("]", wStart);
                    String wSub = json.substring(wStart + 11, wEnd);
                    updateWorkersFromJson(wSub);
                }

                // Parse Clients
                int cStart = json.indexOf("\"clients\":[");
                if (cStart != -1) {
                    int cEnd = json.indexOf("],\"events\":[", cStart);
                    if (cEnd == -1) cEnd = json.indexOf("]", cStart);
                    String cSub = json.substring(cStart + 11, cEnd);
                    updateClientsFromJson(cSub);
                }

                // Parse Events
                int eStart = json.indexOf("\"events\":[");
                if (eStart != -1) {
                    int eEnd = json.lastIndexOf("]}");
                    if (eEnd != -1) {
                        String eSub = json.substring(eStart + 10, eEnd);
                        updateEventsFromJson(eSub);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void updateWorkersFromJson(String json) {
        workersModel.setRowCount(0);
        if (json.trim().isEmpty()) {
            activeWorkersLabel.setText("0");
            return;
        }
        String[] items = json.split("\\},\\{");
        int count = 0;
        for (String item : items) {
            String clean = item.replace("{", "").replace("}", "");
            String id = extractJsonField(clean, "id");
            String lang = extractJsonField(clean, "language");
            String threads = extractJsonField(clean, "threads");
            String completed = extractJsonField(clean, "completed_tasks");
            String status = extractJsonField(clean, "status");
            String endpoint = extractJsonField(clean, "endpoint");

            workersModel.addRow(new Object[]{id, lang, threads + " threads", completed, status, endpoint});
            count++;
        }
        activeWorkersLabel.setText(String.valueOf(count));
    }

    private void updateClientsFromJson(String json) {
        clientsModel.setRowCount(0);
        if (json.trim().isEmpty()) {
            activeClientsLabel.setText("0");
            return;
        }
        String[] items = json.split("\\},\\{");
        int count = 0;
        for (String item : items) {
            String clean = item.replace("{", "").replace("}", "");
            String id = extractJsonField(clean, "client_id");
            String remote = extractJsonField(clean, "remote_address");
            String infs = extractJsonField(clean, "total_inferences");
            String pred = extractJsonField(clean, "last_prediction");
            String lat = extractJsonField(clean, "last_latency_ms");

            clientsModel.addRow(new Object[]{id, remote, infs, pred, lat});
            count++;
        }
        activeClientsLabel.setText(String.valueOf(count));
    }

    private void updateEventsFromJson(String json) {
        eventsModel.setRowCount(0);
        if (json.trim().isEmpty()) return;
        String[] items = json.split("\\},\\{");
        for (String item : items) {
            String clean = item.replace("{", "").replace("}", "");
            String time = extractJsonField(clean, "time_str");
            String type = extractJsonField(clean, "type");
            String client = extractJsonField(clean, "client_id");
            String details = extractJsonField(clean, "details");

            eventsModel.addRow(new Object[]{time, type, client, details});
        }
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) return "";
        int start = idx + key.length();
        if (start >= json.length()) return "";
        char first = json.charAt(start);
        if (first == '"') {
            int end = json.indexOf("\"", start + 1);
            return (end != -1) ? json.substring(start + 1, end) : "";
        } else {
            int end = json.indexOf(",", start);
            int endB = json.indexOf("}", start);
            if (end == -1) end = endB;
            else if (endB != -1 && endB < end) end = endB;
            return (end != -1) ? json.substring(start, end).trim() : json.substring(start).trim();
        }
    }

    public static void main(String[] args) {
        String baseUrl = (args.length > 0) ? args[0] : "http://localhost:8080";
        SwingUtilities.invokeLater(() -> {
            AdminMonitorGui gui = new AdminMonitorGui(baseUrl);
            gui.setVisible(true);
        });
    }
}
