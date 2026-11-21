package dev.stsv.economy.secret.fish;

import java.util.Collection;
import java.util.List;

/**
 * The eyeless fish's rules that need no server (unit-tested): which water each stage asks for, when a reel counts as
 * perfect, how much it has told, when the clever ones may skip ahead, how near the hook is to where it drowned, which
 * omens the player has earned, and which water is the deepest.
 * <p>
 * Stage s (1..7): the player holds the fish (not loose) and its release speaks memory s, or it is loose and waits in
 * water s ({@link #ORDER}). Done = 8.
 */
final class Waters {

    /** The waters in the order the memories name them; memory s names ORDER[s - 1]. The last is where it drowned. */
    static final List<String> ORDER = List.of("sea", "frozen", "cave", "swamp", "mushroom", "warm", "drowned");

    /** The last water: a place, not a habitat. */
    static final String DROWNED = "drowned";

    /** What the first syllables of the seven memories spell (처음 온 곳, 깊은 물): the shortcut to the last water. */
    static final String ACROSTIC = "처음온곳깊은물";

    /** The omens in the order they are earned (one more per release). */
    static final List<String> OMENS = List.of("bubbles", "heavy", "footprints", "figure", "name");

    private Waters() {}

    /** The water a stage asks for, or null (not started, or done). */
    static String forStage(int stage) {
        return stage >= 1 && stage <= ORDER.size() ? ORDER.get(stage - 1) : null;
    }

    /** Whether a hook's habitat (FishSpec.habitat) is one of a water's habitats. */
    static boolean matches(Collection<String> habitats, String habitat) {
        return habitat != null && habitats != null && habitats.contains(habitat);
    }

    /** How many memories it has spoken: the one of this stage too once it is loose. */
    static int told(int stage, boolean loose) {
        if (stage <= 0) return 0;
        if (stage > ORDER.size()) return ORDER.size();
        return loose ? stage : stage - 1;
    }

    /** A reel in time: within window ticks of the bite (biteTick 0 or less = no bite seen). */
    static boolean perfect(long biteTick, long nowTick, int window) {
        return biteTick > 0 && nowTick >= biteTick && nowTick - biteTick <= window;
    }

    /**
     * Whether a catch at the drowned place may end it now: loose, and either at the last stage or (the shortcut) far enough
     * along that the first syllables already spell where (from: the earliest stage that may skip).
     */
    static boolean mayEnd(int stage, boolean loose, int from) {
        if (!loose || stage < 1 || stage > ORDER.size()) return false;
        return stage == ORDER.size() || stage >= Math.max(1, from);
    }

    /** How near the hook is to the drowned place. */
    enum Near { HERE, CLOSE, NEAR, FAR }

    /** HERE within radius, CLOSE within close, NEAR within near (blocks across the map); dist &lt; 0 = another world. */
    static Near near(double dist, double radius, double close, double nearer) {
        if (dist < 0) return Near.FAR;
        if (dist <= radius) return Near.HERE;
        if (dist <= close) return Near.CLOSE;
        if (dist <= nearer) return Near.NEAR;
        return Near.FAR;
    }

    /** The omens a player has earned after this many memories (one per memory, five at most). */
    static List<String> omens(int told) {
        return OMENS.subList(0, Math.clamp(told, 0, OMENS.size()));
    }

    /** The new omen a release brings (memory n = the n-th omen), or null past the fifth. */
    static String newOmen(int told) {
        return told >= 1 && told <= OMENS.size() ? OMENS.get(told - 1) : null;
    }

    /** The deeper water wins; of two as deep, the one nearer the spawn. */
    static boolean deeper(int depth, double distSq, int bestDepth, double bestDistSq) {
        return depth > bestDepth || depth == bestDepth && distSq < bestDistSq;
    }

    /** The first syllable of a memory line, past any MiniMessage tags, dots, spaces and quotes; ' ' when there is none. */
    static char first(String line) {
        if (line == null) return ' ';
        boolean tag = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '<') tag = true;
            else if (c == '>' && tag) tag = false;
            else if (!tag && !Character.isWhitespace(c) && "….·\"'“”‘’-—~".indexOf(c) < 0) return c;
        }
        return ' ';
    }

    /** The first syllables of the memories, together. */
    static String acrostic(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(first(l));
        return b.toString();
    }

    /** "world x y z depth" for eco.kv (the drowned place). */
    static String encode(String world, int x, int y, int z, int depth) {
        return world + " " + x + " " + y + " " + z + " " + depth;
    }

    /** Back from {@link #encode} (or "world x y z" from the settings): {world, x, y, z, depth} or null. */
    static Object[] decode(String s) {
        if (s == null || s.isBlank()) return null;
        String[] w = s.trim().split("\\s+");
        if (w.length < 4) return null;
        try {
            return new Object[]{w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]),
                    w.length > 4 ? Integer.parseInt(w[4]) : -1};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
