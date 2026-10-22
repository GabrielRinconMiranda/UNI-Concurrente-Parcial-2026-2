@echo off
rem Open AI Cubes: Lanzador unificado de cluster local para Windows
setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
cd /d "%ROOT_DIR%"

set WORKER_PORT=9010
set CLIENT_PORT=8080

echo [Cluster Windows] Inicializando cluster Open AI Cubes...

rem 1. Compilar Java
echo [Cluster Windows] Compilando modulos Java...
cd /d "%ROOT_DIR%\java_server_orchestrator"
if not exist "bin" mkdir bin
dir /s /b src\*.java > sources.txt
javac -d bin @sources.txt
del sources.txt

rem 2. Compilar C++ con OpenMP (si g++ esta disponible)
echo [Cluster Windows] Compilando microcubo C++...
cd /d "%ROOT_DIR%\cpp_worker_cube"
where g++ >nul 2>nul
if %errorlevel% equ 0 (
    g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube.exe
    set HAVE_CPP=true
) else (
    echo [AVISO] g++ no detectado en PATH. El microcubo C++ no se iniciara.
    set HAVE_CPP=false
)

rem 3. Compilar Rust (si rustc esta disponible)
cd /d "%ROOT_DIR%\rust_worker_cube"
where rustc >nul 2>nul
if %errorlevel% equ 0 (
    echo [Cluster Windows] Compilando microcubo Rust...
    rustc --edition 2021 -O src\main.rs -o rust_worker_bin.exe
    set HAVE_RUST=true
) else (
    set HAVE_RUST=false
)

rem 3.1 Compilar Go (si go esta disponible)
cd /d "%ROOT_DIR%\go_worker_cube"
where go >nul 2>nul
if %errorlevel% equ 0 (
    echo [Cluster Windows] Compilando microcubo Go...
    go build -o worker_cube.exe
    set HAVE_GO=true
) else (
    set HAVE_GO=false
)

rem 4. Iniciar Servidor Central
echo [Cluster Windows] Iniciando Servidor Central (puertos %WORKER_PORT% y %CLIENT_PORT%)...
cd /d "%ROOT_DIR%\java_server_orchestrator"
start "OpenAI_Server" /b java -cp bin server.ServerOrchestrator %WORKER_PORT% %CLIENT_PORT% > "%TEMP%\openai_server.log" 2>&1
timeout /t 1 /nobreak >nul

rem 5. Iniciar Trabajador Java
echo [Cluster Windows] Iniciando nodo Java (node_java_1)...
start "OpenAI_Worker_Java" /b java -cp bin worker.JavaWorkerCube node_java_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_java.log" 2>&1

rem 6. Iniciar Trabajador Python
echo [Cluster Windows] Iniciando nodo Python (node_py_1)...
cd /d "%ROOT_DIR%\python_worker_cube"
start "OpenAI_Worker_Python" /b python worker_cube.py node_py_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_py.log" 2>&1

rem 7. Iniciar Trabajador C++ (si se compilo)
if "%HAVE_CPP%"=="true" (
    echo [Cluster Windows] Iniciando nodo C++ (node_cpp_1)...
    cd /d "%ROOT_DIR%\cpp_worker_cube"
    start "OpenAI_Worker_CPP" /b worker_cube.exe node_cpp_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_cpp.log" 2>&1
)

rem 8. Iniciar Trabajador Rust (si se compilo)
if "%HAVE_RUST%"=="true" (
    echo [Cluster Windows] Iniciando nodo Rust (node_rust_1)...
    cd /d "%ROOT_DIR%\rust_worker_cube"
    start "OpenAI_Worker_Rust" /b rust_worker_bin.exe node_rust_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_rust.log" 2>&1
)

rem 9. Iniciar Trabajador Go (si se compilo)
if "%HAVE_GO%"=="true" (
    echo [Cluster Windows] Iniciando nodo Go (node_go_1)...
    cd /d "%ROOT_DIR%\go_worker_cube"
    start "OpenAI_Worker_Go" /b worker_cube.exe node_go_1 127.0.0.1 %WORKER_PORT% 4 > "%TEMP%\openai_worker_go.log" 2>&1
)

timeout /t 1 /nobreak >nul
echo [Cluster Windows] Nodos en linea. Lanzando interfaz grafica...

rem 9. Lanzar Desktop GUI
cd /d "%ROOT_DIR%\java_server_orchestrator"
java -cp bin client.DesktopChatGui 0812 127.0.0.1 %CLIENT_PORT%

echo [Cluster Windows] Interfaz cerrada. Para detener procesos de fondo ejecute scripts\stop_cluster.bat

