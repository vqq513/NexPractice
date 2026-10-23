package com.vprac.leaderboard;

import java.util.ArrayList;
import java.util.List;

/** Win-based per-kit division ladder used by the Ranks menu and match announcements. */
public final class KitRankProgression {
    private static final String[] DIVISIONS = {
            "Iron", "Bronze", "Silver", "Gold", "Platinum", "Diamond", "Master", "Grandmaster"
    };
    /** Wins required to enter each division at tier III, as requested for NexPractice. */
    private static final int[] DIVISION_START_WINS = {10, 75, 150, 250, 350, 500, 750, 1000};
    /** The final division has no following division, so its II/I tiers are 100 wins apart. */
    private static final int FINAL_DIVISION_TIER_STEP = 100;
    private static final List<RankLevel> LEVELS = buildLevels();

    private KitRankProgression() { }

    public record RankLevel(String division, String tier, int requiredWins) {
        public String displayName() { return division + " " + tier; }
    }

    private static List<RankLevel> buildLevels() {
        List<RankLevel> levels = new ArrayList<>(DIVISIONS.length * 3);
        for (int i = 0; i < DIVISIONS.length; i++) {
            int start = DIVISION_START_WINS[i];
            if (i < DIVISIONS.length - 1) {
                int span = DIVISION_START_WINS[i + 1] - start;
                levels.add(new RankLevel(DIVISIONS[i], "III", start));
                levels.add(new RankLevel(DIVISIONS[i], "II", start + span / 3));
                levels.add(new RankLevel(DIVISIONS[i], "I", start + (span * 2) / 3));
            } else {
                levels.add(new RankLevel(DIVISIONS[i], "III", start));
                levels.add(new RankLevel(DIVISIONS[i], "II", start + FINAL_DIVISION_TIER_STEP));
                levels.add(new RankLevel(DIVISIONS[i], "I", start + FINAL_DIVISION_TIER_STEP * 2));
            }
        }
        return List.copyOf(levels);
    }

    public static List<RankLevel> levels() {
        return LEVELS;
    }

    /** Returns null while the player has fewer than 10 wins for this kit. */
    public static RankLevel currentLevel(int wins) {
        int safeWins = Math.max(0, wins);
        RankLevel current = null;
        for (RankLevel level : LEVELS) {
            if (safeWins < level.requiredWins()) break;
            current = level;
        }
        return current;
    }

    /** Returns the next tier to unlock, or null once Grandmaster I is reached. */
    public static RankLevel nextLevel(int wins) {
        int safeWins = Math.max(0, wins);
        for (RankLevel level : LEVELS) {
            if (safeWins < level.requiredWins()) return level;
        }
        return null;
    }

    public static String rankName(int wins) {
        RankLevel level = currentLevel(wins);
        return level == null ? "Unranked" : level.displayName();
    }

    /** Sort key for the rank leaderboard. Unranked players sort below Iron III. */
    public static int rankOrdinal(int wins) {
        RankLevel level = currentLevel(wins);
        return level == null ? -1 : LEVELS.indexOf(level);
    }

    public static int winsToNext(int wins) {
        RankLevel next = nextLevel(wins);
        return next == null ? 0 : Math.max(0, next.requiredWins() - Math.max(0, wins));
    }
}
