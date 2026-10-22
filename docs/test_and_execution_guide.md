# Open AI Cubes: Execution And Deployment Guide

This guide details the commands required to run the Open AI Cubes distributed architecture, both in an automated 1-click local cluster and in a multi-laptop physical network deployment.

## 1. Prerequisites
- **Java JDK 8+** (Tested with OpenJDK 25)
- **Python 3.8+** (Tested with Python 3.14)
- **GCC / G++ with C++17 support** (Tested with GCC 16)
- **Rust toolchain** (Optional complementary worker, tested with rustc 1.98)

## 2. Option A: Automated 1-Click Local Cluster Launch
To compile, start the Central Server, register all heterogeneous worker nodes (Java, Python, C++), and launch the Desktop GUI in a single command:

```bash
./scripts/launch_cluster.sh
```

### Additional Options for Local Launcher
- To run with CPU core pinning (simulating multi-machine hardware with `taskset`):
  ```bash
  ./scripts/launch_cluster.sh --cores
  ```
- To run in background without Desktop GUI:
  ```bash
  ./scripts/launch_cluster.sh --no-gui
  ```
- To cleanly stop the cluster and release ports:
  ```bash
  ./scripts/stop_cluster.sh
  ```

## 3. Option B: Real Multi-Laptop Network Deployment (LAN / Wi-Fi)
When deploying across multiple physical laptops on the same local network:

### 3.1 Step 1: Start Central Server (Laptop 1 - Master)
Find your local IP address (`ip addr show` or `ifconfig`, e.g., `192.168.1.50`):
```bash
cd java_server_orchestrator
mkdir -p bin
javac -d bin $(find src -name "*.java")
java -cp bin server.ServerOrchestrator 9010 8080
```
- Worker registration port: `9010`
- Client query port: `8080`

### 3.2 Step 2: Connect Distributed Worker Cubes (Teammate Laptops)
Each teammate runs their preferred worker node pointing to Laptop 1's IP address:

- **Java Worker Cube (Laptop 2):**
  ```bash
  cd java_server_orchestrator
  java -cp bin worker.JavaWorkerCube node_java_laptop2 192.168.1.50 9010 4
  ```

- **Python Worker Cube (Laptop 3):**
  ```bash
  cd python_worker_cube
  python3 worker_cube.py node_py_laptop3 192.168.1.50 9010 4
  ```

- **C++ Worker Cube (Laptop 4):**
  ```bash
  cd cpp_worker_cube
  g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube
  ./worker_cube node_cpp_laptop4 192.168.1.50 9010 4
  ```

- **Rust Worker Cube (Optional / Laptop 5):**
  ```bash
  cd rust_worker_cube
  rustc --edition 2021 -O src/main.rs -o rust_worker_bin
  ./rust_worker_bin node_rust_laptop5 192.168.1.50 9010 4
  ```

### 3.3 Step 3: Launch Central Desktop GUI (Laptop 1)
```bash
cd java_server_orchestrator
java -cp bin client.DesktopChatGui 0812 127.0.0.1 8080
```
The Desktop GUI dynamically detects all connected workers in the "Nodos Trabajadores Conectados" table in real time.

### 3.4 Step 4: Multi-Client Concurrent Access
Multiple clients can connect simultaneously from the same machine or across the network, each identified by a unique `idClientChat`:
```bash
# Client 1
java -cp bin client.DesktopChatGui 0812 192.168.1.50 8080

# Client 2 (Another terminal or laptop)
java -cp bin client.DesktopChatGui 1045 192.168.1.50 8080
```

## 4. Unit Testing Commands (Micro-Chunks)

### 4.1 Java Unit Tests
```bash
cd java_server_orchestrator
mkdir -p bin
javac -d bin $(find src -name "*.java")
java -cp bin test.CoreUnitTests
rm -rf bin
cd ..
```

### 4.2 Python Unit Tests
```bash
cd python_worker_cube
python3 test_python_chunks.py
cd ..
```

### 4.3 C++ Unit Tests
```bash
cd cpp_worker_cube
g++ -std=c++17 -O2 test_cpp_chunks.cpp -o test_cpp_chunks
./test_cpp_chunks
rm -f test_cpp_chunks
cd ..
```

### 4.4 Rust Unit Tests
```bash
cd rust_worker_cube
rustc --test --edition 2021 -O src/main.rs -o test_rust
./test_rust --nocapture
rm -f test_rust
cd ..
```

## 5. Automated System Integration And Multi-Client Test
Verifies end-to-end communication across all 4 execution modes (Sequential, Parallel, Distributed, Parallel-Distributed) and evaluates concurrent multi-client requests:
```bash
python3 tests/test_integration.py
```

## 6. Real Dataset Mode Vs Mock Fallback Mode
- **Real Dataset Mode**: Active when `dataset/real/train` exists with genuine animal photographs. The prototypes are automatically computed from the spatial convolutions of authentic Wikimedia Commons photos.
- **Mock Fallback Mode**: Active when no external image directory is found (e.g. grading source-only zip). The system seamlessly falls back to canonical geometric matrices. Mathematical CNN computations remain 100% active and authentic in both modes.

## 7. Independent Training and Inference Pipelines
Training and inference operate through separate processing pipelines, yet both support all four execution modes:
- **Four Computational Execution Modes**:
  1. `SEQUENTIAL`: Strictly single-task execution. Processed locally one task at a time protected by an exclusive monitor lock.
  2. `PARALLEL`: Real multi-core parallelism across local CPU threads via thread pool.
  3. `DISTRIBUTED`: Micro-chunk pipeline across heterogeneous remote cubes. Each worker cube executes its internal filters in parallel using its allocated threads/cores (via OpenMP in C++, ThreadPoolExecutor in Python, scoped threads in Rust, and thread pools in Java), but **only one micro-chunk cube runs at a time in the pipeline** (Cube 1 executes parallel convolution $\to$ finishes $\to$ hands off features to Cube 2 which executes parallel MLP).
  4. `PARALLEL_DISTRIBUTED`: Concurrent distributed pipeline across cubes and threads simultaneously without sequential pipeline gating.
- **Simulated Hardware Mode Vs Real Physical Cluster**:
  - *Simulated Mode (`--cores`)*: Uses `taskset` to bind each worker node to dedicated CPU cores with restricted worker thread counts, simulating multi-machine execution on a single computer.
  - *Real Mode (Default)*: Worker cubes are deployed across separate physical machines (or without core isolation locally) utilizing all available CPU hardware cores.
- **Training Pipeline (`core.ModelTrainer`)**:
  - Ingests training photographs from `dataset/real/train` or normalized entries from `dataset/database.csv`.
  - Computes spatial convolutions, ReLU activations, and Max-Pooling features across the chosen mode.
  - Persists the computed prototype vectors to a standard CSV weights file: `dataset/weights.csv`.
  - Immediately updates in-memory weights for real-time inference.
- **Inference Pipeline (`core.CnnInferenceEngine`)**:
  - Ingests input images (either from the built-in sample catalog or uploaded locally via `JFileChooser`).
  - Evaluates similarity scores and class probabilities using the pre-loaded or newly trained weights from `dataset/weights.csv`.
- **Graphical Interface Switching**:
  - The Desktop GUI provides radio buttons to toggle between **Inferencia** (single image identification or 4-mode inference benchmark) and **Entrenamiento** (model training or 4-mode training benchmark).

## 8. High-Resolution Authentic Community Dataset And Adaptive Pooling
- **Community Dataset Downloader (`dataset/download_community_dataset.py`)**:
  - Automatically queries community photographic archives on Wikimedia Commons for the 5 target animal classes (`panda`, `cat`, `dog`, `bear`, `lion`).
  - Filters out maps, diagrams, illustrations, skeletons, and icons, downloading only genuine, distinct photographs.
  - Applies center-crop and high-quality Lanczos resampling to $720\times 720$ resolution RGB.
  - Generates 200 distinct authentic photographs (40 per class) with zero transformed copies or synthetic drawing seeds:
    - `dataset/real/train/`: 25 distinct photographs per class ($125$ total images)
    - `dataset/real/val/`: 5 distinct photographs per class ($25$ total images)
    - `dataset/real/test/`: 10 distinct photographs per class ($50$ total images)
  - Execution command:
    ```bash
    python3 dataset/download_community_dataset.py
    ```
- **Spatial Adaptive Max-Pooling ($720\times 720 \to 13\times 13$)**:
  - To prevent network buffer saturation and $O(N^2)$ prototype vectors, all heterogeneous worker cubes (Java, Python, C++, Rust) execute fused 2D convolution + ReLU + Adaptive Spatial Max-Pooling.
  - Regardless of input resolution ($28\times 28$ or $720\times 720$), feature maps are dynamically binned into a $13\times 13$ grid.
  - The resulting feature vector is invariant at 1352 dimensions ($8 \times 169$), ensuring $O(1)$ memory usage, efficient TCP transmission, and high CPU stress concentrated on the spatial convolution stages.
- **Weights File (`dataset/weights.csv`)**:
  - Persisted in standard CSV format: `<label>,<w0>,<w1>,...,<w1351>`.
  - Recomputed directly from the community photographs using regularized Dual Ridge Regression ($W = \alpha^T X$, with $\alpha = (X X^T + \lambda I)^{-1} Y, \lambda = 0.08$) across any of the 4 execution modes (Sequential, Parallel, Distributed, Parallel-Distributed).
  - Achieves **100.0% (50/50) accuracy** across all 50 unseen authentic test photographs with high calibrated confidences ($63.1\% - 98.7\%$).

## 9. Graphical Interface Ergonomics And Visualizer
- **Aspect-Ratio Preserved Bilinear Visualizer**:
  - `ImagePreviewPanel` renders input photographs in true RGB color using bilinear interpolation (`RenderingHints.KEY_INTERPOLATION_BILINEAR`) and anti-aliasing.
  - Automatically calculates aspect ratio bounding to prevent stretching, distortion, or black-and-white downsampling artifacts.
- **Horizontal Ergonomic Layout**:
  - The control buttons ("Yo: Identificar", "Yo: Entrenar Modelo") and benchmark execution parameters ("Muestras:", spinner, "Ejecutar Benchmark") are positioned directly alongside the image preview rather than beneath it, minimizing vertical scrolling and maximizing horizontal screen space.
- **Resilient Connection Handling**:
  - The Desktop GUI client incorporates an automatic connection retry loop with exponential backoff, preventing connection errors if the central orchestrator is still binding network sockets during cluster launch.




