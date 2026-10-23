package com.vprac.leaderboard.storage;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Persistent backend for queue match statistics and the rank progression derived from wins. */
public interface PlayerStatsStorage extends AutoCloseable {
    /** Loads UUID -> YAML snapshot, where the snapshot root is a section named "player". */
    Map<String, String> loadAll() throws Exception;

    /** Atomically increments the requested lifetime counters and selected period counters. */
    void incrementStats(UUID uuid, String playerName, String kitId, Map<String, Integer> deltas,
                        Map<String, Map<String, Integer>> periodDeltas);

    void updateName(UUID uuid, String playerName);

    /** Replaces a player's complete stats subtree; used only for one-time YAML-to-database migration. */
    void replacePlayer(UUID uuid, String serializedPlayerYaml);

    void removeKit(String kitId);

    /** Runs a reload after all previously queued writes, then invokes callback on the storage worker. */
    void reloadSnapshotAsync(Consumer<Map<String, String>> callback);

    String label();

    @Override
    void close();
}
