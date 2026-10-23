package com.vprac.queue;

import com.vprac.VpracPlugin;
import com.vprac.arena.ArenaData;
import com.vprac.leaderboard.KitRankProgression;
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
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public final class QueueManager {
    private final VpracPlugin plugin;
    private final Map<String, LinkedHashSet<UUID>> queues = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<String>> playerQueues = new HashMap<>();
    /** Per-kit queue-entry timestamps; newest entry is the actionbar focus when multi-queueing. */
    private final Map<UUID, LinkedHashMap<String, Long>> queuedAt = new HashMap<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    /** Completion callbacks for bracket-controlled 1v1 matches, invoked after cleanup and arena release. */
    private final Map<Match, Consumer<UUID>> tournamentCallbacks = new IdentityHashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    private final Map<UUID, Location> pendingDeathLocations = new HashMap<>();
    private final Map<UUID, Location> pendingLobbyRespawnLocations = new HashMap<>();
    private final Set<UUID> pendingBedfightRespawns = new HashSet<>();
    private final Set<UUID> activeBedfightRespawnCountdowns = new HashSet<>();

    public QueueManager(VpracPlugin plugin) { this.plugin = plugin; }

    public boolean isQueued(UUID player, String kit) {
        return playerQueues.getOrDefault(player, new LinkedHashSet<>()).contains(normalize(kit));
    }

    public int count(String kit) { return queues.getOrDefault(normalize(kit), new LinkedHashSet<>()).size(); }
    public int totalCount() { return queues.values().stream().mapToInt(Set::size).sum(); }
    /** Number of distinct players currently waiting in at least one solo queue. */
    public int queueingPlayersCount() {
        return (int) playerQueues.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .filter(entry -> Bukkit.getPlayer(entry.getKey()) != null)
                .count();
    }
    public Set<String> getPlayerQueues(UUID player) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(playerQueues.getOrDefault(player, new LinkedHashSet<>())));
    }
    public boolean isInMatch(UUID uuid) { return matches.containsKey(uuid) || (plugin.getPartyMatchManager() != null && plugin.getPartyMatchManager().isInMatch(uuid)); }
    public Match getMatch(UUID uuid) { return matches.get(uuid); }

    public void toggle(Player player, String kitId) {
        String id = normalize(kitId);
        if (!plugin.getKitManager().exists(id)) return;
        UUID uuid = player.getUniqueId();
        if (plugin.getTournamentManager() != null && plugin.getTournamentManager().isRegistered(uuid)) {
            Texts.send(player, plugin.getConfig().getString("tournaments.messages.unable-state", "&6unable to execute this in ur current state"));
            return;
        }
        if (plugin.getPartyManager() != null && plugin.getPartyManager().getParty(uuid) != null) {
            Texts.send(player, "Leave your party before joining or leaving solo queues.");
            return;
        }
        if (isInMatch(uuid)) {
            Texts.send(player, "You cannot change queues while in a match.");
            return;
        }

        LinkedHashSet<String> selected = playerQueues.computeIfAbsent(uuid, ignored -> new LinkedHashSet<>());
        if (selected.contains(id)) {
            selected.remove(id);
            LinkedHashSet<UUID> members = queues.get(id);
            if (members != null) {
                members.remove(uuid);
                if (members.isEmpty()) queues.remove(id);
            }
            if (selected.isEmpty()) playerQueues.remove(uuid);
            LinkedHashMap<String, Long> times = queuedAt.get(uuid);
            if (times != null) {
                times.remove(id);
                if (times.isEmpty()) queuedAt.remove(uuid);
            }
            Texts.send(player, "Dequeued from &b" + plugin.getKitManager().getDisplayName(id) + "&r.");
        } else {
            selected.add(id);
            queues.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(uuid);
            queuedAt.computeIfAbsent(uuid, ignored -> new LinkedHashMap<>()).put(id, System.currentTimeMillis());
            Texts.send(player, "Queued for &b" + plugin.getKitManager().getDisplayName(id) + "&r.");
        }

        tryMatchAll();
        updateQueueActionBar(player);
        if (!isInMatch(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !isInMatch(uuid)) plugin.openQueueDialog(player, false);
            }, 1L);
        }
    }

    /** Starts a direct duel through the exact same arena, kit, countdown and result flow as solo matchmaking. */
    public boolean startDuel(Player first, Player second, String kitId) {
        String id = normalize(kitId);
        if (first == null || second == null || !first.isOnline() || !second.isOnline()) return false;
        if (first.getUniqueId().equals(second.getUniqueId())) {
            Texts.send(first, "You cannot duel yourself.");
            return false;
        }
        if (!plugin.getKitManager().exists(id)) {
            Texts.send(first, "That kit no longer exists.");
            return false;
        }
        if (plugin.getTournamentManager() != null
                && (plugin.getTournamentManager().isRegistered(first.getUniqueId())
                || plugin.getTournamentManager().isRegistered(second.getUniqueId()))) {
            String blockedMessage = plugin.getConfig().getString("tournaments.messages.unable-state", "&6unable to execute this in ur current state");
            Texts.send(first, blockedMessage);
            Texts.send(second, blockedMessage);
            return false;
        }
        if (isInMatch(first.getUniqueId()) || isInMatch(second.getUniqueId())) {
            Texts.send(first, "Both players must be out of a match to start a duel.");
            Texts.send(second, "Both players must be out of a match to start a duel.");
            return false;
        }
        if (plugin.getPartyManager() != null
                && (plugin.getPartyManager().getParty(first.getUniqueId()) != null
                || plugin.getPartyManager().getParty(second.getUniqueId()) != null)) {
            Texts.send(first, "Both players must leave their parties before dueling.");
            Texts.send(second, "Both players must leave their parties before dueling.");
            return false;
        }
        ArenaData arena = plugin.getArenaManager().acquireAvailable(id);
        if (arena == null) {
            Texts.send(first, "No enabled arena is available for this duel right now.");
            Texts.send(second, "No enabled arena is available for this duel right now.");
            return false;
        }
        removeFromAllQueues(first.getUniqueId());
        removeFromAllQueues(second.getUniqueId());
        startMatch(first.getUniqueId(), second.getUniqueId(), id, arena, false);
        Match directMatch = matches.get(first.getUniqueId());
        if (directMatch != null) directMatch.setRoundsToWin(1);
        return true;
    }

    /**
     * Starts a bracket-controlled 1v1 using the existing solo combat lifecycle. Unlike public duels,
     * tournament fighters may remain in ordinary parties; tournament registration has already
     * validated their state and owns their eligibility.
     */
    public boolean startTournamentDuel(Player first, Player second, String kitId, Consumer<UUID> completion) {
        String id = normalize(kitId);
        if (first == null || second == null || !first.isOnline() || !second.isOnline()
                || first.getUniqueId().equals(second.getUniqueId()) || !plugin.getKitManager().exists(id)) return false;
        if (isInMatch(first.getUniqueId()) || isInMatch(second.getUniqueId())) return false;
        ArenaData arena = plugin.getArenaManager().acquireAvailable(id);
        if (arena == null) return false;
        removeFromAllQueues(first.getUniqueId());
        removeFromAllQueues(second.getUniqueId());
        startMatch(first.getUniqueId(), second.getUniqueId(), id, arena, false);
        Match match = matches.get(first.getUniqueId());
        if (match == null) {
            plugin.getArenaManager().release(arena);
            return false;
        }
        // Tournament rounds are controlled by the bracket, never by a kit's queue round rule.
        match.setRoundsToWin(1);
        tournamentCallbacks.put(match, completion == null ? ignored -> { } : completion);
        return true;
    }

    /** Refreshes actionbars while queued; the most recently selected queue is shown if multi-queueing. */
    public void updateQueueActionBars() {
        for (UUID uuid : new ArrayList<>(queuedAt.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                removeFromAllQueues(uuid);
                continue;
            }
            updateQueueActionBar(player);
        }
    }

    private void updateQueueActionBar(Player player) {
        UUID uuid = player.getUniqueId();
        LinkedHashMap<String, Long> times = queuedAt.get(uuid);
        if (times == null || times.isEmpty() || isInMatch(uuid)) {
            player.sendActionBar(Component.empty());
            return;
        }
        Map.Entry<String, Long> primary = null;
        for (Map.Entry<String, Long> entry : times.entrySet()) primary = entry;
        if (primary == null || !plugin.getKitManager().exists(primary.getKey())) {
            player.sendActionBar(Component.empty());
            return;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - primary.getValue()) / 1000L);
        String kitName = plugin.getKitManager().getDisplayName(primary.getKey());
        player.sendActionBar(Texts.component("&6Queueing for " + kitName + " &9" + elapsedSeconds + "s"));
    }

    public void tryMatchAll() {
        for (String kitId : new ArrayList<>(queues.keySet())) {
            LinkedHashSet<UUID> members = queues.get(kitId);
            if (members == null) continue;
            while (members.size() >= 2) {
                UUID first = null;
                UUID second = null;
                for (UUID uuid : new ArrayList<>(members)) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline() || isInMatch(uuid)) {
                        removeFromAllQueues(uuid);
                        continue;
                    }
                    if (first == null) first = uuid;
                    else {
                        second = uuid;
                        break;
                    }
                }
                if (first == null || second == null) break;
                ArenaData arena = plugin.getArenaManager().acquireAvailableForQueue(kitId);
                if (arena == null) return;
                startMatch(first, second, kitId, arena, true);
                members = queues.get(kitId);
                if (members == null) break;
            }
        }
    }

    private void startMatch(UUID firstId, UUID secondId, String kitId, ArenaData arena, boolean trackStats) {
        Player first = Bukkit.getPlayer(firstId);
        Player second = Bukkit.getPlayer(secondId);
        if (first == null || second == null || !first.isOnline() || !second.isOnline()) {
            plugin.getArenaManager().release(arena);
            if (first == null || !first.isOnline()) removeFromAllQueues(firstId);
            if (second == null || !second.isOnline()) removeFromAllQueues(secondId);
            return;
        }

        removeFromAllQueues(firstId);
        removeFromAllQueues(secondId);
        Match match = new Match(firstId, secondId, kitId, arena);
        match.setStatsTracked(trackStats);
        match.setBedfight(arena.bedfightEnabled());
        // Bedfight uses beds/lives as its win condition. A destroyed bed followed by a death
        // must finish the match instead of entering another configured round.
        match.setRoundsToWin(match.bedfight() ? 1 : plugin.getKitRulesManager().roundsToWin(kitId));
        matches.put(firstId, match);
        matches.put(secondId, match);
        returnLocations.put(firstId, first.getLocation().clone());
        returnLocations.put(secondId, second.getLocation().clone());

        // Notify both players immediately, but leave them in the lobby for three seconds.
        // Players may move during this transition; countdown movement restrictions begin only
        // after both participants have been teleported into the arena.
        match.setCountdown(true);
        first.setInvulnerable(true);
        second.setInvulnerable(true);
        first.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        second.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        showMatchFound(first);
        showMatchFound(second);
        sendMatchStatics(first, second, arena, kitId);
        sendMatchStatics(second, first, arena, kitId);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;
            Player delayedFirst = Bukkit.getPlayer(match.first());
            Player delayedSecond = Bukkit.getPlayer(match.second());
            if (delayedFirst == null || delayedSecond == null
                    || !delayedFirst.isOnline() || !delayedSecond.isOnline()) return;

            // Close a menu/dialog again in case the player reopened one during the title delay.
            match.setArenaTeleportInProgress(true);
            try {
                prepareForMatch(delayedFirst, match.kitId(), match.arena().spawnOne());
                prepareForMatch(delayedSecond, match.kitId(), match.arena().spawnTwo());
            } finally {
                match.setArenaTeleportInProgress(false);
            }
            // Switch the participants to the match sidebar only after they have arrived at the arena.
            match.setOnArena(true);
            plugin.getMatchVisibilityManager().registerSolo(match);
            if (plugin.getPlayerPreferencesManager() != null) {
                plugin.getPlayerPreferencesManager().refreshScoreboard(delayedFirst);
                plugin.getPlayerPreferencesManager().refreshScoreboard(delayedSecond);
            }
            beginCountdown(match);
        }, 60L);
    }

    private void showMatchFound(Player player) {
        if (player == null || !player.isOnline()) return;
        player.closeInventory();
        player.closeDialog();
        player.sendActionBar(Component.empty());
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

    private void sendMatchStatics(Player recipient, Player opponent, ArenaData arena, String kitId) {
        if (!plugin.getConfig().getBoolean("match-statics.enabled", true)) return;

        int opponentWins = plugin.getLeaderboardManager()
                .getPlayerKitStats(opponent.getUniqueId(), kitId).wins();
        String opponentRank = plugin.getPlayerPreferencesManager() == null
                ? KitRankProgression.rankName(opponentWins)
                : plugin.getPlayerPreferencesManager().visibleRank(opponent.getUniqueId(), opponentWins);
        int recipientWins = plugin.getLeaderboardManager()
                .getPlayerKitStats(recipient.getUniqueId(), kitId).wins();
        String recipientRank = plugin.getPlayerPreferencesManager() == null
                ? KitRankProgression.rankName(recipientWins)
                : plugin.getPlayerPreferencesManager().visibleRank(recipient.getUniqueId(), recipientWins);
        String opponentPing = Integer.toString(Math.max(0, opponent.getPing()));
        List<String> lines = plugin.getConfig().getStringList("match-statics.lines");
        if (lines.isEmpty()) {
            lines = List.of(
                    "&6Match Statics:",
                    "",
                    "&6Map: &f%map%",
                    "&6Opponent: &f%opponent%",
                    "&6Rank: &f%opponent_rank%",
                    "&6Their Ping: &f%opponent_ping%ms"
            );
        }

        for (String configuredLine : lines) {
            String line = configuredLine == null ? "" : configuredLine;
            line = line.replace("%map%", arena.id())
                    .replace("%opponent%", opponent.getName())
                    .replace("%rank%", opponentRank)
                    .replace("%opponent_rank%", opponentRank)
                    .replace("%their_rank%", opponentRank)
                    .replace("%opponent_ping%", opponentPing)
                    .replace("%their_ping%", opponentPing)
                    .replace("%kit%", plugin.getKitManager().getDisplayName(kitId))
                    .replace("%player_name%", recipient.getName())
                    .replace("%player_rank%", recipientRank)
                    .replace("%player_ping%", Integer.toString(Math.max(0, recipient.getPing())));
            Texts.send(recipient, line);
        }
    }

    private void beginCountdown(Match match) {
        showCountdownTitle(match, "3", NamedTextColor.YELLOW);
        playCountdownSound(match, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;
            showCountdownTitle(match, "2", NamedTextColor.GOLD);
            playCountdownSound(match, Sound.BLOCK_NOTE_BLOCK_PLING, 1.3f);
        }, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;
            showCountdownTitle(match, "1", NamedTextColor.RED);
            playCountdownSound(match, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        }, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isActiveCountdown(match)) return;
            showCountdownTitle(match, "Start!", NamedTextColor.GREEN);
            playCountdownSound(match, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f);
            match.setCountdown(false);
            match.setStartedAtMillis(System.currentTimeMillis());
            for (UUID uuid : List.of(match.first(), match.second())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline() && !player.isDead()) {
                    player.setInvulnerable(false);
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                }
            }
        }, 60L);
    }

    private void playCountdownSound(Match match, Sound sound, float pitch) {
        for (UUID uuid : List.of(match.first(), match.second())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            if (plugin.getPlayerPreferencesManager() != null) {
                plugin.getPlayerPreferencesManager().playSound(player, sound, 1.0f, pitch);
            } else {
                player.playSound(player.getLocation(), sound, 1.0f, pitch);
            }
        }
    }

    private boolean isActiveCountdown(Match match) {
        if (match == null || match.finishing() || !match.countdown()) return false;
        Player first = Bukkit.getPlayer(match.first());
        Player second = Bukkit.getPlayer(match.second());
        return first != null && first.isOnline() && second != null && second.isOnline();
    }

    private void showCountdownTitle(Match match, String text, NamedTextColor color) {
        Title title = Title.title(
                Component.text(text, color),
                Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(950), Duration.ZERO));
        for (UUID uuid : List.of(match.first(), match.second())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && !player.isDead()) player.showTitle(title);
        }
    }

    private void prepareForMatch(Player player, String kitId, Location spawn) {
        player.closeInventory();
        player.closeDialog();
        player.setGameMode(GameMode.SURVIVAL);
        if (!plugin.getKitProfileManager().applySelected(player, kitId)) {
            plugin.getKitManager().apply(player.getInventory(), kitId);
        }
        if (plugin.getCosmeticsManager() != null) plugin.getCosmeticsManager().applyArmorTrims(player);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
            player.setHealth(Math.min(maxHealth, 20.0));
        }
        player.setFoodLevel(20);
        // Start the match with no carried-over exhaustion. When Unlimited Hunger is off,
        // zero saturation exposes vanilla hunger behavior immediately; afterward VPRAC must
        // not refill food, saturation, or exhaustion for this kit.
        player.setExhaustion(0.0f);
        player.setSaturation(plugin.getKitRulesManager().enabled(kitId,
                com.vprac.kit.KitRulesManager.Rule.UNLIMITED_HUNGER) ? 20.0f : 0.0f);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setAllowFlight(false);
        player.setGravity(true);
        // Keep participants invulnerable until the 3-2-1 countdown reaches Start!.
        player.setInvulnerable(true);
        player.teleport(spawn);
    }

    public void maintainMatchNeeds() {
        for (UUID uuid : new ArrayList<>(matches.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            Match match = matches.get(uuid);
            if (player == null || !player.isOnline() || player.isDead() || match == null) continue;

            if (match.onArena()) plugin.maintainKitHunger(player, match.kitId());

            // NexPractice should protect participants during the start countdown, not during active combat.
            if (match.onArena() && !match.countdown() && !match.finishing() && player.isInvulnerable()) {
                // Countdown protection ends as soon as active arena combat begins.
                player.setInvulnerable(false);
            }
        }
    }

    public void handleDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Match match = matches.get(dead.getUniqueId());
        if (match == null) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        Location deathLocation = dead.getLocation().clone();
        pendingDeathLocations.put(dead.getUniqueId(), deathLocation);

        if (match.finishing() || match.roundTransitioning()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
            }, 1L);
            return;
        }

        UUID deadId = dead.getUniqueId();
        Player creditedKiller = dead.getKiller();
        UUID killerId = plugin.resolveCreeperKillCredit(dead, match.opponent(deadId));
        if (killerId == null) {
            killerId = creditedKiller != null && creditedKiller.getUniqueId().equals(match.opponent(deadId))
                    ? creditedKiller.getUniqueId() : null;
        }
        if (killerId != null) match.recordKill(killerId);
        if (match.statsTracked()) {
            String creditedName = creditedKiller != null ? creditedKiller.getName()
                    : killerId == null ? null : Optional.ofNullable(Bukkit.getOfflinePlayer(killerId).getName()).orElse(null);
            plugin.getLeaderboardManager().recordDeath(deadId, dead.getName(), killerId, creditedName, match.kitId());
        }
        if (match.bedfight() && match.bedIntact(deadId)) {
            // Bed intact: this death consumes a life, not a round. Respawn into spectator mode
            // for four seconds, then restore the selected kit at the player's configured bed spawn.
            pendingDeathLocations.remove(deadId);
            pendingBedfightRespawns.add(deadId);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
            }, 1L);
            return;
        }
        UUID winnerId = match.opponent(deadId);
        if (match.bedfight()) {
            // An intact bed grants repeat lives; without it, the next death ends Bedfight outright.
            finishMatch(match, winnerId, deadId, true);
        } else {
            resolveRound(match, winnerId, deadId);
        }

        // Do not leave the defeated player at a respawn screen during the round transition.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (dead.isOnline() && dead.isDead()) dead.spigot().respawn();
        }, 1L);
    }

    private void resolveRound(Match match, UUID winnerId, UUID loserId) {
        if (match.finishing() || match.roundTransitioning()) return;
        match.recordRoundWin(winnerId);
        boolean finalRound = match.roundWins(winnerId) >= match.roundsToWin();
        match.setRoundTransitioning(true);
        match.setCountdown(true);
        for (UUID uuid : List.of(match.first(), match.second())) {
            Player participant = Bukkit.getPlayer(uuid);
            if (participant == null || !participant.isOnline()) continue;
            participant.setInvulnerable(true);
            participant.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            clearForResult(participant);
        }

        if (!finalRound) {
            playLossSound(Bukkit.getPlayer(loserId));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (match.finishing()) return;
                Player winner = Bukkit.getPlayer(winnerId);
                Player loser = Bukkit.getPlayer(loserId);
                if (winner != null && winner.isOnline()) {
                    TitleAnimations.showSweep(plugin, winner, "🏆Round Won 🏆", Component.empty(),
                            NamedTextColor.GREEN, NamedTextColor.YELLOW, true);
                }
                if (loser != null && loser.isOnline()) {
                    TitleAnimations.showSweep(plugin, loser, "💀Round Lost 💀", Component.empty(),
                            NamedTextColor.RED, NamedTextColor.DARK_RED, true, false);
                }
            }, 2L);
        } else {
            // The decisive round only shows the final Victory/Defeat title, never a round title first.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (match.finishing()) return;
                Player first = Bukkit.getPlayer(match.first());
                Player second = Bukkit.getPlayer(match.second());
                match.setRoundTransitioning(false);
                if (first == null || !first.isOnline()) {
                    finishMatch(match, match.second(), match.first(), false);
                } else if (second == null || !second.isOnline()) {
                    finishMatch(match, match.first(), match.second(), false);
                } else {
                    finishMatch(match, winnerId, loserId, true);
                }
            }, 2L);
        }

        // Let a non-final round-result sweep finish before the next countdown.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (match.finishing() || finalRound) return;
            Player first = Bukkit.getPlayer(match.first());
            Player second = Bukkit.getPlayer(match.second());
            if (first == null || !first.isOnline()) {
                match.setRoundTransitioning(false);
                finishMatch(match, match.second(), match.first(), false);
                return;
            }
            if (second == null || !second.isOnline()) {
                match.setRoundTransitioning(false);
                finishMatch(match, match.first(), match.second(), false);
                return;
            }
            plugin.getArenaManager().restoreSnapshot(match.arena(), restored -> {
                if (match.finishing()) return;
                if (!restored) {
                    plugin.getLogger().warning("Could not restore arena " + match.arena().id() + " between rounds; ending the match to protect the arena.");
                    match.setRoundTransitioning(false);
                    finishMatch(match, winnerId, loserId, true);
                    return;
                }
                Player restoredFirst = Bukkit.getPlayer(match.first());
                Player restoredSecond = Bukkit.getPlayer(match.second());
                if (restoredFirst == null || !restoredFirst.isOnline() || restoredSecond == null || !restoredSecond.isOnline()) {
                    match.setRoundTransitioning(false);
                    if (restoredFirst == null || !restoredFirst.isOnline()) finishMatch(match, match.second(), match.first(), false);
                    else finishMatch(match, match.first(), match.second(), false);
                    return;
                }
                if (restoredFirst.isDead()) restoredFirst.spigot().respawn();
                if (restoredSecond.isDead()) restoredSecond.spigot().respawn();
                if (match.bedfight()) {
                    // A bed remains destroyed for the entire match, even when a multi-round kit
                    // restores the arena schematic between rounds.
                    if (!match.bedIntact(match.first())) removeConfiguredBed(match.arena().bedA());
                    if (!match.bedIntact(match.second())) removeConfiguredBed(match.arena().bedB());
                }
                match.setArenaTeleportInProgress(true);
                try {
                    prepareForMatch(restoredFirst, match.kitId(), match.arena().spawnOne());
                    prepareForMatch(restoredSecond, match.kitId(), match.arena().spawnTwo());
                } finally {
                    match.setArenaTeleportInProgress(false);
                }
                match.setRoundTransitioning(false);
                match.setCountdown(true);
                match.setOnArena(true);
                if (plugin.getPlayerPreferencesManager() != null) {
                    plugin.getPlayerPreferencesManager().refreshScoreboard(restoredFirst);
                    plugin.getPlayerPreferencesManager().refreshScoreboard(restoredSecond);
                }
                beginCountdown(match);
            });
        }, 65L);
    }

    private void removeConfiguredBed(Location footLocation) {
        if (footLocation == null || footLocation.getWorld() == null) return;
        org.bukkit.block.Block foot = footLocation.getBlock();
        if (foot.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
            org.bukkit.block.Block head = foot.getRelative(bed.getFacing());
            foot.setType(Material.AIR, false);
            head.setType(Material.AIR, false);
        } else {
            // Snapshot restoration or another plugin may have left only one half of the bed.
            foot.setType(Material.AIR, false);
            for (org.bukkit.block.BlockFace face : List.of(org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
                org.bukkit.block.Block adjacent = foot.getRelative(face);
                if (adjacent.getBlockData() instanceof org.bukkit.block.data.type.Bed) adjacent.setType(Material.AIR, false);
            }
        }
    }

    private void finishMatch(Match match, UUID winnerId, UUID loserId, boolean loserDied) {
        if (match.finishing()) return;
        match.setFinishing(true);
        match.setCountdown(false);
        match.setRoundTransitioning(false);
        if (plugin.getMatchSpectatorManager() != null) plugin.getMatchSpectatorManager().endSpectatorsForMatch(match);
        Player winner = Bukkit.getPlayer(winnerId);
        Player loser = Bukkit.getPlayer(loserId);
        if (match.statsTracked()) {
            plugin.getLeaderboardManager().recordWin(winnerId, winner == null ? null : winner.getName(), match.kitId());
            plugin.getLeaderboardManager().recordLoss(loserId, loser == null ? null : loser.getName(), match.kitId(), loserDied);
        }

        if (winner != null && winner.isOnline()) {
            winner.sendActionBar(Component.empty());
            showVictory(winner);
            clearForResult(winner);
            winner.setInvulnerable(true);
        }
        if (loserDied && loser != null && loser.isOnline()) showDefeat(loser);
        if (loser != null && loser.isOnline()) {
            playLossSound(loser);
            loser.sendActionBar(Component.empty());
            clearForResult(loser);
            if (!loser.isDead()) loser.setInvulnerable(true);
        }

        // At exactly three seconds, return players to the lobby. Start the potentially slower
        // FAWE schematic restore afterward, while keeping the arena locked from new matches.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            finishPlayerAtLobby(winnerId);
            finishPlayerAtLobby(loserId);
            plugin.getArenaManager().restoreSnapshot(match.arena(), restored -> {
                if (!restored) {
                    plugin.getLogger().warning("Arena " + match.arena().id() + " could not be restored after the match; disabling it to prevent another match on a dirty arena.");
                    plugin.getArenaManager().setEnabled(match.arena().id(), false);
                }
                // Let any forced respawn and its one-tick lobby teleport complete before reusing the arena.
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    pendingDeathLocations.remove(loserId);
                    pendingDeathLocations.remove(winnerId);
                    removeMatch(match);
                    plugin.getArenaManager().release(match.arena());
                    tryMatchAll();
                    Consumer<UUID> tournamentCallback = tournamentCallbacks.remove(match);
                    if (tournamentCallback != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> tournamentCallback.accept(winnerId));
                    }
                }, 2L);
            });
        }, 60L);
    }

    private void playLossSound(Player player) {
        if (player == null || !player.isOnline()) return;
        if (plugin.getPlayerPreferencesManager() != null) {
            plugin.getPlayerPreferencesManager().playSound(player, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f);
        }
    }

    private void clearForResult(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
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

    private void finishPlayerAtLobby(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        Location target = plugin.getLobbySpawn();
        Location preMatchLocation = returnLocations.remove(uuid);
        if (target == null) target = preMatchLocation;
        // Do not let the arena-death respawn location override the lobby destination once the timer ends.
        pendingDeathLocations.remove(uuid);
        if (player == null || !player.isOnline()) {
            pendingLobbyRespawnLocations.remove(uuid);
            plugin.getMatchVisibilityManager().detachPlayer(uuid);
            return;
        }
        if (target == null || target.getWorld() == null) target = player.getWorld().getSpawnLocation();
        Location finalTarget = target.clone();

        if (player.isDead()) {
            // PlayerRespawnEvent consumes this location and uses it for the forced respawn.
            pendingLobbyRespawnLocations.put(uuid, finalTarget);
            player.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingLobbyRespawnLocations.remove(uuid);
                if (player.isOnline() && !player.isDead()) resetPlayerToLobby(player, finalTarget);
            }, 1L);
        } else {
            pendingLobbyRespawnLocations.remove(uuid);
            resetPlayerToLobby(player, finalTarget);
        }
    }

    private void resetPlayerToLobby(Player player, Location target) {
        player.setInvulnerable(false);
        player.setGravity(true);
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
        player.teleport(target);
        plugin.getMatchVisibilityManager().detachPlayer(player.getUniqueId());
        plugin.giveLobbyItems(player);
    }

    /** Forfeits the player's current solo match; persistent queue stats use the normal result path. */
    public boolean forfeit(Player player) {
        if (player == null) return false;
        UUID playerId = player.getUniqueId();
        Match match = matches.get(playerId);
        if (match == null || match.finishing()) return false;
        String message = "&7" + player.getName() + " forfeited";
        Player opponent = Bukkit.getPlayer(match.opponent(playerId));
        Texts.send(player, message);
        if (opponent != null && opponent.isOnline()) Texts.send(opponent, message);
        finishMatch(match, match.opponent(playerId), playerId, false);
        return true;
    }

    public void handleQuit(UUID playerId) {
        removeFromAllQueues(playerId);
        plugin.getMatchVisibilityManager().detachPlayer(playerId);
        Match match = matches.get(playerId);
        if (match != null && !match.finishing()) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName()).orElse(playerId.toString().substring(0, 8));
            Player opponent = Bukkit.getPlayer(match.opponent(playerId));
            if (opponent != null && opponent.isOnline()) Texts.send(opponent, "&7" + name + " forfeited");
            finishMatch(match, match.opponent(playerId), playerId, false);
        }
        returnLocations.remove(playerId);
        pendingDeathLocations.remove(playerId);
        pendingLobbyRespawnLocations.remove(playerId);
        pendingBedfightRespawns.remove(playerId);
        activeBedfightRespawnCountdowns.remove(playerId);
    }

    public void restoreLobbyOnRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location lobbyLocation = pendingLobbyRespawnLocations.remove(uuid);
        if (lobbyLocation != null && lobbyLocation.getWorld() != null) {
            event.setRespawnLocation(lobbyLocation);
            return;
        }
        if (pendingBedfightRespawns.remove(uuid)) {
            Match bedfight = matches.get(uuid);
            if (bedfight != null && bedfight.bedfight() && !bedfight.finishing() && bedfight.bedIntact(uuid)) {
                Location bedSpawn = bedfight.spawnFor(uuid);
                if (bedSpawn != null && bedSpawn.getWorld() != null) {
                    event.setRespawnLocation(bedSpawn);
                    Bukkit.getScheduler().runTask(plugin, () -> beginBedfightRespawnCountdown(player, bedfight));
                    return;
                }
            } else if (bedfight != null && bedfight.bedfight() && !bedfight.finishing() && !bedfight.bedIntact(uuid)) {
                // If the bed was destroyed during the respawn hand-off, the pending death becomes
                // decisive instead of granting a respawn without a bed.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (matches.get(uuid) == bedfight && !bedfight.finishing()) {
                        finishMatch(bedfight, bedfight.opponent(uuid), uuid, true);
                    }
                });
            }
        }
        Match active = matches.get(uuid);
        if (active != null && active.bedfight() && !active.finishing() && active.bedIntact(uuid)) {
            Location bedSpawn = active.spawnFor(uuid);
            if (bedSpawn != null && bedSpawn.getWorld() != null) {
                pendingDeathLocations.remove(uuid);
                event.setRespawnLocation(bedSpawn);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Match current = matches.get(uuid);
                    if (!player.isOnline() || player.isDead() || current != active || current.finishing() || !current.bedIntact(uuid)) return;
                    Location currentSpawn = current.spawnFor(uuid);
                    if (currentSpawn == null || currentSpawn.getWorld() == null) return;
                    prepareForMatch(player, current.kitId(), currentSpawn);
                    player.setInvulnerable(true);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        Match latest = matches.get(uuid);
                        if (player.isOnline() && !player.isDead() && latest == current && !latest.countdown() && !latest.finishing()) {
                            player.setInvulnerable(false);
                        }
                    }, 10L);
                });
                return;
            }
        }
        Location deathLocation = pendingDeathLocations.get(uuid);
        if (deathLocation != null && deathLocation.getWorld() != null) {
            // Respawn three blocks above the death point, then hold the defeated player there until the result delay ends.
            Location airborne = deathLocation.clone();
            airborne.setY(Math.min(deathLocation.getWorld().getMaxHeight() - 2.0, deathLocation.getY() + 3.0));
            event.setRespawnLocation(airborne);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Match current = matches.get(uuid);
                if (player.isOnline() && !player.isDead() && current != null && current.finishing()) {
                    player.setInvulnerable(true);
                    player.setGravity(false);
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                }
            });
        }
    }

    private void beginBedfightRespawnCountdown(Player player, Match expectedMatch) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || matches.get(uuid) != expectedMatch
                || expectedMatch.finishing() || !expectedMatch.bedfight() || !expectedMatch.bedIntact(uuid)
                || !activeBedfightRespawnCountdowns.add(uuid)) return;

        player.setGameMode(GameMode.SPECTATOR);
        player.setInvulnerable(true);
        player.setGravity(false);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        showBedfightRespawnTitle(player, 3, "&a");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isBedfightRespawnCurrent(player, expectedMatch)) showBedfightRespawnTitle(player, 2, "&d");
        }, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isBedfightRespawnCurrent(player, expectedMatch)) showBedfightRespawnTitle(player, 1, "&c");
        }, 40L);
        // The 3 -> 2 -> 1 sequence occupies the final three seconds; respawn at 3 seconds
        // after countdown start rather than waiting an extra second after the displayed 1.
        Bukkit.getScheduler().runTaskLater(plugin, () -> completeBedfightRespawn(player, expectedMatch), 60L);
    }

    private void showBedfightRespawnTitle(Player player, int seconds, String color) {
        Title title = Title.title(Texts.component("&cYOU DIED!").decorate(TextDecoration.BOLD),
                Texts.component("&7Respawning in " + color + seconds),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
        player.showTitle(title);
    }

    private boolean isBedfightRespawnCurrent(Player player, Match match) {
        return player != null && player.isOnline() && !player.isDead()
                && activeBedfightRespawnCountdowns.contains(player.getUniqueId())
                && matches.get(player.getUniqueId()) == match && !match.finishing();
    }

    private void completeBedfightRespawn(Player player, Match match) {
        UUID uuid = player.getUniqueId();
        if (!isBedfightRespawnCurrent(player, match)) {
            activeBedfightRespawnCountdowns.remove(uuid);
            return;
        }
        if (!match.bedIntact(uuid)) {
            activeBedfightRespawnCountdowns.remove(uuid);
            finishMatch(match, match.opponent(uuid), uuid, true);
            return;
        }
        Location spawn = match.spawnFor(uuid);
        if (spawn == null || spawn.getWorld() == null) {
            activeBedfightRespawnCountdowns.remove(uuid);
            finishMatch(match, match.opponent(uuid), uuid, true);
            return;
        }

        activeBedfightRespawnCountdowns.remove(uuid);
        player.clearTitle();
        prepareForMatch(player, match.kitId(), spawn);
        player.setInvulnerable(true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Match current = matches.get(uuid);
            if (player.isOnline() && !player.isDead() && current == match
                    && !current.finishing() && !current.countdown()) {
                player.setInvulnerable(false);
            }
        }, 10L);
    }

    private void removeMatch(Match match) {
        matches.remove(match.first());
        matches.remove(match.second());
    }

    public void removeFromAllQueues(UUID uuid) {
        LinkedHashSet<String> selected = playerQueues.remove(uuid);
        if (selected != null) {
            for (String id : selected) {
                LinkedHashSet<UUID> members = queues.get(id);
                if (members != null) {
                    members.remove(uuid);
                    if (members.isEmpty()) queues.remove(id);
                }
            }
        }
        queuedAt.remove(uuid);
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) online.sendActionBar(Component.empty());
    }

    public void onKitAdded(String kitId) {
        // Queue entries and leaderboard selectors are rendered dynamically.
    }

    /** Purges queued selections for a kit that has been deleted by an administrator. */
    public void onKitRemoved(String kitId) {
        String id = normalize(kitId);
        LinkedHashSet<UUID> waiting = queues.remove(id);
        if (waiting != null) {
            for (UUID uuid : waiting) {
                LinkedHashSet<String> selected = playerQueues.get(uuid);
                if (selected != null) {
                    selected.remove(id);
                    if (selected.isEmpty()) playerQueues.remove(uuid);
                }
            }
        }
        for (Map.Entry<UUID, LinkedHashSet<String>> entry : new ArrayList<>(playerQueues.entrySet())) {
            entry.getValue().remove(id);
            if (entry.getValue().isEmpty()) playerQueues.remove(entry.getKey());
        }
        for (Map.Entry<UUID, LinkedHashMap<String, Long>> entry : new ArrayList<>(queuedAt.entrySet())) {
            entry.getValue().remove(id);
            if (entry.getValue().isEmpty()) queuedAt.remove(entry.getKey());
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) updateQueueActionBar(player);
        }
    }

    private String normalize(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT); }
}
