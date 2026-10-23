package com.vprac.leaderboard.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.util.Map;

/** Small conversion helper shared by database implementations. */
final class PlayerStatsYamlCodec {
    private PlayerStatsYamlCodec() { }

    static YamlConfiguration parse(String serialized) {
        return YamlConfiguration.loadConfiguration(new StringReader(serialized == null ? "" : serialized));
    }

    static void copySection(ConfigurationSection from, ConfigurationSection to) {
        if (from == null || to == null) return;
        for (String key : from.getKeys(false)) {
            Object value = from.get(key);
            if (value instanceof ConfigurationSection child) {
                copySection(child, to.createSection(key));
            } else {
                to.set(key, value);
            }
        }
    }

    static void copyMapToYaml(Map<String, Object> values, YamlConfiguration yaml, String path) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String keyPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) nested;
                copyMapToYaml(typed, yaml, keyPath);
            } else {
                yaml.set(keyPath, value);
            }
        }
    }
}
