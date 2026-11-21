package dev.stsv.economy.secret.usher;

import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Haunt;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bag of things one player sees (displays, click boxes, fake blocks, figures from {@link Haunt}), taken away together.
 * Nothing here is saved; everything is hidden from everyone else.
 */
final class Props {

    private final Plugin plugin;
    private final Haunt haunt;
    final UUID viewer;
    final List<Entity> entities = new ArrayList<>();
    final List<Location> fakes = new ArrayList<>();

    Props(Plugin plugin, Haunt haunt, UUID viewer) {
        this.plugin = plugin;
        this.haunt = haunt;
        this.viewer = viewer;
    }

    private Player player() {
        return Bukkit.getPlayer(viewer);
    }

    <T extends Entity> T own(T e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        Player p = player();
        if (p != null) p.showEntity(plugin, e);
        entities.add(e);
        return e;
    }

    /** A figure made by Haunt (already shown and tracked there): kept here too, so it goes with the rest. */
    Mannequin add(Mannequin m) {
        entities.add(m);
        return m;
    }

    /**
     * A block display: its box is sx×sy×sz, centred on at's x and z with its bottom at at's y, turned by yaw around its
     * middle.
     */
    BlockDisplay block(Location at, BlockData data, float sx, float sy, float sz, float yaw) {
        Location l = at.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        return own(l.getWorld().spawn(l, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setBlock(data);
            e.setTransformation(new Transformation(new Vector3f(-sx / 2, 0, -sz / 2), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setTeleportDuration(3);
            e.setViewRange(1.5f);
        }));
    }

    /** An item display (scale s, centred), facing yaw. */
    ItemDisplay item(Location at, ItemStack it, ItemDisplay.ItemDisplayTransform how, float s, float yaw) {
        Location l = at.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        return own(l.getWorld().spawn(l, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(how);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            e.setViewRange(1.5f);
        }));
    }

    /** Floating text. background = the dark plate a name tag has. */
    TextDisplay text(Location at, String mm, Display.Billboard how, float scale, boolean background) {
        return own(at.getWorld().spawn(at, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.text(Text.mm(mm));
            e.setBillboard(how);
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setShadowed(!background);
            if (!background) e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            if (scale != 1f) e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            e.setViewRange(1.0f);
        }));
    }

    /** A click box (width × height, its bottom middle at at). */
    Interaction hit(Location at, float width, float height) {
        return own(at.getWorld().spawn(at, Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(width);
            e.setInteractionHeight(height);
            e.setResponsive(true);
        }));
    }

    /** A block only the viewer sees (put back by {@link #clear}). */
    void fake(Location at, BlockData data) {
        Player p = player();
        Location l = at.getBlock().getLocation();
        if (p != null) p.sendBlockChange(l, data);
        fakes.add(l);
    }

    /** Sends the fake blocks again (after a chunk resend, a teleport back). */
    void refake(java.util.function.Function<Location, BlockData> what) {
        Player p = player();
        if (p == null) return;
        for (Location l : fakes) {
            BlockData d = what.apply(l);
            if (d != null) p.sendBlockChange(l, d);
        }
    }

    /** All of it still there (a chunk unloaded, someone killed a display)? */
    boolean intact() {
        for (Entity e : entities) if (!e.isValid()) return false;
        return true;
    }

    void forget(Entity e) {
        entities.remove(e);
        remove(e);
    }

    private void remove(Entity e) {
        Player p = player();
        if (e instanceof Mannequin && p != null) haunt.remove(p, e);
        else if (e.isValid()) e.remove();
    }

    /** Takes it all away. */
    void clear() {
        for (Entity e : entities) remove(e);
        entities.clear();
        Player p = player();
        if (p != null) for (Location l : fakes) if (l.getWorld() == p.getWorld()) p.sendBlockChange(l, l.getBlock().getBlockData());
        fakes.clear();
    }
}
