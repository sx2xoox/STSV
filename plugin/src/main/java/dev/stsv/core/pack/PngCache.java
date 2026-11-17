package dev.stsv.core.pack;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PngOptimizer's results, kept: the re-encoding tries every filter at the strongest compression, which is most of a
 * pack build (a minute for the whole content). A picture is optimised once; the next build in the same server run
 * takes it from memory, and the next server start from the file (plugins/STSV/generated/png-cache.bin).
 *
 * The result for a picture depends only on its bytes and on the optimiser (VERSION), so a cached build is byte for byte
 * the build without the cache.
 */
final class PngCache {

    /** Bumped whenever PngOptimizer could give other bytes for the same picture: older files are ignored. */
    static final int VERSION = 1;
    private static final int MAGIC = 0x53545043; // "STPC"

    private static final Map<String, byte[]> ENTRIES = new ConcurrentHashMap<>();
    /** Pictures asked for since the last save: only these are written, so the file follows the content. */
    private static final Set<String> USED = ConcurrentHashMap.newKeySet();
    private static volatile boolean dirty;
    private static volatile Path loaded;

    private PngCache() {}

    static byte[] optimized(byte[] png) {
        String key = key(png);
        USED.add(key);
        byte[] hit = ENTRIES.get(key);
        if (hit != null) return hit;
        // not inside computeIfAbsent: the optimiser works on several threads, and the map's lock must not be held
        byte[] small = PngOptimizer.optimize(png);
        byte[] earlier = ENTRIES.putIfAbsent(key, small);
        dirty = true;
        return earlier != null ? earlier : small;
    }

    private static String key(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the file once per server run. A missing, older or damaged file is simply not used. */
    static synchronized void load(Path file) {
        if (file.equals(loaded)) return;
        loaded = file;
        try {
            if (!Files.isRegularFile(file)) return;
            byte[] all = Files.readAllBytes(file);
            if (all.length < 32) return;
            byte[] body = Arrays.copyOf(all, all.length - 32);
            if (!Arrays.equals(digest(body), Arrays.copyOfRange(all, all.length - 32, all.length))) return;
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return;
            int n = in.readInt();
            Map<String, byte[]> read = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                String key = in.readUTF();
                int len = in.readInt();
                if (len < 0 || len > in.available()) return;
                read.put(key, in.readNBytes(len));
            }
            read.forEach(ENTRIES::putIfAbsent);
        } catch (IOException | RuntimeException e) {
            // no cache this time: the pictures are optimised again
        }
    }

    /** Writes the pictures of the builds since the last save, if any of them was new or the file is not there. */
    static synchronized void save(Path file) throws IOException {
        Set<String> used = Set.copyOf(USED);
        USED.clear();
        ENTRIES.keySet().retainAll(used);
        if (!dirty && Files.isRegularFile(file)) return;
        dirty = false;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        Map<String, byte[]> sorted = new TreeMap<>(ENTRIES);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(sorted.size());
        for (Map.Entry<String, byte[]> e : sorted.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeInt(e.getValue().length);
            out.write(e.getValue());
        }
        out.flush();
        byte[] body = bytes.toByteArray();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var os = Files.newOutputStream(tmp)) {
            os.write(body);
            os.write(digest(body));
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** For tests: as after a server restart. */
    static synchronized void forget() {
        ENTRIES.clear();
        USED.clear();
        dirty = false;
        loaded = null;
    }

    static int size() {
        return ENTRIES.size();
    }

    private static byte[] digest(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
