package dev.stsv.economy.shop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * contents/economy/shop.yml: what the admin shop buys (판매, dynamic price) and sells (구매, fixed price),
 * both by category, the 수요 고시, the 상인 행렬 goods. Pure data.
 * <p>
 * The menus show at most {@link #MAX_CATEGORIES} categories a side (one row). Older files without
 * categories still load: a flat {@code sell} map, a {@code buy} list and a {@code vanity} list each become
 * categories of their own.
 */
public record ShopCatalog(
        Map<String, SellEntry> sell,
        List<Category> sellCategories,
        List<BuyCategory> buyCategories,
        Merchant merchant,
        double floor,
        double dailyDecay,
        Curve curve,
        DemandNotice demand) {

    public static final int MAX_CATEGORIES = 7;

    /**
     * How fast one player's selling lowers their own price: Q = q x depth (how many players are on does not matter), and what
     * they sold S shrinks by hourlyRecovery every hour (besides daily-decay at the reset).
     */
    public record Curve(double depth, double hourlyRecovery) {
        public double quota(double q) {
            return q * depth;
        }
    }

    /** 수요 고시: every week on this weekday (at the reset hour), count items sell for multiplier x until the next one. */
    public record DemandNotice(java.time.DayOfWeek weekday, int count, double multiplier) {}

    public record SellEntry(String id, double base, double q, Integer cap, String job) {}

    /** A 판매 category: the items the shop buys, in order. */
    public record Category(String id, String name, String icon, List<String> items) {}

    /** Something the shop sells: a vanilla or custom item (kind "item", amount per purchase), a cosmetic or a title. */
    public record Good(String kind, String id, int amount, long price) {
        public boolean vanity() {
            return kind.equals("cosmetic") || kind.equals("title");
        }
    }

    public record BuyCategory(String id, String name, String icon, List<Good> goods) {}

    public record LimitedEntry(int index, String id, int amount, long price, int stock) {}

    /**
     * 상인 행렬: a 구매 category that is only there while the event runs. Besides its goods, each event
     * brings one of the rare entries (picked by the event, so everyone sees the same one).
     */
    public record Merchant(String name, String icon, List<LimitedEntry> goods, List<LimitedEntry> rare) {
        public Merchant(String name, String icon, List<LimitedEntry> goods) {
            this(name, icon, goods, List.of());
        }

        /** The rare entry of this event (null if there are none). */
        public LimitedEntry rareFor(String eventId) {
            return rare.isEmpty() ? null : rare.get(Math.floorMod(eventId.hashCode(), rare.size()));
        }

        /** What the event sells: the goods, then its rare entry. */
        public List<LimitedEntry> shown(String eventId) {
            LimitedEntry r = rareFor(eventId);
            if (r == null) return goods;
            List<LimitedEntry> out = new ArrayList<>(goods);
            out.add(r);
            return out;
        }
    }

    /**
     * The sell ids a /시세 search finds: by id ("diamond", "iron ingot", "economy:ruby"), by the item's own name (nameOf: custom
     * items read "루비"), or by its 판매 category's name ("광물"). The server has no Korean names for vanilla items (each
     * client translates them), so a vanilla item is found by its id or its category.
     */
    public List<String> search(String filter, java.util.function.Function<String, String> nameOf) {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        java.util.Set<String> inCategory = new java.util.HashSet<>();
        for (Category c : sellCategories) if (c.name().toLowerCase(Locale.ROOT).contains(f)) inCategory.addAll(c.items());
        List<String> out = new ArrayList<>();
        for (String id : sell.keySet()) {
            if (id.contains(f.replace(' ', '_')) || inCategory.contains(id) || nameOf.apply(id).toLowerCase(Locale.ROOT).contains(f)) out.add(id);
        }
        return out;
    }

    /** Every good of every 구매 category, in order. */
    public List<Good> goods() {
        List<Good> out = new ArrayList<>();
        for (BuyCategory c : buyCategories) out.addAll(c.goods());
        return out;
    }

    public static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static ShopCatalog parse(ConfigurationSection c, List<String> problems) {
        Map<String, SellEntry> sell = new LinkedHashMap<>();
        List<Category> sellCats = new ArrayList<>();
        ConfigurationSection s = c.getConfigurationSection("sell");
        if (s != null) {
            List<String> loose = new ArrayList<>();
            for (String key : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(key);
                if (e != null && e.isConfigurationSection("items")) {
                    List<String> ids = new ArrayList<>();
                    ConfigurationSection items = e.getConfigurationSection("items");
                    for (String raw : items.getKeys(false)) {
                        SellEntry x = sellEntry(items, raw, "sell." + key, problems);
                        if (x == null) continue;
                        if (sell.containsKey(x.id())) problems.add("sell." + key + "." + raw + ": 다른 카테고리에 이미 있음");
                        sell.put(x.id(), x);
                        ids.add(x.id());
                    }
                    sellCats.add(new Category(key, e.getString("name", key), norm(e.getString("icon", "chest")), ids));
                } else {
                    // an older file: items straight under sell
                    SellEntry x = sellEntry(s, key, "sell", problems);
                    if (x == null) continue;
                    sell.put(x.id(), x);
                    loose.add(x.id());
                }
            }
            if (!loose.isEmpty()) sellCats.add(new Category("etc", "기타", "minecraft:chest", loose));
        }

        List<BuyCategory> buyCats = new ArrayList<>();
        if (c.isConfigurationSection("buy")) {
            ConfigurationSection b = c.getConfigurationSection("buy");
            for (String key : b.getKeys(false)) {
                ConfigurationSection e = b.getConfigurationSection(key);
                if (e == null) {
                    problems.add("buy." + key + ": name, icon, items 필요");
                    continue;
                }
                List<Good> goods = new ArrayList<>();
                for (Map<?, ?> m : e.getMapList("items")) {
                    Good g = good(m, "buy." + key, problems);
                    if (g != null) goods.add(g);
                }
                buyCats.add(new BuyCategory(key, e.getString("name", key), norm(e.getString("icon", "chest")), goods));
            }
        } else {
            // an older file: one list of items, and the vanity list beside it
            List<Good> goods = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("buy")) {
                Good g = good(m, "buy", problems);
                if (g != null) goods.add(g);
            }
            if (!goods.isEmpty()) buyCats.add(new BuyCategory("goods", "물건", "minecraft:chest", goods));
            List<Good> cos = new ArrayList<>(), titles = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("vanity")) {
                Object kind = m.get("kind"), id = m.get("id"), price = m.get("price");
                if (kind == null || id == null || !(price instanceof Number p)) {
                    problems.add("vanity: kind(cosmetic|title), id, price 필요 (" + m + ")");
                    continue;
                }
                (String.valueOf(kind).equals("title") ? titles : cos).add(new Good(String.valueOf(kind).equals("title") ? "title" : "cosmetic",
                        String.valueOf(id), 1, p.longValue()));
            }
            if (!cos.isEmpty()) buyCats.add(new BuyCategory("cosmetics", "치장", cos.getFirst().id(), cos));
            if (!titles.isEmpty()) buyCats.add(new BuyCategory("titles", "칭호", "minecraft:name_tag", titles));
        }
        if (sellCats.size() > MAX_CATEGORIES) problems.add("sell: 카테고리는 " + MAX_CATEGORIES + "개까지 (" + sellCats.size() + "개)");
        if (buyCats.size() + 1 > MAX_CATEGORIES) problems.add("buy: 카테고리는 상인 행렬까지 " + MAX_CATEGORIES + "개까지 (" + (buyCats.size() + 1) + "개)");

        List<LimitedEntry> limited = new ArrayList<>();
        List<Map<?, ?>> raw = c.isConfigurationSection("merchant") ? c.getMapList("merchant.items") : c.getMapList("merchant");
        int i = 0;
        List<LimitedEntry> rare = new ArrayList<>();
        int r = 1000;   // their own stock counters (the index is part of the key)
        for (Map<?, ?> m : c.isConfigurationSection("merchant") ? c.getMapList("merchant.rare") : List.<Map<?, ?>>of()) {
            Object item = m.get("item"), price = m.get("price"), stock = m.get("stock");
            if (item == null || !(price instanceof Number p) || !(stock instanceof Number st)) {
                problems.add("merchant.rare: item, price, stock 필요 (" + m + ")");
                continue;
            }
            Object amount = m.get("amount");
            rare.add(new LimitedEntry(r++, norm(String.valueOf(item)), amount instanceof Number a ? Math.max(1, a.intValue()) : 1, p.longValue(), st.intValue()));
        }
        for (Map<?, ?> m : raw) {
            Object item = m.get("item"), price = m.get("price"), stock = m.get("stock");
            if (item == null || !(price instanceof Number p) || !(stock instanceof Number st)) {
                problems.add("merchant: item, price, stock 필요 (" + m + ")");
                continue;
            }
            Object amount = m.get("amount");
            limited.add(new LimitedEntry(i++, norm(String.valueOf(item)), amount instanceof Number a ? Math.max(1, a.intValue()) : 1, p.longValue(), st.intValue()));
        }
        Merchant merchant = new Merchant(c.getString("merchant.name", "상인 행렬"), norm(c.getString("merchant.icon", "emerald")), limited, rare);

        java.time.DayOfWeek weekday;
        try {
            weekday = java.time.DayOfWeek.valueOf(c.getString("demand-notice.weekday", "MONDAY").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("demand-notice.weekday: MONDAY ~ SUNDAY (" + c.getString("demand-notice.weekday") + ")");
            weekday = java.time.DayOfWeek.MONDAY;
        }
        DemandNotice demand = new DemandNotice(weekday, Math.max(0, c.getInt("demand-notice.count", 3)), c.getDouble("demand-notice.multiplier", 1.5));
        return new ShopCatalog(sell, sellCats, buyCats, merchant,
                c.getDouble("floor", 0.35), c.getDouble("daily-decay", 0.5),
                new Curve(Math.max(0.1, c.getDouble("depth", 1)), Math.clamp(c.getDouble("hourly-recovery", 0), 0, 0.9)),
                demand);
    }

    private static SellEntry sellEntry(ConfigurationSection parent, String raw, String where, List<String> problems) {
        ConfigurationSection e = parent.getConfigurationSection(raw);
        if (e == null || e.getDouble("base") <= 0 || e.getDouble("q") <= 0) {
            problems.add(where + "." + raw + ": base, q (0보다 큼) 필요");
            return null;
        }
        String id = norm(raw.replace('.', ':'));
        return new SellEntry(id, e.getDouble("base"), e.getDouble("q"), e.contains("cap") ? e.getInt("cap") : null, e.getString("job"));
    }

    /** { item: id, amount: n, price: p } or { cosmetic: id, price: p } or { title: id, price: p } */
    private static Good good(Map<?, ?> m, String where, List<String> problems) {
        Object price = m.get("price");
        String kind = m.containsKey("cosmetic") ? "cosmetic" : m.containsKey("title") ? "title" : "item";
        Object id = m.get(kind);
        if (id == null || !(price instanceof Number p) || p.longValue() <= 0) {
            problems.add(where + ": item(또는 cosmetic, title), price 필요 (" + m + ")");
            return null;
        }
        Object amount = m.get("amount");
        String key = kind.equals("item") ? norm(String.valueOf(id)) : String.valueOf(id).trim().toLowerCase(Locale.ROOT);
        return new Good(kind, key, amount instanceof Number a ? Math.max(1, a.intValue()) : 1, p.longValue());
    }
}
