package dev.stsv.core.nickname;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameIndexTest {

    private static final UUID STEVE = UUID.randomUUID(), ALEX = UUID.randomUUID(), OFF = UUID.randomUUID();

    private static NameIndex.Entry nick(UUID u, String n) {
        return new NameIndex.Entry(u, n, null, 0, 1, 0);
    }

    private static Map<UUID, String> online() {
        Map<UUID, String> m = new LinkedHashMap<>();
        m.put(STEVE, "Steve");
        m.put(ALEX, "Alex_99");
        return m;
    }

    @Test
    void uniqueByKey() {
        NameIndex i = new NameIndex();
        i.put(nick(STEVE, "Star"));
        assertTrue(i.takenByOther("STAR", ALEX), "any case");
        assertFalse(i.takenByOther("star", STEVE), "your own nickname is not taken");
        assertEquals(Optional.of(STEVE), i.owner("sTaR"));
        // changing frees the old one
        i.put(nick(STEVE, "별빛"));
        assertFalse(i.takenByOther("Star", ALEX));
        assertEquals(1, i.count());
        // removing the nickname keeps the entry (colour, counts) but frees the name
        i.put(nick(STEVE, null));
        assertTrue(i.owner("별빛").isEmpty());
        assertTrue(i.get(STEVE).isPresent());
    }

    @Test
    void aForcedSetTakesTheNicknameAway() {
        NameIndex i = new NameIndex();
        i.put(nick(STEVE, "별빛"));
        i.put(nick(ALEX, "별빛"));
        assertEquals(Optional.of(ALEX), i.owner("별빛"));
        assertNull(i.nickname(STEVE));
    }

    @Test
    void resolvesRealNamesFirstThenNicknamesThenPrefixes() {
        NameIndex i = new NameIndex();
        i.put(nick(STEVE, "홍길동"));
        i.put(nick(ALEX, "별빛"));
        i.put(nick(OFF, "오프라인"));
        Map<UUID, String> on = online();
        assertEquals(Optional.of(STEVE), i.resolve("steve", on), "account name, any case");
        assertEquals(Optional.of(STEVE), i.resolve("홍길동", on), "nickname");
        assertEquals(Optional.of(OFF), i.resolve("오프라인", on), "an offline player's nickname");
        assertEquals(Optional.of(ALEX), i.resolve("별", on), "unique start of a nickname");
        assertEquals(Optional.of(ALEX), i.resolve("alex", on), "unique start of an account name");
        assertTrue(i.resolve("오프", on).isEmpty(), "prefixes only for online players");
        assertTrue(i.resolve("없는사람", on).isEmpty());
        assertTrue(i.resolve(" ", on).isEmpty());
        // a start shared by two online players is ambiguous
        i.put(nick(ALEX, "홍당무"));
        assertTrue(i.resolve("홍", on).isEmpty());
        // an account name beats a nickname that looks the same
        i.put(nick(OFF, "Alex_99"));
        assertEquals(Optional.of(ALEX), i.resolve("Alex_99", on));
    }

    @Test
    void completesNicknamesAndAccountNames() {
        NameIndex i = new NameIndex();
        i.put(nick(STEVE, "홍길동"));
        assertEquals(List.of("Alex_99", "Steve", "홍길동"), i.complete("", online()));
        assertEquals(List.of("홍길동"), i.complete("홍", online()));
        assertEquals(List.of("Steve"), i.complete("ST", online()));
        assertEquals("홍길동", i.display(STEVE, "Steve"));
        assertEquals("Alex_99", i.display(ALEX, "Alex_99"));
    }

    @Test
    void gameMessagesNameTheNickname() {
        Component msg = Component.translatable("death.attack.player", Component.text("Steve"), Component.text("Steve2"));
        Component out = msg.replaceText(NicknameModule.nameReplacement("Steve", NickColor.solid(0xffb7c5).component("홍길동")));
        // translatable arguments are replaced, a longer name that starts the same is not
        var args = ((net.kyori.adventure.text.TranslatableComponent) out).arguments();
        assertEquals("홍길동", PlainTextComponentSerializer.plainText().serialize(args.get(0).asComponent()));
        assertEquals("Steve2", PlainTextComponentSerializer.plainText().serialize(args.get(1).asComponent()));
        Component join = Component.text("Steve joined the game");
        assertEquals("홍길동 joined the game", PlainTextComponentSerializer.plainText().serialize(
                join.replaceText(NicknameModule.nameReplacement("Steve", Component.text("홍길동")))));
        assertEquals("xSteve", PlainTextComponentSerializer.plainText().serialize(
                Component.text("xSteve").replaceText(NicknameModule.nameReplacement("Steve", Component.text("홍길동")))));
    }

    @Test
    void storeKeepsNicknamesUniqueAndSurvivesRestarts(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        NicknameStore s = new NicknameStore(db);
        s.init();
        NickColor dawn = NickColor.gradient(0xffb347, 0xff5e8a);
        assertTrue(s.save(new NameIndex.Entry(STEVE, "별빛", dawn, 1000, 1, 2), false).get().ok());
        // another player cannot take it (any case), unless forced
        assertFalse(s.save(nick(ALEX, "별빛"), false).get().ok());
        assertTrue(s.save(new NameIndex.Entry(ALEX, null, NickColor.solid(0xffffff), 0, 0, 1), false).get().ok(), "many players without a nickname");
        assertTrue(s.save(new NameIndex.Entry(OFF, null, null, 0, 0, 0), false).get().ok());
        NicknameStore.Saved forced = s.save(nick(ALEX, "별빛"), true).get();
        assertTrue(forced.ok());
        assertEquals(STEVE, forced.tookFrom());

        // a new store on the same file (a restart) loads the same
        NicknameStore again = new NicknameStore(db);
        again.init();
        NameIndex i = new NameIndex();
        again.loadAll().forEach(i::put);
        assertEquals("별빛", i.nickname(ALEX));
        assertNull(i.nickname(STEVE));
        assertEquals(dawn, i.color(STEVE), "the colour stays when the nickname is taken away");
        assertEquals(2, i.get(STEVE).orElseThrow().colorSets());
        assertEquals(1000, i.get(STEVE).orElseThrow().changedAt());
        assertEquals("별빛", again.load(ALEX).get().orElseThrow().nickname());
        db.close();
    }

    @Test
    void realNamesOfOthersAreRefused(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        dev.stsv.core.profile.ProfileStore profiles = new dev.stsv.core.profile.ProfileStore(db);
        profiles.init();
        profiles.seen(STEVE, "Steve", 1);
        NicknameStore s = new NicknameStore(db);
        s.init();
        assertTrue(s.isOthersRealName("STEVE", ALEX).get());
        assertFalse(s.isOthersRealName("Steve", STEVE).get(), "your own account name");
        assertFalse(s.isOthersRealName("Nobody", ALEX).get());
        s.save(nick(STEVE, "홍길동"), false).get();
        assertEquals(Map.of(STEVE, "Steve"), s.realNames().get());
        db.close();
    }
}
