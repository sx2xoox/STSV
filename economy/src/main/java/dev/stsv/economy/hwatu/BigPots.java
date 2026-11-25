package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 오늘의 큰 판 on the 도박장 whiteboard (README "도박장"): the biggest pots won today (a new day starts at 05시 with an empty
 * board), the top few kept. Stored as one line of text (eco_hwatu_hall "records"). Pure (unit-tested).
 */
public final class BigPots {

    /** One win: the pot and the line shown (MiniMessage: who, where, with what). */
    public record Entry(long pot, String line) {}

    private int day;
    private final int keep;
    private final List<Entry> entries = new ArrayList<>();

    public BigPots(int keep) {
        this.keep = Math.max(1, keep);
    }

    /** Today's entries, biggest first (an empty list once the day has turned). */
    public List<Entry> entries(int today) {
        if (today != day) return List.of();
        return List.copyOf(entries);
    }

    /** A win of that pot today: true if it made the board. */
    public boolean add(int today, long pot, String line) {
        if (today != day) {
            day = today;
            entries.clear();
        }
        entries.add(new Entry(pot, line.replace('\n', ' ').replace('\t', ' ')));
        entries.sort(Comparator.comparingLong(Entry::pot).reversed());
        boolean made = true;
        while (entries.size() > keep) made &= !entries.removeLast().line().equals(line);
        return made;
    }

    /** As stored: "day\npot\tline\npot\tline…". */
    public String encode() {
        StringBuilder b = new StringBuilder().append(day);
        for (Entry e : entries) b.append('\n').append(e.pot()).append('\t').append(e.line());
        return b.toString();
    }

    public static BigPots decode(String s, int keep) {
        BigPots p = new BigPots(keep);
        if (s == null || s.isBlank()) return p;
        String[] lines = s.split("\n");
        try {
            p.day = Integer.parseInt(lines[0].trim());
        } catch (NumberFormatException e) {
            return p;
        }
        for (int i = 1; i < lines.length; i++) {
            int tab = lines[i].indexOf('\t');
            if (tab < 0) continue;
            try {
                p.entries.add(new Entry(Long.parseLong(lines[i].substring(0, tab)), lines[i].substring(tab + 1)));
            } catch (NumberFormatException ignored) {
                // a broken line is dropped
            }
        }
        p.entries.sort(Comparator.comparingLong(Entry::pot).reversed());
        while (p.entries.size() > p.keep) p.entries.removeLast();
        return p;
    }
}
