package dev.stsv.economy.road;

import dev.stsv.core.furniture.DisplayView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * The 국도 direction signs in the world ({@link RoadSign}): one item display for the model, a text display for each of its
 * words. All persistent (they stay with their chunk like a dock's models), invulnerable and without a hit box, tagged
 * {@link #TAG} with the dock's name and {@link #SET} with the road's set of signs ({@link RoadJob#signs}), so
 * /관리자 하역장 제거 and /관리자 국도 표지판 정리 find them, and ones whose chunk was not loaded then go when it loads. A
 * junction's guide sign ({@link #guide}: the wider model, an arrow model a line) also carries the other road's dock and set
 * ({@link #ALSO}): either road's clearing takes it.
 */
final class RoadSigns {

    /** On every entity of a sign: the dock's name. */
    static final NamespacedKey TAG = new NamespacedKey("economy", "road_sign");
    /** On every entity of a sign: the road's set of signs it belongs to. */
    static final NamespacedKey SET = new NamespacedKey("economy", "road_sign_set");
    /** On a junction's sign (RoadGuide) that stands by one road: the other road's dock and its set of signs. */
    static final NamespacedKey ALSO = new NamespacedKey("economy", "road_sign_also"), ALSO_SET = new NamespacedKey("economy", "road_sign_also_set");
    static final NamespacedKey MODEL = new NamespacedKey("economy", "model/road_sign");
    /** The model's scale (it is drawn at half size, models/roads.py) */
    static final float SCALE = 2f;
    /**
     * Culling boxes {width, height}: the model (the panel 3 wide at any yaw, from the road up to its top), a text display
     * (at most the panel's width, a line high).
     */
    static final float[] MODEL_BOX = {4.2f, 5.0f}, TEXT_BOX = {3.4f, 1.0f};

    private RoadSigns() {}

    /**
     * Whose a sign is: the dock whose road it stands by and its set of signs, and at a junction the other road's dock and
     * set (null: none). Either dock's sign clearing takes it away.
     */
    record Owner(String dock, int set, String also, int alsoSet) {
        Owner(String dock, int set) {
            this(dock, set, null, 0);
        }
    }

    /** A sign at (x, y, z) (between its posts, on the road's surface), its face towards yaw. An old one of the same dock there goes first. */
    static void place(World w, double x, double y, double z, float yaw, RoadSign.Words words, String dock, int set, DisplayView.Settings view) {
        place(w, x, y, z, yaw, words, new Owner(dock, set), view);
    }

    static void place(World w, double x, double y, double z, float yaw, RoadSign.Words words, Owner owner, DisplayView.Settings view) {
        clear(w, x, y, z, owner);
        model(w, x, y, z, yaw, MODEL, owner, view);
        for (RoadSign.Text t : RoadSign.texts(words)) text(w, x, y, z, yaw, t, owner, view);
    }

    /**
     * A guide sign (RoadGuide: at junctions): the wider panel, an arrow model on each line, the lines' words. An old one
     * of the same docks there goes first.
     */
    static void guide(World w, double x, double y, double z, float yaw, List<RoadSign.Row> rows, Owner owner, DisplayView.Settings view) {
        clear(w, x, y, z, owner);
        model(w, x, y, z, yaw, GUIDE, owner, view);
        for (RoadSign.ArrowAt a : RoadSign.guideArrows(rows)) {
            double[] o = RoadSign.offset(yaw, a.right(), a.out());
            ItemStack shown = new ItemStack(Material.PAPER);
            shown.editMeta(m -> m.setItemModel(new NamespacedKey("economy", "model/road_arrow_" + a.arrow().model)));
            float s = (float) (a.size() / ARROW_DRAWN);
            w.spawn(new Location(w, x + o[0], y + a.up(), z + o[1], yaw, 0), ItemDisplay.class, d -> {
                setUp(d, owner, view);
                d.setItemStack(shown);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            });
        }
        for (RoadSign.Text t : RoadSign.guideTexts(rows)) text(w, x, y, z, yaw, t, owner, view);
    }

    /** How big the arrow models are drawn (blocks across, models/roads.py): an arrow display is scaled to its size from this. */
    static final double ARROW_DRAWN = 1.5;

    /** A sign's entities of the same docks round (x, y, z) (it is put up again). */
    private static void clear(World w, double x, double y, double z, Owner owner) {
        for (Entity e : w.getNearbyEntities(new Location(w, x, y + 3, z), 2.5, 4, 2.5)) {
            String d = dock(e);
            if (d != null && (d.equals(owner.dock()) || d.equals(owner.also()))) e.remove();
        }
    }

    private static void model(World w, double x, double y, double z, float yaw, NamespacedKey model, Owner owner, DisplayView.Settings view) {
        ItemStack shown = new ItemStack(Material.PAPER);
        shown.editMeta(m -> m.setItemModel(model));
        w.spawn(new Location(w, x, y, z, yaw, 0), ItemDisplay.class, d -> {
            setUp(d, owner, view);
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            model(d);
        });
    }

    private static void text(World w, double x, double y, double z, float yaw, RoadSign.Text t, Owner owner, DisplayView.Settings view) {
        double[] o = RoadSign.offset(yaw, t.right(), t.out());
        w.spawn(new Location(w, x + o[0], y + t.up(), z + o[1], yaw, 0), TextDisplay.class, d -> {
            setUp(d, owner, view);
            d.text(text(t.text()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(1000);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setBrightness(new Display.Brightness(15, 15));   // white like reflective sheeting, also at night
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(t.scale(), t.scale(), t.scale()),
                    new Quaternionf()));
        });
    }

    /** How big words painted on the road are (a line about a block deep, "하역장 입구" about 5 wide). */
    static final float PAINT = 4f;

    /**
     * Words painted on the road (RoadSection: "하역장 입구" before a dock's gate): a text display lying on the surface at (x,
     * y, z), its top towards where the road goes (yaw: the way a sign there faces, back at the traffic), so a driver
     * coming reads it. Tagged like a sign (cleared with them). An old one of the same dock there goes first.
     */
    static void paint(World w, double x, double y, double z, float yaw, String words, String dock, int set, DisplayView.Settings view) {
        Location at = new Location(w, x, y, z, yaw, 0);
        for (Entity e : w.getNearbyEntities(at, 2, 1, 2)) if (e instanceof TextDisplay && dock.equals(dock(e))) e.remove();
        w.spawn(at, TextDisplay.class, d -> {
            setUp(d, new Owner(dock, set), view);
            d.text(Component.text(words, NamedTextColor.WHITE));
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            // its face up (turned back about its width): its top runs away from the traffic, along the road
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) (-Math.PI / 2)),
                    new Vector3f(PAINT, PAINT, PAINT), new Quaternionf()));
        });
    }

    /**
     * Where the road lies in the model (model px): models/roads.py draws the road's surface at y 0 and bakes it with
     * shift -16, scale 2 (model point = 8 + (drawn - 16 - 8) / 2), so the road is at model y -4.
     */
    static final float MODEL_ROAD_Y = -4f;
    /** The guide sign's model (economy:model/road_sign_guide, 2 blocks taller than the road: drawn shifted 24 down), its road's y. */
    static final NamespacedKey GUIDE = new NamespacedKey("economy", "model/road_sign_guide");
    static final float GUIDE_ROAD_Y = -8f;

    /**
     * The model's transformation: FIXED draws model (8, 8, 8) on the entity, scaled by {@link #SCALE} round it, so the
     * road (model y {@link #MODEL_ROAD_Y}) is (8 - MODEL_ROAD_Y) / 16 x SCALE = 1.5 below the entity unless lifted that much
     * (the guide sign's: 2). (0.51.0 lifted it 1.0: the panel stood half a block low and its words looked pushed up.) Also
     * mends signs placed then.
     */
    static void model(ItemDisplay d) {
        ItemStack it = d.getItemStack();
        boolean guide = it != null && it.hasItemMeta() && GUIDE.equals(it.getItemMeta().getItemModel());
        float lift = lift(guide);
        d.setTransformation(new Transformation(new Vector3f(0, lift, 0), new Quaternionf(), new Vector3f(SCALE, SCALE, SCALE),
                new Quaternionf()));
    }

    static float lift(boolean guide) {
        return (8f - (guide ? GUIDE_ROAD_Y : MODEL_ROAD_Y)) / 16f * SCALE;
    }

    /** White, digits bold ({@link RoadSign#bold}), the server's default font. */
    static Component text(String s) {
        var b = Component.text().color(NamedTextColor.WHITE);
        int i = 0;
        while (i < s.length()) {
            boolean bold = RoadSign.bold(s.charAt(i));
            int j = i;
            while (j < s.length() && RoadSign.bold(s.charAt(j)) == bold) j++;
            b.append(Component.text(s.substring(i, j)).decoration(TextDecoration.BOLD, bold));
            i = j;
        }
        return b.build();
    }

    private static void setUp(Display d, Owner owner, DisplayView.Settings view) {
        d.setPersistent(true);
        d.setInvulnerable(true);
        d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, owner.dock());
        d.getPersistentDataContainer().set(SET, PersistentDataType.INTEGER, owner.set());
        if (owner.also() != null) {
            d.getPersistentDataContainer().set(ALSO, PersistentDataType.STRING, owner.also());
            d.getPersistentDataContainer().set(ALSO_SET, PersistentDataType.INTEGER, owner.alsoSet());
        }
        view(d, view);
    }

    /** The other dock of a junction's sign (it goes with either dock's signs), or null. */
    static String also(Entity e) {
        return e.getPersistentDataContainer().get(ALSO, PersistentDataType.STRING);
    }

    static int alsoSet(Entity e) {
        Integer s = e.getPersistentDataContainer().get(ALSO_SET, PersistentDataType.INTEGER);
        return s == null ? 0 : s;
    }

    /** Its view range (display.view-distance.road-signs) and culling box. */
    static void view(Display d, DisplayView.Settings view) {
        DisplayView.apply(d, view.viewRange(DisplayView.Kind.ROAD_SIGN), d instanceof TextDisplay ? TEXT_BOX : MODEL_BOX, view.culling());
    }

    /** The dock of a sign's entity, or null (not a sign). */
    static String dock(Entity e) {
        return e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
    }

    static int set(Entity e) {
        Integer s = e.getPersistentDataContainer().get(SET, PersistentDataType.INTEGER);
        return s == null ? 0 : s;
    }

    /**
     * Removes a dock's signs (every entity of them, also those at its junctions that stand by another road) in the loaded
     * chunks of every world. How many entities went.
     */
    static int remove(String dock) {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Display d : w.getEntitiesByClass(Display.class)) {
                if (dock.equals(dock(d)) || dock.equals(also(d))) {
                    d.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
