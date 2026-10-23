package com.vprac.ui;

import com.vprac.VpracPlugin;
import com.vprac.queue.Match;
import com.vprac.queue.PartyMatch;
import com.vprac.util.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent cosmetics and player-facing cosmetic chest menus. */
public final class CosmeticsManager implements Listener {
    private static final int[] COSMETIC_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
    private static final int[] TRIM_SLOTS = {28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43};
    private static final int TRIMS_PER_PAGE = TRIM_SLOTS.length;
    private static final int OPTIONS_BACK_SLOT = 40; // 5-row inventories have valid slots 0-44.

    private static final List<CosmeticOption> TRAILS = List.of(
            new CosmeticOption("flame", "Flame Trail", Material.BLAZE_POWDER, Particle.FLAME),
            new CosmeticOption("end_rod", "End Rod Trail", Material.END_ROD, Particle.END_ROD),
            new CosmeticOption("heart", "Heart Trail", Material.POPPY, Particle.HEART),
            new CosmeticOption("critical", "Critical Trail", Material.IRON_SWORD, Particle.CRIT),
            new CosmeticOption("enchant", "Enchantment Trail", Material.ENCHANTED_BOOK, Particle.ENCHANT),
            new CosmeticOption("soul_fire", "Soul Fire Trail", Material.SOUL_LANTERN, Particle.SOUL_FIRE_FLAME),
            new CosmeticOption("cloud", "Cloud Trail", Material.WHITE_WOOL, Particle.CLOUD),
            new CosmeticOption("dragon", "Dragon Breath Trail", Material.DRAGON_BREATH, Particle.DRAGON_BREATH),
            new CosmeticOption("totem", "Totem Trail", Material.TOTEM_OF_UNDYING, Particle.TOTEM_OF_UNDYING),
            new CosmeticOption("portal", "Portal Trail", Material.ENDER_PEARL, Particle.PORTAL)
    );

    private static final List<MessageOption> KILL_MESSAGES = List.of(
            new MessageOption("classic", "Classic", Material.PAPER, "&c{victim} &7was slain by &6{killer}&7."),
            new MessageOption("outplayed", "Outplayed", Material.FEATHER, "&6{killer} &7outplayed &c{victim}&7."),
            new MessageOption("comboed", "Comboed", Material.IRON_SWORD, "&c{victim} &7got comboed by &6{killer}&7."),
            new MessageOption("sent_home", "Sent Home", Material.ENDER_PEARL, "&c{victim} &7was sent back to spawn by &6{killer}&7."),
            new MessageOption("owned", "Owned", Material.GOLD_INGOT, "&6{killer} &7owned &c{victim}&7."),
            new MessageOption("clipped", "Clipped", Material.SHEARS, "&c{victim} &7got clipped by &6{killer}&7."),
            new MessageOption("no_chance", "No Chance", Material.SHIELD, "&c{victim} &7had no chance against &6{killer}&7."),
            new MessageOption("dominated", "Dominated", Material.GOLDEN_SWORD, "&6{killer} &7dominated &c{victim}&7."),
            new MessageOption("one_tap", "One-Tapped", Material.ARROW, "&c{victim} &7was one-tapped by &6{killer}&7."),
            new MessageOption("knocked_out", "Knocked Out", Material.IRON_AXE, "&c{victim} &7was knocked out by &6{killer}&7.")
    );

    private static final List<CosmeticOption> KILL_EFFECTS = List.of(
            new CosmeticOption("fire_burst", "Fire Burst", Material.FIRE_CHARGE, Particle.FLAME),
            new CosmeticOption("heart_burst", "Heart Burst", Material.POPPY, Particle.HEART),
            new CosmeticOption("spark_burst", "Spark Burst", Material.LIGHTNING_ROD, Particle.ELECTRIC_SPARK),
            new CosmeticOption("soul_release", "Soul Release", Material.SOUL_LANTERN, Particle.SOUL),
            new CosmeticOption("ender_pop", "Ender Pop", Material.ENDER_PEARL, Particle.PORTAL),
            new CosmeticOption("totem_burst", "Totem Burst", Material.TOTEM_OF_UNDYING, Particle.TOTEM_OF_UNDYING),
            new CosmeticOption("critical_burst", "Critical Burst", Material.IRON_SWORD, Particle.CRIT),
            new CosmeticOption("dragon_cloud", "Dragon Cloud", Material.DRAGON_BREATH, Particle.DRAGON_BREATH),
            new CosmeticOption("enchant_burst", "Enchantment Burst", Material.ENCHANTED_BOOK, Particle.ENCHANT),
            new CosmeticOption("cloud_puff", "Cloud Puff", Material.WHITE_WOOL, Particle.CLOUD)
    );

    private static final List<TrimOption> TRIMS = List.of(
            new TrimOption("sentry", "Sentry", Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SENTRY),
            new TrimOption("dune", "Dune", Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.DUNE),
            new TrimOption("coast", "Coast", Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.COAST),
            new TrimOption("wild", "Wild", Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.WILD),
            new TrimOption("ward", "Ward", Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.WARD),
            new TrimOption("eye", "Eye", Material.EYE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.EYE),
            new TrimOption("vex", "Vex", Material.VEX_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.VEX),
            new TrimOption("tide", "Tide", Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.TIDE),
            new TrimOption("snout", "Snout", Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SNOUT),
            new TrimOption("rib", "Rib", Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.RIB),
            new TrimOption("spire", "Spire", Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SPIRE),
            new TrimOption("silence", "Silence", Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SILENCE),
            new TrimOption("wayfinder", "Wayfinder", Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.WAYFINDER),
            new TrimOption("shaper", "Shaper", Material.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.SHAPER),
            new TrimOption("host", "Host", Material.HOST_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.HOST),
            new TrimOption("raiser", "Raiser", Material.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.RAISER),
            new TrimOption("flow", "Flow", Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.FLOW),
            new TrimOption("bolt", "Bolt", Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, TrimPattern.BOLT)
    );

    private final VpracPlugin plugin;
    private final File file;
    private YamlConfiguration data;
    private List<CosmeticOption> trails = TRAILS;
    private List<MessageOption> killMessages = KILL_MESSAGES;
    private List<CosmeticOption> killEffects = KILL_EFFECTS;
    private List<TrimOption> trims = TRIMS;

    public CosmeticsManager(VpracPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cosmetics.yml");
        load();
        migrateExistingSelectionsToUnlocked();
        reloadOptions();
    }

    private void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not create cosmetics.yml: " + exception.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
    }

    public void openMenu(Player player) {
        if (player == null || !player.isOnline() || plugin.getQueueManager().isInMatch(player.getUniqueId())) return;
        CosmeticsMenuHolder holder = new CosmeticsMenuHolder(player.getUniqueId(), CosmeticsMenuHolder.Type.CATEGORY, 0);
        Inventory inventory = Bukkit.createInventory(holder, 45, Texts.component("&8Cosmetics"));
        holder.setInventory(inventory);
        fill(inventory, 45);
        inventory.setItem(10, categoryItem("projectile-trails", Material.BOW, "&eProjectile Trails", "&7Particle effects that follow projectiles that you throw."));
        inventory.setItem(13, categoryItem("kill-messages", Material.ANVIL, "&eKill Messages", "&7Make every kill message your own."));
        inventory.setItem(16, categoryItem("kill-effects", Material.DIAMOND_SWORD, "&eKill Effects", "&7Particle effects that play when you kill other players."));
        inventory.setItem(31, categoryItem("armor-trims", Material.DIAMOND_CHESTPLATE, "&eArmor Trims", "&7Show off your armor with fresh and stylish trims."));
        inventory.setItem(40, menuItem(Material.ARROW, "&eBack to Practice Menu", List.of()));
        player.openInventory(inventory);
    }

    private void openOptions(Player player, CosmeticsMenuHolder.Type type) {
        if (type == CosmeticsMenuHolder.Type.ARMOR_TRIMS || type == CosmeticsMenuHolder.Type.CATEGORY) return;
        UUID uuid = player.getUniqueId();
        CosmeticsMenuHolder holder = new CosmeticsMenuHolder(uuid, type, 0);
        String title = switch (type) {
            case PROJECTILE_TRAILS -> categoryTitle("projectile-trails", "&8Projectile Trails");
            case KILL_MESSAGES -> categoryTitle("kill-messages", "&8Kill Messages");
            case KILL_EFFECTS -> categoryTitle("kill-effects", "&8Kill Effects");
            default -> "&8Cosmetics";
        };
        Inventory inventory = Bukkit.createInventory(holder, 45, Texts.component(title));
        holder.setInventory(inventory);
        fillOutline(inventory, 45);
        String selected = selected(player.getUniqueId(), sectionFor(type));
        if (selected == null) selected = type == CosmeticsMenuHolder.Type.KILL_MESSAGES ? "classic" : "none";
        inventory.setItem(4, menuItem(Material.PAPER, displayCategory(type), List.of(
                "", "&7Current: &f" + displaySelection(type, selected), "&7Select an item below to equip it."
        )));

        List<CosmeticOption> options = type == CosmeticsMenuHolder.Type.PROJECTILE_TRAILS
                ? trails : type == CosmeticsMenuHolder.Type.KILL_EFFECTS ? killEffects : null;
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                CosmeticOption option = options.get(i);
                int slot = COSMETIC_SLOTS[i];
                List<String> lore = new ArrayList<>();
                lore.add("");
                addDescription(lore, option.description());
                lore.add("");
                lore.add("&6" + cosmeticPrice(type, option.id()) + " ⛃");
                lore.add(!isUnlocked(uuid, type, option.id()) ? "&7Click to purchase"
                        : option.id().equals(selected) ? "&aSelected" : "&7Click to equip");
                inventory.setItem(slot, menuItem(option.icon(), "&e" + option.name(), lore));
                holder.mapOption(slot, option.id());
            }
        } else {
            for (int i = 0; i < killMessages.size(); i++) {
                MessageOption option = killMessages.get(i);
                int slot = COSMETIC_SLOTS[i];
                List<String> lore = new ArrayList<>();
                lore.add("");
                addDescription(lore, option.description());
                lore.add("");
                lore.add("&6" + cosmeticPrice(type, option.id()) + " ⛃");
                lore.add(!isUnlocked(uuid, type, option.id()) ? "&7Click to purchase"
                        : option.id().equals(selected) ? "&aSelected" : "&7Click to equip");
                inventory.setItem(slot, menuItem(option.icon(), "&e" + option.name(), lore));
                holder.mapOption(slot, option.id());
            }
        }
        inventory.setItem(OPTIONS_BACK_SLOT, menuItem(Material.ARROW, "&eBack", List.of()));
        player.openInventory(inventory);
    }

    private void openArmorTrims(Player player, int requestedPage) {
        int pageCount = Math.max(1, (trims.size() + TRIMS_PER_PAGE - 1) / TRIMS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        CosmeticsMenuHolder holder = new CosmeticsMenuHolder(player.getUniqueId(), CosmeticsMenuHolder.Type.ARMOR_TRIMS, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Texts.component("&8Armor Trims"));
        holder.setInventory(inventory);
        fill(inventory, 54);

        // These four equipment icons are preview-only. A selected pattern applies to all four pieces.
        inventory.setItem(10, armorPreview(Material.DIAMOND_HELMET, "Helmet", player.getUniqueId()));
        inventory.setItem(12, armorPreview(Material.DIAMOND_CHESTPLATE, "Chestplate", player.getUniqueId()));
        inventory.setItem(14, armorPreview(Material.DIAMOND_LEGGINGS, "Leggings", player.getUniqueId()));
        inventory.setItem(16, armorPreview(Material.DIAMOND_BOOTS, "Boots", player.getUniqueId()));
        int start = page * TRIMS_PER_PAGE;
        int end = Math.min(trims.size(), start + TRIMS_PER_PAGE);
        for (int i = start; i < end; i++) {
            TrimOption option = trims.get(i);
            int slot = TRIM_SLOTS[i - start];
            String current = selectedTrim(player.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("");
            addDescription(lore, option.description());
            lore.add("");
            lore.add("&6" + cosmeticPrice(CosmeticsMenuHolder.Type.ARMOR_TRIMS, option.id()) + " ⛃");
            lore.add(!isUnlocked(player.getUniqueId(), CosmeticsMenuHolder.Type.ARMOR_TRIMS, option.id())
                    ? "&7Click to purchase" : option.id().equals(current) ? "&aApplied to all armor" : "&7Click to apply to all armor pieces");
            inventory.setItem(slot, menuItem(option.icon(), "&e" + option.name() + " Trim", lore));
            holder.mapOption(slot, option.id());
        }
        if (page > 0) inventory.setItem(48, menuItem(Material.ARROW, "&ePrevious Page", List.of()));
        inventory.setItem(49, menuItem(Material.PAPER, "&7Page &f" + (page + 1) + "&7/&f" + pageCount, List.of()));
        if (page + 1 < pageCount) inventory.setItem(50, menuItem(Material.ARROW, "&eNext Page", List.of()));
        inventory.setItem(53, menuItem(Material.ARROW, "&eBack", List.of("&7Return to Cosmetics.")));
        player.openInventory(inventory);
    }

    private ItemStack armorPreview(Material material, String pieceName, UUID uuid) {
        ItemStack item = new ItemStack(material);
        String current = selectedTrim(uuid);
        TrimOption option = trimById(current);
        ItemMeta rawMeta = item.getItemMeta();
        if (rawMeta instanceof ArmorMeta armorMeta && option != null) {
            armorMeta.setTrim(new ArmorTrim(option.material(), option.pattern()));
            item.setItemMeta(armorMeta);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.component("&e" + pieceName));
        String label = option == null ? "None" : option.name() + " (" + option.material().getKey().getKey().toUpperCase(java.util.Locale.ROOT) + ")";
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Trim: &f" + label);
        lore.add("&7Preview only");
        lore.add("&7Selected patterns apply to all armor pieces.");
        meta.lore(Texts.nonItalicLore(lore.stream().map(Texts::component).toList()));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CosmeticsMenuHolder holder)) return;
        event.setCancelled(true);
        if (!holder.viewerId().equals(player.getUniqueId()) || event.getClickedInventory() != top
                || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) handleClick(player, holder, slot);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CosmeticsMenuHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Cosmetic choices are persisted on every change; this is a final safety flush.
        saveQuietly();
    }

    private void handleClick(Player player, CosmeticsMenuHolder holder, int slot) {
        UUID uuid = player.getUniqueId();
        switch (holder.type()) {
            case CATEGORY -> {
                if (slot == 40) { plugin.getPracticeMenuManager().openPracticeMenu(player); return; }
                if (slot == 10) openOptions(player, CosmeticsMenuHolder.Type.PROJECTILE_TRAILS);
                else if (slot == 13) openOptions(player, CosmeticsMenuHolder.Type.KILL_MESSAGES);
                else if (slot == 16) openOptions(player, CosmeticsMenuHolder.Type.KILL_EFFECTS);
                else if (slot == 31) openArmorTrims(player, 0);
            }
            case PROJECTILE_TRAILS, KILL_MESSAGES, KILL_EFFECTS -> {
                if (slot == OPTIONS_BACK_SLOT) { openMenu(player); return; }
                String optionId = holder.optionAt(slot);
                if (optionId == null) return;
                String section = sectionFor(holder.type());
                String previous = selected(uuid, section);
                String optionName = cosmeticName(holder.type(), optionId);
                if (!purchaseIfNeeded(player, holder.type(), optionId, optionName)) return;
                if (holder.type() != CosmeticsMenuHolder.Type.KILL_MESSAGES && optionId.equals(previous)) {
                    setSelection(uuid, section, "none");
                } else {
                    setSelection(uuid, section, optionId);
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                openOptions(player, holder.type());
            }
            case ARMOR_TRIMS -> {
                if (slot == 53) { openMenu(player); return; }
                if (slot == 48 && holder.page() > 0) { openArmorTrims(player, holder.page() - 1); return; }
                if (slot == 50 && (holder.page() + 1) * TRIMS_PER_PAGE < trims.size()) {
                    openArmorTrims(player, holder.page() + 1); return;
                }
                // Armor-piece icons are previews only; selecting a trim below updates all four pieces.
                if (slot == 10 || slot == 12 || slot == 14 || slot == 16) return;
                String patternId = holder.optionAt(slot);
                if (patternId == null) return;
                TrimOption chosen = trimById(patternId);
                if (chosen == null || !purchaseIfNeeded(player, CosmeticsMenuHolder.Type.ARMOR_TRIMS, patternId, chosen.name() + " Trim")) return;
                String previous = selectedTrim(uuid);
                setTrimSelection(uuid, patternId.equals(previous) ? "none" : patternId);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                openArmorTrims(player, holder.page());
            }
        }
    }

    /** Applies selected cosmetic trims after a kit is loaded, without changing kits or saved profiles. */
    public void applyArmorTrims(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        applyTrim(player.getInventory().getHelmet(), uuid);
        applyTrim(player.getInventory().getChestplate(), uuid);
        applyTrim(player.getInventory().getLeggings(), uuid);
        applyTrim(player.getInventory().getBoots(), uuid);
    }

    private void applyTrim(ItemStack item, UUID uuid) {
        if (item == null || item.getType().isAir()) return;
        String stored = selectedTrim(uuid);
        if (stored == null) return; // No personal override: preserve the kit's original armor metadata.
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof ArmorMeta meta)) return;
        TrimOption option = trimById(stored);
        if (option == null) meta.setTrim(null);
        else meta.setTrim(new ArmorTrim(TrimMaterial.DIAMOND, option.pattern()));
        item.setItemMeta(meta);
    }

    /** Returns one shared trim selection, with legacy per-piece settings supported. */
    private String selectedTrim(UUID uuid) {
        String global = selected(uuid, "armor-trims.pattern");
        if (global != null) return global;
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            String legacy = selected(uuid, "armor-trims." + piece);
            if (legacy != null && !legacy.equals("none")) return legacy;
        }
        // Keep a legacy explicit "none" override instead of preserving kit-provided trims.
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            String legacy = selected(uuid, "armor-trims." + piece);
            if (legacy != null) return legacy;
        }
        return null;
    }

    /** Persists the selected pattern for all armor slots, so every piece stays synchronized. */
    private void setTrimSelection(UUID uuid, String patternId) {
        data.set("players." + uuid + ".armor-trims.pattern", patternId);
        for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
            data.set("players." + uuid + ".armor-trims." + piece, patternId);
        }
        saveQuietly();
    }

    /** Returns a player's selected kill message and plays their kill effect only for a valid NexPractice match kill. */
    public Component handleMatchKill(Player killer, Player victim) {
        if (killer == null || victim == null || !isValidMatchKill(killer, victim)) return null;
        playKillEffect(killer, victim);
        if (killMessages.isEmpty()) return null;
        String id = selected(killer.getUniqueId(), "kill-message");
        MessageOption option = killMessages.stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElse(killMessages.get(0));
        String template = option.template()
                .replace("{killer}", "&6" + killer.getName())
                .replace("{victim}", "&c" + victim.getName());
        return Texts.component(template);
    }

    private boolean isValidMatchKill(Player killer, Player victim) {
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        Match solo = plugin.getQueueManager() == null ? null : plugin.getQueueManager().getMatch(victimId);
        if (solo != null) {
            return !solo.finishing() && solo.onArena() && solo.startedAtMillis() > 0
                    && solo.opponent(victimId).equals(killerId);
        }
        PartyMatch party = plugin.getPartyMatchManager() == null ? null : plugin.getPartyMatchManager().getActiveMatch(victimId);
        if (party == null || party.finishing() || !party.onArena() || party.startedAtMillis() <= 0
                || !party.members().contains(killerId) || !party.isAlive(killerId) || !party.isAlive(victimId)) return false;
        return party.mode() == PartyMatch.Mode.FFA || party.team(killerId) != party.team(victimId);
    }

    private void playKillEffect(Player killer, Player victim) {
        String id = selected(killer.getUniqueId(), "kill-effect");
        if (id == null || id.equals("none")) return;
        CosmeticOption option = killEffects.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
        if (option == null) return;
        Location center = victim.getLocation().clone().add(0, 1.0, 0);
        World world = center.getWorld();
        if (world == null) return;
        Particle particle = option.particle();
        spawnCosmeticParticle(world, particle, center, option.count(), option.offsetX(), option.offsetY(),
                option.offsetZ(), option.extra());
        if (option.secondaryParticle() != null && option.secondaryCount() > 0) {
            spawnCosmeticParticle(world, option.secondaryParticle(), center, option.secondaryCount(),
                    option.secondaryOffsetX(), option.secondaryOffsetY(), option.secondaryOffsetZ(), option.secondaryExtra());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)) return;
        String selected = selected(shooter.getUniqueId(), "projectile-trail");
        CosmeticOption option = trails.stream().filter(candidate -> candidate.id().equals(selected)).findFirst().orElse(null);
        if (option == null) return;
        new BukkitRunnable() {
            private int lifetime;
            @Override public void run() {
                if (!projectile.isValid() || projectile.isDead() || projectile.isOnGround() || lifetime++ >= option.maxLifetimeTicks()) {
                    cancel();
                    return;
                }
                Location location = projectile.getLocation();
                World world = location.getWorld();
                if (world != null) spawnCosmeticParticle(world, option.particle(), location, option.count(),
                        option.offsetX(), option.offsetY(), option.offsetZ(), option.extra());
            }
        }.runTaskTimer(plugin, 1L, option.intervalTicks());
    }

    private void spawnCosmeticParticle(World world, Particle particle, Location location, int count,
                                       double offsetX, double offsetY, double offsetZ, double extra) {
        // DRAGON_BREATH requires a Float particle-data payload on modern Paper versions.
        if (particle == Particle.DRAGON_BREATH) {
            world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, 1.0f);
        } else {
            world.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra);
        }
    }

    private void setSelection(UUID uuid, String section, String value) {
        data.set("players." + uuid + "." + section, value);
        saveQuietly();
    }

    private String selected(UUID uuid, String section) {
        return data.getString("players." + uuid + "." + section);
    }

    private void saveQuietly() {
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save cosmetics.yml: " + exception.getMessage());
        }
    }

    public void shutdown() { saveQuietly(); }

    private void fill(Inventory inventory, int size) {
        for (int slot = 0; slot < size; slot++) inventory.setItem(slot, menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
    }

    /** Fills only the outer ring of a chest menu, leaving the center available for options. */
    private void fillOutline(Inventory inventory, int size) {
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) {
                inventory.setItem(slot, menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
            }
        }
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null || material.isAir() ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Texts.nonItalic(Texts.component(name)));
        if (lore != null && !lore.isEmpty()) meta.lore(Texts.nonItalicLore(lore.stream().map(Texts::component).toList()));
        item.setItemMeta(meta);
        return item;
    }

    private String sectionFor(CosmeticsMenuHolder.Type type) {
        return switch (type) {
            case PROJECTILE_TRAILS -> "projectile-trail";
            case KILL_MESSAGES -> "kill-message";
            case KILL_EFFECTS -> "kill-effect";
            default -> "unknown";
        };
    }

    private String displayCategory(CosmeticsMenuHolder.Type type) {
        return switch (type) {
            case PROJECTILE_TRAILS -> categoryName("projectile-trails", "&eProjectile Trails");
            case KILL_MESSAGES -> categoryName("kill-messages", "&eKill Messages");
            case KILL_EFFECTS -> categoryName("kill-effects", "&eKill Effects");
            default -> "&eCosmetics";
        };
    }

    private String displaySelection(CosmeticsMenuHolder.Type type, String id) {
        if (id == null || id.equals("none")) return "None";
        if (type == CosmeticsMenuHolder.Type.KILL_MESSAGES) {
            return killMessages.stream().filter(option -> option.id().equals(id)).map(MessageOption::name).findFirst().orElse("Classic");
        }
        List<CosmeticOption> options = type == CosmeticsMenuHolder.Type.PROJECTILE_TRAILS ? trails : killEffects;
        return options.stream().filter(option -> option.id().equals(id)).map(CosmeticOption::name).findFirst().orElse("None");
    }

    private TrimOption trimById(String id) {
        if (id == null || id.equals("none")) return null;
        return trims.stream().filter(option -> option.id().equals(id)).findFirst().orElse(null);
    }

    private void migrateExistingSelectionsToUnlocked() {
        boolean changed = false;
        for (String section : List.of("projectile-trail", "kill-message", "kill-effect")) {
            ConfigurationSection players = data.getConfigurationSection("players");
            if (players == null) break;
            for (String playerId : players.getKeys(false)) {
                String selected = data.getString("players." + playerId + "." + section);
                if (selected != null && !selected.equalsIgnoreCase("none")
                        && !data.getBoolean("unlocked." + playerId + "." + section + "." + selected, false)) {
                    data.set("unlocked." + playerId + "." + section + "." + selected, true);
                    changed = true;
                }
            }
        }
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) for (String playerId : players.getKeys(false)) {
            String trim = data.getString("players." + playerId + ".armor-trims.pattern");
            if (trim != null && !trim.equalsIgnoreCase("none")
                    && !data.getBoolean("unlocked." + playerId + ".armor-trims." + trim, false)) {
                data.set("unlocked." + playerId + ".armor-trims." + trim, true);
                changed = true;
            }
        }
        if (changed) saveQuietly();
    }

    private boolean isUnlocked(UUID uuid, CosmeticsMenuHolder.Type type, String id) {
        String section = switch (type) {
            case PROJECTILE_TRAILS -> "projectile-trail";
            case KILL_MESSAGES -> "kill-message";
            case KILL_EFFECTS -> "kill-effect";
            case ARMOR_TRIMS -> "armor-trims";
            default -> "unknown";
        };
        if (id.equals(selected(uuid, section)) || type == CosmeticsMenuHolder.Type.ARMOR_TRIMS && id.equals(selectedTrim(uuid))) return true;
        return data.getBoolean("unlocked." + uuid + "." + section + "." + id, false);
    }

    private int cosmeticPrice(CosmeticsMenuHolder.Type type, String id) {
        String root = switch (type) {
            case PROJECTILE_TRAILS -> "cosmetics.projectile-trails.";
            case KILL_MESSAGES -> "cosmetics.kill-messages.";
            case KILL_EFFECTS -> "cosmetics.kill-effects.";
            case ARMOR_TRIMS -> "cosmetics.armor-trims.";
            default -> "cosmetics.";
        };
        return Math.max(150, Math.min(350, plugin.getConfig().getInt(root + id + ".price", 250)));
    }

    private String cosmeticName(CosmeticsMenuHolder.Type type, String id) {
        if (type == CosmeticsMenuHolder.Type.KILL_MESSAGES) {
            return killMessages.stream().filter(option -> option.id().equals(id)).map(MessageOption::name).findFirst().orElse(id);
        }
        if (type == CosmeticsMenuHolder.Type.ARMOR_TRIMS) {
            TrimOption option = trimById(id);
            return option == null ? id : option.name() + " Trim";
        }
        List<CosmeticOption> options = type == CosmeticsMenuHolder.Type.PROJECTILE_TRAILS ? trails : killEffects;
        return options.stream().filter(option -> option.id().equals(id)).map(CosmeticOption::name).findFirst().orElse(id);
    }

    private boolean purchaseIfNeeded(Player player, CosmeticsMenuHolder.Type type, String id, String name) {
        UUID uuid = player.getUniqueId();
        if (isUnlocked(uuid, type, id)) return true;
        int price = cosmeticPrice(type, id);
        if (plugin.getCoinsManager() == null || !plugin.getCoinsManager().takeCoins(uuid, price)) {
            int balance = plugin.getCoinsManager() == null ? 0 : plugin.getCoinsManager().getCoins(uuid);
            Texts.send(player, "&cYou need &6" + price + " ⛃ &cto unlock &e" + name + "&c. Your balance: &6" + balance + " ⛃&c.");
            return false;
        }
        String section = type == CosmeticsMenuHolder.Type.ARMOR_TRIMS ? "armor-trims" : sectionFor(type);
        data.set("unlocked." + uuid + "." + section + "." + id, true);
        saveQuietly();
        Texts.send(player, "&aPurchased &e" + name + " &afor &6" + price + " ⛃&a.");
        return true;
    }

    /** Reload cosmetic labels and the actual effects from config.yml; player selections stay in cosmetics.yml. */
    public void reloadOptions() {
        trails = loadCosmeticOptions("cosmetics.projectile-trails", TRAILS, true);
        killEffects = loadCosmeticOptions("cosmetics.kill-effects", KILL_EFFECTS, false);
        killMessages = loadMessageOptions("cosmetics.kill-messages", KILL_MESSAGES);
        trims = loadTrimOptions("cosmetics.armor-trims", TRIMS);
    }

    private List<CosmeticOption> loadCosmeticOptions(String root, List<CosmeticOption> defaults, boolean projectileTrail) {
        List<CosmeticOption> loaded = new ArrayList<>();
        for (CosmeticOption base : defaults) {
            String path = root + "." + base.id();
            if (!plugin.getConfig().getBoolean(path + ".enabled", true)) continue;
            String name = plugin.getConfig().getString(path + ".name", base.name());
            String description = plugin.getConfig().getString(path + ".description", base.description());
            Material icon = configuredMaterial(path + ".icon", base.icon());
            Particle particle = configuredParticle(path + ".particle", base.particle());
            int count = Math.max(0, Math.min(200, plugin.getConfig().getInt(path + (projectileTrail ? ".particles-per-step" : ".particle-count"), projectileTrail ? base.count() : 28)));
            double defaultOffset = projectileTrail ? 0.025 : 0.55;
            double offsetX = plugin.getConfig().getDouble(path + ".offset-x", defaultOffset);
            double offsetY = plugin.getConfig().getDouble(path + ".offset-y", projectileTrail ? defaultOffset : 0.65);
            double offsetZ = plugin.getConfig().getDouble(path + ".offset-z", defaultOffset);
            double extra = plugin.getConfig().getDouble(path + ".extra", projectileTrail ? 0.005 : 0.08);
            int interval = Math.max(1, Math.min(20, plugin.getConfig().getInt(path + ".interval-ticks", base.intervalTicks())));
            int lifetime = Math.max(1, Math.min(1200, plugin.getConfig().getInt(path + ".max-lifetime-ticks", base.maxLifetimeTicks())));
            String secondaryName = plugin.getConfig().getString(path + ".secondary-particle", base.secondaryParticle() == null ? "" : base.secondaryParticle().name());
            Particle secondary = parseOptionalParticle(secondaryName, path + ".secondary-particle");
            int secondaryCount = Math.max(0, plugin.getConfig().getInt(path + ".secondary-count", base.secondaryCount()));
            double secondaryOffsetX = plugin.getConfig().getDouble(path + ".secondary-offset-x", 0.35);
            double secondaryOffsetY = plugin.getConfig().getDouble(path + ".secondary-offset-y", 0.4);
            double secondaryOffsetZ = plugin.getConfig().getDouble(path + ".secondary-offset-z", 0.35);
            double secondaryExtra = plugin.getConfig().getDouble(path + ".secondary-extra", 0.0);
            loaded.add(new CosmeticOption(base.id(), name, description, icon, particle, count, offsetX, offsetY,
                    offsetZ, extra, interval, lifetime, secondary, secondaryCount, secondaryOffsetX,
                    secondaryOffsetY, secondaryOffsetZ, secondaryExtra));
        }
        return List.copyOf(loaded);
    }

    private List<MessageOption> loadMessageOptions(String root, List<MessageOption> defaults) {
        List<MessageOption> loaded = new ArrayList<>();
        for (MessageOption base : defaults) {
            String path = root + "." + base.id();
            if (!plugin.getConfig().getBoolean(path + ".enabled", true)) continue;
            loaded.add(new MessageOption(base.id(), plugin.getConfig().getString(path + ".name", base.name()),
                    plugin.getConfig().getString(path + ".description", base.description()),
                    configuredMaterial(path + ".icon", base.icon()),
                    plugin.getConfig().getString(path + ".template", base.template())));
        }
        return List.copyOf(loaded);
    }

    private List<TrimOption> loadTrimOptions(String root, List<TrimOption> defaults) {
        List<TrimOption> loaded = new ArrayList<>();
        for (TrimOption base : defaults) {
            String path = root + "." + base.id();
            if (!plugin.getConfig().getBoolean(path + ".enabled", true)) continue;
            String patternName = plugin.getConfig().getString(path + ".pattern", base.id());
            TrimPattern pattern = configuredTrimPattern(patternName, base.pattern(), path + ".pattern");
            TrimMaterial material = configuredTrimMaterial(plugin.getConfig().getString(path + ".trim-material", "DIAMOND"), base.material(), path + ".trim-material");
            loaded.add(new TrimOption(base.id(), plugin.getConfig().getString(path + ".name", base.name()),
                    plugin.getConfig().getString(path + ".description", base.description()),
                    configuredMaterial(path + ".icon", base.icon()), pattern, material));
        }
        return List.copyOf(loaded);
    }

    private ItemStack categoryItem(String id, Material fallbackIcon, String fallbackName, String fallbackDescription) {
        String path = "cosmetics.categories." + id;
        Material icon = configuredMaterial(path + ".icon", fallbackIcon);
        String name = plugin.getConfig().getString(path + ".name", fallbackName);
        String description = plugin.getConfig().getString(path + ".description", fallbackDescription);
        List<String> lore = new ArrayList<>();
        lore.add("");
        addDescription(lore, description);
        return menuItem(icon, name, lore);
    }

    private String categoryName(String id, String fallback) {
        return plugin.getConfig().getString("cosmetics.categories." + id + ".name", fallback);
    }

    private String categoryTitle(String id, String fallback) {
        return plugin.getConfig().getString("cosmetics.categories." + id + ".title", fallback);
    }

    private void addDescription(List<String> lore, String description) {
        if (description == null || description.isBlank()) return;
        for (String line : description.split("\\n")) lore.add(line);
    }

    private Material configuredMaterial(String path, Material fallback) {
        String value = plugin.getConfig().getString(path);
        if (value == null || value.isBlank()) return fallback;
        Material parsed = Material.matchMaterial(value.trim());
        if (parsed == null || parsed.isAir()) {
            plugin.getLogger().warning("Invalid material '" + value + "' in " + path + "; using " + fallback + ".");
            return fallback;
        }
        return parsed;
    }

    private Particle configuredParticle(String path, Particle fallback) {
        String value = plugin.getConfig().getString(path);
        if (value == null || value.isBlank()) return fallback;
        try { return Particle.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid particle '" + value + "' in " + path + "; using " + fallback + ".");
            return fallback;
        }
    }

    private Particle parseOptionalParticle(String value, String path) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) return null;
        try { return Particle.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid particle '" + value + "' in " + path + "; no secondary particle will play.");
            return null;
        }
    }

    private TrimPattern configuredTrimPattern(String value, TrimPattern fallback, String path) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
            case "sentry" -> TrimPattern.SENTRY;
            case "dune" -> TrimPattern.DUNE;
            case "coast" -> TrimPattern.COAST;
            case "wild" -> TrimPattern.WILD;
            case "ward" -> TrimPattern.WARD;
            case "eye" -> TrimPattern.EYE;
            case "vex" -> TrimPattern.VEX;
            case "tide" -> TrimPattern.TIDE;
            case "snout" -> TrimPattern.SNOUT;
            case "rib" -> TrimPattern.RIB;
            case "spire" -> TrimPattern.SPIRE;
            case "silence" -> TrimPattern.SILENCE;
            case "wayfinder" -> TrimPattern.WAYFINDER;
            case "shaper" -> TrimPattern.SHAPER;
            case "host" -> TrimPattern.HOST;
            case "raiser" -> TrimPattern.RAISER;
            case "flow" -> TrimPattern.FLOW;
            case "bolt" -> TrimPattern.BOLT;
            default -> {
                plugin.getLogger().warning("Unknown trim pattern '" + value + "' in " + path + "; using the built-in default.");
                yield fallback;
            }
        };
    }

    private TrimMaterial configuredTrimMaterial(String value, TrimMaterial fallback, String path) {
        if (value == null) return fallback;
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AMETHYST" -> TrimMaterial.AMETHYST;
            case "COPPER" -> TrimMaterial.COPPER;
            case "DIAMOND" -> TrimMaterial.DIAMOND;
            case "EMERALD" -> TrimMaterial.EMERALD;
            case "GOLD" -> TrimMaterial.GOLD;
            case "IRON" -> TrimMaterial.IRON;
            case "LAPIS" -> TrimMaterial.LAPIS;
            case "NETHERITE" -> TrimMaterial.NETHERITE;
            case "QUARTZ" -> TrimMaterial.QUARTZ;
            case "REDSTONE" -> TrimMaterial.REDSTONE;
            case "RESIN" -> TrimMaterial.RESIN;
            default -> {
                plugin.getLogger().warning("Unknown trim material '" + value + "' in " + path + "; using " + fallback + ".");
                yield fallback;
            }
        };
    }

    private record CosmeticOption(String id, String name, String description, Material icon, Particle particle,
                                  int count, double offsetX, double offsetY, double offsetZ, double extra,
                                  int intervalTicks, int maxLifetimeTicks, Particle secondaryParticle, int secondaryCount,
                                  double secondaryOffsetX, double secondaryOffsetY, double secondaryOffsetZ, double secondaryExtra) {
        private CosmeticOption(String id, String name, Material icon, Particle particle) {
            this(id, name, "&7A cosmetic particle effect.", icon, particle, 2, 0.025, 0.025, 0.025, 0.005,
                    2, 200, null, 0, 0.35, 0.4, 0.35, 0.0);
        }
    }
    private record MessageOption(String id, String name, String description, Material icon, String template) {
        private MessageOption(String id, String name, Material icon, String template) {
            this(id, name, "&7A custom message shown for your match kill.", icon, template);
        }
    }
    private record TrimOption(String id, String name, String description, Material icon, TrimPattern pattern, TrimMaterial material) {
        private TrimOption(String id, String name, Material icon, TrimPattern pattern) {
            this(id, name, "&7A visual armor-trim pattern.", icon, pattern, TrimMaterial.DIAMOND);
        }
    }
}
