package dev.stsv.core.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks a finished backup before it is trusted: the database copy opens and SQLite finds nothing wrong in it
 * (quick_check), and every world region file holds together: its two header sectors are there and every chunk the
 * header lists lies inside the file with its whole length. (The file's size need not be a whole number of 4 KiB
 * sectors: the server does not pad the last chunk, so that was no sign of damage and warned about sound files.) A backup that fails is kept but marked, and the administrators are told: an untested
 * backup is the one that turns out not to open on the day it is needed.
 */
public final class Verify {

    private Verify() {}

    /** What is wrong with the backup (empty: nothing found). db: the database copy, or null when there is none. */
    public static List<String> check(Path backup, Path db) {
        List<String> problems = new ArrayList<>();
        if (db != null) {
            if (!Files.isRegularFile(db)) problems.add("DB 복사본이 없음");
            else {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath()); Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                    String first = rs.next() ? rs.getString(1) : "?";
                    if (!"ok".equalsIgnoreCase(first)) problems.add("DB 검사: " + first);
                } catch (Exception e) {
                    problems.add("DB 를 열 수 없음: " + e.getMessage());
                }
            }
        }
        try (Stream<Path> all = Files.walk(backup)) {
            for (Path p : all.filter(f -> f.getFileName().toString().endsWith(".mca")).toList()) {
                String bad = region(p);
                if (bad != null) problems.add("지역 파일이 깨짐: " + backup.relativize(p) + " (" + bad + ")");
                if (problems.size() > 10) break;
            }
        } catch (IOException e) {
            problems.add("폴더를 읽지 못함: " + e.getMessage());
        }
        return problems;
    }

    /** What is wrong with a region file, or null. An empty file is a region nothing was written to yet. */
    static String region(Path p) throws IOException {
        long size = Files.size(p);
        if (size == 0) return null;
        if (size < 8192) return "머리 부분이 모자람, " + size + " B";
        try (var ch = java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(4096);
            while (head.hasRemaining() && ch.read(head, head.position()) > 0) { }
            head.flip();
            java.nio.ByteBuffer len = java.nio.ByteBuffer.allocate(4);
            for (int i = 0; i < 1024; i++) {
                int entry = head.getInt(i * 4);
                if (entry == 0) continue;
                long offset = (entry >>> 8) * 4096L;
                int sectors = entry & 0xFF;
                if (offset < 8192 || offset + 5 > size) return "청크 " + i + " 가 파일 밖을 가리킴";
                len.clear();
                ch.read(len, offset);
                len.flip();
                int length = len.getInt();
                // (a chunk too big for its sectors is kept in its own .mcc file: its length here is 1)
                if (length < 1 || (sectors > 0 && length > sectors * 4096L) || offset + 4 + length > size) {
                    return "청크 " + i + " 가 잘림";
                }
            }
        }
        return null;
    }
}
