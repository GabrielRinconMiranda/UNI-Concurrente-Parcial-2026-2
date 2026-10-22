package server;

import protocol.ProtocolConstants;
import protocol.ProtocolMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WorkerNodeSession: Manages the TCP session and state for a connected worker node.
 */
public class WorkerNodeSession {
    private final String workerId;
    private final String language;
    private final int maxThreads;
    private final String capabilities;
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final String httpBaseUrl;
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong completedTasks = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile long lastActiveTime = System.currentTimeMillis();

    public WorkerNodeSession(String workerId, String language, int maxThreads, String capabilities, Socket socket) throws IOException {
        this.workerId = workerId;
        this.language = language;
        this.maxThreads = Math.max(1, maxThreads);
        this.capabilities = capabilities;
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.httpBaseUrl = null;
    }

    public WorkerNodeSession(String workerId, String language, int maxThreads, String capabilities, String httpBaseUrl) {
        this.workerId = workerId;
        this.language = language;
        this.maxThreads = Math.max(1, maxThreads);
        this.capabilities = capabilities;
        this.socket = null;
        this.reader = null;
        this.writer = null;
        this.httpBaseUrl = (httpBaseUrl != null) ? httpBaseUrl.replaceAll("/+$", "") : null;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getLanguage() {
        return language;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public int getActiveTaskCount() {
        return activeTaskCount.get();
    }

    public long getCompletedTasks() {
        return completedTasks.get();
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    public String getHttpBaseUrl() {
        return (httpBaseUrl != null) ? httpBaseUrl : "tcp://localhost";
    }

    public boolean isConnected() {
        if (httpBaseUrl != null) return true;
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * Synchronously sends a protocol request and receives the response.
     * Supports both direct raw TCP streams and Cloud HTTP microservice endpoints.
     */
    public synchronized ProtocolMessage sendAndReceive(ProtocolMessage request) throws IOException {
        activeTaskCount.incrementAndGet();
        try {
            if (httpBaseUrl != null) {
                String endpoint = "/infer";
                String action = request.getAction();
                if (ProtocolConstants.CMD_CONV_REQ.equals(action)) {
                    endpoint = "/conv";
                } else if (ProtocolConstants.CMD_MLP_REQ.equals(action)) {
                    endpoint = "/mlp";
                }
                String resp = httpPost(httpBaseUrl + endpoint, request.getPayload());
                String respAction = action.replace("_REQ", "_RESP");
                completedTasks.incrementAndGet();
                lastActiveTime = System.currentTimeMillis();
                return new ProtocolMessage(respAction, request.getId(), resp);
            }

            writer.write(request.toWireString());
            writer.flush();

            String responseLine = reader.readLine();
            if (responseLine == null) {
                throw new IOException("Worker disconnected during task: " + workerId);
            }
            completedTasks.incrementAndGet();
            lastActiveTime = System.currentTimeMillis();
            return ProtocolMessage.parse(responseLine);
        } finally {
            activeTaskCount.decrementAndGet();
        }
    }

    private String httpPost(String targetUrl, String payload) throws IOException {
        java.net.URL url = new java.net.URL(targetUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(35000);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        conn.setRequestProperty("Content-Type", "text/plain");
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        try (java.io.InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    public void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public String toString() {
        return "Worker[" + workerId + " (" + language + ") threads=" + maxThreads + " active=" + activeTaskCount.get() + "]";
    }
}

