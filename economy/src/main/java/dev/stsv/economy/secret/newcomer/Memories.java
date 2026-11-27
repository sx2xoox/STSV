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
    /** the table was read (with db null: always); a read that failed is tried again by {@link #ready} now and then */
    private volatile boolean loaded, loading;
    private volatile long failedAt;

    /** db: null keeps everything in memory only (tests). */
    Memories(Db db) {
        this.db = db;
        this.loaded = db == null;
    }

    /**
     * Whether everyone he remembers is known (the table has been read). The letters wait for this: right after a start the
     * read may not be back yet, and a letter round sent then would reach nobody. A failed read is tried again a minute on.
     */
    boolean ready() {
        if (!loaded && !loading && db != null && System.currentTimeMillis() - failedAt > 60_000L) load();
        return loaded;
    }

    void init() {
        if (db == null) return;
        db.migrate("eco_newcomer_memory", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_newcomer_memory (player CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                        + "answered INT NOT NULL, teased INT NOT NULL, rude INT NOT NULL, whispers INT NOT NULL, shards INT NOT NULL, "
                        + "named INT NOT NULL, signed INT NOT NULL, notes VARCHAR(255) NOT NULL, updated BIGINT NOT NULL)")));
        load();
    }

    void load() {
        loading = true;
        try {
            db.async(c -> Db.query(c, "SELECT player, name, answered, teased, rude, whispers, shards, named, signed, notes FROM eco_newcomer_memory",
                    rs -> Map.entry(UUID.fromString(rs.getString(1)), new Note(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getInt(7), rs.getInt(8) != 0, rs.getInt(9) != 0, rs.getString(10)))))
                    .whenComplete((rows, e) -> {
                        if (e == null && rows != null) {
                            synchronized (this) {
                                rows.forEach(x -> notes.putIfAbsent(x.getKey(), x.getValue()));
                                loaded = true;
                                for (Waiting w : waiting) apply(w.u(), w.name(), w.change());
                                waiting.clear();
                            }
                        } else {
                            failedAt = System.currentTimeMillis();
                        }
                        loading = false;
                    });
        } catch (RuntimeException e) {
            failedAt = System.currentTimeMillis();
            loading = false;
        }
    }

    /**
     * The secret is opened anew (서버초기화): what people did for his record (shards, the name, the signature, and the notes
     * of it) is forgotten; that they spoke to him, answered, teased or swore stays. A rehearsal on the live server followed
     * by 서버초기화 leaves no "증인" or "조각" behind for the real night's letters and prompts.
     */
    void forgetRecord() {
        if (db != null) db.execute("UPDATE eco_newcomer_memory SET shards = 0, named = 0, signed = 0");   // rows not read yet too
        for (Map.Entry<UUID, Note> e : all().entrySet()) {
            Note n = e.getValue();
            if (n.shards() == 0 && !n.named() && !n.signed() && strip(n.notes()).equals(n.notes())) continue;
            Note k = new Note(n.name(), n.answered(), n.teased(), n.rude(), n.whispers(), 0, false, false, strip(n.notes()));
            notes.put(e.getKey(), k);
            if (db != null) save(e.getKey(), k);
        }
    }

    /** The note without what came of his record ("조각을 넣어 줌 …", "이름을 새겨 줌", "증인으로 서명함", "비석을 찾아 줌"). */
    static String strip(String notes) {
        if (notes == null || notes.isBlank()) return "";
        java.util.List<String> keep = new java.util.ArrayList<>();
        for (String part : notes.split(" / ")) {
            String t = part.strip();
            if (t.isEmpty() || t.startsWith("조각을 넣어 줌") || t.equals("이름을 새겨 줌") || t.equals("증인으로 서명함") || t.equals("비석을 찾아 줌")) continue;
            keep.add(t);
        }
        return String.join(" / ", keep);
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

    /**
     * Something about a player changes. Before the table is read (the first moments after a start) the change waits and is
     * applied on top of what was read: a row is never written from nothing over what was saved (a whisper right after a
     * restart used to reset someone's counts, and with them the letter they would get).
     */
    void update(UUID u, String name, UnaryOperator<Note> change) {
        synchronized (this) {
            if (!loaded) {
                waiting.add(new Waiting(u, name, change));
                return;
            }
        }
        apply(u, name, change);
    }

    private record Waiting(UUID u, String name, UnaryOperator<Note> change) {}

    private final List<Waiting> waiting = new java.util.ArrayList<>();

    private void apply(UUID u, String name, UnaryOperator<Note> change) {
        Note n = change.apply(of(u).name(name));
        notes.put(u, n);
        if (db == null) return;
        save(u, n);
    }

    private void save(UUID u, Note n) {
        db.execute(db.upsert("eco_newcomer_memory", List.of("player"),
                        List.of("name", "answered", "teased", "rude", "whispers", "shards", "named", "signed", "notes", "updated")),
                u.toString(), n.name(), n.answered(), n.teased(), n.rude(), n.whispers(), n.shards(), n.named() ? 1 : 0, n.signed() ? 1 : 0,
                n.notes(), System.currentTimeMillis());
    }

    void forget(UUID u) {
        synchronized (this) {
            waiting.removeIf(w -> w.u().equals(u));
        }
        notes.remove(u);
        if (db != null) db.execute("DELETE FROM eco_newcomer_memory WHERE player = ?", u.toString());
    }
}
