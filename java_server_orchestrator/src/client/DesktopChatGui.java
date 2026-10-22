package client;

import core.ImageMatrix;
import protocol.ProtocolConstants;
import protocol.ProtocolMessage;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

/**
 * DesktopChatGui: Client inference workspace for the Open AI Cubes architecture.
 * Dedicated to client-side visual ingestion, model prediction, and cluster connectivity.
 */
public class DesktopChatGui extends JFrame {
    private String clientId;
    private final String serverHost;
    private final int serverPort;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    // UI Elements
    private JTextArea chatArea;
    private DefaultTableModel workersTableModel;
    private JLabel workersCountLabel;
    private JComboBox<String> modeSelector;
    private JComboBox<String> sampleSelector;
    private JButton loadFileButton;
    private JButton identifyButton;
    private JLabel statusLabel;
    private JLabel sessionLabel;
    private ImagePreviewPanel previewPanel;
    private JLabel predictionLabel;
    private JLabel confidenceLabel;
    private JLabel latencyLabel;
    private JLabel handlerLabel;

    private ImageMatrix currentLoadedMatrix;

    public DesktopChatGui(String clientId, String serverHost, int serverPort) {
        super("Open AI Cubes | Client Inference Workspace");
        this.clientId = (clientId != null && !clientId.trim().isEmpty() && !clientId.equals("0812"))
                ? clientId
                : "Client-" + (1000 + new Random().nextInt(9000));
        this.serverHost = serverHost;
        this.serverPort = serverPort;

        initComponents();
        connectToServer();
        startWorkerPollTimer();
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1160, 740);
        setMinimumSize(new Dimension(1040, 680));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Top Header
        JPanel topHeader = new JPanel(new BorderLayout());
        topHeader.setBackground(new Color(245, 247, 250));
        topHeader.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setOpaque(false);

        JLabel titleLabel = new JLabel("Open AI Cubes | Client Workspace");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLabel.setForeground(new Color(30, 41, 59));

        sessionLabel = new JLabel("Active Session: " + clientId);
        sessionLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        sessionLabel.setForeground(new Color(16, 185, 129));

        titleBox.add(titleLabel);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(sessionLabel);
        topHeader.add(titleBox, BorderLayout.WEST);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerRight.setOpaque(false);
        headerRight.add(new JLabel("Execution Mode:"));
        String[] modes = {
                "Mode 4: Parallel Distributed (Concurrent Pipeline)",
                "Mode 3: Distributed (Sequential Pipeline)",
                "Mode 2: Parallel (Local Multi-Core)",
                "Mode 1: Sequential (Single-Thread)"
        };
        modeSelector = new JComboBox<>(modes);
        modeSelector.addActionListener(e -> onModeChanged());
        headerRight.add(modeSelector);

        statusLabel = new JLabel("Status: Connecting...");
        statusLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        headerRight.add(statusLabel);
        topHeader.add(headerRight, BorderLayout.EAST);
        add(topHeader, BorderLayout.NORTH);

        // Center Content: Left (Visual Ingestion & Inference), Right (Cluster Topology & Dialogue)
        JPanel mainPanel = new JPanel(new GridLayout(1, 2, 14, 14));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));

        // ==================== LEFT PANEL: INFERENCE & PREVIEW ====================
        JPanel leftPanel = new JPanel(new BorderLayout(10, 10));

        // Top Toolbar: Sample selector & load custom file
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.setBorder(BorderFactory.createTitledBorder("Dataset Image Selection"));
        String[] samples = {"panda", "dog", "cat", "lion", "bear"};
        sampleSelector = new JComboBox<>(samples);
        sampleSelector.setPreferredSize(new Dimension(110, 28));
        sampleSelector.addActionListener(e -> loadCatalogSample());

        loadFileButton = new JButton("Load Custom Image...");
        loadFileButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        loadFileButton.addActionListener(e -> chooseLocalFile());

        toolbar.add(new JLabel("Sample:"));
        toolbar.add(sampleSelector);
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(loadFileButton);
        leftPanel.add(toolbar, BorderLayout.NORTH);

        // Center: Full-fidelity image preview
        previewPanel = new ImagePreviewPanel();
        previewPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                "High-Resolution Image Preview",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                new Color(71, 85, 105)));
        leftPanel.add(previewPanel, BorderLayout.CENTER);

        // Bottom Left: Prediction Details & Trigger Button
        JPanel leftBottom = new JPanel(new BorderLayout(8, 8));
        JPanel metricsPanel = new JPanel();
        metricsPanel.setLayout(new BoxLayout(metricsPanel, BoxLayout.Y_AXIS));
        metricsPanel.setBackground(new Color(248, 250, 252));
        metricsPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(226, 232, 240)),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));

        predictionLabel = new JLabel("Class: NONE");
        predictionLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        predictionLabel.setForeground(new Color(15, 23, 42));

        confidenceLabel = new JLabel("Confidence: 0.0%");
        confidenceLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        confidenceLabel.setForeground(new Color(37, 99, 235));

        latencyLabel = new JLabel("Latency: 0.0 ms  |  Mode: PARALLEL");
        latencyLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        latencyLabel.setForeground(new Color(100, 116, 139));

        handlerLabel = new JLabel("Handler: Ready");
        handlerLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
        handlerLabel.setForeground(new Color(100, 116, 139));

        metricsPanel.add(predictionLabel);
        metricsPanel.add(Box.createVerticalStrut(4));
        metricsPanel.add(confidenceLabel);
        metricsPanel.add(Box.createVerticalStrut(4));
        metricsPanel.add(latencyLabel);
        metricsPanel.add(Box.createVerticalStrut(2));
        metricsPanel.add(handlerLabel);
        leftBottom.add(metricsPanel, BorderLayout.CENTER);

        identifyButton = new JButton("Run Inference on Cluster");
        identifyButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        identifyButton.setBackground(new Color(37, 99, 235));
        identifyButton.setForeground(Color.WHITE);
        identifyButton.setFocusPainted(false);
        identifyButton.setPreferredSize(new Dimension(100, 44));
        identifyButton.addActionListener(e -> executeInference());
        leftBottom.add(identifyButton, BorderLayout.SOUTH);

        leftPanel.add(leftBottom, BorderLayout.SOUTH);
        mainPanel.add(leftPanel);

        // ==================== RIGHT PANEL: CLUSTER TOPOLOGY & DIALOGUE ====================
        JPanel rightPanel = new JPanel(new BorderLayout(10, 10));

        // Top Right: Connected Worker Cubes Table
        JPanel topologyCard = new JPanel(new BorderLayout(6, 6));
        topologyCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                "Connected Worker Nodes",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                new Color(71, 85, 105)));

        String[] colNames = {"Worker ID", "Language", "Threads", "Status"};
        workersTableModel = new DefaultTableModel(colNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable workersTable = new JTable(workersTableModel);
        workersTable.setRowHeight(24);
        workersTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        workersTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        workersTable.getTableHeader().setBackground(new Color(241, 245, 249));

        JScrollPane tableScroll = new JScrollPane(workersTable);
        tableScroll.setPreferredSize(new Dimension(450, 150));
        topologyCard.add(tableScroll, BorderLayout.CENTER);

        workersCountLabel = new JLabel("Connected Cubes: 0");
        workersCountLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        workersCountLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        topologyCard.add(workersCountLabel, BorderLayout.SOUTH);
        rightPanel.add(topologyCard, BorderLayout.NORTH);

        // Bottom Right: Chat & Event Dialogue Log
        JPanel chatCard = new JPanel(new BorderLayout(6, 6));
        chatCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                "Session Dialogue & Audit Log",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 12),
                new Color(71, 85, 105)));

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatArea.setBackground(new Color(15, 23, 42));
        chatArea.setForeground(new Color(241, 245, 249));
        chatArea.setCaretColor(Color.WHITE);
        JScrollPane chatScroll = new JScrollPane(chatArea);

        JPanel chatInputRow = new JPanel(new BorderLayout(6, 6));
        JTextField chatInputField = new JTextField();
        chatInputField.setFont(new Font("SansSerif", Font.PLAIN, 12));
        JButton sendButton = new JButton("Send");
        sendButton.setFont(new Font("SansSerif", Font.BOLD, 12));

        Runnable sendMsg = () -> {
            String text = chatInputField.getText().trim();
            if (!text.isEmpty()) {
                appendChat(clientId + ": " + text);
                chatInputField.setText("");
                handleUserChatMessage(text);
            }
        };
        sendButton.addActionListener(e -> sendMsg.run());
        chatInputField.addActionListener(e -> sendMsg.run());

        chatInputRow.add(chatInputField, BorderLayout.CENTER);
        chatInputRow.add(sendButton, BorderLayout.EAST);

        chatCard.add(chatScroll, BorderLayout.CENTER);
        chatCard.add(chatInputRow, BorderLayout.SOUTH);
        rightPanel.add(chatCard, BorderLayout.CENTER);

        mainPanel.add(rightPanel);
        add(mainPanel, BorderLayout.CENTER);

        loadCatalogSample();
    }

    private void handleUserChatMessage(String query) {
        String lower = query.toLowerCase();
        if (lower.contains("status")) {
            appendChat("System: Session is active. Mode: " + modeSelector.getSelectedItem());
        } else if (lower.contains("help")) {
            appendChat("System: Available commands: 'status', 'infer', 'workers', 'mode <1-4>'");
        } else if (lower.contains("workers")) {
            requestWorkersUpdate();
            appendChat("System: Querying connected worker nodes...");
        } else if (lower.contains("infer")) {
            executeInference();
        } else {
            appendChat("System: Request received. Client thread handling query.");
        }
    }

    private void loadCatalogSample() {
        String sample = (String) sampleSelector.getSelectedItem();
        if (sample == null) sample = "panda";
        try {
            currentLoadedMatrix = ImageMatrix.createSample(sample);
            previewPanel.setImage(currentLoadedMatrix);
            appendChat("System: Loaded dataset sample: " + sample.toUpperCase());
        } catch (Exception e) {
            appendChat("System: Notice loading sample (" + e.getMessage() + ")");
        }
    }

    private void chooseLocalFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Image File");
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                currentLoadedMatrix = ImageMatrix.fromFile(f, "custom");
                previewPanel.setImage(currentLoadedMatrix);
                appendChat("System: Loaded image file: " + f.getName());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to read image: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void executeInference() {
        if (currentLoadedMatrix == null) {
            JOptionPane.showMessageDialog(this, "Please select or load an image first.", "Notice", JOptionPane.WARNING_MESSAGE);
            return;
        }

        identifyButton.setEnabled(false);
        predictionLabel.setText("Class: EVALUATING...");
        confidenceLabel.setText("Confidence: --");
        latencyLabel.setText("Processing request...");

        new Thread(() -> {
            try {
                if (socket == null || socket.isClosed()) {
                    connectToServer();
                    Thread.sleep(300);
                }

                String payload = currentLoadedMatrix.getLabel() + "," + currentLoadedMatrix.toCsvString();
                ProtocolMessage req = new ProtocolMessage(ProtocolConstants.CMD_CLIENT_REQ, clientId, payload);
                synchronized (writer) {
                    writer.write(req.toWireString());
                    writer.flush();
                }

                String respLine = reader.readLine();
                ProtocolMessage resp = ProtocolMessage.parse(respLine);

                SwingUtilities.invokeLater(() -> {
                    if (resp != null && ProtocolConstants.CMD_CLIENT_RESP.equals(resp.getAction())) {
                        String[] parts = resp.getPayload().split("\\|");
                        String label = parts[0];
                        String conf = (parts.length > 1) ? parts[1] : "0.0";
                        String modeUsed = (parts.length > 2) ? parts[2] : "Mode";
                        String latMs = (parts.length > 3) ? parts[3] : "0.0";
                        String trace = (parts.length > 5) ? parts[5] : "Cluster Pipeline";

                        predictionLabel.setText("Class: " + label.toUpperCase());
                        confidenceLabel.setText("Confidence: " + conf + "%");
                        latencyLabel.setText("Latency: " + latMs + " ms  |  Mode: " + modeUsed);
                        handlerLabel.setText("Handler: " + trace);

                        appendChat("Cluster: Inference completed successfully");
                        appendChat("         Predicted Class: " + label.toUpperCase());
                        appendChat("         Confidence:      " + conf + "%");
                        appendChat("         Latency:         " + latMs + " ms");
                        appendChat("         Pipeline Trace:  " + trace);
                    } else {
                        appendChat("Cluster: Received invalid inference response.");
                    }
                    identifyButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendChat("Cluster: Network communication error (" + ex.getMessage() + ")");
                    identifyButton.setEnabled(true);
                });
            }
        }, "ClientInferThread-" + System.currentTimeMillis()).start();
    }

    private void onModeChanged() {
        int idx = modeSelector.getSelectedIndex();
        String modeName = "PARALLEL_DISTRIBUTED";
        if (idx == 1) modeName = "DISTRIBUTED";
        else if (idx == 2) modeName = "PARALLEL";
        else if (idx == 3) modeName = "SEQUENTIAL";

        final String finalMode = modeName;
        new Thread(() -> {
            try {
                if (socket == null || socket.isClosed()) connectToServer();
                ProtocolMessage req = new ProtocolMessage("SET_MODE", clientId, finalMode);
                synchronized (writer) {
                    writer.write(req.toWireString());
                    writer.flush();
                }
                appendChat("Client: Switched cluster execution mode to " + finalMode);
            } catch (Exception ex) {
                appendChat("Client: Error changing mode: " + ex.getMessage());
            }
        }).start();
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                socket = new Socket(serverHost, serverPort);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                // Read optional dynamic ID assignment greeting from server
                try {
                    socket.setSoTimeout(1500);
                    String firstLine = reader.readLine();
                    if (firstLine != null) {
                        ProtocolMessage greeting = ProtocolMessage.parse(firstLine);
                        if (greeting != null && "ASSIGN_CLIENT_ID".equals(greeting.getAction())) {
                            this.clientId = greeting.getId();
                            SwingUtilities.invokeLater(() -> sessionLabel.setText("Active Session: " + clientId));
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    socket.setSoTimeout(0);
                }

                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Connected to " + serverHost + ":" + serverPort);
                    statusLabel.setForeground(new Color(22, 163, 74));
                });
                requestWorkersUpdate();
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Status: Disconnected (" + e.getMessage() + ")");
                    statusLabel.setForeground(new Color(220, 38, 38));
                });
            }
        }, "ClientNetInitThread").start();
    }

    private void requestWorkersUpdate() {
        if (socket == null || socket.isClosed()) return;
        new Thread(() -> {
            try {
                ProtocolMessage req = new ProtocolMessage(ProtocolConstants.CMD_GET_WORKERS, clientId, "");
                synchronized (writer) {
                    writer.write(req.toWireString());
                    writer.flush();
                }

                String respLine = reader.readLine();
                ProtocolMessage resp = ProtocolMessage.parse(respLine);
                if (resp != null && ProtocolConstants.CMD_WORKERS_RESP.equals(resp.getAction())) {
                    SwingUtilities.invokeLater(() -> updateWorkersTable(resp.getPayload()));
                }
            } catch (Exception ignored) {}
        }, "WorkersPollThread").start();
    }

    private void updateWorkersTable(String payload) {
        workersTableModel.setRowCount(0);
        if (payload == null || payload.trim().isEmpty()) {
            workersCountLabel.setText("Connected Cubes: 0");
            return;
        }
        String[] rows = payload.split(";");
        int count = 0;
        for (String r : rows) {
            String[] parts = r.split(",");
            if (parts.length >= 5) {
                workersTableModel.addRow(new Object[]{parts[0], parts[1], parts[2] + " threads", parts[4]});
                count++;
            }
        }
        workersCountLabel.setText("Connected Cubes: " + count);
    }

    private void startWorkerPollTimer() {
        Timer timer = new Timer(4000, e -> requestWorkersUpdate());
        timer.start();
    }

    private void appendChat(String message) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        chatArea.append("[" + timeStamp + "] " + message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    /**
     * Preview canvas rendering full-fidelity image without aspect ratio distortion.
     */
    private static class ImagePreviewPanel extends JPanel {
        private ImageMatrix matrix;

        public void setImage(ImageMatrix matrix) {
            this.matrix = matrix;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            int panelW = getWidth();
            int panelH = getHeight();
            g2.setColor(new Color(15, 23, 42));
            g2.fillRect(0, 0, panelW, panelH);

            if (matrix == null) {
                g2.setColor(new Color(148, 163, 184));
                g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
                g2.drawString("Loading image...", panelW / 2 - 50, panelH / 2);
                return;
            }

            BufferedImage img = (matrix.getSourceImage() != null)
                    ? matrix.getSourceImage()
                    : matrix.toBufferedImage();

            if (img == null) return;

            int imgW = img.getWidth();
            int imgH = img.getHeight();
            int pad = 8;
            int availW = Math.max(1, panelW - 2 * pad);
            int availH = Math.max(1, panelH - 2 * pad);

            double scale = Math.min((double) availW / imgW, (double) availH / imgH);
            int drawW = Math.max(1, (int) Math.round(imgW * scale));
            int drawH = Math.max(1, (int) Math.round(imgH * scale));
            int drawX = (panelW - drawW) / 2;
            int drawY = (panelH - drawH) / 2;

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.drawImage(img, drawX, drawY, drawW, drawH, null);
            g2.setColor(new Color(51, 65, 85));
            g2.drawRect(drawX, drawY, drawW, drawH);
        }
    }

    public static void main(String[] args) {
        String id = (args.length > 0) ? args[0] : null;
        String host = (args.length > 1) ? args[1] : "localhost";
        int port = (args.length > 2) ? Integer.parseInt(args[2]) : ProtocolConstants.DEFAULT_CLIENT_PORT;

        SwingUtilities.invokeLater(() -> {
            DesktopChatGui gui = new DesktopChatGui(id, host, port);
            gui.setVisible(true);
        });
    }
}
