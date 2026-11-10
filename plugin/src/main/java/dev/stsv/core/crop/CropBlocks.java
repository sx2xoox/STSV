package dev.stsv.core.crop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Custom crops drawn as real blocks: every growth stage of every crop is one state of the vanilla tripwire (string)
 * block, which the resource pack draws as that stage's model (assets/minecraft/blockstates/tripwire.json). Pure data
 * and rules (unit-tested); CropModule places the blocks, PackBuilder writes the models.
 *
 * <p>A state is a 7-bit code, the tripwire's properties in the order the game writes them:
 * attached (64), disarmed (32), east (16), north (8), powered (4), south (2), west (1). Two codes stay plain string
 * ({@link #PLAIN}, {@link #PLAIN_ATTACHED}); the other 126 can show crops. Attached states have a thin selection outline
 * (1.5 px), so they go to the earliest stages; the rest have a half-block outline.
 *
 * <p>Which stage has which state is written down once, in each content pack's {@code crop-blocks.yml} (made by
 * tools/assetgen/crop_blocks.py, committed). New crops and stages are appended; an entry is never moved or reused, so a
 * crop keeps its state across releases. Stages missing from the file get the next free states here at start, the same
 * way the generator would, with a warning.
 */
public final class CropBlocks {

    public static final int STATES = 128;
    public static final int ATTACHED = 64, DISARMED = 32, EAST = 16, NORTH = 8, POWERED = 4, SOUTH = 2, WEST = 1;
    /** Kept as plain string: a lone piece of string, and one hung between hooks (what old string can be turned into). */
    public static final int PLAIN = 0, PLAIN_ATTACHED = ATTACHED;
    /** The allocation file at the root of a content pack. */
    public static final String FILE = "crop-blocks.yml";

    private CropBlocks() {}

    public static boolean reserved(int code) {
        return code == PLAIN || code == PLAIN_ATTACHED;
    }

    /** A state with the thin (string-high) selection outline. */
    public static boolean thin(int code) {
        return (code & ATTACHED) != 0;
    }

    public static int code(boolean attached, boolean disarmed, boolean east, boolean north, boolean powered, boolean south, boolean west) {
        return (attached ? ATTACHED : 0) | (disarmed ? DISARMED : 0) | (east ? EAST : 0) | (north ? NORTH : 0) | (powered ? POWERED : 0)
                | (south ? SOUTH : 0) | (west ? WEST : 0);
    }

    /** The block state properties of a code, as in a blockstate file or "minecraft:tripwire[...]". */
    public static String properties(int code) {
        check(code);
        return "attached=" + has(code, ATTACHED) + ",disarmed=" + has(code, DISARMED) + ",east=" + has(code, EAST) + ",north=" + has(code, NORTH)
                + ",powered=" + has(code, POWERED) + ",south=" + has(code, SOUTH) + ",west=" + has(code, WEST);
    }

    /** The code of "attached=true,disarmed=false,..." (any order, all seven properties). */
    public static int parse(String properties) {
        int code = 0, seen = 0;
        for (String part : properties.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2 || !(kv[1].equals("true") || kv[1].equals("false"))) throw new IllegalArgumentException(properties);
            int bit = switch (kv[0]) {
                case "attached" -> ATTACHED;
                case "disarmed" -> DISARMED;
                case "east" -> EAST;
                case "north" -> NORTH;
                case "powered" -> POWERED;
                case "south" -> SOUTH;
                case "west" -> WEST;
                default -> throw new IllegalArgumentException(properties);
            };
            seen |= bit;
            if (kv[1].equals("true")) code |= bit;
        }
        if (seen != STATES - 1) throw new IllegalArgumentException("not all seven properties: " + properties);
        return code;
    }

    private static boolean has(int code, int bit) {
        return (code & bit) != 0;
    }

    private static void check(int code) {
        if (code < 0 || code >= STATES) throw new IllegalArgumentException("tripwire state " + code);
    }

    /** States new entries take, in order: thin ones for early stages, tall ones for the rest. Fixed forever. */
    public static List<Integer> pool(boolean thin) {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < STATES; c++) if (thin(c) == thin && !reserved(c)) out.add(c);
        return out;
    }

    /** An early stage (first half of the stages): its plant is small, so the thin outline fits it. */
    public static boolean early(int stage, int stages) {
        return stage < stages / 2;
    }

    public static String key(String crop, int stage) {
        return crop + "/" + stage;
    }

    /** What vanilla draws for a tripwire state (its model and y turn), for the states that stay string. */
    public record Vanilla(String model, int y) {}

    public static Vanilla vanilla(int code) {
        check(code);
        boolean n = has(code, NORTH), e = has(code, EAST), s = has(code, SOUTH), w = has(code, WEST);
        String base = "minecraft:block/tripwire" + (thin(code) ? "_attached" : "") + "_";
        int count = (n ? 1 : 0) + (e ? 1 : 0) + (s ? 1 : 0) + (w ? 1 : 0);
        return switch (count) {
            case 0 -> new Vanilla(base + "ns", 0);
            case 1 -> new Vanilla(base + "n", n ? 0 : e ? 90 : s ? 180 : 270);
            case 2 -> {
                if (n && s) yield new Vanilla(base + "ns", 0);
                if (e && w) yield new Vanilla(base + "ns", 90);
                yield new Vanilla(base + "ne", n && e ? 0 : e && s ? 90 : s && w ? 180 : 270);
            }
            case 3 -> new Vanilla(base + "nse", !w ? 0 : !n ? 90 : !e ? 180 : 270);
            default -> new Vanilla(base + "nsew", 0);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Allocation
    // ------------------------------------------------------------------------------------------

    /** One crop as far as states go: its id and how many stages (models) it has. */
    public record Crop(String id, int stages) {}

    /** States ran out: the pack cannot show every crop. */
    public static final class Full extends Exception {
        public Full(String message) {
            super(message);
        }
    }

    /**
     * Which crop stage each state shows.
     *
     * @param byKey   "crop/stage" -> code, every stage of every crop (and entries of crops no longer defined: kept, unused)
     * @param added   keys that were not in the files (given states now; the generator writes the same)
     * @param problems entries of the files that were ignored (bad code, reserved, a state taken twice)
     */
    public record Allocation(Map<String, Integer> byKey, Map<Integer, String> byCode, List<String> added, List<String> problems) {

        public Integer code(String crop, int stage) {
            return byKey.get(key(crop, stage));
        }

        /** The crop stage a state shows ("crop/stage"), or null (plain string or unused). */
        public String keyOf(int code) {
            return byCode.get(code);
        }

        public static Allocation empty() {
            return new Allocation(Map.of(), Map.of(), List.of(), List.of());
        }
    }

    /**
     * Resolves the states: the files' entries as they are (in file order, earlier packs first), then every stage of
     * {@code crops} not in them, in crop order then stage order, from the pools (an early stage from the thin pool and
     * a later one from the tall pool, the other pool when that one is empty).
     *
     * @param files entries read from the packs' files, in order ("crop/stage" -> code)
     * @throws Full when a stage is left without a state
     */
    public static Allocation allocate(List<Map<String, Integer>> files, List<Crop> crops) throws Full {
        Map<String, Integer> byKey = new LinkedHashMap<>();
        Map<Integer, String> byCode = new TreeMap<>();
        List<String> problems = new ArrayList<>(), added = new ArrayList<>();
        for (Map<String, Integer> file : files) {
            for (Map.Entry<String, Integer> e : file.entrySet()) {
                Integer c = e.getValue();
                if (c == null || c < 0 || c >= STATES || reserved(c)) {
                    problems.add(e.getKey() + ": 쓸 수 없는 상태 " + c);
                } else if (byCode.containsKey(c)) {
                    problems.add(e.getKey() + ": 상태 " + c + " 는 이미 " + byCode.get(c) + " 가 씀");
                } else if (byKey.containsKey(e.getKey())) {
                    problems.add(e.getKey() + ": 두 번 적힘");
                } else {
                    byKey.put(e.getKey(), c);
                    byCode.put(c, e.getKey());
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (Crop crop : crops) {
            for (int st = 0; st < crop.stages(); st++) {
                String k = key(crop.id(), st);
                if (byKey.containsKey(k)) continue;
                Integer c = next(byCode, early(st, crop.stages()));
                if (c == null) {
                    missing.add(k);
                    continue;
                }
                byKey.put(k, c);
                byCode.put(c, k);
                added.add(k);
            }
        }
        if (!missing.isEmpty()) {
            throw new Full("작물 블록 상태가 모자랍니다: 트립와이어 상태 " + (STATES - 2) + "개를 모두 썼고 " + missing.size()
                    + "개 단계가 남음 (" + String.join(", ", missing.subList(0, Math.min(8, missing.size()))) + (missing.size() > 8 ? ", ..." : "")
                    + "). 작물·단계 수를 줄이거나 " + FILE + " 에서 없어진 작물의 줄을 지우세요.");
        }
        return new Allocation(Collections.unmodifiableMap(byKey), Collections.unmodifiableMap(byCode), List.copyOf(added), List.copyOf(problems));
    }

    /** The entries of one allocation file ("crop/stage" -> code, in file order); empty if there is none. */
    public static Map<String, Integer> read(java.nio.file.Path file) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!java.nio.file.Files.isRegularFile(file)) return out;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        org.bukkit.configuration.ConfigurationSection states = y.getConfigurationSection("states");
        if (states == null) return out;
        for (String k : states.getKeys(false)) out.put(k, states.isInt(k) ? states.getInt(k) : null);
        return out;
    }

    /** The states of these packs' crops: their allocation files, then the stages the files miss. */
    public static Allocation load(java.util.Collection<dev.stsv.core.content.ContentPack> packs, java.util.Collection<CropSpec> specs) throws Full {
        List<Map<String, Integer>> files = new ArrayList<>();
        for (dev.stsv.core.content.ContentPack p : packs) files.add(read(p.dir().resolve(FILE)));
        List<Crop> crops = new ArrayList<>();
        for (CropSpec s : specs) crops.add(new Crop(s.id(), s.stages()));
        return allocate(files, crops);
    }

    private static Integer next(Map<Integer, String> used, boolean thin) {
        for (boolean t : new boolean[]{thin, !thin}) {
            for (int c : pool(t)) if (!used.containsKey(c)) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // What to do with a block
    // ------------------------------------------------------------------------------------------

    /** For a planted crop's block, at chunk load or when it is checked. */
    public enum Placement {
        /** it already shows the crop */
        OK,
        /** set the crop's state (air, something replaceable, or a tripwire in another state) */
        SET,
        /** something else is there (a block someone put by other means): the crop comes out as its seed */
        POP
    }

    /**
     * @param tripwire     the block is a tripwire
     * @param sameState    ... in exactly the crop's state
     * @param empty        air, or a replaceable block that is not a fluid (grass, snow)
     */
    public static Placement placement(boolean tripwire, boolean sameState, boolean empty) {
        if (tripwire) return sameState ? Placement.OK : Placement.SET;
        return empty ? Placement.SET : Placement.POP;
    }

    /**
     * A block placement that would put tripwire (string) in the world: refused while crops are blocks. Whatever the item
     * (string, or anything else that would end up as tripwire); the item stays in the hand.
     */
    public static boolean stringPlacement(org.bukkit.Material placed, org.bukkit.Material inHand) {
        return placed == org.bukkit.Material.TRIPWIRE || inHand == org.bukkit.Material.STRING;
    }

    /** Version of the stray-tripwire check a chunk is marked with; a chunk with an older mark (or none) is checked again. */
    public static final int CHECK_VERSION = 1;

    /** Does a chunk with this mark (null = none) still need its stray tripwire handled? */
    public static boolean needsCheck(Integer mark) {
        return mark == null || mark < CHECK_VERSION;
    }

    /** What happens to tripwire that is not a planted crop (old string, temple traps). */
    public enum Stray {
        /** removed (the default): it can never look like a crop */
        AIR,
        /** turned into plain string (one piece; how it connected is lost) */
        STRING;

        public static Stray of(String s) {
            return s != null && s.trim().equalsIgnoreCase("string") ? STRING : AIR;
        }
    }

    /**
     * The state stray tripwire becomes: -1 = air, else a plain-string code; the same code = leave it (already plain).
     */
    public static int stray(int code, Stray mode) {
        if (mode == Stray.AIR) return -1;
        return thin(code) ? PLAIN_ATTACHED : PLAIN;
    }
}
