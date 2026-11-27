package dev.stsv.economy.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The worker's actions made visible (기획안 §17, README "편의점"): the steps the things go through as they are done — a
 * spill getting smaller under the mop, a shelf filling from its open box, the kettle's water level, the 발주 tablet's pages,
 * the microwave's door — and the bookkeeping of what a worker holds in place of the scanner. Pure (unit-tested); the
 * models it names are items/store.yml's (§17 계약), each one optional: the store falls back to the old look when the pack
 * does not have it.
 */
public final class StoreMotion {

    private StoreMotion() {}

    // ------------------------------------------------------------------------------------------ the ids (§17)

    public static final String MOP = "economy:store_held_mop", JUG = "economy:store_held_jug", WASTE_BAG = "economy:store_held_waste_bag",
            TRAY = "economy:store_held_tray", PDA = "economy:store_held_pda", HELD_ID = "economy:store_held_id",
            WET_SIGN = "economy:store_wet_sign", HANGER_EMPTY = "economy:store_hanger_empty", ID_CARD = "economy:store_id_card";

    /** The box a worker carries in both arms. */
    public static String heldBox(String box) {
        return "economy:store_held_box_" + box;
    }

    /** The box opened on the floor by the shelf it fills. */
    public static String openBox(String box) {
        return "economy:store_box_" + box + "_open";
    }

    /** Held things that only show what the worker is doing a moment: put back by themselves (another action, a timer). */
    public static boolean brief(String held) {
        return MOP.equals(held) || JUG.equals(held) || PDA.equals(held) || HELD_ID.equals(held);
    }

    // ------------------------------------------------------------------------------------------ 대걸레: the spill's stages

    /** Strokes of the mop a spill takes (store_spill → _2 → _3 → gone). */
    public static final int WIPES = 3;

    /** A spill's model at this stage (1 = the biggest, store_spill), null past the last. */
    public static String spillModel(int stage) {
        return switch (stage) {
            case 1 -> "economy:store_spill";
            case 2 -> "economy:store_spill_2";
            case 3 -> "economy:store_spill_3";
            default -> null;
        };
    }

    /** The stage after one stroke: 0 = gone. */
    public static int wiped(int stage) {
        return stage <= 0 || stage >= WIPES ? 0 : stage + 1;
    }

    /** How big the first model is drawn at this stage when the pack has no smaller one (1, 0.75, 0.5). */
    public static float spillScale(int stage) {
        return stage <= 1 ? 1f : Math.max(0.35f, 1f - 0.25f * (stage - 1));
    }

    // ------------------------------------------------------------------------------------------ 진열: the shelf filling

    /** How many things pop out of the box onto the shelf, one by one. */
    public static final int POPS = 6;

    /**
     * The stock after each of `pops` pops from `from` to `capacity` (even steps, the last exactly the capacity): one step when
     * there is nothing to add.
     */
    public static List<Integer> fillSteps(int from, int capacity, int pops) {
        int a = Math.clamp(from, 0, Math.max(0, capacity)), n = Math.max(1, pops);
        List<Integer> out = new ArrayList<>();
        if (a >= capacity) {
            out.add(capacity);
            return out;
        }
        for (int i = 1; i <= n; i++) out.add(a + (int) Math.round((capacity - a) * (double) i / n));
        return out;
    }

    /** The shelf's model state after each pop (empty → half → full as it fills). */
    public static List<String> fillStates(StoreSpec.Shelf s, int from, int pops) {
        List<String> out = new ArrayList<>();
        for (int stock : fillSteps(from, s.capacity(), pops)) out.add(s.state(stock));
        return out;
    }

    /** The things that pop onto a shelf: its goods (that the store sells) in turn, `pops` of them. */
    public static List<String> popItems(List<String> goods, int pops) {
        List<String> out = new ArrayList<>();
        if (goods.isEmpty()) return out;
        for (int i = 0; i < pops; i++) out.add(goods.get(i % goods.size()));
        return out;
    }

    // ------------------------------------------------------------------------------------------ 온수기: the water level

    public enum Level { FULL, LOW, EMPTY }

    /** The kettle's level: empty at 0, low at a third or less, else full. */
    public static Level level(int water, int capacity) {
        if (water <= 0) return Level.EMPTY;
        return water * 3 <= capacity ? Level.LOW : Level.FULL;
    }

    public static String kettleModel(Level l) {
        return switch (l) {
            case FULL -> "economy:store_kettle";
            case LOW -> "economy:store_kettle_low";
            case EMPTY -> "economy:store_kettle_empty";
        };
    }

    /** The water after each pour of the jug (`steps` of them, the last the capacity). */
    public static List<Integer> refillSteps(int from, int capacity, int steps) {
        return fillSteps(from, capacity, steps);
    }

    // ------------------------------------------------------------------------------------------ 전자레인지

    /** The microwave's model: the door open (food in or out), running (lit), done (띵), or shut. */
    public static String ovenModel(Chores.Oven oven, boolean doorOpen) {
        if (doorOpen) return "economy:store_microwave_open";
        return switch (oven) {
            case WARMING -> "economy:store_microwave_run";
            case DONE -> "economy:store_microwave_done";
            default -> "economy:store_microwave";
        };
    }

    // ------------------------------------------------------------------------------------------ 발주 tablet

    /**
     * The box kind an order group comes in (its page on the tablet, the box the truck brings): the kind most of its things
     * are stocked from (ties: the first met), null when none is (only the counter's or the freezer's).
     *
     * @param placeOf a product's place (snack · noodle · daily · meal · fridge · freezer · counter)
     * @param boxOf   a stocked place's box (snack → snack, fridge → drink …)
     */
    public static String kind(StoreLife.Group g, Function<String, String> placeOf, Map<String, String> boxOf) {
        if (g == null) return null;
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String p : sorted(g.products())) {
            String box = boxOf.get(placeOf.apply(p));
            if (box != null) n.merge(box, 1, Integer::sum);
        }
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : n.entrySet()) if (e.getValue() > most) {
            best = e.getKey();
            most = e.getValue();
        }
        return best;
    }

    /** A group's products in a fixed order (its set has none): by id. */
    private static List<String> sorted(Set<String> s) {
        List<String> out = new ArrayList<>(s);
        out.sort(null);
        return out;
    }

    /** The tablet's model: sent (the order is in), the page of a box kind, or the plain screen. */
    public static String tabletModel(String kind, boolean sent) {
        if (sent) return "economy:store_order_tablet_sent";
        return kind == null ? "economy:store_order_tablet" : "economy:store_order_tablet_" + kind;
    }

    // ------------------------------------------------------------------------------------------ what a worker holds

    /**
     * Who holds what in place of their scanner: the held thing, the inventory slot the scanner goes back to, and when it is
     * put back by itself (ms; 0 = only by an action). Holding something else while holding keeps the scanner's slot.
     */
    public static final class HeldBook {
        public record Held(String id, int slot, long until) {}

        private final Map<UUID, Held> held = new HashMap<>();

        /** `who` now holds `id` (the scanner was in `slot`); the thing held before (replaced), or null. */
        public Held hold(UUID who, String id, int slot, long until) {
            Held before = held.get(who);
            held.put(who, new Held(id, before != null ? before.slot() : slot, until));
            return before;
        }

        public Held get(UUID who) {
            return held.get(who);
        }

        /** What they hold, or null. */
        public String id(UUID who) {
            Held h = held.get(who);
            return h == null ? null : h.id();
        }

        public boolean holds(UUID who, String id) {
            return id != null && id.equals(id(who));
        }

        /** Someone holding this thing (any of them), or null. */
        public UUID who(String id) {
            for (Map.Entry<UUID, Held> e : held.entrySet()) if (e.getValue().id().equals(id)) return e.getKey();
            return null;
        }

        /** A later time to put it back by itself (the thing is still being used). */
        public void touch(UUID who, long until) {
            Held h = held.get(who);
            if (h != null) held.put(who, new Held(h.id(), h.slot(), until));
        }

        /** They put it down: what it was (the scanner goes back to its slot), or null when they held nothing. */
        public Held release(UUID who) {
            return held.remove(who);
        }

        /** Those whose thing goes back by itself now. */
        public List<UUID> due(long now) {
            List<UUID> out = new ArrayList<>();
            held.forEach((u, h) -> {
                if (h.until() > 0 && now >= h.until()) out.add(u);
            });
            return out;
        }

        public boolean isEmpty() {
            return held.isEmpty();
        }

        public void clear() {
            held.clear();
        }
    }
}
