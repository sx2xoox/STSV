package dev.stsv.economy.secret.garage;

import dev.stsv.core.util.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the 폐차고 shows one player alone, in named groups ("memorial", "shed", "ghost", ...): block, item and text
 * displays and click boxes, hidden from everyone before they enter the world and shown to their viewer only (as
 * {@code Haunt} does it; that has no item displays and no turned boxes). Nothing is saved; a group whose chunk unloaded
 * is noticed ({@link #has}) and shown again.
 */
final class Props {

    /** On a click box: "&lt;viewer uuid&gt; &lt;what&gt;". */
    static final NamespacedKey TAG = new NamespacedKey("economy", "garage_hit");

    private final Plugin plugin;
    private final Map<UUID, Map<String, List<Entity>>> shown = new HashMap<>();

    Props(Plugin plugin) {
        this.plugin = plugin;
    }

    private <T extends Entity> T track(Player viewer, String group, T e) {
        viewer.showEntity(plugin, e);
        shown.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>()).computeIfAbsent(group, k -> new ArrayList<>()).add(e);
        return e;
    }

    private static void hidden(Entity e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
    }

    /** Whether the group is there for this viewer (all of it still in the world). */
    boolean has(UUID viewer, String group) {
        List<Entity> l = shown.getOrDefault(viewer, Map.of()).get(group);
        if (l == null || l.isEmpty()) return false;
        for (Entity e : l) if (!e.isValid()) return false;
        return true;
    }

    /** Whether anything of the group is tracked (valid or not). */
    boolean any(UUID viewer, String group) {
        return shown.getOrDefault(viewer, Map.of()).containsKey(group);
    }

    void drop(UUID viewer, String group) {
        Map<String, List<Entity>> g = shown.get(viewer);
        List<Entity> l = g == null ? null : g.remove(group);
        if (l != null) for (Entity e : l) if (e.isValid()) e.remove();
        if (g != null && g.isEmpty()) shown.remove(viewer);
    }

    void remove(UUID viewer, String group, Entity e) {
        if (e == null) return;
        List<Entity> l = shown.getOrDefault(viewer, Map.of()).get(group);
        if (l != null) l.remove(e);
        if (e.isValid()) e.remove();
    }

    void clear(UUID viewer) {
        Map<String, List<Entity>> g = shown.remove(viewer);
        if (g != null) for (List<Entity> l : g.values()) for (Entity e : l) if (e.isValid()) e.remove();
    }

    void clearAll() {
        for (UUID u : new ArrayList<>(shown.keySet())) clear(u);
    }

    /**
     * A box of a block in the frame of {@code origin} (its yaw turns the frame: +z the way it faces, +x to its left): from
     * the corner (x, y, z), this big.
     */
    BlockDisplay box(Player viewer, String group, Location origin, BlockData data, float x, float y, float z, float sx, float sy, float sz) {
        BlockDisplay d = origin.getWorld().spawn(origin, BlockDisplay.class, e -> {
            hidden(e);
            e.setBlock(data);
            e.setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setViewRange(1.6f);
        });
        return track(viewer, group, d);
    }

    /** An item shown at the frame's (x, y, z), this big, turned by {@code turn} (null: as the frame). */
    ItemDisplay item(Player viewer, String group, Location origin, ItemStack it, Vector3f at, Vector3f scale, Quaternionf turn) {
        ItemDisplay d = origin.getWorld().spawn(origin, ItemDisplay.class, e -> {
            hidden(e);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(at, turn == null ? new Quaternionf() : turn, scale, new Quaternionf()));
            e.setViewRange(1.6f);
        });
        return track(viewer, group, d);
    }

    /** Text standing in the world (facing the way {@code at} faces), or turning to the viewer. */
    TextDisplay text(Player viewer, String group, Location at, String mm, boolean billboard, float scale) {
        TextDisplay t = at.getWorld().spawn(at, TextDisplay.class, e -> {
            hidden(e);
            e.text(Text.mm(mm));
            e.setBillboard(billboard ? Display.Billboard.CENTER : Display.Billboard.FIXED);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(false);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
        return track(viewer, group, t);
    }

    /** A click box only the viewer can hit; {@code what} says what it is for. */
    Interaction hit(Player viewer, String group, Location feet, float width, float height, String what) {
        Interaction i = feet.getWorld().spawn(feet, Interaction.class, e -> {
            hidden(e);
            e.setInteractionWidth(width);
            e.setInteractionHeight(height);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, viewer.getUniqueId() + " " + what);
        });
        return track(viewer, group, i);
    }

    /** What a click box is for when it is this player's, else null. */
    static String what(Entity hit, Player p) {
        String tag = hit.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        String mine = p.getUniqueId() + " ";
        return tag != null && tag.startsWith(mine) ? tag.substring(mine.length()) : null;
    }

    /** A spot of a frame in the world: (x, y, z) in the frame of origin (+z the way it faces, +x to its left). */
    static Location local(Location origin, double x, double y, double z) {
        double r = Math.toRadians(origin.getYaw()), cos = Math.cos(r), sin = Math.sin(r);
        return new Location(origin.getWorld(), origin.getX() + x * cos - z * sin, origin.getY() + y, origin.getZ() + x * sin + z * cos,
                origin.getYaw(), 0);
    }
}
