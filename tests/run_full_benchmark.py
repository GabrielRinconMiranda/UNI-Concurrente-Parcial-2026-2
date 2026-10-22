"""
Automated Multi-Mode Performance Benchmark Runner.
Coordinates:
- Java Central Server
- Distributed Cluster Workers (Java, Python, C++)
- Executes saturated stress test across all 4 execution modes:
  1. SEQUENTIAL
  2. PARALLEL
  3. DISTRIBUTED
  4. PARALLEL_DISTRIBUTED
Outputs structured metrics for reporting and visualization.
"""

import json
import os
import signal
import socket
import subprocess
import sys
import time

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
WORKER_PORT = 9030
CLIENT_PORT = 8098
procs = []


def cleanup():
    print("[Cleanup] Stopping benchmark server and workers...")
    for p in procs:
        try:
            p.send_signal(signal.SIGINT)
            p.wait(timeout=1)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
    procs.clear()


def wait_for_port(port, timeout=5.0):
    start = time.time()
    while time.time() - start < timeout:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.1)
    return False


def main():
    print("[Benchmark] Preparing automated cluster benchmark...")
    try:
        # 1. Compile Java
        java_src = subprocess.check_output(
            ["find", "src", "-name", "*.java"],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            text=True
        ).split()
        os.makedirs(os.path.join(PROJECT_ROOT, "java_server_orchestrator", "bin"), exist_ok=True)
        subprocess.check_call(
            ["javac", "-d", "bin"] + java_src,
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator")
        )

        # 2. Compile C++
        subprocess.check_call(
            ["g++", "-std=c++17", "-O2", "worker_cube.cpp", "-o", "worker_cube_bin"],
            cwd=os.path.join(PROJECT_ROOT, "cpp_worker_cube")
        )

        # 3. Start Server
        server_proc = subprocess.Popen(
            ["java", "-cp", "bin", "server.ServerOrchestrator", str(WORKER_PORT), str(CLIENT_PORT)],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        procs.append(server_proc)
        assert wait_for_port(WORKER_PORT), "Server worker port timeout"
        assert wait_for_port(CLIENT_PORT), "Server client port timeout"

        # 4. Start Workers: Java, Python, C++
        w_java = subprocess.Popen(
            ["java", "-cp", "bin", "worker.JavaWorkerCube", "node_java_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        procs.append(w_java)

        w_py = subprocess.Popen(
            [sys.executable, "worker_cube.py", "node_py_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "python_worker_cube"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        procs.append(w_py)

        w_cpp = subprocess.Popen(
            ["./worker_cube_bin", "node_cpp_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "cpp_worker_cube"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        procs.append(w_cpp)

        time.sleep(1.0)
        print("[Benchmark] Central Server and 3 heterogeneous workers active.")

        # 5. Import and run stress benchmark
        sys.path.insert(0, os.path.join(PROJECT_ROOT, "python_worker_cube"))
        from stress_benchmark import run_benchmark

        modes = ["SEQUENTIAL", "PARALLEL", "DISTRIBUTED", "PARALLEL_DISTRIBUTED"]
        all_metrics = []

        for mode in modes:
            # 100 requests per mode, concurrency 10, random delay 5ms - 20ms
            res = run_benchmark(
                mode=mode,
                num_requests=100,
                concurrency=10,
                min_delay_ms=5.0,
                max_delay_ms=20.0,
                host="127.0.0.1",
                port=CLIENT_PORT
            )
            all_metrics.append(res)
            time.sleep(0.5)

        # Save results to docs/benchmark_results.json
        out_path = os.path.join(PROJECT_ROOT, "docs", "benchmark_results.json")
        with open(out_path, "w") as f:
            json.dump(all_metrics, f, indent=2)
        print(f"[Benchmark] Saved metrics to {out_path}")

    finally:
        cleanup()
        cpp_bin = os.path.join(PROJECT_ROOT, "cpp_worker_cube", "worker_cube_bin")
        if os.path.exists(cpp_bin):
            os.remove(cpp_bin)
        java_bin = os.path.join(PROJECT_ROOT, "java_server_orchestrator", "bin")
        if os.path.exists(java_bin):
            subprocess.call(["rm", "-rf", java_bin])


if __name__ == "__main__":
    main()

