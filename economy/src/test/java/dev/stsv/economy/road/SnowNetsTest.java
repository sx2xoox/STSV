package dev.stsv.economy.road;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The roads snow keeps off ({@link SnowNets}): looked up without ever being built on a lookup, swapped in whole. */
class SnowNetsTest {

    static RoadNetwork road(double x) {
        return RoadNetwork.of(List.of(new RoadNetwork.Road("서해", RoadPlanner.densify(List.of(new double[]{x, 0.5}, new double[]{x + 100, 0.5}), 1),
                new int[101])));
    }

    @Test
    void lookupsNeverBuildAndARefreshSwapsInWhenDone() {
        AtomicInteger builds = new AtomicInteger();
        List<Runnable> later = new ArrayList<>();   // the async thread: run when the test says
        RoadNetwork first = road(0), second = road(1000);
        SnowNets nets = new SnowNets(() -> Map.of("world", builds.incrementAndGet() == 1 ? first : second), later::add);
        // at start: none yet, and looking does not build
        for (int i = 0; i < 10_000; i++) assertSame(RoadNetwork.NONE, nets.get("world"));
        assertEquals(0, builds.get());
        nets.refresh();
        assertSame(RoadNetwork.NONE, nets.get("world"), "until the build is done: the one before");
        nets.refresh();   // asked again while it is on its way: no second task now
        assertEquals(1, later.size());
        later.removeFirst().run();
        assertEquals(1, builds.get(), "two refreshes while one was waiting: one build");
        assertSame(first, nets.get("world"));
        assertNotNull(nets.get("world").at(50, 0));
        for (int i = 0; i < 10_000; i++) nets.get("world").at(i % 200, 0);
        assertEquals(1, builds.get(), "repeated lookups never build again");
        assertSame(RoadNetwork.NONE, nets.get("nether"));
        nets.refresh();
        assertSame(first, nets.get("world"), "the old roads while the new are built");
        later.removeFirst().run();
        assertSame(second, nets.get("world"));
        assertNull(nets.get("world").at(50, 0));
        assertEquals(2, builds.get());
    }

    /** /관리자 국도 눈치우기 전체: every chunk a road's columns are in is visited, each once. */
    @Test
    void aSweepVisitsEveryChunkOfARoadOnce() {
        RoadNetwork net = road(0);          // 100 blocks along +x from (0, 0.5)
        assertTrue(net.columnCount() > 100 * 5, net.columnCount() + " columns: its lanes, kerbs and shoulders");
        java.util.Set<Long> chunks = net.chunkKeys();
        // every column's chunk is among them, and nothing else
        java.util.Set<Long> want = new java.util.HashSet<>();
        for (int x = -40; x <= 140; x++) {
            for (int z = -40; z <= 40; z++) if (net.at(x, z) != null) want.add(RoadJob.key(x >> 4, z >> 4));
        }
        assertEquals(want, chunks);
        assertTrue(chunks.size() >= 7 && chunks.size() <= 24, chunks.size() + " chunks for 100 blocks of road");
        assertTrue(chunks.contains(RoadJob.key(0, 0)) && chunks.contains(RoadJob.key(6, 0)));
        assertTrue(RoadNetwork.NONE.chunkKeys().isEmpty());
        assertEquals(0, RoadNetwork.NONE.columnCount());
    }
}
