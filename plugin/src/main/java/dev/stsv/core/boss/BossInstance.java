package dev.stsv.core.boss;

import dev.stsv.core.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One living boss: the invisible iron golem that does the walking, fighting and taking damage, the
 * bone displays riding it, the boss bar, and the fight logic (patterns, phases, death).
 */
public final class BossInstance {

    private static final int BLEND = 5;          // ticks to blend from one animation into the next
    private static final int INTERPOLATION = 2;  // client-side smoothing of each bone update

    final BossModule module;
    final BossSpec spec;
    final IronGolem golem;
    final Map<String, ItemDisplay> parts = new LinkedHashMap<>();
    final Location home;
    final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    final Map<UUID, Double> damageBy = new HashMap<>();

    int age;
    Player target;
    boolean dying;
    boolean ended;

    // animation state
    private Action action;
    private float idleT, walkT;
    private String lastAnim = "";
    private Map<String, Rig.Pose> lastPose = new HashMap<>();
    private Map<String, Rig.Pose> blendFrom = new HashMap<>();
    private int blendLeft, blendTotal = BLEND;
    private float lookYaw, lookPitch;
    private int hurtTicks;
    private Location lastLoc;
    private double groundSpeed;
    private Map<String, Matrix4f> modelSpace = new HashMap<>();

    // fight state
    private int phase;                 // phases already entered
    private BossSpec.Phase pendingPhase;
    double speedMultiplier = 1, cooldownMultiplier = 1;
    private final Map<String, Integer> readyAt = new HashMap<>();
    private int nextSkillAt;
    private BossSpec.Skill forced;          // phase "then": used as soon as the phase change ends
    private Player healer;                  // tried to heal nearby: punished as soon as possible
    private int healerUntil;
    private int behindTicks;                // how long the target has stood behind the boss
    private final List<WaveFx> waves = new ArrayList<>();
    Player held;                            // grabbed player
    double damageTaken;                     // running total, for "rescue" checks

    BossInstance(BossModule module, BossSpec spec, IronGolem golem, Location home) {
        this.module = module;
        this.spec = spec;
        this.golem = golem;
        this.home = home.clone();
        this.lastLoc = golem.getLocation();
        this.bar = BossBar.bossBar(Text.mm(spec.name()), 1f, spec.barColor(), BossBar.Overlay.NOTCHED_10);
        for (BossSpec.Bone b : spec.bones().values()) {
            ItemDisplay d = golem.getWorld().spawn(golem.getLocation(), ItemDisplay.class, e -> {
                e.setItemStack(modelItem(b.model()));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setPersistent(false);
                e.setViewRange(module.viewRange());
                e.getPersistentDataContainer().set(module.partKey, org.bukkit.persistence.PersistentDataType.STRING, spec.id());
            });
            golem.addPassenger(d);
            parts.put(b.name(), d);
        }
        nextSkillAt = 60;
        if (spec.animations().containsKey("spawn")) play(new Action("spawn", true, true));
        render();
    }

    static ItemStack modelItem(NamespacedKey model) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(model));
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------

    void tick() {
        if (ended) return;
        if (!golem.isValid()) {
            module.end(this);
            return;
        }
        age++;
        Location now = golem.getLocation();
        groundSpeed = now.getWorld() == lastLoc.getWorld()
                ? Math.hypot(now.getX() - lastLoc.getX(), now.getZ() - lastLoc.getZ()) : 0;
        lastLoc = now;
        if (hurtTicks > 0) hurtTicks--;

        if (dying) {
            if (action == null || action.tick()) {
                finishDeath();
                return;
            }
            render();
            return;
        }

        if (age % 10 == 0) updateTarget();
        if (age % 20 == 0 && leashed()) return;
        tickWaves();
        updateBehind();

        if (action != null) {
            if (action.tick()) {
                action.end();
                Action done = action;
                action = null;
                if (done.freeze) unfreeze();
            }
        } else if (pendingPhase != null) {
            enterPhase(pendingPhase);
            pendingPhase = null;
        } else if (forced != null) {
            BossSpec.Skill f = forced;
            forced = null;
            start(f);
        } else if (healer != null && age <= healerUntil && valid(healer)) {
            punishHeal();
        } else if (target != null && age >= nextSkillAt && age % spec.think() == 0) {
            chooseSkill();
        }

        render();
        if (age % 5 == 0) updateBar();
    }

    private void updateTarget() {
        if (target != null && (!valid(target) || target.getLocation().distanceSquared(golem.getLocation()) > 40 * 40)) {
            target = null;
        }
        if (target == null) {
            double best = 32 * 32;
            for (Player p : golem.getWorld().getPlayers()) {
                if (!valid(p)) continue;
                double d = p.getLocation().distanceSquared(golem.getLocation());
                if (d < best) {
                    best = d;
                    target = p;
                }
            }
        }
        if (golem.getTarget() != target) golem.setTarget(target);
    }

    static boolean valid(Player p) {
        return p.isValid() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    /** Walked too far from home (lured away or stuck): go back and heal, so the fight cannot be cheesed. */
    private boolean leashed() {
        Location at = golem.getLocation();
        if (at.getWorld() == home.getWorld() && at.distanceSquared(home) <= spec.leash() * spec.leash()) return false;
        if (action != null) {
            action.end();
            if (action.freeze) unfreeze();
            action = null;
        }
        release();
        moveGolem(home);
        golem.setHealth(maxHealth());
        damageBy.clear();
        target = null;
        golem.setTarget(null);
        for (UUID id : viewers) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(Text.msg(spec.name() + " <gray>이(가) 제자리로 돌아가 체력을 회복했습니다."));
        }
        updateBar();
        return true;
    }

    double maxHealth() {
        return golem.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    private void render() {
        String animName;
        float t;
        if (action != null) {
            animName = action.anim;
            t = action.animTime();
        } else if (groundSpeed > 0.02 && spec.animations().containsKey("walk")) {
            animName = "walk";
            float before = walkT;
            walkT += (float) Math.clamp(groundSpeed / 0.11, 0.6, 1.8);
            sounds(spec.animations().get("walk"), before, walkT);
            t = walkT;
        } else {
            animName = "idle";
            t = idleT++;
        }
        BossSpec.Animation anim = spec.animations().get(animName);
        if (!animName.equals(lastAnim)) {
            blendFrom = lastPose;
            blendLeft = action != null ? action.blend : BLEND;
            blendTotal = blendLeft;
            lastAnim = animName;
        }

        Map<String, Rig.Pose> sampled = Rig.sample(anim, t);
        Map<String, Rig.Pose> pose = new HashMap<>();
        for (String bone : spec.bones().keySet()) {
            Rig.Pose p = sampled.getOrDefault(bone, Rig.Pose.REST);
            if (blendLeft > 0) {
                float k = 1f - blendLeft / (float) (blendTotal + 1);
                p = blendFrom.getOrDefault(bone, Rig.Pose.REST).lerp(p, k * k * (3 - 2 * k));
            }
            pose.put(bone, p);
        }
        if (blendLeft > 0) blendLeft--;
        lastPose = pose;
        Map<String, Rig.Pose> shown = pose;
        if (hurtTicks > 0) {
            // flinch: a quick shake of the whole upper body
            shown = new HashMap<>(pose);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Rig.Pose root = shown.getOrDefault(rootBone(), Rig.Pose.REST);
            shown.put(rootBone(), new Rig.Pose(new Vector3f(root.rot()).add((float) r.nextDouble(-4, 4), (float) r.nextDouble(-3, 3),
                    (float) r.nextDouble(-4, 4)), root.pos()));
        }

        updateLook();
        modelSpace = Rig.modelSpace(spec, shown, lookYaw, lookPitch);
        float yaw = golem.getBodyYaw();
        float rider = (float) golem.getHeight() + module.riderOffsetAdjust();
        for (Map.Entry<String, ItemDisplay> e : parts.entrySet()) {
            ItemDisplay d = e.getValue();
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(INTERPOLATION);
            Rig.DisplayParts dp = Rig.displayParts(modelSpace.get(e.getKey()), yaw, spec.modelScale(), rider);
            d.setTransformation(new Transformation(dp.translation(), dp.rotation(), new Vector3f(dp.scale()), new Quaternionf()));
        }
    }

    private String rootBone() {
        return spec.bones().keySet().iterator().next();
    }

    private void updateLook() {
        float wantYaw = 0, wantPitch = 0;
        if (target != null && !dying) {
            Location eye = golem.getEyeLocation();
            Vector to = target.getEyeLocation().toVector().subtract(eye.toVector());
            float yawTo = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            wantYaw = Math.clamp(wrap(yawTo - golem.getBodyYaw()), -50f, 50f);
            double flat = Math.hypot(to.getX(), to.getZ());
            wantPitch = Math.clamp((float) Math.toDegrees(Math.atan2(-to.getY(), flat)), -30f, 30f);
        }
        lookYaw += (wantYaw - lookYaw) * 0.3f;
        lookPitch += (wantPitch - lookPitch) * 0.3f;
    }

    static float wrap(float deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }

    private void sounds(BossSpec.Animation anim, float from, float to) {
        if (anim == null || anim.sounds().isEmpty()) return;
        int a = (int) Math.floor(from), b = (int) Math.floor(to);
        for (int t = a + 1; t <= b; t++) {
            int at = anim.loop() ? Math.floorMod(t, anim.length()) : t;
            String s = anim.sounds().get(at);
            if (s != null) sound(s, 1.6f, 0.85f);
        }
    }

    void sound(String key, float volume, float pitch) {
        golem.getWorld().playSound(Sound.sound(Key.key(key), Sound.Source.HOSTILE, volume, pitch), golem);
    }

    /** World position of a socket (e.g. the hand) in the current pose. */
    Location socket(String name) {
        BossSpec.Socket s = spec.sockets().get(name);
        Location base = golem.getLocation();
        if (s == null || !modelSpace.containsKey(s.bone())) return base.add(0, golem.getHeight() * 0.6, 0);
        Vector3f o = Rig.worldOffset(spec, modelSpace, s.bone(), s.at(), golem.getBodyYaw(), spec.modelScale());
        return base.add(o.x, o.y, o.z);
    }

    // ------------------------------------------------------------------------------------------
    // Boss bar
    // ------------------------------------------------------------------------------------------

    void updateBar() {
        bar.progress((float) Math.clamp(golem.getHealth() / maxHealth(), 0, 1));
        if (age % 20 != 0 && age > 5) return;
        double r2 = spec.barRange() * spec.barRange();
        for (Player p : golem.getWorld().getPlayers()) {
            boolean in = p.getLocation().distanceSquared(golem.getLocation()) <= r2;
            if (in && viewers.add(p.getUniqueId())) p.showBossBar(bar);
            else if (!in && viewers.remove(p.getUniqueId())) p.hideBossBar(bar);
        }
        viewers.removeIf(id -> {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && p.getWorld() == golem.getWorld()) return false;
            if (p != null) p.hideBossBar(bar);
            return true;
        });
    }

    void hideBar() {
        for (UUID id : viewers) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        viewers.clear();
    }

    List<Player> audience() {
        List<Player> out = new ArrayList<>();
        for (UUID id : viewers) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Damage, phases, death
    // ------------------------------------------------------------------------------------------

    boolean invulnerable() {
        return dying || (action != null && action.invulnerable);
    }

    /** Skills and phase changes block the vanilla punch; the punch itself plays an animation. */
    boolean busy() {
        return action != null;
    }

    void onHurt(double amount, Player by) {
        hurtTicks = 4;
        damageTaken += amount;
        sound("entity.iron_golem.hurt", 1.2f, 0.7f);
        if (by != null) damageBy.merge(by.getUniqueId(), amount, Double::sum);
        double ratio = (golem.getHealth() - amount) / maxHealth();
        if (phase < spec.phases().size() && pendingPhase == null && ratio < spec.phases().get(phase).below()) {
            pendingPhase = spec.phases().get(phase);
            phase++;
        }
        org.bukkit.Bukkit.getScheduler().runTask(module.plugin(), this::updateBar);
    }

    void onPunch() {
        if (action == null && spec.animations().containsKey("punch")) play(new Action("punch", false, false));
        sound("entity.iron_golem.attack", 1.2f, 0.7f);
    }

    private void enterPhase(BossSpec.Phase p) {
        speedMultiplier *= p.speedMultiplier();
        cooldownMultiplier *= p.cooldownMultiplier();
        if (p.then() != null) forced = spec.skills().get(p.then());
        if (p.barColor() != null) bar.color(p.barColor());
        Runnable change = () -> {
            for (Map.Entry<String, NamespacedKey> m : p.models().entrySet()) {
                ItemDisplay d = parts.get(m.getKey());
                if (d != null) d.setItemStack(modelItem(m.getValue()));
            }
            if (p.message() != null) {
                for (Player pl : audience()) pl.sendMessage(Text.mm(p.message()));
            }
            Location chest = golem.getLocation().add(0, golem.getHeight() * 0.6, 0);
            golem.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, chest, 1);
            golem.getWorld().spawnParticle(Particle.DUST, chest, 120, 1.6, 1.4, 1.6, 0, new Particle.DustOptions(Color.fromRGB(0xff3b2f), 2.2f));
            if (p.shockRadius() > 0) {
                for (Player v : victims(golem.getLocation(), p.shockRadius(), 4)) {
                    v.setVelocity(away(golem.getLocation(), v.getLocation(), p.shockKnockback(), 0.6));
                }
                ring(golem.getLocation().add(0, 0.2, 0), p.shockRadius(), Particle.CLOUD, null, 64);
            }
        };
        if (p.animation() != null) {
            play(new Action(p.animation(), true, true) {
                @Override
                void onTick() {
                    if (t == 12) change.run();
                }
            });
        } else {
            change.run();
        }
    }

    void startDeath() {
        if (dying) return;
        dying = true;
        if (action != null) action.end();
        action = null;
        freeze();
        golem.setAI(false);
        golem.setInvulnerable(true);
        bar.progress(0);
        if (spec.animations().containsKey("death")) {
            action = new Action("death", true, true);
            lastAnim = "";
        }
    }

    private void finishDeath() {
        World w = golem.getWorld();
        Location at = golem.getLocation().add(0, golem.getHeight() * 0.4, 0);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 2, 0.8, 0.8, 0.8);
        w.spawnParticle(Particle.LARGE_SMOKE, at, 80, 1, 1.2, 1, 0.05);
        sound("entity.generic.explode", 2f, 0.7f);
        module.dropLoot(this, golem.getLocation().add(0, 0.5, 0));
        module.announceDefeat(this);
        module.end(this);
    }

    // ------------------------------------------------------------------------------------------
    // Skills
    // ------------------------------------------------------------------------------------------

    private boolean usable(BossSpec.Skill s, double dist) {
        return readyAt.getOrDefault(s.id(), 0) <= age && s.minPhase() <= phase && dist >= s.minRange() && dist <= s.maxRange();
    }

    /** Situational patterns first (target behind the boss, target out of reach above), then a random one. */
    private void chooseSkill() {
        double dist = golem.getLocation().distance(target.getLocation());
        List<BossSpec.Skill> ok = new ArrayList<>();
        for (BossSpec.Skill s : spec.skills().values()) {
            if (!usable(s, dist) || "heal".equals(s.when())) continue;
            boolean special = "behind".equals(s.when()) || "above".equals(s.when());
            if (special && condition(s.when())) {
                start(s);
                return;
            }
            if (!special) ok.add(s);
        }
        if (ok.isEmpty()) return;
        start(ok.get(ThreadLocalRandom.current().nextInt(ok.size())));
    }

    private boolean condition(String when) {
        if (target == null) return false;
        return switch (when) {
            case "behind" -> behindTicks >= 16;
            case "above" -> target.getLocation().getY() - golem.getLocation().getY() >= 3;
            default -> false;
        };
    }

    private void updateBehind() {
        if (target == null) {
            behindTicks = 0;
            return;
        }
        Vector to = target.getLocation().toVector().subtract(golem.getLocation().toVector()).setY(0);
        boolean behind = to.lengthSquared() < 36 && to.lengthSquared() > 1e-4 && forward().angle(to) > Math.toRadians(110);
        behindTicks = behind ? behindTicks + 1 : 0;
    }

    /** Someone close by is about to eat or drink. */
    void onHealAttempt(Player p) {
        if (dying || !valid(p)) return;
        healer = p;
        healerUntil = age + 15;
        if (action == null) punishHeal();
    }

    private void punishHeal() {
        Player p = healer;
        healer = null;
        double dist = golem.getLocation().distance(p.getLocation());
        for (BossSpec.Skill s : spec.skills().values()) {
            if ("heal".equals(s.when()) && usable(s, dist)) {
                target = p;
                golem.setTarget(p);
                start(s);
                return;
            }
        }
    }

    private void start(BossSpec.Skill s) {
        Action a = switch (s.type()) {
            case "slam" -> new Slam(s);
            case "charge" -> new Charge(s);
            case "throw" -> new Throw(s);
            case "sequence" -> new Sequence(s);
            default -> null;
        };
        if (a == null) {
            module.plugin().getLogger().warning("보스 " + spec.id() + ": 알 수 없는 패턴 종류 '" + s.type() + "'");
            readyAt.put(s.id(), Integer.MAX_VALUE);
            return;
        }
        int len = s.animation() != null && spec.animations().containsKey(s.animation()) ? spec.animations().get(s.animation()).length() : 30;
        readyAt.put(s.id(), age + len + (int) (s.cooldown() * cooldownMultiplier));
        nextSkillAt = age + len + (int) (spec.globalCooldown() * cooldownMultiplier);
        play(a);
    }

    private void play(Action a) {
        if (action != null) {
            action.end();
            if (action.freeze && !a.freeze) unfreeze();
        }
        action = a;
        if (a.freeze) freeze();
        a.start();
    }

    private void freeze() {
        golem.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0);
        golem.getPathfinder().stopPathfinding();
    }

    private void unfreeze() {
        if (!dying) golem.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(spec.speed() * speedMultiplier);
    }

    private Vector forward() {
        double r = Math.toRadians(golem.getBodyYaw());
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }

    private void face(Location to) {
        Vector d = to.toVector().subtract(golem.getLocation().toVector());
        if (d.getX() * d.getX() + d.getZ() * d.getZ() < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        golem.setRotation(yaw, 0);
        golem.setBodyYaw(yaw);
    }

    List<Player> victims(Location center, double radius, double height) {
        List<Player> out = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (!valid(p)) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getY() - center.getY()) > height) continue;
            if (Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ()) <= radius) out.add(p);
        }
        return out;
    }

    void hit(Player p, double damage, Vector knockback) {
        module.skillDamage(() -> p.damage(damage, golem));
        p.setVelocity(knockback);
    }

    static Vector away(Location from, Location to, double strength, double up) {
        Vector d = to.toVector().subtract(from.toVector()).setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(1, 0, 0);
        return d.normalize().multiply(strength).setY(up);
    }

    void ring(Location c, double radius, Particle particle, Particle.DustOptions dust, int points) {
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Location l = c.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            if (dust != null) c.getWorld().spawnParticle(particle, l, 1, 0, 0, 0, 0, dust);
            else c.getWorld().spawnParticle(particle, l, 1, 0, 0, 0, 0);
        }
    }

    private static final Particle.DustOptions WARN = new Particle.DustOptions(Color.fromRGB(0xff3b2f), 1.6f);
    private static final Particle.DustOptions WARN_FILL = new Particle.DustOptions(Color.fromRGB(0xffa53c), 1.2f);

    /** Danger zone: red edge, plus an orange ring growing towards it until the hit lands. */
    private void telegraph(Location c, double radius, float progress) {
        int n = (int) Math.max(24, radius * 10);
        ring(c, radius, Particle.DUST, WARN, n);
        ring(c, Math.max(0.3, radius * progress), Particle.DUST, WARN_FILL, (int) Math.max(12, n * progress));
    }

    private BlockData groundBlock(Location c) {
        Material m = c.clone().subtract(0, 0.5, 0).getBlock().getType();
        return (m.isAir() || !m.isSolid() ? Material.STONE : m).createBlockData();
    }

    /** A playing animation, optionally with fight logic. tick() returns true when finished. */
    class Action {
        String anim;
        final boolean freeze;
        final boolean invulnerable;
        int t;
        int blend = BLEND;

        float animTime() {
            return t;
        }

        Action(String anim, boolean freeze, boolean invulnerable) {
            this.anim = anim;
            this.freeze = freeze;
            this.invulnerable = invulnerable;
        }

        void start() {}

        void onTick() {}

        void end() {}

        int length() {
            BossSpec.Animation a = spec.animations().get(anim);
            return a == null ? 20 : a.length();
        }

        boolean finished() {
            return t >= length();
        }

        boolean tick() {
            BossSpec.Animation a = spec.animations().get(anim);
            if (a != null) {
                String s = a.sounds().get(t);
                if (s != null) sound(s, 1.8f, 0.85f);
            }
            onTick();
            t++;
            return finished();
        }
    }

    private final class Slam extends Action {
        final BossSpec.Skill s;
        final int hitTick;
        Location center;

        Slam(BossSpec.Skill s) {
            super(s.animation(), true, false);
            this.s = s;
            this.hitTick = s.i("hit-tick", 20);
        }

        @Override
        void onTick() {
            double radius = s.d("radius", 4);
            if (t < hitTick - 5 && target != null) face(target.getLocation());
            if (t <= hitTick) {
                center = golem.getLocation().add(forward().multiply(s.d("forward", 2) * spec.scale() / 1.4)).add(0, 0.15, 0);
            }
            if (t < hitTick && t % 2 == 0) telegraph(center, radius, t / (float) hitTick);
            if (t == hitTick) {
                World w = golem.getWorld();
                w.spawnParticle(Particle.EXPLOSION, center, 4, radius / 3, 0.2, radius / 3);
                w.spawnParticle(Particle.BLOCK, center, 160, radius / 2, 0.3, radius / 2, 0.2, groundBlock(center));
                sound("block.anvil.land", 1.6f, 0.5f);
                for (Player p : victims(center, radius, 3)) {
                    hit(p, s.d("damage", 10), away(center, p.getLocation(), s.d("knockback", 1.2), 0.55));
                }
            }
            if (t > hitTick && t <= hitTick + 6) {
                ring(center, radius * (t - hitTick) / 6.0, Particle.CLOUD, null, 40);
            }
        }
    }

    private final class Charge extends Action {
        final BossSpec.Skill s;
        final int start, stop;
        Vector dir;
        Location lastPos;
        final Set<UUID> struck = new HashSet<>();

        Charge(BossSpec.Skill s) {
            super(s.animation(), true, false);
            this.s = s;
            this.start = s.i("start-tick", 20);
            this.stop = s.i("end-tick", 36);
        }

        @Override
        void onTick() {
            double speed = s.d("speed", 0.8) * speedMultiplier;
            if (t < start) {
                if (target != null) face(target.getLocation());
                if (t % 2 == 0) {
                    Vector f = forward();
                    Vector side = new Vector(-f.getZ(), 0, f.getX()).multiply(s.d("width", 1.5));
                    Location o = golem.getLocation().add(0, 0.15, 0);
                    double len = speed * (stop - start);
                    for (double d = 1; d < len; d += 0.6) {
                        Location c = o.clone().add(f.clone().multiply(d));
                        golem.getWorld().spawnParticle(Particle.DUST, c.clone().add(side), 1, 0, 0, 0, 0, WARN);
                        golem.getWorld().spawnParticle(Particle.DUST, c.clone().subtract(side), 1, 0, 0, 0, 0, WARN);
                        if (((int) (d / 0.6) + t / 2) % 4 == 0) golem.getWorld().spawnParticle(Particle.DUST, c, 1, 0, 0, 0, 0, WARN_FILL);
                    }
                }
                return;
            }
            if (t == start) {
                dir = forward();
                lastPos = golem.getLocation();
            }
            if (t >= start && t < stop) {
                Location here = golem.getLocation();
                if (t > start + 2 && here.distanceSquared(lastPos) < 0.15 * 0.15) {
                    // ran into a wall: crash and stop
                    golem.getWorld().spawnParticle(Particle.EXPLOSION, here.clone().add(dir).add(0, 1.5, 0), 3, 0.5, 0.5, 0.5);
                    sound("entity.generic.explode", 1.2f, 0.9f);
                    t = stop;
                    golem.setVelocity(new Vector());
                    return;
                }
                lastPos = here;
                golem.setRotation((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())), 0);
                golem.setBodyYaw(golem.getLocation().getYaw());
                golem.setVelocity(dir.clone().multiply(speed).setY(Math.min(0, golem.getVelocity().getY())));
                golem.getWorld().spawnParticle(Particle.BLOCK, here, 12, 0.6, 0.1, 0.6, 0.1, groundBlock(here));
                double reach = golem.getWidth() / 2 + s.d("width", 1.5) * 0.5;
                for (Player p : victims(here.clone().add(dir.clone().multiply(0.8)), reach, golem.getHeight())) {
                    if (struck.add(p.getUniqueId())) {
                        hit(p, s.d("damage", 8), dir.clone().multiply(s.d("knockback", 1.6)).setY(0.45));
                        sound("entity.player.attack.knockback", 1.4f, 0.6f);
                    }
                }
            }
            if (t == stop) golem.setVelocity(new Vector());
        }

        @Override
        void end() {
            if (t < stop) golem.setVelocity(new Vector());
        }
    }

    private final class Throw extends Action {
        final BossSpec.Skill s;
        final int pickup, release, flight;
        ItemDisplay rock;
        Location from, to;
        int flown = -1;
        double apex;
        boolean landed;

        Throw(BossSpec.Skill s) {
            super(s.animation(), true, false);
            this.s = s;
            this.pickup = s.i("pickup-tick", 8);
            this.release = s.i("release-tick", 24);
            this.flight = Math.max(4, s.i("flight-ticks", 20));
        }

        @Override
        void onTick() {
            if (t < release - 2 && target != null) face(target.getLocation());
            if (t == pickup) {
                Location at = socket("hand_r");
                rock = golem.getWorld().spawn(at, ItemDisplay.class, e -> {
                    e.setItemStack(modelItem(NamespacedKey.fromString(s.s("model") == null ? "minecraft:stone" : s.s("model"))));
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    e.setPersistent(false);
                    e.setTeleportDuration(1);
                    e.setViewRange(module.viewRange());
                    e.getPersistentDataContainer().set(module.partKey, org.bukkit.persistence.PersistentDataType.STRING, spec.id());
                });
                golem.getWorld().spawnParticle(Particle.BLOCK, at, 30, 0.4, 0.4, 0.4, 0.1, Material.STONE.createBlockData());
            }
            if (rock != null && t > pickup && t < release) rock.teleport(socket("hand_r"));
            if (t == release && rock != null) {
                from = rock.getLocation();
                Location aim = target != null ? target.getLocation() : golem.getLocation().add(forward().multiply(8));
                if (target != null) aim.add(target.getVelocity().setY(0).multiply(flight * 0.6));
                to = aim;
                apex = 1.5 + from.distance(to) * 0.22;
                flown = 0;
            }
            if (flown >= 0 && !landed) {
                flown++;
                double u = Math.min(1, flown / (double) flight);
                Location p = from.clone().add(to.toVector().subtract(from.toVector()).multiply(u)).add(0, apex * 4 * u * (1 - u), 0);
                rock.teleport(p);
                rock.setInterpolationDelay(0);
                rock.setInterpolationDuration(1);
                rock.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateXYZ(flown * 0.5f, flown * 0.3f, 0),
                        new Vector3f(1.2f), new Quaternionf()));
                telegraph(to.clone().add(0, 0.15, 0), s.d("radius", 3), (float) u);
                if (flown >= flight) impact();
            }
        }

        private void impact() {
            landed = true;
            World w = to.getWorld();
            double radius = s.d("radius", 3);
            w.spawnParticle(Particle.EXPLOSION, to, 3, radius / 3, 0.3, radius / 3);
            w.spawnParticle(Particle.BLOCK, to, 120, radius / 2, 0.4, radius / 2, 0.2, Material.STONE.createBlockData());
            w.playSound(Sound.sound(Key.key("entity.generic.explode"), Sound.Source.HOSTILE, 1.5f, 1.1f), to.getX(), to.getY(), to.getZ());
            for (Player p : victims(to, radius, 3)) hit(p, s.d("damage", 8), away(to, p.getLocation(), s.d("knockback", 1), 0.5));
            if (rock != null) rock.remove();
            rock = null;
        }

        @Override
        boolean finished() {
            return t >= length() && (flown < 0 ? t > release : landed);
        }

        @Override
        void end() {
            if (rock != null) rock.remove();
            rock = null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sequence patterns: steps of animations with timed hits, delays, movement, grabs and hazards
    // ------------------------------------------------------------------------------------------

    private static final Particle.DustOptions GLINT = new Particle.DustOptions(Color.fromRGB(0xfff2c4), 1.4f);
    private static final Particle.DustOptions CRIMSON = new Particle.DustOptions(Color.fromRGB(0xd8102a), 1.8f);
    private static final Particle.DustOptions WAVE = new Particle.DustOptions(Color.fromRGB(0xc86bff), 1.5f);
    private static final Particle.DustOptions SAFE = new Particle.DustOptions(Color.fromRGB(0x5dff7a), 1.5f);
    private static final Particle.DustOptions LINE = new Particle.DustOptions(Color.fromRGB(0xf2f2f2), 1.0f);

    /** Turns towards a point by at most maxDeg this tick (bosses cannot spin on the spot instantly). */
    private void turnToward(Location to, double maxDeg) {
        Vector d = to.toVector().subtract(golem.getLocation().toVector());
        if (d.getX() * d.getX() + d.getZ() * d.getZ() < 1e-4) return;
        float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float now = golem.getBodyYaw();
        float yaw = now + (float) Math.clamp(wrap(want - now), -maxDeg, maxDeg);
        golem.setRotation(yaw, 0);
        golem.setBodyYaw(yaw);
    }

    private boolean inShape(BossSpec.Hit h, Player p, Location origin, Vector fwd) {
        if (h.shape().equals("held")) return p == held;
        Location l = p.getLocation();
        double dy = l.getY() - origin.getY();
        if (dy < -1.2 || dy > 3.2) return false;
        Vector to = new Vector(l.getX() - origin.getX(), 0, l.getZ() - origin.getZ());
        double pad = 0.3; // player half width
        return switch (h.shape()) {
            case "cone" -> to.length() - pad <= h.range()
                    && (to.length() < 1.2 || Math.toDegrees(fwd.angle(to)) <= h.arc() / 2);
            case "circle" -> to.clone().subtract(fwd.clone().multiply(h.forward())).length() - pad <= h.radius();
            case "line" -> {
                double along = to.dot(fwd);
                double side = to.clone().subtract(fwd.clone().multiply(along)).length();
                yield along >= -0.5 && along <= h.range() + pad && side <= h.width() / 2 + pad;
            }
            default -> false;
        };
    }

    private void strike(Player p, BossSpec.Hit h, Location origin) {
        boolean blocking = p.isBlocking();
        if (blocking && h.unblockable()) breakGuard(p);
        hit(p, h.damage(), away(origin, p.getLocation(), h.knockback(), h.up()));
        if (blocking && h.guardBreak() && !h.unblockable()) breakGuard(p);
    }

    /** Knocks the shield out of the player's guard for a few seconds. */
    private void breakGuard(Player p) {
        p.clearActiveItem();
        p.setCooldown(Material.SHIELD, 60);
        p.getWorld().playSound(Sound.sound(Key.key("item.shield.break"), Sound.Source.PLAYER, 1.2f, 0.8f), p);
        p.getWorld().spawnParticle(Particle.CRIT, p.getLocation().add(0, 1.2, 0), 20, 0.3, 0.3, 0.3, 0.3);
    }

    void release() {
        held = null;
    }

    /**
     * Teleports the boss together with its bone displays. Newer Paper keeps passengers on teleport by
     * itself and deprecates the flag; it is still passed so older 26.x builds do the same.
     */
    @SuppressWarnings("removal")
    private void moveGolem(Location to) {
        golem.teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /** Feet on the floor (the player-reported on-ground flag can be spoofed, so look at the block). */
    private static boolean grounded(Player p) {
        Location l = p.getLocation();
        return !l.clone().subtract(0, 0.08, 0).getBlock().isPassable();
    }

    /** Standing height under a point (so a leap lands on the floor, not in the air where the target jumped). */
    private static Location ground(Location at) {
        Location l = at.clone();
        for (int i = 0; i < 8; i++) {
            org.bukkit.block.Block below = l.clone().subtract(0, 0.05, 0).getBlock();
            if (!below.isPassable()) {
                l.setY(below.getY() + below.getBoundingBox().getHeight());
                return l;
            }
            l.subtract(0, 1, 0);
        }
        return at.clone();
    }

    /** Shockwave ring sliding along the floor: only players touching the ground are hit (jump over it). */
    private final class WaveFx {
        final Location center;
        final BossSpec.Wave w;
        double r = 0.5;
        final Set<UUID> struck = new HashSet<>();

        WaveFx(Location center, BossSpec.Wave w) {
            this.center = center;
            this.w = w;
        }

        boolean tick() {
            r += w.speed();
            int n = (int) Math.max(16, r * 2 * Math.PI / 0.45);
            ring(center, r, Particle.DUST, WAVE, n);
            if (age % 2 == 0) center.getWorld().spawnParticle(Particle.BLOCK, center, (int) (r * 3), r * 0.7, 0.05, r * 0.7, 0, groundBlock(center));
            for (Player p : victims(center, r + w.width() / 2 + 0.3, 1.4)) {
                double d = Math.hypot(p.getLocation().getX() - center.getX(), p.getLocation().getZ() - center.getZ());
                if (Math.abs(d - r) > w.width() / 2 + 0.3 || !grounded(p) || !struck.add(p.getUniqueId())) continue;
                hit(p, w.damage(), away(center, p.getLocation(), 0.6, 0.55));
            }
            return r >= w.max();
        }
    }

    private void tickWaves() {
        waves.removeIf(WaveFx::tick);
    }

    final class Sequence extends Action {
        final BossSpec.Skill skill;
        int index = -1;
        BossSpec.Step step;
        int st;                 // step animation time (stands still while holding)
        int holdLeft = -1;      // ticks left in the current hold, -1 = not holding
        boolean holdDone;
        boolean done;
        String outcome;         // grab result: caught / missed
        final Map<BossSpec.Hit, Set<UUID>> struck = new HashMap<>();
        Location leapFrom, leapTo;
        double grabMark;
        boolean[] safe;
        double sectorBase;
        Location sectorCenter;

        float lockedYaw;

        Sequence(BossSpec.Skill skill) {
            super(skill.steps().getFirst().animation(), true, false);
            this.skill = skill;
            this.blend = 3;
            this.lockedYaw = golem.getBodyYaw();
        }

        @Override
        void start() {
            if (!next(true)) done = true;
        }

        private boolean next(boolean first) {
            for (int i = index + 1; i < skill.steps().size(); i++) {
                BossSpec.Step s = skill.steps().get(i);
                if (s.minPhase() > phase) continue;
                if (s.condition() != null && !s.condition().equals(outcome)) continue;
                if (!first && s.chance() < 1 && ThreadLocalRandom.current().nextDouble() > s.chance()) return false;
                index = i;
                step = s;
                anim = s.animation();
                st = 0;
                holdLeft = -1;
                holdDone = false;
                struck.clear();
                return true;
            }
            return false;
        }

        @Override
        float animTime() {
            return st;
        }

        @Override
        boolean finished() {
            return done;
        }

        @Override
        boolean tick() {
            if (done) return true;
            t++;
            BossSpec.Step s = step;
            boolean holding = holdLeft >= 0;
            int glint = s.hold() == null ? 0 : s.hold().glint();

            // facing: follow the target until track-until (and while holding, until the glint)
            boolean track = st < s.trackUntil() && !(holding && holdLeft <= glint);
            if (track && target != null) {
                turnToward(target.getLocation(), s.turn());
                lockedYaw = golem.getBodyYaw();
            } else if (s.leap() == null || st < s.leap().from() || st > s.leap().to()) {
                // committed: the body keeps its direction (vanilla head-follow would slowly turn it)
                golem.setRotation(lockedYaw, 0);
                golem.setBodyYaw(lockedYaw);
            }

            if (holding) {
                if (holdLeft == glint || (holdLeft < glint && holdLeft == glintStart)) glint();
                holdLeft--;
                if (holdLeft < 0) holdDone = true;
                tickHeld(s);
                return false;
            }

            events(s);
            if (done) return true;
            BossSpec.Animation a = spec.animations().get(anim);
            if (a != null) {
                String snd = a.sounds().get(st);
                if (snd != null) sound(snd, 1.8f, 0.9f);
            }
            st++;
            if (s.hold() != null && !holdDone && st == s.hold().tick()) {
                holdLeft = ThreadLocalRandom.current().nextInt(s.hold().min(), s.hold().max() + 1);
                glintStart = Math.min(holdLeft, glint);
                if (holdLeft == 0) holdDone = true;
            }
            if (a == null || st >= a.length()) {
                if (!next(false)) done = true;
            }
            return done;
        }

        int glintStart;

        private void glint() {
            Location tip = socket("blade");
            golem.getWorld().spawnParticle(Particle.DUST, tip, 30, 0.5, 0.5, 0.5, 0, GLINT);
            golem.getWorld().spawnParticle(Particle.END_ROD, tip, 12, 0.3, 0.3, 0.3, 0.05);
            golem.getWorld().playSound(Sound.sound(Key.key("block.bell.use"), Sound.Source.HOSTILE, 1.6f, 1.9f), golem);
        }

        private void events(BossSpec.Step s) {
            Location origin = golem.getLocation();
            Vector fwd = forward();

            if (s.move() != null && st >= s.move().from() && st < s.move().to()) {
                golem.setVelocity(fwd.clone().multiply(s.move().speed() * speedMultiplier).setY(Math.min(0, golem.getVelocity().getY())));
            }
            if (s.flash() != null && st >= s.flash()[0] && st <= s.flash()[1] && st % 2 == 0) {
                golem.getWorld().spawnParticle(Particle.DUST, socket("hand_l"), 8, 0.25, 0.25, 0.25, 0, CRIMSON);
            }
            if (s.leap() != null) leap(s.leap());

            for (BossSpec.Hit h : s.hits()) {
                if (h.minPhase() > phase || st < h.tick() || st > h.until()) continue;
                Set<UUID> done = struck.computeIfAbsent(h, k -> new HashSet<>());
                for (Player p : golem.getWorld().getPlayers()) {
                    if (!valid(p) || done.contains(p.getUniqueId()) || !inShape(h, p, origin, fwd)) continue;
                    done.add(p.getUniqueId());
                    strike(p, h, origin);
                }
                if (h.shape().equals("held") && st == h.tick()) release();
            }
            for (BossSpec.Wave w : s.waves()) {
                if (w.tick() == st && w.minPhase() <= phase) {
                    waves.add(new WaveFx(origin.clone().add(fwd.clone().multiply(w.forward())).add(0, 0.1, 0), w));
                }
            }
            if (s.grab() != null && st == s.grab().tick()) grab(s.grab(), origin, fwd);
            tickHeld(s);
            if (s.sectors() != null) sectors(s.sectors());
        }

        private void grab(BossSpec.Grab g, Location origin, Vector fwd) {
            Player best = null;
            double bestD = Double.MAX_VALUE;
            for (Player p : golem.getWorld().getPlayers()) {
                if (!valid(p)) continue;
                Vector to = p.getLocation().toVector().subtract(origin.toVector()).setY(0);
                double dy = p.getLocation().getY() - origin.getY();
                if (dy < -1 || dy > 2.5 || to.length() - 0.3 > g.range()) continue;
                if (to.length() > 1 && Math.toDegrees(fwd.angle(to)) > g.arc() / 2) continue;
                if (to.length() < bestD) {
                    bestD = to.length();
                    best = p;
                }
            }
            if (best != null) {
                held = best;
                outcome = "caught";
                grabMark = damageTaken;
                best.getWorld().playSound(Sound.sound(Key.key("entity.player.hurt"), Sound.Source.PLAYER, 1f, 0.7f), best);
            } else {
                outcome = "missed";
            }
            // no gap between grabbing and lifting: go straight into the follow-up step
            if (!next(false)) done = true;
        }

        /** Keeps the grabbed player in the hand; enough damage from the others frees them. */
        private void tickHeld(BossSpec.Step s) {
            if (s.held() == null || held == null) return;
            if (!valid(held)) {
                release();
                return;
            }
            if (damageTaken - grabMark >= maxHealth() * s.held().rescue()) {
                Player freed = held;
                release();
                freed.setVelocity(away(golem.getLocation(), freed.getLocation(), 0.6, 0.4));
                for (Player v : audience()) v.sendMessage(Text.mm("<green><b>구출!</b> <white>" + freed.getName() + "<gray> 이(가) 풀려났습니다."));
                sound("entity.iron_golem.damage", 1.5f, 0.6f);
                outcome = "missed";
                if (!next(false)) done = true;   // straight into the stagger (grab_miss)
                return;
            }
            if (st <= s.held().until()) {
                Location hand = socket(s.held().socket());
                Location at = hand.clone().subtract(0, 1.3, 0);
                at.setYaw(held.getLocation().getYaw());
                at.setPitch(held.getLocation().getPitch());
                held.teleport(at);
                held.setVelocity(new Vector());
                held.setFallDistance(0);
            }
        }

        private void leap(BossSpec.Leap l) {
            if (st < l.from() || st > l.to()) {
                return;
            }
            if (st == l.from()) leapFrom = golem.getLocation();
            if (leapTo == null || (st <= l.trackUntil() && target != null)) {
                leapTo = ground(target != null ? target.getLocation() : golem.getLocation().add(forward().multiply(6)));
                // land a little short, so the strike in front of the boss lands on the target
                Vector back = leapTo.toVector().subtract(leapFrom.toVector()).setY(0);
                if (back.lengthSquared() > 4) leapTo.subtract(back.normalize().multiply(1.6));
            }
            double u = (st - l.from()) / (double) Math.max(1, l.to() - l.from());
            Location p = leapFrom.clone().add(leapTo.toVector().subtract(leapFrom.toVector()).multiply(u));
            p.add(0, l.height() * 4 * u * (1 - u), 0);
            Vector dir = leapTo.toVector().subtract(leapFrom.toVector()).setY(0);
            float yaw = dir.lengthSquared() > 1e-3 ? (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())) : golem.getBodyYaw();
            p.setYaw(yaw);
            p.setPitch(0);
            moveGolem(p);
            golem.setBodyYaw(yaw);
            lockedYaw = yaw;
            golem.setVelocity(new Vector());
            golem.setFallDistance(0);
            if (st >= l.markerFrom() && st < l.to() && st % 2 == 0) {
                Location mark = leapTo.clone().add(dir.lengthSquared() > 1e-3 ? dir.clone().normalize().multiply(1.8) : new Vector());
                telegraph(mark.add(0, 0.15, 0), l.markerRadius(), (st - l.markerFrom()) / (float) Math.max(1, l.to() - l.markerFrom()));
            }
        }

        /** The arena splits into slices; only a few are safe. Shown, then hidden: remember them. */
        private void sectors(BossSpec.Sectors x) {
            int n = x.count();
            if (safe == null) {
                sectorCenter = golem.getLocation().add(0, 0.2, 0);
                sectorBase = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                safe = new boolean[n];
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < n; i++) idx.add(i);
                java.util.Collections.shuffle(idx);
                for (int i = 0; i < Math.min(x.safe(), n - 1); i++) safe[idx.get(i)] = true;
            }
            if (sectorCenter == null) return;
            World w = golem.getWorld();
            double slice = 2 * Math.PI / n;
            if (st >= x.show() && st < x.tick() && st % 3 == 0) {
                boolean reveal = st < x.hide();
                boolean urgent = st >= x.tick() - 12;
                for (int i = 0; i < n; i++) {
                    double a0 = sectorBase + i * slice;
                    for (double r = 2; r <= x.radius(); r += 1.2) {      // boundary lines
                        w.spawnParticle(Particle.DUST, sectorCenter.clone().add(Math.cos(a0) * r, 0, Math.sin(a0) * r), 1, 0, 0, 0, 0,
                                urgent ? WARN : LINE);
                    }
                    if (!reveal) continue;
                    for (double r = 3; r <= x.radius(); r += 3.5) {      // slice fill
                        int pts = (int) Math.max(3, r * slice / 1.2);
                        for (int k = 1; k < pts; k++) {
                            double a = a0 + slice * k / pts;
                            w.spawnParticle(Particle.DUST, sectorCenter.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0,
                                    safe[i] ? SAFE : WARN);
                        }
                    }
                }
                ring(sectorCenter, x.radius(), Particle.DUST, WARN, (int) (x.radius() * 6));
            }
            if (st == x.hide()) sound("block.beacon.deactivate", 2f, 0.6f);
            if (st == x.tick()) {
                for (int i = 0; i < n; i++) {
                    if (safe[i]) continue;
                    double mid = sectorBase + (i + 0.5) * slice;
                    for (double r = 3; r <= x.radius(); r += 4) {
                        w.spawnParticle(Particle.EXPLOSION, sectorCenter.clone().add(Math.cos(mid) * r, 0.5, Math.sin(mid) * r), 2, 0.8, 0.3, 0.8);
                    }
                }
                for (Player p : victims(sectorCenter, x.radius(), 6)) {
                    Location l = p.getLocation();
                    double a = Math.atan2(l.getZ() - sectorCenter.getZ(), l.getX() - sectorCenter.getX()) - sectorBase;
                    int i = (int) Math.floor(((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI) / slice);
                    if (safe[Math.clamp(i, 0, n - 1)]) continue;
                    if (p.isBlocking()) breakGuard(p);
                    hit(p, x.damage(), away(sectorCenter, l, 1.4, 0.8));
                }
            }
        }

        @Override
        void end() {
            release();
        }
    }

    // ------------------------------------------------------------------------------------------

    void remove() {
        ended = true;
        if (action != null) action.end();
        action = null;
        release();
        waves.clear();
        hideBar();
        for (ItemDisplay d : parts.values()) d.remove();
        parts.clear();
        golem.remove();
    }

    String state() {
        return action == null ? (groundSpeed > 0.02 ? "walk" : "idle") : action.anim;
    }

    double health() {
        return golem.getHealth();
    }

    Display anyPart() {
        return parts.values().stream().findFirst().orElse(null);
    }
}
