package dev.stsv.economy.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * One day's economy health check (the plan's 밸런스 목표): what the numbers are and which ones are out of
 * their normal range, with what to adjust. Pure (unit-tested).
 */
public record Report(int day, long supply, long previousSupply, long faucet, long sink, double gini,
                     double lowHourly, double topHourly, int floorItems, int players, double targetRatio) {

    public double growth() {
        return previousSupply <= 0 ? 0 : (double) (supply - previousSupply) / previousSupply;
    }

    public double ratio() {
        return faucet <= 0 ? 0 : (double) sink / faucet;
    }

    /** Problems as message keys, each with its numbers. */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (previousSupply > 0 && growth() > 0.08) out.add("report.warn-growth-high");
        if (previousSupply > 0 && growth() < 0.03) out.add("report.warn-growth-low");
        if (faucet > 0 && ratio() < targetRatio - 0.05) out.add("report.warn-ratio-low");
        if (faucet > 0 && ratio() > targetRatio + 0.05) out.add("report.warn-ratio-high");
        if (players >= 4 && gini > 0.6) out.add("report.warn-gini");
        if (topHourly > 0 && lowHourly < topHourly / 4) out.add("report.warn-gap");
        if (floorItems > 2) out.add("report.warn-floor");
        return out;
    }

    /** Average hourly income of the bottom and top quarter (players with at least 10 minutes online). */
    public static double[] quartiles(List<long[]> incomeAndSeconds) {
        List<Double> rates = new ArrayList<>();
        for (long[] r : incomeAndSeconds) if (r[1] >= 600) rates.add(r[0] * 3600.0 / r[1]);
        if (rates.isEmpty()) return new double[]{0, 0};
        rates.sort(Double::compare);
        int q = Math.max(1, rates.size() / 4);
        double low = 0, top = 0;
        for (int i = 0; i < q; i++) {
            low += rates.get(i);
            top += rates.get(rates.size() - 1 - i);
        }
        return new double[]{low / q, top / q};
    }
}
