#!/bin/bash
# Open AI Cubes: Client Desktop GUI Launcher
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

CLIENT_ID="${1:-}"
HOST="${2:-127.0.0.1}"
PORT="${3:-8085}"

echo "[Client] Starting Desktop Client Workspace connecting to $HOST:$PORT..."
cd "$ROOT_DIR/java_server_orchestrator"
java -cp bin client.DesktopChatGui "$CLIENT_ID" "$HOST" "$PORT"
