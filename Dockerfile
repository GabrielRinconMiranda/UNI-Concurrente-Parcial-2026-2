# Open AI Cubes: Entorno contenerizado para desarrollo y pruebas locales
# NOTA: Este contenedor es una herramienta auxiliar para pruebas y no reemplaza
# el despliegue nativo ni las condiciones de entrega de la rubrica academica.

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    python3 \
    g++ \
    libgomp1 \
    golang-go \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Copiar fuentes y base de datos
COPY dataset /workspace/dataset
COPY java_server_orchestrator /workspace/java_server_orchestrator
COPY python_worker_cube /workspace/python_worker_cube
COPY cpp_worker_cube /workspace/cpp_worker_cube
COPY rust_worker_cube /workspace/rust_worker_cube
COPY go_worker_cube /workspace/go_worker_cube
COPY scripts /workspace/scripts

# Compilar modulos Java
RUN cd /workspace/java_server_orchestrator && \
    mkdir -p bin && \
    javac -d bin $(find src -name "*.java")

# Compilar microcubo C++ con OpenMP
RUN cd /workspace/cpp_worker_cube && \
    g++ -std=c++17 -O2 -fopenmp worker_cube.cpp -o worker_cube

# Compilar microcubo Go
RUN cd /workspace/go_worker_cube && \
    go build -o worker_cube worker_cube.go micro_chunks.go

EXPOSE 9010 8080

CMD ["bash", "-c", "cd /workspace/java_server_orchestrator && java -cp bin server.ServerOrchestrator 9010 8080"]

