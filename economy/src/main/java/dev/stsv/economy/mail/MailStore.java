package dev.stsv.economy.mail;

import dev.stsv.core.data.Db;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Items waiting for a player (expired listings, auction wins, evicted stalls, full inventories). */
public final class MailStore {

    public record Mail(long id, UUID player, byte[] item, String reason, long at) {}

    private final Db db;

    public MailStore(Db db) {
        this.db = db;
    }

    public static String blob(Db db) {
        return db.dialect() == Db.Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB";
    }

    public static String autoId(Db db) {
        return db.dialect() == Db.Dialect.MYSQL ? "id BIGINT PRIMARY KEY AUTO_INCREMENT" : "id INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    public void init() {
        db.migrate("eco_mail", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_mail (" + autoId(db) + ", player CHAR(36) NOT NULL, item " + blob(db) + " NOT NULL, "
                        + "reason VARCHAR(64) NOT NULL, at BIGINT NOT NULL)",
                "CREATE INDEX eco_mail_player ON eco_mail (player)")));
    }

    public CompletableFuture<Void> send(UUID player, byte[] item, String reason, long now) {
        return db.execute("INSERT INTO eco_mail (player, item, reason, at) VALUES (?, ?, ?, ?)", player.toString(), item, reason, now);
    }

    public CompletableFuture<List<Mail>> list(UUID player) {
        return db.async(c -> Db.query(c, "SELECT id, player, item, reason, at FROM eco_mail WHERE player = ? ORDER BY id",
                rs -> new Mail(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getBytes(3), rs.getString(4), rs.getLong(5)), player.toString()));
    }

    public CompletableFuture<Integer> count(UUID player) {
        return db.async(c -> Db.query(c, "SELECT COUNT(*) FROM eco_mail WHERE player = ?", rs -> rs.getInt(1), player.toString()).getFirst());
    }

    /** Takes one mail out, once (true only for the first taker: a double click cannot duplicate it). */
    public CompletableFuture<Boolean> take(long id, UUID player) {
        return db.async(c -> Db.update(c, "DELETE FROM eco_mail WHERE id = ? AND player = ?", id, player.toString()) == 1);
    }

    public CompletableFuture<Void> reset() {
        return db.execute("DELETE FROM eco_mail");
    }
}
