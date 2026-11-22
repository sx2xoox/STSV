package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.crop.Scarecrows;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the night left on it (scarecrow.yml {@code marks}): claw scratches across its body, torn straw sticking out, and a
 * small crown of straw on its head. Shown once to the player at dawn (it stood at their door all night, its back to it),
 * and for good — to everyone — on every scarecrow the solver set up: extra displays over the furniture, which itself is
 * never touched. Not saved (made again each second while the chunk is loaded).
 */
final class Marks {

    /** On the lasting marks: left over from before (the module restarted) they are found and taken away, never doubled. */
    private static final org.bukkit.NamespacedKey TAG = new org.bukkit.NamespacedKey("economy", "scarecrow_mark");

    private final Scarecrow s;
    /** "world x y z" -> the marks on that scarecrow (everyone sees them) */
    private final Map<String, List<Entity>> on = new HashMap<>();

    Marks(Scarecrow s) {
        this.s = s;
    }

    private YamlConfiguration cfg() {
        return s.cfg();
    }

    private static String key(Scarecrows.Scarecrow sc) {
        return sc.world() + " " + sc.x() + " " + sc.y() + " " + sc.z();
    }

    /** Each second: the solver's scarecrows carry their marks; gone ones (broken, unloaded, not solved) lose them. */
    void tick() {
        boolean enabled = cfg().getBoolean("marks.enabled", true);
        Map<String, Scarecrows.Scarecrow> want = new HashMap<>();
        Map<java.util.UUID, Boolean> solved = new HashMap<>();
        if (enabled) {
            for (Scarecrows.Scarecrow sc : s.field().all()) {
                if (!solved.computeIfAbsent(sc.owner(), s::solvedBy)) continue;
                World w = Bukkit.getWorld(sc.world());
                if (w == null || !w.isChunkLoaded(sc.cx(), sc.cz())) continue;
                want.put(key(sc), sc);
            }
        }
        for (String k : new ArrayList<>(on.keySet())) {
            List<Entity> l = on.get(k);
            if (!want.containsKey(k) || l.stream().anyMatch(e -> !e.isValid())) {
                l.forEach(e -> {
                    if (e.isValid()) e.remove();
                });
                on.remove(k);
            }
        }
        for (Map.Entry<String, Scarecrows.Scarecrow> en : want.entrySet()) {
            if (on.containsKey(en.getKey())) continue;
            ItemDisplay real = s.field().real(en.getValue()).orElse(null);
            Block b = Field.block(en.getValue()).orElse(null);
            if (real == null || b == null) continue;
            float facing = (float) Geometry.wrap(real.getYaw() - s.field().yawOffset());
            Location base = b.getLocation().add(0.5, 0, 0.5);
            orphans(base);
            on.put(en.getKey(), build(base, facing, true,
                    e -> e.getPersistentDataContainer().set(TAG, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1)));
        }
    }

    /** Lasting marks at this scarecrow that nobody keeps track of (made before a restart of the module): gone. */
    private void orphans(Location base) {
        java.util.Set<java.util.UUID> kept = new java.util.HashSet<>();
        for (List<Entity> l : on.values()) for (Entity e : l) kept.add(e.getUniqueId());
        for (Entity e : base.getWorld().getNearbyEntities(base.clone().add(0, 1.2, 0), 1.2, 1.6, 1.2)) {
            if (e.getPersistentDataContainer().has(TAG) && !kept.contains(e.getUniqueId())) e.remove();
        }
    }

    /** The marks on one scarecrow, for an intruder it turns to watch (they would not turn with the copy): hidden, or back. */
    void hideFor(Player viewer, Scarecrows.Scarecrow sc, boolean hide) {
        List<Entity> l = on.get(key(sc));
        if (l == null) return;
        for (Entity e : l) {
            if (hide) viewer.hideEntity(s.props().plugin(), e);
            else viewer.showEntity(s.props().plugin(), e);
        }
    }

    /** The marks only one player sees (the dawn), on a scarecrow standing at base facing that way. */
    List<Entity> show(Player viewer, Location base, float facing) {
        return build(base, facing, cfg().getBoolean("marks.crown-at-dawn", false), e -> {
            e.setVisibleByDefault(false);
            viewer.showEntity(s.props().plugin(), e);
        });
    }

    /** Admin scene: the solver's look (scratches and the crown), only one player sees it. */
    List<Entity> preview(Player viewer, Location base, float facing) {
        return build(base, facing, true, e -> {
            e.setVisibleByDefault(false);
            viewer.showEntity(s.props().plugin(), e);
        });
    }

    /**
     * Builds them: three claw lines across the chest and two tufts of torn straw (front), and — when crown — a flat ring
     * of hay on the head with straw standing up around it. base = the middle of its feet block, on the ground.
     */
    private List<Entity> build(Location base, float facing, boolean crown, Consumer<Display> setup) {
        List<Entity> out = new ArrayList<>();
        World w = base.getWorld();
        double r = Math.toRadians(facing);
        double fx = -Math.sin(r), fz = Math.cos(r);   // the way it faces
        double rx = -Math.cos(r), rz = -Math.sin(r);  // its right-hand side
        double body = cfg().getDouble("marks.body-y", 1.05), front = cfg().getDouble("marks.front", 0.2);
        // claw scratches: thin dark slivers, slanted the same way, side by side on its chest
        for (int i = -1; i <= 1; i++) {
            Location l = base.clone().add(fx * front + rx * i * 0.09, body + i * 0.03, fz * front + rz * i * 0.09);
            l.setYaw(facing);
            out.add(w.spawn(l, BlockDisplay.class, e -> {
                e.setBlock(Material.DARK_OAK_PLANKS.createBlockData());
                e.setTransformation(new Transformation(new Vector3f(-0.015f, -0.2f, -0.015f), new AxisAngle4f((float) Math.toRadians(28), 0, 0, 1),
                        new Vector3f(0.03f, 0.4f, 0.03f), new AxisAngle4f()));
                base(e, setup);
            }));
        }
        // torn straw where it was clawed
        for (int sd = -1; sd <= 1; sd += 2) {
            int side = sd;
            Location l = base.clone().add(fx * (front + 0.02) + rx * side * 0.2, body - 0.15, fz * (front + 0.02) + rz * side * 0.2);
            l.setYaw(facing + side * 35);
            out.add(w.spawn(l, ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.WHEAT));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(side * 40), 0, 0, 1),
                        new Vector3f(0.3f, 0.3f, 0.3f), new AxisAngle4f()));
                base(e, setup);
            }));
        }
        if (!crown) return out;
        double top = cfg().getDouble("marks.crown-y", 1.9);
        Location ring = base.clone().add(0, top, 0);
        ring.setYaw(facing);
        out.add(w.spawn(ring, BlockDisplay.class, e -> {
            e.setBlock(Material.HAY_BLOCK.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.21f, 0, -0.21f), new AxisAngle4f(), new Vector3f(0.42f, 0.08f, 0.42f), new AxisAngle4f()));
            base(e, setup);
        }));
        int points = 5;
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points + r;
            Location l = ring.clone().add(Math.cos(a) * 0.17, 0.14, Math.sin(a) * 0.17);
            l.setYaw((float) Math.toDegrees(a) - 90);
            out.add(w.spawn(l, ItemDisplay.class, e -> {
                e.setItemStack(new ItemStack(Material.WHEAT));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(-12), 1, 0, 0),
                        new Vector3f(0.26f, 0.26f, 0.26f), new AxisAngle4f()));
                base(e, setup);
            }));
        }
        return out;
    }

    private static void base(Display e, Consumer<Display> setup) {
        e.setPersistent(false);
        e.setBillboard(Display.Billboard.FIXED);
        setup.accept(e);
    }

    void clear() {
        for (List<Entity> l : on.values()) for (Entity e : l) if (e.isValid()) e.remove();
        on.clear();
    }
}
