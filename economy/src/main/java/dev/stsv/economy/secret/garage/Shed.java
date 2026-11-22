package dev.stsv.economy.secret.garage;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * His garage, there for one viewer: a small concrete box at the roadside with a rusted roll-up shutter and a neon sign
 * that is dead (lit once the story ended), all block displays in one frame — the middle of the front on the floor,
 * facing the road (+z out of the door, +x to the left of someone looking out). Nothing of it stops anyone.
 * <p>
 * Plan (blocks): 7 wide (x -3.5..3.5), 7 deep (z -7..0), 3.6 high, the door 5 wide and 3 high in the front; walls go 2
 * under the floor so uneven ground shows no gap. Inside: the car in the middle (under a tarp, or as it is being
 * restored, or gone), a workbench in the back left corner (a click box).
 */
final class Shed {

    static final String GROUP = "shed", INSIDE = "shed-in";
    /** What is inside: the car under its tarp, nothing, or the car in a look ({@link Combo#encode}). */
    static final String TARP = "tarp", EMPTY = "empty";
    private static final float DOOR = 3.0f, SLAT = 0.5f, CAR_Z = -3.6f;
    private static final int SLATS = 6;

    private static final class View {
        Location origin;
        final List<BlockDisplay> slats = new ArrayList<>();
        TextDisplay sign;
        boolean lit;
        double open = -1;
        String inside = "";
    }

    private final Props props;
    private final CarLook cars;
    private final Map<UUID, View> views = new HashMap<>();
    private YamlConfiguration cfg = new YamlConfiguration();

    Shed(Props props, CarLook cars) {
        this.props = props;
        this.cars = cars;
    }

    void settings(YamlConfiguration cfg) {
        this.cfg = cfg;
    }

    private BlockData block(String key, Material def) {
        Material mat = Material.matchMaterial(cfg.getString("garage.blocks." + key, def.name()));
        return (mat == null || !mat.isBlock() ? def : mat).createBlockData();
    }

    boolean showing(UUID u) {
        return views.containsKey(u) && props.has(u, GROUP);
    }

    Location origin(UUID u) {
        View v = views.get(u);
        return v == null ? null : v.origin.clone();
    }

    /** How far the shutter is up for this viewer (0 shut .. 1), -1 when the garage is not shown. */
    double open(UUID u) {
        View v = views.get(u);
        return v == null ? -1 : v.open;
    }

    /** Where the car stands inside (its ground centre, facing out of the door). */
    static Location carSpot(Location origin) {
        return Props.local(origin, 0, 0.06, CAR_Z);
    }

    /** The garage for this viewer as it should be now: built if it is not there, else only what differs is changed. */
    void show(Player p, Location origin, boolean lit, double open, String inside) {
        UUID u = p.getUniqueId();
        View v = views.get(u);
        if (v != null && (!props.has(u, GROUP) || v.origin.distanceSquared(origin) > 0.01 || v.origin.getWorld() != origin.getWorld())) {
            hide(u);
            v = null;
        }
        if (v == null) {
            v = build(p, origin, lit);
            views.put(u, v);
            shutter(p, open, 0);
        } else if (v.lit != lit) {
            v.lit = lit;
            sign(v);
        }
        if (Math.abs(v.open - open) > 1e-3) shutter(p, open, 0);
        inside(p, inside);
    }

    private View build(Player p, Location o, boolean lit) {
        View v = new View();
        v.origin = o.clone();
        v.lit = lit;
        BlockData wall = block("wall", Material.LIGHT_GRAY_CONCRETE), trim = block("trim", Material.GRAY_CONCRETE),
                roof = block("roof", Material.STONE), floor = block("floor", Material.SMOOTH_STONE), board = block("sign-board", Material.BLACK_CONCRETE);
        props.box(p, GROUP, o, floor, -3.5f, -0.04f, -7f, 7f, 0.1f, 7f);
        props.box(p, GROUP, o, floor, -2.6f, -0.05f, 0f, 5.2f, 0.09f, 1.6f);            // the apron before the door
        props.box(p, GROUP, o, wall, -3.5f, -2f, -7f, 0.3f, 5.6f, 7f);                   // left
        props.box(p, GROUP, o, wall, 3.2f, -2f, -7f, 0.3f, 5.6f, 7f);                    // right
        props.box(p, GROUP, o, wall, -3.5f, -2f, -7f, 7f, 5.6f, 0.3f);                   // back
        props.box(p, GROUP, o, trim, -3.5f, -2f, -0.35f, 1f, 5.6f, 0.35f);               // the door's posts
        props.box(p, GROUP, o, trim, 2.5f, -2f, -0.35f, 1f, 5.6f, 0.35f);
        props.box(p, GROUP, o, trim, -2.5f, DOOR, -0.35f, 5f, 0.6f, 0.35f);              // the lintel
        props.box(p, GROUP, o, roof, -3.7f, 3.6f, -7.2f, 7.4f, 0.25f, 7.5f);
        props.box(p, GROUP, o, board, -2.1f, 3.9f, -0.3f, 4.2f, 0.8f, 0.12f);            // the sign's board over the door
        props.box(p, GROUP, o, block("bench", Material.SMITHING_TABLE), -3.1f, 0.05f, -6.6f, 1f, 1f, 1f);
        props.box(p, GROUP, o, block("shelf", Material.BARREL), -3.1f, 0.05f, -5.5f, 0.8f, 0.8f, 0.8f);
        props.hit(p, GROUP, Props.local(o, -2.6, 0.05, -6.1), 1.3f, 1.3f, "bench");
        List<String> slatBlocks = cfg.getStringList("garage.blocks.shutter");
        for (int i = 0; i < SLATS; i++) {
            Material mat = slatBlocks.isEmpty() ? null : Material.matchMaterial(slatBlocks.get(i % slatBlocks.size()));
            if (mat == null || !mat.isBlock()) mat = i % 3 == 1 ? Material.RAW_IRON_BLOCK : Material.IRON_BLOCK;
            v.slats.add(props.box(p, GROUP, o, mat.createBlockData(), -2.5f, i * SLAT, -0.22f, 5f, SLAT, 0.1f));
        }
        v.sign = props.text(p, GROUP, Props.local(o, 0, 4.12, -0.16), "", false, 1.5f);
        sign(v);
        return v;
    }

    private void sign(View v) {
        if (v.sign == null || !v.sign.isValid()) return;
        String name = cfg.getString("garage.sign", "도윤 모터스");
        v.sign.text(dev.stsv.core.util.Text.mm((v.lit ? cfg.getString("garage.sign-lit", "<#ff5fa8>") : cfg.getString("garage.sign-dead", "<#3b3b40>")) + name));
        v.sign.setBrightness(v.lit ? new Display.Brightness(15, 15) : new Display.Brightness(2, 2));
    }

    /** The shutter goes to this height (0 shut .. 1 rolled all the way up) over this many ticks, with its rattle. */
    void shutter(Player p, double open, int ticks) {
        View v = views.get(p.getUniqueId());
        if (v == null) return;
        boolean moved = v.open >= 0 && Math.abs(v.open - open) > 1e-3;
        v.open = Math.clamp(open, 0, 1);
        for (int i = 0; i < v.slats.size(); i++) {
            BlockDisplay d = v.slats.get(i);
            if (!d.isValid()) continue;
            float y = (float) Math.min(DOOR, i * SLAT + v.open * DOOR), h = Math.clamp(DOOR - y, 0f, SLAT);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(Math.max(0, ticks));
            d.setTransformation(new Transformation(new Vector3f(-2.5f, y, -0.22f), new Quaternionf(), new Vector3f(5f, h, 0.1f), new Quaternionf()));
        }
        if (moved && ticks > 0) {
            Location at = Props.local(v.origin, 0, 1.5, 0);
            p.playSound(at, Sound.BLOCK_IRON_DOOR_OPEN, SoundCategory.AMBIENT, 0.9f, 0.5f);
            p.playSound(at, Sound.BLOCK_CHAIN_BREAK, SoundCategory.AMBIENT, 0.8f, 0.6f);
        }
    }

    /** What is inside for this viewer. */
    void inside(Player p, String inside) {
        UUID u = p.getUniqueId();
        View v = views.get(u);
        if (v == null || (v.inside.equals(inside) && (inside.equals(EMPTY) || props.has(u, INSIDE)))) return;
        props.drop(u, INSIDE);
        v.inside = inside;
        Location o = v.origin;
        if (inside.equals(EMPTY)) return;
        if (inside.equals(TARP)) {
            BlockData tarp = block("tarp", Material.LIGHT_GRAY_WOOL), fold = block("tarp-fold", Material.GRAY_WOOL);
            props.box(p, INSIDE, o, fold, -1.3f, 0.05f, CAR_Z - 2.6f, 2.6f, 0.08f, 5.2f);     // its hem on the floor
            props.box(p, INSIDE, o, tarp, -1.15f, 0.1f, CAR_Z - 2.4f, 2.3f, 0.85f, 4.8f);     // over the body
            props.box(p, INSIDE, o, tarp, -0.95f, 0.95f, CAR_Z - 1.5f, 1.9f, 0.45f, 2.3f);    // over the roof
            props.box(p, INSIDE, o, fold, -1.0f, 0.93f, CAR_Z + 0.8f, 2.0f, 0.06f, 0.5f);     // a fold on the bonnet
            return;
        }
        cars.spawn(p, INSIDE, carSpot(o), Combo.decode(inside), false, -1, 0);
    }

    void hide(UUID u) {
        views.remove(u);
        props.drop(u, GROUP);
        props.drop(u, INSIDE);
    }
}
