package dev.stsv.economy.land.log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "30m", "6h", "2d", "1w", "1d12h", "90" (minutes) -> milliseconds; -1 when it is not a time. Pure. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)(m|min|분|h|시간|d|일|w|주)?");

    private Durations() {}

    public static long parse(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return -1;
        Matcher m = PART.matcher(t);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2) == null ? "m" : m.group(2);
            long ms = switch (unit) {
                case "h", "시간" -> 3_600_000L;
                case "d", "일" -> 86_400_000L;
                case "w", "주" -> 7 * 86_400_000L;
                default -> 60_000L;
            };
            total += n * ms;
            end = m.end();
        }
        return end == t.length() && total > 0 ? total : -1;
    }

    /** 90 minutes -> "1시간 30분" (for messages). */
    public static String text(long ms) {
        long min = Math.max(0, ms / 60_000L);
        long d = min / 1440, h = min % 1440 / 60, m = min % 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("일 ");
        if (h > 0) b.append(h).append("시간 ");
        if (m > 0 || b.isEmpty()) b.append(m).append("분");
        return b.toString().trim();
    }
}
