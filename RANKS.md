# VPRAC per-kit ranks

Ranks are based on wins for each kit, independently. All player statistics are persisted in `plugins/VPRAC/leaderboards.yml`; rank labels and next-rank progress are derived from those saved win counts, so no external database or client mod is required. The plugin also stores a `played` marker per player/kit and recognizes older statistics when migrating from existing data.

Players who have never recorded a match in a kit see a red stained-glass pane marked `Locked`. After a match has been recorded, that kit shows its actual icon; until the player reaches 10 wins, its label is `Unranked`. Rank tiers are ordered III, II, I, with I being the highest tier in each division.

## Win thresholds

The specified division thresholds enter each division at tier III. Tiers II and I are evenly spaced between that division's threshold and the next division's tier-III threshold. Grandmaster has no following division, so Grandmaster II and I are each 100 wins above the previous tier.

| Required wins | Rank |
|---:|---|
| 10 | Iron III |
| 31 | Iron II |
| 53 | Iron I |
| 75 | Bronze III |
| 100 | Bronze II |
| 125 | Bronze I |
| 150 | Silver III |
| 183 | Silver II |
| 216 | Silver I |
| 250 | Gold III |
| 283 | Gold II |
| 316 | Gold I |
| 350 | Platinum III |
| 400 | Platinum II |
| 450 | Platinum I |
| 500 | Diamond III |
| 583 | Diamond II |
| 666 | Diamond I |
| 750 | Master III |
| 833 | Master II |
| 916 | Master I |
| 1,000 | Grandmaster III |
| 1,100 | Grandmaster II |
| 1,200 | Grandmaster I |

## Match messages in `config.yml`

`match-statics.enabled` controls the chat stats message sent when a solo queue/direct duel is found. Each entry in `match-statics.lines` becomes one chat line; an empty string adds a blank line. Supported placeholders include:

- `%map%`
- `%opponent%`
- `%opponent_rank%`, `%rank%`, `%their_rank%`
- `%opponent_ping%`, `%their_ping%`
- `%kit%`, `%player_name%`, `%player_rank%`, `%player_ping%`

`match-info.enabled` and `match-info.lines` control post-match report chat lines. `%winner%` and `%loser%` are special placeholders; the names remain clickable and open the existing read-only inventory report. Leave `enabled: true` and remove or rearrange lines to customize the message. Set `enabled: false` to hide that message.

VPRAC's player-facing text is intentionally non-bold. Legacy bold-formatting codes are stripped by the text utility, including if they remain in an older server config.
