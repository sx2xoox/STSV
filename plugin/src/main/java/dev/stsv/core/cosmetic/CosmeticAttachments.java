package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The cosmetics worn above the head, on the shoulder, on the back and as a tail: one item display per
 * worn cosmetic, showing the cosmetic's model (item transform NONE: the model's (8, 8, 8) is the anchor).
 *
 * <p>Placement: a display's origin is the top of the wearer's hitbox (where the client puts passengers),
 * turned to the wearer's body yaw (the head yaw above the head); the slot's anchor (config
 * cosmetics.anchors, blocks: left, up, front) moves the model from there. Sneaking leans the upper body
 * 0.5 rad forward about the neck, so the shoulder, back and tail models tilt with it.
 *
 * <p>Following the wearer: CosmeticPackets makes the displays ride the wearer on the clients only, so the
 * client moves them with the wearer every frame. On the server they stay ordinary entities next to the
 * wearer (moved along when they drift away, for tracking), because a player with real passengers cannot
 * be teleported to another world by other plugins. Without packet rewriting they are teleported every
 * tick instead (a little behind a running player).
 *
 * <p>The link must reach a client before or with the display: the server sends a new entity to the
 * players around it while it is being added to the world, so a display is spawned visible to nobody,
 * linked, and only then shown. Every 2 seconds the link is sent again to everyone who sees the wearer
 * (a client that lost it for any reason gets it back).
 *
 * <p>Who sees them: everyone who can see the wearer; the wearer themselves only if they turned that on
 * (a display riding the camera player is drawn in first person too, where a shoulder pet covers the view).
 */
final class CosmeticAttachments {

    /** The upper body leans this far forward while sneaking (HumanoidModel: 0.5 rad). */
    static final float SNEAK_TILT = (float) Math.toDegrees(0.5);
    /**
     * How far the neck (the body's pivot) is below the top of the hitbox: standing, the model is 2 blocks
     * x 15/16 and the hitbox 1.8; sneaking, the body pivot drops 3.2 px (x 15/16) and the model is drawn
     * 2 px lower, under a 1.5 hitbox.
     */
    static final double NECK_STAND = 1.8 - 24 * 0.9375 / 16, NECK_SNEAK = 1.5 - (24 * 0.9375 / 16 - 3.2 * 0.9375 / 16 - 0.125);
    /** Ticks between the safety re-sends of the riding link. */
    static final int RELINK_TICKS = 40;
    /** Bobbing above the head: blocks up and down, ticks per half period. */
    static final float BOB = 0.035f;
    static final int BOB_TICKS = 24;

    /** Default anchors (config.yml cosmetics.anchors): blocks from the top of the hitbox, x left, y up, z front. */
    static final Map<CosmeticSlot, Vector3f> DEFAULT_ANCHORS = Map.of(
            CosmeticSlot.ABOVE, new Vector3f(0, 0.10f, 0),
            CosmeticSlot.SHOULDER, new Vector3f(0.38f, -0.39f, 0),
            CosmeticSlot.BACK, new Vector3f(0, -0.62f, -0.12f),
            CosmeticSlot.TAIL, new Vector3f(0, -1.02f, -0.12f));

    /**
     * The way the clients turn a player's body (LivingEntity.tick, then tickHeadTurn): towards the walking
     * direction (backwards walking keeps facing forward), towards the head while swinging, 30% of the way
     * each tick, and never more than 50 degrees (15 while blocking with a shield) from the head. The
     * server's own body yaw for players cannot be used: the server resets a player's previous position
     * just before ticking them, so to it they never walk and the body only follows the head.
     *
     * @param dx, dz how far the player moved since the last tick
     */
    static float nextBody(float body, double dx, double dz, float yaw, boolean swinging, boolean blocking) {
        float target = body;
        if ((float) (dx * dx + dz * dz) > 0.0025f) {
            float walk = (float) Math.atan2(dz, dx) * 57.295776f - 90f;
            float off = Math.abs(wrap(yaw) - walk);
            target = 95f < off && off < 265f ? walk - 180f : walk;
        }
        if (swinging) target = yaw;
        body += wrap(target - body) * 0.3f;
        float d = wrap(yaw - body);
        float max = blocking ? 15f : 50f;
        if (Math.abs(d) > max) body += d - Math.signum(d) * max;
        return body;
    }

    /** Per wearer: the body turn as the clients draw it. */
    private static final class Body {
        double x, z;
        float yaw;
        int swing;
    }

    /** Slots that move with the upper body (body yaw, sneak tilt); the rest follow the head. */
    static boolean onBody(CosmeticSlot slot) {
        return slot == CosmeticSlot.SHOULDER || slot == CosmeticSlot.BACK || slot == CosmeticSlot.TAIL;
    }

    // ------------------------------------------------------------------------------------------
    // Placement (pure)
    // ------------------------------------------------------------------------------------------

    /**
     * The display's translation in its own (turned) frame: x left, y up, z front.
     *
     * @param offsetPx    the cosmetic's own offset, pixels: x right, y up, z back
     * @param sneaking    a body slot while sneaking: the frame is tilted by {@link #SNEAK_TILT} about the
     *                    hitbox top, and this moves the model so it turns about the neck instead
     * @param playerScale the wearer's scale attribute
     */
    static Vector3f translation(Vector3f anchor, Vector offsetPx, boolean sneaking, float playerScale, float bob) {
        Vector3f t = new Vector3f(anchor).add((float) -offsetPx.getX() / 16f, (float) offsetPx.getY() / 16f, (float) -offsetPx.getZ() / 16f);
        if (sneaking) {
            // wanted: neck_sneak + R(d) with d = t - neck_stand (from the top), drawn as top + R(t'):
            // t' = R^-1 (neck_sneak - top) + (top - neck_stand) + t
            double a = Math.toRadians(SNEAK_TILT);
            t.add(0, (float) (NECK_STAND - NECK_SNEAK * Math.cos(a)), (float) (NECK_SNEAK * Math.sin(a)));
        }
        t.add(0, bob, 0);
        return t.mul(playerScale);
    }

    static Transformation transformation(Vector3f anchor, CustomItem.Cosmetic c, boolean sneaking, float playerScale, float bob) {
        float s = c.scale() * playerScale;
        return new Transformation(translation(anchor, c.offset(), sneaking, playerScale, bob),
                new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    // ------------------------------------------------------------------------------------------
    // Live displays
    // ------------------------------------------------------------------------------------------

    private static final class Worn {
        final ItemDisplay display;
        final CustomItem cosmetic;
        boolean sneaking, hidden, bobUp;
        float scale;

        Worn(ItemDisplay display, CustomItem cosmetic) {
            this.display = display;
            this.cosmetic = cosmetic;
        }
    }

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final CosmeticPackets packets;
    private final Predicate<Player> selfView;
    private final Map<UUID, Map<CosmeticSlot, Worn>> worn = new HashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    /** wearer -> ticks at which the link is sent again (right after their displays changed) */
    private final Map<UUID, List<Long>> relinkAt = new HashMap<>();
    private Map<CosmeticSlot, Vector3f> anchors = DEFAULT_ANCHORS;
    private long ticks;

    /**
     * Name tags (nicknames): a text display riding the player on the clients like the models, drawn where the
     * game draws a name tag, seen by everyone who sees the player but never by the player themselves. Hidden while
     * the player is invisible, dead or spectating; faint and not seen through walls while sneaking (as the game's
     * own name tag). The wanted text is kept apart from the display, so a display lost to a respawn, a world change
     * or a teleport is made again by {@link #tick}.
     */
    private static final class Tag {
        final TextDisplay display;
        boolean sneaking;
        float scale;

        Tag(TextDisplay display) {
            this.display = display;
        }
    }

    private final Map<UUID, net.kyori.adventure.text.Component> tagText = new HashMap<>();
    private final Map<UUID, Tag> tags = new HashMap<>();
    /** blocks above the top of the hitbox where the bottom of the tag is (the game's name tag: about 0.25) */
    private float tagHeight = 0.25f;

    /**
     * @param packets  null: no client-side riding, the displays are teleported along every tick
     * @param selfView whether a wearer sees their own displays
     */
    CosmeticAttachments(Plugin plugin, NamespacedKey tag, CosmeticPackets packets, Predicate<Player> selfView) {
        this.plugin = plugin;
        this.tag = tag;
        this.packets = packets;
        this.selfView = selfView;
    }

    void anchors(Map<CosmeticSlot, Vector3f> anchors) {
        this.anchors = Map.copyOf(anchors);
        for (Map.Entry<UUID, Map<CosmeticSlot, Worn>> e : worn.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) e.getValue().forEach((slot, w) -> w.display.setTransformation(transformation(slot, w, p)));
        }
    }

    Vector3f anchor(CosmeticSlot slot) {
        return anchors.getOrDefault(slot, DEFAULT_ANCHORS.getOrDefault(slot, new Vector3f()));
    }

    boolean isAttachment(Entity e) {
        return e.getPersistentDataContainer().has(tag);
    }

    /** What the player wears now outside the head slot (empty: nothing shown). Spawns and removes displays. */
    void set(Player p, Map<CosmeticSlot, CustomItem> wanted) {
        Map<CosmeticSlot, Worn> have = worn.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(CosmeticSlot.class));
        boolean changed = false;
        for (CosmeticSlot slot : new ArrayList<>(have.keySet())) {
            Worn w = have.get(slot);
            CustomItem want = wanted.get(slot);
            if (want == null || !want.key().equals(w.cosmetic.key()) || !w.display.isValid() || w.display.getWorld() != p.getWorld()) {
                w.display.remove();
                have.remove(slot);
                changed = true;
            }
        }
        List<Worn> fresh = new ArrayList<>();
        for (Map.Entry<CosmeticSlot, CustomItem> e : wanted.entrySet()) {
            if (!DEFAULT_ANCHORS.containsKey(e.getKey()) || have.containsKey(e.getKey())) continue;   // head, pet, effects: not here
            Worn w = spawn(p, e.getKey(), e.getValue());
            have.put(e.getKey(), w);
            fresh.add(w);
            changed = true;
        }
        if (have.isEmpty()) worn.remove(p.getUniqueId());
        if (!changed) return;
        // linked first, shown after: the spawn packets then carry the link
        link(p);
        for (Worn w : fresh) {
            for (Player v : p.getWorld().getPlayers()) if (shouldSee(v, p)) v.showEntity(plugin, w.display);
        }
        // and once more shortly after, in case a client got a display before the link
        if (!fresh.isEmpty()) relinkAt.put(p.getUniqueId(), new ArrayList<>(List.of(ticks + 2, ticks + 10)));
    }

    /** The player swung an arm: the clients turn their body to the head for the swing (6 ticks). */
    void swung(Player p) {
        Body b = bodies.get(p.getUniqueId());
        if (b != null) b.swing = 6;
    }

    /** Who sees a wearer's displays: whoever sees the wearer; the wearer only if they turned it on. */
    private boolean shouldSee(Player viewer, Player wearer) {
        return viewer == wearer ? selfView.test(wearer) : viewer.canSee(wearer);
    }

    /** Removes the player's displays (they come back with the next {@link #set}). */
    void remove(Player p) {
        bodies.remove(p.getUniqueId());
        relinkAt.remove(p.getUniqueId());
        Map<CosmeticSlot, Worn> have = worn.remove(p.getUniqueId());
        if (have != null) have.values().forEach(w -> w.display.remove());
        Tag t = tags.remove(p.getUniqueId());
        if (t != null) t.display.remove();
        if (packets != null) packets.link(p.getEntityId(), new int[0], new int[0]);
    }

    void removeAll() {
        for (Map<CosmeticSlot, Worn> have : worn.values()) have.values().forEach(w -> w.display.remove());
        worn.clear();
        tags.values().forEach(t -> t.display.remove());
        tags.clear();
        tagText.clear();
        bodies.clear();
        relinkAt.clear();
    }

    /** Debug: slot -> display entity id. */
    Map<CosmeticSlot, Integer> displays(Player p) {
        Map<CosmeticSlot, Integer> out = new EnumMap<>(CosmeticSlot.class);
        worn.getOrDefault(p.getUniqueId(), Map.of()).forEach((s, w) -> out.put(s, w.display.getEntityId()));
        return out;
    }

    private void link(Player p) {
        if (packets == null) return;
        java.util.stream.IntStream models = worn.getOrDefault(p.getUniqueId(), Map.of()).values().stream().mapToInt(w -> w.display.getEntityId());
        Tag t = tags.get(p.getUniqueId());
        int[] ids = (t == null ? models : java.util.stream.IntStream.concat(models, java.util.stream.IntStream.of(t.display.getEntityId()))).toArray();
        int[] real = p.getPassengers().stream().mapToInt(Entity::getEntityId).toArray();
        packets.link(p.getEntityId(), ids, real);
    }

    private Worn spawn(Player p, CosmeticSlot slot, CustomItem c) {
        boolean sneak = sneaking(p, slot);
        float scale = scale(p);
        Body b = bodies.get(p.getUniqueId());
        ItemDisplay d = p.getWorld().spawn(place(p, slot, sneak, b == null ? p.getBodyYaw() : b.yaw), ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);   // shown once linked (set)
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(c.create(1));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTeleportDuration(packets == null ? 3 : 1);   // turning (and, without riding, moving) is smoothed
            e.setInterpolationDuration(3);
            e.setShadowRadius(0);
            e.setTransformation(transformation(anchor(slot), c.cosmetic(), sneak, scale, 0));
        });
        Worn w = new Worn(d, c);
        w.sneaking = sneak;
        w.scale = scale;
        return w;
    }

    /** Every tick: turn with the wearer, lean when sneaking, hide in poses the models do not fit. */
    void tick() {
        ticks++;
        boolean sweep = ticks % 5 == 0;
        boolean resend = ticks % RELINK_TICKS == 0;
        boolean bobFlip = ticks % BOB_TICKS == 0;
        for (Map.Entry<UUID, Map<CosmeticSlot, Worn>> e : new ArrayList<>(worn.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                e.getValue().values().forEach(w -> w.display.remove());
                worn.remove(e.getKey());
                continue;
            }
            boolean hide = badPose(p);
            float scale = scale(p);
            float body = turnBody(p);
            boolean relink = false;
            for (Map.Entry<CosmeticSlot, Worn> we : e.getValue().entrySet()) {
                CosmeticSlot slot = we.getKey();
                Worn w = we.getValue();
                if (!w.display.isValid() || w.display.getWorld() != p.getWorld()) {
                    relink = true;   // set() recreates it
                    continue;
                }
                if (hide != w.hidden) {
                    w.hidden = hide;
                    w.display.setItemStack(hide ? null : w.cosmetic.create(1));
                }
                boolean sneak = sneaking(p, slot);
                boolean bob = w.cosmetic.cosmetic().bob() && bobFlip;
                if (bob) w.bobUp = !w.bobUp;
                if (sneak != w.sneaking || scale != w.scale || bob) {
                    w.sneaking = sneak;
                    w.scale = scale;
                    w.display.setInterpolationDelay(0);
                    w.display.setInterpolationDuration(bob ? BOB_TICKS : 3);
                    w.display.setTransformation(transformation(slot, w, p));
                }
                Location target = place(p, slot, sneak, body);
                Location now = w.display.getLocation();
                if (packets == null || now.distanceSquared(target) > 1.0) {
                    if (now.distanceSquared(target) > 1e-4 || turned(now, target)) w.display.teleport(target);
                } else if (turned(now, target)) {
                    w.display.setRotation(target.getYaw(), target.getPitch());
                }
                if (sweep) {
                    for (Player v : p.getWorld().getPlayers()) {
                        boolean want = shouldSee(v, p);
                        if (want != v.canSee(w.display)) {
                            if (want) v.showEntity(plugin, w.display);
                            else v.hideEntity(plugin, w.display);
                        }
                    }
                }
            }
            if (relink) {
                Map<CosmeticSlot, CustomItem> wanted = new EnumMap<>(CosmeticSlot.class);
                e.getValue().forEach((slot, w) -> wanted.put(slot, w.cosmetic));
                set(p, wanted);
            } else if (packets != null && (resend || dueRelink(p))) {
                List<Player> viewers = new ArrayList<>(p.getTrackedBy());
                if (selfView.test(p)) viewers.add(p);
                packets.resendLink(p, viewers);
            }
        }
        tickTags(sweep, resend);
    }

    /** The wearer's own view was switched: show or hide their displays to them now. */
    void selfViewChanged(Player p) {
        boolean want = selfView.test(p);
        for (Worn w : worn.getOrDefault(p.getUniqueId(), Map.of()).values()) {
            if (want) p.showEntity(plugin, w.display);
            else p.hideEntity(plugin, w.display);
        }
    }

    private Transformation transformation(CosmeticSlot slot, Worn w, Player p) {
        float bob = w.cosmetic.cosmetic().bob() ? (w.bobUp ? BOB : -BOB) : 0;
        return transformation(anchor(slot), w.cosmetic.cosmetic(), w.sneaking && onBody(slot), scale(p), bob);
    }

    private static boolean turned(Location a, Location b) {
        return Math.abs(wrap(a.getYaw() - b.getYaw())) > 0.5f || Math.abs(a.getPitch() - b.getPitch()) > 0.5f;
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private boolean dueRelink(Player p) {
        List<Long> due = relinkAt.get(p.getUniqueId());
        if (due == null || !due.remove(ticks)) return false;
        if (due.isEmpty()) relinkAt.remove(p.getUniqueId());
        return true;
    }

    /** Advances the wearer's body turn by one tick (see {@link #nextBody}); riding: the vehicle's. */
    private float turnBody(Player p) {
        Location l = p.getLocation();
        Body b = bodies.get(p.getUniqueId());
        if (b == null) {
            b = new Body();
            b.x = l.getX();
            b.z = l.getZ();
            b.yaw = p.getBodyYaw();
            bodies.put(p.getUniqueId(), b);
        }
        Entity vehicle = p.getVehicle();
        if (vehicle != null) {
            // horses turn their rider's body with theirs, boats and the rest to their own facing
            b.yaw = vehicle instanceof org.bukkit.entity.LivingEntity le ? le.getBodyYaw() : vehicle.getYaw();
        } else {
            b.yaw = nextBody(b.yaw, l.getX() - b.x, l.getZ() - b.z, l.getYaw(), b.swing > 0, p.isBlocking());
        }
        if (b.swing > 0) b.swing--;
        b.x = l.getX();
        b.z = l.getZ();
        return b.yaw;
    }

    /** Where the display stands: the top of the hitbox (the clients' passenger point), facing with the wearer. */
    private static Location place(Player p, CosmeticSlot slot, boolean sneaking, float body) {
        Location l = p.getLocation();
        l.add(0, p.getHeight(), 0);
        l.setYaw(onBody(slot) ? body : l.getYaw());
        l.setPitch(sneaking && onBody(slot) ? SNEAK_TILT : 0);
        return l;
    }

    private static boolean sneaking(Player p, CosmeticSlot slot) {
        return onBody(slot) && p.getPose() == org.bukkit.entity.Pose.SNEAKING;
    }

    private static float scale(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        return a == null ? 1f : (float) a.getValue();
    }

    /** Lying, swimming, gliding, spinning, sleeping: the models would stick out of the body. */
    static boolean badPose(Player p) {
        return switch (p.getPose()) {
            case SWIMMING, FALL_FLYING, SLEEPING, SPIN_ATTACK, DYING -> true;
            default -> p.isGliding() || p.isRiptiding() || p.isSleeping();
        };
    }

    // ------------------------------------------------------------------------------------------
    // Name tags
    // ------------------------------------------------------------------------------------------

    void tagHeight(float blocks) {
        this.tagHeight = blocks;
        for (Tag t : tags.values()) t.display.setTransformation(tagTransformation(t.scale));
    }

    /** The name tag above the player (null: none). */
    void nameTag(Player p, net.kyori.adventure.text.Component text) {
        UUID id = p.getUniqueId();
        if (text == null) {
            tagText.remove(id);
            Tag t = tags.remove(id);
            if (t != null) {
                t.display.remove();
                link(p);
            }
            return;
        }
        tagText.put(id, text);
        Tag t = tags.get(id);
        if (t != null && t.display.isValid() && t.display.getWorld() == p.getWorld()) {
            t.display.text(text);
            return;
        }
        if (!p.isDead()) spawnTag(p, text);
    }

    /** Debug: the name tag's display id, -1 none. */
    int tagDisplay(Player p) {
        Tag t = tags.get(p.getUniqueId());
        return t == null ? -1 : t.display.getEntityId();
    }

    /** The game shows no name tag on invisible players; dead and spectating players show none to others either. */
    static boolean tagHidden(Player p) {
        return p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.isInvisible()
                || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
    }

    private boolean shouldSeeTag(Player viewer, Player owner) {
        return viewer != owner && viewer.canSee(owner) && !tagHidden(owner);
    }

    private Transformation tagTransformation(float playerScale) {
        return new Transformation(new Vector3f(0, tagHeight * playerScale, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f());
    }

    /** A sneaking player's tag: faint, as the game draws it. */
    static final byte SNEAK_TAG_OPACITY = (byte) 0x50;

    private void spawnTag(Player p, net.kyori.adventure.text.Component text) {
        Tag old = tags.remove(p.getUniqueId());
        if (old != null) old.display.remove();
        float scale = scale(p);
        boolean sneak = p.isSneaking();
        TextDisplay d = p.getWorld().spawn(p.getLocation().add(0, p.getHeight(), 0), TextDisplay.class, e -> {
            e.setVisibleByDefault(false);   // shown once linked
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.text(text);
            e.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setDefaultBackground(true);   // the game's name tag background
            e.setShadowed(false);
            e.setSeeThrough(!sneak);
            e.setTextOpacity(sneak ? SNEAK_TAG_OPACITY : (byte) -1);
            e.setTeleportDuration(packets == null ? 3 : 1);
            e.setShadowRadius(0);
            e.setTransformation(tagTransformation(scale));
        });
        Tag t = new Tag(d);
        t.sneaking = sneak;
        t.scale = scale;
        tags.put(p.getUniqueId(), t);
        link(p);
        for (Player v : p.getWorld().getPlayers()) if (shouldSeeTag(v, p)) v.showEntity(plugin, d);
        relinkAt.put(p.getUniqueId(), new ArrayList<>(List.of(ticks + 2, ticks + 10)));
    }

    private void tickTags(boolean sweep, boolean resend) {
        for (Map.Entry<UUID, net.kyori.adventure.text.Component> e : new ArrayList<>(tagText.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                tagText.remove(e.getKey());
                Tag gone = tags.remove(e.getKey());
                if (gone != null) gone.display.remove();
                continue;
            }
            Tag t = tags.get(e.getKey());
            if (t == null || !t.display.isValid() || t.display.getWorld() != p.getWorld()) {
                if (sweep && !p.isDead()) spawnTag(p, e.getValue());
                continue;
            }
            TextDisplay d = t.display;
            boolean sneak = p.isSneaking();
            if (sneak != t.sneaking) {
                t.sneaking = sneak;
                d.setSeeThrough(!sneak);
                d.setTextOpacity(sneak ? SNEAK_TAG_OPACITY : (byte) -1);
            }
            float scale = scale(p);
            if (scale != t.scale) {
                t.scale = scale;
                d.setTransformation(tagTransformation(scale));
            }
            Location target = p.getLocation().add(0, p.getHeight(), 0);
            double drift = d.getLocation().distanceSquared(target);
            if ((packets == null && drift > 1e-4) || drift > 1.0) d.teleport(target);
            if (sweep) {
                for (Player v : p.getWorld().getPlayers()) {
                    boolean want = shouldSeeTag(v, p);
                    if (want != v.canSee(d)) {
                        if (want) v.showEntity(plugin, d);
                        else v.hideEntity(plugin, d);
                    }
                }
            }
            // the models' loop re-sends the link of players who wear models; this one the rest
            if (packets != null && !worn.containsKey(e.getKey()) && (resend || dueRelink(p))) packets.resendLink(p, new ArrayList<>(p.getTrackedBy()));
        }
    }


    // ------------------------------------------------------------------------------------------
    // Wardrobe mannequin (not riding anything: placed at its hitbox top, turned with it)
    // ------------------------------------------------------------------------------------------

    /** A display for the wardrobe mannequin, visible to nobody until shown. */
    ItemDisplay spawnPreview(Location mannequinTop, float bodyYaw, CosmeticSlot slot, CustomItem c) {
        Location at = mannequinTop.clone();
        at.setYaw(onBody(slot) ? bodyYaw : at.getYaw());
        at.setPitch(0);
        return at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(c.create(1));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTeleportDuration(2);
            e.setShadowRadius(0);
            e.setTransformation(transformation(anchor(slot), c.cosmetic(), false, 1f, 0));
        });
    }

    /** Parses cosmetics.anchors (a missing or broken entry keeps the default, reported in problems). */
    static Map<CosmeticSlot, Vector3f> anchorsFrom(org.bukkit.configuration.ConfigurationSection s, List<String> problems) {
        Map<CosmeticSlot, Vector3f> out = new EnumMap<>(CosmeticSlot.class);
        out.putAll(DEFAULT_ANCHORS);
        if (s == null) return out;
        for (CosmeticSlot slot : DEFAULT_ANCHORS.keySet()) {
            if (!s.contains(slot.id)) continue;
            List<Double> v = s.getDoubleList(slot.id);
            if (v.size() != 3) {
                problems.add("cosmetics.anchors." + slot.id + ": [왼쪽, 위, 앞] 숫자 3개");
                continue;
            }
            out.put(slot, new Vector3f(v.get(0).floatValue(), v.get(1).floatValue(), v.get(2).floatValue()));
        }
        return out;
    }
}
