package dev.stsv.economy.stock;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The market's memory: each company's state (price, base value, yesterday's close, today's range, what
 * an admin changed), who holds what (shares, cost, the part still locked after buying, the last alert),
 * 10-minute, hourly and daily candles, the news, the activity per industry per day and the week's investment
 * record for the 주식왕. Everything the market needs is in memory; writes go through the database queue.
 */
public final class StockStore {

    /** A listed company's live state. Personality fields hold the effective values. */
    public static final class Co {
        public final String id;
        public String name, industry;
        public int risk;
        public long shares;
        public double start, base, price, prevClose, dayHigh, dayLow, volatility, sensitivity, dividend;
        public boolean halted, delisted, listed;
        /** fields an admin changed (stocks.yml no longer sets them): "name,volatility,..." */
        public String overrides = "";
        /** today's limit already announced: +1 upper, -1 lower */
        public int limitShown;
        public long boughtToday;

        Co(String id) {
            this.id = id;
        }

        public boolean overridden(String field) {
            return ("," + overrides + ",").contains("," + field + ",");
        }

        public void override(String field) {
            if (!overridden(field)) overrides = overrides.isEmpty() ? field : overrides + "," + field;
        }

        public double dayChange() {
            return prevClose <= 0 ? 0 : (price / prevClose - 1) * 100;
        }
    }

    public static final class Hold {
        public final UUID player;
        public final String company;
        public long shares, locked, lockUntil;
        public double cost;
        public int band;

        Hold(UUID player, String company) {
            this.player = player;
            this.company = company;
        }

        public long sellable(long now) {
            return shares - (now < lockUntil ? Math.min(shares, locked) : 0);
        }
    }

    public record Candle(long t, double open, double high, double low, double close) {}

    public record News(long at, String company, String line) {}

    /** Investment record of a week: value of holdings when it started, money in and out since, money put in. */
    public static final class Week {
        public final UUID player;
        public int week;
        public double startValue, flow, invested;

        Week(UUID player) {
            this.player = player;
        }
    }

    private final Db db;
    private final Map<String, Co> companies = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Hold>> holds = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> tenMinute = new ConcurrentHashMap<>(), hourly = new ConcurrentHashMap<>(), daily = new ConcurrentHashMap<>();
    private final List<News> news = new ArrayList<>();
    private final Map<UUID, Week> weeks = new ConcurrentHashMap<>();

    public StockStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("eco_stock", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_stock_co (id VARCHAR(64) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, industry VARCHAR(32) NOT NULL, "
                        + "risk INT NOT NULL, shares BIGINT NOT NULL, start DOUBLE NOT NULL, base DOUBLE NOT NULL, price DOUBLE NOT NULL, "
                        + "prev_close DOUBLE NOT NULL, day_high DOUBLE NOT NULL, day_low DOUBLE NOT NULL, volatility DOUBLE NOT NULL, "
                        + "sensitivity DOUBLE NOT NULL, dividend DOUBLE NOT NULL, halted INT NOT NULL, delisted INT NOT NULL, listed INT NOT NULL, "
                        + "overrides VARCHAR(255) NOT NULL, limit_shown INT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_stock_hold (player CHAR(36) NOT NULL, company VARCHAR(64) NOT NULL, shares BIGINT NOT NULL, "
                        + "cost DOUBLE NOT NULL, locked BIGINT NOT NULL, lock_until BIGINT NOT NULL, band INT NOT NULL, PRIMARY KEY (player, company))",
                "CREATE TABLE IF NOT EXISTS eco_stock_candle (company VARCHAR(64) NOT NULL, kind CHAR(1) NOT NULL, t BIGINT NOT NULL, "
                        + "o DOUBLE NOT NULL, h DOUBLE NOT NULL, l DOUBLE NOT NULL, c DOUBLE NOT NULL, PRIMARY KEY (company, kind, t))",
                "CREATE TABLE IF NOT EXISTS eco_stock_news (at BIGINT NOT NULL, company VARCHAR(64) NOT NULL, line VARCHAR(512) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_stock_activity (day INT NOT NULL, industry VARCHAR(32) NOT NULL, value DOUBLE NOT NULL, "
                        + "PRIMARY KEY (day, industry))",
                "CREATE TABLE IF NOT EXISTS eco_stock_week (player CHAR(36) NOT NULL PRIMARY KEY, week INT NOT NULL, start_value DOUBLE NOT NULL, "
                        + "flow DOUBLE NOT NULL, invested DOUBLE NOT NULL)")));
        companies.clear();
        holds.clear();
        tenMinute.clear();
        hourly.clear();
        daily.clear();
        news.clear();
        weeks.clear();
        for (Co c : db.sync(x -> Db.query(x, "SELECT id, name, industry, risk, shares, start, base, price, prev_close, day_high, day_low, volatility, "
                + "sensitivity, dividend, halted, delisted, listed, overrides, limit_shown FROM eco_stock_co", rs -> {
            Co c2 = new Co(rs.getString(1));
            c2.name = rs.getString(2);
            c2.industry = rs.getString(3);
            c2.risk = rs.getInt(4);
            c2.shares = rs.getLong(5);
            c2.start = rs.getDouble(6);
            c2.base = rs.getDouble(7);
            c2.price = rs.getDouble(8);
            c2.prevClose = rs.getDouble(9);
            c2.dayHigh = rs.getDouble(10);
            c2.dayLow = rs.getDouble(11);
            c2.volatility = rs.getDouble(12);
            c2.sensitivity = rs.getDouble(13);
            c2.dividend = rs.getDouble(14);
            c2.halted = rs.getInt(15) != 0;
            c2.delisted = rs.getInt(16) != 0;
            c2.listed = rs.getInt(17) != 0;
            c2.overrides = rs.getString(18);
            c2.limitShown = rs.getInt(19);
            return c2;
        }))) {
            companies.put(c.id, c);
        }
        for (Hold h : db.sync(x -> Db.query(x, "SELECT player, company, shares, cost, locked, lock_until, band FROM eco_stock_hold", rs -> {
            Hold h2 = new Hold(UUID.fromString(rs.getString(1)), rs.getString(2));
            h2.shares = rs.getLong(3);
            h2.cost = rs.getDouble(4);
            h2.locked = rs.getLong(5);
            h2.lockUntil = rs.getLong(6);
            h2.band = rs.getInt(7);
            return h2;
        }))) {
            holds.computeIfAbsent(h.player, k -> new ConcurrentHashMap<>()).put(h.company, h);
        }
        for (Object[] r : db.sync(x -> Db.query(x, "SELECT company, kind, t, o, h, l, c FROM eco_stock_candle ORDER BY t",
                rs -> new Object[]{rs.getString(1), rs.getString(2), new Candle(rs.getLong(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7))}))) {
            Map<String, List<Candle>> kind = switch ((String) r[1]) {
                case "m" -> tenMinute;
                case "h" -> hourly;
                default -> daily;
            };
            kind.computeIfAbsent((String) r[0], k -> new ArrayList<>()).add((Candle) r[2]);
        }
        news.addAll(db.sync(x -> Db.query(x, "SELECT at, company, line FROM eco_stock_news ORDER BY at DESC LIMIT 50",
                rs -> new News(rs.getLong(1), rs.getString(2), rs.getString(3)))).reversed());
        for (Week w : db.sync(x -> Db.query(x, "SELECT player, week, start_value, flow, invested FROM eco_stock_week", rs -> {
            Week w2 = new Week(UUID.fromString(rs.getString(1)));
            w2.week = rs.getInt(2);
            w2.startValue = rs.getDouble(3);
            w2.flow = rs.getDouble(4);
            w2.invested = rs.getDouble(5);
            return w2;
        }))) {
            weeks.put(w.player, w);
        }
    }

    // ------------------------------------------------------------------------------------------ companies

    public Map<String, Co> companies() {
        return companies;
    }

    public Co company(String id) {
        return companies.get(id);
    }

    public Co create(String id) {
        return companies.computeIfAbsent(id, Co::new);
    }

    public void save(Co c) {
        db.execute(db.upsert("eco_stock_co", List.of("id"), List.of("name", "industry", "risk", "shares", "start", "base", "price", "prev_close",
                        "day_high", "day_low", "volatility", "sensitivity", "dividend", "halted", "delisted", "listed", "overrides", "limit_shown")),
                c.id, c.name, c.industry, c.risk, c.shares, c.start, c.base, c.price, c.prevClose, c.dayHigh, c.dayLow, c.volatility,
                c.sensitivity, c.dividend, c.halted ? 1 : 0, c.delisted ? 1 : 0, c.listed ? 1 : 0, c.overrides, c.limitShown);
    }

    // ------------------------------------------------------------------------------------------ holdings

    public Map<String, Hold> holdings(UUID player) {
        return holds.getOrDefault(player, Map.of());
    }

    public Hold hold(UUID player, String company) {
        return holds.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).computeIfAbsent(company, k -> new Hold(player, company));
    }

    /** Every holding of a company. */
    public List<Hold> holders(String company) {
        List<Hold> out = new ArrayList<>();
        for (Map<String, Hold> m : holds.values()) {
            Hold h = m.get(company);
            if (h != null && h.shares > 0) out.add(h);
        }
        return out;
    }

    /** Shares of a company held by everyone. */
    public long held(String company) {
        long n = 0;
        for (Hold h : holders(company)) n += h.shares;
        return n;
    }

    public void save(Hold h) {
        if (h.shares <= 0) {
            Map<String, Hold> m = holds.get(h.player);
            if (m != null) m.remove(h.company);
            db.execute("DELETE FROM eco_stock_hold WHERE player = ? AND company = ?", h.player.toString(), h.company);
            return;
        }
        db.execute(db.upsert("eco_stock_hold", List.of("player", "company"), List.of("shares", "cost", "locked", "lock_until", "band")),
                h.player.toString(), h.company, h.shares, h.cost, h.locked, h.lockUntil, h.band);
    }

    public java.util.Set<UUID> investors() {
        return holds.keySet();
    }

    // ------------------------------------------------------------------------------------------ candles

    /** 10-minute candles: one price move each, so each opens at the one before's close. */
    public List<Candle> tenMinute(String company) {
        return tenMinute.getOrDefault(company, List.of());
    }

    public List<Candle> hourly(String company) {
        return hourly.getOrDefault(company, List.of());
    }

    public List<Candle> daily(String company) {
        return daily.getOrDefault(company, List.of());
    }

    /**
     * Adds a price to the candle of kind 'm' (10 minutes), 'h' (hour) or 'd' (day) starting at t (opens it
     * if new), keeping at most `keep` candles. A new 10-minute candle opens at the previous one's close when
     * that one is the period just before (`span` ms earlier): the price moves once a tick, so a candle opened
     * at its own price would be flat.
     */
    public void candle(String company, char kind, long t, double price, int keep, long span) {
        List<Candle> l = (kind == 'm' ? tenMinute : kind == 'h' ? hourly : daily).computeIfAbsent(company, k -> new ArrayList<>());
        Candle c;
        if (!l.isEmpty() && l.getLast().t() == t) {
            Candle last = l.removeLast();
            c = new Candle(t, last.open(), Math.max(last.high(), price), Math.min(last.low(), price), price);
        } else if (kind == 'm' && !l.isEmpty() && l.getLast().t() == t - span) {
            double open = l.getLast().close();
            c = new Candle(t, open, Math.max(open, price), Math.min(open, price), price);
        } else {
            c = new Candle(t, price, price, price, price);
        }
        l.add(c);
        while (l.size() > keep) {
            Candle old = l.removeFirst();
            db.execute("DELETE FROM eco_stock_candle WHERE company = ? AND kind = ? AND t = ?", company, String.valueOf(kind), old.t());
        }
        db.execute(db.upsert("eco_stock_candle", List.of("company", "kind", "t"), List.of("o", "h", "l", "c")),
                company, String.valueOf(kind), t, c.open(), c.high(), c.low(), c.close());
    }

    // ------------------------------------------------------------------------------------------ news

    public List<News> news() {
        return news;
    }

    public void addNews(long at, String company, String line) {
        news.add(new News(at, company, line));
        while (news.size() > 50) news.removeFirst();
        db.execute("INSERT INTO eco_stock_news (at, company, line) VALUES (?, ?, ?)", at, company, line);
    }

    // ------------------------------------------------------------------------------------------ activity

    public void addActivity(int day, String industry, double value) {
        db.execute(db.upsert("eco_stock_activity", List.of("day", "industry"), List.of("value")), day, industry,
                activity(day).getOrDefault(industry, 0.0) + value);
        activityCache.computeIfAbsent(day, k -> new HashMap<>()).merge(industry, value, Double::sum);
    }

    private final Map<Integer, Map<String, Double>> activityCache = new ConcurrentHashMap<>();

    /** Counted activity of a day per industry. */
    public Map<String, Double> activity(int day) {
        return activityCache.computeIfAbsent(day, d -> {
            Map<String, Double> m = new HashMap<>();
            for (Object[] r : db.sync(x -> Db.query(x, "SELECT industry, value FROM eco_stock_activity WHERE day = ?",
                    rs -> new Object[]{rs.getString(1), rs.getDouble(2)}, d))) {
                m.put((String) r[0], (Double) r[1]);
            }
            return m;
        });
    }

    // ------------------------------------------------------------------------------------------ weeks

    public Week week(UUID player, int week) {
        Week w = weeks.computeIfAbsent(player, Week::new);
        if (w.week != week) {
            w.week = week;
            w.startValue = 0;
            w.flow = 0;
            w.invested = 0;
        }
        return w;
    }

    public Map<UUID, Week> weeks() {
        return weeks;
    }

    public void save(Week w) {
        db.execute(db.upsert("eco_stock_week", List.of("player"), List.of("week", "start_value", "flow", "invested")),
                w.player.toString(), w.week, w.startValue, w.flow, w.invested);
    }

    public void reset() {
        companies.clear();
        holds.clear();
        tenMinute.clear();
        hourly.clear();
        daily.clear();
        news.clear();
        weeks.clear();
        activityCache.clear();
        for (String t : List.of("eco_stock_co", "eco_stock_hold", "eco_stock_candle", "eco_stock_news", "eco_stock_activity", "eco_stock_week")) {
            db.execute("DELETE FROM " + t);
        }
    }
}
