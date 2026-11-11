package dev.stsv.economy.road;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 국도 되돌리기 (README "국도", 되돌리기): every block a road changes, recorded as it is built (where, what was there, what
 * the road put), written beside its job (roads/&lt;dock&gt;.undo, a patch's &lt;dock&gt;.tail.undo) and put back in reverse
 * order by /관리자 국도 &lt;하역장&gt; 되돌리기. Pure (unit-tested): block states are their strings
 * ("minecraft:oak_stairs[facing=east,...]"), a container's contents an opaque blob ({@link RoadModule} makes it).
 *
 * <p>The file is a run of frames, appended as the road is built (each save: {@link Recorder#take}), so it never has to be
 * held whole: a frame is 'R' 'U', its length (4 bytes), then gzip of: a version, the frame's own palette of block states,
 * the changes (x, y, z as zig-zag varint steps from the change before, the palette ids of before and after as varints; a
 * road changes the blocks of a column one after the other, so most steps are a byte each) and the containers' contents. A
 * frame cut short (the server stopped mid-write) is left out.
 *
 * <p>Putting back ({@link Restorer}), the newest change first: a block is put back only while it is still what the road
 * put ({@link #decide}): the same block (its state may have changed: a wall joined to a new neighbour, grass under snow)
 * or, where the road cleared it, water or lava that ran in. A block a player changed since is left as it is and counted.
 */
public final class RoadUndo {

    private RoadUndo() {}

    static final int VERSION = 1;
    /** A frame's mark. */
    static final byte M0 = 'R', M1 = 'U';
    /** A frame is taken once it has this many changes, whatever the time (memory). */
    public static final int FRAME_MAX = 50_000;

    /** A growable byte array with varints. */
    static final class Bytes {
        byte[] a = new byte[1024];
        int n;

        void put(int b) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = (byte) b;
        }

        void varint(int v) {
            while ((v & ~0x7f) != 0) {
                put((v & 0x7f) | 0x80);
                v >>>= 7;
            }
            put(v);
        }

        void zigzag(int v) {
            varint((v << 1) ^ (v >> 31));
        }

        void bytes(byte[] b) {
            varint(b.length);
            for (byte x : b) put(x);
        }
    }

    /** Reads what {@link Bytes} wrote. */
    static final class Reader {
        final byte[] a;
        int i;

        Reader(byte[] a) {
            this.a = a;
        }

        int varint() {
            int v = 0, shift = 0;
            while (true) {
                if (i >= a.length) throw new IllegalStateException("되돌리기 기록이 잘림");
                int b = a[i++] & 0xff;
                v |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return v;
                shift += 7;
                if (shift > 28) throw new IllegalStateException("되돌리기 기록이 깨짐");
            }
        }

        int zigzag() {
            int v = varint();
            return (v >>> 1) ^ -(v & 1);
        }

        byte[] bytes() {
            int len = varint();
            if (len < 0 || i + len > a.length) throw new IllegalStateException("되돌리기 기록이 잘림");
            byte[] out = Arrays.copyOfRange(a, i, i + len);
            i += len;
            return out;
        }
    }

    /** The changes of one road as it is built, taken off in frames ({@link #take}). Main thread only. */
    public static final class Recorder {
        private Map<String, Integer> ids = new HashMap<>();
        private List<String> names = new ArrayList<>();
        private Bytes body = new Bytes();
        private int count, lx, ly, lz;
        private Map<Integer, byte[]> extras = new HashMap<>();
        private long total;

        /** A block changed: where, its state before and after, a container's contents it held (or null). */
        public void add(int x, int y, int z, String before, String after, byte[] extra) {
            if (extra != null) extras.put(count, extra);
            body.zigzag(x - lx);
            body.zigzag(y - ly);
            body.zigzag(z - lz);
            lx = x;
            ly = y;
            lz = z;
            body.varint(id(before));
            body.varint(id(after));
            count++;
            total++;
        }

        private int id(String s) {
            Integer i = ids.get(s);
            if (i != null) return i;
            ids.put(s, names.size());
            names.add(s);
            return names.size() - 1;
        }

        /** Changes since the last take. */
        public int size() {
            return count;
        }

        /** Changes ever recorded here. */
        public long total() {
            return total;
        }

        /**
         * What was recorded since the last take, as a frame to append to the file (made when {@link Taken#record} is
         * called: off the main thread); empties it. Null when nothing was.
         */
        public Taken take() {
            if (count == 0) return null;
            Taken t = new Taken(names.toArray(String[]::new), body, count, extras);
            ids = new HashMap<>();
            names = new ArrayList<>();
            body = new Bytes();
            extras = new HashMap<>();
            count = lx = ly = lz = 0;
            return t;
        }
    }

    /** A frame taken off a {@link Recorder}, not encoded yet. */
    public static final class Taken {
        private final String[] palette;
        private final Bytes body;
        private final int count;
        private final Map<Integer, byte[]> extras;

        Taken(String[] palette, Bytes body, int count, Map<Integer, byte[]> extras) {
            this.palette = palette;
            this.body = body;
            this.count = count;
            this.extras = extras;
        }

        public int count() {
            return count;
        }

        /** The frame as appended to the file: its mark, its length, its gzipped bytes. */
        public byte[] record() {
            Bytes raw = new Bytes();
            raw.varint(VERSION);
            raw.varint(palette.length);
            for (String s : palette) raw.bytes(s.getBytes(StandardCharsets.UTF_8));
            raw.varint(count);
            byte[] all = Arrays.copyOf(raw.a, raw.n + body.n);
            System.arraycopy(body.a, 0, all, raw.n, body.n);
            Bytes tail = new Bytes();
            tail.varint(extras.size());
            extras.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                tail.varint(e.getKey());
                tail.bytes(e.getValue());
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, all.length / 4));
            try (GZIPOutputStream gz = new GZIPOutputStream(out, 8192)) {
                gz.write(all);
                gz.write(tail.a, 0, tail.n);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            byte[] z = out.toByteArray();
            byte[] rec = new byte[6 + z.length];
            rec[0] = M0;
            rec[1] = M1;
            rec[2] = (byte) (z.length >>> 24);
            rec[3] = (byte) (z.length >>> 16);
            rec[4] = (byte) (z.length >>> 8);
            rec[5] = (byte) z.length;
            System.arraycopy(z, 0, rec, 6, z.length);
            return rec;
        }
    }

    /** A file's frames (gzipped), in the order written; a frame cut short or broken, and all after it, left out. */
    public static List<byte[]> frames(byte[] file) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (file != null && i + 6 <= file.length) {
            if (file[i] != M0 || file[i + 1] != M1) break;
            int len = ((file[i + 2] & 0xff) << 24) | ((file[i + 3] & 0xff) << 16) | ((file[i + 4] & 0xff) << 8) | (file[i + 5] & 0xff);
            if (len < 0 || i + 6 + len > file.length) break;
            out.add(Arrays.copyOfRange(file, i + 6, i + 6 + len));
            i += 6 + len;
        }
        return out;
    }

    /** One frame read back: its palette and changes in the order made, containers' contents by change. */
    public record Frame(String[] palette, int[] x, int[] y, int[] z, int[] before, int[] after, Map<Integer, byte[]> extras) {
        public int count() {
            return x.length;
        }

        /** The chunks its changes are in. */
        public Set<Long> chunks() {
            Set<Long> out = new HashSet<>();
            for (int i = 0; i < x.length; i++) out.add(RoadJob.key(x[i] >> 4, z[i] >> 4));
            return out;
        }
    }

    public static Frame decode(byte[] gz) {
        byte[] raw;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            raw = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Reader r = new Reader(raw);
        int v = r.varint();
        if (v != VERSION) throw new IllegalStateException("되돌리기 기록 버전이 다름: " + v);
        String[] palette = new String[r.varint()];
        for (int i = 0; i < palette.length; i++) palette[i] = new String(r.bytes(), StandardCharsets.UTF_8);
        int n = r.varint();
        int[] x = new int[n], y = new int[n], z = new int[n], before = new int[n], after = new int[n];
        int lx = 0, ly = 0, lz = 0;
        for (int i = 0; i < n; i++) {
            lx += r.zigzag();
            ly += r.zigzag();
            lz += r.zigzag();
            x[i] = lx;
            y[i] = ly;
            z[i] = lz;
            before[i] = r.varint();
            after[i] = r.varint();
        }
        Map<Integer, byte[]> extras = new HashMap<>();
        int e = r.varint();
        for (int k = 0; k < e; k++) {
            int at = r.varint();
            extras.put(at, r.bytes());
        }
        return new Frame(palette, x, y, z, before, after, extras);
    }

    /** What putting a block back comes to. */
    public enum Outcome {
        /** it is what the road put: put back */
        RESTORE,
        /** it is already what was there before (put back by an earlier, stopped 되돌리기) */
        ALREADY,
        /** something else is there now (a player changed it): left alone */
        SKIP
    }

    /** The block's kind of a state string ("minecraft:oak_stairs[...]" → "minecraft:oak_stairs"). */
    static String kind(String state) {
        int b = state.indexOf('[');
        return b < 0 ? state : state.substring(0, b);
    }

    static boolean air(String state) {
        String k = kind(state);
        return k.equals("minecraft:air") || k.equals("minecraft:cave_air") || k.equals("minecraft:void_air");
    }

    /** Water or lava that is not a source (it ran in). */
    static boolean flowing(String state) {
        String k = kind(state);
        return (k.equals("minecraft:water") || k.equals("minecraft:lava")) && state.contains("level=") && !state.contains("level=0]")
                && !state.contains("level=0,");
    }

    /** Whether a block whose state is now `current` goes back to `before` (the road changed it from that to `after`). */
    public static Outcome decide(String current, String before, String after) {
        if (current.equals(after)) return Outcome.RESTORE;
        if (current.equals(before)) return Outcome.ALREADY;
        if (kind(current).equals(kind(after))) return Outcome.RESTORE;   // the road's block, its state changed since
        if (air(after) && flowing(current)) return Outcome.RESTORE;      // water or lava ran into what the road cleared
        return Outcome.SKIP;
    }

    /** The world as putting back sees it. */
    public interface Blocks {
        /** A block's state now ("minecraft:stone"). */
        String get(int x, int y, int z);

        /** Puts a state back (no physics), and a container's contents when there were any (extra non-null). */
        void set(int x, int y, int z, String state, byte[] extra);
    }

    /**
     * Puts a road's changes back, newest first, a few at a time ({@link #step}); one frame at a time, so its chunks
     * ({@link #chunks}) can be loaded first.
     */
    public static final class Restorer {
        private final List<byte[]> frames;
        private int frame;
        private Frame cur;
        private int entry = -1;
        private long restored, already, containers;
        private final Set<Long> skipped = new HashSet<>();

        /** frames: every file's frames in the order written (a road's, then its patch's). */
        public Restorer(List<byte[]> frames) {
            this.frames = frames;
            this.frame = frames.size();
        }

        private void advance() {
            while ((cur == null || entry < 0) && frame > 0) {
                cur = decode(frames.get(--frame));
                entry = cur.count() - 1;
            }
            if (cur != null && entry < 0) cur = null;
        }

        public boolean done() {
            advance();
            return cur == null;
        }

        /** The chunks the changes now being put back are in (load them before {@link #step}). */
        public Set<Long> chunks() {
            advance();
            return cur == null ? Set.of() : cur.chunks();
        }

        /** Puts back up to max changes, never past the end of a frame. How many were looked at. */
        public int step(int max, Blocks w) {
            advance();
            int n = 0;
            while (cur != null && n < max && entry >= 0) {
                int i = entry--;
                n++;
                String before = cur.palette()[cur.before()[i]], after = cur.palette()[cur.after()[i]];
                int x = cur.x()[i], y = cur.y()[i], z = cur.z()[i];
                switch (decide(w.get(x, y, z), before, after)) {
                    case RESTORE -> {
                        byte[] extra = cur.extras().get(i);
                        w.set(x, y, z, before, extra);
                        restored++;
                        if (extra != null) containers++;
                    }
                    case ALREADY -> {
                        already++;
                        // a block the road changed more than once, put back by an earlier 되돌리기: its later changes
                        // did not match only because it is back already
                        skipped.remove(pos(x, y, z));
                    }
                    case SKIP -> skipped.add(pos(x, y, z));
                }
            }
            if (cur != null && entry < 0) cur = null;   // the frame is done: the next one's chunks come first
            return n;
        }

        static long pos(int x, int y, int z) {
            return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
        }

        public long restored() {
            return restored;
        }

        public long already() {
            return already;
        }

        /** Blocks left alone because something else is there now (each block once). */
        public int skipped() {
            return skipped.size();
        }

        /** Where those are ({x, y, z}), a few for the log. */
        public List<int[]> skippedAt(int most) {
            List<int[]> out = new ArrayList<>();
            for (long p : skipped) {
                if (out.size() >= most) break;
                out.add(new int[]{(int) (p >> 38) << 6 >> 6, (int) (p << 52 >> 52), (int) (p << 26 >> 38)});
            }
            return out;
        }

        public long containers() {
            return containers;
        }

        /** Frames put back (wholly or in part) of all. */
        public int framesDone() {
            return frames.size() - frame;
        }

        public int frames() {
            return frames.size();
        }
    }
}
