package dev.stsv.core.backup;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which backups to keep: the newest {@code recent} of them, then the newest of each of the last {@code days} days, then
 * the newest of each of the last {@code weeks} weeks (Monday to Sunday). So with 24 / 7 / 4 an hourly backup leaves a
 * day of hours, a week of days and a month of weeks; everything else goes. Pure (unit-tested).
 */
public record Retention(int recent, int days, int weeks) {

    /** The backups (their times) to delete, given all there are and the time now. */
    public List<LocalDateTime> drop(List<LocalDateTime> all, LocalDateTime now) {
        List<LocalDateTime> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.reverseOrder());
        Set<LocalDateTime> keep = new HashSet<>();
        for (int i = 0; i < Math.min(recent, sorted.size()); i++) keep.add(sorted.get(i));
        Set<Object> daysSeen = new HashSet<>(), weeksSeen = new HashSet<>();
        for (LocalDateTime t : sorted) {
            if (days > 0 && !t.toLocalDate().isBefore(now.toLocalDate().minusDays(days - 1)) && daysSeen.add(t.toLocalDate())) keep.add(t);
            var monday = t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            var thisMonday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (weeks > 0 && !monday.isBefore(thisMonday.minusWeeks(weeks - 1)) && weeksSeen.add(monday)) keep.add(t);
        }
        List<LocalDateTime> out = new ArrayList<>();
        for (LocalDateTime t : sorted) if (!keep.contains(t)) out.add(t);
        return out;
    }
}
