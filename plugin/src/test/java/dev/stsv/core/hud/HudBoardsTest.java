package dev.stsv.core.hud;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HudBoards: each viewer sees the highest-priority board whose audience takes them (a 도박장 table over a tournament). */
class HudBoardsTest {

    static HudLayout.Board board(String title) {
        return new HudLayout.Board(Component.text(title), List.of());
    }

    @Test
    void aSeatedPlayerSeesTheTableEveryoneElseTheTournament() {
        HudBoards<String> b = new HudBoards<>();
        HudLayout.Board tournament = board("낚시대회"), table = board("섯다");
        Set<String> seated = Set.of("앉은사람");
        // the tournament registers first (economy's fishing before the 도박장), for everyone
        b.put("tournament", 0, null, v -> tournament);
        b.put("hwatu", HudModule.BOARD_SEATED, seated::contains, v -> table);
        assertSame(table, b.pick("앉은사람", null));
        assertSame(tournament, b.pick("구경꾼", null));
        assertEquals(List.of("hwatu", "tournament"), b.order());
    }

    @Test
    void aHigherBoardThatAnswersNothingLetsTheNextOneShow() {
        HudBoards<String> b = new HudBoards<>();
        HudLayout.Board low = board("low");
        b.put("high", 5, null, v -> null);
        b.put("low", 0, null, v -> low);
        assertSame(low, b.pick("x", null));
        b.remove("low");
        assertNull(b.pick("x", null));
        assertTrue(b.remove("high"));
        assertTrue(b.isEmpty());
    }

    @Test
    void theSamePriorityGoesByWhoCameFirstAndReplacingKeepsThePlace() {
        HudBoards<String> b = new HudBoards<>();
        b.put("a", 0, null, v -> board("a"));
        b.put("b", 0, null, v -> board("b"));
        assertEquals(List.of("a", "b"), b.order());
        b.put("a", 0, null, v -> board("a2"));   // replaced: still first
        assertEquals(List.of("a", "b"), b.order());
        assertEquals(Component.text("a2"), b.pick("x", null).title());
        b.put("b", 1, null, v -> board("b"));     // raised: now first
        assertEquals(List.of("b", "a"), b.order());
    }

    @Test
    void aBrokenProducerIsSkippedAndReported() {
        HudBoards<String> b = new HudBoards<>();
        List<String> errors = new ArrayList<>();
        HudLayout.Board ok = board("ok");
        b.put("broken", 10, null, v -> {
            throw new IllegalStateException("boom");
        });
        b.put("picky", 9, v -> {
            throw new IllegalStateException("audience");
        }, v -> board("never"));
        b.put("ok", 0, null, v -> ok);
        assertSame(ok, b.pick("x", (id, e) -> errors.add(id)));
        assertEquals(List.of("broken", "picky"), errors);
    }
}
