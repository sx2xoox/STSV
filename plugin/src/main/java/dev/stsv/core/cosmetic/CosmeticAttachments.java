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
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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
            CosmeticSlot.TAIL, new Vector3f(0, -1.02f, -0.12f),
            CosmeticSlot.NECK, new Vector3f(0, -0.47f, 0),
            CosmeticSlot.WAIST, new Vector3f(0, -1.00f, 0),
            CosmeticSlot.AURA, new Vector3f(0, -0.90f, 0));   // a model aura (cosmetic.worn) circles the body here

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
        return slot == CosmeticSlot.SHOULDER || slot == CosmeticSlot.BACK || slot == CosmeticSlot.TAIL
                || slot == CosmeticSlot.NECK || slot == CosmeticSlot.WAIST || slot == CosmeticSlot.AURA;
    }

    /** Whether this cosmetic is a model carried on the body (an aura only when it has a worn model). */
    static boolean carried(CustomItem c) {
        CosmeticSlot slot = c.cosmetic().slot();
        if (!DEFAULT_ANCHORS.containsKey(slot)) return false;
        return slot != CosmeticSlot.AURA || (c.cosmetic().premium() != null && c.cosmetic().premium().worn() != null);
    }

    /**
     * The item a worn display shows: the cosmetic's own item, its worn model (a model aura), or its awakened model
     * while its whole set is worn.
     */
    static ItemStack look(CustomItem c, boolean awake) {
        ItemStack it = c.create(1);
        CustomItem.Premium pr = c.cosmetic().premium();
        NamespacedKey model = pr == null ? null : awake && pr.awakened() != null ? pr.awakened() : pr.worn();
        NamespacedKey shown = model != null ? model : c.itemModelKey();
        if (shown != null) it.editMeta(m -> m.setItemModel(shown));
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // Placement (pure)
    // ------------------------------------------------------------------------------------------

    /**
     * The display's translation in its own (turned) frame: x left, y up, z front.
     *
     * @param offsetPx    the cosmetic's own offset, pixels: x right, y up, z back
     * @param sneaking    a body slot while sneaking: the frame is tilted by {@link #SNEAK_TILT} about the
     *                    hitbox top (in {@link #transformation}, which turns this translation and the model by
     *                    the lean), and this moves the model so it turns about the neck instead
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
        return transformation(anchor, c, sneaking, playerScale, bob, 0);
    }

    /**
     * @param phase where the cosmetic's motion is (0, 1, 2 ... a step of {@link #motionSteps} each; 0 = at rest):
     *              SPIN turns by a third of a turn a step, SWAY and FLAP and PULSE swing between their two ends
     */
    static Transformation transformation(Vector3f anchor, CustomItem.Cosmetic c, boolean sneaking, float playerScale, float bob, int phase) {
        float s = c.scale() * playerScale;
        AxisAngle4f turn = new AxisAngle4f();
        Vector3f scale = new Vector3f(s, s, s);
        CustomItem.Motion m = c.motion();
        if (m != null && phase != 0) {
            switch (m.kind()) {
                case SPIN -> turn = new AxisAngle4f((float) (phase % 3 * Math.PI * 2 / 3), 0, 1, 0);
                case SWAY -> turn = new AxisAngle4f((float) Math.toRadians(phase % 2 == 1 ? m.amount() : -m.amount()), 0, 0, 1);
                case FLAP -> scale.x *= phase % 2 == 1 ? 1 - m.amount() : 1;
                case PULSE -> scale.mul(phase % 2 == 1 ? 1 + m.amount() : 1 - m.amount() / 2);
                case ORBIT -> turn = new AxisAngle4f((float) (phase % 4 * Math.PI / 2), 0, 1, 0);
            }
        }
        Vector3f t = translation(anchor, c.offset(), sneaking, playerScale, bob);
        // an orbit: the model is built round the body's axis and turned a quarter a step (the clients slerp the
        // turn, so it glides round on a circle), rising and sinking a little as it goes
        if (m != null && m.kind() == CustomItem.MotionKind.ORBIT && phase != 0) t.add(0, (phase % 2 == 1 ? m.amount() : -m.amount()) * playerScale, 0);
        if (!sneaking) return new Transformation(t, turn, scale, new AxisAngle4f());
        // the sneaking lean is part of the transformation (the display itself only ever turns about the vertical axis):
        // the same picture as pitching the display by the lean, R(lean) T(t) = T(R(lean) t) R(lean), and a wearer's
        // own copy can then be drawn from a display that sits higher up (CosmeticPackets, own view)
        Quaternionf lean = new Quaternionf().rotationX((float) Math.toRadians(SNEAK_TILT));
        return new Transformation(lean.transform(t), new Quaternionf(lean).mul(new Quaternionf(turn)), scale, new Quaternionf());
    }

    /** Steps in one motion cycle: a spin goes round in three, an orbit in four, the others swing there and back in two. */
    static int motionSteps(CustomItem.Motion m) {
        return m.kind() == CustomItem.MotionKind.SPIN ? 3 : m.kind() == CustomItem.MotionKind.ORBIT ? 4 : 2;
    }

    // ------------------------------------------------------------------------------------------
    // Live displays
    // ------------------------------------------------------------------------------------------

    private static final class Worn {
        final ItemDisplay display;
        final CustomItem cosmetic;
        /** the displays of its cosmetic.parts, in order (they ride and move with the main one) */
        final List<ItemDisplay> parts = new ArrayList<>();
        boolean sneaking, hidden, bobUp;
        /** its whole set is worn: the awakened model shows (cosmetic.awakened) */
        boolean awake;
        /** where its motion is (CosmeticAttachments#transformation phase; 0 = at rest) */
        int phase;
        float scale;

        Worn(ItemDisplay display, CustomItem cosmetic) {
            this.display = display;
            this.cosmetic = cosmetic;
        }

        /** The main display and its parts. */
        List<ItemDisplay> all() {
            if (parts.isEmpty()) return List.of(display);
            List<ItemDisplay> out = new ArrayList<>(parts.size() + 1);
            out.add(display);
            out.addAll(parts);
            return out;
        }

        void removeAll() {
            display.remove();
            parts.forEach(Entity::remove);
        }

        boolean valid(org.bukkit.World world) {
            for (ItemDisplay d : all()) if (!d.isValid() || d.getWorld() != world) return false;
            return true;
        }
    }

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final CosmeticPackets packets;
    private final Predicate<Player> selfView;
    private final Map<UUID, Map<CosmeticSlot, Worn>> worn = new HashMap<>();
    /** wearer -> the display of their full set's bonus model (cosmetic_sets.yml bonus.model), worn at the aura anchor */
    private final Map<UUID, Worn> bonus = new HashMap<>();
    /** wearer -> what was last asked for (set): awakened pieces and the bonus model, to make lost displays again */
    private final Map<UUID, java.util.Set<String>> awakeOf = new HashMap<>();
    private final Map<UUID, CustomItem> bonusOf = new HashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    /** how far up each player with the own view looked a tick ago (degrees; SelfLift.Band) */
    private final Map<UUID, Float> looks = new HashMap<>();
    /** wearer -> ticks at which the link is sent again (right after their displays changed) */
    private final Map<UUID, List<Long>> relinkAt = new HashMap<>();
    /** wearer -> displays of another module that ride them on the clients (the emote puppet's carrier) */
    private final Map<UUID, int[]> carried = new HashMap<>();
    /** wearer -> the ticks at which a carried display's link is sent once more */
    private final Map<UUID, List<Long>> carriedRelinkAt = new HashMap<>();
    private Map<CosmeticSlot, Vector3f> anchors = DEFAULT_ANCHORS;
    private long ticks;
    /** Display#setViewRange of the worn displays: not drawn beyond cosmetics.lod.far (CosmeticLod) */
    private float viewRange = 1f;

    /** The view range of worn displays from now on, and of those worn now. */
    void viewRange(float range) {
        viewRange = range;
        for (Map<CosmeticSlot, Worn> have : worn.values()) for (Worn w : have.values()) w.all().forEach(d -> d.setViewRange(range));
        for (Worn w : bonus.values()) w.all().forEach(d -> d.setViewRange(range));
    }

    /** The worn model displays of this player (pieces, their parts, the set's bonus model), for CosmeticLod. */
    List<ItemDisplay> models(Player p) {
        Map<CosmeticSlot, Worn> have = worn.get(p.getUniqueId());
        Worn b = bonus.get(p.getUniqueId());
        if ((have == null || have.isEmpty()) && b == null) return List.of();
        List<ItemDisplay> out = new ArrayList<>();
        if (have != null) for (Worn w : have.values()) out.addAll(w.all());
        if (b != null) out.addAll(b.all());
        return out;
    }

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
        TagStyle style = TagStyle.PLAIN;

        Tag(TextDisplay display) {
            this.display = display;
        }
    }

    /**
     * How a name tag is drawn: the game's look (background, seen through walls) or a picture of its own (a title
     * banner: no background, the game's text shadow, never through walls so its letters stay over its plate by
     * depth), its size and how far above the usual place its baseline sits (blocks, at size 1).
     */
    record TagStyle(boolean plain, float size, float lift) {
        static final TagStyle PLAIN = new TagStyle(true, 1f, 0f);
    }

    private record TagWant(net.kyori.adventure.text.Component text, TagStyle style) {}

    private final Map<UUID, TagWant> tagText = new HashMap<>();
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
            if (p != null) e.getValue().forEach((slot, w) -> pose(w, transformation(slot, w, p)));
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
        set(p, wanted, java.util.Set.of(), null);
    }

    /**
     * @param awake      ids of the worn pieces whose whole set is worn (they show their awakened model)
     * @param bonusModel the full set's bonus model to wear at the aura anchor (null: none), as a cosmetic item
     */
    void set(Player p, Map<CosmeticSlot, CustomItem> wanted, java.util.Set<String> awake, CustomItem bonusModel) {
        UUID id = p.getUniqueId();
        if (awake.isEmpty()) awakeOf.remove(id);
        else awakeOf.put(id, java.util.Set.copyOf(awake));
        if (bonusModel == null) bonusOf.remove(id);
        else bonusOf.put(id, bonusModel);
        Map<CosmeticSlot, Worn> have = worn.computeIfAbsent(id, k -> new EnumMap<>(CosmeticSlot.class));
        boolean changed = false;
        for (CosmeticSlot slot : new ArrayList<>(have.keySet())) {
            Worn w = have.get(slot);
            CustomItem want = wanted.get(slot);
            if (want == null || !carried(want) || !want.key().equals(w.cosmetic.key()) || !w.valid(p.getWorld())) {
                w.removeAll();
                have.remove(slot);
                changed = true;
                continue;
            }
            boolean a = awake.contains(want.key().asString());
            if (a != w.awake) {
                w.awake = a;
                if (!w.hidden) dress(w, false);
            }
        }
        List<Worn> fresh = new ArrayList<>();
        for (Map.Entry<CosmeticSlot, CustomItem> e : wanted.entrySet()) {
            if (!carried(e.getValue()) || have.containsKey(e.getKey())) continue;   // head, pet, effects (and particle auras): not here
            Worn w = spawn(p, e.getKey(), e.getValue(), awake.contains(e.getValue().key().asString()));
            have.put(e.getKey(), w);
            fresh.add(w);
            changed = true;
        }
        Worn b = bonus.get(id);
        if (b != null && (bonusModel == null || !bonusModel.key().equals(b.cosmetic.key()) || !b.valid(p.getWorld()))) {
            b.removeAll();
            bonus.remove(id);
            b = null;
            changed = true;
        }
        if (b == null && bonusModel != null) {
            b = spawn(p, CosmeticSlot.AURA, bonusModel, false);
            bonus.put(id, b);
            fresh.add(b);
            changed = true;
        }
        if (have.isEmpty()) worn.remove(id);
        if (!changed) return;
        // linked first, shown after: the spawn packets then carry the link
        link(p);
        for (Worn w : fresh) {
            for (Player v : p.getWorld().getPlayers()) if (shouldSee(v, p)) for (ItemDisplay d : w.all()) v.showEntity(plugin, d);
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
        looks.remove(p.getUniqueId());
        relinkAt.remove(p.getUniqueId());
        Map<CosmeticSlot, Worn> have = worn.remove(p.getUniqueId());
        if (have != null) have.values().forEach(Worn::removeAll);
        Worn b = bonus.remove(p.getUniqueId());
        if (b != null) b.removeAll();
        awakeOf.remove(p.getUniqueId());
        bonusOf.remove(p.getUniqueId());
        Tag t = tags.remove(p.getUniqueId());
        if (t != null) t.display.remove();
        link(p);   // what another module has riding them stays
    }

    /**
     * Displays of another module that ride the player on the clients like the worn models (null or empty: no more).
     * They must be shown to their viewers after this call: the spawn packets then carry the link. The player's own
     * client is told too (the caller shows them to the player).
     *
     * @return whether the clients carry them (false: no packet layer, the caller has to move them itself)
     */
    boolean carry(Player p, int[] ids) {
        UUID id = p.getUniqueId();
        if (ids == null || ids.length == 0) {
            carried.remove(id);
            carriedRelinkAt.remove(id);
        } else {
            carried.put(id, ids.clone());
            carriedRelinkAt.put(id, new ArrayList<>(List.of(ticks + 2, ticks + 10)));
        }
        link(p);
        return packets != null;
    }

    boolean carrying(Player p) {
        return carried.containsKey(p.getUniqueId());
    }

    /** Every tick: the link of what is carried, once more shortly after it began and every {@link #RELINK_TICKS}. */
    private void tickCarried(boolean resend) {
        if (packets == null || carried.isEmpty()) return;
        for (UUID id : new ArrayList<>(carried.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                carried.remove(id);
                carriedRelinkAt.remove(id);
                continue;
            }
            List<Long> due = carriedRelinkAt.get(id);
            boolean now = due != null && due.remove(ticks);
            if (due != null && due.isEmpty()) carriedRelinkAt.remove(id);
            if (!resend && !now) continue;
            List<Player> viewers = new ArrayList<>(p.getTrackedBy());
            viewers.add(p);
            packets.resendLink(p, viewers);
        }
    }

    void removeAll() {
        for (Map<CosmeticSlot, Worn> have : worn.values()) have.values().forEach(Worn::removeAll);
        worn.clear();
        bonus.values().forEach(Worn::removeAll);
        bonus.clear();
        awakeOf.clear();
        bonusOf.clear();
        tags.values().forEach(t -> t.display.remove());
        tags.clear();
        tagText.clear();
        bodies.clear();
        relinkAt.clear();
        carried.clear();
        carriedRelinkAt.clear();
    }

    /** Debug: slot -> display entity id. */
    Map<CosmeticSlot, Integer> displays(Player p) {
        Map<CosmeticSlot, Integer> out = new EnumMap<>(CosmeticSlot.class);
        worn.getOrDefault(p.getUniqueId(), Map.of()).forEach((s, w) -> out.put(s, w.display.getEntityId()));
        return out;
    }

    private void link(Player p) {
        if (packets == null) return;
        java.util.stream.IntStream models = worn.getOrDefault(p.getUniqueId(), Map.of()).values().stream()
                .flatMap(w -> w.all().stream()).mapToInt(Entity::getEntityId);
        Worn b = bonus.get(p.getUniqueId());
        if (b != null) models = java.util.stream.IntStream.concat(models, b.all().stream().mapToInt(Entity::getEntityId));
        Tag t = tags.get(p.getUniqueId());
        java.util.stream.IntStream own = t == null ? models : java.util.stream.IntStream.concat(models, java.util.stream.IntStream.of(t.display.getEntityId()));
        int[] ids = java.util.stream.IntStream.concat(own, java.util.Arrays.stream(carried.getOrDefault(p.getUniqueId(), new int[0]))).toArray();
        int[] real = p.getPassengers().stream().mapToInt(Entity::getEntityId).toArray();
        Worn above = worn.getOrDefault(p.getUniqueId(), Map.of()).get(CosmeticSlot.ABOVE);
        int[] head = above == null ? new int[0] : above.all().stream().mapToInt(Entity::getEntityId).toArray();
        packets.link(p.getEntityId(), ids, real, wornIds(p, false), head);
    }

    /**
     * Own view (SelfLift): what the wearer wears on the body sits above their head on their client while they look
     * level or down, at their feet while they look up and at their body while they look up steeply, so that no look
     * in first person brings it into view. The look is taken a tick and a half ahead: the server hears of a turn a
     * moment late, and its answer takes as long again.
     */
    private void ownBand(Player p) {
        float up = -p.getLocation().getPitch();
        Float last = looks.put(p.getUniqueId(), up);
        double ahead = Math.clamp(up + (last == null ? 0 : (up - last) * 1.5), -90, 90);
        boolean upright = p.getPose() == org.bukkit.entity.Pose.STANDING || p.getPose() == org.bukkit.entity.Pose.SNEAKING;
        SelfLift.Band was = packets.ownBand(p), now = SelfLift.band(was, ahead, upright);
        if (now == was) return;
        List<ItemDisplay> body = new ArrayList<>();
        for (Map.Entry<CosmeticSlot, Worn> e : worn.getOrDefault(p.getUniqueId(), Map.of()).entrySet()) {
            if (e.getKey() != CosmeticSlot.ABOVE) body.addAll(e.getValue().all());
        }
        Worn b = bonus.get(p.getUniqueId());
        if (b != null) body.addAll(b.all());
        int[] ids = new int[body.size()];
        org.joml.Vector3fc[] at = new org.joml.Vector3fc[body.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = body.get(i).getEntityId();
            at[i] = body.get(i).getTransformation().getTranslation();
        }
        packets.ownBand(p, now, ids, at);
    }

    /** The ids of the player's worn displays (models and parts, the set bonus too); unlit: only those without a brightness of their own. */
    private int[] wornIds(Player p, boolean unlit) {
        java.util.stream.Stream<ItemDisplay> all = worn.getOrDefault(p.getUniqueId(), Map.of()).values().stream().flatMap(w -> w.all().stream());
        Worn b = bonus.get(p.getUniqueId());
        if (b != null) all = java.util.stream.Stream.concat(all, b.all().stream());
        return all.filter(d -> !unlit || d.getBrightness() == null).mapToInt(Entity::getEntityId).toArray();
    }

    private Worn spawn(Player p, CosmeticSlot slot, CustomItem c, boolean awake) {
        boolean sneak = sneaking(p, slot);
        float scale = scale(p);
        Body b = bodies.get(p.getUniqueId());
        ItemDisplay d = p.getWorld().spawn(place(p, slot, sneak, b == null ? p.getBodyYaw() : b.yaw), ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);   // shown once linked (set)
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(look(c, awake));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTeleportDuration(packets == null ? 3 : 1);   // turning (and, without riding, moving) is smoothed
            e.setInterpolationDuration(3);
            e.setShadowRadius(0);
            e.setViewRange(viewRange);
            if (c.cosmetic().glow()) e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            e.setTransformation(transformation(anchor(slot), c.cosmetic(), sneak, scale, 0));
        });
        Worn w = new Worn(d, c);
        w.sneaking = sneak;
        w.scale = scale;
        w.awake = awake;
        CustomItem.Premium pr = c.cosmetic().premium();
        if (pr != null && !pr.parts().isEmpty()) {
            Transformation main = d.getTransformation();
            Location at = d.getLocation();
            for (CustomItem.Part part : pr.parts()) {
                ItemDisplay pd = p.getWorld().spawn(at, ItemDisplay.class, e -> {
                    e.setVisibleByDefault(false);
                    e.setPersistent(false);
                    e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                    e.setItemStack(partLook(c, part, awake));
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    e.setTeleportDuration(packets == null ? 3 : 1);
                    e.setInterpolationDuration(3);
                    e.setShadowRadius(0);
                    e.setViewRange(viewRange);
                    if (part.billboard()) e.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                    if (part.glow() || c.cosmetic().glow()) e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    e.setTransformationMatrix(partMatrix(main, part));
                });
                w.parts.add(pd);
            }
        }
        return w;
    }

    /** The item a part's display shows: the cosmetic's item drawn as the part's model (awakened when the set is whole). */
    static ItemStack partLook(CustomItem c, CustomItem.Part part, boolean awake) {
        ItemStack it = c.create(1);
        NamespacedKey model = awake && part.awakened() != null ? part.awakened() : part.model();
        it.editMeta(m -> m.setItemModel(model));
        return it;
    }

    /** Sets the main display's transformation and moves its parts with it. */
    private static void pose(Worn w, Transformation main) {
        w.display.setTransformation(main);
        CustomItem.Premium pr = w.cosmetic.cosmetic().premium();
        if (pr == null) return;
        for (int i = 0; i < w.parts.size() && i < pr.parts().size(); i++) w.parts.get(i).setTransformationMatrix(partMatrix(main, pr.parts().get(i)));
    }

    /** Shows (or, hide: empties) the main display and its parts, in their awakened looks while the set is whole. */
    private static void dress(Worn w, boolean hide) {
        w.display.setItemStack(hide ? null : look(w.cosmetic, w.awake));
        CustomItem.Premium pr = w.cosmetic.cosmetic().premium();
        if (pr == null) return;
        for (int i = 0; i < w.parts.size() && i < pr.parts().size(); i++) {
            w.parts.get(i).setItemStack(hide ? null : partLook(w.cosmetic, pr.parts().get(i), w.awake));
        }
    }

    /** An item display draws a model turned half round about the vertical (model north = the display's front). */
    private static final Quaternionf HALF_TURN = new Quaternionf().rotationY((float) Math.PI);

    /**
     * Where a part's display draws its model: the main display's transformation, then (in the main model's own frame:
     * units of 16 per block, (8, 8, 8) its centre) the part moved to its place, turned and sized round its own centre.
     * A billboard part keeps only where its centre lands and its size (the client turns it to the camera).
     */
    static Matrix4f partMatrix(Transformation main, CustomItem.Part part) {
        Matrix4f m = new Matrix4f().translate(main.getTranslation()).rotate(main.getLeftRotation()).scale(main.getScale())
                .rotate(main.getRightRotation()).rotate(HALF_TURN);
        Vector3f at = new Vector3f(part.at()).sub(8, 8, 8).div(16f);
        if (part.billboard()) {
            Vector3f centre = m.transformPosition(new Vector3f(at));
            float s = main.getScale().x() * part.scale();
            return new Matrix4f().translate(centre).scale(s);
        }
        Vector3f r = part.rotation();
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(r.y())).rotateX((float) Math.toRadians(r.x()))
                .rotateZ((float) Math.toRadians(r.z()));
        return m.translate(at).rotate(turn).scale(part.scale()).rotate(new Quaternionf(HALF_TURN).conjugate());
    }

    /** Every tick: turn with the wearer, lean when sneaking, hide in poses the models do not fit. */
    void tick() {
        ticks++;
        boolean sweep = ticks % 5 == 0;
        boolean resend = ticks % RELINK_TICKS == 0;
        boolean bobFlip = ticks % BOB_TICKS == 0;
        java.util.Set<UUID> wearers = new java.util.LinkedHashSet<>(worn.keySet());
        wearers.addAll(bonus.keySet());
        for (UUID id : wearers) {
            Player p = Bukkit.getPlayer(id);
            Map<CosmeticSlot, Worn> mine = worn.getOrDefault(id, Map.of());
            if (p == null) {
                mine.values().forEach(Worn::removeAll);
                worn.remove(id);
                Worn b = bonus.remove(id);
                if (b != null) b.removeAll();
                awakeOf.remove(id);
                bonusOf.remove(id);
                continue;
            }
            boolean hide = badPose(p);
            float scale = scale(p);
            float body = turnBody(p);
            boolean relink = false;
            // own view (SelfLift): the wearer's own displays sit above their head on their client, maybe inside the
            // ceiling, so they are lit as the wearer is
            if (packets != null && packets.ownLiftOn() && selfView.test(p)) {
                org.bukkit.block.Block at = p.getEyeLocation().getBlock();
                packets.ownLight(p, wornIds(p, true), at.getLightFromBlocks(), at.getLightFromSky());
                ownBand(p);
            }
            List<Map.Entry<CosmeticSlot, Worn>> all = new ArrayList<>(mine.entrySet());
            Worn bw = bonus.get(id);
            if (bw != null) all.add(Map.entry(CosmeticSlot.AURA, bw));
            for (Map.Entry<CosmeticSlot, Worn> we : all) {
                CosmeticSlot slot = we.getKey();
                Worn w = we.getValue();
                if (!w.valid(p.getWorld())) {
                    relink = true;   // set() recreates it
                    continue;
                }
                if (hide != w.hidden) {
                    w.hidden = hide;
                    dress(w, hide);
                }
                boolean sneak = sneaking(p, slot);
                boolean bob = w.cosmetic.cosmetic().bob() && bobFlip;
                if (bob) w.bobUp = !w.bobUp;
                CustomItem.Motion motion = w.cosmetic.cosmetic().motion();
                int stepTicks = motion == null ? 0 : Math.max(2, motion.period() / motionSteps(motion));
                boolean moved = motion != null && !hide && ticks % stepTicks == 0;
                if (moved) w.phase = w.phase % motionSteps(motion) + 1;
                if (sneak != w.sneaking || scale != w.scale || bob || moved) {
                    w.sneaking = sneak;
                    w.scale = scale;
                    for (ItemDisplay d : w.all()) {
                        d.setInterpolationDelay(0);
                        d.setInterpolationDuration(moved ? stepTicks : bob ? BOB_TICKS : 3);
                    }
                    pose(w, transformation(slot, w, p));
                }
                Location target = place(p, slot, sneak, body);
                for (ItemDisplay d : w.all()) {
                    Location now = d.getLocation();
                    if (packets == null || now.distanceSquared(target) > 1.0) {
                        if (now.distanceSquared(target) > 1e-4 || turned(now, target)) d.teleport(target);
                    } else if (turned(now, target)) {
                        d.setRotation(target.getYaw(), target.getPitch());
                    }
                }
                if (sweep) {
                    for (Player v : p.getWorld().getPlayers()) {
                        boolean want = shouldSee(v, p);
                        for (ItemDisplay d : w.all()) {
                            if (want != v.canSee(d)) {
                                if (want) v.showEntity(plugin, d);
                                else v.hideEntity(plugin, d);
                            }
                        }
                    }
                }
            }
            if (relink) {
                Map<CosmeticSlot, CustomItem> wanted = new EnumMap<>(CosmeticSlot.class);
                mine.forEach((slot, w) -> wanted.put(slot, w.cosmetic));
                set(p, wanted, awakeOf.getOrDefault(id, java.util.Set.of()), bonusOf.get(id));
            } else if (packets != null && (resend || dueRelink(p))) {
                List<Player> viewers = new ArrayList<>(p.getTrackedBy());
                if (selfView.test(p)) viewers.add(p);
                packets.resendLink(p, viewers);
            }
        }
        tickTags(sweep, resend);
        tickCarried(resend);
    }

    /** The wearer's own view was switched: show or hide their displays to them now. */
    void selfViewChanged(Player p) {
        boolean want = selfView.test(p);
        List<Worn> all = new ArrayList<>(worn.getOrDefault(p.getUniqueId(), Map.of()).values());
        Worn b = bonus.get(p.getUniqueId());
        if (b != null) all.add(b);
        for (Worn w : all) {
            for (ItemDisplay d : w.all()) {
                if (want) p.showEntity(plugin, d);
                else p.hideEntity(plugin, d);
            }
        }
    }

    private Transformation transformation(CosmeticSlot slot, Worn w, Player p) {
        float bob = w.cosmetic.cosmetic().bob() ? (w.bobUp ? BOB : -BOB) : 0;
        return transformation(anchor(slot), w.cosmetic.cosmetic(), w.sneaking && onBody(slot), scale(p), bob, w.phase);
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
            // on a living mount (a horse, a vehicle's seat) clients draw the body facing the mount's way, the head turning up
            // to 85 degrees (VehicleSeats.riderBodyYaw); boats and the rest turn it to their own facing
            b.yaw = vehicle instanceof org.bukkit.entity.LivingEntity le
                    ? dev.stsv.core.vehicle.VehicleSeats.riderBodyYaw(le.getBodyYaw(), p.getYaw()) : vehicle.getYaw();
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
        l.setPitch(0);   // the sneaking lean is in the transformation (see transformation)
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
        for (Tag t : tags.values()) t.display.setTransformation(tagTransformation(t.scale, t.style));
    }

    /** The name tag above the player (null: none). */
    void nameTag(Player p, net.kyori.adventure.text.Component text) {
        nameTag(p, text, TagStyle.PLAIN);
    }

    void nameTag(Player p, net.kyori.adventure.text.Component text, TagStyle style) {
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
        tagText.put(id, new TagWant(text, style));
        Tag t = tags.get(id);
        if (t != null && t.display.isValid() && t.display.getWorld() == p.getWorld()) {
            t.display.text(text);
            if (!t.style.equals(style)) {
                t.style = style;
                styleTag(t.display, style, t.sneaking);
                t.display.setTransformation(tagTransformation(t.scale, style));
            }
            return;
        }
        if (!p.isDead()) spawnTag(p, text, style);
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
        // a player whose puppet stands in for them (an emote in their own body) is invisible, but there to be seen
        return viewer != owner && viewer.canSee(owner) && (!tagHidden(owner) || (carrying(owner) && !owner.isDead()));
    }

    private Transformation tagTransformation(float playerScale, TagStyle style) {
        float k = style.size();
        return new Transformation(new Vector3f(0, (tagHeight + style.lift() * k) * playerScale, 0), new AxisAngle4f(),
                new Vector3f(k, k, k), new AxisAngle4f());
    }

    /** Background, shadow and seeing through walls for the style (sneaking: never through walls). */
    private static void styleTag(TextDisplay e, TagStyle style, boolean sneak) {
        e.setDefaultBackground(style.plain());   // the game's name tag background
        if (!style.plain()) e.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
        e.setShadowed(!style.plain());
        e.setSeeThrough(style.plain() && !sneak);
    }

    /** A sneaking player's tag: faint, as the game draws it. */
    static final byte SNEAK_TAG_OPACITY = (byte) 0x50;

    private void spawnTag(Player p, net.kyori.adventure.text.Component text, TagStyle style) {
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
            styleTag(e, style, sneak);
            e.setTextOpacity(sneak ? SNEAK_TAG_OPACITY : (byte) -1);
            e.setTeleportDuration(packets == null ? 3 : 1);
            e.setShadowRadius(0);
            e.setTransformation(tagTransformation(scale, style));
        });
        Tag t = new Tag(d);
        t.style = style;
        t.sneaking = sneak;
        t.scale = scale;
        tags.put(p.getUniqueId(), t);
        link(p);
        for (Player v : p.getWorld().getPlayers()) if (shouldSeeTag(v, p)) v.showEntity(plugin, d);
        relinkAt.put(p.getUniqueId(), new ArrayList<>(List.of(ticks + 2, ticks + 10)));
    }

    private void tickTags(boolean sweep, boolean resend) {
        for (Map.Entry<UUID, TagWant> e : new ArrayList<>(tagText.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                tagText.remove(e.getKey());
                Tag gone = tags.remove(e.getKey());
                if (gone != null) gone.display.remove();
                continue;
            }
            Tag t = tags.get(e.getKey());
            if (t == null || !t.display.isValid() || t.display.getWorld() != p.getWorld()) {
                if (sweep && !p.isDead()) spawnTag(p, e.getValue().text(), e.getValue().style());
                continue;
            }
            TextDisplay d = t.display;
            boolean sneak = p.isSneaking();
            if (sneak != t.sneaking) {
                t.sneaking = sneak;
                d.setSeeThrough(t.style.plain() && !sneak);
                d.setTextOpacity(sneak ? SNEAK_TAG_OPACITY : (byte) -1);
            }
            float scale = scale(p);
            if (scale != t.scale) {
                t.scale = scale;
                d.setTransformation(tagTransformation(scale, t.style));
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
            e.setItemStack(look(c, false));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTeleportDuration(2);
            if (c.cosmetic().glow()) e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            e.setShadowRadius(0);
            e.setTransformation(transformation(anchor(slot), c.cosmetic(), false, 1f, 0));
        });
    }

    /** The displays of a cosmetic's parts for the wardrobe mannequin (empty: none), visible to nobody until shown. */
    List<ItemDisplay> spawnPreviewParts(ItemDisplay main, CustomItem c) {
        CustomItem.Premium pr = c.cosmetic().premium();
        if (pr == null || pr.parts().isEmpty()) return List.of();
        List<ItemDisplay> out = new ArrayList<>();
        for (CustomItem.Part part : pr.parts()) {
            out.add(main.getWorld().spawn(main.getLocation(), ItemDisplay.class, e -> {
                e.setVisibleByDefault(false);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                e.setItemStack(partLook(c, part, false));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setTeleportDuration(2);
                e.setShadowRadius(0);
                if (part.billboard()) e.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                if (part.glow() || c.cosmetic().glow()) e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setTransformationMatrix(partMatrix(main.getTransformation(), part));
            }));
        }
        return out;
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
