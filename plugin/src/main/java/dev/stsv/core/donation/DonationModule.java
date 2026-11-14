package dev.stsv.core.donation;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.donation.DonationShop.Category;
import dev.stsv.core.donation.DonationShop.Product;
import dev.stsv.core.donation.DonationStore.Entry;
import dev.stsv.core.donation.DonationStore.Kind;
import dev.stsv.core.donation.DonationStore.Outcome;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The donation shop (/후원).
 *
 * <p><b>How currency comes in.</b> By hand for now: a player donates (a bank transfer, outside the game), an
 * administrator checks it and credits them with /관리자 후원 지급. That command is the only way in, so whatever is
 * put in front of it later (a payment page calling it) changes nothing here.
 *
 * <p><b>Buying.</b> /후원 opens the shop: the balance, the categories, the products of one, and a screen that asks
 * once more before anything is spent. A purchase is written to the books first ({@link DonationStore}: the price
 * off, a line marked "not delivered"), then handed over and the line marked; if the player has gone meanwhile, or
 * has no room for an item, it waits and is handed over when they next join (or run /후원 받기). One purchase at a
 * time a player.
 *
 * <p><b>What is sold</b> is the file's business (content/stsv/donation_shop.yml). The rule the file's comments
 * state, and nothing here enforces: looks only, nothing that makes a player stronger or richer in the game.
 */
public final class DonationModule implements StsvModule, Listener, TabExecutor {

    public static final String PERMISSION = "stsv.donation";
    private static final String ADMIN_OWNER = "STSV 후원";
    /** Lines of history in chat (admins), in the menu's history tab, and the category tabs the menu has. */
    private static final int HISTORY = 10, MENU_HISTORY = 72, TABS = 7;
    /** The history's tab (no category may be called this: ids have no '#'). */
    private static final String HISTORY_TAB = "#history";

    private STSVPlugin plugin;
    private DonationStore store;
    private DonationShop shop = DonationShop.EMPTY;
    /** players with a purchase (or a delivery) under way: one at a time */
    private final Set<UUID> busy = new HashSet<>();

    @Override
    public String id() {
        return "donation";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new DonationStore(plugin.db());
        store.init();
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.admin().add(ADMIN_OWNER, "후원", "지급|회수 <플레이어> <수량> [메모] | 조회 <플레이어> | 내역 <플레이어> | 환불 <내역 번호> [메모]",
                "후원 재화를 주기 (입금 확인 뒤) / 도로 가져가기 / 보유량 보기 / 그 사람의 내역 / 구매 한 건을 물러 값을 돌려주기", this::admin,
                (s, a) -> a.length == 1 ? List.of("지급", "회수", "조회", "내역", "환불")
                        : a.length == 2 && !a[0].equals("환불") ? Names.complete(s, a[1]) : a.length == 3 && a[0].equals("지급") ? List.of("100", "300", "500") : List.of());
        plugin.admin().report(ADMIN_OWNER, (s, a) -> store.totals().thenAccept(t -> Bukkit.getScheduler().runTask(plugin, () ->
                s.sendMessage(Lang.mm("donation.report", "granted", shop.amount(t[0]), "spent", shop.amount(t[1]), "held", shop.amount(t[2]), "players", t[3])))));
    }

    @Override
    public void disable() {
        if (plugin != null) {
            plugin.admin().removeAll(ADMIN_OWNER);
            HandlerList.unregisterAll(this);
        }
        busy.clear();
    }

    @Override
    public void reload() {
        load();
    }

    /** Reads the last pack's donation_shop.yml that has one (one shop: a later pack replaces it whole). */
    private void load() {
        DonationShop read = DonationShop.EMPTY;
        for (ContentPack pack : plugin.content().packs()) {
            Path f = pack.dir().resolve("donation_shop.yml");
            if (!Files.isRegularFile(f)) continue;
            read = DonationShop.parse(YamlConfiguration.loadConfiguration(f.toFile()), id -> plugin.content().item(id).isPresent(),
                    problem -> plugin.getLogger().warning("[후원] " + pack.id() + "/donation_shop.yml: " + problem));
        }
        shop = read;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("donation.enabled");
    }

    // ------------------------------------------------------------------------------------------
    // /후원
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (!enabled()) {
            p.sendMessage(Lang.msg("donation.disabled"));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0];
        switch (sub) {
            case "내역", "history" -> openShop(p, HISTORY_TAB);
            case "받기", "claim" -> deliverPending(p, true);
            default -> openShop(p, null);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        return a.length == 1 ? List.of("내역", "받기").stream().filter(s -> s.startsWith(a[0])).toList() : List.of();
    }

    /** What a shop screen shows of a player's books (read once when it opens). */
    private record Books(DonationStore.Account account, Set<String> owned, int pending, List<Entry> history) {}

    /** Opens the shop (the pack's own menu, menus/donation.yml) on a tab: a category's id, {@link #HISTORY_TAB}, or null for the first category. */
    private void openShop(Player p, String tab) {
        UUID id = p.getUniqueId();
        store.account(id).thenCompose(account -> store.owned(id).thenCompose(owned -> store.undelivered(id).thenCompose(pending ->
                store.history(id, MENU_HISTORY).thenApply(history -> new Books(account, owned, pending.size(), history)))))
                .thenAccept(books -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    String open = HISTORY_TAB.equals(tab) || shop.categories().containsKey(tab) ? tab
                            : shop.categories().isEmpty() ? HISTORY_TAB : shop.categories().keySet().iterator().next();
                    plugin.menus().open(p, "stsv:donation", new ShopScreen(open, books));
                })).exceptionally(err -> failed(p, err));
    }

    /** Whether a player has what a product gives for good (bought once, or every thing it unlocks is theirs already). */
    private boolean has(Player p, Product product, Set<String> owned) {
        if (product.once() && owned.contains(product.id())) return true;
        if (product.unlocks().isEmpty() || plugin.unlocks() == null) return false;
        for (String u : product.unlocks()) if (!plugin.unlocks().has(p.getUniqueId(), u)) return false;
        return true;
    }

    /** A product as an item to look at: its own icon, the item it gives, or the thing it unlocks. */
    private ItemStack look(Product product) {
        ItemStack it = null;
        String icon = product.icon() != null ? product.icon() : product.item();
        if (icon != null) {
            Optional<CustomItem> custom = plugin.content().item(icon);
            if (custom.isPresent()) {
                it = custom.get().create(1);
            } else if (icon.contains(":")) {
                NamespacedKey model = NamespacedKey.fromString(icon);
                it = new ItemStack(Material.PAPER);
                if (model != null) it.editMeta(m -> m.setItemModel(model));
            } else {
                Material material = Material.matchMaterial(icon);
                if (material != null && material.isItem()) it = new ItemStack(material);
            }
        }
        if (it == null && !product.unlocks().isEmpty() && plugin.unlocks() != null) it = plugin.unlocks().icon(product.unlocks().getFirst());
        return it == null ? new ItemStack(Material.PAPER) : it;
    }

    /** A product's tile: its look, its name, what it is (with the player's own preview where there is one), and the lines under it. */
    private ItemStack tile(Player p, Product product, List<Component> under) {
        ItemStack it = look(product);
        it.editMeta(m -> {
            m.itemName(Lang.item("donation.tile-name", "name", product.name()));
            List<Component> lore = new ArrayList<>();
            if (plugin.unlocks() != null) {
                for (String u : product.unlocks()) {
                    for (Component line : plugin.unlocks().preview(p, u)) lore.add(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
            }
            for (String line : product.description().split("\n")) if (!line.isBlank()) lore.add(dev.stsv.core.util.Text.item(line));
            lore.addAll(under);
            m.lore(lore);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        });
        return it;
    }

    /** The shop: a tab a category (and the history), the products of the open one. */
    private final class ShopScreen implements MenuHandler {
        private final String tab;
        private final Books books;
        private List<Product> shown = List.of();

        ShopScreen(String tab, Books books) {
            this.tab = tab;
            this.books = books;
        }

        private List<Category> categories() {
            return List.copyOf(shop.categories().values());
        }

        @Override
        public Map<String, String> placeholders(MenuView view) {
            Map<String, String> out = new HashMap<>();
            Category open = shop.categories().get(tab);
            out.put("tab", open == null ? Lang.raw("donation.tab-history") : open.name());
            out.put("balance", shop.amount(books.account().balance()));
            out.put("currency", shop.currency());
            out.put("pending", String.valueOf(books.pending()));
            List<Category> cs = categories();
            for (int k = 1; k <= TABS; k++) {
                out.put("cat_" + k, k <= cs.size() ? cs.get(k - 1).name() : "");
                out.put("count_" + k, k <= cs.size() ? String.valueOf(cs.get(k - 1).products().size()) : "0");
            }
            return out;
        }

        @Override
        public ItemStack button(MenuView view, MenuSpec.Button button, ItemStack base) {
            String action = button.action();
            if (base == null) return null;
            if (action.equals("donation:tab:history")) {
                if (HISTORY_TAB.equals(tab)) base.editMeta(m -> m.setEnchantmentGlintOverride(true));
                return base;
            }
            if (action.startsWith("donation:tab:")) {
                int k = Integer.parseInt(action.substring("donation:tab:".length()));
                List<Category> cs = categories();
                if (k > cs.size()) return null;                 // no such category: an empty tab
                Category c = cs.get(k - 1);
                ItemStack it = c.icon() == null ? base : icon(c.icon(), base);
                it.editMeta(m -> {
                    m.itemName(base.getItemMeta().itemName());
                    m.lore(base.getItemMeta().lore());
                    if (c.id().equals(tab)) m.setEnchantmentGlintOverride(true);
                });
                return it;
            }
            if (button.key() == 'I') {
                List<Component> lines = new ArrayList<>();
                for (String line : shop.notice()) lines.add(dev.stsv.core.util.Text.item(line));
                base.editMeta(m -> m.lore(lines));
            }
            return base;
        }

        /** A category's icon: an item model of the pack ("stsv:gui/don_car") or a material ("NAME_TAG"). */
        private ItemStack icon(String icon, ItemStack base) {
            if (icon.contains(":")) {
                NamespacedKey model = NamespacedKey.fromString(icon);
                ItemStack it = new ItemStack(Material.PAPER);
                if (model != null) it.editMeta(m -> m.setItemModel(model));
                return it;
            }
            Material material = Material.matchMaterial(icon);
            return material != null && material.isItem() ? new ItemStack(material) : base;
        }

        @Override
        public List<ItemStack> list(MenuView view) {
            Player p = view.player();
            List<ItemStack> out = new ArrayList<>();
            if (HISTORY_TAB.equals(tab)) {
                shown = List.of();
                for (Entry e : books.history()) {
                    boolean waiting = e.kind() == Kind.BUY && !e.delivered();
                    ItemStack it = new ItemStack(e.kind() == Kind.BUY ? Material.PAPER : e.kind() == Kind.GRANT ? Material.NETHER_STAR : Material.MAP);
                    it.editMeta(m -> {
                        m.itemName(Lang.item("donation.history-tile-name", "number", e.id(), "what", what(e)));
                        m.lore(Lang.items(waiting ? "donation.history-tile-lore-pending" : "donation.history-tile-lore", "when", when(e.created()),
                                "amount", (e.amount() > 0 ? "+" : "") + shop.amount(e.amount()), "balance", shop.amount(e.balance())));
                    });
                    out.add(it);
                }
                return out;
            }
            Category c = shop.categories().get(tab);
            List<Product> products = new ArrayList<>();
            for (String pid : c == null ? List.<String>of() : c.products()) products.add(shop.products().get(pid));
            shown = products;
            for (Product product : products) {
                boolean owned = has(p, product, books.owned()), affords = DonationShop.affords(books.account().balance(), product);
                out.add(tile(p, product, Lang.items(owned ? "donation.tile-lore-owned" : affords ? "donation.tile-lore" : "donation.tile-lore-short",
                        "price", shop.amount(product.price()), "short", shop.amount(Math.max(0, product.price() - books.account().balance())))));
            }
            return out;
        }

        @Override
        public void onListClick(MenuView view, int index, ClickType click) {
            Player p = view.player();
            if (index < 0 || index >= shown.size()) return;
            Product product = shown.get(index);
            if (has(p, product, books.owned()) || !DonationShop.affords(books.account().balance(), product)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return;
            }
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) plugin.menus().open(p, "stsv:donation_buy", new BuyScreen(product, tab, books));
            });
        }

        @Override
        public void onAction(MenuView view, String action, ClickType click) {
            Player p = view.player();
            if (action.equals("donation:claim")) {
                view.close();
                deliverPending(p, true);
                return;
            }
            if (!action.startsWith("donation:tab:")) return;
            String word = action.substring("donation:tab:".length());
            String next;
            if (word.equals("history")) {
                next = HISTORY_TAB;
            } else {
                int k = Integer.parseInt(word);
                List<Category> cs = categories();
                if (k > cs.size()) return;
                next = cs.get(k - 1).id();
            }
            if (next.equals(tab)) return;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
            // the title names the tab: the menu is opened again (next tick, never inside its own click)
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) plugin.menus().open(p, "stsv:donation", new ShopScreen(next, books));
            });
        }
    }

    /** One product, once more, with the one button that buys it: nothing is bought by a single click in the shop. */
    private final class BuyScreen implements MenuHandler {
        private final Product product;
        private final String tab;
        private final Books books;
        private boolean done;

        BuyScreen(Product product, String tab, Books books) {
            this.product = product;
            this.tab = tab;
            this.books = books;
        }

        @Override
        public Map<String, String> placeholders(MenuView view) {
            return Map.of("name", product.name(), "price", shop.amount(product.price()), "balance", shop.amount(books.account().balance()),
                    "after", shop.amount(books.account().balance() - product.price()));
        }

        @Override
        public ItemStack button(MenuView view, MenuSpec.Button button, ItemStack base) {
            if (button.key() != 'O') return base;
            return tile(view.player(), product, Lang.items("donation.tile-lore-confirm", "price", shop.amount(product.price())));
        }

        @Override
        public void onAction(MenuView view, String action, ClickType click) {
            Player p = view.player();
            if (action.equals("donation:back")) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline()) openShop(p, tab);
                });
            } else if (action.equals("donation:confirm") && !done) {
                done = true;                    // one purchase a screen, however fast the clicks come
                view.close();
                buy(p, product, tab);
            }
        }
    }

    private Void failed(Player p, Throwable err) {
        plugin.getLogger().log(Level.WARNING, "[후원] " + p.getName() + ": 장부를 읽거나 쓰지 못함", err);
        Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(p.getUniqueId());
            if (p.isOnline()) p.sendMessage(Lang.msg("donation.error"));
        });
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Buying and handing over
    // ------------------------------------------------------------------------------------------

    /** Whether what a product gives can be put into the player's hands now (an empty slot for each of its items). */
    private boolean room(Player p, Product product) {
        if (product.items().isEmpty()) return true;
        int free = 0;
        for (ItemStack slot : p.getInventory().getStorageContents()) if (slot == null || slot.isEmpty()) free++;
        return free >= product.items().size();
    }

    /** Whether everything a product unlocks still exists (a theme that was taken out of the pack cannot be sold). */
    private boolean sellable(Product product) {
        if (product.unlocks().isEmpty()) return true;
        if (plugin.unlocks() == null) return false;
        for (String u : product.unlocks()) if (!plugin.unlocks().exists(u)) return false;
        return true;
    }

    /** tab: the shop's tab to come back to when it is done (null: stay out of the shop). */
    private void buy(Player p, Product product, String tab) {
        UUID id = p.getUniqueId();
        if (!sellable(product)) {
            p.sendMessage(Lang.msg("donation.not-sellable", "name", product.name()));
            return;
        }
        if (has(p, product, Set.of())) {
            p.sendMessage(Lang.msg("donation.already", "name", product.name()));
            return;
        }
        if (!room(p, product)) {
            p.sendMessage(Lang.msg("donation.no-room"));
            return;
        }
        if (!busy.add(id)) {
            p.sendMessage(Lang.msg("donation.busy"));
            return;
        }
        store.change(id, p.getName(), -product.price(), Kind.BUY, product.id(), product.name(), p.getName(), product.once(), System.currentTimeMillis())
                .thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (r.outcome() == Outcome.SHORT) {
                        if (p.isOnline()) p.sendMessage(Lang.msg("donation.short", "price", shop.amount(product.price()), "balance", shop.amount(r.balance())));
                        return;
                    }
                    if (r.outcome() == Outcome.ALREADY) {
                        if (p.isOnline()) p.sendMessage(Lang.msg("donation.already", "name", product.name()));
                        return;
                    }
                    plugin.getLogger().info("[후원] " + p.getName() + " 구매 #" + r.entry() + ": " + product.id() + " (" + shop.amount(product.price())
                            + ", 남은 " + shop.amount(r.balance()) + ")");
                    if (!p.isOnline()) return;      // paid, not delivered: handed over when they join
                    p.sendMessage(Lang.msg("donation.bought", "name", product.name(), "price", shop.amount(product.price()), "balance", shop.amount(r.balance())));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                    handOver(p, r.entry(), product);
                    if (tab != null) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline()) openShop(p, tab);
                    }, 30L);
                })).exceptionally(err -> failed(p, err));
    }

    /**
     * Hands a paid purchase over: first the line is marked delivered (only one caller ever gets a yes), then the
     * item and the commands follow. No room for the item: it stays undelivered, and the player is told how to get it.
     */
    private void handOver(Player p, long entry, Product product) {
        if (!room(p, product)) {
            p.sendMessage(Lang.msg("donation.pending-room", "name", product.name()));
            return;
        }
        store.delivered(entry).thenAccept(first -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!first) return;
            if (!p.isOnline()) {
                // marked, and they left before the next tick: unmarking is not safe (it might be handed over twice), so say so loudly
                plugin.getLogger().warning("[후원] 구매 #" + entry + " (" + product.id() + "): 지급 표시 뒤 " + p.getName()
                        + " 이(가) 나갔습니다. 직접 지급해 주세요: " + (product.items().isEmpty() ? product.commands() : "/관리자 지급 " + p.getName() + " " + String.join(", ", product.items())));
                return;
            }
            give(p, product);
            p.sendMessage(Lang.msg("donation.delivered", "name", product.name()));
        })).exceptionally(err -> failed(p, err));
    }

    private void give(Player p, Product product) {
        for (String id : product.items()) {
            Optional<CustomItem> item = plugin.content().item(id);
            if (item.isPresent()) {
                ItemStack stack = item.get().create(1);
                // a vehicle is its buyer's from the start (bound: only they place it)
                if (plugin.vehicles() != null) plugin.vehicles().bind(stack, p.getUniqueId(), p.getName());
                p.getInventory().addItem(stack).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
            } else {
                plugin.getLogger().warning("[후원] 상품 " + product.id() + " 의 아이템 " + id + " 이 없어 " + p.getName() + " 에게 주지 못했습니다.");
            }
        }
        if (plugin.unlocks() != null) {
            for (String u : product.unlocks()) {
                String[] thing = dev.stsv.core.unlock.UnlockModule.split(u);
                if (thing != null) plugin.unlocks().grant(p.getUniqueId(), thing[0], thing[1], "donation:" + product.id());
            }
        }
        for (String command : product.commands()) {
            String line = command.replace("{player}", p.getName());
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)) plugin.getLogger().warning("[후원] 상품 " + product.id() + " 의 명령어를 모름: " + line);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "[후원] 상품 " + product.id() + " 의 명령어 실패: " + line, e);
            }
        }
    }

    /** Hands over what a player paid for and has not got yet. loud: tell them when there is nothing. */
    private void deliverPending(Player p, boolean loud) {
        store.undelivered(p.getUniqueId()).thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (list.isEmpty()) {
                if (loud) p.sendMessage(Lang.msg("donation.nothing-pending"));
                return;
            }
            for (Entry e : list) {
                Product product = shop.products().get(e.product());
                if (product == null) {
                    plugin.getLogger().warning("[후원] " + p.getName() + " 의 구매 #" + e.id() + ": 상품 " + e.product() + " 이 이제 상점에 없어 주지 못했습니다 (환불: /관리자 후원 환불 " + e.id() + ")");
                    p.sendMessage(Lang.msg("donation.pending-gone", "number", e.id()));
                    continue;
                }
                handOver(p, e.id(), product);
            }
        })).exceptionally(err -> failed(p, err));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) deliverPending(p, false);
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        busy.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // History, administrators
    // ------------------------------------------------------------------------------------------

    private static String when(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
    }

    private String what(Entry e) {
        Product product = e.product() == null ? null : shop.products().get(e.product());
        String name = product != null ? product.name() : e.product() != null ? e.product() : e.note() == null ? "" : e.note();
        return Lang.raw("donation.kind." + e.kind().name().toLowerCase(java.util.Locale.ROOT)) + (name.isBlank() ? "" : " · " + name);
    }

    private void history(CommandSender to, UUID player, String name) {
        store.account(player).thenCombine(store.history(player, HISTORY), (account, list) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                to.sendMessage(Lang.msg("donation.history-head", "player", name, "balance", shop.amount(account.balance()), "granted", shop.amount(account.granted()),
                        "spent", shop.amount(account.spent())));
                if (list.isEmpty()) to.sendMessage(Lang.msg("donation.history-none"));
                for (Entry e : list) {
                    to.sendMessage(Lang.mm(e.kind() == Kind.BUY && !e.delivered() ? "donation.history-pending" : "donation.history-entry", "number", e.id(),
                            "when", when(e.created()), "what", what(e), "amount", (e.amount() > 0 ? "+" : "") + shop.amount(e.amount()), "balance", shop.amount(e.balance())));
                }
            });
            return null;
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING, "[후원] 내역을 읽지 못함", err);
            return null;
        });
    }

    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0];
        String actor = s instanceof Player p ? p.getName() : "console";
        switch (sub) {
            case "지급", "give", "회수", "take" -> {
                boolean give = sub.equals("지급") || sub.equals("give");
                long parsed;
                try {
                    parsed = a.length > 2 ? Long.parseLong(a[2].replace(",", "")) : 0;
                } catch (NumberFormatException e) {
                    parsed = 0;
                }
                final long amount = parsed;
                if (amount < 1 || amount > 10_000_000) {
                    s.sendMessage(Lang.msg("donation.admin-usage"));
                    return;
                }
                long delta = give ? amount : -amount;
                String note = a.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)) : null;
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Lang.msg("donation.admin-no-player", "name", a[1]));
                        return;
                    }
                    UUID id = found.get();
                    String name = Names.real(id);
                    store.change(id, name, delta, give ? Kind.GRANT : Kind.REVOKE, null, note, actor, false, System.currentTimeMillis())
                            .thenAccept(r -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (r.outcome() != Outcome.DONE) {
                                    s.sendMessage(Lang.msg("donation.admin-short", "player", name, "balance", shop.amount(r.balance())));
                                    return;
                                }
                                plugin.getLogger().info("[후원] " + actor + " → " + name + " " + (give ? "지급 " : "회수 ") + shop.amount(amount) + " (#" + r.entry()
                                        + ", 보유 " + shop.amount(r.balance()) + (note == null ? "" : ", " + note) + ")");
                                s.sendMessage(Lang.msg(give ? "donation.admin-given" : "donation.admin-taken", "player", name, "amount", shop.amount(amount),
                                        "balance", shop.amount(r.balance()), "number", r.entry()));
                                Player target = Bukkit.getPlayer(id);
                                if (target != null && give) {
                                    target.sendMessage(Lang.msg("donation.received", "amount", shop.amount(amount), "balance", shop.amount(r.balance())));
                                    target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.1f);
                                }
                            })).exceptionally(err -> {
                                plugin.getLogger().log(Level.WARNING, "[후원] 지급·회수 실패", err);
                                Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Lang.msg("donation.error")));
                                return null;
                            });
                });
            }
            case "조회", "내역", "info", "history" -> {
                if (a.length < 2) {
                    s.sendMessage(Lang.msg("donation.admin-usage"));
                    return;
                }
                Names.find(a[1], found -> {
                    if (found.isEmpty()) s.sendMessage(Lang.msg("donation.admin-no-player", "name", a[1]));
                    else history(s, found.get(), Names.real(found.get()));
                });
            }
            case "환불", "refund" -> {
                long number;
                try {
                    number = a.length > 1 ? Long.parseLong(a[1].replace("#", "")) : 0;
                } catch (NumberFormatException e) {
                    number = 0;
                }
                String note = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : null;
                refund(s, actor, number, note);
            }
            default -> s.sendMessage(Lang.msg("donation.admin-usage"));
        }
    }

    /**
     * Undoes one purchase in the books: its price back. What was handed over is not taken away by this (an item is
     * in the world, a command was run): the administrator sees to that, and is told so.
     */
    private void refund(CommandSender s, String actor, long number, String note) {
        store.refund(number, note, actor, System.currentTimeMillis()).thenAccept(done -> Bukkit.getScheduler().runTask(plugin, () -> {
            Entry e = done.entry();
            if (e == null) {
                s.sendMessage(Lang.msg("donation.admin-no-entry", "number", number));
                return;
            }
            String name = Names.real(e.player());
            if (done.result().outcome() != Outcome.DONE) {
                s.sendMessage(Lang.msg("donation.admin-refunded-before", "number", e.id()));
                return;
            }
            plugin.getLogger().info("[후원] " + actor + " 환불 #" + e.id() + " (" + name + ", " + e.product() + ", " + shop.amount(-e.amount()) + ")");
            Product product = e.product() == null ? null : shop.products().get(e.product());
            if (product != null && !done.wasPending() && plugin.unlocks() != null) {
                // what was unlocked goes back with the price (an item or a command's doing stays: the admin sees to that)
                for (String u : product.unlocks()) {
                    String[] thing = dev.stsv.core.unlock.UnlockModule.split(u);
                    if (thing != null) plugin.unlocks().revoke(e.player(), thing[0], thing[1]);
                }
            }
            s.sendMessage(Lang.msg(done.wasPending() ? "donation.admin-refunded-pending" : "donation.admin-refunded", "number", e.id(), "player", name,
                    "amount", shop.amount(-e.amount()), "balance", shop.amount(done.result().balance())));
        })).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING, "[후원] 환불 실패", err);
            Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Lang.msg("donation.error")));
            return null;
        });
    }
}
