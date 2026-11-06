package dev.stsv.economy.stock;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.calendar.WeeklyListener;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.event.EventSpec;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.shop.ShopCatalog;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 주식: 42 make-believe companies in six industries. Prices move every 10 minutes (own wobble, the
 * industry, a pull back to the company's value, now and then company news), ±20% a day at most. Server
 * activity moves the industries once a day, without the activity of people who hold shares in them (so no
 * one can push their own stock), and events shake them (품귀, 폭락, 풍년, 광산 호황, 실적 발표).
 * Buy and sell by 1 / 10 / 100 shares in /주식, sell 10 minutes after buying, 1% fee (the first buy is
 * free), 30% of a company at most. Dividends every Monday from part of the money destroyed that week;
 * the week's best three investors get the 주식왕 titles. The window draws a candle chart in its title.
 */
public final class StockModule extends EcoModule implements DailyListener, WeeklyListener {

    private StockSpec spec;
    private NewsSpec news;
    private StockStore store;
    private final NewsSpec.Writer writer = new NewsSpec.Writer(30);
    private final Random rnd = new Random();
    /** industry -> today's move from activity (a fraction for the whole day) */
    private final Map<String, Double> drift = new HashMap<>();
    private TextDisplay board;

    public StockModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-stocks";
    }

    @Override
    protected void start() {
        store = new StockStore(core.db());
        store.init();
        load();
        for (String ind : spec.industries().keySet()) drift.put(ind, Double.parseDouble(eco.kv().get("stock.drift." + ind, "0")));
        every(spec.settings().tickMinutes() * 1200L, this::tick);
        every(1200L, this::updateBoard);
        eco.calendar().onDaily(this);
        eco.calendar().onWeekly(this);
        double shock = spec.settings().eventShock();
        eco.events().onStart("shortage", e -> shock(industryOf(e.param("item")), shock, e));
        eco.events().onStart("crash", e -> shock(industryOf(e.param("item")), -shock, e));
        eco.events().onStart("harvest", e -> shockJobs(e, shock));
        eco.events().onStart("mining_boom", e -> shockJobs(e, shock));
        eco.events().onStart("earnings", e -> earnings());
        eco.onReset(() -> {
            store.reset();
            drift.clear();
            load();
        });
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        if (board != null) board.remove();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = StockSpec.parse(eco.data("stocks.yml"), problems);
        news = NewsSpec.parse(eco.data("news.yml"), problems);
        problems.forEach(p -> eco.getLogger().warning("[stocks.yml/news.yml] " + p));
        for (StockSpec.Company c : spec.companies().values()) {
            StockStore.Co co = store.create(c.id());
            boolean fresh = co.name == null;
            if (fresh) {
                co.start = co.base = co.price = co.prevClose = co.dayHigh = co.dayLow = c.price();
            }
            if (fresh || !co.overridden("name")) co.name = c.name();
            co.industry = c.industry();
            co.risk = c.risk();
            co.shares = c.shares();
            if (fresh || !co.overridden("volatility")) co.volatility = c.volatility();
            if (fresh || !co.overridden("sensitivity")) co.sensitivity = c.sensitivity();
            if (fresh || !co.overridden("dividend")) co.dividend = c.dividend();
            if (!co.overridden("start")) co.start = c.price();
            store.save(co);
        }
        // a company taken out of stocks.yml (not one an admin listed) is delisted: holders get the price
        for (StockStore.Co co : List.copyOf(store.companies().values())) {
            if (!co.delisted && !co.listed && !spec.companies().containsKey(co.id)) delist(co);
        }
    }

    public StockSpec spec() {
        return spec;
    }

    public StockStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------------------------------

    public List<StockStore.Co> listed() {
        List<StockStore.Co> out = new ArrayList<>();
        for (StockStore.Co c : store.companies().values()) if (!c.delisted) out.add(c);
        out.sort(Comparator.comparingInt((StockStore.Co c) -> industryOrder(c.industry)).thenComparingInt(c -> c.risk).thenComparing(c -> c.id));
        return out;
    }

    private int industryOrder(String id) {
        int i = new ArrayList<>(spec.industries().keySet()).indexOf(id);
        return i < 0 ? 99 : i;
    }

    /** A company by id or Korean name (spaces ignored). */
    public StockStore.Co find(String word) {
        if (word == null) return null;
        String w = word.replace(" ", "");
        for (StockStore.Co c : store.companies().values()) {
            if (!c.delisted && (c.id.equalsIgnoreCase(w) || c.name.replace(" ", "").equals(w))) return c;
        }
        return null;
    }

    /** The industry of a thing sold: by the shop's job of a gathered item, or the job of the machine that makes it. */
    public String industryOf(String item) {
        if (item == null) return null;
        String job = null;
        ShopCatalog.SellEntry e = eco.shop().catalog().sell().get(item);
        if (e != null && e.job() != null) job = e.job();
        if (job == null) {
            String machine = eco.machines().madeOn(item);
            JobSpec.Job j = machine == null ? null : eco.jobs().spec().ofMachine(machine);
            job = j == null ? null : j.id();
        }
        if (job == null) return null;
        for (StockSpec.Industry i : spec.industries().values()) if (job.equals(i.job())) return i.id();
        return null;
    }

    private boolean holdsIndustry(UUID player, String industry) {
        for (StockStore.Hold h : store.holdings(player).values()) {
            StockStore.Co c = store.company(h.company);
            if (c != null && h.shares > 0 && industry.equals(c.industry)) return true;
        }
        return false;
    }

    /** Shop sales and request deliveries count for their industry, unless the seller holds shares in it. */
    public void recordSale(UUID seller, String item, double value) {
        String ind = industryOf(item);
        if (ind == null || value <= 0 || holdsIndustry(seller, ind)) return;
        store.addActivity(eco.calendar().day(), ind, value);
    }

    public double value(UUID player) {
        double v = 0;
        for (StockStore.Hold h : store.holdings(player).values()) {
            StockStore.Co c = store.company(h.company);
            if (c != null) v += h.shares * c.price;
        }
        return v;
    }

    private static String stars(int risk) {
        return "★".repeat(risk) + "☆".repeat(3 - risk);
    }

    static String money(double v) {
        return EconomyModule.format(Math.round(v));
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f", Math.abs(v));
    }

    private String changeText(double pctValue) {
        if (Math.abs(pctValue) < 0.05) return Lang.raw("stock.flat");
        return Lang.raw(pctValue > 0 ? "stock.up" : "stock.down", "pct", pct(pctValue));
    }

    private StockSpec.Industry industry(StockStore.Co c) {
        return spec.industries().get(c.industry);
    }

    // ------------------------------------------------------------------------------------------
    // Price moves
    // ------------------------------------------------------------------------------------------

    private static final long TEN_MINUTES = 600_000L;

    private long hourStart() {
        return now() / 3_600_000L * 3_600_000L;
    }

    private void record(StockStore.Co c) {
        c.dayHigh = Math.max(c.dayHigh, c.price);
        c.dayLow = Math.min(c.dayLow, c.price);
        store.candle(c.id, 'm', now() / TEN_MINUTES * TEN_MINUTES, c.price, 32, TEN_MINUTES);
        store.candle(c.id, 'h', hourStart(), c.price, 48, 0);
        store.candle(c.id, 'd', eco.calendar().day(), c.price, 60, 0);
        int at = StockSpec.atLimit(c.price, c.prevClose, spec.settings().limit());
        if (at != 0 && c.limitShown != at) {
            c.limitShown = at;
            Bukkit.broadcast(Lang.msg(at > 0 ? "stock.limit-up" : "stock.limit-down", "company", c.name, "price", money(c.price)));
        }
        store.save(c);
    }

    /** Moves a company by a factor now (news, events, admin); part of it can stick to its value. */
    private double jump(StockStore.Co c, double factor, double baseShare) {
        double before = c.price;
        c.price = StockSpec.clamp(c.price * factor, c.prevClose, spec.settings().limit());
        c.base *= Math.pow(factor, baseShare);
        record(c);
        return (c.price / before - 1) * 100;
    }

    private void tick() {
        StockSpec.Settings s = spec.settings();
        int perDay = s.ticksPerDay();
        for (StockStore.Co c : listed()) {
            if (c.halted) continue;
            double ind = drift.getOrDefault(c.industry, 0.0) / perDay;
            c.base *= Math.exp(ind * c.sensitivity + s.baseKappa() * Math.log(c.start / c.base));
            double p = StockSpec.step(c.price, c.base, c.volatility, rnd.nextGaussian(), ind, c.sensitivity, s.kappa());
            c.price = StockSpec.clamp(p, c.prevClose, s.limit());
            record(c);
        }
        if (rnd.nextDouble() < s.newsPerDay() / perDay) {
            List<StockStore.Co> l = listed().stream().filter(c -> !c.halted).toList();
            if (!l.isEmpty()) news(l.get(rnd.nextInt(l.size())), null, null);
        }
        alerts();
    }

    /** A company news: ±5~10% (or the given move), a line in chat and in /주식 뉴스. */
    public void news(StockStore.Co c, Double pctWanted, String cause) {
        double want = pctWanted != null ? pctWanted : (rnd.nextBoolean() ? 1 : -1) * (5 + rnd.nextDouble() * 5);
        double moved = jump(c, 1 + want / 100, 0.3);
        String line = writer.line(news, c.id, c.name, c.industry, moved, cause, rnd);
        store.addNews(now(), c.id, line);
        Bukkit.broadcast(Lang.msg("stock.news", "line", line));
        alerts();
    }

    /** 실적 발표: one industry, every company moves on its own by 5~15% up or down. */
    private void earnings() {
        List<String> inds = new ArrayList<>(spec.industries().keySet());
        if (inds.isEmpty()) return;
        String ind = inds.get(rnd.nextInt(inds.size()));
        StockSpec.Industry i = spec.industries().get(ind);
        Bukkit.broadcast(Lang.msg("stock.earnings", "color", i.color(), "industry", i.name()));
        for (StockStore.Co c : listed()) {
            if (!c.industry.equals(ind) || c.halted) continue;
            double want = (rnd.nextBoolean() ? 1 : -1) * (5 + rnd.nextDouble() * 10);
            double moved = jump(c, 1 + want / 100, 0.5);
            String line = writer.line(news, c.id, c.name, c.industry, moved, null, rnd);
            store.addNews(now(), c.id, line);
            Bukkit.broadcast(Lang.mm("stock.earnings-line", "line", line));
        }
        alerts();
    }

    /** An event moves an industry: every company by the shock times its sensitivity, half of it lasting. */
    private void shock(String industry, double amount, ActiveEvent e) {
        if (industry == null) return;
        for (StockStore.Co c : listed()) {
            if (c.industry.equals(industry) && !c.halted) jump(c, Math.exp(amount * c.sensitivity), 0.5);
        }
        StockSpec.Industry i = spec.industries().get(industry);
        if (i != null) Bukkit.broadcast(Lang.msg(amount > 0 ? "stock.event-up" : "stock.event-down", "color", i.color(), "industry", i.name(),
                "event", eco.events().name(e.type())));
        alerts();
    }

    private void shockJobs(ActiveEvent e, double amount) {
        for (String job : EventSpec.list(e.param("jobs"))) {
            for (StockSpec.Industry i : spec.industries().values()) if (job.equals(i.job())) shock(i.id(), amount, e);
        }
    }

    private void alerts() {
        List<Double> thresholds = spec.settings().alerts();
        for (UUID u : List.copyOf(store.investors())) {
            for (StockStore.Hold h : store.holdings(u).values()) {
                StockStore.Co c = store.company(h.company);
                if (c == null || h.shares <= 0) continue;
                int band = StockSpec.band(h.shares * c.price, h.cost, thresholds);
                if (band == h.band) continue;
                boolean further = Math.abs(band) > Math.abs(h.band);
                h.band = band;
                store.save(h);
                Player p = Bukkit.getPlayer(u);
                if (p == null || !further || eco.kv().has("stock.mute." + u)) continue;
                double ch = (h.shares * c.price / h.cost - 1) * 100;
                p.sendMessage(Lang.msg(ch >= 0 ? "stock.alert-up" : "stock.alert-down", "company", c.name, "pct", pct(ch),
                        "value", money(h.shares * c.price), "profit", money(Math.abs(h.shares * c.price - h.cost))));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Days and weeks
    // ------------------------------------------------------------------------------------------

    @Override
    public void newDay(int day, long dayStart) {
        for (StockStore.Co c : store.companies().values()) {
            c.prevClose = c.price;
            c.dayHigh = c.dayLow = c.price;
            c.limitShown = 0;
            c.boughtToday = 0;
            store.save(c);
        }
        StockSpec.Settings s = spec.settings();
        Map<String, Double> yesterday = store.activity(day - 1);
        for (String ind : spec.industries().keySet()) {
            double sum = 0;
            int n = 0;
            for (int d = day - 8; d <= day - 2; d++) {
                if (d < 1) continue;
                sum += store.activity(d).getOrDefault(ind, 0.0);
                n++;
            }
            double move = n == 0 ? 0 : StockSpec.activityMove(yesterday.getOrDefault(ind, 0.0), sum / n, s.activityScale(), s.activityCap());
            drift.put(ind, move);
            eco.kv().set("stock.drift." + ind, move);
        }
    }

    @Override
    public void newWeek(int week, long weekStart) {
        // 주식왕: the week that ended, among people who put at least min-invested in
        List<Map.Entry<UUID, Double>> profits = new ArrayList<>();
        for (StockStore.Week w : store.weeks().values()) {
            if (w.week != week - 1 || w.invested < spec.settings().minInvested()) continue;
            profits.add(Map.entry(w.player, value(w.player) + w.flow - w.startValue));
        }
        profits.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        for (int i = 0; i < Math.min(3, profits.size()); i++) {
            Map.Entry<UUID, Double> e = profits.get(i);
            if (e.getValue() <= 0) break;
            core.profiles().grantTitle(e.getKey(), "economy:stock_king_" + (i + 1));
            Bukkit.broadcast(Lang.msg("stock.king", "rank", i + 1, "player", Players.name(e.getKey()), "profit", money(e.getValue())));
        }
        // the new week starts from what everyone holds now
        for (UUID u : List.copyOf(store.investors())) {
            StockStore.Week w = store.week(u, week);
            w.startValue = value(u);
            w.invested = w.startValue;
            store.save(w);
        }
        // dividends: part of the money destroyed last week goes back to shareholders
        core.economy().store().stats(weekStart - 7L * 86_400_000L).thenAccept(st -> sync(() -> payDividends(st.sink() * spec.settings().dividendShare())));
    }

    private void payDividends(double pool) {
        int day = eco.calendar().day();
        Map<String, Double> activity = new HashMap<>();
        for (int d = day - 7; d < day; d++) {
            if (d < 1) continue;
            store.activity(d).forEach((k, v) -> activity.merge(k, v, Double::sum));
        }
        Map<String, StockSpec.Company> companies = new LinkedHashMap<>();
        Map<String, Double> prices = new HashMap<>();
        for (StockStore.Co c : listed()) {
            companies.put(c.id, new StockSpec.Company(c.id, c.name, c.industry, c.risk, c.shares, c.start, c.volatility, c.sensitivity, c.dividend));
            prices.put(c.id, c.price);
        }
        Map<String, Double> perShare = StockSpec.dividends(pool, activity, companies, prices);
        // remembered for the windows: "지난번 1주당 ..."
        for (StockStore.Co c : listed()) if (c.dividend > 0) eco.kv().set("stock.div." + c.id, perShare.getOrDefault(c.id, 0.0));
        long total = 0;
        for (UUID u : List.copyOf(store.investors())) {
            double amount = 0;
            for (StockStore.Hold h : store.holdings(u).values()) amount += h.shares * perShare.getOrDefault(h.company, 0.0);
            long paid = (long) Math.floor(amount);
            if (paid <= 0) continue;
            core.economy().move(null, u, paid, EconomyStore.Kind.FAUCET, "stock_dividend", null);
            total += paid;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(Lang.msg("stock.dividend-you", "amount", money(paid)));
        }
        if (total > 0) Bukkit.broadcast(Lang.msg("stock.dividend", "amount", money(total)));
    }

    // ------------------------------------------------------------------------------------------
    // Buying and selling
    // ------------------------------------------------------------------------------------------

    private boolean feeFree() {
        return eco.events().isActive("fee_free");
    }

    /** Returns null on success, else the message key of the refusal; params get filled for the message. */
    public String buy(Player p, StockStore.Co c, long qty, Map<String, Object> params) {
        if (c == null || c.delisted) return "stock.no-such";
        if (c.halted) return "stock.halted";
        if (qty <= 0) return "stock.bad-amount";
        UUID u = p.getUniqueId();
        StockStore.Hold h = store.hold(u, c.id);
        long cap = (long) Math.floor(c.shares * spec.settings().holdingCap());
        if (h.shares + qty > cap) {
            params.put("max", Math.max(0, cap - h.shares));
            return "stock.cap";
        }
        long free = c.shares - store.held(c.id);
        if (qty > free) {
            params.put("max", Math.max(0, free));
            return "stock.sold-out";
        }
        double cost = c.price * qty;
        boolean first = !eco.kv().has("stock.first." + u);
        long fee = first || feeFree() ? 0 : (long) Math.ceil(cost * spec.settings().fee());
        long total = (long) Math.ceil(cost) + fee;
        params.put("cost", money(total));
        if (!core.economy().withdraw(u, total, "stock_buy")) return "stock.not-enough";
        long nowMs = now();
        h.shares += qty;
        h.cost += cost;
        h.locked = nowMs < h.lockUntil ? h.locked + qty : qty;
        h.lockUntil = nowMs + spec.settings().sellWaitMinutes() * 60_000L;
        h.band = StockSpec.band(h.shares * c.price, h.cost, spec.settings().alerts());
        store.save(h);
        c.boughtToday += qty;
        StockStore.Week w = store.week(u, eco.calendar().week());
        w.flow -= total;
        w.invested += total;
        store.save(w);
        if (first) {
            eco.kv().set("stock.first." + u, 1);
            params.put("first", true);
        }
        params.put("fee", money(fee));
        return null;
    }

    public String sell(Player p, StockStore.Co c, long qty, Map<String, Object> params) {
        if (c == null) return "stock.no-such";
        if (c.halted) return "stock.halted";
        UUID u = p.getUniqueId();
        StockStore.Hold h = store.holdings(u).get(c.id);
        if (h == null || h.shares <= 0) return "stock.none";
        long nowMs = now();
        long sellable = h.sellable(nowMs);
        if (sellable <= 0) {
            params.put("left", Math.max(1, (h.lockUntil - nowMs + 59_999) / 60_000));
            return "stock.locked";
        }
        qty = Math.min(qty, sellable);
        if (qty <= 0) return "stock.bad-amount";
        double gross = c.price * qty;
        long fee = feeFree() ? 0 : (long) Math.ceil(gross * spec.settings().fee());
        long net = Math.max(0, (long) Math.floor(gross) - fee);
        double basis = h.cost * qty / h.shares;
        core.economy().move(null, u, net, EconomyStore.Kind.FAUCET, "stock_sell", c.name + " x" + qty);
        h.cost -= basis;
        h.shares -= qty;
        h.locked = Math.min(h.locked, h.shares);
        store.save(h);
        StockStore.Week w = store.week(u, eco.calendar().week());
        w.flow += net;
        store.save(w);
        params.put("count", qty);
        params.put("amount", money(net));
        params.put("fee", money(fee));
        params.put("profit", money(Math.abs(net - basis)));
        params.put("sign", net - basis >= 0 ? "+" : "-");
        return null;
    }

    private void trade(Player p, StockStore.Co c, boolean buying, long qty) {
        Map<String, Object> params = new HashMap<>();
        String refused = buying ? buy(p, c, qty, params) : sell(p, c, qty, params);
        params.put("company", c == null ? "-" : c.name);
        params.put("count", params.getOrDefault("count", qty));
        List<Object> flat = new ArrayList<>();
        params.forEach((k, v) -> {
            flat.add(k);
            flat.add(v);
        });
        if (refused != null) {
            HudModule.flash(p, Lang.mm(refused, flat.toArray()));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            return;
        }
        p.sendMessage(Lang.msg(buying ? (params.containsKey("first") ? "stock.bought-first" : "stock.bought") : "stock.sold", flat.toArray()));
        p.playSound(p.getLocation(), buying ? Sound.BLOCK_NOTE_BLOCK_CHIME : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
    }

    // ------------------------------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------------------------------

    public String list(String name, String industry, long shares, double price) {
        if (!spec.industries().containsKey(industry)) {
            for (StockSpec.Industry i : spec.industries().values()) if (i.name().equals(industry)) industry = i.id();
        }
        if (!spec.industries().containsKey(industry)) return "없는 산업: " + industry;
        if (find(name) != null) return "이미 있는 회사: " + name;
        String id = "c" + Long.toString(now(), 36);
        StockStore.Co c = store.create(id);
        c.name = name;
        c.industry = industry;
        c.risk = price >= 1000 ? 1 : price >= 250 ? 2 : 3;
        c.shares = shares;
        c.start = c.base = c.price = c.prevClose = c.dayHigh = c.dayLow = price;
        c.volatility = c.risk == 1 ? 0.0017 : c.risk == 2 ? 0.004 : 0.0085;
        c.sensitivity = 1;
        c.dividend = c.risk == 1 ? 1 : c.risk == 2 ? 0.5 : 0;
        c.listed = true;
        record(c);
        Bukkit.broadcast(Lang.msg("stock.listed", "company", name, "industry", spec.industries().get(industry).name(), "price", money(price)));
        return null;
    }

    /** Delists a company: every holder gets the current price for their shares. */
    public void delist(StockStore.Co c) {
        for (StockStore.Hold h : store.holders(c.id)) {
            long pay = (long) Math.floor(h.shares * c.price);
            if (pay > 0) core.economy().move(null, h.player, pay, EconomyStore.Kind.FAUCET, "stock_delist", c.name);
            h.shares = 0;
            store.save(h);
        }
        c.delisted = true;
        store.save(c);
        if (c.name != null) Bukkit.broadcast(Lang.msg("stock.delisted", "company", c.name, "price", money(c.price)));
    }

    public void setPrice(StockStore.Co c, double price) {
        c.price = Math.max(1, price);
        c.base = c.price;
        c.dayHigh = Math.max(c.dayHigh, c.price);
        c.dayLow = Math.min(c.dayLow, c.price);
        record(c);
        alerts();
    }

    public void halt(StockStore.Co c, boolean on) {
        c.halted = on;
        store.save(c);
        Bukkit.broadcast(Lang.msg(on ? "stock.halted-notice" : "stock.resumed-notice", "company", c.name));
    }

    /** "volatility" (변동성), "sensitivity" (민감도), "dividend" (배당). */
    public boolean personality(StockStore.Co c, String field, double v) {
        switch (field) {
            case "volatility" -> c.volatility = Math.max(0, v);
            case "sensitivity" -> c.sensitivity = v;
            case "dividend" -> c.dividend = Math.max(0, v);
            default -> {
                return false;
            }
        }
        c.override(field);
        store.save(c);
        return true;
    }

    /** Admin: n price moves now (one is 10 minutes of the game's time); returns the biggest movers, as text. */
    public List<String> forceTicks(int n) {
        Map<String, Double> before = new HashMap<>();
        for (StockStore.Co c : listed()) before.put(c.id, c.price);
        for (int i = 0; i < Math.max(1, n); i++) tick();
        List<StockStore.Co> l = new ArrayList<>(listed());
        l.sort(Comparator.comparingDouble((StockStore.Co c) -> c.price / before.getOrDefault(c.id, c.price)));
        List<String> out = new ArrayList<>();
        for (StockStore.Co c : List.of(l.getLast(), l.getFirst())) {
            double b = before.getOrDefault(c.id, c.price);
            out.add(c.name + " " + money(b) + " → " + money(c.price) + " " + changeText((c.price / b - 1) * 100));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Chat: /주식 <회사>, /주식 뉴스
    // ------------------------------------------------------------------------------------------

    public void quote(Player p, StockStore.Co c) {
        StockSpec.Industry i = industry(c);
        double[] range = range24(c);
        p.sendMessage(Lang.msg("stock.quote", "company", c.name, "color", i == null ? "white" : i.color(), "industry", i == null ? "-" : i.name(),
                "stars", stars(c.risk), "price", money(c.price), "change", changeText(c.dayChange()), "low", money(range[0]), "high", money(range[1])));
        StockStore.Hold h = store.holdings(p.getUniqueId()).get(c.id);
        if (h != null && h.shares > 0) p.sendMessage(Lang.msg("stock.quote-mine", mine(h, c)));
    }

    /** The lowest and highest price of the last 24 hours, from the same hourly candles the 24-hour chart draws. */
    private double[] range24(StockStore.Co c) {
        List<StockStore.Candle> l = store.hourly(c.id);
        if (l.size() > 24) l = l.subList(l.size() - 24, l.size());
        double lo = c.price, hi = c.price;
        for (StockStore.Candle k : l) {
            lo = Math.min(lo, k.low());
            hi = Math.max(hi, k.high());
        }
        return new double[]{lo, hi};
    }

    /** Placeholders of a holding: count, value, profit with its sign, colour (Korean: gain red, loss blue), percent. */
    private Object[] mine(StockStore.Hold h, StockStore.Co c) {
        double v = h.shares * c.price, gain = v - h.cost, pct = h.cost <= 0 ? 0 : (v / h.cost - 1) * 100;
        boolean flat = Math.abs(pct) < 0.05 && Math.abs(gain) < 0.5;
        String colour = flat ? "gray" : gain > 0 ? "#ff5f57" : "#4d9bff";
        String sign = flat ? "" : gain > 0 ? "+" : "-";
        return new Object[]{"count", String.format("%,d", h.shares), "value", money(v), "color", colour,
                "profit", sign + money(Math.abs(gain)), "pct", sign + pct(pct)};
    }

    /** "+9,102 G (+0.3%)" in red, a loss in blue, nothing in grey. */
    private String plText(double value, double cost) {
        double gain = value - cost, pct = cost <= 0 ? 0 : (value / cost - 1) * 100;
        boolean flat = Math.abs(pct) < 0.05 && Math.abs(gain) < 0.5;
        String sign = flat ? "" : gain > 0 ? "+" : "-";
        return "<" + (flat ? "gray" : gain > 0 ? "#ff5f57" : "#4d9bff") + ">" + sign + money(Math.abs(gain)) + " (" + sign + pct(pct) + "%)";
    }

    /** The dividend per share paid last Monday, or null before the first one. */
    private Double lastDividend(StockStore.Co c) {
        String v = eco.kv().get("stock.div." + c.id);
        try {
            return v == null ? null : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A small amount with cents (dividends per share are often under 1 G). */
    private static String amountFine(double v) {
        return v >= 100 ? money(v) : Lang.raw("stock.amount-fine", "amount", String.format(Locale.ROOT, "%,.2f", v));
    }

    /** When and how much: every Monday 05:00, last time per share (or none, or not yet). */
    private String dividendLine(StockStore.Co c) {
        if (c.dividend <= 0) return Lang.raw("stock.dividend-no");
        Double per = lastDividend(c);
        return per == null ? Lang.raw("stock.dividend-soon") : Lang.raw("stock.dividend-yes", "per", amountFine(per));
    }

    /** What a holding got last time (an estimate for the next Monday). */
    private String myDividend(StockStore.Co c, long shares) {
        if (c.dividend <= 0) return Lang.raw("stock.my-dividend-none");
        Double per = lastDividend(c);
        return per == null ? Lang.raw("stock.my-dividend-soon") : Lang.raw("stock.my-dividend", "amount", amountFine(per * shares));
    }

    public void showNews(Player p) {
        List<StockStore.News> l = store.news();
        if (l.isEmpty()) {
            p.sendMessage(Lang.msg("stock.no-news"));
            return;
        }
        p.sendMessage(Lang.msg("stock.news-header"));
        for (StockStore.News n : l.subList(Math.max(0, l.size() - 10), l.size())) p.sendMessage(Lang.mm("stock.news-line", "line", n.line()));
    }

    public void toggleAlerts(Player p) {
        String k = "stock.mute." + p.getUniqueId();
        boolean muted = eco.kv().has(k);
        if (muted) eco.kv().remove(k);
        else eco.kv().set(k, 1);
        p.sendMessage(Lang.msg(muted ? "stock.alerts-on" : "stock.alerts-off"));
    }

    // ------------------------------------------------------------------------------------------
    // The windows
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        String k = "stock.intro." + p.getUniqueId();
        if (!eco.kv().has(k)) {
            eco.kv().set(k, 1);
            Lang.items("stock.intro").forEach(p::sendMessage);
        }
        core.menus().open(p, "economy:stocks", new ListMenu("all", "industry"));
    }

    public void openCompany(Player p, StockStore.Co c, String period) {
        core.menus().open(p, "economy:stock", new CompanyMenu(c.id, period));
    }

    private ItemStack companyIcon(StockStore.Co c, UUID viewer) {
        StockSpec.Industry i = industry(c);
        ItemStack it;
        if (spec.companies().containsKey(c.id)) {
            // its own logo (economy_logos.py); a company an admin listed later shows its industry's item
            it = new ItemStack(Material.PAPER);
            it.editMeta(meta -> meta.setItemModel(new org.bukkit.NamespacedKey("economy", "gui/logo_" + c.id)));
        } else {
            it = Items.create(i == null ? "minecraft:paper" : i.icon(), 1).orElse(new ItemStack(Material.PAPER));
        }
        double[] range = range24(c);
        List<Component> lore = new ArrayList<>(Lang.items("stock.icon-lore", "color", i == null ? "white" : i.color(), "industry", i == null ? "-" : i.name(),
                "risk", Lang.raw("stock.risk." + c.risk), "price", money(c.price), "change", changeText(c.dayChange()),
                "low", money(range[0]), "high", money(range[1]), "dividend", dividendLine(c)));
        StockStore.Hold h = store.holdings(viewer).get(c.id);
        if (h != null && h.shares > 0) {
            lore.addAll(Lang.items("stock.icon-mine", mine(h, c)));
            lore.add(Text.item(myDividend(c, h.shares)));
        }
        if (c.halted) lore.add(Lang.item("stock.icon-halted"));
        lore.add(Lang.item("stock.icon-click"));
        it.editMeta(meta -> {
            meta.itemName(Text.item("<white>" + c.name));
            meta.lore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return it;
    }

    /** The list orders, in the order the sort button goes through them, with their icons. */
    private static final List<String> SORTS = List.of("industry", "up", "down", "popular");
    private static final Map<String, String> SORT_ICONS = Map.of("industry", "gui/refresh", "up", "gui/rise", "down", "gui/fall", "popular", "gui/trophy");

        private final class ListMenu implements MenuHandler {
        private final String tab;
        private final String sort;
        private List<StockStore.Co> shown = List.of();

        ListMenu(String tab, String sort) {
            this.tab = tab;
            this.sort = sort;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            double value = value(u), cost = 0;
            for (StockStore.Hold h : store.holdings(u).values()) cost += h.cost;
            StockSpec.Industry i = spec.industries().get(tab);
            String tabName = tab.equals("all") ? Lang.raw("stock.tab-all") : tab.equals("mine") ? Lang.raw("stock.tab-mine")
                    : i == null ? tab : "<" + i.color() + ">" + i.name();
            return Map.of("tab", tabName, "value", money(value), "profit", money(Math.abs(value - cost)), "sign", value >= cost ? "+" : "-",
                    "pl", plText(value, cost), "sort", Lang.raw("stock.sort." + sort));
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button b, ItemStack base) {
            if (b.key() == 'S') base.editMeta(meta -> meta.setItemModel(new org.bukkit.NamespacedKey("economy", SORT_ICONS.getOrDefault(sort, "gui/refresh"))));
            return base;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID u = v.player().getUniqueId();
            List<StockStore.Co> l = new ArrayList<>();
            for (StockStore.Co c : listed()) {
                if (tab.equals("mine")) {
                    StockStore.Hold h = store.holdings(u).get(c.id);
                    if (h == null || h.shares <= 0) continue;
                } else if (!tab.equals("all") && !tab.equals(c.industry)) continue;
                l.add(c);
            }
            switch (sort) {
                case "up" -> l.sort(Comparator.comparingDouble(StockStore.Co::dayChange).reversed());
                case "down" -> l.sort(Comparator.comparingDouble(StockStore.Co::dayChange));
                case "popular" -> l.sort(Comparator.comparingLong((StockStore.Co c) -> c.boughtToday).reversed());
                default -> { }
            }
            shown = l;
            List<ItemStack> out = new ArrayList<>();
            for (StockStore.Co c : l) out.add(companyIcon(c, u));
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index < shown.size()) openCompany(v.player(), shown.get(index), "h24");
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            if (action.startsWith("stock:tab:")) core.menus().open(p, "economy:stocks", new ListMenu(action.substring(10), sort));
            else if (action.equals("stock:sort:next")) {
                int at = Math.max(0, SORTS.indexOf(sort)), step = click.isRightClick() ? SORTS.size() - 1 : 1;
                core.menus().open(p, "economy:stocks", new ListMenu(tab, SORTS.get((at + step) % SORTS.size())));
            } else if (action.startsWith("stock:sort:")) core.menus().open(p, "economy:stocks", new ListMenu(tab, action.substring(11)));
            else if (action.equals("stock:news")) {
                p.closeInventory();
                showNews(p);
            } else if (action.equals("stock:help")) {
                p.closeInventory();
                Lang.items("stock.intro").forEach(p::sendMessage);
            }
        }
    }

    private static List<StockGraph.Candle> candles(List<StockStore.Candle> src) {
        List<StockGraph.Candle> out = new ArrayList<>();
        for (StockStore.Candle k : src) out.add(new StockGraph.Candle(k.open(), k.high(), k.low(), k.close()));
        return out;
    }

    /** Hourly candles merged two by two (even hours), for the 24-hour chart's bigger bars. */
    static List<StockGraph.Candle> twoHour(List<StockStore.Candle> hourly) {
        List<StockGraph.Candle> out = new ArrayList<>();
        long bucket = Long.MIN_VALUE;
        double open = 0, high = 0, low = 0, close = 0;
        for (StockStore.Candle k : hourly) {
            long b = Math.floorDiv(k.t(), 7_200_000L);
            if (b != bucket) {
                if (bucket != Long.MIN_VALUE) out.add(new StockGraph.Candle(open, high, low, close));
                bucket = b;
                open = k.open();
                high = k.high();
                low = k.low();
            } else {
                high = Math.max(high, k.high());
                low = Math.min(low, k.low());
            }
            close = k.close();
        }
        if (bucket != Long.MIN_VALUE) out.add(new StockGraph.Candle(open, high, low, close));
        return out;
    }

    private final class CompanyMenu implements MenuHandler {
        private final String id;
        private final String period;

        CompanyMenu(String id, String period) {
            this.id = id;
            this.period = period;
        }

        private StockStore.Co c() {
            return store.company(id);
        }

        /** 10분: sixteen 10-minute candles; 24시간: twelve 2-hour candles; 7일: seven day candles. */
        private String graph() {
            List<StockGraph.Candle> all = switch (period) {
                case "m10" -> candles(store.tenMinute(id));
                case "d7" -> candles(store.daily(id));
                default -> twoHour(store.hourly(id));
            };
            int slots = switch (period) {
                case "m10" -> 16;
                case "d7" -> 7;
                default -> 12;
            };
            return StockGraph.title(all.subList(Math.max(0, all.size() - slots), all.size()), slots);
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            StockStore.Co c = c();
            if (c == null) return Map.of();
            UUID u = v.player().getUniqueId();
            StockStore.Hold h = store.holdings(u).get(id);
            long have = h == null ? 0 : h.shares, sellable = h == null ? 0 : h.sellable(now());
            double val = have * c.price, cost = h == null ? 0 : h.cost;
            StockSpec.Industry i = industry(c);
            Map<String, String> m = new HashMap<>();
            m.put("graph", graph());
            m.put("name", c.name);
            m.put("price", money(c.price));
            m.put("change", changeText(c.dayChange()));
            m.put("stars", stars(c.risk));
            m.put("industry", i == null ? "-" : "<" + i.color() + ">" + i.name());
            m.put("high", money(c.dayHigh));
            m.put("low", money(c.dayLow));
            m.put("close", money(c.prevClose));
            m.put("shares", String.format("%,d", c.shares));
            m.put("held", String.format("%,d", store.held(id)));
            m.put("dividend", dividendLine(c));
            m.put("risk", Lang.raw("stock.risk." + c.risk));
            m.put("pl", plText(val, cost));
            m.put("mydiv", myDividend(c, have));
            m.put("period", Lang.raw("stock.period." + period));
            m.put("have", String.format("%,d", have));
            m.put("sellable", String.format("%,d", sellable));
            m.put("value", money(val));
            m.put("profit", money(Math.abs(val - cost)));
            m.put("sign", val >= cost ? "+" : "-");
            boolean first = !eco.kv().has("stock.first." + u);
            double fee = first || feeFree() ? 0 : spec.settings().fee();
            for (int n : new int[]{1, 10, 100}) m.put("p" + n, money(Math.ceil(c.price * n) + Math.ceil(c.price * n * fee)));
            m.put("fee", first ? Lang.raw("stock.fee-first") : feeFree() ? Lang.raw("stock.fee-free") : Lang.raw("stock.fee", "pct", pct(spec.settings().fee() * 100)));
            m.put("halted", c.halted ? Lang.raw("stock.icon-halted") : "");
            List<String> lines = new ArrayList<>();
            for (StockStore.News n : store.news()) if (n.company().equals(id)) lines.add(n.line());
            m.put("news", lines.isEmpty() ? Lang.raw("stock.no-news-company") : lines.getLast());
            return m;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            StockStore.Co c = c();
            if (c == null) {
                p.closeInventory();
                return;
            }
            if (action.startsWith("stock:period:")) {
                openCompany(p, c, action.substring(13));
            } else if (action.startsWith("stock:buy:")) {
                trade(p, c, true, Long.parseLong(action.substring(10)));
                v.refresh();
            } else if (action.startsWith("stock:sell:")) {
                String n = action.substring(11);
                StockStore.Hold h = store.holdings(p.getUniqueId()).get(id);
                trade(p, c, false, n.equals("all") ? (h == null ? 0 : h.shares) : Long.parseLong(n));
                v.refresh();
            } else if (action.equals("stock:back")) {
                core.menus().open(p, "economy:stocks", new ListMenu(c.industry, "industry"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The 주식판 hologram (/관리자 위치 주식판): industries and today's biggest movers
    // ------------------------------------------------------------------------------------------

    public void updateBoard() {
        Location at = eco.location("stock-board");
        if (at == null) {
            if (board != null) board.remove();
            board = null;
            return;
        }
        if (!at.isChunkLoaded()) return;
        // upright whatever way the admin was looking when saving the spot (a pitch tilts the text away)
        at.setPitch(0);
        at.setYaw(0);
        if (board == null || !board.isValid()) {
            for (var ent : at.getWorld().getNearbyEntitiesByType(TextDisplay.class, at, 1.5)) {
                if (ent.getScoreboardTags().contains("stsv-stock-board")) ent.remove();
            }
            board = at.getWorld().spawn(at, TextDisplay.class, t -> {
                t.addScoreboardTag("stsv-stock-board");
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setLineWidth(300);
                t.setBackgroundColor(org.bukkit.Color.fromARGB(160, 10, 10, 14));
            });
        }
        StringBuilder sb = new StringBuilder(Lang.raw("stock.board-title"));
        for (StockSpec.Industry i : spec.industries().values()) {
            double now = 0, prev = 0;
            int n = 0;
            for (StockStore.Co c : listed()) {
                if (!c.industry.equals(i.id())) continue;
                now += c.price / c.start;
                prev += c.prevClose / c.start;
                n++;
            }
            if (n == 0 || prev <= 0) continue;
            // 산업 지수: listing prices = 1,000
            sb.append('\n').append(Lang.raw("stock.board-industry", "color", i.color(), "industry", i.name(),
                    "index", String.format("%,.0f", now / n * 1000), "change", changeText((now / prev - 1) * 100)));
        }
        List<StockStore.Co> movers = new ArrayList<>(listed());
        movers.sort(Comparator.comparingDouble(StockStore.Co::dayChange).reversed());
        if (!movers.isEmpty()) {
            sb.append('\n').append(Lang.raw("stock.board-movers"));
            for (StockStore.Co c : movers.subList(0, Math.min(3, movers.size()))) {
                sb.append('\n').append(Lang.raw("stock.board-line", "company", c.name, "price", money(c.price), "change", changeText(c.dayChange())));
            }
            for (StockStore.Co c : movers.subList(Math.max(0, movers.size() - 3), movers.size()).reversed()) {
                sb.append('\n').append(Lang.raw("stock.board-line", "company", c.name, "price", money(c.price), "change", changeText(c.dayChange())));
            }
        }
        board.text(Text.mm(sb.toString()));
    }

    /** For the chat command: the trade by name. */
    public void tradeByCommand(Player p, String company, String amount, boolean buying) {
        StockStore.Co c = find(company);
        if (c == null) {
            p.sendMessage(Lang.msg("stock.no-such"));
            return;
        }
        long qty;
        if (!buying && amount.equals("전부")) {
            StockStore.Hold h = store.holdings(p.getUniqueId()).get(c.id);
            qty = h == null ? 0 : h.shares;
        } else {
            try {
                qty = Long.parseLong(amount);
            } catch (NumberFormatException e) {
                p.sendMessage(Lang.msg("stock.bad-amount"));
                return;
            }
        }
        trade(p, c, buying, qty);
    }
}
