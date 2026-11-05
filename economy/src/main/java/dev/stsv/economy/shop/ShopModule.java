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
 * 관리자 상점: buys what players gather at a price that falls as the server sells (매입), sells basic
 * tools, seeds and fuel at fixed prices, 수요 고시 (three items x1.5 for a week), 품귀 / 시장 폭락 events,
 * 시세 (prices, 24h change), the 시세판 hologram, repairs, the 상인 행렬 limited goods and the
 * 치장·칭호 상점.
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
        store = new ShopStore(core.db());
        store.init();
        load();
        eco.calendar().onDaily(this);
        eco.events().onStart("shortage", this::startShortage);
        eco.events().onStart("crash", this::startCrash);
        eco.events().onStart("merchant", e -> e.params().put("detail", Lang.raw("shop.merchant-detail")));
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
        for (ShopCatalog.BuyEntry b : catalog.buy()) if (Items.create(b.id(), 1).isEmpty()) problems.add("buy: 없는 아이템 " + b.id());
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

    /** Everything that changes an item's price right now (수요 고시, 품귀, 폭락). */
    public double multiplier(String item) {
        double m = 1;
        if (noticed().contains(item)) m *= catalog.demand().multiplier();
        for (ActiveEvent e : eco.events().active("shortage")) if (item.equals(e.param("item"))) m *= e.number("multiplier", 3);
        for (ActiveEvent e : eco.events().active("crash")) if (item.equals(e.param("item"))) m *= e.number("multiplier", 0.5);
        return m;
    }

    public double price(String item) {
        ShopCatalog.SellEntry e = catalog.sell().get(item);
        if (e == null) return 0;
        return PriceModel.unit(e.base(), store.sold(item), e.q(), eco.activity().store().players(), catalog.floor(), multiplier(item));
    }

    public boolean atFloor(String item) {
        ShopCatalog.SellEntry e = catalog.sell().get(item);
        return e != null && PriceModel.atFloor(e.base(), store.sold(item), e.q(), eco.activity().store().players(), catalog.floor());
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
        double mult = multiplier(id);
        double total = PriceModel.total(e.base(), store.sold(id), e.q(), eco.activity().store().players(), catalog.floor(), mult, n);
        String rk = p.getUniqueId() + "|" + id;
        total += remainder.getOrDefault(rk, 0.0);
        long paid = (long) Math.floor(total);
        remainder.put(rk, total - paid);
        int removed = Items.remove(p.getInventory(), same, n);
        if (removed != n) eco.getLogger().warning("판매 수량 불일치: " + p.getName() + " " + id + " " + removed + "/" + n);
        store.record(p.getUniqueId(), id, removed);
        if (paid > 0) core.economy().move(null, p.getUniqueId(), paid, EconomyStore.Kind.FAUCET, "shop_sell", id + " x" + removed);
        if (atFloor(id)) floorHits.add(id);
        return new Sale(removed, paid, price(id));
    }

    private void sellFeedback(Player p, ItemStack sample, Object result) {
        if (result instanceof Sale s) {
            HudModule.flash(p, Lang.mm("shop.sold", "item", Items.plainName(sample), "count", s.count(),
                    "amount", EconomyModule.format(s.paid()), "unit", String.format("%.1f", s.unitAfter())));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
        } else {
            HudModule.flash(p, Lang.mm((String) result));
        }
    }

    /** Sells every sellable item in the inventory (상점의 "전부 팔기"). */
    public long sellAll(Player p) {
        long total = 0;
        int kinds = 0;
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.isEmpty()) continue;
            String id = Items.key(it);
            if (catalog.sell().containsKey(id) && Items.plain(it, id)) samples.putIfAbsent(id, it.clone());
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
        HudModule.flash(p, Lang.mm("shop.bought", "item", Items.plainName(item.get()), "count", amount, "amount", EconomyModule.format(price)));
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
        HudModule.flash(p, Lang.mm("shop.repaired", "item", Items.plainName(hand), "amount", EconomyModule.format(cost)));
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

    /** Items a running 품귀 or 시장 폭락 is about: never in the 수요 고시 at the same time. */
    private java.util.Set<String> eventItems() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String type : List.of("shortage", "crash")) for (ActiveEvent e : eco.events().active(type)) if (e.param("item") != null) out.add(e.param("item"));
        return out;
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

    public String itemName(String id) {
        return Items.create(id, 1).map(Items::plainName).orElse(id);
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
        if (board == null || !board.isValid()) {
            for (var ent : at.getWorld().getNearbyEntitiesByType(TextDisplay.class, at, 1.5)) {
                if (ent.getScoreboardTags().contains("stsv-price-board")) ent.remove();
            }
            board = at.getWorld().spawn(at, TextDisplay.class, t -> {
                t.addScoreboardTag("stsv-price-board");
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.VERTICAL);
                t.setLineWidth(400);
                t.setBackgroundColor(org.bukkit.Color.fromARGB(160, 10, 10, 14));
            });
        }
        StringBuilder sb = new StringBuilder(Lang.raw("shop.board-title"));
        for (String id : catalog.sell().keySet()) sb.append('\n').append(priceLine(id));
        board.text(Text.mm(sb.toString()));
    }

    private String priceLine(String id) {
        Double ch = change(id);
        String change = ch == null ? Lang.raw("shop.change-none") : Lang.raw(ch >= 0 ? "shop.change-up" : "shop.change-down", "pct", String.format("%.0f", Math.abs(ch)));
        return Lang.raw("shop.price-line", "item", itemName(id), "price", String.format("%.1f", price(id)), "change", change, "marks", marks(id));
    }

    private String marks(String id) {
        StringBuilder m = new StringBuilder();
        if (noticed().contains(id)) m.append(Lang.raw("shop.mark-notice"));
        for (ActiveEvent e : eco.events().active("shortage")) if (id.equals(e.param("item"))) m.append(Lang.raw("shop.mark-shortage"));
        for (ActiveEvent e : eco.events().active("crash")) if (id.equals(e.param("item"))) m.append(Lang.raw("shop.mark-crash"));
        if (atFloor(id)) m.append(Lang.raw("shop.mark-floor"));
        return m.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, "economy:shop", new ShopMenu());
    }

    public void openPrices(Player p) {
        core.menus().open(p, "economy:prices", new PricesMenu());
    }

    public void openMerchant(Player p) {
        List<ActiveEvent> ev = eco.events().active("merchant");
        if (ev.isEmpty()) {
            HudModule.flash(p, Lang.mm("shop.merchant-gone"));
            return;
        }
        core.menus().open(p, "economy:merchant", new MerchantMenu(ev.getFirst()));
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

    private final class ShopMenu implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", EconomyModule.format(core.economy().balance(v.player().getUniqueId())),
                    "repair", Optional.of(repairCost(v.player().getInventory().getItemInMainHand())).filter(c -> c >= 0)
                            .map(EconomyModule::format).orElse(Lang.raw("shop.repair-none")));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (ShopCatalog.BuyEntry b : catalog.buy()) {
                Items.create(b.id(), b.amount()).ifPresent(it -> out.add(withLore(it,
                        Lang.items("shop.buy-lore", "price", EconomyModule.format(b.price()), "count", b.amount()))));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= catalog.buy().size()) return;
            ShopCatalog.BuyEntry b = catalog.buy().get(index);
            int times = click.isShiftClick() ? 8 : 1;
            for (int i = 0; i < times; i++) if (!buy(v.player(), b.id(), b.amount(), b.price(), "shop_buy")) break;
            v.refresh();
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int slot, ClickType click) {
            Player p = v.player();
            ItemStack it = p.getInventory().getItem(slot);
            if (it == null || it.isEmpty()) return;
            int count = click.isShiftClick() ? Integer.MAX_VALUE : click.isRightClick() ? 1 : it.getAmount();
            sellFeedback(p, it, sell(p, it.clone(), count));
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "shop:sell-all" -> sellAll(p);
                case "shop:prices" -> Bukkit.getScheduler().runTask(eco, () -> openPrices(p));
                case "shop:repair" -> repair(p);
                case "shop:merchant" -> Bukkit.getScheduler().runTask(eco, () -> openMerchant(p));
                case "shop:vanity" -> Bukkit.getScheduler().runTask(eco, () -> eco.vanity().openShop(p));
                default -> { }
            }
            v.refresh();
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button button, ItemStack base) {
            if (button.action().equals("shop:merchant") && !eco.events().isActive("merchant")) return null;
            return base;
        }
    }

    private final class PricesMenu implements MenuHandler {
        private final List<String> ids = new ArrayList<>(catalog.sell().keySet());

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (String id : ids) {
                ShopCatalog.SellEntry e = catalog.sell().get(id);
                Double ch = change(id);
                String change = ch == null ? Lang.raw("shop.change-none") : Lang.raw(ch >= 0 ? "shop.change-up" : "shop.change-down", "pct", String.format("%.0f", Math.abs(ch)));
                Items.create(id, 1).ifPresent(it -> out.add(withLore(it, Lang.items("shop.price-lore",
                        "price", String.format("%.1f", price(id)), "base", trim(e.base()), "change", change, "marks", marks(id),
                        "sold", String.format("%.0f", store.sold(id)), "quota", String.format("%.0f", e.q() * eco.activity().store().players()),
                        "mine", store.soldBy(v.player().getUniqueId(), id), "cap", e.cap() == null ? "-" : e.cap()))));
            }
            return out;
        }
    }

    private final class MerchantMenu implements MenuHandler {
        private final ActiveEvent event;

        MerchantMenu(ActiveEvent event) {
            this.event = event;
        }

        private int left(ShopCatalog.LimitedEntry l) {
            return l.stock() - (int) eco.kv().getLong("shop.merchant." + event.id() + "." + l.index(), 0);
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (ShopCatalog.LimitedEntry l : catalog.merchant()) {
                Items.create(l.id(), l.amount()).ifPresent(it -> out.add(withLore(it,
                        Lang.items("shop.merchant-lore", "price", EconomyModule.format(l.price()), "left", Math.max(0, left(l)), "stock", l.stock()))));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= catalog.merchant().size() || !event.runningAt(now())) return;
            ShopCatalog.LimitedEntry l = catalog.merchant().get(index);
            if (left(l) <= 0) {
                HudModule.flash(v.player(), Lang.mm("shop.sold-out"));
                return;
            }
            if (buy(v.player(), l.id(), l.amount(), l.price(), "merchant")) {
                String key = "shop.merchant." + event.id() + "." + l.index();
                eco.kv().set(key, eco.kv().getLong(key, 0) + 1);
                if (left(l) == 0) Bukkit.broadcast(Lang.msg("shop.merchant-sold-out", "item", itemName(l.id()), "player", v.player().getName()));
            }
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Price list for chat (/시세 [품목]). */
    public List<Component> priceLines(String filter) {
        List<Component> out = new ArrayList<>();
        out.add(Lang.msg("shop.prices-header", "players", eco.activity().store().players()));
        for (String id : catalog.sell().keySet()) {
            if (filter != null && !id.contains(filter.toLowerCase(java.util.Locale.ROOT)) && !itemName(id).contains(filter)) continue;
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
