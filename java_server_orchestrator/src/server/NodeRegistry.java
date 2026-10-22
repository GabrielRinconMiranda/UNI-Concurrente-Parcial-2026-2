package server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NodeRegistry: Thread-safe registry and load balancer for cluster worker nodes.
 * Supports dynamic configuration of active cube counts and language filters.
 */
public class NodeRegistry {
    private final List<WorkerNodeSession> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private volatile int maxActiveCubes = 0; // 0 = all available
    private final Set<String> enabledLanguages = new CopyOnWriteArraySet<>(); // empty = all

    public void setConfig(int maxCubes, List<String> languages) {
        this.maxActiveCubes = Math.max(0, maxCubes);
        this.enabledLanguages.clear();
        if (languages != null) {
            for (String l : languages) {
                if (!l.trim().isEmpty() && !l.equalsIgnoreCase("ALL")) {
                    this.enabledLanguages.add(l.trim().toLowerCase());
                }
            }
        }
        System.out.println("[Registry] Configuración actualizada: maxCubos=" + maxActiveCubes + ", lenguajes=" + enabledLanguages);
    }

    public int getMaxActiveCubes() {
        return maxActiveCubes;
    }

    public Set<String> getEnabledLanguages() {
        return Collections.unmodifiableSet(enabledLanguages);
    }

    public void register(WorkerNodeSession session) {
        // Remove existing worker with same ID if reconnecting
        workers.removeIf(w -> w.getWorkerId().equals(session.getWorkerId()) || !w.isConnected());
        workers.add(session);
        System.out.println("[Registry] Registered worker: " + session);
    }

    public void unregister(WorkerNodeSession session) {
        workers.remove(session);
        session.close();
        System.out.println("[Registry] Unregistered worker: " + session.getWorkerId());
    }

    /**
     * Returns the list of all physically connected workers regardless of filter.
     */
    public List<WorkerNodeSession> getAllConnectedWorkers() {
        workers.removeIf(w -> !w.isConnected());
        return Collections.unmodifiableList(new ArrayList<>(workers));
    }

    /**
     * Returns the active workers according to user cube limit and language filter.
     */
    public List<WorkerNodeSession> getActiveWorkers() {
        // Prune disconnected
        workers.removeIf(w -> !w.isConnected());
        List<WorkerNodeSession> filtered = new ArrayList<>();
        for (WorkerNodeSession w : workers) {
            if (enabledLanguages.isEmpty() || enabledLanguages.contains(w.getLanguage().toLowerCase())) {
                filtered.add(w);
                if (maxActiveCubes > 0 && filtered.size() >= maxActiveCubes) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    public int getWorkerCount() {
        return getActiveWorkers().size();
    }

    /**
     * Retrieves the next available worker using round-robin and load awareness.
     */
    public WorkerNodeSession acquireWorker() {
        List<WorkerNodeSession> active = getActiveWorkers();
        if (active.isEmpty()) {
            return null;
        }

        // Find worker with minimal load
        WorkerNodeSession best = null;
        int minLoad = Integer.MAX_VALUE;

        int size = active.size();
        int start = Math.abs(roundRobinIndex.getAndIncrement() % size);

        for (int i = 0; i < size; i++) {
            WorkerNodeSession candidate = active.get((start + i) % size);
            int load = candidate.getActiveTaskCount();
            if (load < minLoad) {
                minLoad = load;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Retrieves the next available worker excluding a specific worker session if possible.
     */
    public WorkerNodeSession acquireWorkerExcluding(WorkerNodeSession exclude) {
        List<WorkerNodeSession> active = getActiveWorkers();
        if (active.isEmpty()) {
            return null;
        }
        if (active.size() == 1) {
            return active.get(0);
        }

        WorkerNodeSession best = null;
        int minLoad = Integer.MAX_VALUE;
        int size = active.size();
        int start = Math.abs(roundRobinIndex.getAndIncrement() % size);

        for (int i = 0; i < size; i++) {
            WorkerNodeSession candidate = active.get((start + i) % size);
            if (exclude != null && candidate.getWorkerId().equals(exclude.getWorkerId())) {
                continue;
            }
            int load = candidate.getActiveTaskCount();
            if (load < minLoad) {
                minLoad = load;
                best = candidate;
            }
        }
        return (best != null) ? best : active.get(0);
    }
}

