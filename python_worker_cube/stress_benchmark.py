"""
Stress Testing and Performance Benchmarking Client.
Measures latency, throughput, and scalability across the 4 execution modes:
- SEQUENTIAL
- PARALLEL
- DISTRIBUTED
- PARALLEL_DISTRIBUTED
Includes randomized delays to saturate the system and measure throughput limits.
"""

import argparse
import random
import socket
import statistics
import time
from concurrent.futures import ThreadPoolExecutor


def send_single_request(client_id: str, sample_name: str, host: str = "localhost", port: int = 8080) -> tuple[bool, str, float, float]:
    """
    Sends one CLIENT_REQ to the Server and measures round-trip latency.
    Returns (success, label, confidence, latency_ms).
    """
    t0 = time.perf_counter()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.connect((host, port))
            f_in = s.makefile("r", encoding="utf-8")
            f_out = s.makefile("w", encoding="utf-8")

            req = f"CLIENT_REQ|{client_id}|{sample_name}\n"
            f_out.write(req)
            f_out.flush()

            resp = f_in.readline()
            elapsed_ms = (time.perf_counter() - t0) * 1000.0

            if not resp:
                return False, "ERR_EMPTY", 0.0, elapsed_ms

            # Format: CLIENT_RESP|<client_id>|<label>|<conf>|<mode>|<server_ms>
            parts = resp.strip().split("|")
            if len(parts) >= 3 and parts[0] == "CLIENT_RESP":
                payload = parts[2].split(":") if ":" in parts[2] else [parts[2], "0.0"]
                label = payload[0]
                conf = float(payload[1]) if len(payload) > 1 else 0.0
                return True, label, conf, elapsed_ms

            return False, "ERR_FORMAT", 0.0, elapsed_ms
    except Exception as ex:
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        return False, str(ex), 0.0, elapsed_ms


def set_server_mode(mode: str, host: str = "localhost", port: int = 8080) -> bool:
    """Sets the active execution mode on the Central Server."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.connect((host, port))
            f_in = s.makefile("r", encoding="utf-8")
            f_out = s.makefile("w", encoding="utf-8")
            f_out.write(f"SET_MODE|bench|{mode}\n")
            f_out.flush()
            ack = f_in.readline()
            return "SET_MODE_ACK" in ack
    except Exception as e:
        print(f"[Benchmark] Failed to set server mode: {e}")
        return False


def run_benchmark(
    mode: str,
    num_requests: int = 100,
    concurrency: int = 10,
    min_delay_ms: float = 5.0,
    max_delay_ms: float = 25.0,
    host: str = "localhost",
    port: int = 8080
) -> dict:
    """
    Executes a saturated load benchmark with random delays and returns performance metrics.
    """
    print(f"[Benchmark] Starting benchmark mode: {mode}")
    print(f"[Benchmark] Total requests: {num_requests}, concurrency: {concurrency}")
    print(f"[Benchmark] Random delay: {min_delay_ms:.1f}ms - {max_delay_ms:.1f}ms")

    set_server_mode(mode, host, port)
    time.sleep(0.5)

    samples = ["panda", "cat", "dog", "bear", "lion"]
    latencies = []
    success_count = 0

    bench_start = time.perf_counter()

    def worker_task(req_idx: int):
        # Random delay between transactions to simulate realistic user interaction
        delay_sec = random.uniform(min_delay_ms, max_delay_ms) / 1000.0
        time.sleep(delay_sec)

        sample = random.choice(samples)
        client_id = f"cli_{req_idx:04d}"
        success, label, conf, lat_ms = send_single_request(client_id, sample, host, port)
        return success, lat_ms

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        results = list(executor.map(worker_task, range(num_requests)))

    total_duration_sec = time.perf_counter() - bench_start

    for ok, lat in results:
        if ok:
            success_count += 1
            latencies.append(lat)

    if not latencies:
        return {"mode": mode, "success_rate": 0.0, "total_reqs": num_requests}

    latencies.sort()
    throughput = success_count / total_duration_sec
    metrics = {
        "mode": mode,
        "total_requests": num_requests,
        "success_count": success_count,
        "success_rate": (success_count / num_requests) * 100.0,
        "total_duration_sec": total_duration_sec,
        "throughput_req_per_sec": throughput,
        "latency_min_ms": min(latencies),
        "latency_mean_ms": statistics.mean(latencies),
        "latency_median_ms": statistics.median(latencies),
        "latency_p95_ms": latencies[int(len(latencies) * 0.95)],
        "latency_max_ms": max(latencies)
    }

    print(f"[Benchmark] Results for {mode}:")
    print(f"  Success rate: {metrics['success_rate']:.1f}% ({success_count}/{num_requests})")
    print(f"  Throughput:   {metrics['throughput_req_per_sec']:.2f} req/s")
    print(f"  Latency min:  {metrics['latency_min_ms']:.2f} ms")
    print(f"  Latency mean: {metrics['latency_mean_ms']:.2f} ms")
    print(f"  Latency p95:  {metrics['latency_p95_ms']:.2f} ms")
    print(f"  Latency max:  {metrics['latency_max_ms']:.2f} ms")

    return metrics


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Open AI Cubes Stress Benchmark")
    parser.add_argument("--mode", type=str, default="PARALLEL", choices=["SEQUENTIAL", "PARALLEL", "DISTRIBUTED", "PARALLEL_DISTRIBUTED"])
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--min-delay", type=float, default=5.0)
    parser.add_argument("--max-delay", type=float, default=25.0)
    parser.add_argument("--host", type=str, default="localhost")
    parser.add_argument("--port", type=int, default=8080)

    args = parser.parse_args()
    run_benchmark(
        mode=args.mode,
        num_requests=args.requests,
        concurrency=args.concurrency,
        min_delay_ms=args.min_delay,
        max_delay_ms=args.max_delay,
        host=args.host,
        port=args.port
    )

