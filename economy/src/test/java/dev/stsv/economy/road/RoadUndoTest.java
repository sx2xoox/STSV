package dev.stsv.economy.road;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 국도 되돌리기 ({@link RoadUndo}): the record read back as written, putting back that leaves what players changed, its size. */
class RoadUndoTest {

    static final String STONE = "minecraft:stone", AIR = "minecraft:air", GRASS = "minecraft:grass_block[snowy=false]", DIRT = "minecraft:dirt",
            CONCRETE = "minecraft:gray_concrete", CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]",
            WALL = "minecraft:andesite_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]",
            WALL_JOINED = "minecraft:andesite_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=low]";

    /** Frames appended to a file, one after the other, as JobFiles does. */
    static byte[] file(RoadUndo.Taken... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (RoadUndo.Taken t : frames) out.writeBytes(t.record());
        return out.toByteArray();
    }

    @Test
    void theRecordReadsBackAsWritten() {
        RoadUndo.Recorder r = new RoadUndo.Recorder();
        r.add(-1000, 64, 2_000_000, GRASS, CONCRETE, null);
        r.add(-1000, 65, 2_000_000, STONE, AIR, null);
        r.add(-999, -40, 2_000_001, CHEST, AIR, new byte[]{1, 2, 3, (byte) 200});
        r.add(30_000_000, 319, -30_000_000, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", AIR, null);
        RoadUndo.Taken first = r.take();
        assertEquals(0, r.size());
        r.add(5, 70, 5, AIR, "minecraft:lantern[hanging=true,waterlogged=false]", null);
        RoadUndo.Taken second = r.take();
        assertEquals(null, r.take(), "nothing more");
        assertEquals(5, r.total());
        byte[] f = file(first, second);
        List<byte[]> frames = RoadUndo.frames(f);
        assertEquals(2, frames.size());
        RoadUndo.Frame a = RoadUndo.decode(frames.get(0));
        assertEquals(4, a.count());
        assertArrayEquals(new int[]{-1000, -1000, -999, 30_000_000}, a.x());
        assertArrayEquals(new int[]{64, 65, -40, 319}, a.y());
        assertArrayEquals(new int[]{2_000_000, 2_000_000, 2_000_001, -30_000_000}, a.z());
        assertEquals(GRASS, a.palette()[a.before()[0]]);
        assertEquals(CONCRETE, a.palette()[a.after()[0]]);
        assertEquals(CHEST, a.palette()[a.before()[2]]);
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 200}, a.extras().get(2));
        assertEquals(1, a.extras().size());
        RoadUndo.Frame b = RoadUndo.decode(frames.get(1));
        assertEquals(1, b.count());
        assertEquals("minecraft:lantern[hanging=true,waterlogged=false]", b.palette()[b.after()[0]]);
        // the server stopped mid-write: the frame cut short is left out, the ones before it read
        assertEquals(1, RoadUndo.frames(Arrays.copyOf(f, f.length - 3)).size());
        assertEquals(0, RoadUndo.frames(new byte[0]).size());
        assertEquals(0, RoadUndo.frames(null).size());
    }

    /** A world of block states (what is not set: stone under 66, grass at 66, air over it). */
    static final class World implements RoadUndo.Blocks {
        final Map<Long, String> at = new HashMap<>();
        final Map<Long, byte[]> extras = new HashMap<>();

        static long k(int x, int y, int z) {
            return RoadUndo.Restorer.pos(x, y, z);
        }

        public String get(int x, int y, int z) {
            String s = at.get(k(x, y, z));
            return s != null ? s : y < 66 ? STONE : y == 66 ? GRASS : AIR;
        }

        public void set(int x, int y, int z, String state, byte[] extra) {
            at.put(k(x, y, z), state);
            if (extra != null) extras.put(k(x, y, z), extra);
        }

        /** As the road does it: the change recorded, then made. */
        void road(RoadUndo.Recorder r, int x, int y, int z, String to, byte[] extra) {
            String before = get(x, y, z);
            if (before.equals(to)) return;
            r.add(x, y, z, before, to, extra);
            set(x, y, z, to, null);
        }
    }

    /** A road over the world: a strip cut, paved, a wall joined after it was put, a chest taken, then its record. */
    static List<byte[]> road(World w) {
        RoadUndo.Recorder r = new RoadUndo.Recorder();
        List<RoadUndo.Taken> frames = new ArrayList<>();
        w.set(3, 65, 3, CHEST, null);   // a chest in the way (its contents saved)
        w.set(4, 66, 2, "minecraft:oak_planks", null);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                w.road(r, x, 64, z, CONCRETE, null);
                for (int y = 65; y < 69; y++) w.road(r, x, y, z, AIR, x == 3 && y == 65 && z == 3 ? new byte[]{9, 9} : null);
            }
            if (x == 3) frames.add(r.take());   // a save in the middle
        }
        w.road(r, 0, 65, 0, WALL, null);
        w.road(r, 0, 65, 0, WALL_JOINED, null);   // joined to its neighbours afterwards: changed twice
        frames.add(r.take());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (RoadUndo.Taken t : frames) out.writeBytes(t.record());
        return RoadUndo.frames(out.toByteArray());
    }

    static void putBack(RoadUndo.Restorer r, World w) {
        while (!r.done()) r.step(7, w);
    }

    @Test
    void puttingBackLeavesWhatAPlayerChangedSince() {
        World w = new World();
        List<byte[]> frames = road(w);
        assertEquals(AIR, w.get(4, 66, 2), "the road cut the planks");
        // since: a player put a block on the road, broke another, built where the road cut; water ran into the cut
        w.set(1, 64, 1, "minecraft:cobblestone", null);
        w.set(2, 64, 2, AIR, null);
        w.set(5, 66, 4, "minecraft:torch", null);
        w.set(6, 66, 0, "minecraft:water[level=3]", null);
        w.set(7, 64, 4, CONCRETE, null);   // re-placed the same block: still the road's
        RoadUndo.Restorer r = new RoadUndo.Restorer(frames);
        putBack(r, w);
        assertEquals(3, r.skipped(), "the three blocks players changed: " + r.skippedAt(10).stream().map(Arrays::toString).toList());
        assertEquals("minecraft:cobblestone", w.get(1, 64, 1));
        assertEquals(AIR, w.get(2, 64, 2));
        assertEquals("minecraft:torch", w.get(5, 66, 4));
        // everything else as it was: stone, grass on top (the water that ran into the cut gone with it), the chest, the planks
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 5; z++) {
                for (int y = 64; y < 69; y++) {
                    if ((x == 1 && z == 1 && y == 64) || (x == 2 && z == 2 && y == 64) || (x == 5 && y == 66 && z == 4)) continue;
                    String want = x == 3 && y == 65 && z == 3 ? CHEST : x == 4 && y == 66 && z == 2 ? "minecraft:oak_planks" : y < 66 ? STONE : y == 66 ? GRASS : AIR;
                    assertEquals(want, w.get(x, y, z), x + ", " + y + ", " + z);
                }
            }
        }
        assertEquals(STONE, w.get(0, 65, 0), "the wall changed twice (put, joined): back to the stone before it");
        assertArrayEquals(new byte[]{9, 9}, w.extras.get(World.k(3, 65, 3)), "the chest's contents back");
        assertEquals(1, r.containers());
        // 되돌리기 again (stopped and started again): nothing more is changed, nothing counted as a player's
        RoadUndo.Restorer again = new RoadUndo.Restorer(frames);
        Map<Long, String> before = new HashMap<>(w.at);
        putBack(again, w);
        assertEquals(before, w.at);
        assertEquals(0, again.restored());
        assertEquals(3, again.skipped());
    }

    @Test
    void stoppedHalfwayItGoesOnFromTheEnd() {
        World w = new World();
        List<byte[]> frames = road(w);
        RoadUndo.Restorer r = new RoadUndo.Restorer(frames);
        for (int k = 0; k < 10; k++) r.step(5, w);   // stopped (되돌리기 중지)
        assertTrue(r.restored() > 0 && !r.done());
        RoadUndo.Restorer again = new RoadUndo.Restorer(frames);
        putBack(again, w);
        assertEquals(0, again.skipped(), "what the first one put back is not taken for a player's change");
        assertTrue(again.already() > 0);
        assertEquals(STONE, w.get(0, 64, 0));
        assertEquals(GRASS, w.get(0, 66, 0));
        assertEquals(CHEST, w.get(3, 65, 3));
    }

    @Test
    void eachFramesChunksComeFirst() {
        World w = new World();
        RoadUndo.Restorer r = new RoadUndo.Restorer(road(w));
        assertEquals(java.util.Set.of(RoadJob.key(0, 0)), r.chunks());
        int n = r.step(1_000_000, w);
        assertTrue(n > 0 && n < 200, "never past the end of a frame: " + n);
    }

    @Test
    void decide() {
        assertEquals(RoadUndo.Outcome.RESTORE, RoadUndo.decide(CONCRETE, GRASS, CONCRETE));
        assertEquals(RoadUndo.Outcome.ALREADY, RoadUndo.decide(GRASS, GRASS, CONCRETE));
        assertEquals(RoadUndo.Outcome.SKIP, RoadUndo.decide("minecraft:cobblestone", GRASS, CONCRETE));
        assertEquals(RoadUndo.Outcome.RESTORE, RoadUndo.decide(WALL_JOINED, AIR, WALL), "the road's wall, joined since");
        assertEquals(RoadUndo.Outcome.RESTORE, RoadUndo.decide("minecraft:water[level=5]", STONE, AIR), "water ran in");
        assertEquals(RoadUndo.Outcome.SKIP, RoadUndo.decide("minecraft:water[level=0]", STONE, AIR), "a source someone poured");
        assertEquals(RoadUndo.Outcome.SKIP, RoadUndo.decide(AIR, STONE, CONCRETE), "broken by a player");
    }

    /**
     * A long road's record: 7,000 points, 13 columns each, 40 blocks a column changed (3.64 million changes, about what a
     * 7 km road through hills is), taken off every 50,000 as BUILD does. Gzipped palette and varint steps: about a byte a
     * change at most (the old blocks vary: stone, dirt, ores, grass, air).
     */
    @Test
    void aLongRoadsRecordStaysSmall() {
        RoadUndo.Recorder r = new RoadUndo.Recorder();
        String[] ground = {STONE, STONE, STONE, DIRT, "minecraft:andesite", "minecraft:coal_ore", "minecraft:gravel", "minecraft:granite"};
        java.util.Random rnd = new java.util.Random(7);
        long bytes = 0;
        int frames = 0;
        List<byte[]> some = new ArrayList<>();
        long changes = 0;
        for (int i = 0; i < 7000; i++) {
            int top = 64 + (int) Math.round(12 * Math.sin(i / 300.0));
            for (int c = -6; c <= 6; c++) {
                int x = 1000 + i, z = -500 + c + (i / 50);
                for (int y = top - 20; y < top + 20; y++) {
                    String before = y < top - 3 ? ground[rnd.nextInt(ground.length)] : y < top ? DIRT : y == top ? GRASS : rnd.nextInt(9) == 0 ? "minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false]" : AIR;
                    String after = y < 64 ? "minecraft:cobblestone" : y == 64 ? (Math.abs(c) <= 2 ? CONCRETE : "minecraft:smooth_stone") : AIR;
                    r.add(x, y, z, before, after, null);
                    changes++;
                }
            }
            if (r.size() >= RoadUndo.FRAME_MAX) {
                byte[] rec = r.take().record();
                bytes += rec.length;
                frames++;
                if (some.size() < 2) some.add(rec);
            }
        }
        RoadUndo.Taken last = r.take();
        if (last != null) {
            bytes += last.record().length;
            frames++;
        }
        assertEquals(3_640_000, changes);
        double per = bytes / (double) changes;
        System.out.printf("[국도 되돌리기] %,d changes in %d frames: %,d bytes (%.2f bytes a change)%n", changes, frames, bytes, per);
        assertTrue(per < 1.0, "bytes a change: " + per);
        assertTrue(bytes < 4_000_000, "the whole record: " + bytes);
        // and it reads back
        RoadUndo.Frame f = RoadUndo.decode(RoadUndo.frames(some.getFirst()).getFirst());
        assertTrue(f.count() >= RoadUndo.FRAME_MAX);
        assertEquals(1000, f.x()[0]);
    }
}
