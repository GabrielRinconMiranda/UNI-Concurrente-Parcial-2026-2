> [!IMPORTANT]
> **Strict Compliance with Official Examination Guidelines ([`parcialCC4P1_262_v03.pdf`](file:///home/gartur/Downloads/Concurrente%20Parcial/parcialCC4P1_262_v03.pdf))**:
> The grading rubric enforces strict compliance with all technical requirements. Any AI assistant contributing to code, documentation, benchmarks, or presentations MUST adhere to the following invariants:
> 1. **Standard SDK Exclusivity:** Do not introduce third-party machine learning frameworks, computer vision packages, or high-level network libraries (no PyTorch, TensorFlow, OpenCV, WebSockets, or gRPC).
> 2. **Four Computational Execution Modes:** Sequential, Parallel, Distributed, and Parallel Distributed must all remain fully functional and measurable.
> 3. **Heterogeneous Five-Language Architecture:** LP1 (Java), LP2 (Python), LP3 (C++ with OpenMP), LP4 (Rust with native threads), and LP5 (Go with goroutines and net.Conn).
> 4. **Packaging Constraint for Final Submission:** Point deductions apply if unnecessary artifacts or extensions are included. **Never include the `.git` directory** or compiled binaries in the submission ZIP archive.

---

## 1. Project Identity and Architectural Purpose

* **Course:** CC4P1 Concurrent and Distributed Programming.
* **Core Concept:** Open AI Cubes. A modular convolutional neural network (CNN) distributed across heterogeneous worker nodes, coordinated by a central server to classify five animal classes: `panda`, `cat`, `dog`, `bear`, and `lion`.
* **Worker Implementations (Five Languages):**
  * Java 25: [`java_server_orchestrator/src/worker/JavaWorkerCube.java`](file:///home/gartur/Downloads/Concurrente%20Parcial/java_server_orchestrator/src/worker/JavaWorkerCube.java)
  * Python 3: [`python_worker_cube/worker_cube.py`](file:///home/gartur/Downloads/Concurrente%20Parcial/python_worker_cube/worker_cube.py)
  * C++17 (OpenMP): [`cpp_worker_cube/worker_cube.cpp`](file:///home/gartur/Downloads/Concurrente%20Parcial/cpp_worker_cube/worker_cube.cpp)
  * Rust 2021: [`rust_worker_cube/src/main.rs`](file:///home/gartur/Downloads/Concurrente%20Parcial/rust_worker_cube/src/main.rs)
  * Go 1.22: [`go_worker_cube/worker_cube.go`](file:///home/gartur/Downloads/Concurrente%20Parcial/go_worker_cube/worker_cube.go)

---

## 2. Technical Invariants and Mandatory Constraints

1. **Zero External Machine Learning Frameworks:**
   * All spatial convolutions, activation functions, adaptive pooling, and Softmax distributions must be computed natively from scratch.
2. **Cross-Platform Compatibility (Linux, macOS, and Windows):**
   * Native Windows batch scripts provided: [`scripts/launch_cluster.bat`](file:///home/gartur/Downloads/Concurrente%20Parcial/scripts/launch_cluster.bat), [`scripts/stop_cluster.bat`](file:///home/gartur/Downloads/Concurrente%20Parcial/scripts/stop_cluster.bat), [`scripts/launch_gui.bat`](file:///home/gartur/Downloads/Concurrente%20Parcial/scripts/launch_gui.bat).
   * Containerization via Docker (`Dockerfile` and `docker-compose.yml`) supported for local development.
3. **Feature Vector Dimensional Invariance ($D = 1352$):**
   * 8 spatial filters ($3\times 3$).
   * Adaptive max-pooling downsamples feature maps to $13\times 13$.
   * Flattened vector: $8 \times 13 \times 13 = 1352$ 32-bit floating-point numbers.
   * Identical dimension across all five languages guarantees full network interoperability.
4. **Dual Networking Support (TCP and Cloud HTTP):**
   * Low-latency TCP text protocol (`ACTION|ID|PAYLOAD\n`) on ports 9010 (workers) and 8080 (clients).
   * Standard library HTTP REST endpoints (`/health`, `/workers`, `/infer`) on port 8082 (Java), 8081 (Go), and 8083 (Python) for free cloud web hosts (Render, Fly.io, Railway).
5. **Execution Mode Semantics:**
   * `SEQUENTIAL`: Strictly single-threaded local execution gated by `sequentialLock`.
   * `PARALLEL`: Local multi-core shared memory execution via managed `ThreadPoolExecutor`.
   * `DISTRIBUTED`: Remote workers execute internal threads in parallel, but stages are gated sequentially in the pipeline under `distributedLock`.
   * `PARALLEL_DISTRIBUTED`: Concurrent distributed pipeline across workers and threads simultaneously without pipeline gating.
6. **Dual Dataset Handling:**
   * *Real Mode:* Active when `dataset/real/` contains authentic photographic samples.
   * *Mock Fallback Mode:* Active when the image directory is absent, using synthetic canonical matrices while keeping all mathematical CNN operations 100% active.

---

## 3. Project Status and Key Deliverables

* **Academic Deliverables (English):**
  * Technical Report: [`docs/TECHNICAL_REPORT_OPEN_AI_CUBES.pdf`](file:///home/gartur/Downloads/Concurrente%20Parcial/docs/TECHNICAL_REPORT_OPEN_AI_CUBES.pdf) (11 pages, formal cover page with 4-student author template).
  * Executive Presentation: [`docs/PRESENTATION_OPEN_AI_CUBES.pdf`](file:///home/gartur/Downloads/Concurrente%20Parcial/docs/PRESENTATION_OPEN_AI_CUBES.pdf) (12 slides, 16:9, corrected margins).
  * Cloud Deployment Guide: [`docs/DEPLOYMENT_GUIDE.md`](file:///home/gartur/Downloads/Concurrente%20Parcial/docs/DEPLOYMENT_GUIDE.md).
  * Showcase Video: [`media/open_ai_cubes_showcase.mp4`](file:///home/gartur/Downloads/Concurrente%20Parcial/media/open_ai_cubes_showcase.mp4) (1080x1080 square format, ~2 minutes, natural English neural voiceover, implementation-agnostic narrative).
* **Empirical Benchmark Results:**
  * Raw dataset: [`docs/benchmark_multicube_data.csv`](file:///home/gartur/Downloads/Concurrente%20Parcial/docs/benchmark_multicube_data.csv).
  * Summary JSON: [`docs/benchmark_multicube_summary.json`](file:///home/gartur/Downloads/Concurrente%20Parcial/docs/benchmark_multicube_summary.json).
  * Maximum measured speedup: 1.71x with 4 workers under 20 concurrent clients, reducing mean latency by 33% and P95 latency by 42%.
* **Stress Test Verification:**
  * [`scripts/stress_test_saturation.py`](file:///home/gartur/Downloads/Concurrente%20Parcial/scripts/stress_test_saturation.py): 250 requests, 20 concurrent threads, 5–45 ms randomized delay, 100.0% success rate at 13.71 requests/second.
