package dev.stsv.economy.shop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 판매 소득세 (shop.yml income-tax): a progressive tax on what one player sold to the admin shop today (since the daily
 * reset). Each bracket taxes only the part of the day's sales above its threshold and below the next one, at its rate, so
 * selling more never leaves anyone with less, and a small seller pays nothing. Pure (unit-tested).
 */
public record IncomeTax(List<Bracket> brackets) {

    /** Sales of the day above {@code over} (up to the next bracket) are taxed at {@code rate}. */
    public record Bracket(long over, double rate) {}

    public static final IncomeTax NONE = new IncomeTax(List.of());

    public IncomeTax {
        List<Bracket> b = new ArrayList<>(brackets);
        b.sort(Comparator.comparingLong(Bracket::over));
        brackets = List.copyOf(b);
    }

    /** The tax on a sale of {@code paid} gold when the player had already sold {@code before} gold today (whole gold, down). */
    public long tax(long before, long paid) {
        if (paid <= 0) return 0;
        double t = 0;
        long lo = Math.max(0, before), hi = lo + paid;
        for (int i = 0; i < brackets.size(); i++) {
            Bracket b = brackets.get(i);
            long top = i + 1 < brackets.size() ? brackets.get(i + 1).over() : Long.MAX_VALUE;
            long from = Math.max(lo, b.over()), to = Math.min(hi, top);
            if (to > from) t += (to - from) * b.rate();
        }
        return Math.min(paid, (long) Math.floor(t + 1e-9));
    }

    /** The tax on a whole day's sales of {@code total} gold. */
    public long taxOn(long total) {
        return tax(0, total);
    }

    /** The marginal rate at a day's sales so far (for the sell screen). */
    public double rateAt(long soldToday) {
        double r = 0;
        for (Bracket b : brackets) if (soldToday >= b.over()) r = b.rate();
        return r;
    }

    /** income-tax: [ { over: gold, rate: 0.1 }, ... ]; missing = no tax. */
    public static IncomeTax parse(ConfigurationSection c, List<String> problems) {
        List<Bracket> out = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("income-tax")) {
            Object over = m.get("over"), rate = m.get("rate");
            if (!(over instanceof Number o) || !(rate instanceof Number r) || o.longValue() < 0 || r.doubleValue() < 0 || r.doubleValue() >= 1) {
                problems.add("income-tax: over (0 이상), rate (0 이상 1 미만) 필요 (" + m + ")");
                continue;
            }
            out.add(new Bracket(o.longValue(), r.doubleValue()));
        }
        return out.isEmpty() ? NONE : new IncomeTax(out);
    }
}
