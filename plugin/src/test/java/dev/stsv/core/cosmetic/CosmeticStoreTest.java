package dev.stsv.core.cosmetic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticStoreTest {

    @Test
    void ownershipSurvivesSaveAndReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data/cosmetics.yml");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        CosmeticStore s = new CosmeticStore(file);
        assertTrue(s.grant(a, "stsv:tomato_mask"));
        assertFalse(s.grant(a, "stsv:tomato_mask"), "second grant is not new");
        s.grant(a, "stsv:eggplant_mask");
        s.setEquipped(a, "stsv:eggplant_mask");
        s.grant(b, "stsv:mushroom_mask");
        s.save();
        assertFalse(Files.exists(file.resolveSibling("cosmetics.yml.tmp")), "temp file is moved into place");

        CosmeticStore r = new CosmeticStore(file);
        r.load();
        assertEquals(Set.of("stsv:tomato_mask", "stsv:eggplant_mask"), r.owned(a));
        assertEquals(Optional.of("stsv:eggplant_mask"), r.equipped(a));
        assertEquals(Set.of("stsv:mushroom_mask"), r.owned(b));
    }

    @Test
    void revokingTheWornCosmeticTakesItOff(@TempDir Path dir) {
        UUID a = UUID.randomUUID();
        CosmeticStore s = new CosmeticStore(dir.resolve("c.yml"));
        s.grant(a, "stsv:tomato_mask");
        s.setEquipped(a, "stsv:tomato_mask");
        assertTrue(s.revoke(a, "stsv:tomato_mask"));
        assertEquals(Optional.empty(), s.equipped(a));
        assertFalse(s.revoke(a, "stsv:tomato_mask"));
    }
}
