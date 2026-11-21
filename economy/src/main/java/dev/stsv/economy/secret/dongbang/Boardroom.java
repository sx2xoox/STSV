package dev.stsv.economy.secret.dongbang;

import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Haunt;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The 최종 주주총회: a wet boardroom only one player sees, standing on the 본사's ground. Fake blocks (dark planks, a long
 * table, 13 stair chairs, black portholes, two soul lanterns), puddles as thin glass displays, and twelve faceless
 * mannequins in suits sitting (riding invisible seats), each under the name of a real player who was on lately. The
 * thirteenth chair, at the head of the table, is empty. Sitting there turns every head, then the vote: 찬성 and the
 * twelve fall into water one by one (solved); 반대 and the door locks while the room fills with water (failed today).
 * <p>
 * Room plan around the middle (x0, y0 = ground, z0): walls at x ±4 and z ±7, the door in the north wall (z -7), the
 * table along x 0 from z -5 to 5, six chairs a side at x ±1, z -5, -3, ... 5, the empty one at (0, 6).
 */
final class Boardroom {

    static final int HALF_X = 4, HALF_Z = 7;
    private static final int[] SIDE_Z = {-5, -3, -1, 1, 3, 5};

    /** The voting window (a plain chest inventory, not a menu file). */
    static final class Ballot implements InventoryHolder {
        static final int AGENDA = 4, YES = 11, NO = 15;
        private final UUID viewer;
        private Inventory inv;

        Ballot(UUID viewer) {
            this.viewer = viewer;
        }

        UUID viewer() {
            return viewer;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    private record Seat(Mannequin figure, TextDisplay seat, TextDisplay tag, Location chair, BlockData chairData) {}

    private final Dongbang d;
    private final Haunt h;
    private final Player p;
    private final UUID u;
    private final World w;
    private final int x0, y0, z0;
    private final boolean preview;
    private final Random rnd;
    /** Real blocks we covered: put back on teardown. */
    private final Map<Location, BlockData> covered = new HashMap<>();
    private final List<Entity> shown = new ArrayList<>();
    private final List<Seat> seats = new ArrayList<>();
    private BukkitTask task;
    private TextDisplay mySeat;
    private boolean seated, entered, voting, voted, flooding, ended, gone, mustLeave;
    private int age, reopenAt = -1;

    Boardroom(Dongbang d, Player p, Location center, boolean preview) {
        this.d = d;
        this.h = d.m().haunt();
        this.p = p;
        this.u = p.getUniqueId();
        this.w = center.getWorld();
        this.x0 = center.getBlockX();
        this.y0 = center.getBlockY();
        this.z0 = center.getBlockZ();
        this.preview = preview;
        this.rnd = new Random(u.getLeastSignificantBits() ^ 0x13L);
    }

    UUID viewer() {
        return u;
    }

    boolean alive() {
        return !gone;
    }

    // ------------------------------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------------------------------

    private Location at(int dx, int dy, int dz) {
        return new Location(w, x0 + dx, y0 + dy, z0 + dz);
    }

    private void fake(int dx, int dy, int dz, BlockData data) {
        Location l = at(dx, dy, dz);
        covered.putIfAbsent(l, l.getBlock().getBlockData());
        h.fake(p, l, data);
    }

    private static BlockData data(String s) {
        return Bukkit.createBlockData(s);
    }

    private BlockData chair(BlockFace back) {
        Stairs s = (Stairs) Material.DARK_OAK_STAIRS.createBlockData();
        s.setFacing(back);
        s.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
        return s;
    }

    void build() {
        BlockData floor = data("minecraft:dark_oak_planks"), wall = data("minecraft:dark_oak_planks"), post = data("minecraft:stripped_dark_oak_log"),
                port = data("minecraft:black_stained_glass"), air = Material.AIR.createBlockData();
        for (int dx = -HALF_X; dx <= HALF_X; dx++) {
            for (int dz = -HALF_Z; dz <= HALF_Z; dz++) {
                fake(dx, 0, dz, floor);
                fake(dx, 5, dz, floor);
                boolean edge = Math.abs(dx) == HALF_X || Math.abs(dz) == HALF_Z;
                boolean corner = Math.abs(dx) == HALF_X && Math.abs(dz) == HALF_Z;
                for (int dy = 1; dy <= 4; dy++) {
                    if (edge) {
                        boolean door = dx == 0 && dz == -HALF_Z && dy <= 2;
                        boolean porthole = dy == 2 && Math.abs(dx) == HALF_X && (dz == -4 || dz == 0 || dz == 4);
                        fake(dx, dy, dz, door ? air : corner ? post : porthole ? port : wall);
                    } else if (!at(dx, dy, dz).getBlock().isEmpty()) {
                        fake(dx, dy, dz, air);
                    }
                }
            }
        }
        Slab top = (Slab) Material.DARK_OAK_SLAB.createBlockData();
        top.setType(Slab.Type.TOP);
        for (int dz = -5; dz <= 5; dz++) fake(0, 1, dz, top);
        fake(0, 4, -3, data("minecraft:soul_lantern[hanging=true]"));
        fake(0, 4, 3, data("minecraft:soul_lantern[hanging=true]"));
        fake(0, 1, 6, chair(BlockFace.SOUTH));

        // puddles: thin pale glass on the floor, away from the chairs
        for (int i = 0; i < 9; i++) {
            int dx = rnd.nextInt(7) - 3, dz = rnd.nextInt(13) - 6;
            if (Math.abs(dx) <= 1 && dz >= -5 && dz <= 6) continue;
            BlockDisplay b = h.block(p, at(dx, 1, dz), data("minecraft:light_blue_stained_glass"));
            float s = 0.5f + rnd.nextFloat() * 0.4f;
            b.setTransformation(new Transformation(new Vector3f((1 - s) / 2, 0.005f, (1 - s) / 2), new AxisAngle4f(), new Vector3f(s, 0.02f, s), new AxisAngle4f()));
            shown.add(b);
        }
        shown.add(h.text(p, new Location(w, x0 + 0.5, y0 + 3.3, z0 + 0.5), d.line("meeting.table-text")));

        // the twelve: nearest the empty chair first
        List<String> names = names();
        double sh = d.cfg().getDouble("meeting.seat-height", 0.6);
        String skin = d.cfg().getString("meeting.skin", "");
        for (int i = 0; i < 12; i++) {
            int side = i % 2 == 0 ? -1 : 1, dz = SIDE_Z[5 - i / 2];
            BlockFace back = side < 0 ? BlockFace.WEST : BlockFace.EAST;
            BlockData cd = chair(back);
            fake(side, 1, dz, cd);
            Location seatAt = new Location(w, x0 + side + 0.5, y0 + 1 + sh, z0 + dz + 0.5, side < 0 ? -90 : 90, 0);
            TextDisplay seat = h.text(p, seatAt, "");
            Mannequin m = h.figure(p, seatAt, skin == null || skin.isBlank() ? null : skin, null);
            dress(m);
            seat.addPassenger(m);
            m.setRotation(side < 0 ? -90 : 90, 8);
            m.setBodyYaw(side < 0 ? -90 : 90);
            TextDisplay tag = h.text(p, seatAt.clone().add(0, 1.55, 0), d.line("meeting.tag").replace("{name}", names.get(i)));
            seats.add(new Seat(m, seat, tag, at(side, 1, dz), cd));
            shown.add(seat);
            shown.add(m);
            shown.add(tag);
        }
        task = Bukkit.getScheduler().runTaskTimer(d.plugin(), this::tick, 5L, 5L);
    }

    /** The twelve most recently seen players other than the viewer, "—" for the empty places. */
    private List<String> names() {
        List<OfflinePlayer> all = new ArrayList<>(List.of(Bukkit.getOfflinePlayers()));
        all.removeIf(o -> o.getUniqueId().equals(u) || o.getName() == null);
        all.sort(Comparator.comparingLong(OfflinePlayer::getLastSeen).reversed());
        List<String> out = new ArrayList<>();
        for (OfflinePlayer o : all) {
            if (out.size() == 12) break;
            out.add(o.getName());
        }
        String empty = d.line("meeting.empty-tag").isEmpty() ? "—" : d.line("meeting.empty-tag");
        while (out.size() < 12) out.add(empty);
        // tags are MiniMessage: a name with < > is shown as it is
        out.replaceAll(n -> n.equals(empty) ? n : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(n));
        return out;
    }

    private void dress(Mannequin m) {
        Color suit;
        try {
            suit = Color.fromRGB(Integer.parseInt(d.cfg().getString("meeting.suit-color", "#1d2026").replace("#", ""), 16));
        } catch (RuntimeException e) {
            suit = Color.fromRGB(0x1d2026);
        }
        var eq = m.getEquipment();
        eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, suit));
        eq.setLeggings(leather(Material.LEATHER_LEGGINGS, suit));
        eq.setBoots(leather(Material.LEATHER_BOOTS, Color.fromRGB(0x0c0c0e)));
        String head = d.cfg().getString("meeting.head", "minecraft:tinted_glass");
        Material hm = head == null || head.isBlank() ? null : Material.matchMaterial(head);
        if (hm != null && hm.isItem()) eq.setHelmet(new ItemStack(hm));
    }

    private static ItemStack leather(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        it.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(c));
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // every 5 ticks
    // ------------------------------------------------------------------------------------------

    private Location chairTop() {
        return new Location(w, x0 + 0.5, y0 + 1.5, z0 + 6.5);
    }

    private void tick() {
        if (gone) return;
        age++;
        if (!p.isOnline() || p.getWorld() != w) {
            teardown();
            return;
        }
        Location l = p.getLocation();
        double dx = l.getX() - (x0 + 0.5), dz = l.getZ() - (z0 + 0.5);
        if (!seated && !ended && dx * dx + dz * dz > 48 * 48) {
            teardown();
            return;
        }
        // water drips from the ceiling
        if (age % 6 == 0 || rnd.nextInt(5) == 0) {
            Location drip = new Location(w, x0 + 0.5 + rnd.nextInt(7) - 3, y0 + 4.9, z0 + 0.5 + rnd.nextInt(13) - 6);
            p.spawnParticle(Particle.DRIPPING_WATER, drip, 1);
            if (rnd.nextInt(3) == 0) h.sound(p, drip, Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 0.6f, 0.8f + rnd.nextFloat() * 0.4f);
        }
        boolean inside = Math.abs(dx) < HALF_X && Math.abs(dz) < HALF_Z && Math.abs(l.getY() - (y0 + 1)) < 3;
        if (inside && !entered) {
            entered = true;
            h.dark(p, 4);
            h.whisper(p, d.line("meeting.enter"));
            h.sound(p, at(0, 1, -HALF_Z), Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, 0.5f, 0.5f);
        }
        if (seated) {
            for (Seat s : seats) if (s.figure().isValid()) look(s.figure(), p.getEyeLocation());
            if (reopenAt >= 0 && age >= reopenAt && !voted) {
                reopenAt = -1;
                h.whisper(p, d.line("meeting.vote-again"));
                ballot();
            }
            return;
        }
        if (ended) return;
        double cd = l.toVector().setY(0).distance(chairTop().toVector().setY(0));
        if (mustLeave) {
            if (cd > 1.8) mustLeave = false;
            return;
        }
        if (inside && (cd < 0.8 && Math.abs(l.getY() - (y0 + 1)) < 1.2 || p.isSneaking() && cd < 1.8)) sit();
    }

    private static void look(Mannequin m, Location target) {
        Location from = m.getLocation().add(0, 1.0, 0);
        Vector v = target.toVector().subtract(from.toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
        float pitch = (float) -Math.toDegrees(Math.atan2(v.getY(), Math.hypot(v.getX(), v.getZ())));
        m.setRotation(yaw, pitch);
    }

    /** A right click: on (or looking at) the empty chair from nearby sits them. True when it was for us. */
    boolean clicked() {
        if (seated || ended || gone) return false;
        Location eye = p.getEyeLocation();
        Vector to = chairTop().toVector().subtract(eye.toVector());
        if (to.length() > 3.5) return false;
        if (eye.getDirection().angle(to.normalize()) > Math.toRadians(30)) return false;
        mustLeave = false;
        sit();
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // sitting and the vote
    // ------------------------------------------------------------------------------------------

    void sit() {
        if (seated || ended || gone) return;
        seated = true;
        entered = true;
        double sh = d.cfg().getDouble("meeting.seat-height", 0.6);
        Location at = new Location(w, x0 + 0.5, y0 + 1 + sh, z0 + 6.5, 180, 0);
        p.teleport(at.clone().add(0, 0.5, 0));
        mySeat = h.text(p, at, "");
        shown.add(mySeat);
        mySeat.addPassenger(p);
        // every head at once
        for (Seat s : seats) {
            look(s.figure(), p.getEyeLocation());
            h.sound(p, s.figure().getLocation(), Sound.BLOCK_LADDER_STEP, 0.35f, 0.5f + rnd.nextFloat() * 0.1f);
        }
        h.sound(p, at(0, 2, 0), Sound.AMBIENT_CAVE, 0.6f, 0.6f);
        h.whisper(p, d.line("meeting.sit"));
        Bukkit.getScheduler().runTaskLater(d.plugin(), () -> {
            if (!gone && seated && !voted) ballot();
        }, 50L);
    }

    private void ballot() {
        if (!p.isOnline()) return;
        Ballot b = new Ballot(u);
        Inventory inv = Bukkit.createInventory(b, 27, Text.mm(d.line("meeting.ballot.title")));
        b.inv = inv;
        inv.setItem(Ballot.AGENDA, item(Material.PAPER, "meeting.ballot.agenda-name", "meeting.ballot.agenda-lore"));
        inv.setItem(Ballot.YES, item(Material.WHITE_STAINED_GLASS_PANE, "meeting.ballot.yes-name", "meeting.ballot.yes-lore"));
        inv.setItem(Ballot.NO, item(Material.BLACK_STAINED_GLASS_PANE, "meeting.ballot.no-name", "meeting.ballot.no-lore"));
        voting = true;
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 0.6f);
    }

    private ItemStack item(Material m, String name, String lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.itemName(Text.item(d.line(name)));
            meta.lore(Text.itemLines(d.lines(lore)));
        });
        return it;
    }

    void ballotClosed() {
        if (!voting || voted || gone) return;
        voting = false;
        if (seated) reopenAt = age + 12;      // three seconds, then once more
    }

    void vote(boolean yes) {
        if (voted || gone) return;
        if (!seated) sit();
        voted = true;
        voting = false;
        reopenAt = -1;
        p.closeInventory();
        if (yes) release();
        else flood();
    }

    /** A seat entity left: the player standing up. Returns true to keep them seated (the room is filling). */
    boolean dismount(Entity vehicle) {
        if (vehicle != mySeat) return false;
        if (flooding || (voted && !ended)) return true;
        stand();
        return false;
    }

    private void stand() {
        seated = false;
        mustLeave = true;
        reopenAt = -1;
        if (mySeat != null) {
            shown.remove(mySeat);
            h.remove(p, mySeat);
            mySeat = null;
        }
        for (Seat s : seats) {
            if (!s.figure().isValid()) continue;
            boolean west = s.chair().getBlockX() < x0;
            s.figure().setRotation(west ? -90 : 90, 8);
        }
    }

    // ------------------------------------------------------------------------------------------
    // the endings
    // ------------------------------------------------------------------------------------------

    /** 찬성: from the far end toward the chair, each one falls into water and its name goes blank. */
    private void release() {
        List<Seat> order = new ArrayList<>(seats);
        order.sort(Comparator.comparingInt(s -> s.chair().getBlockZ()));
        h.sound(p, at(0, 2, 0), Sound.BLOCK_WATER_AMBIENT, 0.8f, 0.6f);
        int[] i = {0};
        BukkitTask[] t = new BukkitTask[1];
        t[0] = Bukkit.getScheduler().runTaskTimer(d.plugin(), () -> {
            if (gone || !p.isOnline()) {
                t[0].cancel();
                return;
            }
            if (i[0] >= order.size()) {
                t[0].cancel();
                epilogue(true);
                return;
            }
            Seat s = order.get(i[0]);
            Location l = s.figure().isValid() ? s.figure().getLocation().add(0, 0.8, 0) : s.chair().clone().add(0.5, 1, 0.5);
            p.spawnParticle(Particle.SPLASH, l, 90, 0.25, 0.6, 0.25, 0.2);
            p.spawnParticle(Particle.FALLING_WATER, l, 40, 0.3, 0.7, 0.3, 0);
            p.spawnParticle(Particle.BUBBLE_POP, l, 20, 0.3, 0.6, 0.3, 0.02);
            h.sound(p, l, Sound.ENTITY_GENERIC_SPLASH, 0.9f, 0.55f + i[0] * 0.03f);
            h.sound(p, l, Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, 1f, 0.6f);
            remove(s.figure());
            remove(s.seat());
            s.tag().text(Component_empty());
            BlockData wet = s.chairData().clone();
            if (wet instanceof Waterlogged wl) wl.setWaterlogged(true);
            h.fake(p, s.chair(), wet);
            i[0]++;
        }, 30L, 16L);
    }

    private static net.kyori.adventure.text.Component Component_empty() {
        return net.kyori.adventure.text.Component.empty();
    }

    /** 반대: the door shuts and the water rises over about six seconds, then black. */
    private void flood() {
        flooding = true;
        fake(0, 1, -HALF_Z, data("minecraft:dark_oak_planks"));
        fake(0, 2, -HALF_Z, data("minecraft:dark_oak_planks"));
        h.sound(p, at(0, 1, -HALF_Z), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.5f);
        h.sound(p, at(0, 1, -HALF_Z), Sound.BLOCK_CHEST_LOCKED, 1f, 0.5f);
        h.dark(p, 12);
        BlockData water = Material.WATER.createBlockData();
        int[] layer = {1};
        BukkitTask[] t = new BukkitTask[1];
        t[0] = Bukkit.getScheduler().runTaskTimer(d.plugin(), () -> {
            if (gone || !p.isOnline()) {
                t[0].cancel();
                return;
            }
            if (layer[0] > 4) {
                t[0].cancel();
                h.black(p, 5);
                h.sound(p, p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.35f, 0.5f);
                Bukkit.getScheduler().runTaskLater(d.plugin(), () -> epilogue(false), 30L);
                return;
            }
            int y = layer[0];
            for (int dx = -HALF_X + 1; dx < HALF_X; dx++) {
                for (int dz = -HALF_Z + 1; dz < HALF_Z; dz++) {
                    Location l = at(dx, y, dz);
                    BlockData now = p.getWorld().getBlockAt(l).getBlockData();
                    BlockData shownHere = furniture(dx, y, dz);
                    if (shownHere != null) {
                        BlockData wet = shownHere.clone();
                        if (wet instanceof Waterlogged wl) {
                            wl.setWaterlogged(true);
                            fake(dx, y, dz, wet);
                        }
                    } else if (now.getMaterial().isAir() || !l.getBlock().isSolid()) {
                        fake(dx, y, dz, water);
                    }
                }
            }
            h.sound(p, at(0, y, 0), Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1f, 0.6f + y * 0.1f);
            h.sound(p, at(0, y, 0), Sound.BLOCK_WATER_AMBIENT, 1f, 0.5f);
            p.spawnParticle(Particle.BUBBLE, p.getEyeLocation(), 30, 1.5, 0.5, 1.5, 0.05);
            layer[0]++;
        }, 20L, 30L);
    }

    /** What the room shows at a spot that is furniture (for waterlogging), else null. */
    private BlockData furniture(int dx, int dy, int dz) {
        if (dy == 1 && dx == 0 && dz >= -5 && dz <= 5) {
            Slab top = (Slab) Material.DARK_OAK_SLAB.createBlockData();
            top.setType(Slab.Type.TOP);
            return top;
        }
        if (dy == 1 && dx == 0 && dz == 6) return chair(BlockFace.SOUTH);
        if (dy == 1 && Math.abs(dx) == 1) for (int z : SIDE_Z) if (z == dz) return chair(dx < 0 ? BlockFace.WEST : BlockFace.EAST);
        if (dy == 4 && dx == 0 && (dz == -3 || dz == 3)) return data("minecraft:soul_lantern[hanging=true]");
        return null;
    }

    private void epilogue(boolean yes) {
        if (gone) return;
        ended = true;
        List<String> lines = d.lines(yes ? "meeting.yes" : "meeting.no");
        if (!yes) {
            if (!preview) d.lost(p);
            teardown();
        }
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            Bukkit.getScheduler().runTaskLater(d.plugin(), () -> {
                if (p.isOnline()) p.sendMessage(Text.mm(l));
            }, 20L + i * 40L);
        }
        if (!yes) return;
        Bukkit.getScheduler().runTaskLater(d.plugin(), () -> {
            if (!p.isOnline()) {
                teardown();
                return;
            }
            if (preview) p.sendMessage(Text.mm("<yellow>[연출] 여기서 풀림 처리 (보상 · 공지 · 단계는 건드리지 않음)"));
            else d.solved(p);
            // the room stays a little, empty and dripping, then is gone
            Bukkit.getScheduler().runTaskLater(d.plugin(), this::teardown, 160L);
        }, 20L + lines.size() * 40L);
    }

    private void remove(Entity e) {
        shown.remove(e);
        h.remove(p, e);
    }

    /** Takes it all away (blocks back as they are, entities gone). */
    void teardown() {
        if (gone) return;
        gone = true;
        if (task != null) task.cancel();
        if (mySeat != null && mySeat.getPassengers().contains(p)) {
            flooding = false;
            mySeat.removePassenger(p);
        }
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Ballot) p.closeInventory();
        for (Entity e : List.copyOf(shown)) h.remove(p, e);
        shown.clear();
        if (p.isOnline()) for (Map.Entry<Location, BlockData> e : covered.entrySet()) p.sendBlockChange(e.getKey(), e.getKey().getBlock().getBlockData());
        covered.clear();
        d.gone(this);
    }

    @SuppressWarnings("unused")
    private Block ground() {
        return w.getBlockAt(x0, y0, z0);
    }
}
