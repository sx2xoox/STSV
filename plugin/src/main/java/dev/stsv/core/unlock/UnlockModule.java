package dev.stsv.core.unlock;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * What players own for good and wear or use without holding an item: title banners, emotes, tool skins. One book for
 * all of them ({@link UnlockStore}); a feature registers its kind ({@link #register}) and asks {@link #has}. Things
 * are given by the donation shop (a product's {@code unlocks}) or by an admin: /관리자 해금.
 *
 * <p>Online players' things are kept in memory (read when they join); {@link #has} of someone offline is false.
 */
public final class UnlockModule implements StsvModule, Listener {

    private static final String ADMIN_OWNER = "STSV 해금";

    /**
     * A kind of thing.
     *
     * @param word    its word in commands and in the shop ("배너")
     * @param name    what one is called, by its id
     * @param ids     the ids there are now
     * @param icon    one as an item to look at (a shop tile); may be null
     * @param preview lines that show a player what it would look like on them (a banner with their own name); may be null
     * @param changed told when a player's things of this kind changed (id null: all of them were just read); may be null
     */
    public record Kind(String id, String word, java.util.function.Function<String, String> name, Supplier<Collection<String>> ids,
                       java.util.function.Function<String, org.bukkit.inventory.ItemStack> icon,
                       java.util.function.BiFunction<Player, String, List<net.kyori.adventure.text.Component>> preview,
                       BiConsumer<UUID, String> changed) {}

    private STSVPlugin plugin;
    private UnlockStore store;
    private final Map<String, Kind> kinds = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Set<String>>> online = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "unlocks";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new UnlockStore(plugin.db());
        store.init();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        plugin.admin().add(ADMIN_OWNER, "해금", "지급|회수 <플레이어> <종류> <id|전체> | 목록 <플레이어> | 종류",
                "플레이어가 영구히 가지는 것(칭호 배너 · 감정표현 · 도구 스킨)을 주기 / 도로 가져가기 / 가진 것 보기", this::admin, this::complete);
    }

    @Override
    public void disable() {
        if (plugin != null) {
            plugin.admin().removeAll(ADMIN_OWNER);
            HandlerList.unregisterAll(this);
        }
        online.clear();
    }

    /** A feature's kind of thing (again: replaces the earlier one, as after a reload). */
    public void register(Kind kind) {
        kinds.put(kind.id(), kind);
    }

    public Kind kind(String id) {
        return kinds.get(id);
    }

    /** A kind by its id or its word in commands. */
    private Kind kindOf(String typed) {
        for (Kind k : kinds.values()) if (k.id().equalsIgnoreCase(typed) || k.word().equals(typed)) return k;
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------------------------------

    /** Whether an online player has a thing (offline: false). */
    public boolean has(UUID player, String kind, String id) {
        Map<String, Set<String>> mine = online.get(player);
        Set<String> of = mine == null ? null : mine.get(kind);
        return of != null && of.contains(id);
    }

    /** An online player's things of a kind (offline: none). */
    public Set<String> owned(UUID player, String kind) {
        Map<String, Set<String>> mine = online.get(player);
        Set<String> of = mine == null ? null : mine.get(kind);
        return of == null ? Set.of() : Set.copyOf(of);
    }

    /** "banner:fire" → {kind, id}; null when it is not of that shape. */
    public static String[] split(String unlock) {
        int at = unlock == null ? -1 : unlock.indexOf(':');
        if (at < 1 || at == unlock.length() - 1) return null;
        return new String[]{unlock.substring(0, at).trim(), unlock.substring(at + 1).trim()};
    }

    /** Whether there is such a thing now ("banner:fire": the kind is registered and knows the id). */
    public boolean exists(String unlock) {
        String[] u = split(unlock);
        Kind k = u == null ? null : kinds.get(u[0]);
        return k != null && k.ids().get().contains(u[1]);
    }

    /** A thing as an item to look at ("banner:fire": the theme's icon), or null when its kind has none. */
    public org.bukkit.inventory.ItemStack icon(String unlock) {
        String[] u = split(unlock);
        Kind k = u == null ? null : kinds.get(u[0]);
        return k == null || k.icon() == null ? null : k.icon().apply(u[1]);
    }

    /** What a thing would look like on a player, as lines of an item's description (none when its kind has no preview). */
    public List<net.kyori.adventure.text.Component> preview(Player p, String unlock) {
        String[] u = split(unlock);
        Kind k = u == null ? null : kinds.get(u[0]);
        return k == null || k.preview() == null ? List.of() : k.preview().apply(p, u[1]);
    }

    /** Whether an online player has a thing ("banner:fire"). */
    public boolean has(UUID player, String unlock) {
        String[] u = split(unlock);
        return u != null && has(player, u[0], u[1]);
    }

    /** "화염 배너": what a thing is called (its id when nobody knows). */
    public String name(String unlock) {
        String[] u = split(unlock);
        Kind k = u == null ? null : kinds.get(u[0]);
        return k == null ? unlock : k.name().apply(u[1]);
    }

    // ------------------------------------------------------------------------------------------
    // Giving and taking
    // ------------------------------------------------------------------------------------------

    /** Gives a thing (true: new to the player). The memory of an online player follows, and the kind is told. */
    public CompletableFuture<Boolean> grant(UUID player, String kind, String id, String source) {
        return store.grant(player, kind, id, source, System.currentTimeMillis()).thenApply(fresh -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Map<String, Set<String>> mine = online.get(player);
                if (mine != null) mine.computeIfAbsent(kind, k -> ConcurrentHashMap.newKeySet()).add(id);
                tell(player, kind, id);
            });
            return fresh;
        });
    }

    public CompletableFuture<Boolean> revoke(UUID player, String kind, String id) {
        return store.revoke(player, kind, id).thenApply(had -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Map<String, Set<String>> mine = online.get(player);
                Set<String> of = mine == null ? null : mine.get(kind);
                if (of != null) of.remove(id);
                tell(player, kind, id);
            });
            return had;
        });
    }

    private void tell(UUID player, String kind, String id) {
        Kind k = kinds.get(kind);
        if (k == null || k.changed() == null) return;
        try {
            k.changed().accept(player, id);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "[해금] " + kind + ":" + id + " 변경 알림 실패", e);
        }
    }

    private void load(Player p) {
        UUID u = p.getUniqueId();
        store.owned(u).thenAccept(all -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Map<String, Set<String>> mine = new ConcurrentHashMap<>();
            all.forEach((kind, ids) -> {
                Set<String> set = ConcurrentHashMap.newKeySet();
                set.addAll(ids);
                mine.put(kind, set);
            });
            online.put(u, mine);
            for (Kind k : kinds.values()) if (mine.containsKey(k.id())) tell(u, k.id(), null);
        })).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING, "[해금] " + p.getName() + " 의 목록을 읽지 못함", err);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        online.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 해금
    // ------------------------------------------------------------------------------------------

    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0];
        switch (sub) {
            case "종류", "kinds" -> {
                for (Kind k : kinds.values()) {
                    s.sendMessage(Lang.mm("unlock.kind-line", "word", k.word(), "kind", k.id(), "ids", String.join(", ", k.ids().get())));
                }
            }
            case "목록", "list" -> {
                if (a.length < 2) {
                    s.sendMessage(Lang.msg("unlock.usage"));
                    return;
                }
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Lang.msg("unlock.no-player", "name", a[1]));
                        return;
                    }
                    store.owned(found.get()).thenAccept(all -> Bukkit.getScheduler().runTask(plugin, () -> {
                        s.sendMessage(Lang.msg("unlock.list-head", "player", Names.real(found.get())));
                        if (all.isEmpty()) s.sendMessage(Lang.mm("unlock.list-none"));
                        all.forEach((kind, ids) -> {
                            Kind k = kinds.get(kind);
                            List<String> names = new ArrayList<>();
                            for (String id : ids) names.add(k == null ? id : k.name().apply(id) + " (" + id + ")");
                            s.sendMessage(Lang.mm("unlock.list-line", "word", k == null ? kind : k.word(), "things", String.join(", ", names)));
                        });
                    }));
                });
            }
            case "지급", "give", "회수", "take" -> {
                boolean give = sub.equals("지급") || sub.equals("give");
                Kind k = a.length > 3 ? kindOf(a[2]) : null;
                if (k == null) {
                    s.sendMessage(Lang.msg("unlock.usage"));
                    return;
                }
                boolean all = a[3].equals("전체") || a[3].equalsIgnoreCase("all");
                List<String> ids = all ? List.copyOf(k.ids().get()) : List.of(a[3]);
                if (!all && !k.ids().get().contains(a[3])) {
                    s.sendMessage(Lang.msg("unlock.unknown", "word", k.word(), "id", a[3], "ids", String.join(", ", k.ids().get())));
                    return;
                }
                String actor = s instanceof Player p ? p.getName() : "console";
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Lang.msg("unlock.no-player", "name", a[1]));
                        return;
                    }
                    UUID id = found.get();
                    String name = Names.real(id);
                    for (String thing : ids) {
                        (give ? grant(id, k.id(), thing, "admin:" + actor) : revoke(id, k.id(), thing)).thenAccept(changed ->
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    String key = give ? (changed ? "unlock.given" : "unlock.had") : (changed ? "unlock.taken" : "unlock.had-not");
                                    s.sendMessage(Lang.msg(key, "player", name, "word", k.word(), "name", k.name().apply(thing)));
                                    if (changed) plugin.getLogger().info("[해금] " + actor + " → " + name + " " + (give ? "지급 " : "회수 ") + k.id() + ":" + thing);
                                    Player target = Bukkit.getPlayer(id);
                                    if (changed && give && target != null) target.sendMessage(Lang.msg("unlock.received", "word", k.word(), "name", k.name().apply(thing)));
                                })).exceptionally(err -> {
                            plugin.getLogger().log(Level.WARNING, "[해금] 지급·회수 실패", err);
                            return null;
                        });
                    }
                });
            }
            default -> s.sendMessage(Lang.msg("unlock.usage"));
        }
    }

    private List<String> complete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("지급", "회수", "목록", "종류");
        if (a.length == 2 && !a[0].equals("종류")) return Names.complete(s, a[1]);
        if (a.length == 3 && (a[0].equals("지급") || a[0].equals("회수"))) return kinds.values().stream().map(Kind::word).toList();
        if (a.length == 4 && (a[0].equals("지급") || a[0].equals("회수"))) {
            Kind k = kindOf(a[2]);
            if (k == null) return List.of();
            List<String> out = new ArrayList<>(k.ids().get());
            out.add("전체");
            return out;
        }
        return List.of();
    }
}
