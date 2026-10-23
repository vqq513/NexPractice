package com.vprac.leaderboard.storage;

import com.vprac.VpracPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Serializes database operations away from the server tick thread. */
public abstract class AbstractPlayerStatsStorage implements PlayerStatsStorage {
    protected final VpracPlugin plugin;
    private final ExecutorService worker;

    protected AbstractPlayerStatsStorage(VpracPlugin plugin, String threadName) {
        this.plugin = plugin;
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    protected abstract Map<String, String> loadAllNow() throws Exception;
    protected abstract void incrementStatsNow(UUID uuid, String playerName, String kitId,
                                              Map<String, Integer> deltas,
                                              Map<String, Map<String, Integer>> periodDeltas) throws Exception;
    protected abstract void updateNameNow(UUID uuid, String playerName) throws Exception;
    protected abstract void replacePlayerNow(UUID uuid, String serializedPlayerYaml) throws Exception;
    protected abstract void removeKitNow(String kitId) throws Exception;
    protected abstract void closeResources();

    @Override
    public final Map<String, String> loadAll() throws Exception {
        return loadAllNow();
    }

    @Override
    public final void incrementStats(UUID uuid, String playerName, String kitId, Map<String, Integer> deltas,
                                     Map<String, Map<String, Integer>> periodDeltas) {
        if (uuid == null || kitId == null || kitId.isBlank()) return;
        Map<String, Integer> safeDeltas = Map.copyOf(deltas == null ? Map.of() : deltas);
        Map<String, Map<String, Integer>> safePeriods = periodDeltas == null ? Map.of() : periodDeltas.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Map.copyOf(entry.getValue())));
        enqueue("updating statistics", () -> incrementStatsNow(uuid, playerName, kitId, safeDeltas, safePeriods));
    }

    @Override
    public final void updateName(UUID uuid, String playerName) {
        if (uuid == null || playerName == null || playerName.isBlank()) return;
        enqueue("updating player name", () -> updateNameNow(uuid, playerName));
    }

    @Override
    public final void replacePlayer(UUID uuid, String serializedPlayerYaml) {
        if (uuid == null || serializedPlayerYaml == null || serializedPlayerYaml.isBlank()) return;
        enqueue("migrating player statistics", () -> replacePlayerNow(uuid, serializedPlayerYaml));
    }

    @Override
    public final void removeKit(String kitId) {
        if (kitId == null || kitId.isBlank()) return;
        enqueue("removing kit statistics", () -> removeKitNow(kitId));
    }

    @Override
    public final void reloadSnapshotAsync(Consumer<Map<String, String>> callback) {
        if (callback == null) return;
        worker.execute(() -> {
            try {
                callback.accept(Map.copyOf(loadAllNow()));
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not refresh " + label() + " player statistics. Check the database connection and logs.");
            }
        });
    }

    private void enqueue(String operation, CheckedRunnable action) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            try {
                action.run();
            } catch (Exception exception) {
                plugin.getLogger().warning("Could not complete " + operation + " in " + label()
                        + ". Local stats backup was still written; check the database connection.");
                plugin.getLogger().fine(label() + " storage error: " + exception.getClass().getSimpleName()
                        + ": " + exception.getMessage());
            }
        });
    }

    @Override
    public final void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                worker.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeResources();
    }

    @FunctionalInterface
    protected interface CheckedRunnable { void run() throws Exception; }
}
