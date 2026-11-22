package dev.stsv.economy.secret.lostkey;

import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The solver's keepsake on the road: while they drive a bus, a small key charm hangs at its rear-view mirror (an item
 * display of the key, seen by everyone). It is not part of the vehicle's model (the core's vehicles take no extra parts):
 * it follows the driver's seat each tick, turned with the bus, and swings a little when the bus speeds up or brakes.
 */
final class Charm {

    private final LostKey s;
    /** driver -> their charm */
    private final Map<UUID, Hung> hung = new ConcurrentHashMap<>();
    private BukkitTask follow;

    private static final class Hung {
        final ItemDisplay d;
        double speed;
        float swing;

        Hung(ItemDisplay d) {
            this.d = d;
        }
    }

    Charm(LostKey s) {
        this.s = s;
    }

    /** Where the mirror is from the driver's seat, in blocks: forward, up, right (charm.offset). */
    private double[] offset() {
        List<Double> o = s.cfg().getDoubleList("charm.offset");
        return new double[]{o.size() > 0 ? o.get(0) : 0.8, o.size() > 1 ? o.get(1) : 1.55, o.size() > 2 ? o.get(2) : 0.45};
    }

    /** The mirror's spot for a seat (feet) and the bus's yaw. Pure. */
    static double[] mirror(double x, double y, double z, float yaw, double forward, double up, double right) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);   // the way the bus faces
        double rx = -Math.cos(r), rz = -Math.sin(r);  // its right-hand side
        return new double[]{x + fx * forward + rx * right, y + up, z + fz * forward + rz * right};
    }

    /** The bus this player drives now (a vehicle the bus module counts as a bus), or null. */
    private VehicleModule.Driven bus(Player p) {
        VehicleModule vm = s.core().vehicles();
        BusModule b = s.busModule();
        if (vm == null || b == null) return null;
        VehicleModule.Driven d = vm.drivenBy(p);
        return d != null && b.spec().vehicles().contains(d.itemKey()) ? d : null;
    }

    /** Each second: the charm hangs for these drivers while they drive a bus, and is taken down otherwise. */
    void beat(List<Player> owners) {
        if (!s.cfg().getBoolean("charm.enabled", true)) owners = List.of();
        for (UUID u : List.copyOf(hung.keySet())) {
            Player p = Bukkit.getPlayer(u);
            Hung h = hung.get(u);
            if (p == null || !owners.contains(p) || bus(p) == null || h == null || !h.d.isValid()) take(u);   // gone with its chunk: hung again below
        }
        for (Player p : owners) {
            if (hung.containsKey(p.getUniqueId()) || bus(p) == null) continue;
            hang(p);
        }
        if (!hung.isEmpty() && follow == null) follow = Bukkit.getScheduler().runTaskTimer(s.plugin(), this::tick, 1L, 1L);
        if (hung.isEmpty() && follow != null) {
            follow.cancel();
            follow = null;
        }
    }

    private void hang(Player p) {
        Location at = p.getLocation();
        ItemStack key = Items.create(LostKey.ITEM, 1).orElseGet(() -> new ItemStack(Material.TRIPWIRE_HOOK));
        key.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        float scale = (float) s.cfg().getDouble("charm.scale", 0.32);
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setItemStack(key);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTeleportDuration(2);
            e.setInterpolationDuration(3);
            e.setTransformation(look(0, scale));
        });
        hung.put(p.getUniqueId(), new Hung(d));
        place(p, hung.get(p.getUniqueId()));
    }

    /** Hanging from the top: a little lower than the mirror, tilted by the swing (degrees). */
    private static Transformation look(float swing, float scale) {
        return new Transformation(new Vector3f(0, -scale * 0.5f, 0), new AxisAngle4f((float) Math.toRadians(swing), 1, 0, 0),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    private void tick() {
        for (Map.Entry<UUID, Hung> e : hung.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !e.getValue().d.isValid()) continue;
            place(p, e.getValue());
        }
    }

    private void place(Player p, Hung h) {
        VehicleModule.Driven d = bus(p);
        if (d == null) return;
        Location seat = p.getLocation();
        double[] o = offset();
        double[] at = mirror(seat.getX(), seat.getY(), seat.getZ(), d.yaw(), o[0], o[1], o[2]);
        Location l = new Location(seat.getWorld(), at[0], at[1], at[2], d.yaw(), 0);
        if (l.getWorld() != h.d.getWorld()) {
            take(p.getUniqueId());
            return;
        }
        h.d.teleport(l);
        // a jolt: it swings back as the bus speeds up, forward as it brakes, and settles
        double dv = d.speed() - h.speed;
        h.speed = d.speed();
        float target = (float) Math.clamp(-dv * 120, -30, 30);
        float next = h.swing * 0.85f + target * 0.15f;
        if (Math.abs(next - h.swing) > 0.5f) {
            h.swing = next;
            h.d.setInterpolationDelay(0);
            h.d.setTransformation(look(h.swing, (float) s.cfg().getDouble("charm.scale", 0.32)));
        }
    }

    void take(UUID u) {
        Hung h = hung.remove(u);
        if (h != null && h.d.isValid()) h.d.remove();
    }

    void clear() {
        for (UUID u : List.copyOf(hung.keySet())) take(u);
        if (follow != null) {
            follow.cancel();
            follow = null;
        }
    }
}
