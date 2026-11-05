package dev.stsv.economy.stock;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * contents/economy/stocks.yml: the market's rules, the six industries and the companies. Pure data and the
 * price maths (unit-tested): every move a company makes is computed here.
 */
public record StockSpec(Settings settings, Map<String, Industry> industries, Map<String, Company> companies) {

    public record Settings(int tickMinutes, double limit, double holdingCap, double fee, int sellWaitMinutes, double newsPerDay,
                           double activityCap, double activityScale, double reversionDays, double baseReversionDays,
                           double dividendShare, long minInvested, List<Double> alerts, double eventShock) {

        public int ticksPerDay() {
            return Math.max(1, 1440 / Math.max(1, tickMinutes));
        }

        /** Pull back towards the base value per tick: half the gap closes in reversionDays. */
        public double kappa() {
            return Math.log(2) / (reversionDays * ticksPerDay());
        }

        /** The base value's pull back towards the listing price per tick. */
        public double baseKappa() {
            return Math.log(2) / (baseReversionDays * ticksPerDay());
        }
    }

    public record Industry(String id, String name, String job, String icon, String color) {}

    /** A company as configured (or listed by an admin): price = listing price = starting base value. */
    public record Company(String id, String name, String industry, int risk, long shares, double price,
                          double volatility, double sensitivity, double dividend) {}

    public static StockSpec parse(ConfigurationSection c, List<String> problems) {
        ConfigurationSection s = c.getConfigurationSection("settings");
        if (s == null) s = c.createSection("settings");
        List<Double> alerts = s.getDoubleList("alerts");
        Settings settings = new Settings(Math.max(1, s.getInt("tick-minutes", 10)), s.getDouble("limit", 0.2), s.getDouble("holding-cap", 0.3),
                s.getDouble("fee", 0.01), s.getInt("sell-wait-minutes", 10), s.getDouble("news-per-day", 5), s.getDouble("activity-cap", 0.03),
                s.getDouble("activity-scale", 0.03), s.getDouble("reversion-days", 3), s.getDouble("base-reversion-days", 30),
                s.getDouble("dividend-share", 0.15), s.getLong("min-invested", 1000), alerts.isEmpty() ? List.of(0.1, 0.2) : alerts,
                s.getDouble("event-shock", 0.02));
        Map<String, Industry> industries = new LinkedHashMap<>();
        ConfigurationSection is = c.getConfigurationSection("industries");
        if (is != null) {
            for (String id : is.getKeys(false)) {
                ConfigurationSection i = is.getConfigurationSection(id);
                if (i == null) continue;
                industries.put(id, new Industry(id, i.getString("name", id), i.getString("job"), i.getString("icon", "minecraft:paper"),
                        i.getString("color", "white")));
            }
        }
        ConfigurationSection rs = c.getConfigurationSection("risk");
        Map<String, Company> companies = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("companies");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection x = cs.getConfigurationSection(id);
                if (x == null) continue;
                String ind = x.getString("industry");
                if (!industries.containsKey(ind)) {
                    problems.add("companies." + id + ": 없는 산업 " + ind);
                    continue;
                }
                int risk = Math.clamp(x.getInt("risk", 2), 1, 3);
                ConfigurationSection r = rs == null ? null : rs.getConfigurationSection(String.valueOf(risk));
                double price = x.getDouble("price", 0);
                if (price <= 0) {
                    problems.add("companies." + id + ": price 필요");
                    continue;
                }
                companies.put(id, new Company(id, x.getString("name", id), ind, risk,
                        x.getLong("shares", r == null ? 5000 : r.getLong("shares", 5000)), price,
                        x.getDouble("volatility", r == null ? 0.004 : r.getDouble("volatility", 0.004)),
                        x.getDouble("sensitivity", 1.0),
                        x.getDouble("dividend", r == null ? 0.5 : r.getDouble("dividend", 0.5))));
            }
        }
        if (companies.isEmpty()) problems.add("companies: 회사가 없음");
        return new StockSpec(settings, industries, companies);
    }

    // ------------------------------------------------------------------------------------------
    // Price maths
    // ------------------------------------------------------------------------------------------

    /**
     * One tick of a company's price: its own random move (z ~ N(0,1) times its volatility), the industry's
     * move times its sensitivity, and a pull back towards its base value.
     */
    public static double step(double price, double base, double volatility, double z, double industryMove, double sensitivity, double kappa) {
        double r = volatility * z + industryMove * sensitivity + kappa * Math.log(base / price);
        return price * Math.exp(r);
    }

    /** Inside the day's band: yesterday's close ± limit. */
    public static double clamp(double price, double prevClose, double limit) {
        return Math.clamp(price, prevClose * (1 - limit), prevClose * (1 + limit));
    }

    /** +1 at the upper limit (상한가), -1 at the lower (하한가), 0 between. */
    public static int atLimit(double price, double prevClose, double limit) {
        if (price >= prevClose * (1 + limit) - 1e-9) return 1;
        if (price <= prevClose * (1 - limit) + 1e-9) return -1;
        return 0;
    }

    /**
     * The industry's move for a day from server activity: how far yesterday was above or below the
     * 7-day average, scaled and capped. No history yet = no move.
     */
    public static double activityMove(double yesterday, double average, double scale, double cap) {
        if (average <= 0) return 0;
        return Math.clamp(scale * (yesterday / average - 1), -cap, cap);
    }

    /**
     * Dividend per share of every company: the pool goes to the industries by their activity (+1 so a
     * quiet industry still gets a little), then to the companies by dividend weight x market value.
     * Companies with weight 0 pay nothing.
     */
    public static Map<String, Double> dividends(double pool, Map<String, Double> industryActivity, Map<String, Company> companies,
                                                Map<String, Double> prices) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (pool <= 0) return out;
        Map<String, List<Company>> byIndustry = new LinkedHashMap<>();
        for (Company c : companies.values()) byIndustry.computeIfAbsent(c.industry(), k -> new ArrayList<>()).add(c);
        double totalActivity = 0;
        for (String ind : byIndustry.keySet()) totalActivity += industryActivity.getOrDefault(ind, 0.0) + 1;
        for (Map.Entry<String, List<Company>> e : byIndustry.entrySet()) {
            double share = pool * (industryActivity.getOrDefault(e.getKey(), 0.0) + 1) / totalActivity;
            double weights = 0;
            for (Company c : e.getValue()) weights += c.dividend() * c.shares() * prices.getOrDefault(c.id(), c.price());
            if (weights <= 0) continue;
            for (Company c : e.getValue()) {
                double w = c.dividend() * c.shares() * prices.getOrDefault(c.id(), c.price());
                if (w > 0) out.put(c.id(), share * w / weights / c.shares());
            }
        }
        return out;
    }

    /** ▁▂▃▄▅▆▇█ of a price series. */
    public static String sparkline(List<Double> values) {
        if (values.isEmpty()) return "";
        String bars = "▁▂▃▄▅▆▇█";
        double lo = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double hi = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            int i = hi - lo < 1e-9 ? 3 : (int) Math.round((v - lo) / (hi - lo) * 7);
            sb.append(bars.charAt(Math.clamp(i, 0, 7)));
        }
        return sb.toString();
    }

    /** The alert band of a holding: 0 within the first threshold, ±1, ±2... past each threshold. */
    public static int band(double value, double cost, List<Double> thresholds) {
        if (cost <= 0) return 0;
        double ch = value / cost - 1;
        int b = 0;
        for (int i = 0; i < thresholds.size(); i++) if (Math.abs(ch) >= thresholds.get(i) - 1e-9) b = i + 1;
        return ch < 0 ? -b : b;
    }
}
