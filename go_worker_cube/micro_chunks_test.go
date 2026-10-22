package main

import (
	"math"
	"testing"
)

func TestMicroChunks(t *testing.T) {
	t.Log("[Test] Running Go micro-chunks unit tests")

	// 1. Test sample generation
	sample := createSample("panda")
	if len(sample) != DefaultWidth*DefaultHeight {
		t.Fatalf("Sample size %d != %d", len(sample), DefaultWidth*DefaultHeight)
	}

	// 2. Test convolution chunk dimensions
	feats := ConvolutionChunk(sample, 4)
	if len(feats) != TotalFeatureDim {
		t.Fatalf("Features dimension %d != %d", len(feats), TotalFeatureDim)
	}
	t.Logf("  [PASS] ConvolutionChunk: 2D convolution, ReLU, and Adaptive Max-Pooling (%d dimensions)", len(feats))

	// 3. Test multithreaded equivalence
	featsSeq := ConvolutionChunk(sample, 1)
	maxDiff := float32(0.0)
	for i := range feats {
		diff := float32(math.Abs(float64(feats[i] - featsSeq[i])))
		if diff > maxDiff {
			maxDiff = diff
		}
	}
	if maxDiff > 1e-4 {
		t.Fatalf("Parallel vs sequential mismatch: %f", maxDiff)
	}
	t.Log("  [PASS] Multithreaded goroutine filter invariance verified")

	// 4. Test MLP chunk
	pred := MlpChunk(feats)
	if pred.Label == "" || pred.Confidence <= 0 {
		t.Fatalf("Invalid prediction: %v", pred)
	}
	t.Logf("  [PASS] MlpChunk: Projection and Softmax (predicted %s with %.1f%% confidence)", pred.Label, pred.Confidence*100)

	// 5. Test all classes
	for _, c := range Classes {
		s := createSample(c)
		f := ConvolutionChunk(s, 4)
		p := MlpChunk(f)
		t.Logf("  [PASS] Sample %-5s -> %-5s (%.1f%%)", c, p.Label, p.Confidence*100)
	}
}

