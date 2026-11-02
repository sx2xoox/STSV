package dev.stsv.economy.vanity;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.data.Db;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.shop.ShopCatalog;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 과시 소비 (money that leaves the economy for good, and stays with the account): the cosmetic and
 * title shop, and monuments - a giant head and an inscription in the town, each dearer than the last.
 */
public final class VanityModule extends EcoModule {

    public record Monument(int slot, UUID player, String text, long at, long price) {}

    private Db db;
    private final Map<Integer, Monument> monuments = new LinkedHashMap<>();
    private final Map<Integer, List<org.bukkit.entity.Entity>> shown = new LinkedHashMap<>();

    public VanityModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-vanity";
    }

    @Override
    protected void start() {
        db = core.db();
        db.migrate("eco_monument", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_monument (slot INT NOT NULL PRIMARY KEY, player CHAR(36) NOT NULL, text VARCHAR(255) NOT NULL, "
                        + "at BIGINT NOT NULL, price BIGINT NOT NULL)")));
        monuments.clear();
        for (Monument m : db.sync(c -> Db.query(c, "SELECT slot, player, text, at, price FROM eco_monument ORDER BY slot",
                rs -> new Monument(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4), rs.getLong(5))))) {
            monuments.put(m.slot(), m);
        }
        every(200L, this::showAll);
        eco.onReset(() -> {
            hideAll();
            monuments.clear();
            db.execute("DELETE FROM eco_monument");
        });
    }

    @Override
    protected void stop() {
        hideAll();
    }

    // ------------------------------------------------------------------------------------------
    // The shop
    // ------------------------------------------------------------------------------------------

    public void openShop(Player p) {
        core.menus().open(p, "economy:vanity", new Shop());
    }

    private boolean owned(Player p, ShopCatalog.VanityEntry v) {
        if (v.kind().equals("cosmetic")) {
            return core.content().item(v.id()).map(c -> core.cosmetics().owns(p, c)).orElse(false);
        }
        if (bought.contains(p.getUniqueId() + "|" + v.id())) return true;   // granted, cache not updated yet
        return core.profiles().title(v.id()).map(t -> core.profiles().ownedTitles(p.getUniqueId()).contains(t.id())).orElse(false);
    }

    private final java.util.Set<String> bought = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Returns null on success, else the refusal key. */
    public String buy(Player p, ShopCatalog.VanityEntry v) {
        if (owned(p, v)) return "vanity.owned";
        if (v.kind().equals("cosmetic")) {
            Optional<CustomItem> c = core.content().item(v.id());
            if (c.isEmpty() || c.get().cosmetic() == null) return "vanity.unknown";
            if (!core.economy().withdraw(p.getUniqueId(), v.price(), "vanity_cosmetic")) return "vanity.not-enough";
            core.cosmetics().grant(p, c.get(), true);
            core.cosmetics().celebrate(p, c.get());
        } else {
            if (core.profiles().title(v.id()).isEmpty()) return "vanity.unknown";
            if (!core.economy().withdraw(p.getUniqueId(), v.price(), "vanity_title")) return "vanity.not-enough";
            core.profiles().grantTitle(p.getUniqueId(), v.id());
            bought.add(p.getUniqueId() + "|" + v.id());
        }
        eco.season().broadcast(Lang.msg("vanity.bought-broadcast", "player", p.getName(), "item", name(v), "price", EconomyModule.format(v.price())));
        return null;
    }

    private String name(ShopCatalog.VanityEntry v) {
        if (v.kind().equals("cosmetic")) return core.content().item(v.id()).map(c -> c.name() == null ? v.id() : c.name()).orElse(v.id());
        return core.profiles().title(v.id()).map(t -> t.name()).orElse(v.id());
    }

    private final class Shop implements MenuHandler {
        private String armed;
        private long armedAt;

        private List<ShopCatalog.VanityEntry> entries() {
            return eco.shop().catalog().vanity();
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("balance", EconomyModule.format(core.economy().balance(v.player().getUniqueId())));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (ShopCatalog.VanityEntry e : entries()) {
                ItemStack it = e.kind().equals("cosmetic")
                        ? core.content().item(e.id()).map(c -> c.create(1)).orElse(new ItemStack(Material.BARRIER))
                        : new ItemStack(Material.NAME_TAG);
                boolean own = owned(v.player(), e);
                boolean isArmed = (e.kind() + e.id()).equals(armed) && now() - armedAt < 5000;
                it.editMeta(meta -> {
                    meta.itemName(Text.item(name(e)));
                    List<Component> lore = new ArrayList<>(Lang.items(e.kind().equals("cosmetic") ? "vanity.lore-cosmetic" : "vanity.lore-title",
                            "price", EconomyModule.format(e.price())));
                    lore.addAll(Lang.items(own ? "vanity.lore-owned" : isArmed ? "vanity.lore-confirm" : "vanity.lore-buy"));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(own || isArmed);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            List<ShopCatalog.VanityEntry> list = entries();
            if (index >= list.size()) return;
            ShopCatalog.VanityEntry e = list.get(index);
            Player p = v.player();
            String key = e.kind() + e.id();
            if (!key.equals(armed) || now() - armedAt > 5000) {
                armed = key;
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = null;
            String refused = buy(p, e);
            if (refused != null) HudModule.flash(p, Lang.mm(refused, "price", EconomyModule.format(e.price())));
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Monuments
    // ------------------------------------------------------------------------------------------

    public long nextPrice() {
        var c = eco.data("shop.yml");
        double base = c.getDouble("monument.base", 20000), growth = c.getDouble("monument.growth", 1.5);
        return Math.round(base * Math.pow(growth, monuments.size()));
    }

    private int maxLength() {
        return eco.data("shop.yml").getInt("monument.max-length", 30);
    }

    private Integer freeSlot() {
        for (int i = 1; i <= 50; i++) {
            if (eco.location("monument-" + i) == null) return null;
            if (!monuments.containsKey(i)) return i;
        }
        return null;
    }

    public void build(Player p, String text) {
        if (!eco.season().checkOpen(p, "monument")) return;
        String clean = text.replace("<", "").replace(">", "").trim();
        if (clean.isEmpty() || clean.length() > maxLength()) {
            p.sendMessage(Lang.msg("vanity.monument-usage", "max", maxLength(), "price", EconomyModule.format(nextPrice())));
            return;
        }
        Integer slot = freeSlot();
        if (slot == null) {
            p.sendMessage(Lang.msg("vanity.monument-full"));
            return;
        }
        long price = nextPrice();
        if (!core.economy().withdraw(p.getUniqueId(), price, "monument")) {
            p.sendMessage(Lang.msg("vanity.not-enough", "price", EconomyModule.format(price)));
            return;
        }
        Monument m = new Monument(slot, p.getUniqueId(), clean, now(), price);
        monuments.put(slot, m);
        db.execute("INSERT INTO eco_monument (slot, player, text, at, price) VALUES (?, ?, ?, ?, ?)", slot, p.getUniqueId().toString(), clean, m.at(), price);
        show(m);
        eco.season().broadcast(Lang.msg("vanity.monument-built", "player", p.getName(), "text", clean, "price", EconomyModule.format(price)));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.showTitle(Title.title(Lang.mm("vanity.monument-title"), Lang.mm("vanity.monument-subtitle", "player", p.getName()),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
            o.playSound(o.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1f);
        }
    }

    private void showAll() {
        for (Monument m : monuments.values()) {
            List<org.bukkit.entity.Entity> ents = shown.get(m.slot());
            if (ents == null || ents.stream().anyMatch(e -> !e.isValid())) show(m);
        }
    }

    private void show(Monument m) {
        Location at = eco.location("monument-" + m.slot());
        if (at == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        List<org.bukkit.entity.Entity> old = shown.remove(m.slot());
        if (old != null) old.forEach(org.bukkit.entity.Entity::remove);
        for (var e : at.getWorld().getNearbyEntities(at, 2, 4, 2)) if (e.getScoreboardTags().contains("stsv-monument-" + m.slot())) e.remove();
        String tag = "stsv-monument-" + m.slot();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, sm -> sm.setOwningPlayer(Bukkit.getOfflinePlayer(m.player())));
        ItemDisplay statue = at.getWorld().spawn(at.clone().add(0, 2.2, 0), ItemDisplay.class, d -> {
            d.addScoreboardTag(tag);
            d.setPersistent(false);
            d.setItemStack(head);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(2.5f, 2.5f, 2.5f), new AxisAngle4f()));
            d.setRotation(at.getYaw(), 0);
        });
        String date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(m.at()));
        TextDisplay plate = at.getWorld().spawn(at.clone().add(0, 0.9, 0), TextDisplay.class, d -> {
            d.addScoreboardTag(tag);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(170, 20, 16, 8));
            d.text(Text.mm(Lang.raw("vanity.monument-plate", "player", Players.name(m.player()), "text", m.text(), "date", date,
                    "price", EconomyModule.format(m.price()))));
        });
        shown.put(m.slot(), List.of(statue, plate));
    }

    private void hideAll() {
        for (List<org.bukkit.entity.Entity> l : shown.values()) l.forEach(org.bukkit.entity.Entity::remove);
        shown.clear();
    }

    // ------------------------------------------------------------------------------------------
    // 세무 조사: a donation to the state; enough of it earns a title
    // ------------------------------------------------------------------------------------------

    public void donate(Player p, long amount) {
        List<dev.stsv.economy.season.ActiveEvent> events = eco.season().active("tax_audit");
        if (events.isEmpty()) {
            p.sendMessage(Lang.msg("vanity.donate-closed"));
            return;
        }
        var e = events.getFirst();
        long min = (long) e.number("donation-min", 5000);
        if (amount <= 0) {
            p.sendMessage(Lang.msg("vanity.donate-usage", "min", EconomyModule.format(min)));
            return;
        }
        if (!core.economy().withdraw(p.getUniqueId(), amount, "donation")) {
            p.sendMessage(Lang.msg("vanity.not-enough", "price", EconomyModule.format(amount)));
            return;
        }
        String key = "donation." + e.id() + "." + p.getUniqueId();
        long total = eco.kv().getLong(key, 0) + amount;
        eco.kv().set(key, total);
        eco.season().broadcast(Lang.msg("vanity.donated", "player", p.getName(), "amount", EconomyModule.format(amount)));
        String title = e.params().getOrDefault("donation-title", "economy:patron");
        if (total >= min && !eco.kv().has(key + ".title")) {
            eco.kv().set(key + ".title", "1");
            core.profiles().grantTitle(p.getUniqueId(), title);
        } else if (total < min) {
            p.sendMessage(Lang.msg("vanity.donate-progress", "total", EconomyModule.format(total), "min", EconomyModule.format(min)));
        }
    }

    public List<Monument> monuments() {
        return List.copyOf(monuments.values());
    }
}
