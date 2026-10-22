"""
Micro-Chunks Implementation in Python Standard Library.
28x28 Image Dimension, 8 Spatial Convolution Filters (3x3), ReLU,
2x2 Max Pooling (1352 Feature Vector), and MLP Dense Classification with Softmax.
"""

import math
import os

WIDTH = 28
HEIGHT = 28
TOTAL_PIXELS = WIDTH * HEIGHT

KERNEL_SIZE = 3
NUM_FILTERS = 8
CONV_OUT_DIM = WIDTH - KERNEL_SIZE + 1  # 26
POOL_STRIDE = 2
POOL_OUT_DIM = CONV_OUT_DIM // POOL_STRIDE  # 13
FEATURE_DIM_PER_FILTER = POOL_OUT_DIM * POOL_OUT_DIM  # 169
TOTAL_FEATURE_DIM = NUM_FILTERS * FEATURE_DIM_PER_FILTER  # 1352

# 8 Spatial Feature Kernels (3x3)
KERNELS = [
    # Filter 0: Horizontal Edge
    [
        [-1.0, -1.0, -1.0],
        [ 2.0,  2.0,  2.0],
        [-1.0, -1.0, -1.0]
    ],
    # Filter 1: Vertical Edge
    [
        [-1.0,  2.0, -1.0],
        [-1.0,  2.0, -1.0],
        [-1.0,  2.0, -1.0]
    ],
    # Filter 2: Diagonal (+45 deg)
    [
        [-1.0, -1.0,  2.0],
        [-1.0,  2.0, -1.0],
        [ 2.0, -1.0, -1.0]
    ],
    # Filter 3: Diagonal (-45 deg)
    [
        [ 2.0, -1.0, -1.0],
        [-1.0,  2.0, -1.0],
        [-1.0, -1.0,  2.0]
    ],
    # Filter 4: Sharpen / Center-Surround
    [
        [ 0.0, -1.0,  0.0],
        [-1.0,  5.0, -1.0],
        [ 0.0, -1.0,  0.0]
    ],
    # Filter 5: Ridge Horizontal
    [
        [ 1.0,  1.0,  1.0],
        [-2.0, -2.0, -2.0],
        [ 1.0,  1.0,  1.0]
    ],
    # Filter 6: Ridge Vertical
    [
        [ 1.0, -2.0,  1.0],
        [ 1.0, -2.0,  1.0],
        [ 1.0, -2.0,  1.0]
    ],
    # Filter 7: Laplacian Texture
    [
        [ 1.0,  1.0,  1.0],
        [ 1.0, -8.0,  1.0],
        [ 1.0,  1.0,  1.0]
    ]
]

CLASSES = ["panda", "cat", "dog", "bear", "lion"]


def create_sample(class_name: str) -> list[float]:
    """Generates canonical 28x28 grayscale image matrix for a class."""
    p = [0.0] * TOTAL_PIXELS
    name = (class_name or "").lower().strip()

    def set_block(start_x, start_y, w, h, val):
        for y in range(start_y, min(start_y + h, HEIGHT)):
            for x in range(start_x, min(start_x + w, WIDTH)):
                p[y * WIDTH + x] = val

    if name == "panda":
        set_block(6, 6, 4, 6, 1.0)
        set_block(18, 6, 4, 6, 1.0)
        set_block(2, 0, 6, 4, 1.0)
        set_block(20, 0, 6, 4, 1.0)
        set_block(12, 16, 4, 4, 1.0)
    elif name == "cat":
        for i in range(4):
            p[i * WIDTH + (4 + i)] = 1.0
            p[i * WIDTH + (23 - i)] = 1.0
        set_block(4, 2, 4, 3, 1.0)
        set_block(20, 2, 4, 3, 1.0)
        set_block(2, 12, 8, 2, 1.0)
        set_block(18, 12, 8, 2, 1.0)
        set_block(8, 10, 3, 2, 1.0)
        set_block(17, 10, 3, 2, 1.0)
    elif name == "dog":
        set_block(0, 6, 5, 10, 1.0)
        set_block(23, 6, 5, 10, 1.0)
        set_block(10, 14, 8, 6, 1.0)
        set_block(8, 8, 3, 3, 1.0)
        set_block(17, 8, 3, 3, 1.0)
    elif name == "bear":
        set_block(4, 0, 6, 6, 1.0)
        set_block(18, 0, 6, 6, 1.0)
        set_block(8, 12, 12, 8, 1.0)
    elif name == "lion":
        for x in range(WIDTH):
            p[0 * WIDTH + x] = 1.0
            p[1 * WIDTH + x] = 1.0
            p[(HEIGHT - 2) * WIDTH + x] = 1.0
            p[(HEIGHT - 1) * WIDTH + x] = 1.0
        for y in range(HEIGHT):
            p[y * WIDTH + 0] = 1.0
            p[y * WIDTH + 1] = 1.0
            p[y * WIDTH + (WIDTH - 2)] = 1.0
            p[y * WIDTH + (WIDTH - 1)] = 1.0
        set_block(10, 10, 8, 8, 0.8)
    else:
        p = [0.5] * TOTAL_PIXELS

    return p


from concurrent.futures import ThreadPoolExecutor

def convolution_chunk(pixels: list[float], thread_count: int = 4) -> list[float]:
    """Applies 2D convolution with 8 filters, ReLU activation, and adaptive max-pooling (1352 features)."""
    features = [0.0] * TOTAL_FEATURE_DIM
    total_p = len(pixels)
    side = int(round(math.sqrt(total_p)))
    in_w = side if (side * side == total_p and total_p >= KERNEL_SIZE * KERNEL_SIZE) else WIDTH
    in_h = in_w
    if len(pixels) < in_w * in_h:
        pixels = list(pixels) + [0.0] * (in_w * in_h - len(pixels))

    conv_w = in_w - KERNEL_SIZE + 1
    conv_h = in_h - KERNEL_SIZE + 1
    step = 2 if conv_w > 50 else 1

    def compute_filter(f_idx, kernel):
        offset = f_idx * FEATURE_DIM_PER_FILTER
        k00, k01, k02 = kernel[0]
        k10, k11, k12 = kernel[1]
        k20, k21, k22 = kernel[2]

        for py in range(POOL_OUT_DIM):
            sy = py * conv_h // POOL_OUT_DIM
            ey = (py + 1) * conv_h // POOL_OUT_DIM
            for px in range(POOL_OUT_DIM):
                sx = px * conv_w // POOL_OUT_DIM
                ex = (px + 1) * conv_w // POOL_OUT_DIM
                max_v = 0.0

                for y in range(sy, ey, step):
                    r0 = y * in_w
                    r1 = (y + 1) * in_w
                    r2 = (y + 2) * in_w
                    for x in range(sx, ex, step):
                        acc = (pixels[r0 + x] * k00 + pixels[r0 + x + 1] * k01 + pixels[r0 + x + 2] * k02 +
                               pixels[r1 + x] * k10 + pixels[r1 + x + 1] * k11 + pixels[r1 + x + 2] * k12 +
                               pixels[r2 + x] * k20 + pixels[r2 + x + 1] * k21 + pixels[r2 + x + 2] * k22)
                        if acc > max_v:
                            max_v = acc
                features[offset + (py * POOL_OUT_DIM + px)] = max_v

    if thread_count > 1:
        with ThreadPoolExecutor(max_workers=min(thread_count, NUM_FILTERS)) as executor:
            list(executor.map(lambda item: compute_filter(item[0], item[1]), enumerate(KERNELS)))
    else:
        for f_idx, kernel in enumerate(KERNELS):
            compute_filter(f_idx, kernel)

    return features


def _load_weight_prototypes() -> list[list[float]]:
    # 0. Check standard trained weights file first
    weights_paths = ["dataset/weights.csv", "../dataset/weights.csv"]
    for path in weights_paths:
        if os.path.exists(path):
            try:
                loaded = {}
                with open(path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#"):
                            continue
                        parts = line.split(",")
                        if len(parts) >= TOTAL_FEATURE_DIM + 1:
                            label = parts[0].strip().lower()
                            if label in CLASSES:
                                loaded[label] = [float(x) for x in parts[1:TOTAL_FEATURE_DIM + 1]]
                if all(c in loaded for c in CLASSES):
                    return [loaded[c] for c in CLASSES]
            except Exception:
                pass

    # 1. Fallback to dataset/database.csv
    db_paths = ["dataset/database.csv", "../dataset/database.csv"]
    for path in db_paths:
        if os.path.exists(path):
            try:
                class_accum = {c: [0.0] * TOTAL_FEATURE_DIM for c in CLASSES}
                class_counts = {c: 0 for c in CLASSES}

                with open(path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#"):
                            continue
                        parts = line.split(",")
                        if len(parts) >= TOTAL_PIXELS + 1:
                            label = parts[0].strip().lower()
                            if label in class_accum:
                                pixels = [float(x) for x in parts[1:TOTAL_PIXELS + 1]]
                                feats = convolution_chunk(pixels)
                                for i in range(TOTAL_FEATURE_DIM):
                                    class_accum[label][i] += feats[i]
                                class_counts[label] += 1

                if all(class_counts[c] > 0 for c in CLASSES):
                    prototypes = []
                    for c in CLASSES:
                        count = class_counts[c]
                        avg_feats = [class_accum[c][i] / count for i in range(TOTAL_FEATURE_DIM)]
                        prototypes.append(avg_feats)
                    return prototypes
            except Exception:
                pass

    return [convolution_chunk(create_sample(c)) for c in CLASSES]


WEIGHT_PROTOTYPES = _load_weight_prototypes()


def mlp_chunk(features: list[float]) -> tuple[str, float, list[float]]:
    """Dense classification layer with Softmax probability normalization."""
    raw_scores = []
    for c_idx in range(len(CLASSES)):
        w = WEIGHT_PROTOTYPES[c_idx]
        dot = sum(w[i] * features[i] for i in range(TOTAL_FEATURE_DIM))
        norm_w = sum(w[i] * w[i] for i in range(TOTAL_FEATURE_DIM)) + 1e-4
        norm_x = sum(features[i] * features[i] for i in range(TOTAL_FEATURE_DIM)) + 1e-4
        cosine = dot / (math.sqrt(norm_w) * math.sqrt(norm_x))
        raw_scores.append(cosine)

    min_s = min(raw_scores)
    max_s = max(raw_scores)
    rng = max(max_s - min_s, 0.001)
    logits = [((s - min_s) / rng) * 6.0 for s in raw_scores]

    max_l = max(logits)
    exp_vals = [math.exp(l - max_l) for l in logits]
    sum_exp = sum(exp_vals)
    probs = [v / sum_exp for v in exp_vals]

    best_idx = max(range(len(CLASSES)), key=lambda i: probs[i])
    return CLASSES[best_idx], probs[best_idx], probs


def infer_end_to_end(pixels: list[float]) -> tuple[str, float, list[float]]:
    feats = convolution_chunk(pixels)
    return mlp_chunk(feats)
