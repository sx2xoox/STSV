package dev.stsv.economy.secret.designer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The designer's pure rules ({@link Parts}), the 13th machine's angles, and its settings file. */
class DesignerPartsTest {

    private static final Map<String, Integer> RIB = Map.of("minecraft:bone", 1, "minecraft:iron_ingot", 1);

    @Test
    void stagesAskForThePartsInOrder() {
        assertNull(Parts.forStage(0));
        assertEquals("rib", Parts.forStage(1));
        assertEquals("vein", Parts.forStage(2));
        assertEquals("lens", Parts.forStage(3));
        assertEquals("heart", Parts.forStage(4));
        assertNull(Parts.forStage(5));
    }

    @Test
    void aLaneMustHoldExactlyThoseThings() {
        assertTrue(Parts.exactly(Arrays.asList("minecraft:bone", "minecraft:iron_ingot", null), List.of(1, 1, 0), RIB));
        assertTrue(Parts.exactly(Arrays.asList(null, "minecraft:iron_ingot", "minecraft:bone"), List.of(0, 5, 3), RIB), "more is fine");
        assertTrue(Parts.exactly(Arrays.asList("minecraft:bone", "minecraft:bone", "minecraft:iron_ingot"), List.of(1, 1, 1), RIB));
        assertFalse(Parts.exactly(Arrays.asList("minecraft:bone", null, null), List.of(1, 0, 0), RIB), "one missing");
        assertFalse(Parts.exactly(Arrays.asList("minecraft:bone", "minecraft:iron_ingot", "minecraft:coal"), List.of(1, 1, 1), RIB), "something else in it");
        assertFalse(Parts.exactly(Arrays.asList(null, null, null), List.of(0, 0, 0), RIB));
        assertFalse(Parts.exactly(Arrays.asList("minecraft:bone", "minecraft:iron_ingot", null), List.of(1, 1, 0), Map.of()));
        assertFalse(Parts.exactly(Arrays.asList("minecraft:bone", "minecraft:iron_ingot"), List.of(1, 1), Map.of("minecraft:bone", 2, "minecraft:iron_ingot", 1)),
                "not enough");
    }

    @Test
    void theHeartWantsAKindNotUsedYet() {
        Set<String> used = new HashSet<>(List.of("forge", "still", "gem_bench"));
        assertTrue(Parts.kindFits("forge", "forge", used));
        assertFalse(Parts.kindFits("forge", "still", used));
        assertTrue(Parts.kindFits(Parts.ANY, "kitchen", used));
        assertFalse(Parts.kindFits(Parts.ANY, "still", used));
        assertFalse(Parts.kindFits(Parts.ANY, null, used));
    }

    @Test
    void theEyeLooksWhereItShould() {
        assertArrayEquals(new float[]{0, 0}, Parts.look(0, 0, 5), 1e-5f);                       // straight ahead (+z)
        assertEquals(Math.PI / 2, Parts.look(3, 0, 0)[0], 1e-5);                                 // to +x
        assertEquals(Math.PI, Math.abs(Parts.look(0, 0, -2)[0]), 1e-5);                          // behind
        assertTrue(Parts.look(0, 2, 2)[1] < 0, "up is a negative pitch");
        assertEquals(-Math.PI / 4, Parts.look(0, 2, 2)[1], 1e-5);
        assertArrayEquals(new float[]{0, 0}, Parts.look(0, 0, 0), 1e-5f);
        // the lens turns the short way round
        assertEquals((float) (Math.PI - 0.1 + 2 * Math.PI), Thirteenth.closest((float) (3 * Math.PI), (float) (Math.PI - 0.1)), 1e-4);
        assertEquals(0.5f, Thirteenth.closest(0.4f, 0.5f), 1e-6);
    }

    @Test
    void theBodyBreathesAboutItsCentre() {
        float[] c = Parts.breathe(new float[]{0.5f, 1.2f, 0.5f}, new float[]{0.5f, 1.0f, 0.5f}, 1.1f);
        assertArrayEquals(new float[]{0.5f, 1.22f, 0.5f}, c, 1e-5f);
    }

    @Test
    void placesRoundTrip() {
        Object[] w = Parts.decode(Parts.encode("world", -12, 64, 300));
        assertNotNull(w);
        assertEquals("world", w[0]);
        assertEquals(-12, w[1]);
        assertEquals(64, w[2]);
        assertEquals(300, w[3]);
        assertNull(Parts.decode("world 1 2"));
        assertNull(Parts.decode("world a b c"));
        assertNull(Parts.decode(null));
    }

    @Test
    void theThirteenthMachineHasItsPieces() {
        Set<Thirteenth.Group> groups = new HashSet<>();
        for (Thirteenth.Piece p : Thirteenth.PIECES) {
            groups.add(p.group());
            assertTrue(p.block().startsWith("minecraft:"), p.block());
            assertTrue(p.sx() > 0 && p.sy() > 0 && p.sz() > 0);
            assertTrue(p.y() >= 0 && p.y() < 2, "within two blocks: " + p);
        }
        assertEquals(Set.of(Thirteenth.Group.values()), groups);
    }

    @Test
    void theSettingsFileHasWhatTheCodeReads() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(System.getProperty("stsv.content"), "economy/secrets/designer.yml"));
        for (String id : Parts.ORDER) {
            assertTrue(y.getString("parts." + id + ".item", "").startsWith("economy:designer_"), id);
            assertNotNull(y.getString("parts." + id + ".machine"), id);
        }
        assertEquals(Set.of("minecraft:bone", "minecraft:iron_ingot"), y.getConfigurationSection("parts.rib.in").getKeys(false));
        assertEquals(Set.of("minecraft:redstone", "minecraft:vine"), y.getConfigurationSection("parts.vein.in").getKeys(false));
        assertEquals(Set.of("minecraft:spider_eye", "minecraft:glass"), y.getConfigurationSection("parts.lens.in").getKeys(false));
        // the three fixed parts are made in three different kinds of machine, and the heart in a fourth
        Set<String> kinds = new HashSet<>();
        for (String id : List.of("rib", "vein", "lens")) kinds.add(y.getString("parts." + id + ".machine"));
        assertEquals(3, kinds.size());
        assertEquals(Parts.ANY, y.getString("parts.heart.machine"));
        assertEquals(4, y.getList("memo.pages").size(), "one note per part");
        assertEquals(List.of("고마워.", "이제 내가 돌게."), y.getStringList("memo.final"));
        assertEquals(300_000, y.getLong("rewards.money"));
        assertEquals("economy:designer_heir", y.getString("rewards.title"));
        assertEquals(List.of(0, 5), y.getIntegerList("start.hours"));
        assertEquals(20, y.getInt("start.min-level"));
    }
}
