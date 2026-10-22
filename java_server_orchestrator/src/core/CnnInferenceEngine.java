package core;

/**
 * CnnInferenceEngine: High-level Image Processing Module pipeline.
 * Orchestrates local sequential or thread-based execution of
 * ConvolutionChunk and MlpChunk.
 */
public class CnnInferenceEngine {

    /**
     * Executes end-to-end CNN inference on an image matrix.
     *
     * @param image Input image matrix
     * @return Prediction with class label and confidence
     */
    public static MlpChunk.Prediction infer(ImageMatrix image) {
        float[] features = ConvolutionChunk.process(image);
        return MlpChunk.process(features);
    }
}

