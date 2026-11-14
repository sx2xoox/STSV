package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Pose;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
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
 * <p>A puppet can also ride its player ({@link #riding}: the emote in the player's own body). Then the faces ride a
 * carrier, an empty display that the clients carry on the player's head top (CosmeticModule.carry: every frame, no
 * packets), their transformations are in the body's own frame, and the whole puppet turns by the displays' yaw.
 *
 * <p>The displays are not saved (a crash leaves none), carry a tag (leftovers of a reload are removed by it), and
 * are shown player by player: to those within {@code viewDistance} who could see the emoting player.
 */
final class Puppet {

    /** The displays of a standing puppet are this far over its feet. */
    static final double LIFT = 1.0;
    /** A riding puppet turns with its player when they have turned this far (degrees; a turn is a packet a face). */
    static final float TURN_STEP = 2f;
    /** A carrier further than this from its player (blocks, squared) is put back on the server too. */
    private static final double DRIFT_SQUARED = 1.0;
    /** A player this much further than the view distance stops seeing the puppet (so the edge does not flicker). */
    private static final double SLACK = 8;

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Location base;
    private final double facing, lift;
    /** riding: what the faces ride (null: a standing puppet), and the yaw they were last given */
    private final ItemDisplay carrier;
    private float yaw;
    /** riding: where the player is now (who is near enough to see the puppet is measured from here) */
    private Location near;

    /**
     * "Keep what rides it" for moving the carrier. Newer servers move an entity with its passengers anyway and are
     * dropping the flag: it is looked up by name, and left out where it is gone.
     */
    private static final io.papermc.paper.entity.TeleportFlag[] KEEP_RIDERS = keepRiders();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static io.papermc.paper.entity.TeleportFlag[] keepRiders() {
        try {
            Class<?> states = Class.forName("io.papermc.paper.entity.TeleportFlag$EntityState");
            return new io.papermc.paper.entity.TeleportFlag[]{(io.papermc.paper.entity.TeleportFlag) Enum.valueOf((Class) states, "RETAIN_PASSENGERS")};
        } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException e) {
            return new io.papermc.paper.entity.TeleportFlag[0];
        }
    }
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
        this(plugin, tag, feet, LIFT, bodyYaw, false, skin, pose, planted, props, never);
    }

    /**
     * A puppet that rides its player: it stands where they stand, as high as they are, and looks where they look.
     *
     * @param height the player's height (their passenger point: where the clients carry the puppet's carrier)
     */
    static Puppet riding(Plugin plugin, NamespacedKey tag, Location feet, double height, PuppetSkin skin, Pose pose, boolean planted,
                         List<EmoteSpec.Prop> props, Set<UUID> never) {
        return new Puppet(plugin, tag, feet, height, feet.getYaw(), true, skin, pose, planted, props, never);
    }

    private Puppet(Plugin plugin, NamespacedKey tag, Location feet, double lift, float bodyYaw, boolean riding, PuppetSkin skin, Pose pose,
                   boolean planted, List<EmoteSpec.Prop> props, Set<UUID> never) {
        this.plugin = plugin;
        this.tag = tag;
        this.lift = lift;
        this.yaw = riding ? bodyYaw : 0;
        this.base = new Location(feet.getWorld(), feet.getX(), feet.getY() + lift, feet.getZ(), yaw, 0);
        this.facing = riding ? 0 : -Math.toRadians(bodyYaw);
        this.carrier = !riding ? null : base.getWorld().spawn(new Location(base.getWorld(), base.getX(), base.getY(), base.getZ(), 0, 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            d.setTeleportDuration(1);
        });
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
        PuppetModel.Placement at = PuppetModel.place(face, joints[face.joint().ordinal()], facing, lift);
        sent.add(at);
        Location where = carrier == null ? base : carrier.getLocation();
        where.setYaw(yaw);
        TextDisplay display = base.getWorld().spawn(where, TextDisplay.class, d -> {
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
            d.setTeleportDuration(carrier == null ? 0 : 1);   // riding: a turn is blended over a tick
            d.setInterpolationDuration(0);
            d.setTransformation(transformation(at));
        });
        if (carrier != null) carrier.addPassenger(display);
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
            PuppetModel.Placement at = PuppetModel.place(face, joints[face.joint().ordinal()], facing, lift);
            if (same(at, sent.get(i))) continue;
            TextDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            sent.set(i, at);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(transformation(at));
        }
    }

    /** What rides the player on the clients (null: a standing puppet). */
    ItemDisplay carrier() {
        return carrier;
    }

    /**
     * Riding: keeps the puppet at its player on the server. The clients carry it themselves; the server's copy only
     * has to be near enough for who is sent what, so it is moved a block at a time. Without the clients' carrying
     * it is moved every tick (and lags a little behind a running player).
     *
     * @param top     the player's head top (their place plus their height)
     * @param carried whether the clients carry the puppet (CosmeticModule.carry)
     */
    void follow(Location top, boolean carried) {
        if (carrier == null || !carrier.isValid() || !top.getWorld().equals(carrier.getWorld())) return;
        near = top.clone();
        double drift = carrier.getLocation().distanceSquared(top);
        if (drift <= (carried ? DRIFT_SQUARED : 1e-4)) return;
        Location to = top.clone();
        to.setYaw(0);
        to.setPitch(0);
        if (!carrier.teleport(to, KEEP_RIDERS)) {
            // a server that moves nothing with riders on it: off, over, and on again below
            carrier.eject();
            carrier.teleport(to);
        }
        for (TextDisplay d : displays) {
            if (!d.isValid() || d.getVehicle() == carrier) continue;
            Location at = to.clone();
            at.setYaw(yaw);
            d.teleport(at);
            carrier.addPassenger(d);
        }
    }

    /** Riding: the whole puppet looks where its player looks (sent when they have turned {@link #TURN_STEP}). */
    void turn(float to) {
        if (carrier == null || !EmoteRules.turned(yaw, to, TURN_STEP)) return;
        yaw = to;
        for (TextDisplay d : displays) if (d.isValid()) d.setRotation(to, 0);
    }

    /** Shows the puppet to the players who came near and takes it from those who went away. */
    void showTo(Iterable<? extends Player> online, double viewDistance) {
        Set<UUID> seen = new HashSet<>();
        Location here = carrier == null ? base : near != null ? near : carrier.getLocation();
        for (Player v : online) {
            if (never.contains(v.getUniqueId()) || !v.getWorld().equals(here.getWorld())) continue;
            double d2 = v.getLocation().distanceSquared(here);
            boolean has = viewers.contains(v.getUniqueId());
            double limit = viewDistance + (has ? SLACK : 0);
            if (d2 <= limit * limit) {
                seen.add(v.getUniqueId());
                if (has) continue;
                // the carrier first: a client must know what the faces ride before it is told that they ride it
                if (carrier != null) v.showEntity(plugin, carrier);
                for (TextDisplay d : displays) v.showEntity(plugin, d);
            } else if (has) {
                for (TextDisplay d : displays) v.hideEntity(plugin, d);
                if (carrier != null) v.hideEntity(plugin, carrier);
            }
        }
        viewers.clear();
        viewers.addAll(seen);
    }

    boolean valid() {
        if (carrier != null && !carrier.isValid()) return false;
        for (TextDisplay d : displays) if (!d.isValid() || (carrier != null && d.getVehicle() != carrier)) return false;
        return true;
    }

    void remove() {
        for (TextDisplay d : displays) d.remove();
        if (carrier != null) carrier.remove();
        displays.clear();
        viewers.clear();
    }
}
