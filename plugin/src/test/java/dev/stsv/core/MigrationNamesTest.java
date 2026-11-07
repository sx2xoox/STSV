package dev.stsv.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No table or column the core or the economy plugin creates is a reserved word in MySQL 8 / MariaDB. SQLite
 * takes them (so the other tests stay green), but on a {@code storage: mysql} server the migration fails and
 * the module never starts ("natural" did that to the crop store). Reads the SQL string literals of the sources:
 * the names after ADD COLUMN, after CREATE TABLE and in its column list. Parts built by code (an id column
 * per dialect) are not seen.
 */
class MigrationNamesTest {

    private static final Path CORE = Path.of("src/main/java");
    private static final Path ECONOMY = Path.of("../economy/src/main/java");

    /** Reserved words of MySQL 8.0 / 8.4 / 9 and MariaDB (not the non-reserved keywords: those work as names). */
    static final Set<String> RESERVED = Set.of(
            "ACCESSIBLE", "ADD", "ALL", "ALTER", "ANALYZE", "AND", "ARRAY", "AS", "ASC", "ASENSITIVE", "BEFORE", "BETWEEN",
            "BIGINT", "BINARY", "BLOB", "BOTH", "BY", "CALL", "CASCADE", "CASE", "CHANGE", "CHAR", "CHARACTER", "CHECK",
            "COLLATE", "COLUMN", "CONDITION", "CONSTRAINT", "CONTINUE", "CONVERT", "CREATE", "CROSS", "CUBE", "CUME_DIST",
            "CURRENT_DATE", "CURRENT_ROLE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURSOR", "DATABASE",
            "DATABASES", "DAY_HOUR", "DAY_MICROSECOND", "DAY_MINUTE", "DAY_SECOND", "DEC", "DECIMAL", "DECLARE", "DEFAULT",
            "DELAYED", "DELETE", "DELETE_DOMAIN_ID", "DENSE_RANK", "DESC", "DESCRIBE", "DETERMINISTIC", "DISTINCT",
            "DISTINCTROW", "DIV", "DO_DOMAIN_IDS", "DOUBLE", "DROP", "DUAL", "EACH", "ELSE", "ELSEIF", "EMPTY", "ENCLOSED",
            "ESCAPED", "EXCEPT", "EXISTS", "EXIT", "EXPLAIN", "FALSE", "FETCH", "FIRST_VALUE", "FLOAT", "FLOAT4", "FLOAT8",
            "FOR", "FORCE", "FOREIGN", "FROM", "FULLTEXT", "FUNCTION", "GENERAL", "GENERATED", "GET", "GRANT", "GROUP",
            "GROUPING", "GROUPS", "HAVING", "HIGH_PRIORITY", "HOUR_MICROSECOND", "HOUR_MINUTE", "HOUR_SECOND", "IF",
            "IGNORE", "IGNORE_DOMAIN_IDS", "IGNORE_SERVER_IDS", "IN", "INDEX", "INFILE", "INNER", "INOUT", "INSENSITIVE",
            "INSERT", "INT", "INT1", "INT2", "INT3", "INT4", "INT8", "INTEGER", "INTERSECT", "INTERVAL", "INTO",
            "IO_AFTER_GTIDS", "IO_BEFORE_GTIDS", "IS", "ITERATE", "JOIN", "JSON_TABLE", "KEY", "KEYS", "KILL", "LAG",
            "LAST_VALUE", "LATERAL", "LEAD", "LEADING", "LEAVE", "LEFT", "LIBRARY", "LIKE", "LIMIT", "LINEAR", "LINES",
            "LOAD", "LOCALTIME", "LOCALTIMESTAMP", "LOCK", "LONG", "LONGBLOB", "LONGTEXT", "LOOP", "LOW_PRIORITY", "MANUAL",
            "MASTER_BIND", "MASTER_HEARTBEAT_PERIOD", "MASTER_SSL_VERIFY_SERVER_CERT", "MATCH", "MAXVALUE", "MEDIUMBLOB",
            "MEDIUMINT", "MEDIUMTEXT", "MEMBER", "MIDDLEINT", "MINUTE_MICROSECOND", "MINUTE_SECOND", "MOD", "MODIFIES",
            "NATURAL", "NO_WRITE_TO_BINLOG", "NOT", "NTH_VALUE", "NTILE", "NULL", "NUMERIC", "OF", "OFFSET", "ON",
            "OPTIMIZE", "OPTIMIZER_COSTS", "OPTION", "OPTIONALLY", "OR", "ORDER", "OUT", "OUTER", "OUTFILE", "OVER",
            "PAGE_CHECKSUM", "PARALLEL", "PARSE_VCOL_EXPR", "PARTITION", "PERCENT_RANK", "POSITION", "PRECISION",
            "PRIMARY", "PROCEDURE", "PURGE", "QUALIFY", "RANGE", "RANK", "READ", "READ_WRITE", "READS", "REAL", "RECURSIVE",
            "REF_SYSTEM_ID", "REFERENCES", "REGEXP", "RELEASE", "RENAME", "REPEAT", "REPLACE", "REQUIRE", "RESIGNAL",
            "RESTRICT", "RETURN", "RETURNING", "REVOKE", "RIGHT", "RLIKE", "ROW", "ROW_NUMBER", "ROWS", "SCHEMA", "SCHEMAS",
            "SECOND_MICROSECOND", "SELECT", "SENSITIVE", "SEPARATOR", "SET", "SHOW", "SIGNAL", "SLOW", "SMALLINT",
            "SPATIAL", "SPECIFIC", "SQL", "SQL_BIG_RESULT", "SQL_CALC_FOUND_ROWS", "SQL_SMALL_RESULT", "SQLEXCEPTION",
            "SQLSTATE", "SQLWARNING", "SSL", "STARTING", "STATS_AUTO_RECALC", "STATS_PERSISTENT", "STATS_SAMPLE_PAGES",
            "STORED", "STRAIGHT_JOIN", "SYSTEM", "TABLE", "TABLESAMPLE", "TERMINATED", "THEN", "TINYBLOB", "TINYINT",
            "TINYTEXT", "TO", "TRAILING", "TRIGGER", "TRUE", "UNDO", "UNION", "UNIQUE", "UNLOCK", "UNSIGNED", "UPDATE",
            "USAGE", "USE", "USING", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP", "VALUES", "VARBINARY", "VARCHAR",
            "VARCHARACTER", "VARYING", "VIRTUAL", "WHEN", "WHERE", "WHILE", "WINDOW", "WITH", "WRITE", "XOR", "YEAR_MONTH",
            "ZEROFILL");

    /** What starts a table constraint instead of a column in a CREATE TABLE list. */
    private static final Set<String> CONSTRAINTS = Set.of("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "CHECK",
            "FULLTEXT", "SPATIAL");
    private static final Pattern ADD_COLUMN = Pattern.compile("(?i)\\bALTER\\s+TABLE\\s+([A-Za-z_]\\w*)\\s+ADD\\s+COLUMN\\s+([A-Za-z_]\\w*)");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern NAME = Pattern.compile("^([A-Za-z_]\\w*)");

    /** The names one piece of SQL creates, as "table.column" (or "table" for the table itself). */
    static List<String> names(String sql) {
        List<String> out = new ArrayList<>();
        Matcher add = ADD_COLUMN.matcher(sql);
        while (add.find()) out.add(add.group(1) + "." + add.group(2));
        Matcher create = CREATE_TABLE.matcher(sql);
        while (create.find()) {
            String table = create.group(1);
            out.add(table);
            int depth = 1, start = create.end(), i = start;
            List<String> parts = new ArrayList<>();
            for (; i < sql.length() && depth > 0; i++) {
                char ch = sql.charAt(i);
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                if (depth == 0 || depth == 1 && ch == ',') {
                    parts.add(sql.substring(start, i).trim());
                    start = i + 1;
                }
            }
            for (String part : parts) {
                Matcher n = NAME.matcher(part);
                if (n.find() && !CONSTRAINTS.contains(n.group(1).toUpperCase(Locale.ROOT))) out.add(table + "." + n.group(1));
            }
        }
        return out;
    }

    static boolean reserved(String name) {
        return RESERVED.contains(name.substring(name.indexOf('.') + 1).toUpperCase(Locale.ROOT));
    }

    /**
     * The string literals of a Java source, those joined by + into one string (an operand that is not a literal
     * becomes "?"). Comments, char literals and text blocks are handled; anything fancier is not needed here.
     */
    static List<String> strings(String src) {
        List<String> out = new ArrayList<>();
        StringBuilder current = null, between = new StringBuilder();
        int i = 0, n = src.length();
        while (i < n) {
            char ch = src.charAt(i);
            if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (ch == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != '\'') j += src.charAt(j) == '\\' ? 2 : 1;
                between.append(src, i, Math.min(n, j + 1));
                i = j + 1;
            } else if (ch == '"') {
                boolean block = src.startsWith("\"\"\"", i);
                StringBuilder lit = new StringBuilder();
                int j = i + (block ? 3 : 1);
                while (j < n && !(block ? src.startsWith("\"\"\"", j) : src.charAt(j) == '"')) {
                    if (src.charAt(j) == '\\' && j + 1 < n) j++;
                    lit.append(src.charAt(j++));
                }
                i = j + (block ? 3 : 1);
                String glue = between.toString().trim();
                if (current != null && glue.startsWith("+") && glue.endsWith("+")) {
                    String operand = glue.length() > 1 ? glue.substring(1, glue.length() - 1).trim() : "";
                    if (!operand.isEmpty()) current.append('?');
                } else {
                    if (current != null) out.add(current.toString());
                    current = new StringBuilder();
                }
                current.append(lit);
                between.setLength(0);
            } else {
                between.append(ch);
                i++;
            }
        }
        if (current != null) out.add(current.toString());
        return out;
    }

    /** "File: table.column" for every name the SQL in a source tree creates; the reserved ones go to {@code bad}. */
    private static List<String> scan(Path root, List<String> bad) throws Exception {
        List<String> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                for (String s : strings(Files.readString(f))) {
                    for (String name : names(s)) {
                        all.add(name);
                        if (reserved(name)) bad.add(f.getFileName() + ": " + name);
                    }
                }
            }
        }
        return all;
    }

    @Test
    void theCheckSeesReservedNames() {
        assertEquals(List.of("crop.natural"), names("ALTER TABLE crop ADD COLUMN natural INT NOT NULL DEFAULT 0"));
        assertTrue(reserved("crop.natural"));
        List<String> made = names("CREATE TABLE IF NOT EXISTS t (id INT NOT NULL, rank INT NOT NULL, v DECIMAL(10, 2), PRIMARY KEY (id, rank))");
        assertEquals(List.of("t", "t.id", "t.rank", "t.v"), made);
        assertEquals(List.of("t.rank"), made.stream().filter(MigrationNamesTest::reserved).toList());
        // literals joined by +, a computed column in between; comments and other strings stay apart
        String src = "db.migrate(\"m\", List.of(\"CREATE TABLE x (\" + id + \", item \" + blob(db) + \" NOT NULL, \"\n"
                + "        + \"rows INT)\", // \"order\" is not SQL\n \"DROP TABLE y\"));";
        assertEquals(List.of("m", "CREATE TABLE x (?, item ? NOT NULL, rows INT)", "DROP TABLE y"), strings(src));
        assertEquals(List.of("x", "x.item", "x.rows"), names(strings(src).get(1)));
    }

    @Test
    void coreNamesAreNotReserved() throws Exception {
        List<String> bad = new ArrayList<>();
        List<String> all = scan(CORE, bad);
        assertTrue(all.contains("crop.grown") && all.contains("ledger.reverted") && all.size() > 60, "found " + all);
        assertTrue(bad.isEmpty(), "MySQL / MariaDB reserved words:\n" + String.join("\n", bad));
    }

    @Test
    void economyNamesAreNotReserved() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(ECONOMY), "the economy plugin's sources are next to the core's");
        List<String> bad = new ArrayList<>();
        List<String> all = scan(ECONOMY, bad);
        assertTrue(all.contains("eco_state.k") && all.contains("eco_stock_candle.c") && all.size() > 150, "found " + all);
        assertTrue(bad.isEmpty(), "MySQL / MariaDB reserved words:\n" + String.join("\n", bad));
    }
}
