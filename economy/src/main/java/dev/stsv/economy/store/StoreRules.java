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
 * store.yml's 2차 (README "편의점" 내 가게 · 신분증 · 진상 · 폐기 · 청소, 기획안 §5 §6 §13): the three 내 가게 등급,
 * the ID check, the food a worker is given (gifts: product -> item), the 폐기 and the mess, the weather's and the calendar's demand, the twelve
 * troubles' buttons and lines. Pure (unit-tested); {@link StoreSpec} keeps the 1차.
 */
public record StoreRules(Map<String, Tier> tiers, Id id, Map<String, String> gifts, Waste waste, Mess mess, Demand demand,
                         Map<Trouble.Kind, TroubleText> troubles, Trouble.Numbers numbers) {

    /**
     * A 내 가게 등급 (기획안 §13): its name, the 권리증's price (G) and item, at most `maxInside` customers inside, each takes
     * `items` things more, a customer earns `base` + `margin` × the bill (원) + tip + accuracy (100% the owner's), what it sells,
     * the furniture it does not have (layout roles), its rank (1 .. 3: upgrades go up).
     */
    public record Tier(String id, String name, long price, String license, int maxInside, int items, long base, double margin,
                       Set<String> products, Set<String> without, int rank) {
        public boolean sells(String product) {
            return products.contains(product);
        }

        /** What one served customer earns before the share (G): as {@link StoreSpec#earn} with this tier's base and margin. */
        public long earn(StoreSpec s, double mood, double kindTip, boolean accurate, int bill) {
            return base + s.tip(mood, kindTip) + (accurate ? s.pay().accuracy() : 0) + Math.round(Math.max(0, bill) * margin);
        }
    }

    /** 신분증 (§5): what needs it, the age, the fine (G) for selling to a minor, the bonus for refusing one, moods, who looks young. */
    public record Id(Set<String> items, int age, long fine, long bonus, double refusedAdult, double refusedMinor, Map<String, IdCheck.Kind> kinds,
                     List<String> names, Map<String, List<String>> talk) {
        /** The lines of the ID check (ask, show, sold, sold-minor, refused-minor, refused-adult). */
        public static final List<String> TALK = List.of("ask", "show", "sold", "sold-minor", "refused-minor", "refused-adult");

        public List<String> lines(String key) {
            return talk.getOrDefault(key, List.of());
        }
    }

    /**
     * 폐기 (§5): on the `place` shelf, every `every` seconds of a shift one more thing expires (red tag), at most `most`; a
     * customer who picks one up complains (`complain` mood); the worker may keep one when throwing them out (`food`).
     */
    public record Waste(String place, int every, int most, double complain, String food) {}

    /**
     * 청소 · 온수 (§5): a drink spills with `spill` per customer (× `messy` for some kinds), a mess costs entering customers `mood`
     * each (at most `most` messes); the kettle holds `water` cups, a 컵라면 customer without hot water `dry`; `eat` of them eat at
     * the bar for `eatMin` .. `eatMax` seconds and leave their cup with `cup`.
     */
    public record Mess(double spill, Map<String, Double> messy, double mood, int most, int water, double dry, double eat, int eatMin, int eatMax,
                       double cup) {
        public double spillChance(String type) {
            return Math.min(1, spill * messy.getOrDefault(type, 1.0));
        }
    }

    /**
     * 날씨 · 달력 (§6): when it rains a customer adds one of `rain` (by weight) with `rainChance`; in the summer months one of
     * `summer` with `summerChance`; on a 장날 `market` × as many customers come.
     */
    public record Demand(double rainChance, Map<String, Double> rain, Set<Integer> summerMonths, double summerChance, Map<String, Double> summer,
                         double market) {
        /** What the weather and the season add to a customer's things (only what the store sells, nothing twice). */
        public List<String> extra(boolean raining, int month, Set<String> sold, List<String> has, RandomGenerator rnd) {
            List<String> out = new ArrayList<>();
            if (raining && rnd.nextDouble() < rainChance) add(rain, sold, has, out, rnd);
            if (summerMonths.contains(month) && rnd.nextDouble() < summerChance) add(summer, sold, has, out, rnd);
            return out;
        }

        private static void add(Map<String, Double> from, Set<String> sold, List<String> has, List<String> out, RandomGenerator rnd) {
            double total = 0;
            for (Map.Entry<String, Double> e : from.entrySet()) if (ok(e.getKey(), sold, has, out)) total += e.getValue();
            if (total <= 0) return;
            double roll = rnd.nextDouble() * total;
            for (Map.Entry<String, Double> e : from.entrySet()) {
                if (!ok(e.getKey(), sold, has, out)) continue;
                roll -= e.getValue();
                if (roll < 0) {
                    out.add(e.getKey());
                    return;
                }
            }
        }

        private static boolean ok(String id, Set<String> sold, List<String> has, List<String> out) {
            return (sold == null || sold.contains(id)) && !has.contains(id) && !out.contains(id);
        }

        /** How many times as many customers today (a 장날 or not). */
        public double busy(boolean marketDay) {
            return marketDay ? market : 1;
        }
    }

    /** A trouble's words: how often it comes with its kind, the buttons' words (choice -> words), its lines (talk key -> lines). */
    public record TroubleText(Trouble.Kind kind, double chance, Map<String, String> buttons, Map<String, List<String>> talk) {
        public List<String> lines(String key) {
            return talk.getOrDefault(key, List.of());
        }
    }

    // ------------------------------------------------------------------------------------------

    public static StoreRules parse(ConfigurationSection c, StoreSpec spec, List<String> problems) {
        Map<String, Tier> tiers = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection("own.tiers");
        if (ts == null) problems.add("own.tiers 가 없음");
        else {
            int rank = 0;
            for (String id : ts.getKeys(false)) {
                ConfigurationSection t = ts.getConfigurationSection(id);
                if (t == null) continue;
                rank++;
                Set<String> products = new LinkedHashSet<>();
                if ("all".equals(t.getString("products"))) products.addAll(spec.products().keySet());
                else for (String p : t.getStringList("products")) {
                    if (!spec.products().containsKey(p)) problems.add("own.tiers." + id + ".products: 없는 상품 " + p);
                    else products.add(p);
                }
                if (products.isEmpty()) problems.add("own.tiers." + id + ".products: 비어 있음");
                Set<String> without = new LinkedHashSet<>(t.getStringList("without"));
                StoreLayout.Layout full = StoreLayout.of();
                for (String r : without) if (full.piece(r) == null) problems.add("own.tiers." + id + ".without: 없는 가구 " + r);
                String license = t.getString("license", "");
                if (!license.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) problems.add("own.tiers." + id + ".license: 아이템 키가 아님");
                long price = t.getLong("price", 0);
                if (price <= 0) problems.add("own.tiers." + id + ".price: 0 초과");
                Tier tier = new Tier(id, t.getString("name", id), price, license, Math.clamp(t.getInt("max-inside", 4), 1, 6),
                        Math.clamp(t.getInt("items", 0), 0, 3), Math.max(0, t.getLong("base", spec.pay().base())), Math.max(0, t.getDouble("margin", 0)),
                        Set.copyOf(products), Set.copyOf(without), rank);
                // what it sells stands somewhere it has
                StoreLayout.Layout l = StoreLayout.of(tier.without());
                for (String p : products) {
                    String place = spec.products().get(p).place();
                    if (!place.equals("counter") && l.spots().getOrDefault(place, List.of()).isEmpty())
                        problems.add("own.tiers." + id + ": " + p + " 의 자리 (" + place + ") 가 이 가게에 없음");
                    if (spec.warm().items().contains(p) && without.contains("microwave")) problems.add("own.tiers." + id + ": " + p + " 는 전자레인지가 있어야 함");
                }
                tiers.put(id, tier);
            }
            long last = 0;
            for (Tier t : tiers.values()) {
                if (t.price() <= last) problems.add("own.tiers." + t.id() + ": 위 등급일수록 비싸야 함");
                last = t.price();
            }
        }
        ConfigurationSection is = section(c, "id");
        Set<String> idItems = new LinkedHashSet<>(is.getStringList("items"));
        for (String p : idItems) if (!spec.products().containsKey(p)) problems.add("id.items: 없는 상품 " + p);
        Map<String, IdCheck.Kind> kinds = new LinkedHashMap<>();
        ConfigurationSection ks = is.getConfigurationSection("kinds");
        if (ks != null) for (String k : ks.getKeys(false)) {
            ConfigurationSection x = ks.getConfigurationSection(k);
            if (x == null) continue;
            if (!spec.customers().containsKey(k)) problems.add("id.kinds: customers 에 없는 손님 " + k);
            kinds.put(k, new IdCheck.Kind(clamp01(x.getDouble("young", 0)), clamp01(x.getDouble("minor", 0)), clamp01(x.getDouble("sibling", 0)),
                    clamp01(x.getDouble("tries", 0))));
        }
        List<String> names = is.getStringList("names");
        if (names.isEmpty()) problems.add("id.names: 신분증 이름이 없음");
        Map<String, List<String>> idTalk = new LinkedHashMap<>();
        ConfigurationSection it = is.getConfigurationSection("talk");
        if (it != null) for (String k : it.getKeys(false)) {
            if (!Id.TALK.contains(k)) problems.add("id.talk: 모르는 대사 " + k);
            idTalk.put(k, List.copyOf(it.getStringList(k)));
        }
        for (String k : Id.TALK) if (idTalk.getOrDefault(k, List.of()).isEmpty()) problems.add("id.talk." + k + ": 대사가 없음");
        Id id = new Id(Set.copyOf(idItems), Math.max(1, is.getInt("age", 19)), Math.max(0, is.getLong("fine", 3000)), Math.max(0, is.getLong("bonus", 300)),
                is.getDouble("refused-adult", -35), is.getDouble("refused-minor", -10), java.util.Collections.unmodifiableMap(kinds), List.copyOf(names),
                java.util.Collections.unmodifiableMap(idTalk));

        // 근무자가 받는 음식 (players no longer buy anything: only these gifts, 할머니's 사탕 and the like)
        Map<String, String> gifts = strings(c.getConfigurationSection("gifts"));
        for (Map.Entry<String, String> g : gifts.entrySet()) {
            if (!spec.products().containsKey(g.getKey())) problems.add("gifts: 없는 상품 " + g.getKey());
            if (!g.getValue().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) problems.add("gifts." + g.getKey() + ": 잘못된 아이템 키 " + g.getValue());
        }
        if (c.isConfigurationSection("players")) problems.add("players: 플레이어 손님은 없어졌음 (이 칸은 지워도 됨)");

        ConfigurationSection ws = section(c, "waste");
        String wastePlace = ws.getString("place", "meal");
        if (!spec.shelves().containsKey(wastePlace)) problems.add("waste.place: 진열대가 아님 " + wastePlace);
        Waste waste = new Waste(wastePlace, Math.max(30, ws.getInt("every", 240)), Math.clamp(ws.getInt("most", 3), 1, 6), ws.getDouble("complain", -15),
                ws.getString("food", ""));

        ConfigurationSection ms = section(c, "mess");
        Map<String, Double> messy = new LinkedHashMap<>();
        ConfigurationSection mk = ms.getConfigurationSection("messy");
        if (mk != null) for (String k : mk.getKeys(false)) messy.put(k, Math.max(0, mk.getDouble(k, 1)));
        List<Integer> eatS = ms.getIntegerList("eat-seconds");
        Mess mess = new Mess(clamp01(ms.getDouble("spill", 0.03)), java.util.Collections.unmodifiableMap(messy), ms.getDouble("mood", -6), Math.clamp(ms.getInt("most", 3), 1, 5),
                Math.max(1, ms.getInt("water", 8)), ms.getDouble("dry", -12), clamp01(ms.getDouble("eat", 0.5)),
                eatS.size() == 2 ? Math.max(3, eatS.get(0)) : 15, eatS.size() == 2 ? Math.max(eatS.get(0), eatS.get(1)) : 30, clamp01(ms.getDouble("cup", 0.5)));

        ConfigurationSection ds = section(c, "demand");
        Map<String, Double> rain = weights(ds.getConfigurationSection("rain.items"), spec, "demand.rain.items", problems);
        Map<String, Double> summer = weights(ds.getConfigurationSection("summer.items"), spec, "demand.summer.items", problems);
        Demand demand = new Demand(clamp01(ds.getDouble("rain.chance", 0.35)), rain, Set.copyOf(ds.getIntegerList("summer.months")),
                clamp01(ds.getDouble("summer.chance", 0.3)), summer, Math.max(1, ds.getDouble("market", 1.5)));

        ConfigurationSection tr = section(c, "troubles");
        Trouble.Numbers d = Trouble.Numbers.DEFAULT;
        Trouble.Numbers numbers = new Trouble.Numbers(Math.max(0, tr.getLong("good", d.good())), Math.max(0, tr.getLong("catch", d.catchBonus())),
                Math.max(0, tr.getLong("parcel", d.parcel())), Math.max(0, tr.getLong("hangover", d.hangover())),
                clamp01(tr.getDouble("lottery.big", d.big())), clamp01(tr.getDouble("lottery.small", d.small())), clamp01(tr.getDouble("ghost", d.ghost())));
        Map<Trouble.Kind, TroubleText> troubles = new LinkedHashMap<>();
        ConfigurationSection list = tr.getConfigurationSection("list");
        for (Trouble.Kind k : Trouble.Kind.values()) {
            ConfigurationSection t = list == null ? null : list.getConfigurationSection(k.id);
            String at = "troubles.list." + k.id;
            if (t == null) {
                problems.add(at + ": 없음");
                continue;
            }
            if (!k.type.equals(t.getString("kind", ""))) problems.add(at + ".kind: " + k.type + " 이어야 함 (bus.yml 손님 종류)");
            if (k.stage != Trouble.Stage.NONE && !spec.customers().containsKey(k.type)) problems.add(at + ": customers 에 " + k.type + " 이 없음");
            Map<String, String> buttons = strings(t.getConfigurationSection("buttons"));
            for (String ch : k.choices) {
                if (k == Trouble.Kind.TOURIST) continue;   // the 관광객's buttons are the places' names
                if (buttons.getOrDefault(ch, "").isBlank()) problems.add(at + ".buttons." + ch + ": 없음");
            }
            if (k == Trouble.Kind.LOTTERY && buttons.getOrDefault("cheer-none", "").isBlank()) problems.add(at + ".buttons.cheer-none: 없음");
            Map<String, List<String>> talk = new LinkedHashMap<>();
            ConfigurationSection tk = t.getConfigurationSection("talk");
            if (tk != null) for (String key : tk.getKeys(false)) {
                if (!k.talk.contains(key)) problems.add(at + ".talk: 모르는 대사 " + key);
                talk.put(key, List.copyOf(tk.getStringList(key)));
            }
            for (String key : k.talk) if (talk.getOrDefault(key, List.of()).isEmpty()) problems.add(at + ".talk." + key + ": 대사가 없음");
            troubles.put(k, new TroubleText(k, clamp01(t.getDouble("chance", 0.5)), java.util.Collections.unmodifiableMap(buttons), java.util.Collections.unmodifiableMap(talk)));
        }
        return new StoreRules(tiers, id, java.util.Collections.unmodifiableMap(gifts), waste, mess, demand, troubles, numbers);
    }

    /** The shelves whose things are eaten or drunk (what a 환불 손님 brings back half eaten). */
    static final Set<String> EDIBLE = Set.of("meal", "noodle", "snack", "fridge", "freezer");

    /** Whether a 환불 손님 may bring this back half eaten: something to eat or drink, not 술 (the ID's things). */
    public boolean refundable(StoreSpec spec, String product) {
        StoreSpec.Product p = spec.products().get(product);
        return p != null && EDIBLE.contains(p.place()) && !id.items().contains(product);
    }

    /** The tier of a name or id (골목점 / alley), or null. */
    public Tier tier(String word) {
        if (word == null) return null;
        for (Tier t : tiers.values()) if (t.id().equals(word) || t.name().equals(word)) return t;
        return null;
    }

    /** The tier whose 권리증 this item is, or null. */
    public Tier byLicense(String item) {
        if (item == null) return null;
        for (Tier t : tiers.values()) if (t.license().equals(item)) return t;
        return null;
    }

    /**
     * The hourly pay of a 내 가게 (G, at 100%) by the 1차's calibration: `served` customers an hour, each with this mood, the
     * average bill (원) for `items` things at `avgPrice`, ★ bonus at the rate for that mood's ★. For the README and the test.
     */
    public static long hourly(StoreSpec s, Tier t, int served, double mood, double items, double avgPrice) {
        long each = t.earn(s, mood, 1, true, (int) Math.round(items * avgPrice));
        double bonus = s.ratingBonus(s.stars(mood));
        return Math.round(served * each * (1 + bonus));
    }

    // ------------------------------------------------------------------------------------------

    private static double clamp01(double v) {
        return Math.clamp(v, 0, 1);
    }

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }

    private static Map<String, String> strings(ConfigurationSection s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) out.put(k, s.getString(k, ""));
        return out;
    }

    private static Map<String, Double> weights(ConfigurationSection s, StoreSpec spec, String at, List<String> problems) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) {
            if (!spec.products().containsKey(k)) problems.add(at + ": 없는 상품 " + k);
            else out.put(k, Math.max(0, s.getDouble(k, 1)));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------------------------------ the shift's small rules (pure)

    /** Standing still: nothing yet, told (the last `warn` seconds), out. */
    public enum Idle { OK, WARN, OUT }

    /** A worker idle for `idle` seconds, out at `afk`: warned `warn` seconds before. */
    public static Idle idle(long idle, int afk, int warn) {
        if (idle > afk) return Idle.OUT;
        return idle > afk - warn ? Idle.WARN : Idle.OK;
    }

    /**
     * Whether a customer leaves the counter to wait aside: paid, the microwave the only thing they still want, and the
     * waiting spot free (one waits there at a time).
     */
    public static boolean stepsAside(boolean paid, java.util.List<String> asks, boolean spotTaken) {
        return paid && !spotTaken && asks.size() == 1 && asks.contains("warm");
    }

    /** Half the width of a green answer button's click box for a label of so many letters (never a sliver). */
    public static double askHalfWidth(int letters) {
        return Math.clamp(0.05 * letters + 0.08, 0.3, 0.7);
    }

    /** Whether a build job running somewhere keeps someone from working in `store` (only that store's own). */
    public static boolean blockedByBuild(boolean running, String building, String store) {
        return running && (building == null || building.equals(store));
    }

    /** Taking a 내 가게 down. */
    public enum Demolish { OK, NOT_OWNER, ON_SHIFT, BUILDING }

    /** Only its owner, only while nobody works in it, only while nothing is being built. */
    public static Demolish mayDemolish(boolean owner, boolean onShift, boolean building) {
        if (!owner) return Demolish.NOT_OWNER;
        if (onShift) return Demolish.ON_SHIFT;
        return building ? Demolish.BUILDING : Demolish.OK;
    }

    /** What a click on the hanger is. */
    public enum Hanger { SHIFT, SWAP, MANAGE, BUSY, NOTHING }

    /**
     * A plain click starts or ends a shift (or applies). A sneaking click: two working here swap places; the owner of a
     * closed store gets its menu (hiring, 철거: each a click in chat, never the hanger itself); the owner of an open one is
     * told to close first; anyone else nothing.
     */
    public static Hanger sneakHanger(boolean sneaking, boolean workingHere, boolean twoWorking, boolean owner, boolean open) {
        if (!sneaking) return Hanger.SHIFT;
        if (workingHere) return twoWorking ? Hanger.SWAP : owner ? Hanger.BUSY : Hanger.NOTHING;
        if (!owner) return Hanger.NOTHING;
        return open ? Hanger.BUSY : Hanger.MANAGE;
    }
}
