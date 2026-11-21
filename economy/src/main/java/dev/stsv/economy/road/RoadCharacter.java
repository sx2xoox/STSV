package dev.stsv.economy.road;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 국도 성격 (README "국도", 성격): every road is easy (쉬운길), normal (보통, the default: steeper and closer to the ground
 * than the roads used to be, between them and a hard road) or hard (어려운길). Its {@link Shape} is what the planner, the
 * profile and the cross-section build differently, and what it pays (bus fares, the streak bonus, hikers at its stops, the
 * dock's unloading). Saved with the road (roads/&lt;dock&gt;.txt {@code character=}). A road saved without the line (built
 * before normal got harder, or before characters) is {@link #LEGACY}: labelled 보통 and paying as normal, but kept (its
 * repairs, its lights, its parts worked out again) to the shape it was built with; built again from scratch it is normal.
 * The shapes come from roads.characters.&lt;id&gt; ({@link #configure}), else these defaults. Pure (unit-tested).
 */
public enum RoadCharacter {
    EASY("easy", "쉬운길"), NORMAL("normal", "보통"), HARD("hard", "어려운길"),
    /** A 보통 road built before normal got harder: never chosen, only read (a road file without {@code character=}). */
    LEGACY("legacy", "보통");

    /** Its name in the config and the road file. */
    public final String id;
    /** Its name in the commands and messages. */
    public final String word;

    RoadCharacter(String id, String word) {
        this.id = id;
        this.word = word;
    }

    /**
     * What a character builds and pays (in brackets: normal's, then legacy's, the roads as they used to be).
     *
     * @param gradeRun    one block up or down in this many along at most (2.75 / 3; 2 .. 8)
     * @param climb       planner: cost a block of height changed (0.015 / 0.02)
     * @param steep       planner: cost a block over the grade (0.45 / 0.6)
     * @param turn        planner: cost each 45 degrees the route turns cell to cell (0: none)
     * @param straighten  planner: a straight line replaces the planned cells when it costs no more than this × theirs (1.01 / 1.02)
     * @param follow      profile: the ground is smoothed over this many points either way before the road is fitted to it
     *                    (3 / 4; fewer: the road follows the ground's crests and dips more)
     * @param bridgeGap   a bridge where the road runs more than this over the ground (4 / 3)
     * @param tunnelCover a tunnel where the ground stands this much over the road (6 / 5)
     * @param minRun      shorter bridges / tunnels are filled / cut open (4)
     * @param shoulder    the shoulder's width each side, blocks more (+) or fewer (-) than normal (-1 .. 1)
     * @param lightFactor street lights every roads.light-every × this (1.25 / 1)
     * @param reflectEvery a reflector post every this many points (24 / 20; 0: none)
     * @param railDrop    a guardrail where the shoulder stands this much or more over the ground (3)
     * @param bendScale   how much of the bend widening ({@link RoadClearance#bend}) the cross-section makes room for (0.8 / 1;
     *                    less: only what a careful driver slowing for the bend needs, a fast one scrapes)
     * @param rough       rough patches: more patched / cracked surface, a coarse edge here and there (cosmetic, full blocks)
     * @param warnings    warning signs (급커브 / 경사 / 낙석 주의) before sharp bends, steep grades and deep cuts
     * @param speedBonus  added to roads.speed-bonus on its surface
     * @param fare        bus fares to or from a stop on it ×
     * @param streak      the safe-driving streak bonus at such a stop ×
     * @param outdoors    hikers' and campers' weight at such a stop ×
     * @param dock        what its dock pays for unloading ×
     */
    public record Shape(double gradeRun, double climb, double steep, double turn, double straighten, int follow, int bridgeGap,
                        int tunnelCover, int minRun, int shoulder, double lightFactor, int reflectEvery, int railDrop, double bendScale,
                        boolean rough, boolean warnings, double speedBonus,
                        double fare, double streak, double outdoors, double dock) {

        /** The planner's weights ({@link RoadPlanner.Weights}): legacy's are {@link RoadPlanner.Weights#DEFAULT}. */
        public RoadPlanner.Weights weights() {
            RoadPlanner.Weights d = RoadPlanner.Weights.DEFAULT;
            return new RoadPlanner.Weights(climb, steep, d.rough(), d.water(), d.depth(), d.reuse(), d.beside(), gradeRun, turn, straighten);
        }

        /** The grade in half blocks ({@link RoadProfile.Grade}). */
        public RoadProfile.Grade grade() {
            return RoadProfile.Grade.of(gradeRun);
        }

        /** Street lights every this many points, for roads.light-every. */
        public int lightEvery(int every) {
            return lightFactor == 1 ? every : Math.max(8, (int) Math.round(every * lightFactor));
        }
    }

    /**
     * Hard roads' bend widening: only what the long vehicles need slowing for the bend (RoadBendTest / RoadCharacterTest:
     * every long vehicle at 4 b/s on its lane, 3.5 round a bend, gets round every hard shape clean; at 0.45 one already
     * touches a zigzag's wall); never under {@link #MIN_BEND}. Normal's: a careful driver at 5 b/s gets round every normal
     * shape clean.
     */
    static final double HARD_BEND = 0.5, NORMAL_BEND = 0.8, MIN_BEND = 0.5;

    /** The roads as they were built before normal got harder (RoadModule's, RoadProfile's and RoadSection's old constants). */
    static final Shape LEGACY_SHAPE = new Shape(RoadPlanner.GRADE_RUN, 0.02, 0.6, 0, RoadPlanner.STRAIGHTEN, RoadProfile.FOLLOW, RoadModule.BRIDGE_GAP,
            RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN, 0, 1, RoadSection.REFLECT_EVERY, RoadSection.RAIL_DROP, 1, false, false, 0, 1, 1, 1, 1);

    static final Map<RoadCharacter, Shape> DEFAULTS = new EnumMap<>(Map.of(
            EASY, new Shape(5, 0.04, 1.2, 0.5, 1.10, 6, 3, 4, 4, 1, 0.75, 12, 2, 1, false, false, 0.15, 1, 1, 1, 1),
            NORMAL, new Shape(2.75, 0.015, 0.45, 0, 1.01, 3, 4, 6, 4, 0, 1.25, 24, 3, NORMAL_BEND, false, false, 0, 1, 1, 1, 1),
            HARD, new Shape(2.25, 0.005, 0.3, 0, 1.0, 0, 6, 10, 10, -1, 3, 0, 4, HARD_BEND, true, true, 0, 1.2, 2, 2, 1),
            LEGACY, LEGACY_SHAPE));

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

    /**
     * The shapes from the config (a missing one: its default); null: the defaults. Legacy is never configured: it keeps
     * the shape the old roads were built with, and pays (and speeds) as normal does.
     */
    public static void configure(Map<RoadCharacter, Shape> m) {
        Map<RoadCharacter, Shape> out = new EnumMap<>(DEFAULTS);
        if (m != null) m.forEach((k, v) -> {
            if (k != LEGACY && v != null) out.put(k, clean(v));
        });
        Shape n = out.get(NORMAL), d = LEGACY_SHAPE;
        out.put(LEGACY, new Shape(d.gradeRun(), d.climb(), d.steep(), d.turn(), d.straighten(), d.follow(), d.bridgeGap(), d.tunnelCover(),
                d.minRun(), d.shoulder(), d.lightFactor(), d.reflectEvery(), d.railDrop(), d.bendScale(), d.rough(), d.warnings(),
                n.speedBonus(), n.fare(), n.streak(), n.outdoors(), n.dock()));
        shapes = out;
    }

    /** What it is called and chosen as: legacy is 보통 (normal). */
    public RoadCharacter label() {
        return this == LEGACY ? NORMAL : this;
    }

    /** A shape kept in bounds the builder can take. */
    static Shape clean(Shape s) {
        return new Shape(Math.clamp(s.gradeRun(), 2, 8), Math.max(0, s.climb()), Math.max(0, s.steep()), Math.max(0, s.turn()),
                Math.clamp(s.straighten(), 1, 1.5), Math.clamp(s.follow(), 0, 8), Math.clamp(s.bridgeGap(), 2, 12), Math.clamp(s.tunnelCover(), 3, 16),
                Math.clamp(s.minRun(), 1, 32), Math.clamp(s.shoulder(), -1, 1), Math.clamp(s.lightFactor(), 0.25, 4), Math.max(0, s.reflectEvery()),
                Math.clamp(s.railDrop(), 1, 8), Math.clamp(s.bendScale(), MIN_BEND, 1.5), s.rough(), s.warnings(),
                Math.clamp(s.speedBonus(), 0, 1), Math.max(0, s.fare()), Math.max(0, s.streak()), Math.max(0, s.outdoors()), Math.max(0, s.dock()));
    }

    /** A character by its word (쉬운길, 보통, 어려운길; also 쉬움 / 어려움) or id (easy, normal, hard); null: none. Never legacy. */
    public static RoadCharacter parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (RoadCharacter c : values()) if (c != LEGACY && (c.id.equals(t) || c.word.equals(t))) return c;
        return switch (t) {
            case "쉬움", "쉬운" -> EASY;
            case "어려움", "어려운", "산길" -> HARD;
            default -> null;
        };
    }

    /** As saved ({@link #id}); anything else (an older road's missing line): legacy. */
    public static RoadCharacter ofId(String s) {
        if (s != null && s.trim().equalsIgnoreCase(LEGACY.id)) return LEGACY;
        RoadCharacter c = parse(s);
        return c == null ? LEGACY : c;
    }
}
