@echo off
rem Diagnóstico de entorno y dependencias para Windows (Open AI Cubes)
setlocal EnableDelayedExpansion

echo ========================================================
echo  Verificacion del entorno de desarrollo en Windows
echo ========================================================

set MISSING=0

rem 1. Java
where javac >nul 2>nul
if %errorlevel% equ 0 (
    for /f "tokens=*" %%i in ('java -version 2^>^&1') do (
        echo [OK] Java detectado: %%i
        goto :check_python
    )
) else (
    echo [FALTA] Java JDK no encontrado en PATH (requerido JDK 8 o superior).
    echo        Instalacion con winget: winget install Microsoft.OpenJDK.21
    echo        Configuracion de PATH: anadir C:\Program Files\Microsoft\jdk-21...\bin a las Variables de Entorno.
    set /a MISSING+=1
)

:check_python
rem 2. Python
where python >nul 2>nul
if %errorlevel% equ 0 (
    for /f "tokens=*" %%i in ('python --version 2^>^&1') do echo [OK] Python detectado: %%i
) else (
    echo [FALTA] Python no encontrado en PATH (requerido Python 3.8 o superior).
    echo        Instalacion con winget: winget install Python.Python.3.11
    echo        Recuerde marcar la casilla "Add Python to PATH" durante el instalador.
    set /a MISSING+=1
)

rem 3. Compilador C++ (g++)
where g++ >nul 2>nul
if %errorlevel% equ 0 (
    for /f "tokens=*" %%i in ('g++ --version 2^>^&1') do (
        echo [OK] Compilador C++ detectado: %%i
        goto :check_rust
    )
) else (
    echo [FALTA] g++ (MinGW/MSYS2) no encontrado en PATH.
    echo        Instalacion sugerida con winget: winget install MSYS2.MSYS2
    echo        Luego en MSYS2 ejecutar: pacman -S --noconfirm mingw-w64-ucrt-x86_64-gcc
    echo        Y anadir C:\msys64\ucrt64\bin a la variable PATH de Windows.
    set /a MISSING+=1
)

:check_rust
rem 4. Rust (Opcional complementario)
where rustc >nul 2>nul
if %errorlevel% equ 0 (
    for /f "tokens=*" %%i in ('rustc --version 2^>^&1') do echo [OK] Compilador Rust detectado: %%i
) else (
    echo [AVISO] Rust no encontrado en PATH (nodo complementario opcional).
    echo        Instalacion con winget: winget install Rustlang.Rustup
)

echo ========================================================
if %MISSING% equ 0 (
    echo  Todos los componentes obligatorios estan listos en Windows.
) else (
    echo  Se encontraron %MISSING% componentes obligatorios ausentes o fuera de PATH.
    echo  Siga las instrucciones anteriores o utilice Docker Desktop.
)
echo ========================================================
pause

