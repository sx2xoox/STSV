package dev.stsv.core.vehicle;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.emote.EmoteModule;
import dev.stsv.core.emote.EmoteRig;
import dev.stsv.core.emote.RiderPuppet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Riders who stand (특별 탈것 with {@code fun.stand}: the kick scooter, the segway, the pogo stick, the skateboard). The
 * client draws anyone riding something as sitting, so the rider is hidden and a puppet in their skin
 * ({@link RiderPuppet}, the emotes' puppet) stands on the deck in their place, posed to the motion ({@link RiderPose}).
 * The puppet rides the seat's carrier, so it glides with the vehicle frame by frame, never a tick behind; the rider's
 * own camera is where its head is (the seat is put so), so their F5 shows it too.
 *
 * <p>Hiding: an invisibility effect without particles or icon (the emotes' own way: it runs out by itself, so a crash
 * leaves nobody hidden for long), kept topped up while they stand, and their gear sent as nothing (armour shows on an
 * invisible player). Given back whenever they get off, by any way {@link VehicleModule#leave} or a dismount sees:
 * sneaking, dying, quitting, a teleport, the vehicle picked up or unloaded, the plugin stopping. Without the emotes
 * (switched off: no puppet font in the pack) riders sit as before.
 */
final class StandingRiders {

    /** The hiding effect lasts this long and is topped up every {@link #REFRESH} ticks while they stand. */
    static final int EFFECT_TICKS = 100, REFRESH = 40;
    /** The pose is sent every this many ticks, glided over as many. */
    static final int POSE_EVERY = 2;
    private static final EquipmentSlot[] GEAR = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final VehicleModule m;
    /** players whose puppet is being made (their skin is on its way) */
    private final Set<UUID> pending = new HashSet<>();

    /** A standing rider: who, and their puppet. */
    record Standing(UUID player, RiderPuppet puppet) {}

    StandingRiders(VehicleModule m) {
        this.m = m;
    }

    private STSVPlugin plugin() {
        return m.plugin();
    }

    private EmoteModule emotes() {
        EmoteModule e = plugin().emotes();
        return e != null && e.puppets() ? e : null;
    }

    /** Whether this vehicle's riders stand (and the puppets can be drawn). */
    boolean stands(LiveVehicle v) {
        return v.spec.fun() != null && v.spec.fun().stand() != null && emotes() != null;
    }

    /** Someone got on: their skin is fetched (or remembered), then their puppet stands on the deck. */
    void board(LiveVehicle v, int seat, Player p) {
        if (seat != 0 || !stands(v)) return;
        EmoteModule e = emotes();
        UUID id = p.getUniqueId();
        if (!pending.add(id)) return;
        e.skinOf(p).thenAccept(skin -> Bukkit.getScheduler().runTask(plugin(), () -> {
            pending.remove(id);
            // still riding this seat?
            if (!p.isOnline() || m.shownById(v.id) != v || v.rider(0) != p || v.standing != null) return;
            LiveVehicle.Seat s = v.seats[0];
            if (s == null || !s.valid() || emotes() == null) return;
            FunSpec.Stand st = v.spec.fun().stand();
            double lift = v.spec.seats().getFirst().up() - st.deck();
            Set<UUID> never = new HashSet<>();
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p && !o.canSee(p)) never.add(o.getUniqueId());
            float yaw = (float) (v.st.yaw + m.shownSettings().yawOffset());
            RiderPuppet puppet = RiderPuppet.mount(plugin(), emotes(), s.carrier(), lift, yaw, skin, EmoteRig.Pose.REST, never);
            v.standing = new Standing(id, puppet);
            hide(p);
            puppet.show(Bukkit.getOnlinePlayers());
        }));
    }

    /**
     * Every tick of a vehicle with a standing rider: the puppet turns with it (plus any spin of its own), takes the pose
     * every {@link #POSE_EVERY} ticks, is shown to who came near; the rider stays hidden. A puppet that lost its seat
     * goes, and its rider shows again.
     */
    void tick(LiveVehicle v, Player rider, RiderPose.Motion motion, double spin, long ticks) {
        Standing s = v.standing;
        if (s == null) return;
        if (rider == null || !rider.getUniqueId().equals(s.player()) || !s.puppet().valid()) {
            Player p = Bukkit.getPlayer(s.player());
            left(v, p);
            return;
        }
        s.puppet().turn((float) (v.st.yaw + m.shownSettings().yawOffset() - spin));
        boolean posing = Math.floorMod(ticks + v.id, POSE_EVERY) == 0;
        if (posing) {
            EmoteRig.Pose pose = RiderPose.pose(v.spec.fun().stand().kind(), motion);
            s.puppet().pose(pose, POSE_EVERY, rider);   // the head follows the rider's look
        }
        // the shadow with the deck; the light at the rider's eye and what they hold
        s.puppet().tick(rider, posing);
        if (Math.floorMod(ticks + v.id, 10) == 0) s.puppet().show(Bukkit.getOnlinePlayers());
        if (Math.floorMod(ticks + v.id, REFRESH) == 0) hide(rider);
    }

    /** The rider is off (or the puppet broke): the puppet goes, the rider shows as they are. */
    void left(LiveVehicle v, Player p) {
        Standing s = v.standing;
        v.standing = null;
        if (s != null) s.puppet().remove();
        if (p != null) {
            pending.remove(p.getUniqueId());
            if (s != null && p.getUniqueId().equals(s.player())) show(p);
        }
    }

    /** Hidden: the effect (topped up) and gear sent as nothing; the worn cosmetics go while invisible. */
    private void hide(Player p) {
        boolean fresh = !ours(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, EFFECT_TICKS, 0, true, false, false));
        Map<EquipmentSlot, ItemStack> none = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : GEAR) none.put(slot, ItemStack.empty());
        for (Player o : p.getTrackedBy()) if (o != p) o.sendEquipmentChange(p, none);
        Map<EquipmentSlot, ItemStack> armour = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : ARMOUR) armour.put(slot, ItemStack.empty());
        p.sendEquipmentChange(p, armour);
        if (fresh && plugin().cosmetics() != null) plugin().cosmetics().refresh(p);
    }

    /** This module's effect (ambient, no particles, no icon), not a potion they drank. */
    private static boolean ours(Player p) {
        PotionEffect e = p.getPotionEffect(PotionEffectType.INVISIBILITY);
        return e != null && e.isAmbient() && !e.hasParticles() && !e.hasIcon() && e.getDuration() <= EFFECT_TICKS;
    }

    /** Shown again: the effect off, the real gear for everyone, the worn cosmetics back (a moment later, when visible). */
    private void show(Player p) {
        if (!p.isOnline()) return;
        if (ours(p)) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        showGear(p);
        for (long delay : new long[]{1, 10}) {
            Bukkit.getScheduler().runTaskLater(plugin(), () -> {
                if (!p.isOnline() || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return;
                if (plugin().cosmetics() != null && !plugin().cosmetics().inWardrobe(p)) plugin().cosmetics().refresh(p);
                showGear(p);
            }, delay);
        }
    }

    private static void showGear(Player p) {
        Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : GEAR) {
            ItemStack item = p.getInventory().getItem(slot);
            real.put(slot, item == null ? ItemStack.empty() : item);
        }
        for (Player o : p.getTrackedBy()) if (o != p) o.sendEquipmentChange(p, real);
        p.updateInventory();
    }
}
