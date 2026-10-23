package com.vprac.queue;

import com.vprac.VpracPlugin;
import com.vprac.arena.ArenaData;
import com.vprac.party.Party;
import com.vprac.util.Texts;
import com.vprac.util.TitleAnimations;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Owns party FFA and split-team matches, isolated from solo 1v1 queue state. */
public final class PartyMatchManager {
    public enum Mode { FFA, SPLIT }

    private final VpracPlugin plugin;
    private final Map<UUID, PartyMatch> byPlayer = new HashMap<>();
    private final Map<UUID, PartyMatch> byParty = new HashMap<>();
    private final Map<UUID, Long> lastTrackerOpen = new HashMap<>();
    /** Original tab names, restored when a Party Split participant returns to the lobby. */
    private final Map<UUID, Component> originalTabNames = new HashMap<>();
    /** Tournament team matches share this manager's team combat, spectator and scoreboard lifecycle. */
    private final Map<UUID, Consumer<Set<UUID>>> tournamentCallbacks = new HashMap<>();
    private final Map<UUID, Set<UUID>> tournamentResults = new HashMap<>();
    /** Players awaiting the native respawn hand-off for Bedfight while their team's bed is intact. */
    private final Set<UUID> pendingBedfightRespawns = new HashSet<>();
    /** Players currently watching the shared Bedfight respawn countdown. */
    private final Set<UUID> activeBedfightRespawnCountdowns = new HashSet<>();

    public PartyMatchManager(VpracPlugin plugin) { this.plugin = plugin; }

    public boolean isInMatch(UUID uuid) {
        PartyMatch match = byPlayer.get(uuid);
        return match != null && !match.isReturned(uuid);
    }

    /** True while an eliminated party participant is still in the match as a spectator. */
    public boolean isEliminatedSpectator(UUID uuid) {
        PartyMatch match = byPlayer.get(uuid);
        return match != null && match.isEliminated(uuid) && !match.isReturned(uuid);
    }
    public boolean isPartyActive(UUID partyId) { return byParty.containsKey(partyId); }
    public PartyMatch getMatch(UUID uuid) { return byPlayer.get(uuid); }

    /** Returns null for eliminated players who have already returned to the lobby. */
    public PartyMatch getActiveMatch(UUID uuid) {
        PartyMatch match = byPlayer.get(uuid);
        return match == null || match.isReturned(uuid) ? null : match;
    }

    public boolean isCountdown(UUID uuid) {
        PartyMatch match = getActiveMatch(uuid);
        return match != null && match.countdown() && match.isAlive(uuid) && !match.finishing();
    }

    public boolean isFinishing(UUID uuid) {
        PartyMatch match = getActiveMatch(uuid);
        return match != null && match.finishing();
    }

    public String kitFor(UUID uuid) {
        PartyMatch match = getActiveMatch(uuid);
        return match == null ? null : match.kitId();
    }

    public ArenaData arenaFor(UUID uuid) {
        PartyMatch match = getActiveMatch(uuid);
        return match == null ? null : match.arena();
    }

    public boolean startPartyFight(Player leader, Party party, Mode requestedMode, String kitId) {
        if (party == null || !party.leader().equals(leader.getUniqueId())) {
            Texts.send(leader, "Only the party leader can start a party fight.");
            return false;
        }
        List<UUID> members = new ArrayList<>(party.members());
        if (members.size() < 2 || members.size() > 4) {
            Texts.send(leader, "Party fights require 2 to 4 members because the party arena has four spawn points.");
            return false;
        }
        if (requestedMode == Mode.SPLIT && members.size() != 2 && members.size() != 4) {
            Texts.send(leader, "Split Fight needs an even party of 2 or 4 players (1v1 or 2v2).");
            return false;
        }
        if (!plugin.getKitManager().exists(kitId)) {
            Texts.send(leader, "The selected party kit no longer exists.");
            return false;
        }
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !member.isOnline()) {
                Texts.send(leader, "Every party member must be online before starting a fight.");
                return false;
            }
            if (plugin.getTournamentManager() != null && plugin.getTournamentManager().isRegistered(uuid)) {
                Texts.send(member, plugin.getConfig().getString("tournaments.messages.unable-state", "&6unable to execute this in ur current state"));
                Texts.send(leader, plugin.getConfig().getString("tournaments.messages.party-member-registered", "A party member is registered in a tournament."));
                return false;
            }
            if (plugin.getQueueManager().getMatch(uuid) != null || isInMatch(uuid)) {
                Texts.send(leader, "A party member is already in a match.");
                return false;
            }
        }

        PartyMatch.Mode mode = requestedMode == Mode.FFA ? PartyMatch.Mode.FFA : PartyMatch.Mode.SPLIT;
        ArenaData arena = mode == PartyMatch.Mode.FFA
                ? plugin.getArenaManager().acquireAvailablePartyFfa(kitId)
                : plugin.getArenaManager().acquireAvailableForPartySplit(kitId);
        if (arena == null) {
            Texts.send(leader, mode == PartyMatch.Mode.FFA
                    ? "No non-Bedfight party FFA arena is available right now."
                    : "No compatible party Split arena with two configured spawns is available right now.");
            return false;
        }
        List<Location> spawns;
        if (mode == PartyMatch.Mode.FFA) {
            spawns = plugin.getArenaManager().getPartyFfaSpawns(arena);
            if (spawns.size() != 4) {
                plugin.getArenaManager().release(arena);
                Texts.send(leader, "That party arena no longer has four safe cardinal spawn positions. Reconfigure its party spawns.");
                return false;
            }
        } else {
            if (arena.spawnOne() == null || arena.spawnTwo() == null) {
                plugin.getArenaManager().release(arena);
                Texts.send(leader, "That Split arena needs both /varena setspawn locations configured.");
                return false;
            }
            spawns = List.of(arena.spawnOne(), arena.spawnTwo());
        }

        // Remove any solo queue selections before claiming participants for the party fight.
        for (UUID uuid : members) plugin.getQueueManager().removeFromAllQueues(uuid);
        PartyMatch match = new PartyMatch(party.id(), members, kitId, arena, mode);
        // The rounds-to-win kit option belongs to solo queues only. Party FFA/Split is one fight.
        match.setRoundsToWin(1);
        if (mode == PartyMatch.Mode.SPLIT) {
            // For 2v2, group adjacent cardinal points on opposing diagonals (North+East vs South+West).
            // For 1v1, use the north and south opposite points.
            for (int i = 0; i < members.size(); i++) {
                int team = members.size() == 2 ? i : ((i == 0 || i == 3) ? 0 : 1);
                match.setTeam(members.get(i), team);
            }
        } else {
            for (int i = 0; i < members.size(); i++) match.setTeam(members.get(i), i);
        }
        byParty.put(party.id(), match);
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            byPlayer.put(uuid, match);
            match.returnLocations().put(uuid, member.getLocation().clone());
        }
        if (mode == PartyMatch.Mode.SPLIT) applySplitTabNames(match);

        // Show the match-found transition first and keep participants in the lobby for 3 seconds.
        // Players may move freely during this transition; countdown movement restrictions begin
        // only after the party has been teleported into the arena.
        match.setCountdown(true);
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !member.isOnline()) continue;
            member.setInvulnerable(true);
            member.setVelocity(new Vector(0, 0, 0));
            showMatchFound(member);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;

            for (int i = 0; i < members.size(); i++) {
                UUID uuid = members.get(i);
                if (!match.isAlive(uuid)) continue; // A player may have disconnected during the title delay.
                Player member = Bukkit.getPlayer(uuid);
                if (member == null || !member.isOnline()) continue;
                Location spawn = chooseSpawn(match, i, spawns);
                match.setArenaTeleportInProgress(true);
                try {
                    prepareForPartyMatch(member, match.kitId(), spawn);
                } finally {
                    match.setArenaTeleportInProgress(false);
                }
                Texts.send(member, mode == PartyMatch.Mode.FFA
                        ? "Party FFA started. Every player fights individually."
                        : "Party Split Fight started. Teams have been balanced automatically.");
            }

            // The match sidebar should not appear while participants are still in the lobby.
            match.setOnArena(true);
            plugin.getMatchVisibilityManager().registerParty(match);
            if (plugin.getPlayerPreferencesManager() != null) {
                for (UUID uuid : members) {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) plugin.getPlayerPreferencesManager().refreshScoreboard(online);
                }
            }
            // A participant can disconnect during the three-second match-found delay. Do not
            // leave offline UUIDs attached when the party visibility group is registered late.
            for (UUID uuid : members) {
                Player online = Bukkit.getPlayer(uuid);
                if (online == null || !online.isOnline()) {
                    plugin.getMatchVisibilityManager().detachPlayer(uuid);
                }
            }
            beginCountdown(match);
        }, 60L);
        return true;
    }

    /**
     * Starts a tournament 2v2 in a normal two-spawn arena. The first fixed team uses spawn 1 and
     * the second fixed team uses spawn 2, while the existing PartyMatch lifecycle handles deaths,
     * eliminated-player spectating, team scoreboard, round resolution and cleanup.
     */
    public boolean startTournamentSplitFight(List<UUID> firstTeam, List<UUID> secondTeam, String kitId,
                                             Consumer<Set<UUID>> completion) {
        if (firstTeam == null || secondTeam == null || firstTeam.size() != 2 || secondTeam.size() != 2
                || !plugin.getKitManager().exists(kitId)) return false;
        List<UUID> members = new ArrayList<>(4);
        members.addAll(firstTeam);
        members.addAll(secondTeam);
        if (new HashSet<>(members).size() != 4) return false;
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !member.isOnline() || plugin.getQueueManager().getMatch(uuid) != null
                    || isInMatch(uuid)) return false;
        }

        ArenaData arena = plugin.getArenaManager().acquireAvailable(kitId);
        if (arena == null || arena.spawnOne() == null || arena.spawnTwo() == null) {
            if (arena != null) plugin.getArenaManager().release(arena);
            return false;
        }
        for (UUID uuid : members) plugin.getQueueManager().removeFromAllQueues(uuid);
        UUID syntheticPartyId = UUID.randomUUID();
        PartyMatch match = new PartyMatch(syntheticPartyId, members, kitId, arena, PartyMatch.Mode.SPLIT);
        match.setRoundsToWin(1);
        for (UUID uuid : firstTeam) match.setTeam(uuid, 0);
        for (UUID uuid : secondTeam) match.setTeam(uuid, 1);
        byParty.put(syntheticPartyId, match);
        if (completion != null) tournamentCallbacks.put(syntheticPartyId, completion);
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            byPlayer.put(uuid, match);
            match.returnLocations().put(uuid, member.getLocation().clone());
        }
        applySplitTabNames(match);

        match.setCountdown(true);
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !member.isOnline()) continue;
            member.setInvulnerable(true);
            member.setVelocity(new Vector(0, 0, 0));
            showMatchFound(member);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;
            Location firstSpawn = match.arena().spawnOne();
            Location secondSpawn = match.arena().spawnTwo();
            for (int i = 0; i < members.size(); i++) {
                UUID uuid = members.get(i);
                if (!match.isAlive(uuid)) continue;
                Player member = Bukkit.getPlayer(uuid);
                if (member == null || !member.isOnline()) continue;
                match.setArenaTeleportInProgress(true);
                try {
                    prepareForPartyMatch(member, match.kitId(), i < 2 ? firstSpawn : secondSpawn);
                } finally {
                    match.setArenaTeleportInProgress(false);
                }
            }
            match.setOnArena(true);
            plugin.getMatchVisibilityManager().registerParty(match);
            for (UUID uuid : members) {
                Player member = Bukkit.getPlayer(uuid);
                if (member != null && member.isOnline() && plugin.getPlayerPreferencesManager() != null) {
                    plugin.getPlayerPreferencesManager().refreshScoreboard(member);
                }
                if (member == null || !member.isOnline()) plugin.getMatchVisibilityManager().detachPlayer(uuid);
            }
            beginCountdown(match);
        }, 60L);
        return true;
    }

    private void showMatchFound(Player player) {
        if (player == null || !player.isOnline()) return;
        player.closeInventory();
        player.closeDialog();
        Title title = Title.title(
                Texts.component("&a&l⚔ Match Found! ⚔"),
                Component.empty(),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2700), Duration.ofMillis(150)));
        player.showTitle(title);
        if (plugin.getPlayerPreferencesManager() != null) {
            plugin.getPlayerPreferencesManager().playSound(player, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.0f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.0f);
        }
    }

    private Location chooseSpawn(PartyMatch match, int index, List<Location> spawns) {
        if (match.mode() == PartyMatch.Mode.SPLIT && spawns.size() == 2) {
            UUID playerId = match.members().get(index);
            return spawns.get(match.team(playerId) == 0 ? 0 : 1);
        }
        if (match.mode() == PartyMatch.Mode.FFA && spawns.size() == 4 && match.members().size() == 3) {
            return spawns.get(new int[]{0, 2, 3}[index]);
        }
        return spawns.get(Math.min(index, spawns.size() - 1));
    }

    private void applySplitTabNames(PartyMatch match) {
        for (UUID uuid : match.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            if (!originalTabNames.containsKey(uuid)) originalTabNames.put(uuid, player.playerListName());
            NamedTextColor color = match.team(uuid) == 0 ? NamedTextColor.BLUE : NamedTextColor.RED;
            player.playerListName(Component.text(player.getName(), color).decoration(TextDecoration.ITALIC, false));
        }
    }

    private void restoreTabName(UUID uuid) {
        if (!originalTabNames.containsKey(uuid)) return;
        Component original = originalTabNames.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) player.playerListName(original);
    }


    private void prepareForPartyMatch(Player player, String kitId, Location spawn) {
        player.closeInventory();
        player.closeDialog();
        player.setGameMode(GameMode.SURVIVAL);
        if (!plugin.getKitProfileManager().applySelected(player, kitId)) plugin.getKitManager().apply(player.getInventory(), kitId);
        if (plugin.getCosmeticsManager() != null) plugin.getCosmeticsManager().applyArmorTrims(player);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
            player.setHealth(Math.min(maxHealth, 20.0));
        }
        player.setFoodLevel(20);
        // Clear exhaustion carried from the lobby/previous match. Unlimited Hunger grants
        // full saturation; when disabled, vanilla survival hunger is allowed to drain normally.
        player.setExhaustion(0.0f);
        player.setSaturation(plugin.getKitRulesManager().enabled(kitId,
                com.vprac.kit.KitRulesManager.Rule.UNLIMITED_HUNGER) ? 20.0f : 0.0f);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setAllowFlight(false);
        player.setGravity(true);
        player.teleport(spawn);
    }

    private void beginCountdown(PartyMatch match) {
        showTitle(match, "3", NamedTextColor.YELLOW);
        playCountdownSound(match, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isActiveCountdown(match)) {
                showTitle(match, "2", NamedTextColor.GOLD);
                playCountdownSound(match, Sound.BLOCK_NOTE_BLOCK_PLING, 1.3f);
            }
        }, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isActiveCountdown(match)) {
                showTitle(match, "1", NamedTextColor.RED);
                playCountdownSound(match, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
            }
        }, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;
            showTitle(match, "Start!", NamedTextColor.GREEN);
            playCountdownSound(match, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f);
            match.setCountdown(false);
            match.setStartedAtMillis(System.currentTimeMillis());
            for (UUID uuid : match.alive()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && !player.isDead()) {
                    player.setInvulnerable(false);
                    player.setVelocity(new Vector(0, 0, 0));
                }
            }
        }, 60L);
    }

    private boolean isActiveCountdown(PartyMatch match) {
        if (match.finishing() || !match.countdown() || !byParty.containsKey(match.partyId())) return false;
        for (UUID uuid : match.alive()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return false;
        }
        return true;
    }

    private void showTitle(PartyMatch match, String text, NamedTextColor color) {
        Title title = Title.title(Component.text(text, color), Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(950), Duration.ZERO));
        for (UUID uuid : match.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && match.isAlive(uuid)) player.showTitle(title);
        }
    }

    private void playCountdownSound(PartyMatch match, Sound sound, float pitch) {
        for (UUID uuid : match.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || !match.isAlive(uuid)) continue;
            if (plugin.getPlayerPreferencesManager() != null) {
                plugin.getPlayerPreferencesManager().playSound(player, sound, 1.0f, pitch);
            } else {
                player.playSound(player.getLocation(), sound, 1.0f, pitch);
            }
        }
    }

    /** True when this was a party-match death and its drops should not enter normal death handling. */
    public boolean handleDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        PartyMatch match = byPlayer.get(dead.getUniqueId());
        if (match == null || match.isReturned(dead.getUniqueId()) || !match.isAlive(dead.getUniqueId())) return false;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        UUID deadId = dead.getUniqueId();
        if (match.roundTransitioning()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
            }, 1L);
            return true;
        }
        Player creditedKiller = dead.getKiller();
        UUID killerId = null;
        Set<UUID> eligibleOpponents = new HashSet<>();
        for (UUID memberId : match.members()) {
            if (memberId.equals(deadId) || !match.isAlive(memberId)) continue;
            if (match.mode() == PartyMatch.Mode.FFA || match.team(memberId) != match.team(deadId)) {
                eligibleOpponents.add(memberId);
            }
        }
        // Prefer explicit Creeper-explosion attribution. Vanilla getKiller() may be null or may
        // retain a prior direct attacker even though the Creeper explosion was the fatal hit.
        killerId = plugin.resolveCreeperKillCredit(dead, eligibleOpponents);
        if (killerId == null && creditedKiller != null && !creditedKiller.getUniqueId().equals(deadId)
                && match.isAlive(creditedKiller.getUniqueId())) {
            boolean memberOfOpposingSide = match.mode() == PartyMatch.Mode.FFA
                    || match.team(creditedKiller.getUniqueId()) != match.team(deadId);
            if (memberOfOpposingSide) killerId = creditedKiller.getUniqueId();
        }
        if (killerId != null) match.recordKill(killerId);
        match.deathLocations().put(deadId, dead.getLocation().clone());

        // Party Split Bedfight uses the same bed-based lives as solo Bedfight. If this player's
        // configured team bed still exists, keep them alive in the match and run the established
        // respawn countdown instead of immediately eliminating them as a normal party death.
        if (canBedfightRespawn(match, deadId)) {
            pendingBedfightRespawns.add(deadId);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
            }, 1L);
            return true;
        }

        match.markEliminated(deadId);
        plugin.getMatchVisibilityManager().markSpectator(deadId);
        clearInventory(dead);
        dead.setInvulnerable(true);
        hideSpectatorFromFighters(match, dead);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
        }, 1L);

        boolean shouldFinish = false;
        Set<UUID> winners = new LinkedHashSet<>();
        if (match.mode() == PartyMatch.Mode.FFA) {
            if (match.alive().size() <= 1) { shouldFinish = true; winners.addAll(match.alive()); }
        } else {
            int deadTeam = match.team(deadId);
            boolean anyRemainingOnDeadTeam = match.alive().stream().anyMatch(uuid -> match.team(uuid) == deadTeam);
            if (!anyRemainingOnDeadTeam) {
                shouldFinish = true;
                for (UUID uuid : match.alive()) if (match.team(uuid) != deadTeam) winners.add(uuid);
            }
        }

        // Eliminated players spectate the rest of the party match instead of being sent to
        // the lobby after three seconds. finishMatch schedules their lobby return when the
        // overall match ends.
        if (shouldFinish) resolveRound(match, winners);
        return true;
    }

    private void resolveRound(PartyMatch match, Set<UUID> proposedWinners) {
        if (match.finishing() || match.roundTransitioning()) return;
        Set<UUID> winners = new LinkedHashSet<>();
        for (UUID uuid : proposedWinners) {
            if (!match.isReturned(uuid) && Bukkit.getPlayer(uuid) != null && Bukkit.getPlayer(uuid).isOnline()) {
                winners.add(uuid);
            }
        }
        if (winners.isEmpty()) {
            // If everyone was disconnected simultaneously there is no valid round winner.
            finishMatch(match, Set.of());
            return;
        }

        int winningTeam = -1;
        UUID ffaWinner = null;
        if (match.mode() == PartyMatch.Mode.FFA) {
            ffaWinner = winners.iterator().next();
            match.recordRoundWin(ffaWinner);
        } else {
            winningTeam = match.team(winners.iterator().next());
            match.recordTeamRoundWin(winningTeam);
            winners.clear();
            for (UUID uuid : match.members()) {
                if (!match.isReturned(uuid) && match.team(uuid) == winningTeam) winners.add(uuid);
            }
        }

        match.setRoundTransitioning(true);
        match.setCountdown(true);
        for (UUID uuid : match.members()) {
            if (match.isReturned(uuid)) continue;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            player.setInvulnerable(true);
            player.setVelocity(new Vector(0, 0, 0));
            clearInventory(player);
        }

        final int targetTeam = winningTeam;
        final UUID targetFfaWinner = ffaWinner;
        final Set<UUID> roundWinnerSet = new LinkedHashSet<>(winners);
        final boolean finalRound = match.mode() == PartyMatch.Mode.FFA
                ? match.roundWins(targetFfaWinner) >= match.roundsToWin()
                : match.teamRoundWins(targetTeam) >= match.roundsToWin();

        if (!finalRound) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : match.members()) {
                if (match.isReturned(uuid)) continue;
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) continue;
                boolean wonRound = match.mode() == PartyMatch.Mode.FFA
                        ? uuid.equals(targetFfaWinner)
                        : match.team(uuid) == targetTeam;
                if (wonRound) {
                    TitleAnimations.showSweep(plugin, player, "🏆Round Won 🏆", Component.empty(),
                            NamedTextColor.GREEN, NamedTextColor.YELLOW, true);
                } else {
                    playLossSound(player);
                    TitleAnimations.showSweep(plugin, player, "💀Round Lost 💀", Component.empty(),
                            NamedTextColor.RED, NamedTextColor.DARK_RED, true, false);
                }
            }
        }, 2L);

        if (finalRound) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (match.finishing()) return;
                Set<UUID> finalWinners = new LinkedHashSet<>();
                for (UUID uuid : match.alive()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (!match.isReturned(uuid) && player != null && player.isOnline()) finalWinners.add(uuid);
                }
                match.setRoundTransitioning(false);
                finishMatch(match, finalWinners);
            }, 2L);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (match.finishing()) return;
            Set<UUID> currentlyOnline = new LinkedHashSet<>();
            for (UUID uuid : match.members()) {
                Player player = Bukkit.getPlayer(uuid);
                if (!match.isReturned(uuid) && player != null && player.isOnline()) currentlyOnline.add(uuid);
            }

            // A quit can end a party match during the result animation; don't start an impossible next round.
            Set<UUID> forcedWinners = winnersAfterForfeits(match, currentlyOnline);
            if (forcedWinners != null) {
                match.setRoundTransitioning(false);
                finishMatch(match, forcedWinners);
                return;
            }

            plugin.getArenaManager().restoreSnapshot(match.arena(), restored -> {
                if (match.finishing()) return;
                if (!restored) {
                    plugin.getLogger().warning("Could not restore party arena " + match.arena().id() + " between rounds; ending the match to protect the arena.");
                    match.setRoundTransitioning(false);
                    finishMatch(match, roundWinnerSet);
                    return;
                }

                Set<UUID> nextRoundPlayers = new LinkedHashSet<>();
                for (UUID uuid : match.members()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (!match.isReturned(uuid) && player != null && player.isOnline()) nextRoundPlayers.add(uuid);
                }
                match.resetForNextRound(nextRoundPlayers);
                List<Location> spawns;
                if (match.mode() == PartyMatch.Mode.FFA) {
                    spawns = plugin.getArenaManager().getPartyFfaSpawns(match.arena());
                    if (spawns.size() != 4) spawns = List.of();
                } else if (match.arena().spawnOne() != null && match.arena().spawnTwo() != null) {
                    spawns = List.of(match.arena().spawnOne(), match.arena().spawnTwo());
                } else {
                    spawns = List.of();
                }
                if (spawns.size() != 4 && spawns.size() != 2) {
                    plugin.getLogger().warning("Party arena " + match.arena().id() + " lost its safe spawns between rounds; ending the match.");
                    match.setRoundTransitioning(false);
                    finishMatch(match, roundWinnerSet);
                    return;
                }
                for (int i = 0; i < match.members().size(); i++) {
                    UUID uuid = match.members().get(i);
                    if (!nextRoundPlayers.contains(uuid)) continue;
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;
                    restoreSpectatorVisibility(player);
                    player.setFlying(false);
                    player.setAllowFlight(false);
                    player.setGravity(true);
                    player.setGameMode(GameMode.SURVIVAL);
                    Location spawn = chooseSpawn(match, i, spawns);
                    prepareForPartyMatch(player, match.kitId(), spawn);
                }
                match.setRoundTransitioning(false);
                match.setCountdown(true);
                match.setOnArena(true);
                plugin.getMatchVisibilityManager().registerParty(match);
                for (UUID uuid : nextRoundPlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline() && plugin.getPlayerPreferencesManager() != null) {
                        plugin.getPlayerPreferencesManager().refreshScoreboard(player);
                    }
                }
                beginCountdown(match);
            });
        }, 65L);
    }

    /** Returns a set when a disconnect has made another round impossible, otherwise null. */
    private Set<UUID> winnersAfterForfeits(PartyMatch match, Set<UUID> active) {
        if (match.mode() == PartyMatch.Mode.FFA) {
            if (active.size() <= 1) return new LinkedHashSet<>(active);
            return null;
        }
        Set<Integer> activeTeams = new HashSet<>();
        for (UUID uuid : active) activeTeams.add(match.team(uuid));
        if (activeTeams.size() <= 1) {
            Set<UUID> winners = new LinkedHashSet<>();
            for (UUID uuid : active) if (activeTeams.contains(match.team(uuid))) winners.add(uuid);
            return winners;
        }
        return null;
    }

    /** Voluntary /leave during a party match. Tournament 2v2 forfeits the entire bracket team. */
    public boolean forfeit(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        PartyMatch match = getActiveMatch(uuid);
        if (match == null || match.finishing() || !match.isAlive(uuid)) return false;
        String name = player.getName();
        Component announcement = Texts.component("&7" + name + " forfeited");
        for (UUID memberId : match.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) member.sendMessage(announcement);
        }
        boolean tournamentMatch = tournamentCallbacks.containsKey(match.partyId());
        if (tournamentMatch && plugin.getTournamentManager() != null) plugin.getTournamentManager().handleQuit(uuid);
        if (tournamentMatch && match.mode() == PartyMatch.Mode.SPLIT) {
            int losingTeam = match.team(uuid);
            Set<UUID> winners = new LinkedHashSet<>();
            for (UUID memberId : match.members()) {
                if (match.team(memberId) != losingTeam && match.isAlive(memberId) && !match.isReturned(memberId)) winners.add(memberId);
            }
            for (UUID memberId : match.members()) {
                if (match.team(memberId) != losingTeam || !match.isAlive(memberId)) continue;
                match.markEliminated(memberId);
                Player member = Bukkit.getPlayer(memberId);
                if (member != null && member.isOnline()) scheduleLobbyReturn(match, memberId, 1L);
                else match.markReturned(memberId);
            }
            finishMatch(match, winners);
            return true;
        }
        match.markEliminated(uuid);
        plugin.getMatchVisibilityManager().markSpectator(uuid);
        returnPlayerToLobby(match, uuid);
        checkForFinishedAfterQuit(match, uuid);
        return true;
    }

    /** Handles disconnects as eliminations, so a party fight cannot remain stuck forever. */
    public void handleQuit(UUID uuid) {
        pendingBedfightRespawns.remove(uuid);
        activeBedfightRespawnCountdowns.remove(uuid);
        PartyMatch match = byPlayer.get(uuid);
        if (match == null || match.isReturned(uuid)) return;
        if (match.isAlive(uuid) && !match.finishing()) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString().substring(0, 8));
            Component announcement = Texts.component("&7" + name + " forfeited");
            for (UUID memberId : match.members()) {
                Player online = Bukkit.getPlayer(memberId);
                if (online != null && online.isOnline()) online.sendMessage(announcement);
            }
        }
        restoreTabName(uuid);
        if (match.isEliminated(uuid)) {
            // Restore this plugin's entity visibility before the spectator connection disappears.
            Player quittingSpectator = Bukkit.getPlayer(uuid);
            if (quittingSpectator != null) restoreSpectatorVisibility(quittingSpectator);
            // A watcher who disconnects no longer occupies a live spectator slot.
            match.markReturned(uuid);
            match.deathLocations().remove(uuid);
            lastTrackerOpen.remove(uuid);
            BukkitTask watcherTask = match.returnTasks().remove(uuid);
            if (watcherTask != null) watcherTask.cancel();
            return;
        }
        if (!match.isAlive(uuid)) return;
        match.markEliminated(uuid);
        match.markReturned(uuid); // disconnected players no longer need a teleport
        match.deathLocations().remove(uuid);
        lastTrackerOpen.remove(uuid);
        BukkitTask task = match.returnTasks().remove(uuid);
        if (task != null) task.cancel();
        checkForFinishedAfterQuit(match, uuid);
    }

    private void checkForFinishedAfterQuit(PartyMatch match, UUID goneId) {
        if (match.finishing()) return;
        Set<UUID> winners = new LinkedHashSet<>();
        boolean finish;
        if (match.mode() == PartyMatch.Mode.FFA) {
            finish = match.alive().size() <= 1;
            if (finish) winners.addAll(match.alive());
        } else {
            int goneTeam = match.team(goneId);
            boolean anyRemainingOnGoneTeam = match.alive().stream().anyMatch(uuid -> match.team(uuid) == goneTeam);
            finish = !anyRemainingOnGoneTeam;
            if (finish) for (UUID uuid : match.alive()) if (match.team(uuid) != goneTeam) winners.add(uuid);
        }
        if (finish) resolveRound(match, winners);
    }

    private void finishMatch(PartyMatch match, Set<UUID> winners) {
        if (match.finishing()) return;
        match.setFinishing(true);
        match.setCountdown(false);
        match.setRoundTransitioning(false);
        if (plugin.getMatchSpectatorManager() != null) plugin.getMatchSpectatorManager().endSpectatorsForMatch(match);
        Set<UUID> winnerSet = new HashSet<>(winners);
        if (tournamentCallbacks.containsKey(match.partyId())) {
            tournamentResults.put(match.partyId(), Set.copyOf(winnerSet));
        }
        // Party fights and tournament 2v2 bouts deliberately do not affect persistent player stats or kit ranks.
        for (UUID uuid : match.members()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && !match.isReturned(uuid)) {
                if (winnerSet.contains(uuid)) {
                    showVictory(player);
                    clearInventory(player);
                    player.setInvulnerable(true);
                } else if (match.markDefeatShown(uuid)) {
                    playLossSound(player);
                    showDefeat(player);
                    clearInventory(player);
                    player.setInvulnerable(true);
                }
                player.setVelocity(new Vector(0, 0, 0));
            }
            scheduleLobbyReturn(match, uuid, 60L);
        }
        // Wait until the 3-second result return operations have completed before restoring/releasing the arena.
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreAndRelease(match), 65L);
    }

    private void scheduleLobbyReturn(PartyMatch match, UUID uuid, long delay) {
        if (match.isReturned(uuid)) return;
        BukkitTask old = match.returnTasks().remove(uuid);
        if (old != null) old.cancel();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            match.returnTasks().remove(uuid);
            returnPlayerToLobby(match, uuid);
        }, delay);
        match.returnTasks().put(uuid, task);
    }

    private void returnPlayerToLobby(PartyMatch match, UUID uuid) {
        if (match.isReturned(uuid)) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            plugin.getMatchVisibilityManager().detachPlayer(uuid);
            markReturned(match, uuid);
            return;
        }
        Location target = plugin.getLobbySpawn();
        if (target == null) target = match.returnLocations().get(uuid);
        if (target == null || target.getWorld() == null) target = player.getWorld().getSpawnLocation();
        Location finalTarget = target.clone();
        if (player.isDead()) {
            match.pendingLobbyRespawns().put(uuid, finalTarget);
            player.spigot().respawn();
            // PlayerRespawnEvent schedules the lobby inventory reset one tick after respawn.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!match.isReturned(uuid)) {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline() && !online.isDead()) resetPlayerToLobby(online, finalTarget);
                    markReturned(match, uuid);
                }
            }, 1L);
        } else {
            resetPlayerToLobby(player, finalTarget);
            markReturned(match, uuid);
        }
    }

    private void resetPlayerToLobby(Player player, Location target) {
        restoreTabName(player.getUniqueId());
        restoreSpectatorVisibility(player);
        player.setInvulnerable(false);
        player.setGravity(true);
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setFlySpeed(0.1f);
        clearInventory(player);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setAllowFlight(false);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.teleport(target);
        plugin.getMatchVisibilityManager().detachPlayer(player.getUniqueId());
        plugin.giveLobbyItems(player);
    }

    private void markReturned(PartyMatch match, UUID uuid) {
        match.markReturned(uuid);
        pendingBedfightRespawns.remove(uuid);
        activeBedfightRespawnCountdowns.remove(uuid);
        match.pendingLobbyRespawns().remove(uuid);
        match.deathLocations().remove(uuid);
        lastTrackerOpen.remove(uuid);
    }

    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
    }

    private void playLossSound(Player player) {
        if (player == null || !player.isOnline()) return;
        if (plugin.getPlayerPreferencesManager() != null) {
            plugin.getPlayerPreferencesManager().playSound(player, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
        }
    }

    private void showVictory(Player winner) {
        Component subtitle = Component.text(winner.getName() + " Won the Match!", NamedTextColor.GREEN);
        TitleAnimations.showSweep(plugin, winner, "🏆 Victory! 🏆", subtitle,
                NamedTextColor.GOLD, NamedTextColor.YELLOW, false);
    }

    private void showDefeat(Player loser) {
        Component subtitle = Component.text("You lost!", NamedTextColor.RED);
        TitleAnimations.showSweep(plugin, loser, "☠ Defeat! ☠", subtitle,
                NamedTextColor.DARK_RED, NamedTextColor.RED, false, false);
    }

    private void restoreAndRelease(PartyMatch match) {
        plugin.getArenaManager().restoreSnapshot(match.arena(), restored -> {
            if (!restored) {
                plugin.getLogger().warning("Party arena " + match.arena().id() + " could not be restored; disabling it to prevent reuse of a dirty arena.");
                plugin.getArenaManager().setEnabled(match.arena().id(), false);
            }
            for (UUID uuid : match.members()) {
                restoreTabName(uuid);
                BukkitTask task = match.returnTasks().remove(uuid);
                if (task != null) task.cancel();
                byPlayer.remove(uuid, match);
                match.pendingLobbyRespawns().remove(uuid);
                match.deathLocations().remove(uuid);
                match.returnLocations().remove(uuid);
            }
            byParty.remove(match.partyId(), match);
            plugin.getArenaManager().release(match.arena());
            plugin.getQueueManager().tryMatchAll();
            Consumer<Set<UUID>> tournamentCallback = tournamentCallbacks.remove(match.partyId());
            Set<UUID> tournamentWinners = tournamentResults.remove(match.partyId());
            if (tournamentCallback != null) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> tournamentCallback.accept(tournamentWinners == null ? Set.of() : tournamentWinners));
            }
        });
    }

    public boolean handleRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PartyMatch match = byPlayer.get(uuid);
        if (match == null) return false;

        Location lobby = match.pendingLobbyRespawns().get(uuid);
        if (lobby != null && lobby.getWorld() != null) {
            event.setRespawnLocation(lobby);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !player.isDead()) resetPlayerToLobby(player, lobby);
                markReturned(match, uuid);
            }, 1L);
            return true;
        }

        if (pendingBedfightRespawns.remove(uuid)) {
            if (canBedfightRespawn(match, uuid)) {
                Location teamSpawn = bedfightSpawnFor(match, uuid);
                if (teamSpawn != null && teamSpawn.getWorld() != null) {
                    // The death location is no longer needed: the player is getting a fresh life.
                    match.deathLocations().remove(uuid);
                    event.setRespawnLocation(teamSpawn);
                    Bukkit.getScheduler().runTask(plugin,
                            () -> beginPartyBedfightRespawnCountdown(player, match));
                    return true;
                }
            }
            // If the bed was destroyed between death and respawn (or no valid spawn remains),
            // this death is decisive. Keep the recorded death location for the spectator path below.
            if (!match.finishing() && match.isAlive(uuid) && !match.isReturned(uuid)) {
                eliminateBedfightRespawningPlayer(match, uuid);
            }
        }

        Location death = match.deathLocations().get(uuid);
        if (death != null && !match.isReturned(uuid) && death.getWorld() != null) {
            // Respawn at the death location; the eliminated player then remains a true spectator
            // until the party match ends or they choose Leave Party.
            Location airborne = death.clone();
            airborne.setY(Math.min(death.getWorld().getMaxHeight() - 2.0, death.getY() + 3.0));
            event.setRespawnLocation(airborne);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && !player.isDead() && !match.isReturned(uuid)) {
                    configureSpectator(player, match);
                }
            });
            return true;
        }
        return false;
    }

    /** True only for an active Party Split Bedfight participant whose own configured bed is intact. */
    private boolean canBedfightRespawn(PartyMatch match, UUID uuid) {
        return match != null && uuid != null && match.mode() == PartyMatch.Mode.SPLIT
                && match.arena() != null && match.arena().bedfightEnabled()
                && match.onArena() && !match.countdown() && !match.finishing()
                && !match.roundTransitioning() && match.isAlive(uuid) && !match.isReturned(uuid)
                && hasIntactPartyBed(match, match.team(uuid));
    }

    /** Team 0 respawns at spawn one; team 1 respawns at spawn two, matching Split's initial spawn mapping. */
    private Location bedfightSpawnFor(PartyMatch match, UUID uuid) {
        if (match == null || match.arena() == null) return null;
        int team = match.team(uuid);
        if (team == 0) return match.arena().spawnOne();
        if (team == 1) return match.arena().spawnTwo();
        return null;
    }

    /** Checks both match state and the actual configured bed blocks so a missing bed never grants a life. */
    private boolean hasIntactPartyBed(PartyMatch match, int team) {
        if (match == null || match.mode() != PartyMatch.Mode.SPLIT || match.arena() == null
                || !match.arena().bedfightEnabled() || (team != 0 && team != 1) || match.bedDestroyed(team)) {
            return false;
        }
        Location configuredFoot = team == 0 ? match.arena().bedA() : match.arena().bedB();
        if (configuredFoot == null || configuredFoot.getWorld() == null) return false;
        Block foot = configuredFoot.getBlock();
        if (!(foot.getBlockData() instanceof Bed footData) || footData.getPart() != Bed.Part.FOOT) return false;
        Block head = foot.getRelative(footData.getFacing());
        return head.getBlockData() instanceof Bed headData && headData.getPart() == Bed.Part.HEAD
                && headData.getFacing() == footData.getFacing();
    }

    private void beginPartyBedfightRespawnCountdown(Player player, PartyMatch expectedMatch) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || player.isDead()
                || byPlayer.get(uuid) != expectedMatch || expectedMatch.finishing()
                || expectedMatch.roundTransitioning() || expectedMatch.countdown()
                || !expectedMatch.isAlive(uuid) || expectedMatch.isReturned(uuid)) return;

        // The bed can be destroyed in the tick between PlayerRespawnEvent and this task.
        // Do not let that race grant a free life without the normal countdown/elimination rule.
        if (!hasIntactPartyBed(expectedMatch, expectedMatch.team(uuid))) {
            expectedMatch.deathLocations().put(uuid, player.getLocation().clone());
            eliminateBedfightRespawningPlayer(expectedMatch, uuid);
            if (!expectedMatch.isReturned(uuid)) configureSpectator(player, expectedMatch);
            return;
        }
        if (!activeBedfightRespawnCountdowns.add(uuid)) return;

        clearInventory(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.setInvulnerable(true);
        player.setGravity(false);
        player.setVelocity(new Vector(0, 0, 0));
        showPartyBedfightRespawnTitle(player, 3, "&a");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isPartyBedfightRespawnCountdownCurrent(player, expectedMatch)) {
                showPartyBedfightRespawnTitle(player, 2, "&d");
            }
        }, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isPartyBedfightRespawnCountdownCurrent(player, expectedMatch)) {
                showPartyBedfightRespawnTitle(player, 1, "&c");
            }
        }, 40L);
        // Match solo Bedfight's timing: the death/respawn hand-off plus this 3 -> 2 -> 1 timer
        // gives the same roughly four-second death-to-re-entry interval.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> completePartyBedfightRespawn(player, expectedMatch), 60L);
    }

    private void showPartyBedfightRespawnTitle(Player player, int seconds, String color) {
        Title title = Title.title(Texts.component("&cYOU DIED!").decorate(TextDecoration.BOLD),
                Texts.component("&7Respawning in " + color + seconds),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
        player.showTitle(title);
    }

    private boolean isPartyBedfightRespawnCountdownCurrent(Player player, PartyMatch match) {
        return player != null && player.isOnline() && !player.isDead()
                && activeBedfightRespawnCountdowns.contains(player.getUniqueId())
                && byPlayer.get(player.getUniqueId()) == match && match.isAlive(player.getUniqueId())
                && !match.finishing() && !match.roundTransitioning()
                && hasIntactPartyBed(match, match.team(player.getUniqueId()));
    }

    private void completePartyBedfightRespawn(Player player, PartyMatch match) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || byPlayer.get(uuid) != match
                || match.finishing() || !match.isAlive(uuid) || match.isReturned(uuid)) {
            activeBedfightRespawnCountdowns.remove(uuid);
            return;
        }
        activeBedfightRespawnCountdowns.remove(uuid);
        if (!hasIntactPartyBed(match, match.team(uuid))) {
            // If the team's bed was destroyed during the countdown, the player loses this pending life.
            match.deathLocations().put(uuid, player.getLocation().clone());
            eliminateBedfightRespawningPlayer(match, uuid);
            if (!match.isReturned(uuid)) configureSpectator(player, match);
            return;
        }

        Location spawn = bedfightSpawnFor(match, uuid);
        if (spawn == null || spawn.getWorld() == null) {
            match.deathLocations().put(uuid, player.getLocation().clone());
            eliminateBedfightRespawningPlayer(match, uuid);
            if (!match.isReturned(uuid)) configureSpectator(player, match);
            return;
        }

        match.deathLocations().remove(uuid);
        player.clearTitle();
        prepareForPartyMatch(player, match.kitId(), spawn);
        player.setInvulnerable(true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PartyMatch current = byPlayer.get(uuid);
            if (player.isOnline() && !player.isDead() && current == match && match.isAlive(uuid)
                    && !match.finishing() && !match.countdown() && !match.roundTransitioning()) {
                player.setInvulnerable(false);
            }
        }, 10L);
    }

    /** Eliminates a player whose bed was lost during their pending respawn and checks for team defeat. */
    private void eliminateBedfightRespawningPlayer(PartyMatch match, UUID uuid) {
        if (match == null || match.finishing() || !match.isAlive(uuid) || match.isReturned(uuid)) return;
        int losingTeam = match.team(uuid);
        match.markEliminated(uuid);
        plugin.getMatchVisibilityManager().markSpectator(uuid);
        boolean teammateAlive = match.alive().stream().anyMatch(member -> match.team(member) == losingTeam);
        if (!teammateAlive) {
            Set<UUID> winners = new LinkedHashSet<>();
            for (UUID member : match.alive()) {
                if (match.team(member) != losingTeam && !match.isReturned(member)) winners.add(member);
            }
            resolveRound(match, winners);
        }
    }

    private void configureSpectator(Player player, PartyMatch match) {
        player.closeInventory();
        player.setInvulnerable(true);
        player.setGravity(false);
        player.setAllowFlight(true);
        // The requested spectator-like state uses GameMode 2 (ADVENTURE), not vanilla SPECTATOR (3).
        // Flight/visibility are controlled explicitly to keep the eliminated player out of active combat.
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlySpeed(1.0f);
        player.setFlying(true);
        player.setFallDistance(0.0f);
        clearInventory(player);
        plugin.givePartySpectatorItems(player);
        hideSpectatorFromFighters(match, player);
        player.updateInventory();
    }

    private void hideSpectatorFromFighters(PartyMatch match, Player spectator) {
        plugin.getMatchVisibilityManager().markSpectator(spectator.getUniqueId());
    }

    private void restoreSpectatorVisibility(Player player) {
        plugin.getMatchVisibilityManager().detachPlayer(player.getUniqueId());
    }

    /** Opens a chest-style tracker for an eliminated player who is watching a live party match. */
    public void openSpectatorTracker(Player spectator) {
        UUID viewerId = spectator.getUniqueId();
        PartyMatch match = byPlayer.get(viewerId);
        if (match == null || match.isReturned(viewerId) || !match.isEliminated(viewerId) || match.finishing()
                || spectator.getGameMode() != GameMode.ADVENTURE || !spectator.getAllowFlight()) return;
        long now = System.currentTimeMillis();
        Long last = lastTrackerOpen.get(viewerId);
        if (last != null && now - last < 350L) return;
        lastTrackerOpen.put(viewerId, now);

        SpectatorTrackerHolder holder = new SpectatorTrackerHolder(viewerId);
        org.bukkit.inventory.Inventory inventory = Bukkit.createInventory(holder, 27, Texts.component("&8Track Players"));
        holder.setInventory(inventory);
        int slot = 0;
        for (UUID targetId : match.alive()) {
            if (targetId.equals(viewerId) || slot >= inventory.getSize()) continue;
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta rawMeta = head.getItemMeta();
            if (rawMeta instanceof SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(target);
                skullMeta.displayName(Texts.nonItalic(Component.text(target.getName(), NamedTextColor.AQUA)));
                skullMeta.lore(Texts.nonItalicLore(List.of(
                        Component.text("Click to teleport to this player", NamedTextColor.GRAY),
                        Component.text("You will remain in spectator flight mode", NamedTextColor.DARK_GRAY))));
                head.setItemMeta(skullMeta);
            }
            inventory.setItem(slot, head);
            holder.mapTarget(slot, targetId);
            slot++;
        }
        if (slot == 0) {
            inventory.setItem(13, new ItemStack(Material.BARRIER));
        }
        spectator.openInventory(inventory);
    }

    /** Teleports an eliminated spectator to a living participant in the same party match. */
    public void trackPlayer(Player spectator, UUID targetId) {
        UUID spectatorId = spectator.getUniqueId();
        PartyMatch match = byPlayer.get(spectatorId);
        if (match == null || match.isReturned(spectatorId) || !match.isEliminated(spectatorId) || match.finishing()) return;
        if (targetId == null || !match.isAlive(targetId)) return;
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            Texts.send(spectator, "That player is no longer in the match.");
            return;
        }
        Location destination = target.getLocation().clone().add(0.0, 1.5, 0.0);
        destination.setYaw(target.getLocation().getYaw());
        destination.setPitch(target.getLocation().getPitch());
        spectator.closeInventory();
        spectator.setGameMode(GameMode.ADVENTURE);
        spectator.setAllowFlight(true);
        spectator.setFlySpeed(1.0f);
        spectator.setFlying(true);
        spectator.teleport(destination);
        Texts.send(spectator, "Now spectating " + target.getName() + ".");
    }

    /** Lets an eliminated spectator leave their party and immediately return to the lobby. */
    public void leaveAsSpectator(Player spectator) {
        UUID uuid = spectator.getUniqueId();
        PartyMatch match = byPlayer.get(uuid);
        if (match == null || match.isReturned(uuid) || !match.isEliminated(uuid)) return;
        // Tournament 2v2 spectators must remain in the match until their teammate is also eliminated.
        if (plugin.getTournamentManager() != null && plugin.getTournamentManager().isRegistered(uuid)) {
            Texts.send(spectator, plugin.getConfig().getString("tournaments.messages.spectate-until-match-end", "&6You must spectate until your teammate's tournament match ends."));
            return;
        }
        if (plugin.getPartyManager() != null) plugin.getPartyManager().leaveAsSpectator(spectator);
        BukkitTask task = match.returnTasks().remove(uuid);
        if (task != null) task.cancel();
        spectator.closeInventory();
        restoreSpectatorVisibility(spectator);
        returnPlayerToLobby(match, uuid);
    }

    public void maintainMatchNeeds() {
        for (PartyMatch match : new HashSet<>(byParty.values())) {
            for (UUID uuid : match.alive()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline() || player.isDead()) continue;

                if (match.onArena()) plugin.maintainKitHunger(player, match.kitId());
                if (match.onArena() && !match.countdown() && !match.finishing() && player.isInvulnerable()) {
                    // Countdown protection ends as soon as active party combat begins.
                    player.setInvulnerable(false);
                }
                if (match.finishing() || (match.countdown() && match.onArena())) {
                    player.setVelocity(new Vector(0, 0, 0));
                }
            }
        }
    }
}
