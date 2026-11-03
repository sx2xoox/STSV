package dev.stsv.core.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;
import dev.stsv.core.pack.PackBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-view wrappers: in the inventory (every context but "head") a helmet must look exactly like
 * the vanilla one, on the head it must be the cosmetic, and every model they point at must exist.
 */
class CosmeticViewsTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final List<String> COSMETICS = List.of("tomato_mask", "eggplant_mask", "mushroom_mask");
    private static Map<String, byte[]> files;
    private static List<CustomItem> items;
    private static PackBuilder.Built built;

    @BeforeAll
    static void build() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        items = new ArrayList<>();
        for (String id : COSMETICS) {
            items.add(item(pack, id, Material.PAPER, new ModelRef(ModelRef.Kind.BBMODEL, id, false), null,
                    new CustomItem.Cosmetic(EquipmentSlot.HEAD, CustomItem.Rarity.COMMON, "")));
        }
        // a custom helmet made from a vanilla helmet, and a custom item worn on the head by equipment
        items.add(item(pack, "test_helmet", Material.IRON_HELMET, new ModelRef(ModelRef.Kind.BBMODEL, "eggplant_mask", false), null, null));
        items.add(item(pack, "test_hat", Material.PAPER, new ModelRef(ModelRef.Kind.BBMODEL, "eggplant_mask", false),
                new CustomItem.Equipment(EquipmentSlot.HEAD, new NamespacedKey("stsv", "test")), null));
        items.add(item(pack, "test_gem", Material.PAPER, new ModelRef(ModelRef.Kind.BBMODEL, "eggplant_mask", false), null, null));
        built = new PackBuilder().build(List.of(pack), items, 97, "test");
        files = unzip(built.zip());
        assertTrue(built.warnings().stream().noneMatch(w -> w.contains("치장")), built.warnings().toString());
    }

    private static CustomItem item(ContentPack pack, String id, Material material, ModelRef model,
                                   CustomItem.Equipment equipment, CustomItem.Cosmetic cosmetic) {
        return new CustomItem(new NamespacedKey("stsv", id), pack, material, id, List.of(), model, null, null,
                false, null, null, equipment, null, null, cosmetic, null, null);
    }

    @Test
    void everyHeadItemGetsAWrapper() {
        for (String target : CosmeticViews.vanillaHeadItems().keySet()) {
            assertNotNull(files.get(CosmeticViews.definitionPath(CosmeticViews.wrapperKey(target))), target);
        }
        assertNotNull(files.get("assets/stsv/items/cosmetic_view/stsv/test_helmet.json"), "helmet material");
        assertNotNull(files.get("assets/stsv/items/cosmetic_view/stsv/test_hat.json"), "head equipment");
        assertFalse(files.containsKey("assets/stsv/items/cosmetic_view/stsv/test_gem.json"), "not wearable");
        assertFalse(files.containsKey("assets/stsv/items/cosmetic_view/stsv/tomato_mask.json"), "cosmetics are not worn");
        assertEquals(16, CosmeticViews.vanillaHeadItems().size());
    }

    /** Inventory = the vanilla definition untouched (trims, dye); head = the cosmetic by custom_model_data. */
    @Test
    void wrapperKeepsTheOriginalOutsideTheHead() {
        for (Map.Entry<String, JsonObject> e : CosmeticViews.vanillaHeadItems().entrySet()) {
            JsonObject wrapper = json(CosmeticViews.definitionPath(CosmeticViews.wrapperKey(e.getKey())));
            JsonObject original = e.getValue();
            JsonObject byContext = wrapper.getAsJsonObject("model");
            assertEquals("minecraft:select", byContext.get("type").getAsString());
            assertEquals("minecraft:display_context", byContext.get("property").getAsString());
            assertEquals(original.get("model"), byContext.get("fallback"), e.getKey() + ": inventory look changed");
            JsonArray contexts = byContext.getAsJsonArray("cases");
            assertEquals(1, contexts.size());
            assertEquals("head", contexts.get(0).getAsJsonObject().get("when").getAsString());

            JsonObject byCosmetic = contexts.get(0).getAsJsonObject().getAsJsonObject("model");
            assertEquals("minecraft:custom_model_data", byCosmetic.get("property").getAsString());
            assertEquals(0, byCosmetic.get("index").getAsInt());
            assertEquals(original.get("model"), byCosmetic.get("fallback"));
            Set<String> seen = new HashSet<>();
            for (JsonElement c : byCosmetic.getAsJsonArray("cases")) {
                String when = c.getAsJsonObject().get("when").getAsString();
                seen.add(when);
                JsonObject cosmeticDef = json(CosmeticViews.definitionPath(when));
                assertEquals(cosmeticDef.get("model"), c.getAsJsonObject().get("model"), "head look = the cosmetic itself");
            }
            assertEquals(Set.of("stsv:tomato_mask", "stsv:eggplant_mask", "stsv:mushroom_mask"), seen);
            // other top-level fields of the definition are kept
            for (String key : original.keySet()) if (!key.equals("model")) assertEquals(original.get(key), wrapper.get(key), key);
        }
    }

    @Test
    void emptySlotLooksLikeTheVanillaEmptySlot() {
        JsonObject def = json("assets/stsv/items/cosmetic_view/empty.json").getAsJsonObject("model");
        assertEquals("stsv:item/cosmetic_view/empty_helmet_slot", def.getAsJsonObject("fallback").get("model").getAsString());
        JsonObject model = json("assets/stsv/models/item/cosmetic_view/empty_helmet_slot.json");
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        String sprite = model.getAsJsonObject("textures").get("layer0").getAsString();
        boolean inAtlas = false;
        for (JsonElement s : json("assets/minecraft/atlases/items.json").getAsJsonArray("sources")) {
            JsonObject o = s.getAsJsonObject();
            if (sprite.equals(o.get("sprite").getAsString())) {
                inAtlas = o.get("type").getAsString().equals("minecraft:single")
                        && o.get("resource").getAsString().equals("minecraft:gui/sprites/container/slot/helmet");
            }
        }
        assertTrue(inAtlas, "the empty slot sprite must be the vanilla GUI texture, added to the item atlas");
    }

    /** Every model a wrapper points at exists (in vanilla 26.3 or in the pack). */
    @Test
    void everyWrapperReferenceResolves() throws Exception {
        Set<String> vanilla = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/vanilla-models-26.3.txt")) {
            vanilla.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
        }
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String path : files.keySet()) {
            if (!path.startsWith("assets/stsv/items/cosmetic_view/")) continue;
            checked++;
            walk(path, json(path).get("model"), vanilla, problems);
        }
        assertTrue(checked >= 19, "wrappers: " + checked);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private void walk(String from, JsonElement node, Set<String> vanilla, List<String> problems) {
        if (node == null || node.isJsonPrimitive()) return;
        if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(n -> walk(from, n, vanilla, problems));
            return;
        }
        JsonObject o = node.getAsJsonObject();
        for (String key : List.of("model", "base")) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                String ref = o.get(key).getAsString();
                int c = ref.indexOf(':');
                String ns = c < 0 ? "minecraft" : ref.substring(0, c), p = ref.substring(c + 1);
                if (ns.equals("minecraft") ? !vanilla.contains(p) : !files.containsKey("assets/" + ns + "/models/" + p + ".json")) {
                    problems.add(from + " → " + ref);
                }
            }
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) walk(from, e.getValue(), vanilla, problems);
    }

    /** The server only points an item at a wrapper the pack really has. */
    @Test
    void reportedViewsAreExactlyTheWrittenOnes() {
        assertEquals(CosmeticViews.targets(items), built.cosmeticViews());
        for (String t : built.cosmeticViews()) {
            assertNotNull(files.get(CosmeticViews.definitionPath(CosmeticViews.wrapperKey(t))), t);
        }
        long wrappers = files.keySet().stream()
                .filter(f -> f.startsWith("assets/stsv/items/cosmetic_view/") && !f.endsWith("/empty.json")).count();
        assertEquals(built.cosmeticViews().size(), wrappers);
    }

    @Test
    void noCosmeticsNoViews() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        PackBuilder.Built none = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        assertTrue(none.cosmeticViews().isEmpty());
        assertFalse(unzip(none.zip()).keySet().stream().anyMatch(f -> f.contains("cosmetic_view")));
    }

    private static JsonObject json(String path) {
        byte[] data = files.get(path);
        assertNotNull(data, path);
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
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
