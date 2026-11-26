package dev.stsv.economy.store;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 진상 응대 (README "편의점" 진상 손님, 기획안 §6 표): twelve kinds of trouble, each the bus's own people (bus.yml types), each
 * answered by one of 2~3 buttons over the customer. There is no one right answer for all: what happens depends on the
 * customer's mood and who else is in the store (the 건달 behaves when a 할머니 · 경찰 · 씨름 선수 is there). Pure (unit-tested):
 * {@link #resolve} says what a choice did; the module moves the people, the till and the pay; store.yml troubles.* has the
 * buttons' words and every line.
 */
public final class Trouble {

    private Trouble() {}

    /** When a trouble starts. */
    public enum Stage {
        /** while shopping (at a shelf) */
        BROWSE,
        /** as soon as they reach the counter (before anything is scanned) */
        COUNTER,
        /** when everything is scanned and they are to pay */
        PAY,
        /** paid: before they go */
        PAID,
        /** paid and gone to the eat bar */
        AFTER,
        /** no customer at all (the 새벽 door bell) */
        NONE
    }

    /**
     * The twelve (store.yml troubles.list.&lt;id&gt;): id, the bus kind it is, when, its choices (button keys), its lines (start
     * first, then every outcome's).
     */
    public enum Kind {
        BANMAL("banmal", "rude", Stage.COUNTER, List.of("ask", "scold", "guess"),
                List.of("start", "ask", "scold-ok", "scold-bad", "guess-ok", "guess-bad")),
        REFUND("refund", "complainer", Stage.COUNTER, List.of("refuse", "refund"),
                List.of("start", "refuse-ok", "refuse-bad", "refund")),
        CREDIT("credit", "thug", Stage.COUNTER, List.of("firm", "allow"),
                List.of("start", "behave", "firm-ok", "firm-bad", "allow")),
        STEAL("steal", "dodger", Stage.BROWSE, List.of(),
                List.of("start", "caught", "wrong")),
        DRUNK("drunk", "drunk", Stage.AFTER, List.of("wake", "hangover"),
                List.of("start", "wake-ok", "wake-bad", "hangover", "self")),
        KID("kid", "kid", Stage.PAY, List.of("discount", "refuse"),
                List.of("start", "discount", "refuse")),
        GRANDMA("grandma", "grandma", Stage.PAY, List.of("wait", "hurry"),
                List.of("start", "wait", "hurry")),
        TOURIST("tourist", "traveler", Stage.BROWSE, List.of("right", "wrong"),
                List.of("start", "right", "wrong", "self")),
        EXAM("exam", "univ", Stage.COUNTER, List.of("give", "decline"),
                List.of("start", "give", "none", "decline")),
        PARCEL("parcel", "resident", Stage.COUNTER, List.of("accept", "refuse"),
                List.of("start", "accept", "refuse")),
        LOTTERY("lottery", "uncle", Stage.PAID, List.of("cheer", "shrug"),
                List.of("start", "big", "small", "none", "cheer-big", "cheer-small", "cheer-none", "shrug-big", "shrug-small", "shrug-none")),
        GHOST("ghost", "ghost", Stage.NONE, List.of(), List.of("start"));

        public final String id, type;
        public final Stage stage;
        public final List<String> choices, talk;

        Kind(String id, String type, Stage stage, List<String> choices, List<String> talk) {
            this.id = id;
            this.type = type;
            this.stage = stage;
            this.choices = choices;
            this.talk = talk;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }

        /** The trouble a bus kind brings (null: none). */
        public static Kind forType(String type) {
            for (Kind k : values()) if (k.type.equals(type) && k.stage != Stage.NONE) return k;
            return null;
        }
    }

    /** What a choice leads to for the customer and the till. */
    public enum Result {
        /** on with the shopping / the till as usual */
        GO_ON,
        /** out without buying, counted as a customer lost (★1) */
        LEAVE_LOST,
        /** out without buying, but the worker did their part (★ by mood, not lost) */
        LEAVE_HANDLED,
        /** the worker gives it all on credit: the till is paid, the bill is the worker's loss */
        FREE,
        /** they pay the exact bill (in coins: no change to give) */
        EXACT,
        /** they put the last thing back and pay the exact rest */
        PUT_BACK
    }

    /**
     * What happened: the line they say (talk key), their mood ±, everyone else's mood ± (`others`), the result, what the worker
     * covers (원, taken from the pay at pay.overpay-rate), a bonus (G), their tip ×, the 복권 fanfare, one 폐기 given away, a
     * product handed to the worker as a gift (할머니's 사탕).
     */
    public record Outcome(String talk, double mood, double others, Result result, long won, long bonus, double tip, boolean fanfare,
                          boolean waste, String gift) {
        static Outcome of(String talk, double mood, Result result) {
            return new Outcome(talk, mood, 0, result, 0, 0, 1, false, false, null);
        }

        Outcome andBonus(long g) {
            return new Outcome(talk, mood, others, result, won, g, tip, fanfare, waste, gift);
        }

        Outcome andWon(long w) {
            return new Outcome(talk, mood, others, result, w, bonus, tip, fanfare, waste, gift);
        }

        Outcome andTip(double t) {
            return new Outcome(talk, mood, others, result, won, bonus, t, fanfare, waste, gift);
        }

        Outcome andOthers(double o) {
            return new Outcome(talk, mood, o, result, won, bonus, tip, fanfare, waste, gift);
        }

        Outcome andFanfare() {
            return new Outcome(talk, mood, others, result, won, bonus, tip, true, waste, gift);
        }

        Outcome andWaste() {
            return new Outcome(talk, mood, others, result, won, bonus, tip, fanfare, true, gift);
        }

        Outcome andGift(String g) {
            return new Outcome(talk, mood, others, result, won, bonus, tip, fanfare, waste, g);
        }
    }

    /**
     * The numbers (store.yml troubles): `good` G for a good answer, `catchBonus` for catching the 슬쩍 손님, `parcel` for taking a
     * parcel in, `hangover` for handing the 취객 a 숙취해소제, the 복권's `big` / `small` win chances.
     */
    public record Numbers(long good, long catchBonus, long parcel, long hangover, double big, double small, double ghost) {
        public static final Numbers DEFAULT = new Numbers(150, 300, 300, 400, 0.03, 0.2, 0.02);
    }

    /**
     * The situation a choice meets: the customer's mood now, whether a 할머니 · 경찰 · 씨름 선수 is in the store (`guardian`), a 폐기
     * waiting to go (`waste`), the bill (원), the price of the thing a 환불 손님 brings back (원), the 복권's prize (0 꽝, 1 small,
     * 2 big).
     */
    public record Context(double mood, boolean guardian, boolean waste, int bill, int refund, int prize) {}

    /** Whether this trouble does not happen at all now: the 건달 with a guardian in the store pays like anyone (says "behave"). */
    public static boolean skip(Kind k, Context c) {
        return k == Kind.CREDIT && c.guardian();
    }

    /** The 복권 scratched at the counter: 2 big win, 1 small, 0 꽝 (`roll` 0..1). */
    public static int scratch(Numbers n, double roll) {
        if (roll < n.big()) return 2;
        if (roll < n.big() + n.small()) return 1;
        return 0;
    }

    /** What a choice does (`roll` 0..1 for the guess). Null: no such choice for this trouble. */
    public static Outcome resolve(Kind k, String choice, Context c, Numbers n, double roll) {
        if (!k.choices.contains(choice)) return null;
        return switch (k) {
            case BANMAL -> switch (choice) {
                case "ask" -> Outcome.of("ask", 10, Result.GO_ON).andBonus(n.good());
                case "scold" -> c.mood() >= 60 ? Outcome.of("scold-ok", -5, Result.GO_ON) : Outcome.of("scold-bad", -30, Result.LEAVE_LOST);
                default -> roll < 0.5 ? Outcome.of("guess-ok", 0, Result.GO_ON) : Outcome.of("guess-bad", -20, Result.GO_ON);
            };
            case REFUND -> choice.equals("refund") ? Outcome.of("refund", 20, Result.LEAVE_HANDLED).andWon(c.refund())
                    : c.mood() >= 40 ? Outcome.of("refuse-ok", -10, Result.LEAVE_HANDLED).andBonus(n.good())
                    : Outcome.of("refuse-bad", -25, Result.LEAVE_HANDLED).andBonus(n.good()).andOthers(-5);
            case CREDIT -> choice.equals("allow") ? Outcome.of("allow", 20, Result.FREE).andWon(c.bill())
                    : c.mood() >= 40 ? Outcome.of("firm-ok", -10, Result.GO_ON).andBonus(n.good())
                    : Outcome.of("firm-bad", -20, Result.LEAVE_LOST).andOthers(3);
            case DRUNK -> choice.equals("hangover") ? Outcome.of("hangover", 25, Result.LEAVE_HANDLED).andBonus(n.hangover()).andTip(1.5)
                    : c.mood() >= 50 ? Outcome.of("wake-ok", 0, Result.LEAVE_HANDLED).andBonus(n.good())
                    : Outcome.of("wake-bad", -15, Result.LEAVE_HANDLED);
            case KID -> choice.equals("discount") ? Outcome.of("discount", 30, Result.EXACT).andWon(100).andBonus(n.good()).andTip(2)
                    : Outcome.of("refuse", -20, Result.PUT_BACK);
            case GRANDMA -> choice.equals("wait") ? Outcome.of("wait", 15, Result.EXACT).andBonus(n.good()).andGift("candy")
                    : Outcome.of("hurry", -20, Result.EXACT);
            case TOURIST -> choice.equals("right") ? Outcome.of("right", 15, Result.GO_ON).andBonus(n.good()).andTip(1.5)
                    : Outcome.of("wrong", -10, Result.GO_ON);
            case EXAM -> !choice.equals("give") ? Outcome.of("decline", -5, Result.GO_ON)
                    : c.waste() ? Outcome.of("give", 25, Result.GO_ON).andTip(1.5).andWaste() : Outcome.of("none", -5, Result.GO_ON);
            case PARCEL -> choice.equals("accept") ? Outcome.of("accept", 10, Result.GO_ON).andBonus(n.parcel()) : Outcome.of("refuse", -15, Result.GO_ON);
            case LOTTERY -> {
                String what = c.prize() >= 2 ? "big" : c.prize() == 1 ? "small" : "none";
                if (choice.equals("shrug")) yield Outcome.of("shrug-" + what, c.prize() >= 2 ? -5 : c.prize() == 1 ? 0 : -10, Result.GO_ON);
                if (c.prize() >= 2) yield Outcome.of("cheer-big", 20, Result.GO_ON).andTip(3).andOthers(8).andFanfare();
                yield c.prize() == 1 ? Outcome.of("cheer-small", 10, Result.GO_ON).andTip(1.5) : Outcome.of("cheer-none", 8, Result.GO_ON);
            }
            default -> null;
        };
    }

    /**
     * The 슬쩍 손님: the worker looked at someone and clicked them. A thief who has not been caught yet says "…계산할게요" and pays
     * for it (a bonus); anyone else is hurt by it.
     */
    public static Outcome accuse(boolean thief, Numbers n) {
        return thief ? Outcome.of("caught", -10, Result.GO_ON).andBonus(n.catchBonus()) : Outcome.of("wrong", -20, Result.GO_ON);
    }

    /** The 슬쩍 손님 got away with it: the worker covers the thing (원). */
    public static long escaped(int price) {
        return Math.max(0, price);
    }

    /** The 취객 woke up by himself (nobody saw to him): out, a little sour. */
    public static Outcome slept(Kind k) {
        return k == Kind.DRUNK ? Outcome.of("self", -10, Result.LEAVE_HANDLED) : Outcome.of("self", -5, Result.GO_ON);
    }

    /** Whether a kind brings its trouble this visit (`chance`, a roll 0..1). */
    public static boolean comes(double chance, double roll) {
        return roll < chance;
    }

    /** Two wrong places for the 관광객 (not `right`), from those the store has. */
    public static List<String> wrong(String right, List<String> places, RandomGenerator rnd) {
        List<String> others = new java.util.ArrayList<>(places);
        others.remove(right);
        java.util.Collections.shuffle(others, new java.util.Random(rnd.nextLong()));
        return others.subList(0, Math.min(1, others.size()));
    }
}
