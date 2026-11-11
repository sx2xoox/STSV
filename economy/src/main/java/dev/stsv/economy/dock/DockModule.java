package dev.stsv.economy.dock;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.vehicle.Hauled;
import dev.stsv.core.vehicle.JigeModule;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.road.ChunkQueue;
import dev.stsv.economy.road.RoadModule;
import dev.stsv.economy.road.RoadOrigin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 하역장 (cargo docks) and 적재 무게 (README "하역장", "적재 무게").
 *
 * <ul>
 *   <li><b>Weights</b>: weights.yml ({@link WeightTable}) gives the core a weight for every stack (shulker boxes and bundles
 *       with what is in them): vehicles and the 지게 take no more than their payload. Finished goods ({@link DockGoods})
 *       weigh what weights.yml says, everything else raw (a stack of 64 = 1): slots limit raw hauls, weight the dock trade.</li>
 *   <li><b>Docks</b>: built by command ({@link DockLayout}, {@link DockBuilder}), kept in the database ({@link DockStore}),
 *       their ground claimed as the server's land. At a dock, sneak + right-click on a vehicle's bed whose vehicle (any unit
 *       of a train, a boat at the pier) stands in the unloading zone, or on the 하역 창구, or sneak + right-click with an empty
 *       hand wearing a 지게 in the zone, opens the 하역장 판매 menu.</li>
 *   <li><b>Selling</b>: only finished goods ({@link DockGoods}) in the vehicle's bed, at 90 % of their base price, and only if
 *       the load changed at least docks.min-distance blocks away and the vehicle drove that far since. The money goes to
 *       the vehicle's owner; who may sell = who may open the bed. Every sale is logged.</li>
 * </ul>
 */
public final class DockModule extends EcoModule {

    /** docks.* in config.yml */
    record Rules(double minDistance, double ratio, int buildPerTick, int scoutStep, int scoutConcurrent) {}

    private DockStore store;
    private Rules rules;
    private WeightTable weights = WeightTable.NONE;
    private DockGoods goods;
    private final Map<String, Dock> docks = new LinkedHashMap<>();
    private final Map<String, Double> unitWeight = new HashMap<>();
    private DockBuilder building;
    private Scout scout;

    public DockModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-docks";
    }

    @Override
    protected void start() {
        store = new DockStore(core.db());
        store.init();
        for (Dock d : store.all()) docks.put(key(d.name()), d);
        load();
        if (core.vehicles() != null) {
            core.vehicles().cargoWeight(this::weigh);
            core.vehicles().cargoClick(this::bedClicked);
        }
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "하역장", "설치 <이름> [육지|항구] | 목록 | 제거 <이름> | 다시꾸미기 <이름> | 후보 <거리> | 기록 [이름]",
                "하역장: 지금 자리에 짓기(보는 방향) / 목록 / 기록 지우기 / 땅은 그대로 장식(컨테이너·모델·블록)만 다시 / 국도 기준점(없으면 건축월드 스폰)에서 8방향으로 후보지 살피기 / 판매 기록",
                this::command,
                (s, a) -> a.length == 1 ? List.of("설치", "목록", "제거", "다시꾸미기", "후보", "기록")
                        : a.length == 2 && (a[0].equals("제거") || a[0].equals("기록") || a[0].equals("다시꾸미기")) ? new ArrayList<>(names())
                        : a.length == 2 && a[0].equals("후보") ? List.of("7000", "5000")
                        : a.length == 3 && a[0].equals("설치") ? List.of("육지", "항구") : List.of());
        every(1L, () -> {
            if (scout != null) scout.tick();
        });
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        if (core.vehicles() != null) {
            core.vehicles().cargoWeight(null);
            core.vehicles().cargoClick(null);
        }
        if (building != null) building.cancel();
        if (scout != null) scout.queue.stop();
    }

    private void load() {
        rules = new Rules(eco.getConfig().getDouble("docks.min-distance"), eco.getConfig().getDouble("docks.price-ratio"),
                eco.getConfig().getInt("docks.build-per-tick"), Math.max(16, eco.getConfig().getInt("docks.scout-step")),
                eco.getConfig().getInt("docks.scout-concurrent"));
        goods = DockGoods.of(eco.machines().specs(), eco.shop().catalog(), rules.ratio());
        List<String> problems = new ArrayList<>();
        // only what the dock buys has a real weight; everything else weighs weights.yml raw (README "적재 무게")
        weights = WeightTable.parse(eco.data("weights.yml"), problems).withGoods(goods.finished());
        problems.forEach(p -> eco.getLogger().warning("[weights.yml] " + p));
        unitWeight.clear();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public java.util.Collection<String> names() {
        return docks.values().stream().map(Dock::name).toList();
    }

    public Dock dock(String name) {
        return docks.get(key(name));
    }

    public java.util.Collection<Dock> docks() {
        return docks.values();
    }

    public DockGoods goods() {
        return goods;
    }

    // ------------------------------------------------------------------------------------------
    // 적재 무게
    // ------------------------------------------------------------------------------------------

    /** What a stack weighs: its count x one item's weight, plus what a shulker box or bundle holds. */
    double weigh(ItemStack it) {
        if (it == null || it.isEmpty()) return 0;
        String id = Items.key(it);
        double unit = unitWeight.computeIfAbsent(id, k -> weights.weight(k, v -> {
            Material m = Material.matchMaterial(v);
            return m != null && m.isBlock();
        }));
        double inner = 0;
        if (it.hasItemMeta()) {
            var meta = it.getItemMeta();
            if (meta instanceof BlockStateMeta b && b.hasBlockState() && b.getBlockState() instanceof ShulkerBox box)
                for (ItemStack in : box.getInventory().getContents()) inner += weigh(in);
            if (meta instanceof BundleMeta b) for (ItemStack in : b.getItems()) inner += weigh(in);
        }
        return unit * it.getAmount() + inner;
    }

    // ------------------------------------------------------------------------------------------
    // Where a load is: in a dock's zone?
    // ------------------------------------------------------------------------------------------

    /** The dock whose zone holds any unit of this load, or null. */
    Dock dockOf(Hauled h) {
        for (Dock d : docks.values()) if (d.anyInZone(h.world().getName(), h.points())) return d;
        return null;
    }

    /** A sneak + right-click on a bed (or a 지게 opened) inside a dock's zone: the sale menu instead. */
    private boolean bedClicked(Player p, Hauled h) {
        Dock d = dockOf(h);
        if (d == null) return false;
        open(p, d, h);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCounter(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        for (Dock d : docks.values()) {
            int[] c = d.counter();
            if (!d.world().equals(b.getWorld().getName()) || b.getX() != c[0] || b.getY() != c[1] || b.getZ() != c[2]) continue;
            e.setCancelled(true);
            counter(e.getPlayer(), d);
            return;
        }
    }

    /** The 하역 창구: the player's 지게 if they stand in the zone with one, else the nearest vehicle in the zone they may use. */
    private void counter(Player p, Dock d) {
        Hauled jige = jige(p);
        if (jige != null && dockOf(jige) == d && !empty(jige)) {
            open(p, d, jige);
            return;
        }
        Hauled best = null;
        double bestD = Double.MAX_VALUE;
        double[] c = d.centre();
        if (core.vehicles() != null) {
            for (Hauled h : core.vehicles().hauledNear(new Location(p.getWorld(), c[0], c[1], c[2]), 64)) {
                if (dockOf(h) != d || empty(h) || !core.vehicles().mayUse(p, h.id())) continue;
                double dd = p.getLocation().distanceSquared(new Location(p.getWorld(), h.centre()[0], h.centre()[1], h.centre()[2]));
                if (dd < bestD) {
                    bestD = dd;
                    best = h;
                }
            }
        }
        if (best == null) {
            HudModule.flash(p, Lang.mm("dock.counter-nothing"));
            return;
        }
        open(p, d, best);
    }

    private static boolean empty(Hauled h) {
        for (ItemStack it : h.contents()) if (it != null && !it.isEmpty()) return false;
        return true;
    }

    private JigeModule jigeModule() {
        return core.modules().get("jige", JigeModule.class).orElse(null);
    }

    private Hauled jige(Player p) {
        JigeModule j = jigeModule();
        return j == null ? null : j.hauled(p);
    }

    /** The load again as it is now (a vehicle by id, a 지게 on this player's back). */
    private Hauled again(Player p, long id) {
        if (id < 0) {
            Hauled h = jige(p);
            return h != null && h.id() == id ? h : null;
        }
        return core.vehicles() == null ? null : core.vehicles().hauled(id);
    }

    private boolean mayUse(Player p, Hauled h) {
        return h.id() < 0 ? h.owner().equals(p.getUniqueId()) : core.vehicles().mayUse(p, h.id());
    }

    // ------------------------------------------------------------------------------------------
    // 하역장 판매 (the menu)
    // ------------------------------------------------------------------------------------------

    /** Why a load may not be sold here (a message key), or null. */
    String refusal(Dock d, Hauled h) {
        if (h.moving()) return "dock.why-moving";
        double[] c = d.centre();
        double straight = h.trip().straight(d.world(), c[0], c[2]);
        if (!Double.isFinite(straight) || straight < rules.minDistance()) return "dock.why-near";
        if (h.trip().odometer() < rules.minDistance()) return "dock.why-odometer";
        return null;
    }

    DockGoods.Plan plan(Hauled h) {
        List<DockGoods.Slot> bed = new ArrayList<>();
        ItemStack[] c = h.contents();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null || c[i].isEmpty()) continue;
            String id = Items.key(c[i]);
            bed.add(new DockGoods.Slot(i, id, c[i].getAmount(), Items.plain(c[i], id)));
        }
        return goods.plan(bed);
    }

    /** The menu: what goes and for how much, the trip, a confirm button (or why not), the bed. */
    final class Sale implements InventoryHolder {
        final Dock dock;
        final long vehicle, version;
        final DockGoods.Plan plan;
        final boolean ok;
        Inventory inventory;

        Sale(Dock dock, Hauled h, DockGoods.Plan plan, boolean ok) {
            this.dock = dock;
            this.vehicle = h.id();
            this.version = h.version();
            this.plan = plan;
            this.ok = ok;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    static final int LIST = 36, INFO = 45, CANCEL = 47, BED = 49, CONFIRM = 53;

    void open(Player p, Dock d, Hauled h) {
        if (!mayUse(p, h)) {
            HudModule.flash(p, Lang.mm("dock.not-yours", "owner", h.ownerName()));
            return;
        }
        DockGoods.Plan plan = plan(h);
        String why = refusal(d, h);
        boolean ok = why == null && !plan.empty();
        Sale s = new Sale(d, h, plan, ok);
        Inventory inv = Bukkit.createInventory(s, 54, Lang.mm("dock.title"));
        s.inventory = inv;
        int slot = 0;
        for (DockGoods.Line l : plan.lines()) {
            if (slot >= LIST) break;
            ItemStack icon = Items.create(l.id(), 1).orElse(new ItemStack(Material.PAPER));
            icon.setAmount(Math.clamp(l.count(), 1, icon.getMaxStackSize()));
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.item("dock.line", "count", l.count(), "unit", String.format(Locale.ROOT, "%.1f", l.unit()),
                    "total", EconomyModule.format((long) Math.floor(l.total()))));
            icon.editMeta(m -> m.lore(lore));
            inv.setItem(slot++, icon);
        }
        for (int i = LIST; i < 54; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, " "));
        double[] c = d.centre();
        double straight = h.trip().straight(d.world(), c[0], c[2]);
        ItemStack info = pane(Material.MAP, Lang.raw("dock.info-title", "vehicle", h.name(), "owner", h.ownerName()));
        info.editMeta(m -> m.lore(List.of(
                Lang.item("dock.info-distance", "distance", Double.isFinite(straight) ? Math.round(straight) : "-", "min", Math.round(rules.minDistance())),
                Lang.item("dock.info-odometer", "odometer", Math.round(h.trip().odometer()), "min", Math.round(rules.minDistance())),
                Lang.item("dock.info-weight", "weight", dev.stsv.core.vehicle.CargoWeights.format(h.weight()),
                        "payload", dev.stsv.core.vehicle.CargoWeights.format(h.payload())),
                Lang.item("dock.info-kept", "kept", plan.kept()),
                Lang.item("dock.info-price", "pct", Math.round(rules.ratio() * 100)))));
        inv.setItem(INFO, info);
        inv.setItem(CANCEL, pane(Material.BARRIER, Lang.raw("dock.cancel")));
        inv.setItem(BED, pane(Material.CHEST, Lang.raw("dock.open-bed")));
        if (ok) {
            ItemStack confirm = pane(Material.EMERALD_BLOCK, Lang.raw("dock.confirm", "total", EconomyModule.format(plan.total())));
            confirm.editMeta(m -> m.lore(List.of(Lang.item("dock.confirm-to", "owner", h.ownerName()))));
            inv.setItem(CONFIRM, confirm);
        } else {
            String reason = plan.empty() ? "dock.why-nothing" : why;
            ItemStack no = pane(Material.GRAY_DYE, Lang.raw("dock.cannot"));
            no.editMeta(m -> m.lore(List.of(Lang.item(reason, "min", Math.round(rules.minDistance())))));
            inv.setItem(CONFIRM, no);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 0.8f);
    }

    private static ItemStack pane(Material m, String name) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.itemName(dev.stsv.core.util.Text.item(name));
            meta.setMaxStackSize(1);
        });
        return s;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Sale s)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        switch (e.getSlot()) {
            case CANCEL -> p.closeInventory();
            case BED -> {
                p.closeInventory();
                Bukkit.getScheduler().runTask(eco, () -> {
                    if (s.vehicle < 0) {
                        JigeModule j = jigeModule();
                        if (j != null) j.openFor(p);
                    } else if (core.vehicles() != null) core.vehicles().openBed(p, s.vehicle);
                });
            }
            case CONFIRM -> {
                if (!s.ok) return;
                p.closeInventory();
                Bukkit.getScheduler().runTask(eco, () -> confirm(p, s));
            }
            default -> { }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Sale) e.setCancelled(true);
    }

    /** The sale, checked again against the load as it is now: same bed, still in the zone, still far-hauled. */
    private void confirm(Player p, Sale s) {
        Hauled h = again(p, s.vehicle);
        if (h == null || h.version() != s.version) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        if (!mayUse(p, h) || dockOf(h) != s.dock || refusal(s.dock, h) != null) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        DockGoods.Plan plan = plan(h);
        if (plan.empty() || plan.total() != s.plan.total()) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        double[] c = s.dock.centre();
        double straight = h.trip().straight(s.dock.world(), c[0], c[2]), odometer = h.trip().odometer();
        List<ItemStack> taken = h.id() < 0 ? jigeModule().unload(p, h.version(), plan.slots()::contains)
                : core.vehicles().unload(h.id(), h.version(), plan.slots()::contains);
        if (taken == null) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        // what was taken is what was priced (the version matched): pay the owner
        UUID owner = h.owner();
        long paid = plan.total();
        StringBuilder items = new StringBuilder();
        for (DockGoods.Line l : plan.lines()) {
            if (!items.isEmpty()) items.append(", ");
            items.append(l.id()).append(" x").append(l.count());
        }
        if (paid > 0) core.economy().move(null, owner, paid, EconomyStore.Kind.FAUCET, "dock_sell", s.dock.name() + ": " + items);
        store.log(new DockStore.Sale(now(), s.dock.name(), h.id(), owner, p.getUniqueId(), items.toString(), paid, straight, odometer));
        eco.getLogger().info("[하역장] " + s.dock.name() + ": " + p.getName() + " 님이 " + h.name() + " #" + Math.abs(h.id()) + " (주인 "
                + h.ownerName() + ") 의 짐을 " + paid + " G 에 팜 (거리 " + Math.round(straight) + ", 주행 " + Math.round(odometer) + "): " + items);
        p.sendMessage(Lang.msg("dock.sold", "kinds", plan.lines().size(), "amount", EconomyModule.format(paid), "owner", h.ownerName()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        Player o = Bukkit.getPlayer(owner);
        if (o != null && !o.equals(p)) o.sendMessage(Lang.msg("dock.sold-owner", "player", p.getName(), "dock", s.dock.name(),
                "amount", EconomyModule.format(paid)));
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 하역장
    // ------------------------------------------------------------------------------------------

    private void command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        switch (sub) {
            case "설치" -> install(s, a);
            case "제거" -> remove(s, a.length > 1 ? a[1] : null);
            case "다시꾸미기" -> redecorate(s, a.length > 1 ? a[1] : null);
            case "후보" -> scout(s, a.length > 1 ? a[1] : null);
            case "기록" -> sales(s, a.length > 1 ? a[1] : null);
            default -> list(s);
        }
    }

    private void list(CommandSender s) {
        s.sendMessage(Lang.msg("dock.list", "count", docks.size()));
        for (Dock d : docks.values()) {
            double[] c = d.centre();
            World w = Bukkit.getWorld(d.world());
            RoadOrigin.Use u = w == null ? null : eco.roads() != null ? eco.roads().from(w)
                    : new RoadOrigin.Use(w.getName(), w.getSpawnLocation().getX(), 0, w.getSpawnLocation().getZ(), false);
            long far = u == null || !u.world().equals(d.world()) ? -1 : Math.round(Math.hypot(c[0] - u.x(), c[2] - u.z()));
            s.sendMessage(Lang.mm("dock.list-line", "name", d.name(), "kind", d.kind().word, "world", d.world(), "x", d.x(), "y", d.y(), "z", d.z(),
                    "far", far));
        }
    }

    private void install(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        if (a.length < 2 || !Dock.validName(a[1])) {
            s.sendMessage(Lang.msg("dock.usage"));
            return;
        }
        DockLayout.Kind kind = DockLayout.Kind.of(a.length > 2 ? a[2] : null);
        if (kind == null) {
            s.sendMessage(Lang.msg("dock.usage"));
            return;
        }
        if (docks.containsKey(key(a[1]))) {
            s.sendMessage(Lang.msg("dock.exists", "name", a[1]));
            return;
        }
        if (building != null) {
            s.sendMessage(Lang.msg("dock.busy"));
            return;
        }
        Location at = p.getLocation();
        Dock draft = new Dock(a[1], kind, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), Dock.facing(at.getYaw()), "", now());
        List<long[]> claimed = eco.land().claimServerChunks(draft.world(), draft.chunksTouched());
        if (claimed == null) {
            s.sendMessage(Lang.msg("dock.player-land"));
            return;
        }
        Dock d = new Dock(draft.name(), kind, draft.world(), draft.x(), draft.y(), draft.z(), draft.facing(), Dock.chunkText(claimed), draft.created());
        docks.put(key(d.name()), d);
        store.save(d);
        s.sendMessage(Lang.msg("dock.building", "name", d.name(), "kind", kind.word, "chunks", d.chunksTouched().size(), "claimed", claimed.size()));
        if (core.worlds() != null && !core.worlds().isBuild(at.getWorld())) s.sendMessage(Lang.msg("dock.not-build-world"));
        UUID who = p.getUniqueId();
        building = new DockBuilder(eco, d, at.getWorld(), rules.buildPerTick(), pct -> {
            Player q = Bukkit.getPlayer(who);
            if (q != null) q.sendActionBar(Lang.mm("dock.progress", "name", d.name(), "pct", pct));
        }, () -> {
            building = null;
            Player q = Bukkit.getPlayer(who);
            if (q != null) q.sendMessage(Lang.msg("dock.built", "name", d.name()));
            eco.getLogger().info("[하역장] " + d.name() + " (" + kind.word + ") 지음: " + d.world() + " " + d.x() + ", " + d.y() + ", " + d.z());
        });
        building.start();
    }

    /**
     * /관리자 하역장 다시꾸미기 &lt;이름&gt;: the dock's decorations as the current layout has them, in place: its blocks put again
     * (no clearing, no foundations: the ground stays as it is), the containers that were blocks taken away, its models and
     * floating name spawned again. For a dock built before the containers became models.
     */
    private void redecorate(CommandSender s, String name) {
        Dock d = name == null ? null : docks.get(key(name));
        if (d == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", name == null ? "" : name));
            return;
        }
        if (building != null) {
            s.sendMessage(Lang.msg("dock.busy"));
            return;
        }
        World w = Bukkit.getWorld(d.world());
        if (w == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", d.name()));
            return;
        }
        UUID who = s instanceof Player p ? p.getUniqueId() : null;
        s.sendMessage(Lang.msg("dock.redecorating", "name", d.name()));
        building = new DockBuilder(eco, d, w, rules.buildPerTick(), pct -> {
            Player q = who == null ? null : Bukkit.getPlayer(who);
            if (q != null) q.sendActionBar(Lang.mm("dock.progress", "name", d.name(), "pct", pct));
        }, () -> {
            building = null;
            Player q = who == null ? null : Bukkit.getPlayer(who);
            (q != null ? q : Bukkit.getConsoleSender()).sendMessage(Lang.msg("dock.redecorated", "name", d.name()));
        }).decorOnly();
        building.start();
    }

    private void remove(CommandSender s, String name) {
        Dock d = name == null ? null : docks.get(key(name));
        if (d == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", name == null ? "" : name));
            return;
        }
        docks.remove(key(d.name()));
        store.delete(d.name());
        int land = eco.land().releaseServerChunks(d.world(), d.claimed());
        World w = Bukkit.getWorld(d.world());
        int gone = (w == null ? 0 : DockBuilder.removeEntities(w, d.name(), d)) + (eco.roads() == null ? 0 : eco.roads().clearSigns(d.name()));
        s.sendMessage(Lang.msg("dock.removed", "name", d.name(), "land", land, "entities", gone));
    }

    /** A dock's display entities whose dock is gone (removed while their chunk was not loaded) go when it loads. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            String name = en.getPersistentDataContainer().get(DockBuilder.TAG, PersistentDataType.STRING);
            if (name != null && !docks.containsKey(key(name))) en.remove();
        }
    }

    private void sales(CommandSender s, String name) {
        store.sales(name, 15).thenAccept(list -> sync(() -> {
            s.sendMessage(Lang.msg("dock.sales", "count", list.size()));
            var fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.of(eco.getConfig().getString("time.zone")));
            for (DockStore.Sale x : list) {
                s.sendMessage(Lang.mm("dock.sales-line", "at", fmt.format(java.time.Instant.ofEpochMilli(x.at())), "dock", x.dock(),
                        "seller", Players.name(x.seller()), "owner", Players.name(x.owner()), "amount", EconomyModule.format(x.paid()),
                        "distance", Math.round(x.distance()), "items", x.items()));
            }
        }));
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 하역장 후보 <거리>: 8 directions from the build world's spawn
    // ------------------------------------------------------------------------------------------

    private static final String[] DIRS = {"남", "남서", "서", "북서", "북", "북동", "동", "남동"};

    /** One scan: the samples it wants, by chunk, and what they found. */
    private final class Scout {
        final CommandSender to;
        final World world;
        final int distance, step;
        final String from;
        final ChunkQueue queue;
        /** per direction: samples along the way, over the site, ahead of it: x, z -> {ground, water, surface} */
        final List<List<int[]>> route = new ArrayList<>(), site = new ArrayList<>(), ahead = new ArrayList<>();
        final Map<Long, int[]> sampled = new HashMap<>();
        final Map<Long, List<int[]>> byChunk = new HashMap<>();
        long lastNote;

        Scout(CommandSender to, World world, double cx, double cz, String from, int distance) {
            this.to = to;
            this.world = world;
            this.distance = distance;
            this.from = from;
            this.step = rules.scoutStep();
            this.queue = new ChunkQueue(world, rules.scoutConcurrent(), c -> {
                List<int[]> want = byChunk.remove(dev.stsv.economy.road.RoadJob.key(c.getX(), c.getZ()));
                if (want == null) return;
                for (int[] xz : want) sampled.put(dev.stsv.economy.road.RoadJob.key(xz[0], xz[1]), sampleColumn(world, xz[0], xz[1]));
            });
            Location spawn = new Location(world, cx, 0, cz);
            for (int d = 0; d < 8; d++) {
                double yaw = Math.toRadians(d * 45);
                double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                List<int[]> r = new ArrayList<>(), si = new ArrayList<>(), ah = new ArrayList<>();
                for (int k = 0; k <= distance; k += step) r.add(new int[]{(int) Math.floor(spawn.getX() + fx * k), (int) Math.floor(spawn.getZ() + fz * k)});
                int ex = (int) Math.floor(spawn.getX() + fx * distance), ez = (int) Math.floor(spawn.getZ() + fz * distance);
                for (int i = -20; i <= 20; i += 10) for (int j = -20; j <= 20; j += 10) si.add(new int[]{ex + i, ez + j});
                for (int k = 16; k <= 64; k += 16) ah.add(new int[]{(int) Math.floor(ex + fx * k), (int) Math.floor(ez + fz * k)});
                route.add(r);
                site.add(si);
                ahead.add(ah);
                for (List<int[]> l : List.of(r, si, ah)) for (int[] xz : l) want(xz);
            }
            for (long k : byChunk.keySet()) queue.add(dev.stsv.economy.road.RoadJob.cx(k), dev.stsv.economy.road.RoadJob.cz(k));
        }

        void want(int[] xz) {
            byChunk.computeIfAbsent(dev.stsv.economy.road.RoadJob.key(xz[0] >> 4, xz[1] >> 4), k -> new ArrayList<>()).add(xz);
        }

        void tick() {
            queue.pump();
            long t = now();
            if (t - lastNote > 5000 && !queue.finished()) {
                lastNote = t;
                to.sendMessage(Lang.msg("dock.scout-progress", "done", queue.done(), "left", queue.left()));
            }
            if (queue.finished()) {
                scout = null;
                report();
            }
        }

        int[] at(int[] xz) {
            int[] s = sampled.get(dev.stsv.economy.road.RoadJob.key(xz[0], xz[1]));
            return s == null ? new int[]{0, 0, 0} : s;
        }

        void report() {
            record Row(int dir, RouteStats stats, int x, int z) {}
            List<Row> rows = new ArrayList<>();
            for (int d = 0; d < 8; d++) {
                List<int[]> r = route.get(d), si = site.get(d), ah = ahead.get(d);
                int[] h = new int[r.size()];
                boolean[] w = new boolean[r.size()];
                for (int i = 0; i < r.size(); i++) {
                    int[] s = at(r.get(i));
                    h[i] = s[0];
                    w[i] = s[1] == 1;
                }
                int[] sh = new int[si.size()];
                boolean[] sw = new boolean[si.size()];
                for (int i = 0; i < si.size(); i++) {
                    int[] s = at(si.get(i));
                    sh[i] = s[0];
                    sw[i] = s[1] == 1;
                }
                boolean[] aw = new boolean[ah.size()];
                for (int i = 0; i < ah.size(); i++) aw[i] = at(ah.get(i))[1] == 1;
                int[] end = r.getLast();
                rows.add(new Row(d, RouteStats.of(h, w, step, sh, sw, aw), end[0], end[1]));
            }
            rows.sort(Comparator.comparingDouble(r -> r.stats().score()));
            to.sendMessage(Lang.msg("dock.scout-head", "distance", distance, "world", world.getName(), "from", from));
            int rank = 1;
            for (Row r : rows) {
                RouteStats st = r.stats();
                to.sendMessage(Lang.mm("dock.scout-line", "rank", rank++, "dir", DIRS[r.dir()], "x", r.x(), "z", r.z(),
                        "crossings", st.crossings(), "water", st.water(), "longest", st.longestWater(),
                        "steep", Math.round(st.steepShare() * 100), "climb", st.climb(), "site", st.siteRange(),
                        "kind", Lang.raw(st.siteWater() ? "dock.scout-site-water" : st.shore() ? "dock.scout-site-port" : "dock.scout-site-land")));
            }
            to.sendMessage(Lang.msg("dock.scout-foot"));
        }
    }

    /** A column's ground (ocean floor height map), whether its surface is water, and the surface's top. */
    public static int[] sampleColumn(World w, int x, int z) {
        int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        int floor = w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
        Material m = w.getBlockAt(x, top, z).getType();
        boolean water = m == Material.WATER || m == Material.ICE || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN;
        return new int[]{water ? floor : w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES), water ? 1 : 0, top};
    }

    private void scout(CommandSender s, String distanceText) {
        int distance;
        try {
            distance = Integer.parseInt(distanceText == null ? "" : distanceText.replace(",", ""));
        } catch (NumberFormatException e) {
            distance = -1;
        }
        if (distance < 500 || distance > 30000) {
            s.sendMessage(Lang.msg("dock.scout-usage"));
            return;
        }
        if (scout != null) {
            s.sendMessage(Lang.msg("dock.busy"));
            return;
        }
        World fallback = core.worlds() != null && core.worlds().buildWorld() != null ? core.worlds().buildWorld()
                : s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
        // from the network's reference point (/관리자 국도 기준점), else the build world's spawn
        RoadOrigin.Use u = eco.roads() != null ? eco.roads().from(fallback)
                : new RoadOrigin.Use(fallback.getName(), fallback.getSpawnLocation().getX(), 0, fallback.getSpawnLocation().getZ(), false);
        World w = Bukkit.getWorld(u.world());
        if (w == null) w = fallback;
        String from = eco.roads() != null ? RoadModule.fromWords(u) : Lang.raw("road.from-spawn", "world", w.getName(), "x", (long) u.x(), "y", 0, "z", (long) u.z());
        scout = new Scout(s, w, u.x(), u.z(), from, distance);
        s.sendMessage(Lang.msg("dock.scout-start", "chunks", scout.queue.left(), "distance", distance, "from", from));
    }

    public Rules rules() {
        return rules;
    }
}
