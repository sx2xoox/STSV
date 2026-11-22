package dev.stsv.economy.secret.garage;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;
import dev.stsv.core.vehicle.VehicleSpec;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * His car as displays only (nobody can sit in it, nothing collides): the body, the four wheels, the glow under it and
 * the lit headlamps, each an item display with the same model, place and size the core's vehicle module gives a real
 * 튜닝카 ({@code VehicleModule#spawnParts}, {@code VehicleTuner}, {@code VehicleTuning}'s model names): the body in the
 * FIXED transform at {@code body-scale}, a wheel at each of the item's wheel places, the glow a flat model stretched to
 * the car, the lamps the body's frame twice as large. Only its viewer sees it ({@link Props}).
 */
final class CarLook {

    /** The glow's lift over the ground and its margin round the car, the lamp model's scale (VehicleTuner, VehicleFeelView). */
    private static final float GLOW_LIFT = 0.03f, GLOW_MARGIN = 0.9f, LAMP_SCALE = 2f;

    /** One shown car. */
    record Car(String group, ItemDisplay body, List<ItemDisplay> wheels, List<Vector3f> wheelAt, ItemDisplay[] glow, ItemDisplay[] lamp,
               ItemStack bodyItem) {}

    private final SecretModule m;
    private final Props props;
    private YamlConfiguration cfg = new YamlConfiguration();
    private boolean warned;

    CarLook(SecretModule m, Props props) {
        this.m = m;
        this.props = props;
    }

    void settings(YamlConfiguration cfg) {
        this.cfg = cfg;
        warned = false;
    }

    /** The car's item id ("stsv:tuner_car"). */
    String itemId() {
        return cfg.getString("car.item", "stsv:tuner_car");
    }

    /** The core's item (null when it is not there). */
    CustomItem item() {
        CustomItem it = m.core().content().item(itemId()).filter(CustomItem::isVehicle).orElse(null);
        if (it == null && !warned) {
            warned = true;
            m.core().getLogger().warning("[비밀] 폐차고: 탈것 아이템 " + itemId() + " 이(가) 없어 차를 보여 줄 수 없음");
        }
        return it;
    }

    private static ItemStack model(NamespacedKey key) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(meta -> meta.setItemModel(key));
        return it;
    }

    private static NamespacedKey key(CustomItem item, String name) {
        return new NamespacedKey(item.key().getNamespace(), "model/" + name);
    }

    private String bodyName(CustomItem item) {
        return item.model() != null && item.model().kind() == ModelRef.Kind.BBMODEL ? item.model().value() : cfg.getString("car.body", "tuner_car");
    }

    private String wheelName(VehicleSpec spec) {
        NamespacedKey k = spec.wheelModel();
        if (k == null) return cfg.getString("car.wheel", "wheel_tuner");
        return k.getKey().startsWith("model/") ? k.getKey().substring(6) : k.getKey();
    }

    /** The model keys a look is drawn with (for the admin: 정답). */
    List<String> keys(Combo look) {
        CustomItem item = item();
        List<String> out = new ArrayList<>();
        if (item == null || look == null) return out;
        out.add(look.paint() == null ? item.key().asString() : key(item, bodyName(item) + "_p_" + look.paint()).asString());
        out.add(key(item, look.wheel() == null ? wheelName(item.vehicle()) : wheelName(item.vehicle()) + "_w_" + look.wheel()).asString());
        if (look.glow() != null) out.add(key(item, "underglow_" + look.glow()).asString());
        return out;
    }

    private float yawOffset() {
        return (float) m.core().getConfig().getDouble("furniture.display-yaw-offset");
    }

    private Location shown(Location at) {
        Location l = at.clone();
        l.setYaw(at.getYaw() + yawOffset());
        l.setPitch(0);
        return l;
    }

    /**
     * The car standing at {@code at} (its ground centre; the yaw is the way it faces) in this look (a part not chosen:
     * as the car comes), for this viewer.
     *
     * @param lamps  its headlamps lit
     * @param bright the light its body is drawn in (0..15; below 0: the world's own)
     * @param glide  ticks the client glides it over when it is moved (0: it stands)
     */
    Car spawn(Player viewer, String group, Location at, Combo look, boolean lamps, int bright, int glide) {
        CustomItem item = item();
        if (item == null) return null;
        VehicleSpec spec = item.vehicle();
        Location l = shown(at);
        Display.Brightness light = bright < 0 ? null : new Display.Brightness(bright, bright);
        float scale = spec.bodyScale();
        ItemStack bodyItem = look == null || look.paint() == null ? item.create(1) : model(key(item, bodyName(item) + "_p_" + look.paint()));
        ItemDisplay body = part(viewer, group, l, bodyItem, new Vector3f(), new Vector3f(scale, scale, scale), light, glide);
        ItemStack wheel = model(key(item, look == null || look.wheel() == null ? wheelName(spec) : wheelName(spec) + "_w_" + look.wheel()));
        List<ItemDisplay> wheels = new ArrayList<>();
        List<Vector3f> wheelAt = new ArrayList<>();
        for (VehicleSpec.Wheel w : spec.wheels()) {
            // the display's own frame: +z forward, +x to the car's left (VehicleModule#local)
            Vector3f t = new Vector3f((float) -w.at().right(), (float) w.at().up(), (float) w.at().forward());
            wheelAt.add(t);
            wheels.add(part(viewer, group, l, wheel, t, new Vector3f(1, 1, 1), light, glide));
        }
        Car car = new Car(group, body, wheels, wheelAt, new ItemDisplay[1], new ItemDisplay[1], bodyItem);
        if (look != null && look.glow() != null) {
            car.glow()[0] = part(viewer, group, l, model(key(item, "underglow_" + look.glow())), new Vector3f(0, GLOW_LIFT, 0),
                    new Vector3f((float) spec.width() + GLOW_MARGIN, 1, (float) spec.length() + GLOW_MARGIN), new Display.Brightness(15, 15), glide);
        }
        if (lamps) car.lamp()[0] = lamp(viewer, group, l, item, spec, glide);
        return car;
    }

    private ItemDisplay lamp(Player viewer, String group, Location shownAt, CustomItem item, VehicleSpec spec, int glide) {
        float s = spec.bodyScale() * LAMP_SCALE;
        return part(viewer, group, shownAt, model(key(item, cfg.getString("car.lamp", "tuner_car_lamp_head"))), new Vector3f(),
                new Vector3f(s, s, s), new Display.Brightness(15, 15), glide);
    }

    /** Lit headlamps alone, over a car standing at {@code at} (the real one waiting at the bend, whose own are off while it is empty). */
    ItemDisplay lampsOver(Player viewer, String group, Location at) {
        CustomItem item = item();
        return item == null ? null : lamp(viewer, group, shown(at), item, item.vehicle(), 0);
    }

    private ItemDisplay part(Player viewer, String group, Location l, ItemStack it, Vector3f at, Vector3f scale, Display.Brightness light, int glide) {
        ItemDisplay d = props.item(viewer, group, l, it, at, scale, null);
        if (light != null) d.setBrightness(light);
        d.setTeleportDuration(Math.clamp(glide, 0, 10));
        d.setViewRange(2.5f);
        return d;
    }

    private static List<ItemDisplay> parts(Car car) {
        List<ItemDisplay> out = new ArrayList<>(car.wheels());
        out.add(car.body());
        if (car.glow()[0] != null) out.add(car.glow()[0]);
        if (car.lamp()[0] != null) out.add(car.lamp()[0]);
        return out;
    }

    /** Moves it (the client glides it there). */
    void move(Car car, Location at) {
        Location l = shown(at);
        for (ItemDisplay d : parts(car)) if (d.isValid()) d.teleport(l);
    }

    /** Its wheels turned to this roll (radians), gliding there over two ticks. */
    void roll(Car car, double roll) {
        Quaternionf spin = new Quaternionf().rotateX((float) (roll % (Math.PI * 4)));
        for (int i = 0; i < car.wheels().size(); i++) {
            ItemDisplay d = car.wheels().get(i);
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(car.wheelAt().get(i), new Quaternionf(), new Vector3f(1, 1, 1), spin));
        }
    }

    /** Its lights go out: the headlamps and the glow under it. */
    void dark(UUID viewer, Car car) {
        if (car == null) return;
        props.remove(viewer, car.group(), car.lamp()[0]);
        props.remove(viewer, car.group(), car.glow()[0]);
        car.lamp()[0] = null;
        car.glow()[0] = null;
    }

    /** Its body there or not for a moment (a flicker). */
    void flicker(Car car, boolean there) {
        if (car != null && car.body().isValid()) car.body().setItemStack(there ? car.bodyItem() : ItemStack.empty());
    }
}
