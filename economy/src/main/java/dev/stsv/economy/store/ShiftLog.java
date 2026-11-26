package dev.stsv.economy.store;

/**
 * One shift's tally and its pay (README "편의점" 정산): customers served (★ each, what they earned before the share, the till's
 * sales), customers lost (left angry, gave up, found the shelves empty: ★1 each, nothing earned), wrong change, change given
 * too much. Pure (unit-tested).
 *
 * <p>The pay at clock-out: earned (base + tip + accuracy, every customer) × (1 + rating bonus for the average ★) × the
 * 직영점's share (40%), less overpaid change × pay.overpay-rate; never below 0.
 */
public final class ShiftLog {

    /** The receipt: the parts of the pay (G) and the shift's numbers. */
    public record Payout(long earned, long bonus, long share, long deduction, long pay, double stars, int served, int lost, long sales,
                         int mistakes, double bonusRate, long fines, int handled, double shareRate) {}

    private int served, lost, mistakes, starSum, starCount, handled;
    private long earned, sales, overpaid, covered, fines;

    /** A customer paid and left: their ★, what they earned (G, before the share), their bill (원), whether it went without a mistake. */
    public void served(int stars, long earn, long bill, boolean accurate) {
        served++;
        starSum += Math.clamp(stars, 1, 5);
        starCount++;
        earned += Math.max(0, earn);
        sales += Math.max(0, bill);
        if (!accurate) mistakes++;
    }

    /** A customer left without buying (angry, gave up, nothing on the shelf): ★1. */
    public void lost() {
        lost++;
        starSum += 1;
        starCount++;
    }

    /** Change given too much (원). */
    public void overpaid(long won) {
        overpaid += Math.max(0, won);
    }

    /**
     * 진상 응대 (2차): a customer who left without buying, but the worker did their part (a refund refused, a 취객 seen out):
     * their ★, a bonus (G, before the share). Not lost.
     */
    public void handled(int stars, long bonus) {
        handled++;
        starSum += Math.clamp(stars, 1, 5);
        starCount++;
        earned += Math.max(0, bonus);
    }

    /** A bonus (G, before the share): a good answer, a thief caught, a parcel taken in. */
    public void bonus(long g) {
        earned += Math.max(0, g);
    }

    /** What the worker covers (원: a refund, 외상, 100원 깎아 줌, a stolen thing): taken from the pay like overpaid change. */
    public void covered(long won) {
        covered += Math.max(0, won);
    }

    /** A fine (G, after the share: selling 술 · 담배 to a minor). */
    public void fine(long g) {
        fines += Math.max(0, g);
    }

    public int handledCount() {
        return handled;
    }

    public long coveredWon() {
        return covered;
    }

    public int served() {
        return served;
    }

    public int lostCount() {
        return lost;
    }

    public long sales() {
        return sales;
    }

    public long earned() {
        return earned;
    }

    public long overpaidWon() {
        return overpaid;
    }

    /** The average ★ (0 before anyone came). */
    public double stars() {
        return starCount == 0 ? 0 : (double) starSum / starCount;
    }

    /** What the worker would get if they clocked out now (at the 직영점's share). */
    public Payout payout(StoreSpec spec) {
        return payout(spec, spec.pay().share());
    }

    /** The same at this share (내 가게: 1, the owner's all of it). */
    public Payout payout(StoreSpec spec, double shareRate) {
        double avg = stars();
        double rate = served == 0 ? 0 : spec.ratingBonus(avg);
        long bonus = Math.round(earned * rate);
        long share = Math.round((earned + bonus) * shareRate);
        long deduction = Math.round((overpaid + covered) * spec.pay().overpayRate()) + fines;
        long pay = Math.max(0, share - deduction);
        return new Payout(earned, bonus, share, deduction, pay, avg, served, lost, sales, mistakes, rate, fines, handled, shareRate);
    }
}
