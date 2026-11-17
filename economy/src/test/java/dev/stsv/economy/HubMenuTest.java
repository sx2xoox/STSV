package dev.stsv.economy;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.HubModule;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The economy's 통합 메뉴 (economy:hub, opened instead of the core's stsv:hub): every button leads to a
 * command of the core or the economy, it keeps everything the core's hub offers, and it offers every
 * player command of the economy's plugin.yml.
 */
class HubMenuTest {

    private static final Path CORE_PACK = Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv").normalize();

    private static YamlConfiguration yml(Class<?> of, String name) throws Exception {
        try (var in = of.getResourceAsStream(name)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Player commands used once per account: no hub button. */
    private static final Set<String> ONCE = Set.of("기본템");

    private static Set<String> commands(YamlConfiguration pluginYml, boolean aliases) {
        Set<String> out = new HashSet<>();
        ConfigurationSection cs = pluginYml.getConfigurationSection("commands");
        for (String name : cs.getKeys(false)) {
            out.add(name);
            if (aliases) out.addAll(cs.getStringList(name + ".aliases"));
        }
        return out;
    }

    /** The hub's pages, the first screen first (every menu its "hub:go:" buttons reach; stsv:hub = the first screen). */
    private static List<MenuSpec> pages(ContentPack pack, String start) throws Exception {
        List<String> problems = new ArrayList<>();
        java.util.Map<String, MenuSpec> byId = new java.util.HashMap<>();
        for (MenuSpec m : MenuSpec.loadAll(List.of(pack), problems)) byId.put(m.id(), m);
        assertTrue(problems.isEmpty(), problems.toString());
        List<MenuSpec> out = new ArrayList<>();
        java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(start));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (!seen.add(id)) continue;
            MenuSpec m = byId.get(id);
            assertTrue(m != null, "no hub page " + id);
            out.add(m);
            for (MenuSpec.Button b : m.buttons().values()) {
                if (!b.action().startsWith(HubModule.GO)) continue;
                String to = b.action().substring(HubModule.GO.length());
                todo.add(to.equals(HubModule.MENU) ? start : to);
            }
        }
        return out;
    }

    /** The first word of every command a menu's buttons run or suggest. */
    private static Set<String> used(List<MenuSpec> pages, List<String> problems) {
        Set<String> out = new HashSet<>();
        for (MenuSpec m : pages) for (MenuSpec.Button b : m.buttons().values()) {
            List<String> cmds = HubModule.commands(b.action());
            if (cmds == null) problems.add(m.id() + " " + b.key() + ": unknown action " + b.action());
            else for (String c : cmds) out.add(c);
        }
        return out;
    }

    @Test
    void everyButtonLeadsSomewhereAndNothingIsMissing() throws Exception {
        List<MenuSpec> eco = pages(new ContentPack("economy", "economy", ContentTest.PACK), "economy:hub");
        List<MenuSpec> core = pages(new ContentPack("stsv", "stsv", CORE_PACK), HubModule.MENU);
        YamlConfiguration ecoYml = yml(getClass(), "/plugin.yml");
        // the core's plugin.yml from its sources (on this classpath "/plugin.yml" is the economy's)
        YamlConfiguration coreYml = YamlConfiguration.loadConfiguration(Path.of("../plugin/src/main/resources/plugin.yml").toFile());
        Set<String> known = new HashSet<>(commands(ecoYml, true));
        known.addAll(commands(coreYml, true));
        List<String> problems = new ArrayList<>();
        Set<String> ecoUsed = used(eco, problems);
        for (String c : ecoUsed) if (!known.contains(c.split(" ")[0])) problems.add("economy:hub: no command /" + c);
        // a superset of the core's hub
        for (String c : used(core, problems)) if (!ecoUsed.contains(c)) problems.add("economy:hub lacks the core hub's /" + c);
        // every player command of the economy (admin tools live under the core's /관리자)
        Set<String> firstWords = new HashSet<>();
        for (String c : ecoUsed) firstWords.add(c.split(" ")[0]);
        // (a command used once per account, the starter kit, is told about, not kept on a button)
        for (String c : commands(ecoYml, false)) if (!firstWords.contains(c) && !ONCE.contains(c)) problems.add("economy:hub has no button for /" + c);
        for (MenuSpec page : eco) {
            boolean back = false;
            for (MenuSpec.Button b : page.buttons().values()) {
                if (!b.action().equals("close") && b.on().lore().isEmpty()) problems.add(page.id() + " " + b.key() + " has no lore");
                back |= b.action().equals(HubModule.GO + HubModule.MENU);
            }
            if (!page.id().equals("economy:hub") && !back) problems.add(page.id() + ": no way back to the first screen");
        }
        // the first screen stays short: the categories, the rest one step in
        if (eco.getFirst().buttons().size() > 12) problems.add("economy:hub: too many buttons on the first screen");
        // every page keeps the full six rows (bigger buttons, not a smaller screen)
        for (MenuSpec page : eco) if (page.rows() != 6) problems.add(page.id() + ": not six rows");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
