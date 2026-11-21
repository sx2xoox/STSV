package dev.stsv.economy.secret.scarecrow;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this secret shows one player beyond the module's {@link dev.stsv.economy.secret.Haunt}: item displays (the
 * scarecrow's copy, the straw hand), click boxes, real entities hidden from them (the real scarecrow while its copy
 * walks), fake blocks it can put back on its own (the withered field, the rattling door) and its timers. {@link #clear}
 * takes everything away and shows the real things again.
 */
final class Props {

    private final Plugin plugin;
    private final Map<UUID, List<Entity>> shown = new ConcurrentHashMap<>();
    private final Map<UUID, List<Entity>> hidden = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Block, Boolean>> fakes = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();

    Props(Plugin plugin) {
        this.plugin = plugin;
    }

    Plugin plugin() {
        return plugin;
    }

    private <T extends Entity> T track(Player viewer, T e) {
        viewer.showEntity(plugin, e);
        shown.computeIfAbsent(viewer.getUniqueId(), k -> new ArrayList<>()).add(e);
        return e;
    }

    /** An item display only the viewer sees. */
    ItemDisplay item(Player viewer, Location at, ItemStack it, float scale) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            e.setBrightness(new Display.Brightness(6, 6));
        });
        return track(viewer, d);
    }

    /** A copy of a display entity (same item, look and turn) only the viewer sees. */
    ItemDisplay copy(Player viewer, ItemDisplay of, Location at) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(of.getItemStack());
            e.setItemDisplayTransform(of.getItemDisplayTransform());
            e.setTransformation(of.getTransformation());
            if (of.getBrightness() != null) e.setBrightness(of.getBrightness());
            e.setViewRange(Math.max(1f, of.getViewRange()));
            e.setDisplayWidth(of.getDisplayWidth());
            e.setDisplayHeight(of.getDisplayHeight());
        });
        return track(viewer, d);
    }

    /** A furniture-looking display from an item and its model offset (when there is no real one to copy). */
    ItemDisplay model(Player viewer, Location at, ItemStack it, Vector3f translation, Vector3f scale) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(translation, new Quaternionf(), scale, new Quaternionf()));
        });
        return track(viewer, d);
    }

    /** A click box only the viewer can hit. */
    Interaction hit(Player viewer, Location feet, float width, float height) {
        Interaction i = feet.getWorld().spawn(feet, Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(width);
            e.setInteractionHeight(height);
            e.setResponsive(true);
        });
        return track(viewer, i);
    }

    /** One shown thing gone. */
    void remove(Player viewer, Entity e) {
        if (e == null) return;
        List<Entity> l = shown.get(viewer.getUniqueId());
        if (l != null) l.remove(e);
        if (e.isValid()) e.remove();
    }

    void remove(UUID viewer, Entity e) {
        if (e == null) return;
        List<Entity> l = shown.get(viewer);
        if (l != null) l.remove(e);
        if (e.isValid()) e.remove();
    }

    /** A real entity the viewer no longer sees (until {@link #unhide} or {@link #clear}). */
    void hide(Player viewer, Entity e) {
        if (e == null) return;
        viewer.hideEntity(plugin, e);
        hidden.computeIfAbsent(viewer.getUniqueId(), k -> new ArrayList<>()).add(e);
    }

    void unhide(Player viewer, Entity e) {
        if (e == null) return;
        List<Entity> l = hidden.get(viewer.getUniqueId());
        if (l != null) l.remove(e);
        // still hidden by another of our things (two copies of the same scarecrow)
        if (l != null && l.contains(e)) return;
        if (e.isValid()) viewer.showEntity(plugin, e);
    }

    /** A block that looks different to the viewer only; put back by {@link #restore} or {@link #clear}. */
    void fake(Player viewer, Block b, BlockData data) {
        viewer.sendBlockChange(b.getLocation(), data);
        fakes.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>()).put(b, Boolean.TRUE);
    }

    /** Shows the viewer the real block again. */
    void restore(Player viewer, Block b) {
        Map<Block, Boolean> f = fakes.get(viewer.getUniqueId());
        if (f != null) f.remove(b);
        viewer.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    void restoreAll(Player viewer, List<Block> blocks) {
        for (Block b : blocks) restore(viewer, b);
    }

    /** A timer belonging to the viewer (cancelled by {@link #clear}). */
    BukkitTask later(Player viewer, long ticks, Runnable r) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (viewer.isOnline()) r.run();
        }, Math.max(1, ticks));
        keep(viewer.getUniqueId(), t);
        return t;
    }

    BukkitTask every(Player viewer, long period, Runnable r) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, r, 1L, Math.max(1, period));
        keep(viewer.getUniqueId(), t);
        return t;
    }

    private void keep(UUID u, BukkitTask t) {
        List<BukkitTask> l = tasks.computeIfAbsent(u, k -> new ArrayList<>());
        l.removeIf(BukkitTask::isCancelled);
        l.add(t);
    }

    /** Everything for this viewer away: shown things removed, hidden things shown again, fake blocks put back. */
    void clear(UUID viewer) {
        List<BukkitTask> t = tasks.remove(viewer);
        if (t != null) for (BukkitTask x : t) x.cancel();
        List<Entity> s = shown.remove(viewer);
        if (s != null) for (Entity e : s) if (e.isValid()) e.remove();
        Player p = Bukkit.getPlayer(viewer);
        List<Entity> h = hidden.remove(viewer);
        if (h != null && p != null) for (Entity e : h) if (e.isValid()) p.showEntity(plugin, e);
        Map<Block, Boolean> f = fakes.remove(viewer);
        if (f != null && p != null) for (Block b : f.keySet()) p.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    void clearAll() {
        List<UUID> all = new ArrayList<>(shown.keySet());
        all.addAll(hidden.keySet());
        all.addAll(fakes.keySet());
        all.addAll(tasks.keySet());
        for (UUID u : all) clear(u);
    }
}
