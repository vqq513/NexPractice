package com.vprac.queue;

import com.vprac.arena.ArenaData;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** State for an in-party free-for-all or an automatically split team fight. */
public final class PartyMatch {
    public enum Mode { FFA, SPLIT }

    private final UUID partyId;
    private final List<UUID> members;
    private final LinkedHashSet<UUID> alive;
    private final Set<UUID> eliminated = new HashSet<>();
    /** Team indexes whose configured Bedfight bed has already triggered its destruction alert. */
    private final Set<Integer> destroyedBedTeams = new HashSet<>();
    private final Set<UUID> defeatShown = new HashSet<>();
    private final Set<UUID> returned = new HashSet<>();
    private final Map<UUID, Integer> teams = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Map<UUID, Location> pendingLobbyRespawns = new HashMap<>();
    private final Map<UUID, BukkitTask> returnTasks = new HashMap<>();
    private final Map<UUID, Integer> matchKills = new HashMap<>();
    private final Map<UUID, Integer> playerRoundWins = new HashMap<>();
    private final Map<Integer, Integer> teamRoundWins = new HashMap<>();
    private int roundsToWin = 1;
    private boolean roundTransitioning;
    private long startedAtMillis;
    private final String kitId;
    private final ArenaData arena;
    private final Mode mode;
    private boolean finishing;
    private boolean countdown;
    /** True only after all online participants have been teleported to their arena spawns. */
    private boolean onArena;
    /** Allows scheduled initial lobby-to-arena teleports through match transition guards. */
    private boolean arenaTeleportInProgress;

    public PartyMatch(UUID partyId, List<UUID> members, String kitId, ArenaData arena, Mode mode) {
        this.partyId = partyId;
        this.members = List.copyOf(members);
        this.alive = new LinkedHashSet<>(members);
        this.kitId = kitId;
        this.arena = arena;
        this.mode = mode;
    }

    public UUID partyId() { return partyId; }
    public List<UUID> members() { return members; }
    public Set<UUID> alive() { return Collections.unmodifiableSet(alive); }
    public Set<UUID> eliminated() { return Collections.unmodifiableSet(eliminated); }
    public Set<UUID> returned() { return Collections.unmodifiableSet(returned); }
    public String kitId() { return kitId; }
    public ArenaData arena() { return arena; }
    public Mode mode() { return mode; }
    public boolean finishing() { return finishing; }
    public void setFinishing(boolean finishing) { this.finishing = finishing; }
    public boolean countdown() { return countdown; }
    public void setCountdown(boolean countdown) { this.countdown = countdown; }
    public boolean onArena() { return onArena; }
    public void setOnArena(boolean onArena) { this.onArena = onArena; }
    public boolean arenaTeleportInProgress() { return arenaTeleportInProgress; }
    public void setArenaTeleportInProgress(boolean inProgress) { this.arenaTeleportInProgress = inProgress; }
    public boolean isAlive(UUID uuid) { return alive.contains(uuid); }
    public boolean isEliminated(UUID uuid) { return eliminated.contains(uuid); }
    public boolean isReturned(UUID uuid) { return returned.contains(uuid); }
    public int team(UUID uuid) { return teams.getOrDefault(uuid, -1); }
    public void setTeam(UUID uuid, int team) { teams.put(uuid, team); }
    /** Returns true only the first time a configured team bed is reported destroyed in this match. */
    public boolean markBedDestroyed(int team) {
        return mode == Mode.SPLIT && (team == 0 || team == 1) && destroyedBedTeams.add(team);
    }
    public boolean bedDestroyed(int team) { return destroyedBedTeams.contains(team); }
    public Map<UUID, Integer> teams() { return Collections.unmodifiableMap(teams); }
    public void markEliminated(UUID uuid) { alive.remove(uuid); eliminated.add(uuid); }
    public void markReturned(UUID uuid) { returned.add(uuid); }
    public boolean markDefeatShown(UUID uuid) { return defeatShown.add(uuid); }
    public Map<UUID, Location> returnLocations() { return returnLocations; }
    public Map<UUID, Location> deathLocations() { return deathLocations; }
    public Map<UUID, Location> pendingLobbyRespawns() { return pendingLobbyRespawns; }
    public Map<UUID, BukkitTask> returnTasks() { return returnTasks; }
    public long startedAtMillis() { return startedAtMillis; }
    public void setStartedAtMillis(long startedAtMillis) { this.startedAtMillis = startedAtMillis; }
    public int kills(UUID uuid) { return matchKills.getOrDefault(uuid, 0); }
    public void recordKill(UUID uuid) { if (uuid != null) matchKills.merge(uuid, 1, Integer::sum); }
    public int roundsToWin() { return roundsToWin; }
    public void setRoundsToWin(int rounds) { roundsToWin = Math.max(1, Math.min(10, rounds)); }
    public int roundWins(UUID uuid) { return playerRoundWins.getOrDefault(uuid, 0); }
    public int recordRoundWin(UUID uuid) { if (uuid != null) return playerRoundWins.merge(uuid, 1, Integer::sum); return 0; }
    public int teamRoundWins(int team) { return teamRoundWins.getOrDefault(team, 0); }
    public int recordTeamRoundWin(int team) { return teamRoundWins.merge(team, 1, Integer::sum); }
    public boolean roundTransitioning() { return roundTransitioning; }
    public void setRoundTransitioning(boolean value) { roundTransitioning = value; }

    /** Re-activates every participant still in this match for the next round. */
    public void resetForNextRound(Set<UUID> activePlayers) {
        alive.clear();
        eliminated.clear();
        defeatShown.clear();
        deathLocations.clear();
        for (UUID uuid : members) {
            if (activePlayers.contains(uuid) && !returned.contains(uuid)) alive.add(uuid);
        }
    }
}
