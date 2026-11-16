package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived model effects of donation-grade cosmetics: footprints (cosmetic.print), kill bursts (cosmetic.burst)
 * and the flourish when a set is completed. Each is one item display, never saved with the chunk, seen only by the
 * players it is shown to, animated by the clients (display interpolation): it pops up, stays, then sinks away and is
 * removed. A wearer has at most {@link #PER_PLAYER} prints out at once, the server at most {@link #MAX_LIVE} effects.
 *
 * <p>The timing and placement are pure ({@link #stage}, {@link #nextPrint}) and tested as is.
 */
final class CosmeticFx {

    /** Live effects a wearer may have at once (prints: the oldest goes first), and the server in all. */
    static final int PER_PLAYER = 10, MAX_LIVE = 400;
    /** Ticks a print takes to pop up and to sink away. */
    static final int POP = 3, SINK = 10;
    /** How far a footprint is set beside the walking line (blocks): left and right foot. */
    static final double FOOT = 0.16;

    // ------------------------------------------------------------------------------------------
    // Pure
    // ------------------------------------------------------------------------------------------

    /** Where an effect is in its life. */
    enum Stage { BORN, GROW, HOLD, FADE, GONE }

    /**
     * The stage at an age (ticks since it was spawned): BORN at 0 and 1 (the clients get the spawn at its tiny
     * size), GROW at 2 (then grows over {@link #POP} ticks), FADE at {@code life - SINK} (shrinks and sinks), GONE at
     * {@code life}; HOLD in between.
     */
    static Stage stage(int age, int life) {
        if (age >= life) return Stage.GONE;
        if (age < 2) return Stage.BORN;
        if (age == 2) return Stage.GROW;
        if (age == Math.max(3, life - SINK)) return Stage.FADE;
        return Stage.HOLD;
    }

    /** A footprint to leave: where (x, z), turned to the walking direction (yaw, degrees), and which foot. */
    record Print(double x, double z, float yaw, boolean left) {}

    /**
     * The next footprint after walking from the last one (lastX, lastZ) to (x, z), or null while the wearer has not
     * walked {@code step} blocks yet. It lies on the walking line, {@link #FOOT} to the side of the foot whose turn
     * it is (left after right when {@code alternate}), turned the way they walk.
     */
    static Print nextPrint(double lastX, double lastZ, double x, double z, double step, boolean lastLeft, boolean alternate) {
        double dx = x - lastX, dz = z - lastZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < step) return null;
        double ux = dx / d, uz = dz / d;
        boolean left = alternate != lastLeft;
        // Minecraft yaw: 0 = +z (south), 90 = -x (west); the left of someone facing (ux, uz) is (uz, -ux)
        float yaw = (float) Math.toDegrees(Math.atan2(-ux, uz));
        double side = left ? FOOT : -FOOT;
        return new Print(x + uz * side, z - ux * side, yaw, left);
    }

    // ------------------------------------------------------------------------------------------
    // Live
    // ------------------------------------------------------------------------------------------

    private static final class Live {
        final ItemDisplay display;
        final UUID owner;
        final int life;
        final List<ItemStack> frames;
        final Transformation full, faded;
        final boolean burst;
        int age, frame;

        Live(ItemDisplay display, UUID owner, int life, List<ItemStack> frames, Transformation full, Transformation faded, boolean burst) {
            this.display = display;
            this.owner = owner;
            this.life = life;
            this.frames = frames;
            this.full = full;
            this.faded = faded;
            this.burst = burst;
        }
    }

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Deque<Live> live = new ArrayDeque<>();
    private final Map<UUID, Deque<Live>> prints = new HashMap<>();

    CosmeticFx(Plugin plugin, NamespacedKey tag) {
        this.plugin = plugin;
        this.tag = tag;
    }

    private static ItemStack modelItem(NamespacedKey model) {
        ItemStack it = new ItemStack(org.bukkit.Material.PAPER);
        it.editMeta(m -> m.setItemModel(model));
        return it;
    }

    private static Transformation tiny() {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.02f, 0.02f, 0.02f), new AxisAngle4f());
    }

    private ItemDisplay spawn(Location at, ItemStack item, boolean glow, List<Player> viewers) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setShadowRadius(0);
            if (glow) e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(tiny());
        });
        for (Player v : viewers) v.showEntity(plugin, d);
        return d;
    }

    private boolean full() {
        return live.size() >= MAX_LIVE;
    }

    /** Leaves one footprint of this wearer at (x, y, z) turned to yaw; frames: its models (awakened already chosen). */
    void print(Player owner, Location at, float yaw, CustomItem.Print spec, List<NamespacedKey> frames, boolean glow, List<Player> viewers) {
        if (full() || viewers.isEmpty()) return;
        Deque<Live> mine = prints.computeIfAbsent(owner.getUniqueId(), k -> new ArrayDeque<>());
        while (mine.size() >= PER_PLAYER) {
            Live old = mine.pollFirst();
            old.display.remove();
            live.remove(old);
        }
        Location l = at.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        List<ItemStack> items = new ArrayList<>();
        for (NamespacedKey k : frames) items.add(modelItem(k));
        float s = spec.scale();
        Transformation full = new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
        Transformation faded = new Transformation(new Vector3f(0, -0.06f, 0), new AxisAngle4f(), new Vector3f(s * 0.35f, s * 0.2f, s * 0.35f), new AxisAngle4f());
        Live e = new Live(spawn(l, items.getFirst(), glow, viewers), owner.getUniqueId(), spec.life(), items, full, faded, false);
        mine.addLast(e);
        live.addLast(e);
    }

    /** A kill burst at the victim, facing the killer; model: the burst's (awakened already chosen). */
    void burst(Player owner, Location at, float yaw, CustomItem.Burst spec, NamespacedKey model, boolean glow, List<Player> viewers) {
        if (full() || viewers.isEmpty()) return;
        Location l = at.clone();
        l.setYaw(yaw);
        l.setPitch(0);
        float s = spec.scale();
        AxisAngle4f turn = spec.spin() ? new AxisAngle4f((float) Math.PI, 0, 1, 0) : new AxisAngle4f();
        Transformation full = new Transformation(new Vector3f(0, spec.rise(), 0), turn, new Vector3f(s, s, s), new AxisAngle4f());
        Transformation faded = new Transformation(new Vector3f(0, spec.rise() * 1.25f, 0), spec.spin() ? new AxisAngle4f((float) (Math.PI * 1.9), 0, 1, 0) : turn,
                new Vector3f(s * 1.3f, s * 0.05f, s * 1.3f), new AxisAngle4f());
        Live e = new Live(spawn(l, modelItem(model), glow, viewers), owner.getUniqueId(), spec.life(), List.of(modelItem(model)), full, faded, true);
        live.addLast(e);
        if (spec.sound() != null) {
            for (Player v : viewers) v.playSound(at, spec.sound(), 0.9f, 1.0f);
        }
    }

    /** Every tick: grow, flicker, fade and remove. */
    void tick() {
        for (Iterator<Live> it = live.iterator(); it.hasNext(); ) {
            Live e = it.next();
            if (!e.display.isValid()) {
                it.remove();
                forget(e);
                continue;
            }
            switch (stage(e.age, e.life)) {
                case GROW -> {
                    e.display.setInterpolationDelay(0);
                    // a burst grows and rises slowly through most of its life; a print pops up
                    e.display.setInterpolationDuration(e.burst ? Math.max(4, e.life - SINK - 2) : POP);
                    e.display.setTransformation(e.full);
                }
                case FADE -> {
                    e.display.setInterpolationDelay(0);
                    e.display.setInterpolationDuration(SINK);
                    e.display.setTransformation(e.faded);
                    e.display.setBrightness(new Display.Brightness(9, 9));
                }
                case GONE -> {
                    e.display.remove();
                    it.remove();
                    forget(e);
                    continue;
                }
                default -> {
                }
            }
            if (e.frames.size() > 1 && e.age > 2 && e.age % 5 == 0) {
                e.frame = (e.frame + 1) % e.frames.size();
                e.display.setItemStack(e.frames.get(e.frame));
            }
            e.age++;
        }
    }

    private void forget(Live e) {
        if (e.burst) return;
        Deque<Live> mine = prints.get(e.owner);
        if (mine != null) {
            mine.remove(e);
            if (mine.isEmpty()) prints.remove(e.owner);
        }
    }

    /** The wearer left: their prints go now. */
    void remove(UUID owner) {
        Deque<Live> mine = prints.remove(owner);
        if (mine == null) return;
        for (Live e : mine) {
            e.display.remove();
            live.remove(e);
        }
    }

    void removeAll() {
        live.forEach(e -> e.display.remove());
        live.clear();
        prints.clear();
    }

    int live() {
        return live.size();
    }

    /** The one-time flourish when a set is completed: the sound, and the burst model rising round the wearer. */
    void awaken(Player p, NamespacedKey model, String sound, List<Player> viewers) {
        if (sound != null) for (Player v : viewers) v.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        else for (Player v : viewers) v.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.4f);
        if (model != null) burst(p, p.getLocation(), p.getYaw(), new CustomItem.Burst(model, 40, 1.0f, 0.2f, true, null), model, true, viewers);
    }
}
