package protocol;

/**
 * ProtocolMessage: Encapsulates line-based protocol parsing and encoding.
 * Format: ACTION|ID|PAYLOAD\n
 */
public class ProtocolMessage {
    private final String action;
    private final String id;
    private final String payload;

    public ProtocolMessage(String action, String id, String payload) {
        this.action = (action != null) ? action.trim() : "";
        this.id = (id != null) ? id.trim() : "";
        this.payload = (payload != null) ? payload.trim() : "";
    }

    public String getAction() {
        return action;
    }

    public String getId() {
        return id;
    }

    public String getPayload() {
        return payload;
    }

    public String toWireString() {
        return action + ProtocolConstants.DELIMITER + id + ProtocolConstants.DELIMITER + payload + ProtocolConstants.LINE_TERMINATOR;
    }

    public static ProtocolMessage parse(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        // Split into at most 3 parts (ACTION, ID, PAYLOAD)
        int firstPipe = trimmed.indexOf('|');
        if (firstPipe == -1) {
            return new ProtocolMessage(trimmed, "", "");
        }
        String act = trimmed.substring(0, firstPipe);
        int secondPipe = trimmed.indexOf('|', firstPipe + 1);
        if (secondPipe == -1) {
            String idVal = trimmed.substring(firstPipe + 1);
            return new ProtocolMessage(act, idVal, "");
        }
        String idVal = trimmed.substring(firstPipe + 1, secondPipe);
        String payloadVal = trimmed.substring(secondPipe + 1);
        return new ProtocolMessage(act, idVal, payloadVal);
    }

    @Override
    public String toString() {
        return "ProtocolMessage{" +
                "action='" + action + '\'' +
                ", id='" + id + '\'' +
                ", payloadLength=" + payload.length() +
                '}';
    }
}

