package dev.stsv.core.pack;

import dev.stsv.core.content.ContentPack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 렌더 예산 (RenderBudget): every shipped 3D model, as the pack sends it, within the face budget of its kind. Crops are
 * blocks (part of the chunk mesh, not drawn one by one) and are not counted.
 */
class RenderBudgetTest {

    @Test
    void everyShippedModelFitsItsBudget() throws Exception {
        Path content = Path.of(System.getProperty("stsv.content"));
        Map<String, Path> packs = new LinkedHashMap<>();
        packs.put("stsv", content.resolve("stsv"));
        Path economy = content.getParent().resolve("economy/content/economy");
        if (Files.isDirectory(economy.resolve("models"))) packs.put("economy", economy);

        Map<String, Integer> faces = new TreeMap<>();
        Map<String, RenderBudget.Category> kinds = new LinkedHashMap<>();
        Set<String> crops = new HashSet<>();
        for (Map.Entry<String, Path> p : packs.entrySet()) {
            String ns = p.getKey();
            ContentPack cp = new ContentPack(ns, ns, p.getValue());
            PackBuilder b = new PackBuilder();
            b.build(List.of(cp), List.of(), 97, "t");
            b.faceCounts().forEach((m, c) -> faces.put(m, c[1]));
            kinds.putAll(RenderBudget.categories(ns, items(p.getValue())));
            for (var spec : dev.stsv.core.crop.CropSpec.loadAll(List.of(cp), w -> {})) {
                for (String id : spec.models()) crops.add(id.replace(":model/", ":"));
            }
        }
        // the kinds are found: furniture, machines by tier, their spinning parts, cosmetics and pets
        assertEquals(RenderBudget.Category.FURNITURE_SMALL, kinds.get("stsv:andon"));
        assertEquals(RenderBudget.Category.FURNITURE_LARGE, kinds.get("stsv:wagasa"));
        assertEquals(RenderBudget.Category.PET, kinds.get("stsv:cat"));
        assertEquals(RenderBudget.Category.COSMETIC, kinds.get("stsv:gat"));
        // vehicles: bodies, the wheels they turn, the light bar's looks and the truck's load stages
        for (String body : List.of("stsv:police_car", "stsv:light_truck", "stsv:golf_cart", "stsv:hand_cart")) {
            assertEquals(RenderBudget.Category.VEHICLE, kinds.get(body));
        }
        assertEquals(RenderBudget.Category.VEHICLE_WHEEL, kinds.get("stsv:wheel_road"));
        assertEquals(RenderBudget.Category.VEHICLE_WHEEL, kinds.get("stsv:wheel_cart"));
        assertEquals(RenderBudget.Category.VEHICLE_WHEEL, kinds.get("stsv:wheel_handcart"));
        for (String m : List.of("stsv:police_lightbar_off", "stsv:police_lightbar_a", "stsv:police_lightbar_b", "stsv:truck_cargo_1",
                "stsv:truck_cargo_4", "stsv:hand_cart_cargo_1", "stsv:hand_cart_cargo_4")) assertEquals(RenderBudget.Category.VEHICLE_PART, kinds.get(m));
        // the road vehicles of the ladder (vehicles_road.yml): bodies, wheels, the bicycle's crank and the open loads
        for (String body : List.of("stsv:cargo_bicycle", "stsv:delivery_scooter", "stsv:atv", "stsv:cargo_van", "stsv:box_truck")) {
            assertEquals(RenderBudget.Category.VEHICLE, kinds.get(body), body);
        }
        for (String w : List.of("stsv:wheel_bicycle", "stsv:wheel_scooter", "stsv:wheel_atv", "stsv:wheel_van", "stsv:wheel_truck")) {
            assertEquals(RenderBudget.Category.VEHICLE_WHEEL, kinds.get(w), w);
        }
        for (String m : List.of("stsv:bicycle_crank", "stsv:cargo_bicycle_cargo_1", "stsv:cargo_bicycle_cargo_4", "stsv:atv_cargo_1",
                "stsv:atv_cargo_4")) assertEquals(RenderBudget.Category.VEHICLE_PART, kinds.get(m), m);
        // the 통통배 (its propeller is its wheel, its catch its load) and the 지게's looks on the back
        assertEquals(RenderBudget.Category.VEHICLE, kinds.get("stsv:fishing_boat"));
        assertEquals(RenderBudget.Category.VEHICLE_WHEEL, kinds.get("stsv:boat_propeller"));
        for (int k = 1; k <= 4; k++) assertEquals(RenderBudget.Category.VEHICLE_PART, kinds.get("stsv:boat_catch_" + k));
        for (String m : List.of("stsv:jige", "stsv:jige_load_1", "stsv:jige_load_2", "stsv:jige_load_3")) {
            assertEquals(RenderBudget.Category.CARRIER, kinds.get(m));
        }
        if (packs.containsKey("economy")) {
            assertEquals(RenderBudget.Category.MACHINE_3, kinds.get("economy:kitchen_3"));
            assertEquals(RenderBudget.Category.MACHINE_0, kinds.get("economy:mill_0"));
            assertEquals(RenderBudget.Category.MACHINE_PART, kinds.get("economy:mill_stone_2"));
            assertTrue(crops.contains("economy:tomato_4"));
        }
        // every model an item shows was built
        for (String m : kinds.keySet()) assertTrue(faces.containsKey(m), "no model for " + m);

        List<String> over = RenderBudget.over(faces, kinds, crops);
        assertEquals(List.of(), over, "렌더 예산 초과:\n" + String.join("\n", over));
    }

    @Test
    void aHeavyModelIsNamedWithItsFacesAndBudget() {
        Map<String, RenderBudget.Category> kinds = Map.of("x:lamp", RenderBudget.Category.FURNITURE_SMALL);
        List<String> over = RenderBudget.over(Map.of("x:lamp", 5000, "x:ok", 10, "x:crop_1", 9999), kinds, Set.of("x:crop_1"));
        assertEquals(1, over.size());
        assertTrue(over.get(0).startsWith("x:lamp: 면 5000개 > 예산 " + RenderBudget.Category.FURNITURE_SMALL.faces), over.get(0));
    }

    /** Every item of a content pack (items/*.yml), by id. */
    private static Map<String, ConfigurationSection> items(Path pack) throws Exception {
        Map<String, ConfigurationSection> out = new LinkedHashMap<>();
        Path dir = pack.resolve("items");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String id : y.getKeys(false)) {
                    ConfigurationSection sec = y.getConfigurationSection(id);
                    if (sec != null) out.put(id, sec);
                }
            }
        }
        return out;
    }
}
