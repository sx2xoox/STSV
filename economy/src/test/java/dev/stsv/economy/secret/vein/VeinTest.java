package dev.stsv.economy.secret.vein;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whispering vein's pure rules: the trail's path ({@link VeinPath}), the beat judge ({@link Rhythm}), the senses, the settings. */
class VeinTest {

    // ------------------------------------------------------------------------------------------
    // the path
    // ------------------------------------------------------------------------------------------

    private static List<VeinPath.Pos> walk(long seed, int attempt, int n, java.util.function.Predicate<VeinPath.Pos> ok) {
        List<VeinPath.Pos> out = new ArrayList<>();
        VeinPath.Pos from = new VeinPath.Pos(100, -10, 100);
        int[] heading = null;
        for (int i = 0; i < n; i++) {
            Optional<VeinPath.Step> s = VeinPath.next(seed, attempt, i, from, heading, -58, 4, 7, ok);
            if (s.isEmpty()) break;
            from = s.get().at();
            heading = new int[] {s.get().hx(), s.get().hy(), s.get().hz()};
            out.add(from);
        }
        return out;
    }

    @Test
    void theSameSeedGivesTheSameTrail() {
        assertEquals(walk(42, 0, 25, p -> true), walk(42, 0, 25, p -> true));
        assertFalse(walk(42, 0, 25, p -> true).equals(walk(42, 1, 25, p -> true)), "a new attempt (after a light) is a new path");
        assertFalse(walk(42, 0, 25, p -> true).equals(walk(43, 0, 25, p -> true)));
    }

    @Test
    void stepsStayInRangeAndAboveTheFloor() {
        for (long seed = 0; seed < 200; seed++) {
            List<VeinPath.Pos> t = walk(seed, 0, 30, p -> true);
            assertEquals(30, t.size());
            VeinPath.Pos prev = new VeinPath.Pos(100, -10, 100);
            for (VeinPath.Pos p : t) {
                double d = p.dist(prev);
                assertTrue(d >= 2 && d <= 7 * 1.25 + 2, "step " + d + " (seed " + seed + ")");
                assertTrue(p.y() > -58, "never at or below the floor");
                prev = p;
            }
        }
    }

    @Test
    void itTendsDownwardsUntilTheFloor() {
        int down = 0;
        for (long seed = 0; seed < 100; seed++) {
            List<VeinPath.Pos> t = walk(seed, 0, 20, p -> true);
            if (t.getLast().y() < -10) down++;
        }
        assertTrue(down > 70, "most trails end deeper than they began: " + down);
    }

    @Test
    void theWorldDecidesWhichCandidateFits() {
        // only blocks with even x are rock: every ore lands on one
        List<VeinPath.Pos> t = walk(7, 0, 20, p -> p.x() % 2 == 0);
        assertFalse(t.isEmpty());
        for (VeinPath.Pos p : t) assertEquals(0, p.x() % 2);
        // nothing fits: no ore
        assertTrue(VeinPath.next(7, 0, 0, new VeinPath.Pos(0, 0, 0), null, -58, 4, 7, p -> false).isEmpty());
    }

    @Test
    void positionsReadBack() {
        assertEquals(Optional.of(new VeinPath.Pos(3, -40, -7)), VeinPath.Pos.decode("world 3 -40 -7"));
        assertEquals(Optional.of(new VeinPath.Pos(3, -40, -7)), VeinPath.Pos.decode(new VeinPath.Pos(3, -40, -7).encode()));
        assertTrue(VeinPath.Pos.decode("world x y").isEmpty());
        assertTrue(VeinPath.Pos.decode(null).isEmpty());
    }

    // ------------------------------------------------------------------------------------------
    // the beat
    // ------------------------------------------------------------------------------------------

    /** 1000 ms beats from t=1000, ±120 window, 13 hits, 3 misses, 4 listening beats. */
    private static Rhythm heart() {
        return new Rhythm(1000, 1000, 120, 0.85, 450, 13, 3, 4);
    }

    /** Plays beats up to time t (as the per-tick loop would). */
    private static void until(Rhythm r, long t) {
        for (long now = 0; now <= t; now += 50) if (r.due(now)) r.beat(now);
    }

    @Test
    void theFirstBeatsAreOnlyForListening() {
        Rhythm r = heart();
        until(r, 3000);   // beats at 1000 (approx), 2000, 3000
        assertEquals(Rhythm.Hit.IGNORED, r.strike(3000));
        assertEquals(0, r.misses());
    }

    @Test
    void onTheBeatHitsOffTheBeatMisses() {
        Rhythm r = heart();
        until(r, 4000);   // 4 listening beats played
        long fifth = r.next();
        until(r, fifth);
        assertEquals(Rhythm.Hit.HIT, r.strike(fifth - 100), "inside the window");
        assertEquals(Rhythm.Hit.IGNORED, r.strike(fifth + 100), "a second strike on a beat that was hit does not count twice");
        long sixth = r.next();
        assertEquals(Rhythm.Hit.HIT, r.strike(sixth - 110), "slightly early counts for the beat to come");
        until(r, sixth);
        assertEquals(Rhythm.Hit.IGNORED, r.strike(sixth + 20), "that beat was already hit");
        long seventh = r.next();
        until(r, seventh);
        assertEquals(Rhythm.Hit.MISS, r.strike(seventh + 400), "between beats");
        assertEquals(2, r.hits());
        assertEquals(1, r.misses());
    }

    @Test
    void aMissMakesItFaster() {
        Rhythm r = heart();
        until(r, 4000);
        long before = r.interval();
        r.strike(r.next() - 500);
        assertTrue(r.interval() < before);
        assertEquals(850, r.interval());
        Rhythm fast = new Rhythm(1000, 500, 120, 0.5, 450, 13, 99, 0);
        until(fast, 1000);
        for (int i = 0; i < 5; i++) fast.strike(fast.next() - 250 + i * 1000L);
        assertTrue(fast.interval() >= 450, "never faster than the floor");
    }

    @Test
    void threeMissesBreakIt() {
        Rhythm r = new Rhythm(1000, 1000, 120, 0.85, 450, 13, 3, 0);
        until(r, 1000);
        assertEquals(Rhythm.Hit.MISS, r.strike(1500));
        assertEquals(Rhythm.Hit.MISS, r.strike(2400));
        long t = 3300;
        Rhythm.Hit h = r.strike(t);
        assertEquals(Rhythm.Hit.BROKEN, h);
        assertTrue(r.over());
        assertEquals(Rhythm.Hit.IGNORED, r.strike(t + 1000));
    }

    @Test
    void thirteenOnTheBeatStopIt() {
        Rhythm r = heart();
        long now = 0;
        Rhythm.Hit last = null;
        int beats = 0;
        while (now < 60_000 && !r.over()) {
            if (r.due(now)) {
                r.beat(now);
                beats++;
                if (beats > 4) last = r.strike(now + 40);
            }
            now += 50;
        }
        assertEquals(Rhythm.Hit.DONE, last);
        assertEquals(13, r.hits());
        assertEquals(0, r.misses());
    }

    @Test
    void spammingDoesNotWork() {
        Rhythm r = heart();
        long now = 0;
        while (now < 60_000 && !r.over()) {
            if (r.due(now)) r.beat(now);
            if (r.beats() >= 4) r.strike(now);   // a swing every tick
            now += 50;
        }
        assertTrue(r.over());
        assertTrue(r.misses() >= 3, "spam breaks the heart before it stops");
        assertTrue(r.hits() < 13);
    }

    @Test
    void swingsTooCloseTogetherAreOneSwing() {
        Rhythm r = heart();
        until(r, 5000);
        long b = r.next();
        until(r, b);
        assertEquals(Rhythm.Hit.HIT, r.strike(b));
        assertEquals(Rhythm.Hit.IGNORED, r.strike(b + Rhythm.DEBOUNCE - 1));
    }

    // ------------------------------------------------------------------------------------------
    // the senses
    // ------------------------------------------------------------------------------------------

    @Test
    void closerIsFasterAndLouder() {
        assertTrue(Sense.interval(2) < Sense.interval(20));
        assertEquals(8, Sense.interval(0));
        assertEquals(40, Sense.interval(500));
        // heard loudness ≈ 1 - d / (16 × volume): falls with distance but never to nothing within 150 blocks
        double near = 1 - 3 / (16 * Sense.volume(3)), far = 1 - 40 / (16 * Sense.volume(40)), lost = 1 - 140 / (16 * Sense.volume(140));
        assertTrue(near > far && far > 0 && lost > 0, near + " " + far + " " + lost);
        assertTrue(Sense.pitch(2) > Sense.pitch(60));
    }

    @Test
    void aimingAtTheHeart() {
        // eye at 0,0,0 looking along +x; heart at 3,0,0
        assertTrue(Sense.aimed(0, 0, 0, 1, 0, 0, 3, 0, 0, 0.6, 5));
        assertTrue(Sense.aimed(0, 0, 0, 1, 0.1, 0, 3, 0, 0, 0.6, 5));
        assertFalse(Sense.aimed(0, 0, 0, 0, 0, 1, 3, 0, 0, 0.6, 5), "looking aside");
        assertFalse(Sense.aimed(0, 0, 0, -1, 0, 0, 3, 0, 0, 0.6, 5), "behind");
        assertFalse(Sense.aimed(0, 0, 0, 1, 0, 0, 9, 0, 0, 0.6, 5), "too far");
    }

    @Test
    void lightsAndLengths() {
        assertTrue(Sense.light(14));
        assertTrue(Sense.light(1));
        assertFalse(Sense.light(0));
        assertEquals(8, Sense.length(123, 20, 30, true, 8));
        Set<Integer> seen = new HashSet<>();
        for (long s = -50; s < 50; s++) {
            int n = Sense.length(s, 20, 30, false, 8);
            assertTrue(n >= 20 && n <= 30);
            seen.add(n);
        }
        assertTrue(seen.size() > 5);
    }

    // ------------------------------------------------------------------------------------------
    // the settings
    // ------------------------------------------------------------------------------------------

    @Test
    void theHeartBeatsOnlyForItsOwner() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        assertTrue(Relic.worksFor(owner.toString(), owner));
        assertFalse(Relic.worksFor(owner.toString(), other));
        assertFalse(Relic.worksFor(null, owner), "no owner: it works for nobody");
        assertTrue(Relic.stray(owner.toString(), other), "in someone else's bag: it goes home");
        assertFalse(Relic.stray(owner.toString(), owner));
        assertFalse(Relic.stray(null, other), "no owner written: left alone");
    }

    @Test
    void settingsFile() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(System.getProperty("stsv.content"), "economy/secrets/vein.yml"));
        assertTrue(y.getBoolean("enabled"));
        assertEquals(0.00005, y.getDouble("start.chance"), 1e-12);
        assertEquals(0, y.getInt("start.below-y"));
        assertEquals(List.of(20, 30), y.getIntegerList("trail.length"));
        assertEquals(8, y.getInt("trail.test-length"));
        assertEquals(13, y.getInt("heart.hits"));
        assertEquals(3, y.getInt("heart.misses"));
        assertEquals(120, y.getInt("heart.window-ms"));
        assertEquals(24, y.getInt("light.radius"));
        assertEquals(0, y.getLong("rewards.money"), "no money: the heart is the reward");
        assertEquals("economy:heart_miner", y.getString("rewards.title"));
        assertEquals("economy:vein_heart", y.getString("relic.item"));
        for (String k : List.of("start", "resume", "relocate", "heart", "glare", "again", "collapse", "retry", "end-before", "end"))
            assertFalse(y.getString("text." + k, "").isEmpty(), "text." + k);
        for (String k : List.of("heartbeat", "whisper", "crumble", "scrape", "glare", "strike", "collapse", "falling"))
            assertNotNull(y.getString("sounds." + k + ".sound"), "sounds." + k);
        for (String k : List.of("bound-line", "keep", "crumble", "returned"))
            assertFalse(y.getString("relic." + k, "").isEmpty(), "relic." + k);
        assertFalse(y.getStringList("trail.ores.stone").isEmpty());
        assertFalse(y.getStringList("trail.ores.deep").isEmpty());
        // the reward item and the title exist
        YamlConfiguration items = YamlConfiguration.loadConfiguration(new File(System.getProperty("stsv.content"), "economy/items/secrets.yml"));
        assertNotNull(items.getConfigurationSection("vein_heart"));
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(new File(System.getProperty("stsv.content"), "economy/titles/secrets.yml"));
        assertNotNull(titles.getConfigurationSection("heart_miner"));
    }
}
