package com.vprac.util;

import com.vprac.VpracPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Smooth left-to-right highlight sweep for round and match result titles. */
public final class TitleAnimations {
    private static final Map<UUID, BukkitTask> ACTIVE = new ConcurrentHashMap<>();
    private static final int FRAME_COUNT = 15;

    private TitleAnimations() { }

    public static void showSweep(VpracPlugin plugin, Player player, String text, Component subtitle,
                                 NamedTextColor baseColor, NamedTextColor glowColor, boolean bold) {
        showSweep(plugin, player, text, subtitle, baseColor, glowColor, bold, true);
    }

    /** Shows the same animated title while allowing result screens to suppress the default toast sound. */
    public static void showSweep(VpracPlugin plugin, Player player, String text, Component subtitle,
                                 NamedTextColor baseColor, NamedTextColor glowColor, boolean bold, boolean playSound) {
        if (plugin == null || player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        BukkitTask old = ACTIVE.remove(uuid);
        if (old != null) old.cancel();

        if (playSound) playCompletionSound(plugin, player);
        int[] codePoints = (text == null ? "" : text).codePoints().toArray();
        if (codePoints.length == 0) return;
        Component safeSubtitle = subtitle == null ? Component.empty() : subtitle;

        final class SweepTask implements Runnable {
            private int frame;
            private BukkitTask task;

            @Override
            public void run() {
                if (!player.isOnline() || !plugin.isEnabled()) {
                    stop();
                    return;
                }
                if (frame >= FRAME_COUNT) {
                    player.showTitle(Title.title(
                            build(codePoints, -1, baseColor, glowColor, bold), safeSubtitle,
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ZERO)));
                    stop();
                    return;
                }
                int cursor = codePoints.length <= 1 ? 0
                        : Math.round((float) frame * (codePoints.length - 1) / (FRAME_COUNT - 1));
                player.showTitle(Title.title(
                        build(codePoints, cursor, baseColor, glowColor, bold), safeSubtitle,
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(500), Duration.ZERO)));
                frame++;
            }

            private void stop() {
                if (task != null) task.cancel();
                ACTIVE.remove(uuid, task);
            }
        }

        SweepTask runnable = new SweepTask();
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, runnable, 0L, 2L);
        // Publish the task to the runnable and registry after scheduling it on the server thread.
        runnable.task = scheduled;
        ACTIVE.put(uuid, scheduled);
    }

    private static Component build(int[] codePoints, int cursor, TextColor baseColor,
                                   TextColor glowColor, boolean bold) {
        Component result = Component.empty();
        for (int i = 0; i < codePoints.length; i++) {
            boolean glowing = cursor >= 0 && Math.abs(i - cursor) <= 1;
            Component character = Component.text(new String(Character.toChars(codePoints[i])),
                    glowing ? glowColor : baseColor);
            if (bold) character = character.decorate(TextDecoration.BOLD);
            result = result.append(character);
        }
        return result;
    }

    private static void playCompletionSound(VpracPlugin plugin, Player player) {
        if (plugin.getPlayerPreferencesManager() != null) {
            plugin.getPlayerPreferencesManager().playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 2.0f);
        } else {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 2.0f);
        }
    }
}
