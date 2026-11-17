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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    /** the standing riders' puppets (the kick scooter, the segway, the pogo stick, the skateboard) */
    final StandingRiders standing;

    FunView(VehicleModule m) {
        this.m = m;
        this.standing = new StandingRiders(m);
    }

    /** Someone got on a seat: a standing rider's puppet is made for them. */
    void boarded(LiveVehicle v, int seat, Player p) {
        standing.board(v, seat, p);
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
        fs.tipped = false;
        fs.overload = false;
        if (fs.tumble > 0) {
            // over on its side (or its back): nobody steers, it scrapes to a stop
            v.st.speed *= 0.85;
            if (Math.abs(v.st.speed) < 0.05) v.st.speed = 0;
            return DriveInput.NONE;
        }
        DriveInput out = in;
        if (f.pivot() || f.lookSteer() || f.buck() != null)
            out = new DriveInput(in.forward(), in.backward(), false, false, in.jump(), in.sneak(), in.sprint());
        float turn = 0;
        if (f.buck() != null) {
            if (ridden) turn = FunPhysics.buck(fs, f.buck(), in.steer(), rnd);
            else {
                FunPhysics.calm(fs);
                if (fs.victory > 0) {
                    // the bull's victory: up on its hind legs, shaking its head
                    fs.victory--;
                    fs.buckPitch = -34 * Math.min(1, fs.victory / 6.0);
                    fs.buckRoll = 9 * Math.sin(fs.victory * 0.9);
                }
            }
        } else if (f.pivot() && ridden) {
            turn = f.dizzy() != null ? FunPhysics.spin(fs, f.dizzy(), in.steer(), v.st.speed, v.spec) : FunPhysics.pivotTurn(in.steer(), v.spec);
        } else if (f.lookSteer() && ridden) {
            turn = FunPhysics.lookTurn(v.st.yaw, driver.getYaw(), v.spec);
        }
        if (turn != 0) {
            float yaw = VehiclePhysics.wrap(v.st.yaw + turn);
            if (!VehiclePhysics.collides(v.st.x, v.st.y, v.st.z, yaw, v.spec, t)) v.st.yaw = yaw;
        }
        if (f.hop() != null) {
            // mashing space on the way up, near the top: the spring overloads (handled after the physics)
            boolean pressed = ridden && in.jump() && !fs.jumpBefore;
            if (f.hop().overload() && pressed && !v.st.onGround && v.st.vy > 0.05 && fs.hopLevel >= f.hop().max() - 0.5) fs.overload = true;
            FunPhysics.hop(v.st, fs, f.hop(), ridden, ridden && in.jump());
        } else if (f.ollie() != null) {
            FunPhysics.ollie(v.st, fs, f.ollie(), ridden && in.jump());
        } else if (f.toot() != null && FunPhysics.press(fs, ridden && in.jump()) && ticks >= v.tootAt) {
            v.tootAt = ticks + TOOT_TICKS;
            toot(v, f.toot(), ticks);
        }
        if (f.tip() != null && ridden && v.st.onGround && FunPhysics.tipping(fs, f.tip(), in.steer(), v.st.speed)) fs.tipped = true;
        if (f.hover() != null) {
            boolean down = ridden && (in.sprint() || driver.getLocation().getPitch() > 55);
            fs.up = ridden && in.jump();
            fs.down = down;
            if (!f.hover().free()) FunPhysics.aim(fs, f.hover(), ridden, fs.up, down);
        }
        if (f.bail() != null) fs.wobble = FunPhysics.wobble(f.bail(), !v.st.onGround, ridden ? in.steer() : 0, v.st.speed, fs.wobble);
        if (f.stand() != null && f.stand().kind() == RiderPose.Kind.KICK) {
            // the kick: while speeding up on the ground, a full stroke every 16 ticks; a stroke started is finished
            double top = v.spec.speed();
            boolean kicking = ridden && in.forward() && !in.backward() && v.st.onGround && v.st.speed < top * 0.95;
            if (kicking || fs.kick > 0) {
                fs.kick += 1 / 16.0;
                if (fs.kick >= 1) {
                    fs.kick = kicking ? fs.kick - 1 : 0;
                    if (kicking) m.play(v, "block.gravel.step", 0.25f, 1.6f);   // the shoe on the road
                }
            }
        }
        return out;
    }

    /** A squeak (a quack): a combo climbs in pitch, the sixth in a row makes the duck jump. */
    private void toot(LiveVehicle v, FunSpec.Toot t, long ticks) {
        FunPhysics.State fs = v.fun;
        int combo = t.combo() ? FunPhysics.squeak(fs, ticks) : 1;
        float pitch = t.combo() ? FunPhysics.squeakPitch(t.pitch(), combo) : t.pitch() * (0.95f + 0.1f * rnd.nextFloat());
        m.play(v, t.sound(), t.volume(), pitch);
        double[] head = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, new VehicleSpec.Vec(0, v.spec.height(), v.spec.length() * 0.3));
        v.world.spawnParticle(Particle.NOTE, head[0], head[1] + 0.4, head[2], 1, 0, 0, 0, Math.min(1, combo / 12.0));
        if (t.combo() && combo >= FunPhysics.COMBO_JUMP && v.st.onGround) {
            // the big one: a squeak up to the sky and a hop
            fs.tootCombo = 0;
            v.st.vy = FunPhysics.launchSpeed(1.2);
            v.st.onGround = false;
            v.st.air = VehiclePhysics.COYOTE + 1;
            m.play(v, t.sound(), t.volume() * 1.4f, 2.0f);
            v.world.spawnParticle(Particle.HEART, head[0], head[1] + 0.6, head[2], 5, 0.5, 0.3, 0.5, 0);
            Player d = v.rider(0);
            if (d != null) HudModule.flash(d, Lang.mm("vehicle.fun.squeak-jump"));
        }
    }

    /**
     * The physics tick of a vehicle that moves its own way (hovering, amphibious, the sled's surfaces); false: the usual
     * {@link VehiclePhysics#step} is for it.
     */
    boolean step(LiveVehicle v, DriveInput in, VehiclePhysics.Terrain world, double cap, double bonus, double top, double accel) {
        FunSpec f = fun(v);
        if (f == null) return false;
        if (f.hover() != null && f.hover().free()) {
            FunPhysics.freeFly(v.st, v.fun, f.hover(), in, v.rider(0) != null, v.fun.up, v.fun.down, v.spec, terrain(v, world), cap,
                    v.world.getMaxHeight() - FunPhysics.CEILING);
            return true;
        }
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

    /** It must be ticked though nobody drives it (still hovering, spinning, settling its pose, lying tumbled). */
    boolean active(LiveVehicle v) {
        FunSpec f = fun(v);
        if (f == null) return false;
        FunPhysics.State fs = v.fun;
        return (f.hover() != null && !FunPhysics.hoverSettled(fs, v.st)) || fs.spinLeft > 0 || fs.buckPitch != 0 || fs.victory > 0
                || (fs.popped && f.hover() != null) || fs.tumble > 0 || fs.squash > 0 || Math.abs(fs.roll) > 0.3
                || !v.funPose.rest() && !fs.floating;
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
        boolean ollieUp = fs.spinLeft > 0;
        FunPhysics.landing(v.st, fs, wasOnGround);
        if (f.ollie() != null) FunPhysics.spin(fs);
        boolean near = v.feel.watched;
        if (fs.tumble > 0) fs.tumble--;
        if (fs.squash > 0) fs.squash--;
        if (fs.crouchLeft > 0) fs.crouchLeft--;

        if (f.hop() != null && fs.landed && driver != null) {
            // boing: deeper from higher up; a puff of the ground's dust
            float pitch = (float) Math.clamp(1.35 - 0.1 * fs.fell, 0.7, 1.4);
            m.play(v, f.hop().sound(), 0.7f, pitch);
            puff(v, 4 + (int) Math.min(10, fs.fell * 2.5));
            fs.crouchLeft = 3;
        }
        if (fs.overload && driver != null) overload(v, driver);
        if (fs.tipped && driver != null) tipOver(v, driver, turned);
        if (f.crash() != null && v.st.bumped && Math.abs(fs.speedBefore) >= f.crash().speed() && fs.tumble == 0) crash(v);

        if (f.ollie() != null && fs.launched) {
            m.play(v, f.ollie().sound(), 0.6f, 1.5f);
            if (driver != null) HudModule.flash(driver, Lang.mm("vehicle.fun.trick", "trick", TRICKS[rnd.nextInt(TRICKS.length)]), 900);
        }
        if (f.ollie() != null && fs.landed && fs.fell > 0.2) {
            m.play(v, "block.wood.fall", 0.7f, 1.3f);
            puff(v, 3);
            fs.crouchLeft = 4;
            if (ollieUp || fs.fell > 0.5) landed(v, driver, f);
        }

        // rolling: a tick of the wheels (or the sleigh bells) every few blocks, quicker and higher the faster
        if (f.roll() != null && moved > 0 && (v.st.onGround || f.hover() != null) && driver != null && fs.tumble == 0) {
            v.funRolled += moved;
            if (v.funRolled >= f.roll().every()) {
                v.funRolled %= f.roll().every();
                double share = Math.min(1, Math.abs(v.st.speed) / Math.max(1, v.spec.speed()));
                float pitch = (float) (f.roll().pitch() * (0.9 + 0.25 * share) * (0.96 + 0.08 * rnd.nextDouble()));
                m.play(v, f.roll().sound(), f.roll().volume(), Math.clamp(pitch, VehicleSounds.MIN_PITCH, VehicleSounds.MAX_PITCH));
            }
        }
        if (near && f.trail() != FunSpec.Trail.NONE) trail(v, f, ticks);

        if (f.heel() != null) {
            double top = Math.min(v.spec.speed(), m.shownSettings().maxSpeed());
            FunPhysics.heel(fs, f.heel(), driver == null ? 0 : turned, v.st.speed, top, rnd.nextDouble() - 0.5);
            if (Math.abs(fs.roll) > 0.3 && v.hasRiders()) m.reseat(v);   // the riders sway with it
            if (Math.abs(fs.roll) > f.heel().roll() * 0.8 && ticks % 6 == 0) m.play(v, "entity.player.splash", 0.5f, 1.4f);
        }
        if (f.toss() != null && driver != null) toss(v, f, turned, ticks);

        if (f.dizzy() != null && driver != null) dizzy(v, driver, f.dizzy(), ticks);

        if (f.buck() != null && driver != null) {
            if (fs.thrown) throwOff(v, driver, f.buck());
            else {
                if (ticks % 2 == 0) HudModule.flash(driver, Lang.mm("vehicle.fun.rodeo-meter", "time", seconds(fs.ridden), "meter", meter(fs.balance)), 400);
                if (fs.move != FunPhysics.Move.CALM && fs.moveLeft % 5 == 4) m.play(v, "block.piston.extend", 0.45f, 0.5f + 0.3f * rnd.nextFloat());
                if (fs.move == FunPhysics.Move.SPIN && fs.moveLeft % 4 == 0) m.play(v, "entity.phantom.flap", 0.5f, 1.6f);
                if (fs.move == FunPhysics.Move.KICK && fs.moveLeft % 6 == 3) m.play(v, "entity.horse.angry", 0.6f, 0.6f);
                m.reseat(v);   // the rider rises and sinks with the bull's back
            }
        }

        if (f.hover() != null && f.hover().pop() && fs.popped) {
            if (!v.st.onGround) fs.spin = (fs.spin + 9) % 360;   // drifting down, turning slowly round and round
            else if (fs.spin != 0) {
                fs.spin = 0;
                m.play(v, "block.wool.fall", 1f, 0.7f);   // 털썩
            }
            fs.poppedGround = v.st.onGround ? fs.poppedGround + 1 : 0;
            if (fs.poppedGround >= FunPhysics.REFILL_TICKS) refill(v);
        }

        // the standing rider's puppet: posed to the motion
        if (v.standing != null) standing.tick(v, driver, motion(v, f), f.ollie() != null ? fs.spin : 0, ticks);

        // the drawn pose: every tick while it spins, tilts, tumbles or squashes; the duck's rocking every few ticks
        // (a boat's pose is drawn by its own rocking: VehicleModule#boat)
        boolean slow = f.amphibious() != null && fs.squash == 0 && v.st.onGround;
        if (!slow || Math.floorMod(ticks + v.id, 3) == 0) {
            FunPhysics.Pose p = near || driver != null || fs.tumble > 0 ? FunPhysics.pose(fs, f, v.st, v.spec, ticks) : FunPhysics.Pose.REST;
            if (!p.equals(v.funPose)) {
                v.funPose = p;
                if (!v.spec.floats()) m.reglide(v, slow ? 3 : 2);
            }
        } else {
            fs.speedBefore = v.st.speed;
        }
        if (v.funPart != null && (near || driver != null || fs.victory > 0) && (f.buck() != null || Math.floorMod(ticks + v.id, 3) == 0)) {
            glide(v.funPart, part(v, ticks), f.buck() != null ? 2 : 3);
        }
    }

    /** What the standing rider's pose follows now. */
    private static RiderPose.Motion motion(LiveVehicle v, FunSpec f) {
        FunPhysics.State fs = v.fun;
        double top = Math.max(0.1, v.spec.speed());
        double crouch = fs.crouchLeft > 0 ? fs.crouchLeft / 4.0 : 0;
        if (f.hop() != null && v.st.onGround) crouch = 1;
        if (f.hop() != null && !v.st.onGround && v.st.vy < 0 && v.st.y - fs.topY > -0.6 && fs.topY - v.st.y > 1) crouch = 0.4;
        return new RiderPose.Motion(v.st.speed / top, v.lean, fs.tilt, fs.kick, crouch, !v.st.onGround, fs.dizzy, fs.wobble,
                v.world.getGameTime(), f.stand() == null ? 0 : f.stand().deck());
    }

    /** What an ollie is called (shown at random as it pops). */
    static final String[] TRICKS = {"알리!", "360 알리!", "킥플립… 인 척!", "임파서블!", "트레 플립!", "노 컴플라이!", "하늘을 나는 판때기!"};

    /** A landed ollie: a clean one counts up the combo; carving hard at speed it may bail. */
    private void landed(LiveVehicle v, Player rider, FunSpec f) {
        FunPhysics.State fs = v.fun;
        if (rider == null) return;
        if (f.bail() != null && FunPhysics.bails(f.bail(), rider.getCurrentInput() == null ? 0 : DriveInput.of(rider.getCurrentInput()).steer(),
                v.st.speed, rnd.nextDouble())) {
            // bail: the board shoots on by itself, the rider sits down hard behind it
            fs.tricks = 0;
            double speed = v.st.speed;
            m.leave(v, 0);
            double[] fw = VehiclePhysics.forward(v.st.yaw);
            rider.setVelocity(new Vector(-fw[0] * 0.35, 0.45, -fw[1] * 0.35));
            v.st.speed = Math.min(v.spec.speed(), speed * 1.25);
            m.play(v, "entity.player.big_fall", 1f, 0.9f);
            m.play(v, "stsv:vehicle.common.slide_whistle", 0.8f, 0.6f);
            HudModule.flash(rider, Lang.mm("vehicle.fun.bail"));
            return;
        }
        fs.tricks++;
        if (fs.tricks >= 2) HudModule.flash(rider, Lang.mm("vehicle.fun.combo", "n", fs.tricks), 1200);
    }

    /** The kick scooter steered over: it falls on its side, the rider rolls off the other way. */
    private void tipOver(LiveVehicle v, Player rider, float turned) {
        FunPhysics.State fs = v.fun;
        double side = turned > 0 ? 1 : -1;   // a right turn throws it over to the left (outwards)
        FunPhysics.tumble(fs, 88 * side);
        m.leave(v, 0);
        double[] fw = VehiclePhysics.forward(v.st.yaw);
        double out = -side;   // + = right of the way it went
        rider.setVelocity(new Vector(fw[0] * 0.5 + -fw[1] * out * 0.45, 0.5, fw[1] * 0.5 + fw[0] * out * 0.45));
        m.play(v, "entity.item.break", 1f, 0.7f);
        m.play(v, "block.chain.fall", 1f, 0.8f);
        m.play(v, "stsv:vehicle.common.slide_whistle", 0.8f, 1.0f);
        puff(v, 8);
        HudModule.flash(rider, Lang.mm("vehicle.fun.tipped"));
    }

    /** The pogo stick's spring overloaded: the rider is shot up into the sky, the stick flops over. */
    private void overload(LiveVehicle v, Player rider) {
        FunPhysics.State fs = v.fun;
        fs.hopLevel = 0;
        m.leave(v, 0);
        rider.setVelocity(new Vector(0, 1.75, 0));
        FunPhysics.tumble(fs, rnd.nextBoolean() ? 90 : -90);
        v.st.vy = 0;
        m.play(v, "stsv:vehicle.pogo_stick.sproing", 1.2f, 1f);
        v.world.spawnParticle(Particle.CLOUD, v.st.x, v.st.y + 0.5, v.st.z, 14, 0.3, 0.2, 0.3, 0.08);
        HudModule.flash(rider, Lang.mm("vehicle.fun.overload"));
    }

    /** The sled into a wall at speed: it flips onto its back in a burst of snow, its riders fly on ahead. */
    private void crash(LiveVehicle v) {
        FunPhysics.State fs = v.fun;
        double[] fw = VehiclePhysics.forward(v.st.yaw);
        double speed = Math.abs(fs.speedBefore);
        for (int i = 0; i < v.seats.length; i++) {
            Player p = v.rider(i);
            if (p == null) continue;
            m.leave(v, i);
            p.setVelocity(new Vector(fw[0] * (0.25 + speed * 0.06), 0.55, fw[1] * (0.25 + speed * 0.06)));
            HudModule.flash(p, Lang.mm("vehicle.fun.crashed"));
        }
        FunPhysics.tumble(fs, 180);
        v.st.speed = 0;
        m.play(v, "block.wood.break", 1f, 0.8f);
        m.play(v, "block.snow.break", 1.2f, 0.6f);
        m.play(v, "block.note_block.bell", 0.8f, 0.6f);
        v.world.spawnParticle(Particle.SNOWFLAKE, v.st.x, v.st.y + 0.6, v.st.z, 40, 0.8, 0.5, 0.8, 0.12);
        v.world.spawnParticle(Particle.BLOCK, v.st.x, v.st.y + 0.6, v.st.z, 40, 0.8, 0.5, 0.8, 0.2, Material.SNOW_BLOCK.createBlockData());
    }

    /** The segway spinning on the spot: robot beeps climbing with the spin, stars when dizzy, and off they stagger. */
    private void dizzy(LiveVehicle v, Player rider, FunSpec.Dizzy d, long ticks) {
        FunPhysics.State fs = v.fun;
        if (fs.spinRun > 10 && ticks % 4 == 0) {
            float pitch = (float) Math.min(2, 0.8 + fs.spinRun / 40.0);
            m.play(v, "block.note_block.bit", 0.6f, pitch);
        }
        if (fs.dizzy > 0.4 && ticks % 3 == 0) {
            double a = ticks * 0.6;
            Location head = rider.getLocation().add(0, 2.0, 0);
            for (int k = 0; k < 3; k++) {
                double b = a + k * 2.1;
                v.world.spawnParticle(Particle.WAX_ON, head.getX() + Math.cos(b) * 0.45, head.getY(), head.getZ() + Math.sin(b) * 0.45, 1, 0, 0, 0, 0);
            }
        }
        if (fs.dizzy >= 1) {
            fs.dizzy = 0;
            fs.spinRun = 0;
            m.leave(v, 0);
            rider.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 140, 0, false, false, true));
            rider.setVelocity(new Vector((rnd.nextDouble() - 0.5) * 0.5, 0.25, (rnd.nextDouble() - 0.5) * 0.5));
            m.play(v, "entity.villager.no", 1f, 0.6f);
            HudModule.flash(rider, Lang.mm("vehicle.fun.dizzy"));
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
    private void toss(LiveVehicle v, FunSpec f, float turned, long ticks) {
        FunSpec.Toss t = f.toss();
        double chance = FunPhysics.tossChance(t, v.st.speed, turned / VehiclePhysics.DT);
        // heeled right over, they go twice as easily; nearly upright, hardly at all
        if (f.heel() != null) chance = Math.min(0.5, chance * FunPhysics.heelToss(f.heel(), v.fun.roll));
        if (chance <= 0) return;
        for (int i = 1; i < v.seats.length; i++) {
            Player p = v.rider(i);
            if (p == null || ticks - v.seatSince[i] < FunPhysics.TOSS_GRACE || rnd.nextDouble() >= chance) continue;
            m.leave(v, i);
            // flung outwards: a right turn throws them to the left
            double[] fw = VehiclePhysics.forward(v.st.yaw);
            double side = turned > 0 ? -1 : 1;
            p.setVelocity(new Vector(-fw[1] * side * 0.45 + fw[0] * 0.15, 0.35, fw[0] * side * 0.45 + fw[1] * 0.15));
            m.play(v, "entity.generic.splash", 1.2f, 0.9f);
            m.play(v, "stsv:vehicle.common.slide_whistle", 0.7f, 1.3f);
            v.world.spawnParticle(Particle.SPLASH, p.getLocation(), 30, 0.5, 0.1, 0.5, 0.3);
            HudModule.flash(p, Lang.mm("vehicle.fun.tossed"));
            // the boat, lighter now, rocks back the other way
            v.fun.rollVel -= Math.signum(v.fun.roll) * 6;
        }
    }

    /** The rodeo rider loses their balance: off they fly, the way they were slipping. */
    private void throwOff(LiveVehicle v, Player p, FunSpec.Buck b) {
        double side = Math.signum(v.fun.balance);
        m.leave(v, 0);   // riderLeft tells them their time
        double[] f = VehiclePhysics.forward(v.st.yaw);
        // up and over, the way they were slipping: a proper rodeo throw
        p.setVelocity(new Vector(-f[1] * side * b.launch(), 0.75 + b.launch() * 0.45, f[0] * side * b.launch()));
        m.play(v, "entity.player.attack.knockback", 1f, 0.8f);
        m.play(v, "stsv:vehicle.common.slide_whistle", 1f, 1f);
        // the bull's victory: up on its hind legs with a bellow
        v.fun.victory = 30;
        m.play(v, "entity.ravager.roar", 0.6f, 1.6f);
        m.play(v, "entity.cow.ambient", 1f, 0.6f);
    }

    /**
     * A rider is off (any way: sneaking, thrown, tossed, quitting): the rodeo's time and record, a fall guard from
     * something that floats or bounces. Called before they are moved out.
     */
    void riderLeft(LiveVehicle v, int seat, Player p, long ticks) {
        FunSpec f = fun(v);
        if (f == null || p == null) return;
        if (seat == 0 && v.standing != null) standing.left(v, p);
        if (seat == 0) {
            v.fun.kick = 0;
            v.fun.dizzy = 0;
            v.fun.spinRun = 0;
            v.fun.wobble = 0;
        }
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
        if (f.hover() != null || f.hop() != null || f.buck() != null || f.ollie() != null || f.tip() != null || f.dizzy() != null
                || f.crash() != null) {
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
        m.play(v, "stsv:vehicle.balloon_chair.trombone", 1.2f, 1f);   // wah wah wah waaah
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

    /** A seat where the heeling boat has swayed it (the seat as it is otherwise). */
    static VehicleSpec.Vec swayed(LiveVehicle v, int seat, VehicleSpec.Vec at) {
        FunSpec f = fun(v);
        if (f == null || f.heel() == null || Math.abs(v.fun.roll) < 0.05) return at;
        double pivot = v.spec.water() == null ? 0 : v.spec.water().draft();
        double[] d = FunPhysics.sway(v.fun.roll, at.up() - pivot);
        return new VehicleSpec.Vec(at.right() + d[0], at.up() + d[1], at.forward());
    }

    /** What space and the keys do on this vehicle, for the driving hint (a messages.yml key), or null: the usual. */
    static String keys(VehicleSpec s) {
        FunSpec f = s.fun();
        if (f == null) return null;
        if (f.tip() != null) return "vehicle.fun.keys-kick";
        if (f.crash() != null) return "vehicle.fun.keys-sled";
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
