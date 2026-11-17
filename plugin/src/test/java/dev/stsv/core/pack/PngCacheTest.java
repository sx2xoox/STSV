package dev.stsv.core.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optimised pictures are kept between pack builds and between server runs (a rebuild must not take a minute). */
class PngCacheTest {

    @TempDir Path dir;

    @BeforeEach
    @AfterEach
    void clean() {
        PngCache.forget();
    }

    private static byte[] picture(int seed) throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFF000000 | ((x * seed) & 0xFF) << 16 | (y * 7) << 8 | (x ^ y));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void aPictureIsOptimisedOnceAndGivesTheOptimisersBytes() throws Exception {
        byte[] png = picture(3);
        byte[] first = PngCache.optimized(png);
        assertArrayEquals(PngOptimizer.optimize(png), first);
        assertSame(first, PngCache.optimized(png.clone()), "the second request is answered from the cache");
    }

    @Test
    void theFileBringsTheResultsBackAfterARestart() throws Exception {
        Path file = dir.resolve("png-cache.bin");
        byte[] a = picture(3), b = picture(5);
        byte[] smallA = PngCache.optimized(a), smallB = PngCache.optimized(b);
        PngCache.save(file);
        assertTrue(Files.size(file) > 0);

        PngCache.forget();
        PngCache.load(file);
        assertEquals(2, PngCache.size());
        assertArrayEquals(smallA, PngCache.optimized(a));
        assertArrayEquals(smallB, PngCache.optimized(b));
    }

    @Test
    void picturesNoLongerInTheContentLeaveTheFile() throws Exception {
        Path file = dir.resolve("png-cache.bin");
        byte[] a = picture(3), b = picture(5);
        PngCache.optimized(a);
        PngCache.optimized(b);
        PngCache.save(file);
        PngCache.optimized(a);          // the next build has only this one
        PngCache.save(file);
        assertEquals(1, PngCache.size());
    }

    @Test
    void aDamagedOrOlderFileIsNotUsed() throws Exception {
        Path file = dir.resolve("png-cache.bin");
        PngCache.optimized(picture(3));
        PngCache.save(file);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x55;
        Files.write(file, bytes);

        PngCache.forget();
        PngCache.load(file);
        assertEquals(0, PngCache.size());

        Files.write(file, new byte[]{1, 2, 3});
        PngCache.forget();
        PngCache.load(file);
        assertEquals(0, PngCache.size());
    }
}
