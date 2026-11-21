package dev.stsv.economy.secret.scarecrow;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

/**
 * The scarecrow as one player sees it: a copy of the real furniture's model that only they see, standing where the
 * real one stands (which is then hidden from them) or wherever it has been "found" since. Its whole body turns on its
 * pole, slowly (the display interpolates between turns). It can carry a click box, so a player can hand it things
 * wherever it stands. The real scarecrow is never moved or changed.
 */
final class Effigy {

    /** model height of the head above the feet block (blocks) */
    static final double HEAD = 1.62;

    private final Props props;
    private final Player viewer;
    private final ItemDisplay real;
    private final ItemDisplay model;
    private final float yawOffset;
    private Interaction hit;
    private Block feet;
    private float facing;

    private Effigy(Props props, Player viewer, ItemDisplay real, ItemDisplay model, float yawOffset, Block feet, float facing) {
        this.props = props;
        this.viewer = viewer;
        this.real = real;
        this.model = model;
        this.yawOffset = yawOffset;
        this.feet = feet;
        this.facing = facing;
    }

    /** A copy of a real scarecrow (hidden from the viewer while the copy stands), at its own spot, facing as it does. */
    static Effigy of(Props props, Player viewer, ItemDisplay real, float yawOffset) {
        Block feet = real.getLocation().getBlock();
        float facing = (float) Geometry.wrap(real.getYaw() - yawOffset);
        ItemDisplay copy = props.copy(viewer, real, display(feet, facing + yawOffset));
        copy.setTeleportDuration(3);
        props.hide(viewer, real);
        return new Effigy(props, viewer, real, copy, yawOffset, feet, facing);
    }

    /** A scarecrow where there is none (admin scenes away from a field): the item's model at a spot. */
    static Effigy made(Props props, Player viewer, Block feet, float facing, ItemStack item, Vector3f translation, Vector3f scale, float yawOffset) {
        ItemDisplay m = props.model(viewer, display(feet, facing + yawOffset), item, translation, scale);
        m.setTeleportDuration(3);
        return new Effigy(props, viewer, null, m, yawOffset, feet, facing);
    }

    private static Location display(Block feet, float yaw) {
        Location l = feet.getLocation().add(0.5, 0.5, 0.5);
        l.setYaw(yaw);
        l.setPitch(0);
        return l;
    }

    Player viewer() {
        return viewer;
    }

    Block feet() {
        return feet;
    }

    float facing() {
        return facing;
    }

    boolean valid() {
        return model.isValid();
    }

    /** The middle of its feet block, on the ground. */
    Location base() {
        return feet.getLocation().add(0.5, 0, 0.5);
    }

    Location head() {
        return base().add(0, HEAD, 0);
    }

    /** Gives it a click box (for offerings). */
    void clickable() {
        if (hit != null && hit.isValid()) return;
        hit = props.hit(viewer, base(), 0.9f, 2.0f);
    }

    boolean owns(org.bukkit.entity.Entity e) {
        return hit != null && hit.getUniqueId().equals(e.getUniqueId());
    }

    /** The yaw that looks at a spot. */
    float yawTo(Location at) {
        Location b = base();
        return Geometry.yawTo(at.getX() - b.getX(), at.getZ() - b.getZ());
    }

    /** Turns by at most step degrees toward a yaw. Returns whether it now faces it. */
    boolean turn(float yaw, float step) {
        facing = Geometry.approach(facing, yaw, step);
        apply();
        return Geometry.between(facing, yaw) < 0.5;
    }

    /** Faces a yaw at once (still eased over a few ticks by the client). */
    void face(float yaw) {
        facing = (float) Geometry.wrap(yaw);
        apply();
    }

    private void apply() {
        if (!model.isValid()) return;
        model.setRotation((float) Geometry.wrap(facing + yawOffset), 0);
    }

    /** Stands somewhere else (no gliding there: it is simply found there). */
    void moveTo(Block to, float yaw) {
        if (!model.isValid()) return;
        feet = to;
        facing = (float) Geometry.wrap(yaw);
        model.setTeleportDuration(0);
        model.teleport(display(to, facing + yawOffset));
        if (hit != null && hit.isValid()) hit.teleport(base());
        props.later(viewer, 2, () -> {
            if (model.isValid()) model.setTeleportDuration(3);
        });
    }

    /** Stands at an exact spot (the last scare: its face right before theirs). */
    void placeAt(Location feetPoint, float yaw) {
        if (!model.isValid()) return;
        feet = feetPoint.getBlock();
        facing = (float) Geometry.wrap(yaw);
        model.setTeleportDuration(0);
        Location l = feetPoint.clone().add(0, 0.5, 0);
        l.setYaw((float) Geometry.wrap(facing + yawOffset));
        l.setPitch(0);
        model.teleport(l);
    }

    /** Whether the viewer is looking at it now (within about 55° of their view, within 64 blocks). */
    boolean seen() {
        if (!viewer.isOnline() || viewer.getWorld() != feet.getWorld()) return false;
        Location eye = viewer.getEyeLocation();
        Vector to = base().add(0, 1, 0).toVector().subtract(eye.toVector());
        double d = to.length();
        if (d > 64) return false;
        if (d < 1.5) return true;
        return eye.getDirection().angle(to) < Math.toRadians(55);
    }

    /** Gone: the copy and its click box removed, the real one shown again. */
    void remove() {
        props.remove(viewer, model);
        if (hit != null) props.remove(viewer, hit);
        if (real != null) props.unhide(viewer, real);
    }
}
