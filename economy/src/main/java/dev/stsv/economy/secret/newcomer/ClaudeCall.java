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
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The ONLY class that touches the Anthropic Java SDK. It is never loaded by the plugin's own class loader: {@link Sdk}
 * defines it in a class loader of its own whose parent holds the SDK's jars (downloaded fail-soft at runtime), so a server
 * that cannot get the library loses the model and nothing else (see {@code SdkIsolationTest}). For that reason it may use
 * nothing of the plugin: only the JDK and the SDK, through public members (it lives in another runtime package).
 * <p>
 * One call: the persona (system, cached for an hour), one user message, effort low, thinking left to the model (adaptive:
 * no thinking parameter on Opus 5.5), the server-side refusal fallback ({@code fallbacks: "default"}), no retries, the
 * client's timeout. The text comes back only when the reply ended normally ({@code end_turn}); a refusal, a reply cut at
 * max_tokens or anything else is no reply (null), with the reason told to {@code problem} (never the reply itself).
 */
public final class ClaudeCall implements BiFunction<String, String, String>, AutoCloseable {

    /** Server-side refusal fallbacks (fallbacks: "default"). */
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final Consumer<String> problem;

    /** The credential from the environment (ANTHROPIC_API_KEY, read by the SDK itself; never logged). */
    public ClaudeCall(String model, long maxTokens, Duration timeout, Consumer<String> problem) {
        this(model, maxTokens, timeout, problem, null);
    }

    /** key: the credential read from the server's key file (ClaudeMind.keyFile), or null = the environment. Never logged. */
    public ClaudeCall(String model, long maxTokens, Duration timeout, Consumer<String> problem, String key) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.problem = problem;
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (key != null && !key.isBlank()) b.apiKey(key.strip());
        else b.fromEnv();
        this.client = b.maxRetries(0).timeout(timeout).build();
    }

    /** system: the persona (stable, cached); user: everything that changes. The reply's text, or null. */
    @Override
    public String apply(String system, String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(system)
                        .cacheControl(BetaCacheControlEphemeral.builder().ttl(BetaCacheControlEphemeral.Ttl.TTL_1H).build())
                        .build()))
                .addUserMessage(user)
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
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // nothing to let go of
        }
    }
}
