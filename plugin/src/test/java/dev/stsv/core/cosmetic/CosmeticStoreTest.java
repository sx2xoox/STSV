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
    void oneCosmeticPerSlotSurvivesARestart(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore s = new CosmeticStore(db);
        s.load(null);
        s.setWorn(a, "head", "stsv:crown");
        s.setWorn(a, "back", "stsv:angel_wings");
        s.setWorn(a, "back", "stsv:demon_wings");   // replaces the wings
        s.setWorn(a, "tail", "stsv:fox_tail");
        s.setWorn(a, "tail", null);
        db.close();

        Db db2 = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore r = new CosmeticStore(db2);
        r.load(null);
        assertEquals(java.util.Map.of("head", "stsv:crown", "back", "stsv:demon_wings"), r.worn(a));
        assertEquals(Optional.of("stsv:crown"), r.equipped(a));
        r.grant(a, "stsv:demon_wings");
        r.revoke(a, "stsv:demon_wings");
        assertEquals(java.util.Map.of("head", "stsv:crown"), r.worn(a), "revoking takes it off its slot");
        db2.close();
    }

    @Test
    void theOldSingleHeadCosmeticMovesToTheHeadSlot(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        // a database written by the version with one worn cosmetic (schema version 1)
        db.migrate("cosmetics", java.util.List.of(java.util.List.of(
                "CREATE TABLE IF NOT EXISTS cosmetic_owned (player CHAR(36) NOT NULL, item VARCHAR(128) NOT NULL, PRIMARY KEY (player, item))",
                "CREATE TABLE IF NOT EXISTS cosmetic_equipped (player CHAR(36) NOT NULL PRIMARY KEY, item VARCHAR(128) NOT NULL)")));
        db.execute("INSERT INTO cosmetic_owned (player, item) VALUES (?, ?)", a.toString(), "stsv:tomato_mask");
        db.execute("INSERT INTO cosmetic_equipped (player, item) VALUES (?, ?)", a.toString(), "stsv:tomato_mask");
        db.close();

        Db db2 = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        CosmeticStore r = new CosmeticStore(db2);
        r.load(null);
        assertEquals(java.util.Map.of("head", "stsv:tomato_mask"), r.worn(a));
        assertEquals(Set.of("stsv:tomato_mask"), r.owned(a));
        db2.close();
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
