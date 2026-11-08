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
 * Builds the resource pack from content packs, hosts it and sends it to players.
 *
 * Hosting (resource-pack.host.mode):
 *  - same-port: on the Minecraft port itself (SamePortHost), at the address the player connected to,
 *    so nothing has to be opened or configured. Falls back to separate-port if the server lacks the hook.
 *  - separate-port: a tiny HTTP server on host.port.
 * host.public-url overrides the address (needed only when the connect address does not reach the
 * server directly, e.g. a domain whose SRV record points to another port, or port forwarding to a
 * different port).
 */
public final class PackModule implements StsvModule, Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("stsv-core-pack".getBytes(StandardCharsets.UTF_8));

    private STSVPlugin plugin;
    private HttpServer http;
    private volatile PackBuilder.Built current;
    private String hostedBind;
    private int hostedPort = -1;
    private SamePortHost samePort;
    private volatile boolean samePortActive;

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
        stopSamePort();
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
        // furniture click boxes and barriers are fitted to these models (computed again, per model, from now on)
        if (plugin.furniture() != null) plugin.furniture().modelsChanged();
        plugin.getLogger().info("리소스팩 빌드 완료: 파일 " + built.files() + "개, "
                + (built.zip().length / 1024) + "KB, sha1=" + built.sha1());

        ConfigurationSection host = cfg.getConfigurationSection("host");
        boolean hosting = cfg.getBoolean("enabled", true) && host != null && host.getBoolean("enabled", true);
        if (hosting && "same-port".equalsIgnoreCase(host.getString("mode")) && startSamePort()) {
            stopHttp();
        } else {
            stopSamePort();
            if (hosting) startHttp(host.getString("bind", "0.0.0.0"), host.getInt("port", 8163));
            else stopHttp();
        }
    }

    private boolean startSamePort() {
        if (samePortActive) return true;
        try {
            if (samePort == null) samePort = new SamePortHost(() -> current, plugin.getLogger());
            samePort.install();
            samePortActive = true;
            plugin.getLogger().info("리소스팩 호스팅 시작: 마인크래프트 포트(" + Bukkit.getPort() + ")로 함께 배포");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("마인크래프트 포트로 배포할 수 없어 별도 포트(resource-pack.host.port)로 배포합니다: " + t);
            return false;
        }
    }

    private void stopSamePort() {
        if (samePort != null && samePortActive) samePort.uninstall();
        samePortActive = false;
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

    /** URL the player downloads from. The hash in the path busts client caches when content changes. */
    URI url(Player player) {
        ConfigurationSection cfg = cfg();
        PackBuilder.Built pack = current;
        if (cfg.getBoolean("host.enabled", true)) {
            String configured = cfg.getString("host.public-url", "").trim().replaceAll("/+$", "");
            String base;
            if (!configured.isEmpty() && !isLocal(configured)) {
                base = configured;
            } else {
                int port = samePortActive ? connectPort(player) : cfg.getInt("host.port", 8163);
                base = "http://" + connectHost(player) + ":" + port;
            }
            return URI.create(base + "/pack/" + pack.sha1() + ".zip");
        }
        String external = cfg.getString("external-url", "");
        return external.isBlank() ? null : URI.create(external);
    }

    /** localhost never reaches another computer: treated as "not set" (it was the old default). */
    static boolean isLocal(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null || h.equalsIgnoreCase("localhost") || h.startsWith("127.") || h.equals("[::1]") || h.equals("::1");
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** The host name or address the player typed to connect (it reaches this server from their side). */
    static String connectHost(Player p) {
        InetSocketAddress v = p.getVirtualHost();
        String h = v == null ? null : v.getHostString();
        if (h != null) {
            int nul = h.indexOf('\0'); // mod loaders append markers after a NUL
            if (nul >= 0) h = h.substring(0, nul);
            h = h.replaceAll("\\.+$", "");
        }
        if (h == null || h.isBlank()) {
            String ip = Bukkit.getIp();
            h = ip == null || ip.isBlank() || ip.equals("0.0.0.0") ? "localhost" : ip;
        }
        return h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h;
    }

    /**
     * The port the player connected to. A domain with an SRV record is typed without a port, so the
     * client reports the default 25565 although the server listens elsewhere: use the server's port then.
     */
    static int connectPort(Player p) {
        InetSocketAddress v = p.getVirtualHost();
        if (v == null || v.getPort() <= 0) return Bukkit.getPort();
        return v.getPort() == 25565 ? Bukkit.getPort() : v.getPort();
    }

    public void send(Player player) {
        if (!cfg().getBoolean("enabled", true) || current == null) return;
        URI uri = url(player);
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
                    e.getPlayer().getName() + " 리소스팩 적용 실패: " + e.getStatus() + " (주소: " + url(e.getPlayer())
                            + "). 서버 앞에 프록시나 디도스 방어가 있으면 resource-pack.host.mode 를 separate-port 로,"
                            + " 접속 주소와 실제 서버 포트가 다르면 resource-pack.host.public-url 을 설정하세요");
            default -> {}
        }
    }
}
