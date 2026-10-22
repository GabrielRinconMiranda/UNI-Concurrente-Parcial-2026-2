package main

import (
	"bufio"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
	"sync"
)

const (
	KernelSize             = 3
	NumFilters             = 8
	PoolOutDim             = 13
	FeatureDimPerFilter    = PoolOutDim * PoolOutDim // 169
	TotalFeatureDim        = NumFilters * FeatureDimPerFilter // 1352
	DefaultWidth           = 28
	DefaultHeight          = 28
)

var Classes = []string{"panda", "cat", "dog", "bear", "lion"}

var Kernels = [NumFilters][KernelSize][KernelSize]float32{
	// Filter 0: Horizontal Edge
	{
		{-1.0, -1.0, -1.0},
		{ 2.0,  2.0,  2.0},
		{-1.0, -1.0, -1.0},
	},
	// Filter 1: Vertical Edge
	{
		{-1.0,  2.0, -1.0},
		{-1.0,  2.0, -1.0},
		{-1.0,  2.0, -1.0},
	},
	// Filter 2: Diagonal Edge 45 deg
	{
		{ 2.0, -1.0, -1.0},
		{-1.0,  2.0, -1.0},
		{-1.0, -1.0,  2.0},
	},
	// Filter 3: Diagonal Edge 135 deg
	{
		{-1.0, -1.0,  2.0},
		{-1.0,  2.0, -1.0},
		{ 2.0, -1.0, -1.0},
	},
	// Filter 4: Emboss
	{
		{-2.0, -1.0,  0.0},
		{-1.0,  1.0,  1.0},
		{ 0.0,  1.0,  2.0},
	},
	// Filter 5: Sharpen
	{
		{ 0.0, -1.0,  0.0},
		{-1.0,  5.0, -1.0},
		{ 0.0, -1.0,  0.0},
	},
	// Filter 6: Vertical Ridge
	{
		{ 1.0, -2.0,  1.0},
		{ 1.0, -2.0,  1.0},
		{ 1.0, -2.0,  1.0},
	},
	// Filter 7: Laplacian Texture
	{
		{ 1.0,  1.0,  1.0},
		{ 1.0, -8.0,  1.0},
		{ 1.0,  1.0,  1.0},
	},
}

// ConvolutionChunk executes 2D spatial convolution, ReLU, and Adaptive Max-Pooling
// parallelized across the 8 filters using goroutines.
func ConvolutionChunk(pixels []float32, threadCount int) []float32 {
	features := make([]float32, TotalFeatureDim)
	totalP := len(pixels)
	side := int(math.Round(math.Sqrt(float64(totalP))))
	inW := DefaultWidth
	if side*side == totalP && totalP >= KernelSize*KernelSize {
		inW = side
	}
	inH := inW
	if len(pixels) < inW*inH {
		padded := make([]float32, inW*inH)
		copy(padded, pixels)
		pixels = padded
	}

	convW := inW - KernelSize + 1
	convH := inH - KernelSize + 1

	workers := threadCount
	if workers > NumFilters {
		workers = NumFilters
	}
	if workers < 1 {
		workers = 1
	}

	var wg sync.WaitGroup
	filterChan := make(chan int, NumFilters)
	for f := 0; f < NumFilters; f++ {
		filterChan <- f
	}
	close(filterChan)

	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for f := range filterChan {
				computeFilter(pixels, f, inW, inH, convW, convH, features)
			}
		}()
	}
	wg.Wait()

	return features
}

func computeFilter(pixels []float32, f, inW, inH, convW, convH int, features []float32) {
	kernel := Kernels[f]
	featureOffset := f * FeatureDimPerFilter

	for py := 0; py < PoolOutDim; py++ {
		startY := py * convH / PoolOutDim
		endY := (py + 1) * convH / PoolOutDim
		if endY <= startY {
			endY = startY + 1
		}

		for px := 0; px < PoolOutDim; px++ {
			startX := px * convW / PoolOutDim
			endX := (px + 1) * convW / PoolOutDim
			if endX <= startX {
				endX = startX + 1
			}

			maxVal := float32(-1e9)
			for cy := startY; cy < endY; cy++ {
				for cx := startX; cx < endX; cx++ {
					sum := float32(0.0)
					for ky := 0; ky < KernelSize; ky++ {
						rowIdx := (cy + ky) * inW
						for kx := 0; kx < KernelSize; kx++ {
							sum += pixels[rowIdx+(cx+kx)] * kernel[ky][kx]
						}
					}
					// ReLU
					if sum < 0 {
						sum = 0
					}
					if sum > maxVal {
						maxVal = sum
					}
				}
			}
			features[featureOffset+py*PoolOutDim+px] = maxVal
		}
	}
}

var (
	weightPrototypes [][]float32
	weightsOnce      sync.Once
)

func getWeightPrototypes() [][]float32 {
	weightsOnce.Do(func() {
		weightPrototypes = loadWeights()
	})
	return weightPrototypes
}

func loadWeights() [][]float32 {
	paths := []string{"dataset/weights.csv", "../dataset/weights.csv", "../../dataset/weights.csv"}
	for _, p := range paths {
		if file, err := os.Open(p); err == nil {
			defer file.Close()
			scanner := bufio.NewScanner(file)
			weights := make([][]float32, len(Classes))
			loaded := 0
			for scanner.Scan() {
				line := strings.TrimSpace(scanner.Text())
				if line == "" || strings.HasPrefix(line, "#") {
					continue
				}
				parts := strings.Split(line, ",")
				if len(parts) >= TotalFeatureDim+1 {
					label := strings.ToLower(strings.TrimSpace(parts[0]))
					cIdx := findClassIndex(label)
					if cIdx != -1 {
						vec := make([]float32, TotalFeatureDim)
						for i := 0; i < TotalFeatureDim; i++ {
							if val, err := strconv.ParseFloat(parts[i+1], 32); err == nil {
								vec[i] = float32(val)
							}
						}
						weights[cIdx] = vec
						loaded++
					}
				}
			}
			if loaded == len(Classes) {
				return weights
			}
		}
	}

	// Fallback to synthetic prototypes
	fallback := make([][]float32, len(Classes))
	for c := range Classes {
		sample := createSample(Classes[c])
		fallback[c] = ConvolutionChunk(sample, 1)
	}
	return fallback
}

func findClassIndex(label string) int {
	for i, c := range Classes {
		if c == label {
			return i
		}
	}
	return -1
}

func createSample(className string) []float32 {
	p := make([]float32, DefaultWidth*DefaultHeight)
	setBlock := func(x, y, w, h int, v float32) {
		for cy := y; cy < y+h && cy < DefaultHeight; cy++ {
			for cx := x; cx < x+w && cx < DefaultWidth; cx++ {
				p[cy*DefaultWidth+cx] = v
			}
		}
	}
	switch className {
	case "panda":
		setBlock(4, 4, 20, 20, 0.9)
		setBlock(2, 2, 6, 6, 0.1)
		setBlock(20, 2, 6, 6, 0.1)
		setBlock(7, 8, 4, 4, 0.1)
		setBlock(17, 8, 4, 4, 0.1)
		setBlock(12, 14, 4, 3, 0.1)
	case "cat":
		setBlock(4, 2, 5, 6, 1.0)
		setBlock(19, 2, 5, 6, 1.0)
		setBlock(6, 8, 16, 14, 0.8)
		setBlock(9, 11, 3, 3, 0.2)
		setBlock(16, 11, 3, 3, 0.2)
	case "dog":
		setBlock(0, 6, 5, 10, 1.0)
		setBlock(23, 6, 5, 10, 1.0)
		setBlock(10, 14, 8, 6, 1.0)
		setBlock(8, 8, 3, 3, 1.0)
		setBlock(17, 8, 3, 3, 1.0)
	case "bear":
		setBlock(4, 0, 6, 6, 1.0)
		setBlock(18, 0, 6, 6, 1.0)
		setBlock(8, 12, 12, 8, 1.0)
	case "lion":
		for x := 0; x < DefaultWidth; x++ {
			p[0*DefaultWidth+x] = 1.0
			p[1*DefaultWidth+x] = 1.0
			p[(DefaultHeight-2)*DefaultWidth+x] = 1.0
			p[(DefaultHeight-1)*DefaultWidth+x] = 1.0
		}
		for y := 0; y < DefaultHeight; y++ {
			p[y*DefaultWidth+0] = 1.0
			p[y*DefaultWidth+1] = 1.0
			p[y*DefaultWidth+(DefaultWidth-2)] = 1.0
			p[y*DefaultWidth+(DefaultWidth-1)] = 1.0
		}
		setBlock(10, 10, 8, 8, 0.8)
	default:
		for i := range p {
			p[i] = 0.5
		}
	}
	return p
}

type PredictionResult struct {
	Label         string
	Confidence    float32
	Probabilities []float32
}

func MlpChunk(features []float32) PredictionResult {
	prototypes := getWeightPrototypes()
	numClasses := len(Classes)
	rawScores := make([]float32, numClasses)
	minScore := float32(1e9)
	maxScore := float32(-1e9)

	for c := 0; c < numClasses; c++ {
		dot := float32(0.0)
		normW := float32(1e-4)
		normX := float32(1e-4)

		for i := 0; i < TotalFeatureDim; i++ {
			dot += prototypes[c][i] * features[i]
			normW += prototypes[c][i] * prototypes[c][i]
			normX += features[i] * features[i]
		}
		cosine := dot / (float32(math.Sqrt(float64(normW))) * float32(math.Sqrt(float64(normX))))
		rawScores[c] = cosine
		if cosine < minScore {
			minScore = cosine
		}
		if cosine > maxScore {
			maxScore = cosine
		}
	}

	diff := maxScore - minScore
	if diff < 0.001 {
		diff = 0.001
	}

	logits := make([]float32, numClasses)
	maxLogit := float32(-1e9)
	for c := 0; c < numClasses; c++ {
		logits[c] = ((rawScores[c] - minScore) / diff) * 6.0
		if logits[c] > maxLogit {
			maxLogit = logits[c]
		}
	}

	sumExp := float32(0.0)
	expVals := make([]float32, numClasses)
	for c := 0; c < numClasses; c++ {
		expVals[c] = float32(math.Exp(float64(logits[c] - maxLogit)))
		sumExp += expVals[c]
	}

	probs := make([]float32, numClasses)
	bestIdx := 0
	bestProb := float32(0.0)
	for c := 0; c < numClasses; c++ {
		probs[c] = expVals[c] / sumExp
		if probs[c] > bestProb {
			bestProb = probs[c]
			bestIdx = c
		}
	}

	return PredictionResult{
		Label:         Classes[bestIdx],
		Confidence:    bestProb,
		Probabilities: probs,
	}
}

func FeaturesToString(features []float32) string {
	var sb strings.Builder
	for i, f := range features {
		if i > 0 {
			sb.WriteString(",")
		}
		sb.WriteString(fmt.Sprintf("%.4f", f))
	}
	return sb.String()
}

func FeaturesFromString(s string) []float32 {
	parts := strings.Split(s, ",")
	res := make([]float32, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			if v, err := strconv.ParseFloat(p, 32); err == nil {
				res = append(res, float32(v))
			}
		}
	}
	return res
}

