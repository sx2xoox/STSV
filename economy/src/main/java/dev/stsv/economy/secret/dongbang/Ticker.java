package dev.stsv.economy.secret.dongbang;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 동방해운's one share as one holder sees it: the price, its candles (10-minute, 2-hour, day) and the closes at each
 * midnight. Pure (unit-tested): time comes in as ms, nothing here touches the server.
 * <p>
 * The price only follows the holder: online it climbs, away it sinks, by the water it swings. It is atmosphere; the
 * 본사 address comes from the radio log ({@link Radio}), not from the price.
 */
final class Ticker {

    /** What the holder is doing now. */
    enum Mood { ONLINE, OFFLINE, WATER }

    record Candle(long t, double open, double high, double low, double close) {
        boolean up() {
            return close >= open;
        }

        Candle with(double price) {
            return new Candle(t, open, Math.max(high, price), Math.min(low, price), price);
        }
    }

    /** A midnight: when (ms), the price, and whether the last candle before it was red. */
    record Close(long at, double price, boolean up) {}

    /** Day lines: Korean days, or test "days" of {@code day} ms counted from the epoch (as SecretModule.today does). */
    record Clock(boolean test, long day) {
        static final ZoneId KST = ZoneId.of("Asia/Seoul");

        long dayStart(long t) {
            if (test) return Math.floorDiv(t, day) * day;
            LocalDate d = Instant.ofEpochMilli(t).atZone(KST).toLocalDate();
            return d.atStartOfDay(KST).toInstant().toEpochMilli();
        }

        /** The length of a day here (a Korean day is always 24 hours: no summer time). */
        long length() {
            return test ? day : 86_400_000L;
        }

        long nextMidnight(long t) {
            return dayStart(t) + length();
        }

        /** 10 minutes, or a 24th of a test day (so a test day still draws a chart). */
        long candle() {
            return test ? Math.max(1000, day / 24) : 600_000L;
        }

        int candlesPerDay() {
            return (int) (length() / candle());
        }

        /** The start of the bucket of `len` ms holding t, counted from that day's start. */
        long bucket(long t, long len) {
            long s = dayStart(t);
            return s + Math.floorDiv(t - s, len) * len;
        }
    }

    /** up/down: log change per candle online/away; noise: per candle; water: per candle swing; min/max: the range. */
    record Rules(double up, double down, double noise, double water, double min, double max) {}

    static final int M10 = 16, H2 = 12, D1 = 7, CLOSES = 7;

    final Clock clock;
    final Rules rules;
    double price;
    /** Last update (ms). */
    long t;
    final List<Candle> m10 = new ArrayList<>();
    final List<Candle> h2 = new ArrayList<>();
    final List<Candle> d1 = new ArrayList<>();
    final List<Close> closes = new ArrayList<>();

    Ticker(Clock clock, Rules rules, double price, long t) {
        this.clock = clock;
        this.rules = rules;
        this.price = price;
        this.t = t;
    }

    /** The day's reference: the last midnight's close, or the first price. */
    double prevClose(double fallback) {
        return closes.isEmpty() ? fallback : closes.getLast().price();
    }

    /** The next free price (`dtCandles` candles of it under `mood`). */
    private double priceAt(double dtCandles, Mood mood, Random rnd) {
        double g = rnd.nextGaussian(), sq = Math.sqrt(Math.max(0, dtCandles));
        double d = switch (mood) {
            case ONLINE -> rules.up() * dtCandles + rules.noise() * sq * g;
            case OFFLINE -> -rules.down() * dtCandles + rules.noise() * 0.5 * sq * g;
            case WATER -> rules.water() * sq * g;
        };
        return Math.clamp(price * Math.exp(d), rules.min(), rules.max());
    }

    /**
     * Moves the price on to `to`, in steps that stop at every 10-minute line (so an away stretch still leaves one candle
     * per 10 minutes). Returns the midnights crossed.
     */
    List<Close> advance(long to, Mood mood, Random rnd) {
        List<Close> crossed = new ArrayList<>();
        long c = clock.candle();
        // a server off for weeks: do not walk every candle of it (only the last 7 days can show)
        long floor = to - 8 * clock.length();
        if (t < floor) t = clock.bucket(floor, c);
        while (t < to) {
            long next = Math.min(to, clock.bucket(t, c) + c);
            double p = priceAt((next - t) / (double) c, mood, rnd);
            price = p;
            t = next;
            if (next == clock.dayStart(next)) {
                // a midnight: this price closes the day that ended
                record(next - 1, p);
                Candle last = m10.isEmpty() ? null : m10.getLast();
                Close cl = new Close(next, p, last == null || last.up());
                closes.add(cl);
                while (closes.size() > CLOSES) closes.removeFirst();
                crossed.add(cl);
            } else {
                record(next, p);
            }
        }
        return crossed;
    }

    /** Puts a price into the candles holding `at` (a new candle opens at the last close, as a real chart does). */
    void record(long at, double p) {
        long c = clock.candle();
        put(m10, clock.bucket(at, c), p, M10);
        put(h2, clock.bucket(at, 12 * c), p, H2);
        put(d1, clock.dayStart(at), p, D1);
    }

    private static void put(List<Candle> list, long bucket, double p, int cap) {
        if (!list.isEmpty() && list.getLast().t() == bucket) {
            list.set(list.size() - 1, list.getLast().with(p));
            return;
        }
        double open = list.isEmpty() ? p : list.getLast().close();
        list.add(new Candle(bucket, open, Math.max(open, p), Math.min(open, p), p));
        while (list.size() > cap) list.removeFirst();
    }

    // ------------------------------------------------------------------------------------------
    // saving (eco.kv strings)
    // ------------------------------------------------------------------------------------------

    static String encode(List<Candle> l) {
        StringBuilder b = new StringBuilder();
        for (Candle k : l) {
            if (!b.isEmpty()) b.append(';');
            b.append(k.t()).append(',').append(num(k.open())).append(',').append(num(k.high())).append(',')
                    .append(num(k.low())).append(',').append(num(k.close()));
        }
        return b.toString();
    }

    static void decode(String s, List<Candle> into) {
        into.clear();
        if (s == null || s.isBlank()) return;
        for (String part : s.split(";")) {
            String[] w = part.split(",");
            try {
                into.add(new Candle(Long.parseLong(w[0]), Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]),
                        Double.parseDouble(w[4])));
            } catch (RuntimeException ignored) {
                // a hand-edited value: that candle is dropped
            }
        }
    }

    static String encodeCloses(List<Close> l) {
        StringBuilder b = new StringBuilder();
        for (Close k : l) {
            if (!b.isEmpty()) b.append(';');
            b.append(k.at()).append(',').append(num(k.price())).append(',').append(k.up() ? 1 : 0);
        }
        return b.toString();
    }

    static void decodeCloses(String s, List<Close> into) {
        into.clear();
        if (s == null || s.isBlank()) return;
        for (String part : s.split(";")) {
            String[] w = part.split(",");
            try {
                into.add(new Close(Long.parseLong(w[0]), Double.parseDouble(w[1]), w[2].equals("1")));
            } catch (RuntimeException ignored) {
                // dropped
            }
        }
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    // ------------------------------------------------------------------------------------------
    // where the 본사 is
    // ------------------------------------------------------------------------------------------

    /**
     * Candidate 본사 spots for a seed: block coordinates {x, z}, each a chunk's middle (x ≡ 8 mod 16, so the room fits in
     * one chunk) with min ≤ |x|, |z| ≤ max.
     */
    static List<int[]> candidates(long seed, int min, int max, int n) {
        Random r = new Random(seed);
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new int[]{axis(r, min, max), axis(r, min, max)});
        return out;
    }

    private static int axis(Random r, int min, int max) {
        int lo = Math.max(16, min), hi = Math.max(lo + 32, max);
        int mag = lo + r.nextInt(hi - lo + 1);
        int sign = r.nextBoolean() ? 1 : -1;
        int c = Math.floorDiv(sign * mag, 16);
        int v = c * 16 + 8;
        while (Math.abs(v) < lo) v += sign * 16;
        while (Math.abs(v) > hi) v -= sign * 16;
        return v;
    }
}
