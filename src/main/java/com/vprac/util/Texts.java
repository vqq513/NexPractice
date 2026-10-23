package com.vprac.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;

public final class Texts {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Texts() { }

    public static Component component(String text) {
        String safeText = text == null ? "" : text;
        // VPRAC text is intentionally non-bold, including values loaded from older configs.
        safeText = safeText.replaceAll("(?i)[&§]l", "");
        return nonItalic(LEGACY.deserialize(safeText));
    }

    public static String color(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component(text));
    }

    /** Converts menu lore text to a component with every node explicitly non-italic. */
    public static Component lore(String text) {
        return nonItalic(component(text));
    }

    /** Recursively disables italics so item lore never inherits Minecraft's default italic style. */
    public static Component nonItalic(Component component) {
        Component safe = component == null ? Component.empty() : component;
        List<Component> children = new ArrayList<>(safe.children().size());
        for (Component child : safe.children()) children.add(nonItalic(child));
        return safe.children(children).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Applies the non-italic rule to a complete lore list, including already-styled components. */
    public static List<Component> nonItalicLore(List<? extends Component> components) {
        if (components == null || components.isEmpty()) return List.of();
        List<Component> result = new ArrayList<>(components.size());
        for (Component component : components) result.add(nonItalic(component));
        return List.copyOf(result);
    }

    public static void send(CommandSender sender, String text) {
        sender.sendMessage(component(text == null ? "" : text));
    }
}
