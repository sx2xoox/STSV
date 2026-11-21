package dev.stsv.economy.bus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finding a 정류장 by what someone typed (README "마을버스", /관리자 정류장): stop names may have spaces ("다람쥐 고개",
 * "<하역장> 앞"), and commands split what is typed on spaces. A stop is named by its whole name (the words joined again, or
 * in quotes "..."), by its number (#12 or 12), and found exactly first, then ignoring case and spaces. Pure (unit-tested).
 */
public final class StopNames {

    private StopNames() {
    }

    /** What was typed, without the quotes round it. */
    static String unquote(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1).trim();
        else if (t.startsWith("\"")) t = t.substring(1).trim();
        return t;
    }

    /** A name as compared loosely: lower case, no spaces. */
    static String loose(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Whether a name would read as a stop's number (#12, 12): no stop may be named so. */
    public static boolean numberLike(String name) {
        return unquote(name).matches("#?\\d+");
    }

    /** The stop typed (its name, quoted or not, or its number #12 / 12), or null: exactly, then ignoring case and spaces. */
    public static BusStop find(Collection<BusStop> stops, String typed) {
        String t = unquote(typed);
        if (t.isEmpty()) return null;
        for (BusStop s : stops) if (s.name().equals(t)) return s;
        if (t.matches("#?\\d+")) {
            try {
                int id = Integer.parseInt(t.startsWith("#") ? t.substring(1) : t);
                for (BusStop s : stops) if (s.id() == id) return s;
            } catch (NumberFormatException ignored) {
                // too long a number: no such stop
            }
        }
        String l = loose(t);
        BusStop found = null;
        for (BusStop s : stops) {
            if (!loose(s.name()).equals(l)) continue;
            if (found != null) return null;   // two the same but for case or spaces: say which exactly
            found = s;
        }
        return found;
    }

    /** Whether another stop than `except` (null: any) already has this name (ignoring case and spaces). */
    public static boolean taken(Collection<BusStop> stops, String name, Integer except) {
        String l = loose(unquote(name));
        for (BusStop s : stops) if ((except == null || s.id() != except) && loose(s.name()).equals(l)) return true;
        return false;
    }

    /** A stop typed and the words after it. */
    public record Match(BusStop stop, String[] rest) {}

    /**
     * The stop named by args[from..] and the words after it, leaving at least `minRest` words (a number, a new name): a
     * quoted name ("다람쥐 고개" 3) up to its closing quote, else the most words that name a stop (the longest name that
     * fits). Null: no stop.
     */
    public static Match split(Collection<BusStop> stops, String[] args, int from, int minRest) {
        if (args == null || from >= args.length) return null;
        if (args[from].startsWith("\"")) {
            for (int j = from; j < args.length; j++) {
                boolean closes = j == from ? args[j].length() > 1 && args[j].endsWith("\"") : args[j].endsWith("\"");
                if (!closes) continue;
                BusStop s = find(stops, String.join(" ", Arrays.copyOfRange(args, from, j + 1)));
                return s == null ? null : new Match(s, Arrays.copyOfRange(args, j + 1, args.length));
            }
        }
        for (int j = args.length - minRest; j > from; j--) {
            BusStop s = find(stops, String.join(" ", Arrays.copyOfRange(args, from, j)));
            if (s != null) return new Match(s, Arrays.copyOfRange(args, j, args.length));
        }
        return null;
    }

    /** A name as a whole word for a command: in quotes when it has a space. */
    public static String quoted(String name) {
        return name.contains(" ") ? "\"" + name + "\"" : name;
    }

    /**
     * Tab completion of a stop typed as args[from..] (the last of them being typed): the first word offers every stop's
     * name (quoted when it has spaces) and number (#12) that starts so; words after it (a name typed without quotes, or
     * inside its quotes) the next word of the names that fit (with the closing quote at a quoted name's end).
     */
    public static List<String> complete(Collection<BusStop> stops, String[] args, int from) {
        Set<String> out = new LinkedHashSet<>();
        if (args == null || from >= args.length) return new ArrayList<>(out);
        String last = args[args.length - 1];
        if (args.length - 1 == from) {
            String typed = last.toLowerCase(Locale.ROOT);
            for (BusStop s : stops) {
                String q = quoted(s.name());
                if (q.toLowerCase(Locale.ROOT).startsWith(typed) || s.name().toLowerCase(Locale.ROOT).startsWith(typed)) out.add(q);
            }
            for (BusStop s : stops) if (("#" + s.id()).startsWith(last)) out.add("#" + s.id());
            return new ArrayList<>(out);
        }
        boolean inQuotes = args[from].startsWith("\"");
        String head = String.join(" ", Arrays.copyOfRange(args, from, args.length - 1));
        if (inQuotes) head = head.substring(1);
        if (inQuotes && head.endsWith("\"")) return new ArrayList<>(out);   // the quote closed: the name is done
        int word = args.length - 1 - from;
        for (BusStop s : stops) {
            String[] w = s.name().split(" ");
            if (w.length <= word) continue;
            if (!String.join(" ", Arrays.copyOf(w, word)).equals(head)) continue;
            if (!w[word].startsWith(last)) continue;
            out.add(w[word] + (inQuotes && w.length == word + 1 ? "\"" : ""));
        }
        return new ArrayList<>(out);
    }
}
