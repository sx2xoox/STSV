package dev.stsv.core.account;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 부계정 금지: one account per IP. The first account that joins from an address owns it; another account from the
 * same address is refused at login, unless it is an operator or an admin allowed it (/관리자 부계정 허용). Pure
 * (unit-tested): the module reads the owner record and the flags, this decides.
 * <p>
 * Addresses are never stored as text: {@link #key} turns one into the part that names a household (an IPv4 address;
 * the first 64 bits of an IPv6 address, the part a home network keeps while the rest rotates), and {@link #hash}
 * keeps only an HMAC-SHA256 of that with a secret of the server. An address can be looked up again (an admin types
 * it, or a player is online), but the table alone does not show it.
 */
public final class AltRules {

    private AltRules() {}

    /** Who owns an address: the account, its name when it claimed or last used it, when it was last used. */
    public record Owner(UUID player, String name, long lastSeen) {}

    public enum Verdict {
        /** nobody owned the address (or the owner's claim expired): this account now owns it */
        CLAIM,
        /** the account owns the address already */
        OWNER,
        /** an operator or an allowed account: let in, the address is not claimed */
        BYPASS,
        /** someone else owns the address */
        REFUSE
    }

    /**
     * @param owner     the address's owner record, or null when nobody owns it
     * @param joining   the account logging in
     * @param bypass    an operator, or allowed on shared addresses by an admin
     * @param expireMs  an owner unseen this long loses the address (0 or less = never)
     */
    public static Verdict decide(Owner owner, UUID joining, boolean bypass, long now, long expireMs) {
        if (owner != null && owner.player().equals(joining)) return Verdict.OWNER;
        if (bypass) return Verdict.BYPASS;
        if (owner == null || (expireMs > 0 && now - owner.lastSeen() >= expireMs)) return Verdict.CLAIM;
        return Verdict.REFUSE;
    }

    /** Addresses that are not a household on the internet (this machine, a LAN): never checked when ignore-local is on. */
    public static boolean local(InetAddress a) {
        return a == null || a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || (a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc);   // fc00::/7, unique local
    }

    /**
     * The part of an address that names one household: an IPv4 address as is (also one inside ::ffff:a.b.c.d), the
     * first 64 bits of an IPv6 address ("2001:db8:1:2::/64").
     */
    public static String key(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return a.getHostAddress();
        if (b.length == 16 && mapped(b)) return (b[12] & 0xff) + "." + (b[13] & 0xff) + "." + (b[14] & 0xff) + "." + (b[15] & 0xff);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            if (i > 0) s.append(':');
            s.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff)));
        }
        return s.append("::/64").toString();
    }

    private static boolean mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** HMAC-SHA256 of the address key with the server's secret, as 64 hex characters. */
    public static String hash(String key, byte[] secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(key.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An address as an admin may see it: the last part hidden (123.45.67.* / 2001:db8:1:*). */
    public static String masked(String key) {
        if (key.endsWith("::/64")) {
            String[] p = key.substring(0, key.length() - 5).split(":");
            return p.length < 3 ? key : p[0] + ":" + p[1] + ":" + p[2] + ":*";
        }
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(0, dot) + ".*";
    }
}
