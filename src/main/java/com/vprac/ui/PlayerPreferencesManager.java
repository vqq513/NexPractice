package com.vprac.ui;

import com.vprac.VpracPlugin;
import com.vprac.queue.Match;
import com.vprac.queue.PartyMatch;
import com.vprac.leaderboard.LeaderboardManager;
import com.vprac.leaderboard.KitRankProgression;
import com.vprac.util.Texts;
import fr.mrmicky.fastboard.FastBoard;
import me.clip.placeholderapi.PlaceholderAPI;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.UUID;

/** Persistent player preferences, client-side time control, and FastBoard sidebar management. */
public final class PlayerPreferencesManager {
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");
    private static final Map<String, Long> TIME_TICKS = Map.of(
            "day", 1000L,
            "noon", 6000L,
            "sunset", 12000L,
            "night", 13000L,
            "midnight", 18000L
    );

    private final VpracPlugin plugin;
    private final File settingsFile;
    private final YamlConfiguration settings;
    private final Map<UUID, FastBoard> boards = new HashMap<>();
    private BukkitTask scoreboardUpdateTask;
    private static final Pattern SIMPLE_PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_]+)%");

    public PlayerPreferencesManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.settingsFile = new File(plugin.getDataFolder(), "player-settings.yml");
        if (!settingsFile.exists()) {
            File parent = settingsFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create NexPractice data folder for player settings.");
            }
            try {
                if (!settingsFile.createNewFile()) {
                    plugin.getLogger().warning("Could not create player-settings.yml.");
                }
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not create player-settings.yml: " + exception.getMessage());
            }
        }
        this.settings = YamlConfiguration.loadConfiguration(settingsFile);
    }

    public boolean isSidebarEnabled(UUID uuid) {
        return settings.getBoolean(path(uuid, "sidebar"), true);
    }

    public boolean areSoundsEnabled(UUID uuid) {
        return settings.getBoolean(path(uuid, "sounds"), true);
    }

    public boolean areDuelRequestsAllowed(UUID uuid) {
        return settings.getBoolean(path(uuid, "duel-requests"), true);
    }

    public boolean arePartyInvitesAllowed(UUID uuid) {
        return settings.getBoolean(path(uuid, "party-invites"), true);
    }

    /** Whether this player accepts incoming /msg and /r private messages. */
    public boolean arePrivateMessagesAllowed(UUID uuid) {
        return settings.getBoolean(path(uuid, "private-messages"), true);
    }

    /** Player preference for permission-gated lobby flight. */
    public boolean isLobbyFlyEnabled(UUID uuid) {
        return settings.getBoolean(path(uuid, "lobby-fly"), false);
    }

    /** Applies lobby flight only outside NexPractice matches, tournaments, and active spectator sessions. */
    public void refreshLobbyFlight(Player player) {
        if (player == null || !player.isOnline()) return;
        // Creative mode has built-in flight and is independent of the lobby-flight preference.
        if (player.getGameMode() == GameMode.CREATIVE) {
            player.setAllowFlight(true);
            return;
        }
        UUID uuid = player.getUniqueId();
        if ((plugin.getQueueManager() != null && plugin.getQueueManager().isInMatch(uuid))
                || (plugin.getTournamentManager() != null && plugin.getTournamentManager().isRegistered(uuid))
                || (plugin.getMatchSpectatorManager() != null && plugin.getMatchSpectatorManager().isSpectating(uuid))) {
            return; // Match/spectator managers own flight state while in their lifecycle.
        }
        boolean permitted = player.hasPermission("vprac.lobby.fly");
        boolean enabled = permitted && isLobbyFlyEnabled(uuid);
        if (enabled) {
            player.setAllowFlight(true);
        } else {
            if (player.isFlying()) player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    public boolean isOwnRankShown(UUID uuid) {
        return settings.getBoolean(path(uuid, "show-own-rank"), true);
    }

    /** Rank presentation used anywhere another player can see the rank. */
    public String visibleRank(UUID uuid, int wins) {
        return isOwnRankShown(uuid) ? KitRankProgression.rankName(wins) : "Hidden";
    }

    public boolean areSpectatorsAllowed(UUID uuid) {
        return settings.getBoolean(path(uuid, "spectators"), true);
    }

    public String getTimePreference(UUID uuid) {
        String time = settings.getString(path(uuid, "time"), "server");
        return time == null ? "server" : time.toLowerCase(Locale.ROOT);
    }

    public void handleJoin(Player player) {
        applyPlayerTime(player);
        refreshLobbyFlight(player);
        updateBoardForPlayer(player);
    }

    public void handleQuit(Player player) {
        FastBoard board = boards.remove(player.getUniqueId());
        if (board != null) board.delete();
    }

    public void shutdown() {
        if (scoreboardUpdateTask != null) {
            scoreboardUpdateTask.cancel();
            scoreboardUpdateTask = null;
        }
        for (FastBoard board : new ArrayList<>(boards.values())) board.delete();
        boards.clear();
        for (Player player : Bukkit.getOnlinePlayers()) player.resetPlayerTime();
    }

    /** Starts the configurable scoreboard refresh loop. Call again after reloading config. */
    public void startScoreboardUpdates() {
        if (scoreboardUpdateTask != null) scoreboardUpdateTask.cancel();
        long interval = Math.max(1L, Math.min(1200L,
                plugin.getConfig().getLong("scoreboard.update-interval-ticks", 20L)));
        scoreboardUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 1L, interval);
        validateScoreboardLines("lobby");
        validateScoreboardLines("match");
        validateScoreboardLines("match-multiround");
        validateScoreboardLines("party-ffa");
        validateScoreboardLines("party-split");
        validateScoreboardLines("party-split-bedfight");
    }

    /** Refreshes scoreboard settings and immediately applies the current configuration. */
    public void reloadScoreboardSettings() {
        startScoreboardUpdates();
        updateAll();
    }

    private void validateScoreboardLines(String context) {
        List<String> lines = plugin.getConfig().getStringList("scoreboard." + context + ".lines");
        if (lines.size() > 15) {
            plugin.getLogger().warning("scoreboard." + context + ".lines has " + lines.size()
                    + " entries; Minecraft sidebars support at most 15. Only the first 15 lines will be displayed.");
        }
    }

    /** Immediately refreshes one player's sidebar after a state transition such as entering the arena. */
    public void refreshScoreboard(Player player) {
        if (player == null || !player.isOnline()) return;
        updateBoardForPlayer(player);
    }

    public void updateAll() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int fighting = plugin.getFightingPlayerCount();
        int queueing = plugin.getQueueManager().queueingPlayersCount();
        int onlineCount = online.size();

        for (Player player : online) updateBoardForPlayer(player, fighting, queueing, onlineCount);
    }

    private void updateBoardForPlayer(Player player) {
        updateBoardForPlayer(player, plugin.getFightingPlayerCount(),
                plugin.getQueueManager().queueingPlayersCount(), Bukkit.getOnlinePlayers().size());
    }

    private void updateBoardForPlayer(Player player, int fighting, int queueing, int onlineCount) {
        UUID uuid = player.getUniqueId();
        if (!isSidebarEnabled(uuid) || !plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            removeBoard(uuid);
            return;
        }

        BoardContext context = resolveBoardContext(player);
        String section = context.scoreboardSection();
        // Only solo queue matches configured for multiple round wins use the round-score layout.
        // Direct duels, tournament bouts, Bedfight and party matches are forced to one round.
        if ("match".equals(section) && context.soloMatch() != null
                && !context.soloMatch().bedfight() && context.soloMatch().roundsToWin() > 1) {
            section = "match-multiround";
        }
        if (!plugin.getConfig().getBoolean("scoreboard." + section + ".enabled", true)) {
            // If a specialized layout is disabled, fall back to the default match layout.
            if ((section.equals("party-ffa") || section.equals("party-split") || section.equals("party-split-bedfight")
                    || section.equals("bedfight") || section.equals("match-multiround"))
                    && plugin.getConfig().getBoolean("scoreboard.match.enabled", true)) {
                section = "match";
            } else {
                removeBoard(uuid);
                return;
            }
        }

        ensureBoard(player);
        updateBoard(player, boards.get(uuid), context, section, fighting, queueing, onlineCount);
    }

    public void openSettings(Player player) {
        openSettingsTab(player, "settings");
    }

    private void openSettingsTab(Player player, String selectedTab) {
        UUID uuid = player.getUniqueId();
        boolean settingsTab = !"time".equalsIgnoreCase(selectedTab);
        List<DialogBody> body = new ArrayList<>();

        Component settingsLink = Component.text("  SETTINGS  ", settingsTab ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .clickEvent(custom("settings/tab/settings"))
                .hoverEvent(HoverEvent.showText(Component.text("Open player settings")));
        Component timeLink = Component.text("  TIME  ", !settingsTab ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .clickEvent(custom("settings/tab/time"))
                .hoverEvent(HoverEvent.showText(Component.text("Open personal time settings")));
        body.add(DialogBody.plainMessage(settingsLink.append(Component.text("     ", NamedTextColor.DARK_GRAY))
                .append(timeLink), 360));
        body.add(DialogBody.plainMessage(Component.text(" ", NamedTextColor.GRAY), 360));

        if (settingsTab) {
            body.add(DialogBody.plainMessage(Component.text("SETTINGS", NamedTextColor.AQUA), 360));
            addToggleOption(body, "sidebar", "Sidebar", isSidebarEnabled(uuid), "Toggle your personal sidebar");
            addToggleOption(body, "sounds", "Sounds", areSoundsEnabled(uuid), "Toggle interface and match sounds");
            addToggleOption(body, "duel-requests", "Duel Requests", areDuelRequestsAllowed(uuid), "Allow or block incoming duel requests");
            addToggleOption(body, "spectators", "Allow Spectators", areSpectatorsAllowed(uuid), "Allow or block players from spectating your matches");
            addToggleOption(body, "party-invites", "Party Invites", arePartyInvitesAllowed(uuid), "Allow or block incoming party invitations");
            addToggleOption(body, "private-messages", "Private Messages", arePrivateMessagesAllowed(uuid), "Allow or block incoming /msg and /r messages");
            addToggleOption(body, "show-own-rank", "Show Own Rank", isOwnRankShown(uuid),
                    isOwnRankShown(uuid) ? "Your rank is visible in public displays" : "Your rank will be displayed as Hidden");
            addFlyOption(body, player, isLobbyFlyEnabled(uuid));
        } else {
            String selectedTime = getTimePreference(uuid);
            body.add(DialogBody.plainMessage(Component.text("TIME", NamedTextColor.AQUA), 360));
            String currentTime = selectedTime.equals("server") ? "World default" : selectedTime;
            body.add(DialogBody.plainMessage(Component.text("Current: " + currentTime, NamedTextColor.GRAY), 360));
            addTimeOption(body, "day", "☀ Day", NamedTextColor.YELLOW, selectedTime);
            addTimeOption(body, "noon", "☼ Noon", NamedTextColor.GOLD, selectedTime);
            addTimeOption(body, "sunset", "☀ Sunset", NamedTextColor.RED, selectedTime);
            addTimeOption(body, "night", "☾ Night", NamedTextColor.DARK_BLUE, selectedTime);
            addTimeOption(body, "midnight", "✦ Midnight", NamedTextColor.DARK_PURPLE, selectedTime);
            body.add(DialogBody.plainMessage(Component.text(
                    "Click the selected time again to restore the world's normal time. These options only change your own view.",
                    NamedTextColor.GRAY), 360));
        }

        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY)).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Player Preferences", NamedTextColor.AQUA))
                        .body(body)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.notice(close)));
        player.showDialog(dialog);
    }

    private void addToggleOption(List<DialogBody> body, String id, String label, boolean enabled, String description) {
        Component line = Component.text((enabled ? "✔ " : "✖ ") + label + ": " + (enabled ? "ON" : "OFF"),
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .clickEvent(custom("settings/" + id))
                .hoverEvent(HoverEvent.showText(Component.text(description)));
        body.add(DialogBody.plainMessage(line, 360));
    }

    private void addFlyOption(List<DialogBody> body, Player player, boolean enabled) {
        boolean hasPermission = player.hasPermission("vprac.lobby.fly");
        boolean effectiveEnabled = hasPermission && enabled;
        String label = plugin.getConfig().getString("settings.fly-label", "&bFly&7: {status}")
                .replace("{status}", plugin.getConfig().getString(effectiveEnabled ? "settings.fly-enabled" : "settings.fly-disabled",
                        effectiveEnabled ? "&aON" : "&cOFF"));
        String hoverPath = hasPermission ? "settings.fly-hover" : "settings.fly-hover-no-permission";
        String defaultHover = hasPermission ? "&fEnable or disable flying in lobby"
                : "&fEnable or disable flying in lobby (require {required_rank})";
        String requiredRank = plugin.getConfig().getString("settings.fly-required-rank", "Your Rank");
        String hover = plugin.getConfig().getString(hoverPath, defaultHover).replace("{required_rank}", requiredRank);
        Component line = Texts.component(label)
                .clickEvent(custom("settings/lobby-fly"))
                .hoverEvent(HoverEvent.showText(Texts.component(hover)));
        body.add(DialogBody.plainMessage(line, 360));
    }

    private void addTimeOption(List<DialogBody> body, String id, String label, NamedTextColor color, String selectedTime) {
        boolean selected = id.equals(selectedTime);
        Component option = Component.text((selected ? "✔ " : "• ") + label, selected ? NamedTextColor.GREEN : color)
                .clickEvent(custom("settings/time/" + id))
                .hoverEvent(HoverEvent.showText(Component.text(selected
                        ? "Click again to return to world time"
                        : "Click to set your personal time to " + id)));
        body.add(DialogBody.plainMessage(option, 360));
    }

    /** Handles settings actions and returns true when the action belongs to this manager. */
    public boolean handleCustomClick(PlayerCustomClickEvent event, Player player) {
        Key identifier = event.getIdentifier();
        if (!identifier.namespace().equals("vprac") || !identifier.value().startsWith("settings/")) return false;
        String action = identifier.value().substring("settings/".length());
        UUID uuid = player.getUniqueId();

        if (action.equals("tab/settings")) {
            openSettingsTab(player, "settings");
            return true;
        }
        if (action.equals("tab/time")) {
            openSettingsTab(player, "time");
            return true;
        }

        if (action.equals("sidebar")) {
            boolean enabled = !isSidebarEnabled(uuid);
            settings.set(path(uuid, "sidebar"), enabled);
            save();
            // Apply this personal toggle immediately, even when the configured scoreboard refresh interval is long.
            updateBoardForPlayer(player);
            player.sendMessage(Component.text(enabled ? "Sidebar enabled." : "Sidebar disabled.",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            openSettings(player);
            return true;
        }

        if (action.equals("sounds")) {
            boolean enabled = !areSoundsEnabled(uuid);
            settings.set(path(uuid, "sounds"), enabled);
            save();
            player.sendMessage(Component.text("Sounds " + (enabled ? "enabled." : "disabled."),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            openSettings(player);
            return true;
        }

        if (action.equals("duel-requests")) {
            boolean enabled = !areDuelRequestsAllowed(uuid);
            settings.set(path(uuid, "duel-requests"), enabled);
            save();
            player.sendMessage(Component.text("Duel requests " + (enabled ? "enabled." : "disabled."),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            if (!enabled && plugin.getDuelManager() != null) plugin.getDuelManager().cancelRequestsTargeting(uuid);
            openSettings(player);
            return true;
        }

        if (action.equals("spectators")) {
            boolean enabled = !areSpectatorsAllowed(uuid);
            settings.set(path(uuid, "spectators"), enabled);
            save();
            player.sendMessage(Component.text("Match spectators " + (enabled ? "enabled." : "disabled."),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            if (!enabled && plugin.getMatchSpectatorManager() != null) {
                plugin.getMatchSpectatorManager().stopSpectatorsForParticipant(uuid);
            }
            openSettings(player);
            return true;
        }

        if (action.equals("party-invites")) {
            boolean enabled = !arePartyInvitesAllowed(uuid);
            settings.set(path(uuid, "party-invites"), enabled);
            save();
            if (!enabled && plugin.getPartyManager() != null) {
                plugin.getPartyManager().cancelInvitesForTarget(uuid);
            }
            player.sendMessage(Component.text("Party invites " + (enabled ? "enabled." : "disabled."),
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            openSettings(player);
            return true;
        }

        if (action.equals("private-messages")) {
            boolean enabled = !arePrivateMessagesAllowed(uuid);
            settings.set(path(uuid, "private-messages"), enabled);
            save();
            player.sendMessage(Component.text(enabled ? "Private messages enabled." : "Private messages disabled.",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            openSettings(player);
            return true;
        }

        if (action.equals("show-own-rank")) {
            boolean enabled = !isOwnRankShown(uuid);
            settings.set(path(uuid, "show-own-rank"), enabled);
            save();
            player.sendMessage(Component.text(enabled ? "Your rank is now visible." : "Your rank will now appear as Hidden.",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            updateBoardForPlayer(player);
            openSettings(player);
            return true;
        }

        if (action.equals("lobby-fly")) {
            if (!player.hasPermission("vprac.lobby.fly")) {
                // The hover label explains the rank requirement; intentionally no chat message.
                openSettings(player);
                return true;
            }
            settings.set(path(uuid, "lobby-fly"), !isLobbyFlyEnabled(uuid));
            save();
            refreshLobbyFlight(player);
            openSettings(player);
            return true;
        }

        if (action.startsWith("time/")) {
            String chosen = action.substring("time/".length()).toLowerCase(Locale.ROOT);
            if (!TIME_TICKS.containsKey(chosen)) return true;
            if (chosen.equals(getTimePreference(uuid))) {
                settings.set(path(uuid, "time"), "server");
                player.resetPlayerTime();
                player.sendMessage(Component.text("Your time now follows the world again.", NamedTextColor.GREEN));
            } else {
                settings.set(path(uuid, "time"), chosen);
                player.setPlayerTime(TIME_TICKS.get(chosen), false);
                player.sendMessage(Component.text("Your personal time is now " + chosen + ".", NamedTextColor.GREEN));
            }
            save();
            openSettingsTab(player, "time");
            return true;
        }
        return true;
    }

    public void playSound(Player player, org.bukkit.Sound sound, float volume, float pitch) {
        if (player != null && player.isOnline() && areSoundsEnabled(player.getUniqueId())) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private void applyPlayerTime(Player player) {
        String preference = getTimePreference(player.getUniqueId());
        Long ticks = TIME_TICKS.get(preference);
        if (ticks == null) player.resetPlayerTime();
        else player.setPlayerTime(ticks, false);
    }

    private void ensureBoard(Player player) {
        boards.computeIfAbsent(player.getUniqueId(), ignored -> new FastBoard(player));
    }

    private void removeBoard(UUID uuid) {
        FastBoard board = boards.remove(uuid);
        if (board != null) board.delete();
    }

    private void updateBoard(Player player, FastBoard board, BoardContext context, String section,
                             int fighting, int queueing, int onlineCount) {
        if (board == null) return;
        String sectionPath = "scoreboard." + section;
        Map<String, String> values = buildPlaceholderValues(player, context, fighting, queueing, onlineCount);
        String configuredTitle = plugin.getConfig().getString(sectionPath + ".title", "&6Your Server");
        String renderedTitle = replaceSimplePlaceholders(configuredTitle == null ? "" : configuredTitle, values);
        try {
            renderedTitle = PlaceholderAPI.setPlaceholders(player, renderedTitle);
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("PlaceholderAPI failed while rendering the NexPractice scoreboard title: " + exception.getMessage());
        }
        board.updateTitle(color(renderedTitle));

        List<String> configuredLines = plugin.getConfig().getStringList(sectionPath + ".lines");
        if (configuredLines.size() > 15) configuredLines = configuredLines.subList(0, 15);
        List<String> rendered = new ArrayList<>(configuredLines.size());
        for (String configuredLine : configuredLines) {
            String line = configuredLine == null ? "" : configuredLine;
            line = replaceSimplePlaceholders(line, values);
            try {
                line = PlaceholderAPI.setPlaceholders(player, line);
            } catch (RuntimeException exception) {
                // A third-party expansion should not be able to break the player's entire sidebar.
                plugin.getLogger().fine("PlaceholderAPI failed while rendering a NexPractice scoreboard line: " + exception.getMessage());
            }
            // Keep the bold requested for the clock glyph without changing the project's default
            // non-bold treatment for ordinary scoreboard lines.
            if ("lobby".equals(section) && configuredLine != null && configuredLine.startsWith("&7&l⌚")) {
                rendered.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .serialize(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(line)));
            } else {
                rendered.add(color(line));
            }
        }
        board.updateLines(rendered.toArray(new String[0]));
    }

    private Map<String, String> buildPlaceholderValues(Player player, BoardContext context,
                                                       int fighting, int queueing, int onlineCount) {
        Map<String, String> values = new HashMap<>();
        String kitId = context.kitId();
        if (kitId == null || kitId.isBlank()) kitId = com.vprac.kit.KitManager.SWORD_ID;
        // Scoreboard stat placeholders always describe the viewer. In spectator mode the match
        // context follows the watched target, but personal stats must not switch to that target.
        LeaderboardManager.KitStats stats = plugin.getLeaderboardManager() == null
                ? new LeaderboardManager.KitStats(0, 0, 0, 0)
                : plugin.getLeaderboardManager().getPlayerKitStats(player.getUniqueId(), kitId);

        values.put("coins", Integer.toString(plugin.getCoinsManager() == null ? 0 : plugin.getCoinsManager().getCoins(player.getUniqueId())));
        values.put("player_name", player.getName());
        values.put("player_ping", Integer.toString(Math.max(0, player.getPing())));
        values.put("player_ping_color", pingColor(Math.max(0, player.getPing())));
        values.put("player_health", formatDecimal(player.getHealth()));
        values.put("player_max_health", formatDecimal(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20.0 : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
        values.put("player_food", Integer.toString(player.getFoodLevel()));
        values.put("player_level", Integer.toString(player.getLevel()));
        values.put("player_world", player.getWorld().getName());
        values.put("player_gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
        values.put("player_x", Integer.toString(player.getLocation().getBlockX()));
        values.put("player_y", Integer.toString(player.getLocation().getBlockY()));
        values.put("player_z", Integer.toString(player.getLocation().getBlockZ()));
        values.put("server_online", Integer.toString(onlineCount));
        values.put("server_max_players", Integer.toString(Bukkit.getMaxPlayers()));

        values.put("vprac_date", formatLobbyClock());
        values.put("vprac_fighting", Integer.toString(fighting));
        values.put("vprac_queueing", Integer.toString(queueing));
        values.put("vprac_online", Integer.toString(onlineCount));
        values.put("vprac_status", statusFor(context));
        values.put("vprac_kit", context.kitId() == null ? "None" : plugin.getKitManager().getDisplayName(context.kitId()));
        values.put("vprac_opponent", opponentName(context));
        values.put("vprac_opponent_ping", opponentPing(context));
        values.put("vprac_match_time", matchTime(context));
        values.put("vprac_match_mode", matchMode(context));
        values.put("vprac_match_kills", Integer.toString(context.matchKills()));
        values.put("vprac_round_score", Integer.toString(roundScore(context)));
        Match bedfightMatch = context.soloMatch();
        boolean soloBedfight = bedfightMatch != null && bedfightMatch.bedfight();
        String bedSelfColor = "&7";
        String bedSelfMark = "-";
        String bedOpponentColor = "&7";
        String bedOpponentMark = "-";
        if (soloBedfight) {
            boolean selfIsFirst = bedfightMatch.first().equals(context.subjectId());
            bedSelfColor = selfIsFirst ? "&9" : "&c";
            bedSelfMark = bedfightMatch.bedIntact(context.subjectId()) ? "✔" : "✘";
            bedOpponentColor = selfIsFirst ? "&c" : "&9";
            bedOpponentMark = bedfightMatch.bedIntact(context.opponentId()) ? "✔" : "✘";
        } else {
            PartyMatch partyBedfight = context.partyMatch();
            if (partyBedfight != null && partyBedfight.mode() == PartyMatch.Mode.SPLIT
                    && partyBedfight.arena().bedfightEnabled()) {
                int team = partyBedfight.team(context.subjectId());
                if (team == 0 || team == 1) {
                    boolean selfIsFirst = team == 0;
                    org.bukkit.Location ownBed = selfIsFirst
                            ? partyBedfight.arena().bedA() : partyBedfight.arena().bedB();
                    org.bukkit.Location enemyBed = selfIsFirst
                            ? partyBedfight.arena().bedB() : partyBedfight.arena().bedA();
                    bedSelfColor = selfIsFirst ? "&9" : "&c";
                    bedSelfMark = configuredBedPresent(ownBed) ? "✔" : "✘";
                    bedOpponentColor = selfIsFirst ? "&c" : "&9";
                    bedOpponentMark = configuredBedPresent(enemyBed) ? "✔" : "✘";
                }
            }
        }
        values.put("vprac_bed_self_color", bedSelfColor);
        values.put("vprac_bed_self_mark", bedSelfMark);
        values.put("vprac_bed_opponent_color", bedOpponentColor);
        values.put("vprac_bed_opponent_mark", bedOpponentMark);
        values.put("vprac_opponent_round_score", Integer.toString(opponentRoundScore(context)));
        values.put("vprac_party_players_alive", context.partyMatch() == null ? "0" : Integer.toString(context.partyMatch().alive().size()));
        values.put("vprac_party_players_total", context.partyMatch() == null ? "0" : Integer.toString(context.partyMatch().members().size()));
        values.put("vprac_party_team_alive", Integer.toString(partyTeamAlive(context, true)));
        values.put("vprac_party_team_total", Integer.toString(partyTeamTotal(context, true)));
        values.put("vprac_party_enemy_alive", Integer.toString(partyTeamAlive(context, false)));
        values.put("vprac_party_enemy_total", Integer.toString(partyTeamTotal(context, false)));
        values.put("vprac_kills", Integer.toString(stats.kills()));
        values.put("vprac_total_kills", Integer.toString(stats.kills()));
        values.put("vprac_wins", Integer.toString(stats.wins()));
        values.put("vprac_rank", visibleRank(player.getUniqueId(), stats.wins()));
        values.put("player_rank", visibleRank(player.getUniqueId(), stats.wins()));
        values.put("vprac_losses", Integer.toString(stats.losses()));
        values.put("vprac_deaths", Integer.toString(stats.deaths()));
        values.put("vprac_kdr", String.format(Locale.ROOT, "%.2f", stats.kdr()));
        values.put("vprac_kit_id", context.kitId() == null ? "none" : context.kitId());
        return values;
    }

    private String formatLobbyClock() {
        String configuredZone = plugin.getConfig().getString("lobby-clock.timezone", "GMT+03:00");
        ZoneId zone;
        try {
            zone = ZoneId.of(configuredZone == null || configuredZone.isBlank() ? "GMT+03:00" : configuredZone.trim());
        } catch (Exception exception) {
            zone = ZoneId.of("GMT+03:00");
            plugin.getLogger().warning("Invalid lobby-clock.timezone; using GMT+03:00.");
        }
        String pattern = plugin.getConfig().getString("lobby-clock.format", "MMM dd, HH:mm");
        try {
            return DateTimeFormatter.ofPattern(pattern == null || pattern.isBlank() ? "MMM dd, HH:mm" : pattern, Locale.ENGLISH)
                    .withZone(zone).format(Instant.now());
        } catch (Exception exception) {
            plugin.getLogger().warning("Invalid lobby-clock.format; using MMM dd, HH:mm.");
            return DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH).withZone(zone).format(Instant.now());
        }
    }

    private int roundScore(BoardContext context) {
        if (context.soloMatch() != null) return context.soloMatch().roundWins(context.subjectId());
        PartyMatch match = context.partyMatch();
        if (match == null) return 0;
        if (match.mode() == PartyMatch.Mode.FFA) return match.roundWins(context.subjectId());
        int team = match.team(context.subjectId());
        return team < 0 ? 0 : match.teamRoundWins(team);
    }

    private int opponentRoundScore(BoardContext context) {
        if (context.soloMatch() != null) {
            return context.opponentId() == null ? 0 : context.soloMatch().roundWins(context.opponentId());
        }
        PartyMatch match = context.partyMatch();
        if (match == null) return 0;
        if (match.mode() == PartyMatch.Mode.FFA) {
            return context.opponentId() == null ? 0 : match.roundWins(context.opponentId());
        }
        int ownTeam = match.team(context.subjectId());
        for (UUID member : match.members()) {
            int enemyTeam = match.team(member);
            if (enemyTeam >= 0 && enemyTeam != ownTeam) return match.teamRoundWins(enemyTeam);
        }
        return 0;
    }

    private int partyTeamAlive(BoardContext context, boolean ownTeam) {
        PartyMatch match = context.partyMatch();
        if (match == null || match.mode() != PartyMatch.Mode.SPLIT) return 0;
        int targetTeam = match.team(context.subjectId());
        return (int) match.alive().stream().filter(uuid -> (match.team(uuid) == targetTeam) == ownTeam).count();
    }

    private int partyTeamTotal(BoardContext context, boolean ownTeam) {
        PartyMatch match = context.partyMatch();
        if (match == null || match.mode() != PartyMatch.Mode.SPLIT) return 0;
        int targetTeam = match.team(context.subjectId());
        return (int) match.members().stream().filter(uuid -> (match.team(uuid) == targetTeam) == ownTeam).count();
    }

    private String replaceSimplePlaceholders(String input, Map<String, String> values) {
        Matcher matcher = SIMPLE_PLACEHOLDER.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (replacement == null) matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
            else matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private BoardContext resolveBoardContext(Player viewer) {
        UUID subjectId = viewer.getUniqueId();
        boolean externalSpectator = false;
        UUID spectatedTarget = plugin.getMatchSpectatorManager() == null
                ? null : plugin.getMatchSpectatorManager().getSpectatingTarget(viewer.getUniqueId());
        if (spectatedTarget != null) {
            subjectId = spectatedTarget;
            externalSpectator = true;
        }

        Match soloMatch = plugin.getQueueManager() == null ? null : plugin.getQueueManager().getMatch(subjectId);
        if (soloMatch != null) {
            if (!soloMatch.onArena()) {
                // A match is reserved at Match Found, but the lobby layout remains until teleport.
                UUID viewerId = viewer.getUniqueId();
                return BoardContext.lobby(viewerId, !plugin.getQueueManager().getPlayerQueues(viewerId).isEmpty());
            }
            UUID opponentId = soloMatch.opponent(subjectId);
            Player opponent = Bukkit.getPlayer(opponentId);
            return new BoardContext(subjectId, soloMatch, null, opponentId, opponent,
                    externalSpectator, soloMatch.kitId(), soloMatch.startedAtMillis() > 0L
                    ? Math.max(0L, (System.currentTimeMillis() - soloMatch.startedAtMillis()) / 1000L) : 0L,
                    soloMatch.kills(subjectId), soloMatch.countdown(), soloMatch.finishing(), false);
        }

        PartyMatch partyMatch = plugin.getPartyMatchManager() == null
                ? null : plugin.getPartyMatchManager().getActiveMatch(subjectId);
        if (partyMatch != null) {
            if (!partyMatch.onArena()) {
                // Keep the lobby layout during the Match Found delay for party participants too.
                UUID viewerId = viewer.getUniqueId();
                return BoardContext.lobby(viewerId, !plugin.getQueueManager().getPlayerQueues(viewerId).isEmpty());
            }
            UUID opponentId = findPartyOpponent(partyMatch, subjectId);
            Player opponent = opponentId == null ? null : Bukkit.getPlayer(opponentId);
            long elapsed = partyMatch.startedAtMillis() > 0L
                    ? Math.max(0L, (System.currentTimeMillis() - partyMatch.startedAtMillis()) / 1000L) : 0L;
            return new BoardContext(subjectId, null, partyMatch, opponentId, opponent,
                    externalSpectator, partyMatch.kitId(), elapsed,
                    partyMatch.kills(subjectId), partyMatch.countdown(), partyMatch.finishing(), false);
        }

        if (externalSpectator) {
            return new BoardContext(subjectId, null, null, null, null, true, null, 0L, 0, false, false, false);
        }
        return BoardContext.lobby(subjectId, !plugin.getQueueManager().getPlayerQueues(subjectId).isEmpty());
    }

    private UUID findPartyOpponent(PartyMatch match, UUID subjectId) {
        if (match.mode() == PartyMatch.Mode.FFA) {
            for (UUID candidate : match.alive()) if (!candidate.equals(subjectId)) return candidate;
            return null;
        }
        int team = match.team(subjectId);
        for (UUID candidate : match.alive()) {
            if (!candidate.equals(subjectId) && match.team(candidate) != team) return candidate;
        }
        return null;
    }

    private String statusFor(BoardContext context) {
        if (context.externalSpectator()) return "Spectating";
        if (context.partyMatch() != null && context.partyMatch().isEliminated(context.subjectId())) return "Spectating";
        if (context.inMatchContext()) {
            if (context.finishing()) return "Finishing";
            if (context.countdown()) return "Countdown";
            return "Fighting";
        }
        return context.queueing() ? "Queueing" : "Lobby";
    }

    private String opponentName(BoardContext context) {
        if (context.soloMatch() != null) return knownPlayerName(context.opponentId());
        if (context.partyMatch() != null) {
            if (context.opponentId() != null) return knownPlayerName(context.opponentId());
            return context.partyMatch().mode() == PartyMatch.Mode.FFA ? "Party FFA" : "No opponent";
        }
        return context.externalSpectator() ? "Spectating" : "None";
    }

    private String opponentPing(BoardContext context) {
        Player opponent = context.opponent();
        if (opponent == null || !opponent.isOnline()) return "&7Offline";
        int ping = Math.max(0, opponent.getPing());
        return pingColor(ping) + ping + "ms";
    }

    private String knownPlayerName(UUID uuid) {
        if (uuid == null) return "None";
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String offlineName = Bukkit.getOfflinePlayer(uuid).getName();
        return offlineName == null || offlineName.isBlank() ? "Offline" : offlineName;
    }

    private String matchTime(BoardContext context) {
        long seconds = context.elapsedSeconds();
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private String matchMode(BoardContext context) {
        if (context.soloMatch() != null) return "Solo";
        if (context.partyMatch() == null) return "None";
        return context.partyMatch().mode() == PartyMatch.Mode.FFA ? "Party FFA" : "Party Split";
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private boolean configuredBedPresent(org.bukkit.Location location) {
        return location != null && location.getWorld() != null
                && location.getBlock().getBlockData() instanceof org.bukkit.block.data.type.Bed;
    }

    private String pingColor(int ping) {
        int greenBelow = Math.max(0, plugin.getConfig().getInt("scoreboard.ping-colors.green-below-ms", 60));
        int yellowBelow = Math.max(greenBelow, plugin.getConfig().getInt("scoreboard.ping-colors.yellow-below-ms", 120));
        if (ping < greenBelow) return plugin.getConfig().getString("scoreboard.ping-colors.good", "&a");
        if (ping < yellowBelow) return plugin.getConfig().getString("scoreboard.ping-colors.medium", "&e");
        return plugin.getConfig().getString("scoreboard.ping-colors.high", "&c");
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private record BoardContext(UUID subjectId, Match soloMatch, PartyMatch partyMatch,
                                UUID opponentId, Player opponent, boolean externalSpectator,
                                String kitId, long elapsedSeconds, int matchKills,
                                boolean countdown, boolean finishing, boolean queueing) {
        private static BoardContext lobby(UUID subjectId, boolean queueing) {
            return new BoardContext(subjectId, null, null, null, null, false, null,
                    0L, 0, false, false, queueing);
        }
        private boolean inMatchContext() { return soloMatch != null || partyMatch != null || externalSpectator; }
        private String scoreboardSection() {
            if (partyMatch != null) {
                if (partyMatch.mode() == PartyMatch.Mode.FFA) return "party-ffa";
                return partyMatch.arena().bedfightEnabled() ? "party-split-bedfight" : "party-split";
            }
            if (soloMatch != null && soloMatch.bedfight()) return "bedfight";
            return inMatchContext() ? "match" : "lobby";
        }
    }

    private static String path(UUID uuid, String key) {
        return uuid + "." + key;
    }

    private ClickEvent custom(String action) {
        return ClickEvent.custom(Key.key("vprac", action), EMPTY_TAG);
    }

    private void save() {
        try {
            settings.save(settingsFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save player-settings.yml: " + exception.getMessage());
        }
    }
}
