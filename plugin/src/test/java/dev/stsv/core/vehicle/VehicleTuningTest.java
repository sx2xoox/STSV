package dev.stsv.core.vehicle;

import dev.stsv.core.vehicle.VehicleTuning.Catalogue;
import dev.stsv.core.vehicle.VehicleTuning.Custom;
import dev.stsv.core.vehicle.VehicleTuning.Kind;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tuning shop's catalogue and a vehicle's look (VehicleTuning): the rules, and the shipped vehicle_tuning.yml. */
class VehicleTuningTest {

    private static final Path PACK = Path.of("..", "content", "stsv");

    @Test
    void aLookIsOneOptionAKind() {
        Custom c = Custom.NONE.with(Kind.WHEEL, "turbine").with(Kind.PAINT, "wine");
        assertEquals("paint=wine;wheel=turbine", c.encode(), "the kinds in their own order, whatever order they were chosen in");
        assertEquals(c, Custom.decode(c.encode()));
        assertEquals("wine", c.get(Kind.PAINT));
        assertNull(c.get(Kind.SKID), "nothing chosen: the stock look");
        assertEquals(Custom.NONE, c.with(Kind.PAINT, null).with(Kind.WHEEL, null));
        assertEquals("", Custom.NONE.encode());
        assertEquals(Custom.NONE, Custom.decode(null));
        assertEquals(Custom.NONE, Custom.decode("  "));
        // whatever cannot be read is left out, the rest stays
        assertEquals("paint=red", Custom.decode("paint=red;nonsense;wing=big;wheel=;=x;skid=Bad Id").encode());
        assertEquals("paint=red", Custom.decode("paints=red").encode(), "the file's word for a kind is understood too");
        assertEquals(Kind.UNDERGLOW, Kind.of("underglow"));
        assertNull(Kind.of("spoiler"));
    }

    @Test
    void modelNames() {
        assertEquals("grand_sedan_p_wine", VehicleTuning.painted("grand_sedan", "wine"));
        assertEquals("grand_sedan_p_wine_door_fl", VehicleTuning.part("grand_sedan_door_fl", "grand_sedan", "wine"));
        assertEquals("police_lightbar_off", VehicleTuning.part("police_lightbar_off", "police_car", "red"), "not a part of that body: as it is");
        assertEquals("wheel_grand_w_turbine", VehicleTuning.wheel("wheel_grand", "turbine"));
        assertEquals("hyper_car_lamp_head_c_ice", VehicleTuning.headlamp("hyper_car_lamp_head", "ice"));
        assertEquals("underglow_cyan", VehicleTuning.underglow("cyan"));
        // and back: what a variant is another look of
        assertEquals("grand_sedan", VehicleTuning.stock("grand_sedan_p_wine"));
        assertEquals("grand_sedan_door_fl", VehicleTuning.stock("grand_sedan_p_wine_door_fl"));
        assertEquals("wheel_grand", VehicleTuning.stock("wheel_grand_w_turbine"));
        assertEquals("hyper_car_lamp_head", VehicleTuning.stock("hyper_car_lamp_head_c_ice"));
        for (String plain : List.of("grand_sedan", "wheel_grand", "police_car_lamp_head", "underglow_cyan", "test_sword"))
            assertEquals(plain, VehicleTuning.stock(plain));
    }

    private static VehicleTuning.Content content(Set<String> models, Map<String, VehicleTuning.Facts> vehicles, Set<String> skids) {
        return new VehicleTuning.Content() {
            @Override
            public VehicleTuning.Facts vehicle(String item) {
                return vehicles.get(item);
            }

            @Override
            public boolean model(String name) {
                return models.contains(name);
            }

            @Override
            public boolean skid(String id) {
                return skids.contains(id);
            }
        };
    }

    @Test
    void theFileOffersOnlyWhatAVehicleCanShow() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                paints:
                  red: { name: "레드", color: "#ff0000", price: 100 }
                  blue: { name: "블루", color: "#0000ff", price: 200 }
                  Bad: { name: "x", price: 1 }
                wheels:
                  gold: { name: "골드", price: 300 }
                skids:
                  neon: { name: "네온", price: 50 }
                  none: { name: "없는 것", price: 50 }
                smokes:
                  pink: { name: "핑크", color: "#ff66cc", price: 70 }
                  clear: { name: "색 없음", price: 70 }
                flames:
                  soul: { name: "파랑", particle: soul, price: 90 }
                  odd: { name: "?", particle: lava, price: 90 }
                crackles:
                  pop: { name: "팝콘", pops: 3, gap: [2, 5], price: 120 }
                plates:
                  red: { name: "레드", color: "#c81e1e", price: 30 }
                  cheap: { name: "값이 음수", color: "#c81e1e", price: -1 }
                vehicles:
                  car: { paints: all, wheels: all, skids: all, smokes: all, flames: all, crackles: all, plates: all }
                  truck: { paints: [blue, red, green], wheels: [gold], skids: [neon], plates: all }
                  ghost: { paints: all }
                """);
        List<String> problems = new ArrayList<>();
        Catalogue c = VehicleTuning.parse(y, content(
                Set.of("car_p_red", "car_p_blue", "truck_p_red", "wheel_car_w_gold"),
                Map.of("car", new VehicleTuning.Facts("car", "wheel_car", "car_lamp_head", true, true, true),
                        "truck", new VehicleTuning.Facts("truck", "wheel_truck", null, false, false, false)),
                Set.of("neon")), problems::add);

        assertEquals(List.of(Kind.PAINT, Kind.WHEEL, Kind.SKID, Kind.SMOKE, Kind.FLAME, Kind.CRACKLE, Kind.PLATE), c.kinds("car"));
        assertEquals(List.of("red", "blue"), c.offered("car", Kind.PAINT).stream().map(VehicleTuning.Option::id).toList(), "the catalogue's order");
        assertEquals(List.of("neon"), c.offered("car", Kind.SKID).stream().map(VehicleTuning.Option::id).toList(), "a style the feel file has not: out");
        assertEquals(3, c.option(Kind.CRACKLE, "pop").pops());
        assertEquals(0xff66cc, c.option(Kind.SMOKE, "pink").color());
        assertNull(c.option(Kind.SMOKE, "clear"), "smoke without a colour is nothing");
        assertNull(c.option(Kind.FLAME, "odd"));
        assertNull(c.option(Kind.PLATE, "cheap"));
        assertNull(c.option(Kind.PAINT, "Bad"));
        // the truck: only the red body was drawn, no wheel of that design at its size, it leaves no marks, it has no plates
        assertEquals(List.of(Kind.PAINT), c.kinds("truck"));
        assertEquals(List.of("red"), c.offered("truck", Kind.PAINT).stream().map(VehicleTuning.Option::id).toList());
        assertTrue(c.kinds("ghost").isEmpty(), "no such vehicle");
        assertTrue(c.offers("car", Kind.PAINT, "red") && !c.offers("truck", Kind.PAINT, "blue") && !c.offers("car", Kind.PAINT, null));
        // what was asked for by name and cannot be had is said; "all" leaves it out quietly
        String log = String.join("\n", problems);
        for (String word : List.of("paints.Bad", "smokes.clear", "flames.odd", "plates.cheap", "vehicles.ghost", "vehicles.truck.paints: blue", "모르는 항목 green",
                "vehicles.truck.wheels", "vehicles.truck.skids")) {
            assertTrue(log.contains(word), word + " in:\n" + log);
        }
        assertFalse(log.contains("vehicles.car"), log);
        assertFalse(log.contains("vehicles.truck.plates"), "all: quietly nothing\n" + log);

        // the price: what is new is paid for; what stays and going back to stock is free
        Custom none = Custom.NONE, red = none.with(Kind.PAINT, "red"), redGold = red.with(Kind.WHEEL, "gold");
        assertEquals(100, c.price(none, red));
        assertEquals(400, c.price(none, redGold));
        assertEquals(300, c.price(red, redGold));
        assertEquals(0, c.price(redGold, red), "the wheels back to stock");
        assertEquals(200, c.price(redGold, redGold.with(Kind.PAINT, "blue")), "another paint is paid again");
        assertEquals(0, c.price(redGold, redGold));
        // an option taken out of the file since: that kind is stock again, the rest stays
        assertEquals("paint=red", c.valid("truck", Custom.decode("paint=red;wheel=gold;skid=neon")).encode());
        assertEquals(Custom.NONE, c.valid("ghost", redGold));
    }

    /** The shipped file against the shipped vehicles, models and skid mark styles: nothing in it is left out. */
    @Test
    void theShippedCatalogue() {
        Map<String, VehicleTuning.Facts> facts = new java.util.HashMap<>();
        VehicleFeel.File feel = VehicleFeel.parse(YamlConfiguration.loadConfiguration(PACK.resolve("vehicle_feel.yml").toFile()), "stsv",
                m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")), p -> { });
        for (String file : List.of("vehicles.yml", "vehicles_road.yml", "vehicles_farm.yml", "vehicles_misc.yml")) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/" + file).toFile());
            for (String id : y.getKeys(false)) {
                ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
                if (s == null) continue;
                VehicleSpec v = VehicleSpec.parse(s, "stsv", m -> true);
                VehicleFeel.Spec f = feel.spec("stsv:" + id, v.pulled() ? VehicleFeel.Power.MUSCLE : VehicleFeel.Power.ENGINE);
                facts.put(id, new VehicleTuning.Facts(y.getString(id + ".model"), v.wheelModel() == null ? null : v.wheelModel().getKey().substring(6),
                        f.lamps().head() == null ? null : f.lamps().head().getKey().substring(6), f.sport() != null && f.sport().mark() != null,
                        f.sport() != null, !v.extras().dress().plates().isEmpty()));
            }
        }
        List<String> problems = new ArrayList<>();
        Catalogue c = VehicleTuning.parse(YamlConfiguration.loadConfiguration(PACK.resolve("vehicle_tuning.yml").toFile()), new VehicleTuning.Content() {
            @Override
            public VehicleTuning.Facts vehicle(String item) {
                return facts.get(item);
            }

            @Override
            public boolean model(String name) {
                return Files.exists(PACK.resolve("models/" + name + ".bbmodel"));
            }

            @Override
            public boolean skid(String id) {
                return feel.marks().containsKey(id);
            }
        }, problems::add);
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        // every kind has options, each with a name and a price that is no gift
        for (Kind kind : Kind.values()) {
            assertFalse(c.options().getOrDefault(kind, Map.of()).isEmpty(), kind + " has options");
            for (VehicleTuning.Option o : c.options().get(kind).values()) {
                assertFalse(o.name().isBlank() || o.name().equals(o.id()), kind + " " + o.id() + ": a name");
                assertTrue(o.price() >= 10_000 && o.price() <= 500_000, kind + " " + o.id() + ": " + o.price());
                if (kind != Kind.CRACKLE) assertTrue(o.color() >= 0, kind + " " + o.id() + ": a swatch");
            }
        }
        // every vehicle with an engine or pedals can be painted in at least ten colours; the wooden ones are not in the shop
        for (String id : List.of("cargo_bicycle", "delivery_scooter", "atv", "cargo_van", "box_truck", "power_tiller", "tractor_trailer", "police_car",
                "light_truck", "golf_cart", "sports_car", "tuner_car", "hyper_car", "grand_sedan", "luxury_suv")) {
            assertTrue(c.offered(id, Kind.PAINT).size() >= 10, id + ": " + c.offered(id, Kind.PAINT).size() + " paints");
        }
        for (String id : List.of("hand_cart", "ox_cart", "fishing_boat")) assertTrue(c.kinds(id).isEmpty(), id);
        // a body is not offered in the colour it has already (that model is not drawn)
        assertFalse(c.offers("grand_sedan", Kind.PAINT, "pearl"));
        assertFalse(c.offers("sports_car", Kind.PAINT, "red"));
        assertTrue(c.offers("sports_car", Kind.PAINT, "pearl") && c.offers("grand_sedan", Kind.PAINT, "red"));
        // the event and the premium cars take everything they have the parts for
        for (String id : List.of("sports_car", "tuner_car")) {
            assertEquals(List.of(Kind.PAINT, Kind.WHEEL, Kind.UNDERGLOW, Kind.HEADLAMP, Kind.SKID, Kind.SMOKE, Kind.FLAME, Kind.CRACKLE), c.kinds(id), id);
        }
        assertEquals(List.of(Kind.values()), c.kinds("hyper_car"), "the hyper car: every kind there is");
        for (String id : List.of("grand_sedan", "luxury_suv")) {
            assertEquals(List.of(Kind.PAINT, Kind.WHEEL, Kind.UNDERGLOW, Kind.HEADLAMP, Kind.PLATE), c.kinds(id), id + ": no drift, no flames");
        }
        assertEquals(7, c.offered("luxury_suv", Kind.WHEEL).size(), "every wheel design at the monster wheels' size too");
        // the painted bodies of the cars with doors come with painted doors
        for (String part : List.of("grand_sedan_p_wine_door_fl", "luxury_suv_p_red_door_rr", "hyper_car_p_white_wing")) {
            assertTrue(Files.exists(PACK.resolve("models/" + part + ".bbmodel")), part);
        }
        // a plain vehicle: paint, and a glow if it has an engine and wheels on the road
        assertEquals(List.of(Kind.PAINT), c.kinds("cargo_bicycle"));
        assertEquals(List.of(Kind.PAINT, Kind.UNDERGLOW), c.kinds("light_truck"));
    }

    /** The shop's screen is the pack's own menu: a tab a kind (with its dimmed twin), a list, pay and undo. */
    @Test
    void theMenu() throws Exception {
        YamlConfiguration menu = YamlConfiguration.loadConfiguration(PACK.resolve("menus/tuning.yml").toFile());
        assertEquals("stsv:gui/tuning", menu.getString("tuning.background"), "drawn by the pack, not a plain chest");
        assertEquals(6, menu.getInt("tuning.rows"));
        String layout = String.join("", menu.getStringList("tuning.layout"));
        String tabs = menu.getString("tuning.tabs");
        assertEquals(Kind.values().length, tabs.length(), "a tab a kind");
        java.util.Set<String> actions = new java.util.HashSet<>();
        for (String key : menu.getConfigurationSection("tuning.buttons").getKeys(false)) {
            assertTrue(layout.contains(key), key + " is in the layout");
            actions.add(menu.getString("tuning.buttons." + key + ".action"));
            String icon = menu.getString("tuning.buttons." + key + ".icon");
            assertTrue(icon != null && Files.exists(PACK.resolve("assets/stsv/textures/item/" + icon.substring(5) + ".png")), key + ": " + icon);
        }
        for (Kind kind : Kind.values()) {
            assertTrue(actions.contains("tuning:kind:" + kind.word()), kind + " has its tab");
            for (String twin : List.of("", "_off")) {
                assertTrue(Files.exists(PACK.resolve("assets/stsv/textures/item/gui/tune_" + kind.word() + twin + ".png")), kind + twin + " icon");
                assertTrue(Files.exists(PACK.resolve("assets/stsv/items/gui/tune_" + kind.word() + twin + ".json")), kind + twin + " item definition");
            }
        }
        assertTrue(actions.containsAll(List.of("tuning:pay", "tuning:undo", "close", "page:prev", "page:next")));
        for (String off : List.of("coins_off", "clear_off")) assertTrue(Files.exists(PACK.resolve("assets/stsv/textures/item/gui/" + off + ".png")), off);
        assertEquals(36, layout.chars().filter(c -> c == menu.getString("tuning.list").charAt(0)).count(), "four rows of options");
        // the words the screen and the chat use
        YamlConfiguration messages = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(getClass().getResourceAsStream("/messages.yml"), java.nio.charset.StandardCharsets.UTF_8));
        for (Kind kind : Kind.values()) assertTrue(messages.isString("tuning.kind." + kind.word()), kind.word());
        for (String key : List.of("tile-stock", "tile-name", "tab-off", "pay-off", "undo-off", "tried", "actions", "paid", "poor", "reverted", "left", "expired",
                "no-vehicle", "nothing", "no-shop", "no-shops", "busy", "stock", "free")) {
            assertTrue(messages.isString("tuning." + key), key);
        }
        for (String key : List.of("tile-lore", "tile-lore-stock", "tile-lore-on", "tile-lore-owned", "tab-off-lore", "pay-off-lore")) {
            assertTrue(messages.isList("tuning." + key), key);
        }
    }
}
