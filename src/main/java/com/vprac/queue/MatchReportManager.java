package com.vprac.queue;

import com.vprac.VpracPlugin;
import com.vprac.util.Texts;
import com.vprac.util.ItemTooltips;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Death messages and read-only post-death inventory reports for NexPractice matches. */
public final class MatchReportManager {
    private static final long REPORT_VIEW_LIFETIME_TICKS = 20L * 60L;
    private static final long REPORT_RETENTION_TICKS = 20L * 60L * 5L;
    private final VpracPlugin plugin;
    private final Map<String, MatchReport> reports = new HashMap<>();

    public MatchReportManager(VpracPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called before match managers clear the dead player's inventory; vanilla death broadcasts are suppressed by VpracPlugin. */
    public void handleDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Player killer = dead.getKiller();
        UUID deadId = dead.getUniqueId();
        Match solo = plugin.getQueueManager() == null ? null : plugin.getQueueManager().getMatch(deadId);
        PartyMatchManager partyManager = plugin.getPartyMatchManager();
        PartyMatch partyMatch = partyManager == null ? null : partyManager.getActiveMatch(deadId);
        Set<UUID> creeperEligible = new LinkedHashSet<>();
        if (solo != null) {
            creeperEligible.add(solo.opponent(deadId));
        } else if (partyMatch != null) {
            for (UUID memberId : partyMatch.members()) {
                if (memberId.equals(deadId) || !partyMatch.isAlive(memberId)) continue;
                if (partyMatch.mode() == PartyMatch.Mode.FFA
                        || partyMatch.team(memberId) != partyMatch.team(deadId)) creeperEligible.add(memberId);
            }
        }
        UUID creeperCredit = plugin.peekCreeperKillCredit(dead, creeperEligible);
        if (creeperCredit != null) {
            Player creeperAttacker = Bukkit.getPlayer(creeperCredit);
            if (creeperAttacker != null) killer = creeperAttacker;
        }
        boolean validPartyKill = partyMatch != null && killer != null
                && !killer.getUniqueId().equals(deadId)
                && partyMatch.onArena() && !partyMatch.finishing() && partyMatch.startedAtMillis() > 0L
                && partyMatch.isAlive(deadId) && partyMatch.isAlive(killer.getUniqueId())
                && partyMatch.members().contains(killer.getUniqueId())
                && (partyMatch.mode() == PartyMatch.Mode.FFA
                    || partyMatch.team(killer.getUniqueId()) != partyMatch.team(deadId));

        if (killer != null && !killer.getUniqueId().equals(deadId)) {
            if (partyMatch != null) {
                if (validPartyKill) {
                    Component slain = plugin.getCosmeticsManager() == null ? null
                            : plugin.getCosmeticsManager().handleMatchKill(killer, dead);
                    if (slain == null) {
                        slain = Component.text(dead.getName(), NamedTextColor.RED)
                                .append(Component.text(" was slain by ", NamedTextColor.GRAY))
                                .append(Component.text(killer.getName(), NamedTextColor.GOLD));
                    }
                    slain = Texts.nonItalic(slain);
                    for (UUID recipientId : partyMatch.members()) {
                        // Tell players still alive in this party fight, including the victim before elimination.
                        if (!partyMatch.isAlive(recipientId) && !recipientId.equals(deadId)) continue;
                        if (partyMatch.isReturned(recipientId)) continue;
                        Player recipient = Bukkit.getPlayer(recipientId);
                        if (recipient != null && recipient.isOnline()) recipient.sendMessage(slain);
                    }
                    killer.sendActionBar(Texts.component("&c&lKI&4&lLL! &7" + dead.getName()));
                }
            } else {
                Component slain = plugin.getCosmeticsManager() == null ? null
                        : plugin.getCosmeticsManager().handleMatchKill(killer, dead);
                if (slain == null) {
                    slain = Component.text(dead.getName(), NamedTextColor.RED)
                            .append(Component.text(" was slain by ", NamedTextColor.GRAY))
                            .append(Component.text(killer.getName(), NamedTextColor.GOLD));
                }
                slain = Texts.nonItalic(slain);
                killer.sendMessage(slain);
                dead.sendMessage(slain);
            }
        }

        if (solo != null) {
            if (!solo.finishing()) {
                UUID winnerId = solo.opponent(deadId);
                boolean decisiveRound = solo.roundWins(winnerId) + 1 >= solo.roundsToWin();
                Player winner = Bukkit.getPlayer(winnerId);
                // Bedfight deaths with an intact bed are lives, not decisive round results.
                if (decisiveRound && !(solo.bedfight() && solo.bedIntact(deadId))
                        && winner != null && winner.isOnline()) {
                    List<UUID> viewers = List.of(winnerId, deadId);
                    createAndSendReport(winner, dead, viewers);
                }
            }
            return;
        }

        if (!validPartyKill || partyMatch == null || killer == null) return;
        if (isDecisivePartyRoundDeath(partyMatch, deadId, killer.getUniqueId())) {
            createAndSendReport(killer, dead, List.of(killer.getUniqueId(), deadId));
        }
    }

    private boolean isDecisivePartyRoundDeath(PartyMatch match, UUID deadId, UUID killerId) {
        if (match == null || killerId == null || match.isAlive(killerId) == false) return false;
        if (match.mode() == PartyMatch.Mode.FFA) {
            // This death must end the current FFA round, and the surviving killer must reach the target.
            if (match.alive().size() > 2) return false;
            UUID survivor = match.alive().stream().filter(uuid -> !uuid.equals(deadId)).findFirst().orElse(null);
            return killerId.equals(survivor)
                    && match.roundWins(killerId) + 1 >= match.roundsToWin();
        }

        int deadTeam = match.team(deadId);
        boolean lastMemberOfDeadTeam = match.alive().stream()
                .noneMatch(uuid -> !uuid.equals(deadId) && match.team(uuid) == deadTeam);
        int winningTeam = match.team(killerId);
        return lastMemberOfDeadTeam && winningTeam >= 0
                && match.teamRoundWins(winningTeam) + 1 >= match.roundsToWin();
    }

    private void createAndSendReport(Player winner, Player loser, List<UUID> viewerIds) {
        if (winner == null || loser == null) return;
        String reportId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<UUID, InventorySnapshot> snapshots = new HashMap<>();
        snapshots.put(winner.getUniqueId(), InventorySnapshot.capture(winner));
        snapshots.put(loser.getUniqueId(), InventorySnapshot.capture(loser));
        Set<UUID> viewers = new LinkedHashSet<>(viewerIds);
        MatchReport report = new MatchReport(reportId, winner.getUniqueId(), loser.getUniqueId(), snapshots, viewers,
                System.currentTimeMillis() + (REPORT_VIEW_LIFETIME_TICKS / 20L) * 1000L);
        reports.put(reportId, report);

        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) sendMatchInfo(viewer, report, winner, loser);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> reports.remove(reportId), REPORT_RETENTION_TICKS);
    }

    private void sendMatchInfo(Player viewer, MatchReport report, Player winner, Player loser) {
        if (!plugin.getConfig().getBoolean("match-info.enabled", true)) return;
        List<String> lines = plugin.getConfig().getStringList("match-info.lines");
        if (lines.isEmpty()) {
            lines = List.of(
                    "&6Match Info:",
                    "&fWinner: %winner%",
                    "&fLoser: %loser%",
                    "&8————————"
            );
        }
        for (String line : lines) {
            viewer.sendMessage(renderMatchInfoLine(line == null ? "" : line, report, winner, loser));
        }
    }

    /** Renders configured message lines while keeping %winner% and %loser% clickable. */
    private Component renderMatchInfoLine(String template, MatchReport report, Player winner, Player loser) {
        Component result = Component.empty();
        int cursor = 0;
        while (cursor < template.length()) {
            int winnerAt = indexOfIgnoreCase(template, "%winner%", cursor);
            int loserAt = indexOfIgnoreCase(template, "%loser%", cursor);
            int nextAt;
            boolean winnerToken;
            if (winnerAt < 0 && loserAt < 0) break;
            if (winnerAt >= 0 && (loserAt < 0 || winnerAt <= loserAt)) {
                nextAt = winnerAt;
                winnerToken = true;
            } else {
                nextAt = loserAt;
                winnerToken = false;
            }
            if (nextAt > cursor) result = result.append(com.vprac.util.Texts.component(template.substring(cursor, nextAt)));
            if (winnerToken) {
                result = result.append(clickableName(report, winner));
                cursor = nextAt + "%winner%".length();
            } else {
                result = result.append(clickableName(report, loser));
                cursor = nextAt + "%loser%".length();
            }
        }
        if (cursor < template.length()) result = result.append(com.vprac.util.Texts.component(template.substring(cursor)));
        return result;
    }

    private int indexOfIgnoreCase(String value, String token, int fromIndex) {
        return value.toLowerCase(Locale.ROOT).indexOf(token, Math.max(0, fromIndex));
    }

    private Component clickableName(MatchReport report, Player target) {
        String command = "/vmatchview " + report.id + " " + target.getUniqueId();
        return Component.text(target.getName(), target.getUniqueId().equals(report.winnerId)
                        ? NamedTextColor.GREEN : NamedTextColor.RED)
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Click to view " + target.getName() + "'s match inventory, health and hunger.", NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.runCommand(command));
    }

    /** Handles the safe, tokenized command invoked by a clickable Match Info name. */
    public boolean handleViewCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2) {
            viewer.sendMessage(Component.text("Click a Winner/Loser name in Match Info to view its snapshot.", NamedTextColor.RED));
            return true;
        }
        MatchReport report = reports.get(args[0]);
        if (report == null) {
            viewer.sendMessage(Component.text("That match report has expired.", NamedTextColor.RED));
            return true;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(args[1]);
        } catch (IllegalArgumentException exception) {
            viewer.sendMessage(Component.text("Invalid match report target.", NamedTextColor.RED));
            return true;
        }
        InventorySnapshot snapshot = report.snapshots.get(targetId);
        if (snapshot == null) {
            viewer.sendMessage(Component.text("That player is not part of this match report.", NamedTextColor.RED));
            return true;
        }
        if (report.expiresAtMillis < System.currentTimeMillis()) {
            Texts.send(viewer, "&cunable to show " + snapshot.playerName + " inventory");
            return true;
        }
        if (!report.viewers.contains(viewer.getUniqueId())) {
            viewer.sendMessage(Component.text("Only players from this match can view its inventory report.", NamedTextColor.RED));
            return true;
        }
        openInventorySnapshot(viewer, report.id, snapshot);
        return true;
    }

    private void openInventorySnapshot(Player viewer, String reportId, InventorySnapshot snapshot) {
        MatchInventoryViewHolder holder = new MatchInventoryViewHolder(reportId, snapshot.playerId);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Match Inventory: " + snapshot.playerName, NamedTextColor.DARK_GRAY));
        holder.bind(inventory);

        // Equipment across the top row, in familiar helmet/chestplate/leggings/boots order.
        setIfPresent(inventory, 0, element(snapshot.armor[3]));
        setIfPresent(inventory, 1, element(snapshot.armor[2]));
        setIfPresent(inventory, 2, element(snapshot.armor[1]));
        setIfPresent(inventory, 3, element(snapshot.armor[0]));
        inventory.setItem(4, createPlayerHead(snapshot));
        setIfPresent(inventory, 5, element(snapshot.offhand));
        inventory.setItem(6, makeInfo(Material.RED_DYE, "Health: " + format(snapshot.health) + " / " + format(snapshot.maxHealth),
                List.of("Absorption: " + format(snapshot.absorption), "Last recorded health at death/result.")));
        inventory.setItem(7, makeInfo(Material.COOKED_BEEF, "Hunger: " + snapshot.foodLevel + " / 20",
                List.of("Saturation: " + format(snapshot.saturation), "Exhaustion: " + format(snapshot.exhaustion))));
        inventory.setItem(8, makeInfoWithComponents(Material.BOOK, "Player Status", statusLore(snapshot)));

        // Storage indices 9-35 are the 27 regular inventory slots, and 0-8 are the hotbar.
        for (int sourceSlot = 9; sourceSlot < Math.min(36, snapshot.storage.length); sourceSlot++) {
            setIfPresent(inventory, sourceSlot, element(snapshot.storage[sourceSlot]));
        }
        ItemStack spacer = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta spacerMeta = spacer.getItemMeta();
        spacerMeta.displayName(Texts.nonItalic(Component.text(" ")));
        spacer.setItemMeta(spacerMeta);
        for (int slot = 36; slot <= 44; slot++) inventory.setItem(slot, spacer.clone());
        for (int sourceSlot = 0; sourceSlot < Math.min(9, snapshot.storage.length); sourceSlot++) {
            setIfPresent(inventory, 45 + sourceSlot, element(snapshot.storage[sourceSlot]));
        }
        viewer.openInventory(inventory);
    }

    private List<Component> statusLore(InventorySnapshot snapshot) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Level: " + snapshot.level + " | XP: " + Math.round(snapshot.exp * 100) + "%", NamedTextColor.GRAY));
        lore.add(Component.text("Ping at snapshot: " + snapshot.ping + "ms", NamedTextColor.GRAY));
        lore.add(Component.text("Active effects:", NamedTextColor.AQUA));
        if (snapshot.effects.isEmpty()) lore.add(Component.text("None", NamedTextColor.GRAY));
        else for (String effect : snapshot.effects) lore.add(Component.text(effect, NamedTextColor.GRAY));
        return lore;
    }

    private ItemStack createPlayerHead(InventorySnapshot snapshot) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(snapshot.playerId);
            meta.setOwningPlayer(offline);
            meta.displayName(Texts.nonItalic(Component.text(snapshot.playerName, NamedTextColor.AQUA)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Match inventory snapshot", NamedTextColor.GRAY));
            lore.add(Component.text("Health: " + format(snapshot.health) + " / " + format(snapshot.maxHealth), NamedTextColor.RED));
            lore.add(Component.text("Hunger: " + snapshot.foodLevel + " / 20", NamedTextColor.GOLD));
            lore.add(Component.text("Saturation: " + format(snapshot.saturation), NamedTextColor.GRAY));
            lore.add(Component.text("Absorption: " + format(snapshot.absorption), NamedTextColor.YELLOW));
            meta.lore(Texts.nonItalicLore(lore));
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack makeInfo(Material material, String name, List<String> loreText) {
        List<Component> lore = new ArrayList<>(loreText.size());
        for (String line : loreText) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        return makeInfoWithComponents(material, name, lore);
    }

    /** Builds an info item from already-styled Adventure components without flattening their formatting. */
    private ItemStack makeInfoWithComponents(Material material, String name, List<? extends Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.nonItalic(Component.text(name, NamedTextColor.WHITE)));
        meta.lore(Texts.nonItalicLore(new ArrayList<>(lore)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack element(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        // Only the GUI clone is modified; the snapshot and the player's actual equipment retain
        // all combat attributes. Paper 1.21.11 needs TooltipDisplay to hide implicit attributes.
        return ItemTooltips.hideAttributes(item.clone());
    }

    private void setIfPresent(Inventory inventory, int slot, ItemStack item) {
        if (item != null) inventory.setItem(slot, item);
    }

    public boolean isViewInventory(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof MatchInventoryViewHolder;
    }

    public void shutdown() { reports.clear(); }

    private static String format(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    private static final class MatchReport {
        private final String id;
        private final UUID winnerId;
        private final UUID loserId;
        private final Map<UUID, InventorySnapshot> snapshots;
        private final Set<UUID> viewers;
        private final long expiresAtMillis;

        private MatchReport(String id, UUID winnerId, UUID loserId, Map<UUID, InventorySnapshot> snapshots,
                            Set<UUID> viewers, long expiresAtMillis) {
            this.id = id;
            this.winnerId = winnerId;
            this.loserId = loserId;
            this.snapshots = snapshots;
            this.viewers = viewers;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class InventorySnapshot {
        private final UUID playerId;
        private final String playerName;
        private final ItemStack[] storage;
        private final ItemStack[] armor;
        private final ItemStack offhand;
        private final double health;
        private final double maxHealth;
        private final double absorption;
        private final int foodLevel;
        private final float saturation;
        private final float exhaustion;
        private final int level;
        private final float exp;
        private final int ping;
        private final List<String> effects;

        private InventorySnapshot(UUID playerId, String playerName, ItemStack[] storage, ItemStack[] armor,
                                  ItemStack offhand, double health, double maxHealth, double absorption,
                                  int foodLevel, float saturation, float exhaustion, int level, float exp,
                                  int ping, List<String> effects) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.storage = storage;
            this.armor = armor;
            this.offhand = offhand;
            this.health = health;
            this.maxHealth = maxHealth;
            this.absorption = absorption;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.level = level;
            this.exp = exp;
            this.ping = ping;
            this.effects = effects;
        }

        private static InventorySnapshot capture(Player player) {
            ItemStack[] storage = cloneItems(player.getInventory().getStorageContents());
            ItemStack[] armor = cloneItems(player.getInventory().getArmorContents());
            ItemStack offhand = player.getInventory().getItemInOffHand();
            AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
            double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
            double health;
            try { health = Math.max(0.0, Math.min(maxHealth, player.getHealth())); }
            catch (IllegalArgumentException exception) { health = 0.0; }
            List<String> effects = new ArrayList<>();
            for (PotionEffect effect : player.getActivePotionEffects()) {
                String name = effect.getType().getKey().getKey().toLowerCase(Locale.ROOT).replace('_', ' ');
                effects.add(name + " " + (effect.getAmplifier() + 1) + " · " + Math.max(0, effect.getDuration() / 20) + "s");
            }
            return new InventorySnapshot(player.getUniqueId(), player.getName(), storage, armor,
                    offhand == null || offhand.getType().isAir() ? null : offhand.clone(), health, maxHealth,
                    player.getAbsorptionAmount(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                    player.getLevel(), player.getExp(), Math.max(0, player.getPing()), effects);
        }

        private static ItemStack[] cloneItems(ItemStack[] source) {
            ItemStack[] clone = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) clone[i] = source[i] == null || source[i].getType().isAir() ? null : source[i].clone();
            return clone;
        }
    }
}
