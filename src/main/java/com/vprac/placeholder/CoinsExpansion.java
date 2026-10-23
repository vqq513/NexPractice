package com.vprac.placeholder;

import com.vprac.VpracPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Provides the requested short-form %coins% placeholder alongside VPRAC's %vprac_*% placeholders. */
public final class CoinsExpansion extends PlaceholderExpansion {
    private final VpracPlugin plugin;

    public CoinsExpansion(VpracPlugin plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "coins"; }
    @Override public String getAuthor() { return "NexPractice"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params != null && !params.isEmpty()) return null;
        if (player == null || player.getUniqueId() == null || plugin.getCoinsManager() == null) return "0";
        return String.valueOf(plugin.getCoinsManager().getCoins(player.getUniqueId()));
    }
}
