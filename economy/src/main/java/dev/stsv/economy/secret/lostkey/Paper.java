package dev.stsv.economy.secret.lostkey;

import dev.stsv.economy.secret.Memory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The paper in the locker (분실물 보관증): the player's first records on this server written as if someone had handed them
 * in — the day they first came, where, the first thing they sold. Pure: the values come from {@link Memory} and the
 * ledger, the wording from lostkey.yml.
 */
final class Paper {

    private Paper() {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** The oldest sale of a newest-first list (the ledger's order), or null. */
    static Memory.Sale oldest(List<Memory.Sale> newestFirst) {
        Memory.Sale best = null;
        for (Memory.Sale s : newestFirst) if (best == null || s.at() <= best.at()) best = s;
        return best;
    }

    /** "2026년 3월 4일" (Korean time), or "" for an unknown time. */
    static String date(long ms) {
        if (ms <= 0) return "";
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(KST);
        return t.getYear() + "년 " + t.getMonthValue() + "월 " + t.getDayOfMonth() + "일";
    }

    /** "오후 3시 12분" (Korean time). */
    static String time(long ms) {
        if (ms <= 0) return "";
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(KST);
        int h = t.getHour();
        return (h < 12 ? "오전 " : "오후 ") + (h % 12 == 0 ? 12 : h % 12) + "시 " + t.getMinute() + "분";
    }

    /** The first lost item given back, as kept: "time\tthing\towner". */
    static String encodeBack(long at, String thing, String owner) {
        return at + "\t" + clean(thing) + "\t" + clean(owner);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').trim();
    }

    /**
     * The paper's line for the first lost item given back: format with {owner} and {thing} ("할머니의 지갑"), or the
     * plain one ({thing}) when who left it is not known. Null when nothing was kept (given back before it was kept): the
     * line is left out.
     */
    static String firstBack(String saved, String format, String plain) {
        if (saved == null || saved.isBlank()) return null;
        String[] w = saved.split("\t", -1);
        if (w.length < 2 || w[1].isBlank()) return null;
        String owner = w.length > 2 ? w[2] : "";
        String f = owner.isBlank() || format == null || format.isBlank() ? plain : format;
        if (f == null || f.isBlank()) f = "{thing}";
        return f.replace("{owner}", owner).replace("{thing}", w[1]);
    }

    /**
     * Fills {key} in each line; a line with a {key} that has no value (null) is left out, so a record that is not known
     * simply is not written.
     */
    static List<String> fill(List<String> lines, Map<String, String> v) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String s = line;
            boolean missing = false;
            int at = 0;
            while (true) {
                int a = s.indexOf('{', at), b = a < 0 ? -1 : s.indexOf('}', a);
                if (a < 0 || b < 0) break;
                String key = s.substring(a + 1, b);
                if (!v.containsKey(key)) {
                    at = b + 1;   // not ours (left as it is)
                    continue;
                }
                String val = v.get(key);
                if (val == null) {
                    missing = true;
                    break;
                }
                s = s.substring(0, a) + val + s.substring(b + 1);
                at = a + val.length();
            }
            if (!missing) out.add(s);
        }
        return out;
    }
}
