package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 작업기: what a vehicle can work with (content/stsv/vehicle_tools.yml, one section per vehicle item id), README "작업기".
 * Pure (unit-tested): {@link VehicleTools} shows the tools and does their work.
 *
 * <pre>
 * tractor_trailer:
 *   base: "트레일러만"           # the vehicle as it comes (no tool): Ctrl cycles base -> each tool -> base
 *   tools:
 *     - id: rotary
 *       name: "로터리"
 *       kind: till                # till | sow | harvest | net | cover
 *       unit: 0                   # which unit it hangs on: 0 = the driven unit, 1 = the first trailer ...
 *       replaces: false           # true: it takes that trailer's place (its body and load hidden, its bed shut; wheels: keep its wheels)
 *       model: tractor_rotary     # drawn at `at` on that unit (body-scale like the vehicles: `scale`)
 *       at: [0, 0, 0]
 *       scale: 2
 *       parts:                    # moving parts: spin pto (turns while someone drives: rate degrees a tick) or roll (rolls on the ground: radius)
 *         - { model: tractor_rotary_tines, at: [0, 0.3, -2.0], axis: x, spin: pto, rate: 30, scale: 2 }
 *       work: { at: [0, 0, -2.0], width: 3 }   # the strip it works: its middle on the unit, blocks across (1 .. 5)
 *       speed: 4                  # top speed while it is attached (blocks/s; the vehicle's own if more)
 * shutter: { model: ..., inside: ..., at: [...], scale: 2.5, rolled: 0.08, ticks: 14 }   # a rear roller door (the 탑차)
 * </pre>
 *
 * net:   { seconds: 40 }   how long on average between catches while trawling (kind net)
 * cover: { fold: 0.12, ticks: 12 }   how far the cover folds up when taken off (its length's share), how long it takes
 *
 * @param base    the name of the vehicle without a tool
 * @param tools   the tools in Ctrl order
 * @param shutter a rear roller door that opens with the bed, or null
 */
public record VehicleToolSpec(String base, List<Tool> tools, Shutter shutter) {

    /** What a tool does. */
    public enum Kind {
        /** grass, dirt and dirt paths it passes over become farmland */
        TILL,
        /** the seeds in the bed go onto the empty farmland it passes */
        SOW,
        /** the ripe crops it passes are harvested into the bed (and planted again) */
        HARVEST,
        /** trawling slowly on the sea, a common or uncommon fish now and then goes into the hold */
        NET,
        /** a cover over the bed (looks only: the load is hidden under it) */
        COVER;

        /** Does it change blocks (protection, budget)? */
        public boolean works() {
            return this == TILL || this == SOW || this == HARVEST;
        }
    }

    public enum Spin { PTO, ROLL }

    /** A moving part: turned about its axis (x across, z along) while the engine drives it (pto) or as it rolls. */
    public record Part(NamespacedKey model, VehicleSpec.Vec at, char axis, Spin spin, double rate, double radius, float scale) {}

    /** The strip a tool works: its middle on the unit, `width` blocks across. */
    public record Work(VehicleSpec.Vec at, int width) {}

    /**
     * One tool.
     *
     * @param unit     the unit it hangs on (0 = the driven one)
     * @param replaces it takes that trailer's place: the trailer's body and load are hidden and its bed cannot be opened
     *                 (what it holds stays), its wheels too unless keepWheels
     * @param speed    top speed while it is attached (0 = the vehicle's own)
     * @param seconds  NET: the average seconds between catches
     * @param fold     COVER: its length's share when taken off (folded at the front)
     * @param ticks    COVER: how long it takes to unfold or fold
     */
    public record Tool(String id, String name, Kind kind, int unit, boolean replaces, boolean keepWheels, NamespacedKey model,
                       VehicleSpec.Vec at, float scale, List<Part> parts, Work work, double speed, double seconds, double fold, int ticks) {}

    /** The 탑차's rear roller door: the slats (rolled up about their top), the open doorway behind them, how long it takes. */
    public record Shutter(NamespacedKey model, NamespacedKey inside, VehicleSpec.Vec at, float scale, double rolled, int ticks) {}

    /** Mode 0 is the vehicle as it comes; mode i is tools().get(i - 1). */
    public Tool tool(int mode) {
        return mode <= 0 || mode > tools.size() ? null : tools.get(mode - 1);
    }

    /** The mode with this tool id (0 = none, or not one of these). */
    public int mode(String id) {
        if (id == null) return 0;
        for (int i = 0; i < tools.size(); i++) if (tools.get(i).id().equals(id)) return i + 1;
        return 0;
    }

    /** How many modes there are (the vehicle as it comes and each tool). */
    public int modes() {
        return tools.size() + 1;
    }

    /** The cover tool, if any (it stays drawn folded while off). */
    public Tool cover() {
        for (Tool t : tools) if (t.kind() == Kind.COVER) return t;
        return null;
    }

    /** Every model this shows (render budget, model checks). */
    public List<NamespacedKey> models() {
        List<NamespacedKey> out = new ArrayList<>();
        for (Tool t : tools) {
            out.add(t.model());
            for (Part p : t.parts()) out.add(p.model());
        }
        if (shutter != null) {
            out.add(shutter.model());
            out.add(shutter.inside());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /**
     * Reads one vehicle's section.
     *
     * @param modelExists whether the pack has models/&lt;name&gt;.bbmodel
     */
    public static VehicleToolSpec parse(ConfigurationSection s, String namespace, Predicate<String> modelExists) {
        String base = s.getString("base", "");
        List<Tool> tools = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<?, ?> m : s.getMapList("tools")) {
            Tool t = tool(section(m), namespace, modelExists);
            if (!ids.add(t.id())) throw new IllegalArgumentException("tools: 같은 id 가 두 번 (" + t.id() + ")");
            tools.add(t);
        }
        Shutter shutter = null;
        if (s.isConfigurationSection("shutter")) {
            ConfigurationSection h = s.getConfigurationSection("shutter");
            double rolled = h.getDouble("rolled", 0.08);
            if (rolled < 0.02 || rolled > 1) throw new IllegalArgumentException("shutter.rolled 는 0.02 ~ 1");
            int ticks = h.getInt("ticks", 14);
            if (ticks < 1 || ticks > 60) throw new IllegalArgumentException("shutter.ticks 는 1 ~ 60");
            shutter = new Shutter(model(h.getString("model"), "shutter.model", namespace, modelExists),
                    model(h.getString("inside"), "shutter.inside", namespace, modelExists), vec(h, "at", "shutter.at"), scale(h, "shutter.scale"),
                    rolled, ticks);
        }
        if (tools.isEmpty() && shutter == null) throw new IllegalArgumentException("tools 도 shutter 도 없음");
        if (tools.stream().filter(t -> t.kind() == Kind.COVER).count() > 1) throw new IllegalArgumentException("tools: cover 는 하나만");
        return new VehicleToolSpec(base, List.copyOf(tools), shutter);
    }

    private static Tool tool(ConfigurationSection t, String namespace, Predicate<String> modelExists) {
        String id = t.getString("id", "");
        if (!id.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("tools.id 는 a-z 0-9 _ (1 ~ 32자): " + id);
        String what = "tools." + id;
        Kind kind;
        try {
            kind = Kind.valueOf(t.getString("kind", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ".kind 는 till, sow, harvest, net, cover 중 하나");
        }
        int unit = t.getInt("unit", 0);
        if (unit < 0 || unit > 8) throw new IllegalArgumentException(what + ".unit 은 0 ~ 8");
        boolean replaces = t.getBoolean("replaces", false);
        if (replaces && unit == 0) throw new IllegalArgumentException(what + ".replaces: 끄는 쪽(unit 0)은 바꿀 수 없음 (트레일러만)");
        List<Part> parts = new ArrayList<>();
        for (Map<?, ?> m : t.getMapList("parts")) {
            ConfigurationSection p = section(m);
            String axis = p.getString("axis", "x");
            if (!axis.equals("x") && !axis.equals("z")) throw new IllegalArgumentException(what + ".parts.axis 는 x 또는 z");
            Spin spin;
            try {
                spin = Spin.valueOf(p.getString("spin", "pto").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(what + ".parts.spin 은 pto 또는 roll");
            }
            double rate = p.getDouble("rate", 0), radius = p.getDouble("radius", 0);
            if (spin == Spin.PTO && (rate == 0 || Math.abs(rate) > 90)) throw new IllegalArgumentException(what + ".parts.rate 는 -90 ~ 90, 0 이 아님 (도/틱, - = 반대로)");
            if (spin == Spin.ROLL && (radius <= 0 || radius > 2)) throw new IllegalArgumentException(what + ".parts.radius 는 0 초과 2 이하 (블록)");
            parts.add(new Part(model(p.getString("model"), what + ".parts.model", namespace, modelExists), vec(p, "at", what + ".parts.at"),
                    axis.charAt(0), spin, rate, radius, scale(p, what + ".parts.scale")));
        }
        Work work = null;
        if (kind.works()) {
            if (!t.isConfigurationSection("work")) throw new IllegalArgumentException(what + ".work 가 없음 (작업하는 줄: at, width)");
            ConfigurationSection w = t.getConfigurationSection("work");
            int width = w.getInt("width", 1);
            if (width < 1 || width > 5) throw new IllegalArgumentException(what + ".work.width 는 1 ~ 5 (블록)");
            work = new Work(vec(w, "at", what + ".work.at"), width);
        }
        double speed = t.getDouble("speed", 0);
        if (speed < 0 || speed > 20) throw new IllegalArgumentException(what + ".speed 는 0 ~ 20 (블록/초, 0 = 탈것 그대로)");
        double seconds = 0;
        if (kind == Kind.NET) {
            seconds = t.getDouble("net.seconds", 40);
            if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException(what + ".net.seconds 는 5 ~ 3600");
        }
        double fold = 1;
        int ticks = 0;
        if (kind == Kind.COVER) {
            fold = t.getDouble("cover.fold", 0.12);
            if (fold < 0.02 || fold > 1) throw new IllegalArgumentException(what + ".cover.fold 는 0.02 ~ 1");
            ticks = t.getInt("cover.ticks", 12);
            if (ticks < 1 || ticks > 60) throw new IllegalArgumentException(what + ".cover.ticks 는 1 ~ 60");
        }
        return new Tool(id, t.getString("name", id), kind, unit, replaces, t.getBoolean("wheels", false),
                model(t.getString("model"), what + ".model", namespace, modelExists), t.contains("at") ? vec(t, "at", what + ".at")
                : new VehicleSpec.Vec(0, 0, 0), scale(t, what + ".scale"), List.copyOf(parts), work, speed, seconds, fold, ticks);
    }

    /**
     * What is wrong with these tools on this vehicle (empty = nothing): a unit it does not have, a sower or harvester
     * without a bed, a net on something that does not float, a cover over a bed without loads to hide.
     */
    public List<String> problems(VehicleSpec v) {
        List<String> out = new ArrayList<>();
        int units = v.train() == null ? 0 : v.train().trailers().size();
        for (Tool t : tools) {
            if (t.unit() > units) out.add(t.id() + ": unit " + t.unit() + " 이 없음 (트레일러 " + units + "대)");
            if ((t.kind() == Kind.SOW || t.kind() == Kind.HARVEST || t.kind() == Kind.NET) && v.cargo() == null)
                out.add(t.id() + ": 짐칸이 없는 탈것 (씨앗·수확물·물고기를 둘 곳)");
            if (t.kind() == Kind.NET && !v.floats()) out.add(t.id() + ": 그물은 배에만");
            if (t.kind() != Kind.NET && v.floats()) out.add(t.id() + ": 배에는 그물만");
            if (t.kind() == Kind.COVER && (v.cargo() == null || v.train() != null)) out.add(t.id() + ": 덮개는 짐칸이 하나인 탈것에만");
            if (t.replaces() && (t.kind() == Kind.SOW || t.kind() == Kind.HARVEST) && units <= 1)
                out.add(t.id() + ": 짐칸을 대신하는 파종기·수확기는 다른 트레일러가 있어야 함");
        }
        if (shutter != null && v.cargo() == null) out.add("shutter: 짐칸이 없는 탈것");
        return out;
    }

    private static float scale(ConfigurationSection s, String what) {
        double k = s.getDouble("scale", 1);
        if (k <= 0 || k > 4) throw new IllegalArgumentException(what + " 는 0 초과 4 이하");
        return (float) k;
    }

    private static NamespacedKey model(String name, String what, String namespace, Predicate<String> modelExists) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException(what + " 모델이 없음");
        if (name.contains(":")) {
            NamespacedKey k = NamespacedKey.fromString(name);
            if (k == null) throw new IllegalArgumentException(what + ": 잘못된 모델 id " + name);
            return k;
        }
        if (!modelExists.test(name)) throw new IllegalArgumentException(what + ": models/" + name + ".bbmodel 이 없음");
        return new NamespacedKey(namespace, "model/" + name);
    }

    private static VehicleSpec.Vec vec(ConfigurationSection s, String key, String what) {
        List<Double> l = s.getDoubleList(key);
        if (l.size() != 3) throw new IllegalArgumentException(what + " 는 [오른쪽, 위, 앞] (블록)");
        return new VehicleSpec.Vec(l.get(0), l.get(1), l.get(2));
    }

    private static ConfigurationSection section(Map<?, ?> map) {
        return new YamlConfiguration().createSection("entry", map);
    }
}
