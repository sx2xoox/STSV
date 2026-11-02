package dev.stsv.core.effect;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Weapon effects that everyone sees:
 *  - swing: a glowing slash sweeps in front of the player (a short-lived display entity whose rotation
 *    and size the client interpolates)
 *  - charge: hold right-click (the item is raised); after the charge time the model turns fully charged
 *    (item definition), and releasing fires two big crossing slashes with particles and sound.
 * Purely visual: damage is left to the vanilla weapon.
 */
public final class ItemEffectsModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    private final Map<UUID, Boolean> flip = new HashMap<>();

    @Override
    public String id() {
        return "item-effects";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    private Optional<CustomItem.Effects> effects(ItemStack item) {
        return plugin.content().itemOf(item).map(CustomItem::effects);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (p.isHandRaised()) return;
        effects(p.getInventory().getItemInMainHand()).filter(fx -> fx.swingModel() != null).ifPresent(fx -> {
            long now = p.getWorld().getGameTime();
            if (now - lastSwing.getOrDefault(p.getUniqueId(), -100L) < 5) return; // one slash per swing
            lastSwing.put(p.getUniqueId(), now);
            boolean f = !flip.getOrDefault(p.getUniqueId(), false);
            flip.put(p.getUniqueId(), f);
            slash(p, fx.swingModel(), fx.swingScale(), f ? 18 : -18, 0);
            p.getWorld().playSound(Sound.sound(fx.swingSound(), Sound.Source.PLAYER, 0.8f, 1.15f), p);
        });
    }

    @EventHandler
    public void onRelease(PlayerStopUsingItemEvent e) {
        effects(e.getItem()).filter(fx -> fx.chargeTicks() > 0 && fx.swingModel() != null).ifPresent(fx -> {
            Player p = e.getPlayer();
            if (e.getTicksHeldFor() < fx.chargeTicks()) return;
            slash(p, fx.swingModel(), fx.swingScale() * 1.6f, 10, 0);
            Bukkit.getScheduler().runTaskLater(plugin, () -> slash(p, fx.swingModel(), fx.swingScale() * 1.4f, 75, 0.2), 3L);
            Location at = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(2));
            p.getWorld().spawnParticle(Particle.END_ROD, at, 40, 0.8, 0.5, 0.8, 0.08);
            p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 60, 1.0, 0.6, 1.0, 0.2);
            p.getWorld().playSound(Sound.sound(fx.chargeSound(), Sound.Source.PLAYER, 0.7f, 1.3f), p);
            lastSwing.put(p.getUniqueId(), p.getWorld().getGameTime());
        });
    }

    /** The charge uses a consumable component that must never finish. */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (effects(e.getItem()).filter(fx -> fx.chargeTicks() > 0).isPresent()) e.setCancelled(true);
    }

    /**
     * One slash: spawned small and turned to one side, then (next tick, so the client can interpolate)
     * swept across while growing, and removed shortly after.
     *
     * @param roll  tilt of the arc in degrees (alternating swings look less repetitive)
     * @param lift  extra height in blocks
     */
    private void slash(Player p, NamespacedKey model, float scale, float roll, double lift) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Location at = eye.clone().add(dir.clone().multiply(1.3)).add(0, -0.35 + lift, 0);
        at.setYaw(eye.getYaw() + (float) plugin.getConfig().getDouble("furniture.display-yaw-offset", 0));
        at.setPitch(eye.getPitch());
        ItemStack look = new ItemStack(Material.PAPER);
        look.editMeta(m -> m.setItemModel(model));
        Quaternionf tilt = new Quaternionf().rotateZ((float) Math.toRadians(roll));
        ItemDisplay d = p.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(look);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(tilt).rotateY((float) Math.toRadians(70)),
                    new Vector3f(scale * 0.45f), new Quaternionf()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(tilt).rotateY((float) Math.toRadians(-70)),
                    new Vector3f(scale), new Quaternionf()));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(tilt).rotateY((float) Math.toRadians(-95)),
                    new Vector3f(scale * 1.15f, scale * 0.2f, scale * 1.15f), new Quaternionf()));
        }, 4L);
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, 7L);
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0);
    }
}
