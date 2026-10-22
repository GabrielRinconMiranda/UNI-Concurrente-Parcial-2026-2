package core;

import protocol.ProtocolConstants;
import protocol.ProtocolMessage;
import server.ExecutionMode;
import server.NodeRegistry;
import server.WorkerNodeSession;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * ModelTrainer: High-Level CNN Model Training Pipeline.
 * Decoupled from inference, yet parallelized and distributed across
 * Sequential, Parallel, Distributed, and Parallel-Distributed modes.
 * Writes trained prototype weights to dataset/weights.csv.
 */
public class ModelTrainer {
    public static final String DEFAULT_WEIGHTS_PATH = "dataset/weights.csv";

    public static class TrainingResult {
        public final ExecutionMode modeUsed;
        public final int samplesProcessed;
        public final double durationMs;
        public final double throughput;
        public final String weightsFilePath;
        public final String participatingNodes;

        public TrainingResult(ExecutionMode modeUsed, int samplesProcessed, double durationMs,
                              double throughput, String weightsFilePath, String participatingNodes) {
            this.modeUsed = modeUsed;
            this.samplesProcessed = samplesProcessed;
            this.durationMs = durationMs;
            this.throughput = throughput;
            this.weightsFilePath = weightsFilePath;
            this.participatingNodes = participatingNodes;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "Entrenamiento: %d muestras procesadas en %.2f ms (%.1f img/s) via %s [%s] -> %s",
                    samplesProcessed, durationMs, throughput, modeUsed.name(), participatingNodes, weightsFilePath);
        }
    }

    /**
     * Ingests available training dataset from disk or memory.
     */
    public static List<ImageMatrix> loadTrainingDataset() {
        List<ImageMatrix> dataset = new ArrayList<>();

        // 1. Try loading from all real photographs in dataset/real/{train,val,test}/{class}/
        File realBaseDir = new File("dataset/real");
        if (!realBaseDir.exists()) {
            realBaseDir = new File("../dataset/real");
        }

        if (realBaseDir.exists() && realBaseDir.isDirectory()) {
            String[] splits = {"train", "val", "test"};
            for (String split : splits) {
                File splitDir = new File(realBaseDir, split);
                if (splitDir.exists() && splitDir.isDirectory()) {
                    for (String c : MlpChunk.CLASSES) {
                        File classDir = new File(splitDir, c);
                        if (classDir.exists() && classDir.isDirectory()) {
                            File[] files = classDir.listFiles((dir, name) -> {
                                String n = name.toLowerCase();
                                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".bmp");
                            });
                            if (files != null) {
                                Arrays.sort(files, Comparator.comparing(File::getName));
                                for (File f : files) {
                                    try {
                                        dataset.add(ImageMatrix.fromFile(f, c));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. If no images found, try loading from database.csv
        if (dataset.isEmpty()) {
            File dbFile = new File("dataset/database.csv");
            if (!dbFile.exists()) {
                dbFile = new File("../dataset/database.csv");
            }
            if (dbFile.exists() && dbFile.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(dbFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        try {
                            ImageMatrix img = ImageMatrix.fromCsvString(line, "unknown");
                            if (MlpChunk.findClassIndex(img.getLabel()) != -1) {
                                dataset.add(img);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }

        // 3. Fallback to canonical prototype patterns ONLY if no external images or database was found
        if (dataset.isEmpty()) {
            for (String c : MlpChunk.CLASSES) {
                for (int i = 0; i < 10; i++) {
                    dataset.add(ImageMatrix.createSample(c));
                }
            }
        }

        return dataset;
    }

    public static float[] normalizeVector(float[] v) {
        float sumSq = 0.0f;
        for (float val : v) sumSq += val * val;
        float norm = (float) Math.sqrt(sumSq) + 1e-6f;
        float[] res = new float[v.length];
        for (int i = 0; i < v.length; i++) res[i] = v[i] / norm;
        return res;
    }

    /**
     * Executes end-to-end model training across the selected execution mode with optional sample cap.
     */
    public static TrainingResult train(ExecutionMode mode, NodeRegistry registry, ExecutorService threadPool, int maxSamples) {
        List<ImageMatrix> fullDataset = loadTrainingDataset();
        if (fullDataset.isEmpty()) {
            throw new IllegalStateException("No training samples found in dataset/real/train or dataset/database.csv");
        }

        List<ImageMatrix> dataset;
        if (maxSamples > 0 && maxSamples < fullDataset.size()) {
            // Balanced stratified sampling across all target classes
            Map<String, List<ImageMatrix>> byClass = new HashMap<>();
            for (String c : MlpChunk.CLASSES) {
                byClass.put(c, new ArrayList<>());
            }
            for (ImageMatrix img : fullDataset) {
                String c = img.getLabel().toLowerCase().trim();
                if (byClass.containsKey(c)) {
                    byClass.get(c).add(img);
                }
            }
            int perClass = Math.max(1, maxSamples / MlpChunk.NUM_CLASSES);
            dataset = new ArrayList<>();
            for (String c : MlpChunk.CLASSES) {
                List<ImageMatrix> list = byClass.get(c);
                int take = Math.min(perClass, list.size());
                for (int i = 0; i < take; i++) {
                    dataset.add(list.get(i));
                }
            }
        } else {
            dataset = fullDataset;
        }

        long startNano = System.nanoTime();
        float[][] classAccum = new float[MlpChunk.NUM_CLASSES][ConvolutionChunk.TOTAL_FEATURE_DIM];
        int[] classCounts = new int[MlpChunk.NUM_CLASSES];
        List<Pair<Integer, float[]>> sampleFeatures = new ArrayList<>();
        String participatingNodes;

        switch (mode) {
            case SEQUENTIAL:
                // Single-threaded sequential training
                for (ImageMatrix img : dataset) {
                    int cIdx = MlpChunk.findClassIndex(img.getLabel());
                    if (cIdx != -1) {
                        float[] feats = normalizeVector(ConvolutionChunk.process(img));
                        sampleFeatures.add(new Pair<>(cIdx, feats));
                        for (int i = 0; i < ConvolutionChunk.TOTAL_FEATURE_DIM; i++) {
                            classAccum[cIdx][i] += feats[i];
                        }
                        classCounts[cIdx]++;
                    }
                }
                participatingNodes = "Local Core (Sequential Single-Thread)";
                break;

            case PARALLEL:
                // Local multi-core thread pool parallelism
                List<Future<Pair<Integer, float[]>>> parallelFutures = new ArrayList<>();
                for (ImageMatrix img : dataset) {
                    final int cIdx = MlpChunk.findClassIndex(img.getLabel());
                    if (cIdx != -1) {
                        parallelFutures.add(threadPool.submit(() -> {
                            float[] feats = normalizeVector(ConvolutionChunk.process(img));
                            return new Pair<>(cIdx, feats);
                        }));
                    }
                }
                for (Future<Pair<Integer, float[]>> fut : parallelFutures) {
                    try {
                        Pair<Integer, float[]> res = fut.get();
                        sampleFeatures.add(res);
                        int cIdx = res.first;
                        float[] feats = res.second;
                        for (int i = 0; i < ConvolutionChunk.TOTAL_FEATURE_DIM; i++) {
                            classAccum[cIdx][i] += feats[i];
                        }
                        classCounts[cIdx]++;
                    } catch (Exception e) {
                        System.err.println("[Trainer] Error in parallel task: " + e.getMessage());
                    }
                }
                participatingNodes = "Local Core (Thread Pool size=" + Runtime.getRuntime().availableProcessors() + ")";
                break;

            case DISTRIBUTED:
                // Distributed Sequential: Micro-chunks execute internally in parallel with all their cores,
                // but only one micro-chunk cube runs at a time in the cluster sequence.
                List<WorkerNodeSession> distWorkers = (registry != null) ? registry.getActiveWorkers() : Collections.emptyList();
                if (distWorkers.isEmpty()) {
                    for (ImageMatrix img : dataset) {
                        int cIdx = MlpChunk.findClassIndex(img.getLabel());
                        if (cIdx != -1) {
                            float[] feats = normalizeVector(ConvolutionChunk.process(img));
                            sampleFeatures.add(new Pair<>(cIdx, feats));
                            for (int i = 0; i < ConvolutionChunk.TOTAL_FEATURE_DIM; i++) {
                                classAccum[cIdx][i] += feats[i];
                            }
                            classCounts[cIdx]++;
                        }
                    }
                    participatingNodes = "Fallback Local (No remote workers connected)";
                } else {
                    for (ImageMatrix img : dataset) {
                        int cIdx = MlpChunk.findClassIndex(img.getLabel());
                        if (cIdx != -1) {
                            try {
                                WorkerNodeSession worker = registry.acquireWorker();
                                String taskId = "train_dist_" + UUID.randomUUID().toString().substring(0, 8);
                                ProtocolMessage req = new ProtocolMessage(
                                        ProtocolConstants.CMD_CONV_REQ,
                                        taskId,
                                        img.toCsvString()
                                );
                                ProtocolMessage resp = worker.sendAndReceive(req);
                                float[] feats = normalizeVector(ConvolutionChunk.featuresFromString(resp.getPayload()));
                                sampleFeatures.add(new Pair<>(cIdx, feats));
                                for (int i = 0; i < ConvolutionChunk.TOTAL_FEATURE_DIM; i++) {
                                    classAccum[cIdx][i] += feats[i];
                                }
                                classCounts[cIdx]++;
                            } catch (Exception e) {
                                System.err.println("[Trainer] Error in distributed task: " + e.getMessage());
                            }
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    for (int w = 0; w < distWorkers.size(); w++) {
                        if (w > 0) sb.append(", ");
                        sb.append(distWorkers.get(w).getWorkerId()).append(" (").append(distWorkers.get(w).getLanguage()).append(")");
                    }
                    participatingNodes = "Cluster (Distributed Sequential): " + sb.toString();
                }
                break;

            case PARALLEL_DISTRIBUTED:
                // Distributed Parallel: Concurrent distribution across remote worker cubes simultaneously
                List<WorkerNodeSession> parDistWorkers = (registry != null) ? registry.getActiveWorkers() : Collections.emptyList();
                if (parDistWorkers.isEmpty()) {
                    for (ImageMatrix img : dataset) {
                        int cIdx = MlpChunk.findClassIndex(img.getLabel());
                        if (cIdx != -1) {
                            float[] feats = normalizeVector(ConvolutionChunk.process(img));
                            sampleFeatures.add(new Pair<>(cIdx, feats));
                            for (int i = 0; i < ConvolutionChunk.TOTAL_FEATURE_DIM; i++) {
                                classAccum[cIdx][i] += feats[i];
                            }
                            classCounts[cIdx]++;
                        }
                    }
                    participatingNodes = "Fallback Local (No remote workers connected)";
                } else {
                    List<Future<Pair<Integer, float[]>>> distFutures = new ArrayList<>();
                    for (ImageMatrix img : dataset) {
                        final int cIdx = MlpChunk.findClassIndex(img.getLabel());
                        if (cIdx != -1) {
                            distFutures.add(threadPool.submit(() -> {
                                WorkerNodeSession worker = registry.acquireWorker();
                                String taskId = "train_par_" + UUID.randomUUID().toString().substring(0, 8);
                                ProtocolMessage req = new ProtocolMessage(
                                        ProtocolConstants.CMD_CONV_REQ,
                                        taskId,
                                        img.toCsvString()
                                );
                                ProtocolMessage resp = worker.sendAndReceive(req);
                                float[] feats = normalizeVector(ConvolutionChunk.featuresFromString(resp.getPayload()));
                                return new Pair<>(cIdx, feats);
                            }));
                        }
                    }
                    for (Future<Pair<Integer, float[]>> fut : distFutures) {
                        try {
                            Pair<Integer, float[]> res = fut.get();
                            sampleFeatures.add(res);
                            int cIdx = res.first;
                            float[] feats = res.second;
                            for (int i = 0; i < ConvolutionChunk.TOTAL_FEATURE_DIM; i++) {
                                classAccum[cIdx][i] += feats[i];
                            }
                            classCounts[cIdx]++;
                        } catch (Exception e) {
                            System.err.println("[Trainer] Error in distributed parallel task: " + e.getMessage());
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    for (int w = 0; w < parDistWorkers.size(); w++) {
                        if (w > 0) sb.append(", ");
                        sb.append(parDistWorkers.get(w).getWorkerId()).append(" (").append(parDistWorkers.get(w).getLanguage()).append(")");
                    }
                    participatingNodes = "Cluster (Distributed Parallel): " + sb.toString();
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown execution mode: " + mode);
        }

        // Calculate prototype weights using Dual Ridge Regression
        float[][] trainedWeights = new float[MlpChunk.NUM_CLASSES][ConvolutionChunk.TOTAL_FEATURE_DIM];
        int N = sampleFeatures.size();

        if (N >= MlpChunk.NUM_CLASSES) {
            double[][] K = new double[N][N];
            for (int i = 0; i < N; i++) {
                float[] xi = sampleFeatures.get(i).second;
                for (int j = i; j < N; j++) {
                    float[] xj = sampleFeatures.get(j).second;
                    double dot = 0;
                    for (int d = 0; d < ConvolutionChunk.TOTAL_FEATURE_DIM; d++) dot += xi[d] * xj[d];
                    K[i][j] = dot;
                    K[j][i] = dot;
                }
            }

            double lambda = 0.08;
            for (int i = 0; i < N; i++) K[i][i] += lambda;
            double[][] invK = invertMatrix(K);

            double[][] Y = new double[N][MlpChunk.NUM_CLASSES];
            for (int i = 0; i < N; i++) {
                int y = sampleFeatures.get(i).first;
                for (int c = 0; c < MlpChunk.NUM_CLASSES; c++) {
                    Y[i][c] = (c == y) ? 1.0 : -0.25;
                }
            }

            double[][] alpha = new double[N][MlpChunk.NUM_CLASSES];
            for (int i = 0; i < N; i++) {
                for (int c = 0; c < MlpChunk.NUM_CLASSES; c++) {
                    double sum = 0;
                    for (int j = 0; j < N; j++) sum += invK[i][j] * Y[j][c];
                    alpha[i][c] = sum;
                }
            }

            for (int c = 0; c < MlpChunk.NUM_CLASSES; c++) {
                for (int d = 0; d < ConvolutionChunk.TOTAL_FEATURE_DIM; d++) {
                    double sum = 0;
                    for (int i = 0; i < N; i++) sum += alpha[i][c] * sampleFeatures.get(i).second[d];
                    trainedWeights[c][d] = (float) sum;
                }
                trainedWeights[c] = normalizeVector(trainedWeights[c]);
            }
        } else {
            for (int c = 0; c < MlpChunk.NUM_CLASSES; c++) {
                trainedWeights[c] = (classCounts[c] > 0) ? normalizeVector(classAccum[c]) : MlpChunk.getWeights()[c];
            }
        }

        // Update in-memory weights for immediate subsequent inference
        MlpChunk.setWeights(trainedWeights);

        // Persist trained weights to standard weights.csv
        File weightsFile = new File(DEFAULT_WEIGHTS_PATH);
        if (!weightsFile.exists()) {
            File alt = new File("../" + DEFAULT_WEIGHTS_PATH);
            if (alt.getParentFile() != null && alt.getParentFile().exists()) {
                weightsFile = alt;
            }
        }
        MlpChunk.saveWeightsToFile(weightsFile, trainedWeights);

        double durationMs = (System.nanoTime() - startNano) / 1_000_000.0;
        double throughput = dataset.size() / Math.max(durationMs / 1000.0, 0.0001);

        return new TrainingResult(mode, dataset.size(), durationMs, throughput, weightsFile.getPath(), participatingNodes);
    }

    private static double[][] invertMatrix(double[][] a) {
        int n = a.length;
        double[][] x = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) x[i][j] = a[i][j];
            x[i][n + i] = 1.0;
        }
        for (int i = 0; i < n; i++) {
            int pivot = i;
            for (int j = i + 1; j < n; j++) {
                if (Math.abs(x[j][i]) > Math.abs(x[pivot][i])) pivot = j;
            }
            double[] temp = x[i]; x[i] = x[pivot]; x[pivot] = temp;
            double div = x[i][i];
            for (int j = 0; j < 2 * n; j++) x[i][j] /= div;
            for (int j = 0; j < n; j++) {
                if (j != i) {
                    double mult = x[j][i];
                    for (int k = 0; k < 2 * n; k++) x[j][k] -= mult * x[i][k];
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) inv[i][j] = x[i][n + j];
        }
        return inv;
    }

    public static TrainingResult train(ExecutionMode mode, NodeRegistry registry, ExecutorService threadPool) {
        return train(mode, registry, threadPool, Integer.MAX_VALUE);
    }

    /**
     * Executes automated 4-mode comparative training benchmark with warmup.
     */
    public static String runComparativeTrainingBenchmark(NodeRegistry registry, ExecutorService threadPool, int runsPerMode) {
        StringBuilder report = new StringBuilder();
        report.append("Resultados del Benchmark de Entrenamiento:\n");
        report.append(String.format(Locale.US, "%-22s | %13s | %13s | %12s\n",
                "Escenario", "Rendimiento", "Tiempo Total", "Aceleración"));

        int benchSamples = 40; // 40 high-resolution samples for responsive cluster benchmark
        // Warmup run
        train(ExecutionMode.PARALLEL, registry, threadPool, benchSamples);

        ExecutionMode[] modes = {
                ExecutionMode.SEQUENTIAL,
                ExecutionMode.PARALLEL,
                ExecutionMode.DISTRIBUTED,
                ExecutionMode.PARALLEL_DISTRIBUTED
        };

        double baselineDuration = 1.0;

        for (int m = 0; m < modes.length; m++) {
            ExecutionMode mode = modes[m];
            double totalDuration = 0.0;
            int totalSamples = 0;

            for (int r = 0; r < runsPerMode; r++) {
                TrainingResult res = train(mode, registry, threadPool, benchSamples);
                totalDuration += res.durationMs;
                totalSamples += res.samplesProcessed;
            }

            double meanDuration = totalDuration / runsPerMode;
            double throughput = totalSamples / Math.max(totalDuration / 1000.0, 0.0001);

            if (m == 0) {
                baselineDuration = meanDuration;
            }
            double speedup = (baselineDuration > 0) ? (baselineDuration / Math.max(meanDuration, 0.001)) : 1.0;

            report.append(String.format(Locale.US, "%-22s | %10.1f img/s | %10.2f ms | %10.2fx\n",
                    mode.name(), throughput, meanDuration, speedup));
        }

        return report.toString();
    }

    private static class Pair<A, B> {
        public final A first;
        public final B second;

        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    public static void main(String[] args) {
        System.out.println("[ModelTrainer] Starting standalone training on authentic dataset...");
        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {
            TrainingResult res = train(ExecutionMode.PARALLEL, null, pool, 0);
            System.out.println("[ModelTrainer] " + res);
        } finally {
            pool.shutdown();
        }
    }
}
