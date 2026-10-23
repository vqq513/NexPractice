package com.vprac.leaderboard;

import com.vprac.VpracPlugin;
import com.vprac.leaderboard.storage.MongoPlayerStatsStorage;
import com.vprac.leaderboard.storage.MysqlPlayerStatsStorage;
import com.vprac.leaderboard.storage.PlayerStatsStorage;
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
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.*;
import org.bukkit.scheduler.BukkitTask;

/** Tracks per-kit match stats and renders a no-client-mod native Dialog leaderboard. */
public final class LeaderboardManager {
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");
    private static final DecimalFormat KDR_FORMAT = new DecimalFormat("0.00");

    public enum Metric {
        WINS("wins", "Wins"), KILLS("kills", "Kills"), KDR("kdr", "KDR"), RANK("rank", "Rank");
        private final String id;
        private final String label;
        Metric(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        public String label() { return label; }
        public static Metric fromId(String id) {
            if (id != null) {
                for (Metric metric : values()) if (metric.id.equalsIgnoreCase(id)) return metric;
            }
            return RANK;
        }
    }

    /** Period scope used by the FancyHolograms leaderboard display. */
    public enum Period {
        LIFETIME("lifetime", "Lifetime"), MONTHLY("monthly", "Monthly"), WEEKLY("weekly", "Weekly"), DAILY("daily", "Daily");
        private final String id;
        private final String label;
        Period(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        public String label() { return label; }
        public static Period fromId(String id) {
            if (id != null) for (Period period : values()) if (period.id.equalsIgnoreCase(id)) return period;
            return LIFETIME;
        }
    }

    /** Per-player stats used by the lobby Stats chest. */
    public record KitStats(int wins, int losses, int kills, int deaths) {
        public double kdr() { return deaths == 0 ? kills : (double) kills / deaths; }
    }

    public static final class Entry {
        private final UUID uuid;
        private final String name;
        private final int wins;
        private final int lifetimeWins;
        private final int kills;
        private final int deaths;
        public Entry(UUID uuid, String name, int wins, int lifetimeWins, int kills, int deaths) {
            this.uuid = uuid; this.name = name; this.wins = wins; this.lifetimeWins = lifetimeWins;
            this.kills = kills; this.deaths = deaths;
        }
        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public int wins() { return wins; }
        public int lifetimeWins() { return lifetimeWins; }
        public int kills() { return kills; }
        public int deaths() { return deaths; }
        public double kdr() { return deaths == 0 ? kills : (double) kills / deaths; }
        public String value(Metric metric) {
            return switch (metric) {
                case WINS -> Integer.toString(wins);
                case RANK -> KitRankProgression.rankName(lifetimeWins);
                case KILLS -> Integer.toString(kills);
                case KDR -> KDR_FORMAT.format(kdr());
            };
        }
        private double score(Metric metric) {
            return switch (metric) {
                case WINS -> wins;
                case RANK -> KitRankProgression.rankOrdinal(lifetimeWins);
                case KILLS -> kills;
                case KDR -> kdr();
            };
        }
    }

    private final VpracPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private PlayerStatsStorage remoteStorage;
    private BukkitTask storageRefreshTask;
    private long localMutationVersion;

    public LeaderboardManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "leaderboards.yml");
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) plugin.getLogger().warning("Could not create NexPractice data directory for leaderboards.");
        if (!file.exists()) {
            try { if (!file.createNewFile()) plugin.getLogger().warning("Could not create leaderboards.yml."); }
            catch (IOException exception) { plugin.getLogger().severe("Could not create leaderboards.yml: " + exception.getMessage()); }
        }
        this.data = YamlConfiguration.loadConfiguration(file);
        initializeStorageBackend();
    }

    public void updateName(Player player) {
        if (player == null) return;
        String path = playerPath(player.getUniqueId()) + ".name";
        if (Objects.equals(data.getString(path), player.getName())) return;
        data.set(path, player.getName());
        if (remoteStorage != null) remoteStorage.updateName(player.getUniqueId(), player.getName());
        localMutationVersion++;
        saveQuietly("updating player name");
    }

    public void recordWin(UUID uuid, String playerName, String kitId) {
        if (uuid == null || !validKitId(kitId)) return;
        data.set(playerPath(uuid) + ".name", safeName(uuid, playerName));
        String root = kitPath(uuid, kitId);
        data.set(root + ".played", true);
        data.set(root + ".wins", data.getInt(root + ".wins", 0) + 1);
        incrementPeriodStat(uuid, kitId, "wins");
        if (remoteStorage != null) remoteStorage.incrementStats(uuid, safeName(uuid, playerName), kitId,
                Map.of("wins", 1), currentPeriodDelta("wins"));
        localMutationVersion++;
        saveQuietly("recording a win");
    }

    /** Records a match loss separately from deaths so the two stats remain distinct. */
    public void recordLoss(UUID uuid, String playerName, String kitId, boolean deathAlreadyCounted) {
        if (uuid == null || !validKitId(kitId)) return;
        data.set(playerPath(uuid) + ".name", safeName(uuid, playerName));
        String root = kitPath(uuid, kitId);
        data.set(root + ".played", true);
        // Older records predate the explicit loss counter. Approximate their historic losses
        // from deaths, then avoid counting the current death twice when it was already recorded.
        int oldLosses;
        if (data.contains(root + ".losses")) {
            oldLosses = data.getInt(root + ".losses", 0);
        } else {
            int deaths = data.getInt(root + ".deaths", 0);
            oldLosses = Math.max(0, deaths - (deathAlreadyCounted ? 1 : 0));
        }
        data.set(root + ".losses", oldLosses + 1);
        if (remoteStorage != null) remoteStorage.incrementStats(uuid, safeName(uuid, playerName), kitId,
                Map.of("losses", 1), Map.of());
        localMutationVersion++;
        saveQuietly("recording a loss");
    }

    public KitStats getPlayerKitStats(UUID uuid, String kitId) {
        if (uuid == null || !validKitId(kitId)) return new KitStats(0, 0, 0, 0);
        String root = kitPath(uuid, kitId);
        int wins = data.getInt(root + ".wins", 0);
        int kills = data.getInt(root + ".kills", 0);
        int deaths = data.getInt(root + ".deaths", 0);
        int losses = data.getInt(root + ".losses", deaths);
        return new KitStats(wins, losses, kills, deaths);
    }

    /** True if the player has completed or recorded a match in this kit. Legacy records are inferred from stats. */
    public boolean hasPlayedKit(UUID uuid, String kitId) {
        if (uuid == null || !validKitId(kitId)) return false;
        String root = kitPath(uuid, kitId);
        if (data.getBoolean(root + ".played", false)) return true;
        int wins = data.getInt(root + ".wins", 0);
        int losses = data.getInt(root + ".losses", data.getInt(root + ".deaths", 0));
        int kills = data.getInt(root + ".kills", 0);
        int deaths = data.getInt(root + ".deaths", 0);
        return wins > 0 || losses > 0 || kills > 0 || deaths > 0;
    }

    /** Records one in-match death; match logic validates that the supplied killer is a legitimate opponent. */
    public void recordDeath(UUID victim, String victimName, UUID killer, String killerName, String kitId) {
        if (victim == null || !validKitId(kitId)) return;
        data.set(playerPath(victim) + ".name", safeName(victim, victimName));
        String victimRoot = kitPath(victim, kitId);
        data.set(victimRoot + ".played", true);
        data.set(victimRoot + ".deaths", data.getInt(victimRoot + ".deaths", 0) + 1);
        incrementPeriodStat(victim, kitId, "deaths");
        if (remoteStorage != null) remoteStorage.incrementStats(victim, safeName(victim, victimName), kitId,
                Map.of("deaths", 1), currentPeriodDelta("deaths"));
        if (killer != null && !killer.equals(victim)) {
            data.set(playerPath(killer) + ".name", safeName(killer, killerName));
            String killerRoot = kitPath(killer, kitId);
            data.set(killerRoot + ".played", true);
            data.set(killerRoot + ".kills", data.getInt(killerRoot + ".kills", 0) + 1);
            incrementPeriodStat(killer, kitId, "kills");
            if (remoteStorage != null) remoteStorage.incrementStats(killer, safeName(killer, killerName), kitId,
                    Map.of("kills", 1), currentPeriodDelta("kills"));
        }
        localMutationVersion++;
        saveQuietly("recording a death");
    }

    public List<Entry> top(String kitId, Metric metric, int amount) {
        return top(kitId, metric, amount, Period.LIFETIME);
    }

    /** Returns leaders using lifetime totals or the active day/week/month in the configured timezone. */
    public List<Entry> top(String kitId, Metric metric, int amount, Period period) {
        if (!validKitId(kitId)) return List.of();
        String normalizedKit = kitId.toLowerCase(Locale.ROOT);
        Period selectedPeriod = period == null ? Period.LIFETIME : period;
        String bucket = selectedPeriod == Period.LIFETIME ? null : currentPeriodBucket(selectedPeriod);
        List<Entry> entries = new ArrayList<>();
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return List.of();
        for (String rawUuid : players.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(rawUuid); } catch (IllegalArgumentException ignored) { continue; }
            String root = "players." + rawUuid + ".kits." + normalizedKit;
            int lifetimeWins = data.getInt(root + ".wins", 0);
            int lifetimeKills = data.getInt(root + ".kills", 0);
            int lifetimeDeaths = data.getInt(root + ".deaths", 0);
            int wins = lifetimeWins;
            int kills = lifetimeKills;
            int deaths = lifetimeDeaths;
            if (bucket != null) {
                String scoped = root + ".periods." + selectedPeriod.id() + "." + bucket;
                wins = data.getInt(scoped + ".wins", 0);
                kills = data.getInt(scoped + ".kills", 0);
                deaths = data.getInt(scoped + ".deaths", 0);
                if (wins == 0 && kills == 0 && deaths == 0) continue;
            } else if (wins == 0 && kills == 0 && deaths == 0) {
                continue;
            }
            String name = data.getString("players." + rawUuid + ".name");
            if (name == null || name.isBlank()) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                name = offline.getName() == null ? rawUuid.substring(0, 8) : offline.getName();
            }
            entries.add(new Entry(uuid, name, wins, lifetimeWins, kills, deaths));
        }
        Metric ordering = metric == null ? Metric.RANK : metric;
        Comparator<Entry> comparator = Comparator.comparingDouble((Entry entry) -> entry.score(ordering)).reversed();
        comparator = comparator.thenComparing(Comparator.comparingInt(Entry::wins).reversed());
        if (ordering != Metric.KILLS) comparator = comparator.thenComparing(Comparator.comparingInt(Entry::kills).reversed());
        comparator = comparator.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
        entries.sort(comparator);
        return List.copyOf(entries.subList(0, Math.min(Math.max(0, amount), entries.size())));
    }

    private Map<String, Map<String, Integer>> currentPeriodDelta(String stat) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Period period : List.of(Period.DAILY, Period.WEEKLY, Period.MONTHLY)) {
            result.put(period.id() + ":" + currentPeriodBucket(period), Map.of(stat, 1));
        }
        return Map.copyOf(result);
    }

    private void incrementPeriodStat(UUID uuid, String kitId, String stat) {
        String root = kitPath(uuid, kitId) + ".periods";
        for (Period period : List.of(Period.DAILY, Period.WEEKLY, Period.MONTHLY)) {
            String path = root + "." + period.id() + "." + currentPeriodBucket(period) + "." + stat;
            data.set(path, data.getInt(path, 0) + 1);
        }
    }

    private String currentPeriodBucket(Period period) {
        ZoneId zone;
        String configured = plugin.getConfig().getString("leaderboards.holograms.timezone", "GMT+03:00");
        try {
            zone = ZoneId.of(configured == null || configured.isBlank() ? "GMT+03:00" : configured.trim());
        } catch (Exception exception) {
            zone = ZoneId.of("GMT+03:00");
            plugin.getLogger().warning("Invalid leaderboards.holograms.timezone '" + configured + "'; using GMT+03:00.");
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate date = now.toLocalDate();
        return switch (period) {
            case LIFETIME -> "all";
            case DAILY -> date.toString();
            case WEEKLY -> {
                WeekFields iso = WeekFields.ISO;
                yield String.format(Locale.ROOT, "%04d-W%02d", date.get(iso.weekBasedYear()), date.get(iso.weekOfWeekBasedYear()));
            }
            case MONTHLY -> YearMonth.from(now).toString();
        };
    }

    public void removeKitData(String kitId) throws IOException {
        if (!validKitId(kitId)) return;
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        String normalized = kitId.toLowerCase(Locale.ROOT);
        for (String rawUuid : new ArrayList<>(players.getKeys(false))) data.set("players." + rawUuid + ".kits." + normalized, null);
        data.save(file);
        localMutationVersion++;
        if (remoteStorage != null) remoteStorage.removeKit(normalized);
    }

    public void open(Player player) { open(player, "sword", Metric.RANK); }

    public void open(Player player, String requestedKit, Metric metric) {
        if (player == null || !player.isOnline()) return;
        List<String> kitIds = plugin.getKitManager().getKitIds();
        if (kitIds.isEmpty()) return;
        String kitId = requestedKit == null ? "sword" : requestedKit.toLowerCase(Locale.ROOT);
        if (!plugin.getKitManager().exists(kitId)) kitId = kitIds.get(0);
        Metric selectedMetric = metric == null ? Metric.RANK : metric;
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("SELECT KIT", NamedTextColor.AQUA), 360));

        Component kitSelector = Component.empty();
        boolean firstKit = true;
        for (String id : kitIds) {
            boolean selected = id.equalsIgnoreCase(kitId);
            Component name = Component.text((selected ? "[ " : "") + plugin.getKitManager().getDisplayName(id) + (selected ? " ]" : ""), selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(custom("leaderboard/kit/" + id + "/" + selectedMetric.id()))
                    .hoverEvent(HoverEvent.showText(Component.text("Show " + plugin.getKitManager().getDisplayName(id) + " leaderboards")));
            if (!firstKit) kitSelector = kitSelector.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY));
            kitSelector = kitSelector.append(name);
            firstKit = false;
        }
        body.add(DialogBody.plainMessage(kitSelector, 360));

        body.add(DialogBody.plainMessage(Component.text("FILTER BY", NamedTextColor.AQUA), 360));
        Component filters = Component.empty();
        boolean firstMetric = true;
        for (Metric option : Metric.values()) {
            boolean selected = option == selectedMetric;
            Component label = Component.text((selected ? "[ " : "") + option.label() + (selected ? " ]" : ""), selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(custom("leaderboard/filter/" + option.id() + "/" + kitId))
                    .hoverEvent(HoverEvent.showText(Component.text(switch (option) { case WINS -> "Rank by match wins"; case RANK -> "Rank by this kit's division"; case KILLS -> "Rank by total kills"; case KDR -> "Rank by kill/death ratio"; })));
            if (!firstMetric) filters = filters.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY));
            filters = filters.append(label);
            firstMetric = false;
        }
        body.add(DialogBody.plainMessage(filters, 360));
        body.add(DialogBody.plainMessage(Component.text("Top 10 " + plugin.getKitManager().getDisplayName(kitId), NamedTextColor.AQUA), 360));

        List<Entry> leaders = top(kitId, selectedMetric, 10);
        if (leaders.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No stats recorded for this kit yet.", NamedTextColor.GRAY), 360));
        } else {
            for (int i = 0; i < leaders.size(); i++) {
                Entry entry = leaders.get(i);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                if (head.getItemMeta() instanceof SkullMeta skullMeta) {
                    skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));
                    head.setItemMeta(skullMeta);
                }
                String displayedValue = entry.value(selectedMetric);
                if (selectedMetric == Metric.RANK && plugin.getPlayerPreferencesManager() != null) {
                    displayedValue = plugin.getPlayerPreferencesManager().visibleRank(entry.uuid(), entry.lifetimeWins());
                }
                Component row = Component.text("#" + (i + 1) + " ", NamedTextColor.DARK_AQUA)
                        .append(Component.text(entry.name(), NamedTextColor.DARK_AQUA))
                        .append(Component.text("  -  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(displayedValue, NamedTextColor.WHITE));
                body.add(DialogBody.item(head, DialogBody.plainMessage(row, 320), true, true, 24, 24));
            }
        }
        body.add(DialogBody.plainMessage(Component.text("Rank is based on wins for this kit. KDR = kills / deaths; when deaths are zero, KDR equals kills.", NamedTextColor.GRAY), 360));
        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY)).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Leaderboards", NamedTextColor.AQUA))
                        .body(body).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).build())
                .type(DialogType.notice(close)));
        player.showDialog(dialog);
    }

    /** Shows the global rank ladder. Progress is tracked independently for every kit. */
    public void openRanks(Player player) {
        if (player == null || !player.isOnline()) return;
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "Every kit has its own separate rank. Wins in one kit do not change your rank in another kit.",
                NamedTextColor.GRAY), 360));
        body.add(DialogBody.plainMessage(Component.text("RANK PROGRESSION  ·  WINS REQUIRED", NamedTextColor.AQUA), 360));
        for (KitRankProgression.RankLevel level : KitRankProgression.levels()) {
            Component row = Component.text("• ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(level.displayName(), NamedTextColor.AQUA))
                    .append(Component.text("  —  " + level.requiredWins() + " wins", NamedTextColor.GRAY));
            body.add(DialogBody.plainMessage(row, 360));
        }
        body.add(DialogBody.plainMessage(Component.text(
                "Ranks are earned separately by winning matches in each kit.", NamedTextColor.GRAY), 360));
        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY)).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("NexPractice Ranks", NamedTextColor.GOLD))
                        .body(body).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).build())
                .type(DialogType.notice(close)));
        player.showDialog(dialog);
    }

    public boolean handleCustomClick(PlayerCustomClickEvent event, Player player) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals("vprac") || !key.value().startsWith("leaderboard/")) return false;
        String[] parts = key.value().split("/");
        if (parts.length == 4 && parts[1].equals("kit")) {
            String kit = parts[2].toLowerCase(Locale.ROOT);
            if (plugin.getKitManager().exists(kit)) open(player, kit, Metric.fromId(parts[3]));
            return true;
        }
        if (parts.length == 4 && parts[1].equals("filter")) {
            String kit = parts[3].toLowerCase(Locale.ROOT);
            if (plugin.getKitManager().exists(kit)) open(player, kit, Metric.fromId(parts[2]));
            return true;
        }
        return true;
    }

    private ClickEvent custom(String action) { return ClickEvent.custom(Key.key("vprac", action), EMPTY_TAG); }
    private String playerPath(UUID uuid) { return "players." + uuid; }
    private String kitPath(UUID uuid, String kitId) { return playerPath(uuid) + ".kits." + kitId.toLowerCase(Locale.ROOT); }
    private String safeName(UUID uuid, String name) {
        if (name != null && !name.isBlank()) return name;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() == null ? uuid.toString().substring(0, 8) : offline.getName();
    }
    private boolean validKitId(String kitId) { return kitId != null && kitId.matches("[A-Za-z0-9_-]{1,32}"); }
    public void startStorageRefresh() {
        if (remoteStorage == null || storageRefreshTask != null) return;
        storageRefreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            PlayerStatsStorage source = remoteStorage;
            if (source == null) return;
            long versionAtRequest = localMutationVersion;
            source.reloadSnapshotAsync(snapshot -> Bukkit.getScheduler().runTask(plugin, () -> {
                // Do not replace fresh local changes with an older snapshot returned by the DB.
                if (remoteStorage != source || versionAtRequest != localMutationVersion || snapshot.isEmpty()) return;
                mergeRemoteSnapshot(source, snapshot);
                saveQuietly("refreshing database statistics backup");
            }));
        }, 1200L, 1200L);
    }

    public void shutdown() {
        if (storageRefreshTask != null) {
            storageRefreshTask.cancel();
            storageRefreshTask = null;
        }
        if (remoteStorage != null) {
            remoteStorage.close();
            remoteStorage = null;
        }
    }

    private void initializeStorageBackend() {
        String configured = plugin.getConfig().getString("storage.backend", "server");
        String backend = configured == null ? "server" : configured.trim().toLowerCase(Locale.ROOT);
        if (backend.equals("server") || backend.equals("local") || backend.equals("yaml")) return;
        PlayerStatsStorage candidate = null;
        try {
            candidate = switch (backend) {
                case "mysql" -> new MysqlPlayerStatsStorage(plugin);
                case "mongodb", "mongo" -> new MongoPlayerStatsStorage(plugin);
                default -> throw new IllegalArgumentException("storage.backend must be server, mysql, or mongodb");
            };
            Map<String, String> snapshot = candidate.loadAll();
            if (snapshot.isEmpty()) {
                ConfigurationSection players = data.getConfigurationSection("players");
                if (players != null) {
                    for (String rawUuid : new ArrayList<>(players.getKeys(false))) {
                        UUID uuid;
                        try { uuid = UUID.fromString(rawUuid); } catch (IllegalArgumentException ignored) { continue; }
                        ConfigurationSection playerKits = data.getConfigurationSection(playerPath(uuid) + ".kits");
                        if (playerKits == null || playerKits.getKeys(false).isEmpty()) continue;
                        String serialized = serializePlayer(uuid);
                        if (serialized != null && !serialized.isBlank()) candidate.replacePlayer(uuid, serialized);
                    }
                    if (!players.getKeys(false).isEmpty()) {
                        plugin.getLogger().info("Migrating existing local leaderboard stats into " + candidate.label() + ".");
                    }
                }
            } else {
                mergeRemoteSnapshot(candidate, snapshot);
                try { data.save(file); } catch (IOException exception) {
                    plugin.getLogger().warning("Could not refresh the local leaderboard backup from " + candidate.label() + ".");
                }
            }
            remoteStorage = candidate;
            plugin.getLogger().info("Player match statistics storage: " + candidate.label() + ".");
        } catch (Exception exception) {
            if (candidate != null) {
                try { candidate.close(); } catch (Exception ignored) { }
            }
            plugin.getLogger().severe("Could not initialize storage.backend='" + backend + "'. NexPractice will continue with server-file storage; fix storage config/database access and restart to enable the requested backend.");
            plugin.getLogger().fine("Player stats backend initialization error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            remoteStorage = null;
        }
    }

    /**
     * Merges remote counters with the local backup instead of blindly replacing it. All match
     * counters are monotonic, so max(local, remote) prevents a temporarily failed remote write
     * from losing stats. Local-ahead deltas are queued as atomic increments to reconcile the DB.
     */
    private void mergeRemoteSnapshot(PlayerStatsStorage target, Map<String, String> remoteSnapshot) {
        Map<String, String> localSnapshot = new LinkedHashMap<>();
        ConfigurationSection currentPlayers = data.getConfigurationSection("players");
        if (currentPlayers != null) {
            for (String rawUuid : currentPlayers.getKeys(false)) {
                UUID uuid;
                try { uuid = UUID.fromString(rawUuid); } catch (IllegalArgumentException ignored) { continue; }
                String serialized = serializePlayer(uuid);
                if (serialized != null) localSnapshot.put(uuid.toString(), serialized);
            }
        }

        Set<String> ids = new LinkedHashSet<>(remoteSnapshot.keySet());
        ids.addAll(localSnapshot.keySet());
        Map<String, String> mergedSnapshot = new LinkedHashMap<>();
        for (String rawUuid : ids) {
            UUID uuid;
            try { uuid = UUID.fromString(rawUuid); } catch (IllegalArgumentException ignored) { continue; }
            YamlConfiguration localYaml = parsePlayerSnapshot(localSnapshot.get(rawUuid));
            YamlConfiguration remoteYaml = parsePlayerSnapshot(remoteSnapshot.get(rawUuid));
            ConfigurationSection localPlayer = localYaml.getConfigurationSection("player");
            ConfigurationSection remotePlayer = remoteYaml.getConfigurationSection("player");
            if (localPlayer != null) queueLocalAheadDeltas(target, uuid, localPlayer, remotePlayer);

            YamlConfiguration mergedYaml = new YamlConfiguration();
            ConfigurationSection mergedPlayer = mergedYaml.createSection("player");
            mergePlayerSections(localPlayer, remotePlayer, mergedPlayer);
            if (!mergedPlayer.getKeys(false).isEmpty()) mergedSnapshot.put(uuid.toString(), mergedYaml.saveToString());
        }
        applyRemoteSnapshot(mergedSnapshot);
    }

    private YamlConfiguration parsePlayerSnapshot(String serialized) {
        return YamlConfiguration.loadConfiguration(new StringReader(serialized == null ? "" : serialized));
    }

    private void mergePlayerSections(ConfigurationSection local, ConfigurationSection remote,
                                     ConfigurationSection target) {
        Set<String> keys = new LinkedHashSet<>();
        if (local != null) keys.addAll(local.getKeys(false));
        if (remote != null) keys.addAll(remote.getKeys(false));
        for (String key : keys) {
            ConfigurationSection localChild = local == null ? null : local.getConfigurationSection(key);
            ConfigurationSection remoteChild = remote == null ? null : remote.getConfigurationSection(key);
            if (localChild != null || remoteChild != null) {
                mergePlayerSections(localChild, remoteChild, target.createSection(key));
                continue;
            }
            Object localValue = local == null ? null : local.get(key);
            Object remoteValue = remote == null ? null : remote.get(key);
            Object chosen;
            if (localValue == null) {
                chosen = remoteValue;
            } else if (remoteValue == null) {
                chosen = localValue;
            } else if (Set.of("wins", "losses", "kills", "deaths").contains(key)
                    && localValue instanceof Number localNumber && remoteValue instanceof Number remoteNumber) {
                chosen = Math.max(localNumber.longValue(), remoteNumber.longValue());
            } else if (key.equals("played") && localValue instanceof Boolean localPlayed
                    && remoteValue instanceof Boolean remotePlayed) {
                chosen = localPlayed || remotePlayed;
            } else {
                // The remote backend is authoritative for non-counter values, such as a renamed player.
                chosen = remoteValue;
            }
            if (chosen != null) target.set(key, chosen);
        }
    }

    private void queueLocalAheadDeltas(PlayerStatsStorage target, UUID uuid,
                                       ConfigurationSection localPlayer,
                                       ConfigurationSection remotePlayer) {
        ConfigurationSection localKits = localPlayer.getConfigurationSection("kits");
        if (localKits == null) return;
        ConfigurationSection remoteKits = remotePlayer == null ? null : remotePlayer.getConfigurationSection("kits");
        for (String rawKit : localKits.getKeys(false)) {
            ConfigurationSection localKit = localKits.getConfigurationSection(rawKit);
            if (localKit == null) continue;
            ConfigurationSection remoteKit = remoteKits == null ? null : remoteKits.getConfigurationSection(rawKit);
            Map<String, Integer> deltas = new LinkedHashMap<>();
            for (String stat : List.of("wins", "losses", "kills", "deaths")) {
                int difference = positiveDifference(localKit.getLong(stat, 0),
                        remoteKit == null ? 0 : remoteKit.getLong(stat, 0));
                if (difference > 0) deltas.put(stat, difference);
            }
            Map<String, Map<String, Integer>> periodDeltas = new LinkedHashMap<>();
            ConfigurationSection localPeriods = localKit.getConfigurationSection("periods");
            ConfigurationSection remotePeriods = remoteKit == null ? null : remoteKit.getConfigurationSection("periods");
            if (localPeriods != null) {
                for (String period : List.of("daily", "weekly", "monthly")) {
                    ConfigurationSection localBuckets = localPeriods.getConfigurationSection(period);
                    ConfigurationSection remoteBuckets = remotePeriods == null ? null : remotePeriods.getConfigurationSection(period);
                    if (localBuckets == null) continue;
                    for (String bucket : localBuckets.getKeys(false)) {
                        ConfigurationSection localCounts = localBuckets.getConfigurationSection(bucket);
                        if (localCounts == null) continue;
                        ConfigurationSection remoteCounts = remoteBuckets == null ? null : remoteBuckets.getConfigurationSection(bucket);
                        Map<String, Integer> counts = new LinkedHashMap<>();
                        for (String stat : List.of("wins", "kills", "deaths")) {
                            int difference = positiveDifference(localCounts.getLong(stat, 0),
                                    remoteCounts == null ? 0 : remoteCounts.getLong(stat, 0));
                            if (difference > 0) counts.put(stat, difference);
                        }
                        if (!counts.isEmpty()) periodDeltas.put(period + ":" + bucket, counts);
                    }
                }
            }
            if (!deltas.isEmpty() || !periodDeltas.isEmpty()) {
                String playerName = localPlayer.getString("name");
                target.incrementStats(uuid, playerName, rawKit, deltas, periodDeltas);
            }
        }
    }

    private int positiveDifference(long local, long remote) {
        if (local <= remote) return 0;
        return (int) Math.min(Integer.MAX_VALUE, local - remote);
    }

    private void applyRemoteSnapshot(Map<String, String> snapshot) {
        data.set("players", null);
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            UUID uuid;
            try { uuid = UUID.fromString(entry.getKey()); } catch (IllegalArgumentException ignored) { continue; }
            YamlConfiguration parsed = YamlConfiguration.loadConfiguration(new StringReader(entry.getValue()));
            ConfigurationSection source = parsed.getConfigurationSection("player");
            if (source == null) continue;
            copySection(source, data.createSection(playerPath(uuid)));
        }
        localMutationVersion++;
    }

    private String serializePlayer(UUID uuid) {
        ConfigurationSection player = data.getConfigurationSection(playerPath(uuid));
        if (player == null) return null;
        YamlConfiguration snapshot = new YamlConfiguration();
        copySection(player, snapshot.createSection("player"));
        return snapshot.saveToString();
    }

    private void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            if (value instanceof ConfigurationSection child) copySection(child, target.createSection(key));
            else target.set(key, value);
        }
    }

    private void saveQuietly(String operation) {
        try { data.save(file); } catch (IOException exception) {
            plugin.getLogger().severe("Could not save leaderboard data while " + operation + ": " + exception.getMessage());
        }
    }
}
