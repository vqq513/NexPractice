package com.vprac.kit;

import com.vprac.VpracPlugin;
import com.vprac.util.ItemTooltips;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Persists three independent, per-player loadout profiles for each kit. */
public final class KitProfileManager {
    public static final int PROFILE_COUNT = 3;
    private final VpracPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public KitProfileManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kit-profiles.yml");
        load();
    }

    private void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (IOException e) { plugin.getLogger().severe("Could not create kit-profiles.yml: " + e.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public boolean hasProfile(UUID uuid, String kitId, int profile) {
        if (!validProfile(profile)) return false;
        return data.contains(profilePath(uuid, kitId, profile) + ".contents");
    }

    public int selectedProfile(UUID uuid, String kitId) {
        int selected = data.getInt(selectedPath(uuid, kitId), 1);
        return validProfile(selected) ? selected : 1;
    }

    public void saveProfile(Player player, String kitId, int profile) throws IOException {
        if (!validProfile(profile)) throw new IllegalArgumentException("Profile must be between 1 and 3");
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = cloneItems(inventory.getStorageContents());
        for (int i = 0; i < contents.length; i++) {
            if (plugin.isLobbyItem(contents[i])) contents[i] = null;
        }
        ItemStack[] armor = cloneItems(inventory.getArmorContents());
        ItemStack offhand = inventory.getItemInOffHand() == null ? null : inventory.getItemInOffHand().clone();
        if (plugin.isLobbyItem(offhand)) offhand = null;

        String path = profilePath(player.getUniqueId(), kitId, profile);
        data.set(path + ".contents", Arrays.asList(contents));
        data.set(path + ".armor", Arrays.asList(armor));
        data.set(path + ".offhand", offhand);
        data.set(path + ".held-slot", inventory.getHeldItemSlot());
        data.set(selectedPath(player.getUniqueId(), kitId), profile);
        data.save(file);
    }

    public boolean loadProfile(Player player, String kitId, int profile) {
        if (!loadProfileForEditing(player, kitId, profile)) return false;
        data.set(selectedPath(player.getUniqueId(), kitId), profile);
        try { data.save(file); }
        catch (IOException e) { plugin.getLogger().severe("Could not save active kit profile: " + e.getMessage()); }
        return true;
    }

    /** Loads a saved layout into the player's inventory without changing the saved active profile. */
    public boolean loadProfileForEditing(Player player, String kitId, int profile) {
        if (!validProfile(profile) || !hasProfile(player.getUniqueId(), kitId, profile)) return false;
        String path = profilePath(player.getUniqueId(), kitId, profile);
        ItemStack[] contents = readItems(data.getList(path + ".contents"), 36);
        ItemStack[] armor = readItems(data.getList(path + ".armor"), 4);
        Object offhandObject = data.get(path + ".offhand");
        ItemStack offhand = offhandObject instanceof ItemStack stack ? stack.clone() : null;

        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setStorageContents(contents);
        inventory.setArmorContents(armor);
        inventory.setItemInOffHand(offhand == null ? new ItemStack(org.bukkit.Material.AIR) : offhand);
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, data.getInt(path + ".held-slot", 0))));
        return true;
    }

    /** Removes saved profile/loadout data for a custom kit after an admin deletes it. */
    public void removeKitData(String kitId) throws IOException {
        if (kitId == null) return;
        org.bukkit.configuration.ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        String normalized = kitId.toLowerCase(java.util.Locale.ROOT);
        for (String rawUuid : new java.util.ArrayList<>(players.getKeys(false))) {
            data.set("players." + rawUuid + ".kits." + normalized, null);
            data.set("players." + rawUuid + ".selected." + normalized, null);
        }
        data.save(file);
    }

    /** Uses a player's selected saved profile, falling back to the admin-defined base kit. */
    public boolean applySelected(Player player, String kitId) {
        int selected = selectedProfile(player.getUniqueId(), kitId);
        return loadProfileForMatch(player, kitId, selected);
    }

    private boolean loadProfileForMatch(Player player, String kitId, int profile) {
        if (!hasProfile(player.getUniqueId(), kitId, profile)) return false;
        String path = profilePath(player.getUniqueId(), kitId, profile);
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setStorageContents(readItems(data.getList(path + ".contents"), 36));
        inventory.setArmorContents(readItems(data.getList(path + ".armor"), 4));
        Object raw = data.get(path + ".offhand");
        if (raw instanceof ItemStack stack) {
            inventory.setItemInOffHand(ItemTooltips.hideAttributes(stack.clone()));
        } else inventory.setItemInOffHand(new ItemStack(org.bukkit.Material.AIR));
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, data.getInt(path + ".held-slot", 0))));
        return true;
    }

    private String playerPath(UUID uuid) { return "players." + uuid; }
    private String profilePath(UUID uuid, String kitId, int profile) {
        return playerPath(uuid) + ".kits." + kitId.toLowerCase(java.util.Locale.ROOT) + ".profiles." + profile;
    }
    private String selectedPath(UUID uuid, String kitId) {
        return playerPath(uuid) + ".selected." + kitId.toLowerCase(java.util.Locale.ROOT);
    }
    private boolean validProfile(int profile) { return profile >= 1 && profile <= PROFILE_COUNT; }

    private ItemStack[] readItems(List<?> list, int size) {
        ItemStack[] items = new ItemStack[size];
        if (list == null) return items;
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            Object value = list.get(i);
            if (value instanceof ItemStack stack && !plugin.isLobbyItem(stack)) {
                items[i] = ItemTooltips.hideAttributes(stack.clone());
            }
        }
        return items;
    }

    private ItemStack[] cloneItems(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
        return copy;
    }
}
