package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Pose;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
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
 * A puppet in the world: one text display per face ({@link PuppetModel}), all at one fixed place (at the eye height
 * over the feet, {@link #LIFT}) with yaw and pitch 0. Only their transformations move: every pose is sent as the
 * translation and rotation of the faces that changed, and the clients blend to it over the step's ticks. The text of
 * a face, the heavy part, is sent when a player starts seeing it, and again only when its shade has to change.
 *
 * <p>A puppet can also ride its player ({@link #riding}: the emote in the player's own body). Then the faces ride a
 * carrier, an empty display that the clients carry on the player's head top (CosmeticModule.carry: every frame, no
 * packets), their transformations are in the body's own frame, and the whole puppet turns by the displays' yaw.
 *
 * <p>What makes it look like a player and not like a cut-out ({@link PuppetLight}): its faces are shaded by where
 * they look in the world, as the game's entity shader shades a player's model; it is lit by the light at the eye of
 * the player it stands for ({@link #light}); it casts the round shadow a player casts ({@link #shadow}: the hidden
 * player casts none, the game draws no shadow under an invisible entity); it holds what its player holds
 * ({@link #hold}).
 *
 * <p>The displays are not saved (a crash leaves none), carry a tag (leftovers of a reload are removed by it), and
 * are shown player by player: to those within {@code viewDistance} who could see the emoting player.
 */
final class Puppet {

    /**
     * The displays of a standing puppet are this far over its feet: a standing player's eye. A display is lit by the
     * block its origin is in, a player by the block their eye is in (Entity.getLightProbePosition): so it is the same
     * block, and the clients light the puppet as they would light the player, with nothing sent.
     */
    static final double LIFT = PuppetLight.EYE;
    /** A riding puppet turns with its player when they have turned this far (degrees; a turn is a packet a face). */
    static final float TURN_STEP = 2f;
    /** A face's text is sent again (in its new shade) once its shade is this far from the one it was sent in. */
    static final double RESHADE = 0.06;
    /** At most this many faces are sent in a new shade at a time (the rest at the next pose or turn). */
    static final int RESHADE_FACES = 4;
    /** The player's shadow (AvatarRenderer: radius 0.5; EntityRenderer: strength 1). */
    static final float SHADOW_RADIUS = 0.5f, SHADOW_STRENGTH = 1f;
    /** A riding puppet's shadow is moved to its player's feet and glided there over this many ticks (as the clients glide a player). */
    static final int SHADOW_GLIDE = 3;
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
    /** the light map lookups of the nether (its second light is from below) */
    private final boolean nether, slim;

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
    /** the shade each face's text was sent in */
    private final List<Double> shades = new ArrayList<>();
    /** the joints of the pose last sent */
    private Matrix4d[] joints;
    /** the joints the faces are shaded by (an emote's poses, the shade averaged over them); null: the pose last sent */
    private List<Matrix4d[]> shading;
    /** what the hands hold: an item display on the right arm and one on the left, what they show, where they were put */
    private final ItemDisplay[] hands = new ItemDisplay[2];
    private final ItemStack[] held = {ItemStack.empty(), ItemStack.empty()};
    private final PuppetModel.Placement[] handSent = new PuppetModel.Placement[2];
    /** the shadow caster at the feet (not riding anything: a shadow is cast from an entity's own place) */
    private final ItemDisplay shadow;
    /** the brightness the displays were given ({@link PuppetLight#pack}ed), or {@link PuppetLight#NATURAL} */
    private int brightness = PuppetLight.NATURAL;
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
        this(plugin, tag, feet, LIFT, bodyYaw, false, skin, pose, planted, props, never, 0);
    }

    /**
     * A puppet that rides its player: it stands where they stand, as high as they are, and looks where they look.
     *
     * @param height the player's height (their passenger point: where the clients carry the puppet's carrier)
     */
    static Puppet riding(Plugin plugin, NamespacedKey tag, Location feet, double height, PuppetSkin skin, Pose pose, boolean planted,
                         List<EmoteSpec.Prop> props, Set<UUID> never) {
        return new Puppet(plugin, tag, feet, height, feet.getYaw(), true, skin, pose, planted, props, never, SHADOW_GLIDE);
    }

    /**
     * A puppet that rides something else (a vehicle's seat, {@link RiderPuppet}): its carrier rides the mount, so the
     * clients carry it with the mount's own glide, frame by frame; its feet {@code lift} blocks under the mount's point.
     */
    static Puppet mounted(Plugin plugin, NamespacedKey tag, Entity mount, double lift, float yaw, PuppetSkin skin, Pose pose,
                          boolean planted, Set<UUID> never) {
        Location at = mount.getLocation();
        Location feet = new Location(at.getWorld(), at.getX(), at.getY() - lift, at.getZ(), yaw, 0);
        int glide = mount instanceof Display d ? d.getTeleportDuration() : 1;
        Puppet puppet = new Puppet(plugin, tag, feet, lift, yaw, true, skin, pose, planted, List.of(), never, glide);
        if (puppet.carrier != null) mount.addPassenger(puppet.carrier);
        puppet.near = at;
        return puppet;
    }

    /** Mounted: whether its carrier still rides this mount. */
    boolean rides(Entity mount) {
        return carrier != null && carrier.isValid() && mount.equals(carrier.getVehicle());
    }

    /** Mounted: keeps who sees it measured from the mount (it is not moved by the server: the mount carries it). */
    void near(Location at) {
        near = at.clone();
    }

    private Puppet(Plugin plugin, NamespacedKey tag, Location feet, double lift, float bodyYaw, boolean riding, PuppetSkin skin, Pose pose,
                   boolean planted, List<EmoteSpec.Prop> props, Set<UUID> never, int shadowGlide) {
        this.plugin = plugin;
        this.tag = tag;
        this.lift = lift;
        this.yaw = riding ? bodyYaw : 0;
        World world = feet.getWorld();
        this.nether = world.getEnvironment() == World.Environment.NETHER;
        this.slim = skin.slim();
        this.base = new Location(world, feet.getX(), feet.getY() + lift, feet.getZ(), yaw, 0);
        this.facing = riding ? 0 : -Math.toRadians(bodyYaw);
        this.carrier = !riding ? null : world.spawn(new Location(world, base.getX(), base.getY(), base.getZ(), 0, 0), ItemDisplay.class, d -> {
            mark(d);
            d.setTeleportDuration(1);
        });
        this.shadow = world.spawn(new Location(world, feet.getX(), feet.getY(), feet.getZ(), 0, 0), ItemDisplay.class, d -> {
            mark(d);
            d.setShadowRadius(SHADOW_RADIUS);
            d.setShadowStrength(SHADOW_STRENGTH);
            d.setTeleportDuration(Math.clamp(shadowGlide, 0, 59));
        });
        this.faces = new ArrayList<>(PuppetModel.faces(skin));
        this.skinFaces = faces.size();
        this.never = never;
        this.joints = EmoteRig.matrices(pose, planted);
        for (int i = 0; i < faces.size(); i++) add(faces.get(i), shadeOf(i));
        for (EmoteSpec.Prop prop : props) {
            PuppetModel.Face face = PuppetModel.prop(prop);
            faces.add(face);
            add(face, face.shade());
        }
        for (int k = 0; k < 2; k++) hands[k] = addHand(k == 0);
    }

    /** Not saved, hidden until shown, tagged as the emotes' own. */
    private void mark(Display d) {
        d.setPersistent(false);
        d.setVisibleByDefault(false);
        d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
    }

    /** Where the displays stand now, turned as they are. */
    private Location origin() {
        Location where = carrier == null ? base.clone() : carrier.getLocation();
        where.setYaw(yaw);
        where.setPitch(0);
        return where;
    }

    private TextDisplay add(PuppetModel.Face face, double shade) {
        PuppetModel.Placement at = PuppetModel.place(face, joints[face.joint().ordinal()], facing, lift);
        sent.add(at);
        shades.add(shade);
        TextDisplay display = base.getWorld().spawn(origin(), TextDisplay.class, d -> {
            mark(d);
            d.text(PuppetText.component(face, shade));
            d.setLineWidth(PuppetText.LINE_WIDTH);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setDefaultBackground(false);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setShadowRadius(0);
            d.setBrightness(givenBrightness());
            d.setTeleportDuration(carrier == null ? 0 : 1);   // riding: a turn is blended over a tick
            d.setInterpolationDuration(0);
            d.setTransformation(transformation(at));
        });
        if (carrier != null) carrier.addPassenger(display);
        displays.add(display);
        return display;
    }

    private ItemDisplay addHand(boolean right) {
        PuppetModel.Placement at = handAt(right);
        handSent[right ? 0 : 1] = at;
        ItemDisplay display = base.getWorld().spawn(origin(), ItemDisplay.class, d -> {
            mark(d);
            d.setItemDisplayTransform(right ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
                    : ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND);
            d.setShadowRadius(0);
            d.setBrightness(givenBrightness());
            d.setTeleportDuration(carrier == null ? 0 : 1);
            d.setInterpolationDuration(0);
            d.setTransformation(transformation(at));
        });
        if (carrier != null) carrier.addPassenger(display);
        return display;
    }

    private PuppetModel.Placement handAt(boolean right) {
        return PuppetModel.hand(joints[(right ? EmoteRig.Joint.ARM_R : EmoteRig.Joint.ARM_L).ordinal()], right, slim, facing, lift);
    }

    /** The displays' own turn now (radians about +y): a standing puppet's is in its transformations, a riding one's is its yaw. */
    private double turnNow() {
        return carrier == null ? facing : -Math.toRadians(yaw);
    }

    /** The shade a face is to be drawn in now: by where it looks in the world (props are not shaded: a sign is meant to be seen). */
    private double shadeOf(int i) {
        PuppetModel.Face face = faces.get(i);
        if (i >= skinFaces) return face.shade();
        double turn = turnNow(), sum = 0;
        List<Matrix4d[]> by = shading != null ? shading : List.<Matrix4d[]>of(joints);
        for (Matrix4d[] m : by) sum += PuppetLight.diffuse(PuppetModel.worldNormal(face, m[face.joint().ordinal()], turn), nether);
        return sum / by.size();
    }

    /** The faces are shaded by these poses (an emote's round: the shade averaged over it, so it does not flicker), or by the pose last sent (null). */
    void shading(List<Pose> poses, boolean planted) {
        if (poses == null || poses.isEmpty()) shading = null;
        else {
            shading = new ArrayList<>();
            for (Pose p : poses) shading.add(EmoteRig.matrices(p, planted));
        }
        reshade(Integer.MAX_VALUE);
    }

    /**
     * Sends the text of the faces whose shade is {@link #RESHADE} or more off again, the furthest off first, at most
     * {@code most} of them.
     */
    private void reshade(int most) {
        List<double[]> off = new ArrayList<>();
        for (int i = 0; i < skinFaces; i++) {
            double s = shadeOf(i);
            double d = Math.abs(s - shades.get(i));
            if (d >= RESHADE) off.add(new double[]{d, i, s});
        }
        off.sort((a, b) -> Double.compare(b[0], a[0]));
        for (int k = 0; k < Math.min(most, off.size()); k++) {
            int i = (int) off.get(k)[1];
            double s = off.get(k)[2];
            TextDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            d.text(PuppetText.component(faces.get(i), s));
            shades.set(i, s);
        }
    }

    /**
     * Another emote's props (the emote changed while the puppet stood): the old signs go, the new ones come at once
     * for everyone who sees the puppet.
     */
    void props(List<EmoteSpec.Prop> props, Pose pose, boolean planted) {
        while (faces.size() > skinFaces) {
            faces.removeLast();
            sent.removeLast();
            shades.removeLast();
            displays.removeLast().remove();
        }
        if (props.isEmpty()) return;
        joints = EmoteRig.matrices(pose, planted);
        for (EmoteSpec.Prop prop : props) {
            PuppetModel.Face face = PuppetModel.prop(prop);
            faces.add(face);
            TextDisplay d = add(face, face.shade());
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

    private static void glide(Display d, PuppetModel.Placement at, int ticks) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(transformation(at));
    }

    /** Moves the puppet to a pose over {@code ticks} ticks: only the faces whose transformation changes are sent. */
    void pose(Pose pose, boolean planted, int ticks) {
        joints = EmoteRig.matrices(pose, planted);
        for (int i = 0; i < faces.size(); i++) {
            PuppetModel.Face face = faces.get(i);
            PuppetModel.Placement at = PuppetModel.place(face, joints[face.joint().ordinal()], facing, lift);
            if (same(at, sent.get(i))) continue;
            TextDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            sent.set(i, at);
            glide(d, at, ticks);
        }
        for (int k = 0; k < 2; k++) {
            PuppetModel.Placement at = handAt(k == 0);
            if (same(at, handSent[k]) || !hands[k].isValid()) continue;
            handSent[k] = at;
            if (!held[k].isEmpty()) glide(hands[k], at, ticks);
            else hands[k].setTransformation(transformation(at));   // nothing to see: no glide to send
        }
        reshade(RESHADE_FACES);
    }

    /**
     * What the hands hold: the main hand's item in the right hand (the left for a left-handed player), the off hand's
     * in the other. Sent only when it changed.
     */
    void hold(ItemStack main, ItemStack off, boolean leftHanded) {
        ItemStack[] want = {leftHanded ? off : main, leftHanded ? main : off};
        for (int k = 0; k < 2; k++) {
            ItemStack w = want[k] == null || want[k].isEmpty() ? ItemStack.empty() : want[k];
            if (w.equals(held[k]) || !hands[k].isValid()) continue;
            held[k] = w.clone();
            hands[k].setItemStack(held[k]);
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
        for (Display d : riders()) {
            if (!d.isValid() || d.getVehicle() == carrier) continue;
            Location at = to.clone();
            at.setYaw(yaw);
            d.teleport(at);
            carrier.addPassenger(d);
        }
    }

    /** The faces and the hands: what rides the carrier. */
    private List<Display> riders() {
        List<Display> out = new ArrayList<>(displays);
        for (ItemDisplay h : hands) if (h != null) out.add(h);
        return out;
    }

    /**
     * The shadow follows the feet (riding and mounted puppets: the shadow cannot ride with them, a rider is drawn at
     * its vehicle's passenger point, the head top or the seat, and a shadow falls only half a block down). Moved when
     * it moved; the clients glide it there as they glide a player.
     */
    void shadow(Location feet) {
        if (!shadow.isValid() || !feet.getWorld().equals(shadow.getWorld())) return;
        if (shadow.getLocation().distanceSquared(feet) < 1e-6) return;
        Location to = feet.clone();
        to.setYaw(0);
        to.setPitch(0);
        shadow.teleport(to);
    }

    /**
     * Lights the puppet as the player it stands for would be lit: by the block their eye is in. Where the block the
     * clients light the displays by (their origin's) has the same light, nothing is set and the clients' own light is
     * used, frame by frame; else the eye block's levels are given to every display (sent only when they change).
     *
     * @param eye where the eye of the player it stands for is
     */
    void light(Location eye) {
        Location probe = carrier == null ? base : near != null ? near : carrier.getLocation();
        if (probe.getWorld() == null || !probe.getWorld().equals(eye.getWorld())) return;
        int want = PuppetLight.override(lightAt(probe), lightAt(eye));
        if (want == brightness) return;
        brightness = want;
        Display.Brightness b = givenBrightness();
        for (Display d : riders()) if (d.isValid()) d.setBrightness(b);
    }

    private Display.Brightness givenBrightness() {
        return brightness == PuppetLight.NATURAL ? null : new Display.Brightness(PuppetLight.block(brightness), PuppetLight.sky(brightness));
    }

    private static int lightAt(Location at) {
        Block b = at.getBlock();
        return PuppetLight.pack(b.getLightFromBlocks(), b.getLightFromSky());
    }

    /** Riding: the whole puppet looks where its player's body looks (sent when it has turned {@link #TURN_STEP}). */
    void turn(float to) {
        if (carrier == null || !EmoteRules.turned(yaw, to, TURN_STEP)) return;
        yaw = to;
        for (Display d : riders()) if (d.isValid()) d.setRotation(to, 0);
        reshade(RESHADE_FACES);
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
                for (Display d : riders()) v.showEntity(plugin, d);
                v.showEntity(plugin, shadow);
            } else if (has) {
                for (Display d : riders()) v.hideEntity(plugin, d);
                v.hideEntity(plugin, shadow);
                if (carrier != null) v.hideEntity(plugin, carrier);
            }
        }
        viewers.clear();
        viewers.addAll(seen);
    }

    boolean valid() {
        if (carrier != null && !carrier.isValid()) return false;
        for (Display d : riders()) if (!d.isValid() || (carrier != null && d.getVehicle() != carrier)) return false;
        return true;
    }

    void remove() {
        for (Display d : riders()) d.remove();
        shadow.remove();
        if (carrier != null) carrier.remove();
        displays.clear();
        viewers.clear();
    }
}
