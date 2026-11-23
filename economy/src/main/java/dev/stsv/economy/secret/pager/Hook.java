package dev.stsv.economy.secret.pager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What is left where the blue gate stood, in the weeks of the follow-up ({@link PagerSequel}): one gate post with a scrap
 * of blue paint, and the basket hanging on it. Only its viewer sees it (displays that are not saved, and one click box);
 * it blocks nothing.
 */
final class Hook {

    /** On the click box: its viewer's uuid. */
    static final NamespacedKey TAG = new NamespacedKey("economy", "pager_hook");

    private static final Vector3f BASKET = new Vector3f(0.4f, 0.3f, 0.36f);

    private static final class Shown {
        Location base;
        boolean preview;
        final List<Entity> all = new ArrayList<>();
        BlockDisplay basket;
        Interaction hit;
        ItemDisplay inside;
    }

    private final Pager s;
    private final Map<UUID, Shown> shown = new HashMap<>();

    Hook(Pager s) {
        this.s = s;
    }

    boolean showing(UUID u) {
        return shown.containsKey(u);
    }

    boolean preview(UUID u) {
        Shown x = shown.get(u);
        return x != null && x.preview;
    }

    /** Whether what was shown is no longer there (its chunk was unloaded under it). */
    boolean gone(UUID u) {
        Shown x = shown.get(u);
        return x != null && !(x.basket.isValid() && x.hit.isValid());
    }

    Location where(UUID u) {
        Shown x = shown.get(u);
        return x == null ? null : x.base.clone();
    }

    private BlockData block(String key, Material def) {
        String v = s.cfg().getString(key);
        if (v != null && !v.isBlank()) {
            try {
                return Bukkit.createBlockData(v.trim().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // (the house's own blocks are warned about where the house is built)
            }
        }
        return def.createBlockData();
    }

    private BlockDisplay box(Player p, Shown x, double lx, double ly, double lz, float sx, float sy, float sz, BlockData data) {
        Location at = x.base.clone().add(lx, ly, lz);
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setBlock(data);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setBrightness(new Display.Brightness(0, 15));
        });
        p.showEntity(s.plugin(), d);
        x.all.add(d);
        return d;
    }

    /**
     * Where the post may stand at a kept spot: on the first free block at or above it (something was built there since),
     * or on the ground below it (the ground was dug away); the spot itself when neither is found within a few blocks.
     */
    static Location settle(Location at) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ(), y = at.getBlockY();
        if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) return at;
        int lo = Math.max(w.getMinHeight() + 1, y - 8), hi = Math.min(w.getMaxHeight() - 2, y + 8);
        for (int yy = y; yy <= hi; yy++) {
            if (!w.getBlockAt(x, yy, z).isPassable()) continue;
            if (yy > y || !w.getBlockAt(x, yy - 1, z).isPassable()) return new Location(w, at.getX(), yy, at.getZ(), at.getYaw(), 0);
            break;
        }
        for (int yy = y - 1; yy >= lo; yy--)
            if (w.getBlockAt(x, yy, z).isPassable() && !w.getBlockAt(x, yy - 1, z).isPassable()) return new Location(w, at.getX(), yy, at.getZ(), at.getYaw(), 0);
        return at;
    }

    /** The post and its basket for this viewer, the foot of the post at {@code base}. */
    void show(Player p, Location base, boolean preview) {
        hide(p.getUniqueId());
        Shown x = new Shown();
        x.base = base.clone();
        x.preview = preview;
        BlockData post = block("house.blocks.post", Material.POLISHED_ANDESITE), wood = block("house.blocks.frame", Material.DARK_OAK_PLANKS);
        box(p, x, -0.16, 0, -0.16, 0.32f, 1.7f, 0.32f, post);
        box(p, x, -0.2, 1.7, -0.2, 0.4f, 0.08f, 0.4f, block("house.blocks.cap", Material.SMOOTH_STONE));
        // what is left of the gate: a hinge, and a hand's width of blue
        box(p, x, 0.16, 1.2, -0.03, 0.22f, 0.3f, 0.06f, block("house.blocks.gate", Material.BLUE_CONCRETE));
        box(p, x, 0.16, 0.3, -0.03, 0.1f, 0.12f, 0.06f, block("house.blocks.handle", Material.IRON_BLOCK));
        // the basket on its hook
        box(p, x, -0.02, 1.1, 0.16, 0.04f, 0.04f, 0.3f, wood);
        box(p, x, -0.015, 0.8, 0.42, 0.03f, 0.32f, 0.03f, wood);
        x.basket = box(p, x, -0.2, 0.55, 0.26, BASKET.x, BASKET.y, BASKET.z, block("house.blocks.basket", Material.COMPOSTER));
        Location at = x.base.clone().add(0, 0.5, 0.44);
        x.hit = at.getWorld().spawn(at, Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(0.7f);
            e.setInteractionHeight(0.6f);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        p.showEntity(s.plugin(), x.hit);
        x.all.add(x.hit);
        shown.put(p.getUniqueId(), x);
    }

    void hide(UUID u) {
        Shown x = shown.remove(u);
        if (x == null) return;
        for (Entity e : x.all) if (e.isValid()) e.remove();
        if (x.inside != null && x.inside.isValid()) x.inside.remove();
    }

    void clearAll() {
        for (UUID u : new HashSet<>(shown.keySet())) hide(u);
    }

    /** Whether this click box is this viewer's own basket. */
    boolean mine(Player p, Interaction hit) {
        Shown x = shown.get(p.getUniqueId());
        return x != null && hit.equals(x.hit);
    }

    /** The basket swings on its hook (it took nothing). */
    void swing(Player p) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null || !x.basket.isValid()) return;
        tilt(x.basket, 0.2f, 3);
        later(p, x, 4, () -> tilt(x.basket, -0.13f, 4));
        later(p, x, 9, () -> tilt(x.basket, 0.06f, 4));
        later(p, x, 14, () -> tilt(x.basket, 0, 4));
        s.sound(p, x.base.clone().add(0, 0.8, 0.4), "block.scaffolding.hit", 0.5f, 0.8f);
    }

    private static void tilt(BlockDisplay d, float angle, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ(angle), new Vector3f(BASKET), new Quaternionf()));
    }

    private void later(Player p, Shown x, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(s.plugin(), () -> {
            if (p.isOnline() && shown.get(p.getUniqueId()) == x) r.run();
        }, ticks);
    }

    /** Something lies in the basket (until it is hidden or emptied). */
    void fill(Player p, ItemStack it) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null) return;
        empty(p.getUniqueId());
        ItemStack one = it.clone();
        one.setAmount(1);
        Location at = x.base.clone().add(0, 0.8, 0.44);
        x.inside = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(one);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) Math.toRadians(-70)), new Vector3f(0.3f, 0.3f, 0.3f), new Quaternionf()));
            e.setBrightness(new Display.Brightness(0, 15));
        });
        p.showEntity(s.plugin(), x.inside);
    }

    void empty(UUID u) {
        Shown x = shown.get(u);
        if (x == null || x.inside == null) return;
        if (x.inside.isValid()) x.inside.remove();
        x.inside = null;
    }

    /** A latch somewhere behind the post, where no gate is: she heard them. */
    void latch(Player p) {
        Shown x = shown.get(p.getUniqueId());
        Location at = x == null ? p.getLocation() : x.base.clone().add(0, 1.0, -0.4);
        s.sound(p, at, "block.iron_trapdoor.close", 0.45f, 1.4f);
    }
}
