@echo off
rem Open AI Cubes: Multi-Host Docker Deployment Script (Windows)
setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
cd /d "%ROOT_DIR%"

echo =================================================================
echo         Open AI Cubes ^| Multi-Host Docker Deployment (Windows)
echo =================================================================

rem 1. Check if Docker is installed
where docker >nul 2>nul
if %errorlevel% neq 0 (
    echo [!] Docker was not found on this Windows system.
    echo.
    echo To install Docker Desktop on Windows:
    echo 1. Download installer from: https://docs.docker.com/desktop/setup/install/windows-install/
    echo 2. Run Docker Desktop Installer.exe and ensure WSL 2 backend is enabled.
    echo 3. Start Docker Desktop and re-run this script.
    echo.
    set /p INSTALL_CHOICE="Do you want to open the Docker download page now? (y/N): "
    if /i "!INSTALL_CHOICE!"=="y" (
        start https://docs.docker.com/desktop/setup/install/windows-install/
    )
    exit /b 0
)

rem 2. Check if Docker daemon is running
docker info >nul 2>nul
if %errorlevel% neq 0 (
    echo [-] Docker is installed but the Docker daemon is not running.
    echo Please launch Docker Desktop from the Start Menu, wait until it starts, and re-run this script.
    pause
    exit /b 1
)

echo.
echo Deployment and Activation Guidelines:
echo   1. Activation Order:
echo      Step 1: Start Central Orchestrator on Host 1.
echo      Step 2: Start Worker Cubes on Host 2, Host 3, etc., pointing to Host 1's IP.
echo      Step 3: Verify worker connectivity on Web Admin Console (http://^<HOST1_IP^>:8080/admin.html).
echo      Step 4: Run client inference from browser or Desktop Swing Client.
echo   2. Port Requirements on Host 1 (Orchestrator):
echo      - 9010 (TCP): Worker node registration and task streams.
echo      - 8085 (TCP): Desktop client query socket.
echo      - 8080 (HTTP): REST inference and web dashboard.
echo.
echo Select the component to deploy on this computer:
echo   1) Central Orchestrator Server (Web Dashboard + TCP Dispatcher)
echo   2) Java Worker Cube
echo   3) Python Worker Cube
echo   4) C++ Worker Cube (OpenMP Accelerated)
echo   5) Rust Worker Cube
echo   6) Go Worker Cube
echo   7) Complete Cluster (All-in-One: Orchestrator + 5 Workers on this PC)
echo   8) Exit
set /p CHOICE="Enter choice [1-8]: "

if "%CHOICE%"=="8" exit /b 0

rem 3. Build Docker image if not present
docker image inspect open-ai-cubes >nul 2>nul
if %errorlevel% neq 0 (
    echo [*] Docker image 'open-ai-cubes' not found. Building image from Dockerfile...
    docker build -t open-ai-cubes .
) else (
    echo [+] Found existing 'open-ai-cubes' image.
)

rem 4. Handle Component Selection
if "%CHOICE%"=="1" (
    echo [*] Launching Central Orchestrator container...
    docker rm -f openai_orchestrator >nul 2>&1
    docker run -d --name openai_orchestrator --restart unless-stopped -p 9010:9010 -p 8085:8085 -p 8080:8080 open-ai-cubes
    echo [+] Central Orchestrator running.
    echo     - Web Client Workspace:  http://localhost:8080
    echo     - Admin Monitor Console: http://localhost:8080/admin.html
    echo     - TCP Worker Port:       9010
    echo     - TCP Client Port:       8085
    pause
    exit /b 0
)

if "%CHOICE%"=="2" (
    set /p SERVER_HOST="Enter Central Orchestrator IP address [default: 127.0.0.1]: "
    if "!SERVER_HOST!"=="" set SERVER_HOST=127.0.0.1
    set /p W_ID="Enter Worker ID [default: node_java_remote]: "
    if "!W_ID!"=="" set W_ID=node_java_remote
    echo [*] Launching Java Worker Cube connecting to !SERVER_HOST!:9010...
    docker rm -f openai_!W_ID! >nul 2>&1
    docker run -d --name openai_!W_ID! --restart unless-stopped open-ai-cubes bash -c "cd /workspace/java_server_orchestrator && java -cp bin worker.JavaWorkerCube !W_ID! !SERVER_HOST! 9010 4"
    echo [+] Java Worker Cube launched.
    pause
    exit /b 0
)

if "%CHOICE%"=="3" (
    set /p SERVER_HOST="Enter Central Orchestrator IP address [default: 127.0.0.1]: "
    if "!SERVER_HOST!"=="" set SERVER_HOST=127.0.0.1
    set /p W_ID="Enter Worker ID [default: node_py_remote]: "
    if "!W_ID!"=="" set W_ID=node_py_remote
    echo [*] Launching Python Worker Cube connecting to !SERVER_HOST!:9010...
    docker rm -f openai_!W_ID! >nul 2>&1
    docker run -d --name openai_!W_ID! --restart unless-stopped open-ai-cubes bash -c "cd /workspace/python_worker_cube && python3 worker_cube.py !W_ID! !SERVER_HOST! 9010 4"
    echo [+] Python Worker Cube launched.
    pause
    exit /b 0
)

if "%CHOICE%"=="4" (
    set /p SERVER_HOST="Enter Central Orchestrator IP address [default: 127.0.0.1]: "
    if "!SERVER_HOST!"=="" set SERVER_HOST=127.0.0.1
    set /p W_ID="Enter Worker ID [default: node_cpp_remote]: "
    if "!W_ID!"=="" set W_ID=node_cpp_remote
    echo [*] Launching C++ Worker Cube connecting to !SERVER_HOST!:9010...
    docker rm -f openai_!W_ID! >nul 2>&1
    docker run -d --name openai_!W_ID! --restart unless-stopped open-ai-cubes bash -c "cd /workspace/cpp_worker_cube && ./worker_cube !W_ID! !SERVER_HOST! 9010 4"
    echo [+] C++ Worker Cube launched.
    pause
    exit /b 0
)

if "%CHOICE%"=="5" (
    set /p SERVER_HOST="Enter Central Orchestrator IP address [default: 127.0.0.1]: "
    if "!SERVER_HOST!"=="" set SERVER_HOST=127.0.0.1
    set /p W_ID="Enter Worker ID [default: node_rust_remote]: "
    if "!W_ID!"=="" set W_ID=node_rust_remote
    echo [*] Launching Rust Worker Cube connecting to !SERVER_HOST!:9010...
    docker rm -f openai_!W_ID! >nul 2>&1
    docker run -d --name openai_!W_ID! --restart unless-stopped open-ai-cubes bash -c "cd /workspace/rust_worker_cube && ./rust_worker_bin !W_ID! !SERVER_HOST! 9010 4"
    echo [+] Rust Worker Cube launched.
    pause
    exit /b 0
)

if "%CHOICE%"=="6" (
    set /p SERVER_HOST="Enter Central Orchestrator IP address [default: 127.0.0.1]: "
    if "!SERVER_HOST!"=="" set SERVER_HOST=127.0.0.1
    set /p W_ID="Enter Worker ID [default: node_go_remote]: "
    if "!W_ID!"=="" set W_ID=node_go_remote
    echo [*] Launching Go Worker Cube connecting to !SERVER_HOST!:9010...
    docker rm -f openai_!W_ID! >nul 2>&1
    docker run -d --name openai_!W_ID! --restart unless-stopped open-ai-cubes bash -c "cd /workspace/go_worker_cube && ./worker_cube !W_ID! !SERVER_HOST! 9010 4"
    echo [+] Go Worker Cube launched.
    pause
    exit /b 0
)

if "%CHOICE%"=="7" (
    echo [*] Launching complete cluster stack via Docker Compose...
    docker compose up -d --build
    echo [+] Complete cluster is active.
    echo     - Web Dashboard:  http://localhost:8080
    echo     - Admin Console:  http://localhost:8080/admin.html
    pause
    exit /b 0
)

echo Invalid choice.
exit /b 1
