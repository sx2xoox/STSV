package dev.stsv.economy.fishing;

import dev.stsv.core.world.WorldRules;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.land.Land;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 통발 and 가두리 (TrapSpec): a trap's catch-up (one catch a cycle, part-cycles carried over, nothing past its storage, a full trap
 * standing still), a pen's growth (on feed, up to its capacity, nothing without a breeding pair), the fisher's job effects from
 * jobs.yml (faster, better, never a legend) and where they may be placed. Also the boat bonus of the rod (FishingModule).
 */
class TrapSpecTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final long MIN = 60_000L;

    static YamlConfiguration yaml(String file) {
        return YamlConfiguration.loadConfiguration(PACK.resolve(file).toFile());
    }

    static TrapSpec spec() {
        List<String> problems = new ArrayList<>();
        TrapSpec s = TrapSpec.parse(yaml("fish.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return s;
    }

    static JobSpec.Job fisher() {
        List<String> problems = new ArrayList<>();
        JobSpec j = JobSpec.parse(yaml("jobs.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return j.jobs().get("fisher");
    }

    // ------------------------------------------------------------------------------------------ traps: time

    @Test
    void aTrapCatchesOneACycleAndCarriesThePartCycleOver() {
        long cycle = 10 * MIN;
        TrapSpec.Step s = TrapSpec.accumulate(0, 35 * MIN, cycle, 0, 8);
        assertEquals(3, s.catches(), "35 minutes at 10 a catch");
        assertEquals(30 * MIN, s.last(), "the 5 minutes into the fourth carry over");
        TrapSpec.Step t = TrapSpec.accumulate(s.last(), 41 * MIN, cycle, 3, 8);
        assertEquals(1, t.catches(), "the carried 5 minutes and 6 more make the fourth");
        assertEquals(0, TrapSpec.accumulate(0, 9 * MIN, cycle, 0, 8).catches(), "not a whole cycle yet");
        assertEquals(0, TrapSpec.accumulate(50 * MIN, 40 * MIN, cycle, 0, 8).catches(), "a clock that went back catches nothing");
        assertEquals(40 * MIN, TrapSpec.accumulate(50 * MIN, 40 * MIN, cycle, 0, 8).last(), "... and starts its cycle again");
    }

    @Test
    void catchUpNeverPassesTheStorageAndAFullTrapStandsStill() {
        long cycle = 10 * MIN;
        // a week away: never more than the room left, the work bounded by it
        TrapSpec.Step week = TrapSpec.accumulate(0, 7 * 24 * 60 * MIN, cycle, 0, 8);
        assertEquals(8, week.catches());
        assertEquals(7 * 24 * 60 * MIN, week.last(), "full: it stood still, the time past full is not banked");
        assertEquals(3, TrapSpec.accumulate(0, 7 * 24 * 60 * MIN, cycle, 5, 8).catches(), "only the room left");
        TrapSpec.Step full = TrapSpec.accumulate(0, 100 * MIN, cycle, 8, 8);
        assertEquals(0, full.catches());
        assertEquals(100 * MIN, full.last(), "emptied, a full trap's next catch is a whole cycle away");
        assertEquals(cycle, TrapSpec.untilNext(full.last(), 100 * MIN, cycle, 0, 8));
        // exactly filling it: also stands still from then on
        TrapSpec.Step exact = TrapSpec.accumulate(0, 80 * MIN + 1, cycle, 0, 8);
        assertEquals(8, exact.catches());
        assertEquals(0, TrapSpec.untilNext(0, 80 * MIN, cycle, 8, 8), "full: no next catch");
    }

    @Test
    void theContentTrapFillsInAboutAnHourForATendingFisher() {
        TrapSpec s = spec();
        JobSpec.Job f = fisher();
        long lv30 = TrapSpec.cycleMs(s.trap().minutes(), f.range("trap-speed", 30, 30));
        long lv1 = TrapSpec.cycleMs(s.trap().minutes(), f.range("trap-speed", 1, 30));
        long anyone = TrapSpec.cycleMs(s.trap().minutes(), 0);
        assertTrue(lv30 < lv1 && lv1 < anyone, "a fisher's trap is faster, more so with the level");
        assertEquals(Math.round(s.trap().minutes() * MIN), anyone, "anyone else: the plain cycle");
        assertTrue(s.trap().storage() * lv30 >= 60 * MIN, "a Lv30 fisher emptying it every hour never finds it full (storage >= an hour)");
        assertTrue(s.trap().storage() * anyone <= 3 * 60 * MIN, "left alone it stops within a few hours: tending pays");
        assertEquals(1000L, TrapSpec.cycleMs(0.001, 0), "never under a second");
        assertEquals(Math.round(10 * MIN * 0.2), TrapSpec.cycleMs(10, 5), "the speed cut stops at 80%");
    }

    // ------------------------------------------------------------------------------------------ traps: what they catch

    @Test
    void aFisherCatchesBetterAndNoTrapCatchesALegend() {
        TrapSpec s = spec();
        JobSpec.Job f = fisher();
        TrapSpec.Odds anyone = s.odds(0, 0);
        TrapSpec.Odds lv1 = s.odds(f.range("trap-uncommon", 1, 30), f.range("trap-rare", 1, 30));
        TrapSpec.Odds lv30 = s.odds(f.range("trap-uncommon", 30, 30), f.range("trap-rare", 30, 30));
        assertTrue(anyone.uncommon() < lv1.uncommon() && lv1.uncommon() < lv30.uncommon());
        assertTrue(anyone.rare() < lv1.rare() && lv1.rare() < lv30.rare());
        assertTrue(lv30.rare() <= 0.05, "rares stay rare in a trap: " + lv30.rare());
        Random r = new Random(7);
        int[] seen = new int[4];
        for (int i = 0; i < 200_000; i++) seen[TrapSpec.roll(lv30, r)]++;
        assertEquals(0, seen[3], "never a legend");
        assertEquals(lv30.rare(), seen[2] / 200_000.0, 0.003);
        assertEquals((1 - lv30.rare()) * lv30.uncommon(), seen[1] / 200_000.0, 0.005);
        // in every water a trap finds commons, uncommons and (rarely) rares of any time, never a fish of a moment or a legend
        List<String> problems = new ArrayList<>();
        FishSpec fish = FishSpec.parse(yaml("fish.yml"), problems);
        List<FishSpec.Rarity> rar = new ArrayList<>(fish.rarities().values());
        for (String h : List.of("sea", "deep", "warm", "cold", "river", "coldriver", "swamp", "mangrove", "jungle", "cave", "mushroom")) {
            for (int k = 0; k < 3; k++) {
                for (int i = 0; i < 30; i++) {
                    FishSpec.Species sp = fish.pick(h, TrapSpec.ANY_TIME, rar.get(k), r);
                    assertNotNull(sp, h + " " + k);
                    assertTrue(sp.when() == null, h + ": " + sp.id() + " bites only at a moment");
                    assertTrue(fish.rarities().get(sp.rarity()).index() <= k, h + ": " + sp.id());
                }
            }
        }
    }

    /** A trap's day, as the parity model counts it: emptied every active hour for `hours`, then left overnight. */
    static double dayValue(TrapSpec s, double cut, TrapSpec.Odds odds, int hours, double common, double uncommon, double rare) {
        double cycleMin = TrapSpec.cycleMs(s.trap().minutes(), cut) / (double) MIN;
        double catches = hours * Math.min(s.trap().storage(), 60 / cycleMin) + s.trap().storage();
        double each = odds.rare() * rare + (1 - odds.rare()) * (odds.uncommon() * uncommon + (1 - odds.uncommon()) * common);
        return catches * each;
    }

    @Test
    void anyoneCanTrapButAFisherEarnsClearlyMore() {
        TrapSpec s = spec();
        JobSpec.Job f = fisher();
        // the sea's prices (fish.yml): commons 5 G, uncommons about 14 G, rares about 58 G
        double anyone = dayValue(s, 0, s.odds(0, 0), 3, 5, 14, 58);
        double lv1 = dayValue(s, f.range("trap-speed", 1, 30), s.odds(f.range("trap-uncommon", 1, 30), f.range("trap-rare", 1, 30)), 3, 5, 14, 58);
        double lv30 = dayValue(s, f.range("trap-speed", 30, 30), s.odds(f.range("trap-uncommon", 30, 30), f.range("trap-rare", 30, 30)), 3, 5, 14, 58);
        assertTrue(anyone > 0);
        assertTrue(lv1 >= 1.4 * anyone, "a new fisher's trap already pays 40% more: " + lv1 + " vs " + anyone);
        assertTrue(lv30 >= 2 * anyone, "a Lv30 fisher's trap pays at least twice anyone's: " + lv30 + " vs " + anyone);
        // more traps, more fish: a trap's day does not depend on how many others there are (no cap but the chunk's, for lag)
        assertTrue(s.trap().perChunk() >= 4 && s.trap().perChunk() <= 16, "a chunk holds a handful, not hundreds");
    }

    // ------------------------------------------------------------------------------------------ pens

    @Test
    void aPenGrowsOnFeedUpToItsCapacity() {
        TrapSpec.Pen pen = new TrapSpec.Pen(60, 32, 16, 0.25, 16, 2, 2);
        long cycle = 60 * MIN;
        TrapSpec.Growth g = TrapSpec.grow(16, 16, 0, 2 * 60 * MIN + 30 * MIN, cycle, pen);
        assertEquals(25, g.count(), "16 -> 20 -> 25");
        assertEquals(14, g.feed(), "a feed a growth");
        assertEquals(2 * 60 * MIN, g.last(), "the half hour carries over");
        // a night: it fills up and stops there, eating no more
        TrapSpec.Growth night = TrapSpec.grow(16, 16, 0, 21 * 60 * MIN, cycle, pen);
        assertEquals(32, night.count());
        assertEquals(12, night.feed(), "16 -> 20 -> 25 -> 31 -> 32: four feed");
        assertEquals(21 * 60 * MIN, night.last(), "full: it stands still");
        // small pens grow at least a fish a growth; one fish alone does not breed; no feed, no growth
        assertEquals(3, TrapSpec.grow(2, 16, 0, cycle, cycle, pen).count());
        assertEquals(1, TrapSpec.grow(1, 16, 0, 10 * cycle, cycle, pen).count());
        TrapSpec.Growth hungry = TrapSpec.grow(10, 0, 0, 10 * cycle, cycle, pen);
        assertEquals(10, hungry.count());
        assertEquals(10 * cycle, hungry.last(), "it waits from now: feeding it starts a fresh cycle");
        // a year away costs no more than its feed in steps, and runs out of feed
        TrapSpec.Growth year = TrapSpec.grow(2, 3, 0, 365L * 24 * 60 * MIN, cycle, pen);
        assertEquals(5, year.count(), "2 -> 3 -> 4 -> 5 on three feed");
        assertEquals(0, year.feed());
    }

    @Test
    void harvestingLeavesTheBreedersUnlessAllAreTaken() {
        TrapSpec s = spec();
        TrapSpec.Pen pen = s.pen();
        assertEquals(pen.capacity() - pen.keep(), TrapSpec.harvest(pen.capacity(), false, pen));
        assertEquals(0, TrapSpec.harvest(pen.keep() - 3, false, pen), "fewer than the keep: nothing");
        assertEquals(20, TrapSpec.harvest(20, true, pen), "sneaking: all");
        assertTrue(pen.keep() >= pen.seed(), "what a harvest leaves can breed");
        // the content pen: a fisher's grows faster, and a harvested pen refills to its capacity overnight
        JobSpec.Job f = fisher();
        long lv30 = TrapSpec.cycleMs(pen.minutes(), f.range("trap-speed", 30, 30));
        long anyone = TrapSpec.cycleMs(pen.minutes(), 0);
        assertTrue(lv30 < anyone);
        TrapSpec.Growth night = TrapSpec.grow(pen.keep(), pen.feed(), 0, 21 * 60 * MIN, anyone, pen);
        assertEquals(pen.capacity(), night.count(), "anyone's pen refills overnight");
        assertTrue(night.feed() > 0, "on less than its feed");
        List<String> problems = new ArrayList<>();
        FishSpec fish = FishSpec.parse(yaml("fish.yml"), problems);
        List<FishSpec.Rarity> rar = new ArrayList<>(fish.rarities().values());
        assertTrue(TrapSpec.penFish(rar.get(0)) && TrapSpec.penFish(rar.get(1)), "commons and uncommons breed in a pen");
        assertFalse(TrapSpec.penFish(rar.get(2)) || TrapSpec.penFish(rar.get(3)), "rares and legends do not (they keep their size)");
    }

    // ------------------------------------------------------------------------------------------ where, and the boat

    @Test
    void trapsGoOnOwnLandOrInTheWildOnly() {
        UUID me = UUID.randomUUID(), other = UUID.randomUUID();
        Land mine = new Land(1, me, 0);
        Land theirs = new Land(2, other, 0);
        assertTrue(TrapModule.mayPlace(WorldRules.Kind.BUILD, mine, me, false), "my land");
        assertFalse(TrapModule.mayPlace(WorldRules.Kind.BUILD, theirs, me, false), "someone else's");
        assertFalse(TrapModule.mayPlace(WorldRules.Kind.BUILD, null, me, false), "the build world outside land");
        assertTrue(TrapModule.mayPlace(WorldRules.Kind.WILD, null, me, false), "the wild world");
        assertTrue(TrapModule.mayPlace(WorldRules.Kind.WILD, theirs, me, false), "claims sleep in the wild world");
        assertFalse(TrapModule.mayPlace(WorldRules.Kind.OTHER, null, me, false), "other worlds: only own land");
        assertTrue(TrapModule.mayPlace(WorldRules.Kind.BUILD, theirs, me, true), "an operator anywhere");
        theirs.members.put(me, 0L);
        assertTrue(TrapModule.mayPlace(WorldRules.Kind.BUILD, theirs, me, false), "a member's land is their own land too");
    }

    @Test
    void fishingFromABoatAddsTheFishersBoatBonus() {
        JobSpec.Job f = fisher();
        double finds = f.finds().chance().at(30, 30);
        double boat = f.range("boat-rare", 30, 30);
        assertTrue(boat > 0 && boat <= 0.05, "a modest bonus: " + boat);
        assertEquals(finds + boat, FishingModule.rareChance(finds, boat, true), 1e-12);
        assertEquals(finds, FishingModule.rareChance(finds, boat, false), 1e-12, "on the shore: none");
        assertEquals(0.01, FishingModule.rareChance(0.01, 0, true), 1e-12, "anyone else in a boat: their effect is 0");
        assertTrue(f.range("boat-rare", 1, 30) < boat, "it grows with the level");
        assertEquals(0, f.range("boat-rare", 0, 30), "no level in the job: nothing");
    }
}
