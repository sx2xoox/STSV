package dev.stsv.core.vehicle;

/**
 * The 지게's rules ({@link JigeModule}). Pure (unit-tested).
 *
 * <ul>
 *   <li><b>Wearing</b>: it is worn in the chestplate slot (so no elytra and no chest armour meanwhile). Putting one on
 *       claims it ({@link JigeStore#claim}: its token changes), and only the wearer of the claimed one opens its load.</li>
 *   <li><b>Taking it off</b>: always allowed, full or not. The load is not in the item: it stays in the database with the
 *       지게's id and is there again when the same 지게 is put on (by anyone who has it). Refusing to take off a loaded
 *       지게 would need every way an armour slot can change caught (clicks, shift-clicks, number keys, swapping by
 *       right-click, dispensers, commands, other plugins, death): one missed and the rule breaks. With the load bound to
 *       the id nothing depends on that, and a copy of the item opens nothing ({@link #worn}, the token).</li>
 *   <li><b>One holder</b>: a 지게 claimed by a player on this server cannot be claimed by anyone else (another copy) until
 *       they take it off: two people never have the same load open.</li>
 *   <li><b>Death</b>: every 지게 item is kept (never dropped): no 지게 lies on the ground for someone else to put on and
 *       empty, none is lost in lava or the void with its load. Nothing is copied (the item stays in its slot).</li>
 * </ul>
 */
public final class JigeRules {

    private JigeRules() {}

    /** What the chest slot means now. */
    public enum Worn {
        /** no 지게 in the chest slot */
        NONE,
        /** a 지게 never worn (an admin-made item: no id yet): a new, empty one is made for it */
        NEW,
        /** a 지게 this player does not hold yet (just put on, or another copy): claim it */
        CLAIM,
        /** the 지게 they hold: nothing to do */
        SAME
    }

    /**
     * @param jige          the chest slot holds a 지게 item
     * @param id            its id (0 = none yet)
     * @param token         its token
     * @param sessionId     the 지게 this player holds now (0 = none)
     * @param sessionToken  its token
     */
    public static Worn worn(boolean jige, long id, long token, long sessionId, long sessionToken) {
        if (!jige) return Worn.NONE;
        if (id <= 0) return Worn.NEW;
        return id == sessionId && token == sessionToken ? Worn.SAME : Worn.CLAIM;
    }

    /** May this player claim that 지게 now? Not while another player holds it (a copy put on twice). */
    public static boolean mayClaim(java.util.UUID player, java.util.UUID holder) {
        return holder == null || holder.equals(player);
    }

    /** Why it opens or not. */
    public enum Open { OK, NOT_WORN, LOADING, STALE }

    /**
     * @param worn    a 지게 is in the chest slot
     * @param held    this player holds it (claimed: a live session)
     * @param pending its claim is on the way to the database
     */
    public static Open open(boolean worn, boolean held, boolean pending) {
        if (!worn) return Open.NOT_WORN;
        if (held) return Open.OK;
        return pending ? Open.LOADING : Open.STALE;
    }

    /**
     * The click that opens it (besides /지게): sneaking, the main hand empty, a right click in the air or at a block that
     * does nothing when clicked (a door, a chest, a lever open as usual).
     */
    public static boolean openClick(boolean sneaking, boolean mainHandEmpty, boolean rightClick, boolean usefulBlock) {
        return sneaking && mainHandEmpty && rightClick && !usefulBlock;
    }

    /** Taking it off: always (the load stays with its id). */
    public static boolean mayTakeOff(int loadUsed) {
        return true;
    }

    /** On death a 지게 item is kept, worn or carried (whatever the keepInventory rule). */
    public static boolean keptOnDeath(boolean isJige) {
        return isJige;
    }
}
