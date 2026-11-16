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
 * it. The owner's own work is not written (config land-log.owner), which keeps the log small. Lines are queued on the
 * main thread and written in one batch a second; a block's state after the change is read on the next tick (a door's
 * top half, a bed's foot appear only then).
 *
 * <p><b>Undo.</b> The owner looks at who did what (/땅 기록), picks a person and asks to undo what they did (all of it,
 * or since a time). First a preview: the blocks as they would be, shown only to the owner for a while; then 확정. A block
 * goes back only if it is still what that person left it as (later work wins, and is counted as skipped). What they took
 * from a container goes back into it if it is still there and has room, otherwise into the owner's mailbox; what they
 * put in is taken out again if it is still there. Destroyed frames, stands and animals come back; furniture they broke
 * goes to the owner's mailbox as its item. Lines undone are marked, so nothing is undone twice.
 *
 * <p><b>/관리자 테러.</b> An administrator's one step for a griefer: ban, kick, undo everything they did in any land,
 * and pay back the money other players paid them in that time, as far as their balance goes.
 */
public final class LandLogModule extends EcoModule {

    /** A change waiting for its after-state (next tick) or for the next write. */
    private static final class Pending {
        final Row row;
        final Block block;    // non-null: read the after-state from it next tick

        Pending(Row row, Block block) {
            this.row = row;
            this.block = block;
        }
    }

    /** An open container: what it held when this person opened it. */
    private record Open(Location at, long land, UUID owner, Map<String, Integer> before) {}

    /** An undo the owner is looking at (preview) and may confirm. */
    private record Preview(UUID actor, String actorName, long land, long since, List<Row> rows, List<UndoPlan.Step> steps, World world, long until) {}

    private LandLogStore store;
    private final List<Pending> settling = new ArrayList<>();
    private final ConcurrentLinkedQueue<Row> queue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Open> open = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    /** An animal's or stand's last blow from a player in a watched land: who, and the entity as it was just before. */
    private record Hit(UUID player, byte[] copy, long at) {}

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

    private void log(Player p, Land land, Location l, Action a, String before, String after, byte[] item, int amount, Block settleFrom) {
        Row r = new Row(0, now(), land.id, land.owner, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), p.getUniqueId(), p.getName(),
                a, before, after, item, amount);
        if (settleFrom != null) settling.add(new Pending(r, settleFrom));
        else queue.add(r);
    }

    /** Next tick: each block change gets the state the block was left in. */
    private void settle() {
        if (settling.isEmpty()) return;
        for (Pending p : settling) {
            Row r = p.row;
            queue.add(new Row(0, r.at(), r.land(), r.owner(), r.world(), r.x(), r.y(), r.z(), r.actor(), r.actorName(), r.action(), r.before(),
                    p.block.getBlockData().getAsString(), r.item(), r.amount()));
        }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        if (land == null) return;
        BlockData d = b.getBlockData();
        log(e.getPlayer(), land, b.getLocation(), Action.BREAK, d.getAsString(), null, contents(b.getState()), 0, b);
        Block other = partner(b, d);
        if (other != null && other.getType() == b.getType()) log(e.getPlayer(), land, other.getLocation(), Action.BREAK, data(other), null, null, 0, other);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (core.vehicles() != null && core.vehicles().placingVehicle()) return;
        Player p = e.getPlayer();
        List<BlockState> replaced = e instanceof BlockMultiPlaceEvent m ? m.getReplacedBlockStates() : List.of(e.getBlockReplacedState());
        for (BlockState s : replaced) {
            Land land = watched(p, s.getLocation());
            if (land != null) log(p, land, s.getLocation(), Action.PLACE, s.getBlockData().getAsString(), null, null, 0, s.getBlock());
        }
        // a door's top half is set after the event: what is there now is what it replaces
        Block b = e.getBlockPlaced();
        if (!(e instanceof BlockMultiPlaceEvent) && e.getBlockPlaced().getBlockData() instanceof Bisected && partner(b, b.getBlockData()) != null) {
            Block top = b.getRelative(org.bukkit.block.BlockFace.UP);
            Land land = watched(p, top.getLocation());
            if (land != null) log(p, land, top.getLocation(), Action.PLACE, data(top), null, null, 0, top);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        if (land != null) log(e.getPlayer(), land, b.getLocation(), Action.PLACE, data(b), null, null, 0, b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (e.getItemStack() != null && e.getItemStack().getType() == Material.MILK_BUCKET) return;
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        if (land != null) log(e.getPlayer(), land, b.getLocation(), Action.BREAK, data(b), null, null, 0, b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() == null) return;
        Block b = e.getBlock();
        Land land = watched(e.getPlayer(), b.getLocation());
        if (land != null) log(e.getPlayer(), land, b.getLocation(), Action.PLACE, data(b), null, null, 0, b);
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

    private static byte[] entity(Entity en) {
        try {
            return Bukkit.getUnsafe().serializeEntity(en);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = player(e.getRemover());
        if (p == null) return;
        Land land = watched(p, e.getEntity().getLocation());
        if (land != null) log(p, land, e.getEntity().getLocation(), Action.ENTITY_BREAK, e.getEntity().getType().name(), null, entity(e.getEntity()), 0, null);
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

    /** A frame's item knocked out, and who last hit an entity (for its death). */
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity en = e.getEntity();
        if (!living(en)) return;
        Hit hit = hitBy.remove(en.getUniqueId());
        Player p = en.getKiller() != null ? en.getKiller() : hit == null ? null : Bukkit.getPlayer(hit.player());
        if (p == null) return;
        Land land = watched(p, en.getLocation());
        if (land != null) log(p, land, en.getLocation(), Action.ENTITY_BREAK, en.getType().name(), null, hit != null ? hit.copy() : null, 0, null);
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
    // Undoing
    // ------------------------------------------------------------------------------------------

    /** How an undo went. */
    public static final class Result {
        public int blocks, blocksSkipped, itemsBack, itemsMailed, itemsGone, itemsRemoved, entities, entitiesSkipped, furnitureMailed, notUndone;
        final List<Long> handled = new ArrayList<>();

        Map<String, Object> args() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blocks", blocks);
            m.put("skipped", blocksSkipped);
            m.put("back", itemsBack);
            m.put("mailed", itemsMailed + furnitureMailed);
            m.put("removed", itemsRemoved);
            m.put("entities", entities);
            m.put("other", itemsGone + entitiesSkipped + notUndone);
            return m;
        }

        public String text() {
            Object[] pairs = args().entrySet().stream().flatMap(en -> java.util.stream.Stream.of(en.getKey(), en.getValue())).toArray();
            return Lang.raw("land-log.result", pairs);
        }
    }

    private static UndoPlan.Change change(Row r) {
        return new UndoPlan.Change(r.id(), r.world(), r.x(), r.y(), r.z(), r.before(), r.after());
    }

    private static boolean blockLine(Row r) {
        return r.action() == Action.BREAK || r.action() == Action.PLACE;
    }

    /** The block steps of these lines (still to check against the world when applied). */
    private static List<UndoPlan.Step> steps(List<Row> rows) {
        List<UndoPlan.Change> changes = new ArrayList<>();
        for (Row r : rows) if (blockLine(r) && r.after() != null) changes.add(change(r));
        return UndoPlan.fold(changes);
    }

    /**
     * Applies an undo over several ticks (blocks-per-tick at a time), then marks the lines handled. mailTo: who gets
     * what cannot go back where it was (a land's owner; null = the line's own land owner).
     */
    private CompletableFuture<Result> apply(List<Row> rows, List<UndoPlan.Step> steps) {
        CompletableFuture<Result> done = new CompletableFuture<>();
        Result res = new Result();
        Map<Long, Row> byId = new HashMap<>();
        for (Row r : rows) byId.put(r.id(), r);
        List<Runnable> work = new ArrayList<>();
        for (UndoPlan.Step s : steps) work.add(() -> undoBlock(s, byId.get(s.first()), res));
        for (Row r : rows) {
            if (blockLine(r)) continue;
            work.add(() -> undoOther(r, res));
        }
        for (Row r : rows) res.handled.add(r.id());
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
                store.undone(res.handled).whenComplete((v, err) -> done.complete(res));
            }
        }, 1, 1);
        return done;
    }

    private void undoBlock(UndoPlan.Step s, Row first, Result res) {
        World w = Bukkit.getWorld(s.world());
        if (w == null) {
            res.blocksSkipped++;
            return;
        }
        Block b = w.getBlockAt(s.x(), s.y(), s.z());
        if (!UndoPlan.applies(s, data(b))) {
            res.blocksSkipped++;
            return;
        }
        BlockData d;
        try {
            d = Bukkit.createBlockData(s.restore() == null ? "minecraft:air" : s.restore());
        } catch (IllegalArgumentException ex) {
            res.blocksSkipped++;
            return;
        }
        b.setBlockData(d, false);
        res.blocks++;
        // a broken container: what was in it goes back in
        if (first != null && first.action() == Action.BREAK && first.item() != null && b.getState(false) instanceof Container c) {
            for (ItemStack it : ItemStack.deserializeItemsFromBytes(first.item())) {
                for (ItemStack left : c.getInventory().addItem(it).values()) mail(first, left, res);
            }
        }
    }

    private void mail(Row r, ItemStack it, Result res) {
        if (r.owner() == null) {
            res.itemsGone += it.getAmount();
            eco.getLogger().info("땅 기록: 운영자 땅에서 되돌린 물건을 줄 곳이 없음: " + it.getType() + " x" + it.getAmount() + " (" + r.world() + " " + r.x() + "," + r.y() + "," + r.z() + ")");
            return;
        }
        eco.mail().deliver(r.owner(), it, "land_undo");
        res.itemsMailed += it.getAmount();
    }

    private void undoOther(Row r, Result res) {
        World w = Bukkit.getWorld(r.world());
        Location at = w == null ? null : new Location(w, r.x(), r.y(), r.z());
        switch (r.action()) {
            case TAKE -> {
                ItemStack it = Items.fromBytes(r.item());
                it.setAmount(1);
                int left = r.amount();
                Block b = at == null ? null : at.getBlock();
                if (b != null && b.getState(false) instanceof Container c) {
                    while (left > 0) {
                        ItemStack part = it.clone();
                        part.setAmount(Math.min(left, it.getMaxStackSize()));
                        int over = c.getInventory().addItem(part).values().stream().mapToInt(ItemStack::getAmount).sum();
                        res.itemsBack += part.getAmount() - over;
                        left -= part.getAmount();
                        if (over > 0) {
                            ItemStack rest = it.clone();
                            rest.setAmount(over);
                            mail(r, rest, res);
                        }
                    }
                } else {
                    while (left > 0) {
                        ItemStack part = it.clone();
                        part.setAmount(Math.min(left, it.getMaxStackSize()));
                        left -= part.getAmount();
                        mail(r, part, res);
                    }
                }
            }
            case PUT -> {
                Block b = at == null ? null : at.getBlock();
                if (b != null && b.getState(false) instanceof Container c) {
                    ItemStack it = Items.fromBytes(r.item());
                    String k = key(it);
                    int left = r.amount();
                    ItemStack[] all = c.getInventory().getContents();
                    for (int i = 0; i < all.length && left > 0; i++) {
                        if (all[i] == null || all[i].isEmpty() || !key(all[i]).equals(k)) continue;
                        int take = Math.min(left, all[i].getAmount());
                        all[i].setAmount(all[i].getAmount() - take);
                        left -= take;
                        res.itemsRemoved += take;
                    }
                    c.getInventory().setContents(all);
                }
            }
            case ENTITY_BREAK -> {
                if (at == null || r.item() == null) {
                    res.entitiesSkipped++;
                    return;
                }
                try {
                    Entity en = Bukkit.getUnsafe().deserializeEntity(r.item(), w, false);
                    if (en instanceof Hanging) {
                        // a frame or painting already back at that spot: do not stack a second one
                        boolean taken = !at.getWorld().getNearbyEntities(at.clone().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6, x -> x.getType() == en.getType()).isEmpty();
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
            case ENTITY_PLACE -> {
                Entity en = r.after() == null ? null : Bukkit.getEntity(UUID.fromString(r.after()));
                if (en != null && en.isValid()) {
                    en.remove();
                    res.entities++;
                } else res.entitiesSkipped++;
            }
            case ENTITY_TAKE -> {
                ItemStack it = Items.fromBytes(r.item());
                Entity frame = r.after() == null ? null : Bukkit.getEntity(UUID.fromString(r.after()));
                if (frame instanceof ItemFrame f && (f.getItem() == null || f.getItem().isEmpty())) {
                    f.setItem(it, false);
                    res.itemsBack += it.getAmount();
                } else mail(r, it, res);
            }
            case FURNITURE_BREAK -> {
                if (r.item() == null) {
                    res.notUndone++;
                    return;
                }
                ItemStack it = Items.fromBytes(r.item());
                if (r.owner() == null) res.itemsGone++;
                else {
                    eco.mail().deliver(r.owner(), it, "land_undo");
                    res.furnitureMailed++;
                }
            }
            default -> res.notUndone++;   // furniture placed: taken down by the owner (it is theirs to break in their land)
        }
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

    /** /땅 기록 [이름 [쪽 | 되돌리기 [시간] | 확정 | 취소]] */
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
        String name = a[0];
        Players.find(name, found -> {
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

    private void summary(Player p, Land l) {
        long since = now() - eco.getConfig().getInt("land-log.keep-days") * 86_400_000L;
        store.tally(l.id, since).thenAccept(rows -> sync(() -> {
            if (rows.isEmpty()) {
                p.sendMessage(Lang.msg("land-log.empty"));
                return;
            }
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
            });
        }));
    }

    private void detail(CommandSender s, long land, UUID who, int page) {
        int per = 10;
        store.recent(who, land, 0, (page - 1) * per, per).thenAccept(rows -> sync(() -> {
            String name = Players.name(who);
            s.sendMessage(Lang.msg("land-log.detail-head", "player", name, "page", page));
            if (rows.isEmpty()) s.sendMessage(Lang.msg("land-log.empty"));
            for (Row r : rows) s.sendMessage(Lang.mm("land-log.line", "when", when(r.at()), "what", line(r), "x", r.x(), "y", r.y(), "z", r.z()));
            // the buttons are the owner's commands (an administrator pages with /관리자 땅기록)
            if (rows.size() == per && land >= 0) s.sendMessage(Lang.mm("land-log.more", "player", name, "next", page + 1));
        }));
    }

    /** "부숨 참나무 판자", "꺼냄 다이아몬드 x3". */
    private static String line(Row r) {
        String thing = switch (r.action()) {
            case BREAK -> shortBlock(r.before());
            case PLACE -> shortBlock(r.after());
            case TAKE, PUT, ENTITY_TAKE -> {
                try {
                    ItemStack it = Items.fromBytes(r.item());
                    yield Items.plainName(it) + " x" + r.amount();
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
        flush();
        store.toUndo(who, l.id, since).thenAccept(rows -> sync(() -> {
            if (!p.isOnline()) return;
            if (rows.isEmpty()) {
                p.sendMessage(Lang.msg("land-log.nothing", "player", name));
                return;
            }
            List<UndoPlan.Step> steps = steps(rows);
            World w = p.getWorld();
            int shown = 0, applies = 0, max = eco.getConfig().getInt("land-log.preview-max-blocks");
            for (UndoPlan.Step s : steps) {
                World sw = Bukkit.getWorld(s.world());
                if (sw == null) continue;
                Block b = sw.getBlockAt(s.x(), s.y(), s.z());
                if (!UndoPlan.applies(s, data(b))) continue;
                applies++;
                if (sw != w || shown >= max || b.getLocation().distanceSquared(p.getLocation()) > 160 * 160) continue;
                try {
                    p.sendBlockChange(b.getLocation(), Bukkit.createBlockData(s.restore() == null ? "minecraft:air" : s.restore()));
                    shown++;
                } catch (IllegalArgumentException ignored) {
                }
            }
            int seconds = eco.getConfig().getInt("land-log.preview-seconds");
            previews.put(p.getUniqueId(), new Preview(who, name, l.id, since, rows, steps, w, now() + seconds * 1000L));
            long items = rows.stream().filter(r -> r.action() == Action.TAKE).mapToLong(Row::amount).sum();
            p.sendMessage(Lang.msg("land-log.preview", "player", name, "blocks", applies, "skipped", steps.size() - applies, "items", items,
                    "lines", rows.size(), "seconds", seconds, "since", since <= 0 ? Lang.raw("land-log.since-all") : when(since)));
            p.sendMessage(Lang.mm("land-log.preview-buttons"));
        }));
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
        apply(pv.rows(), pv.steps()).thenAccept(res -> sync(() -> {
            eco.getLogger().info("[땅 기록] " + p.getName() + " 이(가) 땅 #" + pv.land() + " 에서 " + pv.actorName() + " 의 " + pv.rows().size() + "줄을 되돌림: " + res.text());
            if (p.isOnline()) p.sendMessage(Lang.msg("land-log.undone", "player", pv.actorName(), "result", res.text()));
        }));
    }

    // ------------------------------------------------------------------------------------------
    // Administrators: /관리자 땅기록, /관리자 테러
    // ------------------------------------------------------------------------------------------

    /** /관리자 땅기록 <플레이어> [시간] [쪽] */
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
            flush();
            store.byLand(who, since).thenAccept(rows -> sync(() -> {
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
                detail(s, -1, who, 1);
            }));
        });
    }

    /** /관리자 테러 <플레이어> <시간> [확정] */
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
        boolean go = a.length >= 3 && (a[2].equals("확정") || a[2].equals("confirm"));
        long since = now() - ms;
        String admin = s instanceof Player p ? p.getName() : "console";
        Players.find(a[0], found -> {
            if (found.isEmpty()) {
                s.sendMessage(Lang.msg("land.no-player"));
                return;
            }
            UUID who = found.get();
            String name = Players.name(who);
            flush();
            store.toUndo(who, -1, since).thenCombine(store.payments(who, since), (rows, pays) -> {
                sync(() -> {
                    long balance = core.economy().balance(who);
                    List<MoneyReturn.Back> plan = MoneyReturn.plan(pays, balance);
                    long paid = pays.stream().mapToLong(MoneyReturn.Payment::amount).sum();
                    long back = plan.stream().mapToLong(MoneyReturn.Back::returned).sum();
                    long blocks = rows.stream().filter(LandLogModule::blockLine).count();
                    long taken = rows.stream().filter(r -> r.action() == Action.TAKE || r.action() == Action.ENTITY_TAKE).mapToLong(Row::amount).sum();
                    long lands = rows.stream().map(Row::land).distinct().count();
                    long senders = pays.stream().map(MoneyReturn.Payment::from).distinct().count();
                    s.sendMessage(Lang.msg("land-log.grief-summary", "player", name, "time", Durations.text(ms), "blocks", blocks, "lands", lands, "items", taken,
                            "paid", EconomyModule.format(paid), "payments", pays.size(), "senders", senders, "balance", EconomyModule.format(balance),
                            "back", EconomyModule.format(back), "short", EconomyModule.format(paid - back)));
                    if (!go) {
                        s.sendMessage(Lang.mm("land-log.grief-confirm", "player", name, "time", a[1]));
                        return;
                    }
                    // 1. out: banned (for good, until an administrator lifts it) and kicked
                    String reason = Lang.raw("land-log.ban-reason");
                    Bukkit.getOfflinePlayer(who).ban(reason, (java.util.Date) null, admin);
                    Player online = Bukkit.getPlayer(who);
                    if (online != null) online.kick(Lang.mm("land-log.kick"));
                    // 2. the money back, as far as it goes
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
                    long moneyBack = returned;
                    store.returned(done, who, now());
                    // 3. everything they did in any land, undone
                    apply(rows, steps(rows)).thenAccept(res -> sync(() -> {
                        String summary = "banned; " + res.text() + "; money " + moneyBack + "/" + paid;
                        store.audit(admin, who, name, since, summary, now());
                        eco.getLogger().warning("[테러 처리] " + admin + " → " + name + " (" + Durations.text(ms) + "): " + summary);
                        s.sendMessage(Lang.msg("land-log.grief-done", "player", name, "result", res.text(), "back", EconomyModule.format(moneyBack),
                                "short", EconomyModule.format(paid - moneyBack)));
                        // the owners whose land was put back are told
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
