# NexPractice Cosmetics (1.0.0)

Cosmetic choices are saved by player UUID in `plugins/NexPractice/cosmetics.yml`, independent of kit profiles and combat statistics. Choices save immediately when clicked, and NexPractice performs a final save during shutdown.

## Projectile trails (10)

- Flame Trail
- End Rod Trail
- Heart Trail
- Critical Trail
- Enchantment Trail
- Soul Fire Trail
- Cloud Trail
- Dragon Breath Trail
- Totem Trail
- Portal Trail

Trails use particles on player-launched projectiles. Clicking the selected trail again turns it off.

## Kill messages (10)

- Classic
- Outplayed
- Comboed
- Sent Home
- Owned
- Clipped
- No Chance
- Dominated
- One-Tapped
- Knocked Out

The killer's selected message is sent to the killer and defeated opponent for valid NexPractice match eliminations. The classic message is the default for players who have not chosen one.

## Kill effects (10)

- Fire Burst
- Heart Burst
- Spark Burst
- Soul Release
- Ender Pop
- Totem Burst
- Critical Burst
- Dragon Cloud
- Enchantment Burst
- Cloud Puff

Kill effects use server-side particles and activate only for valid NexPractice match eliminations. Clicking the selected effect again turns it off.

## Armor trims

All 18 vanilla trim patterns available in Paper 1.21.11 are listed across two menu pages: Sentry, Dune, Coast, Wild, Ward, Eye, Vex, Tide, Snout, Rib, Spire, Silence, Wayfinder, Shaper, Host, Raiser, Flow, and Bolt. One selected pattern is shared by the helmet, chestplate, leggings, and boots. The four armor icons in the menu are previews only; they do not need to be selected. Selecting a pattern immediately saves it for all armor pieces. Trim accent material is configurable per pattern through `trim-material` in `config.yml` and the chosen value is shared across all four pieces. Clicking the currently selected pattern stores `none` to clear a personal override; an unset override preserves trim data that was already part of the kit. Older per-piece selections remain readable; VPRAC picks the first existing trim in helmet/chestplate/leggings/boots order until the player selects a new shared pattern.

Selections are stored in `players.<UUID>.projectile-trail`, `kill-message`, `kill-effect`, `armor-trims.pattern`, and synchronized `armor-trims.<piece>` paths in `cosmetics.yml`. No player inventory contents or kit profiles are changed by cosmetics selection.


## Coins and prices

Each purchasable trail, kill message, kill effect, and armor trim displays its configured price as `&6<price> ⛃`. The shipped cosmetic prices range from 150 to 350 coins. Balances are persisted in `plugins/NexPractice/coins.yml`; players can see the balance through `%coins%` (or `%vprac_coins%` in PlaceholderAPI), and the default tournament champion reward is 100 coins per winning player.

Prices and descriptions are editable per item with `price` and `description` under the relevant `cosmetics.*` category in `config.yml`. NexPractice clamps prices to 150–350 at purchase/menu time to keep them in the requested range.

## Editing cosmetic definitions

All cosmetic option names, descriptions, item icons, and behavior settings live under `cosmetics:` in `config.yml`. Existing installations receive missing defaults on startup without overwriting values they have already changed. After editing these definitions, run `/vscoreboard reload` to reload cosmetic behavior and labels. Player selections remain separate in `plugins/NexPractice/cosmetics.yml`.

- `cosmetics.categories.*`: category title, menu label, description, and icon.
- `cosmetics.projectile-trails.*`: `enabled`, `name`, `description`, `icon`, `particle`, `particles-per-step`, `offset-x/y/z`, `extra`, `interval-ticks`, and `max-lifetime-ticks`. Particle names must match Paper 1.21.11/Bukkit `Particle` constants. Shorter interval and larger counts create denser trails; larger `max-lifetime-ticks` lets effects run longer.
- `cosmetics.kill-messages.*`: `enabled`, `name`, `description`, `icon`, and `template`. Use `{killer}` and `{victim}` in `template`; those tokens are replaced with the players' names.
- `cosmetics.kill-effects.*`: `enabled`, `name`, `description`, `icon`, `particle`, `particle-count`, offsets, `extra`, and optional `secondary-particle`, `secondary-count`, `secondary-offset-x/y/z`, and `secondary-extra` settings. The primary particle is the kill burst; the optional secondary particle creates an additional burst. Increase count/offsets for larger effects; note that high particle counts affect client performance.
- `cosmetics.armor-trims.*`: `enabled`, `name`, `description`, `icon`, `pattern`, and `trim-material`. `pattern` must be one of the vanilla trim patterns listed in config; trim material can be AMETHYST, COPPER, DIAMOND, EMERALD, GOLD, IRON, LAPIS, NETHERITE, QUARTZ, REDSTONE, or RESIN on Paper 1.21.11.

Use valid material and particle identifiers. NexPractice logs a warning and falls back to the built-in value for an invalid material, particle, trim pattern, or trim material.
