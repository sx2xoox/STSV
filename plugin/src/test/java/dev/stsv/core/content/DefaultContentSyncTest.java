package dev.stsv.core.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultContentSyncTest {

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String read(Path p) throws Exception { return Files.readString(p); }

    @Test
    void freshInstallWritesEverything(@TempDir Path dir) throws Exception {
        var r = DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("v1")), Map.of());
        assertEquals(1, r.installed());
        assertEquals("v1", read(dir.resolve("stsv/a.yml")));
    }

    @Test
    void untouchedFilesAreUpgradedAndEditsAreKept(@TempDir Path dir) throws Exception {
        DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("v1"), "stsv/b.yml", b("v1")), Map.of());
        Files.writeString(dir.resolve("stsv/b.yml"), "my edit");

        var r = DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("v2"), "stsv/b.yml", b("v2")), Map.of());
        assertEquals(1, r.updated());
        assertEquals(List.of("stsv/b.yml"), r.kept());
        assertEquals("v2", read(dir.resolve("stsv/a.yml")));
        assertEquals("my edit", read(dir.resolve("stsv/b.yml")));

        // still kept on the next update, even though the manifest was rewritten in between
        r = DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("v3"), "stsv/b.yml", b("v3")), Map.of());
        assertEquals(List.of("stsv/b.yml"), r.kept());
        assertEquals("v3", read(dir.resolve("stsv/a.yml")));
    }

    @Test
    void legacyInstallWithoutManifestUsesShippedHistory(@TempDir Path dir) throws Exception {
        // a 0.1.0 server: files on disk, no manifest
        Files.createDirectories(dir.resolve("stsv"));
        Files.writeString(dir.resolve("stsv/a.yml"), "old shipped");
        Files.writeString(dir.resolve("stsv/b.yml"), "owner wrote this");
        var history = Map.of("stsv/a.yml", Set.of(DefaultContentSync.sha1(b("old shipped"))),
                "stsv/b.yml", Set.of(DefaultContentSync.sha1(b("old shipped b"))));

        var r = DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("new"), "stsv/b.yml", b("new b")), history);
        assertEquals(1, r.updated());
        assertEquals(List.of("stsv/b.yml"), r.kept());
        assertEquals("new", read(dir.resolve("stsv/a.yml")));
        assertEquals("owner wrote this", read(dir.resolve("stsv/b.yml")));
    }

    @Test
    void filesNoLongerShippedAreRemovedUnlessEdited(@TempDir Path dir) throws Exception {
        DefaultContentSync.sync(dir, Map.of("stsv/old.yml", b("v1"), "stsv/mine.yml", b("v1"), "stsv/keep.yml", b("k")),
                Map.of());
        Files.writeString(dir.resolve("stsv/mine.yml"), "edited");
        var r = DefaultContentSync.sync(dir, Map.of("stsv/keep.yml", b("k")), Map.of());
        assertEquals(1, r.removed());
        assertEquals(List.of("stsv/mine.yml"), r.kept());
        assertEquals(false, Files.exists(dir.resolve("stsv/old.yml")));
        assertEquals("edited", read(dir.resolve("stsv/mine.yml")));
    }

    @Test
    void legacyFilesFromHistoryAreRemoved(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("stsv"));
        Files.writeString(dir.resolve("stsv/smelter.bbmodel"), "old shipped");
        var history = Map.of("stsv/smelter.bbmodel", Set.of(DefaultContentSync.sha1(b("old shipped"))));
        var r = DefaultContentSync.sync(dir, Map.of(), history);
        assertEquals(1, r.removed());
    }

    @Test
    void deletedFilesComeBack(@TempDir Path dir) throws Exception {
        DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("v1")), Map.of());
        Files.delete(dir.resolve("stsv/a.yml"));
        var r = DefaultContentSync.sync(dir, Map.of("stsv/a.yml", b("v1")), Map.of());
        assertEquals(1, r.installed());
    }
}
