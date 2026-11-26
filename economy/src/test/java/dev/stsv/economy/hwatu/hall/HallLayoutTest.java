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
            b.append(String.format(Locale.ROOT, "{\"index\":%d,\"x\":%d,\"y\":%d,\"z\":%d,\"yaw\":%s,\"item\":\"%s\",\"game\":\"%s\",\"room\":\"%s\",\"title\":\"%s\",",
                    t.index(), t.x(), t.y(), t.z(), t.yaw(), t.item(), t.game().id, t.room().id, t.title()));
            if (t.dealer() != null) b.append(String.format(Locale.ROOT, "\"dealer\":[%s,%s,%s,%s],", t.dealer().x(), t.dealer().y(), t.dealer().z(), t.dealer().yaw()));
            b.append("\"seats\":[");
            for (int i = 0; i < t.seats().size(); i++) b.append(i == 0 ? "" : ",").append('[').append(t.seats().get(i)[0]).append(',').append(t.seats().get(i)[2]).append(']');
            b.append("]}");
        }
        b.append("],\"anchors\":{");
        Map<String, HallLayout.Point> anchors = Map.of("gate", L.gate(), "warp", L.warp(), "counterNpc", L.counterNpc(), "counter", L.counter(),
                "whiteboard", L.whiteboard(), "gallery", L.gallery(), "annexCounterNpc", L.annexCounterNpc(), "annexCounter", L.annexCounter(),
                "annexDoor", L.annexDoor());
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
    void nineTablesInThePlansOrder() {
        List<HallLayout.Table> t = L.tables();
        assertEquals(9, t.size());
        String[][] want = {{"sutda", "beginner"}, {"sutda", "normal"}, {"sutda", "high"}, {"dice", "beginner"}, {"holjjak", "beginner"}, {"holjjak", "normal"},
                {"seven_poker", "beginner"}, {"seven_poker", "normal"}, {"baccarat", "normal"}};
        for (int i = 0; i < 9; i++) {
            HallLayout.Table x = t.get(i);
            assertEquals(i, x.index());
            assertEquals(want[i][0], x.game().id);
            assertEquals(want[i][1], x.room().id);
            assertEquals(switch (x.game()) {
                case HOLJJAK -> HallLayout.DUEL;
                case SEVEN_POKER -> HallLayout.POKER;
                case BACCARAT -> HallLayout.BACCARAT;
                default -> HallLayout.ROUND;
            }, x.item());
            assertEquals(x.game() == HallLayout.Game.HOLJJAK ? 2 : 6, x.seats().size());
            assertEquals(i >= 6, x.annex(), x.title());
            assertEquals(x.game() == HallLayout.Game.BACCARAT, x.dealer() != null, x.title() + ": a dealer only at the baccarat table");
        }
        // no two tables share a block, and each has an aisle (a block) all round it
        for (int i = 0; i < 9; i++) for (int j = i + 1; j < 9; j++) {
            for (int[] a : t.get(i).footprint()) for (int[] b : t.get(j).footprint())
                assertTrue(Math.max(Math.abs(a[0] - b[0]), Math.abs(a[2] - b[2])) >= 2, t.get(i).title() + " too close to " + t.get(j).title());
        }
        Map<Long, String> solid = solid();
        for (HallLayout.Table x : t) {
            HallLayout.Box hall = x.annex() ? L.annex().getFirst() : L.hall();
            for (int[] c : x.footprint()) {
                assertTrue(hall.containsBlock(c[0], c[1], c[2]), x.title() + " outside its room");
                assertNotNull(solid.get(key(c[0], c[1] - 1, c[2])), x.title() + ": no floor under " + c[0] + "," + c[2]);
                for (int y = 0; y < 2; y++) assertFalse(solid.containsKey(key(c[0], c[1] + y, c[2])), x.title() + ": something in its place at " + c[0] + "," + (c[1] + y) + "," + c[2]);
            }
            for (int[] s : x.seats()) assertTrue(x.footprint().stream().anyMatch(c -> c[0] == s[0] && c[2] == s[2]), x.title() + ": a seat outside its place");
        }
    }

    /**
     * The 별관's baccarat table: its seats round the curve (towards the door), clockwise from above from the dealer's left
     * (the furniture's seat order, turned as the hall turns it), its dealer in the furniture's cell behind the straight
     * edge (2 behind the anchor, away from the door), facing the players, inside the table's own place, with room behind
     * him to walk round; the poker tables seat as the round ones.
     */
    @Test
    void theBaccaratDealerStandsBehindItsStraightEdge() {
        HallLayout.Table bac = L.tables().stream().filter(x -> x.game() == HallLayout.Game.BACCARAT).findFirst().orElseThrow();
        HallLayout.Point d = bac.dealer();
        assertEquals(bac.x() + 0.5, d.x(), 1e-9);
        assertEquals(bac.z() + HallLayout.DEALER_BACK + 0.5, d.z(), 1e-9);
        assertEquals(bac.y(), d.y(), 1e-9);
        assertEquals(180f, d.yaw(), "facing the players (and the door)");
        assertTrue(bac.footprint().stream().anyMatch(c -> c[0] == bac.x() && c[2] == bac.z() + HallLayout.DEALER_BACK), "in the table's own place");
        for (int[] s : bac.seats()) assertTrue(s[2] <= bac.z(), "seats round the curve, the door's side");
        assertEquals(bac.x() + 2, bac.seats().getFirst()[0], "seat 0 at the dealer's left (the hall's +x as it is drawn)");
        for (HallLayout.Table p : L.tables()) if (p.game() == HallLayout.Game.SEVEN_POKER)
            for (int i = 0; i < 6; i++) {
                assertEquals(p.x() + HallLayout.roundSeats().get(i)[0], p.seats().get(i)[0]);
                assertEquals(p.z() + HallLayout.roundSeats().get(i)[1], p.seats().get(i)[2]);
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
                if (x < -HallLayout.LOT - 1 || x > HallLayout.LOT_RIGHT + 1 || z < HallLayout.APRON - 1 || z > HallLayout.BACK + 1) continue;
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
    void everySeatTheDealerTheCountersAndTheGalleryCanBeWalkedTo() {
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
        for (HallLayout.Point p : List.of(L.warp(), L.counter(), L.counterNpc(), L.gallery(), L.annexCounter(), L.annexCounterNpc(), L.annexDoor())) {
            int[] b = block(p);
            assertTrue(from.contains(key(b[0], b[1], b[2])), "cannot walk to " + p);
        }
        // the dealer's spot (inside the table's place, which nobody walks through) has a walkable block next to it
        for (HallLayout.Table t : L.tables()) {
            if (t.dealer() == null) continue;
            int[] d = block(t.dealer());
            boolean ok = false;
            for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long k = key(d[0] + n[0], d[1], d[2] + n[1]);
                ok |= !taken.contains(k) && from.contains(k);
            }
            assertTrue(ok, t.title() + ": the dealer cannot walk to his spot");
        }
        // the 별관 from the hall itself: through the passage (not only round by the yard)
        int[] warp = block(L.warp());
        Set<Long> inside = new HashSet<>();
        for (long k : reach(warp[0], warp[1], warp[2])) inside.add(k);
        int[] ac = block(L.annexCounter());
        assertTrue(inside.contains(key(ac[0], ac[1], ac[2])), "the 별관 from the hall's door");
        // the whiteboard can be read from the back aisle
        int[] wb = block(L.whiteboard());
        assertTrue(from.contains(key(wb[0], 1, wb[2] - 2)), "nowhere to stand before the whiteboard");
        // the gallery looks straight down onto the back row: its hole is over them
        for (HallLayout.Table t : L.tables()) if (t.z() > 13 && !t.annex()) assertTrue(HallLayout.VOID.containsBlock(t.x(), HallLayout.ROOF, t.z()), t.title());
    }

    @Test
    void anchorsAreInsideAndFaceTheRightWay() {
        HallLayout.Box hall = L.hall();
        for (HallLayout.Point p : List.of(L.warp(), L.counter(), L.counterNpc(), L.whiteboard(), L.gallery()))
            assertTrue(hall.contains(p.x(), p.y(), p.z()), "outside the hall: " + p);
        assertFalse(L.inside(L.gate().x(), L.gate().y(), L.gate().z()));
        HallLayout.Box annex = L.annex().getFirst();
        for (HallLayout.Point p : List.of(L.annexCounter(), L.annexCounterNpc(), L.annexDoor()))
            assertTrue(annex.contains(p.x(), p.y(), p.z()), "outside the 별관: " + p);
        for (HallLayout.Table t : L.tables()) if (t.dealer() != null) assertTrue(annex.contains(t.dealer().x(), t.dealer().y(), t.dealer().z()));
        // the 별관's boss and customer face each other across its desk, the customer on the room's side
        assertEquals(180f, Math.abs(L.annexCounterNpc().yaw() - L.annexCounter().yaw()));
        assertTrue(L.annexCounterNpc().x() > L.annexCounter().x());
        // the passage counts as inside
        assertTrue(L.inside(HallLayout.HALF + 2.5, 1.5, HallLayout.PASS_Z0 + 1.5));
        // the boss and the customer face each other across the desk
        assertEquals(180f, Math.abs(L.counterNpc().yaw() - L.counter().yaw()));
        assertTrue(L.counterNpc().x() < L.counter().x());
        assertEquals(180f, L.whiteboard().yaw());
        assertEquals(9 + 3, L.smoke().size(), "over every table, the two drum fires, the lounge");
    }

    @Test
    void theWhiteboardHangsOnTheWallAboveTheFloor() {
        HallLayout.Prop wb = L.props().stream().filter(p -> p.item().equals("economy:gamble_whiteboard")).findFirst().orElseThrow();
        double floor = 1;   // the hall's floor blocks are y 0: people stand on 1, the ceiling (the roof's blocks) is at ROOF
        // HallBuilder lifts every prop half a block (a model's floor on its point); this model is centred on its display
        double middle = wb.y() + 0.5 * wb.scale();
        double bottom = middle - HallLayout.WHITEBOARD_DOWN * wb.scale(), top = middle + HallLayout.WHITEBOARD_UP * wb.scale();
        assertTrue(bottom >= floor + 0.5, "the tray clear of the floor: " + bottom);
        assertTrue(top <= HallLayout.ROOF - 0.3, "under the ceiling: " + top);
        assertTrue(middle - floor > 1.2 && middle - floor < 1.9, "about eye height: " + middle);
        // its words (a text display growing up from 0.45 under its point, six lines at 0.45) stay on the writing surface
        double textLow = L.whiteboard().y() - 0.45, textHigh = textLow + 6 * 0.25 * 0.45;
        assertTrue(textLow >= middle - 0.72 && textHigh <= middle + 0.72, textLow + " .. " + textHigh + " on " + middle);
        // against the back wall, inside the hall
        assertTrue(Math.abs(wb.z() - (HallLayout.HALL_Z1 - 0.55)) < 1e-9);
        assertTrue(L.hall().contains(wb.x(), wb.y(), wb.z()));
    }

    @Test
    void everyFloorPropStandsOnSomething() {
        Map<Long, String> solid = solid();
        for (HallLayout.Prop p : L.props()) {
            if (p.item().equals("economy:gamble_whiteboard")) continue;   // hangs on the wall (above)
            int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z());
            double y = p.y();
            // on a block's top (a pallet on a pallet: 0.15 up)
            int under = (int) Math.floor(y) - 1;
            assertTrue(y - Math.floor(y) < 0.2, p + ": not on a block's top");
            boolean onSomething = solid.containsKey(key(x, under, z)) || under < 0 && y >= 0;   // y 0: the lot's own ground
            assertTrue(onSomething, p + " floats");
            assertFalse(solid.containsKey(key(x, (int) Math.floor(y), z)) && y - Math.floor(y) < 0.01, p + " is sunk in a block");
        }
        // the tables' signs float between their bulb and the ceiling, the same height over the floor as TableScene's
        for (HallLayout.Table t : L.tables()) {
            assertEquals(t.y() + dev.stsv.economy.hwatu.TableArt.SIGN_UP, t.sign().y(), 1e-9);
            assertTrue(t.sign().y() + 0.4 <= HallLayout.ROOF, t.title() + ": the sign under the ceiling");
            assertTrue(t.sign().y() > t.y() + 2.5, t.title() + ": over the bulb");
        }
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
