package dev.stsv.economy.store;

import dev.stsv.economy.bus.BusSpec;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 편의점 장면 (store_scenes.yml, README "편의점" 손님끼리 장면, 기획안 §6): the bus's 합승 대화 (BusScenes) in the store. Two or
 * three customers inside together now and then play a little scene of their own, a speech bubble at a time (they stop where
 * they are), and its end changes their visit (mood, tip, one storms out, a gift for the worker). Like the bus: every `check`
 * seconds with a cast inside the store rolls `chance`; a worker sees at most one scene in `cooldown` seconds; scenes the
 * worker has not seen yet weigh `unseen` × more. A pair kind (커플 · 신혼부부: always a man and a woman) speaks as `a` (him)
 * and `a2` (her). Pure (unit-tested).
 */
public record StoreScenes(Rules rules, Map<String, Scene> scenes) {

    public static final StoreScenes NONE = new StoreScenes(new Rules(0, 600, 30, 4, 1.6, 0.07, 2.5, 5.5), Map.of());

    /** The roles of a cast: a, b, c; a pair kind's partner a2, b2, c2. */
    public static final List<String> ROLES = List.of("a", "b", "c");
    /** mood targets besides a role: everyone inside, everyone inside but the cast */
    public static final int ALL = -1, OTHERS = -2;

    /** chance a check, cooldown seconds a worker, check every seconds, unseen ×, a line's pause (base + perChar × length, min..max). */
    public record Rules(double chance, int cooldown, int check, double unseen, double base, double perChar, double min, double max) {
        public double pause(String line) {
            return Math.clamp(base + perChar * (line == null ? 0 : line.codePointCount(0, line.length())), min, max);
        }
    }

    /** A line: the role (0 .. 2), whether the pair's partner says it, the words. */
    public record Line(int who, boolean partner, String text) {}

    /** hours [from, to) game time (-1 any), months (Korean time), rain, a 장날, a weekend, role moods, a scene seen before. */
    public record When(int hourFrom, int hourTo, Set<Integer> months, boolean rain, boolean market, boolean weekend, Map<Integer, Double> below,
                       Map<Integer, Double> above, String seen) {
        static final When ANY = new When(-1, -1, Set.of(), false, false, false, Map.of(), Map.of(), "");

        boolean now(Now n, Set<String> seenByWorker) {
            if (hourFrom >= 0) {
                boolean in = hourFrom <= hourTo ? n.hour() >= hourFrom && n.hour() < hourTo : n.hour() >= hourFrom || n.hour() < hourTo;
                if (!in) return false;
            }
            if (!months.isEmpty() && !months.contains(n.month())) return false;
            if (rain && !n.rain()) return false;
            if (market && !n.market()) return false;
            if (weekend && !n.weekend()) return false;
            return seen.isEmpty() || seenByWorker.contains(seen);
        }
    }

    /**
     * What a scene does after its last line: `mood` role (all, others) ±, `tip` role × (when they pay), `leave` roles walk out
     * without buying (not counted as lost), `calm` everyone inside at least this mood, `gift` something for the worker.
     */
    public record Effect(Map<Integer, Double> mood, Map<Integer, Double> tip, Set<Integer> leave, double calm, BusSpec.Gift gift) {
        public boolean any() {
            return !mood.isEmpty() || !tip.isEmpty() || !leave.isEmpty() || calm > 0 || gift != null;
        }
    }

    public record Scene(String id, String title, List<String> cast, When when, List<Line> lines, Effect effect, double weight) {}

    /** The world now: game hour, Korean month, rain, a 장날, a weekend. */
    public record Now(int hour, int month, boolean rain, boolean market, boolean weekend) {}

    /** A customer inside as a scene sees them: their kind, mood, whether they may be cast now (free). */
    public record Guest(String type, double mood, boolean free) {}

    public record Pick(Scene scene, int[] cast) {}

    // ------------------------------------------------------------------------------------------

    public static StoreScenes parse(ConfigurationSection c, StoreSpec store, BusSpec bus, List<String> problems) {
        if (c == null) return NONE;
        ConfigurationSection p = c.getConfigurationSection("pause");
        Rules d = NONE.rules();
        Rules rules = new Rules(Math.clamp(c.getDouble("chance", 0.12), 0, 1), Math.max(0, c.getInt("cooldown", d.cooldown())),
                Math.max(5, c.getInt("check", d.check())), Math.max(1, c.getDouble("unseen", d.unseen())),
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
            Scene sc = scene(id, s, store, bus, problems);
            if (sc != null) out.put(id, sc);
        }
        for (Scene sc : out.values()) {
            if (!sc.when().seen().isEmpty() && !out.containsKey(sc.when().seen())) problems.add("scenes." + sc.id() + ".when.seen: 없는 장면 " + sc.when().seen());
        }
        return new StoreScenes(rules, out);
    }

    private static Scene scene(String id, ConfigurationSection s, StoreSpec store, BusSpec bus, List<String> problems) {
        String at = "scenes." + id;
        String title = s.getString("title", "");
        if (title.isBlank()) problems.add(at + ".title: 제목이 없음");
        List<String> cast = s.getStringList("cast");
        if (cast.size() < 2 || cast.size() > 3) {
            problems.add(at + ".cast: 손님 2~3명이어야 함 (" + cast.size() + ")");
            return null;
        }
        for (String t : cast) {
            if (!store.customers().containsKey(t)) problems.add(at + ".cast: 편의점에 오지 않는 손님 " + t);
            if (bus != null && bus.types().get(t) == null) problems.add(at + ".cast: bus.yml 에 없는 손님 " + t);
        }
        int n = cast.size();
        List<Line> lines = new ArrayList<>();
        Set<Integer> speaks = new LinkedHashSet<>();
        for (Map<?, ?> m : s.getMapList("lines")) {
            if (m.size() != 1) {
                problems.add(at + ".lines: - 역할: \"대사\" 한 줄씩");
                continue;
            }
            Map.Entry<?, ?> e = m.entrySet().iterator().next();
            String who = String.valueOf(e.getKey()).trim();
            boolean partner = who.endsWith("2");
            int r = role(partner ? who.substring(0, who.length() - 1) : who, n);
            String text = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (r < 0) problems.add(at + ".lines: 없는 역할 " + who);
            else if (text.isEmpty()) problems.add(at + ".lines: 빈 대사 (" + who + ")");
            else {
                if (partner && bus != null && bus.types().get(cast.get(r)) != null && !bus.types().get(cast.get(r)).pair())
                    problems.add(at + ".lines: " + who + " — " + cast.get(r) + " 은(는) 둘이 오는 손님이 아님");
                lines.add(new Line(r, partner, text));
                speaks.add(r);
            }
        }
        if (lines.size() < 3) problems.add(at + ".lines: 대사가 셋 넘게 있어야 함");
        for (int i = 0; i < n; i++) if (!speaks.contains(i)) problems.add(at + ".lines: " + ROLES.get(i) + " 의 대사가 없음");
        When when = when(at, s.getConfigurationSection("when"), n, problems);
        Effect effect = effect(at, s.getConfigurationSection("effect"), n, problems);
        if (!effect.any()) problems.add(at + ".effect: 효과가 없음");
        if (!together(cast, store, when)) problems.add(at + ": 함께 올 수 없는 손님들 (시간)");
        return new Scene(id, title, List.copyOf(cast), when, List.copyOf(lines), effect, Math.max(0.01, s.getDouble("weight", 1)));
    }

    /** Whether the whole cast may be in the store at some game hour the scene allows. */
    static boolean together(List<String> cast, StoreSpec store, When w) {
        for (int h = 0; h < 24; h++) {
            if (w.hourFrom() >= 0) {
                boolean in = w.hourFrom() <= w.hourTo() ? h >= w.hourFrom() && h < w.hourTo() : h >= w.hourFrom() || h < w.hourTo();
                if (!in) continue;
            }
            boolean all = true;
            for (String t : cast) {
                StoreSpec.Customer c = store.customers().get(t);
                if (c != null && !c.at(h)) all = false;
            }
            if (all) return true;
        }
        return false;
    }

    static int role(String r, int n) {
        int i = ROLES.indexOf(r == null ? "" : r.trim());
        return i >= 0 && i < n ? i : -1;
    }

    private static When when(String at, ConfigurationSection w, int n, List<String> problems) {
        if (w == null) return When.ANY;
        for (String k : w.getKeys(false)) {
            if (!List.of("hours", "months", "rain", "market", "weekend", "below", "above", "seen").contains(k)) problems.add(at + ".when: 모르는 조건 " + k);
        }
        List<Integer> hours = w.getIntegerList("hours");
        if (w.contains("hours") && hours.size() != 2) problems.add(at + ".when.hours: [시작, 끝] 이어야 함");
        Set<Integer> months = new LinkedHashSet<>(w.getIntegerList("months"));
        for (int m : months) if (m < 1 || m > 12) problems.add(at + ".when.months: " + m);
        return new When(hours.size() == 2 ? Math.floorMod(hours.get(0), 24) : -1, hours.size() == 2 ? Math.floorMod(hours.get(1), 24) : -1, Set.copyOf(months),
                w.getBoolean("rain"), w.getBoolean("market"), w.getBoolean("weekend"), numbers(at + ".when.below", w, "below", n, false, problems),
                numbers(at + ".when.above", w, "above", n, false, problems), w.getString("seen", "").trim());
    }

    private static Effect effect(String at, ConfigurationSection e, int n, List<String> problems) {
        if (e == null) return new Effect(Map.of(), Map.of(), Set.of(), 0, null);
        for (String k : e.getKeys(false)) {
            if (!List.of("mood", "tip", "leave", "calm", "gift").contains(k)) problems.add(at + ".effect: 모르는 효과 " + k);
        }
        BusSpec.Gift gift = null;
        ConfigurationSection g = e.getConfigurationSection("gift");
        if (g != null) {
            String item = g.getString("item", "");
            if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) problems.add(at + ".effect.gift: 잘못된 아이템 키 " + item);
            else gift = new BusSpec.Gift(item, Math.max(1, g.getInt("amount", 1)));
        }
        Map<Integer, Double> mood = numbers(at + ".effect.mood", e, "mood", n, true, problems);
        for (double v : mood.values()) if (Math.abs(v) > 40) problems.add(at + ".effect.mood: ±40 안 (" + v + ")");
        Map<Integer, Double> tip = numbers(at + ".effect.tip", e, "tip", n, false, problems);
        for (double v : tip.values()) if (v < 0 || v > 5) problems.add(at + ".effect.tip: 0~5 배 (" + v + ")");
        double calm = e.getDouble("calm", 0);
        if (calm < 0 || calm > 100) problems.add(at + ".effect.calm: 0~100 (" + calm + ")");
        Set<Integer> leave = new LinkedHashSet<>();
        if (e.contains("leave")) {
            List<String> raw = e.isList("leave") ? e.getStringList("leave") : List.of(e.getString("leave", ""));
            for (String r : raw) {
                int i = role(r, n);
                if (i < 0) problems.add(at + ".effect.leave: 없는 역할 " + r);
                else leave.add(i);
            }
        }
        return new Effect(mood, tip, Set.copyOf(leave), calm, gift);
    }

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
        return java.util.Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------------------------------ who, which

    /** Who of those inside plays each role (indexes), or null when the cast is not all there (free, distinct, mood conditions met). */
    public static int[] cast(Scene s, List<Guest> inside) {
        int n = s.cast().size();
        int[] out = new int[n];
        boolean[] used = new boolean[inside.size()];
        for (int r = 0; r < n; r++) {
            out[r] = -1;
            for (int i = 0; i < inside.size(); i++) {
                Guest x = inside.get(i);
                if (used[i] || x == null || !x.free() || !x.type().equals(s.cast().get(r))) continue;
                Double below = s.when().below().get(r), above = s.when().above().get(r);
                if (below != null && x.mood() >= below) continue;
                if (above != null && x.mood() < above) continue;
                out[r] = i;
                used[i] = true;
                break;
            }
            if (out[r] < 0) return null;
        }
        return out;
    }

    public List<Pick> candidates(List<Guest> inside, Now now, Set<String> seen) {
        List<Pick> out = new ArrayList<>();
        for (Scene s : scenes.values()) {
            if (!s.when().now(now, seen)) continue;
            int[] c = cast(s, inside);
            if (c != null) out.add(new Pick(s, c));
        }
        return out;
    }

    /** A scene's weight for this worker: unseen ones × rules.unseen. */
    public double weight(Scene s, Set<String> seen) {
        return s.weight() * (seen.contains(s.id()) ? 1 : rules.unseen());
    }

    public Pick pick(List<Guest> inside, Now now, Set<String> seen, double roll) {
        List<Pick> all = candidates(inside, now, seen);
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

    /** Whether a check plays a scene: the worker's cooldown has passed (`sinceLast` seconds; negative: never) and the roll is under the chance. */
    public boolean due(double sinceLast, double roll) {
        if (sinceLast >= 0 && sinceLast < rules.cooldown()) return false;
        return roll < rules.chance();
    }

    /** The id kept in the 인물 도감's seen scenes (apart from the bus's). */
    public static String seenId(String id) {
        return "store:" + id;
    }

    /** The store scenes among a worker's seen ones (without the prefix). */
    public static Set<String> seen(Set<String> all) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : all) if (s.startsWith("store:")) out.add(s.substring(6));
        return out;
    }
}
