package dev.stsv.economy.bus;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** bus.yml: fares, moods, stops and passenger types of the 마을버스 (README "마을버스"). Pure (unit-tested). */
public record BusSpec(Set<String> vehicles, Fare fare, Mood mood, Stops stops, Map<String, Type> types, Extra extra, Rental rental,
                      Queue queue, Patience patience, Board board, BusEvents events, ServiceGate.Rules fair, Shiny shiny) {

    /**
     * 별난 손님 (bus.yml shiny, README "마을버스" 별난 손님): a passenger who comes to a stop is, with `chance`, the 별난 one of
     * their kind: the same fare, mood and rewards, another look (`skins`: kind -> its skin, else its first skin's file with
     * _s1), a sparkle and a chime, a ✦ before the name, and their own lines (`own`: kind -> situation -> lines, else `talk`).
     * Never a kind of weight 0 (the secret ??? who comes by his own rules) or in `never`; never an event team, a 특급 손님 or
     * a transfer (they wait for something of their own).
     */
    public record Shiny(double chance, Map<String, List<String>> talk, Map<String, Map<String, List<String>>> own, Map<String, String> skins,
                        Set<String> never) {
        public static final String MARK = "✦";

        public boolean eligible(Type t) {
            return t != null && t.weight() > 0 && !never.contains(t.id());
        }

        /** The skin (with " slim" like the bus.yml skins) a shiny of this kind wears, or null when it has no skins at all. */
        public String skin(Type t) {
            String own = skins.get(t.id());
            if (own != null && !own.isBlank()) return own;
            return t.skins().isEmpty() ? null : shinySkin(t.skins().getFirst(), t.id());
        }

        /** economy:entity/bus/grandma_1 slim -> economy:entity/bus/grandma_s1 slim (the folder of the first skin, the kind's id). */
        static String shinySkin(String first, String id) {
            boolean slim = first.endsWith(" slim");
            String key = slim ? first.substring(0, first.length() - 5).trim() : first.trim();
            int cut = key.lastIndexOf('/');
            int colon = key.indexOf(':');
            String folder = cut >= 0 ? key.substring(0, cut + 1) : colon >= 0 ? key.substring(0, colon + 1) : "";
            return folder + id + "_s1" + (slim ? " slim" : "");
        }

        /** A shiny one's lines in this situation (board, thanks): their kind's own, else the general ones, else null. */
        public List<String> lines(Type t, String situation) {
            List<String> mine = t == null ? null : own.getOrDefault(t.id(), Map.of()).get(situation);
            if (mine != null && !mine.isEmpty()) return mine;
            List<String> all = talk.get(situation);
            return all == null || all.isEmpty() ? null : all;
        }

        /** How many of `newcomers` who just came are shiny: one roll each (`roll` gives 0..1). */
        public int arrivals(int newcomers, java.util.function.DoubleSupplier roll) {
            int n = 0;
            for (int i = 0; i < newcomers; i++) if (roll.getAsDouble() < chance) n++;
            return n;
        }
    }

    /**
     * How waiting passengers stand and pass the time: `gap` blocks apart in their line, they notice a bus within `notice`
     * blocks (and look at it), else every `idleEvery` ticks each does what their kind does (`idle`: type id -> sit, phone,
     * fidget, hop; others look about now and then).
     */
    public record Queue(double gap, int notice, int idleEvery, Map<String, String> idle) {
        public String idleOf(String type) {
            return idle.getOrDefault(type, "");
        }
    }

    /**
     * 기다림 짜증: a passenger who waited longer than `grace` minutes boards in a worse mood, down to `floor` at `full`
     * minutes; every `every` seconds, the one waiting longest past `giveUp` minutes walks off with `chance`.
     */
    public record Patience(double grace, double full, double floor, double giveUp, double chance, int every) {
        /** The mood a passenger boards in after waiting this many minutes (the start mood when they did not wait long). */
        public double mood(double start, double minutes) {
            if (minutes <= grace || floor >= start) return start;
            if (minutes >= full || full <= grace) return floor;
            return start + (floor - start) * (minutes - grace) / (full - grace);
        }

        /** Whether someone waiting this many minutes may give up. */
        public boolean fedUp(double minutes) {
            return giveUp > 0 && minutes >= giveUp;
        }
    }

    /** 버스 도착 안내판 and 노선도: refreshed every `refresh` seconds, at most `lines` lines on the sign, `around` stops each side. */
    public record Board(int refresh, int lines, int around) {}

    /**
     * 버스 렌트 (for beginners): the server's bus of this kind lent at a stop within `range` blocks; the driver keeps `share`
     * of every fare (no time limit); it goes back `returnAfter` seconds after its driver got out (or by /버스 반납).
     */
    public record Rental(String vehicle, double share, int returnAfter, int range) {
        /** What the driver keeps of a fare (paid in whole G). */
        public long keep(long fare) {
            return Math.round(fare * share);
        }
    }

    /** One kind of bus's rules: passengers only from this road distance on, only these kinds (empty: all), a fare factor. */
    public record Bus(double minRoad, Set<String> only, double fare) {
        public static final Bus ANY = new Bus(0, Set.of(), 1);

        public boolean takes(Type t) {
            return only.isEmpty() || only.contains(t.id());
        }
    }

    public record Gift(String item, int amount) {}

    /**
     * The 2차 details: stop traits (id -> word), market day (every n days, at stops of these traits), the picnic, transfers,
     * dozing, passing a stop, the weather, talk, gifts, and each bus's rules.
     */
    public record Extra(Map<String, String> tags, int marketEvery, Set<String> marketTags, int picnicFrom, int picnicTo, int picnicStudents,
                        double transfer, double transferBonus, int dozeAfter, double dozeMiss, int passed, double missedMood,
                        double rainTip, double snowJolt, int talkCooldown, double giftChance, double giftMood,
                        Map<String, List<String>> talk, Map<String, Bus> buses, Map<String, List<String>> names) {}

    public record Step(double at, double factor) {}

    public record Fare(double base, double perBlock, double onTimeSpeed, double onTimeGrace, double tip, List<Step> mood,
                       List<Step> daily, int repeatWindow, int repeatFree, double repeatFactor) {}

    public record Mood(double start, double jolt, double joltFrom, int joltWindow, double bump, double turn, double turnSpeed, double turnDegrees,
                       double smoothStop, int cooldown) {}

    public record Hours(int from, int to, double factor) {}

    public record Auto(int startSkip, int endMin, int endMax, int every, int apart, int junctionClear) {}

    public record Stops(int waitingMax, double refillMinutes, List<Hours> hours, int showRange, int hideAfter, int boardTicks, Auto auto,
                        int bayLength, int bayWidth, double stopReach) {}

    public record Type(String id, String name, String color, double weight, double fare, double mood, double slow, double jolt, double tip,
                       double far, double tipMood, double rush, double late, double scale, boolean announce, List<String> skins,
                       Map<String, Double> tags, int hourFrom, int hourTo, double weekend, double market, double luggage, boolean pair,
                       double wander, double turnFactor, boolean toEnd, double doze, List<Gift> gifts, Map<String, List<String>> talk,
                       Traits x) {

        /** This kind's weight at a stop of these traits, at this hour, on a weekend or not, on its market day or not (0: not now). */
        public double weightAt(Set<String> stopTags, int hour, boolean weekendDay, boolean marketDay, boolean rush) {
            return weightAt(stopTags, hour, weekendDay, marketDay, rush, 0, 0);
        }

        /** The same on this month (1-12) and day of the month (Korean time): a kind of some months only (산타) is 0 in others. */
        public double weightAt(Set<String> stopTags, int hour, boolean weekendDay, boolean marketDay, boolean rush, int month, int day) {
            if (!x.months().isEmpty() && !x.months().contains(month)) return 0;
            if (hourFrom >= 0) {
                boolean in = hourFrom <= hourTo ? hour >= hourFrom && hour < hourTo : hour >= hourFrom || hour < hourTo;
                if (!in) return 0;
            }
            double w = weight * (rush ? this.rush : 1) * (weekendDay ? weekend : 1) * (marketDay ? market : 1);
            double best = 1;
            boolean any = false;
            for (String t : stopTags) {
                Double f = tags.get(t);
                if (f == null) continue;
                best = any ? Math.max(best, f) : f;
                any = true;
            }
            if (x.tagsOnly() && !any) return 0;
            if (x.peakDays().contains(day)) w *= x.peak();
            return w * best;
        }

        /** This kind's mood never goes below its floor (수다쟁이 아줌마, 스님). */
        public double floor(double mood) {
            return Math.max(mood, x.moodFloor());
        }
    }

    /**
     * The 3차 kinds' ways (bus.yml types.*): `chatter` seconds between their chatter bubbles (0: none), each lowering the other
     * riders' mood by `chatterMood`; mood never below `moodFloor`; at `sickAt` mood or less they get off at the next stop
     * (멀미); a baby aboard: everyone `babyCry` at a jolt or bump, `babySmile` at a smooth stop; `seats` seats taken (the dog: a
     * `pet` entity in the second); only in `months`, `peak` × on `peakDays`; a `ghost` (see-through, glowing) leaving `token`
     * 유령 승차권 조각; their on-time window × `onTime`, `lateHit` mood once late; `bless` next passengers' tips × `blessTip`
     * after they get off (스님); a ★5 review when they get off at `review` mood or more (유튜버); their own gift chance and mood
     * (-1: bus.yml's); `tagsOnly`: only at stops of their traits.
     */
    public record Traits(double chatter, double chatterMood, double moodFloor, double sickAt, double babyCry, double babySmile, int seats,
                         String pet, Set<Integer> months, Set<Integer> peakDays, double peak, boolean ghost, int token, double onTime,
                         double lateHit, int bless, double blessTip, double review, double giftChance, double giftMood, boolean tagsOnly,
                         double dodge, double haggleAt, double haggle, double prank, double litter, double treat) {
        public static final Traits NONE = new Traits(0, 0, 0, 0, 0, 0, 1, "", Set.of(), Set.of(), 1, false, 0, 1, 0, 0, 1, 0, -1, -1, false,
                0, 0, 1, 0, 0, 0);

        static Traits parse(ConfigurationSection t) {
            return new Traits(t.getDouble("chatter", 0), t.getDouble("chatter-mood", 0), t.getDouble("mood-floor", 0), t.getDouble("sick-at", 0),
                    t.getDouble("baby-cry", 0), t.getDouble("baby-smile", 0), Math.max(1, t.getInt("seats", 1)), t.getString("pet", ""),
                    Set.copyOf(t.getIntegerList("months")), Set.copyOf(t.getIntegerList("peak-days")), t.getDouble("peak", 1),
                    t.getBoolean("ghost"), t.getInt("token", 0), t.getDouble("on-time", 1), t.getDouble("late-hit", 0), t.getInt("bless", 0),
                    t.getDouble("bless-tip", 1), t.getDouble("review", 0), t.getDouble("gift-chance", -1), t.getDouble("gift-mood", -1),
                    t.getBoolean("tags-only"), Math.clamp(t.getDouble("dodge", 0), 0, 1), t.getDouble("haggle-at", 0),
                    Math.clamp(t.getDouble("haggle", 0.5), 0, 1), Math.clamp(t.getDouble("prank", 0), 0, 1), t.getDouble("litter", 0),
                    Math.max(0, t.getDouble("treat", 0)));
        }
    }

    public static BusSpec parse(ConfigurationSection c, List<String> problems) {
        ConfigurationSection f = section(c, "fare"), m = section(c, "mood"), s = section(c, "stops");
        ConfigurationSection a = section(s, "auto"), b = section(s, "bay");
        Fare fare = new Fare(f.getDouble("base", 100), f.getDouble("per-block", 0.4), f.getDouble("on-time-speed", 6),
                f.getDouble("on-time-grace", 30), f.getDouble("tip", 0.15), steps(f.getMapList("mood"), "at", "factor"),
                steps(f.getMapList("daily"), "upto", "rate"), f.getInt("repeat-window", 30), f.getInt("repeat-free", 2),
                f.getDouble("repeat-factor", 0.5));
        if (fare.mood().isEmpty()) problems.add("fare.mood 가 비어 있음");
        if (fare.daily().isEmpty()) problems.add("fare.daily 가 비어 있음");
        Mood mood = new Mood(m.getDouble("start", 80), m.getDouble("jolt", -5), m.getDouble("jolt-from", 6), m.getInt("jolt-window", 30), m.getDouble("bump", -10),
                m.getDouble("turn", -3), m.getDouble("turn-speed", 6), m.getDouble("turn-degrees", 3), m.getDouble("smooth-stop", 2),
                m.getInt("cooldown", 20));
        List<Hours> hours = new ArrayList<>();
        for (Map<?, ?> h : s.getMapList("hours")) hours.add(new Hours(num(h, "from", 0).intValue(), num(h, "to", 0).intValue(), num(h, "factor", 1).doubleValue()));
        List<Integer> end = a.getIntegerList("end-before");
        Auto auto = new Auto(a.getInt("start-skip", 20), end.size() > 0 ? end.get(0) : 40, end.size() > 1 ? end.get(1) : 60, a.getInt("every", 2000),
                a.getInt("apart", 150), a.getInt("junction-clear", 150));
        Stops stops = new Stops(s.getInt("waiting-max", 4), s.getDouble("refill-minutes", 3), hours, s.getInt("show-range", 64),
                s.getInt("hide-after", 30), s.getInt("board-ticks", 10), auto, b.getInt("length", 24), b.getInt("width", 3), b.getDouble("stop-reach", 7));
        Map<String, Type> types = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection("types");
        if (ts != null) for (String id : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(id);
            if (t == null) continue;
            Map<String, Double> tg = new LinkedHashMap<>();
            ConfigurationSection tgs = t.getConfigurationSection("tags");
            if (tgs != null) for (String k : tgs.getKeys(false)) tg.put(k, tgs.getDouble(k, 1));
            List<Integer> when = t.getIntegerList("hours");
            List<Gift> gifts = new ArrayList<>();
            for (Map<?, ?> g : t.getMapList("gifts")) {
                if (g.get("item") instanceof String item) gifts.add(new Gift(item, num(g, "amount", 1).intValue()));
            }
            types.put(id, new Type(id, t.getString("name", id), t.getString("color", "white"), t.getDouble("weight", 1), t.getDouble("fare", 1),
                    t.getDouble("mood", 1), t.getDouble("slow", 1), t.getDouble("jolt", 1), t.getDouble("tip", 1), t.getDouble("far", 0),
                    t.getDouble("tip-mood", 0), t.getDouble("rush", 1), t.getDouble("late", 0), t.getDouble("scale", 1), t.getBoolean("announce"),
                    List.copyOf(t.getStringList("skins")), tg, when.size() == 2 ? when.get(0) : -1, when.size() == 2 ? when.get(1) : -1,
                    t.getDouble("weekend", 1), t.getDouble("market", 1), t.getDouble("luggage", 0), t.getBoolean("pair"), t.getDouble("wander", 0),
                    t.getDouble("turn", 1), t.getBoolean("to-end"), t.getDouble("doze", 0.15), List.copyOf(gifts), talk(t.getConfigurationSection("talk")),
                    Traits.parse(t)));
        }
        if (types.isEmpty()) problems.add("types 가 비어 있음");
        ConfigurationSection mk = section(c, "market"), pc = section(c, "picnic"), dz = section(c, "doze");
        Map<String, String> tagNames = new LinkedHashMap<>();
        ConfigurationSection tn = c.getConfigurationSection("tags");
        if (tn != null) for (String k : tn.getKeys(false)) tagNames.put(k, tn.getString(k, k));
        Map<String, Bus> buses = new LinkedHashMap<>();
        ConfigurationSection bs = c.getConfigurationSection("buses");
        if (bs != null) for (String k : bs.getKeys(false)) {
            ConfigurationSection bb = bs.getConfigurationSection(k);
            if (bb != null) buses.put(k, new Bus(bb.getDouble("min-road", 0), Set.copyOf(bb.getStringList("only")), bb.getDouble("fare", 1)));
        }
        Extra extra = new Extra(tagNames, Math.max(1, mk.getInt("every", 5)), Set.copyOf(mk.getStringList("tags")), pc.getInt("from", 9),
                pc.getInt("to", 12), pc.getInt("students", 10), c.getDouble("transfer", 0.25), c.getDouble("transfer-bonus", 0.2),
                dz.getInt("after", 90), dz.getDouble("miss", 0.4), c.getInt("passed", 15), c.getDouble("missed-mood", -10),
                c.getDouble("rain-tip", 0.2), c.getDouble("snow-jolt", 0.5), c.getInt("talk-cooldown", 60), c.getDouble("gift-chance", 0.35),
                c.getDouble("gift-mood", 90), talk(c.getConfigurationSection("talk")), buses, talk(c.getConfigurationSection("names")));
        for (Type t : types.values()) for (String k : t.tags().keySet()) {
            if (!tagNames.containsKey(k)) problems.add("types." + t.id() + ".tags: 없는 특성 " + k);
        }
        for (Bus bus : buses.values()) for (String k : bus.only()) if (!types.containsKey(k)) problems.add("buses: 없는 손님 종류 " + k);
        ConfigurationSection rn = section(c, "rental");
        Rental rental = new Rental(rn.getString("vehicle", ""), Math.clamp(rn.getDouble("share", 0.4), 0, 1),
                Math.max(5, rn.getInt("return-after", 60)), Math.max(4, rn.getInt("range", 20)));
        Set<String> vehicles = Set.copyOf(c.getStringList("vehicles"));
        if (!rental.vehicle().isEmpty() && !vehicles.contains(rental.vehicle())) problems.add("rental.vehicle 이 vehicles 에 없음: " + rental.vehicle());
        ConfigurationSection q = section(c, "queue"), pt = section(c, "patience"), bd = section(c, "board");
        Map<String, String> idle = new LinkedHashMap<>();
        ConfigurationSection id = q.getConfigurationSection("idle");
        if (id != null) for (String k : id.getKeys(false)) {
            idle.put(k, id.getString(k, ""));
            if (!types.containsKey(k)) problems.add("queue.idle: 없는 손님 종류 " + k);
        }
        Queue queue = new Queue(Math.max(0.5, q.getDouble("gap", 0.8)), q.getInt("notice", 24), Math.max(10, q.getInt("idle-every", 30)), idle);
        Patience patience = new Patience(pt.getDouble("grace", 3), pt.getDouble("full", 15), pt.getDouble("floor", 55),
                pt.getDouble("give-up", 20), Math.clamp(pt.getDouble("give-up-chance", 0.25), 0, 1), Math.max(5, pt.getInt("every", 20)));
        Board board = new Board(Math.max(1, bd.getInt("refresh", 3)), Math.max(1, bd.getInt("lines", 4)), Math.max(1, bd.getInt("around", 2)));
        BusEvents events = BusEvents.parse(c, problems);
        for (BusEvents.Crowd cr : List.of(events.rush(), events.ballgame(), events.picnic())) {
            if (!types.containsKey(cr.type())) problems.add("events: 없는 손님 종류 " + cr.type());
            if (!cr.alt().isEmpty() && !types.containsKey(cr.alt())) problems.add("events: 없는 손님 종류 " + cr.alt());
        }
        for (BusEvents.Kind k : events.special().kinds().values()) if (!types.containsKey(k.type())) problems.add("events.special: 없는 손님 종류 " + k.type());
        ConfigurationSection fr = section(c, "fair");
        ServiceGate.Rules d = ServiceGate.Rules.DEFAULT;
        ServiceGate.Rules fair = new ServiceGate.Rules(fr.getDouble("min-drive", d.minDrive()), fr.getDouble("road-share", d.roadShare()),
                fr.getDouble("max-speed", d.maxSpeed()), fr.getDouble("min-ride", d.minRide()));
        ConfigurationSection sh = section(c, "shiny");
        Map<String, Map<String, List<String>>> shinyOwn = new LinkedHashMap<>();
        Map<String, String> shinySkins = new LinkedHashMap<>();
        if (ts != null) for (String kind : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(kind);
            if (t == null) continue;
            Map<String, List<String>> own = talk(t.getConfigurationSection("shiny-talk"));
            if (!own.isEmpty()) shinyOwn.put(kind, own);
            String sk = t.getString("shiny-skin");
            if (sk != null) shinySkins.put(kind, sk);
        }
        Shiny shiny = new Shiny(Math.clamp(sh.getDouble("chance", 0.004), 0, 1), talk(sh.getConfigurationSection("talk")), shinyOwn, shinySkins,
                Set.copyOf(sh.getStringList("never")));
        for (String k : shiny.never()) if (!types.containsKey(k)) problems.add("shiny.never: 없는 손님 종류 " + k);
        return new BusSpec(vehicles, fare, mood, stops, types, extra, rental, queue, patience, board, events, fair, shiny);
    }

    private static Map<String, List<String>> talk(ConfigurationSection t) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (t != null) for (String k : t.getKeys(false)) out.put(k, List.copyOf(t.getStringList(k)));
        return out;
    }

    /** A line for this kind in this situation (its own, else the default ones), or null. */
    public List<String> lines(Type t, String situation) {
        List<String> own = t == null ? null : t.talk().get(situation);
        if (own != null && !own.isEmpty()) return own;
        List<String> d = extra.talk().get(situation);
        return d == null || d.isEmpty() ? null : d;
    }

    /** Every situation this kind has a line for: its own talk keys first, then the default ones (/관리자 버스 손님). */
    public List<String> situations(Type t) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (t != null) t.talk().forEach((k, v) -> {
            if (!v.isEmpty()) out.add(k);
        });
        extra.talk().forEach((k, v) -> {
            if (!v.isEmpty()) out.add(k);
        });
        return List.copyOf(out);
    }

    /** The situations a kind's own talk may show off in the preview, most telling first (수다, 느릿느릿 타기, 아기 울음 …). */
    static final List<String> SIGNATURE = List.of("dodge", "haggle", "prank", "chat", "slow", "cry", "smile", "sick", "bark", "late", "view", "waited");

    /**
     * The preview's three situations (/관리자 버스 손님 <종류>): getting on, then the kind's most telling own one (SIGNATURE),
     * else its own smooth-stop line, else a jolt; then the thanks getting off. Only situations with a line.
     */
    public List<String> previewSequence(Type t) {
        String middle = null;
        if (t != null) for (String k : SIGNATURE) {
            List<String> own = t.talk().get(k);
            if (own != null && !own.isEmpty()) {
                middle = k;
                break;
            }
        }
        if (middle == null) middle = t != null && t.talk().containsKey("smooth") ? "smooth" : "jolt";
        List<String> out = new ArrayList<>();
        for (String k : List.of("board", middle, "thanks")) if (lines(t, k) != null && !out.contains(k)) out.add(k);
        return out;
    }

    /**
     * A cute name for a stop of these traits not in `used`: from its traits' names, else the general ones; when every one
     * is taken, a general one with a number. `roll` picks among them (0..1).
     */
    public String stopName(java.util.Set<String> tags, java.util.Set<String> used, double roll) {
        List<String> pool = new ArrayList<>();
        for (String t : tags) for (String n : extra.names().getOrDefault(t, List.of())) if (!used.contains(n)) pool.add(n);
        if (pool.isEmpty()) for (String n : extra.names().getOrDefault("any", List.of())) if (!used.contains(n)) pool.add(n);
        if (!pool.isEmpty()) return pool.get(Math.min(pool.size() - 1, (int) (roll * pool.size())));
        List<String> any = extra.names().getOrDefault("any", List.of("정류장"));
        String base = any.get(Math.min(any.size() - 1, (int) (roll * any.size())));
        for (int i = 2; ; i++) if (!used.contains(base + " " + i)) return base + " " + i;
    }

    /** A bus's rules (any bus not listed takes everyone). */
    public Bus bus(String itemKey) {
        return extra.buses().getOrDefault(itemKey, Bus.ANY);
    }

    /** Whether today (a calendar day number) is market day. */
    public boolean marketDay(int day) {
        return Math.floorMod(day, extra.marketEvery()) == 0;
    }

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }

    private static List<Step> steps(List<Map<?, ?>> list, String at, String factor) {
        List<Step> out = new ArrayList<>();
        for (Map<?, ?> m : list) out.add(new Step(num(m, at, 0).doubleValue(), num(m, factor, 1).doubleValue()));
        return out;
    }

    private static Number num(Map<?, ?> m, String key, Number def) {
        return m.get(key) instanceof Number n ? n : def;
    }

    // ------------------------------------------------------------------------------------------
    // The numbers
    // ------------------------------------------------------------------------------------------

    /** The fare before mood and the daily curve: base + road blocks × per-block, times the type's fare. */
    public double fare(double roadBlocks, Type t) {
        return (fare.base() + Math.max(0, roadBlocks) * fare.perBlock()) * (t == null ? 1 : t.fare());
    }

    /** The mood factor: the first step whose threshold the mood reaches (steps from the top down). */
    public double moodFactor(double mood) {
        for (Step s : fare.mood()) if (mood >= s.at()) return s.factor();
        return fare.mood().isEmpty() ? 1 : fare.mood().getLast().factor();
    }

    /** Seconds a ride of this many road blocks may take and still tip. */
    public double onTime(double roadBlocks) {
        return roadBlocks / Math.max(0.1, fare.onTimeSpeed()) + fare.onTimeGrace();
    }

    /** The same for a kind of passenger (배달 라이더: a tighter window). */
    public double onTime(double roadBlocks, Type t) {
        return onTime(roadBlocks) * (t == null ? 1 : t.x().onTime());
    }

    /**
     * What is paid of an amount when the driver already earned this much today: each band of the daily curve at its rate
     * (a fare across a band boundary is paid at both).
     */
    public double daily(double earnedToday, double amount) {
        double paid = 0, at = earnedToday, left = amount;
        for (Step s : fare.daily()) {
            if (left <= 0) break;
            double top = s.at() < 0 ? Double.MAX_VALUE : s.at();
            if (at >= top) continue;
            // the band's room is counted in what is paid out (earned money is what the driver received)
            double room = (top - at) / Math.max(1e-9, s.factor());
            double used = Math.min(left, room);
            paid += used * s.factor();
            at += used * s.factor();
            left -= used;
        }
        return paid;
    }

    /** How many times more passengers come at this hour (Korean time). */
    public double hourFactor(int hour) {
        for (Hours h : stops.hours()) {
            boolean in = h.from() <= h.to() ? hour >= h.from() && hour < h.to() : hour >= h.from() || hour < h.to();
            if (in) return h.factor();
        }
        return 1;
    }

    /** Whether this hour is a rush hour (a factor over 1). */
    public boolean rush(int hour) {
        return hourFactor(hour) > 1;
    }
}
