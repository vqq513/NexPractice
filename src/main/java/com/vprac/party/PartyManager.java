package com.vprac.party;

import com.vprac.VpracPlugin;
import com.vprac.queue.PartyMatchManager;
import com.vprac.util.Texts;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Party membership, invites, public discovery, permissions, and no-mod Dialog UI. */
public final class PartyManager {
    public static final int MAX_MEMBERS = 4; // The party-FFA arena has four cardinal spawn points.
    private static final long INVITE_TTL_MILLIS = 120_000L;
    private static final long INVITE_COOLDOWN_MILLIS = 60_000L;
    private static final int MAX_INVITE_SEARCH_RESULTS = 36;
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");
    private final VpracPlugin plugin;
    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, Party> partyByMember = new HashMap<>();
    private final Map<UUID, List<Invite>> invitesByTarget = new HashMap<>();
    /** Per-target cooldown: inviting one player does not block inviting a different player. */
    private final Map<String, Long> inviteCooldownUntil = new HashMap<>();
    /** Members who were kicked must receive a new explicit invite before rejoining that party. */
    private final Map<UUID, Set<UUID>> kickedFromParties = new HashMap<>();

    public PartyManager(VpracPlugin plugin) { this.plugin = plugin; }

    public Party getParty(UUID player) { return partyByMember.get(player); }
    public Party getPartyById(UUID id) { return parties.get(id); }
    public Collection<Party> allParties() { return Collections.unmodifiableCollection(parties.values()); }

    /** If an administrator deletes a kit currently selected by a party, return that party to the built-in Sword kit. */
    public void onKitRemoved(String kitId) {
        if (kitId == null) return;
        for (Party party : parties.values()) {
            if (party.selectedKit().equalsIgnoreCase(kitId)) party.setSelectedKit("sword");
        }
    }

    public boolean isLeader(UUID player) {
        Party party = getParty(player);
        return party != null && party.leader().equals(player);
    }

    public Party createParty(Player leader) {
        UUID uuid = leader.getUniqueId();
        Party existing = getParty(uuid);
        if (existing != null) return existing;
        if (plugin.getQueueManager().isInMatch(uuid)) {
            Texts.send(leader, "You cannot create or join a party while in a match.");
            return null;
        }
        plugin.getQueueManager().removeFromAllQueues(uuid);
        Party party = new Party(UUID.randomUUID(), uuid);
        parties.put(party.id(), party);
        partyByMember.put(uuid, party);
        plugin.removeQueueItem(leader);
        return party;
    }

    /** Called when the protected nametag is right-clicked. */
    public void openForPlayer(Player player) {
        Party party = getParty(player.getUniqueId());
        // Unpartied players must be able to browse public parties without first creating a party.
        // The no-party Dialog offers both Create Party and Browse Open Parties.
        if (party == null) {
            openNoPartyDialog(player);
            return;
        }
        if (plugin.getPartyMatchManager().isPartyActive(party.id())) {
            Texts.send(player, "Your party is currently fighting. Party controls are locked until the match ends.");
            return;
        }
        openPartyMenu(player);
    }

    public void openPartyMenu(Player player) {
        Party party = getParty(player.getUniqueId());
        if (party == null) {
            openNoPartyDialog(player);
            return;
        }
        if (plugin.getPartyMatchManager().isPartyActive(party.id())) {
            List<DialogBody> body = List.of(DialogBody.plainMessage(Component.text(
                    "Your party is currently in a fight. Wait until the fight ends before changing party settings.", NamedTextColor.GRAY), 330));
            showDialog(player, "Party", body, List.of(closeButton("Close", "party/close")), null, 1, List.of());
            return;
        }

        List<DialogBody> body = new ArrayList<>();
        boolean leader = party.leader().equals(player.getUniqueId());
        Player leaderPlayer = Bukkit.getPlayer(party.leader());
        String leaderName = leaderPlayer == null ? "offline" : leaderPlayer.getName();
        body.add(DialogBody.plainMessage(Component.text(
                "Leader: " + leaderName + "  ·  Party size: " + party.size() + "/" + party.maxMembersDisplay(), NamedTextColor.GRAY), 360));
        Component membersLink = Texts.component("&bMembers&7(Click to view members)")
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(custom("party/members"))
                .hoverEvent(HoverEvent.showText(Texts.component("&fView all current party members")));
        body.add(DialogBody.plainMessage(membersLink, 360));
        body.add(DialogBody.plainMessage(Component.text("PARTY SETTINGS", NamedTextColor.AQUA), 360));
        Component visibility = Component.text("● " + (party.open() ? "OPEN PARTY" : "CLOSED PARTY"),
                party.open() ? NamedTextColor.GREEN : NamedTextColor.RED)
                .clickEvent(custom("party/toggle-open"))
                .hoverEvent(HoverEvent.showText(Component.text(leader ? "Click to change party visibility" : "Only the leader can change visibility")));
        body.add(DialogBody.plainMessage(visibility, 360));
        String announceRank = plugin.getConfig().getString("party.announce-required-rank", "Your Rank");
        String announceHover = plugin.getConfig().getString("party.announce-hover", "&fRequire {required_rank} to announce party!")
                .replace("{required_rank}", announceRank);
        Component announceLine = Component.text("◇ Announce Party", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(custom("party/announce"))
                .hoverEvent(HoverEvent.showText(Texts.component(announceHover)));
        body.add(DialogBody.plainMessage(announceLine, 360));
        body.add(DialogBody.plainMessage(Component.text("Party FFA: everyone fights individually. Split Fight: balanced teams inside this party.", NamedTextColor.GRAY), 360));

        List<ActionButton> actions = new ArrayList<>();
        List<DialogInput> inputs = new ArrayList<>();
        if (leader) {
            actions.add(button("Invite Player", "party/invite/open"));
            if (party.size() > 1) actions.add(button("Kick Party Member", "party/kick-menu"));
            actions.add(button("FFA Fight", "party/fight/ffa"));
            actions.add(button("Split Fight (Teams)", "party/fight/split"));
            boolean unlimitedPermission = player.hasPermission("vprac.party.max-unlimited");
            int sliderMaximum = unlimitedPermission ? 150 : 35;
            int sliderInitial = party.hasUnlimitedMembers() ? 150 : Math.max(2, Math.min(sliderMaximum, party.maxMembers()));
            inputs.add(DialogInput.numberRange("party_max_players", Component.text("Party max players", NamedTextColor.AQUA),
                            2.0f, (float) sliderMaximum)
                    .width(300).labelFormat("%s: %s").initial((float) sliderInitial).step(1.0f).build());
            body.add(DialogBody.plainMessage(Texts.component(unlimitedPermission
                    ? "&7Set the slider to &e150 &7for &aUnlimited &7party members."
                    : "&7Your maximum party capacity is &e35 &7members."), 360));
            actions.add(button("Save Max Players", "party/max-players/save"));
        }
        if (leader) actions.add(button("Disband Party", "party/disband"));
        else actions.add(button("Leave Party", "party/leave"));
        ActionButton close = closeButton("Close", "party/close");
        showDialog(player, "Party", body, actions, close, 2, inputs);
    }

    private void openPartyMembersDialog(Player player) {
        Party party = getParty(player.getUniqueId());
        if (party == null) {
            openNoPartyDialog(player);
            return;
        }
        boolean viewerIsLeader = party.leader().equals(player.getUniqueId());
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Texts.component("&7Party members: &f" + party.size() + "/" + party.maxMembersDisplay()), 360));
        for (UUID memberId : party.members()) {
            Player online = Bukkit.getPlayer(memberId);
            OfflinePlayer offline = online == null ? Bukkit.getOfflinePlayer(memberId) : online;
            String name = online != null ? online.getName() : offline.getName();
            if (name == null || name.isBlank()) name = memberId.toString().substring(0, 8);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(offline);
                head.setItemMeta(skullMeta);
            }

            Component row = Component.text(name, memberId.equals(party.leader()) ? NamedTextColor.GOLD : NamedTextColor.AQUA);
            if (memberId.equals(party.leader())) {
                row = row.append(Component.text("  (Leader)", NamedTextColor.YELLOW));
            } else if (viewerIsLeader && !plugin.getPartyMatchManager().isPartyActive(party.id())) {
                Component transfer = Component.text("   ♛ Transfer", NamedTextColor.AQUA)
                        .clickEvent(custom("party/transfer/" + memberId))
                        .hoverEvent(HoverEvent.showText(Texts.component("&fTransfer party leadership to " + name)));
                Component kick = Component.text("   ✖ Kick", NamedTextColor.RED)
                        .clickEvent(custom("party/kick/" + memberId))
                        .hoverEvent(HoverEvent.showText(Texts.component("&fRemove " + name + " from your party")));
                row = row.append(transfer).append(kick);
            }
            body.add(DialogBody.item(head, DialogBody.plainMessage(row, 320), true, true, 24, 24));
        }
        showDialog(player, "Party Members", body,
                List.of(button("Back to Party", "party/menu")), closeButton("Close", "party/close"), 1, List.of());
    }

    private void openPartyKickDialog(Player player) {
        Party party = getParty(player.getUniqueId());
        if (!requireLeader(player, party)) { openPartyMenu(player); return; }
        if (party.members().size() <= 1) { Texts.send(player, "Your party has no other members to kick."); openPartyMenu(player); return; }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Texts.component("&7Click a member to remove them from your party."), 360));
        for (UUID memberId : party.members()) {
            if (memberId.equals(player.getUniqueId())) continue;
            Player online = Bukkit.getPlayer(memberId);
            OfflinePlayer offline = online == null ? Bukkit.getOfflinePlayer(memberId) : online;
            String name = online != null ? online.getName() : offline.getName();
            if (name == null || name.isBlank()) name = memberId.toString().substring(0, 8);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) { meta.setOwningPlayer(offline); head.setItemMeta(meta); }
            Component clickable = Component.text(name, NamedTextColor.RED)
                    .clickEvent(custom("party/kick-select/" + compact(memberId)))
                    .hoverEvent(HoverEvent.showText(Texts.component("&cClick to kick " + name)));
            body.add(DialogBody.item(head, DialogBody.plainMessage(clickable, 320), true, true, 24, 24));
        }
        showDialog(player, "Kick Party Member", body,
                List.of(button("Back to Party", "party/menu")), closeButton("Close", "party/close"), 1, List.of());
    }

    private void openNoPartyDialog(Player player) {
        List<DialogBody> body = List.of(DialogBody.plainMessage(
                Component.text("You are not in a party. Create one, or browse parties that are open to join.", NamedTextColor.GRAY), 340));
        List<ActionButton> actions = List.of(button("Create Party", "party/create"), button("Browse Open Parties", "party/browse"));
        showDialog(player, "Party", body, actions, closeButton("Close", "party/close"), 2, List.of());
    }

    private void openInviteDialog(Player player) {
        Party party = getParty(player.getUniqueId());
        if (!requireLeader(player, party)) return;
        if (party.isFull()) {
            Texts.send(player, plugin.getConfig().getString("party.messages.party-full", "&cYour party is full ({max_members} members maximum).").replace("{max_members}", party.maxMembersDisplay()));
            openPartyMenu(player);
            return;
        }
        List<DialogBody> body = List.of(DialogBody.plainMessage(
                Component.text("Enter all or part of an online player's name, then search. Click a matching player head to invite them.", NamedTextColor.GRAY), 340));
        DialogInput input = DialogInput.text("party_player", Component.text("Search player name", NamedTextColor.AQUA))
                .width(260).maxLength(16).labelVisible(true).build();
        List<ActionButton> actions = List.of(button("Search Players", "party/invite/send"));
        showDialog(player, "Invite to Party", body, actions, closeButton("Back to Party", "party/invite/back"), 2, List.of(input));
    }

    /** Shows online players matching the submitted partial name. Native Dialog text inputs submit on action,
     * so suggestions refresh after the user clicks Search rather than on every keystroke. */
    private void openInviteSuggestions(Player inviter, String query) {
        Party party = getParty(inviter.getUniqueId());
        if (!requireLeader(inviter, party)) { openPartyMenu(inviter); return; }
        if (party.isFull()) {
            Texts.send(inviter, plugin.getConfig().getString("party.messages.party-full", "&cYour party is full ({max_members} members maximum).")
                    .replace("{max_members}", party.maxMembersDisplay()));
            openPartyMenu(inviter);
            return;
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isBlank()) {
            Texts.send(inviter, "&cEnter at least one letter to search for a player.");
            openInviteDialog(inviter);
            return;
        }

        List<Player> matches = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getUniqueId().equals(inviter.getUniqueId())) continue;
            String candidateName = candidate.getName().toLowerCase(Locale.ROOT);
            if (!candidateName.contains(needle)) continue;
            if (getParty(candidate.getUniqueId()) != null) continue;
            if (plugin.getQueueManager().isInMatch(candidate.getUniqueId())) continue;
            if (plugin.getPlayerPreferencesManager() != null
                    && !plugin.getPlayerPreferencesManager().arePartyInvitesAllowed(candidate.getUniqueId())) continue;
            matches.add(candidate);
        }
        matches.sort((first, second) -> {
            boolean firstPrefix = first.getName().toLowerCase(Locale.ROOT).startsWith(needle);
            boolean secondPrefix = second.getName().toLowerCase(Locale.ROOT).startsWith(needle);
            if (firstPrefix != secondPrefix) return firstPrefix ? -1 : 1;
            return first.getName().compareToIgnoreCase(second.getName());
        });

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Texts.component("&7Search: &f" + query.trim() + "\n&7Click a player head to send an invite."), 360));
        int shown = 0;
        for (Player candidate : matches) {
            if (shown >= MAX_INVITE_SEARCH_RESULTS) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta skullMeta) {
                skullMeta.setOwningPlayer(candidate);
                skullMeta.displayName(Texts.nonItalic(Component.text(candidate.getName(), NamedTextColor.AQUA)));
                skullMeta.lore(Texts.nonItalicLore(List.of(Component.text("Click to invite " + candidate.getName(), NamedTextColor.GREEN))));
                head.setItemMeta(skullMeta);
            }
            Component clickableName = Component.text(candidate.getName(), NamedTextColor.AQUA)
                    .clickEvent(custom("party/invite/select/" + compact(candidate.getUniqueId())))
                    .hoverEvent(HoverEvent.showText(Texts.component("&aClick to invite " + candidate.getName())));
            body.add(DialogBody.item(head, DialogBody.plainMessage(clickableName, 320), true, true, 24, 24));
            shown++;
        }
        if (shown == 0) body.add(DialogBody.plainMessage(Texts.component("&7No eligible online players matched that search."), 340));
        if (matches.size() > MAX_INVITE_SEARCH_RESULTS) {
            body.add(DialogBody.plainMessage(Texts.component("&7Showing the first " + MAX_INVITE_SEARCH_RESULTS + " matches. Refine your search for more."), 340));
        }
        showDialog(inviter, "Select Player to Invite", body,
                List.of(button("Search Again", "party/invite/open"), button("Back to Party", "party/invite/back")),
                closeButton("Close", "party/close"), 1, List.of());
    }

    private void openPartyBrowser(Player player) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("OPEN PARTIES  ·  Select a row to join.", NamedTextColor.AQUA), 360));
        int displayed = 0;
        for (Party party : parties.values()) {
            if (!party.open() || party.isFull() || plugin.getPartyMatchManager().isPartyActive(party.id())) continue;
            Player leader = Bukkit.getPlayer(party.leader());
            if (leader == null || !leader.isOnline()) continue;
            Component row = Component.text("● " + leader.getName() + "  ·  " + party.size() + "/" + party.maxMembersDisplay()
                            + "  ·  Kit: " + plugin.getKitManager().getDisplayName(party.selectedKit()), NamedTextColor.AQUA)
                    .clickEvent(custom("party/join/" + compact(party.id())))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to join this open party")));
            body.add(DialogBody.plainMessage(row, 360));
            displayed++;
            if (displayed >= 30) break;
        }
        if (displayed == 0) body.add(DialogBody.plainMessage(Component.text("No open parties are available right now.", NamedTextColor.GRAY), 340));
        showDialog(player, "Open Parties", body,
                List.of(button("Refresh", "party/browse"), button("Back to Party", "party/menu")),
                closeButton("Close", "party/close"), 2, List.of());
    }

    private void showDialog(Player player, String title, List<DialogBody> body, List<ActionButton> actions,
                            ActionButton exitAction, int columns, List<DialogInput> inputs) {
        Dialog dialog = Dialog.create(builder -> {
            DialogBase.Builder base = DialogBase.builder(Component.text(title, NamedTextColor.AQUA))
                    .body(body)
                    .canCloseWithEscape(true)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE);
            if (!inputs.isEmpty()) base.inputs(inputs);
            builder.empty().base(base.build()).type(DialogType.multiAction(actions, exitAction, columns));
        });
        player.showDialog(dialog);
    }

    private ActionButton button(String label, String action) {
        return ActionButton.builder(Component.text(label, NamedTextColor.AQUA))
                .action(DialogAction.customClick(Key.key("vprac", action), EMPTY_TAG))
                .build();
    }

    private ActionButton closeButton(String label, String action) {
        return ActionButton.builder(Component.text(label, NamedTextColor.GRAY))
                .action(DialogAction.customClick(Key.key("vprac", action), EMPTY_TAG))
                .build();
    }

    private ClickEvent custom(String action) {
        return ClickEvent.custom(Key.key("vprac", action), EMPTY_TAG);
    }

    private boolean requireLeader(Player player, Party party) {
        if (party == null || !party.leader().equals(player.getUniqueId())) {
            Texts.send(player, "Only your party leader can do that.");
            return false;
        }
        if (plugin.getPartyMatchManager().isPartyActive(party.id())) {
            Texts.send(player, "Your party is fighting; party settings are locked until the match ends.");
            return false;
        }
        return true;
    }

    /** Returns true when the click belongs to party UI and has been handled. */
    public boolean handleCustomClick(PlayerCustomClickEvent event, Player player) {
        String key = event.getIdentifier().value();
        if (!event.getIdentifier().namespace().equals("vprac") || !key.startsWith("party/")) return false;
        String action = key.substring("party/".length());

        if (action.equals("create")) {
            if (getParty(player.getUniqueId()) == null) createParty(player);
            openPartyMenu(player);
            return true;
        }
        if (action.equals("menu")) { openPartyMenu(player); return true; }
        if (action.equals("close")) { player.closeDialog(); return true; }
        if (action.equals("browse")) { openPartyBrowser(player); return true; }
        if (action.equals("members")) { openPartyMembersDialog(player); return true; }
        if (action.equals("kick-menu")) { openPartyKickDialog(player); return true; }
        if (action.equals("invite/open")) { openInviteDialog(player); return true; }
        if (action.equals("invite/back")) { openPartyMenu(player); return true; }
        if (action.equals("invite/send")) {
            String query = event.getDialogResponseView() == null ? null : event.getDialogResponseView().getText("party_player");
            openInviteSuggestions(player, query);
            return true;
        }
        if (action.startsWith("invite/select/")) {
            UUID targetId = parseUuid(action.substring("invite/select/".length()));
            Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                Texts.send(player, "&cThat player is no longer online.");
                openInviteDialog(player);
            } else {
                sendInvite(player, target.getName());
            }
            return true;
        }
        if (action.equals("toggle-open")) { toggleOpen(player); return true; }
        if (action.equals("announce")) { announce(player); return true; }
        if (action.equals("leave")) { leave(player); return true; }
        if (action.equals("disband")) { disbandParty(player); return true; }
        if (action.equals("fight/ffa")) { openFightKitDialog(player, PartyMatchManager.Mode.FFA); return true; }
        if (action.equals("fight/split")) { openFightKitDialog(player, PartyMatchManager.Mode.SPLIT); return true; }
        if (action.equals("max-players/save")) {
            Party party = getParty(player.getUniqueId());
            if (!requireLeader(player, party)) return true;
            Float selected = event.getDialogResponseView() == null ? null : event.getDialogResponseView().getFloat("party_max_players");
            if (selected == null) { openPartyMenu(player); return true; }
            int selectedLimit = Math.max(2, Math.round(selected));
            boolean unlimitedPermission = player.hasPermission("vprac.party.max-unlimited");
            if (!unlimitedPermission && selectedLimit > 35) {
                // The Dialog slider caps at 35 without permission; repeat the check server-side too.
                openPartyMenu(player);
                return true;
            }
            int max = unlimitedPermission && selectedLimit >= 150 ? Integer.MAX_VALUE : selectedLimit;
            if (max != Integer.MAX_VALUE && max < party.size()) {
                Texts.send(player, "&cYour party already has " + party.size() + " members. Remove members before lowering the limit.");
            } else {
                party.setMaxMembers(max);
                String display = party.maxMembersDisplay();
                Texts.send(player, plugin.getConfig().getString("party.messages.max-members-updated", "&aParty max players set to {max_members}.").replace("{max_members}", display));
            }
            openPartyMenu(player);
            return true;
        }
        if (action.startsWith("fight-select/")) {
            String[] parts = action.split("/", -1);
            if (parts.length == 3) {
                PartyMatchManager.Mode mode = parts[1].equalsIgnoreCase("ffa") ? PartyMatchManager.Mode.FFA
                        : parts[1].equalsIgnoreCase("split") ? PartyMatchManager.Mode.SPLIT : null;
                String kitId = parts[2].toLowerCase(Locale.ROOT);
                if (mode != null && plugin.getKitManager().exists(kitId)) startPartyFight(player, mode, kitId);
                else Texts.send(player, "That kit no longer exists.");
            }
            return true;
        }
        if (action.startsWith("transfer/")) {
            UUID target = parseUuid(action.substring("transfer/".length()));
            if (target != null) transferLeadership(player, target);
            return true;
        }
        if (action.startsWith("kick/")) {
            UUID target = parseUuid(action.substring("kick/".length()));
            if (target != null) kick(player, target);
            return true;
        }
        if (action.startsWith("kick-select/")) {
            UUID target = parseUuid(action.substring("kick-select/".length()));
            if (target != null) kick(player, target);
            return true;
        }
        if (action.startsWith("join/")) {
            UUID partyId = parseUuid(action.substring("join/".length()));
            if (partyId != null) joinOpenParty(player, partyId);
            return true;
        }
        return true;
    }

    private void openFightKitDialog(Player player, PartyMatchManager.Mode mode) {
        Party party = getParty(player.getUniqueId());
        if (!requireLeader(player, party)) return;
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Choose a kit for this party fight. Click a kit name.", NamedTextColor.GRAY), 360));
        int visibleKitCount = 0;
        for (String kitId : plugin.getKitManager().getKitIds()) {
            // Bedfight is a team mode, not a Party FFA kit. Split may still use a Bedfight arena.
            if (mode == PartyMatchManager.Mode.FFA && isBedfightKit(kitId)) continue;
            String displayName = plugin.getKitManager().getDisplayName(kitId);
            Component row = Component.text(displayName, NamedTextColor.AQUA)
                    .clickEvent(custom("party/fight-select/" + (mode == PartyMatchManager.Mode.FFA ? "ffa" : "split") + "/" + kitId))
                    .hoverEvent(HoverEvent.showText(Component.text("Start " + (mode == PartyMatchManager.Mode.FFA ? "FFA" : "Split") + " with " + displayName, NamedTextColor.GRAY)));
            body.add(DialogBody.item(new org.bukkit.inventory.ItemStack(plugin.getKitManager().getIconMaterial(kitId)),
                    DialogBody.plainMessage(row, 330), true, true, 24, 24));
            visibleKitCount++;
        }
        if (visibleKitCount == 0) {
            body.add(DialogBody.plainMessage(Component.text(
                    mode == PartyMatchManager.Mode.FFA
                            ? "No eligible non-Bedfight kits are configured for Party FFA."
                            : "No kits are configured for Party Split.", NamedTextColor.RED), 360));
        }
        showDialog(player, mode == PartyMatchManager.Mode.FFA ? "Party FFA Kit" : "Party Split Kit", body,
                List.of(button("Back to Party", "party/menu")), closeButton("Close", "party/close"), 1, List.of());
    }

    private void startPartyFight(Player player, PartyMatchManager.Mode mode, String kitId) {
        Party party = getParty(player.getUniqueId());
        if (!requireLeader(player, party)) return;
        if (!plugin.getKitManager().exists(kitId)) { Texts.send(player, "That kit no longer exists."); return; }
        if (mode == PartyMatchManager.Mode.FFA && isBedfightKit(kitId)) {
            Texts.send(player, "Bedfight kits cannot be used in Party FFA. Choose another kit.");
            openFightKitDialog(player, mode);
            return;
        }
        party.setSelectedKit(kitId);
        plugin.getPartyMatchManager().startPartyFight(player, party, mode, kitId);
    }

    /** Matches configured kit IDs or display names such as "bedfight", "Bed Fight" or "bed-fight". */
    private boolean isBedfightKit(String kitId) {
        if (kitId == null || kitId.isBlank()) return false;
        String normalizedId = normalizeKitLabel(kitId);
        String displayName = plugin.getKitManager().getDisplayName(kitId);
        return normalizedId.contains("bedfight") || normalizeKitLabel(displayName).contains("bedfight");
    }

    private String normalizeKitLabel(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Sends a message only to online members of the player's current party. */
    public void sendPartyChat(Player sender, String message) {
        Party party = sender == null ? null : getParty(sender.getUniqueId());
        if (party == null) {
            if (sender != null) Texts.send(sender, plugin.getConfig().getString("party.messages.not-in-party", "&cYou are not in a party"));
            return;
        }
        String format = plugin.getConfig().getString("party.messages.chat-format", "&8[&cParty&8]&c{player} &8» &f{message}");
        format = format.replace("{player}", sender.getName()).replace("{message}", message == null ? "" : message);
        Component rendered = Texts.component(format);
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) member.sendMessage(rendered);
        }
    }

    public void sendInvite(Player inviter, String targetName) {
        Party party = getParty(inviter.getUniqueId());
        if (!requireLeader(inviter, party)) { openPartyMenu(inviter); return; }
        if (targetName == null || targetName.isBlank()) {
            Texts.send(inviter, "Enter a player name first.");
            openInviteDialog(inviter);
            return;
        }
        if (party.isFull()) {
            Texts.send(inviter, plugin.getConfig().getString("party.messages.party-full", "&cYour party is full ({max_members} members maximum).")
                    .replace("{max_members}", party.maxMembersDisplay()));
            openPartyMenu(inviter);
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName.trim());
        if (target == null || !target.isOnline()) {
            Texts.send(inviter, "That player is not online. Use the exact in-game name.");
            openInviteDialog(inviter);
            return;
        }
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            Texts.send(inviter, "You cannot invite yourself.");
            openInviteDialog(inviter);
            return;
        }
        if (plugin.getPlayerPreferencesManager() != null
                && !plugin.getPlayerPreferencesManager().arePartyInvitesAllowed(target.getUniqueId())) {
            Texts.send(inviter, "That player has disabled party invites.");
            openInviteDialog(inviter);
            return;
        }
        if (getParty(target.getUniqueId()) != null) {
            Texts.send(inviter, "That player is already in a party.");
            openInviteDialog(inviter);
            return;
        }
        if (plugin.getQueueManager().isInMatch(target.getUniqueId())) {
            Texts.send(inviter, "That player is currently in a match.");
            openInviteDialog(inviter);
            return;
        }
        long now = System.currentTimeMillis();
        String cooldownKey = inviteCooldownKey(inviter.getUniqueId(), target.getUniqueId());
        inviteCooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        long cooldownUntil = inviteCooldownUntil.getOrDefault(cooldownKey, 0L);
        if (cooldownUntil > now) {
            long remainingSeconds = Math.max(1L, (cooldownUntil - now + 999L) / 1000L);
            Texts.send(inviter, "&cYou must wait " + remainingSeconds + "s before inviting that same player again.");
            openInviteSuggestions(inviter, targetName);
            return;
        }
        List<Invite> invites = invitesByTarget.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>());
        invites.removeIf(invite -> invite.partyId().equals(party.id()) || invite.expiresAt() < now);
        invites.add(new Invite(party.id(), inviter.getUniqueId(), now + INVITE_TTL_MILLIS));
        inviteCooldownUntil.put(cooldownKey, now + INVITE_COOLDOWN_MILLIS);
        Texts.send(inviter, "Invitation sent to &b" + target.getName() + "&r.");
        Component message = Component.text(inviter.getName() + " invited you to join their party. ", NamedTextColor.GRAY)
                .append(Component.text("[ACCEPT]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/vparty accept"))
                        .hoverEvent(HoverEvent.showText(Component.text("Accept the latest valid party invite"))))
                .append(Component.text(" "))
                .append(Component.text("[DECLINE]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/vparty decline"))
                        .hoverEvent(HoverEvent.showText(Component.text("Decline the latest valid party invite"))));
        target.sendMessage(message);
        // Party invitations and their acceptance are chat-only. Close any stale party Dialog
        // left on either client so neither the inviter nor the target gets a pop-up on accept.
        inviter.closeDialog();
        target.closeDialog();
    }

    /** Removes incoming invitations when a player disables party invites. */
    public void cancelInvitesForTarget(UUID targetId) {
        if (targetId != null) invitesByTarget.remove(targetId);
    }

    public void acceptInvite(Player player) {
        if (plugin.getPlayerPreferencesManager() != null
                && !plugin.getPlayerPreferencesManager().arePartyInvitesAllowed(player.getUniqueId())) {
            invitesByTarget.remove(player.getUniqueId());
            Texts.send(player, "Enable Party Invites in your settings before accepting invitations.");
            return;
        }
        if (getParty(player.getUniqueId()) != null) { Texts.send(player, "Leave your current party before accepting another invite."); return; }
        if (plugin.getQueueManager().isInMatch(player.getUniqueId())) { Texts.send(player, "You cannot accept a party invite during a match."); return; }
        List<Invite> invites = invitesByTarget.get(player.getUniqueId());
        if (invites == null || invites.isEmpty()) { Texts.send(player, "You don't have a party invitation."); return; }
        long now = System.currentTimeMillis();
        invites.removeIf(invite -> invite.expiresAt() < now || !parties.containsKey(invite.partyId()));
        if (invites.isEmpty()) { invitesByTarget.remove(player.getUniqueId()); Texts.send(player, "Your party invitation expired."); return; }
        Invite invite = invites.remove(invites.size() - 1);
        if (invites.isEmpty()) invitesByTarget.remove(player.getUniqueId());
        Party party = parties.get(invite.partyId());
        if (party == null || party.isFull() || plugin.getPartyMatchManager().isPartyActive(party.id())) {
            Texts.send(player, "That party is no longer available or is full.");
            return;
        }
        joinParty(player, party, true);
        if (getParty(player.getUniqueId()) == party) {
            player.closeDialog();
            Player inviter = Bukkit.getPlayer(invite.inviter());
            if (inviter != null && inviter.isOnline()) inviter.closeDialog();
        }
    }

    public void declineInvite(Player player) {
        List<Invite> invites = invitesByTarget.get(player.getUniqueId());
        if (invites == null || invites.isEmpty()) { Texts.send(player, "You don't have a party invitation."); return; }
        invites.removeIf(invite -> invite.expiresAt() < System.currentTimeMillis() || !parties.containsKey(invite.partyId()));
        if (invites.isEmpty()) { invitesByTarget.remove(player.getUniqueId()); Texts.send(player, "No valid party invitation remains."); return; }
        Invite declined = invites.remove(invites.size() - 1);
        if (invites.isEmpty()) invitesByTarget.remove(player.getUniqueId());
        Texts.send(player, "Party invitation declined.");
        Player inviter = Bukkit.getPlayer(declined.inviter());
        if (inviter != null && inviter.isOnline()) {
            Texts.send(inviter, "&cYour party invitation was declined.");
        }
    }

    public void joinOpenParty(Player player, UUID partyId) {
        Party party = parties.get(partyId);
        if (party == null || !party.open() || party.isFull() || plugin.getPartyMatchManager().isPartyActive(partyId)) {
            Texts.send(player, "That party is not available to join.");
            if (getParty(player.getUniqueId()) != null) openPartyMenu(player); else openPartyBrowser(player);
            return;
        }
        if (getParty(player.getUniqueId()) != null) {
            Texts.send(player, "Leave your current party before joining another one.");
            openPartyMenu(player);
            return;
        }
        if (plugin.getQueueManager().isInMatch(player.getUniqueId())) {
            Texts.send(player, "You cannot join a party during a match.");
            return;
        }
        joinParty(player, party, false);
    }

    private void joinParty(Player player, Party party, boolean viaInvite) {
        Set<UUID> kickedParties = kickedFromParties.get(player.getUniqueId());
        if (!viaInvite && kickedParties != null && kickedParties.contains(party.id())) {
            Texts.send(player, "&cYou were kicked from this party. You need a new invitation to rejoin.");
            return;
        }
        if (viaInvite && kickedParties != null) {
            kickedParties.remove(party.id());
            if (kickedParties.isEmpty()) kickedFromParties.remove(player.getUniqueId());
        }
        if (party.isFull()) { Texts.send(player, "That party is full."); return; }
        plugin.getQueueManager().removeFromAllQueues(player.getUniqueId());
        party.add(player.getUniqueId());
        partyByMember.put(player.getUniqueId(), party);
        plugin.removeQueueItem(player);
        broadcastParty(party, Component.text(player.getName() + " joined the party.", NamedTextColor.GREEN));
        // Invite acceptance should be a chat-only action. Keep the browser join flow convenient
        // by opening the Party Dialog only for open-party joins, not invite acceptance.
        if (!viaInvite) openPartyMenu(player);
        Player leader = Bukkit.getPlayer(party.leader());
        if (leader != null && !leader.getUniqueId().equals(player.getUniqueId())) Texts.send(leader, player.getName() + " joined your party.");
    }

    /** Removes an eliminated party-fight spectator without changing the active match participants.
     * The caller is responsible for returning the spectator to the lobby afterwards.
     */
    public void leaveAsSpectator(Player player) {
        Party party = getParty(player.getUniqueId());
        if (party == null) return;
        if (!plugin.getPartyMatchManager().isEliminatedSpectator(player.getUniqueId())) return;
        removeMember(party, player.getUniqueId(), "left the party as a spectator.");
    }

    public void leave(Player player) {
        Party party = getParty(player.getUniqueId());
        if (party == null) { Texts.send(player, "You are not in a party."); return; }
        if (plugin.getPartyMatchManager().isPartyActive(party.id())) { Texts.send(player, "You cannot leave a party while it is fighting."); return; }
        removeMember(party, player.getUniqueId(), "left the party.");
        plugin.giveQueueItem(player, false);
        openNoPartyDialog(player);
    }

    public void kick(Player actor, UUID targetId) {
        Party party = getParty(actor.getUniqueId());
        if (!requireLeader(actor, party)) return;
        if (targetId.equals(actor.getUniqueId()) || !party.contains(targetId)) { Texts.send(actor, "That player is not a kickable party member."); openPartyMenu(actor); return; }
        if (plugin.getPartyMatchManager().isPartyActive(party.id())) { Texts.send(actor, "You cannot manage members while your party is fighting."); return; }
        Player target = Bukkit.getPlayer(targetId);
        kickedFromParties.computeIfAbsent(targetId, ignored -> new HashSet<>()).add(party.id());
        removeMember(party, targetId, "was removed from the party.");
        if (target != null) {
            plugin.giveQueueItem(target, false);
            Texts.send(target, "You were removed from the party.");
            openNoPartyDialog(target);
        }
        Texts.send(actor, "Party member removed.");
        openPartyMenu(actor);
    }

    public void transferLeadership(Player actor, UUID targetId) {
        Party party = getParty(actor.getUniqueId());
        if (!requireLeader(actor, party)) return;
        if (!party.contains(targetId) || targetId.equals(actor.getUniqueId())) { Texts.send(actor, "Choose another current party member."); openPartyMenu(actor); return; }
        party.setLeader(targetId);
        Player target = Bukkit.getPlayer(targetId);
        if (target != null) Texts.send(target, "You are now the party leader.");
        broadcastParty(party, Component.text((target == null ? "A member" : target.getName()) + " is now the party leader.", NamedTextColor.GOLD));
        openPartyMenu(actor);
        if (target != null) openPartyMenu(target);
    }

    public void toggleOpen(Player actor) {
        Party party = getParty(actor.getUniqueId());
        if (!requireLeader(actor, party)) { openPartyMenu(actor); return; }
        party.setOpen(!party.open());
        Texts.send(actor, "Party is now " + (party.open() ? "OPEN" : "CLOSED") + ".");
        openPartyMenu(actor);
    }

    public void announce(Player actor) {
        Party party = getParty(actor.getUniqueId());
        if (!requireLeader(actor, party)) { openPartyMenu(actor); return; }
        if (!actor.hasPermission("vprac.party.announce")) {
            // The rank requirement is shown on the clickable item's hover label; do not spam chat.
            openPartyMenu(actor);
            return;
        }
        if (!party.open()) {
            Texts.send(actor, "Open your party before announcing it.");
            openPartyMenu(actor);
            return;
        }
        String id = compact(party.id());
        Component announcement = Component.text("[Party] ", NamedTextColor.AQUA)
                .append(Component.text(actor.getName() + " is recruiting! " + party.size() + "/" + party.maxMembersDisplay() + " members. ", NamedTextColor.GRAY))
                .append(Component.text("[CLICK TO JOIN]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/vparty join " + id))
                        .hoverEvent(HoverEvent.showText(Component.text("Join this open party"))));
        Bukkit.broadcast(announcement);
        openPartyMenu(actor);
    }

    public void handleQuit(UUID uuid) {
        inviteCooldownUntil.keySet().removeIf(key -> key.startsWith(uuid + ":") || key.endsWith(":" + uuid));
        invitesByTarget.remove(uuid);
        invitesByTarget.values().forEach(list -> list.removeIf(invite -> invite.inviter().equals(uuid)));
        invitesByTarget.values().removeIf(List::isEmpty);
        Party party = getParty(uuid);
        if (party == null) return;
        removeMember(party, uuid, "disconnected from the party.");
    }

    /** Disbands the full party; only the current leader can request this action. */
    public void disbandParty(Player actor) {
        Party party = getParty(actor.getUniqueId());
        if (!requireLeader(actor, party)) { openPartyMenu(actor); return; }
        if (plugin.getPartyMatchManager().isPartyActive(party.id())) {
            Texts.send(actor, "You cannot disband a party while it is fighting.");
            return;
        }

        List<UUID> members = new ArrayList<>(party.members());
        broadcastParty(party, Component.text("The party was disbanded by " + actor.getName() + ".", NamedTextColor.RED));
        parties.remove(party.id());
        for (UUID memberId : members) {
            partyByMember.remove(memberId);
            Player online = Bukkit.getPlayer(memberId);
            if (online != null && online.isOnline()) {
                plugin.giveQueueItem(online, false);
                if (!memberId.equals(actor.getUniqueId())) Texts.send(online, "Your party was disbanded.");
            }
        }
        cleanupPartyInvites(party.id());
        openNoPartyDialog(actor);
    }

    private void removeMember(Party party, UUID uuid, String reason) {
        boolean wasLeader = party.leader().equals(uuid);
        Player removed = Bukkit.getPlayer(uuid);
        String name = removed != null ? removed.getName() : Bukkit.getOfflinePlayer(uuid).getName();
        if (name == null) name = uuid.toString().substring(0, 8);

        party.remove(uuid);
        partyByMember.remove(uuid);

        if (party.size() == 0) {
            parties.remove(party.id());
            cleanupPartyInvites(party.id());
            if (removed != null && removed.isOnline()) Texts.send(removed, "Your party was disbanded because no members remained.");
            return;
        }

        if (wasLeader) {
            List<UUID> candidates = new ArrayList<>(party.members());
            UUID replacement = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            party.setLeader(replacement);
            Player newLeader = Bukkit.getPlayer(replacement);
            String newLeaderName = newLeader != null ? newLeader.getName() : Bukkit.getOfflinePlayer(replacement).getName();
            if (newLeaderName == null || newLeaderName.isBlank()) newLeaderName = replacement.toString().substring(0, 8);
            if (reason.contains("disconnected from the party")) {
                broadcastParty(party, Texts.component("&9" + newLeaderName + " &6is The new party leader!"));
            } else if (newLeader != null && newLeader.isOnline()) {
                Texts.send(newLeader, "You became party leader because the previous leader " + reason);
            }
        }

        broadcastParty(party, Component.text(name + " " + reason, NamedTextColor.GRAY));
    }

    private void cleanupPartyInvites(UUID partyId) {
        invitesByTarget.values().forEach(list -> list.removeIf(invite -> invite.partyId().equals(partyId)));
        invitesByTarget.values().removeIf(List::isEmpty);
        kickedFromParties.values().forEach(set -> set.remove(partyId));
        kickedFromParties.values().removeIf(Set::isEmpty);
    }

    private static String inviteCooldownKey(UUID inviter, UUID target) {
        return inviter + ":" + target;
    }

    private void broadcastParty(Party party, Component message) {
        for (UUID member : party.members()) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) online.sendMessage(message);
        }
    }

    public boolean handleCommand(Player player, String[] args) {
        if (args.length == 0) { openForPlayer(player); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> acceptInvite(player);
            case "decline" -> declineInvite(player);
            case "leave" -> leave(player);
            case "join" -> {
                if (args.length != 2) { Texts.send(player, "Usage: /vparty join <party-id>"); return true; }
                UUID partyId = parseUuid(args[1]);
                if (partyId == null) Texts.send(player, "Invalid party link."); else joinOpenParty(player, partyId);
            }
            case "menu" -> openPartyMenu(player);
            default -> Texts.send(player, "Usage: /vparty <menu|accept|decline|leave|join <party-id>>");
        }
        return true;
    }

    private static String compact(UUID uuid) { return uuid.toString().replace("-", ""); }

    private static UUID parseUuid(String text) {
        try {
            if (text == null) return null;
            if (text.length() == 32) text = text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16)
                    + "-" + text.substring(16, 20) + "-" + text.substring(20);
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) { return null; }
    }

    // Plain data holder; avoid record bytecode so Shadow's ASM processing is not forced
    // through record-specific generated methods during relocation.
    private static final class Invite {
        private final UUID partyId;
        private final UUID inviter;
        private final long expiresAt;

        private Invite(UUID partyId, UUID inviter, long expiresAt) {
            this.partyId = partyId;
            this.inviter = inviter;
            this.expiresAt = expiresAt;
        }

        private UUID partyId() { return partyId; }
        private UUID inviter() { return inviter; }
        private long expiresAt() { return expiresAt; }
    }
}
