package dev.stsv.economy.secret.jesa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The plain sums of the 후속 임무 「기일」 ({@link JesaSequel}): which family asks this week and with which circumstance
 * ({@link Variant}), the families themselves, and the lines the solver's 족보 gains, one family a week. Pure
 * (JesaGiilTest).
 */
final class Giil {

    private Giil() {}

    /** Steps of the mission: the passenger asked; their house stands (the table is to be laid); the table was accepted. */
    static final int ASKED = 0, HOUSE = 1, ACCEPTED = 2;

    /**
     * A family of the pool (jesa.yml {@code sequel.families}).
     *
     * @param ask    what the passenger says on the bus (empty: one of the common lines)
     * @param house  what they say where they get off
     * @param meal   said at the table while they eat
     * @param thanks said when it is over
     * @param father the husband's tablet, and the wife's ({@code mother})
     * @param jokbo  the lines the 족보 gains ({date} = the day)
     */
    record Family(String id, String name, String ask, String house, String meal, String thanks, String father, String mother, List<String> jokbo) {}

    static List<Family> families(List<Map<?, ?>> list) {
        List<Family> out = new ArrayList<>();
        int n = 0;
        for (Map<?, ?> m : list) {
            n++;
            String id = m.get("id") == null ? "family" + n : String.valueOf(m.get("id"));
            List<String> jokbo = new ArrayList<>();
            if (m.get("jokbo") instanceof List<?> l) for (Object o : l) jokbo.add(String.valueOf(o));
            else if (m.get("jokbo") != null) jokbo.add(String.valueOf(m.get("jokbo")));
            String name = str(m, "name", id);
            if (jokbo.isEmpty()) jokbo.add("<#6b6258>" + name + " 댁");
            Object t = m.get("tablets");
            String father = t instanceof Map<?, ?> tm && tm.get("father") != null ? String.valueOf(tm.get("father")) : "顯考學生府君神位";
            String mother = t instanceof Map<?, ?> tm && tm.get("mother") != null ? String.valueOf(tm.get("mother")) : "顯妣孺人神位";
            out.add(new Family(id, name, str(m, "ask", ""), str(m, "house", "…저기가 우리 집이오."), str(m, "meal", "…"), str(m, "thanks", "…잘 먹었소."),
                    father, mother, List.copyOf(jokbo)));
        }
        return out;
    }

    private static String str(Map<?, ?> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    /** The same for one player in one week (a restart goes on with the same family), different the next week. */
    static long seed(String week, UUID player) {
        long h = 3141592653589793L;
        for (int i = 0; i < week.length(); i++) h = 31 * h + week.charAt(i);
        return h ^ player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 29);
    }

    /**
     * This week's one of `ids`: fixed by the seed, and not one of the recent ones while another is left (null: no ids).
     */
    static String pick(long seed, List<String> ids, Collection<String> recent) {
        if (ids.isEmpty()) return null;
        int first = (int) Math.floorMod(seed ^ (seed >>> 27), (long) ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get((first + i) % ids.size());
            if (!recent.contains(id)) return id;
        }
        return ids.get(first);
    }

    static List<String> list(String saved) {
        List<String> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String w : saved.split(";")) if (!w.isBlank()) out.add(w.trim());
        return out;
    }

    /** The list with one more at its end, cut to the last `keep`; saved with ";" between. */
    static String remember(String saved, String one, int keep) {
        List<String> out = list(saved);
        out.remove(one);
        out.add(one);
        while (out.size() > Math.max(0, keep)) out.removeFirst();
        return String.join(";", out);
    }

    /**
     * Whether the weekly house may be theirs right now, by their stage in the secret itself: only when the secret's own
     * house cannot stand for them — solved (stage past the last), or never begun (a tester let in by an admin). The two
     * houses never stand at once.
     */
    static boolean free(int stage, int stages) {
        return stage == 0 || stage > stages;
    }

    // ------------------------------------------------------------------------------------------
    // the 족보: one more family a week
    // ------------------------------------------------------------------------------------------

    /** One table set for a family: which, and the day ("2026년 11월 23일"). */
    record Entry(String family, String date) {}

    /** Kept as "family@date;family@date" (oldest first). */
    static List<Entry> entries(String saved) {
        List<Entry> out = new ArrayList<>();
        for (String w : list(saved)) {
            int at = w.indexOf('@');
            out.add(at < 0 ? new Entry(w, "") : new Entry(w.substring(0, at), w.substring(at + 1)));
        }
        return out;
    }

    /** The kept value with one more entry, the oldest dropped past `max`. */
    static String add(String saved, Entry e, int max) {
        List<String> out = list(saved);
        out.add(e.family() + "@" + e.date().replace(";", " ").replace("@", " "));
        while (out.size() > Math.max(1, max)) out.removeFirst();
        return String.join(";", out);
    }

    /**
     * The pages the book gains after its own: `header` on top of each, then every entry's lines (its family's, {date}
     * filled in; a family the file no longer has is written by its id), `perPage` entries a page, a blank line between.
     */
    static List<List<String>> pages(List<Entry> entries, Map<String, Family> families, List<String> header, int perPage) {
        List<List<String>> out = new ArrayList<>();
        int per = Math.max(1, perPage);
        for (int i = 0; i < entries.size(); i += per) {
            List<String> page = new ArrayList<>(header);
            for (int k = i; k < Math.min(entries.size(), i + per); k++) {
                Entry e = entries.get(k);
                Family f = families.get(e.family());
                if (k > i || !header.isEmpty()) page.add("");
                if (f == null) page.add("<#6b6258>" + e.family());
                else for (String l : f.jokbo()) page.add(l.replace("{date}", e.date()));
                if (f == null || f.jokbo().stream().noneMatch(l -> l.contains("{date}"))) page.add("<dark_gray>" + e.date());
            }
            out.add(page);
        }
        return out;
    }
}
