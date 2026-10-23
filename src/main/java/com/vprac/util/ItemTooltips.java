package com.vprac.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Small helpers for changing tooltip presentation without changing combat attributes. */
public final class ItemTooltips {
    private ItemTooltips() {}

    /** Hides implicit weapon/armor attribute lines on this item stack while preserving its stats. */
    public static ItemStack hideAttributes(ItemStack item) {
        if (item == null || item.getType().isAir()) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Legacy-compatible flag. On 1.21.11 the component below is also needed for
            // implicit default modifiers such as a sword's attack damage/speed or armor value.
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }

        TooltipDisplay current = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        TooltipDisplay.Builder builder = TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (current != null) {
            builder.hideTooltip(current.hideTooltip());
            for (var component : current.hiddenComponents()) {
                builder.addHiddenComponents(component);
            }
        }
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, builder.build());
        return item;
    }
}
