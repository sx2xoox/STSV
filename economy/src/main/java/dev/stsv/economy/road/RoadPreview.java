package dev.stsv.economy.road;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /관리자 국도 &lt;하역장&gt; 미리보기 (README "국도", 미리보기): what a planned road would be ({@link #summary}) and the
 * builds without land along it that it could hurt ({@link Scan}). Pure (unit-tested): {@link RoadModule} reads the world.
 *
 * <p><b>Builds without land</b> (the heuristic): every block of the road's footprint plus {@link #MARGIN} (out to
 * {@link #SCAN_REACH} from its middle), from the top of each column down {@link #SCAN_DEPTH} blocks, outside any land and
 * any road already built, is looked at. A block counts when a player most likely put it ({@link #kind}): planks, glass,
 * beds, doors, chests and barrels, crafting tables, furnaces, wool carpets, torches, lanterns, wool, bricks, concrete,
 * terracotta (not in a badlands biome, where it is the ground; glazed always), stairs and slabs, signs. A block inside a
 * piece of a naturally generated structure (a village's houses, ruins, mineshafts, shipwrecks, igloos, outposts: the
 * server's own structure pieces, {@code Chunk#getStructures}) does not count. A chunk with {@link #CLUSTER_MIN} or more
 * counted blocks is a likely build: its middle (the counted blocks' average) and what it is made of are listed. A few torches
 * or a lone sign stay under it; a build inside a village's pieces is missed.
 */
public final class RoadPreview {

    private RoadPreview() {}

    /** What a counted block is (its Korean word in the warning). */
    public enum Kind {
        PLANKS("판자"), GLASS("유리"), BED("침대"), DOOR("문"), CHEST("상자"), CRAFTING("제작대"), FURNACE("화로"), CARPET("카펫"),
        TORCH("횃불"), LANTERN("랜턴"), WOOL("양털"), BRICKS("벽돌"), CONCRETE("콘크리트"), TERRACOTTA("테라코타"),
        STAIRS("계단·반 블록"), SIGN("표지판");

        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    /** How far past the road's footprint (its shoulders, widened at the gate) the scan looks. */
    public static final int MARGIN = 3;
    /** Out to how far from the road's middle (blocks): the shoulders (5.5), the gate's widening (2), the margin. */
    public static final double SCAN_REACH = RoadSection.HALF + 0.5 + RoadSection.WIDEN + MARGIN;
    /** How deep under a column's top block the scan goes. */
    public static final int SCAN_DEPTH = 24;
    /** Counted blocks in a chunk from which it is listed as a likely build. */
    public static final int CLUSTER_MIN = 6;

    /** A block a player most likely put, or null. badlands: the block stands in a badlands biome (terracotta there is ground). */
    public static Kind kind(Material m, boolean badlands) {
        String n = m.name();
        if (n.endsWith("_PLANKS")) return Kind.PLANKS;
        if (n.equals("GLASS") || n.endsWith("_GLASS") || n.endsWith("GLASS_PANE")) return Kind.GLASS;
        if (n.endsWith("_BED")) return Kind.BED;
        if (n.endsWith("_DOOR")) return Kind.DOOR;
        if (n.equals("CHEST") || n.equals("TRAPPED_CHEST") || n.equals("BARREL")) return Kind.CHEST;
        if (n.equals("CRAFTING_TABLE")) return Kind.CRAFTING;
        if (n.equals("FURNACE") || n.equals("BLAST_FURNACE") || n.equals("SMOKER")) return Kind.FURNACE;
        if (n.endsWith("_CARPET")) return n.contains("MOSS") ? null : Kind.CARPET;   // moss carpets grow
        if (n.endsWith("TORCH") && !n.contains("REDSTONE") && !n.contains("FLOWER")) return Kind.TORCH;   // torchflowers grow
        if (n.endsWith("LANTERN") && !n.equals("JACK_O_LANTERN")) return Kind.LANTERN;
        if (n.endsWith("_WOOL")) return Kind.WOOL;
        if (n.contains("SIGN")) return Kind.SIGN;
        if (n.contains("TERRACOTTA")) {
            if (n.contains("GLAZED")) return Kind.TERRACOTTA;
            return badlands ? null : Kind.TERRACOTTA;
        }
        if (n.endsWith("_STAIRS") || n.endsWith("_SLAB")) return Kind.STAIRS;
        if (n.contains("CONCRETE")) return Kind.CONCRETE;
        if (n.endsWith("BRICKS")) return Kind.BRICKS;
        return null;
    }

    /** {@link #kind}(m, false) by material, worked out once each (the scan looks at thousands of blocks a chunk). */
    private static final Kind[] CACHE = new Kind[Material.values().length];
    private static final boolean[] KNOWN = new boolean[Material.values().length];

    /** What a block may be, its biome not looked at yet (terracotta: ask {@link #kind} with the biome). */
    public static Kind cached(Material m) {
        int o = m.ordinal();
        if (!KNOWN[o]) {
            CACHE[o] = kind(m, false);
            KNOWN[o] = true;
        }
        return CACHE[o];
    }

    /** A generated structure's piece (block box, both corners included). */
    public record Piece(int x1, int y1, int z1, int x2, int y2, int z2) {
        public boolean contains(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    /** A likely build: the middle of its counted blocks, how many, the kinds by count, how far along the road (points). */
    public record Cluster(int x, int y, int z, int blocks, Map<Kind, Integer> kinds, int along) {
        /** Its kinds, most first (the first three), in Korean. */
        public String words() {
            List<Map.Entry<Kind, Integer>> es = new ArrayList<>(kinds.entrySet());
            es.sort(Map.Entry.<Kind, Integer>comparingByValue().reversed());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(3, es.size()); i++) b.append(i > 0 ? ", " : "").append(es.get(i).getKey().word).append(' ').append(es.get(i).getValue());
            return b.toString();
        }
    }

    /** The counted blocks, chunk by chunk, as the scan finds them; {@link #clusters} at the end. */
    public static final class Scan {
        private static final class Acc {
            long sx, sy, sz;
            int n, along = Integer.MAX_VALUE;
            final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        }

        private final Map<Long, Acc> chunks = new HashMap<>();

        /**
         * A block in the scanned corridor (outside land and old roads), its biome badlands or not, the structure pieces of
         * its chunk, the road point it is nearest. True when it counted.
         */
        public boolean add(int x, int y, int z, Material m, boolean badlands, List<Piece> pieces, int along) {
            Kind k = kind(m, badlands);
            if (k == null) return false;
            for (Piece p : pieces) if (p.contains(x, y, z)) return false;   // generated with the world
            Acc a = chunks.computeIfAbsent(RoadJob.key(x >> 4, z >> 4), c -> new Acc());
            a.sx += x;
            a.sy += y;
            a.sz += z;
            a.n++;
            a.along = Math.min(a.along, along);
            a.kinds.merge(k, 1, Integer::sum);
            return true;
        }

        /** The chunks with at least min counted blocks, in the road's order. */
        public List<Cluster> clusters(int min) {
            List<Cluster> out = new ArrayList<>();
            for (Acc a : chunks.values()) {
                if (a.n < min) continue;
                out.add(new Cluster((int) Math.floorDiv(a.sx, a.n), (int) Math.floorDiv(a.sy, a.n), (int) Math.floorDiv(a.sz, a.n), a.n,
                        new EnumMap<>(a.kinds), a.along));
            }
            out.sort(Comparator.comparingInt(Cluster::along));
            return out;
        }
    }

    /**
     * What the preview tells: the road's length (points, a block apart), its corners, its bridges and tunnels (how many
     * and how long), about how many blocks it cuts and fills ({@link #WIDTH} wide; a tunnel's bore apart), the chunks it
     * passes through, the land chunks near it.
     */
    public record Summary(int length, int corners, int bridges, int bridgeLength, int tunnels, int tunnelLength, long cut, long fill, long bore,
                          int chunks, int playerChunks, int serverChunks) {}

    /** How wide the cut and fill is reckoned (blocks: the surface, kerbs and shoulders). */
    public static final int WIDTH = 2 * RoadSection.HALF + 1;
    /** A tunnel's bore across and up (blocks): its lanes, kerbs and walls; the surface and 4 of air. */
    public static final int BORE_WIDE = 9, BORE_HIGH = 5;
    /** How near the road (blocks) land chunks are counted. */
    public static final int LAND_NEAR = 16;

    /**
     * The summary from the planned road: its points, their ground (top block), its surface (half blocks,
     * {@link RoadProfile#halves}) and parts, its corners, and who owns a chunk (0 nobody, 1 a player, 2 the server, not
     * counting the chunks open to it).
     */
    public static Summary summary(List<double[]> points, int[] ground, int[] halves, RoadProfile.Part[] parts, int corners, RoadGuard.Lands lands,
                                  String world) {
        int n = points.size();
        int bridges = 0, bridgeLen = 0, tunnels = 0, tunnelLen = 0;
        long cut = 0, fill = 0, bore = 0;
        for (int i = 0; i < n; i++) {
            RoadProfile.Part p = parts[i];
            boolean starts = i == 0 || parts[i - 1] != p;
            switch (p) {
                case BRIDGE -> {
                    bridgeLen++;
                    if (starts) bridges++;
                }
                case TUNNEL -> {
                    tunnelLen++;
                    if (starts) tunnels++;
                    bore += (long) BORE_WIDE * BORE_HIGH;
                }
                case ROAD -> {
                    int y = RoadSection.surfaceY(halves[i]);
                    cut += (long) Math.max(0, ground[i] - y) * WIDTH;
                    fill += (long) Math.max(0, y - 1 - ground[i]) * WIDTH;
                }
            }
        }
        Set<Long> chunks = new HashSet<>();
        for (double[] p : points) chunks.add(RoadJob.key((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4));
        int[] land = landNear(points, LAND_NEAR, lands, world);
        return new Summary(n, corners, bridges, bridgeLen, tunnels, tunnelLen, cut, fill, bore, chunks.size(), land[0], land[1]);
    }

    /** How many player's (0) and server's (1) land chunks have a block within `near` of a point of the road. */
    public static int[] landNear(List<double[]> points, int near, RoadGuard.Lands lands, String world) {
        Set<Long> seen = new HashSet<>();
        int[] out = new int[2];
        for (double[] p : points) {
            int x0 = (int) Math.floor(p[0] - near) >> 4, x1 = (int) Math.floor(p[0] + near) >> 4;
            int z0 = (int) Math.floor(p[1] - near) >> 4, z1 = (int) Math.floor(p[1] + near) >> 4;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    long k = RoadJob.key(cx, cz);
                    if (seen.contains(k)) continue;
                    // the chunk's nearest block to the point
                    double nx = Math.clamp(p[0], cx * 16, cx * 16 + 15.999), nz = Math.clamp(p[1], cz * 16, cz * 16 + 15.999);
                    if (Math.hypot(nx - p[0], nz - p[1]) > near) continue;
                    seen.add(k);
                    int o = lands.owner(world, cx, cz);
                    if (o == 1) out[0]++;
                    else if (o == 2) out[1]++;
                }
            }
        }
        return out;
    }
}
