package dev.stsv.economy.road;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heavy background work (국도, 하역장 짓기, 후보 살피기) paced by the server's load: the MSPT gate ({@link LoadGate}), the
 * blocks-per-tick slicing ({@link BlockBudget}), the road files written off the main thread ({@link JobFiles},
 * {@link RoadJob#snapshot}) and the config's defaults.
 */
class LoadGateTest {

    static final LoadGate.Settings GATE = new LoadGate.Settings(35, 4, 2);

    // ------------------------------------------------------------------------------------------
    // The MSPT gate
    // ------------------------------------------------------------------------------------------

    @Test
    void quietServerRunsFullyWithFewerChunksWhilePlayersAreOn() {
        LoadGate.Allow empty = LoadGate.allow(GATE, 12, 0), online = LoadGate.allow(GATE, 12, 5);
        assertTrue(empty.run());
        assertEquals(4, empty.concurrent());
        assertEquals(1, empty.share());
        assertTrue(online.run());
        assertEquals(2, online.concurrent(), "chunk generation slowed while players are on");
        assertEquals(1, online.share());
    }

    @Test
    void busyServerPauses() {
        for (double mspt : new double[]{35, 35.01, 49.9, 120}) {
            for (int players : new int[]{0, 1, 30}) {
                LoadGate.Allow a = LoadGate.allow(GATE, mspt, players);
                assertSame(LoadGate.Allow.PAUSE, a, mspt + " ms");
                assertFalse(a.run());
                assertEquals(0, a.concurrent(), "no chunk asked for");
                assertEquals(0, a.share(), "no block set");
            }
        }
    }

    @Test
    void nearTheLimitItSlowsDown() {
        // from 80 % of the limit (28 ms of 35): one chunk at a time, half the blocks
        for (double mspt : new double[]{28, 30, 34.99}) {
            LoadGate.Allow a = LoadGate.allow(GATE, mspt, 0);
            assertTrue(a.run());
            assertEquals(1, a.concurrent());
            assertEquals(0.5, a.share());
        }
        assertEquals(1, LoadGate.allow(GATE, 27.99, 0).share());
    }

    @Test
    void noLimitOrNoReadingNeverPauses() {
        LoadGate.Settings off = new LoadGate.Settings(0, 3, 1);
        assertTrue(LoadGate.allow(off, 500, 0).run());
        assertEquals(3, LoadGate.allow(off, 500, 0).concurrent());
        assertEquals(1, LoadGate.allow(off, 500, 2).concurrent());
        assertTrue(LoadGate.allow(GATE, Double.NaN, 0).run(), "no average yet");
    }

    @Test
    void settingsAreSane() {
        LoadGate.Settings s = new LoadGate.Settings(35, 0, -3);
        assertEquals(1, s.concurrent());
        assertEquals(1, s.concurrentOnline());
        assertEquals(4, new LoadGate.Settings(35, 2, 4).most(), "a chunk queue is made for the most it ever has on the way");
    }

    // ------------------------------------------------------------------------------------------
    // Blocks per tick
    // ------------------------------------------------------------------------------------------

    /** Runs pieces of these costs through a budget for a number of ticks; what each tick started (sum of costs). */
    static List<Long> slice(BlockBudget b, int[] costs, int ticks, double share) {
        List<Long> perTick = new ArrayList<>();
        int next = 0;
        for (int t = 0; t < ticks; t++) {
            b.tick(share);
            long spent = 0;
            while (b.any() && next < costs.length) {
                b.spend(costs[next]);
                spent += costs[next++];
            }
            perTick.add(spent);
        }
        return perTick;
    }

    @Test
    void smallPiecesFillATickExactly() {
        int[] ones = new int[5000];
        java.util.Arrays.fill(ones, 1);
        List<Long> got = slice(new BlockBudget(1000), ones, 6, 1);
        assertEquals(List.of(1000L, 1000L, 1000L, 1000L, 1000L, 0L), got);
    }

    @Test
    void aBigPieceIsPaidByTheNextTicks() {
        // a 3000-block cross-section at 800 a tick: it runs, then nothing until its debt is paid
        BlockBudget b = new BlockBudget(800);
        List<Long> got = slice(b, new int[]{3000, 10, 10}, 6, 1);
        // -2200, -1400, -600, then 200 in hand: the next pieces go
        assertEquals(List.of(3000L, 0L, 0L, 20L, 0L, 0L), got);
    }

    @Test
    void columnsCountTheirBlocks() {
        // a dock: foundation columns (up to 24 blocks) between single blocks; never far over the budget in any tick and
        // on average no more than it
        Random r = new Random(7);
        int[] ops = new int[20000];
        long all = 0;
        for (int i = 0; i < ops.length; i++) {
            ops[i] = r.nextInt(5) == 0 ? 1 + r.nextInt(24) : 1;
            all += ops[i];
        }
        BlockBudget b = new BlockBudget(1000);
        List<Long> got = slice(b, ops, 200, 1);
        long done = 0;
        for (int t = 0; t < got.size(); t++) {
            assertTrue(got.get(t) <= 1000 + 23, "tick " + t + ": " + got.get(t));
            done += got.get(t);
            assertTrue(done <= 1000L * (t + 1) + 23, "on average within the budget");
        }
        assertEquals(all, done, "all of it built");
    }

    @Test
    void sharesAndPausesSlowItDown() {
        int[] ones = new int[100_000];
        java.util.Arrays.fill(ones, 1);
        assertEquals(List.of(400L, 400L), slice(new BlockBudget(800), ones, 2, 0.5));
        assertEquals(List.of(0L, 0L), slice(new BlockBudget(800), ones, 2, 0), "paused: nothing");
        BlockBudget b = new BlockBudget(800);
        slice(b, ones, 0, 1);
        for (int t = 0; t < 10; t++) b.tick(1);
        assertEquals(800, b.credit(), "idle ticks do not save up a burst");
        b.spend(300);
        b.tick(0);
        assertFalse(b.any(), "paused: nothing starts, even with blocks in hand");
        assertEquals(500, b.credit());
        b.tick(1);
        assertTrue(b.any());
    }

    @Test
    void randomRoadSectionsStayOnAverage() {
        Random r = new Random(42);
        for (int round = 0; round < 50; round++) {
            int per = 200 + r.nextInt(2000);
            int[] sections = new int[300];
            int max = 0;
            for (int i = 0; i < sections.length; i++) {
                sections[i] = 1 + r.nextInt(r.nextBoolean() ? 400 : 4000);
                max = Math.max(max, sections[i]);
            }
            BlockBudget b = new BlockBudget(per);
            List<Long> got = slice(b, sections, 2000, 1);
            long done = 0;
            for (int t = 0; t < got.size(); t++) {
                done += got.get(t);
                assertTrue(done <= (long) per * (t + 1) + max, "round " + round + " tick " + t);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Road files: written off the main thread, from a snapshot
    // ------------------------------------------------------------------------------------------

    static RoadJob sample() {
        RoadJob j = new RoadJob();
        j.dock = "서해";
        j.world = "build";
        j.startX = 0.5;
        j.startZ = 0.5;
        j.endX = 300.25;
        j.endZ = -40.5;
        j.cells.put(RoadJob.key(1, 2), new RoadJob.Cell(64.5, 0, 3.25, false));
        j.cells.put(RoadJob.key(-3, 2), new RoadJob.Cell(70, 2, 1, true));
        j.corners.add(new double[]{0.5, 0.5});
        j.corners.add(new double[]{300.25, -40.5});
        for (int i = 0; i < j.points().size(); i++) {
            RoadJob.Ground g = new RoadJob.Ground();
            if (i % 7 != 0) g.ground = 60 + i % 5;
            g.water = i % 11 == 0;
            g.surface = 62;
            j.ground.add(g);
        }
        j.halves = new int[]{128, 129, 130};
        j.phase = RoadJob.Phase.BUILD;
        j.cursor = 17;
        j.route = 3;
        j.signs = 2;
        return j;
    }

    @Test
    void aSnapshotWritesTheSameAndStaysPut() {
        RoadJob j = sample();
        RoadJob copy = j.snapshot();
        String before = j.write();
        assertEquals(before, copy.write());
        // the job goes on meanwhile: the copy does not
        j.ground.getFirst().ground = 99;
        j.cursor = 40;
        j.halves[0] = 0;
        j.corners.getFirst()[0] = 7;
        j.cells.clear();
        assertEquals(before, copy.write());
        assertEquals(RoadJob.read(before).write(), before);
    }

    @Test
    void filesAreWrittenInTheBackgroundAndReadAtOnce(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        List<String> warnings = new ArrayList<>();
        JobFiles f = new JobFiles(dir, warnings::add);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        // the writer busy with the first text (made slowly): the newer ones wait, and reads see them meanwhile
        f.write("a.txt", () -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "one";
        });
        f.write("b.txt", () -> "bee");
        assertEquals("bee", f.read("b.txt"), "a save not yet on disk is read as saved");
        f.write("b.txt", () -> "bee 2");
        assertEquals("bee 2", f.read("b.txt"));
        assertNull(f.read("c.txt"));
        Files.writeString(dir.resolve("origin.txt"), "origin");
        hold.countDown();
        assertEquals(List.of("one", "bee 2"), f.texts("origin.txt"));
        f.close();   // everything waiting is on disk now
        assertEquals("one", Files.readString(dir.resolve("a.txt")));
        assertEquals("bee 2", Files.readString(dir.resolve("b.txt")), "the newest text of a file wins");
        assertFalse(Files.exists(dir.resolve("b.txt.tmp")));
        assertEquals(List.of("one", "bee 2"), f.texts("origin.txt"));
        f.write("c.txt", () -> "after");   // closed: written at once
        assertEquals("after", Files.readString(dir.resolve("c.txt")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    // ------------------------------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------------------------------

    @Test
    void configHasTheLimitsWithKoreanNotes() throws Exception {
        YamlConfiguration c;
        try (var in = LoadGateTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            c = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals(35, c.getDouble("roads.max-mspt"));
        assertEquals(800, c.getInt("roads.blocks-per-tick"));
        assertEquals(2, c.getInt("roads.concurrent-online"));
        assertEquals(35, c.getDouble("docks.max-mspt"));
        assertEquals(1000, c.getInt("docks.build-blocks-per-tick"));
        assertEquals(2, c.getInt("docks.scout-concurrent-online"));
        String text;
        try (var in = LoadGateTest.class.getResourceAsStream("/config.yml")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#") || !t.contains(":")) continue;
            String key = t.substring(0, t.indexOf(':')).trim().toLowerCase(java.util.Locale.ROOT);
            assertFalse(List.of("on", "off", "yes", "no", "y", "n").contains(key), "YAML 1.1 boolean as a key: " + line);
        }
    }
}
