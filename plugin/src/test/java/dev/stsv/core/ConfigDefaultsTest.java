package dev.stsv.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setting defaults live in ONE place: the bundled config.yml. Bukkit's getX(path, default) returns the
 * code's default (not config.yml's) when a server's older config file lacks the key, which once showed
 * the old wardrobe menu on updated servers. So: no code defaults, and every key read must exist in config.yml.
 */
class ConfigDefaultsTest {

    private static final Pattern READ = Pattern.compile("(?:getConfig\\(\\)|\\bc)\\.get(?:String|Boolean|Int|Double|Long|IntegerList|StringList)\\(\"([a-z0-9.\\-]+)\"(,?)");

    @Test
    void everySettingReadHasItsDefaultInConfigYml() throws Exception {
        YamlConfiguration defaults;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> problems = new ArrayList<>();
        int reads = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = READ.matcher(src);
                while (m.find()) {
                    String path = m.group(1);
                    if (!path.contains(".") && !src.contains("getConfig().get")) continue;
                    if (m.group(1).startsWith("cosmetics.") || src.substring(Math.max(0, m.start() - 12), m.start() + 12).contains("getConfig()")) {
                        reads++;
                        if (!defaults.contains(path)) problems.add(f.getFileName() + ": '" + path + "' is not in config.yml");
                        if (!m.group(2).isEmpty()) problems.add(f.getFileName() + ": '" + path + "' has a code default (use config.yml)");
                    }
                }
            }
        }
        assertTrue(reads > 10, "found " + reads + " reads");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
