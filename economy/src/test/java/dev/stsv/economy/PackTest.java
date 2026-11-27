package dev.stsv.economy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.pack.BBModelConverter;
import dev.stsv.core.pack.PackBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The economy pack goes through the core's real model converter and resource pack builder. */
class PackTest {

    private static final Path PACK = ContentTest.PACK;
    private static final Path CORE = Path.of("../content/stsv").toAbsolutePath().normalize();
    /** job machines, the press and their parts: model_tier (crops are the other models) */
    static final String MACHINE = "(gem_bench(_wheel)?|mill(_stone)?|fish_bench|forge|kitchen|still|press(_screw)?)_[0-4]";
    /** the 도박장 models that give light (models/gamble.py): the work light, the drum fire and the buttons */
    static final String GAMBLE_LIT = "gamble_(prop_worklight|prop_drumfire|btn_[a-z]+)";
    /** the 편의점 models that give light (models/store.py): the sign, the fridges, the cigarette wall's header, the keys, the scanner, the order tablet's screens, §17's microwave states (its lamp, its display), the empty kettle's lamp, the handheld PDA's screen */
    static final String STORE_LIT = "store_(sign|fridge_[a-z]+|cig_shelf|btn_[a-z0-9_]+|scanner|order_tablet(_[a-z]+)?|microwave_(open|run|done)|kettle_empty|held_pda)";
    /** 숨은 비밀 (관리자 문서): the one model of models/newcomer_assets.py that gives light (its flame and panes) */
    static final String NEWCOMER_LIT = "everlasting_lantern";
    // crop stages are crossed planes with cut-out textures

    @Test
    void machineModelsConvertCleanly() throws Exception {
        List<Path> models;
        try (var s = Files.list(PACK.resolve("models"))) {
            models = s.filter(p -> p.toString().endsWith(".bbmodel")).sorted().toList();
        }
        assertEquals(7 * 5 + 3 * 5 + 77 + 9 + 1 + 1 + 5 + 2 + 2 + 13 + 50 + 63 + 23 + 137 + 5, models.size(),
                "7 machines in 5 tiers (4세대: 4강), 3 spinning parts in 5 tiers, 77 growth stages of 17 crops, 9 하역장 props (6 container colours), the 국도 sign, "
                        + "its guide sign and its 5 arrows, the 통발 and the 가두리, the 숨은 비밀 기념비 stone unsolved and solved, and solved with each secret's own emblem (13), "
                        + "and the 도박장's 50 (2 tables, 7 props, 21 cards, 5 chip piles, the die, the bowl, the dice cup, 11 buttons, the whiteboard) and its 별관's 63 (the poker and baccarat tables, 53 playing cards, 3 baccarat chip piles, 5 more buttons), and the 23 things bus passengers hold (models/bus_hold.py: the hen and her flapping self, 21 more), and the 편의점's 137 (models/store.py: 96, and 3차's licences 3, ID card, two vests, order tablet, and §17's 12 furniture states, 11 held things, 2 spill stages, the wet floor sign, 5 opened boxes and the 다시 key, and the receipt feeding out of the printer and its garbled twin), and the 5 of models/newcomer_assets.py (four record shards, the lantern)");
        for (Path p : models) {
            String name = p.getFileName().toString().replace(".bbmodel", "");
            BBModelConverter.Result r = BBModelConverter.convert(p, "economy:item/" + name);
            assertTrue(r.warnings().isEmpty(), name + ": " + r.warnings());
            boolean glows = false;
            for (JsonElement e : r.model().getAsJsonArray("elements")) glows |= e.getAsJsonObject().has("light_emission");
            if (name.matches(GAMBLE_LIT)) assertTrue(glows, name + ": the 도박장's lamp, fire and buttons are lit");
            else if (name.startsWith("gamble_")) assertFalse(glows, name);
            else if (name.matches(STORE_LIT)) assertTrue(glows, name + ": the 편의점's sign, fridges' lights, cigarette header, keys and scanner window are lit");
            else if (name.startsWith("store_")) assertFalse(glows, name);
            else if (name.matches(NEWCOMER_LIT)) assertTrue(glows, name + ": the lantern is lit");
            else if (!name.matches(MACHINE)) assertFalse(glows, name + ": crops do not glow");
            else if (name.endsWith("_3") || name.startsWith("forge_")) assertTrue(glows, name + ": 3강 glows, and so does the forge fire");
            else if (name.matches("(gem_bench|mill|fish_bench)(_wheel|_stone)?_[0-2]")) assertFalse(glows, name + ": nothing glows below 3강");
            assertTrue(r.model().has("display"), name);
        }
    }

    @Test
    void cropPlanesStandLikeVanillaCrops() throws Exception {
        List<Path> crops;
        try (var s = Files.list(PACK.resolve("models"))) {
            crops = s.filter(p -> p.toString().endsWith(".bbmodel")).filter(p -> p.getFileName().toString().matches("[a-z_]+_\\d\\.bbmodel"))
                    .filter(p -> !p.getFileName().toString().replace(".bbmodel", "").matches(MACHINE))
                    .filter(p -> !p.getFileName().toString().startsWith("gamble_"))
                    .filter(p -> !p.getFileName().toString().startsWith("store_")).sorted().toList();   // store_spill_2 · _3 are no crop stages
        }
        assertEquals(77, crops.size());
        for (Path p : crops) {
            String name = p.getFileName().toString().replace(".bbmodel", "");
            int planes = 0, solids = 0;
            for (JsonElement el : BBModelConverter.convert(p, "economy:item/" + name).model().getAsJsonArray("elements")) {
                JsonObject e = el.getAsJsonObject();
                var from = e.getAsJsonArray("from");
                var to = e.getAsJsonArray("to");
                boolean plane = false;
                for (int a = 0; a < 3; a++) plane |= from.get(a).getAsFloat() == to.get(a).getAsFloat();
                if (!plane) {
                    solids++;
                    assertTrue(from.get(1).getAsFloat() >= -1, name + ": a bulb or root starts at the soil surface");
                    continue;
                }
                planes++;
                float y = from.get(1).getAsFloat();
                assertTrue(y == -1 || y == 15, name + ": planes stand 1px down in the farmland, like vanilla crops (" + y + ")");
                assertTrue(e.has("shade") && !e.get("shade").getAsBoolean(), name + ": plant planes are not shaded by direction");
                var faces = e.getAsJsonObject("faces");
                assertEquals(2, faces.size(), name + ": a plane has a front and a back");
                var uvs = faces.entrySet().stream().map(f -> f.getValue().getAsJsonObject().getAsJsonArray("uv").get(0).getAsFloat()).sorted().toList();
                assertEquals(List.of(0f, 16f), uvs, name + ": the back is mirrored back, so the picture is the same from both sides");
            }
            assertTrue(planes == 2 || planes == 4 || planes == 8, name + ": cross (2) or hash (4) planes, doubled for tall plants");
            if (name.matches("(radish|onion|garlic|cabbage|green_onion|lettuce|strawberry)_3|(eggplant|zucchini|muskmelon)_4")) {
                assertTrue(solids > 0, name + ": roots, bulbs, heads and berries are solid, not painted on the planes");
            }
        }
    }

    @Test
    void bothPacksBuildIntoOneResourcePack() throws Exception {
        List<ContentPack> packs = List.of(new ContentPack("economy", "economy", PACK), new ContentPack("stsv", "stsv", CORE));
        PackBuilder.Built a = new PackBuilder().build(packs, List.of(), 97, "test");
        assertTrue(a.warnings().isEmpty(), "warnings: " + a.warnings());
        assertEquals(a.sha1(), new PackBuilder().build(packs, List.of(), 97, "test").sha1(), "deterministic");
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(a.zip()))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) files.put(e.getName(), in.readAllBytes());
        }
        assertTrue(files.containsKey("assets/economy/textures/item/ruby.png"), "2D job icons");
        assertTrue(files.containsKey("assets/economy/models/item/gem_bench_3.json"), "machine models");
        assertTrue(files.containsKey("assets/economy/models/item/mill_stone_2.json"), "spinning part models");
        assertTrue(files.containsKey("assets/economy/textures/gui/shop.png"), "menu backgrounds");
        assertTrue(files.containsKey("assets/economy/items/gui/coins.json"), "button icons");
        assertTrue(files.containsKey("assets/stsv/textures/gui/wardrobe.png"), "the core pack is still there");
        JsonObject font = com.google.gson.JsonParser.parseString(new String(files.get("assets/stsv/font/menus.json"))).getAsJsonObject();
        // one picture per background (a framed one is cut into tiles: a provider per row of tiles, the same file)
        long economyBackgrounds = font.getAsJsonArray("providers").asList().stream()
                .filter(p -> p.getAsJsonObject().has("file") && p.getAsJsonObject().get("file").getAsString().startsWith("economy:gui/"))
                .map(p -> p.getAsJsonObject().get("file").getAsString()).distinct().count();
        assertEquals(34, economyBackgrounds, "every economy menu background is in the menu font");   // + 인물 도감
        assertTrue(files.containsKey("assets/economy/font/stockgraph.json"), "stock chart font");
        assertFalse(files.containsKey("assets/economy/font/fishring.json"), "the fishing ring is gone");
        assertTrue(files.containsKey("assets/economy/font/fishicon.json"), "fish picture font");
        assertTrue(files.containsKey("assets/economy/textures/font/fishicon.png"));
        assertTrue(files.containsKey("assets/economy/textures/font/stockgraph_up_body.png"));
    }
}
