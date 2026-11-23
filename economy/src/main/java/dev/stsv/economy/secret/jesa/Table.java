package dev.stsv.economy.secret.jesa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rite table of 주인 없는 제사 as pure data: 5 rows of 3 vessels (row 0 nearest the folding screen, which stands on
 * the north; column 0 is the west vessel, 1 the middle, 2 the east — the one who bows stands on the south, so west is their
 * left hand and east their right), what lies on each ("economy:rice" …), and the judging by the real rules of a 제사상:
 * <ol>
 * <li>row 0, 메 · 잔 · 갱: rice west, soup east (반서갱동), the liquor in the cup between them (the middle vessel is the cup);</li>
 * <li>row 1, 적 · 전: meat west, fish east (어동육서), a 전 between them;</li>
 * <li>row 2, 탕: a soup, not one already in row 0, no soup twice;</li>
 * <li>row 3, 포 · 나물 · 혜: dried fish west, 식혜 east (좌포우혜), 나물 between if any;</li>
 * <li>row 4, 과일: the pale fruit (배, a pear: only the bus's 할머니 hands them out) west, red fruit east (홍동백서), rice cakes
 * and the like between if any.</li>
 * </ol>
 * Anything taboo (chili, garlic, a fish whose name ends in 치, red beans) is wrong wherever it lies, and so is anything
 * that has no business in its row. Nothing here touches the server (unit-tested); where the vessels stand is {@link Vessel}.
 */
final class Table {

    static final int COLS = 3, ROWS = 5, SLOTS = COLS * ROWS;
    /**
     * The middle vessel. With {@link Rules#halves} a west thing never lies on the east vessel nor an east thing on the west
     * one; the middle belongs to neither side, so whatever stands on it is judged by its neighbour alone (meat in the middle
     * with the fish east of it is still meat west of fish). Row 0's middle is the cup: only liquor goes there.
     */
    static final int MID = 1;
    /** Where a missing dish is pointed at: the west vessel, the middle, the east vessel of its row. */
    static final int WEST_AT = 0, EAST_AT = 2;

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
                    eco("saengseonjeon", "hobakjeon", "gamjajeon", "pajeon"), eco("dried_pollack", "dried_fish_stirfry", "dried_fish", "gulbi"),
                    eco("sikhye"), eco("japchae", "gyeranmari"), eco("pear"), set("minecraft:apple", "economy:strawberry"),
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
        sides(0, rows.get(0), r.rice, r.soup, r.liquor, true, true, r.halves, wrong, missing);
        sides(1, rows.get(1), r.meat, r.fish, r.jeon, true, false, r.halves, wrong, missing);
        soups(rows.get(0), rows.get(2), r, wrong, missing);
        sides(3, rows.get(3), r.dried, r.sikhye, r.namul, false, false, r.halves, wrong, missing);
        sides(4, rows.get(4), r.pale, r.red, r.cake, false, false, r.halves, wrong, missing);
        return new Verdict(wrong, missing);
    }

    /**
     * A row with a west kind and an east kind (both owed) and things that go between them (owed when needBetween). cup: the
     * middle vessel takes only the between kind, and the between kind goes nowhere else (row 0: the liquor in its cup).
     */
    private static void sides(int row, Map<Integer, String> cells, Set<String> west, Set<String> east, Set<String> between,
                              boolean needBetween, boolean cup, boolean halves, Set<Integer> wrong, List<Integer> missing) {
        List<Integer> w = new ArrayList<>(), e = new ArrayList<>(), b = new ArrayList<>();
        boolean anyWest = false, anyEast = false, anyBetween = false;
        for (Map.Entry<Integer, String> c : cells.entrySet()) {
            int col = c.getKey();
            String k = c.getValue();
            boolean isW = west.contains(k), isE = east.contains(k), isB = between.contains(k);
            if (cup && (col == MID) != isB) {   // something else in the cup, or the liquor out of it
                if (isW) anyWest = true;
                else if (isE) anyEast = true;
                else if (isB) anyBetween = true;
                wrong.add(slot(row, col));
                continue;
            }
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
            } else if (isB) {
                anyBetween = true;
                if (!cup) b.add(col);
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
        // (a between kind that names nothing at all is owed by nobody: a weekly house whose fish place takes the 전.
        // Never the cup: a table whose liquor names nothing is not one that needs no drink — it cannot be set at all)
        if (needBetween && !anyBetween && (cup || !between.isEmpty())) missing.add(slot(row, MID));
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
}
