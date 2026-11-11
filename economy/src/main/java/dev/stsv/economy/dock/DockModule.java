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
 *   <li><b>Selling</b>: only finished goods ({@link DockGoods}) in the vehicle's bed, inspected and sealed for this dock at a
 *       departure terminal ({@link CargoTrip}), at the value sealed then: base price x the dock's ratio at seal time (its
 *       own, /관리자 하역장 가격, else docks.price-ratio). The money goes to the vehicle's owner; who may sell = who may open
 *       the bed. Every sale is logged.</li>
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
    /** 출발 터미널 (by name, lower case) and the sealed trips (by load id) */
    private final Map<String, Dock> terminals = new LinkedHashMap<>();
    private TripStore trips;
    private final Map<Long, CargoTrip> sealed = new HashMap<>();

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
        for (Dock t : store.terminals()) terminals.put(key(t.name()), t);
        trips = new TripStore(core.db());
        trips.init();
        for (CargoTrip t : trips.sealed()) sealed.put(t.vehicle(), t);
        load();
        if (core.vehicles() != null) {
            core.vehicles().cargoWeight(this::weigh);
            core.vehicles().cargoClick(this::bedClicked);
            core.vehicles().cargoSeal(this::seal);
            core.vehicles().tripBroken(this::broken);
        }
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "출발지", "설치 <이름> | 목록 | 제거 <이름> | 다시꾸미기 <이름>",
                "출발 터미널: 지금 자리에 짓기(보는 방향, 앞이 출구) — 화물 검사소·검사대·주차장, 국도 기준점이 그 출구로 / 목록 / 기록 지우기 / 장식만 다시",
                this::terminalCommand,
                (s, a) -> a.length == 1 ? List.of("설치", "목록", "제거", "다시꾸미기")
                        : a.length == 2 && (a[0].equals("제거") || a[0].equals("다시꾸미기")) ? terminals.values().stream().map(Dock::name).toList()
                        : List.of());
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "하역장",
                "설치 <이름> [육지|항구] | 목록 | 가격 <이름> <퍼센트|기본> | 제거 <이름> | 다시꾸미기 <이름> | 후보 <거리> | 기록 [이름]",
                "하역장: 지금 자리에 짓기(보는 방향) / 목록(매입가·거리) / 그 하역장 매입가(기준가의 1~300%, 기본 = docks.price-ratio) / 기록 지우기 / "
                        + "땅은 그대로 장식(컨테이너·모델·블록)만 다시 / 국도 기준점(없으면 건축월드 스폰)에서 8방향으로 후보지 살피기 / 판매 기록",
                this::command,
                (s, a) -> a.length == 1 ? List.of("설치", "목록", "가격", "제거", "다시꾸미기", "후보", "기록")
                        : a.length == 2 && (a[0].equals("제거") || a[0].equals("기록") || a[0].equals("다시꾸미기") || a[0].equals("가격")) ? new ArrayList<>(names())
                        : a.length == 2 && a[0].equals("후보") ? List.of("7000", "5000")
                        : a.length == 3 && a[0].equals("가격") ? List.of(Dock.DEFAULT_WORD, "95", "100", "110")
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
            core.vehicles().cargoSeal(null);
            core.vehicles().tripBroken(null);
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

    /** the chunks docks and terminals stand in, by world; null: worked out again on the next look (a dock or terminal came or went) */
    private Map<String, java.util.Set<Long>> cover;

    /**
     * Whether a dock or a departure terminal stands in this chunk (no snow settles there: RoadModule, on every snow event:
     * a lookup, the chunks worked out only after a dock or terminal comes or goes).
     */
    public boolean covers(String world, int cx, int cz) {
        Map<String, java.util.Set<Long>> c = cover;
        if (c == null) {
            c = new java.util.HashMap<>();
            for (Dock d : docks.values()) for (long[] ch : d.chunksTouched()) c.computeIfAbsent(d.world(), w -> new java.util.HashSet<>()).add(ch[0] << 32 ^ (ch[1] & 0xffffffffL));
            for (Dock d : terminals.values()) for (long[] ch : d.chunksTouched()) c.computeIfAbsent(d.world(), w -> new java.util.HashSet<>()).add(ch[0] << 32 ^ (ch[1] & 0xffffffffL));
            cover = c;
        }
        java.util.Set<Long> in = c.get(world);
        return in != null && in.contains((long) cx << 32 ^ (cz & 0xffffffffL));
    }

    /** The departure terminals (출발 터미널): a 국도 sign for the way back names the one its road started from. */
    public java.util.Collection<Dock> terminals() {
        return terminals.values();
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
        if (d != null) {
            open(p, d, h);
            return true;
        }
        CargoTrip t = sealed.get(h.id());
        if (t != null) {   // sealed on the way: what it is, and the owner may call it off
            sealedMenu(p, h, t);
            return true;
        }
        Dock term = terminalOf(h);
        if (term != null) {   // in a terminal's bay: the inspection
            inspect(p, term, h);
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCounter(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        for (Dock t : terminals.values()) {
            int[] c = t.counter();
            if (!t.world().equals(b.getWorld().getName()) || b.getX() != c[0] || b.getY() != c[1] || b.getZ() != c[2]) continue;
            e.setCancelled(true);
            inspectAt(e.getPlayer(), t);
            return;
        }
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

    /**
     * Why a load may not be sold here (a message key), or null: it must be an inspected trip (출발 터미널) for this dock, its
     * load as it was sealed, driven at least {@link CargoTrip#MIN_DRIVEN} of the straight way since.
     */
    String refusal(Dock d, Hauled h) {
        if (h.moving()) return "dock.why-moving";
        CargoTrip t = sealed.get(h.id());
        if (t == null) return "dock.why-no-trip";
        return switch (t.check(d.name(), h.trip().changed(), CargoTrip.manifest(plan(h).lines()), h.trip().odometer())) {
            case OTHER_DOCK -> "dock.why-other-dock";
            case MISMATCH -> "dock.why-mismatch";
            case SHORT -> "dock.why-short";
            case OK -> null;
        };
    }

    /** A trip whose load is not what was sealed: void (unsealed, never paid). */
    private void voidTrip(CargoTrip t, Player tell) {
        voidTrip(t, tell, "terminal.voided", "짐이 검사 때와 다름");
    }

    private void voidTrip(CargoTrip t, Player tell, String key, String why) {
        CargoTrip v = t.end(CargoTrip.Status.VOID, now());
        sealed.remove(t.vehicle());
        trips.end(v);
        eco.getLogger().info("[출발 터미널] 운송 #" + t.id() + " 무효 (" + why + "): " + t);
        if (tell != null) tell.sendMessage(Lang.msg(key, "dock", t.dock(), "terminal", t.terminal()));
        Player o = Bukkit.getPlayer(t.owner());
        if (o != null && o != tell) o.sendMessage(Lang.msg(key, "dock", t.dock(), "terminal", t.terminal()));
    }

    /** A load's trip was broken off (its 지게's wearer teleported: the core says so at once): its seal is void now. */
    private void broken(long id) {
        CargoTrip t = sealed.get(id);
        if (t != null) voidTrip(t, null, "terminal.voided-teleport", "순간이동");
    }

    /** The bed as the dock trade sees it. */
    static List<DockGoods.Slot> bed(Hauled h) {
        List<DockGoods.Slot> bed = new ArrayList<>();
        ItemStack[] c = h.contents();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null || c[i].isEmpty()) continue;
            String id = Items.key(c[i]);
            bed.add(new DockGoods.Slot(i, id, c[i].getAmount(), Items.plain(c[i], id)));
        }
        return bed;
    }

    /** What a load would sell (its manifest and slots: the same at every dock), priced at the global ratio. */
    DockGoods.Plan plan(Hauled h) {
        return goods.plan(bed(h));
    }

    /** What a dock pays as a share of the base now: its own price, else docks.price-ratio. */
    double ratio(Dock d) {
        return d.ratio(rules.ratio());
    }

    /** A dock's price for people: "110%", or "90% (기본)" on the global ratio. */
    String priceWords(Dock d) {
        String pct = Dock.percent(ratio(d));
        return d.ownPrice() ? Lang.raw("dock.price-own", "pct", pct) : Lang.raw("dock.price-default", "pct", pct);
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
        String why = refusal(d, h);
        if ("dock.why-mismatch".equals(why)) voidTrip(sealed.get(h.id()), p);
        CargoTrip trip = sealed.get(h.id());
        // the lines at the ratio this load was sealed with for this dock (its promised value), else at the dock's ratio now
        boolean promised = trip != null && trip.ratio() > 0 && trip.dock().equalsIgnoreCase(d.name());
        DockGoods.Plan plan = goods.plan(bed(h), promised ? trip.ratio() : ratio(d));
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
        ItemStack info = pane(Material.MAP, Lang.raw("dock.info-title", "vehicle", h.name(), "owner", h.ownerName()));
        List<Component> infoLore = new ArrayList<>(List.of(
                trip == null ? Lang.item("dock.info-no-trip") : Lang.item("dock.info-trip", "terminal", trip.terminal(), "dock", trip.dock()),
                trip == null ? Lang.item("dock.info-no-trip-how") : Lang.item("dock.info-driven", "driven", Math.round(trip.driven(h.trip().odometer())),
                        "needed", Math.round(trip.needed())),
                Lang.item("dock.info-weight", "weight", dev.stsv.core.vehicle.CargoWeights.format(h.weight()),
                        "payload", dev.stsv.core.vehicle.CargoWeights.format(h.payload())),
                Lang.item("dock.info-kept", "kept", plan.kept()),
                Lang.item("dock.info-price", "price", priceWords(d))));
        if (trip != null) infoLore.add(Lang.item("dock.info-promised", "total", EconomyModule.format(trip.total())));
        info.editMeta(m -> m.lore(infoLore));
        inv.setItem(INFO, info);
        inv.setItem(CANCEL, pane(Material.BARRIER, Lang.raw("dock.cancel")));
        inv.setItem(BED, pane(Material.CHEST, Lang.raw("dock.open-bed")));
        if (ok) {
            // what it pays: the value sealed at inspection (the dock's ratio then), whatever its price is now
            ItemStack confirm = pane(Material.EMERALD_BLOCK, Lang.raw("dock.confirm", "total", EconomyModule.format(trip.total())));
            confirm.editMeta(m -> m.lore(List.of(Lang.item("dock.confirm-to", "owner", h.ownerName()))));
            inv.setItem(CONFIRM, confirm);
        } else {
            String reason = why != null ? why : "dock.why-nothing";
            ItemStack no = pane(Material.GRAY_DYE, Lang.raw("dock.cannot"));
            no.editMeta(m -> m.lore(List.of(Lang.item(reason, "dock", trip == null ? "" : trip.dock(),
                    "needed", trip == null ? 0 : Math.round(trip.needed())))));
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
        Dock here = dockOf(h);   // by name: its price may have been set since the menu opened (a new record)
        if (!mayUse(p, h) || here == null || !here.name().equals(s.dock.name()) || refusal(here, h) != null) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        DockGoods.Plan plan = plan(h);
        CargoTrip trip = sealed.get(h.id());
        if (plan.empty() || trip == null || !CargoTrip.manifest(plan.lines()).equals(trip.manifest())) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        double straight = trip.straight(), odometer = trip.driven(h.trip().odometer());
        List<ItemStack> taken = h.id() < 0 ? jigeModule().unload(p, h.version(), plan.slots()::contains)
                : core.vehicles().unload(h.id(), h.version(), plan.slots()::contains);
        if (taken == null) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        // what was taken is what was sealed (the version matched): pay the owner what it was worth at inspection
        UUID owner = h.owner();
        long paid = trip.total();
        CargoTrip done = trip.end(CargoTrip.Status.DONE, now());
        sealed.remove(h.id());
        trips.end(done);
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
            case "가격" -> price(s, a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
            default -> list(s);
        }
    }

    private void list(CommandSender s) {
        s.sendMessage(Lang.msg("dock.list", "count", docks.size(), "pct", Dock.percent(rules.ratio())));
        for (Dock d : docks.values()) {
            double[] c = d.centre();
            World w = Bukkit.getWorld(d.world());
            RoadOrigin.Use u = w == null ? null : eco.roads() != null ? eco.roads().from(w)
                    : new RoadOrigin.Use(w.getName(), w.getSpawnLocation().getX(), 0, w.getSpawnLocation().getZ(), false);
            String far = u == null || !u.world().equals(d.world()) ? Lang.raw("dock.list-far-none")
                    : Lang.raw("dock.list-far", "far", String.format(Locale.ROOT, "%,d", Math.round(Math.hypot(c[0] - u.x(), c[2] - u.z()))));
            List<String> fromTerminals = new ArrayList<>();
            for (Dock t : terminals.values()) {
                double st = straight(t, d);
                if (Double.isFinite(st)) fromTerminals.add(Lang.raw("dock.list-terminal", "terminal", t.name(),
                        "far", String.format(Locale.ROOT, "%,d", Math.round(st))));
            }
            s.sendMessage(Lang.mm("dock.list-line", "name", d.name(), "kind", d.kind().word, "world", d.world(), "x", d.x(), "y", d.y(), "z", d.z(),
                    "price", priceWords(d), "far", far,
                    "terminals", fromTerminals.isEmpty() ? "" : Lang.raw("dock.list-terminals", "list", String.join(", ", fromTerminals))));
        }
    }

    /**
     * /관리자 하역장 가격 &lt;이름&gt; &lt;퍼센트|기본&gt;: what this dock pays, in percent of the shop base price (1 .. 300), or the
     * global docks.price-ratio again. Trips sealed before keep the value they were sealed with.
     */
    private void price(CommandSender s, String name, String pctText) {
        String global = Dock.percent(rules.ratio());
        if (name == null) {
            s.sendMessage(Lang.msg("dock.price-usage", "pct", global));
            return;
        }
        Dock d = docks.get(key(name));
        if (d == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", name));
            return;
        }
        if (pctText == null) {
            s.sendMessage(Lang.msg("dock.price-show", "name", d.name(), "price", priceWords(d)));
            s.sendMessage(Lang.msg("dock.price-usage", "pct", global));
            return;
        }
        int pct = Dock.parsePrice(pctText);
        if (pct == Dock.BAD_PRICE) {
            s.sendMessage(Lang.msg("dock.price-bad", "value", pctText, "min", Dock.MIN_PRICE, "max", Dock.MAX_PRICE));
            return;
        }
        Dock priced = d.withPrice(pct);
        docks.put(key(d.name()), priced);
        store.price(d.name(), pct);
        long open = sealed.values().stream().filter(t -> t.dock().equalsIgnoreCase(d.name())).count();
        eco.getLogger().info("[하역장] " + d.name() + " 매입가: " + (pct == Dock.DEFAULT_PRICE ? "기본 (" + global + "%)" : pct + "%")
                + " (" + s.getName() + ", 봉인된 운송 " + open + "건은 봉인 때 값 그대로)");
        s.sendMessage(pct == Dock.DEFAULT_PRICE ? Lang.msg("dock.price-reset", "name", d.name(), "pct", global, "open", open)
                : Lang.msg("dock.price-set", "name", d.name(), "pct", pct, "open", open));
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
        if (docks.containsKey(key(a[1])) || terminals.containsKey(key(a[1]))) {
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
        cover = null;
        store.save(d);
        for (Dock t : terminals.values()) {
            double far = straight(t, d);
            if (far < rules.minDistance()) s.sendMessage(Lang.msg("terminal.too-close", "terminal", t.name(), "dock", d.name(), "far", Math.round(far),
                    "min", Math.round(rules.minDistance())));
        }
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
        cover = null;
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
            if (name != null && !docks.containsKey(key(name)) && !terminals.containsKey(key(name))) en.remove();
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
    // 출발 터미널: the inspection (화물 검사) and the seal
    // ------------------------------------------------------------------------------------------

    /** Why a load is sealed (the core asks before its bed or 지게 opens), or null. */
    private Component seal(long id) {
        CargoTrip t = sealed.get(id);
        return t == null ? null : Lang.mm("terminal.sealed", "dock", t.dock(), "terminal", t.terminal());
    }

    /** The trip sealed for a load (by its id), or null. */
    public CargoTrip trip(long id) {
        return sealed.get(id);
    }

    /** The terminal whose bay holds any unit of this load, or null. */
    Dock terminalOf(Hauled h) {
        for (Dock t : terminals.values()) if (t.anyInZone(h.world().getName(), h.points())) return t;
        return null;
    }

    /** Where a terminal's loads are measured from: the middle of its bay. */
    static double[] bay(Dock t) {
        DockLayout.Box b = DockLayout.BAY;
        return t.world((b.minX() + b.maxX() + 1) / 2.0 - 0.5, 0, (b.minZ() + b.maxZ() + 1) / 2.0 - 0.5);
    }

    /** The straight way from a terminal's bay to a dock's zone (blocks; infinite in another world). */
    static double straight(Dock terminal, Dock dock) {
        if (!terminal.world().equals(dock.world())) return Double.POSITIVE_INFINITY;
        double[] a = bay(terminal), b = dock.centre();
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    /** The counter: the player's 지게 in the bay, else the nearest load in the bay they may use. */
    private void inspectAt(Player p, Dock t) {
        Hauled jige = jige(p);
        if (jige != null && terminalOf(jige) == t && !empty(jige)) {
            Hauled j = jige;
            if (sealed.containsKey(j.id())) sealedMenu(p, j, sealed.get(j.id()));
            else inspect(p, t, j);
            return;
        }
        Hauled best = null;
        double bestD = Double.MAX_VALUE;
        double[] c = bay(t);
        if (core.vehicles() != null) {
            for (Hauled h : core.vehicles().hauledNear(new Location(p.getWorld(), c[0], c[1], c[2]), 48)) {
                if (terminalOf(h) != t || empty(h) || !core.vehicles().mayUse(p, h.id())) continue;
                double dd = p.getLocation().distanceSquared(new Location(p.getWorld(), h.centre()[0], h.centre()[1], h.centre()[2]));
                if (dd < bestD) {
                    bestD = dd;
                    best = h;
                }
            }
        }
        if (best == null) {
            HudModule.flash(p, Lang.mm("terminal.bay-nothing"));
            return;
        }
        if (sealed.containsKey(best.id())) sealedMenu(p, best, sealed.get(best.id()));
        else inspect(p, t, best);
    }

    /** A destination at inspection: a dock, how far it is from the terminal, its ratio now and what this load fetches there. */
    record Offer(Dock dock, double far, double ratio, DockGoods.Plan plan) {
        long total() {
            return plan.total();
        }
    }

    /**
     * The destinations of a load at a terminal: every dock in its world, nearest first, each with what the load fetches at
     * that dock's ratio now (its own price, else the global one). Pure (unit-tested).
     */
    static List<Offer> offers(DockGoods goods, List<DockGoods.Slot> bed, Dock terminal, java.util.Collection<Dock> docks) {
        List<Offer> out = new ArrayList<>();
        for (Dock d : docks) {
            double far = straight(terminal, d);
            if (!Double.isFinite(far)) continue;
            double r = d.ratio(goods.ratio());
            out.add(new Offer(d, far, r, goods.plan(bed, r)));
        }
        out.sort(Comparator.comparingDouble(Offer::far));
        return out;
    }

    /** The 화물 검사 menu: the manifest (what the docks buy and what it is worth), where it goes, the seal. */
    final class Inspect implements InventoryHolder {
        final Dock terminal;
        final long vehicle, version;
        final List<DockGoods.Slot> bed;
        /** at the global ratio: the manifest, the slots, what is kept (the same at every dock) */
        final DockGoods.Plan plan;
        final List<Offer> choices = new ArrayList<>();
        Offer chosen;
        Inventory inventory;

        Inspect(Dock terminal, Hauled h, List<DockGoods.Slot> bed, DockGoods.Plan plan) {
            this.terminal = terminal;
            this.vehicle = h.id();
            this.version = h.version();
            this.bed = bed;
            this.plan = plan;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    static final int I_DOCKS = 27, I_LIST = 27;

    void inspect(Player p, Dock t, Hauled h) {
        if (!mayUse(p, h)) {
            HudModule.flash(p, Lang.mm("dock.not-yours", "owner", h.ownerName()));
            return;
        }
        if (h.moving()) {
            HudModule.flash(p, Lang.mm("terminal.moving"));
            return;
        }
        List<DockGoods.Slot> bed = bed(h);
        Inspect in = new Inspect(t, h, bed, goods.plan(bed));
        in.choices.addAll(offers(goods, bed, t, docks.values()));
        if (in.choices.size() == 1) in.chosen = in.choices.getFirst();
        Inventory inv = Bukkit.createInventory(in, 54, Lang.mm("terminal.title"));
        in.inventory = inv;
        draw(in, h);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1.1f);
    }

    private void draw(Inspect in, Hauled h) {
        Inventory inv = in.inventory;
        inv.clear();
        int slot = 0;
        // the goods priced at the chosen dock (none chosen yet: at the global ratio)
        for (DockGoods.Line l : (in.chosen != null ? in.chosen.plan() : in.plan).lines()) {
            if (slot >= I_LIST) break;
            ItemStack icon = Items.create(l.id(), 1).orElse(new ItemStack(Material.PAPER));
            icon.setAmount(Math.clamp(l.count(), 1, icon.getMaxStackSize()));
            List<Component> lore = new ArrayList<>();
            lore.add(Lang.item("dock.line", "count", l.count(), "unit", String.format(Locale.ROOT, "%.1f", l.unit()),
                    "total", EconomyModule.format((long) Math.floor(l.total()))));
            icon.editMeta(m -> m.lore(lore));
            inv.setItem(slot++, icon);
        }
        for (int i = I_DOCKS; i < 54; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, " "));
        for (int i = 0; i < Math.min(9, in.choices.size()); i++) {
            Offer o = in.choices.get(i);
            Dock d = o.dock();
            boolean on = o == in.chosen;
            double far = o.far();
            ItemStack icon = pane(on ? Material.FILLED_MAP : Material.MAP, Lang.raw(on ? "terminal.dock-chosen" : "terminal.dock", "dock", d.name()));
            icon.editMeta(m -> {
                List<Component> lore = new ArrayList<>();
                lore.add(Lang.item("terminal.dock-price", "price", priceWords(d), "total", EconomyModule.format(o.total())));
                lore.add(Lang.item("terminal.dock-far", "far", Math.round(far), "needed", Math.round(far * CargoTrip.MIN_DRIVEN)));
                if (far < rules.minDistance()) lore.add(Lang.item("terminal.dock-near", "min", Math.round(rules.minDistance())));
                lore.add(Lang.item(on ? "terminal.dock-on" : "terminal.dock-pick"));
                m.lore(lore);
                if (on) m.setEnchantmentGlintOverride(true);
            });
            inv.setItem(I_DOCKS + i, icon);
        }
        ItemStack info = pane(Material.WRITABLE_BOOK, Lang.raw("terminal.info-title", "vehicle", h.name(), "owner", h.ownerName()));
        info.editMeta(m -> m.lore(List.of(
                Lang.item("dock.info-weight", "weight", dev.stsv.core.vehicle.CargoWeights.format(h.weight()),
                        "payload", dev.stsv.core.vehicle.CargoWeights.format(h.payload())),
                Lang.item("terminal.info-kept", "kept", in.plan.kept()),
                Lang.item("terminal.info-seal"),
                Lang.item("terminal.info-price"))));
        inv.setItem(INFO, info);
        inv.setItem(CANCEL, pane(Material.BARRIER, Lang.raw("dock.cancel")));
        inv.setItem(BED, pane(Material.CHEST, Lang.raw("terminal.open-bed")));
        boolean ok = in.chosen != null && !in.plan.empty();
        if (ok) {
            Offer o = in.chosen;
            ItemStack confirm = pane(Material.EMERALD_BLOCK, Lang.raw("terminal.confirm", "dock", o.dock().name(), "total", EconomyModule.format(o.total())));
            confirm.editMeta(m -> m.lore(List.of(Lang.item("terminal.confirm-lore"),
                    Lang.item("terminal.confirm-promise", "pct", Dock.percent(o.ratio())))));
            inv.setItem(CONFIRM, confirm);
        } else {
            ItemStack no = pane(Material.GRAY_DYE, Lang.raw("dock.cannot"));
            no.editMeta(m -> m.lore(List.of(Lang.item(in.plan.empty() ? "terminal.why-nothing" : in.choices.isEmpty() ? "terminal.why-no-docks"
                    : "terminal.why-pick"))));
            inv.setItem(CONFIRM, no);
        }
    }

    /** Seals the load for the chosen dock, checked again against the load as it is now (same bed, in the bay, still). */
    private void seal(Player p, Inspect in) {
        // whoever still has its bed (or 지게) open loses the view first, what they did stored: they cannot take anything out
        // of the sealed load through it, and the load checked below is the one sealed (its version moved on if they did)
        if (core.vehicles() != null) core.vehicles().closeLoad(in.vehicle);
        Hauled h = again(p, in.vehicle);
        if (h == null || h.version() != in.version || !mayUse(p, h) || terminalOf(h) != in.terminal || h.moving() || sealed.containsKey(h.id())) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        List<DockGoods.Slot> bed = bed(h);
        DockGoods.Plan plan = goods.plan(bed);
        Dock to = in.chosen == null ? null : dock(in.chosen.dock().name());   // the dock as it is now (its price too)
        if (plan.empty() || !CargoTrip.manifest(plan.lines()).equals(CargoTrip.manifest(in.plan.lines())) || to == null) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        // the value at the dock's ratio now, fixed from here on: a later price change does not touch this trip
        CargoTrip draft = CargoTrip.seal(h.id(), h.owner(), in.terminal.name(), to, goods, bed, h.trip().changed(), h.trip().odometer(),
                straight(in.terminal, to), now());
        if (draft.total() != in.chosen.total()) {   // its price was set while the menu was open: never seal another value than shown
            p.sendMessage(Lang.msg("terminal.price-changed", "dock", to.name(), "price", priceWords(to), "total", EconomyModule.format(draft.total())));
            return;
        }
        CargoTrip t = trips.open(draft);
        sealed.put(h.id(), t);
        eco.getLogger().info("[출발 터미널] " + in.terminal.name() + ": " + p.getName() + " 님이 " + h.name() + " #" + Math.abs(h.id()) + " 검사, "
                + to.name() + " 하역장으로 봉인 (" + t.total() + " G, 매입가 " + Dock.percent(t.ratio()) + "%): " + CargoTrip.words(t.manifest()));
        p.sendMessage(Lang.msg("terminal.sealed-now", "dock", t.dock(), "far", Math.round(t.straight()), "needed", Math.round(t.needed()),
                "total", EconomyModule.format(t.total()), "pct", Dock.percent(t.ratio())));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 1.2f);
    }

    /** A sealed load away from its dock: where it goes, how far it has come, and (its owner) calling the trip off. */
    final class SealedView implements InventoryHolder {
        final CargoTrip trip;
        final long vehicle;
        Inventory inventory;

        SealedView(CargoTrip trip, long vehicle) {
            this.trip = trip;
            this.vehicle = vehicle;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    static final int S_INFO = 11, S_CANCEL = 15;

    private void sealedMenu(Player p, Hauled h, CargoTrip t) {
        SealedView v = new SealedView(t, h.id());
        Inventory inv = Bukkit.createInventory(v, 27, Lang.mm("terminal.sealed-title"));
        v.inventory = inv;
        for (int i = 0; i < 27; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, " "));
        ItemStack info = pane(Material.WRITABLE_BOOK, Lang.raw("terminal.sealed-info", "terminal", t.terminal(), "dock", t.dock()));
        info.editMeta(m -> m.lore(List.of(
                Lang.item("terminal.sealed-goods", "goods", CargoTrip.words(t.manifest())),
                Lang.item("terminal.sealed-total", "total", EconomyModule.format(t.total())),
                t.ratio() > 0 ? Lang.item("terminal.sealed-promise", "pct", Dock.percent(t.ratio())) : Lang.item("terminal.sealed-promise-old"),
                Lang.item("dock.info-driven", "driven", Math.round(t.driven(h.trip().odometer())), "needed", Math.round(t.needed())))));
        inv.setItem(S_INFO, info);
        ItemStack cancel = pane(Material.SHEARS, Lang.raw("terminal.cancel"));
        cancel.editMeta(m -> m.lore(List.of(Lang.item("terminal.cancel-lore"))));
        inv.setItem(S_CANCEL, cancel);
        p.openInventory(inv);
    }

    @EventHandler
    public void onTerminalClick(InventoryClickEvent e) {
        Object holder = e.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof Inspect) && !(holder instanceof SealedView)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (holder instanceof Inspect in) {
            int slot = e.getSlot();
            if (slot >= I_DOCKS && slot < I_DOCKS + Math.min(9, in.choices.size())) {
                in.chosen = in.choices.get(slot - I_DOCKS);
                Hauled h = again(p, in.vehicle);
                if (h != null) draw(in, h);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                return;
            }
            switch (slot) {
                case CANCEL -> p.closeInventory();
                case BED -> {
                    p.closeInventory();
                    Bukkit.getScheduler().runTask(eco, () -> {
                        if (in.vehicle < 0) {
                            JigeModule j = jigeModule();
                            if (j != null) j.openFor(p);
                        } else if (core.vehicles() != null) core.vehicles().openBed(p, in.vehicle);
                    });
                }
                case CONFIRM -> {
                    if (in.chosen == null || in.plan.empty()) return;
                    p.closeInventory();
                    Bukkit.getScheduler().runTask(eco, () -> seal(p, in));
                }
                default -> { }
            }
        } else if (holder instanceof SealedView v && e.getSlot() == S_CANCEL) {
            p.closeInventory();
            CargoTrip t = sealed.get(v.vehicle);
            if (t == null || t.id() != v.trip.id()) return;
            if (!t.owner().equals(p.getUniqueId()) && !p.hasPermission("stsv.admin")) {
                p.sendMessage(Lang.msg("terminal.cancel-owner"));
                return;
            }
            CargoTrip c = t.end(CargoTrip.Status.CANCELLED, now());
            sealed.remove(v.vehicle);
            trips.end(c);
            eco.getLogger().info("[출발 터미널] 운송 #" + t.id() + " 취소: " + p.getName());
            p.sendMessage(Lang.msg("terminal.cancelled", "dock", t.dock()));
        }
    }

    @EventHandler
    public void onTerminalDrag(InventoryDragEvent e) {
        Object holder = e.getView().getTopInventory().getHolder(false);
        if (holder instanceof Inspect || holder instanceof SealedView) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 출발지
    // ------------------------------------------------------------------------------------------

    private void terminalCommand(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        String name = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "설치" -> installTerminal(s, name);
            case "제거" -> {
                Dock t = name == null ? null : terminals.get(key(name));
                if (t == null) {
                    s.sendMessage(Lang.msg("terminal.unknown", "name", name == null ? "" : name));
                    return;
                }
                terminals.remove(key(t.name()));
                cover = null;
                store.deleteTerminal(t.name());
                int land = eco.land().releaseServerChunks(t.world(), t.claimed());
                World w = Bukkit.getWorld(t.world());
                int gone = w == null ? 0 : DockBuilder.removeEntities(w, t.name(), t);
                s.sendMessage(Lang.msg("terminal.removed", "name", t.name(), "land", land, "entities", gone));
            }
            case "다시꾸미기" -> {
                Dock t = name == null ? null : terminals.get(key(name));
                if (t == null) {
                    s.sendMessage(Lang.msg("terminal.unknown", "name", name == null ? "" : name));
                    return;
                }
                rebuild(s, t, true);
            }
            default -> {
                s.sendMessage(Lang.msg("terminal.list", "count", terminals.size()));
                for (Dock t : terminals.values()) s.sendMessage(Lang.mm("terminal.list-line", "name", t.name(), "world", t.world(), "x", t.x(), "y", t.y(),
                        "z", t.z()));
            }
        }
    }

    private void installTerminal(CommandSender s, String name) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        if (name == null || !Dock.validName(name)) {
            s.sendMessage(Lang.msg("terminal.usage"));
            return;
        }
        if (terminals.containsKey(key(name)) || docks.containsKey(key(name))) {   // one name, one place (their displays carry it)
            s.sendMessage(Lang.msg("terminal.exists", "name", name));
            return;
        }
        if (building != null) {
            s.sendMessage(Lang.msg("dock.busy"));
            return;
        }
        Location at = p.getLocation();
        Dock draft = new Dock(name, DockLayout.Kind.TERMINAL, at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                Dock.facing(at.getYaw()), "", now());
        List<long[]> claimed = eco.land().claimServerChunks(draft.world(), draft.chunksTouched());
        if (claimed == null) {
            s.sendMessage(Lang.msg("dock.player-land"));
            return;
        }
        Dock t = new Dock(draft.name(), draft.kind(), draft.world(), draft.x(), draft.y(), draft.z(), draft.facing(), Dock.chunkText(claimed), draft.created());
        terminals.put(key(t.name()), t);
        cover = null;
        store.saveTerminal(t);
        // the 국도 start from its exit, straight out first
        if (eco.roads() != null) {
            int[] g = t.gate(), f = Dock.forward(t.facing());
            eco.roads().origin(new RoadOrigin(t.world(), g[0] + 0.5, g[1], g[2] + 0.5, -f[0], -f[1]));
        }
        s.sendMessage(Lang.msg("terminal.building", "name", t.name(), "chunks", t.chunksTouched().size(), "claimed", claimed.size()));
        for (Dock d : docks.values()) {
            double far = straight(t, d);
            if (far < rules.minDistance()) s.sendMessage(Lang.msg("terminal.too-close", "terminal", t.name(), "dock", d.name(), "far", Math.round(far),
                    "min", Math.round(rules.minDistance())));
        }
        rebuild(s, t, false);
    }

    /** Builds a terminal (all of it, or only its decorations again). */
    private void rebuild(CommandSender s, Dock t, boolean decorOnly) {
        if (building != null) {
            s.sendMessage(Lang.msg("dock.busy"));
            return;
        }
        World w = Bukkit.getWorld(t.world());
        if (w == null) return;
        UUID who = s instanceof Player p ? p.getUniqueId() : null;
        building = new DockBuilder(eco, t, w, rules.buildPerTick(), pct -> {
            Player q = who == null ? null : Bukkit.getPlayer(who);
            if (q != null) q.sendActionBar(Lang.mm("dock.progress", "name", t.name(), "pct", pct));
        }, () -> {
            building = null;
            Player q = who == null ? null : Bukkit.getPlayer(who);
            (q != null ? q : Bukkit.getConsoleSender()).sendMessage(Lang.msg("terminal.built", "name", t.name()));
            eco.getLogger().info("[출발 터미널] " + t.name() + " 지음: " + t.world() + " " + t.x() + ", " + t.y() + ", " + t.z());
        });
        if (decorOnly) building.decorOnly();
        building.start();
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
            to.sendMessage(Lang.msg("dock.scout-head", "distance", distance, "from", from));
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
