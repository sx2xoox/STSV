package dev.stsv.economy.secret.doll;

import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The reward: a small "낡은 인형" floating at the solver's right shoulder (an item display of economy:old_doll, seen by
 * everyone). Now and then it turns its head toward another player nearby and holds the look for a few seconds. Only one
 * exists (eco.kv "secret.doll.companion"); its owner puts it to sleep or wakes it ("secret.doll.companion-off").
 * The entity is never saved: it is made again when its owner is online.
 */
final class Companion {

    private final Doll d;
    private ItemDisplay e;
    private BukkitTask task;
    /** Whose shoulder it sits on now. */
    private UUID on;
    /** An admin preview on someone else's shoulder until then (ms). */
    private long previewUntil;
    private UUID preview;
    private Player lookAt;
    private long lookUntil;
    private int t;
    /** The step ({@link #t}) at which it began to bow (the 후속 임무: a small nod toward someone), far in the past when not. */
    private int bowFrom = -1000;
    /** How many steps (two ticks each) a bow takes: down and up again. */
    private static final int BOW_STEPS = 24;

    Companion(Doll d) {
        this.d = d;
    }

    UUID owner() {
        String v = d.kvGet("companion");
        try {
            return v == null ? null : UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    boolean awake() {
        return d.kvGet("companion-off") == null;
    }

    /** Toggles it for its owner; returns whether it is now awake. */
    boolean toggle() {
        if (awake()) d.kvSet("companion-off", 1);
        else d.kvRemove("companion-off");
        return awake();
    }

    /** Each second: on the shoulder it belongs on (owner online and awake, or an admin preview), or gone. */
    void tick() {
        long now = System.currentTimeMillis();
        Player want = null;
        if (preview != null && now < previewUntil) want = Bukkit.getPlayer(preview);
        else preview = null;
        if (want == null) {
            UUID o = owner();
            Player p = o == null ? null : Bukkit.getPlayer(o);
            if (p != null && awake() && !p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) want = p;
        }
        if (want == null) {
            remove();
            return;
        }
        if (e != null && e.isValid() && want.getUniqueId().equals(on) && e.getWorld().equals(want.getWorld())) return;
        remove();
        spawn(want);
    }

    /** Shows it on this player's shoulder for a while (admin 연출 동반자). */
    void preview(Player p, int seconds) {
        preview = p.getUniqueId();
        previewUntil = System.currentTimeMillis() + seconds * 1000L;
        remove();
        spawn(p);
    }

    /** Whether it sits on this player's shoulder right now. */
    boolean on(UUID u) {
        return showing() && u.equals(on);
    }

    /**
     * Keeps it on this player's shoulder a little longer without making it again when it is already there (the 후속 임무
     * of a tester who is not its owner).
     */
    void hold(Player p, int seconds) {
        preview = p.getUniqueId();
        previewUntil = System.currentTimeMillis() + seconds * 1000L;
        if (on(p.getUniqueId()) && e.getWorld().equals(p.getWorld())) return;
        remove();
        spawn(p);
    }

    /** Turns its head to this player and holds the look for so long (the 후속 임무: the one it wanted to see). */
    void look(Player at, long ms) {
        if (!showing() || at == null) return;
        lookAt = at;
        lookUntil = System.currentTimeMillis() + ms;
    }

    /** A small bow: its head tilts forward and comes up again (about 2.4 seconds), toward whoever it is looking at. */
    void bow() {
        if (showing()) bowFrom = t;
    }

    /** How far forward (degrees) it leans this many steps into a bow of `steps` steps at most `max` deep: 0 outside it. */
    static float bowPitch(int into, int steps, float max) {
        if (into < 0 || into >= steps || steps <= 0) return 0;
        return (float) (max * Math.sin(Math.PI * into / steps));
    }

    private void spawn(Player p) {
        ItemStack it = Items.create(Doll.ITEM, 1).orElse(null);
        if (it == null) return;
        float s = (float) d.cfg().getDouble("companion.scale", 0.4);
        on = p.getUniqueId();
        e = p.getWorld().spawn(spot(p), ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setItemStack(it);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setBillboard(Display.Billboard.FIXED);
            x.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            x.setTeleportDuration(3);
            x.setShadowRadius(0);
            x.setViewRange(0.6f);
        });
        task = Bukkit.getScheduler().runTaskTimer(d.plugin(), this::follow, 1L, 2L);
    }

    /** Where it floats: beside the right shoulder, bobbing a little. */
    private Location spot(Player p) {
        Location l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        Vector right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw)).multiply(0.55);
        double bob = Math.sin(t / 9.0) * 0.035;
        Location at = l.clone().add(right).add(0, (p.isSneaking() ? 1.15 : 1.45) + bob, 0);
        at.setYaw(l.getYaw() + (d.cfg().getBoolean("companion.flip", false) ? 180 : 0));
        at.setPitch(0);
        return at;
    }

    private void follow() {
        Player p = on == null ? null : Bukkit.getPlayer(on);
        if (p == null || e == null || !e.isValid() || !e.getWorld().equals(p.getWorld())) {
            remove();
            return;
        }
        t++;
        Location at = spot(p);
        long now = System.currentTimeMillis();
        if (lookAt != null && (now > lookUntil || !lookAt.isOnline() || !lookAt.getWorld().equals(p.getWorld()))) lookAt = null;
        if (lookAt == null && t % 10 == 0 && ThreadLocalRandom.current().nextDouble() < d.cfg().getDouble("companion.look-chance", 0.06)) {
            double range = d.cfg().getDouble("companion.look-range", 10);
            List<Player> near = new ArrayList<>();
            for (Player o : p.getWorld().getPlayers()) {
                if (o != p && o.getGameMode() != org.bukkit.GameMode.SPECTATOR && o.getLocation().distanceSquared(p.getLocation()) <= range * range) near.add(o);
            }
            if (!near.isEmpty()) {
                lookAt = near.get(ThreadLocalRandom.current().nextInt(near.size()));
                lookUntil = now + 2500 + ThreadLocalRandom.current().nextInt(2500);
                p.playSound(at, Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, SoundCategory.AMBIENT, 0.25f, 0.55f);
            }
        }
        if (lookAt != null) {
            Vector to = lookAt.getEyeLocation().toVector().subtract(at.toVector());
            if (to.lengthSquared() > 0.01) {
                Location face = at.clone();
                face.setDirection(to);
                at.setYaw(face.getYaw() + (d.cfg().getBoolean("companion.flip", false) ? 180 : 0));
            }
        }
        float bow = bowPitch(t - bowFrom, BOW_STEPS, (float) d.cfg().getDouble("sequel.bow-degrees", 32));
        if (bow != 0) at.setPitch(d.cfg().getBoolean("companion.flip", false) ? -bow : bow);
        e.teleport(at);
    }

    /** Takes it away (it comes back on the next tick if it belongs somewhere). */
    void remove() {
        if (task != null) task.cancel();
        task = null;
        if (e != null && e.isValid()) e.remove();
        e = null;
        on = null;
        lookAt = null;
        bowFrom = -1000;
    }

    /** The player left or the secret stops. */
    void cleanup(UUID u) {
        if (u.equals(on) || u.equals(preview)) {
            if (u.equals(preview)) preview = null;
            remove();
        }
    }

    boolean showing() {
        return e != null && e.isValid();
    }
}
