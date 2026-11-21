package dev.stsv.economy.guide;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 길라잡이 topics (contents/economy/guide/topics/*.yml, one topic a file; the file name is its id). Pure: parsed and checked
 * without a server. Format (see the files):
 * <pre>
 * name: "농사"                      # shown in the picker and the HUD
 * icon: minecraft:wheat             # the picker's icon (vanilla or custom id)
 * minutes: 3                        # about how long it takes
 * order: 3                          # place in the picker
 * station: farming                  # where in the tutorial world (GuideLayout)
 * about: "밭 갈기부터 수확까지"       # the picker's line
 * reward: { money: 2500, xp: 30 }   # first completion only (xp: to the player's job, if any)
 * items: [ { item: minecraft:iron_hoe } ]   # trial things given when the topic starts
 * steps:
 *   - text: "괭이로 흙을 갈아 보세요"   # the HUD line
 *     say: "흙을 괭이로 우클릭!"         # the helper's speech bubble (optional)
 *     detail: "..."                     # a chat line when the step starts (optional)
 *     done: "..."                       # a chat line when it is done (optional)
 *     goal: { type: till, count: 1 }    # what the player must really do (GOALS) and its parameters
 *     give: [ { item: economy:cabbage_seeds, amount: 4 } ]   # trial things given when the step starts
 *     at: [0, 0, 8]                     # where the particle arrow points (station coordinates: right, up, inward)
 *     then: truck                       # what happens once it is done (THENS)
 * </pre>
 */
public final class GuideSpec {

    private GuideSpec() {}

    /** The goal types the module listens for (README "길라잡이"). */
    public static final Set<String> GOALS = Set.of(
            "menu",            // menu: <menu id>                    a menu opened (economy:shop, stsv:titles ...)
            "command",         // command: <name>, args: <prefix|*>  a command typed (aliases count)
            "has_job",         //                                     the player has a job
            "place_machine",   // machine: <machines.yml id>          a trial machine placed
            "machine_input",   // machine: <id>                        something put into its input slots
            "have_item",       // item: <id>                           a trial stack of it in the inventory
            "till",            //                                     dirt turned into farmland in the station's field
            "plant",           // crop: <crop id>                      a crop planted
            "crop_ripe",       //                                     a crop the player planted is ripe
            "harvest",         // crop: <id>                           a crop harvested
            "break_ore",       //                                     an ore broken
            "catch",           //                                     a fish caught
            "practice_sell",   //                                     a trial thing sold for practice in the shop
            "accessory_worn",  //                                     a trial accessory worn (/장신구)
            "accessory_off",   //                                     ... and taken off again
            "place_block",     // region: <station region>             a trial block placed there
            "interact",        // feature: <station feature>           that block right-clicked
            "drive_stop",      // zone: <station zone>                 the trial vehicle stopped in that zone
            "bus_rent",        //                                     /버스 렌트 at the bus station
            "bus_return");     //                                     /버스 반납

    /** What can happen once a step is done. */
    public static final Set<String> THENS = Set.of("truck", "board", "alight", "remove-vehicle");

    public record Item(String id, int amount) {}

    /** A goal: its type, parameters and how many times (count). */
    public record Goal(String type, Map<String, String> params, int count) {

        /**
         * Does this signal (type and what it carries) answer the goal? Every parameter of the goal must match: ids by their
         * normalised form ("wheat" = "minecraft:wheat"), "args" as a prefix of the typed arguments ("*" = any, "" = none).
         */
        public boolean matches(String signal, Map<String, String> data) {
            if (!type.equals(signal)) return false;
            for (Map.Entry<String, String> e : params.entrySet()) {
                String want = e.getValue(), got = data == null ? null : data.get(e.getKey());
                switch (e.getKey()) {
                    case "args" -> {
                        String have = got == null ? "" : got.trim();
                        if (want.equals("*") ? have.isEmpty() : !(have.equals(want) || have.startsWith(want + " "))) return false;
                    }
                    case "item", "crop", "menu" -> {
                        if (got == null || !normalize(want).equals(normalize(got))) return false;
                    }
                    default -> {
                        if (!want.equals(got)) return false;
                    }
                }
            }
            return true;
        }
    }

    public record Step(String text, String say, String detail, String done, Goal goal, List<Item> give, double[] at, String then) {}

    public record Reward(long money, double xp) {}

    public record Topic(String id, String name, String icon, int minutes, int order, String station, String about, Reward reward,
                        List<Item> items, List<Step> steps) {}

    /** "wheat" -> "minecraft:wheat", "economy:ruby" stays. */
    public static String normalize(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** One topic file. Problems are added (with the file's id); null when it cannot be used at all. */
    public static Topic parse(String id, ConfigurationSection y, Set<String> stations, List<String> problems) {
        String where = "guide/topics/" + id + ".yml";
        String name = y.getString("name");
        if (name == null || name.isBlank()) problems.add(where + ": name 이 없음");
        String station = y.getString("station", "");
        if (stations != null && !stations.contains(station)) problems.add(where + ": 없는 station '" + station + "' (" + String.join(", ", stations) + ")");
        ConfigurationSection rw = y.getConfigurationSection("reward");
        Reward reward = new Reward(rw == null ? 0 : rw.getLong("money"), rw == null ? 0 : rw.getDouble("xp"));
        if (reward.money() < 0) problems.add(where + ": reward.money 가 음수");
        List<Step> steps = new ArrayList<>();
        List<Map<?, ?>> raw = y.getMapList("steps");
        for (int i = 0; i < raw.size(); i++) {
            Step s = step(raw.get(i), where + " steps[" + (i + 1) + "]", problems);
            if (s != null) steps.add(s);
        }
        if (steps.isEmpty()) problems.add(where + ": steps 가 없음");
        if (name == null || steps.isEmpty()) return null;
        return new Topic(id, name, y.getString("icon", "minecraft:book"), Math.max(1, y.getInt("minutes", 2)), y.getInt("order", 99), station,
                y.getString("about", ""), reward, items(y.getMapList("items"), where + " items", problems), List.copyOf(steps));
    }

    private static Step step(Map<?, ?> m, String where, List<String> problems) {
        String text = str(m.get("text"));
        if (text == null) {
            problems.add(where + ": text 가 없음");
            return null;
        }
        Goal goal = null;
        if (m.get("goal") instanceof Map<?, ?> g) {
            String type = str(g.get("type"));
            if (type == null || !GOALS.contains(type)) problems.add(where + ": 없는 goal.type '" + type + "'");
            else {
                Map<String, String> params = new LinkedHashMap<>();
                int count = 1;
                for (Map.Entry<?, ?> e : g.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (k.equals("type")) continue;
                    if (k.equals("count")) {
                        try {
                            count = Math.max(1, Integer.parseInt(String.valueOf(e.getValue())));
                        } catch (NumberFormatException ex) {
                            problems.add(where + ": goal.count 가 숫자가 아님");
                        }
                        continue;
                    }
                    params.put(k, e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
                String need = switch (type) {
                    case "menu" -> "menu";
                    case "command" -> "command";
                    case "place_machine", "machine_input" -> "machine";
                    case "have_item" -> "item";
                    case "place_block" -> "region";
                    case "interact" -> "feature";
                    case "drive_stop" -> "zone";
                    default -> null;
                };
                if (need != null && !params.containsKey(need)) problems.add(where + ": goal " + type + " 에 " + need + " 가 필요");
                goal = new Goal(type, Map.copyOf(params), count);
            }
        } else problems.add(where + ": goal 이 없음");
        if (goal == null) return null;
        double[] at = null;
        if (m.get("at") instanceof List<?> l) {
            if (l.size() != 3) problems.add(where + ": at 은 [오른쪽, 위, 안쪽] 세 수");
            else {
                at = new double[3];
                for (int i = 0; i < 3; i++) at[i] = l.get(i) instanceof Number n ? n.doubleValue() : 0;
            }
        }
        String then = str(m.get("then"));
        if (then != null && !THENS.contains(then)) problems.add(where + ": 없는 then '" + then + "'");
        List<Map<?, ?>> give = new ArrayList<>();
        if (m.get("give") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> x) give.add(x);
        return new Step(text, str(m.get("say")), str(m.get("detail")), str(m.get("done")), goal, items(give, where + " give", problems), at, then);
    }

    private static List<Item> items(List<Map<?, ?>> raw, String where, List<String> problems) {
        List<Item> out = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            String id = str(m.get("item"));
            if (id == null) {
                problems.add(where + ": item 이 없음");
                continue;
            }
            int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
            out.add(new Item(normalize(id), Math.max(1, amount)));
        }
        return List.copyOf(out);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Topics in picker order (order, then id). */
    public static List<Topic> sorted(Collection<Topic> topics) {
        return topics.stream().sorted(Comparator.comparingInt(Topic::order).thenComparing(Topic::id)).toList();
    }

    /** What all topics pay together on first completion. */
    public static long totalMoney(Collection<Topic> topics) {
        return topics.stream().mapToLong(t -> t.reward().money()).sum();
    }
}
