#!/bin/bash
# Open AI Cubes: Admin Monitor Launcher
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

HOST="${1:-http://localhost:8080}"
echo "[Admin] Starting Open AI Cubes Admin Monitor connecting to $HOST..."
cd "$ROOT_DIR/java_server_orchestrator"
java -cp bin server.AdminMonitorGui "$HOST"
