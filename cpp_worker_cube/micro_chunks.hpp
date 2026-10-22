#ifndef MICRO_CHUNKS_HPP
#define MICRO_CHUNKS_HPP

#include <vector>
#include <string>
#include <cmath>
#include <sstream>
#include <fstream>
#include <algorithm>
#include <iomanip>

#include <omp.h>

namespace OpenAICubes {

constexpr int WIDTH = 28;
constexpr int HEIGHT = 28;
constexpr int TOTAL_PIXELS = WIDTH * HEIGHT; // 784

constexpr int KERNEL_SIZE = 3;
constexpr int NUM_FILTERS = 8;
constexpr int CONV_OUT_DIM = WIDTH - KERNEL_SIZE + 1; // 26
constexpr int POOL_STRIDE = 2;
constexpr int POOL_OUT_DIM = CONV_OUT_DIM / POOL_STRIDE; // 13
constexpr int FEATURE_DIM_PER_FILTER = POOL_OUT_DIM * POOL_OUT_DIM; // 169
constexpr int TOTAL_FEATURE_DIM = NUM_FILTERS * FEATURE_DIM_PER_FILTER; // 1352

// 8 Spatial Feature Kernels (3x3)
const float KERNELS[8][3][3] = {
    // 0: Horizontal Edge
    {
        {-1.0f, -1.0f, -1.0f},
        { 2.0f,  2.0f,  2.0f},
        {-1.0f, -1.0f, -1.0f}
    },
    // 1: Vertical Edge
    {
        {-1.0f,  2.0f, -1.0f},
        {-1.0f,  2.0f, -1.0f},
        {-1.0f,  2.0f, -1.0f}
    },
    // 2: Diagonal (+45 deg)
    {
        {-1.0f, -1.0f,  2.0f},
        {-1.0f,  2.0f, -1.0f},
        { 2.0f, -1.0f, -1.0f}
    },
    // 3: Diagonal (-45 deg)
    {
        { 2.0f, -1.0f, -1.0f},
        {-1.0f,  2.0f, -1.0f},
        {-1.0f, -1.0f,  2.0f}
    },
    // 4: Sharpen / Center-Surround
    {
        { 0.0f, -1.0f,  0.0f},
        {-1.0f,  5.0f, -1.0f},
        { 0.0f, -1.0f,  0.0f}
    },
    // 5: Ridge Horizontal
    {
        { 1.0f,  1.0f,  1.0f},
        {-2.0f, -2.0f, -2.0f},
        { 1.0f,  1.0f,  1.0f}
    },
    // 6: Ridge Vertical
    {
        { 1.0f, -2.0f,  1.0f},
        { 1.0f, -2.0f,  1.0f},
        { 1.0f, -2.0f,  1.0f}
    },
    // 7: Laplacian Texture
    {
        { 1.0f,  1.0f,  1.0f},
        { 1.0f, -8.0f,  1.0f},
        { 1.0f,  1.0f,  1.0f}
    }
};

const std::vector<std::string> CLASSES = {"panda", "cat", "dog", "bear", "lion"};

inline std::vector<float> create_sample(const std::string& class_name) {
    std::vector<float> p(TOTAL_PIXELS, 0.0f);

    auto set_block = [&](int sx, int sy, int w, int h, float val) {
        for (int y = sy; y < sy + h && y < HEIGHT; ++y) {
            for (int x = sx; x < sx + w && x < WIDTH; ++x) {
                p[y * WIDTH + x] = val;
            }
        }
    };

    if (class_name == "panda") {
        set_block(6, 6, 4, 6, 1.0f);
        set_block(18, 6, 4, 6, 1.0f);
        set_block(2, 0, 6, 4, 1.0f);
        set_block(20, 0, 6, 4, 1.0f);
        set_block(12, 16, 4, 4, 1.0f);
    } else if (class_name == "cat") {
        for (int i = 0; i < 4; ++i) {
            p[i * WIDTH + (4 + i)] = 1.0f;
            p[i * WIDTH + (23 - i)] = 1.0f;
        }
        set_block(4, 2, 4, 3, 1.0f);
        set_block(20, 2, 4, 3, 1.0f);
        set_block(2, 12, 8, 2, 1.0f);
        set_block(18, 12, 8, 2, 1.0f);
        set_block(8, 10, 3, 2, 1.0f);
        set_block(17, 10, 3, 2, 1.0f);
    } else if (class_name == "dog") {
        set_block(0, 6, 5, 10, 1.0f);
        set_block(23, 6, 5, 10, 1.0f);
        set_block(10, 14, 8, 6, 1.0f);
        set_block(8, 8, 3, 3, 1.0f);
        set_block(17, 8, 3, 3, 1.0f);
    } else if (class_name == "bear") {
        set_block(4, 0, 6, 6, 1.0f);
        set_block(18, 0, 6, 6, 1.0f);
        set_block(8, 12, 12, 8, 1.0f);
    } else if (class_name == "lion") {
        for (int x = 0; x < WIDTH; ++x) {
            p[0 * WIDTH + x] = 1.0f;
            p[1 * WIDTH + x] = 1.0f;
            p[(HEIGHT - 2) * WIDTH + x] = 1.0f;
            p[(HEIGHT - 1) * WIDTH + x] = 1.0f;
        }
        for (int y = 0; y < HEIGHT; ++y) {
            p[y * WIDTH + 0] = 1.0f;
            p[y * WIDTH + 1] = 1.0f;
            p[y * WIDTH + (WIDTH - 2)] = 1.0f;
            p[y * WIDTH + (WIDTH - 1)] = 1.0f;
        }
        set_block(10, 10, 8, 8, 0.8f);
    } else {
        std::fill(p.begin(), p.end(), 0.5f);
    }
    return p;
}

inline std::vector<float> convolution_chunk(const std::vector<float>& pixels, int thread_count = 4) {
    std::vector<float> features(TOTAL_FEATURE_DIM, 0.0f);
    size_t total_p = pixels.size();
    int side = static_cast<int>(std::round(std::sqrt(total_p)));
    int in_w = (side * side == static_cast<int>(total_p)) ? side : WIDTH;
    int in_h = in_w;

    int conv_w = in_w - KERNEL_SIZE + 1;
    int conv_h = in_h - KERNEL_SIZE + 1;

    #pragma omp parallel for schedule(static) num_threads(thread_count)
    for (int f = 0; f < NUM_FILTERS; ++f) {
        int offset = f * FEATURE_DIM_PER_FILTER;

        for (int py = 0; py < POOL_OUT_DIM; ++py) {
            int start_y = py * conv_h / POOL_OUT_DIM;
            int end_y = (py + 1) * conv_h / POOL_OUT_DIM;

            for (int px = 0; px < POOL_OUT_DIM; ++px) {
                int start_x = px * conv_w / POOL_OUT_DIM;
                int end_x = (px + 1) * conv_w / POOL_OUT_DIM;

                float max_val = 0.0f;
                for (int y = start_y; y < end_y; ++y) {
                    for (int x = start_x; x < end_x; ++x) {
                        float sum = 0.0f;
                        for (int ky = 0; ky < KERNEL_SIZE; ++ky) {
                            for (int kx = 0; kx < KERNEL_SIZE; ++kx) {
                                sum += pixels[(y + ky) * in_w + (x + kx)] * KERNELS[f][ky][kx];
                            }
                        }
                        if (sum > max_val) {
                            max_val = sum;
                        }
                    }
                }
                features[offset + (py * POOL_OUT_DIM + px)] = max_val;
            }
        }
    }
    return features;
}

inline std::vector<std::vector<float>> get_weight_prototypes() {
    static std::vector<std::vector<float>> prototypes;
    if (prototypes.empty()) {
        bool loaded = false;

        // 0. Check standard weights.csv first
        std::vector<std::string> weights_paths = {"dataset/weights.csv", "../dataset/weights.csv"};
        for (const auto& w_path : weights_paths) {
            std::ifstream w_file(w_path);
            if (w_file.is_open()) {
                std::vector<std::vector<float>> loaded_protos(CLASSES.size());
                int found = 0;
                std::string line;
                while (std::getline(w_file, line)) {
                    if (line.empty() || line[0] == '#') continue;
                    std::stringstream ss(line);
                    std::string label;
                    if (!std::getline(ss, label, ',')) continue;
                    int c_idx = -1;
                    for (size_t i = 0; i < CLASSES.size(); ++i) {
                        if (CLASSES[i] == label) { c_idx = static_cast<int>(i); break; }
                    }
                    if (c_idx != -1) {
                        std::vector<float> vals;
                        std::string val_str;
                        while (std::getline(ss, val_str, ',')) {
                            if (!val_str.empty()) vals.push_back(std::stof(val_str));
                        }
                        if (vals.size() >= TOTAL_FEATURE_DIM) {
                            loaded_protos[c_idx] = vals;
                            found++;
                        }
                    }
                }
                if (found == static_cast<int>(CLASSES.size())) {
                    prototypes = loaded_protos;
                    loaded = true;
                    break;
                }
            }
        }

        // 1. Fallback to database.csv
        if (!loaded) {
            std::vector<std::string> paths = {"dataset/database.csv", "../dataset/database.csv"};
            for (const auto& path : paths) {
                std::ifstream file(path);
                if (file.is_open()) {
                std::vector<std::vector<float>> accum(CLASSES.size(), std::vector<float>(TOTAL_FEATURE_DIM, 0.0f));
                std::vector<int> counts(CLASSES.size(), 0);

                std::string line;
                while (std::getline(file, line)) {
                    if (line.empty() || line[0] == '#') continue;
                    std::stringstream ss(line);
                    std::string label;
                    if (!std::getline(ss, label, ',')) continue;

                    int class_idx = -1;
                    for (size_t i = 0; i < CLASSES.size(); ++i) {
                        if (CLASSES[i] == label) {
                            class_idx = static_cast<int>(i);
                            break;
                        }
                    }

                    if (class_idx != -1) {
                        std::vector<float> px;
                        std::string val_str;
                        while (std::getline(ss, val_str, ',')) {
                            if (!val_str.empty()) px.push_back(std::stof(val_str));
                        }
                        if (px.size() >= TOTAL_PIXELS) {
                            auto feats = convolution_chunk(px);
                            for (int i = 0; i < TOTAL_FEATURE_DIM; ++i) {
                                accum[class_idx][i] += feats[i];
                            }
                            counts[class_idx]++;
                        }
                    }
                }

                bool all_found = true;
                for (size_t c = 0; c < CLASSES.size(); ++c) {
                    if (counts[c] == 0) all_found = false;
                }

                if (all_found) {
                    prototypes.resize(CLASSES.size(), std::vector<float>(TOTAL_FEATURE_DIM, 0.0f));
                    for (size_t c = 0; c < CLASSES.size(); ++c) {
                        for (int i = 0; i < TOTAL_FEATURE_DIM; ++i) {
                            prototypes[c][i] = accum[c][i] / counts[c];
                        }
                    }
                    loaded = true;
                    break;
                }
            }
        }
    }

        if (!loaded) {
            for (const auto& c : CLASSES) {
                prototypes.push_back(convolution_chunk(create_sample(c)));
            }
        }
    }
    return prototypes;
}

inline std::tuple<std::string, float, std::vector<float>> mlp_chunk(const std::vector<float>& features) {
    const auto& prototypes = get_weight_prototypes();
    std::vector<float> raw_scores(CLASSES.size(), 0.0f);
    float min_score = 1e9f, max_score = -1e9f;

    for (size_t c = 0; c < CLASSES.size(); ++c) {
        float dot = 0.0f, norm_w = 1e-4f, norm_x = 1e-4f;
        for (int i = 0; i < TOTAL_FEATURE_DIM; ++i) {
            dot += prototypes[c][i] * features[i];
            norm_w += prototypes[c][i] * prototypes[c][i];
            norm_x += features[i] * features[i];
        }
        float cosine = dot / (std::sqrt(norm_w) * std::sqrt(norm_x));
        raw_scores[c] = cosine;
        if (cosine < min_score) min_score = cosine;
        if (cosine > max_score) max_score = cosine;
    }

    float range = std::max(max_score - min_score, 0.001f);
    std::vector<float> logits(CLASSES.size(), 0.0f);
    float max_logit = -1e9f;
    for (size_t c = 0; c < CLASSES.size(); ++c) {
        logits[c] = ((raw_scores[c] - min_score) / range) * 6.0f;
        if (logits[c] > max_logit) max_logit = logits[c];
    }

    float sum_exp = 0.0f;
    std::vector<float> exp_vals(CLASSES.size());
    for (size_t c = 0; c < CLASSES.size(); ++c) {
        exp_vals[c] = std::exp(logits[c] - max_logit);
        sum_exp += exp_vals[c];
    }

    size_t best_idx = 0;
    float best_prob = 0.0f;
    std::vector<float> probs(CLASSES.size());
    for (size_t c = 0; c < CLASSES.size(); ++c) {
        probs[c] = exp_vals[c] / sum_exp;
        if (probs[c] > best_prob) {
            best_prob = probs[c];
            best_idx = c;
        }
    }

    return {CLASSES[best_idx], best_prob, probs};
}

inline std::tuple<std::string, float, std::vector<float>> infer_end_to_end(const std::vector<float>& pixels) {
    return mlp_chunk(convolution_chunk(pixels));
}

} // namespace OpenAICubes

#endif // MICRO_CHUNKS_HPP
