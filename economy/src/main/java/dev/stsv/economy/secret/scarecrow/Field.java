package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.crop.CropSpec;
import dev.stsv.core.crop.CropState;
import dev.stsv.core.crop.Scarecrows;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.land.Land;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The world around a scarecrow: which scarecrows a player may call theirs (on land they own or live in, and working),
 * the real furniture entity, their nearest door and a window by it, the crops around it (to wither for them and to
 * ripen at dawn) and what they planted most there.
 */
final class Field {

    private final STSVPlugin core;
    private final EcoPlugin eco;

    Field(STSVPlugin core, EcoPlugin eco) {
        this.core = core;
        this.eco = eco;
    }

    private Scarecrows scarecrows() {
        return core.crops() == null ? null : core.crops().scarecrows();
    }

    /** The anchor block of a scarecrow, when its world is loaded. */
    static Optional<Block> block(Scarecrows.Scarecrow s) {
        World w = Bukkit.getWorld(s.world());
        return w == null ? Optional.empty() : Optional.of(w.getBlockAt(s.x(), s.y(), s.z()));
    }

    static String encode(Scarecrows.Scarecrow s) {
        return s.world() + " " + s.x() + " " + s.y() + " " + s.z() + " " + s.owner();
    }

    static Scarecrows.Scarecrow decode(String v) {
        if (v == null) return null;
        String[] w = v.split(" ");
        try {
            return w.length < 5 ? null : new Scarecrows.Scarecrow(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]), UUID.fromString(w[4]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether this scarecrow still stands and works, on land the player owns or lives in. */
    boolean theirs(Scarecrows.Scarecrow s, UUID u) {
        Scarecrows all = scarecrows();
        if (all == null || s == null) return false;
        Scarecrows.Scarecrow now = all.at(s.world(), s.cx(), s.cz());
        if (now == null || now.x() != s.x() || now.y() != s.y() || now.z() != s.z()) return false;
        return allows(s.world(), s.x(), s.y(), s.z(), u);
    }

    /** Whether the land at a spot is the player's (owned or lived in). No land module: never. */
    boolean allows(String world, int x, int y, int z, UUID u) {
        World w = Bukkit.getWorld(world);
        if (w == null || eco.land() == null) return false;
        Land l = eco.land().at(new Location(w, x, y, z));
        return l != null && !l.server() && l.allows(u);
    }

    boolean allows(Location at, UUID u) {
        return allows(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), u);
    }

    /** The nearest working scarecrow on the player's land within this many blocks of them. */
    Scarecrows.Scarecrow near(Player p, double radius) {
        Scarecrows all = scarecrows();
        if (all == null) return null;
        Location l = p.getLocation();
        String w = l.getWorld().getName();
        Scarecrows.Scarecrow best = null;
        double bd = radius * radius;
        for (Scarecrows.Scarecrow s : all.all()) {
            if (!s.world().equals(w)) continue;
            double dx = s.x() + 0.5 - l.getX(), dy = s.y() - l.getY(), dz = s.z() + 0.5 - l.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d > bd || !theirs(s, p.getUniqueId())) continue;
            bd = d;
            best = s;
        }
        return best;
    }

    /** Every scarecrow standing, with its owner (for the companion). */
    List<Scarecrows.Scarecrow> all() {
        Scarecrows all = scarecrows();
        return all == null ? List.of() : new ArrayList<>(all.all());
    }

    /** The real furniture entity of a scarecrow (its chunk must be loaded). */
    Optional<ItemDisplay> real(Scarecrows.Scarecrow s) {
        Optional<Block> b = block(s);
        if (b.isEmpty() || core.furniture() == null || !b.get().getWorld().isChunkLoaded(s.cx(), s.cz())) return Optional.empty();
        return core.furniture().furnitureAt(b.get()).filter(f -> f.item().key().asString().equals(Scarecrows.ITEM)).map(f -> f.display());
    }

    float yawOffset() {
        return (float) core.getConfig().getDouble("furniture.display-yaw-offset");
    }

    // ------------------------------------------------------------------------------------------
    // the house
    // ------------------------------------------------------------------------------------------

    /** The lower half of the nearest door on the player's land (any land when u is null) within radius of a spot, or null. */
    Block door(Location from, UUID u, int radius) {
        World w = from.getWorld();
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x * x + z * z > radius * radius) continue;
            for (int y = -5; y <= 6; y++) {
                Block b = w.getBlockAt(from.getBlockX() + x, from.getBlockY() + y, from.getBlockZ() + z);
                if (!Tag.DOORS.isTagged(b.getType())) continue;
                if (b.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP) continue;
                double d = b.getLocation().add(0.5, 0, 0.5).distanceSquared(from);
                if (d >= bd || (u != null && !allows(b.getLocation(), u))) continue;
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** Whether a block is the door (either half) given by its lower half. */
    static boolean sameDoor(Block lower, Block clicked) {
        if (lower == null || clicked == null || !lower.getWorld().equals(clicked.getWorld())) return false;
        return clicked.getX() == lower.getX() && clicked.getZ() == lower.getZ() && (clicked.getY() == lower.getY() || clicked.getY() == lower.getY() + 1);
    }

    /**
     * The side of a door that is outside: of its two open neighbours (along the way it faces), the one with more sky
     * light; a tie goes to the one nearer the given spot (the field). Returns the block one step out, at the door's feet.
     */
    Block outside(Block door, Location field) {
        BlockFace f = door.getBlockData() instanceof org.bukkit.block.data.Directional d ? d.getFacing() : BlockFace.NORTH;
        Block a = door.getRelative(f), b = door.getRelative(f.getOppositeFace());
        int la = a.getLightFromSky(), lb = b.getLightFromSky();
        if (la != lb) return la > lb ? a : b;
        if (field == null || field.getWorld() != door.getWorld()) return a;
        return a.getLocation().distanceSquared(field) <= b.getLocation().distanceSquared(field) ? a : b;
    }

    /** The nearest glass (block or pane) within 6 blocks of the door at about head height, or null. */
    Block window(Block door) {
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = 0; y <= 2; y++) {
            Block b = door.getRelative(x, y, z);
            String n = b.getType().name();
            if (!n.contains("GLASS")) continue;
            double d = b.getLocation().distanceSquared(door.getLocation());
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** Of a window's four sides, the open one with the most sky light (outside), or null. */
    static BlockFace outsideOf(Block window) {
        BlockFace best = null;
        int bl = -1;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = window.getRelative(f);
            if (!n.isPassable()) continue;
            int l = n.getLightFromSky();
            if (l > bl) {
                bl = l;
                best = f;
            }
        }
        return best;
    }

    /** A spot to stand near (x, y, z): passable feet and head over a solid block, searched up and down 6 blocks. */
    static Block ground(World w, double x, int y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int dy = 0; dy <= 6; dy++) {
            for (int sgn : new int[]{1, -1}) {
                Block feet = w.getBlockAt(bx, y + dy * sgn, bz);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                        && feet.getRelative(BlockFace.DOWN).getType().isSolid()) return feet;
                if (dy == 0) break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // the crops
    // ------------------------------------------------------------------------------------------

    private static boolean vanillaCrop(Block b) {
        return Tag.CROPS.isTagged(b.getType()) || Riddles.VANILLA.containsKey(b.getType().getKey().getKey());
    }

    /** The crop blocks (vanilla, and the blocks custom crops stand in) around a spot. */
    List<Block> crops(Block at, int radius) {
        List<Block> out = new ArrayList<>();
        var crops = core.crops();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) for (int y = -3; y <= 3; y++) {
            Block b = at.getRelative(x, y, z);
            if (b.getType() == Material.FARMLAND) continue;
            if (vanillaCrop(b) || (crops != null && crops.at(b).isPresent())) out.add(b);
        }
        return out;
    }

    /**
     * What the player planted most on the field around the scarecrow: custom crops they planted themselves, vanilla
     * crops on their land there. The produce ids tied for the most, with their seeds; empty when there is nothing.
     */
    Set<String> mostPlanted(Block at, UUID u, int radius) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, List<String>> seeds = new HashMap<>();
        var crops = core.crops();
        Map<String, CropSpec> specs = crops == null ? Map.of() : crops.specs();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (!at.getWorld().isChunkLoaded((at.getX() + x) >> 4, (at.getZ() + z) >> 4)) continue;
            for (int y = -6; y <= 6; y++) {
                Block b = at.getRelative(x, y, z);
                if (crops != null) {
                    Optional<CropState> st = crops.at(b);
                    if (st.isPresent()) {
                        CropSpec spec = specs.get(st.get().crop);
                        if (spec != null && u.equals(st.get().planter)) {
                            String produce = Riddles.norm(spec.produce());
                            counts.merge(produce, 1, Integer::sum);
                            seeds.put(produce, List.of(Riddles.norm(spec.seed())));
                        }
                        continue;
                    }
                }
                List<String> v = Riddles.VANILLA.get(b.getType().getKey().getKey());
                if (v == null || !allows(b.getLocation(), u)) continue;
                counts.merge(v.getFirst(), 1, Integer::sum);
                seeds.put(v.getFirst(), v.subList(1, v.size()));
            }
        }
        return Riddles.most(counts, seeds);
    }

    /** Dawn's gift: every crop around the scarecrow ripe (vanilla at once, custom ones grown to the end). */
    int ripen(Block at, int radius) {
        int n = 0;
        for (Block b : crops(at, radius)) {
            if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) {
                a.setAge(a.getMaximumAge());
                b.setBlockData(a, false);
                n++;
            }
        }
        var crops = core.crops();
        if (crops != null) for (int i = 0; i < 16; i++) n += crops.growAround(at.getWorld(), at.getX(), at.getY(), at.getZ(), radius, 3, 3, 1.0);
        return n;
    }
}
