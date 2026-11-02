package dev.stsv.core.pack;

import com.sun.net.httpserver.HttpServer;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Builds the resource pack from content packs, hosts it with a tiny built-in HTTP server and sends it to players.
 */
public final class PackModule implements StsvModule, Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("stsv-core-pack".getBytes(StandardCharsets.UTF_8));

    private STSVPlugin plugin;
    private HttpServer http;
    private volatile PackBuilder.Built current;
    private String hostedBind;
    private int hostedPort = -1;

    @Override
    public String id() {
        return "resource-pack";
    }

    @Override
    public void enable(STSVPlugin plugin) throws IOException {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        rebuild();
    }

    @Override
    public void reload() throws IOException {
        rebuild();
        Bukkit.getOnlinePlayers().forEach(this::send);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        stopHttp();
    }

    public PackBuilder.Built current() {
        return current;
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("resource-pack");
    }

    public void rebuild() throws IOException {
        ConfigurationSection cfg = cfg();
        PackBuilder.Built built = new PackBuilder().build(
                plugin.content().packs(), plugin.content().items(),
                cfg.getInt("pack-format", 97), "STSV server resources");
        built.warnings().forEach(w -> plugin.getLogger().warning("[리소스팩] " + w));

        Path out = plugin.getDataFolder().toPath().resolve("generated").resolve("stsv-pack.zip");
        Files.createDirectories(out.getParent());
        Files.write(out, built.zip());
        current = built;
        plugin.getLogger().info("리소스팩 빌드 완료: 파일 " + built.files() + "개, "
                + (built.zip().length / 1024) + "KB, sha1=" + built.sha1());

        ConfigurationSection host = cfg.getConfigurationSection("host");
        if (cfg.getBoolean("enabled", true) && host != null && host.getBoolean("enabled", true)) {
            startHttp(host.getString("bind", "0.0.0.0"), host.getInt("port", 8163));
        } else {
            stopHttp();
        }
    }

    private void startHttp(String bind, int port) throws IOException {
        if (http != null && bind.equals(hostedBind) && port == hostedPort) return;
        stopHttp();
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/", exchange -> {
            PackBuilder.Built pack = current;
            try (exchange) {
                if (pack == null || !"GET".equals(exchange.getRequestMethod())
                        || !exchange.getRequestURI().getPath().startsWith("/pack/")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, pack.zip().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(pack.zip());
                }
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "STSV-PackHost");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        hostedBind = bind;
        hostedPort = port;
        plugin.getLogger().info("리소스팩 호스팅 시작: " + bind + ":" + port);
    }

    private void stopHttp() {
        if (http != null) {
            http.stop(0);
            http = null;
            hostedPort = -1;
        }
    }

    /** URL players download from. The hash in the path busts client caches when content changes. */
    private URI url() {
        ConfigurationSection cfg = cfg();
        PackBuilder.Built pack = current;
        if (cfg.getBoolean("host.enabled", true)) {
            String base = cfg.getString("host.public-url", "http://localhost:8163").replaceAll("/+$", "");
            return URI.create(base + "/pack/" + pack.sha1() + ".zip");
        }
        String external = cfg.getString("external-url", "");
        return external.isBlank() ? null : URI.create(external);
    }

    public void send(Player player) {
        if (!cfg().getBoolean("enabled", true) || current == null) return;
        URI uri = url();
        if (uri == null) {
            plugin.getLogger().warning("리소스팩 주소가 없어 전송하지 않음 (resource-pack.external-url 설정 필요)");
            return;
        }
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, uri, current.sha1());
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .replace(true)
                .required(cfg().getBoolean("required", true))
                .prompt(Text.mm(cfg().getString("prompt", "")))
                .build());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        send(e.getPlayer());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        switch (e.getStatus()) {
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> plugin.getLogger().warning(
                    e.getPlayer().getName() + " 리소스팩 적용 실패: " + e.getStatus()
                            + " (public-url이 외부에서 접속 가능한 주소인지 확인)");
            default -> {}
        }
    }
}
