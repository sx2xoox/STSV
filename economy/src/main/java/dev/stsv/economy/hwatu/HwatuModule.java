package dev.stsv.economy.hwatu;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.furniture.FurnitureModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudLayout;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.hwatu.hall.HallBuilder;
import dev.stsv.economy.hwatu.hall.HallLayout;
import dev.stsv.economy.util.Players;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 화투방 · 도박장 (README "도박장"; hwatu.yml, see {@link HwatuSpec} and {@link HallSpec}): players play each other for chips,
 * never the server, at real tables in the 도박장 building ({@link HallBuilder}). Sitting in a table's chair is sitting at its
 * {@link Table} ({@link HallTable}: one a bound table furniture, its game and room kept in {@link HallStore}); what lies on
 * the table is {@link TableScene}. Chips are bought with gold and cashed to gold only (never 별 or anything 후원), are a
 * number on the account (no item), and every pot pays the 자릿세, which is destroyed: the 도박장 only ever takes money out of
 * the economy. A daily buy limit, a daily loss cap and a minimum account age can each be set in hwatu.yml (0 = none, the
 * default), every hand is logged for /관리자 도박장 기록, no winnings ranking. /관리자 도박장 시험: a 시험 판 at a real table
 * with bots and pretend chips.
 *
 * <p>Money: buying holds the gold outside the balances (ESCROW, like shares) and the chips stand for it at the buy price;
 * cashing gives back the cash price (ESCROW) and destroys the rest; the 자릿세 destroys the gold behind its chips (burn).
 * The weekly ranking and the 순자산 achievements ignore chips won or lost ({@link dev.stsv.economy.rank.RankModule#gambled}).
 */
public final class HwatuModule extends EcoModule {

    static final String MENU = "economy:hwatu";
    /** Message keys of what a player had: hwatu.hand.&lt;code&gt; ({@link Sutda.Hand#code()}, odd, even, die). */
    static final String HAND = "hwatu.hand.";
    /** The hall's name (its entities' tag, HallBuilder). One hall. */
    static final String HALL = "main";
    /** The whiteboard text and the 현장 소장 (not saved, drawn again when their chunk is loaded): tag value. */
    private static final String BOARD_TAG = "whiteboard", BOSS_TAG = "counter";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** The board (core HUD, bottom right) of a seated player. */
    private static final String HUD = "eco-hwatu";

    private HwatuSpec spec;
    private HallSpec hall;
    private ChipStore store;
    private HallStore halls;
    private ZoneId zone;
    private final SecureRandom rng = new SecureRandom();
    /** bound tables by their furniture entity */
    private final Map<UUID, HallTable> tables = new LinkedHashMap<>();
    /** who sits at which table (players; a 시험 판's admin too) */
    private final Map<UUID, HallTable> seated = new HashMap<>();
    /** the bots of every 시험 판 and their names (봇1, 봇2 …) */
    private final Map<UUID, String> bots = new HashMap<>();
    private int nextTable = 1;
    private BigPots bigPots;
    private TextDisplay whiteboard;
    private Mannequin boss;
    private HallBuilder job;

    public HwatuModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-hwatu";
    }

    @Override
    protected void start() {
        load();
        store = new ChipStore(core.db());
        store.init();
        halls = new HallStore(core.db());
        halls.init();
        bigPots = BigPots.decode(halls.get(HallStore.RECORDS), hall.boardLines());
        for (HallStore.Binding b : halls.tables()) bindQuiet(b);
        eco.onReset(this::reset);
        core.admin().add(EcoPlugin.ADMIN_OWNER, "도박장",
                "건설 · 제거 · 테이블 <종목> <방> | 해제 | 목록 · 딜러 · 위치 · 창구 · 칠판 · 시험 [인원] | 섯다 패 <카드> <카드> | 끝 · 기록 [플레이어] · 칩 <플레이어> [수]",
                "도박장 짓기 · 테이블 묶기 (보고 있는 테이블) · /화투방 위치 · 혼자 봇과 시험 판 (진짜 칩 · 골드 · 기록 없음) · 판 기록 (세탁 감시) · 칩 보기·정하기",
                this::admin, this::adminComplete);
        purgeStrays();
        if (core.hud() != null) {
            // seated: this table's 족보 over anything shown to everyone (a fishing tournament keeps the others' corner)
            core.hud().board(HUD, HudModule.BOARD_SEATED, p -> seated.containsKey(p.getUniqueId()), this::board);
            core.hud().line(HUD, 0, this::actionLine);
        }
        every(20, this::second);
        every(1, this::frame);
    }

    @Override
    protected void stop() {
        if (job != null && job.running()) job.cancel();
        // a running hand is void: nothing was moved yet (chips move only when a hand ends)
        for (HallTable t : tables.values()) t.close(null);
        tables.clear();
        seated.clear();
        bots.clear();
        removeHallNpcs();
        if (core.hud() != null) {
            core.hud().removeBoard(HUD);
            core.hud().removeLine(HUD);
        }
    }

    @Override
    public void reload() {
        load();
        for (HallTable t : tables.values()) t.spec(spec);
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        var data = eco.data("hwatu.yml");
        spec = HwatuSpec.parse(data, problems);
        hall = HallSpec.parse(data.getConfigurationSection("hall"), problems);
        zone = ZoneId.of(eco.getConfig().getString("time.zone"));
        problems.forEach(p -> eco.getLogger().warning("[hwatu.yml] " + p));
    }

    // ------------------------------------------------------------------------------------------ what HallTable uses

    public HwatuSpec spec() {
        return spec;
    }

    HallSpec hall() {
        return hall;
    }

    RandomGenerator rng() {
        return rng;
    }

    int nextTableId() {
        return nextTable++;
    }

    Plugin plugin() {
        return eco;
    }

    FurnitureModule furniture() {
        return core.furniture();
    }

    boolean isBot(UUID u) {
        return bots.containsKey(u);
    }

    void addBot(UUID u, String name) {
        bots.put(u, name);
    }

    void removeBot(UUID u) {
        bots.remove(u);
    }

    void seat(UUID u, HallTable t) {
        seated.put(u, t);
    }

    void unseat(UUID u, HallTable t) {
        seated.remove(u, t);
    }

    HallTable bound(UUID furniture) {
        return tables.get(furniture);
    }

    /** A bound table's furniture turned up under a new entity id (placed again where it stood). */
    void moved(UUID from, HallTable t) {
        tables.remove(from);
        tables.put(t.furniture(), t);
        halls.moved(from, t.binding());
        eco.getLogger().info("[도박장] 테이블을 다시 찾음: " + from + " → " + t.furniture());
    }

    void refreshBoard() {
        if (core.hud() != null) core.hud().refreshBoard();
    }

    private int today() {
        return eco.calendar().day();
    }

    /** The gold a player's chips stand for (the buy price): part of their net worth. */
    public long worth(UUID player) {
        return store == null || spec == null ? 0 : store.account(player, today()).chips * spec.buyPrice();
    }

    public long chips(UUID player) {
        return store.account(player, today()).chips;
    }

    /** 바카라: the chips a player bet today (baccarat.daily-wager-cap). */
    long wageredToday(UUID player) {
        return store.account(player, today()).wagered;
    }

    /** Where a 바카라 table's dealer stands (set by the hall's builder or /관리자 도박장 딜러), or null: behind its flat edge. */
    Location dealerSpot(HallTable t) {
        return place(halls.get(DEALER + t.furniture()));
    }

    /** 바카라 road of a table (the last results survive a restart): hall setting key. */
    private static final String DEALER = "dealer:", ROAD = "road:";

    /** Chips not held in a running hand (a 시험 판 holds none: its chips are pretend). */
    long available(UUID player) {
        HallTable t = seated.get(player);
        return chips(player) - (t == null || t.testing() ? 0 : t.table().committed(player));
    }

    private void reset() {
        for (HallTable t : tables.values()) t.close(null);
        seated.clear();
        // the chips' gold goes back (a test reset must not destroy money held outside the balances)
        for (var e : store.holders()) core.economy().move(null, e.getKey(), e.getValue() * spec.buyPrice(), EconomyStore.Kind.ESCROW, "hwatu_reset", null);
        store.reset();
    }

    // ------------------------------------------------------------------------------------------
    // Chips
    // ------------------------------------------------------------------------------------------

    private boolean oldEnough(Player p) {
        return HwatuRules.oldEnough(p.getFirstPlayed(), now(), spec.minAgeMs());
    }

    /** Buys n chips with gold; null on success, else the refusal key. */
    public String buy(Player p, long n) {
        UUID u = p.getUniqueId();
        if (!oldEnough(p)) return "hwatu.too-new";
        ChipStore.Account a = store.account(u, today());
        String why = HwatuRules.canBuy(n, a.bought, spec.dailyBuy(), core.economy().balance(u), spec.buyPrice());
        if (why != null) return why;
        // held, not destroyed (ESCROW): the chips stand for it
        if (!core.economy().move(u, null, n * spec.buyPrice(), EconomyStore.Kind.ESCROW, "hwatu_chip_buy", n + "칩")) return "hwatu.buy-gold";
        store.bought(u, n, today());
        p.sendMessage(Lang.msg("hwatu.bought", "n", fmt(n), "gold", EconomyModule.format(n * spec.buyPrice()), "chips", fmt(chips(u)),
                "left", buyLeftText(a.bought + n)));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);
        refreshBoard();
        return null;
    }

    /** Cashes n chips (all free ones if n &lt; 0) to gold; null on success, else the refusal key. */
    public String cash(Player p, long n) {
        UUID u = p.getUniqueId();
        long free = available(u);
        if (n < 0) n = free;
        String why = HwatuRules.canCash(n, free);
        if (why != null) return why;
        if (!store.cash(u, n, chips(u) - free, today())) return "hwatu.cash-short";
        long gold = n * spec.cashPrice(), fee = n * (spec.buyPrice() - spec.cashPrice());
        core.economy().move(null, u, gold, EconomyStore.Kind.ESCROW, "hwatu_chip_cash", n + "칩");
        if (fee > 0) core.economy().burn(fee, "hwatu_cash_fee", u + " " + n + "칩");
        p.sendMessage(Lang.msg("hwatu.cashed", "n", fmt(n), "gold", EconomyModule.format(gold), "fee", EconomyModule.format(fee), "chips", fmt(chips(u))));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        refreshBoard();
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Hands
    // ------------------------------------------------------------------------------------------

    /** Why a player may not be dealt in (or sit down), null if they may. */
    String refusal(UUID u, long need) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return "hwatu.away";
        ChipStore.Account a = store.account(u, today());
        return HwatuRules.canPlay(p.getFirstPlayed(), now(), spec.minAgeMs(), a.net, spec.lossCap(), a.chips, need);
    }

    /** A real hand ended: the chips move, the ranking leaves it out, the hand is logged, everyone is told. */
    void settle(Table t, Table.Result r) {
        int day = today();
        for (UUID u : r.players()) {
            long delta = r.net(u);
            store.settle(u, delta, day);
            // not earnings, not a loss: the weekly ranking and the 순자산 achievements leave the 도박장 out
            if (eco.rank() != null) eco.rank().gambled(u, delta * spec.buyPrice());
        }
        if (r.burned() > 0) core.economy().burn(r.burned() * spec.buyPrice(), "hwatu_rake", t.game().key() + " " + t.room().id());
        if (r.game() == Table.Game.BACCARAT) {
            // the house: chips it won are destroyed (and the gold behind them); chips it paid out are new (their gold appears
            // only when they are cashed). Each one's bets count against today's cap.
            long house = 0;
            for (UUID u : r.players()) {
                house -= r.net(u);
                store.wagered(u, r.put().getOrDefault(u, 0L), day);
            }
            if (house > 0) core.economy().burn(house * spec.buyPrice(), "hwatu_baccarat_house", t.room().id());
        }
        // the log: who put in what and got what (admin review: who lost to whom)
        StringBuilder d = new StringBuilder();
        for (UUID u : r.players()) {
            if (!d.isEmpty()) d.append(" · ");
            d.append(Names.plain(u)).append(' ').append(r.put().get(u)).append("→").append(r.payout().getOrDefault(u, 0L));
            String what = detail(r, u);
            if (!what.isEmpty()) d.append(" (").append(Text.plain(what)).append(')');
        }
        store.log(now(), t.game().key(), t.room().id(), r.players(), r.pot(), r.burned(), d.toString());
        announce(t, r, this::chips, false);
        if (r.game() == Table.Game.BACCARAT) saveRoad(t);
        for (UUID u : r.players()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && HwatuRules.resting(store.account(u, day).net, spec.lossCap())) p.sendMessage(Lang.msg("hwatu.resting-now", "cap", fmt(spec.lossCap())));
        }
        refreshBoard();
    }

    /** Keeps a 바카라 table's road over a restart. */
    private void saveRoad(Table t) {
        for (HallTable h : tables.values()) if (h.table() == t) halls.set(ROAD + h.furniture(), t.road());
    }

    /** A 바카라 table's stored road ("" if none). */
    String road(HallTable t) {
        String r = halls.get(ROAD + t.furniture());
        return r == null ? "" : r;
    }

    /** Tells everyone in a finished hand who won with what, and their own result (`chipsOf`: what they hold now). */
    void announce(Table t, Table.Result r, java.util.function.ToLongFunction<UUID> chipsOf, boolean test) {
        if (r.game() == Table.Game.BACCARAT) {
            announceBaccarat(t, r, chipsOf, test);
            return;
        }
        // the main pot (with side pots: each one after it, contested only by those who put in that much)
        List<Component> lines = new ArrayList<>();
        List<Table.PotResult> pots = r.pots();
        for (int k = 0; k < pots.size(); k++) {
            Table.PotResult pr = pots.get(k);
            List<String> names = new ArrayList<>();
            for (UUID w : pr.winners()) names.add(mini(w));
            String hand = pr.winners().size() == 1 ? detail(r, pr.winners().getFirst()) : "";
            String players = String.join("<gray>, </gray>", names);
            if (k == 0) lines.add(Lang.msg(r.uncontested() ? "hwatu.won-alone" : pr.winners().size() > 1 ? "hwatu.won-split" : "hwatu.won",
                    "players", players, "hand", hand, "pot", fmt(pots.size() > 1 ? pr.amount() : r.pot()), "share", fmt(pr.share()), "rake", fmt(pots.size() > 1 ? pr.burned() : r.burned())));
            else lines.add(Lang.msg(pr.winners().size() > 1 ? "hwatu.won-side-split" : "hwatu.won-side", "n", k, "players", players, "hand", hand,
                    "pot", fmt(pr.amount()), "share", fmt(pr.share()), "rake", fmt(pr.burned())));
        }
        for (UUID u : r.players()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            for (Component line : lines) p.sendMessage(line);
            if (t.game().bets() && !r.uncontested()) {
                List<String> hands = new ArrayList<>();
                for (UUID o : r.players()) hands.add(mini(o) + " <gray>" + detail(r, o));
                p.sendMessage(Lang.msg("hwatu.hands", "hands", String.join("<dark_gray> · </dark_gray>", hands)));
            }
            long net = r.net(u);
            String key = (test ? "hwatu.test-" : "hwatu.") + (net >= 0 ? "my-win" : "my-loss");
            p.sendMessage(Lang.msg(key, "n", fmt(Math.abs(net)), "chips", fmt(chipsOf.applyAsLong(u))));
            p.playSound(p.getLocation(), r.winners().contains(u) ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, r.winners().contains(u) ? 1.4f : 0.8f);
        }
    }

    /** 바카라's result: the cards and totals, who won, then each player's bets and what came back. */
    private void announceBaccarat(Table t, Table.Result r, java.util.function.ToLongFunction<UUID> chipsOf, boolean test) {
        Baccarat.Coup c = r.coup();
        List<String> pc = new ArrayList<>(), bc = new ArrayList<>();
        for (PCard x : c.player()) pc.add(HallTable.cardText(x));
        for (PCard x : c.banker()) bc.add(HallTable.cardText(x));
        Component line = Lang.msg("hwatu.bac.result", "won", Lang.raw("hwatu.bac.won." + c.winner().key()), "player", c.playerTotal(), "banker", c.bankerTotal(),
                "pcards", String.join(" ", pc), "bcards", String.join(" ", bc), "natural", c.natural() ? Lang.raw("hwatu.bac.natural") : "");
        for (UUID u : r.players()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.sendMessage(line);
            long net = r.net(u);
            String key = (test ? "hwatu.test-" : "hwatu.") + (net >= 0 ? "my-win" : "my-loss");
            p.sendMessage(Lang.msg(key, "n", fmt(Math.abs(net)), "chips", fmt(chipsOf.applyAsLong(u))));
            p.playSound(p.getLocation(), net > 0 ? Sound.ENTITY_PLAYER_LEVELUP : net == 0 ? Sound.BLOCK_NOTE_BLOCK_HAT : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, net > 0 ? 1.4f : 0.8f);
        }
    }

    /** What a player had in a finished hand, as MiniMessage ("" if nothing to say). */
    private String detail(Table.Result r, UUID u) {
        String d = r.detail().get(u);
        if (d == null) return "";
        if (d.equals("die")) return Lang.raw("hwatu.hand.die");
        String hand = HAND + d;
        return switch (r.game()) {
            case POKER -> {
                String[] v = d.split("\\|", 2);
                yield Lang.raw("hwatu.poker-hand." + v[0], "ranks", v.length > 1 ? v[1] : "");
            }
            case BACCARAT -> {
                List<String> on = new ArrayList<>();
                for (Baccarat.Spot sp : Baccarat.Spot.values()) {
                    long n = TableScene.betOf(d, sp);
                    if (n > 0) on.add(Lang.raw("hwatu.bac.line-bet", "spot", Lang.raw("hwatu.bac.spot-short." + sp.key()), "n", fmt(n)));
                }
                yield String.join(" ", on);
            }
            case SUTDA -> Lang.raw(hand);
            case HOLJJAK -> Lang.raw(hand) + (r.die() > 0 ? " " + Lang.raw("hwatu.die-face", "n", r.die()) : "");
            case DICE -> {
                int total = 0;
                for (String x : d.split("\\+")) total += Integer.parseInt(x);
                yield Lang.raw("hwatu.dice", "roll", d, "total", total);
            }
        };
    }

    /** A big pot won (not a 시험 판): the hall hears of it, and it goes on the whiteboard. */
    void bigWin(HallTable t, Table.Result r) {
        UUID w = r.winners().getFirst();
        long won = r.payout().getOrDefault(w, 0L);
        String names = String.join(", ", r.winners().stream().map(this::mini).toList());
        String hand = r.winners().size() == 1 ? detail(r, w) : "";
        String line = Lang.raw("hwatu.whiteboard-line", "players", names, "game", t.gameName(), "room", t.room().name(), "pot", fmt(r.pot()), "hand", hand);
        if (bigPots.add(today(), r.pot(), line)) {
            halls.set(HallStore.RECORDS, bigPots.encode());
            drawWhiteboard();
        }
        Location at = t.furnitureEntity().map(Furniture::location).orElse(null);
        if (at == null) return;
        Component c = Lang.msg("hwatu.big-win", "players", names, "game", t.gameName(), "room", t.room().name(), "share", fmt(won), "hand", hand);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > (double) hall.radius() * hall.radius()) continue;
            p.sendMessage(c);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1f);
        }
    }

    String gameName(Table.Game g) {
        return spec.game(g).name();
    }

    static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** A player's name, or a 시험 판 bot's (봇1 …). */
    String mini(UUID u) {
        String bot = bots.get(u);
        return bot != null ? bot : Names.mini(u);
    }

    /** Chips that can still be bought today, as text ("제한 없음" with no daily limit). */
    private String buyLeftText(long boughtToday) {
        return spec.dailyBuy() <= 0 ? Lang.raw("hwatu.no-limit") : fmt(HwatuRules.buyLeft(boughtToday, spec.dailyBuy()));
    }

    private String dailyBuyText() {
        return spec.dailyBuy() <= 0 ? Lang.raw("hwatu.no-limit") : fmt(spec.dailyBuy());
    }

    // ------------------------------------------------------------------------------------------
    // The tables in the world
    // ------------------------------------------------------------------------------------------

    /** A binding of the store as a playing table (null: its game or room is not in hwatu.yml any more). */
    private HallTable bindQuiet(HallStore.Binding b) {
        Table.Game g = gameOf(b.game());
        HwatuSpec.Room r = roomOf(b.room());
        TableKind k = null;
        Entity e = Bukkit.getEntity(b.furniture());
        if (e instanceof org.bukkit.entity.ItemDisplay d) k = core.furniture().fromDisplay(d).map(f -> TableKind.of(f.item().key().asString())).orElse(null);
        if (k == null && g != null) k = TableKind.forGame(g);   // not loaded now: the kind its game needs
        if (g == null || r == null) {
            eco.getLogger().warning("[도박장] 테이블 묶음의 종목/방이 없음: " + b);
            return null;
        }
        HallTable t = new HallTable(this, b, k, g, r);
        if (g == Table.Game.BACCARAT) t.table().road(road(t));
        tables.put(b.furniture(), t);
        return t;
    }

    /** Binds a table furniture to a game and room (rebinding closes the old game: its hand is void). */
    private HallTable bind(Furniture f, Table.Game g, HwatuSpec.Room r) {
        UUID id = f.display().getUniqueId();
        HallTable old = tables.remove(id);
        if (old != null) old.close("hwatu.table-rebound");
        Location l = f.location();
        HallStore.Binding b = new HallStore.Binding(id, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), g.key(), r.id());
        halls.bind(b);
        return bindQuiet(b);
    }

    private void unbind(UUID furniture, String why) {
        HallTable t = tables.remove(furniture);
        if (t != null) t.close(why);
        halls.unbind(furniture);
    }

    private void second() {
        long now = now();
        for (HallTable t : new ArrayList<>(tables.values())) t.second(now);
        keepHallNpcs();
        smoke();
    }

    private void frame() {
        for (HallTable t : tables.values()) t.frame();
    }

    /** Entities of scenes that are not running (left from a crash, an older version): none of ours is ever saved. */
    private void purgeStrays() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(TableScene.TAG)) {
                    e.remove();
                    n++;
                }
            }
        }
        if (n > 0) eco.getLogger().info("[도박장] 남아 있던 테이블 표시 " + n + "개 치움");
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en.getPersistentDataContainer().has(TableScene.TAG)) en.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onTableClick(FurnitureInteractEvent e) {
        HallTable t = tables.get(e.getFurniture().display().getUniqueId());
        if (t == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.isSneaking() || p.isInsideVehicle()) return;
        HallTable at = seated.get(p.getUniqueId());
        if (at != null && at != t) {
            deny(p, "hwatu.already-seated", t);
            return;
        }
        String why = t.sit(p, e.getFurniture());
        if (why != null) deny(p, why, t);
        else seated.put(p.getUniqueId(), t);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTableBreak(FurnitureBreakEvent e) {
        HallTable t = tables.get(e.getFurniture().display().getUniqueId());
        if (t == null) return;
        Player p = e.getPlayer();
        if (p == null || !p.hasPermission("stsv.economy.admin")) {
            e.setCancelled(true);
            if (p != null) p.sendMessage(Lang.msg("hwatu.table-locked"));
            return;
        }
        // an admin breaks a bound table only on purpose: sneaking and not seated at a table (a missed button click
        // on the felt must never knock the table down)
        if (!p.isSneaking() || seated.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            HudModule.flash(p, Lang.mm("hwatu.table-break-sneak"), 2500);
            return;
        }
        unbind(t.furniture(), "hwatu.table-removed");
        p.sendMessage(Lang.msg("hwatu.table-unbound", "game", t.gameName(), "room", t.room().name()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        HallTable t = seated.get(p.getUniqueId());
        if (t != null) t.stood(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        HallTable t = seated.get(e.getPlayer().getUniqueId());
        if (t != null) t.stood(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            for (HallTable t : tables.values()) t.showTo(p);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onButton(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (boss != null && clicked.equals(boss)) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) openChips(e.getPlayer());
            return;
        }
        if (!(clicked instanceof Interaction)) return;
        String tag = clicked.getPersistentDataContainer().get(TableScene.BUTTON, PersistentDataType.STRING);
        if (tag == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) press(e.getPlayer(), tag);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onButtonHit(PrePlayerAttackEntityEvent e) {
        Entity hit = e.getAttacked();
        if (boss != null && hit.equals(boss)) {
            e.setCancelled(true);
            openChips(e.getPlayer());
            return;
        }
        String tag = hit instanceof Interaction ? hit.getPersistentDataContainer().get(TableScene.BUTTON, PersistentDataType.STRING) : null;
        if (tag == null) return;
        e.setCancelled(true);
        press(e.getPlayer(), tag);
    }

    /** A button's click box was clicked: "viewer;button". Only its viewer can (nobody else is shown it). */
    private void press(Player p, String tag) {
        String[] v = tag.split(";", 2);
        if (v.length != 2 || !v[0].equals(p.getUniqueId().toString())) return;
        Controls.Button b = Controls.Button.of(v[1]);
        HallTable t = seated.get(p.getUniqueId());
        if (b != null && t != null) t.press(p, b);
    }

    private void deny(Player p, String why, HallTable t) {
        ChipStore.Account a = store.account(p.getUniqueId(), today());
        HudModule.flash(p, Lang.mm(why, "need", fmt(t == null ? 0 : t.table().need()), "cap", fmt(spec.lossCap()), "hours", spec.minAgeMs() / 3_600_000L,
                "left", buyLeftText(a.bought), "daily", dailyBuyText(), "chips", fmt(a.chips)));
        p.sendMessage(Lang.msg(why, "need", fmt(t == null ? 0 : t.table().need()), "cap", fmt(spec.lossCap()), "hours", spec.minAgeMs() / 3_600_000L,
                "left", buyLeftText(a.bought), "daily", dailyBuyText(), "chips", fmt(a.chips)));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }

    /** The board (bottom right) of whoever sits at a table: the game's 족보 / rules. */
    private HudLayout.Board board(Player viewer) {
        HallTable t = seated.get(viewer.getUniqueId());
        return t == null ? null : t.board(viewer);
    }

    /** The action bar's line of whoever sits at a table: the hand as it stands for them (null: not seated). */
    private net.kyori.adventure.text.Component actionLine(Player viewer) {
        HallTable t = seated.get(viewer.getUniqueId());
        return t == null ? null : t.actionLine(viewer);
    }

    // ------------------------------------------------------------------------------------------
    // The hall: its warp, the 현장 소장 at the counter, the whiteboard, a little smoke
    // ------------------------------------------------------------------------------------------

    private static String place(Location l) {
        return String.format(Locale.ROOT, "%s;%.3f;%.3f;%.3f;%.1f;%.1f", l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** A stored place (world;x;y;z;yaw;pitch), or null (none, or its world is not loaded). */
    private static Location place(String s) {
        if (s == null) return null;
        String[] v = s.split(";");
        if (v.length < 6) return null;
        World w = Bukkit.getWorld(v[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HallLayout.Site site() {
        String s = halls.get("site");
        if (s == null) return null;
        String[] v = s.split(";");
        try {
            return new HallLayout.Site(v[0], Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3]), Integer.parseInt(v[4]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean loaded(Location l) {
        return l != null && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    /** The 현장 소장 and the whiteboard's text stand while their chunk is loaded (not saved: no copies pile up). */
    private void keepHallNpcs() {
        Location counter = place(halls.get(HallStore.COUNTER));
        if (counter != null && loaded(counter) && (boss == null || !boss.isValid())) boss = spawnBoss(counter);
        if (boss != null && !boss.isValid()) boss = null;
        Location board = place(halls.get(HallStore.BOARD));
        if (board != null && loaded(board) && (whiteboard == null || !whiteboard.isValid())) drawWhiteboard();
        if (whiteboard != null && !whiteboard.isValid()) whiteboard = null;
        if (whiteboard != null && today() != boardDay) drawWhiteboard();   // a new day: an empty board
    }

    private int boardDay = -1;

    private void removeHallNpcs() {
        if (boss != null) boss.remove();
        if (whiteboard != null) whiteboard.remove();
        boss = null;
        whiteboard = null;
    }

    private Mannequin spawnBoss(Location at) {
        return at.getWorld().spawn(at, Mannequin.class, x -> {
            x.setPersistent(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setImmovable(true);
            x.setGravity(false);
            x.setCollidable(false);
            x.setRemoveWhenFarAway(false);
            x.customName(Lang.mm("hwatu.boss-name"));
            x.setCustomNameVisible(true);
            x.setDescription(Lang.mm("hwatu.boss-description"));
            x.getPersistentDataContainer().set(TableScene.TAG, PersistentDataType.STRING, BOSS_TAG);
            String skin = eco.data("hwatu.yml").getString("hall.boss-skin", "economy:entity/bus/site_boss");
            try {
                x.setProfile(ResolvableProfile.resolvableProfile().name("site_boss").skinPatch(sp -> sp.body(Key.key(skin))).build());
            } catch (RuntimeException e) {
                eco.getLogger().warning("[hwatu.yml] hall.boss-skin 키가 잘못됨: " + skin);
            }
        });
    }

    /** 오늘의 큰 판 on the whiteboard. */
    private void drawWhiteboard() {
        Location at = place(halls.get(HallStore.BOARD));
        if (at == null || !loaded(at)) return;
        boardDay = today();
        List<BigPots.Entry> list = bigPots.entries(today());
        StringBuilder b = new StringBuilder(Lang.raw("hwatu.whiteboard-title"));
        if (list.isEmpty()) b.append('\n').append(Lang.raw("hwatu.whiteboard-empty"));
        for (int i = 0; i < list.size(); i++) b.append('\n').append(Lang.raw("hwatu.whiteboard-entry", "n", i + 1, "line", list.get(i).line()));
        Component c = Text.mm(b.toString());
        if (whiteboard != null && whiteboard.isValid()) {
            whiteboard.text(c);
            return;
        }
        whiteboard = at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.text(c);
            t.setBillboard(Display.Billboard.FIXED);
            t.setAlignment(TextDisplay.TextAlignment.LEFT);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(false);
            t.setLineWidth(200);
            // text grows up from its position: down a little so the lines sit in the middle of the writing surface
            t.setTransformation(new Transformation(new Vector3f(0, -0.45f, 0), new Quaternionf(), new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf()));
            t.setViewRange(0.5f);
            t.getPersistentDataContainer().set(TableScene.TAG, PersistentDataType.STRING, BOARD_TAG);
        });
    }

    /** A wisp of 담배연기 now and then over the hall's tables (only while someone is near). */
    private void smoke() {
        for (HallTable t : tables.values()) {
            if (t.seatedPlayers().isEmpty() || rng.nextInt(3) != 0) continue;
            t.furnitureEntity().ifPresent(f -> {
                Location l = f.location().add(rng.nextDouble() - 0.5, 2.2 + rng.nextDouble() * 0.6, rng.nextDouble() - 0.5);
                l.getWorld().spawnParticle(Particle.SMOKE, l, 3, 0.25, 0.1, 0.25, 0.005);
            });
        }
    }

    // ------------------------------------------------------------------------------------------
    // /화투방: to the 도박장 · 칩 · 사기 · 바꾸기 · 나가기
    // ------------------------------------------------------------------------------------------

    /** /화투방 [칩 | 사기 &lt;칩&gt; | 바꾸기 &lt;칩|전부&gt; | 나가기] */
    public void command(Player p, String[] a) {
        if (a.length == 0) {
            goToHall(p);
            return;
        }
        switch (a[0]) {
            case "칩", "전적" -> openChips(p);
            case "사기", "바꾸기" -> {
                if (a.length < 2) {
                    p.sendMessage(Lang.msg("hwatu.usage"));
                    return;
                }
                long n = a[1].equals("전부") && a[0].equals("바꾸기") ? -1 : number(a[1]);
                if (n == 0 || n < -1) {
                    p.sendMessage(Lang.msg("hwatu.bad-amount"));
                    return;
                }
                String why = a[0].equals("사기") ? buy(p, n) : cash(p, n);
                if (why != null) {
                    ChipStore.Account acc = store.account(p.getUniqueId(), today());
                    p.sendMessage(Lang.msg(why, "hours", spec.minAgeMs() / 3_600_000L, "left", buyLeftText(acc.bought),
                            "daily", dailyBuyText(), "need", "0", "cap", fmt(spec.lossCap())));
                }
            }
            case "나가기" -> {
                if (!seated.containsKey(p.getUniqueId())) p.sendMessage(Lang.msg("hwatu.not-seated"));
                else if (p.isInsideVehicle()) p.leaveVehicle();
                else seated.get(p.getUniqueId()).stood(p.getUniqueId());
            }
            default -> p.sendMessage(Lang.msg("hwatu.usage"));
        }
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** /화투방: to the hall's door (standing up first). */
    public void goToHall(Player p) {
        Location warp = place(halls.get(HallStore.WARP));
        if (warp == null) {
            p.sendMessage(Lang.msg("hwatu.no-hall"));
            return;
        }
        boolean riding = p.isInsideVehicle();
        if (riding) p.leaveVehicle();   // a chair puts its sitter beside it on the next tick: go after that
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            p.teleportAsync(warp).thenAccept(ok -> {
                if (!ok) return;
                p.playSound(warp, Sound.BLOCK_IRON_DOOR_OPEN, 0.6f, 0.9f);
                p.sendMessage(Lang.msg("hwatu.welcome", "chips", fmt(chips(p.getUniqueId()))));
            });
        }, riding ? 3L : 1L);
    }

    public void openChips(Player p) {
        core.menus().open(p, MENU, new Chips());
    }

    /** The chip screen (/화투방 칩, the 현장 소장): my chips, buy · cash, the rules, the 족보, my last hands. */
    private final class Chips implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            ChipStore.Account a = store.account(u, today());
            Map<String, String> m = new HashMap<>();
            m.put("chips", fmt(a.chips));
            m.put("chips_gold", EconomyModule.format(a.chips * spec.cashPrice()));
            m.put("bought", fmt(a.bought));
            m.put("buy_left", buyLeftText(a.bought));
            m.put("daily_buy", dailyBuyText());
            m.put("net", (a.net >= 0 ? "+" : "") + fmt(a.net));
            m.put("loss_cap", spec.lossCap() <= 0 ? Lang.raw("hwatu.no-limit") : fmt(spec.lossCap()));
            m.put("rest", spec.lossCap() <= 0 ? Lang.raw("hwatu.rest-off") : Lang.raw("hwatu.rest-on", "cap", fmt(spec.lossCap())));
            m.put("age", spec.minAgeMs() <= 0 ? Lang.raw("hwatu.age-off") : Lang.raw("hwatu.age-on", "hours", spec.minAgeMs() / 3_600_000L));
            m.put("buy_price", fmt(spec.buyPrice()));
            m.put("cash_price", fmt(spec.cashPrice()));
            m.put("rake", String.valueOf(spec.rakePercent()));
            m.put("rake_from", fmt((100 + spec.rakePercent() - 1) / spec.rakePercent()));   // the smallest pot that pays (rounded down)
            m.put("limit_antes", spec.handLimitAntes() <= 0 ? Lang.raw("hwatu.no-limit") : fmt(spec.handLimitAntes()));
            List<String> rooms = new ArrayList<>();
            for (HwatuSpec.Room r : spec.rooms()) rooms.add(Lang.raw("hwatu.room-ante", "room", r.name(), "ante", fmt(r.ante())));
            m.put("rooms", String.join(" · ", rooms));
            m.put("hours", String.valueOf(spec.minAgeMs() / 3_600_000L));
            m.put("turn", String.valueOf(spec.turnSeconds()));
            m.put("balance", EconomyModule.format(core.economy().balance(u)));
            return m;
        }

        /** 전적: my last few hands (newest first). */
        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null || !b.action().equals("hwatu:record")) return base;
            UUID me = v.player().getUniqueId();
            ItemStack it = base.clone();
            List<ChipStore.Hand> hands = store.hands(me, 8);
            it.editMeta(meta -> {
                List<Component> lore = new ArrayList<>();
                for (ChipStore.Hand h : hands) {
                    Table.Game g = gameOf(h.game());
                    String game = g == null ? h.game() : gameName(g);
                    String room = spec.rooms().stream().filter(r -> r.id().equals(h.room())).map(HwatuSpec.Room::name).findFirst().orElse(h.room());
                    lore.add(Lang.item("hwatu.record-line", "when", WHEN.format(Instant.ofEpochMilli(h.at()).atZone(zone)), "game", game, "room", room,
                            "pot", fmt(h.pot())));
                }
                if (hands.isEmpty()) lore.add(Lang.item("hwatu.record-empty"));
                meta.lore(lore);
            });
            return it;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            String why;
            if (action.startsWith("hwatu:buy:")) why = buy(p, Long.parseLong(action.substring(10)));
            else if (action.startsWith("hwatu:cash:")) {
                String n = action.substring(11);
                why = cash(p, n.equals("all") ? -1 : Long.parseLong(n));
            } else if (action.equals("hwatu:hall")) {
                Bukkit.getScheduler().runTask(eco, () -> {
                    v.close();
                    goToHall(p);
                });
                return;
            } else return;
            if (why != null) deny(p, why, null);
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 도박장
    // ------------------------------------------------------------------------------------------

    /** A game by its name (홀짝) or key (holjjak), or null. */
    private Table.Game gameOf(String s) {
        if (s == null) return null;
        String t = s.replace(" ", "");
        for (Table.Game g : Table.Game.values()) if (g.key().equals(s) || gameName(g).replace(" ", "").equals(t)) return g;
        return null;
    }

    /** A game's name as typed in a command (no spaces: 세븐포커). */
    private String gameWord(Table.Game g) {
        return gameName(g).replace(" ", "");
    }

    /** A room by its name (초보방) or id (beginner), or null. */
    private HwatuSpec.Room roomOf(String s) {
        for (HwatuSpec.Room r : spec.rooms()) if (r.id().equals(s) || r.name().equals(s)) return r;
        return null;
    }

    /** The table furniture the player looks at (6 blocks), or empty. */
    private Optional<Furniture> lookedAt(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 6, 0.05,
                e -> e instanceof Interaction && !e.getPersistentDataContainer().has(TableScene.BUTTON));
        Optional<Furniture> f = r == null || r.getHitEntity() == null ? Optional.empty() : core.furniture().fromPart(r.getHitEntity());
        if (f.isEmpty()) {
            var b = p.getTargetBlockExact(6);
            if (b != null) f = core.furniture().furnitureAt(b);
        }
        return f.filter(x -> TableKind.of(x.item().key().asString()) != null);
    }

    /** The bound table the player looks at, else the nearest bound one within 12 blocks, or null. */
    private HallTable tableNear(Player p) {
        Optional<Furniture> f = lookedAt(p);
        if (f.isPresent() && tables.containsKey(f.get().display().getUniqueId())) return tables.get(f.get().display().getUniqueId());
        HallTable best = null;
        double bestD = 12 * 12;
        for (HallTable t : tables.values()) {
            Optional<Furniture> tf = t.furnitureEntity();
            if (tf.isEmpty() || !tf.get().location().getWorld().equals(p.getWorld())) continue;
            double d = tf.get().location().distanceSquared(p.getLocation());
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    private void admin(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(Lang.msg("hwatu.admin-usage"));
            return;
        }
        String[] rest = java.util.Arrays.copyOfRange(a, 1, a.length);
        switch (a[0]) {
            case "건설" -> adminBuild(s);
            case "제거" -> adminRemove(s);
            case "테이블" -> adminTable(s, rest);
            case "딜러" -> adminDealer(s);
            case "위치", "창구", "칠판" -> adminPlace(s, a[0]);
            case "시험" -> adminTest(s, rest);
            case "기록" -> {
                if (a.length < 2) {
                    showLog(s, null, "전체");
                    return;
                }
                Players.find(a[1], found -> sync(() -> {
                    if (found.isEmpty()) s.sendMessage(Text.msg("<red>그런 플레이어가 없습니다."));
                    else showLog(s, found.get(), a[1]);
                }));
            }
            case "칩" -> adminChips(s, a);
            default -> s.sendMessage(Lang.msg("hwatu.admin-usage"));
        }
    }

    private void adminChips(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(Lang.msg("hwatu.admin-usage"));
            return;
        }
        Players.find(a[1], found -> sync(() -> {
            if (found.isEmpty()) {
                s.sendMessage(Text.msg("<red>그런 플레이어가 없습니다."));
                return;
            }
            UUID u = found.get();
            if (a.length >= 3) {
                long n;
                try {
                    n = Long.parseLong(a[2].replace(",", ""));
                } catch (NumberFormatException e) {
                    s.sendMessage(Lang.msg("hwatu.admin-usage"));
                    return;
                }
                long before = chips(u);
                store.set(u, n, today());   // a correction: logged, no gold moves
                eco.getLogger().info("[도박장] " + s.getName() + ": " + a[1] + " 칩 " + before + " → " + Math.max(0, n));
            }
            ChipStore.Account acc = store.account(u, today());
            s.sendMessage(Text.msg("<gold>" + a[1] + " <gray>칩 <white>" + fmt(acc.chips) + "</white> · 오늘 산 칩 " + fmt(acc.bought)
                    + " · 오늘 " + (acc.net >= 0 ? "+" : "") + fmt(acc.net) + "칩" + (seated.containsKey(u) ? " · 앉아 있음" : "")));
        }));
    }

    /** /관리자 도박장 테이블 &lt;종목&gt; &lt;방&gt; | 해제 | 목록 (the table looked at). */
    private void adminTable(CommandSender s, String[] a) {
        if (a.length >= 1 && a[0].equals("목록")) {
            s.sendMessage(Lang.msg("hwatu.table-list", "n", tables.size()));
            for (HallTable t : tables.values()) {
                HallStore.Binding b = t.binding();
                s.sendMessage(Lang.mm("hwatu.table-list-line", "game", t.gameName(), "room", t.room().name(), "world", b.world(), "x", b.x(), "y", b.y(),
                        "z", b.z(), "n", t.seatedPlayers().size(), "loaded", t.furnitureEntity().isPresent() ? "" : Lang.raw("hwatu.table-unloaded")));
            }
            return;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("hwatu.test-player-only"));
            return;
        }
        Optional<Furniture> f = lookedAt(p);
        if (f.isEmpty()) {
            p.sendMessage(Lang.msg("hwatu.table-look"));
            return;
        }
        UUID id = f.get().display().getUniqueId();
        if (a.length >= 1 && a[0].equals("해제")) {
            HallTable t = tables.get(id);
            if (t == null) {
                p.sendMessage(Lang.msg("hwatu.table-not-bound"));
                return;
            }
            unbind(id, "hwatu.table-removed");
            p.sendMessage(Lang.msg("hwatu.table-unbound", "game", t.gameName(), "room", t.room().name()));
            return;
        }
        Table.Game g = a.length >= 1 ? gameOf(a[0]) : null;
        // 바카라 has no room of its own (its bets are baccarat:'s): the first room unless one is named
        HwatuSpec.Room r = a.length >= 2 ? roomOf(a[1]) : g == Table.Game.BACCARAT ? spec.rooms().getFirst() : null;
        if (g == null || r == null) {
            p.sendMessage(Lang.msg("hwatu.admin-usage"));
            return;
        }
        TableKind k = TableKind.of(f.get().item().key().asString());
        if (!k.fits(g)) {
            p.sendMessage(Lang.msg(g == Table.Game.BACCARAT ? "hwatu.table-not-baccarat" : "hwatu.table-baccarat-only", "game", gameName(g)));
            return;
        }
        if (TableKind.usable(k.seats(), spec.game(g).max()).size() < g.least()) {
            p.sendMessage(Lang.msg("hwatu.table-too-small", "game", gameName(g)));
            return;
        }
        bind(f.get(), g, r);
        p.sendMessage(Lang.msg("hwatu.table-bound", "game", gameName(g), "room", r.name(), "ante", fmt(r.ante()),
                "seats", Math.min(k.seats(), spec.game(g).max())));
    }

    /** /관리자 도박장 딜러: the 바카라 table looked at (or the nearest) gets its dealer where the admin stands, facing it. */
    private void adminDealer(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("hwatu.test-player-only"));
            return;
        }
        HallTable t = tableNear(p);
        if (t == null || t.game() != Table.Game.BACCARAT) {
            p.sendMessage(Lang.msg("hwatu.dealer-no-table"));
            return;
        }
        Location l = p.getLocation();
        halls.set(DEALER + t.furniture(), place(new Location(l.getWorld(), l.getX(), l.getY(), l.getZ(), l.getYaw(), 0)));
        t.redrawScene();
        p.sendMessage(Lang.msg("hwatu.place-set", "what", "딜러"));
    }

    /** /관리자 도박장 위치 (where /화투방 brings people) · 창구 (the 현장 소장 here) · 칠판 (the whiteboard text here). */
    private void adminPlace(CommandSender s, String what) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("hwatu.test-player-only"));
            return;
        }
        Location l = p.getLocation();
        switch (what) {
            case "위치" -> halls.set(HallStore.WARP, place(l));
            case "창구" -> {
                halls.set(HallStore.COUNTER, place(new Location(l.getWorld(), l.getX(), l.getY(), l.getZ(), l.getYaw(), 0)));
                if (boss != null) boss.remove();
                boss = null;
            }
            default -> {
                Location at = l.clone().add(l.getDirection().setY(0).normalize().multiply(0.1)).add(0, 1.4, 0);
                at.setYaw(l.getYaw() + 180);
                at.setPitch(0);
                halls.set(HallStore.BOARD, place(at));
                if (whiteboard != null) whiteboard.remove();
                whiteboard = null;
            }
        }
        keepHallNpcs();
        p.sendMessage(Lang.msg("hwatu.place-set", "what", what));
    }

    /** /관리자 도박장 건설: the hall where the admin stands, the way they look; its tables, counter and whiteboard bound after. */
    private void adminBuild(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("hwatu.test-player-only"));
            return;
        }
        if (job != null && job.running()) {
            p.sendMessage(Lang.msg("hwatu.hall-busy"));
            return;
        }
        Location l = p.getLocation();
        HallLayout.Site site = new HallLayout.Site(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), HallLayout.Site.facing(l.getYaw()));
        HallLayout.Site old = site();
        if (old != null && !old.equals(site)) {
            p.sendMessage(Lang.msg("hwatu.hall-exists", "site", old.toString()));
            return;
        }
        try {
            job = HallBuilder.build(eco, core, HALL, site, undoFile(), HallBuilder.Settings.of(eco.getConfig()),
                    pct -> p.sendActionBar(Lang.mm("hwatu.hall-progress", "pct", pct)), placed -> built(p, site, placed));
        } catch (IOException | IllegalArgumentException e) {
            p.sendMessage(Lang.msg("hwatu.hall-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        halls.set("site", site.world() + ";" + site.x() + ";" + site.y() + ";" + site.z() + ";" + site.facing());
        p.sendMessage(Lang.msg("hwatu.hall-started", "site", site.toString()));
    }

    private Path undoFile() {
        return eco.getDataFolder().toPath().resolve("hall").resolve(HALL + ".undo");
    }

    /** The hall stands: /화투방 goes to its door, the 현장 소장 to the counter, the board's text up, the six tables bound. */
    private void built(Player admin, HallLayout.Site site, HallBuilder.Placed placed) {
        halls.set(HallStore.WARP, place(placed.warp()));
        halls.set(HallStore.COUNTER, place(placed.counterNpc()));
        halls.set(HallStore.BOARD, place(placed.whiteboard()));
        removeHallNpcs();
        int bound = 0;
        for (HallBuilder.PlacedTable pt : placed.tables()) {
            if (pt.furniture() == null) continue;
            Entity e = Bukkit.getEntity(pt.furniture());
            Optional<Furniture> f = e instanceof org.bukkit.entity.ItemDisplay d ? core.furniture().fromDisplay(d) : Optional.empty();
            Table.Game g = gameOf(pt.game());
            HwatuSpec.Room r = roomOf(pt.room());
            if (f.isEmpty() || g == null || r == null) continue;
            HallTable bt = bind(f.get(), g, r);
            Location dealer = pt.dealer();   // the 바카라 table's dealer spot (null for the others)
            if (bt != null && dealer != null) halls.set(DEALER + bt.furniture(), place(dealer));
            bound++;
        }
        // tables of an earlier build of this hall that are gone now (it builds over itself)
        for (HallTable t : new ArrayList<>(tables.values())) {
            if (t.furnitureEntity().isEmpty() && placed.inside(new Location(Bukkit.getWorld(t.binding().world()), t.binding().x() + 0.5, t.binding().y() + 0.5,
                    t.binding().z() + 0.5))) unbind(t.furniture(), null);
        }
        keepHallNpcs();
        if (admin.isOnline()) {
            admin.sendMessage(Lang.msg("hwatu.hall-built", "tables", bound));
            for (String problem : placed.problems()) admin.sendMessage(Lang.msg("hwatu.hall-problem", "problem", problem));
        }
    }

    /** /관리자 도박장 제거: the hall's tables unbound, its entities gone, the ground back (its undo file). */
    private void adminRemove(CommandSender s) {
        HallLayout.Site site = site();
        if (site == null) {
            s.sendMessage(Lang.msg("hwatu.no-hall-admin"));
            return;
        }
        if (job != null && job.running()) {
            s.sendMessage(Lang.msg("hwatu.hall-busy"));
            return;
        }
        for (HallTable t : new ArrayList<>(tables.values())) {
            HallStore.Binding b = t.binding();
            if (b.world().equals(site.world()) && site.inside(b.world(), b.x() + 0.5, b.y() + 0.5, b.z() + 0.5)) unbind(t.furniture(), "hwatu.table-removed");
        }
        removeHallNpcs();
        halls.set(HallStore.WARP, null);
        halls.set(HallStore.COUNTER, null);
        halls.set(HallStore.BOARD, null);
        try {
            job = HallBuilder.remove(eco, core, HALL, site, undoFile(), HallBuilder.Settings.of(eco.getConfig()),
                    pct -> { if (s instanceof Player p) p.sendActionBar(Lang.mm("hwatu.hall-progress", "pct", pct)); },
                    restored -> {
                        halls.set("site", null);
                        s.sendMessage(Lang.msg("hwatu.hall-removed", "blocks", restored));
                    });
        } catch (IOException | IllegalArgumentException e) {
            s.sendMessage(Lang.msg("hwatu.hall-failed", "error", String.valueOf(e.getMessage())));
        }
    }

    /** /관리자 도박장 시험 [인원] · 시험 섯다 패 &lt;카드&gt; &lt;카드&gt; [봇1의 카드 둘] · 시험 끝 */
    private void adminTest(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("hwatu.test-player-only"));
            return;
        }
        UUID u = p.getUniqueId();
        HallTable mine = seated.get(u);
        if (a.length >= 1 && a[0].equals("끝")) {
            if (mine == null || !u.equals(mine.testAdmin())) p.sendMessage(Lang.msg("hwatu.test-none"));
            else mine.endTest(true);
            return;
        }
        int cards = a.length >= 1 && a[0].equals("패") ? 1 : a.length >= 2 && a[1].equals("패") && gameOf(a[0]) == Table.Game.SUTDA ? 2 : -1;
        if (cards > 0) {
            forceCards(p, java.util.Arrays.copyOfRange(a, cards, a.length));
            return;
        }
        int n = -1;
        if (a.length >= 1) {
            try {
                n = Integer.parseInt(a[0]);
            } catch (NumberFormatException e) {
                p.sendMessage(Lang.msg("hwatu.test-usage"));
                return;
            }
            if (n < 2 || n > 6) {
                p.sendMessage(Lang.msg("hwatu.test-usage"));
                return;
            }
        }
        startTest(p, n);
    }

    /** Seats the admin at the table they look at (or the nearest) with bots; null on success, else why not (already told). */
    private HallTable startTest(Player p, int n) {
        UUID u = p.getUniqueId();
        HallTable t = tableNear(p);
        if (t == null) {
            p.sendMessage(Lang.msg("hwatu.test-no-table"));
            return null;
        }
        HallTable mine = seated.get(u);
        if (mine != null && mine != t) {
            p.sendMessage(Lang.msg("hwatu.test-seated"));
            return null;
        }
        Optional<Furniture> f = t.furnitureEntity();
        if (f.isEmpty()) {
            p.sendMessage(Lang.msg("hwatu.test-no-table"));
            return null;
        }
        int players = n > 0 ? n : t.game() == Table.Game.HOLJJAK ? 2 : t.game() == Table.Game.BACCARAT ? 1 : 3;
        String why = t.startTest(p, f.get(), players);
        if (why != null) {
            p.sendMessage(Lang.msg(why));
            return null;
        }
        return t;
    }

    /** /관리자 도박장 시험 섯다 패 …: the cards of the next 섯다 hand (opens a 시험 판 at a 섯다 table if none). */
    private void forceCards(Player p, String[] a) {
        if (a.length != 2 && a.length != 4) {
            p.sendMessage(Lang.msg("hwatu.test-usage"));
            return;
        }
        Card[] c = new Card[a.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = Card.parse(a[i]);
            if (c[i] == null) {
                p.sendMessage(Lang.msg("hwatu.test-bad-card", "card", a[i]));
                return;
            }
        }
        if (java.util.Arrays.stream(c).distinct().count() != c.length) {
            p.sendMessage(Lang.msg("hwatu.test-cards-clash"));
            return;
        }
        UUID u = p.getUniqueId();
        HallTable t = seated.get(u);
        if (t == null || !u.equals(t.testAdmin())) {
            t = startTest(p, 3);
            if (t == null) return;
        }
        if (t.game() != Table.Game.SUTDA) {
            p.sendMessage(Lang.msg("hwatu.test-not-sutda"));
            return;
        }
        UUID bot = t.firstBot();
        if (!t.table().force(u, c[0], c[1]) || (c.length == 4 && (bot == null || !t.table().force(bot, c[2], c[3])))) {
            p.sendMessage(Lang.msg("hwatu.test-cards-clash"));
            return;
        }
        p.sendMessage(Lang.msg("hwatu.test-forced", "cards", c[0].label() + " · " + c[1].label(), "hand", Lang.raw(HAND + Sutda.of(c[0], c[1]).code())));
        if (c.length == 4) {
            p.sendMessage(Lang.msg("hwatu.test-forced-bot", "bot", mini(bot), "cards", c[2].label() + " · " + c[3].label(),
                    "hand", Lang.raw(HAND + Sutda.of(c[2], c[3]).code())));
        }
    }

    private void showLog(CommandSender s, UUID player, String who) {
        List<ChipStore.Hand> hands = store.hands(player, 15);
        s.sendMessage(Text.msg("<gold>도박장 기록 <gray>(" + who + ", 최근 " + hands.size() + "판, 새것부터 · 넣은 칩→받은 칩)"));
        for (ChipStore.Hand h : hands) {
            Table.Game g = gameOf(h.game());
            String game = g == null ? h.game() : gameName(g);
            String room = spec.rooms().stream().filter(r -> r.id().equals(h.room())).map(HwatuSpec.Room::name).findFirst().orElse(h.room());
            s.sendMessage(Text.msg("<dark_gray>#" + h.id() + " <gray>" + WHEN.format(Instant.ofEpochMilli(h.at()).atZone(zone)) + " <white>" + game + " " + room
                    + " <gray>판 " + fmt(h.pot()) + "칩 · 소각 " + fmt(h.burned()) + "칩 <dark_gray>| <white>" + Text.plain(h.detail())));
        }
        if (hands.isEmpty()) s.sendMessage(Text.msg("<gray>기록이 없습니다."));
    }

    private List<String> adminComplete(CommandSender s, String[] a) {
        String last = a[a.length - 1];
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("건설", "제거", "테이블", "딜러", "위치", "창구", "칠판", "시험", "기록", "칩"));
        else switch (a[0]) {
            case "테이블" -> {
                if (a.length == 2) {
                    for (Table.Game g : Table.Game.values()) out.add(gameWord(g));
                    out.addAll(List.of("해제", "목록"));
                } else if (a.length == 3 && gameOf(a[1]) != null) for (HwatuSpec.Room r : spec.rooms()) out.add(r.name());
            }
            case "시험" -> {
                if (a.length == 2) {
                    for (int n = 2; n <= 6; n++) out.add(String.valueOf(n));
                    out.addAll(List.of("섯다", "끝"));
                } else if (a.length == 3 && a[1].equals("섯다")) out.add("패");
                else if (a.length >= 4 && a.length <= 7 && a[2].equals("패")) for (Card c : Card.deck()) out.add(c.label());
            }
            case "기록", "칩" -> {
                if (a.length == 2) return Players.complete(s, a[1]);
            }
            default -> {}
        }
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
