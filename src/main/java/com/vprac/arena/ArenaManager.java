package com.vprac.arena;

import com.vprac.VpracPlugin;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Owns arena templates and their independent, spatially-separated physical instances. */
public final class ArenaManager {
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final long MAX_SNAPSHOT_BLOCKS = 2_000_000L;
    private static final int MAX_INSTANCES = 32;
    private static final int INSTANCE_GAP = 32;
    private static final int MAX_PLACEMENT_ATTEMPTS = 4096;

    private final VpracPlugin plugin;
    private final File file;
    private final File schematicDirectory;
    private YamlConfiguration config;
    /** User-facing arena templates, keyed by template ID. */
    private final Map<String, ArenaData> arenas = new LinkedHashMap<>();
    /** Physical instances per template; instance 1 is the original selected region. */
    private final Map<String, List<ArenaData>> instances = new LinkedHashMap<>();
    /** Reservations are instance keys, never template IDs, so copies can run concurrently. */
    private final Set<String> inUse = new HashSet<>();
    private final Set<String> snapshotsBusy = new HashSet<>();
    private final Set<String> copiesBusy = new HashSet<>();
    private final Set<String> deleting = new HashSet<>();
    private final Set<String> recovering = new HashSet<>();
    /** Arenas skipped only because their world or baseline snapshot was temporarily unavailable. */
    private final Set<String> deferredStartupRecovery = new LinkedHashSet<>();
    /** Blocks matchmaking while the asynchronous startup restore pass is running. */
    private boolean startupRecoveryPending = true;
    private boolean startupRecoveryScanning;
    private int startupRecoveryJobs;

    public ArenaManager(VpracPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "arenas.yml");
        schematicDirectory = new File(plugin.getDataFolder(), "arena-snapshots");
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!schematicDirectory.exists()) schematicDirectory.mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create arenas.yml: " + e.getMessage()); }
        }
        // Bukkit's automatic Location deserializer throws before plugin code can recover when
        // a saved world has been renamed or is temporarily unavailable. Parse legacy Location
        // sections as plain YAML maps, then resolve their world safely below.
        config = new YamlConfiguration();
        boolean migratedLegacyLocationMarkers = false;
        try {
            String yaml = Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            migratedLegacyLocationMarkers = yaml.contains("==: org.bukkit.Location");
            yaml = yaml.replaceAll("(?m)^([ \t]*)==:[ \t]*org\\.bukkit\\.Location[ \t]*$", "$1# legacy Bukkit Location marker migrated safely");
            config.loadFromString(yaml);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not safely load arenas.yml: " + exception.getMessage());
            config = new YamlConfiguration();
        }
        arenas.clear();
        instances.clear();
        deferredStartupRecovery.clear();
        inUse.clear();
        ConfigurationSection section = config.getConfigurationSection("arenas");
        if (section == null) return;

        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            Location one = readSavedLocation("arenas." + rawId + ".spawn1");
            Location two = readSavedLocation("arenas." + rawId + ".spawn2");
            boolean requestedEnabled = config.getBoolean("arenas." + rawId + ".enabled", false);
            ArenaData arena = new ArenaData(id, one, two, false);
            arena.setConfiguredEnabled(requestedEnabled);
            // Flags are persisted independently of physical arena readiness. An administrator
            // may configure Party FFA before finishing the border/spawns; queue eligibility
            // remains guarded by ready() and safe-spawn checks.
            arena.setPartyFfaFlag(config.getBoolean("arenas." + rawId + ".party-ffa", false));
            String regionPath = "arenas." + rawId + ".border";
            String worldName = config.getString(regionPath + ".world");
            if (worldName != null && !worldName.isBlank()) {
                arena.setRegion(worldName,
                        config.getInt(regionPath + ".min-x"), config.getInt(regionPath + ".min-y"), config.getInt(regionPath + ".min-z"),
                        config.getInt(regionPath + ".max-x"), config.getInt(regionPath + ".max-y"), config.getInt(regionPath + ".max-z"));
            }
            arena.setKitRestricted(config.getBoolean("arenas." + rawId + ".kit-restricted", false));
            arena.setAllowedKits(config.getStringList("arenas." + rawId + ".allowed-kits"));
            String bedPath = "arenas." + rawId + ".bedfight";
            arena.setBedfightEnabled(config.getBoolean(bedPath + ".enabled", false));
            Location bedA = readSavedLocation(bedPath + ".bed-a");
            Location bedB = readSavedLocation(bedPath + ".bed-b");
            arena.setBedLocations(bedA, bedB);
            String voidWorld = config.getString(bedPath + ".void.world");
            if (voidWorld != null && !voidWorld.isBlank() && config.contains(bedPath + ".void.min-x")
                    && config.contains(bedPath + ".void.max-x")) {
                arena.setBedVoid(voidWorld,
                        config.getInt(bedPath + ".void.min-x"), config.getInt(bedPath + ".void.min-y"), config.getInt(bedPath + ".void.min-z"),
                        config.getInt(bedPath + ".void.max-x"), config.getInt(bedPath + ".void.max-y"), config.getInt(bedPath + ".void.max-z"));
            }
            // An arena's enabled state is based on its region, standard spawns and snapshot.
            // Party FFA cardinal spots only govern eligibility for Party FFA queues; they must
            // not prevent the arena from being enabled for normal 1v1s after a restart.
            boolean ready = arena.ready() && Bukkit.getWorld(arena.regionWorld()) != null && hasUsableSnapshot(id);
            arena.setEnabled(requestedEnabled && ready);
            if (requestedEnabled && !ready) {
                String missing = !arena.hasRegion() ? "border" : arena.spawnOne() == null ? "spawn 1 / its world is unavailable"
                        : arena.spawnTwo() == null ? "spawn 2 / its world is unavailable" : Bukkit.getWorld(arena.regionWorld()) == null ? "world " + arena.regionWorld()
                        : !hasUsableSnapshot(id) ? "missing or empty snapshot " + snapshotFile(id).getName() : "spawn bounds";
                // Keep the administrator's enabled intent on disk. This instance remains disabled
                // in memory until every prerequisite is available, then can be enabled again
                // without requiring the administrator to repair an unrelated setting.
                plugin.getLogger().warning("Arena " + id + " is saved as enabled but is currently unavailable because of " + missing
                        + ". Runtime use is disabled; saved arena settings were preserved.");
            }
            // Preserve a retry marker when a world-management plugin has not loaded the world yet,
            // or when a snapshot is temporarily unavailable. The periodic retry below rehydrates
            // location objects from the saved coordinates and restores the arena without reloading
            // the whole registry (which could disrupt active matches).
            if (arena.hasRegion() && (Bukkit.getWorld(arena.regionWorld()) == null || !hasUsableSnapshot(id))) {
                deferredStartupRecovery.add(id);
            }
            arenas.put(id, arena);

            List<ArenaData> copies = new ArrayList<>();
            copies.add(arena);
            int requestedCopies = Math.max(1, Math.min(MAX_INSTANCES,
                    config.getInt("arenas." + rawId + ".copies.count", 1)));
            for (int index = 2; index <= requestedCopies; index++) {
                String offsetPath = "arenas." + rawId + ".copies.offsets." + index;
                if (!config.contains(offsetPath + ".x") || !config.contains(offsetPath + ".z")) {
                    arena.setEnabled(false);
                    // A malformed copy entry disables the runtime template but does not overwrite
                    // the saved enabled intent. Keep all metadata for recovery/manual repair.
                    plugin.getLogger().severe("Arena " + id + " copy " + index + " has no saved offset. The template is disabled in memory to avoid allocating an untracked physical region; saved metadata was preserved. Inspect arenas.yml and arena-snapshots before repairing copies in /varenamenu.");
                    break;
                }
                int dx = config.getInt(offsetPath + ".x");
                int dz = config.getInt(offsetPath + ".z");
                ArenaData copy = arena.createInstance(index, dx, dz);
                boolean operational = config.getBoolean(offsetPath + ".operational", true);
                if (!copyBackgroundFile(id, index).isFile()) {
                    operational = false;
                    plugin.getLogger().severe("Arena copy " + copy.instanceKey() + " has no saved destination backup and will not be allocated. Automatic removal is blocked because the original destination blocks cannot be recovered safely; restore the backup or repair the arena metadata manually.");
                }
                copy.setOperational(operational);
                copy.setEnabled(arena.enabled() && operational);
                copies.add(copy);
            }
            instances.put(id, copies);
            reconcilePendingCopyOnLoad(id, arena, copies);
        }
        cleanupOrphanCopyBackups();
        if (migratedLegacyLocationMarkers) {
            try { config.save(file); }
            catch (IOException exception) { plugin.getLogger().severe("Could not persist safe arena location migration: " + exception.getMessage()); }
            plugin.getLogger().info("Migrated legacy Bukkit Location entries in arenas.yml to safe plain YAML coordinates.");
        }
    }

    /**
     * Pending markers make copy generation recoverable if the server stops after the destination
     * region is backed up but before the copy metadata is committed.
     */
    private void reconcilePendingCopyOnLoad(String id, ArenaData template, List<ArenaData> copies) {
        String pendingPath = "arenas." + id + ".copies.pending";
        ConfigurationSection pending = config.getConfigurationSection(pendingPath);
        if (pending == null || pending.getKeys(false).isEmpty()) return;
        if (pending.getKeys(false).size() != 1) {
            disableArenaPreservingPending(template);
            plugin.getLogger().severe("Arena " + id + " has multiple pending copy transactions. Disabled for safety; inspect arenas.yml and arena-snapshots.");
            return;
        }
        String indexText = pending.getKeys(false).iterator().next();
        int index;
        try { index = Integer.parseInt(indexText); }
        catch (NumberFormatException exception) {
            disableArenaPreservingPending(template);
            plugin.getLogger().severe("Arena " + id + " has an invalid pending copy index; disabled for safety.");
            return;
        }
        String base = pendingPath + "." + indexText;
        if (index <= copies.size()) {
            // Copy metadata was committed; only cleanup of the marker was interrupted.
            saveArena(template);
            return;
        }
        if (index != copies.size() + 1) {
            disableArenaPreservingPending(template);
            plugin.getLogger().severe("Arena " + id + " has a pending copy index gap. Disabled to avoid overwriting untracked blocks.");
            return;
        }
        File backup = copyBackgroundFile(id, index);
        if (!backup.isFile()) {
            // Paste can't start until the destination backup has been written.
            saveArena(template);
            plugin.getLogger().warning("Cleared an unstarted arena-copy transaction for " + id + " (copy " + index + ").");
            return;
        }
        if (!config.contains(base + ".x") || !config.contains(base + ".z")) {
            disableArenaPreservingPending(template);
            plugin.getLogger().severe("Arena " + id + " has a pending backup but no saved offset; disabled for manual recovery: " + backup.getName());
            return;
        }
        ArenaData pendingCopy = template.createInstance(index, config.getInt(base + ".x"), config.getInt(base + ".z"));
        pendingCopy.setOperational(false);
        pendingCopy.setEnabled(false);
        copies.add(pendingCopy);
        saveArena(template);
        plugin.getLogger().warning("Recovered interrupted creation of " + pendingCopy.instanceKey() + " as disabled. Reduce the copy count in /varenamenu to restore its original destination blocks.");
    }

    private void disableArenaPreservingPending(ArenaData template) {
        template.setEnabled(false);
        template.setConfiguredEnabled(false);
        setInstancesEnabled(template.id(), false);
        config.set("arenas." + template.id() + ".enabled", false);
        try { config.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Could not persist emergency arena disable for " + template.id() + ": " + exception.getMessage()); }
    }

    /** Backups beyond the committed copy count are leftovers from completed shrink operations. */
    private void cleanupOrphanCopyBackups() {
        File[] files = schematicDirectory.listFiles((directory, name) -> name.matches(".+__copy_[0-9]+_original\\.schem"));
        if (files == null) return;
        for (File backup : files) {
            String name = backup.getName();
            int marker = name.lastIndexOf("__copy_");
            if (marker <= 0) continue;
            String id = name.substring(0, marker).toLowerCase(Locale.ROOT);
            int suffixStart = marker + "__copy_".length();
            int suffixEnd = name.indexOf("_original.schem", suffixStart);
            if (suffixEnd < 0 || !arenas.containsKey(id)) continue;
            int index;
            try { index = Integer.parseInt(name.substring(suffixStart, suffixEnd)); }
            catch (NumberFormatException exception) { continue; }
            if (config.contains("arenas." + id + ".copies.pending." + index)) continue;
            int committedCount = config.getInt("arenas." + id + ".copies.count", 1);
            if (index > committedCount) {
                try { Files.deleteIfExists(backup.toPath()); }
                catch (IOException exception) { plugin.getLogger().warning("Could not delete orphan backup " + backup.getName() + ": " + exception.getMessage()); }
            }
        }
    }

    public boolean validId(String id) { return id != null && VALID_ID.matcher(id).matches(); }

    public boolean create(String id) {
        if (!validId(id)) return false;
        String normalized = id.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(normalized)) return false;
        ArenaData arena = new ArenaData(normalized, null, null, false);
        arenas.put(normalized, arena);
        instances.put(normalized, new ArrayList<>(List.of(arena)));
        if (saveArena(arena)) return true;
        arenas.remove(normalized);
        instances.remove(normalized);
        return false;
    }

    public ArenaData get(String id) { return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT)); }

    public Collection<ArenaData> all() { return Collections.unmodifiableCollection(arenas.values()); }

    public int copyCount(String id) {
        if (id == null) return 0;
        List<ArenaData> copies = instances.get(id.toLowerCase(Locale.ROOT));
        return copies == null ? 0 : copies.size();
    }

    public List<ArenaData> instances(String id) {
        if (id == null) return List.of();
        List<ArenaData> copies = instances.get(id.toLowerCase(Locale.ROOT));
        return copies == null ? List.of() : Collections.unmodifiableList(copies);
    }

    public ArenaData mostRecentArenaWithoutBorder() {
        List<ArenaData> all = new ArrayList<>(arenas.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            ArenaData arena = all.get(i);
            if (!arena.hasRegion() && !isInUse(arena.id()) && !copiesBusy.contains(arena.id())) return arena;
        }
        return null;
    }

    public boolean setSpawn(String id, int point, Location location) {
        ArenaData arena = get(id);
        String normalized = normalize(id);
        if (arena == null || location == null || location.getWorld() == null || !arena.hasRegion()
                || isInUse(id) || copiesBusy.contains(normalized) || snapshotsBusy.contains(normalized)
                || deleting.contains(normalized) || recovering.contains(normalized)) return false;
        if (!arena.contains(location)) return false;
        if (point != 1 && point != 2) return false;

        Location previousOne = arena.spawnOne();
        Location previousTwo = arena.spawnTwo();
        boolean previousEnabled = arena.enabled();
        boolean previousConfiguredEnabled = arena.configuredEnabled();
        List<ArenaData> previousPhysical = new ArrayList<>(instances.getOrDefault(normalized, List.of(arena)));
        Map<ArenaData, Boolean> previousEnabledStates = new LinkedHashMap<>();
        for (ArenaData instance : previousPhysical) previousEnabledStates.put(instance, instance.enabled());

        if (point == 1) arena.setSpawnOne(location); else arena.setSpawnTwo(location);
        // A spawn change intentionally clears the configured enabled state until explicitly re-enabled.
        arena.setEnabled(false);
        arena.setConfiguredEnabled(false);
        rebuildInstances(normalized);
        setInstancesEnabled(normalized, false);
        if (saveArena(arena)) return true;

        arena.setSpawnOne(previousOne);
        arena.setSpawnTwo(previousTwo);
        arena.setEnabled(previousEnabled);
        arena.setConfiguredEnabled(previousConfiguredEnabled);
        instances.put(normalized, previousPhysical);
        previousEnabledStates.forEach(ArenaData::setEnabled);
        return false;
    }

    /** Describes why an arena cannot be enabled, for useful command and GUI feedback. */
    public String enableFailureReason(String id) {
        ArenaData arena = get(id);
        if (arena == null) return "arena does not exist";
        String normalized = normalize(id);
        if (isInUse(normalized)) return "the arena is currently hosting a match";
        if (copiesBusy.contains(normalized) || deleting.contains(normalized) || snapshotsBusy.contains(normalized)) {
            return "an arena copy, snapshot, or delete operation is still running";
        }
        if (recovering.contains(normalized)) return "startup recovery is still restoring this arena; wait for the recovery log";
        if (!arena.hasRegion()) return "the arena border is not saved";
        if (Bukkit.getWorld(arena.regionWorld()) == null) return "the arena world '" + arena.regionWorld() + "' is not loaded";
        if (arena.spawnOne() == null) return "standard spawn 1 is not saved";
        if (arena.spawnTwo() == null) return "standard spawn 2 is not saved";
        if (!arena.contains(arena.spawnOne())) return "standard spawn 1 is outside the saved border or in the wrong world";
        if (!arena.contains(arena.spawnTwo())) return "standard spawn 2 is outside the saved border or in the wrong world";
        if (!hasUsableSnapshot(arena.id())) return "the saved arena snapshot is missing or empty at plugins/NexPractice/arena-snapshots/" + normalized + ".schem; recapture the arena border and wait for snapshot saving to finish";
        List<ArenaData> physical = instances.getOrDefault(normalized, List.of());
        if (physical.isEmpty()) return "no physical arena instances are registered";
        int savedCount = Math.max(1, Math.min(MAX_INSTANCES, config.getInt("arenas." + normalized + ".copies.count", 1)));
        if (physical.size() != savedCount) return "arena copy metadata is incomplete (saved " + savedCount + ", loaded " + physical.size() + "); inspect arenas.yml before enabling";
        boolean usable = physical.stream().anyMatch(copy -> copy.operational() && copy.ready()
                && copy.regionWorld() != null && Bukkit.getWorld(copy.regionWorld()) != null);
        if (!usable) return "no operational arena instance has valid spawns; inspect the saved copy offsets and recovery logs";
        return null;
    }

    public boolean setEnabled(String id, boolean enabled) {
        ArenaData arena = get(id);
        String normalized = normalize(id);
        if (arena == null || copiesBusy.contains(normalized) || snapshotsBusy.contains(normalized)
                || deleting.contains(normalized) || recovering.contains(normalized) || isInUse(id)) return false;
        if (enabled && enableFailureReason(id) != null) return false;

        boolean previous = arena.enabled();
        boolean previousConfigured = arena.configuredEnabled();
        Map<ArenaData, Boolean> previousCopies = new LinkedHashMap<>();
        for (ArenaData copy : instances.getOrDefault(arena.id(), List.of())) previousCopies.put(copy, copy.enabled());
        arena.setEnabled(enabled);
        arena.setConfiguredEnabled(enabled);
        setInstancesEnabled(arena.id(), enabled);
        if (saveArena(arena)) return true;
        arena.setEnabled(previous);
        arena.setConfiguredEnabled(previousConfigured);
        previousCopies.forEach(ArenaData::setEnabled);
        return false;
    }

    /** Describes why the Party FFA flag itself could not be saved. Geometry is intentionally not checked here. */
    public String partyFfaFailureReason(String id) {
        ArenaData arena = get(id);
        if (arena == null) return "arena does not exist";
        String normalized = normalize(id);
        if (isInUse(normalized)) return "the arena is currently hosting a match";
        if (copiesBusy.contains(normalized) || deleting.contains(normalized) || snapshotsBusy.contains(normalized)) {
            return "an arena copy, snapshot, or delete operation is still running";
        }
        if (recovering.contains(normalized)) return "startup recovery is still restoring this arena";
        return null;
    }

    /** Persist Party FFA as an arena setting independently of whether its geometry is ready yet. */
    public boolean setPartyFfa(String id) {
        ArenaData arena = get(id);
        if (partyFfaFailureReason(id) != null) return false;
        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of());
        boolean previous = arena.partyFfa();
        Map<ArenaData, Boolean> previousCopies = new LinkedHashMap<>();
        for (ArenaData copy : physical) previousCopies.put(copy, copy.partyFfa());
        arena.setPartyFfaFlag(true);
        for (ArenaData copy : physical) copy.setPartyFfaFlag(true);
        if (saveArena(arena)) return true;
        arena.setPartyFfaFlag(previous);
        previousCopies.forEach(ArenaData::setPartyFfaFlag);
        return false;
    }


    public String bedfightFailureReason(String id) {
        ArenaData arena = get(id);
        if (arena == null) return "arena does not exist";
        if (isInUse(id)) return "the arena is currently hosting a match";
        if (copiesBusy.contains(normalize(id)) || snapshotsBusy.contains(normalize(id)) || recovering.contains(normalize(id)) || deleting.contains(normalize(id)))
            return "the arena is being copied, restored, edited, or deleted";
        if (!arena.hasRegion()) return "the arena border is not saved";
        if (Bukkit.getWorld(arena.regionWorld()) == null) return "the arena world '" + arena.regionWorld() + "' is not loaded";
        if (arena.spawnOne() == null || arena.spawnTwo() == null || !arena.ready()) return "both standard spawns must be saved inside the arena border";
        if (arena.bedA() == null || arena.bedB() == null) return "both Bedfight beds are not set; run /varena bedsetup " + arena.id();
        if (!arena.hasBedVoid() || !arena.regionWorld().equals(arena.bedVoidWorld())) return "the Bedfight void region is not set in the arena world; run /varena bedsetvoid " + arena.id();
        if (!arena.bedfightReady()) return "saved bed locations or void bounds are invalid or outside the configured arena world";
        if (!hasUsableSnapshot(id)) return "the arena snapshot is missing or empty; restore/recapture it before enabling Bedfight";
        List<ArenaData> physical = instances.getOrDefault(normalize(id), List.of());
        if (physical.isEmpty() || physical.stream().anyMatch(copy -> !copy.bedfightReady() || !copy.operational()))
            return "one or more physical copies have invalid Bedfight locations or are not operational";
        return null;
    }

    public boolean setBedfightEnabled(String id, boolean enabled) {
        ArenaData arena = get(id);
        if (arena == null || isInUse(id) || copiesBusy.contains(normalize(id)) || snapshotsBusy.contains(normalize(id))
                || recovering.contains(normalize(id)) || deleting.contains(normalize(id))) return false;
        if (enabled && bedfightFailureReason(id) != null) return false;
        Map<ArenaData, Boolean> previous = new LinkedHashMap<>();
        List<ArenaData> physical = instances.getOrDefault(normalize(id), List.of(arena));
        for (ArenaData copy : physical) previous.put(copy, copy.bedfightEnabled());
        arena.setBedfightEnabled(enabled);
        for (ArenaData copy : physical) copy.setBedfightEnabled(enabled);
        if (saveArena(arena)) return true;
        previous.forEach(ArenaData::setBedfightEnabled);
        return false;
    }

    public boolean setBedLocations(String id, Location bedA, Location bedB) {
        ArenaData arena = get(id);
        if (arena == null || bedA == null || bedB == null || bedA.getWorld() == null || bedB.getWorld() == null
                || !Objects.equals(bedA.getWorld(), bedB.getWorld()) || !arena.hasRegion()
                || !arena.regionWorld().equals(bedA.getWorld().getName()) || !arena.contains(bedA) || !arena.contains(bedB)
                || bedA.getBlockX() == bedB.getBlockX() && bedA.getBlockY() == bedB.getBlockY() && bedA.getBlockZ() == bedB.getBlockZ()
                || isInUse(id) || copiesBusy.contains(normalize(id)) || snapshotsBusy.contains(normalize(id))
                || recovering.contains(normalize(id)) || deleting.contains(normalize(id))) return false;
        Location oldA = arena.bedA();
        Location oldB = arena.bedB();
        boolean oldEnabled = arena.bedfightEnabled();
        arena.setBedLocations(bedA, bedB);
        // Changing either bed invalidates the previous Bedfight setup until an administrator
        // explicitly enables it again after the snapshot has been refreshed.
        arena.setBedfightEnabled(false);
        rebuildInstances(normalize(id));
        if (saveArena(arena)) return true;
        arena.setBedLocations(oldA, oldB);
        arena.setBedfightEnabled(oldEnabled);
        rebuildInstances(normalize(id));
        return false;
    }

    public boolean setBedVoidRegion(String id, Location first, Location second) {
        ArenaData arena = get(id);
        if (arena == null || first == null || second == null || first.getWorld() == null || second.getWorld() == null
                || !Objects.equals(first.getWorld(), second.getWorld()) || !arena.hasRegion()
                || !arena.regionWorld().equals(first.getWorld().getName()) || isInUse(id)
                || copiesBusy.contains(normalize(id)) || snapshotsBusy.contains(normalize(id))
                || recovering.contains(normalize(id)) || deleting.contains(normalize(id))) return false;
        String oldWorld = arena.bedVoidWorld();
        int[] old = {arena.bedVoidMinX(), arena.bedVoidMinY(), arena.bedVoidMinZ(), arena.bedVoidMaxX(), arena.bedVoidMaxY(), arena.bedVoidMaxZ()};
        boolean hadOld = arena.hasBedVoid();
        boolean oldEnabled = arena.bedfightEnabled();
        arena.setBedVoid(first.getWorld().getName(), first.getBlockX(), first.getBlockY(), first.getBlockZ(),
                second.getBlockX(), second.getBlockY(), second.getBlockZ());
        arena.setBedfightEnabled(false);
        rebuildInstances(normalize(id));
        if (saveArena(arena)) return true;
        if (hadOld) arena.setBedVoid(oldWorld, old[0], old[1], old[2], old[3], old[4], old[5]);
        arena.setBedfightEnabled(oldEnabled);
        rebuildInstances(normalize(id));
        return false;
    }

    /** Capture the just-configured beds into the reusable baseline without clearing spawns or other arena settings. */
    public boolean refreshSnapshotPreservingSettings(String id, Consumer<Boolean> completion) {
        ArenaData arena = get(id);
        String normalized = normalize(id);
        World world = arena == null || arena.regionWorld() == null ? null : Bukkit.getWorld(arena.regionWorld());
        if (arena == null || world == null || !arena.hasRegion() || isInUse(id) || !copiesBusy.isEmpty()
                || deleting.contains(normalized) || recovering.contains(normalized) || !snapshotsBusy.add(normalized)) return false;
        BlockVector3 min = BlockVector3.at(arena.minX(), arena.minY(), arena.minZ());
        BlockVector3 max = BlockVector3.at(arena.maxX(), arena.maxY(), arena.maxZ());
        if (arena.regionVolume() <= 0 || arena.regionVolume() > MAX_SNAPSHOT_BLOCKS) {
            snapshotsBusy.remove(normalized);
            return false;
        }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        Region region = new CuboidRegion(weWorld, min, max);
        File output = snapshotFile(normalized);
        File temporary = new File(schematicDirectory, normalized + ".schem.tmp");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean captured = false;
            try {
                BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
                clipboard.setOrigin(min);
                ForwardExtentCopy copy = new ForwardExtentCopy(weWorld, region, clipboard, min);
                copy.setCopyingEntities(false);
                Operations.complete(copy);
                try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(temporary))) {
                    writer.write(clipboard);
                }
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                captured = true;
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not update Bedfight baseline snapshot for " + normalized + ": " + exception.getMessage());
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            }
            final boolean saved = captured;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!saved) {
                    snapshotsBusy.remove(normalized);
                    completion.accept(false);
                    return;
                }
                restoreUpdatedSnapshotCopies(normalized, new ArrayList<>(instances.getOrDefault(normalized, List.of(arena))), 1, true, completion);
            });
        });
        return true;
    }

    private void restoreUpdatedSnapshotCopies(String id, List<ArenaData> copies, int index, boolean allRestored, Consumer<Boolean> completion) {
        if (index >= copies.size()) {
            ArenaData template = get(id);
            if (template != null && !allRestored) {
                template.setEnabled(false);
                setInstancesEnabled(id, false);
            }
            boolean persisted = template != null && saveArena(template);
            snapshotsBusy.remove(id);
            completion.accept(allRestored && persisted);
            return;
        }
        ArenaData copy = copies.get(index);
        restoreSnapshot(copy, ok -> {
            if (!ok) {
                copy.setOperational(false);
                copy.setEnabled(false);
            }
            restoreUpdatedSnapshotCopies(id, copies, index + 1, allRestored && ok, completion);
        });
    }

    public boolean setKitRestricted(String id, boolean restricted) {
        ArenaData arena = get(id);
        if (arena == null || isInUse(id) || copiesBusy.contains(normalize(id)) || snapshotsBusy.contains(normalize(id))
                || deleting.contains(normalize(id)) || recovering.contains(normalize(id))) return false;
        // Kit eligibility is independent of arena availability. Enabling specific-kit mode
        // starts with an empty allow-list, but must not disable the arena while the admin
        // selects the first allowed kit in the same GUI interaction.
        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of(arena));
        Map<ArenaData, Boolean> previous = new LinkedHashMap<>();
        for (ArenaData copy : physical) previous.put(copy, copy.kitRestricted());
        arena.setKitRestricted(restricted);
        for (ArenaData copy : physical) copy.setKitRestricted(restricted);
        if (saveArena(arena)) return true;
        previous.forEach(ArenaData::setKitRestricted);
        return false;
    }

    public boolean toggleAllowedKit(String id, String kitId) {
        ArenaData arena = get(id);
        if (arena == null || kitId == null || isInUse(id) || copiesBusy.contains(normalize(id))
                || snapshotsBusy.contains(normalize(id)) || deleting.contains(normalize(id)) || recovering.contains(normalize(id))) return false;
        // Removing the final allowed kit means no queue can currently use this arena,
        // not that the arena itself should be disabled.
        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of(arena));
        Map<ArenaData, Set<String>> previous = new LinkedHashMap<>();
        for (ArenaData copy : physical) previous.put(copy, new LinkedHashSet<>(copy.allowedKits()));
        arena.toggleAllowedKit(kitId);
        for (ArenaData copy : physical) copy.setAllowedKits(arena.allowedKits());
        if (saveArena(arena)) return true;
        previous.forEach(ArenaData::setAllowedKits);
        return false;
    }

    public boolean setAllowedKits(String id, Collection<String> kitIds) {
        ArenaData arena = get(id);
        if (arena == null || isInUse(id) || copiesBusy.contains(normalize(id)) || snapshotsBusy.contains(normalize(id))
                || deleting.contains(normalize(id)) || recovering.contains(normalize(id))) return false;
        // Keep availability separate from the kit allow-list. An empty list blocks all
        // kit queues until an admin selects a kit, but does not force a disable/enable cycle.
        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of(arena));
        Map<ArenaData, Set<String>> previous = new LinkedHashMap<>();
        for (ArenaData copy : physical) previous.put(copy, new LinkedHashSet<>(copy.allowedKits()));
        arena.setAllowedKits(kitIds);
        for (ArenaData copy : physical) copy.setAllowedKits(arena.allowedKits());
        if (saveArena(arena)) return true;
        previous.forEach(ArenaData::setAllowedKits);
        return false;
    }


    /** Atomically switch to specific-kit mode and allow only the supplied kit(s). */
    public boolean setSpecificAllowedKits(String id, Collection<String> kitIds) {
        ArenaData arena = get(id);
        if (arena == null || isInUse(id) || copiesBusy.contains(normalize(id))
                || recovering.contains(normalize(id)) || deleting.contains(normalize(id))
                || snapshotsBusy.contains(normalize(id))) return false;
        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of(arena));
        Map<ArenaData, Boolean> oldRestricted = new LinkedHashMap<>();
        Map<ArenaData, Set<String>> oldAllowed = new LinkedHashMap<>();
        for (ArenaData copy : physical) {
            oldRestricted.put(copy, copy.kitRestricted());
            oldAllowed.put(copy, new LinkedHashSet<>(copy.allowedKits()));
        }
        arena.setKitRestricted(true);
        arena.setAllowedKits(kitIds);
        for (ArenaData copy : physical) {
            copy.setKitRestricted(true);
            copy.setAllowedKits(arena.allowedKits());
        }
        if (saveArena(arena)) return true;
        oldRestricted.forEach(ArenaData::setKitRestricted);
        oldAllowed.forEach(ArenaData::setAllowedKits);
        return false;
    }

    /** Set a kit's allowed state idempotently; repeated/stale GUI clicks cannot toggle it back. */
    public boolean setKitAllowed(String id, String kitId, boolean allowed) {
        ArenaData arena = get(id);
        String normalized = normalize(id);
        if (arena == null || kitId == null || kitId.isBlank() || !arena.kitRestricted()
                || isInUse(id) || copiesBusy.contains(normalized) || recovering.contains(normalized)
                || deleting.contains(normalized) || snapshotsBusy.contains(normalized)) return false;
        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of(arena));
        Map<ArenaData, Set<String>> previous = new LinkedHashMap<>();
        for (ArenaData copy : physical) previous.put(copy, new LinkedHashSet<>(copy.allowedKits()));
        Set<String> next = new LinkedHashSet<>(arena.allowedKits());
        String normalizedKit = kitId.toLowerCase(Locale.ROOT);
        if (allowed) next.add(normalizedKit); else next.remove(normalizedKit);
        arena.setAllowedKits(next);
        for (ArenaData copy : physical) {
            copy.setKitRestricted(true);
            copy.setAllowedKits(next);
        }
        if (saveArena(arena)) return true;
        previous.forEach(ArenaData::setAllowedKits);
        return false;
    }

    /** Create or remove physical copies of a template. Copy count includes the original selected region. */
    public boolean resizeCopies(String id, int desiredCount, Consumer<Boolean> completion) {
        String normalized = normalize(id);
        ArenaData arena = get(normalized);
        if (arena == null || desiredCount < 1 || desiredCount > MAX_INSTANCES || isInUse(normalized)
                || copiesBusy.contains(normalized) || !copiesBusy.isEmpty() || recovering.contains(normalized)) return false;
        if (desiredCount > 1 && (!arena.ready() || !hasUsableSnapshot(normalized))) return false;
        if (desiredCount != copyCount(normalized) && (!arena.hasRegion() || !hasUsableSnapshot(normalized))) return false;
        copiesBusy.add(normalized);
        resizeStep(normalized, desiredCount, success -> {
            copiesBusy.remove(normalized);
            completion.accept(success);
        });
        return true;
    }

    private void resizeStep(String id, int desiredCount, Consumer<Boolean> completion) {
        ArenaData template = get(id);
        List<ArenaData> physical = instances.get(id);
        if (template == null || physical == null) { completion.accept(false); return; }
        int count = physical.size();
        if (count == desiredCount) { completion.accept(saveArena(template)); return; }

        if (count < desiredCount) {
            int nextIndex = count + 1;
            int[] offset = findPlacementOffset(template);
            if (offset == null) {
                plugin.getLogger().warning("No safe arena-copy coordinates found for " + id + ".");
                completion.accept(false);
                return;
            }
            ArenaData newInstance = template.createInstance(nextIndex, offset[0], offset[1]);
            if (!persistPendingCopy(template, newInstance)) {
                plugin.getLogger().severe("Could not persist pending copy transaction for " + newInstance.instanceKey() + "; no world blocks were changed.");
                completion.accept(false);
                return;
            }
            createPhysicalCopy(template, newInstance, success -> {
                if (!success) {
                    // On rollback success no clone was committed, so clear the marker. If rollback
                    // failed, createPhysicalCopy has already recorded a disabled clone and backup.
                    if (physical.stream().noneMatch(existing -> existing.instanceNumber() == newInstance.instanceNumber())) saveArena(template);
                    completion.accept(false);
                    return;
                }
                physical.add(newInstance);
                if (!saveArena(template)) {
                    newInstance.setOperational(false);
                    newInstance.setEnabled(false);
                    saveArena(template);
                    plugin.getLogger().severe("Copy pasted but metadata could not be committed for " + newInstance.instanceKey() + "; it is disabled and its destination backup is retained.");
                    completion.accept(false);
                    return;
                }
                resizeStep(id, desiredCount, completion);
            });
            return;
        }

        ArenaData last = physical.get(physical.size() - 1);
        if (last.instanceNumber() == 1 || inUse.contains(last.instanceKey())) {
            completion.accept(false);
            return;
        }
        restoreCopyBackground(last, restored -> {
            if (!restored) {
                plugin.getLogger().severe("Could not restore the original world blocks for " + last.instanceKey() + "; the instance was retained and will not be removed.");
                completion.accept(false);
                return;
            }
            physical.remove(physical.size() - 1);
            if (!saveArena(template)) {
                physical.add(last);
                saveArena(template);
                plugin.getLogger().severe("Could not persist removal of " + last.instanceKey() + "; its destination backup is retained and the copy stays tracked.");
                completion.accept(false);
                return;
            }
            try { Files.deleteIfExists(copyBackgroundFile(template.id(), last.instanceNumber()).toPath()); }
            catch (IOException exception) { plugin.getLogger().warning("Copy was removed, but its orphaned backup could not be deleted: " + exception.getMessage()); }
            resizeStep(id, desiredCount, completion);
        });
    }

    private int[] findPlacementOffset(ArenaData template) {
        World world = Bukkit.getWorld(template.regionWorld());
        if (world == null) return null;
        long width = (long) template.maxX() - template.minX() + 1L;
        long depth = (long) template.maxZ() - template.minZ() + 1L;
        long stepX = width + INSTANCE_GAP;
        long stepZ = depth + INSTANCE_GAP;
        if (stepX <= 0 || stepZ <= 0) return null;

        // Search a square spiral around the template instead of only placing copies at +X/+Z.
        // This still works when the source arena is close to a positive world-border edge.
        int checked = 0;
        for (int ring = 1; checked < MAX_PLACEMENT_ATTEMPTS; ring++) {
            for (int edge = -ring; edge <= ring && checked < MAX_PLACEMENT_ATTEMPTS; edge++) {
                int[][] candidates = {
                        {ring, edge}, {-ring, edge}, {edge, ring}, {edge, -ring}
                };
                for (int[] grid : candidates) {
                    if (checked++ >= MAX_PLACEMENT_ATTEMPTS) return null;
                    long dxLong = (long) grid[0] * stepX;
                    long dzLong = (long) grid[1] * stepZ;
                    if (dxLong < Integer.MIN_VALUE || dxLong > Integer.MAX_VALUE
                            || dzLong < Integer.MIN_VALUE || dzLong > Integer.MAX_VALUE) continue;
                    long minX = (long) template.minX() + dxLong;
                    long maxX = (long) template.maxX() + dxLong;
                    long minZ = (long) template.minZ() + dzLong;
                    long maxZ = (long) template.maxZ() + dzLong;
                    if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
                            || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) continue;
                    Location minimum = new Location(world, minX, template.minY(), minZ);
                    Location maximum = new Location(world, maxX, template.maxY(), maxZ);
                    if (!world.getWorldBorder().isInside(minimum) || !world.getWorldBorder().isInside(maximum)) continue;
                    if (overlapsExisting(template.regionWorld(), (int) minX, template.minY(), (int) minZ,
                            (int) maxX, template.maxY(), (int) maxZ)) continue;
                    return new int[]{(int) dxLong, (int) dzLong};
                }
            }
        }
        return null;
    }

    private boolean overlapsExisting(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (List<ArenaData> list : instances.values()) {
            for (ArenaData other : list) {
                if (!other.hasRegion() || !world.equals(other.regionWorld())) continue;
                // Instances are deliberately separated in X/Z even if two templates have
                // different Y ranges. Vertical stacking can still allow cross-map projectiles,
                // visibility confusion, and accidental interactions, so don't place copies above
                // or below another registered arena footprint.
                boolean horizontalOverlap = minX <= other.maxX() && maxX >= other.minX()
                        && minZ <= other.maxZ() && maxZ >= other.minZ();
                if (horizontalOverlap) return true;
            }
        }
        return false;
    }

    private boolean persistPendingCopy(ArenaData template, ArenaData copy) {
        String pendingPath = "arenas." + template.id() + ".copies.pending." + copy.instanceNumber();
        config.set(pendingPath + ".x", copy.offsetX());
        config.set(pendingPath + ".z", copy.offsetZ());
        try {
            config.save(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not persist pending instance data for " + copy.instanceKey() + ": " + exception.getMessage());
            return false;
        }
    }

    private void createPhysicalCopy(ArenaData template, ArenaData copy, Consumer<Boolean> completion) {
        World world = Bukkit.getWorld(template.regionWorld());
        File source = snapshotFile(template.id());
        File background = copyBackgroundFile(template.id(), copy.instanceNumber());
        if (world == null || !source.isFile()) { completion.accept(false); return; }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        BlockVector3 targetMin = BlockVector3.at(copy.minX(), copy.minY(), copy.minZ());
        BlockVector3 targetMax = BlockVector3.at(copy.maxX(), copy.maxY(), copy.maxZ());
        File temporary = new File(schematicDirectory, background.getName() + ".tmp");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean backgroundSaved = false;
            boolean success = false;
            try {
                saveRegionSchematic(weWorld, targetMin, targetMax, temporary, background);
                backgroundSaved = true;
                pasteSchematic(source, weWorld, targetMin);
                success = true;
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not create " + copy.instanceKey() + ": " + exception.getMessage());
                if (backgroundSaved) {
                    boolean restoredBackground = false;
                    try {
                        pasteSchematic(background, weWorld, targetMin);
                        restoredBackground = true;
                    } catch (Exception restoreError) {
                        // Keep the original-background schematic on disk. The disabled, tracked
                        // instance can be removed later by Apply Instances, which retries restoration.
                        plugin.getLogger().severe("Could not roll back failed copy creation for " + copy.instanceKey()
                                + ": " + restoreError.getMessage() + ". Backup retained at " + background.getAbsolutePath());
                    }
                    if (restoredBackground) {
                        try { Files.deleteIfExists(background.toPath()); } catch (IOException ignored) { }
                    }
                }
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            }
            final boolean copied = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!copied && background.isFile()) {
                    // There is still a durable backup, so retain a disabled instance entry rather
                    // than allowing the coordinates to be selected for another copy or match.
                    List<ArenaData> physical = instances.get(template.id());
                    if (physical != null && physical.size() + 1 == copy.instanceNumber()) {
                        copy.setOperational(false);
                        copy.setEnabled(false);
                        physical.add(copy);
                        saveArena(template);
                    }
                }
                completion.accept(copied);
            });
        });
    }

    private void saveRegionSchematic(com.sk89q.worldedit.world.World world, BlockVector3 min, BlockVector3 max,
                                     File temporary, File output) throws Exception {
        Region region = new CuboidRegion(world, min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);
        ForwardExtentCopy copy = new ForwardExtentCopy(world, region, clipboard, min);
        copy.setCopyingEntities(false);
        Operations.complete(copy);
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(temporary))) {
            writer.write(clipboard);
        }
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void pasteSchematic(File schematic, com.sk89q.worldedit.world.World world, BlockVector3 target) throws Exception {
        ClipboardFormat format = ClipboardFormats.findByFile(schematic);
        if (format == null) throw new IOException("Unsupported snapshot format: " + schematic.getName());
        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(new FileInputStream(schematic))) {
            clipboard = reader.read();
        }
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(world)) {
            Operation operation = new ClipboardHolder(clipboard).createPaste(editSession)
                    .to(target).ignoreAirBlocks(false).build();
            Operations.complete(operation);
        }
    }

    private void restoreCopyBackground(ArenaData copy, Consumer<Boolean> completion) {
        World world = Bukkit.getWorld(copy.regionWorld());
        File background = copyBackgroundFile(copy.id(), copy.instanceNumber());
        if (world == null || !background.isFile()) { completion.accept(false); return; }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        BlockVector3 target = BlockVector3.at(copy.minX(), copy.minY(), copy.minZ());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            try {
                pasteSchematic(background, weWorld, target);
                success = true;
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not restore original world blocks for " + copy.instanceKey() + ": " + exception.getMessage());
            }
            final boolean restored = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                // The caller deletes the backup only after the reduced count has been persisted.
                completion.accept(restored);
            });
        });
    }

    public boolean renameArena(String oldId, String newId) {
        String oldKey = normalize(oldId);
        String newKey = normalize(newId);
        ArenaData arena = get(oldKey);
        if (arena != null && oldKey.equals(newKey)) return true;
        if (arena == null || !validId(newKey) || arenas.containsKey(newKey) || isInUse(oldKey)
                || copiesBusy.contains(oldKey) || !copiesBusy.isEmpty() || deleting.contains(oldKey) || recovering.contains(oldKey)) return false;
        if (snapshotsBusy.contains(oldKey)) return false;

        List<FileMove> moves = new ArrayList<>();
        File oldSnapshot = snapshotFile(oldKey);
        File newSnapshot = snapshotFile(newKey);
        if (oldSnapshot.exists()) moves.add(new FileMove(oldSnapshot, newSnapshot));
        for (ArenaData copy : instances.getOrDefault(oldKey, List.of())) {
            if (copy.instanceNumber() <= 1) continue;
            File from = copyBackgroundFile(oldKey, copy.instanceNumber());
            File to = copyBackgroundFile(newKey, copy.instanceNumber());
            if (from.exists()) moves.add(new FileMove(from, to));
        }
        if (moves.stream().anyMatch(move -> move.to().exists())) return false;
        List<FileMove> completedMoves = new ArrayList<>();
        try {
            for (FileMove move : moves) {
                Files.move(move.from().toPath(), move.to().toPath(), StandardCopyOption.REPLACE_EXISTING);
                completedMoves.add(move);
            }
        } catch (IOException exception) {
            Collections.reverse(completedMoves);
            for (FileMove move : completedMoves) {
                try { Files.move(move.to().toPath(), move.from().toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ignored) { }
            }
            plugin.getLogger().warning("Arena rename failed because a snapshot could not be renamed: " + exception.getMessage());
            return false;
        }

        List<ArenaData> oldInstances = instances.getOrDefault(oldKey, List.of());
        Map<Integer, CopyPosition> offsets = new LinkedHashMap<>();
        for (ArenaData copy : oldInstances) if (copy.instanceNumber() > 1) {
            offsets.put(copy.instanceNumber(), new CopyPosition(copy.offsetX(), copy.offsetZ(), copy.operational()));
        }
        arenas.remove(oldKey);
        instances.remove(oldKey);
        config.set("arenas." + oldKey, null);
        arena.renameId(newKey);
        arenas.put(newKey, arena);
        List<ArenaData> renamedCopies = new ArrayList<>();
        renamedCopies.add(arena);
        for (Map.Entry<Integer, CopyPosition> entry : offsets.entrySet()) {
            CopyPosition position = entry.getValue();
            ArenaData copy = arena.createInstance(entry.getKey(), position.x(), position.z());
            copy.setOperational(position.operational());
            copy.setEnabled(arena.enabled() && position.operational());
            renamedCopies.add(copy);
        }
        instances.put(newKey, renamedCopies);
        saveArena(arena);
        return true;
    }

    public boolean deleteArena(String id, Consumer<Boolean> completion) {
        String normalized = normalize(id);
        ArenaData arena = get(normalized);
        if (arena == null || isInUse(normalized) || copiesBusy.contains(normalized) || !copiesBusy.isEmpty()
                || deleting.contains(normalized) || snapshotsBusy.contains(normalized) || recovering.contains(normalized)) return false;
        deleting.add(normalized);
        arena.setEnabled(false);
        arena.setConfiguredEnabled(false);
        setInstancesEnabled(normalized, false);
        saveArena(arena);
        boolean resizeAccepted = resizeCopies(normalized, 1, resized -> {
            if (!resized) {
                deleting.remove(normalized);
                completion.accept(false);
                return;
            }
            arenas.remove(normalized);
            instances.remove(normalized);
            config.set("arenas." + normalized, null);
            try { config.save(file); }
            catch (IOException exception) {
                plugin.getLogger().severe("Could not delete arena config " + normalized + ": " + exception.getMessage());
                deleting.remove(normalized);
                completion.accept(false);
                return;
            }
            try { Files.deleteIfExists(snapshotFile(normalized).toPath()); }
            catch (IOException exception) { plugin.getLogger().warning("Could not delete snapshot for arena " + normalized + ": " + exception.getMessage()); }
            File[] leftovers = schematicDirectory.listFiles((directory, name) -> name.startsWith(normalized + "__copy_") && name.endsWith("_original.schem"));
            if (leftovers != null) for (File leftover : leftovers) {
                try { Files.deleteIfExists(leftover.toPath()); }
                catch (IOException exception) { plugin.getLogger().warning("Could not delete backup " + leftover.getName() + ": " + exception.getMessage()); }
            }
            deleting.remove(normalized);
            completion.accept(true);
        });
        if (!resizeAccepted) { deleting.remove(normalized); return false; }
        return true;
    }

    private void rebuildInstances(String id) {
        ArenaData root = get(id);
        if (root == null) return;
        List<ArenaData> old = instances.getOrDefault(root.id(), List.of(root));
        List<ArenaData> rebuilt = new ArrayList<>();
        rebuilt.add(root);
        for (ArenaData previous : old) {
            if (previous.instanceNumber() <= 1) continue;
            ArenaData copy = root.createInstance(previous.instanceNumber(), previous.offsetX(), previous.offsetZ());
            copy.setEnabled(root.enabled() && previous.operational());
            copy.setOperational(previous.operational());
            rebuilt.add(copy);
        }
        instances.put(root.id(), rebuilt);
    }

    private void setInstancesEnabled(String id, boolean enabled) {
        for (ArenaData copy : instances.getOrDefault(normalize(id), List.of())) {
            copy.setEnabled(enabled && copy.operational() && copy.ready()
                    && copy.regionWorld() != null && Bukkit.getWorld(copy.regionWorld()) != null);
        }
    }

    /** Normal queues can use any compatible arena, including templates marked for party fights. */
    public ArenaData acquireAvailable() { return acquireAvailable(null, false, false); }
    /** Direct duels intentionally do not enter the queue-only Bedfight mode. */
    public ArenaData acquireAvailable(String kitId) { return acquireAvailable(kitId, false, false); }
    /** Solo queues may use configured Bedfight arenas; a reserved arena's mode is carried into Match. */
    public ArenaData acquireAvailableForQueue(String kitId) { return acquireAvailable(kitId, false, true); }
    public ArenaData acquireAvailablePartyFfa() { return acquireAvailable(null, true, false); }
    public ArenaData acquireAvailablePartyFfa(String kitId) { return acquireAvailable(kitId, true, false); }
    /** Party Split uses two configured spawns and may use Bedfight-enabled arenas; FFA may not. */
    public ArenaData acquireAvailableForPartySplit(String kitId) { return acquireAvailable(kitId, false, true); }

    private ArenaData acquireAvailable(String kitId, boolean partyFfaOnly, boolean queueMatch) {
        if (startupRecoveryPending) return null;
        for (Map.Entry<String, List<ArenaData>> entry : instances.entrySet()) {
            String templateId = entry.getKey();
            ArenaData template = arenas.get(templateId);
            if (template == null || !template.enabled() || deleting.contains(templateId) || copiesBusy.contains(templateId)
                    || recovering.contains(templateId) || !hasUsableSnapshot(templateId)) continue;
            for (ArenaData arena : entry.getValue()) {
                if (!arena.ready() || !arena.operational() || !arena.enabled()
                        || inUse.contains(arena.instanceKey()) || !arena.supportsKit(kitId)) continue;
                if (arena.bedfightEnabled() && (!queueMatch || !arena.bedfightReady())) continue;
                if (partyFfaOnly && (!arena.partyFfa() || !partyFfaSpawnsReady(arena) || arena.bedfightEnabled())) continue;
                inUse.add(arena.instanceKey());
                return arena;
            }
        }
        return null;
    }

    /** Returns N/S/W/E spawns at radius five within the physical copy's own cuboid. */
    public List<Location> getPartyFfaSpawns(ArenaData arena) {
        if (arena == null || !arena.partyFfa() || !arena.hasPartyFfaBounds()) return List.of();
        World world = Bukkit.getWorld(arena.regionWorld());
        if (world == null) return List.of();
        int centerX = arena.minX() + (arena.maxX() - arena.minX()) / 2;
        int centerZ = arena.minZ() + (arena.maxZ() - arena.minZ()) / 2;
        double targetY = arena.spawnOne() != null && arena.spawnTwo() != null
                ? (arena.spawnOne().getY() + arena.spawnTwo().getY()) / 2.0
                : arena.minY() + 1.0;
        int[][] coords = {{centerX, centerZ - 5}, {centerX, centerZ + 5}, {centerX - 5, centerZ}, {centerX + 5, centerZ}};
        float[] yaw = {0.0f, 180.0f, -90.0f, 90.0f};
        List<Location> points = new ArrayList<>(4);
        for (int i = 0; i < coords.length; i++) {
            int x = coords[i][0], z = coords[i][1];
            if (x < arena.minX() || x > arena.maxX() || z < arena.minZ() || z > arena.maxZ()) return List.of();
            Location safe = findSafePartySpawn(arena, world, x, z, targetY, yaw[i]);
            if (safe == null) return List.of();
            points.add(safe);
        }
        return Collections.unmodifiableList(points);
    }

    private boolean partyFfaSpawnsReady(ArenaData arena) { return getPartyFfaSpawns(arena).size() == 4; }

    private Location findSafePartySpawn(ArenaData arena, World world, int x, int z, double targetFeetY, float yaw) {
        int minimumFeetY = Math.max(arena.minY() + 1, world.getMinHeight() + 1);
        int maximumFeetY = Math.min(arena.maxY() - 1, world.getMaxHeight() - 2);
        if (minimumFeetY > maximumFeetY) return null;
        List<Integer> candidates = new ArrayList<>();
        for (int y = minimumFeetY; y <= maximumFeetY; y++) candidates.add(y);
        candidates.sort(Comparator.comparingDouble(y -> Math.abs(y - targetFeetY)));
        for (int feetY : candidates) {
            org.bukkit.block.Block floor = world.getBlockAt(x, feetY - 1, z);
            org.bukkit.block.Block feet = world.getBlockAt(x, feetY, z);
            org.bukkit.block.Block head = world.getBlockAt(x, feetY + 1, z);
            if (floor.getType().isSolid() && !floor.isLiquid() && feet.isPassable() && head.isPassable()) {
                return new Location(world, x + 0.5, feetY, z + 0.5, yaw, 0.0f);
            }
        }
        return null;
    }

    public boolean isInUse(String id) {
        if (id == null) return false;
        String normalized = normalize(id);
        if (inUse.contains(normalized)) return true;
        for (String reservation : inUse) {
            if (reservation.startsWith(normalized + "::")) return true;
        }
        return false;
    }

    /** Releases only the exact physical instance used by a match. */
    public void release(ArenaData arena) { if (arena != null) inUse.remove(arena.instanceKey()); }

    /** Kept for compatibility; use release(ArenaData) for matches running on copies. */
    public void release(String id) { if (id != null) inUse.remove(normalize(id) + "::1"); }

    public boolean isCapturing(String id) { return id != null && snapshotsBusy.contains(normalize(id)); }
    public boolean copiesBusy(String id) { return id != null && copiesBusy.contains(normalize(id)); }
    public boolean isRecovering(String id) { return id != null && recovering.contains(normalize(id)); }
    public static int maxInstances() { return MAX_INSTANCES; }

    /** Captures the original template baseline; recapture requires reducing the template to one copy first. */
    public boolean captureSnapshot(String arenaId, World bukkitWorld, BlockVector3 first, BlockVector3 second,
                                   Consumer<Boolean> completion) {
        ArenaData arena = get(arenaId);
        String id = normalize(arenaId);
        if (arena == null || bukkitWorld == null || first == null || second == null || isInUse(id)
                || copyCount(id) > 1 || !copiesBusy.isEmpty() || deleting.contains(id) || recovering.contains(id)) return false;
        if (!snapshotsBusy.add(id)) return false;

        BlockVector3 min = BlockVector3.at(Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z()));
        BlockVector3 max = BlockVector3.at(Math.max(first.x(), second.x()), Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
        long volume = ((long) max.x() - min.x() + 1L) * ((long) max.y() - min.y() + 1L) * ((long) max.z() - min.z() + 1L);
        if (volume <= 0 || volume > MAX_SNAPSHOT_BLOCKS) {
            snapshotsBusy.remove(id);
            return false;
        }

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
        Region region = new CuboidRegion(weWorld, min, max);
        File output = snapshotFile(id);
        File temporary = new File(schematicDirectory, id + ".schem.tmp");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            try {
                if (!schematicDirectory.exists() && !schematicDirectory.mkdirs()) throw new IOException("Could not create snapshot directory.");
                BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
                clipboard.setOrigin(min);
                ForwardExtentCopy copy = new ForwardExtentCopy(weWorld, region, clipboard, min);
                copy.setCopyingEntities(false);
                Operations.complete(copy);
                try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(temporary))) {
                    writer.write(clipboard);
                }
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                success = true;
            } catch (Exception exception) {
                plugin.getLogger().severe("Could not save arena snapshot for " + id + ": " + exception.getMessage());
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            }
            final boolean saved = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                snapshotsBusy.remove(id);
                boolean persisted = false;
                if (saved) {
                    arena.setRegion(bukkitWorld.getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
                    arena.setSpawnOne(null);
                    arena.setSpawnTwo(null);
                    config.set("arenas." + id + ".spawn1", null);
                    config.set("arenas." + id + ".spawn2", null);
                    arena.setEnabled(false);
                    arena.setConfiguredEnabled(false);
                    instances.put(id, new ArrayList<>(List.of(arena)));
                    persisted = saveArena(arena);
                    if (!persisted) plugin.getLogger().severe("Snapshot for arena " + id + " was written, but its metadata could not be saved to arenas.yml. Check file permissions and console before configuring spawns.");
                }
                completion.accept(saved && persisted);
            });
        });
        return true;
    }

    /** Restores the original template baseline at this physical instance's translated region. */
    public void restoreSnapshot(ArenaData arena, Consumer<Boolean> completion) {
        if (arena == null || !arena.hasRegion()) { completion.accept(false); return; }
        World bukkitWorld = Bukkit.getWorld(arena.regionWorld());
        File schematic = snapshotFile(arena.id());
        if (bukkitWorld == null || !hasUsableSnapshot(arena.id())) {
            plugin.getLogger().warning("Cannot restore arena " + arena.instanceKey() + ": world is not loaded or template snapshot is missing/empty.");
            completion.accept(false);
            return;
        }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
        BlockVector3 target = BlockVector3.at(arena.minX(), arena.minY(), arena.minZ());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            try { pasteSchematic(schematic, weWorld, target); success = true; }
            catch (Exception exception) { plugin.getLogger().severe("Could not restore arena " + arena.instanceKey() + ": " + exception.getMessage()); }
            final boolean restored = success;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (restored) clearDroppedItems(arena);
                completion.accept(restored);
            });
        });
    }

    /** Remove block/item drops from this physical arena instance, including items that fell below its Y bounds. */
    private void clearDroppedItems(ArenaData arena) {
        if (arena == null || !arena.hasRegion()) return;
        World world = Bukkit.getWorld(arena.regionWorld());
        if (world == null) return;
        List<org.bukkit.entity.Entity> dropsToRemove = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            if (!(entity instanceof org.bukkit.entity.Item)) continue;
            Location location = entity.getLocation();
            if (location.getBlockX() >= arena.minX() && location.getBlockX() <= arena.maxX()
                    && location.getBlockZ() >= arena.minZ() && location.getBlockZ() <= arena.maxZ()) {
                dropsToRemove.add(entity);
            }
        }
        for (org.bukkit.entity.Entity entity : dropsToRemove) entity.remove();
    }

    private Location readSavedLocation(String path) {
        ConfigurationSection location = config.getConfigurationSection(path);
        if (location == null) return null;
        String worldName = location.getString("world");
        if (worldName == null || worldName.isBlank()) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = location.getDouble("x", Double.NaN);
        double y = location.getDouble("y", Double.NaN);
        double z = location.getDouble("z", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        float yaw = (float) location.getDouble("yaw", 0.0);
        float pitch = (float) location.getDouble("pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    /** Store locations as plain world-name/coordinate keys, never Bukkit serialized objects. */
    private void writeSavedLocation(String path, Location location) {
        if (location == null || location.getWorld() == null) {
            // A missing world must not erase the saved coordinates. Operations that intentionally
            // clear a spawn explicitly clear the path before calling saveArena.
            if (!config.contains(path + ".world")) config.set(path, null);
            return;
        }
        config.set(path, null);
        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", location.getX());
        config.set(path + ".y", location.getY());
        config.set(path + ".z", location.getZ());
        config.set(path + ".yaw", location.getYaw());
        config.set(path + ".pitch", location.getPitch());
    }

    private boolean saveArena(ArenaData arena) {
        String path = "arenas." + arena.id();
        writeSavedLocation(path + ".spawn1", arena.spawnOne());
        writeSavedLocation(path + ".spawn2", arena.spawnTwo());
        config.set(path + ".enabled", arena.configuredEnabled());
        config.set(path + ".party-ffa", arena.partyFfa());
        config.set(path + ".kit-restricted", arena.kitRestricted());
        config.set(path + ".allowed-kits", new ArrayList<>(arena.allowedKits()));
        String bedPath = path + ".bedfight";
        config.set(bedPath + ".enabled", arena.bedfightEnabled());
        writeSavedLocation(bedPath + ".bed-a", arena.bedA());
        writeSavedLocation(bedPath + ".bed-b", arena.bedB());
        if (arena.hasBedVoid()) {
            config.set(bedPath + ".void.world", arena.bedVoidWorld());
            config.set(bedPath + ".void.min-x", arena.bedVoidMinX());
            config.set(bedPath + ".void.min-y", arena.bedVoidMinY());
            config.set(bedPath + ".void.min-z", arena.bedVoidMinZ());
            config.set(bedPath + ".void.max-x", arena.bedVoidMaxX());
            config.set(bedPath + ".void.max-y", arena.bedVoidMaxY());
            config.set(bedPath + ".void.max-z", arena.bedVoidMaxZ());
        }
        String border = path + ".border";
        if (arena.hasRegion()) {
            config.set(border + ".world", arena.regionWorld());
            config.set(border + ".min-x", arena.minX());
            config.set(border + ".min-y", arena.minY());
            config.set(border + ".min-z", arena.minZ());
            config.set(border + ".max-x", arena.maxX());
            config.set(border + ".max-y", arena.maxY());
            config.set(border + ".max-z", arena.maxZ());
        } else config.set(border, null);

        List<ArenaData> physical = instances.getOrDefault(arena.id(), List.of(arena));
        config.set(path + ".copies.count", physical.size());
        config.set(path + ".copies.offsets", null);
        config.set(path + ".copies.pending", null);
        for (ArenaData copy : physical) {
            if (copy.instanceNumber() <= 1) continue;
            String offset = path + ".copies.offsets." + copy.instanceNumber();
            config.set(offset + ".x", copy.offsetX());
            config.set(offset + ".z", copy.offsetZ());
            config.set(offset + ".operational", copy.operational());
        }
        try {
            config.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save arena " + arena.id() + ": " + e.getMessage());
            return false;
        }
    }

    /** Restores every configured physical instance at startup before allowing matches to use it. */
    public void recoverAfterRestart() {
        startupRecoveryPending = true;
        startupRecoveryScanning = true;
        startupRecoveryJobs = 0;
        for (Map.Entry<String, ArenaData> entry : arenas.entrySet()) {
            String id = entry.getKey();
            ArenaData template = entry.getValue();
            if (!template.hasRegion()) {
                plugin.getLogger().warning("Arena " + id + " has no saved border; startup recovery skipped. Its metadata was preserved.");
                continue;
            }
            if (Bukkit.getWorld(template.regionWorld()) == null) {
                deferredStartupRecovery.add(id);
                plugin.getLogger().warning("Arena " + id + " references unloaded/missing world '" + template.regionWorld() + "'; startup recovery is deferred without deleting its saved locations. NexPractice will retry after world-management plugins finish loading.");
                continue;
            }
            if (!hasUsableSnapshot(id)) {
                deferredStartupRecovery.add(id);
                plugin.getLogger().warning("Arena " + id + " has no usable snapshot at " + snapshotFile(id).getAbsolutePath() + "; recovery is deferred and its saved enable/spawn settings are preserved. If the file is genuinely missing, restore it from backup or recapture the baseline with /vsetarenaborder.");
                continue;
            }
            deferredStartupRecovery.remove(id);
            recovering.add(id);
            startupRecoveryJobs++;
            List<ArenaData> copies = new ArrayList<>(instances.getOrDefault(id, List.of()));
            recoverNextInstance(id, copies, 0, true);
        }
        startupRecoveryScanning = false;
        finishStartupRecoveryIfReady();
    }

    /**
     * Retries startup recovery for arenas whose world or snapshot was unavailable during the
     * initial pass. It deliberately rehydrates only those arena entries rather than calling
     * load(), which would rebuild reservations and state for arenas already being played.
     */
    public void retryDeferredStartupRecovery() {
        if (startupRecoveryPending || startupRecoveryScanning || deferredStartupRecovery.isEmpty()) return;
        for (String id : new ArrayList<>(deferredStartupRecovery)) {
            ArenaData template = arenas.get(id);
            if (template == null) {
                deferredStartupRecovery.remove(id);
                continue;
            }
            if (!template.hasRegion()) {
                // Region setup is an administrator action, not a transient world-load problem.
                deferredStartupRecovery.remove(id);
                continue;
            }
            World world = Bukkit.getWorld(template.regionWorld());
            if (world == null || !hasUsableSnapshot(id)) continue;
            if (isInUse(id) || copiesBusy.contains(id) || snapshotsBusy.contains(id)
                    || deleting.contains(id) || recovering.contains(id)) continue;

            // Location fields can be null after a safe load while the named world was unavailable.
            // Re-read the plain YAML coordinates now that Bukkit can resolve that world.
            if (template.spawnOne() == null) template.setSpawnOne(readSavedLocation("arenas." + id + ".spawn1"));
            if (template.spawnTwo() == null) template.setSpawnTwo(readSavedLocation("arenas." + id + ".spawn2"));
            Location bedA = template.bedA();
            Location bedB = template.bedB();
            if (bedA == null) bedA = readSavedLocation("arenas." + id + ".bedfight.bed-a");
            if (bedB == null) bedB = readSavedLocation("arenas." + id + ".bedfight.bed-b");
            if (bedA != null || bedB != null) template.setBedLocations(bedA, bedB);

            template.setEnabled(template.configuredEnabled() && template.ready() && hasUsableSnapshot(id));
            rebuildInstances(id);
            List<ArenaData> physical = new ArrayList<>(instances.getOrDefault(id, List.of(template)));
            deferredStartupRecovery.remove(id);
            recovering.add(id);
            plugin.getLogger().info("Retrying deferred startup recovery for arena " + id + " now that its world and snapshot are available.");
            recoverNextInstance(id, physical, 0, true);
        }
    }

    private void finishStartupRecoveryIfReady() {
        if (!startupRecoveryScanning && startupRecoveryJobs <= 0 && startupRecoveryPending) {
            startupRecoveryPending = false;
            plugin.getLogger().info("NexPractice arena startup recovery pass finished; valid recovered arenas are available to matchmaking.");
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getQueueManager().tryMatchAll());
        }
    }

    private void recoverNextInstance(String id, List<ArenaData> copies, int index, boolean allRestored) {
        if (index >= copies.size()) {
            ArenaData template = get(id);
            if (!allRestored && template != null) {
                template.setEnabled(false);
                setInstancesEnabled(id, false);
                // Do not call saveArena here: it would replace the administrator's saved
                // enabled=true preference with a transient runtime failure state.
                plugin.getLogger().severe("Arena " + id + " remains disabled in memory because one or more copies could not be restored. Saved settings were preserved; fix the world/snapshot and re-enable it after checking the arena.");
            } else if (template != null) {
                for (ArenaData copy : copies) copy.setEnabled(template.enabled() && copy.operational());
                plugin.getLogger().info("Recovered " + copies.size() + " arena instance(s) for " + id + ".");
            }
            recovering.remove(id);
            startupRecoveryJobs = Math.max(0, startupRecoveryJobs - 1);
            finishStartupRecoveryIfReady();
            return;
        }
        ArenaData copy = copies.get(index);
        restoreSnapshot(copy, restored -> {
            if (!restored) plugin.getLogger().severe("Recovery failed for " + copy.instanceKey() + ". It will remain unavailable for this runtime; the saved enabled preference and copy metadata were preserved for a later retry.");
            recoverNextInstance(id, copies, index + 1, allRestored && restored);
        });
    }

    private boolean hasUsableSnapshot(String id) {
        File snapshot = snapshotFile(id);
        return snapshot.isFile() && snapshot.length() > 0L;
    }

    private File snapshotFile(String id) { return new File(schematicDirectory, normalize(id) + ".schem"); }
    private File copyBackgroundFile(String id, int copyNumber) {
        return new File(schematicDirectory, normalize(id) + "__copy_" + copyNumber + "_original.schem");
    }
    private String normalize(String id) { return id == null ? "" : id.toLowerCase(Locale.ROOT); }

    private static final class FileMove {
        private final File from;
        private final File to;
        private FileMove(File from, File to) { this.from = from; this.to = to; }
        private File from() { return from; }
        private File to() { return to; }
    }

    private static final class CopyPosition {
        private final int x;
        private final int z;
        private final boolean operational;
        private CopyPosition(int x, int z, boolean operational) {
            this.x = x;
            this.z = z;
            this.operational = operational;
        }
        private int x() { return x; }
        private int z() { return z; }
        private boolean operational() { return operational; }
    }
}
