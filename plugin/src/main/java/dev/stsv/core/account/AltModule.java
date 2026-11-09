package dev.stsv.core.account;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 부계정 금지 (config "alts"): one account per IP ({@link AltRules}). Checked at login (AsyncPlayerPreLoginEvent, off
 * the main thread): the first account from an address owns it, another account from it is refused with
 * messages.yml alts.refused. Operators and accounts an admin allowed (/관리자 부계정 허용) get in without claiming.
 * Nobody online is kicked when this is turned on: the first account to log in afterwards claims its address.
 * Addresses are kept as an HMAC with a secret in plugins/STSV/data/alt-secret.txt (servers sharing one MySQL database
 * must copy the same file). When the database cannot answer, the login is let through (logged).
 */
public final class AltModule implements StsvModule, Listener {

    public static final String ADMIN_OWNER = "STSV 부계정";
    private static final long DAY = 86_400_000L;

    private STSVPlugin plugin;
    private AltStore store;
    private byte[] secret;
    private volatile boolean enabled;
    private volatile boolean ignoreLocal;
    private volatile long expireMs;
    private volatile Set<UUID> ops = Set.of();
    /** Accounts refused lately (lower-case name -> id): an admin allows them by name before they ever got in. */
    private final java.util.Map<String, UUID> refused = new java.util.concurrent.ConcurrentHashMap<>();
    private BukkitTask opsTask;

    @Override
    public String id() {
        return "alts";
    }

    @Override
    public void enable(STSVPlugin plugin) throws IOException {
        this.plugin = plugin;
        store = new AltStore(plugin.db());
        store.init();
        secret = secret(plugin.getDataFolder().toPath().resolve("data").resolve("alt-secret.txt"));
        load();
        refreshOps();
        // operators are read on the main thread; the login check (another thread) uses this copy
        opsTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOps, 200L, 200L);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.admin().add(ADMIN_OWNER, "부계정", "<허용|해제|목록|정보|초기화> ...",
                "한 IP에 한 계정: 같은 IP를 쓰는 계정 허용·해제, 허용 목록, 플레이어나 IP의 주인 보기, IP 주인 초기화", this::command,
                (s, a) -> a.length == 1 ? List.of("허용", "해제", "목록", "정보", "초기화")
                        : a.length == 2 && !a[0].equals("목록") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        if (opsTask != null) opsTask.cancel();
        HandlerList.unregisterAll(this);
        if (plugin != null) plugin.admin().removeAll(ADMIN_OWNER);
    }

    private void load() {
        enabled = plugin.getConfig().getBoolean("alts.enabled");
        ignoreLocal = plugin.getConfig().getBoolean("alts.ignore-local");
        expireMs = Math.max(0, plugin.getConfig().getLong("alts.expire-days")) * DAY;
    }

    private void refreshOps() {
        ops = Bukkit.getOperators().stream().map(OfflinePlayer::getUniqueId).collect(Collectors.toUnmodifiableSet());
    }

    /** The server's secret for the address hashes: made once (32 random bytes), kept in a file next to the database. */
    private static byte[] secret(Path file) throws IOException {
        if (Files.exists(file)) {
            String s = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (s.length() >= 32) return s.getBytes(StandardCharsets.UTF_8);
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String s = HexFormat.of().formatHex(b);
        Files.createDirectories(file.getParent());
        Files.writeString(file, s + "\n", StandardCharsets.UTF_8);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private String hash(InetAddress a) {
        return AltRules.hash(AltRules.key(a), secret);
    }

    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!enabled || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        InetAddress a = e.getAddress();
        if (a == null || (ignoreLocal && AltRules.local(a))) return;
        AltStore.Login r;
        try {
            r = store.login(hash(a), e.getUniqueId(), e.getName(), ops.contains(e.getUniqueId()), System.currentTimeMillis(), expireMs);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[부계정] " + e.getName() + " 의 접속을 확인하지 못해 그대로 들여보냅니다: " + ex.getMessage());
            return;
        }
        switch (r.verdict()) {
            case REFUSE -> {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Lang.mm("alts.refused"));
                if (refused.size() > 500) refused.clear();
                refused.put(e.getName().toLowerCase(Locale.ROOT), e.getUniqueId());
                plugin.getLogger().info("[부계정] " + e.getName() + " 접속 거부: 이 IP(" + AltRules.masked(AltRules.key(a)) + ")는 "
                        + r.owner().name() + " 의 것");
            }
            case CLAIM -> plugin.getLogger().info("[부계정] " + e.getName() + " 이(가) IP " + AltRules.masked(AltRules.key(a))
                    + (r.owner() == null ? " 의 주인이 됨" : " 의 주인이 됨 (전 주인 " + r.owner().name() + " 은 오래 접속하지 않음)"));
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 부계정 <허용|해제|목록|정보|초기화>
    // ------------------------------------------------------------------------------------------

    private void command(CommandSender sender, String[] args) {
        String what = args.length == 0 ? "" : args[0];
        String target = args.length > 1 ? args[1] : null;
        switch (what) {
            case "허용", "allow" -> {
                if (target == null) { usage(sender); return; }
                find(sender, target, u -> store.allow(u, target, sender.getName(), System.currentTimeMillis()).thenAccept(added ->
                        sender.sendMessage(Text.msg(added ? "<green>" + target + " 은(는) 이제 다른 계정이 쓰는 IP에서도 접속할 수 있습니다."
                                : "<yellow>" + target + " 은(는) 이미 허용되어 있습니다."))));
            }
            case "해제", "remove" -> {
                if (target == null) { usage(sender); return; }
                find(sender, target, u -> store.disallow(u).thenAccept(removed -> sender.sendMessage(Text.msg(removed
                        ? "<green>" + target + " 허용 해제. 다른 계정이 주인인 IP에서는 다시 접속할 수 없습니다."
                        : "<yellow>" + target + " 은(는) 허용 목록에 없습니다."))));
            }
            case "목록", "list" -> store.allowedList().thenAccept(list -> {
                sender.sendMessage(Text.msg("<gray>같은 IP 허용 계정 <white>" + list.size() + "</white>명" + (enabled ? "" : " <red>(alts.enabled: false, 지금은 검사 안 함)")));
                var f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault());
                for (AltStore.Allowed x : list) {
                    sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + x.name() + " <gray>(" + x.by() + ", " + f.format(java.time.Instant.ofEpochMilli(x.at())) + ")"));
                }
            });
            case "정보", "info" -> {
                if (target == null) { usage(sender); return; }
                InetAddress ip = literal(target);
                if (ip != null) {
                    tellOwner(sender, AltRules.masked(AltRules.key(ip)), hash(ip));
                    return;
                }
                find(sender, target, u -> {
                    Player online = Bukkit.getPlayer(u);
                    store.owned(u).thenCombine(store.isAllowed(u), (n, allowed) -> {
                        sender.sendMessage(Text.msg("<gray>" + target + ": 주인인 IP <white>" + n + "</white>개"
                                + (allowed ? " <green>· 같은 IP 허용됨" : "") + (ops.contains(u) ? " <gold>· 관리자(검사 안 함)" : "")));
                        return null;
                    });
                    if (online != null && online.getAddress() != null) {
                        InetAddress a = online.getAddress().getAddress();
                        if (ignoreLocal && AltRules.local(a)) sender.sendMessage(Text.mm(" <gray>지금 접속한 주소는 내부망이라 검사하지 않습니다."));
                        else tellOwner(sender, AltRules.masked(AltRules.key(a)), hash(a));
                    }
                });
            }
            case "초기화", "reset" -> {
                if (target == null) { usage(sender); return; }
                InetAddress ip = literal(target);
                if (ip != null) {
                    store.release(hash(ip)).thenAccept(had -> sender.sendMessage(Text.msg(had
                            ? "<green>IP " + AltRules.masked(AltRules.key(ip)) + " 의 주인을 지웠습니다. 다음에 그 IP로 처음 접속하는 계정이 주인이 됩니다."
                            : "<yellow>그 IP에는 주인이 없습니다.")));
                    return;
                }
                find(sender, target, u -> store.releaseAll(u).thenAccept(n -> sender.sendMessage(Text.msg("<green>" + target + " 이(가) 주인이던 IP "
                        + n + "개를 풀었습니다. 그 IP로 다음에 처음 접속하는 계정이 주인이 됩니다."))));
            }
            default -> usage(sender);
        }
    }

    private void tellOwner(CommandSender sender, String masked, String hash) {
        store.ownerOf(hash).thenAccept(o -> {
            if (o == null) {
                sender.sendMessage(Text.mm(" <gray>IP " + masked + ": 주인 없음 (다음에 접속하는 계정이 주인이 됨)"));
                return;
            }
            long days = (System.currentTimeMillis() - o.lastSeen()) / DAY;
            sender.sendMessage(Text.mm(" <gray>IP " + masked + " 의 주인: <white>" + o.name() + "</white> (마지막 접속 " + days + "일 전"
                    + (expireMs > 0 ? ", " + expireMs / DAY + "일 접속하지 않으면 풀림" : "") + ")"));
        });
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Text.msg("<red>/관리자 부계정 허용|해제 <플레이어> <gray>(같은 IP를 쓰는 가족·친구 계정)"));
        sender.sendMessage(Text.msg("<red>/관리자 부계정 목록 <gray>| <red>정보 <플레이어|IP> <gray>| <red>초기화 <플레이어|IP> <gray>(그 IP의 주인을 지움)"));
    }

    /** A typed IP address (digits and dots, or hex and colons; never a name lookup), else null. */
    static InetAddress literal(String s) {
        if (!s.matches("[0-9.]+") && !(s.contains(":") && s.toLowerCase(Locale.ROOT).matches("[0-9a-f:.]+"))) return null;
        try {
            return InetAddress.ofLiteral(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * An account by name: online, refused lately, ever joined, or (an account that never got in) looked up by the
     * server like /whitelist add does (off the main thread). Reports unknown names.
     */
    private void find(CommandSender sender, String name, Consumer<UUID> then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(online.getUniqueId());
            return;
        }
        UUID wasRefused = refused.get(name.toLowerCase(Locale.ROOT));
        if (wasRefused != null) {
            then.accept(wasRefused);
            return;
        }
        plugin.profiles().store().findByName(name).thenAccept(found -> {
            if (found.isPresent()) {
                then.accept(found.get());
                return;
            }
            if (!name.matches("[A-Za-z0-9_]{1,16}")) {
                sender.sendMessage(Text.msg("<red>알 수 없는 플레이어: " + name));
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                if (op.getUniqueId() == null) sender.sendMessage(Text.msg("<red>알 수 없는 플레이어: " + name));
                else then.accept(op.getUniqueId());
            });
        });
    }
}
