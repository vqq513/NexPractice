package com.vprac.visibility;

import com.vprac.VpracPlugin;
import com.vprac.queue.Match;
import com.vprac.queue.PartyMatch;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/** Limits participants' views to their match while preserving visibility from the lobby toward matches. */
public final class MatchVisibilityManager {
    private enum Kind { SOLO, PARTY_FFA, PARTY_SPLIT }
    private static final class Group {
        private final Object key;
        private final Kind kind;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        private final Map<UUID, Integer> teams = new HashMap<>();
        private final Set<UUID> spectators = new HashSet<>();
        private Group(Object key, Kind kind) { this.key = key; this.kind = kind; }
    }
    /** Plain immutable key; avoid record-specific bytecode paths in the Shadow ASM remapper. */
    private static final class Pair {
        private final UUID viewer;
        private final UUID target;

        private Pair(UUID viewer, UUID target) {
            this.viewer = Objects.requireNonNull(viewer, "viewer");
            this.target = Objects.requireNonNull(target, "target");
        }

        private UUID viewer() { return viewer; }
        private UUID target() { return target; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Pair pair)) return false;
            return viewer.equals(pair.viewer) && target.equals(pair.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewer, target);
        }
    }

    private final VpracPlugin plugin;
    private final Map<Object, Group> groups = new IdentityHashMap<>();
    private final Map<UUID, Group> byPlayer = new HashMap<>();
    private final Set<Pair> hiddenByVprac = new HashSet<>();

    public MatchVisibilityManager(VpracPlugin plugin) { this.plugin = plugin; }

    public void registerSolo(Match match) {
        Map<UUID, Integer> teams = new HashMap<>();
        teams.put(match.first(), 0);
        teams.put(match.second(), 1);
        register(match, Kind.SOLO, teams, List.of(match.first(), match.second()));
    }

    public void registerParty(PartyMatch match) {
        register(match, match.mode() == PartyMatch.Mode.FFA ? Kind.PARTY_FFA : Kind.PARTY_SPLIT,
                match.teams(), match.members());
    }

    private void register(Object key, Kind kind, Map<UUID, Integer> teams, Collection<UUID> members) {
        for (UUID member : members) detachInternal(member);
        Group group = new Group(key, kind);
        for (UUID member : members) {
            group.members.add(member);
            group.teams.put(member, teams.getOrDefault(member, -1));
            byPlayer.put(member, group);
        }
        groups.put(key, group);
        refresh();
    }

    public void markSpectator(UUID uuid) {
        Group group = byPlayer.get(uuid);
        if (group != null && group.spectators.add(uuid)) refresh();
    }

    /** Attaches an outside spectator to a registered match visibility group without making them a fighter. */
    public boolean attachExternalSpectator(Object matchKey, UUID spectatorId) {
        Group group = groups.get(matchKey);
        if (group == null || spectatorId == null) return false;
        detachInternal(spectatorId);
        byPlayer.put(spectatorId, group);
        group.spectators.add(spectatorId);
        refresh();
        return true;
    }

    public void clearSpectator(UUID uuid) {
        Group group = byPlayer.get(uuid);
        if (group != null && group.spectators.remove(uuid)) refresh();
    }

    /** Called when a participant returns to the lobby or disconnects. */
    public void detachPlayer(UUID uuid) {
        if (uuid == null) return;
        detachInternal(uuid);
        // Reconcile first so visible pairs receive showPlayer(plugin, target); simply deleting
        // these keys would leave entities hidden after a match has ended.
        refresh();
        // PlayerQuitEvent can run before Bukkit removes the player from its online collection.
        // Prune any old connection's UUID pairs one tick later; a future reconnect will then get
        // fresh hidePlayer calls instead of inheriting stale tracking state.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player current = Bukkit.getPlayer(uuid);
            if (current == null || !current.isOnline()) {
                hiddenByVprac.removeIf(pair -> pair.viewer().equals(uuid) || pair.target().equals(uuid));
            }
        }, 1L);
    }

    public void refresh() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : online) {
            for (Player target : online) {
                if (viewer.equals(target)) continue;
                Pair pair = new Pair(viewer.getUniqueId(), target.getUniqueId());
                boolean shouldHide = shouldHide(viewer.getUniqueId(), target.getUniqueId());
                if (shouldHide) {
                    if (hiddenByVprac.add(pair)) viewer.hidePlayer(plugin, target);
                } else if (hiddenByVprac.remove(pair)) {
                    viewer.showPlayer(plugin, target);
                }
            }
        }
        Set<UUID> onlineIds = new HashSet<>();
        for (Player player : online) onlineIds.add(player.getUniqueId());
        hiddenByVprac.removeIf(pair -> !onlineIds.contains(pair.viewer()) || !onlineIds.contains(pair.target()));
    }

    public void restoreAll() {
        for (Pair pair : new HashSet<>(hiddenByVprac)) {
            Player viewer = Bukkit.getPlayer(pair.viewer());
            Player target = Bukkit.getPlayer(pair.target());
            if (viewer != null && target != null && viewer.isOnline() && target.isOnline()) viewer.showPlayer(plugin, target);
        }
        hiddenByVprac.clear();
        groups.clear();
        byPlayer.clear();
    }

    private boolean shouldHide(UUID viewerId, UUID targetId) {
        Group viewerGroup = byPlayer.get(viewerId);
        Group targetGroup = byPlayer.get(targetId);
        // Lobby/outside players may still see match participants. Only a match participant's
        // view is restricted to that exact match.
        if (viewerGroup == null) return false;
        if (targetGroup == null || viewerGroup != targetGroup) return true;
        boolean viewerSpectator = viewerGroup.spectators.contains(viewerId);
        boolean targetSpectator = viewerGroup.spectators.contains(targetId);
        if (viewerSpectator) return targetSpectator;
        if (targetSpectator) return true;
        // Teammates remain visible in tab/world; friendly-fire is prevented by the combat listener.
        return false;
    }

    private void detachInternal(UUID uuid) {
        Group group = byPlayer.remove(uuid);
        if (group == null) return;
        group.members.remove(uuid);
        group.teams.remove(uuid);
        group.spectators.remove(uuid);
        if (group.members.isEmpty()) groups.remove(group.key);
    }
}
