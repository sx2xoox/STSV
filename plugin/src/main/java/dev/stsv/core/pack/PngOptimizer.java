package dev.stsv.core.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Lossless re-encoding of RGBA PNG textures: the smallest of the original, an 8-bit palette image with a tRNS chunk
 * and a re-compressed RGBA image, each tried with every PNG filter. The decoded pixels are the same (checked by
 * decoding the result), and the decoded channel count stays 4: the client's PNG reader (stb_image) expands a palette
 * image that has a tRNS chunk to RGBA, just like a truecolour + alpha image.
 *
 * Only plain 8-bit, non-interlaced images that decode to RGBA and carry no colour chunks (gAMA, iCCP, sRGB...) are
 * touched; anything else is returned unchanged.
 */
public final class PngOptimizer {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final Set<String> PLAIN = Set.of("IHDR", "PLTE", "tRNS", "IDAT", "IEND");

    private PngOptimizer() {}

    public static byte[] optimize(byte[] png) {
        try {
            Map<String, byte[]> chunks = chunks(png);
            if (chunks == null) return png;
            byte[] ihdr = chunks.get("IHDR");
            int depth = ihdr[8] & 0xFF, type = ihdr[9] & 0xFF, interlace = ihdr[12] & 0xFF;
            boolean rgba = type == 6 || (type == 3 && chunks.containsKey("tRNS"));
            if (depth != 8 || interlace != 0 || !rgba) return png;

            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return png;
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);

            byte[] best = png;
            byte[] palette = palette(w, h, argb);
            if (palette != null && palette.length < best.length) best = palette;
            byte[] truecolour = truecolour(w, h, argb);
            if (truecolour.length < best.length) best = truecolour;
            if (best == png) return png;

            BufferedImage check = ImageIO.read(new ByteArrayInputStream(best));
            if (check == null || check.getWidth() != w || check.getHeight() != h
                    || !Arrays.equals(argb, check.getRGB(0, 0, w, h, null, 0, w))) return png;
            return best;
        } catch (IOException | RuntimeException e) {
            return png;
        }
    }

    /** Chunk type -> data of a PNG with only plain chunks (one IDAT joined), or null if it has anything else. */
    private static Map<String, byte[]> chunks(byte[] png) {
        if (png.length < 8 || !Arrays.equals(Arrays.copyOf(png, 8), SIGNATURE)) return null;
        Map<String, byte[]> out = new LinkedHashMap<>();
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int i = 8;
        while (i + 12 <= png.length) {
            int len = ((png[i] & 0xFF) << 24) | ((png[i + 1] & 0xFF) << 16) | ((png[i + 2] & 0xFF) << 8) | (png[i + 3] & 0xFF);
            if (len < 0 || i + 12 + len > png.length) return null;
            String t = new String(png, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (!PLAIN.contains(t)) return null;
            byte[] data = Arrays.copyOfRange(png, i + 8, i + 8 + len);
            if (t.equals("IDAT")) idat.writeBytes(data);
            else if (out.put(t, data) != null) return null;
            i += 12 + len;
            if (t.equals("IEND")) break;
        }
        if (!out.containsKey("IHDR") || out.get("IHDR").length != 13) return null;
        out.put("IDAT", idat.toByteArray());
        return out;
    }

    /** 8-bit palette + tRNS (at least one entry, so the image always decodes to RGBA), or null if > 256 colours. */
    static byte[] palette(int w, int h, int[] argb) throws IOException {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int c : argb) {
            counts.merge(c, 1, Integer::sum);
            if (counts.size() > 256) return null;
        }
        // translucent entries first so tRNS can stop at the last one; more frequent first within each part
        Integer[] colours = counts.keySet().toArray(new Integer[0]);
        Arrays.sort(colours, (a, b) -> {
            boolean oa = (a >>> 24) == 0xFF, ob = (b >>> 24) == 0xFF;
            if (oa != ob) return oa ? 1 : -1;
            int byCount = Integer.compare(counts.get(b), counts.get(a));
            return byCount != 0 ? byCount : Integer.compareUnsigned(a, b);
        });
        Map<Integer, Integer> index = new LinkedHashMap<>();
        byte[] plte = new byte[colours.length * 3];
        int translucent = 0;
        for (int i = 0; i < colours.length; i++) {
            int c = colours[i];
            index.put(c, i);
            plte[i * 3] = (byte) (c >> 16);
            plte[i * 3 + 1] = (byte) (c >> 8);
            plte[i * 3 + 2] = (byte) c;
            if ((c >>> 24) != 0xFF) translucent = i + 1;
        }
        byte[] trns = new byte[Math.max(1, translucent)];
        for (int i = 0; i < trns.length; i++) trns[i] = (byte) (colours[i] >>> 24);
        byte[] raw = new byte[w * h];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (int) index.get(argb[i]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        chunk(out, "IHDR", ihdr(w, h, 3));
        chunk(out, "PLTE", plte);
        chunk(out, "tRNS", trns);
        chunk(out, "IDAT", bestIdat(raw, w, h, 1));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    static byte[] truecolour(int w, int h, int[] argb) throws IOException {
        byte[] raw = new byte[w * h * 4];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            raw[i * 4] = (byte) (c >> 16);
            raw[i * 4 + 1] = (byte) (c >> 8);
            raw[i * 4 + 2] = (byte) c;
            raw[i * 4 + 3] = (byte) (c >>> 24);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        chunk(out, "IHDR", ihdr(w, h, 6));
        chunk(out, "IDAT", bestIdat(raw, w, h, 4));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] ihdr(int w, int h, int type) {
        return new byte[]{(byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h, 8, (byte) type, 0, 0, 0};
    }

    /** Smallest zlib stream over the five fixed filters and the per-row "minimum sum" heuristic. */
    private static byte[] bestIdat(byte[] raw, int w, int h, int bpp) {
        int stride = w * bpp;
        byte[] best = null;
        for (int mode = 0; mode <= 5; mode++) {
            byte[] filtered = new byte[(stride + 1) * h];
            for (int y = 0; y < h; y++) {
                int f = mode;
                if (mode == 5) {
                    long bestSum = Long.MAX_VALUE;
                    for (int t = 0; t < 5; t++) {
                        long sum = 0;
                        for (int x = 0; x < stride; x++) sum += Math.abs((byte) filter(t, raw, y, x, stride, bpp));
                        if (sum < bestSum) { bestSum = sum; f = t; }
                    }
                }
                filtered[y * (stride + 1)] = (byte) f;
                for (int x = 0; x < stride; x++) filtered[y * (stride + 1) + 1 + x] = (byte) filter(f, raw, y, x, stride, bpp);
            }
            for (int strategy : new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED}) {
                Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
                d.setStrategy(strategy);
                d.setInput(filtered);
                d.finish();
                ByteArrayOutputStream z = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!d.finished()) z.write(buf, 0, d.deflate(buf));
                d.end();
                if (best == null || z.size() < best.length) best = z.toByteArray();
            }
        }
        return best;
    }

    private static int filter(int type, byte[] raw, int y, int x, int stride, int bpp) {
        int cur = raw[y * stride + x] & 0xFF;
        int a = x >= bpp ? raw[y * stride + x - bpp] & 0xFF : 0;
        int b = y > 0 ? raw[(y - 1) * stride + x] & 0xFF : 0;
        int c = x >= bpp && y > 0 ? raw[(y - 1) * stride + x - bpp] & 0xFF : 0;
        return switch (type) {
            case 0 -> cur;
            case 1 -> cur - a;
            case 2 -> cur - b;
            case 3 -> cur - ((a + b) >> 1);
            default -> {
                int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                yield cur - (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
            }
        } & 0xFF;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        int len = data.length;
        out.write(len >>> 24); out.write(len >>> 16); out.write(len >>> 8); out.write(len);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long v = crc.getValue();
        out.write((int) (v >>> 24)); out.write((int) (v >>> 16)); out.write((int) (v >>> 8)); out.write((int) v);
    }
}
