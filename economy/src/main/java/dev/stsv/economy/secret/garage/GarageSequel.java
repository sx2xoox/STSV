package dev.stsv.economy.secret.garage;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.core.vehicle.VehiclePhysics;
import dev.stsv.economy.road.RoadNetwork;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 후속 임무 「도윤 모터스」 (docs/admin/숨은_비밀/폐차고의_주인.md "후속 임무"; garage.yml {@code sequel}). About once a week,
 * at night, when the solver comes near the garage (shut now, its sign lit), a note slides out under the shutter:
 * something 한도윤 never got to do. Each is a small errand on the built roads with the solver's own 미완의 질주 — a road
 * without a scratch, a time he promised to be home by, a needle held still, the bend taken slowly, stopping at the line
 * before it, the lights of his line, a part he never delivered ({@code sequel.tasks}; {@link Errand} holds the rules).
 * Done, they come back to the garage: a line from behind the shutter, the reward, and one more line on the back of the
 * photo "사진 — 두 대". Broken rules ({@code sequel.tries} times): "…괜찮아. 다음 주에." and nothing is lost.
 * <p>
 * The car is told by its item ({@link VehicleModule#drivenBy}: the reward car is sold nowhere). Someone who no longer has
 * theirs waits a moment before the shutter and is lent one: a temporary vehicle as in the last drive, which lives in
 * memory only and is taken away by whatever ends its use ({@link #takeCar}; a quit, a reload, the week turning, being left
 * behind, the place moving). Everything shown is per viewer ({@link Props}).
 * <p>
 * Notes of the going mission ({@code sequel.x.}): task, road (the other road's dock; "" the garage's own), seed, tries
 * (broken attempts), part (the serial of the part that counts), drove (came to the spot by car). Kept
 * ({@code sequel.keep.}): recent (the last tasks), stickers (what is written on the photo: "day|task;…").
 * Steps: 0 the note lies there (offered) · 1 the errand · 2 done, back to the garage.
 */
public final class GarageSequel extends Sequel {

    static final String NOTE = "sequel-note", SPOT = "sequel-spot";
    static final NamespacedKey PART_OWNER = new NamespacedKey("economy", "garage_part_owner"),
            PART_SERIAL = new NamespacedKey("economy", "garage_part_serial"),
            PHOTO_BASE = new NamespacedKey("economy", "garage_photo_base"), PHOTO_LIST = new NamespacedKey("economy", "garage_photo_list");
    private static final int ERRAND = 1, BACK = 2;
    private static final double ZONE = 12;

    /** A stretch being driven (memory only: leaving in the middle of it costs nothing but the start). */
    private static final class Run {
        final Errand.Course course;
        final Errand.Rules rules;
        final boolean night;
        Errand.Progress st;
        boolean armed, going;
        double along;
        long car;
        int cool, beat, quiet;

        Run(Errand.Course course, Errand.Rules rules, boolean night) {
            this.course = course;
            this.rules = rules;
            this.night = night;
        }
    }

    private final Garage g;
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Errand.Course> courses = new HashMap<>();
    /** player -> the temporary car lent to them */
    private final Map<UUID, Long> lent = new HashMap<>();
    /** player -> seconds they have stood before the shutter without a car */
    private final Map<UUID, Integer> afoot = new HashMap<>();
    /** player -> where their roadside box stands */
    private final Map<UUID, Location> spots = new HashMap<>();
    /** players whose shutter this holds up for a moment */
    private final Set<UUID> lifted = new HashSet<>();
    private List<RoadNetwork.Road> roads = List.of();
    private String roadsWorld = "";
    private long roadsAt;
    private BukkitTask task;

    GarageSequel(Garage g) {
        super(g);
        this.g = g;
    }

    @Override
    public String id() {
        return "도윤 모터스";
    }

    @Override
    protected void loaded() {
        runs.clear();
        courses.clear();
        spots.clear();
        roadsAt = 0;
    }

    // ------------------------------------------------------------------------------------------
    // words
    // ------------------------------------------------------------------------------------------

    private String ln(String key, String def, String... pairs) {
        return line("lines." + key, def, pairs);
    }

    private void say(Player p, String key, String def, String... pairs) {
        String s = ln(key, def, pairs);
        if (!s.isEmpty()) p.sendMessage(Text.mm(s));
    }

    /** Small text above the hotbar; the driving read-out keeps off it for two seconds. */
    private void hush(Player p, String key, String def, String... pairs) {
        String s = ln(key, def, pairs);
        if (s.isEmpty()) return;
        m.haunt().whisper(p, s);
        Run r = runs.get(p.getUniqueId());
        if (r != null) r.quiet = 40;
    }

    private static String fill(String s, Map<String, String> words) {
        for (Map.Entry<String, String> e : words.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    private static String date(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy.MM.dd");
        f.setTimeZone(TimeZone.getTimeZone(dev.stsv.economy.secret.SecretModule.KST));
        return f.format(new Date(ms));
    }

    private static double flat(Player p, Location at) {
        if (at == null || at.getWorld() == null || !at.getWorld().equals(p.getWorld())) return Double.MAX_VALUE;
        return Math.hypot(at.getX() - p.getLocation().getX(), at.getZ() - p.getLocation().getZ());
    }

    // ------------------------------------------------------------------------------------------
    // the tasks and their roads
    // ------------------------------------------------------------------------------------------

    private ConfigurationSection tasks() {
        return cfg().getConfigurationSection("tasks");
    }

    private ConfigurationSection task(UUID u) {
        String id = get(u, "task");
        ConfigurationSection all = tasks();
        return id == null || all == null ? null : all.getConfigurationSection(id);
    }

    private static boolean part(ConfigurationSection t) {
        return "part".equalsIgnoreCase(t.getString("kind", "drive"));
    }

    /** Why nothing of it can happen now (for the status line), or null. */
    private String dormant() {
        Places.Site site = g.places().site();
        if (site == null) return "잠듦: " + (g.places().why().isEmpty() ? "아직 국도를 보지 않음" : g.places().why());
        if (g.vm() == null) return "잠듦: 코어의 탈것 모듈이 꺼져 있음";
        if (g.cars().item() == null) return "잠듦: 탈것 아이템 " + g.cars().itemId() + " 이(가) 없음";
        if (!site.fitted()) return "잠듦: 차고 자리가 아직 없음 (" + (g.places().dryWhy().isEmpty() ? "누가 가까이 가면 땅에 맞춤" : g.places().dryWhy()) + ")";
        ConfigurationSection all = tasks();
        if (all == null || all.getKeys(false).isEmpty()) return "잠듦: garage.yml 의 sequel.tasks 가 비어 있음";
        return null;
    }

    /** The built roads of this world (asked for at most once a minute: the road module makes the lists anew each time). */
    private List<RoadNetwork.Road> roads(String world) {
        long now = System.currentTimeMillis();
        if (world.equals(roadsWorld) && now - roadsAt < 60_000L) return roads;
        List<RoadNetwork.Road> out = new ArrayList<>();
        if (eco.roads() != null)
            for (RoadNetwork.Road r : eco.roads().builtRoads(world))
                if (r.points() != null && r.halves() != null && r.points().size() >= 2 && r.halves().length >= r.points().size() && r.dock() != null) out.add(r);
        roads = out;
        roadsWorld = world;
        roadsAt = now;
        return out;
    }

    /** Another road than the garage's for this week (by the seed, among those long enough), or null. */
    private String otherDock(Places.Site site, long seed) {
        int min = Math.max(60, cfg().getInt("other-min", 150));
        List<String> docks = new ArrayList<>();
        for (RoadNetwork.Road r : roads(site.world()))
            if (!r.dock().equals(String.valueOf(site.dock())) && r.points().size() >= min && !docks.contains(r.dock())) docks.add(r.dock());
        if (docks.isEmpty()) return null;
        java.util.Collections.sort(docks);
        return docks.get((int) Math.floorMod(seed >>> 7, (long) docks.size()));
    }

    private static boolean home(Places.Site site, String dock) {
        return dock == null || dock.isEmpty() || dock.equals(String.valueOf(site.dock()));
    }

    /**
     * The stretch a task is driven on (for a part: where its box stands, from = to): on the garage's own road ({@code from}
     * / {@code to} / {@code spot}), or on the other road of the week ({@code other-from} / {@code other-to} /
     * {@code other-spot}; the bend and the garage are no points of that one). Null when it cannot be had.
     */
    private Errand.Course course(Places.Site site, ConfigurationSection t, String dock) {
        boolean home = home(site, dock), part = part(t);
        List<double[]> pts = null;
        int[] halves = null;
        if (home) {
            pts = site.pts();
            halves = site.halves();
        } else {
            for (RoadNetwork.Road r : roads(site.world())) {
                if (!dock.equals(r.dock())) continue;
                List<double[]> copy = new ArrayList<>(r.points().size());
                for (double[] q : r.points()) copy.add(new double[]{q[0], q[1]});
                pts = List.copyOf(copy);
                halves = java.util.Arrays.copyOf(r.halves(), copy.size());
                break;
            }
        }
        if (pts == null) return null;
        String pre = home ? "" : "other-";
        int n = pts.size(), bend = home ? site.bend() : -1, garage = home ? site.garage() : -1;
        int a, b;
        if (part) {
            a = b = Errand.anchor(t.getString(pre + "spot", home ? "start+20" : "end-20"), n, bend, garage);
        } else {
            a = Errand.anchor(t.getString(pre + "from", "start+4"), n, bend, garage);
            b = Errand.anchor(t.getString(pre + "to", home ? "garage" : "end-6"), n, bend, garage);
            int max = t.getInt("max-length", 0);
            if (a >= 0 && b >= 0 && max > 0 && Math.abs(b - a) > max) b = a + (b > a ? max : -max);
        }
        if (a < 0 || b < 0) return null;
        return new Errand.Course(site.world(), home ? String.valueOf(site.dock()) : dock, pts, halves, a, b);
    }

    private boolean feasible(Errand.Course c, ConfigurationSection t) {
        return c != null && (part(t) || c.length() >= Math.max(30, cfg().getInt("min-length", 40)));
    }

    /** This player's stretch (worked out once and kept while they are here). */
    private Errand.Course course(UUID u) {
        Errand.Course c = courses.get(u);
        if (c != null) return c;
        Places.Site site = g.places().site();
        ConfigurationSection t = task(u);
        if (site == null || t == null) return null;
        c = course(site, t, get(u, "road", ""));
        if (!feasible(c, t)) return null;
        courses.put(u, c);
        return c;
    }

    private Errand.Rules rules(UUID u, ConfigurationSection t, Errand.Course c) {
        double speed = t.getDouble("speed", 0);
        List<Double> band = t.getDoubleList("band");
        double spacing = t.getDouble("gates", 0);
        double lane = t.getDouble("lane", g.cfg().getDouble("drive.lane", 1.3) + 0.3);
        // (the lights are laid in the road's own frame: which hand they are on does not depend on the way it is driven)
        List<double[]> gates = spacing > 0 ? Errand.gates(c.length(), 26, spacing, lane, getLong(u, "seed", 0)) : List.of();
        return new Errand.Rules(t.getInt("bumps", 0), speed > 0 ? Errand.limit(c.length(), speed, t.getDouble("slack", 4)) * 20 : 0,
                band.size() >= 2 ? band.get(0) : 0, band.size() >= 2 ? band.get(1) : 0, (int) Math.round(t.getDouble("grace", 2.5) * 20),
                gates, t.getDouble("radius", 1.2), Math.max(0, t.getInt("misses", 1)), t.getBoolean("stop"), t.getDouble("reach", 10),
                Math.max(2, t.getDouble("box", 5)), Math.max(5, cfg().getDouble("offroad", 9)));
    }

    /** What a task's lines are filled with: {road} {seconds} {lo} {hi} {sx} {sz} (where it starts) {x} {z} (where it ends) {tries}. */
    private Map<String, String> words(UUID u, ConfigurationSection t, Errand.Course c) {
        Map<String, String> w = new LinkedHashMap<>();
        Places.Site site = g.places().site();
        w.put("racer", g.cfg().getString("racer", "한도윤"));
        w.put("tries", String.valueOf(Math.max(1, cfg().getInt("tries", 2))));
        w.put("road", c == null || (site != null && home(site, c.dock())) ? ln("home-road", "차고") : c.dock());
        List<Double> band = t.getDoubleList("band");
        w.put("lo", band.size() >= 2 ? num(band.get(0)) : "-");
        w.put("hi", band.size() >= 2 ? num(band.get(1)) : "-");
        double speed = t.getDouble("speed", 0);
        w.put("seconds", c == null || speed <= 0 ? "-" : String.valueOf(Errand.limit(c.length(), speed, t.getDouble("slack", 4))));
        if (c != null) {
            double[] a = c.pts().get(c.from()), b = c.pts().get(c.to());
            w.put("sx", String.valueOf(Math.round(a[0])));
            w.put("sz", String.valueOf(Math.round(a[1])));
            w.put("x", String.valueOf(Math.round(b[0])));
            w.put("z", String.valueOf(Math.round(b[1])));
        }
        return w;
    }

    /** A line of a task; where the week has no other road and the task falls back to the garage's own, its {@code -home} wording when there is one. */
    private String text(UUID u, ConfigurationSection t, String key, String def) {
        if (fallback(u, t) && t.isString(key + "-home")) return t.getString(key + "-home", def);
        return t.getString(key, def);
    }

    private boolean fallback(UUID u, ConfigurationSection t) {
        return "other".equalsIgnoreCase(t.getString("road", "home")) && get(u, "road", "").isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // the car
    // ------------------------------------------------------------------------------------------

    /** Whether what they drive is his car: their own 미완의 질주, or the one lent to them. */
    private boolean his(VehicleModule.Driven d, UUID u) {
        if (d == null) return false;
        Long l = lent.get(u);
        return (l != null && d.id() == l) || d.itemKey().equals(g.cars().shownId()) || d.itemKey().equals(g.cars().itemId());
    }

    /** A click on a vehicle part: the car lent to this player lets them (back) in. */
    void board(Player p, long id) {
        UUID u = p.getUniqueId();
        Long l = lent.get(u);
        VehicleModule vm = g.vm();
        if (l == null || l != id || vm == null || !may(p) || !live(u)) return;
        vm.seatTemporary(id, p);
    }

    /** The lent car each second: gone when it is no longer needed or was left behind; lent when they stand before the shutter without one. */
    private void lending(Player p, Places.Site site, boolean mayLend) {
        UUID u = p.getUniqueId();
        VehicleModule vm = g.vm();
        if (vm == null) return;
        VehicleModule.Driven d = vm.drivenBy(p);
        Long id = lent.get(u);
        if (id != null) {
            VehiclePhysics.State s = vm.temporaryState(id);
            if (s == null || !vm.temporaryAlive(id)) {
                lent.remove(u);
                vm.removeTemporary(id);
            } else if (d != null && d.id() != id && his(d, u)) {   // they have their own after all
                takeCar(p, false);
            } else if (!vm.drivesTemporary(id, p)) {
                double leash = Math.max(40, cfg().getDouble("lend-leash", 120));
                boolean far = !p.getWorld().getName().equals(site.world())
                        || Math.hypot(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) > leash;
                if (far || p.isDead()) {
                    takeCar(p, false);
                    hush(p, "lend-back", "<#9fb2c8>…차는 내가 넣어 둘게.");
                }
            }
            return;
        }
        Location o = g.places().garage();
        if (!mayLend || d != null || p.isInsideVehicle() || p.isDead() || o == null
                || flat(p, o) > Math.max(4, cfg().getDouble("lend-near", 12))) {
            afoot.remove(u);
            return;
        }
        if (afoot.merge(u, 1, Integer::sum) < seconds(u, "lend-after", 20)) return;
        afoot.remove(u);
        if (lend(p, site)) {
            say(p, "lend", "<#9fb2c8>…차가 없어? 내 걸 써. 길에 내놨어.");
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.AMBIENT, 0.5f, 0.6f);
        }
    }

    /**
     * His car on the road before the garage, facing back the way the road came (a temporary vehicle in the true look,
     * as the last drive's: it only appears where the land is already loaded, and nobody but its player gets in).
     */
    private boolean lend(Player p, Places.Site site) {
        UUID u = p.getUniqueId();
        VehicleModule vm = g.vm();
        CustomItem item = g.cars().item();
        World w = site.bukkit();
        if (vm == null || item == null || w == null || lent.containsKey(u)) return false;
        double lane = Math.clamp(g.cfg().getDouble("drive.lane", 1.3), 0, 2.5);
        int at = site.garage();
        double[] q = Roadway.beside(site.pts(), at, -lane, 3), t = Roadway.tangent(site.pts(), at, 3);
        Location l = new Location(w, q[0], Roadway.height(site.halves(), at) + 0.1, q[1], Roadway.yaw(-t[0], -t[1]), 0);
        for (int cx = (int) Math.floor(l.getX() - 5) >> 4; cx <= (int) Math.floor(l.getX() + 5) >> 4; cx++)
            for (int cz = (int) Math.floor(l.getZ() - 5) >> 4; cz <= (int) Math.floor(l.getZ() + 5) >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) return false;
        long id = g.cars().spawnReal(vm, item, w, l, g.answer(), null);
        if (id == 0) return false;
        vm.letOutOfTemporary(id);
        lent.put(u, id);
        return true;
    }

    /** Solid ground at the edge of the nearest of their roads (the stretch's, the garage's) within reach of (x, z), or null. */
    private Location roadside(UUID u, String world, double x, double z) {
        Places.Site site = g.places().site();
        List<double[]> pts = null;
        int[] halves = null;
        double best = 16, along = 0;
        Errand.Course c = courses.get(u);
        for (int k = 0; k < 2; k++) {
            List<double[]> l = k == 0 ? (c == null ? null : c.pts()) : (site == null ? null : site.pts());
            String lw = k == 0 ? (c == null ? null : c.world()) : (site == null ? null : site.world());
            if (l == null || !world.equals(lw)) continue;
            double s = Roadway.project(l, x, z, 0, l.size() - 1);
            double[] q = Roadway.at(l, s);
            double d = Math.hypot(q[0] - x, q[1] - z);
            if (d < best) {
                best = d;
                pts = l;
                halves = k == 0 ? c.halves() : site.halves();
                along = s;
            }
        }
        if (pts == null) return null;
        Drive.Run r = new Drive.Run(u, true, world, pts, halves, 0, 1, pts.size() - 1);
        r.along = along;
        return g.drive().roadside(r, x, z);
    }

    /**
     * Takes the lent car away, whatever state it is in. settle: when they sat in it, they are stood on solid ground at the
     * roadside a moment later (never left on a bridge's rail or over water), as after a broken last drive.
     */
    private void takeCar(Player p, boolean settle) {
        UUID u = p.getUniqueId();
        Long id = lent.remove(u);
        VehicleModule vm = g.vm();
        if (id == null || vm == null) return;
        VehicleModule.Driven d = vm.drivenBy(p);
        boolean seated = d != null && d.id() == id;
        Location safe = settle && seated && p.isOnline() && !p.isDead() ? roadside(u, d.world().getName(), d.x(), d.z()) : null;
        vm.removeTemporary(id);
        if (safe == null || !eco.isEnabled()) return;   // (nothing can be scheduled while the plugin stops: the core has stepped them out beside it)
        Bukkit.getScheduler().runTaskLater(eco, () -> {   // after the core has stepped them out beside the car
            if (p.isOnline() && !p.isDead() && !p.isInsideVehicle() && p.getWorld().equals(safe.getWorld())
                    && p.getLocation().distanceSquared(safe) < 48 * 48 && Drive.standable(safe)) {
                safe.setYaw(p.getLocation().getYaw());
                safe.setPitch(p.getLocation().getPitch());
                p.teleport(safe);
            }
        }, 3L);
    }

    private void dropCar(UUID u) {
        Long id = lent.remove(u);
        if (id != null && g.vm() != null) g.vm().removeTemporary(id);
    }

    /** Leaving from the wheel of the lent car: where they stand next time is kept (the secret's own landing puts them there). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Long id = lent.get(u);
        VehicleModule vm = g.vm();
        if (id == null || vm == null) return;
        VehicleModule.Driven d = vm.drivenBy(p);
        if (d != null && d.id() == id) {
            Location safe = roadside(u, d.world().getName(), d.x(), d.z());
            if (safe != null) g.keepGround(u, safe);
        }
        dropCar(u);
    }

    /** The bend or the garage moved (their road changed or went): nothing of the going errands stays at the old place. */
    void placeChanged() {
        runs.clear();
        courses.clear();
        spots.clear();
        roadsAt = 0;
        for (UUID u : new ArrayList<>(lent.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) takeCar(p, true);
            else dropCar(u);
        }
    }

    // ------------------------------------------------------------------------------------------
    // the part
    // ------------------------------------------------------------------------------------------

    private static boolean partOf(ItemStack it, UUID u) {
        return it != null && !it.isEmpty() && it.hasItemMeta()
                && u.toString().equals(it.getItemMeta().getPersistentDataContainer().get(PART_OWNER, PersistentDataType.STRING));
    }

    private boolean ownPart(ItemStack it, UUID u) {
        String want = get(u, "part");
        return want != null && partOf(it, u) && want.equals(it.getItemMeta().getPersistentDataContainer().get(PART_SERIAL, PersistentDataType.STRING));
    }

    private boolean hasPart(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (ownPart(it, p.getUniqueId())) return true;
        return ownPart(p.getItemOnCursor(), p.getUniqueId());
    }

    /** Every part of theirs they carry goes (the one that counts and older copies alike: it is the mission's, not theirs). */
    private void takePart(Player p) {
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) if (partOf(all[i], u)) inv.setItem(i, null);
        if (partOf(p.getItemOnCursor(), u)) p.setItemOnCursor(null);
    }

    /** A mission-only thing made of an existing secret item (a paint chip's paper) under another name: worth nothing, not tradable. */
    private void givePart(Player p, ConfigurationSection t) {
        UUID u = p.getUniqueId();
        takePart(p);
        String serial = Long.toHexString(System.currentTimeMillis()) + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        set(u, "part", serial);   // the copies given before are only paper now
        Map<String, String> words = words(u, t, course(u));
        ItemStack it = Items.create(cfg().getString("part.item", Garage.CHIP), 1).orElseGet(() -> new ItemStack(Material.PAPER));
        List<String> raw = t.isList("part-lore") ? t.getStringList("part-lore") : cfg().getStringList("part.lore");
        if (raw.isEmpty()) raw = List.of("<gray>기름종이에 싸여 있다. 손바닥만 한 쇳덩이.", "<dark_gray><i>꼬리표: 길가 상자 ({x}, {z})", "<dark_gray>팔 수 없음");
        List<Component> lore = new ArrayList<>();
        for (String s : raw) lore.add(Text.item(fill(s, words)));
        String name = fill(t.getString("part-name", cfg().getString("part.name", "<#b9b2a8>기름종이에 싼 부품")), words);
        it.editMeta(meta -> {
            meta.itemName(Text.item(name));
            meta.setMaxStackSize(1);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PART_OWNER, PersistentDataType.STRING, u.toString());
            meta.getPersistentDataContainer().set(PART_SERIAL, PersistentDataType.STRING, serial);
        });
        give(p, Items.markBought(it));
    }

    /** Where the roadside box stands: dry, solid ground beside the stretch's point (looked for a little way back along the road too); null while that land is not loaded. */
    private Location spotAt(Errand.Course c) {
        World w = Bukkit.getWorld(c.world());
        if (w == null) return null;
        int n = c.pts().size();
        for (int shift : new int[]{0, -8, 8, -16, 16, -24}) {
            int i = Math.clamp(c.to() + shift, 0, n - 1);
            int roadY = Roadway.surfaceY(c.halves(), i);
            double[] t = Roadway.tangent(c.pts(), i, 4);
            for (double lateral : new double[]{4.5, -4.5, 3.5, -3.5}) {
                double[] at = Roadway.beside(c.pts(), i, lateral, 3);
                int y = Places.ground(w, at[0], at[1], roadY, 1, 2);
                if (y == Places.UNKNOWN) return null;
                if (y == Places.NONE) continue;
                int side = lateral > 0 ? 1 : -1;
                Location l = new Location(w, at[0], y, at[1], Roadway.yaw(t[1] * side, -t[0] * side), 0);
                if (Drive.standable(l.clone().add(0, 0.05, 0))) return l;
            }
        }
        // no dry ground near (a bridge's end): on the road's own kerb
        int i = c.to();
        double[] at = Roadway.beside(c.pts(), i, 2.6, 3), t = Roadway.tangent(c.pts(), i, 4);
        return new Location(w, at[0], Roadway.height(c.halves(), i), at[1], Roadway.yaw(t[1], -t[0]), 0);
    }

    private void partTick(Player p, ConfigurationSection t) {
        UUID u = p.getUniqueId();
        Errand.Course c = course(u);
        if (c == null) return;
        // a part lost on the way: another lies before the shutter
        Location o = g.places().garage();
        if (o != null && flat(p, o) <= cfg().getDouble("near", 10) && !hasPart(p) && g.once(u, "sq-part", 20)) {
            givePart(p, t);
            hush(p, "part-again", "<#9fb2c8>…흘렸어? 하나 더 있어.");
        }
        double[] q = c.pts().get(c.to());
        double d = p.getWorld().getName().equals(c.world()) ? Math.hypot(p.getLocation().getX() - q[0], p.getLocation().getZ() - q[1]) : Double.MAX_VALUE;
        double range = Math.max(24, cfg().getDouble("spot.range", 56));
        if (d > range + 16) {
            g.props().drop(u, SPOT);
            return;
        }
        if (d > range) return;
        Location at = spots.get(u);
        if (at == null) {
            at = spotAt(c);
            if (at == null) return;
            spots.put(u, at);
        }
        if (!g.props().has(u, SPOT)) {
            g.props().drop(u, SPOT);
            g.props().box(p, SPOT, at, block("spot.block", Material.BARREL).createBlockData(), -0.35f, 0, -0.35f, 0.7f, 0.7f, 0.7f);
            g.props().box(p, SPOT, at, block("spot.light", Material.SOUL_LANTERN).createBlockData(), -0.3f, 0.7f, -0.3f, 0.6f, 0.6f, 0.6f);
            g.props().hit(p, SPOT, at, 1.2f, 1.4f, SPOT);
        }
        VehicleModule vm = g.vm();
        if (get(u, "drove") == null && vm != null && d <= 48 && his(vm.drivenBy(p), u)) set(u, "drove", 1);
        p.spawnParticle(Particle.SOUL, at.getX(), at.getY() + 1.5, at.getZ(), 2, 0.15, 0.3, 0.15, 0.005);
        if (d <= 30 && g.once(u, "sq-spot", 90)) hush(p, "spot-seen", "<dark_gray>…길가에 상자가 하나 놓여 있다. 불이 켜져 있다.");
    }

    private Material block(String key, Material def) {
        Material mat = Material.matchMaterial(cfg().getString(key, def.name()));
        return mat == null || !mat.isBlock() ? def : mat;
    }

    /** A click on the roadside box. */
    void clickSpot(Player p) {
        UUID u = p.getUniqueId();
        ConfigurationSection t = task(u);
        if (!may(p) || state(u) != SequelState.State.ACTIVE || step(u) != ERRAND || t == null || !part(t)) return;
        if (!hasPart(p)) {
            hush(p, "spot-empty", "<dark_gray>…빈손이다. 부품은 차고 셔터 앞에 있다.");
            return;
        }
        if (get(u, "drove") == null) {
            hush(p, "spot-walked", "<#9fb2c8>…차로 가 줘. 그 차로 가기로 했었어.");
            return;
        }
        takePart(p);
        g.props().drop(u, SPOT);
        spots.remove(u);
        step(u, BACK);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_CLOSE, SoundCategory.AMBIENT, 0.7f, 0.8f);
        say(p, "part-left", "<dark_gray>…상자에 넣고 뚜껑을 닫았다. 등불이 한 번 흔들렸다.");
        g.later(p, 40, () -> say(p, "passed", "<#9fb2c8>…됐어. 차고로 와."));
    }

    // ------------------------------------------------------------------------------------------
    // beginning: the note
    // ------------------------------------------------------------------------------------------

    /** Chooses this week's task among those the roads allow and writes it down. False when there is none (or nothing can happen now). */
    private boolean choose(Player p) {
        UUID u = p.getUniqueId();
        if (dormant() != null) return false;
        Places.Site site = g.places().site();
        ConfigurationSection all = tasks();
        long seed = Errand.seed(week(u), u);
        String other = otherDock(site, seed);
        List<String> ok = new ArrayList<>();
        Map<String, String> road = new HashMap<>();
        for (String id : all.getKeys(false)) {
            ConfigurationSection t = all.getConfigurationSection(id);
            if (t == null || !t.getBoolean("enabled", true)) continue;
            String dock = "other".equalsIgnoreCase(t.getString("road", "home")) && other != null ? other : "";
            if (!feasible(course(site, t, dock), t)) continue;
            ok.add(id);
            road.put(id, dock);
        }
        String id = Errand.pick(ok, Errand.list(kept(u, "recent", ""), ","), seed);
        if (id == null) return false;
        assign(u, id, road.get(id), seed);
        keep(u, "recent", Errand.remember(kept(u, "recent", ""), id, cfg().getInt("avoid-recent", 4)));
        return true;
    }

    private void assign(UUID u, String id, String dock, long seed) {
        runs.remove(u);
        courses.remove(u);
        spots.remove(u);
        g.props().drop(u, SPOT);
        for (String k : List.of("tries", "part", "drove")) remove(u, k);
        set(u, "task", id);
        set(u, "road", dock == null ? "" : dock);
        set(u, "seed", seed);
    }

    @Override
    protected boolean offer(Player p) {
        UUID u = p.getUniqueId();
        Places.Site site = g.places().site();
        if (site == null || !p.getWorld().getName().equals(site.world())) return false;
        Location o = g.places().garage();
        if (o == null || flat(p, o) > cfg().getDouble("near", 10) || !g.night(p) || !g.shedShowing(u)) return false;
        if (!chance(p, "chance", 1.0) || !choose(p)) return false;
        slide(p);
        return true;
    }

    @Override
    protected void start(Player p) {
        if (!choose(p)) {
            String why = dormant();
            p.sendMessage(Text.mm("<red>[폐차고 후속] 지금은 쪽지를 정하지 못함: " + (why != null ? why : "지금 길에서 할 수 있는 과제가 없음")
                    + " <gray>(될 때 저절로 정해짐)"));
            return;
        }
        Location o = g.places().garage();
        if (o != null && flat(p, o) <= 24 && g.shedShowing(p.getUniqueId())) slide(p);
    }

    /** The shutter lifts a finger's width, a note slides out, it comes down again. */
    private void slide(Player p) {
        UUID u = p.getUniqueId();
        Location o = g.places().garage();
        if (o == null) return;
        lift(p, 0.07, 6);
        p.playSound(o, Sound.BLOCK_CHAIN_STEP, SoundCategory.AMBIENT, 0.7f, 0.5f);
        g.later(p, 10, () -> {
            note(p, o);
            p.playSound(o, Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.AMBIENT, 0.8f, 0.7f);
        });
        g.later(p, 22, () -> lift(p, 0, 8));
        g.later(p, 34, () -> lower(u));
        g.later(p, 40, () -> say(p, "note-out", "<dark_gray>…셔터 밑으로 쪽지 한 장이 밀려 나왔다."));
    }

    private void lift(Player p, double open, int ticks) {
        lifted.add(p.getUniqueId());
        g.shutter(p, open, ticks);
    }

    private void lower(UUID u) {
        if (lifted.remove(u)) g.release(u);
    }

    /** The note lying before the shutter (a sheet of paper flat on the apron, a click box over it). */
    private void note(Player p, Location o) {
        UUID u = p.getUniqueId();
        g.props().drop(u, NOTE);
        Location at = Props.local(o, 0.7, 0.03, 0.85);
        g.props().item(p, NOTE, at, new ItemStack(Material.PAPER), new Vector3f(0, 0.01f, 0), new Vector3f(0.5f, 0.5f, 0.5f),
                new Quaternionf().rotateX((float) Math.toRadians(-90)).rotateZ((float) Math.toRadians(23)));
        g.props().hit(p, NOTE, at.clone().add(0, -0.1, 0), 0.9f, 0.5f, NOTE);
    }

    /** A click on the note: it is theirs now (active, step 1) and they read it. */
    void clickNote(Player p) {
        UUID u = p.getUniqueId();
        if (!may(p) || state(u) != SequelState.State.OFFERED) return;
        ConfigurationSection t = task(u);
        if (t == null || !accept(p)) return;
        step(u, ERRAND);
        g.props().drop(u, NOTE);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.9f);
        read(p, t);
        if (part(t)) {
            givePart(p, t);
            g.later(p, 30, () -> say(p, "part-given", "<dark_gray>…쪽지 밑에 기름종이에 싼 것이 같이 놓여 있었다."));
        }
        photo(p);
    }

    /** The note's words: his few lines, what is asked, and how. */
    private void read(Player p, ConfigurationSection t) {
        UUID u = p.getUniqueId();
        Errand.Course c = course(u);
        Map<String, String> words = words(u, t, c);
        say(p, "note-head", "<dark_gray>— 구겨진 쪽지. 연필로 눌러 쓴 글씨 —");
        List<String> lines = fallback(u, t) && t.isList("note-home") ? t.getStringList("note-home") : t.getStringList("note");
        for (String s : lines) p.sendMessage(Text.mm(fill(s, words)));
        String goal = text(u, t, "goal", "");
        if (!goal.isEmpty()) p.sendMessage(Text.mm(fill(goal, words)));
        Places.Site site = g.places().site();
        if (c != null && site != null && !home(site, c.dock())) p.sendMessage(Text.mm(fill(ln("note-where", "<dark_gray>(귀퉁이에 작게: {sx}, {sz} 에서부터)"), words)));
        if (!part(t)) p.sendMessage(Text.mm(fill(ln("note-tries", "<dark_gray>(그 밑에: 안 되면 한 번 더. 그래도 안 되면 다음 주에.)"), words)));
        p.sendMessage(Text.mm(fill(ln("note-car", "<dark_gray>(맨 아래: 네 차로. 없으면 셔터 앞에 잠깐 서 있어.)"), words)));
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        Places.Site site = g.places().site();
        if (get(u, "task") == null) {   // an admin began it while nothing could be chosen: chosen when it can
            if (elapsed(u) % 5 == 0) choose(p);
            return;
        }
        ConfigurationSection t = task(u);
        if (site == null || t == null) {   // the roads went, or the task was taken out of the file: it sleeps until the week turns
            quietly(u);
            return;
        }
        boolean here = p.getWorld().getName().equals(site.world());
        Location o = g.places().garage();
        if (state(u) == SequelState.State.OFFERED) {
            if (!here || o == null || flat(p, o) > 40 || !g.shedShowing(u)) {
                g.props().drop(u, NOTE);
                return;
            }
            if (!g.props().has(u, NOTE)) note(p, o);
            if (flat(p, o) <= 14 && g.once(u, "sq-note", 60)) hush(p, "note-there", "<dark_gray>…셔터 밑에 쪽지가 끼어 있다.");
            return;
        }
        int step = step(u);
        lending(p, site, step == ERRAND);
        if (step >= BACK) {
            runs.remove(u);
            if (here && !p.isDead() && flat(p, o != null ? o : g.places().garageNear()) <= cfg().getDouble("near", 10)) finish(p);
            return;
        }
        if (here && o != null && flat(p, o) <= 14 && g.once(u, "sq-goal", 90)) {
            String goal = text(u, t, "goal", "");
            if (!goal.isEmpty()) m.haunt().whisper(p, fill(goal, words(u, t, course(u))));
        }
        if (part(t)) {
            partTick(p, t);
            return;
        }
        if (runs.containsKey(u)) return;
        Errand.Course c = course(u);
        if (c == null) return;
        runs.put(u, new Run(c, rules(u, t, c), t.getBoolean("night")));
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(eco, this::fast, 1L, 1L);
    }

    private void quietly(UUID u) {
        runs.remove(u);
        g.props().drop(u, NOTE);
        g.props().drop(u, SPOT);
    }

    @Override
    protected void resume(Player p) {
        UUID u = p.getUniqueId();
        ConfigurationSection t = task(u);
        if (t == null || state(u) != SequelState.State.ACTIVE) return;
        if (step(u) >= BACK) hush(p, "passed", "<#9fb2c8>…됐어. 차고로 와.");
        else {
            String goal = text(u, t, "goal", "");
            if (!goal.isEmpty()) m.haunt().whisper(p, fill(goal, words(u, t, course(u))));
        }
        photo(p);
    }

    // ------------------------------------------------------------------------------------------
    // every tick: the driven stretch
    // ------------------------------------------------------------------------------------------

    private void fast() {
        if (runs.isEmpty()) {
            if (task != null) task.cancel();
            task = null;
            return;
        }
        VehicleModule vm = g.vm();
        for (Map.Entry<UUID, Run> e : new ArrayList<>(runs.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || vm == null || !may(p) || state(e.getKey()) != SequelState.State.ACTIVE || step(e.getKey()) != ERRAND) {
                runs.remove(e.getKey());
                continue;
            }
            try {
                drive(p, e.getValue(), vm);
            } catch (RuntimeException ex) {
                eco.getLogger().log(Level.WARNING, "[비밀] 폐차고 후속 주행 오류", ex);
                runs.remove(e.getKey());
            }
        }
    }

    private void drive(Player p, Run r, VehicleModule vm) {
        UUID u = p.getUniqueId();
        r.beat++;
        if (r.quiet > 0) r.quiet--;
        if (r.cool > 0) {
            r.cool--;
            return;
        }
        Errand.Course c = r.course;
        VehicleModule.Driven d = vm.drivenBy(p);
        if (!r.going) {
            if (!his(d, u) || !d.world().getName().equals(c.world())) {
                r.armed = false;
                return;
            }
            if (!r.armed && r.beat % 10 != 0) return;
            double s = r.armed ? Roadway.project(c.pts(), d.x(), d.z(), (int) r.along - 40, (int) r.along + 40)
                    : Roadway.project(c.pts(), d.x(), d.z(), 0, c.pts().size() - 1);
            r.along = s;
            double prog = c.prog(s), lat = Roadway.lateral(c.pts(), s, d.x(), d.z(), 3);
            if (Math.abs(lat) > 7 || prog < -ZONE) {
                r.armed = false;
                if (prog < 0 && prog > -90 && r.beat % 20 == 0) beacon(p, c);   // coming up to where it starts
                return;
            }
            if (prog <= ZONE) {
                if (r.night && !g.night(p)) {
                    r.armed = false;
                    if (g.once(u, "sq-night", 20)) hush(p, "at-night", "<#9fb2c8>…밤에 하자. 이건 밤에 하던 거야.");
                    return;
                }
                if (!r.armed) {
                    r.armed = true;
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.7f);
                    if (g.once(u, "sq-here", 15)) hush(p, "from-here", "<#9fb2c8>…여기서부터야.");
                }
                if (r.beat % 5 == 0) marks(p, r, 0);
                return;
            }
            if (!r.armed) return;   // they came onto the road past its start: not from here
            r.going = true;
            r.st = new Errand.Progress();
            r.car = d.id();
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 0.8f);
            ConfigurationSection t = task(u);
            String go = t == null ? "" : text(u, t, "go", "");
            if (!go.isEmpty()) {
                m.haunt().whisper(p, fill(go, words(u, t, c)));
                r.quiet = 50;
            }
            return;
        }
        if (d == null || d.id() != r.car) {
            broke(p, r, "out", "<#9fb2c8>…내리면 거기까지야.");
            return;
        }
        double s = Roadway.project(c.pts(), d.x(), d.z(), (int) r.along - 50, (int) r.along + 50);
        r.along = s;
        Errand.Sample now = new Errand.Sample(c.prog(s), Roadway.lateral(c.pts(), s, d.x(), d.z(), 3), d.speed(), d.bumped());
        Errand.Verdict v = Errand.step(r.rules, c.length(), r.st, now);
        switch (v) {
            case GO -> going(p, r, now);
            case PASS -> passed(p);
            case BUMP -> broke(p, r, "bump", "<#9fb2c8>…긁혔네.");
            case LATE -> broke(p, r, "late", "<#9fb2c8>…늦었어. 그날처럼.");
            case FAST -> broke(p, r, "fast", "<#9fb2c8>…너무 밟았어.");
            case SLOW -> broke(p, r, "slow", "<#9fb2c8>…바늘이 떨어졌어.");
            case GATE -> broke(p, r, "gate", "<#9fb2c8>…거긴 내가 밟던 자리가 아니야.");
            case OVER -> broke(p, r, "over", "<#9fb2c8>…넘었어. 나도 거기서 넘었어.");
            case OFF -> broke(p, r, "off", "<#9fb2c8>…길 밖이야.");
        }
    }

    /** Still going: the lights, the read-out, a word when a rule is about to break. */
    private void going(Player p, Run r, Errand.Sample now) {
        Errand.Rules rules = r.rules;
        Errand.Progress st = r.st;
        Errand.Course c = r.course;
        if (st.hit) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.1f + Math.min(0.8f, st.passed * 0.05f));
        if (st.miss) {
            p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, 0.7f, 0.7f);
            hush(p, "gate-missed", "<#9fb2c8>…하나 놓쳤어.");
        }
        if (st.tooGentle) hush(p, "stop-gentle", "<#9fb2c8>…그렇게 살살 와서는 몰라. 달려오다가 서 봐.");
        if (r.beat % 5 == 0) marks(p, r, st.gate);
        double v = Math.abs(now.speed());
        if (rules.band() && st.outBand == 12) {
            if (v > rules.hi()) hush(p, "band-fast", "<#9fb2c8>…빨라. 발 떼.");
            else hush(p, "band-slow", "<#9fb2c8>…처진다. 조금만 더.");
        }
        if (r.quiet > 0 || r.beat % 4 != 0) return;
        List<String> hud = new ArrayList<>();
        if (rules.band()) hud.add(ln("hud-speed", "<gray>바늘 {color}{v} <dark_gray>({lo}~{hi})", "color", v > rules.hi() || v < rules.lo() ? "<red>" : "<white>",
                "v", String.format("%.1f", v), "lo", num(rules.lo()), "hi", num(rules.hi())));
        if (rules.limitTicks() > 0) hud.add(ln("hud-time", "<gray>남은 시간 <white>{s}초", "s", String.valueOf(Math.max(0, (rules.limitTicks() - st.ticks + 19) / 20))));
        if (!rules.gates().isEmpty()) hud.add(ln("hud-lights", "<gray>불빛 <white>{n}<dark_gray>/{of}", "n", String.valueOf(st.passed), "of", String.valueOf(rules.gates().size())));
        if (rules.stop()) hud.add(ln("hud-line", "<gray>선까지 <white>{d}", "d", String.valueOf(Math.max(0, Math.round(c.length() - now.prog())))));
        if (hud.isEmpty()) hud.add(ln("hud-left", "<gray>남은 길 <white>{d}", "d", String.valueOf(Math.max(0, Math.round(c.length() - now.prog())))));
        m.haunt().whisper(p, String.join(" <dark_gray>· ", hud));
    }

    /** A faint light where the stretch starts, for someone driving up to it. */
    private void beacon(Player p, Errand.Course c) {
        double[] q = Roadway.at(c.pts(), c.from());
        double y = Roadway.height(c.halves(), c.from());
        p.spawnParticle(Particle.SOUL, q[0], y + 1.2, q[1], 6, 0.2, 0.7, 0.2, 0.005);
    }

    /** What only they see on the road: the next lights of his line, the white line to stop at. */
    private void marks(Player p, Run r, int next) {
        Errand.Course c = r.course;
        List<double[]> gates = r.rules.gates();
        for (int i = next; i < Math.min(gates.size(), next + 4); i++) {
            double s = c.at(gates.get(i)[0]);
            double[] q = Roadway.beside(c.pts(), s, gates.get(i)[1], 3);
            double y = Roadway.height(c.halves(), s);
            if (i == next) p.spawnParticle(Particle.SOUL_FIRE_FLAME, q[0], y + 0.7, q[1], 3, 0.06, 0.4, 0.06, 0.002);
            else p.spawnParticle(Particle.END_ROD, q[0], y + 0.6, q[1], 1, 0.04, 0.3, 0.04, 0);
        }
        if (r.rules.stop() && r.beat % 10 == 0) {
            Particle.DustOptions white = new Particle.DustOptions(Color.fromRGB(0xf2f2f2), 1.1f);
            double y = Roadway.height(c.halves(), c.to()) + 0.08;
            for (double l = -4; l <= 4.01; l += 0.5) {
                double[] q = Roadway.beside(c.pts(), c.to(), l, 3);
                p.spawnParticle(Particle.DUST, q[0], y, q[1], 1, 0.02, 0, 0.02, 0, white);
            }
        }
    }

    /** The stretch is done: back to the garage (step 2). */
    private void passed(Player p) {
        UUID u = p.getUniqueId();
        runs.remove(u);
        step(u, BACK);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 0.7f);
        say(p, "passed", "<#9fb2c8>…됐어. 차고로 와.");
    }

    /** A rule was broken: once more from the start, or (the tries used up) that was this week. */
    private void broke(Player p, Run r, String why, String def) {
        UUID u = p.getUniqueId();
        int used = (int) getLong(u, "tries", 0) + 1, max = Math.max(1, cfg().getInt("tries", 2));
        set(u, "tries", used);
        r.going = false;
        r.armed = false;
        r.st = null;
        r.cool = 60;
        p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.AMBIENT, 0.3f, 0.5f);
        say(p, "broke-" + why, def);
        if (used >= max) lose(p);
        else g.later(p, 30, () -> say(p, "again", "<#9fb2c8>…한 번 더. 처음부터."));
    }

    /** That was this week: what the mission lent goes back, nothing else is lost. */
    private void lose(Player p) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        runs.remove(u);
        takeCar(p, true);
        takePart(p);
        g.props().drop(u, SPOT);
        g.props().drop(u, NOTE);
        fail(p, "lines.fail");
    }

    /** Back at the garage: a line from behind the shutter, the reward, one more line on the back of the photo. */
    private void finish(Player p) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        ConfigurationSection t = task(u);
        String id = get(u, "task", "");
        runs.remove(u);
        takeCar(p, true);
        takePart(p);
        g.props().drop(u, SPOT);
        keep(u, "stickers", Errand.stick(kept(u, "stickers", ""), date(System.currentTimeMillis()), id, Math.max(1, cfg().getInt("stickers", 6))));
        boolean stuck = photo(p);
        Location o = g.places().garage();
        if (o != null && g.shedShowing(u)) {
            lift(p, 0.1, 10);
            p.playSound(o, Sound.BLOCK_CHAIN_STEP, SoundCategory.AMBIENT, 0.7f, 0.5f);
            g.later(p, 70, () -> lift(p, 0, 12));
            g.later(p, 90, () -> lower(u));
        }
        String after = t == null ? "" : text(u, t, "after", "");
        p.sendMessage(Text.mm(after.isEmpty() ? ln("after", "<#9fb2c8>…고마워. 그것도 못 하고 갔거든.") : fill(after, words(u, t, courses.get(u)))));
        if (stuck) say(p, "sticker", "<dark_gray>…사진 뒷면에 연필 글씨가 한 줄 늘어 있다.");
        else say(p, "sticker-later", "<dark_gray>…사진을 가지고 오면, 뒷면에 한 줄이 늘어 있을 것이다.");
        complete(p);
        courses.remove(u);
    }

    // ------------------------------------------------------------------------------------------
    // the back of the photo
    // ------------------------------------------------------------------------------------------

    private static boolean donePhoto(ItemStack it, UUID u) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        return u.toString().equals(pdc.get(Garage.PHOTO_OWNER, PersistentDataType.STRING)) && pdc.has(Garage.PHOTO_DONE);
    }

    /**
     * Writes the list of finished things on every "사진 — 두 대" of theirs they carry (the photo's own lines stay as they
     * were; the list under them is written anew). True when a photo was there.
     */
    private boolean photo(Player p) {
        UUID u = p.getUniqueId();
        String saved = kept(u, "stickers", "");
        List<Errand.Done> done = Errand.stuck(saved);
        ConfigurationSection all = tasks();
        List<String> lines = new ArrayList<>();
        if (!done.isEmpty()) lines.add(ln("photo-list", "<dark_gray>— 그 뒤에 대신 해 준 것 —"));
        for (Errand.Done d : done) {
            String what = all == null ? null : all.getString(d.id() + ".sticker");
            lines.add(ln("photo-line", "<gray>{date} <dark_gray>· <gray>{what}", "date", d.date(), "what", what == null ? ln("photo-unknown", "남기고 간 일 하나") : what));
        }
        long more = count(u) - done.size();
        if (more > 0) lines.add(ln("photo-more", "<dark_gray>…그리고 {n}번 더.", "n", String.valueOf(more)));
        String mark = saved + "#" + more;
        boolean found = false;
        PlayerInventory inv = p.getInventory();
        ItemStack[] slots = inv.getContents();
        for (int i = 0; i < slots.length; i++) {
            ItemStack it = slots[i];
            if (!donePhoto(it, u)) continue;
            found = true;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            if (mark.equals(pdc.get(PHOTO_LIST, PersistentDataType.STRING))) continue;
            it.editMeta(meta -> {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                Integer base = meta.getPersistentDataContainer().get(PHOTO_BASE, PersistentDataType.INTEGER);
                if (base == null) {
                    base = lore.size();
                    meta.getPersistentDataContainer().set(PHOTO_BASE, PersistentDataType.INTEGER, base);
                }
                List<Component> out = new ArrayList<>(lore.subList(0, Math.min(base, lore.size())));
                for (String s : lines) out.add(Text.item(s));
                meta.lore(out);
                meta.getPersistentDataContainer().set(PHOTO_LIST, PersistentDataType.STRING, mark);
            });
            inv.setItem(i, it);
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------
    // ending without an outcome
    // ------------------------------------------------------------------------------------------

    @Override
    protected void cleanup(UUID u) {
        runs.remove(u);
        courses.remove(u);
        spots.remove(u);
        afoot.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (p != null) takeCar(p, true);   // (a quit: where they stand next time was kept in onQuit)
        else dropCar(u);
        g.props().drop(u, NOTE);
        g.props().drop(u, SPOT);
        lower(u);
    }

    /** The week turned (or an admin reset it): the lent car and the part are the mission's and go; nothing of the player's was ever held. */
    @Override
    protected void expired(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            takeCar(p, true);
            takePart(p);   // (a part left in a chest is only paper: its serial is forgotten with the notes)
        } else dropCar(u);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    private String where(Errand.Course c, int i) {
        double[] q = c.pts().get(Math.clamp(i, 0, c.pts().size() - 1));
        return Math.round(q[0]) + ", " + Roadway.surfaceY(c.halves(), i) + ", " + Math.round(q[1]);
    }

    private String rulesWords(Errand.Rules r) {
        List<String> out = new ArrayList<>();
        if (r.bumps() >= 0) out.add("부딪힘 " + r.bumps() + "번까지");
        if (r.limitTicks() > 0) out.add(r.limitTicks() / 20 + "초 안에");
        if (r.band()) out.add("속도 " + num(r.lo()) + "~" + num(r.hi()));
        if (!r.gates().isEmpty()) out.add("불빛 " + r.gates().size() + "개 (" + r.misses() + "개까지 놓쳐도 됨)");
        if (r.stop()) out.add(num(r.reach()) + " 넘게 달리다 끝 " + num(r.box()) + "칸 안에 정지");
        return String.join(", ", out);
    }

    @Override
    public String status(UUID u) {
        String sleep = dormant();
        if (sleep != null) return sleep;
        Places.Site site = g.places().site();
        SequelState.State st = state(u);
        String garage = "차고 " + site.world() + " " + Places.coords(site, site.garage());
        if (!st.live()) return (st == SequelState.State.NONE ? "밤에 차고 " + num(cfg().getDouble("near", 10)) + "칸 안으로 오면 쪽지가 나옴" : "다음 주에 새 쪽지")
                + " · " + garage + " · 뒷면 " + Errand.stuck(kept(u, "stickers", "")).size() + "줄 · 최근 " + kept(u, "recent", "-");
        String id = get(u, "task");
        ConfigurationSection t = task(u);
        if (id == null) return "쪽지를 아직 정하지 못함 (지금 길에서 할 수 있는 과제가 없음: 될 때 저절로 정해짐) · " + garage;
        if (t == null) return "쪽지 " + id + " 가 garage.yml 에 없음: 이번 주는 잠듦";
        List<String> out = new ArrayList<>();
        out.add("쪽지 " + id + " (" + t.getString("sticker", t.getString("kind", "drive")) + ")");
        Errand.Course c = course(u);
        if (c == null) {
            out.add("그 길을 지금 찾을 수 없음 (" + get(u, "road", "") + "): 이번 주는 잠듦");
            return String.join(" · ", out);
        }
        out.add("길 " + (home(site, c.dock()) ? "차고의 길" : c.dock()) + (fallback(u, t) ? " (다른 국도가 없어 대신)" : ""));
        if (st == SequelState.State.OFFERED) out.add("할 일: 차고 셔터 앞의 쪽지를 우클릭 (" + garage + ")");
        else if (step(u) >= BACK) out.add("할 일: 차고로 돌아오기 (" + garage + ")");
        else if (part(t)) out.add("할 일: 부품을 지니고 미완의 질주로 " + where(c, c.to()) + " 길가 상자까지 가서 상자를 우클릭"
                + (get(u, "drove") != null ? " (차로 도착함)" : "") + (Bukkit.getPlayer(u) != null && !hasPart(Bukkit.getPlayer(u)) ? " (부품 없음: 차고 앞에 서면 다시 줌)" : ""));
        else {
            out.add("할 일: 미완의 질주를 몰고 " + where(c, c.from()) + " (앞뒤 " + (int) ZONE + "칸) 에서 출발 → " + where(c, c.to()) + " (" + c.length() + "칸"
                    + (t.getBoolean("night") ? ", 밤에만" : "") + ")");
            out.add("규칙: " + rulesWords(rules(u, t, c)));
            out.add("실패 " + getLong(u, "tries", 0) + "/" + Math.max(1, cfg().getInt("tries", 2)));
            Run r = runs.get(u);
            if (r != null && r.going) out.add("주행 중 " + Math.round(c.prog(r.along)) + "/" + c.length());
            else if (r != null && r.armed) out.add("출발선에 있음");
        }
        if (lent.containsKey(u)) out.add("빌린 차 있음");
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        List<String> out = new ArrayList<>(List.of("쪽지", "목록", "빌린차", "사진", "완료", "실패"));
        ConfigurationSection all = tasks();
        if (all != null) out.addAll(all.getKeys(false));
        return out;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        Places.Site site = g.places().site();
        ConfigurationSection all = tasks();
        switch (word) {
            case "쪽지" -> {
                Location o = g.places().garage();
                if (o == null || !g.shedShowing(u)) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] 그 사람에게 차고가 보이는 곳 (차고 64칸 안) 에서 해 주세요"));
                    return true;
                }
                boolean keep = state(u) == SequelState.State.OFFERED;
                slide(p);
                if (!keep) g.later(p, 300, () -> {
                    if (state(u) != SequelState.State.OFFERED) g.props().drop(u, NOTE);
                });
            }
            case "목록" -> {
                String sleep = dormant();
                if (sleep != null) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] " + sleep));
                    return true;
                }
                long seed = Errand.seed(week(u), u);
                String other = otherDock(site, seed);
                admin.sendMessage(Text.mm("<gold>도윤 모터스 <gray>과제 · 이번 주(" + week(u) + ") 다른 국도: <white>" + (other == null ? "없음 (차고의 길로 대신)" : other)));
                for (String id : all.getKeys(false)) {
                    ConfigurationSection t = all.getConfigurationSection(id);
                    if (t == null) continue;
                    String dock = "other".equalsIgnoreCase(t.getString("road", "home")) && other != null ? other : "";
                    Errand.Course c = course(site, t, dock);
                    admin.sendMessage(Text.mm("<gray>- <white>" + id + " <gray>" + t.getString("sticker", "") + " · " + (!t.getBoolean("enabled", true) ? "<red>꺼짐"
                            : !feasible(c, t) ? "<red>이 길에서는 못 함 (너무 짧거나 길이 없음)"
                            : part(t) ? "상자 " + where(c, c.to()) : where(c, c.from()) + " → " + where(c, c.to()) + " (" + c.length() + "칸)")));
                }
                return true;
            }
            case "빌린차" -> {
                if (site == null || !live(u) || !lend(p, site)) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] 빌려주지 못함 (임무가 진행 중이어야 하고, 차고 앞 길의 땅이 불러와져 있어야 하며, 이미 빌린 차가 없어야 함)"));
                    return true;
                }
            }
            case "사진" -> {
                admin.sendMessage(Text.mm(photo(p) ? "<green>[폐차고 후속] 사진 뒷면을 다시 적음 (" + Errand.stuck(kept(u, "stickers", "")).size() + "줄)"
                        : "<red>[폐차고 후속] 그 사람 인벤토리에 '사진 — 두 대' 가 없음"));
                return true;
            }
            case "완료" -> {
                if (!live(u) || get(u, "task") == null) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] 진행 중인 임무가 없음"));
                    return true;
                }
                if (state(u) == SequelState.State.OFFERED) accept(p);
                step(u, BACK);
                finish(p);
            }
            case "실패" -> {
                if (!live(u)) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] 진행 중인 임무가 없음"));
                    return true;
                }
                lose(p);
            }
            default -> {
                ConfigurationSection t = all == null ? null : all.getConfigurationSection(word);
                if (t == null) return false;
                if (!live(u) || site == null) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] 먼저 임무를 시작해 주세요 (/관리자 비밀 후속 폐차고 시작): 진행 중인 쪽지를 이 과제로 바꿈"));
                    return true;
                }
                long seed = Errand.seed(week(u), u);
                String other = otherDock(site, seed);
                String dock = "other".equalsIgnoreCase(t.getString("road", "home")) && other != null ? other : "";
                if (!feasible(course(site, t, dock), t)) {
                    admin.sendMessage(Text.mm("<red>[폐차고 후속] 이 길에서는 못 하는 과제예요 (너무 짧거나 길이 없음)"));
                    return true;
                }
                takePart(p);
                assign(u, word, dock, seed);
                if (state(u) == SequelState.State.ACTIVE) {
                    step(u, ERRAND);
                    read(p, t);
                    if (part(t)) givePart(p, t);
                }
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] 후속 " + word + " → " + p.getName()));
        return true;
    }
}
