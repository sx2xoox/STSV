package dev.stsv.core.backup;

import dev.stsv.core.backup.TimeMachinePlan.Chunk;
import dev.stsv.core.backup.TimeMachinePlan.Dimension;
import dev.stsv.core.backup.TimeMachinePlan.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The time machine's plain parts: region files, the world's region folder, what differs, the batches. */
class TimeMachinePlanTest {

    @Test
    void chunksLiveInTheirRegionFiles() {
        assertEquals("r.0.0.mca", TimeMachinePlan.regionFile(new Chunk(0, 0)));
        assertEquals("r.0.0.mca", TimeMachinePlan.regionFile(new Chunk(31, 31)));
        assertEquals("r.1.0.mca", TimeMachinePlan.regionFile(new Chunk(32, 5)));
        assertEquals("r.-1.-1.mca", TimeMachinePlan.regionFile(new Chunk(-1, -32)));
        assertEquals("r.-2.0.mca", TimeMachinePlan.regionFile(new Chunk(-33, 0)));
        // a land over a region border: both files, each once
        assertEquals(List.of("r.0.0.mca", "r.1.0.mca"), TimeMachinePlan.regionFiles(List.of(new Chunk(31, 0), new Chunk(32, 0), new Chunk(30, 1))));
    }

    @Test
    void theWorldsOwnRegionFolder(@TempDir Path dir) throws Exception {
        Path world = dir.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("DIM-1/region"));
        Files.createDirectories(world.resolve("DIM1/region"));
        assertEquals(Path.of("region"), TimeMachinePlan.regionDir(world, Dimension.OVERWORLD));
        assertEquals(Path.of("DIM-1/region"), TimeMachinePlan.regionDir(world, Dimension.NETHER));
        assertEquals(Path.of("DIM1/region"), TimeMachinePlan.regionDir(world, Dimension.END));
        // a newer, deeper layout is found too
        Path deep = dir.resolve("deep");
        Files.createDirectories(deep.resolve("dimensions/minecraft/overworld/region"));
        assertEquals(Path.of("dimensions/minecraft/overworld/region"), TimeMachinePlan.regionDir(deep, Dimension.OVERWORLD));
        assertNull(TimeMachinePlan.regionDir(dir.resolve("never-saved"), Dimension.OVERWORLD));
    }

    @Test
    void onlyWhatDiffersIsTouched() {
        // then: stone everywhere below y 2; now: a hole dug at (3, 1, 4) and a block added at (0, 3, 0)
        TimeMachinePlan.Column<String> then = (x, y, z) -> y < 2 ? "stone" : "air";
        TimeMachinePlan.Column<String> now = (x, y, z) -> (x == 3 && y == 1 && z == 4) ? "air" : (x == 0 && y == 3 && z == 0) ? "dirt" : y < 2 ? "stone" : "air";
        List<Pos> d = new ArrayList<>(TimeMachinePlan.differences(new Chunk(2, -1), 0, 8, then, now));
        TimeMachinePlan.bottomUp(d);
        assertEquals(List.of(new Pos(32 + 3, 1, -16 + 4), new Pos(32, 3, -16)), d);
        assertEquals(List.of(), TimeMachinePlan.differences(new Chunk(0, 0), 0, 8, then, then));
    }

    @Test
    void workIsCutIntoBatches() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7);
        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7)), TimeMachinePlan.batches(all, 3));
        assertEquals(List.of(), TimeMachinePlan.batches(List.of(), 3));
        assertEquals(7, TimeMachinePlan.batches(all, 0).size(), "a batch is at least one");
    }
}
