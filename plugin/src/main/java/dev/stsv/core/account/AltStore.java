package dev.stsv.core.account;

import dev.stsv.core.data.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Who owns which address (as {@link AltRules#hash}) and which accounts an admin allowed on a shared address. No Bukkit
 * types (unit-tested on SQLite). {@link #login} reads and claims in one piece of work on the database thread, so two
 * accounts logging in from one address at the same moment cannot both claim it.
 */
public final class AltStore {

    public record Allowed(UUID player, String name, String by, long at) {}

    public record Login(AltRules.Verdict verdict, AltRules.Owner owner) {}

    private final Db db;

    public AltStore(Db db) {
        this.db = db;
    }

    public void init() {
        db.migrate("alts", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS stsv_ip_owner (ip_hash CHAR(64) NOT NULL PRIMARY KEY, owner CHAR(36) NOT NULL, "
                        + "owner_name VARCHAR(16) NOT NULL, claimed_at BIGINT NOT NULL, last_seen BIGINT NOT NULL)",
                "CREATE INDEX stsv_ip_owner_by ON stsv_ip_owner (owner)",
                "CREATE TABLE IF NOT EXISTS stsv_alt_allow (player CHAR(36) NOT NULL PRIMARY KEY, player_name VARCHAR(16) NOT NULL, "
                        + "added_by VARCHAR(16) NOT NULL, added_at BIGINT NOT NULL)")));
    }

    private static AltRules.Owner owner(Connection c, String hash) throws SQLException {
        List<AltRules.Owner> r = Db.query(c, "SELECT owner, owner_name, last_seen FROM stsv_ip_owner WHERE ip_hash = ?",
                rs -> new AltRules.Owner(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)), hash);
        return r.isEmpty() ? null : r.getFirst();
    }

    private static boolean allowed(Connection c, UUID player) throws SQLException {
        return !Db.query(c, "SELECT 1 FROM stsv_alt_allow WHERE player = ?", rs -> 1, player.toString()).isEmpty();
    }

    /**
     * A login from an address: decides, and on CLAIM takes the address for the account (on OWNER notes it was used).
     * Blocking: for the login thread, never the main thread.
     */
    public Login login(String hash, UUID player, String name, boolean op, long now, long expireMs) {
        return db.sync(c -> {
            AltRules.Owner o = owner(c, hash);
            boolean bypass = op || allowed(c, player);
            AltRules.Verdict v = AltRules.decide(o, player, bypass, now, expireMs);
            String n = cut(name);
            if (v == AltRules.Verdict.CLAIM) {
                Db.update(c, db.upsert("stsv_ip_owner", List.of("ip_hash"), List.of("owner", "owner_name", "claimed_at", "last_seen")),
                        hash, player.toString(), n, now, now);
            } else if (v == AltRules.Verdict.OWNER) {
                Db.update(c, "UPDATE stsv_ip_owner SET last_seen = ?, owner_name = ? WHERE ip_hash = ?", now, n, hash);
            }
            return new Login(v, o);
        });
    }

    public java.util.concurrent.CompletableFuture<AltRules.Owner> ownerOf(String hash) {
        return db.async(c -> owner(c, hash));
    }

    /** How many addresses the account owns. */
    public java.util.concurrent.CompletableFuture<Integer> owned(UUID player) {
        return db.async(c -> Db.query(c, "SELECT COUNT(*) FROM stsv_ip_owner WHERE owner = ?", rs -> rs.getInt(1), player.toString()).getFirst());
    }

    /** Frees one address: the next account to log in from it owns it. true = it had an owner. */
    public java.util.concurrent.CompletableFuture<Boolean> release(String hash) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_ip_owner WHERE ip_hash = ?", hash) > 0);
    }

    /** Frees every address the account owns; how many. */
    public java.util.concurrent.CompletableFuture<Integer> releaseAll(UUID player) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_ip_owner WHERE owner = ?", player.toString()));
    }

    /** /관리자 부계정 허용: true = newly allowed. */
    public java.util.concurrent.CompletableFuture<Boolean> allow(UUID player, String name, String by, long now) {
        return db.async(c -> {
            if (allowed(c, player)) return false;
            Db.update(c, "INSERT INTO stsv_alt_allow (player, player_name, added_by, added_at) VALUES (?, ?, ?, ?)",
                    player.toString(), cut(name), cut(by), now);
            return true;
        });
    }

    /** /관리자 부계정 해제: true = it was allowed. */
    public java.util.concurrent.CompletableFuture<Boolean> disallow(UUID player) {
        return db.async(c -> Db.update(c, "DELETE FROM stsv_alt_allow WHERE player = ?", player.toString()) > 0);
    }

    public java.util.concurrent.CompletableFuture<Boolean> isAllowed(UUID player) {
        return db.async(c -> allowed(c, player));
    }

    public java.util.concurrent.CompletableFuture<List<Allowed>> allowedList() {
        return db.async(c -> Db.query(c, "SELECT player, player_name, added_by, added_at FROM stsv_alt_allow ORDER BY added_at",
                rs -> new Allowed(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getLong(4))));
    }

    private static String cut(String s) {
        if (s == null) return "?";
        return s.length() <= 16 ? s : s.substring(0, 16);
    }
}
