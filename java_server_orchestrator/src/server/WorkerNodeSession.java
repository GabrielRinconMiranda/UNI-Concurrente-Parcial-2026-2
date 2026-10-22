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
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);

    public WorkerNodeSession(String workerId, String language, int maxThreads, String capabilities, Socket socket) throws IOException {
        this.workerId = workerId;
        this.language = language;
        this.maxThreads = Math.max(1, maxThreads);
        this.capabilities = capabilities;
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
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

    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * Synchronously sends a protocol request and receives the response.
     * Synchronized to guarantee safe multiplexing over the TCP stream.
     */
    public synchronized ProtocolMessage sendAndReceive(ProtocolMessage request) throws IOException {
        activeTaskCount.incrementAndGet();
        try {
            writer.write(request.toWireString());
            writer.flush();

            String responseLine = reader.readLine();
            if (responseLine == null) {
                throw new IOException("Worker disconnected during task: " + workerId);
            }
            return ProtocolMessage.parse(responseLine);
        } finally {
            activeTaskCount.decrementAndGet();
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

