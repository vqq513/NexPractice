package com.vprac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent;
import com.vprac.arena.ArenaData;
import com.vprac.arena.ArenaManager;
import com.vprac.arena.ArenaMenuManager;
import com.vprac.kit.KitManager;
import com.vprac.kit.KitMenuHolder;
import com.vprac.kit.KitProfileManager;
import com.vprac.kit.KitRulesManager;
import com.vprac.leaderboard.LeaderboardManager;
import com.vprac.leaderboard.FancyLeaderboardHologramManager;
import com.vprac.visibility.MatchVisibilityManager;
import com.vprac.placeholder.VpracExpansion;
import com.vprac.placeholder.CoinsExpansion;
import com.vprac.party.PartyManager;
import com.vprac.duel.DuelManager;
import com.vprac.queue.Match;
import com.vprac.queue.MatchReportManager;
import com.vprac.queue.PartyMatch;
import com.vprac.queue.PartyMatchManager;
import com.vprac.queue.SpectatorTrackerHolder;
import com.vprac.queue.QueueManager;
import com.vprac.queue.MatchSpectatorManager;
import com.vprac.util.Texts;
import com.vprac.ui.PlayerPreferencesManager;
import com.vprac.ui.PracticeMenuManager;
import com.vprac.ui.CosmeticsManager;
import com.vprac.ui.CoinsManager;
import com.vprac.tournament.TournamentManager;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.util.Vector;

public final class VpracPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener {
    private NamespacedKey queueToolKey;
    private NamespacedKey editKitKey;
    private NamespacedKey arenaWandKey;
    private NamespacedKey bedVoidWandKey;
    private NamespacedKey bedSetupItemKey;
    private NamespacedKey specialItemKey;
    private NamespacedKey specialProjectileKey;
    private NamespacedKey partyToolKey;
    private NamespacedKey settingsToolKey;
    private NamespacedKey leaderboardToolKey;
    private NamespacedKey spectatorTrackerKey;
    private NamespacedKey spectatorLeaveKey;
    private NamespacedKey spectatorStopKey;
    private NamespacedKey attackSpeedKey;
    private KitManager kitManager;
    private KitProfileManager kitProfileManager;
    private KitRulesManager kitRulesManager;
    private ArenaManager arenaManager;
    private ArenaMenuManager arenaMenuManager;
    private QueueManager queueManager;
    private PartyManager partyManager;
    private DuelManager duelManager;
    private PartyMatchManager partyMatchManager;
    private PlayerPreferencesManager playerPreferencesManager;
    private PracticeMenuManager practiceMenuManager;
    private CosmeticsManager cosmeticsManager;
    private CoinsManager coinsManager;
    private LeaderboardManager leaderboardManager;
    private FancyLeaderboardHologramManager leaderboardHologramManager;
    private MatchVisibilityManager matchVisibilityManager;
    private MatchSpectatorManager matchSpectatorManager;
    private MatchReportManager matchReportManager;
    private TournamentManager tournamentManager;
    private final Map<UUID, KitModeState> kitModeStates = new HashMap<>();
    private final Map<UUID, Long> lastPacketUse = new ConcurrentHashMap<>();
    /** Recent player-to-Creeper hits, used to attribute explosion damage to the player who pushed the Creeper. */
    private final Map<UUID, CreeperHitCredit> creeperHitCredits = new ConcurrentHashMap<>();
    /** Last credited Creeper attacker for each player actually damaged by that Creeper. */
    private final Map<UUID, CreeperHitCredit> creeperVictimCredits = new ConcurrentHashMap<>();
    /** Explosion location + attacker fallback for server paths that expose generic ENTITY_EXPLOSION damage. */
    private final Map<UUID, CreeperExplosionCredit> creeperExplosionCredits = new ConcurrentHashMap<>();
    /** Block coordinates placed by a specific live match; fireballs may break these when arena destruction is disabled. */
    private final Map<String, PlacedBlockMarker> placedBlocksByMatch = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDialogOpen = new ConcurrentHashMap<>();
    /** Sender of the most recent incoming private message, used by /r. */
    private final Map<UUID, UUID> lastMessageFrom = new ConcurrentHashMap<>();
    private final Set<UUID> spectatorPacketUsers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ArenaSetupState> arenaSetupStates = new HashMap<>();
    private final Map<UUID, BedSetupState> bedSetupStates = new HashMap<>();
    private final Map<UUID, BedVoidSetupState> bedVoidSetupStates = new HashMap<>();
    private final Map<UUID, ArenaSelection> completedArenaSelections = new HashMap<>();
    private final Map<UUID, String> editingKits = new HashMap<>();
    private PacketListenerAbstract packetListener;
    private VpracExpansion placeholderExpansion;
    private CoinsExpansion coinsExpansion;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        mergeConfigSectionFromDefaults("storage");
        mergeConfigSectionFromDefaults("lobby-clock");
        migrateLobbyScoreboardClockLine();
        migrateDefaultMatchScoreboard();
        migrateScoreboardBranding();
        mergeConfigSectionFromDefaults("scoreboard.match-multiround");
        mergeConfigSectionFromDefaults("scoreboard.party-split-bedfight");
        mergeCosmeticDefaultsIntoExistingConfig();
        mergeTournamentDefaultsIntoExistingConfig();
        mergePartyDefaultsIntoExistingConfig();
        mergeSettingsDefaultsIntoExistingConfig();
        mergeRespawnDefaultsIntoExistingConfig();
        mergeConfigSectionFromDefaults("leaderboards");
        applyImmediateRespawnRuleToLoadedWorlds();
        mergeBedfightScoreboardDefaultsIntoExistingConfig();
        mergeSpecialItemDefaultsIntoExistingConfig();
        // Keep the old PDC namespace so existing hotbar items and match controls survive the NexPractice rename.
        queueToolKey = new NamespacedKey("vprac", "queue_tool");
        editKitKey = new NamespacedKey("vprac", "edit_kit_book");
        arenaWandKey = new NamespacedKey("vprac", "arena_wand");
        bedVoidWandKey = new NamespacedKey("vprac", "bed_void_wand");
        bedSetupItemKey = new NamespacedKey("vprac", "bed_setup_item");
        specialItemKey = new NamespacedKey("vprac", "special_item_type");
        specialProjectileKey = new NamespacedKey("vprac", "special_fireball_projectile");
        partyToolKey = new NamespacedKey("vprac", "party_tool");
        settingsToolKey = new NamespacedKey("vprac", "settings_tool");
        leaderboardToolKey = new NamespacedKey("vprac", "leaderboards_tool");
        spectatorTrackerKey = new NamespacedKey("vprac", "spectator_tracker");
        spectatorLeaveKey = new NamespacedKey("vprac", "spectator_leave_party");
        spectatorStopKey = new NamespacedKey("vprac", "spectator_stop");
        attackSpeedKey = new NamespacedKey("vprac", "queue_attack_speed");
        kitManager = new KitManager(this);
        kitProfileManager = new KitProfileManager(this);
        kitRulesManager = new KitRulesManager(this);
        leaderboardManager = new LeaderboardManager(this);
        leaderboardManager.startStorageRefresh();
        coinsManager = new CoinsManager(this);
        cosmeticsManager = new CosmeticsManager(this);
        practiceMenuManager = new PracticeMenuManager(this);
        matchVisibilityManager = new MatchVisibilityManager(this);
        arenaManager = new ArenaManager(this);
        arenaMenuManager = new ArenaMenuManager(this);
        queueManager = new QueueManager(this);
        partyMatchManager = new PartyMatchManager(this);
        partyManager = new PartyManager(this);
        playerPreferencesManager = new PlayerPreferencesManager(this);
        duelManager = new DuelManager(this);
        matchSpectatorManager = new MatchSpectatorManager(this);
        matchReportManager = new MatchReportManager(this);
        tournamentManager = new TournamentManager(this);
        // FancyHolograms is optional and its API/runtime requirements may differ by Paper release.
        // Isolate linkage failures to the hologram feature so a mismatched optional dependency cannot disable the practice core.
        if (Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
            try {
                leaderboardHologramManager = new FancyLeaderboardHologramManager(this);
            } catch (LinkageError | RuntimeException exception) {
                leaderboardHologramManager = null;
                getLogger().warning("FancyHolograms is installed but its API is incompatible with this Paper/runtime build. "
                        + "Leaderboard holograms are disabled; queues, parties, tournaments, and the rest of NexPractice remain enabled. "
                        + "Install a FancyHolograms version supporting this Paper version. Details: "
                        + exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
            }
        }
        // Wait briefly before the final arena reload/recovery pass so world-management plugins
        // can finish loading their worlds. ArenaManager blocks matchmaking until this pass ends.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            arenaManager.load();
            arenaManager.recoverAfterRestart();
        }, 20L);
        // World-management plugins may finish loading custom worlds later than the first tick.
        // Retry only deferred/unavailable arenas; never reload the entire arena registry while
        // matches may already be active.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (arenaManager != null) arenaManager.retryDeferredStartupRecovery();
        }, 100L, 200L);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(practiceMenuManager, this);
        Bukkit.getPluginManager().registerEvents(cosmeticsManager, this);
        Bukkit.getPluginManager().registerEvents(tournamentManager, this);
        if (leaderboardHologramManager != null) {
            FancyLeaderboardHologramManager optionalHolograms = leaderboardHologramManager;
            try {
                optionalHolograms.start();
            } catch (LinkageError | RuntimeException exception) {
                try { optionalHolograms.shutdown(); } catch (LinkageError | RuntimeException ignored) { }
                leaderboardHologramManager = null;
                getLogger().warning("FancyHolograms could not start on this Paper/runtime build. "
                        + "Leaderboard holograms are disabled; the practice core remains available. Details: "
                        + exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
            }
        }
        registerPacketEventsListener();

        placeholderExpansion = new VpracExpansion(this);
        if (!placeholderExpansion.register()) getLogger().warning("Could not register the PlaceholderAPI expansion.");
        coinsExpansion = new CoinsExpansion(this);
        if (!coinsExpansion.register()) getLogger().warning("Could not register the %coins% PlaceholderAPI expansion; another expansion may already own the coins identifier.");

        Bukkit.getScheduler().runTaskTimer(this, queueManager::maintainMatchNeeds, 1L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, partyMatchManager::maintainMatchNeeds, 1L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::pruneTrackedPlacedBlocks, 1200L, 1200L);
        playerPreferencesManager.startScoreboardUpdates();
        Bukkit.getScheduler().runTaskTimer(this, queueManager::updateQueueActionBars, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, matchSpectatorManager::maintain, 10L, 10L);
        // Normalize NexPractice lobby items and preferences for already-online players after a plugin reload.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!queueManager.isInMatch(online.getUniqueId())) giveLobbyItems(online);
                playerPreferencesManager.handleJoin(online);
                if (leaderboardManager != null) leaderboardManager.updateName(online);
            }
            if (matchVisibilityManager != null) matchVisibilityManager.refresh();
        });
        getLogger().info("NexPractice enabled. Native Dialogs require no client mod; party fights use four-point party FFA arenas.");
    }


    /**
     * Copies missing files from previous plugins/vPractice and plugins/VPRAC data folders
     * into plugins/NexPractice. The most recent vPractice folder is tried first, then VPRAC.
     * Existing target files are never overwritten, and source folders are retained as backups.
     */
    private void migrateLegacyDataFolder() {
        File current = getDataFolder();
        File pluginsDirectory = current.getAbsoluteFile().getParentFile();
        if (pluginsDirectory == null) return;
        File[] legacyFolders = {
                new File(pluginsDirectory, "vPractice"),
                new File(pluginsDirectory, "VPRAC")
        };
        for (File legacy : legacyFolders) {
            try {
                if (!legacy.isDirectory() || legacy.getCanonicalFile().equals(current.getCanonicalFile())) continue;
                getLogger().info("Migrating missing data files from plugins/" + legacy.getName()
                        + " into plugins/NexPractice (existing files are preserved; source folder is retained as a backup).");
                copyMissingLegacyFiles(legacy, current);
            } catch (IOException exception) {
                getLogger().severe("Could not migrate plugins/" + legacy.getName()
                        + " data into plugins/NexPractice. To avoid starting with incomplete data, startup is being aborted: "
                        + exception.getMessage());
                throw new IllegalStateException("Legacy plugin data migration failed", exception);
            }
        }
    }

    private void copyMissingLegacyFiles(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs() && !target.isDirectory()) {
                throw new IOException("Could not create directory " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Could not list directory " + source.getAbsolutePath());
            for (File child : children) copyMissingLegacyFiles(child, new File(target, child.getName()));
        } else if (!target.exists()) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Could not create directory " + parent.getAbsolutePath());
            }
            java.nio.file.Files.copy(source.toPath(), target.toPath());
        }
    }

    /** Replaces only the shipped old lobby clock line, leaving any customized first line intact. */
    private void migrateLobbyScoreboardClockLine() {
        List<String> lines = getConfig().getStringList("scoreboard.lobby.lines");
        if (lines.isEmpty()) return;
        String first = lines.get(0);
        if ("&7⏰ &r&7%vprac_date%".equals(first) || "&7&7%vprac_date%".equals(first)) {
            lines.set(0, "&7&l⌚ &7%vprac_date%");
            getConfig().set("scoreboard.lobby.lines", lines);
            saveConfig();
        }
    }

    /** Migrate only the old untouched default match sidebar; preserve any custom owner-defined layout. */
    private void migrateDefaultMatchScoreboard() {
        List<String> previousDefault = List.of(
                "&cFight",
                "&fOpponent: &b%vprac_opponent%",
                "&fYour Ping: %player_ping_color%%player_ping%ms",
                "&fTheir Ping: %vprac_opponent_ping%",
                "",
                "&7&olsa tjrbh :)"
        );
        List<String> updatedDefault = List.of(
                "&cFight",
                "",
                "🛈 &fScore: &c%vprac_round_score%&7 - &c%vprac_opponent_round_score%",
                "",
                "&fOpponent: &b%vprac_opponent%",
                "&fYour Ping: %player_ping_color%%player_ping%ms",
                "&fTheir Ping: %vprac_opponent_ping%",
                "",
                "&7&oServer.ip"
        );
        if (getConfig().getStringList("scoreboard.match.lines").equals(previousDefault)) {
            getConfig().set("scoreboard.match.lines", updatedDefault);
            saveConfig();
        }
    }

    /** Updates only the old bundled scoreboard branding in existing configs, preserving custom values. */
    private void migrateScoreboardBranding() {
        boolean changed = false;
        ConfigurationSection scoreboards = getConfig().getConfigurationSection("scoreboard");
        if (scoreboards == null) return;

        for (String key : scoreboards.getKeys(false)) {
            String sectionPath = "scoreboard." + key;
            String title = getConfig().getString(sectionPath + ".title");
            if (title != null && (title.equalsIgnoreCase("&6Tjrbh") || title.equalsIgnoreCase("&6TJRBH"))) {
                getConfig().set(sectionPath + ".title", "&6Your Server");
                changed = true;
            }
            List<String> lines = getConfig().getStringList(sectionPath + ".lines");
            boolean sectionChanged = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line != null && (line.trim().equalsIgnoreCase("&7&olsa tjrbh :)")
                        || line.trim().equalsIgnoreCase("&7lsa tjrbh :)")
                        || line.trim().equalsIgnoreCase("&7&olsa tjrbh")
                        || line.trim().equalsIgnoreCase("&7lsa tjrbh"))) {
                    lines.set(i, "&7&oServer.ip");
                    sectionChanged = true;
                }
            }
            if (sectionChanged) {
                getConfig().set(sectionPath + ".lines", lines);
                changed = true;
            }
        }
        if (changed) saveConfig();
    }

    /** Adds the Bedfight scoreboard to existing config files without overwriting custom layouts. */
    private void mergeBedfightScoreboardDefaultsIntoExistingConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        try (InputStreamReader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
            ConfigurationSection defaults = shipped.getConfigurationSection("scoreboard.bedfight");
            if (defaults == null) return;
            boolean changed = mergeMissingSection(existing, defaults, "scoreboard.bedfight");
            if (changed) existing.save(configFile);
            reloadConfig();
        } catch (Exception exception) {
            getLogger().warning("Could not merge Bedfight scoreboard defaults into config.yml: " + exception.getMessage());
        }
    }

    /** Adds newly introduced cosmetic config options to existing installs without overwriting owner edits. */
    private void mergeCosmeticDefaultsIntoExistingConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        try (InputStreamReader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
            ConfigurationSection defaults = shipped.getConfigurationSection("cosmetics");
            if (defaults == null) return;
            if (existing.getConfigurationSection("cosmetics") == null && !existing.contains("cosmetics")) {
                existing.createSection("cosmetics");
                List<String> comments = shipped.getComments("cosmetics");
                if (!comments.isEmpty()) existing.setComments("cosmetics", comments);
                List<String> inlineComments = shipped.getInlineComments("cosmetics");
                if (!inlineComments.isEmpty()) existing.setInlineComments("cosmetics", inlineComments);
            }
            boolean changed = mergeMissingSection(existing, defaults, "cosmetics");
            int cosmeticsVersion = existing.getInt("cosmetics-config-version", 0);
            if (cosmeticsVersion < 2) {
                // Apply the new built-in prices/descriptions once on upgrade. After version 2 is
                // written, operator edits in config.yml are preserved across future startups.
                for (String category : List.of("projectile-trails", "kill-messages", "kill-effects", "armor-trims")) {
                    ConfigurationSection categoryDefaults = defaults.getConfigurationSection(category);
                    if (categoryDefaults == null) continue;
                    for (String optionId : categoryDefaults.getKeys(false)) {
                        String optionPath = "cosmetics." + category + "." + optionId;
                        String description = categoryDefaults.getString(optionId + ".description");
                        if (description != null) existing.set(optionPath + ".description", description);
                        if (categoryDefaults.contains(optionId + ".price")) {
                            existing.set(optionPath + ".price", categoryDefaults.getInt(optionId + ".price"));
                        }
                    }
                }
                existing.set("cosmetics-config-version", 2);
                changed = true;
            }
            if (changed) existing.save(configFile);
            reloadConfig();
        } catch (Exception exception) {
            getLogger().warning("Could not merge new cosmetics defaults into config.yml: " + exception.getMessage());
        }
    }

    /** Adds tournament messages, host requirements, cooldowns, and rewards to old configs. */
    private void mergeTournamentDefaultsIntoExistingConfig() {
        mergeConfigSectionFromDefaults("tournaments");
    }

    /** Adds party UI and permission text defaults to old configs. */
    private void mergePartyDefaultsIntoExistingConfig() {
        mergeConfigSectionFromDefaults("party");
    }

    /** Adds lobby-flight tooltip and required-rank defaults to existing configs. */
    private void mergeSettingsDefaultsIntoExistingConfig() {
        mergeConfigSectionFromDefaults("settings");
    }

    /** Adds immediate-respawn defaults to old configs without overwriting owner values. */
    private void mergeRespawnDefaultsIntoExistingConfig() {
        mergeConfigSectionFromDefaults("respawn");
    }

    private void mergeConfigSectionFromDefaults(String root) {
        File configFile = new File(getDataFolder(), "config.yml");
        try (InputStreamReader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
            ConfigurationSection defaults = shipped.getConfigurationSection(root);
            if (defaults == null) return;
            boolean changed = mergeMissingSection(existing, defaults, root);
            if (changed) existing.save(configFile);
            reloadConfig();
        } catch (Exception exception) {
            getLogger().warning("Could not merge " + root + " defaults into config.yml: " + exception.getMessage());
        }
    }

    /** Adds the fireball knockback defaults to existing configs without overwriting owner values. */
    private void mergeSpecialItemDefaultsIntoExistingConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        try (InputStreamReader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(configFile);
            ConfigurationSection defaults = shipped.getConfigurationSection("special-items");
            if (defaults == null) return;
            boolean changed = mergeMissingSection(existing, defaults, "special-items");
            if (changed) existing.save(configFile);
            reloadConfig();
        } catch (Exception exception) {
            getLogger().warning("Could not merge special-item defaults into config.yml: " + exception.getMessage());
        }
    }

    private boolean mergeMissingSection(ConfigurationSection targetRoot, ConfigurationSection defaults, String path) {
        boolean changed = false;
        ConfigurationSection target = targetRoot.getConfigurationSection(path);
        if (target == null) {
            if (targetRoot.contains(path)) return false;
            target = targetRoot.createSection(path);
            changed = true;
        }
        for (String key : defaults.getKeys(false)) {
            String childPath = path + "." + key;
            ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
            if (defaultChild != null) {
                if (target.getConfigurationSection(key) == null && !target.contains(key)) {
                    target.createSection(key);
                    List<String> comments = defaults.getComments(key);
                    if (!comments.isEmpty()) target.setComments(key, comments);
                    List<String> inlineComments = defaults.getInlineComments(key);
                    if (!inlineComments.isEmpty()) target.setInlineComments(key, inlineComments);
                    changed = true;
                }
                if (target.getConfigurationSection(key) != null) {
                    changed |= mergeMissingSection(target, defaultChild, key);
                }
            } else if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                List<String> comments = defaults.getComments(key);
                if (!comments.isEmpty()) target.setComments(key, comments);
                List<String> inlineComments = defaults.getInlineComments(key);
                if (!inlineComments.isEmpty()) target.setInlineComments(key, inlineComments);
                changed = true;
            }
        }
        return changed;
    }

    private void applyImmediateRespawnRuleToLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) applyImmediateRespawnRule(world);
    }

    private void applyImmediateRespawnRule(World world) {
        if (world == null) return;
        boolean enabled = getConfig().getBoolean("respawn.immediate-respawn", true);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, enabled);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        applyImmediateRespawnRule(event.getWorld());
    }

    @Override
    public void onDisable() {
        if (practiceMenuManager != null) practiceMenuManager.shutdown();
        if (leaderboardManager != null) leaderboardManager.shutdown();
        if (tournamentManager != null) tournamentManager.shutdown();
        if (leaderboardHologramManager != null) leaderboardHologramManager.shutdown();
        if (cosmeticsManager != null) cosmeticsManager.shutdown();
        if (matchSpectatorManager != null) matchSpectatorManager.shutdown();
        if (matchReportManager != null) matchReportManager.shutdown();
        if (playerPreferencesManager != null) playerPreferencesManager.shutdown();
        if (matchVisibilityManager != null) matchVisibilityManager.restoreAll();
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        if (coinsExpansion != null) coinsExpansion.unregister();
        if (packetListener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        for (Map.Entry<UUID, KitModeState> entry : new HashMap<>(kitModeStates).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) restoreKitMode(player, entry.getValue());
        }
    }

    private void registerPacketEventsListener() {
        packetListener = new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.USE_ITEM
                        && event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return;

                Object rawPlayer = event.getPlayer();
                if (!(rawPlayer instanceof Player player)) return;

                // PacketEvents receives these on the network thread. Record thread-safe metadata here,
                // then check special spectator items on the Bukkit main thread. Spectators can still send
                // use-item packets even where vanilla does not produce the normal Bukkit interact event.
                UUID playerId = player.getUniqueId();
                lastPacketUse.put(playerId, System.currentTimeMillis());
                if (!spectatorPacketUsers.contains(playerId)) return;
                if (event.getPacketType() == PacketType.Play.Client.USE_ITEM
                        || event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                    Bukkit.getScheduler().runTask(VpracPlugin.this, () -> {
                        if (!player.isOnline()) return;
                        UUID id = player.getUniqueId();
                        ItemStack held = player.getInventory().getItemInMainHand();
                        if (isSpectatorTrackerItem(held)) {
                            if (partyMatchManager.isEliminatedSpectator(id)) partyMatchManager.openSpectatorTracker(player);
                            else if (matchSpectatorManager.isSpectating(id)) matchSpectatorManager.openTracker(player);
                        } else if (isSpectatorLeaveItem(held) && partyMatchManager.isEliminatedSpectator(id)) {
                            partyMatchManager.leaveAsSpectator(player);
                        } else if (isSpectatorStopItem(held) && matchSpectatorManager.isSpectating(id)) {
                            matchSpectatorManager.stopSpectating(player);
                        }
                    });
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
    }

    public KitManager getKitManager() { return kitManager; }
    public KitProfileManager getKitProfileManager() { return kitProfileManager; }
    public KitRulesManager getKitRulesManager() { return kitRulesManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public PracticeMenuManager getPracticeMenuManager() { return practiceMenuManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public CosmeticsManager getCosmeticsManager() { return cosmeticsManager; }
    public CoinsManager getCoinsManager() { return coinsManager; }
    public PartyManager getPartyManager() { return partyManager; }
    public DuelManager getDuelManager() { return duelManager; }
    public PartyMatchManager getPartyMatchManager() { return partyMatchManager; }
    public PlayerPreferencesManager getPlayerPreferencesManager() { return playerPreferencesManager; }
    public LeaderboardManager getLeaderboardManager() { return leaderboardManager; }
    public MatchVisibilityManager getMatchVisibilityManager() { return matchVisibilityManager; }
    public MatchSpectatorManager getMatchSpectatorManager() { return matchSpectatorManager; }
    public MatchReportManager getMatchReportManager() { return matchReportManager; }
    public TournamentManager getTournamentManager() { return tournamentManager; }
    public Set<UUID> getSpectatorPacketUsers() { return spectatorPacketUsers; }

    /** Practice menus are unavailable to active fighters and admins currently creating a kit. */
    public boolean canUsePracticeMenu(Player player) {
        if (player == null || !player.isOnline()) return false;
        UUID uuid = player.getUniqueId();
        if (queueManager != null && queueManager.isInMatch(uuid)) {
            Texts.send(player, "You cannot edit kit layouts during a match.");
            return false;
        }
        if (tournamentManager != null && tournamentManager.isRegistered(uuid)) {
            Texts.send(player, getConfig().getString("tournaments.messages.unable-state", "&6unable to execute this in ur current state"));
            return false;
        }
        if (kitModeStates.containsKey(uuid)) {
            Texts.send(player, "Finish kit creation before opening the Practice Menu.");
            return false;
        }
        return true;
    }

    /** Counts online players who are currently participating in a NexPractice match. */
    public int getFightingPlayerCount() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (queueManager != null && queueManager.isInMatch(player.getUniqueId())) count++;
        }
        return count;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLobbyFlightToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Creative mode owns its normal flight ability; lobby preference checks must not cancel it.
        if (player.getGameMode() == GameMode.CREATIVE) return;
        // Match and spectator systems own flight while a player is actively in those states.
        if ((queueManager != null && queueManager.isInMatch(uuid))
                || (tournamentManager != null && tournamentManager.isRegistered(uuid))
                || (matchSpectatorManager != null && matchSpectatorManager.isSpectating(uuid))) return;
        if (event.isFlying() && (playerPreferencesManager == null
                || !player.hasPermission("vprac.lobby.fly")
                || !playerPreferencesManager.isLobbyFlyEnabled(uuid))) {
            event.setCancelled(true);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newMode = event.getNewGameMode();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || player.getGameMode() != newMode) return;
            if (newMode == GameMode.CREATIVE) {
                // Creative flight should work even for players who do not have the lobby-flight permission.
                player.setAllowFlight(true);
            } else if (newMode != GameMode.SPECTATOR && playerPreferencesManager != null) {
                // Spectator mode manages its own flight; only restore the lobby preference in ordinary modes.
                playerPreferencesManager.refreshLobbyFlight(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        // A disconnected spectator may rejoin in spectator mode; NexPractice always restores the lobby state.
        player.setGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(false);
        player.setGravity(true);
        player.setFlying(false);
        player.setFlySpeed(0.1f);
        player.setAllowFlight(false);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        giveLobbyItems(player);
        if (leaderboardManager != null) leaderboardManager.updateName(player);
        if (playerPreferencesManager != null) playerPreferencesManager.handleJoin(player);
        Location lobby = getLobbySpawn();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && lobby != null) player.teleport(lobby);
            if (player.isOnline() && playerPreferencesManager != null) playerPreferencesManager.refreshLobbyFlight(player);
            if (matchVisibilityManager != null) matchVisibilityManager.refresh();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (playerPreferencesManager != null) playerPreferencesManager.handleQuit(event.getPlayer());
        lastPacketUse.remove(uuid);
        lastDialogOpen.remove(uuid);
        spectatorPacketUsers.remove(uuid);
        arenaSetupStates.remove(uuid);
        bedSetupStates.remove(uuid);
        bedVoidSetupStates.remove(uuid);
        removeBedSetupItems(event.getPlayer());
        removeBedVoidWands(event.getPlayer());
        completedArenaSelections.remove(uuid);
        editingKits.remove(uuid);
        if (tournamentManager != null) tournamentManager.handleQuit(uuid);
        if (duelManager != null) duelManager.handleQuit(uuid);
        if (matchSpectatorManager != null) matchSpectatorManager.handleQuit(uuid);
        if (partyMatchManager != null) partyMatchManager.handleQuit(uuid);
        queueManager.handleQuit(uuid);
        if (partyManager != null) partyManager.handleQuit(uuid);
        if (matchVisibilityManager != null) matchVisibilityManager.detachPlayer(uuid);
        KitModeState state = kitModeStates.remove(uuid);
        if (state != null) restoreKitMode(event.getPlayer(), state);
    }

    // Do not use ignoreCancelled=true here: Paper marks RIGHT_CLICK_AIR cancelled when vanilla has no
    // default air-interaction. That was the reason the queue sword only worked against blocks.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = event.getItem() != null ? event.getItem() : player.getInventory().getItemInMainHand();
        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;

        if (rightClick && specialItemType(held) != null) {
            event.setCancelled(true);
            useSpecialItem(player, held, specialItemType(held));
            return;
        }
        if (isBedVoidWand(held)) {
            handleBedVoidWandClick(event);
            return;
        }
        if (rightClick && isSpectatorTrackerItem(held)) {
            event.setCancelled(true);
            UUID id = player.getUniqueId();
            if (partyMatchManager.isEliminatedSpectator(id)) partyMatchManager.openSpectatorTracker(player);
            else if (matchSpectatorManager.isSpectating(id)) matchSpectatorManager.openTracker(player);
            return;
        }
        if (rightClick && isSpectatorLeaveItem(held)) {
            event.setCancelled(true);
            partyMatchManager.leaveAsSpectator(player);
            return;
        }
        if (rightClick && isSpectatorStopItem(held)) {
            event.setCancelled(true);
            matchSpectatorManager.stopSpectating(player);
            return;
        }

        if (isArenaWand(held)) {
            handleArenaWandClick(event);
            return;
        }

        if (!rightClick) return;
        if (isQueueTool(held)) {
            event.setCancelled(true);
            if (!allowMenuOpen(player)) return;
            openQueueDialog(player);
        } else if (isEditKitBook(held)) {
            event.setCancelled(true);
            if (!allowMenuOpen(player)) return;
            playHotbarMenuOpenSound(player);
            practiceMenuManager.openPracticeMenu(player);
        } else if (isPartyTool(held)) {
            event.setCancelled(true);
            if (!allowMenuOpen(player)) return;
            partyManager.openForPlayer(player);
        } else if (isLeaderboardTool(held)) {
            event.setCancelled(true);
            if (!allowMenuOpen(player)) return;
            playHotbarMenuOpenSound(player);
            leaderboardManager.open(player);
        } else if (isSettingsTool(held)) {
            event.setCancelled(true);
            if (!allowMenuOpen(player)) return;
            playHotbarMenuOpenSound(player);
            playerPreferencesManager.openSettings(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        String specialType = specialItemType(held);
        if (specialType != null) {
            event.setCancelled(true);
            useSpecialItem(player, held, specialType);
            return;
        }
        if (isBedVoidWand(held)) {
            event.setCancelled(true);
            return;
        }
        if (isSpectatorTrackerItem(held) && (partyMatchManager.isEliminatedSpectator(player.getUniqueId())
                || matchSpectatorManager.isSpectating(player.getUniqueId()))) {
            event.setCancelled(true);
            if (partyMatchManager.isEliminatedSpectator(player.getUniqueId())) partyMatchManager.openSpectatorTracker(player);
            else matchSpectatorManager.openTracker(player);
            return;
        }
        if (isSpectatorLeaveItem(held) && partyMatchManager.isEliminatedSpectator(player.getUniqueId())) {
            event.setCancelled(true);
            partyMatchManager.leaveAsSpectator(player);
            return;
        }
        if (isSpectatorStopItem(held) && matchSpectatorManager.isSpectating(player.getUniqueId())) {
            event.setCancelled(true);
            matchSpectatorManager.stopSpectating(player);
            return;
        }
        if (!isQueueTool(held) && !isEditKitBook(held) && !isPartyTool(held) && !isSettingsTool(held) && !isLeaderboardTool(held)) return;
        event.setCancelled(true);
        if (!allowMenuOpen(player)) return;
        if (isQueueTool(held)) openQueueDialog(player);
        else if (isEditKitBook(held)) { playHotbarMenuOpenSound(player); practiceMenuManager.openPracticeMenu(player); }
        else if (isPartyTool(held)) partyManager.openForPlayer(player);
        else if (isLeaderboardTool(held)) { playHotbarMenuOpenSound(player); leaderboardManager.open(player); }
        else { playHotbarMenuOpenSound(player); playerPreferencesManager.openSettings(player); }
    }

    private void playHotbarMenuOpenSound(Player player) {
        if (player == null || !player.isOnline()) return;
        org.bukkit.Sound sound = org.bukkit.Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON;
        if (playerPreferencesManager != null) {
            playerPreferencesManager.playSound(player, sound, 1.0f, 1.0f);
        } else {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }

    private void playMenuOpenSound(Player player) {
        if (player == null || !player.isOnline()) return;
        if (playerPreferencesManager != null) {
            playerPreferencesManager.playSound(player, org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 0.9f);
        } else {
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 0.9f);
        }
    }

    private boolean allowMenuOpen(Player player) {
        long now = System.currentTimeMillis();
        Long lastOpen = lastDialogOpen.get(player.getUniqueId());
        Long packetAt = lastPacketUse.get(player.getUniqueId());
        long debounceMillis = packetAt != null && now - packetAt < 1000L ? 180L : 300L;
        if (lastOpen != null && now - lastOpen < debounceMillis) return false;
        lastDialogOpen.put(player.getUniqueId(), now);
        return true;
    }

    private String specialItemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(specialItemKey, PersistentDataType.STRING);
    }

    private ItemStack createSpecialItem(String type, int amount) {
        boolean goldenHead = "golden_head".equalsIgnoreCase(type);
        Material material = "fireball".equalsIgnoreCase(type) ? Material.FIRE_CHARGE
                : (goldenHead ? Material.PLAYER_HEAD : Material.GOLDEN_APPLE);
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Set the custom profile before the display name so the profile cannot replace the item name.
            if (goldenHead && meta instanceof SkullMeta skullMeta) {
                applyGoldenHeadTexture(skullMeta);
            }
            meta.displayName(Texts.component("fireball".equalsIgnoreCase(type) ? "&6Fireball" : "&6Golden Head&7(Right Click)"));
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, type);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyGoldenHeadTexture(SkullMeta skullMeta) {
        try {
            // The provided base64 texture resolves to this official Minecraft texture URL.
            URL textureUrl = new URL("http://textures.minecraft.net/texture/95503a93fd08d6526ebd0ffcfc05c257683ff2e483187d5385d9f0ba935e3208");
            UUID profileId = UUID.nameUUIDFromBytes("vpractice-golden-head-texture".getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createProfile(profileId);
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(textureUrl);
            profile.setTextures(textures);
            skullMeta.setPlayerProfile(profile);
        } catch (MalformedURLException exception) {
            getLogger().warning("Could not configure the Golden Head texture URL: " + exception.getMessage());
        }
    }

    private void consumeOne(Player player, ItemStack held) {
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else {
            ItemStack updated = held.clone();
            updated.setAmount(updated.getAmount() - 1);
            player.getInventory().setItemInMainHand(updated);
        }
    }

    private void useSpecialItem(Player player, ItemStack held, String type) {
        if (player == null || !player.isOnline() || held == null || held.getType().isAir()) return;
        if ("golden_head".equalsIgnoreCase(type)) {
            consumeOne(player, held);
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 2, true, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1, true, true, true));
            return;
        }
        if ("fireball".equalsIgnoreCase(type)) {
            consumeOne(player, held);
            Fireball fireball = player.getWorld().spawn(player.getEyeLocation(), Fireball.class);
            fireball.setShooter(player);
            fireball.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(1.35));
            fireball.setIsIncendiary(false);
            fireball.setYield(1.5f);
            fireball.getPersistentDataContainer().set(specialProjectileKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    private boolean isBedVoidWand(ItemStack item) {
        if (item == null || item.getType() != Material.WOODEN_AXE || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(bedVoidWandKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private ItemStack createBedVoidWand() {
        ItemStack axe = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.displayName(Texts.component("&bBedfight Void Selection Axe"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Left-click: position 1"), Texts.component("&7Right-click: finish void selection"))));
        meta.getPersistentDataContainer().set(bedVoidWandKey, PersistentDataType.BYTE, (byte) 1);
        axe.setItemMeta(meta);
        return axe;
    }

    private void removeBedVoidWands(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isBedVoidWand(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
    }

    private void handleBedVoidWandClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        BedVoidSetupState state = bedVoidSetupStates.get(uuid);
        event.setCancelled(true);
        if (state == null) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;
        Location clicked = clickedBlock.getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            state.setFirstCorner(clicked);
            Texts.send(player, "Bedfight void position 1 set. Right-click the opposite corner to finish.");
            return;
        }
        if (state.firstCorner() == null) {
            Texts.send(player, "Select void position 1 first by left-clicking a block.");
            return;
        }
        if (!Objects.equals(state.firstCorner().getWorld(), clicked.getWorld())) {
            Texts.send(player, "Both void positions must be in the arena world.");
            return;
        }
        boolean saved = arenaManager.setBedVoidRegion(state.arenaId(), state.firstCorner(), clicked);
        bedVoidSetupStates.remove(uuid);
        removeBedVoidWands(player);
        if (saved) Texts.send(player, "Void selection done. Run /varena enablebedfight " + state.arenaId() + " after confirming the beds and arena are ready.");
        else Texts.send(player, "Could not save void selection. Ensure the arena exists and is not in use, copying, or recovering.");
    }

    private void handleArenaWandClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ArenaSetupState state = arenaSetupStates.get(uuid);
        if (state == null) {
            event.setCancelled(true);
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            if (action == Action.LEFT_CLICK_AIR || action == Action.RIGHT_CLICK_AIR) event.setCancelled(true);
            return;
        }
        if (event.getClickedBlock() == null) return;
        event.setCancelled(true);
        Location clicked = event.getClickedBlock().getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            state.setFirstCorner(clicked);
            Texts.send(player, "Position 1 set. Right-click the opposite corner with the axe.");
            return;
        }
        if (state.firstCorner() == null) {
            Texts.send(player, "Select position 1 first by left-clicking a block.");
            return;
        }
        if (!Objects.equals(state.firstCorner().getWorld(), clicked.getWorld())) {
            Texts.send(player, "Both arena positions must be in the same world.");
            return;
        }
        completedArenaSelections.put(uuid, new ArenaSelection(state.arenaId(), state.firstCorner().clone(), clicked.clone()));
        arenaSetupStates.remove(uuid);
        removeArenaWands(player);
        Texts.send(player, "arena positions set");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDialogAction(PlayerCustomClickEvent event) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals("vprac")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        if (tournamentManager != null && tournamentManager.handleCustomClick(event, player)) return;
        if (duelManager != null && duelManager.handleCustomClick(event, player)) return;
        if (partyManager != null && partyManager.handleCustomClick(event, player)) return;
        if (playerPreferencesManager != null && playerPreferencesManager.handleCustomClick(event, player)) return;
        if (leaderboardHologramManager != null && leaderboardHologramManager.handleCustomClick(event, player)) return;
        if (leaderboardManager != null && leaderboardManager.handleCustomClick(event, player)) return;
        if (!key.value().startsWith("queue/")) return;

        String kitId = key.value().substring("queue/".length()).toLowerCase(Locale.ROOT);
        if (!kitManager.exists(kitId)) return;
        queueManager.toggle(player, kitId);
    }

    public void openQueueDialog(Player player) {
        openQueueDialog(player, true);
    }

    /** Opens a native, no-mod queue dialog. Each queue name is a real clickable text component
     * inside the body; per-kit action buttons are intentionally not created. */
    public void openQueueDialog(Player player, boolean playOpenSound) {
        if (player == null || !player.isOnline()) return;
        if (playOpenSound && (playerPreferencesManager == null || playerPreferencesManager.areSoundsEnabled(player.getUniqueId()))) {
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_FENCE_GATE_OPEN, 1.0f, 1.0f);
        }

        List<String> kitIds = kitManager.getKitIds();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(
                Component.text("Select a kit queue. Click the aqua kit name to join or leave it.", NamedTextColor.GRAY), 360));

        // Queue entries are clickable text inside the dialog body, not ActionButtons.
        // Minecraft 1.21.6+ supports click events on text components inside dialog bodies.
        // Keep the icon and queue count in the body so no duplicate kit buttons are shown below.
        for (String id : kitIds) {
            boolean selected = queueManager.isQueued(player.getUniqueId(), id);
            Key actionKey = Key.key("vprac", "queue/" + id);
            Component clickableName = Component.text(
                            kitManager.getDisplayName(id) + " Queue", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.custom(actionKey, BinaryTagHolder.binaryTagHolder("{}")))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            selected ? "Click to leave this queue." : "Click to join this queue.", NamedTextColor.GRAY)));

            Component rowText = clickableName.append(
                    Component.text("  ·  " + queueManager.count(id) + " queued", NamedTextColor.GRAY));
            if (selected) {
                rowText = rowText.append(Component.text("  ✔", NamedTextColor.GREEN));
            }

            ItemStack icon = new ItemStack(kitManager.getIconMaterial(id));
            body.add(DialogBody.item(icon, DialogBody.plainMessage(rowText, 340),
                    true, true, 24, 24));
        }

        // Keep only a single Close control in the footer. Queue membership is changed by
        // clicking the aqua text above; the old per-kit footer buttons are deliberately removed.
        ActionButton closeButton = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY)).build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Queues", NamedTextColor.AQUA))
                        .body(body)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.notice(closeButton)));

        player.showDialog(dialog);
    }

    public Location getLobbySpawn() {
        String worldName = getConfig().getString("lobby-spawn.world");
        if (worldName == null || worldName.isBlank()) return null;
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world,
                getConfig().getDouble("lobby-spawn.x"),
                getConfig().getDouble("lobby-spawn.y"),
                getConfig().getDouble("lobby-spawn.z"),
                (float) getConfig().getDouble("lobby-spawn.yaw"),
                (float) getConfig().getDouble("lobby-spawn.pitch"));
    }

    private boolean handleSetLobbyCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        Location location = player.getLocation();
        getConfig().set("lobby-spawn.world", location.getWorld().getName());
        getConfig().set("lobby-spawn.x", location.getX());
        getConfig().set("lobby-spawn.y", location.getY());
        getConfig().set("lobby-spawn.z", location.getZ());
        getConfig().set("lobby-spawn.yaw", location.getYaw());
        getConfig().set("lobby-spawn.pitch", location.getPitch());
        saveConfig();
        Texts.send(player, "Practice lobby spawn set at your current location.");
        return true;
    }

    public void openAdminKitSelectionMenu(Player player, int page) {
        if (!player.isOnline()) return;
        List<String> ids = kitManager.getKitIds();
        int pageCount = Math.max(1, (ids.size() + 44) / 45);
        int safePage = Math.max(0, Math.min(page, pageCount - 1));
        KitMenuHolder holder = new KitMenuHolder(KitMenuHolder.Type.ADMIN_KIT_SELECT, null, safePage);
        Inventory inventory = Bukkit.createInventory(holder, 54, Texts.component("&3Kit Rules"));
        holder.setInventory(inventory);
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inventory.setItem(i, filler);
        int start = safePage * 45;
        int end = Math.min(ids.size(), start + 45);
        for (int index = start; index < end; index++) {
            String id = ids.get(index);
            int slot = index - start;
            inventory.setItem(slot, menuItem(kitManager.getIconMaterial(id), "&b" + kitManager.getDisplayName(id), List.of(
                    "&7Click to configure this kit's rules.",
                    "&7Build: " + stateText(kitRulesManager.enabled(id, KitRulesManager.Rule.BUILD)),
                    "&7Destroy: " + stateText(kitRulesManager.enabled(id, KitRulesManager.Rule.DESTROY)),
                    "&7Crafting: " + stateText(kitRulesManager.enabled(id, KitRulesManager.Rule.CRAFTING)),
                    "&7Health regeneration: " + stateText(kitRulesManager.enabled(id, KitRulesManager.Rule.HEALTH_REGEN)),
                    "&7Unlimited hunger: " + stateText(kitRulesManager.enabled(id, KitRulesManager.Rule.UNLIMITED_HUNGER)),
                    "&7Rounds to win: &e" + kitRulesManager.roundsToWin(id)
            )));
            holder.mapKitSlot(slot, id);
        }
        if (safePage > 0) inventory.setItem(45, menuItem(Material.ARROW, "&ePrevious page", List.of()));
        inventory.setItem(49, menuItem(Material.BARRIER, "&cClose", List.of()));
        if (safePage + 1 < pageCount) inventory.setItem(53, menuItem(Material.ARROW, "&eNext page", List.of()));
        player.openInventory(inventory);
    }

    private void openKitRulesMenu(Player player, String kitId) {
        KitMenuHolder holder = new KitMenuHolder(KitMenuHolder.Type.KIT_RULES, kitId, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Texts.component("&3Kit Rules: &b" + kitManager.getDisplayName(kitId)));
        holder.setInventory(inventory);
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);

        inventory.setItem(4, menuItem(kitManager.getIconMaterial(kitId), "&b" + kitManager.getDisplayName(kitId) + " &7Rules", List.of(
                "&7Configure gameplay rules for this kit.",
                "&7Changes are saved to the server immediately."
        )));
        inventory.setItem(10, menuItem(Material.BOOK, "&e&lGeneral Rules", List.of("&7Build, block and survival settings.")));
        inventory.setItem(11, ruleMenuItem(kitId, KitRulesManager.Rule.BUILD, Material.BRICKS, "Build / Place blocks"));
        inventory.setItem(13, ruleMenuItem(kitId, KitRulesManager.Rule.DESTROY, Material.IRON_PICKAXE, "Destroy / Break blocks"));
        inventory.setItem(15, ruleMenuItem(kitId, KitRulesManager.Rule.CRAFTING, Material.CRAFTING_TABLE, "Crafting"));
        inventory.setItem(20, ruleMenuItem(kitId, KitRulesManager.Rule.HEALTH_REGEN, Material.GOLDEN_APPLE, "Natural heart regeneration"));
        inventory.setItem(22, ruleMenuItem(kitId, KitRulesManager.Rule.UNLIMITED_HUNGER, Material.COOKED_BEEF, "Unlimited hunger"));
        inventory.setItem(24, roundsMenuItem(kitId));
        inventory.setItem(26, ruleMenuItem(kitId, KitRulesManager.Rule.FALL_DAMAGE, Material.FEATHER, "Fall damage"));

        inventory.setItem(28, menuItem(Material.TNT, "&e&lTNT Auto-Ignition: " + stateText(kitRulesManager.enabled(kitId, KitRulesManager.Rule.AUTO_TNT_IGNITION)), List.of(
                "&7When enabled, placed TNT ignites automatically.",
                "&7Click to toggle this rule.", "&8Kit: " + kitManager.getDisplayName(kitId))));
        inventory.setItem(29, ignitionDelayMenuItem(true, kitId));
        inventory.setItem(32, menuItem(Material.CREEPER_SPAWN_EGG, "&e&lCreeper Auto-Ignition: " + stateText(kitRulesManager.enabled(kitId, KitRulesManager.Rule.AUTO_CREEPER_IGNITION)), List.of(
                "&7Creepers spawned from eggs ignite automatically.",
                "&7They cannot walk, but can still be pushed.",
                "&7Click to toggle this rule.", "&8Kit: " + kitManager.getDisplayName(kitId))));
        inventory.setItem(33, ignitionDelayMenuItem(false, kitId));

        if (!KitManager.SWORD_ID.equalsIgnoreCase(kitId)) {
            inventory.setItem(45, menuItem(Material.TNT, "&cRemove this kit", List.of(
                    "&7Permanently delete this custom kit.",
                    "&7Its rules, player profiles, and stats will be removed.",
                    "&cRequires confirmation."
            )));
        }
        inventory.setItem(49, menuItem(Material.ARROW, "&eBack to kits", List.of("&7Return to kit selection.")));
        inventory.setItem(53, menuItem(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inventory);
    }

    private void openKitDeleteConfirmation(Player player, String kitId) {
        if (!kitManager.exists(kitId) || KitManager.SWORD_ID.equalsIgnoreCase(kitId)) {
            Texts.send(player, "Only existing custom kits can be removed.");
            openAdminKitSelectionMenu(player, 0);
            return;
        }
        KitMenuHolder holder = new KitMenuHolder(KitMenuHolder.Type.KIT_DELETE_CONFIRM, kitId, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27, Texts.component("&4Confirm kit removal"));
        holder.setInventory(inventory);
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        inventory.setItem(13, menuItem(kitManager.getIconMaterial(kitId), "&cDelete &f" + kitManager.getDisplayName(kitId) + "?", List.of(
                "&7This permanently removes the custom kit.",
                "&7The built-in Sword kit is protected.",
                "&cThis action cannot be undone."
        )));
        inventory.setItem(11, menuItem(Material.RED_CONCRETE, "&cConfirm delete", List.of("&7Delete this kit and its per-kit data.")));
        inventory.setItem(15, menuItem(Material.LIME_CONCRETE, "&aCancel", List.of("&7Keep this kit.")));
        inventory.setItem(22, menuItem(Material.ARROW, "&eBack", List.of("&7Return to kit rules.")));
        inventory.setItem(26, menuItem(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inventory);
    }

    private ItemStack ruleMenuItem(String kitId, KitRulesManager.Rule rule, Material icon, String title) {
        boolean enabled = kitRulesManager.enabled(kitId, rule);
        return menuItem(icon, (enabled ? "&a" : "&c") + title + ": " + (enabled ? "&aENABLED" : "&cDISABLED"),
                List.of("&7Click to toggle this rule.", "&8Kit: " + kitManager.getDisplayName(kitId),
                        "&7Current value: " + (enabled ? "&aAllowed" : "&cBlocked")));
    }

    private ItemStack roundsMenuItem(String kitId) {
        return menuItem(Material.CLOCK, "&eRounds to win: &b" + kitRulesManager.roundsToWin(kitId), List.of(
                "&7Choose how many rounds a player/team must win to win the match.",
                "&aLeft-click: add one round.",
                "&cRight-click: remove one round.",
                "&7Minimum: 1 | Maximum: 10",
                "&8Kit: " + kitManager.getDisplayName(kitId)
        ));
    }

    private ItemStack ignitionDelayMenuItem(boolean tnt, String kitId) {
        int seconds = tnt ? kitRulesManager.tntIgnitionSeconds(kitId) : kitRulesManager.creeperIgnitionSeconds(kitId);
        String label = tnt ? "TNT" : "Creeper";
        return menuItem(Material.GREEN_DYE, "&a&l" + label + " ignition time: &f" + seconds + "s", List.of(
                "&7Set the delay before " + label.toLowerCase(Locale.ROOT) + " ignition.",
                "&aLeft-click: add one second.",
                "&cRight-click: remove one second.",
                "&7Minimum: 0s | Maximum: 5s",
                "&8Kit: " + kitManager.getDisplayName(kitId)
        ));
    }

    private String stateText(boolean enabled) { return enabled ? "&aEnabled" : "&cDisabled"; }

    public void closeQueueDialog(Player player) {
        if (player != null && player.isOnline()) player.closeDialog();
    }

    public void openKitSelectMenu(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        if (queueManager.isInMatch(uuid)) {
            Texts.send(player, "You cannot edit kits during a match.");
            return;
        }
        if (kitModeStates.containsKey(uuid)) {
            Texts.send(player, "Leave kit creation mode by saving the kit before editing player profiles.");
            return;
        }

        List<String> ids = kitManager.getKitIds();
        int pageCount = Math.max(1, (ids.size() + 44) / 45);
        int safePage = Math.max(0, Math.min(page, pageCount - 1));
        KitMenuHolder holder = new KitMenuHolder(KitMenuHolder.Type.KIT_SELECT, null, safePage);
        Inventory inventory = Bukkit.createInventory(holder, 54, Texts.component("&3Edit Kit"));
        holder.setInventory(inventory);
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inventory.setItem(i, filler);

        int start = safePage * 45;
        int end = Math.min(ids.size(), start + 45);
        for (int index = start; index < end; index++) {
            String id = ids.get(index);
            int slot = index - start;
            ItemStack icon = menuItem(kitManager.getIconMaterial(id), "&b" + kitManager.getDisplayName(id), List.of(
                    "&7Click to select this kit.",
                    "&7You can save up to 3 profiles for it.",
                    "&8Active kit: " + (id.equalsIgnoreCase(editingKits.get(uuid)) ? "&aYes" : "&7No")
            ));
            inventory.setItem(slot, icon);
            holder.mapKitSlot(slot, id);
        }
        if (safePage > 0) inventory.setItem(45, menuItem(Material.ARROW, "&ePrevious page", List.of("&7Page " + safePage + " / " + (pageCount - 1))));
        inventory.setItem(49, menuItem(Material.BARRIER, "&cClose", List.of("&7Close the kit menu.")));
        if (safePage + 1 < pageCount) inventory.setItem(53, menuItem(Material.ARROW, "&eNext page", List.of("&7Page " + (safePage + 2) + " / " + pageCount)));
        player.openInventory(inventory);
    }

    private void openKitProfilesMenu(Player player, String kitId) {
        KitMenuHolder holder = new KitMenuHolder(KitMenuHolder.Type.KIT_PROFILES, kitId, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Texts.component("&3Kit Profiles: &b" + kitManager.getDisplayName(kitId)));
        holder.setInventory(inventory);
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        inventory.setItem(4, menuItem(kitManager.getIconMaterial(kitId), "&b" + kitManager.getDisplayName(kitId), List.of(
                "&7Your current inventory layout is editable",
                "&7after you close this menu.",
                "&7Reopen the book to save/load profiles."
        )));
        int[] slots = {11, 13, 15};
        UUID uuid = player.getUniqueId();
        int selected = kitProfileManager.selectedProfile(uuid, kitId);
        for (int profile = 1; profile <= KitProfileManager.PROFILE_COUNT; profile++) {
            boolean exists = kitProfileManager.hasProfile(uuid, kitId, profile);
            Material material = exists ? Material.ENDER_CHEST : Material.BOOK;
            String status = exists ? "&aSaved" : "&7Empty";
            if (exists && selected == profile) status += " &b(active)";
            inventory.setItem(slots[profile - 1], menuItem(material, "&bProfile " + profile + " " + status, List.of(
                    "&eLeft-click: &fSave/overwrite current layout",
                    "&eRight-click: &fLoad this saved layout",
                    exists ? "&7A saved profile exists." : "&7Right-click will not load an empty profile."
            )));
        }
        inventory.setItem(22, menuItem(Material.ARROW, "&eBack to kits", List.of("&7Return to the kit list.")));
        inventory.setItem(24, menuItem(Material.LIME_DYE, "&aFinish editing", List.of(
                "&7Return to the lobby and receive your queue items.",
                "&7Save your layout to a profile first if needed."
        )));
        inventory.setItem(26, menuItem(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inventory);
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null || material.isAir() ? Material.CHEST : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(Texts.lore(line));
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void handleKitMenuClick(Player player, KitMenuHolder holder, int slot, ClickType clickType) {
        if (holder.type() == KitMenuHolder.Type.KIT_DELETE_CONFIRM) {
            String kitId = holder.kitId();
            if (slot == 15 || slot == 22) {
                if (kitManager.exists(kitId)) openKitRulesMenu(player, kitId);
                else openAdminKitSelectionMenu(player, 0);
                return;
            }
            if (slot == 26) { player.closeInventory(); return; }
            if (slot != 11) return;
            if (KitManager.SWORD_ID.equalsIgnoreCase(kitId)) {
                Texts.send(player, "The built-in Sword kit cannot be removed.");
                openAdminKitSelectionMenu(player, 0);
                return;
            }
            try {
                if (!kitManager.removeCustomKit(kitId)) {
                    Texts.send(player, "That custom kit no longer exists.");
                    openAdminKitSelectionMenu(player, 0);
                    return;
                }
            } catch (IOException exception) {
                getLogger().severe("Could not remove kit " + kitId + ": " + exception.getMessage());
                Texts.send(player, "Could not remove that kit. Check the server console.");
                openAdminKitSelectionMenu(player, 0);
                return;
            }
            try { kitRulesManager.removeKit(kitId); }
            catch (IOException exception) { getLogger().severe("Could not remove rules for kit " + kitId + ": " + exception.getMessage()); }
            try { kitProfileManager.removeKitData(kitId); }
            catch (IOException exception) { getLogger().severe("Could not remove profiles for kit " + kitId + ": " + exception.getMessage()); }
            try { leaderboardManager.removeKitData(kitId); }
            catch (IOException exception) { getLogger().severe("Could not remove leaderboard stats for kit " + kitId + ": " + exception.getMessage()); }
            queueManager.onKitRemoved(kitId);
            partyManager.onKitRemoved(kitId);
            editingKits.entrySet().removeIf(entry -> entry.getValue().equalsIgnoreCase(kitId));
            Texts.send(player, "Removed custom kit '&b" + kitId + "&r'. Its rules, profiles, queues, party selections, and leaderboard stats were cleaned where storage permitted.");
            openAdminKitSelectionMenu(player, 0);
            return;
        }
        if (holder.type() == KitMenuHolder.Type.ADMIN_KIT_SELECT) {
            if (slot == 49) { player.closeInventory(); return; }
            if (slot == 45 && holder.page() > 0) { openAdminKitSelectionMenu(player, holder.page() - 1); return; }
            if (slot == 53) { openAdminKitSelectionMenu(player, holder.page() + 1); return; }
            String id = holder.kitAt(slot);
            if (id != null && kitManager.exists(id)) openKitRulesMenu(player, id);
            return;
        }
        if (holder.type() == KitMenuHolder.Type.KIT_RULES) {
            if (slot == 49) { openAdminKitSelectionMenu(player, 0); return; }
            if (slot == 53) { player.closeInventory(); return; }
            if (slot == 45 && !KitManager.SWORD_ID.equalsIgnoreCase(holder.kitId())) {
                openKitDeleteConfirmation(player, holder.kitId());
                return;
            }
            if (slot == 29 || slot == 33) {
                boolean tnt = slot == 29;
                int current = tnt ? kitRulesManager.tntIgnitionSeconds(holder.kitId()) : kitRulesManager.creeperIgnitionSeconds(holder.kitId());
                int next = current;
                if (clickType.isLeftClick()) next = Math.min(5, current + 1);
                else if (clickType.isRightClick()) next = Math.max(0, current - 1);
                else return;
                if (next == current) {
                    Texts.send(player, next == 5 ? "Ignition time is already at the maximum of 5 seconds." : "Ignition time is already at the minimum of 0 seconds.");
                } else {
                    try {
                        if (tnt) kitRulesManager.setTntIgnitionSeconds(holder.kitId(), next);
                        else kitRulesManager.setCreeperIgnitionSeconds(holder.kitId(), next);
                        Texts.send(player, (tnt ? "TNT" : "Creeper") + " ignition time set to &e" + next + " seconds&r.");
                    } catch (IOException exception) {
                        getLogger().severe("Could not save ignition delay: " + exception.getMessage());
                        Texts.send(player, "Could not save ignition time. Check the server console.");
                    }
                }
                openKitRulesMenu(player, holder.kitId());
                return;
            }
            if (slot == 24) {
                int current = kitRulesManager.roundsToWin(holder.kitId());
                int next = current;
                if (clickType.isLeftClick()) next = Math.min(10, current + 1);
                else if (clickType.isRightClick()) next = Math.max(1, current - 1);
                else return;
                if (next == current) {
                    Texts.send(player, next == 10 ? "Rounds to win is already at the maximum of 10." : "Rounds to win is already at the minimum of 1.");
                } else {
                    try {
                        kitRulesManager.setRoundsToWin(holder.kitId(), next);
                        Texts.send(player, "Rounds to win for '&b" + kitManager.getDisplayName(holder.kitId()) + "&r' set to &e" + next + "&r.");
                    } catch (IOException exception) {
                        getLogger().severe("Could not save kit round settings: " + exception.getMessage());
                        Texts.send(player, "Could not save the round setting. Check the server console.");
                    }
                }
                openKitRulesMenu(player, holder.kitId());
                return;
            }
            KitRulesManager.Rule rule = switch (slot) {
                case 11 -> KitRulesManager.Rule.BUILD;
                case 13 -> KitRulesManager.Rule.DESTROY;
                case 15 -> KitRulesManager.Rule.CRAFTING;
                case 20 -> KitRulesManager.Rule.HEALTH_REGEN;
                case 22 -> KitRulesManager.Rule.UNLIMITED_HUNGER;
                case 26 -> KitRulesManager.Rule.FALL_DAMAGE;
                case 28 -> KitRulesManager.Rule.AUTO_TNT_IGNITION;
                case 32 -> KitRulesManager.Rule.AUTO_CREEPER_IGNITION;
                default -> null;
            };
            if (rule == null) return;
            try {
                boolean nowEnabled = kitRulesManager.toggle(holder.kitId(), rule);
                Texts.send(player, "Rule '&b" + rule.name().toLowerCase(Locale.ROOT).replace('_', ' ') + "&r' is now " + (nowEnabled ? "&aenabled" : "&cdisabled") + " for '&b" + kitManager.getDisplayName(holder.kitId()) + "&r'.");
                openKitRulesMenu(player, holder.kitId());
            } catch (IOException exception) {
                getLogger().severe("Could not save kit rules: " + exception.getMessage());
                Texts.send(player, "Could not save kit rules. Check the console.");
            }
            return;
        }
        if (holder.type() == KitMenuHolder.Type.KIT_SELECT) {
            if (slot == 49) { player.closeInventory(); return; }
            if (slot == 45 && holder.page() > 0) { openKitSelectMenu(player, holder.page() - 1); return; }
            if (slot == 53) { openKitSelectMenu(player, holder.page() + 1); return; }
            String kitId = holder.kitAt(slot);
            if (kitId == null || !kitManager.exists(kitId)) return;
            String currentlyEditing = editingKits.get(player.getUniqueId());
            if (!kitId.equalsIgnoreCase(currentlyEditing)) {
                editingKits.put(player.getUniqueId(), kitId);
                if (!kitProfileManager.applySelected(player, kitId)) {
                    kitManager.apply(player.getInventory(), kitId);
                }
                giveEditKitBook(player);
                player.setFoodLevel(20);
                player.setSaturation(20.0f);
                Texts.send(player, "Selected kit '&b" + kitManager.getDisplayName(kitId) + "&r'. Close the menu and arrange your inventory, then reopen Edit Kit to save a profile.");
            }
            openKitProfilesMenu(player, kitId);
            return;
        }

        if (slot == 22) { openKitSelectMenu(player, 0); return; }
        if (slot == 24) {
            player.closeInventory();
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            editingKits.remove(player.getUniqueId());
            giveLobbyItems(player);
            Texts.send(player, "Returned to the lobby. Save a profile before finishing if you want to keep your layout.");
            return;
        }
        if (slot == 26) { player.closeInventory(); return; }
        int[] profileSlots = {11, 13, 15};
        int profile = -1;
        for (int i = 0; i < profileSlots.length; i++) if (profileSlots[i] == slot) profile = i + 1;
        if (profile == -1) return;

        String kitId = holder.kitId();
        if (clickType.isLeftClick()) {
            try {
                kitProfileManager.saveProfile(player, kitId, profile);
                Texts.send(player, "Saved your current '&b" + kitManager.getDisplayName(kitId) + "&r' layout to profile " + profile + ".");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) openKitProfilesMenu(player, kitId);
                });
            } catch (IOException exception) {
                getLogger().severe("Could not save kit profile: " + exception.getMessage());
                Texts.send(player, "Could not save that profile. Check the server console.");
            }
        } else if (clickType.isRightClick()) {
            if (!kitProfileManager.loadProfile(player, kitId, profile)) {
                Texts.send(player, "Profile " + profile + " is empty. Left-click it to save your current layout.");
                return;
            }
            editingKits.put(player.getUniqueId(), kitId);
            giveEditKitBook(player);
            player.closeInventory();
            Texts.send(player, "Loaded profile " + profile + " for '&b" + kitManager.getDisplayName(kitId) + "&r'. Your layout is ready to edit.");
        }
    }

    public void giveLobbyItems(Player player) {
        spectatorPacketUsers.remove(player.getUniqueId());
        if (tournamentManager != null) {
            tournamentManager.clearTournamentItems(player);
            if (tournamentManager.isRegistered(player.getUniqueId())) {
                // Match return callbacks call giveLobbyItems before their internal player mapping is
                // fully removed. Keep tournament controls in that short cleanup window as well.
                tournamentManager.giveParticipantItems(player);
                return;
            }
        }
        if (partyManager != null && partyManager.getParty(player.getUniqueId()) != null) {
            removeQueueItem(player);
        } else {
            giveQueueItem(player, true);
        }
        givePartyItem(player);
        giveLeaderboardItem(player);
        giveSettingsItem(player);
        giveEditKitBook(player);
        if (playerPreferencesManager != null) {
            playerPreferencesManager.refreshLobbyFlight(player);
            // Match cleanup can call this method just before the match manager releases its UUID mapping.
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline() && playerPreferencesManager != null) playerPreferencesManager.refreshLobbyFlight(player);
            });
        }
    }

    private void givePartyItem(Player player) {
        removeTaggedInventoryItems(player, partyToolKey);
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component("&bCreate Party&8(&7Right Click&8)"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Create or manage your party."))));
        meta.getPersistentDataContainer().set(partyToolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        // User-facing hotbar slot 5 (Bukkit inventory index 4), moved one slot left from slot 6.
        player.getInventory().setItem(4, item);
    }

    private void giveLeaderboardItem(Player player) {
        removeTaggedInventoryItems(player, leaderboardToolKey);
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component("&6Leaderboards"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7View per-kit Top 10 rankings."), Texts.component("&7Right-click to open."))));
        meta.getPersistentDataContainer().set(leaderboardToolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        // Visible hotbar slot 7 (Bukkit index 6), immediately before Settings in slot 8.
        player.getInventory().setItem(6, item);
    }

    private void giveSettingsItem(Player player) {
        removeTaggedInventoryItems(player, settingsToolKey);
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component("&8Settings"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Personal settings."), Texts.component("&7Right-click to open."))));
        meta.getPersistentDataContainer().set(settingsToolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        // User-facing hotbar slot 8 (Bukkit inventory index 7).
        player.getInventory().setItem(7, item);
    }

    private void giveEditKitBook(Player player) {
        removeTaggedInventoryItems(player, editKitKey);
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(Texts.component("&bPractice Menu &7(Right Click)"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Open the Practice Menu."))));
        meta.getPersistentDataContainer().set(editKitKey, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        // User-facing hotbar slot 9 (Bukkit inventory index 8).
        player.getInventory().setItem(8, book);
    }

    /** Gives eliminated party spectators exactly two utility items: tracker in slot 1 and leave in slot 9. */
    public void givePartySpectatorItems(Player player) {
        spectatorPacketUsers.add(player.getUniqueId());
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(new ItemStack(Material.AIR));

        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta compassMeta = compass.getItemMeta();
        compassMeta.displayName(Texts.component("&bTrack Players"));
        compassMeta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Right-click to choose a player to spectate."))));
        compassMeta.getPersistentDataContainer().set(spectatorTrackerKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(compassMeta);
        inventory.setItem(0, compass);

        ItemStack barrier = new ItemStack(Material.BARRIER);
        ItemMeta barrierMeta = barrier.getItemMeta();
        barrierMeta.displayName(Texts.component("&4Leave Party"));
        barrierMeta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Leave the party and return to the lobby."))));
        barrierMeta.getPersistentDataContainer().set(spectatorLeaveKey, PersistentDataType.BYTE, (byte) 1);
        barrier.setItemMeta(barrierMeta);
        inventory.setItem(8, barrier);
        inventory.setHeldItemSlot(0);
    }

    /** Gives outside /spec viewers a compass in hotbar slot 1 and red dye to return to the lobby. */
    public void giveMatchSpectatorItems(Player player) {
        spectatorPacketUsers.add(player.getUniqueId());
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(new ItemStack(Material.AIR));

        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta compassMeta = compass.getItemMeta();
        compassMeta.displayName(Texts.component("&bTrack Players"));
        compassMeta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Right-click to choose a player to spectate."))));
        compassMeta.getPersistentDataContainer().set(spectatorTrackerKey, PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(compassMeta);
        inventory.setItem(0, compass);

        ItemStack redDye = new ItemStack(Material.RED_DYE);
        ItemMeta dyeMeta = redDye.getItemMeta();
        dyeMeta.displayName(Texts.component("&cStop Spectating"));
        dyeMeta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Right-click to return to the lobby."))));
        dyeMeta.getPersistentDataContainer().set(spectatorStopKey, PersistentDataType.BYTE, (byte) 1);
        redDye.setItemMeta(dyeMeta);
        inventory.setItem(8, redDye);
        inventory.setHeldItemSlot(0);
    }

    private boolean isSpectatorStopItem(ItemStack item) {
        if (item == null || item.getType() != Material.RED_DYE || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(spectatorStopKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isSpectatorTrackerItem(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(spectatorTrackerKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isSpectatorLeaveItem(ItemStack item) {
        if (item == null || item.getType() != Material.BARRIER || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(spectatorLeaveKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public boolean isLobbyItem(ItemStack item) {
        return isQueueTool(item) || isEditKitBook(item) || isPartyTool(item) || isSettingsTool(item)
                || isLeaderboardTool(item) || isArenaWand(item) || isSpectatorTrackerItem(item)
                || isSpectatorLeaveItem(item) || isSpectatorStopItem(item)
                || (tournamentManager != null && tournamentManager.isTournamentItem(item));
    }

    public void removeQueueItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (isQueueTool(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
    }

    private void removeTaggedInventoryItems(Player player, NamespacedKey key) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !item.hasItemMeta()) continue;
            Byte marker = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
            if (marker != null && marker == (byte) 1) inventory.setItem(slot, null);
        }
    }

    private boolean isPartyTool(ItemStack item) {
        if (item == null || item.getType() != Material.NAME_TAG || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(partyToolKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isSettingsTool(ItemStack item) {
        if (item == null || item.getType() != Material.CRAFTING_TABLE || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(settingsToolKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isLeaderboardTool(ItemStack item) {
        if (item == null || item.getType() != Material.OAK_SIGN || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(leaderboardToolKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private boolean isEditKitBook(ItemStack item) {
        if (item == null || item.getType() != Material.BOOK || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(editKitKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private ItemStack createArenaWand() {
        ItemStack axe = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = axe.getItemMeta();
        meta.displayName(Texts.component("&bFAWE WorldEdit Axe"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Left-click: position 1"), Texts.component("&7Right-click: position 2"))));
        meta.getPersistentDataContainer().set(arenaWandKey, PersistentDataType.BYTE, (byte) 1);
        axe.setItemMeta(meta);
        return axe;
    }

    private boolean isArenaWand(ItemStack item) {
        if (item == null || item.getType() != Material.WOODEN_AXE || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(arenaWandKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void removeArenaWands(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isArenaWand(inventory.getItem(i))) inventory.setItem(i, null);
        }
    }

    public void giveQueueItem(Player player) {
        giveQueueItem(player, true);
    }

    /** Gives the queue sword only when the player is not in a party. */
    public void giveQueueItem(Player player, boolean selectSlot) {
        if (partyManager != null && partyManager.getParty(player.getUniqueId()) != null) {
            removeQueueItem(player);
            return;
        }
        removeQueueItem(player);
        int slot = Math.max(0, Math.min(8, getConfig().getInt("queue-item-slot", 0)));
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component(getConfig().getString("queue-item-name", "&bQueues&8(&7Right Click&8)")));
        meta.getPersistentDataContainer().set(queueToolKey, PersistentDataType.BYTE, (byte) 1);

        double desiredSpeed = getConfig().getDouble("queue-item-attack-speed", 20.0);
        // Player base speed 4.0 + diamond sword's default -2.4 + this modifier = desiredSpeed.
        double modifier = desiredSpeed - 1.6;
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                attackSpeedKey, modifier, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        item.setItemMeta(meta);
        player.getInventory().setItem(slot, item);
        if (selectSlot) player.getInventory().setHeldItemSlot(slot);
    }

    private boolean isQueueTool(ItemStack item) {
        if (item == null || item.getType() != Material.DIAMOND_SWORD || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(queueToolKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (matchReportManager != null && matchReportManager.isViewInventory(top)) {
            event.setCancelled(true);
            return;
        }
        if (top.getHolder() instanceof SpectatorTrackerHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == top && event.getRawSlot() < top.getSize()) {
                UUID targetId = holder.targetAt(event.getRawSlot());
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) return;
                    if (partyMatchManager.isEliminatedSpectator(player.getUniqueId())) partyMatchManager.trackPlayer(player, targetId);
                    else if (matchSpectatorManager.isSpectating(player.getUniqueId())) matchSpectatorManager.trackPlayer(player, targetId);
                });
            }
            return;
        }
        if (top.getHolder() instanceof KitMenuHolder holder) {
            event.setCancelled(true);
            if (event.getClickedInventory() == top && event.getRawSlot() < top.getSize()) {
                // Bukkit advises deferring inventory transitions until after InventoryClickEvent;
                // otherwise an open/close performed in this callback can be overwritten internally.
                int clickedSlot = event.getRawSlot();
                ClickType clickType = event.getClick();
                Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) handleKitMenuClick(player, holder, clickedSlot, clickType);
                });
            }
            return;
        }

        boolean protectedItem = isLobbyItem(event.getCurrentItem()) || isLobbyItem(event.getCursor());
        int hotbarButton = event.getHotbarButton();
        if (!protectedItem && hotbarButton >= 0 && hotbarButton <= 8) {
            protectedItem = isLobbyItem(player.getInventory().getItem(hotbarButton));
        }
        if (protectedItem) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (matchReportManager != null && matchReportManager.isViewInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof SpectatorTrackerHolder) {
            event.setCancelled(true);
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof KitMenuHolder) {
            event.setCancelled(true);
            return;
        }
        if (isLobbyItem(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        // A player can otherwise drag an ordinary stack onto a protected hotbar item and replace it.
        for (int rawSlot : event.getRawSlots()) {
            if (isLobbyItem(event.getView().getItem(rawSlot))) {
                event.setCancelled(true);
                return;
            }
        }
        for (ItemStack item : event.getNewItems().values()) {
            if (isLobbyItem(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (isLobbyItem(item) || isBedSetupItem(item) || isBedVoidWand(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        ItemStack main = event.getMainHandItem();
        ItemStack off = event.getOffHandItem();
        if (isLobbyItem(main) || isLobbyItem(off) || isBedSetupItem(main) || isBedSetupItem(off)
                || isBedVoidWand(main) || isBedVoidWand(off)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (isLobbyItem(item) || isBedSetupItem(item) || isBedVoidWand(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // Suppress vanilla's global death broadcast; NexPractice sends a private PvP kill line to the participants.
        event.deathMessage(null);
        if (matchReportManager != null) matchReportManager.handleDeath(event);
        if (partyMatchManager.handleDeath(event)) return;
        queueManager.handleDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (partyMatchManager.handleRespawn(event)) return;
        queueManager.restoreLobbyOnRespawn(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Block block = event.getBlock();
        Location bedFoot = bedFootLocation(block);

        // During setup the first bed is only counted while it still exists in the world.
        // Check every setup session so a different administrator breaking it also clears the count.
        if (bedFoot != null) {
            for (Map.Entry<UUID, BedSetupState> setupEntry : new ArrayList<>(bedSetupStates.entrySet())) {
                BedSetupState setup = setupEntry.getValue();
                if (sameBlock(setup.firstBed(), bedFoot)) {
                    setup.setFirstBed(null);
                    Player setupPlayer = Bukkit.getPlayer(setupEntry.getKey());
                    if (setupPlayer != null && setupPlayer.isOnline()) {
                        Texts.send(setupPlayer, "The first setup bed was broken and removed from the count. Place bed one again.");
                    }
                }
            }
        }

        Match bedMatch = queueManager == null ? null : queueManager.getMatch(uuid);
        MatchContext context = activeMatchContext(uuid);
        if (context == null) return;
        if (context.finishing() || context.countdown()
                || !context.arena().contains(block.getLocation())) {
            event.setCancelled(true);
            return;
        }

        PartyMatch partyBedMatch = partyMatchManager == null ? null : partyMatchManager.getActiveMatch(uuid);
        if (isPartySplitBedfight(partyBedMatch) && bedFoot != null) {
            int playerTeam = partyBedMatch.team(uuid);
            int bedTeam = configuredPartyBedTeam(partyBedMatch, bedFoot);
            if (!partyBedMatch.onArena() || !partyBedMatch.isAlive(uuid)
                    || playerTeam < 0 || bedTeam < 0 || bedTeam == playerTeam) {
                event.setCancelled(true);
                if (bedTeam == playerTeam && playerTeam >= 0) {
                    Texts.send(player, "You cannot break your team's bed.");
                } else {
                    Texts.send(player, "You can only break the enemy team's configured bed.");
                }
                return;
            }
            // The enemy's configured bed is always breakable, even when ordinary block
            // destruction is disabled for this kit. Do not drop a bed item or XP.
            // The MONITOR handler sends the destruction alert only after this break succeeds.
            event.setDropItems(false);
            event.setExpToDrop(0);
            return;
        }

        if (bedMatch != null && bedMatch.bedfight() && bedFoot != null) {
            UUID owner = bedMatch.bedOwnerAt(bedFoot);
            if (owner != null) {
                if (owner.equals(uuid)) {
                    event.setCancelled(true);
                    Texts.send(player, "You cannot break your own bed.");
                    return;
                }
                event.setDropItems(false);
                event.setExpToDrop(0);
                if (bedMatch.destroyBed(owner)) notifyBedDestroyed(bedMatch, owner);
                // Opponents may break beds even if ordinary block destruction is disabled.
                return;
            }
        }
        if (!kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.DESTROY)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void notifyPartySplitBedBreak(BlockBreakEvent event) {
        Location bedFoot = bedFootLocation(event.getBlock());
        if (bedFoot == null || partyMatchManager == null) return;
        PartyMatch match = activePartyBedfightAt(event.getBlock().getLocation());
        if (match == null) return;
        int bedTeam = configuredPartyBedTeam(match, bedFoot);
        if (bedTeam >= 0) notifyPartySplitBedDestroyed(match, bedTeam);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void forgetTrackedPlacedBlock(BlockBreakEvent event) {
        placedBlocksByMatch.remove(blockKey(event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        BedSetupState setup = bedSetupStates.get(uuid);
        if (setup != null && isBedSetupItem(event.getItemInHand())) {
            if (!(event.getBlockPlaced().getBlockData() instanceof Bed)) {
                event.setCancelled(true);
                Texts.send(player, "Only the provided bed items can be used for Bedfight setup.");
                return;
            }
            Location placedLocation = event.getBlockPlaced().getLocation();
            Bukkit.getScheduler().runTaskLater(this, () -> processBedSetupPlacement(player, placedLocation), 1L);
            return;
        }

        MatchContext context = activeMatchContext(uuid);
        if (context == null) return;
        Block placed = event.getBlockPlaced();
        if (context.finishing() || context.countdown()
                || !context.arena().contains(placed.getLocation())) {
            event.setCancelled(true);
            return;
        }
        Match activeSolo = queueManager.getMatch(uuid);
        PartyMatch activeParty = partyMatchManager == null ? null : partyMatchManager.getActiveMatch(uuid);
        if (((activeSolo != null && activeSolo.bedfight())
                || isPartySplitBedfight(activeParty)) && placed.getBlockData() instanceof Bed) {
            // Bedfight beds are the two preconfigured team beds only; don't let players place
            // extra, untracked beds during a match.
            event.setCancelled(true);
            return;
        }
        if (!kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.BUILD)) {
            event.setCancelled(true);
            return;
        }
        Object matchIdentity = activeMatchIdentity(uuid);
        if (matchIdentity != null && placed.getType() != Material.TNT) {
            Location placedLocation = placed.getLocation().clone();
            Material expectedType = placed.getType();
            String placedKey = blockKey(placedLocation);
            // Record on the following tick, after the server has finalized placement and other plugins have run.
            Bukkit.getScheduler().runTask(this, () -> {
                if (activeMatchIdentity(uuid) != matchIdentity || placedLocation.getWorld() == null) return;
                Block current = placedLocation.getWorld().getBlockAt(placedLocation);
                if (current.getType() == expectedType && !current.getType().isAir()) {
                    placedBlocksByMatch.put(placedKey, new PlacedBlockMarker(matchIdentity));
                }
            });
        }
        if (placed.getType() == Material.TNT && kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.AUTO_TNT_IGNITION)) {
            int fuseSeconds = kitRulesManager.tntIgnitionSeconds(context.kitId());
            Location tntLocation = placed.getLocation().clone();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                World world = tntLocation.getWorld();
                if (world == null || world.getBlockAt(tntLocation).getType() != Material.TNT) return;
                world.getBlockAt(tntLocation).setType(Material.AIR, false);
                TNTPrimed primed = world.spawn(tntLocation.clone().add(0.5, 0.0, 0.5), TNTPrimed.class);
                primed.setSource(player);
                primed.setFuseTicks(Math.max(1, fuseSeconds * 20));
            }, 1L);
        }
    }

    private boolean isBedSetupItem(ItemStack item) {
        if (item == null || item.getType() != Material.RED_BED || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(bedSetupItemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private ItemStack createBedSetupItem() {
        ItemStack item = new ItemStack(Material.RED_BED, 2);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component("&cBedfight Setup Bed"));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component("&7Place two beds inside the saved arena border."))));
        meta.getPersistentDataContainer().set(bedSetupItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void removeBedSetupItems(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isBedSetupItem(inventory.getItem(slot))) inventory.setItem(slot, null);
        }
    }

    private Location bedFootLocation(Block block) {
        if (block == null || !(block.getBlockData() instanceof Bed bed)) return null;
        Block foot = bed.getPart() == Bed.Part.HEAD
                ? block.getRelative(bed.getFacing().getOppositeFace()) : block;
        return foot.getLocation();
    }

    private boolean sameBlock(Location first, Location second) {
        return first != null && second != null && first.getWorld() != null && second.getWorld() != null
                && first.getWorld().getName().equals(second.getWorld().getName())
                && first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private boolean isPartySplitBedfight(PartyMatch match) {
        return match != null && match.mode() == PartyMatch.Mode.SPLIT
                && match.arena() != null && match.arena().bedfightEnabled();
    }

    /** Returns 0 for configured bed A, 1 for configured bed B, or -1 for any other location. */
    private int configuredPartyBedTeam(PartyMatch match, Location bedFoot) {
        if (!isPartySplitBedfight(match) || bedFoot == null) return -1;
        if (sameBlock(bedFoot, match.arena().bedA())) return 0;
        if (sameBlock(bedFoot, match.arena().bedB())) return 1;
        return -1;
    }

    private PartyMatch activePartyBedfightAt(Location location) {
        if (location == null || partyMatchManager == null) return null;
        for (Player participant : Bukkit.getOnlinePlayers()) {
            PartyMatch match = partyMatchManager.getActiveMatch(participant.getUniqueId());
            if (isPartySplitBedfight(match) && match.onArena() && !match.finishing()
                    && match.arena().contains(location)) return match;
        }
        return null;
    }

    private UUID explosionPlayerSource(Entity entity) {
        if (entity instanceof Fireball fireball && fireball.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player.getUniqueId();
        }
        if (entity instanceof Creeper creeper) {
            CreeperHitCredit credit = creeperHitCredits.get(creeper.getUniqueId());
            if (credit != null && System.currentTimeMillis() - credit.atMillis() <= 30_000L) {
                return credit.playerId();
            }
        }
        return null;
    }

    private void processBedSetupPlacement(Player player, Location placedLocation) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        BedSetupState setup = bedSetupStates.get(uuid);
        if (setup == null || placedLocation.getWorld() == null) return;
        Block placedBlock = placedLocation.getWorld().getBlockAt(placedLocation);
        Location foot = bedFootLocation(placedBlock);
        ArenaData arena = arenaManager.get(setup.arenaId());
        if (foot == null || arena == null || !arena.contains(foot)) {
            Texts.send(player, "That bed is not inside the saved arena border. It was not counted for Bedfight setup.");
            return;
        }
        if (setup.firstBed() == null) {
            setup.setFirstBed(foot.clone());
            Texts.send(player, "Bed A saved. Place the second bed inside the arena border.");
            return;
        }
        if (sameBlock(setup.firstBed(), foot)) {
            Texts.send(player, "Place the second bed at a different position.");
            return;
        }
        if (!arenaManager.setBedLocations(setup.arenaId(), setup.firstBed(), foot)) {
            Texts.send(player, "Could not save both beds. Ensure both are inside the border and the arena isn't in use or recovering.");
            return;
        }
        bedSetupStates.remove(uuid);
        removeBedSetupItems(player);
        Texts.send(player, "Both beds placed. Saving them into the arena baseline snapshot...");
        boolean started = arenaManager.refreshSnapshotPreservingSettings(setup.arenaId(), success -> {
            if (!player.isOnline()) return;
            if (success) Texts.send(player, "Bedwars setup done. Next set the void with /varena bedsetvoid " + setup.arenaId() + " and enable it using /varena enablebedfight " + setup.arenaId() + ".");
            else Texts.send(player, "Bed positions were stored, but the snapshot refresh failed. Check console and try /varena bedsetup " + setup.arenaId() + " again after recovery finishes.");
        });
        if (!started) Texts.send(player, "Could not update the arena snapshot now. Finish any arena copy/recovery operation and repeat /varena bedsetup " + setup.arenaId() + ".");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreeperSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;
        Location at = creeper.getLocation();
        for (Player participant : Bukkit.getOnlinePlayers()) {
            MatchContext context = activeMatchContext(participant.getUniqueId());
            if (context == null || !context.onArena() || context.countdown() || context.finishing()
                    || !context.arena().contains(at)
                    || !kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.AUTO_CREEPER_IGNITION)) continue;
            int seconds = kitRulesManager.creeperIgnitionSeconds(context.kitId());
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!creeper.isValid() || creeper.isDead()) return;
                org.bukkit.attribute.AttributeInstance speed = creeper.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) speed.setBaseValue(0.0);
                creeper.setMaxFuseTicks(Math.max(1, seconds * 20));
                creeper.setFuseTicks(0);
                creeper.setIgnited(true);
            }, 1L);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpecialFireballExplode(EntityExplodeEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(specialProjectileKey, PersistentDataType.BYTE)) return;

        List<Block> vanillaExplosionBlocks = new ArrayList<>(event.blockList());
        event.blockList().clear();
        event.setYield(0.0f);

        Location origin = event.getEntity().getLocation().clone();
        World world = origin.getWorld();
        if (world == null) return;
        Vector projectileDirection = event.getEntity().getVelocity().clone().setY(0);
        Player shooter = event.getEntity() instanceof Fireball fireball && fireball.getShooter() instanceof Player player
                ? player : null;
        MatchContext context = shooter == null ? null : activeMatchContext(shooter.getUniqueId());
        Object matchIdentity = shooter == null ? null : activeMatchIdentity(shooter.getUniqueId());
        if (shooter != null && context != null && context.onArena() && !context.finishing()
                && !context.countdown() && context.arena().hasRegion()
                && origin.getWorld().getName().equals(context.arena().regionWorld())) {
            boolean mayBreakArenaBlocks = kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.DESTROY);
            List<Block> chosen = selectFireballThreeByTwoBlocks(vanillaExplosionBlocks, origin, projectileDirection, context, matchIdentity, mayBreakArenaBlocks, shooter.getUniqueId());
            event.blockList().addAll(chosen);
            if (!chosen.isEmpty()) {
                Bukkit.getScheduler().runTask(this, () -> {
                    for (Block block : chosen) {
                        if (block.getType().isAir()) placedBlocksByMatch.remove(blockKey(block.getLocation()));
                    }
                });
            }
        }

        double horizontalPower = getConfig().getDouble("special-items.fireball.knockback.horizontal", 3.5);
        double verticalPower = getConfig().getDouble("special-items.fireball.knockback.vertical", 4.5);
        // Apply after the native explosion so the configured velocity becomes the final knockback.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Entity nearby : world.getNearbyEntities(origin, 4.5, 4.5, 4.5)) {
                if (!(nearby instanceof LivingEntity living) || living.isDead() || !living.isValid()) continue;
                if (nearby.getLocation().distanceSquared(origin) > (4.5 * 4.5)) continue;
                Vector direction = living.getLocation().toVector().subtract(origin.toVector()).setY(0);
                if (direction.lengthSquared() < 1.0E-6) direction = projectileDirection.clone();
                if (direction.lengthSquared() < 1.0E-6) direction = new Vector(0, 0, 1);
                direction.normalize();
                living.setVelocity(new Vector(direction.getX() * horizontalPower, verticalPower,
                        direction.getZ() * horizontalPower));
            }
        }, 1L);
    }

    /** Selects a single wall-facing 3-wide by 2-high panel from the vanilla explosion candidates. */
    private List<Block> selectFireballThreeByTwoBlocks(List<Block> vanillaBlocks, Location origin, Vector direction,
                                                        MatchContext context, Object matchIdentity,
                                                        boolean mayBreakArenaBlocks, UUID shooterId) {
        if (vanillaBlocks.isEmpty() || origin.getWorld() == null || context == null || matchIdentity == null) return List.of();
        PartyMatch partyBedMatch = partyMatchManager == null ? null : partyMatchManager.getActiveMatch(shooterId);
        boolean partyBedfight = partyBedMatch == matchIdentity && isPartySplitBedfight(partyBedMatch);
        if (direction.lengthSquared() < 1.0E-6) {
            Player nearestShooter = null;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (activeMatchIdentity(player.getUniqueId()) == matchIdentity) { nearestShooter = player; break; }
            }
            if (nearestShooter != null) direction = nearestShooter.getEyeLocation().getDirection().setY(0);
        }
        if (direction.lengthSquared() < 1.0E-6) return List.of();
        direction.normalize();
        final Vector horizontalDirection = direction.clone();
        boolean planeOnX = Math.abs(horizontalDirection.getX()) >= Math.abs(horizontalDirection.getZ());

        Block planeBlock = null;
        double bestScore = Double.MAX_VALUE;
        for (Block candidate : vanillaBlocks) {
            if (candidate.getType().isAir() || !context.arena().contains(candidate.getLocation())) continue;
            Vector centerOffset = candidate.getLocation().add(0.5, 0.5, 0.5).toVector()
                    .subtract(origin.toVector());
            double depth = centerOffset.dot(horizontalDirection);
            double verticalDistance = Math.abs(candidate.getY() + 0.5 - origin.getY());
            double score = Math.abs(depth) + verticalDistance * 0.75 + (depth < -0.05 ? 0.35 : 0.0);
            if (score < bestScore) { bestScore = score; planeBlock = candidate; }
        }
        if (planeBlock == null) return List.of();

        int planeCoordinate = planeOnX ? planeBlock.getX() : planeBlock.getZ();
        int centerWidth = planeOnX ? planeBlock.getZ() : planeBlock.getX();
        int baseY = planeBlock.getY() - 1;
        Set<String> vanillaKeys = new HashSet<>();
        for (Block candidate : vanillaBlocks) vanillaKeys.add(blockKey(candidate.getLocation()));
        List<Block> selected = new ArrayList<>(6);
        for (int y = baseY; y <= baseY + 1; y++) {
            for (int offset = -1; offset <= 1; offset++) {
                int x = planeOnX ? planeCoordinate : centerWidth + offset;
                int z = planeOnX ? centerWidth + offset : planeCoordinate;
                Block candidate = origin.getWorld().getBlockAt(x, y, z);
                if (candidate.getType().isAir() || !context.arena().contains(candidate.getLocation())) continue;
                String key = blockKey(candidate.getLocation());
                if (!vanillaKeys.contains(key)) continue;
                Location bedFoot = bedFootLocation(candidate);
                if (partyBedfight) {
                    // Party Split Bedfight fireballs may destroy only blocks placed during this
                    // exact match. The configured beds and every other baseline/map block are
                    // protected even when this kit allows ordinary block destruction.
                    if (bedFoot != null && configuredPartyBedTeam(partyBedMatch, bedFoot) >= 0) continue;
                    PlacedBlockMarker marker = placedBlocksByMatch.get(key);
                    if (marker == null || marker.matchIdentity != matchIdentity) continue;
                    selected.add(candidate);
                    continue;
                }
                if (!mayBreakArenaBlocks) {
                    PlacedBlockMarker marker = placedBlocksByMatch.get(key);
                    if (marker == null || marker.matchIdentity != matchIdentity) continue;
                }
                selected.add(candidate);
            }
        }
        return selected;
    }

    private Object activeMatchIdentity(UUID uuid) {
        if (queueManager != null) {
            Match solo = queueManager.getMatch(uuid);
            if (solo != null) return solo;
        }
        return partyMatchManager == null ? null : partyMatchManager.getActiveMatch(uuid);
    }

    private String blockKey(Location location) {
        if (location == null || location.getWorld() == null) return "";
        return location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
    }

    private void pruneTrackedPlacedBlocks() {
        Set<Object> liveMatches = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player player : Bukkit.getOnlinePlayers()) {
            Object matchIdentity = activeMatchIdentity(player.getUniqueId());
            if (matchIdentity != null) liveMatches.add(matchIdentity);
        }
        placedBlocksByMatch.entrySet().removeIf(entry -> !liveMatches.contains(entry.getValue().matchIdentity));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectPartySplitBedfightBedsFromExplosions(EntityExplodeEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(specialProjectileKey, PersistentDataType.BYTE)
                || event.blockList().isEmpty()) return;
        UUID sourceId = explosionPlayerSource(event.getEntity());
        PartyMatch sourceMatch = sourceId == null || partyMatchManager == null
                ? null : partyMatchManager.getActiveMatch(sourceId);
        for (Block exploded : new ArrayList<>(event.blockList())) {
            Location bedFoot = bedFootLocation(exploded);
            if (bedFoot == null) continue;
            PartyMatch bedMatch = activePartyBedfightAt(exploded.getLocation());
            if (bedMatch == null) continue;
            int bedTeam = configuredPartyBedTeam(bedMatch, bedFoot);
            int sourceTeam = sourceMatch == bedMatch && sourceMatch.isAlive(sourceId)
                    ? sourceMatch.team(sourceId) : -1;
            // Without an attacker who belongs to this live match, do not let an environmental
            // or cross-match explosion destroy either configured bed. Otherwise only enemies'
            // configured beds are eligible; all own and unconfigured beds are protected.
            if (bedTeam < 0 || sourceTeam < 0 || bedTeam == sourceTeam) {
                event.blockList().remove(exploded);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedfightExplosionBreakBeds(EntityExplodeEvent event) {
        if (event.blockList().isEmpty()) return;
        for (Block exploded : new ArrayList<>(event.blockList())) {
            Location bedFoot = bedFootLocation(exploded);
            if (bedFoot == null) continue;
            if (queueManager != null) {
                for (Player participant : Bukkit.getOnlinePlayers()) {
                    Match match = queueManager.getMatch(participant.getUniqueId());
                    if (match == null || !match.bedfight() || !match.onArena() || match.finishing()
                            || !match.arena().contains(exploded.getLocation())) continue;
                    UUID owner = match.bedOwnerAt(bedFoot);
                    if (owner != null && match.destroyBed(owner)) notifyBedDestroyed(match, owner);
                }
            }

            // Ordinary explosions and the custom fireball both arrive here after their
            // block lists have been filtered to allow only a valid enemy-team bed.
            PartyMatch partyMatch = activePartyBedfightAt(exploded.getLocation());
            if (partyMatch != null) {
                int bedTeam = configuredPartyBedTeam(partyMatch, bedFoot);
                if (bedTeam >= 0) notifyPartySplitBedDestroyed(partyMatch, bedTeam);
            }
        }
    }

    /** Alerts every member of the team whose configured bed was destroyed, exactly once. */
    private void notifyPartySplitBedDestroyed(PartyMatch match, int bedTeam) {
        if (!isPartySplitBedfight(match) || !match.markBedDestroyed(bedTeam)) return;
        for (UUID memberId : match.members()) {
            if (match.isReturned(memberId) || match.team(memberId) != bedTeam) continue;
            Player teammate = Bukkit.getPlayer(memberId);
            if (teammate == null || !teammate.isOnline()) continue;
            teammate.showTitle(net.kyori.adventure.title.Title.title(
                    Texts.component("&c&lBed Destroyed"),
                    Texts.component("&7Your team's bed has been destroyed!")));
            teammate.playSound(teammate.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_AMBIENT, 1.0f, 1.0f);
        }

        // BedBreak/Explosion events fire before the world updates the block state. Refresh
        // the scoreboard one tick later so the bed placeholder changes to the broken mark.
        Bukkit.getScheduler().runTask(this, () -> {
            if (playerPreferencesManager == null) return;
            for (UUID memberId : match.members()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member != null && member.isOnline() && !match.isReturned(memberId)) {
                    playerPreferencesManager.refreshScoreboard(member);
                }
            }
        });
    }

    private void notifyBedDestroyed(Match match, UUID owner) {
        Player bedOwner = Bukkit.getPlayer(owner);
        if (bedOwner != null && bedOwner.isOnline()) {
            bedOwner.showTitle(net.kyori.adventure.title.Title.title(
                    Texts.component("&c&lBed Destroyed"),
                    Texts.component("&7You will no longer be able to respawn!")));
            bedOwner.playSound(bedOwner.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_AMBIENT, 1.0f, 1.0f);
        }
        if (playerPreferencesManager != null) {
            Player first = Bukkit.getPlayer(match.first());
            Player second = Bukkit.getPlayer(match.second());
            if (first != null) playerPreferencesManager.refreshScoreboard(first);
            if (second != null) playerPreferencesManager.refreshScoreboard(second);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBedfightVoidMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Standard solo Bedfight uses Match; Party Split Bedfight has a separate PartyMatch
        // lifecycle, so check both or players can walk through the configured void region alive.
        Match match = queueManager == null ? null : queueManager.getMatch(playerId);
        if (match != null) {
            if (!match.bedfight() || !match.onArena() || match.countdown() || match.finishing()
                    || match.roundTransitioning() || player.isDead()
                    || player.getGameMode() == GameMode.SPECTATOR) return;
            if (match.arena().containsBedVoid(to)) player.setHealth(0.0);
            return;
        }

        PartyMatch partyMatch = partyMatchManager == null ? null : partyMatchManager.getActiveMatch(playerId);
        if (!isPartySplitBedfight(partyMatch) || !partyMatch.onArena() || partyMatch.countdown()
                || partyMatch.finishing() || partyMatch.roundTransitioning() || !partyMatch.isAlive(playerId)
                || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return;
        if (partyMatch.arena().containsBedVoid(to)) player.setHealth(0.0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MatchContext context = activeMatchContext(player.getUniqueId());
        if (context != null && (context.countdown() || context.finishing()
                || !kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.CRAFTING))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHealthRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        MatchContext context = activeMatchContext(player.getUniqueId());
        if (context == null || kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.HEALTH_REGEN)) return;
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED
                || reason == EntityRegainHealthEvent.RegainReason.REGEN) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCountdownMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        MatchContext context = activeMatchContext(uuid);
        if (context == null || !context.countdown() || context.finishing() || context.roundTransitioning() || !context.onArena()) return;
        // Participants may move freely during the three-second Match Found delay in the lobby.
        // Movement is frozen only after the arena teleport, during the 3-2-1 countdown.
        if (event instanceof PlayerTeleportEvent && context.arenaTeleportInProgress()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location frozen = from.clone();
            frozen.setYaw(to.getYaw());
            frozen.setPitch(to.getPitch());
            event.setTo(frozen);
            event.getPlayer().setVelocity(new Vector(0, 0, 0));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        MatchContext context = activeMatchContext(player.getUniqueId());
        if (context == null || !context.onArena() || context.countdown() || context.finishing()) return;

        if (!kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.UNLIMITED_HUNGER)) {
            // PEACEFUL difficulty normally disables hunger loss. Prevent peaceful's automatic
            // food refill in that case; food-item events remain allowed.
            if (player.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
                if (event.getItem() == null && event.getFoodLevel() > player.getFoodLevel()) {
                    event.setCancelled(true);
                }
            } else {
                // The kit rule explicitly allows normal hunger even if another listener cancelled
                // the event before NexPractice saw it.
                event.setCancelled(false);
            }
            return;
        }

        // Unlimited hunger alone suppresses food changes and actively restores full hunger.
        event.setCancelled(true);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setExhaustion(0.0f);
    }

    /** Applies the configured hunger mode for a player in an active arena match. */
    public void maintainKitHunger(Player player, String kitId) {
        if (player == null || !player.isOnline() || kitId == null) return;
        if (kitRulesManager.enabled(kitId, KitRulesManager.Rule.UNLIMITED_HUNGER)) {
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setExhaustion(0.0f);
            return;
        }

        // On Easy/Normal/Hard, never touch hunger while the rule is off: vanilla handles it.
        // In Peaceful, Minecraft refills food and disables normal hunger drain. Keep saturation
        // at zero and spend accumulated activity exhaustion directly so this per-kit rule still
        // works without changing the entire world's difficulty for everyone else.
        if (player.getWorld().getDifficulty() != org.bukkit.Difficulty.PEACEFUL) return;
        if (player.getSaturation() > 0.0f) player.setSaturation(0.0f);
        float exhaustion = player.getExhaustion();
        if (exhaustion < 4.0f) return;
        int food = player.getFoodLevel();
        while (exhaustion >= 4.0f) {
            exhaustion -= 4.0f;
            if (food > 0) food--;
        }
        player.setExhaustion(exhaustion);
        if (player.getFoodLevel() != food) player.setFoodLevel(food);
    }

    /**
     * Explosion damage is allowed for active arena fighters. Countdown protection and
     * spectator protection remain in force; this clears stale invulnerability and any
     * explosion cancellation applied before this event priority.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArenaExplosionDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        UUID uuid = player.getUniqueId();
        MatchContext context = activeMatchContext(uuid);
        if (context == null || !context.onArena() || context.countdown() || context.finishing()) return;
        if (matchSpectatorManager.isSpectating(uuid) || partyMatchManager.isEliminatedSpectator(uuid)) return;
        // NexPractice's countdown invulnerability must never suppress crystals, beds, anchors, or TNT
        // once active combat has begun. Reset no-damage ticks for this explosion only.
        if (player.isInvulnerable()) player.setInvulnerable(false);
        player.setNoDamageTicks(0);
        if (event.isCancelled()) event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKitFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        MatchContext context = activeMatchContext(player.getUniqueId());
        if (context == null || !context.onArena()) return;
        if (!kitRulesManager.enabled(context.kitId(), KitRulesManager.Rule.FALL_DAMAGE)) {
            event.setCancelled(true);
            player.setFallDistance(0.0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackCreeperDamageCredit(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();
        creeperHitCredits.entrySet().removeIf(entry -> now - entry.getValue().atMillis() > 30_000L);
        creeperVictimCredits.entrySet().removeIf(entry -> now - entry.getValue().atMillis() > 30_000L);

        // Record attribution on the victim when the explosion lands. This is more reliable than
        // relying only on Player#getLastDamageCause(), which can be represented as a generic
        // ENTITY_EXPLOSION event depending on the server damage path.
        if (event.getEntity() instanceof Player victim && event.getDamager() instanceof Creeper creeper) {
            CreeperHitCredit pushedBy = creeperHitCredits.get(creeper.getUniqueId());
            if (pushedBy != null && now - pushedBy.atMillis() <= 30_000L) {
                UUID victimId = victim.getUniqueId();
                CreeperHitCredit victimCredit = new CreeperHitCredit(pushedBy.playerId(), now);
                creeperVictimCredits.put(victimId, victimCredit);
                Bukkit.getScheduler().runTaskLater(this, () -> creeperVictimCredits.remove(victimId, victimCredit), 205L);
            }
            return;
        }

        if (!(event.getEntity() instanceof Creeper creeper)) return;
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker == null) return;
        UUID creeperId = creeper.getUniqueId();
        CreeperHitCredit credit = new CreeperHitCredit(attacker.getUniqueId(), now);
        creeperHitCredits.put(creeperId, credit);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            CreeperHitCredit current = creeperHitCredits.get(creeperId);
            if (current != null && System.currentTimeMillis() - current.atMillis() > 30_000L) {
                creeperHitCredits.remove(creeperId, current);
            }
        }, 620L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackCreeperExplosion(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) return;
        long now = System.currentTimeMillis();
        CreeperHitCredit hit = creeperHitCredits.get(creeper.getUniqueId());
        if (hit == null || now - hit.atMillis() > 30_000L) return;
        UUID creeperId = creeper.getUniqueId();
        CreeperExplosionCredit explosion = new CreeperExplosionCredit(hit.playerId(), event.getLocation().clone(), now);
        creeperExplosionCredits.put(creeperId, explosion);
        Bukkit.getScheduler().runTaskLater(this, () -> creeperExplosionCredits.remove(creeperId, explosion), 60L);
    }

    /** Fallback for Paper damage paths where a Creeper explosion is reported as plain EntityDamageEvent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackGenericCreeperExplosionDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || event instanceof EntityDamageByEntityEvent
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        long now = System.currentTimeMillis();
        Location victimLocation = victim.getLocation();
        CreeperExplosionCredit closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (CreeperExplosionCredit explosion : creeperExplosionCredits.values()) {
            if (now - explosion.atMillis() > 2_000L || explosion.location().getWorld() != victimLocation.getWorld()) continue;
            double distance = explosion.location().distanceSquared(victimLocation);
            if (distance <= 36.0 && distance < closestDistance) {
                closest = explosion;
                closestDistance = distance;
            }
        }
        if (closest == null) return;
        UUID victimId = victim.getUniqueId();
        CreeperHitCredit victimCredit = new CreeperHitCredit(closest.playerId(), now);
        creeperVictimCredits.put(victimId, victimCredit);
        Bukkit.getScheduler().runTaskLater(this, () -> creeperVictimCredits.remove(victimId, victimCredit), 205L);
    }

    /** Non-consuming lookup used by the match-info death message before the match manager records the kill. */
    public UUID peekCreeperKillCredit(Player dead, Set<UUID> eligibleOpponents) {
        return creeperKillCredit(dead, eligibleOpponents, false);
    }

    /** Credits a Creeper explosion kill only to a recent player attacker who is a valid opponent. */
    public UUID resolveCreeperKillCredit(Player dead, UUID expectedOpponent) {
        return expectedOpponent == null ? null : resolveCreeperKillCredit(dead, Set.of(expectedOpponent));
    }

    public UUID resolveCreeperKillCredit(Player dead, Set<UUID> eligibleOpponents) {
        return creeperKillCredit(dead, eligibleOpponents, true);
    }

    private UUID creeperKillCredit(Player dead, Set<UUID> eligibleOpponents, boolean consume) {
        if (dead == null || eligibleOpponents == null || eligibleOpponents.isEmpty()) return null;
        EntityDamageEvent lastDamage = dead.getLastDamageCause();
        if (lastDamage == null) return null;
        EntityDamageEvent.DamageCause cause = lastDamage.getCause();
        // Creeper knockback commonly kills indirectly: the explosion launches the victim and
        // the final damage is FALL or VOID, not ENTITY_EXPLOSION. Only use those causes when
        // a recent Creeper hit/explosion was explicitly recorded for this same victim.
        boolean eligibleFinalCause = cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.FALL
                || cause == EntityDamageEvent.DamageCause.VOID;
        if (!eligibleFinalCause) return null;
        if (lastDamage instanceof EntityDamageByEntityEvent byEntity && !(byEntity.getDamager() instanceof Creeper)) return null;

        long now = System.currentTimeMillis();
        CreeperHitCredit victimCredit = creeperVictimCredits.get(dead.getUniqueId());
        if (victimCredit != null) {
            boolean fresh = now - victimCredit.atMillis() <= 10_000L;
            boolean eligible = eligibleOpponents.contains(victimCredit.playerId());
            if (consume) creeperVictimCredits.remove(dead.getUniqueId(), victimCredit);
            if (fresh && eligible) return victimCredit.playerId();
        }

        // Fallback for Paper versions where the final explosion event retains the Creeper damager.
        if (lastDamage instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Creeper creeper) {
            CreeperHitCredit credit = creeperHitCredits.get(creeper.getUniqueId());
            if (credit == null || now - credit.atMillis() > 30_000L
                    || !eligibleOpponents.contains(credit.playerId())) return null;
            if (consume) creeperHitCredits.remove(creeper.getUniqueId(), credit);
            return credit.playerId();
        }
        return null;
    }

    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }

    /**
     * Against Creepers only, swords do not reset the player's attack cooldown after each hit.
     * This gives the effective rapid mob-hit behavior requested without changing the sword's
     * attack-speed attribute, altering PvP, or replacing vanilla knockback with a custom impulse.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreeperSwordCooldownReset(PlayerAttackEntityCooldownResetEvent event) {
        if (!(event.getAttackedEntity() instanceof Creeper)) return;
        ItemStack weapon = event.getPlayer().getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()
                || !weapon.getType().name().endsWith("_SWORD")) return;
        // Paper documents that cancelling this event keeps the attack strength from being reset.
        // Leave the pushed-entity vector alone so normal vanilla knockback applies to the Creeper.
        event.setCancelled(true);
    }

    /** Enforces asymmetric match visibility as well as match isolation and Party Split friendly-fire. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorCombat(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayerDamager(event.getDamager());
        Player target = event.getEntity() instanceof Player p ? p : null;
        if (attacker != null) {
            UUID attackerId = attacker.getUniqueId();
            if (matchSpectatorManager.isSpectating(attackerId) || partyMatchManager.isEliminatedSpectator(attackerId)) {
                event.setCancelled(true);
                return;
            }
        }
        if (target == null) return;
        UUID targetId = target.getUniqueId();
        if (matchSpectatorManager.isSpectating(targetId) || partyMatchManager.isEliminatedSpectator(targetId)) {
            event.setCancelled(true);
            return;
        }
        if (attacker == null) return;
        Object attackerMatch = activeMatchToken(attacker.getUniqueId());
        Object targetMatch = activeMatchToken(targetId);
        if (attackerMatch != null || targetMatch != null) {
            // A fighter can only damage a player in that exact match. This also blocks lobby/outside
            // players and projectiles from damaging match participants while leaving visibility asymmetric.
            if (attackerMatch == null || targetMatch == null || attackerMatch != targetMatch) {
                event.setCancelled(true);
                return;
            }
            if (attackerMatch instanceof PartyMatch partyMatch && partyMatch.mode() == PartyMatch.Mode.SPLIT
                    && partyMatch.team(attacker.getUniqueId()) == partyMatch.team(targetId)) {
                event.setCancelled(true);
            }
        }
    }

    private Object activeMatchToken(UUID uuid) {
        Match solo = queueManager == null ? null : queueManager.getMatch(uuid);
        if (solo != null) return solo;
        return partyMatchManager == null ? null : partyMatchManager.getActiveMatch(uuid);
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "vkitaddingmode" -> handleKitAddingMode(sender, args);
            case "vaddkit" -> handleAddKit(sender, args);
            case "vaddcoins" -> handleVAddCoins(sender, args);
            case "vgive" -> handleVGive(sender, args);
            case "msg" -> handlePrivateMessage(sender, args, false);
            case "r" -> handlePrivateMessage(sender, args, true);
            case "pc" -> handlePartyChat(sender, args);
            case "varena" -> handleArenaCommand(sender, args);
            case "varenamenu" -> arenaMenuManager.handleCommand(sender, args);
            case "vsetarenaborder" -> handleSetArenaBorderCommand(sender, args);
            case "vsetlobby" -> handleSetLobbyCommand(sender);
            case "vkitsettings" -> handleKitSettingsCommand(sender, args);
            case "vparty" -> handlePartyCommand(sender, args);
            case "party" -> handleRootPartyCommand(sender, args);
            case "vhg" -> handleHologramCommand(sender, args);
            case "duel" -> handleDuelCommand(sender, args);
            case "spec" -> handleSpecCommand(sender, args);
            case "vmatchview" -> matchReportManager.handleViewCommand(sender, args);
            case "vscoreboard" -> handleScoreboardCommand(sender, args);
            case "vtournament" -> tournamentManager != null && tournamentManager.handleCommand(sender, args);
            case "ranks" -> handleRanksCommand(sender);
            case "leave" -> handleLeaveCommand(sender);
            default -> false;
        };
    }

    private boolean handleLeaveCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "&cOnly players can use /leave.");
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (queueManager.forfeit(player)) return true;
        PartyMatch currentPartyMatch = partyMatchManager == null ? null : partyMatchManager.getMatch(uuid);
        if (currentPartyMatch != null && !currentPartyMatch.isReturned(uuid)
                && currentPartyMatch.isEliminated(uuid) && tournamentManager != null && tournamentManager.isRegistered(uuid)) {
            Texts.send(player, "&cYou must remain spectating until your tournament match finishes.");
            return true;
        }
        if (partyMatchManager.forfeit(player)) return true;
        if (tournamentManager != null && tournamentManager.isRegistered(uuid)) {
            tournamentManager.leaveTournament(player, false);
            return true;
        }
        if (!queueManager.getPlayerQueues(uuid).isEmpty()) {
            queueManager.removeFromAllQueues(uuid);
            Texts.send(player, "&7You left the queue.");
            return true;
        }
        Texts.send(player, "&cYou are not in a match or queue.");
        return true;
    }

    private boolean handleRanksCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "Only players can open the ranks menu.");
            return true;
        }
        if (leaderboardManager != null) leaderboardManager.openRanks(player);
        return true;
    }

    private boolean handlePartyChat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "&cOnly players can use party chat.");
            return true;
        }
        if (partyManager == null || partyManager.getParty(player.getUniqueId()) == null) {
            if (partyManager != null) partyManager.sendPartyChat(player, args.length == 0 ? "" : String.join(" ", args));
            else Texts.send(player, "&cYou are not in a party");
            return true;
        }
        if (args.length == 0) {
            Texts.send(player, "&c/pc <msg>");
            return true;
        }
        partyManager.sendPartyChat(player, String.join(" ", args));
        return true;
    }

    /** Treat messages beginning with "@ " as party chat, without exposing them in global chat. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPartyChatShortcut(AsyncPlayerChatEvent event) {
        String raw = event.getMessage();
        if (raw == null || !raw.startsWith("@ ")) return;
        String message = raw.substring(2).trim();
        event.setCancelled(true);
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            if (message.isEmpty()) {
                Texts.send(player, "&c@ <msg>");
                return;
            }
            if (partyManager != null) partyManager.sendPartyChat(player, message);
        });
    }

    private boolean handlePrivateMessage(CommandSender sender, String[] args, boolean reply) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, reply && args.length == 0 ? "&c/msg <name> <msg>"
                    : "&cOnly players can use private messages.");
            return true;
        }

        if (args.length == 0 || (!reply && args.length < 2)) {
            Texts.send(player, "&c/msg <name> <msg>");
            return true;
        }

        Player target;
        String message;
        if (reply) {
            UUID targetId = lastMessageFrom.get(player.getUniqueId());
            if (targetId == null) {
                Texts.send(player, "&cYou have nobody to whom you can reply.");
                return true;
            }
            target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                Texts.send(player, "&cThat player is no longer online.");
                return true;
            }
            message = String.join(" ", args);
        } else {
            target = Bukkit.getOnlinePlayers().stream()
                    .filter(online -> online.getName().equalsIgnoreCase(args[0]))
                    .findFirst().orElse(null);
            if (target == null) {
                Texts.send(player, "&cThat player is not online.");
                return true;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                Texts.send(player, "&cYou cannot message yourself.");
                return true;
            }
            message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Texts.send(player, "&cYou cannot message yourself.");
            return true;
        }
        if (playerPreferencesManager != null
                && !playerPreferencesManager.arePrivateMessagesAllowed(target.getUniqueId())) {
            Texts.send(player, "&cThat player has private messages disabled.");
            return true;
        }

        Texts.send(player, "&d[me -> " + target.getName() + "] &f" + message);
        Texts.send(target, "&d[" + player.getName() + " -> me] &f" + message);
        lastMessageFrom.put(target.getUniqueId(), player.getUniqueId());
        return true;
    }

    private boolean handleScoreboardCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vprac.admin.scoreboard")) {
            Texts.send(sender, "You do not have permission to reload the scoreboard configuration.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            Texts.send(sender, "Usage: /vscoreboard reload");
            return true;
        }

        mergeCosmeticDefaultsIntoExistingConfig();
        mergeTournamentDefaultsIntoExistingConfig();
        mergePartyDefaultsIntoExistingConfig();
        mergeSettingsDefaultsIntoExistingConfig();
        mergeRespawnDefaultsIntoExistingConfig();
        mergeConfigSectionFromDefaults("leaderboards");
        reloadConfig();
        applyImmediateRespawnRuleToLoadedWorlds();
        if (playerPreferencesManager != null) playerPreferencesManager.reloadScoreboardSettings();
        if (cosmeticsManager != null) cosmeticsManager.reloadOptions();
        Texts.send(sender, "Scoreboard and cosmetic configuration reloaded. Updated layouts and cosmetic options are now active.");
        return true;
    }

    private boolean handleDuelCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return duelManager.handleChatResponse(player, args[0], args[1]);
        }
        if (args.length != 1) {
            Texts.send(player, "Usage: /duel <player> or /duel <accept|deny> <request-id>");
            return true;
        }
        Player target = Bukkit.getOnlinePlayers().stream()
                .filter(online -> online.getName().equalsIgnoreCase(args[0]))
                .findFirst().orElse(null);
        if (target == null) {
            Texts.send(player, "That player is not online.");
            return true;
        }
        duelManager.openKitSelectionMenu(player, target);
        return true;
    }

    private boolean handleSpecCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        if (args.length != 1) {
            Texts.send(player, "Usage: /spec <player>");
            return true;
        }
        // Waiting tournament entrants must not spectate before they have been eliminated;
        // doing so would invalidate their bracket entry and could strand the tournament.
        if (tournamentManager != null && tournamentManager.isRegistered(player.getUniqueId())) {
            Texts.send(player, getConfig().getString("tournaments.messages.unable-state", "&6unable to execute this in ur current state"));
            return true;
        }
        Player target = Bukkit.getOnlinePlayers().stream()
                .filter(online -> online.getName().equalsIgnoreCase(args[0]))
                .findFirst().orElse(null);
        if (target == null) {
            Texts.send(player, "That player is not online.");
            return true;
        }
        matchSpectatorManager.startSpectating(player, target);
        return true;
    }

    private boolean handleRootPartyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "&cParty commands can only be used by a player.");
            return true;
        }
        if (args.length == 0) {
            sendRootPartyHelp(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "invite" -> {
                if (args.length != 2) Texts.send(player, "&c/party invite <name>");
                else partyManager.sendInvite(player, args[1]);
            }
            case "chat" -> {
                if (args.length < 2) Texts.send(player, "&c/party chat <msg>");
                else partyManager.sendPartyChat(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "kick" -> {
                if (args.length != 2) {
                    Texts.send(player, "&c/party kick <name>");
                    break;
                }
                Player target = Bukkit.getOnlinePlayers().stream()
                        .filter(online -> online.getName().equalsIgnoreCase(args[1]))
                        .findFirst().orElse(null);
                if (target == null) Texts.send(player, "&cPlayer " + args[1] + " is not online.");
                else partyManager.kick(player, target.getUniqueId());
            }
            case "transfer" -> {
                if (args.length != 2) {
                    Texts.send(player, "&c/party transfer <name>");
                    break;
                }
                Player target = Bukkit.getOnlinePlayers().stream()
                        .filter(online -> online.getName().equalsIgnoreCase(args[1]))
                        .findFirst().orElse(null);
                if (target == null) Texts.send(player, "&cPlayer " + args[1] + " is not online.");
                else partyManager.transferLeadership(player, target.getUniqueId());
            }
            case "disband" -> {
                if (args.length != 1) Texts.send(player, "&c/party disband &7(if leader)");
                else partyManager.disbandParty(player);
            }
            case "leave" -> {
                if (args.length != 1) Texts.send(player, "&c/party leave");
                else partyManager.leave(player);
            }
            default -> sendRootPartyHelp(player);
        }
        return true;
    }

    private void sendRootPartyHelp(Player player) {
        Texts.send(player, "&c/party invite <name>");
        Texts.send(player, "&c/party chat <msg>");
        Texts.send(player, "&c/party kick <name>");
        Texts.send(player, "&c/party transfer <name>");
        Texts.send(player, "&c/party disband &7(if leader)");
        Texts.send(player, "&c/party leave");
    }

    private boolean handleHologramCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vprac.admin.holograms")) {
            Texts.send(sender, "&cYou do not have permission to use /vhg.");
            return true;
        }
        if (leaderboardHologramManager == null) {
            Texts.send(sender, "&cFancyHolograms is not installed. Install it to enable NexPractice leaderboard holograms.");
            return true;
        }
        return leaderboardHologramManager.handleCommand(sender, args);
    }

    private boolean handlePartyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "Party commands can only be used by a player.");
            return true;
        }
        return partyManager.handleCommand(player, args);
    }

    private boolean handleKitSettingsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        if (args.length > 1) {
            Texts.send(player, "Usage: /vkitsettings [kit]");
            return true;
        }
        if (args.length == 1) {
            String kitId = args[0].toLowerCase(Locale.ROOT);
            if (!kitManager.exists(kitId)) {
                Texts.send(player, "Kit not found. Run /vkitsettings to choose from all kits.");
                return true;
            }
            openKitRulesMenu(player, kitId);
        } else openAdminKitSelectionMenu(player, 0);
        return true;
    }

    private boolean handleVAddCoins(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vprac.admin.coins")) {
            Texts.send(sender, "&cYou do not have permission to use /vaddcoins.");
            return true;
        }
        if (args.length != 2) {
            Texts.send(sender, "&cUsage: /vaddcoins <name> <amount>");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            amount = -1;
        }
        if (amount < 1) {
            Texts.send(sender, "&cAmount must be a positive whole number.");
            return true;
        }

        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            Texts.send(sender, "&cThat player was not found. They must have joined this server before.");
            return true;
        }

        int balance = coinsManager.addCoins(target.getUniqueId(), amount);
        String targetName = target.getName() == null ? args[0] : target.getName();
        Texts.send(sender, "&aAdded &e" + amount + " ⛃ &ato &b" + targetName
                + "&a. New balance: &e" + balance + " ⛃&a.");
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null && onlineTarget.isOnline()) {
            Texts.send(onlineTarget, "&aYou received &e" + amount + " ⛃ &afrom an administrator.");
        }
        return true;
    }

    private boolean handleVGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vprac.admin.give")) {
            Texts.send(sender, "You do not have permission to use /vgive.");
            return true;
        }
        if (args.length != 3) {
            Texts.send(sender, "Usage: /vgive <player> <fireball|goldenhead> <amount>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            Texts.send(sender, "That player is not online.");
            return true;
        }
        String type = args[1].toLowerCase(Locale.ROOT).replace('-', '_');
        if (type.equals("goldenhead") || type.equals("golden_head")) type = "golden_head";
        if (!type.equals("fireball") && !type.equals("golden_head")) {
            Texts.send(sender, "Unknown item. Use 'fireball' or 'goldenhead'.");
            return true;
        }
        int amount;
        try { amount = Integer.parseInt(args[2]); }
        catch (NumberFormatException exception) { amount = -1; }
        if (amount < 1 || amount > 64) {
            Texts.send(sender, "Amount must be a whole number from 1 to 64.");
            return true;
        }
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createSpecialItem(type, amount));
        for (ItemStack leftover : leftovers.values()) target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        Texts.send(sender, "Gave &e" + amount + " " + (type.equals("fireball") ? "Fireball" : "Golden Head") + "&r to &b" + target.getName() + "&r.");
        return true;
    }

    private boolean handleKitAddingMode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (queueManager.isInMatch(uuid)) {
            Texts.send(player, "You cannot enter kit creation mode during a match.");
            return true;
        }
        if (kitModeStates.containsKey(uuid)) {
            Texts.send(player, "You are already in kit creation mode. Use /vaddkit <name> <icon> to save it.");
            return true;
        }

        queueManager.removeFromAllQueues(uuid);
        PlayerInventory inventory = player.getInventory();
        KitModeState state = new KitModeState(player.getGameMode(), cloneItems(inventory.getStorageContents()),
                cloneItems(inventory.getArmorContents()), inventory.getItemInOffHand() == null ? null : inventory.getItemInOffHand().clone(),
                inventory.getHeldItemSlot(), player.getFoodLevel(), player.getSaturation());
        kitModeStates.put(uuid, state);
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        player.setGameMode(GameMode.CREATIVE);
        Texts.send(player, "Kit creation mode enabled. Build the kit in your inventory, then run /vaddkit <name> <icon>.");
        return true;
    }

    private boolean handleAddKit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        if (args.length != 2) {
            Texts.send(player, "Usage: /vaddkit <name> <icon> (example: /vaddkit hello minecraft:mace)");
            return true;
        }
        KitModeState state = kitModeStates.get(player.getUniqueId());
        if (state == null) {
            Texts.send(player, "Run /vkitaddingmode before adding a kit.");
            return true;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (!kitManager.isValidId(id) || KitManager.SWORD_ID.equals(id)) {
            Texts.send(player, "Use 1-32 letters, numbers, underscores or hyphens. 'sword' is reserved.");
            return true;
        }
        Material icon = kitManager.parseMaterial(args[1]);
        if (icon == null || icon.isAir()) {
            Texts.send(player, "Invalid icon. Use a Minecraft item ID, for example minecraft:mace or DIAMOND_SWORD.");
            return true;
        }
        boolean hasItem = Arrays.stream(player.getInventory().getStorageContents())
                .anyMatch(item -> item != null && !item.getType().isAir())
                || Arrays.stream(player.getInventory().getArmorContents())
                .anyMatch(item -> item != null && !item.getType().isAir())
                || (player.getInventory().getItemInOffHand() != null && !player.getInventory().getItemInOffHand().getType().isAir());
        if (!hasItem) {
            Texts.send(player, "The kit is empty. Add at least one item before saving it.");
            return true;
        }

        try {
            if (!kitManager.saveKit(id, icon, player.getInventory())) {
                Texts.send(player, "That kit name or icon cannot be saved.");
                return true;
            }
            kitModeStates.remove(player.getUniqueId());
            restoreKitMode(player, state);
            queueManager.onKitAdded(id);
            Texts.send(player, "Saved kit '&b" + id + "&r' with icon '&b" + icon.getKey() + "&r'. It is now available in the queue dialog and Edit Kit menu.");
        } catch (IOException exception) {
            getLogger().severe("Failed to save kit " + id + ": " + exception.getMessage());
            Texts.send(player, "Could not save the kit. Check the server console.");
        }
        return true;
    }

    private void restoreKitMode(Player player, KitModeState state) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setStorageContents(cloneItems(state.contents()));
        inventory.setArmorContents(cloneItems(state.armor()));
        inventory.setItemInOffHand(state.offhand() == null ? new ItemStack(Material.AIR) : state.offhand().clone());
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, state.heldSlot())));
        player.setGameMode(state.gameMode());
        player.setFoodLevel(state.foodLevel());
        player.setSaturation(state.saturation());
    }

    private ItemStack[] cloneItems(ItemStack[] input) {
        ItemStack[] output = new ItemStack[input.length];
        for (int i = 0; i < input.length; i++) output[i] = input[i] == null ? null : input[i].clone();
        return output;
    }

    private boolean handleArenaCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Texts.send(sender, "Usage: /varena <create|setspawn|enable|disable|list|partyffa|bedsetup|bedsetvoid|enablebedfight> [arguments]");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length != 2) { Texts.send(sender, "Usage: /varena create <name>"); return true; }
                if (!arenaManager.create(args[1])) Texts.send(sender, "Arena could not be created. Check the name or whether it already exists.");
                else Texts.send(sender, "Created arena '&b" + args[1].toLowerCase(Locale.ROOT) + "&r'. Next run /vsetarenaborder start " + args[1] + ".");
            }
            case "setspawn" -> {
                if (!(sender instanceof Player player)) { Texts.send(sender, "This command can only be used by a player."); return true; }
                if (args.length != 3 || !(args[2].equals("1") || args[2].equals("2"))) {
                    Texts.send(player, "Usage: /varena setspawn <name> <1|2>"); return true;
                }
                int point = Integer.parseInt(args[2]);
                if (!arenaManager.setSpawn(args[1], point, player.getLocation())) {
                    Texts.send(player, "Could not set or save the spawn. Save the arena border, stand inside it in the correct world, and ensure no copy, snapshot, delete, or recovery operation is running; check console if the save failed.");
                } else {
                    Texts.send(player, "Set spawn " + point + " for arena '&b" + args[1] + "&r'. The arena remains disabled until enabled.");
                }
            }
            case "enable" -> {
                if (args.length != 2) { Texts.send(sender, "Usage: /varena enable <name>"); return true; }
                if (!arenaManager.setEnabled(args[1], true)) {
                    String reason = arenaManager.enableFailureReason(args[1]);
                    if (reason == null) reason = "the arena settings could not be saved to plugins/NexPractice/arenas.yml; check the console and file permissions";
                    Texts.send(sender, "Arena not enabled: " + reason + ". Unsafe Party FFA spawn spots only make the arena ineligible for Party FFA queues.");
                } else {
                    Texts.send(sender, "Enabled arena '&b" + args[1] + "&r'.");
                    queueManager.tryMatchAll();
                }
            }
            case "disable" -> {
                if (args.length == 3 && args[1].equalsIgnoreCase("bedfight")) {
                    String arenaId = args[2];
                    if (!arenaManager.setBedfightEnabled(arenaId, false)) Texts.send(sender, "Could not disable Bedfight. Ensure the arena exists and is not in use or being edited.");
                    else Texts.send(sender, "Bedfight disabled for arena '&b" + arenaId + "&r'. The arena's ordinary queue eligibility is unchanged.");
                    return true;
                }
                if (args.length != 2) { Texts.send(sender, "Usage: /varena disable <name> or /varena disable bedfight <name>"); return true; }
                if (arenaManager.isInUse(args[1])) Texts.send(sender, "That arena is currently hosting a match; wait until the match finishes.");
                else if (!arenaManager.setEnabled(args[1], false)) Texts.send(sender, "Arena not found.");
                else Texts.send(sender, "Disabled arena '&b" + args[1] + "&r'.");
            }
            case "enablebedfight" -> {
                if (args.length != 2) { Texts.send(sender, "Usage: /varena enablebedfight <arena>"); return true; }
                if (!arenaManager.setBedfightEnabled(args[1], true)) {
                    String reason = arenaManager.bedfightFailureReason(args[1]);
                    Texts.send(sender, "Could not enable Bedfight" + (reason == null ? ". Check the arena file and console." : ": " + reason + "."));
                } else {
                    Texts.send(sender, "Bedfight enabled for '&b" + args[1] + "&r'. Only solo queue matches may use it.");
                    queueManager.tryMatchAll();
                }
            }
            case "bedsetup" -> {
                if (!(sender instanceof Player player)) { Texts.send(sender, "This command can only be used by a player."); return true; }
                if (args.length != 2) { Texts.send(player, "Usage: /varena bedsetup <arena>"); return true; }
                ArenaData arena = arenaManager.get(args[1]);
                if (arena == null) { Texts.send(player, "Arena not found."); return true; }
                if (!arena.hasRegion() || !arena.ready() || arenaManager.isInUse(arena.id()) || arenaManager.isCapturing(arena.id())
                        || arenaManager.copiesBusy(arena.id()) || arenaManager.isRecovering(arena.id())) {
                    Texts.send(player, "Bed setup requires a saved arena border, both standard spawns, and no active copy/recovery/match operation."); return true;
                }
                if (player.getInventory().firstEmpty() == -1) { Texts.send(player, "Free one inventory slot before beginning Bedfight bed setup."); return true; }
                bedSetupStates.remove(player.getUniqueId());
                removeBedSetupItems(player);
                BedSetupState setup = new BedSetupState(arena.id());
                bedSetupStates.put(player.getUniqueId(), setup);
                player.getInventory().addItem(createBedSetupItem());
                Texts.send(player, "Bedfight setup started for '&b" + arena.id() + "&r'. Place bed A, then bed B, inside the saved arena border.");
            }
            case "bedsetvoid" -> {
                if (!(sender instanceof Player player)) { Texts.send(sender, "This command can only be used by a player."); return true; }
                if (args.length != 2) { Texts.send(player, "Usage: /varena bedsetvoid <arena>"); return true; }
                ArenaData arena = arenaManager.get(args[1]);
                if (arena == null || !arena.hasRegion()) { Texts.send(player, "Save the arena border before setting its Bedfight void."); return true; }
                if (arenaManager.isInUse(arena.id()) || arenaManager.isRecovering(arena.id()) || arenaManager.copiesBusy(arena.id())) {
                    Texts.send(player, "Cannot configure the void while this arena is in use or being recovered."); return true;
                }
                if (player.getInventory().firstEmpty() == -1) { Texts.send(player, "Free one inventory slot before starting void selection."); return true; }
                removeBedVoidWands(player);
                bedVoidSetupStates.put(player.getUniqueId(), new BedVoidSetupState(arena.id()));
                player.getInventory().addItem(createBedVoidWand());
                Texts.send(player, "Select the Bedfight void cuboid for '&b" + arena.id() + "&r': left-click position 1, then right-click position 2 with the wooden axe.");
            }
            case "partyffa" -> {
                if (args.length != 2) { Texts.send(sender, "Usage: /varena partyffa <arena>"); return true; }
                if (!arenaManager.setPartyFfa(args[1])) {
                    String reason = arenaManager.partyFfaFailureReason(args[1]);
                    if (reason == null) reason = "the arena settings could not be saved to plugins/NexPractice/arenas.yml; check the console and file permissions";
                    Texts.send(sender, "Could not configure Party FFA: " + reason + ". The setting is independent of arena geometry; safe-spawn validation only affects Party FFA queue eligibility.");
                } else {
                    Texts.send(sender, "Party FFA is saved for arena '&b" + args[1].toLowerCase(Locale.ROOT) + "&r'. The flag is stored in plugins/NexPractice/arenas.yml. Party FFA queues will use it only when the arena is enabled and all four safe cardinal spawns are valid; ordinary queues are unaffected.");
                }
            }
            case "list" -> {
                if (arenaManager.all().isEmpty()) Texts.send(sender, "No arenas have been created.");
                else for (ArenaData arena : arenaManager.all()) {
                    Texts.send(sender, "&b" + arena.id() + "&7: enabled=" + arena.enabled() + ", ready=" + arena.ready() + ", instances=" + arenaManager.copyCount(arena.id()) + ", border=" + arena.hasRegion() + ", partyFFA=" + arena.partyFfa());
                }
            }
            default -> Texts.send(sender, "Usage: /varena <create|setspawn|enable|disable|list|partyffa|bedsetup|bedsetvoid|enablebedfight> [arguments]");
        }
        return true;
    }

    private boolean handleSetArenaBorderCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        if (args.length != 1 && args.length != 2) {
            Texts.send(player, "Usage: /vsetarenaborder <start [arena]|done>");
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();
        if (subcommand.equals("start")) {
            if (queueManager.isInMatch(uuid) || arenaManager.isInUse(args.length == 2 ? args[1] : null)) {
                Texts.send(player, "You cannot select an arena border during a match.");
                return true;
            }
            ArenaData arena = args.length == 2 ? arenaManager.get(args[1]) : arenaManager.mostRecentArenaWithoutBorder();
            if (arena == null) {
                Texts.send(player, args.length == 2 ? "Arena not found. Create it with /varena create <name>." : "Create an arena first with /varena create <name>, then run /vsetarenaborder start.");
                return true;
            }
            if (arenaManager.isInUse(arena.id())) {
                Texts.send(player, "That arena is currently in use.");
                return true;
            }
            if (arenaManager.copyCount(arena.id()) > 1) {
                Texts.send(player, "Reduce this arena to one instance in /varenamenu before changing its template border.");
                return true;
            }
            if (arenaManager.copiesBusy(arena.id()) || arenaManager.isRecovering(arena.id())) {
                Texts.send(player, "That arena is being restored or copied. Wait until the operation finishes.");
                return true;
            }
            removeArenaWands(player);
            int freeSlot = player.getInventory().firstEmpty();
            if (freeSlot == -1) {
                Texts.send(player, "Free one inventory slot before starting arena selection.");
                return true;
            }
            arenaSetupStates.put(uuid, new ArenaSetupState(arena.id()));
            completedArenaSelections.remove(uuid);
            player.getInventory().setItem(freeSlot, createArenaWand());
            Texts.send(player, "Selecting border for '&b" + arena.id() + "&r'. Left-click a block for position 1, then right-click the opposite corner for position 2.");
            return true;
        }
        if (subcommand.equals("done")) {
            if (args.length != 1) {
                Texts.send(player, "Usage: /vsetarenaborder done");
                return true;
            }
            ArenaSelection selection = completedArenaSelections.get(uuid);
            if (selection == null) {
                Texts.send(player, "Select both positions first: /vsetarenaborder start, left-click position 1, right-click position 2, then run /vsetarenaborder done.");
                return true;
            }
            if (!Objects.equals(selection.first().getWorld(), selection.second().getWorld())) {
                Texts.send(player, "Both arena positions must be in the same world.");
                return true;
            }
            boolean started = arenaManager.captureSnapshot(selection.arenaId(), selection.first().getWorld(),
                    com.sk89q.worldedit.math.BlockVector3.at(selection.first().getBlockX(), selection.first().getBlockY(), selection.first().getBlockZ()),
                    com.sk89q.worldedit.math.BlockVector3.at(selection.second().getBlockX(), selection.second().getBlockY(), selection.second().getBlockZ()),
                    saved -> {
                        if (saved) {
                            completedArenaSelections.remove(uuid);
                            Texts.send(player, "Saved border and original blocks for arena '&b" + selection.arenaId() + "&r'. Set spawns with /varena setspawn " + selection.arenaId() + " 1 and /varena setspawn " + selection.arenaId() + " 2, then enable it.");
                        } else {
                            Texts.send(player, "Could not save the arena snapshot or its settings. Check the console and write permissions for plugins/NexPractice/arenas.yml and plugins/NexPractice/arena-snapshots; ensure the region is valid and below 2,000,000 blocks, then retry /vsetarenaborder done.");
                        }
                    });
            if (!started) Texts.send(player, "Could not start snapshot saving. Check that the arena exists, is not in use, and the selection is not too large.");
            else Texts.send(player, "Saving the arena snapshot with FastAsyncWorldEdit...");
            return true;
        }
        Texts.send(player, "Usage: /vsetarenaborder <start [arena]|done>");
        return true;
    }

    private MatchContext activeMatchContext(UUID uuid) {
        Match solo = queueManager.getMatch(uuid);
        if (solo != null) return new MatchContext(solo.kitId(), solo.arena(), solo.countdown(), solo.finishing(),
                solo.arenaTeleportInProgress(), solo.onArena(), solo.roundTransitioning());
        PartyMatch partyMatch = partyMatchManager.getActiveMatch(uuid);
        if (partyMatch != null) return new MatchContext(partyMatch.kitId(), partyMatch.arena(), partyMatch.countdown(),
                partyMatch.finishing(), partyMatch.arenaTeleportInProgress(), partyMatch.onArena(), partyMatch.roundTransitioning());
        return null;
    }

    private static final class PlacedBlockMarker {
        private final Object matchIdentity;
        private PlacedBlockMarker(Object matchIdentity) { this.matchIdentity = matchIdentity; }
    }

    // Plain holder classes avoid record-specific invokedynamic/metadata paths in Shadow's ASM remapper.
    private static final class MatchContext {
        private final String kitId;
        private final ArenaData arena;
        private final boolean countdown;
        private final boolean finishing;
        private final boolean arenaTeleportInProgress;
        private final boolean onArena;
        private final boolean roundTransitioning;

        private MatchContext(String kitId, ArenaData arena, boolean countdown, boolean finishing,
                             boolean arenaTeleportInProgress, boolean onArena, boolean roundTransitioning) {
            this.kitId = kitId;
            this.arena = arena;
            this.countdown = countdown;
            this.finishing = finishing;
            this.arenaTeleportInProgress = arenaTeleportInProgress;
            this.onArena = onArena;
            this.roundTransitioning = roundTransitioning;
        }

        private String kitId() { return kitId; }
        private ArenaData arena() { return arena; }
        private boolean countdown() { return countdown; }
        private boolean finishing() { return finishing; }
        private boolean arenaTeleportInProgress() { return arenaTeleportInProgress; }
        private boolean onArena() { return onArena; }
        private boolean roundTransitioning() { return roundTransitioning; }
    }

    private static final class KitModeState {
        private final GameMode gameMode;
        private final ItemStack[] contents;
        private final ItemStack[] armor;
        private final ItemStack offhand;
        private final int heldSlot;
        private final int foodLevel;
        private final float saturation;

        private KitModeState(GameMode gameMode, ItemStack[] contents, ItemStack[] armor,
                             ItemStack offhand, int heldSlot, int foodLevel, float saturation) {
            this.gameMode = gameMode;
            this.contents = contents;
            this.armor = armor;
            this.offhand = offhand;
            this.heldSlot = heldSlot;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
        }

        private GameMode gameMode() { return gameMode; }
        private ItemStack[] contents() { return contents; }
        private ItemStack[] armor() { return armor; }
        private ItemStack offhand() { return offhand; }
        private int heldSlot() { return heldSlot; }
        private int foodLevel() { return foodLevel; }
        private float saturation() { return saturation; }
    }

    private static final class ArenaSetupState {
        private final String arenaId;
        private Location firstCorner;
        private ArenaSetupState(String arenaId) { this.arenaId = arenaId; }
        private String arenaId() { return arenaId; }
        private Location firstCorner() { return firstCorner; }
        private void setFirstCorner(Location location) { firstCorner = location == null ? null : location.clone(); }
    }

    private static final class BedSetupState {
        private final String arenaId;
        private Location firstBed;
        private BedSetupState(String arenaId) { this.arenaId = arenaId; }
        private String arenaId() { return arenaId; }
        private Location firstBed() { return firstBed == null ? null : firstBed.clone(); }
        private void setFirstBed(Location location) { firstBed = location == null ? null : location.clone(); }
    }

    private static final class BedVoidSetupState {
        private final String arenaId;
        private Location firstCorner;
        private BedVoidSetupState(String arenaId) { this.arenaId = arenaId; }
        private String arenaId() { return arenaId; }
        private Location firstCorner() { return firstCorner == null ? null : firstCorner.clone(); }
        private void setFirstCorner(Location location) { firstCorner = location == null ? null : location.clone(); }
    }

    private static final class ArenaSelection {
        private final String arenaId;
        private final Location first;
        private final Location second;

        private ArenaSelection(String arenaId, Location first, Location second) {
            this.arenaId = arenaId;
            this.first = first;
            this.second = second;
        }

        private String arenaId() { return arenaId; }
        private Location first() { return first; }
        private Location second() { return second; }
    }

    private static final class CreeperHitCredit {
        private final UUID playerId;
        private final long atMillis;
        private CreeperHitCredit(UUID playerId, long atMillis) {
            this.playerId = playerId;
            this.atMillis = atMillis;
        }
        private UUID playerId() { return playerId; }
        private long atMillis() { return atMillis; }
    }

    private static final class CreeperExplosionCredit {
        private final UUID playerId;
        private final Location location;
        private final long atMillis;
        private CreeperExplosionCredit(UUID playerId, Location location, long atMillis) {
            this.playerId = playerId;
            this.location = location;
            this.atMillis = atMillis;
        }
        private UUID playerId() { return playerId; }
        private Location location() { return location; }
        private long atMillis() { return atMillis; }
    }
}
