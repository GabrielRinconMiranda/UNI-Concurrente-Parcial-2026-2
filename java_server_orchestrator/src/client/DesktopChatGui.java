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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

/**
 * DesktopChatGui: Cliente de inferencia neuronal distribuida para Open AI Cubes.
 * Orientado exclusivamente al usuario/cliente. Cero datos simulados (100% dataset real).
 */
public class DesktopChatGui extends JFrame {
    private final String clientId;
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
    private ImagePreviewPanel previewPanel;
    private JLabel predictionLabel;
    private JLabel confidenceLabel;
    private JLabel latencyLabel;
    private JLabel handlerLabel;

    private ImageMatrix currentLoadedMatrix;

    public DesktopChatGui(String clientId, String serverHost, int serverPort) {
        super("Open AI Cubes - Cliente de Inferencia Neuronal");
        this.clientId = clientId;
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

        JLabel titleLabel = new JLabel("Open AI Cubes | Cliente de Inferencia");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLabel.setForeground(new Color(30, 41, 59));

        JLabel validatedUserLabel = new JLabel("Sesión Concurrente Activa  |  idClientChat: " + clientId);
        validatedUserLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        validatedUserLabel.setForeground(new Color(16, 185, 129));

        titleBox.add(titleLabel);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(validatedUserLabel);
        topHeader.add(titleBox, BorderLayout.WEST);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        headerRight.setOpaque(false);
        headerRight.add(new JLabel("Modo de Clúster:"));
        String[] modes = {
                "4. Distribuido Paralelo (Pipeline Concurrente)",
                "3. Distribuido (Pipeline de Microcubos)",
                "2. Paralelo (Multinúcleo Local)",
                "1. Secuencial (Mononúcleo Local)"
        };
        modeSelector = new JComboBox<>(modes);
        modeSelector.addActionListener(e -> onModeChanged());
        headerRight.add(modeSelector);

        statusLabel = new JLabel("Estado: Conectando...");
        statusLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        headerRight.add(statusLabel);
        topHeader.add(headerRight, BorderLayout.EAST);
        add(topHeader, BorderLayout.NORTH);

        // Center Content: Left (Visual Ingestion & Inference), Right (Cluster Topology & Dialogue)
        JPanel mainPanel = new JPanel(new GridLayout(1, 2, 14, 14));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));

        // ==================== LEFT PANEL: INFERENCE & REAL PREVIEW ====================
        JPanel leftPanel = new JPanel(new BorderLayout(10, 10));

        // Top Toolbar: Sample selector & load custom file
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.setBorder(BorderFactory.createTitledBorder("Selección de Fotografía (Dataset Real)"));
        String[] samples = {"panda", "dog", "cat", "lion", "bear"};
        sampleSelector = new JComboBox<>(samples);
        sampleSelector.setPreferredSize(new Dimension(110, 28));
        sampleSelector.addActionListener(e -> loadCatalogSample());

        loadFileButton = new JButton("Cargar Fotografía Local...");
        loadFileButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        loadFileButton.addActionListener(e -> onChooseLocalFile());

        JLabel dbBadge = new JLabel("✓ Base de Datos Real (dataset/real/)");
        dbBadge.setFont(new Font("SansSerif", Font.BOLD, 11));
        dbBadge.setForeground(new Color(22, 163, 74));

        toolbar.add(new JLabel("Muestra:"));
        toolbar.add(sampleSelector);
        toolbar.add(loadFileButton);
        toolbar.add(Box.createHorizontalStrut(8));
        toolbar.add(dbBadge);
        leftPanel.add(toolbar, BorderLayout.NORTH);

        // Center: Full-Fidelity Photographic Preview (Zero Degradation)
        JPanel previewCard = new JPanel(new BorderLayout());
        previewCard.setBorder(BorderFactory.createTitledBorder("Vista Previa Fotográfica Original"));
        previewPanel = new ImagePreviewPanel();
        previewPanel.setBackground(new Color(15, 23, 42));
        previewCard.add(previewPanel, BorderLayout.CENTER);
        leftPanel.add(previewCard, BorderLayout.CENTER);

        // Bottom: Action Button & Results Card
        JPanel bottomCard = new JPanel();
        bottomCard.setLayout(new BoxLayout(bottomCard, BoxLayout.Y_AXIS));
        bottomCard.setBorder(BorderFactory.createTitledBorder("Resultado de la Inferencia"));

        identifyButton = new JButton("⚡ Identificar Fotografía en el Clúster");
        identifyButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        identifyButton.setBackground(new Color(37, 99, 235));
        identifyButton.setForeground(Color.BLACK);
        identifyButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        identifyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        identifyButton.addActionListener(e -> onIdentifyClicked());
        bottomCard.add(identifyButton);
        bottomCard.add(Box.createVerticalStrut(8));

        JPanel resultsGrid = new JPanel(new GridLayout(2, 2, 8, 4));
        resultsGrid.setOpaque(false);
        predictionLabel = new JLabel("Clase: -");
        predictionLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        predictionLabel.setForeground(new Color(37, 99, 235));

        confidenceLabel = new JLabel("Confianza: -");
        confidenceLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        confidenceLabel.setForeground(new Color(16, 185, 129));

        latencyLabel = new JLabel("Latencia: -");
        latencyLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

        handlerLabel = new JLabel("Manejador: -");
        handlerLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        handlerLabel.setForeground(new Color(100, 116, 139));

        resultsGrid.add(predictionLabel);
        resultsGrid.add(confidenceLabel);
        resultsGrid.add(latencyLabel);
        resultsGrid.add(handlerLabel);
        bottomCard.add(resultsGrid);

        leftPanel.add(bottomCard, BorderLayout.SOUTH);
        mainPanel.add(leftPanel);

        // ==================== RIGHT PANEL: CLUSTER NODES & CHAT ====================
        JPanel rightPanel = new JPanel(new BorderLayout(10, 10));

        // Active Cluster Workers Table (Auto-bound by system)
        JPanel workersCard = new JPanel(new BorderLayout(4, 4));
        workersCard.setBorder(BorderFactory.createTitledBorder("Topología de Cubos Activos (Vinculados por el Sistema)"));
        String[] colNames = {"ID de Cubo", "Lenguaje", "Hilos", "Tareas", "Estado"};
        workersTableModel = new DefaultTableModel(colNames, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable workersTable = new JTable(workersTableModel);
        workersTable.setRowHeight(22);
        JScrollPane scrollTable = new JScrollPane(workersTable);
        scrollTable.setPreferredSize(new Dimension(420, 150));
        workersCard.add(scrollTable, BorderLayout.CENTER);

        JPanel workersFooter = new JPanel(new BorderLayout());
        workersCountLabel = new JLabel("Cubos conectados: 0");
        workersCountLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        JButton refreshBtn = new JButton("Actualizar");
        refreshBtn.addActionListener(e -> requestWorkersUpdate());
        workersFooter.add(workersCountLabel, BorderLayout.WEST);
        workersFooter.add(refreshBtn, BorderLayout.EAST);
        workersCard.add(workersFooter, BorderLayout.SOUTH);
        rightPanel.add(workersCard, BorderLayout.NORTH);

        // Client Interactive Dialogue (`idClientChat 0812`)
        JPanel chatCard = new JPanel(new BorderLayout(6, 6));
        chatCard.setBorder(BorderFactory.createTitledBorder("Diálogo del Cliente y Registro de Red (idClientChat 0812)"));
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatArea.setBackground(new Color(248, 250, 252));
        JScrollPane scrollChat = new JScrollPane(chatArea);
        chatCard.add(scrollChat, BorderLayout.CENTER);

        JPanel chatInputRow = new JPanel(new BorderLayout(6, 0));
        JTextField chatInputField = new JTextField();
        JButton sendBtn = new JButton("Enviar");
        Runnable sendAction = () -> {
            String text = chatInputField.getText().trim();
            if (!text.isEmpty()) {
                appendChat("Yo: " + text);
                chatInputField.setText("");
                if ("status".equalsIgnoreCase(text)) {
                    requestWorkersUpdate();
                } else if ("infer".equalsIgnoreCase(text)) {
                    onIdentifyClicked();
                } else {
                    appendChat("Bot: Solicitud recibida [idClientChat 0812]. Concurrencia de cliente activa.");
                }
            }
        };
        sendBtn.addActionListener(e -> sendAction.run());
        chatInputField.addActionListener(e -> sendAction.run());
        chatInputRow.add(chatInputField, BorderLayout.CENTER);
        chatInputRow.add(sendBtn, BorderLayout.EAST);
        chatCard.add(chatInputRow, BorderLayout.SOUTH);

        rightPanel.add(chatCard, BorderLayout.CENTER);
        mainPanel.add(rightPanel);

        add(mainPanel, BorderLayout.CENTER);

        // Load initial real photographic sample
        loadCatalogSample();
        appendChat("[Sistema] Cliente iniciado. Conectando hilo independiente al orquestador.");
    }

    private void loadCatalogSample() {
        String selected = (String) sampleSelector.getSelectedItem();
        if (selected == null) selected = "panda";
        try {
            currentLoadedMatrix = ImageMatrix.createSample(selected);
            previewPanel.setImage(currentLoadedMatrix);
            appendChat("[Cliente] Muestra del dataset real cargada: " + selected.toUpperCase() + " (fotografia real original)");
        } catch (Exception ex) {
            appendChat("[Cliente] Error cargando muestra real: " + ex.getMessage());
        }
    }

    private void onChooseLocalFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Seleccionar Fotografía para Inferencia");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Imágenes (JPG, PNG, BMP)", "jpg", "jpeg", "png", "bmp"));
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            try {
                currentLoadedMatrix = ImageMatrix.fromFile(selected, "custom_photo");
                previewPanel.setImage(currentLoadedMatrix);
                appendChat("[Cliente] Fotografía personalizada cargada: " + selected.getName() + " (" + currentLoadedMatrix.getWidth() + "x" + currentLoadedMatrix.getHeight() + " px)");
            } catch (Exception ex) {
                appendChat("[Cliente] Error decodificando imagen: " + ex.getMessage());
            }
        }
    }

    private void onIdentifyClicked() {
        if (currentLoadedMatrix == null) return;
        appendChat("Yo: Identificar fotografía (" + currentLoadedMatrix.getLabel() + ")");
        identifyButton.setEnabled(false);

        new Thread(() -> {
            try {
                if (socket == null || socket.isClosed()) connectToServer();

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
                        String modeUsed = (parts.length > 2) ? parts[2] : "Modo";
                        String latMs = (parts.length > 3) ? parts[3] : "0.0";
                        String trace = (parts.length > 5) ? parts[5] : "Pipeline Clúster";

                        predictionLabel.setText("Clase: " + label.toUpperCase());
                        confidenceLabel.setText("Confianza: " + conf + "%");
                        latencyLabel.setText("Latencia: " + latMs + " ms  |  Modo: " + modeUsed);
                        handlerLabel.setText("Manejador: " + trace);

                        appendChat("Bot: Clasificación completada exitosamente");
                        appendChat("     Clase Predicha: " + label.toUpperCase());
                        appendChat("     Confianza: " + conf + "%");
                        appendChat("     Tiempo: " + latMs + " ms");
                        appendChat("     Trazabilidad: " + trace);
                    } else {
                        appendChat("Bot: Error en respuesta de inferencia del servidor.");
                    }
                    identifyButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendChat("Bot: Error de comunicación durante la inferencia (" + ex.getMessage() + ")");
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
                appendChat("[Cliente] Modo de ejecución cambiado a: " + finalMode);
            } catch (Exception ex) {
                appendChat("[Cliente] Aviso al cambiar modo: " + ex.getMessage());
            }
        }).start();
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                socket = new Socket(serverHost, serverPort);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Estado: Conectado a " + serverHost + ":" + serverPort);
                    statusLabel.setForeground(new Color(22, 163, 74));
                });
                requestWorkersUpdate();
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Estado: Sin conexión (" + e.getMessage() + ")");
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
            workersCountLabel.setText("Cubos conectados: 0");
            return;
        }
        String[] rows = payload.split(";");
        int count = 0;
        for (String r : rows) {
            String[] parts = r.split(",");
            if (parts.length >= 5) {
                workersTableModel.addRow(new Object[]{parts[0], parts[1], parts[2] + " hilos", parts[3], parts[4]});
                count++;
            }
        }
        workersCountLabel.setText("Cubos conectados: " + count);
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
     * Preview canvas rendering the full-fidelity real photograph without degradation or distortion.
     * Preserves aspect ratio, applies bicubic interpolation, and displays true RGB colors.
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
                g2.drawString("Cargando fotografía real...", panelW / 2 - 80, panelH / 2);
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
        String id = (args.length > 0) ? args[0] : "0812";
        String host = (args.length > 1) ? args[1] : "localhost";
        int port = (args.length > 2) ? Integer.parseInt(args[2]) : ProtocolConstants.DEFAULT_CLIENT_PORT;

        SwingUtilities.invokeLater(() -> {
            DesktopChatGui gui = new DesktopChatGui(id, host, port);
            gui.setVisible(true);
        });
    }
}
