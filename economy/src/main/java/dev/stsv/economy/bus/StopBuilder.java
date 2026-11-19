package dev.stsv.economy.bus;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Builds a 정류장 in the world (README "마을버스", 정류장 구조): the bay paved level with the road (its surface block, a yellow
 * edge, air cleared above, filled below), a shelter behind it (posts, a roof, a bench, a glass back) and a sign (a text display
 * with the stop's name and how many wait). Every block it changes is written first (bus/undo/&lt;id&gt;.txt) so the stop can be
 * taken away as it was. A column that may not be built on ({@code blocked}: land, the server's, roads.avoid) stops the build
 * before anything changes.
 */
final class StopBuilder {

    static final NamespacedKey SIGN = new NamespacedKey("economy", "bus_stop_sign");

    private final Path undoDir;

    StopBuilder(Path undoDir) {
        this.undoDir = undoDir;
    }

    /** The blocks the stop changes: block -> what it becomes (in build order: ground, then the shelter). */
    static Map<Block, BlockData> plan(World w, BusStop s, BusSpec.Stops cfg, Material surface) {
        Map<Block, BlockData> out = new LinkedHashMap<>();
        int y = s.y();
        Material edge = Material.YELLOW_CONCRETE;
        for (BusStop.Column c : s.bayColumns(cfg.bayLength(), cfg.bayWidth())) {
            Material m = c.edge() ? edge : surface;
            BlockData top = s.slab() ? slabOf(m) : m.createBlockData();
            out.put(w.getBlockAt(c.x(), y, c.z()), top);
            for (int up = 1; up <= 4; up++) out.put(w.getBlockAt(c.x(), y + up, c.z()), Material.AIR.createBlockData());
            for (int down = 1; down <= 3; down++) {
                Block b = w.getBlockAt(c.x(), y - down, c.z());
                if (!b.getType().isSolid()) out.put(b, Material.STONE.createBlockData());
            }
        }
        // the shelter: 5 along, 3 out, behind the bay; its floor level with the bay's top
        int fy = y + 1;
        double out0 = s.shelterOut(cfg.bayWidth());
        BlockFace toRoad = face(-(-s.hz() * s.side()), -(s.hx() * s.side()));
        for (int a = -2; a <= 2; a++) {
            for (int o = 0; o <= 2; o++) {
                double[] p = s.at(a, out0 + o + 0.5);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                out.put(w.getBlockAt(bx, fy - 1, bz), Material.POLISHED_ANDESITE.createBlockData());
                for (int down = 2; down <= 4; down++) {
                    Block b = w.getBlockAt(bx, fy - down, bz);
                    if (!b.getType().isSolid()) out.put(b, Material.STONE.createBlockData());
                }
                boolean post = Math.abs(a) == 2 && (o == 0 || o == 2);
                boolean back = o == 2 && !post;
                for (int up = 0; up <= 2; up++) {
                    Material m = post ? Material.DARK_OAK_FENCE : back ? Material.GLASS_PANE : Material.AIR;
                    out.put(w.getBlockAt(bx, fy + up, bz), m.createBlockData());
                }
                BlockData roof = slabOf(Material.DARK_OAK_SLAB);
                out.put(w.getBlockAt(bx, fy + 3, bz), roof);
                if (o == 1 && Math.abs(a) <= 1) {
                    BlockData bench = Material.SPRUCE_STAIRS.createBlockData();
                    if (bench instanceof Directional d) d.setFacing(toRoad.getOppositeFace());
                    out.put(w.getBlockAt(bx, fy, bz), bench);
                }
            }
        }
        return out;
    }

    private static BlockData slabOf(Material m) {
        Material s = dev.stsv.economy.road.RoadSection.slab(m);
        BlockData d = (s != null ? s : Material.SMOOTH_STONE_SLAB).createBlockData();
        if (d instanceof Slab sl) sl.setType(Slab.Type.BOTTOM);
        return d;
    }

    /** The block face nearest this way (x, z). */
    static BlockFace face(double x, double z) {
        if (Math.abs(x) > Math.abs(z)) return x > 0 ? BlockFace.EAST : BlockFace.WEST;
        return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** The first column of the plan that may not be built on, or null. */
    static int[] blocked(Map<Block, BlockData> plan, BiPredicate<Integer, Integer> blocked) {
        for (Block b : plan.keySet()) if (blocked.test(b.getX(), b.getZ())) return new int[]{b.getX(), b.getZ()};
        return null;
    }

    /** Whether the bay would hang over nothing (a bridge, a cliff): more than 3 of its columns with nothing solid 4 under. */
    static boolean overVoid(World w, BusStop s, BusSpec.Stops cfg) {
        int bad = 0;
        for (BusStop.Column c : s.bayColumns(cfg.bayLength(), cfg.bayWidth())) {
            boolean ground = false;
            for (int down = 0; down <= 4 && !ground; down++) ground = w.getBlockAt(c.x(), s.y() - down, c.z()).getType().isSolid();
            if (!ground && ++bad > 3) return true;
        }
        return false;
    }

    /** Builds it: writes what was there first, then sets the blocks and the sign. */
    void build(World w, BusStop s, Map<Block, BlockData> plan, BusSpec.Stops cfg) throws IOException {
        Files.createDirectories(undoDir);
        List<String> lines = new ArrayList<>();
        Path file = undoDir.resolve(s.id() + ".txt");
        if (Files.exists(file)) lines.addAll(Files.readAllLines(file));   // built again: the first state stays the one to go back to
        java.util.Set<String> had = new java.util.HashSet<>();
        for (String l : lines) {
            String[] p = l.split(" ", 4);
            if (p.length == 4) had.add(p[0] + " " + p[1] + " " + p[2]);
        }
        for (Block b : plan.keySet()) {
            String at = b.getX() + " " + b.getY() + " " + b.getZ();
            if (had.add(at)) lines.add(at + " " + b.getBlockData().getAsString());
        }
        Files.write(file, lines);
        for (Map.Entry<Block, BlockData> e : plan.entrySet()) e.getKey().setBlockData(e.getValue(), false);
        sign(w, s, 0, cfg);
    }

    /** Puts back what the stop changed and takes its sign away; false when there was no record. */
    boolean undo(World w, BusStop s) throws IOException {
        removeSign(w, s);
        Path file = undoDir.resolve(s.id() + ".txt");
        if (!Files.exists(file)) return false;
        List<String> lines = Files.readAllLines(file);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] p = lines.get(i).split(" ", 4);
            if (p.length < 4) continue;
            try {
                w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])).setBlockData(org.bukkit.Bukkit.createBlockData(p[3]), false);
            } catch (IllegalArgumentException ignored) {
                // a block this server no longer knows: left as the stop made it
            }
        }
        Files.delete(file);
        return true;
    }

    /** Where the sign floats: over the shelter's road side, facing the road. */
    static Location signAt(World w, BusStop s, BusSpec.Stops cfg) {
        double[] p = s.at(0, s.shelterOut(cfg.bayWidth()) + 0.2);
        return new Location(w, p[0], s.y() + 4.6, p[1], s.facingRoad(), 0);
    }

    /** The sign (made if it is not there, cfg given): the stop's name and how many wait. */
    void sign(World w, BusStop s, int waiting, BusSpec.Stops cfg) {
        TextDisplay t = findSign(w, s);
        net.kyori.adventure.text.Component text = dev.stsv.core.util.Text.mm("<#3fb34f>🚏 <white><bold>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s.name())
                + "</bold>\n<gray>기다리는 손님 <white>" + waiting + "<gray>명");
        if (t != null) {
            if (!text.equals(t.text())) t.text(text);
            return;
        }
        if (cfg == null) return;
        Location at = signAt(w, s, cfg);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        w.spawn(at, TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(160, 20, 40, 24));
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));
            d.getPersistentDataContainer().set(SIGN, PersistentDataType.INTEGER, s.id());
        });
    }

    static TextDisplay findSign(World w, BusStop s) {
        Location near = new Location(w, s.x(), s.y() + 3, s.z());
        if (!w.isChunkLoaded(near.getBlockX() >> 4, near.getBlockZ() >> 4)) return null;
        for (Entity e : w.getNearbyEntities(near, 16, 8, 16, e -> e instanceof TextDisplay)) {
            Integer id = e.getPersistentDataContainer().get(SIGN, PersistentDataType.INTEGER);
            if (id != null && id == s.id()) return (TextDisplay) e;
        }
        return null;
    }

    static void removeSign(World w, BusStop s) {
        TextDisplay t = findSign(w, s);
        if (t != null) t.remove();
    }
}
