package dev.stsv.core.cosmetic;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.Rarity;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 치장 뽑기 (/뽑기, the 상점's 구매 → 치장 뽑기, the wardrobe and the hub): money for a random cosmetic of the pool.
 * A cosmetic the player has already comes as its voucher (an item: traded, sold on the 거래소, or right-clicked
 * by whoever does not have it). Rules: contents/&lt;pack&gt;/gacha.yml ({@link GachaRules}); the menu:
 * contents/stsv/menus/gacha.yml. The pull is paid and given at once; the reveal afterwards is only a show.
 * The chances are not shown to players (the owner's choice); they are in gacha.yml.
 */
public final class GachaModule implements StsvModule, Listener, CommandExecutor {

    static final String MENU = "stsv:gacha";
    private static final long CONFIRM_MILLIS = 5000;

    private STSVPlugin plugin;
    private GachaStore store;
    private GachaRules rules;
    private List<GachaRules.Entry> pool = List.of();
    /** cosmetic id -> its voucher item */
    private Map<String, CustomItem> vouchers = Map.of();
    /** players watching their reveal (no new pull until it ends) */
    private final Map<UUID, BukkitTask> revealing = new HashMap<>();

    @Override
    public String id() {
        return "cosmetic-gacha";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new GachaStore(plugin.db());
        store.load();
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        revealing.values().forEach(BukkitTask::cancel);
        revealing.clear();
        HandlerList.unregisterAll(this);
    }

    /** The first pack (by folder name) with a gacha.yml sets the rules; no file = no gacha. */
    private void load() {
        List<String> problems = new ArrayList<>();
        rules = null;
        for (ContentPack pack : plugin.content().packs()) {
            Path f = pack.dir().resolve("gacha.yml");
            if (!Files.isRegularFile(f)) continue;
            rules = GachaRules.parse(YamlConfiguration.loadConfiguration(f.toFile()), problems);
            break;
        }
        Map<String, CustomItem> v = new HashMap<>();
        for (CustomItem i : plugin.content().items()) {
            if (i.voucher() != null) v.putIfAbsent(i.voucher(), i);
        }
        vouchers = Map.copyOf(v);
        pool = rules == null ? List.of() : rules.entries(
                id -> plugin.content().item(id).map(CustomItem::cosmetic).map(CustomItem.Cosmetic::rarity).orElse(null),
                id -> plugin.content().item(id).map(i -> vouchers.containsKey(i.key().asString())).orElse(false), problems)
                .stream().map(e -> new GachaRules.Entry(plugin.content().item(e.id()).orElseThrow().key().asString(), e.rarity())).toList();
        problems.forEach(p -> plugin.getLogger().warning("[치장 뽑기] gacha.yml " + p));
        if (rules != null && pool.isEmpty()) plugin.getLogger().warning("[치장 뽑기] 뽑을 치장이 없습니다 (gacha.yml pool)");
    }

    public boolean available() {
        return rules != null && !pool.isEmpty() && plugin.economy() != null;
    }

    public long singlePrice() {
        return rules == null ? 0 : rules.singlePrice();
    }

    public long multiPrice() {
        return rules == null ? 0 : rules.multiPrice();
    }

    public int multiCount() {
        return rules == null ? 10 : rules.multiCount();
    }

    /** Owner-facing (/관리자 치장 정보): the pity counter and all draws. */
    public String info(UUID player) {
        GachaStore.Row r = store.get(player);
        return "뽑기: 천장 카운트 " + r.pity() + "/" + (rules == null ? 0 : rules.pity()) + " · 총 " + r.total() + "회 · 오늘 " + r.pullsOn(today()) + "회";
    }

    private static int today() {
        return (int) LocalDate.now().toEpochDay();
    }

    // ------------------------------------------------------------------------------------------
    // /뽑기 and the menu
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (sender instanceof Player p) open(p, null);
        else sender.sendMessage(Lang.msg("common.players-only"));
        return true;
    }

    /** Opens the menu; back (may be null) is what the back button does (e.g. the shop), else it closes. */
    public void open(Player p, Runnable back) {
        if (!available()) {
            p.sendMessage(Lang.msg("gacha.unavailable"));
            return;
        }
        if (plugin.cosmetics() != null && plugin.cosmetics().inWardrobe(p)) {
            // out of the wardrobe first (camera, game mode, place), then the menu on the next tick
            plugin.cosmetics().exitWardrobe(p, false);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) plugin.menus().open(p, MENU, new Menu(back));
            });
            return;
        }
        plugin.menus().open(p, MENU, new Menu(back));
    }

    private final class Menu implements MenuHandler {
        private final Runnable back;
        /** "1" or "multi" armed by a first click (a pull is paid on the second) */
        private String armed;
        private long armedAt;

        Menu(Runnable back) {
            this.back = back;
        }

        private boolean armed(String which) {
            return which.equals(armed) && System.currentTimeMillis() - armedAt < CONFIRM_MILLIS;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Player p = v.player();
            Set<String> owned = plugin.cosmetics().owned(p.getUniqueId());
            Map<String, String> m = new HashMap<>();
            m.put("balance", EconomyModule.format(plugin.economy().balance(p.getUniqueId())));
            m.put("single", EconomyModule.format(rules.singlePrice()));
            m.put("multi", EconomyModule.format(rules.multiPrice()));
            m.put("count", String.valueOf(rules.multiCount()));
            long full = rules.singlePrice() * rules.multiCount();
            m.put("discount", String.valueOf(full <= 0 ? 0 : Math.round(100.0 * (full - rules.multiPrice()) / full)));
            m.put("floor", rules.multiFloor().color + rules.multiFloor().label);
            m.put("owned", String.valueOf(pool.stream().filter(e -> owned.contains(e.id())).count()));
            m.put("total", String.valueOf(pool.size()));
            m.put("confirm_single", Lang.raw(armed("1") ? "gacha.confirm-again" : "gacha.confirm-click"));
            m.put("confirm_multi", Lang.raw(armed("multi") ? "gacha.confirm-again" : "gacha.confirm-click"));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base != null && (b.action().equals("gacha:pull:1") && armed("1") || b.action().equals("gacha:pull:multi") && armed("multi"))) {
                base.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            }
            return base;
        }

        /** The pool, best first: what can come out (no chances shown). */
        @Override
        public List<ItemStack> list(MenuView v) {
            Set<String> owned = plugin.cosmetics().owned(v.player().getUniqueId());
            List<ItemStack> out = new ArrayList<>();
            for (GachaRules.Entry e : sortedPool()) {
                Optional<CustomItem> c = plugin.content().item(e.id());
                if (c.isEmpty()) continue;
                ItemStack it = c.get().create(1);
                boolean own = owned.contains(e.id());
                it.editMeta(meta -> {
                    List<Component> lore = new ArrayList<>();
                    lore.add(Lang.item("wardrobe.lore-rarity", "color", e.rarity().color, "rarity", e.rarity().label,
                            "slot", Lang.raw("cosmetic.slot." + c.get().cosmetic().slot().id)));
                    lore.addAll(Lang.items(own ? "gacha.lore-owned" : "gacha.lore-new"));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(own);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "gacha:pull:1", "gacha:pull:multi" -> {
                    String which = action.endsWith("multi") ? "multi" : "1";
                    if (!armed(which)) {
                        armed = which;
                        armedAt = System.currentTimeMillis();
                        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                        v.refresh();
                        return;
                    }
                    armed = null;
                    int n = which.equals("multi") ? rules.multiCount() : 1;
                    Bukkit.getScheduler().runTask(plugin, () -> pull(p, n, back));
                }
                case "gacha:back" -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (back != null) back.run();
                    else v.close();
                });
                default -> { }
            }
        }
    }

    private List<GachaRules.Entry> sortedPool() {
        return pool.stream().sorted(Comparator.comparing(GachaRules.Entry::rarity).reversed()
                .thenComparing(e -> plugin.content().item(e.id()).map(c -> c.cosmetic().slot().ordinal()).orElse(0))
                .thenComparing(GachaRules.Entry::id)).toList();
    }

    // ------------------------------------------------------------------------------------------
    // A pull
    // ------------------------------------------------------------------------------------------

    /** Pays, draws, grants (new cosmetics to the account, duplicates as vouchers), then shows the reveal. */
    public void pull(Player p, int n, Runnable back) {
        if (!p.isOnline() || !available()) return;
        UUID id = p.getUniqueId();
        if (revealing.containsKey(id)) {
            HudModule.flash(p, Lang.mm("gacha.busy"));
            return;
        }
        long price = rules.price(n);
        GachaStore.Row row = store.get(id);
        int today = today();
        if (rules.dailyLimit() > 0 && row.pullsOn(today) + n > rules.dailyLimit()) {
            p.sendMessage(Lang.msg("gacha.daily-limit", "limit", rules.dailyLimit(), "left", Math.max(0, rules.dailyLimit() - row.pullsOn(today))));
            return;
        }
        if (!plugin.economy().has(id, price) || !plugin.economy().withdraw(id, price, "cosmetic_gacha")) {
            HudModule.flash(p, Lang.mm("gacha.not-enough", "price", EconomyModule.format(price)));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            return;
        }
        GachaRules.Result result = rules.pull(pool, n, row.pity(), plugin.cosmetics().owned(id), ThreadLocalRandom.current()::nextDouble);
        store.set(id, row.after(n, result.pityAfter(), today));
        List<ItemStack> give = new ArrayList<>();
        for (GachaRules.Draw d : result.draws()) {
            CustomItem c = plugin.content().item(d.entry().id()).orElseThrow();
            if (d.duplicate()) give.add(vouchers.get(c.key().asString()).create(1));
            else plugin.cosmetics().grant(id, c);
        }
        int dropped = 0;
        for (ItemStack left : p.getInventory().addItem(give.toArray(ItemStack[]::new)).values()) {
            dropped += left.getAmount();
            p.getWorld().dropItem(p.getLocation(), left, item -> {
                item.setOwner(id);
                item.setUnlimitedLifetime(true);
            });
        }
        if (dropped > 0) p.sendMessage(Lang.msg("gacha.dropped", "count", dropped));
        plugin.getLogger().info("[치장 뽑기] " + p.getName() + " " + n + "회 (" + price + "G): "
                + String.join(", ", result.draws().stream().map(d -> d.entry().id() + (d.duplicate() ? "(교환권)" : "")).toList()));
        reveal(p, result.draws(), () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) open(p, back);
        });
    }

    // ------------------------------------------------------------------------------------------
    // The reveal: a drum roll, then each draw as a title in its rarity's colour
    // ------------------------------------------------------------------------------------------

    private static final Title.Times QUICK = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200));
    private static final Title.Times HOLD = Title.Times.times(Duration.ZERO, Duration.ofMillis(2500), Duration.ofMillis(600));

    private void reveal(Player p, List<GachaRules.Draw> draws, Runnable after) {
        p.closeInventory();
        Map<Integer, Runnable> at = new LinkedHashMap<>();
        // drum roll: three beats, the best rarity's colour shows on the last
        Rarity best = draws.stream().map(d -> d.entry().rarity()).max(Comparator.naturalOrder()).orElse(Rarity.COMMON);
        for (int b = 0; b < 3; b++) {
            int beat = b;
            at.put(b * 6, () -> {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 0.8f + beat * 0.25f);
                p.showTitle(Title.title(Lang.mm("gacha.roll-title", "dots", ".".repeat(beat + 1)),
                        beat == 2 ? Lang.mm("gacha.roll-hint", "color", best.color) : Component.empty(), QUICK));
            });
        }
        int t = 20;
        boolean single = draws.size() == 1;
        for (int i = 0; i < draws.size(); i++) {
            GachaRules.Draw d = draws.get(i);
            int index = i;
            at.put(t, () -> show(p, d, index, draws.size()));
            t += single ? 50 : d.entry().rarity().compareTo(Rarity.EPIC) >= 0 ? 24 : 9;
        }
        at.put(t, () -> summary(p, draws));
        int end = t + 40;
        int[] tick = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                stop(p.getUniqueId());
                return;
            }
            Runnable r = at.get(tick[0]);
            if (r != null) r.run();
            if (tick[0]++ >= end) {
                stop(p.getUniqueId());
                after.run();
            }
        }, 1L, 1L);
        revealing.put(p.getUniqueId(), task);
    }

    private void stop(UUID id) {
        BukkitTask task = revealing.remove(id);
        if (task != null) task.cancel();
    }

    private void show(Player p, GachaRules.Draw d, int index, int of) {
        Rarity r = d.entry().rarity();
        String name = plugin.content().item(d.entry().id()).map(GachaModule::name).orElse(d.entry().id());
        String counter = of > 1 ? Lang.raw("gacha.counter", "n", index + 1, "of", of) : "";
        p.showTitle(Title.title(Lang.mm("gacha.reveal-title", "color", r.color, "cosmetic", name),
                Lang.mm(d.duplicate() ? "gacha.reveal-duplicate" : "gacha.reveal-new", "color", r.color, "rarity", r.label, "counter", counter),
                of > 1 && r.compareTo(Rarity.EPIC) < 0 ? QUICK : HOLD));
        switch (r) {
            case COMMON -> p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.0f);
            case RARE -> {
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
            }
            default -> {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.1f);
                p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.4);
            }
        }
        if (r.compareTo(rules.broadcastFrom()) >= 0) {
            Bukkit.broadcast(Lang.msg("gacha.broadcast", "player", p.getName(), "color", r.color, "rarity", r.label, "cosmetic", name));
        }
    }

    private void summary(Player p, List<GachaRules.Draw> draws) {
        long fresh = draws.stream().filter(d -> !d.duplicate()).count();
        p.sendMessage(Lang.msg("gacha.result", "count", draws.size(), "new", fresh, "vouchers", draws.size() - fresh));
        for (GachaRules.Draw d : draws) {
            Rarity r = d.entry().rarity();
            String name = plugin.content().item(d.entry().id()).map(GachaModule::name).orElse(d.entry().id());
            p.sendMessage(Lang.mm(d.duplicate() ? "gacha.result-duplicate" : "gacha.result-new", "color", r.color, "rarity", r.label, "cosmetic", name));
        }
        if (draws.size() > 1) {
            p.showTitle(Title.title(Lang.mm("gacha.summary-title"),
                    Lang.mm("gacha.summary-subtitle", "new", fresh, "vouchers", draws.size() - fresh), HOLD));
        }
        if (fresh > 0) p.sendMessage(Lang.msg("gacha.wear-hint"));
    }

    private static String name(CustomItem c) {
        return c.name() == null ? c.key().getKey() : c.name();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer().getUniqueId());
    }
}
