package dev.stsv.economy.dock;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dock's shipping containers: one model in six colours, stacked alike, solid with barriers, and the old block ones cleared. */
class DockContainersTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @Test
    void everyContainerIsTheModelStackedAndSolid() {
        for (DockLayout.Kind kind : DockLayout.Kind.docks()) {
            DockLayout.Layout l = DockLayout.of(kind, "서해");
            Set<String> barriers = new HashSet<>();
            for (DockLayout.Block b : l.blocks()) if (b.data().equals(DockLayout.BARRIER)) barriers.add(key(b.x(), b.y(), b.z()));
            // no container of blocks any more: no coloured concrete or terracotta out in the yard
            for (DockLayout.Block b : l.blocks()) {
                boolean yard = b.x() >= 11 && b.x() <= 21 && b.z() >= 1 && b.z() <= 36 && b.y() >= 0;
                assertFalse(yard && (b.data().endsWith("_concrete") || b.data().endsWith("_terracotta")) && !b.data().contains("gray"), kind + ": a block container at " + b);
            }
            List<DockLayout.Prop> boxes = l.props().stream().filter(p -> p.model().startsWith("dock_container_")).toList();
            int expected = DockLayout.STACKS.stream().mapToInt(DockLayout.Stack::high).sum();
            assertEquals(expected, boxes.size(), "one model per container");
            Set<String> covered = new HashSet<>();
            for (DockLayout.Stack st : DockLayout.STACKS) {
                // its models: one over the other, 2.5 apart, all on the footprint's middle, the colours taking turns
                List<DockLayout.Prop> mine = boxes.stream().filter(p -> Math.abs(p.x() - (st.x0() + st.width() / 2.0)) < 1e-9
                        && Math.abs(p.z() - (st.z0() + st.length() / 2.0)) < 1e-9).toList();
                assertEquals(st.high(), mine.size(), "stack at " + st);
                for (int level = 0; level < st.high(); level++) {
                    final int lv = level;
                    assertTrue(mine.stream().anyMatch(p -> Math.abs(p.y() - lv * DockLayout.CONTAINER_HEIGHT) < 1e-9), "level " + level + " of " + st);
                }
                for (DockLayout.Prop p : mine) {
                    assertEquals(st.turned() ? 90f : 0f, p.yaw(), "lies along its footprint");
                    assertTrue(Files.exists(PACK.resolve("models/" + p.model() + ".bbmodel")), p.model());
                    assertTrue(DockLayout.CONTAINER_COLOURS.contains(p.model().substring("dock_container_".length())), p.model());
                }
                // solid: barriers over its whole footprint up to its top, nothing over that
                for (int x = st.x0(); x < st.x0() + st.width(); x++) {
                    for (int z = st.z0(); z < st.z0() + st.length(); z++) {
                        for (int y = 0; y < st.barrierHeight(); y++) {
                            assertTrue(barriers.contains(key(x, y, z)), "a barrier in " + st + " at " + x + ", " + y + ", " + z);
                            covered.add(key(x, y, z));
                        }
                        assertFalse(barriers.contains(key(x, st.barrierHeight(), z)), "nothing invisible over its top");
                    }
                }
                assertTrue(st.barrierHeight() >= st.high() * DockLayout.CONTAINER_HEIGHT && st.barrierHeight() < st.high() * DockLayout.CONTAINER_HEIGHT + 1);
            }
            assertEquals(covered, barriers, "barriers only inside containers");
            // the yard: inside the pad, off the unloading zone, the gate and the warehouse, no two stacks on each other
            DockLayout.Box zone = DockLayout.ZONE;
            Map<String, DockLayout.Stack> at = new HashMap<>();
            for (DockLayout.Stack st : DockLayout.STACKS) {
                for (int x = st.x0(); x < st.x0() + st.width(); x++) {
                    for (int z = st.z0(); z < st.z0() + st.length(); z++) {
                        assertTrue(Math.abs(x) < DockLayout.HALF_WIDTH && z >= 0 && z < DockLayout.DEPTH, "on the pad: " + st);
                        assertFalse(x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ(), "out of the zone: " + st);
                        assertFalse(Math.abs(x) <= 8 && z <= 2, "clear of the gate: " + st);
                        assertTrue(x > -11, "clear of the warehouse: " + st);
                        assertEquals(null, at.put(x + "," + z, st), "two stacks at " + x + ", " + z);
                    }
                }
            }
        }
    }

    @Test
    void theOldBlockContainersAreWhatRedecoratingClears() {
        List<int[]> old = DockLayout.legacyContainerCells();
        assertEquals(24 * 3 * 3 * 7, old.size(), "24 old containers of 3 x 3 x 7 blocks");
        DockLayout.Layout l = DockLayout.of(DockLayout.Kind.LAND, "서해");
        Set<String> blocks = new HashSet<>();
        for (DockLayout.Block b : l.blocks()) blocks.add(key(b.x(), b.y(), b.z()));
        long cleared = old.stream().filter(c -> !blocks.contains(key(c[0], c[1], c[2]))).count();
        assertTrue(cleared > 0 && cleared < old.size(), "the old cells the new layout does not use are cleared, the rest put again: " + cleared);
        for (int[] c : old) assertTrue(c[0] >= 11 && c[0] <= 21 && c[1] >= 0 && c[1] < 9 && c[2] >= 6 && c[2] <= 36, "inside the old yard");
    }
}
