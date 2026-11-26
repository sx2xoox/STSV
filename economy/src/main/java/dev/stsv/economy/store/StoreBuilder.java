package dev.stsv.economy.store;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.economy.hwatu.hall.HallLayout;
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
 * Builds a 편의점 ({@link StoreLayout}) in the world or takes it away, the 도박장's way ({@code HallBuilder}): its chunks
 * loaded and held, about {@link HallBuilderSettings#blocksPerTick} blocks a tick, paused while the server is busy, every block
 * it changes written first to an undo file so {@link #remove} puts the ground back.
 *
 * <ul>
 *   <li>The furniture is core furniture ({@code FurnitureModule#placeAt}) turned and moved so its cells (items/store.yml)
 *       fill the layout's box ({@link StoreLayout#fit}); its display carries {@link #TAG} = the store's name and {@link #ROLE}
 *       (counter, hanger, shelf_snack …). A piece not in the content pack yet (or that does not fit) is named in
 *       {@link Placed#problems}: build again once it is there.</li>
 *   <li>The sign and other props are ItemDisplays, labels TextDisplays, all tagged {@link #TAG}.</li>
 *   <li>Building again first takes away everything tagged with the name (furniture through {@code FurnitureModule#discard}).</li>
 * </ul>
 */
public final class StoreBuilder {

    /** On every entity a store spawned: the store's name. */
    public static final NamespacedKey TAG = new NamespacedKey("economy", "store");
    /** On a piece of furniture's display: what it is in the store ({@link StoreLayout.Piece#role}). */
    public static final NamespacedKey ROLE = new NamespacedKey("economy", "store_role");

    /** How far props and labels are drawn (× 64 blocks at 100 % entity distance). */
    static final float PROP_VIEW = 1.25f, LABEL_VIEW = 0.6f;

    /** How fast it works: the 도박장's settings. */
    public record HallBuilderSettings(int blocksPerTick, LoadGate.Settings gate) {
        public static HallBuilderSettings of(dev.stsv.economy.hwatu.hall.HallBuilder.Settings s) {
            return new HallBuilderSettings(s.blocksPerTick(), s.gate());
        }
    }

    /** What did not go (empty: all of it). */
    public record Placed(String name, StoreLayout.Site site, List<String> problems) {}

    private record Op(int kind, int x, int y, int z, int depth, String data) {}

    private static final int CLEAR = 0, COLUMN = 1, BLOCK = 2;

    private final Plugin plugin;
    private final STSVPlugin core;
    private final String name;
    private final StoreLayout.Site site;
    private final World world;
    private final Path undo;
    private final HallBuilderSettings settings;
    private final BlockBudget budget;
    private final IntConsumer progress;
    private final boolean removing;
    private StoreLayout.Layout layout = StoreLayout.of();
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

    private StoreBuilder(Plugin plugin, STSVPlugin core, String name, StoreLayout.Site site, Path undo, HallBuilderSettings settings,
                         IntConsumer progress, boolean removing) {
        this.plugin = plugin;
        this.core = core;
        this.name = name;
        this.site = site;
        this.world = Bukkit.getWorld(site.world());
        if (world == null) throw new IllegalArgumentException("no world " + site.world());
        this.undo = undo;
        this.settings = settings;
        this.budget = new BlockBudget(Math.max(100, settings.blocksPerTick()));
        this.progress = progress == null ? p -> {} : progress;
        this.removing = removing;
    }

    /** Starts building (main thread); over a store of the same name it builds again in place. */
    public static StoreBuilder build(Plugin plugin, STSVPlugin core, String name, StoreLayout.Site site, Path undo, HallBuilderSettings settings,
                                     IntConsumer progress, Consumer<Placed> done) throws IOException {
        return build(plugin, core, name, site, StoreLayout.of(), undo, settings, progress, done);
    }

    /** The same with some furniture left out (a 내 가게 등급: {@link StoreLayout#of(java.util.Set)}). */
    public static StoreBuilder build(Plugin plugin, STSVPlugin core, String name, StoreLayout.Site site, StoreLayout.Layout layout, Path undo,
                                     HallBuilderSettings settings, IntConsumer progress, Consumer<Placed> done) throws IOException {
        StoreBuilder b = new StoreBuilder(plugin, core, name, site, undo, settings, progress, false);
        b.layout = layout;
        b.built = done;
        b.startBuild();
        return b;
    }

    /** Starts taking it away (main thread): its entities, then the blocks back from the undo file (deleted after). */
    public static StoreBuilder remove(Plugin plugin, STSVPlugin core, String name, StoreLayout.Site site, Path undo, HallBuilderSettings settings,
                                      IntConsumer progress, Consumer<Integer> done) throws IOException {
        StoreBuilder b = new StoreBuilder(plugin, core, name, site, undo, settings, progress, true);
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

    public boolean running() {
        return !finished;
    }

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
                plugin.getLogger().warning("[편의점] 되돌리기 파일을 닫지 못함: " + e);
            }
            out = null;
        }
    }

    private void startBuild() throws IOException {
        StoreLayout.Layout l = layout;
        if (undo != null) {
            Files.createDirectories(undo.toAbsolutePath().getParent());
            if (Files.exists(undo)) {
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
        LoadGate.Allow allow = LoadGate.now(settings.gate());
        if (!loads.finished()) {
            loads.pump(allow.concurrent());
            return;
        }
        if (!entitiesCleared) {
            if (!entitiesLoaded()) return;
            int gone = removeEntities(core, world, name, site);
            if (gone > 0) plugin.getLogger().info("[편의점] " + name + ": 전에 있던 것 " + gone + "개 치움");
            entitiesCleared = true;
        }
        budget.tick(allow.share());
        int total = removing ? restore.size() : ops.size();
        try {
            while (budget.any() && next < total) budget.spend(removing ? restoreOne(restore.get(next++)) : apply(ops.get(next++)));
            if (out != null) out.flush();
        } catch (IOException e) {
            plugin.getLogger().severe("[편의점] 되돌리기 파일을 쓰지 못해 짓기를 멈춤: " + e);
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
                plugin.getLogger().warning("[편의점] 되돌리기 파일을 지우지 못함: " + e);
            }
            finished = true;
            stop();
            if (removed != null) removed.accept(restore.size());
            return;
        }
        decorate();
        finished = true;
        stop();
        if (built != null) built.accept(new Placed(name, site, List.copyOf(problems)));
    }

    private boolean entitiesLoaded() {
        for (long[] c : site.chunks()) {
            if (!world.isChunkLoaded((int) c[0], (int) c[1])) continue;
            if (!world.getChunkAt((int) c[0], (int) c[1]).isEntitiesLoaded()) return false;
        }
        return true;
    }

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
            // a block this server no longer knows: left as built
        }
        return 1;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static boolean fillable(Block b) {
        Material m = b.getType();
        return m.isAir() || b.isLiquid() || b.isReplaceable() || !m.isSolid() || org.bukkit.Tag.LEAVES.isTagged(m) || org.bukkit.Tag.LOGS.isTagged(m);
    }

    // ------------------------------------------------------------------------------------------ props, labels, furniture

    private void decorate() {
        StoreLayout.Layout l = layout;
        for (HallLayout.Prop p : l.props()) {
            ItemStack shown = shown(core, p.item());
            double[] at = site.at(p.x(), p.y(), p.z());
            world.spawn(new Location(world, at[0], at[1], at[2], site.yaw(p.yaw()), 0), ItemDisplay.class, d -> {
                d.setPersistent(true);
                d.setItemStack(shown);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(new Transformation(new Vector3f(0, 0.5f * p.scale(), 0), new Quaternionf(),
                        new Vector3f(p.scale(), p.scale(), p.scale()), new Quaternionf()));
                d.setViewRange(PROP_VIEW);
                d.setBrightness(new Display.Brightness(15, 15));   // the lit sign
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
        for (StoreLayout.Piece p : l.pieces()) place(p);
        plugin.getLogger().info("[편의점] " + name + " 지음: " + site + (problems.isEmpty() ? "" : " · 문제 " + problems));
    }

    private void place(StoreLayout.Piece p) {
        Optional<CustomItem> item = core == null ? Optional.empty() : core.content().item(p.item());
        if (item.isEmpty() || !item.get().isFurniture()) {
            problems.add(p.role() + ": 가구 아이템 " + p.item() + " 없음");
            return;
        }
        StoreLayout.Fit fit = StoreLayout.fit(item.get().furniture().cells(), p);
        if (!fit.exact()) problems.add(p.role() + ": 칸 수가 자리와 다름 (가운데 맞춤)");
        int[] w = site.block(fit.x(), fit.y(), fit.z());
        Optional<Furniture> f = core.furniture().placeAt(item.get(), world.getBlockAt(w[0], w[1], w[2]), site.yaw(fit.yaw()));
        if (f.isEmpty()) {
            problems.add(p.role() + ": 자리가 맞지 않음 (" + w[0] + ", " + w[1] + ", " + w[2] + ")");
            return;
        }
        PersistentDataContainer pdc = f.get().data();
        pdc.set(TAG, PersistentDataType.STRING, name);
        pdc.set(ROLE, PersistentDataType.STRING, p.role());
    }

    /** What a display shows: the content item (its model), else a paper with the model economy:model/&lt;id&gt;. */
    static ItemStack shown(STSVPlugin core, String id) {
        Optional<CustomItem> c = core == null ? Optional.empty() : core.content().item(id);
        if (c.isPresent()) return c.get().create(1);
        ItemStack it = new ItemStack(Material.PAPER);
        NamespacedKey model = NamespacedKey.fromString(id.replace("economy:", "economy:model/"));
        if (model != null) it.editMeta(m -> m.setItemModel(model));
        return it;
    }

    // ------------------------------------------------------------------------------------------ finding it again

    /** The entities tagged with a store's name in its loaded chunks. */
    public static List<Entity> tagged(World world, String name, StoreLayout.Site site) {
        List<Entity> out = new ArrayList<>();
        for (long[] c : site.chunks()) {
            if (!world.isChunkLoaded((int) c[0], (int) c[1])) continue;
            for (Entity e : world.getChunkAt((int) c[0], (int) c[1]).getEntities()) {
                if (name.equals(e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING))) out.add(e);
            }
        }
        return out;
    }

    /** The store's furniture displays by role (in loaded chunks). */
    public static Map<String, UUID> pieces(World world, String name, StoreLayout.Site site) {
        Map<String, UUID> out = new HashMap<>();
        for (Entity e : tagged(world, name, site)) {
            String role = e.getPersistentDataContainer().get(ROLE, PersistentDataType.STRING);
            if (role != null && e instanceof ItemDisplay) out.put(role, e.getUniqueId());
        }
        return out;
    }

    /** Takes away a store's entities in its loaded chunks (furniture through the furniture module); how many. */
    public static int removeEntities(STSVPlugin core, World world, String name, StoreLayout.Site site) {
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
