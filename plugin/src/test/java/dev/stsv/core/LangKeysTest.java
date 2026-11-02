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

/** Every message key the code asks for exists in the bundled messages.yml (no "[key]" texts in game). */
class LangKeysTest {

    private static final Pattern KEY = Pattern.compile("Lang\\.(?:raw|mm|item|items|msg)\\(\"([a-z0-9.\\-]+)\"");

    @Test
    void everyKeyExists() throws Exception {
        YamlConfiguration messages;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            messages = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(f));
                while (m.find()) {
                    found++;
                    if (!messages.contains(m.group(1))) missing.add(f.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertTrue(found > 25, "found " + found);
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }
}
