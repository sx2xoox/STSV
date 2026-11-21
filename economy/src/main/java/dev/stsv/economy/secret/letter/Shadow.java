package dev.stsv.economy.secret.letter;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 그림자 분신, the solver's lasting reward (letter.yml {@code shadow}): every few minutes, while they are on the move,
 * the other players near them see them once more for a few seconds — standing where they stood one second earlier,
 * flickering in and out at the start and the end like something not quite there. The solver never sees it themselves
 * (unless an admin shows it to them). A mannequin can not be drawn half see-through (and an invisible one still shows
 * its armour), so the "translucency" is that flicker: the figure is shown and hidden per viewer, tick by tick.
 */
final class Shadow {

    private static final int DELAY = 20;
    private static final int FADE = 16;

    private final Letters s;
    private final Map<UUID, Long> next = new ConcurrentHashMap<>();
    private final Map<UUID, Show> shows = new ConcurrentHashMap<>();
    private final Map<UUID, Location> last = new ConcurrentHashMap<>();

    Shadow(Letters s) {
        this.s = s;
    }

    private final class Show {
        final Player of;
        final Trail trail = new Trail(DELAY + 8);
        final Set<UUID> viewers = new HashSet<>();
        /** the player shown to (an admin's preview), or null for everyone near */
        final Player only;
        Mannequin twin;
        boolean on;
        BukkitTask task;
        int t, life;

        Show(Player of, Player only, int life) {
            this.of = of;
            this.only = only;
            this.life = life;
        }
    }

    boolean enabled() {
        return s.cfg().getBoolean("shadow.enabled", true);
    }

    /** Each second for a solver: maybe it is time. */
    void tick(Player p) {
        if (!enabled() || shows.containsKey(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        long at = next.computeIfAbsent(p.getUniqueId(), k -> now + wait(p));
        if (now < at || p.isInsideVehicle() || p.isFlying() || p.isGliding() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        Location was = last.put(p.getUniqueId(), p.getLocation());
        // standing still it would only hide inside them: it comes while they walk
        if (was == null || was.getWorld() != p.getWorld() || was.distanceSquared(p.getLocation()) < 1.5 * 1.5) return;
        if (watchers(p).isEmpty()) return;
        next.put(p.getUniqueId(), now + wait(p));
        play(p, null);
    }

    private long wait(Player p) {
        return LetterText.between(s.cfg().getIntegerList("shadow.every"), 300, ThreadLocalRandom.current()) * 1000L;
    }

    private Set<Player> watchers(Player of) {
        double r = s.cfg().getDouble("shadow.radius", 32);
        Set<Player> out = new HashSet<>();
        for (Player o : of.getWorld().getPlayers()) {
            if (o == of || !o.canSee(of)) continue;
            if (o.getLocation().distanceSquared(of.getLocation()) <= r * r) out.add(o);
        }
        return out;
    }

    /** Plays the shadow of {@code of} — to everyone near, or to {@code only} (an admin's look, which may be themselves). */
    void play(Player of, Player only) {
        stop(of.getUniqueId());
        int life = Math.max(2, s.cfg().getInt("shadow.seconds", 6)) * 20;
        Show sh = new Show(of, only, life);
        shows.put(of.getUniqueId(), sh);
        sh.task = Bukkit.getScheduler().runTaskTimer(s.plugin(), () -> step(sh), 1L, 1L);
    }

    private void step(Show sh) {
        Player of = sh.of;
        if (!of.isOnline()) {
            stop(of.getUniqueId());
            return;
        }
        Location l = of.getLocation();
        sh.trail.add(new Trail.Step(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), of.isSneaking(), false));
        sh.t++;
        if (sh.t < DELAY) return;   // a second of memory first
        Trail.Step st = sh.trail.at(DELAY);
        World w = Bukkit.getWorld(st.world());
        if (w == null || w != of.getWorld()) {
            stop(of.getUniqueId());
            return;
        }
        Location at = new Location(w, st.x(), st.y(), st.z(), st.yaw(), st.pitch());
        if (sh.twin == null) sh.twin = spawn(of, at);
        else sh.twin.teleport(at);
        sh.twin.setRotation(at.getYaw(), at.getPitch());
        sh.twin.setBodyYaw(at.getYaw());
        int age = sh.t - DELAY;
        // the flicker: more and more there while it fades in, less and less while it fades out
        int edge = Math.min(age, sh.life - age);
        boolean shown = edge >= FADE || (edge % 4) < 1 + edge * 3 / FADE;
        if (shown != sh.on || age % 10 == 0) {
            sh.on = shown;
            viewers(sh);
        }
        if (age >= sh.life) stop(of.getUniqueId());
    }

    private void viewers(Show sh) {
        Set<UUID> want = new HashSet<>();
        if (sh.only != null) {
            if (sh.only.isOnline()) want.add(sh.only.getUniqueId());
        } else for (Player o : watchers(sh.of)) want.add(o.getUniqueId());
        if (!sh.on) want.clear();
        for (UUID u : want) if (sh.viewers.add(u)) {
            Player o = Bukkit.getPlayer(u);
            if (o != null) o.showEntity(s.plugin(), sh.twin);
        }
        for (UUID u : new HashSet<>(sh.viewers)) if (!want.contains(u)) {
            sh.viewers.remove(u);
            Player o = Bukkit.getPlayer(u);
            if (o != null) o.hideEntity(s.plugin(), sh.twin);
        }
    }

    private Mannequin spawn(Player of, Location at) {
        return at.getWorld().spawn(at, Mannequin.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setImmovable(true);
            e.setCollidable(false);
            e.setRemoveWhenFarAway(false);
            e.setCustomNameVisible(false);
            e.setDescription(null);
            e.setProfile(ResolvableProfile.resolvableProfile(of.getPlayerProfile()));
            var eq = of.getEquipment();
            var me = e.getEquipment();
            me.setHelmet(eq.getHelmet());
            me.setChestplate(eq.getChestplate());
            me.setLeggings(eq.getLeggings());
            me.setBoots(eq.getBoots());
            me.setItemInMainHand(eq.getItemInMainHand());
            me.setItemInOffHand(eq.getItemInOffHand());
        });
    }

    void stop(UUID of) {
        Show sh = shows.remove(of);
        if (sh == null) return;
        if (sh.task != null) sh.task.cancel();
        if (sh.twin != null && sh.twin.isValid()) sh.twin.remove();
    }

    void cleanup(UUID u) {
        stop(u);
        next.remove(u);
        last.remove(u);
        // someone else's shadow that this player was watching goes on for the others
    }

    void stopAll() {
        for (UUID u : shows.keySet()) stop(u);
    }
}
