package dev.stsv.economy.shop;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.event.EventSpec;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 관리자 상점: buys what players gather at a price that falls as the server sells (판매), sells basic
 * tools, seeds, supplies, cosmetics and titles at fixed prices (구매), both by category; 수요 고시 (three
 * items x1.5 for a week), 품귀 / 시장 폭락 events, 시세 (prices, 24h change), the 시세판 hologram,
 * repairs and the 상인 행렬 limited goods (a 구매 category while the event runs).
 */
public final class ShopModule extends EcoModule implements DailyListener {

    private static final long DAY = 86_400_000L;

    private ShopCatalog catalog;
    private ShopStore store;
    /** fractions of a gold piece owed to a player per item, so selling one at a time loses nothing */
    private final Map<String, Double> remainder = new ConcurrentHashMap<>();
    private TextDisplay board;
    /** items whose price touched the floor today / yesterday (daily report) */
    private final java.util.Set<String> floorHits = ConcurrentHashMap.newKeySet();
    private int floorHitsYesterday;

    public ShopModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-shop";
    }

    @Override
    protected void start() {
        // prices recover through the day too: S shrinks by hourly-recovery an hour, applied every 10 minutes
        every(12_000L, () -> {
            double r = catalog.curve().hourlyRecovery();
            if (r > 0) store.recover(Math.pow(1 - r, 1 / 6.0));
        });
        store = new ShopStore(core.db());
        store.init();
        load();
        eco.calendar().onDaily(this);
        eco.events().onStart("shortage", this::startShortage);
        eco.events().onStart("crash", this::startCrash);
        eco.events().onStart("merchant", e -> {
            ShopCatalog.LimitedEntry rare = catalog.merchant().rareFor(String.valueOf(e.id()));
            e.params().put("detail", rare == null ? Lang.raw("shop.merchant-detail")
                    : Lang.raw("shop.merchant-detail-rare", "item", Items.mmName(rare.id())));
        });
        eco.events().onStart("urgent_order", this::startOrder);
        eco.events().onStart("premium_buyer", this::startPremium);
        every(1200L, this::minute);
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
        catalog = ShopCatalog.parse(eco.data("shop.yml"), problems);
        for (String id : catalog.sell().keySet()) if (Items.create(id, 1).isEmpty()) problems.add("sell: 없는 아이템 " + id);
        for (ShopCatalog.Good g : catalog.goods()) {
            if (g.kind().equals("item") && Items.create(g.id(), 1).isEmpty()) problems.add("buy: 없는 아이템 " + g.id());
        }
        for (ShopCatalog.LimitedEntry l : catalog.merchant().goods()) if (Items.create(l.id(), 1).isEmpty()) problems.add("merchant: 없는 아이템 " + l.id());
        for (ShopCatalog.LimitedEntry l : catalog.merchant().rare()) if (Items.create(l.id(), 1).isEmpty()) problems.add("merchant.rare: 없는 아이템 " + l.id());
        problems.forEach(p -> eco.getLogger().warning("[shop.yml] " + p));
    }

    public ShopCatalog catalog() {
        return catalog;
    }

    public ShopStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // Prices
    // ------------------------------------------------------------------------------------------

    public int floorHitsToday() {
        return floorHits.size();
    }

    public int floorHitsYesterday() {
        return floorHitsYesterday;
    }

    public List<String> noticed() {
        String s = eco.kv().get("shop.notice", "");
        if (s.isEmpty() || now() >= eco.kv().getLong("shop.notice-until", 0)) return List.of();
        return List.of(s.split(","));
    }

    /** Everything that changes an item's price right now (수요 고시, 품귀, 폭락, 고가 매입상 while its stock lasts). */
    public double multiplier(String item) {
        double m = baseMultiplier(item);
        ActiveEvent premium = premiumFor(item);
        if (premium != null) m *= premium.number("multiplier", 2);
        return m;
    }

    private double baseMultiplier(String item) {
        double m = 1;
        if (noticed().contains(item)) m *= catalog.demand().multiplier();
        for (ActiveEvent e : eco.events().active("shortage")) if (item.equals(e.param("item"))) m *= e.number("multiplier", 3);
        for (ActiveEvent e : eco.events().active("crash")) if (item.equals(e.param("item"))) m *= e.number("multiplier", 0.5);
        return m;
    }

    /** The running 고가 매입상 that still buys this item, or null. */
    private ActiveEvent premiumFor(String item) {
        for (ActiveEvent e : eco.events().active("premium_buyer")) {
            if (EventSpec.list(e.param("items")).contains(item) && e.number("left." + item, 0) > 0) return e;
        }
        return null;
    }

    public double price(String item) {
        ShopCatalog.SellEntry e = catalog.sell().get(item);
        if (e == null) return 0;
        return PriceModel.unit(e.base(), store.sold(item), depthQ(e), pricePlayers(), catalog.floor(), multiplier(item));
    }

    public boolean atFloor(String item) {
        ShopCatalog.SellEntry e = catalog.sell().get(item);
        return e != null && PriceModel.atFloor(e.base(), store.sold(item), depthQ(e), pricePlayers(), catalog.floor());
    }

    /** 24h change in percent, or null if there is no price from a day ago yet. */
    public Double change(String item) {
        Double ago = store.priceAgo(item, now(), DAY);
        if (ago == null || ago <= 0) return null;
        return (price(item) - ago) / ago * 100;
    }

    // ------------------------------------------------------------------------------------------
    // Selling to the shop
    // ------------------------------------------------------------------------------------------

    public record Sale(int count, long paid, double unitAfter) {}

    /**
     * Sells up to count of the stack's kind from the player's inventory. Returns what happened, or
     * a reason (message key) why nothing was sold.
     */
    public Object sell(Player p, ItemStack sample, int count) {
        String id = Items.key(sample);
        ShopCatalog.SellEntry e = catalog.sell().get(id);
        if (e == null || !Items.plain(sample, id)) return "shop.not-buying";
        java.util.function.Predicate<ItemStack> same = it -> Items.plain(it, id);
        int have = Items.count(p.getInventory(), same);
        int n = Math.min(count, have);
        if (e.cap() != null) n = Math.min(n, e.cap() - store.soldBy(p.getUniqueId(), id));
        if (n <= 0) return e.cap() != null && store.soldBy(p.getUniqueId(), id) >= e.cap() ? "shop.cap-reached" : "shop.not-buying";
        int players = pricePlayers();
        double total;
        ActiveEvent premium = premiumFor(id);
        if (premium == null) {
            total = PriceModel.total(e.base(), store.sold(id), depthQ(e), players, catalog.floor(), multiplier(id), n);
        } else {
            // the 고가 매입상 pays double for what is left of its stock; the rest sells at the usual price
            int left = (int) premium.number("left." + id, 0), high = Math.min(n, left);
            double normal = baseMultiplier(id);
            total = PriceModel.total(e.base(), store.sold(id), depthQ(e), players, catalog.floor(), normal * premium.number("multiplier", 2), high)
                    + PriceModel.total(e.base(), store.sold(id) + high, depthQ(e), players, catalog.floor(), normal, n - high);
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>(premium.params());
            params.put("left." + id, String.valueOf(left - high));
            eco.events().update(premium, params);
            if (left - high <= 0) Bukkit.broadcast(Lang.msg("shop.premium-sold-out", "item", itemName(id), "player", p.getName()));
        }
        // 직업 Lv30: the job's own machine goods sell for more (세공품, 손질품, 요리...)
        total *= eco.jobs().sellBonus(p.getUniqueId(), id);
        // rare and legend fish: by their size (0.7 - 1.5x)
        total *= Items.sizeFactor(p.getInventory(), same, n);
        String rk = p.getUniqueId() + "|" + id;
        total += remainder.getOrDefault(rk, 0.0);
        long paid = (long) Math.floor(total);
        remainder.put(rk, total - paid);
        int removed = Items.remove(p.getInventory(), same, n);
        if (removed != n) eco.getLogger().warning("판매 수량 불일치: " + p.getName() + " " + id + " " + removed + "/" + n);
        store.record(p.getUniqueId(), id, removed);
        if (paid > 0) core.economy().move(null, p.getUniqueId(), paid, EconomyStore.Kind.FAUCET, "shop_sell", id + " x" + removed);
        core.profiles().add(p.getUniqueId(), "shop_sold", removed);
        core.profiles().add(p.getUniqueId(), "shop_income", paid);
        eco.stocks().recordSale(p.getUniqueId(), id, paid);
        if (atFloor(id)) floorHits.add(id);
        return new Sale(removed, paid, price(id));
    }

    private void sellFeedback(Player p, ItemStack sample, Object result) {
        if (result instanceof Sale s) {
            HudModule.flash(p, Lang.mm("shop.sold", "item", Items.mmName(sample), "count", s.count(),
                    "amount", EconomyModule.format(s.paid()), "unit", String.format("%.1f", s.unitAfter())));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
        } else {
            HudModule.flash(p, Lang.mm((String) result));
        }
    }

    /** Sells every sellable item in the inventory, or only these items (상점의 "전부 팔기"). */
    public long sellAll(Player p, java.util.Collection<String> only) {
        long total = 0;
        int kinds = 0;
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.isEmpty()) continue;
            String id = Items.key(it);
            if (catalog.sell().containsKey(id) && (only == null || only.contains(id)) && Items.plain(it, id)) samples.putIfAbsent(id, it.clone());
        }
        for (ItemStack sample : samples.values()) {
            Object r = sell(p, sample, Integer.MAX_VALUE);
            if (r instanceof Sale s) {
                total += s.paid();
                kinds++;
            }
        }
        if (kinds > 0) {
            HudModule.flash(p, Lang.mm("shop.sold-all", "kinds", kinds, "amount", EconomyModule.format(total)));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        } else {
            HudModule.flash(p, Lang.mm("shop.nothing-to-sell"));
        }
        return total;
    }

    // ------------------------------------------------------------------------------------------
    // Buying from the shop
    // ------------------------------------------------------------------------------------------

    private boolean buy(Player p, String id, int amount, long price, String reason) {
        Optional<ItemStack> item = Items.create(id, amount);
        if (item.isEmpty()) return false;
        if (Items.room(p.getInventory(), item.get()) < amount) {
            HudModule.flash(p, Lang.mm("shop.no-room"));
            return false;
        }
        if (!core.economy().withdraw(p.getUniqueId(), price, reason)) {
            HudModule.flash(p, Lang.mm("shop.not-enough", "price", EconomyModule.format(price)));
            return false;
        }
        // tagged: buying at a fixed price and selling back at a 품귀 price (or delivering it) is no money machine
        item.get().editMeta(m -> m.getPersistentDataContainer().set(Items.SHOP_BOUGHT, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        for (ItemStack left : p.getInventory().addItem(item.get()).values()) p.getWorld().dropItem(p.getLocation(), left);
        HudModule.flash(p, Lang.mm("shop.bought", "item", Items.mmName(item.get()), "count", amount, "amount", EconomyModule.format(price)));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.1f);
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Repair
    // ------------------------------------------------------------------------------------------

    public long repairCost(ItemStack item) {
        if (item == null || item.isEmpty() || !(item.getItemMeta() instanceof Damageable d) || !d.hasDamage()) return -1;
        return Math.max(catalog.repairMin(), (long) Math.ceil(d.getDamage() * catalog.repairPerPoint() - 1e-9));
    }

    public void repair(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        long cost = repairCost(hand);
        if (cost < 0) {
            HudModule.flash(p, Lang.mm("shop.repair-nothing"));
            return;
        }
        if (!core.economy().withdraw(p.getUniqueId(), cost, "repair")) {
            HudModule.flash(p, Lang.mm("shop.not-enough", "price", EconomyModule.format(cost)));
            return;
        }
        hand.editMeta(Damageable.class, d -> d.setDamage(0));
        HudModule.flash(p, Lang.mm("shop.repaired", "item", Items.mmName(hand), "amount", EconomyModule.format(cost)));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------------------------------------
    // Daily: decay, 수요 고시
    // ------------------------------------------------------------------------------------------

    @Override
    public void newDay(int day, long dayStart) {
        floorHitsYesterday = floorHits.size();
        floorHits.clear();
        store.decay(catalog.dailyDecay());
        remainder.clear();
        // a new notice on its weekday (when the last one runs out), or right away if there is none (first start)
        if (catalog.demand().count() > 0 && noticed().isEmpty()) announceNotice(dayStart);
    }

    /** Items a running event is about (품귀, 폭락, 긴급 납품, 고가 매입상): never in the 수요 고시 or another event at the same time. */
    private java.util.Set<String> eventItems() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String type : List.of("shortage", "crash", "urgent_order")) {
            for (ActiveEvent e : eco.events().active(type)) if (e.param("item") != null) out.add(e.param("item"));
        }
        for (ActiveEvent e : eco.events().active("premium_buyer")) out.addAll(EventSpec.list(e.param("items")));
        return out;
    }

    /** Things gathered by a job (ores, crops, fish): what the 긴급 납품 and 고가 매입상 ask for. */
    private List<ShopCatalog.SellEntry> gathered() {
        List<ShopCatalog.SellEntry> pool = new ArrayList<>();
        for (ShopCatalog.SellEntry x : catalog.sell().values()) {
            if (x.job() != null && !noticed().contains(x.id()) && !eventItems().contains(x.id())) pool.add(x);
        }
        if (pool.isEmpty()) pool.addAll(catalog.sell().values());
        return pool;
    }

    // ------------------------------------------------------------------------------------------
    // 긴급 납품: a merchant orders a lot of one thing; the earlier you deliver, the more each one pays
    // ------------------------------------------------------------------------------------------

    private void startOrder(ActiveEvent e) {
        List<ShopCatalog.SellEntry> pool = gathered();
        ShopCatalog.SellEntry pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        int target = Order.target(pick.base(), e.number("target-value", 3000), (int) e.number("min", 16), (int) e.number("max", 512));
        e.params().put("item", pick.id());
        e.params().put("base", String.valueOf(pick.base()));
        e.params().put("target", String.valueOf(target));
        e.params().put("delivered", "0");
        e.params().put("detail", orderDetail(e.params()));
    }

    private Order order(java.util.Map<String, String> p) {
        return new Order(Double.parseDouble(p.getOrDefault("base", "1")), Integer.parseInt(p.getOrDefault("target", "1")),
                Double.parseDouble(p.getOrDefault("top", "2")), Double.parseDouble(p.getOrDefault("bottom", "1")));
    }

    private String orderDetail(java.util.Map<String, String> p) {
        Order o = order(p);
        int done = Integer.parseInt(p.getOrDefault("delivered", "0"));
        return Lang.raw("shop.order-detail", "item", itemName(p.get("item")), "done", done, "target", o.target(),
                "unit", String.format("%.1f", o.unit(done)));
    }

    /** /납품: hands in every matching item for the running 긴급 납품. */
    public void deliver(Player p) {
        List<ActiveEvent> running = eco.events().active("urgent_order");
        if (running.isEmpty()) {
            p.sendMessage(Lang.msg("shop.order-none"));
            return;
        }
        ActiveEvent e = running.getFirst();
        String id = e.param("item");
        Order o = order(e.params());
        int done = (int) e.number("delivered", 0);
        java.util.function.Predicate<ItemStack> same = it -> Items.plain(it, id);
        int n = Math.min(Items.count(p.getInventory(), same), o.target() - done);
        if (n <= 0) {
            p.sendMessage(Lang.msg("shop.order-missing", "item", itemName(id)));
            return;
        }
        int removed = Items.remove(p.getInventory(), same, n);
        long paid = (long) Math.floor(o.pay(done, removed));
        if (paid > 0) core.economy().move(null, p.getUniqueId(), paid, EconomyStore.Kind.FAUCET, "urgent_order", id + " x" + removed);
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>(e.params());
        String by = "by." + p.getUniqueId();
        params.put(by, String.valueOf(Integer.parseInt(params.getOrDefault(by, "0")) + removed));
        params.put("delivered", String.valueOf(done + removed));
        params.put("detail", orderDetail(params));
        e = eco.events().update(e, params);
        p.sendMessage(Lang.msg("shop.order-delivered", "count", removed, "item", itemName(id), "amount", EconomyModule.format(paid),
                "done", done + removed, "target", o.target()));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1f);
        if (done + removed >= o.target()) {
            List<String> top = params.entrySet().stream().filter(x -> x.getKey().startsWith("by."))
                    .sorted(java.util.Map.Entry.<String, String>comparingByValue(java.util.Comparator.comparingInt(Integer::parseInt)).reversed())
                    .limit(3).map(x -> dev.stsv.economy.util.Players.name(java.util.UUID.fromString(x.getKey().substring(3))) + " " + x.getValue()).toList();
            Bukkit.broadcast(Lang.msg("shop.order-complete", "item", itemName(id), "target", o.target(), "top", String.join(", ", top)));
            eco.events().end(e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 고가 매입상: a few things bought at double the market price, while its stock lasts
    // ------------------------------------------------------------------------------------------

    private void startPremium(ActiveEvent e) {
        List<ShopCatalog.SellEntry> pool = gathered();
        java.util.Collections.shuffle(pool, ThreadLocalRandom.current());
        int count = Math.min(pool.size(), ThreadLocalRandom.current().nextInt((int) e.number("min-items", 2), (int) e.number("max-items", 3) + 1));
        int stock = (int) e.number("stock", 64);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(pool.get(i).id());
            e.params().put("left." + pool.get(i).id(), String.valueOf(stock));
        }
        e.params().put("items", String.join(",", ids));
        e.params().put("detail", Lang.raw("shop.premium-detail", "items", String.join(", ", ids.stream().map(this::itemName).toList()),
                "mult", trim(e.number("multiplier", 2)), "stock", stock));
    }

    /** Picks this week's 수요 고시: not last week's items, not an item of a running 품귀/폭락. Lasts until the next notice weekday. */
    public List<String> announceNotice(long from) {
        var d = catalog.demand();
        List<String> last = List.of(eco.kv().get("shop.notice", "").split(","));
        List<String> pool = new ArrayList<>(catalog.sell().keySet());
        pool.removeAll(last);
        pool.removeAll(eventItems());
        java.util.Collections.shuffle(pool, ThreadLocalRandom.current());
        List<String> picked = List.copyOf(pool.subList(0, Math.min(d.count(), pool.size())));
        long until = eco.calendar().days().next(d.weekday(), from);
        eco.kv().set("shop.notice", String.join(",", picked));
        eco.kv().set("shop.notice-until", until);
        List<String> names = picked.stream().map(this::itemName).toList();
        Bukkit.broadcast(Lang.msg("shop.notice", "items", String.join(", ", names), "mult", trim(d.multiplier())));
        return picked;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** For a {item} placeholder (MiniMessage, each player's language). */
    public String itemName(String id) {
        return Items.mmName(id);
    }

    private void startShortage(ActiveEvent e) {
        String item = e.param("item");
        if (item == null || !catalog.sell().containsKey(ShopCatalog.norm(item))) {
            // a mid-priced item: something worth stocking up on, not the cheapest dirt (and not one the 수요 고시 already raises)
            List<ShopCatalog.SellEntry> pool = new ArrayList<>(catalog.sell().values());
            pool.removeIf(x -> noticed().contains(x.id()) || eventItems().contains(x.id()));
            if (pool.isEmpty()) pool.addAll(catalog.sell().values());
            pool.sort(Comparator.comparingDouble(ShopCatalog.SellEntry::base));
            List<ShopCatalog.SellEntry> mid = pool.subList(pool.size() / 4, Math.max(pool.size() / 4 + 1, pool.size() * 3 / 4));
            item = mid.get(ThreadLocalRandom.current().nextInt(mid.size())).id();
        }
        item = ShopCatalog.norm(item);
        e.params().put("item", item);
        e.params().put("detail", Lang.raw("shop.shortage-detail", "item", itemName(item), "mult", trim(e.number("multiplier", 3))));
    }

    private void startCrash(ActiveEvent e) {
        // the item the server sold most of lately (the one everybody farms)
        String item = e.param("item");
        if (item == null) {
            item = catalog.sell().keySet().stream()
                    .filter(i -> !noticed().contains(i) && !eventItems().contains(i))
                    .max(Comparator.comparingDouble(i -> store.sold(i) * catalog.sell().get(i).base()))
                    .orElse(catalog.sell().keySet().iterator().next());
        }
        item = ShopCatalog.norm(item);
        e.params().put("item", item);
        e.params().put("detail", Lang.raw("shop.crash-detail", "item", itemName(item), "mult", trim(e.number("multiplier", 0.5))));
    }

    // ------------------------------------------------------------------------------------------
    // Every minute: hourly snapshots, the 시세판
    // ------------------------------------------------------------------------------------------

    private void minute() {
        long hour = now() / 3_600_000L;
        if (eco.kv().getLong("shop.snap-hour", -1) != hour) {
            eco.kv().set("shop.snap-hour", hour);
            for (String id : catalog.sell().keySet()) store.snapshot(id, now(), price(id));
        }
        updateBoard();
    }

    public void updateBoard() {
        Location at = eco.location("price-board");
        if (at == null) {
            if (board != null) board.remove();
            board = null;
            return;
        }
        if (!at.isChunkLoaded()) return;
        at.setPitch(0);
        at.setYaw(0);
        if (board == null || !board.isValid()) {
            for (var ent : at.getWorld().getNearbyEntitiesByType(TextDisplay.class, at, 1.5)) {
                if (ent.getScoreboardTags().contains("stsv-price-board")) ent.remove();
            }
            board = at.getWorld().spawn(at, TextDisplay.class, t -> {
                t.addScoreboardTag("stsv-price-board");
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setLineWidth(400);
                t.setBackgroundColor(org.bukkit.Color.fromARGB(160, 10, 10, 14));
            });
        }
        // the whole list is too long for a sign: the marked ones (수요 고시, 사건) and the ten biggest 24h moves
        List<String> shown = new ArrayList<>();
        for (String id : catalog.sell().keySet()) if (!marks(id).isEmpty()) shown.add(id);
        List<String> movers = new ArrayList<>(catalog.sell().keySet());
        movers.removeAll(shown);
        movers.removeIf(id -> change(id) == null);
        movers.sort(java.util.Comparator.comparingDouble((String id) -> -Math.abs(change(id))));
        shown.addAll(movers.subList(0, Math.min(10, movers.size())));
        StringBuilder sb = new StringBuilder(Lang.raw("shop.board-title"));
        for (String id : shown) sb.append('\n').append(priceLine(id));
        if (shown.isEmpty()) sb.append('\n').append(Lang.raw("shop.board-quiet"));
        board.text(Text.mm(sb.toString()));
    }

    private String priceLine(String id) {
        Double ch = change(id);
        String change = ch == null ? Lang.raw("shop.change-none") : Lang.raw(ch >= 0 ? "shop.change-up" : "shop.change-down", "pct", String.format("%.0f", Math.abs(ch)));
        return Lang.raw("shop.price-line", "item", itemName(id), "price", String.format("%.1f", price(id)), "change", change, "marks", marks(id));
    }

    /** Players today for the price curve: at least curve.minPlayers (a quiet server does not crash prices at once). */
    private int pricePlayers() {
        return Math.max(catalog.curve().minPlayers(), eco.activity().store().players());
    }

    /** One player's daily amount as the curve counts it (q x depth). */
    private double depthQ(ShopCatalog.SellEntry e) {
        return e.q() * catalog.curve().depth();
    }

    private String marks(String id) {
        StringBuilder m = new StringBuilder();
        if (noticed().contains(id)) m.append(Lang.raw("shop.mark-notice"));
        for (ActiveEvent e : eco.events().active("shortage")) if (id.equals(e.param("item"))) m.append(Lang.raw("shop.mark-shortage"));
        for (ActiveEvent e : eco.events().active("crash")) if (id.equals(e.param("item"))) m.append(Lang.raw("shop.mark-crash"));
        ActiveEvent premium = premiumFor(id);
        if (premium != null) m.append(Lang.raw("shop.mark-premium", "left", (int) premium.number("left." + id, 0)));
        if (atFloor(id)) m.append(Lang.raw("shop.mark-floor"));
        return m.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Menus: 상점 → 구매 / 판매 → 카테고리 → 품목
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, "economy:shop", new FrontMenu());
    }

    public void openBuy(Player p) {
        core.menus().open(p, "economy:shop_buy", new BuyCategories());
    }

    public void openSell(Player p) {
        core.menus().open(p, "economy:shop_sell", new SellCategories());
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(eco, r);
    }

    private String balance(Player p) {
        return EconomyModule.format(core.economy().balance(p.getUniqueId()));
    }

    private ItemStack withLore(ItemStack base, List<Component> extra) {
        ItemStack it = base.clone();
        it.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.addAll(extra);
            meta.lore(lore);
        });
        return it;
    }

    /** A category tile: its icon item, its name, only the given lore (the icon's own tooltip hidden). */
    private ItemStack tile(String icon, String name, List<Component> lore) {
        ItemStack it = Items.create(icon, 1).orElseGet(() -> new ItemStack(org.bukkit.Material.CHEST));
        it.editMeta(meta -> {
            meta.itemName(Text.item(Lang.raw("shop.category-name", "name", name)));
            meta.customName(null);
            meta.lore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return it;
    }

    /** Categories centred in the one row of category cells (null = an empty cell). */
    private static <T> List<T> centred(List<T> entries, int cells) {
        List<T> out = new ArrayList<>();
        for (int i = 0; i < Math.max(0, (cells - entries.size()) / 2); i++) out.add(null);
        out.addAll(entries);
        return out;
    }

    /** Sells the clicked stack of the player's own inventory (both 판매 screens). */
    private void sellFromInventory(MenuView v, int slot, ClickType click) {
        Player p = v.player();
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || it.isEmpty()) return;
        int count = click.isShiftClick() ? Integer.MAX_VALUE : click.isRightClick() ? 1 : it.getAmount();
        // a copy: selling empties the clicked slot, and the message would then name the empty slot (공기)
        ItemStack sample = it.clone();
        sellFeedback(p, sample, sell(p, sample.clone(), count));
        v.refresh();
    }

    private final class FrontMenu implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", balance(v.player()),
                    "repair", Optional.of(repairCost(v.player().getInventory().getItemInMainHand())).filter(c -> c >= 0)
                            .map(EconomyModule::format).orElse(Lang.raw("shop.repair-none")));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "shop:buy" -> later(() -> openBuy(p));
                case "shop:sell" -> later(() -> openSell(p));
                case "shop:repair" -> {
                    repair(p);
                    v.refresh();
                }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ 구매

    private final class BuyCategories implements MenuHandler {
        /** a ShopCatalog.BuyCategory, or the running 상인 행렬 (an ActiveEvent) */
        private final List<Object> shown = new ArrayList<>();

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", balance(v.player()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<Object> entries = new ArrayList<>(catalog.buyCategories());
            List<ActiveEvent> merchant = eco.events().active("merchant");
            if (!merchant.isEmpty() && !catalog.merchant().goods().isEmpty()) entries.add(merchant.getFirst());
            shown.clear();
            shown.addAll(centred(entries, ShopCatalog.MAX_CATEGORIES));
            List<ItemStack> out = new ArrayList<>();
            for (Object o : shown) {
                if (o instanceof ShopCatalog.BuyCategory c) {
                    long min = c.goods().stream().mapToLong(ShopCatalog.Good::price).min().orElse(0);
                    long max = c.goods().stream().mapToLong(ShopCatalog.Good::price).max().orElse(0);
                    out.add(tile(c.icon(), c.name(), Lang.items("shop.category-buy", "count", c.goods().size(),
                            "min", EconomyModule.format(min), "max", EconomyModule.format(max))));
                } else if (o instanceof ActiveEvent e) {
                    ShopCatalog.Merchant m = catalog.merchant();
                    List<ShopCatalog.LimitedEntry> shown = m.shown(String.valueOf(e.id()));
                    int left = shown.stream().mapToInt(l -> Math.max(0, left(e, l))).sum();
                    out.add(tile(m.icon(), m.name(), Lang.items("shop.category-merchant", "count", shown.size(), "left", left)));
                } else {
                    out.add(null);
                }
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            Object o = shown.get(index);
            Player p = v.player();
            if (o instanceof ShopCatalog.BuyCategory c) later(() -> core.menus().open(p, "economy:shop_buy_items", new BuyItems(c)));
            else if (o instanceof ActiveEvent e) later(() -> core.menus().open(p, "economy:shop_buy_items", new MerchantItems(e)));
        }
    }

    private final class BuyItems implements MenuHandler {
        private final ShopCatalog.BuyCategory category;
        /** a cosmetic or title waits for a second click (they are dear) */
        private String armed;
        private long armedAt;

        BuyItems(ShopCatalog.BuyCategory category) {
            this.category = category;
        }

        private boolean vanity() {
            return category.goods().stream().anyMatch(ShopCatalog.Good::vanity);
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", balance(v.player()), "category", category.name(),
                    "hint", Lang.raw(vanity() ? "shop.hint-vanity" : "shop.hint-buy"));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (ShopCatalog.Good g : category.goods()) {
                if (g.vanity()) {
                    out.add(eco.vanity().display(v.player(), g, (g.kind() + g.id()).equals(armed) && now() - armedAt < 5000));
                } else {
                    out.add(Items.create(g.id(), g.amount()).map(it -> withLore(it,
                            Lang.items("shop.buy-lore", "price", EconomyModule.format(g.price()), "count", g.amount()))).orElse(null));
                }
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= category.goods().size()) return;
            ShopCatalog.Good g = category.goods().get(index);
            Player p = v.player();
            if (g.vanity()) {
                String key = g.kind() + g.id();
                if (!key.equals(armed) || now() - armedAt > 5000) {
                    armed = key;
                    armedAt = now();
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                } else {
                    armed = null;
                    String refused = eco.vanity().buy(p, g);
                    if (refused != null) HudModule.flash(p, Lang.mm(refused, "price", EconomyModule.format(g.price())));
                }
            } else {
                int times = click.isShiftClick() ? 8 : 1;
                for (int i = 0; i < times; i++) if (!buy(p, g.id(), g.amount(), g.price(), "shop_buy")) break;
            }
            v.refresh();
        }
    }

    private int left(ActiveEvent event, ShopCatalog.LimitedEntry l) {
        return l.stock() - (int) eco.kv().getLong("shop.merchant." + event.id() + "." + l.index(), 0);
    }

    private final class MerchantItems implements MenuHandler {
        private final ActiveEvent event;

        MerchantItems(ActiveEvent event) {
            this.event = event;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", balance(v.player()), "category", catalog.merchant().name(), "hint", Lang.raw("shop.hint-merchant"));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (ShopCatalog.LimitedEntry l : catalog.merchant().shown(String.valueOf(event.id()))) {
                out.add(Items.create(l.id(), l.amount()).map(it -> withLore(it,
                        Lang.items("shop.merchant-lore", "price", EconomyModule.format(l.price()), "left", Math.max(0, left(event, l)), "stock", l.stock())))
                        .orElse(null));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            List<ShopCatalog.LimitedEntry> shown = catalog.merchant().shown(String.valueOf(event.id()));
            if (index >= shown.size()) return;
            if (!event.runningAt(now())) {
                HudModule.flash(v.player(), Lang.mm("shop.merchant-gone"));
                return;
            }
            ShopCatalog.LimitedEntry l = shown.get(index);
            if (left(event, l) <= 0) {
                HudModule.flash(v.player(), Lang.mm("shop.sold-out"));
                return;
            }
            if (buy(v.player(), l.id(), l.amount(), l.price(), "merchant")) {
                String key = "shop.merchant." + event.id() + "." + l.index();
                eco.kv().set(key, eco.kv().getLong(key, 0) + 1);
                if (left(event, l) == 0) Bukkit.broadcast(Lang.msg("shop.merchant-sold-out", "item", itemName(l.id()), "player", v.player().getName()));
            }
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------ 판매

    /** What the player could sell of these items right now: kinds, pieces, about how much. */
    private record Holding(int kinds, int count, long value) {}

    private Holding holding(Player p, List<String> ids) {
        int kinds = 0, count = 0;
        double value = 0;
        int players = pricePlayers();
        for (String id : ids) {
            ShopCatalog.SellEntry e = catalog.sell().get(id);
            int n = Items.count(p.getInventory(), it -> Items.plain(it, id));
            if (e == null || n <= 0) continue;
            if (e.cap() != null) n = Math.min(n, Math.max(0, e.cap() - store.soldBy(p.getUniqueId(), id)));
            if (n <= 0) continue;
            kinds++;
            count += n;
            value += PriceModel.total(e.base(), store.sold(id), depthQ(e), players, catalog.floor(), multiplier(id), n)
                    * eco.jobs().sellBonus(p.getUniqueId(), id);
        }
        return new Holding(kinds, count, (long) Math.floor(value));
    }

    private List<Component> holdingLore(Player p, List<String> ids) {
        Holding h = holding(p, ids);
        return h.kinds() == 0 ? Lang.items("shop.holding-none")
                : Lang.items("shop.holding", "kinds", h.kinds(), "count", h.count(), "value", EconomyModule.format(h.value()));
    }

    private final class SellCategories implements MenuHandler {
        private final List<ShopCatalog.Category> shown = new ArrayList<>();

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", balance(v.player()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            shown.clear();
            shown.addAll(centred(catalog.sellCategories(), ShopCatalog.MAX_CATEGORIES));
            List<ItemStack> out = new ArrayList<>();
            for (ShopCatalog.Category c : shown) {
                if (c == null) {
                    out.add(null);
                    continue;
                }
                List<Component> lore = new ArrayList<>(Lang.items("shop.category-sell", "count", c.items().size()));
                lore.addAll(holdingLore(v.player(), c.items()));
                lore.addAll(Lang.items("shop.category-open"));
                out.add(tile(c.icon(), c.name(), lore));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size() || shown.get(index) == null) return;
            ShopCatalog.Category c = shown.get(index);
            Player p = v.player();
            later(() -> core.menus().open(p, "economy:shop_sell_items", new SellItems(c)));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("shop:sell-all")) sellAll(v.player(), null);
            v.refresh();
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int slot, ClickType click) {
            sellFromInventory(v, slot, click);
        }
    }

    private final class SellItems implements MenuHandler {
        private final ShopCatalog.Category category;

        SellItems(ShopCatalog.Category category) {
            this.category = category;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", balance(v.player()), "category", category.name());
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            Player p = v.player();
            for (String id : category.items()) {
                ShopCatalog.SellEntry e = catalog.sell().get(id);
                Double ch = change(id);
                String change = ch == null ? Lang.raw("shop.change-none") : Lang.raw(ch >= 0 ? "shop.change-up" : "shop.change-down", "pct", String.format("%.0f", Math.abs(ch)));
                int have = Items.count(p.getInventory(), it -> Items.plain(it, id));
                List<Component> lore = new ArrayList<>(Lang.items("shop.price-lore",
                        "price", String.format("%.1f", price(id)), "base", trim(e.base()), "change", change, "marks", marks(id),
                        "sold", String.format("%.0f", store.sold(id)), "quota", String.format("%.0f", catalog.curve().quota(e.q(), eco.activity().store().players())),
                        "mine", store.soldBy(p.getUniqueId(), id), "cap", e.cap() == null ? "-" : e.cap()));
                lore.addAll(have > 0 ? Lang.items("shop.sell-have", "have", have) : Lang.items("shop.sell-have-none"));
                out.add(Items.create(id, 1).map(it -> withLore(it, lore)).orElse(null));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= category.items().size()) return;
            String id = category.items().get(index);
            Player p = v.player();
            Optional<ItemStack> sample = Items.create(id, 1);
            if (sample.isEmpty()) return;
            if (Items.count(p.getInventory(), it -> Items.plain(it, id)) <= 0) {
                HudModule.flash(p, Lang.mm("shop.have-none", "item", itemName(id)));
                return;
            }
            int count = click.isShiftClick() ? Integer.MAX_VALUE : click.isRightClick() ? 1 : sample.get().getMaxStackSize();
            sellFeedback(p, sample.get(), sell(p, sample.get(), count));
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("shop:sell-category")) sellAll(v.player(), category.items());
            v.refresh();
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int slot, ClickType click) {
            sellFromInventory(v, slot, click);
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Price list for chat (/시세 [품목]). */
    public List<Component> priceLines(String filter) {
        List<Component> out = new ArrayList<>();
        out.add(Lang.msg("shop.prices-header", "players", eco.activity().store().players()));
        for (String id : catalog.sell().keySet()) {
            if (filter != null && !id.contains(filter.toLowerCase(java.util.Locale.ROOT)) && !Items.create(id, 1).map(Items::plainName).orElse(id).contains(filter)) continue;
            out.add(Text.mm(" " + priceLine(id)));
        }
        return out;
    }

    public void reset() {
        store.reset();
        remainder.clear();
        eco.kv().removePrefix("shop.");
    }
}
