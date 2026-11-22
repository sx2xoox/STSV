package dev.stsv.economy.secret.jesa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rite table of 주인 없는 제사 as pure data: 5 rows of 9 places (row 0 nearest the folding screen, which stands on the
 * north; column 0 is the west end, column 8 the east end — the one who bows stands on the south, so east is their right
 * hand), what lies where ("economy:rice" …), and the judging by the real rules of a 제사상:
 * <ol>
 * <li>row 0, 메 · 갱 · 잔: rice west, soup east (반서갱동), liquor anywhere;</li>
 * <li>row 1, 적 · 전: meat west, fish east (어동육서), a 전 between them;</li>
 * <li>row 2, 탕: a soup, not one already in row 0, no soup twice;</li>
 * <li>row 3, 포 · 나물 · 혜: dried fish west, 식혜 east (좌포우혜), 나물 between if any;</li>
 * <li>row 4, 과일: pale fruit west, red fruit east (홍동백서), rice cakes and the like between if any.</li>
 * </ol>
 * Anything taboo (chili, garlic, a fish whose name ends in 치, red beans) is wrong wherever it lies, and so is anything
 * that has no business in its row. Nothing here touches the server (unit-tested).
 */
final class Table {

    static final int COLS = 9, ROWS = 5, SLOTS = COLS * ROWS;
    /**
     * The middle column. With {@link Rules#halves} a west thing lies in 0..4 and an east thing in 4..8: the middle place
     * belongs to neither half, so whichever stands on it is judged by its neighbour alone (rice on 4 with the soup on 5 is
     * rice west of soup; a table gives no reason when a dish rolls off, so a right pair one place off-centre must not).
     */
    static final int MID = 4;
    /** Where a missing dish is pointed at: the west side, the middle, the east side of its row. */
    static final int WEST_AT = 2, EAST_AT = 6;

    private Table() {}

    /**
     * What may lie where (item ids as {@code Items.key} gives them). halves: no west thing lies east of the middle column
     * and no east thing west of it (and every west thing is west of every east thing); without it only that order counts.
     */
    record Rules(Set<String> rice, Set<String> soup, Set<String> liquor, Set<String> meat, Set<String> fish, Set<String> jeon,
                 Set<String> dried, Set<String> sikhye, Set<String> namul, Set<String> pale, Set<String> red, Set<String> cake,
                 Set<String> taboo, boolean halves) {

        /** The same rules with more taboo things (the 치 fish, worked out from their names). */
        Rules alsoTaboo(Collection<String> more) {
            Set<String> t = new LinkedHashSet<>(taboo);
            t.addAll(more);
            return new Rules(rice, soup, liquor, meat, fish, jeon, dried, sikhye, namul, pale, red, cake, t, halves);
        }

        /** jesa.yml {@code table}: a kind the file does not list keeps its usual ids ({@link #defaults}). */
        static Rules read(org.bukkit.configuration.ConfigurationSection c) {
            Rules d = defaults();
            if (c == null) return d;
            return new Rules(ids(c, "rice", d.rice), ids(c, "soup", d.soup), ids(c, "liquor", d.liquor), ids(c, "meat", d.meat),
                    ids(c, "fish", d.fish), ids(c, "jeon", d.jeon), ids(c, "dried", d.dried), ids(c, "sikhye", d.sikhye),
                    ids(c, "namul", d.namul), ids(c, "pale", d.pale), ids(c, "red", d.red), ids(c, "sweets", d.cake),
                    ids(c, "taboo", d.taboo), c.getBoolean("halves", true));
        }

        private static Set<String> ids(org.bukkit.configuration.ConfigurationSection c, String key, Set<String> def) {
            if (!c.isList(key)) return def;
            Set<String> out = new LinkedHashSet<>();
            for (String s : c.getStringList(key)) if (!s.isBlank()) out.add(norm(s));
            return out;
        }

        /** The rules as shipped (the file's own lists). */
        static Rules defaults() {
            return new Rules(eco("rice"), eco("bogeo_jiri", "eomuktang", "pollack_stew", "doenjang_jjigae", "kalguksu"),
                    eco("cheongju", "golden_cheongju", "century_yakju", "makgeolli", "soju"), eco("bulgogi"),
                    eco("godeungeo_gui", "fish_platter", "red_seabream", "croaker", "pollack", "sunset_bream", "gulbi", "gulbi_dureum"),
                    eco("saengseonjeon", "hobakjeon", "gamjajeon", "pajeon"), eco("dried_fish_stirfry", "dried_fish", "dried_pollack", "gulbi"),
                    eco("sikhye"), eco("japchae", "gyeranmari"), eco("muskmelon"), set("minecraft:apple", "economy:strawberry"),
                    eco("injeolmi", "golden_injeolmi", "ginseng_jeonggwa", "grapes"),
                    eco("chili_pepper", "chili_seeds", "garlic", "chili_powder", "gochujang", "jang_set", "royal_jang_set", "kimchi", "kkakdugi",
                            "oi_sobagi", "oi_muchim", "sundubu_jjigae", "kimchi_jjigae", "maeuntang", "dakbokkeumtang", "gamjatang",
                            "miyeokguk", "samgyetang", "ginseng_samgyetang", "jeyuk", "bossam", "bibimbap", "tteokbokki", "grilled_eel",
                            "braised_mackerel", "agujjim", "mulhoe", "steamed_crab", "grilled_fish_set", "eggplant_stirfry",
                            "beoseot_bokkeum", "gamja_jorim", "gujeolpan", "hanjeongsik", "royal_eoran", "royal_surasang", "gosa_tteok"),
                    true);
        }

        private static Set<String> eco(String... ids) {
            Set<String> out = new LinkedHashSet<>();
            for (String s : ids) out.add("economy:" + s);
            return out;
        }

        private static Set<String> set(String... ids) {
            return new LinkedHashSet<>(List.of(ids));
        }

        /** Every id the rules name (to check them against the items that exist). */
        Set<String> all() {
            Set<String> out = new LinkedHashSet<>();
            for (Set<String> s : List.of(rice, soup, liquor, meat, fish, jeon, dried, sikhye, namul, pale, red, cake, taboo)) out.addAll(s);
            return out;
        }
    }

    /** wrong: the places whose dish must go (taboo, in the wrong row, on the wrong side); missing: places a dish is owed. */
    record Verdict(Set<Integer> wrong, List<Integer> missing) {
        boolean ok() {
            return wrong.isEmpty() && missing.isEmpty();
        }
    }

    static int slot(int row, int col) {
        return row * COLS + col;
    }

    static int row(int slot) {
        return slot / COLS;
    }

    static int col(int slot) {
        return slot % COLS;
    }

    /** "apple" and "minecraft:apple" are the same; "economy:rice" stays. */
    static String norm(String id) {
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** Whether a name is a 치 fish's ("갈치", "거대 가물치"): they are never offered. */
    static boolean endsIn(String plainName, String suffix) {
        return plainName != null && suffix != null && !suffix.isEmpty() && plainName.strip().endsWith(suffix);
    }

    // ------------------------------------------------------------------------------------------
    // judging
    // ------------------------------------------------------------------------------------------

    static Verdict judge(Map<Integer, String> layout, Rules r) {
        Set<Integer> wrong = new TreeSet<>();
        List<Integer> missing = new ArrayList<>();
        List<Map<Integer, String>> rows = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) rows.add(new TreeMap<>());
        for (Map.Entry<Integer, String> e : layout.entrySet()) {
            int s = e.getKey();
            if (s < 0 || s >= SLOTS || e.getValue() == null) continue;
            if (r.taboo.contains(e.getValue())) wrong.add(s);
            else rows.get(row(s)).put(col(s), e.getValue());
        }
        sides(0, rows.get(0), r.rice, r.soup, Set.of(), r.liquor, false, true, r.halves, wrong, missing);
        sides(1, rows.get(1), r.meat, r.fish, r.jeon, Set.of(), true, false, r.halves, wrong, missing);
        soups(rows.get(0), rows.get(2), r, wrong, missing);
        sides(3, rows.get(3), r.dried, r.sikhye, r.namul, Set.of(), false, false, r.halves, wrong, missing);
        sides(4, rows.get(4), r.pale, r.red, r.cake, Set.of(), false, false, r.halves, wrong, missing);
        return new Verdict(wrong, missing);
    }

    /**
     * A row with a west kind and an east kind (both owed), things that go between them (owed when needBetween) and things
     * that may lie anywhere in it (owed when needFree).
     */
    private static void sides(int row, Map<Integer, String> cells, Set<String> west, Set<String> east, Set<String> between, Set<String> free,
                              boolean needBetween, boolean needFree, boolean halves, Set<Integer> wrong, List<Integer> missing) {
        List<Integer> w = new ArrayList<>(), e = new ArrayList<>(), b = new ArrayList<>();
        boolean anyWest = false, anyEast = false, anyBetween = false, anyFree = false;
        for (Map.Entry<Integer, String> c : cells.entrySet()) {
            int col = c.getKey();
            String k = c.getValue();
            boolean isW = west.contains(k), isE = east.contains(k);
            if (isW && isE) {   // named on both sides: at home on either
                anyWest = anyEast = true;
            } else if (isW) {
                anyWest = true;
                if (halves && col > MID) wrong.add(slot(row, col));
                else w.add(col);
            } else if (isE) {
                anyEast = true;
                if (halves && col < MID) wrong.add(slot(row, col));
                else e.add(col);
            } else if (free.contains(k)) {
                anyFree = true;
            } else if (between.contains(k)) {
                anyBetween = true;
                b.add(col);
            } else wrong.add(slot(row, col));   // no business in this row
        }
        // the order itself: nothing of the west east of something of the east
        int firstEast = e.stream().mapToInt(Integer::intValue).min().orElse(COLS);
        int lastWest = w.stream().mapToInt(Integer::intValue).max().orElse(-1);
        for (int col : w) if (col > firstEast) wrong.add(slot(row, col));
        for (int col : e) if (col < lastWest) wrong.add(slot(row, col));
        int lo = -1, hi = COLS;
        for (int col : w) if (!wrong.contains(slot(row, col))) lo = Math.max(lo, col);
        for (int col : e) if (!wrong.contains(slot(row, col))) hi = Math.min(hi, col);
        for (int col : b) if (col <= lo || col >= hi) wrong.add(slot(row, col));
        if (!anyWest) missing.add(slot(row, WEST_AT));
        if (needBetween && !anyBetween || needFree && !anyFree) missing.add(slot(row, MID));
        if (!anyEast) missing.add(slot(row, EAST_AT));
    }

    /** Row 2 (탕): only soups, none of them one of row 0's, none twice. */
    private static void soups(Map<Integer, String> first, Map<Integer, String> cells, Rules r, Set<Integer> wrong, List<Integer> missing) {
        Set<String> seen = new LinkedHashSet<>();
        // a soup of row 0 that is going anyway (it stood on the wrong side) does not take the 탕 with it
        for (Map.Entry<Integer, String> c : first.entrySet()) if (r.soup.contains(c.getValue()) && !wrong.contains(slot(0, c.getKey()))) seen.add(c.getValue());
        boolean any = false;
        for (Map.Entry<Integer, String> c : cells.entrySet()) {
            if (!r.soup.contains(c.getValue())) {
                wrong.add(slot(2, c.getKey()));
                continue;
            }
            any = true;
            if (!seen.add(c.getValue())) wrong.add(slot(2, c.getKey()));
        }
        if (!any) missing.add(slot(2, MID));
    }

    // ------------------------------------------------------------------------------------------
    // a table that is right (an admin's jump, the scenes)
    // ------------------------------------------------------------------------------------------

    /** A plain right table from the first id of every kind (empty places where a kind names nothing). */
    static Map<Integer, String> sample(Rules r) {
        Map<Integer, String> out = new TreeMap<>();
        put(out, 0, WEST_AT, first(r.rice, null));
        put(out, 0, MID, first(r.liquor, null));
        String soup = first(r.soup, null);
        put(out, 0, EAST_AT, soup);
        put(out, 1, WEST_AT, first(r.meat, null));
        put(out, 1, MID, first(r.jeon, null));
        put(out, 1, EAST_AT, first(r.fish, null));
        put(out, 2, MID, first(r.soup, soup));
        put(out, 3, WEST_AT, first(r.dried, null));
        put(out, 3, EAST_AT, first(r.sikhye, null));
        put(out, 4, WEST_AT, first(r.pale, null));
        put(out, 4, EAST_AT, first(r.red, null));
        return out;
    }

    private static void put(Map<Integer, String> out, int row, int col, String id) {
        if (id != null) out.put(slot(row, col), id);
    }

    /** The first id of a kind that is not `not`; the first of all when there is no other. */
    private static String first(Set<String> of, String not) {
        for (String s : of) if (!s.equals(not)) return s;
        return of.isEmpty() ? null : of.iterator().next();
    }

    // ------------------------------------------------------------------------------------------
    // the remembered layout
    // ------------------------------------------------------------------------------------------

    /** "2=economy:rice;4=economy:cheongju": place -> item id, in place order. */
    static String encode(Map<Integer, String> layout) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, String> e : new TreeMap<>(layout).entrySet()) {
            if (e.getKey() < 0 || e.getKey() >= SLOTS || e.getValue() == null || e.getValue().isBlank()) continue;
            if (!b.isEmpty()) b.append(';');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    /** The inverse of {@link #encode}; whatever does not read is left out. */
    static Map<Integer, String> decode(String saved) {
        Map<Integer, String> out = new TreeMap<>();
        if (saved == null || saved.isBlank()) return out;
        for (String part : saved.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) continue;
            try {
                int slot = Integer.parseInt(part.substring(0, eq).trim());
                if (slot >= 0 && slot < SLOTS) out.put(slot, part.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
                // a broken entry: that place is simply empty
            }
        }
        return out;
    }

    /** How many of each item a layout takes. */
    static Map<String, Integer> needs(Map<Integer, String> layout) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : layout.values()) out.merge(k, 1, Integer::sum);
        return out;
    }

    /** What a layout takes that is not there (have: item id -> how many they carry); empty = everything is in hand. */
    static Map<String, Integer> lacking(Map<Integer, String> layout, Map<String, Integer> have) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : needs(layout).entrySet()) {
            int short_ = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (short_ > 0) out.put(e.getKey(), short_);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // where a place is on the table in the world
    // ------------------------------------------------------------------------------------------

    /** Between two dishes of a row, and between two rows (blocks). */
    static final double STEP_X = 0.27, STEP_Z = 0.235, FIRST_Z = -0.40;

    /**
     * A place's spot from the table's middle: {east (+x), south (+z)} in blocks. Column 0 is the west end and row 0 the
     * north end (by the screen), so the table reads in the world as it does in the window.
     */
    static double[] at(int slot) {
        return new double[]{(col(slot) - MID) * STEP_X, FIRST_Z + row(slot) * STEP_Z};
    }

    /** Which way a dish at this place rolls off the table: -1 over the west edge, +1 over the east edge. */
    static int rollTo(int slot) {
        int c = col(slot);
        return c < MID ? -1 : c > MID ? 1 : row(slot) % 2 == 0 ? -1 : 1;
    }
}
