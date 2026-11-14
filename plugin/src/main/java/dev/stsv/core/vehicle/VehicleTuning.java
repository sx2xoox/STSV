package dev.stsv.core.vehicle;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The tuning shop's catalogue (content/&lt;pack&gt;/vehicle_tuning.yml) and what a vehicle has been given from it.
 * Pure: no server.
 *
 * <p>Tuning changes how a vehicle <b>looks</b>, never how it drives. There are {@link Kind kinds} of it (paint,
 * wheels, a glow under the car, ...); each kind has options (an id, a name, a price, and what the kind needs: a
 * colour, a particle, a number of pops); and each vehicle lists, kind by kind, the options it may take. A vehicle
 * carries at most one option of a kind ({@link Custom}); none is its stock look. An option that the vehicle cannot
 * show (no model for it in the pack, nothing on the vehicle for it to change) is left out when the file is read,
 * with a line in the log.
 *
 * <p>Looks that are models are drawn ahead of time (tools/assetgen/models/vehicle_paints.py) and found by name:
 * {@link #painted}, {@link #part}, {@link #wheel}, {@link #headlamp}, {@link #underglow}.
 */
public final class VehicleTuning {

    private VehicleTuning() {}

    /** What can be changed. The order is the shop's. */
    public enum Kind {
        /** The body (and its moving parts) in another paint: a model each. */
        PAINT("paints"),
        /** The wheels in another design, the same size: a model each. */
        WHEEL("wheels"),
        /** A glow on the ground under the car: a model each, stretched to the car. */
        UNDERGLOW("underglows"),
        /** The headlamps in another colour (lens, glow and beam): a model each. */
        HEADLAMP("headlamps"),
        /** The marks the tyres leave in a drift: a style of vehicle_feel.yml skid-marks. */
        SKID("skids"),
        /** The smoke off the tyres (a launch, a drift) in a colour. */
        SMOKE("smokes"),
        /** The flame out of the exhaust: a particle, or a colour. */
        FLAME("flames"),
        /** How the exhaust crackles when the throttle is lifted: how many pops, how far apart. */
        CRACKLE("crackles"),
        /** The name plate's ink. */
        PLATE("plates");

        /** The section of the file, and the word in a vehicle's list. */
        public final String section;

        Kind(String section) {
            this.section = section;
        }

        /** The kind's word in what is stored and typed (paint, wheel, ...). */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind of(String word) {
            if (word == null) return null;
            for (Kind k : values()) if (k.word().equals(word.toLowerCase(Locale.ROOT)) || k.section.equals(word.toLowerCase(Locale.ROOT))) return k;
            return null;
        }
    }

    /**
     * One thing to choose.
     *
     * @param color    0xRRGGBB (the swatch in the shop; the colour itself for smoke, a coloured flame, a plate), -1 none
     * @param particle FLAME: "flame", "soul", "copper" (the game's own flames) or "dust" (a puff of {@code color})
     * @param pops     CRACKLE: pops in a burst (0: silent)
     * @param gapMin   CRACKLE: ticks between two pops, at least
     * @param gapMax   and at most
     */
    public record Option(String id, String name, long price, int color, String particle, int pops, int gapMin, int gapMax) {}

    /** What a vehicle has: one option id a kind, at most. Unmodifiable. */
    public record Custom(Map<Kind, String> chosen) {

        public static final Custom NONE = new Custom(Map.of());

        public Custom {
            chosen = chosen.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(chosen));
        }

        /** The option of a kind, null for the stock look. */
        public String get(Kind kind) {
            return chosen.get(kind);
        }

        /** This with another option of a kind (null: the stock look again). */
        public Custom with(Kind kind, String id) {
            EnumMap<Kind, String> m = new EnumMap<>(Kind.class);
            m.putAll(chosen);
            if (id == null) m.remove(kind);
            else m.put(kind, id);
            return new Custom(m);
        }

        /** "paint=wine;wheel=turbine" (the kinds in their order); "" for none. */
        public String encode() {
            StringBuilder b = new StringBuilder();
            for (Kind k : Kind.values()) {
                String id = chosen.get(k);
                if (id == null) continue;
                if (!b.isEmpty()) b.append(';');
                b.append(k.word()).append('=').append(id);
            }
            return b.toString();
        }

        /** Reads {@link #encode}; whatever cannot be read (an unknown kind, a broken pair) is left out. */
        public static Custom decode(String raw) {
            if (raw == null || raw.isBlank()) return NONE;
            EnumMap<Kind, String> m = new EnumMap<>(Kind.class);
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || eq == pair.length() - 1) continue;
                Kind k = Kind.of(pair.substring(0, eq).trim());
                String id = pair.substring(eq + 1).trim();
                if (k != null && id.matches("[a-z0-9_]{1,32}")) m.put(k, id);
            }
            return new Custom(m);
        }
    }

    /**
     * What is known about a vehicle when the file is read (from its item and its feel).
     *
     * @param body     its body's model name
     * @param wheel    its wheel's model name (null: none)
     * @param headlamp its lit headlamps' model name (null: none)
     * @param marks    it leaves skid marks
     * @param sport    it shoots flames and smokes its tyres
     * @param plates   it has name plates
     */
    public record Facts(String body, String wheel, String headlamp, boolean marks, boolean sport, boolean plates) {}

    /** What the reader of the file asks about the server's content. */
    public interface Content {
        /** The vehicle item (its id without namespace), null if there is none. */
        Facts vehicle(String item);

        /** Is there a model of this name? */
        boolean model(String name);

        /** Is there a skid mark style of this name (vehicle_feel.yml skid-marks)? */
        boolean skid(String id);
    }

    /**
     * @param options  kind -> option id -> option, in the file's order
     * @param vehicles vehicle item id -> kind -> the ids it may take, in the catalogue's order (no empty lists)
     */
    public record Catalogue(Map<Kind, Map<String, Option>> options, Map<String, Map<Kind, List<String>>> vehicles) {

        public static final Catalogue EMPTY = new Catalogue(Map.of(), Map.of());

        public Option option(Kind kind, String id) {
            return id == null ? null : options.getOrDefault(kind, Map.of()).get(id);
        }

        /** The kinds a vehicle can be tuned in, in the shop's order. */
        public List<Kind> kinds(String vehicle) {
            return List.copyOf(vehicles.getOrDefault(vehicle, Map.of()).keySet());
        }

        /** The options of a kind a vehicle may take. */
        public List<Option> offered(String vehicle, Kind kind) {
            List<Option> out = new ArrayList<>();
            for (String id : vehicles.getOrDefault(vehicle, Map.of()).getOrDefault(kind, List.of())) out.add(option(kind, id));
            return out;
        }

        public boolean offers(String vehicle, Kind kind, String id) {
            return id != null && vehicles.getOrDefault(vehicle, Map.of()).getOrDefault(kind, List.of()).contains(id);
        }

        /** What of a custom look this vehicle may still show (an option taken out of the file since is dropped). */
        public Custom valid(String vehicle, Custom c) {
            Custom out = c;
            for (Map.Entry<Kind, String> e : c.chosen().entrySet()) if (!offers(vehicle, e.getKey(), e.getValue())) out = out.with(e.getKey(), null);
            return out;
        }

        /**
         * What it costs to go from one look to another: every option that is new is paid for; going back to the
         * stock look of a kind is free, and so is what stays.
         */
        public long price(Custom from, Custom to) {
            long sum = 0;
            for (Map.Entry<Kind, String> e : to.chosen().entrySet()) {
                if (e.getValue().equals(from.get(e.getKey()))) continue;
                Option o = option(e.getKey(), e.getValue());
                if (o != null) sum = Math.addExact(sum, o.price());
            }
            return sum;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Model names (tools/assetgen/models/vehicle_paints.py)
    // ------------------------------------------------------------------------------------------

    public static String painted(String body, String paint) {
        return body + "_p_" + paint;
    }

    /** A moving part of a painted body ("grand_sedan_door_fl" of "grand_sedan" in wine: "grand_sedan_p_wine_door_fl"). */
    public static String part(String part, String body, String paint) {
        return part.startsWith(body + "_") ? painted(body, paint) + part.substring(body.length()) : part;
    }

    public static String wheel(String stock, String design) {
        return stock + "_w_" + design;
    }

    public static String headlamp(String stock, String colour) {
        return stock + "_c_" + colour;
    }

    public static String underglow(String colour) {
        return "underglow_" + colour;
    }

    private static final java.util.regex.Pattern PAINTED = java.util.regex.Pattern.compile("^(.+)_p_[a-z0-9]+(_.+)?$"),
            WHEEL = java.util.regex.Pattern.compile("^(wheel_.+)_w_[a-z0-9]+$"), HEADLAMP = java.util.regex.Pattern.compile("^(.+_lamp_head)_c_[a-z0-9]+$");

    /**
     * The model a tuning variant is another look of ("grand_sedan_p_wine_door_fl": "grand_sedan_door_fl",
     * "wheel_grand_w_turbine": "wheel_grand"), or the name itself when it is no variant. What holds for a model by its
     * name (its kind, its budget of faces) holds for its variants.
     */
    public static String stock(String model) {
        java.util.regex.Matcher m = PAINTED.matcher(model);
        if (m.matches()) return m.group(1) + (m.group(2) == null ? "" : m.group(2));
        m = WHEEL.matcher(model);
        if (m.matches()) return m.group(1);
        m = HEADLAMP.matcher(model);
        return m.matches() ? m.group(1) : model;
    }

    // ------------------------------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------------------------------

    /** Whether a vehicle can show an option of a kind at all (null), or why not. */
    static String cannot(Kind kind, Option o, Facts f, Content content) {
        return switch (kind) {
            case PAINT -> content.model(painted(f.body(), o.id())) ? null : "모델 " + painted(f.body(), o.id()) + " 없음";
            case WHEEL -> f.wheel() == null ? "바퀴 없음" : content.model(wheel(f.wheel(), o.id())) ? null : "모델 " + wheel(f.wheel(), o.id()) + " 없음";
            case HEADLAMP -> f.headlamp() == null ? "전조등 없음"
                    : content.model(headlamp(f.headlamp(), o.id())) ? null : "모델 " + headlamp(f.headlamp(), o.id()) + " 없음";
            case UNDERGLOW -> content.model(underglow(o.id())) ? null : "모델 " + underglow(o.id()) + " 없음";
            case SKID -> !f.marks() ? "스키드 마크를 남기지 않는 탈것" : content.skid(o.id()) ? null : "vehicle_feel.yml skid-marks 에 " + o.id() + " 없음";
            case SMOKE, FLAME, CRACKLE -> f.sport() ? null : "배기 · 타이어 효과가 없는 탈것";
            case PLATE -> f.plates() ? null : "번호판이 없는 탈것";
        };
    }

    private static int color(Object raw) {
        if (raw == null) return -1;
        String s = raw.toString().trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            return s.length() == 6 ? Integer.parseInt(s, 16) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Reads vehicle_tuning.yml.
     *
     * @param problems one line for each thing left out
     */
    public static Catalogue parse(ConfigurationSection file, Content content, Consumer<String> problems) {
        Map<Kind, Map<String, Option>> options = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            ConfigurationSection sec = file.getConfigurationSection(kind.section);
            if (sec == null) continue;
            Map<String, Option> of = new LinkedHashMap<>();
            for (String id : sec.getKeys(false)) {
                ConfigurationSection o = sec.getConfigurationSection(id);
                if (o == null || !id.matches("[a-z0-9_]{1,32}")) {
                    problems.accept(kind.section + "." + id + ": id 는 영문 소문자·숫자·_ (32자까지), 값은 { name, price, ... }");
                    continue;
                }
                long price = o.getLong("price");
                String particle = o.getString("particle", "flame").toLowerCase(Locale.ROOT);
                List<Integer> gap = o.getIntegerList("gap");
                int color = color(o.get("color"));
                if (price < 0) {
                    problems.accept(kind.section + "." + id + ": price 는 0 이상");
                    continue;
                }
                if (kind == Kind.FLAME && !List.of("flame", "soul", "copper", "dust").contains(particle)) {
                    problems.accept(kind.section + "." + id + ": particle 은 flame, soul, copper, dust 중 하나");
                    continue;
                }
                if (color < 0 && (kind == Kind.SMOKE || kind == Kind.PLATE || (kind == Kind.FLAME && particle.equals("dust")))) {
                    problems.accept(kind.section + "." + id + ": color \"#rrggbb\" 가 있어야 함");
                    continue;
                }
                int pops = Math.clamp(o.getInt("pops"), 0, 12);
                int gapMin = Math.clamp(gap.isEmpty() ? 2 : gap.getFirst(), 1, 40), gapMax = Math.clamp(gap.size() < 2 ? gapMin + 3 : gap.get(1), gapMin, 40);
                of.put(id, new Option(id, o.getString("name", id), price, color, particle, pops, gapMin, gapMax));
            }
            if (!of.isEmpty()) options.put(kind, Collections.unmodifiableMap(of));
        }

        Map<String, Map<Kind, List<String>>> vehicles = new LinkedHashMap<>();
        ConfigurationSection vs = file.getConfigurationSection("vehicles");
        for (String item : vs == null ? List.<String>of() : vs.getKeys(false)) {
            ConfigurationSection v = vs.getConfigurationSection(item);
            Facts facts = content.vehicle(item);
            if (v == null || facts == null) {
                problems.accept("vehicles." + item + ": 그런 탈것 아이템이 없음");
                continue;
            }
            Map<Kind, List<String>> kinds = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                if (!v.contains(kind.section)) continue;
                Map<String, Option> known = options.getOrDefault(kind, Map.of());
                boolean all = v.isString(kind.section) && v.getString(kind.section).equalsIgnoreCase("all");
                List<String> wanted = all ? List.copyOf(known.keySet()) : v.getStringList(kind.section);
                List<String> ok = new ArrayList<>();
                for (String id : known.keySet()) {            // the catalogue's order
                    if (!wanted.contains(id)) continue;
                    String why = cannot(kind, known.get(id), facts, content);
                    // "all" means all it can show: what it cannot is left out quietly (a paint that is its own colour)
                    if (why == null) ok.add(id);
                    else if (!all) problems.accept("vehicles." + item + "." + kind.section + ": " + id + " 뺌 (" + why + ")");
                }
                for (String id : wanted) if (!known.containsKey(id)) problems.accept("vehicles." + item + "." + kind.section + ": 모르는 항목 " + id);
                if (!ok.isEmpty()) kinds.put(kind, List.copyOf(ok));
            }
            if (!kinds.isEmpty()) vehicles.put(item, Collections.unmodifiableMap(kinds));
        }
        return new Catalogue(Collections.unmodifiableMap(options), Collections.unmodifiableMap(vehicles));
    }
}
