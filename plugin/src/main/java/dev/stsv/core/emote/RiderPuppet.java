package dev.stsv.core.emote;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * A puppet in a player's skin standing on a vehicle (the vehicle module's standing riders: the kick scooter, the
 * segway, the pogo stick, the skateboard). A rider on a vehicle is always drawn sitting by the client, so the rider is
 * hidden and this stands in for them: its displays ride the seat's carrier, so the clients carry it with the vehicle's
 * own glide, frame by frame, without the server moving it; the vehicle poses it ({@link EmoteRig.Pose}, legs planted
 * on the deck) and turns it with the vehicle.
 */
public final class RiderPuppet {

    private final Puppet puppet;
    private final Entity mount;
    private final double viewDistance;

    private RiderPuppet(Puppet puppet, Entity mount, double viewDistance) {
        this.puppet = puppet;
        this.mount = mount;
        this.viewDistance = viewDistance;
    }

    /**
     * A puppet riding mount, its feet lift blocks under the mount's point, facing yaw (degrees), in pose; hidden from
     * the players in never (they could not see the rider).
     */
    public static RiderPuppet mount(Plugin plugin, EmoteModule emotes, Entity mount, double lift, float yaw, PuppetSkin skin,
                                    EmoteRig.Pose pose, Set<UUID> never) {
        Puppet p = Puppet.mounted(plugin, emotes.puppetTag(), mount, lift, yaw, skin, pose, true, never);
        return new RiderPuppet(p, mount, plugin.getConfig().getInt("emotes.view-distance"));
    }

    /** Glides to a pose over ticks (only the faces that move are sent). */
    public void pose(EmoteRig.Pose pose, int ticks) {
        puppet.pose(pose, true, ticks);
    }

    /** Faces this way (sent when it has turned a little: {@link Puppet#TURN_STEP}). */
    public void turn(float yaw) {
        puppet.turn(yaw);
    }

    /** Shown to the players near (the carrier's whereabouts are the mount's). */
    public void show(Iterable<? extends Player> online) {
        if (mount.isValid()) puppet.near(mount.getLocation());
        puppet.showTo(online, viewDistance);
    }

    /** Whether an entity is part of a puppet (the emotes' displays: a vehicle seat lets them ride it). */
    public static boolean isPuppet(Entity e) {
        for (org.bukkit.NamespacedKey k : e.getPersistentDataContainer().getKeys()) if (k.getKey().equals("emote_entity")) return true;
        return false;
    }

    public boolean valid() {
        return mount.isValid() && puppet.valid() && puppet.rides(mount);
    }

    public void remove() {
        puppet.remove();
    }
}
