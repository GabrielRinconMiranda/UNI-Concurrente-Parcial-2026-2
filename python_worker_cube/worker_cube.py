"""
Python Worker Cube (Distributed Compute Node).
Connects to Central Orchestrator Server via native standard sockets.
Processes Convolution and MLP micro-chunks concurrently.
"""

import socket
import sys
import threading
from micro_chunks import convolution_chunk, mlp_chunk, infer_end_to_end


class PythonWorkerCube:
    def __init__(self, worker_id: str = "node_py_1", host: str = "localhost", port: int = 9010, threads: int = 4):
        self.worker_id = worker_id
        self.host = host
        self.port = port
        self.threads = threads
        self.running = False
        self.sock = None

    def start(self):
        print(f"[PythonWorker {self.worker_id}] Connecting to Server at {self.host}:{self.port}...")
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.connect((self.host, self.port))
            self.running = True

            f_in = self.sock.makefile("r", encoding="utf-8")
            f_out = self.sock.makefile("w", encoding="utf-8")

            # 1. Register with Server
            reg_msg = f"REGISTER|{self.worker_id}|Python|{self.threads}|CONV,MLP\n"
            f_out.write(reg_msg)
            f_out.flush()

            ack_line = f_in.readline()
            print(f"[PythonWorker {self.worker_id}] Registration ACK: {ack_line.strip()}")

            # 2. Process tasks
            for line in f_in:
                if not line:
                    break
                resp = self._handle_message(line.strip())
                if resp:
                    f_out.write(resp + "\n")
                    f_out.flush()

        except Exception as e:
            print(f"[PythonWorker {self.worker_id}] Connection error: {e}")
        finally:
            self.running = False
            if self.sock:
                self.sock.close()
            print(f"[PythonWorker {self.worker_id}] Disconnected.")

    def _handle_message(self, line: str) -> str:
        parts = line.split("|", 2)
        if len(parts) < 2:
            return ""

        action = parts[0]
        task_id = parts[1]
        payload = parts[2] if len(parts) > 2 else ""

        if action == "CONV_REQ":
            pixels = [float(x) for x in payload.split(",")]
            feats = convolution_chunk(pixels, self.threads)
            feats_csv = ",".join(f"{f:.4f}" for f in feats)
            return f"CONV_RESP|{task_id}|{feats_csv}"

        elif action == "MLP_REQ":
            feats = [float(x) for x in payload.split(",")]
            label, conf, probs = mlp_chunk(feats)
            prob_str = ";".join(f"{p:.4f}" for p in probs)
            return f"MLP_RESP|{task_id}|{label}:{conf * 100.0:.2f};{prob_str}"

        elif action == "INFER_REQ":
            # Payload format: label|csv
            if "|" in payload:
                _, csv = payload.split("|", 1)
            else:
                csv = payload
            pixels = [float(x) for x in csv.split(",")]
            label, conf, probs = infer_end_to_end(pixels)
            prob_str = ";".join(f"{p:.4f}" for p in probs)
            return f"INFER_RESP|{task_id}|{label}:{conf * 100.0:.2f};{prob_str}"

        elif action == "PING":
            return f"PONG|{task_id}|OK"

        return f"UNKNOWN|{task_id}|ERR"


def run_http_worker(port: int, threads: int):
    from http.server import HTTPServer, BaseHTTPRequestHandler

    class WorkerHttpHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def do_GET(self):
            if self.path == "/health":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"ok","worker":"node_py_cloud","language":"Python"}\n')
            else:
                self.send_response(404)
                self.end_headers()

        def do_POST(self):
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8").strip()

            if self.path == "/conv":
                pixels = [float(x) for x in body.split(",") if x.strip()]
                feats = convolution_chunk(pixels, threads)
                feats_csv = ",".join(f"{f:.4f}" for f in feats)
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(feats_csv.encode("utf-8"))

            elif self.path == "/mlp":
                feats = [float(x) for x in body.split(",") if x.strip()]
                label, conf, probs = mlp_chunk(feats)
                prob_str = ";".join(f"{p:.4f}" for p in probs)
                resp = f"{label}:{conf * 100.0:.2f};{prob_str}"
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(resp.encode("utf-8"))

            elif self.path == "/infer":
                pixels = [float(x) for x in body.split(",") if x.strip()]
                label, conf, probs = infer_end_to_end(pixels)
                prob_str = ";".join(f"{p:.4f}" for p in probs)
                resp = f"{label}:{conf * 100.0:.2f};{prob_str}"
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(resp.encode("utf-8"))
            else:
                self.send_response(404)
                self.end_headers()

    server = HTTPServer(("", port), WorkerHttpHandler)
    print(f"[Worker-Python-HTTP] Starting Cloud HTTP Worker on port {port}...")
    server.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--http":
        import os
        env_port = os.environ.get("PORT")
        default_port = int(env_port) if env_port and env_port.isdigit() else 8082
        h_port = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else default_port
        h_threads = int(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3].isdigit() else 4
        run_http_worker(h_port, h_threads)
        sys.exit(0)

    w_id = sys.argv[1] if len(sys.argv) > 1 else "node_py_1"
    host = sys.argv[2] if len(sys.argv) > 2 else "localhost"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 9010
    threads = int(sys.argv[4]) if len(sys.argv) > 4 else 4

    worker = PythonWorkerCube(w_id, host, port, threads)
    worker.start()

