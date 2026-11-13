package dev.stsv.core.emote;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Player skins by their texture address, fetched off the server thread and kept in memory. Only the game's own
 * texture host is asked (a profile's texture address comes from the client's login data: anything else is
 * refused), with a short timeout and a size limit; a skin that cannot be had is {@link Optional#empty()} (the
 * puppet then wears {@link PuppetSkin#placeholder()}) and is tried again after {@link #RETRY_MILLIS}.
 */
public final class SkinCache implements AutoCloseable {

    static final String HOST = "textures.minecraft.net";
    static final int MAX_BYTES = 256 * 1024;
    static final int KEPT = 128;
    static final long RETRY_MILLIS = 60_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, PuppetSkin> skins = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PuppetSkin> eldest) {
            return size() > KEPT;
        }
    });
    private final Map<String, Long> failed = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<PuppetSkin>>> loading = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The address to fetch for a profile's skin address, or null when it is not one of the game's textures
     * (always over https, whatever the profile says).
     */
    static URI source(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (!HOST.equalsIgnoreCase(u.getHost()) || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 80 && u.getPort() != 443)) return null;
            String path = u.getPath();
            if (path == null || !path.matches("/texture/[0-9a-fA-F]{16,80}")) return null;
            return new URI("https", HOST, path, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return null;
        }
    }

    /** A downloaded skin picture: a PNG of 64 x 64 or 64 x 32. Anything else: empty. */
    static Optional<PuppetSkin> decode(byte[] png, boolean slim) {
        if (png == null || png.length == 0 || png.length > MAX_BYTES) return Optional.empty();
        try {
            BufferedImage im = ImageIO.read(new ByteArrayInputStream(png));
            return im == null ? Optional.empty() : Optional.of(PuppetSkin.of(im, slim));
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The skin if it is already here (no fetch). */
    public Optional<PuppetSkin> cached(String url, boolean slim) {
        URI source = source(url);
        return source == null ? Optional.empty() : Optional.ofNullable(skins.get(key(source, slim)));
    }

    /**
     * The skin of a profile's skin address, from memory or the texture host. Completes on any thread, never
     * exceptionally; empty when the address is not the game's, the fetch failed or the picture is no skin.
     */
    public CompletableFuture<Optional<PuppetSkin>> skin(String url, boolean slim) {
        URI source = source(url);
        if (source == null) return CompletableFuture.completedFuture(Optional.empty());
        String key = key(source, slim);
        PuppetSkin have = skins.get(key);
        if (have != null) return CompletableFuture.completedFuture(Optional.of(have));
        Long failedAt = failed.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MILLIS) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<PuppetSkin>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<PuppetSkin>> running = loading.putIfAbsent(key, mine);
        if (running != null) return running;
        CompletableFuture<Optional<PuppetSkin>> fetched;
        try {
            HttpRequest request = HttpRequest.newBuilder(source).timeout(TIMEOUT).GET().build();
            fetched = http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES))
                    .thenApply(r -> r.statusCode() == 200 ? decode(r.body(), slim) : Optional.<PuppetSkin>empty())
                    .exceptionally(t -> Optional.empty());
        } catch (RuntimeException e) {
            fetched = CompletableFuture.completedFuture(Optional.empty());
        }
        fetched.thenAccept(skin -> {
            if (skin.isPresent()) {
                skins.put(key, skin.get());
                failed.remove(key);
            } else {
                failed.put(key, System.currentTimeMillis());
            }
            loading.remove(key);
            mine.complete(skin);
        });
        return mine;
    }

    private static String key(URI source, boolean slim) {
        return source.getPath() + (slim ? "|slim" : "|classic");
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
