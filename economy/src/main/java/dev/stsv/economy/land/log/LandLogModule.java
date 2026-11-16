package dev.stsv.economy.land.log;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurniturePlaceEvent;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.land.Land;
import dev.stsv.economy.land.log.LandLogStore.Action;
import dev.stsv.economy.land.log.LandLogStore.Row;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * 땅 기록 · 테러 되돌리기 (README "테러 되돌리기").
 *
 * <p><b>The log.</b> Inside a land everyone but its owner is written down when they change something: blocks broken
 * and placed (a door's or a bed's other half too, buckets, fire), what they take out of and put into containers, frames,
 * paintings, armor stands and animals they destroy or put up, our furniture. In a land only its people may change
 * anything ({@code LandAccess}), so this is the members (and administrators with the bypass): exactly who could grief
 * it. The owner's own work is not written (config land-log.owner). With each line goes what the person <b>gained</b> by
 * it: a broken block's real drops (the drop event of that very break: nothing in creative or without the right tool), a
 * scooped liquid's bucket, an animal's or stand's drops, a frame and its item, the items taken from a container.
 *
 * <p><b>Undo without making items.</b> Only what is taken back from the person is put back ({@link Confiscation}): an
 * undo first takes what they gained out of their inventory and ender chest (the same item, isSimilar), then restores
 * exactly that part. Units that gained them nothing (blocks placed, things broken with no drops, items they put in) go
 * back freely. Whatever they no longer hold stays owed: the lines wait, the owner sees "아직 회수 못 함", and they are
 * tried again when the person next joins. What an undo removes that the person paid for (a block they placed, items
 * they put in, a frame they hung) goes back to them by mail, so nothing is lost or made. Lines undone are marked, so
 * nothing is undone twice.
 *
 * <p><b>/관리자 테러.</b> An administrator's one step for a griefer: the same undo, then ban and kick, and the money
 * other players paid them in that time paid back. With 강제 everything goes back even where too little could be taken
 * back (the administrator's call, written in the audit).
 */
public final class LandLogModule extends EcoModule {

    /** A change waiting for its after-state (next tick) and, for a break, its drops. */
    private static final class Pending {
        Row row;
        final Block block;

        Pending(Row row, Block block) {
            this.row = row;
            this.block = block;
        }
    }

    /** An open container: what it held when this person opened it. */
    private record Open(Location at, long land, UUID owner, Map<String, Integer> before) {}

    /** An undo the owner is looking at (preview) and may confirm. */
    private record Preview(UUID actor, String actorName, long land, long since, List<Row> rows, List<UndoPlan.Step> steps, World world, long until) {}

    /** An animal's or stand's last blow from a player in a watched land: who, and the entity as it was just before. */
    private record Hit(UUID player, byte[] copy, long at) {}

    private LandLogStore store;
    private final List<Pending> settling = new ArrayList<>();
    private final ConcurrentLinkedQueue<Row> queue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Open> open = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    /** entity -> its last blow (an armor stand's death names no killer; a dead animal cannot be copied alive) */
    private final Map<UUID, Hit> hitBy = new HashMap<>();
    private boolean undoing;

    public LandLogModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-land-log";
    }

    @Override
    protected void start() {
        store = new LandLogStore(core.db());
        store.init();
        every(1, this::settle);
        every(20, this::flush);
        every(20 * 60 * 60, this::prune);
        every(20, this::expirePreviews);
        Bukkit.getScheduler().runTaskLater(eco, this::prune, 20 * 60);
    }

    @Override
    protected void stop() {
        settle();
        // the last lines are written before the plugin goes (the database is still open: the core closes it later)
        List<Row> rest = drain();
        if (!rest.isEmpty()) {
            try {
                store.write(rest).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                eco.getLogger().log(Level.WARNING, "땅 기록: 마지막 " + rest.size() + "줄을 쓰지 못함", e);
            }
        }
        for (UUID u : List.copyOf(previews.keySet())) endPreview(u);
    }

    public LandLogStore store() {
        return store;
    }

    private boolean on() {
        return eco.getConfig().getBoolean("land-log.enabled");
    }

    // ------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------

    /** The land here if this person's changes in it are written down (not its owner's), else null. */
    private Land watched(Player p, Location l) {
        if (!on() || undoing || l == null || l.getWorld() == null) return null;
        Land land = eco.land().at(l);
        if (land == null) return null;
        if (land.isOwner(p.getUniqueId()) && !eco.getConfig().getBoolean("land-log.owner")) return null;
        return land;
    }

    private Row row(Player p, Land land, Location l, Action a, String before, String after, byte[] item, int amount, byte[] gain) {
        return new Row(0, now(), land.id, land.owner, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), p.getUniqueId(), p.getName(),
                a, before, after, item, amount, gain, false);
    }

    private void log(Player p, Land land, Location l, Action a, String before, String after, byte[] item, int amount, byte[] gain) {
        queue.add(row(p, land, l, a, before, after, item, amount, gain));
    }

    /** A block change: its after-state is read next tick (a door's top half, a bed's foot appear only then). */
    private void logBlock(Player p, Land land, Block b, Action a, String before, byte[] item, int primary, byte[] gain) {
        settling.add(new Pending(row(p, land, b.getLocation(), a, before, null, item, primary, gain), b));
    }

    private void settle() {
        if (settling.isEmpty()) return;
        for (Pending p : settling) queue.add(p.row.withAfter(p.block.getBlockData().getAsString()));
        settling.clear();
    }

    private List<Row> drain() {
        List<Row> out = new ArrayList<>();
        for (Row r; (r = queue.poll()) != null; ) out.add(r);
        return out;
    }

    private void flush() {
        List<Row> rows = drain();
        if (rows.isEmpty()) return;
        store.write(rows).exceptionally(err -> {
            eco.getLogger().log(Level.WARNING, "땅 기록: " + rows.size() + "줄을 쓰지 못함", err);
            return null;
        });
    }

    private void prune() {
        int days = eco.getConfig().getInt("land-log.keep-days");
        if (days <= 0) return;
        store.prune(now() - days * 86_400_000L).thenAccept(n -> {
            if (n > 0) eco.getLogger().info("땅 기록: " + days + "일 지난 " + n + "줄을 지웠습니다.");
        });
    }

    private static String data(Block b) {
        return b.getBlockData().getAsString();
    }

    /** The other half of a door, tall flower or bed, or null. */
    private static Block partner(Block b, BlockData d) {
        if (d instanceof Bed bed) {
            return b.getRelative(bed.getPart() == Bed.Part.HEAD ? bed.getFacing().getOppositeFace() : bed.getFacing());
        }
        if (d instanceof Bisected bi && !(d instanceof org.bukkit.block.data.type.Stairs) && !(d instanceof org.bukkit.block.data.type.TrapDoor)) {
            return b.getRelative(bi.getHalf() == Bisected.Half.TOP ? org.bukkit.block.BlockFace.DOWN : org.bukkit.block.BlockFace.UP);
        }
        return null;
    }

    private static byte[] contents(BlockState s) {
        if (!(s instanceof Container c)) return null;
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : c.getSnapshotInventory().getContents()) if (it != null && !it.isEmpty()) items.add(it);
        return items.isEmpty() ? null : ItemStack.serializeItemsAsBytes(items);
    }

    private static byte[] pack(List<ItemStack> items) {
        List<ItemStack> real = items.stream().filter(i -> i != null && !i.isEmpty()).map(ItemStack::clone).toList();
        return real.isEmpty() ? null : ItemStack.serializeItemsAsBytes(real);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        if (land == null) return;
        BlockData d = b.getBlockData();
        // the gain comes with the drop event of this very break (container contents included: they are captured with it)
        logBlock(e.getPlayer(), land, b, Action.BREAK, d.getAsString(), contents(b.getState()), 1, null);
        Block other = partner(b, d);
        if (other != null && other.getType() == b.getType()) logBlock(e.getPlayer(), land, other, Action.BREAK, data(other), null, 0, null);
    }

    /** What a break really dropped: the gain of its line (nothing at all in creative, or without the right tool). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Block b = e.getBlock();
        for (Pending p : settling) {
            Row r = p.row;
            if (r.action() == Action.BREAK && r.amount() == 1 && r.actor().equals(e.getPlayer().getUniqueId()) && r.x() == b.getX() && r.y() == b.getY()
                    && r.z() == b.getZ() && r.world().equals(b.getWorld().getName())) {
                p.row = r.withGain(pack(e.getItems().stream().map(org.bukkit.entity.Item::getItemStack).toList()));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (core.vehicles() != null && core.vehicles().placingVehicle()) return;
        Player p = e.getPlayer();
        Block placed = e.getBlockPlaced();
        List<BlockState> replaced = e instanceof BlockMultiPlaceEvent m ? m.getReplacedBlockStates() : List.of(e.getBlockReplacedState());
        for (BlockState s : replaced) {
            Land land = watched(p, s.getLocation());
            // the block that used up the item is the primary line (an undo gives that item back, once)
            if (land != null) logBlock(p, land, s.getBlock(), Action.PLACE, s.getBlockData().getAsString(), null, s.getLocation().equals(placed.getLocation()) ? 1 : 0, null);
        }
        // a door's top half is set after the event: what is there now is what it replaces
        if (!(e instanceof BlockMultiPlaceEvent) && placed.getBlockData() instanceof Bisected && partner(placed, placed.getBlockData()) != null) {
            Block top = placed.getRelative(org.bukkit.block.BlockFace.UP);
            Land land = watched(p, top.getLocation());
            if (land != null) logBlock(p, land, top, Action.PLACE, data(top), null, 0, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        // a liquid is no item to give back: primary 0
        if (land != null) logBlock(e.getPlayer(), land, b, Action.PLACE, data(b), null, 0, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        ItemStack filled = e.getItemStack();
        if (filled != null && filled.getType() == Material.MILK_BUCKET) return;
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        // water is endless: scooping it gains nothing worth taking back; lava or powder snow does
        boolean gains = filled != null && filled.getType() != Material.WATER_BUCKET && filled.getType() != Material.BUCKET;
        if (land != null) logBlock(e.getPlayer(), land, b, Action.BUCKET, data(b), null, 1, gains ? pack(List.of(filled.asOne())) : null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() == null) return;
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        if (land != null) logBlock(e.getPlayer(), land, b, Action.PLACE, data(b), null, 0, null);
    }

    // containers: what they held when opened, against what they hold when closed

    private static Location containerAt(Inventory inv) {
        if (inv.getHolder(false) instanceof Container || inv.getHolder(false) instanceof org.bukkit.block.DoubleChest) return inv.getLocation();
        return null;
    }

    static String key(ItemStack it) {
        return Base64.getEncoder().encodeToString(it.asOne().serializeAsBytes());
    }

    private static Map<String, Integer> counts(Inventory inv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.isEmpty()) out.merge(key(it), it.getAmount(), Integer::sum);
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Location at = containerAt(e.getInventory());
        if (at == null) return;
        Land land = watched(p, at);
        if (land == null) return;
        open.put(p.getUniqueId(), new Open(at.toBlockLocation(), land.id, land.owner, counts(e.getInventory())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Open o = open.remove(p.getUniqueId());
        if (o == null) return;
        Map<String, Integer> diff = ContainerDiff.diff(o.before(), counts(e.getInventory()));
        if (diff.isEmpty()) return;
        Land land = eco.land().store().index().get(o.land());
        if (land == null) return;
        // others had it open too: what changed may be theirs as well (the line says so)
        String shared = e.getViewers().size() > 1 ? "shared" : null;
        diff.forEach((k, n) -> log(p, land, o.at(), n < 0 ? Action.TAKE : Action.PUT, shared, null, Base64.getDecoder().decode(k), Math.abs(n), null));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        endPreview(e.getPlayer().getUniqueId());
    }

    // entities: frames, paintings, armor stands, animals

    private static Player player(Entity damager) {
        return damager instanceof Player p ? p : damager instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
    }

    @SuppressWarnings("deprecation")
    private static byte[] entity(Entity en) {
        try {
            return Bukkit.getUnsafe().serializeEntity(en);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The item a frame, painting or armor stand is made from. */
    private static ItemStack itemOf(Entity en) {
        Material m = Material.matchMaterial(en.getType().name());
        return m != null && m.isItem() ? new ItemStack(m) : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = player(e.getRemover());
        if (p == null) return;
        Land land = watched(p, e.getEntity().getLocation());
        if (land == null) return;
        List<ItemStack> gain = new ArrayList<>();
        ItemStack self = itemOf(e.getEntity());
        if (self != null) gain.add(self);
        if (e.getEntity() instanceof ItemFrame f && f.getItem() != null && !f.getItem().isEmpty()) gain.add(f.getItem());
        log(p, land, e.getEntity().getLocation(), Action.ENTITY_BREAK, e.getEntity().getType().name(), null, entity(e.getEntity()), 0, pack(gain));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() == null) return;
        Land land = watched(e.getPlayer(), e.getEntity().getLocation());
        if (land != null) log(e.getPlayer(), land, e.getEntity().getLocation(), Action.ENTITY_PLACE, e.getEntity().getType().name(),
                e.getEntity().getUniqueId().toString(), null, 0, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() == null || !(e.getEntity() instanceof ArmorStand)) return;
        Land land = watched(e.getPlayer(), e.getEntity().getLocation());
        if (land != null) log(e.getPlayer(), land, e.getEntity().getLocation(), Action.ENTITY_PLACE, e.getEntity().getType().name(),
                e.getEntity().getUniqueId().toString(), null, 0, null);
    }

    /** A frame's item knocked out, and who last hit an animal or stand (for its death). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player p = player(e.getDamager());
        if (p == null) return;
        if (e.getEntity() instanceof ItemFrame f && f.getItem() != null && !f.getItem().isEmpty()) {
            Land land = watched(p, f.getLocation());
            if (land != null) log(p, land, f.getLocation(), Action.ENTITY_TAKE, f.getType().name(), f.getUniqueId().toString(), Items.bytes(f.getItem()),
                    f.getItem().getAmount(), null);
            return;
        }
        // an animal or stand is copied as it was before the blow (at its death it is already dying), with who struck it
        if (living(e.getEntity()) && watched(p, e.getEntity().getLocation()) != null) {
            hitBy.put(e.getEntity().getUniqueId(), new Hit(p.getUniqueId(), entity(e.getEntity()), now()));
        }
    }

    private static boolean living(Entity en) {
        return en instanceof ArmorStand || en instanceof Animals || en instanceof org.bukkit.entity.Villager;
    }

    /** An animal, villager or stand killed: brought back only if its drops are taken back (meat, leather, the stand's gear). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity en = e.getEntity();
        if (!living(en)) return;
        Hit hit = hitBy.remove(en.getUniqueId());
        Player p = en.getKiller() != null ? en.getKiller() : hit == null ? null : Bukkit.getPlayer(hit.player());
        if (p == null) return;
        Land land = watched(p, en.getLocation());
        if (land != null) log(p, land, en.getLocation(), Action.ENTITY_BREAK, en.getType().name(), null, hit != null ? hit.copy() : null, 0, pack(e.getDrops()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        ItemStack took = e.getArmorStandItem();
        if (took == null || took.isEmpty()) return;
        Land land = watched(e.getPlayer(), e.getRightClicked().getLocation());
        if (land != null) log(e.getPlayer(), land, e.getRightClicked().getLocation(), Action.ENTITY_TAKE, e.getRightClicked().getType().name(), null,
                Items.bytes(took), took.getAmount(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent e) {
        Location l = e.getFurniture().location();
        Land land = watched(e.getPlayer(), l);
        if (land != null) log(e.getPlayer(), land, l, Action.FURNITURE_BREAK, e.getFurniture().item().key().toString(), null,
                Items.bytes(e.getFurniture().item().create(1)), 1, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent e) {
        Location l = e.getFurniture().location();
        Land land = watched(e.getPlayer(), l);
        if (land != null) log(e.getPlayer(), land, l, Action.FURNITURE_PLACE, e.getFurniture().item().key().toString(), null, null, 1, null);
    }

    // ------------------------------------------------------------------------------------------
    // Undoing: take back what they gained, then put back that much
    // ------------------------------------------------------------------------------------------

    /** How an undo went. */
    public static final class Result {
        public int blocks, blocksSkipped, itemsBack, itemsMailed, itemsRemoved, entities, entitiesSkipped, furnitureMailed, notUndone;
        /** items taken back from the person; items given back to them (blocks they placed, things they put in) */
        public int confiscated, refunded;
        /** items still to be taken back (their lines wait) */
        public int owed;
        final List<Long> handled = new ArrayList<>();
        final List<Long> waiting = new ArrayList<>();
        final Map<Long, Integer> partly = new HashMap<>();

        public String text() {
            return Lang.raw("land-log.result", "blocks", blocks, "skipped", blocksSkipped, "back", itemsBack, "mailed", itemsMailed + furnitureMailed,
                    "removed", itemsRemoved, "entities", entities, "other", entitiesSkipped + notUndone, "confiscated", confiscated, "refunded", refunded,
                    "owed", owed);
        }
    }

    private static boolean blockLine(Row r) {
        return r.action() == Action.BREAK || r.action() == Action.PLACE || r.action() == Action.BUCKET;
    }

    /** One thing an undo puts back: a folded block, or one other line; and what the person gained by it. */
    private record Unit(long id, Confiscation.Kind kind, Map<String, Integer> gain, UndoPlan.Step step, List<Row> rows) {}

    /** Item keys of gains -> one item of that kind (to find and remove in an inventory). */
    private final Map<String, ItemStack> protos = new HashMap<>();

    private static final String CUSTOM = "custom:";

    private Map<String, Integer> gainOf(Row r) {
        Map<String, Integer> g = new LinkedHashMap<>();
        switch (r.action()) {
            case TAKE, ENTITY_TAKE -> {
                if (r.item() != null) {
                    ItemStack it = Items.fromBytes(r.item());
                    String k = key(it);
                    protos.putIfAbsent(k, it.asOne());
                    g.merge(k, r.amount(), Integer::sum);
                }
            }
            case FURNITURE_BREAK -> {
                // whatever furniture item it dropped (its size, binding may differ): the same furniture
                String k = CUSTOM + r.before();
                if (r.item() != null) protos.putIfAbsent(k, Items.fromBytes(r.item()).asOne());
                g.merge(k, 1, Integer::sum);
            }
            default -> {
                if (r.gain() != null) {
                    for (ItemStack it : ItemStack.deserializeItemsFromBytes(r.gain())) {
                        if (it == null || it.isEmpty()) continue;
                        String k = key(it);
                        protos.putIfAbsent(k, it.asOne());
                        g.merge(k, it.getAmount(), Integer::sum);
                    }
                }
            }
        }
        return g;
    }

    /** The units of these lines: blocks folded per place (only those still as the person left them), then the rest. */
    private List<Unit> units(List<Row> rows, List<Long> skipped) {
        Map<Long, Row> byId = new HashMap<>();
        for (Row r : rows) byId.put(r.id(), r);
        List<UndoPlan.Change> changes = new ArrayList<>();
        for (Row r : rows) {
            if (blockLine(r) && r.after() != null) changes.add(new UndoPlan.Change(r.id(), r.world(), r.x(), r.y(), r.z(), r.before(), r.after()));
            else if (blockLine(r)) skipped.add(r.id());
        }
        List<Unit> out = new ArrayList<>();
        java.util.Set<Long> inSteps = new java.util.HashSet<>();
        for (UndoPlan.Step s : UndoPlan.fold(changes)) {
            inSteps.addAll(s.ids());
            World w = Bukkit.getWorld(s.world());
            if (w == null || !UndoPlan.applies(s, data(w.getBlockAt(s.x(), s.y(), s.z())))) {
                skipped.addAll(s.ids());       // changed since (later work wins): nothing taken, nothing put back
                continue;
            }
            List<Row> its = s.ids().stream().map(byId::get).toList();
            Map<String, Integer> g = new LinkedHashMap<>();
            for (Row r : its) gainOf(r).forEach((k, n) -> g.merge(k, n, Integer::sum));
            out.add(new Unit(s.first(), Confiscation.Kind.WHOLE, g, s, its));
        }
        // a block they changed and changed back themselves: folded away, nothing to do
        for (Row r : rows) if (blockLine(r) && r.after() != null && !inSteps.contains(r.id())) skipped.add(r.id());
        for (Row r : rows) {
            if (blockLine(r)) continue;
            Confiscation.Kind kind = r.action() == Action.TAKE ? Confiscation.Kind.COUNT : Confiscation.Kind.WHOLE;
            out.add(new Unit(r.id(), kind, gainOf(r), null, List.of(r)));
        }
        return out;
    }

    private boolean matches(String key, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (key.startsWith(CUSTOM)) {
            String id = key.substring(CUSTOM.length());
            return core.content().itemOf(stack).map(ci -> ci.key().toString().equals(id)).orElse(false);
        }
        ItemStack proto = protos.get(key);
        return proto != null && proto.isSimilar(stack);
    }

    /** How many of each kind the person holds (inventory, armor, off hand, ender chest). */
    private Map<String, Integer> held(Player p, java.util.Set<String> keys) {
        Map<String, Integer> out = new HashMap<>();
        for (Inventory inv : List.of(p.getInventory(), p.getEnderChest())) {
            for (ItemStack it : inv.getContents()) {
                if (it == null || it.isEmpty()) continue;
                for (String k : keys) {
                    if (matches(k, it)) {
                        out.merge(k, it.getAmount(), Integer::sum);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Takes so many of a kind out of the person's inventory, then their ender chest. */
    private int confiscate(Player p, String key, int amount) {
        int left = amount;
        for (Inventory inv : List.of(p.getInventory(), p.getEnderChest())) {
            ItemStack[] all = inv.getContents();
            for (int i = 0; i < all.length && left > 0; i++) {
                if (!matches(key, all[i])) continue;
                int t = Math.min(left, all[i].getAmount());
                all[i].setAmount(all[i].getAmount() - t);
                if (all[i].getAmount() <= 0) all[i] = null;
                left -= t;
            }
            inv.setContents(all);
        }
        return amount - left;
    }

    /**
     * Undoes these lines of one person: what they gained is taken back first (if they are online: else it waits for
     * their next join, unless forced), then what was taken back goes back, over several ticks. The lines are marked
     * done, waiting (still owed) or with a smaller amount (a container take partly undone).
     */
    private CompletableFuture<Result> undo(UUID actor, List<Row> rows, boolean forced) {
        CompletableFuture<Result> done = new CompletableFuture<>();
        Result res = new Result();
        List<Long> skipped = new ArrayList<>();
        List<Unit> units = units(rows, skipped);
        res.blocksSkipped += (int) rows.stream().filter(r -> skipped.contains(r.id()) && r.amount() == 1 && blockLine(r)).count();
        res.handled.addAll(skipped);
        Player p = Bukkit.getPlayer(actor);
        boolean online = p != null && p.isOnline() && !p.isDead();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Unit u : units) keys.addAll(u.gain().keySet());
        Map<String, Integer> held = online ? held(p, keys) : Map.of();
        List<Confiscation.Result> plan = Confiscation.plan(units.stream().map(u -> new Confiscation.Unit(u.id(), u.kind(), u.gain())).toList(), held, forced);
        Map<Long, Unit> byId = new HashMap<>();
        for (Unit u : units) byId.put(u.id(), u);
        List<Runnable> work = new ArrayList<>();
        for (Confiscation.Result c : plan) {
            Unit u = byId.get(c.id());
            if (online) c.take().forEach((k, n) -> res.confiscated += confiscate(p, k, n));
            int owed = c.owed().values().stream().mapToInt(Integer::intValue).sum();
            res.owed += owed;
            if (!c.restore()) {
                for (Row r : u.rows()) res.waiting.add(r.id());
                continue;
            }
            if (u.kind() == Confiscation.Kind.COUNT && owed > 0) res.partly.put(u.id(), owed);
            else for (Row r : u.rows()) res.handled.add(r.id());
            work.add(() -> restore(u, c, actor, res));
        }
        int per = Math.max(50, eco.getConfig().getInt("land-log.undo-blocks-per-tick"));
        int[] at = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(eco, () -> {
            undoing = true;
            try {
                for (int i = 0; i < per && at[0] < work.size(); i++, at[0]++) {
                    try {
                        work.get(at[0]).run();
                    } catch (RuntimeException ex) {
                        res.notUndone++;
                        eco.getLogger().log(Level.WARNING, "땅 기록: 되돌리기 한 건 실패", ex);
                    }
                }
            } finally {
                undoing = false;
            }
            if (at[0] >= work.size()) {
                task[0].cancel();
                CompletableFuture<?> marks = CompletableFuture.allOf(store.undone(res.handled), store.waiting(res.waiting),
                        CompletableFuture.allOf(res.partly.entrySet().stream().map(en -> store.owe(en.getKey(), en.getValue())).toArray(CompletableFuture[]::new)));
                marks.whenComplete((v, err) -> {
                    if (err != null) eco.getLogger().log(Level.WARNING, "땅 기록: 되돌린 표시를 쓰지 못함", err);
                    done.complete(res);
                });
            }
        }, 1, 1);
        return done;
    }

    /** Puts one unit back: a block (and a broken container's contents), container items (so many), an entity... */
    private void restore(Unit u, Confiscation.Result c, UUID actor, Result res) {
        if (u.step() != null) {
            restoreBlock(u, actor, res);
            return;
        }
        Row r = u.rows().getFirst();
        World w = Bukkit.getWorld(r.world());
        Location at = w == null ? null : new Location(w, r.x(), r.y(), r.z());
        switch (r.action()) {
            case TAKE -> putBack(r, at, c.restoreCount(), res);
            case PUT -> {
                // what they put in comes out again, and goes back to them: it was theirs
                Block b = at == null ? null : at.getBlock();
                if (b != null && b.getState(false) instanceof Container ct) {
                    ItemStack it = Items.fromBytes(r.item());
                    String k = key(it);
                    int left = r.amount();
                    ItemStack[] all = ct.getInventory().getContents();
                    for (int i = 0; i < all.length && left > 0; i++) {
                        if (all[i] == null || all[i].isEmpty() || !key(all[i]).equals(k)) continue;
                        int take = Math.min(left, all[i].getAmount());
                        ItemStack out = all[i].clone();
                        out.setAmount(take);
                        all[i].setAmount(all[i].getAmount() - take);
                        left -= take;
                        res.itemsRemoved += take;
                        refund(actor, out, res);
                    }
                    ct.getInventory().setContents(all);
                }
            }
            case ENTITY_BREAK -> respawn(r, w, at, res);
            case ENTITY_PLACE -> {
                Entity en = r.after() == null ? null : Bukkit.getEntity(UUID.fromString(r.after()));
                if (en == null || !en.isValid()) {
                    res.entitiesSkipped++;
                    return;
                }
                // what hangs in it or is worn by it was the land's; the frame or stand itself was theirs
                if (en instanceof ItemFrame f && f.getItem() != null && !f.getItem().isEmpty()) mailOwner(r, f.getItem(), res);
                if (en instanceof ArmorStand st) for (ItemStack it : st.getEquipment().getArmorContents()) if (it != null && !it.isEmpty()) mailOwner(r, it, res);
                ItemStack self = itemOf(en);
                en.remove();
                res.entities++;
                if (self != null) refund(actor, self, res);
            }
            case ENTITY_TAKE -> {
                ItemStack it = Items.fromBytes(r.item());
                Entity frame = r.after() == null ? null : Bukkit.getEntity(UUID.fromString(r.after()));
                if (frame instanceof ItemFrame f && (f.getItem() == null || f.getItem().isEmpty())) {
                    f.setItem(it, false);
                    res.itemsBack += it.getAmount();
                } else mailOwner(r, it, res);
            }
            case FURNITURE_BREAK -> {
                if (r.item() == null) res.notUndone++;
                else {
                    mailOwner(r, Items.fromBytes(r.item()), res);
                    res.furnitureMailed++;
                }
            }
            default -> res.notUndone++;   // furniture placed: taken down by the owner (it is theirs to break in their land)
        }
    }

    private void restoreBlock(Unit u, UUID actor, Result res) {
        UndoPlan.Step s = u.step();
        World w = Bukkit.getWorld(s.world());
        Block b = w == null ? null : w.getBlockAt(s.x(), s.y(), s.z());
        if (b == null || !UndoPlan.applies(s, data(b))) {
            // changed in the few ticks since it was taken back: what was taken goes back to them
            res.blocksSkipped++;
            giveBackGain(u, actor, res);
            return;
        }
        BlockData d;
        try {
            d = Bukkit.createBlockData(s.restore() == null ? "minecraft:air" : s.restore());
        } catch (IllegalArgumentException ex) {
            res.blocksSkipped++;
            giveBackGain(u, actor, res);
            return;
        }
        b.setBlockData(d, false);
        res.blocks++;
        Row first = u.rows().getFirst(), last = u.rows().getLast();
        // a broken container: what was in it goes back in (its drops, contents included, were taken back above)
        if (first.action() == Action.BREAK && first.item() != null && b.getState(false) instanceof Container c) {
            for (ItemStack it : ItemStack.deserializeItemsFromBytes(first.item())) {
                for (ItemStack left : c.getInventory().addItem(it).values()) mailOwner(first, left, res);
            }
        }
        // the block they left there and this undo removes was theirs (they placed it): back to them
        if (last.action() == Action.PLACE && last.amount() == 1) {
            try {
                Material m = Bukkit.createBlockData(s.expect()).getPlacementMaterial();
                if (m.isItem() && !m.isAir()) refund(actor, new ItemStack(m), res);
            } catch (IllegalArgumentException ignored) {
            }
        }
        // a bucket of lava or powder snow taken back: the empty bucket is theirs
        if (first.action() == Action.BUCKET && first.gain() != null) refund(actor, new ItemStack(Material.BUCKET), res);
    }

    private void giveBackGain(Unit u, UUID actor, Result res) {
        for (Map.Entry<String, Integer> e : u.gain().entrySet()) {
            ItemStack proto = protos.get(e.getKey());
            if (proto == null) continue;
            ItemStack it = proto.clone();
            it.setAmount(e.getValue());
            refund(actor, it, res);
        }
    }

    private void putBack(Row r, Location at, int count, Result res) {
        if (count <= 0) return;
        ItemStack it = Items.fromBytes(r.item());
        it.setAmount(1);
        int left = count;
        Block b = at == null ? null : at.getBlock();
        Container c = b != null && b.getState(false) instanceof Container ct ? ct : null;
        while (left > 0) {
            ItemStack part = it.clone();
            part.setAmount(Math.min(left, it.getMaxStackSize()));
            left -= part.getAmount();
            int over = c == null ? part.getAmount() : c.getInventory().addItem(part).values().stream().mapToInt(ItemStack::getAmount).sum();
            res.itemsBack += part.getAmount() - over;
            if (over > 0) {
                ItemStack rest = it.clone();
                rest.setAmount(over);
                mailOwner(r, rest, res);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void respawn(Row r, World w, Location at, Result res) {
        if (at == null || r.item() == null) {
            res.entitiesSkipped++;
            return;
        }
        try {
            Entity en = Bukkit.getUnsafe().deserializeEntity(r.item(), w, false);
            if (en instanceof Hanging) {
                // a frame or painting already back at that spot: do not stack a second one
                boolean taken = !w.getNearbyEntities(at.clone().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6, x -> x.getType() == en.getType()).isEmpty();
                if (taken) {
                    res.entitiesSkipped++;
                    return;
                }
            }
            // where it was (a frame's own spot on its wall); the logged block only if the copy has none
            Location spawn = en.getLocation().getWorld() != null ? en.getLocation() : at.clone().add(0.5, 0, 0.5);
            en.spawnAt(spawn);
            if (en instanceof LivingEntity le && le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null) {
                le.setHealth(le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
            }
            res.entities++;
        } catch (RuntimeException ex) {
            res.entitiesSkipped++;
        }
    }

    /** To the land's owner (what cannot go back where it was). The server's land has nobody to send to: logged. */
    private void mailOwner(Row r, ItemStack it, Result res) {
        if (r.owner() == null) {
            res.notUndone++;
            eco.getLogger().info("땅 기록: 운영자 땅에서 되돌린 물건을 줄 곳이 없음: " + it.getType() + " x" + it.getAmount() + " (" + r.world() + " " + r.x() + "," + r.y() + "," + r.z() + ")");
            return;
        }
        eco.mail().deliver(r.owner(), it, "land_undo");
        res.itemsMailed += it.getAmount();
    }

    /** Back to the person who did it (what the undo removes that they paid for). */
    private void refund(UUID actor, ItemStack it, Result res) {
        eco.mail().deliver(actor, it, "land_undo_refund");
        res.refunded += it.getAmount();
    }

    /** Lines waiting for things to be taken back are tried again when that person joins. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        store.hasWaiting(u).thenAccept(has -> {
            if (has) Bukkit.getScheduler().runTaskLater(eco, () -> retry(u), 60);
        });
    }

    private void retry(UUID actor) {
        Player p = Bukkit.getPlayer(actor);
        if (p == null) return;
        settle();
        flush();
        store.waitingOf(actor, -1).thenAccept(rows -> sync(() -> {
            if (rows.isEmpty() || !p.isOnline()) return;
            undo(actor, rows, false).thenAccept(res -> sync(() -> {
                eco.getLogger().info("[땅 기록] " + p.getName() + " 접속: 기다리던 되돌리기 " + rows.size() + "줄 다시 시도: " + res.text());
                if (res.confiscated > 0 && p.isOnline()) p.sendMessage(Lang.msg("land-log.confiscated-you", "count", res.confiscated));
                for (UUID owner : rows.stream().map(Row::owner).filter(java.util.Objects::nonNull).distinct().toList()) {
                    Player o = Bukkit.getPlayer(owner);
                    if (o != null && res.confiscated > 0) o.sendMessage(Lang.msg("land-log.retried", "player", p.getName(), "result", res.text()));
                }
            }));
        }));
    }

    // ------------------------------------------------------------------------------------------
    // /땅 기록
    // ------------------------------------------------------------------------------------------

    private DateTimeFormatter clock() {
        return DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.of(eco.getConfig().getString("time.zone")));
    }

    private String when(long at) {
        return clock().format(Instant.ofEpochMilli(at));
    }

    private Land owned(Player p) {
        Land l = eco.land().store().index().ownedBy(p.getUniqueId());
        if (l == null) p.sendMessage(Lang.msg("land-log.not-owner"));
        return l;
    }

    /** /땅 기록 [이름 [쪽 | 되돌리기 [시간]] | 확정 | 취소] */
    public void command(Player p, String[] a) {
        if (a.length >= 1 && (a[0].equals("확정") || a[0].equals("confirm"))) {
            confirm(p);
            return;
        }
        if (a.length >= 1 && (a[0].equals("취소") || a[0].equals("cancel"))) {
            if (previews.containsKey(p.getUniqueId())) {
                endPreview(p.getUniqueId());
                p.sendMessage(Lang.msg("land-log.cancelled"));
            }
            return;
        }
        Land l = owned(p);
        if (l == null) return;
        if (a.length == 0) {
            summary(p, l);
            return;
        }
        Players.find(a[0], found -> {
            if (found.isEmpty()) {
                p.sendMessage(Lang.msg("land.no-player"));
                return;
            }
            UUID who = found.get();
            if (a.length >= 2 && (a[1].equals("되돌리기") || a[1].equals("undo"))) {
                long since = 0;
                if (a.length >= 3) {
                    long ms = Durations.parse(a[2]);
                    if (ms < 0) {
                        p.sendMessage(Lang.msg("land-log.bad-time", "time", a[2]));
                        return;
                    }
                    since = now() - ms;
                }
                if (who.equals(p.getUniqueId())) {
                    p.sendMessage(Lang.msg("land-log.not-self"));
                    return;
                }
                preview(p, l, who, Players.name(who), since);
                return;
            }
            int page = 1;
            if (a.length >= 2) {
                try {
                    page = Math.max(1, Integer.parseInt(a[1]));
                } catch (NumberFormatException ignored) {
                }
            }
            detail(p, l.id, who, page);
        });
    }

    private static String label(Action a) {
        return Lang.raw("land-log.action." + a.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Items still owed by waiting lines, per person. */
    private Map<UUID, Integer> owedBy(List<Row> waiting) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Row r : waiting) out.merge(r.actor(), gainOf(r).values().stream().mapToInt(Integer::intValue).sum(), Integer::sum);
        return out;
    }

    private void summary(Player p, Land l) {
        long since = now() - eco.getConfig().getInt("land-log.keep-days") * 86_400_000L;
        store.tally(l.id, since).thenCombine(store.waitingIn(l.id), (rows, waiting) -> {
            sync(() -> {
                if (rows.isEmpty()) {
                    p.sendMessage(Lang.msg("land-log.empty"));
                    return;
                }
                Map<UUID, Integer> owed = owedBy(waiting);
                Map<UUID, List<LandLogStore.Tally>> per = new LinkedHashMap<>();
                for (LandLogStore.Tally t : rows) per.computeIfAbsent(t.actor(), k -> new ArrayList<>()).add(t);
                p.sendMessage(Lang.msg("land-log.head", "days", eco.getConfig().getInt("land-log.keep-days")));
                per.forEach((who, ts) -> {
                    Map<Action, Long> n = new EnumMap<>(Action.class);
                    long first = Long.MAX_VALUE, last = 0;
                    String name = ts.getFirst().name();
                    for (LandLogStore.Tally t : ts) {
                        n.merge(t.action(), t.count(), Long::sum);
                        first = Math.min(first, t.first());
                        last = Math.max(last, t.last());
                    }
                    List<String> parts = new ArrayList<>();
                    n.forEach((act, c) -> parts.add(label(act) + " " + c));
                    p.sendMessage(Lang.mm("land-log.person", "player", name, "what", String.join(" · ", parts), "first", when(first), "last", when(last)));
                    int o = owed.getOrDefault(who, 0);
                    if (o > 0) p.sendMessage(Lang.mm("land-log.person-owed", "count", o));
                });
            });
            return null;
        });
    }

    private void detail(CommandSender s, long land, UUID who, int page) {
        int per = 10;
        store.recent(who, land, 0, (page - 1) * per, per).thenAccept(rows -> sync(() -> {
            String name = Players.name(who);
            s.sendMessage(Lang.msg("land-log.detail-head", "player", name, "page", page));
            if (rows.isEmpty()) s.sendMessage(Lang.msg("land-log.empty"));
            for (Row r : rows) {
                s.sendMessage(Lang.mm(r.waiting() ? "land-log.line-owed" : "land-log.line", "when", when(r.at()), "what", line(r), "x", r.x(), "y", r.y(), "z", r.z()));
            }
            // the buttons are the owner's commands (an administrator pages with /관리자 땅기록)
            if (rows.size() == per && land >= 0) s.sendMessage(Lang.mm("land-log.more", "player", name, "next", page + 1));
        }));
    }

    /** "부숨 참나무 판자", "꺼냄 다이아몬드 x3". */
    private static String line(Row r) {
        String thing = switch (r.action()) {
            case BREAK, BUCKET -> shortBlock(r.before());
            case PLACE -> shortBlock(r.after());
            case TAKE, PUT, ENTITY_TAKE -> {
                try {
                    yield Items.plainName(Items.fromBytes(r.item())) + " x" + r.amount();
                } catch (RuntimeException ex) {
                    yield "? x" + r.amount();
                }
            }
            default -> r.before() == null ? "" : r.before().toLowerCase(java.util.Locale.ROOT);
        };
        return label(r.action()) + " " + thing;
    }

    private static String shortBlock(String data) {
        if (data == null) return "";
        String s = data.startsWith("minecraft:") ? data.substring(10) : data;
        int br = s.indexOf('[');
        return br < 0 ? s : s.substring(0, br);
    }

    // preview and confirm

    private void preview(Player p, Land l, UUID who, String name, long since) {
        endPreview(p.getUniqueId());
        settle();
        flush();
        // the lines just written are read back a tick later
        Bukkit.getScheduler().runTaskLater(eco, () -> store.toUndo(who, l.id, since).thenAccept(rows -> sync(() -> {
            if (!p.isOnline()) return;
            if (rows.isEmpty()) {
                p.sendMessage(Lang.msg("land-log.nothing", "player", name));
                return;
            }
            // what would go back now: only units whose gain they hold (or that gained nothing)
            List<Long> skipped = new ArrayList<>();
            List<Unit> units = units(rows, skipped);
            Player actor = Bukkit.getPlayer(who);
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (Unit u : units) keys.addAll(u.gain().keySet());
            Map<String, Integer> held = actor != null ? held(actor, keys) : Map.of();
            List<Confiscation.Result> plan = Confiscation.plan(units.stream().map(u -> new Confiscation.Unit(u.id(), u.kind(), u.gain())).toList(), held, false);
            Map<Long, Unit> byId = new HashMap<>();
            for (Unit u : units) byId.put(u.id(), u);
            List<UndoPlan.Step> shownSteps = new ArrayList<>();
            int blocks = 0, take = 0, owed = 0, items = 0;
            int max = eco.getConfig().getInt("land-log.preview-max-blocks");
            for (Confiscation.Result c : plan) {
                Unit u = byId.get(c.id());
                take += c.take().values().stream().mapToInt(Integer::intValue).sum();
                owed += c.owed().values().stream().mapToInt(Integer::intValue).sum();
                if (!c.restore()) continue;
                if (u.kind() == Confiscation.Kind.COUNT) items += c.restoreCount();
                if (u.step() == null) continue;
                blocks++;
                UndoPlan.Step s = u.step();
                World sw = Bukkit.getWorld(s.world());
                if (sw != p.getWorld() || shownSteps.size() >= max) continue;
                Location loc = new Location(sw, s.x(), s.y(), s.z());
                if (loc.distanceSquared(p.getLocation()) > 160 * 160) continue;
                try {
                    p.sendBlockChange(loc, Bukkit.createBlockData(s.restore() == null ? "minecraft:air" : s.restore()));
                    shownSteps.add(s);
                } catch (IllegalArgumentException ignored) {
                }
            }
            int seconds = eco.getConfig().getInt("land-log.preview-seconds");
            previews.put(p.getUniqueId(), new Preview(who, name, l.id, since, rows, shownSteps, p.getWorld(), now() + seconds * 1000L));
            p.sendMessage(Lang.msg("land-log.preview", "player", name, "blocks", blocks, "skipped", skipped.size(), "items", items, "take", take, "owed", owed,
                    "lines", rows.size(), "seconds", seconds, "since", since <= 0 ? Lang.raw("land-log.since-all") : when(since)));
            if (owed > 0) p.sendMessage(Lang.msg(actor != null ? "land-log.preview-owed" : "land-log.preview-offline", "player", name, "owed", owed));
            p.sendMessage(Lang.mm("land-log.preview-buttons"));
        })), 2);
    }

    /** The preview's blocks back to what is really there (the owner's client). */
    private void endPreview(UUID owner) {
        Preview pv = previews.remove(owner);
        Player p = Bukkit.getPlayer(owner);
        if (pv == null || p == null) return;
        for (UndoPlan.Step s : pv.steps()) {
            World sw = Bukkit.getWorld(s.world());
            if (sw == null || sw != p.getWorld()) continue;
            Block b = sw.getBlockAt(s.x(), s.y(), s.z());
            p.sendBlockChange(b.getLocation(), b.getBlockData());
        }
    }

    private void expirePreviews() {
        long now = now();
        hitBy.values().removeIf(h -> now - h.at() > 30_000);
        for (Map.Entry<UUID, Preview> e : List.copyOf(previews.entrySet())) {
            if (e.getValue().until() < now) {
                endPreview(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) p.sendMessage(Lang.msg("land-log.expired"));
            }
        }
    }

    private void confirm(Player p) {
        Preview pv = previews.get(p.getUniqueId());
        if (pv == null) {
            p.sendMessage(Lang.msg("land-log.no-preview"));
            return;
        }
        endPreview(p.getUniqueId());
        Land l = eco.land().store().index().get(pv.land());
        if (l == null || !l.isOwner(p.getUniqueId())) {
            p.sendMessage(Lang.msg("land-log.not-owner"));
            return;
        }
        p.sendMessage(Lang.msg("land-log.undoing", "player", pv.actorName()));
        // read again: what was undone or written meanwhile counts
        store.toUndo(pv.actor(), pv.land(), pv.since()).thenAccept(rows -> sync(() -> undo(pv.actor(), rows, false).thenAccept(res -> sync(() -> {
            eco.getLogger().info("[땅 기록] " + p.getName() + " 이(가) 땅 #" + pv.land() + " 에서 " + pv.actorName() + " 의 " + rows.size() + "줄을 되돌림: " + res.text());
            if (p.isOnline()) {
                p.sendMessage(Lang.msg("land-log.undone", "player", pv.actorName(), "result", res.text()));
                if (res.owed > 0) p.sendMessage(Lang.msg("land-log.still-owed", "player", pv.actorName(), "owed", res.owed));
            }
            Player actor = Bukkit.getPlayer(pv.actor());
            if (actor != null && res.confiscated > 0) actor.sendMessage(Lang.msg("land-log.confiscated-you", "count", res.confiscated));
        }))));
    }

    // ------------------------------------------------------------------------------------------
    // Administrators: /관리자 땅기록, /관리자 테러
    // ------------------------------------------------------------------------------------------

    /** /관리자 땅기록 <플레이어> [시간] */
    public void adminLog(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(Lang.msg("land-log.admin-usage"));
            return;
        }
        long ms = a.length >= 2 ? Durations.parse(a[1]) : eco.getConfig().getInt("land-log.keep-days") * 86_400_000L;
        if (ms < 0) {
            s.sendMessage(Lang.msg("land-log.bad-time", "time", a[1]));
            return;
        }
        long since = now() - ms;
        Players.find(a[0], found -> {
            if (found.isEmpty()) {
                s.sendMessage(Lang.msg("land.no-player"));
                return;
            }
            UUID who = found.get();
            settle();
            flush();
            store.byLand(who, since).thenCombine(store.waitingOf(who, -1), (rows, waiting) -> {
                sync(() -> {
                    s.sendMessage(Lang.msg("land-log.admin-head", "player", Players.name(who), "time", Durations.text(ms)));
                    if (rows.isEmpty()) s.sendMessage(Lang.msg("land-log.empty"));
                    Map<Long, List<Object[]>> per = new LinkedHashMap<>();
                    for (Object[] r : rows) per.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(r);
                    per.forEach((land, rs) -> {
                        List<String> parts = new ArrayList<>();
                        for (Object[] r : rs) parts.add(label((Action) r[2]) + " " + r[3]);
                        String owner = rs.getFirst()[1] == null ? Lang.raw("land.server") : Players.name(UUID.fromString((String) rs.getFirst()[1]));
                        s.sendMessage(Lang.mm("land-log.admin-land", "land", land, "owner", owner, "what", String.join(" · ", parts)));
                    });
                    int owed = owedBy(waiting).getOrDefault(who, 0);
                    if (owed > 0) s.sendMessage(Lang.msg("land-log.admin-owed", "player", Players.name(who), "owed", owed, "lines", waiting.size()));
                    detail(s, -1, who, 1);
                });
                return null;
            });
        });
    }

    /** /관리자 테러 <플레이어> <시간> [확정] [강제] */
    public void grief(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage(Lang.msg("land-log.grief-usage"));
            return;
        }
        long ms = Durations.parse(a[1]);
        if (ms < 0) {
            s.sendMessage(Lang.msg("land-log.bad-time", "time", a[1]));
            return;
        }
        List<String> words = List.of(a).subList(2, a.length);
        boolean go = words.contains("확정") || words.contains("confirm");
        boolean forced = words.contains("강제") || words.contains("force");
        long since = now() - ms;
        String admin = s instanceof Player p ? p.getName() : "console";
        Players.find(a[0], found -> {
            if (found.isEmpty()) {
                s.sendMessage(Lang.msg("land.no-player"));
                return;
            }
            UUID who = found.get();
            String name = Players.name(who);
            settle();
            flush();
            store.toUndo(who, -1, since).thenCombine(store.payments(who, since), (rows, pays) -> {
                sync(() -> {
                    long balance = core.economy().balance(who);
                    List<MoneyReturn.Back> plan = MoneyReturn.plan(pays, balance);
                    long paid = pays.stream().mapToLong(MoneyReturn.Payment::amount).sum();
                    long back = plan.stream().mapToLong(MoneyReturn.Back::returned).sum();
                    long blocks = rows.stream().filter(r -> blockLine(r) && r.amount() == 1).count();
                    long taken = rows.stream().filter(r -> r.action() == Action.TAKE || r.action() == Action.ENTITY_TAKE).mapToLong(Row::amount).sum();
                    long lands = rows.stream().map(Row::land).distinct().count();
                    long senders = pays.stream().map(MoneyReturn.Payment::from).distinct().count();
                    boolean online = Bukkit.getPlayer(who) != null;
                    s.sendMessage(Lang.msg("land-log.grief-summary", "player", name, "time", Durations.text(ms), "blocks", blocks, "lands", lands, "items", taken,
                            "paid", EconomyModule.format(paid), "payments", pays.size(), "senders", senders, "balance", EconomyModule.format(balance),
                            "back", EconomyModule.format(back), "short", EconomyModule.format(paid - back)));
                    s.sendMessage(Lang.msg(online ? "land-log.grief-online" : "land-log.grief-offline", "player", name));
                    if (!go) {
                        s.sendMessage(Lang.mm("land-log.grief-confirm", "player", name, "time", a[1]));
                        return;
                    }
                    // 1. the undo first, while they may still be online (what they took is taken back from them)
                    undo(who, rows, forced).thenAccept(res -> sync(() -> {
                        // 2. out: banned (for good, until an administrator lifts it) and kicked
                        Bukkit.getOfflinePlayer(who).ban(Lang.raw("land-log.ban-reason"), (java.util.Date) null, admin);
                        Player online2 = Bukkit.getPlayer(who);
                        if (online2 != null) online2.kick(Lang.mm("land-log.kick"));
                        // 3. the money back, as far as it goes
                        List<MoneyReturn.Back> done = new ArrayList<>();
                        long returned = 0;
                        for (MoneyReturn.Back b : plan) {
                            if (b.returned() > 0 && !core.economy().move(who, b.payment().from(), b.returned(), EconomyStore.Kind.ADMIN, "grief_return",
                                    "ledger #" + b.payment().entry())) {
                                done.add(new MoneyReturn.Back(b.payment(), 0, b.payment().amount()));
                                continue;
                            }
                            done.add(b);
                            returned += b.returned();
                            if (b.returned() > 0) {
                                Player to = Bukkit.getPlayer(b.payment().from());
                                if (to != null) to.sendMessage(Lang.msg("land-log.money-back", "amount", EconomyModule.format(b.returned()), "player", name));
                            }
                        }
                        store.returned(done, who, now());
                        String summary = (forced ? "FORCED; " : "") + "banned; " + res.text() + "; money " + returned + "/" + paid;
                        store.audit(admin, who, name, since, summary, now());
                        eco.getLogger().warning("[테러 처리] " + admin + " → " + name + " (" + Durations.text(ms) + (forced ? ", 강제" : "") + "): " + summary);
                        s.sendMessage(Lang.msg("land-log.grief-done", "player", name, "result", res.text(), "back", EconomyModule.format(returned),
                                "short", EconomyModule.format(paid - returned)));
                        if (res.owed > 0) s.sendMessage(Lang.mm("land-log.grief-owed", "player", name, "owed", res.owed, "time", a[1]));
                        for (UUID owner : rows.stream().map(Row::owner).filter(java.util.Objects::nonNull).distinct().toList()) {
                            Player o = Bukkit.getPlayer(owner);
                            if (o != null) o.sendMessage(Lang.msg("land-log.owner-told", "player", name));
                        }
                    }));
                });
                return null;
            }).exceptionally(err -> {
                eco.getLogger().log(Level.WARNING, "테러 처리 실패", err);
                sync(() -> s.sendMessage(Lang.msg("land-log.error")));
                return null;
            });
        });
    }
}
