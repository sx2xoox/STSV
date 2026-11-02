package dev.stsv.core.content;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every vanilla id used by the bundled content must exist in 26.3
 * (list taken from the 26.3 client's assets/minecraft/items, see vanilla-items-26.3.txt).
 * Catches renames like chain -> iron_chain before they reach a server.
 */
class VanillaIdsTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));

    @Test
    void allVanillaIdsExist() throws Exception {
        Set<String> vanilla = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/vanilla-items-26.3.txt")) {
            vanilla.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
        }
        List<String> unknown = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(CONTENT)) {
            files = s.filter(p -> p.toString().endsWith(".yml")).toList();
        }
        for (Path file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            for (String path : yaml.getKeys(true)) {
                Object v = yaml.get(path);
                List<Object> values = v instanceof List<?> l ? new ArrayList<>(l) : List.of(v);
                for (Object o : values) {
                    if (!(o instanceof String s) || o instanceof ConfigurationSection) continue;
                    String leaf = path.substring(path.lastIndexOf('.') + 1);
                    boolean idField = leaf.equals("material") || leaf.equals("input") || leaf.equals("result")
                            || leaf.equals("item") || leaf.equals("ingredients") || leaf.equals("remainder")
                            || path.contains(".keys.");
                    if (!idField || s.startsWith("#")) continue;
                    String id = s.toLowerCase(Locale.ROOT);
                    if (id.contains(":") && !id.startsWith("minecraft:")) continue; // custom item
                    id = id.replace("minecraft:", "");
                    if (!vanilla.contains(id)) unknown.add(CONTENT.relativize(file) + " → " + path + " = " + s);
                }
            }
        }
        assertTrue(unknown.isEmpty(), "26.3에 없는 바닐라 id:\n" + String.join("\n", unknown));
    }
}
