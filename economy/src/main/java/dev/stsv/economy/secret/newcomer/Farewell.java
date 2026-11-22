package dev.stsv.economy.secret.newcomer;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;

/**
 * The ending, for the one who whispered at his stone: his answers come one at a time as whispers, dust falls off the bare
 * stone, the world is quiet, and for a while someone stands past the stone at the edge of sight (gone when looked at).
 * Nobody else sees or hears any of it. After the last line {@link Newcomer#done} says he left and hands the stone on.
 * Nothing is saved while it plays: quitting, dying, changing world or walking away from the stone (ending.leave-range)
 * in the middle stops it ({@link Newcomer#broken}) and leaves everything as it was (whisper there again).
 */
final class Farewell implements Runnable {

    private static final int STEP = 2;

    final boolean preview;
    private final Newcomer n;
    private final UUID id;
    /** his stone's foot (a spot in front of the player when there is no monument: an admin's preview), the monument's own spot */
    private final Location stone, centre;
    private final int lead, gap, figureLine;
    private final double look, leave;
    /** who he speaks to, as the lines name them ({player}) */
    private final String hearer;
    private final boolean quiet, dust, withFigure;
    private BlockData grit;
    private BukkitTask task;
    private Mannequin figure;
    private int t, said;

    Farewell(Newcomer n, Player p, Location stone, Location centre, boolean preview) {
        this.n = n;
        this.id = p.getUniqueId();
        this.preview = preview;
        Location l = p.getLocation();
        this.stone = stone != null ? stone : l.clone().add(flat(l.getDirection(), l.getYaw()).multiply(3));
        this.centre = centre;
        var c = n.cfg();
        lead = 20 * Math.max(0, c.getInt("ending.lead-seconds", 3));
        gap = 20 * Math.max(1, c.getInt("ending.gap-seconds", 5));
        figureLine = c.getInt("ending.figure-line", 3) - 1;
        look = Math.toRadians(Math.max(1, c.getDouble("ending.figure-look-degrees", 15)));
        leave = Math.max(4, c.getDouble("ending.leave-range", 12));
        String plain;
        try {
            plain = dev.stsv.core.nickname.Names.plain(id);
        } catch (RuntimeException e) {
            plain = p.getName();
        }
        hearer = MiniMessage.miniMessage().escapeTags(plain);
        quiet = c.getBoolean("ending.quiet", true);
        dust = c.getBoolean("ending.dust", true);
        withFigure = c.getBoolean("ending.figure", true);
    }

    /** A level direction (straight up or down: the way the body faces). */
    private static Vector flat(Vector dir, float yaw) {
        Vector v = dir.clone().setY(0);
        if (v.lengthSquared() < 1e-6) {
            double rad = Math.toRadians(yaw);
            v = new Vector(-Math.sin(rad), 0, Math.cos(rad));
        }
        return v.normalize();
    }

    UUID who() {
        return id;
    }

    void begin() {
        task = Bukkit.getScheduler().runTaskTimer(n.plugin(), this, STEP, STEP);
    }

    /** Stops where it is and takes the figure away. */
    void stop() {
        if (task != null) task.cancel();
        vanish(Bukkit.getPlayer(id));
    }

    @Override
    public void run() {
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            n.broken(this, null);
            return;
        }
        try {
            play(p);
        } catch (RuntimeException e) {
            // never left half played with its task running on (nobody else would get an answer from him meanwhile)
            n.plugin().getLogger().log(java.util.logging.Level.WARNING, "[비밀] newcomer 엔딩 오류 (멈춤: 그 비석 앞에서 다시 귓속말하면 처음부터)", e);
            n.broken(this, null);
        }
    }

    private void play(Player p) {
        // they left him standing there: it stops, and nothing is handed on
        if (p.isDead() || !p.getWorld().equals(stone.getWorld()) || p.getLocation().distanceSquared(stone) > leave * leave) {
            n.broken(this, p);
            return;
        }
        List<String> lines = n.ending();
        int due = t < lead ? 0 : Math.min(lines.size(), (t - lead) / gap + 1);
        while (said < due) line(p, lines, said++);
        if (t >= lead + lines.size() * gap) {
            stop();
            n.done(this, p);
            return;
        }
        if (quiet && t % 20 == 0) p.stopAllSounds();
        if (dust && t % 10 == 0) dust(p, 2);
        watch(p);
        t += STEP;
    }

    private void line(Player p, List<String> lines, int i) {
        p.sendMessage(n.incoming(lines.get(i).replace("{player}", hearer)));
        if (dust && (i == 0 || i == lines.size() - 2)) dust(p, 18);
        if (withFigure && i == figureLine) appear(p);
        if (i == lines.size() - 1) vanish(p);
    }

    private void dust(Player p, int count) {
        if (!p.getWorld().equals(stone.getWorld())) return;
        if (grit == null) grit = Material.GRAVEL.createBlockData();
        p.spawnParticle(Particle.FALLING_DUST, stone.clone().add(0, 1.6, 0), count, 0.28, 0.12, 0.28, 0, grit);
    }

    /**
     * Someone past the stone, facing it, where the player is not looking: behind the stone when that is at the edge of
     * their sight, else swung round to the edge on that side.
     */
    private void appear(Player p) {
        if (figure != null || !p.getWorld().equals(stone.getWorld())) return;
        Location eye = p.getEyeLocation();
        Vector away = (centre != null && centre.getWorld().equals(stone.getWorld()) ? stone.toVector().subtract(centre.toVector())
                : stone.toVector().subtract(eye.toVector())).setY(0);
        if (away.lengthSquared() < 1e-6) away = flat(eye.getDirection(), eye.getYaw());
        Vector at = stone.toVector().add(away.normalize().multiply(2.2));
        Vector facing = flat(eye.getDirection(), eye.getYaw());
        Vector to = at.clone().subtract(eye.toVector()).setY(0);
        double angle = to.lengthSquared() < 1e-6 ? 0 : facing.angle(to);
        if (angle < Math.toRadians(32) || angle > Math.toRadians(52)) {
            double far = Math.max(3.5, Math.min(7, to.length()));
            Vector a = facing.clone().rotateAroundY(Math.toRadians(44)).multiply(far), b = facing.clone().rotateAroundY(Math.toRadians(-44)).multiply(far);
            Vector pick = a.distanceSquared(to) <= b.distanceSquared(to) ? a : b;
            at = eye.toVector().add(pick);
        }
        Location l = new Location(stone.getWorld(), at.getX(), stone.getY(), at.getZ());
        Vector toStone = stone.toVector().subtract(l.toVector()).setY(0);
        if (toStone.lengthSquared() > 1e-6) l.setDirection(toStone);
        l.setPitch(0);
        String skin = n.cfg().getString("ending.figure-skin", "");
        String name = n.cfg().getBoolean("ending.figure-name", true) ? "<gray>" + MiniMessage.miniMessage().escapeTags(n.who()) : null;
        try {
            figure = n.module().haunt().figure(p, l, skin == null || skin.isBlank() ? null : skin, name);
        } catch (RuntimeException e) {
            figure = null;   // no figure is no loss
        }
    }

    /** Looked at straight on (or walked up to), he is not there. */
    private void watch(Player p) {
        if (figure == null) return;
        if (!figure.isValid() || !figure.getWorld().equals(p.getWorld())) {
            vanish(p);
            return;
        }
        Location eye = p.getEyeLocation();
        Vector to = figure.getLocation().add(0, 1.1, 0).toVector().subtract(eye.toVector());
        if (to.lengthSquared() < 2.5 * 2.5 || eye.getDirection().angle(to) < look) vanish(p);
    }

    private void vanish(Player p) {
        Mannequin f = figure;
        figure = null;
        if (f == null) return;
        if (p != null) n.module().haunt().remove(p, f);
        else if (f.isValid()) f.remove();
    }
}
