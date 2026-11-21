package dev.stsv.economy.road;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 국도 성격 (README "국도", 성격): every road is easy (쉬운길), normal (보통: the roads as they always were, the default) or
 * hard (어려운길). Its {@link Shape} is what the planner, the profile and the cross-section build differently, and what it
 * pays (bus fares, the streak bonus, hikers at its stops, the dock's unloading). Saved with the road (roads/&lt;dock&gt;.txt
 * {@code character=}; a road saved before: normal). The shapes come from roads.characters.&lt;id&gt; ({@link #configure}),
 * else these defaults. Pure (unit-tested).
 */
public enum RoadCharacter {
    EASY("easy", "쉬운길"), NORMAL("normal", "보통"), HARD("hard", "어려운길");

    /** Its name in the config and the road file. */
    public final String id;
    /** Its name in the commands and messages. */
    public final String word;

    RoadCharacter(String id, String word) {
        this.id = id;
        this.word = word;
    }

    /**
     * What a character builds and pays.
     *
     * @param gradeRun    one block up or down in this many along at most (normal 3; 2 .. 8)
     * @param climb       planner: cost a block of height changed (normal 0.02)
     * @param steep       planner: cost a block over the grade (normal 0.6)
     * @param turn        planner: cost each 45 degrees the route turns cell to cell (normal 0: none)
     * @param bridgeGap   a bridge where the road runs more than this over the ground (normal 3)
     * @param tunnelCover a tunnel where the ground stands this much over the road (normal 5)
     * @param minRun      shorter bridges / tunnels are filled / cut open (normal 4)
     * @param shoulder    the shoulder's width each side, blocks more (+) or fewer (-) than normal (-1 .. 1)
     * @param lightFactor street lights every roads.light-every × this
     * @param reflectEvery a reflector post every this many points (0: none)
     * @param railDrop    a guardrail where the shoulder stands this much or more over the ground
     * @param warnings    warning signs (급커브 / 경사 / 낙석 주의) before sharp bends, steep grades and deep cuts
     * @param speedBonus  added to roads.speed-bonus on its surface
     * @param fare        bus fares to or from a stop on it ×
     * @param streak      the safe-driving streak bonus at such a stop ×
     * @param outdoors    hikers' and campers' weight at such a stop ×
     * @param dock        what its dock pays for unloading ×
     */
    public record Shape(double gradeRun, double climb, double steep, double turn, int bridgeGap, int tunnelCover, int minRun,
                        int shoulder, double lightFactor, int reflectEvery, int railDrop, boolean warnings, double speedBonus,
                        double fare, double streak, double outdoors, double dock) {

        /** The planner's weights ({@link RoadPlanner.Weights}): normal's are {@link RoadPlanner.Weights#DEFAULT}. */
        public RoadPlanner.Weights weights() {
            RoadPlanner.Weights d = RoadPlanner.Weights.DEFAULT;
            return new RoadPlanner.Weights(climb, steep, d.rough(), d.water(), d.depth(), d.reuse(), d.beside(), gradeRun, turn);
        }

        /** The grade in half blocks ({@link RoadProfile.Grade}). */
        public RoadProfile.Grade grade() {
            return RoadProfile.Grade.of(gradeRun);
        }

        /** Street lights every this many points, for roads.light-every. */
        public int lightEvery(int every) {
            return Math.max(8, (int) Math.round(every * lightFactor));
        }
    }

    static final Map<RoadCharacter, Shape> DEFAULTS = new EnumMap<>(Map.of(
            EASY, new Shape(5, 0.04, 1.2, 0.5, 3, 4, 4, 1, 0.75, 12, 2, false, 0.15, 1, 1, 1, 1),
            NORMAL, new Shape(3, 0.02, 0.6, 0, 3, 5, 4, 0, 1, 20, 3, false, 0, 1, 1, 1, 1),
            HARD, new Shape(2.5, 0.01, 0.3, 0, 5, 8, 8, -1, 2, 0, 3, true, 0, 1.4, 2, 2, 1)));

    private static volatile Map<RoadCharacter, Shape> shapes = DEFAULTS;

    /** Its shape now (roads.characters.&lt;id&gt;, else the default). */
    public Shape shape() {
        Shape s = shapes.get(this);
        return s != null ? s : DEFAULTS.get(this);
    }

    /** Its default shape. */
    public Shape defaults() {
        return DEFAULTS.get(this);
    }

    /** The shapes from the config (a missing one: its default); null: the defaults. Normal always builds as it always did. */
    public static void configure(Map<RoadCharacter, Shape> m) {
        Map<RoadCharacter, Shape> out = new EnumMap<>(DEFAULTS);
        if (m != null) m.forEach((k, v) -> {
            if (k != NORMAL && v != null) out.put(k, clean(v));
        });
        // normal never changes how a road is built (only what it pays)
        if (m != null && m.get(NORMAL) != null) {
            Shape n = m.get(NORMAL), d = DEFAULTS.get(NORMAL);
            out.put(NORMAL, new Shape(d.gradeRun(), d.climb(), d.steep(), d.turn(), d.bridgeGap(), d.tunnelCover(), d.minRun(), d.shoulder(),
                    d.lightFactor(), d.reflectEvery(), d.railDrop(), d.warnings(), Math.max(0, n.speedBonus()), Math.max(0, n.fare()),
                    Math.max(0, n.streak()), Math.max(0, n.outdoors()), Math.max(0, n.dock())));
        }
        shapes = out;
    }

    /** A shape kept in bounds the builder can take. */
    static Shape clean(Shape s) {
        return new Shape(Math.clamp(s.gradeRun(), 2, 8), Math.max(0, s.climb()), Math.max(0, s.steep()), Math.max(0, s.turn()),
                Math.clamp(s.bridgeGap(), 2, 12), Math.clamp(s.tunnelCover(), 3, 16), Math.clamp(s.minRun(), 1, 32), Math.clamp(s.shoulder(), -1, 1),
                Math.clamp(s.lightFactor(), 0.25, 4), Math.max(0, s.reflectEvery()), Math.clamp(s.railDrop(), 1, 8), s.warnings(),
                Math.clamp(s.speedBonus(), 0, 1), Math.max(0, s.fare()), Math.max(0, s.streak()), Math.max(0, s.outdoors()), Math.max(0, s.dock()));
    }

    /** A character by its word (쉬운길, 보통, 어려운길; also 쉬움 / 어려움) or id (easy, normal, hard); null: none. */
    public static RoadCharacter parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (RoadCharacter c : values()) if (c.id.equals(t) || c.word.equals(t)) return c;
        return switch (t) {
            case "쉬움", "쉬운" -> EASY;
            case "어려움", "어려운", "산길" -> HARD;
            default -> null;
        };
    }

    /** As saved ({@link #id}); anything else (an older road's missing line): normal. */
    public static RoadCharacter ofId(String s) {
        RoadCharacter c = parse(s);
        return c == null ? NORMAL : c;
    }
}
