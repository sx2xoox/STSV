package dev.stsv.core.painting;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBehavior;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import io.papermc.paper.event.player.CartographyItemEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Crafter;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.CartographyInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 그림 그리기 (이젤): an easel (furniture, behavior "stsv:easel") takes a canvas; the canvas is a map in an invisible, fixed glow
 * item frame standing in the easel's front cell, so every viewer sees it like any map. Whoever put the canvas up paints on
 * it with the brush (right-click and hold while looking at it); the brush's palette (sneak + right-click, or left-click the
 * easel) picks colours, tools, the brush size, undoes, takes the canvas off or finishes the painting: a titled map item
 * (stsv:painting) that hangs in item frames and can be traded, and cannot be copied.
 *
 * <p>State: the paintings ({@link PaintingStore}, plugins/STSV/paintings), the easel's display (the map id and its frame's
 * uuid) and the frame (the easel's uuid and the map id). A painting knows the one easel it is on, so a canvas item is never
 * on two easels and a stale frame or easel left in an unloaded chunk tidies itself when it loads.
 */
public final class PaintingModule implements StsvModule, Listener {

    public static final String BEHAVIOR = "stsv:easel";
    static final String EASEL = "stsv:easel", BLANK = "stsv:blank_canvas", BRUSH = "stsv:paint_brush",
            UNFINISHED = "stsv:unfinished_canvas", PAINTING = "stsv:painting";
    /** the easel's look with a canvas on it (its back and stretcher bars; the picture is the map in front) */
    static final NamespacedKey LOADED_MODEL = new NamespacedKey("stsv", "model/easel_canvas");
    /** ticks a right-click keeps the brush going (the client repeats a held click every 4 ticks) */
    static final int HOLD_TICKS = 5;
    /** a fill or a colour pick at most this often (ticks) */
    static final int TOOL_COOLDOWN = 8;
    /** a changed canvas is sent to players this close (blocks) at most every FLUSH_TICKS */
    static final double VIEW_RANGE = 48;
    static final int FLUSH_TICKS = 2;
    static final double REACH = 6;
    /** a look this far past the canvas edge (blocks, two cells) still paints the edge cell */
    static final double EDGE_SLACK = 2.0 / PaintCanvas.GRID;
    /** the hover outline: how far in front of the picture it floats and how thick it is (blocks) */
    static final double PREVIEW_FRONT = 0.004, PREVIEW_THICK = 0.002;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.systemDefault());

    public enum Tool {
        PEN("pen"), ERASER("eraser"), FILL("fill"), PICK("pick");
        private final String key;

        Tool(String key) { this.key = key; }

        public String key() { return key; }
    }

    /** A player's brush: colour, size and tool (kept while online). */
    static final class Brush {
        byte color = MapColors.BLACK;
        int size = 1;
        Tool tool = Tool.PEN;
    }

    /** A player painting now: which canvas, the last cell and the stroke so far. */
    private static final class Session {
        final UUID player;
        final int mapId;
        final UUID frame;
        long lastClick;
        int lastU = -1, lastV = -1;
        PaintCanvas.Stroke stroke = new PaintCanvas.Stroke();

        Session(UUID player, int mapId, UUID frame) {
            this.player = player;
            this.mapId = mapId;
            this.frame = frame;
        }
    }

    private record Aim(GlowItemFrame frame, int mapId, CanvasRay.Hit hit) {}

    /** The outline a painter sees over the cells the brush would paint (a display only they can see). */
    private static final class Preview {
        final UUID entity;
        String key = "";

        Preview(UUID entity) {
            this.entity = entity;
        }
    }

    private STSVPlugin plugin;
    private PaintingStore store;
    private NamespacedKey keyCanvas, keyFrame, keyEasel, keyItemMap, keyItemId;
    private final Map<UUID, Brush> brushes = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final Map<Integer, UndoStack> undo = new HashMap<>();
    private final Set<Integer> dirtyView = new HashSet<>(), dirtyDisk = new HashSet<>();
    /** the frame showing each mounted painting (loaded ones), for sending updates to the players near it */
    private final Map<Integer, UUID> frames = new HashMap<>();
    private final Map<UUID, Long> lastTool = new HashMap<>(), lastHint = new HashMap<>(), lastPalette = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public String id() {
        return "painting";
    }

    @Override
    public void enable(STSVPlugin plugin) throws Exception {
        this.plugin = plugin;
        store = new PaintingStore(plugin.getDataFolder().toPath().resolve("paintings"));
        keyCanvas = new NamespacedKey(plugin, "easel_canvas");
        keyFrame = new NamespacedKey(plugin, "easel_frame");
        keyEasel = new NamespacedKey(plugin, "canvas_easel");
        keyItemMap = new NamespacedKey(plugin, "painting");
        keyItemId = new NamespacedKey(plugin, "painting_id");
        plugin.furniture().registerBehavior(BEHAVIOR, new EaselBehavior());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // maps the server loaded before this module was ready (item frames near spawn): give them their renderer now
        for (int id : store.ids()) {
            MapView view = Bukkit.getMap(id);
            if (view != null) prepare(view);
        }
        for (World w : Bukkit.getWorlds()) {
            for (GlowItemFrame f : w.getEntitiesByClass(GlowItemFrame.class)) checkFrame(f);
        }
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickSessions, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickPreviews, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 1L, FLUSH_TICKS));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickBar, 5L, 10L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> saveDirty(true), 600L, 600L));
    }

    @Override
    public void disable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        for (Session s : sessions.values()) endStroke(s);
        sessions.clear();
        for (UUID id : new ArrayList<>(previews.keySet())) hidePreview(id);
        saveDirty(false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof PaletteMenu) p.closeInventory();
        }
        for (Painting p : store.loaded()) {
            MapView view = Bukkit.getMap(p.mapId());
            if (view != null) for (MapRenderer r : new ArrayList<>(view.getRenderers())) if (r instanceof PaintingRenderer) view.removeRenderer(r);
        }
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Maps: every painting's map draws with a PaintingRenderer, nothing of the world
    // ------------------------------------------------------------------------------------------

    private void prepare(MapView view) {
        int id = view.getId();
        if (!store.exists(id)) return;
        for (MapRenderer r : view.getRenderers()) if (r instanceof PaintingRenderer) return;
        Optional<Painting> p = store.get(id);
        if (p.isEmpty()) return;
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(new PaintingRenderer(p.get()));
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        prepare(e.getMap());
    }

    private Painting create(Player painter, World world) throws IOException {
        MapView view = Bukkit.createMap(world);
        Painting p = new Painting(view.getId(), UUID.randomUUID(), painter.getUniqueId(), painter.getName(), System.currentTimeMillis());
        store.add(p);
        store.write(p.mapId(), p.encode());
        prepare(view);
        return p;
    }

    private void changed(Painting p) {
        p.changed();
        dirtyView.add(p.mapId());
        dirtyDisk.add(p.mapId());
    }

    /** Sends the canvases changed since the last flush to the players near them (the rest get it with the frame's own updates). */
    private void flush() {
        if (dirtyView.isEmpty()) return;
        for (int id : dirtyView) {
            UUID fid = frames.get(id);
            Entity frame = fid == null ? null : Bukkit.getEntity(fid);
            MapView view = Bukkit.getMap(id);
            if (frame == null || view == null) continue;
            Location at = frame.getLocation();
            for (Player p : at.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(at) <= VIEW_RANGE * VIEW_RANGE) p.sendMap(view);
            }
        }
        dirtyView.clear();
    }

    /** Writes the changed paintings (encoded here, written off the main thread when async). */
    private void saveDirty(boolean async) {
        if (dirtyDisk.isEmpty()) return;
        Map<Integer, byte[]> out = new HashMap<>();
        for (int id : dirtyDisk) store.get(id).ifPresent(p -> out.put(id, p.encode()));
        dirtyDisk.clear();
        Runnable write = () -> out.forEach((id, bytes) -> {
            try {
                store.write(id, bytes);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "[그림] 저장 실패: " + id, ex);
            }
        });
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }

    /** Writes one painting now (mounting, taking off, finishing: the state that guards against copies). */
    private void saveNow(Painting p) {
        dirtyDisk.remove(p.mapId());
        try {
            store.write(p.mapId(), p.encode());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "[그림] 저장 실패: " + p.mapId(), ex);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------------------------------

    private boolean is(ItemStack stack, String id) {
        return plugin.content().itemOf(stack).map(i -> i.key().asString().equals(id)).orElse(false);
    }

    private boolean holdsBrush(Player p) {
        return is(p.getInventory().getItemInMainHand(), BRUSH);
    }

    /** The map id an item names: our canvas and painting items, or any filled map of a painting (a frame's map). */
    private Optional<Integer> paintingOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return Optional.empty();
        Integer id = stack.getItemMeta().getPersistentDataContainer().get(keyItemMap, PersistentDataType.INTEGER);
        if (id != null) return Optional.of(id);
        if (stack.getType() == Material.FILLED_MAP && stack.getItemMeta() instanceof MapMeta m && m.hasMapView() && m.getMapView() != null) {
            int mid = m.getMapView().getId();
            if (store.exists(mid)) return Optional.of(mid);
        }
        return Optional.empty();
    }

    private ItemStack item(String id) {
        return plugin.content().item(id).map(i -> i.create(1)).orElseGet(() -> new ItemStack(Material.PAPER));
    }

    private ItemStack canvasItem(Painting p) {
        ItemStack it = item(UNFINISHED);
        it.editMeta(m -> {
            m.lore(Lang.items("painting.item.unfinished-lore", "painter", esc(p.painterName()), "date", DATE.format(Instant.ofEpochMilli(p.created()))));
            m.getPersistentDataContainer().set(keyItemMap, PersistentDataType.INTEGER, p.mapId());
            m.getPersistentDataContainer().set(keyItemId, PersistentDataType.STRING, p.id().toString());
        });
        return it;
    }

    ItemStack paintingItem(Painting p) {
        ItemStack it = item(PAINTING);
        if (it.getType() != Material.FILLED_MAP) it = it.withType(Material.FILLED_MAP);
        MapView view = Bukkit.getMap(p.mapId());
        it.editMeta(m -> {
            if (m instanceof MapMeta mm && view != null) mm.setMapView(view);
            m.itemName(Lang.item("painting.item.name", "title", esc(p.title())));
            m.lore(Lang.items("painting.item.lore", "painter", esc(p.painterName()), "date", DATE.format(Instant.ofEpochMilli(p.finishedAt()))));
            m.getPersistentDataContainer().set(keyItemMap, PersistentDataType.INTEGER, p.mapId());
            m.getPersistentDataContainer().set(keyItemId, PersistentDataType.STRING, p.id().toString());
        });
        return it;
    }

    private static String esc(String s) {
        return s == null ? "" : MiniMessage.miniMessage().escapeTags(s);
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    /** A colour as a MiniMessage swatch: two squares in it. */
    static String swatch(byte colour) {
        return "<" + MapColors.hex(colour) + ">■■</" + MapColors.hex(colour) + ">";
    }

    // ------------------------------------------------------------------------------------------
    // The easel: its canvas frame, putting a canvas up and taking it off
    // ------------------------------------------------------------------------------------------

    /** The block of the easel's canvas (its front upper cell) and the way the canvas faces (towards the painter). */
    private record Spot(Block block, BlockFace facing) {}

    private Spot spot(Furniture easel) {
        Location front = plugin.furniture().modelPoint(easel, new Vector3f(8, 24, -8));
        Location back = plugin.furniture().modelPoint(easel, new Vector3f(8, 24, 8));
        double dx = front.getX() - back.getX(), dz = front.getZ() - back.getZ();
        BlockFace face = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST) : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        return new Spot(front.getBlock(), face);
    }

    private boolean isEasel(Furniture f) {
        return f.item().key().asString().equals(EASEL);
    }

    private Integer canvasOn(Furniture easel) {
        return easel.data().get(keyCanvas, PersistentDataType.INTEGER);
    }

    private boolean mayManage(Player p, Painting painting) {
        return p.getUniqueId().equals(painting.painter()) || p.hasPermission("stsv.admin");
    }

    /** Puts a canvas (a new blank one, or one taken off before) up on the easel. */
    private void putUp(Player player, Furniture easel, ItemStack hand) {
        Block anchor = easel.display().getLocation().getBlock();
        if (!plugin.furniture().mayBuild(player, List.of(anchor), anchor.getRelative(BlockFace.DOWN), EquipmentSlot.HAND)) {
            player.sendMessage(Lang.msg("painting.no-build"));
            return;
        }
        Painting painting;
        if (is(hand, BLANK)) {
            try {
                painting = create(player, anchor.getWorld());
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "[그림] 새 캔버스를 만들지 못함", ex);
                return;
            }
        } else {
            Optional<Integer> id = paintingOf(hand);
            Optional<Painting> p = id.flatMap(store::get);
            String itemId = hand.getItemMeta().getPersistentDataContainer().get(keyItemId, PersistentDataType.STRING);
            if (p.isEmpty() || itemId == null || !itemId.equals(p.get().id().toString())) {
                player.sendMessage(Lang.msg("painting.lost"));
                return;
            }
            painting = p.get();
            if (painting.finished()) {
                player.sendMessage(Lang.msg("painting.finished-canvas"));
                return;
            }
            if (painting.mountedOn() != null) {
                player.sendMessage(Lang.msg("painting.already-up"));
                return;
            }
        }
        Spot spot = spot(easel);
        for (GlowItemFrame old : spot.block().getWorld().getNearbyEntitiesByType(GlowItemFrame.class, spot.block().getLocation().add(0.5, 0.5, 0.5), 0.9)) {
            if (old.getPersistentDataContainer().has(keyEasel)) old.remove();       // a stale one of an easel that was here
        }
        MapView view = Bukkit.getMap(painting.mapId());
        if (view == null) {
            player.sendMessage(Lang.msg("painting.lost"));
            return;
        }
        prepare(view);
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        map.editMeta(MapMeta.class, m -> m.setMapView(view));
        UUID easelId = easel.display().getUniqueId();
        GlowItemFrame frame = spot.block().getWorld().spawn(spot.block().getLocation(), GlowItemFrame.class, f -> {
            f.setFacingDirection(spot.facing(), true);
            f.setItem(map, false);
            f.setItemDropChance(0f);
            f.setVisible(false);
            f.setFixed(true);
            f.setInvulnerable(true);
            f.setSilent(true);
            f.setPersistent(true);
            f.getPersistentDataContainer().set(keyEasel, PersistentDataType.STRING, easelId.toString());
            f.getPersistentDataContainer().set(keyCanvas, PersistentDataType.INTEGER, painting.mapId());
        });
        easel.data().set(keyCanvas, PersistentDataType.INTEGER, painting.mapId());
        easel.data().set(keyFrame, PersistentDataType.STRING, frame.getUniqueId().toString());
        plugin.furniture().setModel(easel, LOADED_MODEL);
        painting.mountedOn(easelId);
        painting.painter(player.getUniqueId(), player.getName());
        frames.put(painting.mapId(), frame.getUniqueId());
        saveNow(painting);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE || !is(hand, BLANK)) hand.subtract(1);
        player.playSound(frame.getLocation(), Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 0.8f, 1.1f);
        player.sendMessage(Lang.msg("painting.canvas-up"));
    }

    /** Takes the easel's canvas down (the frame goes, the easel shows its empty look). The painting, or null if none. */
    private Painting takeDown(Furniture easel) {
        Integer id = canvasOn(easel);
        if (id == null) return null;
        String fid = easel.data().get(keyFrame, PersistentDataType.STRING);
        Entity frame = fid == null ? null : Bukkit.getEntity(UUID.fromString(fid));
        if (frame != null) frame.remove();
        else {
            Spot spot = spot(easel);
            for (GlowItemFrame f : spot.block().getWorld().getNearbyEntitiesByType(GlowItemFrame.class, spot.block().getLocation().add(0.5, 0.5, 0.5), 0.9)) {
                if (f.getPersistentDataContainer().has(keyEasel)) f.remove();
            }
        }
        easel.data().remove(keyCanvas);
        easel.data().remove(keyFrame);
        if (easel.display().isValid()) plugin.furniture().setModel(easel, null);
        frames.remove(id);
        sessions.values().removeIf(s -> {
            if (s.mapId != id) return false;
            endStroke(s);
            return true;
        });
        Optional<Painting> p = store.get(id);
        if (p.isEmpty()) return null;
        if (easel.display().getUniqueId().equals(p.get().mountedOn())) p.get().mountedOn(null);
        saveNow(p.get());
        return p.get();
    }

    private Optional<Furniture> easelOf(Painting p) {
        if (p.mountedOn() == null) return Optional.empty();
        return Bukkit.getEntity(p.mountedOn()) instanceof ItemDisplay d ? plugin.furniture().fromDisplay(d) : Optional.empty();
    }

    /** Right-click on the easel (or its canvas) with anything but the brush. */
    private void easelClick(Player player, Furniture easel) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Integer on = canvasOn(easel);
        if (on == null) {
            if (is(hand, BLANK) || is(hand, UNFINISHED)) putUp(player, easel, hand);
            else hint(player, "painting.need-canvas");
            return;
        }
        Optional<Painting> p = store.get(on);
        if (p.isEmpty()) return;
        if (hand.isEmpty() && player.isSneaking() && mayManage(player, p.get())) {
            takeOff(player, easel);
            return;
        }
        if (is(hand, BLANK) || is(hand, UNFINISHED)) player.sendMessage(Lang.msg("painting.occupied"));
        else if (!mayManage(player, p.get())) hint(player, "painting.not-yours", "painter", esc(p.get().painterName()));
        else hint(player, "painting.how");
    }

    private void takeOff(Player player, Furniture easel) {
        Painting p = takeDown(easel);
        if (p == null) return;
        give(player, canvasItem(p));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 0.8f, 1f);
        player.sendMessage(Lang.msg("painting.taken-off"));
    }

    private void hint(Player p, String key, Object... pairs) {
        long now = Bukkit.getCurrentTick();
        Long last = lastHint.get(p.getUniqueId());
        if (last != null && now - last < 20) return;
        lastHint.put(p.getUniqueId(), now);
        p.sendActionBar(Lang.mm(key, pairs));
    }

    private final class EaselBehavior implements FurnitureBehavior {
        @Override
        public void onInteract(Player player, Furniture furniture) {
            if (holdsBrush(player) && player.isSneaking()) {
                Integer on = canvasOn(furniture);
                paletteOnce(player, on == null ? -1 : on);       // this easel's canvas, wherever on it the click landed
            } else if (holdsBrush(player)) brushClick(player);
            else easelClick(player, furniture);
        }

        @Override
        public void onBreak(Player player, Furniture furniture) {
            Painting p = takeDown(furniture);
            if (p != null) furniture.display().getWorld().dropItemNaturally(furniture.location(), canvasItem(p));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Painting: aim, click, hold
    // ------------------------------------------------------------------------------------------

    private Brush brush(Player p) {
        return brushes.computeIfAbsent(p.getUniqueId(), k -> new Brush());
    }

    private double reach(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        return Math.min(REACH, (a == null ? 4.5 : a.getValue()) + 1.5);
    }

    private static CanvasRay.Face face(GlowItemFrame f) {
        BlockFace facing = f.getFacing();
        Block b = f.getLocation().getBlock();
        double[] n = {facing.getModX(), facing.getModY(), facing.getModZ()};
        // the map lies against the block behind the frame, a hair in front of it
        double off = 0.5 - 1.0 / 32;
        double[] c = {b.getX() + 0.5 - n[0] * off, b.getY() + 0.5 - n[1] * off, b.getZ() + 0.5 - n[2] * off};
        return CanvasRay.Face.upright(c, n);
    }

    private CanvasRay.Hit hit(Player p, GlowItemFrame f) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector d = eye.getDirection();
        return CanvasRay.hit(new double[]{eye.getX(), eye.getY(), eye.getZ()}, new double[]{d.getX(), d.getY(), d.getZ()}, face(f), reach(p), EDGE_SLACK);
    }

    /** The canvas the player looks at (a look on a canvas before one just past an edge, then the nearest), or null. */
    private Aim aim(Player p) {
        Aim best = null;
        for (GlowItemFrame f : p.getWorld().getNearbyEntitiesByType(GlowItemFrame.class, p.getEyeLocation(), REACH + 1)) {
            Integer id = f.getPersistentDataContainer().get(keyCanvas, PersistentDataType.INTEGER);
            if (id == null || f.getFacing().getModY() != 0) continue;
            CanvasRay.Hit h = hit(p, f);
            if (h == null) continue;
            if (best == null || (h.exact() && !best.hit().exact())
                    || (h.exact() == best.hit().exact() && h.distance() < best.hit().distance())) best = new Aim(f, id, h);
        }
        return best;
    }

    /** Any right-click with the brush (on the air, a block, the easel or its canvas). */
    private void brushClick(Player player) {
        long now = Bukkit.getCurrentTick();
        if (player.isSneaking()) {
            Aim a = aim(player);
            paletteOnce(player, a == null ? -1 : a.mapId());
            return;
        }
        Aim a = aim(player);
        if (a == null) return;
        Optional<Painting> op = store.get(a.mapId());
        if (op.isEmpty() || op.get().finished()) return;
        Painting painting = op.get();
        if (!player.getUniqueId().equals(painting.painter())) {
            hint(player, "painting.not-yours", "painter", esc(painting.painterName()));
            return;
        }
        Brush b = brush(player);
        switch (b.tool) {
            case PEN, ERASER -> {
                Session s = sessions.get(player.getUniqueId());
                if (s != null && s.mapId != a.mapId()) {
                    endStroke(s);
                    s = null;
                }
                if (s == null) {
                    s = new Session(player.getUniqueId(), a.mapId(), a.frame().getUniqueId());
                    sessions.put(player.getUniqueId(), s);
                    frames.put(a.mapId(), a.frame().getUniqueId());
                    paintStep(player, s, a.frame(), painting);        // the first dot right away
                }
                s.lastClick = now;
            }
            case FILL -> {
                if (!cooled(player, now)) return;
                PaintCanvas.Stroke stroke = new PaintCanvas.Stroke();
                if (painting.canvas().fill(a.hit().u(), a.hit().v(), b.color, stroke) > 0) {
                    undo(a.mapId()).push(stroke);
                    frames.put(a.mapId(), a.frame().getUniqueId());
                    changed(painting);
                    player.playSound(player.getLocation(), Sound.ITEM_BUCKET_EMPTY, 0.6f, 1.4f);
                }
            }
            case PICK -> {
                if (!cooled(player, now)) return;
                b.color = painting.canvas().cell(a.hit().u(), a.hit().v());
                b.tool = Tool.PEN;
                player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.6f, 1.6f);
                player.sendActionBar(Lang.mm("painting.picked", "swatch", swatch(b.color)));
            }
        }
    }

    private boolean cooled(Player p, long now) {
        Long last = lastTool.get(p.getUniqueId());
        if (last != null && now - last < TOOL_COOLDOWN) return false;
        lastTool.put(p.getUniqueId(), now);
        return true;
    }

    private UndoStack undo(int mapId) {
        return undo.computeIfAbsent(mapId, k -> new UndoStack());
    }

    private void endStroke(Session s) {
        if (!s.stroke.isEmpty()) {
            undo(s.mapId).push(s.stroke);
            dirtyDisk.add(s.mapId);
        }
        s.stroke = new PaintCanvas.Stroke();
        s.lastU = -1;
    }

    /** One tick of a held brush: a line from the last cell to the one looked at now (no gaps however fast the look moves). */
    private void paintStep(Player player, Session s, GlowItemFrame frame, Painting painting) {
        CanvasRay.Hit h = hit(player, frame);
        if (h == null) {
            s.lastU = -1;
            return;
        }
        Brush b = brush(player);
        byte colour = b.tool == Tool.ERASER ? MapColors.CANVAS : b.color;
        int changed = s.lastU < 0 ? painting.canvas().stamp(h.u(), h.v(), b.size, colour, s.stroke)
                : painting.canvas().line(s.lastU, s.lastV, h.u(), h.v(), b.size, colour, s.stroke);
        s.lastU = h.u();
        s.lastV = h.v();
        if (changed > 0) changed(painting);
    }

    private void tickSessions() {
        if (sessions.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            Player p = Bukkit.getPlayer(s.player);
            Brush b = p == null ? null : brush(p);
            boolean active = p != null && holdsBrush(p) && now - s.lastClick <= HOLD_TICKS
                    && (b.tool == Tool.PEN || b.tool == Tool.ERASER);
            Optional<Painting> painting = active ? store.get(s.mapId) : Optional.empty();
            Entity frame = active ? Bukkit.getEntity(s.frame) : null;
            if (!active || painting.isEmpty() || painting.get().finished() || !(frame instanceof GlowItemFrame gf)
                    || !p.getUniqueId().equals(painting.get().painter())) {
                endStroke(s);
                it.remove();
                continue;
            }
            paintStep(p, s, gf, painting.get());
        }
    }

    /** The action bar while holding the brush: the colour, tool and size. */
    private void tickBar() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holdsBrush(p)) continue;
            Long last = lastHint.get(p.getUniqueId());
            if (last != null && Bukkit.getCurrentTick() - last < 30) continue;      // a hint is showing
            Brush b = brush(p);
            byte c = b.tool == Tool.ERASER ? MapColors.CANVAS : b.color;
            p.sendActionBar(Lang.mm("painting.bar", "swatch", swatch(c), "tool", Lang.raw("painting.tool." + b.tool.key()), "size", b.size));
        }
    }

    /**
     * The hover outline: while a painter holds the brush and looks at their canvas, the cells the next dab would paint
     * (the brush's square for the pen and eraser, one cell for fill and pick) show a thin glowing box only they see. It
     * moves only when the cells under the crosshair change; the map itself is not redrawn for it.
     */
    private void tickPreviews() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Aim a = holdsBrush(p) ? aim(p) : null;
            Painting painting = a == null ? null : store.get(a.mapId()).orElse(null);
            if (painting == null || painting.finished() || !id.equals(painting.painter())) {
                if (previews.containsKey(id)) hidePreview(id);
                continue;
            }
            Brush b = brush(p);
            int size = b.tool == Tool.PEN || b.tool == Tool.ERASER ? b.size : 1;
            int[] cells = PaintCanvas.stampBounds(a.hit().u(), a.hit().v(), size);
            if (cells == null) continue;
            byte under = painting.canvas().cell(a.hit().u(), a.hit().v());
            int glow = MapColors.outline(under);
            String key = a.frame().getUniqueId() + ":" + cells[0] + "," + cells[1] + "," + cells[2] + "," + cells[3] + ":" + glow;
            Preview pv = previews.get(id);
            Entity e = pv == null ? null : Bukkit.getEntity(pv.entity);
            if (e instanceof BlockDisplay d && d.getWorld().equals(a.frame().getWorld())) {
                if (pv.key.equals(key)) continue;
                double[][] box = CanvasRay.box(face(a.frame()), cells[0], cells[1], cells[2], cells[3], PREVIEW_FRONT, PREVIEW_THICK);
                d.teleport(new Location(d.getWorld(), box[0][0], box[0][1], box[0][2], 0f, 0f));
                d.setTransformation(transform(box));
                d.setGlowColorOverride(Color.fromRGB(glow));
                pv.key = key;
            } else {
                if (pv != null) hidePreview(id);
                double[][] box = CanvasRay.box(face(a.frame()), cells[0], cells[1], cells[2], cells[3], PREVIEW_FRONT, PREVIEW_THICK);
                Location at = new Location(a.frame().getWorld(), box[0][0], box[0][1], box[0][2], 0f, 0f);
                BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, n -> {
                    n.setVisibleByDefault(false);
                    n.setPersistent(false);
                    n.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
                    n.setBrightness(new Display.Brightness(15, 15));
                    n.setGlowing(true);
                    n.setGlowColorOverride(Color.fromRGB(glow));
                    n.setTransformation(transform(box));
                });
                p.showEntity(plugin, d);
                Preview fresh = new Preview(d.getUniqueId());
                fresh.key = key;
                previews.put(id, fresh);
            }
        }
    }

    private static Transformation transform(double[][] box) {
        Vector3f scale = new Vector3f((float) (box[1][0] - box[0][0]), (float) (box[1][1] - box[0][1]), (float) (box[1][2] - box[0][2]));
        return new Transformation(new Vector3f(), new AxisAngle4f(), scale, new AxisAngle4f());
    }

    private void hidePreview(UUID player) {
        Preview pv = previews.remove(player);
        if (pv == null) return;
        Entity e = Bukkit.getEntity(pv.entity);
        if (e != null) e.remove();
    }

    // ------------------------------------------------------------------------------------------
    // The palette
    // ------------------------------------------------------------------------------------------

    private void openPalette(Player p, int mapId, int page) {
        new PaletteMenu(p, mapId, page).open(brush(p), item(BRUSH));
        p.playSound(p.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.6f, 1.3f);
    }

    @EventHandler
    public void onPaletteClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof PaletteMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Brush b = brush(p);
        int slot = e.getSlot();
        Byte colour = menu.colourAt(slot);
        boolean close = false;
        if (colour != null) {
            b.color = colour;
            if (b.tool == Tool.ERASER || b.tool == Tool.PICK) b.tool = Tool.PEN;
            close = true;
        } else {
            switch (slot) {
                case PaletteMenu.TOOL_PEN -> b.tool = Tool.PEN;
                case PaletteMenu.TOOL_ERASER -> b.tool = Tool.ERASER;
                case PaletteMenu.TOOL_FILL -> b.tool = Tool.FILL;
                case PaletteMenu.TOOL_PICK -> b.tool = Tool.PICK;
                case PaletteMenu.SIZE_1 -> b.size = 1;
                case PaletteMenu.SIZE_2 -> b.size = 2;
                case PaletteMenu.SIZE_3 -> b.size = 3;
                case PaletteMenu.PREV -> menu.page = Math.floorMod(menu.page - 1, MapColors.PAGES.size());
                case PaletteMenu.NEXT, PaletteMenu.PAGE -> menu.page = Math.floorMod(menu.page + 1, MapColors.PAGES.size());
                case PaletteMenu.UNDO -> undoLast(p, menu.mapId);
                case PaletteMenu.TAKE_OFF -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        store.get(menu.mapId).filter(pt -> mayManage(p, pt)).flatMap(this::easelOf).ifPresent(f -> takeOff(p, f));
                    });
                    return;
                }
                case PaletteMenu.FINISH -> {
                    Bukkit.getScheduler().runTask(plugin, () -> askTitle(p, menu.mapId));
                    return;
                }
                case PaletteMenu.CLOSE -> close = true;
                default -> {
                    return;
                }
            }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        if (close) Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        else menu.draw(b, item(BRUSH));
    }

    @EventHandler
    public void onPaletteDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof PaletteMenu) e.setCancelled(true);
    }

    private void undoLast(Player p, int mapId) {
        Optional<Painting> painting = store.get(mapId);
        if (painting.isEmpty() || painting.get().finished() || !p.getUniqueId().equals(painting.get().painter())) return;
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) endStroke(s);
        UndoStack u = undo(mapId);
        if (u.undo(painting.get().canvas())) {
            changed(painting.get());
            p.sendActionBar(Lang.mm("painting.undo", "left", u.size()));
        } else {
            p.sendActionBar(Lang.mm("painting.undo-none"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // 완성: a title, then the painting item
    // ------------------------------------------------------------------------------------------

    private void askTitle(Player p, int mapId) {
        Optional<Painting> painting = store.get(mapId);
        if (painting.isEmpty() || painting.get().finished() || !p.getUniqueId().equals(painting.get().painter())) {
            p.closeInventory();
            return;
        }
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) endStroke(s);
        plugin.menus().prompt(p, Lang.msg("painting.title-ask", "max", Painting.TITLE_MAX), answer -> {
            if (answer == null) {
                openPalette(p, mapId, 0);
                return;
            }
            String title = Painting.cleanTitle(answer);
            if (title == null) {
                p.sendMessage(Lang.msg("painting.title-empty"));
                return;
            }
            finish(p, mapId, title);
        });
    }

    private void finish(Player p, int mapId, String title) {
        Optional<Painting> op = store.get(mapId);
        if (op.isEmpty() || op.get().finished() || !p.getUniqueId().equals(op.get().painter())) return;
        Painting painting = op.get();
        Optional<Furniture> easel = easelOf(painting);
        if (easel.isEmpty()) {
            p.sendMessage(Lang.msg("painting.lost"));
            return;
        }
        takeDown(easel.get());
        painting.finish(title, System.currentTimeMillis());
        undo.remove(mapId);
        changed(painting);
        saveNow(painting);
        give(p, paintingItem(painting));
        p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.9f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
        p.sendMessage(Lang.msg("painting.done", "title", esc(title)));
    }

    // ------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------

    private boolean ourFrame(Entity e) {
        return e instanceof GlowItemFrame f && f.getPersistentDataContainer().has(keyEasel);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!holdsBrush(p)) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && !p.isSneaking() && aim(p) == null) return;   // a door, a chest...
        e.setCancelled(true);
        brushClick(p);
    }

    /** Right-click on a canvas frame: never turns or takes its map; the brush paints, anything else is an easel click. */
    @EventHandler(priority = EventPriority.LOW)
    public void onFrameClick(PlayerInteractEntityEvent e) {
        if (!ourFrame(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (holdsBrush(p)) {
            Integer on = e.getRightClicked().getPersistentDataContainer().get(keyCanvas, PersistentDataType.INTEGER);
            if (p.isSneaking() && on != null) paletteOnce(p, on);
            else brushClick(p);
            return;
        }
        String easel = e.getRightClicked().getPersistentDataContainer().get(keyEasel, PersistentDataType.STRING);
        if (easel != null && Bukkit.getEntity(UUID.fromString(easel)) instanceof ItemDisplay d) {
            plugin.furniture().fromDisplay(d).ifPresent(f -> easelClick(p, f));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFrameAttack(PrePlayerAttackEntityEvent e) {
        if (!ourFrame(e.getAttacked())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (holdsBrush(p)) {
            Aim a = aim(p);
            paletteOnce(p, a == null ? -1 : a.mapId());
        }
    }

    /** Opens the palette unless it just opened (one click can arrive as two events). */
    private void paletteOnce(Player p, int mapId) {
        Long last = lastPalette.get(p.getUniqueId());
        long now = Bukkit.getCurrentTick();
        if (last != null && now - last < 5) return;
        lastPalette.put(p.getUniqueId(), now);
        openPalette(p, mapId, 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDamage(EntityDamageEvent e) {
        if (ourFrame(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameBreak(HangingBreakEvent e) {
        if (ourFrame(e.getEntity())) e.setCancelled(true);
    }

    /**
     * Breaking (left-clicking) an easel: with the brush in hand it opens the palette instead; one with a canvas on it is
     * only taken down by its painter (or an admin), so nobody else knocks someone's canvas off.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEaselBreak(FurnitureBreakEvent e) {
        Furniture f = e.getFurniture();
        Player p = e.getPlayer();
        if (!isEasel(f) || p == null) return;
        if (holdsBrush(p)) {
            e.setCancelled(true);
            Integer on = canvasOn(f);
            paletteOnce(p, on == null ? -1 : on);
            return;
        }
        Integer on = canvasOn(f);
        if (on != null && store.get(on).map(pt -> !mayManage(p, pt)).orElse(false)) {
            e.setCancelled(true);
            hint(p, "painting.only-painter-break");
        }
    }

    /** A canvas frame or an easel coming into the world: still matching its painting? Otherwise it is a leftover. */
    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent e) {
        Entity en = e.getEntity();
        if (en instanceof GlowItemFrame f && f.getPersistentDataContainer().has(keyEasel)) {
            Bukkit.getScheduler().runTask(plugin, () -> checkFrame(f));
        } else if (en instanceof ItemDisplay d && d.getPersistentDataContainer().has(keyCanvas)) {
            Bukkit.getScheduler().runTask(plugin, () -> checkEasel(d));
        }
    }

    private void checkFrame(GlowItemFrame f) {
        if (!f.isValid()) return;
        PersistentDataContainer pdc = f.getPersistentDataContainer();
        String easel = pdc.get(keyEasel, PersistentDataType.STRING);
        Integer id = pdc.get(keyCanvas, PersistentDataType.INTEGER);
        if (easel == null || id == null) return;
        Optional<Painting> p = store.get(id);
        if (p.isEmpty() || !easel.equals(String.valueOf(p.get().mountedOn()))) {
            f.remove();
            return;
        }
        frames.put(id, f.getUniqueId());
        MapView view = Bukkit.getMap(id);
        if (view != null) prepare(view);
    }

    private void checkEasel(ItemDisplay d) {
        if (!d.isValid()) return;
        Integer id = d.getPersistentDataContainer().get(keyCanvas, PersistentDataType.INTEGER);
        if (id == null) return;
        Optional<Painting> p = store.get(id);
        if (p.isPresent() && d.getUniqueId().equals(p.get().mountedOn())) return;
        d.getPersistentDataContainer().remove(keyCanvas);
        d.getPersistentDataContainer().remove(keyFrame);
        plugin.furniture().fromDisplay(d).ifPresent(f -> plugin.furniture().setModel(f, null));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Session s = sessions.remove(id);
        if (s != null) endStroke(s);
        hidePreview(id);
        brushes.remove(id);
        lastTool.remove(id);
        lastHint.remove(id);
        lastPalette.remove(id);
    }

    // --- no copies: a painting's map is never cloned, locked, zoomed or crafted ----------------

    private boolean anyPainting(Inventory inv) {
        for (ItemStack it : inv.getContents()) if (paintingOf(it).isPresent()) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (paintingOf(it).isPresent()) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (e.getBlock().getState(false) instanceof Crafter c && anyPainting(c.getInventory())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCartographyPrepare(PrepareResultEvent e) {
        if (e.getInventory() instanceof CartographyInventory inv && anyPainting(inv)) e.setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartography(CartographyItemEvent e) {
        if (e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.RESULT && anyPainting(e.getInventory())) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendActionBar(Lang.mm("painting.no-copy"));
        }
    }

    /** For tests and tools: the custom item ids this module uses. */
    static List<String> itemIds() {
        return List.of(EASEL, BLANK, BRUSH, UNFINISHED, PAINTING);
    }
}
