package dev.stsv.core.banner;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BannerStoreTest {

    @Test
    void wornBannerSurvivesARestart(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        BannerStore s = new BannerStore(db);
        s.init();
        assertEquals(Optional.empty(), s.worn(a).get());
        s.wear(a, "fire", 1).get();
        s.wear(b, "neon", 2).get();
        s.wear(a, "prism", 3).get();           // another one replaces it
        assertEquals(Optional.of("prism"), s.worn(a).get());
        db.close();

        Db again = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        BannerStore r = new BannerStore(again);
        r.init();
        assertEquals(Optional.of("prism"), r.worn(a).get());
        assertEquals(2, r.all().get().size());
        r.wear(a, null, 4).get();               // taken off
        assertEquals(Optional.empty(), r.worn(a).get());
        assertEquals(Optional.of("neon"), r.worn(b).get());
        again.close();
    }
}
