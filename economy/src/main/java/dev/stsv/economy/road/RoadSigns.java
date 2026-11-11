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

/**
 * The 국도 direction signs in the world ({@link RoadSign}): one item display for the model, a text display for each of its
 * words. All persistent (they stay with their chunk like a dock's models), invulnerable and without a hit box, tagged
 * {@link #TAG} with the dock's name and {@link #SET} with the road's set of signs ({@link RoadJob#signs}), so
 * /관리자 하역장 제거 and /관리자 국도 표지판 정리 find them, and ones whose chunk was not loaded then go when it loads.
 */
final class RoadSigns {

    /** On every entity of a sign: the dock's name. */
    static final NamespacedKey TAG = new NamespacedKey("economy", "road_sign");
    /** On every entity of a sign: the road's set of signs it belongs to. */
    static final NamespacedKey SET = new NamespacedKey("economy", "road_sign_set");
    static final NamespacedKey MODEL = new NamespacedKey("economy", "model/road_sign");
    /** The model's scale (it is drawn at half size, models/roads.py) */
    static final float SCALE = 2f;
    /**
     * Culling boxes {width, height}: the model (the panel 3 wide at any yaw, from the road up to its top), a text display
     * (at most the panel's width, a line high).
     */
    static final float[] MODEL_BOX = {4.2f, 5.0f}, TEXT_BOX = {3.4f, 1.0f};

    private RoadSigns() {}

    /** A sign at (x, y, z) (between its posts, on the road's surface), its face towards yaw. An old one of the same dock there goes first. */
    static void place(World w, double x, double y, double z, float yaw, RoadSign.Words words, String dock, int set, DisplayView.Settings view) {
        Location at = new Location(w, x, y, z, yaw, 0);
        for (Entity e : w.getNearbyEntities(at.clone().add(0, 3, 0), 2.5, 4, 2.5)) if (dock.equals(dock(e))) e.remove();   // built again
        ItemStack shown = new ItemStack(Material.PAPER);
        shown.editMeta(m -> m.setItemModel(MODEL));
        w.spawn(at, ItemDisplay.class, d -> {
            setUp(d, dock, set, view);
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // the model's floor (model y 0) on the point: FIXED draws model (8, 8, 8) at the entity
            d.setTransformation(new Transformation(new Vector3f(0, 0.5f * SCALE, 0), new Quaternionf(), new Vector3f(SCALE, SCALE, SCALE),
                    new Quaternionf()));
        });
        for (RoadSign.Text t : RoadSign.texts(words)) {
            double[] o = RoadSign.offset(yaw, t.right(), t.out());
            w.spawn(new Location(w, x + o[0], y + t.up(), z + o[1], yaw, 0), TextDisplay.class, d -> {
                setUp(d, dock, set, view);
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

    private static void setUp(Display d, String dock, int set, DisplayView.Settings view) {
        d.setPersistent(true);
        d.setInvulnerable(true);
        d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, dock);
        d.getPersistentDataContainer().set(SET, PersistentDataType.INTEGER, set);
        view(d, view);
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

    /** Removes a dock's signs (every entity of them) in the loaded chunks of every world. How many entities went. */
    static int remove(String dock) {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Display d : w.getEntitiesByClass(Display.class)) {
                if (dock.equals(dock(d))) {
                    d.remove();
                    n++;
                }
            }
        }
        return n;
    }
}
