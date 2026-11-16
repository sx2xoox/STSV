package dev.stsv.core.backup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Copies a folder into a backup, sharing what did not change with the backup before it.
 *
 * <p>Every backup is a whole, plain copy that can be put back by itself (no chain of increments to replay). A file whose
 * size and last-modified time are the same as in the previous backup is not copied again but hard-linked to it, so
 * it takes no more room: an hour's backup of a big world costs only the region files that changed that hour. Deleting
 * an old backup frees only what no other backup shares. Where the file system cannot link (another kind of disk), the
 * file is copied.
 */
public final class Snapshot {

    /** What one copy did: files seen, copied anew, shared with the previous backup, bytes copied anew. */
    public static final class Stats {
        public long files, copied, linked, bytes;

        void add(Stats o) {
            files += o.files;
            copied += o.copied;
            linked += o.linked;
            bytes += o.bytes;
        }
    }

    private Snapshot() {}

    /**
     * Copies {@code from} into {@code to} (made), linking to {@code previous} (the same folder in the last backup; null
     * or missing: copy everything). {@code skip}: a path (relative to {@code from}) left out, with everything under it.
     */
    public static Stats copy(Path from, Path to, Path previous, Predicate<Path> skip) throws IOException {
        Stats s = new Stats();
        if (!Files.isDirectory(from)) return s;
        Files.createDirectories(to);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = from.relativize(dir);
                if (!rel.toString().isEmpty() && skip.test(rel)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = from.relativize(file);
                if (skip.test(rel) || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                Path target = to.resolve(rel.toString());
                s.files++;
                Path old = previous == null ? null : previous.resolve(rel.toString());
                if (old != null && same(attrs, old) && link(target, old)) {
                    s.linked++;
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (java.nio.file.NoSuchFileException gone) {
                    s.files--;          // removed while we walked (a temporary file): nothing to keep
                    return FileVisitResult.CONTINUE;
                }
                s.copied++;
                s.bytes += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;   // vanished or locked mid-walk: the rest still goes in
            }
        });
        return s;
    }

    private static boolean same(BasicFileAttributes now, Path old) {
        try {
            BasicFileAttributes was = Files.readAttributes(old, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return was.isRegularFile() && was.size() == now.size() && was.lastModifiedTime().equals(now.lastModifiedTime());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean link(Path target, Path existing) {
        try {
            Files.createLink(target, existing);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** Adds b into a (for totals). */
    public static void sum(Stats a, Stats b) {
        a.add(b);
    }

    /** Deletes a folder and everything in it (an old backup). */
    public static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** The bytes a folder's files take (shared files counted in each backup that has them). */
    public static long size(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
