package dev.stsv.core.cosmetic;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticStoreTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void ownershipSurvivesARestart(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore s = new CosmeticStore(db);
        s.load(null);
        assertTrue(s.grant(a, "stsv:tomato_mask"));
        assertFalse(s.grant(a, "stsv:tomato_mask"), "second grant is not new");
        s.grant(a, "stsv:eggplant_mask");
        s.setEquipped(a, "stsv:eggplant_mask");
        s.grant(b, "stsv:mushroom_mask");
        db.close();   // waits for the queued writes

        Db db2 = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore r = new CosmeticStore(db2);
        r.load(null);
        assertEquals(Set.of("stsv:tomato_mask", "stsv:eggplant_mask"), r.owned(a));
        assertEquals(Optional.of("stsv:eggplant_mask"), r.equipped(a));
        assertEquals(Set.of("stsv:mushroom_mask"), r.owned(b));
        db2.close();
    }

    @Test
    void oldYamlFileIsImportedOnce(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        Path yml = dir.resolve("cosmetics.yml");
        Files.writeString(yml, a + ":\n  owned: [stsv:tomato_mask, stsv:mushroom_mask]\n  equipped: stsv:tomato_mask\nnot-a-uuid: {}\n");
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore s = new CosmeticStore(db);
        s.load(yml);
        assertEquals(Set.of("stsv:tomato_mask", "stsv:mushroom_mask"), s.owned(a));
        assertEquals(Optional.of("stsv:tomato_mask"), s.equipped(a));
        assertFalse(Files.exists(yml));
        assertTrue(Files.exists(dir.resolve("cosmetics.yml.imported")));
        db.close();
    }

    @Test
    void revokingTheWornCosmeticTakesItOff(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore s = new CosmeticStore(db);
        s.load(null);
        s.grant(a, "stsv:tomato_mask");
        s.setEquipped(a, "stsv:tomato_mask");
        assertTrue(s.revoke(a, "stsv:tomato_mask"));
        assertEquals(Optional.empty(), s.equipped(a));
        assertFalse(s.revoke(a, "stsv:tomato_mask"));
        db.close();
    }

    @Test
    void migrationsRunOnceAndUpsertWorksInBothDialects(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        var steps = java.util.List.of(java.util.List.of("CREATE TABLE t (k VARCHAR(8) PRIMARY KEY, v INT)"));
        db.migrate("test", steps);
        db.migrate("test", steps);   // second time: nothing to do (would fail on CREATE TABLE)
        String up = db.upsert("t", java.util.List.of("k"), java.util.List.of("v"));
        db.execute(up, "a", 1);
        db.execute(up, "a", 2);
        Integer v = db.sync(c -> Db.query(c, "SELECT v FROM t WHERE k = 'a'", rs -> rs.getInt(1)).getFirst());
        assertEquals(Integer.valueOf(2), v);
        db.close();
        Db mysql = new Db(() -> null, Db.Dialect.MYSQL, 1, LOG, null);
        assertEquals("INSERT INTO t (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)", mysql.upsert("t", java.util.List.of("k"), java.util.List.of("v")));
        mysql.close();
    }
}
