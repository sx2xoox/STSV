package dev.stsv.economy.people;

import dev.stsv.economy.bus.BusSpec;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * people.yml: the 인물 도감 (README "인물 도감"). One person = one bus passenger kind (bus.yml types: its name, colour, skins,
 * where and when it comes), and here the intros that open as you meet them (`milestones` meetings: 1, 10, 50), the people
 * never listed (`never`: the secret ones; a kind of weight 0 never is either), the completion titles and the stamps of
 * where you met them (`places`: bus for now). Pure (unit-tested).
 */
public record PeopleSpec(List<Integer> milestones, Set<String> never, String titleAll, String titleShiny, Map<String, String> places,
                         Map<String, List<String>> intros, Map<String, String> hints) {

    /** 만난 곳: the 마을버스 (a passenger carried to where they were going). */
    public static final String PLACE_BUS = "bus";

    public static PeopleSpec parse(ConfigurationSection c, List<String> problems) {
        List<Integer> ms = new ArrayList<>(c.getIntegerList("milestones"));
        if (ms.isEmpty()) ms = new ArrayList<>(List.of(1, 10, 50));
        ms.removeIf(m -> m <= 0);
        ms.sort(Comparator.naturalOrder());
        Map<String, String> places = new LinkedHashMap<>();
        ConfigurationSection pl = c.getConfigurationSection("places");
        if (pl != null) for (String k : pl.getKeys(false)) places.put(k, pl.getString(k, k));
        if (!places.containsKey(PLACE_BUS)) places.put(PLACE_BUS, "버스");
        Map<String, List<String>> intros = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();
        ConfigurationSection pp = c.getConfigurationSection("people");
        if (pp != null) for (String id : pp.getKeys(false)) {
            ConfigurationSection p = pp.getConfigurationSection(id);
            if (p == null) continue;
            List<String> in = p.getStringList("intro");
            if (in.size() != ms.size()) problems.add("people." + id + ".intro: 소개 글은 " + ms.size() + "줄 (milestones 마다 한 줄)");
            intros.put(id, List.copyOf(in));
            String h = p.getString("hint");
            if (h != null && !h.isBlank()) hints.put(id, h);
        }
        return new PeopleSpec(List.copyOf(ms), Set.copyOf(c.getStringList("never")), c.getString("titles.all", "economy:people_all"),
                c.getString("titles.shiny", "economy:people_shiny_all"), places, intros, hints);
    }

    /** Problems against the bus's kinds: an intro for every person, none for a kind that does not exist. */
    public List<String> check(BusSpec bus) {
        List<String> out = new ArrayList<>();
        List<String> ids = people(bus);
        for (String id : ids) if (!intros.containsKey(id)) out.add("people." + id + ": 소개 글이 없음");
        for (String id : intros.keySet()) if (!bus.types().containsKey(id)) out.add("people." + id + ": bus.yml 에 없는 손님 종류");
        for (String id : never) if (!bus.types().containsKey(id)) out.add("never: bus.yml 에 없는 손님 종류 " + id);
        return out;
    }

    /** Everyone in the codex, in bus.yml's order: every passenger kind that really comes (weight over 0), not `never`. */
    public List<String> people(BusSpec bus) {
        List<String> out = new ArrayList<>();
        for (BusSpec.Type t : bus.types().values()) if (listed(t)) out.add(t.id());
        return out;
    }

    public boolean listed(BusSpec.Type t) {
        return t != null && t.weight() > 0 && !never.contains(t.id());
    }

    /** How many intro lines are open after this many meetings. */
    public int introsOpen(int met) {
        int n = 0;
        for (int m : milestones) if (met >= m) n++;
        return n;
    }

    /** The meetings at which the next intro line opens, or -1 when all are open. */
    public int nextMilestone(int met) {
        for (int m : milestones) if (met < m) return m;
        return -1;
    }

    /**
     * Whether a passenger getting off registers a meeting: at the stop they were going to, carried all of their ride
     * (`paid` blocks paid for is the whole `planned` road: 공정 운행 counted it all; a bus that did not drive them, or a
     * short hop after it turned round, does not).
     */
    public static boolean registers(boolean atDest, double paid, double planned) {
        return atDest && paid > 0 && paid + 1e-6 >= planned;
    }

    /**
     * Where to meet someone (an unregistered entry's hint), as [message key, value] pairs for people.hint-*: the stop traits
     * they come to most (up to two, factor over 1, the biggest first), the hours, the months, weekends, market days; else
     * anywhere.
     */
    public static List<String[]> hint(BusSpec.Type t) {
        List<String[]> out = new ArrayList<>();
        List<Map.Entry<String, Double>> tags = new ArrayList<>(t.tags().entrySet());
        tags.removeIf(e -> e.getValue() <= 1);
        tags.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        if (!tags.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Map.Entry<String, Double> e : tags.subList(0, Math.min(2, tags.size()))) ids.add(e.getKey());
            out.add(new String[]{"tags", String.join(",", ids)});
        }
        if (t.hourFrom() >= 0) out.add(new String[]{"hours", t.hourFrom() + "~" + t.hourTo()});
        if (!t.x().months().isEmpty()) {
            out.add(new String[]{"months", String.join(",", new TreeSet<>(t.x().months()).stream().map(String::valueOf).toList())});
        }
        if (t.weekend() > 1) out.add(new String[]{"weekend", ""});
        if (t.market() > 1) out.add(new String[]{"market", ""});
        if (t.pair()) out.add(new String[]{"pair", ""});
        if (out.isEmpty()) out.add(new String[]{"anywhere", ""});
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // One player's record of one person
    // ------------------------------------------------------------------------------------------

    /** What a meeting changed: the first meeting, the first shiny one, an intro line that opened (its index, else -1). */
    public record Meet(boolean first, boolean firstShiny, int opened, boolean newPlace) {}

    /** A player and one person: meetings, the first one (millis, 0: never), registered normal and shiny (millis, 0: no), places. */
    public static final class Entry {
        public int met;
        public long first, normal, shiny;
        public final Set<String> places = new LinkedHashSet<>();

        public boolean registered() {
            return normal > 0 || shiny > 0;
        }

        /** One more meeting (shiny or not) at this place, now. */
        public Meet meet(PeopleSpec spec, long now, boolean isShiny, String place) {
            boolean firstMeet = met == 0;
            int before = spec.introsOpen(met);
            met++;
            if (first == 0) first = now;
            boolean firstShiny = false;
            if (isShiny) {
                if (shiny == 0) {
                    shiny = now;
                    firstShiny = true;
                }
            } else if (normal == 0) normal = now;
            boolean newPlace = place != null && places.add(place);
            int after = spec.introsOpen(met);
            return new Meet(firstMeet, firstShiny, after > before ? after - 1 : -1, newPlace);
        }

        /** The places as saved ("bus,restaurant"). */
        public String placesText() {
            return String.join(",", places);
        }

        public static Set<String> places(String saved) {
            Set<String> out = new LinkedHashSet<>();
            if (saved != null) for (String s : saved.split(",")) if (!s.isBlank()) out.add(s.trim());
            return out;
        }
    }

    /** Whether every listed person is registered (normal: any look; `shinyOnly`: their shiny look). */
    public static boolean complete(List<String> people, Map<String, Entry> mine, boolean shinyOnly) {
        if (people.isEmpty()) return false;
        for (String id : people) {
            Entry e = mine.get(id);
            if (e == null || (shinyOnly ? e.shiny == 0 : e.normal == 0)) return false;
        }
        return true;
    }

    /** How many listed people are registered (normal look, or shiny look). */
    public static int count(List<String> people, Map<String, Entry> mine, boolean shiny) {
        int n = 0;
        for (String id : people) {
            Entry e = mine.get(id);
            if (e != null && (shiny ? e.shiny > 0 : e.normal > 0)) n++;
        }
        return n;
    }
}
