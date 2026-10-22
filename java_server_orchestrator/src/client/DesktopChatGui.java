package client;

import core.ImageMatrix;
import protocol.ProtocolConstants;
import protocol.ProtocolMessage;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

/**
 * DesktopChatGui: Graphical User Interface for the Open AI Cubes Client.
 * Displays real-time cluster worker topology, execution mode selection,
 * image preview (synthetic and local photography), single inference with ranked similarities,
 * decoupled model training pipeline across 4 execution modes, and automated benchmarking.
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
    private JButton trainButton;
    private JButton runBenchmarkButton;
    private JSpinner benchmarkRunsSpinner;
    private JLabel benchmarkUnitsLabel;
    private JRadioButton processInferRadio;
    private JRadioButton processTrainRadio;
    private JRadioButton simpleOpRadio;
    private JRadioButton benchmarkOpRadio;
    private JLabel statusLabel;
    private JLabel dbStatusLabel;
    private ImagePreviewPanel previewPanel;
    private JPanel displayCard;
    private JPanel inputSelection;
    private JPanel trainingInfoPanel;
    private TitledBorder centralCardBorder;

    private ImageMatrix currentLoadedMatrix;

    public DesktopChatGui(String clientId, String serverHost, int serverPort) {
        super("Open AI Cubes - Centro de Control y Cliente");
        this.clientId = clientId;
        this.serverHost = serverHost;
        this.serverPort = serverPort;

        initComponents();
        connectToServer();
        startWorkerPollTimer();
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 780);
        setMinimumSize(new Dimension(1100, 720));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // Top Header
        JPanel topHeader = new JPanel(new BorderLayout());
        topHeader.setBackground(new Color(245, 247, 250));
        topHeader.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setOpaque(false);

        JLabel titleLabel = new JLabel("Modulo Frontend - Open AI Cubes");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        titleLabel.setForeground(new Color(33, 37, 41));

        JLabel validatedUserLabel = new JLabel("Usuario Validado  |  idClientChat: " + clientId);
        validatedUserLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        validatedUserLabel.setForeground(new Color(40, 167, 69));

        titleBox.add(titleLabel);
        titleBox.add(Box.createVerticalStrut(2));
        titleBox.add(validatedUserLabel);
        topHeader.add(titleBox, BorderLayout.WEST);

        statusLabel = new JLabel("Estado: Conectando...");
        statusLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        topHeader.add(statusLabel, BorderLayout.EAST);
        add(topHeader, BorderLayout.NORTH);

        // Main Center Split Panel (Left: Control & Workers, Right: Dialogue & Metrics)
        JPanel mainPanel = new JPanel(new GridLayout(1, 2, 12, 12));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(6, 14, 10, 14));

        // LEFT PANEL: Cluster Dashboard, Modes & Processing Controls
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));

        // 1. Workers Monitoring Card
        JPanel workersCard = new JPanel(new BorderLayout(4, 4));
        workersCard.setBorder(BorderFactory.createTitledBorder("Nodos Trabajadores Conectados (Clúster Dinámico)"));

        String[] columnNames = {"Nodo ID", "Lenguaje", "Hilos", "Tareas", "Estado"};
        workersTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        JTable workersTable = new JTable(workersTableModel);
        workersTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        workersTable.setRowHeight(20);
        JScrollPane workersScroll = new JScrollPane(workersTable);
        workersScroll.setPreferredSize(new Dimension(440, 90));
        workersCard.add(workersScroll, BorderLayout.CENTER);

        JPanel workersFooter = new JPanel(new BorderLayout());
        workersCountLabel = new JLabel("Nodos activos: 0");
        workersCountLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        JButton refreshWorkersBtn = new JButton("Actualizar Nodos");
        refreshWorkersBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        refreshWorkersBtn.addActionListener(e -> requestWorkersUpdate());
        workersFooter.add(workersCountLabel, BorderLayout.WEST);
        workersFooter.add(refreshWorkersBtn, BorderLayout.EAST);
        workersCard.add(workersFooter, BorderLayout.SOUTH);
        leftPanel.add(workersCard);
        leftPanel.add(Box.createVerticalStrut(6));

        // 1.5 Cubes Administration Card (Dynamic count and language management)
        JPanel cubesAdminCard = new JPanel();
        cubesAdminCard.setLayout(new BoxLayout(cubesAdminCard, BoxLayout.Y_AXIS));
        cubesAdminCard.setBorder(BorderFactory.createTitledBorder("Administración de Cubos (Cantidad y Lenguajes)"));

        JPanel cubesCountRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        cubesCountRow.add(new JLabel("Límite de cubos activos:"));
        String[] cubeOptions = {"0 (Todos los cubos)", "1 cubo", "2 cubos", "3 cubos", "4 cubos", "5 cubos"};
        JComboBox<String> maxCubesSelector = new JComboBox<>(cubeOptions);
        cubesCountRow.add(maxCubesSelector);
        cubesAdminCard.add(cubesCountRow);

        JPanel langsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JCheckBox cbJava = new JCheckBox("Java", true);
        JCheckBox cbPy = new JCheckBox("Python", true);
        JCheckBox cbCpp = new JCheckBox("C++", true);
        JCheckBox cbRust = new JCheckBox("Rust", true);
        JCheckBox cbGo = new JCheckBox("Go", true);
        langsRow.add(new JLabel("Lenguajes:"));
        langsRow.add(cbJava);
        langsRow.add(cbPy);
        langsRow.add(cbCpp);
        langsRow.add(cbRust);
        langsRow.add(cbGo);

        JButton applyConfigBtn = new JButton("Aplicar Configuración");
        applyConfigBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        applyConfigBtn.addActionListener(e -> {
            int selectedIdx = maxCubesSelector.getSelectedIndex();
            java.util.List<String> selectedLangs = new java.util.ArrayList<>();
            if (cbJava.isSelected()) selectedLangs.add("Java");
            if (cbPy.isSelected()) selectedLangs.add("Python");
            if (cbCpp.isSelected()) selectedLangs.add("C++");
            if (cbRust.isSelected()) selectedLangs.add("Rust");
            if (cbGo.isSelected()) selectedLangs.add("Go");
            sendCubesConfig(selectedIdx, String.join(",", selectedLangs));
        });
        langsRow.add(applyConfigBtn);
        cubesAdminCard.add(langsRow);

        leftPanel.add(cubesAdminCard);
        leftPanel.add(Box.createVerticalStrut(6));

        // 2. Process Selection & Execution Mode Card
        JPanel configCard = new JPanel();
        configCard.setLayout(new BoxLayout(configCard, BoxLayout.Y_AXIS));
        configCard.setBorder(BorderFactory.createTitledBorder("Proceso y Escenario de Ejecución"));

        JPanel processRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        processRow.add(new JLabel("Proceso:"));
        processInferRadio = new JRadioButton("Inferencia (Clasificación)", true);
        processTrainRadio = new JRadioButton("Entrenamiento (Aprender Pesos)", false);
        ButtonGroup processGroup = new ButtonGroup();
        processGroup.add(processInferRadio);
        processGroup.add(processTrainRadio);
        processRow.add(processInferRadio);
        processRow.add(processTrainRadio);
        configCard.add(processRow);

        processInferRadio.addActionListener(e -> toggleOperationMode());
        processTrainRadio.addActionListener(e -> toggleOperationMode());

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        String[] modes = {"PARALLEL", "SEQUENTIAL", "DISTRIBUTED", "PARALLEL_DISTRIBUTED"};
        modeSelector = new JComboBox<>(modes);
        modeSelector.setPreferredSize(new Dimension(280, 26));
        modeSelector.addActionListener(e -> updateExecutionMode((String) modeSelector.getSelectedItem()));
        modeRow.add(new JLabel("Escenario:"));
        modeRow.add(modeSelector);
        configCard.add(modeRow);

        leftPanel.add(configCard);
        leftPanel.add(Box.createVerticalStrut(6));

        // 3. Operation Scope Card
        JPanel opCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        opCard.setBorder(BorderFactory.createTitledBorder("Alcance de la Operación"));

        simpleOpRadio = new JRadioButton("Operación Simple (Unitario)", true);
        benchmarkOpRadio = new JRadioButton("Benchmark Completo (4 Escenarios con Calentamiento)");
        ButtonGroup opGroup = new ButtonGroup();
        opGroup.add(simpleOpRadio);
        opGroup.add(benchmarkOpRadio);
        opCard.add(simpleOpRadio);
        opCard.add(benchmarkOpRadio);

        simpleOpRadio.addActionListener(e -> toggleOperationMode());
        benchmarkOpRadio.addActionListener(e -> toggleOperationMode());

        leftPanel.add(opCard);
        leftPanel.add(Box.createVerticalStrut(6));

        // 4. Central Action & Content Card
        JPanel centralCard = new JPanel(new BorderLayout(8, 6));
        centralCardBorder = BorderFactory.createTitledBorder("Entrada de Imagen & Visualizador (Inferencia)");
        centralCard.setBorder(centralCardBorder);

        boolean hasRealDb = isRealDatasetPresent();

        // Subpanel A: Top bar (Catalog, Load File, DB Status)
        inputSelection = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        String[] samples = {"panda", "cat", "dog", "bear", "lion"};
        sampleSelector = new JComboBox<>(samples);
        sampleSelector.setPreferredSize(new Dimension(85, 26));
        sampleSelector.addActionListener(e -> {
            String selected = (String) sampleSelector.getSelectedItem();
            if (selected != null) {
                File dir = new File("dataset/real/test/" + selected.toLowerCase());
                if (!dir.exists()) dir = new File("../dataset/real/test/" + selected.toLowerCase());
                File[] photos = (dir.exists() && dir.isDirectory())
                        ? dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"))
                        : null;
                if (photos != null && photos.length > 0) {
                    Arrays.sort(photos, Comparator.comparing(File::getName));
                    try {
                        currentLoadedMatrix = ImageMatrix.fromFile(photos[0], selected);
                    } catch (Exception ex) {
                        currentLoadedMatrix = ImageMatrix.createSample(selected);
                    }
                } else {
                    currentLoadedMatrix = ImageMatrix.createSample(selected);
                }
                previewPanel.setImage(currentLoadedMatrix);
            }
        });

        loadFileButton = new JButton("Cargar Foto...");
        loadFileButton.setFont(new Font("SansSerif", Font.PLAIN, 11));
        loadFileButton.addActionListener(e -> onChooseLocalFile());
        if (!hasRealDb) {
            loadFileButton.setEnabled(false);
            loadFileButton.setToolTipText("Deshabilitado: Modo mock activo.");
        }

        dbStatusLabel = new JLabel(hasRealDb
                ? "BD: REAL (200 fotos en dataset/real/)"
                : "BD: MOCK (sintético - dataset real no encontrado)");
        dbStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        dbStatusLabel.setForeground(hasRealDb ? new Color(30, 130, 60) : new Color(180, 40, 40));

        inputSelection.add(new JLabel("Catálogo:"));
        inputSelection.add(sampleSelector);
        inputSelection.add(loadFileButton);
        inputSelection.add(Box.createHorizontalStrut(6));
        inputSelection.add(dbStatusLabel);
        centralCard.add(inputSelection, BorderLayout.NORTH);

        // Subpanel B: Center Body (Left: Preview / Training Info, Right: Actions & Benchmark)
        JPanel centerBody = new JPanel(new BorderLayout(10, 0));

        // Left Container: Visual Display (175x175 px)
        displayCard = new JPanel(new BorderLayout());
        displayCard.setPreferredSize(new Dimension(175, 175));
        previewPanel = new ImagePreviewPanel();
        previewPanel.setPreferredSize(new Dimension(175, 175));
        displayCard.add(previewPanel, BorderLayout.CENTER);

        trainingInfoPanel = new JPanel();
        trainingInfoPanel.setLayout(new BoxLayout(trainingInfoPanel, BoxLayout.Y_AXIS));
        trainingInfoPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        trainingInfoPanel.setBackground(new Color(248, 249, 250));
        JLabel trTitle = new JLabel("Pipeline de Entrenamiento");
        trTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        JLabel trSource = new JLabel(hasRealDb ? "• BD: REAL (200 fotos @ 720x720)" : "• BD: MOCK (sintético)");
        JLabel trArch = new JLabel("• 8 filtros 3x3, ReLU y pooling (1352 dims)");
        JLabel trWeights = new JLabel("• Pesos: dataset/weights.csv");
        trainingInfoPanel.add(trTitle);
        trainingInfoPanel.add(Box.createVerticalStrut(4));
        trainingInfoPanel.add(trSource);
        trainingInfoPanel.add(trArch);
        trainingInfoPanel.add(trWeights);
        trainingInfoPanel.setVisible(false);
        displayCard.add(trainingInfoPanel, BorderLayout.SOUTH);

        centerBody.add(displayCard, BorderLayout.WEST);

        // Right Container: Action Buttons and Benchmark Parameters
        JPanel actionsBox = new JPanel();
        actionsBox.setLayout(new BoxLayout(actionsBox, BoxLayout.Y_AXIS));
        actionsBox.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 6));

        // Action Buttons Row (Yo: Identificar / Yo: Entrenar Modelo)
        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        identifyButton = new JButton("Yo: Identificar");
        identifyButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        identifyButton.setBackground(new Color(0, 123, 255));
        identifyButton.setForeground(Color.BLACK);
        identifyButton.setPreferredSize(new Dimension(145, 32));
        identifyButton.addActionListener(e -> onIdentifyClicked());

        trainButton = new JButton("Yo: Entrenar Modelo");
        trainButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        trainButton.setBackground(new Color(40, 167, 69));
        trainButton.setForeground(Color.BLACK);
        trainButton.setPreferredSize(new Dimension(145, 32));
        trainButton.setVisible(false);
        trainButton.addActionListener(e -> onTrainClicked());

        actionRow.add(identifyButton);
        actionRow.add(trainButton);
        actionsBox.add(actionRow);
        actionsBox.add(Box.createVerticalStrut(8));

        // Benchmark Controls Row
        JPanel benchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        benchmarkUnitsLabel = new JLabel("Muestras:");
        benchmarkUnitsLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        benchRow.add(benchmarkUnitsLabel);
        benchmarkRunsSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 200, 5));
        benchmarkRunsSpinner.setPreferredSize(new Dimension(50, 26));
        benchRow.add(benchmarkRunsSpinner);
        runBenchmarkButton = new JButton("Ejecutar Benchmark");
        runBenchmarkButton.setFont(new Font("SansSerif", Font.BOLD, 11));
        runBenchmarkButton.setEnabled(false);
        runBenchmarkButton.addActionListener(e -> onRunBenchmarkClicked());
        benchRow.add(runBenchmarkButton);
        actionsBox.add(benchRow);
        actionsBox.add(Box.createVerticalStrut(8));

        // Operational Status Tag
        JLabel processNote = new JLabel("Procesamiento convolucional 100% real.");
        processNote.setFont(new Font("SansSerif", Font.ITALIC, 11));
        processNote.setForeground(Color.GRAY);
        actionsBox.add(processNote);

        centerBody.add(actionsBox, BorderLayout.CENTER);
        centralCard.add(centerBody, BorderLayout.CENTER);

        leftPanel.add(centralCard);
        mainPanel.add(leftPanel);

        // RIGHT PANEL: Chat Dialogue (Rubric Format)
        JPanel rightPanel = new JPanel(new BorderLayout(6, 6));
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        chatArea.setMargin(new Insets(10, 12, 10, 12));
        chatArea.setBackground(Color.WHITE);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Título de diálogo - Cliente"));
        rightPanel.add(scrollPane, BorderLayout.CENTER);

        JButton clearChatBtn = new JButton("Limpiar Diálogo");
        clearChatBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        clearChatBtn.addActionListener(e -> chatArea.setText(""));
        rightPanel.add(clearChatBtn, BorderLayout.SOUTH);

        mainPanel.add(rightPanel);
        add(mainPanel, BorderLayout.CENTER);

        // Initial preview: Load authentic high-resolution photograph if available
        File initDir = new File("dataset/real/test/panda");
        if (!initDir.exists()) initDir = new File("../dataset/real/test/panda");
        File[] initPhotos = (initDir.exists() && initDir.isDirectory())
                ? initDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"))
                : null;
        if (initPhotos != null && initPhotos.length > 0) {
            Arrays.sort(initPhotos, Comparator.comparing(File::getName));
            try {
                currentLoadedMatrix = ImageMatrix.fromFile(initPhotos[0], "panda");
            } catch (Exception ignored) {
                currentLoadedMatrix = ImageMatrix.createSample("panda");
            }
        } else {
            currentLoadedMatrix = ImageMatrix.createSample("panda");
        }
        previewPanel.setImage(currentLoadedMatrix);

        if (hasRealDb) {
            appendChat("[Sistema] Base de datos detectada: REAL (200 fotografías en dataset/real/). Modo real activo.");
        } else {
            appendChat("[Sistema] Base de datos real no encontrada. Modo activo: MOCK (sintético). El procesamiento interno convolucional es 100% real.");
        }
    }

    public static boolean isRealDatasetPresent() {
        File dir = new File("dataset/real/train");
        if (!dir.exists()) dir = new File("../dataset/real/train");
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            return files != null && files.length > 0;
        }
        return false;
    }

    private void toggleOperationMode() {
        boolean isInfer = processInferRadio.isSelected();
        boolean isSimple = simpleOpRadio.isSelected();

        identifyButton.setVisible(isInfer);
        identifyButton.setEnabled(isInfer && isSimple);

        trainButton.setVisible(!isInfer);
        trainButton.setEnabled(!isInfer && isSimple);

        sampleSelector.setEnabled(isInfer);
        loadFileButton.setEnabled(isInfer && isRealDatasetPresent());

        inputSelection.setVisible(true);
        previewPanel.setVisible(isInfer);
        trainingInfoPanel.setVisible(!isInfer);

        runBenchmarkButton.setEnabled(!isSimple);
        benchmarkRunsSpinner.setEnabled(!isSimple);

        if (isInfer) {
            centralCardBorder.setTitle("Entrada de Imagen & Visualizador (Inferencia)");
            benchmarkUnitsLabel.setText("Muestras:");
            if ((Integer) benchmarkRunsSpinner.getValue() < 10) {
                benchmarkRunsSpinner.setValue(30);
            }
        } else {
            centralCardBorder.setTitle("Pipeline de Entrenamiento & Pesos (Entrenamiento)");
            benchmarkUnitsLabel.setText("Épocas:");
            if ((Integer) benchmarkRunsSpinner.getValue() > 10) {
                benchmarkRunsSpinner.setValue(3);
            }
        }

        revalidate();
        repaint();
    }

    private void connectToServer() {
        new Thread(() -> {
            int maxAttempts = 6;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    socket = new Socket(serverHost, serverPort);
                    reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Estado: Conectado a " + serverHost + ":" + serverPort));
                    requestWorkersUpdate();
                    return;
                } catch (IOException e) {
                    final int currentAttempt = attempt;
                    if (attempt < maxAttempts) {
                        SwingUtilities.invokeLater(() -> statusLabel.setText("Estado: Conectando... (intento " + currentAttempt + "/" + maxAttempts + ")"));
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {}
                    } else {
                        SwingUtilities.invokeLater(() -> statusLabel.setText("Estado: Error de conexión (" + e.getMessage() + ")"));
                    }
                }
            }
        }, "ClientNetThread").start();
    }

    private void startWorkerPollTimer() {
        Timer timer = new Timer(2500, e -> {
            if (socket != null && !socket.isClosed()) {
                requestWorkersUpdate();
            }
        });
        timer.start();
    }

    private void requestWorkersUpdate() {
        if (writer == null) return;
        new Thread(() -> {
            try {
                synchronized (writer) {
                    writer.write(ProtocolConstants.CMD_GET_WORKERS + "|" + clientId + "|LIST\n");
                    writer.flush();
                    String line = reader.readLine();
                    ProtocolMessage msg = ProtocolMessage.parse(line);
                    if (msg != null && ProtocolConstants.CMD_WORKERS_RESP.equals(msg.getAction())) {
                        updateWorkersTable(msg.getPayload());
                    }
                }
            } catch (IOException ignored) {}
        }, "WorkersPollThread").start();
    }

    private void updateWorkersTable(String payload) {
        SwingUtilities.invokeLater(() -> {
            workersTableModel.setRowCount(0);
            if (payload == null || payload.trim().isEmpty()) {
                workersCountLabel.setText("Nodos: 0 activos");
                return;
            }
            String[] workerEntries = payload.split(";");
            int activeCount = 0;
            for (String entry : workerEntries) {
                String[] fields = entry.split(",");
                if (fields.length >= 4) {
                    String status = (fields.length >= 5) ? fields[4] : "ACTIVO";
                    if ("ACTIVO".equalsIgnoreCase(status)) activeCount++;
                    workersTableModel.addRow(new Object[]{fields[0], fields[1], fields[2], fields[3], status});
                }
            }
            workersCountLabel.setText("Nodos: " + activeCount + " activos (" + workerEntries.length + " conectados)");
        });
    }

    private void sendCubesConfig(int maxCubes, String languagesCsv) {
        if (writer == null) return;
        new Thread(() -> {
            try {
                synchronized (writer) {
                    writer.write("SET_CUBES_CONFIG|" + clientId + "|" + maxCubes + "|" + languagesCsv + "\n");
                    writer.flush();
                    String line = reader.readLine();
                    ProtocolMessage msg = ProtocolMessage.parse(line);
                    if (msg != null) {
                        appendChat("Sistema: configuración de cubos aplicada (" + msg.getPayload() + ")");
                    }
                }
                requestWorkersUpdate();
            } catch (IOException ex) {
                appendChat("Error: no se pudo actualizar configuración (" + ex.getMessage() + ")");
            }
        }, "CubesConfigThread").start();
    }

    private void updateExecutionMode(String mode) {
        if (writer == null) return;
        new Thread(() -> {
            try {
                synchronized (writer) {
                    writer.write("SET_MODE|" + clientId + "|" + mode + "\n");
                    writer.flush();
                    reader.readLine();
                }
            } catch (IOException ignored) {}
        }).start();
    }

    private void onChooseLocalFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Seleccionar Fotografía de Entrada");
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos de Imagen (*.png, *.jpg, *.jpeg, *.bmp)", "png", "jpg", "jpeg", "bmp"));
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            try {
                currentLoadedMatrix = ImageMatrix.fromFile(selectedFile, selectedFile.getName());
                previewPanel.setImage(currentLoadedMatrix);
                appendChat("[Cliente] Fotografía cargada: " + selectedFile.getName() + " (" + currentLoadedMatrix.getWidth() + "x" + currentLoadedMatrix.getHeight() + " px, " + currentLoadedMatrix.getTotalPixels() + " puntos)");
            } catch (Exception ex) {
                appendChat("[Cliente] Error al decodificar la imagen: " + ex.getMessage());
            }
        }
    }

    private void onIdentifyClicked() {
        if (currentLoadedMatrix == null) return;
        appendChat("Yo: Identificar");

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
                        String conf = (parts.length > 1) ? parts[1] : "";
                        String mode = (parts.length > 2) ? parts[2] : "";
                        String ms = (parts.length > 3) ? parts[3] : "";
                        String similarities = (parts.length > 4) ? parts[4] : "";
                        String handler = (parts.length > 5) ? parts[5] : "";

                        appendChat("Bot: " + label + " (" + conf + "% en " + ms + " ms [" + mode + "])");
                        if (!similarities.isEmpty()) {
                            appendChat("     Ranking de Semejanzas: " + similarities);
                        }
                        if (!handler.isEmpty()) {
                            appendChat("     Nodo(s): " + handler);
                        }
                    } else {
                        appendChat("Bot: error en respuesta del servidor");
                    }
                    identifyButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendChat("Bot: error de comunicación (" + ex.getMessage() + ")");
                    identifyButton.setEnabled(true);
                });
            }
        }, "IdentifyWorkerThread").start();
    }

    private void onTrainClicked() {
        String mode = (String) modeSelector.getSelectedItem();
        appendChat("Yo: Entrenar Modelo [" + mode + "]");
        trainButton.setEnabled(false);

        new Thread(() -> {
            try {
                if (socket == null || socket.isClosed()) connectToServer();

                ProtocolMessage req = new ProtocolMessage(ProtocolConstants.CMD_TRAIN_REQ, clientId, mode);
                synchronized (writer) {
                    writer.write(req.toWireString());
                    writer.flush();
                }

                String respLine = reader.readLine();
                ProtocolMessage resp = ProtocolMessage.parse(respLine);

                SwingUtilities.invokeLater(() -> {
                    if (resp != null && ProtocolConstants.CMD_TRAIN_RESP.equals(resp.getAction())) {
                        // Format: <samples>|<mode>|<duration_ms>|<throughput>|<weights_file>|<participating_nodes>
                        String[] parts = resp.getPayload().split("\\|");
                        String samples = parts[0];
                        String modeUsed = (parts.length > 1) ? parts[1] : mode;
                        String ms = (parts.length > 2) ? parts[2] : "";
                        String throughput = (parts.length > 3) ? parts[3] : "";
                        String weightsFile = (parts.length > 4) ? parts[4] : "";
                        String nodes = (parts.length > 5) ? parts[5] : "";

                        appendChat("Bot: Entrenamiento completado exitosamente");
                        appendChat("     Muestras procesadas: " + samples + " imágenes (5 clases)");
                        appendChat("     Escenario: " + modeUsed);
                        appendChat("     Tiempo: " + ms + " ms (" + throughput + " img/s)");
                        appendChat("     Pesos guardados en: " + weightsFile);
                        if (!nodes.isEmpty()) {
                            appendChat("     Nodos participantes: " + nodes);
                        }
                    } else {
                        appendChat("Bot: error en respuesta de entrenamiento del servidor");
                    }
                    trainButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendChat("Bot: error de comunicación durante entrenamiento (" + ex.getMessage() + ")");
                    trainButton.setEnabled(true);
                });
            }
        }, "TrainWorkerThread").start();
    }

    private void onRunBenchmarkClicked() {
        boolean isInfer = processInferRadio.isSelected();
        int runs = (Integer) benchmarkRunsSpinner.getValue();
        String processName = isInfer ? "INFERENCIA" : "ENTRENAMIENTO";
        appendChat("\n>>> INICIANDO BENCHMARK DE " + processName + " EN EL SERVIDOR (" + runs + " iteraciones por modo)...");
        runBenchmarkButton.setEnabled(false);

        new Thread(() -> {
            try {
                if (socket == null || socket.isClosed()) connectToServer();

                String action = isInfer ? ProtocolConstants.CMD_RUN_BENCHMARK : ProtocolConstants.CMD_BENCHMARK_TRAIN;
                ProtocolMessage req = new ProtocolMessage(action, clientId, String.valueOf(runs));
                synchronized (writer) {
                    writer.write(req.toWireString());
                    writer.flush();
                }

                String respLine = reader.readLine();
                ProtocolMessage resp = ProtocolMessage.parse(respLine);

                SwingUtilities.invokeLater(() -> {
                    if (resp != null && (ProtocolConstants.CMD_BENCHMARK_RESP.equals(resp.getAction())
                            || ProtocolConstants.CMD_BENCHMARK_TRAIN_RESP.equals(resp.getAction()))) {
                        String formattedTable = resp.getPayload().replace("§", "\n");
                        appendChat("\n" + formattedTable);
                    } else {
                        appendChat("Bot: error ejecutando el benchmark en el servidor");
                    }
                    runBenchmarkButton.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendChat("Bot: error durante el benchmark (" + ex.getMessage() + ")");
                    runBenchmarkButton.setEnabled(true);
                });
            }
        }, "BenchmarkThread").start();
    }

    private void appendChat(String message) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        chatArea.append("[" + timeStamp + "] " + message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    /**
     * Preview canvas rendering full-fidelity image without distortion.
     * Preserves aspect ratio, applies bilinear anti-aliasing, and displays true RGB colors.
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
            g2.setColor(new Color(235, 238, 242));
            g2.fillRect(0, 0, panelW, panelH);

            if (matrix == null) {
                g2.setColor(Color.DARK_GRAY);
                g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
                g2.drawString("Sin Imagen", panelW / 2 - 32, panelH / 2);
                return;
            }

            BufferedImage img = (matrix.getSourceImage() != null)
                    ? matrix.getSourceImage()
                    : matrix.toBufferedImage();

            if (img == null) return;

            int imgW = img.getWidth();
            int imgH = img.getHeight();
            int pad = 4;
            int availW = Math.max(1, panelW - 2 * pad);
            int availH = Math.max(1, panelH - 2 * pad);

            double scale = Math.min((double) availW / imgW, (double) availH / imgH);
            int drawW = Math.max(1, (int) Math.round(imgW * scale));
            int drawH = Math.max(1, (int) Math.round(imgH * scale));
            int drawX = (panelW - drawW) / 2;
            int drawY = (panelH - drawH) / 2;

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.drawImage(img, drawX, drawY, drawW, drawH, null);
            g2.setColor(new Color(170, 175, 185));
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
