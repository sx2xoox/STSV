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

    private static Set<String> commands(YamlConfiguration pluginYml, boolean aliases) {
        Set<String> out = new HashSet<>();
        ConfigurationSection cs = pluginYml.getConfigurationSection("commands");
        for (String name : cs.getKeys(false)) {
            out.add(name);
            if (aliases) out.addAll(cs.getStringList(name + ".aliases"));
        }
        return out;
    }

    private static MenuSpec hub(ContentPack pack, String id) throws Exception {
        List<String> problems = new ArrayList<>();
        MenuSpec m = MenuSpec.loadAll(List.of(pack), problems).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        assertTrue(problems.isEmpty(), problems.toString());
        return m;
    }

    /** The first word of every command a menu's buttons run or suggest. */
    private static Set<String> used(MenuSpec m, List<String> problems) {
        Set<String> out = new HashSet<>();
        for (MenuSpec.Button b : m.buttons().values()) {
            List<String> cmds = HubModule.commands(b.action());
            if (cmds == null) problems.add(m.id() + " " + b.key() + ": unknown action " + b.action());
            else for (String c : cmds) out.add(c);
        }
        return out;
    }

    @Test
    void everyButtonLeadsSomewhereAndNothingIsMissing() throws Exception {
        MenuSpec eco = hub(new ContentPack("economy", "economy", ContentTest.PACK), "economy:hub");
        MenuSpec core = hub(new ContentPack("stsv", "stsv", CORE_PACK), HubModule.MENU);
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
        for (String c : commands(ecoYml, false)) if (!firstWords.contains(c)) problems.add("economy:hub has no button for /" + c);
        for (MenuSpec.Button b : eco.buttons().values()) {
            if (!b.action().equals("close") && b.on().lore().isEmpty()) problems.add("economy:hub " + b.key() + " has no lore");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
