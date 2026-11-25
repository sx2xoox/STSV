package dev.stsv.economy.hwatu;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 화투방 (README "화투방"; hwatu.yml, see {@link HwatuSpec}): players play each other for chips, never the server. Chips are
 * bought with gold and cashed to gold only (never 별 or anything 후원), are a number on the account (no item), and every pot
 * pays the 자릿세, which is destroyed: the 화투방 only ever takes money out of the economy. Daily buy limit, daily loss cap,
 * no accounts younger than a day, every hand logged for /관리자 화투방 기록, no winnings ranking.
 *
 * <p>Money: buying holds the gold outside the balances (ESCROW, like shares) and the chips stand for it at the buy price;
 * cashing gives back the cash price (ESCROW) and destroys the rest; the 자릿세 destroys the gold behind its chips (burn).
 * The weekly ranking and the 순자산 achievements ignore chips won or lost ({@link dev.stsv.economy.rank.RankModule#gambled}).
 */
public final class HwatuModule extends EcoModule {

    static final String MENU = "economy:hwatu", TABLE_MENU = "economy:hwatu_table";
    /** Message keys of what a player had: hwatu.hand.&lt;code&gt; ({@link Sutda.Hand#code()}, odd, even, die). */
    static final String HAND = "hwatu.hand.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private HwatuSpec spec;
    private ChipStore store;
    private ZoneId zone;
    private final SecureRandom rng = new SecureRandom();
    private final Map<Integer, Table> tables = new LinkedHashMap<>();
    private final Map<UUID, Table> seated = new HashMap<>();
    private final Map<UUID, MenuView> views = new HashMap<>();
    private final Map<Integer, UUID> turns = new HashMap<>();
    private int nextTable = 1;
    private final Events events = new Events();

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
        eco.onReset(this::reset);
        core.admin().add(EcoPlugin.ADMIN_OWNER, "화투방", "기록 [플레이어] · 칩 <플레이어> [수]",
                "화투방 판 기록 (누가 누구에게 얼마를: 세탁 감시) / 칩 보기·정하기", this::admin, this::adminComplete);
        every(20, this::tick);
    }

    @Override
    protected void stop() {
        // a running hand is void: nothing was moved yet (chips move only when a hand ends)
        for (Table t : tables.values()) t.abandon();
        for (MenuView v : new ArrayList<>(views.values())) v.close();
        tables.clear();
        seated.clear();
        views.clear();
    }

    @Override
    public void reload() {
        load();
        for (Table t : tables.values()) t.spec(spec);
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = HwatuSpec.parse(eco.data("hwatu.yml"), problems);
        zone = ZoneId.of(eco.getConfig().getString("time.zone"));
        problems.forEach(p -> eco.getLogger().warning("[hwatu.yml] " + p));
    }

    public HwatuSpec spec() {
        return spec;
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

    /** Chips not held in a running hand. */
    long available(UUID player) {
        Table t = seated.get(player);
        return chips(player) - (t == null ? 0 : t.committed(player));
    }

    private void reset() {
        for (Table t : tables.values()) t.abandon();
        for (MenuView v : new ArrayList<>(views.values())) v.close();
        tables.clear();
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
                "left", fmt(HwatuRules.buyLeft(a.bought + n, spec.dailyBuy()))));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);
        return null;
    }

    /** Cashes n chips (all free ones if n &lt; 0) to gold; null on success, else the refusal key. */
    public String cash(Player p, long n) {
        UUID u = p.getUniqueId();
        long free = available(u);
        if (n < 0) n = free;
        String why = HwatuRules.canCash(n, free);
        if (why != null) return why;
        Table t = seated.get(u);
        if (!store.cash(u, n, t == null ? 0 : t.committed(u), today())) return "hwatu.cash-short";
        long gold = n * spec.cashPrice(), fee = n * (spec.buyPrice() - spec.cashPrice());
        core.economy().move(null, u, gold, EconomyStore.Kind.ESCROW, "hwatu_chip_cash", n + "칩");
        if (fee > 0) core.economy().burn(fee, "hwatu_cash_fee", u + " " + n + "칩");
        p.sendMessage(Lang.msg("hwatu.cashed", "n", fmt(n), "gold", EconomyModule.format(gold), "fee", EconomyModule.format(fee), "chips", fmt(chips(u))));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------------------------------

    /** Why a player may not be dealt in (or sit down), null if they may. */
    private String refusal(UUID u, long need) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return "hwatu.away";
        ChipStore.Account a = store.account(u, today());
        return HwatuRules.canPlay(p.getFirstPlayed(), now(), spec.minAgeMs(), a.net, spec.lossCap(), a.chips, need);
    }

    /** Sits the player at a table of that game and room (one with players waiting first); null on success, else why not. */
    public String sit(Player p, Table.Game game, HwatuSpec.Room room) {
        UUID u = p.getUniqueId();
        Table at = seated.get(u);
        if (at != null) {
            if (at.game() == game && at.room().id().equals(room.id())) {
                openTable(p);
                return null;
            }
            return "hwatu.already-seated";
        }
        String why = refusal(u, spec.need(game, room));
        if (why != null) return why;
        Table pick = null;
        for (Table t : tables.values()) {
            if (t.game() != game || !t.room().id().equals(room.id()) || t.full()) continue;
            if (pick == null || t.seats().size() > pick.seats().size()) pick = t;
        }
        if (pick == null) {
            pick = new Table(nextTable++, game, room, spec, rng);
            tables.put(pick.id(), pick);
        }
        pick.sit(u, now());
        seated.put(u, pick);
        for (UUID o : pick.seats()) {
            Player op = Bukkit.getPlayer(o);
            if (op != null && !o.equals(u)) op.sendMessage(Lang.msg("hwatu.joined", "player", Names.mini(u)));
        }
        p.sendMessage(Lang.msg("hwatu.sat", "game", gameName(game), "room", room.name(), "ante", fmt(room.ante()), "need", fmt(spec.need(game, room))));
        openTable(p);
        refresh(pick);
        return null;
    }

    /** Stands up (a fold in a running hand). */
    public void leave(UUID u) {
        Table t = seated.remove(u);
        if (t == null) return;
        boolean folding = t.inHand(u);
        t.leave(u, now(), events);
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.sendMessage(Lang.msg(folding ? "hwatu.left-fold" : "hwatu.left"));
        for (UUID o : t.seats()) {
            Player op = Bukkit.getPlayer(o);
            if (op != null) op.sendMessage(Lang.msg("hwatu.someone-left", "player", Names.mini(u)));
        }
        if (t.empty()) tables.remove(t.id());
    }

    private void tick() {
        for (Table t : new ArrayList<>(tables.values())) {
            t.tick(now(), this::refusalAt, events);
            if (t.empty()) tables.remove(t.id());
            else refresh(t);
        }
    }

    /** What the table's own code tells the module. */
    private final class Events implements Table.Listener {
        @Override
        public void changed(Table t) {
            UUID turn = t.turn();
            if (turn != null && !turn.equals(turns.get(t.id()))) {
                Player p = Bukkit.getPlayer(turn);
                if (p != null) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
                    HudModule.flash(p, Lang.mm("hwatu.your-turn", "seconds", spec.turnSeconds()));
                }
            }
            if (turn == null) turns.remove(t.id());
            else turns.put(t.id(), turn);
            refresh(t);
        }

        @Override
        public void kicked(Table t, UUID player, String reason) {
            seated.remove(player);
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                p.sendMessage(Lang.msg(reason, "need", fmt(t.need()), "cap", fmt(spec.lossCap())));
                p.sendMessage(Lang.msg("hwatu.stood-up"));
            }
        }

        @Override
        public void replay(Table t) {
            tell(t, Lang.msg("hwatu.replay"));
        }

        @Override
        public void finished(Table t, Table.Result r) {
            settle(t, r);
        }
    }

    private void settle(Table t, Table.Result r) {
        int day = today();
        for (UUID u : r.players()) {
            long delta = r.net(u);
            store.settle(u, delta, day);
            // not earnings, not a loss: the weekly ranking and the 순자산 achievements leave the 화투방 out
            if (eco.rank() != null) eco.rank().gambled(u, delta * spec.buyPrice());
        }
        if (r.burned() > 0) core.economy().burn(r.burned() * spec.buyPrice(), "hwatu_rake", t.game().key() + " " + t.room().id());
        // the log: who put in what and got what (admin review: who lost to whom)
        StringBuilder d = new StringBuilder();
        for (UUID u : r.players()) {
            if (!d.isEmpty()) d.append(" · ");
            d.append(Names.plain(u)).append(' ').append(r.put().get(u)).append("→").append(r.payout().getOrDefault(u, 0L));
            String what = detail(r, u);
            if (!what.isEmpty()) d.append(" (").append(Text.plain(what)).append(')');
        }
        store.log(now(), t.game().key(), t.room().id(), r.players(), r.pot(), r.burned(), d.toString());
        // tell the table
        List<String> names = new ArrayList<>();
        for (UUID w : r.winners()) names.add(Names.mini(w));
        String winnerHand = r.winners().size() == 1 ? detail(r, r.winners().getFirst()) : "";
        long share = r.winners().isEmpty() ? 0 : r.payout().get(r.winners().getFirst());
        Component line = Lang.msg(r.uncontested() ? "hwatu.won-alone" : r.winners().size() > 1 ? "hwatu.won-split" : "hwatu.won",
                "players", String.join("<gray>, </gray>", names), "hand", winnerHand, "pot", fmt(r.pot()), "share", fmt(share), "rake", fmt(r.burned()));
        for (UUID u : r.players()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.sendMessage(line);
            if (t.game() == Table.Game.SUTDA && !r.uncontested()) {
                List<String> hands = new ArrayList<>();
                for (UUID o : r.players()) hands.add(Names.mini(o) + " <gray>" + detail(r, o));
                p.sendMessage(Lang.msg("hwatu.hands", "hands", String.join("<dark_gray> · </dark_gray>", hands)));
            }
            long net = r.net(u);
            p.sendMessage(Lang.msg(net >= 0 ? "hwatu.my-win" : "hwatu.my-loss", "n", fmt(Math.abs(net)), "chips", fmt(chips(u))));
            p.playSound(p.getLocation(), r.winners().contains(u) ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, r.winners().contains(u) ? 1.4f : 0.8f);
            if (HwatuRules.resting(store.account(u, day).net, spec.lossCap())) p.sendMessage(Lang.msg("hwatu.resting-now", "cap", fmt(spec.lossCap())));
        }
    }

    /** What a player had in a finished hand, as MiniMessage ("" if nothing to say). */
    private String detail(Table.Result r, UUID u) {
        String d = r.detail().get(u);
        if (d == null) return "";
        if (d.equals("die")) return Lang.raw("hwatu.hand.die");
        String hand = HAND + d;
        return switch (r.game()) {
            case SUTDA -> Lang.raw(hand);
            case HOLJJAK -> Lang.raw(hand) + (r.die() > 0 ? " " + Lang.raw("hwatu.die-face", "n", r.die()) : "");
            case DICE -> {
                int total = 0;
                for (String x : d.split("\\+")) total += Integer.parseInt(x);
                yield Lang.raw("hwatu.dice", "roll", d, "total", total);
            }
        };
    }

    private void tell(Table t, Component c) {
        for (UUID u : t.seats()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(c);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        views.remove(e.getPlayer().getUniqueId());
        leave(e.getPlayer().getUniqueId());
    }

    private String gameName(Table.Game g) {
        return spec.game(g).name();
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    // ------------------------------------------------------------------------------------------
    // /화투방: the lobby
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        if (seated.containsKey(p.getUniqueId())) openTable(p);
        else openLobby(p);
    }

    public void openLobby(Player p) {
        core.menus().open(p, MENU, new Lobby());
    }

    private final class Lobby implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            ChipStore.Account a = store.account(u, today());
            Map<String, String> m = new HashMap<>();
            m.put("chips", fmt(a.chips));
            m.put("chips_gold", EconomyModule.format(a.chips * spec.cashPrice()));
            m.put("bought", fmt(a.bought));
            m.put("buy_left", fmt(HwatuRules.buyLeft(a.bought, spec.dailyBuy())));
            m.put("daily_buy", fmt(spec.dailyBuy()));
            m.put("net", (a.net >= 0 ? "+" : "") + fmt(a.net));
            m.put("loss_cap", fmt(spec.lossCap()));
            m.put("buy_price", fmt(spec.buyPrice()));
            m.put("cash_price", fmt(spec.cashPrice()));
            m.put("rake", String.valueOf(spec.rakePercent()));
            m.put("hours", String.valueOf(spec.minAgeMs() / 3_600_000L));
            m.put("turn", String.valueOf(spec.turnSeconds()));
            m.put("balance", EconomyModule.format(core.economy().balance(u)));
            return m;
        }

        /** The rooms: every game in every room (3 × 3, game by game). */
        private List<Object[]> kinds() {
            List<Object[]> out = new ArrayList<>();
            for (Table.Game g : Table.Game.values()) for (HwatuSpec.Room r : spec.rooms()) out.add(new Object[]{g, r});
            return out;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID me = v.player().getUniqueId();
            List<ItemStack> out = new ArrayList<>();
            for (Object[] k : kinds()) {
                Table.Game g = (Table.Game) k[0];
                HwatuSpec.Room r = (HwatuSpec.Room) k[1];
                int sitting = 0, open = 0;
                for (Table t : tables.values()) {
                    if (t.game() != g || !t.room().id().equals(r.id())) continue;
                    sitting += t.seats().size();
                    if (!t.full()) open++;
                }
                String icon = switch (g) {
                    case HOLJJAK -> "economy:hwatu_back";
                    case DICE -> "minecraft:bone_block";
                    case SUTDA -> "economy:hwatu_3a";
                };
                ItemStack it = Items.create(icon, 1).orElseGet(() -> new ItemStack(Material.PAPER));
                boolean here = seated.get(me) != null && seated.get(me).game() == g && seated.get(me).room().id().equals(r.id());
                int people = sitting, tablesOpen = open;
                it.editMeta(meta -> {
                    meta.itemName(Lang.item("hwatu.room-name", "game", gameName(g), "room", r.name()));
                    String about = "hwatu.room-lore-" + g.key();
                    List<Component> lore = new ArrayList<>(Lang.items(about, "max", spec.game(g).max(), "dice", spec.game(g).dice(),
                            "raises", spec.game(g).maxRaises()));
                    lore.add(Lang.item("hwatu.room-ante", "ante", fmt(r.ante()), "gold", EconomyModule.format(r.ante() * spec.buyPrice()),
                            "need", fmt(spec.need(g, r))));
                    lore.add(Lang.item("hwatu.room-people", "n", people, "open", tablesOpen));
                    lore.add(Component.empty());
                    lore.add(Lang.item(here ? "hwatu.room-here" : "hwatu.room-click"));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(here);
                    meta.addItemFlags(ItemFlag.values());
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            List<Object[]> k = kinds();
            if (index < 0 || index >= k.size()) return;
            Table.Game g = (Table.Game) k.get(index)[0];
            HwatuSpec.Room r = (HwatuSpec.Room) k.get(index)[1];
            Player p = v.player();
            Bukkit.getScheduler().runTask(eco, () -> {   // not inside the click: sitting opens the table's screen
                String why = sit(p, g, r);
                if (why != null) {
                    deny(p, why, g, r);
                    v.refresh();
                }
            });
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            String why = null;
            if (action.startsWith("hwatu:buy:")) why = buy(p, Long.parseLong(action.substring(10)));
            else if (action.startsWith("hwatu:cash:")) {
                String n = action.substring(11);
                why = cash(p, n.equals("all") ? -1 : Long.parseLong(n));
            } else return;
            if (why != null) deny(p, why, null, null);
            v.refresh();
        }
    }

    private void deny(Player p, String why, Table.Game g, HwatuSpec.Room r) {
        ChipStore.Account a = store.account(p.getUniqueId(), today());
        HudModule.flash(p, Lang.mm(why, "need", fmt(g == null ? 0 : spec.need(g, r)), "cap", fmt(spec.lossCap()), "hours", spec.minAgeMs() / 3_600_000L,
                "left", fmt(HwatuRules.buyLeft(a.bought, spec.dailyBuy())), "daily", fmt(spec.dailyBuy())));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
    }

    // ------------------------------------------------------------------------------------------
    // The table screen
    // ------------------------------------------------------------------------------------------

    public void openTable(Player p) {
        Table t = seated.get(p.getUniqueId());
        if (t == null) {
            openLobby(p);
            return;
        }
        core.menus().open(p, TABLE_MENU, new TableView(t)).ifPresent(v -> views.put(p.getUniqueId(), v));
    }

    private void refresh(Table t) {
        for (UUID u : t.seats()) {
            MenuView v = views.get(u);
            if (v == null) continue;
            if (!(v.handler() instanceof TableView tv) || tv.table != t || v.player().getOpenInventory().getTopInventory().getHolder(false) != v) {
                views.remove(u);
                continue;
            }
            v.refresh();
        }
    }

    /** True while the player has their table's screen open (a player who wandered off is not dealt the next hand). */
    private boolean watching(UUID u) {
        MenuView v = views.get(u);
        return v != null && v.player().getOpenInventory().getTopInventory().getHolder(false) == v;
    }

    private final class TableView implements MenuHandler {
        final Table table;

        TableView(Table table) {
            this.table = table;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            ChipStore.Account a = store.account(u, today());
            Map<String, String> m = new HashMap<>();
            m.put("game", gameName(table.game()));
            m.put("room", table.room().name());
            m.put("ante", fmt(table.ante()));
            m.put("chips", fmt(a.chips));
            m.put("free", fmt(a.chips - table.committed(u)));
            m.put("net", (a.net >= 0 ? "+" : "") + fmt(a.net));
            m.put("loss_cap", fmt(spec.lossCap()));
            m.put("need", fmt(table.need()));
            m.put("rake", String.valueOf(spec.rakePercent()));
            return m;
        }

        private long left() {
            return Math.max(0, (table.deadline() - now() + 999) / 1000);
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null) return null;
            UUID me = v.player().getUniqueId();
            String a = b.action();
            ItemStack it = base.clone();
            if (a.equals("hwatu:info")) {
                it.editMeta(meta -> {
                    meta.itemName(Lang.item("hwatu.info-name", "game", gameName(table.game()), "room", table.room().name()));
                    List<Component> lore = new ArrayList<>();
                    lore.add(Lang.item("hwatu.info-pot", "pot", fmt(table.phase() == Table.Phase.WAITING ? 0 : table.pot()), "ante", fmt(table.ante()),
                            "rake", spec.rakePercent()));
                    lore.add(Lang.item(switch (table.phase()) {
                        case WAITING -> table.seats().size() < 2 ? "hwatu.state-alone" : "hwatu.state-starting";
                        case PLAYING -> table.step() == Table.Step.ROLL ? "hwatu.state-rolling"
                                : table.turn() == null ? "hwatu.state-playing" : "hwatu.state-turn";
                        case RESULT -> "hwatu.state-result";
                    }, "seconds", left(), "player", table.turn() == null ? "" : Names.mini(table.turn())));
                    if (table.game() == Table.Game.SUTDA && table.betting() != null && table.phase() == Table.Phase.PLAYING) {
                        lore.add(Lang.item("hwatu.info-raises", "n", table.betting().raises(), "max", spec.game(Table.Game.SUTDA).maxRaises()));
                        if (table.replays() > 0) lore.add(Lang.item("hwatu.info-replay"));
                    }
                    if (table.game() == Table.Game.HOLJJAK && table.calledOdd() != null) {
                        lore.add(Lang.item("hwatu.info-called", "player", Names.mini(table.players().getFirst()),
                                "call", Lang.raw(table.calledOdd() ? "hwatu.hand.odd" : "hwatu.hand.even")));
                    }
                    Table.Result r = table.last();
                    if (r != null && table.phase() == Table.Phase.RESULT) {
                        List<String> names = new ArrayList<>();
                        for (UUID w : r.winners()) names.add(Names.mini(w));
                        lore.add(Lang.item("hwatu.info-result", "players", String.join(", ", names), "pot", fmt(r.pot()), "rake", fmt(r.burned())));
                    }
                    meta.lore(lore);
                });
                return it;
            }
            if (!a.equals("hwatu:a") && !a.equals("hwatu:b") && !a.equals("hwatu:c")) return base;
            boolean myTurn = me.equals(table.turn());
            String key;
            Material mat;
            boolean glint = false;
            long amount = 0;
            if (myTurn && table.step() == Table.Step.PICK) {
                switch (a) {
                    case "hwatu:a" -> { key = "hwatu.btn-odd"; mat = Material.RED_CONCRETE; }
                    case "hwatu:c" -> { key = "hwatu.btn-even"; mat = Material.BLUE_CONCRETE; }
                    default -> { key = "hwatu.btn-pick"; mat = Material.CLOCK; }
                }
            } else if (myTurn && table.step() == Table.Step.BET) {
                Betting bet = table.betting();
                int seat = table.players().indexOf(me);
                switch (a) {
                    case "hwatu:a" -> { key = "hwatu.btn-die"; mat = Material.RED_CONCRETE; }
                    case "hwatu:b" -> {
                        amount = bet.toCall(seat);
                        key = amount > 0 ? "hwatu.btn-call" : "hwatu.btn-check";
                        mat = Material.LIME_CONCRETE;
                    }
                    default -> {
                        amount = bet.toRaise(seat);
                        key = bet.canRaise() ? "hwatu.btn-raise" : "hwatu.btn-no-raise";
                        mat = bet.canRaise() ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE;
                    }
                }
                glint = a.equals("hwatu:b");
            } else {
                key = "hwatu.btn-wait";
                mat = Material.GRAY_STAINED_GLASS_PANE;
            }
            ItemStack out = new ItemStack(mat);
            String k = key;
            long n = amount;
            boolean g = glint;
            long seconds = left();
            out.editMeta(meta -> {
                meta.itemName(Lang.item(k, "n", fmt(n)));
                String loreKey = k + "-lore";
                List<Component> lore = new ArrayList<>(Lang.items(loreKey, "n", fmt(n), "seconds", seconds,
                        "auto", Lang.raw(table.step() == Table.Step.BET && table.betting() != null && table.betting().turn() >= 0
                                && table.betting().toCall(table.betting().turn()) > 0 ? "hwatu.hand.die" : "hwatu.auto-call")));
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(g);
                meta.addItemFlags(ItemFlag.values());
            });
            return out;
        }

        /** 18 cells: the six seats (heads), then each seat's first card / die, then its second. */
        @Override
        public List<ItemStack> list(MenuView v) {
            UUID me = v.player().getUniqueId();
            List<UUID> order = new ArrayList<>(table.phase() == Table.Phase.WAITING ? table.seats() : table.players());
            for (UUID u : table.seats()) if (!order.contains(u)) order.add(u);   // sat down during the hand: waiting
            List<ItemStack> heads = new ArrayList<>(), first = new ArrayList<>(), second = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                if (i >= table.max()) {
                    heads.add(null);
                    first.add(null);
                    second.add(null);
                    continue;
                }
                if (i >= order.size()) {
                    heads.add(pane("hwatu.seat-empty"));
                    first.add(null);
                    second.add(null);
                    continue;
                }
                UUID u = order.get(i);
                heads.add(head(u));
                ItemStack[] two = shown(u, me);
                first.add(two[0]);
                second.add(two[1]);
            }
            List<ItemStack> out = new ArrayList<>(heads);
            out.addAll(first);
            out.addAll(second);
            return out;
        }

        private ItemStack pane(String key) {
            ItemStack it = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            it.editMeta(meta -> meta.itemName(Lang.item(key)));
            return it;
        }

        private ItemStack head(UUID u) {
            ItemStack it = new ItemStack(Material.PLAYER_HEAD);
            boolean turn = u.equals(table.turn());
            Table.Result r = table.phase() == Table.Phase.RESULT ? table.last() : null;
            it.editMeta(meta -> {
                if (meta instanceof SkullMeta sm) sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
                meta.itemName(Text.item((turn ? "<yellow>▶ " : "<white>") + Names.mini(u)));
                List<Component> lore = new ArrayList<>();
                if (!table.players().contains(u) || table.phase() == Table.Phase.WAITING) lore.add(Lang.item("hwatu.seat-waiting"));
                else {
                    lore.add(Lang.item("hwatu.seat-put", "n", fmt(table.put(u))));
                    if (table.folded(u)) lore.add(Lang.item("hwatu.seat-die"));
                    else if (turn) lore.add(Lang.item("hwatu.seat-turn", "seconds", left()));
                    if (r != null) {
                        String d = detail(r, u);
                        if (!d.isEmpty() && !table.folded(u)) lore.add(Text.item("<gray>" + d));
                        long net = r.net(u);
                        lore.add(Lang.item(net >= 0 ? "hwatu.seat-won" : "hwatu.seat-lost", "n", fmt(Math.abs(net))));
                    }
                }
                lore.add(Lang.item("hwatu.seat-chips", "n", fmt(chips(u))));
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(turn || (r != null && r.winners().contains(u)));
                meta.addItemFlags(ItemFlag.values());
            });
            return it;
        }

        /** The two cells under a seat: cards (섯다), dice (주사위), the call and the die (홀짝). */
        private ItemStack[] shown(UUID u, UUID me) {
            ItemStack[] out = new ItemStack[2];
            if (table.phase() == Table.Phase.WAITING || !table.players().contains(u)) return out;
            switch (table.game()) {
                case SUTDA -> {
                    Card[] c = table.cards(u);
                    if (c == null) return out;   // folded before a 재경기 deal
                    boolean open = u.equals(me) || (table.phase() == Table.Phase.RESULT && !table.folded(u));
                    for (int k = 0; k < 2; k++) out[k] = open ? card(c[k]) : back();
                }
                case DICE -> {
                    int[] r = table.rolls(u);
                    if (r == null) {
                        if (!table.folded(u)) out[0] = rolling();
                        return out;
                    }
                    for (int k = 0; k < Math.min(2, r.length); k++) out[k] = die(r[k]);   // a third die is told in the seat
                }
                case HOLJJAK -> {
                    if (table.players().indexOf(u) == 0 && table.calledOdd() != null) {
                        ItemStack it = new ItemStack(table.calledOdd() ? Material.RED_CONCRETE : Material.BLUE_CONCRETE);
                        it.editMeta(meta -> meta.itemName(Lang.item(table.calledOdd() ? "hwatu.hand.odd" : "hwatu.hand.even")));
                        out[0] = it;
                    }
                    if (table.players().indexOf(u) == 0 && table.die() > 0) out[1] = die(table.die());
                }
            }
            return out;
        }

        private ItemStack card(Card c) {
            ItemStack it = Items.create(c.item(), 1).orElseGet(() -> new ItemStack(Material.PAPER));
            it.editMeta(meta -> {
                String name = c.gwang() ? "hwatu.card-gwang" : c.yeol() ? "hwatu.card-yeol" : "hwatu.card-tti";
                meta.itemName(Lang.item(name, "month", c.month()));
                meta.lore(List.of());
            });
            return it;
        }

        private ItemStack back() {
            ItemStack it = Items.create("economy:hwatu_back", 1).orElseGet(() -> new ItemStack(Material.PAPER));
            it.editMeta(meta -> {
                meta.itemName(Lang.item("hwatu.card-back"));
                meta.lore(List.of());
            });
            return it;
        }

        private ItemStack die(int n) {
            ItemStack it = new ItemStack(Material.BONE_BLOCK, Math.max(1, n));
            it.editMeta(meta -> meta.itemName(Lang.item("hwatu.die-item", "n", n)));
            return it;
        }

        private ItemStack rolling() {
            ItemStack it = new ItemStack(Material.BONE_BLOCK);
            it.editMeta(meta -> meta.itemName(Lang.item("hwatu.die-rolling")));
            return it;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            UUID u = p.getUniqueId();
            if (action.equals("hwatu:leave")) {
                leave(u);
                Bukkit.getScheduler().runTask(eco, () -> openLobby(p));
                return;
            }
            Table.Move m = null;
            if (table.step() == Table.Step.PICK) m = action.equals("hwatu:a") ? Table.Move.ODD : action.equals("hwatu:c") ? Table.Move.EVEN : null;
            else if (table.step() == Table.Step.BET) m = switch (action) {
                case "hwatu:a" -> Table.Move.DIE;
                case "hwatu:b" -> Table.Move.CALL;
                case "hwatu:c" -> Table.Move.RAISE;
                default -> null;
            };
            if (m == null || seated.get(u) != table) return;
            String why = table.act(u, m, now(), events);
            if (why != null) HudModule.flash(p, Lang.mm(why));
            else p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
            v.refresh();
        }

        @Override
        public void onClose(MenuView v) {
            // closing the screen does not stand up: the turn timer plays for the player, and the next hand is not dealt to
            // someone who is not looking (see refusal: "hwatu.away")
        }
    }

    /** Players at a table must be looking at it to be dealt in. */
    private String refusalAt(UUID u, long need) {
        if (!watching(u)) return "hwatu.away";
        return refusal(u, need);
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 화투방
    // ------------------------------------------------------------------------------------------

    private void admin(CommandSender s, String[] a) {
        String usage = "<red>/관리자 화투방 기록 [플레이어] · 칩 <플레이어> [수]";
        if (a.length < 1) {
            s.sendMessage(Text.msg(usage));
            return;
        }
        switch (a[0]) {
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
            case "칩" -> {
                if (a.length < 2) {
                    s.sendMessage(Text.msg(usage));
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
                            s.sendMessage(Text.msg(usage));
                            return;
                        }
                        long before = chips(u);
                        store.set(u, n, today());   // a correction: logged, no gold moves
                        eco.getLogger().info("[화투방] " + s.getName() + ": " + a[1] + " 칩 " + before + " → " + Math.max(0, n));
                    }
                    ChipStore.Account acc = store.account(u, today());
                    s.sendMessage(Text.msg("<gold>" + a[1] + " <gray>칩 <white>" + fmt(acc.chips) + "</white> · 오늘 산 칩 " + fmt(acc.bought)
                            + " · 오늘 " + (acc.net >= 0 ? "+" : "") + fmt(acc.net) + "칩" + (seated.containsKey(u) ? " · 앉아 있음" : "")));
                }));
            }
            default -> s.sendMessage(Text.msg(usage));
        }
    }

    private void showLog(CommandSender s, UUID player, String who) {
        List<ChipStore.Hand> hands = store.hands(player, 15);
        s.sendMessage(Text.msg("<gold>화투방 기록 <gray>(" + who + ", 최근 " + hands.size() + "판, 새것부터 · 넣은 칩→받은 칩)"));
        for (ChipStore.Hand h : hands) {
            String game = switch (h.game()) {
                case "holjjak" -> gameName(Table.Game.HOLJJAK);
                case "dice" -> gameName(Table.Game.DICE);
                case "sutda" -> gameName(Table.Game.SUTDA);
                default -> h.game();
            };
            String room = spec.rooms().stream().filter(r -> r.id().equals(h.room())).map(HwatuSpec.Room::name).findFirst().orElse(h.room());
            s.sendMessage(Text.msg("<dark_gray>#" + h.id() + " <gray>" + WHEN.format(Instant.ofEpochMilli(h.at()).atZone(zone)) + " <white>" + game + " " + room
                    + " <gray>판 " + fmt(h.pot()) + "칩 · 소각 " + fmt(h.burned()) + "칩 <dark_gray>| <white>" + Text.plain(h.detail())));
        }
        if (hands.isEmpty()) s.sendMessage(Text.msg("<gray>기록이 없습니다."));
    }

    private List<String> adminComplete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("기록", "칩");
        if (a.length == 2) return Players.complete(s, a[1]);
        return List.of();
    }
}
