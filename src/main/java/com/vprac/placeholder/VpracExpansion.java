package com.vprac.placeholder;

import com.vprac.VpracPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

public final class VpracExpansion extends PlaceholderExpansion {
    private final VpracPlugin plugin;

    public VpracExpansion(VpracPlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "vprac"; }
    @Override public String getAuthor() { return "NexPractice"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return "";
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("coins")) return String.valueOf(plugin.getCoinsManager() == null || player == null ? 0 : plugin.getCoinsManager().getCoins(player.getUniqueId()));
        if (key.equals("queue_count")) return String.valueOf(plugin.getQueueManager().totalCount());
        if (key.equals("queue_count_sword")) return String.valueOf(plugin.getQueueManager().count("sword"));
        if (key.startsWith("queue_") && key.endsWith("_count")) {
            String kit = key.substring("queue_".length(), key.length() - "_count".length());
            return String.valueOf(plugin.getQueueManager().count(kit));
        }
        if (key.startsWith("queued_") && player != null && player.getUniqueId() != null) {
            String kit = key.substring("queued_".length());
            return String.valueOf(plugin.getQueueManager().isQueued(player.getUniqueId(), kit));
        }
        return null;
    }
}
