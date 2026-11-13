package dev.stsv.core.vehicle;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Skid marks: the stripes a drifting car's rear tyres leave on the road, there for a few seconds and then gone.
 *
 * <p>A mark is a row of short flat pieces, each a text display with no text and a coloured background lying on the
 * ground (one small entity, nothing in the resource pack, any colour): a piece from where a wheel last was to where
 * it is now, laid every {@link #STEP} blocks while the car slides (a block at a time: at thirteen blocks a second that is
 * some twenty-six pieces a second for a car, and a bend that wide shows no corners), the rest of a block when it stops sliding. A piece fades to half after {@link #FADE} of its
 * time and is taken away at the end; never more than {@link #MAX} lie in the world (the oldest go first). They are
 * not saved: a restart leaves none.
 *
 * <p>What a mark looks like is a {@link Style} with a name (vehicle_feel.yml {@code skid-marks:}: colour, how see-through,
 * how wide, how long it stays, whether it glows), and a vehicle names the one it leaves ({@code sport.mark}): another
 * look is one more entry there.
 */
final class SkidMarks {

    /** Blocks a wheel moves before the next piece is laid; pieces overlap by OVERLAP so a curve shows no gaps. */
    static final double STEP = 1.0, OVERLAP = 0.08;
    /** The shortest piece worth laying when a slide ends, and the longest a wheel can have moved in a tick or two (a teleport is no mark). */
    static final double LEAST = 0.15, MOST = 3.0;
    /** The share of its time after which a piece is half as strong. */
    static final double FADE = 0.6;
    /** The most pieces in the world at once. */
    static final int MAX = 480;
    /** How high over the ground a piece lies (blocks): just clear of it, so it does not flicker with the block's face. */
    static final double LIFT = 0.02;
    /** How far a piece is drawn: 1 = 64 blocks. */
    static final float VIEW = 0.75f;
    /** A text display's background for one line of " ": 5 x 10 text pixels of 1/40 block, its middle half a pixel off the entity's. */
    static final float QUAD_W = 5 / 40f, QUAD_H = 10 / 40f, QUAD_OFF = -0.5f / 40f;

    /**
     * @param argb    the colour with its opacity
     * @param width   blocks across
     * @param ticks   how long a piece stays
     * @param glow    drawn at full brightness (a neon mark), else lit like the ground it lies on
     */
    record Style(String name, int argb, float width, int ticks, boolean glow) {}

    static Style style(String name, ConfigurationSection s) {
        String hex = s.getString("color", "#17181c").trim();
        if (!hex.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("skid-marks." + name + ".color 는 #rrggbb");
        double opacity = s.getDouble("opacity", 0.7), width = s.getDouble("width", 0.28), seconds = s.getDouble("seconds", 6);
        if (opacity <= 0.1 || opacity > 1) throw new IllegalArgumentException("skid-marks." + name + ".opacity 는 0.1 초과 1 이하");
        if (width < 0.05 || width > 1) throw new IllegalArgumentException("skid-marks." + name + ".width 는 0.05 ~ 1 (블록)");
        if (seconds < 0.5 || seconds > 30) throw new IllegalArgumentException("skid-marks." + name + ".seconds 는 0.5 ~ 30");
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return new Style(name, ((int) Math.round(opacity * 255) << 24) | rgb, (float) width, (int) Math.round(seconds * 20), s.getBoolean("glow"));
    }

    /** How a piece from (x0, z0) to (x1, z1) is drawn: the background's scale across and along, the turn about y (radians). */
    record Shape(float across, float along, float yaw) {}

    static Shape shape(double x0, double z0, double x1, double z1, double width) {
        double dx = x1 - x0, dz = z1 - z0;
        // laid flat, the background's height runs towards -z: turned so that it runs from the first point to the second
        return new Shape((float) (width / QUAD_W), (float) ((Math.hypot(dx, dz) + OVERLAP) / QUAD_H), (float) Math.atan2(-dx, -dz));
    }

    private record Piece(TextDisplay display, Style style, long fadesAt, long goesAt) {}

    private final NamespacedKey key;
    private final Deque<Piece> pieces = new ArrayDeque<>();

    SkidMarks(NamespacedKey key) {
        this.key = key;
    }

    boolean isMark(Entity e) {
        return e != null && e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Lays a piece on the ground at height y from (x0, z0) to (x1, z1). */
    void lay(World w, double x0, double y, double z0, double x1, double z1, Style st, long now) {
        Shape sh = shape(x0, z0, x1, z1, st.width());
        Quaternionf flat = new Quaternionf().rotateY(sh.yaw()).rotateX((float) (-Math.PI / 2));
        TextDisplay d = w.spawn(new Location(w, x0, y + LIFT, z0), TextDisplay.class, e -> {
            e.setPersistent(false);
            e.text(Component.text(" "));
            e.setBackgroundColor(Color.fromARGB(st.argb()));
            e.setShadowed(false);
            e.setSeeThrough(false);
            e.setBillboard(Display.Billboard.FIXED);
            // the background is drawn half a pixel to one side of the entity: moved back, so the piece lies on the wheel's line
            Vector3f centred = flat.transform(new Vector3f(QUAD_OFF * sh.across(), 0, 0));
            e.setTransformation(new Transformation(centred, flat, new Vector3f(sh.across(), sh.along(), 1f), new Quaternionf()));
            e.setViewRange(VIEW);
            e.setShadowRadius(0);
            if (st.glow()) e.setBrightness(new Display.Brightness(15, 15));
            e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        pieces.addLast(new Piece(d, st, now + Math.round(st.ticks() * FADE), now + st.ticks()));
        while (pieces.size() > MAX) pieces.removeFirst().display().remove();
    }

    /** Every tick: old pieces fade and go. */
    void tick(long now) {
        if (pieces.isEmpty() || now % 2 != 0) return;
        // looks stay for different times, so the oldest piece is not always the first to go: every piece is looked at
        for (var it = pieces.iterator(); it.hasNext(); ) {
            Piece p = it.next();
            if (now >= p.goesAt() || !p.display().isValid()) {
                p.display().remove();
                it.remove();
                continue;
            }
            if (now < p.fadesAt()) continue;
            int argb = half(p.style().argb());
            if (p.display().getBackgroundColor() == null || p.display().getBackgroundColor().asARGB() != argb) p.display().setBackgroundColor(Color.fromARGB(argb));
        }
    }

    /** The colour at half its opacity. */
    static int half(int argb) {
        return ((argb >>> 24) / 2 << 24) | (argb & 0xFFFFFF);
    }

    void clear() {
        for (Piece p : pieces) p.display().remove();
        pieces.clear();
    }
}
