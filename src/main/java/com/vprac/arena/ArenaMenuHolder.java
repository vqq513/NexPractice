package com.vprac.arena;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class ArenaMenuHolder implements InventoryHolder {
    public enum Type { ARENA_LIST, ARENA_EDITOR, DELETE_CONFIRM }

    private final Type type;
    private final String arenaId;
    private final int page;
    private final int desiredCopies;
    private final Map<Integer, String> arenaSlots = new HashMap<>();
    private final Map<Integer, String> kitSlots = new HashMap<>();
    private final Map<Integer, Boolean> renderedKitAllowed = new HashMap<>();
    private boolean kitRestrictedAtRender;
    private Inventory inventory;

    public ArenaMenuHolder(Type type, String arenaId, int page, int desiredCopies) {
        this.type = type;
        this.arenaId = arenaId;
        this.page = page;
        this.desiredCopies = desiredCopies;
    }

    public Type type() { return type; }
    public String arenaId() { return arenaId; }
    public int page() { return page; }
    public int desiredCopies() { return desiredCopies; }
    public void mapArenaSlot(int slot, String id) { arenaSlots.put(slot, id); }
    public String arenaAt(int slot) { return arenaSlots.get(slot); }
    public void mapKitSlot(int slot, String id, boolean allowed) {
        kitSlots.put(slot, id);
        renderedKitAllowed.put(slot, allowed);
    }
    public String kitAt(int slot) { return kitSlots.get(slot); }
    public boolean kitAllowedAt(int slot) { return renderedKitAllowed.getOrDefault(slot, false); }
    public void setKitRestrictedAtRender(boolean value) { kitRestrictedAtRender = value; }
    public boolean kitRestrictedAtRender() { return kitRestrictedAtRender; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
