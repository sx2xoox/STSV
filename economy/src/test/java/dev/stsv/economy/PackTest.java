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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The economy pack goes through the core's real model converter and resource pack builder. */
class PackTest {

    private static final Path PACK = ContentTest.PACK;
    private static final Path CORE = Path.of("../content/stsv").toAbsolutePath().normalize();
    private static final Set<String> GLOWING = Set.of("smoker_oven", "distiller");

    @Test
    void machineModelsConvertCleanly() throws Exception {
        List<Path> models;
        try (var s = Files.list(PACK.resolve("models"))) {
            models = s.filter(p -> p.toString().endsWith(".bbmodel")).sorted().toList();
        }
        assertEquals(8, models.size());
        for (Path p : models) {
            String name = p.getFileName().toString().replace(".bbmodel", "");
            BBModelConverter.Result r = BBModelConverter.convert(p, "economy:item/" + name);
            assertTrue(r.warnings().isEmpty(), name + ": " + r.warnings());
            boolean glows = false;
            for (JsonElement e : r.model().getAsJsonArray("elements")) glows |= e.getAsJsonObject().has("light_emission");
            assertEquals(GLOWING.contains(name), glows, name + ": only fire and the distiller bulb glow");
            assertTrue(r.model().has("display"), name);
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
        assertTrue(files.containsKey("assets/economy/models/item/crusher.json"), "machine models");
        assertTrue(files.containsKey("assets/economy/textures/gui/shop.png"), "menu backgrounds");
        assertTrue(files.containsKey("assets/economy/items/gui/coins.json"), "button icons");
        assertTrue(files.containsKey("assets/stsv/textures/gui/wardrobe.png"), "the core pack is still there");
        JsonObject font = com.google.gson.JsonParser.parseString(new String(files.get("assets/stsv/font/menus.json"))).getAsJsonObject();
        long economyBackgrounds = font.getAsJsonArray("providers").asList().stream()
                .filter(p -> p.getAsJsonObject().has("file") && p.getAsJsonObject().get("file").getAsString().startsWith("economy:gui/")).count();
        assertEquals(12, economyBackgrounds, "every economy menu background is in the menu font");
    }
}
