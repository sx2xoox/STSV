package dev.stsv.core.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An old untouched default in a server's messages.yml becomes the new default; an owner's own text stays. */
class LangUpgradeTest {

    @Test
    void oldDefaultsUpdateEditedTextsStay() {
        YamlConfiguration user = new YamlConfiguration();
        user.set("stock.up", "<green>▲{pct}%");          // the old default
        user.set("stock.down", "<red>내 문구 ▼{pct}%");   // the owner changed it
        user.set("intro", List.of("a", "b"));            // an old default list
        user.set("gone", "removed from the jar");
        YamlConfiguration jar = new YamlConfiguration();
        jar.set("stock.up", "<#ff5f57>▲{pct}%");
        jar.set("stock.down", "<#4d9bff>▼{pct}%");
        jar.set("intro", List.of("a", "b", "c"));
        Set<String> history = Set.of(Lang.sha1("stock.up\0<green>▲{pct}%"), Lang.sha1("stock.down\0<red>▼{pct}%"), Lang.sha1("intro\0a\nb"));
        assertEquals(2, Lang.upgrade(user, jar, history));
        assertEquals("<#ff5f57>▲{pct}%", user.getString("stock.up"));
        assertEquals("<red>내 문구 ▼{pct}%", user.getString("stock.down"));
        assertEquals(List.of("a", "b", "c"), user.getStringList("intro"));
        assertEquals("removed from the jar", user.getString("gone"));
    }

    @Test
    void theShippedHistoryKnowsTheShippedFile() throws Exception {
        // every value of the jar's own messages.yml as committed is in its history (tools/messages_history.py)
        var in = getClass().getResourceAsStream("/default-messages-history.txt");
        assertTrue(in != null, "history shipped");
        Set<String> history;
        try (var r = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            history = r.lines().collect(java.util.stream.Collectors.toSet());
        }
        assertTrue(history.size() > 50);
        YamlConfiguration jar;
        try (var m = getClass().getResourceAsStream("/messages.yml")) {
            jar = YamlConfiguration.loadConfiguration(new InputStreamReader(m, StandardCharsets.UTF_8));
        }
        assertTrue(history.contains(Lang.sha1("common.none\0" + jar.getString("common.none"))), "a value unchanged since it was committed");
    }
}
