#!/usr/bin/env python3
"""
scripts/run_cluster_benchmark.py

Comprehensive Multi-Cube Multi-Client Cluster Benchmark Engine
Evaluates throughput, latency distribution, Amdahl speedup, and parallel efficiency
across varying active worker cubes (K in {1, 2, 3, 4, 5}) and concurrent client loads (C in {1, 5, 10, 20}).

Data is saved to:
  - docs/benchmark_multicube_data.csv (raw observations)
  - docs/benchmark_multicube_summary.json (statistical aggregates)
  - docs/benchmark_multicube_summary.txt (formatted text report)
"""

import os
import sys
import time
import json
import csv
import socket
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
WORKER_PORT = 9120
CLIENT_PORT = 8195

TEST_IMAGES = ["panda", "cat", "dog", "bear", "lion"]

CUBE_CONFIGURATIONS = [
    (1, ["C++"]),
    (2, ["C++", "Rust"]),
    (3, ["C++", "Rust", "Go"]),
    (4, ["C++", "Rust", "Go", "Java"]),
    (5, ["C++", "Rust", "Go", "Java", "Python"])
]

CONCURRENCY_LEVELS = [1, 5, 10, 20]
REPETITIONS_PER_CONDITION = 5
WARMUP_REPETITIONS = 2


def wait_for_port(port: int, host: str = "127.0.0.1", timeout: float = 6.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.4):
                return True
        except (OSError, ConnectionRefusedError):
            time.sleep(0.08)
    return False


def send_client_request(client_id: str, sample_label: str) -> tuple[str, float, float]:
    """
    Sends a single client inference request via standard TCP socket.
    Returns: (predicted_label, confidence, latency_ms)
    """
    t0 = time.perf_counter()
    with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=15.0) as s:
        f_in = s.makefile("r", encoding="utf-8")
        f_out = s.makefile("w", encoding="utf-8")
        f_out.write(f"CLIENT_REQ|{client_id}|{sample_label}\n")
        f_out.flush()
        line = f_in.readline()
        if not line:
            raise RuntimeError(f"Server closed connection unexpectedly for {client_id}")
        t1 = time.perf_counter()
        
        parts = line.strip().split("|")
        if parts[0] != "CLIENT_RESP":
            raise RuntimeError(f"Invalid protocol response: {line.strip()}")
        label = parts[2]
        conf = float(parts[3])
        latency_ms = (t1 - t0) * 1000.0
        return label, conf, latency_ms


def set_server_mode(mode_name: str):
    with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=5.0) as s:
        f_in = s.makefile("r", encoding="utf-8")
        f_out = s.makefile("w", encoding="utf-8")
        f_out.write(f"SET_MODE|bench_mgr|{mode_name}\n")
        f_out.flush()
        resp = f_in.readline()
        assert "MODE_ACK" in resp or "ACK" in resp, f"Failed to set mode {mode_name}: {resp}"


def set_server_cube_config(max_cubes: int, languages: list[str]) -> int:
    langs_str = ",".join(languages)
    with socket.create_connection(("127.0.0.1", CLIENT_PORT), timeout=5.0) as s:
        f_in = s.makefile("r", encoding="utf-8")
        f_out = s.makefile("w", encoding="utf-8")
        f_out.write(f"SET_CUBES_CONFIG|bench_mgr|{max_cubes}|{langs_str}\n")
        f_out.flush()
        resp = f_in.readline().strip()
        parts = resp.split("|")
        # Format: SET_CUBES_CONFIG_ACK|<id>|<active_count>|<nodes_csv>
        active_count = int(parts[2]) if len(parts) > 2 else max_cubes
        return active_count


def run_batch(concurrency: int, batch_id: str) -> dict:
    """
    Executes C concurrent client requests and records latency and throughput.
    """
    latencies = []
    labels = []
    
    t_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = []
        for i in range(concurrency):
            cid = f"client_{batch_id}_{i:03d}"
            sample = TEST_IMAGES[i % len(TEST_IMAGES)]
            futures.append(executor.submit(send_client_request, cid, sample))
            
        for fut in as_completed(futures):
            label, conf, lat = fut.result()
            latencies.append(lat)
            labels.append(label)
    t_end = time.perf_counter()
    
    total_sec = max(t_end - t_start, 0.0001)
    throughput = concurrency / total_sec
    latencies.sort()
    
    mean_lat = sum(latencies) / len(latencies)
    min_lat = latencies[0]
    p50_lat = latencies[len(latencies) // 2]
    p95_idx = min(int(len(latencies) * 0.95), len(latencies) - 1)
    p95_lat = latencies[p95_idx]
    max_lat = latencies[-1]
    
    return {
        "concurrency": concurrency,
        "total_time_ms": total_sec * 1000.0,
        "throughput_req_per_sec": throughput,
        "mean_latency_ms": mean_lat,
        "min_latency_ms": min_lat,
        "p50_latency_ms": p50_lat,
        "p95_latency_ms": p95_lat,
        "max_latency_ms": max_lat,
        "success_rate": 100.0
    }


def main():
    print("=" * 80)
    print("   OPEN AI CUBES — MOTOR DE BENCHMARK MULTI-CUBO Y MULTI-CLIENTE")
    print("=" * 80)
    
    os.makedirs(os.path.join(PROJECT_ROOT, "docs"), exist_ok=True)
    os.makedirs(os.path.join(PROJECT_ROOT, "docs", "figures"), exist_ok=True)
    
    csv_file_path = os.path.join(PROJECT_ROOT, "docs", "benchmark_multicube_data.csv")
    json_file_path = os.path.join(PROJECT_ROOT, "docs", "benchmark_multicube_summary.json")
    txt_file_path = os.path.join(PROJECT_ROOT, "docs", "benchmark_multicube_summary.txt")

    procs = []

    def cleanup():
        print("\n[Benchmark] Deteniendo procesos del clúster...")
        for p in procs:
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(timeout=1.5)
                except Exception:
                    p.kill()

    try:
        # 1. Compile Java
        print("  -> Compilando orquestador y nodo Java...")
        java_src = subprocess.check_output(
            ["find", "src", "-name", "*.java"],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            text=True
        ).split()
        subprocess.check_call(
            ["javac", "-d", "bin"] + java_src,
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator")
        )

        # 2. Compile C++
        print("  -> Compilando cubo C++ (OpenMP)...")
        subprocess.check_call(
            ["g++", "-std=c++17", "-O2", "-fopenmp", "worker_cube.cpp", "-o", "worker_cube_bench"],
            cwd=os.path.join(PROJECT_ROOT, "cpp_worker_cube")
        )

        # 3. Compile Rust
        print("  -> Compilando cubo Rust...")
        subprocess.check_call(
            ["rustc", "--edition", "2021", "-O", "src/main.rs", "-o", "rust_worker_bench"],
            cwd=os.path.join(PROJECT_ROOT, "rust_worker_cube")
        )

        # 4. Compile Go
        print("  -> Compilando cubo Go...")
        go_bin = "go"
        local_go = os.path.join(PROJECT_ROOT, "bin", "go_dist", "bin", "go")
        if os.path.exists(local_go):
            go_bin = local_go
        subprocess.check_call(
            [go_bin, "build", "-o", "worker_cube_bench_go"],
            cwd=os.path.join(PROJECT_ROOT, "go_worker_cube")
        )

        # 5. Start Central Server
        print(f"  -> Iniciando Servidor Central (Puertos: worker={WORKER_PORT}, client={CLIENT_PORT})...")
        server_p = subprocess.Popen(
            ["java", "-cp", "bin", "server.ServerOrchestrator", str(WORKER_PORT), str(CLIENT_PORT)],
            cwd=os.path.join(PROJECT_ROOT, "java_server_orchestrator"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        procs.append(server_p)

        assert wait_for_port(WORKER_PORT, timeout=6.0), "Error abriendo puerto de workers del servidor"
        assert wait_for_port(CLIENT_PORT, timeout=6.0), "Error abriendo puerto de clientes del servidor"
        print("  [OK] Servidor Central activo y escuchando.")

        # 6. Start all 5 Workers
        workers_def = [
            ("Java", ["java", "-cp", "bin", "worker.JavaWorkerCube", "node_java_1", "127.0.0.1", str(WORKER_PORT), "4"], "java_server_orchestrator"),
            ("Python", [sys.executable, "worker_cube.py", "node_py_1", "127.0.0.1", str(WORKER_PORT), "4"], "python_worker_cube"),
            ("C++", ["./worker_cube_bench", "node_cpp_1", "127.0.0.1", str(WORKER_PORT), "4"], "cpp_worker_cube"),
            ("Rust", ["./rust_worker_bench", "node_rust_1", "127.0.0.1", str(WORKER_PORT), "4"], "rust_worker_cube"),
            ("Go", ["./worker_cube_bench_go", "node_go_1", "127.0.0.1", str(WORKER_PORT), "4"], "go_worker_cube")
        ]

        for w_lang, w_cmd, w_dir in workers_def:
            print(f"  -> Levantando cubo {w_lang}...")
            wp = subprocess.Popen(
                w_cmd,
                cwd=os.path.join(PROJECT_ROOT, w_dir),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL
            )
            procs.append(wp)

        time.sleep(1.5)

        # 7. Set mode to PARALLEL_DISTRIBUTED
        set_server_mode("PARALLEL_DISTRIBUTED")
        print("  [OK] Modo de clúster establecido: PARALLEL_DISTRIBUTED")

        # Prepare CSV Writer
        csv_rows = []
        raw_fieldnames = [
            "timestamp", "num_cubes", "cubes_list", "concurrency",
            "repetition", "total_time_ms", "throughput_req_per_sec",
            "mean_latency_ms", "min_latency_ms", "p50_latency_ms",
            "p95_latency_ms", "max_latency_ms"
        ]

        summary_records = []

        print("\n" + "=" * 80)
        print("   EJECUCIÓN DE BENCHMARKS MULTI-CUBO Y MULTI-CLIENTE")
        print("=" * 80)

        # Baseline throughput for speedup calculation (K=1, C=1)
        baseline_throughput_c1 = 1.0

        for k, langs in CUBE_CONFIGURATIONS:
            langs_joined = ",".join(langs)
            active_k = set_server_cube_config(k, langs)
            time.sleep(0.3)
            print(f"\n>>> CONFIGURACIÓN: {k} Cubo(s) Activo(s) [{langs_joined}] (Confirmados: {active_k})")

            for c in CONCURRENCY_LEVELS:
                print(f"  * Evaluando con C={c:2d} cliente(s) concurrente(s)...", end="", flush=True)

                # Warmup
                for w in range(WARMUP_REPETITIONS):
                    run_batch(c, f"warm_k{k}_c{c}_{w}")

                # Measurement repetitions
                batch_results = []
                for rep in range(1, REPETITIONS_PER_CONDITION + 1):
                    res = run_batch(c, f"k{k}_c{c}_r{rep}")
                    batch_results.append(res)

                    row = {
                        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                        "num_cubes": k,
                        "cubes_list": langs_joined,
                        "concurrency": c,
                        "repetition": rep,
                        "total_time_ms": round(res["total_time_ms"], 2),
                        "throughput_req_per_sec": round(res["throughput_req_per_sec"], 2),
                        "mean_latency_ms": round(res["mean_latency_ms"], 2),
                        "min_latency_ms": round(res["min_latency_ms"], 2),
                        "p50_latency_ms": round(res["p50_latency_ms"], 2),
                        "p95_latency_ms": round(res["p95_latency_ms"], 2),
                        "max_latency_ms": round(res["max_latency_ms"], 2)
                    }
                    csv_rows.append(row)

                # Aggregate stats for this (K, C)
                mean_tp = sum(r["throughput_req_per_sec"] for r in batch_results) / len(batch_results)
                mean_lat = sum(r["mean_latency_ms"] for r in batch_results) / len(batch_results)
                mean_p95 = sum(r["p95_latency_ms"] for r in batch_results) / len(batch_results)
                min_lat = min(r["min_latency_ms"] for r in batch_results)

                if k == 1 and c == 1:
                    baseline_throughput_c1 = mean_tp

                summary_records.append({
                    "num_cubes": k,
                    "cubes_list": langs_joined,
                    "concurrency": c,
                    "mean_throughput": round(mean_tp, 2),
                    "mean_latency_ms": round(mean_lat, 2),
                    "mean_p95_ms": round(mean_p95, 2),
                    "min_latency_ms": round(min_lat, 2)
                })

                print(f" -> Rendimiento: {mean_tp:6.2f} img/s | Latencia media: {mean_lat:6.2f} ms | P95: {mean_p95:6.2f} ms")

        # 8. Language Isolation Benchmark (Single-cube comparison)
        print("\n" + "=" * 80)
        print("   EVALUACIÓN COMPARATIVA AISLADA POR LENGUAJE (K=1)")
        print("=" * 80)
        lang_iso_results = {}
        all_langs = ["C++", "Rust", "Go", "Java", "Python"]
        for lang in all_langs:
            set_server_cube_config(1, [lang])
            time.sleep(0.3)
            # Warmup
            run_batch(1, f"warm_iso_{lang}")
            l_lats = []
            for r in range(10):
                res = run_batch(1, f"iso_{lang}_{r}")
                l_lats.append(res["mean_latency_ms"])
            avg_lat = sum(l_lats) / len(l_lats)
            tp = 1000.0 / avg_lat
            lang_iso_results[lang] = {
                "mean_latency_ms": round(avg_lat, 2),
                "throughput_req_per_sec": round(tp, 2)
            }
            print(f"  * Lenguaje: {lang:7s} | Latencia media: {avg_lat:6.2f} ms | Rendimiento: {tp:6.2f} img/s")

        # 9. Compute Speedup and Efficiency in Summary
        # Compute baseline for each concurrency level C at K=1
        k1_throughputs = {s["concurrency"]: s["mean_throughput"] for s in summary_records if s["num_cubes"] == 1}
        for s in summary_records:
            c = s["concurrency"]
            k = s["num_cubes"]
            base_tp = k1_throughputs.get(c, 1.0)
            speedup = s["mean_throughput"] / max(base_tp, 0.001)
            eff = speedup / k
            s["speedup_relative_to_k1"] = round(speedup, 3)
            s["parallel_efficiency"] = round(eff, 3)

        # 10. Write Raw CSV File
        with open(csv_file_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=raw_fieldnames)
            writer.writeheader()
            writer.writerows(csv_rows)
        print(f"\n[OK] Datos crudos de benchmark guardados en: {csv_file_path}")

        # 11. Write JSON Summary
        summary_payload = {
            "metadata": {
                "date": time.strftime("%Y-%m-%d %H:%M:%S"),
                "total_observations": len(csv_rows),
                "cube_configurations": CUBE_CONFIGURATIONS,
                "concurrency_levels": CONCURRENCY_LEVELS,
                "repetitions": REPETITIONS_PER_CONDITION,
                "dataset_mode": "REAL (200 fotografías en dataset/real/)",
                "languages_evaluated": all_langs
            },
            "cluster_scaling_summary": summary_records,
            "language_isolation": lang_iso_results
        }
        with open(json_file_path, "w", encoding="utf-8") as f:
            json.dump(summary_payload, f, indent=2, ensure_ascii=False)
        print(f"[OK] Resumen estadístico guardado en: {json_file_path}")

        # 12. Write Formatted Text Report
        with open(txt_file_path, "w", encoding="utf-8") as f:
            f.write("=" * 95 + "\n")
            f.write("    REPORTE INTEGRAL DE BENCHMARK: RENDIMIENTO Y ESCALABILIDAD CLÚSTER OPEN AI CUBES\n")
            f.write("=" * 95 + "\n\n")
            f.write(f"Fecha de ejecución: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"Modo de clúster: PARALLEL_DISTRIBUTED (Distribución dinámica balanceada)\n")
            f.write(f"Repeticiones por condición: {REPETITIONS_PER_CONDITION} (con {WARMUP_REPETITIONS} rondas de calentamiento previo)\n\n")
            f.write("-" * 95 + "\n")
            f.write(f"{'Cubos (K)':<10} | {'Clientes (C)':<14} | {'Rendimiento':<16} | {'Latencia Media':<16} | {'P95 (ms)':<10} | {'Speedup':<9} | {'Eficiencia':<10}\n")
            f.write("-" * 95 + "\n")
            for s in summary_records:
                f.write(f"{s['num_cubes']:<10} | {s['concurrency']:<14} | {s['mean_throughput']:8.2f} img/s   | {s['mean_latency_ms']:10.2f} ms   | {s['mean_p95_ms']:8.2f}   | {s['speedup_relative_to_k1']:7.2f}x  | {s['parallel_efficiency']*100:6.1f}%\n")
            f.write("-" * 95 + "\n\n")
            f.write("EVALUACIÓN DE AISLAMIENTO POR LENGUAJE (Un solo cubo K=1 ejecutando inferencias completas):\n")
            f.write("-" * 65 + "\n")
            f.write(f"{'Lenguaje':<12} | {'Latencia Media (ms)':<22} | {'Rendimiento (img/s)':<20}\n")
            f.write("-" * 65 + "\n")
            for lang, d in lang_iso_results.items():
                f.write(f"{lang:<12} | {d['mean_latency_ms']:16.2f} ms   | {d['throughput_req_per_sec']:14.2f} img/s\n")
            f.write("-" * 65 + "\n")

        print(f"[OK] Reporte textual guardado en: {txt_file_path}")

    finally:
        cleanup()
        # Clean test binaries
        for bin_name in ["cpp_worker_cube/worker_cube_bench", "rust_worker_cube/rust_worker_bench", "go_worker_cube/worker_cube_bench_go"]:
            p = os.path.join(PROJECT_ROOT, bin_name)
            if os.path.exists(p):
                try: os.remove(p)
                except Exception: pass


if __name__ == "__main__":
    main()

