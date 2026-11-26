package dev.stsv.economy.store;

import dev.stsv.economy.bus.BusSpec;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * store.yml: the 편의점 알바 (README "편의점", docs/plans/편의점_기획안.md). Products (§15 ids, prices in 원 for the till only),
 * shelves and their stock, which bus kinds come in (bus.yml types: names, skins, shiny) at which game hours, what each likes,
 * their store talk, and the pay. Pure (unit-tested).
 */
public record StoreSpec(Map<String, Product> products, Map<String, Shelf> shelves, Map<String, Customer> customers,
                        Map<String, Time> times, Map<String, List<String>> talk, Pay pay, Cash cash, Warm warm, Patience patience,
                        Spawn spawn, Shift shift, MoodRules mood, List<Integer> deliveries, int recognizeAt, double shinyChance) {

    /** Where products stand (§15 "곳"). */
    public static final Set<String> PLACES = Set.of("meal", "noodle", "fridge", "snack", "daily", "freezer", "counter");
    /** The talk situations every kind has (its own or the default lines). */
    public static final List<String> SITUATIONS = List.of("enter", "browse", "pay", "wait", "thanks", "angry");
    /** The extra situations (default lines only, a kind may have its own). */
    public static final List<String> EXTRA = List.of("empty", "warm", "short", "over", "recognize", "card", "cash", "burnt", "warmed");

    /** A product: its id (economy:store_item_&lt;id&gt; is its model), name, price (원, the till's number) and place. */
    public record Product(String id, String name, int price, String place, double weight) {
        public String item() {
            return "economy:store_item_" + id;
        }
    }

    /** A stocked shelf group (snack, noodle, daily, meal, fridge): how many it holds full and the delivery box that fills it. */
    public record Shelf(String place, int capacity, String box) {
        /** full / half / empty: the model state for this much stock. */
        public String state(int stock) {
            if (stock <= 0) return "empty";
            return stock * 3 >= capacity * 2 ? "full" : "half";
        }
    }

    /**
     * A bus kind as a store customer: how often (`weight`), how many things (`min` .. `max`), what it likes (product -> weight;
     * `taste` of its picks come from these), game hours it comes in (`hourFrom` .. `hourTo`, -1: any), its own talk.
     */
    public record Customer(String type, double weight, int min, int max, Map<String, Double> likes, double taste, int hourFrom, int hourTo,
                           Map<String, List<String>> talk) {
        public boolean at(int hour) {
            if (hourFrom < 0) return true;
            return hourFrom <= hourTo ? hour >= hourFrom && hour < hourTo : hour >= hourFrom || hour < hourTo;
        }
    }

    /** A part of the game day (hours from .. to): customers come `every` × as far apart, these kinds `boost` × as often. */
    public record Time(String id, int from, int to, double every, Map<String, Double> boost) {
        public boolean at(int hour) {
            return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
        }
    }

    public record Step(double at, double value) {}

    /**
     * The pay (G): `base` a customer served + a tip by mood (× the kind's bus tip, at most `tipCap`) + `accuracy` when served
     * without a mistake; at clock-out the whole × (1 + rating bonus); then × `share` (직영점 40%); minus overpaid change × `overpayRate`.
     */
    public record Pay(double share, long base, List<Step> tips, double tipCap, long accuracy, List<Step> rating, List<Step> stars, double overpayRate) {}

    /** Paying: `card` of customers pay by card; cash customers pay a bill (or round up in 1,000s with `roundUp`); `manual`: the worker gives change. */
    public record Cash(boolean manual, double card, double roundUp, List<Integer> bills, List<Integer> change) {}

    /** 데워 주세요: these products, `chance` per customer with one, `seconds` in the microwave, `forget` seconds after the ding it burns. */
    public record Warm(Set<String> items, double chance, int seconds, int forget) {}

    /** Waiting in line or at the counter: after `grace` seconds the mood sinks to `floor` at `full`; at `giveUp` seconds they leave. */
    public record Patience(double grace, double full, double floor, double giveUp) {
        public double mood(double start, double seconds) {
            if (seconds <= grace || floor >= start) return start;
            if (seconds >= full || full <= grace) return floor;
            return start + (floor - start) * (seconds - grace) / (full - grace);
        }
    }

    /** Customers come every `min` .. `max` seconds (× the time of day's every), at most `maxInside` inside, `queue` in line. */
    public record Spawn(int min, int max, int maxInside, int queue, double speed) {}

    /** The shift: idle this many seconds = clocked out; away from the store this many seconds = clocked out. */
    public record Shift(int afk, int away) {}

    /** Mood changes (+/-): start, exact change, short change, overpaid, an empty shelf, warmed, burnt, a fast checkout. */
    public record MoodRules(double start, double exact, double shortChange, double over, double empty, double warmed, double burnt) {}

    // ------------------------------------------------------------------------------------------

    public static StoreSpec parse(ConfigurationSection c, List<String> problems) {
        Map<String, Product> products = new LinkedHashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("products");
        if (ps == null) problems.add("products 가 없음");
        else for (String id : ps.getKeys(false)) {
            ConfigurationSection p = ps.getConfigurationSection(id);
            if (p == null) continue;
            String place = p.getString("place", "");
            if (!PLACES.contains(place)) problems.add("products." + id + ".place: " + PLACES + " 중 하나");
            int price = p.getInt("price", 0);
            if (price <= 0 || price % 100 != 0) problems.add("products." + id + ".price: 100원 단위, 0 초과");
            products.put(id, new Product(id, p.getString("name", id), Math.max(100, price), place, Math.max(0, p.getDouble("weight", 1))));
        }
        Map<String, Shelf> shelves = new LinkedHashMap<>();
        ConfigurationSection ss = c.getConfigurationSection("shelves");
        if (ss != null) for (String place : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(place);
            if (s == null) continue;
            if (!PLACES.contains(place)) problems.add("shelves." + place + ": 없는 곳");
            shelves.put(place, new Shelf(place, Math.max(1, s.getInt("capacity", 12)), s.getString("box", place)));
        }
        Map<String, Customer> customers = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("customers");
        if (cs == null) problems.add("customers 가 없음");
        else for (String type : cs.getKeys(false)) {
            ConfigurationSection t = cs.getConfigurationSection(type);
            if (t == null) continue;
            Map<String, Double> likes = new LinkedHashMap<>();
            ConfigurationSection ls = t.getConfigurationSection("likes");
            if (ls != null) for (String k : ls.getKeys(false)) {
                if (!products.containsKey(k)) problems.add("customers." + type + ".likes: 없는 상품 " + k);
                else likes.put(k, Math.max(0, ls.getDouble(k, 1)));
            }
            List<Integer> items = t.getIntegerList("items");
            int min = items.size() == 2 ? items.get(0) : 1, max = items.size() == 2 ? items.get(1) : 3;
            if (min < 1 || max < min || max > 5) problems.add("customers." + type + ".items: [최소, 최대] 1~5");
            List<Integer> hours = t.getIntegerList("hours");
            customers.put(type, new Customer(type, Math.max(0, t.getDouble("weight", 1)), Math.clamp(min, 1, 5), Math.clamp(Math.max(min, max), 1, 5),
                    likes, Math.clamp(t.getDouble("taste", 0.8), 0, 1), hours.size() == 2 ? hours.get(0) : -1, hours.size() == 2 ? hours.get(1) : -1,
                    talk(t.getConfigurationSection("talk"))));
        }
        Map<String, Time> times = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection("times");
        if (ts != null) for (String id : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(id);
            if (t == null) continue;
            Map<String, Double> boost = new LinkedHashMap<>();
            ConfigurationSection b = t.getConfigurationSection("boost");
            if (b != null) for (String k : b.getKeys(false)) {
                if (!customers.containsKey(k)) problems.add("times." + id + ".boost: customers 에 없는 손님 " + k);
                boost.put(k, Math.max(0, b.getDouble(k, 1)));
            }
            times.put(id, new Time(id, Math.floorMod(t.getInt("from", 0), 24), Math.floorMod(t.getInt("to", 0), 24),
                    Math.max(0.1, t.getDouble("every", 1)), boost));
        }
        for (int h = 0; h < 24; h++) {
            final int hour = h;
            if (times.values().stream().noneMatch(t -> t.at(hour))) problems.add("times: " + h + "시가 어느 때에도 없음");
        }
        ConfigurationSection p = section(c, "pay");
        Pay pay = new Pay(Math.clamp(p.getDouble("share", 0.4), 0, 1), Math.max(0, p.getLong("base", 450)), steps(p.getMapList("tip"), "at", "g"),
                Math.max(1, p.getDouble("tip-cap", 2)), Math.max(0, p.getLong("accuracy", 60)), steps(p.getMapList("rating"), "at", "bonus"),
                steps(p.getMapList("stars"), "at", "stars"), Math.max(0, p.getDouble("overpay-rate", 0.1)));
        if (pay.tips().isEmpty()) problems.add("pay.tip 이 비어 있음");
        if (pay.stars().isEmpty()) problems.add("pay.stars 가 비어 있음");
        ConfigurationSection k = section(c, "cash");
        List<Integer> bills = k.getIntegerList("bills"), change = k.getIntegerList("change");
        if (bills.isEmpty()) bills = List.of(1000, 5000, 10000, 50000);
        if (change.isEmpty()) change = List.of(50000, 10000, 5000, 1000, 500, 100);
        Cash cash = new Cash(k.getBoolean("manual", true), Math.clamp(k.getDouble("card", 0.6), 0, 1), Math.clamp(k.getDouble("round-up", 0.3), 0, 1),
                bills.stream().sorted().toList(), change.stream().sorted(java.util.Comparator.reverseOrder()).toList());
        ConfigurationSection w = section(c, "warm");
        Warm warm = new Warm(Set.copyOf(w.getStringList("items")), Math.clamp(w.getDouble("chance", 0.5), 0, 1), Math.max(1, w.getInt("seconds", 20)),
                Math.max(1, w.getInt("forget", 20)));
        for (String id : warm.items()) if (!products.containsKey(id)) problems.add("warm.items: 없는 상품 " + id);
        ConfigurationSection pt = section(c, "patience");
        Patience patience = new Patience(pt.getDouble("grace", 20), pt.getDouble("full", 90), pt.getDouble("floor", 20), pt.getDouble("give-up", 120));
        ConfigurationSection sp = section(c, "spawn");
        List<Integer> every = sp.getIntegerList("every");
        Spawn spawn = new Spawn(every.size() == 2 ? Math.max(3, every.get(0)) : 20, every.size() == 2 ? Math.max(every.get(0), every.get(1)) : 45,
                Math.clamp(sp.getInt("max-inside", 6), 1, 6), Math.clamp(sp.getInt("queue", 5), 1, 5), Math.clamp(sp.getDouble("speed", 0.16), 0.05, 0.4));
        ConfigurationSection sh = section(c, "shift");
        Shift shift = new Shift(Math.max(30, sh.getInt("afk", 180)), Math.max(5, sh.getInt("away", 30)));
        ConfigurationSection m = section(c, "mood");
        MoodRules mood = new MoodRules(m.getDouble("start", 80), m.getDouble("exact", 5), m.getDouble("short", -25), m.getDouble("over", 5),
                m.getDouble("empty", -15), m.getDouble("warmed", 5), m.getDouble("burnt", -25));
        List<Integer> deliveries = c.getIntegerList("delivery.hours");
        if (deliveries.isEmpty()) deliveries = List.of(7, 19);
        Map<String, List<String>> talk = talk(c.getConfigurationSection("talk"));
        for (String s : SITUATIONS) if (talk.getOrDefault(s, List.of()).isEmpty()) problems.add("talk." + s + ": 기본 대사가 없음");
        for (String s : EXTRA) if (talk.getOrDefault(s, List.of()).isEmpty()) problems.add("talk." + s + ": 기본 대사가 없음");
        for (Customer cu : customers.values()) for (String s : cu.talk().keySet()) {
            if (!SITUATIONS.contains(s) && !EXTRA.contains(s)) problems.add("customers." + cu.type() + ".talk: 모르는 상황 " + s);
        }
        return new StoreSpec(products, shelves, customers, times, talk, pay, cash, warm, patience, spawn, shift, mood,
                deliveries.stream().map(h -> Math.floorMod(h, 24)).sorted().toList(), Math.max(1, c.getInt("recognize-at", 10)),
                Math.clamp(c.getDouble("shiny-chance", -1), -1, 1));
    }

    /** Problems against the bus kinds: every customer is a bus kind that really comes (weight over 0). */
    public List<String> check(BusSpec bus) {
        List<String> out = new ArrayList<>();
        for (String t : customers.keySet()) {
            BusSpec.Type bt = bus.types().get(t);
            if (bt == null) out.add("customers." + t + ": bus.yml 에 없는 손님 종류");
            else if (bt.weight() <= 0) out.add("customers." + t + ": 숨은 손님은 편의점에 오지 않음");
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Who comes, what they take
    // ------------------------------------------------------------------------------------------

    /** The part of the day at this game hour (0 .. 23). */
    public Time time(int hour) {
        for (Time t : times.values()) if (t.at(hour)) return t;
        return new Time("any", 0, 0, 1, Map.of());
    }

    /** A kind's weight at this hour (0: not now, or not a kind of `allowed`). */
    public double weight(Customer c, int hour) {
        if (!c.at(hour)) return 0;
        return c.weight() * time(hour).boost().getOrDefault(c.type(), 1.0);
    }

    /** Who walks in at this hour: a kind of `allowed` (the bus kinds there are), by weight; null when none may come. */
    public String pickType(int hour, Set<String> allowed, RandomGenerator rnd) {
        double total = 0;
        for (Customer c : customers.values()) if (allowed.contains(c.type())) total += weight(c, hour);
        if (total <= 0) return null;
        double roll = rnd.nextDouble() * total;
        String last = null;
        for (Customer c : customers.values()) {
            if (!allowed.contains(c.type())) continue;
            double w = weight(c, hour);
            if (w <= 0) continue;
            last = c.type();
            roll -= w;
            if (roll < 0) return c.type();
        }
        return last;
    }

    /**
     * What a customer of this kind takes (product ids, 1 .. 5, in the order they are fetched): min .. max things, each from
     * its likes with `taste` (by weight), else from every product (by the product's weight); the same product at most twice.
     */
    public List<String> pickItems(Customer c, RandomGenerator rnd) {
        int n = c.min() + (c.max() > c.min() ? rnd.nextInt(c.max() - c.min() + 1) : 0);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n && out.size() < 5; i++) {
            boolean fromLikes = !c.likes().isEmpty() && rnd.nextDouble() < c.taste();
            String id = fromLikes ? weighted(c.likes(), out, rnd) : weighted(allWeights(), out, rnd);
            if (id != null) out.add(id);
        }
        return out;
    }

    private Map<String, Double> allWeights() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Product p : products.values()) m.put(p.id(), p.weight());
        return m;
    }

    private static String weighted(Map<String, Double> ws, List<String> taken, RandomGenerator rnd) {
        double total = 0;
        for (Map.Entry<String, Double> e : ws.entrySet()) if (count(taken, e.getKey()) < 2) total += e.getValue();
        if (total <= 0) return null;
        double roll = rnd.nextDouble() * total;
        String last = null;
        for (Map.Entry<String, Double> e : ws.entrySet()) {
            if (count(taken, e.getKey()) >= 2 || e.getValue() <= 0) continue;
            last = e.getKey();
            roll -= e.getValue();
            if (roll < 0) return e.getKey();
        }
        return last;
    }

    private static int count(List<String> l, String id) {
        int n = 0;
        for (String s : l) if (s.equals(id)) n++;
        return n;
    }

    /** Seconds to the next customer at this hour: min .. max × the time of day's every. */
    public double interval(int hour, RandomGenerator rnd) {
        double s = spawn.min() + rnd.nextDouble() * (spawn.max() - spawn.min());
        return s * time(hour).every();
    }

    /** The lines of a kind in a situation: its own, else the default ones (null: none). */
    public List<String> lines(String type, String situation) {
        Customer c = customers.get(type);
        List<String> own = c == null ? null : c.talk().get(situation);
        if (own != null && !own.isEmpty()) return own;
        List<String> d = talk.get(situation);
        return d == null || d.isEmpty() ? null : d;
    }

    // ------------------------------------------------------------------------------------------
    // The pay
    // ------------------------------------------------------------------------------------------

    /** The tip (G) for a customer leaving in this mood, × their kind's bus tip factor (at most tip-cap). */
    public long tip(double mood, double kindTip) {
        double g = 0;
        for (Step s : pay.tips()) if (mood >= s.at()) {
            g = s.value();
            break;
        }
        return Math.round(g * Math.clamp(kindTip, 0, pay.tipCap()));
    }

    /** ★ (1 .. 5) of a customer leaving in this mood. */
    public int stars(double mood) {
        for (Step s : pay.stars()) if (mood >= s.at()) return (int) Math.round(s.value());
        return 1;
    }

    /** The rating bonus (a fraction) for this average ★. */
    public double ratingBonus(double avgStars) {
        for (Step s : pay.rating()) if (avgStars + 1e-9 >= s.at()) return s.value();
        return 0;
    }

    /** What one served customer earns before the share (G). */
    public long earn(double mood, double kindTip, boolean accurate) {
        return pay.base() + tip(mood, kindTip) + (accurate ? pay.accuracy() : 0);
    }

    // ------------------------------------------------------------------------------------------

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }

    private static List<Step> steps(List<Map<?, ?>> list, String at, String value) {
        List<Step> out = new ArrayList<>();
        for (Map<?, ?> m : list) out.add(new Step(num(m, at), num(m, value)));
        out.sort((a, b) -> Double.compare(b.at(), a.at()));
        return List.copyOf(out);
    }

    private static double num(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    private static Map<String, List<String>> talk(ConfigurationSection t) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (t != null) for (String k : t.getKeys(false)) out.put(k, List.copyOf(t.getStringList(k)));
        return out;
    }
}
