package dev.stsv.economy.accessory;

import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 장신구 (/장신구): six slots worn server-side (the items leave the inventory), each accessory one main effect at its grade, roll
 * and enhance level, sub-options opened by its level. Other modules ask {@link #value} for an effect's amount and report what the
 * player did with {@link #activity} (accessory XP) and {@link #drop} (a chance of a new accessory). The workshop enhances, refines
 * and rerolls; boxes, fragments and the 용궁 상자 hand them out. docs/plans/장신구_시스템_기획안.md
 */
public final class AccessoryModule extends EcoModule {

    public static final NamespacedKey KEY = new NamespacedKey("economy", "acc");
    private static final NamespacedKey MINING = new NamespacedKey("economy", "acc_mining");
    private static final NamespacedKey MOVE = new NamespacedKey("economy", "acc_move");
    private static final String MENU = "economy:accessories", WORKSHOP = "economy:accessory_workshop", EFFECTS = "economy:accessory_effects";

    private AccessorySpec spec;
    private AccessoryStore store;
    /** player -> effect -> amount (worn main effects: the highest; sub-options add) */
    private final Map<UUID, Map<String, Double>> values = new ConcurrentHashMap<>();
    private Bag bags;
    private VeinSight sight;

    public AccessoryModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "economy-accessory";
    }

    @Override
    protected void start() {
        load();
        store = new AccessoryStore(core.db());
        store.init();
        bags = new Bag(this);
        Bukkit.getPluginManager().registerEvents(bags, eco);
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        every(1200L, store::flush);
        every(1200L, this::walked);
        every(5L, bags::magnet);
        sight = new VeinSight(this);
        every(30L, sight::tick);
        // the core's vehicles: 탈것 가속 for the one they drive, 짐 더 싣기 for the ones they own
        if (core.vehicles() != null) core.vehicles().bonuses((u, k) -> switch (k) {
            case "speed" -> value(u, "vehicle_speed");
            case "payload" -> value(u, "cargo");
            default -> 0.0;
        });
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        load();
        // the numbers come from accessories.yml (an item keeps only its roll, grade, effect and enhance level): the
        // descriptions are drawn again with the new ranges, the effects recomputed
        for (Player p : Bukkit.getOnlinePlayers()) {
            redraw(p);
            refresh(p);
        }
    }

    @Override
    protected void stop() {
        if (core.vehicles() != null) core.vehicles().bonuses(null);
        if (sight != null) sight.clear();
        if (bags != null) {
            bags.closeAll();
            org.bukkit.event.HandlerList.unregisterAll(bags);
        }
        store.flush();
        for (Player p : Bukkit.getOnlinePlayers()) clearAttributes(p);
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = AccessorySpec.parse(eco.data("accessories.yml"), problems);
        problems.forEach(p -> eco.getLogger().warning("[accessories.yml] " + p));
    }

    public AccessorySpec spec() {
        return spec;
    }

    AccessoryStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // What other modules ask
    // ------------------------------------------------------------------------------------------

    /** The amount of an effect for a player (0 when not worn): fractions (0.43 = 43%), factor bonuses (1.0 = x2), blocks, rows. */
    public double value(UUID player, String effect) {
        if (player == null) return 0;
        Map<String, Double> m = values.get(player);
        return m == null ? 0 : m.getOrDefault(effect, 0.0);
    }

    /** A chance effect: true with its amount as the chance. */
    public boolean roll(UUID player, String effect) {
        double v = value(player, effect);
        return v > 0 && ThreadLocalRandom.current().nextDouble() < v;
    }

    /** What the player did (mine, harvest, fish, ...): XP for every worn accessory whose effect levels on it. */
    public void activity(UUID player, String activity, double times) {
        if (player == null || times <= 0) return;
        Double w = spec.activities().get(activity);
        if (w == null) return;
        ItemStack[] worn = store.worn(player);
        boolean changed = false;
        for (int i = 0; i < worn.length; i++) {
            Accessory a = read(worn[i]);
            if (a == null) continue;
            AccessorySpec.Effect e = spec.effects().get(a.effect);
            if (e == null || !e.activity().equals(activity)) continue;
            int before = spec.levelFor(a.xp);
            a.xp = Math.min(spec.xpFor(spec.maxLevel()), a.xp + w * times);
            int after = spec.levelFor(a.xp);
            if (after > before) leveled(player, a, before, after);
            worn[i] = write(worn[i], a);
            changed = true;
            if (after > before) recompute(player);
        }
        if (changed) store.touched(player);
    }

    /** A chance of a new accessory while doing something (drops: mine, harvest, fish, kill, machine). */
    public void drop(Player p, String kind) {
        Double base = spec.drops().get(kind);
        if (base == null || base <= 0) return;
        double chance = base * (1 + value(p.getUniqueId(), "fortune_find"));
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        Accessory a = rollNew(null, spec.draw(false, 1 + value(p.getUniqueId(), "appraiser"), ThreadLocalRandom.current().nextDouble()));
        ItemStack it = create(a);
        give(p, it);
        p.sendMessage(Lang.msg("accessory.dropped", "item", Items.mm(it.getItemMeta().itemName())));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.4f);
        announce(p, a, it);
    }

    // ------------------------------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------------------------------

    public static Accessory read(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        return Accessory.decode(it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
    }

    public static boolean isAccessory(ItemStack it) {
        return read(it) != null;
    }

    /** The trade fee on accessories (거래소 · 경매): 10%, the rest of the market's own. */
    public static final double TRADE_FEE = 0.10;

    /** An accessory's item id (economy:accessory, or the old economy:acc_<slot>_<grade>), not the box or the fragment. */
    public static boolean isAccessoryKey(String key) {
        return key != null && (key.equals(Accessory.ITEM) || key.matches("economy:acc_(ring|necklace|earring|belt|bracelet)_[a-z]+"));
    }

    /** An accessory on the old per-slot item (economy:acc_<slot>_<grade>) made again on economy:accessory, data kept; else as it is. */
    ItemStack migrate(ItemStack it) {
        Accessory a = read(it);
        if (a == null || Accessory.ITEM.equals(Items.key(it))) return it;
        ItemStack fresh = create(a);
        return fresh;
    }

    /** A new accessory (an effect, or any at random) at a grade, rolled. */
    public Accessory rollNew(String effect, AccessorySpec.Grade g) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (effect == null) {
            List<String> ids = new ArrayList<>(spec.effects().keySet());
            effect = ids.get(r.nextInt(ids.size()));
        }
        return new Accessory(UUID.randomUUID(), effect, g.id(), r.nextDouble());
    }

    public ItemStack create(Accessory a) {
        AccessorySpec.Effect e = spec.effects().get(a.effect);
        ItemStack it = Items.create(Accessory.ITEM, 1).orElse(new ItemStack(Material.PAPER));
        return write(it, a);
    }

    /** Puts the accessory's data on the item and draws its name and lore again. */
    public ItemStack write(ItemStack it, Accessory a) {
        AccessorySpec.Effect e = spec.effects().get(a.effect);
        AccessorySpec.Grade g = spec.grades().get(a.grade);
        if (e == null || g == null) return it;
        int lv = spec.levelFor(a.xp);
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, a.encode());
            String plus = a.enhance > 0 ? " <white>+" + a.enhance : "";
            m.itemName(Text.item("<" + g.color() + ">[" + g.name() + "] " + e.name() + " " + spec.slotNames().getOrDefault(e.slot(), e.slot()) + plus));
            List<Component> lore = new ArrayList<>();
            double v = spec.amount(e, g, a.roll, a.enhance);
            double[] range = e.range(g);
            lore.add(Text.item("<white>" + e.name() + " <yellow>" + AccessorySpec.text(e, v)
                    + " <dark_gray>(" + AccessorySpec.text(e, range[0]) + " ~ " + AccessorySpec.text(e, range[1]) + ")"
                    + (a.perfect() ? " <gold><b>완벽</b>" : "")));
            if (a.enhance > 0) lore.add(Text.item("<gray>  └ 강화 +" + a.enhance + " <dark_gray>×" + AccessorySpec.num(spec.multiplier(a.enhance), 3)));
            int open = spec.subsOpen(g, lv);
            for (int i = 0; i < g.subs().size(); i++) {
                if (i < a.subs.size() && i < open) {
                    Accessory.Sub s = a.subs.get(i);
                    AccessorySpec.Effect se = spec.effects().get(s.effect());
                    if (se != null) lore.add(Text.item("<gray>부가: <white>" + se.name() + " " + AccessorySpec.text(se, spec.subAmount(se, s.roll()))));
                } else {
                    lore.add(Text.item("<dark_gray>부가: Lv" + g.subs().get(i)));
                }
            }
            double lo = spec.xpFor(lv), hi = spec.xpFor(lv + 1);
            int pct = lv >= spec.maxLevel() ? 100 : (int) Math.clamp((a.xp - lo) / Math.max(1, hi - lo) * 100, 0, 99);
            lore.add(Text.item("<gray>Lv <white>" + lv + "</white> / " + spec.maxLevel() + " " + bar(pct)
                    + " <dark_gray>· 강화 상한 +" + spec.enhanceCap(lv)));
            lore.add(Text.item("<gray>제련 남음 <white>" + Math.max(0, spec.refines() - a.refines) + "</white>/" + spec.refines()));
            m.lore(lore);
            m.setEnchantmentGlintOverride(a.enhance >= spec.steps().size() || a.perfect() && g.index() >= 2 ? true : null);
            m.setMaxStackSize(1);
            // the look is the effect's own, the same in every grade (the grade is the name's colour): economy:acc/<effect>
            m.setItemModel(new NamespacedKey("economy", "acc/" + a.effect));
        });
        return it;
    }

    private static String bar(int pct) {
        int full = Math.clamp(pct / 10, 0, 10);
        return "<green>" + "|".repeat(full) + "<dark_gray>" + "|".repeat(10 - full);
    }

    private void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) eco.mail().deliver(p.getUniqueId(), left, "accessory");
    }

    private void announce(Player p, Accessory a, ItemStack it) {
        AccessorySpec.Grade g = spec.grades().get(a.grade);
        if (g == null) return;
        if (a.perfect() && g.announcePerfect()) {
            Bukkit.broadcast(Lang.msg("accessory.announce-perfect", "player", Names.mini(p), "item", Items.mm(it.getItemMeta().itemName())));
        } else if (g.announce()) {
            Bukkit.broadcast(Lang.msg("accessory.announce", "player", Names.mini(p), "item", Items.mm(it.getItemMeta().itemName())));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Worn values
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        redraw(e.getPlayer());
        refresh(e.getPlayer());
    }

    /**
     * Draws again the name and description of every accessory a player has (inventory, ender chest, worn): an item
     * keeps only its data, so changed ranges, grade names or looks in accessories.yml show at once. Those on the old
     * per-slot items move to economy:accessory.
     */
    private void redraw(Player p) {
        redrawAll(p.getInventory());
        redrawAll(p.getEnderChest());
        ItemStack[] worn = store.worn(p.getUniqueId());
        for (int i = 0; i < worn.length; i++) {
            Accessory a = read(worn[i]);
            if (a != null) {
                worn[i] = write(migrate(worn[i]), a);
                store.touched(p.getUniqueId());
            }
        }
    }

    /** A chest (or any block's inventory) opened: its accessories are drawn again (and moved off the old items). */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof org.bukkit.block.Container
                || e.getInventory().getHolder() instanceof org.bukkit.block.DoubleChest) redrawAll(e.getInventory());
    }

    private void redrawAll(org.bukkit.inventory.Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            Accessory a = read(it);
            if (a != null) inv.setItem(i, write(migrate(it), a));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        values.remove(e.getPlayer().getUniqueId());
        clearAttributes(e.getPlayer());
        bags.forget(e.getPlayer());
    }

    /** Recomputes the effects a player wears and their attributes (mining and walking speed). */
    public void refresh(Player p) {
        recompute(p.getUniqueId());
        applyAttributes(p);
    }

    private void recompute(UUID player) {
        Map<String, Double> best = new HashMap<>(), subs = new HashMap<>();
        for (ItemStack it : store.worn(player)) {
            Accessory a = read(it);
            if (a == null) continue;
            AccessorySpec.Effect e = spec.effects().get(a.effect);
            AccessorySpec.Grade g = spec.grades().get(a.grade);
            if (e == null || g == null) continue;
            double v = spec.amount(e, g, a.roll, a.enhance);
            best.merge(a.effect, v, (x, y) -> Math.abs(y) > Math.abs(x) || x < 0 ? y : x);
            int open = spec.subsOpen(g, spec.levelFor(a.xp));
            for (int i = 0; i < Math.min(open, a.subs.size()); i++) {
                AccessorySpec.Effect se = spec.effects().get(a.subs.get(i).effect());
                if (se != null) subs.merge(se.id(), spec.subAmount(se, a.subs.get(i).roll()), Double::sum);
            }
        }
        Map<String, Double> out = new HashMap<>(best);
        for (Map.Entry<String, Double> s : subs.entrySet()) {
            AccessorySpec.Effect e = spec.effects().get(s.getKey());
            double v = out.getOrDefault(s.getKey(), 0.0);
            if (v < 0) continue;   // 원격 기계 anywhere stays anywhere
            v += s.getValue();
            out.put(s.getKey(), e.cap() > 0 ? Math.min(v, e.cap()) : v);
        }
        values.put(player, out);
        Player p = Bukkit.getPlayer(player);
        if (p != null) applyAttributes(p);
    }

    private void applyAttributes(Player p) {
        set(p, Attribute.BLOCK_BREAK_SPEED, MINING, value(p.getUniqueId(), "mining_speed"));
        set(p, Attribute.MOVEMENT_SPEED, MOVE, value(p.getUniqueId(), "move_speed"));
    }

    private static void set(Player p, Attribute attr, NamespacedKey key, double v) {
        AttributeInstance a = p.getAttribute(attr);
        if (a == null) return;
        a.removeModifier(key);
        if (v > 0) a.addTransientModifier(new AttributeModifier(key, v, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    private void clearAttributes(Player p) {
        set(p, Attribute.BLOCK_BREAK_SPEED, MINING, 0);
        set(p, Attribute.MOVEMENT_SPEED, MOVE, 0);
    }

    /** Distance walked (and driven) since the last look: 1 XP unit a block (walk / drive). */
    private final Map<UUID, Integer> lastWalk = new HashMap<>();
    private final Map<UUID, org.bukkit.Location> lastRide = new HashMap<>();

    private void walked() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int walk = p.getStatistic(org.bukkit.Statistic.WALK_ONE_CM) + p.getStatistic(org.bukkit.Statistic.SPRINT_ONE_CM);
            Integer last = lastWalk.put(p.getUniqueId(), walk);
            if (last != null) activity(p.getUniqueId(), "walk", Math.max(0, walk - last) / 100.0);
            // our vehicles are seats the player rides: the straight distance since the last look while riding
            org.bukkit.Location now = p.getLocation(), before = p.isInsideVehicle() ? lastRide.get(p.getUniqueId()) : null;
            if (before != null && before.getWorld() == now.getWorld()) activity(p.getUniqueId(), "drive", Math.min(2000, before.distance(now)));
            if (p.isInsideVehicle()) lastRide.put(p.getUniqueId(), now);
            else lastRide.remove(p.getUniqueId());
        }
    }

    private void leveled(UUID player, Accessory a, int before, int after) {
        AccessorySpec.Grade g = spec.grades().get(a.grade);
        // sub-options open at their levels: roll the new ones (a treasure's from the upper half of its range)
        int open = spec.subsOpen(g, after);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (a.subs.size() < open) a.subs.add(newSub(a, g, r));
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            AccessorySpec.Effect e = spec.effects().get(a.effect);
            p.sendMessage(Lang.msg("accessory.leveled", "effect", e.name(), "level", after));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        }
    }

    Accessory.Sub newSub(Accessory a, AccessorySpec.Grade g, ThreadLocalRandom r) {
        List<AccessorySpec.Effect> pool = new ArrayList<>(spec.subPool(a.effect));
        pool.removeIf(e -> a.subs.stream().anyMatch(s -> s.effect().equals(e.id())));
        if (pool.isEmpty()) pool = spec.subPool(a.effect);
        AccessorySpec.Effect pick = pool.get(r.nextInt(pool.size()));
        return new Accessory.Sub(pick.id(), g.subFloor() + (1 - g.subFloor()) * r.nextDouble());
    }

    // ------------------------------------------------------------------------------------------
    // Boxes, fragments, the 용궁 상자
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        ItemStack hand = e.getItem();
        if (hand == null) return;
        String key = Items.key(hand);
        Player p = e.getPlayer();
        if (key.equals(spec.boxItem())) {
            e.setCancelled(true);
            hand.subtract(1);
            openBox(p);
        } else if (key.equals(spec.chestItem())) {
            e.setCancelled(true);
            hand.subtract(1);
            openChest(p);
        } else if (key.equals(spec.fragmentItem())) {
            e.setCancelled(true);
            int have = Items.count(p.getInventory(), it -> spec.fragmentItem().equals(Items.key(it)));
            if (have < spec.fragments()) {
                HudModule.flash(p, Lang.mm("accessory.fragments-need", "have", have, "need", spec.fragments()));
                return;
            }
            Items.remove(p.getInventory(), it -> spec.fragmentItem().equals(Items.key(it)), spec.fragments());
            Items.create(spec.boxItem(), 1).ifPresent(b -> give(p, b));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 1f, 1.2f);
            HudModule.flash(p, Lang.mm("accessory.fragments-joined"));
        } else if (isAccessory(hand)) {
            e.setCancelled(true);
            open(p);
        }
    }

    private void openBox(Player p) {
        Accessory a = rollNew(null, spec.draw(true, 1 + value(p.getUniqueId(), "appraiser"), ThreadLocalRandom.current().nextDouble()));
        ItemStack it = create(a);
        give(p, it);
        activity(p.getUniqueId(), "box", 1);
        AccessorySpec.Grade g = spec.grades().get(a.grade);
        p.showTitle(net.kyori.adventure.title.Title.title(Text.item("<" + g.color() + ">[" + g.name() + "]"), it.getItemMeta().itemName()));
        p.playSound(p.getLocation(), g.index() >= 2 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
        announce(p, a, it);
    }

    private void openChest(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double total = spec.loot().stream().mapToDouble(AccessorySpec.Loot::weight).sum(), u = r.nextDouble() * total;
        activity(p.getUniqueId(), "box", 1);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.8f, 1.4f);
        for (AccessorySpec.Loot l : spec.loot()) {
            u -= l.weight();
            if (u > 0) continue;
            if (l.accessory()) {
                Accessory a = rollNew(null, spec.draw(false, 1 + value(p.getUniqueId(), "appraiser"), r.nextDouble()));
                ItemStack it = create(a);
                give(p, it);
                announce(p, a, it);
                HudModule.flash(p, Lang.mm("accessory.chest-got", "item", Items.mm(it.getItemMeta().itemName())));
            } else if (l.fish() != null) {
                var fish = eco.fishing().spec().species().values().stream().filter(s -> s.rarity().equals(l.fish())).toList();
                if (fish.isEmpty()) return;
                var f = fish.get(r.nextInt(fish.size()));
                Items.create(f.item(), 1).ifPresent(it -> {
                    give(p, it);
                    HudModule.flash(p, Lang.mm("accessory.chest-got", "item", Items.mmName(it)));
                });
            } else {
                int n = l.min() + r.nextInt(Math.max(1, l.max() - l.min() + 1));
                Items.create(l.item(), n).ifPresent(it -> {
                    give(p, it);
                    HudModule.flash(p, Lang.mm("accessory.chest-got", "item", Items.mmName(it) + " ×" + n));
                });
            }
            return;
        }
    }

    // ------------------------------------------------------------------------------------------
    // /장신구: the six slots
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, MENU, new WornMenu());
    }

    private final class WornMenu implements MenuHandler {

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("count", String.valueOf(java.util.Arrays.stream(store.worn(v.player().getUniqueId())).filter(java.util.Objects::nonNull).count()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            ItemStack[] worn = store.worn(v.player().getUniqueId());
            for (int i = 0; i < spec.slots().size(); i++) {
                if (worn[i] != null) {
                    ItemStack shown = worn[i].clone();
                    shown.editMeta(m -> {
                        List<Component> lore = new ArrayList<>(m.hasLore() ? m.lore() : List.of());
                        lore.add(Component.empty());
                        lore.add(Lang.item("accessory.worn-take-off"));
                        m.lore(lore);
                    });
                    out.add(shown);
                } else {
                    String slot = spec.slots().get(i);
                    // an empty slot shows nothing: the background's cushion and its name say what goes there
                    ItemStack empty = new ItemStack(Material.PAPER);
                    empty.editMeta(m -> {
                        m.setItemModel(new NamespacedKey("stsv", "gui/blank"));
                        m.itemName(Text.item("<dark_gray>" + spec.slotNames().getOrDefault(slot, slot) + " 칸 (비어 있음)"));
                        m.lore(Lang.items("accessory.slot-empty-lore"));
                    });
                    out.add(empty);
                }
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            Player p = v.player();
            ItemStack[] worn = store.worn(p.getUniqueId());
            if (index < 0 || index >= worn.length || worn[index] == null) return;
            Accessory a = read(worn[index]);
            if (a != null && "bag".equals(a.effect) && !store.bagEmpty(a.serial)) {
                HudModule.flash(p, Lang.mm("accessory.bag-not-empty"));
                return;
            }
            if (p.getInventory().firstEmpty() < 0) {
                HudModule.flash(p, Lang.mm("accessory.inventory-full"));
                return;
            }
            ItemStack it = worn[index];
            store.setWorn(p.getUniqueId(), index, null);
            p.getInventory().addItem(it);
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.6f, 1.2f);
            refresh(p);
            store.flush();
            v.refresh();
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int slot, ClickType click) {
            Player p = v.player();
            ItemStack it = p.getInventory().getItem(slot);
            Accessory a = read(it);
            if (a == null) return;
            AccessorySpec.Effect e = spec.effects().get(a.effect);
            if (e == null) return;
            if (wornSerial(a.serial) != null) {
                eco.getLogger().warning("장신구 복사 의심: " + a.serial + " (" + p.getName() + ")");
                HudModule.flash(p, Lang.mm("accessory.duplicate"));
                return;
            }
            ItemStack[] worn = store.worn(p.getUniqueId());
            int target = -1;
            for (int i = 0; i < spec.slots().size(); i++) {
                if (!spec.slots().get(i).equals(e.slot())) continue;
                if (worn[i] == null) {
                    target = i;
                    break;
                }
                if (target < 0) target = i;   // all full: swap with the first of that kind
            }
            if (target < 0) return;
            ItemStack old = worn[target];
            Accessory oa = read(old);
            if (oa != null && "bag".equals(oa.effect) && !store.bagEmpty(oa.serial)) {
                HudModule.flash(p, Lang.mm("accessory.bag-not-empty"));
                return;
            }
            p.getInventory().setItem(slot, old);
            store.setWorn(p.getUniqueId(), target, it);
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GOLD, 0.7f, 1.3f);
            refresh(p);
            store.flush();
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "accessory:workshop" -> Bukkit.getScheduler().runTask(eco, () -> openWorkshop(p));
                case "accessory:effects" -> Bukkit.getScheduler().runTask(eco, () -> openEffects(p));
                case "accessory:bag" -> Bukkit.getScheduler().runTask(eco, () -> bags.open(p, 0));
                default -> { }
            }
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null || b.key() != 'I') return base;
            UUID u = v.player().getUniqueId();
            List<Component> lore = new ArrayList<>();
            Map<String, Double> m = values.getOrDefault(u, Map.of());
            for (AccessorySpec.Effect e : spec.effects().values()) {
                Double val = m.get(e.id());
                if (val != null && val != 0) lore.add(Text.item("<gray>" + e.name() + " <white>" + AccessorySpec.text(e, val)));
            }
            if (lore.isEmpty()) lore.addAll(Lang.items("accessory.info-none"));
            base.editMeta(meta -> meta.lore(lore));
            return base;
        }
    }

    // ------------------------------------------------------------------------------------------
    // /순위 장신구: everyone's worn accessories, scored (AccessorySpec.score), highest first
    // ------------------------------------------------------------------------------------------

    public record Scored(UUID player, double score, List<Accessory> worn) {}

    /** Everyone who wears at least one accessory, by score (offline players too: what they wore when they left). */
    public List<Scored> ranking() {
        List<Scored> out = new ArrayList<>();
        for (Map.Entry<UUID, ItemStack[]> e : store.allWorn().entrySet()) {
            List<Accessory> worn = new ArrayList<>();
            double score = 0;
            for (ItemStack it : e.getValue()) {
                Accessory a = read(it);
                if (a == null) continue;
                worn.add(a);
                score += spec.score(a.grade, a.roll, a.enhance);
            }
            if (!worn.isEmpty()) out.add(new Scored(e.getKey(), score, worn));
        }
        out.sort(java.util.Comparator.comparingDouble(Scored::score).reversed());
        return out;
    }

    /** The chat lines of /순위 장신구: the top 10 (each name shows what they wear on hover) and where I stand. */
    public List<Component> rankingLines(UUID me) {
        List<Scored> all = ranking();
        List<Component> out = new ArrayList<>();
        out.add(Lang.msg("rank.acc-header", "count", all.size()));
        if (all.isEmpty()) {
            out.add(Lang.mm("rank.acc-none"));
            return out;
        }
        for (int i = 0; i < all.size(); i++) {
            Scored s = all.get(i);
            boolean mine = s.player().equals(me);
            if (i >= 10 && !mine) continue;
            String key = i >= 10 ? "rank.acc-mine" : mine ? "rank.acc-line-me" : "rank.acc-line";
            Accessory best = s.worn().stream().max(java.util.Comparator.comparingDouble(a -> spec.score(a.grade, a.roll, a.enhance))).orElseThrow();
            out.add(Lang.mm(key, "rank", i + 1, "player", dev.stsv.economy.util.Players.name(s.player()),
                    "score", AccessorySpec.num(s.score(), 1), "best", shortName(best), "count", s.worn().size())
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(wornList(s))));
            if (i >= 10) break;
        }
        return out;
    }

    /** "<color>[S] 가방 허리띠 +7" */
    private String shortName(Accessory a) {
        AccessorySpec.Effect e = spec.effects().get(a.effect);
        AccessorySpec.Grade g = spec.grades().get(a.grade);
        if (e == null || g == null) return "?";
        return "<" + g.color() + ">[" + g.name() + "] " + e.name() + " " + spec.slotNames().getOrDefault(e.slot(), e.slot())
                + (a.enhance > 0 ? " +" + a.enhance : "") + (a.perfect() ? " <gold>완벽" : "");
    }

    private Component wornList(Scored s) {
        Component c = Lang.mm("rank.acc-hover-header", "player", dev.stsv.economy.util.Players.name(s.player()));
        for (Accessory a : s.worn()) {
            c = c.append(Component.newline()).append(Text.item(shortName(a) + " <dark_gray>· "
                    + AccessorySpec.num(spec.score(a.grade, a.roll, a.enhance), 1) + "점"));
        }
        return c;
    }

    /** Who wears an accessory with this serial (a copy check), or null. */
    UUID wornSerial(UUID serial) {
        for (Map.Entry<UUID, ItemStack[]> e : store.allWorn().entrySet()) {
            for (ItemStack it : e.getValue()) {
                Accessory a = read(it);
                if (a != null && a.serial.equals(serial)) return e.getKey();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // The workshop: enhance, refine, reroll (an accessory in the inventory, not worn)
    // ------------------------------------------------------------------------------------------

    // ------------------------------------------------------------------------------------------
    // The effects screen (/장신구 효과): every effect, a row a slot kind, what it does and its numbers
    // ------------------------------------------------------------------------------------------

    public void openEffects(Player p) {
        core.menus().open(p, EFFECTS, new EffectsMenu());
    }

    private final class EffectsMenu implements MenuHandler {

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID u = v.player().getUniqueId();
            List<ItemStack> out = new ArrayList<>();
            for (String slot : new java.util.LinkedHashSet<>(spec.slots())) {
                for (AccessorySpec.Effect e : spec.effectsOf(slot)) out.add(effectCard(e, values.getOrDefault(u, Map.of()).get(e.id())));
            }
            return out;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("accessory:back")) Bukkit.getScheduler().runTask(eco, () -> open(v.player()));
        }
    }

    /** One effect as the effects screen shows it: its look, what it does, every grade's range (before enhancing), the top, mine. */
    private ItemStack effectCard(AccessorySpec.Effect e, Double mine) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            m.setItemModel(new NamespacedKey("economy", "acc/" + e.id()));
            m.itemName(Text.item("<#f0e2b0>" + e.name() + " <dark_gray>· " + spec.slotNames().getOrDefault(e.slot(), e.slot())));
            List<Component> lore = new ArrayList<>();
            if (!e.desc().isEmpty()) lore.add(Text.item("<white>" + e.desc()));
            lore.add(Component.empty());
            for (AccessorySpec.Grade g : spec.grades().values()) {
                double lo = spec.amount(e, g, 0, 0), hi = spec.amount(e, g, 1, 0);
                String range = lo == hi ? AccessorySpec.text(e, lo) : AccessorySpec.text(e, lo) + " ~ " + AccessorySpec.text(e, hi);
                lore.add(Text.item("<" + g.color() + ">" + g.name() + " <gray>" + range));
            }
            AccessorySpec.Grade top = spec.grades().get("treasure");
            if (e.enhances() && top != null) {
                lore.add(Text.item("<dark_gray>S 최상위 +" + spec.steps().size() + ": <gold>" + AccessorySpec.text(e, spec.amount(e, top, 1, spec.steps().size()))));
            } else if (!e.enhances()) {
                lore.add(Text.item("<dark_gray>강화해도 그대로"));
            }
            if (!e.sub()) lore.add(Text.item("<dark_gray>부가 옵션으로는 안 나옴"));
            if (mine != null && mine != 0) lore.add(Text.item("<green>지금 내 효과 " + AccessorySpec.text(e, mine)));
            m.lore(lore);
        });
        return it;
    }

    public void openWorkshop(Player p) {
        core.menus().open(p, WORKSHOP, new Workshop());
    }

    private final class Workshop implements MenuHandler {
        private int slot = -1;
        private UUID serial;
        private boolean useCharm = true;
        private int subPick;
        private String armed;
        private long armedAt;
        // the last outcome, shown on the button that was clicked (its tooltip sits under the cursor; a title or the
        // action bar would be hidden behind the screen) for RESULT_MS
        private String resultAction;
        private Component resultName;
        private List<Component> resultLore = List.of();
        private boolean resultGood;
        private long resultAt;
        private static final long RESULT_MS = 4000;

        private void result(MenuView v, String action, Component name, List<Component> lore, boolean good) {
            resultAction = action;
            resultName = name;
            resultLore = lore;
            resultGood = good;
            long at = resultAt = now();
            // put the button back afterwards (unless a newer outcome replaced this one, or the screen was closed)
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (resultAt == at && v.player().isOnline() && v.player().getOpenInventory().getTopInventory().getHolder() == v) v.refresh();
            }, RESULT_MS / 50 + 1);
        }

        private Accessory current(Player p) {
            if (slot < 0) return null;
            Accessory a = read(p.getInventory().getItem(slot));
            return a != null && a.serial.equals(serial) ? a : null;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Accessory a = current(v.player());
            Map<String, String> m = new HashMap<>();
            m.put("charm", useCharm ? "켜짐" : "꺼짐");
            m.put("state", a == null ? "-" : "+" + a.enhance);
            return m;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Accessory a = current(v.player());
            if (a == null) return List.of();
            return List.of(v.player().getInventory().getItem(slot).clone());
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int clicked, ClickType click) {
            Accessory a = read(v.player().getInventory().getItem(clicked));
            if (a == null) return;
            slot = clicked;
            serial = a.serial;
            subPick = 0;
            armed = null;
            v.player().playSound(v.player().getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
            v.refresh();
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null) return null;
            if (b.action().equals(resultAction) && now() - resultAt < RESULT_MS) {
                base.editMeta(meta -> {
                    meta.itemName(resultName);
                    meta.lore(resultLore);
                    meta.setEnchantmentGlintOverride(resultGood);
                });
                return base;
            }
            Player p = v.player();
            Accessory a = current(p);
            AccessorySpec.Grade g = a == null ? null : spec.grades().get(a.grade);
            AccessorySpec.Effect e = a == null ? null : spec.effects().get(a.effect);
            List<Component> lore = new ArrayList<>();
            switch (b.action()) {
                case "accessory:enhance" -> {
                    if (a == null) lore.addAll(Lang.items("accessory.pick-first"));
                    else if (!e.enhances()) lore.add(Lang.item("accessory.no-enhance"));
                    else if (a.enhance >= spec.steps().size()) lore.add(Lang.item("accessory.enhance-max"));
                    else {
                        int to = a.enhance + 1;
                        AccessorySpec.Step st = spec.steps().get(to - 1);
                        double now = spec.amount(e, g, a.roll, a.enhance), next = spec.amount(e, g, a.roll, to);
                        lore.add(Lang.item("accessory.enhance-line", "to", to, "now", AccessorySpec.text(e, now), "next", AccessorySpec.text(e, next)));
                        lore.add(Lang.item("accessory.cost-line", "cost", EconomyModule.format(spec.enhanceCost(g, to)), "stones", spec.enhanceStones(g, to)));
                        lore.add(Lang.item("accessory.chance-line", "chance", Math.round(st.chance() * 100),
                                "drop", to >= spec.dropFrom() ? Lang.raw("accessory.drop-yes") : Lang.raw("accessory.drop-no")));
                        int cap = spec.enhanceCap(spec.levelFor(a.xp));
                        if (to > cap) lore.add(Lang.item("accessory.enhance-level-cap", "cap", cap));
                        if (armed("accessory:enhance")) lore.add(Lang.item("accessory.confirm"));
                    }
                }
                case "accessory:refine" -> {
                    if (a == null) lore.addAll(Lang.items("accessory.pick-first"));
                    else {
                        lore.add(Lang.item("accessory.refine-line", "left", Math.max(0, spec.refines() - a.refines), "all", spec.refines()));
                        lore.add(Lang.item("accessory.cost-line2", "cost", EconomyModule.format(g.refine()), "items", itemsText(g.refineItems())));
                        if (armed("accessory:refine")) lore.add(Lang.item("accessory.confirm"));
                    }
                }
                case "accessory:reroll" -> {
                    if (a == null) lore.addAll(Lang.items("accessory.pick-first"));
                    else {
                        int open = spec.subsOpen(g, spec.levelFor(a.xp));
                        if (open == 0 || a.subs.isEmpty()) lore.add(Lang.item("accessory.reroll-none"));
                        else {
                            int pick = Math.min(subPick, Math.min(open, a.subs.size()) - 1);
                            AccessorySpec.Effect se = spec.effects().get(a.subs.get(pick).effect());
                            lore.add(Lang.item("accessory.reroll-line", "n", pick + 1, "effect", se == null ? "?" : se.name()));
                            lore.add(Lang.item("accessory.cost-line2", "cost", EconomyModule.format(spec.rerollCost(g, a.rerolls)), "items", "-"));
                            lore.add(Lang.item("accessory.reroll-pick"));
                            if (armed("accessory:reroll")) lore.add(Lang.item("accessory.confirm"));
                        }
                    }
                }
                case "accessory:charm" -> lore.add(Lang.item("accessory.charm-line", "state", useCharm ? "켜짐" : "꺼짐",
                        "have", Items.count(p.getInventory(), it -> spec.charm().equals(Items.key(it)))));
                default -> {
                    return base;
                }
            }
            base.editMeta(m -> m.lore(lore));
            return base;
        }

        private boolean armed(String action) {
            return action.equals(armed) && now() - armedAt < 5000;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            if (action.equals("accessory:charm")) {
                useCharm = !useCharm;
                v.refresh();
                return;
            }
            if (action.equals("accessory:back")) {
                Bukkit.getScheduler().runTask(eco, () -> open(p));
                return;
            }
            Accessory a = current(p);
            if (a == null) {
                result(v, action, Lang.mm("accessory.pick-first-flash"), List.of(), false);
                v.refresh();
                return;
            }
            if (action.equals("accessory:reroll") && click.isRightClick()) {
                subPick++;
                int open = Math.min(spec.subsOpen(spec.grades().get(a.grade), spec.levelFor(a.xp)), a.subs.size());
                if (open > 0) subPick %= open;
                v.refresh();
                return;
            }
            if (!armed(action)) {
                armed = action;
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = null;
            String no = switch (action) {
                case "accessory:enhance" -> enhance(v, p, a);
                case "accessory:refine" -> refine(v, p, a);
                case "accessory:reroll" -> reroll(v, p, a, subPick);
                default -> null;
            };
            if (no != null) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
                result(v, action, Lang.mm(no), List.of(), false);
            }
            v.refresh();
        }

        private String enhance(MenuView v, Player p, Accessory a) {
            AccessorySpec.Grade g = spec.grades().get(a.grade);
            AccessorySpec.Effect e = spec.effects().get(a.effect);
            if (!e.enhances()) return "accessory.no-enhance";
            if (a.enhance >= spec.steps().size()) return "accessory.enhance-max";
            int to = a.enhance + 1;
            if (to > spec.enhanceCap(spec.levelFor(a.xp))) return "accessory.enhance-level";
            long cost = spec.enhanceCost(g, to);
            int stones = spec.enhanceStones(g, to);
            if (Items.count(p.getInventory(), it -> spec.stone().equals(Items.key(it))) < stones) return "accessory.no-stones";
            if (!core.economy().withdraw(p.getUniqueId(), cost, "acc_enhance")) return "accessory.no-money";
            Items.remove(p.getInventory(), it -> spec.stone().equals(Items.key(it)), stones);
            activity(p.getUniqueId(), "refine", 1);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (r.nextDouble() < spec.steps().get(to - 1).chance()) {
                a.enhance = to;
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
                result(v, "accessory:enhance", Lang.mm("accessory.enhance-ok-title", "to", to), List.of(), true);
                if (to >= spec.steps().size()) Bukkit.broadcast(Lang.msg("accessory.announce-max", "player", Names.mini(p),
                        "item", Items.mm(write(p.getInventory().getItem(slot), a).getItemMeta().itemName())));
            } else {
                String result = "accessory.enhance-fail";
                if (to >= spec.dropFrom()) {
                    if (roll(p.getUniqueId(), "blessing")) result = "accessory.enhance-blessed";
                    else if (useCharm && Items.count(p.getInventory(), it -> spec.charm().equals(Items.key(it))) > 0) {
                        Items.remove(p.getInventory(), it -> spec.charm().equals(Items.key(it)), 1);
                        result = "accessory.enhance-charmed";
                    } else {
                        a.enhance = Math.max(0, a.enhance - 1);
                        result = "accessory.enhance-dropped";
                    }
                }
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 0.8f);
                result(v, "accessory:enhance", Lang.mm("accessory.enhance-fail-title"), List.of(Lang.mm(result)), false);
            }
            p.getInventory().setItem(slot, write(p.getInventory().getItem(slot), a));
            return null;
        }

        private String refine(MenuView v, Player p, Accessory a) {
            AccessorySpec.Grade g = spec.grades().get(a.grade);
            if (a.refines >= spec.refines()) return "accessory.refine-none";
            for (Map.Entry<String, Integer> in : g.refineItems().entrySet()) {
                if (Items.count(p.getInventory(), it -> in.getKey().equals(Items.key(it))) < in.getValue()) return "accessory.no-items";
            }
            if (!core.economy().withdraw(p.getUniqueId(), g.refine(), "acc_refine")) return "accessory.no-money";
            for (Map.Entry<String, Integer> in : g.refineItems().entrySet()) Items.remove(p.getInventory(), it -> in.getKey().equals(Items.key(it)), in.getValue());
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double roll = r.nextDouble();
            boolean twice = roll(p.getUniqueId(), "refine_hand");
            if (twice) roll = Math.max(roll, r.nextDouble());
            a.roll = roll;
            a.refines++;
            activity(p.getUniqueId(), "refine", 1);
            ItemStack it = write(p.getInventory().getItem(slot), a);
            p.getInventory().setItem(slot, it);
            p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.7f, 1.2f);
            AccessorySpec.Effect e = spec.effects().get(a.effect);
            result(v, "accessory:refine", Lang.mm(twice ? "accessory.refined-twice" : "accessory.refined"),
                    List.of(Lang.mm("accessory.refined-value", "value", AccessorySpec.text(e, spec.amount(e, g, a.roll, a.enhance)))), true);
            announce(p, a, it);
            return null;
        }

        private String reroll(MenuView v, Player p, Accessory a, int pick) {
            AccessorySpec.Grade g = spec.grades().get(a.grade);
            int open = Math.min(spec.subsOpen(g, spec.levelFor(a.xp)), a.subs.size());
            if (open == 0) return "accessory.reroll-none";
            int i = Math.min(pick, open - 1);
            if (!core.economy().withdraw(p.getUniqueId(), spec.rerollCost(g, a.rerolls), "acc_reroll")) return "accessory.no-money";
            Accessory.Sub keep = a.subs.remove(i);
            a.subs.add(i, newSub(a, g, ThreadLocalRandom.current()));
            if (a.subs.get(i).effect().equals(keep.effect()) && spec.subPool(a.effect).size() > 1) a.subs.set(i, newSub(a, g, ThreadLocalRandom.current()));
            a.rerolls++;
            activity(p.getUniqueId(), "refine", 1);
            p.getInventory().setItem(slot, write(p.getInventory().getItem(slot), a));
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
            AccessorySpec.Effect se = spec.effects().get(a.subs.get(i).effect());
            result(v, "accessory:reroll", Lang.mm("accessory.rerolled"), se == null ? List.of()
                    : List.of(Lang.mm("accessory.rerolled-line", "effect", se.name(), "value", AccessorySpec.text(se, spec.subAmount(se, a.subs.get(i).roll())))), true);
            return null;
        }
    }

    private static String itemsText(Map<String, Integer> items) {
        if (items.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : items.entrySet()) parts.add(Items.mmName(e.getKey()) + " " + e.getValue());
        return String.join(" · ", parts);
    }

    /** Admin: an accessory to a player (effect id, grade id, roll 0..1 or random). */
    public ItemStack adminGive(Player p, String effect, String grade, Double roll) {
        AccessorySpec.Grade g = spec.grades().get(grade);
        if (g == null || !spec.effects().containsKey(effect)) return null;
        Accessory a = rollNew(effect, g);
        if (roll != null) a.roll = Math.clamp(roll, 0, 1);
        ItemStack it = create(a);
        give(p, it);
        return it;
    }

    Bag bags() {
        return bags;
    }

    EcoPlugin eco() {
        return eco;
    }

    public void openBag(Player p) {
        bags.open(p, 0);
    }

    @EventHandler
    public void onKill(org.bukkit.event.entity.EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        // a monster killed by hand (not a spawner's: those are farmed)
        if (killer == null || !(e.getEntity() instanceof org.bukkit.entity.Monster) || e.getEntity().fromMobSpawner()) return;
        drop(killer, "kill");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) activity(p.getUniqueId(), "pickup", 1);
    }

    /** 장신구 원격 기계 (/원격기계): the outputs of my machines in range, into the inventory or the bag. Not near a dock or terminal. */
    public void remote(Player p) {
        double range = value(p.getUniqueId(), "remote_machine");
        if (range == 0) {
            HudModule.flash(p, Lang.mm("accessory.remote-none"));
            return;
        }
        var docks = eco.docks();
        if (docks != null) {
            List<dev.stsv.economy.dock.Dock> all = new ArrayList<>(docks.docks());
            all.addAll(docks.terminals());
            for (var d : all) {
                if (d.world().equals(p.getWorld().getName()) && Math.hypot(d.x() - p.getLocation().getX(), d.z() - p.getLocation().getZ()) < 200) {
                    HudModule.flash(p, Lang.mm("accessory.remote-dock"));
                    return;
                }
            }
        }
        int n = eco.machines().collectRemote(p, range, it -> bags.store(p, it));
        activity(p.getUniqueId(), "machine", n);
        HudModule.flash(p, Lang.mm(n > 0 ? "accessory.remote-done" : "accessory.remote-empty", "count", n,
                "range", range < 0 ? "어디서나" : Math.round(range) + "칸"));
    }
}
