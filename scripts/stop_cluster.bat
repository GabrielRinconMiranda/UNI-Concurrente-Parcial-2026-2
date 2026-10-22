@echo off
rem Open AI Cubes: Windows Cluster Process Termination Script
echo [Cluster Windows] Stopping cluster processes on ports 9010, 8085, and 8080...

rem Free ports by terminating associated processes
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :9010') do (
    taskkill /F /PID %%a >nul 2>&1
)

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8085') do (
    taskkill /F /PID %%a >nul 2>&1
)

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8080') do (
    taskkill /F /PID %%a >nul 2>&1
)

rem Terminate specific executables if orphaned
taskkill /F /IM worker_cube.exe >nul 2>&1
taskkill /F /IM rust_worker_bin.exe >nul 2>&1

echo [Cluster Windows] Cluster processes stopped successfully.
