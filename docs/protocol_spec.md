# Open AI Cubes: Distributed Protocol Specification

## 1. Overview
Open AI Cubes is a modular, distributed AI inference architecture designed to process Convolutional Neural Networks (CNNs) across heterogeneous nodes using native socket libraries exclusively.

## 2. Framing And Transport
- Transport: TCP raw stream
- Encoding: UTF-8 plain text
- Message delimiter: Line feed (`\n`)
- Field delimiter: Vertical bar (`|`)
- Standard format: `COMMAND|IDENTIFIER|PAYLOAD\n`

## 3. Command Definitions

### 3.1 Worker Registration
- Sent by: Worker Node $\to$ Central Server
- Format: `REGISTER|<worker_id>|<language>|<max_threads>|<capabilities>`
- Example: `REGISTER|node_py_1|Python|4|CONV,MLP`
- Server Acknowledgement: `REGISTER_ACK|<worker_id>|OK`

### 3.2 Heartbeat (Keep-Alive)
- Sent by: Central Server $\to$ Worker Node or Worker $\to$ Server
- Format: `PING|<seq>`
- Response: `PONG|<seq>`

### 3.3 Micro-Chunk Tasks
1. **Convolution Micro-Chunk**:
   - Request: `CONV_REQ|<task_id>|<comma_separated_image_floats>`
   - Response: `CONV_RESP|<task_id>|<comma_separated_feature_floats>`
2. **MLP Micro-Chunk**:
   - Request: `MLP_REQ|<task_id>|<comma_separated_feature_floats>`
   - Response: `MLP_RESP|<task_id>|<class_label>|<confidence>`
3. **End-to-End Inference Task**:
   - Request: `INFER_REQ|<task_id>|<image_name>|<comma_separated_image_floats>`
   - Response: `INFER_RESP|<task_id>|<class_label>|<confidence>`

### 3.4 Client Query Interface
- Request: `CLIENT_REQ|<client_id>|<image_name>`
- Response: `CLIENT_RESP|<client_id>|<class_label>|<confidence>|<mode>|<latency_ms>`

## 4. Execution Modes Supported
1. **Sequential Mode**: Single-threaded execution inside Central Server.
2. **Parallel Mode**: Multi-threaded execution on Central Server local thread pool.
3. **Distributed Mode**: Tasks dispatched across remote worker nodes (1 active thread per node).
4. **Parallel-Distributed Mode**: Tasks dispatched across remote worker nodes with multi-threaded local worker processing.

