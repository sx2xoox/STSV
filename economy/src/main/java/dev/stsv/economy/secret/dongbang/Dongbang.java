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
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 *     <li>보유: rarely (offer.chance a day), for one minute while they are on, a grey "동방해운 (거래정지)" sits at the
 *     bottom of a player's /주식 list (a ghost news line in chat says so). They buy its one share; its price then follows
 *     them only (up while online, down while away, swinging by water).</li>
 *     <li>교신: after ~30 minutes of play holding it, a soaked 주주총회 소집 통지서 comes to the mailbox: the 본사 address is
 *     동방호's last radio log, six numbered lines spread over the notice, their news, the company window and its chart
 *     ({@link Radio}).</li>
 *     <li>총회: at the 본사 a wet boardroom only they see waits ({@link Boardroom}). Twelve faceless shareholders each say
 *     one line; one of them was never aboard ({@link Inquest}). Naming the right one frees the eleven (solved); a wrong
 *     name floods the room (shut until the next day, with new lines next time).</li>
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
    /** The day's one minute is up for them until then (ms). */
    private final Map<UUID, Long> offerUntil = new HashMap<>();
    /** Online minutes today toward the day's offer minute: day key → minutes (only for players whose day it is). */
    private final Map<UUID, Map.Entry<String, Integer>> offerClock = new HashMap<>();
    /** Holding seconds online toward the notice (saved now and then as "held-s"). */
    private final Map<UUID, Long> held = new HashMap<>();
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

    /** 1 보유 · 2 교신 (notice in hand, finding the 본사) · 3 총회 (entered the boardroom) (4 = solved). */
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

    private Ticker.Rules rules() {
        return new Ticker.Rules(cfg.getDouble("price.online", 0.005), cfg.getDouble("price.offline", 0.003), cfg.getDouble("price.noise", 0.004),
                cfg.getDouble("price.water", 0.05), cfg.getDouble("price.min", 100), cfg.getDouble("price.max", 20_000));
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
        k = new Ticker(clock, rules(), price, m.getLong(this, u, "t", now));
        Ticker.decode(m.get(this, u, "m10"), k.m10);
        Ticker.decode(m.get(this, u, "h2"), k.h2);
        Ticker.decode(m.get(this, u, "d1"), k.d1);
        Ticker.decodeCloses(m.get(this, u, "closes"), k.closes);
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
        Ticker k = new Ticker(clock, rules(), buyPrice(), now);
        k.record(now, buyPrice());
        tickers.put(u, k);
        save(u, k);
        pick(u, () -> {});
    }

    /** Forgets the share (not the stage). */
    private void wipe(UUID u) {
        cleanup(u);
        held.remove(u);
        for (String k : List.of("price", "t", "m10", "h2", "d1", "closes", "bought", "held-s", "notice-at", "found", "news-n", "run", "names",
                "locked-until", "sold", "sold-at", "x", "z", "news-end", "news-echo", "offer-min", "offer-day",
                "kx", "p0", "lost-day"))
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
            notice(p, System.currentTimeMillis());
            if (stage < 3) return;
            // 3: every line of the log is known, the notice is in hand; the room opens at the 본사
            m.set(this, u, "found", 0x3f);
            m.stage(this, u, 3);
            p.getInventory().addItem(noticeItem(u));
        });
    }

    @Override
    public void cleanup(UUID u) {
        Ticker k = tickers.remove(u);
        if (k != null && holding(u)) save(u, k);
        Boardroom r = rooms.remove(u);
        if (r != null) r.teardown();
        Long h = held.remove(u);
        if (h != null && m.stage(this, u) == 1) m.set(this, u, "held-s", h);
        offered.remove(u);
        forced.remove(u);
        offerUntil.remove(u);
        offerClock.remove(u);
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
            out.add("주가 " + (k != null ? money(k.price) : money(parse(m.get(this, u, "price")))));
            if (st == 1) out.add("통지서까지 " + Math.max(0, noticeAfter(u) - heldSeconds(u)) + "초 (접속한 채로)");
            if (st >= 2) {
                int found = (int) m.getLong(this, u, "found", 0);
                out.add("교신 " + Radio.count(found) + "/6 (" + Radio.list(found) + ")");
                Inquest q = inquest(u);
                String names = m.get(this, u, "names");
                String who = names == null ? "" : " " + names.split("\n", -1)[q.liar];
                out.add("<yellow>정답 " + seatLabel(q.liar) + who + "</yellow> · 시도 " + (m.getLong(this, u, "run", 0) + 1) + "번째");
                long lock = m.getLong(this, u, "locked-until", 0);
                if (lock > System.currentTimeMillis()) out.add("회의실 닫힘 (" + when(lock) + "까지)");
            }
        }
        if (st > stages()) out.add(m.getLong(this, u, "test-solved", 0) != 0 ? "테스트로 풂 (배당 없음)" : "배당 마지막 " + m.get(this, u, "div-day", "-"));
        return String.join(" · ", out);
    }

    /** "서쪽 줄 3번째 (3번 자리)": rows are counted from the door. */
    static String seatLabel(int seat) {
        return (Inquest.row(seat) == 0 ? "서쪽" : "동쪽") + " 줄 문에서 " + (seat % Inquest.ROW + 1) + "번째 (" + (seat + 1) + "번 자리)";
    }

    private static double parse(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String when(long at) {
        long s = (at - System.currentTimeMillis()) / 1000;
        if (s < 3600) return s + "초 뒤";
        return DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(at).atZone(SecretModule.KST));
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
            if (st == 0) {
                if (newMinute) offerClock(p, now);
                continue;
            }
            if (st < 1 || st > stages() || !open(u)) continue;
            Ticker k = ticker(u);
            long bucket = k.m10.isEmpty() ? 0 : k.m10.getLast().t();
            boolean wet = wet(p);
            List<Ticker.Close> crossed = k.advance(now, wet ? Ticker.Mood.WATER : Ticker.Mood.ONLINE, rnd);
            if (!crossed.isEmpty() || (!k.m10.isEmpty() && k.m10.getLast().t() != bucket)) save(u, k);
            if (st == 1) {
                long h = heldSeconds(u) + 1;
                held.put(u, h);
                if (h % 15 == 0) m.set(this, u, "held-s", h);
                if (h >= noticeAfter(u)) pick(u, () -> {
                    if (p.isOnline() && m.stage(this, u) == 1) notice(p, System.currentTimeMillis());
                });
            } else {
                meet(p);
            }
            if (newMinute) haunt(p, wet);
        }
    }

    /** Seconds held while online (toward the notice). */
    private long heldSeconds(UUID u) {
        Long h = held.get(u);
        return h != null ? h : m.getLong(this, u, "held-s", 0);
    }

    /** Online seconds holding the share before the notice comes. */
    private long noticeAfter(UUID u) {
        return m.test(u) ? Math.max(1, cfg.getLong("notice.test-after-seconds", 20)) : Math.max(1, cfg.getLong("notice.after-minutes", 30)) * 60L;
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

    /** The notice comes to the mailbox: stage 2, and the first line of the radio log is theirs. */
    void notice(Player p, long now) {
        UUID u = p.getUniqueId();
        m.set(this, u, "notice-at", now);
        m.remove(this, u, "held-s");
        held.remove(u);
        m.stage(this, u, 2);
        found(u, 1);
        eco.mail().store().send(u, Items.bytes(noticeItem(u)), "dongbang", now);
        p.sendMessage(Text.msg(line("notice.arrived")));
        m.haunt().behind(p, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.6f);
    }

    ItemStack noticeItem(UUID u) {
        String first = radio(u).getFirst();
        List<String> lore = new ArrayList<>();
        for (String l : lines("notice.lore")) lore.add(l.replace("{fragment}", first));
        return paper(NOTICE, line("notice.name"), lore);
    }

    // ------------------------------------------------------------------------------------------
    // the radio log (마지막 교신)
    // ------------------------------------------------------------------------------------------

    /** Their six lines (the 본사 if chosen; an example address otherwise, for previews). */
    List<String> radio(UUID u) {
        int x = (int) m.getLong(this, u, "x", -1352), z = (int) m.getLong(this, u, "z", 392);
        Radio.Words w = new Radio.Words(cfg.getString("radio.east", "해 뜨는 쪽"), cfg.getString("radio.west", "해 지는 쪽"),
                cfg.getString("radio.south", "남"), cfg.getString("radio.north", "북"));
        return Radio.lines(x, z, lines("radio.lines"), w);
    }

    /** They have seen line n (1..6). */
    private void found(UUID u, int n) {
        int f = (int) m.getLong(this, u, "found", 0), g = Radio.mark(f, n);
        if (g != f) m.set(this, u, "found", g);
    }

    /** The next news line of the log for them (2 → 4 → 6 → 2 …), marked seen. */
    private String nextNewsLine(UUID u) {
        long n = m.getLong(this, u, "news-n", 0);
        m.set(this, u, "news-n", n + 1);
        int line = Radio.NEWS[(int) Math.floorMod(n + (u.getLeastSignificantBits() & 0xff), Radio.NEWS.length)];
        found(u, line);
        return radio(u).get(line - 1);
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
        if (m.getLong(this, u, "locked-until", 0) > System.currentTimeMillis()) {
            if (rnd.nextInt(20) == 0) m.haunt().whisper(p, line("meeting.closed-today"));
            return;
        }
        room(p, hq, false);
    }

    Boardroom room(Player p, Location center, boolean preview) {
        UUID u = p.getUniqueId();
        Boardroom old = rooms.remove(u);
        if (old != null) old.teardown();
        List<String> names = new ArrayList<>();
        for (String n : names(u, !preview)) names.add(MiniMessage.miniMessage().escapeTags(n));
        Boardroom r = new Boardroom(this, p, center, preview, inquest(u), names);
        rooms.put(u, r);
        r.build();
        return r;
    }

    /** This run's puzzle: the same until a wrong answer (kv "run" counts those). */
    Inquest inquest(UUID u) {
        long run = m.getLong(this, u, "run", 0);
        long seed = u.getMostSignificantBits() * 31 ^ u.getLeastSignificantBits() ^ (run + 1) * 0x9E3779B97F4A7C15L ^ 0x1987L;
        return Inquest.make(seed, new Inquest.Sizes(lines("meeting.testimony.fact-true").size(), lines("meeting.testimony.fact-false").size()));
    }

    /** The testimony wordings, by kind key. */
    Map<String, List<String>> testimonies() {
        Map<String, List<String>> out = new HashMap<>();
        var sec = cfg.getConfigurationSection("meeting.testimony");
        if (sec != null) for (String k : sec.getKeys(false)) out.put(k, sec.getStringList(k));
        return out;
    }

    /**
     * The twelve names on the chairs (plain text): the twelve most recently seen players other than them, kept for the
     * whole run (kv "names") so the lines keep pointing at the same people; "주주 n번" for the empty places.
     */
    List<String> names(UUID u, boolean keep) {
        String saved = m.get(this, u, "names");
        if (saved != null) {
            String[] w = saved.split("\n", -1);
            if (w.length == Inquest.SEATS) return List.of(w);
        }
        List<OfflinePlayer> all = new ArrayList<>(List.of(Bukkit.getOfflinePlayers()));
        all.removeIf(o -> o.getUniqueId().equals(u) || o.getName() == null);
        all.sort(Comparator.comparingLong(OfflinePlayer::getLastSeen).reversed());
        List<String> out = new ArrayList<>();
        for (OfflinePlayer o : all) {
            if (out.size() == Inquest.SEATS) break;
            out.add(o.getName());
        }
        String empty = cfg.getString("meeting.empty-name", "주주 {seat}번");
        while (out.size() < Inquest.SEATS) out.add(empty.replace("{seat}", String.valueOf(out.size() + 1)));
        if (keep) m.set(this, u, "names", String.join("\n", out));
        return out;
    }

    /** The room says it is gone (teardown). */
    void gone(Boardroom r) {
        rooms.remove(r.viewer(), r);
    }

    /** First step into the real room: the meeting is in session (stage 3). */
    void entered(Player p) {
        UUID u = p.getUniqueId();
        if (m.stage(this, u) != 2) return;
        m.stage(this, u, 3);
        m.haunt().sound(p, p.getLocation(), Sound.EVENT_RAID_HORN, 0.4f, 0.5f);
    }

    /** Named the right one: the end. */
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

    /** Named the wrong one: the room is shut until the next day (test: a few minutes), and the next run is a new puzzle. */
    void lost(Player p, boolean preview) {
        if (preview) return;
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        long until = m.test(u) ? now + Math.max(1, cfg.getLong("meeting.test-retry-minutes", 10)) * 60_000L
                : new Ticker.Clock(false, 0).nextMidnight(now);
        m.set(this, u, "locked-until", until);
        m.set(this, u, "run", m.getLong(this, u, "run", 0) + 1);
        m.remove(this, u, "names");
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
        if (r != null && r.clicked(null)) e.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Boardroom r = rooms.get(e.getPlayer().getUniqueId());
        if (r != null && r.seatOf(e.getRightClicked()) >= 0) {
            e.setCancelled(true);
            r.clicked(e.getRightClicked());
        }
    }

    @EventHandler
    public void onMotionClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Boardroom.Motion b)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Boardroom r = rooms.get(b.viewer());
        if (r == null) {
            e.getWhoClicked().closeInventory();
            return;
        }
        if (e.getRawSlot() == Boardroom.Motion.YES) r.accuse(b.seat);
        else if (e.getRawSlot() == Boardroom.Motion.NO) e.getWhoClicked().closeInventory();
    }

    @EventHandler
    public void onMotionDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Boardroom.Motion) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------
    // /주식: the row and the window
    // ------------------------------------------------------------------------------------------

    /** Is the row up for them now: their one minute today (when the day's roll came up), a preview, or test mode. */
    private boolean offerNow(UUID u, long now) {
        return m.test(u) || forced.getOrDefault(u, 0L) > now || offerUntil.getOrDefault(u, 0L) > now;
    }

    /**
     * The day's roll, the same all day for a player: whether today is their day (offer.chance) and after how many minutes
     * online (offer.online-minutes [from, to]) the row comes up. Null when today is not their day.
     */
    private Integer offerMinute(UUID u, String day) {
        Random r = new Random(u.getMostSignificantBits() * 31 + u.getLeastSignificantBits() + day.hashCode() * 1_000_003L);
        if (r.nextDouble() >= cfg.getDouble("offer.chance", 0.03)) return null;
        List<Integer> span = cfg.getIntegerList("offer.online-minutes");
        int from = span.size() > 0 ? Math.max(0, span.get(0)) : 5, to = span.size() > 1 ? Math.max(from, span.get(1)) : 120;
        return from + r.nextInt(to - from + 1);
    }

    /** Each new minute for a player who never held it: count their minutes online on their day, and raise the row. */
    private void offerClock(Player p, long now) {
        UUID u = p.getUniqueId();
        if (m.test(u) || sold(u) || !open(u)) return;
        String day = m.today(u);
        if (day.equals(m.get(this, u, "offer-day"))) return;          // had today's already
        Integer at = offerMinute(u, day);
        if (at == null) return;
        Map.Entry<String, Integer> c = offerClock.get(u);
        int minutes;
        if (c != null && c.getKey().equals(day)) minutes = c.getValue() + 1;
        else {
            // a relog the same day goes on counting from what was saved
            String saved = m.get(this, u, "offer-min", "");
            minutes = saved.startsWith(day + ":") ? Integer.parseInt(saved.substring(day.length() + 1)) + 1 : 1;
        }
        offerClock.put(u, Map.entry(day, minutes));
        if (minutes % 5 == 0) m.set(this, u, "offer-min", day + ":" + minutes);
        if (minutes < at) return;
        m.set(this, u, "offer-day", day);
        m.remove(this, u, "offer-min");
        offerClock.remove(u);
        offerUntil.put(u, now + Math.max(1, cfg.getLong("offer.minutes", 1)) * 60_000L);
        cue(p);
    }

    /** The minute begins: a ghost "[뉴스]" line in their chat and a far horn, pointing at /주식. */
    private void cue(Player p) {
        String l = line("offer.cue");
        if (!l.isEmpty()) p.sendMessage(Lang.msg("stock.news", "line", l));
        m.haunt().sound(p, p.getLocation().add(p.getLocation().getDirection().multiply(-40)), Sound.EVENT_RAID_HORN, 0.35f, 0.5f);
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
            return icon(line("share.name"), lore);
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
                .replace("{halted}", "");
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
        UUID u = p.getUniqueId();
        if (holding(u) && open(u) && m.stage(this, u) >= 2) {
            // the window's info carries line 3, its "어제" (the chart's close) line 5
            found(u, 3);
            found(u, 5);
        }
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
            int st = m.stage(Dongbang.this, u);
            List<String> log = st >= 2 ? radio(u) : null;
            double lo = k.price, hi = k.price;
            if (!k.d1.isEmpty() && k.d1.getLast().t() == k.clock.dayStart(now)) {
                lo = k.d1.getLast().low();
                hi = k.d1.getLast().high();
            }
            ph.put("graph", graph(k));
            ph.put("name", line("share.name"));
            ph.put("price", money(k.price));
            ph.put("change", change(k));
            ph.put("high", money(hi));
            ph.put("low", money(lo));
            // from the notice on, "어제" always reads |z|: line 5 of the radio log
            ph.put("close", log != null ? log.get(4) : money(k.prevClose(buyPrice())));
            ph.put("pl", pl(k.price, buyPrice()));
            ph.put("have", "1");
            ph.put("sellable", "1");
            ph.put("value", money(k.price));
            ph.put("profit", money(Math.abs(k.price - buyPrice())));
            ph.put("sign", k.price >= buyPrice() ? "+" : "-");
            ph.put("p1", line("share.no-more"));
            ph.put("fee", line("share.fee"));
            ph.put("halted", "");
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
            if (holder && b.key() == 'I' && m.stage(Dongbang.this, u) >= 2) {
                // the info button carries line 3 of the radio log, as if written on the company's papers
                String l3 = radio(u).get(2);
                base.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(Component.empty());
                    lore.add(Text.item(l3));
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
            List<String> out = real;
            // from the notice on, every opening carries the next news line of the radio log (2 → 4 → 6 → 2 …)
            if (st >= 2) out = NewsWarp.among(out, nextNewsLine(u), rnd, NEWS_LINES);
            if (m.roll(u, cfg.getDouble("news.held.chance", 0.15))) {
                String l = heldNews(st);
                if (l != null) out = NewsWarp.among(out, l, rnd, NEWS_LINES);
            }
            return out;
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
     * a holder now and then gets one of their stage's headlines, or from the notice on the next news line of the radio
     * log (once a day at most).
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
                l = m.stage(this, u) >= 2 ? nextNewsLine(u) : heldNews(m.stage(this, u));
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
                int st = word.charAt(word.length() - 1) - '0';
                List<String> out = st >= 2 ? NewsWarp.among(real, radio(p.getUniqueId()).get(1), rnd, NEWS_LINES) : real;
                String l = heldNews(st);
                yield l == null ? out : NewsWarp.among(out, l, rnd, NEWS_LINES);
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
        return List.of("목록", "창", "통지서", "편지", "속삭임", "교신", "회의실", "착석", "증언", "지목성공", "지목실패", "증서", "배당",
                "뉴스", "뉴스1", "뉴스2", "뉴스3", "뉴스청산", "뉴스매도");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        switch (word) {
            case "목록" -> {
                forced.put(u, now + 60_000L);
                cue(p);
                eco.stocks().open(p);
            }
            case "창" -> {
                forced.put(u, now + 60_000L);
                offered.put(u, now + 60_000L);
                window(p, "m10");
            }
            case "통지서" -> p.getInventory().addItem(noticeItem(u));
            case "편지" -> p.getInventory().addItem(letter());
            case "속삭임" -> haunt(p, true);
            case "교신" -> {
                // the whole log at once, each line with where it is found (nothing is marked seen)
                List<String> log = radio(u);
                String[] where = {"통지서", "뉴스", "회사 창 · 정보", "뉴스", "회사 창 · 어제 종가", "뉴스"};
                p.sendMessage(Text.mm(line("radio.title")));
                for (int i = 0; i < log.size(); i++) p.sendMessage(Text.mm(log.get(i) + " <dark_gray>(" + where[i] + ")"));
            }
            case "회의실", "착석", "증언", "지목성공", "지목실패" -> {
                Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(12));
                at.setY(at.getWorld().getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES));
                Boardroom r = room(p, new Location(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ()), true);
                int liar = r.inquest().liar;
                admin.sendMessage(Text.msg("<gray>[연출] 정답: " + seatLabel(liar) + " " + r.names().get(liar)));
                if (!word.equals("회의실")) Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (!r.alive()) return;
                    r.sit();
                    if (word.equals("증언")) r.sayAll();
                    if (word.startsWith("지목")) Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (r.alive()) r.accuse(word.equals("지목성공") ? liar : (liar + 1 + rnd.nextInt(Inquest.SEATS - 1)) % Inquest.SEATS);
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
