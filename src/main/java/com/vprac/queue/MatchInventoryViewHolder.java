package com.vprac.queue;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Read-only chest inventory used to inspect a saved match-end inventory snapshot. */
public final class MatchInventoryViewHolder implements InventoryHolder {
    private final String reportId;
    private final UUID targetId;
    private Inventory inventory;

    public MatchInventoryViewHolder(String reportId, UUID targetId) {
        this.reportId = reportId;
        this.targetId = targetId;
    }

    public String reportId() { return reportId; }
    public UUID targetId() { return targetId; }

    public void bind(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
