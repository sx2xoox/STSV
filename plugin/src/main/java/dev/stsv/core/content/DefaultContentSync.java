package dev.stsv.core.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps contents/ in step with the content bundled in the plugin jar without ever losing local edits.
 *
 * contents/.default-content records the hash of every file this class wrote. A file on disk is
 * "untouched" if its hash equals that record, or any version we ever shipped (history, for servers
 * installed before the record existed). Untouched files are upgraded; edited files are kept.
 */
public final class DefaultContentSync {

    /**
     * @param kept    files we would have updated or removed, but the owner edited them
     * @param removed files that are no longer part of the default content and were deleted
     */
    public record Report(int installed, int updated, List<String> kept, int removed) {}

    private DefaultContentSync() {}

    public static Report sync(Path contentsDir, Map<String, byte[]> bundled, Map<String, Set<String>> history)
            throws IOException {
        Files.createDirectories(contentsDir);
        Path manifestFile = contentsDir.resolve(".default-content");
        Map<String, String> manifest = new LinkedHashMap<>();
        if (Files.exists(manifestFile)) {
            for (String line : Files.readAllLines(manifestFile)) {
                int sep = line.indexOf(' ');
                if (sep > 0) manifest.put(line.substring(sep + 1), line.substring(0, sep));
            }
        }

        int installed = 0, updated = 0;
        List<String> kept = new ArrayList<>();
        Map<String, String> next = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : bundled.entrySet()) {
            String rel = e.getKey();
            Path target = contentsDir.resolve(rel).normalize();
            if (!target.startsWith(contentsDir)) continue;
            String newHash = sha1(e.getValue());
            if (Files.exists(target)) {
                String current = sha1(Files.readAllBytes(target));
                if (current.equals(newHash)) {
                    next.put(rel, newHash);
                    continue;
                }
                boolean untouched = current.equals(manifest.get(rel))
                        || history.getOrDefault(rel, Set.of()).contains(current);
                if (!untouched) {
                    kept.add(rel);
                    // remember what we would have written, so the file stays "edited" next time too
                    next.put(rel, manifest.getOrDefault(rel, newHash));
                    continue;
                }
                updated++;
            } else {
                installed++;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
            next.put(rel, newHash);
        }

        // files we shipped before but no longer ship: delete them unless the owner edited them
        int removed = 0;
        java.util.Set<String> known = new java.util.TreeSet<>(manifest.keySet());
        known.addAll(history.keySet());
        for (String rel : known) {
            if (bundled.containsKey(rel)) continue;
            Path target = contentsDir.resolve(rel).normalize();
            if (!target.startsWith(contentsDir) || !Files.isRegularFile(target)) continue;
            String current = sha1(Files.readAllBytes(target));
            boolean untouched = current.equals(manifest.get(rel))
                    || history.getOrDefault(rel, Set.of()).contains(current);
            if (untouched) {
                Files.delete(target);
                removed++;
            } else {
                kept.add(rel);
            }
        }

        StringBuilder sb = new StringBuilder();
        next.forEach((rel, hash) -> sb.append(hash).append(' ').append(rel).append('\n'));
        Files.writeString(manifestFile, sb.toString());
        return new Report(installed, updated, kept, removed);
    }

    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
