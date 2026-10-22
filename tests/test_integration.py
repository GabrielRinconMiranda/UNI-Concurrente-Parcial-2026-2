"""
System Integration Test Suite for Open AI Cubes.
Verifies end-to-end communication between:
- Central Server (Java)
- Java Worker Node (LP1)
- Python Worker Node (LP2)
- C++ Worker Node (LP3)
- Client Query Interface across all 4 execution modes:
  1. SEQUENTIAL
  2. PARALLEL
  3. DISTRIBUTED
  4. PARALLEL_DISTRIBUTED
"""

import os
import signal
import socket
import subprocess
import sys
import time

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
WORKER_PORT = 9020
CLIENT_PORT = 8095

procs = []


def cleanup():
    print("\n[Cleanup] Terminating test processes...")
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


def send_query(mode: str, sample: str) -> tuple[str, str, float]:
    """Sets mode, queries the server, and parses response."""
    with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=3.0) as s:
        f_in = s.makefile("r", encoding="utf-8")
        f_out = s.makefile("w", encoding="utf-8")

        # Set mode
        f_out.write(f"SET_MODE|test_cli|{mode}\n")
        f_out.flush()
        ack = f_in.readline()
        assert "SET_MODE_ACK" in ack, f"Mode set failed: {ack}"

        # Send query
        f_out.write(f"CLIENT_REQ|0812|{sample}\n")
        f_out.flush()
        resp = f_in.readline()
        assert resp, "Empty response from server"

        # Format: CLIENT_RESP|<client_id>|<label>|<confidence>|<mode>|<latency_ms>
        parts = resp.strip().split("|")
        assert parts[0] == "CLIENT_RESP", f"Unexpected response action: {resp}"
        label = parts[2]
        conf = float(parts[3])
        latency_ms = float(parts[5])
        handler_info = parts[7] if len(parts) > 7 else ""
        if label not in ["panda", "cat", "dog", "bear", "lion"]:
            print(f"\n[DEBUG ERROR] Query response: {resp.strip()}")
            print(f"[DEBUG ERROR] Handler info: {handler_info}")
        return label, parts[4], latency_ms


def main():
    print("[Integration] Starting system integration tests")
    try:
        # 1. Compile Java
        print("[Integration] Compiling Java server and worker...")
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

        # 2. Compile C++ worker
        print("  -> Compiling C++ worker with OpenMP...")
        subprocess.check_call(
            ["g++", "-std=c++17", "-O2", "-fopenmp", "worker_cube.cpp", "-o", "worker_cube_bin"],
            cwd=os.path.join(PROJECT_ROOT, "cpp_worker_cube")
        )

        # 3. Compile Rust worker
        print("  -> Compiling Rust worker...")
        subprocess.check_call(
            ["rustc", "--edition", "2021", "-O", "src/main.rs", "-o", "rust_worker_bin"],
            cwd=os.path.join(PROJECT_ROOT, "rust_worker_cube")
        )

        # 3.1 Compile Go worker
        print("  -> Compiling Go worker...")
        go_bin = "go"
        if not subprocess.run(["which", "go"], capture_output=True).returncode == 0:
            local_go = os.path.join(PROJECT_ROOT, "bin", "go_dist", "bin", "go")
            if os.path.exists(local_go):
                go_bin = local_go
        subprocess.check_call(
            [go_bin, "build", "-o", "worker_cube_go"],
            cwd=os.path.join(PROJECT_ROOT, "go_worker_cube")
        )

        # 4. Start Java Central Server
        print("  -> Starting Java Central Server...")
        server_proc = subprocess.Popen(
            ["java", "-cp", "bin", "server.ServerOrchestrator", str(WORKER_PORT), str(CLIENT_PORT)],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        procs.append(server_proc)

        assert wait_for_port(WORKER_PORT, timeout=5.0), f"Server failed to open worker port {WORKER_PORT}"
        assert wait_for_port(CLIENT_PORT, timeout=5.0), f"Server failed to open client port {CLIENT_PORT}"
        print("  [OK] Central Server is online.")

        # 5. Start Java Worker Cube (LP1)
        print("  -> Starting Java Worker Cube (node_java_1)...")
        w_java = subprocess.Popen(
            ["java", "-cp", "bin", "worker.JavaWorkerCube", "node_java_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        procs.append(w_java)

        # 6. Start Python Worker Cube (LP2)
        print("  -> Starting Python Worker Cube (node_py_1)...")
        w_py = subprocess.Popen(
            [sys.executable, "worker_cube.py", "node_py_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "python_worker_cube"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        procs.append(w_py)

        # 7. Start C++ Worker Cube (LP3)
        print("  -> Starting C++ Worker Cube (node_cpp_1)...")
        w_cpp = subprocess.Popen(
            ["./worker_cube_bin", "node_cpp_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "cpp_worker_cube"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        procs.append(w_cpp)

        # 8. Start Rust Worker Cube (LP4)
        print("  -> Starting Rust Worker Cube (node_rust_1)...")
        w_rust = subprocess.Popen(
            ["./rust_worker_bin", "node_rust_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "rust_worker_cube"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        procs.append(w_rust)

        # 8.1 Start Go Worker Cube (LP5)
        print("  -> Starting Go Worker Cube (node_go_1)...")
        w_go = subprocess.Popen(
            ["./worker_cube_go", "node_go_1", "127.0.0.1", str(WORKER_PORT), "4"],
            cwd=os.path.join(PROJECT_ROOT, "go_worker_cube"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        procs.append(w_go)

        time.sleep(1.2)
        print("  [OK] All 5 worker nodes (Java, Python, C++, Rust, Go) registered.")

        # 9. Verify Cluster Workers Query (GET_WORKERS)
        with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=3.0) as s:
            f_in = s.makefile("r", encoding="utf-8")
            f_out = s.makefile("w", encoding="utf-8")
            f_out.write("GET_WORKERS|cli_test|ALL\n")
            f_out.flush()
            workers_resp = f_in.readline()
            print(f"  [Cluster Topology] Active workers: {workers_resp.strip()}")
            assert "node_java_1" in workers_resp
            assert "node_py_1" in workers_resp
            assert "node_cpp_1" in workers_resp
            assert "node_rust_1" in workers_resp
            assert "node_go_1" in workers_resp

        # 10. Test All 4 Execution Modes
        modes = ["SEQUENTIAL", "PARALLEL", "DISTRIBUTED", "PARALLEL_DISTRIBUTED"]
        test_samples = ["panda", "cat", "dog"]

        for mode in modes:
            print(f"\n[Integration] Testing mode: {mode}")
            for sample_input in test_samples:
                label, mode_used, latency = send_query(mode, sample_input)
                assert label in ["panda", "cat", "dog", "bear", "lion"], f"Invalid label: {label} in {mode}"
                assert mode_used == mode, f"Mode mismatch: expected {mode}, got {mode_used}"
                print(f"  [PASS] Input: {sample_input:5s} -> Result: {label:5s} in {latency:.2f} ms [Mode: {mode_used}]")

        # 11. Concurrent Multi-Client Test
        print("\n[Integration] Testing Concurrent Multi-Client Interface...")
        import concurrent.futures

        def client_task(client_num: int, sample: str) -> tuple[int, str, float]:
            client_id = f"idClientChat_{client_num:04d}"
            with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=5.0) as s:
                f_in = s.makefile("r", encoding="utf-8")
                f_out = s.makefile("w", encoding="utf-8")
                f_out.write(f"CLIENT_REQ|{client_id}|{sample}\n")
                f_out.flush()
                resp = f_in.readline().strip()
                parts = resp.split("|")
                assert parts[0] == "CLIENT_RESP", f"Invalid response: {resp}"
                assert parts[1] == client_id, f"Client ID mismatch: expected {client_id}, got {parts[1]}"
                return client_num, parts[2], float(parts[5])

        client_queries = [
            (812, "panda"),
            (1045, "cat"),
            (2026, "dog"),
            (7777, "bear"),
            (9999, "lion")
        ]

        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(client_task, c_id, smp) for c_id, smp in client_queries]
            for fut in concurrent.futures.as_completed(futures):
                c_id, label, latency = fut.result()
                print(f"  [PASS] Client idClientChat_{c_id:04d} concurrent response: {label} in {latency:.2f} ms")

        # 12. Test Training Pipeline Across Modes
        print("\n[Integration] Testing Decoupled Model Training Pipeline...")
        training_modes = ["SEQUENTIAL", "PARALLEL", "DISTRIBUTED", "PARALLEL_DISTRIBUTED"]
        for t_mode in training_modes:
            with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=45.0) as s:
                f_in = s.makefile("r", encoding="utf-8")
                f_out = s.makefile("w", encoding="utf-8")
                f_out.write(f"TRAIN_REQ|train_cli|{t_mode}\n")
                f_out.flush()
                resp = f_in.readline().strip()
                parts = resp.split("|")
                assert parts[0] == "TRAIN_RESP", f"Invalid training response: {resp}"
                samples = int(parts[2])
                mode_used = parts[3]
                duration_ms = float(parts[4])
                weights_path = parts[6]
                print(f"  [PASS] Training [{mode_used}]: {samples} samples in {duration_ms:.2f} ms -> {weights_path}")

        # 13. Test GUI 785-Token Image Payload (Leading Label + 784 Pixels)
        print("\n[Integration] Testing GUI 785-Token Image Payload (Prevent Regression)...")
        with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=5.0) as s:
            f_in = s.makefile("r", encoding="utf-8")
            f_out = s.makefile("w", encoding="utf-8")
            # 785 tokens: label + 784 zeros
            dummy_payload = "panda," + ",".join(["0.0"] * 784)
            f_out.write(f"CLIENT_REQ|0812|{dummy_payload}\n")
            f_out.flush()
            resp = f_in.readline().strip()
            parts = resp.split("|")
            assert parts[0] == "CLIENT_RESP", f"785-token payload failed: {resp}"
            print(f"  [PASS] 785-token GUI payload handled successfully: Result={parts[2]} in {parts[5]} ms")

        print("\n[Integration] All integration, training, and multi-client tests passed successfully")

    finally:
        cleanup()
        # Clean test binaries
        cpp_bin = os.path.join(PROJECT_ROOT, "cpp_worker_cube", "worker_cube_bin")
        if os.path.exists(cpp_bin):
            os.remove(cpp_bin)
        rust_bin = os.path.join(PROJECT_ROOT, "rust_worker_cube", "rust_worker_bin")
        if os.path.exists(rust_bin):
            os.remove(rust_bin)


if __name__ == "__main__":
    main()


