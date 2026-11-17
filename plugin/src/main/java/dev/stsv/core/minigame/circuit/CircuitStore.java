package dev.stsv.core.minigame.circuit;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 서킷 records: every player's best lap and best race on every track, how many races they drove and won there. Kept in
 * memory too (a few rows a player and track), read once when the plugin starts; every write goes to the database thread.
 * No Bukkit types (unit-tested on SQLite).
 */
public final class CircuitStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS stsv_circuit_records (track VARCHAR(32) NOT NULL, player CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                    + "best_lap BIGINT NOT NULL, lap_kart VARCHAR(32) NOT NULL, best_race BIGINT NOT NULL, race_kart VARCHAR(32) NOT NULL, "
                    + "races INT NOT NULL, wins INT NOT NULL, updated BIGINT NOT NULL, PRIMARY KEY (track, player))"));

    /** One player's record on one track (times in milliseconds, 0 = none yet). */
    public record Row(String track, UUID player, String name, long bestLap, String lapKart, long bestRace, String raceKart, int races, int wins,
                      long updated) {}

    /** What one race did to a record: the new row, and whether it beat the player's own and the track's best. */
    public record Result(Row row, boolean lapPb, boolean racePb, boolean lapRecord, boolean raceRecord, long oldLapRecord, long oldRaceRecord) {}

    private final Db db;
    private final Map<String, Map<UUID, Row>> rows = new HashMap<>();

    public CircuitStore(Db db) {
        this.db = db;
    }

    /** Creates the table and reads every record (blocking: only while the plugin starts). */
    public void init() {
        db.migrate("circuit", SCHEMA);
        List<Row> all = db.sync(c -> Db.query(c, "SELECT track, player, name, best_lap, lap_kart, best_race, race_kart, races, wins, updated FROM stsv_circuit_records",
                rs -> new Row(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6),
                        rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getLong(10))));
        rows.clear();
        for (Row r : all) rows.computeIfAbsent(r.track(), k -> new HashMap<>()).put(r.player(), r);
    }

    /**
     * One race driven: lapMs = their best lap in it (0 = none: they did not finish a lap), raceMs = their race time (0 = did
     * not finish), won = first place among two or more finishers... or alone. Updates memory at once, the database after.
     */
    public synchronized Result record(String track, UUID player, String name, long lapMs, long raceMs, boolean won, String kart, long now) {
        Map<UUID, Row> t = rows.computeIfAbsent(track, k -> new HashMap<>());
        Row old = t.get(player);
        long oldLapRecord = best(track, true), oldRaceRecord = best(track, false);
        long bestLap = old == null ? 0 : old.bestLap(), bestRace = old == null ? 0 : old.bestRace();
        String lapKart = old == null ? "" : old.lapKart(), raceKart = old == null ? "" : old.raceKart();
        boolean lapPb = lapMs > 0 && (bestLap == 0 || lapMs < bestLap), racePb = raceMs > 0 && (bestRace == 0 || raceMs < bestRace);
        if (lapPb) {
            bestLap = lapMs;
            lapKart = kart;
        }
        if (racePb) {
            bestRace = raceMs;
            raceKart = kart;
        }
        Row row = new Row(track, player, name, bestLap, lapKart, bestRace, raceKart, (old == null ? 0 : old.races()) + 1,
                (old == null ? 0 : old.wins()) + (won ? 1 : 0), now);
        t.put(player, row);
        boolean lapRecord = lapPb && (oldLapRecord == 0 || lapMs < oldLapRecord), raceRecord = racePb && (oldRaceRecord == 0 || raceMs < oldRaceRecord);
        Row w = row;
        db.async(c -> Db.update(c, db.upsert("stsv_circuit_records", List.of("track", "player"),
                        List.of("name", "best_lap", "lap_kart", "best_race", "race_kart", "races", "wins", "updated")),
                w.track(), w.player().toString(), w.name(), w.bestLap(), w.lapKart(), w.bestRace(), w.raceKart(), w.races(), w.wins(), w.updated()));
        return new Result(row, lapPb, racePb, lapRecord, raceRecord, oldLapRecord, oldRaceRecord);
    }

    /** The track's best lap (lap = true) or race time, ms; 0 = none yet. */
    public synchronized long best(String track, boolean lap) {
        long best = 0;
        for (Row r : rows.getOrDefault(track, Map.of()).values()) {
            long v = lap ? r.bestLap() : r.bestRace();
            if (v > 0 && (best == 0 || v < best)) best = v;
        }
        return best;
    }

    /** The top n of a track by best lap (lap = true) or best race; players without one are left out. */
    public synchronized List<Row> top(String track, boolean lap, int n) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows.getOrDefault(track, Map.of()).values()) if ((lap ? r.bestLap() : r.bestRace()) > 0) out.add(r);
        out.sort(Comparator.<Row>comparingLong(r -> lap ? r.bestLap() : r.bestRace()).thenComparingLong(Row::updated));
        return out.size() > n ? List.copyOf(out.subList(0, n)) : List.copyOf(out);
    }

    /** A player's rank on a track (1 = best) by lap or race, 0 = none. */
    public synchronized int rank(String track, UUID player, boolean lap) {
        Row mine = rows.getOrDefault(track, Map.of()).get(player);
        long v = mine == null ? 0 : lap ? mine.bestLap() : mine.bestRace();
        if (v <= 0) return 0;
        int rank = 1;
        for (Row r : rows.getOrDefault(track, Map.of()).values()) {
            long o = lap ? r.bestLap() : r.bestRace();
            if (o > 0 && (o < v || (o == v && r.updated() < mine.updated()))) rank++;
        }
        return rank;
    }

    public synchronized Row get(String track, UUID player) {
        return rows.getOrDefault(track, Map.of()).get(player);
    }

    /** Wipes records: of a track (null = every track), of a player (null = everyone). Returns how many rows went. */
    public synchronized CompletableFuture<Integer> reset(String track, UUID player) {
        int n = 0;
        for (Map.Entry<String, Map<UUID, Row>> e : rows.entrySet()) {
            if (track != null && !e.getKey().equals(track)) continue;
            if (player == null) {
                n += e.getValue().size();
                e.getValue().clear();
            } else if (e.getValue().remove(player) != null) n++;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM stsv_circuit_records WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (track != null) {
            sql.append(" AND track = ?");
            args.add(track);
        }
        if (player != null) {
            sql.append(" AND player = ?");
            args.add(player.toString());
        }
        int count = n;
        return db.async(c -> Db.update(c, sql.toString(), args.toArray())).thenApply(x -> count);
    }
}
