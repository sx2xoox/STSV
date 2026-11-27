package dev.stsv.economy.secret.newcomer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * His words from the model. The call itself is {@link ClaudeCall} (the one class that touches the SDK, loaded apart by
 * {@link Sdk}); this side never names an SDK type, so the plugin loads and runs without the library. Every call runs on a
 * small pool of its own, never on the server thread, and is given up at its deadline: a call still waiting in the pool
 * when its line is already due is not sent at all (no tokens for a line nobody will read). The key is the server's
 * environment variable ANTHROPIC_API_KEY, read by the SDK itself: never read from a file, never logged.
 */
final class ClaudeMind implements NewcomerMind {

    static final String DEFAULT_MODEL = "claude-opus-5-5";

    private final BiFunction<String, String, String> call;
    private final AutoCloseable closer;
    private final Consumer<String> problem;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "stsv-newcomer-mind");
        t.setDaemon(true);
        return t;
    });

    /** call: (system, user) → the reply's text or null; problem: told why a call gave nothing (never anything secret). */
    ClaudeMind(BiFunction<String, String, String> call, AutoCloseable closer, Consumer<String> problem) {
        this.call = call;
        this.closer = closer;
        this.problem = problem;
    }

    /** Whether the server has a credential for the API in its environment (its value is not looked at beyond "is it there"). */
    static boolean credential() {
        return present(System.getenv("ANTHROPIC_API_KEY")) || present(System.getenv("ANTHROPIC_AUTH_TOKEN"));
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public CompletableFuture<String> think(Ask ask, Duration timeout) {
        long wait = Math.max(200, timeout.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
        String user = Prompts.user(ask);   // built here, on the caller's thread: the pool sees only two strings
        return CompletableFuture.supplyAsync(() -> {
                    // still queued when the line is due: not sent (the scripted line was said)
                    if (System.nanoTime() - deadline > -TimeUnit.MILLISECONDS.toNanos(300)) {
                        problem.accept("늦음 (보내지 않음)");
                        return null;
                    }
                    return call.apply(Prompts.SYSTEM, user);
                }, pool)
                .orTimeout(wait, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    problem.accept(c.getClass().getSimpleName() + (c instanceof java.util.concurrent.TimeoutException ? " (시간 안에 답이 없음)" : ""));
                    return null;
                });
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            closer.close();
        } catch (Exception ignored) {
            // nothing to let go of
        }
    }
}
