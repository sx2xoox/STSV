package dev.stsv.core.minigame.circuit;

import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link Ghost} drawn in the world: the kart's body and wheels in their see-through ghost models
 * (tools/assetgen/models/kart_ghosts.py: kart_&lt;x&gt;_ghost, gold for the track record) with a glowing outline, and the
 * holder's name and lap above. Display entities nobody sees but the players it is shown to (the racer, and whoever
 * watches them), moved every tick with the client's own glide (teleport interpolation), so it drives smoothly; they are
 * never solid and never saved. Main thread only.
 */
final class GhostView {

    static final NamespacedKey TAG = new NamespacedKey("stsv", "circuit_ghost");

    private final Plugin plugin;
    private final World world;
    private final VehicleSpec spec;
    private final float yawOffset;
    private final ItemDisplay body;
    private final List<ItemDisplay> wheels = new ArrayList<>();
    private final TextDisplay label;
    private final Set<UUID> viewers = new HashSet<>();
    private double lastX = Double.NaN, lastZ;
    private double roll;
    private int ticks;

    /**
     * @param bodyModel  the ghost body's model (or the kart's own when no ghost model was drawn)
     * @param wheelModel the ghost wheel's model, or null
     * @param glow       the outline's colour, or null for none
     * @param name       the line above it (MiniMessage)
     */
    GhostView(Plugin plugin, World world, VehicleSpec spec, NamespacedKey bodyModel, NamespacedKey wheelModel, Color glow, String name,
              Ghost.Pose at, int ox, float yawOffset, int smoothing) {
        this.plugin = plugin;
        this.world = world;
        this.spec = spec;
        this.yawOffset = yawOffset;
        Location l = where(at, ox);
        body = world.spawn(l, ItemDisplay.class, d -> {
            setUp(d, smoothing);
            d.setItemStack(model(bodyModel));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(spec.bodyScale()), new Quaternionf()));
            if (glow != null) {
                d.setGlowing(true);
                d.setGlowColorOverride(glow);
            }
        });
        if (wheelModel != null) {
            for (VehicleSpec.Wheel w : spec.wheels()) {
                wheels.add(world.spawn(l, ItemDisplay.class, d -> {
                    setUp(d, smoothing);
                    d.setItemStack(model(wheelModel));
                    d.setTransformation(wheel(w, 0));
                }));
            }
        }
        label = world.spawn(l.clone().add(0, spec.height() + 1.1, 0), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.text(Text.mm(name));
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setSeeThrough(false);
            t.setBackgroundColor(Color.fromARGB(90, 10, 20, 40));
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTeleportDuration(smoothing);
            t.setViewRange(0.6f);
            t.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private static void setUp(ItemDisplay d, int smoothing) {
        d.setPersistent(false);
        d.setVisibleByDefault(false);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        d.setTeleportDuration(smoothing);
        d.setInterpolationDuration(smoothing);
        d.setBrightness(new Display.Brightness(15, 15));
        d.setViewRange(1.5f);
        d.setShadowRadius(0);
        d.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
    }

    private static ItemStack model(NamespacedKey key) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(key));
        return it;
    }

    /** A wheel at its place on the kart (the display's +x is the kart's left, +z its front), rolled this far. */
    private static Transformation wheel(VehicleSpec.Wheel w, double roll) {
        Vector3f t = new Vector3f((float) -w.at().right(), (float) w.at().up(), (float) w.at().forward());
        return new Transformation(t, new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf().rotateX((float) (roll % (Math.PI * 4))));
    }

    private Location where(Ghost.Pose p, int ox) {
        return new Location(world, p.x() + ox, p.y() + TrackLayout.BASE_Y, p.z(), p.yaw() + yawOffset, p.pitch());
    }

    boolean valid() {
        return body.isValid() && label.isValid();
    }

    private List<Entity> all() {
        List<Entity> out = new ArrayList<>(wheels);
        out.add(body);
        out.add(label);
        return out;
    }

    /** Shown to exactly these players (the racer and their watchers). */
    void viewers(List<Player> now) {
        Set<UUID> want = new HashSet<>();
        for (Player p : now) {
            want.add(p.getUniqueId());
            if (viewers.add(p.getUniqueId())) for (Entity e : all()) p.showEntity(plugin, e);
        }
        for (UUID u : List.copyOf(viewers)) {
            if (want.contains(u)) continue;
            viewers.remove(u);
            Player p = plugin.getServer().getPlayer(u);
            if (p != null) for (Entity e : all()) p.hideEntity(plugin, e);
        }
    }

    /** To where the ghost is now (track coordinates + the track's x origin). */
    void move(Ghost.Pose p, int ox) {
        ticks++;
        Location l = where(p, ox);
        body.teleport(l);
        for (ItemDisplay w : wheels) w.teleport(l);
        label.teleport(new Location(world, l.getX(), l.getY() + spec.height() + 1.1, l.getZ()));
        // the wheels roll with the way it went
        if (!Double.isNaN(lastX) && !wheels.isEmpty()) {
            double d = Math.hypot(l.getX() - lastX, l.getZ() - lastZ);
            if (d < 8) {
                roll += d / Math.max(0.1, spec.wheelRadius());
                for (int i = 0; i < wheels.size(); i++) {
                    ItemDisplay w = wheels.get(i);
                    w.setInterpolationDelay(0);
                    w.setTransformation(wheel(spec.wheels().get(i), roll));
                }
            }
        }
        lastX = l.getX();
        lastZ = l.getZ();
        // its boost: a pale flame behind it, for its viewers only
        if (p.boost() && ticks % 2 == 0) {
            double r = Math.toRadians(p.yaw());
            double bx = l.getX() + Math.sin(r) * 1.3, bz = l.getZ() - Math.cos(r) * 1.3;
            for (UUID u : viewers) {
                Player v = plugin.getServer().getPlayer(u);
                if (v != null && v.getWorld().equals(world)) v.spawnParticle(Particle.SOUL_FIRE_FLAME, bx, l.getY() + 0.45, bz, 1, 0.06, 0.04, 0.06, 0.005);
            }
        }
    }

    void remove() {
        for (Entity e : all()) e.remove();
        viewers.clear();
    }
}
