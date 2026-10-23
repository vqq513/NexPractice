package com.vprac.leaderboard;

import com.vprac.VpracPlugin;
import com.vprac.leaderboard.LeaderboardManager.Entry;
import com.vprac.leaderboard.LeaderboardManager.Metric;
import com.vprac.leaderboard.LeaderboardManager.Period;
import com.vprac.util.Texts;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional FancyHolograms leaderboard integration.
 * Each board is one multi-line FancyHolograms text hologram using native CENTER billboarding.
 * FancyHolograms, not VPRAC, owns display spawning and per-viewer facing. VPRAC only updates the
 * board's text when its contents/countdown change. One stationary invisible Interaction entity
 * opens a period picker, since FancyHolograms currently does not natively support text clicks.
 */
public final class FancyLeaderboardHologramManager implements Listener {
    private static final int LINE_COUNT = 18;
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Period[] PERIOD_ORDER = {Period.LIFETIME, Period.MONTHLY, Period.WEEKLY, Period.DAILY};
    private static final Pattern LEGACY_SEGMENT_NAME = Pattern.compile("(?:lb\\d+|lb_[0-9a-fA-F]{8})_(?:period_(?:lifetime|monthly|weekly|daily)|sep[123]|title|kit-name|kit-icon|row_\\d+|timer|display)");
    private static final Pattern VPRAC_DISPLAY_NAME = Pattern.compile("vprac_(lb\\d+|lb_[0-9a-fA-F]{8})_display");

    private final VpracPlugin plugin;
    private final File file;
    private final NamespacedKey interactionGroupKey;
    private final Map<String, HologramState> holograms = new LinkedHashMap<>();
    private HologramManager fancyManager;
    private int taskId = -1;
    private boolean started;
    private boolean initialized;
    private int initializeAttempts;

    public FancyLeaderboardHologramManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "leaderboard-holograms.yml");
        this.interactionGroupKey = new NamespacedKey("vprac", "leaderboard_hologram_group");
        loadState();
    }

    public boolean isFeatureEnabled() {
        return plugin.getConfig().getBoolean("leaderboards.holograms.enabled", true);
    }

    public void start() {
        if (started) return;
        started = true;
        if (!isFeatureEnabled()) {
            plugin.getLogger().info("FancyHolograms leaderboard displays are disabled in config.yml.");
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
            plugin.getLogger().warning("FancyHolograms is not installed. /vhg leaderboard holograms are unavailable; all other NexPractice features remain enabled.");
            return;
        }
        try {
            fancyManager = FancyHologramsPlugin.get().getHologramManager();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not connect to FancyHolograms API: " + throwable.getMessage());
            fancyManager = null;
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // FancyHolograms persists and loads its own objects. Wait until its manager reports loaded,
        // then remove every legacy per-line object before VPRAC creates the single native-billboard display.
        Bukkit.getScheduler().runTaskLater(plugin, this::initializeDisplaysWhenReady, 20L);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 20L);
    }

    private void initializeDisplaysWhenReady() {
        if (fancyManager == null) return;
        if (!fancyManager.isLoaded()) {
            initializeAttempts++;
            if (initializeAttempts <= 10) {
                Bukkit.getScheduler().runTaskLater(plugin, this::initializeDisplaysWhenReady, 20L);
            } else {
                plugin.getLogger().warning("FancyHolograms did not finish loading; leaderboard display migration was deferred. Run /vhg reload after FancyHolograms is ready.");
            }
            return;
        }
        removeAllManagedInteractions();
        int removedLegacy = removeAllLegacyVpracDisplays();
        for (HologramState state : holograms.values()) {
            removeLegacyDisplays(state);
            render(state);
            ensureInteraction(state);
        }
        initialized = true;
        if (removedLegacy > 0) {
            // Persist removals through FancyHolograms so the legacy fragments do not return on restart.
            fancyManager.saveHolograms();
            plugin.getLogger().info("Removed " + removedLegacy + " obsolete legacy leaderboard hologram fragment(s); boards now use one FancyHolograms multiline display each.");
        }
    }

    public boolean handleCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("vprac.admin.holograms")) {
            Texts.send(sender, "&cYou do not have permission to use /vhg.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            if (!isFeatureEnabled()) {
                Texts.send(sender, "&cLeaderboard holograms are disabled in config.yml.");
                return true;
            }
            if (!ensureFancyAvailable(sender)) return true;
            removeAllManagedInteractions();
            int removedLegacy = removeAllLegacyVpracDisplays();
            if (removedLegacy > 0) fancyManager.saveHolograms();
            for (HologramState state : holograms.values()) {
                state.lastRenderedLines = List.of();
                removeLegacyDisplays(state);
                render(state);
                ensureInteraction(state);
            }
            initialized = true;
            saveState();
            Texts.send(sender, "&aRefreshed &e" + holograms.size() + " &aleaderboard hologram(s).");
            return true;
        }
        if (args[0].equalsIgnoreCase("list") && args.length == 1) {
            if (holograms.isEmpty()) {
                Texts.send(sender, "&7No NexPractice leaderboard holograms have been created.");
                return true;
            }
            for (HologramState state : holograms.values()) {
                Texts.send(sender, "&e" + state.id + " &7- &b" + state.metric.label() + " Leaderboards &7(" + state.worldName + ")");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("create") && args.length == 3 && args[1].equalsIgnoreCase("leaderboards")) {
            if (!(sender instanceof Player player)) {
                Texts.send(sender, "&cOnly a player can create a hologram because its location is taken from their position.");
                return true;
            }
            if (!isFeatureEnabled()) {
                Texts.send(player, "&cLeaderboard holograms are disabled in config.yml.");
                return true;
            }
            if (!ensureFancyAvailable(player)) return true;
            if (!args[2].equalsIgnoreCase("wins") && !args[2].equalsIgnoreCase("kills")
                    && !args[2].equalsIgnoreCase("kdr") && !args[2].equalsIgnoreCase("rank")) {
                Texts.send(player, "&cUsage: /vhg create leaderboards <wins|kills|kdr|rank>");
                return true;
            }
            Metric metric = Metric.fromId(args[2]);
            String id = nextSimpleId();
            Location top = createTopLocation(player);
            HologramState state = new HologramState(id, top.getWorld().getName(), top.getX(), top.getY(), top.getZ(),
                    top.getYaw(), top.getPitch(), metric, Period.LIFETIME, 0,
                    System.currentTimeMillis() + intervalMillis());
            holograms.put(id, state);
            render(state);
            ensureInteraction(state);
            initialized = true;
            saveState();
            Texts.send(player, "&aCreated &e" + id + "&a. It will rotate to the next kit every &e"
                    + plugin.getConfig().getInt("leaderboards.holograms.cycle-interval-minutes", 10) + " &aminutes.");
            Texts.send(player, "&7Right- or left-click the hologram to choose Lifetime, Monthly, Weekly, or Daily.");
            return true;
        }
        if (args[0].equalsIgnoreCase("delete") && args.length == 3 && args[1].equalsIgnoreCase("leaderboards")) {
            if (!ensureFancyAvailable(sender)) return true;
            HologramState removed = holograms.remove(args[2]);
            if (removed == null) {
                Texts.send(sender, "&cNo NexPractice leaderboard hologram found with ID " + args[2] + ".");
                return true;
            }
            removeManagedDisplay(removed);
            removeLegacyDisplays(removed);
            removeInteractions(removed.id);
            saveState();
            Texts.send(sender, "&aDeleted leaderboard hologram &e" + removed.id + "&a.");
            return true;
        }
        sendHelp(sender);
        return true;
    }

    /** Handles period-picker buttons opened by the stationary click area. */
    public boolean handleCustomClick(PlayerCustomClickEvent event, Player player) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals("vprac") || !key.value().startsWith("leaderboard/hologram-period/")) return false;
        String[] parts = key.value().split("/");
        if (parts.length != 4) return true;
        HologramState state = holograms.get(parts[2]);
        if (state == null || fancyManager == null) return true;
        Period period = Period.fromId(parts[3]);
        state.period = period;
        state.lastRenderedLines = List.of();
        render(state);
        saveState();
        return true;
    }

    private String nextSimpleId() {
        int number = 1;
        while (holograms.containsKey("lb" + number)) number++;
        return "lb" + number;
    }

    private void sendHelp(org.bukkit.command.CommandSender sender) {
        Texts.send(sender, "&c/vhg create leaderboards <wins|kills|kdr|rank>");
        Texts.send(sender, "&c/vhg list");
        Texts.send(sender, "&c/vhg delete leaderboards <id>");
        Texts.send(sender, "&c/vhg reload");
    }

    private boolean ensureFancyAvailable(org.bukkit.command.CommandSender sender) {
        if (fancyManager == null && Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
            try { fancyManager = FancyHologramsPlugin.get().getHologramManager(); }
            catch (Throwable throwable) { fancyManager = null; }
        }
        if (fancyManager != null) return true;
        Texts.send(sender, "&cFancyHolograms is not installed or its API is unavailable. Install FancyHolograms to use leaderboard holograms.");
        return false;
    }

    private Location createTopLocation(Player player) {
        Location location = player.getLocation().clone();
        org.bukkit.util.Vector forward = location.getDirection().setY(0);
        if (forward.lengthSquared() < 0.001) forward = new org.bukkit.util.Vector(0, 0, 1);
        forward.normalize().multiply(2.2);
        double estimatedLineHeight = 0.25 * textScaleValue();
        // A single multiline display uses Minecraft's native TextDisplay line height. Put its top
        // line at eye height plus the calculated full board height, without placing each line entity.
        location.add(forward).add(0, (LINE_COUNT - 1) * estimatedLineHeight + 0.2, 0);
        location.setPitch(0.0f);
        return location;
    }

    private void tick() {
        if (fancyManager == null || !isFeatureEnabled() || !initialized) return;
        long now = System.currentTimeMillis();
        long interval = intervalMillis();
        List<String> kitIds = plugin.getKitManager().getKitIds();
        if (kitIds.isEmpty()) return;
        boolean changedKit = false;
        for (HologramState state : holograms.values()) {
            if (state.kitIndex < 0 || state.kitIndex >= kitIds.size()) state.kitIndex = Math.floorMod(state.kitIndex, kitIds.size());
            if (state.nextUpdateAt <= now) {
                long intervalsElapsed = 1L + Math.max(0L, (now - state.nextUpdateAt) / interval);
                state.kitIndex = (int) ((state.kitIndex + intervalsElapsed) % kitIds.size());
                state.nextUpdateAt += intervalsElapsed * interval;
                state.lastRenderedLines = List.of();
                changedKit = true;
            }
            // Only this one FancyHolograms text object is updated, and only if its text differs.
            // No PlayerMoveEvent, manual yaw changes, or per-line entity movement is performed.
            render(state);
        }
        if (changedKit) saveState();
    }

    private void render(HologramState state) {
        if (fancyManager == null || !isFeatureEnabled()) return;
        Location base = state.topLocation();
        if (base == null || base.getWorld() == null) return;
        List<String> kits = plugin.getKitManager().getKitIds();
        if (kits.isEmpty()) return;
        if (state.kitIndex < 0 || state.kitIndex >= kits.size()) state.kitIndex = Math.floorMod(state.kitIndex, kits.size());
        String kitId = kits.get(state.kitIndex);
        List<String> lines = new ArrayList<>(LINE_COUNT);

        lines.add(formatPeriodLine(state.period));
        lines.add("");
        lines.add(plugin.getConfig().getString("leaderboards.holograms.title-format", "&b&l{metric} Leaderboards")
                .replace("{metric}", state.metric.label()));
        lines.add("");
        // This is intentionally text-only. FancyHolograms owns one cohesive multiline text display;
        // VPRAC no longer overlays independent display entities or tries to keep an item icon aligned
        // while the native billboard rotates separately for each viewing client.
        lines.add(plugin.getConfig().getString("leaderboards.holograms.kit-format", "&e&l{kit}")
                .replace("{kit}", plugin.getKitManager().getDisplayName(kitId)));
        lines.add("");
        lines.add("");

        List<Entry> top = plugin.getLeaderboardManager().top(kitId, state.metric, 10, state.period);
        for (int place = 1; place <= 10; place++) {
            String row;
            if (place <= top.size()) {
                Entry entry = top.get(place - 1);
                String value = entry.value(state.metric);
                if (state.metric == Metric.RANK && plugin.getPlayerPreferencesManager() != null) {
                    value = plugin.getPlayerPreferencesManager().visibleRank(entry.uuid(), entry.lifetimeWins());
                }
                row = plugin.getConfig().getString("leaderboards.holograms.player-row-format", "&6#{place} &7- &f{player} &7- &6{value}")
                        .replace("{place}", Integer.toString(place))
                        .replace("{player}", entry.name())
                        .replace("{value}", value);
            } else {
                row = plugin.getConfig().getString("leaderboards.holograms.empty-row-format", "&7#{place} &7- &8No player")
                        .replace("{place}", Integer.toString(place));
            }
            lines.add(row);
        }
        lines.add(timerLine(state));

        if (lines.size() < LINE_COUNT) while (lines.size() < LINE_COUNT) lines.add("");
        if (lines.size() > LINE_COUNT) lines = new ArrayList<>(lines.subList(0, LINE_COUNT));
        List<String> serialized = lines.stream().map(this::toMiniMessage).toList();
        String displayName = displayName(state);
        boolean missing = fancyManager.getHologram(displayName).isEmpty();
        if (missing || !serialized.equals(state.lastRenderedLines)) {
            upsertText(displayName, base, serialized);
            state.lastRenderedLines = List.copyOf(serialized);
        }
    }

    private String formatPeriodLine(Period selected) {
        List<String> labels = new ArrayList<>(PERIOD_ORDER.length);
        for (Period period : PERIOD_ORDER) {
            String path = period == selected
                    ? "leaderboards.holograms.selected-label-format"
                    : "leaderboards.holograms.label-format";
            String fallback = period == selected ? "&e&l[{label}]" : "&a[{label}]";
            labels.add(plugin.getConfig().getString(path, fallback).replace("{label}", period.label()));
        }
        String separator = plugin.getConfig().getString("leaderboards.holograms.separator", "&7-");
        return String.join(" " + separator + " ", labels);
    }

    private String timerLine(HologramState state) {
        long seconds = Math.max(0L, (state.nextUpdateAt - System.currentTimeMillis() + 999L) / 1000L);
        long minutes = seconds / 60L;
        long secondsPart = seconds % 60L;
        String remaining = minutes > 0
                ? minutes + "m " + String.format(Locale.ROOT, "%02d", secondsPart) + "s"
                : secondsPart + "s";
        return plugin.getConfig().getString("leaderboards.holograms.timer-format", "&fNext update in {time}")
                .replace("{time}", remaining);
    }

    private String toMiniMessage(String legacyText) {
        return MINI.serialize(LEGACY.deserialize(legacyText == null ? "" : legacyText));
    }

    private Hologram upsertText(String name, Location location, List<String> serializedLines) {
        Optional<Hologram> existing = fancyManager.getHologram(name);
        Hologram hologram = existing.orElse(null);
        if (hologram != null && hologram.getData() instanceof TextHologramData textData) {
            textData.setText(serializedLines);
            textData.setLocation(location);
            textData.setBillboard(Display.Billboard.CENTER);
            textData.setTextAlignment(TextDisplay.TextAlignment.CENTER);
            textData.setScale(textScale());
            hologram.queueUpdate();
            return hologram;
        }
        if (hologram != null) fancyManager.removeHologram(hologram);
        TextHologramData data = new TextHologramData(name, location);
        data.setText(serializedLines);
        data.setPersistent(true);
        data.setBillboard(Display.Billboard.CENTER);
        data.setTextAlignment(TextDisplay.TextAlignment.CENTER);
        data.setScale(textScale());
        Hologram created = fancyManager.create(data);
        fancyManager.addHologram(created);
        return created;
    }

    private org.joml.Vector3f textScale() {
        float scale = (float) textScaleValue();
        return new org.joml.Vector3f(scale, scale, scale);
    }

    private double textScaleValue() {
        return Math.max(0.2, Math.min(2.0,
                plugin.getConfig().getDouble("leaderboards.holograms.text-scale", 0.7)));
    }

    private String displayName(HologramState state) {
        return "vprac_" + state.id + "_display";
    }

    /**
     * Removes old segmented boards even when their VPRAC state file entry has been deleted.
     * Previous releases created one FancyHolograms object per period label/row and an item object;
     * leaving those objects registered would make them appear to rotate independently beside the
     * new single multiline hologram. Only known VPRAC legacy naming patterns are removed.
     */
    private int removeAllLegacyVpracDisplays() {
        if (fancyManager == null) return 0;
        int removed = 0;
        for (Hologram hologram : new ArrayList<>(fancyManager.getHolograms())) {
            String name = hologram.getData().getName();
            Matcher legacy = LEGACY_SEGMENT_NAME.matcher(name);
            Matcher currentDisplay = VPRAC_DISPLAY_NAME.matcher(name);
            boolean orphanCurrentDisplay = currentDisplay.matches() && !holograms.containsKey(currentDisplay.group(1));
            if (legacy.matches() || orphanCurrentDisplay) {
                fancyManager.removeHologram(hologram);
                removed++;
            }
        }
        return removed;
    }

    private void removeLegacyDisplays(HologramState state) {
        if (fancyManager == null) return;
        for (Period period : PERIOD_ORDER) {
            fancyManager.getHologram(state.id + "_period_" + period.id()).ifPresent(fancyManager::removeHologram);
        }
        fancyManager.getHologram(state.id + "_sep1").ifPresent(fancyManager::removeHologram);
        fancyManager.getHologram(state.id + "_sep2").ifPresent(fancyManager::removeHologram);
        fancyManager.getHologram(state.id + "_sep3").ifPresent(fancyManager::removeHologram);
        fancyManager.getHologram(state.id + "_title").ifPresent(fancyManager::removeHologram);
        fancyManager.getHologram(state.id + "_kit-name").ifPresent(fancyManager::removeHologram);
        fancyManager.getHologram(state.id + "_kit-icon").ifPresent(fancyManager::removeHologram);
        fancyManager.getHologram(state.id + "_timer").ifPresent(fancyManager::removeHologram);
        for (int row = 1; row <= 10; row++) {
            fancyManager.getHologram(state.id + "_row_" + row).ifPresent(fancyManager::removeHologram);
        }
    }

    private void removeManagedDisplay(HologramState state) {
        if (fancyManager == null) return;
        fancyManager.getHologram(displayName(state)).ifPresent(fancyManager::removeHologram);
    }

    private void ensureInteraction(HologramState state) {
        Location top = state.topLocation();
        if (top == null || top.getWorld() == null) return;
        Location position = interactionLocation(top);
        int chunkX = position.getBlockX() >> 4;
        int chunkZ = position.getBlockZ() >> 4;
        if (!position.getWorld().isChunkLoaded(chunkX, chunkZ)) return;
        Interaction found = null;
        for (Entity entity : position.getWorld().getChunkAt(chunkX, chunkZ).getEntities()) {
            if (!(entity instanceof Interaction interaction)) continue;
            String group = interaction.getPersistentDataContainer().get(interactionGroupKey, PersistentDataType.STRING);
            if (!state.id.equals(group)) continue;
            if (found == null) found = interaction;
            else interaction.remove();
        }
        if (found != null) {
            found.teleport(position);
            found.setInteractionWidth(clickWidth());
            found.setInteractionHeight(clickHeight());
            found.setResponsive(true);
            return;
        }
        position.getWorld().spawn(position, Interaction.class, interaction -> {
            interaction.setInteractionWidth(clickWidth());
            interaction.setInteractionHeight(clickHeight());
            interaction.setResponsive(true);
            interaction.setPersistent(false); // Recreated on ChunkLoad; nothing is saved into the world.
            interaction.setInvulnerable(true);
            interaction.getPersistentDataContainer().set(interactionGroupKey, PersistentDataType.STRING, state.id);
        });
    }

    private Location interactionLocation(Location top) {
        return top.clone().add(0, -((LINE_COUNT - 1) * 0.25 * textScaleValue()) / 2.0, 0);
    }

    private float clickWidth() {
        return (float) Math.max(2.0, Math.min(12.0, plugin.getConfig().getDouble("leaderboards.holograms.click-width", 6.0)));
    }

    private float clickHeight() {
        return (float) Math.max(2.0, Math.min(8.0, plugin.getConfig().getDouble("leaderboards.holograms.click-height", 4.5)));
    }

    private void removeAllManagedInteractions() {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Interaction interaction
                            && interaction.getPersistentDataContainer().has(interactionGroupKey, PersistentDataType.STRING)) {
                        interaction.remove();
                    }
                }
            }
        }
    }

    private void removeInteractions(String groupId) {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (!(entity instanceof Interaction interaction)) continue;
                    if (groupId.equals(interaction.getPersistentDataContainer().get(interactionGroupKey, PersistentDataType.STRING))) {
                        interaction.remove();
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeaderboardRightClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) return;
        String id = interaction.getPersistentDataContainer().get(interactionGroupKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        HologramState state = holograms.get(id);
        if (state != null) openPeriodPicker(event.getPlayer(), state);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeaderboardLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Interaction interaction)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        String id = interaction.getPersistentDataContainer().get(interactionGroupKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        HologramState state = holograms.get(id);
        if (state != null) openPeriodPicker(player, state);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (fancyManager == null || !isFeatureEnabled()) return;
        for (HologramState state : holograms.values()) {
            Location position = state.topLocation();
            if (position != null && position.getWorld() != null
                    && position.getWorld().equals(event.getWorld())
                    && (position.getBlockX() >> 4) == event.getChunk().getX()
                    && (position.getBlockZ() >> 4) == event.getChunk().getZ()) {
                ensureInteraction(state);
            }
        }
    }

    private void openPeriodPicker(Player player, HologramState state) {
        List<ActionButton> actions = new ArrayList<>();
        for (Period period : PERIOD_ORDER) {
            NamedTextColor color = period == state.period ? NamedTextColor.GOLD : NamedTextColor.GREEN;
            ActionButton button = ActionButton.builder(Component.text(period.label(), color))
                    .action(DialogAction.customClick(Key.key("vprac", "leaderboard/hologram-period/" + state.id + "/" + period.id()), EMPTY_TAG))
                    .build();
            actions.add(button);
        }
        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY)).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Leaderboard Period", NamedTextColor.AQUA))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("Choose the period displayed on this leaderboard.", NamedTextColor.GRAY), 320)))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(actions, close, 2)));
        player.showDialog(dialog);
    }

    private void loadState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create data directory for leaderboard hologram state.");
        }
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int storedLayoutVersion = yaml.getInt("layout-version", 1);
        boolean migrateLayout = storedLayoutVersion < 2;
        double oldSpacing = Math.max(0.16, Math.min(0.5,
                plugin.getConfig().getDouble("leaderboards.holograms.line-spacing", 0.22)));
        double oldTopOffset = (LINE_COUNT - 1) * oldSpacing + 0.22;
        double newTopOffset = (LINE_COUNT - 1) * 0.25 * textScaleValue() + 0.2;
        double verticalCorrection = oldTopOffset - newTopOffset;
        ConfigurationSection section = yaml.getConfigurationSection("holograms");
        if (section == null) return;

        // Older VPRAC versions generated IDs such as lb_a1b2c3d4. Reserve existing simple IDs first,
        // then remap those older random IDs to lb1, lb2, ... without colliding with any current board.
        List<String> savedIds = new ArrayList<>(section.getKeys(false));
        Set<String> reservedSimpleIds = new HashSet<>();
        for (String id : savedIds) {
            if (id.matches("lb\\d+")) reservedSimpleIds.add(id);
        }
        int nextSimple = 1;
        boolean migrateIds = false;
        for (String savedId : savedIds) {
            String path = "holograms." + savedId;
            String world = yaml.getString(path + ".world");
            if (world == null || world.isBlank()) continue;
            String id = savedId;
            if (savedId.matches("lb_[0-9a-fA-F]{8}")) {
                while (reservedSimpleIds.contains("lb" + nextSimple)) nextSimple++;
                id = "lb" + nextSimple++;
                reservedSimpleIds.add(id);
                migrateIds = true;
            }
            Metric metric = Metric.fromId(yaml.getString(path + ".metric", "rank"));
            Period period = Period.fromId(yaml.getString(path + ".period", "lifetime"));
            double storedY = yaml.getDouble(path + ".y");
            if (migrateLayout) storedY -= verticalCorrection;
            holograms.put(id, new HologramState(id, world,
                    yaml.getDouble(path + ".x"), storedY, yaml.getDouble(path + ".z"),
                    (float) yaml.getDouble(path + ".yaw"), (float) yaml.getDouble(path + ".pitch"), metric, period,
                    yaml.getInt(path + ".kit-index", 0), yaml.getLong(path + ".next-update-at", System.currentTimeMillis() + intervalMillis())));
        }
        if ((migrateLayout || migrateIds) && !holograms.isEmpty()) saveState();
    }

    private void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("layout-version", 2);
        for (HologramState state : holograms.values()) {
            String path = "holograms." + state.id;
            yaml.set(path + ".world", state.worldName);
            yaml.set(path + ".x", state.x); yaml.set(path + ".y", state.y); yaml.set(path + ".z", state.z);
            yaml.set(path + ".yaw", state.yaw); yaml.set(path + ".pitch", state.pitch);
            yaml.set(path + ".metric", state.metric.id()); yaml.set(path + ".period", state.period.id());
            yaml.set(path + ".kit-index", state.kitIndex); yaml.set(path + ".next-update-at", state.nextUpdateAt);
        }
        try { yaml.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Could not save leaderboard hologram state: " + exception.getMessage()); }
    }

    private long intervalMillis() {
        long minutes = Math.max(1, Math.min(1440, plugin.getConfig().getLong("leaderboards.holograms.cycle-interval-minutes", 10)));
        return minutes * 60_000L;
    }

    public void shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        saveState();
        for (HologramState state : holograms.values()) removeInteractions(state.id);
    }

    private final class HologramState {
        private final String id;
        private final String worldName;
        private final double x, z;
        private double y;
        private final float yaw;
        private final float pitch;
        private final Metric metric;
        private Period period;
        private int kitIndex;
        private long nextUpdateAt;
        private List<String> lastRenderedLines = List.of();

        private HologramState(String id, String worldName, double x, double y, double z, float yaw, float pitch,
                              Metric metric, Period period, int kitIndex, long nextUpdateAt) {
            this.id = id; this.worldName = worldName; this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.metric = metric; this.period = period;
            this.kitIndex = kitIndex; this.nextUpdateAt = nextUpdateAt;
        }

        private Location topLocation() {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }
}
