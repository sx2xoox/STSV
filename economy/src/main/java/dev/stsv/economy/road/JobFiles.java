package dev.stsv.economy.road;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
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
        try {
            if (text == GONE) {
                Files.deleteIfExists(dir.resolve(name));
                return;
            }
            Files.createDirectories(dir);
            Path f = dir.resolve(name), tmp = dir.resolve(name + ".tmp");
            Files.writeString(tmp, text.get(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("[국도] 저장 실패: " + name + ": " + e);
        } finally {
            pending.remove(name, text);   // only this text: a newer one stays for its own turn
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
                Files.write(dir.resolve(name), b, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
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
                Path f = dir.resolve(name);
                if (Files.exists(f)) b = Files.readAllBytes(f);
            } catch (IOException e) {
                warn.accept("[국도] 읽기 실패: " + name + ": " + e);
            }
            then.accept(b);
        });
    }

    /** Whether a file is there (or being written; a delete waiting counts as gone). Appends still waiting are not seen. */
    boolean exists(String name) {
        Text p = pending.get(name);
        if (p != null) return p != GONE;
        return Files.exists(dir.resolve(name));
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
        Path f = dir.resolve(name);
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * Every saved road's text (the .txt files but `except` and previews (&lt;dock&gt;.preview.txt: never built), by name),
     * those waiting to be written included.
     */
    List<String> texts(String except) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).forEach(names::add);
            }
        }
        for (String n : pending.keySet()) if (n.endsWith(".txt")) names.add(n);
        names.remove(except);
        names.removeIf(n -> n.endsWith(".preview.txt"));
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String t;
            try {
                t = read(n);
            } catch (java.nio.file.NoSuchFileException e) {
                continue;
            }
            if (t != null) out.add(t);
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
