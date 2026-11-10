package dev.stsv.core.vehicle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads an Ogg Vorbis file's length and channels from its pages (no decoder): the Vorbis identification header holds
 * the channels and sample rate, the last page's granule position the number of samples.
 */
final class OggLength {

    private OggLength() {}

    static double seconds(Path ogg) throws IOException {
        byte[] b = Files.readAllBytes(ogg);
        long samples = -1;
        for (int at = 0; at + 27 <= b.length; ) {
            if (!capture(b, at)) throw new IOException(ogg + ": not an Ogg page at " + at);
            long granule = ByteBuffer.wrap(b, at + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (granule >= 0) samples = granule;
            at = next(b, at);
        }
        if (samples < 0) throw new IOException(ogg + ": no granule position");
        return samples / (double) rate(b);
    }

    static int channels(Path ogg) throws IOException {
        byte[] b = Files.readAllBytes(ogg);
        return b[identification(b) + 11] & 0xff;
    }

    private static int rate(byte[] b) throws IOException {
        return ByteBuffer.wrap(b, identification(b) + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Where the identification packet starts: right after the first page's header and segment table. */
    private static int identification(byte[] b) throws IOException {
        if (!capture(b, 0)) throw new IOException("not an Ogg file");
        int p = 27 + (b[26] & 0xff);
        if (b[p] != 1 || !new String(b, p + 1, 6, StandardCharsets.US_ASCII).equals("vorbis")) throw new IOException("not Vorbis");
        return p;
    }

    private static boolean capture(byte[] b, int at) {
        return b[at] == 'O' && b[at + 1] == 'g' && b[at + 2] == 'g' && b[at + 3] == 'S';
    }

    private static int next(byte[] b, int at) {
        int segments = b[at + 26] & 0xff, body = 0;
        for (int i = 0; i < segments; i++) body += b[at + 27 + i] & 0xff;
        return at + 27 + segments + body;
    }
}
