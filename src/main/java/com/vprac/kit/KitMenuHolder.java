package com.vprac.kit;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class KitMenuHolder implements InventoryHolder {
    public enum Type { KIT_SELECT, KIT_PROFILES, ADMIN_KIT_SELECT, KIT_RULES, KIT_DELETE_CONFIRM }

    private final Type type;
    private final String kitId;
    private final int page;
    private final Map<Integer, String> kitSlots = new HashMap<>();
    private Inventory inventory;

    public KitMenuHolder(Type type, String kitId, int page) {
        this.type = type;
        this.kitId = kitId;
        this.page = page;
    }

    public Type type() { return type; }
    public String kitId() { return kitId; }
    public int page() { return page; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    public void mapKitSlot(int slot, String id) { kitSlots.put(slot, id); }
    public String kitAt(int slot) { return kitSlots.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }
}
