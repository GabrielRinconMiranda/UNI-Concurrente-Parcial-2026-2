#!/bin/bash
# Open AI Cubes: Cluster Shutdown Script
# Terminates running server and worker processes and cleans up resources.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_FILE="$ROOT_DIR/.cluster_pids"

echo "[Cluster] Shutting down Open AI Cubes Cluster..."

if [ -f "$PID_FILE" ]; then
    while IFS= read -r pid; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done < "$PID_FILE"
    sleep 0.3
    while IFS= read -r pid; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null || true
        fi
    done < "$PID_FILE"
    rm -f "$PID_FILE"
fi

# Fallback: Terminate any processes holding ports 9010 or 8080
if command -v fuser >/dev/null 2>&1; then
    fuser -k -9 9010/tcp >/dev/null 2>&1 || true
    fuser -k -9 8080/tcp >/dev/null 2>&1 || true
fi

# Fallback by process name
pkill -9 -f "server.ServerOrchestrator" 2>/dev/null || true
pkill -9 -f "worker.JavaWorkerCube" 2>/dev/null || true
pkill -9 -f "python3 worker_cube.py" 2>/dev/null || true
pkill -9 -f "./worker_cube" 2>/dev/null || true
pkill -9 -f "rust_worker_bin" 2>/dev/null || true

# Optional deep clean if requested
if [ "$1" == "--clean" ]; then
    rm -rf "$ROOT_DIR/java_server_orchestrator/bin"
    rm -f "$ROOT_DIR/cpp_worker_cube/worker_cube"
    rm -f "$ROOT_DIR/rust_worker_cube/rust_worker_bin"
    find "$ROOT_DIR" -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
fi

echo "[Cluster] Cluster stopped."

