package dev.stsv.economy.secret.letter;

import dev.stsv.economy.secret.Haunt;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The last night (letter.yml {@code chase}). Someone knocks — on the nearest door, or somewhere behind the walls. Open
 * the door and they stand outside it; let the knocking run out and, after a silence, they stand inside, behind you. It
 * is you: it looks at you for a moment, then walks exactly where you walked a second ago, and the second shrinks (1.0 s
 * to 0.4 s). Stop, double back, or let it catch up and it touches you: the screen goes black and chat says you left.
 * Keep going for three minutes and it stops, says "그럼 네가 계속 해", and is gone. Whatever the hour, it is night for
 * them while it lasts (their own sky only).
 * <p>
 * Everything runs on a one-tick timer per player and is seen and heard by that player only ({@link Haunt}).
 */
final class Finale {

    /** FULL: the real night. CHASE: an admin look at the chase (no knocking, no consequences). KNOCK: the knocking only. */
    enum Mode { FULL, CHASE, KNOCK }

    private enum Phase { KNOCK, SILENCE, STARE, CHASE, END }

    private final Letters s;
    private final SecretModule m;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    Finale(Letters s, SecretModule m) {
        this.s = s;
        this.m = m;
    }

    private Haunt haunt() {
        return m.haunt();
    }

    private final class Run {
        final Player p;
        final Mode mode;
        final Trail trail = new Trail(80);
        Phase phase;
        int t;
        BukkitTask task;
        /** where the knocking comes from */
        Location knock;
        Block door;
        int round, knocksLeft, nextKnock;
        Mannequin twin;
        TextDisplay words;
        Location from;
        int total, chase, nextBreath, nextBeat, nextStep, lastSwing = -10;
        Location lastStepAt;
        boolean swing;

        Run(Player p, Mode mode) {
            this.p = p;
            this.mode = mode;
        }
    }

    boolean running(UUID u) {
        return runs.containsKey(u);
    }

    /** Whether it is out and following them (vehicles and gliding are refused then). */
    boolean chasing(UUID u) {
        Run r = runs.get(u);
        return r != null && (r.phase == Phase.STARE || r.phase == Phase.CHASE);
    }

    void begin(Player p, Mode mode) {
        stop(p.getUniqueId());
        Run r = new Run(p, mode);
        int sec = mode == Mode.CHASE ? s.cfg().getInt("chase.scene-seconds", 40)
                : m.test(p.getUniqueId()) ? s.cfg().getInt("chase.test-seconds", 60) : s.cfg().getInt("chase.seconds", 180);
        r.total = Math.max(5, sec) * 20;
        runs.put(p.getUniqueId(), r);
        // whatever the hour, for them it is night ("밤이었다")
        p.setPlayerTime(18000, false);
        if (mode == Mode.CHASE) appearInside(r);
        else {
            r.door = door(p);
            if (r.door != null) r.knock = r.door.getLocation().add(0.5, 1.0, 0.5);
            else {
                double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                r.knock = p.getLocation().add(Math.cos(a) * 5, 1.2, Math.sin(a) * 5);
            }
            r.phase = Phase.KNOCK;
            r.round = 0;
            r.knocksLeft = 3;
            r.nextKnock = 30;
        }
        r.task = Bukkit.getScheduler().runTaskTimer(s.plugin(), () -> step(r), 1L, 1L);
    }

    /** The nearest door within door-radius (its lower half), or null. */
    private Block door(Player p) {
        int rad = Math.max(2, s.cfg().getInt("chase.door-radius", 10));
        Location c = p.getLocation();
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) for (int y = -3; y <= 3; y++) {
            Block b = c.getWorld().getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
            if (!Tag.DOORS.isTagged(b.getType())) continue;
            if (b.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP) b = b.getRelative(BlockFace.DOWN);
            double d = b.getLocation().add(0.5, 0, 0.5).distanceSquared(c);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** They opened a door: if it is the one being knocked on, it is standing on the other side. */
    void doorOpened(Player p, Block clicked) {
        Run r = runs.get(p.getUniqueId());
        if (r == null || (r.phase != Phase.KNOCK && r.phase != Phase.SILENCE) || r.door == null) return;
        Block b = clicked.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP ? clicked.getRelative(BlockFace.DOWN) : clicked;
        if (!b.getWorld().equals(r.door.getWorld()) || b.getLocation().distanceSquared(r.door.getLocation()) > 4) return;
        if (r.mode == Mode.KNOCK) {
            stop(p.getUniqueId());
            return;
        }
        // one beat after the door swings open
        Location center = r.door.getLocation().add(0.5, 0, 0.5);
        Vector in = p.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (in.lengthSquared() < 1e-4) in = p.getLocation().getDirection().setY(0);
        if (in.lengthSquared() < 1e-4) in = new Vector(1, 0, 0);
        Location out = center.clone().subtract(in.normalize().multiply(1.3));
        Location ground = Omens.ground(out.getWorld(), out.getX(), center.getBlockY(), out.getZ());
        appear(r, ground != null ? ground : out);
    }

    void swing(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r != null) r.swing = true;
    }

    private void appearInside(Run r) {
        Location at = r.p.getLocation();
        Vector back = at.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(0, 0, 1);
        Location spot = at.clone().subtract(back.normalize().multiply(3));
        Location ground = Omens.ground(at.getWorld(), spot.getX(), at.getBlockY(), spot.getZ());
        appear(r, ground != null ? ground : spot);
    }

    private void appear(Run r, Location at) {
        face(at, r.p.getEyeLocation());
        r.twin = haunt().likeness(r.p, r.p, at, null);
        r.twin.setBodyYaw(at.getYaw());
        r.phase = Phase.STARE;
        r.t = 0;
        haunt().sound(r.p, r.p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 0.9f);
    }

    private static void face(Location at, Location target) {
        Vector v = target.toVector().subtract(at.clone().add(0, 1.62, 0).toVector());
        if (v.lengthSquared() > 1e-4) at.setDirection(v);
    }

    private void step(Run r) {
        Player p = r.p;
        if (!p.isOnline() || p.isDead()) {
            stop(p.getUniqueId());
            return;
        }
        Location l = p.getLocation();
        r.trail.add(new Trail.Step(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), p.isSneaking(), r.swing));
        r.swing = false;
        r.t++;
        switch (r.phase) {
            case KNOCK -> knock(r);
            case SILENCE -> {
                if (r.t >= 80) {
                    if (r.mode == Mode.KNOCK) stop(p.getUniqueId());
                    else appearInside(r);
                }
            }
            case STARE -> stare(r);
            case CHASE -> chase(r);
            case END -> end(r);
        }
    }

    private void knock(Run r) {
        if (r.t < r.nextKnock) return;
        int rounds = Math.max(1, s.cfg().getInt("chase.knock-rounds", 6));
        boolean last = r.round >= rounds - 1;
        float vol = (float) Math.min(1.0, 0.3 + r.round * 0.12);
        boolean heavy = last && r.knocksLeft == 1;
        haunt().sound(r.p, r.knock, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, heavy ? 1.0f : vol * 0.6f,
                heavy ? 0.7f : 1.55f + ThreadLocalRandom.current().nextFloat() * 0.12f);
        r.knocksLeft--;
        if (r.knocksLeft > 0) {
            r.nextKnock = r.t + (r.round >= rounds - 2 ? 5 : 7);
            return;
        }
        r.round++;
        if (r.round >= rounds) {
            r.phase = Phase.SILENCE;
            r.t = 0;
            return;
        }
        r.knocksLeft = r.round >= rounds - 2 ? 5 : 3;
        r.nextKnock = r.t + Math.max(50, 110 - r.round * 10);
    }

    private void stare(Run r) {
        Location at = r.twin.getLocation();
        face(at, r.p.getEyeLocation());
        r.twin.teleport(at);
        r.twin.setBodyYaw(at.getYaw());
        int stare = Math.max(1, s.cfg().getInt("chase.stare-seconds", 2)) * 20;
        if (r.t % 20 == 0) haunt().sound(r.p, r.p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 1.0f);
        if (r.t >= stare) {
            r.phase = Phase.CHASE;
            r.t = 0;
            r.chase = 0;
            r.from = at.clone();
            r.lastStepAt = at.clone();
        }
    }

    private void chase(Run r) {
        Player p = r.p;
        r.chase++;
        if (p.isInsideVehicle()) p.leaveVehicle();
        if (p.isGliding()) p.setGliding(false);
        double prog = Math.min(1.0, r.chase / (double) r.total);
        double d0 = s.cfg().getDouble("chase.delay-start-ticks", 20), d1 = s.cfg().getDouble("chase.delay-end-ticks", 8);
        double delay = d0 + (d1 - d0) * Math.pow(prog, 1.15);
        Trail.Step st = r.trail.at(delay);
        World w = Bukkit.getWorld(st.world());
        if (w == null) return;
        Location to = new Location(w, st.x(), st.y(), st.z(), st.yaw(), st.pitch());
        if (r.chase <= 20 && r.from.getWorld() == w) {
            // it steps from where it stood onto your trail
            double f = r.chase / 20.0;
            to.setX(Trail.lerp(r.from.getX(), to.getX(), f));
            to.setY(Trail.lerp(r.from.getY(), to.getY(), f));
            to.setZ(Trail.lerp(r.from.getZ(), to.getZ(), f));
        }
        if (!r.twin.isValid() || r.twin.getWorld() != w) {
            haunt().remove(p, r.twin);
            r.twin = haunt().likeness(p, p, to, null);
        } else r.twin.teleport(to);
        r.twin.setRotation(to.getYaw(), to.getPitch());
        r.twin.setBodyYaw(to.getYaw());
        try {
            r.twin.setPose(st.sneak() ? Pose.SNEAKING : Pose.STANDING, true);
        } catch (RuntimeException ignored) {
            // a pose the mannequin refuses
        }
        Trail.Step exact = r.trail.back((int) Math.round(delay));
        if (exact != null && exact.swing() && r.chase - r.lastSwing > 3) {
            r.twin.swingMainHand();
            r.lastSwing = r.chase;
        }
        double dist = p.getWorld() == w ? to.distance(p.getLocation()) : 99;
        if (r.chase > s.cfg().getInt("chase.grace-ticks", 20) && dist < s.cfg().getDouble("chase.catch-distance", 0.8)) {
            caught(r);
            return;
        }
        sounds(r, to, dist);
        if (r.chase >= r.total) {
            r.phase = Phase.END;
            r.t = 0;
        }
    }

    /** Its breathing (louder and quicker the closer it is), its steps, and your heart when it is very close. */
    private void sounds(Run r, Location at, double dist) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (r.chase >= r.nextBreath) {
            float vol = (float) Math.max(0.12, Math.min(1.3, 1.3 - dist / 9));
            haunt().sound(r.p, at.clone().add(0, 1.6, 0), Sound.ENTITY_HORSE_BREATHE, vol, 0.5f + rnd.nextFloat() * 0.08f);
            if (dist < 4) haunt().sound(r.p, at.clone().add(0, 1.6, 0), Sound.ENTITY_PLAYER_BREATH, vol * 0.8f, 0.6f);
            r.nextBreath = r.chase + (int) Math.max(10, Math.min(50, 8 + dist * 5));
        }
        if (dist < 3.5 && r.chase >= r.nextBeat) {
            haunt().sound(r.p, r.p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 1.0f);
            r.nextBeat = r.chase + 14;
        }
        if (r.chase >= r.nextStep) {
            r.nextStep = r.chase + 6;
            if (r.lastStepAt.getWorld() == at.getWorld() && r.lastStepAt.distanceSquared(at) > 0.04) {
                Block under = at.getBlock().getRelative(BlockFace.DOWN);
                if (!under.getType().isAir()) haunt().sound(r.p, at, under.getBlockData().getSoundGroup().getStepSound(), 0.45f, 0.9f);
            }
            r.lastStepAt = at.clone();
        }
    }

    private void caught(Run r) {
        Player p = r.p;
        UUID u = p.getUniqueId();
        boolean real = r.mode == Mode.FULL;
        stop(u);
        haunt().black(p, 4);
        haunt().sound(p, p.getLocation(), Sound.ENTITY_PLAYER_BREATH, 1.2f, 0.5f);
        Bukkit.getScheduler().runTaskLater(s.plugin(), () -> {
            if (p.isOnline()) haunt().leaveLine(p, p.getName(), false);
        }, 12L);
        Bukkit.getScheduler().runTaskLater(s.plugin(), () -> {
            if (p.isOnline()) haunt().whisper(p, s.cfg().getString("chase.caught-whisper", "<dark_gray>…"));
        }, 100L);
        if (real) s.caught(p);
    }

    /**
     * It made it: it stops, turns, and talks — first that the place did not come free (it rewound all the way back to find
     * the moment they began, and there is room for one), then "그럼 네가 계속 해" — and is gone.
     */
    private void end(Run r) {
        Player p = r.p;
        String before = s.cfg().getString("chase.before-words", "");
        // with a first line it waits a little longer before the last words
        int say = before.isEmpty() ? 30 : 60;
        if (r.t <= say) {
            if (r.twin.isValid()) {
                Location at = r.twin.getLocation();
                face(at, p.getEyeLocation());
                r.twin.teleport(at);
                r.twin.setBodyYaw(at.getYaw());
                r.twin.setPose(Pose.STANDING, false);
            }
        }
        String words = s.cfg().getString("chase.last-words", "그럼 네가 계속 해");
        if (r.t == 20 && !before.isEmpty()) {
            r.words = haunt().text(p, r.twin.getLocation().add(0, 2.25, 0), "<dark_gray>" + before);
            haunt().chat(p, p.getName(), before);
        }
        if (r.t == say) {
            if (r.words != null) haunt().remove(p, r.words);
            r.words = haunt().text(p, r.twin.getLocation().add(0, 2.25, 0), "<gray>" + words);
            haunt().chat(p, p.getName(), words);
        }
        if (r.t == say + 60) {
            Location at = r.twin.getLocation().add(0, 1, 0);
            p.spawnParticle(Particle.SMOKE, at, 30, 0.25, 0.6, 0.25, 0.01);
            haunt().sound(p, at, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 0.8f);
            haunt().remove(p, r.twin);
            if (r.words != null) haunt().remove(p, r.words);
        }
        if (r.t >= say + 110) {
            boolean real = r.mode == Mode.FULL;
            stop(p.getUniqueId());
            if (real) s.survived(p);
        }
    }

    /** Stops whatever runs for them. True when it was the real night with it already out (that counts as being caught). */
    boolean stop(UUID u) {
        Run r = runs.remove(u);
        if (r == null) return false;
        if (r.task != null) r.task.cancel();
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.resetPlayerTime();
        if (r.twin != null) {
            if (p != null) haunt().remove(p, r.twin);
            else if (r.twin.isValid()) r.twin.remove();
        }
        if (r.words != null) {
            if (p != null) haunt().remove(p, r.words);
            else if (r.words.isValid()) r.words.remove();
        }
        return r.mode == Mode.FULL && (r.phase == Phase.STARE || r.phase == Phase.CHASE);
    }

    void stopAll() {
        for (UUID u : runs.keySet()) stop(u);
    }
}
