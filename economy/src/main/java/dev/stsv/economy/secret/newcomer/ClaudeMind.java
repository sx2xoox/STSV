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

    /** The key file's name in the plugin's data folder (plugins/<경제 플러그인>/anthropic.key): for hosts where no environment variable can be set. */
    static final String KEY_FILE = "anthropic.key";

    /**
     * The key in the server's key file, or null: the first line that is not blank and not a # comment, stripped.
     * The file lives only on the server (never in the repository, never in the content pack); its value is never logged.
     */
    /** Puts an empty key file (comment lines only) in the data folder when there is none, so the owner finds where the key goes. */
    static void keyTemplate(java.nio.file.Path dataFolder) {
        if (dataFolder == null) return;
        try {
            java.nio.file.Path f = dataFolder.resolve(KEY_FILE);
            if (java.nio.file.Files.exists(f)) return;
            java.nio.file.Files.createDirectories(dataFolder);
            java.nio.file.Files.writeString(f, "# 이 줄 아래에 API 키를 한 줄로 붙여 넣고 저장한 뒤 서버를 다시 시작하세요.\n"
                    + "# (# 으로 시작하는 줄과 빈 줄은 읽지 않습니다. 이 파일은 남에게 주지 마세요.)\n", java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // no template: the path is still told in the console and in 상태
        }
    }

    static String keyFile(java.nio.file.Path dataFolder) {
        if (dataFolder == null) return null;
        try {
            java.nio.file.Path f = dataFolder.resolve(KEY_FILE);
            if (!java.nio.file.Files.isRegularFile(f)) return null;
            for (String line : java.nio.file.Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("\uFEFF")) t = t.substring(1).strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                return t;
            }
        } catch (Exception e) {
            return null;   // unreadable = no key (the reason is not worth a secret's worth of logging)
        }
        return null;
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
