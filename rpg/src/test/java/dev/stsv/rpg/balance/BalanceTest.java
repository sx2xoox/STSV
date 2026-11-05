package dev.stsv.rpg.balance;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped config meets the plan's targets at every level. */
class BalanceTest {

    static Balance b;

    @BeforeAll
    static void load() {
        b = shipped();
    }

    static Balance shipped() {
        var in = BalanceTest.class.getResourceAsStream("/config.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        return new Balance(y.getConfigurationSection("balance"));
    }

    @Test
    void growthIsSevenPercentAndLevelFiftyIsAbout27Times() {
        assertEquals(10, b.attack(1), 1e-9);
        assertEquals(10 * 1.07, b.attack(2), 1e-9);
        double ratio = b.attack(50) / b.attack(1);
        assertTrue(ratio > 26 && ratio < 28, "Lv50 / Lv1 = " + ratio);
        assertEquals(100, b.health(1), 1e-9);
    }

    /** The reference player kills a same-level mob in its role's time, at every level. */
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20, 30, 40, 50})
    void referencePlayerKillTimesMatchTheTargets(int level) {
        for (Role r : Role.values()) {
            double seconds = b.mobHealth(level, r) / b.referenceDps(level);
            assertEquals(b.killSeconds(r), seconds, 1e-6, r + " Lv" + level);
        }
        assertEquals(2.5, b.killSeconds(Role.NORMAL));
        assertEquals(180, b.killSeconds(Role.BOSS));
    }

    /** ...and survives the target number of ordinary hits from it. */
    @ParameterizedTest
    @ValueSource(ints = {1, 10, 25, 50})
    void referencePlayerSurvivesTheTargetHits(int level) {
        double refHealth = b.health(level) * b.refGearHealth;
        double refDefense = b.defenseFor(b.refReduction, level);
        for (Role r : new Role[]{Role.NORMAL, Role.ELITE, Role.BOSS}) {
            double perHit = b.mobAttack(level, r) * (1 - b.reduction(refDefense, level));
            assertEquals(b.hitsToDown(r), refHealth / perHit, 1e-6, r + " Lv" + level);
        }
        assertEquals(0, b.mobAttack(level, Role.CRITTER));
    }

    @Test
    void reductionAndDefenseAreInverse() {
        for (int lv : new int[]{1, 17, 50}) {
            for (double r : new double[]{0.1, 0.3, 0.6}) assertEquals(r, b.reduction(b.defenseFor(r, lv), lv), 1e-9);
        }
    }

    @Test
    void defenseHasDiminishingReturnsAndACap() {
        double r1 = b.reduction(100, 10), r2 = b.reduction(200, 10), r3 = b.reduction(1_000_000, 10);
        assertTrue(r2 < 2 * r1, "doubling defense must not double the reduction");
        assertEquals(0.75, r3, 1e-9);
        // the same defense is worth less against a higher-level attacker
        assertTrue(b.reduction(100, 40) < b.reduction(100, 10));
    }

    /** Planned cumulative play time: Lv15 = 7 h, Lv35 = 28 h, Lv50 = 50 h (within 5%). */
    @Test
    void levelCurveHitsThePlannedHours() {
        assertHours(15, 7);
        assertHours(35, 28);
        assertHours(50, 50);
    }

    private static void assertHours(int level, double hours) {
        double minutes = 0;
        for (int l = 1; l < level; l++) minutes += b.minutesForLevel(l);
        assertEquals(hours, minutes / 60, hours * 0.05, "Lv" + level);
        // and the experience table really takes that long at the planned kill speed
        double exp = 0, time = 0;
        for (int l = 1; l < level; l++) {
            exp = b.expToNext(l);
            time += exp / (b.killsPerMinute * b.mobExp(l, Role.NORMAL));
        }
        assertEquals(hours, time / 60, hours * 0.05, "exp table Lv" + level);
    }

    @Test
    void expToNextGrowsAndStopsAtTheCap() {
        for (int l = 1; l < 49; l++) assertTrue(b.expToNext(l + 1) > b.expToNext(l), "Lv" + l);
        assertEquals(Long.MAX_VALUE, b.expToNext(50));
    }

    @Test
    void levelGapRules() {
        assertEquals(1, b.expFactor(10, 10));
        assertEquals(1, b.expFactor(14, 10));
        assertEquals(0.8, b.expFactor(15, 10), 1e-9);
        assertEquals(0.1, b.expFactor(40, 10), 1e-9);
        assertEquals(1, b.expFactor(5, 30), "no penalty for fighting stronger mobs");
        assertEquals(1, b.hurtFactor(10, 14));
        assertEquals(1.1, b.hurtFactor(10, 15), 1e-9);
        assertEquals(2.0, b.hurtFactor(1, 50), 1e-9);
    }

    @Test
    void eliteAndBossAreTougherAndWorthMore() {
        assertTrue(b.mobHealth(20, Role.ELITE) > 5 * b.mobHealth(20, Role.NORMAL));
        assertTrue(b.mobAttack(20, Role.ELITE) > b.mobAttack(20, Role.NORMAL));
        assertTrue(b.mobExp(20, Role.BOSS) > b.mobExp(20, Role.ELITE));
    }
}
