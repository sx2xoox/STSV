package dev.stsv.economy.road;

import dev.stsv.core.vehicle.DriveInput;
import dev.stsv.core.vehicle.VehiclePhysics;
import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 높이 여유 ({@link RoadClearance}): every vehicle in the core's content (content/stsv/items/vehicles*.yml, read here,
 * never written down) fits under what a road builds, driven by the vehicles' own physics (VehiclePhysics) through it:
 * <ul>
 *   <li>the constants cover the tallest and longest vehicle and the highest step;</li>
 *   <li>a tunnel going up and down at 1 in 3 (the owner's: the box truck stuck coming down): as roads were built before
 *       ({@link RoadClearance#legacy}) the box truck sticks; as built now, and as {@link RoadSection#rebore} mends the old
 *       one, every vehicle drives through both ways;</li>
 *   <li>the mending only touches the bore over the old ceiling, only the ground's and the tunnel's own blocks, and only
 *       on a grade (a flat tunnel is left as it is);</li>
 *   <li>the whole preview strip (RoadPreviewTest: street lights, a hill, a bridge, the tunnel in the steep hill) and the
 *       older road under the overpass, driven both ways.</li>
 * </ul>
 */
class RoadClearanceTest {

    static final Path ITEMS = Path.of("../content/stsv/items").toAbsolutePath().normalize();

    /** Every vehicle section (top level) in vehicles*.yml, by id. */
    static Map<String, ConfigurationSection> sections() throws IOException {
        Map<String, ConfigurationSection> out = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(ITEMS)) {
            files = s.filter(p -> p.getFileName().toString().matches("vehicles.*\\.yml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no vehicles*.yml in " + ITEMS);
        for (Path f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
            for (String id : y.getKeys(false)) {
                ConfigurationSection v = y.getConfigurationSection(id + ".vehicle");
                if (v != null) out.put(id, v);
            }
        }
        assertTrue(out.containsKey("box_truck"), "the box truck is read");
        return out;
    }

    /** Every collision size [length, width, height] in a vehicle section: its own and its trailers'. */
    static List<List<Double>> sizes(ConfigurationSection v) {
        List<List<Double>> out = new ArrayList<>();
        out.add(v.getDoubleList("size"));
        for (Map<?, ?> t : v.getMapList("train.trailers")) {
            if (t.get("size") instanceof List<?> l) {
                List<Double> d = new ArrayList<>();
                for (Object o : l) d.add(((Number) o).doubleValue());
                out.add(d);
            }
        }
        return out;
    }

    /** The vehicles that drive on land (not the boat), parsed as the core does. */
    static Map<String, VehicleSpec> landVehicles() throws IOException {
        Map<String, VehicleSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigurationSection> e : sections().entrySet()) {
            VehicleSpec s = VehicleSpec.parse(e.getValue(), "stsv", m -> true);
            if (s.water() == null) out.put(e.getKey(), s);
        }
        return out;
    }

    @Test
    void theClearanceCoversTheTallestAndLongestVehicleAndItsStep() throws IOException {
        double tallest = 0, longest = 0, step = 0;
        String who = "";
        for (Map.Entry<String, ConfigurationSection> e : sections().entrySet()) {
            // boats (vehicle.water) never drive on a road: the trawler is longer and taller than any road is cut for
            if (e.getValue().isConfigurationSection("water")) {
                assertEquals(3, e.getValue().getDoubleList("size").size(), e.getKey() + ": size");
                continue;
            }
            for (List<Double> s : sizes(e.getValue())) {
                assertEquals(3, s.size(), e.getKey() + ": size");
                if (s.get(2) > tallest) who = e.getKey();
                tallest = Math.max(tallest, s.get(2));
                longest = Math.max(longest, s.get(0));
            }
            step = Math.max(step, e.getValue().getDouble("step-height", 1.0));
        }
        assertTrue(RoadClearance.HEIGHT >= tallest, "RoadClearance.HEIGHT " + RoadClearance.HEIGHT + " < " + who + " " + tallest);
        assertTrue(RoadClearance.MARGIN >= step, "RoadClearance.MARGIN < the highest step " + step);
        assertTrue(RoadClearance.LENGTH >= longest, "RoadClearance.LENGTH < the longest vehicle " + longest);
        assertTrue(RoadClearance.REACH >= Math.ceil(longest) + 1, "the reach covers a vehicle's length");
        // on the flat: 4 clear over a full block (the ceiling 5 over the surface's block), 4.5 over a slab
        int[] flat = RoadClearance.ceilings(new int[]{128, 128, 128});
        assertEquals(64 + 5, flat[1]);
        assertTrue(flat[1] - RoadSection.top(128) >= tallest + step);
        int[] slab = RoadClearance.ceilings(new int[]{129, 129, 129});
        assertTrue(slab[1] - RoadSection.top(129) >= tallest + step);
        // on a grade: over the highest surface a vehicle's length away
        int[] ramp = new int[40];
        for (int i = 0; i < ramp.length; i++) ramp[i] = 128 + Math.max(0, 20 - i) * 2 / 3;   // coming down at 1 in 3
        int[] c = RoadClearance.ceilings(ramp);
        for (int i = 0; i < ramp.length; i++) {
            double high = Double.NEGATIVE_INFINITY;
            for (int j = Math.max(0, i - (int) Math.ceil(longest)); j <= Math.min(ramp.length - 1, i + (int) Math.ceil(longest)); j++)
                high = Math.max(high, RoadSection.top(ramp[j]));
            assertTrue(c[i] - high >= tallest + step - 1e-9, "the ceiling at " + i + " over the highest surface a vehicle's length away");
        }
        // a deck over another road: 6 clear over the old road's highest surface near the crossing
        assertTrue(RoadNetwork.CLEARANCE - 1 >= tallest + step);
        assertTrue(RoadNetwork.HEADROOM_REACH >= Math.ceil(longest) + RoadSection.HALF);
    }

    // ------------------------------------------------------------------------------------------
    // A world of blocks the vehicles drive in
    // ------------------------------------------------------------------------------------------

    /** Collision boxes of a block as the server has them (near enough: walls a full 1.5 high column, a lantern its lowest box). */
    static void boxes(Material m, int x, int y, int z, List<double[]> out) {
        if (m == null || isAirBlock(m) || m == Material.WATER || m == Material.LAVA || m == Material.SHORT_GRASS || m == Material.TALL_GRASS
                || m == Material.SNOW || m == Material.VINE || m == Material.SPRUCE_SIGN) return;
        String n = m.name();
        if (n.endsWith("_SLAB")) out.add(new double[]{x, y, z, x + 1, y + 0.5, z + 1});
        else if (n.endsWith("_WALL") || n.endsWith("_FENCE")) out.add(new double[]{x, y, z, x + 1, y + 1.5, z + 1});
        else if (m == Material.LANTERN) out.add(new double[]{x + 0.3125, y, z + 0.3125, x + 0.6875, y + 0.625, z + 0.6875});
        else if (m == Material.END_ROD) out.add(new double[]{x + 0.375, y, z + 0.375, x + 0.625, y + 1, z + 0.625});
        else if (m == Material.IRON_TRAPDOOR) out.add(new double[]{x, y + 0.8125, z, x + 1, y + 1, z + 1});
        else if (m == Material.DIRT_PATH) out.add(new double[]{x, y, z, x + 1, y + 0.9375, z + 1});
        else out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
    }

    interface Blocks {
        Material at(int x, int y, int z);
    }

    static VehiclePhysics.Terrain terrain(Blocks w) {
        return new VehiclePhysics.Terrain() {
            public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
                    for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++)
                        for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) RoadClearanceTest.boxes(w.at(x, y, z), x, y, z, out);
            }

            public VehiclePhysics.Medium medium(int x, int y, int z) {
                return w.at(x, y, z) == Material.WATER ? VehiclePhysics.Medium.WATER : VehiclePhysics.Medium.AIR;
            }
        };
    }

    /**
     * Drives a vehicle straight from (x0, z) to x1 along x (W held), set down on the ground there. Null: it got there;
     * else where it stuck.
     */
    static String drive(VehicleSpec s, VehiclePhysics.Terrain t, Blocks w, double x0, double x1, double z) {
        return driveAlong(s, t, w, x0, x1, z, true);
    }

    /** As {@link #drive}, along x (alongX) or along z (then x0, x1 are z and z is the x it runs at). */
    static String driveAlong(VehicleSpec s, VehiclePhysics.Terrain t, Blocks w, double from, double to, double across, boolean alongX) {
        return driveAlong(s, t, w, from, to, across, alongX, 300);
    }

    /** As above, set down on what is under fromY (in a tunnel: under its ceiling). */
    static String driveAlong(VehicleSpec s, VehiclePhysics.Terrain t, Blocks w, double from, double to, double across, boolean alongX, int fromY) {
        float yaw = alongX ? (to > from ? 270f : 90f) : (to > from ? 0f : 180f);
        double x = alongX ? from : across, z = alongX ? across : from;
        int y = fromY;
        while (y > -64 && isOpen(w.at((int) Math.floor(x), y, (int) Math.floor(z)))) y--;
        VehiclePhysics.State st = new VehiclePhysics.State(x, y + 1.01, z, yaw);
        DriveInput go = new DriveInput(true, false, false, false, false, false);
        double best = 0;
        int since = 0;
        for (int tick = 0; tick < 40_000; tick++) {
            VehiclePhysics.step(st, go, s, t, 20);
            double done = Math.abs((alongX ? st.x : st.z) - from);
            if (done >= Math.abs(to - from)) return null;
            if (done > best + 0.05) {
                best = done;
                since = 0;
            } else if (++since > 100) {
                return String.format(java.util.Locale.ROOT, "stuck at (%.2f, %.2f, %.2f)", st.x, st.y, st.z);
            }
        }
        return "too slow";
    }

    static boolean isAirBlock(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    static boolean isOpen(Material m) {
        List<double[]> b = new ArrayList<>();
        boxes(m, 0, 0, 0, b);
        return b.isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // A tunnel up and down at 1 in 3
    // ------------------------------------------------------------------------------------------

    /** A hill of stone the tunnel runs through (x 14 .. LEN - 14), flat land round it at 63; what was put over it. */
    static final class Hill implements RoadSection.Site {
        static final int LEN = 110;
        final Map<Long, Material> put = new HashMap<>();
        /** what was changed (x, y, z), in order, when recording */
        List<int[]> log;

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        static Material base(int x, int y, int z) {
            int top = x >= 14 && x <= LEN - 14 ? 95 : 63;
            return y <= top ? Material.STONE : Material.AIR;
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            return m != null ? m : base(x, y, z);
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return isAirBlock(get(x, y, z));
        }

        public boolean liquid(int x, int y, int z) {
            return get(x, y, z) == Material.WATER;
        }

        public boolean natural(int x, int y, int z) {
            return false;
        }

        public boolean fillable(int x, int y, int z) {
            return isAirBlock(get(x, y, z)) || get(x, y, z) == Material.WATER;
        }

        public void set(int x, int y, int z, Material m) {
            if (!inside(y) || get(x, y, z) == m) return;
            put.put(key(x, y, z), m);
            if (log != null) log.add(new int[]{x, y, z});
        }

        public boolean protectedLand(int x, int z) {
            return false;
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}
    }

    /** The road's surface (half blocks): flat at 64, up 3 blocks at 1 in 3, a short top, down again, flat. */
    static int[] upAndDown() {
        double[] p = new double[Hill.LEN];
        for (int i = 0; i < p.length; i++) p[i] = 64 + Math.clamp(Math.min(i - 30, 80 - i) / 3.0, 0, 3);
        return RoadProfile.halves(p);
    }

    static List<double[]> line() {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < Hill.LEN; i++) pts.add(new double[]{i + 0.5, 0.5});
        return pts;
    }

    static RoadProfile.Part[] tunnelParts() {
        RoadProfile.Part[] parts = new RoadProfile.Part[Hill.LEN];
        for (int i = 0; i < parts.length; i++) parts[i] = i >= 15 && i <= Hill.LEN - 16 ? RoadProfile.Part.TUNNEL : RoadProfile.Part.ROAD;
        return parts;
    }

    static RoadSection tunnel(Hill hill, boolean legacy) {
        int[] h = upAndDown();
        RoadSection s = new RoadSection(hill, RoadPreviewTest.STYLE, line(), h, tunnelParts(), "북해", 3);
        if (legacy) s.ceilings(RoadClearance.legacy(h));
        for (int i = 0; i < Hill.LEN; i++) assertNull(s.build(i));
        return s;
    }

    /** Both ways in the right lane, and down the middle. */
    static List<String> through(VehicleSpec s, Hill hill) {
        List<String> out = new ArrayList<>();
        VehiclePhysics.Terrain t = terrain(hill::get);
        String a = drive(s, t, hill::get, 4, Hill.LEN - 5, 0.5 + 1.25), b = drive(s, t, hill::get, Hill.LEN - 5, 4, 0.5 - 1.25),
                c = drive(s, t, hill::get, 4, Hill.LEN - 5, 0.5);
        if (a != null) out.add("east " + a);
        if (b != null) out.add("west " + b);
        if (c != null) out.add("middle " + c);
        return out;
    }

    @Test
    void theOldTunnelStopsTheBoxTruckComingDown() throws IOException {
        VehicleSpec truck = landVehicles().get("box_truck");
        Hill hill = new Hill();
        tunnel(hill, true);
        VehiclePhysics.Terrain t = terrain(hill::get);
        // as the owner saw it: set down on the top (x 55) and driven east, down the grade (x 71 .. 80): its rear still on
        // the higher road, its box reaches under the ceiling that already came down with the road under its front
        int under = RoadSection.surfaceY(upAndDown()[55]) + 4;
        String down = driveAlong(truck, t, hill::get, 55, Hill.LEN - 5, 0.5 + 1.25, true, under);
        assertTrue(down != null, "the old tunnel (4 clear, stepping down with the road) stops the box truck coming down");
        double x = Double.parseDouble(down.substring(down.indexOf('(') + 1, down.indexOf(',')));
        assertTrue(x > 60 && x < 80, "where the road comes down: " + down);
        // going up it sticks as well (its front lifted onto the higher road, its rear under the lower ceiling)
        assertTrue(drive(truck, t, hill::get, 4, 50, 0.5 + 1.25) != null);
        // the smaller ones got through it (only the tallest and longest did not)
        assertEquals(List.of(), through(landVehicles().get("police_car"), hill));
    }

    @Test
    void everyVehicleDrivesThroughATunnelAsBuiltNow() throws IOException {
        Hill hill = new Hill();
        RoadSection s = tunnel(hill, false);
        for (Map.Entry<String, VehicleSpec> e : landVehicles().entrySet()) assertEquals(List.of(), through(e.getValue(), hill), e.getKey());
        // it still looks like a tunnel: a stone brick ceiling with lights flush in it, lined walls up to it
        boolean lit = false;
        for (int i = 15; i <= Hill.LEN - 16; i++) {
            int c = s.ceiling(i), y = RoadSection.surfaceY(upAndDown()[i]);
            assertTrue(c >= y + 5);
            Material m = hill.get(i, c, 0);
            assertTrue(m == Material.STONE_BRICKS || m == Material.SEA_LANTERN, "the ceiling at " + i + ": " + m);
            lit |= m == Material.SEA_LANTERN;
            for (int k = y + 1; k < c; k++) assertTrue(isAirBlock(hill.get(i, k, 0)), "the bore at " + i + ", " + k);
            for (int k = y + 5; k <= c; k++) assertEquals(Material.STONE_BRICKS, hill.get(i, k, -4), "the wall at " + i + ", " + k);
        }
        assertTrue(lit);
        // the flat stretch is as it always was: 4 clear over the surface block
        assertEquals(RoadSection.surfaceY(upAndDown()[20]) + 5, s.ceiling(20));
    }

    @Test
    void anOldTunnelIsReboredAndOnlyItsBoreIsTouched() throws IOException {
        Hill hill = new Hill();
        RoadSection old = tunnel(hill, true);
        int[] h = upAndDown();
        // a player's chest in the rock over the wall where the mended wall would go, one past the shoulders
        int top = 36;   // on the way up: the new ceiling is higher here
        int wallY = RoadSection.surfaceY(h[top]) + 6;
        RoadSection fresh = new RoadSection(new Hill(), RoadPreviewTest.STYLE, line(), h, tunnelParts(), "북해", 3);
        assertTrue(fresh.ceiling(top) > RoadSection.surfaceY(h[top]) + 5, "the ceiling rises here");
        hill.set(top, wallY, -4, Material.CHEST);
        hill.set(top + 1, wallY, -7, Material.CHEST);
        hill.log = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < Hill.LEN; i++) n += rebore(hill, h, i);
        assertTrue(n > 0, "the tunnel was mended");
        assertEquals(Material.CHEST, hill.get(top, wallY, -4), "a player's block is never taken");
        assertEquals(Material.CHEST, hill.get(top + 1, wallY, -7));
        RoadProfile.Part[] parts = tunnelParts();
        for (int[] c : hill.log) {
            int i = c[0];   // the road runs along x, a point a block
            assertEquals(RoadProfile.Part.TUNNEL, parts[i], "only tunnels: " + java.util.Arrays.toString(c));
            assertTrue(Math.abs(c[2] + 0.5 - 0.5) <= 5.5, "only the bore and its walls: " + java.util.Arrays.toString(c));
            assertTrue(c[1] >= RoadSection.surfaceY(h[i]) + 5, "never under the old ceiling: " + java.util.Arrays.toString(c));
            assertTrue(c[1] <= Math.max(fresh.ceiling(i), Math.max(fresh.ceiling(Math.min(Hill.LEN - 1, i + 1)), fresh.ceiling(Math.max(0, i - 1)))) + 1,
                    "never over the new ceiling: " + java.util.Arrays.toString(c));
        }
        // the flat parts of the tunnel are left as they were
        for (int[] c : hill.log) assertTrue(c[0] >= 30 - RoadClearance.REACH - 1 && c[0] <= 80 + RoadClearance.REACH + 1, "flat: untouched " + c[0]);
        // done twice: nothing more
        hill.log = new ArrayList<>();
        for (int i = 0; i < Hill.LEN; i++) rebore(hill, h, i);
        assertEquals(List.of(), hill.log.stream().map(java.util.Arrays::toString).toList());
        // and now every vehicle gets through
        hill.put.remove(Hill.key(top, wallY, -4));
        for (Map.Entry<String, VehicleSpec> e : landVehicles().entrySet()) assertEquals(List.of(), through(e.getValue(), hill), e.getKey());
        assertTrue(old.ceiling(top) < fresh.ceiling(top));
    }

    /** /관리자 국도 터널보수 at one point, as RoadModule does it: a RoadSection of the saved road over the world. */
    static int rebore(Hill hill, int[] h, int i) {
        return new RoadSection(hill, RoadPreviewTest.STYLE, line(), h, tunnelParts(), "북해", 3).rebore(i);
    }

    @Test
    void aFlatOldTunnelIsLeftAsItIs() {
        Hill hill = new Hill();
        int[] h = new int[Hill.LEN];
        java.util.Arrays.fill(h, 128);
        RoadSection s = new RoadSection(hill, RoadPreviewTest.STYLE, line(), h, tunnelParts(), "북해", 3).ceilings(RoadClearance.legacy(h));
        for (int i = 0; i < Hill.LEN; i++) s.build(i);
        hill.log = new ArrayList<>();
        int n = 0;
        RoadSection now = new RoadSection(hill, RoadPreviewTest.STYLE, line(), h, tunnelParts(), "북해", 3);
        for (int i = 0; i < Hill.LEN; i++) n += now.rebore(i);
        assertEquals(0, n);
        assertTrue(hill.log.isEmpty());
    }

    @Test
    void theRepairTakesTheGroundAndTheTunnelsOwnBlocksOnly() {
        for (Material m : List.of(Material.AIR, Material.CAVE_AIR, Material.STONE, Material.PACKED_ICE, Material.BLUE_ICE, Material.SNOW_BLOCK,
                Material.ICE, Material.DIRT, Material.DEEPSLATE, Material.IRON_ORE, Material.STONE_BRICKS, Material.SEA_LANTERN, Material.GRAVEL))
            assertTrue(RoadSection.carvable(m), m.name());
        for (Material m : List.of(Material.OAK_PLANKS, Material.CHEST, Material.GLASS, Material.WATER, Material.LAVA, Material.BRICKS,
                Material.WHITE_WOOL, Material.TORCH, Material.CRAFTING_TABLE, Material.ORANGE_GLAZED_TERRACOTTA, Material.SPAWNER))
            assertFalse(RoadSection.carvable(m), m.name());
    }

    @Test
    void anIceSpikeIsNotTheGround() {
        // snow at 70, a spike 3 across up to 101 round (0, 0), a column not read at (-2, 0)
        java.util.function.IntBinaryOperator g = (x, z) -> Math.abs(x) <= 1 && Math.abs(z) <= 1 ? Integer.MIN_VALUE : x == -2 && z == 0 ? Integer.MIN_VALUE : 70;
        assertEquals(70, dev.stsv.economy.dock.DockModule.besideSpike(0, 0, 101, g));
        assertEquals(70, dev.stsv.economy.dock.DockModule.besideSpike(1, 1, 101, g));
        // nothing round it read: the top as it was
        assertEquals(101, dev.stsv.economy.dock.DockModule.besideSpike(0, 0, 101, (x, z) -> Integer.MIN_VALUE));
        // never higher than the spike's own top (a low spike by a bank)
        assertEquals(66, dev.stsv.economy.dock.DockModule.besideSpike(0, 0, 66, (x, z) -> x == 0 && z == 0 ? Integer.MIN_VALUE : 70));
    }

    // ------------------------------------------------------------------------------------------
    // The preview strip
    // ------------------------------------------------------------------------------------------

    @Test
    void everyVehicleDrivesTheStripAndUnderTheOverpass() throws IOException {
        RoadPreviewTest.Built b = RoadPreviewTest.build();
        RoadPreviewTest.Land land = b.land();
        Blocks w = land::get;
        VehiclePhysics.Terrain t = terrain(w);
        for (Map.Entry<String, VehicleSpec> e : landVehicles().entrySet()) {
            VehicleSpec s = e.getValue();
            // (a) .. (d): street lights, the hill, the bridge over the river, the tunnel in the steep hill (the right lanes)
            assertNull(drive(s, t, w, 12, 1325, 0.5 + 1.25), e.getKey() + " east");
            assertNull(drive(s, t, w, 1325, 12, 0.5 - 1.25), e.getKey() + " west");
            // (h) the older road in its cutting, under the strip's deck
            double x = RoadPreviewTest.UNDER_X;
            assertNull(driveAlong(s, t, w, -36, 36, x - 1.25, false), e.getKey() + " under the overpass, south");
            assertNull(driveAlong(s, t, w, 36, -36, x + 1.25, false), e.getKey() + " under the overpass, north");
        }
    }
}
