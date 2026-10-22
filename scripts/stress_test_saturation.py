#!/usr/bin/env python3
"""
scripts/stress_test_saturation.py

Saturates the Open AI Cubes cluster with hundreds of client transactions
using randomized delays between requests (as mandated by the course rubric):
"Hacer pruebas donde los clientes puedan enviar cientos de transacciones regulando
 con un delay aleatorio entre las transacciones, para saturar el sistema y ver sus límites."

Measures:
  - Throughput under heavy contention
  - Tail latencies (P50, P90, P95, P99)
  - Connection error rates and server resilience
"""

import os
import sys
import time
import random
import socket
from concurrent.futures import ThreadPoolExecutor, as_completed

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CLIENT_PORT = 8095  # Connect to running server or default client port
NUM_TRANSACTIONS = 250
CONCURRENT_CLIENTS = 20
MIN_DELAY_MS = 5
MAX_DELAY_MS = 45

TEST_SAMPLES = ["panda", "cat", "dog", "bear", "lion"]


def single_transaction(tx_id: int, host: str, port: int) -> dict:
    time.sleep(random.uniform(MIN_DELAY_MS, MAX_DELAY_MS) / 1000.0)
    client_id = f"idClientChat_{tx_id:04d}"
    sample = TEST_SAMPLES[tx_id % len(TEST_SAMPLES)]
    t0 = time.perf_counter()
    try:
        with socket.create_connection((host, port), timeout=10.0) as s:
            f_in = s.makefile("r", encoding="utf-8")
            f_out = s.makefile("w", encoding="utf-8")
            f_out.write(f"CLIENT_REQ|{client_id}|{sample}\n")
            f_out.flush()
            line = f_in.readline()
            t1 = time.perf_counter()
            if not line:
                return {"id": tx_id, "success": False, "error": "Empty socket response", "lat_ms": 0}
            parts = line.strip().split("|")
            if parts[0] == "CLIENT_RESP" and parts[2] in TEST_SAMPLES:
                return {"id": tx_id, "success": True, "label": parts[2], "lat_ms": (t1 - t0) * 1000.0}
            else:
                return {"id": tx_id, "success": False, "error": line.strip(), "lat_ms": (t1 - t0) * 1000.0}
    except Exception as e:
        return {"id": tx_id, "success": False, "error": str(e), "lat_ms": 0}


def run_stress_test(host: str = "127.0.0.1", port: int = 8095):
    print("=" * 80)
    print("   PRUEBA DE ESTRÉS Y SATURACIÓN CON DELAY ALEATORIO — OPEN AI CUBES")
    print("=" * 80)
    print(f"Destino: {host}:{port}")
    print(f"Total de transacciones: {NUM_TRANSACTIONS}")
    print(f"Clientes concurrentes: {CONCURRENT_CLIENTS}")
    print(f"Rango de retraso aleatorio: [{MIN_DELAY_MS}, {MAX_DELAY_MS}] ms")
    print("-" * 80)

    # Check connection
    try:
        with socket.create_connection((host, port), timeout=3.0):
            pass
    except Exception as e:
        print(f"Error: No se pudo conectar a {host}:{port}. ¿Está el servidor iniciado?")
        print(f"Detalle: {e}")
        return False

    t_start = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=CONCURRENT_CLIENTS) as pool:
        futures = [pool.submit(single_transaction, i, host, port) for i in range(1, NUM_TRANSACTIONS + 1)]
        done_count = 0
        for fut in as_completed(futures):
            res = fut.result()
            results.append(res)
            done_count += 1
            if done_count % 50 == 0 or done_count == NUM_TRANSACTIONS:
                print(f"  -> Progreso: {done_count}/{NUM_TRANSACTIONS} transacciones procesadas...")
    t_end = time.perf_counter()

    total_time_s = t_end - t_start
    successes = [r for r in results if r["success"]]
    failures = [r for r in results if not r["success"]]

    latencies = sorted([r["lat_ms"] for r in successes])
    mean_lat = sum(latencies) / len(latencies) if latencies else 0
    p50 = latencies[len(latencies) // 2] if latencies else 0
    p90 = latencies[int(len(latencies) * 0.90)] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    p99 = latencies[int(len(latencies) * 0.99)] if latencies else 0
    tp = len(successes) / total_time_s if total_time_s > 0 else 0

    report = []
    report.append("=" * 80)
    report.append("   RESULTADOS DE LA PRUEBA DE SATURACIÓN Y LÍMITES")
    report.append("=" * 80)
    report.append(f"Tiempo total transcurrido: {total_time_s:.2f} s")
    report.append(f"Transacciones exitosas: {len(successes)} / {NUM_TRANSACTIONS} ({len(successes)/NUM_TRANSACTIONS*100:.1f}%)")
    report.append(f"Transacciones fallidas: {len(failures)}")
    report.append(f"Rendimiento promedio bajo saturación: {tp:.2f} transacciones/s")
    report.append("")
    report.append("Distribución de Latencias (ms):")
    report.append(f"  • Mínima:  {latencies[0]:.2f} ms" if latencies else "  • N/A")
    report.append(f"  • Media:   {mean_lat:.2f} ms")
    report.append(f"  • P50:     {p50:.2f} ms")
    report.append(f"  • P90:     {p90:.2f} ms")
    report.append(f"  • P95:     {p95:.2f} ms")
    report.append(f"  • P99:     {p99:.2f} ms")
    report.append(f"  • Máxima:  {latencies[-1]:.2f} ms" if latencies else "  • N/A")
    report.append("=" * 80)

    report_text = "\n".join(report)
    print("\n" + report_text)

    out_path = os.path.join(PROJECT_ROOT, "docs", "stress_test_results.txt")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(report_text + "\n")
    print(f"\n[OK] Reporte de saturación guardado en: {out_path}")
    return len(failures) == 0


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8095
    host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
    run_stress_test(host, port)

