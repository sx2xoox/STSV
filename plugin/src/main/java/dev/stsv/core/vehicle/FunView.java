package dev.stsv.core.vehicle;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 특별 탈것, shown and felt ({@link FunSpec}, {@link FunPhysics}): {@link VehicleModule} calls in before and after each
 * vehicle's physics tick, and when drawing, seating and unseating. Nothing here runs for a vehicle without a
 * {@code fun:} section.
 *
 * <ul>
 *   <li><b>Before</b> the physics: turning on the spot or towards the rider's look, the bull's lurches, a bounce or an
 *       ollie launched, the hover height asked for, a quack.</li>
 *   <li><b>After</b>: landings (a boing and a puff of dust), the rolling sound, trails, riders tossed off the banana boat,
 *       the rodeo rider thrown, the balloons blown up again, and the drawn pose (spin, tilt, waddle, squash) glided to.</li>
 *   <li><b>Falls</b>: someone getting off something that floats or bounces lands without fall damage, as long as they
 *       come down no lower than the ground that was under it ({@link #onFall}); a fall past that hurts as usual.</li>
 * </ul>
 */
final class FunView {

    /** On a player: their best time on the rodeo bull (seconds). */
    static final NamespacedKey RODEO_BEST = NamespacedKey.fromString("stsv:rodeo_best");
    /** Ticks between two quacks (holding space does not repeat). */
    static final int TOOT_TICKS = 8;
    /** How long a fall guard lasts at most (ticks). */
    static final int GUARD_TICKS = 200;

    private final VehicleModule m;
    private final Random rnd = new Random();
    /** players who got off a floating or bouncing vehicle: the ground they may fall to unhurt */
    private final Map<UUID, Guard> guards = new HashMap<>();

    private record Guard(UUID world, double groundY, long until) {}

    FunView(VehicleModule m) {
        this.m = m;
    }

    private static FunSpec fun(LiveVehicle v) {
        return v.spec.fun();
    }

    /** The terrain this vehicle feels: water a floor for what floats over it or on it. */
    static VehiclePhysics.Terrain terrain(LiveVehicle v, VehiclePhysics.Terrain t) {
        return terrain(v.spec, t);
    }

    /** The terrain a vehicle of this kind feels (placing one too). */
    static VehiclePhysics.Terrain terrain(VehicleSpec s, VehiclePhysics.Terrain t) {
        FunSpec f = s.fun();
        if (f == null) return t;
        if (f.hover() != null) return FunPhysics.waterFloor(t, 0);
        if (f.amphibious() != null) return FunPhysics.waterFloor(t, f.amphibious().draft());
        return t;
    }

    // ------------------------------------------------------------------------------------------
    // Before the physics
    // ------------------------------------------------------------------------------------------

    /** The keys the physics gets (without A / D where the behaviour turns it), after turning and launching. */
    DriveInput before(LiveVehicle v, Player driver, DriveInput in, VehiclePhysics.Terrain world, long ticks) {
        FunSpec f = fun(v);
        if (f == null) return in;
        FunPhysics.State fs = v.fun;
        VehiclePhysics.Terrain t = terrain(v, world);
        boolean ridden = driver != null;
        DriveInput out = in;
        if (f.pivot() || f.lookSteer() || f.buck() != null)
            out = new DriveInput(in.forward(), in.backward(), false, false, in.jump(), in.sneak(), in.sprint());
        float turn = 0;
        if (f.buck() != null) {
            if (ridden) turn = FunPhysics.buck(fs, f.buck(), in.steer(), rnd);
            else FunPhysics.calm(fs);
        } else if (f.pivot() && ridden) {
            turn = FunPhysics.pivotTurn(in.steer(), v.spec);
        } else if (f.lookSteer() && ridden) {
            turn = FunPhysics.lookTurn(v.st.yaw, driver.getYaw(), v.spec);
        }
        if (turn != 0) {
            float yaw = VehiclePhysics.wrap(v.st.yaw + turn);
            if (!VehiclePhysics.collides(v.st.x, v.st.y, v.st.z, yaw, v.spec, t)) v.st.yaw = yaw;
        }
        if (f.hop() != null) FunPhysics.hop(v.st, fs, f.hop(), ridden, ridden && in.jump());
        else if (f.ollie() != null) FunPhysics.ollie(v.st, fs, f.ollie(), ridden && in.jump());
        else if (f.toot() != null && FunPhysics.press(fs, ridden && in.jump()) && ticks >= v.tootAt) {
            v.tootAt = ticks + TOOT_TICKS;
            m.play(v, f.toot().sound(), f.toot().volume(), f.toot().pitch() * (0.95f + 0.1f * rnd.nextFloat()));
        }
        if (f.hover() != null) {
            boolean down = ridden && (in.sprint() || driver.getLocation().getPitch() > 55);
            FunPhysics.aim(fs, f.hover(), ridden, ridden && in.jump(), down);
        }
        return out;
    }

    /**
     * The physics tick of a vehicle that moves its own way (hovering, amphibious, the sled's surfaces); false: the usual
     * {@link VehiclePhysics#step} is for it.
     */
    boolean step(LiveVehicle v, DriveInput in, VehiclePhysics.Terrain world, double cap, double bonus, double top, double accel) {
        FunSpec f = fun(v);
        if (f == null) return false;
        if (f.hover() != null) {
            FunPhysics.hover(v.st, v.fun, in, v.spec, terrain(v, world), cap);
            return true;
        }
        if (f.amphibious() != null) {
            boolean afloat = FunPhysics.overWater(v.st.x, v.st.y + 0.05, v.st.z, world);
            double scale = afloat ? 1 : f.amphibious().landSpeed() / v.spec.speed();
            VehiclePhysics.step(v.st, in, v.spec, terrain(v, world), cap, 1f, 1, cap, top * scale, accel);
            v.fun.floating = v.st.onGround && FunPhysics.overWater(v.st.x, v.st.y + 0.05, v.st.z, world);
            return true;
        }
        if (f.surface() != null) {
            double sf = FunPhysics.surfaceFactor(v.st.x, v.st.y, v.st.z, v.st.yaw, v.spec, f.surface(), world, v.feel.grade);
            VehiclePhysics.step(v.st, in, v.spec, world, cap, 1f, bonus, cap, top * sf, accel);
            return true;
        }
        return false;
    }

    /** It must be ticked though nobody drives it (still hovering, spinning, settling its pose). */
    boolean active(LiveVehicle v) {
        FunSpec f = fun(v);
        if (f == null) return false;
        FunPhysics.State fs = v.fun;
        return (f.hover() != null && !FunPhysics.hoverSettled(fs, v.st)) || fs.spinLeft > 0 || fs.buckPitch != 0
                || (fs.popped && f.hover() != null) || !v.funPose.rest() && !fs.floating;
    }

    // ------------------------------------------------------------------------------------------
    // After the physics
    // ------------------------------------------------------------------------------------------

    /**
     * After the vehicle's tick (moved or not).
     *
     * @param wasOnGround it was on the ground before the physics ran
     * @param turned      degrees it turned this tick (+ = right)
     * @param moved       blocks it went this tick
     */
    void after(LiveVehicle v, Player driver, boolean wasOnGround, float turned, double moved, long ticks) {
        FunSpec f = fun(v);
        if (f == null) return;
        FunPhysics.State fs = v.fun;
        FunPhysics.landing(v.st, fs, wasOnGround);
        if (f.ollie() != null) FunPhysics.spin(fs);
        boolean near = v.feel.watched;

        if (f.hop() != null && fs.landed && driver != null) {
            // boing: deeper from higher up; a puff of the ground's dust
            float pitch = (float) Math.clamp(1.35 - 0.1 * fs.fell, 0.7, 1.4);
            m.play(v, f.hop().sound(), 0.7f, pitch);
            puff(v, 4 + (int) Math.min(10, fs.fell * 2.5));
        }
        if (f.ollie() != null && fs.launched) m.play(v, f.ollie().sound(), 0.6f, 1.5f);
        if (f.ollie() != null && fs.landed && fs.fell > 0.2) {
            m.play(v, "block.wood.fall", 0.7f, 1.3f);
            puff(v, 3);
        }

        // rolling: a tick of the wheels (or the sleigh bells) every few blocks, quicker and higher the faster
        if (f.roll() != null && moved > 0 && (v.st.onGround || f.hover() != null) && driver != null) {
            v.funRolled += moved;
            if (v.funRolled >= f.roll().every()) {
                v.funRolled %= f.roll().every();
                double share = Math.min(1, Math.abs(v.st.speed) / Math.max(1, v.spec.speed()));
                float pitch = (float) (f.roll().pitch() * (0.9 + 0.25 * share) * (0.96 + 0.08 * rnd.nextDouble()));
                m.play(v, f.roll().sound(), f.roll().volume(), Math.clamp(pitch, VehicleSounds.MIN_PITCH, VehicleSounds.MAX_PITCH));
            }
        }
        if (near && f.trail() != FunSpec.Trail.NONE) trail(v, f, ticks);

        if (f.toss() != null && driver != null) toss(v, f.toss(), turned, ticks);

        if (f.buck() != null && driver != null) {
            if (fs.thrown) throwOff(v, driver, f.buck());
            else if (ticks % 2 == 0) HudModule.flash(driver, Lang.mm("vehicle.fun.rodeo-meter", "time", seconds(fs.ridden), "meter", meter(fs.balance)), 400);
            if (fs.lurch != 0 && fs.lurchLeft % 7 == 6) m.play(v, "block.piston.extend", 0.35f, 0.6f + 0.2f * rnd.nextFloat());
            if (!fs.thrown) m.reseat(v);   // the rider rises and sinks with the bull's back
        }

        if (f.hover() != null && f.hover().pop() && fs.popped) {
            fs.poppedGround = v.st.onGround ? fs.poppedGround + 1 : 0;
            if (fs.poppedGround >= FunPhysics.REFILL_TICKS) refill(v);
        }

        // the drawn pose: every tick while it spins, tilts or squashes; the duck's rocking every few ticks
        boolean slow = f.amphibious() != null;
        if (!slow || Math.floorMod(ticks + v.id, 3) == 0) {
            FunPhysics.Pose p = near || driver != null ? FunPhysics.pose(fs, f, v.st, v.spec, ticks) : FunPhysics.Pose.REST;
            if (!p.equals(v.funPose)) {
                v.funPose = p;
                m.reglide(v, slow ? 3 : 2);
            }
        }
        if (v.funPart != null && (near || driver != null) && (f.buck() != null || Math.floorMod(ticks + v.id, 3) == 0)) {
            glide(v.funPart, part(v, ticks), f.buck() != null ? 2 : 3);
        }
    }

    private static String seconds(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0);
    }

    /** The rider's balance as a bar: the marker slides the way they are being thrown. */
    static String meter(double balance) {
        int cells = 11, at = (int) Math.round((Math.clamp(balance, -1, 1) + 1) / 2 * (cells - 1));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells; i++) {
            int off = Math.abs(i - cells / 2);
            String colour = off >= 4 ? "<red>" : off >= 2 ? "<yellow>" : "<green>";
            sb.append(i == at ? "<white>◆" : colour + "-");
        }
        return sb.toString();
    }

    private void puff(LiveVehicle v, int count) {
        Block under = v.world.getBlockAt((int) Math.floor(v.st.x), (int) Math.floor(v.st.y - 0.05), (int) Math.floor(v.st.z));
        v.world.spawnParticle(Particle.POOF, v.st.x, v.st.y + 0.1, v.st.z, count, 0.25, 0.02, 0.25, 0.02);
        if (under.getType().isSolid())
            v.world.spawnParticle(Particle.BLOCK, v.st.x, v.st.y + 0.1, v.st.z, count, 0.3, 0.05, 0.3, 0, under.getBlockData());
    }

    private void trail(LiveVehicle v, FunSpec f, long ticks) {
        boolean moving = Math.abs(v.st.speed) > 0.5;
        double[] tail = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, new VehicleSpec.Vec(0, 0.3, -v.spec.length() / 2));
        switch (f.trail()) {
            case GLITTER -> {
                // a witch's broom: sparkles off its bristles, more when it flies on
                if (v.st.onGround && !moving) return;
                if (Math.floorMod(ticks, moving ? 2 : 5) != 0) return;
                v.world.spawnParticle(Particle.WAX_OFF, tail[0], tail[1], tail[2], moving ? 3 : 1, 0.15, 0.1, 0.15, 0.4);
                v.world.spawnParticle(Particle.END_ROD, tail[0], tail[1], tail[2], 1, 0.1, 0.05, 0.1, 0.01);
            }
            case SNOW -> {
                if (!moving || !v.st.onGround || Math.floorMod(ticks, 2) != 0) return;
                FunPhysics.Ground g = new WorldTerrain(v.world).ground((int) Math.floor(v.st.x), (int) Math.floor(v.st.y - 0.05), (int) Math.floor(v.st.z));
                FunPhysics.Ground in = new WorldTerrain(v.world).ground((int) Math.floor(v.st.x), (int) Math.floor(v.st.y + 0.05), (int) Math.floor(v.st.z));
                if (g == FunPhysics.Ground.OTHER && in == FunPhysics.Ground.OTHER) return;
                int n = 1 + (int) Math.min(4, Math.abs(v.st.speed) / 2.5);
                v.world.spawnParticle(Particle.SNOWFLAKE, tail[0], v.st.y + 0.15, tail[2], n, 0.35, 0.05, 0.25, 0.03);
                v.world.spawnParticle(Particle.BLOCK, tail[0], v.st.y + 0.1, tail[2], n, 0.35, 0.05, 0.25, 0.05, Material.SNOW_BLOCK.createBlockData());
            }
            case BUBBLES -> {
                if (!moving || !v.fun.floating || Math.floorMod(ticks, 3) != 0) return;
                v.world.spawnParticle(Particle.SPLASH, tail[0], v.st.y + 0.3, tail[2], 4, 0.2, 0.02, 0.2, 0.05);
                v.world.spawnParticle(Particle.BUBBLE_POP, tail[0], v.st.y + 0.3, tail[2], 2, 0.2, 0.02, 0.2, 0.01);
            }
            case NONE -> { }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Riders falling off
    // ------------------------------------------------------------------------------------------

    /** The banana boat: in a hard turn at speed, each rider behind the driver may fall off, outwards, into the water. */
    private void toss(LiveVehicle v, FunSpec.Toss t, float turned, long ticks) {
        double chance = FunPhysics.tossChance(t, v.st.speed, turned / VehiclePhysics.DT);
        if (chance <= 0) return;
        for (int i = 1; i < v.seats.length; i++) {
            Player p = v.rider(i);
            if (p == null || ticks - v.seatSince[i] < FunPhysics.TOSS_GRACE || rnd.nextDouble() >= chance) continue;
            m.leave(v, i);
            // flung outwards: a right turn throws them to the left
            double[] f = VehiclePhysics.forward(v.st.yaw);
            double side = turned > 0 ? -1 : 1;
            p.setVelocity(new Vector(-f[1] * side * 0.45 + f[0] * 0.15, 0.35, f[0] * side * 0.45 + f[1] * 0.15));
            m.play(v, "entity.generic.splash", 0.9f, 1.1f);
            v.world.spawnParticle(Particle.SPLASH, p.getLocation(), 20, 0.4, 0.1, 0.4, 0.2);
            HudModule.flash(p, Lang.mm("vehicle.fun.tossed"));
        }
    }

    /** The rodeo rider loses their balance: off they fly, the way they were slipping. */
    private void throwOff(LiveVehicle v, Player p, FunSpec.Buck b) {
        double side = Math.signum(v.fun.balance);
        m.leave(v, 0);   // riderLeft tells them their time
        double[] f = VehiclePhysics.forward(v.st.yaw);
        p.setVelocity(new Vector(-f[1] * side * b.launch(), 0.45 + b.launch() * 0.3, f[0] * side * b.launch()));
        m.play(v, "entity.player.attack.knockback", 0.9f, 0.8f);
        m.play(v, "entity.cow.hurt", 0.8f, 0.7f);
    }

    /**
     * A rider is off (any way: sneaking, thrown, tossed, quitting): the rodeo's time and record, a fall guard from
     * something that floats or bounces. Called before they are moved out.
     */
    void riderLeft(LiveVehicle v, int seat, Player p, long ticks) {
        FunSpec f = fun(v);
        if (f == null || p == null) return;
        if (f.buck() != null && seat == 0) {
            FunPhysics.State fs = v.fun;
            double time = fs.ridden / 20.0;
            if (fs.ridden > 0) {
                Double best = p.getPersistentDataContainer().get(RODEO_BEST, PersistentDataType.DOUBLE);
                boolean record = best == null || time > best;
                if (record) p.getPersistentDataContainer().set(RODEO_BEST, PersistentDataType.DOUBLE, time);
                double shown = record ? time : best;
                String key = fs.thrown ? "vehicle.fun.rodeo-thrown" : "vehicle.fun.rodeo-off";
                p.sendMessage(Lang.msg(key, "time", seconds(fs.ridden), "best", String.format(java.util.Locale.ROOT, "%.1f", shown)));
                HudModule.flash(p, Lang.mm("vehicle.fun.rodeo-time", "time", seconds(fs.ridden), "best", String.format(java.util.Locale.ROOT, "%.1f", shown)));
                if (record && best != null) p.sendMessage(Lang.msg("vehicle.fun.rodeo-record", "time", seconds(fs.ridden)));
            }
            fs.thrown = false;
            fs.ridden = 0;
            fs.balance = 0;
        }
        if (f.hover() != null || f.hop() != null || f.buck() != null || f.ollie() != null) {
            double ground = FunPhysics.groundBelow(v.st.x, v.st.y + 0.01, v.st.z, v.st.yaw, v.spec, terrain(v, new WorldTerrain(v.world)), 16);
            double groundY = Double.isNaN(ground) ? v.st.y - 16 : ground;
            guards.put(p.getUniqueId(), new Guard(v.world.getUID(), groundY - 0.6, ticks + GUARD_TICKS));
        }
    }

    /** Fall damage after getting off: none down to the ground that was under the vehicle; past it, as usual. */
    void onFall(EntityDamageEvent e, long ticks) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Guard g = guards.remove(p.getUniqueId());
        if (g == null || ticks > g.until() || !p.getWorld().getUID().equals(g.world())) return;
        if (p.getLocation().getY() >= g.groundY()) e.setCancelled(true);
    }

    void forget(UUID player) {
        guards.remove(player);
    }

    /** Ticks: forgets fall guards gone stale (and those of players back on the ground). */
    void sweep(long ticks) {
        if (ticks % 40 != 0 || guards.isEmpty()) return;
        guards.values().removeIf(g -> ticks > g.until());
    }

    // ------------------------------------------------------------------------------------------
    // Balloons
    // ------------------------------------------------------------------------------------------

    /** A projectile (not from one of its riders) hit a vehicle with balloons: they pop and it floats down. */
    void hit(LiveVehicle v, Entity shooter) {
        FunSpec f = fun(v);
        if (f == null || f.hover() == null || !f.hover().pop() || v.fun.popped) return;
        if (shooter instanceof Player s && v.seatOf(s) >= 0) return;
        v.fun.popped = true;
        v.fun.poppedGround = 0;
        if (v.funPart != null) v.funPart.setItemStack(ItemStack.empty());
        m.play(v, "entity.firework_rocket.blast", 1.2f, 1.7f);
        m.play(v, "entity.firework_rocket.blast", 0.9f, 1.95f);
        double[] c = f.part() == null ? new double[]{v.st.x, v.st.y + 2, v.st.z}
                : VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, f.part().at());
        for (Color col : List.of(Color.fromRGB(0xF2, 0x4B, 0x5B), Color.fromRGB(0xFF, 0xC8, 0x3D), Color.fromRGB(0x4F, 0xA8, 0xF2),
                Color.fromRGB(0x6C, 0xD4, 0x7A), Color.fromRGB(0xC0, 0x7C, 0xF0)))
            v.world.spawnParticle(Particle.DUST, c[0], c[1] + 1.2, c[2], 8, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(col, 1.4f));
        for (int i = 0; i < v.seats.length; i++) {
            Player p = v.rider(i);
            if (p != null) HudModule.flash(p, Lang.mm("vehicle.fun.popped"));
        }
    }

    private void refill(LiveVehicle v) {
        FunSpec f = fun(v);
        v.fun.popped = false;
        v.fun.poppedGround = 0;
        v.fun.wasRidden = false;   // a rider still sitting in it rises again
        if (v.funPart != null && f.part() != null) v.funPart.setItemStack(VehicleModule.model(f.part().model()));
        m.play(v, "block.note_block.chime", 0.8f, 1.5f);
        Player d = v.rider(0);
        if (d != null) HudModule.flash(d, Lang.mm("vehicle.fun.refilled"));
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    /** The separate part (the bull, the balloons), drawn with the vehicle's other parts. */
    void spawn(LiveVehicle v, long ticks) {
        FunSpec f = fun(v);
        if (f == null || f.part() == null) return;
        ItemStack shown = v.fun.popped ? ItemStack.empty() : VehicleModule.model(f.part().model());
        v.funPart = m.display(v, shown, part(v, ticks));
    }

    void remove(LiveVehicle v) {
        if (v.funPart != null) v.funPart.remove();
        v.funPart = null;
        v.funPose = FunPhysics.Pose.REST;
    }

    static boolean broken(LiveVehicle v) {
        return v.funPart != null && !v.funPart.isValid();
    }

    /** The part's transformation: at its point, nodded and rolled by the bull's bucking or swaying in the breeze (balloons). */
    static Transformation part(LiveVehicle v, long ticks) {
        FunSpec f = fun(v);
        VehicleSpec.Vec at = f.part().at();
        Vector3f t = new Vector3f((float) -at.right(), (float) at.up(), (float) at.forward());
        Quaternionf q = new Quaternionf();
        if (f.buck() != null) {
            q.rotateZ((float) -Math.toRadians(v.fun.buckRoll)).rotateX((float) Math.toRadians(v.fun.buckPitch));
        } else {
            double sway = Math.abs(v.st.speed) > 0.3 ? 5 : 3;
            q.rotateZ((float) Math.toRadians(sway * Math.sin(ticks * 0.045 + v.id)))
                    .rotateX((float) Math.toRadians(sway * 0.7 * Math.sin(ticks * 0.031 + 2 * v.id) - Math.signum(v.st.speed) * 4));
        }
        float k = (float) f.part().scale();
        return new Transformation(t, q, new Vector3f(k, k, k), new Quaternionf());
    }

    private static void glide(ItemDisplay d, Transformation t, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(t);
    }

    /**
     * A drawn part's transformation in the vehicle's pose ({@link FunPhysics.Pose}): turned round about its middle,
     * tipped about the axle, rocked about the ground under it, lifted and squashed. A wheel is not tipped (the segway's
     * body rocks on its wheels).
     */
    static Transformation posed(LiveVehicle v, Transformation t, boolean wheel) {
        FunPhysics.Pose p = v.funPose;
        if (p == null || p.rest()) return t;
        Quaternionf q = new Quaternionf();
        if (p.spin() != 0) q.rotateY((float) Math.toRadians(p.spin()));
        if (p.roll() != 0) q.rotateZ((float) -Math.toRadians(p.roll()));   // display +x is the vehicle's left
        Vector3f tr = new Vector3f(t.getTranslation()).rotate(q);
        Quaternionf left = new Quaternionf(q).mul(t.getLeftRotation());
        if (p.pitch() != 0 && !wheel) {
            Quaternionf pitch = new Quaternionf().rotateX((float) Math.toRadians(p.pitch()));   // +x lowers the front
            Vector3f pivot = new Vector3f(0, (float) v.spec.wheelRadius(), 0);
            tr.sub(pivot).rotate(pitch).add(pivot);
            left = new Quaternionf(pitch).mul(left);
        }
        Vector3f scale = new Vector3f(t.getScale());
        if (p.squash() != 1 && !wheel) {
            tr.y *= (float) p.squash();
            scale.y *= (float) p.squash();
        }
        tr.y += (float) p.lift();
        return new Transformation(tr, left, scale, t.getRightRotation());
    }

    /** How far a seat's rider is lifted by the behaviour (the bull's back rising under them; the duck's waddle). */
    static double seatLift(LiveVehicle v, int seat) {
        FunSpec f = fun(v);
        if (f == null) return 0;
        double lift = v.funPose == null ? 0 : v.funPose.lift();
        if (f.buck() != null && seat == 0) lift += v.fun.buckLift;
        return lift;
    }

    /** What space and the keys do on this vehicle, for the driving hint (a messages.yml key), or null: the usual. */
    static String keys(VehicleSpec s) {
        FunSpec f = s.fun();
        if (f == null) return null;
        if (f.hop() != null) return "vehicle.fun.keys-hop";
        if (f.ollie() != null) return "vehicle.fun.keys-ollie";
        if (f.hover() != null) return f.lookSteer() ? "vehicle.fun.keys-balloon" : "vehicle.fun.keys-hover";
        if (f.buck() != null) return "vehicle.fun.keys-rodeo";
        if (f.toot() != null) return "vehicle.fun.keys-quack";
        if (f.pivot()) return "vehicle.fun.keys-pivot";
        if (f.toss() != null) return "vehicle.fun.keys-banana";
        return null;
    }

    /** Where someone getting off something up in the air steps out: beside their seat, at its height (they drop down). */
    static Location airExit(LiveVehicle v, List<double[]> tries) {
        for (double[] c : tries) {
            Block feet = v.world.getBlockAt((int) Math.floor(c[0]), (int) Math.floor(c[1] + 0.01), (int) Math.floor(c[2]));
            if (!v.world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)) continue;
            if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable())
                return new Location(v.world, c[0], c[1], c[2]);
        }
        return null;
    }
}
