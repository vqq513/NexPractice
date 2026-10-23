package com.vprac.tournament;

import com.vprac.VpracPlugin;
import com.vprac.queue.PartyMatch;
import com.vprac.util.Texts;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Single-elimination tournaments integrated with NexPractice's normal match and party-split lifecycles. */
public final class TournamentManager implements Listener {
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");
    private static final long REGISTRATION_SECONDS = 60L;
    private static final long INVITE_COOLDOWN_MILLIS = 30_000L;
    private static final long INVITE_LIFETIME_MILLIS = 60_000L;
    private static final String UNABLE_STATE_MESSAGE = "&6unable to execute this in ur current state";

    public enum Format { ONE_V_ONE, TWO_V_TWO }
    private enum Status { REGISTRATION, RUNNING, COMPLETE, CANCELLED }

    private final VpracPlugin plugin;
    private final NamespacedKey leaveItemKey;
    private final NamespacedKey teammateItemKey;
    private final NamespacedKey inviteTargetKey;
    private Tournament active;
    private final Map<UUID, Invite> invitesByTarget = new HashMap<>();
    /** Per-target cooldown: one pending/invited player does not block invitations to others. */
    private final Map<String, Long> inviteCooldownUntil = new HashMap<>();
    private final Map<UUID, UUID> outgoingInviteTarget = new HashMap<>();
    private final File cooldownFile;
    private final YamlConfiguration cooldownData;

    public TournamentManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.cooldownFile = new File(plugin.getDataFolder(), "tournament-data.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!cooldownFile.exists()) {
            try { cooldownFile.createNewFile(); }
            catch (IOException exception) { plugin.getLogger().warning("Could not create tournament-data.yml: " + exception.getMessage()); }
        }
        this.cooldownData = YamlConfiguration.loadConfiguration(cooldownFile);
        this.leaveItemKey = new NamespacedKey("vprac", "tournament_leave");
        this.teammateItemKey = new NamespacedKey("vprac", "tournament_teammate");
        this.inviteTargetKey = new NamespacedKey("vprac", "tournament_invite_target");
    }

    private String configText(String path, String fallback, String... replacements) {
        String text = plugin.getConfig().getString("tournaments." + path, fallback);
        if (text == null) text = fallback;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace("{" + replacements[i] + "}", replacements[i + 1] == null ? "" : replacements[i + 1]);
        }
        return text;
    }

    private void sendConfigured(CommandSender recipient, String key, String fallback, String... replacements) {
        Texts.send(recipient, configText("messages." + key, fallback, replacements));
    }

    private void broadcastConfigured(String key, String fallback, String... replacements) {
        Bukkit.broadcast(Texts.component(configText("broadcasts." + key, fallback, replacements)));
    }

    private long hostCooldownUntil(UUID uuid) {
        return cooldownData.getLong("host-cooldowns." + uuid, 0L);
    }

    private void setHostCooldown(UUID uuid, long until) {
        cooldownData.set("host-cooldowns." + uuid, until);
        try { cooldownData.save(cooldownFile); }
        catch (IOException exception) { plugin.getLogger().warning("Could not save tournament host cooldowns: " + exception.getMessage()); }
    }

    private boolean checkCanHost(Player player) {
        String permission = plugin.getConfig().getString("tournaments.host-permission", "vprac.tournament.host");
        if (permission == null || permission.isBlank()) permission = "vprac.tournament.host";
        if (!player.hasPermission(permission)) {
            sendConfigured(player, "no-host-permission", "&cYou need the required rank to host a tournament.");
            return false;
        }
        long seconds = Math.max(0L, plugin.getConfig().getLong("tournaments.host-cooldown-seconds", 1800L));
        long remaining = hostCooldownUntil(player.getUniqueId()) - System.currentTimeMillis();
        if (seconds > 0 && remaining > 0L) {
            long totalSeconds = (remaining + 999L) / 1000L;
            sendConfigured(player, "host-cooldown", "&cYou can host another tournament in {minutes}m {seconds}s.",
                    "minutes", String.valueOf(totalSeconds / 60L), "seconds", String.valueOf(totalSeconds % 60L));
            return false;
        }
        return true;
    }

    public boolean isRegistered(UUID uuid) {
        return uuid != null && active != null && active.status != Status.COMPLETE
                && active.status != Status.CANCELLED && active.registered.contains(uuid);
    }

    public boolean isTournamentItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(leaveItemKey, PersistentDataType.BYTE)
                || pdc.has(teammateItemKey, PersistentDataType.BYTE);
    }

    /** Removes only this subsystem's hotbar controls; normal NexPractice lobby items are handled elsewhere. */
    public void clearTournamentItems(Player player) {
        if (player == null) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isTournamentItem(item)) player.getInventory().setItem(slot, null);
        }
    }

    /** Replaces the regular lobby hotbar with tournament controls while the player is registered. */
    public void giveParticipantItems(Player player) {
        if (player == null || !player.isOnline() || !isRegistered(player.getUniqueId())) return;
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        if (active.format == Format.TWO_V_TWO) player.getInventory().setItem(0, createTeammateItem(player.getUniqueId()));
        player.getInventory().setItem(4, createLeaveItem());
        player.updateInventory();
    }

    public void openHostDialog(Player player) {
        if (player == null || !player.isOnline()) return;
        if (plugin.getQueueManager().isInMatch(player.getUniqueId())) {
            sendConfigured(player, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        if (!checkCanHost(player)) return;
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Texts.component(configText("ui.choose-kit", "&7Choose the kit for your tournament. Click a kit name.")), 360));
        for (String kitId : plugin.getKitManager().getKitIds()) {
            String displayName = plugin.getKitManager().getDisplayName(kitId);
            Component clickable = Component.text(displayName, NamedTextColor.AQUA)
                    .clickEvent(custom("tournament/kit/" + kitId))
                    .hoverEvent(HoverEvent.showText(Texts.component(configText("ui.choose-kit-hover", "&7Choose {kit}", "kit", displayName))));
            body.add(DialogBody.item(new ItemStack(plugin.getKitManager().getIconMaterial(kitId)),
                    DialogBody.plainMessage(clickable, 330), true, true, 24, 24));
        }
        showDialog(player, configText("ui.host-title", "Host a Tournament"), body);
    }

    private void openFormatDialog(Player player, String kitId) {
        if (player == null || !player.isOnline() || !plugin.getKitManager().exists(kitId)) return;
        String kitName = plugin.getKitManager().getDisplayName(kitId);
        Material icon = plugin.getKitManager().getIconMaterial(kitId);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Texts.component(configText("ui.choose-format", "&7Select a single-elimination format for {kit}.", "kit", kitName)), 360));
        Component one = Texts.component(configText("ui.host-1v1", "&6Host a 1v1 {kit} Tournament", "kit", kitName))
                .clickEvent(custom("tournament/host/1v1/" + kitId))
                .hoverEvent(HoverEvent.showText(Texts.component(configText("ui.host-1v1-hover", "&7Players compete individually"))));
        Component two = Texts.component(configText("ui.host-2v2", "&6Host a 2v2 {kit} Tournament", "kit", kitName))
                .clickEvent(custom("tournament/host/2v2/" + kitId))
                .hoverEvent(HoverEvent.showText(Texts.component(configText("ui.host-2v2-hover", "&7Players compete in teams of two"))));
        body.add(DialogBody.item(new ItemStack(icon), DialogBody.plainMessage(one, 350), true, true, 24, 24));
        body.add(DialogBody.item(new ItemStack(icon), DialogBody.plainMessage(two, 350), true, true, 24, 24));
        showDialog(player, configText("ui.host-kit-title", "Host {kit} Tournament", "kit", kitName), body);
    }

    public boolean handleCustomClick(PlayerCustomClickEvent event, Player player) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals("vprac") || !key.value().startsWith("tournament/")) return false;
        String[] parts = key.value().split("/", -1);
        if (parts.length >= 3 && parts[1].equals("kit")) {
            String kitId = parts[2].toLowerCase(Locale.ROOT);
            if (plugin.getKitManager().exists(kitId)) openFormatDialog(player, kitId);
            else sendConfigured(player, "kit-missing", "&cThat kit no longer exists.");
            return true;
        }
        if (parts.length == 4 && parts[1].equals("host")) {
            String kitId = parts[3].toLowerCase(Locale.ROOT);
            if (!plugin.getKitManager().exists(kitId)) {
                sendConfigured(player, "kit-missing", "&cThat kit no longer exists.");
                return true;
            }
            Format format = parts[2].equalsIgnoreCase("1v1") ? Format.ONE_V_ONE
                    : parts[2].equalsIgnoreCase("2v2") ? Format.TWO_V_TWO : null;
            if (format != null) createTournament(player, format, kitId);
            return true;
        }
        return true;
    }

    private void createTournament(Player host, Format format, String kitId) {
        if (active != null && active.status != Status.COMPLETE && active.status != Status.CANCELLED) {
            sendConfigured(host, "active-exists", "&cA tournament is already active. Wait until it finishes before hosting another.");
            return;
        }
        if (plugin.getQueueManager().isInMatch(host.getUniqueId())) {
            sendConfigured(host, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        if (!checkCanHost(host)) return;
        long cooldownSeconds = Math.max(0L, plugin.getConfig().getLong("tournaments.host-cooldown-seconds", 1800L));
        if (cooldownSeconds > 0L) setHostCooldown(host.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        Tournament tournament = new Tournament(UUID.randomUUID(), format, kitId);
        active = tournament;
        broadcastJoinMessage(tournament, tournament.secondsRemaining());
        // Announce again after ten seconds, then every ten seconds until five seconds remain.
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (active != tournament || tournament.status != Status.REGISTRATION) {
                task.cancel();
                return;
            }
            long remaining = tournament.secondsRemaining();
            if (remaining <= 0L) {
                task.cancel();
                beginTournament(tournament);
                return;
            }
            if (remaining <= 5L || (remaining < REGISTRATION_SECONDS && remaining % 10L == 0L)) {
                broadcastJoinMessage(tournament, remaining);
            }
        }, 20L, 20L);
    }

    private void broadcastJoinMessage(Tournament tournament, long leftSeconds) {
        String formatName = tournament.format == Format.ONE_V_ONE ? "1v1" : "2v2";
        Component join = Texts.component(configText("broadcasts.join-click", "&a(Click here to join!)"))
                .clickEvent(ClickEvent.runCommand("/vtournament join " + tournament.id))
                .hoverEvent(HoverEvent.showText(Texts.component(configText("ui.join-hover", "&eJoin the {format} tournament", "format", formatName))));
        Bukkit.broadcast(Texts.component(configText("broadcasts.join-separator", "&7━━━━━━━━━━━━━━━━━━━━")));
        Bukkit.broadcast(Texts.component(configText("broadcasts.join-title", "&6{format} Tournament", "format", formatName)));
        Bukkit.broadcast(Texts.component(configText("broadcasts.join-kit", "&6Kit: &d{kit}", "kit", plugin.getKitManager().getDisplayName(tournament.kitId))));
        Bukkit.broadcast(Texts.component(configText("broadcasts.join-starting", "&eStarting in &6{left_seconds} &eseconds!", "left_seconds", String.valueOf(Math.max(0L, leftSeconds)))));
        Bukkit.broadcast(join);
        Bukkit.broadcast(Texts.component(configText("broadcasts.join-separator", "&7━━━━━━━━━━━━━━━━━━━━")));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void handleInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (!isTournamentItem(held)) return;
        event.setCancelled(true);
        handleTournamentItemUse(event.getPlayer(), held);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void handleInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        if (!isTournamentItem(held)) return;
        event.setCancelled(true);
        handleTournamentItemUse(event.getPlayer(), held);
    }

    private void handleTournamentItemUse(Player player, ItemStack held) {
        if (held.getItemMeta().getPersistentDataContainer().has(leaveItemKey, PersistentDataType.BYTE)) {
            leaveTournament(player, true);
        } else if (held.getItemMeta().getPersistentDataContainer().has(teammateItemKey, PersistentDataType.BYTE)) {
            openTeammateControl(player);
        }
    }

    private void openTeammateControl(Player player) {
        if (!isRegistered(player.getUniqueId()) || active.format != Format.TWO_V_TWO) {
            sendConfigured(player, "teammate-not-2v2", "&cTeammate selection is only available in a 2v2 tournament.");
            return;
        }
        if (active.status != Status.REGISTRATION) {
            sendConfigured(player, "teammates-locked", "&cTeammates are locked after tournament registration closes.");
            return;
        }
        UUID teammate = active.teammates.get(player.getUniqueId());
        if (teammate == null) openTeammateSelector(player);
        else openLeaveTeammateMenu(player, teammate);
    }

    private void openTeammateSelector(Player player) {
        openTeammateSelector(player, 0);
    }

    private void openTeammateSelector(Player player, int requestedPage) {
        if (active == null || active.status != Status.REGISTRATION || !isRegistered(player.getUniqueId())) return;
        List<Player> candidates = new ArrayList<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            UUID targetId = target.getUniqueId();
            if (targetId.equals(player.getUniqueId()) || plugin.getQueueManager().isInMatch(targetId)
                    || plugin.getMatchSpectatorManager().isSpectating(targetId)
                    || active.teammates.containsKey(targetId)) continue;
            candidates.add(target);
        }
        candidates.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pageSize = 14;
        int pageCount = Math.max(1, (candidates.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        TeammateMenuHolder holder = new TeammateMenuHolder(player.getUniqueId(), false, page);
        Inventory inventory = Bukkit.createInventory(holder, 36, Texts.component(configText("ui.teammate-selector-title", "&8Select a Teammate &7({page}/{pages})", "page", String.valueOf(page + 1), "pages", String.valueOf(pageCount))));
        holder.setInventory(inventory);
        ItemStack glass = menuItem(Material.GRAY_STAINED_GLASS_PANE, configText("ui.teammate-selector-glass-name", "&7 "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot < 9 || slot >= 27 || slot % 9 == 0 || slot % 9 == 8) inventory.setItem(slot, glass.clone());
        }
        int[] contentSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int startIndex = page * pageSize;
        int endIndex = Math.min(candidates.size(), startIndex + pageSize);
        int index = 0;
        for (int candidateIndex = startIndex; candidateIndex < endIndex; candidateIndex++) {
            Player target = candidates.get(candidateIndex);
            UUID targetId = target.getUniqueId();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta raw = head.getItemMeta();
            if (raw instanceof SkullMeta skull) {
                skull.setOwningPlayer(target);
                boolean inviteSent = targetId.equals(outgoingInviteTarget.get(player.getUniqueId()))
                        && invitesByTarget.containsKey(targetId);
                skull.displayName(Texts.component(configText("ui.teammate-selector-player-name", "&f{player}", "player", target.getName())));
                skull.lore(Texts.nonItalicLore(List.of(Texts.component(inviteSent
                        ? configText("ui.invite-sent-lore", "&aInvite Sent!") : configText("ui.invite-player-lore", "&aClick to invite {player}", "player", target.getName())))));
                skull.getPersistentDataContainer().set(inviteTargetKey, PersistentDataType.STRING, targetId.toString());
                head.setItemMeta(skull);
            }
            inventory.setItem(contentSlots[index], head);
            holder.targetAt.put(contentSlots[index], targetId);
            index++;
        }
        if (candidates.isEmpty()) inventory.setItem(13, menuItem(Material.BARRIER, configText("ui.no-players-name", "&cNo available players"), List.of(configText("ui.no-players-lore", "&7Wait for other players to join."))));
        if (page > 0) inventory.setItem(30, menuItem(Material.ARROW, configText("ui.previous-page-name", "&ePrevious Page"), List.of(configText("ui.previous-page-lore", "&7Go to page {page}.", "page", String.valueOf(page)))));
        if (page + 1 < pageCount) inventory.setItem(32, menuItem(Material.ARROW, configText("ui.next-page-name", "&eNext Page"), List.of(configText("ui.next-page-lore", "&7Go to page {page}.", "page", String.valueOf(page + 2)))));
        player.openInventory(inventory);
    }

    private void openLeaveTeammateMenu(Player player, UUID teammateId) {
        TeammateMenuHolder holder = new TeammateMenuHolder(player.getUniqueId(), true);
        Inventory inventory = Bukkit.createInventory(holder, 9, Texts.component(configText("ui.teammate-settings-title", "&8Teammate Settings")));
        holder.setInventory(inventory);
        inventory.setItem(4, menuItem(Material.BARRIER, configText("ui.leave-teammate-name", "&4Leave teammate!"), List.of(configText("ui.leave-teammate-lore", "&7Stop being teammates with {player}.", "player", playerName(teammateId)))));
        holder.partnerId = teammateId;
        player.openInventory(inventory);
    }

    private void sendTeammateInvite(Player inviter, UUID targetId) {
        if (active == null || active.status != Status.REGISTRATION || active.format != Format.TWO_V_TWO
                || !isRegistered(inviter.getUniqueId())) return;
        long now = System.currentTimeMillis();
        String cooldownKey = teammateCooldownKey(inviter.getUniqueId(), targetId);
        inviteCooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        long cooldown = inviteCooldownUntil.getOrDefault(cooldownKey, 0L);
        if (now < cooldown) {
            sendConfigured(inviter, "invite-cooldown", "&cYou can invite this player again in {seconds} seconds.", "seconds", String.valueOf(Math.max(1L, (cooldown - now + 999L) / 1000L)));
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline() || targetId.equals(inviter.getUniqueId())) {
            sendConfigured(inviter, "invite-unavailable", "&cThat player is no longer available.");
            return;
        }
        if (plugin.getQueueManager().isInMatch(targetId) || plugin.getMatchSpectatorManager().isSpectating(targetId)) {
            sendConfigured(inviter, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        if (active.teammates.containsKey(inviter.getUniqueId()) || active.teammates.containsKey(targetId)) {
            sendConfigured(inviter, "already-has-teammate", "&cYou or that player already has a teammate.");
            return;
        }
        if (!isRegistered(targetId) && plugin.getQueueManager().isInMatch(targetId)) {
            sendConfigured(inviter, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        Invite old = invitesByTarget.get(targetId);
        if (old != null && old.expiresAt > now) {
            sendConfigured(inviter, "pending-invite", "&cThat player already has a pending teammate invitation.");
            return;
        }
        Invite invite = new Invite(UUID.randomUUID(), inviter.getUniqueId(), targetId, now + INVITE_LIFETIME_MILLIS);
        invitesByTarget.put(targetId, invite);
        outgoingInviteTarget.put(inviter.getUniqueId(), targetId);
        inviteCooldownUntil.put(cooldownKey, now + INVITE_COOLDOWN_MILLIS);
        Component accept = Texts.component(configText("ui.invite-accept-text", "&a[ACCEPT]"))
                .clickEvent(ClickEvent.runCommand("/vtournament accept " + invite.token))
                .hoverEvent(HoverEvent.showText(Texts.component(configText("ui.invite-accept-hover", "&aAccept teammate invitation"))));
        Component deny = Texts.component(configText("ui.invite-deny-text", "&c[DENY]"))
                .clickEvent(ClickEvent.runCommand("/vtournament deny " + invite.token))
                .hoverEvent(HoverEvent.showText(Texts.component(configText("ui.invite-deny-hover", "&cDecline teammate invitation"))));
        target.sendMessage(Texts.component(configText("messages.teammate-invite", "&a{inviter} &7Want you to become his teammate! ", "inviter", inviter.getName()))
                .append(accept).append(Component.space()).append(deny));
        sendConfigured(inviter, "invite-sent", "&aTeammate invitation sent to {target}.", "target", target.getName());
    }

    private void acceptTeammateInvite(Player target, UUID token) {
        Invite invite = invitesByTarget.get(target.getUniqueId());
        if (invite == null || !invite.token.equals(token) || invite.expiresAt < System.currentTimeMillis()) {
            sendConfigured(target, "invite-expired", "&cThat teammate invitation has expired.");
            return;
        }
        Player inviter = Bukkit.getPlayer(invite.inviter);
        if (active == null || active.status != Status.REGISTRATION || active.format != Format.TWO_V_TWO
                || inviter == null || !inviter.isOnline() || !isRegistered(invite.inviter)) {
            invitesByTarget.remove(target.getUniqueId());
            sendConfigured(target, "invite-unavailable-now", "&cThat tournament or invitation is no longer available.");
            return;
        }
        if (plugin.getQueueManager().isInMatch(target.getUniqueId()) || plugin.getMatchSpectatorManager().isSpectating(target.getUniqueId())) {
            sendConfigured(target, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        if (active.teammates.containsKey(invite.inviter) || active.teammates.containsKey(target.getUniqueId())) {
            sendConfigured(target, "already-has-teammate", "&cYou or the inviter already has a teammate.");
            return;
        }
        if (!isRegistered(target.getUniqueId())) {
            addParticipant(target, active);
        }
        invitesByTarget.remove(target.getUniqueId());
        outgoingInviteTarget.remove(invite.inviter, target.getUniqueId());
        // Neither newly paired player should retain stale incoming/outgoing invitations.
        clearInviteFor(invite.inviter);
        clearInviteFor(target.getUniqueId());
        active.teammates.put(invite.inviter, target.getUniqueId());
        active.teammates.put(target.getUniqueId(), invite.inviter);
        giveParticipantItems(inviter);
        giveParticipantItems(target);
        sendConfigured(inviter, "teammate-confirm-inviter", "&a{target} is now your teammate.", "target", target.getName());
        sendConfigured(target, "teammate-confirm-target", "&aYou are now teammates with {inviter}.", "inviter", inviter.getName());
    }

    private void denyTeammateInvite(Player target, UUID token) {
        Invite invite = invitesByTarget.get(target.getUniqueId());
        if (invite == null || !invite.token.equals(token)) {
            sendConfigured(target, "invite-no-longer-available", "&cThat teammate invitation is no longer available.");
            return;
        }
        invitesByTarget.remove(target.getUniqueId());
        outgoingInviteTarget.remove(invite.inviter, target.getUniqueId());
        Player inviter = Bukkit.getPlayer(invite.inviter);
        if (inviter != null && inviter.isOnline()) sendConfigured(inviter, "invite-declined-inviter", "&c{target} declined your teammate invitation.", "target", target.getName());
        sendConfigured(target, "invite-declined-target", "&eYou declined the teammate invitation.");
    }

    private void leaveTeammate(Player player, UUID partnerId) {
        if (active == null || active.status != Status.REGISTRATION) {
            sendConfigured(player, "teammates-locked", "&cTeammates are locked after tournament registration closes.");
            player.closeInventory();
            return;
        }
        UUID uuid = player.getUniqueId();
        UUID actualPartner = active.teammates.get(uuid);
        if (actualPartner == null || !actualPartner.equals(partnerId)) {
            player.closeInventory();
            sendConfigured(player, "no-longer-teammates", "&cYou no longer have that teammate.");
            return;
        }
        active.teammates.remove(uuid);
        active.teammates.remove(partnerId);
        player.closeInventory();
        sendConfigured(player, "left-teammate", "&eYou left your teammate.");
        Player partner = Bukkit.getPlayer(partnerId);
        if (partner != null && partner.isOnline()) {
            sendConfigured(partner, "teammate-left", "&e{player} left your team.", "player", player.getName());
            giveParticipantItems(partner);
        }
        giveParticipantItems(player);
    }

    private void addParticipant(Player player, Tournament tournament) {
        UUID uuid = player.getUniqueId();
        if (plugin.getQueueManager().isInMatch(uuid) || plugin.getMatchSpectatorManager().isSpectating(uuid)) {
            sendConfigured(player, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        tournament.registered.add(uuid);
        plugin.getQueueManager().removeFromAllQueues(uuid);
        giveParticipantItems(player);
        broadcastToParticipants(tournament, configText("messages.joined", "&9{player} &6Joined the tournament!", "player", player.getName()));
    }

    public void joinTournament(Player player, UUID tournamentId) {
        Tournament tournament = active;
        if (player == null || !player.isOnline() || tournament == null
                || tournament.status != Status.REGISTRATION
                || tournamentId != null && !tournament.id.equals(tournamentId)) {
            if (player != null) sendConfigured(player, "not-accepting", "&cThat tournament is no longer accepting players.");
            return;
        }
        if (plugin.getQueueManager().isInMatch(player.getUniqueId()) || plugin.getMatchSpectatorManager().isSpectating(player.getUniqueId())) {
            sendConfigured(player, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        if (isRegistered(player.getUniqueId())) {
            sendConfigured(player, "already-joined", "&cYou have already joined this tournament.");
            return;
        }
        addParticipant(player, tournament);
    }

    private void beginTournament(Tournament tournament) {
        if (active != tournament || tournament.status != Status.REGISTRATION) return;
        tournament.status = Status.RUNNING;
        if (!plugin.getKitManager().exists(tournament.kitId)) {
            cancelTournament(tournament, configText("messages.cancel-kit-removed", "The selected kit no longer exists."));
            return;
        }
        List<TournamentTeam> teams = buildTeams(tournament);
        if (tournament.format == Format.TWO_V_TWO) {
            TournamentTeam oddEntrant = teams.stream().filter(team -> team.members.size() == 1).findFirst().orElse(null);
            if (oddEntrant != null) {
                teams.remove(oddEntrant);
                tournament.oddEntrantTeam = oddEntrant;
                // With only three entrants, two of the entrants must first play a genuine 1v1;
                // the remaining individual waits and then gets a 1v1 against that winner.
                if (teams.size() == 1 && teams.get(0).members.size() == 2) {
                    TournamentTeam duo = teams.remove(0);
                    UUID first = duo.members.get(0);
                    UUID second = duo.members.get(1);
                    tournament.teammates.remove(first);
                    tournament.teammates.remove(second);
                    teams.add(new TournamentTeam(List.of(first)));
                    teams.add(new TournamentTeam(List.of(second)));
                    for (UUID uuid : List.of(first, second)) {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null && online.isOnline()) giveParticipantItems(online);
                    }
                }
            }
        }
        if (teams.size() < 2) {
            cancelTournament(tournament, configText(tournament.format == Format.ONE_V_ONE ? "messages.not-enough-players-1v1" : "messages.not-enough-players-2v2",
                    tournament.format == Format.ONE_V_ONE ? "&cNot enough players joined; at least two are required." : "&cNot enough complete teams joined; at least two teams are required."));
            return;
        }
        tournament.roundNumber = 0;
        startRound(tournament, teams);
    }

    private List<TournamentTeam> buildTeams(Tournament tournament) {
        List<TournamentTeam> teams = new ArrayList<>();
        List<UUID> players = new ArrayList<>(tournament.registered);
        Collections.shuffle(players);
        if (tournament.format == Format.ONE_V_ONE) {
            for (UUID uuid : players) teams.add(new TournamentTeam(List.of(uuid)));
            return teams;
        }

        Set<UUID> used = new HashSet<>();
        for (UUID uuid : new ArrayList<>(players)) {
            if (!used.add(uuid)) continue;
            UUID mate = tournament.teammates.get(uuid);
            if (mate != null && tournament.registered.contains(mate) && !used.contains(mate)) {
                used.add(mate);
                teams.add(new TournamentTeam(List.of(uuid, mate)));
            } else {
                tournament.teammates.remove(uuid);
            }
        }
        // Rebuild only the unmatched set; paired players are removed by membership in teams.
        Set<UUID> paired = new HashSet<>();
        for (TournamentTeam team : teams) paired.addAll(team.members);
        List<UUID> unmatched = new ArrayList<>();
        for (UUID uuid : tournament.registered) if (!paired.contains(uuid)) unmatched.add(uuid);
        Collections.shuffle(unmatched);
        for (int i = 0; i + 1 < unmatched.size(); i += 2) {
            UUID first = unmatched.get(i), second = unmatched.get(i + 1);
            tournament.teammates.put(first, second);
            tournament.teammates.put(second, first);
            teams.add(new TournamentTeam(List.of(first, second)));
        }
        if (unmatched.size() % 2 == 1) {
            UUID waiting = unmatched.get(unmatched.size() - 1);
            tournament.teammates.remove(waiting);
            // Preserve the odd entrant as a singleton bracket team. They play a 1v1 play-in
            // against a representative of the next paired team when that pairing is scheduled;
            // the resulting winner advances as that entire bracket team.
            teams.add(new TournamentTeam(List.of(waiting)));
            Player online = Bukkit.getPlayer(waiting);
            if (online != null && online.isOnline()) {
                Texts.send(online, "&eYou are the odd 2v2 tournament entrant. You'll wait for your 1v1 play-in.");
                giveParticipantItems(online);
            }
        }
        // Refresh the auto-assigned teammate head for new fixed teams.
        for (TournamentTeam team : teams) for (UUID uuid : team.members) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) giveParticipantItems(online);
        }
        return teams;
    }

    private void startRound(Tournament tournament, List<TournamentTeam> entrants) {
        if (active != tournament || tournament.status != Status.RUNNING) return;
        List<TournamentTeam> available = new ArrayList<>();
        for (TournamentTeam team : entrants) {
            if (team.members.stream().allMatch(tournament.registered::contains)) available.add(team);
        }
        if (available.isEmpty()) {
            cancelTournament(tournament, configText("messages.cancel-no-eligible-players", "No eligible players remain in the bracket."));
            return;
        }
        if (available.size() == 1) {
            if (tournament.oddEntrantTeam != null) startOddEntrantPlayIn(tournament, available.get(0));
            else completeTournament(tournament, available.get(0));
            return;
        }

        tournament.roundNumber++;
        tournament.roundWinners.clear();
        tournament.pendingMatches.clear();
        tournament.inProgress.clear();
        tournament.waitingTeam = null;
        Collections.shuffle(available);
        int entrantCount = available.size();
        // Do not award a bye. If the round is odd, hold one entrant back and pair them with
        // the winner of a match as soon as one fight finishes.
        if ((available.size() & 1) == 1) tournament.waitingTeam = available.remove(available.size() - 1);
        for (int index = 0; index + 1 < available.size(); index += 2) {
            tournament.pendingMatches.addLast(new Pairing(available.get(index), available.get(index + 1)));
        }

        broadcastRoundStart(tournament, entrantCount);
        startPendingMatches(tournament);
    }

    private void broadcastRoundStart(Tournament tournament, int entrantCount) {
        String kitName = plugin.getKitManager().getDisplayName(tournament.kitId);
        if (entrantCount == 8) {
            broadcastConfigured("quarter-finals", "&9{kit} Tournament quarter finals has started!", "kit", kitName);
        } else if (entrantCount == 4) {
            broadcastConfigured("semi-finals", "&9{kit} Tournament Semi-Finals has started!", "kit", kitName);
        } else if (entrantCount == 2 && tournament.oddEntrantTeam == null) {
            broadcastConfigured("finals", "&9{kit} Finals has started!", "kit", kitName);
            if (!tournament.pendingMatches.isEmpty()) {
                Pairing finalPair = tournament.pendingMatches.peekFirst();
                Bukkit.broadcast(finalAnnouncement(finalPair));
            }
        }
    }

    private Component finalAnnouncement(Pairing pairing) {
        // During the odd-player 2v2 play-in, the actual match is 1v1 even if the finalist
        // represents a remaining duo in the team bracket. Announce the two actual fighters.
        boolean oddPlayIn = active != null && active.oddPlayInPairing == pairing;
        String left = oddPlayIn ? playerName(pairing.first.members.get(0)) : teamNames(pairing.first);
        String right = oddPlayIn ? playerName(pairing.second.members.get(0)) : teamNames(pairing.second);
        UUID specTarget = pairing.first.members.get(0);
        Player target = Bukkit.getPlayer(specTarget);
        String targetName = target == null ? playerName(specTarget) : target.getName();
        Component line = Texts.component(configText("broadcasts.final-matchup", "&7{team_a} &8vs &7{team_b} &a(Click to spectate!)",
                        "team_a", left, "team_b", right))
                .clickEvent(ClickEvent.runCommand("/spec " + targetName))
                .hoverEvent(HoverEvent.showText(Texts.component(configText("broadcasts.final-hover", "&aSpectate the final"))));
        return line;
    }

    private String teamNames(TournamentTeam team) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : team.members) names.add(playerName(uuid));
        return String.join(", ", names);
    }

    private void startPendingMatches(Tournament tournament) {
        if (active != tournament || tournament.status != Status.RUNNING) return;
        boolean blockedOnArena = false;
        int initial = tournament.pendingMatches.size();
        for (int i = 0; i < initial; i++) {
            Pairing pairing = tournament.pendingMatches.pollFirst();
            if (pairing == null) break;
            boolean firstAvailable = teamAvailable(tournament, pairing.first);
            boolean secondAvailable = teamAvailable(tournament, pairing.second);
            if (!firstAvailable || !secondAvailable) {
                if (pairing == tournament.oddPlayInPairing) {
                    // If somebody leaves before the play-in can begin, treat it as a forfeit;
                    // never crown the previous-round winner and the waiting entrant together.
                    tournament.oddPlayInPairing = null;
                    tournament.oddEntrantTeam = null;
                    tournament.pendingMatches.clear();
                    if (firstAvailable && !secondAvailable) {
                        eliminateTeam(tournament, pairing.second, true);
                        completeTournament(tournament, pairing.first);
                    } else if (!firstAvailable && secondAvailable) {
                        eliminateTeam(tournament, pairing.first, true);
                        completeTournament(tournament, pairing.second);
                    } else {
                        eliminateTeam(tournament, pairing.first, true);
                        eliminateTeam(tournament, pairing.second, true);
                        cancelTournament(tournament, configText("messages.cancel-no-winner", "The bracket ended without a winner."));
                    }
                    return;
                }
                if (firstAvailable && !secondAvailable) {
                    eliminateTeam(tournament, pairing.second, true);
                    recordRoundWinnerOrPairWaiting(tournament, pairing.first);
                } else if (!firstAvailable && secondAvailable) {
                    eliminateTeam(tournament, pairing.first, true);
                    recordRoundWinnerOrPairWaiting(tournament, pairing.second);
                } else {
                    // Neither side can play. Remove both bracket entries instead of leaving one
                    // team registered forever with no opponent or match callback.
                    eliminateTeam(tournament, pairing.first, true);
                    eliminateTeam(tournament, pairing.second, true);
                }
                continue;
            }
            boolean started;
            if (tournament.format == Format.ONE_V_ONE) {
                Player first = Bukkit.getPlayer(pairing.first.members.get(0));
                Player second = Bukkit.getPlayer(pairing.second.members.get(0));
                started = plugin.getQueueManager().startTournamentDuel(first, second, tournament.kitId,
                        winnerId -> handleSoloResult(tournament, pairing, winnerId));
            } else if (pairing.first.members.size() == 2 && pairing.second.members.size() == 2) {
                started = plugin.getPartyMatchManager().startTournamentSplitFight(
                        pairing.first.members, pairing.second.members, tournament.kitId,
                        winners -> handleTeamResult(tournament, pairing, winners));
            } else {
                // If a 2v2 bracket pairing includes an odd singleton entrant, resolve that
                // play-in as a true 1v1. The winner advances as the whole represented bracket team.
                Player first = Bukkit.getPlayer(pairing.first.members.get(0));
                Player second = Bukkit.getPlayer(pairing.second.members.get(0));
                started = first != null && second != null && plugin.getQueueManager().startTournamentDuel(
                        first, second, tournament.kitId,
                        winnerId -> handleSoloResult(tournament, pairing, winnerId));
            }
            if (started) {
                tournament.inProgress.add(pairing);
                for (UUID uuid : pairing.allMembers()) tournament.activePairingsByPlayer.put(uuid, pairing);
            } else {
                tournament.pendingMatches.addFirst(pairing);
                blockedOnArena = true;
                break;
            }
        }
        if (blockedOnArena && tournament.retryTask == null) {
            tournament.retryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (active != tournament || tournament.status != Status.RUNNING) {
                    if (tournament.retryTask != null) tournament.retryTask.cancel();
                    tournament.retryTask = null;
                    return;
                }
                startPendingMatches(tournament);
            }, 20L, 20L);
        } else if (!blockedOnArena && !tournament.pendingMatches.isEmpty()) {
            // A forfeited/unavailable pairing may have generated a new waiting-entrant pairing
            // after this pass captured its initial queue size. Process that new pairing now;
            // otherwise a 3-entrant tournament could stall if the first fight cannot start.
            startPendingMatches(tournament);
            return;
        } else if (!blockedOnArena && tournament.pendingMatches.isEmpty() && tournament.retryTask != null) {
            tournament.retryTask.cancel();
            tournament.retryTask = null;
        }
        finishRoundIfReady(tournament);
    }

    private boolean teamAvailable(Tournament tournament, TournamentTeam team) {
        if (tournament.format == Format.ONE_V_ONE && team.members.size() != 1) return false;
        if (tournament.format == Format.TWO_V_TWO && (team.members.isEmpty() || team.members.size() > 2)) return false;
        for (UUID uuid : team.members) {
            if (!tournament.registered.contains(uuid)) return false;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || plugin.getMatchSpectatorManager().isSpectating(uuid)) return false;
            if (plugin.getQueueManager().isInMatch(uuid)) return false;
        }
        return true;
    }

    private void handleSoloResult(Tournament tournament, Pairing pairing, UUID winnerId) {
        if (active != tournament || tournament.status != Status.RUNNING || !tournament.inProgress.remove(pairing)) return;
        for (UUID uuid : pairing.allMembers()) tournament.activePairingsByPlayer.remove(uuid, pairing);
        if (pairing == tournament.oddPlayInPairing) {
            boolean firstForfeited = tournament.forfeitedTeams.contains(pairing.first.id);
            boolean secondForfeited = tournament.forfeitedTeams.contains(pairing.second.id);
            TournamentTeam champion;
            if (firstForfeited && !secondForfeited) champion = pairing.second;
            else if (secondForfeited && !firstForfeited) champion = pairing.first;
            else champion = pairing.first.members.contains(winnerId) ? pairing.first
                    : pairing.second.members.contains(winnerId) ? pairing.second : null;
            tournament.forfeitedTeams.remove(pairing.first.id);
            tournament.forfeitedTeams.remove(pairing.second.id);
            tournament.oddPlayInPairing = null;
            tournament.oddEntrantTeam = null;
            if (champion == null) {
                eliminateTeam(tournament, pairing.first, true);
                eliminateTeam(tournament, pairing.second, true);
                cancelTournament(tournament, configText("messages.cancel-no-winner", "The bracket ended without a winner."));
            } else {
                eliminateTeam(tournament, champion == pairing.first ? pairing.second : pairing.first, true);
                // Only the play-in winner (or the winner's full 2v2 team) can receive champion status.
                completeTournament(tournament, champion);
            }
            return;
        }
        boolean firstForfeited = tournament.forfeitedTeams.contains(pairing.first.id);
        boolean secondForfeited = tournament.forfeitedTeams.contains(pairing.second.id);
        TournamentTeam winner;
        if (firstForfeited && !secondForfeited) winner = pairing.second;
        else if (secondForfeited && !firstForfeited) winner = pairing.first;
        else winner = pairing.first.members.contains(winnerId) ? pairing.first
                    : pairing.second.members.contains(winnerId) ? pairing.second : null;
        tournament.forfeitedTeams.remove(pairing.first.id);
        tournament.forfeitedTeams.remove(pairing.second.id);
        if (winner != null) {
            eliminateTeam(tournament, winner == pairing.first ? pairing.second : pairing.first, true);
            recordRoundWinnerOrPairWaiting(tournament, winner);
        } else {
            eliminateTeam(tournament, pairing.first, true);
            eliminateTeam(tournament, pairing.second, true);
        }
        startPendingMatches(tournament);
    }

    private void handleTeamResult(Tournament tournament, Pairing pairing, Set<UUID> winnerIds) {
        if (active != tournament || tournament.status != Status.RUNNING || !tournament.inProgress.remove(pairing)) return;
        for (UUID uuid : pairing.allMembers()) tournament.activePairingsByPlayer.remove(uuid, pairing);
        boolean firstForfeited = tournament.forfeitedTeams.contains(pairing.first.id);
        boolean secondForfeited = tournament.forfeitedTeams.contains(pairing.second.id);
        TournamentTeam winner;
        if (firstForfeited && !secondForfeited) {
            winner = pairing.second;
        } else if (secondForfeited && !firstForfeited) {
            winner = pairing.first;
        } else if (firstForfeited || secondForfeited) {
            winner = null;
        } else {
            winner = winnerIds != null && winnerIds.stream().anyMatch(pairing.first.members::contains)
                    ? pairing.first : winnerIds != null && winnerIds.stream().anyMatch(pairing.second.members::contains)
                    ? pairing.second : null;
        }
        tournament.forfeitedTeams.remove(pairing.first.id);
        tournament.forfeitedTeams.remove(pairing.second.id);
        if (winner != null) {
            eliminateTeam(tournament, winner == pairing.first ? pairing.second : pairing.first, true);
            recordRoundWinnerOrPairWaiting(tournament, winner);
        } else {
            eliminateTeam(tournament, pairing.first, true);
            eliminateTeam(tournament, pairing.second, true);
        }
        startPendingMatches(tournament);
    }

    /**
     * Records a match winner, unless an entrant is waiting due to an odd bracket size. In that
     * case the waiting entrant immediately gets a real match against this winner; it is not a bye.
     */
    private void recordRoundWinnerOrPairWaiting(Tournament tournament, TournamentTeam winner) {
        TournamentTeam waiting = tournament.waitingTeam;
        if (waiting == null) {
            tournament.roundWinners.add(winner);
            return;
        }
        tournament.waitingTeam = null;
        if (!waiting.members.stream().allMatch(tournament.registered::contains)) {
            tournament.roundWinners.add(winner);
            return;
        }
        Pairing waitingPair = new Pairing(waiting, winner);
        boolean onlyRemainingMatch = tournament.pendingMatches.isEmpty() && tournament.inProgress.isEmpty();
        tournament.pendingMatches.addFirst(waitingPair);
        if (onlyRemainingMatch && tournament.oddEntrantTeam == null) {
            broadcastConfigured("finals", "&9{kit} Finals has started!", "kit", plugin.getKitManager().getDisplayName(tournament.kitId));
            Bukkit.broadcast(finalAnnouncement(waitingPair));
        }
    }

    private void eliminateTeam(Tournament tournament, TournamentTeam team, boolean refreshInventory) {
        tournament.forfeitedTeams.remove(team.id);
        for (UUID uuid : team.members) {
            clearTeammateCooldowns(uuid);
            if (!tournament.registered.remove(uuid)) continue;
            UUID partner = tournament.teammates.remove(uuid);
            if (partner != null) tournament.teammates.remove(partner);
            clearInviteFor(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (refreshInventory && online != null && online.isOnline()) plugin.giveLobbyItems(online);
        }
    }

    private void finishRoundIfReady(Tournament tournament) {
        if (active != tournament || tournament.status != Status.RUNNING
                || !tournament.pendingMatches.isEmpty() || !tournament.inProgress.isEmpty()
                || tournament.oddPlayInPairing != null) return;
        if (tournament.retryTask != null) {
            tournament.retryTask.cancel();
            tournament.retryTask = null;
        }
        if (tournament.waitingTeam != null) {
            TournamentTeam waiting = tournament.waitingTeam;
            tournament.waitingTeam = null;
            if (waiting.members.stream().allMatch(tournament.registered::contains)) {
                if (tournament.roundWinners.isEmpty()) {
                    // Do not turn an odd entrant into a free bye if every possible opponent
                    // forfeited/disconnected before an actual match could be played.
                    cancelTournament(tournament, configText("messages.cancel-waiting-without-opponent",
                            "The waiting entrant had no eligible opponent; no winner was awarded."));
                    return;
                }
                TournamentTeam opponent = tournament.roundWinners.iterator().next();
                tournament.roundWinners.remove(opponent);
                Pairing finalPair = new Pairing(waiting, opponent);
                tournament.pendingMatches.addFirst(finalPair);
                broadcastConfigured("finals", "&9{kit} Finals has started!", "kit", plugin.getKitManager().getDisplayName(tournament.kitId));
                Bukkit.broadcast(finalAnnouncement(finalPair));
                startPendingMatches(tournament);
                return;
            }
        }
        if (tournament.roundWinners.isEmpty()) {
            cancelTournament(tournament, configText("messages.cancel-no-winner", "The bracket ended without a winner."));
            return;
        }
        List<TournamentTeam> winners = new ArrayList<>();
        for (TournamentTeam team : tournament.roundWinners) {
            if (team.members.stream().allMatch(tournament.registered::contains)) winners.add(team);
        }
        if (winners.isEmpty()) {
            cancelTournament(tournament, configText("messages.cancel-no-eligible-players", "No eligible players remain in the bracket."));
        } else if (winners.size() == 1) {
            if (tournament.oddEntrantTeam != null) startOddEntrantPlayIn(tournament, winners.get(0));
            else completeTournament(tournament, winners.get(0));
        } else {
            startRound(tournament, winners);
        }
    }

    /** Hold the odd 2v2 entrant until the complete-team bracket has one finalist, then schedule a real 1v1. */
    private void startOddEntrantPlayIn(Tournament tournament, TournamentTeam finalist) {
        if (active != tournament || tournament.status != Status.RUNNING) return;
        TournamentTeam oddEntrant = tournament.oddEntrantTeam;
        if (oddEntrant == null) {
            completeTournament(tournament, finalist);
            return;
        }
        if (oddEntrant.members.size() != 1
                || !oddEntrant.members.stream().allMatch(tournament.registered::contains)) {
            tournament.oddEntrantTeam = null;
            completeTournament(tournament, finalist);
            return;
        }

        tournament.waitingTeam = null;
        tournament.roundWinners.clear();
        tournament.pendingMatches.clear();
        Pairing playIn = new Pairing(finalist, oddEntrant);
        tournament.oddPlayInPairing = playIn;
        tournament.pendingMatches.addFirst(playIn);
        broadcastConfigured("finals", "&9{kit} Finals has started!", "kit", plugin.getKitManager().getDisplayName(tournament.kitId));
        Bukkit.broadcast(finalAnnouncement(playIn));
        startPendingMatches(tournament);
    }

    private void completeTournament(Tournament tournament, TournamentTeam champion) {
        if (active != tournament || tournament.status == Status.COMPLETE) return;
        tournament.status = Status.COMPLETE;
        String winnerNames = teamNames(champion);
        broadcastConfigured("winner-separator", "&7━━━━━━━━━━━━━━━━━━━━");
        broadcastConfigured("winner-title", "&6Tournament");
        broadcastConfigured("winner-line", "&7{winners} &6Won the Tournament!", "winners", winnerNames);
        broadcastConfigured("winner-separator", "&7━━━━━━━━━━━━━━━━━━━━");
        int reward = Math.max(0, plugin.getConfig().getInt("tournaments.winner-coins", 100));
        for (UUID winnerId : champion.members) {
            if (plugin.getCoinsManager() != null && reward > 0) plugin.getCoinsManager().addCoins(winnerId, reward);
            Player winner = Bukkit.getPlayer(winnerId);
            if (winner != null && winner.isOnline()) sendConfigured(winner, "reward-received", "&aRecived {amount}⛃ for winning on tournament", "amount", String.valueOf(reward));
        }
        cleanupTournament(tournament);
    }

    private void cancelTournament(Tournament tournament, String reason) {
        if (active != tournament || tournament.status == Status.CANCELLED || tournament.status == Status.COMPLETE) return;
        tournament.status = Status.CANCELLED;
        broadcastConfigured("tournament-cancelled", "&cTournament cancelled: &7{reason}", "reason", reason);
        cleanupTournament(tournament);
    }

    private void cleanupTournament(Tournament tournament) {
        if (tournament.retryTask != null) tournament.retryTask.cancel();
        Set<UUID> participants = new LinkedHashSet<>(tournament.registered);
        for (UUID uuid : participants) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                clearTournamentItems(online);
                plugin.giveLobbyItems(online);
            }
        }
        for (UUID uuid : participants) clearTeammateCooldowns(uuid);
        tournament.registered.clear();
        tournament.teammates.clear();
        tournament.forfeitedTeams.clear();
        tournament.waitingTeam = null;
        tournament.oddEntrantTeam = null;
        tournament.oddPlayInPairing = null;
        tournament.pendingMatches.clear();
        tournament.inProgress.clear();
        tournament.activePairingsByPlayer.clear();
        for (UUID target : new ArrayList<>(invitesByTarget.keySet())) {
            Invite invite = invitesByTarget.get(target);
            if (invite != null && participants.contains(invite.inviter)) clearInviteFor(target);
        }
        if (active == tournament) active = null;
    }

    public void leaveTournament(Player player, boolean viaItem) {
        if (player == null || !player.isOnline()) return;
        Tournament tournament = active;
        UUID uuid = player.getUniqueId();
        if (tournament == null || !tournament.registered.contains(uuid)) {
            sendConfigured(player, "not-in-tournament", "&cYou are not in a tournament.");
            return;
        }
        if (tournament.activePairingsByPlayer.containsKey(uuid)) {
            sendConfigured(player, "unable-state", UNABLE_STATE_MESSAGE);
            return;
        }
        UUID partner = tournament.teammates.get(uuid);
        if (tournament.status == Status.RUNNING && partner != null) {
            tournament.registered.remove(partner);
            tournament.teammates.remove(partner);
            Player onlinePartner = Bukkit.getPlayer(partner);
            if (onlinePartner != null && onlinePartner.isOnline()) {
                sendConfigured(onlinePartner, "team-left", "&cYour team left the tournament because your teammate left.");
                plugin.giveLobbyItems(onlinePartner);
            }
        }
        tournament.registered.remove(uuid);
        if (tournament.oddEntrantTeam != null && tournament.oddEntrantTeam.members.contains(uuid)) {
            tournament.oddEntrantTeam = null;
        }
        if (partner != null) tournament.teammates.remove(partner);
        tournament.teammates.remove(uuid);
        clearInviteFor(uuid);
        String leftMessage = configText("messages.left", "&9{player} &6Left the tournament!", "player", player.getName());
        broadcastToParticipants(tournament, leftMessage);
        // The leaver has already been removed from registration, so notify them directly too.
        Texts.send(player, leftMessage);
        if (partner != null && tournament.status == Status.REGISTRATION) {
            Player onlinePartner = Bukkit.getPlayer(partner);
            if (onlinePartner != null && onlinePartner.isOnline() && tournament.registered.contains(partner)) {
                giveParticipantItems(onlinePartner);
            }
        }
        clearTournamentItems(player);
        plugin.giveLobbyItems(player);
        if (tournament.status == Status.RUNNING) startPendingMatches(tournament);
    }

    public void handleQuit(UUID uuid) {
        clearInviteFor(uuid);
        clearTeammateCooldowns(uuid);
        if (active == null || !active.registered.contains(uuid)) return;
        String name = playerName(uuid);
        if (active.status == Status.RUNNING && active.format == Format.TWO_V_TWO) {
            Pairing pairing = active.activePairingsByPlayer.get(uuid);
            PartyMatch currentMatch = plugin.getPartyMatchManager() == null ? null : plugin.getPartyMatchManager().getMatch(uuid);
            if (pairing != null && currentMatch != null && !currentMatch.finishing() && !currentMatch.isEliminated(uuid)) {
                TournamentTeam ownTeam = pairing.first.members.contains(uuid) ? pairing.first : pairing.second;
                active.forfeitedTeams.add(ownTeam.id);
                UUID teammateId = ownTeam.members.stream().filter(memberId -> !memberId.equals(uuid)).findFirst().orElse(null);
                Player teammate = teammateId == null ? null : Bukkit.getPlayer(teammateId);
                if (teammate != null && teammate.isOnline()) {
                    sendConfigured(teammate, "team-disconnected", "&cYour team will forfeit this tournament match because your teammate disconnected.");
                }
            }
        }
        if (active.status == Status.REGISTRATION) {
            active.registered.remove(uuid);
            if (active.oddEntrantTeam != null && active.oddEntrantTeam.members.contains(uuid)) active.oddEntrantTeam = null;
            UUID partner = active.teammates.remove(uuid);
            if (partner != null) active.teammates.remove(partner);
            Player onlinePartner = partner == null ? null : Bukkit.getPlayer(partner);
            if (onlinePartner != null && onlinePartner.isOnline()) giveParticipantItems(onlinePartner);
            broadcastToParticipants(active, configText("messages.left", "&9{player} &6Left the tournament!", "player", name));
            return;
        }
        // Active fighters are resolved by QueueManager / PartyMatchManager as forfeits. A player
        // who disconnects between rounds forfeits their whole fixed 2v2 team slot.
        if (!active.activePairingsByPlayer.containsKey(uuid)) {
            if (active.oddEntrantTeam != null && active.oddEntrantTeam.members.contains(uuid)) active.oddEntrantTeam = null;
            UUID partner = active.teammates.remove(uuid);
            if (partner != null) active.teammates.remove(partner);
            active.registered.remove(uuid);
            if (partner != null) active.registered.remove(partner);
            broadcastToParticipants(active, configText("messages.left", "&9{player} &6Left the tournament!", "player", name));
            Player onlinePartner = partner == null ? null : Bukkit.getPlayer(partner);
            if (onlinePartner != null && onlinePartner.isOnline()) {
                clearTournamentItems(onlinePartner);
                plugin.giveLobbyItems(onlinePartner);
                sendConfigured(onlinePartner, "team-left", "&cYour team left the tournament because your teammate left.");
                Texts.send(onlinePartner, configText("messages.left", "&9{player} &6Left the tournament!", "player", name));
            }
        }
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendConfigured(sender, "usage", "&cUsage: /vtournament <join|leave|accept|deny|cancel>");
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel")) {
            if (!(sender instanceof Player player) || !player.hasPermission("vprac.admin.tournament")) {
                sendConfigured(sender, "cancel-no-permission", "&cYou do not have permission to cancel a tournament.");
                return true;
            }
            if (active == null) sendConfigured(player, "no-active-tournament", "&cThere is no active tournament.");
            else if (active.status != Status.REGISTRATION) sendConfigured(player, "cancel-registration-only", "&cA tournament can only be cancelled during registration.");
            else cancelTournament(active, configText("messages.cancelled-by-admin", "Cancelled by an administrator."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sendConfigured(sender, "players-only", "&cOnly players can use this command.");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                UUID tournamentId = null;
                if (args.length >= 2) {
                    try { tournamentId = UUID.fromString(args[1]); }
                    catch (IllegalArgumentException exception) {
                        sendConfigured(player, "invalid-tournament-link", "&cThat tournament link is invalid.");
                        return true;
                    }
                }
                joinTournament(player, tournamentId);
            }
            case "leave" -> leaveTournament(player, false);
            case "accept", "deny" -> {
                if (args.length < 2) {
                    sendConfigured(player, "invite-usage", "&cUsage: /vtournament {action} <invite-id>", "action", args[0].toLowerCase(Locale.ROOT));
                    return true;
                }
                UUID token;
                try { token = UUID.fromString(args[1]); }
                catch (IllegalArgumentException exception) {
                    sendConfigured(player, "invalid-invite-link", "&cThat invitation link is invalid.");
                    return true;
                }
                if (args[0].equalsIgnoreCase("accept")) acceptTeammateInvite(player, token);
                else denyTeammateInvite(player, token);
            }
            default -> sendConfigured(player, "usage", "&cUsage: /vtournament <join|leave|accept|deny|cancel>");
        }
        return true;
    }

    private void broadcastToParticipants(Tournament tournament, String message) {
        Component component = Texts.component(message);
        for (UUID uuid : new ArrayList<>(tournament.registered)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) player.sendMessage(component);
        }
    }

    private static String teammateCooldownKey(UUID inviter, UUID target) {
        return inviter + ":" + target;
    }

    private void clearTeammateCooldowns(UUID uuid) {
        inviteCooldownUntil.keySet().removeIf(key -> key.startsWith(uuid + ":") || key.endsWith(":" + uuid));
    }

    private void clearInviteFor(UUID uuid) {
        Invite incoming = invitesByTarget.remove(uuid);
        if (incoming != null) outgoingInviteTarget.remove(incoming.inviter, uuid);
        UUID target = outgoingInviteTarget.remove(uuid);
        if (target != null) invitesByTarget.remove(target);
    }

    private ItemStack createLeaveItem() {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component(configText("items.leave-name", "&cLeave tournament&7(Right click)")));
        meta.lore(Texts.nonItalicLore(List.of(Texts.component(configText("items.leave-lore", "&7Right-click to leave the tournament.")))));
        meta.getPersistentDataContainer().set(leaveItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTeammateItem(UUID playerId) {
        UUID teammateId = active == null ? null : active.teammates.get(playerId);
        Player teammate = teammateId == null ? null : Bukkit.getPlayer(teammateId);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta skull) {
            if (teammate != null && teammate.isOnline()) skull.setOwningPlayer(teammate);
            else skull.setOwningPlayer(Bukkit.getOfflinePlayer(teammateId == null ? playerId : teammateId));
            skull.displayName(Texts.component(teammateId == null
                    ? configText("items.teammate-name", "&6Teammate&7(Right Click)")
                    : configText("items.teammate-named", "&6{player}&7(Right Click)", "player", playerName(teammateId))));
            skull.lore(Texts.nonItalicLore(List.of(Texts.component(teammateId == null
                    ? configText("items.teammate-unpaired-lore", "&7Choose a tournament teammate.")
                    : configText("items.teammate-paired-lore", "&7Manage your teammate.")))));
            skull.getPersistentDataContainer().set(teammateItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(skull);
        } else {
            raw.displayName(Texts.component(configText("items.teammate-name", "&6Teammate&7(Right Click)")));
            raw.getPersistentDataContainer().set(teammateItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(raw);
        }
        return item;
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component(name));
        meta.lore(Texts.nonItalicLore(lore.stream().map(Texts::component).toList()));
        item.setItemMeta(meta);
        return item;
    }

    private void showDialog(Player player, String title, List<DialogBody> body) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                        .body(body).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).build())
                .type(DialogType.notice(ActionButtonFactory.closeButton(configText("ui.close-button", "Close")))));
        player.showDialog(dialog);
    }

    private ClickEvent custom(String action) { return ClickEvent.custom(Key.key("vprac", action), EMPTY_TAG); }

    private String playerName(UUID uuid) {
        if (uuid == null) return "Unknown";
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String offline = Bukkit.getOfflinePlayer(uuid).getName();
        return offline == null || offline.isBlank() ? uuid.toString().substring(0, 8) : offline;
    }

    public void shutdown() {
        if (active != null && active.retryTask != null) active.retryTask.cancel();
        active = null;
        invitesByTarget.clear();
        inviteCooldownUntil.clear();
        outgoingInviteTarget.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof TeammateMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (event.getClickedInventory() != top || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        int slot = event.getRawSlot();
        if (holder.leaveMenu) {
            if (slot == 4 && holder.partnerId != null) {
                UUID partnerId = holder.partnerId;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) leaveTeammate(player, partnerId);
                });
            }
            return;
        }
        if (slot == 30 && holder.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) openTeammateSelector(player, holder.page - 1); });
            return;
        }
        if (slot == 32) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) openTeammateSelector(player, holder.page + 1); });
            return;
        }
        UUID targetId = holder.targetAt.get(slot);
        if (targetId == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            sendTeammateInvite(player, targetId);
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof TeammateMenuHolder) {
                openTeammateSelector(player, holder.page);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TeammateMenuHolder) event.setCancelled(true);
    }

    private static final class TeammateMenuHolder implements InventoryHolder {
        private final UUID owner;
        private final boolean leaveMenu;
        private final int page;
        private final Map<Integer, UUID> targetAt = new HashMap<>();
        private UUID partnerId;
        private Inventory inventory;
        private TeammateMenuHolder(UUID owner, boolean leaveMenu) { this(owner, leaveMenu, 0); }
        private TeammateMenuHolder(UUID owner, boolean leaveMenu, int page) {
            this.owner = owner;
            this.leaveMenu = leaveMenu;
            this.page = page;
        }
        private void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class TournamentTeam {
        private final UUID id = UUID.randomUUID();
        private final List<UUID> members;
        private TournamentTeam(List<UUID> members) { this.members = List.copyOf(members); }
    }

    private static final class Pairing {
        private final TournamentTeam first;
        private final TournamentTeam second;
        private Pairing(TournamentTeam first, TournamentTeam second) { this.first = first; this.second = second; }
        private Set<UUID> allMembers() {
            LinkedHashSet<UUID> ids = new LinkedHashSet<>(first.members);
            ids.addAll(second.members);
            return ids;
        }
    }

    private static final class Invite {
        private final UUID token;
        private final UUID inviter;
        private final UUID target;
        private final long expiresAt;
        private Invite(UUID token, UUID inviter, UUID target, long expiresAt) {
            this.token = token;
            this.inviter = inviter;
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Tournament {
        private final UUID id;
        private final Format format;
        private final String kitId;
        private final LinkedHashSet<UUID> registered = new LinkedHashSet<>();
        private final Map<UUID, UUID> teammates = new HashMap<>();
        private final Deque<Pairing> pendingMatches = new ArrayDeque<>();
        private final Set<Pairing> inProgress = new HashSet<>();
        private final Set<TournamentTeam> roundWinners = new LinkedHashSet<>();
        private TournamentTeam waitingTeam;
        /** Odd individual in a 2v2 tournament; must play a 1v1 play-in before any champion is declared. */
        private TournamentTeam oddEntrantTeam;
        /** Exact active/pending pairing for the odd entrant's final 1v1; it cannot be treated as a bye. */
        private Pairing oddPlayInPairing;
        private final Set<UUID> forfeitedTeams = new HashSet<>();
        private final Map<UUID, Pairing> activePairingsByPlayer = new HashMap<>();
        private final long registrationEndsAt = System.currentTimeMillis() + REGISTRATION_SECONDS * 1000L;
        private Status status = Status.REGISTRATION;
        private int roundNumber;
        private org.bukkit.scheduler.BukkitTask retryTask;
        private Tournament(UUID id, Format format, String kitId) { this.id = id; this.format = format; this.kitId = kitId; }
        private long secondsRemaining() {
            return Math.max(0L, (registrationEndsAt - System.currentTimeMillis() + 999L) / 1000L);
        }
    }

    /** Paper 1.21.11 DialogType.notice needs an action button for its close control. */
    private static final class ActionButtonFactory {
        private static io.papermc.paper.registry.data.dialog.ActionButton closeButton(String label) {
            return io.papermc.paper.registry.data.dialog.ActionButton.builder(Texts.component(label))
                    .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(Key.key("vprac", "tournament/close"), EMPTY_TAG))
                    .build();
        }
    }
}
