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

    /** Left click in the air or on a block. Right-clicks also swing the arm, but must not slash. */
    @EventHandler
    public void onLeftClick(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (e.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_AIR
                && e.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) return;
        swing(e.getPlayer());
    }

    /** Hitting an entity does not fire a left-click interact event. */
    @EventHandler
    public void onAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        swing(e.getPlayer());
    }

    private void swing(Player p) {
        if (p.isHandRaised()) return;
        effects(p.getInventory().getItemInMainHand()).filter(fx -> fx.swingModel() != null).ifPresent(fx -> {
            long now = p.getWorld().getGameTime();
            if (now - lastSwing.getOrDefault(p.getUniqueId(), -100L) < 5) return; // one slash per swing
            lastSwing.put(p.getUniqueId(), now);
            slash(p, fx.swingModel(), fx.swingScale(), 35);
            p.getWorld().playSound(Sound.sound(fx.swingSound(), Sound.Source.PLAYER, 0.8f, 1.15f), p);
        });
    }

    @EventHandler
    public void onRelease(PlayerStopUsingItemEvent e) {
        effects(e.getItem()).filter(fx -> fx.chargeTicks() > 0 && fx.swingModel() != null).ifPresent(fx -> {
            Player p = e.getPlayer();
            if (e.getTicksHeldFor() < fx.chargeTicks()) return;
            slash(p, fx.swingModel(), fx.swingScale() * 1.6f, 10);
            Bukkit.getScheduler().runTaskLater(plugin, () -> slash(p, fx.swingModel(), fx.swingScale() * 1.4f, 70), 3L);
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
     * One slash, oriented from the player's own view (not from entity yaw/pitch, which is what made the
     * old angle wrong): the arc lies in the plane spanned by the look direction and a tilted "right-up"
     * axis, and sweeps from the upper right to the lower left like the vanilla arm swing (mirrored for
     * left-handed players).
     *
     * Space notes: the display has yaw = pitch = 0; the item renderer turns the model 180° around Y, so
     * in transformation space the arc's front is +Z and the arc spans the XZ plane around the centre.
     *
     * @param tilt degrees the arc plane is rolled from horizontal (35 ≈ the vanilla diagonal swing)
     */
    private void slash(Player p, NamespacedKey model, float scale, float tilt) {
        Location eye = p.getEyeLocation();
        Vector f = eye.getDirection().normalize();
        Vector worldUp = Math.abs(f.getY()) > 0.98 ? new Vector(0, 0, 1) : new Vector(0, 1, 0);
        Vector right = f.clone().crossProduct(worldUp).normalize();      // player's right
        Vector up = right.clone().crossProduct(f).normalize();           // player's up
        boolean leftHanded = p.getMainHand() == org.bukkit.inventory.MainHand.LEFT;
        Vector side = leftHanded ? right.clone().multiply(-1) : right;
        double t = Math.toRadians(tilt);
        // arc axis: towards the weapon side and up (start of the swing)
        Vector a = side.clone().multiply(Math.cos(t)).add(up.clone().multiply(Math.sin(t))).normalize();
        Vector n = f.clone().crossProduct(a).normalize();                // plane normal (x × y = z holds)
        org.joml.Matrix3f basis = new org.joml.Matrix3f(
                (float) a.getX(), (float) a.getY(), (float) a.getZ(),     // column 0: local X
                (float) n.getX(), (float) n.getY(), (float) n.getZ(),     // column 1: local Y
                (float) f.getX(), (float) f.getY(), (float) f.getZ());    // column 2: local Z (front)
        Quaternionf orient = new Quaternionf().setFromNormalized(basis);

        // pivot a little in front of the eyes, slightly to the weapon side and down
        Location at = eye.clone().add(f.clone().multiply(0.35)).add(side.clone().multiply(0.2)).add(up.clone().multiply(-0.25));
        at.setYaw(0);
        at.setPitch(0);
        ItemStack look = new ItemStack(Material.PAPER);
        look.editMeta(m -> m.setItemModel(model));
        ItemDisplay d = p.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(look);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(orient).rotateY((float) Math.toRadians(65)),
                    new Vector3f(scale * 0.6f), new Quaternionf()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(orient).rotateY((float) Math.toRadians(-55)),
                    new Vector3f(scale), new Quaternionf()));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(orient).rotateY((float) Math.toRadians(-80)),
                    new Vector3f(scale * 1.1f, scale * 0.15f, scale * 1.1f), new Quaternionf()));
        }, 4L);
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, 6L);
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, eye.clone().add(f.clone().multiply(1.2)), 1, 0, 0, 0, 0);
    }
}
