package worker;

import core.CnnInferenceEngine;
import core.ConvolutionChunk;
import core.ImageMatrix;
import core.MlpChunk;
import protocol.ProtocolConstants;
import protocol.ProtocolMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaWorkerCube: Standalone distributed compute node implemented in Java (LP1 = LP2).
 * Connects to the Central Orchestrator Server, registers its capabilities,
 * and processes Micro-Chunk tasks concurrently.
 */
public class JavaWorkerCube {
    private final String workerId;
    private final String serverHost;
    private final int serverPort;
    private final int threadCount;
    private final ExecutorService taskPool;

    public JavaWorkerCube(String workerId, String serverHost, int serverPort, int threadCount) {
        this.workerId = workerId;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.threadCount = threadCount;
        this.taskPool = Executors.newFixedThreadPool(threadCount);
    }

    public void start() {
        System.out.println("[JavaWorker " + workerId + "] Connecting to Server at " + serverHost + ":" + serverPort + "...");
        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // 1. Send Registration Message
            String regPayload = "Java|" + threadCount + "|CONV,MLP";
            ProtocolMessage regMsg = new ProtocolMessage(ProtocolConstants.CMD_REGISTER, workerId, regPayload);
            writer.write(regMsg.toWireString());
            writer.flush();

            String ackLine = reader.readLine();
            ProtocolMessage ack = ProtocolMessage.parse(ackLine);
            if (ack == null || !ProtocolConstants.CMD_REGISTER_ACK.equals(ack.getAction())) {
                System.err.println("[JavaWorker " + workerId + "] Registration rejected: " + ackLine);
                return;
            }
            System.out.println("[JavaWorker " + workerId + "] Registered successfully with " + threadCount + " threads.");

            // 2. Main Task Processing Loop
            String line;
            while ((line = reader.readLine()) != null) {
                ProtocolMessage msg = ProtocolMessage.parse(line);
                if (msg == null) continue;

                ProtocolMessage response = handleMessage(msg);
                if (response != null) {
                    synchronized (writer) {
                        writer.write(response.toWireString());
                        writer.flush();
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[JavaWorker " + workerId + "] Connection error: " + e.getMessage());
        } finally {
            taskPool.shutdown();
        }
    }

    private ProtocolMessage handleMessage(ProtocolMessage msg) {
        String action = msg.getAction();
        String id = msg.getId();

        switch (action) {
            case ProtocolConstants.CMD_CONV_REQ: {
                // Micro-Chunk: Convolutions executed in parallel with threadCount cores
                ImageMatrix img = ImageMatrix.fromCsvString(msg.getPayload(), "conv_task");
                float[] features = ConvolutionChunk.process(img, threadCount);
                String csvFeatures = ConvolutionChunk.featuresToString(features);
                return new ProtocolMessage(ProtocolConstants.CMD_CONV_RESP, id, csvFeatures);
            }

            case ProtocolConstants.CMD_MLP_REQ: {
                // Micro-Chunk: MLP
                float[] features = ConvolutionChunk.featuresFromString(msg.getPayload());
                MlpChunk.Prediction pred = MlpChunk.process(features);
                return new ProtocolMessage(ProtocolConstants.CMD_MLP_RESP, id, pred.toProtocolString());
            }

            case ProtocolConstants.CMD_INFER_REQ: {
                // End-to-end Inference
                String payload = msg.getPayload();
                int pipe = payload.indexOf('|');
                String label = (pipe != -1) ? payload.substring(0, pipe) : "sample";
                String csv = (pipe != -1) ? payload.substring(pipe + 1) : payload;

                ImageMatrix img = ImageMatrix.fromCsvString(csv, label);
                MlpChunk.Prediction pred = CnnInferenceEngine.infer(img);
                return new ProtocolMessage(ProtocolConstants.CMD_INFER_RESP, id, pred.toProtocolString());
            }

            case ProtocolConstants.CMD_PING:
                return new ProtocolMessage(ProtocolConstants.CMD_PONG, id, "OK");

            default:
                return new ProtocolMessage("UNKNOWN_CMD", id, "ERR");
        }
    }

    public static void main(String[] args) {
        String id = (args.length > 0) ? args[0] : "node_java_1";
        String host = (args.length > 1) ? args[1] : "localhost";
        int port = (args.length > 2) ? Integer.parseInt(args[2]) : ProtocolConstants.DEFAULT_WORKER_PORT;
        int threads = (args.length > 3) ? Integer.parseInt(args[3]) : 4;

        JavaWorkerCube worker = new JavaWorkerCube(id, host, port, threads);
        worker.start();
    }
}

