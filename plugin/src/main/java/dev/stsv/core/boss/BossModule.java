package dev.stsv.core.boss;

import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Bosses: definitions from contents/&lt;pack&gt;/bosses/*.yml, spawned with /stsv boss spawn.
 *
 * The boss body is an invisible iron golem (pathfinding, collision, health, armour and the vanilla
 * punch that throws players up), so it behaves like a real mob. Its look is one item display per bone,
 * riding the golem, posed every tick from the boss's animations. Bosses are not saved: on restart or
 * when their chunk unloads they disappear (a boss fight is an event, not world furniture).
 */
public final class BossModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    NamespacedKey bossKey;
    NamespacedKey partKey;
    private final Map<String, BossSpec> specs = new LinkedHashMap<>();
    private final Map<UUID, BossInstance> active = new LinkedHashMap<>();
    private BukkitTask task;
    private boolean skillHit;

    @Override
    public String id() {
        return "boss";
    }

    @Override
    public void enable(STSVPlugin plugin) throws IOException {
        this.plugin = plugin;
        bossKey = new NamespacedKey(plugin, "boss");
        partKey = new NamespacedKey(plugin, "boss_part");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (World w : Bukkit.getWorlds()) w.getEntities().forEach(this::removeIfStale);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public void reload() throws IOException {
        load();
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        for (BossInstance b : new ArrayList<>(active.values())) end(b);
        HandlerList.unregisterAll(this);
    }

    STSVPlugin plugin() {
        return plugin;
    }

    float viewRange() {
        return (float) plugin.getConfig().getDouble("bosses.view-range", 2.0);
    }

    float riderOffsetAdjust() {
        return (float) plugin.getConfig().getDouble("bosses.rider-offset-adjust", 0.0);
    }

    private void load() throws IOException {
        specs.clear();
        for (ContentPack pack : plugin.content().packs()) {
            Path dir = pack.dir().resolve("bosses");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f.toFile());
                    for (String id : yaml.getKeys(false)) {
                        try {
                            BossSpec spec = BossSpec.parse(id, yaml.getConfigurationSection(id));
                            if (specs.put(id, spec) != null) plugin.getLogger().warning("보스 id 중복: " + id + " (" + f + ")");
                            for (BossSpec.Drop d : spec.drops()) {
                                if (item(d.item(), 1).isEmpty()) plugin.getLogger().warning("보스 " + id + ": 알 수 없는 드롭 아이템 " + d.item());
                            }
                        } catch (RuntimeException e) {
                            plugin.getLogger().warning("보스 '" + id + "' 읽기 실패 (" + f.getFileName() + "): " + e.getMessage());
                        }
                    }
                }
            }
        }
        plugin.getLogger().info("보스 " + specs.size() + "종");
    }

    public Collection<BossSpec> specs() {
        return specs.values();
    }

    public Collection<BossInstance> active() {
        return active.values();
    }

    // ------------------------------------------------------------------------------------------
    // Spawn / end
    // ------------------------------------------------------------------------------------------

    public Optional<BossInstance> spawn(String id, Location at) {
        BossSpec spec = specs.get(id);
        if (spec == null) return Optional.empty();
        Location loc = at.clone();
        loc.setPitch(0);
        IronGolem golem = at.getWorld().spawn(loc, IronGolem.class, g -> {
            g.setPersistent(false);
            g.setRemoveWhenFarAway(false);
            g.setPlayerCreated(false);
            g.setSilent(true);
            g.setCanPickupItems(false);
            g.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
            set(g, Attribute.MAX_HEALTH, spec.health());
            set(g, Attribute.ARMOR, spec.armor());
            set(g, Attribute.MOVEMENT_SPEED, spec.speed());
            set(g, Attribute.ATTACK_DAMAGE, spec.meleeDamage());
            set(g, Attribute.SCALE, spec.scale());
            set(g, Attribute.FOLLOW_RANGE, 40);
            set(g, Attribute.KNOCKBACK_RESISTANCE, 1);
            set(g, Attribute.STEP_HEIGHT, 1.1);
            g.setHealth(spec.health());
            g.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, spec.id());
        });
        // targets are chosen by the boss (players only), not by the golem's village instincts
        Bukkit.getMobGoals().removeAllGoals(golem, GoalType.TARGET);
        BossInstance b = new BossInstance(this, spec, golem, loc);
        active.put(golem.getUniqueId(), b);
        b.updateBar();
        return Optional.of(b);
    }

    private static void set(IronGolem g, Attribute a, double v) {
        var inst = g.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    void end(BossInstance b) {
        if (b.ended) return;
        active.remove(b.golem.getUniqueId());
        b.remove();
    }

    public int removeAll() {
        int n = active.size();
        for (BossInstance b : new ArrayList<>(active.values())) end(b);
        return n;
    }

    private void tick() {
        for (BossInstance b : new ArrayList<>(active.values())) {
            try {
                b.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "보스 " + b.spec.id() + " 처리 중 오류, 제거합니다", e);
                end(b);
            }
        }
    }

    private void removeIfStale(Entity e) {
        var pdc = e.getPersistentDataContainer();
        if ((pdc.has(bossKey) && !active.containsKey(e.getUniqueId())) || (pdc.has(partKey) && !isActivePart(e))) e.remove();
    }

    private boolean isActivePart(Entity e) {
        Entity v = e.getVehicle();
        return v != null && active.containsKey(v.getUniqueId()) || active.values().stream().anyMatch(b -> b.parts.containsValue(e));
    }

    private BossInstance of(Entity e) {
        return e == null ? null : active.get(e.getUniqueId());
    }

    /** Damage dealt by a boss skill (not the vanilla punch). */
    void skillDamage(Runnable r) {
        skillHit = true;
        try {
            r.run();
        } finally {
            skillHit = false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Loot and results
    // ------------------------------------------------------------------------------------------

    private Optional<ItemStack> item(String id, int amount) {
        if (id == null) return Optional.empty();
        Optional<ItemStack> custom = plugin.content().item(id).map(i -> i.create(amount));
        if (custom.isPresent()) return custom;
        Material m = Material.matchMaterial(id);
        return m == null || !m.isItem() ? Optional.empty() : Optional.of(new ItemStack(m, amount));
    }

    void dropLoot(BossInstance b, Location at) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (BossSpec.Drop d : b.spec.drops()) {
            if (r.nextDouble() > d.chance()) continue;
            int amount = d.max() > d.min() ? r.nextInt(d.min(), d.max() + 1) : d.min();
            item(d.item(), amount).ifPresent(it -> at.getWorld().dropItemNaturally(at, it));
        }
    }

    /** Title for everyone who fought, and the damage ranking in chat. */
    void announceDefeat(BossInstance b) {
        double total = b.damageBy.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<UUID, Double>> ranking = b.damageBy.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder())).limit(5).toList();
        List<Player> audience = b.audience();
        for (Player p : audience) {
            p.showTitle(Title.title(Text.mm("<gold><b>처치!"), Text.mm(b.spec.name()),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(800))));
            p.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key("ui.toast.challenge_complete"),
                    net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f));
            p.sendMessage(Text.msg(b.spec.name() + " <gold>처치!"));
            int rank = 1;
            for (Map.Entry<UUID, Double> e : ranking) {
                String name = Optional.ofNullable(Bukkit.getOfflinePlayer(e.getKey()).getName()).orElse("?");
                p.sendMessage(Text.mm("  <gray>" + rank++ + ". <white>" + name + " <dark_gray>- <yellow>"
                        + Math.round(total > 0 ? e.getValue() * 100 / total : 0) + "%"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        BossInstance b = of(e.getEntity());
        if (b == null) return;
        switch (e.getCause()) {
            case FALL, SUFFOCATION, DROWNING, CRAMMING, FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE, FREEZE -> {
                e.setCancelled(true);
                return;
            }
            default -> { }
        }
        if (b.invulnerable()) {
            e.setCancelled(true);
            return;
        }
        Player by = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            Entity d = ee.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Player s) by = s;
            else if (d instanceof Player p) by = p;
            if (d == b.golem) {
                e.setCancelled(true);
                return;
            }
        }
        double amount = Math.min(e.getFinalDamage(), b.golem.getHealth());
        if (amount <= 0) return;
        if (e.getFinalDamage() >= b.golem.getHealth()) {
            e.setCancelled(true);
            if (by != null) b.damageBy.merge(by.getUniqueId(), amount, Double::sum);
            b.golem.setHealth(Math.min(1, b.golem.getHealth()));
            b.startDeath();
            return;
        }
        b.onHurt(amount, by);
    }

    /** The golem's own punch: blocked during patterns, otherwise animated. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossHits(EntityDamageByEntityEvent e) {
        BossInstance b = of(e.getDamager());
        if (b == null || skillHit) return;
        if (!(e.getEntity() instanceof Player) || b.busy() || b.dying || b.spec.meleeDamage() <= 0) {
            e.setCancelled(true);
            return;
        }
        b.onPunch();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (of(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (of(e.getEntity()) != null && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true);
    }

    /** Killed some other way (/kill, void): no vanilla iron golem drops, clean up. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        BossInstance b = of(e.getEntity());
        if (b == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Bukkit.getScheduler().runTask(plugin, () -> end(b));
    }

    @EventHandler
    public void onRemoved(EntityRemoveFromWorldEvent e) {
        BossInstance b = of(e.getEntity());
        if (b != null && !b.ended) Bukkit.getScheduler().runTask(plugin, () -> end(b));
    }

    /** About to eat or drink near a boss: bosses with a "when: heal" pattern punish it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(org.bukkit.event.player.PlayerInteractEvent e) {
        if (active.isEmpty() || e.getItem() == null || e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Material m = e.getItem().getType();
        Player p = e.getPlayer();
        boolean drink = m == Material.POTION;
        var food = e.getItem().getData(io.papermc.paper.datacomponent.DataComponentTypes.FOOD);
        boolean eat = food != null && (p.getFoodLevel() < 20 || food.canAlwaysEat());
        if (!drink && !eat) return;
        for (BossInstance b : active.values()) {
            if (b.golem.getWorld() == p.getWorld() && b.golem.getLocation().distanceSquared(p.getLocation()) < 18 * 18) b.onHealAttempt(p);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        e.getEntities().forEach(this::removeIfStale);
    }
}
