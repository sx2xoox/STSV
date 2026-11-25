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
import java.util.function.Predicate;

/**
 * One family's circumstance in the 후속 임무 「기일」 ({@link JesaSequel}): a small change to the rules of the rite table
 * ({@link Table}), as families really keep them (가가례). The family only ever says it as a remark ({@link #say}); what it
 * means for the table is here:
 * <ul>
 * <li>{@code replace}: a kind is other things this once (the cup holds 식혜 or tea for one who did not drink; 떡국 stands
 * for the rice at 설; the red fruit is the one they loved);</li>
 * <li>{@code noFish}: no fish on the table — the fish place (row 1, east) takes a 전 instead (a 전 is then at home on the
 * middle vessel and on the east one; one on either will do);</li>
 * <li>{@code taboo}: more things that may not lie anywhere;</li>
 * <li>{@code must}: a favourite that has to be there, on its own vessel in its own row (it must be something that row takes);</li>
 * <li>{@code soups}: how many different soups row 2 holds (삼탕: three);</li>
 * <li>{@code tablets}: which tablets stand on the screen (one alone: only "father" or only "mother") — seen, not judged.</li>
 * </ul>
 * Pure: nothing here touches the server (JesaGiilTest).
 */
record Variant(String id, List<String> say, Map<String, Set<String>> replace, boolean noFish, Set<String> taboo, Map<String, Integer> must,
               int soups, List<String> tablets) {

    /** The kinds a variant may replace, by their names in jesa.yml {@code table}. */
    static final List<String> KINDS = List.of("rice", "soup", "liquor", "meat", "fish", "jeon", "dried", "sikhye", "namul", "pale", "red", "sweets");

    /** jesa.yml {@code sequel.variants}: a list of maps (id, say, replace, no-fish, taboo, must ["id row"], soups, tablets). */
    static List<Variant> parse(List<Map<?, ?>> list) {
        List<Variant> out = new ArrayList<>();
        int n = 0;
        for (Map<?, ?> m : list) {
            n++;
            String id = m.get("id") == null ? "variant" + n : String.valueOf(m.get("id"));
            Map<String, Set<String>> replace = new LinkedHashMap<>();
            if (m.get("replace") instanceof Map<?, ?> r) for (Map.Entry<?, ?> e : r.entrySet()) replace.put(String.valueOf(e.getKey()), ids(e.getValue()));
            Map<String, Integer> must = new LinkedHashMap<>();
            for (String line : strings(m.get("must"))) {
                String[] w = line.trim().split("\\s+");
                try {
                    if (w.length == 2) must.put(Table.norm(w[0]), Integer.parseInt(w[1]) - 1);   // the file counts rows from 1
                } catch (NumberFormatException ignored) {
                    // not "id row": left out (the tests read the shipped file)
                }
            }
            int soups = m.get("soups") instanceof Number num ? num.intValue() : 1;
            out.add(new Variant(id, List.copyOf(strings(m.get("say"))), replace, Boolean.TRUE.equals(m.get("no-fish")), ids(m.get("taboo")), must,
                    Math.max(1, Math.min(Table.COLS, soups)), List.copyOf(strings(m.get("tablets")))));
        }
        return out;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
        else if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static Set<String> ids(Object o) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : strings(o)) if (!s.isBlank()) out.add(Table.norm(s));
        return out;
    }

    /** The same variant naming only things that exist on this server (a replaced kind may be left naming nothing). */
    Variant existing(Predicate<String> exists) {
        Map<String, Set<String>> r = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : replace.entrySet()) {
            Set<String> keep = new LinkedHashSet<>();
            for (String id : e.getValue()) if (exists.test(id)) keep.add(id);
            r.put(e.getKey(), keep);
        }
        return new Variant(id, say, r, noFish, taboo, must, soups, tablets);
    }

    private Set<String> kind(String name, Set<String> usual) {
        Set<String> r = replace.get(name);
        return r == null ? usual : r;
    }

    /** The table's rules as this family keeps them. */
    Table.Rules apply(Table.Rules b) {
        Set<String> fish = kind("fish", b.fish()), jeon = kind("jeon", b.jeon()), dried = kind("dried", b.dried());
        Set<String> t = new LinkedHashSet<>(b.taboo());
        t.addAll(taboo);
        if (noFish) {
            // the fish goes (what is also a 포 stays a 포), and its place takes a 전: the 전 is at home on the middle vessel
            // and on the east one (Table owes nothing between when the two kinds are the same)
            for (String f : fish) if (!dried.contains(f)) t.add(f);
            Set<String> east = new LinkedHashSet<>(jeon);
            east.removeAll(t);
            fish = east;
            jeon = east;
        }
        return new Table.Rules(kind("rice", b.rice()), kind("soup", b.soup()), kind("liquor", b.liquor()), kind("meat", b.meat()), fish, jeon, dried,
                kind("sikhye", b.sikhye()), kind("namul", b.namul()), kind("pale", b.pale()), kind("red", b.red()), kind("sweets", b.cake()), t);
    }

    /** The judging of a table by this family's rules: {@link Table#judge} with the changed rules, then the favourite and the soups. */
    Table.Verdict judge(Map<Integer, String> layout, Table.Rules base) {
        return judge(layout, base, -1);
    }

    /** {@link #judge(Map, Table.Rules)}; newest: the vessel just put on ({@link Table#judge(Map, Table.Rules, int)}). */
    Table.Verdict judge(Map<Integer, String> layout, Table.Rules base, int newest) {
        Table.Rules rules = apply(base);
        Table.Verdict v = Table.judge(layout, rules, newest);
        Set<Integer> wrong = new TreeSet<>(v.wrong());
        List<Integer> missing = new ArrayList<>(v.missing());
        for (Map.Entry<String, Integer> e : must.entrySet()) {
            int row = e.getValue();
            if (row < 0 || row >= Table.ROWS) continue;
            boolean there = false;
            for (int col = 0; col < Table.COLS; col++) {
                int slot = Table.slot(row, col);
                if (e.getKey().equals(layout.get(slot)) && !wrong.contains(slot)) there = true;
            }
            if (!there) owe(owed(row, Table.homes(row, e.getKey(), rules), layout, wrong), missing);
        }
        if (soups > 1) {
            int good = 0;
            for (int col = 0; col < Table.COLS; col++) if (stands(layout, wrong, Table.slot(2, col))) good++;
            int owed = soups - good;
            for (int col : new int[]{Table.MID, Table.WEST_AT, Table.EAST_AT}) {
                int slot = Table.slot(2, col);
                if (owed <= 0) break;
                if (stands(layout, wrong, slot)) continue;
                owe(slot, missing);
                owed--;
            }
        }
        return new Table.Verdict(wrong, missing);
    }

    private static boolean stands(Map<Integer, String> layout, Set<Integer> wrong, int slot) {
        return layout.get(slot) != null && !wrong.contains(slot);
    }

    private static void owe(int slot, List<Integer> missing) {
        if (!missing.contains(slot)) missing.add(slot);
    }

    /**
     * The vessel of a row to point at for a favourite that is owed: its own vessel (the first of them that is free), else an
     * empty one (the middle first), else the middle.
     */
    private static int owed(int row, List<Integer> homes, Map<Integer, String> layout, Set<Integer> wrong) {
        for (int slot : homes) if (!stands(layout, wrong, slot)) return slot;
        if (!homes.isEmpty()) return homes.getFirst();
        for (int col : new int[]{Table.MID, Table.WEST_AT, Table.EAST_AT}) if (!stands(layout, wrong, Table.slot(row, col))) return Table.slot(row, col);
        return Table.slot(row, Table.MID);
    }

    /** A table this family accepts (an admin's look, the status line, the tests); a kind that names nothing leaves its place empty. */
    Map<Integer, String> sample(Table.Rules base) {
        Table.Rules r = apply(base);
        Map<Integer, String> out = new TreeMap<>(Table.sample(r));
        for (Map.Entry<String, Integer> e : must.entrySet()) {
            int row = e.getValue();
            if (row < 0 || row >= Table.ROWS || out.containsValue(e.getKey()) && rowOf(out, e.getKey()) == row) continue;
            // a favourite goes on its own vessel (between the two sides, as the shipped ones all are; in row 1 the 전 itself)
            List<Integer> homes = Table.homes(row, e.getKey(), r);
            out.put(homes.isEmpty() ? Table.slot(row, Table.MID) : homes.contains(Table.slot(row, Table.MID)) ? Table.slot(row, Table.MID) : homes.getFirst(), e.getKey());
        }
        if (soups > 1) {
            Set<String> used = new LinkedHashSet<>();
            String first = out.get(Table.slot(0, Table.EAST_AT));
            if (first != null) used.add(first);
            int[] cols = {Table.MID, Table.WEST_AT, Table.EAST_AT};
            for (int i = 0; i < soups; i++) {
                String pick = null;
                for (String s : r.soup()) if (!used.contains(s)) {
                    pick = s;
                    break;
                }
                if (pick == null) break;
                used.add(pick);
                out.put(Table.slot(2, cols[i]), pick);
            }
        }
        return out;
    }

    private static int rowOf(Map<Integer, String> layout, String id) {
        for (Map.Entry<Integer, String> e : layout.entrySet()) if (id.equals(e.getValue())) return Table.row(e.getKey());
        return -1;
    }

    /**
     * Whether this family's table can be set at all on this server: its own right table is accepted and everything on it
     * (and every favourite) exists. One that cannot (떡국 where there is no 떡국) is never chosen.
     */
    boolean usable(Table.Rules base, Predicate<String> exists) {
        Variant v = existing(exists);
        Map<Integer, String> s = v.sample(base);
        if (!v.judge(s, base).ok()) return false;
        for (String id : s.values()) if (!exists.test(id)) return false;
        for (String id : must.keySet()) if (!exists.test(id)) return false;
        return !say.isEmpty();
    }

    /** Every id this variant names (to check the file against the items that exist). */
    Set<String> all() {
        Set<String> out = new LinkedHashSet<>(taboo);
        for (Collection<String> c : replace.values()) out.addAll(c);
        out.addAll(must.keySet());
        return out;
    }

    /** Whether this tablet ("father", "mother") stands on the screen: both do unless the family names which. */
    boolean tablet(String which) {
        return tablets.isEmpty() || tablets.contains(which);
    }
}
