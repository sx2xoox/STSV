package dev.stsv.economy.secret.designer;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The 13th machine's owner moving it (designer.yml thirteenth.move). Sneak + right-click the machine starts it: a glowing ghost
 * of the machine, seen only by them, follows the top of the block they look at (green = it may go there, red = not, with the
 * reason above the hotbar). Right-click puts it there, F (swap hands) or left-click turns it a quarter, sneak + right-click
 * again or the time limit cancels. The ghost is never saved; nothing changes until it is put down.
 */
final class Mover {

    private static final class Session {
        final UUID who;
        final String from;          // where it stood when this began (if that changes, someone else moved it: stop)
        final Location origin;
        final long started;
        final long until;
        int facing;
        long turned;
        Location spot;              // the block it would stand on (origin block), null = nothing looked at
        Parts.Spot verdict = Parts.Spot.AIM;
        Boolean tinted;
        Thirteenth.Body ghost;
        BukkitTask task;
        String bar;
        int steps;

        Session(UUID who, String from, Location origin, int facing, long now, long until) {
            this.who = who;
            this.from = from;
            this.origin = origin;
            this.facing = facing;
            this.started = now;
            this.until = until;
        }
    }

    private final Designer d;
    private final EcoPlugin eco;
    private final Thirteenth t;
    private final Map<UUID, Session> sessions = new HashMap<>();

    Mover(Designer d, EcoPlugin eco, Thirteenth t) {
        this.d = d;
        this.eco = eco;
        this.t = t;
    }

    private String text(String key) {
        return d.cfg().getString("thirteenth.move." + key, "");
    }

    boolean moving(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /** Begins (the owner sneak-right-clicked it). */
    void start(Player p) {
        String from = t.where().orElse(null);
        Location origin = t.origin();
        if (from == null || origin == null || moving(p)) return;
        long now = System.currentTimeMillis();
        int seconds = Math.max(5, d.cfg().getInt("thirteenth.move.seconds", 30));
        Session s = new Session(p.getUniqueId(), from, origin, t.facing(), now, now + seconds * 1000L);
        sessions.put(p.getUniqueId(), s);
        s.task = Bukkit.getScheduler().runTaskTimer(eco, () -> step(p, s), 1L, 2L);
        p.playSound(p.getLocation(), "block.chain.place", SoundCategory.PLAYERS, 0.7f, 0.7f);
    }

    /** A click while moving: right = put it here, sneak + right = cancel. */
    void click(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        if (p.isSneaking()) {
            end(p.getUniqueId(), "cancelled");
            return;
        }
        if (System.currentTimeMillis() - s.started < 300) return;   // the click that began it
        aim(p, s);
        if (s.verdict != Parts.Spot.OK) {
            p.playSound(p.getLocation(), "block.note_block.bass", SoundCategory.PLAYERS, 0.7f, 0.6f);
            s.bar = null;   // show the reason right away
            show(p, s);
            return;
        }
        Location to = s.spot;
        int facing = s.facing;
        end(p.getUniqueId(), null);
        if (!t.move(to, facing)) return;
        eco.getLogger().info("[비밀] 13번째 기계: " + p.getName() + " 이(가) 옮김 " + s.from + " → " + t.where().orElse("?") + " (방향 " + facing + ")");
        p.sendActionBar(Text.mm(text("placed-bar")));
        String w = text("placed");
        if (!w.isEmpty()) d.whisper(p, w);
    }

    /** A quarter turn (F or left-click while moving). */
    void rotate(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (now - s.turned < 150) return;   // one key press can come as two events
        s.turned = now;
        s.facing = Math.floorMod(s.facing + 1, 4);
        if (s.ghost != null && s.ghost.valid()) {
            s.ghost.facing = s.facing;
            s.ghost.yaw = Parts.frontYaw(s.facing);
            Thirteenth.refresh(s.ghost, 3);
        }
        p.playSound(p.getLocation(), "block.chain.step", SoundCategory.PLAYERS, 0.8f, 1.2f);
        s.bar = null;
        show(p, s);
    }

    /** Stops one (msg = a key of thirteenth.move for the action bar, null = quietly). */
    void end(UUID who, String msg) {
        Session s = sessions.remove(who);
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        if (s.ghost != null) s.ghost.remove();
        Player p = Bukkit.getPlayer(who);
        if (msg != null && p != null && p.isOnline()) p.sendActionBar(Text.mm(text(msg)));
    }

    void endAll() {
        for (UUID u : List.copyOf(sessions.keySet())) end(u, null);
    }

    // ------------------------------------------------------------------------------------------
    // every other tick
    // ------------------------------------------------------------------------------------------

    private void step(Player p, Session s) {
        if (sessions.get(s.who) != s) {
            if (s.task != null) s.task.cancel();
            return;
        }
        if (!p.isOnline() || !p.isValid()) {
            end(s.who, null);
            return;
        }
        if (!s.from.equals(t.where().orElse(null))) {
            end(s.who, "gone");
            return;
        }
        if (System.currentTimeMillis() > s.until) {
            end(s.who, "timeout");
            return;
        }
        aim(p, s);
        ghost(p, s);
        show(p, s);
    }

    /** What the player looks at, and whether the machine may stand on it. */
    private void aim(Player p, Session s) {
        s.spot = null;
        double reach = 4.5;
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        if (a != null) reach = a.getValue();
        // a right-click only reaches the server within the player's own block reach; never further than 6
        reach = Math.min(6, Math.max(1, reach));
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), reach, FluidCollisionMode.NEVER, true);
        Block hit = r == null ? null : r.getHitBlock();
        if (hit == null) {
            s.verdict = Parts.spot(false, false, false, false, 0, 0);
            return;
        }
        Block at = hit.getRelative(BlockFace.UP);
        s.spot = at.getLocation();
        boolean room = at.getY() + 1 < at.getWorld().getMaxHeight() && free(at) && free(at.getRelative(BlockFace.UP));
        boolean build = eco.land() == null || eco.land().allowed(p, s.spot);
        double dist = s.spot.getWorld() == s.origin.getWorld() ? s.spot.distanceSquared(s.origin) : -1;
        s.verdict = Parts.spot(true, hit.getType().isSolid(), room, build, dist, d.cfg().getInt("thirteenth.move.max-distance", 32));
    }

    /** Air, or something the machine may stand in (grass, flowers, snow), but no liquid. */
    private static boolean free(Block b) {
        return b.isEmpty() || (b.isPassable() && b.isReplaceable() && !b.isLiquid());
    }

    /** The ghost follows the spot looked at (and stays, red, where it was when nothing is). */
    private void ghost(Player p, Session s) {
        if (s.spot != null) {
            if (s.ghost == null || !s.ghost.valid() || s.ghost.origin.getWorld() != s.spot.getWorld()) {
                if (s.ghost != null) s.ghost.remove();
                s.ghost = t.spawn(s.spot, false, false, s.facing, p);
                s.tinted = null;
            } else if (!s.ghost.origin.equals(s.spot)) {
                for (BlockDisplay x : s.ghost.parts) if (x != null && x.isValid()) x.teleport(s.spot);
                s.ghost.origin = s.spot.clone();
            }
        }
        boolean ok = s.verdict == Parts.Spot.OK;
        if (s.ghost != null && (s.tinted == null || s.tinted != ok)) {
            Thirteenth.tint(s.ghost, ok);
            s.tinted = ok;
        }
    }

    /** The hint above the hotbar: the controls (or why not here), the facing and the seconds left. */
    private void show(Player p, Session s) {
        long left = Math.max(0, (s.until - System.currentTimeMillis() + 999) / 1000);
        List<String> names = d.cfg().getStringList("thirteenth.move.facings");
        String facing = names.size() == 4 ? names.get(s.facing) : String.valueOf(s.facing);
        String line;
        if (s.verdict == Parts.Spot.OK) {
            line = text("hint");
        } else {
            String reason = text("reasons." + s.verdict.name().toLowerCase())
                    .replace("{max}", String.valueOf(d.cfg().getInt("thirteenth.move.max-distance", 32)));
            line = text("bad").replace("{reason}", reason);
        }
        line = line.replace("{facing}", facing).replace("{seconds}", String.valueOf(left));
        // the bar fades after a while: send it when it changes, and again now and then
        if (line.equals(s.bar) && ++s.steps % 10 != 0) return;
        s.bar = line;
        p.sendActionBar(Text.mm(line));
    }
}
