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
 * <li>row 2, 탕: a soup on any of the three deep bowls, not one already in row 0's soup bowl, no soup twice;</li>
 * <li>row 3, 포 · 나물 · 혜: dried fish west, 식혜 east (좌포우혜), 나물 between if any;</li>
 * <li>row 4, 과일: the pale fruit (배, a pear: only the bus's 할머니 hands them out) west, red fruit east (홍동백서), rice cakes
 * and the like between if any.</li>
 * </ol>
 * Every thing has its own vessel ({@link #fits}): the column of its kind in its row, whatever else lies on the table — a
 * thing anywhere else is wrong (a thing named on two kinds of one row is at home on either's vessel). Anything taboo (chili,
 * garlic, a fish whose name ends in 치, red beans) is wrong wherever it lies, and so is anything that has no business in its
 * row. What is missing is judged by kind, not by vessel: a misplaced thing is wrong, not also owed. Nothing here touches
 * the server (unit-tested); where the vessels stand is {@link Vessel}.
 */
final class Table {

    static final int COLS = 3, ROWS = 5, SLOTS = COLS * ROWS;
    /** The middle vessel: the between kind's (전 · 나물 · 떡). Row 0's middle is the cup: only liquor goes there. */
    static final int MID = 1;
    /** Where a missing dish is pointed at: the west vessel, the middle, the east vessel of its row. */
    static final int WEST_AT = 0, EAST_AT = 2;

    private Table() {}

    /**
     * What may lie where (item ids as {@code Items.key} gives them). (jesa.yml's old {@code table.halves} is no longer read:
     * every thing has its own vessel now, so there is no looser "order alone" judging left for it to switch.)
     */
    record Rules(Set<String> rice, Set<String> soup, Set<String> liquor, Set<String> meat, Set<String> fish, Set<String> jeon,
                 Set<String> dried, Set<String> sikhye, Set<String> namul, Set<String> pale, Set<String> red, Set<String> cake,
                 Set<String> taboo) {

        /** The same rules with more taboo things (the 치 fish, worked out from their names). */
        Rules alsoTaboo(Collection<String> more) {
            Set<String> t = new LinkedHashSet<>(taboo);
            t.addAll(more);
            return new Rules(rice, soup, liquor, meat, fish, jeon, dried, sikhye, namul, pale, red, cake, t);
        }

        /** jesa.yml {@code table}: a kind the file does not list keeps its usual ids ({@link #defaults}). */
        static Rules read(org.bukkit.configuration.ConfigurationSection c) {
            Rules d = defaults();
            if (c == null) return d;
            return new Rules(ids(c, "rice", d.rice), ids(c, "soup", d.soup), ids(c, "liquor", d.liquor), ids(c, "meat", d.meat),
                    ids(c, "fish", d.fish), ids(c, "jeon", d.jeon), ids(c, "dried", d.dried), ids(c, "sikhye", d.sikhye),
                    ids(c, "namul", d.namul), ids(c, "pale", d.pale), ids(c, "red", d.red), ids(c, "sweets", d.cake),
                    ids(c, "taboo", d.taboo));
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
                            "beoseot_bokkeum", "gamja_jorim", "gujeolpan", "hanjeongsik", "royal_eoran", "royal_surasang", "gosa_tteok"));
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
        return judge(layout, r, -1);
    }

    /**
     * newest: the vessel just put on (-1: none). Of the same 탕 twice in row 2 it is the newest that goes (else the one
     * further east); nothing else depends on it.
     */
    static Verdict judge(Map<Integer, String> layout, Rules r, int newest) {
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
        for (int row : new int[]{0, 1, 3, 4}) sides(row, rows.get(row), r, wrong, missing);
        soups(rows.get(0), rows.get(2), r, newest, wrong, missing);
        return new Verdict(wrong, missing);
    }

    /** A row's three kinds: west, middle (between), east. Row 2 (탕) has none: any deep bowl takes a soup. */
    static List<Set<String>> kinds(int row, Rules r) {
        return switch (row) {
            case 0 -> List.of(r.rice, r.liquor, r.soup);
            case 1 -> List.of(r.meat, r.jeon, r.fish);
            case 3 -> List.of(r.dried, r.namul, r.sikhye);
            case 4 -> List.of(r.pale, r.cake, r.red);
            default -> List.of();
        };
    }

    /**
     * Whether a thing lies on its own vessel, by itself (what else is on the table aside): its kind's column in that row
     * (a thing of two kinds of the row: either's vessel), any deep bowl for a soup in row 2. Never anything taboo. Of the
     * 탕 row this is only half the answer — not row 0's soup, not twice — which {@link #judge} adds.
     */
    static boolean fits(int slot, String id, Rules r) {
        if (slot < 0 || slot >= SLOTS || id == null || r.taboo.contains(id)) return false;
        int row = row(slot);
        if (row == 2) return r.soup.contains(id);
        return kinds(row, r).get(col(slot)).contains(id);
    }

    /** The vessels of a row where this thing would be at home (empty: none in that row). */
    static List<Integer> homes(int row, String id, Rules r) {
        List<Integer> out = new ArrayList<>();
        for (int col = 0; col < COLS; col++) if (fits(slot(row, col), id, r)) out.add(slot(row, col));
        return out;
    }

    /**
     * A row with a west kind and an east kind (both owed) and a between kind on the middle vessel (owed in rows 0 and 1).
     * Wrong: whatever is not on its own vessel. Owed: a kind of which nothing lies anywhere in the row (a misplaced thing is
     * wrong and comes back; its vessel is not tapped for it too).
     */
    private static void sides(int row, Map<Integer, String> cells, Rules r, Set<Integer> wrong, List<Integer> missing) {
        List<Set<String>> k = kinds(row, r);
        Set<String> west = k.get(WEST_AT), between = k.get(MID), east = k.get(EAST_AT);
        boolean anyWest = false, anyEast = false, anyBetween = false;
        for (Map.Entry<Integer, String> c : cells.entrySet()) {
            String id = c.getValue();
            anyWest |= west.contains(id);
            anyEast |= east.contains(id);
            anyBetween |= between.contains(id);
            if (!fits(slot(row, c.getKey()), id, r)) wrong.add(slot(row, c.getKey()));
        }
        if (!anyWest) missing.add(slot(row, WEST_AT));
        // the 전 (row 1) and the cup (row 0) are owed. A between kind that names nothing, or that is the east kind itself
        // (a weekly house whose fish place takes the 전: a 전 on either vessel will do), is owed by nobody — never the cup:
        // a table whose liquor names nothing is not one that needs no drink, it cannot be set at all
        boolean cup = row == 0;
        if (row <= 1 && !anyBetween && (cup || !between.isEmpty() && !between.equals(east))) missing.add(slot(row, MID));
        if (!anyEast) missing.add(slot(row, EAST_AT));
    }

    /** Row 2 (탕): only soups, none of them row 0's (in its own bowl), none twice — the newest of a pair goes. */
    private static void soups(Map<Integer, String> first, Map<Integer, String> cells, Rules r, int newest, Set<Integer> wrong,
                              List<Integer> missing) {
        Set<String> seen = new LinkedHashSet<>();
        // a soup of row 0 that is going anyway (it stood on the wrong vessel) does not take the 탕 with it
        for (Map.Entry<Integer, String> c : first.entrySet()) if (r.soup.contains(c.getValue()) && !wrong.contains(slot(0, c.getKey()))) seen.add(c.getValue());
        boolean any = false;
        List<Integer> order = new ArrayList<>(cells.keySet());
        if (row(newest) == 2 && order.remove(Integer.valueOf(col(newest)))) order.add(col(newest));
        for (int col : order) {
            String id = cells.get(col);
            if (!r.soup.contains(id)) {
                wrong.add(slot(2, col));
                continue;
            }
            any = true;
            if (!seen.add(id)) wrong.add(slot(2, col));
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
