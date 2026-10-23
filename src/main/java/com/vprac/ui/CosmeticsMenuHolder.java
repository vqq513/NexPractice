package com.vprac.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Inventory identity for the player cosmetics chest menus. */
public final class CosmeticsMenuHolder implements InventoryHolder {
    public enum Type { CATEGORY, PROJECTILE_TRAILS, KILL_MESSAGES, KILL_EFFECTS, ARMOR_TRIMS }

    private final UUID viewerId;
    private final Type type;
    private final int page;
    private final Map<Integer, String> options = new HashMap<>();
    private Inventory inventory;

    public CosmeticsMenuHolder(UUID viewerId, Type type, int page) {
        this.viewerId = viewerId;
        this.type = type;
        this.page = page;
    }

    public UUID viewerId() { return viewerId; }
    public Type type() { return type; }
    public int page() { return page; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    public void mapOption(int slot, String id) { options.put(slot, id); }
    public String optionAt(int slot) { return options.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }
}
