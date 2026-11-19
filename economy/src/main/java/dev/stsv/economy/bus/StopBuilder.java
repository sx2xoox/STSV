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
            for (int up = 1; up <= DIG; up++) {   // the bay cut out of a hillside, high enough for the bus
                Block b = w.getBlockAt(c.x(), y + up, c.z());
                if (up <= 4 || !b.getType().isAir()) out.put(b, Material.AIR.createBlockData());
            }
            for (int down = 1; down <= 6; down++) {   // filled down to the ground (never left hanging)
                Block b = w.getBlockAt(c.x(), y - down, c.z());
                if (b.getType().isSolid()) break;
                out.put(b, Material.STONE.createBlockData());
            }
        }
        // the shoulder between the kerb and the bay: paved like the lanes and cleared (reflector posts, guardrails, a light's
        // post), so nothing stands between the lane and the bay; a light whose post goes loses its arm and lantern too
        java.util.Set<Long> bayAt = new java.util.HashSet<>();
        for (BusStop.Column c : s.bayColumns(cfg.bayLength(), cfg.bayWidth())) bayAt.add(((long) c.x() << 32) ^ (c.z() & 0xffffffffL));
        for (BusStop.Column c : s.shoulderColumns(cfg.bayLength())) {
            if (bayAt.contains(((long) c.x() << 32) ^ (c.z() & 0xffffffffL))) continue;
            BlockData top = s.slab() ? slabOf(surface) : surface.createBlockData();
            out.putIfAbsent(w.getBlockAt(c.x(), y, c.z()), top);
            for (int up = 1; up <= DIG; up++) {
                Block b = w.getBlockAt(c.x(), y + up, c.z());
                if (!b.getType().isAir()) out.putIfAbsent(b, Material.AIR.createBlockData());
            }
        }
        for (double a = -cfg.bayLength() / 2.0; a <= cfg.bayLength() / 2.0; a += 0.5) {
            for (double o = 1.5; o <= BusStop.KERB; o += 0.5) {   // over the edge lane: a light's arm and lantern
                double[] p = s.at(a, o);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                for (int up = 3; up <= 7; up++) {
                    Block b = w.getBlockAt(bx, y + up, bz);
                    if (isLight(b.getType())) out.putIfAbsent(b, Material.AIR.createBlockData());
                }
            }
        }
        // the shelter, a country bus stop: 5 along, 3 out, behind the bay. Walls of whitewashed cement (white terracotta) on a
        // cobblestone footing at the back and the sides, a little window in the back, log posts at the open front, a lean-to
        // roof of old tin (weathered copper, waxed: it rusts no further) sloping to the road, a wooden bench, a lantern under
        // the roof; a hay bale with a flower pot beside it, and the sign on a wooden pole by it
        int fy = y + 1;
        double out0 = s.shelterOut(cfg.bayWidth());
        BlockFace toRoad = face(-(-s.hz() * s.side()), -(s.hx() * s.side()));
        BlockFace away = toRoad.getOppositeFace();
        for (int a = -3; a <= 3; a++) {
            for (int o = -1; o <= 2; o++) {
                double[] p = s.at(a, out0 + o + 0.5);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                boolean inside = Math.abs(a) <= 2 && o >= 0;
                boolean hay = a == 3 && o == 1, pole = a == -3 && o == 0;
                if (inside || hay || pole) {
                    if (inside) out.put(w.getBlockAt(bx, fy - 1, bz), Material.SMOOTH_STONE.createBlockData());
                    for (int down = inside ? 2 : 1; down <= 7; down++) {   // down to the ground, never left hanging
                        Block b = w.getBlockAt(bx, fy - down, bz);
                        if (b.getType().isSolid()) break;
                        out.put(b, Material.STONE.createBlockData());
                    }
                }
                if (hay) {
                    out.put(w.getBlockAt(bx, fy, bz), Material.HAY_BLOCK.createBlockData());
                    out.put(w.getBlockAt(bx, fy + 1, bz), Material.POTTED_POPPY.createBlockData());
                    continue;
                }
                if (pole) {
                    for (int up = 0; up <= 2; up++) out.put(w.getBlockAt(bx, fy + up, bz), Material.SPRUCE_FENCE.createBlockData());
                    continue;
                }
                if (Math.abs(a) > 2) continue;
                if (o == -1) {   // the eave over the front, lowest
                    out.put(w.getBlockAt(bx, fy + 3, bz), stairs(TIN_STAIRS, away));
                    continue;
                }
                boolean side = Math.abs(a) == 2, back = o == 2;
                for (int up = 0; up <= 3; up++) {
                    Material m;
                    if (side && o == 0) m = up <= 2 ? Material.SPRUCE_LOG : TIN;                  // the front posts
                    else if (side || back) {
                        if (o == 0 && up == 3) m = TIN;
                        else m = up == 0 ? Material.COBBLESTONE : back && a == 0 && up == 2 ? Material.GLASS_PANE : Material.WHITE_TERRACOTTA;
                    } else if (o == 0 && up == 3) m = TIN;                                       // under the roof's front step
                    else m = Material.AIR;
                    out.put(w.getBlockAt(bx, fy + up, bz), m.createBlockData());
                }
                // the roof: one step up behind the eave, flat over the back
                out.put(w.getBlockAt(bx, fy + 4, bz), o == 0 ? stairs(TIN_STAIRS, away) : TIN.createBlockData());
                if (o == 1 && !side) {
                    if (a == 0) {
                        BlockData lamp = Material.LANTERN.createBlockData();
                        if (lamp instanceof org.bukkit.block.data.type.Lantern l) l.setHanging(true);
                        out.put(w.getBlockAt(bx, fy + 3, bz), lamp);
                    }
                    out.put(w.getBlockAt(bx, fy, bz), stairs(Material.SPRUCE_STAIRS, away));   // the bench, its back to the wall
                }
            }
        }
        // dug out of a hillside: nothing over the shelter, round it a block, up to DIG high (a notch in the slope, like the road's cuts)
        for (int a = -4; a <= 4; a++) {
            for (int o = -1; o <= 3; o++) {
                double[] p = s.at(a, out0 + o + 0.5);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                for (int up = 0; up <= DIG + 1; up++) {
                    Block b = w.getBlockAt(bx, fy + up, bz);
                    if (!b.getType().isAir() && !out.containsKey(b)) out.put(b, Material.AIR.createBlockData());
                }
            }
        }
        return out;
    }

    /** How high above the road a stop clears the ground (a stop on a hillside). */
    static final int DIG = 9;

    /** How many blocks of ground the plan digs away (the side of the road with less hill is the better one). */
    static int digCost(Map<Block, BlockData> plan) {
        int n = 0;
        for (Map.Entry<Block, BlockData> e : plan.entrySet()) {
            if (e.getValue().getMaterial().isAir() && !e.getKey().getType().isAir()) n++;
        }
        return n;
    }

    /** A street light's parts (its post, arm and lantern: RoadSection). */
    static boolean isLight(Material m) {
        String n = m.name();
        return n.contains("LANTERN") || n.endsWith("_WALL") || n.endsWith("_FENCE") || n.contains("CHAIN") || n.endsWith("_SLAB")
                || n.endsWith("_STAIRS") || n.equals("END_ROD") || n.contains("LIGHT") || n.equals("POLISHED_BASALT");
    }

    /** Old tin: weathered copper, waxed so it rusts no further. */
    static final Material TIN = Material.WAXED_WEATHERED_CUT_COPPER, TIN_STAIRS = Material.WAXED_WEATHERED_CUT_COPPER_STAIRS;

    private static BlockData stairs(Material m, BlockFace facing) {
        BlockData d = m.createBlockData();
        if (d instanceof Directional dir) dir.setFacing(facing);
        return d;
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

    /**
     * Why a stop cannot stand here, or null: on a bridge or over water (most of the ground where the bay and the shelter go
     * has nothing solid within 4 under the road's surface: water is not ground), or in a tunnel (a ceiling over the road).
     */
    static String siteProblem(World w, BusStop s, BusSpec.Stops cfg) {
        int y = s.y(), bad = 0, all = 0;
        double far = s.shelterOut(cfg.bayWidth()) + 3;
        for (double a = -cfg.bayLength() / 2.0; a <= cfg.bayLength() / 2.0; a += 2) {
            for (double o = BusStop.ROAD_HALF + 0.5; o <= far; o += 1.5) {
                double[] p = s.at(a, o);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                boolean ground = false;
                for (int down = 0; down <= 4 && !ground; down++) ground = w.getBlockAt(bx, y - down, bz).getType().isSolid();
                all++;
                if (!ground) bad++;
            }
        }
        if (bad * 4 > all) return "다리 위나 물가 · 낭떠러지";
        int roofed = 0;
        for (double a = -8; a <= 8; a += 4) {
            double[] p = s.at(a, 0);
            int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
            for (int up = 5; up <= 8; up++) {
                if (w.getBlockAt(bx, y + up, bz).getType().isSolid()) {
                    roofed++;
                    break;
                }
            }
        }
        return roofed >= 3 ? "굴 안" : null;
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
        sign(w, s, 0, "", cfg);
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

    /** Where the sign floats: on top of its wooden pole beside the shelter, facing the road. */
    static Location signAt(World w, BusStop s, BusSpec.Stops cfg) {
        double[] p = s.at(-3, s.shelterOut(cfg.bayWidth()) + 0.5);
        return new Location(w, Math.floor(p[0]) + 0.5, s.y() + 4.1, Math.floor(p[1]) + 0.5, s.facingRoad(), 0);
    }

    /** The sign (made if it is not there, cfg given): the stop's name and how many wait. */
    void sign(World w, BusStop s, int waiting, String lines, BusSpec.Stops cfg) {
        TextDisplay t = findSign(w, s);
        net.kyori.adventure.text.Component text = dev.stsv.core.util.Text.mm("<#3fb34f>🚏 <white><bold>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s.name())
                + "</bold>" + (lines.isEmpty() ? "" : "\n<#ffd166>" + lines) + "\n<gray>기다리는 손님 <white>" + waiting + "<gray>명");
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
