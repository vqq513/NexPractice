# NexPractice

NexPractice is a PvP practice plugin for Paper servers. It includes queues, duels, parties, tournaments, custom kits, BedFight, and more. It's designed for server owners who want to run their own practice server and customize it to their liking.

## Features

### ⚔️ PvP & Matchmaking
- Kit-based queues for different playstyles
- 1v1 duels and party fights
- Party FFA and Party Split
- Configurable rounds and countdowns for solo queue matches
- Spectator mode and match reports
- Multiple arena instances for running matches at the same time

### 🗺️ Kits & Arenas
- Create custom kits with your own icons
- Edit kits and configure their gameplay rules
- Support for different kits, including Sword, Mace, Crystal, and BedFight
- Automatic arena restoration after matches
- Arena copies with independent spawn points and reservations
- Per-kit rules for hunger, TNT, creeper ignition, and other mechanics

### 🛏️ BedFight
- Bed-based respawning in solo queue matches
- Bed destruction and void elimination
- Custom fireball mechanics
- Party Split support on BedFight-configured arenas
- Dedicated BedFight scoreboards and team bed indicators

### 👥 Parties & Tournaments
- Create parties and invite players
- Manage party members, leaders, and invitations
- Party chat and configurable party visibility
- Party FFA and Party Split fights
- Host 1v1 and 2v2 tournaments
- Tournament brackets, teammate invitations, and rewards

### 🏆 Ranks & Cosmetics
- Independent rank progression for each kit
- Unlock higher ranks by winning matches
- Coins and cosmetic unlocks
- Projectile trails, kill messages, kill effects, and armor trims
- Player statistics and leaderboards

### ⚙️ Customization
- Editable lobby, match, party, and BedFight scoreboards
- Player preferences for sounds, visibility, invitations, and more
- Customizable cosmetics and prices
- PlaceholderAPI support
- YAML, MySQL, and MongoDB options for match statistics
- Optional FancyHolograms integration for in-game leaderboards

## Requirements

- Paper 1.21.11–26.3
- Java 21 for Paper 1.21.11
- Java 25 or newer for Paper 26.1+
- PacketEvents 2.14.0 or newer, with support for your server version
- PlaceholderAPI 2.12.3 or a compatible newer version
- FastAsyncWorldEdit (FAWE), with support for your server version

FancyHolograms is optional and is only required for holographic leaderboards. Install a version compatible with your server.

## Installation

1. Download the NexPractice JAR.
2. Install PacketEvents, PlaceholderAPI, and FastAsyncWorldEdit on your server.
3. Place the NexPractice JAR in your server's `plugins` folder.
4. Start the server and check the console for errors.
5. Configure the plugin files in `plugins/NexPractice/`.

Use `NexPractice-1.0.0.jar`, not the `-plain.jar` build.

Back up your server and plugin data before updating.

## Admin Commands

### Kits
| Command | Description |
|---|---|
| `/vkitaddingmode` | Enter kit creation mode. |
| `/vaddkit <name> <icon>` | Save a kit with an icon. |
| `/vkitsettings <kit>` | Configure rules for a kit. |

### Arenas
| Command | Description |
|---|---|
| `/varena create <name>` | Create an arena template. |
| `/vsetarenaborder start <arena>` | Start selecting an arena region. |
| `/vsetarenaborder done` | Save the selected region and its snapshot. |
| `/varenamenu` | Manage arena copies, kit restrictions, and arena settings. |
| `/varena enable <name>` | Enable an arena. |
| `/varena disable <name>` | Disable an arena. |
| `/varena list` | List configured arenas. |
| `/vsetlobby` | Set the practice lobby spawn. |

Configure both standard arena spawns before enabling an arena. Arena activation also requires a valid region, snapshot, and operational copies.

### BedFight
| Command | Description |
|---|---|
| `/varena bedsetup <arena>` | Configure the two BedFight beds. |
| `/varena bedsetvoid <arena>` | Select the BedFight void region. |
| `/varena enablebedfight <arena>` | Enable BedFight for the arena. |
| `/varena disable bedfight <arena>` | Disable BedFight without disabling the arena. |

Configure the normal arena region and both standard spawns first. Run the bed setup command and place the tagged red beds in A-then-B order. Use the void selection axe to select both corners of the void region, then enable BedFight.

### Other Administration
| Command | Description |
|---|---|
| `/vaddcoins <name> <amount>` | Add coins to a player. |
| `/vgive <player> <fireball\|goldenhead> <amount>` | Give special Fireballs or Golden Heads. |
| `/vscoreboard reload` | Reload scoreboard and supported configuration changes. |
| `/vtournament cancel` | Cancel tournament registration. |
| `/ranks` | Display rank tiers and win requirements. |

Most administrative commands require operator permissions by default.

### Holographic Leaderboards

Requires FancyHolograms.

- `/vhg create leaderboards <wins|kills|kdr|rank>` — Create a leaderboard hologram.
- `/vhg list` — List existing holograms.
- `/vhg delete leaderboards <id>` — Delete a leaderboard.
- `/vhg reload` — Refresh leaderboard displays.

## Player Commands

| Command | Description |
|---|---|
| `/duel <player>` | Send a duel request. |
| `/party` | View available party commands. |
| `/party invite <name>` | Invite a player to your party. |
| `/party kick <name>` | Remove a party member. |
| `/party transfer <name>` | Transfer party leadership. |
| `/party chat <message>` | Send a party message. |
| `/party leave` | Leave your party. |
| `/party disband` | Disband your party. |
| `/msg <name> <message>` | Send a private message. |
| `/r <message>` | Reply to the last player who messaged you. |
| `/spec <player>` | Spectate an eligible live match. |

Party chat is also available through `/pc <message>`, `/p <message>`, and `@ <message>`.

## Configuration

The main configuration file is `plugins/NexPractice/config.yml`.

- **Scoreboards:** Edit the layouts under `scoreboard`, including lobby, match, Party FFA, Party Split, and BedFight layouts.
- **Cosmetics:** Customize names, descriptions, icons, prices, and supported cosmetic effects under `cosmetics`.
- **Statistics storage:** Set `storage.backend` to `server`, `mysql`, or `mongodb`.
- **Tournaments:** Configure registration, broadcasts, rewards, cooldowns, and other options under `tournaments`.
- **Lobby clock:** Customize its timezone and display format under `lobby-clock`.

Use `/vscoreboard reload` to reload the scoreboard configuration.

Player preferences, coins, cosmetics, kit profiles, arena data, and other plugin data are stored in their respective files under `plugins/NexPractice/`. The statistics backend setting applies specifically to per-kit match statistics, not every plugin subsystem.

## Building from Source

You need a compatible JDK and Gradle installed.

Run the following command from the project root:

```bash
gradle clean build
```

The installable JAR is generated in `build/libs/`. Use the shaded `NexPractice-1.0.0.jar`, not the `-plain.jar` file.

## Compatibility

NexPractice targets Paper 1.21.11 through 26.3. Use a compatible build of every required dependency for your server version. A successful build does not by itself guarantee runtime compatibility on every supported Paper release.

## License

NexPractice is distributed under the MIT License. See the `LICENSE` file for the full terms.

## Support

NexPractice is currently in beta, so bugs are possible. When reporting an issue, include your Paper version, relevant console logs, and steps to reproduce the problem.

Thanks for checking out NexPractice!