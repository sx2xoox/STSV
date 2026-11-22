package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.crop.Scarecrows;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * After it is solved (scarecrow.yml {@code companion}): the solver's scarecrows — every one they set up — keep watch.
 * Now and then one turns its head toward someone on its land who does not belong there (not the owner, not a member),
 * follows them a few seconds, and crows caw above it. Only that intruder sees the head turn; the caws reach the owner
 * too when they are near.
 */
final class Companion {

    private final Scarecrow s;
    /** intruder -> when they may be watched again (ms) */
    private final Map<UUID, Long> rest = new ConcurrentHashMap<>();
    /** intruders being watched now */
    private final Map<UUID, BukkitTask> watching = new ConcurrentHashMap<>();
    private int clock;

    Companion(Scarecrow s) {
        this.s = s;
    }

    private YamlConfiguration cfg() {
        return s.cfg();
    }

    /** Each second. */
    void tick() {
        if (!cfg().getBoolean("companion.enabled", true)) return;
        if (++clock % Math.max(1, cfg().getInt("companion.every-seconds", 5)) != 0) return;
        double radius = cfg().getDouble("companion.radius", 16);
        double chance = cfg().getDouble("companion.chance", 0.2);
        long now = System.currentTimeMillis();
        Map<UUID, Boolean> solved = new HashMap<>();
        for (Scarecrows.Scarecrow sc : s.field().all()) {
            if (!solved.computeIfAbsent(sc.owner(), s::solvedBy)) continue;
            World w = Bukkit.getWorld(sc.world());
            if (w == null || !w.isChunkLoaded(sc.cx(), sc.cz())) continue;
            Location at = new Location(w, sc.x() + 0.5, sc.y(), sc.z() + 0.5);
            for (Player o : w.getPlayers()) {
                UUID ou = o.getUniqueId();
                if (ou.equals(sc.owner()) || watching.containsKey(ou) || s.busy(ou)) continue;
                if (o.getLocation().distanceSquared(at) > radius * radius) continue;
                if (s.field().allows(sc.world(), sc.x(), sc.y(), sc.z(), ou)) continue;
                if (rest.getOrDefault(ou, 0L) > now || ThreadLocalRandom.current().nextDouble() >= chance) continue;
                look(o, sc, at);
            }
        }
    }

    /** One intruder watched: the head follows them for a few seconds, then turns back. */
    void look(Player o, Scarecrows.Scarecrow sc, Location at) {
        ItemDisplay real = s.field().real(sc).orElse(null);
        if (real == null) return;
        UUID ou = o.getUniqueId();
        rest.put(ou, System.currentTimeMillis() + cfg().getLong("companion.cooldown-seconds", 120) * 1000L);
        Effigy e = Effigy.of(s.props(), o, real, s.field().yawOffset());
        s.marks().hideFor(o, sc, true);   // the marks would not turn with the copy
        float home = e.facing();
        int ticks = Math.max(2, cfg().getInt("companion.look-seconds", 6)) * 20;
        s.fx().crows(o, at, 3);
        Player owner = Bukkit.getPlayer(sc.owner());
        if (owner != null && owner.getWorld() == o.getWorld() && owner.getLocation().distanceSquared(at) < 48 * 48) s.fx().crows(owner, at, 2);
        int[] t = {0};
        BukkitTask task = s.props().every(o, 2, () -> {
            t[0] += 2;
            if (!o.isOnline() || !e.valid()) {
                stop(ou, e);
                return;
            }
            if (t[0] < ticks) e.turn(e.yawTo(o.getLocation()), 3.5f);
            else if (e.turn(home, 3.5f) || t[0] > ticks + 120) {
                stop(ou, e);
                s.marks().hideFor(o, sc, false);
            }
        });
        watching.put(ou, task);
    }

    private void stop(UUID ou, Effigy e) {
        BukkitTask t = watching.remove(ou);
        if (t != null) t.cancel();
        e.remove();
    }

    void forget(UUID u) {
        BukkitTask t = watching.remove(u);
        if (t != null) t.cancel();
    }

    /** Admin scene: the nearest scarecrow (any) turns toward this player as if they were an intruder. */
    boolean show(Player p) {
        Scarecrows.Scarecrow best = null;
        double bd = 48 * 48;
        for (Scarecrows.Scarecrow sc : s.field().all()) {
            if (!sc.world().equals(p.getWorld().getName())) continue;
            double d = p.getLocation().distanceSquared(new Location(p.getWorld(), sc.x() + 0.5, sc.y(), sc.z() + 0.5));
            if (d < bd) {
                bd = d;
                best = sc;
            }
        }
        if (best == null) return false;
        Block b = Field.block(best).orElse(null);
        if (b == null) return false;
        rest.remove(p.getUniqueId());
        look(p, best, b.getLocation().add(0.5, 0, 0.5));
        return true;
    }
}
