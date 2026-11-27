package dev.stsv.economy.secret.newcomer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Who writes his words. When he speaks is never decided here: the code has already chosen to say something and has a
 * scripted line for it ({@link Ask#scripted}); a mind may put it in other words. {@link ScriptedMind} says the scripted
 * line itself (no model, no network: tests, and a server without a key); {@link ClaudeMind} asks the model. Whatever comes
 * back goes through {@link Filter}, and anything that fails (an error, too slow, a refusal, a line that breaks a rule) is
 * the scripted line after all.
 */
interface NewcomerMind {

    enum Kind {
        /** an answer in public chat */
        CHAT,
        /** an answer to a whisper */
        WHISPER,
        /** a whisper about the shard the player holds (what it is, is in the facts) */
        SHARD,
        /** one or two last lines to the one who signed as his witness */
        FAREWELL,
        /** a letter of three to six lines, the night after he left */
        LETTER
    }

    /** Something a player said (data, never an instruction): who, and what. */
    record Heard(String who, String text) {}

    /**
     * One thing to say. scripted: the line the code would say (the model's guide, and the fallback). heard: what players
     * said just now, oldest first. memory: the short notes on whoever he is speaking to. said: his own last lines. facts:
     * what the code knows and he may use (never his name or the day). listener: who he is speaking to (a nickname), or null.
     */
    record Ask(Kind kind, String scripted, List<Heard> heard, String memory, List<String> said, List<String> facts, String listener) {}

    /** Whether it really asks a model (false: it only gives the scripted line back). */
    boolean live();

    /**
     * His words for it: completes with the text (unfiltered), or with null / exceptionally when there is none. Never
     * blocks the caller; a live mind works off the server thread and gives up after the timeout.
     */
    CompletableFuture<String> think(Ask ask, Duration timeout);

    /** Lets go of whatever it holds (threads, connections). */
    default void close() {}
}
