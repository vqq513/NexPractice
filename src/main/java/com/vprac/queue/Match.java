package com.vprac.queue;

import com.vprac.arena.ArenaData;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public final class Match {
    private final UUID first;
    private final UUID second;
    private final String kitId;
    private final ArenaData arena;
    private boolean finishing;
    private boolean countdown;
    /** True only after both participants have been teleported to their arena spawns. */
    private boolean onArena;
    /** Allows the scheduled initial lobby-to-arena teleport through match transition guards. */
    private boolean arenaTeleportInProgress;
    private long startedAtMillis;
    private final Map<UUID, Integer> matchKills = new HashMap<>();
    private final Map<UUID, Integer> roundWins = new HashMap<>();
    private int roundsToWin = 1;
    private boolean roundTransitioning;
    private boolean bedfight;
    /** False for direct duels and tournament bouts; only ordinary queue matches contribute persistent stats. */
    private boolean statsTracked = true;
    private boolean firstBedIntact = true;
    private boolean secondBedIntact = true;

    public Match(UUID first, UUID second, String kitId, ArenaData arena) {
        this.first = first;
        this.second = second;
        this.kitId = kitId;
        this.arena = arena;
    }

    public UUID first() { return first; }
    public UUID second() { return second; }
    public String kitId() { return kitId; }
    public ArenaData arena() { return arena; }
    public boolean finishing() { return finishing; }
    public void setFinishing(boolean finishing) { this.finishing = finishing; }
    public boolean countdown() { return countdown; }
    public void setCountdown(boolean countdown) { this.countdown = countdown; }
    public boolean onArena() { return onArena; }
    public void setOnArena(boolean onArena) { this.onArena = onArena; }
    public boolean arenaTeleportInProgress() { return arenaTeleportInProgress; }
    public void setArenaTeleportInProgress(boolean inProgress) { this.arenaTeleportInProgress = inProgress; }
    public long startedAtMillis() { return startedAtMillis; }
    public void setStartedAtMillis(long startedAtMillis) { this.startedAtMillis = startedAtMillis; }
    public int kills(UUID player) { return matchKills.getOrDefault(player, 0); }
    public void recordKill(UUID player) { if (player != null) matchKills.merge(player, 1, Integer::sum); }
    public int roundsToWin() { return roundsToWin; }
    public void setRoundsToWin(int rounds) { roundsToWin = Math.max(1, Math.min(10, rounds)); }
    public int roundWins(UUID player) { return roundWins.getOrDefault(player, 0); }
    public int recordRoundWin(UUID player) { if (player != null) return roundWins.merge(player, 1, Integer::sum); return 0; }
    public boolean roundTransitioning() { return roundTransitioning; }
    public void setRoundTransitioning(boolean value) { roundTransitioning = value; }
    public boolean bedfight() { return bedfight; }
    public boolean statsTracked() { return statsTracked; }
    public void setStatsTracked(boolean statsTracked) { this.statsTracked = statsTracked; }
    public void setBedfight(boolean value) { bedfight = value; }
    public boolean bedIntact(UUID owner) {
        if (!bedfight || owner == null) return false;
        if (first.equals(owner)) return firstBedIntact;
        if (second.equals(owner)) return secondBedIntact;
        return false;
    }
    public UUID bedOwnerAt(org.bukkit.Location foot) {
        if (!bedfight || foot == null || foot.getWorld() == null) return null;
        if (sameBlock(arena.bedA(), foot)) return first;
        if (sameBlock(arena.bedB(), foot)) return second;
        return null;
    }
    public boolean destroyBed(UUID owner) {
        if (!bedfight || owner == null || !bedIntact(owner)) return false;
        if (first.equals(owner)) firstBedIntact = false;
        else if (second.equals(owner)) secondBedIntact = false;
        else return false;
        return true;
    }
    public void resetBeds() { if (bedfight) { firstBedIntact = true; secondBedIntact = true; } }
    public org.bukkit.Location spawnFor(UUID player) {
        if (first.equals(player)) return arena.spawnOne();
        if (second.equals(player)) return arena.spawnTwo();
        return null;
    }
    private boolean sameBlock(org.bukkit.Location a, org.bukkit.Location b) {
        return a != null && a.getWorld() != null && b != null && b.getWorld() != null
                && a.getWorld().getName().equals(b.getWorld().getName())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
    public UUID opponent(UUID player) { return first.equals(player) ? second : first; }
}
