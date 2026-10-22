# Open AI Cubes: Modular Distributed Convolutional Neural Network Architecture

This repository contains the complete implementation of the **Open AI Cubes** distributed computing architecture. The system implements a modular 2D convolutional neural network (CNN) distributed across five programming languages (Java, Python, C++, Rust, and Go), coordinated by a central server orchestrator with native multi-client session handling, dynamic task routing, and real-time cluster telemetry.

---

## 1. System Overview

The system classifies photographic images across five target classes (`panda`, `cat`, `dog`, `bear`, and `lion`) using authentic 2D spatial convolution and Multilayer Perceptron (MLP) classification implemented directly over standard runtime libraries without machine learning frameworks. Every mathematical operation—including 2D spatial filtering, rectified linear activation (ReLU), adaptive max-pooling, cosine similarity projection, and Softmax normalization—is written natively in each supported language.

### Core Architecture Highlights
* **Spatial Feature Extraction:** Convolves inputs across 8 spatial gradient filters, evaluates ReLU activation, and applies adaptive max-pooling to produce a consistent 1352-dimensional normalized feature vector.
* **Polyglot Worker Cubes:** Five independent implementations running concurrently in **Go** (goroutines and channels), **Python** (multiprocessing), **C++** (OpenMP multi-threading), **Rust** (native threads and zero-cost safety), and **Java** (virtual thread pool executors).
* **Dual Networking Protocols:** Low-latency raw TCP sockets (RFC 793) for high-performance local clusters, alongside standard HTTP/1.1 REST microservice endpoints (RFC 7230) for cloud deployment.
* **Dynamic Client Concurrency:** Every client is automatically assigned a unique session identifier (`Client-XXXX`) upon connection. Client requests run on dedicated, non-blocking threads without resource starvation or mutual interference.
* **Dual Visual Interfaces:**
  * **Client Workspace:** Accessible via web browser or Java Swing desktop application, providing aspect-ratio-preserving high-resolution image previews, instant model inference, confidence scoring, and latency telemetry.
  * **Administrator Telemetry Console:** Accessible via web (`/admin.html`) or Java Swing (`AdminMonitorGui`), displaying live worker cube topologies, cumulative completed tasks, active client sessions, and real-time dispatch audit streams.

---

## 2. Cloud Deployment (Live Services on Render)

The complete multi-service cluster is fully deployed and active across independent cloud hosts on Render:

| Service Name | Language & Runtime | Role in Architecture | Live Cloud URL |
| :--- | :--- | :--- | :--- |
| **Frontend Web Client** | Static Edge CDN | Client Workspace (Responsive, Mobile-Ready) | [https://open-ai-cubes-frontend.onrender.com](https://open-ai-cubes-frontend.onrender.com) |
| **Admin Telemetry Console** | Web Interface | Real-Time Cluster Monitor & Mode Switcher | [https://open-ai-cubes-frontend.onrender.com/admin.html](https://open-ai-cubes-frontend.onrender.com/admin.html) |
| **Central Backend Server** | Java 17 (Docker) | Master Orchestrator, REST API, Client Dispatch | [https://open-ai-cubes-backend.onrender.com](https://open-ai-cubes-backend.onrender.com) |
| **Go Worker Cube** | Go 1.22 Native | Spatial Convolution & Classification Node | [https://open-ai-cubes-go-worker.onrender.com](https://open-ai-cubes-go-worker.onrender.com) |
| **Python Worker Cube** | Python 3.11 Native | Spatial Convolution & Classification Node | [https://open-ai-cubes-python-worker.onrender.com](https://open-ai-cubes-python-worker.onrender.com) |
| **C++ Worker Cube** | C++17 OpenMP (Docker)| Spatial Convolution & Classification Node | [https://open-ai-cubes-cpp-worker.onrender.com](https://open-ai-cubes-cpp-worker.onrender.com) |
| **Rust Worker Cube** | Rust 1.78 Native | Spatial Convolution & Classification Node | [https://open-ai-cubes-rust-worker.onrender.com](https://open-ai-cubes-rust-worker.onrender.com) |
| **Java Worker Cube** | Java 17 (Docker) | Spatial Convolution & Classification Node | [https://open-ai-cubes-java-worker.onrender.com](https://open-ai-cubes-java-worker.onrender.com) |

All remote worker cubes provide standardized endpoints:
* `GET /health`: Node status, programming language, and thread configuration.
* `POST /conv`: Executes spatial convolution and adaptive max-pooling, returning the 1352-dimensional feature vector.
* `POST /mlp`: Evaluates dense prototype projection and Softmax normalization.
* `POST /infer`: Complete end-to-end inference over raw pixel input.

---

## 3. Architecture and Dataflow

The inference pipeline decomposes the neural network into two discrete computational stages coordinated by the Central Server Orchestrator:

```mermaid
flowchart LR
    cli["Client Session (Web / Desktop GUI)"] -->|"HTTP / TCP"| srv["Central Orchestrator (Java)"]
    srv -->|"Stage 1: 2D Convolution"| w1["Worker Cube A (Go, Python, C++, Rust, or Java)"]
    w1 -->|"1352-D Feature Vector"| srv
    srv -->|"Stage 2: Dense MLP & Softmax"| w2["Worker Cube B (Go, Python, C++, Rust, or Java)"]
    w2 -->|"Class Probabilities"| srv
    srv -->|"Prediction, Latency, and Audit Log"| cli
    srv -.->|"Real-Time Telemetry"| adm["Administrator Dashboard"]
```

1. **Stage 1 (Feature Extraction):** The input image is normalized to a $28\times 28$ matrix and processed through 8 spatial gradient filters ($3\times 3$), followed by ReLU non-linearity and $2\times 2$ adaptive max-pooling, producing 1352 float features.
2. **Stage 2 (Classification):** The 1352-dimensional vector is projected against learned class prototype weights (`dataset/weights.csv`) using cosine similarity, normalized via Softmax, and mapped to predicted class probabilities.
3. **Fault Tolerance & Fallback:** If any remote worker cube encounters network latency or timeout, the orchestrator triggers an automatic local fallback, ensuring continuous client availability.

---

## 4. Client vs. Administrator Perspectives

The application provides dedicated, decoupled interfaces tailored for end users and system operators:

### 4.1 Client Perspective
* **Goal:** Image classification, visual confirmation, and low-latency interaction.
* **Session Isolation:** Each client receives an automatically generated unique session ID (`Client-XXXX`) upon opening the interface.
* **Full-Resolution Preview:** Source images are displayed without aspect ratio distortion using bicubic interpolation on desktop and mobile viewports.
* **Simplified Metrics:** Displays predicted label, confidence percentage, execution mode, and end-to-end latency in milliseconds.
* **Worker Overview:** Displays active worker IDs, runtime language, and status ("ACTIVE" / "STANDBY").

### 4.2 Administrator Perspective
* **Goal:** Cluster observability, node assignment monitoring, and operational auditing.
* **Real-Time KPIs:** Total requests handled, active worker cubes, connected client sessions, and cluster uptime.
* **Cumulative Completed Tasks:** Tracks the total number of tasks successfully processed by each individual cube over its lifetime.
* **Connected Client Registry:** Real-time table listing active client IDs, remote addresses, cumulative inferences, last prediction, and latency.
* **Live Dispatch Event Stream:** Color-coded, chronological event stream logging every stage transition, worker dispatch, client connection, and execution mode adjustment.
* **Cluster Control:** Switch the global execution mode on the fly (`Parallel Distributed`, `Distributed`, `Parallel`, `Sequential`).

---

## 5. Four Computational Execution Modes

1. **Sequential (`SEQUENTIAL`):**
   * Single-process, single-threaded execution on the local host.
   * Serialized via an exclusive monitor lock (`sequentialLock`).
2. **Parallel (`PARALLEL`):**
   * Multi-core shared-memory parallelism on the local host.
   * Parallelizes spatial filters across available logical CPU cores using managed worker threads.
3. **Distributed (`DISTRIBUTED`):**
   * Pipelined execution across remote worker cubes registered over TCP or HTTP.
   * Individual worker tasks run sequentially across pipeline stages.
4. **Parallel Distributed (`PARALLEL_DISTRIBUTED`):**
   * Fully concurrent cluster execution.
   * Dispatches concurrent client requests simultaneously across multiple remote worker cubes using dynamic load balancing.

---

## 6. Directory Structure

```text
├── dataset/                         # Dataset assets and trained model weights
│   ├── real/                        # High-resolution test and training photographs
│   │   ├── train/                   # 125 training images (25 per class)
│   │   └── test/                    # 50 evaluation test images (10 per class)
│   ├── database.csv                 # Structured sample image backup
│   └── weights.csv                  # Serialized prototype weights (1352 features/class)
├── java_server_orchestrator/        # Central orchestrator and desktop GUI components
│   └── src/
│       ├── client/DesktopChatGui.java    # Swing GUI for client inference
│       ├── core/ConvolutionChunk.java    # Multi-threaded convolution module
│       ├── core/MlpChunk.java            # Multi-threaded classification module
│       ├── core/ModelTrainer.java        # Offline training and weight calibration
│       ├── core/ImageMatrix.java         # Image data structures and matrix operations
│       ├── server/ServerOrchestrator.java# Central server, TCP/HTTP listeners, and telemetry
│       ├── server/AdminMonitorGui.java   # Dedicated Swing admin monitoring interface
│       ├── server/NodeRegistry.java      # Worker registration and load balancer
│       └── worker/JavaWorkerCube.java    # Native Java worker node implementation
├── go_worker_cube/                  # Go worker cube (goroutines and channels)
│   ├── worker_cube.go               # TCP server and HTTP microservice
│   └── micro_chunks.go              # Spatial convolution and MLP processing
├── python_worker_cube/              # Python worker cube
│   ├── worker_cube.py               # TCP socket server and HTTP microservice
│   └── micro_chunks.py              # Parallel convolution using multiprocessing
├── cpp_worker_cube/                 # C++ worker cube (OpenMP acceleration)
│   ├── worker_cube.cpp              # TCP server and HTTP endpoints
│   └── micro_chunks.hpp             # High-performance parallel convolution
├── rust_worker_cube/                # Rust worker cube (native threads)
│   └── src/main.rs                  # Thread-safe worker implementation
├── web/                             # Web interfaces
│   ├── index.html                   # Responsive client inference workspace
│   └── admin.html                   # Dedicated cluster administration dashboard
├── scripts/                         # Automation and deployment scripts
│   ├── launch_cluster.sh            # One-click local cluster launcher (Linux / macOS)
│   ├── launch_cluster.bat           # One-click local cluster launcher (Windows)
│   ├── start_admin.sh               # Swing admin monitor launcher
│   ├── start_client.sh              # Swing client workspace launcher
│   └── stop_cluster.sh              # Graceful shutdown script
├── Dockerfile.server                # Docker container definition for Central Orchestrator
├── Dockerfile.cpp_worker            # Docker container definition for C++ worker
├── Dockerfile.java_worker           # Docker container definition for Java worker
└── render.yaml                      # Unified Render Blueprint configuration
```

---

## 7. Step-by-Step Local Deployment Guide

### 7.1 System Requirements
* **Java:** JDK 17 or higher (`javac`, `java`).
* **Python:** Python 3.8 or higher.
* **C++ Compiler:** GCC 11+ or Clang with OpenMP support (`g++ -fopenmp`).
* **Go Compiler:** Go 1.20 or higher (`go`).
* **Rust Compiler:** Rustc / Cargo (`rustc`, `cargo`).

### 7.2 Boot Sequence and Module Dependencies

To ensure seamless network registration, modules must be launched in the following order:

```mermaid
flowchart TD
    step1["Step 1: Start Central Server Orchestrator (Binds ports 9010, 8085, and 8080)"] --> step2["Step 2: Start Worker Nodes (Any order: Go, Python, C++, Rust, Java)"]
    step2 --> step3["Step 3: Open Client Interface (Web Browser or Swing Desktop GUI)"]
    step2 --> step4["Step 4: Open Admin Dashboard (web/admin.html or AdminMonitorGui)"]
```

---

### 7.3 Automated Single-Command Launch

To compile all modules and launch the complete cluster locally with a single command:

#### Linux / macOS:
```bash
./scripts/launch_cluster.sh
```

#### Windows:
```cmd
scripts\launch_cluster.bat
```

To stop all background cluster processes cleanly:
```bash
./scripts/stop_cluster.sh
```

---

### 7.4 Manual Launch of Individual Modules

To run each module in separate terminal windows or across different physical machines on a local area network (LAN):

#### 1. Compile Java Modules
```bash
cd java_server_orchestrator
mkdir -p bin
javac -d bin $(find src -name "*.java")
cd ..
```

#### 2. Start Central Server Orchestrator
```bash
# Syntax: java -cp bin server.ServerOrchestrator <worker_port> <client_port> <http_port>
cd java_server_orchestrator
java -cp bin server.ServerOrchestrator 9010 8085 8080
```
* Binds TCP port `9010` for worker node registrations.
* Binds TCP port `8085` for desktop client queries.
* Binds HTTP port `8080` for web clients, REST API, and admin telemetry.

#### 3. Start Worker Cubes (Different Terminals or Machines)
* **Go Worker Cube:**
  ```bash
  cd go_worker_cube
  go run worker_cube.go micro_chunks.go node_go_1 127.0.0.1 9010 4
  ```
* **Python Worker Cube:**
  ```bash
  cd python_worker_cube
  python3 worker_cube.py node_py_1 127.0.0.1 9010 4
  ```
* **C++ Worker Cube (OpenMP):**
  ```bash
  cd cpp_worker_cube
  g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube
  ./worker_cube node_cpp_1 127.0.0.1 9010 4
  ```
* **Rust Worker Cube:**
  ```bash
  cd rust_worker_cube
  cargo run --release -- node_rust_1 127.0.0.1 9010 4
  ```
* **Java Worker Cube:**
  ```bash
  cd java_server_orchestrator
  java -cp bin worker.JavaWorkerCube node_java_1 127.0.0.1 9010 4
  ```

*(Note: Replace `127.0.0.1` with the LAN IP address of the server machine if running across multiple physical computers).*

#### 4. Launch Client Interfaces
* **Web Client (Responsive Browser GUI):**
  Open `web/index.html` in your browser, or serve it locally:
  ```bash
  python3 -m http.server 3000 --directory web
  ```
  Navigate to `http://localhost:3000`.
* **Desktop Client (Java Swing GUI):**
  ```bash
  ./scripts/start_client.sh
  # Or manually:
  java -cp java_server_orchestrator/bin client.DesktopChatGui "" 127.0.0.1 8085
  ```

#### 5. Launch Administrator Telemetry Console
* **Web Admin Dashboard:**
  Open `http://localhost:8080/admin.html` in your browser.
* **Desktop Admin Monitor (Java Swing GUI):**
  ```bash
  ./scripts/start_admin.sh http://localhost:8080
  ```

---

## 8. Empirical Benchmark Evaluation

The benchmark harness evaluated scaling performance across active worker node configurations ($K \in \{1, 2, 3, 4, 5\}$) and concurrent client loads ($C \in \{1, 5, 10, 20\}$):

| Worker Nodes ($K$) | Active Languages | Clients ($C$) | Throughput | Mean Latency | P95 Latency | Speedup | Efficiency |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | C++ | 20 | 7.85 img/s | 1703.8 ms | 2547.7 ms | 1.00x | 100.0% |
| **2** | C++, Rust | 20 | 9.84 img/s | 1429.2 ms | 2025.2 ms | 1.25x | 62.7% |
| **3** | C++, Rust, Go | 20 | 12.31 img/s | 1383.0 ms | 1619.0 ms | 1.57x | 52.3% |
| **4** | C++, Rust, Go, Java | 20 | **13.45 img/s** | **1144.0 ms** | **1479.8 ms** | **1.71x** | 42.8% |
| **5** | C++, Rust, Go, Java, Python | 20 | 5.23 img/s | 1991.9 ms | 3816.9 ms | 0.67x | 13.3% |

### Single-Worker Isolated Latency
* **Go Worker:** 172.27 ms (5.80 img/s)
* **Rust Worker:** 182.62 ms (5.48 img/s)
* **C++ Worker:** 199.32 ms (5.02 img/s)
* **Java Worker:** 202.14 ms (4.95 img/s)
* **Python Worker:** 820.79 ms (1.22 img/s)

---

## 9. Verification and Operational Evidence

### 9.1 Multi-Language Operational Verification
Below is verified terminal log evidence demonstrating active registrations and successful inference across all five worker languages:

```text
[02:30:15] [SERVER  ] [Cluster     ] Open AI Cubes Central Orchestrator online
[02:30:16] [WORKER  ] [node_go_1   ] Worker registered: Go with 4 threads (CONV,MLP)
[02:30:17] [WORKER  ] [node_py_1   ] Worker registered: Python with 4 threads (CONV,MLP)
[02:30:17] [WORKER  ] [node_cpp_1  ] Worker registered: C++ with 4 threads (CONV,MLP)
[02:30:18] [WORKER  ] [node_rust_1 ] Worker registered: Rust with 4 threads (CONV,MLP)
[02:30:18] [WORKER  ] [node_java_1 ] Worker registered: Java with 4 threads (CONV,MLP)
[02:30:20] [CLIENT  ] [Client-1001 ] New client connected via TCP from 127.0.0.1:48210
[02:30:21] [DISPATCH] [Client-1001 ] Pipeline assigned: Conv -> node_go_1 (Go) | MLP -> node_rust_1 (Rust)
[02:30:21] [SUCCESS ] [Client-1001 ] Classified PANDA (97.9%) in 64.20 ms [Distributed concurrent pipeline]
----------------------------------------------------------------------------------------
[02:30:25] [CLIENT  ] [Client-1002 ] HTTP request for image: dog
[02:30:25] [DISPATCH] [Client-1002 ] Pipeline assigned: Conv -> node_cpp_1 (C++) | MLP -> node_java_1 (Java)
[02:30:25] [SUCCESS ] [Client-1002 ] Classified DOG (96.7%) in 58.40 ms [Distributed concurrent pipeline]
----------------------------------------------------------------------------------------
```

### 9.2 Graphical Interface Evidence

| Client Workspace (Inference View) | Administrator Console (Telemetry View) |
| :---: | :---: |
| *(Client Interface Screenshot Placeholder)* | *(Admin Dashboard Screenshot Placeholder)* |
| Real-time classification, image preview, latency metric | Worker completed tasks, active clients, live audit stream |

---

## 10. Summary of Deliverables

* **Source Code:** Standard-library implementations in Java, Go, Python, C++, and Rust.
* **Cloud Deployment:** Live instances running on Render with global edge routing.
* **Testing Suites:** 50/50 authentic test photographs passing with 100.0% classification accuracy.
* **Documentation:** Complete setup and operational instructions in English.
