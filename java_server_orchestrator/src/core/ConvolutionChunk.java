package core;

import java.util.Locale;

/**
 * ConvolutionChunk: Micro-Chunk responsible for feature extraction.
 * Processes 28x28 images using 8 spatial filters (3x3), ReLU non-linear activation,
 * and 2x2 max-pooling dimensionality reduction, yielding a 1352-element feature vector.
 */
public class ConvolutionChunk {
    public static final int KERNEL_SIZE = 3;
    public static final int NUM_FILTERS = 8;
    public static final int CONV_OUT_DIM = ImageMatrix.WIDTH - KERNEL_SIZE + 1; // 28 - 3 + 1 = 26
    public static final int POOL_STRIDE = 2;
    public static final int POOL_OUT_DIM = CONV_OUT_DIM / POOL_STRIDE; // 26 / 2 = 13
    public static final int FEATURE_DIM_PER_FILTER = POOL_OUT_DIM * POOL_OUT_DIM; // 13 * 13 = 169
    public static final int TOTAL_FEATURE_DIM = NUM_FILTERS * FEATURE_DIM_PER_FILTER; // 8 * 169 = 1352

    // 8 Specialized spatial feature filters (3x3)
    public static final float[][][] KERNELS = {
        // Filter 0: Horizontal Edge
        {
            {-1.0f, -1.0f, -1.0f},
            { 2.0f,  2.0f,  2.0f},
            {-1.0f, -1.0f, -1.0f}
        },
        // Filter 1: Vertical Edge
        {
            {-1.0f,  2.0f, -1.0f},
            {-1.0f,  2.0f, -1.0f},
            {-1.0f,  2.0f, -1.0f}
        },
        // Filter 2: Diagonal (+45 deg)
        {
            {-1.0f, -1.0f,  2.0f},
            {-1.0f,  2.0f, -1.0f},
            { 2.0f, -1.0f, -1.0f}
        },
        // Filter 3: Diagonal (-45 deg)
        {
            { 2.0f, -1.0f, -1.0f},
            {-1.0f,  2.0f, -1.0f},
            {-1.0f, -1.0f,  2.0f}
        },
        // Filter 4: Sharpen / Center-Surround
        {
            { 0.0f, -1.0f,  0.0f},
            {-1.0f,  5.0f, -1.0f},
            { 0.0f, -1.0f,  0.0f}
        },
        // Filter 5: Ridge Horizontal
        {
            { 1.0f,  1.0f,  1.0f},
            {-2.0f, -2.0f, -2.0f},
            { 1.0f,  1.0f,  1.0f}
        },
        // Filter 6: Ridge Vertical
        {
            { 1.0f, -2.0f,  1.0f},
            { 1.0f, -2.0f,  1.0f},
            { 1.0f, -2.0f,  1.0f}
        },
        // Filter 7: Laplacian Texture
        {
            { 1.0f,  1.0f,  1.0f},
            { 1.0f, -8.0f,  1.0f},
            { 1.0f,  1.0f,  1.0f}
        }
    };

    /**
     * Executes 2D spatial convolution, ReLU activation, and adaptive max-pooling.
     * Yields a consistent 1352-element feature vector for both 28x28 and 720x720 images.
     *
     * @param input Input image matrix (28x28, 720x720, or arbitrary dimension)
     * @return 1352-element feature vector
     */
    public static float[] process(ImageMatrix input) {
        return process(input, 1);
    }

    /**
     * Executes 2D spatial convolution, ReLU activation, and adaptive max-pooling,
     * parallelizing across the 8 spatial filters using the specified thread count.
     */
    public static float[] process(ImageMatrix input, int threadCount) {
        float[] features = new float[TOTAL_FEATURE_DIM];
        int inW = input.getWidth();
        int inH = input.getHeight();
        int convW = inW - KERNEL_SIZE + 1;
        int convH = inH - KERNEL_SIZE + 1;

        if (threadCount <= 1) {
            for (int f = 0; f < NUM_FILTERS; f++) {
                computeFilter(input, f, inW, convW, convH, features);
            }
        } else {
            int workers = Math.min(threadCount, NUM_FILTERS);
            Thread[] pool = new Thread[workers];
            for (int t = 0; t < workers; t++) {
                final int threadId = t;
                pool[t] = new Thread(() -> {
                    for (int f = threadId; f < NUM_FILTERS; f += workers) {
                        computeFilter(input, f, inW, convW, convH, features);
                    }
                }, "ConvThread-" + threadId);
                pool[t].start();
            }
            for (Thread th : pool) {
                try {
                    th.join();
                } catch (InterruptedException ignored) {}
            }
        }

        return features;
    }

    private static void computeFilter(ImageMatrix input, int f, int inW, int convW, int convH, float[] features) {
        float[][] kernel = KERNELS[f];
        int featureOffset = f * FEATURE_DIM_PER_FILTER;

        for (int py = 0; py < POOL_OUT_DIM; py++) {
            int startY = py * convH / POOL_OUT_DIM;
            int endY = (py + 1) * convH / POOL_OUT_DIM;

            for (int px = 0; px < POOL_OUT_DIM; px++) {
                int startX = px * convW / POOL_OUT_DIM;
                int endX = (px + 1) * convW / POOL_OUT_DIM;

                float maxVal = 0.0f;
                for (int y = startY; y < endY; y++) {
                    for (int x = startX; x < endX; x++) {
                        float sum = 0.0f;
                        for (int ky = 0; ky < KERNEL_SIZE; ky++) {
                            for (int kx = 0; kx < KERNEL_SIZE; kx++) {
                                sum += input.get(x + kx, y + ky) * kernel[ky][kx];
                            }
                        }
                        if (sum > maxVal) {
                            maxVal = sum;
                        }
                    }
                }
                features[featureOffset + (py * POOL_OUT_DIM + px)] = maxVal;
            }
        }
    }

    /**
     * Serializes feature vector to comma-separated string.
     */
    public static String featuresToString(float[] features) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < features.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.4f", features[i]));
        }
        return sb.toString();
    }

    /**
     * Parses feature vector from comma-separated string.
     */
    public static float[] featuresFromString(String csv) {
        String[] tokens = csv.split(",");
        if (tokens.length != TOTAL_FEATURE_DIM) {
            throw new IllegalArgumentException("Invalid feature vector dimension: " + tokens.length + ", expected " + TOTAL_FEATURE_DIM);
        }
        float[] features = new float[TOTAL_FEATURE_DIM];
        for (int i = 0; i < TOTAL_FEATURE_DIM; i++) {
            features[i] = Float.parseFloat(tokens[i].trim());
        }
        return features;
    }
}
