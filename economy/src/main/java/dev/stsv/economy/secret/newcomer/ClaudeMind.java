package dev.stsv.economy.secret.newcomer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * His words from the model (the official Anthropic Java SDK; the server downloads it at startup: plugin.yml "libraries").
 * The key is the server's environment variable ANTHROPIC_API_KEY, read by the SDK itself: it is never read from a file and
 * never logged. Thinking is left to the model (adaptive: no thinking parameter), the effort is low (these are one-line
 * answers), the persona and rules are a cached system prompt, a declined request is retried on the server's own fallback
 * model, and a reply is only used when it ended normally (a refusal or a cut-off reply is no reply: the scripted line is
 * said). Every call runs on its own small pool, never on the server thread.
 */
final class ClaudeMind implements NewcomerMind {

    static final String DEFAULT_MODEL = "claude-opus-5-5";
    /** Server-side refusal fallbacks (fallbacks: "default"). */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final Consumer<String> problem;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "stsv-newcomer-mind");
        t.setDaemon(true);
        return t;
    });

    /** problem: told why a call gave nothing (for the admin's 상태; never anything secret). */
    ClaudeMind(String model, long maxTokens, Duration timeout, Consumer<String> problem) {
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.maxTokens = Math.max(1024, maxTokens);
        this.problem = problem;
        // credentials from the environment only; no retries (a late line is no use: the scripted one is said instead)
        this.client = AnthropicOkHttpClient.builder().fromEnv().maxRetries(0).timeout(timeout).build();
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
        return CompletableFuture.supplyAsync(() -> call(ask), pool)
                .orTimeout(Math.max(200, timeout.toMillis()), TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    problem.accept(c.getClass().getSimpleName() + (c instanceof java.util.concurrent.TimeoutException ? " (시간 안에 답이 없음)" : ""));
                    return null;
                });
    }

    private String call(Ask ask) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(Prompts.SYSTEM)
                        .cacheControl(BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build())
                        .build()))
                .addUserMessage(Prompts.user(ask))
                .build();
        BetaMessage reply = client.beta().messages().create(params);
        BetaStopReason stop = reply.stopReason().orElse(null);
        if (!BetaStopReason.END_TURN.equals(stop)) {   // refusal, max_tokens, anything else: no line
            problem.accept("stop_reason " + (stop == null ? "없음" : stop.toString()));
            return null;
        }
        StringBuilder b = new StringBuilder();
        reply.content().forEach(block -> block.text().ifPresent(t -> b.append(t.text())));
        String text = b.toString().strip();
        return text.isEmpty() ? null : text;
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // nothing to let go of
        }
    }
}
