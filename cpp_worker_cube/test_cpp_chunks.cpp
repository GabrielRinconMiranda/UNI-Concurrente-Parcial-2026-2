#include "micro_chunks.hpp"
#include <iostream>
#include <cassert>

int main() {
    std::cout << "[Test] Running C++ micro-chunks unit tests\n";

    for (const auto& c : OpenAICubes::CLASSES) {
        auto pixels = OpenAICubes::create_sample(c);
        assert(pixels.size() == 784);

        auto features = OpenAICubes::convolution_chunk(pixels);
        assert(features.size() == 1352);

        auto [label, conf, probs] = OpenAICubes::mlp_chunk(features);
        assert(!label.empty());
        assert(conf > 0.0f && conf <= 1.0f);
        float p_sum = 0.0f;
        for (float p : probs) p_sum += p;
        assert(std::abs(p_sum - 1.0f) < 0.01f);

        auto [e2e_label, e2e_conf, e2e_probs] = OpenAICubes::infer_end_to_end(pixels);
        assert(e2e_label == label);

        // Test 720x720 high-resolution convolution & adaptive pooling
        std::vector<float> hi_res(720 * 720, 0.2f);
        auto hi_res_feats = OpenAICubes::convolution_chunk(hi_res);
        assert(hi_res_feats.size() == 1352);

        std::cout << "  [PASS] Sample " << c << " -> " << label 
                  << " (" << std::fixed << std::setprecision(1) << (conf * 100.0f) << "%)\n";
    }

    std::cout << "[Test] All C++ unit tests passed\n";
    return 0;
}

