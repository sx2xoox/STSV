package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem.CosmeticSlot;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped cosmetics: every slot has some, effects use particles that need no extra data, pets have models. */
class CosmeticContentTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    @Test
    void shippedCosmeticsAreUsable() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/cosmetics.yml").toFile());
        Map<CosmeticSlot, Integer> perSlot = new EnumMap<>(CosmeticSlot.class);
        List<String> problems = new ArrayList<>();
        for (String key : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(key + ".cosmetic");
            if (c == null) continue;
            CosmeticSlot slot = CosmeticSlot.parse(c.getString("slot", "head"));
            if (slot == null) {
                problems.add(key + ": slot");
                continue;
            }
            perSlot.merge(slot, 1, Integer::sum);
            String particle = c.getString("particle");
            if (particle != null) {
                try {
                    Particle p = Particle.valueOf(particle.toUpperCase(Locale.ROOT));
                    if (p.getDataType() != Void.class && p.getDataType() != Particle.DustOptions.class) problems.add(key + ": " + particle + " needs data");
                    if (p.getDataType() == Particle.DustOptions.class && c.getString("color") == null) problems.add(key + ": dust without color");
                } catch (IllegalArgumentException e) {
                    problems.add(key + ": no particle " + particle);
                }
            }
            if ((slot == CosmeticSlot.TRAIL || slot == CosmeticSlot.KILL) && particle == null && !c.getBoolean("lightning")) problems.add(key + ": no effect");
            String model = y.getString(key + ".model");
            if (slot != CosmeticSlot.TRAIL && slot != CosmeticSlot.KILL && (model == null || !Files.exists(PACK.resolve("models/" + model + ".bbmodel")))) {
                problems.add(key + ": model " + model);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        for (CosmeticSlot s : CosmeticSlot.values()) assertTrue(perSlot.getOrDefault(s, 0) >= 2, s + ": " + perSlot);
        assertEquals(CosmeticSlot.values().length, perSlot.size());
    }

    @Test
    void petsWalkBehindTheOwnersRight() {
        // facing south (yaw 0): right = west (-x), behind = north (-z)
        org.bukkit.util.Vector v = CosmeticPets.spot(new org.bukkit.Location(null, 0, 64, 0, 0, 0));
        assertEquals(-CosmeticPets.SIDE, v.getX(), 1e-9);
        assertEquals(-CosmeticPets.BEHIND, v.getZ(), 1e-9);
        assertEquals(64, v.getY(), 1e-9);
        // facing east (yaw -90): right = south (+z), behind = west (-x)
        v = CosmeticPets.spot(new org.bukkit.Location(null, 0, 64, 0, -90, 0));
        assertEquals(-CosmeticPets.BEHIND, v.getX(), 1e-9);
        assertEquals(CosmeticPets.SIDE, v.getZ(), 1e-9);
    }

    @Test
    void rainbowCycles() {
        int n = CosmeticEffects.RAINBOW_TICKS;
        // goes round the colours in 1.5 s, so a trail (marks every 3 ticks, living about a second) shows several
        assertEquals(30, n);
        assertTrue(!CosmeticEffects.color("rainbow", 0).equals(CosmeticEffects.color("rainbow", n / 2)));
        assertEquals(CosmeticEffects.color("rainbow", 3), CosmeticEffects.color("rainbow", 3 + n));
        assertEquals(0xff8800, CosmeticEffects.color("#ff8800", 0).asRGB());
    }
}
