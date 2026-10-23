package com.vprac.kit;

import com.vprac.VpracPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Persistent per-kit gameplay switches configured from /vkitsettings. */
public final class KitRulesManager {
    public enum Rule {
        BUILD("build", false),
        DESTROY("destroy", false),
        CRAFTING("crafting", false),
        HEALTH_REGEN("health-regeneration", true),
        UNLIMITED_HUNGER("unlimited-hunger", true),
        FALL_DAMAGE("fall-damage", true),
        AUTO_TNT_IGNITION("auto-tnt-ignition", false),
        AUTO_CREEPER_IGNITION("auto-creeper-ignition", false);

        private final String path;
        private final boolean defaultValue;

        Rule(String path, boolean defaultValue) {
            this.path = path;
            this.defaultValue = defaultValue;
        }

        public String path() { return path; }
        public boolean defaultValue() { return defaultValue; }
    }

    private final VpracPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public KitRulesManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kit-rules.yml");
        reload();
    }

    public void reload() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create NexPractice data directory for kit-rules.yml.");
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled(String kitId, Rule rule) {
        String key = path(kitId, rule);
        return data.getBoolean(key, rule.defaultValue());
    }

    /** Number of round wins required to win a match for this kit (1-10). */
    public int roundsToWin(String kitId) {
        return Math.max(1, Math.min(10, data.getInt(roundsPath(kitId), 1)));
    }

    public void setRoundsToWin(String kitId, int rounds) throws IOException {
        data.set(roundsPath(kitId), Math.max(1, Math.min(10, rounds)));
        data.save(file);
    }

    /** Auto-prime delay in seconds for TNT, clamped to the documented 0-5 range. */
    public int tntIgnitionSeconds(String kitId) {
        return Math.max(0, Math.min(5, data.getInt("kits." + normalize(kitId) + ".tnt-ignition-seconds", 2)));
    }

    public void setTntIgnitionSeconds(String kitId, int seconds) throws IOException {
        data.set("kits." + normalize(kitId) + ".tnt-ignition-seconds", Math.max(0, Math.min(5, seconds)));
        data.save(file);
    }

    /** Auto-prime delay in seconds for egg-spawned creepers, clamped to 0-5. */
    public int creeperIgnitionSeconds(String kitId) {
        return Math.max(0, Math.min(5, data.getInt("kits." + normalize(kitId) + ".creeper-ignition-seconds", 2)));
    }

    public void setCreeperIgnitionSeconds(String kitId, int seconds) throws IOException {
        data.set("kits." + normalize(kitId) + ".creeper-ignition-seconds", Math.max(0, Math.min(5, seconds)));
        data.save(file);
    }

    private String normalize(String kitId) { return kitId == null ? "" : kitId.toLowerCase(Locale.ROOT); }

    public boolean toggle(String kitId, Rule rule) throws IOException {
        boolean value = !enabled(kitId, rule);
        data.set(path(kitId, rule), value);
        data.save(file);
        return value;
    }

    public void set(String kitId, Rule rule, boolean value) throws IOException {
        data.set(path(kitId, rule), value);
        data.save(file);
    }

    public void removeKit(String kitId) throws IOException {
        if (kitId == null) return;
        data.set("kits." + kitId.toLowerCase(Locale.ROOT), null);
        data.save(file);
    }

    private String roundsPath(String kitId) {
        String id = normalize(kitId);
        return "kits." + id + ".rounds-to-win";
    }

    private String path(String kitId, Rule rule) {
        String id = normalize(kitId);
        return "kits." + id + "." + rule.path();
    }
}
