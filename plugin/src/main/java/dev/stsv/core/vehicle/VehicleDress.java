package dev.stsv.core.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * What a car wears besides its body ({@link VehicleSpec.Dress}, {@code movers:} and {@code plates:} in the item file):
 *
 * <ul>
 * <li><b>moving parts</b>: a door that swings open while someone gets in or out, a wing that rises with the speed and
 * stands up as an air brake, a mascot that comes out when the engine runs. Each is a model drawn in the body's own
 * space (as the lamps are: it lies exactly on the body when it is at rest) on a display of its own, which follows
 * the body's pose (lean, springs) and is turned about its hinge and slid by how far its trigger is on;</li>
 * <li><b>name plates</b>: the owner's name on a plate at the nose and the tail, a text display with a pale background
 * (the background is the plate), fitted to the plate's size.</li>
 * </ul>
 *
 * The geometry is {@link #mover} and {@link #plate} (plain functions of the body's transformation, tested without a
 * server); how far a trigger is on is {@link #want} and eased by {@link #ease}.
 */
final class VehicleDress {

    /** Ticks a door stays open after someone got in or out (it shuts sooner when the car moves off). */
    static final int DOOR_TICKS = 26;
    /** Blocks a second over which a door shuts whatever its time. */
    static final double DOOR_SPEED = 1.0;
    /** A text display's line is 10 text pixels of 1/40 block high at scale 1, and a letter about 6 wide. */
    static final float LINE = 10 / 40f, LETTER = 6 / 40f;
    /** How far in front of the plate's place the text stands (blocks): clear of the body's face. */
    static final float PROUD = 0.012f;
    /** The plate: a pale ground, dark letters. */
    static final int PLATE_ARGB = 0xFFECEEF0;
    static final TextColor PLATE_INK = TextColor.color(0x15171C);

    /** A vehicle's dress as it is drawn now. */
    static final class State {
        final List<ItemDisplay> movers = new ArrayList<>();
        final List<TextDisplay> plates = new ArrayList<>();
        /** how far each mover's turn and slide are on (0 .. 1), and what was last drawn */
        float[] turn = new float[0], slide = new float[0];
        Transformation[] shown = new Transformation[0];
        Transformation[] plateShown = new Transformation[0];
        String plateName;
        /** the tick every seat's door shuts again */
        long[] doorUntil = new long[0];
    }

    private final VehicleModule m;

    VehicleDress(VehicleModule m) {
        this.m = m;
    }

    // ------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------

    /** A vehicle point or direction [right, up, forward] in the display's frame: +x is the vehicle's left, +z forward. */
    static Vector3f local(VehicleSpec.Vec p) {
        return new Vector3f((float) -p.right(), (float) p.up(), (float) p.forward());
    }

    /**
     * A moving part's transformation: the body's, then turned {@code turn} of the way about its hinge and slid
     * {@code slide} of the way. The part's model is drawn in the body's space, so with both at 0 this is the body's own
     * transformation. A point of the part at the hinge stays where the body has it, whatever the turn.
     */
    static Transformation mover(Transformation body, VehicleSpec.Mover mv, float turn, float slide) {
        Quaternionf left = new Quaternionf(body.getLeftRotation());
        Vector3f t = new Vector3f(body.getTranslation());
        if (turn != 0 && mv.turn() != 0) {
            Vector3f axis = local(mv.axis()).normalize();
            Quaternionf q = new Quaternionf().rotateAxis((float) Math.toRadians(mv.turn() * turn), axis);
            Vector3f hinge = local(mv.hinge());
            // T(t) L T(P) Q T(-P) S = T(t + L (P - Q P)) (L Q) S for a uniform scale S
            Vector3f moved = new Vector3f(hinge).sub(new Vector3f(hinge).rotate(q)).rotate(left);
            t.add(moved);
            left.mul(q);
        }
        if (slide != 0) t.add(local(mv.slide()).mul(slide).rotate(new Quaternionf(body.getLeftRotation())));
        return new Transformation(t, left, body.getScale(), body.getRightRotation());
    }

    /** The scale a name is written at so that it fits the plate (its width by the letters, its height by one line). */
    static float plateScale(VehicleSpec.Plate p, String name) {
        int letters = Math.max(3, name.length());
        return (float) Math.min(p.height() / LINE, p.width() / (letters * LETTER + 2 / 40f));
    }

    /**
     * A name plate's transformation: at its place on the body (the text's bottom middle), facing forward, or back
     * for a plate on the tail, standing a little proud of the body.
     */
    static Transformation plate(Transformation body, VehicleSpec.Plate p, String name) {
        Quaternionf bodyTurn = new Quaternionf(body.getLeftRotation());
        Quaternionf left = new Quaternionf(bodyTurn);
        if (p.back()) left.rotateY((float) Math.PI);
        float s = plateScale(p, name);
        // the text's bottom is its origin: the plate's middle is half a line under its place's middle
        Vector3f at = local(p.at()).add(0, -LINE * s / 2, p.back() ? -PROUD : PROUD).rotate(bodyTurn).add(body.getTranslation());
        return new Transformation(at, left, new Vector3f(s, s, s), new Quaternionf());
    }

    // ------------------------------------------------------------------------------------------
    // Triggers
    // ------------------------------------------------------------------------------------------

    /**
     * How far a trigger wants to be on now (0 .. 1). door: open while a rider of one of its seats got in or out
     * lately and the car stands. speed: out from half of {@code from} of the top speed, fully at {@code from}.
     * brake: braking (the other pedal than the way it goes) at a quarter of the top speed or faster. engine: it runs.
     */
    static float want(VehicleSpec.Channel c, VehicleSpec.Mover mv, double speed, double top, boolean braking, boolean engine, boolean doorOpen) {
        return switch (c) {
            case DOOR -> doorOpen && Math.abs(speed) < DOOR_SPEED ? 1f : 0f;
            case SPEED -> {
                double share = top <= 0 ? 0 : speed / top, from = mv.from();
                yield (float) Math.clamp((share - from / 2) / (from / 2), 0, 1);
            }
            case BRAKE -> braking && top > 0 && speed >= top / 4 ? 1f : 0f;
            case ENGINE -> engine ? 1f : 0f;
            case NONE -> 0f;
        };
    }

    /** One tick from now towards want: the whole way takes ticks ticks, and it slows into both ends. */
    static float ease(float now, float want, int ticks) {
        float step = 1f / Math.max(1, ticks);
        return Math.abs(want - now) <= step ? want : now + Math.copySign(step, want - now);
    }

    /** The eased amount as the part is drawn: slow out of rest, slow into its end (a smooth step). */
    static float shape(float a) {
        return a * a * (3 - 2 * a);
    }

    // ------------------------------------------------------------------------------------------
    // The displays
    // ------------------------------------------------------------------------------------------

    void spawn(LiveVehicle v) {
        VehicleSpec.Dress dress = v.spec.extras().dress();
        State s = v.dress;
        clear(s);
        if (dress.movers().isEmpty() && dress.plates().isEmpty()) return;
        Transformation body = VehicleModule.body(v, v.tiltShown);
        int n = dress.movers().size();
        if (s.turn.length != n) {
            s.turn = new float[n];
            s.slide = new float[n];
        }
        s.shown = new Transformation[n];
        if (s.doorUntil.length != v.seats.length) s.doorUntil = new long[v.seats.length];
        for (int i = 0; i < n; i++) {
            VehicleSpec.Mover mv = dress.movers().get(i);
            s.shown[i] = mover(body, mv, shape(s.turn[i]), shape(s.slide[i]));
            s.movers.add(m.display(v, VehicleModule.model(m.tuner.part(v, mv.model())), s.shown[i]));
        }
        s.plateShown = new Transformation[dress.plates().size()];
        s.plateName = v.ownerName == null ? "" : v.ownerName;
        Display.Brightness b = VehicleModule.brightness(v.light);
        for (int i = 0; i < dress.plates().size(); i++) {
            VehicleSpec.Plate p = dress.plates().get(i);
            Transformation t = s.plateShown[i] = plate(body, p, s.plateName);
            s.plates.add(v.world.spawn(m.at(v), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.text(Component.text(s.plateName, m.tuner.plateInk(v)));
                d.setBackgroundColor(Color.fromARGB(PLATE_ARGB));
                d.setShadowed(false);
                d.setSeeThrough(false);
                d.setLineWidth(4000);
                d.setBillboard(Display.Billboard.FIXED);
                d.setTransformation(t);
                d.setTeleportDuration(m.smoothing());
                d.setInterpolationDuration(m.smoothing());
                d.setBrightness(b);
                d.setViewRange(0.35f);
                d.getPersistentDataContainer().set(VehicleModule.PART, PersistentDataType.LONG, v.id);
            }));
        }
    }

    private static void clear(State s) {
        s.movers.forEach(ItemDisplay::remove);
        s.plates.forEach(TextDisplay::remove);
        s.movers.clear();
        s.plates.clear();
    }

    void remove(LiveVehicle v) {
        clear(v.dress);
    }

    static boolean broken(LiveVehicle v) {
        return v.dress.movers.stream().anyMatch(d -> !d.isValid()) || v.dress.plates.stream().anyMatch(d -> !d.isValid());
    }

    void teleport(LiveVehicle v, Location at) {
        for (ItemDisplay d : v.dress.movers) d.teleport(at);
        for (TextDisplay d : v.dress.plates) d.teleport(at);
    }

    void brighten(LiveVehicle v, Display.Brightness b) {
        for (ItemDisplay d : v.dress.movers) d.setBrightness(b);
        for (TextDisplay d : v.dress.plates) d.setBrightness(b);
    }

    /** Someone got into or out of a seat: its doors open for a while. */
    void door(LiveVehicle v, int seat, long ticks) {
        long[] until = v.dress.doorUntil;
        if (seat >= 0 && seat < until.length) until[seat] = ticks + DOOR_TICKS;
    }

    /** Every tick: the triggers move the parts, and the parts follow the body's pose. */
    void tick(LiveVehicle v, DriveInput in, double top, boolean engine, long ticks) {
        VehicleSpec.Dress dress = v.spec.extras().dress();
        State s = v.dress;
        if (s.movers.isEmpty() && s.plates.isEmpty()) return;
        Transformation body = VehicleModule.body(v, v.tiltShown);
        boolean braking = v.st.speed > 0 ? in.backward() && !in.forward() : v.st.speed < 0 && in.forward() && !in.backward();
        double speed = Math.abs(v.st.speed);
        for (int i = 0; i < s.movers.size(); i++) {
            VehicleSpec.Mover mv = dress.movers().get(i);
            boolean open = false;
            for (int seat = 0; seat < s.doorUntil.length; seat++)
                if (ticks < s.doorUntil[seat] && (mv.seats().isEmpty() || mv.seats().contains(seat))) open = true;
            s.turn[i] = ease(s.turn[i], want(mv.when(), mv, speed, top, braking, engine, open), mv.ticks());
            s.slide[i] = ease(s.slide[i], want(mv.slideWhen(), mv, speed, top, braking, engine, open), mv.ticks());
            Transformation t = mover(body, mv, shape(s.turn[i]), shape(s.slide[i]));
            if (!t.equals(s.shown[i])) {
                s.shown[i] = t;
                glide(s.movers.get(i), t);
            }
        }
        if (s.plates.isEmpty()) return;
        String name = v.ownerName == null ? "" : v.ownerName;
        boolean renamed = !name.equals(s.plateName);
        s.plateName = name;
        for (int i = 0; i < s.plates.size(); i++) {
            TextDisplay d = s.plates.get(i);
            if (renamed) d.text(Component.text(name, m.tuner.plateInk(v)));
            Transformation t = plate(body, dress.plates().get(i), name);
            if (!t.equals(s.plateShown[i])) {
                s.plateShown[i] = t;
                glide(d, t);
            }
        }
    }

    private static void glide(Display d, Transformation t) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(t);
    }
}
