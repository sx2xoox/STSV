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
 * (quick_check), and every world region file has the shape a region file must have (a whole number of 4 KiB sectors,
 * with its two header sectors). A backup that fails is kept but marked, and the administrators are told: an untested
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
                long size = Files.size(p);
                if (size != 0 && (size < 8192 || size % 4096 != 0)) problems.add("지역 파일 모양이 이상함: " + backup.relativize(p) + " (" + size + " B)");
                if (problems.size() > 10) break;
            }
        } catch (IOException e) {
            problems.add("폴더를 읽지 못함: " + e.getMessage());
        }
        return problems;
    }
}
