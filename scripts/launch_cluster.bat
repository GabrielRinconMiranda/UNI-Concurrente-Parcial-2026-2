@echo off
rem Open AI Cubes: Unified Local Cluster Launcher for Windows
setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
cd /d "%ROOT_DIR%"

set WORKER_PORT=9010
set CLIENT_PORT=8085
set HTTP_PORT=8080

echo [Cluster Windows] Initializing Open AI Cubes Cluster...

rem 1. Compile Java
echo [Cluster Windows] Compiling Java modules...
cd /d "%ROOT_DIR%\java_server_orchestrator"
if not exist "bin" mkdir bin
dir /s /b src\*.java > sources.txt
javac -d bin @sources.txt
del sources.txt

rem 2. Compile C++ with OpenMP (if g++ available)
echo [Cluster Windows] Compiling C++ worker...
cd /d "%ROOT_DIR%\cpp_worker_cube"
where g++ >nul 2>nul
if %errorlevel% equ 0 (
    g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube.exe
    set HAVE_CPP=true
) else (
    echo [NOTICE] g++ not found in PATH. C++ worker will not start.
    set HAVE_CPP=false
)

rem 3. Compile Rust (if rustc available)
cd /d "%ROOT_DIR%\rust_worker_cube"
where rustc >nul 2>nul
if %errorlevel% equ 0 (
    echo [Cluster Windows] Compiling Rust worker...
    rustc --edition 2021 -O src\main.rs -o rust_worker_bin.exe
    set HAVE_RUST=true
) else (
    set HAVE_RUST=false
)

rem 4. Compile Go (if go available)
cd /d "%ROOT_DIR%\go_worker_cube"
where go >nul 2>nul
if %errorlevel% equ 0 (
    echo [Cluster Windows] Compiling Go worker...
    go build -o worker_cube.exe
    set HAVE_GO=true
) else (
    set HAVE_GO=false
)

rem 5. Start Central Server
echo [Cluster Windows] Starting Central Server (Worker port: %WORKER_PORT%, Client port: %CLIENT_PORT%, HTTP port: %HTTP_PORT%)...
cd /d "%ROOT_DIR%\java_server_orchestrator"
start "OpenAI_Server" /b java -cp bin server.ServerOrchestrator %WORKER_PORT% %CLIENT_PORT% %HTTP_PORT% > "%TEMP%\openai_server.log" 2>&1
timeout /t 1 /nobreak >nul

rem 6. Start Java Worker
echo [Cluster Windows] Starting Java worker (node_java_1)...
start "OpenAI_Worker_Java" /b java -cp bin worker.JavaWorkerCube node_java_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_java.log" 2>&1

rem 7. Start Python Worker
echo [Cluster Windows] Starting Python worker (node_py_1)...
cd /d "%ROOT_DIR%\python_worker_cube"
start "OpenAI_Worker_Python" /b python worker_cube.py node_py_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_py.log" 2>&1

rem 8. Start C++ Worker (if compiled)
if "%HAVE_CPP%"=="true" (
    echo [Cluster Windows] Starting C++ worker (node_cpp_1)...
    cd /d "%ROOT_DIR%\cpp_worker_cube"
    start "OpenAI_Worker_CPP" /b worker_cube.exe node_cpp_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_cpp.log" 2>&1
)

rem 9. Start Rust Worker (if compiled)
if "%HAVE_RUST%"=="true" (
    echo [Cluster Windows] Starting Rust worker (node_rust_1)...
    cd /d "%ROOT_DIR%\rust_worker_cube"
    start "OpenAI_Worker_Rust" /b rust_worker_bin.exe node_rust_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_rust.log" 2>&1
)

rem 10. Start Go Worker (if compiled)
if "%HAVE_GO%"=="true" (
    echo [Cluster Windows] Starting Go worker (node_go_1)...
    cd /d "%ROOT_DIR%\go_worker_cube"
    start "OpenAI_Worker_Go" /b worker_cube.exe node_go_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_go.log" 2>&1
)

timeout /t 1 /nobreak >nul
echo [Cluster Windows] Cluster nodes online. Launching Desktop GUI Client...

rem 11. Launch Desktop GUI Client
cd /d "%ROOT_DIR%\java_server_orchestrator"
java -cp bin client.DesktopChatGui "" 127.0.0.1 %CLIENT_PORT%

echo [Cluster Windows] Client closed. To terminate background worker processes, execute scripts\stop_cluster.bat
