package dev.stsv.core.painting;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paintings on disk: plugins/STSV/paintings/&lt;map id&gt;.stsvp, one file each ({@link Painting#encode()}), read when first
 * needed and kept in memory. Writes go to a temporary file first and replace the old one in one move, so a crash never
 * leaves half a painting. No Bukkit: the module decides when (and on which thread) to write.
 */
public final class PaintingStore {

    private static final String EXT = ".stsvp";
    private final Path dir;
    private final Map<Integer, Painting> loaded = new ConcurrentHashMap<>();

    public PaintingStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public Path file(int mapId) {
        return dir.resolve(mapId + EXT);
    }

    /** A painting with this map id is stored (the map belongs to the paintings). */
    public boolean exists(int mapId) {
        return loaded.containsKey(mapId) || Files.isRegularFile(file(mapId));
    }

    /** The painting of a map, read from disk the first time; empty if there is none (or it cannot be read). */
    public Optional<Painting> get(int mapId) {
        Painting p = loaded.get(mapId);
        if (p != null) return Optional.of(p);
        Path f = file(mapId);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            Painting read = Painting.decode(Files.readAllBytes(f));
            if (read.mapId() != mapId) return Optional.empty();
            Painting raced = loaded.putIfAbsent(mapId, read);
            return Optional.of(raced != null ? raced : read);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Keeps a new painting in memory (write it with {@link #write}). */
    public void add(Painting p) {
        loaded.put(p.mapId(), p);
    }

    /** Writes the encoded painting (from {@link Painting#encode()}, taken on the main thread) to its file. */
    public void write(int mapId, byte[] encoded) throws IOException {
        Path f = file(mapId);
        Path tmp = dir.resolve(mapId + EXT + ".tmp");
        Files.write(tmp, encoded);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The map ids of every stored painting. */
    public List<Integer> ids() throws IOException {
        List<Integer> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(EXT)) continue;
                try {
                    out.add(Integer.parseInt(n.substring(0, n.length() - EXT.length())));
                } catch (NumberFormatException ignored) {
                    // not ours
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** Paintings read so far. */
    public java.util.Collection<Painting> loaded() {
        return loaded.values();
    }
}
