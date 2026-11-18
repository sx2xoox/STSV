package dev.stsv.core.minigame.circuit;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * 고스트: one lap of one racer's kart, to be driven again beside a player (the waiting room's choice: none, my best lap,
 * the track record). Pure (unit-tested): no Bukkit types.
 *
 * <h2>What is kept</h2> The kart's place (track coordinates: x and z as the track file, y above {@link TrackLayout#BASE_Y})
 * and heading every {@link #stepTicks()} ticks, whether it was boosting, the lap time and the time of each gate past
 * the line (for the time gap on the screen: {@link #gate}). Sample k is at {@code t0 + k * step} seconds since the lap
 * began (t0 is 0 or a little below: the last sample before the line).
 *
 * <h2>Encoding</h2> {@link #encode}: a small header, the first sample as is, then each next one as the change from the one
 * before, in fixed units (1/64 block, 1/65536 turn) as zig-zag varints: a kart moves a block or two between samples, so a
 * sample takes about 6 to 8 bytes (a minute's lap at a sample every 2 ticks: about 5 KB). Positions are quantised before
 * the differences are taken, so nothing drifts however long the lap. Never more than {@link #MAX_SAMPLES} samples and
 * {@link #MAX_BYTES} bytes; {@link #decode} checks every length and value it reads.
 *
 * <h2>Playback</h2> {@link #at}: the place at any time, linear between samples (the heading the short way round), the nose
 * pitched with the road's slope around it; before the first sample the first, after the last the last.
 */
public final class Ghost {

    static final int MAGIC = 0x47;          // 'G'
    static final int VERSION = 1;
    /** Samples a ghost holds at most (10 minutes at one a tick, 20 at one every two). */
    public static final int MAX_SAMPLES = 12_000;
    /** Bytes an encoded ghost takes at most. */
    public static final int MAX_BYTES = 200_000;
    /** Gates a lap has at most (the track files have a few dozen). */
    static final int MAX_GATES = 256;
    static final double POS_UNIT = 1 / 64.0;
    static final double YAW_UNITS = 65536 / 360.0;
    /** The steepest the nose is drawn (degrees). */
    static final float MAX_PITCH = 35;

    /** Which ghost a player races against (the waiting room's button; kept on the player). */
    public enum Choice {
        NONE("none"), MINE("mine"), RECORD("record");

        public final String id;

        Choice(String id) {
            this.id = id;
        }

        public Choice next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Choice previous() {
            return values()[(ordinal() + values().length - 1) % values().length];
        }

        public static Choice of(String id) {
            for (Choice c : values()) if (c.id.equals(id)) return c;
            return NONE;
        }
    }

    /** Where the ghost kart is at one moment (track coordinates). */
    public record Pose(double x, double y, double z, float yaw, float pitch, boolean boost) {}

    private final int step;
    private final int t0Ms;
    private final int lapMs;
    private final int[] gateMs;
    private final int[] qx, qy, qz;
    private final int[] qyaw;
    private final boolean[] boost;

    private Ghost(int step, int t0Ms, int lapMs, int[] gateMs, int[] qx, int[] qy, int[] qz, int[] qyaw, boolean[] boost) {
        this.step = step;
        this.t0Ms = t0Ms;
        this.lapMs = lapMs;
        this.gateMs = gateMs;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qyaw = qyaw;
        this.boost = boost;
    }

    /**
     * A ghost from samples (track coordinates; yaw in degrees), step ticks apart, the first t0 seconds from the lap's
     * start (0 or a little below), the lap's time and its gates' times (seconds since the start; gate 1 first, the line not
     * included; NaN for a gate not timed).
     */
    public static Ghost of(int stepTicks, double t0, double lapSeconds, double[] gates, double[] x, double[] y, double[] z, float[] yaw, boolean[] boosting) {
        int n = x.length;
        if (n < 2 || n > MAX_SAMPLES || y.length != n || z.length != n || yaw.length != n || boosting.length != n)
            throw new IllegalArgumentException("samples: " + n);
        if (stepTicks < 1 || stepTicks > 20) throw new IllegalArgumentException("step: " + stepTicks);
        if (!(lapSeconds > 0) || lapSeconds > 3600) throw new IllegalArgumentException("lap: " + lapSeconds);
        if (gates.length > MAX_GATES) throw new IllegalArgumentException("gates: " + gates.length);
        int[] g = new int[gates.length];
        for (int k = 0; k < g.length; k++) g[k] = Double.isNaN(gates[k]) ? -1 : (int) Math.round(gates[k] * 1000);
        int[] qx = new int[n], qy = new int[n], qz = new int[n], qyaw = new int[n];
        for (int i = 0; i < n; i++) {
            qx[i] = quantise(x[i]);
            qy[i] = quantise(y[i]);
            qz[i] = quantise(z[i]);
            qyaw[i] = (int) Math.round(wrap(yaw[i]) * YAW_UNITS) & 0xffff;
        }
        if (!(Math.abs(t0) <= 60)) throw new IllegalArgumentException("start: " + t0);
        return new Ghost(stepTicks, (int) Math.round(t0 * 1000), (int) Math.round(lapSeconds * 1000), g, qx, qy, qz, qyaw,
                boosting.clone());
    }

    private static int quantise(double v) {
        if (Double.isNaN(v) || Math.abs(v) > 1_000_000) throw new IllegalArgumentException("coordinate: " + v);
        return (int) Math.round(v / POS_UNIT);
    }

    private static float wrap(float yaw) {
        float y = yaw % 360f;
        return y < 0 ? y + 360f : y;
    }

    // ------------------------------------------------------------------------------------------
    // What it holds
    // ------------------------------------------------------------------------------------------

    public int stepTicks() {
        return step;
    }

    public int samples() {
        return qx.length;
    }

    /** The lap's time, seconds. */
    public double lap() {
        return lapMs / 1000.0;
    }

    public long lapMs() {
        return lapMs;
    }

    /** When the ghost passed gate k (1 = the first past the line), seconds since its lap began; NaN when not known. */
    public double gate(int k) {
        if (k < 1 || k > gateMs.length || gateMs[k - 1] < 0) return Double.NaN;
        return gateMs[k - 1] / 1000.0;
    }

    public int gates() {
        return gateMs.length;
    }

    /** The time of the first sample (seconds since the lap's start, usually 0 or a little below). */
    public double start() {
        return t0Ms / 1000.0;
    }

    /** The time of the last sample. */
    public double end() {
        return start() + (samples() - 1) * step * 0.05;
    }

    // ------------------------------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------------------------------

    /** Where the ghost is t seconds after its lap began (held at the ends). */
    public Pose at(double t) {
        double f = (t - start()) / (step * 0.05);
        int last = samples() - 1;
        if (!(f > 0)) f = 0;
        if (f >= last) f = last;
        int i = Math.min((int) Math.floor(f), last - 1);
        double a = f - i;
        double x = lerp(qx[i], qx[i + 1], a) * POS_UNIT, y = lerp(qy[i], qy[i + 1], a) * POS_UNIT, z = lerp(qz[i], qz[i + 1], a) * POS_UNIT;
        double y0 = qyaw[i] / YAW_UNITS, y1 = qyaw[i + 1] / YAW_UNITS;
        double d = ((y1 - y0) % 360 + 540) % 360 - 180;
        float yaw = (float) ((y0 + d * a) % 360);
        if (yaw < 0) yaw += 360;
        return new Pose(x, y, z, yaw, pitch(i, a), a < 0.5 ? boost[i] : boost[i + 1]);
    }

    private static double lerp(int a, int b, double t) {
        return a + (b - a) * t;
    }

    /**
     * The nose's pitch: the slope between the samples a little behind and ahead (about half a second's road), so a
     * single bump does not shake it. Minecraft's pitch: + is nose down.
     */
    private float pitch(int i, double a) {
        int span = Math.max(1, 4 / step);
        int from = Math.max(0, i - span + 1), to = Math.min(samples() - 1, i + span);
        double dx = (qx[to] - qx[from]) * POS_UNIT, dz = (qz[to] - qz[from]) * POS_UNIT, dy = (qy[to] - qy[from]) * POS_UNIT;
        double flat = Math.hypot(dx, dz);
        if (flat < 0.4) return 0;
        float p = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return Math.clamp(p, -MAX_PITCH, MAX_PITCH);
    }

    // ------------------------------------------------------------------------------------------
    // The time gap
    // ------------------------------------------------------------------------------------------

    /** The gap at a gate: mine minus the ghost's (seconds; - = ahead of the ghost), NaN when either is unknown. */
    public static double gap(double mine, double ghost) {
        return Double.isNaN(mine) || Double.isNaN(ghost) ? Double.NaN : mine - ghost;
    }

    /** "+0.42" (behind the ghost) / "-1.10" (ahead), hundredths; "±0.00" when level. */
    public static String gapText(double gap) {
        if (Double.isNaN(gap)) return "";
        long cs = Math.round(gap * 100);
        if (cs == 0) return "±0.00";
        return (cs > 0 ? "+" : "-") + String.format(Locale.ROOT, "%d.%02d", Math.abs(cs) / 100, Math.abs(cs) % 100);
    }

    // ------------------------------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------------------------------

    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + samples() * 7);
        out.write(MAGIC);
        out.write(VERSION);
        out.write(step);
        varint(out, samples());
        varint(out, zig(t0Ms));
        varint(out, lapMs);
        varint(out, gateMs.length);
        for (int g : gateMs) varint(out, g + 1);       // -1 (not timed) as 0
        varint(out, zig(qx[0]));
        varint(out, zig(qy[0]));
        varint(out, zig(qz[0]));
        varint(out, qyaw[0] << 1 | (boost[0] ? 1 : 0));
        for (int i = 1; i < samples(); i++) {
            varint(out, zig(qx[i] - qx[i - 1]));
            // the boost rides in the height's lowest bit (it changes rarely; the height changes little)
            varint(out, zig(qy[i] - qy[i - 1]) << 1 | (boost[i] ? 1 : 0));
            varint(out, zig(qz[i] - qz[i - 1]));
            int dy = (short) (qyaw[i] - qyaw[i - 1]);         // the short way round, -32768 .. 32767
            varint(out, zig(dy));
        }
        byte[] b = out.toByteArray();
        if (b.length > MAX_BYTES) throw new IllegalStateException("ghost too large: " + b.length);
        return b;
    }

    /** Reads an encoded ghost; IllegalArgumentException on anything but a ghost this class wrote. */
    public static Ghost decode(byte[] data) {
        if (data == null || data.length < 8 || data.length > MAX_BYTES) throw new IllegalArgumentException("size");
        Reader r = new Reader(data);
        if (r.u8() != MAGIC) throw new IllegalArgumentException("not a ghost");
        if (r.u8() != VERSION) throw new IllegalArgumentException("version");
        int step = r.u8();
        if (step < 1 || step > 20) throw new IllegalArgumentException("step");
        int n = r.varint();
        if (n < 2 || n > MAX_SAMPLES) throw new IllegalArgumentException("samples");
        int t0 = unzig(r.varint());
        if (t0 > 60_000 || t0 < -60_000) throw new IllegalArgumentException("start");
        int lap = r.varint();
        if (lap <= 0 || lap > 3_600_000) throw new IllegalArgumentException("lap");
        int gates = r.varint();
        if (gates > MAX_GATES) throw new IllegalArgumentException("gates");
        int[] g = new int[gates];
        for (int k = 0; k < gates; k++) g[k] = r.varint() - 1;
        int[] qx = new int[n], qy = new int[n], qz = new int[n], qyaw = new int[n];
        boolean[] boost = new boolean[n];
        qx[0] = unzig(r.varint());
        qy[0] = unzig(r.varint());
        qz[0] = unzig(r.varint());
        int y0 = r.varint();
        qyaw[0] = (y0 >>> 1) & 0xffff;
        boost[0] = (y0 & 1) != 0;
        for (int i = 1; i < n; i++) {
            qx[i] = qx[i - 1] + unzig(r.varint());
            int h = r.varint();
            boost[i] = (h & 1) != 0;
            qy[i] = qy[i - 1] + unzig(h >>> 1);
            qz[i] = qz[i - 1] + unzig(r.varint());
            qyaw[i] = (qyaw[i - 1] + unzig(r.varint())) & 0xffff;
        }
        if (r.left() != 0) throw new IllegalArgumentException("trailing bytes");
        return new Ghost(step, t0, lap, g, qx, qy, qz, qyaw, boost);
    }

    private static int zig(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzig(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    private static void varint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static final class Reader {
        final byte[] b;
        int at;

        Reader(byte[] b) {
            this.b = b;
        }

        int u8() {
            if (at >= b.length) throw new IllegalArgumentException("truncated");
            return b[at++] & 0xff;
        }

        int varint() {
            int v = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int x = u8();
                v |= (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("varint");
        }

        int left() {
            return b.length - at;
        }
    }
}
