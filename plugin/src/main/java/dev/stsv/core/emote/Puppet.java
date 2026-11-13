package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Pose;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A puppet in the world: one text display per face ({@link PuppetModel}), all at one fixed place (a block over the
 * feet, where the light is the body's, not the floor's) with yaw and pitch 0. Only their transformations move: every
 * pose is sent as the translation and rotation of the faces that changed, and the clients blend to it over the
 * step's ticks. The text of a face, the heavy part, is sent once, when a player starts seeing it.
 *
 * <p>The displays are not saved (a crash leaves none), carry a tag (leftovers of a reload are removed by it), and
 * are shown player by player: to those within {@code viewDistance} who could see the emoting player.
 */
final class Puppet {

    /** The displays stand this far over the puppet's feet. */
    static final double LIFT = 1.0;
    /** A player this much further than the view distance stops seeing the puppet (so the edge does not flicker). */
    private static final double SLACK = 8;

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Location base;
    private final double facing;
    /** the skin's faces, then the props' */
    private final List<PuppetModel.Face> faces;
    private final int skinFaces;
    private final List<TextDisplay> displays = new ArrayList<>();
    private final List<PuppetModel.Placement> sent = new ArrayList<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final Set<UUID> never;

    /**
     * @param feet    where the puppet stands
     * @param bodyYaw where it looks (degrees)
     * @param props   the signs it carries in this emote
     * @param never   players who do not get to see it (they could not see the emoting player)
     */
    Puppet(Plugin plugin, NamespacedKey tag, Location feet, float bodyYaw, PuppetSkin skin, Pose pose, boolean planted,
           List<EmoteSpec.Prop> props, Set<UUID> never) {
        this.plugin = plugin;
        this.tag = tag;
        this.base = new Location(feet.getWorld(), feet.getX(), feet.getY() + LIFT, feet.getZ(), 0, 0);
        this.facing = -Math.toRadians(bodyYaw);
        this.faces = new ArrayList<>(PuppetModel.faces(skin));
        this.skinFaces = faces.size();
        this.never = never;
        Matrix4d[] joints = EmoteRig.matrices(pose, planted);
        for (PuppetModel.Face face : faces) add(face, joints);
        for (EmoteSpec.Prop prop : props) {
            PuppetModel.Face face = PuppetModel.prop(prop);
            faces.add(face);
            add(face, joints);
        }
    }

    private TextDisplay add(PuppetModel.Face face, Matrix4d[] joints) {
        PuppetModel.Placement at = PuppetModel.place(face, joints[face.joint().ordinal()], facing, LIFT);
        sent.add(at);
        TextDisplay display = base.getWorld().spawn(base, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            d.text(PuppetText.component(face));
            d.setLineWidth(PuppetText.LINE_WIDTH);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setDefaultBackground(false);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setShadowRadius(0);
            d.setTeleportDuration(0);
            d.setInterpolationDuration(0);
            d.setTransformation(transformation(at));
        });
        displays.add(display);
        return display;
    }

    /**
     * Another emote's props (the emote changed while the puppet stood): the old signs go, the new ones come at once
     * for everyone who sees the puppet.
     */
    void props(List<EmoteSpec.Prop> props, Pose pose, boolean planted) {
        while (faces.size() > skinFaces) {
            faces.removeLast();
            sent.removeLast();
            displays.removeLast().remove();
        }
        if (props.isEmpty()) return;
        Matrix4d[] joints = EmoteRig.matrices(pose, planted);
        for (EmoteSpec.Prop prop : props) {
            PuppetModel.Face face = PuppetModel.prop(prop);
            faces.add(face);
            TextDisplay d = add(face, joints);
            for (UUID id : viewers) {
                Player v = plugin.getServer().getPlayer(id);
                if (v != null) v.showEntity(plugin, d);
            }
        }
    }

    private static Transformation transformation(PuppetModel.Placement p) {
        return new Transformation(new Vector3f((float) p.translation().x, (float) p.translation().y, (float) p.translation().z),
                new Quaternionf((float) p.rotation().x, (float) p.rotation().y, (float) p.rotation().z, (float) p.rotation().w),
                new Vector3f((float) p.scale().x, (float) p.scale().y, (float) p.scale().z), new Quaternionf());
    }

    private static boolean same(PuppetModel.Placement a, PuppetModel.Placement b) {
        return a.translation().distanceSquared(b.translation()) < 1e-10
                && Math.abs(a.rotation().dot(b.rotation())) > 1 - 1e-9;
    }

    /** Moves the puppet to a pose over {@code ticks} ticks: only the faces whose transformation changes are sent. */
    void pose(Pose pose, boolean planted, int ticks) {
        Matrix4d[] joints = EmoteRig.matrices(pose, planted);
        for (int i = 0; i < faces.size(); i++) {
            PuppetModel.Face face = faces.get(i);
            PuppetModel.Placement at = PuppetModel.place(face, joints[face.joint().ordinal()], facing, LIFT);
            if (same(at, sent.get(i))) continue;
            TextDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            sent.set(i, at);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(transformation(at));
        }
    }

    /** Shows the puppet to the players who came near and takes it from those who went away. */
    void showTo(Iterable<? extends Player> online, double viewDistance) {
        Set<UUID> seen = new HashSet<>();
        for (Player v : online) {
            if (never.contains(v.getUniqueId()) || !v.getWorld().equals(base.getWorld())) continue;
            double d2 = v.getLocation().distanceSquared(base);
            boolean has = viewers.contains(v.getUniqueId());
            double limit = viewDistance + (has ? SLACK : 0);
            if (d2 <= limit * limit) {
                seen.add(v.getUniqueId());
                if (!has) for (TextDisplay d : displays) v.showEntity(plugin, d);
            } else if (has) {
                for (TextDisplay d : displays) v.hideEntity(plugin, d);
            }
        }
        viewers.clear();
        viewers.addAll(seen);
    }

    boolean valid() {
        for (TextDisplay d : displays) if (!d.isValid()) return false;
        return true;
    }

    void remove() {
        for (TextDisplay d : displays) d.remove();
        displays.clear();
        viewers.clear();
    }
}
