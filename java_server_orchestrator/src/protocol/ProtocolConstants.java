package protocol;

/**
 * ProtocolConstants: Definitions of commands and control tokens.
 */
public class ProtocolConstants {
    public static final String DELIMITER = "|";
    public static final String LINE_TERMINATOR = "\n";

    // Worker Registration
    public static final String CMD_REGISTER = "REGISTER";
    public static final String CMD_REGISTER_ACK = "REGISTER_ACK";

    // Heartbeat
    public static final String CMD_PING = "PING";
    public static final String CMD_PONG = "PONG";

    // Micro-Chunk Tasks
    public static final String CMD_CONV_REQ = "CONV_REQ";
    public static final String CMD_CONV_RESP = "CONV_RESP";
    public static final String CMD_MLP_REQ = "MLP_REQ";
    public static final String CMD_MLP_RESP = "MLP_RESP";
    public static final String CMD_INFER_REQ = "INFER_REQ";
    public static final String CMD_INFER_RESP = "INFER_RESP";

    // Client Chat Commands
    public static final String CMD_CLIENT_REQ = "CLIENT_REQ";
    public static final String CMD_CLIENT_RESP = "CLIENT_RESP";
    public static final String CMD_GET_WORKERS = "GET_WORKERS";
    public static final String CMD_WORKERS_RESP = "WORKERS_RESP";
    public static final String CMD_RUN_BENCHMARK = "RUN_BENCHMARK";
    public static final String CMD_BENCHMARK_RESP = "BENCHMARK_RESP";

    // Training Commands
    public static final String CMD_TRAIN_REQ = "TRAIN_REQ";
    public static final String CMD_TRAIN_RESP = "TRAIN_RESP";
    public static final String CMD_BENCHMARK_TRAIN = "BENCHMARK_TRAIN";
    public static final String CMD_BENCHMARK_TRAIN_RESP = "BENCHMARK_TRAIN_RESP";

    // Default Ports
    public static final int DEFAULT_WORKER_PORT = 9010;
    public static final int DEFAULT_CLIENT_PORT = 8080;
}

