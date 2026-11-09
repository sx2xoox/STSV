package dev.stsv.core.tpa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Teleport requests: one per sender, several per target, expiry, accepting the latest or by name, blocking, cooldown. */
class TpaBookTest {

    private static final UUID ALEX = UUID.randomUUID(), STEVE = UUID.randomUUID(), NOOR = UUID.randomUUID(), KAI = UUID.randomUUID();

    private static TpaBook.Request req(UUID from, String fromName, UUID to, String toName, TpaBook.Kind kind, long at) {
        return new TpaBook.Request(from, fromName, to, toName, kind, at);
    }

    @Test
    void whoMovesWhere() {
        TpaBook.Request to = req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0);
        assertEquals(ALEX, to.mover());
        assertEquals(STEVE, to.destination());
        TpaBook.Request here = req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.HERE, 0);
        assertEquals(STEVE, here.mover(), "/텔포여기: the target comes");
        assertEquals(ALEX, here.destination());
    }

    @Test
    void onePendingRequestPerSender() {
        TpaBook b = new TpaBook(60_000, 30_000);
        assertEquals(TpaBook.Status.NEW, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0)).status());
        TpaBook.Added again = b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 5_000));
        assertEquals(TpaBook.Status.SAME, again.status(), "the same request is not sent twice");
        assertEquals(0, again.previous().at(), "and the first one keeps its time");
        TpaBook.Added other = b.add(req(ALEX, "Alex", NOOR, "Noor", TpaBook.Kind.TO, 6_000));
        assertEquals(TpaBook.Status.REPLACED, other.status());
        assertEquals(STEVE, other.previous().target());
        assertTrue(b.incoming(STEVE, 6_000).isEmpty(), "the old target lost it");
        assertEquals(NOOR, b.outgoing(ALEX, 6_000).target());
        // the same target, the other kind: a new request in its place
        assertEquals(TpaBook.Status.REPLACED, b.add(req(ALEX, "Alex", NOOR, "Noor", TpaBook.Kind.HERE, 7_000)).status());
        assertEquals(TpaBook.Kind.HERE, b.outgoing(ALEX, 7_000).kind());
    }

    @Test
    void requestsExpire() {
        TpaBook b = new TpaBook(60_000, 30_000);
        b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0));
        b.add(req(NOOR, "Noor", STEVE, "Steve", TpaBook.Kind.TO, 30_000));
        assertEquals(1_000, b.expiresIn(b.outgoing(ALEX, 59_000), 59_000));
        assertEquals(2, b.incoming(STEVE, 59_999).size());
        assertEquals(1, b.incoming(STEVE, 60_000).size(), "gone at the timeout even before it is swept");
        assertNull(b.outgoing(ALEX, 60_000));
        assertNull(b.take(STEVE, "Alex", 60_000), "an expired request cannot be accepted");
        List<TpaBook.Request> gone = b.expire(60_000);
        assertEquals(1, gone.size());
        assertEquals(ALEX, gone.getFirst().sender());
        assertTrue(b.expire(60_001).isEmpty(), "swept once");
        // after it expired, the same request can be sent again
        assertEquals(TpaBook.Status.NEW, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 61_000)).status());
    }

    @Test
    void acceptTheLatestOrByName() {
        TpaBook b = new TpaBook(60_000, 30_000);
        b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 1_000));
        b.add(req(NOOR, "Noor", STEVE, "Steve", TpaBook.Kind.HERE, 2_000));
        b.add(req(KAI, "Kai", STEVE, "Steve", TpaBook.Kind.TO, 2_000));
        b.add(req(STEVE, "Steve", ALEX, "Alex", TpaBook.Kind.TO, 2_500));
        assertEquals(List.of(KAI, NOOR, ALEX), b.incoming(STEVE, 3_000).stream().map(TpaBook.Request::sender).toList(),
                "newest first; at the same time the later one");
        assertEquals(NOOR, b.take(STEVE, "noor", 3_000).sender(), "by name, ignoring case");
        assertNull(b.take(STEVE, "Noor", 3_000), "taken once");
        assertNull(b.take(STEVE, "Zed", 3_000));
        assertEquals(ALEX, b.take(STEVE, "Al", 3_000).sender(), "the only name starting with it");
        assertEquals(KAI, b.take(STEVE, null, 3_000).sender(), "no name: the newest");
        assertNull(b.take(STEVE, null, 3_000));
        assertEquals(STEVE, b.take(ALEX, "", 3_000).sender(), "requests to someone else are untouched");
    }

    @Test
    void anAmbiguousStartOfANameTakesNothing() {
        TpaBook b = new TpaBook(60_000, 30_000);
        b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0));
        b.add(req(NOOR, "Alexa", STEVE, "Steve", TpaBook.Kind.TO, 0));
        assertNull(b.take(STEVE, "Ale", 1_000));
        assertEquals(ALEX, b.take(STEVE, "alex", 1_000).sender(), "an exact name wins over a longer one");
    }

    @Test
    void cancelAndLeaving() {
        TpaBook b = new TpaBook(60_000, 30_000);
        b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0));
        assertEquals(STEVE, b.cancel(ALEX, 1_000).target());
        assertNull(b.cancel(ALEX, 1_000), "nothing left to cancel");
        b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0));
        b.add(req(STEVE, "Steve", NOOR, "Noor", TpaBook.Kind.HERE, 0));
        b.add(req(KAI, "Kai", NOOR, "Noor", TpaBook.Kind.TO, 0));
        assertEquals(2, b.forget(STEVE, 1_000).size(), "sent and received");
        assertTrue(b.incoming(STEVE, 1_000).isEmpty());
        assertEquals(List.of(KAI), b.incoming(NOOR, 1_000).stream().map(TpaBook.Request::sender).toList());
        b.add(req(ALEX, "Alex", NOOR, "Noor", TpaBook.Kind.TO, 0));
        assertNull(b.cancel(ALEX, 60_000), "an expired request is not reported as cancelled");
    }

    @Test
    void blockingIsAToggle() {
        TpaBook b = new TpaBook(60_000, 30_000);
        assertFalse(b.blocking(STEVE));
        assertTrue(b.toggleBlocking(STEVE));
        assertTrue(b.blocking(STEVE));
        assertFalse(b.blocking(ALEX), "only that player");
        assertFalse(b.toggleBlocking(STEVE));
        assertFalse(b.blocking(STEVE));
        b.setBlocking(STEVE, true);
        assertTrue(b.blocking(STEVE));
        b.forget(STEVE, 0);
        assertFalse(b.blocking(STEVE), "read again from the player when they come back");
    }

    @Test
    void cooldownAfterATrip() {
        TpaBook b = new TpaBook(60_000, 30_000);
        assertEquals(0, b.cooldownLeft(ALEX, 0));
        b.tripDone(ALEX, 10_000);
        assertEquals(30_000, b.cooldownLeft(ALEX, 10_000));
        assertEquals(1, b.cooldownLeft(ALEX, 39_999));
        assertEquals(0, b.cooldownLeft(ALEX, 40_000));
        assertEquals(0, b.cooldownLeft(STEVE, 10_000));
        b.settings(60_000, 0);
        b.tripDone(ALEX, 50_000);
        assertEquals(0, b.cooldownLeft(ALEX, 50_000), "cooldown 0 = none");
    }

    @Test
    void bundledSettings() throws Exception {
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(60, config.getInt("tpa.timeout-seconds"));
        assertEquals(30, config.getInt("tpa.cooldown-seconds"));
        assertTrue(config.getBoolean("elevator.enabled"));
        assertEquals("BONE_BLOCK", config.getString("elevator.block"));
        assertEquals(32, config.getInt("elevator.max-distance"));
        assertEquals(0.5, config.getDouble("elevator.cooldown-seconds"));
    }

    @Test
    void switchingKindsDoesNotFloodTheTarget() {
        TpaBook b = new TpaBook(60_000, 30_000);
        b.gap(5_000);
        assertEquals(TpaBook.Status.NEW, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 0)).status());
        assertEquals(TpaBook.Status.SAME, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 1_000)).status());
        TpaBook.Added flip = b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.HERE, 1_000));
        assertEquals(TpaBook.Status.TOO_SOON, flip.status(), "/tpa then /tpahere at once: refused, nothing sent");
        assertEquals(TpaBook.Kind.TO, b.outgoing(ALEX, 1_000).kind(), "the first request stays as it was");
        assertEquals(4_000, b.gapLeft(ALEX, 1_000));
        assertEquals(TpaBook.Status.TOO_SOON, b.add(req(ALEX, "Alex", NOOR, "Noor", TpaBook.Kind.TO, 4_999)).status(), "another target too");
        assertEquals(TpaBook.Status.REPLACED, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.HERE, 5_000)).status());
        assertEquals(TpaBook.Status.TOO_SOON, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 6_000)).status());
        assertEquals(TpaBook.Status.REPLACED, b.add(req(ALEX, "Alex", STEVE, "Steve", TpaBook.Kind.TO, 6_000), true).status(), "admins have no gap");
        assertEquals(TpaBook.Status.NEW, b.add(req(STEVE, "Steve", ALEX, "Alex", TpaBook.Kind.TO, 6_000)).status(), "the gap is per sender");
    }
}
