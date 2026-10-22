# Open AI Cubes: Unified Containerized Environment
# Multi-language cluster support: Java, Python, C++, Rust, and Go

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    python3 \
    g++ \
    libgomp1 \
    golang-go \
    rustc \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Copy source trees, datasets, web assets, and scripts
COPY dataset /workspace/dataset
COPY java_server_orchestrator /workspace/java_server_orchestrator
COPY python_worker_cube /workspace/python_worker_cube
COPY cpp_worker_cube /workspace/cpp_worker_cube
COPY rust_worker_cube /workspace/rust_worker_cube
COPY go_worker_cube /workspace/go_worker_cube
COPY web /workspace/web
COPY scripts /workspace/scripts

# 1. Compile Java components
RUN cd /workspace/java_server_orchestrator && \
    mkdir -p bin && \
    javac -d bin $(find src -name "*.java")

# 2. Compile C++ worker with OpenMP
RUN cd /workspace/cpp_worker_cube && \
    g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube

# 3. Compile Rust worker
RUN cd /workspace/rust_worker_cube && \
    rustc --edition 2021 -O src/main.rs -o rust_worker_bin

# 4. Compile Go worker
RUN cd /workspace/go_worker_cube && \
    go build -o worker_cube worker_cube.go micro_chunks.go

EXPOSE 9010 8085 8080

CMD ["bash", "-c", "cd /workspace/java_server_orchestrator && java -cp bin server.ServerOrchestrator 9010 8085 8080"]
