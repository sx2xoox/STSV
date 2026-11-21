package dev.stsv.economy.secret;

import dev.stsv.core.data.Db;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the secrets know about a player, from their own play: where they last left, where they first came in, who they
 * spend time near, what they sold (the money ledger), their first land. Gathered quietly (eco.kv "secret.mem.<uuid>.*");
 * the ledger and land are read from the database. Callbacks run on the main thread.
 */
public final class Memory implements Listener {

    /** A place: world name and block position. */
    public record Place(String world, int x, int y, int z) {
        public Optional<Location> location() {
            World w = Bukkit.getWorld(world);
            return w == null ? Optional.empty() : Optional.of(new Location(w, x + 0.5, y, z + 0.5));
        }

        String encode() {
            return world + " " + x + " " + y + " " + z;
        }

        static Optional<Place> decode(String s) {
            if (s == null) return Optional.empty();
            String[] w = s.split(" ");
            try {
                return w.length < 4 ? Optional.empty() : Optional.of(new Place(w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        static Place of(Location l) {
            return new Place(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
    }

    /** One sale from the ledger: item id, how many, when (ms). */
    public record Sale(String item, int count, long at) {}

    private final SecretModule m;
    private final EcoPlugin eco;
    /** minutes spent near each other player this session: player -> (other -> minutes) */
    private final Map<UUID, Map<UUID, Integer>> near = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>();

    Memory(SecretModule m) {
        this.m = m;
        this.eco = m.eco();
    }

    private static String key(UUID u, String k) {
        return "secret.mem." + u + "." + k;
    }

    void start() {
        for (Player p : Bukkit.getOnlinePlayers()) seen(p);
        Bukkit.getScheduler().runTaskTimer(eco, this::sample, 1200L, 1200L);
    }

    /** Every minute: each player's nearest other player within 48 blocks gets a minute. */
    private void sample() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Player best = null;
            double bd = 48 * 48;
            for (Player o : p.getWorld().getPlayers()) {
                if (o == p) continue;
                double d = o.getLocation().distanceSquared(p.getLocation());
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
            if (best != null) near.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).merge(best.getUniqueId(), 1, Integer::sum);
        }
    }

    private void seen(Player p) {
        UUID u = p.getUniqueId();
        joined.put(u, System.currentTimeMillis());
        if (!eco.kv().has(key(u, "first"))) {
            // a brand-new player stands where they came in; anyone older gets the world spawn (marked as a guess)
            Location at = p.hasPlayedBefore() ? p.getWorld().getSpawnLocation() : p.getLocation();
            eco.kv().set(key(u, "first"), Place.of(at).encode() + (p.hasPlayedBefore() ? " ~" : ""));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        seen(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        eco.kv().set(key(u, "quit"), Place.of(p.getLocation()).encode());
        eco.kv().set(key(u, "quit-at"), System.currentTimeMillis());
        flush(u);
        joined.remove(u);
    }

    void save() {
        for (UUID u : new ArrayList<>(near.keySet())) flush(u);
    }

    /** Adds this session's minutes to the saved friends list (top 8 kept). */
    private void flush(UUID u) {
        Map<UUID, Integer> now = near.remove(u);
        if (now == null || now.isEmpty()) return;
        Map<UUID, Integer> all = friendsRaw(u);
        now.forEach((o, n) -> all.merge(o, n, Integer::sum));
        StringBuilder b = new StringBuilder();
        all.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(8)
                .forEach(en -> b.append(b.isEmpty() ? "" : ",").append(en.getKey()).append(':').append(en.getValue()));
        eco.kv().set(key(u, "friends"), b.toString());
    }

    private Map<UUID, Integer> friendsRaw(UUID u) {
        Map<UUID, Integer> out = new HashMap<>();
        String s = eco.kv().get(key(u, "friends"));
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            String[] w = part.split(":");
            try {
                out.put(UUID.fromString(w[0]), Integer.parseInt(w[1]));
            } catch (RuntimeException ignored) {
                // a hand-edited value
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // what the secrets ask
    // ------------------------------------------------------------------------------------------

    /** Where they last logged out. */
    public Optional<Place> lastQuit(UUID u) {
        return Place.decode(eco.kv().get(key(u, "quit")));
    }

    /** When they last logged out (ms), 0 when unknown. */
    public long lastQuitAt(UUID u) {
        return eco.kv().getLong(key(u, "quit-at"), 0);
    }

    /** Where they first came in (a guess = the world spawn for players older than this module: {@link #firstIsGuess}). */
    public Optional<Place> firstPlace(UUID u) {
        return Place.decode(eco.kv().get(key(u, "first")));
    }

    public boolean firstIsGuess(UUID u) {
        String s = eco.kv().get(key(u, "first"));
        return s == null || s.endsWith("~");
    }

    /** The players they spend the most time near, best first (names that are known). */
    public List<String> friends(UUID u) {
        Map<UUID, Integer> all = friendsRaw(u);
        Map<UUID, Integer> live = near.get(u);
        if (live != null) live.forEach((o, n) -> all.merge(o, n, Integer::sum));
        List<String> out = new ArrayList<>();
        all.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).forEach(en -> {
            String n = Bukkit.getOfflinePlayer(en.getKey()).getName();
            if (n != null) out.add(n);
        });
        return out;
    }

    /** When this session began (ms), or now when unknown. */
    public long sessionStart(UUID u) {
        return joined.getOrDefault(u, System.currentTimeMillis());
    }

    /** Their sales to the shop, newest first (the last 2000 ledger lines). */
    public void sales(UUID u, Consumer<List<Sale>> then) {
        m.core().economy().store().history(u, 2000).whenComplete((list, err) -> {
            List<Sale> out = new ArrayList<>();
            if (list != null) {
                for (var e : list) {
                    if (!"shop_sell".equals(e.reason()) || e.note() == null) continue;
                    String[] w = e.note().split(" x");
                    try {
                        out.add(new Sale(w[0].trim(), w.length > 1 ? Integer.parseInt(w[1].trim()) : 1, e.at()));
                    } catch (NumberFormatException ignored) {
                        out.add(new Sale(w[0].trim(), 1, e.at()));
                    }
                }
            }
            Bukkit.getScheduler().runTask(eco, () -> then.accept(out));
        });
    }

    /** The item they sold the most of (by count), if any. */
    public void mostSold(UUID u, Consumer<Optional<String>> then) {
        sales(u, list -> {
            Map<String, Integer> n = new LinkedHashMap<>();
            for (Sale s : list) n.merge(s.item(), s.count(), Integer::sum);
            then.accept(n.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey));
        });
    }

    /** The first chunk of land they ever claimed that is still theirs: a block in its middle at y 0 (the caller finds the ground). */
    public void firstLand(UUID u, Consumer<Optional<Place>> then) {
        Db db = m.core().db();
        db.async(c -> Db.query(c, "SELECT ch.world, ch.cx, ch.cz FROM eco_territory_chunk ch JOIN eco_territory t ON t.id = ch.land "
                + "WHERE t.owner = ? ORDER BY ch.claimed ASC LIMIT 1", rs -> new Place(rs.getString(1), rs.getInt(2) * 16 + 8, 0, rs.getInt(3) * 16 + 8),
                u.toString())).whenComplete((list, err) -> {
            if (err != null) eco.getLogger().warning("[비밀] 첫 땅 읽기 오류: " + err);
            Optional<Place> r = list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
            Bukkit.getScheduler().runTask(eco, () -> then.accept(r));
        });
    }
}
