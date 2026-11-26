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
 * where you met them (`places`: bus for now). Two separate collections: the 일반 인물 (normal look) and the 별난 인물 (the
 * shiny look, README "별난 손님"): each has its own entries (met count, first met, intro progress, places); a shiny entry's
 * intro is its own line (`people.<id>.shiny`) then the normal intro. Pure (unit-tested).
 */
public record PeopleSpec(List<Integer> milestones, Set<String> never, String titleAll, String titleShiny, Map<String, String> places,
                         Map<String, List<String>> intros, Map<String, String> hints, Map<String, String> shinyIntros) {

    /** 장면 (합승 대화, bus_scenes.yml) a player has seen: eco_people version 3. */
    public static final String SCENES_TABLE = "CREATE TABLE IF NOT EXISTS eco_people_scenes (player CHAR(36) NOT NULL, scene VARCHAR(64) NOT NULL, "
            + "first_at BIGINT NOT NULL, PRIMARY KEY (player, scene))";

    /** A person's 장면 in the codex: how many scenes they are in, how many of them seen, the seen titles shown, how many more. */
    public record SceneRows(int all, int seen, List<String> titles, int more) {}

    /** The 장면 rows of a person (a bus kind): every scene they play a part in, the seen ones' titles in file order (up to `max`). */
    public static SceneRows sceneRows(dev.stsv.economy.bus.BusScenes scenes, String person, Set<String> seen, int max) {
        List<dev.stsv.economy.bus.BusScenes.Scene> with = scenes.with(person);
        List<String> titles = new ArrayList<>();
        for (var s : with) if (seen.contains(s.id())) titles.add(s.title());
        int n = titles.size();
        return new SceneRows(with.size(), n, List.copyOf(titles.subList(0, Math.min(max, n))), Math.max(0, n - max));
    }

    /** 만난 곳: the 마을버스 (a passenger carried to where they were going). */
    public static final String PLACE_BUS = "bus";
    /** 만난 곳: the 편의점 (a customer served at the till, store.yml). */
    public static final String PLACE_STORE = "store";

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
        Map<String, String> hints = new LinkedHashMap<>(), shiny = new LinkedHashMap<>();
        ConfigurationSection pp = c.getConfigurationSection("people");
        if (pp != null) for (String id : pp.getKeys(false)) {
            ConfigurationSection p = pp.getConfigurationSection(id);
            if (p == null) continue;
            List<String> in = p.getStringList("intro");
            if (in.size() != ms.size()) problems.add("people." + id + ".intro: 소개 글은 " + ms.size() + "줄 (milestones 마다 한 줄)");
            intros.put(id, List.copyOf(in));
            String sh = p.getString("shiny");
            if (sh == null || sh.isBlank()) problems.add("people." + id + ".shiny: 별난 인물 소개 한 줄이 없음");
            else shiny.put(id, sh);
            String h = p.getString("hint");
            if (h != null && !h.isBlank()) hints.put(id, h);
        }
        return new PeopleSpec(List.copyOf(ms), Set.copyOf(c.getStringList("never")), c.getString("titles.all", "economy:people_all"),
                c.getString("titles.shiny", "economy:people_shiny_all"), places, intros, hints, shiny);
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

    /** How many intro lines are open after this many meetings (일반 인물). */
    public int introsOpen(int met) {
        int n = 0;
        for (int m : milestones) if (met >= m) n++;
        return n;
    }

    /** How many intro lines are open: a 별난 인물 opens its own line at the first meeting, then the normal ones as usual. */
    public int introsOpen(int met, boolean shiny) {
        if (!shiny) return introsOpen(met);
        return met >= 1 ? 1 + introsOpen(met) : 0;
    }

    /** A person's intro lines in a collection: 별난 = its own line, then the normal intro. */
    public List<String> intro(String id, boolean shiny) {
        List<String> normal = intros.getOrDefault(id, List.of());
        String own = shinyIntros.get(id);
        if (!shiny || own == null) return normal;
        List<String> out = new ArrayList<>(normal.size() + 1);
        out.add(own);
        out.addAll(normal);
        return out;
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

    /** What a meeting changed: the first meeting (registered), an intro line that opened (its index, else -1), a new place. */
    public record Meet(boolean first, int opened, boolean newPlace) {}

    /**
     * A player and one person in one collection (일반 or 별난: two separate entries): meetings, the first one (millis, 0:
     * never), the places.
     */
    public static final class Entry {
        public int met;
        public long first;
        public final Set<String> places = new LinkedHashSet<>();

        public boolean registered() {
            return met > 0;
        }

        /** One more meeting at this place, now; `shiny`: this is a 별난 인물 entry (its intro has one line more). */
        public Meet meet(PeopleSpec spec, long now, boolean shiny, String place) {
            boolean firstMeet = met == 0;
            int before = spec.introsOpen(met, shiny);
            met++;
            if (first == 0) first = now;
            boolean newPlace = place != null && places.add(place);
            int after = spec.introsOpen(met, shiny);
            return new Meet(firstMeet, after > before ? after - 1 : -1, newPlace);
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

    /** Whether every listed person is registered in this collection (일반 or 별난: `mine` is that collection's entries). */
    public static boolean complete(List<String> people, Map<String, Entry> mine) {
        if (people.isEmpty()) return false;
        for (String id : people) {
            Entry e = mine.get(id);
            if (e == null || !e.registered()) return false;
        }
        return true;
    }

    /** How many listed people are registered in this collection. */
    public static int count(List<String> people, Map<String, Entry> mine) {
        int n = 0;
        for (String id : people) {
            Entry e = mine.get(id);
            if (e != null && e.registered()) n++;
        }
        return n;
    }

    /**
     * The DB steps that split the old one-row-per-person store (eco_people with normal_at / shiny_at) into the two
     * collections, run once (stsv_schema eco_people version 2, one transaction), and safe to run again:
     * <ul>
     *   <li>every registered shiny (shiny_at &gt; 0) becomes a 별난 인물 row in eco_people_shiny: first met = shiny_at, places
     *       copied, met = all the meetings if the normal look was never registered (then every meeting was shiny), else 1
     *       (the old count did not tell them apart: at least the one);</li>
     *   <li>the 일반 인물 row keeps the rest: one meeting fewer when both were registered, first met = normal_at; a person
     *       registered only shiny has no 일반 entry any more (the row goes);</li>
     *   <li>shiny_at is cleared, so nothing is moved twice.</li>
     * </ul>
     */
    public static List<String> splitShinyMigration() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS eco_people_shiny (player CHAR(36) NOT NULL, person VARCHAR(64) NOT NULL, met INT NOT NULL, "
                        + "first_at BIGINT NOT NULL, places VARCHAR(255) NOT NULL, PRIMARY KEY (player, person))",
                "INSERT INTO eco_people_shiny (player, person, met, first_at, places) "
                        + "SELECT p.player, p.person, CASE WHEN p.normal_at = 0 THEN p.met ELSE 1 END, p.shiny_at, p.places FROM eco_people p "
                        + "WHERE p.shiny_at > 0 AND NOT EXISTS (SELECT 1 FROM eco_people_shiny s WHERE s.player = p.player AND s.person = p.person)",
                "UPDATE eco_people SET met = met - 1 WHERE shiny_at > 0 AND normal_at > 0 AND met > 1",
                "DELETE FROM eco_people WHERE shiny_at > 0 AND normal_at = 0",
                "UPDATE eco_people SET first_at = normal_at, shiny_at = 0 WHERE shiny_at > 0");
    }
}
