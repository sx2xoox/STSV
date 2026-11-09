package dev.stsv.core.gui;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 통합 메뉴 (stsv:hub): every button leads somewhere that exists, and the action forms parse. */
class HubTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    /** Every command name and alias in a plugin.yml. */
    static Set<String> commands(YamlConfiguration pluginYml) {
        Set<String> out = new HashSet<>();
        ConfigurationSection cs = pluginYml.getConfigurationSection("commands");
        for (String name : cs.getKeys(false)) {
            out.add(name);
            out.addAll(cs.getStringList(name + ".aliases"));
        }
        return out;
    }

    static YamlConfiguration resource(Class<?> of, String name) throws Exception {
        try (var in = of.getResourceAsStream(name)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void actionsParse() {
        assertEquals(List.of("돈"), HubModule.commands("command:돈"));
        assertEquals(List.of("직업", "직업 순위"), HubModule.commands("hub:run:직업|run:직업 순위"));
        assertEquals(List.of("송금"), HubModule.commands("hub:suggest:송금"));
        assertEquals(List.of("납품", "기부"), HubModule.commands("hub:run:납품|suggest:기부"));
        assertEquals(List.of(), HubModule.commands("close"));
        assertNull(HubModule.commands("hub:jump:x"));
        assertNull(HubModule.commands("wardrobe:exit"));
    }

    @Test
    void everyButtonOfTheCoreHubLeadsToACoreCommand() throws Exception {
        List<String> problems = new ArrayList<>();
        MenuSpec hub = MenuSpec.loadAll(List.of(new ContentPack("stsv", "stsv", PACK)), problems).stream()
                .filter(m -> m.id().equals(HubModule.MENU)).findFirst().orElseThrow();
        assertTrue(problems.isEmpty(), problems.toString());
        Set<String> known = commands(resource(getClass(), "/plugin.yml"));
        Set<String> used = new HashSet<>();
        for (MenuSpec.Button b : hub.buttons().values()) {
            List<String> cmds = HubModule.commands(b.action());
            if (cmds == null) {
                problems.add(b.key() + ": unknown action " + b.action());
                continue;
            }
            for (String c : cmds) {
                used.add(c.split(" ")[0]);
                if (!known.contains(c.split(" ")[0])) problems.add(b.key() + ": no command /" + c);
            }
            assertTrue(!b.on().lore().isEmpty() || b.action().equals("close"), b.key() + " has a line of lore");
        }
        // the hub gathers every player command of the core
        for (String c : List.of("치장", "칭호", "업적", "프로필", "명예의전당", "돈", "송금", "수표", "건축월드", "야생월드", "야간투시", "tpa", "tpahere")) {
            if (!used.contains(c)) problems.add("no button for /" + c);
        }
        assertTrue(hub.buttons().values().stream().anyMatch(b -> b.action().equals("close")), "a close button");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theHotkeyCanBeTurnedOffAndTheHubHasACommand() throws Exception {
        YamlConfiguration config = resource(getClass(), "/config.yml");
        assertTrue(config.isBoolean("hub.hotkey") && config.isBoolean("hub.join-tip"));
        assertTrue(commands(resource(getClass(), "/plugin.yml")).contains("메뉴"));
    }
}
