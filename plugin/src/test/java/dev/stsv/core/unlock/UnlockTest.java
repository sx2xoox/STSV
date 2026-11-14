package dev.stsv.core.unlock;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What players own for good (title banners, emotes, tool skins): the book, the "kind:id" words, the admin command's messages. */
class UnlockTest {

    @Test
    void theBook(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        UnlockStore s = new UnlockStore(db);
        s.init();
        s.init();           // again: nothing breaks
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(s.owned(a).get().isEmpty());
        assertTrue(s.grant(a, "banner", "fire", "donation:banner_fire", 1000).get(), "new to the player");
        assertFalse(s.grant(a, "banner", "fire", "admin:op", 2000).get(), "had it already: nothing changes");
        assertTrue(s.grant(a, "banner", "frost", "admin:op", 3000).get());
        assertTrue(s.grant(a, "emote", "twerk", "donation:emote_twerk", 4000).get());
        assertTrue(s.grant(b, "banner", "fire", "admin:op", 5000).get());
        assertEquals(Map.of("banner", Set.of("fire", "frost"), "emote", Set.of("twerk")), s.owned(a).get());
        assertEquals(Map.of("banner", Set.of("fire")), s.owned(b).get());
        assertEquals(Map.of("fire", 2, "frost", 1), s.counts("banner").get());
        // taking back: once
        assertTrue(s.revoke(a, "banner", "fire").get());
        assertFalse(s.revoke(a, "banner", "fire").get());
        assertEquals(Set.of("frost"), s.owned(a).get().get("banner"));
        assertEquals(Map.of("banner", Set.of("fire")), s.owned(b).get(), "nobody else's");
        // the same id in two kinds is two things
        assertTrue(s.grant(a, "tool_skin", "twerk", null, 6000).get());
        assertTrue(s.revoke(a, "emote", "twerk").get());
        assertEquals(Set.of("twerk"), s.owned(a).get().get("tool_skin"));
        db.close();
    }

    @Test
    void kindAndId() {
        assertArrayEquals(new String[]{"banner", "fire"}, UnlockModule.split("banner:fire"));
        assertArrayEquals(new String[]{"tool_skin", "toy_sword"}, UnlockModule.split(" tool_skin : toy_sword ".replace(" ", "")));
        assertNull(UnlockModule.split("fire"));
        assertNull(UnlockModule.split(":fire"));
        assertNull(UnlockModule.split("banner:"));
        assertNull(UnlockModule.split(null));
    }

    @Test
    void theAdminCommandsWords() {
        var messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (String key : java.util.List.of("usage", "no-player", "unknown", "given", "had", "taken", "had-not", "received", "kind-line", "list-head", "list-none", "list-line")) {
            assertTrue(messages.isString("unlock." + key), key);
        }
    }
}
