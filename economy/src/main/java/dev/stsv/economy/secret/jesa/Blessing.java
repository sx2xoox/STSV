package dev.stsv.economy.secret.jesa;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 조상님 음덕, the solver's lasting reward (jesa.yml {@code blessing}): every few minutes, when they have stood still a
 * moment, two old people stand two blocks behind them for a few seconds — seen by the other players near, never by the
 * solver (unless an admin shows it to them). They say nothing and are gone. Nothing is saved.
 */
final class Blessing {

    private record Standing(List<Mannequin> elders, BukkitTask end) {}

    private final Jesa jesa;
    private final Map<UUID, Long> next = new ConcurrentHashMap<>();
    private final Map<UUID, Location> last = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> still = new ConcurrentHashMap<>();
    private final Map<UUID, Standing> standing = new ConcurrentHashMap<>();

    Blessing(Jesa jesa) {
        this.jesa = jesa;
    }

    boolean enabled() {
        return jesa.cfg().getBoolean("blessing.enabled", true);
    }

    /** The wait until the next time: between the two numbers of a list (seconds); one number = always that. */
    static long between(List<Integer> range, int def, java.util.Random r) {
        int a = range.isEmpty() ? def : range.getFirst(), b = range.size() > 1 ? range.get(1) : a;
        int lo = Math.max(1, Math.min(a, b)), hi = Math.max(lo, Math.max(a, b));
        return lo + (hi > lo ? r.nextInt(hi - lo + 1) : 0);
    }

    private long wait(UUID u) {
        return between(jesa.cfg().getIntegerList(jesa.testing(u) ? "blessing.test-every" : "blessing.every"), 540, ThreadLocalRandom.current()) * 1000L;
    }

    /** Each second for the solver: maybe it is time. */
    void tick(Player p) {
        UUID u = p.getUniqueId();
        if (!enabled() || standing.containsKey(u)) return;
        long now = System.currentTimeMillis();
        long at = next.computeIfAbsent(u, k -> now + wait(u));
        Location here = p.getLocation();
        Location was = last.put(u, here);
        boolean same = was != null && was.getWorld() == here.getWorld() && was.distanceSquared(here) < 0.01;
        int secs = same ? still.merge(u, 1, Integer::sum) : still.merge(u, 0, (a, b) -> 0);
        if (now < at || secs < Math.max(1, jesa.cfg().getInt("blessing.still-seconds", 3))) return;
        if (p.isInsideVehicle() || p.isFlying() || p.isGliding() || p.isSwimming() || p.getGameMode() == GameMode.SPECTATOR) return;
        Set<Player> watchers = watchers(p);
        if (watchers.isEmpty()) return;
        if (play(p, watchers)) next.put(u, now + wait(u));
    }

    private Set<Player> watchers(Player of) {
        double r = jesa.cfg().getDouble("blessing.radius", 24);
        Set<Player> out = new HashSet<>();
        for (Player o : of.getWorld().getPlayers()) {
            if (o == of || !o.canSee(of) || o.getGameMode() == GameMode.SPECTATOR) continue;
            if (o.getLocation().distanceSquared(of.getLocation()) <= r * r) out.add(o);
        }
        return out;
    }

    /** Where the two stand: two blocks behind, half a block either side, on ground they can stand on (else nowhere). */
    private static List<Location> spots(Player of) {
        Location me = of.getLocation();
        Vector back = me.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(0, 0, 1);
        back.normalize();
        Vector side = new Vector(-back.getZ(), 0, back.getX());
        List<Location> out = new ArrayList<>();
        for (double s : new double[] {-0.55, 0.55}) {
            Location at = me.clone().subtract(back.clone().multiply(2)).add(side.clone().multiply(s));
            Block b = at.getBlock();
            Block feet = null;
            for (int dy : new int[] {0, 1, -1}) {
                Block c = b.getRelative(0, dy, 0);
                if (c.isPassable() && !c.isLiquid() && c.getRelative(0, 1, 0).isPassable() && c.getRelative(0, -1, 0).isSolid()) {
                    feet = c;
                    break;
                }
            }
            if (feet == null) return List.of();
            at.setY(feet.getY());
            at.setYaw(me.getYaw());
            at.setPitch(8);
            out.add(at);
        }
        return out;
    }

    /** The two stand behind `of`, shown to these players. False when there is no ground for them. */
    boolean play(Player of, Set<Player> viewers) {
        stop(of.getUniqueId());
        List<Location> at = spots(of);
        if (at.size() < 2) return false;
        List<String> skins = jesa.cfg().getStringList("blessing.skins");
        if (skins.size() < 2) skins = List.of("economy:entity/bus/grandma_2 slim", "economy:entity/bus/dogwalker_1");
        List<Mannequin> elders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Mannequin e = spawn(at.get(i), skins.get(i));
            for (Player v : viewers) v.showEntity(jesa.plugin(), e);
            elders.add(e);
        }
        long life = Math.max(1, jesa.cfg().getInt("blessing.seconds", 5)) * 20L;
        UUID u = of.getUniqueId();
        BukkitTask end = Bukkit.getScheduler().runTaskLater(jesa.plugin(), () -> stop(u), life);
        standing.put(u, new Standing(elders, end));
        return true;
    }

    private Mannequin spawn(Location at, String skin) {
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
            boolean slim = skin.endsWith(" slim");
            String k = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
            try {
                e.setProfile(ResolvableProfile.resolvableProfile().name("elder").skinPatch(sp -> sp.body(Key.key(k))
                        .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
            } catch (RuntimeException ex) {
                jesa.plugin().getLogger().warning("[비밀] 스킨 키가 잘못됨: " + skin);
            }
        });
    }

    void stop(UUID of) {
        Standing s = standing.remove(of);
        if (s == null) return;
        s.end.cancel();
        for (Mannequin e : s.elders) if (e.isValid()) e.remove();
    }

    void cleanup(UUID u) {
        stop(u);
        next.remove(u);
        last.remove(u);
        still.remove(u);
    }
}
