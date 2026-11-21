package dev.stsv.economy.bus;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 마을버스 이벤트 (README "마을버스" 이벤트, bus.yml {@code events}): crowds of passengers in teams of four (출근 대란, 야구 끝,
 * 소풍날), 특급 손님, 외딴 정류장 구조, 안전 운행 연속, 노선 완주, 분실물 and the weekly 버스 대축제. Every reward is per team, per
 * run, per stop or per skill: never per head, so a 16-seat bus earns no more of it than a 4-seat one. Pure (unit-tested).
 */
public record BusEvents(Team team, Crowd rush, Crowd ballgame, Crowd picnic, Special special, Rescue rescue, Streak streak,
                        Course course, Lost lost, Festival festival) {

    /** A team: at most `size` passengers going to one stop; delivered whole it pays `bonus`, `onTime` more when all came in time. */
    public record Team(int size, long bonus, long onTime, List<String> colors) {
        /** A team's name colour (teams numbered from 1). */
        public String color(int number) {
            return colors.isEmpty() ? "#ffd166" : colors.get(Math.floorMod(number - 1, colors.size()));
        }
    }

    /**
     * A crowd at one stop, split into teams: `times` a weekday (`weekendTimes` a weekend) at random minutes between `from`
     * and `to` o'clock (Korean time); of passenger kind `type` (or `alt` with `altChance`, a whole crowd); `min`..`max` of
     * them; at a stop of these traits when there is one; a `deadline` hour (-1: none) after which a team pays nothing and
     * its riders' mood drops by `lateMood`; a team pays `bonus` / `onTime` (-1: the team's); a ball game is won with
     * `winChance` (team bonus × `winBonus`, mood + `winMood`), else lost (mood losses × `loseMood`).
     */
    public record Crowd(boolean enabled, String type, String alt, double altChance, int from, int to, int deadline, int min, int max,
                        List<String> tags, int times, int weekendTimes, long bonus, long onTime, double lateMood, double winChance,
                        double winBonus, double winMood, double loseMood, Map<String, List<String>> talk) {

        /** How many crowds come on this day of the week. */
        public int timesOn(DayOfWeek d) {
            return d.getValue() >= 6 ? weekendTimes : times;
        }

        /** The team bonus: none when it came after the deadline; the on-time part when every member was on time; a won game ×. */
        public long teamBonus(Team t, boolean late, boolean allOnTime, boolean won) {
            if (late) return 0;
            long b = bonus >= 0 ? bonus : t.bonus(), o = onTime >= 0 ? onTime : t.onTime();
            double v = b + (allOnTime ? o : 0);
            return Math.round(v * (won ? winBonus : 1));
        }
    }

    /** A 특급 손님 kind (see {@link Special}). */
    public record Kind(String id, String name, String type, double weight, long bonus, double time, double minMood, long hit,
                       double failFare, double minRoad, List<String> talk) {
        /**
         * What a delivery pays: nothing past the time limit (onTime × time; 0 = no limit) or below the mood it wants, else the
         * bonus less `hit` for every jolt or bump on the way (not below 0).
         */
        public long pay(double secs, double onTimeSecs, double mood, int hits) {
            if (time > 0 && secs > onTimeSecs * time) return 0;
            if (mood < minMood) return 0;
            return Math.max(0, bonus - hit * (long) hits);
        }

        /** The time limit in seconds for a ride whose on-time is this (0: none). */
        public double limit(double onTimeSecs) {
            return time > 0 ? onTimeSecs * time : 0;
        }
    }

    /** 특급 손님: one in `chance` of the passengers who come to a stop, at most `max` at once on the server, one a bus at a time. */
    public record Special(boolean enabled, double chance, int max, Map<String, Kind> kinds) {
        /** A kind by a roll in 0..1 over their weights, or null. */
        public Kind pick(double roll) {
            double total = 0;
            for (Kind k : kinds.values()) total += Math.max(0, k.weight());
            if (total <= 0) return null;
            double r = roll * total;
            for (Kind k : kinds.values()) {
                r -= Math.max(0, k.weight());
                if (r <= 0) return k;
            }
            return kinds.values().stream().reduce((a, b) -> b).orElse(null);
        }
    }

    /** 외딴 정류장 구조: the stop unserved longest, past `after` minutes, pays `base` + `perMinute` a minute (at most `cap`). */
    public record Rescue(boolean enabled, double after, long base, long perMinute, long cap) {
        public long bonus(double minutes) {
            if (minutes < after) return 0;
            return Math.min(cap, base + perMinute * (long) Math.floor(minutes));
        }
    }

    /** 안전 운행 연속: every `every` stops served in a row with no jolt or bump: `base`, `step` more each time, at most `cap`. */
    public record Streak(boolean enabled, int every, long base, long step, long cap) {
        /** The bonus when the streak reaches this many stops (0 when it is not a multiple of every). */
        public long bonus(int streak) {
            if (streak <= 0 || every <= 0 || streak % every != 0) return 0;
            return Math.min(cap, base + step * (streak / every - 1));
        }
    }

    /** 노선 완주: every stop of a line in order one way in one run (at least `minStops`): `perStop` × stops, at most `cap`. */
    public record Course(boolean enabled, int minStops, long perStop, long cap) {
        public long bonus(int stops) {
            if (stops < minStops) return 0;
            return Math.min(cap, perStop * stops);
        }
    }

    /**
     * One kind of thing left behind (events.lost.kinds; its item is economy:lost_&lt;id&gt;): its name; how likely, `weight` for
     * any passenger plus `types` more for those kinds (a kind with weight 0 is left only by them: 틀니 by a grandma); what
     * its owner pays (`reward`, × `rain` when it rains: an umbrella); a phone that `ring`s now and then; food that loses
     * value till it `spoil`s after that many minutes (`decay` of the reward left just before, then `spoiledReward`); a
     * `keepsake` that the finder keeps when its owner never comes; the owner's own `thanks`.
     */
    public record LostKind(String id, String name, double weight, Map<String, Double> types, long reward, double rain, boolean ring,
                           double spoil, double decay, long spoiledReward, boolean keepsake, List<String> thanks) {
        /** How likely this passenger kind leaves this (0: never). */
        public double weightFor(String type) {
            return Math.max(0, weight) + Math.max(0, types.getOrDefault(type, 0.0));
        }

        public boolean spoiled(double minutes) {
            return spoil > 0 && minutes >= spoil;
        }

        /** What it is worth to its owner `minutes` after it was left (food loses value till it spoils, then the spoiled reward). */
        public long value(double minutes) {
            if (spoil <= 0) return reward;
            if (minutes >= spoil) return spoiledReward;
            return Math.round(reward * (1 - (1 - decay) * Math.max(0, minutes) / spoil));
        }

        /** Its custom item (spoiled food has its own look). */
        public String item(boolean spoiled) {
            return "economy:lost_" + id + (spoiled && spoil > 0 ? "_spoiled" : "");
        }

        public String keepsakeItem() {
            return "economy:lost_" + id + "_keepsake";
        }
    }

    /** A lost item's state at a time: still its owner's (gone off for food), nobody's any more (to the lost and found), a keepsake. */
    public enum LostState { FRESH, SPOILED, ORPHAN, KEEPSAKE }

    /** What a returned item pays: the amount, and why it is more or less (fast, a rainy umbrella, gone off). */
    public record LostPay(long amount, boolean fast, boolean rain, boolean spoiled) {}

    /**
     * 분실물: at a stop where passengers got off, with `chance` one of them leaves something (one a stop at most, at most
     * `maxHeld` a driver at once), its kind by who they are. Brought back to the stop they got on at within `expire`
     * minutes it pays its kind's reward (× `fastBonus` within `fastMinutes`); past that it is nobody's: handed in at the end
     * of a line (분실물 센터) it pays `orphan` of its reward, a keepsake stays the finder's. Everyone waiting where it went
     * back boards `mood` happier. A phone rings every `ringFrom`..`ringTo` seconds. `titles`: title -> things returned.
     */
    public record Lost(boolean enabled, double chance, int expire, int maxHeld, double fastMinutes, double fastBonus, double orphan, double mood,
                       int ringFrom, int ringTo, Map<String, LostKind> kinds, List<String> thanks, Map<String, Integer> titles) {

        /** The kind this passenger kind leaves, by a roll in 0..1 over the weights for them, or null (nothing for them). */
        public LostKind pick(String type, double roll) {
            double total = 0;
            for (LostKind k : kinds.values()) total += k.weightFor(type);
            if (total <= 0) return null;
            double r = roll * total;
            LostKind last = null;
            for (LostKind k : kinds.values()) {
                double w = k.weightFor(type);
                if (w <= 0) continue;
                last = k;
                r -= w;
                if (r < 0) return k;
            }
            return last;
        }

        /** A kind by its id, or by its name (tags written before kinds had ids), or null. */
        public LostKind kind(String what) {
            if (what == null) return null;
            LostKind k = kinds.get(what);
            if (k != null) return k;
            for (LostKind x : kinds.values()) if (x.name().equals(what)) return x;
            return null;
        }

        public LostState state(LostTag t, long now) {
            LostKind k = kind(t.what());
            if (t.expired(now)) return k != null && k.keepsake() ? LostState.KEEPSAKE : LostState.ORPHAN;
            return k != null && k.spoiled(t.minutes(now)) ? LostState.SPOILED : LostState.FRESH;
        }

        /** What bringing it back now pays: its value by now, × its rain factor when it rains, × the fast bonus when it came back soon. */
        public LostPay pay(LostKind k, LostTag t, long now, boolean raining) {
            double minutes = t.minutes(now);
            double v = k.value(minutes);
            boolean rain = raining && k.rain() != 1;
            if (rain) v *= k.rain();
            boolean fast = t.found() > 0 && minutes <= fastMinutes && fastBonus > 1;
            if (fast) v *= fastBonus;
            return new LostPay(Math.round(v), fast, rain, k.spoiled(minutes));
        }

        /** What a thing nobody came for pays at the lost and found (its reward as it ended up, × orphan). */
        public long orphanPay(LostKind k) {
            return k == null ? 0 : Math.round(k.value(Double.MAX_VALUE) * orphan);
        }

        /** The titles reached going from `before` to `after` things returned. */
        public List<String> titlesReached(long before, long after) {
            List<String> out = new ArrayList<>();
            titles.forEach((t, n) -> {
                if (before < n && after >= n) out.add(t);
            });
            return out;
        }
    }

    /** A word with 이 or 가 after it by its last sound (non-Korean: "이(가)"): "할머니가", "커플이". Also 을/를, 이요/요 … */
    public static String josa(String word, String withFinal, String without) {
        if (word == null || word.isEmpty()) return "";
        char c = word.charAt(word.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) return word + withFinal + "(" + without + ")";
        return word + ((c - 0xAC00) % 28 != 0 ? withFinal : without);
    }

    /**
     * 버스 대축제: on `day` (1 = Monday … 7 = Sunday) from `from` to `to` o'clock (Korean time): passengers come `refill` times
     * as fast, 특급 손님 `chance` times as often; drivers score `points` (stop, team, special, rescue, course), the top ones get
     * `prizes` (G) and `titles`. Warned `warn` minutes before it ends.
     */
    public record Festival(boolean enabled, int day, int from, int to, int warn, double refill, double chance, Map<String, Integer> points,
                           List<Long> prizes, List<String> titles) {
        /** Whether it is on at this (Korean) time. */
        public boolean on(ZonedDateTime t) {
            if (!enabled || t.getDayOfWeek().getValue() != day) return false;
            int m = t.getHour() * 60 + t.getMinute();
            return m >= from * 60 && m < to * 60;
        }

        /** Minutes till it ends at this (Korean) time (only meaningful while on). */
        public int minutesLeft(ZonedDateTime t) {
            return to * 60 - (t.getHour() * 60 + t.getMinute());
        }

        public int point(String what) {
            return points.getOrDefault(what, 0);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------------------------------

    /** A crowd of n split into teams of at most `size`, as even as can be, the bigger first (10 by 4: 4, 3, 3). */
    public static int[] split(int n, int size) {
        if (n <= 0) return new int[0];
        size = Math.max(1, size);
        int teams = (n + size - 1) / size;
        int[] out = new int[teams];
        for (int i = 0; i < teams; i++) out[i] = n / teams + (i < n % teams ? 1 : 0);
        return out;
    }

    /**
     * The minutes of the day (Korean time) a crowd comes on a day: `count` distinct random minutes from `from` to `to`
     * o'clock, sorted, the same for the same day and salt (a restart does not move them).
     */
    public static int[] minutes(long epochDay, long salt, int from, int to, int count) {
        int lo = from * 60, span = Math.max(1, (to - from) * 60);
        if (count <= 0 || to <= from) return new int[0];
        Random r = new Random(epochDay * 1_000_003L + salt);
        List<Integer> picked = new ArrayList<>();
        for (int guard = 0; picked.size() < Math.min(count, span) && guard < 1000; guard++) {
            int m = lo + r.nextInt(span);
            // a second crowd at least an hour away from the first when the window allows
            boolean near = false;
            for (int p : picked) if (Math.abs(p - m) < Math.min(60, span / Math.max(1, count))) near = true;
            if (!near) picked.add(m);
        }
        Collections.sort(picked);
        return picked.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 노선 완주 for one bus: the stops it stopped at, in order, on one line one way. {@link #arrive} is told every stop
     * the bus stops at (with the line it runs, which way, and that line's stops in the order that way); it returns the
     * number of stops when the bus just stopped at the last of a line it ran whole from the first, else 0.
     */
    public static final class CourseRun {
        int line, dir, next = -1, last = -1, lastLine, lastDir;

        public int arrive(int line, int dir, List<Integer> ordered, int stop) {
            // the same stop again the same way (pulled out and in): nothing changes; turned round there: a new way
            if (stop == last && line == lastLine && dir == lastDir) return 0;
            last = stop;
            lastLine = line;
            lastDir = dir;
            if (ordered.isEmpty()) {
                next = -1;
                return 0;
            }
            if (next >= 0 && line == this.line && dir == this.dir && next < ordered.size() && ordered.get(next) == stop) {
                next++;
                if (next == ordered.size()) {
                    next = -1;
                    return ordered.size();
                }
                return 0;
            }
            if (ordered.getFirst() == stop) {   // at the first stop of the way: a new run starts
                this.line = line;
                this.dir = dir;
                next = 1;
                return 0;
            }
            next = -1;   // a stop skipped, or another line: the run is broken
            return 0;
        }

        /** How many stops of the current run so far (0: none going). */
        public int progress() {
            return Math.max(0, next);
        }
    }

    /** A driver's festival score: points, and when they last scored (an earlier one wins a tie). */
    public record Score(UUID player, int points, long at) {}

    /** The top `n` of the festival, the most points first, the earlier to reach them first on a tie; nobody with 0 points. */
    public static List<Score> ranking(Map<UUID, int[]> points, Map<UUID, Long> at, int n) {
        List<Score> out = new ArrayList<>();
        for (var e : points.entrySet()) {
            if (e.getValue()[0] <= 0) continue;
            out.add(new Score(e.getKey(), e.getValue()[0], at.getOrDefault(e.getKey(), 0L)));
        }
        out.sort((a, b) -> a.points() != b.points() ? Integer.compare(b.points(), a.points()) : Long.compare(a.at(), b.at()));
        return out.size() > n ? List.copyOf(out.subList(0, n)) : out;
    }

    /**
     * A lost item's tag (kept on the item): the stop it goes back to, when it stops being its owner's, who found it, what
     * (its kind's id), its own id, when it was left (0: not known, an older tag) and the passenger kind who left it.
     */
    public record LostTag(int stop, long expires, UUID finder, String what, UUID id, long found, String owner) {
        public LostTag(int stop, long expires, UUID finder, String what, UUID id) {
            this(stop, expires, finder, what, id, 0, "");
        }

        public String encode() {
            return "2;" + stop + ";" + expires + ";" + finder + ";" + id + ";" + found + ";" + owner + ";" + what;
        }

        public boolean expired(long now) {
            return now >= expires;
        }

        /** Minutes left (0 when past). */
        public long minutesLeft(long now) {
            return Math.max(0, (expires - now + 59_999) / 60_000);
        }

        /** Minutes since it was left (0 when not known). */
        public double minutes(long now) {
            return found > 0 ? Math.max(0, (now - found) / 60_000.0) : 0;
        }

        /** A tag read back, or null (not one, or damaged). Reads the older tags too (stop;expires;finder;id;name). */
        public static LostTag decode(String s) {
            if (s == null) return null;
            try {
                if (s.startsWith("2;")) {
                    String[] p = s.split(";", 8);
                    if (p.length < 8) return null;
                    return new LostTag(Integer.parseInt(p[1]), Long.parseLong(p[2]), UUID.fromString(p[3]), p[7], UUID.fromString(p[4]),
                            Long.parseLong(p[5]), p[6]);
                }
                String[] p = s.split(";", 5);
                if (p.length < 5) return null;
                return new LostTag(Integer.parseInt(p[0]), Long.parseLong(p[1]), UUID.fromString(p[2]), p[4], UUID.fromString(p[3]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // bus.yml
    // ------------------------------------------------------------------------------------------

    public static BusEvents parse(ConfigurationSection root, List<String> problems) {
        ConfigurationSection e = sec(root, "events");
        ConfigurationSection t = sec(e, "team");
        Team team = new Team(Math.max(1, t.getInt("size", 4)), t.getLong("bonus", 800), t.getLong("on-time", 400),
                List.copyOf(t.getStringList("colors")));
        Crowd rush = crowd(sec(e, "rush"), "office", 7, 8, 9, 12, 12, 1, 0, problems, "rush");
        Crowd ball = crowd(sec(e, "ballgame"), "fan", 12, 23, -1, 8, 12, 1, 2, problems, "ballgame");
        // 소풍날: the old top-level picnic (from, to, students) still counts; events.picnic adds to it
        ConfigurationSection old = sec(root, "picnic"), pc = sec(e, "picnic");
        int students = pc.getInt("count", old.getInt("students", 12));
        Crowd picnic = crowd(pc, "student", old.getInt("from", 9), old.getInt("to", 12), -1, students, students, 1, 1, problems, "picnic");
        ConfigurationSection sp = sec(e, "special");
        Map<String, Kind> kinds = new LinkedHashMap<>();
        ConfigurationSection ks = sp.getConfigurationSection("kinds");
        if (ks != null) for (String id : ks.getKeys(false)) {
            ConfigurationSection k = ks.getConfigurationSection(id);
            if (k == null) continue;
            kinds.put(id, new Kind(id, k.getString("name", id), k.getString("type", "resident"), k.getDouble("weight", 1), k.getLong("bonus", 2000),
                    k.getDouble("time", 0), k.getDouble("min-mood", 0), k.getLong("hit", 0), k.getDouble("fail-fare", 1), k.getDouble("min-road", 0),
                    List.copyOf(k.getStringList("talk"))));
        }
        Special special = new Special(sp.getBoolean("enabled", true), Math.clamp(sp.getDouble("chance", 0.02), 0, 1), Math.max(0, sp.getInt("max", 3)), kinds);
        ConfigurationSection rc = sec(e, "rescue");
        Rescue rescue = new Rescue(rc.getBoolean("enabled", true), Math.max(1, rc.getDouble("after", 15)), rc.getLong("base", 500),
                rc.getLong("per-minute", 50), rc.getLong("cap", 2500));
        ConfigurationSection st = sec(e, "streak");
        Streak streak = new Streak(st.getBoolean("enabled", true), Math.max(1, st.getInt("every", 5)), st.getLong("base", 300), st.getLong("step", 100),
                st.getLong("cap", 1000));
        ConfigurationSection co = sec(e, "course");
        Course course = new Course(co.getBoolean("enabled", true), Math.max(2, co.getInt("min-stops", 4)), co.getLong("per-stop", 150), co.getLong("cap", 3000));
        ConfigurationSection lo = sec(e, "lost");
        Lost lost = lost(lo);
        ConfigurationSection fs = sec(e, "festival");
        Map<String, Integer> points = new LinkedHashMap<>();
        ConfigurationSection ps = fs.getConfigurationSection("points");
        if (ps != null) for (String k : ps.getKeys(false)) points.put(k, ps.getInt(k));
        if (points.isEmpty()) points.putAll(Map.of("stop", 1, "team", 5, "special", 5));
        List<Long> prizes = new ArrayList<>();
        for (Object o : fs.getList("prizes", List.of(30000, 20000, 10000))) if (o instanceof Number n) prizes.add(n.longValue());
        Festival festival = new Festival(fs.getBoolean("enabled", true), Math.clamp(fs.getInt("day", 6), 1, 7), fs.getInt("from", 20), fs.getInt("to", 21),
                Math.max(1, fs.getInt("warn", 10)), Math.max(1, fs.getDouble("refill", 2)), Math.max(1, fs.getDouble("chance", 2)), points,
                List.copyOf(prizes), List.copyOf(fs.getStringList("titles")));
        if (festival.to() <= festival.from()) problems.add("events.festival: to 가 from 보다 커야 함");
        return new BusEvents(team, rush, ball, picnic, special, rescue, streak, course, lost, festival);
    }

    /** events.lost: its kinds (or, from an older bus.yml, its `things` names with one `reward`). */
    static Lost lost(ConfigurationSection lo) {
        Map<String, LostKind> kinds = new LinkedHashMap<>();
        ConfigurationSection ks = lo.getConfigurationSection("kinds");
        long reward = lo.getLong("reward", 600);
        if (ks != null) for (String id : ks.getKeys(false)) {
            ConfigurationSection k = ks.getConfigurationSection(id);
            if (k == null) continue;
            Map<String, Double> types = new LinkedHashMap<>();
            ConfigurationSection ts = k.getConfigurationSection("types");
            if (ts != null) for (String t : ts.getKeys(false)) types.put(t, ts.getDouble(t));
            kinds.put(id, new LostKind(id, k.getString("name", id), k.getDouble("weight", 1), Map.copyOf(types), k.getLong("reward", reward),
                    Math.max(0, k.getDouble("rain", 1)), k.getBoolean("ring", false), Math.max(0, k.getDouble("spoil", 0)),
                    Math.clamp(k.getDouble("decay", 1), 0, 1), k.getLong("spoiled-reward", 0), k.getBoolean("keepsake", false),
                    List.copyOf(k.getStringList("thanks"))));
        }
        if (kinds.isEmpty()) {
            Map<String, String> ids = Map.of("지갑", "wallet", "우산", "umbrella", "휴대폰", "phone", "도시락", "lunchbox", "모자", "hat", "열쇠", "key");
            for (Map<?, ?> m : lo.getMapList("things")) {
                if (!(m.get("name") instanceof String n) || !ids.containsKey(n)) continue;
                kinds.put(ids.get(n), new LostKind(ids.get(n), n, 1, Map.of(), reward, 1, false, 0, 1, 0, false, List.of()));
            }
            if (kinds.isEmpty()) kinds.put("wallet", new LostKind("wallet", "지갑", 1, Map.of(), reward, 1, false, 0, 1, 0, false, List.of()));
        }
        Map<String, Integer> titles = new LinkedHashMap<>();
        ConfigurationSection tt = lo.getConfigurationSection("titles");
        if (tt != null) for (String t : tt.getKeys(false)) titles.put(t.contains(":") ? t : "economy:" + t, Math.max(1, tt.getInt(t)));
        List<Integer> ring = lo.getIntegerList("ring");
        int ringFrom = ring.isEmpty() ? 120 : Math.max(10, ring.getFirst()), ringTo = ring.isEmpty() ? 180 : Math.max(ringFrom, ring.getLast());
        return new Lost(lo.getBoolean("enabled", true), Math.clamp(lo.getDouble("chance", 0.06), 0, 1), Math.max(1, lo.getInt("expire", 30)),
                Math.max(1, lo.getInt("max-held", 2)), Math.max(0, lo.getDouble("fast-minutes", 10)), Math.max(1, lo.getDouble("fast-bonus", 1.5)),
                Math.clamp(lo.getDouble("orphan", 1 / 3.0), 0, 1), lo.getDouble("mood", 5), ringFrom, ringTo, kinds,
                List.copyOf(lo.getStringList("thanks")), titles);
    }

    private static Crowd crowd(ConfigurationSection c, String type, int from, int to, int deadline, int min, int max, int times, int weekendTimes,
                               List<String> problems, String name) {
        // count: a number, or [least, most]
        int lo = min, hi = max;
        if (c.isList("count")) {
            List<Integer> n = c.getIntegerList("count");
            if (!n.isEmpty()) {
                lo = n.getFirst();
                hi = n.getLast();
            }
        } else if (c.contains("count")) {
            lo = hi = c.getInt("count");
        }
        Map<String, List<String>> talk = new LinkedHashMap<>();
        ConfigurationSection tk = c.getConfigurationSection("talk");
        if (tk != null) for (String k : tk.getKeys(false)) talk.put(k, List.copyOf(tk.getStringList(k)));
        Crowd out = new Crowd(c.getBoolean("enabled", true), c.getString("type", type), c.getString("alt", ""), Math.clamp(c.getDouble("alt-chance", 0), 0, 1),
                c.getInt("from", from), c.getInt("to", to), c.getInt("deadline", deadline), Math.max(1, lo), Math.max(Math.max(1, lo), hi),
                List.copyOf(c.getStringList("tags")), Math.max(0, c.getInt("times", times)), Math.max(0, c.getInt("weekend-times", weekendTimes)),
                c.getLong("bonus", -1), c.getLong("on-time", -1), c.getDouble("late-mood", 0), Math.clamp(c.getDouble("win-chance", 0), 0, 1),
                c.getDouble("win-bonus", 1), c.getDouble("win-mood", 0), c.getDouble("lose-mood", 1), talk);
        if (out.to() <= out.from()) problems.add("events." + name + ": to 가 from 보다 커야 함");
        if (out.deadline() >= 0 && out.deadline() < out.to()) problems.add("events." + name + ": deadline 이 to 보다 이르면 안 됨");
        return out;
    }

    private static ConfigurationSection sec(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }
}
