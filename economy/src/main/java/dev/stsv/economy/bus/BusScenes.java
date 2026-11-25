package dev.stsv.economy.bus;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 합승 대화 (bus_scenes.yml, README "마을버스" 합승 대화; 기획안 §6-4): two or three passenger kinds aboard together now and
 * then play a little scene of their own, a speech bubble at a time, and what happens in it changes the ride (`effect`: the
 * 통화남 hangs up, the 건달 pays, someone gets off at the next stop …). A scene is a chance event: once a stop leg (`settle`
 * seconds after pulling out of a stop) a bus with an eligible cast aboard rolls `chance`; a driver sees at most one scene in
 * `cooldown` seconds; scenes the driver has not seen yet weigh `unseen` × more. Seen scenes are kept in the 인물 도감 (장면).
 * Pure (unit-tested): the module does the bubbles and the effects on its riders.
 */
public record BusScenes(Rules rules, Map<String, Scene> scenes) {

    public static final BusScenes NONE = new BusScenes(new Rules(0, 600, 12, 4, 1.6, 0.07, 2.5, 5.5), Map.of());

    /** The roles of a cast, in order: a, b, c. */
    public static final List<String> ROLES = List.of("a", "b", "c");
    /** mood effect targets besides a role: everyone aboard, everyone aboard but the cast */
    public static final int ALL = -1, OTHERS = -2;

    /**
     * How often: `chance` a stop leg with a cast aboard, a driver's `cooldown` seconds between scenes, `settle` seconds after
     * leaving a stop before the roll, unseen scenes × `unseen`; a line stays `base + perChar × length` seconds (min..max).
     */
    public record Rules(double chance, int cooldown, int settle, double unseen, double base, double perChar, double min, double max) {
        /** Seconds a line shows before the next one (natural timing: longer lines stay longer). */
        public double pause(String line) {
            return Math.clamp(base + perChar * (line == null ? 0 : line.codePointCount(0, line.length())), min, max);
        }
    }

    /** One line of a scene: who says it (role index) and what. */
    public record Line(int who, String text) {}

    /**
     * When a scene may play: hours [from, to) (Korean time, over midnight when from > to; -1: any), months, a rainy world, a
     * market day, a weekend; a role's mood below or at least a number; roles that must still be loud (their nuisance not yet
     * quieted) or dozing; a scene the driver must have seen before (a running gag's callback).
     */
    public record When(int hourFrom, int hourTo, Set<Integer> months, boolean rain, boolean market, boolean weekend, Map<Integer, Double> below,
                       Map<Integer, Double> above, Set<Integer> loud, Set<Integer> dozing, String seen) {
        static final When ANY = new When(-1, -1, Set.of(), false, false, false, Map.of(), Map.of(), Set.of(), Set.of(), "");

        boolean now(Now n, Set<String> seenByDriver) {
            if (hourFrom >= 0) {
                boolean in = hourFrom <= hourTo ? n.hour() >= hourFrom && n.hour() < hourTo : n.hour() >= hourFrom || n.hour() < hourTo;
                if (!in) return false;
            }
            if (!months.isEmpty() && !months.contains(n.month())) return false;
            if (rain && !n.rain()) return false;
            if (market && !n.market()) return false;
            if (weekend && !n.weekend()) return false;
            return seen.isEmpty() || seenByDriver.contains(seen);
        }
    }

    /**
     * What a scene does to the ride after its last line (README "마을버스" 합승 대화), each a real change:
     * <ul>
     *   <li>`quiet`: those roles' nuisance stops for the rest of their ride (chatter, the bell prank, the crumbs, the 건달's glare);</li>
     *   <li>`pay`: they pay their whole fare (no dodging, haggling or 공짜);</li>
     *   <li>`mood`: role (or all, others) ± mood;</li>
     *   <li>`leave`: off at the next stop the bus stops at (paid as far as they rode);</li>
     *   <li>`doze` / `wake`: they nod off (may miss their stop) / are woken (won't sleep through it);</li>
     *   <li>`tip` / `fare`: their tip / fare × this when they get off;</li>
     *   <li>`calm`: everyone aboard at least this mood;</li>
     *   <li>`gift`: something for the driver (an item of the economy).</li>
     * </ul>
     */
    public record Effect(Set<Integer> quiet, Set<Integer> pay, Map<Integer, Double> mood, Set<Integer> leave, Set<Integer> doze, Set<Integer> wake,
                         Map<Integer, Double> tip, Map<Integer, Double> fare, double calm, BusSpec.Gift gift) {
        public boolean any() {
            return !quiet.isEmpty() || !pay.isEmpty() || !mood.isEmpty() || !leave.isEmpty() || !doze.isEmpty() || !wake.isEmpty()
                    || !tip.isEmpty() || !fare.isEmpty() || calm > 0 || gift != null;
        }
    }

    public record Scene(String id, String title, List<String> cast, When when, List<Line> lines, Effect effect, double weight) {}

    /** The world now, for a scene's conditions. */
    public record Now(int hour, int month, boolean rain, boolean market, boolean weekend) {}

    /**
     * One rider as a scene sees them: their kind, mood, whether their nuisance is still on (`loud`), dozing, and whether they
     * may be cast now (`free`: not the secret one, not in a scene already this ride, here and able to speak).
     */
    public record Seat(String type, double mood, boolean loud, boolean dozing, boolean free) {}

    /** A scene to play and who of those aboard plays each role (indexes into the seats). */
    public record Pick(Scene scene, int[] cast) {}

    // ------------------------------------------------------------------------------------------
    // bus_scenes.yml
    // ------------------------------------------------------------------------------------------

    public static BusScenes parse(ConfigurationSection c, BusSpec bus, List<String> problems) {
        if (c == null) return NONE;
        ConfigurationSection p = c.getConfigurationSection("pause");
        Rules d = NONE.rules();
        Rules rules = new Rules(Math.clamp(c.getDouble("chance", 0.12), 0, 1), Math.max(0, c.getInt("cooldown", d.cooldown())),
                Math.max(0, c.getInt("settle", d.settle())), Math.max(1, c.getDouble("unseen", d.unseen())),
                p == null ? d.base() : p.getDouble("base", d.base()), p == null ? d.perChar() : p.getDouble("per-char", d.perChar()),
                p == null ? d.min() : p.getDouble("min", d.min()), p == null ? d.max() : p.getDouble("max", d.max()));
        Map<String, Scene> out = new LinkedHashMap<>();
        ConfigurationSection ss = c.getConfigurationSection("scenes");
        if (ss == null) problems.add("scenes 가 없음");
        else for (String id : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(id);
            if (s == null) {
                problems.add("scenes." + id + ": 장면이 아님");
                continue;
            }
            Scene sc = scene(id, s, bus, problems);
            if (sc != null) out.put(id, sc);
        }
        for (Scene sc : out.values()) {
            if (!sc.when().seen().isEmpty() && !out.containsKey(sc.when().seen())) problems.add("scenes." + sc.id() + ".when.seen: 없는 장면 " + sc.when().seen());
            if (sc.when().seen().equals(sc.id())) problems.add("scenes." + sc.id() + ".when.seen: 자기 자신");
        }
        return new BusScenes(rules, out);
    }

    private static Scene scene(String id, ConfigurationSection s, BusSpec bus, List<String> problems) {
        String at = "scenes." + id;
        String title = s.getString("title", "");
        if (title.isBlank()) problems.add(at + ".title: 제목이 없음");
        List<String> cast = s.getStringList("cast");
        if (cast.size() < 2 || cast.size() > 3) {
            problems.add(at + ".cast: 손님 2~3명이어야 함 (" + cast.size() + ")");
            return null;
        }
        for (String t : cast) {
            BusSpec.Type type = bus.types().get(t);
            if (type == null) problems.add(at + ".cast: 없는 손님 종류 " + t);
            else if (type.weight() <= 0) problems.add(at + ".cast: 보통 손님으로 안 나오는 종류 " + t);
        }
        int n = cast.size();
        List<Line> lines = new ArrayList<>();
        Set<Integer> speaks = new LinkedHashSet<>();
        for (Map<?, ?> m : s.getMapList("lines")) {
            Object who = m.get("who"), line = m.get("line");
            if (who == null && m.size() == 1) {   // the short form: - a: "…"
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                who = e.getKey();
                line = e.getValue();
            }
            int r = role(String.valueOf(who), n);
            String text = line == null ? "" : String.valueOf(line).trim();
            if (r < 0) problems.add(at + ".lines: 없는 역할 " + who);
            else if (text.isEmpty()) problems.add(at + ".lines: 빈 대사 (" + who + ")");
            else {
                lines.add(new Line(r, text));
                speaks.add(r);
            }
        }
        if (lines.size() < 2) problems.add(at + ".lines: 대사가 둘 넘게 있어야 함");
        for (int i = 0; i < n; i++) if (!speaks.contains(i)) problems.add(at + ".lines: " + ROLES.get(i) + " 의 대사가 없음");
        When when = when(at, s.getConfigurationSection("when"), n, problems);
        Effect effect = effect(at, s.getConfigurationSection("effect"), n, problems);
        if (!effect.any()) problems.add(at + ".effect: 효과가 없음");
        // the effects must do something to who they name (a quiet on someone with nothing to quiet would be a lie)
        for (int r : effect.quiet()) {
            BusSpec.Type t = bus.types().get(cast.get(r));
            if (t != null && !nuisance(t)) problems.add(at + ".effect.quiet: " + cast.get(r) + " 은(는) 조용히 할 버릇이 없음");
        }
        for (int r : effect.pay()) {
            BusSpec.Type t = bus.types().get(cast.get(r));
            if (t != null && !payTrouble(t)) problems.add(at + ".effect.pay: " + cast.get(r) + " 은(는) 운임을 안 깎음");
        }
        // the cast can be aboard together at some hour and month
        if (!together(cast, bus, when)) problems.add(at + ": 함께 탈 수 없는 손님들 (시간 · 달)");
        return new Scene(id, title, List.copyOf(cast), when, List.copyOf(lines), effect, Math.max(0.01, s.getDouble("weight", 1)));
    }

    /** Whether a kind has a nuisance a scene can quiet: chatter, the bell prank, crumbs, the 건달's glare. */
    public static boolean nuisance(BusSpec.Type t) {
        BusSpec.Traits x = t.x();
        return x.chatter() > 0 || x.prank() > 0 || x.litter() != 0 || x.scare() != 0;
    }

    /** Whether a kind may not pay its whole fare (dodging, haggling, 공짜). */
    public static boolean payTrouble(BusSpec.Type t) {
        BusSpec.Traits x = t.x();
        return x.dodge() > 0 || x.haggleAt() > 0 || x.freeload() > 0;
    }

    /** Whether all of the cast come at some hour of some month that the scene allows. */
    static boolean together(List<String> cast, BusSpec bus, When w) {
        for (int month = 1; month <= 12; month++) {
            if (!w.months().isEmpty() && !w.months().contains(month)) continue;
            hours:
            for (int h = 0; h < 24; h++) {
                if (w.hourFrom() >= 0) {
                    boolean in = w.hourFrom() <= w.hourTo() ? h >= w.hourFrom() && h < w.hourTo() : h >= w.hourFrom() || h < w.hourTo();
                    if (!in) continue;
                }
                for (String id : cast) {
                    BusSpec.Type t = bus.types().get(id);
                    if (t == null) return true;   // told already
                    if (t.weightAt(Set.of(), h, true, true, false, month, 1) <= 0 && t.weightAt(t.tags().keySet(), h, true, true, false, month, 1) <= 0) continue hours;
                }
                return true;
            }
        }
        return false;
    }

    static int role(String r, int n) {
        int i = ROLES.indexOf(r == null ? "" : r.trim());
        return i >= 0 && i < n ? i : -1;
    }

    private static When when(String at, ConfigurationSection w, int n, List<String> problems) {
        if (w == null) return When.ANY;
        List<Integer> hours = w.getIntegerList("hours");
        if (w.contains("hours") && hours.size() != 2) problems.add(at + ".when.hours: [시작, 끝] 이어야 함");
        for (String k : w.getKeys(false)) {
            if (!List.of("hours", "months", "rain", "market", "weekend", "below", "above", "loud", "dozing", "seen").contains(k))
                problems.add(at + ".when: 모르는 조건 " + k);
        }
        Set<Integer> months = new LinkedHashSet<>(w.getIntegerList("months"));
        for (int m : months) if (m < 1 || m > 12) problems.add(at + ".when.months: " + m);
        return new When(hours.size() == 2 ? hours.get(0) : -1, hours.size() == 2 ? hours.get(1) : -1, Set.copyOf(months), w.getBoolean("rain"),
                w.getBoolean("market"), w.getBoolean("weekend"), numbers(at + ".when.below", w, "below", n, false, problems),
                numbers(at + ".when.above", w, "above", n, false, problems), roles(at + ".when.loud", w, "loud", n, problems),
                roles(at + ".when.dozing", w, "dozing", n, problems), w.getString("seen", "").trim());
    }

    private static Effect effect(String at, ConfigurationSection e, int n, List<String> problems) {
        if (e == null) return new Effect(Set.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), 0, null);
        for (String k : e.getKeys(false)) {
            if (!List.of("quiet", "pay", "mood", "leave", "doze", "wake", "tip", "fare", "calm", "gift").contains(k))
                problems.add(at + ".effect: 모르는 효과 " + k);
        }
        BusSpec.Gift gift = null;
        ConfigurationSection g = e.getConfigurationSection("gift");
        if (g != null) {
            String item = g.getString("item", "");
            if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) problems.add(at + ".effect.gift: 잘못된 아이템 키 " + item);
            else gift = new BusSpec.Gift(item, Math.max(1, g.getInt("amount", 1)));
        }
        Map<Integer, Double> tip = numbers(at + ".effect.tip", e, "tip", n, false, problems), fare = numbers(at + ".effect.fare", e, "fare", n, false, problems);
        for (double v : tip.values()) if (v < 0 || v > 5) problems.add(at + ".effect.tip: 0~5 배 (" + v + ")");
        for (double v : fare.values()) if (v < 0 || v > 3) problems.add(at + ".effect.fare: 0~3 배 (" + v + ")");
        Map<Integer, Double> mood = numbers(at + ".effect.mood", e, "mood", n, true, problems);
        for (double v : mood.values()) if (Math.abs(v) > 40) problems.add(at + ".effect.mood: ±40 안 (" + v + ")");
        double calm = e.getDouble("calm", 0);
        if (calm < 0 || calm > 100) problems.add(at + ".effect.calm: 0~100 (" + calm + ")");
        Set<Integer> doze = roles(at + ".effect.doze", e, "doze", n, problems), wake = roles(at + ".effect.wake", e, "wake", n, problems);
        for (int r : doze) if (wake.contains(r)) problems.add(at + ".effect: 같은 손님을 재우고 깨움");
        return new Effect(roles(at + ".effect.quiet", e, "quiet", n, problems), roles(at + ".effect.pay", e, "pay", n, problems), mood,
                roles(at + ".effect.leave", e, "leave", n, problems), doze, wake, tip, fare, calm, gift);
    }

    /** a / [a, b] -> role indexes */
    private static Set<Integer> roles(String at, ConfigurationSection c, String key, int n, List<String> problems) {
        if (!c.contains(key)) return Set.of();
        List<String> raw = c.isList(key) ? c.getStringList(key) : List.of(c.getString(key, ""));
        Set<Integer> out = new LinkedHashSet<>();
        for (String r : raw) {
            int i = role(r, n);
            if (i < 0) problems.add(at + ": 없는 역할 " + r);
            else out.add(i);
        }
        return Set.copyOf(out);
    }

    /** {a: 3, all: -2} -> role index (or ALL, OTHERS) -> number */
    private static Map<Integer, Double> numbers(String at, ConfigurationSection c, String key, int n, boolean groups, List<String> problems) {
        ConfigurationSection s = c.getConfigurationSection(key);
        if (s == null) {
            if (c.contains(key)) problems.add(at + ": {역할: 숫자} 이어야 함");
            return Map.of();
        }
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            int i = groups && k.equals("all") ? ALL : groups && k.equals("others") ? OTHERS : role(k, n);
            if (i == -1 && !(groups && k.equals("all"))) {
                problems.add(at + ": 없는 역할 " + k);
                continue;
            }
            if (!s.isDouble(k) && !s.isInt(k)) problems.add(at + "." + k + ": 숫자가 아님");
            out.put(i, s.getDouble(k));
        }
        return Map.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------
    // Who plays it, which one
    // ------------------------------------------------------------------------------------------

    /**
     * Who of those aboard plays each role of this scene (seat indexes), or null when the cast is not all aboard: free riders of
     * each kind, distinct, meeting their role's mood, loud and dozing conditions (a role the scene quiets must still be loud,
     * a role it wakes must be dozing).
     */
    public static int[] cast(Scene s, List<Seat> aboard) {
        int n = s.cast().size();
        int[] out = new int[n];
        boolean[] used = new boolean[aboard.size()];
        for (int r = 0; r < n; r++) {
            out[r] = -1;
            for (int i = 0; i < aboard.size(); i++) {
                Seat x = aboard.get(i);
                if (used[i] || x == null || !x.free() || !x.type().equals(s.cast().get(r)) || !fits(s, r, x)) continue;
                out[r] = i;
                used[i] = true;
                break;
            }
            if (out[r] < 0) return null;
        }
        return out;
    }

    private static boolean fits(Scene s, int r, Seat x) {
        When w = s.when();
        Double below = w.below().get(r), above = w.above().get(r);
        if (below != null && x.mood() >= below) return false;
        if (above != null && x.mood() < above) return false;
        if ((w.loud().contains(r) || s.effect().quiet().contains(r)) && !x.loud()) return false;
        return !(w.dozing().contains(r) || s.effect().wake().contains(r)) || x.dozing();
    }

    /** Every scene that could play now with those aboard (and the cast of each). */
    public List<Pick> candidates(List<Seat> aboard, Now now, Set<String> seen) {
        List<Pick> out = new ArrayList<>();
        for (Scene s : scenes.values()) {
            if (!s.when().now(now, seen)) continue;
            int[] c = cast(s, aboard);
            if (c != null) out.add(new Pick(s, c));
        }
        return out;
    }

    /** A scene's weight for this driver: unseen ones × rules.unseen. */
    public double weight(Scene s, Set<String> seen) {
        return s.weight() * (seen.contains(s.id()) ? 1 : rules.unseen());
    }

    /** One of the candidates by weight (`roll` 0..1), or null when there are none. */
    public Pick pick(List<Seat> aboard, Now now, Set<String> seen, double roll) {
        List<Pick> all = candidates(aboard, now, seen);
        if (all.isEmpty()) return null;
        double total = 0;
        for (Pick p : all) total += weight(p.scene(), seen);
        double at = roll * total;
        for (Pick p : all) {
            at -= weight(p.scene(), seen);
            if (at < 0) return p;
        }
        return all.getLast();
    }

    /**
     * Whether a leg's check plays a scene: the driver's cooldown has passed (`sinceLast` seconds; negative: never had one) and
     * the roll is under the chance.
     */
    public boolean due(double sinceLast, double roll) {
        if (sinceLast >= 0 && sinceLast < rules.cooldown()) return false;
        return roll < rules.chance();
    }

    /** The scenes a passenger kind plays a part in (인물 도감: 장면), in file order. */
    public List<Scene> with(String type) {
        List<Scene> out = new ArrayList<>();
        for (Scene s : scenes.values()) if (s.cast().contains(type)) out.add(s);
        return out;
    }
}
