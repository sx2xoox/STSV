package dev.stsv.economy;

import dev.stsv.core.crop.CropSpec;
import dev.stsv.economy.fishing.FishSpec;
import dev.stsv.economy.fishing.Ring;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.request.RequestSpec;
import dev.stsv.economy.shop.ShopCatalog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The balance framework as executable checks, one test per rule (R0 ... R14c): what a recipe, a crop, a job bonus, the fishing
 * ring and the shop may pay against each other. A rule collects every recipe that breaks it and fails once with the list (the
 * recipe, both numbers and the scenario).
 * <p>
 * Every compared ratio and G per minute is rounded half up to 2 decimals first, as a player reads it. Values: an item is worth its
 * shop sell base; an item the shop does not buy is worth {@link #SHADOW}; a group input ("#economy:x") its median member (lo / hi =
 * the cheapest / dearest member); a vanilla tag {@link #TAG_VALUE}. A scenario ({@link World#valued}) values a recipe again with every
 * group at its typical, cheapest or dearest member, and every sized fish (rare, legend: it keeps its size and the shop pays base x
 * {@link FishSpec#priceFactor}) at the mean price factor of a typical catch ({@link #SIZE_CASES}).
 * <p>
 * Glossary: ratio = output base x amount / input value; class = how many input kinds any machine makes (R = 0, C1 = 1, C2 = 2 or
 * more); G/min = (output value - input value) per minute at base speed (no upgrade, no job); main input = the input with the most
 * value, per-main = (output value - the other inputs' value) / main count; k = a job's machine multiplier at a level,
 * (1 + double)(1 + extra + yield) / (1 - save).
 * <p>
 * The content is economy/content/economy; {@code ./gradlew test --tests dev.stsv.economy.BalanceTest -PbalanceContent=<dir>} checks
 * another tree (a directory with shop.yml, machines.yml, ...), e.g. an older one, to compare the failures.
 */
class BalanceTest {

    /** The content checked: economy/content/economy, or the tree -PbalanceContent names. */
    static final Path ROOT = System.getProperty("stsv.balance.content") != null
            ? Path.of(System.getProperty("stsv.balance.content")) : ContentTest.PACK;

    /** A closed range of a rule; it prints as "lo-hi". */
    record Range(double lo, double hi) {
        boolean has(double x) {
            return lo <= x && x <= hi;
        }

        @Override
        public String toString() {
            return py(lo) + "-" + py(hi);
        }
    }

    // ------------------------------------------------------------------------------------------ model: values
    /** Vanilla items the shop does not buy (G each); the whole numbers stay whole (see {@link #sum}). */
    static final Map<String, Number> SHADOW = linked(
            "minecraft:beef", 3, "minecraft:porkchop", 3, "minecraft:chicken", 2, "minecraft:sugar", 1, "minecraft:apple", 2,
            "minecraft:honey_bottle", 4, "minecraft:sweet_berries", 0.5, "minecraft:kelp", 0.2, "minecraft:brown_mushroom", 1,
            "minecraft:amethyst_shard", 1, "minecraft:glowstone_dust", 1, "minecraft:torch", 0.3, "minecraft:bone_meal", 0.2,
            "minecraft:clay_ball", 0.5, "minecraft:stick", 0.1, "minecraft:wheat_seeds", 0.05, "minecraft:beetroot_seeds", 0.05);
    static final Map<String, Double> TAG_VALUE = Map.of("#small_flowers", 0.2, "#saplings", 0.2, "#leaves", 0.05, "#logs", 1.0,
            "#stone_crafting_materials", 0.0);
    /** fish.yml rarities whose catch keeps its size (FishingModule). */
    static final Set<String> SIZED_RARITIES = Set.of("rare", "legend");
    /** A typical catch: the mean price factor over u = (i + 0.5) / SIZE_SAMPLES. */
    static final int SIZE_SAMPLES = 1000;

    /** Perfect hits, all perfect, fisher size bonus. */
    record SizeCase(int perfect, boolean allPerfect, double bonus) {}

    static final Map<String, SizeCase> SIZE_CASES = Map.of(
            "lv1", new SizeCase(0, false, 0.0),       // any player: no perfect hit, no fisher bonus
            "lv20", new SizeCase(2, false, 0.10));    // a Lv20 fisher's typical catch: 2 perfect hits, +10% size (R5b)
    static final List<String> JOB_MACHINES = List.of("gem_bench", "mill", "fish_bench", "forge", "kitchen", "still");
    /** The top good of each machine (full-chain multiple). */
    static final Map<String, String> CAPSTONE = linked(
            "gem_bench", "economy:jeweled_crown", "mill", "economy:jang_set", "fish_bench", "economy:seafood_feast",
            "forge", "economy:ornate_armor", "kitchen", "economy:hanjeongsik", "still", "economy:vintage_wine");

    // ------------------------------------------------------------------------------------------ R1 / R2 bands
    static final Map<String, Range> BAND = Map.of("R", new Range(1.20, 1.60), "C1", new Range(1.75, 1.85), "C2", new Range(2.15, 2.30));
    /** Inputs worth less than this have no band (flower tea, the press's junk). */
    static final double FREE_INPUT = 2.0;
    /** R2: a press ratio stays this far under the job band's floor of its class. */
    static final double PRESS_BELOW_FLOOR = 0.10;
    /** R2b: each press upgrade costs at most this share of a job machine's. */
    static final double PRESS_COST_SHARE = 0.20;
    static final double NO_LOSS = 1.00;
    // ------------------------------------------------------------------------------------------ R3b job bonuses
    static final Set<String> TIME_BONUS = Set.of("speed", "fast", "aging");      // change seconds only
    static final List<String> OUTPUT_BONUS = List.of("extra", "yield");          // each output unit +1 with chance p (one roll, chances added)
    static final Set<String> BATCH_BONUS = Set.of("double");                     // the whole batch doubles with chance p
    static final Set<String> INPUT_BONUS = Set.of("save");                       // each input unit is kept with chance p
    /** Lv30 machine multiplier of every job. */
    static final Range JOB_K_RANGE = new Range(1.15, 1.65);
    /** Lv30: max / min over the jobs (the farmer's Lv30 perks are on crops, so it is the lowest). */
    static final double JOB_K_SPREAD = 1.45;
    /** Lv30: max / min over the jobs whose Lv30 perk is a machine bonus (all but the farmer). */
    static final double JOB_K_SPREAD_LV30_PERK = 1.21;
    /** R3c: the old shop-price bonus key (refused by R3b), still modelled so R3c sees what it would do. */
    static final String SHOP_PRICE_BONUS = "sell";
    static final int MAX_LEVEL = 30;
    // ------------------------------------------------------------------------------------------ R4 aging
    static final double AGE_MIN_SECONDS = 600;
    /** G per minute at base speed: raw-only, chained. */
    static final Map<String, Range> AGE_GMIN = Map.of("R", new Range(3.0, 10.0), "C", new Range(12.0, 25.0));
    /** R4d: raw aging ratio cap where the machine has no chained aging recipe. */
    static final double AGE_RAW_CAP_ALONE = 3.0;
    /** R4e: chained aging ratio. */
    static final Range AGE_CHAIN_BAND = new Range(2.60, 3.60);
    /** R4f: an aged raw good pays more per input than any quick raw good (R band top + 0.01). */
    static final double AGE_RAW_FLOOR = 1.61;
    // ------------------------------------------------------------------------------------------ R5 .. R9
    static final double WORST_MEMBER = 1.20;
    /** R5b: sized fish at a Lv20 fisher's typical size. */
    static final String FISHER_CASE = "lv20";

    /** A group input at its typical (median) or cheapest member ("typ" / "lo"), sized fish at base (null) or in a SIZE_CASES case. */
    record Scenario(String pick, String sizeCase) {}

    /**
     * R9a / R9b: the beating recipe at its typical or cheapest group member, every sized fish at base, at any player's typical size
     * and at a Lv20 fisher's; the beaten (upgraded / harder) recipe at its typical member in the same case.
     */
    static final List<Scenario> SCENARIOS = List.of(new Scenario("typ", null), new Scenario("lo", null), new Scenario("typ", "lv1"),
            new Scenario("lo", "lv1"), new Scenario("typ", "lv20"), new Scenario("lo", "lv20"));
    /** R7: share of a recipe's input value another recipe also takes. */
    static final double SHARE = 0.80;
    // ------------------------------------------------------------------------------------------ R10 q ladder
    /** base below the limit -> q. */
    static final double[][] Q_LADDER = {{3, 128}, {6, 64}, {15, 32}, {50, 16}, {150, 8}, {500, 4}, {1000, 2}, {Double.POSITIVE_INFINITY, 1}};
    // ------------------------------------------------------------------------------------------ R8 crops
    /** G per plot per 24 min at base price, no job (balance2 23-37 / 14-21 x 0.07: crops cut to about 7% in the 2026-11 parity rebalance). */
    static final Map<String, Range> CROP_BAND = Map.of("regrow", new Range(1.6, 2.6), "replant", new Range(1.0, 1.5));
    static final double CROP_SPREAD = 2.7;
    static final Range CROP_REGROW_OVER_REPLANT = new Range(1.5, 2.0);
    /** Bone meal: one +1 age per this many ages grown naturally (at most one banked). */
    static final int MEAL_EVERY = 7;
    /** A single-crop route step: the route's good is at least this share of the input value. */
    static final double ROUTE_SHARE = 0.85;
    /** R8c: best single-crop route per plot, max / min. */
    static final double ROUTE_SPREAD = 5.0;
    /** A crop's own route: its first good and what that good becomes in one more machine. */
    static final int ROUTE_STEPS = 2;
    // ------------------------------------------------------------------------------------------ R11 chains
    /** Best full-chain multiple of each job machine (any of its goods), max / min over the six. */
    static final double CHAIN_SPREAD = 3.0;   // 2.5 before 2026-11: melon liqueur became an aged drink, the still's chain carries the aging premium
    // ------------------------------------------------------------------------------------------ R12 fishing ring
    // (the ring's own constants: Ring.SEG, HIT_SPEEDUP, MIN_ZONE, DRIFT_EVERY)
    /** Every hit: the needle is on green for at least 2 server ticks (100 ms) at every phase. */
    static final int MIN_WINDOW_TICKS = 2;
    /** Ring.newRound: the zone's near end starts AHEAD_MIN + nextInt(span) cells ahead of the needle, span at most AHEAD_SPAN. */
    static final int AHEAD_MIN = 14, AHEAD_SPAN = 19;
    /** FishingModule: +1% zone a fisher level (Lv0 = not a fisher), Lv0-30. */
    static final double FISHER_ZONE_PER_LEVEL = 0.01;
    // ------------------------------------------------------------------------------------------ R13 / R14
    /** Coal: crafts to a coal block and back, or is placed and mined, without its mark. */
    static final Set<String> LAUNDER = Set.of("minecraft:potato", "minecraft:carrot", "minecraft:sugar_cane", "minecraft:bone_meal",
            "minecraft:sweet_berries", "minecraft:cocoa_beans", "minecraft:nether_wart", "minecraft:kelp", "minecraft:bamboo",
            "minecraft:cactus", "minecraft:coal");
    /** Seed = produce custom crops: the core's CropModule.plant keeps the mark. */
    static final Set<String> CARRY_MARKED = Set.of("economy:garlic", "economy:soybean");
    /** R13c: buy items a machine takes through a vanilla tag (a tag key in a group or a recipe). */
    static final Map<String, Set<String>> VANILLA_TAG_MEMBERS = Map.of(
            "#saplings", Set.of("minecraft:oak_sapling"), "#stone_crafting_materials", Set.of("minecraft:cobblestone"));
    static final Map<String, Double> REQUEST_MATERIALS = linked("minecraft:iron_pickaxe", 30.0, "minecraft:shield", 11.5,
            "minecraft:bread", 6.0, "minecraft:cooked_beef", 3.0);
    static final double REQUEST_MAX_OVER_MATERIALS = 1.8;
    static final List<String> VANILLA_CROPS = List.of("minecraft:wheat", "minecraft:potato", "minecraft:carrot", "minecraft:beetroot",
            "minecraft:melon_slice", "minecraft:pumpkin", "minecraft:sugar_cane");
    /** R14b: upgrade steps in the good's chain (0, 1, 2, 3+) -> earliest from-day. */
    static final int[] REQUEST_DAY_BY_STEPS = {1, 4, 8, 15};
    /** R14c: RequestSpec.minShare: at least 0.5% of the target ... */
    static final double GOAL_MIN_SHARE = 0.005;
    /** ... and at least the reward's worth at request prices; that floor stays <= 10% of the target. */
    static final double GOAL_MAX_FLOOR_SHARE = 0.10;

    static World w;

    @BeforeAll
    static void load() {
        w = new World(ROOT);
    }

    /** A rule passes with no failure; else it fails once with every one of them. */
    static void rule(String id, List<String> bad) {
        assertTrue(bad.isEmpty(), () -> id + " (" + bad.size() + "):\n     - " + String.join("\n     - ", bad));
    }

    // ============================================================================================ recipe rules

    /** R0: no recipe loses value on typical inputs (press included). */
    @Test
    void r0NoLoss() {
        List<String> bad = new ArrayList<>();
        for (Row r : w.rows) if (r2(r.ratio) < NO_LOSS) bad.add(r.m + " " + r.name + " ratio " + py(r2(r.ratio)));
        rule("R0", bad);
    }

    /** R1: a quick (non-aging) job recipe sits in the ratio band of its class: R 1.20-1.60, C1 1.75-1.85, C2 2.15-2.30. */
    @Test
    void r1Band() {
        List<String> bad = new ArrayList<>();
        for (Row r : jobRows()) {
            if (r.aging || r.typ < FREE_INPUT) continue;
            Range band = BAND.get(r.cls);
            if (!band.has(r2(r.ratio))) bad.add(r.m + " " + r.name + " " + r.cls + " ratio " + py(r2(r.ratio)) + " not in " + band);
        }
        rule("R1", bad);
    }

    /**
     * R1f: a job recipe from inputs under 2 G earns less per minute than every chained quick recipe of its machine, and, in a machine
     * whose character is aging (R1m b), than every chained aging recipe too.
     */
    @Test
    void r1fFreeInputs() {
        List<String> bad = new ArrayList<>();
        for (Row r : jobRows()) {
            if (r.aging || r.typ >= FREE_INPUT) continue;
            List<Double> ch = new ArrayList<>();
            for (Row x : w.rows) if (x.m.equals(r.m) && !x.cls.equals("R") && (!x.aging || agingMachine(r.m))) ch.add(x.gmin);
            if (!ch.isEmpty() && r2(r.gmin) >= r2(min(ch))) {
                bad.add(r.m + " " + r.name + " " + py(r2(r.gmin)) + " G/min >= chained minimum " + py(r2(min(ch))));
            }
        }
        rule("R1f", bad);
    }

    /** A machine whose character is aging: more of its recipes age than not (the still). */
    static boolean agingMachine(String m) {
        int n = 0, aging = 0;
        for (Row r : w.rows) if (r.m.equals(m)) {
            n++;
            if (r.aging) aging++;
        }
        return aging * 2 > n;
    }

    /**
     * R1m: raw-only pays less per minute too. (a) Every raw-only quick END good (its output feeds no recipe; intermediates are judged
     * by their chain) earns less G/min than every chained quick recipe of its machine at the same or a higher tier. (b) In a machine
     * whose character is aging (most of its recipes age), every raw-only quick recipe also earns less G/min than every chained aging
     * recipe. Elsewhere quick and aging recipes are not compared per minute: a quick lane needs a refill every few minutes, an aging
     * lane runs unattended for 10-75 minutes (aging vs aging is R4c). The raw recipe is judged on its typical inputs and filled with
     * its groups' cheapest member (the chained ones typical).
     */
    @Test
    void r1mRawPerMinute() {
        List<String> bad = new ArrayList<>();
        Set<String> used = fed();
        for (String m : JOB_MACHINES) {
            List<Row> rs = w.rows.stream().filter(r -> r.m.equals(m)).toList();
            List<Row> rawQuick = rs.stream().filter(r -> r.cls.equals("R") && !r.aging && r.typ >= FREE_INPUT).toList();
            for (Row a : rawQuick) {
                if (used.contains(a.out)) continue;
                for (Row b : rs) {
                    if (b.cls.equals("R") || b.aging || b.tier < a.tier) continue;
                    for (String pick : picks(a)) {
                        double g = r2(w.valued(a, pick, null).gmin());
                        if (g >= r2(b.gmin)) {
                            bad.add(m + ": raw " + a.name + " t" + a.tier + " " + py(g) + " G/min" + cheapest(pick) + " >= chained " + b.name
                                    + " t" + b.tier + " " + py(r2(b.gmin)));
                            break;
                        }
                    }
                }
            }
            if (!agingMachine(m)) continue;
            for (Row a : rawQuick) {
                for (Row b : rs) {
                    if (b.cls.equals("R") || !b.aging) continue;
                    for (String pick : picks(a)) {
                        double g = r2(w.valued(a, pick, null).gmin());
                        if (g >= r2(b.gmin)) {
                            bad.add(m + ": raw quick " + a.name + " " + py(g) + " G/min" + cheapest(pick) + " >= chained aging " + b.name + " "
                                    + py(r2(b.gmin)));
                            break;
                        }
                    }
                }
            }
        }
        rule("R1m", bad);
    }

    /** A recipe on its typical inputs, and filled with its groups' cheapest member if it takes a group. */
    static List<String> picks(Row r) {
        return r.groups.isEmpty() ? List.of("typ") : List.of("typ", "lo");
    }

    static String cheapest(String pick) {
        return pick.equals("lo") ? " (cheapest member)" : "";
    }

    /**
     * R2: the press (no job) stays under the job machines: ratio <= band floor - 0.10 (R 1.10, C1 1.65, C2 2.05) for inputs worth
     * >= 2 G, judged on the typical (median) group member AND on the dearest one (a mob farm feeds the dearest junk).
     */
    @Test
    void r2PressCap() {
        List<String> bad = new ArrayList<>();
        for (Row r : w.rows) {
            if (!r.m.equals("press")) continue;
            double cap = r2(BAND.get(r.cls).lo() - PRESS_BELOW_FLOOR);
            for (var e : List.of(Map.entry("typical", r.typ), Map.entry("dearest member", r.hi))) {
                if (e.getValue() < FREE_INPUT) continue;
                double ratio = r.ov / e.getValue();
                if (r2(ratio) > cap) bad.add("press " + r.name + " " + r.cls + " ratio " + py(r2(ratio)) + " (" + e.getKey() + ") > " + py(cap));
            }
        }
        rule("R2", bad);
    }

    /**
     * R2b: the press has its own upgrade cost (only the cost; level, lanes and speed come from the global table), each step at most
     * 20% of the job machines' step.
     */
    @Test
    void r2bPressCost() {
        List<?> job = w.machinesYml.getList("upgrade.cost", List.of());
        ConfigurationSection own = w.machinesYml.getConfigurationSection("press.upgrade");
        List<String> keys = own == null ? List.of() : new TreeSet<>(own.getKeys(false)).stream().toList();
        List<String> bad = new ArrayList<>();
        List<?> cost = own == null ? List.of() : own.getList("cost", List.of());
        if (!keys.equals(List.of("cost"))) bad.add("press upgrade override must set cost only, has " + pyList(keys));
        else if (cost.size() != job.size()) bad.add("press upgrade cost has " + cost.size() + " steps, job machines " + job.size());
        else for (int i = 0; i < cost.size(); i++) {
            if (num(cost.get(i)) > PRESS_COST_SHARE * num(job.get(i))) {
                bad.add("press step " + (i + 1) + ": " + py(cost.get(i)) + " > " + py(PRESS_COST_SHARE) + " x " + py(job.get(i)));
            }
        }
        rule("R2b", bad);
    }

    /** (bulk, base): same machine, same output and input kinds, the bulk one at a higher tier. */
    static List<Row[]> bulkPairs() {
        List<Row[]> out = new ArrayList<>();
        for (Row a : w.rows) for (Row b : w.rows) {
            if (a != b && a.m.equals(b.m) && a.out.equals(b.out) && a.ins.keySet().equals(b.ins.keySet()) && a.tier > b.tier) out.add(new Row[]{a, b});
        }
        return out;
    }

    /** A machine bonus at a level, as JobSpec.Job#bonus (0 below its level); the old 'sell' key too. */
    static double jobBonus(String job, String key, int level) {
        return w.jobs.jobs().get(job).bonus(key, level);
    }

    /**
     * k: expected output per input of a job's machine at a level, every bonus proportional: double = the whole batch x2 with chance p;
     * extra and yield = each output unit +1 with chance p (one roll, chances added, at most 1); save = each input unit kept with chance
     * p (machine-bonus only: a job's growing 'save' effect is for gear crafting, not machines). Same factor for every recipe of the
     * machine.
     */
    static double jobMultiplier(String job, int level) {
        if (job == null) return 1.0;
        double more = 0;
        for (String k : OUTPUT_BONUS) more += jobBonus(job, k, level);
        return (1 + jobBonus(job, "double", level)) * (1 + Math.min(1.0, more)) / (1 - Math.min(0.9, jobBonus(job, "save", level)));
    }

    /**
     * R3: a bulk recipe has exactly its base recipe's ratio (so the proportional job bonuses keep it equal at every level 1-30) and
     * takes fewer seconds per output unit.
     */
    @Test
    void r3Bulk() {
        List<String> bad = new ArrayList<>();
        for (Row[] p : bulkPairs()) {
            Row a = p[0], b = p[1];
            String job = w.machines.get(a.m).job();
            for (int lv = 1; lv <= MAX_LEVEL; lv++) {
                double k = jobMultiplier(job, lv);
                if (Math.abs(a.ratio * k - b.ratio * k) > 1e-9) {
                    bad.add(a.m + " " + a.name + " bulk ratio " + py(r2(a.ratio)) + " != base " + py(r2(b.ratio)) + " (at Lv" + lv + ": "
                            + fmt(a.ratio * k, 3) + " vs " + fmt(b.ratio * k, 3) + ")");
                    break;
                }
            }
            if (a.sec / a.amt >= b.sec / b.amt) {
                bad.add(a.m + " " + a.name + " bulk " + fmt(a.sec / a.amt, 1) + " s per unit, not faster than " + fmt(b.sec / b.amt, 1));
            }
        }
        rule("R3", bad);
    }

    /** {job: [k at Lv1 .. Lv30]} for every job with a machine. */
    static Map<String, double[]> jobTable() {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (JobSpec.Job j : w.jobs.jobs().values()) {
            if (j.machine() == null || j.machine().isEmpty()) continue;
            double[] ks = new double[MAX_LEVEL];
            for (int lv = 1; lv <= MAX_LEVEL; lv++) ks[lv - 1] = jobMultiplier(j.id(), lv);
            out.put(j.id(), ks);
        }
        return out;
    }

    /** Does the job's Lv30 perk sit on its machine (a machine-bonus entry from level 30)? */
    static boolean lv30MachinePerk(String job) {
        return w.jobs.jobs().get(job).bonuses().values().stream().anyMatch(b -> b.level() == MAX_LEVEL);
    }

    /**
     * R3b: job bonuses keep every recipe's order at every level: each machine bonus is a time factor or proportional (output unit,
     * whole batch, input unit); a bonus on the shop price of own-machine goods (the old 'sell') is not proportional (it raises the value
     * of the intermediates a chained recipe uses up as much as its output) and is refused. k never falls as the level rises; at Lv30
     * every job's k is within 1.15-1.65 and max / min <= 1.45.
     */
    @Test
    void r3bJobBonuses() {
        List<String> bad = new ArrayList<>();
        Set<String> known = new HashSet<>(TIME_BONUS);
        known.addAll(OUTPUT_BONUS);
        known.addAll(BATCH_BONUS);
        known.addAll(INPUT_BONUS);
        for (JobSpec.Job j : w.jobs.jobs().values()) {
            for (String key : j.bonuses().keySet()) {
                if (!known.contains(key)) bad.add(j.id() + ": machine-bonus '" + key + "' is not a time factor or a proportional bonus");
            }
        }
        Map<String, double[]> tab = jobTable();
        List<Double> top = new ArrayList<>(), perk = new ArrayList<>();
        for (var e : tab.entrySet()) {
            double[] ks = e.getValue();
            for (int i = 1; i < ks.length; i++) {
                if (ks[i] < ks[i - 1] - 1e-12) {
                    bad.add(e.getKey() + ": k falls with the level");
                    break;
                }
            }
            double lv30 = ks[ks.length - 1];
            if (!JOB_K_RANGE.has(r2(lv30))) bad.add(e.getKey() + ": Lv30 k " + py(r2(lv30)) + " not in " + JOB_K_RANGE);
            top.add(lv30);
            if (lv30MachinePerk(e.getKey())) perk.add(lv30);
        }
        if (!top.isEmpty() && r2(max(top) / min(top)) > JOB_K_SPREAD) {
            bad.add("Lv30 k spread " + fmt(max(top) / min(top), 2) + " > " + py(JOB_K_SPREAD));
        }
        if (!perk.isEmpty() && r2(max(perk) / min(perk)) > JOB_K_SPREAD_LV30_PERK) {
            bad.add("Lv30 k spread among the jobs with a Lv30 machine perk " + fmt(max(perk) / min(perk), 2) + " > " + py(JOB_K_SPREAD_LV30_PERK));
        }
        rule("R3b", bad);
    }

    /**
     * An item's value to a player of a job at a level running a machine: base (model value), times 1 + the shop-price bonus when the
     * item is made on that machine (JobModule.sellBonus: madeOn(item) is the job's machine).
     */
    static Num sellerValue(String job, int level, String machine, String item) {
        Num v = w.valueNum(item);
        if (machine.equals(w.madeOn.get(item))) return new Num(v.v() * (1 + jobBonus(job, SHOP_PRICE_BONUS, level)), false);
        return v;
    }

    /** A job owner's ratio at a level: k x output value / input value, both at the seller's price. */
    static double sellerRatio(Row r, String job, int level) {
        List<Num> terms = new ArrayList<>();
        for (var e : r.ins.entrySet()) {
            Num v = sellerValue(job, level, r.m, e.getKey());
            terms.add(new Num(e.getValue() * v.v(), v.whole()));
        }
        double cost = sum(terms);
        double out = r.amt * sellerValue(job, level, r.m, r.out).v();
        return cost != 0 ? jobMultiplier(job, level) * out / cost : Double.POSITIVE_INFINITY;
    }

    /**
     * R3c: at every job level 1-30, valued at the seller's price (own-machine goods with the shop-price bonus, if any), a machine's
     * quick recipes keep their class order (every raw-only one < every C1 one < every C2 one, inputs >= 2 G) and its raw aging recipes
     * stay <= its lowest chained aging one (R4d). Reported once per machine and pair, at the first level that breaks.
     */
    @Test
    void r3cOrderEveryLevel() {
        List<String> bad = new ArrayList<>();
        for (String m : JOB_MACHINES) {
            String job = w.machines.get(m).job();
            List<Row> rs = w.rows.stream().filter(r -> r.m.equals(m) && r.typ >= FREE_INPUT).toList();
            Set<String> seen = new HashSet<>();
            for (int lv = 1; lv <= MAX_LEVEL; lv++) {
                Map<Row, Double> ratio = new IdentityHashMap<>();
                for (Row r : rs) ratio.put(r, r2(sellerRatio(r, job, lv)));
                Map<String, List<Row>> quick = new HashMap<>();
                for (String c : List.of("R", "C1", "C2")) quick.put(c, rs.stream().filter(r -> !r.aging && r.cls.equals(c)).toList());
                for (String[] pair : new String[][]{{"R", "C1"}, {"C1", "C2"}, {"R", "C2"}}) {
                    String loC = pair[0], hiC = pair[1];
                    if (quick.get(loC).isEmpty() || quick.get(hiC).isEmpty() || seen.contains(loC + ">" + hiC)) continue;
                    Row a = first(quick.get(loC), ratio::get, true), b = first(quick.get(hiC), ratio::get, false);
                    if (ratio.get(a) >= ratio.get(b)) {
                        seen.add(loC + ">" + hiC);
                        long n = quick.get(loC).stream().filter(x -> ratio.get(x) >= ratio.get(b)).count();
                        bad.add(m + " Lv" + lv + ": " + loC + " " + a.name + " " + py(ratio.get(a)) + " >= " + hiC + " " + b.name + " "
                                + py(ratio.get(b)) + " (" + n + " " + loC + " recipe(s))");
                    }
                }
                List<Row> raw = rs.stream().filter(r -> r.aging && r.cls.equals("R")).toList();
                List<Row> ch = rs.stream().filter(r -> r.aging && !r.cls.equals("R")).toList();
                if (!raw.isEmpty() && !ch.isEmpty() && !seen.contains("aging")) {
                    Row a = first(raw, ratio::get, true), b = first(ch, ratio::get, false);
                    if (ratio.get(a) > ratio.get(b)) {
                        seen.add("aging");
                        bad.add(m + " Lv" + lv + ": raw aging " + a.name + " " + py(ratio.get(a)) + " > chained aging " + b.name + " " + py(ratio.get(b)));
                    }
                }
            }
        }
        rule("R3c", bad);
    }

    // ------------------------------------------------------------------------------------------ aging

    static List<Row> agingRows(String m) {
        return w.rows.stream().filter(r -> r.aging && (m == null || r.m.equals(m))).toList();
    }

    /** R4a: an aging recipe takes at least 10 minutes. */
    @Test
    void r4aAgeTime() {
        List<String> bad = new ArrayList<>();
        for (Row r : agingRows(null)) if (r.sec < AGE_MIN_SECONDS) bad.add(r.m + " " + r.name + " " + fmt(r.sec, 0) + " s");
        rule("R4a", bad);
    }

    /** R4b: aging profit per minute (base speed): raw-only 3-10 G, chained 12-25 G. */
    @Test
    void r4bAgeGmin() {
        List<String> bad = new ArrayList<>();
        for (Row r : agingRows(null)) {
            Range band = AGE_GMIN.get(r.cls.equals("R") ? "R" : "C");
            if (!band.has(r2(r.gmin))) bad.add(r.m + " " + r.name + " " + r.cls + " " + py(r2(r.gmin)) + " G/min not in " + band);
        }
        rule("R4b", bad);
    }

    /**
     * R4c: in a machine, chained aging G/min never falls as the tier rises, and every chained aging recipe earns more per minute than
     * every raw-only one.
     */
    @Test
    void r4cAgeTiers() {
        List<String> bad = new ArrayList<>();
        for (String m : JOB_MACHINES) {
            List<Row> ar = agingRows(m);
            List<Row> raw = ar.stream().filter(r -> r.cls.equals("R")).toList();
            List<Row> ch = new ArrayList<>(ar.stream().filter(r -> !r.cls.equals("R")).toList());
            ch.sort(Comparator.comparingInt((Row r) -> r.tier).thenComparingDouble(r -> r.gmin));
            if (!raw.isEmpty() && !ch.isEmpty() && r2(first(raw, Row::gmin, true).gmin) >= r2(first(ch, Row::gmin, false).gmin)) {
                Row hi = first(raw, Row::gmin, true), lo = first(ch, Row::gmin, false);
                bad.add(m + ": raw " + hi.name + " " + py(r2(hi.gmin)) + " >= chained " + lo.name + " " + py(r2(lo.gmin)) + " G/min");
            }
            for (int i = 1; i < ch.size(); i++) {
                Row a = ch.get(i - 1), b = ch.get(i);
                if (b.tier > a.tier && r2(b.gmin) < r2(a.gmin)) {
                    bad.add(m + ": t" + b.tier + " " + b.name + " " + py(r2(b.gmin)) + " < t" + a.tier + " " + a.name + " " + py(r2(a.gmin)) + " G/min");
                }
            }
        }
        rule("R4c", bad);
    }

    static double rawAgeCap(String m) {
        List<Double> ch = agingRows(m).stream().filter(r -> !r.cls.equals("R")).map(r -> r2(r.ratio)).toList();
        return ch.isEmpty() ? AGE_RAW_CAP_ALONE : min(ch);
    }

    /**
     * R4d (per input): a raw-only aging ratio is at most the lowest chained aging ratio of its machine (3.0 if it has none), on its
     * typical inputs and filled with its groups' cheapest members.
     */
    @Test
    void r4dAgeRawCap() {
        List<String> bad = new ArrayList<>();
        for (Row r : agingRows(null)) {
            if (!r.cls.equals("R")) continue;
            double cap = rawAgeCap(r.m);
            for (String pick : picks(r)) {
                double x = r2(w.valued(r, pick, null).ratio());
                if (x > cap) bad.add(r.m + " " + r.name + " raw aging ratio " + py(x) + cheapest(pick) + " > " + py(cap));
            }
        }
        rule("R4d", bad);
    }

    /** R4e: chained aging keeps a bounded premium: ratio 2.60-3.60. */
    @Test
    void r4eAgeChainBand() {
        List<String> bad = new ArrayList<>();
        for (Row r : agingRows(null)) {
            if (!r.cls.equals("R") && !AGE_CHAIN_BAND.has(r2(r.ratio))) {
                bad.add(r.m + " " + r.name + " chained aging ratio " + py(r2(r.ratio)) + " not in " + AGE_CHAIN_BAND);
            }
        }
        rule("R4e", bad);
    }

    /** R4f: an aged raw good pays more per input than any quick raw good (ratio >= 1.61). */
    @Test
    void r4fAgeRawFloor() {
        List<String> bad = new ArrayList<>();
        for (Row r : agingRows(null)) {
            if (r.cls.equals("R") && r2(r.ratio) < AGE_RAW_FLOOR) {
                bad.add(r.m + " " + r.name + " raw aging ratio " + py(r2(r.ratio)) + " < " + py(AGE_RAW_FLOOR));
            }
        }
        rule("R4f", bad);
    }

    // ------------------------------------------------------------------------------------------ groups, auto-pick

    /** R5: in a job recipe that takes a group, the dearest member still gives ratio >= 1.20. */
    @Test
    void r5GroupWorst() {
        List<String> bad = new ArrayList<>();
        for (Row r : jobRows()) {
            if (!r.groups.isEmpty() && r2(r.worst) < WORST_MEMBER) bad.add(r.m + " " + r.name + " worst member ratio " + py(r2(r.worst)));
        }
        rule("R5", bad);
    }

    /**
     * R5b: a job recipe that takes sized fish (rare, legend: they keep their size and sell by it) stays >= 1.20 for its dearest member
     * caught at a Lv20 fisher's typical size (2 perfect hits, +10% size), so the fishers who supply them do not lose by cooking a
     * typical catch.
     */
    @Test
    void r5bSizedFish() {
        List<String> bad = new ArrayList<>();
        for (Row r : jobRows()) {
            if (r.ins.keySet().stream().noneMatch(k -> w.groupOr(k).stream().anyMatch(w::sized))) continue;
            Valued v = w.valued(r, "hi", FISHER_CASE);
            if (r2(v.ratio()) < WORST_MEMBER) bad.add(r.m + " " + r.name + " dearest sized member at a fisher's size: ratio " + py(r2(v.ratio())));
        }
        rule("R5b", bad);
    }

    /**
     * R5c: a job recipe that takes a group keeps its machine's class order when filled with the group's cheapest member (every other
     * recipe on its typical inputs): a quick raw-only recipe pays less per input than every quick C1 and C2 recipe of its machine, a
     * quick C1 less than every quick C2 (inputs >= 2 G). Raw aging at the cheapest member is R4d's.
     */
    @Test
    void r5cGroupCheapest() {
        Map<String, List<String>> above = Map.of("R", List.of("C1", "C2"), "C1", List.of("C2"), "C2", List.of());
        List<String> bad = new ArrayList<>();
        for (Row r : jobRows()) {
            if (r.groups.isEmpty() || r.aging || r.typ < FREE_INPUT) continue;
            double lo = r2(w.valued(r, "lo", null).ratio());
            for (Row x : w.rows) {
                if (x.m.equals(r.m) && !x.aging && above.get(r.cls).contains(x.cls) && x.typ >= FREE_INPUT && lo >= r2(x.ratio)) {
                    bad.add(r.m + " " + r.name + " " + r.cls + " at its cheapest member " + py(lo) + " >= " + x.cls + " " + x.name + " "
                            + py(r2(x.ratio)));
                }
            }
        }
        rule("R5c", bad);
    }

    /** Can one lane (a set of item kinds, any counts) satisfy both recipes' enough() and only()? */
    static boolean oneLaneMakesBoth(Row a, Row b) {
        List<String> keys = new ArrayList<>(a.ins.keySet());
        keys.addAll(b.ins.keySet());
        Set<String> usable = new LinkedHashSet<>();
        for (String k : keys) usable.addAll(w.groupOr(k));
        usable.removeIf(i -> a.ins.keySet().stream().noneMatch(k -> w.groupOr(k).contains(i))
                || b.ins.keySet().stream().noneMatch(k -> w.groupOr(k).contains(i)));
        return keys.stream().allMatch(k -> w.groupOr(k).stream().anyMatch(usable::contains));
    }

    /**
     * R6: no lane can make two recipes of one machine, except a bulk pair (same output, higher tier) or same-tier recipes with the same
     * kinds and different counts; there the recipe the auto-pick takes first has at least the ratio of the one it hides.
     */
    @Test
    void r6Autopick() {
        List<String> bad = new ArrayList<>();
        for (String m : w.machines.keySet()) {
            List<Row> rs = w.rows.stream().filter(r -> r.m.equals(m)).toList();
            for (int i = 0; i < rs.size(); i++) for (int j = i + 1; j < rs.size(); j++) {
                Row a = rs.get(i), b = rs.get(j);
                if (!oneLaneMakesBoth(a, b)) continue;
                boolean aFirst = -a.tier < -b.tier || -a.tier == -b.tier && a.idx < b.idx;
                Row first = aFirst ? a : b, hidden = aFirst ? b : a;
                boolean sameKinds = a.ins.keySet().equals(b.ins.keySet());
                boolean bulk = sameKinds && a.out.equals(b.out) && a.tier != b.tier;
                boolean countPick = sameKinds && a.tier == b.tier && count(a) != count(b);
                if (!(bulk || countPick)) {
                    bad.add(m + ": one lane makes " + a.name + " (t" + a.tier + ") and " + b.name + " (t" + b.tier + ")");
                    continue;
                }
                // a lane holding enough for both makes `first`: it must not be the worse one
                if ((count(first) >= count(hidden) || bulk) && r2(first.ratio) < r2(hidden.ratio)) {
                    bad.add(m + ": auto-pick makes " + first.name + " " + py(r2(first.ratio)) + " over better " + hidden.name + " "
                            + py(r2(hidden.ratio)));
                }
            }
        }
        rule("R6", bad);
    }

    static int count(Row r) {
        return r.ins.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ------------------------------------------------------------------------------------------ dominance

    /** b dominates a: at least as good on value and on G/min, and better on one of them or easier to reach. */
    static boolean dominated(double aVal, double aGmin, double bVal, double bGmin, boolean bEasier) {
        double av = r2(aVal), ag = r2(aGmin), bv = r2(bVal), bg = r2(bGmin);
        return bv >= av && bg >= ag && (bv > av || bg > ag || bEasier);
    }

    /** The press is the deliberately cheap fallback: a press recipe is never judged against a job machine's. */
    static boolean comparable(Row a, Row b) {
        return !(a.m.equals("press") && !b.m.equals("press"));
    }

    /** Share of a's input value that b also takes (an input counts whole if one of b's inputs takes the same item). */
    static double sharedShare(Row a, Row b) {
        if (a.typ == 0) return 0.0;
        List<Num> terms = new ArrayList<>();
        for (String k : a.ins.keySet()) {
            if (b.ins.keySet().stream().anyMatch(k2 -> w.overlap(k, k2))) terms.add(new Num(a.part.get(k), a.wholePart.contains(k)));
        }
        return sum(terms) / a.typ;
    }

    /** Two recipes 'take the same input' when each shares at least SHARE of its input value with the other. */
    static double mutualShare(Row a, Row b) {
        return Math.min(sharedShare(a, b), sharedShare(b, a));
    }

    /**
     * R7: of two recipes that each share >= 80% of their input value with the other, the one at the same or a higher tier is not
     * strictly worse per input AND per minute (the slower or harder one never pays less).
     */
    @Test
    void r7SharedInput() {
        List<String> bad = new ArrayList<>();
        for (Row a : w.rows) for (Row b : w.rows) {
            if (a == b || a.out.equals(b.out) || !comparable(a, b) || a.typ < FREE_INPUT || b.typ < FREE_INPUT) continue;
            if (b.tier > a.tier || mutualShare(a, b) < SHARE) continue;
            if (r2(a.ratio) < r2(b.ratio) && r2(a.gmin) < r2(b.gmin)) {
                bad.add(a.m + " " + a.name + " (" + py(r2(a.ratio)) + "x, " + py(r2(a.gmin)) + " G/min) worse than " + b.m + " " + b.name + " ("
                        + py(r2(b.ratio)) + "x, " + py(r2(b.gmin)) + ") sharing " + pct(mutualShare(a, b)));
            }
        }
        rule("R7", bad);
    }

    static String scenarioText(Scenario s) {
        List<String> parts = new ArrayList<>();
        if (s.pick().equals("lo")) parts.add("cheapest member");
        if ("lv1".equals(s.sizeCase())) parts.add("sized fish at any player's typical size");
        if ("lv20".equals(s.sizeCase())) parts.add("sized fish at a Lv20 fisher's size");
        return parts.isEmpty() ? "" : " [" + String.join(", ", parts) + "]";
    }

    /**
     * R9a: no recipe is dominated on its main input by a recipe of the same or a lower tier: value per main-input unit and G/min both at
     * least as high (one higher, or a lower tier). Checked in every scenario (SCENARIOS): the other recipe at its typical or cheapest
     * group member, sized fish at base, at any player's typical size and at a Lv20 fisher's (this recipe typical, same case).
     */
    @Test
    void r9aMainInput() {
        List<String> bad = new ArrayList<>();
        for (Row a : w.rows) for (Row b : w.rows) {
            if (a == b || a.out.equals(b.out) || !comparable(a, b) || b.tier > a.tier) continue;
            if (a.typ < FREE_INPUT || b.typ < FREE_INPUT || !w.overlap(a.main, b.main)) continue;
            for (Scenario s : SCENARIOS) {
                Valued va = w.valued(a, "typ", s.sizeCase()), vb = w.valued(b, s.pick(), s.sizeCase());
                if (dominated(va.perMain(), va.gmin(), vb.perMain(), vb.gmin(), b.tier < a.tier)) {
                    bad.add(a.m + " " + a.name + " t" + a.tier + " (" + py(r2(va.perMain())) + " per " + idName(a.main) + ", " + py(r2(va.gmin()))
                            + " G/min) <= " + b.m + " " + b.name + " t" + b.tier + " (" + py(r2(vb.perMain())) + ", " + py(r2(vb.gmin())) + ")"
                            + scenarioText(s));
                    break;
                }
            }
        }
        rule("R9a", bad);
    }

    /**
     * R9b: an upgrade never opens a worse recipe: a higher-tier recipe is not dominated (ratio and G/min, neither better) by a
     * lower-tier recipe of the same machine, class and kind (quick / aging), whatever their inputs. Checked in every scenario
     * (SCENARIOS): the lower-tier recipe at its typical or cheapest group member, sized fish at base, at any player's typical size and
     * at a Lv20 fisher's (the upgraded recipe typical, same case). Bulk pairs (the same good from the same kinds) are R3's.
     */
    @Test
    void r9bTierInversion() {
        List<String> bad = new ArrayList<>();
        for (Row a : w.rows) for (Row b : w.rows) {
            if (a == b || !a.m.equals(b.m) || a.tier <= b.tier || !a.cls.equals(b.cls) || a.aging != b.aging || a.out.equals(b.out)) continue;
            if (a.typ < FREE_INPUT || b.typ < FREE_INPUT) continue;
            for (Scenario s : SCENARIOS) {
                Valued va = w.valued(a, "typ", s.sizeCase()), vb = w.valued(b, s.pick(), s.sizeCase());
                if (dominated(va.ratio(), va.gmin(), vb.ratio(), vb.gmin(), true)) {
                    bad.add(a.m + " t" + a.tier + " " + a.name + " (" + py(r2(va.ratio())) + "x, " + py(r2(va.gmin())) + " G/min) <= t" + b.tier
                            + " " + b.name + " (" + py(r2(vb.ratio())) + "x, " + py(r2(vb.gmin())) + ")" + scenarioText(s));
                    break;
                }
            }
        }
        rule("R9b", bad);
    }

    /** R9c: every recipe has one main input (no two inputs tie for the most value), so R9a means the same thing everywhere. */
    @Test
    void r9cMainInputUnique() {
        List<String> bad = new ArrayList<>();
        for (Row r : w.rows) {
            List<Double> parts = new ArrayList<>(r.part.values());
            parts.sort(Comparator.reverseOrder());
            if (r.typ >= FREE_INPUT && parts.size() > 1 && r2(parts.get(0)) == r2(parts.get(1))) {
                bad.add(r.m + " " + r.name + ": inputs tie at " + py(r2(parts.get(0))) + " G");
            }
        }
        rule("R9c", bad);
    }

    // ============================================================================================ shop rules

    static int qFor(double base) {
        for (double[] step : Q_LADDER) if (base < step[0]) return (int) step[1];
        return 1;
    }

    /**
     * R10: every sell line's q (one player's daily room) follows the price-tier ladder: base <3: 128, <6: 64, <15: 32, <50: 16, <150:
     * 8, <500: 4, <1000: 2, else 1.
     */
    @Test
    void r10QLadder() {
        List<String> bad = new ArrayList<>();
        for (ShopCatalog.SellEntry e : w.shop.sell().values()) {
            if (e.q() != qFor(e.base())) bad.add(e.id() + " base " + g(e.base()) + ": q " + whole(e.q()) + " (ladder " + qFor(e.base()) + ")");
        }
        rule("R10", bad);
    }

    /**
     * R10c: a raw custom crop has no per-player daily cap: selling more always pays a little more (the curve's floor and the sales tax
     * soften it). The 4 x q cap of balance2 made farmers' crops pile up in chests (2026-11 parity rebalance).
     */
    @Test
    void r10cCropCaps() {
        List<String> bad = new ArrayList<>();
        Set<String> items = new TreeSet<>();
        for (CropRow c : w.cropRows()) items.add(c.item());
        for (String i : items) {
            ShopCatalog.SellEntry e = w.shop.sell().get(i);
            Integer cap = e == null ? null : e.cap();
            if (cap != null && cap > 0) bad.add(i + ": cap " + cap + " (want none)");
        }
        rule("R10c", bad);
    }

    /** (category, item) of every buy line that sells an item. */
    static List<String[]> buyItems() {
        List<String[]> out = new ArrayList<>();
        ConfigurationSection b = w.shopYml.getConfigurationSection("buy");
        if (b == null) return out;
        for (String c : b.getKeys(false)) {
            for (Map<?, ?> g : b.getMapList(c + ".items")) {
                if (g.containsKey("item")) out.add(new String[]{c, JobSpec.norm(String.valueOf(g.get("item")))});
            }
        }
        return out;
    }

    /** Custom crops planted from their own produce (garlic, soybean). */
    static Set<String> seedIsProduce() {
        Set<String> out = new HashSet<>();
        for (CropSpec c : w.crops.values()) if (c.seed().equals(c.produce())) out.add(c.seed());
        return out;
    }

    /** Every item a machine recipe takes: directly, as a group member, or through a vanilla tag (VANILLA_TAG_MEMBERS). */
    static Set<String> takenByMachines() {
        Set<String> used = fed();
        for (var e : VANILLA_TAG_MEMBERS.entrySet()) if (used.contains(e.getKey())) used.addAll(e.getValue());
        return used;
    }

    /** {item: lowest price per unit over the buy lines that sell it}. */
    static Map<String, Double> buyUnitPrices() {
        Map<String, Double> out = new HashMap<>();
        ConfigurationSection b = w.shopYml.getConfigurationSection("buy");
        if (b == null) return out;
        for (String c : b.getKeys(false)) {
            for (Map<?, ?> g : b.getMapList(c + ".items")) {
                if (!g.containsKey("item") || g.get("price") == null) continue;
                double u = num(g.get("price")) / Math.max(1, g.get("amount") == null ? 1 : (int) num(g.get("amount")));
                out.merge(JobSpec.norm(String.valueOf(g.get("item"))), u, Math::min);
            }
        }
        return out;
    }

    /** The input key of a recipe that an item fills (itself, a group it is in, or a vanilla tag it is in), or null. */
    static String takes(Row r, String item) {
        for (String k : r.ins.keySet()) {
            if (k.equals(item) || w.isGroup(k) && w.groupOr(k).contains(item)
                    || w.groupOr(k).stream().anyMatch(m -> VANILLA_TAG_MEMBERS.getOrDefault(m, Set.of()).contains(item))) return k;
        }
        return null;
    }

    /**
     * What one unit of an item is worth through one machine step: output value / count where it is the recipe's only input, else its
     * value x the recipe's ratio (its share of the output).
     */
    static double unitRouteValue(String item) {
        double best = w.value(item);
        for (Row r : w.rows) {
            String k = takes(r, item);
            if (k == null) continue;
            double u = r.ins.size() == 1 ? r.ov / r.ins.get(k) : w.value(item) * r.ratio;
            best = Math.max(best, u);
        }
        return best;
    }

    /**
     * R13: the buy shop sells nothing that comes back unmarked and pays: (a) no vanilla item that replants as itself (the crop engine
     * does not track vanilla plants), speeds crops, or crafts and places back as itself into a machine route worth more than its price
     * (LAUNDER: coal <-> coal block feeds coke and steel at 12.75 G a coal); (b) a custom crop whose seed is its produce only if it is in
     * CARRY_MARKED (the core's CropModule.plant keeps the bought mark of the seed and a young plant broken out drops it marked); (c) any
     * other buy line a machine takes (directly, in a group or through a vanilla tag) that can come back unmarked (a vanilla seed, a
     * sapling, a placed torch or block) costs more per unit than one machine step makes of it (unitRouteValue), so a machine never pays
     * for laundering it.
     */
    @Test
    void r13NoLaundering() {
        List<String> bad = new ArrayList<>();
        List<String[]> buy = buyItems();
        for (String[] b : buy) if (LAUNDER.contains(b[1])) bad.add("buy." + b[0] + ": " + b[1]);
        Set<String> sip = seedIsProduce();
        for (String[] b : buy) {
            if (sip.contains(b[1]) && !CARRY_MARKED.contains(b[1])) bad.add("buy." + b[0] + ": " + b[1] + " replants as itself and is not carry-marked");
        }
        for (String i : new TreeSet<>(CARRY_MARKED)) if (!sip.contains(i)) bad.add("CARRY_MARKED " + i + " is not a seed = produce crop");
        Set<String> taken = takenByMachines();
        Map<String, Double> prices = buyUnitPrices();
        for (String[] b : buy) {
            String i = b[1];
            if (taken.contains(i) && i.startsWith("minecraft:") && !LAUNDER.contains(i)) {
                double u = unitRouteValue(i), price = prices.getOrDefault(i, 0.0);
                if (price <= u) {
                    bad.add("buy." + b[0] + ": " + i + " " + fmt(price, 2) + " G a unit <= " + fmt(u, 2)
                            + " G a unit through a machine (comes back unmarked)");
                }
            }
        }
        rule("R13", bad);
    }

    /** {machine: highest tier} needed to make an item through its lowest-tier recipe (file order), inputs expanded. */
    static Map<String, Integer> chainSteps(String item, List<String> seen) {
        List<Row> rs = w.rows.stream().filter(r -> r.out.equals(item)).toList();
        if (rs.isEmpty() || seen.contains(item)) return Map.of();
        int t = rs.stream().mapToInt(r -> r.tier).min().getAsInt();
        Row r = rs.stream().filter(x -> x.tier == t).findFirst().orElseThrow();
        Map<String, Integer> need = new LinkedHashMap<>();
        need.put(r.m, r.tier);
        List<String> deeper = new ArrayList<>(seen);
        deeper.add(item);
        for (String k : r.ins.keySet()) {
            if (!w.madeOn.containsKey(k)) continue;
            for (var e : chainSteps(k, deeper).entrySet()) need.merge(e.getKey(), e.getValue(), Math::max);
        }
        return need;
    }

    /**
     * R14: a request for a crafted vanilla item pays at most 1.8x its materials; every vanilla crop the shop buys has a use (a machine
     * recipe or a request).
     */
    @Test
    void r14Requests() {
        List<String> bad = new ArrayList<>();
        double mult = w.requests.rewardMultiplier();
        Map<String, Map<?, ?>> pool = new HashMap<>();
        for (Map<?, ?> e : w.requestsYml.getMapList("pool")) if (e.get("item") != null) pool.put(JobSpec.norm(String.valueOf(e.get("item"))), e);
        for (var m : REQUEST_MATERIALS.entrySet()) {
            Map<?, ?> e = pool.get(m.getKey());
            Object value = e == null ? null : e.get("value");
            if (value == null || num(value) == 0) continue;
            if (num(value) * mult > REQUEST_MAX_OVER_MATERIALS * m.getValue()) {
                bad.add("request " + m.getKey() + ": " + py(value) + " x " + py(mult) + " = " + fmt(num(value) * mult, 1) + " > "
                        + py(REQUEST_MAX_OVER_MATERIALS) + " x " + py(m.getValue()));
            }
        }
        Set<String> used = fed();
        for (String i : VANILLA_CROPS) {
            if (w.sell.containsKey(i) && !used.contains(i) && !pool.containsKey(i)) bad.add(i + ": sold to the shop but no recipe or request uses it");
        }
        rule("R14", bad);
    }

    /**
     * R14b: a request for a machine good starts no earlier than the upgrades its chain needs allow: upgrade steps summed over the
     * machines of its chain 0 -> day 1, 1 -> day 4, 2 -> day 8, 3 or more -> day 15.
     */
    @Test
    void r14bRequestDays() {
        List<String> bad = new ArrayList<>();
        for (RequestSpec.Entry e : w.requests.pool()) {
            if (!w.madeOn.containsKey(e.item())) continue;
            int steps = chainSteps(e.item(), List.of()).values().stream().mapToInt(Integer::intValue).sum();
            int need = REQUEST_DAY_BY_STEPS[Math.min(steps, REQUEST_DAY_BY_STEPS.length - 1)];
            if (e.fromDay() < need) {
                bad.add("request " + e.item() + ": from-day " + e.fromDay() + ", its chain needs " + steps + " upgrade steps (day " + need + ")");
            }
        }
        rule("R14b", bad);
    }

    /**
     * R14c: a weekly community goal pays only contributors who hand in at least RequestSpec.minShare (0.5% of the target, and the
     * reward's worth at request prices): its item sells to the shop, the floor is at most 10% of the target (ten contributors can be
     * paid), and the floor's request value covers the reward (no alt account earns more than a request would pay).
     */
    @Test
    void r14cGoalFloor() {
        List<String> bad = new ArrayList<>();
        double mult = w.requests.rewardMultiplier();
        for (RequestSpec.Community c : w.requests.community()) {
            double base = w.sell.getOrDefault(c.item(), 0.0);
            if (base <= 0) {
                bad.add("goal " + c.name() + ": " + c.item() + " has no sell base");
                continue;
            }
            long floor = RequestSpec.minShare(c.target(), c.reward(), base, mult);
            if (floor > GOAL_MAX_FLOOR_SHARE * c.target()) {
                bad.add("goal " + c.name() + ": floor " + floor + " > " + pct(GOAL_MAX_FLOOR_SHARE) + " of " + c.target());
            }
            if (floor * base * mult < c.reward() - 1e-9) {
                bad.add("goal " + c.name() + ": floor " + floor + " x " + g(base) + " x " + py(mult) + " < reward " + c.reward());
            }
        }
        rule("R14c", bad);
    }

    // ============================================================================================ crops

    /** Growth speed with bone meal under 'one +1 age per 7 ages grown naturally, at most one banked': the same for every crop. */
    static double mealFactor() {
        return (MEAL_EVERY + 1) / (double) MEAL_EVERY;
    }

    /**
     * R8: G per plot per 24 min at base price, no job, regrow 1.6-2.6 and replant 1.0-1.5; best / worst <= 2.7; regrow mean / replant mean
     * 1.5-2.0. The bone-meal rule speeds every crop by the same 8/7, so the spread and the ratio hold with meal too; with at most one
     * meal banked, one meal never ripens a crop from its planting or regrow age.
     */
    @Test
    void r8Crops() {
        List<String> bad = new ArrayList<>();
        List<CropRow> cr = w.cropRows();
        for (CropRow c : cr) {
            Range band = CROP_BAND.get(c.regrow() ? "regrow" : "replant");
            if (!band.has(r1(c.plot24() * 1.0))) bad.add(c.crop() + " " + fmt(c.plot24() * 1.0, 1) + " not in " + band);
        }
        for (boolean meal : new boolean[]{false, true}) {
            List<Double> rg = new ArrayList<>(), rp = new ArrayList<>();
            for (CropRow c : cr) (c.regrow() ? rg : rp).add(c.plot24() * (meal ? mealFactor() : 1.0));
            String tag = meal ? " (with meal)" : "";
            List<Double> all = new ArrayList<>(rg);
            all.addAll(rp);
            double sp = max(all) / min(all);
            if (sp > CROP_SPREAD) bad.add("spread " + fmt(sp, 2) + " > " + py(CROP_SPREAD) + tag);
            double mr = mean(rg) / mean(rp);
            if (!CROP_REGROW_OVER_REPLANT.has(r2(mr))) bad.add("regrow/replant " + fmt(mr, 2) + tag);
        }
        for (CropRow c : cr) {   // one meal never ripens a crop that was just harvested or planted
            int start = c.regrow() ? c.regrowTo() : 0;
            if (start + 1 >= c.maxAge()) bad.add(c.crop() + ": one meal ripens it from age " + start);
        }
        rule("R8", bad);
    }

    /** A route's value per unit and the recipes it takes. */
    record Route(double value, List<String> steps) {}

    /**
     * Best value of one unit of an item: sold, or put through a recipe where it is >= 85% of the input value and the output is taken on
     * the same way (the other inputs are paid at their value).
     */
    static Route routeValue(String item, List<String> seen) {
        Route best = new Route(w.value(item), List.of());
        if (seen.size() >= ROUTE_STEPS) return best;
        for (Row r : w.rows) {
            if (seen.contains(r.out) || r.out.equals(item)) continue;
            String k = r.ins.keySet().stream().filter(x -> w.fits(x, item)).findFirst().orElse(null);
            if (k == null || r.typ <= 0 || r.part.get(k) / r.typ < ROUTE_SHARE) continue;
            List<String> deeper = new ArrayList<>(seen);
            deeper.add(item);
            Route next = routeValue(r.out, deeper);
            double per = (r.amt * next.value() - (r.typ - r.part.get(k))) / r.ins.get(k);
            if (per > best.value() + 1e-9) {
                List<String> steps = new ArrayList<>(List.of(r.name));
                steps.addAll(next.steps());
                best = new Route(per, steps);
            }
        }
        return best;
    }

    /** R8c: every crop's best single-crop route (raw sale included) per plot stays within 5x of every other crop's. */
    @Test
    void r8cCropRoutes() {
        record CropRoute(String crop, double plotRoute, List<String> route) {}
        List<CropRoute> cr = new ArrayList<>();
        for (CropRow c : w.cropRows()) {
            Route r = routeValue(c.item(), List.of());
            double mult = c.base() != 0 ? r.value() / c.base() : 1.0;
            cr.add(new CropRoute(c.crop(), c.plot24() * mult, r.steps()));
        }
        List<String> bad = new ArrayList<>();
        double sp = max(cr.stream().map(CropRoute::plotRoute).toList()) / min(cr.stream().map(CropRoute::plotRoute).toList());
        if (sp > ROUTE_SPREAD) {
            CropRoute hi = first(cr, CropRoute::plotRoute, true), lo = first(cr, CropRoute::plotRoute, false);
            bad.add("route spread " + fmt(sp, 2) + ": " + hi.crop() + " " + fmt(hi.plotRoute(), 1) + " (" + String.join(" > ", hi.route()) + ") vs "
                    + lo.crop() + " " + fmt(lo.plotRoute(), 1) + " (" + (lo.route().isEmpty() ? "sold raw" : String.join(" > ", lo.route())) + ")");
        }
        rule("R8c", bad);
    }

    // ============================================================================================ chains

    /** What an item costs in raw things through its own recipe (machine goods expanded, the rest at value). */
    static double rawCost(String item, int depth) {
        List<Row> made = w.rows.stream().filter(r -> r.out.equals(item)).toList();
        if (made.isEmpty() || depth > 8) return w.value(item);
        int t = made.stream().mapToInt(r -> r.tier).min().getAsInt();
        Row r = first(made.stream().filter(x -> x.tier == t).toList(), x -> x.idx, false);
        double cost = 0.0;
        for (var e : r.ins.entrySet()) cost += e.getValue() * (w.madeOn.containsKey(e.getKey()) ? rawCost(e.getKey(), depth + 1) : w.value(e.getKey()));
        return cost / r.amt;
    }

    /**
     * R11: the best full-chain multiple of each job machine (any of its goods: base / raw cost with machine goods expanded through their
     * lowest-tier recipe) stays within 3.0x of every other machine's, so no machine's own chain is a money printer; every job machine has
     * a capstone (CAPSTONE).
     */
    @Test
    void r11ChainSpread() {
        List<String> bad = new ArrayList<>();
        Set<String> capped = new HashSet<>();
        for (var e : CAPSTONE.entrySet()) if (w.sell.containsKey(e.getValue()) && w.madeOn.containsKey(e.getValue())) capped.add(e.getKey());
        if (capped.size() < CAPSTONE.size()) {
            bad.add("no capstone for " + pyList(new TreeSet<>(CAPSTONE.keySet()).stream().filter(m -> !capped.contains(m)).toList()));
            rule("R11", bad);
            return;
        }
        Map<String, Map.Entry<String, Double>> best = new LinkedHashMap<>();
        for (String m : JOB_MACHINES) {
            Map.Entry<String, Double> top = null;
            for (Row r : w.rows) {
                if (!r.m.equals(m) || !w.sell.containsKey(r.out)) continue;
                double c = rawCost(r.out, 0);
                double x = c != 0 ? w.sell.get(r.out) / c : Double.POSITIVE_INFINITY;
                if (top == null || x > top.getValue()) top = Map.entry(r.out, x);
            }
            if (top != null) best.put(m, top);
        }
        List<Double> v = best.values().stream().map(Map.Entry::getValue).toList();
        double sp = max(v) / min(v);
        if (r2(sp) > CHAIN_SPREAD) {
            List<String> each = new ArrayList<>();
            for (var e : best.entrySet()) each.add(e.getKey() + " " + idName(e.getValue().getKey()) + " " + fmt(e.getValue().getValue(), 2));
            bad.add("chain spread " + fmt(sp, 2) + ": " + String.join(", ", each));
        }
        rule("R11", bad);
    }

    // ============================================================================================ fishing ring

    /** Ring.newRound: zoneW = max(3, round(max(3, zone - hits) x (1 + zoneBonus))). */
    static int zoneAt(FishSpec.Rarity r, int hits, double zoneBonus) {
        return Math.max(Ring.MIN_ZONE, (int) Math.round(Math.max(Ring.MIN_ZONE, r.zone() - hits) * (1 + zoneBonus)));
    }

    static double speedAt(FishSpec.Rarity r, int hits) {
        return r.speed() + Ring.HIT_SPEEDUP * hits;
    }

    /** Legends drift their zone (Ring.legend()). */
    static boolean drifts(FishSpec.Rarity r) {
        return r.index() >= 3;
    }

    /**
     * Ticks the needle stays on green at each hit, continuous: zone cells / relative cells per tick (a drifting zone comes 1/3 cell a
     * tick towards the needle in the worst case).
     */
    static List<Double> windows(FishSpec.Rarity r) {
        List<Double> out = new ArrayList<>();
        for (int h = 0; h < r.hits(); h++) out.add(zoneAt(r, h, 0.0) / (speedAt(r, h) + (drifts(r) ? 1.0 / Ring.DRIFT_EVERY : 0.0)));
        return out;
    }

    /**
     * Exact guarantee of Ring.tick() sampling: the needle moves speed cells a tick and a drifting zone one more cell on one tick of every
     * 3, so two consecutive moves span at most 2 x speed (+ 1). The needle is on green for >= 2 ticks at every phase iff the zone is at
     * least that wide.
     */
    static int minSampledTicks(FishSpec.Rarity r, int hits) {
        double span = 2 * speedAt(r, hits) + (drifts(r) ? 1 : 0);
        return zoneAt(r, hits, 0.0) >= span - 1e-9 ? MIN_WINDOW_TICKS : 1;
    }

    /**
     * R12: every hit of every rarity leaves the needle on green for >= 2 server ticks at every phase, no fisher bonus: zone >= 2 x speed
     * (+ 1 cell where the zone drifts), zone = max(3, zone - hits), speed = speed + 0.22 x hits. The rarities stay in order of
     * difficulty: more hits and a narrower narrowest window (continuous, drift against the needle) from common to legend.
     */
    @Test
    void r12Ring() {
        List<String> bad = new ArrayList<>();
        List<FishSpec.Rarity> rs = List.copyOf(w.fish.rarities().values());
        List<Double> mins = new ArrayList<>();
        for (FishSpec.Rarity r : rs) {
            for (int h = 0; h < r.hits(); h++) {
                if (minSampledTicks(r, h) < MIN_WINDOW_TICKS) {
                    bad.add(r.id() + " hit " + (h + 1) + ": zone " + zoneAt(r, h, 0.0) + " < 2 x " + fmt(speedAt(r, h), 2)
                            + (drifts(r) ? " + 1 (drift)" : "") + ": 1 tick on green at some phases");
                }
            }
            mins.add(min(windows(r)));
        }
        for (int i = 1; i < rs.size(); i++) {
            if (!(rs.get(i).hits() > rs.get(i - 1).hits())) bad.add(rs.get(i).id() + " needs no more hits than " + rs.get(i - 1).id());
        }
        for (int i = 1; i < rs.size(); i++) {
            if (!(mins.get(i) < mins.get(i - 1))) {
                bad.add(rs.get(i).id() + " narrowest window " + fmt(mins.get(i), 2) + " not below " + rs.get(i - 1).id() + " " + fmt(mins.get(i - 1), 2));
            }
        }
        rule("R12", bad);
    }

    /**
     * Ring.newRound: the zone's near end is ahead = AHEAD_MIN + nextInt(max(1, min(AHEAD_SPAN, SEG - 13 - zoneW))) cells from the needle,
     * capped so its far end stays behind the needle.
     */
    static List<Integer> aheadRange(int zoneW) {
        List<Integer> out = new ArrayList<>();
        int span = Math.max(1, Math.min(AHEAD_SPAN, Ring.SEG - (AHEAD_MIN - 1) - zoneW));
        for (int a = AHEAD_MIN; a < AHEAD_MIN + span; a++) out.add(a);
        return out;
    }

    /**
     * The zone's far end is ahead + zoneW - 1 cells from the needle's cell: past SEG - 1 it wraps onto the needle's own cell, and a press
     * in the tick the pause ends (the needle has not moved yet) hits.
     */
    static List<Integer> needleOnGreenAtStart(int zoneW, List<Integer> aheads) {
        return aheads.stream().filter(a -> a + zoneW - 1 >= Ring.SEG).toList();
    }

    /**
     * R12b: no round starts with the needle on green: for every rarity, hit (and the re-roll after a miss), fisher level 0-30 (+1% zone
     * a level) and every distance Ring.newRound can draw, the zone's far end stays behind the needle's cell.
     */
    @Test
    void r12bZoneClearOfNeedle() {
        List<String> bad = new ArrayList<>();
        for (FishSpec.Rarity r : w.fish.rarities().values()) {
            for (int h = 0; h < r.hits(); h++) {
                for (int lv = 0; lv <= MAX_LEVEL; lv++) {
                    int zw = zoneAt(r, h, lv * FISHER_ZONE_PER_LEVEL);
                    List<Integer> hit = needleOnGreenAtStart(zw, aheadRange(zw));
                    if (!hit.isEmpty()) {
                        bad.add(r.id() + " hit " + (h + 1) + " Lv" + lv + ": zone " + zw + " from " + hit.getFirst() + " cells ahead covers the needle");
                        break;
                    }
                }
            }
        }
        rule("R12b", bad);
    }

    // ============================================================================================ the model

    static List<Row> jobRows() {
        return w.rows.stream().filter(r -> !r.m.equals("press")).toList();
    }

    /** Every item some recipe takes (group members included). */
    static Set<String> fed() {
        Set<String> used = new HashSet<>();
        for (Row r : w.rows) for (String k : r.ins.keySet()) used.addAll(w.groupOr(k));
        return used;
    }

    /** A number as the model holds it: SHADOW's whole numbers stay whole, which changes how a sum of them rounds ({@link #sum}). */
    record Num(double v, boolean whole) {}

    /**
     * One machine recipe, valued on typical inputs: input value (typ; lo / hi with every group at its cheapest / dearest member),
     * output value (ov), ratio, the dearest member's ratio (worst), G/min, class, the main input.
     */
    // ============================================================================================ job identity, parity (2026-11)

    /** R15: the job's chance at least this many times anyone's, at Lv1 / Lv30. */
    static final double[] SPECIAL_EDGE = {3.0, 10.0};
    /** R15: other jobs whose machines take a special (or the good its own job's machine makes of it). */
    static final int SPECIAL_USERS = 2;
    /** R16: the farmer's same-field edge: harvest-pct at Lv1 at least, the row (1x3) and 3x3 harvests from these levels at the latest. */
    static final double FARMER_PCT_LV1 = 0.20;
    static final int FARMER_ROW = 10, FARMER_AREA = 20;
    /** R17: a job's day vs the jobs' mean at the same level and hours; the named exceptions (each with its reason) within the wide band. */
    static final double PARITY_BAND = 0.15, PARITY_BAND_WIDE = 0.35;
    /** R17: more hours pay more: 3h / 1h and 6h / 3h before the sales tax, and after it. */
    static final double[] HOURS_RATIO = {2.0, 1.5}, HOURS_AFTER_TAX = {1.8, 1.3};
    /** R17: the Lv30 3h jobs' mean, G a day (balance2's money supply ~100k). */
    static final Range MONEY_SUPPLY = new Range(85000, 115000);

    /**
     * R15: a special material is not bought by the shop (players trade it), has a reference value, its job finds it at least 3x (Lv1)
     * / 10x (Lv30) as often as anyone else, and recipes of at least two other jobs' machines take it (or the good its own job's
     * machine makes of it).
     */
    @Test
    void r15Specials() {
        List<String> bad = new ArrayList<>();
        Map<String, String> machineOf = new HashMap<>();
        for (MachineSpec m : w.machines.values()) if (m.job() != null) machineOf.put(m.job(), m.id());
        for (JobSpec.Special s : w.jobs.specials().values()) {
            if (w.sell.containsKey(s.item())) bad.add(s.item() + ": the shop buys it");
            if (s.value() <= 0) bad.add(s.item() + ": no reference value");
            double lo = s.chance().lo(), hi = s.chance().hi(), out = s.outsider();
            if (out > 0 && (lo < SPECIAL_EDGE[0] * out || hi < SPECIAL_EDGE[1] * out)) {
                bad.add(s.item() + ": chance " + py(lo) + ".." + py(hi) + " vs anyone " + py(out));
            }
            Set<String> forms = new HashSet<>(Set.of(s.item()));
            for (Row r : w.rows) if (r.m().equals(machineOf.get(s.job())) && r.ins().containsKey(s.item())) forms.add(r.out());
            Set<String> users = new TreeSet<>();
            for (Row r : w.rows) {
                String job = w.machines.get(r.m()).job();
                if (job != null && !job.equals(s.job()) && r.ins().keySet().stream().anyMatch(forms::contains)) users.add(job);
            }
            if (users.size() < SPECIAL_USERS) bad.add(s.item() + ": used by " + users + " (want " + SPECIAL_USERS + " other jobs)");
        }
        rule("R15", bad);
    }

    /** R16: on the same field a farmer clearly out-harvests anyone (custom crops +20% from Lv1, a row from Lv10, 3x3 from Lv20). */
    @Test
    void r16FarmerEdge() {
        List<String> bad = new ArrayList<>();
        JobSpec.Job f = w.jobs.jobs().get("farmer");
        if (f.harvestPct(1, MAX_LEVEL) < FARMER_PCT_LV1 - 1e-9) bad.add("harvest-pct Lv1 " + py(f.harvestPct(1, MAX_LEVEL)));
        if (f.harvestSpan(FARMER_ROW) < 3) bad.add("no row harvest at Lv" + FARMER_ROW);
        if (f.harvestSpan(FARMER_AREA) < 9) bad.add("no 3x3 harvest at Lv" + FARMER_AREA);
        if (f.harvestBonus(1, MAX_LEVEL) <= 0) bad.add("harvest-bonus Lv1 must be > 0");
        rule("R16", bad);
    }

    /** The parity model's table: economy/src/test/resources/balance/parity.json next to the content (null when another tree is checked). */
    static com.google.gson.JsonObject parityTable() {
        Path p = ROOT.toAbsolutePath().getParent().getParent().resolve("src/test/resources/balance/parity.json");
        try {
            return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * R17: the parity model's table (written by the parity scripts from this content): at every level and 1 / 3 / 6 hours every
     * job's day is within 15% of the jobs' mean (the exceptions the table names, each with its reason, within 35%); more hours pay
     * more for every job (3h >= 2 x 1h and 6h >= 1.5 x 3h before the sales tax, 1.8 / 1.3 after it); the Lv30 3h mean stays near the
     * money supply balance2 set.
     */
    @Test
    void r17Parity() {
        List<String> bad = new ArrayList<>();
        var t = parityTable();
        if (t == null) {
            rule("R17", List.of("parity.json missing"));
            return;
        }
        Set<String> exc = new HashSet<>();
        for (var e : t.getAsJsonArray("exceptions")) exc.add(e.getAsJsonObject().get("point").getAsString());
        for (var point : t.getAsJsonObject("net").entrySet()) {
            var row = point.getValue().getAsJsonObject();
            double mean = row.entrySet().stream().mapToDouble(e -> e.getValue().getAsDouble()).average().orElse(0);
            for (var e : row.entrySet()) {
                double d = e.getValue().getAsDouble() / mean - 1;
                double lim = exc.contains(point.getKey() + "|" + e.getKey()) ? PARITY_BAND_WIDE : PARITY_BAND;
                if (Math.abs(d) > lim + 1e-9) bad.add(point.getKey() + " " + e.getKey() + ": " + Math.round(d * 1000) / 10.0 + "% of the mean");
            }
        }
        for (var lv : t.getAsJsonObject("hours").entrySet()) {
            for (var e : lv.getValue().getAsJsonObject().entrySet()) {
                var a = e.getValue().getAsJsonArray();
                if (a.get(0).getAsDouble() < HOURS_RATIO[0] - 1e-9 || a.get(1).getAsDouble() < HOURS_RATIO[1] - 1e-9
                        || a.get(2).getAsDouble() < HOURS_AFTER_TAX[0] - 1e-9 || a.get(3).getAsDouble() < HOURS_AFTER_TAX[1] - 1e-9) {
                    bad.add("Lv" + lv.getKey() + " " + e.getKey() + ": hours " + a);
                }
            }
        }
        var lv30 = t.getAsJsonObject("net").getAsJsonObject("regular|30");
        double m = lv30.entrySet().stream().mapToDouble(e -> e.getValue().getAsDouble()).average().orElse(0);
        if (!MONEY_SUPPLY.has(m)) bad.add("Lv30 3h mean " + Math.round(m) + " not in " + MONEY_SUPPLY);
        rule("R17", bad);
    }

    /** R18: with trade (one player a job, 0.7-1.3 x base) every gatherer earns more than selling only to the shop, every processor more than gathering its own staple. */
    @Test
    void r18Trade() {
        List<String> bad = new ArrayList<>();
        var t = parityTable();
        if (t == null) {
            rule("R18", List.of("parity.json missing"));
            return;
        }
        for (var point : t.getAsJsonObject("trade").entrySet()) {
            for (var e : point.getValue().getAsJsonObject().entrySet()) {
                var v = e.getValue().getAsJsonObject();
                if (v.get("trade").getAsDouble() <= v.get("solo").getAsDouble()) {
                    bad.add(point.getKey() + " " + e.getKey() + ": trade " + v.get("trade") + " <= solo " + v.get("solo"));
                }
            }
        }
        rule("R18", bad);
    }

    record Row(String m, int idx, String out, String name, int amt, double sec, int tier, boolean aging, Map<String, Integer> ins,
               Map<String, Double> part, Set<String> wholePart, double typ, double hi, double ov, double ratio, double worst, double gmin,
               String cls, String main, List<String> groups) {}

    /** A recipe valued in a scenario: input value, ratio, G/min and per-main (its main input stays the one of the typical valuation). */
    record Valued(double cost, double ratio, double gmin, double perMain) {}

    /** A custom crop: its produce, the net produce a harvest (a replanted seed taken off), the growth steps to the next harvest. */
    record CropRow(String crop, String item, double base, double net, int steps, boolean regrow, int maxAge, int regrowTo, double plot24) {}

    /** The content as the rules see it: one row per machine recipe, the shop's sell bases, groups, fish, jobs, requests and crops. */
    static final class World {
        final YamlConfiguration shopYml, machinesYml, requestsYml;
        final ShopCatalog shop;
        final Map<String, Double> sell = new LinkedHashMap<>();
        final Map<String, MachineSpec.Group> groupSpecs;
        /** The machine module's tag predicate, as RulesTest builds it: a group by its id. */
        final BiPredicate<String, String> tags;
        final Map<String, MachineSpec> machines = new LinkedHashMap<>();
        final Map<String, String> madeOn = new HashMap<>();
        final FishSpec fish;
        final Map<String, FishSpec.Species> species = new HashMap<>();
        final JobSpec jobs;
        final RequestSpec requests;
        final Map<String, CropSpec> crops = new LinkedHashMap<>();
        final List<Row> rows = new ArrayList<>();
        private final Map<String, Double> factors = new HashMap<>();
        private final Map<String, Valued> valuedCache = new HashMap<>();

        World(Path root) {
            // older trees break today's parse checks (the old 'sell' job bonus): the rules report those, so the problems are not asserted
            List<String> problems = new ArrayList<>();
            shopYml = YamlConfiguration.loadConfiguration(root.resolve("shop.yml").toFile());
            machinesYml = YamlConfiguration.loadConfiguration(root.resolve("machines.yml").toFile());
            requestsYml = YamlConfiguration.loadConfiguration(root.resolve("requests.yml").toFile());
            shop = ShopCatalog.parse(shopYml, problems);
            for (ShopCatalog.SellEntry e : shop.sell().values()) sell.put(e.id(), e.base());
            groupSpecs = MachineSpec.groups(machinesYml, problems);
            tags = (tag, item) -> groupSpecs.containsKey(tag) && groupSpecs.get(tag).items().contains(item);
            for (var e : MachineSpec.parse(machinesYml, problems).entrySet()) {
                if (machinesYml.contains(e.getKey() + ".recipes")) machines.put(e.getKey(), e.getValue());
            }
            for (MachineSpec m : machines.values()) for (MachineSpec.Recipe r : m.recipes()) madeOn.putIfAbsent(r.output(), m.id());
            fish = FishSpec.parse(YamlConfiguration.loadConfiguration(root.resolve("fish.yml").toFile()), problems);
            for (FishSpec.Species s : fish.species().values()) species.put(JobSpec.norm(s.item()), s);
            jobs = JobSpec.parse(YamlConfiguration.loadConfiguration(root.resolve("jobs.yml").toFile()), problems);
            requests = RequestSpec.parse(requestsYml, problems);
            YamlConfiguration cropsYml = YamlConfiguration.loadConfiguration(root.resolve("crops/crops.yml").toFile());
            for (String k : cropsYml.getKeys(false)) {
                ConfigurationSection c = cropsYml.getConfigurationSection(k);
                if (c != null && c.contains("harvest")) crops.put(k, CropSpec.parse("economy", k, c));
            }
            for (MachineSpec m : machines.values()) for (MachineSpec.Recipe r : m.recipes()) rows.add(row(m.id(), r));
        }

        // ---- values

        boolean isGroup(String key) {
            return key.startsWith("#") && groupSpecs.containsKey(key.substring(1));
        }

        /** A group key's members, else the key itself. */
        List<String> groupOr(String key) {
            return isGroup(key) ? groupSpecs.get(key.substring(1)).items() : List.of(key);
        }

        double value(String item) {
            return valueNum(item).v();
        }

        Num valueNum(String item) {
            if (isGroup(item)) return median(members(item));
            if (item.startsWith("#")) return new Num(TAG_VALUE.getOrDefault(item, 0.0), false);
            if (sell.containsKey(item)) return new Num(sell.get(item), false);
            Number s = SHADOW.get(item);
            if (s != null) return new Num(s.doubleValue(), s instanceof Integer);
            // a special material (jobs.yml specials): the shop does not buy it, its reference value prices the recipes that take it
            JobSpec.Special sp = jobs.specials().get(item);
            return new Num(sp == null ? 0.0 : sp.value(), false);
        }

        /** Values of everything a key takes (a group's members, nested groups expanded, tags by their table value). */
        List<Num> members(String key) {
            if (!isGroup(key)) return List.of(valueNum(key));
            List<Num> out = new ArrayList<>();
            for (String m : groupOr(key)) {
                if (isGroup(m)) out.addAll(members(m));
                else out.add(valueNum(m));
            }
            return out.isEmpty() ? List.of(new Num(0.0, false)) : out;
        }

        /** A rare or legend fish: it keeps its size and sells by it. */
        boolean sized(String item) {
            FishSpec.Species s = species.get(item);
            return s != null && SIZED_RARITIES.contains(s.rarity());
        }

        /** The size price factor an item is worth in a case: 1.0 for anything unsized or case null. */
        double factor(String item, String sizeCase) {
            if (sizeCase == null || !sized(item)) return 1.0;
            return factors.computeIfAbsent(item + "/" + sizeCase, k -> meanFactor(species.get(item), SIZE_CASES.get(sizeCase)));
        }

        /** Mean FishSpec.priceFactor of a typical catch of a species: FishSpec.size over u = (i + 0.5) / SIZE_SAMPLES. */
        static double meanFactor(FishSpec.Species s, SizeCase c) {
            List<Num> f = new ArrayList<>();
            for (int i = 0; i < SIZE_SAMPLES; i++) {
                f.add(new Num(FishSpec.priceFactor(s, FishSpec.size(s, (i + 0.5) / SIZE_SAMPLES, c.perfect(), c.allPerfect(), c.bonus())), false));
            }
            return sum(f) / SIZE_SAMPLES;
        }

        /** Values of everything an input key takes, each sized fish at its typical size in the case. */
        List<Double> memberValues(String key, String sizeCase) {
            if (!isGroup(key)) return List.of(value(key) * factor(key, sizeCase));
            List<Double> out = new ArrayList<>();
            for (String m : groupOr(key)) {
                if (isGroup(m)) out.addAll(memberValues(m, sizeCase));
                else out.add(value(m) * factor(m, sizeCase));
            }
            return out.isEmpty() ? List.of(0.0) : out;
        }

        /** A recipe valued in a scenario: every group input at its typical, cheapest or dearest member ("typ" / "lo" / "hi"). */
        Valued valued(Row r, String pick, String sizeCase) {
            String key = r.m + "/" + r.idx + "/" + pick + "/" + sizeCase;
            Valued v = valuedCache.get(key);
            if (v != null) return v;
            double cost = 0.0;
            Map<String, Double> part = new HashMap<>();
            for (var e : r.ins.entrySet()) {
                List<Double> vals = memberValues(e.getKey(), sizeCase);
                double x = switch (pick) {
                    case "typ" -> medianOf(vals);
                    case "lo" -> min(vals);
                    default -> max(vals);
                };
                part.put(e.getKey(), e.getValue() * x);
                cost += e.getValue() * x;
            }
            double mins = r.sec / 60.0;
            v = new Valued(cost, cost != 0 ? r.ov / cost : Double.POSITIVE_INFINITY, (r.ov - cost) / mins,
                    (r.ov - (cost - part.get(r.main))) / r.ins.get(r.main));
            valuedCache.put(key, v);
            return v;
        }

        /** Can an item id go into an input key (the same id, or a member of the group): MachineSpec.fits with the module's tags. */
        boolean fits(String key, String item) {
            return MachineSpec.fits(key, item, tags);
        }

        /** Do two input keys take a common item. */
        boolean overlap(String k1, String k2) {
            Set<String> a = new HashSet<>(groupOr(k1));
            return groupOr(k2).stream().anyMatch(a::contains);
        }

        // ---- one recipe

        Row row(String m, MachineSpec.Recipe r) {
            Map<String, Integer> ins = r.inputs();
            double typ = 0.0, hi = 0.0;
            Map<String, Double> part = new LinkedHashMap<>();
            Set<String> wholePart = new HashSet<>();
            int made = 0;
            for (var e : ins.entrySet()) {
                List<Num> vals = members(e.getKey());
                Num v = median(vals);
                typ += e.getValue() * v.v();
                hi += e.getValue() * vals.stream().mapToDouble(Num::v).max().orElseThrow();
                part.put(e.getKey(), e.getValue() * v.v());
                if (v.whole()) wholePart.add(e.getKey());
                if (madeOn.containsKey(e.getKey())) made++;
            }
            String out = r.output();
            double ov = r.amount() * sell.getOrDefault(out, 0.0);
            String main = null;   // the input with the most value (ties: by id)
            for (String k : ins.keySet()) {
                double pk = part.get(k), pm = main == null ? 0 : part.get(main);
                if (main == null || pk > pm || pk == pm && k.compareTo(main) > 0) main = k;
            }
            double mins = r.seconds() / 60.0;
            List<String> groups = ins.keySet().stream().filter(this::isGroup).toList();
            return new Row(m, r.index(), out, idName(out), r.amount(), r.seconds(), r.tier(), r.aging(), ins, part, wholePart, typ, hi, ov,
                    typ != 0 ? ov / typ : Double.POSITIVE_INFINITY, hi != 0 ? ov / hi : Double.POSITIVE_INFINITY, (ov - typ) / mins,
                    made == 0 ? "R" : made == 1 ? "C1" : "C2", main, groups);
        }

        // ---- crops

        List<CropRow> cropRows() {
            List<CropRow> out = new ArrayList<>();
            for (var e : crops.entrySet()) {
                CropSpec c = e.getValue();
                CropSpec.Drop h = c.harvest().getFirst();
                double avg = (h.min() + h.max()) / 2.0;
                double net = avg - (c.seed().equals(h.item()) ? 1 : 0);
                boolean regrow = c.regrow() >= 0;
                int steps = regrow ? c.maxAge() - c.regrow() : c.maxAge();
                double base = sell.getOrDefault(h.item(), 0.0);
                out.add(new CropRow(e.getKey(), h.item(), base, net, steps, regrow, c.maxAge(), c.regrow(), net * base * 7 / steps));
            }
            return out;
        }
    }

    // ============================================================================================ numbers

    /** Half up to 2 decimals, as a player reads it (Math.round(x * 100) / 100.0). */
    static double r2(double x) {
        return Double.isFinite(x) ? Math.floor(x * 100 + 0.5) / 100 : x;
    }

    /** Half up to 1 decimal. */
    static double r1(double x) {
        return Double.isFinite(x) ? Math.floor(x * 10 + 0.5) / 10 : x;
    }

    /** The median: the middle value, or the mean of the two middle ones; a whole middle value stays whole. */
    static Num median(List<Num> data) {
        List<Num> s = new ArrayList<>(data);
        s.sort(Comparator.comparingDouble(Num::v));
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : new Num((s.get(n / 2 - 1).v() + s.get(n / 2).v()) / 2, false);
    }

    static double medianOf(List<Double> data) {
        return median(data.stream().map(x -> new Num(x, false)).toList()).v();
    }

    /**
     * A sum made one fixed way, the balance report's: whole numbers add exactly while every term so far is whole, then fractions add with
     * Neumaier's compensation and later whole numbers plainly, so a ratio at a rounding boundary (1.625) rounds the same in both.
     */
    static double sum(List<Num> terms) {
        double whole = 0, f = 0, c = 0;
        boolean fraction = false;
        for (Num t : terms) {
            if (!fraction) {
                if (t.whole()) whole += t.v();
                else {
                    f = whole + t.v();
                    fraction = true;
                }
            } else if (t.whole()) {
                f += t.v();
            } else {
                double x = t.v(), s = f + x;
                c += Math.abs(f) >= Math.abs(x) ? (f - s) + x : (x - s) + f;
                f = s;
            }
        }
        if (!fraction) return whole;
        return c != 0 && Double.isFinite(c) ? f + c : f;
    }

    /** The exact mean of the values, rounded once. */
    static double mean(List<Double> data) {
        BigDecimal total = BigDecimal.ZERO;
        for (double x : data) total = total.add(new BigDecimal(x));
        return total.divide(BigDecimal.valueOf(data.size()), MathContext.DECIMAL128).doubleValue();
    }

    static double min(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    static double max(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    }

    /** The first element with the highest (or lowest) key. */
    static <T> T first(List<T> list, ToDoubleFunction<T> key, boolean highest) {
        T best = null;
        for (T t : list) {
            if (best == null || (highest ? key.applyAsDouble(t) > key.applyAsDouble(best) : key.applyAsDouble(t) < key.applyAsDouble(best))) best = t;
        }
        return best;
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** "economy:ruby" -> "ruby". */
    static String idName(String id) {
        return id.substring(id.lastIndexOf(':') + 1);
    }

    static <V> Map<String, V> linked(Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            m.put((String) kv[i], v);
        }
        return m;
    }

    // ---- the failure texts print numbers the way the balance report does (shortest float, fixed decimals rounded half to even)

    /** A float the shortest way that reads back the same ("1.2", "130.0", "inf"). */
    static String py(double x) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        if (x == 0) return 1 / x < 0 ? "-0.0" : "0.0";
        String s = new BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    /** A YAML number as written (an int stays an int). */
    static String py(Object o) {
        if (o == null) return "None";
        if (o instanceof Double || o instanceof Float) return py(((Number) o).doubleValue());
        return String.valueOf(o);
    }

    /** A count that is a whole number in the content (q, a cap). */
    static String whole(double x) {
        return x == Math.rint(x) && !Double.isInfinite(x) ? String.valueOf((long) x) : py(x);
    }

    /** Fixed decimals, rounded half to even on the exact value. */
    static String fmt(double x, int digits) {
        if (Double.isNaN(x)) return "nan";
        if (Double.isInfinite(x)) return x > 0 ? "inf" : "-inf";
        String s = new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
        return (x < 0 || x == 0 && 1 / x < 0) && !s.startsWith("-") ? "-" + s : s;
    }

    /** A share as a whole percentage ("82%"). */
    static String pct(double x) {
        return fmt(x * 100, 0) + "%";
    }

    /** Six significant digits, no trailing zeros ("60", "2.5"). */
    static String g(double x) {
        if (x == 0 || !Double.isFinite(x)) return py(x).replace(".0", "");
        BigDecimal b = new BigDecimal(x).round(new MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros();
        int exp = b.precision() - b.scale() - 1;
        if (exp >= -4 && exp < 6) return b.toPlainString();
        String digits = b.unscaledValue().abs().toString();
        String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        return (b.signum() < 0 ? "-" : "") + mantissa + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
    }

    static String pyList(List<String> l) {
        return l.stream().map(s -> "'" + s + "'").collect(Collectors.joining(", ", "[", "]"));
    }
}
