package com.vprac.ui;

import com.vprac.VpracPlugin;
import com.vprac.kit.KitManager;
import com.vprac.leaderboard.LeaderboardManager;
import com.vprac.leaderboard.KitRankProgression;
import com.vprac.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chest-GUI practice menu, per-kit profile selection, and temporary inventory-layout editor. */
public final class PracticeMenuManager implements Listener {
    private static final int KIT_PAGE_SIZE = 45;
    private static final int STATS_PAGE_SIZE = 36;
    private static final int RANKS_PAGE_SIZE = 21;
    private static final int[] RANKS_CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final DecimalFormat KDR_FORMAT = new DecimalFormat("0.00");

    private final VpracPlugin plugin;
    private final Map<UUID, EditSession> editing = new ConcurrentHashMap<>();

    public PracticeMenuManager(VpracPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEditing(UUID uuid) {
        return uuid != null && editing.containsKey(uuid);
    }

    public void openPracticeMenu(Player player) {
        if (player == null || !player.isOnline() || !plugin.canUsePracticeMenu(player)) return;
        if (isEditing(player.getUniqueId())) return;

        PracticeMenuHolder holder = new PracticeMenuHolder(PracticeMenuHolder.Type.MAIN,
                player.getUniqueId(), null, 0, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, Texts.component("&8Practice Menu"));
        holder.setInventory(inventory);
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);

        inventory.setItem(11, menuItem(Material.BOOK, "&eKit Editor", List.of(
                "&7Edit your item layout for each kit.",
                "&7Choose one of three layouts."
        )));

        ItemStack statsHead = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta baseMeta = statsHead.getItemMeta();
        if (baseMeta instanceof SkullMeta skullMeta) skullMeta.setOwningPlayer(player);
        baseMeta.displayName(Texts.component("&6" + player.getName() + " &eStats"));
        baseMeta.lore(Texts.nonItalicLore(List.of(Texts.component("&7View your stats for every kit."))));
        statsHead.setItemMeta(baseMeta);
        inventory.setItem(13, statsHead);

        inventory.setItem(15, menuItem(Material.ENDER_EYE, "&eRanks", List.of("&7View your per-kit rank progress.")));
        inventory.setItem(29, menuItem(Material.DIAMOND, "&aCosmetics", List.of("&7Customize trails, kill messages, effects, and armor trims.")));
        List<String> tournamentLore = new ArrayList<>(plugin.getConfig().getStringList("tournaments.host-item.lore"));
        if (tournamentLore.isEmpty()) tournamentLore.add("&7Require {required_rank} &7to host a tournament.!");
        String requiredRank = plugin.getConfig().getString("tournaments.required-rank", "Your Rank");
        tournamentLore.replaceAll(line -> line.replace("{required_rank}", requiredRank));
        inventory.setItem(31, menuItem(Material.NETHER_STAR, plugin.getConfig().getString("tournaments.host-item.name", "&6Host a Tournament"), tournamentLore));
        player.openInventory(inventory);
    }

    private void openKitList(Player player, int requestedPage) {
        List<String> kitIds = plugin.getKitManager().getKitIds();
        int pageCount = Math.max(1, (kitIds.size() + KIT_PAGE_SIZE - 1) / KIT_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        PracticeMenuHolder holder = new PracticeMenuHolder(PracticeMenuHolder.Type.KIT_LIST,
                player.getUniqueId(), null, page, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54, Texts.component("&8Kit Editor &8» &7Kits"));
        holder.setInventory(inventory);
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);

        int start = page * KIT_PAGE_SIZE;
        int end = Math.min(kitIds.size(), start + KIT_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            String kitId = kitIds.get(index);
            int slot = index - start;
            int savedLayouts = 0;
            for (int profile = 1; profile <= 3; profile++) {
                if (plugin.getKitProfileManager().hasProfile(player.getUniqueId(), kitId, profile)) savedLayouts++;
            }
            inventory.setItem(slot, menuItem(plugin.getKitManager().getIconMaterial(kitId),
                    "&b" + plugin.getKitManager().getDisplayName(kitId), List.of(
                            "&7Choose this kit to view its layouts.",
                            "&7Saved layouts: &f" + savedLayouts + "&7/3"
                    )));
            holder.mapKitSlot(slot, kitId);
        }
        if (page > 0) inventory.setItem(45, menuItem(Material.ARROW, "&ePrevious Page", List.of()));
        inventory.setItem(49, menuItem(Material.PAPER, "&7Page &f" + (page + 1) + "&7/&f" + pageCount, List.of()));
        if (page + 1 < pageCount) inventory.setItem(51, menuItem(Material.ARROW, "&eNext Page", List.of()));
        inventory.setItem(53, menuItem(Material.ARROW, "&eBack to Practice Menu", List.of("&7Return to the previous menu.")));
        player.openInventory(inventory);
    }

    private void openLayouts(Player player, String kitId, int returnPage) {
        if (!plugin.getKitManager().exists(kitId)) {
            openKitList(player, returnPage);
            return;
        }
        PracticeMenuHolder holder = new PracticeMenuHolder(PracticeMenuHolder.Type.LAYOUTS,
                player.getUniqueId(), kitId, returnPage, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Texts.component("&8Layouts &8» &b" + plugin.getKitManager().getDisplayName(kitId)));
        holder.setInventory(inventory);
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(4, menuItem(plugin.getKitManager().getIconMaterial(kitId),
                "&b" + plugin.getKitManager().getDisplayName(kitId), List.of(
                        "&7Select a layout to begin editing.",
                        "&7Your inventory is not changed until you select one."
                )));

        UUID uuid = player.getUniqueId();
        int selected = plugin.getKitProfileManager().selectedProfile(uuid, kitId);
        int[] layoutSlots = {11, 13, 15};
        for (int profile = 1; profile <= 3; profile++) {
            boolean exists = plugin.getKitProfileManager().hasProfile(uuid, kitId, profile);
            List<String> lore = new ArrayList<>();
            lore.add("&7Click to edit this layout.");
            lore.add(exists ? "&aSaved layout" : "&7Empty &8- &fuses the default kit");
            if (selected == profile && exists) lore.add("&bLast selected layout");
            inventory.setItem(layoutSlots[profile - 1], menuItem(Material.ENDER_CHEST,
                    "&eLayout #" + profile, lore));
        }
        inventory.setItem(22, menuItem(Material.ARROW, "&eBack to Kits", List.of()));
        player.openInventory(inventory);
    }

    private void openStats(Player player, int requestedPage) {
        List<String> kitIds = plugin.getKitManager().getKitIds();
        int pageCount = Math.max(1, (kitIds.size() + STATS_PAGE_SIZE - 1) / STATS_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        PracticeMenuHolder holder = new PracticeMenuHolder(PracticeMenuHolder.Type.STATS,
                player.getUniqueId(), null, page, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, Texts.component("&8Your Stats"));
        holder.setInventory(inventory);
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);

        int start = page * STATS_PAGE_SIZE;
        int end = Math.min(kitIds.size(), start + STATS_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            String kitId = kitIds.get(index);
            int slot = index - start;
            LeaderboardManager.KitStats stats = plugin.getLeaderboardManager()
                    .getPlayerKitStats(player.getUniqueId(), kitId);
            List<String> lore = List.of(
                    "&r",
                    "&eWins: &f" + stats.wins(),
                    "&eLosses: &f" + stats.losses(),
                    "&r",
                    "&eKDR: &f" + KDR_FORMAT.format(stats.kdr())
            );
            inventory.setItem(slot, menuItem(plugin.getKitManager().getIconMaterial(kitId),
                    "&e" + plugin.getKitManager().getDisplayName(kitId), lore));
        }
        if (page > 0) inventory.setItem(36, menuItem(Material.ARROW, "&ePrevious Page", List.of()));
        inventory.setItem(40, menuItem(Material.ARROW, "&eBack to Practice Menu", List.of()));
        if (page + 1 < pageCount) inventory.setItem(44, menuItem(Material.ARROW, "&eNext Page", List.of()));
        player.openInventory(inventory);
    }

    private void openRanks(Player player, int requestedPage) {
        List<String> kitIds = plugin.getKitManager().getKitIds();
        int pageCount = Math.max(1, (kitIds.size() + RANKS_PAGE_SIZE - 1) / RANKS_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        PracticeMenuHolder holder = new PracticeMenuHolder(PracticeMenuHolder.Type.RANKS,
                player.getUniqueId(), null, page, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, Texts.component("&8Ranks"));
        holder.setInventory(inventory);
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        for (int contentSlot : RANKS_CONTENT_SLOTS) inventory.setItem(contentSlot, new ItemStack(Material.BARRIER));

        inventory.setItem(4, menuItem(Material.ENCHANTED_BOOK, "&eRanks", List.of(
                "&7Your rank is tracked separately for each kit.",
                "&7Wins count toward your next division.",
                "&7Page " + (page + 1) + " / " + pageCount
        )));

        int start = page * RANKS_PAGE_SIZE;
        int end = Math.min(kitIds.size(), start + RANKS_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            String kitId = kitIds.get(index);
            int slot = RANKS_CONTENT_SLOTS[index - start];
            String kitName = plugin.getKitManager().getDisplayName(kitId);
            LeaderboardManager.KitStats stats = plugin.getLeaderboardManager()
                    .getPlayerKitStats(player.getUniqueId(), kitId);
            int wins = stats.wins();
            KitRankProgression.RankLevel current = KitRankProgression.currentLevel(wins);
            KitRankProgression.RankLevel next = KitRankProgression.nextLevel(wins);
            List<String> lore = new ArrayList<>();
            lore.add("");

            boolean hasPlayed = plugin.getLeaderboardManager().hasPlayedKit(player.getUniqueId(), kitId);
            if (!hasPlayed) {
                lore.add("&cLocked");
                lore.add("");
                String unlockName = next == null ? "Iron III" : next.displayName();
                int left = next == null ? KitRankProgression.winsToNext(0) : Math.max(0, next.requiredWins() - wins);
                lore.add("&eUnlock " + unlockName + " with " + left + " more wins!");
                inventory.setItem(slot, menuItem(Material.RED_STAINED_GLASS_PANE, "&e" + kitName, lore));
            } else {
                String actualRankDisplay = current == null ? "Unranked" : current.displayName();
                String rankDisplay = plugin.getPlayerPreferencesManager() == null
                        ? actualRankDisplay
                        : plugin.getPlayerPreferencesManager().visibleRank(player.getUniqueId(), wins);
                lore.add("&e· " + rankDisplay);
                if (next != null) {
                    int left = Math.max(0, next.requiredWins() - wins);
                    lore.add("&e◆ Unlock " + next.displayName() + " with " + left + " more wins!");
                } else {
                    lore.add("&e◆ Max rank achieved!");
                }
                inventory.setItem(slot, menuItem(plugin.getKitManager().getIconMaterial(kitId), "&e" + kitName, lore));
            }
            holder.mapKitSlot(slot, kitId);
        }

        if (page > 0) inventory.setItem(39, menuItem(Material.ARROW, "&ePrevious Page", List.of()));
        inventory.setItem(40, menuItem(Material.BARRIER, "&eBack to Practice Menu", List.of("&7Return to the practice menu.")));
        if (page + 1 < pageCount) inventory.setItem(41, menuItem(Material.ARROW, "&eNext Page", List.of()));
        player.openInventory(inventory);
    }

    private void beginEditing(Player player, String kitId, int profile) {
        if (!plugin.canUsePracticeMenu(player)) {
            player.closeInventory();
            return;
        }
        if (!plugin.getKitManager().exists(kitId) || profile < 1 || profile > 3) return;

        EditSession previous = editing.remove(player.getUniqueId());
        if (previous != null) cleanupLobby(player);

        // Loading for editing must never mark a profile selected or persist anything before Save.
        if (!plugin.getKitProfileManager().loadProfileForEditing(player, kitId, profile)) {
            plugin.getKitManager().apply(player.getInventory(), kitId);
        }
        player.setFoodLevel(20);
        player.setSaturation(20.0f);

        PracticeMenuHolder holder = new PracticeMenuHolder(PracticeMenuHolder.Type.EDITOR,
                player.getUniqueId(), kitId, 0, profile);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Texts.component("&8Editing &b" + plugin.getKitManager().getDisplayName(kitId)
                        + " &8- &eLayout #" + profile));
        holder.setInventory(inventory);
        fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(11, menuItem(Material.LIME_WOOL, "&aSave Layout", List.of(
                "&7Save this inventory layout.",
                "&7This updates Layout #" + profile + "."
        )));
        inventory.setItem(13, menuItem(Material.YELLOW_WOOL, "&eReset Layout", List.of(
                "&7Restore the kit's default layout.",
                "&7This is only a draft until you save."
        )));
        inventory.setItem(15, menuItem(Material.RED_WOOL, "&cCancel Editing", List.of(
                "&7Discard all unsaved changes.",
                "&7Return to your lobby items."
        )));

        EditSession session = new EditSession(kitId, profile, inventory);
        editing.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PracticeMenuHolder holder)) return;
        if (!holder.viewerId().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (holder.type() == PracticeMenuHolder.Type.EDITOR) {
            if (event.getClickedInventory() == top) {
                event.setCancelled(true);
                if (event.getRawSlot() < top.getSize()) {
                    int clickedSlot = event.getRawSlot();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) handleEditorClick(player, holder, clickedSlot);
                    });
                }
                return;
            }
            if (event.getClickedInventory() == player.getInventory()) {
                // Reordering inside the player's inventory is supported. Prevent actions that could
                // transfer gear into the control chest or swap gear with hidden lobby slots.
                ClickType click = event.getClick();
                if (click != ClickType.LEFT && click != ClickType.RIGHT) event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() != top || event.getRawSlot() >= top.getSize()) return;
        int slot = event.getRawSlot();
        ClickType clickType = event.getClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) handleMenuClick(player, holder, slot, clickType);
        });
    }

    private void handleMenuClick(Player player, PracticeMenuHolder holder, int slot, ClickType clickType) {
        switch (holder.type()) {
            case MAIN -> {
                if (slot == 11) openKitList(player, 0);
                else if (slot == 13) openStats(player, 0);
                else if (slot == 15) openRanks(player, 0);
                else if (slot == 29 && plugin.getCosmeticsManager() != null) plugin.getCosmeticsManager().openMenu(player);
                else if (slot == 31 && plugin.getTournamentManager() != null) plugin.getTournamentManager().openHostDialog(player);
            }
            case KIT_LIST -> {
                if (slot == 53) { openPracticeMenu(player); return; }
                if (slot == 45 && holder.page() > 0) { openKitList(player, holder.page() - 1); return; }
                if (slot == 51) { openKitList(player, holder.page() + 1); return; }
                String kitId = holder.kitAt(slot);
                if (kitId != null && plugin.getKitManager().exists(kitId)) openLayouts(player, kitId, holder.page());
            }
            case LAYOUTS -> {
                if (slot == 22) { openKitList(player, holder.page()); return; }
                int profile = switch (slot) { case 11 -> 1; case 13 -> 2; case 15 -> 3; default -> -1; };
                if (profile != -1) beginEditing(player, holder.kitId(), profile);
            }
            case STATS -> {
                if (slot == 40) { openPracticeMenu(player); return; }
                if (slot == 36 && holder.page() > 0) { openStats(player, holder.page() - 1); return; }
                if (slot == 44) openStats(player, holder.page() + 1);
            }
            case RANKS -> {
                if (slot == 40) { openPracticeMenu(player); return; }
                if (slot == 39 && holder.page() > 0) { openRanks(player, holder.page() - 1); return; }
                if (slot == 41) openRanks(player, holder.page() + 1);
            }
            case EDITOR -> { /* handled by handleEditorClick */ }
        }
    }

    private void handleEditorClick(Player player, PracticeMenuHolder holder, int slot) {
        EditSession session = editing.get(player.getUniqueId());
        if (session == null || session.inventory != holder.getInventory()) return;
        if (slot == 11) {
            ItemStack cursorItem = player.getItemOnCursor();
            if (cursorItem != null && !cursorItem.getType().isAir()) {
                Texts.send(player, "Place the item held by your cursor into your inventory before saving.");
                return;
            }
            try {
                plugin.getKitProfileManager().saveProfile(player, session.kitId, session.profile);
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not save kit layout for " + player.getName() + ": " + exception.getMessage());
                Texts.send(player, "Could not save this layout. Check the server console; your draft is still open.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
                return;
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
            Texts.send(player, "Saved &b" + plugin.getKitManager().getDisplayName(session.kitId)
                    + "&r Layout #" + session.profile + ".");
            finishEditing(player, session, true);
        } else if (slot == 13) {
            plugin.getKitManager().apply(player.getInventory(), session.kitId);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
        } else if (slot == 15) {
            finishEditing(player, session, false);
        }
    }

    private void finishEditing(Player player, EditSession session, boolean saved) {
        UUID uuid = player.getUniqueId();
        if (editing.get(uuid) != session) return;
        editing.remove(uuid);
        player.closeInventory();
        cleanupLobby(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof PracticeMenuHolder holder)
                || holder.type() != PracticeMenuHolder.Type.EDITOR) return;
        EditSession closedSession = editing.get(player.getUniqueId());
        if (closedSession == null || closedSession.inventory != event.getInventory()) return;

        // Escape/close is always a discard. Delay cleanup by one tick so Bukkit finishes closing
        // the view first; a save/cancel button removes the session before closing and won't hit this.
        Bukkit.getScheduler().runTask(plugin, () -> {
            UUID uuid = player.getUniqueId();
            if (editing.get(uuid) != closedSession) return;
            editing.remove(uuid);
            if (player.isOnline()) cleanupLobby(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PracticeMenuHolder holder)) return;
        if (holder.type() != PracticeMenuHolder.Type.EDITOR) {
            event.setCancelled(true);
            return;
        }
        // Only allow drag operations whose entire destination is the player's inventory.
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        EditSession session = editing.remove(event.getPlayer().getUniqueId());
        if (session != null) {
            // Do not persist an in-progress kit draft into the player's ordinary lobby inventory.
            cleanupLobby(event.getPlayer());
        }
    }

    public void shutdown() {
        for (Map.Entry<UUID, EditSession> entry : new ArrayList<>(editing.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            editing.remove(entry.getKey());
            if (player != null && player.isOnline()) {
                player.closeInventory();
                cleanupLobby(player);
            }
        }
    }

    private void cleanupLobby(Player player) {
        if (player == null || !player.isOnline()) return;
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.setItemOnCursor(new ItemStack(Material.AIR));
        plugin.giveLobbyItems(player);
    }

    private void fill(Inventory inventory, Material material) {
        ItemStack filler = menuItem(material, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler.clone());
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null || material.isAir() ? Material.CHEST : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component(name));
        if (lore != null && !lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(Texts.lore(line));
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static final class EditSession {
        private final String kitId;
        private final int profile;
        private final Inventory inventory;

        private EditSession(String kitId, int profile, Inventory inventory) {
            this.kitId = kitId;
            this.profile = profile;
            this.inventory = inventory;
        }
    }
}
