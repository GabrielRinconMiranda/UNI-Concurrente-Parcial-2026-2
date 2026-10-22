@echo off
rem Detencion de procesos del cluster en Windows (Open AI Cubes)
echo [Cluster Windows] Deteniendo procesos del cluster en puertos 9010 y 8080...

rem Liberar puertos cerrando procesos asociados
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :9010') do (
    taskkill /F /PID %%a >nul 2>&1
)

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8080') do (
    taskkill /F /PID %%a >nul 2>&1
)

rem Detener ejecutables especificos si quedaron huerfanos
taskkill /F /IM worker_cube.exe >nul 2>&1
taskkill /F /IM rust_worker_bin.exe >nul 2>&1

echo [Cluster Windows] Procesos del cluster finalizados exitosamente.

