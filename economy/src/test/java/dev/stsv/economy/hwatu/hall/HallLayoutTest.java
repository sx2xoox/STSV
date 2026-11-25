package dev.stsv.economy.hwatu.hall;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallLayoutTest {

    private static final HallLayout.Layout L = HallLayout.of();

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    /** The built blocks by place (air left out). */
    private static Map<Long, String> solid() {
        Map<Long, String> m = new HashMap<>();
        for (HallLayout.Block b : L.blocks()) if (!HallLayout.name(b.data()).equals("air")) m.put(key(b.x(), b.y(), b.z()), b.data());
        return m;
    }

    /** The cells the tables keep for themselves (y 1 and 2). */
    private static Set<Long> tableCells() {
        Set<Long> s = new HashSet<>();
        for (HallLayout.Table t : L.tables())
            for (int[] c : t.footprint()) for (int y = 0; y < 2; y++) s.add(key(c[0], c[1] + y, c[2]));
        return s;
    }

    @Test
    void writesThePreviewData() throws IOException {
        StringBuilder b = new StringBuilder("{\"blocks\":[");
        boolean first = true;
        for (HallLayout.Block x : L.blocks()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(x.x()).append(',').append(x.y()).append(',').append(x.z()).append(",\"").append(x.data()).append("\"]");
        }
        b.append("],\"props\":[");
        first = true;
        for (HallLayout.Prop p : L.props()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(",\"").append(p.item()).append("\",")
                    .append(p.scale()).append(',').append(p.yaw()).append(']');
        }
        b.append("],\"labels\":[");
        first = true;
        for (HallLayout.Label t : L.labels()) {
            if (!first) b.append(',');
            first = false;
            String plain = t.text().replaceAll("<[^>]*>", "").replace("\n", " / ").replace("\"", "'");
            b.append('[').append(t.x()).append(',').append(t.y()).append(',').append(t.z()).append(',').append(t.yaw()).append(",\"")
                    .append(plain).append("\",").append(t.scale()).append(',').append(Integer.toUnsignedString(t.background())).append(']');
        }
        b.append("],\"tables\":[");
        first = true;
        for (HallLayout.Table t : L.tables()) {
            if (!first) b.append(',');
            first = false;
            b.append(String.format(Locale.ROOT, "{\"index\":%d,\"x\":%d,\"y\":%d,\"z\":%d,\"yaw\":%s,\"item\":\"%s\",\"game\":\"%s\",\"room\":\"%s\",\"title\":\"%s\",\"seats\":[",
                    t.index(), t.x(), t.y(), t.z(), t.yaw(), t.item(), t.game().id, t.room().id, t.title()));
            for (int i = 0; i < t.seats().size(); i++) b.append(i == 0 ? "" : ",").append('[').append(t.seats().get(i)[0]).append(',').append(t.seats().get(i)[2]).append(']');
            b.append("]}");
        }
        b.append("],\"anchors\":{");
        Map<String, HallLayout.Point> anchors = Map.of("gate", L.gate(), "warp", L.warp(), "counterNpc", L.counterNpc(), "counter", L.counter(),
                "whiteboard", L.whiteboard(), "gallery", L.gallery());
        first = true;
        for (Map.Entry<String, HallLayout.Point> e : anchors.entrySet()) {
            if (!first) b.append(',');
            first = false;
            HallLayout.Point p = e.getValue();
            b.append('"').append(e.getKey()).append("\":[").append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(',').append(p.yaw()).append(']');
        }
        b.append("}}");
        Path out = Path.of("build/docks/gamble_hall.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, b.toString());
    }

    @Test
    void everyBlockIsARealBlock() {
        Set<String> seen = new HashSet<>();
        for (HallLayout.Block b : L.blocks()) {
            String n = HallLayout.name(b.data());
            if (seen.add(n)) {
                Material m = Material.matchMaterial("minecraft:" + n);
                assertNotNull(m, b.data());
            }
            // nothing a player could open or take
            assertFalse(n.contains("chest") || n.contains("barrel") || n.endsWith("_door") || n.contains("furnace") || n.contains("shulker")
                    || n.equals("crafting_table") || n.equals("lectern") || n.equals("anvil") || n.contains("hopper"), b.data());
        }
        for (HallLayout.Column c : L.columns()) assertNotNull(Material.matchMaterial(c.data()), c.data());
        for (HallLayout.Block b : L.blocks()) assertTrue(L.bounds().containsBlock(b.x(), b.y(), b.z()), "out of bounds: " + b);
        for (HallLayout.Prop p : L.props()) assertTrue(p.item().startsWith("economy:gamble_") || p.item().startsWith("economy:model/dock_"), p.item());
        assertTrue(L.blocks().size() > 3000, "a real building: " + L.blocks().size());
    }

    @Test
    void sixTablesInThePlansOrder() {
        List<HallLayout.Table> t = L.tables();
        assertEquals(6, t.size());
        String[][] want = {{"sutda", "beginner"}, {"sutda", "normal"}, {"sutda", "high"}, {"dice", "beginner"}, {"holjjak", "beginner"}, {"holjjak", "normal"}};
        for (int i = 0; i < 6; i++) {
            assertEquals(i, t.get(i).index());
            assertEquals(want[i][0], t.get(i).game().id);
            assertEquals(want[i][1], t.get(i).room().id);
            assertEquals(t.get(i).game() == HallLayout.Game.HOLJJAK ? HallLayout.DUEL : HallLayout.ROUND, t.get(i).item());
            assertEquals(t.get(i).round() ? 6 : 2, t.get(i).seats().size());
        }
        // no two tables share a block, and each has an aisle (a block) all round it
        for (int i = 0; i < 6; i++) for (int j = i + 1; j < 6; j++) {
            for (int[] a : t.get(i).footprint()) for (int[] b : t.get(j).footprint())
                assertTrue(Math.max(Math.abs(a[0] - b[0]), Math.abs(a[2] - b[2])) >= 2, t.get(i).title() + " too close to " + t.get(j).title());
        }
        Map<Long, String> solid = solid();
        HallLayout.Box hall = L.hall();
        for (HallLayout.Table x : t) {
            for (int[] c : x.footprint()) {
                assertTrue(hall.containsBlock(c[0], c[1], c[2]), x.title() + " outside the hall");
                assertNotNull(solid.get(key(c[0], c[1] - 1, c[2])), x.title() + ": no floor under " + c[0] + "," + c[2]);
                for (int y = 0; y < 2; y++) assertFalse(solid.containsKey(key(c[0], c[1] + y, c[2])), x.title() + ": something in its place at " + c[0] + "," + (c[1] + y) + "," + c[2]);
            }
            for (int[] s : x.seats()) assertTrue(x.footprint().stream().anyMatch(c -> c[0] == s[0] && c[2] == s[2]), x.title() + ": a seat outside its place");
        }
    }

    // ------------------------------------------------------------------------------------------
    // walking: from the gate (and the warp) to every seat, the counter, the gallery
    // ------------------------------------------------------------------------------------------

    /** Blocks a body may pass through. */
    private static boolean passable(String data) {
        if (data == null) return true;
        String n = HallLayout.name(data);
        return n.endsWith("pressure_plate") || n.equals("ladder") || n.endsWith("_carpet");
    }

    /** Where someone can stand: a body's two blocks free, something under them that is not a rail. */
    private static boolean stand(Map<Long, String> solid, Set<Long> taken, int x, int y, int z) {
        if (taken.contains(key(x, y, z)) || taken.contains(key(x, y + 1, z))) return false;
        if (!passable(solid.get(key(x, y, z))) || !passable(solid.get(key(x, y + 1, z)))) return false;
        String under = solid.get(key(x, y - 1, z));
        if (under == null) return false;
        String n = HallLayout.name(under);
        return !(n.equals("iron_bars") || n.endsWith("_pane") || n.endsWith("_wall") || n.endsWith("_fence") || n.equals("iron_chain")
                || n.equals("lantern") || n.equals("end_rod") || n.equals("lightning_rod"));
    }

    /** Everywhere one can walk to (with jumps of one block, drops of up to three). */
    private static Set<Long> reach(int x0, int y0, int z0) {
        Map<Long, String> solid = solid();
        Set<Long> taken = tableCells();
        assertTrue(stand(solid, taken, x0, y0, z0), "cannot stand at the start " + x0 + "," + y0 + "," + z0);
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[]{x0, y0, z0});
        seen.add(key(x0, y0, z0));
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!todo.isEmpty()) {
            int[] c = todo.poll();
            for (int[] s : steps) {
                int x = c[0] + s[0], z = c[2] + s[1];
                if (Math.abs(x) > HallLayout.LOT + 1 || z < HallLayout.APRON - 1 || z > HallLayout.BACK + 1) continue;
                for (int dy = 1; dy >= -3; dy--) {
                    int y = c[1] + dy;
                    if (dy == 1 && !passable(solid.get(key(c[0], c[1] + 2, c[2])))) continue;   // no room to jump
                    if (dy < 0) {   // a drop: the way down is open
                        boolean open = true;
                        for (int k = 0; k > dy; k--) open &= passable(solid.get(key(x, c[1] + k, z))) && passable(solid.get(key(x, c[1] + k + 1, z)));
                        if (!open) break;
                    }
                    if (stand(solid, taken, x, y, z)) {
                        if (seen.add(key(x, y, z))) todo.add(new int[]{x, y, z});
                        break;
                    }
                }
            }
        }
        return seen;
    }

    private static int[] block(HallLayout.Point p) {
        return new int[]{(int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z())};
    }

    @Test
    void everySeatTheCounterAndTheGalleryCanBeWalkedTo() {
        int[] gate = block(L.gate());
        Set<Long> from = reach(gate[0], gate[1], gate[2]);
        Set<Long> taken = tableCells();
        for (HallLayout.Table t : L.tables()) {
            for (int[] s : t.seats()) {
                boolean ok = false;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long k = key(s[0] + n[0], s[1], s[2] + n[1]);
                    ok |= !taken.contains(k) && from.contains(k);
                }
                assertTrue(ok, t.title() + ": seat at " + s[0] + "," + s[2] + " cannot be walked up to");
            }
            // and someone can stand watching on at least four sides' worth of blocks round it
            int round = 0;
            for (int[] c : t.footprint()) for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long k = key(c[0] + n[0], c[1], c[2] + n[1]);
                if (!taken.contains(k) && from.contains(k)) round++;
            }
            assertTrue(round >= 8, t.title() + ": hardly room to watch (" + round + ")");
        }
        for (HallLayout.Point p : List.of(L.warp(), L.counter(), L.counterNpc(), L.gallery())) {
            int[] b = block(p);
            assertTrue(from.contains(key(b[0], b[1], b[2])), "cannot walk to " + p);
        }
        // the whiteboard can be read from the back aisle
        int[] wb = block(L.whiteboard());
        assertTrue(from.contains(key(wb[0], 1, wb[2] - 2)), "nowhere to stand before the whiteboard");
        // the gallery looks straight down onto the back row: its hole is over them
        for (HallLayout.Table t : L.tables()) if (t.z() > 13) assertTrue(HallLayout.VOID.containsBlock(t.x(), HallLayout.ROOF, t.z()), t.title());
    }

    @Test
    void anchorsAreInsideAndFaceTheRightWay() {
        HallLayout.Box hall = L.hall();
        for (HallLayout.Point p : List.of(L.warp(), L.counter(), L.counterNpc(), L.whiteboard(), L.gallery()))
            assertTrue(hall.contains(p.x(), p.y(), p.z()), "outside the hall: " + p);
        assertFalse(hall.contains(L.gate().x(), L.gate().y(), L.gate().z()));
        // the boss and the customer face each other across the desk
        assertEquals(180f, Math.abs(L.counterNpc().yaw() - L.counter().yaw()));
        assertTrue(L.counterNpc().x() < L.counter().x());
        assertEquals(180f, L.whiteboard().yaw());
        assertEquals(6 + 2, L.smoke().size());
    }

    // ------------------------------------------------------------------------------------------
    // turning
    // ------------------------------------------------------------------------------------------

    @Test
    void blockStatesTurnWithTheHall() {
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom]", HallLayout.rotate("minecraft:oak_stairs[facing=south,half=bottom]", 1));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", HallLayout.rotate("minecraft:oak_stairs[facing=south,half=bottom]", 2));
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", HallLayout.rotate("minecraft:oak_stairs[facing=south,half=bottom]", 3));
        assertEquals("minecraft:end_rod[facing=up]", HallLayout.rotate("minecraft:end_rod[facing=up]", 1));
        assertEquals("minecraft:oak_log[axis=z]", HallLayout.rotate("minecraft:oak_log[axis=x]", 1));
        assertEquals("minecraft:oak_log[axis=x]", HallLayout.rotate("minecraft:oak_log[axis=x]", 2));
        assertEquals("minecraft:iron_bars[west=true,north=false,east=true,south=false]",
                HallLayout.rotate("minecraft:iron_bars[north=true,east=false,south=true,west=false]", 3));
        assertEquals("minecraft:oak_sign[rotation=4]", HallLayout.rotate("minecraft:oak_sign[rotation=0]", 1));
        assertEquals("minecraft:stone", HallLayout.rotate("minecraft:stone", 3));
        // four quarter turns: back where it was, for every state in the layout
        for (HallLayout.Block b : L.blocks()) {
            String s = b.data();
            for (int i = 0; i < 4; i++) s = HallLayout.rotate(s, 1);
            assertEquals(sorted(b.data()), sorted(s));
        }
    }

    private static String sorted(String data) {
        int b = data.indexOf('[');
        if (b < 0) return data;
        String[] kv = data.substring(b + 1, data.length() - 1).split(",");
        java.util.Arrays.sort(kv);
        return data.substring(0, b) + String.join(",", kv);
    }

    @Test
    void theSiteTurnsPlacesAndYaws() {
        // facing south: right is west (-x), forward is +z
        HallLayout.Site s = new HallLayout.Site("world", 100, 64, 200, 0);
        assertArrayEquals(new int[]{99, 64, 203}, s.block(1, 0, 3));
        assertArrayEquals(new double[]{100.5, 65, 200.5}, s.at(0.5, 1, 0.5));
        assertEquals(90f, s.yaw(90));
        for (int f = 0; f < 4; f++) {
            HallLayout.Site t = new HallLayout.Site("world", 100, 64, 200, f);
            // a block's middle and the block agree
            int[] b = t.block(-6, 1, 10);
            double[] p = t.at(-6 + 0.5, 1, 10 + 0.5);
            assertEquals(b[0] + 0.5, p[0], 1e-9);
            assertEquals(b[2] + 0.5, p[2], 1e-9);
            // forward is the way the yaw looks (Minecraft: yaw 0 = +z, 90 = -x)
            int[] o = t.block(0, 0, 0), fw = t.block(0, 0, 1);
            double yaw = Math.toRadians(t.yaw(0));
            assertEquals(-Math.sin(yaw), fw[0] - o[0], 1e-9);
            assertEquals(Math.cos(yaw), fw[2] - o[2], 1e-9);
            // local yaw 90 looks to the right (+x)
            int[] r = t.block(1, 0, 0);
            double y90 = Math.toRadians(t.yaw(90));
            assertEquals(-Math.sin(y90), r[0] - o[0], 1e-9);
            assertEquals(Math.cos(y90), r[2] - o[2], 1e-9);
            assertEquals(f, HallLayout.Site.facing(t.yaw(0)));
            // a stair that climbs forward climbs forward in the world too
            String st = t.state("minecraft:polished_deepslate_stairs[facing=south]");
            String want = switch (f) { case 0 -> "south"; case 1 -> "west"; case 2 -> "north"; default -> "east"; };
            assertTrue(st.contains("facing=" + want), st);
            int[] anchor = t.box(L.hall());
            assertTrue(anchor[0] <= anchor[3] && anchor[2] <= anchor[5]);
            assertTrue(t.inside("world", p[0], p[1] + 0.5, p[2]), "a table is inside the hall, facing " + f);
            assertFalse(t.inside("nether", p[0], p[1] + 0.5, p[2]));
            assertTrue(t.chunks().size() >= 9);
        }
    }

    @Test
    void panesAndBarsJoinTheirNeighbours() {
        Map<Long, String> all = new HashMap<>();
        for (HallLayout.Block b : L.blocks()) all.put(key(b.x(), b.y(), b.z()), b.data());
        // a window pane in the right wall joins the wall either side of it along z (north and south)
        String pane = all.get(key(HallLayout.HALF, 2, 5));
        assertTrue(pane.contains("north=true") && pane.contains("south=true"), pane);
        // a rail of the gallery's hole joins its neighbours along x (east and west) on its long side
        String rail = all.get(key(0, HallLayout.ROOF + 1, HallLayout.VOID.minZ()));
        assertTrue(rail.contains("east=true") && rail.contains("west=true") && rail.contains("north=false"), rail);
    }
}
