package com.vprac.arena;

import com.vprac.VpracPlugin;
import com.vprac.util.Texts;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Chest-style administration UI for arena templates, physical copies and kit compatibility. */
public final class ArenaMenuManager implements Listener {
    private static final int PAGE_SIZE = 28;
    private static final int MAX_COPIES = ArenaManager.maxInstances();
    private static final int[] ARENA_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int[] KIT_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final VpracPlugin plugin;
    private final ArenaManager arenas;
    private final Map<UUID, String> pendingRename = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingCopyCountEdit = new ConcurrentHashMap<>();

    public ArenaMenuManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.arenas = plugin.getArenaManager();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Texts.send(sender, "This command can only be used by a player.");
            return true;
        }
        if (!player.hasPermission("vprac.admin.arenamenu")) {
            Texts.send(player, "You do not have permission to use /varenamenu.");
            return true;
        }
        if (args.length != 0) {
            Texts.send(player, "Usage: /varenamenu");
            return true;
        }
        openArenaList(player, 0);
        return true;
    }

    private void openArenaList(Player player, int page) {
        List<ArenaData> all = new ArrayList<>(arenas.all());
        all.sort(Comparator.comparing(ArenaData::id, String.CASE_INSENSITIVE_ORDER));
        int maxPage = Math.max(0, (all.size() - 1) / PAGE_SIZE);
        int safePage = Math.max(0, Math.min(page, maxPage));
        ArenaMenuHolder holder = new ArenaMenuHolder(ArenaMenuHolder.Type.ARENA_LIST, null, safePage, 1);
        Inventory inventory = Bukkit.createInventory(holder, 54, Texts.component("&8Arena Manager"));
        holder.setInventory(inventory);
        fillBorder(inventory);
        inventory.setItem(4, menuItem(Material.COMPASS, "&6NexPractice Arena Manager", List.of(
                "&7Select an arena to edit its instances, compatible kits, name, and status.",
                "&7Pages: &f" + (safePage + 1) + "&7/&f" + (maxPage + 1),
                "&8Create new templates with /varena create <name>."
        )));
        int start = safePage * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < all.size(); i++) {
            ArenaData arena = all.get(start + i);
            int slot = ARENA_SLOTS[i];
            Material icon = arena.enabled() ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
            List<String> lore = new ArrayList<>();
            lore.add("&7Status: " + (arenas.isRecovering(arena.id()) ? "&eRecovering" : (arena.enabled() ? "&aEnabled" : "&cDisabled")));
            lore.add("&7Instances: &f" + arenas.copyCount(arena.id()));
            lore.add("&7Region saved: " + (arena.hasRegion() ? "&aYes" : "&cNo"));
            lore.add("&7Spawns ready: " + (arena.ready() ? "&aYes" : "&cNo"));
            lore.add("&7Party fights: " + (arena.partyFfa() ? "&aEnabled" : "&7Not enabled"));
            lore.add("&7Bedfight: " + (arena.bedfightEnabled() ? "&aEnabled" : "&7Disabled"));
            lore.add("&7Kit rules: " + (arena.kitRestricted() ? "&eSpecific kits" : "&aAll kits"));
            lore.add("");
            lore.add("&eClick to manage this arena.");
            inventory.setItem(slot, menuItem(icon, "&b" + arena.id(), lore));
            holder.mapArenaSlot(slot, arena.id());
        }
        inventory.setItem(45, menuItem(Material.ARROW, "&ePrevious page", List.of("&7Go to the previous list page.")));
        inventory.setItem(49, menuItem(Material.BARRIER, "&cClose", List.of()));
        inventory.setItem(53, menuItem(Material.ARROW, "&eNext page", List.of("&7Go to the next list page.")));
        if (safePage == 0) inventory.setItem(45, menuItem(Material.GRAY_STAINED_GLASS_PANE, "&8Previous page", List.of("&7You are on the first page.")));
        if (safePage >= maxPage) inventory.setItem(53, menuItem(Material.GRAY_STAINED_GLASS_PANE, "&8Next page", List.of("&7You are on the last page.")));
        player.openInventory(inventory);
    }

    private void openArenaEditor(Player player, String id, int kitPage, int desiredCopies) {
        ArenaData arena = arenas.get(id);
        if (arena == null) { Texts.send(player, "That arena no longer exists."); openArenaList(player, 0); return; }
        int actualCopies = Math.max(1, arenas.copyCount(id));
        int desired = Math.max(1, Math.min(MAX_COPIES, desiredCopies));
        int pageCount = Math.max(1, (plugin.getKitManager().getKitIds().size() + KIT_SLOTS.length - 1) / KIT_SLOTS.length);
        int safeKitPage = Math.max(0, Math.min(kitPage, pageCount - 1));
        ArenaMenuHolder holder = new ArenaMenuHolder(ArenaMenuHolder.Type.ARENA_EDITOR, arena.id(), safeKitPage, desired);
        holder.setKitRestrictedAtRender(arena.kitRestricted());
        Inventory inventory = Bukkit.createInventory(holder, 54, Texts.component("&8Arena: &b" + arena.id()));
        holder.setInventory(inventory);
        fillBorder(inventory);

        inventory.setItem(4, menuItem(Material.MAP, "&b" + arena.id(), List.of(
                "&7World: &f" + (arena.regionWorld() == null ? "Not set" : arena.regionWorld()),
                "&7Template region: " + (arena.hasRegion() ? "&aSaved" : "&cNot saved"),
                "&7Standard spawns: " + (arena.ready() ? "&aReady" : "&cIncomplete"),
                "&7Party FFA: " + (arena.partyFfa() ? "&aEnabled" : "&7Disabled"),
                "&7Bedfight: " + (arena.bedfightEnabled() ? "&aEnabled" : "&cDisabled"),
                "&7Bedfight setup: " + (arena.bedfightReady() ? "&aComplete" : "&cIncomplete"),
                "&7In use: " + (arenas.isInUse(id) ? "&cYes" : "&aNo"),
                "&7Instances include the original template map.",
                "&7Generated copies occupy different X/Z coordinates in this world.",
                "&7Reserve a clear arena area; original destination blocks are backed up."
        )));
        inventory.setItem(10, menuItem(Material.RED_DYE, "&c- Remove one instance", List.of(
                "&7Desired count: &f" + desired,
                "&7Minimum: 1 (the original template).",
                "&eClick to physically remove one generated instance."
        )));
        inventory.setItem(11, menuItem(Material.PAPER, "&6Instances: &f" + desired, List.of(
                "&7Currently generated: &f" + actualCopies,
                "&7Desired after Apply: &f" + desired,
                "&7Maximum: &f" + MAX_COPIES,
                "&eClick to type an exact total count.",
                "&8Use +/- to generate/remove immediately, or type an exact count and Apply."
        )));
        inventory.setItem(12, menuItem(Material.LIME_DYE, "&a+ Add one instance", List.of(
                "&7Desired count: &f" + desired,
                "&eClick to physically generate one new instance."
        )));
        inventory.setItem(14, menuItem(Material.CRAFTING_TABLE, "&aApply Instances", List.of(
                "&7Generate or restore copies with FAWE.",
                "&7Current: &f" + actualCopies + " &7→ Desired: &f" + desired,
                "&7Copies are physically separated in this same world.",
                "&7Existing destination blocks are backed up before pasting.",
                "&cAll matches using this arena must finish first.",
                "&eClick to apply the selected count."
        )));
        inventory.setItem(15, menuItem(arena.kitRestricted() ? Material.HOPPER : Material.CHEST,
                arena.kitRestricted() ? "&eKit Compatibility: &6Specific kits" : "&aKit Compatibility: &fAll kits",
                List.of(
                        "&7All kits: every queue kit may use this arena.",
                        "&7Specific kits: click kit icons below to allow them.",
                        "&eClick to switch to " + (arena.kitRestricted() ? "all kits." : "specific kit selection.")
                )));
        boolean busy = arenas.isInUse(id) || arenas.copiesBusy(id) || arenas.isRecovering(id);
        inventory.setItem(16, menuItem(arena.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                arena.enabled() ? "&aArena Enabled" : "&cArena Disabled",
                List.of("&7Enabled arenas can be assigned to matches.",
                        busy ? (arenas.isRecovering(id) ? "&eRestoring instances at startup." : "&cCurrently in use or processing.") : "&eClick to toggle enabled state.")));
        inventory.setItem(17, menuItem(arena.bedfightEnabled() ? Material.RED_BED : Material.GRAY_BED,
                arena.bedfightEnabled() ? "&aBedfight: Enabled" : "&cBedfight: Disabled",
                List.of("&7Bedfight is queue-only and uses two stored beds plus a void region.",
                        "&7Setup beds: /varena bedsetup " + arena.id(),
                        "&7Set void: /varena bedsetvoid " + arena.id(),
                        "&7Configuration: " + (arena.bedfightReady() ? "&aComplete" : "&cIncomplete"),
                        "&eClick to " + (arena.bedfightEnabled() ? "disable" : "enable") + " Bedfight.")));
        inventory.setItem(13, menuItem(Material.PAPER, "&bKit selection", List.of(
                "&7Click a kit icon below to allow or block it.",
                "&7Green check: allowed; red X: blocked.",
                arena.kitRestricted() ? "&eSpecific-kit mode is active." : "&aAll kits currently allowed.",
                arena.kitRestricted() && arena.allowedKits().isEmpty()
                        ? "&cNo kits allowed: this arena remains enabled but won't match any kit queue until one is allowed."
                        : "&7Kit restrictions never change the arena's enabled state."
        )));

        List<String> kitIds = plugin.getKitManager().getKitIds();
        int start = safeKitPage * KIT_SLOTS.length;
        for (int i = 0; i < KIT_SLOTS.length && start + i < kitIds.size(); i++) {
            String kitId = kitIds.get(start + i);
            boolean allowed = !arena.kitRestricted() || arena.allowedKits().contains(kitId.toLowerCase(Locale.ROOT));
            Material icon = plugin.getKitManager().getIconMaterial(kitId);
            List<String> lore = new ArrayList<>();
            lore.add("&7Kit: &f" + plugin.getKitManager().getDisplayName(kitId));
            lore.add("&7Queue access: " + (allowed ? "&a✔ Allowed" : "&c✘ Blocked"));
            if (!arena.kitRestricted()) lore.add("&7All kits are allowed globally.");
            lore.add("");
            if (!arena.kitRestricted()) lore.add("&eClick to allow ONLY this kit (switch to specific-kit mode)." );
            else lore.add("&eClick to " + (allowed ? "block this kit." : "allow this kit."));
            int slot = KIT_SLOTS[i];
            inventory.setItem(slot, menuItem(allowed ? icon : Material.BARRIER,
                    (allowed ? "&a✔ " : "&c✘ ") + plugin.getKitManager().getDisplayName(kitId), lore));
            holder.mapKitSlot(slot, kitId, allowed);
        }
        inventory.setItem(45, safeKitPage == 0
                ? menuItem(Material.GRAY_STAINED_GLASS_PANE, "&8Previous kits", List.of())
                : menuItem(Material.ARROW, "&ePrevious kits", List.of("&7Page " + safeKitPage + " of " + pageCount)));
        inventory.setItem(46, menuItem(Material.BOOK, "&eBack to arenas", List.of("&7Return to the arena list.")));
        inventory.setItem(48, menuItem(Material.NAME_TAG, "&eRename arena", List.of("&7The next chat message becomes the new arena name.", "&7Allowed: letters, numbers, _ and -.", "&eClick then type the new name or 'cancel'.")));
        inventory.setItem(49, menuItem(Material.TNT, "&cDelete arena", List.of("&7Deletes the arena config and generated copies.", "&7Generated-copy locations are restored from backups.", "&7The original selected arena stays in the world.", "&cThis cannot be undone for the arena configuration.")));
        inventory.setItem(50, menuItem(Material.BARRIER, "&cClose", List.of()));
        inventory.setItem(53, safeKitPage >= pageCount - 1
                ? menuItem(Material.GRAY_STAINED_GLASS_PANE, "&8Next kits", List.of())
                : menuItem(Material.ARROW, "&eNext kits", List.of("&7Page " + (safeKitPage + 2) + " of " + pageCount)));
        player.openInventory(inventory);
    }

    private void applyCopyCount(Player player, String id, int page, int requestedCopies) {
        int desired = Math.max(1, Math.min(MAX_COPIES, requestedCopies));
        int actual = arenas.copyCount(id);
        if (desired == actual) {
            Texts.send(player, "The arena already has " + actual + " generated instance(s).");
            openArenaEditor(player, id, page, actual);
            return;
        }
        if (arenas.isInUse(id) || arenas.copiesBusy(id) || arenas.isRecovering(id)) {
            Texts.send(player, "Wait until this arena is not in use or processing before changing its instances.");
            openArenaEditor(player, id, page, actual);
            return;
        }
        player.closeInventory();
        Texts.send(player, "FAWE is preparing the arena instances. Existing matches on this template are not allowed during this operation.");
        boolean accepted = arenas.resizeCopies(id, desired, success -> {
            if (!player.isOnline()) return;
            int count = arenas.copyCount(id);
            Texts.send(player, success
                    ? "Arena '&b" + id + "&r' now has &f" + count + "&r physical instance(s)."
                    : "Instance update stopped before completion. The arena currently has &f" + count + "&r tracked instance(s); check console and destination backups.");
            if (success) plugin.getQueueManager().tryMatchAll();
            openArenaEditor(player, id, page, count);
        });
        if (!accepted) {
            Texts.send(player, "Could not change copies. Ensure the arena border, both spawns, template snapshot and no active matches are present.");
            openArenaEditor(player, id, page, actual);
        }
    }

    private void openDeleteConfirmation(Player player, String id) {
        ArenaData arena = arenas.get(id);
        if (arena == null) { openArenaList(player, 0); return; }
        ArenaMenuHolder holder = new ArenaMenuHolder(ArenaMenuHolder.Type.DELETE_CONFIRM, id, 0, arenas.copyCount(id));
        Inventory inventory = Bukkit.createInventory(holder, 27, Texts.component("&4Delete arena: &c" + id + "?"));
        holder.setInventory(inventory);
        fillBorder(inventory);
        inventory.setItem(13, menuItem(Material.TNT, "&cDelete &f" + id, List.of(
                "&7NexPractice will restore saved background blocks", 
                "&7under generated copies, then remove its config.",
                "&7The original template region will be left in place.",
                "&cThe arena must not be running a match."
        )));
        inventory.setItem(11, menuItem(Material.RED_CONCRETE, "&cConfirm delete", List.of("&cDelete this arena and its generated copies.")));
        inventory.setItem(15, menuItem(Material.LIME_CONCRETE, "&aCancel", List.of("&7Return without deleting.")));
        player.openInventory(inventory);
    }

    private void handleClick(Player player, ArenaMenuHolder holder, int slot) {
        if (holder.type() == ArenaMenuHolder.Type.ARENA_LIST) {
            if (slot == 49) { player.closeInventory(); return; }
            if (slot == 45 && holder.page() > 0) { openArenaList(player, holder.page() - 1); return; }
            if (slot == 53) { openArenaList(player, holder.page() + 1); return; }
            String arenaId = holder.arenaAt(slot);
            if (arenaId != null) openArenaEditor(player, arenaId, 0, arenas.copyCount(arenaId));
            return;
        }
        if (holder.type() == ArenaMenuHolder.Type.DELETE_CONFIRM) {
            if (slot == 15) { openArenaEditor(player, holder.arenaId(), 0, arenas.copyCount(holder.arenaId())); return; }
            if (slot != 11) return;
            String id = holder.arenaId();
            if (arenas.isInUse(id)) { Texts.send(player, "That arena is in use; wait for every match on it to finish."); openArenaEditor(player, id, 0, arenas.copyCount(id)); return; }
            player.closeInventory();
            Texts.send(player, "Restoring generated-copy locations and deleting arena '&b" + id + "&r'...");
            boolean accepted = arenas.deleteArena(id, success -> {
                if (!player.isOnline()) return;
                if (success) Texts.send(player, "Deleted arena '&b" + id + "&r'. The original template itself remains physically in the world.");
                else Texts.send(player, "Arena deletion failed. It was retained where restoration could not be completed; check console and snapshots.");
                openArenaList(player, 0);
            });
            if (!accepted) { Texts.send(player, "Could not start deletion. Make sure no matches, arena edits, or FAWE operations are in progress."); openArenaEditor(player, id, 0, arenas.copyCount(id)); }
            return;
        }

        String id = holder.arenaId();
        ArenaData arena = arenas.get(id);
        if (arena == null) { Texts.send(player, "That arena no longer exists."); openArenaList(player, 0); return; }
        if (slot == 46) { openArenaList(player, 0); return; }
        if (slot == 50) { player.closeInventory(); return; }
        if (slot == 45 && holder.page() > 0) { openArenaEditor(player, id, holder.page() - 1, holder.desiredCopies()); return; }
        if (slot == 53) { openArenaEditor(player, id, holder.page() + 1, holder.desiredCopies()); return; }
        if (slot == 10) { applyCopyCount(player, id, holder.page(), Math.max(1, arenas.copyCount(id) - 1)); return; }
        if (slot == 11) {
            pendingRename.remove(player.getUniqueId());
            pendingCopyCountEdit.put(player.getUniqueId(), id);
            player.closeInventory();
            Texts.send(player, "Type the desired total arena instance count (1-" + MAX_COPIES + "), or type 'cancel'. This includes the original arena.");
            return;
        }
        if (slot == 12) { applyCopyCount(player, id, holder.page(), Math.min(MAX_COPIES, arenas.copyCount(id) + 1)); return; }
        if (slot == 14) { applyCopyCount(player, id, holder.page(), holder.desiredCopies()); return; }
        if (slot == 15) {
            if (arenas.isInUse(id)) { Texts.send(player, "Arena kit rules cannot be changed while it is in use."); return; }
            boolean newRestricted = !arena.kitRestricted();
            if (arenas.setKitRestricted(id, newRestricted)) {
                Texts.send(player, newRestricted
                        ? "Specific-kit mode enabled. Click kits below to choose which are allowed. The arena stays enabled; until a kit is allowed, no kit queue can use it."
                        : "This arena now accepts all kits. Its enabled state was not changed.");
                openArenaEditor(player, id, holder.page(), holder.desiredCopies());
            } else Texts.send(player, "Could not update kit compatibility while this arena is busy.");
            return;
        }
        if (slot == 16) {
            if (arenas.isInUse(id)) { Texts.send(player, "This arena is currently hosting a match."); return; }
            boolean targetEnabled = !arena.enabled();
            if (!arenas.setEnabled(id, targetEnabled)) {
                String reason = targetEnabled ? arenas.enableFailureReason(id) : null;
                if (targetEnabled && reason == null) reason = "the arena settings could not be saved to plugins/NexPractice/arenas.yml; check the console and file permissions";
                Texts.send(player, targetEnabled
                        ? "Could not enable arena: " + reason + ". Unsafe Party FFA spawn spots only prevent Party FFA queue eligibility."
                        : "Could not change arena state while a copy, snapshot, delete, recovery, or match operation is running.");
            } else {
                Texts.send(player, targetEnabled ? "Arena '&b" + id + "&r' enabled." : "Arena '&b" + id + "&r' disabled.");
                if (targetEnabled) plugin.getQueueManager().tryMatchAll();
            }
            openArenaEditor(player, id, holder.page(), holder.desiredCopies());
            return;
        }
        if (slot == 17) {
            if (arenas.isInUse(id)) { Texts.send(player, "Bedfight cannot be changed while the arena is in use."); return; }
            boolean target = !arena.bedfightEnabled();
            if (arenas.setBedfightEnabled(id, target)) {
                Texts.send(player, target ? "Bedfight enabled for '&b" + id + "&r'. Solo queue matches may select this arena." : "Bedfight disabled for '&b" + id + "&r'.");
                if (target) plugin.getQueueManager().tryMatchAll();
            } else {
                String reason = target ? arenas.bedfightFailureReason(id) : null;
                Texts.send(player, "Could not " + (target ? "enable" : "disable") + " Bedfight" + (reason == null ? ". Check arena settings and file permissions." : ": " + reason + "."));
            }
            openArenaEditor(player, id, holder.page(), holder.desiredCopies());
            return;
        }
        if (slot == 48) {
            if (arenas.isInUse(id) || arenas.copiesBusy(id)) { Texts.send(player, "You cannot rename an arena while it is in use or being processed."); return; }
            pendingCopyCountEdit.remove(player.getUniqueId());
            pendingRename.put(player.getUniqueId(), id);
            player.closeInventory();
            Texts.send(player, "Type the new arena name in chat, or type 'cancel'. Allowed characters: letters, numbers, underscore and hyphen (maximum 32).");
            return;
        }
        if (slot == 49) {
            if (arenas.isInUse(id) || arenas.copiesBusy(id)) { Texts.send(player, "You cannot delete an arena while it is in use or being processed."); return; }
            openDeleteConfirmation(player, id);
            return;
        }
        String kitId = holder.kitAt(slot);
        if (kitId != null && plugin.getKitManager().exists(kitId)) {
            if (arenas.isInUse(id)) { Texts.send(player, "Kit restrictions cannot be changed while the arena is in use."); return; }
            boolean success;
            boolean restrictedWhenShown = holder.kitRestrictedAtRender();
            boolean shownAllowed = holder.kitAllowedAt(slot);

            // Apply the action represented by the icon that was actually rendered, rather than
            // blindly toggling current state. This makes rapid/double clicks and stale inventory
            // events idempotent instead of accidentally removing the kit just added.
            if (!restrictedWhenShown) {
                // In All-kits mode, selecting an icon means "allow only this kit".
                if (arena.kitRestricted()) {
                    Texts.send(player, "Kit compatibility changed while this menu was open; refreshed the menu. Please click again.");
                    openArenaEditor(player, id, holder.page(), holder.desiredCopies());
                    return;
                }
                success = arenas.setSpecificAllowedKits(id, Set.of(kitId));
                if (success) Texts.send(player, "Specific-kit mode enabled. Only '&b" + plugin.getKitManager().getDisplayName(kitId) + "&r' is allowed for this arena.");
            } else {
                if (!arena.kitRestricted()) {
                    Texts.send(player, "Kit compatibility changed while this menu was open; refreshed the menu. Please click again.");
                    openArenaEditor(player, id, holder.page(), holder.desiredCopies());
                    return;
                }
                boolean currentAllowed = arena.allowedKits().contains(kitId.toLowerCase(Locale.ROOT));
                if (currentAllowed != shownAllowed) {
                    Texts.send(player, "This kit's allowed state already changed; the menu has been refreshed.");
                    openArenaEditor(player, id, holder.page(), holder.desiredCopies());
                    return;
                }
                boolean targetAllowed = !shownAllowed;
                success = arenas.setKitAllowed(id, kitId, targetAllowed);
                if (success && targetAllowed) Texts.send(player, "Kit '&b" + plugin.getKitManager().getDisplayName(kitId) + "&r' is now allowed for this arena.");
                else if (success && !targetAllowed && arenas.get(id).allowedKits().isEmpty())
                    Texts.send(player, "Kit '&b" + plugin.getKitManager().getDisplayName(kitId) + "&r' blocked. No kits are currently allowed for this arena; the arena remains enabled. Click a red-X kit to allow it.");
                else if (success) Texts.send(player, "Kit '&b" + plugin.getKitManager().getDisplayName(kitId) + "&r' is now blocked for this arena.");
            }
            if (!success) Texts.send(player, "Could not change kit compatibility. Ensure the arena isn't in use, being copied, or recovering.");
            openArenaEditor(player, id, holder.page(), holder.desiredCopies());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingRename.remove(uuid);
        pendingCopyCountEdit.remove(uuid);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ArenaMenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != top || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) handleClick(player, holder, slot); });
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ArenaMenuHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onRenameChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String countArenaId = pendingCopyCountEdit.remove(uuid);
        if (countArenaId != null) {
            String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) return;
                if (input.equalsIgnoreCase("cancel")) {
                    Texts.send(player, "Instance-count change cancelled.");
                    openArenaEditor(player, countArenaId, 0, arenas.copyCount(countArenaId));
                    return;
                }
                int desired;
                try { desired = Integer.parseInt(input); }
                catch (NumberFormatException exception) { desired = -1; }
                if (desired < 1 || desired > MAX_COPIES) {
                    Texts.send(player, "Enter a whole number from 1 to " + MAX_COPIES + ", or type 'cancel'.");
                    openArenaEditor(player, countArenaId, 0, arenas.copyCount(countArenaId));
                    return;
                }
                Texts.send(player, "Desired total instance count set to &f" + desired + "&r. Click Apply Instances to apply it.");
                openArenaEditor(player, countArenaId, 0, desired);
            });
            return;
        }

        String arenaId = pendingRename.remove(uuid);
        if (arenaId == null) return;
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;
            if (input.equalsIgnoreCase("cancel")) {
                Texts.send(player, "Arena rename cancelled.");
                openArenaEditor(player, arenaId, 0, arenas.copyCount(arenaId));
                return;
            }
            if (!arenas.validId(input)) {
                Texts.send(player, "Invalid arena name. Use 1-32 letters, numbers, underscores or hyphens.");
                openArenaEditor(player, arenaId, 0, arenas.copyCount(arenaId));
                return;
            }
            if (arenas.renameArena(arenaId, input)) {
                Texts.send(player, "Renamed arena '&b" + arenaId + "&r' to '&b" + input.toLowerCase(Locale.ROOT) + "&r'.");
                openArenaList(player, 0);
            } else {
                Texts.send(player, "Rename failed. The new name may already exist, or a match/copy operation may be in progress.");
                openArenaEditor(player, arenaId, 0, arenas.copyCount(arenaId));
            }
        });
    }

    private void fillBorder(Inventory inventory) {
        ItemStack pane = menuItem(Material.GRAY_STAINED_GLASS_PANE, "&8 ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9, col = slot % 9;
            if (row == 0 || row == inventory.getSize() / 9 - 1 || col == 0 || col == 8) inventory.setItem(slot, pane.clone());
        }
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        Material safe = material == null || material.isAir() ? Material.CHEST : material;
        ItemStack item = new ItemStack(safe);
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
}
