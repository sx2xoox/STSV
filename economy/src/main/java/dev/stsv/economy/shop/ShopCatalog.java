package dev.stsv.economy.shop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * contents/economy/shop.yml: what the admin shop buys (매입, dynamic price) and sells (fixed price),
 * the 상인 행렬 goods, repair cost and the vanity goods (치장, 칭호). Pure data.
 */
public record ShopCatalog(
        Map<String, SellEntry> sell,
        List<BuyEntry> buy,
        List<LimitedEntry> merchant,
        double floor,
        double dailyDecay,
        double repairPerPoint,
        long repairMin,
        List<VanityEntry> vanity) {

    public record SellEntry(String id, double base, double q, Integer cap, String job) {}

    public record BuyEntry(String id, int amount, long price) {}

    public record LimitedEntry(int index, String id, int amount, long price, int stock) {}

    public record VanityEntry(String kind, String id, long price) {}

    public static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static ShopCatalog parse(ConfigurationSection c, List<String> problems) {
        Map<String, SellEntry> sell = new LinkedHashMap<>();
        ConfigurationSection s = c.getConfigurationSection("sell");
        if (s != null) {
            for (String raw : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(raw);
                if (e == null || e.getDouble("base") <= 0 || e.getDouble("q") <= 0) {
                    problems.add("sell." + raw + ": base, q (0보다 큼) 필요");
                    continue;
                }
                String id = norm(raw.replace('.', ':'));
                sell.put(id, new SellEntry(id, e.getDouble("base"), e.getDouble("q"), e.contains("cap") ? e.getInt("cap") : null, e.getString("job")));
            }
        }
        List<BuyEntry> buy = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("buy")) {
            Object item = m.get("item"), price = m.get("price");
            if (item == null || !(price instanceof Number p) || p.longValue() <= 0) {
                problems.add("buy: item, price 필요 (" + m + ")");
                continue;
            }
            Object amount = m.get("amount");
            buy.add(new BuyEntry(norm(String.valueOf(item)), amount instanceof Number a ? Math.max(1, a.intValue()) : 1, p.longValue()));
        }
        List<LimitedEntry> merchant = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> m : c.getMapList("merchant")) {
            Object item = m.get("item"), price = m.get("price"), stock = m.get("stock");
            if (item == null || !(price instanceof Number p) || !(stock instanceof Number st)) {
                problems.add("merchant: item, price, stock 필요 (" + m + ")");
                continue;
            }
            Object amount = m.get("amount");
            merchant.add(new LimitedEntry(i++, norm(String.valueOf(item)), amount instanceof Number a ? Math.max(1, a.intValue()) : 1, p.longValue(), st.intValue()));
        }
        List<VanityEntry> vanity = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("vanity")) {
            Object kind = m.get("kind"), id = m.get("id"), price = m.get("price");
            if (kind == null || id == null || !(price instanceof Number p)) {
                problems.add("vanity: kind(cosmetic|title), id, price 필요 (" + m + ")");
                continue;
            }
            vanity.add(new VanityEntry(String.valueOf(kind), String.valueOf(id), p.longValue()));
        }
        return new ShopCatalog(sell, buy, merchant,
                c.getDouble("floor", 0.35), c.getDouble("daily-decay", 0.5),
                c.getDouble("repair.per-point", 0.05), c.getLong("repair.min", 5), vanity);
    }
}
