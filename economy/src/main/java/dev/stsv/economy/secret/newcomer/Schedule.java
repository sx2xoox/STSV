package dev.stsv.economy.secret.newcomer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * When he comes: once a night, at one minute inside the hour window [from, to) (Korean time; the window may cross
 * midnight, "22 to 2" = 22:00–01:59). The minute is chosen from the night's date alone, so a restart neither repeats nor
 * skips it. A "night" is named by the date its window opens on (00:30 of the 23rd belongs to the night of the 22nd).
 */
final class Schedule {

    private Schedule() {}

    static int hour(int h) {
        return Math.floorMod(h, 24);
    }

    /** How long the window is, in minutes (from == to: the whole day). */
    static int minutes(int from, int to) {
        int h = Math.floorMod(hour(to) - hour(from), 24);
        return (h == 0 ? 24 : h) * 60;
    }

    /** The night this moment belongs to, when it is inside the window. */
    static Optional<LocalDate> night(LocalDateTime now, int from, int to) {
        int f = hour(from), t = hour(to), h = now.getHour();
        if (f < t) return h >= f && h < t ? Optional.of(now.toLocalDate()) : Optional.empty();
        if (h >= f) return Optional.of(now.toLocalDate());
        if (h < t || f == t) return Optional.of(now.toLocalDate().minusDays(1));
        return Optional.empty();
    }

    /** The night this moment belongs to, or the next one to open. */
    static LocalDate next(LocalDateTime now, int from, int to) {
        return night(now, from, to).orElseGet(() -> now.getHour() < hour(from) ? now.toLocalDate() : now.toLocalDate().plusDays(1));
    }

    /** That night's minute, counted from the window's opening: the same for the same date and seed, inside [0, minutes). */
    static int offset(LocalDate night, int from, int to, long seed) {
        long z = night.toEpochDay() * 0x9E3779B97F4A7C15L + seed * 0xD1B54A32D192ED03L + 0x2545F4914F6CDD1DL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (int) Math.floorMod(z, (long) minutes(from, to));
    }

    /** The moment he comes on that night. */
    static LocalDateTime planned(LocalDate night, int from, int to, long seed) {
        return night.atTime(hour(from), 0).plusMinutes(offset(night, from, to, seed));
    }
}
