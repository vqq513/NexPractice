package com.vprac.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Inventory identity and navigation state for the Practice Menu and kit editor. */
public final class PracticeMenuHolder implements InventoryHolder {
    public enum Type { MAIN, KIT_LIST, LAYOUTS, EDITOR, STATS, RANKS }

    private final Type type;
    private final UUID viewerId;
    private final String kitId;
    private final int page;
    private final int profile;
    private final Map<Integer, String> kitSlots = new HashMap<>();
    private Inventory inventory;

    public PracticeMenuHolder(Type type, UUID viewerId, String kitId, int page, int profile) {
        this.type = type;
        this.viewerId = viewerId;
        this.kitId = kitId;
        this.page = page;
        this.profile = profile;
    }

    public Type type() { return type; }
    public UUID viewerId() { return viewerId; }
    public String kitId() { return kitId; }
    public int page() { return page; }
    public int profile() { return profile; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    public void mapKitSlot(int slot, String id) { kitSlots.put(slot, id); }
    public String kitAt(int slot) { return kitSlots.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }
}
