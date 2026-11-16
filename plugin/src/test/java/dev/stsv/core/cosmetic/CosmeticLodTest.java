package dev.stsv.core.cosmetic;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 치장 거리별 단순화 (CosmeticLod): the band with its margin, the settings, the light model table, who is sent what. */
class CosmeticLodTest {

    private static final CosmeticLod.Settings S = new CosmeticLod.Settings(12, 2, 32, 10);

    @Test
    void theBandMovesOutGoingAwayAndInComingBack() {
        // from the full model: still full up to near + margin
        assertFalse(CosmeticLod.light(false, 5, S));
        assertFalse(CosmeticLod.light(false, 13.9, S));
        assertTrue(CosmeticLod.light(false, 14.1, S));
        // from the light model: still light down to near - margin
        assertTrue(CosmeticLod.light(true, 13, S));
        assertTrue(CosmeticLod.light(true, 10.1, S));
        assertFalse(CosmeticLod.light(true, 9.9, S));
        // the squared form agrees everywhere
        for (double d = 0; d < 40; d += 0.25) {
            for (boolean was : new boolean[]{false, true}) {
                assertEquals(CosmeticLod.light(was, d, S), CosmeticLod.lightSquared(was, d * d, S), d + " " + was);
            }
        }
    }

    @Test
    void walkingAlongTheEdgeDoesNotFlicker() {
        boolean light = false;
        int changes = 0;
        // a viewer pacing between 11 and 13 blocks, round and round
        for (int i = 0; i < 400; i++) {
            double d = 12 + Math.sin(i * 0.3);
            boolean now = CosmeticLod.light(light, d, S);
            if (now != light) changes++;
            light = now;
        }
        assertEquals(0, changes, "inside the margin nothing changes");
        // walking away and back: one change each way
        changes = 0;
        for (int i = 0; i <= 200; i++) {
            double d = i <= 100 ? i * 0.3 : (200 - i) * 0.3;
            boolean now = CosmeticLod.light(light, d, S);
            if (now != light) changes++;
            light = now;
        }
        assertEquals(2, changes);
    }

    @Test
    void theSettingsComeFromConfigYml() throws Exception {
        YamlConfiguration c;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            c = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String k : List.of("near", "margin", "far", "every")) assertTrue(c.contains("cosmetics.lod." + k), k);
        CosmeticLod.Settings s = CosmeticLod.Settings.of(c.getConfigurationSection("cosmetics.lod"));
        assertEquals(new CosmeticLod.Settings(12, 2, 32, 10), s);
        // the client draws up to view range x 64 blocks
        assertEquals(0.5f, s.viewRange(), 1e-6f);
        assertThrows(IllegalArgumentException.class, () -> new CosmeticLod.Settings(12, 2, 10, 10), "far inside near");
        assertThrows(IllegalArgumentException.class, () -> new CosmeticLod.Settings(12, 12, 32, 10), "margin as wide as near");
        assertThrows(IllegalArgumentException.class, () -> new CosmeticLod.Settings(12, 2, 32, 0));
    }

    @Test
    void eachViewerIsSentTheModelOfTheirBand() {
        Map<String, String> table = Map.of("stsv:model/wings", "stsv:model/wings_lod", "stsv:cape", "stsv:model/cape_lod");
        int wearer = 7, near = 8, far = 9;
        Set<Integer> nearBands = new HashSet<>(), farBands = new HashSet<>(), ownBands = new HashSet<>();
        // the bands as the main thread sets them from the distances
        if (CosmeticLod.light(false, 4, S)) nearBands.add(wearer);
        if (CosmeticLod.light(false, 20, S)) farBands.add(wearer);
        ownBands.add(wearer);   // even if it were set, the wearer sees their own full model
        assertEquals("stsv:model/wings", sent(near, wearer, nearBands, "stsv:model/wings", table));
        assertEquals("stsv:model/wings_lod", sent(far, wearer, farBands, "stsv:model/wings", table));
        assertEquals("stsv:model/wings", sent(wearer, wearer, ownBands, "stsv:model/wings", table));
        // a gacha cosmetic shows its own item id: its light model by name
        assertEquals("stsv:model/cape_lod", sent(far, wearer, farBands, "stsv:cape", table));
        // a model without a light one, or a display nobody wears: as it is
        assertEquals("stsv:model/hat", sent(far, wearer, farBands, "stsv:model/hat", table));
        assertFalse(CosmeticLod.lightFor(far, null, farBands));
        // another wearer's band is their own
        assertEquals("stsv:model/wings", sent(far, 99, farBands, "stsv:model/wings", table));
    }

    private static String sent(int viewer, int owner, Set<Integer> bands, String model, Map<String, String> table) {
        return CosmeticLod.forViewer(model, viewer, owner, null, bands, table);
    }

    /** The display as sent to everyone, worn by 7 (or pet of 7), seen by 7, by someone near and by someone far. */
    @Test
    void bandAndDetailArePickedTogether() {
        Map<String, String> table = Map.of("stsv:model/wings", "stsv:model/wings_lod", "stsv:cape", "stsv:model/cape_lod");
        String wings = NearFade.key(NearFade.Band.OTHERS, "stsv:model/wings");
        assertEquals("stsv:near_fade/near/stsv/model/wings", wings);
        Set<Integer> far = Set.of(7), near = Set.of();
        // worn: the wearer gets their own band and the full model (never light for themselves)
        assertEquals("stsv:near_fade/self/stsv/model/wings", CosmeticLod.forViewer(wings, 7, 7, null, Set.of(7), table));
        // a pet: the owner's pet band
        assertEquals("stsv:near_fade/pet/stsv/model/wings", CosmeticLod.forViewer(wings, 7, null, 7, Set.of(), table));
        // someone near: exactly as sent
        assertSame(wings, CosmeticLod.forViewer(wings, 8, 7, null, near, table));
        // someone far: the light model, still marked, in the others' band
        assertEquals("stsv:near_fade/near/stsv/model/wings_lod", CosmeticLod.forViewer(wings, 9, 7, null, far, table));
        assertEquals("stsv:near_fade/near/stsv/model/wings_lod", CosmeticLod.forViewer(wings, 9, null, 7, far, table));
        // a gacha cosmetic's own item id, marked
        assertEquals("stsv:near_fade/near/stsv/model/cape_lod",
                CosmeticLod.forViewer(NearFade.key(NearFade.Band.OTHERS, "stsv:cape"), 9, 7, null, far, table));
        // a pack without near-fade: plain models both ways
        assertEquals("stsv:model/wings_lod", CosmeticLod.forViewer("stsv:model/wings", 9, 7, null, far, table));
        assertEquals("stsv:model/wings", CosmeticLod.forViewer("stsv:model/wings", 7, 7, null, far, table));
        // not a cosmetic display: untouched
        assertSame(wings, CosmeticLod.forViewer(wings, 9, null, null, far, table));
        // a marked model takes apart again
        assertEquals(new NearFade.Marked(NearFade.Band.PET, "stsv:model/a/b"), NearFade.unwrap("stsv:near_fade/pet/stsv/model/a/b"));
        assertNull(NearFade.unwrap("stsv:model/wings"));
    }

    @Test
    void theTableFindsEveryLightModelInThePack(@TempDir Path dir) throws Exception {
        Path models = Files.createDirectories(dir.resolve("models"));
        for (String m : List.of("wings", "wings_lod", "cape", "cape_lod", "hat", "orphan_lod")) Files.writeString(models.resolve(m + ".bbmodel"), "{}");
        ContentPack pack = new ContentPack("stsv", "stsv", dir);
        CustomItem cape = cosmetic("cape_item", pack, new ModelRef(ModelRef.Kind.BBMODEL, "cape", false));
        CustomItem hat = cosmetic("hat_item", pack, new ModelRef(ModelRef.Kind.BBMODEL, "hat", false));
        Map<String, String> t = CosmeticLod.table(List.of(pack), List.of(cape, hat));
        assertEquals(Map.of("stsv:model/wings", "stsv:model/wings_lod", "stsv:model/cape", "stsv:model/cape_lod",
                "stsv:cape_item", "stsv:model/cape_lod"), t);
    }

    private static CustomItem cosmetic(String id, ContentPack pack, ModelRef model) {
        CustomItem.Cosmetic c = new CustomItem.Cosmetic(CustomItem.CosmeticSlot.BACK, CustomItem.Rarity.RARE, null, new Vector(), 1f, false,
                null, true, null, true, null);
        return new CustomItem(new NamespacedKey("stsv", id), pack, org.bukkit.Material.PAPER, id, List.of(), model, null, null, false,
                null, null, null, null, null, c, null, null);
    }
}
