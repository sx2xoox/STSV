package dev.stsv.core.account;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static dev.stsv.core.account.AltRules.Verdict.BYPASS;
import static dev.stsv.core.account.AltRules.Verdict.CLAIM;
import static dev.stsv.core.account.AltRules.Verdict.OWNER;
import static dev.stsv.core.account.AltRules.Verdict.REFUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltRulesTest {

    private static final long DAY = 86_400_000L;
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    @Test
    void firstAccountOwnsTheAddressOthersAreRefused() {
        assertEquals(CLAIM, AltRules.decide(null, a, false, 0, 30 * DAY));
        AltRules.Owner owner = new AltRules.Owner(a, "a", 0);
        assertEquals(OWNER, AltRules.decide(owner, a, false, DAY, 30 * DAY));
        assertEquals(REFUSE, AltRules.decide(owner, b, false, DAY, 30 * DAY));
        assertEquals(BYPASS, AltRules.decide(owner, b, true, DAY, 30 * DAY), "an op or an allowed account gets in without claiming");
        assertEquals(BYPASS, AltRules.decide(null, b, true, DAY, 30 * DAY), "and does not claim a free address either");
        assertEquals(OWNER, AltRules.decide(owner, a, true, DAY, 30 * DAY), "the owner stays the owner");
    }

    @Test
    void anOwnerLongGoneLosesTheAddress() {
        AltRules.Owner owner = new AltRules.Owner(a, "a", 0);
        assertEquals(REFUSE, AltRules.decide(owner, b, false, 30 * DAY - 1, 30 * DAY));
        assertEquals(CLAIM, AltRules.decide(owner, b, false, 30 * DAY, 30 * DAY));
        assertEquals(REFUSE, AltRules.decide(owner, b, false, 9999 * DAY, 0), "0 = never expires");
    }

    @Test
    void addressKeys() throws Exception {
        assertEquals("203.0.113.7", AltRules.key(InetAddress.ofLiteral("203.0.113.7")));
        assertEquals("203.0.113.7", AltRules.key(InetAddress.ofLiteral("::ffff:203.0.113.7")), "IPv4 inside IPv6 is the IPv4 address");
        assertEquals("2001:db8:1:2::/64", AltRules.key(InetAddress.ofLiteral("2001:db8:1:2:aaaa:bbbb:cccc:dddd")));
        assertEquals(AltRules.key(InetAddress.ofLiteral("2001:db8:1:2::1")), AltRules.key(InetAddress.ofLiteral("2001:db8:1:2:ffff::9")),
                "rotating IPv6 addresses of one home network are one key");
        assertEquals("203.0.113.*", AltRules.masked("203.0.113.7"));
        assertEquals("2001:db8:1:*", AltRules.masked("2001:db8:1:2::/64"));
    }

    @Test
    void localAddressesAreRecognised() throws Exception {
        for (String s : new String[]{"127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.3.4", "::1", "fe80::1", "fd12::3"}) {
            assertTrue(AltRules.local(InetAddress.ofLiteral(s)), s);
        }
        for (String s : new String[]{"203.0.113.7", "8.8.8.8", "2001:db8::1"}) assertFalse(AltRules.local(InetAddress.ofLiteral(s)), s);
    }

    @Test
    void hashesNeedTheSecret() {
        byte[] s1 = "secret-one-secret-one-secret-one".getBytes(StandardCharsets.UTF_8);
        byte[] s2 = "secret-two-secret-two-secret-two".getBytes(StandardCharsets.UTF_8);
        String h = AltRules.hash("203.0.113.7", s1);
        assertEquals(64, h.length());
        assertEquals(h, AltRules.hash("203.0.113.7", s1));
        assertNotEquals(h, AltRules.hash("203.0.113.8", s1));
        assertNotEquals(h, AltRules.hash("203.0.113.7", s2));
        assertFalse(h.contains("203"));
    }

    @Test
    void typedAddresses() {
        assertEquals("203.0.113.7", AltRules.key(AltModule.literal("203.0.113.7")));
        assertNull(AltModule.literal("Steve"), "a name is not looked up as a host");
        assertNull(AltModule.literal("999.1.1.1"));
    }

    @Test
    void storeClaimsOnceAndKeepsAllowedAccounts(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        AltStore s = new AltStore(db);
        s.init();
        String ip = "ab".repeat(32);
        assertEquals(CLAIM, s.login(ip, a, "Alice", false, 1, 30 * DAY).verdict());
        assertEquals(OWNER, s.login(ip, a, "Alice", false, 2, 30 * DAY).verdict());
        AltStore.Login refused = s.login(ip, b, "Bob", false, 3, 30 * DAY);
        assertEquals(REFUSE, refused.verdict());
        assertEquals("Alice", refused.owner().name());
        assertEquals(BYPASS, s.login(ip, b, "Bob", true, 4, 30 * DAY).verdict(), "an operator");

        assertTrue(s.allow(b, "Bob", "admin", 5).get());
        assertFalse(s.allow(b, "Bob", "admin", 6).get());
        assertEquals(BYPASS, s.login(ip, b, "Bob", false, 7, 30 * DAY).verdict());
        assertEquals(a, s.ownerOf(ip).get().player(), "an allowed account does not take the address");
        assertEquals(1, s.allowedList().get().size());
        assertTrue(s.disallow(b).get());
        assertEquals(REFUSE, s.login(ip, b, "Bob", false, 8, 30 * DAY).verdict());

        // the owner's last use counts from their last login: 30 days after it the address is free
        assertEquals(CLAIM, s.login(ip, b, "Bob", false, 2 + 30 * DAY, 30 * DAY).verdict());
        assertEquals(b, s.ownerOf(ip).get().player());

        assertEquals(1, s.owned(b).get());
        assertEquals(1, s.releaseAll(b).get());
        assertNull(s.ownerOf(ip).get());
        assertEquals(CLAIM, s.login(ip, a, "Alice", false, 9 + 30 * DAY, 30 * DAY).verdict());
        assertTrue(s.release(ip).get());
        assertFalse(s.release(ip).get());
        db.close();
    }
}
