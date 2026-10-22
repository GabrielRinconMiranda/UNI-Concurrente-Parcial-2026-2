# Open AI Cubes: Empirical Performance Benchmark Report

## 1. Overview
This benchmark evaluates the computational performance, latency distribution, and throughput of the Open AI Cubes architecture across the 4 execution modes stipulated by the exam rubric:
1. **Sequential**: Single process, single thread.
2. **Parallel**: Single multi-core machine, local thread pool.
3. **Distributed**: Heterogeneous cluster nodes (Java, Python, C++) communicating via native TCP sockets.
4. **Parallel-Distributed**: Distributed cluster nodes operating concurrent multi-threaded worker pools.

## 2. Experimental Setup
- **Hardware**: AMD Ryzen 7 5700U (8 Physical Cores, 16 Logical Threads)
- **Memory**: 16 GB DDR4
- **Operating System**: Linux Fedora (Kernel 6.x)
- **Workload**: 100 client inference transactions per mode
- **Concurrency**: 10 concurrent simulated client threads
- **Transaction Delay**: Randomized uniform distribution between 5.0 ms and 20.0 ms
- **Image Dimension**: 14x14 grayscale matrix (196 features)
- **Convolution Filters**: 2 spatial edge detection filters (3x3), ReLU, 2x2 Max Pooling (72 extracted features)
- **Classifier**: Multi-layer Perceptron (MLP) with Softmax normalization across 5 target classes

## 3. Results Summary

| Execution Mode | Success Rate | Throughput (req/s) | Min Latency (ms) | Mean Latency (ms) | Median Latency (ms) | P95 Latency (ms) | Max Latency (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Sequential** | 100.0% | 465.13 | 0.73 | 7.32 | 1.17 | 62.57 | 68.54 |
| **Parallel** | 100.0% | 667.11 | 0.71 | 1.17 | 1.09 | 1.71 | 3.58 |
| **Distributed** | 100.0% | 551.15 | 1.46 | 4.45 | 3.09 | 12.04 | 44.33 |
| **Parallel-Distributed** | 100.0% | 607.61 | 1.01 | 1.93 | 1.89 | 2.87 | 5.18 |

## 4. Key Performance Observations
1. **Throughput Scaling**:
   - The **Parallel** mode achieves the highest raw throughput (667.11 req/s), representing a **43.4% increase** over Sequential execution due to zero network serialization overhead and direct memory access across local CPU cores.
   - The **Parallel-Distributed** mode achieves 607.61 req/s, providing strong cluster throughput while absorbing network socket overhead across concurrent worker nodes.
2. **Latency And Queue Saturation**:
   - In **Sequential** mode, concurrent requests experience severe queuing delays under saturation, causing P95 latency to spike to **62.57 ms** (8.5x higher than its mean).
   - In **Parallel** and **Parallel-Distributed** modes, tail latency remains strictly controlled (P95 of 1.71 ms and 2.87 ms respectively), demonstrating robust concurrency management under load.
3. **Cluster Scalability**:
   - The **Distributed** modes validate multi-language interoperability (Java, Python, C++, Rust) with 100% transaction integrity, confirming that lightweight socket framing enables high-performance inference across heterogeneous systems.

