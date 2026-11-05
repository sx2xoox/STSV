package dev.stsv.rpg.combat;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.rpg.RpgPlugin;
import dev.stsv.rpg.balance.Balance;
import dev.stsv.rpg.balance.Damage;
import dev.stsv.rpg.balance.Element;
import dev.stsv.rpg.balance.Role;
import dev.stsv.rpg.player.PlayerModule;
import dev.stsv.rpg.player.RpgPlayer;
import dev.stsv.rpg.stat.Stat;
import dev.stsv.rpg.stat.StatBlock;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces vanilla combat between players and creatures with the RPG formula.
 *
 * - A player's left-click on a creature is the basic attack (vanilla attack and its cooldown are off).
 * - Arrows and tridents from players hit with the same formula.
 * - Creatures hit players with their level's attack (Balance.mobAttack) against the player's defense.
 * - Everything else (falling, fire, mobs fighting each other, iron golems) stays vanilla; because RPG
 *   health is the share of the vanilla bar, it scales by itself.
 *
 * Every creature gets a level and a role the first time it matters: from its distance to the world
 * spawn (config mobs.blocks-per-level) and whether it is hostile. They are stored on the entity, so a
 * creature keeps them after a restart. The core's own bosses are left alone (they run their own fight).
 */
public final class CombatModule implements StsvModule, Listener {

    public static final NamespacedKey LEVEL = new NamespacedKey("stsv", "rpg_level");
    public static final NamespacedKey ROLE = new NamespacedKey("stsv", "rpg_role");
    public static final NamespacedKey ELEMENT = new NamespacedKey("stsv", "rpg_element");
    private static final NamespacedKey CORE_BOSS = new NamespacedKey("stsv", "boss");
    private static final NamespacedKey CORE_BOSS_PART = new NamespacedKey("stsv", "boss_part");

    /** A creature's combat identity. */
    public record MobInfo(int level, Role role, Element element) {}

    private final RpgPlugin rpg;
    /** set while this module deals damage itself, so its own listener lets it through */
    private boolean applying;
    private final Map<UUID, String> lastHitBy = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHitAt = new ConcurrentHashMap<>();

    public CombatModule(RpgPlugin rpg) {
        this.rpg = rpg;
    }

    @Override
    public String id() {
        return "rpg-combat";
    }

    @Override
    public void enable(STSVPlugin core) {
        Bukkit.getPluginManager().registerEvents(this, rpg);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    private Balance balance() {
        return rpg.balance();
    }

    private PlayerModule players() {
        return rpg.players();
    }

    // ------------------------------------------------------------------------------------------
    // Creatures
    // ------------------------------------------------------------------------------------------

    /** The creature's level/role/element, assigned on first use; null for players and things outside RPG combat. */
    public MobInfo info(Entity e) {
        if (!(e instanceof LivingEntity le) || e instanceof Player || e instanceof ArmorStand) return null;
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        if (pdc.has(CORE_BOSS) || pdc.has(CORE_BOSS_PART)) return null;
        Integer level = pdc.get(LEVEL, PersistentDataType.INTEGER);
        String role = pdc.get(ROLE, PersistentDataType.STRING);
        String element = pdc.get(ELEMENT, PersistentDataType.STRING);
        if (level == null || role == null) {
            level = levelAt(le.getLocation());
            role = defaultRole(le).id();
            pdc.set(LEVEL, PersistentDataType.INTEGER, level);
            pdc.set(ROLE, PersistentDataType.STRING, role);
        }
        Role r;
        Element el;
        try {
            r = Role.parse(role);
        } catch (IllegalArgumentException ex) {
            r = Role.NORMAL;
        }
        try {
            el = Element.parse(element);
        } catch (IllegalArgumentException ex) {
            el = Element.NONE;
        }
        return new MobInfo(Math.max(1, Math.min(balance().levelCap, level)), r, el);
    }

    /**
     * Vanilla bosses are lords; anything that can fight (hostile mobs, but also iron golems, wolves,
     * bees) is a normal mob; the rest (animals, villagers) are critters.
     */
    private static Role defaultRole(LivingEntity le) {
        if (le instanceof org.bukkit.entity.EnderDragon || le instanceof org.bukkit.entity.Wither) return Role.BOSS;
        if (le instanceof Enemy || le.getAttribute(Attribute.ATTACK_DAMAGE) != null) return Role.NORMAL;
        return Role.CRITTER;
    }

    /** Sets a creature's level/role/element (spawned bosses, admin test mobs). */
    public void assign(LivingEntity e, int level, Role role, Element element) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(LEVEL, PersistentDataType.INTEGER, level);
        pdc.set(ROLE, PersistentDataType.STRING, role.id());
        pdc.set(ELEMENT, PersistentDataType.STRING, element.id());
    }

    /** Level of a creature appearing here: farther from the world spawn = stronger, with a floor per world. */
    public int levelAt(Location l) {
        World w = l.getWorld();
        int min = Math.max(1, rpg.getConfig().getInt("mobs.min-level." + w.getName(), 1));
        double per = Math.max(1, rpg.getConfig().getDouble("mobs.blocks-per-level"));
        Location spawn = w.getSpawnLocation();
        double dist = Math.hypot(l.getX() - spawn.getX(), l.getZ() - spawn.getZ());
        return Math.max(1, Math.min(balance().levelCap, Math.max(min, 1 + (int) (dist / per))));
    }

    public double maxHealth(MobInfo m) {
        return balance().mobHealth(m.level(), m.role());
    }

    private static double vanillaMax(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    /** Current RPG health of a creature (share of its vanilla health). */
    public double health(LivingEntity e, MobInfo m) {
        return e.getHealth() / vanillaMax(e) * maxHealth(m);
    }

    // ------------------------------------------------------------------------------------------
    // Basic attack
    // ------------------------------------------------------------------------------------------

    /** Left-click on a creature: the vanilla attack never happens; the basic attack does, at its own pace. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeftClick(PrePlayerAttackEntityEvent e) {
        Entity target = e.getAttacked();
        if (!(target instanceof LivingEntity le) || target instanceof ArmorStand) return; // frames, boats, armor stands: vanilla
        if (info(le) == null && !(le instanceof Player)) return;                         // core bosses: theirs
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        basicAttack(p, le);
    }

    public void basicAttack(Player p, LivingEntity target) {
        RpgPlayer rp = players().get(p);
        long now = System.currentTimeMillis();
        if (now < rp.nextAttackAt) return;
        double interval = balance().basicInterval / (1 + rp.stats().get(Stat.ATTACK_SPEED));
        rp.nextAttackAt = now + (long) (interval * 1000);
        hit(p, target, 1.0, false, false);
    }

    /**
     * One hit from a player with a skill multiplier (1 = plain basic attack). Skills call this too.
     *
     * @param skill    skill hits get the skill-damage stat; plain basic attacks do not
     * @param forceCrit weak-point hits
     */
    public Damage.Result hit(Player p, LivingEntity target, double mult, boolean skill, boolean forceCrit) {
        RpgPlayer rp = players().get(p);
        StatBlock s = rp.stats();
        Damage.Hit h = new Damage.Hit(s.get(Stat.ATTACK), mult, s.get(Stat.CRIT_CHANCE), s.get(Stat.CRIT_DAMAGE),
                skill ? s.get(Stat.SKILL_DAMAGE) : 0, s.get(Stat.ELEMENT_DAMAGE), weaponElement(p), rp.level(), forceCrit);
        rp.lastCombatAt = System.currentTimeMillis();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (target instanceof Player victim) {
            if (!rpg.getConfig().getBoolean("combat.pvp") || victim.equals(p)) return null;
            RpgPlayer vr = players().get(victim);
            Damage.Result r = Damage.compute(balance(), h, playerTarget(victim, h.element()), rnd.nextDouble(), rnd.nextDouble(-1, 1), 1);
            damagePlayer(victim, r.amount(), p, p.getName());
            vr.lastCombatAt = rp.lastCombatAt;
            return r;
        }
        MobInfo m = info(target);
        if (m == null) return null;
        Damage.Target t = new Damage.Target(balance().mobDefense(m.level()), m.element(), 0);
        Damage.Result r = Damage.compute(balance(), h, t, rnd.nextDouble(), rnd.nextDouble(-1, 1), 1);
        damageMob(p, target, m, r);
        return r;
    }

    /** The element of the held weapon (the gear system writes it); none for now. */
    private Element weaponElement(Player p) {
        String s = p.getInventory().getItemInMainHand().hasItemMeta()
                ? p.getInventory().getItemInMainHand().getPersistentDataContainer().get(ELEMENT, PersistentDataType.STRING) : null;
        try {
            return Element.parse(s);
        } catch (IllegalArgumentException e) {
            return Element.NONE;
        }
    }

    private Damage.Target playerTarget(Player p, Element attackElement) {
        StatBlock s = players().get(p).stats();
        Stat resist = Stat.resist(attackElement);
        return new Damage.Target(s.get(Stat.DEFENSE), Element.NONE, resist == null ? 0 : s.get(resist));
    }

    // ------------------------------------------------------------------------------------------
    // Applying damage
    // ------------------------------------------------------------------------------------------

    private void damageMob(Player by, LivingEntity target, MobInfo m, Damage.Result r) {
        double max = maxHealth(m);
        double left = health(target, m) - r.amount();
        if (target instanceof Mob mob && mob.getTarget() == null && m.role() != Role.CRITTER) mob.setTarget(by);
        if (left <= 0) {
            applying = true;
            try {
                target.setNoDamageTicks(0);
                target.damage(Math.max(1e6, target.getHealth() * 1000),
                        DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(by).withDirectEntity(by).build());
            } finally {
                applying = false;
            }
        } else {
            target.setHealth(Math.max(0.0001, left / max * vanillaMax(target)));
            target.playHurtAnimation(yawFrom(by.getLocation(), target.getLocation()));
            Sound hurt = target.getHurtSound();
            if (hurt != null) target.getWorld().playSound(target.getLocation(), hurt, 1f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
            Vector d = target.getLocation().toVector().subtract(by.getLocation().toVector()).setY(0);
            if (d.lengthSquared() > 1e-6) target.knockback(rpg.getConfig().getDouble("combat.knockback"), -d.getX(), -d.getZ());
        }
        showTarget(by, target, m, r, Math.max(0, left), max);
    }

    /** Until the floating numbers and health bars arrive: the target's state on the attacker's action bar. */
    private void showTarget(Player p, LivingEntity target, MobInfo m, Damage.Result r, double hp, double max) {
        int cells = 10, full = (int) Math.ceil(hp / max * cells);
        String bar = "▮".repeat(Math.max(0, full)) + "<dark_gray>" + "▮".repeat(cells - Math.max(0, full)) + "</dark_gray>";
        String dmg = Lang.raw(r.crit() ? "rpg.target-dmg-crit" : "rpg.target-dmg", "dmg", PlayerModule.fmt(r.rounded()));
        HudModule.flash(p, Lang.mm("rpg.target", "name", nameOf(target), "level", m.level(), "bar", bar,
                "hp", PlayerModule.fmt(Math.ceil(hp)), "max", PlayerModule.fmt(max), "dmg", dmg));
    }

    /** A display name for messages: the custom name, or the client's own name for the creature type. */
    public static String nameOf(Entity e) {
        if (e.customName() != null) return MiniMessage.miniMessage().serialize(e.customName());
        if (e instanceof Player p) return MiniMessage.miniMessage().escapeTags(p.getName());
        return "<lang:" + e.getType().translationKey() + ">";
    }

    /**
     * Deals RPG damage to a player with the hurt animation, sound and knockback. Lethal damage kills
     * through vanilla so death events and messages run; {@code byName} names the killer in the message.
     */
    public void damagePlayer(Player victim, double amount, Entity by, String byName) {
        if (victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) return;
        if (victim.isBlocking()) amount *= rpg.getConfig().getDouble("combat.shield-taken");
        players().get(victim).lastCombatAt = System.currentTimeMillis();
        lastHitBy.put(victim.getUniqueId(), byName);
        lastHitAt.put(victim.getUniqueId(), System.currentTimeMillis());
        double left = players().health(victim) - amount;
        if (!players().setHealth(victim, left)) {
            applying = true;
            try {
                victim.setNoDamageTicks(0);
                DamageSource.Builder src = DamageSource.builder(by instanceof Player ? DamageType.PLAYER_ATTACK : DamageType.MOB_ATTACK);
                if (by != null) src.withCausingEntity(by).withDirectEntity(by);
                victim.damage(1e6, src.build());
            } finally {
                applying = false;
            }
            return;
        }
        float yaw = by == null ? 0 : yawFrom(by.getLocation(), victim.getLocation());
        victim.playHurtAnimation(yaw);
        victim.getWorld().playSound(victim.getLocation(), victim.isBlocking() ? Sound.ITEM_SHIELD_BLOCK : Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        if (by != null) {
            Vector d = victim.getLocation().toVector().subtract(by.getLocation().toVector()).setY(0);
            if (d.lengthSquared() > 1e-6) victim.knockback(rpg.getConfig().getDouble("combat.knockback"), -d.getX(), -d.getZ());
        }
    }

    /** The yaw a hurt animation tilts away from (the direction the hit came from, relative to the victim). */
    private static float yawFrom(Location from, Location to) {
        double dx = from.getX() - to.getX(), dz = from.getZ() - to.getZ();
        float worldYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return worldYaw - to.getYaw();
    }

    // ------------------------------------------------------------------------------------------
    // Vanilla damage between players and creatures
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (applying) return;
        Entity damager = e.getDamager();
        Entity source = damager;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) source = shooter;
        Entity victim = e.getEntity();

        // a player hits something: melee is the basic attack (already handled), projectiles use the formula
        if (source instanceof Player p) {
            if (!(victim instanceof LivingEntity lv) || victim instanceof ArmorStand) return;
            if (!(victim instanceof Player) && info(lv) == null) return;
            e.setCancelled(true);
            if (damager instanceof Projectile) {
                // a full vanilla bow shot does about 6; weaker draws do less
                double mult = Math.max(0.3, Math.min(1.5, e.getDamage() / 6.0));
                hit(p, lv, mult, false, false);
                damager.remove();
            }
            return;
        }

        // a creature hits a player
        if (victim instanceof Player p) {
            MobInfo m = info(source);
            if (m == null) return;
            e.setCancelled(true);
            double base = balance().mobAttack(m.level(), m.role());
            if (base <= 0) return;
            // explosions and heavier vanilla attacks keep their weight relative to an ordinary hit
            double mult = e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    ? Math.max(0.5, Math.min(4, e.getDamage() / 6.0)) : 1.0;
            Damage.Hit h = new Damage.Hit(base, mult, 0, 1, 0, 0, m.element(), m.level(), false);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            Damage.Result r = Damage.compute(balance(), h, playerTarget(p, m.element()), 1, rnd.nextDouble(-1, 1),
                    balance().hurtFactor(players().get(p).level(), m.level()));
            damagePlayer(p, r.amount(), source, nameOf(source));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Deaths
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        if (e instanceof PlayerDeathEvent) return;
        MobInfo m = info(e.getEntity());
        if (m == null) return;
        e.setDroppedExp(0);
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        RpgPlayer rp = players().get(killer);
        double exp = balance().mobExp(m.level(), m.role()) * balance().expFactor(rp.level(), m.level());
        long amount = Math.max(1, Math.round(exp));
        players().giveExp(killer, amount);
        HudModule.flash(killer, Lang.mm("rpg.exp-gain", "exp", PlayerModule.fmt(amount)));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        String by = lastHitBy.remove(id);
        Long at = lastHitAt.remove(id);
        if (by != null && at != null && System.currentTimeMillis() - at < 10_000) {
            e.deathMessage(Lang.mm("rpg.death-by", "player", MiniMessage.miniMessage().escapeTags(e.getPlayer().getName()), "mob", by));
        }
    }
}
