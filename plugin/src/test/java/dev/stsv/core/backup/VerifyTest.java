package dev.stsv.core.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The region file check: a sound file whose last chunk is not padded to a whole sector passes, damaged ones do not. */
class VerifyTest {

    @TempDir Path dir;

    /** A region file with one chunk of `length` bytes in sector 2 (header says `sectors`), cut to `size` bytes. */
    private Path region(String name, int length, int sectors, int size) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(Math.max(size, 8192 + 4 + length));
        b.putInt(0, (2 << 8) | sectors);
        b.putInt(8192, length);
        Path p = dir.resolve(name);
        byte[] all = new byte[size];
        System.arraycopy(b.array(), 0, all, 0, size);
        Files.write(p, all);
        return p;
    }

    @Test
    void anUnpaddedLastChunkIsFine() throws Exception {
        assertNull(Verify.region(region("ok.mca", 3000, 1, 8192 + 4 + 3000)), "the server does not pad the last sector");
        assertNull(Verify.region(region("padded.mca", 3000, 1, 8192 + 4096)));
        Path empty = dir.resolve("empty.mca");
        Files.write(empty, new byte[0]);
        assertNull(Verify.region(empty));
    }

    @Test
    void damageIsFound() throws Exception {
        assertNotNull(Verify.region(region("short.mca", 3000, 1, 4000)), "no whole header");
        assertNotNull(Verify.region(region("cut.mca", 3000, 1, 8192 + 100)), "the chunk is cut off");
        assertNotNull(Verify.region(region("toolong.mca", 9000, 1, 8192 + 4 + 9000)), "longer than its sectors");
        assertTrue(Verify.check(dir, null).stream().anyMatch(s -> s.contains("cut.mca")));
    }
}
