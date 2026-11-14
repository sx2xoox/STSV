package dev.stsv.core.donation;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.donation.DonationShop.Category;
import dev.stsv.core.donation.DonationShop.Product;
import dev.stsv.core.donation.DonationStore.Entry;
import dev.stsv.core.donation.DonationStore.Kind;
import dev.stsv.core.donation.DonationStore.Outcome;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
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
    private static final int WIDTH = 200, HISTORY = 10;

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
            case "내역", "history" -> history(p, p.getUniqueId(), Names.plain(p));
            case "받기", "claim" -> deliverPending(p, true);
            case "분류", "category" -> {
                Category c = a.length > 1 ? shop.categories().get(a[1]) : null;
                if (c == null) openMain(p);
                else openCategory(p, c);
            }
            case "상품", "product" -> {
                Product product = a.length > 1 ? shop.products().get(a[1]) : null;
                if (product == null) openMain(p);
                else openProduct(p, product);
            }
            case "구매", "buy" -> {
                Product product = a.length > 1 ? shop.products().get(a[1]) : null;
                if (product == null) p.sendMessage(Lang.msg("donation.unknown"));
                else if (a.length < 3 || !a[2].equals("확인")) openProduct(p, product);
                else buy(p, product);
            }
            default -> openMain(p);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        return a.length == 1 ? List.of("내역", "받기").stream().filter(s -> s.startsWith(a[0])).toList() : List.of();
    }

    private static ActionButton button(Component label, Component tooltip, String command) {
        ActionButton.Builder b = ActionButton.builder(label).width(WIDTH).action(DialogAction.staticAction(ClickEvent.runCommand(command)));
        if (tooltip != null) b.tooltip(tooltip);
        return b.build();
    }

    private static void show(Player p, Component title, List<Component> lines, List<ActionButton> buttons, int columns) {
        List<DialogBody> body = new ArrayList<>();
        for (Component line : lines) body.add(DialogBody.plainMessage(line));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title).canCloseWithEscape(true).afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).build())
                .type(DialogType.multiAction(buttons).columns(columns)
                        .exitAction(ActionButton.builder(Lang.mm("donation.close")).width(WIDTH).build())
                        .build()));
        p.showDialog(dialog);
    }

    /** The balance, the shop's notice, the categories, the history. */
    private void openMain(Player p) {
        store.account(p.getUniqueId()).thenAccept(account -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            List<Component> lines = new ArrayList<>();
            lines.add(Lang.mm("donation.balance", "amount", shop.amount(account.balance()), "currency", shop.currency()));
            for (String line : shop.notice()) lines.add(dev.stsv.core.util.Text.mm(line));
            List<ActionButton> buttons = new ArrayList<>();
            for (Category c : shop.categories().values()) {
                buttons.add(button(Lang.mm("donation.button-category", "name", c.name(), "count", c.products().size()), null, "/후원 분류 " + c.id()));
            }
            if (shop.categories().isEmpty()) lines.add(Lang.mm("donation.empty"));
            buttons.add(button(Lang.mm("donation.button-history"), null, "/후원 내역"));
            show(p, Lang.mm("donation.title"), lines, buttons, 2);
        })).exceptionally(err -> failed(p, err));
    }

    private void openCategory(Player p, Category c) {
        UUID id = p.getUniqueId();
        store.account(id).thenCombine(store.owned(id), (account, owned) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                List<ActionButton> buttons = new ArrayList<>();
                for (String pid : c.products()) {
                    Product product = shop.products().get(pid);
                    boolean has = product.once() && owned.contains(pid);
                    String key = has ? "donation.button-product-owned" : DonationShop.affords(account.balance(), product) ? "donation.button-product"
                            : "donation.button-product-short";
                    buttons.add(button(Lang.mm(key, "name", product.name(), "price", shop.amount(product.price())),
                            product.description().isBlank() ? null : dev.stsv.core.util.Text.mm(product.description()), "/후원 상품 " + pid));
                }
                buttons.add(button(Lang.mm("donation.button-back"), null, "/후원"));
                show(p, Lang.mm("donation.category-title", "name", c.name()),
                        List.of(Lang.mm("donation.balance", "amount", shop.amount(account.balance()), "currency", shop.currency())), buttons, 2);
            });
            return null;
        }).exceptionally(err -> failed(p, err));
    }

    /** One product: what it is, what it costs, what is left after, and the one button that buys it. */
    private void openProduct(Player p, Product product) {
        UUID id = p.getUniqueId();
        store.account(id).thenCombine(store.owned(id), (account, owned) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                List<Component> lines = new ArrayList<>();
                if (!product.description().isBlank()) lines.add(dev.stsv.core.util.Text.mm(product.description()));
                lines.add(Lang.mm("donation.product-price", "price", shop.amount(product.price()), "balance", shop.amount(account.balance())));
                List<ActionButton> buttons = new ArrayList<>();
                if (product.once() && owned.contains(product.id())) {
                    lines.add(Lang.mm("donation.product-owned"));
                } else if (!DonationShop.affords(account.balance(), product)) {
                    lines.add(Lang.mm("donation.product-short", "short", shop.amount(product.price() - account.balance())));
                } else {
                    lines.add(Lang.mm("donation.product-after", "after", shop.amount(account.balance() - product.price())));
                    lines.add(Lang.mm("donation.product-final"));
                    buttons.add(button(Lang.mm("donation.button-buy", "price", shop.amount(product.price())), null, "/후원 구매 " + product.id() + " 확인"));
                }
                buttons.add(button(Lang.mm("donation.button-back"), null, "/후원"));
                show(p, Lang.mm("donation.product-title", "name", product.name()), lines, buttons, 1);
            });
            return null;
        }).exceptionally(err -> failed(p, err));
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

    /** Whether what a product gives can be put into the player's hands now (an item needs an empty slot). */
    private boolean room(Player p, Product product) {
        return product.item() == null || p.getInventory().firstEmpty() >= 0;
    }

    private void buy(Player p, Product product) {
        UUID id = p.getUniqueId();
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
                        + " 이(가) 나갔습니다. 직접 지급해 주세요: " + (product.item() != null ? "/관리자 지급 " + p.getName() + " " + product.item() : product.commands()));
                return;
            }
            give(p, product);
            p.sendMessage(Lang.msg("donation.delivered", "name", product.name()));
        })).exceptionally(err -> failed(p, err));
    }

    private void give(Player p, Product product) {
        if (product.item() != null) {
            Optional<CustomItem> item = plugin.content().item(product.item());
            if (item.isPresent()) {
                ItemStack stack = item.get().create(1);
                // a vehicle is its buyer's from the start (bound: only they place it)
                if (plugin.vehicles() != null) plugin.vehicles().bind(stack, p.getUniqueId(), p.getName());
                p.getInventory().addItem(stack).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
            } else {
                plugin.getLogger().warning("[후원] 상품 " + product.id() + " 의 아이템 " + product.item() + " 이 없어 " + p.getName() + " 에게 주지 못했습니다.");
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
            s.sendMessage(Lang.msg(done.wasPending() ? "donation.admin-refunded-pending" : "donation.admin-refunded", "number", e.id(), "player", name,
                    "amount", shop.amount(-e.amount()), "balance", shop.amount(done.result().balance())));
        })).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING, "[후원] 환불 실패", err);
            Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Lang.msg("donation.error")));
            return null;
        });
    }
}
