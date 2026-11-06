package dev.stsv.economy.auction;

import dev.stsv.core.boss.BossDefeatEvent;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.TradeRules;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경매: 30 minutes of open bidding with server-wide announcements. Bids are held (escrow) and given
 * back when someone bids higher; a bid in the last minute adds a minute. 7% fee and a 100G deposit
 * (returned when it sells). At most 3 player auctions at once. Server auctions (world boss loot,
 * 대경매) count toward a weekly limit of rare wins per player.
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
        eco.events().onStart("world_boss", this::spawnWorldBoss);
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
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty() || core.economy().isCheque(hand)) {
            p.sendMessage(Lang.msg("auction.hold-item"));
            return;
        }
        if (startPrice < rules.minStart()) {
            p.sendMessage(Lang.msg("auction.min-start", "min", EconomyModule.format(rules.minStart())));
            return;
        }
        if (store.playerRunning() >= rules.maxRunning()) {
            p.sendMessage(Lang.msg("auction.full", "max", rules.maxRunning()));
            return;
        }
        if (store.running().stream().anyMatch(a -> p.getUniqueId().equals(a.seller))) {
            p.sendMessage(Lang.msg("auction.one-each"));
            return;
        }
        if (!core.economy().has(p.getUniqueId(), rules.auctionDeposit())) {
            p.sendMessage(Lang.msg("auction.no-deposit", "deposit", EconomyModule.format(rules.auctionDeposit())));
            return;
        }
        ItemStack item = hand.clone();
        Auction a;
        try {
            a = create(p.getUniqueId(), p.getUniqueId(), item, startPrice, rules.auctionDeposit(), false, rules.auctionMinutes());
        } catch (RuntimeException e) {
            eco.getLogger().warning("경매 등록 실패: " + e);
            p.sendMessage(dev.stsv.core.util.Text.msg("<red>저장소 오류로 등록하지 못했습니다. 물건과 돈은 그대로입니다."));
            return;
        }
        // stored: now take the item and the deposit (same tick, the balance checked above still holds)
        p.getInventory().setItemInMainHand(null);
        core.economy().move(p.getUniqueId(), null, rules.auctionDeposit(), EconomyStore.Kind.ESCROW, "auction_deposit", null);
        announceStart(a, p.getName());
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
                prev.sendMessage(Lang.msg("auction.outbid", "id", a.id, "item", name(a), "player", p.getName(), "price", EconomyModule.format(amount),
                        "min", EconomyModule.format(a.minNext(rules.minRaise()))));
                prev.playSound(prev.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f);
            }
        }
        Bukkit.broadcast(Lang.msg(extended ? "auction.bid-extended" : "auction.bid", "id", a.id, "item", name(a), "player", p.getName(),
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
        core.profiles().add(a.bidder, "auctions_won", 1);
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
    // World boss: the loot is only sold by auction; the best fighters get money
    // ------------------------------------------------------------------------------------------

    private void spawnWorldBoss(ActiveEvent e) {
        String boss = e.params().getOrDefault("boss", "executioner");
        Location at = eco.location("world-boss");
        if (at == null) at = Bukkit.getWorlds().getFirst().getSpawnLocation();
        Location spawn = at;
        e.params().put("boss", boss);
        e.params().put("detail", Lang.raw("auction.world-boss-detail", "x", at.getBlockX(), "z", at.getBlockZ()));
        // a moment later, so the announcement comes first
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (core.bosses().spawn(boss, spawn).isEmpty()) eco.getLogger().warning("월드 보스 소환 실패: " + boss);
        }, 100L);
    }

    @EventHandler
    public void onBossDefeat(BossDefeatEvent e) {
        List<ActiveEvent> events = eco.events().active("world_boss");
        ActiveEvent ev = events.stream().filter(x -> e.bossId().equals(x.param("boss"))).findFirst().orElse(null);
        if (ev == null) return;
        long start = (long) ev.number("start-price", 1000);
        double minutes = ev.number("auction-minutes", rules.auctionMinutes());
        for (ItemStack it : e.drops()) startServer(null, it, start, minutes);
        e.drops().clear();
        // rewards for the top fighters
        long[] rewards = {(long) ev.number("reward-1", 3000), (long) ev.number("reward-2", 2000), (long) ev.number("reward-3", 1000)};
        List<Map.Entry<UUID, Double>> top = e.damage().entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder())).limit(3).toList();
        for (int i = 0; i < top.size(); i++) {
            UUID u = top.get(i).getKey();
            if (rewards[i] > 0 && core.economy().hasAccount(u)) {
                core.economy().deposit(u, rewards[i], "world_boss");
                Bukkit.broadcast(Lang.msg("auction.boss-reward", "rank", i + 1, "player", Players.name(u), "amount", EconomyModule.format(rewards[i])));
            }
        }
        eco.events().end("world_boss");
    }

    // ------------------------------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, "economy:auction", new View());
    }

    private final class View implements MenuHandler {
        private List<Auction> shown = List.of();
        private long armed = -1, armedAt;

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("count", String.valueOf(store.running().size()), "max", String.valueOf(rules.maxRunning()),
                    "balance", EconomyModule.format(core.economy().balance(v.player().getUniqueId())));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            shown = new ArrayList<>(store.running());
            List<ItemStack> out = new ArrayList<>();
            for (Auction a : shown) {
                ItemStack it = Items.fromBytes(a.item);
                boolean isArmed = armed == a.id && now() - armedAt < 5000;
                List<Component> extra = Lang.items("auction.lore", "id", a.id,
                        "price", EconomyModule.format(a.bidder == null ? a.startPrice : a.bid),
                        "player", a.bidder == null ? Lang.raw("auction.no-bids") : Players.name(a.bidder),
                        "seller", a.seller == null ? Lang.raw("auction.server") : Players.name(a.seller),
                        "min", EconomyModule.format(a.minNext(rules.minRaise())), "left", dev.stsv.economy.event.EventModule.hoursText(a.ends - now()),
                        "bids", a.bids);
                if (a.rare) extra.addAll(Lang.items("auction.lore-rare", "limit", rules.rareWeeklyWins()));
                extra.addAll(Lang.items(isArmed ? "auction.lore-confirm" : "auction.lore-click", "min", EconomyModule.format(a.minNext(rules.minRaise()))));
                it.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(extra);
                    meta.lore(lore);
                    if (isArmed) meta.setEnchantmentGlintOverride(true);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            Auction a = shown.get(index);
            Player p = v.player();
            if (armed != a.id || now() - armedAt > 5000) {
                armed = a.id;
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = -1;
            String refused = bid(p, a.id, a.minNext(rules.minRaise()));
            if (refused != null) HudModule.flash(p, Lang.mm(refused, "min", EconomyModule.format(a.minNext(rules.minRaise())), "limit", rules.rareWeeklyWins()));
            v.refresh();
        }
    }
}
