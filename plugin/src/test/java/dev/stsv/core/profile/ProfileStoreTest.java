package dev.stsv.core.profile;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void statsAreCountedAllTimeAndPerPeriod(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        ProfileStore s = new ProfileStore(db);
        s.init();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.seen(a, "Alpha", 1);
        s.seen(b, "Beta", 1);

        s.add(a, "mob_kills", 5);                       // before any period: all-time only
        s.start("economy-1", "경제 서버 1기", 100);
        s.add(a, "mob_kills", 2);
        s.add(b, "mob_kills", 7);
        s.add(b, "deaths", 1);

        assertEquals(7.0, s.stats(a, ProfileStore.ALL_TIME).get().get("mob_kills"));
        assertEquals(2.0, s.stats(a, "economy-1").get().get("mob_kills"));
        List<ProfileStore.Rank> top = s.top("economy-1", "mob_kills", 3).get();
        assertEquals(List.of("Beta", "Alpha"), top.stream().map(ProfileStore.Rank::name).toList());

        Map<String, List<ProfileStore.Rank>> hall = s.end(200, List.of("mob_kills", "deaths", "playtime"), 3);
        assertEquals(2, hall.get("mob_kills").size());
        assertEquals("Beta", hall.get("deaths").getFirst().name());
        assertTrue(hall.get("playtime").isEmpty());
        assertTrue(s.current().isEmpty());

        // after the period: counted all-time only, the old period is frozen
        s.add(b, "mob_kills", 100);
        assertEquals(7.0, s.stats(b, "economy-1").get().get("mob_kills"));
        assertEquals(107.0, s.stats(b, ProfileStore.ALL_TIME).get().get("mob_kills"));
        assertEquals("Beta", s.hall("economy-1").get().get("mob_kills").getFirst().name());
        assertThrows(IllegalArgumentException.class, () -> s.start("economy-1", "again", 300), "ids are never reused");
        db.close();
    }

    @Test
    void runningPeriodSurvivesRestartAndTitlesWork(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        ProfileStore s = new ProfileStore(db);
        s.init();
        s.seen(a, "Alpha", 1);
        s.start("kingdom-1", "왕국 1기", 10);
        s.grantTitle(a, "stsv:test_title_orange", 11);
        s.equipTitle(a, "stsv:test_title_orange");
        assertThrows(IllegalStateException.class, () -> s.start("x", "x", 12), "one period at a time");
        db.close();

        Db db2 = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        ProfileStore r = new ProfileStore(db2);
        r.init();
        assertEquals("kingdom-1", r.current().orElseThrow().id());
        ProfileStore.Titles t = r.titles(a).get();
        assertTrue(t.owned().contains("stsv:test_title_orange"));
        assertEquals("stsv:test_title_orange", t.equipped());
        r.revokeTitle(a, "stsv:test_title_orange").get();
        assertEquals(null, r.titles(a).get().equipped(), "revoking the worn title takes it off");
        assertEquals(a, r.findByName("alpha").get().orElseThrow());
        db2.close();
    }
}
