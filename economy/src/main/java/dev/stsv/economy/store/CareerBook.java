package dev.stsv.economy.store;

import dev.stsv.core.data.Db;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the 편의점 remembers of its workers (3차, README "편의점" 경력): each worker's customers served (all stores) and those
 * served at night, the rank last told them, how many of each kind they served and whether they have learnt that kind's usual
 * (단골 메뉴), each store's customers this month by worker (이달의 점장), each 내 가게's hired workers. All in memory (small),
 * written through to the database as it changes.
 */
final class CareerBook {

    /** One worker: customers served (all stores), at night, the rank index last announced. */
    static final class Career {
        long served, night;
        int shown;
    }

    /** One worker and one kind: served, how many times they fetched the usual. */
    static final class Kind {
        long served;
        int usual;
    }

    private final Db db;
    private final Map<UUID, Career> careers = new HashMap<>();
    private final Map<UUID, Map<String, Kind>> kinds = new HashMap<>();
    /** store -> worker -> served, this month (KST yyyy-MM: month) */
    private final Map<String, Map<UUID, Long>> month = new HashMap<>();
    private String monthKey = "";
    private final Map<String, Set<UUID>> hired = new HashMap<>();

    CareerBook(Db db) {
        this.db = db;
    }

    static String monthOf(ZonedDateTime t) {
        return String.format("%04d-%02d", t.getYear(), t.getMonthValue());
    }

    void start() {
        db.migrate("eco_store_life", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_store_career (player CHAR(36) NOT NULL PRIMARY KEY, served BIGINT NOT NULL, night BIGINT NOT NULL, shown INT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_store_kind (player CHAR(36) NOT NULL, kind VARCHAR(32) NOT NULL, served BIGINT NOT NULL, usual INT NOT NULL, PRIMARY KEY (player, kind))",
                "CREATE TABLE IF NOT EXISTS eco_store_month (store VARCHAR(64) NOT NULL, month CHAR(7) NOT NULL, player CHAR(36) NOT NULL, served BIGINT NOT NULL, PRIMARY KEY (store, month, player))",
                "CREATE TABLE IF NOT EXISTS eco_store_hire (store VARCHAR(64) NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (store, player))")));
        careers.clear();
        kinds.clear();
        month.clear();
        hired.clear();
        monthKey = monthOf(ZonedDateTime.now(StoreModule.KST));
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT player, served, night, shown FROM eco_store_career",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)})) {
                UUID u = uuid((String) r[0]);
                if (u == null) continue;
                Career k = new Career();
                k.served = (Long) r[1];
                k.night = (Long) r[2];
                k.shown = (Integer) r[3];
                careers.put(u, k);
            }
            for (Object[] r : Db.query(c, "SELECT player, kind, served, usual FROM eco_store_kind",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4)})) {
                UUID u = uuid((String) r[0]);
                if (u == null) continue;
                Kind k = new Kind();
                k.served = (Long) r[2];
                k.usual = (Integer) r[3];
                kinds.computeIfAbsent(u, x -> new HashMap<>()).put((String) r[1], k);
            }
            for (Object[] r : Db.query(c, "SELECT store, player, served FROM eco_store_month WHERE month = ?",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)}, monthKey)) {
                UUID u = uuid((String) r[1]);
                if (u != null) month.computeIfAbsent((String) r[0], x -> new HashMap<>()).put(u, (Long) r[2]);
            }
            for (String[] r : Db.query(c, "SELECT store, player FROM eco_store_hire", rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                UUID u = uuid(r[1]);
                if (u != null) hired.computeIfAbsent(r[0], x -> new LinkedHashSet<>()).add(u);
            }
            return null;
        });
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ careers

    Career of(UUID u) {
        return careers.computeIfAbsent(u, x -> new Career());
    }

    long served(UUID u) {
        Career c = careers.get(u);
        return c == null ? 0 : c.served;
    }

    long night(UUID u) {
        Career c = careers.get(u);
        return c == null ? 0 : c.night;
    }

    private void save(UUID u, Career c) {
        db.execute(db.upsert("eco_store_career", List.of("player"), List.of("served", "night", "shown")), u.toString(), c.served, c.night, c.shown);
    }

    /** A customer served: the worker's count (and the night's), the kind's, the store's this month. */
    void served(UUID u, String store, String kind, boolean night) {
        Career c = of(u);
        c.served++;
        if (night) c.night++;
        save(u, c);
        Kind k = kinds.computeIfAbsent(u, x -> new HashMap<>()).computeIfAbsent(kind, x -> new Kind());
        k.served++;
        db.execute(db.upsert("eco_store_kind", List.of("player", "kind"), List.of("served", "usual")), u.toString(), kind, k.served, k.usual);
        String now = monthOf(ZonedDateTime.now(StoreModule.KST));
        if (!now.equals(monthKey)) {   // a new month: everyone starts again
            monthKey = now;
            month.clear();
        }
        long n = month.computeIfAbsent(store, x -> new HashMap<>()).merge(u, 1L, Long::sum);
        db.execute(db.upsert("eco_store_month", List.of("store", "month", "player"), List.of("served")), store, monthKey, u.toString(), n);
    }

    /** Sets a worker's served count (an admin's 경력): the rank told is set back to what it gives, night kept. */
    void set(UUID u, long served, int shown) {
        Career c = of(u);
        c.served = Math.max(0, served);
        c.shown = shown;
        save(u, c);
    }

    /** The rank index last announced; marks this one announced. */
    int shown(UUID u, int now) {
        Career c = of(u);
        int before = c.shown;
        if (now != before) {
            c.shown = now;
            save(u, c);
        }
        return before;
    }

    // ------------------------------------------------------------------------------------------ kinds (단골)

    long kind(UUID u, String kind) {
        Map<String, Kind> m = kinds.get(u);
        Kind k = m == null ? null : m.get(kind);
        return k == null ? 0 : k.served;
    }

    /** Whether they have fetched this kind's usual at least once (the 도감 shows it then). */
    boolean knowsUsual(UUID u, String kind) {
        Map<String, Kind> m = kinds.get(u);
        Kind k = m == null ? null : m.get(kind);
        return k != null && k.usual > 0;
    }

    /** They fetched the usual: true the first time. */
    boolean usual(UUID u, String kind) {
        Kind k = kinds.computeIfAbsent(u, x -> new HashMap<>()).computeIfAbsent(kind, x -> new Kind());
        boolean first = k.usual == 0;
        k.usual++;
        db.execute(db.upsert("eco_store_kind", List.of("player", "kind"), List.of("served", "usual")), u.toString(), kind, k.served, k.usual);
        return first;
    }

    // ------------------------------------------------------------------------------------------ this month

    /** This month's top worker of a store (most served; null: nobody yet). */
    UUID top(String store) {
        if (!monthOf(ZonedDateTime.now(StoreModule.KST)).equals(monthKey)) return null;
        Map<UUID, Long> m = month.get(store);
        if (m == null) return null;
        UUID best = null;
        long n = 0;
        for (Map.Entry<UUID, Long> e : m.entrySet()) if (e.getValue() > n) {
            n = e.getValue();
            best = e.getKey();
        }
        return best;
    }

    long monthServed(String store, UUID u) {
        Map<UUID, Long> m = month.get(store);
        return m == null ? 0 : m.getOrDefault(u, 0L);
    }

    // ------------------------------------------------------------------------------------------ hired (남의 가게 알바)

    Set<UUID> hired(String store) {
        return hired.getOrDefault(store, Set.of());
    }

    boolean hire(String store, UUID u) {
        boolean added = hired.computeIfAbsent(store, x -> new LinkedHashSet<>()).add(u);
        if (added) db.execute(db.upsert("eco_store_hire", List.of("store", "player"), List.of()), store, u.toString());
        return added;
    }

    boolean fire(String store, UUID u) {
        Set<UUID> s = hired.get(store);
        boolean removed = s != null && s.remove(u);
        if (removed) db.execute("DELETE FROM eco_store_hire WHERE store = ? AND player = ?", store, u.toString());
        return removed;
    }

    /** A store gone: its hired workers forgotten. */
    void forget(String store) {
        if (hired.remove(store) != null) db.execute("DELETE FROM eco_store_hire WHERE store = ?", store);
    }
}
