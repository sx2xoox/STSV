package dev.stsv.economy.request;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who completed which request today, and the shared goal's progress per week. */
public final class RequestStore {

    private final Db db;
    private int day = -1;
    private final Map<UUID, Set<Integer>> done = new ConcurrentHashMap<>();
    private final Map<Integer, Long> progress = new ConcurrentHashMap<>();             // week -> delivered
    private final Map<Integer, Map<UUID, Long>> contrib = new ConcurrentHashMap<>();   // week -> player -> delivered
    private final Set<Integer> completed = ConcurrentHashMap.newKeySet();

    public RequestStore(Db db) {
        this.db = db;
    }

    public void init(int today) {
        db.migrate("eco_request", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_request_done (day INT NOT NULL, idx INT NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (day, idx, player))",
                "CREATE TABLE IF NOT EXISTS eco_community (week INT NOT NULL, player CHAR(36) NOT NULL, amount BIGINT NOT NULL, PRIMARY KEY (week, player))",
                "CREATE TABLE IF NOT EXISTS eco_community_done (week INT NOT NULL PRIMARY KEY, at BIGINT NOT NULL)")));
        progress.clear();
        contrib.clear();
        completed.clear();
        db.sync(c -> {
            for (Object[] r : Db.query(c, "SELECT week, player, amount FROM eco_community", rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getLong(3)})) {
                int w = (Integer) r[0];
                contrib.computeIfAbsent(w, k -> new ConcurrentHashMap<>()).put(UUID.fromString((String) r[1]), (Long) r[2]);
                progress.merge(w, (Long) r[2], Long::sum);
            }
            for (Integer w : Db.query(c, "SELECT week FROM eco_community_done", rs -> rs.getInt(1))) completed.add(w);
            return null;
        });
        newDay(today);
    }

    public void newDay(int today) {
        day = today;
        done.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT idx, player FROM eco_request_done WHERE day = ?", rs -> new Object[]{rs.getInt(1), rs.getString(2)}, today))) {
            done.computeIfAbsent(UUID.fromString((String) r[1]), k -> new HashSet<>()).add((Integer) r[0]);
        }
    }

    public Set<Integer> done(UUID player) {
        return done.getOrDefault(player, Set.of());
    }

    public void complete(UUID player, int index) {
        done.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(index);
        db.execute(db.upsert("eco_request_done", List.of("day", "idx", "player"), List.of()), day, index, player.toString());
    }

    public long progress(int week) {
        return progress.getOrDefault(week, 0L);
    }

    public Map<UUID, Long> contributors(int week) {
        return new HashMap<>(contrib.getOrDefault(week, Map.of()));
    }

    public void contribute(int week, UUID player, long amount) {
        progress.merge(week, amount, Long::sum);
        long mine = contrib.computeIfAbsent(week, k -> new ConcurrentHashMap<>()).merge(player, amount, Long::sum);
        db.execute(db.upsert("eco_community", List.of("week", "player"), List.of("amount")), week, player.toString(), mine);
    }

    public boolean completed(int week) {
        return completed.contains(week);
    }

    public void markCompleted(int week, long now) {
        completed.add(week);
        db.execute(db.upsert("eco_community_done", List.of("week"), List.of("at")), week, now);
    }

    public void reset() {
        done.clear();
        progress.clear();
        contrib.clear();
        completed.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_request_done");
            Db.update(c, "DELETE FROM eco_community");
            Db.update(c, "DELETE FROM eco_community_done");
            return null;
        });
    }
}
