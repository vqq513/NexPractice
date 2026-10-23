package com.vprac.duel;

import com.vprac.VpracPlugin;
import com.vprac.util.Texts;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Direct duel requests using the same native, no-client-mod Dialog click system as the queue menu. */
public final class DuelManager {
    private static final long REQUEST_LIFETIME_MILLIS = 60_000L;
    private static final long REQUEST_LIFETIME_TICKS = 20L * 60L;
    private static final BinaryTagHolder EMPTY_TAG = BinaryTagHolder.binaryTagHolder("{}");

    private final VpracPlugin plugin;
    /** At most one pending duel request per target, to keep the accept/decline UI unambiguous. */
    private final Map<UUID, DuelRequest> requestsByTarget = new HashMap<>();

    public DuelManager(VpracPlugin plugin) { this.plugin = plugin; }

    public void openKitSelectionMenu(Player requester, Player target) {
        if (requester == null || target == null || !requester.isOnline() || !target.isOnline()) return;
        if (!validateCanChallenge(requester, target, true)) return;
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "Choose a kit. Click its aqua name to challenge " + target.getName() + ".", NamedTextColor.GRAY), 360));
        for (String kitId : plugin.getKitManager().getKitIds()) {
            Component clickableName = Component.text(plugin.getKitManager().getDisplayName(kitId) + " Duel", NamedTextColor.AQUA)
                    .clickEvent(custom("duel/send/" + target.getUniqueId() + "/" + kitId))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Send " + target.getName() + " a " + plugin.getKitManager().getDisplayName(kitId) + " duel request.", NamedTextColor.GRAY)));
            Component row = clickableName.append(Component.text("  ·  click to challenge", NamedTextColor.GRAY));
            ItemStack icon = new ItemStack(plugin.getKitManager().getIconMaterial(kitId));
            body.add(DialogBody.item(icon, DialogBody.plainMessage(row, 340), true, true, 24, 24));
        }
        if (plugin.getKitManager().getKitIds().isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No kits are configured yet.", NamedTextColor.RED), 360));
        }
        showDialog(requester, "Duel " + target.getName(), body);
    }

    /** Handles duel/send, duel/accept, and duel/deny body-text click events. */
    public boolean handleCustomClick(PlayerCustomClickEvent event, Player clicker) {
        Key key = event.getIdentifier();
        if (!key.namespace().equals("vprac") || !key.value().startsWith("duel/")) return false;
        String[] parts = key.value().split("/", -1);
        if (parts.length < 4) return true;

        String action = parts[1];
        if (action.equals("send")) {
            if (parts.length != 4) return true;
            UUID targetId;
            try { targetId = UUID.fromString(parts[2]); }
            catch (IllegalArgumentException exception) {
                Texts.send(clicker, "That duel action is invalid.");
                return true;
            }
            sendRequest(clicker, targetId, parts[3].toLowerCase(Locale.ROOT));
            return true;
        }

        if (action.equals("accept") || action.equals("deny")) {
            if (parts.length != 5) return true;
            UUID requestToken;
            UUID requesterId;
            try {
                requestToken = UUID.fromString(parts[2]);
                requesterId = UUID.fromString(parts[3]);
            } catch (IllegalArgumentException exception) {
                Texts.send(clicker, "That duel action is invalid.");
                return true;
            }
            String kitId = parts[4].toLowerCase(Locale.ROOT);
            if (action.equals("accept")) acceptRequest(clicker, requestToken, requesterId, kitId);
            else denyRequest(clicker, requestToken, requesterId, kitId);
        }
        return true;
    }

    private void sendRequest(Player requester, UUID targetId, String kitId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            Texts.send(requester, "That player is no longer online.");
            return;
        }
        if (!plugin.getKitManager().exists(kitId)) {
            Texts.send(requester, "That kit no longer exists.");
            return;
        }
        if (!validateCanChallenge(requester, target, true)) return;
        DuelRequest reciprocal = requestsByTarget.get(requester.getUniqueId());
        if (reciprocal != null && reciprocal.expiresAt() > System.currentTimeMillis()
                && reciprocal.requesterId().equals(targetId)) {
            Texts.send(requester, target.getName() + " has already challenged you. Accept or decline their existing request.");
            return;
        }
        DuelRequest existing = requestsByTarget.get(targetId);
        if (existing != null) {
            if (existing.expiresAt() > System.currentTimeMillis()) {
                Texts.send(requester, target.getName() + " already has a pending duel request.");
                return;
            }
            requestsByTarget.remove(targetId);
        }
        if (plugin.getPlayerPreferencesManager() != null
                && !plugin.getPlayerPreferencesManager().areDuelRequestsAllowed(targetId)) {
            Texts.send(requester, target.getName() + " has disabled duel requests.");
            return;
        }
        DuelRequest request = new DuelRequest(UUID.randomUUID(), requester.getUniqueId(), targetId, kitId,
                System.currentTimeMillis() + REQUEST_LIFETIME_MILLIS);
        requestsByTarget.put(targetId, request);
        Texts.send(requester, "Duel request sent to &b" + target.getName() + "&r for &b"
                + plugin.getKitManager().getDisplayName(kitId) + "&r.");
        showIncomingRequestChat(target, requester, kitId, request.token());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (requestsByTarget.get(targetId) != request) return;
            requestsByTarget.remove(targetId);
            Player onlineRequester = Bukkit.getPlayer(request.requesterId());
            Player onlineTarget = Bukkit.getPlayer(request.targetId());
            if (onlineRequester != null) Texts.send(onlineRequester, "Your duel request to &b" + target.getName() + "&r expired.");
            if (onlineTarget != null) Texts.send(onlineTarget, "The duel request expired.");
        }, REQUEST_LIFETIME_TICKS);
    }

    /** Handles the clickable chat commands /duel accept <token> and /duel deny <token>. */
    public boolean handleChatResponse(Player target, String action, String tokenText) {
        if (target == null || action == null || tokenText == null) return false;
        UUID token;
        try {
            token = UUID.fromString(tokenText);
        } catch (IllegalArgumentException exception) {
            Texts.send(target, "That duel request is invalid or expired.");
            return true;
        }
        DuelRequest request = requestsByTarget.get(target.getUniqueId());
        if (request == null || !request.token().equals(token)) {
            Texts.send(target, "That duel request is no longer available.");
            return true;
        }
        if (action.equalsIgnoreCase("accept")) {
            acceptRequest(target, request.token(), request.requesterId(), request.kitId());
        } else if (action.equalsIgnoreCase("deny")) {
            denyRequest(target, request.token(), request.requesterId(), request.kitId());
        } else {
            Texts.send(target, "Usage: /duel <accept|deny> <request-id>");
        }
        return true;
    }

    /** Cancels pending requests addressed to a player who disabled duel requests. */
    public void cancelRequestsTargeting(UUID targetId) {
        DuelRequest request = requestsByTarget.remove(targetId);
        if (request == null) return;
        Player requester = Bukkit.getPlayer(request.requesterId());
        Player target = Bukkit.getPlayer(targetId);
        if (requester != null && requester.isOnline()) {
            Texts.send(requester, "Your duel request was cancelled because the target disabled duel requests.");
        }
        if (target != null && target.isOnline()) Texts.send(target, "Pending duel request cancelled.");
    }

    private void showIncomingRequestChat(Player target, Player requester, String kitId, UUID requestToken) {
        Component header = Component.text(requester.getName() + " challenged you to a duel", NamedTextColor.GOLD)
                .append(Component.text(" (" + plugin.getKitManager().getDisplayName(kitId) + ")", NamedTextColor.AQUA));
        Component accept = Component.text("  [ACCEPT]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/duel accept " + requestToken))
                .hoverEvent(HoverEvent.showText(Component.text("Accept this duel request", NamedTextColor.GREEN)));
        Component deny = Component.text("  [DENY]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/duel deny " + requestToken))
                .hoverEvent(HoverEvent.showText(Component.text("Decline this duel request", NamedTextColor.RED)));
        target.sendMessage(header.append(accept).append(deny));
        target.sendMessage(Component.text("Click ACCEPT or DENY. This request expires in 60 seconds.", NamedTextColor.GRAY));
    }

    private void acceptRequest(Player target, UUID expectedToken, UUID expectedRequester, String expectedKit) {
        DuelRequest request = requestsByTarget.get(target.getUniqueId());
        if (!matches(request, expectedToken, expectedRequester, target.getUniqueId(), expectedKit)) {
            Texts.send(target, "That duel request has expired or is no longer available.");
            return;
        }
        requestsByTarget.remove(target.getUniqueId());
        if (request.expiresAt() <= System.currentTimeMillis()) {
            notifyExpired(request);
            return;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null || !requester.isOnline()) {
            Texts.send(target, "The challenger is no longer online.");
            return;
        }
        if (plugin.getPlayerPreferencesManager() != null
                && !plugin.getPlayerPreferencesManager().areDuelRequestsAllowed(target.getUniqueId())) {
            Texts.send(target, "You have disabled duel requests. Enable them in Settings first.");
            Texts.send(requester, target.getName() + " has disabled duel requests.");
            return;
        }
        if (!validateCanChallenge(requester, target, false)) {
            Texts.send(target, "This duel can no longer start because one of the players is busy.");
            Texts.send(requester, "This duel could not start because one of the players is busy.");
            return;
        }
        if (plugin.getQueueManager().startDuel(requester, target, request.kitId())) {
            Texts.send(target, "You accepted " + requester.getName() + "'s duel request.");
            Texts.send(requester, "&b" + target.getName() + "&r accepted your duel request.");
        }
    }

    private void denyRequest(Player target, UUID expectedToken, UUID expectedRequester, String expectedKit) {
        DuelRequest request = requestsByTarget.get(target.getUniqueId());
        if (!matches(request, expectedToken, expectedRequester, target.getUniqueId(), expectedKit)) {
            Texts.send(target, "That duel request has expired or is no longer available.");
            return;
        }
        requestsByTarget.remove(target.getUniqueId());
        if (request.expiresAt() <= System.currentTimeMillis()) {
            notifyExpired(request);
            return;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        Texts.send(target, "You declined the duel request.");
        if (requester != null && requester.isOnline()) Texts.send(requester, "&b" + target.getName() + "&r declined your duel request.");
    }

    private boolean matches(DuelRequest request, UUID token, UUID requester, UUID target, String kitId) {
        return request != null && request.token().equals(token) && request.requesterId().equals(requester)
                && request.targetId().equals(target) && request.kitId().equalsIgnoreCase(kitId);
    }

    private void notifyExpired(DuelRequest request) {
        Player requester = Bukkit.getPlayer(request.requesterId());
        Player target = Bukkit.getPlayer(request.targetId());
        if (requester != null) Texts.send(requester, "Your duel request expired.");
        if (target != null) Texts.send(target, "The duel request expired.");
    }

    private boolean validateCanChallenge(Player requester, Player target, boolean report) {
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            if (report) Texts.send(requester, "You cannot duel yourself.");
            return false;
        }
        if (report && plugin.getPlayerPreferencesManager() != null
                && !plugin.getPlayerPreferencesManager().areDuelRequestsAllowed(target.getUniqueId())) {
            Texts.send(requester, target.getName() + " has disabled duel requests.");
            return false;
        }
        if (plugin.getQueueManager().isInMatch(requester.getUniqueId())
                || plugin.getQueueManager().isInMatch(target.getUniqueId())) {
            if (report) Texts.send(requester, "Both players must be out of a match to duel.");
            return false;
        }
        if (plugin.getPartyManager().getParty(requester.getUniqueId()) != null
                || plugin.getPartyManager().getParty(target.getUniqueId()) != null) {
            if (report) Texts.send(requester, "Both players must leave their parties before dueling.");
            return false;
        }
        return true;
    }

    public void handleQuit(UUID uuid) {
        DuelRequest targeted = requestsByTarget.remove(uuid);
        if (targeted != null) {
            Player requester = Bukkit.getPlayer(targeted.requesterId());
            if (requester != null && requester.isOnline()) Texts.send(requester, "Your duel request was cancelled because the target disconnected.");
        }
        for (Map.Entry<UUID, DuelRequest> entry : new ArrayList<>(requestsByTarget.entrySet())) {
            DuelRequest request = entry.getValue();
            if (!request.requesterId().equals(uuid)) continue;
            requestsByTarget.remove(entry.getKey());
            Player target = Bukkit.getPlayer(request.targetId());
            if (target != null && target.isOnline()) Texts.send(target, "The duel request was cancelled because the challenger disconnected.");
        }
    }

    private void showDialog(Player player, String title, List<DialogBody> body) {
        ActionButton close = ActionButton.builder(Component.text("Close", NamedTextColor.GRAY)).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.AQUA))
                        .body(body).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE).build())
                .type(DialogType.notice(close)));
        player.showDialog(dialog);
    }

    private ClickEvent custom(String action) {
        return ClickEvent.custom(Key.key("vprac", action), EMPTY_TAG);
    }

    private static final class DuelRequest {
        private final UUID token;
        private final UUID requesterId;
        private final UUID targetId;
        private final String kitId;
        private final long expiresAt;
        private DuelRequest(UUID token, UUID requesterId, UUID targetId, String kitId, long expiresAt) {
            this.token = token;
            this.requesterId = requesterId;
            this.targetId = targetId;
            this.kitId = kitId;
            this.expiresAt = expiresAt;
        }
        private UUID token() { return token; }
        private UUID requesterId() { return requesterId; }
        private UUID targetId() { return targetId; }
        private String kitId() { return kitId; }
        private long expiresAt() { return expiresAt; }
    }
}
