package dev.stsv.economy.hwatu.hall;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.economy.road.BlockBudget;
import dev.stsv.economy.road.ChunkQueue;
import dev.stsv.economy.road.LoadGate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Builds the 도박장 ({@link HallLayout}) in the world, or takes it away again, spread over ticks as a 하역장 is built
 * (DockBuilder): its chunks loaded first (generated if new) a few at a time and held loaded (plugin chunk tickets) while it
 * works; about {@link Settings#blocksPerTick} blocks a tick ({@link BlockBudget}: a foundation column counts every block it
 * fills); paused or slowed while the server is busy ({@link LoadGate}). Every block it changes is written first to an undo
 * file (as a 정류장's: "x y z blockdata" per line, the first state kept when it is built again over itself), so
 * {@link #remove} puts the ground back as it was.
 *
 * <h2>Use (HwatuModule: commands, storage, the NPC, the games)</h2>
 * <pre>{@code
 * // /관리자 도박장 건설: where the admin stands, the way they look
 * HallLayout.Site site = new HallLayout.Site(w.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), HallLayout.Site.facing(loc.getYaw()));
 * HallBuilder job = HallBuilder.build(eco, STSVPlugin.get(), "main", site, dataDir.resolve("hall/main.undo"),
 *         HallBuilder.Settings.of(eco.getConfig()), pct -> admin.sendActionBar(...), placed -> {
 *             // placed.warp(): /화투방 target · placed.counterNpc(): spawn the 현장 소장 there
 *             // placed.whiteboard(): TextDisplay of 오늘의 큰 판 · placed.tables(): six, each with its furniture UUID,
 *             // game ("sutda" / "dice" / "holjjak") and room ("beginner" / "normal" / "high")
 *             // placed.problems(): what did not go (a table item missing from the content pack, a cell taken...)
 *         });
 * // keep site (world, x, y, z, facing) and the name somewhere; after a restart:
 * HallBuilder.Placed again = HallBuilder.placed(world, "main", site);   // anchors, and the tables found in loaded chunks
 * // /관리자 도박장 제거: entities gone, blocks back as they were (from the undo file), the file deleted
 * HallBuilder.remove(eco, STSVPlugin.get(), "main", site, undoFile, settings, pct -> ..., restored -> ...);
 * }</pre>
 *
 * <ul>
 *   <li>Only one job at a time is the caller's business (keep the returned job; {@link #running()}, {@link #cancel()}:
 *       a cancelled build leaves what it built so far, its undo file stays and is still good for {@link #remove}).</li>
 *   <li>The tables are core furniture ({@code FurnitureModule#placeAt}: no player, no place event, no behaviour
 *       onPlace) anchored at {@link HallLayout.Table#x}, y, z, front (seat 0) facing the door; their display entity
 *       carries {@link #TAG} = the hall's name, {@link #TABLE} = the table's index, {@link #GAME} and {@link #ROOM}. A
 *       table whose item is not in the content pack yet (or does not fit) is left out and named in
 *       {@link Placed#problems}: build again (it builds over itself) once it is there.</li>
 *   <li>Props are ItemDisplays of the economy:gamble_prop_* / gamble_whiteboard items' models (FIXED, the model's floor
 *       on the point), labels TextDisplays; all tagged {@link #TAG}. Building again first takes away everything tagged
 *       with the name (furniture through {@code FurnitureModule#discard}).</li>
 *   <li>Nothing is wired here: no commands, no listeners, no storage beyond the undo file.</li>
 * </ul>
 */
public final class HallBuilder {

    /** On every entity the hall spawned (props, labels, the tables' furniture display): the hall's name. */
    public static final NamespacedKey TAG = new NamespacedKey("economy", "gamble_hall");
    /** On a table's furniture display: its index in {@link HallLayout.Layout#tables} (integer). */
    public static final NamespacedKey TABLE = new NamespacedKey("economy", "gamble_hall_table");
    /** On a table's furniture display: its game ({@link HallLayout.Game#id}) and room ({@link HallLayout.Room#id}). */
    public static final NamespacedKey GAME = new NamespacedKey("economy", "gamble_game"), ROOM = new NamespacedKey("economy", "gamble_room");

    /** How far the props and labels are drawn (x 64 blocks at 100 % entity distance). */
    static final float PROP_VIEW = 1.25f, LABEL_VIEW = 1.0f;

    /**
     * How fast it works.
     *
     * @param blocksPerTick about this many blocks a tick
     * @param gate          when to pause / slow down, how many chunks it loads at once
     */
    public record Settings(int blocksPerTick, LoadGate.Settings gate) {
        public static final Settings DEFAULT = new Settings(1000, new LoadGate.Settings(35, 4, 2));

        /** The 하역장's settings (docks.build-blocks-per-tick, docks.max-mspt, docks.scout-concurrent[-online]). */
        public static Settings of(ConfigurationSection config) {
            if (config == null) return DEFAULT;
            return new Settings(Math.clamp(config.getInt("docks.build-blocks-per-tick", 1000), 100, 50000),
                    new LoadGate.Settings(config.getDouble("docks.max-mspt", 35), Math.clamp(config.getInt("docks.scout-concurrent", 4), 1, 16),
                            Math.clamp(config.getInt("docks.scout-concurrent-online", 2), 1, 16)));
        }
    }

    /**
     * A table in the world.
     *
     * @param furniture its furniture's display entity, or null (not placed / not found in a loaded chunk)
     * @param anchor    its anchor block's middle (feet level on the floor), its front's yaw
     */
    public record PlacedTable(HallLayout.Table table, Location anchor, Location centre, Location sign, UUID furniture) {
        public String game() {
            return table.game().id;
        }

        public String room() {
            return table.room().id;
        }

        public int index() {
            return table.index();
        }
    }

    /**
     * The hall in the world: where things are (each with the yaw it looks or faces), its tables.
     *
     * @param gate       inside the lot's gate, looking in
     * @param warp       just inside the hall's door, looking in (/화투방)
     * @param counterNpc the 현장 소장's place behind the desk, facing the customers
     * @param counter    where a customer stands at the desk, facing the boss
     * @param whiteboard the whiteboard's face (middle), facing the room
     * @param gallery    the 2층 구경석's front walkway, looking down at the back row
     * @param smoke      where a wisp of smoke may rise (over the tables, the drum fire, the lounge)
     * @param problems   what did not go (empty: all of it)
     */
    public record Placed(String name, HallLayout.Site site, Location gate, Location warp, Location counterNpc, Location counter,
                         Location whiteboard, Location gallery, List<Location> smoke, List<PlacedTable> tables, List<String> problems) {
        /** Is a point inside the hall (either floor)? */
        public boolean inside(Location at) {
            return at.getWorld() != null && site.inside(at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
        }
    }

    // ------------------------------------------------------------------------------------------

    private record Op(int kind, int x, int y, int z, int depth, String data) {}

    private static final int CLEAR = 0, COLUMN = 1, BLOCK = 2;

    private final Plugin plugin;
    private final STSVPlugin core;
    private final String name;
    private final HallLayout.Site site;
    private final World world;
    private final Path undo;
    private final Settings settings;
    private final BlockBudget budget;
    private final IntConsumer progress;
    private final boolean removing;
    private Consumer<Placed> built;
    private Consumer<Integer> removed;
    private final List<Op> ops = new ArrayList<>();
    private final List<String[]> restore = new ArrayList<>();
    private final Map<String, BlockData> parsed = new HashMap<>();
    private final Set<Long> recorded = new HashSet<>();
    private final Set<Chunk> held = new HashSet<>();
    private final List<String> problems = new ArrayList<>();
    private BufferedWriter out;
    private ChunkQueue loads;
    private BukkitTask task;
    private boolean entitiesCleared, finished;
    private int next, ticks;

    private HallBuilder(Plugin plugin, STSVPlugin core, String name, HallLayout.Site site, Path undo, Settings settings,
                        IntConsumer progress, boolean removing) {
        this.plugin = plugin;
        this.core = core;
        this.name = name;
        this.site = site;
        this.world = Bukkit.getWorld(site.world());
        if (world == null) throw new IllegalArgumentException("no world " + site.world());
        this.undo = undo;
        this.settings = settings == null ? Settings.DEFAULT : settings;
        this.budget = new BlockBudget(Math.max(100, this.settings.blocksPerTick()));
        this.progress = progress == null ? p -> {} : progress;
        this.removing = removing;
    }

    /**
     * Starts building the hall (main thread). Building over a hall of the same name rebuilds it in place (its old
     * entities go first; the undo file keeps the ground from before the first build).
     *
     * @param name     what its entities are tagged with (one name per hall: "main")
     * @param undo     where what it replaces is written (made if missing, appended to)
     * @param progress 0 .. 100 now and then (blocks)
     * @param done     when it is all there (main thread)
     * @throws IOException the undo file cannot be written: nothing was changed
     */
    public static HallBuilder build(Plugin plugin, STSVPlugin core, String name, HallLayout.Site site, Path undo, Settings settings,
                                    IntConsumer progress, Consumer<Placed> done) throws IOException {
        HallBuilder b = new HallBuilder(plugin, core, name, site, undo, settings, progress, false);
        b.built = done;
        b.startBuild();
        return b;
    }

    /**
     * Starts taking the hall away (main thread): every entity tagged with its name in its chunks (the tables through the
     * furniture module), then the blocks back as the undo file has them (last change first) and the file deleted. With
     * no undo file only the entities go.
     *
     * @param done how many blocks were put back (main thread)
     * @throws IOException the undo file cannot be read: nothing was changed
     */
    public static HallBuilder remove(Plugin plugin, STSVPlugin core, String name, HallLayout.Site site, Path undo, Settings settings,
                                     IntConsumer progress, Consumer<Integer> done) throws IOException {
        HallBuilder b = new HallBuilder(plugin, core, name, site, undo, settings, progress, true);
        b.removed = done;
        if (undo != null && Files.exists(undo)) {
            List<String> lines = Files.readAllLines(undo, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String[] p = lines.get(i).split(" ", 4);
                if (p.length == 4) b.restore.add(p);
            }
        }
        b.startLoads();
        return b;
    }

    /** Is it still working? */
    public boolean running() {
        return !finished;
    }

    /** Stops it where it is (main thread): no callback; the chunks are let go. */
    public void cancel() {
        finished = true;
        stop();
    }

    private void stop() {
        if (task != null) task.cancel();
        if (loads != null) loads.stop();
        for (Chunk c : held) c.removePluginChunkTicket(plugin);
        held.clear();
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                plugin.getLogger().warning("[도박장] 되돌리기 파일을 닫지 못함: " + e);
            }
            out = null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------------------------------

    private void startBuild() throws IOException {
        HallLayout.Layout l = HallLayout.of();
        if (undo != null) {
            Files.createDirectories(undo.toAbsolutePath().getParent());
            if (Files.exists(undo)) {   // built again: the first state stays the one to go back to
                for (String line : Files.readAllLines(undo, StandardCharsets.UTF_8)) {
                    String[] p = line.split(" ", 4);
                    if (p.length == 4) recorded.add(pack(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                }
            }
            out = Files.newBufferedWriter(undo, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        for (HallLayout.Box b : l.clear()) {
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++)
                    for (int y = b.minY(); y <= b.maxY(); y++) ops.add(new Op(CLEAR, x, y, z, 0, null));
        }
        for (HallLayout.Column c : l.columns()) ops.add(new Op(COLUMN, c.x(), c.fromY(), c.z(), c.depth(), site.state(c.data())));
        for (HallLayout.Block b : l.blocks()) ops.add(new Op(BLOCK, b.x(), b.y(), b.z(), 0, site.state(b.data())));
        startLoads();
    }

    private void startLoads() {
        loads = new ChunkQueue(world, settings.gate().most(), c -> {
            c.addPluginChunkTicket(plugin);
            held.add(c);
        });
        for (long[] c : site.chunks()) loads.add((int) c[0], (int) c[1]);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        if (finished) return;
        LoadGate.Allow allow = LoadGate.now(settings.gate());   // the server busy: pause or slow down
        if (!loads.finished()) {
            loads.pump(allow.concurrent());
            return;
        }
        if (!entitiesCleared) {
            if (!entitiesLoaded()) return;   // the chunks' entities come in a little after the chunks
            int gone = removeEntities(core, world, name, site);
            if (gone > 0) plugin.getLogger().info("[도박장] " + name + ": 전에 있던 것 " + gone + "개 치움");
            entitiesCleared = true;
        }
        budget.tick(allow.share());
        int total = removing ? restore.size() : ops.size();
        try {
            while (budget.any() && next < total) budget.spend(removing ? restoreOne(restore.get(next++)) : apply(ops.get(next++)));
            if (out != null) out.flush();
        } catch (IOException e) {
            plugin.getLogger().severe("[도박장] 되돌리기 파일을 쓰지 못해 짓기를 멈춤: " + e);
            problems.add("되돌리기 파일을 쓰지 못함: " + e.getMessage());
            cancel();
            return;
        }
        if (next < total) {
            if (++ticks % 5 == 0) progress.accept((int) (next * 100L / total));
            return;
        }
        if (removing) {
            try {
                if (undo != null) Files.deleteIfExists(undo);
            } catch (IOException e) {
                plugin.getLogger().warning("[도박장] 되돌리기 파일을 지우지 못함: " + e);
            }
            finished = true;
            stop();
            if (removed != null) removed.accept(restore.size());
            return;
        }
        Placed placed = decorate();
        finished = true;
        stop();
        if (built != null) built.accept(placed);
    }

    private boolean entitiesLoaded() {
        for (long[] c : site.chunks()) {
            if (!world.isChunkLoaded((int) c[0], (int) c[1])) continue;
            if (!world.getChunkAt((int) c[0], (int) c[1]).isEntitiesLoaded()) return false;
        }
        return true;
    }

    /** Puts one op into the world (what it was written first); what it cost (blocks looked at or set). */
    private int apply(Op o) throws IOException {
        int[] w = site.block(o.x(), o.y(), o.z());
        if (w[1] < world.getMinHeight() || w[1] >= world.getMaxHeight()) return 1;
        int cost = 1;
        switch (o.kind()) {
            case CLEAR -> {
                Block b = world.getBlockAt(w[0], w[1], w[2]);
                if (!b.getType().isAir()) {
                    record(b);
                    b.setType(Material.AIR, false);
                }
            }
            case COLUMN -> {
                BlockData data = parsed.computeIfAbsent(o.data(), Bukkit::createBlockData);
                for (int k = 0; k < o.depth(); k++) {
                    if (w[1] - k < world.getMinHeight()) break;
                    Block at = world.getBlockAt(w[0], w[1] - k, w[2]);
                    cost = k + 1;
                    if (!fillable(at)) break;
                    record(at);
                    at.setBlockData(data, false);
                }
            }
            default -> {
                Block b = world.getBlockAt(w[0], w[1], w[2]);
                BlockData data = parsed.computeIfAbsent(o.data(), Bukkit::createBlockData);
                if (!b.getBlockData().equals(data)) {
                    record(b);
                    b.setBlockData(data, false);
                }
            }
        }
        return cost;
    }

    private void record(Block b) throws IOException {
        if (out == null || !recorded.add(pack(b.getX(), b.getY(), b.getZ()))) return;
        out.write(b.getX() + " " + b.getY() + " " + b.getZ() + " " + b.getBlockData().getAsString());
        out.newLine();
    }

    private int restoreOne(String[] p) {
        try {
            world.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])).setBlockData(Bukkit.createBlockData(p[3]), false);
        } catch (IllegalArgumentException ignored) {
            // a block this server no longer knows: left as the hall made it
        }
        return 1;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Air, water, plants, snow, leaves, logs: a foundation goes down through it to the ground. */
    static boolean fillable(Block b) {
        Material m = b.getType();
        return m.isAir() || b.isLiquid() || b.isReplaceable() || !m.isSolid() || org.bukkit.Tag.LEAVES.isTagged(m) || org.bukkit.Tag.LOGS.isTagged(m);
    }

    // ------------------------------------------------------------------------------------------
    // the props, labels and tables
    // ------------------------------------------------------------------------------------------

    private Placed decorate() {
        HallLayout.Layout l = HallLayout.of();
        for (HallLayout.Prop p : l.props()) {
            ItemStack shown = shown(p.item());
            double[] at = site.at(p.x(), p.y(), p.z());
            world.spawn(new Location(world, at[0], at[1], at[2], site.yaw(p.yaw()), 0), ItemDisplay.class, d -> {
                d.setPersistent(true);
                d.setItemStack(shown);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                // the model's floor (model y 0) on the point: FIXED draws model (8, 8, 8) at the entity
                d.setTransformation(new Transformation(new Vector3f(0, 0.5f * p.scale(), 0), new Quaternionf(),
                        new Vector3f(p.scale(), p.scale(), p.scale()), new Quaternionf()));
                d.setViewRange(PROP_VIEW);
                d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, name);
            });
        }
        for (HallLayout.Label t : l.labels()) {
            double[] at = site.at(t.x(), t.y(), t.z());
            world.spawn(new Location(world, at[0], at[1], at[2], site.yaw(t.yaw()), 0), TextDisplay.class, d -> {
                d.setPersistent(true);
                d.text(dev.stsv.core.util.Text.mm(t.text()));
                d.setBillboard(Display.Billboard.FIXED);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                int a = t.background();
                d.setBackgroundColor(Color.fromARGB(a >>> 24, (a >> 16) & 0xFF, (a >> 8) & 0xFF, a & 0xFF));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(t.scale(), t.scale(), t.scale()), new Quaternionf()));
                d.setViewRange(LABEL_VIEW);
                d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, name);
            });
        }
        List<PlacedTable> tables = new ArrayList<>();
        for (HallLayout.Table t : l.tables()) tables.add(table(t, placeTable(t)));
        Placed p = anchors(name, site, world, tables, List.copyOf(problems));
        plugin.getLogger().info("[도박장] " + name + " 지음: " + site + (problems.isEmpty() ? "" : " · 문제 " + problems));
        return p;
    }

    /** What a prop shows: the content item's model (or, before the pack has the item, a model of the same id). */
    private ItemStack shown(String id) {
        NamespacedKey model = core == null ? null : core.content().item(id).map(CustomItem::itemModelKey).orElse(null);
        if (model == null) model = NamespacedKey.fromString(id);
        ItemStack it = new ItemStack(Material.PAPER);
        NamespacedKey m = model;
        if (m != null) it.editMeta(meta -> meta.setItemModel(m));
        return it;
    }

    private UUID placeTable(HallLayout.Table t) {
        Optional<CustomItem> item = core == null ? Optional.empty() : core.content().item(t.item());
        if (item.isEmpty() || !item.get().isFurniture()) {
            problems.add(t.title() + ": 가구 아이템 " + t.item() + " 없음");
            return null;
        }
        int[] w = site.block(t.x(), t.y(), t.z());
        Optional<Furniture> f = core.furniture().placeAt(item.get(), world.getBlockAt(w[0], w[1], w[2]), site.yaw(t.yaw()));
        if (f.isEmpty()) {
            problems.add(t.title() + ": 자리가 맞지 않음 (" + w[0] + ", " + w[1] + ", " + w[2] + ")");
            return null;
        }
        PersistentDataContainer pdc = f.get().data();
        pdc.set(TAG, PersistentDataType.STRING, name);
        pdc.set(TABLE, PersistentDataType.INTEGER, t.index());
        pdc.set(GAME, PersistentDataType.STRING, t.game().id);
        pdc.set(ROOM, PersistentDataType.STRING, t.room().id);
        return f.get().display().getUniqueId();
    }

    private PlacedTable table(HallLayout.Table t, UUID furniture) {
        double[] a = site.at(t.x() + 0.5, t.y(), t.z() + 0.5);
        float yaw = site.yaw(t.yaw());
        return new PlacedTable(t, new Location(world, a[0], a[1], a[2], yaw, 0), loc(world, site, t.centre()), loc(world, site, t.sign()), furniture);
    }

    // ------------------------------------------------------------------------------------------
    // looking it up again
    // ------------------------------------------------------------------------------------------

    /**
     * The hall as built at a site (after a restart): its anchors, and its tables' furniture found by their tags in the
     * chunks that are loaded (a table in an unloaded chunk, or never placed, has a null furniture).
     */
    public static Placed placed(World world, String name, HallLayout.Site site) {
        Map<Integer, UUID> found = new HashMap<>();
        for (Entity e : tagged(world, name, site)) {
            Integer i = e.getPersistentDataContainer().get(TABLE, PersistentDataType.INTEGER);
            if (i != null) found.put(i, e.getUniqueId());
        }
        List<PlacedTable> tables = new ArrayList<>();
        for (HallLayout.Table t : HallLayout.of().tables()) {
            double[] a = site.at(t.x() + 0.5, t.y(), t.z() + 0.5);
            tables.add(new PlacedTable(t, new Location(world, a[0], a[1], a[2], site.yaw(t.yaw()), 0), loc(world, site, t.centre()),
                    loc(world, site, t.sign()), found.get(t.index())));
        }
        return anchors(name, site, world, tables, List.of());
    }

    private static Placed anchors(String name, HallLayout.Site site, World world, List<PlacedTable> tables, List<String> problems) {
        HallLayout.Layout l = HallLayout.of();
        List<Location> smoke = new ArrayList<>();
        for (HallLayout.Point p : l.smoke()) smoke.add(loc(world, site, p));
        return new Placed(name, site, loc(world, site, l.gate()), loc(world, site, l.warp()), loc(world, site, l.counterNpc()),
                loc(world, site, l.counter()), loc(world, site, l.whiteboard()), loc(world, site, l.gallery()), List.copyOf(smoke),
                List.copyOf(tables), problems);
    }

    private static Location loc(World world, HallLayout.Site site, HallLayout.Point p) {
        double[] a = site.at(p.x(), p.y(), p.z());
        return new Location(world, a[0], a[1], a[2], site.yaw(p.yaw()), 0);
    }

    /** The entities tagged with the hall's name in its loaded chunks. */
    public static List<Entity> tagged(World world, String name, HallLayout.Site site) {
        List<Entity> out = new ArrayList<>();
        for (long[] c : site.chunks()) {
            if (!world.isChunkLoaded((int) c[0], (int) c[1])) continue;
            for (Entity e : world.getChunkAt((int) c[0], (int) c[1]).getEntities()) {
                if (name.equals(e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING))) out.add(e);
            }
        }
        return out;
    }

    /** Takes away the hall's entities in its loaded chunks (its tables through the furniture module); how many. */
    public static int removeEntities(STSVPlugin core, World world, String name, HallLayout.Site site) {
        int n = 0;
        for (Entity e : tagged(world, name, site)) {
            if (!e.isValid()) continue;
            Optional<Furniture> f = core != null && e instanceof ItemDisplay d ? core.furniture().fromDisplay(d) : Optional.empty();
            if (f.isPresent()) core.furniture().discard(f.get());
            else e.remove();
            n++;
        }
        return n;
    }
}
