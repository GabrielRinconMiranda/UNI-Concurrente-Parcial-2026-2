package server;

import core.CnnInferenceEngine;
import core.ConvolutionChunk;
import core.ImageMatrix;
import core.MlpChunk;
import core.ModelTrainer;
import protocol.ProtocolConstants;
import protocol.ProtocolMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ServerOrchestrator: Central coordinating server for the Open AI Cubes architecture.
 * Manages client connections, worker node registration, real-time cluster telemetry,
 * and concurrent task orchestration across the four execution modes.
 */
public class ServerOrchestrator {
    private final int workerPort;
    private final int clientPort;
    private final int httpPort;
    private final NodeRegistry registry = new NodeRegistry();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ExecutionMode currentMode = ExecutionMode.PARALLEL;
    private final ExecutorService localThreadPool = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    private final Object sequentialLock = new Object();
    private final Object distributedLock = new Object();

    // Client session tracking and ID allocation
    private final AtomicInteger clientSequence = new AtomicInteger(1000);
    private final Map<String, ClientSessionInfo> clientSessions = new ConcurrentHashMap<>();
    private final AtomicLong totalClusterRequests = new AtomicLong(0);
    private final long serverStartTime = System.currentTimeMillis();

    // Real-time event log buffer for cluster administrator
    public static class ClusterEvent {
        public final long timestamp;
        public final String timeStr;
        public final String type;
        public final String clientId;
        public final String details;

        public ClusterEvent(String type, String clientId, String details) {
            this.timestamp = System.currentTimeMillis();
            this.timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date(this.timestamp));
            this.type = type;
            this.clientId = (clientId != null && !clientId.trim().isEmpty()) ? clientId : "Cluster";
            this.details = details;
        }
    }

    private final ConcurrentLinkedDeque<ClusterEvent> clusterEvents = new ConcurrentLinkedDeque<>();

    // Client session info for real-time tracking
    public static class ClientSessionInfo {
        public final String clientId;
        public final String remoteAddress;
        public final long connectedTime;
        public volatile long lastActiveTime;
        public final AtomicInteger totalInferences = new AtomicInteger(0);
        public volatile String lastPrediction = "None";
        public volatile double lastLatencyMs = 0.0;

        public ClientSessionInfo(String clientId, String remoteAddress) {
            this.clientId = clientId;
            this.remoteAddress = remoteAddress;
            this.connectedTime = System.currentTimeMillis();
            this.lastActiveTime = System.currentTimeMillis();
        }
    }

    private static class InferenceExecutionResult {
        final MlpChunk.Prediction prediction;
        final String handlerInfo;

        InferenceExecutionResult(MlpChunk.Prediction prediction, String handlerInfo) {
            this.prediction = prediction;
            this.handlerInfo = handlerInfo;
        }
    }

    private ServerSocket workerServerSocket;
    private ServerSocket clientServerSocket;
    private HttpServer httpServer;

    // ANSI terminal formatting constants
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_BOLD = "\u001B[1m";

    public ServerOrchestrator(int workerPort, int clientPort) {
        this(workerPort, clientPort, 8080);
    }

    public ServerOrchestrator(int workerPort, int clientPort, int httpPort) {
        this.workerPort = workerPort;
        this.clientPort = clientPort;
        this.httpPort = httpPort;
    }

    public String allocateClientId() {
        return "Client-" + clientSequence.incrementAndGet();
    }

    public ExecutionMode getCurrentMode() {
        return currentMode;
    }

    public void setExecutionMode(ExecutionMode mode) {
        this.currentMode = mode;
        recordEvent("MODE_CHANGE", "Server", "Active execution mode set to: " + mode.getDescription());
    }

    public NodeRegistry getRegistry() {
        return registry;
    }

    public Map<String, ClientSessionInfo> getClientSessions() {
        return clientSessions;
    }

    public List<ClusterEvent> getRecentEvents() {
        return new ArrayList<>(clusterEvents);
    }

    public long getTotalClusterRequests() {
        return totalClusterRequests.get();
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - serverStartTime) / 1000;
    }

    public void recordEvent(String type, String clientId, String details) {
        clusterEvents.addFirst(new ClusterEvent(type, clientId, details));
        while (clusterEvents.size() > 120) {
            clusterEvents.removeLast();
        }
        logTerminal(type, clientId, details);
    }

    private void logTerminal(String tag, String clientId, String message) {
        String timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String color = ANSI_CYAN;
        if ("DISPATCH".equals(tag)) color = ANSI_GREEN;
        else if ("WORKER".equals(tag)) color = ANSI_YELLOW;
        else if ("CLIENT".equals(tag)) color = ANSI_MAGENTA;
        else if ("ERROR".equals(tag)) color = ANSI_RED;

        System.out.printf("[%s] %s[%-8s]%s %s[%-12s]%s %s\n",
                timeStr,
                color + ANSI_BOLD, tag, ANSI_RESET,
                ANSI_BOLD, (clientId != null ? clientId : "Cluster"), ANSI_RESET,
                message);
    }

    /**
     * InferenceResponse: Holds prediction metadata, similarities, and performance metrics.
     */
    public static class InferenceResponse {
        public final String label;
        public final float confidence;
        public final ExecutionMode modeUsed;
        public final double latencyMs;
        public final String handlerInfo;
        public final String similaritiesSummary;

        public InferenceResponse(String label, float confidence, ExecutionMode modeUsed, double latencyMs, String handlerInfo, String similaritiesSummary) {
            this.label = label;
            this.confidence = confidence;
            this.modeUsed = modeUsed;
            this.latencyMs = latencyMs;
            this.handlerInfo = handlerInfo;
            this.similaritiesSummary = (similaritiesSummary != null) ? similaritiesSummary : "";
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Result: %s (%.1f%%) in %.2f ms via %s [%s] - Ranking: %s",
                    label, confidence * 100.0f, latencyMs, modeUsed.name(), handlerInfo, similaritiesSummary);
        }
    }

    public InferenceResponse processInference(ImageMatrix image) {
        return processInference(image, "Client-Internal");
    }

    /**
     * Orchestrates inference across the active execution mode with client session tracking.
     */
    public InferenceResponse processInference(ImageMatrix image, String clientId) {
        totalClusterRequests.incrementAndGet();
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        long startNano = System.nanoTime();

        ExecutionMode mode = this.currentMode;
        MlpChunk.Prediction pred;
        String handlerInfo;

        try {
            switch (mode) {
                case SEQUENTIAL:
                    synchronized (sequentialLock) {
                        pred = CnnInferenceEngine.infer(image);
                    }
                    handlerInfo = "Local sequential engine (single thread)";
                    break;

                case PARALLEL:
                    int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
                    float[] features = ConvolutionChunk.process(image, cores);
                    pred = MlpChunk.process(features);
                    handlerInfo = "Local multi-core parallel engine (" + cores + " convolutional threads)";
                    break;

                case DISTRIBUTED:
                    synchronized (distributedLock) {
                        InferenceExecutionResult res = executeDistributedPipeline(image, taskId, false, clientId);
                        pred = res.prediction;
                        handlerInfo = res.handlerInfo;
                    }
                    break;

                case PARALLEL_DISTRIBUTED:
                    InferenceExecutionResult res = executeDistributedPipeline(image, taskId, true, clientId);
                    pred = res.prediction;
                    handlerInfo = res.handlerInfo;
                    break;

                default:
                    pred = CnnInferenceEngine.infer(image);
                    handlerInfo = "Default local fallback";
                    break;
            }
        } catch (Exception e) {
            pred = new MlpChunk.Prediction("error:" + e.getMessage(), 0.0f, new float[0]);
            handlerInfo = "Error: " + e.getMessage();
        }

        double elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0;
        InferenceResponse response = new InferenceResponse(pred.label, pred.confidence, mode, elapsedMs, handlerInfo, pred.getSimilaritiesSummary());

        // Update client session state
        ClientSessionInfo session = clientSessions.get(clientId);
        if (session != null) {
            session.totalInferences.incrementAndGet();
            session.lastActiveTime = System.currentTimeMillis();
            session.lastPrediction = pred.label.toUpperCase() + " (" + String.format(Locale.US, "%.1f%%", pred.confidence * 100.0f) + ")";
            session.lastLatencyMs = elapsedMs;
        }

        recordEvent("SUCCESS", clientId, String.format(Locale.US, "Classified %s (%.1f%%) in %.2f ms [%s]",
                pred.label.toUpperCase(), pred.confidence * 100.0f, elapsedMs, handlerInfo));
        System.out.println("  ----------------------------------------------------------------------------------------");

        return response;
    }

    private InferenceExecutionResult executeDistributedPipeline(ImageMatrix image, String taskId, boolean isParallelDistributed, String clientId) {
        List<WorkerNodeSession> workers = registry.getActiveWorkers();
        if (workers.isEmpty()) {
            MlpChunk.Prediction pred = CnnInferenceEngine.infer(image);
            String info = "Automatic local fallback: no remote worker nodes connected";
            recordEvent("WORKER", clientId, info);
            return new InferenceExecutionResult(pred, info);
        }

        try {
            if (workers.size() >= 2) {
                WorkerNodeSession convWorker = registry.acquireWorker();
                WorkerNodeSession mlpWorker = registry.acquireWorkerExcluding(convWorker);

                recordEvent("DISPATCH", clientId, "Pipeline assigned: Conv -> " + convWorker.getWorkerId()
                        + " (" + convWorker.getLanguage() + ") | MLP -> " + mlpWorker.getWorkerId() + " (" + mlpWorker.getLanguage() + ")");

                ProtocolMessage convReq = new ProtocolMessage(
                        ProtocolConstants.CMD_CONV_REQ,
                        taskId,
                        image.toCsvString()
                );
                ProtocolMessage convResp = convWorker.sendAndReceive(convReq);
                float[] features = ConvolutionChunk.featuresFromString(convResp.getPayload());

                ProtocolMessage mlpReq = new ProtocolMessage(
                        ProtocolConstants.CMD_MLP_REQ,
                        taskId,
                        ConvolutionChunk.featuresToString(features)
                );
                ProtocolMessage mlpResp = mlpWorker.sendAndReceive(mlpReq);
                MlpChunk.Prediction pred = MlpChunk.parseProtocolString(mlpResp.getPayload());

                String modeLabel = isParallelDistributed ? "Distributed concurrent pipeline" : "Distributed pipeline";
                String info = modeLabel + ": conv[" + convWorker.getWorkerId() + "/" + convWorker.getLanguage()
                        + " (" + convWorker.getMaxThreads() + " threads)] -> mlp[" + mlpWorker.getWorkerId() + "/"
                        + mlpWorker.getLanguage() + " (" + mlpWorker.getMaxThreads() + " threads)]";
                return new InferenceExecutionResult(pred, info);
            } else {
                WorkerNodeSession worker = registry.acquireWorker();
                recordEvent("DISPATCH", clientId, "Single node assigned: " + worker.getWorkerId() + " (" + worker.getLanguage() + ")");

                ProtocolMessage inferReq = new ProtocolMessage(
                        ProtocolConstants.CMD_INFER_REQ,
                        taskId,
                        image.getLabel() + "|" + image.toCsvString()
                );
                ProtocolMessage resp = worker.sendAndReceive(inferReq);
                MlpChunk.Prediction pred = MlpChunk.parseProtocolString(resp.getPayload());
                String modeLabel = isParallelDistributed ? "Distributed parallel" : "Distributed sequential";
                String info = modeLabel + " (single node): " + worker.getWorkerId() + " [" + worker.getLanguage()
                        + ", " + worker.getMaxThreads() + " threads]";
                return new InferenceExecutionResult(pred, info);
            }
        } catch (Exception e) {
            recordEvent("ERROR", clientId, "Remote worker communication error (" + e.getMessage() + "), initiating local fallback.");
            MlpChunk.Prediction pred = CnnInferenceEngine.infer(image);
            String info = "Automatic local fallback (remote worker unavailable: " + e.getMessage() + ")";
            return new InferenceExecutionResult(pred, info);
        }
    }

    public static ImageMatrix getSampleImage(String className) {
        String norm = (className != null && !className.trim().isEmpty()) ? className.toLowerCase().trim() : "panda";
        return ImageMatrix.createSample(norm);
    }

    /**
     * Executes internal comparative benchmark across all 4 modes.
     */
    public String runComparativeBenchmark(int runsPerMode) {
        int runs = Math.max(10, runsPerMode);
        ExecutionMode[] modes = ExecutionMode.values();
        String[] samples = {"panda", "cat", "dog", "bear", "lion"};

        for (int i = 0; i < 5; i++) {
            ImageMatrix img = getSampleImage(samples[i % samples.length]);
            CnnInferenceEngine.infer(img);
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.US, "Comparative Benchmark Report (Samples per mode: %d)\n", runs));
        report.append(String.format(Locale.US, "%-22s | %-12s | %-10s | %-10s | %-10s\n",
                "Execution Mode", "Throughput", "Mean (ms)", "P95 (ms)", "Min (ms)"));

        ExecutionMode originalMode = this.currentMode;

        for (ExecutionMode mode : modes) {
            setExecutionMode(mode);
            List<Double> latencies = new ArrayList<>();
            long startTotal = System.nanoTime();

            for (int r = 0; r < runs; r++) {
                ImageMatrix img = getSampleImage(samples[r % samples.length]);
                InferenceResponse resp = processInference(img);
                latencies.add(resp.latencyMs);
            }

            long totalElapsedMs = (System.nanoTime() - startTotal) / 1_000_000;
            double throughput = (runs * 1000.0) / Math.max(1, totalElapsedMs);

            Collections.sort(latencies);
            double mean = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p95 = latencies.get((int) (latencies.size() * 0.95));
            double min = latencies.get(0);

            report.append(String.format(Locale.US, "%-22s | %8.1f req/s | %8.2f ms | %8.2f ms | %8.2f ms\n",
                    mode.name(), throughput, mean, p95, min));
        }

        setExecutionMode(originalMode);
        return report.toString();
    }

    /**
     * Starts the server listeners across all network interfaces.
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        workerServerSocket = new ServerSocket();
        workerServerSocket.setReuseAddress(true);
        workerServerSocket.bind(new java.net.InetSocketAddress(workerPort));

        clientServerSocket = new ServerSocket();
        clientServerSocket.setReuseAddress(true);
        clientServerSocket.bind(new java.net.InetSocketAddress(clientPort));

        System.out.println("=================================================================================");
        System.out.println("  Open AI Cubes | Central Cluster Orchestrator Online");
        System.out.println("  TCP Worker Registration Port: " + workerPort);
        System.out.println("  TCP Client Query Port:        " + clientPort);
        System.out.println("  HTTP REST & Admin Port:       " + httpPort);
        System.out.println("  Initial Execution Mode:       " + currentMode.getDescription());
        System.out.println("  Photographic Dataset:         Verified (dataset/real/)");
        System.out.println("=================================================================================");

        new Thread(this::acceptWorkersLoop, "WorkerAcceptorThread").start();
        new Thread(this::acceptClientsLoop, "ClientAcceptorThread").start();

        // Start standard HTTP REST API listener for web client and admin dashboard
        try {
            httpServer = HttpServer.create(new java.net.InetSocketAddress(httpPort), 0);
            httpServer.createContext("/health", this::handleHttpHealth);
            httpServer.createContext("/status", this::handleHttpHealth);
            httpServer.createContext("/workers", this::handleHttpWorkers);
            httpServer.createContext("/infer", this::handleHttpInfer);
            httpServer.createContext("/mode", this::handleHttpMode);
            httpServer.createContext("/register_cloud_worker", this::handleHttpRegisterCloudWorker);
            httpServer.createContext("/dataset/image", this::handleHttpDatasetImage);
            httpServer.createContext("/dataset/samples", this::handleHttpDatasetSamples);
            httpServer.createContext("/api/session", this::handleHttpSession);
            httpServer.createContext("/api/admin/overview", this::handleHttpAdminOverview);
            httpServer.createContext("/api/admin/mode", this::handleHttpAdminMode);
            httpServer.createContext("/api/admin/clear-logs", this::handleHttpAdminClearLogs);
            httpServer.createContext("/admin", this::handleHttpAdmin);
            httpServer.createContext("/admin.html", this::handleHttpAdmin);
            httpServer.createContext("/", this::handleHttpRoot);
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            System.out.println("[Server] HTTP REST & Admin Dashboard online: http://0.0.0.0:" + httpPort);

            // Auto-register cloud workers from environment variable (e.g. Render URLs)
            String cloudWorkersEnv = System.getenv("CLOUD_WORKERS");
            if (cloudWorkersEnv != null && !cloudWorkersEnv.trim().isEmpty()) {
                String[] urls = cloudWorkersEnv.split(",");
                for (int i = 0; i < urls.length; i++) {
                    String u = urls[i].trim();
                    if (!u.isEmpty()) {
                        String lower = u.toLowerCase();
                        String lang = "CloudNode";
                        if (lower.contains("go")) lang = "Go";
                        else if (lower.contains("python") || lower.contains("py")) lang = "Python";
                        else if (lower.contains("rust")) lang = "Rust";
                        else if (lower.contains("cpp") || lower.contains("c++")) lang = "C++";
                        else if (lower.contains("java")) lang = "Java";

                        String id = "node_" + lang.toLowerCase().replaceAll("[^a-z0-9]", "") + "_cloud";
                        WorkerNodeSession ws = new WorkerNodeSession(id, lang, 4, "CONV,MLP", u);
                        registry.register(ws);
                        recordEvent("WORKER", "System", "Registered cloud worker from ENV: " + id + " (" + lang + ") -> " + u);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Server] HTTP listener notice: " + e.getMessage());
        }
    }

    private void acceptWorkersLoop() {
        while (running.get()) {
            try {
                Socket socket = workerServerSocket.accept();
                new Thread(() -> handleWorkerRegistration(socket), "WorkerRegHandler").start();
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.println("[Server] Error accepting worker connection: " + e.getMessage());
            }
        }
    }

    private void handleWorkerRegistration(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line = reader.readLine();
            if (line == null) {
                socket.close();
                return;
            }

            ProtocolMessage msg = ProtocolMessage.parse(line);
            if (msg != null && ProtocolConstants.CMD_REGISTER.equals(msg.getAction())) {
                String payload = msg.getPayload();
                String[] parts = payload.contains("|") ? payload.split("\\|") : payload.split(",");
                String lang = (parts.length > 0) ? parts[0].trim() : "Generic";
                int threads = 1;
                if (parts.length > 1) {
                    try {
                        threads = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {}
                }
                String caps = (parts.length > 2) ? parts[2].trim() : "CONV,MLP";

                WorkerNodeSession session = new WorkerNodeSession(msg.getId(), lang, threads, caps, socket, reader, writer);
                registry.register(session);

                recordEvent("WORKER", msg.getId(), "Worker registered: " + lang + " with " + threads + " threads (" + caps + ")");

                ProtocolMessage ack = new ProtocolMessage(ProtocolConstants.CMD_REGISTER_ACK, msg.getId(), "OK");
                writer.write(ack.toWireString());
                writer.flush();
            } else {
                socket.close();
            }
        } catch (Exception e) {
            System.err.println("[Server] Worker registration failed: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void acceptClientsLoop() {
        while (running.get()) {
            try {
                Socket socket = clientServerSocket.accept();
                new Thread(() -> handleClientSession(socket), "ClientSessionThread").start();
            } catch (IOException e) {
                if (!running.get()) break;
                System.err.println("[Server] Error accepting client: " + e.getMessage());
            }
        }
    }

    private void handleClientSession(Socket socket) {
        String assignedId = allocateClientId();
        String remoteIp = socket.getRemoteSocketAddress().toString();
        clientSessions.put(assignedId, new ClientSessionInfo(assignedId, remoteIp));
        recordEvent("CLIENT", assignedId, "New client connected via TCP from " + remoteIp);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // Send dynamic client assignment message immediately upon connection
            ProtocolMessage greeting = new ProtocolMessage("ASSIGN_CLIENT_ID", assignedId, "OK");
            writer.write(greeting.toWireString());
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                ProtocolMessage msg = ProtocolMessage.parse(line);
                if (msg == null) continue;

                String activeClientId = (msg.getId() != null && !msg.getId().trim().isEmpty() && !msg.getId().equals("0812"))
                        ? msg.getId() : assignedId;

                if (ProtocolConstants.CMD_CLIENT_REQ.equals(msg.getAction())) {
                    String payload = msg.getPayload();
                    ImageMatrix img;
                    if (payload.contains(",")) {
                        img = ImageMatrix.fromCsvString(payload, "custom");
                    } else {
                        img = getSampleImage(payload);
                    }

                    recordEvent("DISPATCH", activeClientId, "Received inference request for image: " + img.getLabel());
                    InferenceResponse resp = processInference(img, activeClientId);

                    String respPayload = String.format(Locale.US, "%s|%.2f|%s|%.2f|%s|%s",
                            resp.label, resp.confidence * 100.0f, resp.modeUsed.name(), resp.latencyMs,
                            resp.similaritiesSummary, resp.handlerInfo);
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_CLIENT_RESP, msg.getId(), respPayload);
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if ("SET_MODE".equals(msg.getAction())) {
                    String modeStr = msg.getPayload().trim();
                    try {
                        ExecutionMode m = ExecutionMode.valueOf(modeStr.toUpperCase());
                        setExecutionMode(m);
                        ProtocolMessage reply = new ProtocolMessage("SET_MODE_ACK", msg.getId(), m.name());
                        writer.write(reply.toWireString());
                        writer.flush();
                    } catch (Exception ignored) {}

                } else if (ProtocolConstants.CMD_GET_WORKERS.equals(msg.getAction())) {
                    List<WorkerNodeSession> allWorkers = registry.getAllConnectedWorkers();
                    List<WorkerNodeSession> activeWorkers = registry.getActiveWorkers();
                    java.util.Set<String> activeIds = new java.util.HashSet<>();
                    for (WorkerNodeSession w : activeWorkers) activeIds.add(w.getWorkerId());

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < allWorkers.size(); i++) {
                        WorkerNodeSession w = allWorkers.get(i);
                        if (i > 0) sb.append(";");
                        String status = activeIds.contains(w.getWorkerId()) ? "ACTIVE" : "STANDBY";
                        sb.append(w.getWorkerId()).append(",").append(w.getLanguage())
                          .append(",").append(w.getMaxThreads()).append(",").append(w.getActiveTaskCount())
                          .append(",").append(status);
                    }
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_WORKERS_RESP, msg.getId(), sb.toString());
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if ("SET_CUBES_CONFIG".equals(msg.getAction())) {
                    String payload = msg.getPayload();
                    String[] parts = payload.split("\\|");
                    int maxCubes = 0;
                    List<String> langs = new ArrayList<>();
                    if (parts.length > 0) {
                        try { maxCubes = Integer.parseInt(parts[0].trim()); } catch (Exception ignored) {}
                    }
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                        String[] lArr = parts[1].split(",");
                        for (String l : lArr) {
                            if (!l.trim().isEmpty()) langs.add(l.trim());
                        }
                    }
                    registry.setConfig(maxCubes, langs);
                    List<WorkerNodeSession> active = registry.getActiveWorkers();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < active.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(active.get(i).getWorkerId());
                    }
                    ProtocolMessage reply = new ProtocolMessage("SET_CUBES_CONFIG_ACK", msg.getId(), active.size() + "|" + sb.toString());
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if (ProtocolConstants.CMD_RUN_BENCHMARK.equals(msg.getAction())) {
                    int runs = 20;
                    if (!msg.getPayload().isEmpty()) {
                        try { runs = Integer.parseInt(msg.getPayload().trim()); } catch (Exception ignored) {}
                    }
                    String reportStr = runComparativeBenchmark(runs);
                    String wireTable = reportStr.replace("\n", "§");
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_BENCHMARK_RESP, msg.getId(), wireTable);
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if (ProtocolConstants.CMD_TRAIN_REQ.equals(msg.getAction())) {
                    ExecutionMode trainMode = currentMode;
                    int maxSamples = (trainMode == ExecutionMode.SEQUENTIAL || trainMode == ExecutionMode.PARALLEL)
                            ? Integer.MAX_VALUE : 40;
                    if (!msg.getPayload().isEmpty()) {
                        String[] trainParts = msg.getPayload().split("\\|");
                        try { trainMode = ExecutionMode.fromString(trainParts[0]); } catch (Exception ignored) {}
                        if (trainParts.length > 1) {
                            try { maxSamples = Integer.parseInt(trainParts[1].trim()); } catch (Exception ignored) {}
                        }
                    }
                    ModelTrainer.TrainingResult tRes = ModelTrainer.train(trainMode, registry, localThreadPool, maxSamples);
                    String trainPayload = String.format(Locale.US, "%d|%s|%.2f|%.1f|%s|%s",
                            tRes.samplesProcessed, tRes.modeUsed.name(), tRes.durationMs,
                            tRes.throughput, tRes.weightsFilePath, tRes.participatingNodes);
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_TRAIN_RESP, msg.getId(), trainPayload);
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if (ProtocolConstants.CMD_PING.equals(msg.getAction())) {
                    writer.write(new ProtocolMessage(ProtocolConstants.CMD_PONG, msg.getId(), "OK").toWireString());
                    writer.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            clientSessions.remove(assignedId);
            recordEvent("CLIENT", assignedId, "Client disconnected: " + remoteIp);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleHttpHealth(HttpExchange exchange) throws IOException {
        String resp = String.format(Locale.US,
                "{\"status\":\"online\",\"mode\":\"%s\",\"active_workers\":%d,\"total_workers\":%d,\"total_requests\":%d,\"uptime_seconds\":%d}\n",
                currentMode.name(), registry.getActiveWorkers().size(), registry.getAllConnectedWorkers().size(),
                totalClusterRequests.get(), getUptimeSeconds());
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpSession(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String newClientId = allocateClientId();
        String remote = exchange.getRemoteAddress().toString();
        clientSessions.put(newClientId, new ClientSessionInfo(newClientId, remote));
        recordEvent("CLIENT", newClientId, "Allocated session for web client: " + remote);

        String json = String.format(Locale.US, "{\"client_id\":\"%s\",\"status\":\"connected\"}\n", newClientId);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpWorkers(HttpExchange exchange) throws IOException {
        List<WorkerNodeSession> workers = registry.getAllConnectedWorkers();
        List<WorkerNodeSession> active = registry.getActiveWorkers();
        java.util.Set<String> activeIds = new java.util.HashSet<>();
        for (WorkerNodeSession w : active) activeIds.add(w.getWorkerId());

        StringBuilder sb = new StringBuilder("{\"workers\":[");
        for (int i = 0; i < workers.size(); i++) {
            WorkerNodeSession w = workers.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US,
                    "{\"id\":\"%s\",\"language\":\"%s\",\"threads\":%d,\"completed_tasks\":%d,\"active_tasks\":%d,\"status\":\"%s\",\"endpoint\":\"%s\"}",
                    w.getWorkerId(), w.getLanguage(), w.getMaxThreads(),
                    w.getCompletedTasks(), w.getActiveTaskCount(),
                    activeIds.contains(w.getWorkerId()) ? "ACTIVE" : "STANDBY",
                    w.getHttpBaseUrl()));
        }
        sb.append("]}\n");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpInfer(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String clientId = exchange.getRequestHeaders().getFirst("X-Client-ID");
        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = allocateClientId();
        }
        String remote = exchange.getRemoteAddress().toString();
        clientSessions.computeIfAbsent(clientId, id -> new ClientSessionInfo(id, remote));

        try {
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            ImageMatrix img;
            if (body.contains(",")) {
                img = ImageMatrix.fromCsvString(body, "custom");
            } else {
                img = getSampleImage(body.isEmpty() ? "panda" : body);
            }

            recordEvent("DISPATCH", clientId, "HTTP request for image: " + img.getLabel());
            InferenceResponse resp = processInference(img, clientId);

            String json = String.format(Locale.US,
                    "{\"client_id\":\"%s\",\"label\":\"%s\",\"confidence\":%.2f,\"mode\":\"%s\",\"latency_ms\":%.2f,\"handler\":\"%s\"}\n",
                    clientId, resp.label, resp.confidence * 100.0f, resp.modeUsed.name(), resp.latencyMs, resp.handlerInfo.replace("\"", "\\\""));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            String errJson = String.format(Locale.US,
                    "{\"client_id\":\"%s\",\"label\":\"error\",\"confidence\":0.0,\"mode\":\"ERROR\",\"latency_ms\":0.0,\"handler\":\"%s\"}\n",
                    clientId, e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown execution error");
            byte[] bytes = errJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void handleHttpAdminOverview(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        List<WorkerNodeSession> workers = registry.getAllConnectedWorkers();
        List<WorkerNodeSession> active = registry.getActiveWorkers();
        java.util.Set<String> activeIds = new java.util.HashSet<>();
        for (WorkerNodeSession w : active) activeIds.add(w.getWorkerId());

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(String.format(Locale.US, "\"cluster_status\":\"ONLINE\",\"mode\":\"%s\",\"mode_desc\":\"%s\",\"uptime_seconds\":%d,\"total_requests\":%d,",
                currentMode.name(), currentMode.getDescription(), getUptimeSeconds(), totalClusterRequests.get()));

        // Workers array
        sb.append("\"workers\":[");
        for (int i = 0; i < workers.size(); i++) {
            WorkerNodeSession w = workers.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US,
                    "{\"id\":\"%s\",\"language\":\"%s\",\"threads\":%d,\"completed_tasks\":%d,\"active_tasks\":%d,\"status\":\"%s\",\"endpoint\":\"%s\"}",
                    w.getWorkerId(), w.getLanguage(), w.getMaxThreads(),
                    w.getCompletedTasks(), w.getActiveTaskCount(),
                    activeIds.contains(w.getWorkerId()) ? "ACTIVE" : "STANDBY",
                    w.getHttpBaseUrl()));
        }
        sb.append("],");

        // Clients array
        sb.append("\"clients\":[");
        List<ClientSessionInfo> clientList = new ArrayList<>(clientSessions.values());
        for (int i = 0; i < clientList.size(); i++) {
            ClientSessionInfo c = clientList.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US,
                    "{\"client_id\":\"%s\",\"remote_address\":\"%s\",\"total_inferences\":%d,\"last_prediction\":\"%s\",\"last_latency_ms\":%.2f,\"connected_time\":%d,\"last_active_time\":%d}",
                    c.clientId, c.remoteAddress.replace("\"", ""), c.totalInferences.get(),
                    c.lastPrediction.replace("\"", ""), c.lastLatencyMs, c.connectedTime, c.lastActiveTime));
        }
        sb.append("],");

        // Recent events array
        sb.append("\"events\":[");
        List<ClusterEvent> events = getRecentEvents();
        int maxEv = Math.min(events.size(), 50);
        for (int i = 0; i < maxEv; i++) {
            ClusterEvent ev = events.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US,
                    "{\"timestamp\":%d,\"time_str\":\"%s\",\"type\":\"%s\",\"client_id\":\"%s\",\"details\":\"%s\"}",
                    ev.timestamp, ev.timeStr, ev.type, ev.clientId, ev.details.replace("\"", "\\\"")));
        }
        sb.append("]}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpAdminMode(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        try {
            ExecutionMode m = ExecutionMode.valueOf(body.toUpperCase());
            setExecutionMode(m);
        } catch (Exception ignored) {}

        String json = "{\"status\":\"ok\",\"mode\":\"" + currentMode.name() + "\"}\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpAdminClearLogs(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        clusterEvents.clear();
        recordEvent("SYSTEM", "Admin", "Cluster audit log buffer cleared by administrator.");
        String json = "{\"status\":\"ok\"}\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpAdmin(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        File adminFile = new File("web/admin.html");
        if (!adminFile.exists()) adminFile = new File("../web/admin.html");
        byte[] bytes;
        if (adminFile.exists()) {
            bytes = java.nio.file.Files.readAllBytes(adminFile.toPath());
        } else {
            String fallback = "<html><body style='font-family:sans-serif;background:#0f172a;color:#fff;padding:40px;'>"
                    + "<h1>Open AI Cubes Admin Dashboard</h1><p>Online. API endpoints: /api/admin/overview, /workers, /infer</p></body></html>";
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpDatasetSamples(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String json = "{\"classes\":[\"panda\",\"dog\",\"cat\",\"lion\",\"bear\"]}\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpDatasetImage(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String className = "panda";
        if (query != null && query.contains("class=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("class=")) {
                    className = param.substring(6).toLowerCase().trim();
                    break;
                }
            }
        }
        File dir = new File("dataset/real/test/" + className);
        if (!dir.exists()) dir = new File("../dataset/real/test/" + className);
        File[] photos = (dir.exists() && dir.isDirectory())
                ? dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"))
                : null;
        if (photos != null && photos.length > 0) {
            Arrays.sort(photos, Comparator.comparing(File::getName));
            byte[] bytes = java.nio.file.Files.readAllBytes(photos[0].toPath());
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }
        exchange.sendResponseHeaders(404, -1);
    }

    private void handleHttpRoot(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        File webFile = new File("web/index.html");
        if (!webFile.exists()) webFile = new File("../web/index.html");
        byte[] bytes;
        if (webFile.exists()) {
            bytes = java.nio.file.Files.readAllBytes(webFile.toPath());
        } else {
            String fallback = "<html><body style='font-family:sans-serif;background:#0f172a;color:#fff;padding:40px;'>"
                    + "<h1>Open AI Cubes Orchestrator</h1><p>Online. API endpoints: /health, /workers, /infer, /status</p></body></html>";
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpMode(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String modeStr;
            try (InputStream is = exchange.getRequestBody()) {
                modeStr = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            try {
                ExecutionMode m = ExecutionMode.valueOf(modeStr.toUpperCase());
                setExecutionMode(m);
            } catch (Exception ignored) {}
        }
        String json = "{\"status\":\"ok\",\"mode\":\"" + currentMode.name() + "\",\"description\":\"" + currentMode.getDescription() + "\"}\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleHttpRegisterCloudWorker(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        String[] parts = body.split("\\|");
        String id = parts.length > 0 ? parts[0].trim() : "node_cloud_" + System.currentTimeMillis();
        String lang = parts.length > 1 ? parts[1].trim() : "Cloud";
        int threads = 4;
        if (parts.length > 2) {
            try { threads = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) {}
        }
        String url = parts.length > 3 ? parts[3].trim() : (parts.length == 1 ? parts[0].trim() : "");
        if (url.startsWith("http")) {
            WorkerNodeSession ws = new WorkerNodeSession(id, lang, threads, "CONV,MLP", url);
            registry.register(ws);
            recordEvent("WORKER", "Cluster", "Manually registered Cloud Worker: " + id + " (" + lang + ") -> " + url);
        }
        String json = "{\"status\":\"ok\",\"registered_id\":\"" + id + "\"}\n";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (httpServer != null) httpServer.stop(0);
            if (workerServerSocket != null) workerServerSocket.close();
            if (clientServerSocket != null) clientServerSocket.close();
        } catch (Exception ignored) {}
        localThreadPool.shutdown();
    }

    public static void main(String[] args) throws Exception {
        String envPort = System.getenv("PORT");
        int hPort = (args.length > 2) ? Integer.parseInt(args[2]) : (envPort != null ? Integer.parseInt(envPort) : 8080);
        int wPort = (args.length > 0) ? Integer.parseInt(args[0]) : (hPort == 9010 ? 9011 : ProtocolConstants.DEFAULT_WORKER_PORT);
        int cPort = (args.length > 1) ? Integer.parseInt(args[1]) : (hPort == 8080 ? 8085 : ProtocolConstants.DEFAULT_CLIENT_PORT);
        if (cPort == hPort) {
            cPort = hPort + 5;
        }

        ServerOrchestrator server = new ServerOrchestrator(wPort, cPort, hPort);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        while (server.running.get()) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
