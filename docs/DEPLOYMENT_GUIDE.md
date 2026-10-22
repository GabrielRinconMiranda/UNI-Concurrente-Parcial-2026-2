# Cloud Deployment and Free Hosting Guide: Open AI Cubes

This guide documents the architecture and deployment procedures for running the **Open AI Cubes** distributed inference system across cloud web hosting platforms, virtual private servers (VPS), and containerized environments.

---

## 1. Network Constraints and Standard Protocol Architecture

The system supports two complementary communication protocols built entirely with base language standard libraries:

1. **Standard TCP Sockets (RFC 793):** Used for low-latency communication in local clusters, private virtual networks, or dedicated cloud VMs with open TCP ports.
2. **Standard HTTP/1.1 REST Endpoints (RFC 7230):** Designed specifically for free web hosting providers that only route traffic over standard HTTP/HTTPS ports (80 and 443).

### Why HTTP Support is Essential for Free Cloud Hosting
Many popular free web hosting platforms (such as Render, Railway, Fly.io, and Koyeb) automatically terminate incoming traffic on HTTP/HTTPS reverse proxies, rejecting or dropping arbitrary raw TCP listening ports (such as `9020`). 

By implementing lightweight HTTP REST handlers using the standard library of each programming language:
* No external frameworks or connection middleware are introduced (satisfying base SDK requirements).
* The central orchestrator can route requests to worker nodes hosted on separate free web services via standard HTTP POST calls.
* Clients and web dashboards can inspect cluster health and trigger predictions through standard HTTP GET and POST requests.

---

## 2. Standard Endpoints and Port Configuration

### Central Orchestrator Endpoints (Default HTTP Port: `8080`)

| Method | Endpoint | Description | Request Body | Response Format |
| :--- | :--- | :--- | :--- | :--- |
| `GET` | `/health` | Cluster health and status check | None | `{"status":"online","mode":"...","active_workers":4}` |
| `GET` | `/workers` | Detailed list of registered nodes | None | `{"workers":[{"id":"node_cpp_1","language":"C++",...}]}` |
| `POST` | `/infer` | Runs synchronous neural inference | Sample label (e.g. `panda`) or comma-separated pixels | `{"label":"panda","confidence":98.4,"latency_ms":18.5}` |

### Worker Node Endpoints (Go, Python, Java)

When launched in HTTP mode (`--http <port>`), worker nodes expose the following endpoints:

| Method | Endpoint | Description | Input / Output Format |
| :--- | :--- | :--- | :--- |
| `GET` | `/health` | Worker status and language metadata | JSON status object |
| `POST` | `/conv` | Computes 8-filter spatial convolution and adaptive max-pooling | Input: CSV pixels $\rightarrow$ Output: 1352-dimensional CSV vector |
| `POST` | `/mlp` | Evaluates cosine similarity and Softmax classification | Input: 1352-dimensional CSV vector $\rightarrow$ Output: `label:confidence;probabilities` |
| `POST` | `/infer` | End-to-end inference (convolution followed by MLP) | Input: CSV pixels $\rightarrow$ Output: Prediction string |

---

## 3. Recommended Free Hosting Platforms

### Platform A: Render (Free Web Services)
Render allows hosting native Go, Python, and Docker container applications on free tiers with automatic TLS/HTTPS certificates (`https://<service-name>.onrender.com`).

#### Option 1: Automatic Deployment via Blueprint (`render.yaml`)
The repository contains a root [render.yaml](file:///home/gartur/Downloads/Concurrente%20Parcial/render.yaml) blueprint specification:
1. Push this repository to GitHub or GitLab.
2. Log into the Render dashboard (`https://dashboard.render.com`).
3. Click **New +** $\rightarrow$ **Blueprint**.
4. Select your repository. Render automatically reads `render.yaml` and provisions both Go and Python worker web services simultaneously.

#### Option 2: Manual Dashboard Configuration

1. **Deploying the Go Worker Cube:**
   * In Render, click **New +** $\rightarrow$ **Web Service**.
   * Connect your GitHub repository.
   * **Name:** `open-ai-cubes-go-worker`
   * **Root Directory:** `go_worker_cube`
   * **Runtime:** `Go`
   * **Build Command:** `go build -o worker_cube worker_cube.go micro_chunks.go`
   * **Start Command:** `./worker_cube --http` (Render automatically assigns the `$PORT` variable).
   * **Instance Type:** `Free` (512 MB RAM, 0.1 CPU).

2. **Deploying the Python Worker Cube:**
   * Click **New +** $\rightarrow$ **Web Service**.
   * **Name:** `open-ai-cubes-python-worker`
   * **Root Directory:** `python_worker_cube`
   * **Runtime:** `Python 3`
   * **Build Command:** `echo "Python standard environment ready"`
   * **Start Command:** `python worker_cube.py --http`
   * **Instance Type:** `Free`.

#### Testing Your Render Deployments
Once deployed, Render provides public HTTPS URLs for each service:
```bash
# Check Go worker health
curl https://open-ai-cubes-go-worker.onrender.com/health

# Check Python worker health
curl https://open-ai-cubes-python-worker.onrender.com/health

# Execute inference directly on cloud worker
curl -X POST https://open-ai-cubes-go-worker.onrender.com/infer -d "panda"
```

> [!NOTE]
> **Free Tier Spin-Down Behavior:** Free instances on Render enter sleep mode after 15 minutes of inactivity. When a new incoming request is received, the instance spins up automatically in ~30–50 seconds, after which subsequent requests are processed in real time.

---

### Platform B: Fly.io (Micro-Containers with TCP or HTTP Support)
Fly.io provides free hobby tier allowances and allows running containers that expose either raw TCP or standard HTTP ports via `fly.toml`.

* **Configuration for HTTP Web Service (`fly.toml`):**
  ```toml
  app = "open-ai-cubes-go-worker"
  primary_region = "iad"

  [build]
    dockerfile = "Dockerfile"

  [[services]]
    internal_port = 8081
    protocol = "tcp"

    [[services.ports]]
      port = 80
      handlers = ["http"]

    [[services.ports]]
      port = 443
      handlers = ["tls", "http"]
  ```

---

### Platform C: Oracle Cloud Free Tier (Always Free Compute)
For running raw TCP socket clusters without HTTP overhead, Oracle Cloud Infrastructure (OCI) offers the most generous free tier:
* Up to 4 ARM Ampere cores with 24 GB RAM (splittable across up to 4 virtual machines).
* 2 additional AMD x86_64 compute instances.
* Fixed public IPv4 addresses with configurable security lists.

**Firewall Configuration for the Central Server:**
```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 9010 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8082 -j ACCEPT
sudo netfilter-persistent save
```

---

### Platform D: Layer 4 Tunnels (Zero-Cost NAT Traversal)
If team members are running worker nodes on home Wi-Fi or campus networks behind NAT, secure Layer 4 tunnels can bridge traffic without port forwarding:

1. **Cloudflare Tunnels (`cloudflared` in TCP mode):**
   ```bash
   cloudflared access tcp --hostname cluster.yourdomain.com --url localhost:9020
   ```
2. **Playit.gg / Ngrok:**
   ```bash
   ngrok tcp 9020
   ```
   Ngrok assigns a public host address (e.g., `0.tcp.ngrok.io:14522`), which workers on other machines can connect to directly.

---

## 4. Multi-Container Deployment with Docker Compose

The repository includes a ready-to-run multi-node stack in `docker-compose.yml`:
* `server-orchestrator`: Central server with Java Swing GUI / headless runner.
* `worker-cpp`: C++ worker with OpenMP multi-threading.
* `worker-rust`: Rust native multi-threaded worker.
* `worker-go`: Go lightweight concurrent worker.
* `worker-python`: Python worker node.
* `worker-java`: Java worker node.

### Execution Commands
```bash
# Build all language images
docker compose build

# Start the complete cluster in the background
docker compose up -d

# View live orchestrator connection logs
docker compose logs -f server-orchestrator

# Stop the cluster cleanly
docker compose down
```

---

## 5. Unified Command-Line Invocations

Every worker node can be launched in either TCP socket mode or HTTP service mode across any platform:

### Standard TCP Socket Mode
```bash
# C++ Worker Cube
./cpp_worker_cube/worker_cube node_cpp_1 127.0.0.1 9010 4

# Rust Worker Cube
./rust_worker_cube/rust_worker_bin node_rust_1 127.0.0.1 9010 4

# Go Worker Cube
./go_worker_cube/worker_cube node_go_1 127.0.0.1 9010 4

# Python Worker Cube
python3 python_worker_cube/worker_cube.py node_py_1 127.0.0.1 9010 4

# Java Worker Cube
java -cp java_server_orchestrator/bin worker.JavaWorkerCube node_java_1 127.0.0.1 9010 4
```

### Windows Equivalents (Command Prompt)
```cmd
:: C++
cpp_worker_cube\worker_cube.exe node_cpp_1 127.0.0.1 9010 4

:: Rust
rust_worker_cube\rust_worker_bin.exe node_rust_1 127.0.0.1 9010 4

:: Go
go_worker_cube\worker_cube.exe node_go_1 127.0.0.1 9010 4

:: Python
python python_worker_cube\worker_cube.py node_py_1 127.0.0.1 9010 4

:: Java
java -cp java_server_orchestrator\bin worker.JavaWorkerCube node_java_1 127.0.0.1 9010 4
```

### Cloud HTTP Service Mode
```bash
# Go Cloud Microservice
./go_worker_cube/worker_cube --http :8081 4

# Python Cloud Microservice
python3 python_worker_cube/worker_cube.py --http 8083 4

# Central Server (exposes Worker TCP on 9010, Client TCP on 8080, HTTP REST on 8082)
java -cp java_server_orchestrator/bin server.ServerOrchestrator 9010 8080 8082
```
