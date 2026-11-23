package dev.stsv.economy.secret;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RumorsGapTest {

    @Test
    void nobodyHearsTwiceWithinTheGap() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Map<UUID, Long> heard = new HashMap<>();
        long gap = 120 * 60_000L;
        assertEquals(List.of(a, b), Rumors.due(List.of(a, b), heard, 1_000, gap, u -> u));
        heard.put(a, 1_000L);
        assertEquals(List.of(b), Rumors.due(List.of(a, b), heard, 1_000 + gap - 1, gap, u -> u));
        assertEquals(List.of(), Rumors.due(List.of(a), heard, 1_000 + gap - 1, gap, u -> u), "alone: the turn passes");
        assertEquals(List.of(a), Rumors.due(List.of(a), heard, 1_000 + gap, gap, u -> u));
    }
}
