"""
Unit tests for Python Micro-Chunks.
Validates convolution, MLP, and end-to-end inference against known classes.
"""

from micro_chunks import create_sample, convolution_chunk, mlp_chunk, infer_end_to_end, CLASSES


def test_micro_chunks():
    print("[Test] Running Python micro-chunks unit tests")

    for c in CLASSES:
        pixels = create_sample(c)
        assert len(pixels) == 784, f"Expected 784 pixels, got {len(pixels)}"

        features = convolution_chunk(pixels)
        assert len(features) == 1352, f"Expected 1352 features, got {len(features)}"

        label, conf, probs = mlp_chunk(features)
        assert label in CLASSES, f"Invalid label: {label}"
        assert 0.0 < conf <= 1.0, f"Invalid confidence: {conf}"
        assert abs(sum(probs) - 1.0) < 0.01, f"Probabilities do not sum to 1: {sum(probs)}"

        label_e2e, conf_e2e, _ = infer_end_to_end(pixels)
        assert label_e2e == label, f"End-to-end mismatch: expected {label}, got {label_e2e}"

        # Test 720x720 high-resolution convolution
        hi_res_p = [0.2] * (720 * 720)
        hi_res_f = convolution_chunk(hi_res_p)
        assert len(hi_res_f) == 1352, f"Expected 1352 high-res features, got {len(hi_res_f)}"

        print(f"  [PASS] Sample {c:5s} -> {label} ({conf * 100:.1f}%)")

    print("[Test] All Python unit tests passed")


if __name__ == "__main__":
    test_micro_chunks()

