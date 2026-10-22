#!/bin/bash
# Diagnóstico de entorno y dependencias para Linux y macOS

echo "========================================================"
echo " Verificación del entorno de desarrollo (Open AI Cubes)"
echo "========================================================"

MISSING=0

# 1. Java
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -n 1)
    echo "[OK] Java SDK detectado: $JAVA_VER"
else
    echo "[FALTA] Java JDK no encontrado (requerido JDK 8 o superior)."
    echo "       Instalación sugerida (Ubuntu/Debian): sudo apt install -y openjdk-21-jdk"
    echo "       Instalación sugerida (Fedora): sudo dnf install -y java-21-openjdk-devel"
    echo "       Instalación sugerida (Arch): sudo pacman -S jdk-openjdk"
    MISSING=$((MISSING + 1))
fi

# 2. Python
if command -v python3 >/dev/null 2>&1; then
    PY_VER=$(python3 --version)
    echo "[OK] Python detectado: $PY_VER"
else
    echo "[FALTA] Python 3 no encontrado (requerido Python 3.8 o superior)."
    echo "       Instalación sugerida: sudo apt install -y python3 python3-pip"
    MISSING=$((MISSING + 1))
fi

# 3. C++ con OpenMP
if command -v g++ >/dev/null 2>&1; then
    CPP_VER=$(g++ --version | head -n 1)
    echo "[OK] Compilador C++ detectado: $CPP_VER"
else
    echo "[FALTA] g++ no encontrado (requerido para el microcubo C++)."
    echo "       Instalación sugerida: sudo apt install -y build-essential"
    MISSING=$((MISSING + 1))
fi

# 4. Rust (Opcional complementario)
if command -v rustc >/dev/null 2>&1; then
    RUST_VER=$(rustc --version)
    echo "[OK] Compilador Rust detectado: $RUST_VER"
else
    echo "[AVISO] Rust no encontrado (nodo complementario opcional)."
    echo "        Instalación sugerida: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
fi

echo "========================================================"
if [ $MISSING -eq 0 ]; then
    echo " Todos los componentes obligatorios están listos."
else
    echo " Se encontraron $MISSING componentes obligatorios ausentes."
    echo " Por favor, instale las herramientas indicadas o utilice Docker."
fi
echo "========================================================"

