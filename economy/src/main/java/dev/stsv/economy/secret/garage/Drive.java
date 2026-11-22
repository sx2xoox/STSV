package dev.stsv.economy.secret.garage;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.vehicle.VehicleModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The last drive (폐차고 stage 3 → 4). His restored car — a real temporary 튜닝카 of the core's vehicle module in the true
 * paint ({@code VehicleModule#spawnTemporary}; a temporary vehicle takes a paint only, so its wheels are stock and it
 * has no glow) — waits before the bend. Its player gets in ({@link #board}); the ghost, a display-only copy in the whole
 * true look ({@link CarLook}) that only they see, pulls up beside them and drives the road's points home at a human pace
 * ({@link Roadway#cruise}, {@link Roadway#adapt}). The rules are checked every tick: stay in his car, do not crash, stay
 * beside him. At the bend he died on his car skids out for a second and comes back. At the garage he rolls in, his
 * lights go dark and the shutter comes down ({@link Garage#arrived}); a broken rule ends the night ({@link Garage#failed}).
 * <p>
 * A run lives in memory only and belongs to an online player: whatever ends it takes the temporary car away
 * ({@link #end}; quitting, dying, changing worlds, a reload and the server stopping all come here).
 */
final class Drive {

    static final String GHOST = "ghost", WAIT = "wait";
    static final int WAITING = 0, READY = 1, GO = 2, HOME = 3, DEMO = 4, FADE = 5;

    static final class Run {
        final UUID u;
        /** an admin's look: nothing is kept, nothing is won or lost */
        final boolean preview;
        final String world;
        final List<double[]> pts;
        final int[] halves;
        final int bend, outside, end;
        long car;
        int phase, ticks;
        /** the ghost: its place along the road (points), its speed (blocks/s), its wheels' roll */
        double s, v, roll;
        /** the player's car: its place along the road */
        double along;
        int far, out, bumps, flick = -1, warned;
        boolean bumped;
        CarLook.Car ghost;
        Location stands, from, door, inside;
        long born = System.currentTimeMillis();

        Run(UUID u, boolean preview, String world, List<double[]> pts, int[] halves, int bend, int outside, int end) {
            this.u = u;
            this.preview = preview;
            this.world = world;
            this.pts = pts;
            this.halves = halves;
            this.bend = bend;
            this.outside = outside;
            this.end = end;
        }
    }

    private final Garage g;
    private final Map<UUID, Run> runs = new HashMap<>();
    /** player -> not before this (ms) does his car come again: it waited its time out and nobody got in */
    private final Map<UUID, Long> rest = new HashMap<>();
    private BukkitTask task;

    Drive(Garage g) {
        this.g = g;
    }

    private YamlConfiguration cfg() {
        return g.cfg();
    }

    boolean has(UUID u) {
        return runs.containsKey(u);
    }

    Run run(UUID u) {
        return runs.get(u);
    }

    /** Whether an admin's look at the drive is on for this player (the garage is shown open and empty for it). */
    boolean previewing(UUID u) {
        Run r = runs.get(u);
        return r != null && r.preview && r.phase != DEMO;
    }

    private double lane() {
        return Math.clamp(cfg().getDouble("drive.lane", 1.3), 0, 2.5);
    }

    private void add(Run r) {
        runs.put(r.u, r);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(g.eco(), this::tick, 1L, 1L);
    }

    /** Where a car stands on the road: s points along, {@code lateral} blocks right of the middle, facing the way the road runs. */
    private static Location pose(Run r, World w, double s, double lateral, float turn) {
        double[] p = Roadway.beside(r.pts, s, lateral, 3), t = Roadway.tangent(r.pts, s, 3);
        return new Location(w, p[0], Roadway.height(r.halves, s) + 0.02, p[1], Roadway.yaw(t[0], t[1]) + turn, 0);
    }

    /** Where a waiting car goes: before the bend, further back for each car already standing there. */
    private double startAt(Places.Site site) {
        int lead = Math.max(10, cfg().getInt("drive.lead", 30));
        double s = site.bend() - lead;
        for (int k = 0; k < 4; k++) {
            double at = s - k * 8;
            boolean free = true;
            for (Run o : runs.values()) if (o.phase <= READY && o.world.equals(site.world()) && Math.abs(o.s - at) < 6) free = false;
            if (free) return Math.max(2, at);
        }
        return Math.max(2, s);
    }

    private Run fresh(Player p, Places.Site site, boolean preview) {
        Run r = new Run(p.getUniqueId(), preview, site.world(), site.pts(), site.halves(), site.bend(), site.outside(), site.garage());
        r.s = startAt(site);
        r.along = r.s;
        return r;
    }

    private long spawn(Run r, World w, Player driver) {
        VehicleModule vm = g.vm();
        CustomItem item = g.cars().item();
        if (vm == null || item == null) return 0;
        Location at = pose(r, w, r.s, lane(), 0);
        at.add(0, 0.08, 0);
        // every chunk the car reaches into is there (the core asks only for the one under its middle; its click boxes and
        // seats would be put into the others)
        for (int cx = (int) Math.floor(at.getX() - 5) >> 4; cx <= (int) Math.floor(at.getX() + 5) >> 4; cx++)
            for (int cz = (int) Math.floor(at.getZ() - 5) >> 4; cz <= (int) Math.floor(at.getZ() + 5) >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) return 0;
        Combo answer = g.answer();
        long id = vm.spawnTemporary(item, w, at.getX(), at.getY(), at.getZ(), at.getYaw(), answer == null ? null : answer.paint(), driver);
        if (id == 0) return 0;
        vm.letOutOfTemporary(id);   // it drives like an owned car (the road's bonus) and its driver may get out (which ends the night)
        r.car = id;
        r.stands = at;
        return id;
    }

    /** His car appears before the bend with its lights on, and waits for this player to get in. False when it cannot (yet). */
    boolean await(Player p, Places.Site site) {
        UUID u = p.getUniqueId();
        World w = site.bukkit();
        if (w == null || runs.containsKey(u)) return false;
        Long until = rest.get(u);
        if (until != null) {
            if (System.currentTimeMillis() < until) return false;
            rest.remove(u);
        }
        Run r = fresh(p, site, false);
        if (spawn(r, w, null) == 0) return false;
        r.phase = WAITING;
        add(r);
        g.props().drop(u, WAIT);
        g.cars().lampsOver(p, WAIT, r.stands);
        p.playSound(r.stands, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.AMBIENT, 0.6f, 0.6f);
        g.say(p, "car-waits", "<#9fb2c8>…타.");
        return true;
    }

    /** The drive begins at once with this player at the wheel (an admin's 주행, or a jump to the last stage). Why not, or null. */
    String now(Player p, Places.Site site, boolean preview) {
        UUID u = p.getUniqueId();
        World w = site.bukkit();
        if (w == null) return "그 월드가 없어요";
        if (g.vm() == null) return "코어의 탈것 모듈이 꺼져 있어요";
        if (g.cars().item() == null) return "탈것 아이템 " + g.cars().itemId() + " 이(가) 없어요";
        end(u);
        Run r = fresh(p, site, preview);
        Location at = pose(r, w, r.s, lane(), 0);
        if (p.isInsideVehicle()) p.leaveVehicle();
        if (!p.getWorld().equals(w) || p.getLocation().distanceSquared(at) > 24 * 24) p.teleport(at.clone().add(0, 0.3, 0));   // (loads the chunk)
        if (spawn(r, w, p) == 0) return "차를 세울 수 없어요 (그 자리의 청크가 아직 없음)";
        add(r);
        ready(r, p);
        return null;
    }

    /** A click on a vehicle part: this player's own waiting car lets them in. */
    void board(Player p, long id) {
        Run r = runs.get(p.getUniqueId());
        VehicleModule vm = g.vm();
        if (r == null || vm == null || r.car != id || r.phase != WAITING) return;
        if (!r.preview && !g.mayGoOn(p)) return;
        if (vm.seatTemporary(id, p)) ready(r, p);
    }

    /** They are in: the car holds still while he pulls up beside them. */
    private void ready(Run r, Player p) {
        VehicleModule vm = g.vm();
        World w = p.getWorld();
        vm.freezeTemporary(r.car, true);
        g.props().drop(r.u, WAIT);
        g.props().drop(r.u, GHOST);
        r.phase = READY;
        r.ticks = 0;
        r.v = 0;
        r.ghost = g.cars().spawn(p, GHOST, pose(r, w, r.s, -lane(), 0), g.answer(), true, 11, 2);
        if (!r.preview) g.driving(p);
        Location beside = pose(r, w, r.s, -lane(), 0);
        p.playSound(beside, cfg().getString("drive.start-sound", "stsv:vehicle.tuner_car.engine_start"), SoundCategory.AMBIENT, 0.5f, 0.8f);
        p.spawnParticle(Particle.SOUL, beside.clone().add(0, 0.6, 0), 14, 0.9, 0.3, 0.9, 0.01);
        g.later(p, 20, () -> g.say(p, "together", "<#9fb2c8>…같이 가자. 그날 못 간 데까지."));
    }

    /**
     * An admin's 유령: only the ghost, from the road beside the player (or straight ahead of them where there is no road
     * near) for this many seconds.
     */
    void demo(Player p, Places.Site site, int seconds) {
        UUID u = p.getUniqueId();
        end(u);
        Location me = p.getLocation();
        Run r = null;
        if (site != null && site.world().equals(me.getWorld().getName())) {
            double s = Roadway.project(site.pts(), me.getX(), me.getZ(), 0, site.pts().size() - 1);
            double[] at = Roadway.at(site.pts(), s);
            if (Math.hypot(at[0] - me.getX(), at[1] - me.getZ()) <= 48 && s < site.pts().size() - 30) {
                r = new Run(u, true, site.world(), site.pts(), site.halves(), site.bend(), site.outside(), site.pts().size() - 1);
                r.s = s;
            }
        }
        if (r == null) {   // no road here: a straight line the way they look, on their own level
            org.bukkit.util.Vector d = me.getDirection().setY(0);
            if (d.lengthSquared() < 1e-4) d = new org.bukkit.util.Vector(0, 0, 1);
            d.normalize();
            List<double[]> pts = new ArrayList<>();
            int n = 400;
            int[] halves = new int[n];
            for (int i = 0; i < n; i++) {
                pts.add(new double[]{me.getX() + d.getX() * (i + 3), me.getZ() + d.getZ() * (i + 3)});
                halves[i] = (int) Math.round((me.getY() - 1) * 2);
            }
            r = new Run(u, true, me.getWorld().getName(), pts, halves, 60, 1, n - 1);
            r.s = 0;
        }
        r.phase = DEMO;
        r.ticks = -Math.max(3, seconds) * 20;   // counts up to 0
        r.ghost = g.cars().spawn(p, GHOST, pose(r, p.getWorld(), r.s, -lane(), 0), g.answer(), true, 11, 2);
        add(r);
    }

    // ------------------------------------------------------------------------------------------
    // every tick
    // ------------------------------------------------------------------------------------------

    private void tick() {
        if (runs.isEmpty()) {
            if (task != null) task.cancel();
            task = null;
            return;
        }
        for (Run r : new ArrayList<>(runs.values())) {
            Player p = Bukkit.getPlayer(r.u);
            if (p == null) {
                end(r.u);
                continue;
            }
            try {
                step(r, p);
            } catch (RuntimeException e) {
                g.eco().getLogger().log(Level.WARNING, "[비밀] 폐차고 주행 오류", e);
                end(r.u);
            }
        }
    }

    private void step(Run r, Player p) {
        VehicleModule vm = g.vm();
        World w = Bukkit.getWorld(r.world);
        if (w == null || (r.phase != DEMO && r.phase != FADE && vm == null)) {
            end(r.u);
            return;
        }
        r.ticks++;
        switch (r.phase) {
            case WAITING -> waiting(r, p, vm);
            case READY -> {
                if (!vm.drivesTemporary(r.car, p)) {
                    fail(r, p);
                    return;
                }
                if (r.ticks >= 50) {
                    vm.freezeTemporary(r.car, false);
                    r.phase = GO;
                    r.ticks = 0;
                }
            }
            case GO -> go(r, p, vm, w);
            case HOME -> home(r, p, vm, w);
            case DEMO -> {
                Location ghost = advance(r, 0, w, p);
                // (never driven out of where the chunks are loaded around its viewer)
                boolean away = !p.getWorld().equals(w) || Math.hypot(ghost.getX() - p.getLocation().getX(), ghost.getZ() - p.getLocation().getZ()) > 64;
                if (r.ticks >= 0 || r.s >= r.end - 1 || away) {
                    g.cars().dark(r.u, r.ghost);
                    r.phase = FADE;
                    r.ticks = 0;
                }
            }
            case FADE -> {
                if (r.ticks >= 30) end(r.u);
            }
            default -> end(r.u);
        }
    }

    /** Nobody in it yet: it goes away quietly when they leave, the night ends, or they never get in. */
    private void waiting(Run r, Player p, VehicleModule vm) {
        if (vm.drivesTemporary(r.car, p)) {
            // at the wheel without having come through board (the core seats a car's own player again by itself): the same
            if (r.preview || g.mayGoOn(p)) ready(r, p);
            else end(r.u);
            return;
        }
        if (r.ticks % 20 != 0) return;
        boolean late = System.currentTimeMillis() - r.born > Math.max(20, cfg().getLong("drive.wait-seconds", 180)) * 1000L;
        boolean gone = late || !vm.temporaryAlive(r.car) || !p.getWorld().getName().equals(r.world)
                || p.getLocation().distanceSquared(r.stands) > 72 * 72 || !g.night(p) || !g.mayGoOn(p);
        if (late) rest.put(r.u, System.currentTimeMillis() + 60_000L);   // it does not come back the very next second
        if (gone) end(r.u);
        else if (r.ticks % 100 == 0) p.spawnParticle(Particle.SOUL, r.stands.clone().add(0, 0.8, 0), 3, 0.8, 0.3, 0.8, 0.005);
    }

    private void go(Run r, Player p, VehicleModule vm, World w) {
        VehicleModule.Driven d = vm.drivenBy(p);
        if (d == null || d.id() != r.car) {   // out of the car (got out, died, was taken elsewhere, the car fell out of the world)
            fail(r, p);
            return;
        }
        if (d.bumped() && !r.bumped && ++r.bumps > Math.max(0, cfg().getInt("pace.bumps-allowed", 0))) {
            fail(r, p);
            return;
        }
        r.bumped = d.bumped();
        r.along = Roadway.project(r.pts, d.x(), d.z(), (int) r.along - 30, (int) r.along + 50);
        double gap = r.along - r.s;
        Location ghost = advance(r, gap, w, p);
        double dist = Math.hypot(d.x() - ghost.getX(), d.z() - ghost.getZ());
        double near = cfg().getDouble("pace.near", 14);
        Roadway.Place place = Roadway.place(gap, dist, near, cfg().getDouble("pace.ahead", 18), cfg().getDouble("pace.behind", 22));
        r.out = place == Roadway.Place.OUT ? r.out + 1 : 0;
        r.far = place == Roadway.Place.BESIDE ? 0 : r.far + 1;
        if (r.out > cfg().getDouble("pace.grace-seconds", 4) * 20 || r.far > cfg().getDouble("pace.near-seconds", 10) * 20) {
            fail(r, p);
            return;
        }
        if (place != Roadway.Place.BESIDE && r.ticks - r.warned >= 50) {
            r.warned = r.ticks;
            if (gap > 0) g.whisper(p, "too-fast", "<#9fb2c8>…천천히. 옆에 있어 줘.");
            else g.whisper(p, "too-slow", "<#9fb2c8>…거기 있지? 옆으로 와.");
        }
        if (r.s >= r.end) {   // home, together
            r.phase = HOME;
            r.ticks = 0;
            r.from = ghost;
            Location o = g.places().garage();
            r.door = o == null ? null : Props.local(o, 0, 0.06, 2.5);
            r.inside = o == null ? null : Shed.carSpot(o);
            vm.freezeTemporary(r.car, true);
            if (!r.preview) g.home(p);
            g.shutter(p, 1.0, 16);
            g.say(p, "home-1", "<#9fb2c8>…다 왔네.");
        }
    }

    /** The ghost a tick further along the road (the player {@code gap} blocks ahead of it); returns where it is now. */
    private Location advance(Run r, double gap, World w, Player p) {
        YamlConfiguration c = cfg();
        int at = (int) Math.round(r.s);
        double turn = Math.max(Math.abs(Roadway.turn(r.pts, at + 8, 8)), Math.abs(Roadway.turn(r.pts, at + 3, 6)));
        double cruise = Roadway.cruise(turn, c.getDouble("pace.straight", 11), c.getDouble("pace.bend", 6.5), c.getDouble("pace.sharp", 40));
        cruise *= 1 + 0.07 * Math.sin(r.ticks / 53.0) + 0.04 * Math.sin(r.ticks / 17.0);   // a person's foot is never still
        double min = Math.max(0.5, c.getDouble("pace.min", 3));
        double target = Roadway.adapt(cruise, gap, min);
        if (r.phase == GO) target = Math.min(target, Math.max(min, (r.end - r.s) / 3 + 2.5));   // easing up to his door
        r.v = Roadway.step(r.v, target, c.getDouble("pace.accel", 3), c.getDouble("pace.brake", 5), 0.05);
        r.s = Math.min(r.end, r.s + r.v * 0.05);
        double lateral = -lane();
        float skid = 0;
        if (r.phase == GO && r.flick < 0 && r.s >= r.bend) {
            r.flick = 0;
            g.say(p, "bend", "<#9fb2c8>…여기야. 여기서 못 돌았어.");
            p.playSound(pose(r, w, r.s, lateral, 0), c.getString("drive.skid-sound", "stsv:vehicle.sports_car.skid"), SoundCategory.AMBIENT, 0.7f, 0.9f);
        }
        if (r.flick >= 0 && r.flick < 24) {   // for a second it goes where it went that night, and comes back
            double k = Math.sin(Math.PI * r.flick / 24.0);
            lateral += r.outside * 2.8 * k;
            skid = (float) (r.outside * 24 * k);
            g.cars().flicker(r.ghost, (r.flick / 2) % 3 != 1);
            r.flick++;
            if (r.flick == 24) g.cars().flicker(r.ghost, true);
        }
        Location l = pose(r, w, r.s, lateral, skid);
        if (r.ghost != null) {
            g.cars().move(r.ghost, l);
            r.roll += r.v * 0.05 / 0.375;
            if (r.ticks % 2 == 0) g.cars().roll(r.ghost, r.roll);
        }
        if (r.ticks % 3 == 0) {
            double[] t = Roadway.tangent(r.pts, r.s, 3);
            p.spawnParticle(Particle.SOUL, l.getX() - t[0] * 2.4, l.getY() + 0.35, l.getZ() - t[1] * 2.4, 1, 0.3, 0.08, 0.3, 0.005);
        }
        return l;
    }

    /** He rolls into his garage, his lights go dark, the shutter comes down by itself. */
    private void home(Run r, Player p, VehicleModule vm, World w) {
        int t = r.ticks;
        if (r.ghost != null && r.inside != null && t <= 80) {
            double k = t / 80.0;
            k = k * k * (3 - 2 * k);
            Location a = k < 0.5 ? r.from : r.door, b = k < 0.5 ? r.door : r.inside;
            double f = k < 0.5 ? k * 2 : (k - 0.5) * 2;
            Location l = new Location(w, a.getX() + (b.getX() - a.getX()) * f, a.getY() + (b.getY() - a.getY()) * f, a.getZ() + (b.getZ() - a.getZ()) * f);
            l.setYaw(Roadway.yaw(b.getX() - a.getX(), b.getZ() - a.getZ()));
            g.cars().move(r.ghost, l);
            r.roll += 0.12;
            if (t % 2 == 0) g.cars().roll(r.ghost, r.roll);
        }
        if (t == 86 && r.ghost != null) {
            g.cars().dark(r.u, r.ghost);
            p.playSound(r.inside == null ? p.getLocation() : r.inside, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.AMBIENT, 0.35f, 0.6f);
        }
        if (t == 100) g.say(p, "home-2", "<#9fb2c8>…그 차, 이제 네가 타.");
        if (t == 120) g.shutter(p, 0, 34);
        if (t == 160) {
            g.props().drop(r.u, GHOST);
            r.ghost = null;
            g.say(p, "home-3", "<#9fb2c8>…천천히 다녀.");
        }
        if (t >= 200) {
            boolean preview = r.preview;
            end(r.u);
            g.arrived(p, preview);
        }
    }

    /** A rule was broken: his lights go out, the car is taken away, the player stands at the roadside. */
    private void fail(Run r, Player p) {
        VehicleModule vm = g.vm();
        VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
        Location safe = p.isOnline() && !p.isDead() && p.getWorld().getName().equals(r.world)
                ? roadside(r, d != null && d.id() == r.car ? d.x() : p.getLocation().getX(), d != null && d.id() == r.car ? d.z() : p.getLocation().getZ()) : null;
        boolean seated = d != null && d.id() == r.car;
        removeCar(r);
        if (r.ghost != null) g.cars().dark(r.u, r.ghost);
        r.phase = FADE;
        r.ticks = 0;
        if (seated && safe != null) {
            Bukkit.getScheduler().runTaskLater(g.eco(), () -> {   // after the core has stepped them out beside the car
                if (p.isOnline() && !p.isDead() && !p.isInsideVehicle() && p.getWorld().equals(safe.getWorld())) {
                    safe.setYaw(p.getLocation().getYaw());
                    safe.setPitch(p.getLocation().getPitch());
                    p.teleport(safe);
                }
            }, 3L);
        }
        g.failed(p, r.preview);
    }

    /**
     * Solid ground beside the road near (x, z): the roadside on either hand, else the road itself. Null when nothing there
     * can be read.
     */
    Location roadside(Run r, double x, double z) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return null;
        double s = Roadway.project(r.pts, x, z, (int) r.along - 80, (int) r.along + 80);
        int roadY = Roadway.surfaceY(r.halves, (int) Math.round(s));
        for (double lateral : new double[]{4.5, -4.5, 0}) {
            double[] at = Roadway.beside(r.pts, s, lateral, 3);
            int y = Places.ground(w, at[0], at[1], roadY, 3, 4);
            if (y != Places.UNKNOWN && y != Places.NONE) return new Location(w, at[0], y + 0.05, at[1]);
        }
        return null;
    }

    private void removeCar(Run r) {
        VehicleModule vm = g.vm();
        if (r.car != 0 && vm != null) vm.removeTemporary(r.car);
        r.car = 0;
    }

    /** Whatever this player's run was, it is over and nothing of it is left (no line, no penalty). */
    void end(UUID u) {
        Run r = runs.remove(u);
        if (r == null) return;
        removeCar(r);
        g.props().drop(u, GHOST);
        g.props().drop(u, WAIT);
        g.release(u);
    }

    void endAll() {
        for (UUID u : new ArrayList<>(runs.keySet())) end(u);
    }
}
