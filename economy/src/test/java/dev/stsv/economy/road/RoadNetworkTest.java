package dev.stsv.economy.road;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 국도 among other 국도 ({@link RoadNetwork}): following the network, junctions, overpasses, never touching an old road. */
class RoadNetworkTest {

    /** Flat land (top block y 64) in a world of its own, the network of roads already there. */
    static class World implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();
        RoadNetwork net = RoadNetwork.NONE;
        final int groundY;

        World(int groundY) {
            this.groundY = groundY;
        }

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            return m != null ? m : y < groundY ? Material.DIRT : y == groundY ? Material.GRASS_BLOCK : Material.AIR;
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public boolean liquid(int x, int y, int z) {
            return false;
        }

        public boolean natural(int x, int y, int z) {
            return false;
        }

        public boolean fillable(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public void set(int x, int y, int z, Material m) {
            put.put(key(x, y, z), m);
        }

        public boolean protectedLand(int x, int z) {
            return false;
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}

        public RoadNetwork.Spot road(int x, int z) {
            return net.at(x, z);
        }

        int top(int x, int z) {
            int y = 120;
            while (y > 0 && get(x, y, z) == Material.AIR) y--;
            return y;
        }
    }

    static final RoadSection.Style STYLE = new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000);

    /** Builds a straight road from a to b over the world (its ground read, the network's crossings met). */
    static RoadNetwork.Road road(World w, String dock, double[] a, double[] b, RoadSection[] out) {
        List<double[]> pts = RoadPlanner.densify(List.of(a, b), 1);
        int n = pts.size();
        int[] ground = new int[n], surface = new int[n];
        for (int i = 0; i < n; i++) ground[i] = surface[i] = w.top((int) Math.floor(pts.get(i)[0]), (int) Math.floor(pts.get(i)[1]));
        int[] cross = w.net.crossings(pts);
        RoadProfile.Plan plan = RoadProfile.planNetwork(ground, new boolean[n], surface, cross);
        RoadProfile.Part[] parts = RoadProfile.partsHalves(plan.halves(), ground, new boolean[n], 3, 5, 4);
        for (int i = 0; i < n; i++) if (plan.over()[i]) parts[i] = RoadProfile.Part.BRIDGE;
        RoadSection s = new RoadSection(w, STYLE, pts, plan.halves(), parts, dock, 1).network(cross, plan.over());
        for (int i = 0; i < n; i++) assertNull(s.build(i));
        if (out != null) out[0] = s;
        return new RoadNetwork.Road(dock, pts, plan.halves());
    }

    // ------------------------------------------------------------------------------------------

    /** Open land of chunk cells, flat. */
    static RoadPlanner.Field field(RoadNetwork net) {
        return new RoadPlanner.Field() {
            public boolean known(int cx, int cz) {
                return cx >= -2 && cx <= 140 && cz >= -40 && cz <= 40;
            }

            public double height(int cx, int cz) {
                return 64;
            }

            public double water(int cx, int cz) {
                return 0;
            }

            public double rough(int cx, int cz) {
                return 0;
            }

            public boolean blocked(int cx, int cz) {
                return false;
            }

            public boolean road(int cx, int cz) {
                return net.cell(cx, cz);
            }

            public boolean nearRoad(int cx, int cz) {
                return net.near(cx, cz);
            }
        };
    }

    @Test
    void aNewRoadFollowsTheTrunkAndBranchesOff() {
        // a trunk east from the origin, 2 km
        List<double[]> pts = RoadPlanner.densify(List.of(new double[]{8.5, 8.5}, new double[]{2008.5, 8.5}), 1);
        RoadNetwork net = RoadNetwork.of(List.of(new RoadNetwork.Road("동해", pts, new int[pts.size()])));
        // a new dock east-north-east: alone it would go straight; with the trunk it follows it and branches off
        List<int[]> alone = RoadPlanner.path(field(RoadNetwork.NONE), 0, 0, 110, 30, RoadPlanner.Weights.DEFAULT, 400_000);
        List<int[]> along = RoadPlanner.path(field(net), 0, 0, 110, 30, RoadPlanner.Weights.DEFAULT, 400_000);
        int aloneOn = 0, alongOn = 0;
        while (aloneOn < alone.size() && alone.get(aloneOn)[1] == 0) aloneOn++;
        while (alongOn < along.size() && along.get(alongOn)[1] == 0) alongOn++;
        assertTrue(alongOn > aloneOn + 20, "follows the trunk for longer: " + alongOn + " cells, alone " + aloneOn);
        RoadNetwork.Branch b = net.branch(along, 110 * 16 + 8.5, 30 * 16 + 8.5);
        assertNotNull(b);
        assertEquals("동해", b.dock());
        assertEquals(8.5, b.z(), 1e-9, "on the trunk's middle");
        assertEquals(alongOn - 1, b.cell(), "where the route leaves it");
        assertEquals(2000 - (b.x() - 8.5), b.left(), 1e-9, "how far the trunk's dock is from there");
        // never alongside: no step of the route in a cell beside the trunk until it has left it for good
        assertNull(net.branch(alone.subList(alone.size() - 3, alone.size()), 0, 0), "a route not starting on the network: no branch");
    }

    @Test
    void aLevelCrossingIsAJunctionThatLeavesTheOldRoadAlone() {
        World w = new World(64);
        RoadNetwork.Road old = road(w, "동해", new double[]{-60.5, 0.5}, new double[]{60.5, 0.5}, null);
        w.net = RoadNetwork.of(List.of(old));
        Map<Long, Material> before = new HashMap<>(w.put);
        RoadSection[] s = new RoadSection[1];
        road(w, "서해", new double[]{0.5, -60.5}, new double[]{0.5, 60.5}, s);
        // the old road's lanes: exactly as they were (no centre line, kerb or cut across them); its kerbs the new lanes
        // cross: paved over level with them, the rest as they were
        for (int x = -12; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 56; y <= 70; y++) {
                    long k = World.key(x, y, z);
                    if (Math.abs(z) <= 2 || y != 64) assertEquals(before.get(k), w.put.get(k), "the old road at " + x + ", " + y + ", " + z);
                    else assertTrue(before.get(k) == w.put.get(k) || STYLE.paved().contains(w.put.get(k)), "its kerb at " + x + ", " + z + ": " + w.put.get(k));
                }
            }
        }
        // the new road meets it level and runs on: paved, no yellow centre line near the junction, a crosswalk either side
        Material[] paved = STYLE.paved().toArray(new Material[0]);
        for (int z = -60; z <= 60; z++) assertTrue(List.of(paved).contains(w.get(0, w.top(0, z), z)), "the new road at z " + z);
        for (int z = -6; z <= 6; z++) assertTrue(w.get(0, 64, z) != Material.YELLOW_CONCRETE, "the centre line stops short at z " + z);
        int white = 0;
        for (int x = -4; x <= 4; x++) if (w.get(x, 64, -8) == Material.SMOOTH_QUARTZ) white++;
        assertTrue(white >= 3, "a crosswalk before the junction: " + white);
        // its corners are wider: the new road's lanes reach further out next to the old road
        assertTrue(List.of(paved).contains(w.get(4, 64, -5)) || List.of(paved).contains(w.get(-4, 64, -5)), "wider at the corner");
    }

    @Test
    void tooHighAboveTheOldRoadItGoesOverIt() {
        // the free profile is the ground: old road at 64, the new one wants 64 (level), 66 (meet: within 2) or 72 (over)
        int n = 81;
        int[] cross = new int[n];
        java.util.Arrays.fill(cross, Integer.MIN_VALUE);
        for (int i = 38; i <= 42; i++) cross[i] = 128;
        for (int[] c : new int[][]{{64, 0}, {66, 0}, {72, 1}}) {
            int[] ground = new int[n];
            java.util.Arrays.fill(ground, c[0]);
            RoadProfile.Plan p = RoadProfile.planNetwork(ground, new boolean[n], new int[n], cross);
            if (c[1] == 0) {
                assertFalse(p.over()[40], "meets it level from " + c[0]);
                for (int i = 38; i <= 42; i++) assertEquals(128, p.halves()[i], "level with the old road at " + i + " from " + c[0]);
            } else {
                assertTrue(p.over()[40], "goes over it from " + c[0]);
                for (int i = 38 - RoadProfile.OVER_REACH; i <= 42 + RoadProfile.OVER_REACH; i++) {
                    assertTrue(p.halves()[i] >= 2 * (64 + RoadNetwork.CLEARANCE), "the deck clears it at " + i + ": " + p.halves()[i]);
                }
            }
            for (int i = 3; i < n; i++) assertTrue(Math.abs(p.halves()[i] - p.halves()[i - 3]) <= 2, "the grade holds at " + i + " from " + c[0]);
        }
    }

    @Test
    void anOverpassNeverTouchesTheOldRoad() {
        World w = new World(64);
        RoadNetwork.Road old = road(w, "동해", new double[]{-60.5, 0.5}, new double[]{60.5, 0.5}, null);
        // a lamp of the old road next to where the new one will cross
        w.net = RoadNetwork.of(List.of(old));
        Map<Long, Material> before = new HashMap<>(w.put);
        // the new road comes in high (an embankment from both sides): it goes over
        World high = new World(64) {
            @Override
            int top(int x, int z) {
                return Math.abs(z) > 3 ? 72 : super.top(x, z);
            }
        };
        high.put.putAll(w.put);
        high.net = w.net;
        RoadSection[] s = new RoadSection[1];
        road(high, "서해", new double[]{0.5, -80.5}, new double[]{0.5, 80.5}, s);
        for (int x = -12; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 56; y <= 70; y++) {
                    long k = World.key(x, y, z);
                    assertEquals(before.get(k), high.put.get(k), "the old road at " + x + ", " + y + ", " + z);
                }
                for (int y = 65; y <= 68; y++) assertEquals(Material.AIR, high.get(x, y, z), "clear over the old lanes at " + x + ", " + y + ", " + z);
            }
        }
        // the deck over it, its piers outside the old road
        assertTrue(STYLE.paved().contains(high.get(0, 64 + RoadNetwork.CLEARANCE, 0)) || high.get(0, 64 + RoadNetwork.CLEARANCE, 0).name().endsWith("_SLAB"),
                "the deck over the old road: " + high.get(0, 64 + RoadNetwork.CLEARANCE, 0));
    }
}
