package dev.stsv.core.cosmetic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import dev.stsv.core.content.ModelRef;
import dev.stsv.core.pack.PackBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cosmetics fading near the camera: the dither maths (the same as the shader's), the shader files, and the pack: every
 * model a cosmetic display shows gets marked definitions on tint-0 copies, nothing else carries the marker.
 */
class NearFadeTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final NearFade.Settings ON = new NearFade.Settings(true, new NearFade.Distances(0.6, 1.2),
            new NearFade.Distances(2.0, 2.5), new NearFade.Distances(2.5, 3.0));
    private static Map<String, byte[]> files;
    private static Map<String, byte[]> plain;
    private static PackBuilder.Built built;
    private static List<CustomItem> items;

    @BeforeAll
    static void build() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        items = new ArrayList<>();
        items.add(item(pack, "halo", cosmetic(CosmeticSlot.ABOVE, null)));
        items.add(item(pack, "puppy", cosmetic(CosmeticSlot.PET, null)));
        items.add(item(pack, "tomato_mask", cosmetic(CosmeticSlot.HEAD, null)));          // on the head: not a display
        items.add(item(pack, "parrot", cosmetic(CosmeticSlot.TRAIL, null)));              // a trail: no worn display
        // a model aura (its card is the item's own model, worn: another one) with an awakened look and a part
        items.add(item(pack, "duck_aura_card", cosmetic(CosmeticSlot.AURA, new CustomItem.Premium(key("model/duck_aura_ring"),
                key("model/duck_aura_ring_awakened"), null, null, List.of(new CustomItem.Part(key("model/azure_pearl_orb"), null,
                new org.joml.Vector3f(8, 12, 8), new org.joml.Vector3f(), 1f, true, true))))));
        items.add(item(pack, "crt_tv", null));                                             // furniture, not a cosmetic
        built = new PackBuilder().nearFade(ON).build(List.of(pack), items, 97, "test");
        files = unzip(built.zip());
        plain = unzip(new PackBuilder().build(List.of(pack), items, 97, "test").zip());
    }

    private static NamespacedKey key(String path) {
        return new NamespacedKey("stsv", path);
    }

    private static CustomItem.Cosmetic cosmetic(CosmeticSlot slot, CustomItem.Premium premium) {
        return new CustomItem.Cosmetic(slot, CustomItem.Rarity.COMMON, "", new org.bukkit.util.Vector(), 1f, false, null, premium != null, null,
                false, null, premium);
    }

    private static CustomItem item(ContentPack pack, String model, CustomItem.Cosmetic cosmetic) {
        return new CustomItem(key("t_" + model), pack, Material.PAPER, model, List.of(), new ModelRef(ModelRef.Kind.BBMODEL, model, false),
                null, null, false, null, null, null, null, null, cosmetic, null, null);
    }

    // ------------------------------------------------------------------------------------------
    // the fade
    // ------------------------------------------------------------------------------------------

    @Test
    void ditherDrawsTheShareOfPixelsTheDistanceAsksFor() {
        NearFade.Distances d = new NearFade.Distances(2.0, 2.5);
        int previous = -1;
        for (int step = 0; step <= 60; step++) {
            double dist = 1.5 + step * 0.025;
            int drawn = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) if (NearFade.drawn(dist, d, x + 37, y + 11)) drawn++;
            double t = NearFade.coverage(dist, d);
            assertEquals(4 * (int) Math.min(16, Math.floor(16 * t + 0.5)), drawn, "at " + dist);
            assertTrue(drawn >= previous, "never fewer pixels further away");
            previous = drawn;
            if (dist <= 2.0) assertEquals(0, drawn, "nothing within inner");
            if (dist >= 2.5) assertEquals(64, drawn, "everything from outer");
        }
    }

    @Test
    void ditherSpreadsOverTheTile() {
        // a quarter: one pixel in every 2x2 block, so the dissolve has no clumps
        NearFade.Distances d = new NearFade.Distances(0, 1);
        for (int by = 0; by < 4; by += 2) for (int bx = 0; bx < 4; bx += 2) {
            int n = 0;
            for (int y = by; y < by + 2; y++) for (int x = bx; x < bx + 2; x++) if (NearFade.drawn(0.25, d, x, y)) n++;
            assertEquals(1, n);
        }
    }

    @Test
    void shaderUsesTheSameDitherMarkerAndBands() {
        String fsh = new String(NearFade.resource("item.fsh"), StandardCharsets.UTF_8);
        String vsh = new String(NearFade.resource("item.vsh"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("STSV_BAYER\\[16\\] = float\\[16\\]\\(([^)]*)\\)").matcher(fsh);
        assertTrue(m.find());
        String[] v = m.group(1).split(",");
        assertEquals(16, v.length);
        for (int i = 0; i < 16; i++) assertEquals(NearFade.BAYER[i], (int) Double.parseDouble(v[i].trim()), "Bayer " + i);
        assertTrue(fsh.contains(NearFade.BANDS_LINE));
        assertTrue(vsh.contains("c.r == " + NearFade.MARK_RED + " && c.g == " + NearFade.MARK_GREEN + " && c.b >= " + NearFade.MARK_BLUE
                + " && c.b <= " + (NearFade.MARK_BLUE + 2) + ") ? c.b - " + NearFade.MARK_BLUE));
        for (NearFade.Band b : NearFade.Band.values()) assertEquals(NearFade.MARK_BLUE + b.index, NearFade.tint(b) & 0xFF);
        // the pieces of vanilla's item shader it must keep: every define of the 26.3 item pipelines
        for (String define : List.of("ALPHA_CUTOUT", "GLINT", "GLINT_SPECIAL", "OIT_ALPHA_ONLY", "OIT_ACCUMULATE")) {
            assertTrue(fsh.contains(define) || vsh.contains(define), define);
        }
    }

    @Test
    void bandsAreWrittenIntoTheShader() {
        NearFade.Settings s = new NearFade.Settings(true, new NearFade.Distances(0.5, 1.0), new NearFade.Distances(1.75, 2.4),
                new NearFade.Distances(3, 3.5));
        String fsh = new String(NearFade.shaders(s).get(NearFade.FSH), StandardCharsets.UTF_8);
        assertTrue(fsh.contains("const vec2 STSV_FADE_BANDS[3] = vec2[3](vec2(0.5, 1.0), vec2(1.75, 2.4), vec2(3.0, 3.5));"), fsh);
        assertFalse(fsh.contains(NearFade.BANDS_LINE));
    }

    @Test
    void settingsComeFromConfigYml() throws Exception {
        YamlConfiguration c;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            c = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> problems = new ArrayList<>();
        NearFade.Settings s = NearFade.Settings.read(c, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertTrue(s.enabled());
        // own worn cosmetics reach 1.95 blocks from the eyes, pets 2.5 (README "근접 페이드"): hidden in first person,
        // drawn in full from the F5 camera (about 4 blocks away) and by other players a step away
        assertTrue(s.self().inner() >= 1.95 && s.self().outer() <= 2.8, s.self().toString());
        assertTrue(s.pet().inner() >= 2.5 && s.pet().outer() <= 3.2, s.pet().toString());
        assertTrue(s.others().outer() <= 1.5, s.others().toString());
        c.set("cosmetics.near-fade.self.outer", 1.0);
        assertEquals(2.1, NearFade.Settings.read(c, problems).self().outer(), 1e-9);
        assertEquals(1, problems.size(), "outer below inner is reported");
    }

    // ------------------------------------------------------------------------------------------
    // keys
    // ------------------------------------------------------------------------------------------

    @Test
    void keysAndTheWearersSwap() {
        assertEquals("stsv:near_fade/near/stsv/model/halo", NearFade.key(NearFade.Band.OTHERS, "stsv:model/halo"));
        assertEquals("stsv:near_fade/self/stsv/model/halo", NearFade.swap("stsv:near_fade/near/stsv/model/halo", NearFade.Band.SELF));
        assertEquals("stsv:near_fade/pet/stsv/t_puppy", NearFade.swap("stsv:near_fade/near/stsv/t_puppy", NearFade.Band.PET));
        assertNull(NearFade.swap("stsv:model/halo", NearFade.Band.SELF), "only near-fade definitions are swapped");
        assertNull(NearFade.swap(null, NearFade.Band.SELF));
    }

    @Test
    void displaysUseTheMarkedDefinitionOnlyWhenThePackHasIt() {
        NearFade.packBuilt(Map.of());
        assertEquals(key("t_halo"), NearFade.shown(key("t_halo")));
        NearFade.packBuilt(built.json());
        assertEquals(NamespacedKey.fromString("stsv:near_fade/near/stsv/t_halo"), NearFade.shown(key("t_halo")));
        assertEquals(NamespacedKey.fromString("stsv:near_fade/near/stsv/model/azure_pearl_orb"), NearFade.shown(key("model/azure_pearl_orb")));
        assertEquals(key("t_crt_tv"), NearFade.shown(key("t_crt_tv")), "not a cosmetic display");
        assertNull(NearFade.shown(null));
        NearFade.packBuilt(Map.of());
    }

    // ------------------------------------------------------------------------------------------
    // the pack
    // ------------------------------------------------------------------------------------------

    @Test
    void shadersAreInThePackOnlyWhenOn() {
        assertTrue(built.warnings().isEmpty(), built.warnings().toString());
        assertNotNull(files.get(NearFade.VSH));
        String fsh = new String(files.get(NearFade.FSH), StandardCharsets.UTF_8);
        assertTrue(fsh.contains("vec2[3](vec2(0.6, 1.2), vec2(2.0, 2.5), vec2(2.5, 3.0))"));
        assertNull(plain.get(NearFade.VSH));
        assertNull(plain.get(NearFade.FSH));
        assertTrue(plain.keySet().stream().noneMatch(f -> f.contains("near_fade")), "off: no near-fade files");
    }

    @Test
    void everyDisplayedModelGetsAMarkedDefinitionPerBand() {
        Set<String> expected = Set.of("stsv:t_halo", "stsv:t_puppy", "stsv:model/duck_aura_ring", "stsv:model/duck_aura_ring_awakened",
                "stsv:model/azure_pearl_orb");
        assertEquals(expected, NearFade.models(items, List.of()));
        assertTrue(NearFade.models(items, List.of("duck_set_pond", "stsv:model/azure_set_ring"))
                .containsAll(Set.of("stsv:model/duck_set_pond", "stsv:model/azure_set_ring")), "set awakening models");
        for (String model : expected) {
            for (NearFade.Band b : NearFade.Band.values()) {
                JsonObject def = json(NearFade.definitionPath(NearFade.key(b, model)));
                assertNotNull(def, model + " " + b);
                List<JsonObject> nodes = modelNodes(def.get("model"));
                assertFalse(nodes.isEmpty());
                for (JsonObject n : nodes) {
                    assertEquals(NearFade.tint(b), n.getAsJsonArray("tints").get(0).getAsJsonObject().get("value").getAsInt(), model);
                    String copy = n.get("model").getAsString();
                    assertTrue(copy.startsWith("stsv:item/near_fade/"), copy);
                    JsonObject m = json("assets/stsv/models/" + copy.substring("stsv:".length()) + ".json");
                    int faces = 0;
                    for (JsonElement el : m.getAsJsonArray("elements")) {
                        for (var f : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                            assertEquals(0, f.getValue().getAsJsonObject().get("tintindex").getAsInt(), copy);
                            faces++;
                        }
                    }
                    assertTrue(faces > 0);
                }
            }
        }
        // the originals are left as they were (held items, the inventory and other uses look the same)
        for (String f : List.of("assets/stsv/models/item/halo.json", "assets/stsv/items/t_halo.json", "assets/stsv/models/item/duck_aura_ring.json")) {
            assertEquals(new String(plain.get(f), StandardCharsets.UTF_8), new String(files.get(f), StandardCharsets.UTF_8), f);
        }
    }

    /** Distance LOD (CosmeticLod) composes with the fade: a far viewer gets the light model, still marked, others' band. */
    @Test
    void lightModelsAreMarkedToo() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        Map<String, String> table = CosmeticLod.table(List.of(pack), items);
        Set<String> withLight = NearFade.withLight(NearFade.models(items, List.of()), table);
        for (String m : List.of("stsv:model/duck_aura_ring", "stsv:model/duck_aura_ring_awakened", "stsv:model/azure_pearl_orb")) {
            assertTrue(withLight.contains(m + "_lod"), m);
            for (NearFade.Band b : NearFade.Band.values()) {
                JsonObject def = json(NearFade.definitionPath(NearFade.key(b, m + "_lod")));
                assertNotNull(def, m + "_lod " + b);
                for (JsonObject n : modelNodes(def.get("model"))) {
                    assertEquals(NearFade.tint(b), n.getAsJsonArray("tints").get(0).getAsJsonObject().get("value").getAsInt());
                    assertNotNull(json("assets/stsv/models/" + n.get("model").getAsString().substring("stsv:".length()) + ".json"));
                }
            }
        }
        // what the packet rewrite sends: everyone the marked full model, the wearer their band, a far viewer the light one
        String sent = NearFade.key(NearFade.Band.OTHERS, "stsv:model/duck_aura_ring");
        String far = CosmeticLod.forViewer(sent, 9, 7, null, Set.of(7), table);
        assertEquals("stsv:near_fade/near/stsv/model/duck_aura_ring_lod", far);
        assertNotNull(json(NearFade.definitionPath(far)), "the far viewer's model is in the pack");
        assertEquals("stsv:near_fade/self/stsv/model/duck_aura_ring", CosmeticLod.forViewer(sent, 7, 7, null, Set.of(7), table));
        assertEquals(sent, CosmeticLod.forViewer(sent, 8, 7, null, Set.of(), table));
        // a model without a light one stays as it is far away
        String halo = NearFade.key(NearFade.Band.OTHERS, "stsv:t_halo");
        assertEquals(halo, CosmeticLod.forViewer(halo, 9, 7, null, Set.of(7), table));
        // off (no near-fade pack): no marked light models either
        assertTrue(plain.keySet().stream().noneMatch(f -> f.contains("near_fade")));
    }

    @Test
    void nothingButTheCosmeticDisplaysCarriesTheMarker() {
        Pattern marker = Pattern.compile("\"value\":(" + NearFade.tint(NearFade.Band.OTHERS) + "|" + NearFade.tint(NearFade.Band.SELF) + "|"
                + NearFade.tint(NearFade.Band.PET) + ")\\b");
        int marked = 0;
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (!f.getKey().endsWith(".json")) continue;
            boolean has = marker.matcher(new String(f.getValue(), StandardCharsets.UTF_8)).find();
            if (has) marked++;
            assertEquals(f.getKey().startsWith("assets/stsv/items/near_fade/"), has, f.getKey());
        }
        // the five models of the items above and the three sets' awakening models (content cosmetic_sets.yml), plus the light
        // far-away models of six of them (CosmeticLod: the aura ring and its awakened look, the pearl, the three rings),
        // three bands each
        assertEquals((5 + 3 + 6) * 3, marked);
        for (String ring : List.of("azure_set_ring", "galaxy_set_ring", "duck_set_pond")) {
            assertNotNull(files.get("assets/stsv/items/near_fade/self/stsv/model/" + ring + ".json"), ring);
        }
        assertNull(files.get("assets/stsv/items/near_fade/near/stsv/t_tomato_mask.json"), "head cosmetics are not displays");
        assertNull(files.get("assets/stsv/items/near_fade/near/stsv/t_crt_tv.json"));
        assertNull(files.get("assets/stsv/items/near_fade/near/stsv/t_parrot.json"), "a trail is not worn");
    }

    private static List<JsonObject> modelNodes(JsonElement e) {
        List<JsonObject> out = new ArrayList<>();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").getAsString().equals("minecraft:model")) out.add(o);
            else for (var x : o.entrySet()) out.addAll(modelNodes(x.getValue()));
        } else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) out.addAll(modelNodes(x));
        return out;
    }

    private static JsonObject json(String path) {
        byte[] b = files.get(path);
        return b == null ? null : JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
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
