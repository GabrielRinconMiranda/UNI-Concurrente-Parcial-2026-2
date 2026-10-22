#!/bin/bash
# Open AI Cubes: Unified Local Cluster Launcher
# Starts Central Orchestrator Server, Worker Cubes (Java, Python, C++, Rust),
# and the Desktop Chat GUI with a single command.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$ROOT_DIR/.cluster_pids"

WORKER_PORT=9010
CLIENT_PORT=8080
LAUNCH_GUI=true
USE_TASKSET=false

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --no-gui) LAUNCH_GUI=false ;;
        --cores) USE_TASKSET=true ;;
        --worker-port) WORKER_PORT="$2"; shift ;;
        --client-port) CLIENT_PORT="$2"; shift ;;
        -h|--help)
            echo "Usage: $0 [--no-gui] [--cores] [--worker-port PORT] [--client-port PORT]"
            echo "  --no-gui       Start server and workers without opening Desktop GUI"
            echo "  --cores        Isolate workers across distinct CPU cores using taskset"
            exit 0
            ;;
        *) echo "Unknown parameter: $1"; exit 1 ;;
    esac
    shift
done

echo "[Cluster] Initializing Open AI Cubes Cluster..."

# Always ensure previous cluster processes and ports are cleaned up
bash "$SCRIPT_DIR/stop_cluster.sh" >/dev/null 2>&1 || true
sleep 0.5
rm -f "$PID_FILE"

# 1. Compile Java components
echo "[Cluster] Compiling Java modules..."
cd "$ROOT_DIR/java_server_orchestrator"
mkdir -p bin
javac -d bin $(find src -name "*.java")

# 2. Compile C++ worker with OpenMP support
echo "[Cluster] Compiling C++ worker with OpenMP..."
cd "$ROOT_DIR/cpp_worker_cube"
g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube

# 3. Compile Rust worker (if rustc available)
HAVE_RUST=false
if command -v rustc >/dev/null 2>&1; then
    echo "[Cluster] Compiling Rust worker..."
    cd "$ROOT_DIR/rust_worker_cube"
    rustc --edition 2021 -O src/main.rs -o rust_worker_bin
    HAVE_RUST=true
fi

# 4. Compile Go worker (if go available)
HAVE_GO=false
GO_BIN=""
if command -v go >/dev/null 2>&1; then
    GO_BIN="go"
    HAVE_GO=true
elif [ -x "$ROOT_DIR/bin/go_dist/bin/go" ]; then
    GO_BIN="$ROOT_DIR/bin/go_dist/bin/go"
    HAVE_GO=true
fi

if [ "$HAVE_GO" = true ]; then
    echo "[Cluster] Compiling Go worker..."
    cd "$ROOT_DIR/go_worker_cube"
    "$GO_BIN" build -o worker_cube
fi

# Determine worker thread allocation (simulated partitioned cores vs real unrestricted cores)
TOTAL_CPUS=$(nproc 2>/dev/null || echo 8)
if [ "$USE_TASKSET" = true ]; then
    WORKER_THREADS=2
    echo "[Cluster] Modo simulado activo: núcleos particionados vía taskset (hilos por worker: $WORKER_THREADS)."
else
    WORKER_THREADS="$TOTAL_CPUS"
    echo "[Cluster] Modo real activo: sin restricción de núcleos (hilos por worker: $WORKER_THREADS)."
fi

# Helper to run with optional taskset and SIGHUP immunity
run_cmd() {
    local core="$1"
    shift
    if [ "$USE_TASKSET" = true ] && command -v taskset >/dev/null 2>&1; then
        nohup taskset -c "$core" "$@"
    else
        nohup "$@"
    fi
}

# 4. Start Central Server (Java)
echo "[Cluster] Starting Central Server on worker port $WORKER_PORT, client port $CLIENT_PORT..."
cd "$ROOT_DIR/java_server_orchestrator"
run_cmd 0 java -cp bin server.ServerOrchestrator "$WORKER_PORT" "$CLIENT_PORT" > /tmp/openai_server.log 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" >> "$PID_FILE"

# Wait for server sockets to be ready
sleep 1.0

# 5. Start Java Worker Cube
echo "[Cluster] Starting Java Worker (node_java_1)..."
run_cmd 1 java -cp bin worker.JavaWorkerCube node_java_1 127.0.0.1 "$WORKER_PORT" "$WORKER_THREADS" > /tmp/openai_worker_java.log 2>&1 &
echo "$!" >> "$PID_FILE"

# 6. Start Python Worker Cube
echo "[Cluster] Starting Python Worker (node_py_1)..."
cd "$ROOT_DIR/python_worker_cube"
run_cmd 2 python3 worker_cube.py node_py_1 127.0.0.1 "$WORKER_PORT" "$WORKER_THREADS" > /tmp/openai_worker_py.log 2>&1 &
echo "$!" >> "$PID_FILE"

# 7. Start C++ Worker Cube
echo "[Cluster] Starting C++ Worker (node_cpp_1)..."
cd "$ROOT_DIR/cpp_worker_cube"
run_cmd 3 ./worker_cube node_cpp_1 127.0.0.1 "$WORKER_PORT" "$WORKER_THREADS" > /tmp/openai_worker_cpp.log 2>&1 &
echo "$!" >> "$PID_FILE"

# 8. Start Rust Worker Cube
if [ "$HAVE_RUST" = true ]; then
    echo "[Cluster] Starting Rust Worker (node_rust_1)..."
    cd "$ROOT_DIR/rust_worker_cube"
    run_cmd 4 ./rust_worker_bin node_rust_1 127.0.0.1 "$WORKER_PORT" "$WORKER_THREADS" > /tmp/openai_worker_rust.log 2>&1 &
    echo "$!" >> "$PID_FILE"
fi

# 9. Start Go Worker Cube
if [ "$HAVE_GO" = true ]; then
    echo "[Cluster] Starting Go Worker (node_go_1)..."
    cd "$ROOT_DIR/go_worker_cube"
    run_cmd 5 ./worker_cube node_go_1 127.0.0.1 "$WORKER_PORT" "$WORKER_THREADS" > /tmp/openai_worker_go.log 2>&1 &
    echo "$!" >> "$PID_FILE"
fi

sleep 0.8
echo "[Cluster] All cluster nodes are online and registered."

# 7. Start Desktop GUI if requested
if [ "$LAUNCH_GUI" = true ]; then
    echo "[Cluster] Launching Desktop GUI Client (idClientChat: 0812)..."
    cd "$ROOT_DIR/java_server_orchestrator"
    trap 'echo "[Cluster] GUI closed, stopping cluster..."; bash "$SCRIPT_DIR/stop_cluster.sh" >/dev/null 2>&1' EXIT INT TERM
    java -cp bin client.DesktopChatGui 0812 127.0.0.1 "$CLIENT_PORT"
else
    echo "[Cluster] Cluster running in background. Use '$SCRIPT_DIR/stop_cluster.sh' to stop."
fi

