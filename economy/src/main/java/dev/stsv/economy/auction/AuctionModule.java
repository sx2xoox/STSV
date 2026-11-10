package dev.stsv.economy.auction;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.TradeRules;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경매: 30 minutes of open bidding with server-wide announcements. Bids are held (escrow) and given
 * back when someone bids higher; a bid in the last minute adds a minute. 7% fee and a 100G deposit
 * (returned when it sells). At most 3 player auctions at once. Server auctions (대경매)
 * count toward a weekly limit of rare wins per player.
 */
public final class AuctionModule extends EcoModule {

    private AuctionStore store;
    private TradeRules rules;
    private final Map<Long, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<String, Integer> rareWins = new HashMap<>();   // player|week
    /** names for messages, read from the item once per auction (the boss bar asks every second) */
    private final Map<Auction, String> names = new java.util.WeakHashMap<>();

    /** From the auctioned item itself, so it reads in each player's language (item_name is kept for the record). */
    private String name(Auction a) {
        return names.computeIfAbsent(a, x -> Items.mmName(Items.fromBytes(x.item)));
    }

    public AuctionModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-auction";
    }

    @Override
    protected void start() {
        store = new AuctionStore(core.db());
        store.init();
        rules = eco.rules();
        every(20L, this::tick);
        eco.onReset(() -> {
            hideBars();
            store.reset();
            rareWins.clear();
        });
    }

    @Override
    public void reload() {
        rules = eco.rules();
    }

    @Override
    protected void stop() {
        hideBars();
    }

    private void hideBars() {
        for (BossBar b : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
        bars.clear();
    }

    public AuctionStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------------------------------

    /** /경매 등록 &lt;시작가&gt;: puts the held stack up. */
    public void startPlayer(Player p, long startPrice) {
        startPlayer(p, p.getInventory().getHeldItemSlot(), startPrice);
    }

    /** Why this player cannot put the stack in this slot up now (a message key), or null. */
    public String problem(Player p, int slot) {
        ItemStack it = slot < 0 ? null : p.getInventory().getItem(slot);
        if (it == null || it.isEmpty() || core.economy().isCheque(it)) return "auction.hold-item";
        if (store.playerRunning() >= rules.maxRunning()) return "auction.full";
        if (store.running().stream().anyMatch(a -> p.getUniqueId().equals(a.seller))) return "auction.one-each";
        if (!core.economy().has(p.getUniqueId(), rules.auctionDeposit())) return "auction.no-deposit";
        return null;
    }

    /** Puts the stack in one inventory slot (0-8 hotbar, 9-35 the rest) up; true when the auction started. */
    public boolean startPlayer(Player p, int slot, long startPrice) {
        if (startPrice < rules.minStart()) {
            p.sendMessage(Lang.msg("auction.min-start", "min", EconomyModule.format(rules.minStart())));
            return false;
        }
        String problem = problem(p, slot);
        if (problem != null) {
            p.sendMessage(Lang.msg(problem, "max", rules.maxRunning(), "deposit", EconomyModule.format(rules.auctionDeposit())));
            return false;
        }
        ItemStack item = p.getInventory().getItem(slot).clone();
        Auction a;
        try {
            a = create(p.getUniqueId(), p.getUniqueId(), item, startPrice, rules.auctionDeposit(), false, rules.auctionMinutes());
        } catch (RuntimeException e) {
            eco.getLogger().warning("경매 등록 실패: " + e);
            p.sendMessage(dev.stsv.core.util.Text.msg("<red>저장소 오류로 등록하지 못했습니다. 물건과 돈은 그대로입니다."));
            return false;
        }
        // stored: now take the item and the deposit (same tick, the balance checked above still holds)
        p.getInventory().setItem(slot, null);
        core.economy().move(p.getUniqueId(), null, rules.auctionDeposit(), EconomyStore.Kind.ESCROW, "auction_deposit", null);
        announceStart(a, Names.mini(p));
        return true;
    }

    /** A server auction (rare: counts toward the weekly win limit). */
    public Auction startServer(UUID creator, ItemStack item, long startPrice, double minutes) {
        Auction a = create(null, creator, item, startPrice, 0, true, minutes);
        announceStart(a, Lang.raw("auction.server"));
        return a;
    }

    private Auction create(UUID seller, UUID creator, ItemStack item, long start, long deposit, boolean rare, double minutes) {
        long now = now();
        return store.create(seller, creator, Items.bytes(item), Items.plainName(item), item.getAmount(), start, deposit, rare,
                eco.calendar().week(), now + (long) (minutes * 60_000L), now);
    }

    private void announceStart(Auction a, String sellerName) {
        Bukkit.broadcast(Lang.msg(a.rare ? "auction.started-rare" : "auction.started", "id", a.id, "item", name(a), "count", a.amount,
                "seller", sellerName, "price", EconomyModule.format(a.startPrice), "minutes", Math.round((a.ends - now()) / 60_000.0)));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
    }

    // ------------------------------------------------------------------------------------------
    // Bidding
    // ------------------------------------------------------------------------------------------

    private int rareWinsOf(UUID player, int week) {
        return rareWins.computeIfAbsent(player + "|" + week, k -> store.rareWinsSync(player, week));
    }

    /** Returns null on success, else the message key of the refusal. */
    public String bid(Player p, long id, long amount) {
        Auction a = store.get(id);
        if (a == null || now() >= a.ends) return "auction.no-such";
        UUID me = p.getUniqueId();
        if (me.equals(a.seller)) return "auction.own";
        if (me.equals(a.bidder)) return "auction.already-top";
        long min = a.minNext(rules.minRaise());
        if (amount < min) return "auction.too-low";
        if (a.rare) {
            int leading = (int) store.running().stream().filter(x -> x.rare && x != a && me.equals(x.bidder)).count();
            if (rareWinsOf(me, eco.calendar().week()) + leading >= rules.rareWeeklyWins()) return "auction.rare-limit";
        }
        if (!core.economy().move(me, null, amount, EconomyStore.Kind.ESCROW, "auction_bid", "#" + a.id)) return "auction.not-enough";
        UUID previous = a.bidder;
        long previousBid = a.bid;
        a.bid = amount;
        a.bidder = me;
        a.bids++;
        boolean extended = a.extendIfLate(now(), rules.extendSeconds() * 1000L);
        store.saveBid(a);
        if (previous != null) {
            core.economy().move(null, previous, previousBid, EconomyStore.Kind.ESCROW, "auction_refund", "#" + a.id);
            Player prev = Bukkit.getPlayer(previous);
            if (prev != null) {
                prev.sendMessage(Lang.msg("auction.outbid", "id", a.id, "item", name(a), "player", Names.mini(p), "price", EconomyModule.format(amount),
                        "min", EconomyModule.format(a.minNext(rules.minRaise()))));
                prev.playSound(prev.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f);
            }
        }
        Bukkit.broadcast(Lang.msg(extended ? "auction.bid-extended" : "auction.bid", "id", a.id, "item", name(a), "player", Names.mini(p),
                "price", EconomyModule.format(amount), "seconds", rules.extendSeconds()));
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Ending
    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = now();
        for (Auction a : List.copyOf(store.running())) if (now >= a.ends) settle(a);
        // a bar per running auction
        List<Long> alive = new ArrayList<>();
        for (Auction a : store.running()) {
            alive.add(a.id);
            Component title = Lang.mm("auction.bar", "id", a.id, "item", name(a),
                    "price", EconomyModule.format(a.bidder == null ? a.startPrice : a.bid),
                    "player", a.bidder == null ? Lang.raw("auction.no-bids") : Players.name(a.bidder), "left", clock(a.ends - now));
            float progress = Math.clamp((float) (a.ends - now) / (float) Math.max(60_000L, (long) (rules.auctionMinutes() * 60_000L)), 0f, 1f);
            BossBar bar = bars.computeIfAbsent(a.id, k -> BossBar.bossBar(title, progress, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS));
            bar.name(title).progress(progress).color(a.ends - now < rules.extendSeconds() * 1000L ? BossBar.Color.RED : BossBar.Color.PURPLE);
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
        }
        for (Long id : List.copyOf(bars.keySet())) {
            if (!alive.contains(id)) {
                BossBar b = bars.remove(id);
                for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
            }
        }
    }

    private static String clock(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void settle(Auction a) {
        if (a.bidder == null) {
            store.finish(a, AuctionStore.UNSOLD);
            UUID back = a.seller != null ? a.seller : a.creator;
            if (back != null) eco.mail().deliver(back, Items.fromBytes(a.item), "auction_unsold");
            if (a.seller != null && a.deposit > 0) {
                // the deposit is kept: it leaves the economy
                core.economy().move(null, a.seller, a.deposit, EconomyStore.Kind.ESCROW, "auction_deposit_back", "#" + a.id);
                core.economy().withdraw(a.seller, a.deposit, "auction_deposit_lost");
            }
            Bukkit.broadcast(Lang.msg("auction.unsold", "id", a.id, "item", name(a)));
            return;
        }
        store.finish(a, AuctionStore.SOLD);
        // the held bid comes back to the winner on paper and is paid on as it really happened
        core.economy().move(null, a.bidder, a.bid, EconomyStore.Kind.ESCROW, "auction_settle", "#" + a.id);
        long fee = 0;
        if (a.seller != null) {
            core.economy().transfer(a.bidder, a.seller, a.bid, "auction_sale");
            fee = eco.feesWaived() ? 0 : TradeRules.fee(a.bid, rules.auctionFee());
            if (fee > 0 && !core.economy().withdraw(a.seller, fee, "auction_fee")) eco.getLogger().warning("경매 수수료를 걷지 못함 #" + a.id);
            if (a.deposit > 0) core.economy().move(null, a.seller, a.deposit, EconomyStore.Kind.ESCROW, "auction_deposit_back", "#" + a.id);
        } else {
            core.economy().withdraw(a.bidder, a.bid, "auction_buy");
            rareWins.merge(a.bidder + "|" + a.week, 1, Integer::sum);
        }
        eco.mail().deliver(a.bidder, Items.fromBytes(a.item), "auction_win");
        // achievements and missions: only at a meaningful price, once a day per seller (no wash trades); server auctions always
        ItemStack won = Items.fromBytes(a.item);
        if (eco.credit().credit(dev.stsv.economy.TradeCredit.AUCTION, a.bidder, a.seller, a.bid,
                eco.market().shopValue(Items.key(won), a.amount), rules, now())) {
            core.profiles().add(a.bidder, "auctions_won", 1);
        }
        Bukkit.broadcast(Lang.msg("auction.sold", "id", a.id, "item", name(a), "player", Players.name(a.bidder),
                "price", EconomyModule.format(a.bid), "bids", a.bids));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
        Player seller = a.seller == null ? null : Bukkit.getPlayer(a.seller);
        if (seller != null) seller.sendMessage(Lang.msg("auction.seller-paid", "price", EconomyModule.format(a.bid - fee), "fee", EconomyModule.format(fee),
                "deposit", EconomyModule.format(a.deposit)));
    }

    /** Ends an auction now (admin), as if its time ran out. */
    public boolean endNow(long id) {
        Auction a = store.get(id);
        if (a == null) return false;
        settle(a);
        return true;
    }

    /** Seller takes it back before anyone bid (deposit returned). */
    public String cancel(Player p, long id) {
        Auction a = store.get(id);
        if (a == null) return "auction.no-such";
        if (!p.getUniqueId().equals(a.seller) && !p.hasPermission("stsv.economy.admin")) return "auction.not-yours";
        if (a.bidder != null) return "auction.has-bids";
        store.finish(a, AuctionStore.CANCELLED);
        if (a.seller != null && a.deposit > 0) core.economy().move(null, a.seller, a.deposit, EconomyStore.Kind.ESCROW, "auction_deposit_back", "#" + a.id);
        eco.mail().deliver(a.seller != null ? a.seller : p.getUniqueId(), Items.fromBytes(a.item), "auction_cancel");
        Bukkit.broadcast(Lang.msg("auction.cancelled", "id", a.id, "item", name(a)));
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, "economy:auction", new View());
    }

    /** The actions of the 경매장 list (economy:auction) besides the built-in ones. */
    public static final java.util.Set<String> ACTIONS = java.util.Set.of("auction:sell");

    /** The auction as shown in the list and on top of the 입찰 menu: the item with the auction's lines under its own. */
    private ItemStack display(Auction a, List<Component> more) {
        ItemStack it = Items.fromBytes(a.item);
        List<Component> extra = Lang.items("auction.lore", "id", a.id,
                "price", EconomyModule.format(a.bidder == null ? a.startPrice : a.bid),
                "player", a.bidder == null ? Lang.raw("auction.no-bids") : Players.name(a.bidder),
                "seller", a.seller == null ? Lang.raw("auction.server") : Players.name(a.seller),
                "min", EconomyModule.format(a.minNext(rules.minRaise())), "left", dev.stsv.economy.event.EventModule.hoursText(a.ends - now()),
                "bids", a.bids);
        if (a.rare) extra.addAll(Lang.items("auction.lore-rare", "limit", rules.rareWeeklyWins()));
        extra.addAll(more);
        it.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.addAll(extra);
            meta.lore(lore);
        });
        return it;
    }

    private final class View implements MenuHandler {
        private List<Auction> shown = List.of();

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("count", String.valueOf(store.running().size()), "max", String.valueOf(rules.maxRunning()),
                    "minutes", String.valueOf(Math.round(rules.auctionMinutes())), "deposit", EconomyModule.format(rules.auctionDeposit()),
                    "extend", String.valueOf(rules.extendSeconds()),
                    "balance", EconomyModule.format(core.economy().balance(v.player().getUniqueId())));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            shown = new ArrayList<>(store.running());
            List<ItemStack> out = new ArrayList<>();
            UUID me = v.player().getUniqueId();
            for (Auction a : shown) {
                String click = me.equals(a.seller) ? "auction.lore-own" : me.equals(a.bidder) ? "auction.lore-top" : "auction.lore-click";
                out.add(display(a, Lang.items(click, "min", EconomyModule.format(a.minNext(rules.minRaise())))));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            Auction a = shown.get(index);
            Player p = v.player();
            Bukkit.getScheduler().runTask(eco, () -> openBid(p, a.id));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("auction:sell")) Bukkit.getScheduler().runTask(eco, () -> openSell(v.player()));
        }
    }

    // ------------------------------------------------------------------------------------------
    // 경매 등록 (economy:auction_sell)
    // ------------------------------------------------------------------------------------------

    /** The 경매 등록 menu: the held stack first (another one by clicking it below), then the start price. */
    public void openSell(Player p) {
        new SellMenu(p).open(p);
    }

    private final class SellMenu extends dev.stsv.economy.market.PriceMenu {
        private int slot;

        SellMenu(Player p) {
            super(eco, rules.minStart());
            int held = p.getInventory().getHeldItemSlot();
            ItemStack hand = p.getInventory().getItem(held);
            slot = hand == null || hand.isEmpty() ? -1 : held;
            price = suggested(p);
        }

        private ItemStack item(Player p) {
            ItemStack it = slot < 0 ? null : p.getInventory().getItem(slot);
            return it == null || it.isEmpty() ? null : it;
        }

        private long shopPrice(Player p) {
            ItemStack it = item(p);
            return it == null ? 0 : (long) Math.ceil(eco.market().shopValue(Items.key(it), it.getAmount()) - 1e-9);
        }

        /** The shop's value of the stack, else 100 G (never under the least start price). */
        private long suggested(Player p) {
            long shop = shopPrice(p);
            return Math.max(rules.minStart(), shop > 0 ? shop : 100);
        }

        @Override
        protected long shopPrice(MenuView v) {
            return shopPrice(v.player());
        }

        @Override
        protected long min(MenuView v) {
            return rules.minStart();
        }

        @Override
        protected String menuId() {
            return "economy:auction_sell";
        }

        @Override
        protected Map<String, String> values(MenuView v) {
            Player p = v.player();
            ItemStack it = item(p);
            long fee = eco.feesWaived() ? 0 : TradeRules.fee(price, rules.auctionFee());
            Map<String, String> m = new HashMap<>();
            m.put("item", it == null ? Lang.raw("price.no-item") : Items.mmName(it) + " ×" + it.getAmount());
            m.put("min", EconomyModule.format(rules.minStart()));
            m.put("deposit", EconomyModule.format(rules.auctionDeposit()));
            m.put("minutes", String.valueOf(Math.round(rules.auctionMinutes())));
            m.put("extend", String.valueOf(rules.extendSeconds()));
            m.put("fee_rate", String.valueOf(Math.round(rules.auctionFee() * 100)));
            m.put("receive", EconomyModule.format(price - fee));
            m.put("running", String.valueOf(store.playerRunning()));
            m.put("max", String.valueOf(rules.maxRunning()));
            long shop = shopPrice(p);
            m.put("shop", shop > 0 ? EconomyModule.format(shop) : Lang.raw("price.no-shop"));
            m.put("credit", EconomyModule.format(rules.creditFloor(it == null ? 0 : eco.market().shopValue(Items.key(it), it.getAmount()))));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button b, ItemStack base) {
            Player p = v.player();
            if (b.key() == 'S') {
                ItemStack it = item(p);
                if (it == null) return base;
                ItemStack show = it.clone();
                show.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(Lang.items("price.picked-lore"));
                    meta.lore(lore);
                });
                return show;
            }
            if (b.key() == 'C') {
                String problem = problem(p, slot);
                if (problem != null) return dev.stsv.economy.market.PriceMenu.blocked(base, problem.equals("auction.hold-item") ? "price.pick-item" : problem,
                        "max", rules.maxRunning(), "deposit", EconomyModule.format(rules.auctionDeposit()));
            }
            return base;
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int clicked, ClickType click) {
            Player p = v.player();
            if (clicked < 0 || clicked > 35) return;
            ItemStack it = p.getInventory().getItem(clicked);
            if (it == null || it.isEmpty()) return;
            slot = clicked;
            if (!edited) price = suggested(p);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
            v.refresh();
        }

        @Override
        protected void confirm(MenuView v) {
            Player p = v.player();
            if (problem(p, slot) != null) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            long at = price;
            Bukkit.getScheduler().runTask(eco, () -> {
                if (startPlayer(p, slot, at)) AuctionModule.this.open(p);
            });
        }

        @Override
        protected void back(Player p) {
            AuctionModule.this.open(p);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 입찰 (economy:auction_bid)
    // ------------------------------------------------------------------------------------------

    /** The 입찰 menu of one auction (from the 경매장 list, or /경매 입찰 &lt;번호&gt;). */
    public void openBid(Player p, long id) {
        Auction a = store.get(id);
        if (a == null || now() >= a.ends) {
            p.sendMessage(Lang.msg("auction.no-such"));
            return;
        }
        new BidMenu(a).open(p);
    }

    private final class BidMenu extends dev.stsv.economy.market.PriceMenu {
        private final long id;

        BidMenu(Auction a) {
            super(eco, a.minNext(rules.minRaise()));
            this.id = a.id;
        }

        /** The auction while it runs, else null (ended, cancelled). */
        private Auction auction() {
            Auction a = store.get(id);
            return a == null || now() >= a.ends ? null : a;
        }

        @Override
        protected long min(MenuView v) {
            Auction a = auction();
            return a == null ? 1 : a.minNext(rules.minRaise());
        }

        @Override
        protected String menuId() {
            return "economy:auction_bid";
        }

        @Override
        protected Map<String, String> values(MenuView v) {
            Auction a = auction();
            long min = min(v);
            long balance = core.economy().balance(v.player().getUniqueId());
            Map<String, String> m = new HashMap<>();
            m.put("id", String.valueOf(id));
            m.put("min", EconomyModule.format(min));
            m.put("raise", String.valueOf(Math.round(rules.minRaise() * 100)));
            m.put("bid5", EconomyModule.format(dev.stsv.economy.market.PriceInput.raise(min, 5)));
            m.put("bid10", EconomyModule.format(dev.stsv.economy.market.PriceInput.raise(min, 10)));
            m.put("current", a == null ? "-" : EconomyModule.format(a.bidder == null ? a.startPrice : a.bid));
            m.put("bidder", a == null || a.bidder == null ? Lang.raw("auction.no-bids") : Players.name(a.bidder));
            m.put("left", a == null ? "-" : clock(a.ends - now()));
            m.put("balance", EconomyModule.format(balance));
            m.put("after", EconomyModule.format(balance - Math.max(min, price)));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button b, ItemStack base) {
            Auction a = auction();
            UUID me = v.player().getUniqueId();
            if (b.key() == 'S') return a == null ? base : display(a, List.of());
            if (b.key() == 'C') {
                if (a == null) return dev.stsv.economy.market.PriceMenu.blocked(base, "auction.no-such");
                if (me.equals(a.seller)) {
                    base.editMeta(meta -> {
                        meta.setItemModel(new org.bukkit.NamespacedKey("economy", a.bidder == null ? "gui/cancel" : "gui/confirm_off"));
                        meta.itemName(Lang.item(a.bidder == null ? "auction.cancel-button" : "auction.has-bids"));
                        meta.lore(Lang.items(a.bidder == null ? "auction.cancel-lore" : "price.blocked-lore"));
                    });
                    return base;
                }
                if (me.equals(a.bidder)) return dev.stsv.economy.market.PriceMenu.blocked(base, "auction.already-top");
                if (!core.economy().has(me, price)) return dev.stsv.economy.market.PriceMenu.blocked(base, "auction.not-enough");
            }
            return base;
        }

        @Override
        protected void confirm(MenuView v) {
            Player p = v.player();
            Auction a = auction();
            if (a == null) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            if (p.getUniqueId().equals(a.seller)) {
                String refused = cancel(p, id);
                if (refused != null) HudModule.flash(p, Lang.mm(refused));
                else Bukkit.getScheduler().runTask(eco, () -> AuctionModule.this.open(p));
                return;
            }
            long min = a.minNext(rules.minRaise());
            long at = Math.max(price, min);
            String refused = bid(p, id, at);
            if (refused != null) {
                HudModule.flash(p, Lang.mm(refused, "min", EconomyModule.format(min), "limit", rules.rareWeeklyWins()));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                v.refresh();
                return;
            }
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
            Bukkit.getScheduler().runTask(eco, () -> AuctionModule.this.open(p));
        }

        @Override
        protected void back(Player p) {
            AuctionModule.this.open(p);
        }
    }
}
