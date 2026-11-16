package dev.stsv.core.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The pure parts of the time machine (unit-tested): which region files hold which chunks, where a world keeps its
 * region folder, which blocks differ between then and now, and how the work is cut into per-tick batches.
 */
public final class TimeMachinePlan {

    private TimeMachinePlan() {}

    /** A chunk column. */
    public record Chunk(int x, int z) {}

    /** One block position (world coordinates). */
    public record Pos(int x, int y, int z) {}

    /** The region file holding a chunk (32 x 32 chunks a file). */
    public static String regionFile(Chunk c) {
        return "r." + (c.x() >> 5) + "." + (c.z() >> 5) + ".mca";
    }

    /** The region files of these chunks, each once, in order. */
    public static List<String> regionFiles(java.util.Collection<Chunk> chunks) {
        Set<String> out = new LinkedHashSet<>();
        for (Chunk c : chunks) out.add(regionFile(c));
        return new ArrayList<>(out);
    }

    /** Which kind of dimension a world is, for finding its folder of region files. */
    public enum Dimension { OVERWORLD, NETHER, END }

    /**
     * Where, inside a world's folder, its region folder is (relative path, e.g. "region" or "DIM-1/region"; newer
     * layouts nest it deeper). The world's own one: for the overworld the shallowest that is not a nether or end
     * folder, for the nether / end the one under theirs. null: none (a world never saved).
     */
    public static Path regionDir(Path worldFolder, Dimension dim) throws IOException {
        if (!Files.isDirectory(worldFolder)) return null;
        List<Path> found;
        try (Stream<Path> s = Files.walk(worldFolder, 5)) {
            found = s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equals("region")).map(worldFolder::relativize).toList();
        }
        List<Path> fit = new ArrayList<>();
        for (Path p : found) {
            String t = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            boolean nether = t.contains("dim-1") || t.contains("the_nether");
            boolean end = t.contains("dim1/") || t.startsWith("dim1") || t.contains("the_end");
            if (dim == Dimension.OVERWORLD && !nether && !end) fit.add(p);
            if (dim == Dimension.NETHER && nether) fit.add(p);
            if (dim == Dimension.END && end) fit.add(p);
        }
        return fit.stream().min(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString)).orElse(null);
    }

    /** Reads one block of a chunk column (local x, z 0..15, world y). */
    public interface Column<T> {
        T at(int x, int y, int z);
    }

    /** The positions (world coordinates) in a chunk whose block differs between then and now. */
    public static <T> List<Pos> differences(Chunk c, int minY, int maxY, Column<T> then, Column<T> now) {
        List<Pos> out = new ArrayList<>();
        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    T a = then.at(x, y, z), b = now.at(x, y, z);
                    if (a == null ? b != null : !a.equals(b)) out.add(new Pos((c.x() << 4) + x, y, (c.z() << 4) + z));
                }
            }
        }
        return out;
    }

    /** Work cut into batches of at most {@code size} (the last one shorter), in order. */
    public static <T> List<List<T>> batches(List<T> all, int size) {
        int n = Math.max(1, size);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += n) out.add(all.subList(i, Math.min(all.size(), i + n)));
        return out;
    }

    /** Positions sorted bottom up (so what stands on something is placed after it). */
    public static void bottomUp(List<Pos> positions) {
        positions.sort(Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
    }
}
