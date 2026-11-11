package dev.stsv.economy.dock;

/**
 * /관리자 하역장 후보: what a straight land route from the town to a candidate site is like, from heightmap samples taken
 * every `step` blocks along it (README "하역장"). Pure (unit-tested).
 *
 * @param crossings  how many separate stretches of water it crosses (each a bridge)
 * @param water      blocks of it over water
 * @param longestWater the longest single water stretch (blocks)
 * @param steepShare share of steps steeper than the road's grade (1 in 3): cuts, fills or tunnels
 * @param maxGrade   the steepest step (blocks up or down per block along)
 * @param climb      all the up and down along it (blocks)
 * @param siteRange  the candidate site's unevenness: highest - lowest ground over the dock's pad
 * @param siteWater  the site itself is mostly water
 * @param shore      water within reach ahead of the site (a 항구 fits)
 */
public record RouteStats(int crossings, int water, int longestWater, double steepShare, double maxGrade, int climb, int siteRange,
                         boolean siteWater, boolean shore) {

    /**
     * From the samples along the route (heights[i] ground, water[i] over water), every `step` blocks; the site's samples
     * (ground heights over the pad, which of them water) and those ahead of it.
     */
    public static RouteStats of(int[] heights, boolean[] water, int step, int[] site, boolean[] siteWet, boolean[] ahead) {
        int crossings = 0, wet = 0, run = 0, longest = 0, steep = 0, climb = 0;
        double max = 0;
        for (int i = 0; i < heights.length; i++) {
            if (water[i]) {
                wet += step;
                run += step;
                longest = Math.max(longest, run);
                if (i == 0 || !water[i - 1]) crossings++;
            } else {
                run = 0;
            }
            if (i > 0 && !water[i] && !water[i - 1]) {
                int dh = Math.abs(heights[i] - heights[i - 1]);
                climb += dh;
                double g = dh / (double) step;
                max = Math.max(max, g);
                if (g > 1 / 3.0) steep++;
            }
        }
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, wetSite = 0;
        for (int i = 0; i < site.length; i++) {
            lo = Math.min(lo, site[i]);
            hi = Math.max(hi, site[i]);
            if (siteWet[i]) wetSite++;
        }
        boolean shore = false;
        for (boolean a : ahead) shore |= a;
        int steps = Math.max(1, heights.length - 1);
        return new RouteStats(crossings, wet, longest, steep / (double) steps, max, climb, site.length == 0 ? 0 : hi - lo,
                wetSite * 2 > site.length, shore);
    }

    /** A rough score, lower is better: water and steepness cost most, then an uneven site. */
    public double score() {
        return water / 50.0 + crossings * 2 + steepShare * 40 + climb / 200.0 + siteRange / 4.0 + (siteWater ? 20 : 0);
    }
}
