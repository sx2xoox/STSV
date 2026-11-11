package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where a 국도 never goes (README "국도", 보호): the planner never routes through it and the builder never cuts, fills or
 * places a block in it. Pure (unit-tested); {@link RoadModule} gives it the land and the docks.
 *
 * <ul>
 *   <li>{@link Why#PLAYER}: a player's land (a whole chunk, as land is claimed).</li>
 *   <li>{@link Why#SERVER}: the server's land (the spawn town and the like), but for the chunks that are open to the road
 *       ({@link Open}): the target dock's and the departure terminal's own chunks, so the road still reaches the gate and
 *       leaves the terminal's exit.</li>
 *   <li>{@link Why#AVOID}: a box of roads.avoid (world, x1, z1, x2, z2: a protected build without land), by block; the
 *       planner keeps out of every chunk a box touches.</li>
 * </ul>
 */
public final class RoadGuard {

    /** Why a place is out of bounds; its code is what a surveyed cell keeps ({@link RoadJob.Cell#why}). */
    public enum Why {
        PLAYER(1, "개인 땅"), SERVER(2, "서버 땅"), AVOID(3, "보호 구역");

        public final int code;
        public final String word;

        Why(int code, String word) {
            this.code = code;
            this.word = word;
        }

        /** The reason for a cell's code, or null (0, or unknown). */
        public static Why of(int code) {
            for (Why w : values()) if (w.code == code) return w;
            return null;
        }
    }

    /** Who owns a chunk: 0 nobody, 1 a player, 2 the server. */
    public interface Lands {
        int owner(String world, int cx, int cz);
    }

    /** Server-land chunks the road may still use (the target dock's, the terminal it leaves from). */
    public interface Open {
        boolean open(String world, int cx, int cz);
    }

    /** A box of roads.avoid (blocks, both corners included, any height). */
    public record Box(String world, int x1, int z1, int x2, int z2) {
        public Box {
            int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
            x1 = ax;
            x2 = bx;
            z1 = az;
            z2 = bz;
        }

        public boolean contains(String w, int x, int z) {
            return world.equals(w) && x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        /** Whether it reaches into a chunk. */
        public boolean touches(String w, int cx, int cz) {
            return world.equals(w) && x2 >= cx << 4 && x1 <= (cx << 4) + 15 && z2 >= cz << 4 && z1 <= (cz << 4) + 15;
        }
    }

    private final Lands lands;
    private final Open open;
    private final List<Box> avoid;

    public RoadGuard(Lands lands, Open open, List<Box> avoid) {
        this.lands = lands;
        this.open = open;
        this.avoid = List.copyOf(avoid);
    }

    /** Nothing protected (tests). */
    public static final RoadGuard NONE = new RoadGuard((w, x, z) -> 0, (w, x, z) -> false, List.of());

    /** The same land and boxes, other chunks open. */
    public RoadGuard open(Open o) {
        return new RoadGuard(lands, o, avoid);
    }

    public List<Box> avoid() {
        return avoid;
    }

    /** The land's reason for a chunk (no boxes), or null. */
    public Why land(String world, int cx, int cz) {
        int o = lands.owner(world, cx, cz);
        if (o == 1) return Why.PLAYER;
        if (o == 2 && !open.open(world, cx, cz)) return Why.SERVER;
        return null;
    }

    /** For the planner: why a whole chunk is out (its land, or a box reaching into it), or null. */
    public Why chunk(String world, int cx, int cz) {
        Why w = land(world, cx, cz);
        if (w != null) return w;
        for (Box b : avoid) if (b.touches(world, cx, cz)) return Why.AVOID;
        return null;
    }

    /** For the builder: why a block column is out, or null. */
    public Why column(String world, int x, int z) {
        Why w = land(world, x >> 4, z >> 4);
        if (w != null) return w;
        for (Box b : avoid) if (b.contains(world, x, z)) return Why.AVOID;
        return null;
    }

    /** Whether no box reaches into a chunk (then its columns are all as its land says). */
    public boolean boxFree(String world, int cx, int cz) {
        for (Box b : avoid) if (b.touches(world, cx, cz)) return false;
        return true;
    }

    /**
     * roads.avoid as read from the config: a list of maps with world, x1, z1, x2, z2. A broken entry is left out (warned).
     */
    public static List<Box> boxes(List<Map<?, ?>> list, Consumer<String> warn) {
        List<Box> out = new ArrayList<>();
        if (list == null) return out;
        for (Map<?, ?> m : list) {
            try {
                Object w = m.get("world");
                if (w == null) throw new IllegalArgumentException("world 없음");
                out.add(new Box(String.valueOf(w), num(m, "x1"), num(m, "z1"), num(m, "x2"), num(m, "z2")));
            } catch (RuntimeException e) {
                warn.accept("[국도] roads.avoid 항목을 읽지 못함 (" + m + "): " + e.getMessage());
            }
        }
        return out;
    }

    private static int num(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return (int) Math.floor(n.doubleValue());
        if (v == null) throw new IllegalArgumentException(k + " 없음");
        return (int) Math.floor(Double.parseDouble(String.valueOf(v).trim()));
    }
}
