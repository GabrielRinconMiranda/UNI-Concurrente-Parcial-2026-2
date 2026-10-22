#!/usr/bin/env bash
# Open AI Cubes: Multi-Host Docker Deployment Script (Linux)
# Compatible with Ubuntu, Debian, Fedora, Arch, CentOS, openSUSE

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

echo "================================================================="
echo "        Open AI Cubes | Multi-Host Docker Deployment"
echo "================================================================="

# 1. Check if Docker is installed
if ! command -v docker >/dev/null 2>&1; then
    echo "[!] Docker is not installed on this system."
    read -rp "Would you like to install Docker automatically now? (y/N): " INSTALL_DOCKER
    if [[ "$INSTALL_DOCKER" =~ ^[Yy]$ ]]; then
        echo "[*] Installing Docker using official installation script..."
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL https://get.docker.com | sudo sh
        elif command -v wget >/dev/null 2>&1; then
            wget -qO- https://get.docker.com | sudo sh
        else
            echo "[-] Error: curl or wget is required to download Docker installer."
            exit 1
        fi
        sudo systemctl enable --now docker || true
        sudo usermod -aG docker "$USER" 2>/dev/null || true
        echo "[+] Docker installed successfully. If permission errors occur, run with sudo or re-login."
    else
        echo "[-] Docker is required to run the containerized cluster. Exiting."
        exit 0
    fi
fi

# Detect if docker requires sudo
DOCKER_CMD="docker"
if ! docker info >/dev/null 2>&1; then
    if sudo docker info >/dev/null 2>&1; then
        DOCKER_CMD="sudo docker"
    else
        echo "[-] Docker daemon is not running. Please start the Docker service:"
        echo "    sudo systemctl start docker"
        exit 1
    fi
fi

# 2. Network information
LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
if [ -z "$LOCAL_IP" ]; then
    LOCAL_IP=$(ip route get 1.1.1.1 2>/dev/null | awk '{print $7}')
fi
if [ -z "$LOCAL_IP" ]; then
    LOCAL_IP="127.0.0.1"
fi

echo ""
echo "Detected Local IP on this machine: $LOCAL_IP"
echo ""
echo "Deployment and Activation Guidelines:"
echo "  1. Activation Order:"
echo "     Step 1: Start Central Orchestrator on Host 1."
echo "     Step 2: Start Worker Cubes on Host 2, Host 3, etc., pointing to Host 1's IP."
echo "     Step 3: Verify worker connectivity on Web Admin Console (http://<HOST1_IP>:8080/admin.html)."
echo "     Step 4: Run client inference from browser or Desktop Swing Client."
echo "  2. Port Requirements on Host 1 (Orchestrator):"
echo "     - 9010 (TCP): Worker node registration and task streams."
echo "     - 8085 (TCP): Desktop client query socket."
echo "     - 8080 (HTTP): REST inference and web dashboard."
echo ""
echo "Select the component to deploy on this computer:"
echo "  1) Central Orchestrator Server (Web Dashboard + TCP Dispatcher)"
echo "  2) Java Worker Cube"
echo "  3) Python Worker Cube"
echo "  4) C++ Worker Cube (OpenMP Accelerated)"
echo "  5) Rust Worker Cube"
echo "  6) Go Worker Cube"
echo "  7) Complete Cluster (All-in-One: Orchestrator + 5 Workers on this PC)"
echo "  8) Exit"
read -rp "Enter choice [1-8]: " CHOICE

if [ "$CHOICE" == "8" ]; then
    echo "Exiting."
    exit 0
fi

# 3. Build Docker image if not present
if ! $DOCKER_CMD image inspect open-ai-cubes >/dev/null 2>&1; then
    echo "[*] Docker image 'open-ai-cubes' not found. Building image from Dockerfile..."
    $DOCKER_CMD build -t open-ai-cubes .
else
    echo "[+] Found existing 'open-ai-cubes' image."
fi

# 4. Handle Selected Component
case "$CHOICE" in
    1)
        echo ""
        echo "[*] Launching Central Orchestrator container..."
        $DOCKER_CMD rm -f openai_orchestrator 2>/dev/null || true
        $DOCKER_CMD run -d \
            --name openai_orchestrator \
            --restart unless-stopped \
            -p 9010:9010 \
            -p 8085:8085 \
            -p 8080:8080 \
            open-ai-cubes
        echo ""
        echo "[+] Central Orchestrator is running in container 'openai_orchestrator'."
        echo "    - Web Client Workspace:  http://$LOCAL_IP:8080"
        echo "    - Admin Monitor Console: http://$LOCAL_IP:8080/admin.html"
        echo "    - TCP Worker Port:       $LOCAL_IP:9010"
        echo "    - TCP Client Port:       $LOCAL_IP:8085"
        echo ""
        echo "Tell teammates running workers to connect to: $LOCAL_IP"
        ;;

    2)
        read -rp "Enter Central Orchestrator IP address [default: $LOCAL_IP]: " SERVER_HOST
        SERVER_HOST="${SERVER_HOST:-$LOCAL_IP}"
        read -rp "Enter Worker ID [default: node_java_remote]: " W_ID
        W_ID="${W_ID:-node_java_remote}"
        echo "[*] Launching Java Worker Cube connecting to $SERVER_HOST:9010..."
        $DOCKER_CMD rm -f "openai_${W_ID}" 2>/dev/null || true
        $DOCKER_CMD run -d \
            --name "openai_${W_ID}" \
            --restart unless-stopped \
            open-ai-cubes \
            bash -c "cd /workspace/java_server_orchestrator && java -cp bin worker.JavaWorkerCube $W_ID $SERVER_HOST 9010 4"
        echo "[+] Java Worker Cube '$W_ID' launched in background."
        ;;

    3)
        read -rp "Enter Central Orchestrator IP address [default: $LOCAL_IP]: " SERVER_HOST
        SERVER_HOST="${SERVER_HOST:-$LOCAL_IP}"
        read -rp "Enter Worker ID [default: node_py_remote]: " W_ID
        W_ID="${W_ID:-node_py_remote}"
        echo "[*] Launching Python Worker Cube connecting to $SERVER_HOST:9010..."
        $DOCKER_CMD rm -f "openai_${W_ID}" 2>/dev/null || true
        $DOCKER_CMD run -d \
            --name "openai_${W_ID}" \
            --restart unless-stopped \
            open-ai-cubes \
            bash -c "cd /workspace/python_worker_cube && python3 worker_cube.py $W_ID $SERVER_HOST 9010 4"
        echo "[+] Python Worker Cube '$W_ID' launched in background."
        ;;

    4)
        read -rp "Enter Central Orchestrator IP address [default: $LOCAL_IP]: " SERVER_HOST
        SERVER_HOST="${SERVER_HOST:-$LOCAL_IP}"
        read -rp "Enter Worker ID [default: node_cpp_remote]: " W_ID
        W_ID="${W_ID:-node_cpp_remote}"
        echo "[*] Launching C++ Worker Cube connecting to $SERVER_HOST:9010..."
        $DOCKER_CMD rm -f "openai_${W_ID}" 2>/dev/null || true
        $DOCKER_CMD run -d \
            --name "openai_${W_ID}" \
            --restart unless-stopped \
            open-ai-cubes \
            bash -c "cd /workspace/cpp_worker_cube && ./worker_cube $W_ID $SERVER_HOST 9010 4"
        echo "[+] C++ Worker Cube '$W_ID' launched in background."
        ;;

    5)
        read -rp "Enter Central Orchestrator IP address [default: $LOCAL_IP]: " SERVER_HOST
        SERVER_HOST="${SERVER_HOST:-$LOCAL_IP}"
        read -rp "Enter Worker ID [default: node_rust_remote]: " W_ID
        W_ID="${W_ID:-node_rust_remote}"
        echo "[*] Launching Rust Worker Cube connecting to $SERVER_HOST:9010..."
        $DOCKER_CMD rm -f "openai_${W_ID}" 2>/dev/null || true
        $DOCKER_CMD run -d \
            --name "openai_${W_ID}" \
            --restart unless-stopped \
            open-ai-cubes \
            bash -c "cd /workspace/rust_worker_cube && ./rust_worker_bin $W_ID $SERVER_HOST 9010 4"
        echo "[+] Rust Worker Cube '$W_ID' launched in background."
        ;;

    6)
        read -rp "Enter Central Orchestrator IP address [default: $LOCAL_IP]: " SERVER_HOST
        SERVER_HOST="${SERVER_HOST:-$LOCAL_IP}"
        read -rp "Enter Worker ID [default: node_go_remote]: " W_ID
        W_ID="${W_ID:-node_go_remote}"
        echo "[*] Launching Go Worker Cube connecting to $SERVER_HOST:9010..."
        $DOCKER_CMD rm -f "openai_${W_ID}" 2>/dev/null || true
        $DOCKER_CMD run -d \
            --name "openai_${W_ID}" \
            --restart unless-stopped \
            open-ai-cubes \
            bash -c "cd /workspace/go_worker_cube && ./worker_cube $W_ID $SERVER_HOST 9010 4"
        echo "[+] Go Worker Cube '$W_ID' launched in background."
        ;;

    7)
        echo "[*] Launching complete cluster stack via Docker Compose..."
        if command -v docker-compose >/dev/null 2>&1; then
            docker-compose up -d --build
        else
            $DOCKER_CMD compose up -d --build
        fi
        echo ""
        echo "[+] Complete cluster is active."
        echo "    - Web Dashboard:  http://localhost:8080"
        echo "    - Admin Console:  http://localhost:8080/admin.html"
        ;;

    *)
        echo "Invalid selection."
        exit 1
        ;;
esac

