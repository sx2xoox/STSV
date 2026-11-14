package dev.stsv.core.fun;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * The party firework (무한 폭죽): an item that sends up a firework each time it is used and is never used up. For
 * show only: its fireworks hurt nobody, it does not push a glider (it is not a rocket in the hand, and it refuses
 * while gliding), and it waits a moment between shots ({@code party-firework.cooldown-seconds}).
 */
public final class PartyFireworkModule implements StsvModule, Listener {

    /** The shapes and colours a shot is drawn from. */
    static final FireworkEffect.Type[] TYPES = {FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST};
    static final int[] COLOURS = {0xff4d4d, 0xff9f3d, 0xffe14d, 0x6fe36f, 0x4dd2ff, 0x5a7dff, 0xb46bff, 0xff7ad1, 0xffffff};

    private STSVPlugin plugin;
    private NamespacedKey keyShot;
    private final Map<UUID, Long> last = new HashMap<>();
    private final Random random = new Random();

    @Override
    public String id() {
        return "party-firework";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keyShot = new NamespacedKey(plugin, "party_firework");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        last.clear();
    }

    /** Whether a stack is the party firework (config party-firework.item). */
    public boolean is(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Optional<CustomItem> item = plugin.content().itemOf(stack);
        return item.isPresent() && item.get().key().toString().equals(plugin.getConfig().getString("party-firework.item"));
    }

    /** How long a player still has to wait (ms; 0: may shoot). */
    static long wait(long lastShot, long now, double cooldownSeconds) {
        return Math.max(0, lastShot + Math.round(cooldownSeconds * 1000) - now);
    }

    /** One shot's look: a shape, one or two colours and a fade, sometimes a trail or a twinkle. */
    static FireworkEffect effect(Random r) {
        int a = COLOURS[r.nextInt(COLOURS.length)], b = COLOURS[r.nextInt(COLOURS.length)], fade = COLOURS[r.nextInt(COLOURS.length)];
        FireworkEffect.Builder e = FireworkEffect.builder().with(TYPES[r.nextInt(TYPES.length)]).withColor(Color.fromRGB(a));
        if (r.nextBoolean()) e.withColor(Color.fromRGB(b));
        e.withFade(Color.fromRGB(fade));
        if (r.nextBoolean()) e.trail(true);
        if (r.nextInt(3) == 0) e.flicker(true);
        return e.build();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() == null || !is(e.getItem())) return;
        e.setCancelled(true);                       // never placed, never used up
        if (e.getHand() != EquipmentSlot.HAND && is(e.getPlayer().getInventory().getItemInMainHand())) return;   // one shot a click
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("party-firework.enabled")) return;
        if (p.isGliding() || p.isInsideVehicle() || p.isSwimming()) {
            p.sendActionBar(Lang.mm("party-firework.not-now"));
            return;
        }
        long now = System.currentTimeMillis();
        long wait = wait(last.getOrDefault(p.getUniqueId(), 0L), now, plugin.getConfig().getDouble("party-firework.cooldown-seconds"));
        if (wait > 0) {
            p.sendActionBar(Lang.mm("party-firework.wait", "seconds", String.format("%.1f", wait / 1000.0)));
            return;
        }
        last.put(p.getUniqueId(), now);
        Location from = p.getEyeLocation().add(p.getLocation().getDirection().setY(0).multiply(0.6));
        p.getWorld().spawn(from, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(effect(random));
            meta.setPower(1);
            fw.setFireworkMeta(meta);
            fw.setShooter(p);
            fw.getPersistentDataContainer().set(keyShot, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** The show hurts nobody: not the shooter, not bystanders, not animals. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework fw && fw.getPersistentDataContainer().has(keyShot)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        last.remove(e.getPlayer().getUniqueId());
    }

    /** For the tests: the pools a shot is drawn from. */
    static List<Integer> colours() {
        return java.util.Arrays.stream(COLOURS).boxed().toList();
    }
}
