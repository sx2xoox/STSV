package dev.stsv.core.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backups: which ones stay, and a copy that shares unchanged files with the one before. */
class BackupTest {

    @Test
    void hoursThenDaysThenWeeks() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 12, 0);
        List<LocalDateTime> all = new ArrayList<>();
        for (int h = 0; h < 24 * 40; h++) all.add(now.minusHours(h));      // hourly for 40 days
        Retention r = new Retention(24, 7, 4);
        Set<LocalDateTime> kept = new HashSet<>(all);
        kept.removeAll(r.drop(all, now));
        // the last 24 hours, all of them
        for (int h = 0; h < 24; h++) assertTrue(kept.contains(now.minusHours(h)), "hour " + h);
        // one a day for the last 7 days (the newest of each)
        for (int d = 1; d < 7; d++) {
            var day = now.toLocalDate().minusDays(d);
            // (yesterday afternoon is still inside the last 24 hours, so it keeps more than one)
            if (d >= 2) assertEquals(1, kept.stream().filter(t -> t.toLocalDate().equals(day)).count(), "day " + day);
            assertTrue(kept.contains(day.atTime(23, 0)), "the newest of " + day);
        }
        // nothing older than four weeks
        assertTrue(kept.stream().allMatch(t -> !t.isBefore(now.minusWeeks(4).minusDays(7))), kept.toString());
        assertTrue(kept.size() <= 24 + 7 + 4, kept.size() + "");
        // (the newest of today and yesterday, and of this week, are among the last 24 already)
        assertTrue(kept.size() >= 24 + 5, kept.size() + "");
        // fewer backups than the rule allows: nothing goes
        assertTrue(r.drop(List.of(now, now.minusHours(1)), now).isEmpty());
    }

    @Test
    void unchangedFilesAreSharedNotCopied(@TempDir Path dir) throws Exception {
        Path world = dir.resolve("server/world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
        Files.writeString(world.resolve("region/r.1.0.mca"), "more chunks");
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("session.lock"), "lock");
        Path first = dir.resolve("backups/1/world");
        var a = Snapshot.copy(world, first, null, rel -> rel.getFileName().toString().equals("session.lock"));
        assertEquals(3, a.files);
        assertEquals(3, a.copied);
        assertFalse(Files.exists(first.resolve("session.lock")), "the lock is left out");

        // one region changes (newer time, other size), the rest does not
        Files.writeString(world.resolve("region/r.0.0.mca"), "chunks, changed");
        Files.setLastModifiedTime(world.resolve("region/r.0.0.mca"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Path second = dir.resolve("backups/2/world");
        var b = Snapshot.copy(world, second, first, rel -> rel.getFileName().toString().equals("session.lock"));
        assertEquals(3, b.files);
        assertEquals(1, b.copied, "only the changed region is copied");
        assertEquals(2, b.linked);
        assertEquals("chunks, changed", Files.readString(second.resolve("region/r.0.0.mca")));
        assertEquals("chunks", Files.readString(first.resolve("region/r.0.0.mca")), "the old backup keeps the old region");
        assertEquals("more chunks", Files.readString(second.resolve("region/r.1.0.mca")));

        // deleting the old backup leaves the new one whole
        Snapshot.delete(dir.resolve("backups/1"));
        assertEquals("more chunks", Files.readString(second.resolve("region/r.1.0.mca")));
        assertEquals("level", Files.readString(second.resolve("level.dat")));
    }
}
