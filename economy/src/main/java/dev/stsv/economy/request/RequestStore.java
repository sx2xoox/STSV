package dev.stsv.economy.request;

import dev.stsv.core.data.Db;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who completed which request today, the shared goal's progress per week, and (주민의 부탁) each player's 친밀도 with each 주민
 * (eco_bond), the 명인 의뢰 done (eco_master: who, for whom, which week) and the step reached in each week's chained request
 * (eco_chain).
 */
public final class RequestStore {

    /** A 명인 의뢰 done: by player, for person, in week, at. */
    public record MasterDone(int week, UUID player, String person, long at) {}

    private final Db db;
    private int day = -1;
    private final Map<UUID, Set<Integer>> done = new ConcurrentHashMap<>();
    private final Map<Integer, Long> progress = new ConcurrentHashMap<>();             // week -> delivered
    private final Map<Integer, Map<UUID, Long>> contrib = new ConcurrentHashMap<>();   // week -> player -> delivered
    private final Set<Integer> completed = ConcurrentHashMap.newKeySet();
    /** "player|person" -> 친밀도 */
    private final Map<String, Integer> bonds = new ConcurrentHashMap<>();
    private final List<MasterDone> masters = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** "week|player" -> steps done */
    private final Map<String, Integer> chains = new ConcurrentHashMap<>();

    public RequestStore(Db db) {
        this.db = db;
    }

    public void init(int today) {
        db.migrate("eco_request", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_request_done (day INT NOT NULL, idx INT NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (day, idx, player))",
                "CREATE TABLE IF NOT EXISTS eco_community (week INT NOT NULL, player CHAR(36) NOT NULL, amount BIGINT NOT NULL, PRIMARY KEY (week, player))",
                "CREATE TABLE IF NOT EXISTS eco_community_done (week INT NOT NULL PRIMARY KEY, at BIGINT NOT NULL)"), List.of(
                "CREATE TABLE IF NOT EXISTS eco_bond (player CHAR(36) NOT NULL, person VARCHAR(64) NOT NULL, points INT NOT NULL, "
                        + "PRIMARY KEY (player, person))",
                "CREATE TABLE IF NOT EXISTS eco_master (week INT NOT NULL, player CHAR(36) NOT NULL, person VARCHAR(64) NOT NULL, "
                        + "at BIGINT NOT NULL, PRIMARY KEY (week, player))",
                "CREATE TABLE IF NOT EXISTS eco_chain (week INT NOT NULL, player CHAR(36) NOT NULL, step INT NOT NULL, PRIMARY KEY (week, player))")));
        bonds.clear();
        masters.clear();
        chains.clear();
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
            for (Object[] r : Db.query(c, "SELECT player, person, points FROM eco_bond", rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)})) {
                bonds.put(r[0] + "|" + r[1], (Integer) r[2]);
            }
            for (MasterDone m : Db.query(c, "SELECT week, player, person, at FROM eco_master ORDER BY at",
                    rs -> new MasterDone(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4)))) masters.add(m);
            for (Object[] r : Db.query(c, "SELECT week, player, step FROM eco_chain", rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getInt(3)})) {
                chains.put(r[0] + "|" + r[1], (Integer) r[2]);
            }
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

    // ------------------------------------------------------------------------------------------ 주민의 부탁

    public int bond(UUID player, String person) {
        return bonds.getOrDefault(player + "|" + person, 0);
    }

    /** Adds 친밀도; returns the new points. */
    public int addBond(UUID player, String person, int n) {
        int now = bonds.merge(player + "|" + person, n, Integer::sum);
        db.execute(db.upsert("eco_bond", List.of("player", "person"), List.of("points")), player.toString(), person, now);
        return now;
    }

    public boolean masterDone(int week, UUID player) {
        return masters.stream().anyMatch(m -> m.week() == week && m.player().equals(player));
    }

    public void master(int week, UUID player, String person, long at) {
        masters.add(new MasterDone(week, player, person == null ? "" : person, at));
        db.execute(db.upsert("eco_master", List.of("week", "player"), List.of("person", "at")), week, player.toString(), person == null ? "" : person, at);
    }

    /** The players who did a 명인 의뢰 for this person, the latest first, each once. */
    public List<UUID> regulars(String person, int limit) {
        List<UUID> out = new java.util.ArrayList<>();
        for (int i = masters.size() - 1; i >= 0 && out.size() < limit; i--) {
            MasterDone m = masters.get(i);
            if (m.person().equals(person) && !out.contains(m.player())) out.add(m.player());
        }
        return out;
    }

    public int chainStep(int week, UUID player) {
        return chains.getOrDefault(week + "|" + player, 0);
    }

    public void chainStep(int week, UUID player, int step) {
        chains.put(week + "|" + player, step);
        db.execute(db.upsert("eco_chain", List.of("week", "player"), List.of("step")), week, player.toString(), step);
    }

    public void reset() {
        bonds.clear();
        masters.clear();
        chains.clear();
        done.clear();
        progress.clear();
        contrib.clear();
        completed.clear();
        db.async(c -> {
            Db.update(c, "DELETE FROM eco_request_done");
            Db.update(c, "DELETE FROM eco_community");
            Db.update(c, "DELETE FROM eco_community_done");
            Db.update(c, "DELETE FROM eco_bond");
            Db.update(c, "DELETE FROM eco_master");
            Db.update(c, "DELETE FROM eco_chain");
            return null;
        });
    }
}
