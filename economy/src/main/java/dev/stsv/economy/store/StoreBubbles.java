package dev.stsv.economy.store;

import dev.stsv.economy.bus.BusModule;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store customers' speech bubbles ({@link Bubbles} says where): one bubble per customer at a time (a new line takes the
 * old one down at once), each out of the way of its neighbours' bubbles, the till's screen and the answer buttons (raised
 * over them, a row up from the next customer's), a line with no room waiting until a bubble near it fades (chatter is let
 * go), a scene's line taking down whatever is in its way. The bubble itself is the bus's ({@link BusModule#bubble}: the
 * same look), only raised; the bus's own bubbles are not touched.
 */
final class StoreBubbles {

    private final StoreModule m;
    /** speaker -> their bubble now */
    private final Map<UUID, Shown> shown = new HashMap<>();
    /** speaker -> a line waiting for room (the newest only) */
    private final Map<UUID, Waiting> waiting = new LinkedHashMap<>();

    private record Shown(Entity who, TextDisplay d, double lift, double tall, long until, Vector3f base) {}

    private record Waiting(Entity who, String text, long life, Bubbles.Kind kind, long said) {}

    StoreBubbles(StoreModule m) {
        this.m = m;
    }

    /** `who` says this for `life` ticks (their bubble before goes now). */
    void say(Entity who, String text, long life, Bubbles.Kind kind) {
        if (who == null || !who.isValid() || text == null || text.isBlank()) return;
        if (kind == Bubbles.Kind.CHATTER) {   // chatter: only when there is room now, never over a line of theirs
            if (!waiting.containsKey(who.getUniqueId())) show(who, text, life, kind, System.currentTimeMillis());
            return;
        }
        forget(who);
        long now = System.currentTimeMillis();
        if (!show(who, text, life, kind, now) && Bubbles.stillWaits(kind, now, now)) {
            waiting.put(who.getUniqueId(), new Waiting(who, text, life, kind, now));
        }
    }

    /** Every tick: the faded ones forgotten, the waiting lines shown once there is room (or let go). */
    void tick() {
        if (shown.isEmpty() && waiting.isEmpty()) return;
        long now = System.currentTimeMillis();
        shown.values().removeIf(s -> s.until <= now || !s.d.isValid() || !s.who.isValid());
        for (Iterator<Waiting> it = waiting.values().iterator(); it.hasNext(); ) {
            Waiting w = it.next();
            if (!w.who.isValid() || !Bubbles.stillWaits(w.kind, w.said, now)) it.remove();
            else if (show(w.who, w.text, w.life, w.kind, now)) it.remove();
        }
    }

    /** Something new showing near this speaker (buttons over them, the till): their bubble moves out of its way, or goes. */
    void refit(Entity who) {
        if (who == null) return;
        Shown s = shown.get(who.getUniqueId());
        if (s == null || !s.d.isValid()) return;
        long now = System.currentTimeMillis();
        double want = want(who);
        double y = Bubbles.place(who.getLocation().getX(), who.getLocation().getZ(), want, s.tall,
                who.getLocation().getY() + Bubbles.CEILING, bands(who, now));
        if (Double.isNaN(y)) {
            forget(who);
            return;
        }
        lift(s.d, s.base, y - want);
        shown.put(who.getUniqueId(), new Shown(who, s.d, y - want, s.tall, s.until, s.base));
    }

    /** Their bubble down now, a line of theirs waiting forgotten (they spoke again, left, the shift ended). */
    void forget(Entity who) {
        if (who == null) return;
        waiting.remove(who.getUniqueId());
        Shown s = shown.remove(who.getUniqueId());
        if (s != null && s.d.isValid()) s.d.remove();
    }

    void clear() {
        for (Shown s : shown.values()) if (s.d.isValid()) s.d.remove();
        shown.clear();
        waiting.clear();
    }

    // ------------------------------------------------------------------------------------------

    private boolean show(Entity who, String text, long life, Bubbles.Kind kind, long now) {
        BusModule bus = m.eco().bus();
        if (bus == null) return true;   // (nothing to show it with: done)
        double x = who.getLocation().getX(), z = who.getLocation().getZ(), want = want(who), tall = Bubbles.tall(text);
        double ceiling = who.getLocation().getY() + Bubbles.CEILING;
        List<Bubbles.Band> others = bands(who, now);
        double y = Bubbles.place(x, z, want, tall, ceiling, others);
        if (Double.isNaN(y)) {
            if (kind != Bubbles.Kind.SCENE) return false;
            // a scene's line goes on: the other customers' bubbles in its way come down (the fixed things stay)
            List<Entity> speakers = new ArrayList<>();
            for (Shown s : shown.values()) if (s.who != who && s.until > now) speakers.add(s.who);
            List<Bubbles.Band> mine = bubbleBands(speakers);
            for (int i : Bubbles.inTheWay(x, z, want, want + tall, mine)) forget(speakers.get(i));
            y = Bubbles.place(x, z, want, tall, ceiling, bands(who, now));
            if (Double.isNaN(y)) y = want;
        }
        Shown before = shown.remove(who.getUniqueId());   // one bubble each: the one before goes
        if (before != null && before.d.isValid()) before.d.remove();
        TextDisplay d = bus.bubble(who, text, life);
        Vector3f base = new Vector3f(d.getTransformation().getTranslation());
        lift(d, base, y - want);
        shown.put(who.getUniqueId(), new Shown(who, d, y - want, tall, now + life * 50, base));
        return true;
    }

    /** Where this speaker's bubble wants its bottom: over their head. */
    private static double want(Entity who) {
        return who.getLocation().getY() + Bubbles.OVER_FEET;
    }

    /** Everything near that a bubble of `who` must keep clear of: the others' bubbles, the store's screen and buttons. */
    private List<Bubbles.Band> bands(Entity who, long now) {
        List<Entity> speakers = new ArrayList<>();
        for (Shown s : shown.values()) if (s.who != who && s.until > now && s.d.isValid() && s.who.isValid()) speakers.add(s.who);
        List<Bubbles.Band> out = new ArrayList<>(bubbleBands(speakers));
        out.addAll(m.fixedBands(who.getLocation()));
        return out;
    }

    private List<Bubbles.Band> bubbleBands(List<Entity> speakers) {
        List<Bubbles.Band> out = new ArrayList<>();
        for (Entity e : speakers) {
            Shown s = shown.get(e.getUniqueId());
            double lo = want(e) + s.lift;
            out.add(new Bubbles.Band(e.getLocation().getX(), e.getLocation().getZ(), lo, lo + s.tall));
        }
        return out;
    }

    private static void lift(TextDisplay d, Vector3f base, double by) {
        Transformation t = d.getTransformation();
        d.setTransformation(new Transformation(new Vector3f(base).add(0, (float) by, 0), t.getLeftRotation(), t.getScale(), t.getRightRotation()));
    }
}
