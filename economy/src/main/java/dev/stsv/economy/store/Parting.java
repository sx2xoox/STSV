package dev.stsv.economy.store;

/**
 * How a customer who paid goes: by how their visit went, never against it. Someone handled badly (a "-bad" answer to their
 * trouble, a wrong accusation, an ID refused, short change, burnt or never warmed food, a wrong shelf for their usual, a
 * spoiled thing, no hot water, scolded in a scene) or leaving in a low mood (under ★3, the same steps the pay and the ★
 * use) does not thank anyone: one sour word at most, and no ★ or hearts over them. A middling mood: a plain word or
 * nothing. Only a good visit ends with the cheerful thanks. Pure (unit-tested); StoreModule.finish shows it.
 */
final class Parting {

    private Parting() {
    }

    /** The lines of store.yml (talk, and each customer's own): a sour parting word, a plain one. */
    static final String SOUR_LINES = "leave-sour", PLAIN_LINES = "leave-plain";
    /** A complaint said this short a while ago (ms) is their last word: nothing is said over it. */
    static final long JUST_SAID = 4000;

    enum Kind {
        /** the cheerful thanks, their ★, hearts when delighted */
        THANKS,
        /** a plain word or nothing; their ★ */
        PLAIN,
        /** one sour word, a small angry puff; no ★, no hearts */
        SOUR,
        /** they have just said what they think: nothing more (the puff only) */
        SILENT
    }

    /**
     * badly: something went wrong for them that was the worker's doing; stars: their ★ by mood as they leave (1..5);
     * sinceComplaint: ms since their last sour line (a complaint, a "-bad" line), negative when they said none.
     */
    static Kind kind(boolean badly, int stars, long sinceComplaint) {
        if (badly || stars < 3) return sinceComplaint >= 0 && sinceComplaint < JUST_SAID ? Kind.SILENT : Kind.SOUR;
        return stars == 3 ? Kind.PLAIN : Kind.THANKS;
    }

    /** Whether their ★ floats over them as they go. */
    static boolean stars(Kind k) {
        return k == Kind.THANKS || k == Kind.PLAIN;
    }

    /** Whether hearts rise (delighted: the mood the bus uses too). */
    static boolean hearts(Kind k, double mood) {
        return k == Kind.THANKS && mood >= 90;
    }

    /** Whether the small angry puff shows. */
    static boolean puff(Kind k) {
        return k == Kind.SOUR || k == Kind.SILENT;
    }

    /** Whether an answer's line is a bad end for the customer (rules: "scold-bad", "refuse-bad", "firm-bad", "wake-bad", …). */
    static boolean bad(String talk, double mood) {
        return talk != null && talk.endsWith("-bad") || mood <= -15;
    }
}
