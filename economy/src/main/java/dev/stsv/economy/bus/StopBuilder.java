package dev.stsv.economy.bus;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Orientable;
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

    /** The blocks a country stop changes (the shape, and so what it digs, is the same for every theme). */
    static Map<Block, BlockData> plan(World w, BusStop s, BusSpec.Stops cfg, Material surface) {
        return plan(w, s, cfg, surface, Theme.COUNTRY);
    }

    /** The blocks the stop changes: block -> what it becomes (in build order: ground, then the shelter, dressed by its theme). */
    static Map<Block, BlockData> plan(World w, BusStop s, BusSpec.Stops cfg, Material surface, Theme theme) {
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
        // the shelter, 5 along, 3 out, behind the bay; how it looks follows the stop's theme (the same shape for every theme).
        // The country stop: walls of whitewashed cement (white terracotta) on a cobblestone footing at the back and the sides,
        // a little window in the back, log posts at the open front, a lean-to roof of old tin (weathered copper, waxed: it
        // rusts no further) sloping to the road, a wooden bench, a copper bulb in the ceiling over it (lit at night: setLamp);
        // a hay bale with a flower pot beside it, and the sign on a wooden pole by it
        Look k = theme.look();
        int fy = y + 1;
        double out0 = s.shelterOut(cfg.bayWidth());
        BlockFace toRoad = face(-(-s.hz() * s.side()), -(s.hx() * s.side()));
        BlockFace away = toRoad.getOppositeFace();
        BlockFace along = face(s.hx(), s.hz());
        org.bukkit.Axis alongAxis = along.getModX() != 0 ? org.bukkit.Axis.X : org.bukkit.Axis.Z;
        org.bukkit.Axis acrossAxis = alongAxis == org.bukkit.Axis.X ? org.bukkit.Axis.Z : org.bukkit.Axis.X;
        for (int a = -3; a <= 3; a++) {
            for (int o = -1; o <= 2; o++) {
                double[] p = s.at(a, out0 + o + 0.5);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                boolean inside = Math.abs(a) <= 2 && o >= 0;
                boolean hay = a == 3 && o == 1, pole = a == -3 && o == 0;
                if (inside || hay || pole) {
                    if (inside) out.put(w.getBlockAt(bx, fy - 1, bz), k.floor().createBlockData());
                    for (int down = inside ? 2 : 1; down <= 7; down++) {   // down to the ground, never left hanging
                        Block b = w.getBlockAt(bx, fy - down, bz);
                        if (b.getType().isSolid()) break;
                        out.put(b, Material.STONE.createBlockData());
                    }
                }
                if (hay) {   // the prop beside it (a hay bale, barrels, a woodpile ...) with something on top
                    BlockData lo = k.propLow().createBlockData(), hi = k.propHigh().createBlockData();
                    if (theme == Theme.MOUNTAIN) {   // a woodpile: the logs' ends to the road
                        lo = axis(lo, acrossAxis);
                        hi = axis(hi, acrossAxis);
                    }
                    out.put(w.getBlockAt(bx, fy, bz), lo);
                    out.put(w.getBlockAt(bx, fy + 1, bz), hi);
                    if (k.propTop() != null) out.put(w.getBlockAt(bx, fy + 2, bz), k.propTop().createBlockData());
                    continue;
                }
                if (pole) {
                    for (int up = 0; up <= 2; up++) out.put(w.getBlockAt(bx, fy + up, bz), k.pole().createBlockData());
                    continue;
                }
                if (Math.abs(a) > 2) continue;
                if (o == -1) {   // the eave over the front, lowest
                    out.put(w.getBlockAt(bx, fy + 3, bz), stairs(k.roofStairs(), away));
                    continue;
                }
                boolean side = Math.abs(a) == 2, back = o == 2;
                for (int up = 0; up <= 3; up++) {
                    Material m;
                    if (side && o == 0) m = up <= 2 ? k.post() : k.roof();                       // the front posts
                    else if (side || back) {
                        if (o == 0 && up == 3) m = k.roof();
                        else if (up == 0) m = k.footing();
                        else if (back && !side && (k.wideWindow() ? up <= 2 : a == 0 && up == 2)) m = k.window();
                        else m = up == 3 ? k.trim() : k.wall();
                    } else if (o == 0 && up == 3) m = k.roof();                                   // under the roof's front step
                    else m = Material.AIR;
                    BlockData d = m.createBlockData();
                    if (theme != Theme.COUNTRY) {
                        // logs lie along their wall (a log cabin), panes and bars join their neighbours in the wall
                        boolean wallLog = (side || back) && !(side && o == 0) && (m.name().endsWith("_LOG") || m.name().endsWith("_WOOD"));
                        if (wallLog) d = axis(d, side && !back ? acrossAxis : alongAxis);
                        if (m == k.window() && d instanceof org.bukkit.block.data.MultipleFacing f) {
                            if (f.getAllowedFaces().contains(along)) f.setFace(along, true);
                            if (f.getAllowedFaces().contains(along.getOppositeFace())) f.setFace(along.getOppositeFace(), true);
                        }
                    }
                    out.put(w.getBlockAt(bx, fy + up, bz), d);
                }
                // the roof: one step up behind the eave, flat over the back; the lamp in it over the bench
                BlockData roof;
                if (o == 0) roof = stairs(k.roofStairs(), away);
                else if (o == 1 && a == 0) {
                    roof = LAMP.createBlockData();
                    if (roof instanceof Lightable l) l.setLit(night(w.getTime()));
                } else roof = k.roof().createBlockData();
                out.put(w.getBlockAt(bx, fy + 4, bz), roof);
                if (k.roofMoss() && o >= 1 && (side || back) && (a + o) % 2 != 0) out.put(w.getBlockAt(bx, fy + 5, bz), Material.MOSS_CARPET.createBlockData());
                if (o == 1 && !side) out.put(w.getBlockAt(bx, fy, bz), stairs(k.bench(), away));   // the bench, its back to the wall
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

    /** The ceiling lamp over the bench: a copper bulb (waxed: it keeps its colour), lit at night. */
    static final Material LAMP = Material.WAXED_WEATHERED_COPPER_BULB;

    /**
     * What a shelter looks like, by its stop's traits (README "마을버스"): the country stop, or one dressed for the sea, a
     * mountain pass, the fields, a forest, a port or a village. The shape is the same; only the blocks and the props change.
     */
    enum Theme {
        COUNTRY(new Look(Material.SMOOTH_STONE, Material.COBBLESTONE, Material.WHITE_TERRACOTTA, Material.WHITE_TERRACOTTA,
                Material.GLASS_PANE, false, Material.SPRUCE_LOG, TIN, TIN_STAIRS, Material.SPRUCE_STAIRS,
                Material.HAY_BLOCK, Material.POTTED_POPPY, null, Material.SPRUCE_FENCE, false)),
        /** 갈매기 쉼터: white and sea-blue painted walls, a sea-green copper roof, a barrel with a harbour lantern. */
        SEA(new Look(Material.SMOOTH_SANDSTONE, Material.CYAN_TERRACOTTA, Material.WHITE_CONCRETE, Material.LIGHT_BLUE_TERRACOTTA,
                Material.GLASS_PANE, false, Material.STRIPPED_BIRCH_LOG, Material.WAXED_OXIDIZED_CUT_COPPER,
                Material.WAXED_OXIDIZED_CUT_COPPER_STAIRS, Material.BIRCH_STAIRS, Material.BARREL, Material.LANTERN, null,
                Material.BIRCH_FENCE, false)),
        /** 다람쥐 고개: a log cabin on a stone footing under a slate roof, a woodpile beside it. */
        MOUNTAIN(new Look(Material.SPRUCE_PLANKS, Material.STONE_BRICKS, Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_LOG,
                Material.GLASS_PANE, false, Material.SPRUCE_LOG, Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_STAIRS,
                Material.SPRUCE_STAIRS, Material.SPRUCE_LOG, Material.SPRUCE_LOG, null, Material.DARK_OAK_FENCE, false)),
        /** 허수아비 앞: a 원두막 of bamboo and oak under a thatch of hay, a hay bale with a pumpkin on it. */
        FIELD(new Look(Material.OAK_PLANKS, Material.MUD_BRICKS, Material.STRIPPED_BAMBOO_BLOCK, Material.BAMBOO_BLOCK,
                Material.BAMBOO_FENCE, false, Material.OAK_LOG, Material.HAY_BLOCK, Material.BAMBOO_MOSAIC_STAIRS,
                Material.OAK_STAIRS, Material.HAY_BLOCK, Material.PUMPKIN, null, Material.OAK_FENCE, false)),
        /** 도토리 숲길: mangrove and dark oak on a mossy footing, moss on the roof, a stump with a potted mushroom. */
        FOREST(new Look(Material.SPRUCE_PLANKS, Material.MOSSY_COBBLESTONE, Material.MANGROVE_PLANKS, Material.DARK_OAK_LOG,
                Material.GLASS_PANE, false, Material.DARK_OAK_LOG, Material.DARK_OAK_PLANKS, Material.DARK_OAK_STAIRS,
                Material.SPRUCE_STAIRS, Material.OAK_LOG, Material.POTTED_RED_MUSHROOM, null, Material.DARK_OAK_FENCE, true)),
        /** 등대 앞: a shipping container (orange, blue trim) with a barred window, stacked barrels beside it. */
        PORT(new Look(Material.SMOOTH_STONE, Material.GRAY_CONCRETE, Material.ORANGE_CONCRETE, Material.BLUE_CONCRETE,
                Material.IRON_BARS, false, Material.GRAY_CONCRETE, Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_STAIRS,
                Material.SPRUCE_STAIRS, Material.BARREL, Material.BARREL, null, Material.DARK_OAK_FENCE, false)),
        /** 느티나무 아래: a town stop of grey cement with a glass back, a red vending machine with a flower pot on it. */
        VILLAGE(new Look(Material.SMOOTH_STONE, Material.GRAY_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.WHITE_CONCRETE,
                Material.GLASS, true, Material.WHITE_CONCRETE, Material.POLISHED_ANDESITE, Material.POLISHED_ANDESITE_STAIRS,
                Material.BIRCH_STAIRS, Material.RED_CONCRETE, Material.RED_STAINED_GLASS, Material.POTTED_RED_TULIP,
                Material.IRON_BARS, false));

        private final Look look;

        Theme(Look look) {
            this.look = look;
        }

        Look look() {
            return look;
        }
    }

    /**
     * A theme's blocks: the floor, the walls' footing, the walls, their top row (trim), the back window (wideWindow: the
     * whole middle of the back), the front posts, the roof and its steps, the bench, the prop beside the shelter (low, high,
     * on top: may be null), the sign's pole, and moss on the roof.
     */
    record Look(Material floor, Material footing, Material wall, Material trim, Material window, boolean wideWindow,
                Material post, Material roof, Material roofStairs, Material bench, Material propLow, Material propHigh,
                Material propTop, Material pole, boolean roofMoss) {
    }

    /** The theme for a stop of these traits: port, sea, mountain, forest, field, village in that order; else the country stop. */
    static Theme theme(java.util.Collection<String> traits) {
        if (traits == null) return Theme.COUNTRY;
        for (Theme t : new Theme[]{Theme.PORT, Theme.SEA, Theme.MOUNTAIN, Theme.FOREST, Theme.FIELD, Theme.VILLAGE}) {
            if (traits.contains(t.name().toLowerCase(java.util.Locale.ROOT))) return t;
        }
        return Theme.COUNTRY;
    }

    /** Whether the stop's lamp is lit at this world time (dusk to dawn). */
    static boolean night(long time) {
        long t = Math.floorMod(time, 24000L);
        return t >= 12500 && t < 23500;
    }

    /** The block the stop's ceiling lamp is in (over the middle of the bench). */
    static Block lampAt(World w, BusStop s, BusSpec.Stops cfg) {
        double[] p = s.at(0, s.shelterOut(cfg.bayWidth()) + 1 + 0.5);
        return w.getBlockAt((int) Math.floor(p[0]), s.y() + 1 + 4, (int) Math.floor(p[1]));
    }

    /** Lights the stop's ceiling lamp, or puts it out; nothing when its place is not loaded or the lamp is gone (an old stop). */
    static void setLamp(World w, BusStop s, BusSpec.Stops cfg, boolean on) {
        double[] p = s.at(0, s.shelterOut(cfg.bayWidth()) + 1 + 0.5);
        if (!w.isChunkLoaded((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4)) return;
        Block b = lampAt(w, s, cfg);
        if (!b.getType().name().endsWith("COPPER_BULB")) return;
        if (b.getBlockData() instanceof Lightable l && l.isLit() != on) {
            l.setLit(on);
            b.setBlockData(l, false);
        }
    }

    private static BlockData axis(BlockData d, org.bukkit.Axis axis) {
        if (d instanceof Orientable or && or.getAxes().contains(axis)) or.setAxis(axis);
        return d;
    }

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
