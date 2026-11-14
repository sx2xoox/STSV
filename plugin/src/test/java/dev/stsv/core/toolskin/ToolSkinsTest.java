package dev.stsv.core.toolskin;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.core.donation.DonationShop;
import dev.stsv.core.toolskin.ToolSkins.Tool;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool skins: the file, which items a skin goes on, what each player has switched on, the shipped series. */
class ToolSkinsTest {

    private static final Path PACK = Path.of("..", "content", "stsv");

    @Test
    void whichToolAnItemIs() {
        assertEquals(Tool.SWORD, Tool.of("DIAMOND_SWORD"));
        assertEquals(Tool.PICKAXE, Tool.of("WOODEN_PICKAXE"));
        assertEquals(Tool.AXE, Tool.of("NETHERITE_AXE"));
        assertEquals(Tool.SHOVEL, Tool.of("iron_shovel"));
        assertEquals(Tool.HOE, Tool.of("GOLDEN_HOE"));
        assertEquals(Tool.FISHING_ROD, Tool.of("FISHING_ROD"));
        // a pickaxe is no axe; things that only sound like tools are none
        assertEquals(Tool.PICKAXE, Tool.of("STONE_PICKAXE"));
        for (String no : List.of("STICK", "CARROT_ON_A_STICK", "BOW", "SHEARS", "TRIDENT", "WARPED_FUNGUS_ON_A_STICK")) assertNull(Tool.of(no), no);
        // every tool of the game is one of the kinds, whatever it is made of
        for (Material m : Material.values()) {
            if (m.isLegacy()) continue;
            String n = m.name();
            if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_AXE") || n.endsWith("_HOE")) {
                assertTrue(Tool.of(n) != null, n);
            }
        }
        assertEquals(Tool.AXE, Tool.parse(" axe "));
        assertNull(Tool.parse("spoon"));
        assertNull(Tool.parse(null));
    }

    @Test
    void theFile() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                series:
                  toy:
                    name: "장난감 시리즈"
                    skins:
                      toy_sword: { name: "장난감 검", tool: sword, model: "stsv:toy_sword" }
                      toy_axe:   { name: "장난감 도끼", tool: AXE, model: "stsv:toy_axe" }
                      no_tool:   { name: "?", tool: spoon, model: "stsv:spoon" }
                      no_model:  { name: "?", tool: hoe }
                  candy:
                    name: "사탕 시리즈"
                    skins:
                      toy_sword: { name: "같은 id", tool: sword, model: "stsv:candy_sword" }
                      candy_rod: { name: "사탕 낚싯대", tool: fishing_rod, model: "stsv:candy_rod" }
                  empty:
                    name: "빈 시리즈"
                """);
        List<String> problems = new ArrayList<>();
        ToolSkins skins = ToolSkins.parse(y, problems::add);
        assertEquals(List.of("toy_sword", "toy_axe", "candy_rod"), List.copyOf(skins.skins().keySet()));
        assertEquals(4, problems.size(), String.join("\n", problems));
        assertEquals(List.of("toy", "candy"), List.copyOf(skins.series().keySet()));
        assertEquals(List.of("toy_sword", "toy_axe"), skins.series().get("toy").skins());
        assertEquals("장난감 시리즈", skins.series().get("toy").name());
        ToolSkins.Skin sword = skins.skin("toy_sword");
        assertEquals(new ToolSkins.Skin("toy_sword", "toy", "장난감 검", Tool.SWORD, "stsv:toy_sword"), sword);
        assertEquals(Tool.FISHING_ROD, skins.skin("candy_rod").tool());
        assertNull(skins.skin("nope"));
        assertNull(skins.skin(null));
    }

    @Test
    void whatIsSwitchedOn(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        ToolSkinStore s = new ToolSkinStore(db);
        s.init();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(s.worn(a).get().isEmpty());
        s.wear(a, "SWORD", "toy_sword").get();
        s.wear(a, "AXE", "toy_axe").get();
        s.wear(b, "SWORD", "candy_sword").get();
        assertEquals(Map.of("SWORD", "toy_sword", "AXE", "toy_axe"), s.worn(a).get());
        // one skin a kind of tool: another replaces it
        s.wear(a, "SWORD", "candy_sword").get();
        assertEquals("candy_sword", s.worn(a).get().get("SWORD"));
        s.wear(a, "SWORD", null).get();
        assertEquals(Map.of("AXE", "toy_axe"), s.worn(a).get());
        s.clear(a).get();
        assertTrue(s.worn(a).get().isEmpty());
        assertEquals(Map.of("SWORD", "candy_sword"), s.worn(b).get(), "nobody else's");
        db.close();
    }

    /** The shipped series: the toy tools, each with a model the pack has, and all of them for sale. */
    @Test
    void theShippedSeries() throws Exception {
        List<String> problems = new ArrayList<>();
        ToolSkins skins = ToolSkins.parse(YamlConfiguration.loadConfiguration(PACK.resolve("tool_skins.yml").toFile()), problems::add);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        ToolSkins.Series toy = skins.series().get("toy");
        assertEquals("장난감 시리즈", toy.name());
        // the first series: a skin for every kind of tool
        assertEquals(Set.of(Tool.values()), Set.copyOf(toy.skins().stream().map(id -> skins.skin(id).tool()).toList()));
        assertEquals(Tool.values().length, toy.skins().size(), "one skin a kind of tool");
        for (ToolSkins.Skin s : skins.skins().values()) {
            String[] model = s.model().split(":");
            assertEquals("stsv", model[0]);
            assertTrue(Files.isRegularFile(PACK.resolve("assets/stsv/items/" + model[1] + ".json")), s.id() + ": the pack has no item model " + s.model());
            assertFalse(s.name().isBlank());
        }
        // every skin is sold in the donation shop (alone or in a set), and nothing is sold that is no skin
        DonationShop shop = DonationShop.parse(YamlConfiguration.loadConfiguration(PACK.resolve("donation_shop.yml").toFile()), id -> true, p -> { });
        Set<String> sold = new java.util.HashSet<>();
        for (DonationShop.Product p : shop.products().values()) {
            for (String u : p.unlocks()) {
                if (!u.startsWith(ToolSkinModule.KIND + ":")) continue;
                String id = u.substring(ToolSkinModule.KIND.length() + 1);
                assertTrue(skins.skins().containsKey(id), p.id() + ": no tool skin " + id);
                sold.add(id);
            }
        }
        assertEquals(skins.skins().keySet(), sold);
        // the menu, the command, the settings
        assertTrue(Files.isRegularFile(PACK.resolve("menus/tool_skins.yml")));
        var plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertEquals("stsv.toolskin", plugin.getString("commands.스킨.permission"));
        assertEquals("true", plugin.getString("permissions.stsv.toolskin.default"));
        assertEquals("op", plugin.getString("permissions." + ToolSkinModule.PERMISSION_ALL + ".default"));
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertTrue(config.getBoolean("tool-skins.enabled"));
        var messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (String key : List.of("disabled", "turned-on", "turned-off", "all-off", "tile-name", "tile-name-locked")) assertTrue(messages.isString("toolskin." + key), key);
        for (String key : List.of("tile-lore", "tile-lore-on", "tile-lore-locked")) assertTrue(messages.isList("toolskin." + key), key);
    }
}
