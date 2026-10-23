package com.vprac.kit;

import com.vprac.VpracPlugin;
import com.vprac.util.ItemTooltips;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public final class KitManager {
    public static final String SWORD_ID = "sword";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final VpracPlugin plugin;
    private final File file;
    private YamlConfiguration data;
    private final Map<String, KitData> customKits = new LinkedHashMap<>();

    public KitManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kits.yml");
        load();
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data directory.");
        }
        if (!file.exists()) {
            try {
                if (!file.createNewFile()) plugin.getLogger().warning("Could not create kits.yml.");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create kits.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
        customKits.clear();
        ConfigurationSection section = data.getConfigurationSection("kits");
        if (section == null) return;

        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!isValidId(id) || SWORD_ID.equals(id)) continue;
            Material icon = parseMaterial(data.getString("kits." + rawId + ".icon"));
            customKits.put(id, new KitData(
                    id,
                    asItems(data.getList("kits." + rawId + ".contents", List.of())),
                    asItems(data.getList("kits." + rawId + ".armor", List.of())),
                    asItem(data.get("kits." + rawId + ".offhand")),
                    icon
            ));
        }
    }

    private ItemStack[] asItems(List<?> list) {
        ItemStack[] result = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) result[i] = asItem(list.get(i));
        return result;
    }

    private ItemStack asItem(Object item) {
        return item instanceof ItemStack stack ? stack.clone() : null;
    }

    public boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    public boolean saveKit(String id, Material icon, PlayerInventory inventory) throws IOException {
        String normalized = id.toLowerCase(Locale.ROOT);
        if (!isValidId(normalized) || SWORD_ID.equals(normalized) || icon == null || icon.isAir()) return false;

        ItemStack[] contents = cloneItems(inventory.getStorageContents());
        ItemStack[] armor = cloneItems(inventory.getArmorContents());
        ItemStack offhand = inventory.getItemInOffHand() == null ? null : inventory.getItemInOffHand().clone();

        data.set("kits." + normalized + ".contents", Arrays.asList(contents));
        data.set("kits." + normalized + ".armor", Arrays.asList(armor));
        data.set("kits." + normalized + ".offhand", offhand);
        data.set("kits." + normalized + ".icon", icon.getKey().toString());
        data.save(file);
        customKits.put(normalized, new KitData(normalized, contents, armor, offhand, icon));
        return true;
    }

    /** Removes an admin-defined kit from persistent storage. The built-in Sword kit is protected. */
    public boolean removeCustomKit(String id) throws IOException {
        if (id == null) return false;
        String normalized = id.toLowerCase(Locale.ROOT);
        if (SWORD_ID.equals(normalized) || !customKits.containsKey(normalized)) return false;
        data.set("kits." + normalized, null);
        data.save(file);
        customKits.remove(normalized);
        return true;
    }

    /** Kept for source compatibility with callers which do not specify an icon. */
    public boolean saveKit(String id, PlayerInventory inventory) throws IOException {
        return saveKit(id, inferIcon(inventory), inventory);
    }

    private Material inferIcon(PlayerInventory inventory) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && !item.getType().isAir()) return item.getType();
        }
        for (ItemStack item : inventory.getArmorContents()) {
            if (item != null && !item.getType().isAir()) return item.getType();
        }
        return Material.CHEST;
    }

    public Material parseMaterial(String input) {
        if (input == null || input.isBlank()) return null;
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            Material material = Material.matchMaterial(normalized);
            return material == null || material.isAir() ? null : material;
        }
        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null) return null;
        Material material = Registry.MATERIAL.get(key);
        return material == null || material.isAir() ? null : material;
    }

    private ItemStack[] cloneItems(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].clone();
        return result;
    }

    public boolean exists(String id) { return get(id) != null; }

    public KitData get(String id) {
        if (id == null) return null;
        String normalized = id.toLowerCase(Locale.ROOT);
        return SWORD_ID.equals(normalized) ? createSwordKit() : customKits.get(normalized);
    }

    public List<String> getKitIds() {
        List<String> ids = new ArrayList<>();
        ids.add(SWORD_ID);
        ids.addAll(customKits.keySet());
        return Collections.unmodifiableList(ids);
    }

    public List<String> getCustomKitIds() { return List.copyOf(customKits.keySet()); }

    public String getDisplayName(String id) {
        if (SWORD_ID.equalsIgnoreCase(id)) return "Sword";
        String readable = id.replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder();
        for (String part : readable.split("\\s+")) {
            if (part.isBlank()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) result.append(part.substring(1));
        }
        return result.toString();
    }

    public Material getIconMaterial(String id) {
        if (SWORD_ID.equalsIgnoreCase(id)) return Material.DIAMOND_SWORD;
        KitData kit = get(id);
        if (kit == null) return Material.CHEST;
        if (kit.icon() != null && !kit.icon().isAir()) return kit.icon();
        for (ItemStack item : kit.contents()) {
            if (item != null && !item.getType().isAir()) return item.getType();
        }
        for (ItemStack item : kit.armor()) {
            if (item != null && !item.getType().isAir()) return item.getType();
        }
        ItemStack offhand = kit.offhand();
        return offhand != null && !offhand.getType().isAir() ? offhand.getType() : Material.CHEST;
    }

    public void apply(PlayerInventory inventory, String id) {
        KitData kit = get(id);
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        if (kit == null) return;
        inventory.setStorageContents(resized(kit.contents(), 36));
        inventory.setArmorContents(resized(kit.armor(), 4));
        ItemStack offhand = kit.offhand();
        inventory.setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : hideAttributes(offhand.clone()));
    }

    public static KitData createSwordKit() {
        ItemStack[] contents = new ItemStack[36];
        contents[0] = new ItemStack(Material.DIAMOND_SWORD);
        return new KitData(SWORD_ID, contents, new ItemStack[] {
                new ItemStack(Material.DIAMOND_BOOTS),
                new ItemStack(Material.DIAMOND_LEGGINGS),
                new ItemStack(Material.DIAMOND_CHESTPLATE),
                new ItemStack(Material.DIAMOND_HELMET)
        }, null, Material.DIAMOND_SWORD);
    }

    private ItemStack[] resized(ItemStack[] input, int size) {
        ItemStack[] output = new ItemStack[size];
        for (int i = 0; i < Math.min(size, input.length); i++) {
            output[i] = input[i] == null ? null : hideAttributes(input[i].clone());
        }
        return output;
    }

    private ItemStack hideAttributes(ItemStack item) {
        return ItemTooltips.hideAttributes(item);
    }
}
