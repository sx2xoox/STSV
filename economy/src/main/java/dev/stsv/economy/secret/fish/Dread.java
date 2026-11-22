package dev.stsv.economy.secret.fish;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What the eyeless fish shows one player (nobody else sees or hears it): the omens it earns one by one (bubbles rising, a
 * line gone heavy and snapping, wet footprints behind them, a figure waist-deep far off in the water, their own name), the
 * water rising over them when they threw it away, and the end — the screen gone dim as under water while their own
 * likeness rises out of the water and sinks back. Also the solver's 젖은 낚싯줄 (dark ripples where their float lands),
 * which everyone sees.
 */
final class Dread {

    private final EyelessFish s;
    private final SecretModule m;
    private final EcoPlugin eco;
    /** the figure standing in the water for a player, and when it came */
    private final Map<UUID, Figure> figures = new ConcurrentHashMap<>();
    /** blocks sent as water to a player (put back by {@link #dry}) */
    private final Map<UUID, List<Location>> wet = new ConcurrentHashMap<>();
    /** where each player stood the last few seconds (the footprints follow) */
    private final Map<UUID, Deque<Location>> trail = new ConcurrentHashMap<>();
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    private record Figure(Mannequin body, long at) {}

    Dread(EyelessFish s, SecretModule m, EcoPlugin eco) {
        this.s = s;
        this.m = m;
        this.eco = eco;
    }

    // ------------------------------------------------------------------------------------------
    // small things
    // ------------------------------------------------------------------------------------------

    private static void sound(Player p, Location at, String key, float volume, float pitch) {
        p.playSound(at, key, SoundCategory.AMBIENT, volume, pitch);
    }

    private void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, r, ticks);
    }

    /** Whether an omen may come now (one every dread.cooldown seconds; testers too, a scene always). */
    boolean ready(UUID u) {
        Long l = last.get(u);
        return l == null || System.currentTimeMillis() - l >= s.cfg().getLong("dread.cooldown", 90) * 1000L;
    }

    void used(UUID u) {
        last.put(u, System.currentTimeMillis());
    }

    /** Water near a player (the hook's, else the nearest open water block within range), or null. */
    static Location waterNear(Player p, int range) {
        FishHook h = p.getFishHook();
        if (h != null && h.isValid() && h.isInWater()) return h.getLocation();
        Location at = p.getLocation();
        World w = at.getWorld();
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                int x = at.getBlockX() + dx, z = at.getBlockZ() + dz;
                for (int dy = 2; dy >= -4; dy--) {
                    Block b = w.getBlockAt(x, at.getBlockY() + dy, z);
                    if (b.getType() != Material.WATER || b.getRelative(0, 1, 0).getType() == Material.WATER) continue;
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = b.getLocation().add(0.5, 0.9, 0.5);
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** The top of the water at a spot: the block above the highest water block of that column at or below it (y = surface). */
    static Location surface(Location at) {
        Block b = at.getBlock();
        int guard = 0;
        while (b.getType() != Material.WATER && guard++ < 4) b = b.getRelative(0, -1, 0);
        while (b.getRelative(0, 1, 0).getType() == Material.WATER && guard++ < 64) b = b.getRelative(0, 1, 0);
        return b.getLocation().add(0.5, 0.9, 0.5);
    }

    // ------------------------------------------------------------------------------------------
    // the omens (one more for each release)
    // ------------------------------------------------------------------------------------------

    /** 1. Breath bubbles rising in the water near them, for a few seconds, with the sound of someone breathing out. */
    void bubbles(Player p, Location at) {
        if (at == null) at = waterNear(p, 8);
        if (at == null) at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
        Location c = at.clone();
        sound(p, c, "block.bubble_column.upwards_ambient", 0.7f, 0.6f);
        later(10, () -> sound(p, c, "entity.player.breath", 0.5f, 0.5f));
        for (int i = 0; i < 8; i++) {
            int k = i;
            later(i * 10L, () -> {
                if (!p.isOnline()) return;
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Location b = c.clone().add(r.nextDouble(-0.6, 0.6), -1.2, r.nextDouble(-0.6, 0.6));
                p.spawnParticle(Particle.BUBBLE_COLUMN_UP, b, 6 + k, 0.15, 0.5, 0.15, 0.02);
                p.spawnParticle(Particle.BUBBLE_POP, c.clone().add(0, 0.05, 0), 3, 0.3, 0, 0.3, 0.01);
                if (k % 3 == 0) sound(p, c, "block.bubble_column.bubble_pop", 0.6f, 0.5f + k * 0.05f);
            });
        }
    }

    /**
     * 2. The line suddenly heavy: the float is dragged down, their arms go slow, the rod creaks — and the line snaps and the
     * float is gone. With no float out, only the weight and the snap.
     */
    void heavy(Player p, FishHook hook) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, false, false));
        sound(p, p.getLocation(), "entity.fishing_bobber.retrieve", 0.8f, 0.4f);
        sound(p, p.getLocation(), "block.wood.hit", 0.6f, 0.5f);
        if (hook != null && hook.isValid()) {
            hook.setVelocity(new Vector(0, -0.8, 0));
            Location at = hook.getLocation();
            p.spawnParticle(Particle.SPLASH, at, 30, 0.3, 0.1, 0.3, 0.1);
            sound(p, at, "entity.fishing_bobber.splash", 1f, 0.4f);
            later(8, () -> {
                if (hook.isValid()) hook.setVelocity(new Vector(0, -0.6, 0));
            });
        }
        later(26, () -> {
            if (!p.isOnline()) return;
            if (hook != null && hook.isValid()) {
                p.spawnParticle(Particle.BUBBLE_POP, hook.getLocation(), 12, 0.2, 0.1, 0.2, 0.02);
                hook.remove();
            }
            sound(p, p.getLocation(), "entity.item.break", 0.7f, 0.6f);
            m.haunt().whisper(p, s.cfg().getString("dread.heavy-line", "<dark_gray><i>…"));
        });
    }

    /** 3. Wet footprints at their feet: where they stood a few seconds ago, one step at a time, as if someone dripping follows. */
    void footprints(Player p) {
        Deque<Location> t = trail.get(p.getUniqueId());
        List<Location> steps = new ArrayList<>();
        if (t != null) steps.addAll(t);
        if (steps.isEmpty()) steps.add(p.getLocation().subtract(p.getLocation().getDirection().setY(0).normalize().multiply(2)));
        for (int i = 0; i < 8; i++) {
            int k = i;
            later(10L * i, () -> {
                if (!p.isOnline()) return;
                // the oldest place first, then nearer: it is walking up behind them
                Location at = k < steps.size() ? steps.get(k) : p.getLocation().subtract(p.getLocation().getDirection().setY(0).normalize().multiply(1.2));
                Location ground = at.clone();
                ground.setY(at.getBlockY() + 0.05);
                p.spawnParticle(Particle.FALLING_WATER, ground.clone().add(0, 1.6, 0), 6, 0.2, 0.3, 0.2, 0);
                p.spawnParticle(Particle.SPLASH, ground, 8, 0.15, 0, 0.15, 0);
                sound(p, ground, "block.mud.step", 0.5f, 0.7f);
            });
        }
    }

    /** Where they stood each second (the footprints' trail). */
    void step(Player p) {
        Deque<Location> t = trail.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        Location l = p.getLocation();
        if (!t.isEmpty() && t.peekLast().getWorld() == l.getWorld() && t.peekLast().distanceSquared(l) < 0.5) return;
        t.addLast(l);
        while (t.size() > 5) t.pollFirst();
    }

    /**
     * 4. A figure standing waist-deep in the water, far off, facing them: no name, dark and dripping. It is gone when they come
     * near (dread.figure-vanish) or after dread.figure-seconds. False when there is no open water at that distance.
     */
    boolean figure(Player p, int min, int max) {
        Location spot = farWater(p, min, max);
        if (spot == null) return false;
        removeFigure(p);
        Vector toP = p.getLocation().toVector().subtract(spot.toVector()).setY(0);
        if (toP.lengthSquared() > 0) spot.setDirection(toP);
        Mannequin body = m.haunt().figure(p, spot, null, null);
        dress(body);
        figures.put(p.getUniqueId(), new Figure(body, System.currentTimeMillis()));
        sound(p, spot, "entity.drowned.ambient_water", 0.6f, 0.6f);
        return true;
    }

    private static void dress(Mannequin body) {
        Color c = Color.fromRGB(0x1d3236);
        var eq = body.getEquipment();
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, c));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, c));
        eq.setBoots(dyed(Material.LEATHER_BOOTS, Color.fromRGB(0x152427)));
    }

    private static ItemStack dyed(Material mat, Color c) {
        ItemStack it = new ItemStack(mat);
        it.editMeta(meta -> {
            if (meta instanceof LeatherArmorMeta l) l.setColor(c);
        });
        return it;
    }

    /** Open water min..max blocks away, ahead of them first, then to the sides; the spot is waist-deep in it. */
    private static Location farWater(Player p, int min, int max) {
        Location at = p.getLocation();
        World w = at.getWorld();
        Vector dir = at.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        for (int a : new int[]{0, 25, -25, 50, -50, 80, -80, 120, -120, 180}) {
            Vector d = dir.clone().rotateAroundY(Math.toRadians(a));
            for (int dist = min; dist <= max; dist += 2) {
                int x = at.getBlockX() + (int) Math.round(d.getX() * dist), z = at.getBlockZ() + (int) Math.round(d.getZ() * dist);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                if (w.getBlockAt(x, top, z).getType() != Material.WATER) continue;
                if (w.getBlockAt(x, top - 1, z).getType() != Material.WATER) continue;   // deep enough to stand in
                return new Location(w, x + 0.5, top - 0.05, z + 0.5);
            }
        }
        return null;
    }

    private void removeFigure(Player p) {
        Figure f = figures.remove(p.getUniqueId());
        if (f != null) m.haunt().remove(p, f.body());
    }

    /** 5. Their own name, said quietly just behind them. */
    void name(Player p) {
        Location l = p.getLocation();
        l.subtract(l.getDirection().setY(0).normalize().multiply(1.5));
        sound(p, l, "entity.drowned.ambient_water", 0.5f, 0.7f);
        later(14, () -> {
            if (!p.isOnline()) return;
            sound(p, p.getLocation(), "entity.player.breath", 0.35f, 0.5f);
            m.haunt().whisper(p, s.cfg().getString("dread.name-line", "<#5f7f8f><i>…{player}…").replace("{player}", p.getName()));
        });
    }

    /** An omen by its name (Waters.OMENS). */
    void play(Player p, String omen) {
        switch (omen) {
            case "bubbles" -> bubbles(p, null);
            case "heavy" -> heavy(p, p.getFishHook());
            case "footprints" -> footprints(p);
            case "figure" -> {
                if (!figure(p, 18, 34) && !figure(p, 8, 48)) name(p);
            }
            case "name" -> name(p);
            default -> { }
        }
    }

    /** Each second: the figure goes when they come near or it has stood long enough; dripping while it stands. */
    void tick(Player p) {
        step(p);
        Figure f = figures.get(p.getUniqueId());
        if (f == null) return;
        Mannequin b = f.body();
        if (!b.isValid() || b.getWorld() != p.getWorld()) {
            figures.remove(p.getUniqueId());
            return;
        }
        double vanish = s.cfg().getDouble("dread.figure-vanish", 12);
        boolean near = b.getLocation().distanceSquared(p.getLocation()) < vanish * vanish;
        boolean old = System.currentTimeMillis() - f.at() > s.cfg().getLong("dread.figure-seconds", 40) * 1000L;
        if (near || old) {
            Location at = b.getLocation();
            p.spawnParticle(Particle.SPLASH, at.clone().add(0, 0.3, 0), 40, 0.4, 0.1, 0.4, 0.1);
            p.spawnParticle(Particle.BUBBLE_COLUMN_UP, at.clone().add(0, -0.5, 0), 20, 0.3, 0.4, 0.3, 0.02);
            if (near) sound(p, at, "entity.player.splash", 0.6f, 0.6f);
            removeFigure(p);
            return;
        }
        p.spawnParticle(Particle.FALLING_WATER, b.getLocation().add(0, 1.7, 0), 3, 0.2, 0.3, 0.2, 0);
    }

    // ------------------------------------------------------------------------------------------
    // releasing, losing it, the end
    // ------------------------------------------------------------------------------------------

    /** The fish (or the tag) given to the water: a soft splash only they hear, rings, and a few bubbles going down. */
    void splash(Player p, Location at) {
        sound(p, at, "entity.fish.swim", 0.8f, 0.6f);
        sound(p, at, "entity.generic.splash", 0.4f, 0.5f);
        p.spawnParticle(Particle.SPLASH, at, 20, 0.25, 0.05, 0.25, 0.05);
        p.spawnParticle(Particle.FISHING, at, 12, 0.4, 0, 0.4, 0.02);
        later(12, () -> {
            if (p.isOnline()) p.spawnParticle(Particle.BUBBLE, at.clone().add(0, -0.6, 0), 10, 0.2, 0.3, 0.2, 0.02);
        });
    }

    /**
     * The end: the first fisher's own name comes up on the water where the tag went in, floats there a moment, and sinks
     * (only they see it) — he has his name back.
     */
    void nameSurfaces(Player p, Location at, String mm) {
        Location top = surface(at);
        Location low = top.clone().add(0, -0.9, 0);
        org.bukkit.entity.TextDisplay t = m.haunt().text(p, low, mm);
        t.setSeeThrough(false);
        t.setTeleportDuration(30);
        later(3, () -> {
            if (t.isValid()) t.teleport(top.clone().add(0, 0.25, 0));
        });
        later(33, () -> {
            if (p.isOnline()) p.spawnParticle(Particle.BUBBLE_POP, top, 12, 0.4, 0.05, 0.4, 0.01);
        });
        later(90, () -> {
            if (!t.isValid()) return;
            t.setTeleportDuration(50);
            t.teleport(top.clone().add(0, -1.6, 0));
            if (p.isOnline()) sound(p, top, "entity.fish.swim", 0.6f, 0.5f);
        });
        later(145, () -> {
            if (p.isOnline()) {
                p.spawnParticle(Particle.BUBBLE_COLUMN_UP, top.clone().add(0, -1, 0), 15, 0.2, 0.3, 0.2, 0.02);
                m.haunt().remove(p, t);
            } else if (t.isValid()) {
                t.remove();
            }
        });
    }

    /** Water only they see, in the air blocks around a spot from y0 up to y1 (inclusive). */
    private void flood(Player p, Location at, int radius, int y0, int y1) {
        BlockData water = Material.WATER.createBlockData();
        World w = at.getWorld();
        List<Location> mine = wet.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        for (int y = y0; y <= y1; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius + 1) continue;
                    Block b = w.getBlockAt(at.getBlockX() + dx, y, at.getBlockZ() + dz);
                    if (!b.isPassable() || b.isLiquid()) continue;
                    p.sendBlockChange(b.getLocation(), water);
                    mine.add(b.getLocation());
                }
            }
        }
    }

    /** Puts back what {@link #flood} showed them. */
    void dry(UUID u) {
        List<Location> l = wet.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (l == null || p == null) return;
        for (Location at : l) if (at.getWorld() == p.getWorld()) p.sendBlockChange(at, at.getBlock().getBlockData());
    }

    /** Thrown away: the water rises around them, over their head, darkness; "…너도 버렸구나."; then it is gone. */
    void rising(Player p) {
        Location at = p.getLocation();
        int y = at.getBlockY();
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 8 * 20, 0, false, false, false));
        sound(p, at, "ambient.underwater.enter", 1f, 0.6f);
        for (int i = 0; i < 4; i++) {
            int k = i;
            later(15L * i, () -> {
                if (!p.isOnline()) return;
                flood(p, at, 2, y - 1 + k, y - 1 + k);
                sound(p, p.getLocation(), "block.water.ambient", 1f, 0.5f + k * 0.1f);
                p.spawnParticle(Particle.BUBBLE_COLUMN_UP, p.getLocation(), 20, 1.2, 0.2, 1.2, 0.05);
            });
        }
        later(70, () -> {
            if (!p.isOnline()) return;
            String line = s.cfg().getString("lose.line", "<#5f7f8f><i>…너도 버렸구나.");
            p.sendMessage(Text.mm(line));
            m.haunt().whisper(p, line);
            sound(p, p.getLocation(), "ambient.underwater.loop.additions.ultra_rare", 1f, 0.6f);
        });
        later(150, () -> {
            if (!p.isOnline()) return;
            dry(p.getUniqueId());
            sound(p, p.getLocation(), "ambient.underwater.exit", 0.8f, 0.6f);
        });
    }

    /**
     * The end: the screen dims as under water (their head and feet are in water only they see, for a while), and out of the
     * water where the tag came up their own likeness slowly rises, faces them, and sinks again.
     */
    void rise(Player p, Location water) {
        Location me = p.getLocation();
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 12 * 20, 0, false, false, false));
        sound(p, me, "ambient.underwater.enter", 1f, 0.5f);
        flood(p, me, 0, me.getBlockY(), me.getBlockY() + 1);
        later(10, () -> {
            if (p.isOnline()) sound(p, p.getLocation(), "ambient.underwater.loop", 1f, 0.6f);
        });
        Location top = surface(water == null ? me : water);
        Vector toP = me.toVector().subtract(top.toVector()).setY(0);
        if (toP.lengthSquared() > 0) top.setDirection(toP);
        double low = top.getY() - 2.4, high = top.getY() - 0.35;
        Location start = top.clone();
        start.setY(low);
        later(20, () -> {
            if (!p.isOnline()) return;
            Mannequin body = m.haunt().likeness(p, p, start, null);
            int up = 80, hold = 50, down = 90;
            for (int t = 1; t <= up + hold + down; t++) {
                int k = t;
                later(t, () -> {
                    if (!body.isValid() || !p.isOnline()) return;
                    double y = k <= up ? low + (high - low) * ease(k / (double) up)
                            : k <= up + hold ? high
                            : high - (high - low - 0.4) * ease((k - up - hold) / (double) down);
                    Location l = body.getLocation();
                    l.setY(y);
                    body.teleport(l);
                    if (k % 5 == 0) p.spawnParticle(Particle.FALLING_WATER, l.clone().add(0, 1.8, 0), 4, 0.25, 0.4, 0.25, 0);
                    if (k == up) sound(p, l, "entity.player.breath", 0.5f, 0.45f);
                    if (k == up + hold + 10) sound(p, l, "entity.player.splash", 0.5f, 0.5f);
                });
            }
            later(up + hold + down + 2, () -> {
                if (p.isOnline()) {
                    p.spawnParticle(Particle.BUBBLE_COLUMN_UP, top, 30, 0.3, 0.3, 0.3, 0.02);
                    m.haunt().remove(p, body);
                }
            });
        });
        later(9 * 20, () -> {
            if (!p.isOnline()) return;
            dry(p.getUniqueId());
            sound(p, p.getLocation(), "ambient.underwater.exit", 0.8f, 0.5f);
        });
    }

    private static double ease(double x) {
        x = Math.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** The solver's 젖은 낚싯줄: dark rings spreading where their float lands (everyone near sees them). */
    void ripples(Location at) {
        World w = at.getWorld();
        Location c = at.clone();
        c.setY(at.getY() + 0.1);
        for (int i = 0; i < 4; i++) {
            int k = i;
            later(4L * i, () -> {
                double r = 0.4 + k * 0.35;
                for (int a = 0; a < 16; a++) {
                    double ang = a * Math.PI / 8;
                    w.spawnParticle(Particle.SQUID_INK, c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 1, 0, 0, 0, 0);
                }
                if (k == 0) w.spawnParticle(Particle.BUBBLE_POP, c, 6, 0.2, 0, 0.2, 0.01);
            });
        }
    }

    void cleanup(UUID u) {
        Player p = Bukkit.getPlayer(u);
        Figure f = figures.remove(u);
        if (f != null) {
            if (p != null) m.haunt().remove(p, f.body());
            else if (f.body().isValid()) f.body().remove();
        }
        dry(u);
        trail.remove(u);
        last.remove(u);
    }
}
