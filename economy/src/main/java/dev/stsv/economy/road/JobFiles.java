package dev.stsv.economy.road;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The saved roads (roads/&lt;dock&gt;.txt, {@link RoadJob}, a few hundred KB each) and their undo records
 * (roads/&lt;dock&gt;.undo, {@link RoadUndo}: appended to, frame by frame), written off the main thread: a save
 * hands in a snapshot taken on the main thread and goes on; one writer thread makes the texts and writes them in order,
 * each file's newest only, through a temporary file (a crash mid-write keeps the old one). Until a text is on disk every
 * read here sees it ({@link #read}, {@link #texts}), so a save is never missed by the next read. {@link #close} writes
 * what is left (the plugin stopping). No Bukkit types (unit-tested).
 *
 * <p>The files are named after their dock (서해.txt). A JVM started without a UTF-8 locale (LANG unset, C or POSIX: a
 * new host, a service, a container) cannot turn such a name into a path (InvalidPathException), and lists the files
 * copied from the old host with names it cannot turn back: every road read failed, and the snow guard and the repairs
 * found no road. So the files are listed by their own bytes ({@link #name}: what is on disk, read as UTF-8, whatever the
 * locale) and read by the path listed; a name the JVM cannot spell is found among them; one that is not there yet cannot
 * be made then (warned of once, with the fix: {@link #encodingProblem}) and stays in memory for this run.
 */
final class JobFiles {

    private final Path dir;
    private final Consumer<String> warn;
    /** file name -> its newest text not yet on disk */
    private final Map<String, Text> pending = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "STSV-roads-io");
        t.setDaemon(true);
        return t;
    });

    JobFiles(Path dir, Consumer<String> warn) {
        this.dir = dir;
        this.warn = warn;
    }

    /**
     * Why Korean file names cannot be made on this server, or null: the JVM's file name encoding (sun.jnu.encoding, from
     * the locale it was started in) is not UTF-8.
     */
    static String encodingProblem() {
        String e = System.getProperty("sun.jnu.encoding", "UTF-8");
        return e.equalsIgnoreCase("UTF-8") || e.equalsIgnoreCase("UTF8") ? null : e;
    }

    private volatile boolean warnedEncoding;

    /**
     * A file's path by name: its own where the JVM can spell the name; else the file already there under that name (found
     * by its bytes), or null (none: it cannot be made on this server).
     */
    private Path path(String name) {
        try {
            return dir.resolve(name);
        } catch (InvalidPathException e) {
            Path p = listed().get(name);
            if (p == null && !warnedEncoding) {
                warnedEncoding = true;
                warn.accept("[국도] 한글 이름 파일을 만들 수 없음 (" + name + "): 서버 JVM 의 파일 이름 인코딩이 " + System.getProperty("sun.jnu.encoding")
                        + " 이에요. 서버 시작 스크립트에 LANG=C.UTF-8 (또는 LC_ALL=C.UTF-8)을 넣고 다시 켜세요. 그때까지 새 길 기록은 메모리에만 있어요.");
            }
            return p;
        }
    }

    /** The files in the folder by name ({@link #name}); none when there is no folder. */
    private Map<String, Path> listed() {
        Map<String, Path> out = new java.util.TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            files.forEach(p -> out.put(name(p), p));
        } catch (IOException | RuntimeException e) {
            warn.accept("[국도] 폴더를 읽지 못함: " + dir + ": " + e);
        }
        return out;
    }

    /** A file's name as it is on disk (its bytes, read as UTF-8), whatever the JVM's locale makes of it. */
    static String name(Path p) {
        String raw = p.toUri().getRawPath();
        if (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return decode(raw.substring(raw.lastIndexOf('/') + 1));
    }

    /** %XX escapes (a URI's raw path) to the UTF-8 text they spell. */
    static String decode(String s) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = c == '%' && i + 2 < s.length() ? hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)) : -1;
            if (v >= 0) {
                b.write(v);
                i += 2;
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(u, 0, u.length);
            }
        }
        return b.toString(StandardCharsets.UTF_8);
    }

    /** A hex digit's value; a negative number for anything else. */
    private static int hex(char c) {
        int d = Character.digit(c, 16);
        return d < 0 ? -1000 : d;
    }

    /** A file to be deleted: reads see it gone at once. */
    private static final Text GONE = new Text(() -> null);

    /** A text made once, when first wanted (on the writer thread, or by a read before it). */
    private static final class Text {
        private Supplier<String> make;
        private String text;

        Text(Supplier<String> make) {
            this.make = make;
        }

        synchronized String get() {
            if (text == null) {
                text = make.get();
                make = null;
            }
            return text;
        }
    }

    /**
     * Saves a file (name: "서해.txt") off the main thread: its text is made there too (from a snapshot the caller took);
     * a newer save of the same file replaces one not written yet.
     */
    void write(String name, Supplier<String> text) {
        pending.put(name, new Text(text));
        try {
            io.execute(() -> flush(name));
        } catch (java.util.concurrent.RejectedExecutionException e) {   // closed: write it here
            flush(name);
        }
    }

    private void flush(String name) {
        Text text = pending.get(name);
        if (text == null) return;   // a newer write of it already went
        boolean keep = false;
        try {
            Path f = path(name);
            if (text == GONE) {
                if (f != null) Files.deleteIfExists(f);
                return;
            }
            if (f == null) {   // a name this server cannot make: kept in memory (reads see it) until it can
                keep = true;
                return;
            }
            Files.createDirectories(dir);
            Path tmp = dir.resolve("write-" + Integer.toHexString(name.hashCode()) + ".tmp");   // never a name the locale cannot spell
            Files.writeString(tmp, text.get(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("[국도] 저장 실패: " + name + ": " + e);
        } finally {
            if (!keep) pending.remove(name, text);   // only this text: a newer one stays for its own turn
        }
    }

    /** Deletes a file off the main thread, after what was asked before it; reads see it gone at once. */
    void delete(String name) {
        pending.put(name, GONE);
        run(() -> flush(name));
    }

    /**
     * Appends bytes to a file (made where it is not), off the main thread, in order with every other write and delete: the
     * bytes are made there too (a {@link RoadUndo.Taken} encoded and gzipped).
     */
    void append(String name, Supplier<byte[]> bytes) {
        run(() -> {
            try {
                byte[] b = bytes.get();
                if (b == null || b.length == 0) return;
                Files.createDirectories(dir);
                Path f = path(name);
                if (f == null) return;   // a name this server cannot make (warned)
                Files.write(f, b, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                warn.accept("[국도] 저장 실패: " + name + ": " + e);
            }
        });
    }

    /**
     * A file's bytes, read off the main thread once everything asked before is written (null: none, or unreadable), handed
     * to `then` there (it moves itself to the main thread).
     */
    void readBytes(String name, Consumer<byte[]> then) {
        run(() -> {
            byte[] b = null;
            try {
                Path f = path(name);
                if (f != null && Files.exists(f)) b = Files.readAllBytes(f);
            } catch (IOException | RuntimeException e) {
                warn.accept("[국도] 읽기 실패: " + name + ": " + e);
            }
            then.accept(b);
        });
    }

    /** Whether a file is there (or being written; a delete waiting counts as gone). Appends still waiting are not seen. */
    boolean exists(String name) {
        Text p = pending.get(name);
        if (p != null) return p != GONE;
        Path f = path(name);
        return f != null && Files.exists(f);
    }

    private void run(Runnable r) {
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {   // closed: here
            r.run();
        }
    }

    /** A file's text: the one waiting to be written, else on disk; null: none. */
    String read(String name) throws IOException {
        Text p = pending.get(name);
        if (p == GONE) return null;
        if (p != null) return p.get();
        Path f = path(name);
        return f != null && Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * Every saved road's text (the .txt files but `except` and previews (&lt;dock&gt;.preview.txt: never built), by name),
     * those waiting to be written included.
     */
    List<String> texts(String except) throws IOException {
        return new ArrayList<>(named(except).values());
    }

    /**
     * {@link #texts}, by file name (in order); a file that cannot be read is left out (and warned of) rather than the
     * whole listing failing.
     */
    Map<String, String> named(String except) throws IOException {
        // those waiting first, then the disk (a text is on disk before it stops waiting: none is missed in between), by
        // the names on disk and read by the paths listed (whatever the locale)
        TreeSet<String> names = new TreeSet<>();
        for (String n : pending.keySet()) if (n.endsWith(".txt")) names.add(n);
        Map<String, Path> disk = listed();
        for (String n : disk.keySet()) if (n.endsWith(".txt")) names.add(n);
        names.remove(except);
        names.removeIf(n -> n.endsWith(".preview.txt"));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String n : names) {
            String t;
            try {
                Text p = pending.get(n);
                if (p == GONE) continue;
                Path f = disk.get(n);
                if (p == null && f == null) f = path(n);   // written since the listing
                t = p != null ? p.get() : f != null && Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
            } catch (java.nio.file.NoSuchFileException e) {
                continue;
            } catch (IOException | RuntimeException e) {
                warn.accept("[국도] 읽기 실패: " + n + ": " + e);
                continue;
            }
            if (t != null) out.put(n, t);
        }
        return out;
    }

    /** Writes what is waiting and stops the writer (the plugin stopping). */
    void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) warn.accept("[국도] 저장이 30초 안에 끝나지 않음");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (String n : List.copyOf(pending.keySet())) flush(n);   // anything the writer did not get to
    }
}
