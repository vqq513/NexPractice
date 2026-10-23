package com.vprac.queue;

import com.vprac.VpracPlugin;
import com.vprac.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.UUID;

/** External /spec sessions for players watching a live solo or party match. */
public final class MatchSpectatorManager {
    private final VpracPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<Object, Set<UUID>> viewersByMatch = new IdentityHashMap<>();
    private final Map<UUID, Long> lastTrackerOpen = new HashMap<>();

    public MatchSpectatorManager(VpracPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isSpectating(UUID uuid) {
        return uuid != null && sessions.containsKey(uuid);
    }

    /** Returns the active match participant currently being watched by an external spectator. */
    public UUID getSpectatingTarget(UUID viewerId) {
        Session session = viewerId == null ? null : sessions.get(viewerId);
        return session == null ? null : session.targetId;
    }

    /** Start spectating the active match that the target player is participating in. */
    public boolean startSpectating(Player viewer, Player target) {
        if (viewer == null || target == null || !viewer.isOnline() || !target.isOnline()) return false;
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            Texts.send(viewer, "You cannot spectate yourself.");
            return false;
        }
        UUID viewerId = viewer.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (plugin.getQueueManager().isInMatch(viewerId)) {
            Texts.send(viewer, "You cannot spectate while you are participating in a match.");
            return false;
        }

        Object match;
        boolean party;
        List<UUID> participants;
        Match soloMatch = plugin.getQueueManager().getMatch(targetId);
        if (soloMatch != null && !soloMatch.finishing() && !soloMatch.countdown()) {
            match = soloMatch;
            party = false;
            participants = List.of(soloMatch.first(), soloMatch.second());
        } else {
            PartyMatch partyMatch = plugin.getPartyMatchManager().getActiveMatch(targetId);
            if (partyMatch == null || partyMatch.finishing() || partyMatch.countdown()) {
                Texts.send(viewer, target.getName() + " is not currently in an active fight.");
                return false;
            }
            match = partyMatch;
            party = true;
            participants = new ArrayList<>(partyMatch.members());
        }

        if (!allAllowSpectators(participants)) {
            Texts.send(viewer, "Someone in that match has disabled spectators in Settings.");
            return false;
        }

        Session existing = sessions.get(viewerId);
        if (existing != null && existing.match == match) {
            Texts.send(viewer, "You are already spectating that match.");
            return true;
        }
        if (existing != null) stopSpectating(viewer, true, true);

        plugin.getQueueManager().removeFromAllQueues(viewerId);
        Session session = new Session(viewerId, viewer.getName(), targetId, match, party, participants);
        sessions.put(viewerId, session);
        viewersByMatch.computeIfAbsent(match, ignored -> new LinkedHashSet<>()).add(viewerId);
        if (!plugin.getMatchVisibilityManager().attachExternalSpectator(match, viewerId)) {
            sessions.remove(viewerId);
            Set<UUID> watchers = viewersByMatch.get(match);
            if (watchers != null) {
                watchers.remove(viewerId);
                if (watchers.isEmpty()) viewersByMatch.remove(match);
            }
            Texts.send(viewer, "That match is no longer available to spectate.");
            return false;
        }

        viewer.closeInventory();
        viewer.closeDialog();
        viewer.getInventory().clear();
        viewer.getInventory().setArmorContents(new ItemStack[4]);
        viewer.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvulnerable(true);
        viewer.setGravity(false);
        viewer.setAllowFlight(true);
        viewer.setFlySpeed(1.0f);
        viewer.setFlying(true);
        viewer.setFallDistance(0.0f);
        plugin.giveMatchSpectatorItems(viewer);
        Location targetLocation = target.getLocation().clone().add(0.0, 2.0, 0.0);
        targetLocation.setYaw(target.getLocation().getYaw());
        targetLocation.setPitch(target.getLocation().getPitch());
        viewer.teleport(targetLocation);
        plugin.getMatchVisibilityManager().refresh();
        Texts.send(viewer, "Now spectating " + target.getName() + ". Right-click the compass to switch players; use the red dye to return to the lobby.");
        broadcast(session, "&7" + viewer.getName() + " is spectating now!");
        return true;
    }

    private boolean allAllowSpectators(List<UUID> participants) {
        if (plugin.getPlayerPreferencesManager() == null) return true;
        for (UUID id : participants) {
            if (!plugin.getPlayerPreferencesManager().areSpectatorsAllowed(id)) return false;
        }
        return true;
    }

    /** Must run on the server thread; returns watchers if a match starts ending or a preference changes. */
    public void maintain() {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!sessionIsActive(session) || !allAllowSpectators(session.participants)) {
                Player viewer = Bukkit.getPlayer(session.viewerId);
                stopSession(session.viewerId, viewer, viewer != null && viewer.isOnline(), true);
            }
        }
    }

    private boolean sessionIsActive(Session session) {
        if (session.partyMatch) {
            PartyMatch match = plugin.getPartyMatchManager().getActiveMatch(session.targetId);
            return match == session.match && match != null && !match.finishing();
        }
        Match match = plugin.getQueueManager().getMatch(session.targetId);
        return match == session.match && match != null && !match.finishing();
    }

    public void openTracker(Player viewer) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || !sessionIsActive(session)) return;
        long now = System.currentTimeMillis();
        Long last = lastTrackerOpen.get(viewer.getUniqueId());
        if (last != null && now - last < 300L) return;
        lastTrackerOpen.put(viewer.getUniqueId(), now);

        SpectatorTrackerHolder holder = new SpectatorTrackerHolder(viewer.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, Texts.component("&8Track Players"));
        holder.setInventory(inventory);
        int slot = 0;
        for (UUID targetId : currentTargets(session)) {
            if (targetId.equals(viewer.getUniqueId()) || slot >= inventory.getSize()) continue;
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = head.getItemMeta();
            if (rawMeta instanceof SkullMeta skull) {
                skull.setOwningPlayer(target);
                skull.displayName(Texts.nonItalic(Texts.component("&b" + target.getName())));
                skull.lore(Texts.nonItalicLore(List.of(Texts.component("&7Click to teleport to this player."))));
                head.setItemMeta(skull);
            }
            inventory.setItem(slot, head);
            holder.mapTarget(slot, targetId);
            slot++;
        }
        if (slot == 0) inventory.setItem(13, new ItemStack(Material.BARRIER));
        viewer.openInventory(inventory);
    }

    private List<UUID> currentTargets(Session session) {
        if (session.partyMatch) {
            PartyMatch match = (PartyMatch) session.match;
            return new ArrayList<>(match.alive());
        }
        Match match = (Match) session.match;
        return List.of(match.first(), match.second());
    }

    public void trackPlayer(Player viewer, UUID targetId) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || targetId == null || !currentTargets(session).contains(targetId)) return;
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            Texts.send(viewer, "That player is no longer in the match.");
            return;
        }
        Location destination = target.getLocation().clone().add(0.0, 1.5, 0.0);
        destination.setYaw(target.getLocation().getYaw());
        destination.setPitch(target.getLocation().getPitch());
        viewer.closeInventory();
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvulnerable(true);
        viewer.setGravity(false);
        viewer.setAllowFlight(true);
        viewer.setFlySpeed(1.0f);
        viewer.setFlying(true);
        viewer.teleport(destination);
        Texts.send(viewer, "Now spectating " + target.getName() + ".");
    }

    public void stopSpectating(Player viewer) {
        if (viewer == null || !sessions.containsKey(viewer.getUniqueId())) return;
        stopSpectating(viewer, true, true);
    }

    private void stopSpectating(Player viewer, boolean returnToLobby, boolean announce) {
        if (viewer == null) return;
        stopSession(viewer.getUniqueId(), viewer, returnToLobby, announce);
    }

    private void stopSession(UUID viewerId, Player viewer, boolean returnToLobby, boolean announce) {
        Session session = sessions.remove(viewerId);
        if (session == null) return;
        if (announce) broadcast(session, "&7" + session.viewerName + " is no longer spectating!");
        Set<UUID> watchers = viewersByMatch.get(session.match);
        if (watchers != null) {
            watchers.remove(viewerId);
            if (watchers.isEmpty()) viewersByMatch.remove(session.match);
        }
        lastTrackerOpen.remove(viewerId);
        plugin.getMatchVisibilityManager().detachPlayer(viewerId);
        plugin.getSpectatorPacketUsers().remove(viewerId);
        if (!returnToLobby || viewer == null || !viewer.isOnline()) return;

        viewer.closeInventory();
        viewer.setFlying(false);
        viewer.setAllowFlight(false);
        viewer.setFlySpeed(0.1f);
        viewer.setGravity(true);
        viewer.setInvulnerable(false);
        viewer.setGameMode(GameMode.SURVIVAL);
        viewer.getInventory().clear();
        viewer.getInventory().setArmorContents(new ItemStack[4]);
        viewer.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        viewer.setFireTicks(0);
        viewer.setFallDistance(0.0f);
        viewer.setFoodLevel(20);
        viewer.setSaturation(20.0f);
        Location lobby = plugin.getLobbySpawn();
        if (lobby == null || lobby.getWorld() == null) lobby = viewer.getWorld().getSpawnLocation();
        viewer.teleport(lobby);
        plugin.giveLobbyItems(viewer);
        Texts.send(viewer, "You are no longer spectating.");
        plugin.getMatchVisibilityManager().refresh();
    }

    private void broadcast(Session session, String message) {
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>(session.participants);
        Set<UUID> watchers = viewersByMatch.get(session.match);
        if (watchers != null) recipients.addAll(watchers);
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient != null && recipient.isOnline()) Texts.send(recipient, message);
        }
    }

    public void endSpectatorsForMatch(Object match) {
        if (match == null) return;
        Set<UUID> watchers = viewersByMatch.get(match);
        if (watchers == null || watchers.isEmpty()) return;
        for (UUID id : new ArrayList<>(watchers)) {
            Player viewer = Bukkit.getPlayer(id);
            stopSession(id, viewer, viewer != null && viewer.isOnline(), true);
        }
    }

    public void stopSpectatorsForParticipant(UUID participantId) {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!session.participants.contains(participantId)) continue;
            Player viewer = Bukkit.getPlayer(session.viewerId);
            stopSession(session.viewerId, viewer, viewer != null && viewer.isOnline(), true);
        }
    }

    public void handleQuit(UUID uuid) {
        Session ownSession = sessions.get(uuid);
        if (ownSession != null) stopSession(uuid, null, false, true);
        stopSpectatorsForParticipant(uuid);
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            stopSession(id, viewer, viewer != null && viewer.isOnline(), false);
        }
        sessions.clear();
        viewersByMatch.clear();
        lastTrackerOpen.clear();
    }

    private static final class Session {
        private final UUID viewerId;
        private final String viewerName;
        private final UUID targetId;
        private final Object match;
        private final boolean partyMatch;
        private final List<UUID> participants;

        private Session(UUID viewerId, String viewerName, UUID targetId, Object match,
                        boolean partyMatch, List<UUID> participants) {
            this.viewerId = viewerId;
            this.viewerName = viewerName;
            this.targetId = targetId;
            this.match = match;
            this.partyMatch = partyMatch;
            this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
        }
    }
}
