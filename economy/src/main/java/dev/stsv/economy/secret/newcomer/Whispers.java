package dev.stsv.economy.secret.newcomer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The game's private-message commands as a player typed them: "/w name words", "/minecraft:tell NAME words". Only a whole
 * whisper is read (a target and words); anything else is left to the game, which answers it as it always does.
 */
final class Whispers {

    /** The game's own labels (each also with "minecraft:" in front). */
    static final List<String> VANILLA = List.of("w", "msg", "tell");

    private Whispers() {}

    /** A whisper: who it is for (as typed) and what it says (never blank). */
    record Whisper(String target, String message) {}

    /**
     * Reads a command line; empty when it is not a whisper command, or has no target or no words. A name in quotes
     * ("name" or 'name', which the game reads as the name) is given without them.
     */
    static Optional<Whisper> parse(String line, Collection<String> labels) {
        if (line == null) return Optional.empty();
        String s = line.startsWith("/") ? line.substring(1) : line;
        int a = s.indexOf(' ');
        if (a <= 0) return Optional.empty();
        String label = s.substring(0, a).toLowerCase(Locale.ROOT);
        if (label.startsWith("minecraft:")) label = label.substring("minecraft:".length());
        if (!labels.contains(label)) return Optional.empty();
        int b = s.indexOf(' ', a + 1);
        if (b < 0 || b == a + 1) return Optional.empty();   // no words, or two spaces where the name should be
        String target = s.substring(a + 1, b), message = s.substring(b + 1).strip();
        if (message.isEmpty()) return Optional.empty();
        char q = target.charAt(0);
        if ((q == '"' || q == '\'') && target.length() > 2 && target.charAt(target.length() - 1) == q) target = target.substring(1, target.length() - 1);
        return Optional.of(new Whisper(target, message));
    }

    /** Whether a whisper is for this name (the game matches names in any case). */
    static boolean to(Whisper w, String name) {
        return name != null && w.target().equalsIgnoreCase(name);
    }

    /** A name the game could have: 3 to 16 of letters, digits and "_". */
    static boolean account(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }
}
