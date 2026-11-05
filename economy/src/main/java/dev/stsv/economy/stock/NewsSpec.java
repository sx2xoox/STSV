package dev.stsv.economy.stock;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * contents/economy/news.yml: company news lines built from three parts, [머리말] + 원인 + 결과, so they rarely
 * repeat: a cause used in the last 30 news is not drawn again, and a company never gets the same headline
 * prefix twice in a row. Pure (unit-tested).
 */
public record NewsSpec(String format, List<String> prefixes, Map<String, Map<Boolean, List<String>>> causes,
                       Map<Boolean, Map<String, List<String>>> results) {

    public static NewsSpec parse(ConfigurationSection c, List<String> problems) {
        Map<String, Map<Boolean, List<String>>> causes = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("causes");
        if (cs != null) {
            for (String ind : cs.getKeys(false)) {
                Map<Boolean, List<String>> m = new HashMap<>();
                m.put(true, cs.getStringList(ind + ".up"));
                m.put(false, cs.getStringList(ind + ".down"));
                if (m.get(true).isEmpty() || m.get(false).isEmpty()) problems.add("news.causes." + ind + ": up / down 필요");
                causes.put(ind, m);
            }
        }
        Map<Boolean, Map<String, List<String>>> results = new HashMap<>();
        for (boolean up : new boolean[]{true, false}) {
            Map<String, List<String>> m = new HashMap<>();
            for (String size : List.of("small", "medium", "large")) {
                List<String> l = c.getStringList("results." + (up ? "up" : "down") + "." + size);
                if (l.isEmpty()) problems.add("news.results." + (up ? "up" : "down") + "." + size + " 필요");
                m.put(size, l);
            }
            results.put(up, m);
        }
        List<String> prefixes = c.getStringList("prefixes");
        if (prefixes.isEmpty()) prefixes = List.of("[속보]");
        return new NewsSpec(c.getString("format", "{prefix} {company}, {cause}… {result} {change}"), prefixes, causes, results);
    }

    /** small < 6.5%, medium < 8.5%, large. */
    public static String size(double pct) {
        double a = Math.abs(pct);
        return a < 6.5 ? "small" : a < 8.5 ? "medium" : "large";
    }

    /** "<green>▲9.4%" / "<red>▼6.1%" */
    public static String change(double pct) {
        return (pct >= 0 ? "<green>▲" : "<red>▼") + String.format(Locale.ROOT, "%.1f%%", Math.abs(pct));
    }

    /** Writes news lines and remembers what it used. */
    public static final class Writer {
        private final Deque<String> recent = new ArrayDeque<>();
        private final Map<String, String> lastPrefix = new HashMap<>();
        private final int memory;

        public Writer(int memory) {
            this.memory = memory;
        }

        /** A whole line (MiniMessage) for a company of an industry moving pct percent, or a custom cause. */
        public String line(NewsSpec spec, String companyId, String company, String industry, double pct, String cause, Random r) {
            boolean up = pct >= 0;
            if (cause == null) {
                List<String> pool = spec.causes().getOrDefault(industry, Map.of()).getOrDefault(up, List.of());
                List<String> fresh = new ArrayList<>();
                for (String c : pool) if (!recent.contains(c)) fresh.add(c);
                if (fresh.isEmpty()) fresh = pool;
                cause = fresh.isEmpty() ? "" : fresh.get(r.nextInt(fresh.size()));
            }
            recent.addLast(cause);
            while (recent.size() > memory) recent.removeFirst();
            List<String> prefixes = new ArrayList<>(spec.prefixes());
            String last = lastPrefix.get(companyId);
            if (prefixes.size() > 1 && last != null) prefixes.remove(last);
            String prefix = prefixes.get(r.nextInt(prefixes.size()));
            lastPrefix.put(companyId, prefix);
            List<String> res = spec.results().getOrDefault(up, Map.of()).getOrDefault(size(pct), List.of());
            String result = res.isEmpty() ? "" : res.get(r.nextInt(res.size()));
            return spec.format().replace("{prefix}", prefix).replace("{company}", company).replace("{cause}", cause)
                    .replace("{result}", result).replace("{change}", change(pct));
        }

        public boolean recentlyUsed(String cause) {
            return recent.contains(cause);
        }
    }
}
