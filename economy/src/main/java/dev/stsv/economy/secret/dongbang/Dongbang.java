package dev.stsv.economy.secret.dongbang;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.stock.StockGraph;
import dev.stsv.economy.stock.StockModule;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 동방해운 (docs/admin/숨은_비밀/동방해운.md; contents/economy/secrets/dongbang.yml). A shipping company delisted in 1987:
 * its ship went down during a shareholders' meeting held on board, and the twelve shareholders are "missing". One share
 * is left, and the meeting has waited ever since for its thirteenth member.
 * <ol>
 *     <li>보유: once a day, for one minute, a grey "동방해운 (거래정지)" sits at the bottom of the player's /주식 list. They
 *     buy its one share; its price then follows them only (up while online, down while away, swinging by water).</li>
 *     <li>통지: the next day a soaked 주주총회 소집 통지서 comes to the mailbox: the 본사 address is the price at two
 *     midnights (east-west, then north-south), the last candle's colour the sign ({@link Ticker}).</li>
 *     <li>소집: after the second midnight the share is halted at |z|; at the 본사 a wet boardroom only they see waits
 *     ({@link Boardroom}). Sitting in the empty 13th chair brings the vote: 찬성 frees them (solved), 반대 floods the
 *     room (try again after the next midnight).</li>
 * </ol>
 * Selling the share ends it for that player for good (a letter: "…또 혼자 남았군요."). The solver gets a daily dividend.
 */
public final class Dongbang extends Secret {

    static final String NOTICE = "economy:dongbang_notice";
    static final String CERTIFICATE = "economy:dongbang_certificate";

    private final Random rnd = new Random();
    private final Map<UUID, Ticker> tickers = new HashMap<>();
    /** Until when (ms) a player who saw the offer row may still buy. */
    private final Map<UUID, Long> offered = new HashMap<>();
    /** Admin 연출 목록: the row shows regardless of the minute, until then (ms). */
    private final Map<UUID, Long> forced = new HashMap<>();
    private final Map<UUID, Long> sellConfirm = new HashMap<>();
    private final Map<UUID, Boolean> water = new HashMap<>();
    /** Players whose 본사 is being chosen, with what waits for it. */
    private final Map<UUID, List<Runnable>> picking = new HashMap<>();
    final Map<UUID, Boardroom> rooms = new HashMap<>();
    /** The offer day (m.today) whose one chat news echo a player already got. */
    private final Map<UUID, String> echoed = new HashMap<>();
    private long lastMinute;
    private int ticks;

    public Dongbang(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "dongbang";
    }

    @Override
    public String name() {
        return "동방해운";
    }

    /** 1 보유 · 2 통지 · 3 소집 (4 = solved). */
    @Override
    public int stages() {
        return 3;
    }

    @Override
    protected void loaded() {
        StockModule stocks = eco.stocks();
        if (stocks != null) stocks.hidden(new StockModule.Hidden() {
            @Override
            public ItemStack icon(Player viewer, String tab) {
                return listIcon(viewer, tab);
            }

            @Override
            public void open(Player viewer) {
                window(viewer, "m10");
            }

            @Override
            public List<String> news(Player viewer, List<String> lines) {
                return newsView(viewer, lines);
            }

            @Override
            public String companyNews(Player viewer, String line) {
                return companyNewsView(viewer, line);
            }

            @Override
            public void broadcast(String line) {
                newsEcho();
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------------------------------

    SecretModule m() {
        return m;
    }

    org.bukkit.configuration.file.YamlConfiguration cfg() {
        return cfg;
    }

    org.bukkit.plugin.Plugin plugin() {
        return eco;
    }

    String line(String path) {
        return cfg.getString(path, "");
    }

    List<String> lines(String path) {
        return cfg.getStringList(path);
    }

    long buyPrice() {
        return Math.max(1, cfg.getLong("share.price", 1987));
    }

    private Ticker.Rules rules(Ticker.Clock clock) {
        double hours = cfg.getDouble("price.approach-hours", 3);
        int candles = (int) Math.round(hours / 24 * clock.candlesPerDay());
        return new Ticker.Rules(cfg.getDouble("price.online", 0.005), cfg.getDouble("price.offline", 0.003), cfg.getDouble("price.noise", 0.004),
                cfg.getDouble("price.water", 0.05), cfg.getDouble("price.last-candle", 0.04), Math.max(clock.test() ? 4 : 2, candles),
                cfg.getDouble("price.min", 100), cfg.getDouble("price.max", 20_000));
    }

    static String money(double v) {
        return EconomyModule.format(Math.round(v));
    }

    // ------------------------------------------------------------------------------------------
    // the share: saved state
    // ------------------------------------------------------------------------------------------

    /** The holder's share, loaded (and moved on through the time they were away) on first use. */
    Ticker ticker(UUID u) {
        boolean test = m.test(u);
        Ticker k = tickers.get(u);
        if (k != null && k.clock.test() == test) return k;
        if (k != null) save(u, k);
        long now = System.currentTimeMillis();
        Ticker.Clock clock = new Ticker.Clock(test, m.day(u));
        double price;
        try {
            price = Double.parseDouble(m.get(this, u, "price", String.valueOf(buyPrice())));
        } catch (NumberFormatException e) {
            price = buyPrice();
        }
        k = new Ticker(clock, rules(clock), price, m.getLong(this, u, "t", now));
        Ticker.decode(m.get(this, u, "m10"), k.m10);
        Ticker.decode(m.get(this, u, "h2"), k.h2);
        Ticker.decode(m.get(this, u, "d1"), k.d1);
        Ticker.decodeCloses(m.get(this, u, "closes"), k.closes);
        k.kx = m.getLong(this, u, "kx", 0);
        k.x = (int) m.getLong(this, u, "x", 0);
        k.z = (int) m.getLong(this, u, "z", 0);
        try {
            k.p0 = Double.parseDouble(m.get(this, u, "p0", "-1"));
        } catch (NumberFormatException e) {
            k.p0 = -1;
        }
        tickers.put(u, k);
        k.advance(now, Ticker.Mood.OFFLINE, rnd);
        save(u, k);
        return k;
    }

    void save(UUID u, Ticker k) {
        m.set(this, u, "price", Ticker.num(k.price));
        m.set(this, u, "t", k.t);
        m.set(this, u, "m10", Ticker.encode(k.m10));
        m.set(this, u, "h2", Ticker.encode(k.h2));
        m.set(this, u, "d1", Ticker.encode(k.d1));
        m.set(this, u, "closes", Ticker.encodeCloses(k.closes));
        m.set(this, u, "kx", k.kx);
        m.set(this, u, "p0", Ticker.num(k.p0));
    }

    private boolean holding(UUID u) {
        int st = m.stage(this, u);
        return st >= 1 && st <= stages();
    }

    private boolean sold(UUID u) {
        return m.getLong(this, u, "sold", 0) != 0;
    }

    // ------------------------------------------------------------------------------------------
    // start, jump, cleanup, status
    // ------------------------------------------------------------------------------------------

    @Override
    public void start(Player p) {
        begin(p);
    }

    /** They own the share now (bought, or given by an admin): stage 1, a fresh price, the 본사 being chosen. */
    private void begin(Player p) {
        UUID u = p.getUniqueId();
        wipe(u);
        m.stage(this, u, 1);
        long now = System.currentTimeMillis();
        m.set(this, u, "bought", now);
        Ticker.Clock clock = new Ticker.Clock(m.test(u), m.day(u));
        Ticker k = new Ticker(clock, rules(clock), buyPrice(), now);
        k.record(now, buyPrice());
        tickers.put(u, k);
        save(u, k);
        pick(u, () -> {});
    }

    /** Forgets the share (not the stage). */
    private void wipe(UUID u) {
        cleanup(u);
        for (String k : List.of("price", "t", "m10", "h2", "d1", "closes", "kx", "p0", "bought", "notice-at", "lost-day", "sold", "sold-at", "x", "z", "news-end", "news-echo"))
            m.remove(this, u, k);
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        if (stage <= 0) {
            wipe(u);
            m.stage(this, u, 0);
            return;
        }
        begin(p);
        if (stage == 1) return;
        pick(u, () -> {
            if (!p.isOnline()) return;
            Ticker k = ticker(u);
            long now = System.currentTimeMillis();
            if (stage == 2) {
                notice(p, k, now);
                return;
            }
            // 3: both midnights have passed (yesterday's end = |x|, today's start = |z|), halted at |z|
            long today = k.clock.dayStart(now), len = k.clock.length();
            k.x = (int) m.getLong(this, u, "x", 0);
            k.z = (int) m.getLong(this, u, "z", 0);
            k.kx = today - len;
            k.closes.clear();
            k.closes.add(new Ticker.Close(today - len, Math.abs(k.x), k.x > 0));
            k.closes.add(new Ticker.Close(today, Math.abs(k.z), k.z > 0));
            k.price = Math.abs(k.z);
            k.t = now;
            k.record(today - 1, Math.abs(k.z));
            save(u, k);
            m.set(this, u, "notice-at", today - 2 * len);
            m.stage(this, u, 3);
            p.getInventory().addItem(noticeItem(k, now));
        });
    }

    @Override
    public void cleanup(UUID u) {
        Ticker k = tickers.remove(u);
        if (k != null && holding(u)) save(u, k);
        Boardroom r = rooms.remove(u);
        if (r != null) r.teardown();
        offered.remove(u);
        forced.remove(u);
        sellConfirm.remove(u);
        water.remove(u);
        echoed.remove(u);
    }

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        if (sold(u)) out.add("<red>팔아 버림 (다시 못 함)");
        int st = m.stage(this, u);
        if (holding(u) || st > stages()) {
            String x = m.get(this, u, "x"), z = m.get(this, u, "z");
            out.add(x == null ? "본사 고르는 중" : "본사 X " + x + " Z " + z);
        }
        if (holding(u)) {
            Ticker k = tickers.get(u);
            String price = k != null ? money(k.price) : money(parse(m.get(this, u, "price")));
            out.add("주가 " + price + (k != null && k.halted(System.currentTimeMillis()) ? " (거래정지)" : ""));
            long kx = m.getLong(this, u, "kx", 0);
            if (kx > 0) {
                Ticker.Clock c = new Ticker.Clock(m.test(u), m.day(u));
                out.add("자정 " + when(c, kx) + " / " + when(c, kx + c.length()));
            }
            String lost = m.get(this, u, "lost-day");
            if (lost != null && lost.equals(m.today(u))) out.add("오늘 반대함 (다음 자정 뒤 다시)");
        }
        if (st > stages()) out.add(m.getLong(this, u, "test-solved", 0) != 0 ? "테스트로 풂 (배당 없음)" : "배당 마지막 " + m.get(this, u, "div-day", "-"));
        return String.join(" · ", out);
    }

    private static double parse(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String when(Ticker.Clock c, long midnight) {
        if (c.test()) {
            long s = (midnight - System.currentTimeMillis()) / 1000;
            return s >= 0 ? s + "초 뒤" : -s + "초 전";
        }
        return DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(midnight).atZone(SecretModule.KST));
    }

    // ------------------------------------------------------------------------------------------
    // the 본사
    // ------------------------------------------------------------------------------------------

    World world() {
        String name = cfg.getString("headquarters.world", "");
        World w = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        if (w == null && m.core().worlds() != null) w = m.core().worlds().buildWorld();
        return w != null ? w : Bukkit.getWorlds().getFirst();
    }

    /** Chooses the 본사 once (kv x, z): the flattest dry candidate for this player, then `then` (main thread). */
    private void pick(UUID u, Runnable then) {
        if (m.get(this, u, "x") != null) {
            then.run();
            return;
        }
        List<Runnable> waiting = picking.get(u);
        if (waiting != null) {
            waiting.add(then);
            return;
        }
        picking.put(u, new ArrayList<>(List.of(then)));
        World w = world();
        List<int[]> c = Ticker.candidates(u.getMostSignificantBits() ^ u.getLeastSignificantBits() ^ 0x1987L,
                cfg.getInt("headquarters.min", 300), cfg.getInt("headquarters.max", 1500), Math.max(1, cfg.getInt("headquarters.tries", 12)));
        tryPick(w, u, c, 0, null, Integer.MAX_VALUE);
    }

    private void tryPick(World w, UUID u, List<int[]> c, int i, int[] best, int bestScore) {
        if (i >= c.size() || bestScore <= 2) {
            int[] at = best != null ? best : c.getFirst();
            m.set(this, u, "x", at[0]);
            m.set(this, u, "z", at[1]);
            List<Runnable> waiting = picking.remove(u);
            if (waiting != null) waiting.forEach(Runnable::run);
            return;
        }
        int[] at = c.get(i);
        if (!w.getWorldBorder().isInside(new Location(w, at[0], 64, at[1]))) {
            tryPick(w, u, c, i + 1, best, bestScore);
            return;
        }
        w.getChunkAtAsync(at[0] >> 4, at[1] >> 4, true, chunk -> {
            int score = roughness(w, at[0], at[1]);
            if (score < bestScore) tryPick(w, u, c, i + 1, at, score);
            else tryPick(w, u, c, i + 1, best, bestScore);
        });
    }

    /** How bad a spot is for the room: the spread of ground heights over it, much worse for water or trees. */
    private static int roughness(World w, int cx, int cz) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, bad = 0;
        for (int dx = -Boardroom.HALF_X; dx <= Boardroom.HALF_X; dx++) {
            for (int dz = -Boardroom.HALF_Z; dz <= Boardroom.HALF_Z; dz++) {
                int y = w.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                Block top = w.getBlockAt(cx + dx, y, cz + dz);
                if (top.isLiquid()) bad += 20;
                if (w.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.MOTION_BLOCKING) > y + 1) bad += 1;
            }
        }
        return hi - lo + bad;
    }

    /** The room's middle: the 본사's ground. */
    Location headquarters(UUID u) {
        World w = world();
        int x = (int) m.getLong(this, u, "x", 0), z = (int) m.getLong(this, u, "z", 0);
        return new Location(w, x, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES), z);
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000L;
        boolean newMinute = minute != lastMinute;
        lastMinute = minute;
        ticks++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            int st = m.stage(this, u);
            if (st == stages() + 1) {
                dividend(p);
                continue;
            }
            if (st < 1 || st > stages() || !open(u)) continue;
            Ticker k = ticker(u);
            long bucket = k.m10.isEmpty() ? 0 : k.m10.getLast().t();
            boolean wet = wet(p);
            List<Ticker.Close> crossed = k.advance(now, wet ? Ticker.Mood.WATER : Ticker.Mood.ONLINE, rnd);
            for (Ticker.Close c : crossed) {
                if (k.kx != 0 && (c.at() == k.kx || c.at() == k.kz())) midnight(p);
            }
            if (!crossed.isEmpty() || (!k.m10.isEmpty() && k.m10.getLast().t() != bucket)) save(u, k);
            if (st == 1 && k.clock.dayStart(now) > k.clock.dayStart(m.getLong(this, u, "bought", now))) {
                pick(u, () -> {
                    if (p.isOnline() && m.stage(this, u) == 1) notice(p, ticker(u), System.currentTimeMillis());
                });
            } else if (st == 2 && k.kx != 0 && now >= k.kz()) {
                m.stage(this, u, 3);
                p.sendMessage(Text.mm(line("haunt.convene")));
                m.haunt().sound(p, p.getLocation(), Sound.EVENT_RAID_HORN, 0.4f, 0.5f);
            } else if (st == 3) {
                meet(p);
            }
            if (newMinute) haunt(p, wet);
        }
    }

    /** By water: in it, or water within 2 blocks (checked every 2 seconds). */
    private boolean wet(Player p) {
        UUID u = p.getUniqueId();
        Boolean known = water.get(u);
        if (known != null && ticks % 2 != 0) return known;
        boolean wet = p.isInWater();
        Block feet = p.getLocation().getBlock();
        for (int dx = -2; dx <= 2 && !wet; dx++)
            for (int dy = -1; dy <= 1 && !wet; dy++)
                for (int dz = -2; dz <= 2 && !wet; dz++) if (feet.getRelative(dx, dy, dz).getType() == Material.WATER) wet = true;
        water.put(u, wet);
        return wet;
    }

    private void haunt(Player p, boolean wet) {
        UUID u = p.getUniqueId();
        if (rooms.containsKey(u)) return;
        if (m.roll(u, cfg.getDouble("haunt.whisper-chance", 0.08))) {
            List<String> pool = new ArrayList<>(lines("haunt.whispers"));
            if (wet) pool.addAll(lines("haunt.water"));
            if (!pool.isEmpty()) m.haunt().whisper(p, pool.get(rnd.nextInt(pool.size())));
        }
        if (wet && rnd.nextDouble() < 0.5) m.haunt().behind(p, Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 0.7f, 0.8f + rnd.nextFloat() * 0.3f);
    }

    /** A key midnight while they are on: a ship's horn far off, and a reminder. */
    private void midnight(Player p) {
        m.haunt().sound(p, p.getLocation().add(p.getLocation().getDirection().multiply(-40)), Sound.EVENT_RAID_HORN, 0.5f, 0.5f);
        m.haunt().whisper(p, line("haunt.midnight"));
    }

    private void dividend(Player p) {
        UUID u = p.getUniqueId();
        if (m.getLong(this, u, "test-solved", 0) != 0) return;
        String today = LocalDate.now(SecretModule.KST).toString();
        if (today.equals(m.get(this, u, "div-day"))) return;
        long amount = cfg.getLong("dividend.amount", 5000);
        m.set(this, u, "div-day", today);
        if (amount <= 0) return;
        m.core().economy().deposit(u, amount, "secret");
        p.sendMessage(Text.msg(line("dividend.message").replace("{amount}", money(amount))));
    }

    // ------------------------------------------------------------------------------------------
    // the notice and the letter
    // ------------------------------------------------------------------------------------------

    /** Day 2: the notice comes to the mailbox and the two key midnights are set. */
    void notice(Player p, Ticker k, long now) {
        UUID u = p.getUniqueId();
        k.notice(now, (int) m.getLong(this, u, "x", 0), (int) m.getLong(this, u, "z", 0));
        save(u, k);
        m.set(this, u, "notice-at", now);
        m.stage(this, u, 2);
        eco.mail().store().send(u, Items.bytes(noticeItem(k, now)), "dongbang", now);
        p.sendMessage(Text.msg(line("notice.arrived")));
        m.haunt().behind(p, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.6f);
    }

    ItemStack noticeItem(Ticker k, long now) {
        String first = dateOf(k, k.kx, now), second = dateOf(k, k.kz(), now);
        List<String> lore = new ArrayList<>();
        for (String l : lines("notice.lore")) lore.add(l.replace("{first}", first).replace("{second}", second));
        return paper(NOTICE, line("notice.name"), lore);
    }

    private String dateOf(Ticker k, long midnight, long now) {
        if (k.clock.test()) return line("notice.test-date").replace("{seconds}", String.valueOf(Math.max(0, (midnight - now) / 1000)));
        ZonedDateTime day = Instant.ofEpochMilli(midnight - 1).atZone(SecretModule.KST);
        return DateTimeFormatter.ofPattern(cfg.getString("notice.date-format", "M월 d일 밤 12시"), Locale.KOREAN).format(day);
    }

    ItemStack paper(String id, String name, List<String> lore) {
        ItemStack it = Items.create(id, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        it.editMeta(meta -> {
            if (!name.isEmpty()) meta.itemName(Text.item(name));
            meta.lore(Text.itemLines(lore));
        });
        return it;
    }

    ItemStack letter() {
        return paper(NOTICE, line("sold.name"), lines("sold.lore"));
    }

    // ------------------------------------------------------------------------------------------
    // the meeting
    // ------------------------------------------------------------------------------------------

    private void meet(Player p) {
        UUID u = p.getUniqueId();
        if (rooms.containsKey(u) || m.get(this, u, "x") == null) return;
        Location hq = headquarters(u);
        if (p.getWorld() != hq.getWorld()) return;
        double dx = p.getX() - (hq.getBlockX() + 0.5), dz = p.getZ() - (hq.getBlockZ() + 0.5);
        int r = cfg.getInt("meeting.radius", 16);
        if (dx * dx + dz * dz > (double) r * r) return;
        if (m.today(u).equals(m.get(this, u, "lost-day"))) {
            if (rnd.nextInt(20) == 0) m.haunt().whisper(p, line("meeting.closed-today"));
            return;
        }
        room(p, hq, false);
    }

    Boardroom room(Player p, Location center, boolean preview) {
        Boardroom old = rooms.remove(p.getUniqueId());
        if (old != null) old.teardown();
        Boardroom r = new Boardroom(this, p, center, preview);
        rooms.put(p.getUniqueId(), r);
        r.build();
        return r;
    }

    /** The room says it is gone (teardown). */
    void gone(Boardroom r) {
        rooms.remove(r.viewer(), r);
    }

    /** 찬성: the end. */
    void solved(Player p) {
        UUID u = p.getUniqueId();
        boolean test = m.test(u);
        if (!test && m.closed(this)) {
            m.solve(this, p);       // says it is too late
            return;
        }
        tickers.remove(u);
        m.solve(this, p);
        if (test) m.set(this, u, "test-solved", 1);
        m.set(this, u, "div-day", LocalDate.now(SecretModule.KST).toString());
        p.getInventory().addItem(certificate(p)).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
    }

    ItemStack certificate(Player p) {
        List<String> lore = new ArrayList<>();
        String div = money(cfg.getLong("dividend.amount", 5000));
        for (String l : lines("meeting.certificate.lore")) lore.add(l.replace("{player}", p.getName()).replace("{dividend}", div));
        return paper(CERTIFICATE, line("meeting.certificate.name"), lore);
    }

    /** 반대: nothing more today. */
    void lost(Player p) {
        m.set(this, p.getUniqueId(), "lost-day", m.today(p.getUniqueId()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Boardroom r = rooms.get(p.getUniqueId());
        if (r != null && r.dismount(e.getDismounted())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) return;
        Boardroom r = rooms.get(e.getPlayer().getUniqueId());
        if (r != null && r.clicked()) e.setCancelled(true);
    }

    @EventHandler
    public void onBallotClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Boardroom.Ballot b)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Boardroom r = rooms.get(b.viewer());
        if (r == null) {
            e.getWhoClicked().closeInventory();
            return;
        }
        if (e.getRawSlot() == Boardroom.Ballot.YES) r.vote(true);
        else if (e.getRawSlot() == Boardroom.Ballot.NO) r.vote(false);
    }

    @EventHandler
    public void onBallotDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Boardroom.Ballot) e.setCancelled(true);
    }

    @EventHandler
    public void onBallotClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Boardroom.Ballot b)) return;
        Boardroom r = rooms.get(b.viewer());
        if (r != null) r.ballotClosed();
    }

    // ------------------------------------------------------------------------------------------
    // /주식: the row and the window
    // ------------------------------------------------------------------------------------------

    /** Is the offer up for them now: one minute a day, at a time of its own for each player and day. */
    private boolean offerNow(UUID u, long now) {
        if (m.test(u) || forced.getOrDefault(u, 0L) > now) return true;
        List<Integer> h = cfg.getIntegerList("offer.hours");
        int from = h.size() > 0 ? h.get(0) : 0, to = h.size() > 1 ? h.get(1) : 24;
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(SecretModule.KST);
        int span = Math.floorMod(to * 60 - from * 60 - 1, 24 * 60) + 1;
        int len = Math.max(1, cfg.getInt("offer.minutes", 1));
        long seed = u.getMostSignificantBits() * 31 + u.getLeastSignificantBits() + t.toLocalDate().toEpochDay() * 1_000_003L;
        int start = Math.floorMod(from * 60 + new Random(seed).nextInt(Math.max(1, span - len + 1)), 24 * 60);
        int nowMin = t.getHour() * 60 + t.getMinute();
        return Math.floorMod(nowMin - start, 24 * 60) < len;
    }

    private ItemStack listIcon(Player viewer, String tab) {
        if (!on()) return null;
        UUID u = viewer.getUniqueId();
        long now = System.currentTimeMillis();
        if (holding(u)) {
            if (!open(u) || !(tab.equals("all") || tab.equals("mine"))) return null;
            Ticker k = ticker(u);
            List<String> lore = new ArrayList<>();
            for (String l : lines("share.icon-lore")) {
                l = fill(l, k, now);
                if (!l.isBlank()) lore.add(l);
            }
            return icon(line(k.halted(now) ? "share.halted-name" : "share.name"), lore);
        }
        if (m.stage(this, u) != 0 || !tab.equals("all") || sold(u) || !open(u) || !offerNow(u, now)) return null;
        offered.put(u, now + Math.max(1, cfg.getLong("offer.grace-minutes", 5)) * 60_000L);
        List<String> lore = new ArrayList<>();
        for (String l : lines("offer.icon-lore")) lore.add(l.replace("{price}", money(buyPrice())));
        return icon(line("offer.icon-name"), lore);
    }

    private static ItemStack icon(String name, List<String> lore) {
        ItemStack it = Items.create(NOTICE, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        it.editMeta(meta -> {
            meta.itemName(Text.item(name));
            meta.lore(Text.itemLines(lore));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return it;
    }

    private String fill(String l, Ticker k, long now) {
        return l.replace("{price}", money(k.price)).replace("{change}", change(k)).replace("{pl}", pl(k.price, buyPrice()))
                .replace("{halted}", k.halted(now) ? Lang.raw("stock.icon-halted") : "");
    }

    private String change(Ticker k) {
        double prev = k.prevClose(buyPrice());
        double pct = prev <= 0 ? 0 : (k.price / prev - 1) * 100;
        if (Math.abs(pct) < 0.05) return Lang.raw("stock.flat");
        return Lang.raw(pct > 0 ? "stock.up" : "stock.down", "pct", String.format(Locale.ROOT, "%.1f", Math.abs(pct)));
    }

    private static String pl(double value, double cost) {
        double gain = value - cost, pct = cost <= 0 ? 0 : (value / cost - 1) * 100;
        boolean flat = Math.abs(pct) < 0.05 && Math.abs(gain) < 0.5;
        String sign = flat ? "" : gain > 0 ? "+" : "-";
        return "<" + (flat ? "gray" : gain > 0 ? "#ff5f57" : "#4d9bff") + ">" + sign + money(Math.abs(gain)) + " ("
                + sign + String.format(Locale.ROOT, "%.1f", Math.abs(pct)) + "%)";
    }

    void window(Player p, String period) {
        m.core().menus().open(p, "economy:stock", new Window(period));
    }

    /** The last trades of 1987, the same for everyone: one long fall to the last price. */
    private List<StockGraph.Candle> sinking() {
        List<StockGraph.Candle> out = new ArrayList<>();
        double end = buyPrice(), start = end * 4.97;
        Random r = new Random(1987);
        double prev = start;
        for (int i = 1; i <= 16; i++) {
            double p = Math.exp(Math.log(start) + (Math.log(end) - Math.log(start)) * i / 16.0) * (i == 16 ? 1 : 1 + r.nextGaussian() * 0.03);
            p = Math.min(p, prev * 0.995);
            out.add(new StockGraph.Candle(prev, prev * (1 + r.nextDouble() * 0.02), p * (1 - r.nextDouble() * 0.02), p));
            prev = p;
        }
        return out;
    }

    private final class Window implements MenuHandler {
        private final String period;

        Window(String period) {
            this.period = period;
        }

        private boolean holder(UUID u) {
            return holding(u) && open(u);
        }

        private String graph(Ticker k) {
            List<Ticker.Candle> src = switch (period) {
                case "h24" -> k.h2;
                case "d7" -> k.d1;
                default -> k.m10;
            };
            int slots = switch (period) {
                case "h24" -> 12;
                case "d7" -> 7;
                default -> 16;
            };
            List<StockGraph.Candle> l = new ArrayList<>();
            for (Ticker.Candle c : src) l.add(new StockGraph.Candle(c.open(), c.high(), c.low(), c.close()));
            return StockGraph.title(l.subList(Math.max(0, l.size() - slots), l.size()), slots);
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            long now = System.currentTimeMillis();
            Map<String, String> ph = new HashMap<>();
            ph.put("stars", "★★★");
            ph.put("industry", line("share.industry"));
            ph.put("risk", line("share.risk"));
            ph.put("shares", line("share.shares"));
            ph.put("held", line("share.shares"));
            ph.put("dividend", line("share.dividend-line"));
            ph.put("mydiv", line("share.dividend-line"));
            ph.put("period", Lang.raw("stock.period." + period));
            for (String n : List.of("p10", "p100")) ph.put(n, "-");
            if (!holder(u)) {
                double price = buyPrice();
                ph.put("graph", StockGraph.title(sinking(), 16));
                ph.put("name", line("offer.icon-name"));
                ph.put("price", money(price));
                ph.put("change", Lang.raw("stock.down", "pct", "79.9"));
                ph.put("high", money(price));
                ph.put("low", money(price));
                ph.put("close", money(price));
                ph.put("pl", "<gray>-");
                ph.put("have", "0");
                ph.put("sellable", "0");
                ph.put("value", money(0));
                ph.put("profit", money(0));
                ph.put("sign", "");
                ph.put("p1", money(price));
                ph.put("fee", line("share.offer-fee"));
                ph.put("halted", Lang.raw("stock.icon-halted"));
                ph.put("news", line("share.news.offer"));
                return ph;
            }
            Ticker k = ticker(u);
            boolean halted = k.halted(now);
            double lo = k.price, hi = k.price;
            if (!k.d1.isEmpty() && k.d1.getLast().t() == k.clock.dayStart(now)) {
                lo = k.d1.getLast().low();
                hi = k.d1.getLast().high();
            }
            ph.put("graph", graph(k));
            ph.put("name", line(halted ? "share.halted-name" : "share.name"));
            ph.put("price", money(k.price));
            ph.put("change", change(k));
            ph.put("high", money(hi));
            ph.put("low", money(lo));
            ph.put("close", money(k.prevClose(buyPrice())));
            ph.put("pl", pl(k.price, buyPrice()));
            ph.put("have", "1");
            ph.put("sellable", halted ? "0" : "1");
            ph.put("value", money(k.price));
            ph.put("profit", money(Math.abs(k.price - buyPrice())));
            ph.put("sign", k.price >= buyPrice() ? "+" : "-");
            ph.put("p1", line("share.no-more"));
            ph.put("fee", line("share.fee"));
            ph.put("halted", halted ? Lang.raw("stock.icon-halted") : "");
            int st = m.stage(Dongbang.this, u);
            ph.put("news", line(st >= 3 ? "share.news.convened" : st == 2 ? "share.news.notice" : "share.news.held"));
            return ph;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            UUID u = v.player().getUniqueId();
            boolean holder = holder(u);
            String a = b.action();
            if (a.equals("stock:buy:10") || a.equals("stock:buy:100") || a.equals("stock:sell:10") || a.equals("stock:sell:100")) return null;
            if (!holder && (a.equals("stock:sell:1") || a.equals("stock:sell:all"))) return null;
            if (holder && b.key() == 'I') {
                // the info button also lists the midnight closes (the address is in them)
                Ticker k = ticker(u);
                if (!k.closes.isEmpty()) base.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(Text.item(line("share.closes-title")));
                    for (Ticker.Close c : k.closes) {
                        lore.add(Text.item(line("share.close-line").replace("{date}", closeDate(k, c.at())).replace("{color}", c.up() ? "#ff5f57" : "#4d9bff")
                                .replace("{price}", money(c.price())).replace("{arrow}", c.up() ? "▲" : "▼")));
                    }
                    meta.lore(lore);
                });
            }
            return base;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            UUID u = p.getUniqueId();
            if (action.startsWith("stock:period:")) window(p, action.substring(13));
            else if (action.equals("stock:back")) eco.stocks().open(p);
            else if (action.equals("stock:buy:1")) {
                if (holder(u)) {
                    HudModule.flash(p, Text.mm(line("share.no-more")));
                    return;
                }
                buy(p);
            } else if (action.equals("stock:sell:1") || action.equals("stock:sell:all")) {
                if (holder(u)) sell(p);
            }
        }
    }

    private static String closeDate(Ticker k, long midnight) {
        if (k.clock.test()) return "T" + (midnight / k.clock.length());
        return DateTimeFormatter.ofPattern("M/d").format(Instant.ofEpochMilli(midnight - 1).atZone(SecretModule.KST));
    }

    private void buy(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (m.stage(this, u) != 0 || sold(u) || !open(u)) return;
        if (!m.test(u) && offered.getOrDefault(u, 0L) < now) {
            p.closeInventory();
            p.sendMessage(Text.mm(line("share.too-late")));
            return;
        }
        long price = buyPrice();
        if (!m.core().economy().has(u, price) || !m.core().economy().withdraw(u, price, "secret")) {
            HudModule.flash(p, Text.mm(line("share.not-enough")));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            return;
        }
        begin(p);
        p.closeInventory();
        p.sendMessage(Text.msg(line("share.bought").replace("{price}", money(price))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.6f);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) {
                m.haunt().whisper(p, line("share.bought-whisper"));
                m.haunt().behind(p, Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 0.8f, 0.7f);
            }
        }, 60L);
    }

    private void sell(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Ticker k = ticker(u);
        if (k.halted(now)) {
            HudModule.flash(p, Text.mm(line("share.halted")));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.6f, 0.6f);
            return;
        }
        if (sellConfirm.getOrDefault(u, 0L) < now) {
            sellConfirm.put(u, now + 5000);
            HudModule.flash(p, Text.mm(line("share.sell-confirm")));
            return;
        }
        long amount = Math.max(0, Math.round(k.price));
        cleanup(u);
        wipe(u);
        m.stage(this, u, 0);
        m.set(this, u, "sold", 1);
        m.set(this, u, "sold-at", now);
        if (amount > 0) m.core().economy().deposit(u, amount, "secret");
        p.closeInventory();
        p.sendMessage(Text.msg(line("share.sold").replace("{amount}", money(amount))));
        long delay = m.test(u) ? cfg.getLong("sold.test-delay-seconds", 5) : cfg.getLong("sold.delay-seconds", 60);
        Bukkit.getScheduler().runTaskLater(eco, () -> eco.mail().store().send(u, Items.bytes(letter()), "dongbang", System.currentTimeMillis()),
                Math.max(1, delay) * 20L);
    }

    // ------------------------------------------------------------------------------------------
    // /주식 뉴스: their copy only (NewsWarp); the stored news and the prices are never touched
    // ------------------------------------------------------------------------------------------

    /** As many lines as /주식 뉴스 shows. */
    private static final int NEWS_LINES = 10;

    /** They saw the offer row and it is still theirs to buy (the minute, then the grace). */
    private boolean offering(UUID u, long now) {
        return m.stage(this, u) == 0 && !sold(u) && open(u) && offered.getOrDefault(u, 0L) > now;
    }

    private boolean newsOn() {
        return on() && cfg.isConfigurationSection("news");
    }

    /** The same page for the whole offer: one seed per player and day. */
    private long newsSeed(UUID u) {
        return u.getMostSignificantBits() ^ Long.rotateLeft(u.getLeastSignificantBits(), 13) ^ m.today(u).hashCode() * 0x1987L;
    }

    private NewsWarp.Offer offerNews() {
        return new NewsWarp.Offer(line("news.offer.head"), line("news.offer.fragment"), line("news.offer.stamp"), line("news.offer.word"),
                Math.max(0, cfg.getInt("news.offer.soaked", 2)), Math.max(0, cfg.getInt("news.offer.thirteen", 1)), NEWS_LINES);
    }

    /** One line of a list (null when it is empty or missing). */
    private String pick(String path) {
        List<String> l = lines(path);
        return l.isEmpty() ? null : l.get(rnd.nextInt(l.size()));
    }

    /** The held stage's headline pool (1 when not holding, for previews). */
    private String heldNews(int stage) {
        return pick("news.held.stage-" + Math.max(1, Math.min(stages(), stage)));
    }

    private List<String> newsView(Player p, List<String> real) {
        if (!newsOn()) return real;
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (offering(u, now)) return NewsWarp.offer(real, offerNews(), newsSeed(u));
        int st = m.stage(this, u);
        if (holding(u) && open(u)) {
            if (!m.roll(u, cfg.getDouble("news.held.chance", 0.15))) return real;
            String l = heldNews(st);
            return l == null ? real : NewsWarp.among(real, l, rnd, NEWS_LINES);
        }
        // the last word, once: the company is wound up, or its last shareholder left
        String end = st == stages() + 1 ? line("news.solved") : st == 0 && sold(u) ? line("news.sold") : "";
        if (end.isEmpty() || m.get(this, u, "news-end") != null) return real;
        m.set(this, u, "news-end", 1);
        return NewsWarp.last(real, end, NEWS_LINES);
    }

    /** During the offer, other companies' windows show their latest news at 03:33 too, now and then soaked. */
    private String companyNewsView(Player p, String line) {
        if (!newsOn() || !offering(p.getUniqueId(), System.currentTimeMillis())) return line;
        Random r = new Random(newsSeed(p.getUniqueId()) ^ line.hashCode());
        String l = r.nextInt(3) == 0 ? NewsWarp.soak(line, line("news.offer.word"), r) : line;
        return line("news.offer.stamp") + l;
    }

    /**
     * A real news went out to everyone. Someone in the offer gets one more "[뉴스]" line a moment later (once an offer);
     * a holder now and then gets one of their stage's headlines (once a day at most).
     */
    private void newsEcho() {
        if (!newsOn()) return;
        long now = System.currentTimeMillis();
        long delay = Math.max(1, cfg.getLong("news.echo-delay-seconds", 3)) * 20L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            String today = m.today(u);
            String l = null;
            if (offering(u, now)) {
                if (today.equals(echoed.get(u))) continue;
                l = line("news.offer.echo");
                if (!l.isEmpty()) echoed.put(u, today);
            } else if (holding(u) && open(u)) {
                if (today.equals(m.get(this, u, "news-echo")) || !m.roll(u, cfg.getDouble("news.held.echo-chance", 0.1))) continue;
                l = heldNews(m.stage(this, u));
                if (l != null) m.set(this, u, "news-echo", today);
            }
            if (l == null || l.isEmpty()) continue;
            String send = l;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) p.sendMessage(Lang.msg("stock.news", "line", send));
            }, delay);
        }
    }

    /** Admin preview: the list as that state would show it (nothing is saved, the chance is 100%). */
    private void previewNews(Player p, String word) {
        if (eco.stocks() == null) return;
        List<String> real = eco.stocks().recentNews();
        List<String> view = switch (word) {
            case "뉴스" -> NewsWarp.offer(real, offerNews(), newsSeed(p.getUniqueId()));
            case "뉴스청산" -> NewsWarp.last(real, line("news.solved"), NEWS_LINES);
            case "뉴스매도" -> NewsWarp.last(real, line("news.sold"), NEWS_LINES);
            default -> {
                String l = heldNews(word.charAt(word.length() - 1) - '0');
                yield l == null ? real : NewsWarp.among(real, l, rnd, NEWS_LINES);
            }
        };
        eco.stocks().showNews(p, view);
        if (word.equals("뉴스") && !line("news.offer.echo").isEmpty()) {
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) p.sendMessage(Lang.msg("stock.news", "line", line("news.offer.echo")));
            }, Math.max(1, cfg.getLong("news.echo-delay-seconds", 3)) * 20L);
        }
    }

    // ------------------------------------------------------------------------------------------
    // admin scenes
    // ------------------------------------------------------------------------------------------

    @Override
    public List<String> scenes() {
        return List.of("목록", "창", "통지서", "편지", "속삭임", "자정", "회의실", "착석", "찬성", "반대", "증서", "배당", "뉴스", "뉴스1", "뉴스2", "뉴스3", "뉴스청산", "뉴스매도");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        switch (word) {
            case "목록" -> {
                forced.put(u, now + 60_000L);
                eco.stocks().open(p);
            }
            case "창" -> {
                forced.put(u, now + 60_000L);
                offered.put(u, now + 60_000L);
                window(p, "m10");
            }
            case "통지서" -> {
                Ticker.Clock c = new Ticker.Clock(m.test(u), m.day(u));
                Ticker k = new Ticker(c, rules(c), buyPrice(), now);
                k.notice(now, (int) m.getLong(this, u, "x", 412), (int) m.getLong(this, u, "z", -1207));
                p.getInventory().addItem(noticeItem(k, now));
            }
            case "편지" -> p.getInventory().addItem(letter());
            case "속삭임" -> haunt(p, true);
            case "자정" -> midnight(p);
            case "회의실", "착석", "찬성", "반대" -> {
                Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(12));
                at.setY(at.getWorld().getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES));
                Boardroom r = room(p, new Location(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ()), true);
                if (!word.equals("회의실")) Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (!r.alive()) return;
                    r.sit();
                    if (!word.equals("착석")) Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (r.alive()) r.vote(word.equals("찬성"));
                    }, 60L);
                }, 20L);
            }
            case "증서" -> p.getInventory().addItem(certificate(p));
            case "뉴스", "뉴스1", "뉴스2", "뉴스3", "뉴스청산", "뉴스매도" -> previewNews(p, word);
            case "배당" -> p.sendMessage(Text.msg(line("dividend.message").replace("{amount}", money(cfg.getLong("dividend.amount", 5000)))
                    + " <dark_gray>[연출: 지급 안 함]"));
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.msg("<gray>[연출] " + name() + " " + word + " → " + p.getName()));
        return true;
    }
}
