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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped cosmetics: every slot has some, effects use particles that need no extra data, pets have models. */
class CosmeticContentTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    @Test
    void shippedCosmeticsAreUsable() {
        // every items file that holds cosmetics (cosmetics.yml and the later batches)
        YamlConfiguration y = new YamlConfiguration();
        try (var files = Files.list(PACK.resolve("items"))) {
            for (Path f : files.filter(f -> f.getFileName().toString().startsWith("cosmetics")).sorted().toList()) {
                YamlConfiguration one = YamlConfiguration.loadConfiguration(f.toFile());
                for (String key : one.getKeys(false)) y.set(key, one.get(key));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
            boolean effect = slot == CosmeticSlot.TRAIL || slot == CosmeticSlot.KILL || slot == CosmeticSlot.AURA;
            // a donation-grade effect may be a model instead of particles (footprints, a worn aura, a kill burst)
            boolean drawn = c.contains("print") || c.contains("worn") || c.contains("burst");
            if (effect && particle == null && !c.getBoolean("lightning") && !drawn) problems.add(key + ": no effect");
            String model = y.getString(key + ".model");
            if (!effect && (model == null || !Files.exists(PACK.resolve("models/" + model + ".bbmodel")))) {
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

    /**
     * Donation-only cosmetics: sold in the donation shop and nowhere else (no voucher to trade, not in the gacha), and
     * their sets are whole (each set named in cosmetic_sets.yml has at least two pieces, each piece's set exists).
     */
    @Test
    void donationCosmeticsAreOnlyInTheDonationShop() throws Exception {
        Map<String, String> setOf = new java.util.HashMap<>();
        List<String> exclusive = new ArrayList<>();
        java.util.Set<String> vouchered = new java.util.HashSet<>();
        try (var files = Files.list(PACK.resolve("items"))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String key : y.getKeys(false)) {
                    String v = y.getString(key + ".voucher.cosmetic");
                    if (v != null) vouchered.add(v.contains(":") ? v.substring(v.indexOf(':') + 1) : v);
                    ConfigurationSection c = y.getConfigurationSection(key + ".cosmetic");
                    if (c == null) continue;
                    if ("donation".equals(c.getString("exclusive"))) exclusive.add(key);
                    if (c.getString("set") != null) setOf.put(key, c.getString("set"));
                }
            }
        }
        assertEquals(27, exclusive.size(), exclusive.toString());   // 청룡 9 · 은하수 9 · 아기 오리 9
        List<String> pool = YamlConfiguration.loadConfiguration(PACK.resolve("gacha.yml").toFile()).getStringList("pool");
        YamlConfiguration shop = YamlConfiguration.loadConfiguration(PACK.resolve("donation_shop.yml").toFile());
        java.util.Set<String> sold = new java.util.HashSet<>();
        for (String product : shop.getConfigurationSection("products").getKeys(false)) {
            for (String cmd : shop.getStringList("products." + product + ".commands")) {
                String[] w = cmd.trim().split("\\s+");
                if (w.length == 5 && w[1].equals("치장") && w[2].equals("지급")) sold.add(w[4].substring(w[4].indexOf(':') + 1));
            }
        }
        for (String id : exclusive) {
            assertFalse(vouchered.contains(id), id + ": a donation cosmetic has no voucher");
            assertFalse(pool.contains("stsv:" + id), id + ": not in the gacha");
            assertTrue(sold.contains(id), id + ": sold in the donation shop");
        }
        YamlConfiguration sets = YamlConfiguration.loadConfiguration(PACK.resolve("cosmetic_sets.yml").toFile());
        for (String set : sets.getConfigurationSection("sets").getKeys(false)) {
            assertTrue(setOf.values().stream().filter(set::equals).count() >= 2, set + ": at least two pieces");
        }
        for (var e : setOf.entrySet()) assertTrue(sets.isConfigurationSection("sets." + e.getValue()), e.getKey() + ": set " + e.getValue());
    }
}
