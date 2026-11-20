package dev.stsv.core.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real default content (content/stsv) and the TEST fixture pack (src/test/fixtures/stsv: every
 * kind of item once, never shipped) through the converter and pack builder.
 */
class PackPipelineTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final Path FIXTURES = Path.of(System.getProperty("stsv.fixtures"));
    private static final Set<Float> VANILLA_ANGLES = Set.of(-45f, -22.5f, 0f, 22.5f, 45f);

    /** Model files of both packs (shipped and fixture). */
    static java.util.stream.Stream<Path> models() throws Exception {
        return java.util.stream.Stream.concat(java.nio.file.Files.list(CONTENT.resolve("stsv/models")), java.nio.file.Files.list(FIXTURES.resolve("stsv/models")))
                .filter(p -> p.getFileName().toString().endsWith(".bbmodel"))
                .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()));
    }

    private static final Set<String> GLOWING = Set.of("test_machine", "test_greatsword", "test_slash",
            "halo", "crown", "wizard_hat", "butterfly_wings",   // cosmetics: the halo, gems, stars, wing spots
            "mole", "gumiho", "phoenix",                        // pets: the helmet lamp, fox-fire tails, flame tips
            "star_ring", "shoulder_dragon", "jetpack",          // cosmetics A: the stars, the dragon's ember, the rocket flames
            "sleepy_duckling_awakened",                         // 아기 오리: the moon and stars of the mobile
            "segway", "rodeo_bull", "rodeo_bull_bull",          // 특별 탈것: the segway's LED face, the big red button, the bull's red eyes
            "phantom_gt",                                       // 쇼케이스 팬텀 GT: its LED headlights, the tail light bar, the dash screens
            "devil_tail", "gold_medal", "crystal_pendant",      // cosmetics B: the heart tip, the medal's star, the crystal
            // donation sets: every piece glows at night (the dragon wings' membrane only at its tips: not listed)
            // 청룡 v2: pearls, flames, eyes, spines, whiskers, glowing cloud, lightning and the footprints' rims
            "azure_pearl_cloud", "azure_pearl_cloud_awakened", "azure_dragon_wings_awakened", "azure_dragon_tail", "azure_shoulder_dragon",
            "azure_cloud_sash", "azure_baby_dragon", "azure_cloud_trail", "azure_cloud_print_a", "azure_cloud_print_b",
            "azure_cloud_print_awakened", "azure_spark_aura", "azure_aura_dragon", "azure_aura_dragon_awakened", "azure_dragon_roar",
            "azure_roar_burst", "azure_set_ring",
            // parts: the pearl sprite (a billboard), the tail segments that carry a glowing spine (every third)
            "azure_pearl_orb", "azure_pearl_orb_awakened", "azure_tail_seg_2", "azure_tail_seg_5", "azure_tail_seg_8", "azure_tail_seg_11",
            // 은하수 v2: stars, gems, the moon, eyes, dust, the galaxy and the prints' rims
            "galaxy_star_crown", "galaxy_star_crown_awakened", "galaxy_cape", "galaxy_cape_awakened", "galaxy_moon_necklace",
            "galaxy_star_owl", "galaxy_star_belt", "galaxy_star_whale", "galaxy_stardust_trail", "galaxy_star_print_a",
            "galaxy_star_print_b", "galaxy_star_print_awakened", "galaxy_constellation_aura", "galaxy_planet_ring",
            "galaxy_planet_ring_awakened", "galaxy_supernova", "galaxy_supernova_burst", "galaxy_set_ring",
            "andon", "stone_lantern", "tea_kettle",             // furniture: lamp paper, the lantern's fire, embers
            "police_car", "light_truck", "golf_cart", "sports_car", "tuner_car",   // vehicles: head and tail lights, the tuner car neon
            "hyper_car", "grand_sedan", "luxury_suv",           // the premium cars: lamps, the SUV roof lamps
            "police_lightbar_a", "police_lightbar_b",           // the light bar's lit halves
            "cargo_bicycle", "cargo_van",                       // road vehicles: the bicycle's lamps, the van's head and tail lights
            "village_bus",                                      // 마을버스: the headlamp eyes and the tail lamps
            "mini_bus", "limousine_bus",                        // 소형 버스, 리무진 관광버스: round headlamps, tail lamps, the amber board
            "boat_trawl", "coastal_trawl",                      // 작업기: the work lamp on the trawl gallows
            "raft", "rowboat", "coastal_boat", "trawler",       // 고깃배: paper lanterns, squid lamps, running and mast lights, floodlights
            // retro furniture: the TV and arcade screens, the arcade marquee, the briquette and kerosene fires, the dalgona brazier,
            // the rice cooker's warm lamp, the mosquito coil on the 평상, the phone's amber window, the street lamp's bulb
            "crt_tv", "arcade_machine", "yeontan_stove", "kerosene_stove", "dalgona_stand", "rice_cooker", "pyeongsang", "public_phone",
            "street_lamp",
            "kart_storm", "kart_bubble", "kart_drifter", "kart_buffalo",    // 서킷 karts: head and tail lamps, the antenna ball, neon
            "retro_camper", "sidecar_bike", "muscle_car", "concept_ev",   // 후원 차 5종: dash lamps, the camper's porch lamp, LED bars
            // 후원 도구 스킨: every piece shines (jade eyes and pearls, stars, embers; the 장난감 series' lamps) — the gold ones never
            "toy_sword", "toy_pickaxe", "toy_shovel", "toy_axe", "toy_hoe", "toy_fishing_rod", "toy_fishing_rod_cast", "toy_bobber", "dragon_sword", "dragon_pickaxe", "dragon_shovel", "dragon_axe", "dragon_hoe", "dragon_fishing_rod", "dragon_fishing_rod_cast", "dragon_bobber", "galaxy_sword", "galaxy_pickaxe", "galaxy_shovel", "galaxy_axe", "galaxy_hoe", "galaxy_fishing_rod", "galaxy_fishing_rod_cast", "galaxy_bobber", "magma_sword", "magma_pickaxe", "magma_shovel", "magma_axe", "magma_hoe", "magma_fishing_rod", "magma_fishing_rod_cast", "magma_bobber",
            // 후원 차 6종 (16px) · 도구 스킨 세트 다시 그림 (2026-11)
            "frost_fishing_rod", "frost_fishing_rod_cast", "frost_sword", "najeon_axe", "najeon_bobber", "najeon_fishing_rod", "najeon_fishing_rod_cast", "najeon_hoe", "najeon_pickaxe", "najeon_shovel", "najeon_sword", "sushi_carnival",
            // 초밥 카니발 루프 부품 · 루프 장식 (vehicle_roofs*.py)
            "sushi_carnival_roof_r_tree",
            // 비밀 임무 보상 검은 세단 · 요원 K: LED eyes, the tail bar, the blue sill lines, the dash screen
            "agent_sedan");
    private static final Set<String> ROTATED = Set.of("scarecrow", "test_sword", "test_sword_skin", "test_greatsword", "test_slash", "test_rod", "test_rod_cast",
            "angel_wings", "demon_wings", "butterfly_wings",   // wings swept back 22.5 degrees
            "gumiho",                                           // nine tails fanned out
            "combine", "combine_reel", "combine_reel_arms", "combine_seeder",   // 소형 콤바인: the inclined feeder, crop dividers, reel bats, slanted arms
            "phantom_gt", "phantom_gt_door_l", "phantom_gt_door_r",   // 쇼케이스 팬텀 GT: the screen at 45°, the rear glass and side glass at 22.5°
            "toy_sword_belt", "lucky_pouch", "champion_belt",   // waist: the sword hangs slanted, the pouch's knot, the belt's plate
            // 은하수 v2: the octagonal crown and rings, the swept-back cape, gems turned 45 degrees, the owl's wings
            "galaxy_star_crown", "galaxy_star_crown_awakened", "galaxy_cape", "galaxy_cape_awakened", "galaxy_moon_necklace",
            "galaxy_star_owl", "galaxy_star_belt", "galaxy_star_whale", "galaxy_constellation_aura", "galaxy_planet_ring",
            "galaxy_planet_ring_awakened", "galaxy_supernova", "galaxy_supernova_burst", "galaxy_set_ring",
            // 청룡 v2 (silhouette first): bodies, horns, fingers and curves of turned segments
            "azure_pearl_cloud", "azure_pearl_cloud_awakened", "azure_dragon_wings", "azure_dragon_wings_awakened", "azure_dragon_tail",
            "azure_shoulder_dragon", "azure_cloud_sash", "azure_baby_dragon", "azure_spark_aura", "azure_aura_dragon",
            "azure_aura_dragon_awakened", "azure_dragon_roar", "azure_roar_burst", "azure_set_ring",
            // the tail's round segments: each a double cross (a plus and a plus turned 45 degrees); the part turns them to the curve
            "azure_tail_seg_1", "azure_tail_seg_2", "azure_tail_seg_3", "azure_tail_seg_4", "azure_tail_seg_5", "azure_tail_seg_6",
            "azure_tail_seg_7", "azure_tail_seg_8", "azure_tail_seg_9", "azure_tail_seg_10", "azure_tail_seg_11", "azure_tail_seg_12",
            // 아기 오리 v2: the ring's corners, the wings turned out, the ducklings' tails and raised wings, the nightcap's tip,
            // the bib's bow, the burst's puddle
            "duck_float_ring", "duck_float_ring_awakened", "duckling_wings", "duckling_wings_awakened", "sleepy_duckling",
            "sleepy_duckling_awakened", "peeking_duckling", "duck_bib", "duckling_parade", "duckling_parade_awakened",
            "duck_squeak", "duck_squeak_burst",
            "wagasa",                                           // the parasol's struts, tilted up to its canopy
            // retro furniture: the kettle's spout, the radio's aerial, the clothes prop, the phone's dial face, the dresser's wing
            // mirrors, the fan's tilted head, the lid leant on the small jar, the washboard in the tub, the TV's rabbit ears, the lamp's arm
            "brass_kettle", "cassette_radio", "clothesline", "dial_phone", "dresser", "electric_fan", "jangdokdae", "washtub",
            "crt_tv", "street_lamp",
            "meal_table",                                      // the round 양은 table top: slabs turned in steps of 22.5 degrees
            "police_car", "light_truck", "sports_car", "tuner_car",   // raked windscreens (45 / 22.5 degrees)
            "kart_storm", "kart_bubble", "kart_drifter", "kart_buffalo", "kart_tub",   // 서킷 karts: the steering column, raked 45 degrees
            "hyper_car", "grand_sedan", "luxury_suv",           // the premium cars: screens, the hyper car hood and back
            "cargo_bicycle", "delivery_scooter", "atv",         // road vehicles: frame tubes, forks, stays, steering posts
            "cargo_van", "box_truck",                          // a raked windscreen, a roof deflector and a tilted steering wheel
            "ox_cart", "power_tiller", "tractor",              // the ox's traces, the tiller's handlebars, the tractor's steering wheel
            "jige", "jige_load_1", "jige_load_2", "jige_load_3",  // the 지게 prongs, jutting back and up 22.5 degrees
            "wheel_atv",                                       // the ATV tyre's knobs, every 45 degrees round it
            // 작업기: the plough's beam and handle, hood plates and shields, tines on the slant, links and arms, the tarp's shoulders
            "ox_plough", "tiller_rotary", "tiller_rotary_tines", "tractor_rotary", "tractor_rotary_tines", "tractor_seeder",
            "tractor_header", "tractor_header_reel", "boat_trawl", "truck_tarp", "coastal_trawl", "trawler_trawl",
            // 고깃배: fishing rods leaning out of their holders, the raft's sweep and the skiff's oars (down into the water)
            "fishing_boat", "raft", "raft_scull", "rowboat", "rowboat_oar_l", "rowboat_oar_r", "coastal_boat", "trawler",
            "toy_pickaxe", "toy_fishing_rod",                  // 장난감 시리즈: the pickaxe's drooping arms, the rod's hanging line and float
            // 특별 탈것: the kick scooter's brake lever and fender, the board's kicktail and nose, the banana's stem, the segway's
            // fenders, the sled's curled runners, bow and rope, the balloons' strings, the bull's tail
            // the duck and the bull: octagonal sections whose corners are boxes turned 45 degrees (smooth round forms), the
            // stand's lever
            "kickboard", "skateboard", "banana_boat", "segway", "sled", "balloon_chair_balloons", "rodeo_bull_bull", "toy_duck", "rodeo_bull",
            "classic_roadster", "concept_ev", "muscle_car", "sidecar_bike",   // 후원 차: raked windscreens, the bike's fork
            "dragon_axe", "dragon_fishing_rod", "dragon_fishing_rod_cast", "dragon_pickaxe", "dragon_sword",
            "galaxy_fishing_rod", "galaxy_pickaxe", "magma_fishing_rod",
            // 도구 스킨 세트: slanted blades, crossed straps, the rods' tilted reels and guides (22.5 or 45 degrees)
            "camping_fishing_rod", "camping_pickaxe", "camping_shovel", "candy_fishing_rod", "candy_pickaxe", "candy_shovel", "frost_fishing_rod", "frost_fishing_rod_cast", "frost_pickaxe", "frost_sword", "kitchen_fishing_rod", "kitchen_pickaxe", "kitchen_sword", "najeon_fishing_rod", "najeon_pickaxe", "pirate_axe", "pirate_fishing_rod", "pirate_hoe", "pirate_pickaxe", "pirate_shovel", "woodcraft_axe", "woodcraft_fishing_rod", "woodcraft_fishing_rod_cast", "woodcraft_hoe", "woodcraft_pickaxe", "woodcraft_shovel", "woodcraft_sword",
            // 후원 차 6종 (16px) · 도구 스킨 세트 다시 그림 (2026-11)
            "classic_roadster_door_l", "classic_roadster_door_r", "concept_ev_door_l", "concept_ev_door_r", "dragon_shovel", "frost_axe", "frost_hoe", "frost_shovel", "galaxy_shovel", "galaxy_sword", "magma_pickaxe", "muscle_car_door_l", "muscle_car_door_r", "retro_camper", "sidecar_bike_screen", "sushi_carnival_door_l", "sushi_carnival_door_r", "sushi_carnival_slide_l", "sushi_carnival_slide_r", "wheel_sidecar", "najeon_sword", "sushi_carnival",
            // 초밥 카니발 루프 부품 · 루프 장식 (vehicle_roofs*.py)
            "sushi_carnival_roof", "sushi_carnival_roof_r_basket", "sushi_carnival_roof_r_box_black", "sushi_carnival_roof_r_cat", "sushi_carnival_roof_r_duck", "sushi_carnival_roof_r_kayak", "sushi_carnival_roof_r_kimbap", "sushi_carnival_roof_r_mattress", "sushi_carnival_roof_r_salmon", "sushi_carnival_roof_r_shrimp", "sushi_carnival_roof_r_ski", "sushi_carnival_roof_r_surf", "sushi_carnival_roof_r_tamago", "sushi_carnival_roof_r_tent", "sushi_carnival_roof_r_watermelon", "sushi_carnival_roof_r_tree",
            // 검은 세단 · 요원 K: the screen, rear window and pillars (22.5 / 45 degrees), the doors' crease; 요원의 코트: the tails
            "agent_sedan", "agent_sedan_door_fl", "agent_sedan_door_fr", "agent_sedan_door_rl", "agent_sedan_door_rr", "agent_coat");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("models")
    void modelConvertsToAValidVanillaModel(Path bbmodel) throws Exception {
        String name = bbmodel.getFileName().toString().replace(".bbmodel", "");
        BBModelConverter.Result r = BBModelConverter.convert(bbmodel, "stsv:item/" + name);
        assertTrue(r.warnings().isEmpty(), "warnings: " + r.warnings());

        JsonObject model = r.model();
        JsonObject textures = model.getAsJsonObject("textures");
        long pngs = r.textures().keySet().stream().filter(k -> k.endsWith(".png")).count();
        assertEquals(pngs + 1, textures.size(), "every texture + particle referenced");
        for (Map.Entry<String, JsonElement> t : textures.entrySet()) {
            String file = t.getValue().getAsString().replace("stsv:item/" + name + "/", "") + ".png";
            assertTrue(r.textures().containsKey(file), "missing texture file " + file);
        }

        boolean glows = false, rotated = false;
        for (JsonElement je : model.getAsJsonArray("elements")) {
            JsonObject el = je.getAsJsonObject();
            for (String k : List.of("from", "to")) {
                for (JsonElement c : el.getAsJsonArray(k)) {
                    float v = c.getAsFloat();
                    assertTrue(v >= -16 && v <= 32, k + " out of vanilla range: " + v);
                }
            }
            if (el.has("rotation")) {
                assertTrue(VANILLA_ANGLES.contains(el.getAsJsonObject("rotation").get("angle").getAsFloat()));
                rotated = true;
            }
            glows |= el.has("light_emission");
            for (Map.Entry<String, JsonElement> f : el.getAsJsonObject("faces").entrySet()) {
                JsonObject face = f.getValue().getAsJsonObject();
                for (JsonElement uv : face.getAsJsonArray("uv")) {
                    float v = uv.getAsFloat();
                    assertTrue(v >= 0 && v <= 16, "uv out of range: " + v);
                }
                assertTrue(textures.has(face.get("texture").getAsString().substring(1)), "unknown texture ref");
            }
        }
        // a cosmetic's light far-away model (cosmetics_lod.py): glows only where its full model does, never turned
        if (name.endsWith("_lod")) {
            String full = name.substring(0, name.length() - 4);
            assertTrue(!glows || GLOWING.contains(full), "only light sources glow (" + full + " does not)");
            assertFalse(rotated, "a light model is made of unturned boxes");
            assertTrue(model.has("display"));
            return;
        }
        // a tuning shop variant (vehicle_paints.py: another paint, wheel design, lamp colour) is judged as its stock model
        // (a 서킷 ghost, kart_ghosts.py: the kart or wheel drawn see-through, as its stock model)
        // (a 루프 장식, vehicle_roofs.py: a model of its own, judged by its own name, not as the roof box it replaces)
        String stock = name.contains("_roof_r_") ? name : dev.stsv.core.vehicle.VehicleTuning.stock(name.replaceFirst("_ghost(_gold)?$", ""));
        assertEquals(GLOWING.contains(stock) || stock.matches(".+_lamp_(head|tail|brake|reverse)") || stock.startsWith("underglow_"), glows,
                "only light sources glow");   // + the vehicles' lamp parts (vehicle_lights.py) and the glow under a tuned car
        assertEquals(ROTATED.contains(stock), rotated, "rotated parts");
        assertTrue(model.has("display"));
    }

    @Test
    void shippedPackBuildsCleanAndHasNoTestSet() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        PackBuilder.Built a = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        PackBuilder.Built b = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        assertEquals(a.sha1(), b.sha1(), "same content must give the same hash (no needless re-downloads)");
        assertTrue(a.warnings().isEmpty(), "warnings: " + a.warnings());
        assertTrue(unzip(a.zip()).keySet().stream().noneMatch(f -> f.contains("/test_") || f.endsWith("/test.json") || f.endsWith("/test.png")),
                "the TEST set is a test fixture, never shipped");
        try (var files = java.nio.file.Files.walk(CONTENT.resolve("stsv"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("test_") || f.getFileName().toString().equals("test.yml")),
                    "no TEST files in content/stsv");
        }
    }

    @Test
    void packIsCompleteAndDeterministic() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", FIXTURES.resolve("stsv"));
        PackBuilder.Built a = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        PackBuilder.Built b = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        assertEquals(a.sha1(), b.sha1(), "same content must give the same hash (no needless re-downloads)");
        assertTrue(a.warnings().isEmpty(), "warnings: " + a.warnings());

        Map<String, byte[]> files = unzip(a.zip());
        assertTrue(files.containsKey("assets/stsv/models/item/test_machine.json"));
        assertTrue(files.containsKey("assets/stsv/items/model/test_fan_blade.json"), "every 3D model gets an item definition");
        // model textures are stored once by content under textures/item/shared, the model points there
        JsonObject machine = JsonParser.parseString(new String(files.get("assets/stsv/models/item/test_machine.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        java.util.Set<String> refs = new java.util.HashSet<>();
        machine.getAsJsonObject("textures").entrySet().forEach(e -> refs.add(e.getValue().getAsString()));
        for (String ref : refs) {
            assertTrue(ref.startsWith("stsv:item/shared/"), "shared texture: " + ref);
            assertTrue(files.containsKey("assets/stsv/textures/item/" + ref.substring("stsv:item/".length()) + ".png"), "texture file for " + ref);
        }
        assertTrue(files.keySet().stream().anyMatch(f -> f.startsWith("assets/stsv/textures/item/shared/") && f.endsWith(".png.mcmeta")),
                "animated texture metadata");
        assertTrue(files.keySet().stream().noneMatch(f -> f.startsWith("assets/stsv/textures/item/test_machine/")), "no per-model copies");
        assertTrue(files.containsKey("assets/stsv/models/item/test_material.json"));
        assertTrue(files.containsKey("assets/stsv/textures/item/test_material.png"));

        JsonObject mcmeta = JsonParser.parseString(new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(97, mcmeta.getAsJsonObject("pack").get("min_format").getAsInt());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }
}
