# VPRAC Scoreboard Configuration

VPRAC's sidebar is configurable in `plugins/VPRAC/config.yml` under `scoreboard:`. Change the `title` and the ordered `lines` list under `lobby` or `match`. Use Minecraft legacy color codes such as `&b`, `&e`, and `&m`; ordinary scoreboard text is kept non-bold for a consistent compact layout, while the default lobby clock glyph retains the requested bold style. Use `''` for an empty line. A sidebar can display at most 15 lines.

After editing, run `/vscoreboard reload` (permission: `vprac.admin.scoreboard`) to reload and apply the configuration without restarting the server.

## Core placeholders

These short placeholders are supported directly in VPRAC scoreboard lines and do not require a separate PlaceholderAPI expansion:

| Placeholder | Meaning |
|---|---|
| `%player_name%` | Viewing player's name |
| `%player_ping%` | Viewing player's ping in milliseconds, as a number |
| `%player_ping_color%` | Color code based on the viewing player's ping (`&a`, `&e`, or `&c`) |
| `%player_health%` | Current health |
| `%player_max_health%` | Maximum health |
| `%player_food%` | Food level |
| `%player_level%` | Experience level |
| `%player_world%` | Current world name |
| `%player_gamemode%` | Current game mode |
| `%player_x%`, `%player_y%`, `%player_z%` | Block coordinates |
| `%server_online%` | Number of players online on this server |
| `%server_max_players%` | Configured server player limit |

## VPRAC placeholders

| Placeholder | Meaning |
|---|---|
| `%vprac_date%` | Lobby clock rendered using `lobby-clock.timezone` and `lobby-clock.format`; defaults to `MMM dd, HH:mm` (e.g. `Oct 22, 21:16`) with no timezone suffix |
| `%vprac_fighting%` | Number of players VPRAC currently considers in matches |
| `%vprac_queueing%` | Number of online players waiting in solo queues |
| `%vprac_online%` | Number of players online |
| `%vprac_status%` | `Lobby`, `Queueing`, `Countdown`, `Fighting`, `Finishing`, or `Spectating` |
| `%vprac_kit%` | Display name of the active match kit, or `None` outside a match |
| `%vprac_kit_id%` | Internal kit ID, or `none` outside a match |
| `%vprac_opponent%` | Solo opponent, or a party opponent/party mode where applicable |
| `%vprac_opponent_ping%` | Opponent ping with a color code, or `Offline` |
| `%vprac_match_time%` | Elapsed match time in `MM:SS`; `00:00` before the match starts or outside a match |
| `%vprac_match_mode%` | `Solo`, `Party FFA`, `Party Split`, or `None` |
| `%vprac_match_kills%` | Kills in the current match, or `0` outside a match |
| `%vprac_kills%` | Lifetime kills for the active kit; Sword stats are used in the lobby |
| `%vprac_total_kills%` | Alias for `%vprac_kills%` |
| `%vprac_wins%` | Lifetime wins for the active kit; Sword stats are used in the lobby |
| `%vprac_losses%` | Lifetime losses for the active kit; Sword stats are used in the lobby |
| `%vprac_deaths%` | Lifetime deaths for the active kit; Sword stats are used in the lobby |
| `%vprac_kdr%` | Lifetime KDR for the active kit; Sword stats are used in the lobby |
| `%vprac_queue_count%` | Existing VPRAC placeholder: players waiting in all solo queues |
| `%vprac_queue_<kit>_count%` | Existing VPRAC placeholder: queue size for a kit, e.g. `%vprac_queue_sword_count%` |
| `%vprac_queued_<kit>%` | Existing VPRAC placeholder: whether the player is queued for a kit, e.g. `%vprac_queued_sword%` |

For party FFA, `%vprac_opponent%` uses the first other living participant when available; if none is available it displays `Party FFA`. For split-team party matches, it uses the first living opponent on the other team. When an external spectator watches a match, the match context follows the watched player, while personal stats and player placeholders continue to describe the spectator.

## Configurable lobby clock

The first lobby line defaults to `&7&l⌚ &7%vprac_date%`. Edit the clock settings in `config.yml`:

```yaml
lobby-clock:
  timezone: 'GMT+03:00'
  format: 'MMM dd, HH:mm'
```

For example, set `timezone` to `GMT+04:00` or `GMT+05:00` to change the lobby clock independently of the server's system timezone. The default format uses the first three letters of the English month, a zero-padded day, and 24-hour `HH:mm`, without appending a GMT label.

## PlaceholderAPI placeholders

After VPRAC's core placeholders are replaced, the remaining line is passed through PlaceholderAPI. Any other expansion placeholder can be used if its provider plugin and PlaceholderAPI expansion are installed. For example, `%vault_prefix%` requires a compatible Vault/LuckPerms setup and relevant expansion. VPRAC provides `%player_name%` and `%player_ping%` directly in scoreboards, so the Player expansion is not required for those two basic values.

## Example custom layout

```yaml
scoreboard:
  enabled: true
  update-interval-ticks: 20
  lobby:
    enabled: true
    title: '&bVPRAC'
    lines:
      - '&8&m----------------'
      - '&fPlayer: &b%player_name%'
      - '&fPing: &a%player_ping%ms'
      - ''
      - '&fStatus: &a%vprac_status%'
      - '&fOnline: &a%server_online%'
      - '&eplay.example.com'
  match:
    enabled: true
    title: '&bVPRAC'
    lines:
      - '&8&m----------------'
      - '&fKit: &b%vprac_kit%'
      - '&fOpponent: &c%vprac_opponent%'
      - '&fPing: &a%player_ping%ms'
      - '&fMatch time: &e%vprac_match_time%'
      - '&fMatch kills: &a%vprac_match_kills%'
      - '&eplay.example.com'
```

## Party match scoreboard placeholders

These placeholders are intended for the `scoreboard.party-ffa`, `scoreboard.party-split`, and `scoreboard.party-split-bedfight` layouts. Party-specific scoreboards are shown only after the match participants have reached the arena.

- `%vprac_match_kills%` — the viewer's kills in the current match.
- `%vprac_party_players_alive%` — living Party FFA players remaining.
- `%vprac_party_players_total%` — original total players assigned to the party match.
- `%vprac_party_team_alive%` — living players remaining on the viewer's split team.
- `%vprac_party_team_total%` — original number of players on the viewer's team.
- `%vprac_party_enemy_alive%` — living players remaining on the opposing team.
- `%vprac_party_enemy_total%` — original number of players on the opposing team.
- `%player_ping%` — the viewer's current ping in milliseconds.
- `%player_ping_color%` — color selected from `scoreboard.ping-colors` based on the viewer's ping.

The ping thresholds and colors can be changed under `scoreboard.ping-colors` without editing Java.

- `%vprac_rank%` and `%player_rank%`: the viewer's per-kit rank, or `Hidden` when Show Own Rank is disabled.


## Round score placeholders

- `%vprac_round_score%` — current match round wins for the player (or followed target when spectating).
- `%vprac_opponent_round_score%` — current round wins for the opponent, or opposing team in Party Split.


## Bedfight scoreboard

The `scoreboard.bedfight` section applies to solo queue matches assigned an arena with Bedfight enabled. Direct duels do not use Bedfight-enabled arenas. Party Split can use a Bedfight-configured arena and automatically switches to `scoreboard.party-split-bedfight`, but the solo queue's bed-based respawn rules do not apply to party matches.

| Placeholder | Meaning |
|---|---|
| `%vprac_bed_self_color%` | The player's Bedfight team color (`&9` for the first participant/team, `&c` for the second). |
| `%vprac_bed_self_mark%` | `✔` while the player's configured bed is intact, otherwise `✘`. In Party Split, this reflects whether the configured team bed block is present. |
| `%vprac_bed_opponent_color%` | The opposing bed's team color. |
| `%vprac_bed_opponent_mark%` | `✔` while the opponent's configured bed is intact, otherwise `✘`. In Party Split, this reflects whether the configured opposing bed block is present. |
| `%vprac_match_kills%` | Current match kills for the scoreboard subject. |

You can edit the solo Bedfight title and lines in `config.yml` under `scoreboard.bedfight`; the Party Split Bedfight-themed layout is separately editable under `scoreboard.party-split-bedfight`. The default keeps the bed markers' colors separate from the surrounding text so the check/cross follows the assigned team. Minecraft sidebars support at most 15 lines, so the Party Split layout uses fewer blank separators to keep the ping and server footer visible.


## Coins

- `%coins%` — the viewer's current VPRAC coin balance. It is available in VPRAC's configurable scoreboard lines and as a PlaceholderAPI placeholder.
- `%vprac_coins%` — the existing namespaced PlaceholderAPI alias for the same balance.

Coins are persisted in `plugins/VPRAC/coins.yml`, cosmetic prices are configurable under `cosmetics.*`, and a tournament champion receives the configured `tournaments.winner-coins` reward (100 by default).

## Multi-round queue scoreboard

When a solo queue match uses a kit configured with more than one round win, VPRAC automatically uses `scoreboard.match-multiround`. Edit its title and lines in `config.yml`; its default layout shows the opponent, round score (your score in blue and opponent score in red), both pings, and `Server.ip`. Set `scoreboard.match-multiround.enabled: false` to fall back to the normal match layout. Direct duels, tournament bouts, Bedfight, and party fights do not use this layout.
