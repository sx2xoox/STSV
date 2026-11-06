package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pets (cosmetic slot PET): an item display with the pet's model (feet on model y 0, so the display
 * stands half a block above the ground) that trots after its owner. Unlike the worn models it is meant
 * to follow, not to be attached: each tick it moves part of the way to a spot behind the owner's right
 * side, turns the way it walks (or the owner's way when standing), hops while walking, and floating
 * pets hover and bob. Far behind (a teleport) or another world: it appears at the spot.
 */
final class CosmeticPets {

    /** Behind the owner's right: blocks to the right, behind. */
    static final double SIDE = 0.85, BEHIND = 0.75;
    /** Share of the way to the spot each tick; distance to give up following and appear there. */
    static final double FOLLOW = 0.16, SNAP = 10;

    private static final class Pet {
        final ItemDisplay display;
        final CustomItem item;
        Vector pos;
        float yaw;
        long t;

        Pet(ItemDisplay display, CustomItem item, Vector pos, float yaw) {
            this.display = display;
            this.item = item;
            this.pos = pos;
            this.yaw = yaw;
        }
    }

    private final Plugin plugin;
    private final NamespacedKey tag;
    private final Map<UUID, Pet> pets = new HashMap<>();
    private long ticks;

    CosmeticPets(Plugin plugin, NamespacedKey tag) {
        this.plugin = plugin;
        this.tag = tag;
    }

    /** The spot a pet heads for: behind the owner's right, at their feet. */
    static Vector spot(Location owner) {
        double yaw = Math.toRadians(owner.getYaw());
        // Minecraft yaw: 0 = south (+z); the right of someone facing (fx, fz) is (-fz, fx)
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -fz, rz = fx;
        return new Vector(owner.getX() + rx * SIDE - fx * BEHIND, owner.getY(), owner.getZ() + rz * SIDE - fz * BEHIND);
    }

    /** The pet the player has out now (null: none). Spawns, swaps or removes. */
    void set(Player p, CustomItem pet) {
        Pet cur = pets.get(p.getUniqueId());
        if (cur != null && pet != null && cur.item.key().equals(pet.key()) && cur.display.isValid() && cur.display.getWorld() == p.getWorld()) return;
        remove(p);
        if (pet == null) return;
        Vector at = spot(p.getLocation());
        float yaw = p.getLocation().getYaw();
        ItemDisplay d = p.getWorld().spawn(location(p.getWorld(), at, yaw, pet, 0), ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(pet.create(1));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTeleportDuration(2);
            e.setShadowRadius(0.25f);
            e.setShadowStrength(0.6f);
        });
        for (Player v : p.getWorld().getPlayers()) if (v == p || v.canSee(p)) v.showEntity(plugin, d);
        pets.put(p.getUniqueId(), new Pet(d, pet, at, yaw));
    }

    void remove(Player p) {
        Pet cur = pets.remove(p.getUniqueId());
        if (cur != null) cur.display.remove();
    }

    void removeAll() {
        pets.values().forEach(x -> x.display.remove());
        pets.clear();
    }

    private static boolean floating(CustomItem pet) {
        return pet.cosmetic() != null && pet.cosmetic().fx() != null && pet.cosmetic().fx().floating();
    }

    /** Where the display stands for a pet whose feet are at `feet`. */
    private static Location location(World w, Vector feet, float yaw, CustomItem pet, long t) {
        double y = feet.getY() + 0.5;
        if (floating(pet)) y += 0.9 + Math.sin(t * 0.12) * 0.08;
        return new Location(w, feet.getX(), y, feet.getZ(), yaw, 0);
    }

    void tick() {
        ticks++;
        boolean sweep = ticks % 5 == 0;
        for (Map.Entry<UUID, Pet> e : new ArrayList<>(pets.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Pet pet = e.getValue();
            if (p == null || !pet.display.isValid()) {
                pet.display.remove();
                pets.remove(e.getKey());
                continue;
            }
            if (pet.display.getWorld() != p.getWorld()) {
                set(p, null);
                set(p, pet.item);
                continue;
            }
            pet.t++;
            Location owner = p.getLocation();
            Vector target = spot(owner);
            Vector before = pet.pos.clone();
            if (pet.pos.distanceSquared(target) > SNAP * SNAP) pet.pos = target;
            else pet.pos = pet.pos.add(target.clone().subtract(pet.pos).multiply(FOLLOW));
            Vector step = pet.pos.clone().subtract(before);
            double moved = Math.hypot(step.getX(), step.getZ());
            if (moved > 0.02) pet.yaw = (float) Math.toDegrees(Math.atan2(-step.getX(), step.getZ()));
            else pet.yaw = turnToward(pet.yaw, owner.getYaw(), 6);
            Location at = location(p.getWorld(), pet.pos, pet.yaw, pet.item, pet.t);
            if (!floating(pet.item) && moved > 0.03) at.add(0, Math.abs(Math.sin(pet.t * 0.55)) * 0.12, 0);   // hop
            pet.display.teleport(at);
            if (sweep) {
                for (Player v : p.getWorld().getPlayers()) {
                    boolean want = v == p || v.canSee(p);
                    if (want != v.canSee(pet.display)) {
                        if (want) v.showEntity(plugin, pet.display);
                        else v.hideEntity(plugin, pet.display);
                    }
                }
            }
        }
    }

    private static float turnToward(float from, float to, float maxStep) {
        float d = to - from;
        while (d >= 180) d -= 360;
        while (d < -180) d += 360;
        return from + Math.max(-maxStep, Math.min(maxStep, d));
    }

    /** A pet for the wardrobe mannequin (visible to nobody until shown). */
    ItemDisplay spawnPreview(Location mannequin, CustomItem pet) {
        Vector at = spot(mannequin);
        return mannequin.getWorld().spawn(location(mannequin.getWorld(), at, mannequin.getYaw(), pet, 0), ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            e.setItemStack(pet.create(1));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setShadowRadius(0.25f);
        });
    }
}
