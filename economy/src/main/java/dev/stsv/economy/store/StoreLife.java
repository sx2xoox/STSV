package dev.stsv.economy.store;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * store.yml's 3차 + 보완안 (README "편의점" 경력 · 발주 · 단골 메뉴 · 2인 근무 · 버스 · 러시 · 하루 이벤트, 기획안 §8 §9 §16):
 * the five ranks and what each opens, the night shift, the 발주, the regulars' usual, two workers and hired workers' shares,
 * the bus link, the rush and the day's events. Pure (unit-tested); {@link StoreSpec} keeps the 1차, {@link StoreRules} the 2차.
 */
public record StoreLife(List<Rank> ranks, String title, String vestManager, String vestNight, Night night, Sticker sticker, Order order,
                        Regulars regulars, Staff staff, BusLink bus, Rush rush, Events events) {

    // ------------------------------------------------------------------------------------------ 경력

    /** A rank: its id, name, the customers served it takes, how many things one scan takes (스캔 단축), its place (0 .. 4). */
    public record Rank(String id, String name, int at, int scan, int index) {}

    /** The rank of a worker who has served this many (the highest whose `at` they reached). */
    public Rank rank(long served) {
        Rank out = ranks.getFirst();
        for (Rank r : ranks) if (served >= r.at()) out = r;
        return out;
    }

    /** The rank after this one, or null at the top. */
    public Rank next(Rank r) {
        return r.index() + 1 < ranks.size() ? ranks.get(r.index() + 1) : null;
    }

    public Rank rankOf(String id) {
        for (Rank r : ranks) if (r.id().equals(id) || r.name().replace(" ", "").equals(id == null ? "" : id.replace(" ", ""))) return r;
        return null;
    }

    /** Whether a worker who served this many reached the rank of this id (an unknown id: nobody). */
    public boolean reached(long served, String id) {
        Rank need = rankOf(id);
        return need != null && rank(served).index() >= need.index();
    }

    /** The rank's index, -1 when unknown. */
    public int index(String id) {
        Rank r = rankOf(id);
        return r == null ? -1 : r.index();
    }

    /** The highest rank. */
    public Rank top() {
        return ranks.getLast();
    }

    /**
     * 스캔 단축: how many of a till's things one click scans: the rank's number, all that are left for a regular's basket (묶음
     * 스캔), at least 1.
     */
    public static int scanBatch(Rank r, boolean regular, int left) {
        if (left <= 0) return 0;
        if (regular) return left;
        return Math.clamp(r.scan(), 1, left);
    }

    // ------------------------------------------------------------------------------------------ 야간

    /** 야간 근무: from the rank of id `rank`, game hours from .. to (wrapping), pay ×, these kinds more often. */
    public record Night(String rank, int from, int to, double pay, Map<String, Double> boost) {
        public boolean at(int hour) {
            return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
        }
    }

    /** What a customer served now earns (G) at this hour, by a worker of this rank: × the night pay when it is night and they may work it. */
    public long nightPay(long earn, int hour, long served) {
        if (!night.at(hour) || !reached(served, night.rank())) return earn;
        return Math.round(earn * night.pay());
    }

    /** Whether a worker who has served this many may clock in at this hour (a 수습 not at night). */
    public boolean mayWork(int hour, long served) {
        return !night.at(hour) || reached(served, night.rank());
    }

    /** 폐기 할인 스티커: from the rank `rank`, a customer who picked a 폐기 is pleased by `mood` and it earns `bonus` (G). */
    public record Sticker(String rank, double mood, long bonus) {}

    // ------------------------------------------------------------------------------------------ 발주

    /** An order group: its name and products. */
    public record Group(String id, String name, Set<String> products) {}

    /** 발주: from rank `rank`, per item of the day's group sold `hit` (matched) / `miss` (not), `first` once on a matched day. */
    public record Order(String rank, long hit, long miss, long first, Map<String, Group> groups) {
        public Group group(String word) {
            if (word == null) return null;
            for (Group g : groups.values()) if (g.id().equals(word) || g.name().equals(word)) return g;
            return null;
        }

        /** The group after this one (the tablet's next page), the first after the last or when none. */
        public Group after(String id) {
            List<Group> l = new ArrayList<>(groups.values());
            if (l.isEmpty()) return null;
            for (int i = 0; i < l.size(); i++) if (l.get(i).id().equals(id)) return l.get((i + 1) % l.size());
            return l.getFirst();
        }
    }

    /** The groups the days ask for, in the order they win (rain over a 장날 over summer over a weekend, else snack). */
    public static final List<String> DEMANDS = List.of("rain", "market", "cold", "night", "snack");

    /** What sells best on a day: its rain, 장날, the month (summer months), a weekend. */
    public static String demand(boolean rain, boolean market, int month, Set<Integer> summerMonths, boolean weekend) {
        if (rain) return "rain";
        if (market) return "market";
        if (summerMonths.contains(month)) return "cold";
        if (weekend) return "night";
        return "snack";
    }

    /**
     * The 발주 bonus (G, before the share) for these things sold on a day the store ordered `ordered` (null: no order) and the
     * day asked for `wanted`: hit per item of the ordered group when it was right, miss per item when not; never below 0.
     */
    public long orderBonus(String ordered, String wanted, List<String> items) {
        if (ordered == null) return 0;
        Group g = order.groups().get(ordered);
        if (g == null) return 0;
        long each = ordered.equals(wanted) ? order.hit() : order.miss();
        long n = 0;
        for (String i : items) if (g.products().contains(i)) n++;
        return Math.max(0, n * each);
    }

    // ------------------------------------------------------------------------------------------ 단골 메뉴

    /** 단골: after `at` of a kind served, now and then (`chance`) they ask for their usual (menu: kind -> product). */
    public record Regulars(int at, double chance, double mood, double tip, double wrong, int hint, Map<String, String> menu,
                           Map<String, List<String>> talk) {
        public String usual(String type) {
            return menu.get(type);
        }

        /** Whether one of this kind, served `served` times by this worker, asks for their usual now (the roll in 0..1). */
        public boolean asks(String type, long served, double roll) {
            return menu.containsKey(type) && served >= at && roll < chance;
        }

        /** Whether this kind is a regular of this worker (묶음 스캔). */
        public boolean regular(long served) {
            return served >= at;
        }

        public List<String> lines(String key) {
            return talk.getOrDefault(key, List.of());
        }
    }

    // ------------------------------------------------------------------------------------------ 2인 근무 · 남의 가게 알바

    /**
     * Two workers and hired workers (§8 §13): the 내 가게 tiers two may work in, a hired worker's share and the owner's cut,
     * the 진열 worker's pay per restock / chore / customer (G before the share), the customers' gaps when two work.
     */
    public record Staff(Set<String> two, double hiredShare, double ownerCut, long restock, long chore, long assist, double busy) {
        /**
         * A worker's share of what they earn: the 직영점's (`direct`) when there is no owner, all of it for the owner, the
         * hired share for anyone else.
         */
        public double share(boolean owned, boolean isOwner, double direct) {
            if (!owned) return direct;
            return isOwner ? 1 : hiredShare;
        }

        /** What the owner gets when a hired worker is paid `paid` (G, after their share): the owner's cut of the same earning. */
        public long ownerCut(long paid) {
            if (paid <= 0 || hiredShare <= 0) return 0;
            return Math.round(paid * ownerCut / hiredShare);
        }

        /** Whether two may work in a store of this tier (null: a 직영점, always). */
        public boolean twoIn(String tier) {
            return tier == null || two.contains(tier);
        }
    }

    // ------------------------------------------------------------------------------------------ 버스

    /** The bus link (§9). */
    public record BusLink(double radius, double carry, int most, double ask, double askMood, int greetAt,
                          Map<String, List<String>> talk) {
        public List<String> lines(String key) {
            return talk.getOrDefault(key, List.of());
        }
    }

    // ------------------------------------------------------------------------------------------ 러시

    /** 러시 타임: game hour windows [from, to), customers `every` × as far apart, `target` served within `seconds` = `bonus` G. */
    public record Rush(List<int[]> windows, double every, int target, int seconds, long bonus) {
        /** The window (its index) this game hour is in, -1 none. */
        public int window(int hour) {
            for (int i = 0; i < windows.size(); i++) {
                int[] w = windows.get(i);
                if (w[0] <= w[1] ? hour >= w[0] && hour < w[1] : hour >= w[0] || hour < w[1]) return i;
            }
            return -1;
        }
    }

    /** One rush going on: started at (ms), served so far; it ends cleared (the target in time) or missed (time up). */
    public static final class RushRun {
        public enum State { ON, CLEARED, MISSED }

        private final long start;
        private final int target, seconds;
        private int served;
        private State state = State.ON;

        public RushRun(long start, int target, int seconds) {
            this.start = start;
            this.target = Math.max(1, target);
            this.seconds = Math.max(1, seconds);
        }

        /** A customer served during it: CLEARED when that was the target (once). */
        public State served(long now) {
            if (state != State.ON) return state;
            if (now - start > seconds * 1000L) return state = State.MISSED;
            if (++served >= target) state = State.CLEARED;
            return state;
        }

        /** The clock: MISSED once the time is up and the target was not reached. */
        public State tick(long now) {
            if (state == State.ON && now - start > seconds * 1000L) state = State.MISSED;
            return state;
        }

        public State state() {
            return state;
        }

        public int served() {
            return served;
        }

        public int target() {
            return target;
        }

        /** Seconds left (0 when over). */
        public int left(long now) {
            return (int) Math.max(0, (start + seconds * 1000L - now + 999) / 1000);
        }
    }

    // ------------------------------------------------------------------------------------------ 하루 이벤트

    public enum Event { OUTAGE, GROUP, INSPECT }

    public record Outage(double chance, int seconds, double mood, long fix) {}

    public record Crowd(double chance, int min, int max, int gapMin, int gapMax, List<String> kinds) {}

    public record Inspect(double chance, int seconds, String skin, String kind, long good, long fine, int pass, int fail) {
        public enum Verdict { GOOD, OK, BAD }

        /** The score (0 .. 100) of a store as the inspector sees it. */
        public static int score(int emptyShelves, int expired, int messes, boolean dryKettle, int boxes) {
            int s = 100 - 15 * emptyShelves - 10 * expired - 10 * messes - (dryKettle ? 10 : 0) - 5 * boxes;
            return Math.clamp(s, 0, 100);
        }

        public Verdict verdict(int score) {
            return score >= pass ? Verdict.GOOD : score < fail ? Verdict.BAD : Verdict.OK;
        }
    }

    /** The day's events: rolled every `check` seconds of a shift, at most `perDay` a game day in a store. */
    public record Events(int check, int perDay, Outage outage, Crowd group, Inspect inspect, Map<String, List<String>> talk) {
        /** Which event comes on this roll (0..1), by the chances in order; null: none. */
        public Event pick(double roll) {
            double at = 0;
            at += outage.chance();
            if (roll < at) return Event.OUTAGE;
            at += group.chance();
            if (roll < at) return Event.GROUP;
            at += inspect.chance();
            if (roll < at) return Event.INSPECT;
            return null;
        }

        public List<String> lines(String key) {
            return talk.getOrDefault(key, List.of());
        }
    }

    // ------------------------------------------------------------------------------------------ parse

    public static StoreLife parse(ConfigurationSection c, StoreSpec spec, List<String> problems) {
        List<Rank> ranks = new ArrayList<>();
        ConfigurationSection rs = c.getConfigurationSection("career.ranks");
        ConfigurationSection scan = c.getConfigurationSection("career.scan");
        if (rs == null || rs.getKeys(false).isEmpty()) {
            problems.add("career.ranks 가 없음");
            ranks.add(new Rank("trainee", "수습", 0, 1, 0));
        } else {
            int last = -1;
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r == null) continue;
                int at = Math.max(0, r.getInt("at", 0));
                if (ranks.isEmpty() && at != 0) problems.add("career.ranks." + id + ".at: 첫 계급은 0");
                if (at <= last) problems.add("career.ranks." + id + ".at: 위 계급일수록 많아야 함");
                last = at;
                ranks.add(new Rank(id, r.getString("name", id), at, Math.clamp(scan == null ? 1 : scan.getInt(id, 1), 1, 5), ranks.size()));
            }
        }
        StoreLife probe = new StoreLife(ranks, "", "", "", null, null, null, null, null, null, null, null);
        ConfigurationSection n = section(c, "night");
        Map<String, Double> boost = new LinkedHashMap<>();
        ConfigurationSection nb = n.getConfigurationSection("boost");
        if (nb != null) for (String k : nb.getKeys(false)) {
            if (!spec.customers().containsKey(k)) problems.add("night.boost: customers 에 없는 손님 " + k);
            boost.put(k, Math.max(0, nb.getDouble(k, 1)));
        }
        Night night = new Night(rankId(n.getString("rank", "parttime"), probe, "night.rank", problems), Math.floorMod(n.getInt("from", 20), 24),
                Math.floorMod(n.getInt("to", 6), 24), Math.max(1, n.getDouble("pay", 1.3)), java.util.Collections.unmodifiableMap(boost));
        ConfigurationSection st = section(c, "sticker");
        Sticker sticker = new Sticker(rankId(st.getString("rank", "deputy"), probe, "sticker.rank", problems), st.getDouble("mood", 3), Math.max(0, st.getLong("bonus", 40)));

        ConfigurationSection o = section(c, "order");
        Map<String, Group> groups = new LinkedHashMap<>();
        ConfigurationSection gs = o.getConfigurationSection("groups");
        if (gs != null) for (String id : gs.getKeys(false)) {
            ConfigurationSection g = gs.getConfigurationSection(id);
            if (g == null) continue;
            if (!DEMANDS.contains(id)) problems.add("order.groups." + id + ": " + DEMANDS + " 중 하나");
            Set<String> ps = new LinkedHashSet<>();
            for (String p : g.getStringList("products")) {
                if (!spec.products().containsKey(p)) problems.add("order.groups." + id + ": 없는 상품 " + p);
                else ps.add(p);
            }
            if (ps.isEmpty()) problems.add("order.groups." + id + ": 상품이 없음");
            groups.put(id, new Group(id, g.getString("name", id), Set.copyOf(ps)));
        }
        for (String d : DEMANDS) if (!groups.containsKey(d)) problems.add("order.groups." + d + ": 없음");
        Order order = new Order(rankId(o.getString("rank", "senior"), probe, "order.rank", problems), Math.max(0, o.getLong("hit", 60)),
                Math.max(0, o.getLong("miss", 15)), Math.max(0, o.getLong("first", 600)), java.util.Collections.unmodifiableMap(groups));
        if (order.miss() > order.hit()) problems.add("order.miss: hit 보다 클 수 없음");

        ConfigurationSection rg = section(c, "regulars");
        Map<String, String> menu = new LinkedHashMap<>();
        ConfigurationSection ms = rg.getConfigurationSection("menu");
        if (ms != null) for (String k : ms.getKeys(false)) {
            String p = ms.getString(k, "");
            if (!spec.customers().containsKey(k)) problems.add("regulars.menu: customers 에 없는 손님 " + k);
            StoreSpec.Product pr = spec.products().get(p);
            if (pr == null) problems.add("regulars.menu." + k + ": 없는 상품 " + p);
            else if (pr.place().equals("counter")) problems.add("regulars.menu." + k + ": 계산대 물건은 꺼내 줄 수 없음 " + p);
            else menu.put(k, p);
        }
        for (String k : spec.customers().keySet()) if (!menu.containsKey(k)) problems.add("regulars.menu: " + k + " 의 단골 메뉴가 없음");
        Map<String, List<String>> rtalk = talk(rg.getConfigurationSection("talk"));
        for (String k : List.of("ask", "hint", "right", "wrong")) if (rtalk.getOrDefault(k, List.of()).isEmpty()) problems.add("regulars.talk." + k + ": 대사가 없음");
        Regulars regulars = new Regulars(Math.max(1, rg.getInt("at", 5)), clamp01(rg.getDouble("chance", 0.3)), rg.getDouble("mood", 15),
                Math.max(1, rg.getDouble("tip", 2)), rg.getDouble("wrong", -6), Math.max(5, rg.getInt("hint", 20)),
                java.util.Collections.unmodifiableMap(menu), rtalk);

        ConfigurationSection sf = section(c, "staff");
        Set<String> two = new LinkedHashSet<>(sf.getStringList("two"));
        ConfigurationSection tiers = c.getConfigurationSection("own.tiers");
        for (String t : two) if (tiers == null || !tiers.contains(t)) problems.add("staff.two: 없는 등급 " + t);
        Staff staff = new Staff(Set.copyOf(two), Math.clamp(sf.getDouble("hired-share", 0.4), 0.05, 1), Math.clamp(sf.getDouble("owner-cut", 0.2), 0, 1),
                Math.max(0, sf.getLong("restock", 600)), Math.max(0, sf.getLong("chore", 250)), Math.max(0, sf.getLong("assist", 100)),
                Math.clamp(sf.getDouble("busy", 0.8), 0.3, 1));
        if (staff.hiredShare() + staff.ownerCut() > 1) problems.add("staff: hired-share + owner-cut 은 1 이하");

        ConfigurationSection b = section(c, "bus-link");
        Map<String, List<String>> btalk = talk(b.getConfigurationSection("talk"));
        for (String k : List.of("ask", "driver", "none", "greet")) if (btalk.getOrDefault(k, List.of()).isEmpty()) problems.add("bus-link.talk." + k + ": 대사가 없음");
        for (String l : btalk.getOrDefault("driver", List.of())) if (!l.contains("{driver}")) problems.add("bus-link.talk.driver: {driver} 가 없음: " + l);
        BusLink bus = new BusLink(Math.clamp(b.getDouble("radius", 24), 4, 64), clamp01(b.getDouble("carry", 0.5)), Math.clamp(b.getInt("most", 3), 0, 6),
                clamp01(b.getDouble("ask", 0.06)), b.getDouble("ask-mood", 6),
                Math.max(1, b.getInt("greet-at", 10)), btalk);

        ConfigurationSection ru = section(c, "rush");
        List<int[]> windows = new ArrayList<>();
        for (Object w : ru.getList("windows", List.of())) {
            if (w instanceof List<?> l && l.size() == 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number z) {
                windows.add(new int[]{Math.floorMod(a.intValue(), 24), Math.floorMod(z.intValue(), 24)});
            } else problems.add("rush.windows: [시작, 끝] 의 목록");
        }
        Rush rush = new Rush(List.copyOf(windows), Math.clamp(ru.getDouble("every", 0.35), 0.1, 1), Math.max(1, ru.getInt("target", 8)),
                Math.max(10, ru.getInt("seconds", 150)), Math.max(0, ru.getLong("bonus", 1500)));

        ConfigurationSection ev = section(c, "events");
        ConfigurationSection ou = section(ev, "outage"), gr = section(ev, "group"), in = section(ev, "inspect");
        Outage outage = new Outage(clamp01(ou.getDouble("chance", 0.03)), Math.max(10, ou.getInt("seconds", 60)), ou.getDouble("mood", -5), Math.max(0, ou.getLong("fix", 200)));
        List<Integer> gap = gr.getIntegerList("gap");
        List<String> kinds = gr.getStringList("kinds");
        for (String k : kinds) if (!spec.customers().containsKey(k)) problems.add("events.group.kinds: customers 에 없는 손님 " + k);
        if (kinds.isEmpty()) problems.add("events.group.kinds: 비어 있음");
        int gmin = Math.max(1, gr.getInt("min", 8));
        Crowd group = new Crowd(clamp01(gr.getDouble("chance", 0.03)), gmin, Math.max(gmin, gr.getInt("max", 12)), gap.size() == 2 ? Math.max(1, gap.get(0)) : 1,
                gap.size() == 2 ? Math.max(Math.max(1, gap.get(0)), gap.get(1)) : 3, List.copyOf(kinds));
        String skin = in.getString("skin", "");
        if (!skin.matches("[a-z0-9_.-]+:[a-z0-9_./-]+( slim)?")) problems.add("events.inspect.skin: 스킨 키가 아님");
        String kind = in.getString("kind", "office");
        Inspect inspect = new Inspect(clamp01(in.getDouble("chance", 0.02)), Math.max(20, in.getInt("seconds", 120)), skin, kind,
                Math.max(0, in.getLong("good", 1500)), Math.max(0, in.getLong("fine", 800)), Math.clamp(in.getInt("pass", 70), 0, 100), Math.clamp(in.getInt("fail", 40), 0, 100));
        if (inspect.fail() > inspect.pass()) problems.add("events.inspect: fail 은 pass 이하");
        Map<String, List<String>> etalk = talk(ev.getConfigurationSection("talk"));
        for (String k : List.of("outage", "restored", "group", "inspect-good", "inspect-bad")) if (etalk.getOrDefault(k, List.of()).isEmpty()) problems.add("events.talk." + k + ": 대사가 없음");
        Events events = new Events(Math.max(10, ev.getInt("check", 60)), Math.max(0, ev.getInt("per-day", 1)), outage, group, inspect, etalk);
        if (outage.chance() + group.chance() + inspect.chance() > 1) problems.add("events: 확률의 합은 1 이하");

        String title = c.getString("career.title", "");
        if (!title.isEmpty() && !title.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) problems.add("career.title: 키가 아님");
        return new StoreLife(List.copyOf(ranks), title, c.getString("career.vest-manager", "economy:store_vest_manager"),
                c.getString("career.vest-night", "economy:store_vest_night"), night, sticker, order, regulars, staff, bus, rush, events);
    }

    private static String rankId(String id, StoreLife probe, String at, List<String> problems) {
        if (probe.rankOf(id) == null) problems.add(at + ": 없는 계급 " + id);
        return id;
    }

    /** A random whole number in min .. max (both included). */
    static int between(int min, int max, RandomGenerator rnd) {
        return max <= min ? min : min + rnd.nextInt(max - min + 1);
    }

    private static double clamp01(double v) {
        return Math.clamp(v, 0, 1);
    }

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }

    private static Map<String, List<String>> talk(ConfigurationSection t) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (t != null) for (String k : t.getKeys(false)) out.put(k, List.copyOf(t.getStringList(k)));
        return java.util.Collections.unmodifiableMap(out);
    }
}
