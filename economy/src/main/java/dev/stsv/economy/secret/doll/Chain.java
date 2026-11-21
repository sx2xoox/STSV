package dev.stsv.economy.secret.doll;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The doll's journey (pure; tested by DollChainTest): the first owner (who bought it) is hand 1; each riddle names hand
 * 2 … 7; the seventh riddle sends it back to the first owner. A pass to anyone the riddle did not name is a wrong hand:
 * it is written down crossed out, that player now holds it, and the same riddle stands. Only the first owner can finish.
 * <p>
 * Saved as one string (eco.kv "secret.doll.chain").
 */
final class Chain {

    /** Hands in a full run, the first owner included. */
    static final int HANDS = 7;

    enum Phase {
        /** On its way: hands 1 … 7 (and back to the first owner after the seventh). */
        CHAIN,
        /** Back with the first owner after seven hands: take it to where it was born and answer. */
        HOME
    }

    /** One time it changed hands. ok = a hand that counts; else a wrong one (crossed out). */
    record Hand(UUID id, String name, boolean ok) {}

    enum Result {
        /** Nothing changed (the same holder). */
        NONE,
        /** A new hand counted. */
        NEXT,
        /** The wrong person: crossed out, the riddle stands. */
        WRONG,
        /** Back with the first owner after the seventh hand. */
        HOME
    }

    final UUID owner;
    UUID holder;
    Phase phase = Phase.CHAIN;
    final List<Hand> trail = new ArrayList<>();
    /** Names of those who held it when it went home on its own (earlier runs). */
    final List<String> lost = new ArrayList<>();
    /** Riddles already asked in this run. */
    final Set<Riddles.Kind> used = EnumSet.noneOf(Riddles.Kind.class);
    /** The riddle standing now (null until told). */
    Riddles.Kind riddle;
    /** Who answered it at each telling (the moment it spoke). */
    final Set<UUID> snapshot = new LinkedHashSet<>();

    Chain(UUID owner, String ownerName) {
        this.owner = owner;
        this.holder = owner;
        trail.add(new Hand(owner, ownerName, true));
    }

    /** The hands that count, in order (the first owner first). */
    List<Hand> hands() {
        List<Hand> out = new ArrayList<>();
        for (Hand h : trail) if (h.ok()) out.add(h);
        return out;
    }

    /** Whether this player is one of the hands that count. */
    boolean held(UUID u) {
        for (Hand h : trail) if (h.ok() && h.id().equals(u)) return true;
        return false;
    }

    /** The number of the riddle standing now: 1 … 7 (= hands so far). */
    int index() {
        return hands().size();
    }

    /** Whether the next riddle is the last one: back to the first owner. */
    boolean last() {
        return index() >= HANDS;
    }

    /**
     * It is now in this player's hands. matches = whether the riddle standing named them (the caller decides, from the
     * riddle; ignored for the last one, which only the first owner answers).
     */
    Result pass(UUID to, String name, boolean matches) {
        if (to.equals(holder)) return Result.NONE;
        if (phase == Phase.HOME) {
            // it left the first owner after the seventh hand: it has to come back to them again
            phase = Phase.CHAIN;
            return wrong(to, name);
        }
        if (last()) {
            if (!to.equals(owner)) return wrong(to, name);
            holder = to;
            phase = Phase.HOME;
            riddle = null;
            snapshot.clear();
            return Result.HOME;
        }
        if (!matches) return wrong(to, name);
        trail.add(new Hand(to, name, true));
        holder = to;
        if (riddle != null) used.add(riddle);
        riddle = null;
        snapshot.clear();
        return Result.NEXT;
    }

    private Result wrong(UUID to, String name) {
        trail.add(new Hand(to, name, false));
        holder = to;
        return Result.WRONG;
    }

    /** It went home to the first owner on its own (too slow) or the answer was lost: the run starts again at hand 1. */
    void restart(String lostName) {
        if (lostName != null && !lostName.isBlank()) lost.add(lostName);
        Hand first = trail.getFirst();
        trail.clear();
        trail.add(first);
        holder = owner;
        phase = Phase.CHAIN;
        used.clear();
        riddle = null;
        snapshot.clear();
    }

    /** The final answer: the first letter of each counting hand's name, in order. */
    String answer() {
        StringBuilder b = new StringBuilder();
        for (Hand h : hands()) {
            String n = h.name() == null ? "" : h.name().strip();
            if (!n.isEmpty()) b.appendCodePoint(n.codePointAt(0));
        }
        return b.toString();
    }

    /** Whether what they typed is the answer (spaces, punctuation and letter case do not matter). */
    static boolean check(String typed, String answer) {
        String t = normalize(typed);
        return !t.isEmpty() && t.equals(normalize(answer));
    }

    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.strip().codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp) || ",.-·/|'\"…~_>→:;!?()[]".indexOf(cp) >= 0) return;
            b.appendCodePoint(cp);
        });
        return b.toString().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------
    // saved form
    // ------------------------------------------------------------------------------------------

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    String encode() {
        List<String> t = new ArrayList<>();
        for (Hand h : trail) t.add(h.id() + ":" + (h.ok() ? 1 : 0) + ":" + b64(h.name()));
        List<String> l = new ArrayList<>();
        for (String n : lost) l.add(b64(n));
        List<String> u = new ArrayList<>();
        for (Riddles.Kind k : used) u.add(k.name());
        List<String> s = new ArrayList<>();
        for (UUID x : snapshot) s.add(x.toString());
        return String.join(";", owner.toString(), holder.toString(), phase.name(), String.join(",", t), String.join(",", l),
                String.join(",", u), riddle == null ? "-" : riddle.name(), String.join(",", s));
    }

    /** Reads a saved chain, or null when there is none (or it is unreadable). */
    static Chain decode(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String[] w = s.split(";", -1);
            UUID owner = UUID.fromString(w[0]);
            List<Hand> trail = new ArrayList<>();
            for (String part : w[3].split(",")) {
                if (part.isEmpty()) continue;
                String[] h = part.split(":", -1);
                trail.add(new Hand(UUID.fromString(h[0]), unb64(h[2]), h[1].equals("1")));
            }
            if (trail.isEmpty() || !trail.getFirst().id().equals(owner)) return null;
            Chain c = new Chain(owner, trail.getFirst().name());
            c.trail.clear();
            c.trail.addAll(trail);
            c.holder = UUID.fromString(w[1]);
            c.phase = Phase.valueOf(w[2]);
            for (String n : w[4].split(",")) if (!n.isEmpty()) c.lost.add(unb64(n));
            for (String k : w[5].split(",")) {
                Riddles.Kind kind = Riddles.Kind.of(k);
                if (kind != null) c.used.add(kind);
            }
            c.riddle = Riddles.Kind.of(w[6]);
            for (String x : w[7].split(",")) if (!x.isEmpty()) c.snapshot.add(UUID.fromString(x));
            return c;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
