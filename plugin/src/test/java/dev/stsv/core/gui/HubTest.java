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

    /**
     * The hub's pages, the first screen first: every menu reached from start by "hub:go:" buttons. A go to
     * stsv:hub means start (a content plugin's hub replaces the core's). A missing page is a problem.
     */
    static List<MenuSpec> pages(List<MenuSpec> all, String start, List<String> problems) {
        java.util.Map<String, MenuSpec> byId = new java.util.HashMap<>();
        for (MenuSpec m : all) byId.put(m.id(), m);
        List<MenuSpec> out = new ArrayList<>();
        java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(start));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (!seen.add(id)) continue;
            MenuSpec m = byId.get(id);
            if (m == null) {
                problems.add("no hub page " + id);
                continue;
            }
            out.add(m);
            for (MenuSpec.Button b : m.buttons().values()) {
                if (!b.action().startsWith(HubModule.GO)) continue;
                String to = b.action().substring(HubModule.GO.length());
                todo.add(to.equals(HubModule.MENU) ? start : to);
            }
        }
        return out;
    }

    @Test
    void actionsParseGo() {
        assertEquals(List.of(), HubModule.commands("hub:go:stsv:hub_travel"));
        assertNull(HubModule.commands("hub:go:"));
    }

    @Test
    void everyButtonOfTheCoreHubLeadsToACoreCommand() throws Exception {
        List<String> problems = new ArrayList<>();
        List<MenuSpec> all = MenuSpec.loadAll(List.of(new ContentPack("stsv", "stsv", PACK)), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        List<MenuSpec> pages = pages(all, HubModule.MENU, problems);
        Set<String> known = commands(resource(getClass(), "/plugin.yml"));
        Set<String> used = new HashSet<>();
        for (MenuSpec page : pages) {
            boolean back = false;
            for (MenuSpec.Button b : page.buttons().values()) {
                List<String> cmds = HubModule.commands(b.action());
                if (cmds == null) {
                    problems.add(page.id() + " " + b.key() + ": unknown action " + b.action());
                    continue;
                }
                for (String c : cmds) {
                    used.add(c.split(" ")[0]);
                    if (!known.contains(c.split(" ")[0])) problems.add(page.id() + " " + b.key() + ": no command /" + c);
                }
                back |= b.action().equals(HubModule.GO + HubModule.MENU);
                assertTrue(!b.on().lore().isEmpty() || b.action().equals("close"), page.id() + " " + b.key() + " has a line of lore");
            }
            // a page leads back to the first screen
            if (!page.id().equals(HubModule.MENU) && !back) problems.add(page.id() + ": no way back to the first screen");
        }
        // the hub gathers every player command of the core
        for (String c : List.of("치장", "칭호", "업적", "프로필", "명예의전당", "돈", "송금", "수표", "건축월드", "야생월드", "야간투시", "tpa", "tpahere",
                "뽑기", "닉네임", "감정표현", "투명갑옷", "후원", "탈것", "ㅆ", "사이드바", "rtp")) {
            if (!used.contains(c)) problems.add("no button for /" + c);
        }
        // the first screen stays short: a handful of buttons, the rest one step in
        MenuSpec first = pages.getFirst();
        assertTrue(first.buttons().size() <= 7, "the first screen has few buttons: " + first.buttons().keySet());
        // every page keeps the full six rows (bigger buttons, not a smaller screen)
        for (MenuSpec page : pages) assertEquals(6, page.rows(), page.id() + " has six rows");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theHotkeyCanBeTurnedOffAndTheHubHasACommand() throws Exception {
        YamlConfiguration config = resource(getClass(), "/config.yml");
        assertTrue(config.isBoolean("hub.hotkey") && config.isBoolean("hub.join-tip"));
        assertTrue(commands(resource(getClass(), "/plugin.yml")).contains("메뉴"));
    }
}
