package dev.stsv.core.furniture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The merged click targets must cover exactly the furniture's blocks: nothing missing, nothing extra. */
class PrismCoverTest {

    private static List<int[]> box(int sx, int sy, int sz) {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) cells.add(new int[]{x, y, z});
        return cells;
    }

    private static Set<String> covered(List<int[]> prisms) {
        Set<String> out = new HashSet<>();
        for (int[] p : prisms)
            for (int dy = 0; dy < p[4]; dy++) for (int dx = 0; dx < p[3]; dx++) for (int dz = 0; dz < p[3]; dz++)
                if (!out.add((p[0] + dx) + "," + (p[1] + dy) + "," + (p[2] + dz))) throw new AssertionError("overlap");
        return out;
    }

    private static Set<String> asSet(List<int[]> cells) {
        Set<String> out = new HashSet<>();
        cells.forEach(c -> out.add(c[0] + "," + c[1] + "," + c[2]));
        return out;
    }

    @Test
    void wideTallMachineNeedsTwoColumns() {
        List<int[]> cells = box(2, 2, 1);           // 2 wide, 2 tall, 1 deep (old: 4 entities)
        List<int[]> prisms = FurnitureModule.coverWithPrisms(cells);
        assertEquals(2, prisms.size());
        assertEquals(asSet(cells), covered(prisms));
    }

    @Test
    void squareFootprintBecomesOne() {
        List<int[]> cells = box(2, 1, 2);           // 2x2 table (old: 4)
        assertEquals(1, FurnitureModule.coverWithPrisms(cells).size());
        List<int[]> big = box(3, 2, 3);             // 3x3, 2 tall (old: 18)
        List<int[]> prisms = FurnitureModule.coverWithPrisms(big);
        assertEquals(1, prisms.size());
        assertEquals(asSet(big), covered(prisms));
    }

    @Test
    void lShapeIsCoveredExactly() {
        List<int[]> cells = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{0, 0, 1}, new int[]{0, 1, 0});
        List<int[]> prisms = FurnitureModule.coverWithPrisms(cells);
        assertEquals(asSet(cells), covered(prisms));
    }
}
