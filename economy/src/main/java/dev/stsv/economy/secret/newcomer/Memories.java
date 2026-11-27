package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.data.Db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * What he remembers of each account, in a few numbers and one short note (table eco_newcomer_memory): who answered his
 * questions, who made fun of him, who swore, who whispered, who brought shards, who engraved his name, who signed.
 * Whoever has a row has spoken to him: they get his letter the night after he leaves ({@link Letters}). Read once at
 * start and kept in memory; a change is written behind.
 */
final class Memories {

    /** One account: its name as he heard it, the counts, and a note (the last few things, in a line). */
    record Note(String name, int answered, int teased, int rude, int whispers, int shards, boolean named, boolean signed, String notes) {

        static final Note NEW = new Note("", 0, 0, 0, 0, 0, false, false, "");

        Note name(String n) {
            return new Note(n == null ? name : n, answered, teased, rude, whispers, shards, named, signed, notes);
        }

        Note plusAnswer() {
            return new Note(name, answered + 1, teased, rude, whispers, shards, named, signed, notes);
        }

        Note plusTease() {
            return new Note(name, answered, teased + 1, rude, whispers, shards, named, signed, notes);
        }

        Note plusRude() {
            return new Note(name, answered, teased, rude + 1, whispers, shards, named, signed, notes);
        }

        Note plusWhisper() {
            return new Note(name, answered, teased, rude, whispers + 1, shards, named, signed, notes);
        }

        Note plusShard() {
            return new Note(name, answered, teased, rude, whispers, shards + 1, named, signed, notes);
        }

        Note engraved() {
            return new Note(name, answered, teased, rude, whispers, shards, true, signed, notes);
        }

        Note witnessed() {
            return new Note(name, answered, teased, rude, whispers, shards, named, true, notes);
        }

        /** One more thing in the note (the newest last; the oldest fall off past 160 characters). */
        Note note(String more) {
            if (more == null || more.isBlank()) return this;
            String n = notes.isEmpty() ? more.strip() : notes + " / " + more.strip();
            while (n.length() > 160 && n.contains(" / ")) n = n.substring(n.indexOf(" / ") + 3);
            if (n.length() > 160) n = n.substring(n.length() - 160);
            return new Note(name, answered, teased, rude, whispers, shards, named, signed, n);
        }
    }

    /** What someone was to him, for his letter: the first that fits. */
    enum Kind { WITNESS, NAMER, GIVER, RUDE, TEASER, ANSWERER, TALKER }

    static Kind kind(Note n) {
        if (n.signed()) return Kind.WITNESS;
        if (n.named()) return Kind.NAMER;
        if (n.shards() > 0) return Kind.GIVER;
        if (n.rude() > 0) return Kind.RUDE;
        if (n.teased() > 0) return Kind.TEASER;
        if (n.answered() > 0) return Kind.ANSWERER;
        return Kind.TALKER;
    }

    /** The notes as the model reads them (and an admin's 기억). */
    static String brief(Note n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (n.answered() > 0) out.add("질문에 답해 준 사람 (" + n.answered() + "번)");
        if (n.teased() > 0) out.add("봇이냐고 놀린 적 있음 (" + n.teased() + "번)");
        if (n.rude() > 0) out.add("욕을 한 적 있음 (" + n.rude() + "번)");
        if (n.whispers() > 0) out.add("귓속말을 건 사람 (" + n.whispers() + "번)");
        if (n.shards() > 0) out.add("깨진 조각을 넣어 준 사람 (" + n.shards() + "개)");
        if (n.named()) out.add("비석에 이름을 새겨 준 사람");
        if (n.signed()) out.add("증인이 되어 준 사람");
        if (!n.notes().isBlank()) out.add("메모: " + n.notes());
        return out.isEmpty() ? "처음 보는 사람" : String.join(" · ", out);
    }

    private final Db db;
    private final Map<UUID, Note> notes = new ConcurrentHashMap<>();

    /** db: null keeps everything in memory only (tests). */
    Memories(Db db) {
        this.db = db;
    }

    void init() {
        if (db == null) return;
        db.migrate("eco_newcomer_memory", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_newcomer_memory (player CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                        + "answered INT NOT NULL, teased INT NOT NULL, rude INT NOT NULL, whispers INT NOT NULL, shards INT NOT NULL, "
                        + "named INT NOT NULL, signed INT NOT NULL, notes VARCHAR(255) NOT NULL, updated BIGINT NOT NULL)")));
        db.async(c -> Db.query(c, "SELECT player, name, answered, teased, rude, whispers, shards, named, signed, notes FROM eco_newcomer_memory",
                rs -> Map.entry(UUID.fromString(rs.getString(1)), new Note(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getInt(8) != 0, rs.getInt(9) != 0, rs.getString(10)))))
                .thenAccept(rows -> rows.forEach(e -> notes.putIfAbsent(e.getKey(), e.getValue())));
    }

    Note of(UUID u) {
        return notes.getOrDefault(u, Note.NEW);
    }

    boolean knows(UUID u) {
        return notes.containsKey(u);
    }

    /** Everyone who has ever spoken to him. */
    Map<UUID, Note> all() {
        return new LinkedHashMap<>(notes);
    }

    void update(UUID u, String name, UnaryOperator<Note> change) {
        Note n = change.apply(of(u).name(name));
        notes.put(u, n);
        if (db == null) return;
        db.execute(db.upsert("eco_newcomer_memory", List.of("player"),
                        List.of("name", "answered", "teased", "rude", "whispers", "shards", "named", "signed", "notes", "updated")),
                u.toString(), n.name(), n.answered(), n.teased(), n.rude(), n.whispers(), n.shards(), n.named() ? 1 : 0, n.signed() ? 1 : 0,
                n.notes(), System.currentTimeMillis());
    }

    void forget(UUID u) {
        notes.remove(u);
        if (db != null) db.execute("DELETE FROM eco_newcomer_memory WHERE player = ?", u.toString());
    }
}
