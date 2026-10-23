package com.vprac.arena;

import org.bukkit.Location;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Arena template data or one physical instance of that template.
 * id() is always the user-facing template name; instanceKey() is unique per physical copy.
 */
public final class ArenaData {
    private String id;
    private Location spawnOne;
    private Location spawnTwo;
    /** Effective runtime state; false while unavailable or recovering. */
    private boolean enabled;
    /** Persisted administrator intent, kept separately so transient startup failures don't rewrite arenas.yml. */
    private boolean configuredEnabled;
    private boolean partyFfa;
    private String regionWorld;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private int instanceNumber = 1;
    private int offsetX;
    private int offsetZ;
    private boolean operational = true;
    private boolean kitRestricted;
    private final Set<String> allowedKits = new LinkedHashSet<>();
    private boolean bedfightEnabled;
    private Location bedA;
    private Location bedB;
    private String bedVoidWorld;
    private int bedVoidMinX, bedVoidMinY, bedVoidMinZ, bedVoidMaxX, bedVoidMaxY, bedVoidMaxZ;

    public ArenaData(String id, Location spawnOne, Location spawnTwo, boolean enabled) {
        this.id = id;
        this.spawnOne = spawnOne == null ? null : spawnOne.clone();
        this.spawnTwo = spawnTwo == null ? null : spawnTwo.clone();
        this.enabled = enabled;
        this.configuredEnabled = enabled;
    }

    public String id() { return id; }
    public Location spawnOne() { return spawnOne == null ? null : spawnOne.clone(); }
    public Location spawnTwo() { return spawnTwo == null ? null : spawnTwo.clone(); }
    public boolean enabled() { return enabled; }
    public boolean configuredEnabled() { return configuredEnabled; }
    public boolean partyFfa() { return partyFfa; }
    public String regionWorld() { return regionWorld; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
    public int instanceNumber() { return instanceNumber; }
    public int offsetX() { return offsetX; }
    public int offsetZ() { return offsetZ; }
    public boolean operational() { return operational; }
    public void setOperational(boolean operational) { this.operational = operational; }
    public String instanceKey() { return id.toLowerCase(Locale.ROOT) + "::" + instanceNumber; }
    public boolean isTemplateInstance() { return instanceNumber == 1 && offsetX == 0 && offsetZ == 0; }
    public boolean kitRestricted() { return kitRestricted; }
    public Set<String> allowedKits() { return Collections.unmodifiableSet(allowedKits); }
    public boolean bedfightEnabled() { return bedfightEnabled; }
    public Location bedA() { return bedA == null ? null : bedA.clone(); }
    public Location bedB() { return bedB == null ? null : bedB.clone(); }
    public String bedVoidWorld() { return bedVoidWorld; }
    public int bedVoidMinX() { return bedVoidMinX; }
    public int bedVoidMinY() { return bedVoidMinY; }
    public int bedVoidMinZ() { return bedVoidMinZ; }
    public int bedVoidMaxX() { return bedVoidMaxX; }
    public int bedVoidMaxY() { return bedVoidMaxY; }
    public int bedVoidMaxZ() { return bedVoidMaxZ; }
    public boolean hasBedVoid() { return bedVoidWorld != null && !bedVoidWorld.isBlank(); }
    public boolean bedfightReady() {
        return bedA != null && bedB != null && hasBedVoid()
                && bedA.getWorld() != null && bedB.getWorld() != null
                && bedA.getWorld().getName().equals(regionWorld)
                && bedB.getWorld().getName().equals(regionWorld)
                && bedVoidWorld.equals(regionWorld)
                && contains(bedA) && contains(bedB);
    }
    public boolean containsBedVoid(Location location) {
        if (!hasBedVoid() || location == null || location.getWorld() == null
                || !bedVoidWorld.equals(location.getWorld().getName())) return false;
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        return x >= bedVoidMinX && x <= bedVoidMaxX
                && y >= bedVoidMinY && y <= bedVoidMaxY
                && z >= bedVoidMinZ && z <= bedVoidMaxZ;
    }
    public void setBedfightEnabled(boolean value) { this.bedfightEnabled = value; }
    public void setBedLocations(Location a, Location b) {
        this.bedA = a == null ? null : a.clone();
        this.bedB = b == null ? null : b.clone();
    }
    public void setBedVoid(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.bedVoidWorld = world;
        this.bedVoidMinX = Math.min(x1, x2);
        this.bedVoidMinY = Math.min(y1, y2);
        this.bedVoidMinZ = Math.min(z1, z2);
        this.bedVoidMaxX = Math.max(x1, x2);
        this.bedVoidMaxY = Math.max(y1, y2);
        this.bedVoidMaxZ = Math.max(z1, z2);
    }
    public boolean supportsKit(String kitId) {
        return !kitRestricted || (kitId != null && allowedKits.contains(kitId.toLowerCase(Locale.ROOT)));
    }

    public boolean hasRegion() { return regionWorld != null && !regionWorld.isBlank(); }

    public boolean ready() {
        return spawnOne != null && spawnTwo != null
                && hasRegion() && contains(spawnOne) && contains(spawnTwo);
    }

    public boolean contains(Location location) {
        if (!hasRegion() || location == null || location.getWorld() == null) return false;
        if (!regionWorld.equals(location.getWorld().getName())) return false;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public void setSpawnOne(Location location) { spawnOne = location == null ? null : location.clone(); }
    public void setSpawnTwo(Location location) { spawnTwo = location == null ? null : location.clone(); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setConfiguredEnabled(boolean enabled) { this.configuredEnabled = enabled; }
    public void setPartyFfa(boolean partyFfa) { this.partyFfa = partyFfa; }
    public void setPartyFfaFlag(boolean partyFfa) { this.partyFfa = partyFfa; }
    public void setKitRestricted(boolean restricted) { this.kitRestricted = restricted; }
    public void setAllowedKits(Collection<String> kits) {
        allowedKits.clear();
        if (kits != null) {
            for (String kit : kits) if (kit != null && !kit.isBlank()) allowedKits.add(kit.toLowerCase(Locale.ROOT));
        }
    }
    public boolean toggleAllowedKit(String kitId) {
        if (kitId == null || kitId.isBlank()) return false;
        String normalized = kitId.toLowerCase(Locale.ROOT);
        if (allowedKits.remove(normalized)) return false;
        allowedKits.add(normalized);
        return true;
    }
    public void renameId(String newId) { this.id = newId; }

    /** A 5-block-radius cardinal circle needs north/south/east/west to fit inside the cuboid. */
    public boolean hasPartyFfaBounds() {
        if (!hasRegion()) return false;
        int centerX = minX + (maxX - minX) / 2;
        int centerZ = minZ + (maxZ - minZ) / 2;
        return centerZ - 5 >= minZ && centerZ - 5 <= maxZ
                && centerZ + 5 >= minZ && centerZ + 5 <= maxZ
                && centerX - 5 >= minX && centerX - 5 <= maxX
                && centerX + 5 >= minX && centerX + 5 <= maxX;
    }

    public void setRegion(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.regionWorld = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        this.enabled = false;
    }

    public long regionVolume() {
        if (!hasRegion()) return 0L;
        return ((long) maxX - minX + 1L)
                * ((long) maxY - minY + 1L)
                * ((long) maxZ - minZ + 1L);
    }

    /** Creates a translated physical copy while preserving template settings. */
    public ArenaData createInstance(int number, int dx, int dz) {
        Location one = translated(spawnOne, dx, dz);
        Location two = translated(spawnTwo, dx, dz);
        ArenaData copy = new ArenaData(id, one, two, enabled);
        if (hasRegion()) {
            copy.setRegion(regionWorld, minX + dx, minY, minZ + dz, maxX + dx, maxY, maxZ + dz);
        }
        copy.setPartyFfaFlag(partyFfa);
        copy.setEnabled(enabled);
        copy.setConfiguredEnabled(configuredEnabled);
        copy.instanceNumber = number;
        copy.offsetX = dx;
        copy.offsetZ = dz;
        copy.operational = operational;
        copy.kitRestricted = kitRestricted;
        copy.setAllowedKits(allowedKits);
        copy.bedfightEnabled = bedfightEnabled;
        copy.bedA = translated(bedA, dx, dz);
        copy.bedB = translated(bedB, dx, dz);
        if (hasBedVoid()) copy.setBedVoid(bedVoidWorld, bedVoidMinX + dx, bedVoidMinY, bedVoidMinZ + dz,
                bedVoidMaxX + dx, bedVoidMaxY, bedVoidMaxZ + dz);
        return copy;
    }

    private Location translated(Location location, int dx, int dz) {
        if (location == null) return null;
        Location copy = location.clone();
        copy.add(dx, 0, dz);
        return copy;
    }
}
