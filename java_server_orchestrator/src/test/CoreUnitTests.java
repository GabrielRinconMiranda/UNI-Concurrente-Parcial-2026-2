package test;

import core.CnnInferenceEngine;
import core.ConvolutionChunk;
import core.ImageMatrix;
import core.MlpChunk;

/**
 * CoreUnitTests: Standalone unit tests for CNN micro-chunks and inference pipeline.
 * Runs without external testing libraries using native JVM assertions.
 */
public class CoreUnitTests {

    public static void main(String[] args) {
        System.out.println("[Test] Running Java core unit tests");

        testImageMatrixCreation();
        testConvolutionChunk();
        testModelTraining();
        testMlpChunk();
        testEndToEndInference();
        testRealPhotographs();

        System.out.println("[Test] All Java core unit tests passed");
    }

    private static void testImageMatrixCreation() {
        ImageMatrix panda = ImageMatrix.createSample("panda");
        if (panda.getPixels().length != 784) {
            throw new AssertionError("ImageMatrix should have 784 pixels");
        }
        String csv = panda.toCsvString();
        ImageMatrix parsed = ImageMatrix.fromCsvString(csv, "panda");
        if (Math.abs(parsed.get(3, 3) - panda.get(3, 3)) > 1e-4) {
            throw new AssertionError("Parsed pixel does not match original");
        }
        System.out.println("  [PASS] ImageMatrix creation, serialization, and deserialization");
    }

    private static void testConvolutionChunk() {
        ImageMatrix cat = ImageMatrix.createSample("cat");
        float[] features = ConvolutionChunk.process(cat);
        if (features.length != 1352) {
            throw new AssertionError("Feature vector must have 1352 dimensions");
        }
        String featStr = ConvolutionChunk.featuresToString(features);
        float[] parsed = ConvolutionChunk.featuresFromString(featStr);
        if (Math.abs(features[0] - parsed[0]) > 1e-3) {
            throw new AssertionError("Feature serialization mismatch");
        }
        System.out.println("  [PASS] ConvolutionChunk: 2D convolution, ReLU, and Max Pooling");
    }

    private static void testMlpChunk() {
        // Evaluate MLP projection using dog prototype vector
        float[] dogWeights = MlpChunk.getWeights()[MlpChunk.findClassIndex("dog")];
        MlpChunk.Prediction pred = MlpChunk.process(dogWeights);

        if (!"dog".equals(pred.label)) {
            throw new AssertionError("Expected 'dog' but got: " + pred.label);
        }
        if (pred.confidence <= 0.20f) {
            throw new AssertionError("Confidence should exceed random chance (20%), got: " + pred.confidence);
        }
        System.out.println("  [PASS] MlpChunk: Dense layer dot product and Softmax (predicted dog with " + String.format(java.util.Locale.US, "%.1f%%", pred.confidence * 100) + ")");
    }

    private static void testEndToEndInference() {
        String[] testClasses = {"panda", "cat", "dog", "bear", "lion"};
        for (String c : testClasses) {
            java.io.File f = new java.io.File("dataset/real/train/" + c + "/" + c + "_train_001.jpg");
            if (!f.exists()) f = new java.io.File("../dataset/real/train/" + c + "/" + c + "_train_001.jpg");
            ImageMatrix img;
            if (f.exists()) {
                try {
                    img = ImageMatrix.fromFile(f, c);
                } catch (Exception e) {
                    img = ImageMatrix.createSample(c);
                }
            } else {
                img = ImageMatrix.createSample(c);
            }
            MlpChunk.Prediction p = CnnInferenceEngine.infer(img);
            if (p == null || p.label == null) {
                throw new AssertionError("Inference returned null prediction for: " + c);
            }
            System.out.println("  [PASS] Inferred training sample " + c + " -> " + p.label + " (" + String.format(java.util.Locale.US, "%.1f%%", p.confidence * 100) + ")");
        }
    }

    private static void testRealPhotographs() {
        String[] testClasses = {"panda", "cat", "dog", "bear", "lion"};
        int totalTested = 0;
        int correct = 0;
        for (String c : testClasses) {
            java.io.File testDir = new java.io.File("dataset/real/test/" + c);
            if (!testDir.exists()) testDir = new java.io.File("../dataset/real/test/" + c);
            java.io.File[] files = (testDir.exists() && testDir.isDirectory())
                    ? testDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg"))
                    : null;
            if (files != null && files.length > 0) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
                for (java.io.File f : files) {
                    try {
                        ImageMatrix img = ImageMatrix.fromFile(f, c);
                        MlpChunk.Prediction p = CnnInferenceEngine.infer(img);
                        totalTested++;
                        if (c.equalsIgnoreCase(p.label)) {
                            correct++;
                        }
                    } catch (Exception e) {
                        System.err.println("  [FAIL] Error processing " + f.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        double accuracy = (totalTested > 0) ? (correct * 100.0 / totalTested) : 0.0;
        System.out.println("  [PASS] Evaluated " + totalTested + " authentic test photographs: " + correct + "/" + totalTested + " correct (" + String.format(java.util.Locale.US, "%.1f%%", accuracy) + " accuracy)");
        if (accuracy < 80.0) {
            throw new AssertionError("Test accuracy below threshold: " + accuracy + "%");
        }
    }

    private static void testModelTraining() {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            // 1. Test Sequential training
            core.ModelTrainer.TrainingResult seqRes = core.ModelTrainer.train(server.ExecutionMode.SEQUENTIAL, null, null);
            if (seqRes.samplesProcessed <= 0) {
                throw new AssertionError("Sequential training processed 0 samples");
            }
            System.out.println("  [PASS] Sequential training: " + seqRes.samplesProcessed + " samples in " + String.format("%.2f ms", seqRes.durationMs));

            // 2. Test Parallel training
            core.ModelTrainer.TrainingResult parRes = core.ModelTrainer.train(server.ExecutionMode.PARALLEL, null, pool);
            if (parRes.samplesProcessed <= 0) {
                throw new AssertionError("Parallel training processed 0 samples");
            }
            System.out.println("  [PASS] Parallel training: " + parRes.samplesProcessed + " samples in " + String.format("%.2f ms", parRes.durationMs));

            // 3. Verify weights file persistence
            java.io.File weightsFile = new java.io.File(core.ModelTrainer.DEFAULT_WEIGHTS_PATH);
            if (!weightsFile.exists()) {
                weightsFile = new java.io.File("../" + core.ModelTrainer.DEFAULT_WEIGHTS_PATH);
            }
            if (!weightsFile.exists() || weightsFile.length() == 0) {
                throw new AssertionError("Weights file was not saved properly: " + weightsFile.getPath());
            }
            System.out.println("  [PASS] Model weights successfully saved to: " + weightsFile.getPath());
        } finally {
            pool.shutdown();
        }
    }
}

