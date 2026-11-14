package dev.stsv.core.emote;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The emote wheel on the player's own screen (감정표현 휠, the quick key): not a window of the game but a handful of
 * text displays only the player sees, carried on them by their client and turned to their camera (billboard), so a
 * display's translation is a place on the screen: x to the right, y up, -z ahead. They sit where passengers sit, the
 * top of the hitbox, a little above the eyes: every tick that difference is taken out of the translation for the
 * pitch the player looks at ({@link #onScreen}). The pictures are glyphs of the pack's font {@link #FONT}.
 *
 * <p>While the quick key's sneak is held the player's look picks a place of the wheel (how far they turned since it
 * opened: {@link #pick}); letting go plays it, letting go in the middle plays nothing.
 */
final class EmoteHud {

    static final Key FONT = Key.key("stsv", "emote_wheel");
    /** Glyphs of the font: the dial, the ring round the picked place, the pointer; the icons follow from {@link #ICON0}. */
    static final char DIAL = '', RING = '', POINTER = '', ICON0 = '';
    /** The icons in the font's order ({@link EmotePack#wheelFont}): every drawn emote, the plain one, the empty place. */
    static final List<String> ICONS = icons();
    /** The dial's picture and its places, picture pixels from its middle (x right, y down): the menu's wheel (menu_theme.wheel). */
    static final int DIAL_PX = 112;
    static final int[][] PLACES = {{0, -36}, {36, -18}, {36, 18}, {0, 36}, {-36, 18}, {-36, -18}};
    /** How far ahead of the eyes the wheel floats (blocks: nearer than a wall a player can stand against), and a picture pixel's size there. */
    static final double AHEAD = 0.25, PIXEL = 0.0014;
    /** Turning this many degrees from where the wheel opened picks a place; the pointer stops at {@link #REACH}. */
    static final double DEAD = 4.0, REACH = 14.0;
    /** The wheel closes by itself after this long (ticks). */
    static final int MAX_TICKS = 200;
    private static final float TEXT_PX = 0.025f;

    private static List<String> icons() {
        List<String> out = new ArrayList<>(new java.util.TreeSet<>(EmoteWheel.DRAWN).stream().map(EmoteWheel::icon).toList());
        out.add(EmoteWheel.ICON_ANY);
        out.add(EmoteWheel.ICON_EMPTY);
        return List.copyOf(out);
    }

    static char glyph(String icon) {
        int at = ICONS.indexOf(icon);
        return (char) (ICON0 + (at < 0 ? ICONS.indexOf(EmoteWheel.ICON_ANY) : at));
    }

    /**
     * Where a point of the screen is for a display that sits at the passenger point.
     *
     * @param x     blocks to the right of the screen's middle
     * @param y     blocks up from it
     * @param ahead blocks ahead of the eyes
     * @param above how far the passenger point is above the eyes (the player's height less their eye height)
     * @param pitch the player's pitch (degrees, down positive)
     * @return the display's translation {x, y, z}
     */
    static double[] onScreen(double x, double y, double ahead, double above, double pitch) {
        double t = Math.toRadians(pitch);
        return new double[]{x, y - above * Math.cos(t), -(ahead + above * Math.sin(t))};
    }

    /**
     * The place a look picks.
     *
     * @param turn  degrees the player turned to the right since the wheel opened
     * @param raise degrees they looked up since then
     * @return the place (0 the top, on clockwise), or -1 in the middle
     */
    static int pick(double turn, double raise) {
        if (Math.hypot(turn, raise) < DEAD) return -1;
        int best = -1;
        double bestDot = -2;
        double len = Math.hypot(turn, raise);
        for (int k = 0; k < PLACES.length; k++) {
            double px = PLACES[k][0], py = -PLACES[k][1], pl = Math.hypot(px, py);
            double dot = (turn * px + raise * py) / (len * pl);
            if (dot > bestDot) {
                bestDot = dot;
                best = k;
            }
        }
        return best;
    }

    /** The pointer's place for a look: picture pixels from the middle (x right, y down), never past the places' ring. */
    static double[] pointer(double turn, double raise) {
        double len = Math.hypot(turn, raise), max = 40;
        if (len < 1e-9) return new double[]{0, 0};
        double r = Math.min(len, REACH) / REACH * max;
        return new double[]{turn / len * r, -raise / len * r};
    }

    /** Degrees from a to b the short way round (-180 .. 180). */
    static double turned(double from, double to) {
        double d = (to - from) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // ------------------------------------------------------------------------------------------
    // One player's wheel
    // ------------------------------------------------------------------------------------------

    private final class Open {
        final float yaw0, pitch0;
        final String[] wheel;
        TextDisplay dial, ring, pointer, label;
        final TextDisplay[] icons = new TextDisplay[EmoteWheel.SLOTS];
        int picked = -1, ticks;

        Open(Player p, String[] wheel) {
            this.yaw0 = p.getLocation().getYaw();
            this.pitch0 = p.getLocation().getPitch();
            this.wheel = wheel;
        }

        List<TextDisplay> all() {
            List<TextDisplay> out = new ArrayList<>(List.of(dial, ring, pointer, label));
            out.addAll(List.of(icons));
            return out;
        }
    }

    private final STSVPlugin plugin;
    private final EmoteModule emotes;
    private final NamespacedKey key;
    private final Map<UUID, Open> open = new HashMap<>();

    EmoteHud(STSVPlugin plugin, EmoteModule emotes, NamespacedKey entityKey) {
        this.plugin = plugin;
        this.emotes = emotes;
        this.key = entityKey;
    }

    boolean showing(Player p) {
        return open.containsKey(p.getUniqueId());
    }

    private TextDisplay spawn(Player p, Component text) {
        Location at = p.getLocation().add(0, p.getHeight(), 0);
        at.setYaw(0);
        at.setPitch(0);
        return p.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0));
            d.setDefaultBackground(false);
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setLineWidth(4096);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(0.2f);
            d.setInterpolationDuration(0);
            d.setTeleportDuration(0);
            // nothing to see until it is put on the screen (the first tick)
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0, 0, 0), new Quaternionf()));
        });
    }

    private static Component glyphText(char c) {
        return Component.text(String.valueOf(c), NamedTextColor.WHITE).font(FONT);
    }

    /**
     * Opens a player's wheel. False when the displays cannot be carried on the player (no packet layer): the caller
     * opens the menu instead.
     */
    boolean open(Player p) {
        close(p);
        if (plugin.cosmetics() == null) return false;
        Open o = new Open(p, emotes.wheel(p));
        o.dial = spawn(p, glyphText(DIAL));
        o.dial.setTextOpacity((byte) 235);          // the world shows through the dial a little
        o.ring = spawn(p, glyphText(RING));
        o.pointer = spawn(p, glyphText(POINTER));
        o.label = spawn(p, Lang.mm("emote.hud-hint"));
        for (int k = 0; k < o.icons.length; k++) {
            o.icons[k] = spawn(p, glyphText(glyph(o.wheel[k] == null ? EmoteWheel.ICON_EMPTY : EmoteWheel.icon(o.wheel[k]))));
        }
        int[] ids = o.all().stream().mapToInt(TextDisplay::getEntityId).toArray();
        if (!plugin.cosmetics().carryAll(p, ids)) {
            o.all().forEach(TextDisplay::remove);
            plugin.cosmetics().carryAll(p, null);
            return false;
        }
        for (TextDisplay d : o.all()) p.showEntity(plugin, d);
        open.put(p.getUniqueId(), o);
        return true;
    }

    /** A display's place on the screen: a picture's middle at (x, y) pixels of the dial (y down), layer steps nearer, scale times its size. */
    private void place(Player p, TextDisplay d, double x, double y, int layer, double scale, int ticks) {
        double above = p.getHeight() - p.getEyeHeight();
        double[] t = onScreen(x * PIXEL, -y * PIXEL, AHEAD - layer * 0.0015, above, p.getLocation().getPitch());
        float s = (float) (PIXEL / TEXT_PX * scale);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(new Vector3f((float) t[0], (float) t[1], (float) t[2]), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
    }

    /** Every tick: the wheel follows the pitch, the look picks a place. */
    void tick() {
        if (open.isEmpty()) return;
        for (UUID id : new ArrayList<>(open.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            Open o = open.get(id);
            if (p == null || !p.isOnline() || p.isDead() || ++o.ticks > MAX_TICKS || o.dial.isDead()) {
                if (p != null) close(p);
                else open.remove(id);
                continue;
            }
            double turn = turned(o.yaw0, p.getLocation().getYaw()), raise = -(p.getLocation().getPitch() - o.pitch0);
            int picked = pick(turn, raise);
            int smooth = o.ticks <= 1 ? 3 : 1;          // popping up, then following the look
            place(p, o.dial, 0, 0, 0, 1, smooth);
            for (int k = 0; k < o.icons.length; k++) {
                place(p, o.icons[k], PLACES[k][0], PLACES[k][1], 2, k == picked ? 1.35 : 1.0, k == picked || k == o.picked ? 2 : smooth);
            }
            double[] at = pointer(turn, raise);
            place(p, o.pointer, at[0], at[1], 3, 1, 1);
            if (picked >= 0) place(p, o.ring, PLACES[picked][0], PLACES[picked][1], 1, 1, o.picked == picked ? smooth : 0);
            else o.ring.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0, 0, 0), new Quaternionf()));
            // the words under the dial: what letting go would play
            place(p, o.label, 0, DIAL_PX / 2.0 + 14, 1, 0.55 * TEXT_PX / PIXEL / 8, smooth);
            if (picked != o.picked) {
                EmoteSpec e = picked < 0 || o.wheel[picked] == null ? null : emotes.all().get(o.wheel[picked]);
                o.label.text(picked < 0 ? Lang.mm("emote.hud-hint") : e == null ? Lang.mm("emote.hud-empty") : Lang.mm("emote.hud-pick", "name", e.name()));
                if (picked >= 0) p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.25f, 1.8f);
                o.picked = picked;
            }
        }
    }

    /** The key was let go: the picked emote plays (none in the middle or on an empty place). Returns it, or null. */
    EmoteSpec release(Player p) {
        Open o = open.get(p.getUniqueId());
        if (o == null) return null;
        EmoteSpec e = o.picked < 0 || o.wheel[o.picked] == null ? null : emotes.all().get(o.wheel[o.picked]);
        close(p);
        return e;
    }

    void close(Player p) {
        Open o = open.remove(p.getUniqueId());
        if (o == null) return;
        if (plugin.cosmetics() != null) plugin.cosmetics().carryAll(p, null);
        o.all().forEach(TextDisplay::remove);
    }

    void closeAll() {
        for (UUID id : new ArrayList<>(open.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) close(p);
        }
        open.clear();
    }
}
