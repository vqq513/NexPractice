package com.vprac.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class KitData {
    private final String id;
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offhand;
    private final Material icon;

    public KitData(String id, ItemStack[] contents, ItemStack[] armor) {
        this(id, contents, armor, null, null);
    }

    public KitData(String id, ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
        this(id, contents, armor, offhand, null);
    }

    public KitData(String id, ItemStack[] contents, ItemStack[] armor, ItemStack offhand, Material icon) {
        this.id = id;
        this.contents = cloneItems(contents);
        this.armor = cloneItems(armor);
        this.offhand = offhand == null ? null : offhand.clone();
        this.icon = icon;
    }

    public String id() { return id; }
    public ItemStack[] contents() { return cloneItems(contents); }
    public ItemStack[] armor() { return cloneItems(armor); }
    public ItemStack offhand() { return offhand == null ? null : offhand.clone(); }
    public Material icon() { return icon; }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        if (items == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
    }
}
