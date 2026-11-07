package dev.stsv.core.crop;

import java.util.Random;

/**
 * Vanilla crop growth (CropBlock), for crops that are not blocks. Every game tick each 16x16x16
 * section gets randomTickSpeed random ticks; a random tick on a crop with light 9+ grows it one age
 * with chance 1 / (floor(25 / speed) + 1), where speed comes from the farmland under and around it.
 * Pure (unit-tested).
 */
public final class CropGrowth {

    private CropGrowth() {}

    /**
     * The growth speed. soil[dx+1][dz+1]: 0 = not farmland, 1 = dry farmland, 2 = wet farmland, for the
     * 3x3 blocks under the crop (centre [1][1]). Same crops beside it (north or south and west or east,
     * or any diagonal) halve it, so rows grow faster than a solid field.
     */
    public static double speed(int[][] soil, boolean sameNorthSouth, boolean sameWestEast, boolean sameDiagonal) {
        double f = 1.0;
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                double g = soil[dx][dz] == 2 ? 3 : soil[dx][dz] == 1 ? 1 : 0;
                if (dx != 1 || dz != 1) g /= 4;
                f += g;
            }
        }
        if (sameNorthSouth && sameWestEast) f /= 2;
        else if (sameDiagonal) f /= 2;
        return f;
    }

    /** Chance that one random tick grows the crop. */
    public static double chance(double speed) {
        return 1.0 / (Math.floor(25.0 / speed) + 1);
    }

    /** How many random ticks one block gets in this many game ticks (Poisson). */
    public static int randomTicks(long gameTicks, int randomTickSpeed, Random r) {
        double lambda = gameTicks * (double) Math.max(0, randomTickSpeed) / 4096.0;
        if (lambda <= 0) return 0;
        if (lambda > 30) return (int) Math.max(0, Math.round(lambda + Math.sqrt(lambda) * r.nextGaussian()));
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    /** The age after these random ticks. */
    public static int grow(int age, int maxAge, int randomTicks, double chance, Random r) {
        for (int i = 0; i < randomTicks && age < maxAge; i++) if (r.nextDouble() < chance) age++;
        return age;
    }

    /** Chance per item and Fortune level on a custom crop's produce (Fortune III: +45%). */
    public static final double FORTUNE_PER_LEVEL = 0.15;

    /**
     * Fortune on a harvest: every one of the n produce items gets one more with chance 0.15 x level, so the
     * bonus is a share of the harvest (a one-melon crop and a four-bean crop gain alike). Seeds get none.
     */
    public static int fortune(int level, int n, Random r) {
        double p = Math.min(1, FORTUNE_PER_LEVEL * Math.max(0, level));
        if (p <= 0 || n <= 0) return 0;
        int k = 0;
        for (int i = 0; i < n; i++) if (r.nextDouble() < p) k++;
        return k;
    }

    /** One bone meal for every this many ages a plant grew by itself. */
    public static final int MEAL_EVERY = 7;

    /** Bone meal: one age (never past ripe). */
    public static int boneMeal(int age, int maxAge) {
        return Math.min(maxAge, age + 1);
    }

    /** May the plant take a bone meal: one per 7 ages it grew by itself, counted over its whole life. */
    public static boolean mealAllowed(int grown, int meals) {
        return meals < grown / MEAL_EVERY;
    }

    /** Meals counted with at most one unused meal banked: credits beyond one are dropped, so meals cannot be saved up. */
    public static int mealsBanked(int grown, int meals) {
        return Math.max(meals, grown / MEAL_EVERY - 1);
    }

    /** Ages it still has to grow by itself before the next bone meal works. */
    public static int agesToNextMeal(int grown, int meals) {
        return (meals + 1) * MEAL_EVERY - grown;
    }
}
