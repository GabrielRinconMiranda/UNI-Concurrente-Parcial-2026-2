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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ServerOrchestrator: Central coordinating server for the Open AI Cubes architecture.
 * Manages client connections, worker node registration, and task orchestration
 * across the 4 computational execution modes.
 */
public class ServerOrchestrator {
    private final int workerPort;
    private final int clientPort;
    private final NodeRegistry registry = new NodeRegistry();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ExecutionMode currentMode = ExecutionMode.PARALLEL;
    private final ExecutorService localThreadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final Object sequentialLock = new Object();
    private final Object distributedLock = new Object();

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
    private final int httpPort;
    private HttpServer httpServer;

    public ServerOrchestrator(int workerPort, int clientPort) {
        this(workerPort, clientPort, 8080);
    }

    public ServerOrchestrator(int workerPort, int clientPort, int httpPort) {
        this.workerPort = workerPort;
        this.clientPort = clientPort;
        this.httpPort = httpPort;
    }

    public ExecutionMode getCurrentMode() {
        return currentMode;
    }

    public void setExecutionMode(ExecutionMode mode) {
        this.currentMode = mode;
        System.out.println("[Server] Active execution mode changed to: " + mode.getDescription());
    }

    public NodeRegistry getRegistry() {
        return registry;
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

    /**
     * Orchestrates inference across the active execution mode.
     */
    public InferenceResponse processInference(ImageMatrix image) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        long startNano = System.nanoTime();

        ExecutionMode mode = this.currentMode;
        MlpChunk.Prediction pred;
        String handlerInfo;

        try {
            switch (mode) {
                case SEQUENTIAL:
                    // 1 Process, 1 Thread sequential
                    synchronized (sequentialLock) {
                        pred = CnnInferenceEngine.infer(image);
                    }
                    handlerInfo = "Núcleo local: secuencial de un solo hilo";
                    break;

                case PARALLEL:
                    // Multi-threaded local thread pool
                    Future<MlpChunk.Prediction> future = localThreadPool.submit(() -> CnnInferenceEngine.infer(image));
                    pred = future.get();
                    handlerInfo = "Núcleo local: paralelismo multinúcleo (" + Runtime.getRuntime().availableProcessors() + " hilos)";
                    break;

                case DISTRIBUTED:
                    // Micro-chunk pipeline: workers run internal parallelism, but only one cube executes at a time
                    synchronized (distributedLock) {
                        InferenceExecutionResult res = executeDistributedPipeline(image, taskId, false);
                        pred = res.prediction;
                        handlerInfo = res.handlerInfo;
                    }
                    break;

                case PARALLEL_DISTRIBUTED:
                    // Concurrent distributed pipeline across cubes and threads
                    InferenceExecutionResult res = executeDistributedPipeline(image, taskId, true);
                    pred = res.prediction;
                    handlerInfo = res.handlerInfo;
                    break;

                default:
                    pred = CnnInferenceEngine.infer(image);
                    handlerInfo = "Local por defecto";
                    break;
            }
        } catch (Exception e) {
            pred = new MlpChunk.Prediction("error:" + e.getMessage(), 0.0f, new float[0]);
            handlerInfo = "Error: " + e.getMessage();
        }

        double elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0;
        return new InferenceResponse(pred.label, pred.confidence, mode, elapsedMs, handlerInfo, pred.getSimilaritiesSummary());
    }

    private InferenceExecutionResult executeDistributedPipeline(ImageMatrix image, String taskId, boolean isParallelDistributed) throws Exception {
        List<WorkerNodeSession> workers = registry.getActiveWorkers();
        if (workers.isEmpty()) {
            MlpChunk.Prediction pred = CnnInferenceEngine.infer(image);
            String info = "Respaldo local: sin nodos remotos conectados";
            return new InferenceExecutionResult(pred, info);
        }

        if (workers.size() >= 2) {
            WorkerNodeSession convWorker = registry.acquireWorker();
            WorkerNodeSession mlpWorker = registry.acquireWorkerExcluding(convWorker);

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

            String modeLabel = isParallelDistributed ? "Distribuido paralelo concurrente" : "Distribuido secuencial";
            String info = modeLabel + ": conv[" + convWorker.getWorkerId() + "/" + convWorker.getLanguage()
                    + " (" + convWorker.getMaxThreads() + " hilos)] -> mlp[" + mlpWorker.getWorkerId() + "/"
                    + mlpWorker.getLanguage() + " (" + mlpWorker.getMaxThreads() + " hilos)]";
            return new InferenceExecutionResult(pred, info);
        } else {
            WorkerNodeSession worker = registry.acquireWorker();
            ProtocolMessage inferReq = new ProtocolMessage(
                    ProtocolConstants.CMD_INFER_REQ,
                    taskId,
                    image.getLabel() + "|" + image.toCsvString()
            );
            ProtocolMessage resp = worker.sendAndReceive(inferReq);
            MlpChunk.Prediction pred = MlpChunk.parseProtocolString(resp.getPayload());
            String modeLabel = isParallelDistributed ? "Distribuido paralelo" : "Distribuido secuencial";
            String info = modeLabel + " (nodo único): " + worker.getWorkerId() + " [" + worker.getLanguage()
                    + ", " + worker.getMaxThreads() + " hilos]";
            return new InferenceExecutionResult(pred, info);
        }
    }

    public static ImageMatrix getSampleImage(String className) {
        String norm = (className != null) ? className.toLowerCase().trim() : "unknown";
        File dir = new File("dataset/real/test/" + norm);
        if (!dir.exists()) dir = new File("../dataset/real/test/" + norm);
        File[] photos = (dir.exists() && dir.isDirectory())
                ? dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"))
                : null;
        if (photos != null && photos.length > 0) {
            Arrays.sort(photos, Comparator.comparing(File::getName));
            try {
                return ImageMatrix.fromFile(photos[0], norm);
            } catch (Exception ignored) {}
        }
        return ImageMatrix.createSample(norm);
    }

    /**
     * Executes an internal comparative benchmark across all 4 modes with warmup.
     */
    public String runComparativeBenchmark(int runsPerMode) {
        int runs = Math.max(10, runsPerMode);
        ExecutionMode[] modes = ExecutionMode.values();
        String[] samples = {"panda", "cat", "dog", "bear", "lion"};

        // 1. Warmup phase (5 iterations)
        for (int i = 0; i < 5; i++) {
            ImageMatrix img = getSampleImage(samples[i % samples.length]);
            CnnInferenceEngine.infer(img);
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.US, "Reporte de benchmark comparativo (Muestras por modo: %d)\n", runs));
        report.append(String.format(Locale.US, "%-22s | %-12s | %-10s | %-10s | %-10s\n",
                "Modo de Ejecución", "Rendimiento", "Media (ms)", "P95 (ms)", "Mín (ms)"));

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

            double totalSec = (System.nanoTime() - startTotal) / 1_000_000_000.0;
            double throughput = runs / Math.max(totalSec, 0.0001);

            Collections.sort(latencies);
            double min = latencies.get(0);
            double mean = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p95 = latencies.get((int) (latencies.size() * 0.95));

            report.append(String.format(Locale.US, "%-22s | %8.1f req/s | %8.2f ms | %8.2f ms | %8.2f ms\n",
                    mode.name(), throughput, mean, p95, min));
        }

        setExecutionMode(originalMode);
        return report.toString();
    }

    /**
     * Starts the server listeners.
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

        System.out.println("[Server] Open AI Cubes Central Orchestrator online");
        System.out.println("[Server] Worker registration port: " + workerPort);
        System.out.println("[Server] Client query port: " + clientPort);
        System.out.println("[Server] Initial execution mode: " + currentMode.getDescription());
        if (MlpChunk.isRealDatasetMode()) {
            System.out.println("[Server] Base de datos detectada: REAL (200 fotografías en dataset/real/). Procesamiento convolucional 100% real.");
        } else {
            System.out.println("[Server] Base de datos real no encontrada. Modo activo: MOCK (sintético). Procesamiento convolucional 100% real.");
        }

        new Thread(this::acceptWorkersLoop, "WorkerAcceptorThread").start();
        new Thread(this::acceptClientsLoop, "ClientAcceptorThread").start();

        // Start standard HTTP REST API listener for web / cloud hosting
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
            httpServer.createContext("/", this::handleHttpRoot);
            httpServer.setExecutor(localThreadPool);
            httpServer.start();
            System.out.println("[Server] HTTP REST API listener online: http://0.0.0.0:" + httpPort);

            // Auto-register cloud workers from environment variable (e.g. Render URLs)
            String cloudWorkersEnv = System.getenv("CLOUD_WORKERS");
            if (cloudWorkersEnv != null && !cloudWorkersEnv.trim().isEmpty()) {
                String[] urls = cloudWorkersEnv.split(",");
                for (int i = 0; i < urls.length; i++) {
                    String u = urls[i].trim();
                    if (!u.isEmpty()) {
                        String lang = u.toLowerCase().contains("go") ? "Go" : (u.toLowerCase().contains("py") ? "Python" : "CloudNode");
                        String id = "node_" + lang.toLowerCase() + "_render_" + (i + 1);
                        WorkerNodeSession ws = new WorkerNodeSession(id, lang, 4, "CONV,MLP", u);
                        registry.register(ws);
                        System.out.println("[Server] Registered Cloud HTTP Worker from ENV: " + id + " -> " + u);
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
            ProtocolMessage msg = ProtocolMessage.parse(line);
            if (msg != null && ProtocolConstants.CMD_REGISTER.equals(msg.getAction())) {
                String[] parts = msg.getPayload().split("\\|");
                String lang = (parts.length > 0) ? parts[0] : "Unknown";
                int threads = (parts.length > 1) ? Integer.parseInt(parts[1]) : 1;
                String caps = (parts.length > 2) ? parts[2] : "CONV,MLP";

                WorkerNodeSession session = new WorkerNodeSession(msg.getId(), lang, threads, caps, socket);
                registry.register(session);

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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                ProtocolMessage msg = ProtocolMessage.parse(line);
                if (msg == null) continue;

                if (ProtocolConstants.CMD_CLIENT_REQ.equals(msg.getAction())) {
                    String payload = msg.getPayload();
                    ImageMatrix img;
                    if (payload.contains(",")) {
                        img = ImageMatrix.fromCsvString(payload, "custom");
                    } else {
                        img = getSampleImage(payload);
                    }

                    InferenceResponse resp = processInference(img);

                    // Payload: <label>|<confidence>|<mode>|<latency_ms>|<similarities>|<handler_info>
                    String respPayload = String.format(Locale.US, "%s|%.2f|%s|%.2f|%s|%s",
                            resp.label, resp.confidence * 100.0f, resp.modeUsed.name(), resp.latencyMs,
                            resp.similaritiesSummary, resp.handlerInfo);
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_CLIENT_RESP, msg.getId(), respPayload);
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if (ProtocolConstants.CMD_GET_WORKERS.equals(msg.getAction())) {
                    List<WorkerNodeSession> allWorkers = registry.getAllConnectedWorkers();
                    List<WorkerNodeSession> activeWorkers = registry.getActiveWorkers();
                    java.util.Set<String> activeIds = new java.util.HashSet<>();
                    for (WorkerNodeSession w : activeWorkers) activeIds.add(w.getWorkerId());

                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < allWorkers.size(); i++) {
                        WorkerNodeSession w = allWorkers.get(i);
                        if (i > 0) sb.append(";");
                        String status = activeIds.contains(w.getWorkerId()) ? "ACTIVO" : "EN_ESPERA";
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
                    int runs = 30;
                    if (!msg.getPayload().isEmpty()) {
                        try { runs = Integer.parseInt(msg.getPayload().trim()); } catch (Exception ignored) {}
                    }
                    String reportStr = runComparativeBenchmark(runs);
                    // Encode newlines as escaped for single-line wire transmission
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
                    // Payload: <samples>|<mode>|<duration_ms>|<throughput>|<weights_file>|<participating_nodes>
                    String trainPayload = String.format(Locale.US, "%d|%s|%.2f|%.1f|%s|%s",
                            tRes.samplesProcessed, tRes.modeUsed.name(), tRes.durationMs,
                            tRes.throughput, tRes.weightsFilePath, tRes.participatingNodes);
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_TRAIN_RESP, msg.getId(), trainPayload);
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if (ProtocolConstants.CMD_BENCHMARK_TRAIN.equals(msg.getAction())) {
                    int runs = 3;
                    if (!msg.getPayload().isEmpty()) {
                        try { runs = Integer.parseInt(msg.getPayload().trim()); } catch (Exception ignored) {}
                    }
                    String reportStr = ModelTrainer.runComparativeTrainingBenchmark(registry, localThreadPool, runs);
                    String wireTable = reportStr.replace("\n", "§");
                    ProtocolMessage reply = new ProtocolMessage(ProtocolConstants.CMD_BENCHMARK_TRAIN_RESP, msg.getId(), wireTable);
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if ("SET_MODE".equals(msg.getAction())) {
                    ExecutionMode newMode = ExecutionMode.fromString(msg.getPayload());
                    setExecutionMode(newMode);
                    ProtocolMessage reply = new ProtocolMessage("SET_MODE_ACK", msg.getId(), newMode.name());
                    writer.write(reply.toWireString());
                    writer.flush();

                } else if (ProtocolConstants.CMD_PING.equals(msg.getAction())) {
                    writer.write(new ProtocolMessage(ProtocolConstants.CMD_PONG, msg.getId(), "OK").toWireString());
                    writer.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleHttpHealth(HttpExchange exchange) throws IOException {
        String resp = String.format(Locale.US,
                "{\"status\":\"online\",\"mode\":\"%s\",\"active_workers\":%d,\"total_workers\":%d}\n",
                currentMode.name(), registry.getActiveWorkers().size(), registry.getAllConnectedWorkers().size());
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
                    "{\"id\":\"%s\",\"language\":\"%s\",\"threads\":%d,\"status\":\"%s\"}",
                    w.getWorkerId(), w.getLanguage(), w.getMaxThreads(),
                    activeIds.contains(w.getWorkerId()) ? "ACTIVE" : "STANDBY"));
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
            InferenceResponse resp = processInference(img);
            String json = String.format(Locale.US,
                    "{\"label\":\"%s\",\"confidence\":%.2f,\"mode\":\"%s\",\"latency_ms\":%.2f,\"handler\":\"%s\"}\n",
                    resp.label, resp.confidence * 100.0f, resp.modeUsed.name(), resp.latencyMs, resp.handlerInfo.replace("\"", "\\\""));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            String errJson = String.format(Locale.US,
                    "{\"label\":\"error\",\"confidence\":0.0,\"mode\":\"ERROR\",\"latency_ms\":0.0,\"handler\":\"%s\"}\n",
                    e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown execution error");
            byte[] bytes = errJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
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
            System.out.println("[Server] Manually registered Cloud Worker: " + id + " (" + lang + ") -> " + url);
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
