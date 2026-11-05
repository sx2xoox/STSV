package dev.stsv.economy.event;

import dev.stsv.core.data.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Running events, kept in the database so a restart does not end them. */
public final class EventStore {

    private final Db db;
    private final List<ActiveEvent> active = new CopyOnWriteArrayList<>();

    public EventStore(Db db) {
        this.db = db;
    }

    public void init() {
        String id = db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        db.migrate("eco_event", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_event (" + id + ", type VARCHAR(32) NOT NULL, started BIGINT NOT NULL, ends BIGINT NOT NULL, "
                        + "params TEXT, active INT NOT NULL)")));
        active.clear();
        active.addAll(db.sync(c -> Db.query(c, "SELECT id, type, started, ends, params FROM eco_event WHERE active = 1",
                rs -> new ActiveEvent(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), ActiveEvent.decode(rs.getString(5))))));
    }

    public List<ActiveEvent> active() {
        return List.copyOf(active);
    }

    public List<ActiveEvent> active(String type, long now) {
        List<ActiveEvent> out = new ArrayList<>();
        for (ActiveEvent e : active) if (e.type().equals(type) && e.runningAt(now)) out.add(e);
        return out;
    }

    public ActiveEvent start(String type, long started, long ends, Map<String, String> params) {
        long id = db.sync(c -> {
            return Db.insert(c, "INSERT INTO eco_event (type, started, ends, params, active) VALUES (?, ?, ?, ?, 1)", type, started, ends, ActiveEvent.encode(params));
        });
        ActiveEvent e = new ActiveEvent(id, type, started, ends, Map.copyOf(params));
        active.add(e);
        return e;
    }

    /** Ends events whose time is up (or all of a type when type != null); returns the ended ones. */
    public List<ActiveEvent> end(long now, String type) {
        List<ActiveEvent> ended = new ArrayList<>();
        for (ActiveEvent e : active) {
            if (type != null ? e.type().equals(type) : now >= e.ends()) ended.add(e);
        }
        active.removeAll(ended);
        for (ActiveEvent e : ended) db.execute("UPDATE eco_event SET active = 0, ends = ? WHERE id = ?", Math.min(now, e.ends()), e.id());
        return ended;
    }

    public void reset() {
        active.clear();
        db.execute("DELETE FROM eco_event");
    }
}
