package dev.stsv.economy.dock;

import dev.stsv.economy.road.BlockBudget;
import dev.stsv.economy.road.ChunkQueue;
import dev.stsv.economy.road.LoadGate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Puts a {@link DockLayout} into the world for a {@link Dock}, about a thousand blocks a tick (docks.build-blocks-per-tick,
 * {@link BlockBudget}: a foundation column counts every block it fills), so the server never stalls, pausing while it is
 * busy ({@link LoadGate}: docks.max-mspt): its chunks are loaded (generated if new) asynchronously first and held loaded
 * meanwhile (plugin chunk tickets); then air where it stands, foundations and pier posts down to the ground, the blocks bottom up, the
 * sign, and last the display models and the floating name (tagged {@link #TAG} with the dock's name, so 제거 finds them).
 * Terrain is not restored when a dock is removed.
 */
final class DockBuilder {

    /** On every display entity of a dock: its name. */
    static final NamespacedKey TAG = new NamespacedKey("economy", "dock");

    private record Op(int kind, int x, int y, int z, int depth, BlockData data) {}

    private static final int CLEAR = 0, COLUMN = 1, BLOCK = 2, LEGACY = 3;
    /** How far the props and the floating name are drawn (x 64 blocks at 100 % entity distance): about 80 and 96 blocks. */
    static final float PROP_VIEW = 1.25f, LABEL_VIEW = 1.5f;

    private final Plugin plugin;
    private final Dock dock;
    private final World world;
    private final DockLayout.Layout layout;
    private final BlockBudget budget;
    private final LoadGate.Settings gate;
    private final IntConsumer progress;
    private final Runnable done;
    private final List<Op> ops = new ArrayList<>();
    private final Set<Chunk> held = new HashSet<>();
    private ChunkQueue loads;
    private BukkitTask task;
    private int next, chunks, ticks;
    /** only the decorations (/관리자 하역장 다시꾸미기): no clearing, no foundations; old block containers go */
    private boolean decorOnly;

    /** Puts the layout's blocks and models again without touching the ground (see {@link #decorOnly}). */
    DockBuilder decorOnly() {
        decorOnly = true;
        return this;
    }

    DockBuilder(Plugin plugin, Dock dock, World world, int blocksPerTick, LoadGate.Settings gate, IntConsumer progress, Runnable done) {
        this.plugin = plugin;
        this.dock = dock;
        this.world = world;
        this.layout = dock.layout();
        this.budget = new BlockBudget(Math.max(100, blocksPerTick));
        this.gate = gate;
        this.progress = progress;
        this.done = done;
    }

    void start() {
        Map<String, BlockData> parsed = new HashMap<>();
        BlockData air = Material.AIR.createBlockData();
        if (decorOnly) {
            // the containers that were blocks (docks built before they became models): gone where the layout puts nothing
            java.util.Set<String> taken = new HashSet<>();
            for (DockLayout.Block b : layout.blocks()) taken.add(b.x() + "," + b.y() + "," + b.z());
            for (int[] c : DockLayout.legacyContainerCells()) if (!taken.contains(c[0] + "," + c[1] + "," + c[2])) ops.add(new Op(LEGACY, c[0], c[1], c[2], 0, air));
        }
        for (DockLayout.Box b : decorOnly ? List.<DockLayout.Box>of() : layout.clear()) {
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++)
                    for (int y = b.minY(); y <= b.maxY(); y++) ops.add(new Op(CLEAR, x, y, z, 0, air));
        }
        for (DockLayout.Column c : decorOnly ? List.<DockLayout.Column>of() : layout.columns())
            ops.add(new Op(COLUMN, c.x(), c.fromY(), c.z(), c.depth(), parsed.computeIfAbsent(c.data(), Bukkit::createBlockData)));
        for (DockLayout.Block b : layout.blocks())
            ops.add(new Op(BLOCK, b.x(), b.y(), b.z(), 0, parsed.computeIfAbsent(b.data(), Bukkit::createBlockData)));
        loads = new ChunkQueue(world, gate.most(), c -> {
            c.addPluginChunkTicket(plugin);
            held.add(c);
        });
        for (long[] c : dock.chunksTouched()) loads.add((int) c[0], (int) c[1]);
        chunks = dock.chunksTouched().size();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void cancel() {
        if (task != null) task.cancel();
        if (loads != null) loads.stop();
        for (Chunk c : held) c.removePluginChunkTicket(plugin);
        held.clear();
    }

    private void tick() {
        LoadGate.Allow allow = LoadGate.now(gate);   // the server busy: pause or slow down
        if (!loads.finished()) {
            loads.pump(allow.concurrent());
            return;
        }
        budget.tick(allow.share());
        while (budget.any() && next < ops.size()) budget.spend(apply(ops.get(next++)));
        if (next < ops.size()) {
            if (++ticks % 5 == 0) progress.accept((int) (next * 100L / ops.size()));
            return;
        }
        signs();
        props();
        cancel();
        done.run();
    }

    /** Puts one op into the world; what it cost (blocks looked at or set: a column, each one it went down). */
    private int apply(Op o) {
        int[] w = dock.block(o.x(), o.y(), o.z());
        if (w[1] < world.getMinHeight() || w[1] >= world.getMaxHeight()) return 1;
        Block b = world.getBlockAt(w[0], w[1], w[2]);
        int cost = 1;
        switch (o.kind()) {
            case CLEAR -> {
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
            case LEGACY -> {   // only what a block container was made of
                String n = b.getType().name();
                if (n.endsWith("_CONCRETE") || n.endsWith("_TERRACOTTA")) b.setType(Material.AIR, false);
            }
            case COLUMN -> {
                for (int k = 0; k < o.depth(); k++) {
                    Block at = world.getBlockAt(w[0], w[1] - k, w[2]);
                    if (at.getY() < world.getMinHeight()) break;
                    cost = k + 1;
                    if (!fillable(at)) break;
                    at.setBlockData(o.data(), false);
                }
            }
            default -> b.setBlockData(o.data(), false);
        }
        return cost;
    }

    /** Air, water, lava, plants, snow: a foundation or a post goes down through it to the ground. */
    static boolean fillable(Block b) {
        Material m = b.getType();
        return m.isAir() || b.isLiquid() || b.isReplaceable() || !m.isSolid() || m == Material.KELP_PLANT || m == Material.SEAGRASS
                || org.bukkit.Tag.LEAVES.isTagged(m) || org.bukkit.Tag.LOGS.isTagged(m);
    }

    private void signs() {
        for (DockLayout.Sign s : layout.signs()) {
            int[] w = dock.block(s.x(), s.y(), s.z());
            Block b = world.getBlockAt(w[0], w[1], w[2]);
            BlockData data = Material.SPRUCE_SIGN.createBlockData();
            if (data instanceof Rotatable r) r.setRotation(rotation((dock.facing() * 4 + 8) % 16));
            b.setBlockData(data, false);
            if (b.getState() instanceof org.bukkit.block.Sign sign) {
                for (int i = 0; i < Math.min(4, s.lines().size()); i++) sign.getSide(Side.FRONT).line(i, Component.text(s.lines().get(i)));
                sign.setWaxed(true);
                sign.update(true, false);
            }
        }
    }

    /** A sign rotation (0 = facing south, 4 west, 8 north, 12 east). */
    private static org.bukkit.block.BlockFace rotation(int r) {
        org.bukkit.block.BlockFace[] faces = {org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.SOUTH_SOUTH_WEST,
                org.bukkit.block.BlockFace.SOUTH_WEST, org.bukkit.block.BlockFace.WEST_SOUTH_WEST, org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.WEST_NORTH_WEST, org.bukkit.block.BlockFace.NORTH_WEST, org.bukkit.block.BlockFace.NORTH_NORTH_WEST,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.NORTH_NORTH_EAST, org.bukkit.block.BlockFace.NORTH_EAST,
                org.bukkit.block.BlockFace.EAST_NORTH_EAST, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.EAST_SOUTH_EAST,
                org.bukkit.block.BlockFace.SOUTH_EAST, org.bukkit.block.BlockFace.SOUTH_SOUTH_EAST};
        return faces[Math.floorMod(r, 16)];
    }

    private void props() {
        removeEntities(world, dock.name(), dock);
        float yaw = Dock.yaw(dock.facing());
        for (DockLayout.Prop p : layout.props()) {
            double[] at = dock.at(p.x(), p.y(), p.z());
            ItemStack shown = new ItemStack(Material.PAPER);
            shown.editMeta(m -> m.setItemModel(new NamespacedKey("economy", "model/" + p.model())));
            world.spawn(new Location(world, at[0], at[1], at[2], yaw + p.yaw(), 0), ItemDisplay.class, d -> {
                d.setPersistent(true);
                d.setItemStack(shown);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                // the model's floor (model y 0) at the point: FIXED draws model (8, 8, 8) at the entity
                d.setTransformation(new Transformation(new Vector3f(0, 0.5f * p.scale(), 0), new Quaternionf(),
                        new Vector3f(p.scale(), p.scale(), p.scale()), new Quaternionf()));
                d.setViewRange(PROP_VIEW);
                d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, dock.name());
            });
        }
        double[] l = dock.at(layout.label()[0], layout.label()[1], layout.label()[2]);
        world.spawn(new Location(world, l[0], l[1], l[2]), TextDisplay.class, t -> {
            t.setPersistent(true);
            t.text(dev.stsv.core.util.Text.mm(dock.kind() == DockLayout.Kind.TUNING
                    ? "<red><bold>" + DockLayout.tuningTitle(dock.name()) + "</bold>\n<gray>차를 안에 세우고 작업대 우클릭"
                    : dock.kind() == DockLayout.Kind.TERMINAL
                    ? "<aqua><bold>" + DockLayout.terminalTitle(dock.name()) + "</bold>\n<gray>검사대에 세우고 창구 우클릭 = 화물 검사"
                    : "<gold><bold>" + DockLayout.title(dock.name()) + "</bold>\n<gray>" + dock.kind().word + " · 웅크리고 짐칸 우클릭 = 판매"));
            t.setBillboard(Display.Billboard.CENTER);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.5f, 2.5f, 2.5f), new Quaternionf()));
            t.setViewRange(LABEL_VIEW);
            t.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, dock.name());
        });
    }

    /** Removes a dock's display entities in the loaded chunks it touches. */
    static int removeEntities(World world, String name, Dock dock) {
        int n = 0;
        for (long[] c : dock.chunksTouched()) {
            if (!world.isChunkLoaded((int) c[0], (int) c[1])) continue;
            for (Entity e : world.getChunkAt((int) c[0], (int) c[1]).getEntities()) {
                if (name.equals(e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING))) {
                    e.remove();
                    n++;
                }
            }
        }
        return n;
    }

    int chunks() {
        return chunks;
    }
}
