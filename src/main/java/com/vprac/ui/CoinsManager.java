package com.vprac.ui;

import com.vprac.VpracPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Persistent NexPractice coin balances used by tournament rewards and cosmetic purchases. */
public final class CoinsManager {
    private final VpracPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    public CoinsManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "coins.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (IOException exception) {
                plugin.getLogger().severe("Could not create coins.yml: " + exception.getMessage());
            }
        }
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public int getCoins(UUID playerId) {
        if (playerId == null) return 0;
        return Math.max(0, data.getInt("players." + playerId, 0));
    }

    public int addCoins(UUID playerId, int amount) {
        if (playerId == null || amount <= 0) return getCoins(playerId);
        long next = (long) getCoins(playerId) + amount;
        int balance = (int) Math.min(Integer.MAX_VALUE, next);
        data.set("players." + playerId, balance);
        save();
        return balance;
    }

    public boolean takeCoins(UUID playerId, int amount) {
        if (playerId == null || amount < 0 || getCoins(playerId) < amount) return false;
        data.set("players." + playerId, getCoins(playerId) - amount);
        save();
        return true;
    }

    public void setCoins(UUID playerId, int amount) {
        if (playerId == null) return;
        data.set("players." + playerId, Math.max(0, amount));
        save();
    }

    private void save() {
        try { data.save(file); }
        catch (IOException exception) {
            plugin.getLogger().severe("Could not save coins.yml: " + exception.getMessage());
        }
    }
}
