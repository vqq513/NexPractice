package com.vprac.queue;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Identifies the private tracker inventory and maps its slots to eligible match targets. */
public final class SpectatorTrackerHolder implements InventoryHolder {
    private final UUID viewerId;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private Inventory inventory;

    public SpectatorTrackerHolder(UUID viewerId) {
        this.viewerId = viewerId;
    }

    public UUID viewerId() { return viewerId; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    public void mapTarget(int slot, UUID targetId) { targets.put(slot, targetId); }
    public UUID targetAt(int slot) { return targets.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }
}
