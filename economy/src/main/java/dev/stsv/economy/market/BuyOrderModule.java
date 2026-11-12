package dev.stsv.economy.market;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.TradeCredit;
import dev.stsv.economy.TradeRules;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 거래소 구매 주문: "I want 500 밀가루 at 12 G each". The whole amount x price is held from the buyer at once (escrow); anyone
 * hands items over from the 구매 주문 tab and is paid at once from it, minus the 거래소 fee; the items go to the buyer's 우편함.
 * Partial fills, cancel and expiry (the rest comes back). Only what the shop buys can be ordered (so never cheques, vehicles,
 * cosmetics or vouchers), matched as the shop matches it ({@link Items#plain}); a fish of any size fills an order, the smallest
 * first. A fill is not a shop sale: it does not move anyone's shop price.
 * <p>
 * Order of a fill: the seller's items are taken (main thread), the claim + the fill's record + the buyer's mail commit in one
 * transaction (refused: the items go back), then the money moves. See {@link BuyOrderStore}.
 */
public final class BuyOrderModule extends EcoModule {

    /** Every action of the 구매 주문 menus (besides close and the pages): see menus/market.yml. */
    public static final Set<String> LIST_ACTIONS = Set.of("order:mine", "order:new", "order:search", "order:refresh", "order:listings", "order:orders");
    public static final Set<String> QTY_ACTIONS = Set.of("order:qty:1", "order:qty:64", "order:qty:type", "order:pick");
    public static final Set<String> PICK_ACTIONS = Set.of("order:pick:search", "order:pick:back");

    private BuyOrderStore store;
    /** Fills on their way to the database, per order (main thread): no one is offered what is being handed over. */
    private final Map<Long, Integer> reserved = new HashMap<>();

    public BuyOrderModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-orders";
    }

    @Override
    protected void start() {
        store = new BuyOrderStore(core.db());
        store.init();
        recover();
        every(1200L, this::expire);
        eco.onReset(this::reset);
    }

    public BuyOrderStore store() {
        return store;
    }

    private TradeRules rules() {
        return eco.rules();
    }

    /** The server stopped between a commit and the money: pay those fills, refund those closed orders. */
    private void recover() {
        for (BuyOrderStore.Fill f : store.unpaid()) {
            eco.getLogger().info("구매 주문 #" + f.order() + ": 멈추기 전 체결의 대금을 지급합니다 (" + f.gross() + " G)");
            settle(f, false);
        }
        for (BuyOrderStore.Closed c : store.unrefunded()) {
            eco.getLogger().info("구매 주문 #" + c.order().id() + ": 멈추기 전 닫힌 주문의 남은 돈을 돌려줍니다 (" + c.refund() + " G)");
            refund(c);
        }
    }

    /** /관리자 초기화 경제서버: every open order gives its money back first, then they are gone. */
    private void reset() {
        for (BuyOrderStore.Order o : store.active()) {
            if (o.escrow() > 0) core.economy().move(null, o.buyer(), o.escrow(), EconomyStore.Kind.ESCROW, "buy_order_refund", "#" + o.id());
        }
        reserved.clear();
        store.reset();
    }

    // ------------------------------------------------------------------------------------------
    // What can be ordered
    // ------------------------------------------------------------------------------------------

    /** What the shop buys (and so has a price), and never a cheque, vehicle, piece of furniture, cosmetic or voucher. */
    public boolean orderable(String key) {
        if (key == null || !eco.shop().catalog().sell().containsKey(key)) return false;
        return core.content().item(key).map(i -> !i.isVehicle() && !i.isFurniture() && i.voucher() == null && i.cosmetic() == null).orElse(true);
    }

    /** Every orderable item, in the shop's category order (the 물건 고르기 list). */
    public List<String> orderables() {
        Set<String> out = new LinkedHashSet<>();
        for (var c : eco.shop().catalog().sellCategories()) out.addAll(c.items());
        out.addAll(eco.shop().catalog().sell().keySet());
        out.removeIf(k -> !orderable(k));
        return new ArrayList<>(out);
    }

    /** What the shop pays per unit now to someone who sold none lately (rounded up; 0: it does not buy it). */
    public long shopUnit(String key) {
        if (key == null) return 0;
        double m = eco.shop().marketPrice(key);
        return m <= 0 ? 0 : (long) Math.ceil(m - 1e-9);
    }

    /** What an order still wants, less what is being handed over right now. */
    private int left(BuyOrderStore.Order o) {
        return o.left() - reserved.getOrDefault(o.id(), 0);
    }

    // ------------------------------------------------------------------------------------------
    // Placing
    // ------------------------------------------------------------------------------------------

    /** /거래소 주문 &lt;개당 가격&gt; [수량]: an order for the held item (amount -1: as many as are held). */
    public boolean placeHeld(Player p, long unit, long amount) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            p.sendMessage(Lang.msg("order.hold-item"));
            return false;
        }
        if (unit <= 0 || amount == 0 || amount < -1) {
            p.sendMessage(Lang.msg("order.usage"));
            return false;
        }
        return place(p, Items.key(hand), amount < 0 ? hand.getAmount() : (int) Math.min(Integer.MAX_VALUE, amount), unit);
    }

    /** Why this order cannot be placed now (a message key), or null. */
    public String problem(Player p, String key, int amount, long unit) {
        if (key == null) return "order.pick-item";
        if (!orderable(key)) return "order.not-orderable";
        return BuyOrders.placeProblem(rules(), store.count(p.getUniqueId()), amount, unit, core.economy().balance(p.getUniqueId()));
    }

    /** Places an order: the money is held first, then the order is stored (refunded if that fails). True when placed. */
    public boolean place(Player p, String key, int amount, long unit) {
        UUID me = p.getUniqueId();
        String problem = problem(p, key, amount, unit);
        if (problem != null) {
            p.sendMessage(Lang.msg(problem, "limit", rules().orderMax(), "min", EconomyModule.format(rules().orderMinPrice()),
                    "max", rules().orderMaxAmount(), "total", EconomyModule.format(amount > 0 && unit > 0 && unit <= PriceInput.MAX ? BuyOrders.escrow(amount, unit) : 0)));
            return false;
        }
        long total = BuyOrders.escrow(amount, unit);
        if (!core.economy().move(me, null, total, EconomyStore.Kind.ESCROW, "buy_order_escrow", key + " x" + amount)) {
            p.sendMessage(Lang.msg("order.not-enough", "total", EconomyModule.format(total)));
            return false;
        }
        BuyOrderStore.Order o;
        long now = now();
        try {
            String name = Items.create(key, 1).map(Items::plainName).orElse(key);
            o = store.add(me, key, name, amount, unit, now, now + (long) (rules().orderHours() * 3_600_000L));
        } catch (RuntimeException e) {
            core.economy().move(null, me, total, EconomyStore.Kind.ESCROW, "buy_order_refund", key + " x" + amount);
            eco.getLogger().warning("구매 주문 등록 실패: " + e);
            p.sendMessage(dev.stsv.core.util.Text.msg("<red>저장소 오류로 주문하지 못했습니다. 돈은 돌려받았습니다."));
            return false;
        }
        eco.getLogger().info("구매 주문 #" + o.id() + " 등록: " + p.getName() + " " + key + " ×" + amount + " 개당 " + unit + " G (맡김 " + total + " G)");
        p.sendMessage(Lang.msg("order.placed", "item", Items.mmName(key), "count", amount, "price", EconomyModule.format(unit),
                "total", EconomyModule.format(total), "days", days()));
        long shop = shopUnit(key);
        if (shop > 0 && unit < shop) p.sendMessage(Lang.msg("order.below-shop", "shop", EconomyModule.format(shop)));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
        return true;
    }

    /** How long an order stands, for messages ("7" days, "1.5"). */
    private String days() {
        double d = rules().orderHours() / 24.0;
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.1f", d);
    }

    // ------------------------------------------------------------------------------------------
    // Filling
    // ------------------------------------------------------------------------------------------

    /** The seller hands items over: one stack at most, or (all) as many as they have. */
    public void fill(Player p, BuyOrderStore.Order o, boolean all) {
        UUID me = p.getUniqueId();
        Predicate<ItemStack> same = it -> Items.plain(it, o.itemKey());
        int have = Items.count(p.getInventory(), same);
        String problem = BuyOrders.fillProblem(o.buyer(), me, left(o), have);
        if (problem != null) {
            HudModule.flash(p, Lang.mm(problem, "item", Items.mmName(o.itemKey())));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        int n = BuyOrders.fillCount(left(o), have, all, stackSize(o.itemKey()));
        // taken first: whatever happens next, the items exist once (with the buyer, or back with the seller)
        List<ItemStack> taken = take(p.getInventory(), same, n);
        List<ItemStack> parcels = merged(taken);
        int got = taken.stream().mapToInt(ItemStack::getAmount).sum();
        if (got != n) {
            eco.getLogger().warning("구매 주문 수량 불일치: " + p.getName() + " " + o.itemKey() + " " + got + "/" + n);
            for (ItemStack it : parcels) eco.mail().deliver(me, it, BuyOrderStore.MAIL_RETURN);
            return;
        }
        long fee = eco.market().fee(BuyOrders.escrow(n, o.price()));
        List<byte[]> bytes = parcels.stream().map(Items::bytes).toList();
        reserved.merge(o.id(), n, Integer::sum);
        store.fill(o.id(), me, n, fee, bytes, now()).whenComplete((f, err) -> sync(() -> {
            reserved.computeIfPresent(o.id(), (k, v) -> v - n <= 0 ? null : v - n);
            if (err != null || f == null) {
                if (err != null) eco.getLogger().warning("구매 주문 #" + o.id() + " 체결 실패: " + err);
                for (ItemStack it : parcels) eco.mail().deliver(me, it, BuyOrderStore.MAIL_RETURN);
                Player s = Bukkit.getPlayer(me);
                if (s != null) HudModule.flash(s, Lang.mm(err != null ? "order.failed" : "order.gone"));
                return;
            }
            store.applied(o.id(), n);
            settle(f, true);
        }));
    }

    /** Pays a committed fill: the buyer's held money goes to the seller (player to player), the fee leaves the economy. */
    private void settle(BuyOrderStore.Fill f, boolean live) {
        String note = "#" + f.order() + " " + f.itemKey() + " x" + f.amount();
        // as an auction settles: the held money is the buyer's again on paper, then paid on as it really happened
        core.economy().move(null, f.buyer(), f.gross(), EconomyStore.Kind.ESCROW, "buy_order_settle", note);
        if (!core.economy().transfer(f.buyer(), f.seller(), f.gross(), "buy_order_sale"))
            eco.getLogger().warning("구매 주문 대금을 넘기지 못함: 주문 #" + f.order() + " 체결 " + f.id());
        if (f.fee() > 0 && !core.economy().withdraw(f.seller(), f.fee(), "buy_order_fee"))
            eco.getLogger().warning("구매 주문 수수료를 걷지 못함: 주문 #" + f.order() + " 체결 " + f.id());
        store.paid(f.id());
        eco.getLogger().info("구매 주문 #" + f.order() + " 체결: " + Players.name(f.seller()) + " → " + Players.name(f.buyer()) + " " + f.itemKey()
                + " ×" + f.amount() + ", " + f.gross() + " G (수수료 " + f.fee() + " G)");
        // achievements and missions: as a 거래소 sale (a meaningful price, once a day per pair)
        if (eco.credit().credit(TradeCredit.MARKET, f.seller(), f.buyer(), f.gross(), eco.market().shopValue(f.itemKey(), f.amount()), rules(), now())) {
            core.profiles().add(f.seller(), "market_sold", 1);
        }
        if (!live) return;
        String item = Items.mmName(f.itemKey());
        Player seller = Bukkit.getPlayer(f.seller());
        if (seller != null) {
            seller.sendMessage(Lang.msg("order.sold", "item", item, "count", f.amount(), "price", EconomyModule.format(f.gross()),
                    "fee", EconomyModule.format(f.fee()), "receive", EconomyModule.format(f.gross() - f.fee()), "buyer", Players.name(f.buyer())));
            seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
        }
        Player buyer = Bukkit.getPlayer(f.buyer());
        if (buyer != null) {
            BuyOrderStore.Order o = store.get(f.order());
            buyer.sendMessage(Lang.msg("order.filled", "item", item, "count", f.amount(), "price", EconomyModule.format(f.gross()),
                    "seller", seller != null ? Names.mini(seller) : Players.name(f.seller()), "left", o == null ? 0 : o.left()));
            buyer.playSound(buyer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
            store.told(f.id());
        }
    }

    /** The stack size of an item (one click hands over at most this many). */
    private static int stackSize(String key) {
        return Items.create(key, 1).map(ItemStack::getMaxStackSize).orElse(64);
    }

    /** Takes n matching items from the storage slots, the smallest fish first; returns what was taken. */
    private static List<ItemStack> take(PlayerInventory inv, Predicate<ItemStack> same, int n) {
        ItemStack[] contents = inv.getStorageContents();
        double[] factor = new double[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            factor[i] = it != null && !it.isEmpty() && same.test(it) ? Items.sizeFactor(it) : Double.NaN;
        }
        List<ItemStack> taken = new ArrayList<>();
        int left = n;
        for (int i : BuyOrders.smallestFirst(factor)) {
            if (left <= 0) break;
            ItemStack it = contents[i];
            int k = Math.min(left, it.getAmount());
            ItemStack part = it.clone();
            part.setAmount(k);
            taken.add(part);
            it.setAmount(it.getAmount() - k);
            contents[i] = it.getAmount() <= 0 ? null : it;
            left -= k;
        }
        inv.setStorageContents(contents);
        return taken;
    }

    /** Taken pieces packed into full stacks (alike ones together), each one mail. */
    private static List<ItemStack> merged(List<ItemStack> taken) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack t : taken) {
            int left = t.getAmount();
            for (ItemStack o : out) {
                if (left <= 0) break;
                if (!o.isSimilar(t)) continue;
                int k = Math.min(left, o.getMaxStackSize() - o.getAmount());
                if (k <= 0) continue;
                o.setAmount(o.getAmount() + k);
                left -= k;
            }
            while (left > 0) {
                ItemStack c = t.clone();
                int k = Math.min(left, Math.max(1, t.getMaxStackSize()));
                c.setAmount(k);
                out.add(c);
                left -= k;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Cancel, expiry
    // ------------------------------------------------------------------------------------------

    /** The buyer (or an admin) takes an order off: what it still holds comes back. */
    public void cancel(Player p, BuyOrderStore.Order o) {
        boolean admin = !o.buyer().equals(p.getUniqueId());
        if (admin && !p.hasPermission("stsv.economy.admin")) return;
        store.close(o.id(), BuyOrderStore.CANCELLED, now()).thenAccept(c -> sync(() -> {
            if (c == null) {
                HudModule.flash(p, Lang.mm("order.gone"));
                return;
            }
            refund(c);
            eco.getLogger().info("구매 주문 #" + o.id() + " 취소" + (admin ? " (관리자 " + p.getName() + ")" : "") + ": " + c.refund() + " G 환불");
            HudModule.flash(p, Lang.mm("order.cancelled", "item", Items.mmName(o.itemKey()), "refund", EconomyModule.format(c.refund())));
            Player b = admin ? Bukkit.getPlayer(o.buyer()) : null;
            if (b != null) b.sendMessage(Lang.msg("order.cancelled-admin", "item", Items.mmName(o.itemKey()), "refund", EconomyModule.format(c.refund())));
        }));
    }

    private void refund(BuyOrderStore.Closed c) {
        if (c.refund() > 0) core.economy().move(null, c.order().buyer(), c.refund(), EconomyStore.Kind.ESCROW, "buy_order_refund", "#" + c.order().id());
        store.refunded(c.order().id());
    }

    private void expire() {
        for (BuyOrderStore.Order o : store.expired(now())) {
            store.close(o.id(), BuyOrderStore.EXPIRED, now()).thenAccept(c -> sync(() -> {
                if (c == null) return;
                refund(c);
                eco.getLogger().info("구매 주문 #" + o.id() + " 만료: " + c.refund() + " G 환불");
                Player b = Bukkit.getPlayer(o.buyer());
                if (b != null) {
                    b.sendMessage(Lang.msg("order.expired", "item", Items.mmName(o.itemKey()), "left", c.order().left(),
                            "refund", EconomyModule.format(c.refund())));
                    store.toldExpired(o.id());
                }
            }));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        store.untold(p.getUniqueId()).thenAccept(u -> sync(() -> {
            if (!p.isOnline()) return;
            if (!u.fills().isEmpty()) {
                int count = u.fills().stream().mapToInt(BuyOrderStore.Fill::amount).sum();
                p.sendMessage(Lang.msg("order.away", "fills", u.fills().size(), "count", count));
                for (BuyOrderStore.Fill f : u.fills().subList(0, Math.min(5, u.fills().size()))) {
                    p.sendMessage(Lang.mm("order.away-line", "item", Items.mmName(f.itemKey()), "count", f.amount(),
                            "price", EconomyModule.format(f.gross()), "seller", Players.name(f.seller())));
                }
            }
            for (BuyOrderStore.Order o : u.expired()) {
                p.sendMessage(Lang.msg("order.expired-away", "item", Items.mmName(o.itemKey()), "left", o.left()));
            }
        }));
    }

    // ------------------------------------------------------------------------------------------
    // 구매 주문 tab (economy:market_orders)
    // ------------------------------------------------------------------------------------------

    public void open(Player p, String search, boolean mine) {
        core.menus().open(p, "economy:market_orders", new View(search, mine));
    }

    /** The ids a search finds: by id, by the item's name, or by its shop category (as /시세 finds them). */
    private Set<String> search(String text) {
        return new java.util.HashSet<>(eco.shop().catalog().search(text, id -> Items.create(id, 1).map(Items::plainName).orElse(id)));
    }

    private final class View implements MenuHandler {
        private final String search;
        private final Set<String> found;
        private final boolean mine;
        private List<BuyOrderStore.Order> shown = List.of();
        private long armed = -1, armedAt;
        private boolean armedAll;

        View(String search, boolean mine) {
            this.search = search;
            this.mine = mine;
            this.found = search == null ? null : search(search);
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID me = v.player().getUniqueId();
            Map<String, String> m = new HashMap<>();
            m.put("mode", Lang.raw(mine ? "order.mode-mine" : search != null ? "market.mode-search" : "order.mode-all", "search",
                    search == null ? "" : MarketModule.titleText(search, 8)));
            m.put("search", search == null ? Lang.raw("market.no-search") : MarketModule.titleText(search, 16));
            m.put("mine_toggle", Lang.raw(mine ? "order.show-all" : "order.show-mine"));
            m.put("count", String.valueOf(shown.size()));
            m.put("mine", String.valueOf(store.count(me)));
            m.put("limit", String.valueOf(rules().orderMax()));
            m.put("held", EconomyModule.format(store.escrowOf(me)));
            m.put("balance", EconomyModule.format(core.economy().balance(me)));
            m.put("fee", String.valueOf(Math.round(rules().marketFee() * 100)));
            m.put("days", days());
            return m;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Player p = v.player();
            UUID me = p.getUniqueId();
            List<BuyOrderStore.Order> all = new ArrayList<>();
            for (BuyOrderStore.Order o : store.active()) {
                if (mine && !o.buyer().equals(me)) continue;
                if (found != null && !found.contains(o.itemKey()) && !o.itemName().contains(search)) continue;
                if (left(o) <= 0 && !o.buyer().equals(me)) continue;
                all.add(o);
            }
            shown = all;
            Map<String, Integer> have = new HashMap<>();
            List<ItemStack> out = new ArrayList<>();
            for (BuyOrderStore.Order o : all) {
                int left = left(o);
                ItemStack it = Items.create(o.itemKey(), 1).orElseGet(() -> new ItemStack(Material.PAPER));
                it.setAmount(Math.clamp(left, 1, it.getMaxStackSize()));
                long shop = shopUnit(o.itemKey());
                List<Component> extra = Lang.items("order.lore", "price", EconomyModule.format(o.price()), "left", left, "amount", o.amount(),
                        "buyer", Players.name(o.buyer()), "time", dev.stsv.economy.event.EventModule.hoursText(o.expires() - now()),
                        "shop", shop > 0 ? EconomyModule.format(shop) : Lang.raw("price.no-shop"));
                if (o.buyer().equals(me)) {
                    extra.addAll(Lang.items("order.lore-own", "held", EconomyModule.format(o.escrow())));
                } else {
                    int mineCount = have.computeIfAbsent(o.itemKey(), k -> Items.count(p.getInventory(), x -> Items.plain(x, k)));
                    boolean isArmed = armed == o.id() && now() - armedAt < 5000;
                    if (isArmed) {
                        int n = BuyOrders.fillCount(left, mineCount, armedAll, it.getMaxStackSize());
                        long gross = BuyOrders.escrow(n, o.price()), fee = eco.market().fee(gross);
                        extra.addAll(Lang.items("order.lore-confirm", "count", n, "receive", EconomyModule.format(gross - fee),
                                "fee", EconomyModule.format(fee)));
                        it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
                    } else {
                        extra.addAll(Lang.items(mineCount > 0 ? "order.lore-fill" : "order.lore-none", "have", mineCount));
                    }
                }
                it.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(extra);
                    meta.lore(lore);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            BuyOrderStore.Order o = store.get(shown.get(index).id());
            Player p = v.player();
            if (o == null) {
                HudModule.flash(p, Lang.mm("order.gone"));
                v.refresh();
                return;
            }
            if (o.buyer().equals(p.getUniqueId()) || (click.isShiftClick() && click.isRightClick() && p.hasPermission("stsv.economy.admin"))) {
                cancel(p, o);
                Bukkit.getScheduler().runTaskLater(eco, v::refresh, 2L);
                return;
            }
            // two clicks to hand over (the same kind of click): the first one only shows how many and for how much
            boolean all = click.isShiftClick();
            if (armed != o.id() || armedAll != all || now() - armedAt > 5000) {
                armed = o.id();
                armedAll = all;
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = -1;
            fill(p, o, all);
            Bukkit.getScheduler().runTaskLater(eco, v::refresh, 2L);
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base != null && b.action().equals("order:orders")) base.editMeta(m -> m.setEnchantmentGlintOverride(true));
            return base;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "order:mine" -> Bukkit.getScheduler().runTask(eco, () -> open(p, null, !mine));
                case "order:refresh", "order:orders" -> v.refresh();
                case "order:new" -> Bukkit.getScheduler().runTask(eco, () -> openNew(p, null));
                case "order:listings" -> Bukkit.getScheduler().runTask(eco, () -> eco.market().open(p, null, false));
                case "order:search" -> Bukkit.getScheduler().runTask(eco, () -> {
                    if (click.isRightClick()) {
                        open(p, null, false);
                        return;
                    }
                    core.menus().prompt(p, Lang.msg("market.search-ask"), text -> {
                        if (p.isOnline()) open(p, text == null || text.isBlank() ? search : text.trim(), false);
                    });
                });
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 구매 주문 넣기 (economy:market_order) and 물건 고르기 (economy:market_order_pick)
    // ------------------------------------------------------------------------------------------

    /** The order screen: the held item if it can be ordered (else none yet), the shop's price, 64 of it. */
    public void openNew(Player p, String key) {
        new OrderMenu(p, key).open(p);
    }

    private final class OrderMenu extends PriceMenu {
        private String key;
        private int amount = 64;

        OrderMenu(Player p, String key) {
            super(eco, 1);
            ItemStack hand = p.getInventory().getItemInMainHand();
            this.key = key != null ? key : !hand.isEmpty() && orderable(Items.key(hand)) ? Items.key(hand) : null;
            price = suggested();
        }

        private long suggested() {
            return Math.max(rules().orderMinPrice(), shopUnit(key));
        }

        void choose(Player p, String to) {
            key = to;
            if (!edited) price = suggested();
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
        }

        @Override
        protected long min(MenuView v) {
            return rules().orderMinPrice();
        }

        @Override
        protected long shopPrice(MenuView v) {
            return shopUnit(key);
        }

        @Override
        protected String menuId() {
            return "economy:market_order";
        }

        @Override
        protected Map<String, String> values(MenuView v) {
            Player p = v.player();
            UUID me = p.getUniqueId();
            long total = BuyOrders.escrow(amount, Math.min(price, PriceInput.MAX));
            long balance = core.economy().balance(me), shop = shopUnit(key), best = key == null ? 0 : store.best(key);
            Map<String, String> m = new HashMap<>();
            m.put("item", key == null ? Lang.raw("price.no-item") : Items.mmName(key));
            m.put("amount", String.format("%,d", amount));
            m.put("max", String.format("%,d", rules().orderMaxAmount()));
            m.put("total", EconomyModule.format(total));
            m.put("balance", EconomyModule.format(balance));
            m.put("after", EconomyModule.format(balance - total));
            m.put("shop", shop > 0 ? EconomyModule.format(shop) : Lang.raw("price.no-shop"));
            m.put("best", best > 0 ? EconomyModule.format(best) : Lang.raw("order.no-best"));
            m.put("warn", shop <= 0 ? "" : Lang.raw(price < shop ? "order.warn-below-shop" : "order.warn-ok"));
            m.put("fee_rate", String.valueOf(Math.round(rules().marketFee() * 100)));
            m.put("days", days());
            m.put("mine", String.valueOf(store.count(me)));
            m.put("limit", String.valueOf(rules().orderMax()));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            Player p = v.player();
            if (b.key() == 'S' && key != null) {
                ItemStack show = Items.create(key, 1).orElse(null);
                if (show == null) return base;
                show.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(Lang.items("order.picked-lore"));
                    meta.lore(lore);
                });
                return show;
            }
            if (b.key() == 'C') {
                String problem = problem(p, key, amount, price);
                if (problem != null) return PriceMenu.blocked(base, problem, "limit", rules().orderMax(), "max", rules().orderMaxAmount(),
                        "min", EconomyModule.format(rules().orderMinPrice()), "total", EconomyModule.format(BuyOrders.escrow(amount, Math.min(price, PriceInput.MAX))));
            }
            return base;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "order:qty:1", "order:qty:64" -> {
                    int step = action.endsWith(":64") ? 64 : 1;
                    long by = (long) step * (click.isShiftClick() ? 10 : 1) * (click.isRightClick() ? -1 : 1);
                    amount = (int) Math.clamp(amount + by, 1, rules().orderMaxAmount());
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, by > 0 ? 1.5f : 1.1f);
                    v.refresh();
                }
                case "order:qty:type" -> Bukkit.getScheduler().runTask(eco, () -> core.menus().prompt(p, Lang.msg("order.qty-ask"), text -> {
                    if (text != null) {
                        long typed = PriceInput.parse(text.replace("개", ""));
                        if (typed <= 0) p.sendMessage(Lang.msg("price.bad", "text", text));
                        else amount = (int) Math.clamp(typed, 1, rules().orderMaxAmount());
                    }
                    if (p.isOnline()) open(p);
                }));
                case "order:pick" -> Bukkit.getScheduler().runTask(eco, () -> new PickMenu(this, null).open(p));
                default -> super.onAction(v, action, click);
            }
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int clicked, ClickType click) {
            Player p = v.player();
            if (clicked < 0 || clicked > 35) return;
            ItemStack it = p.getInventory().getItem(clicked);
            if (it == null || it.isEmpty()) return;
            if (!orderable(Items.key(it))) {
                HudModule.flash(p, Lang.mm("order.not-orderable"));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            choose(p, Items.key(it));
            v.refresh();
        }

        @Override
        protected void confirm(MenuView v) {
            Player p = v.player();
            if (problem(p, key, amount, price) != null) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            String k = key;
            int n = amount;
            long at = price;
            Bukkit.getScheduler().runTask(eco, () -> {
                if (place(p, k, n, at)) BuyOrderModule.this.open(p, null, true);
            });
        }

        @Override
        protected void back(Player p) {
            BuyOrderModule.this.open(p, null, false);
        }
    }

    /** Every orderable item (searchable): a click chooses it for the order screen. */
    private final class PickMenu implements MenuHandler {
        private final OrderMenu back;
        private final String search;
        private List<String> shown = List.of();

        PickMenu(OrderMenu back, String search) {
            this.back = back;
            this.search = search;
        }

        void open(Player p) {
            core.menus().open(p, "economy:market_order_pick", this);
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("search", search == null ? Lang.raw("market.no-search") : MarketModule.titleText(search, 16),
                    "count", String.valueOf(shown.size()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<String> keys = orderables();
            if (search != null) {
                Set<String> found = search(search);
                keys.removeIf(k -> !found.contains(k));
            }
            shown = keys;
            List<ItemStack> out = new ArrayList<>();
            for (String k : keys) {
                ItemStack it = Items.create(k, 1).orElseGet(() -> new ItemStack(Material.PAPER));
                long shop = shopUnit(k), best = store.best(k);
                List<Component> extra = Lang.items("order.pick-lore", "shop", shop > 0 ? EconomyModule.format(shop) : Lang.raw("price.no-shop"),
                        "best", best > 0 ? EconomyModule.format(best) : Lang.raw("order.no-best"));
                it.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(extra);
                    meta.lore(lore);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            Player p = v.player();
            back.choose(p, shown.get(index));
            Bukkit.getScheduler().runTask(eco, () -> back.open(p));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "order:pick:back" -> Bukkit.getScheduler().runTask(eco, () -> back.open(p));
                case "order:pick:search" -> Bukkit.getScheduler().runTask(eco, () -> {
                    if (click.isRightClick()) {
                        new PickMenu(back, null).open(p);
                        return;
                    }
                    core.menus().prompt(p, Lang.msg("market.search-ask"), text -> {
                        if (p.isOnline()) new PickMenu(back, text == null || text.isBlank() ? search : text.trim()).open(p);
                    });
                });
                default -> { }
            }
        }
    }
}
