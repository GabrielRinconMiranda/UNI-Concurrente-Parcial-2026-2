package server;

/**
 * ExecutionMode: Defines the four computational execution modes of the architecture.
 */
public enum ExecutionMode {
    SEQUENTIAL("Sequential (1 Process, 1 Thread)"),
    PARALLEL("Parallel (Local N Cores, H Threads)"),
    DISTRIBUTED("Distributed (M Remote Nodes, 1 Thread per Node)"),
    PARALLEL_DISTRIBUTED("Parallel Distributed (M Remote Nodes, Multi-Threaded)");

    private final String description;

    ExecutionMode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public static ExecutionMode fromString(String modeStr) {
        if (modeStr == null) return SEQUENTIAL;
        String m = modeStr.toUpperCase().replace('-', '_').replace(' ', '_');
        for (ExecutionMode em : values()) {
            if (em.name().equals(m) || em.name().startsWith(m)) {
                return em;
            }
        }
        return SEQUENTIAL;
    }
}

