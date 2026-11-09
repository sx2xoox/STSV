package dev.stsv.core.world;

import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * 건축월드 평화로움 (worlds.build-peaceful): no hostile mob lives in the build world, while the difficulty (and with it
 * hunger) stays what the server set. Hostile = {@link Enemy} (monsters, phantoms, slimes, ghasts, shulkers, hoglins,
 * the dragon). Their natural, spawner, raid, patrol, siege, conversion... spawns are refused before the mob is made;
 * raids do not start; hostiles that come in anyway (in a loaded chunk, through a portal, by a teleport) are removed
 * when their chunk loads and by a sweep every few seconds. Only an admin's spawns (commands, plugins, spawn eggs) stay.
 */
final class PeacefulBuild implements Listener {

    /** Spawns that stay: an admin or a plugin asked for that very mob. */
    static final Set<SpawnReason> KEPT = EnumSet.of(SpawnReason.CUSTOM, SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG);

    private final BooleanSupplier on;
    private final Predicate<World> isBuild;

    PeacefulBuild(BooleanSupplier on, Predicate<World> isBuild) {
        this.on = on;
        this.isBuild = isBuild;
    }

    /** Pure: is a mob of this type hostile? */
    static boolean hostile(EntityType type) {
        Class<? extends Entity> c = type == null ? null : type.getEntityClass();
        return c != null && Enemy.class.isAssignableFrom(c);
    }

    /** Pure: is a hostile spawn for this reason refused in a peaceful build world? */
    static boolean refused(SpawnReason reason) {
        return reason == null || !KEPT.contains(reason);
    }

    private boolean peaceful(World w) {
        return w != null && on.getAsBoolean() && isBuild.test(w);
    }

    /** Before the mob is made (natural and spawner spawns): stop the server from trying again this cycle too. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreSpawn(PreCreatureSpawnEvent e) {
        if (!hostile(e.getType()) || !refused(e.getReason()) || !peaceful(e.getSpawnLocation().getWorld())) return;
        e.setCancelled(true);
        e.setShouldAbortSpawn(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Enemy && refused(e.getSpawnReason()) && peaceful(e.getLocation().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRaid(RaidTriggerEvent e) {
        if (peaceful(e.getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!peaceful(e.getWorld())) return;
        for (Entity en : e.getEntities()) if (stray(en)) en.remove();   // (whatever slips through: the sweep)
    }

    /** Every few seconds: hostiles that walked, fell or teleported in. */
    void sweep() {
        if (!on.getAsBoolean()) return;
        for (World w : Bukkit.getWorlds()) {
            if (!isBuild.test(w)) continue;
            for (LivingEntity en : w.getLivingEntities()) if (en.isValid() && stray(en)) en.remove();
        }
    }

    private static boolean stray(Entity en) {
        return en instanceof Enemy && refused(en.getEntitySpawnReason());
    }
}
