package dev.stsv.core.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The panel's sizes and places, and moving it with the mouse (HudEdit), as the game would draw it. */
class HudEditTest {

    static HudSheets sheets;
    static HudLayout layout;
    static HudClient client;
    static List<HudLayout.Row> rows;

    static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    @BeforeAll
    static void load() throws Exception {
        HudSheets bundled = HudSheets.load();
        sheets = HudLayout.panelSheets(bundled, bundled.panelLetters());
        layout = new HudLayout(sheets, HudLayout.Settings.defaults());
        client = new HudClient(sheets, layout.fonts());
        rows = List.of(
                new HudLayout.Row("coin", mm("<gray>잔액\t<gold>12,345 G"), false),
                new HudLayout.Row("job", mm("<gray>직업\t<#7ec850>농부 <white>Lv12 <dark_gray>34%"), false),
                new HudLayout.Row("mission", mm("<gray>미션\t<white>2/3 <dark_gray>· <gray>주간 <white>1/3"), false),
                new HudLayout.Row("event", mm("<gray>사건\t<gold>풍년 <dark_gray>2시간"), true));
    }

    /** A fit for a panel of these rows (what HudModule gives the edit). */
    static HudEdit.Fit fit(List<HudLayout.Row> r) {
        return want -> layout.fit(want, layout.lines(r, want.size()));
    }

    static HudClient.Draw find(HudClient.Laid laid, String sheet, String cell) {
        return laid.draws().stream().filter(d -> d.sheet().equals(sheet) && d.cell().equals(cell)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + sheet + ":" + cell + " drawn"));
    }

    static int width(HudClient.Draw d) {
        HudSheets.Sheet s = sheets.sheet(d.sheet());
        return s.advanceAt(s.index(d.cell()), d.scale()) - 1;
    }

    // ------------------------------------------------------------------------------------------
    // Sizes and places
    // ------------------------------------------------------------------------------------------

    @Test
    void everySizeHugsItsRightEdgeOnItsOwnRows() {
        for (HudLayout.Size size : HudLayout.Size.values()) {
            String st = size.style.suffix;
            int k = size.scale;
            for (int right : new int[]{212, 40, -150, 600}) {
                HudLayout.Placement at = layout.fit(new HudLayout.Placement(size, right, 54), rows.size());
                HudClient.Laid laid = client.lay(layout.panel(rows, at, false));
                assertEquals(0, laid.width(), size + ": zero net advance");
                HudClient.Draw cap = find(laid, "panel_head" + st, "r");
                assertEquals(right, cap.x() + HudLayout.CAP * k, size + ": the outline ends at the right edge");
                assertEquals(at.top(), cap.y() + 3, size + ": the top where it was put");
                HudClient.Draw corner = find(laid, "panel_head" + st, "l");
                int x = corner.x();
                assertTrue(laid.draws().stream().allMatch(d -> d.scale() == k), size + ": every glyph at the size's scale");
                assertTrue(laid.draws().stream().allMatch(d -> d.x() >= x && d.x() + width(d) <= right + k), size + ": nothing outside the panel");
                // icons and letters on the size's rows, the value against the right edge
                HudClient.Draw coin = find(laid, "icons", "coin"), job = find(laid, "icons", "job");
                assertEquals(x + HudLayout.PAD * k, coin.x());
                assertEquals(size.row(), job.y() - coin.y(), size + ": rows " + size.row() + " px apart");
                assertEquals(at.top() + size.head(), coin.y() + 3, size + ": the first row under the title");
                Map<Integer, List<HudClient.Draw>> byRow = laid.draws().stream().filter(HudClient.Draw::shadow)
                        .collect(Collectors.groupingBy(HudClient.Draw::y));
                assertEquals(rows.size(), byRow.size(), size + ": one line of letters per row");
                for (List<HudClient.Draw> line : byRow.values()) {
                    assertEquals(x + HudLayout.TEXT_X * k, line.stream().mapToInt(HudClient.Draw::x).min().orElseThrow());
                    int end = line.stream().mapToInt(d -> d.x() + width(d) + 1).max().orElseThrow();
                    assertEquals(right - HudLayout.PAD * k, end, size + ": value against the right edge");
                    String letters = size == HudLayout.Size.NORMAL ? "hangul_panel" : "hangul_panel7";
                    assertTrue(line.stream().allMatch(d -> d.sheet().equals(letters) || d.sheet().startsWith("text")), size + ": its own letters");
                }
            }
        }
    }

    @Test
    void sizesGrowInOrder() {
        int[] widths = new int[3], heights = new int[3];
        for (HudLayout.Size size : HudLayout.Size.values()) {
            HudClient.Laid laid = client.lay(layout.panel(rows, new HudLayout.Placement(size, 212, 54), false));
            int left = laid.draws().stream().mapToInt(HudClient.Draw::x).min().orElseThrow();
            int top = laid.draws().stream().mapToInt(HudClient.Draw::y).min().orElseThrow();
            int bottom = laid.draws().stream().mapToInt(d -> d.y() + sheets.sheet(d.sheet()).cellH() * d.scale()).max().orElseThrow();
            widths[size.ordinal()] = 212 - left;
            heights[size.ordinal()] = bottom - top;
        }
        assertTrue(widths[0] < widths[1] && widths[1] < widths[2], "wider: " + java.util.Arrays.toString(widths));
        assertTrue(heights[0] < heights[1] && heights[1] < heights[2], "taller: " + java.util.Arrays.toString(heights));
        assertEquals(HudLayout.Size.SMALL, HudLayout.Size.NORMAL.step(-1));
        assertEquals(HudLayout.Size.SMALL, HudLayout.Size.SMALL.step(-1), "no smaller than small");
        assertEquals(HudLayout.Size.LARGE, HudLayout.Size.NORMAL.step(1));
        assertEquals(HudLayout.Size.LARGE, HudLayout.Size.LARGE.step(1), "no bigger than large");
        for (HudLayout.Size s : HudLayout.Size.values()) assertEquals(s, HudLayout.Size.of(s.code));
        assertEquals(HudLayout.Size.NORMAL, HudLayout.Size.of(99));
    }

    @Test
    void placesSnapToRowsWithinTheirRange() {
        HudLayout.Placement d = layout.defaults();
        assertEquals(d, layout.fit(d, layout.maxRows(HudLayout.Size.NORMAL)), "the default place fits a full panel");
        for (HudLayout.Size size : HudLayout.Size.values()) {
            int min = layout.minTop(size), pitch = size.row();
            assertTrue(min >= HudLayout.MIN_TOP && min < HudLayout.MIN_TOP + pitch, size + " starts near the top: " + min);
            assertEquals(0, (54 - min) % pitch, size + ": the default height is one of its rows");
            for (int n = 1; n <= layout.maxRows(size); n++) {
                int max = layout.maxTop(size, n);
                assertTrue(max >= 54, size + " " + n + " rows: reaches the default height");
                assertEquals(min, layout.fit(new HudLayout.Placement(size, 0, -500), n).top());
                assertEquals(max, layout.fit(new HudLayout.Placement(size, 0, 5000), n).top());
                if (n > 1) assertEquals(layout.maxTop(size, n - 1) - pitch, max, "a row shorter goes a row lower");
                for (int top = min - 20; top < max + 20; top += 3) {
                    int got = layout.fit(new HudLayout.Placement(size, 0, top), n).top();
                    assertEquals(0, (got - min) % pitch);
                    assertTrue(got >= min && got <= max);
                    if (top >= min && top <= max) assertTrue(Math.abs(got - top) <= pitch / 2, "nearest row");
                }
            }
        }
        assertEquals(HudLayout.MAX_RIGHT, layout.fit(new HudLayout.Placement(HudLayout.Size.NORMAL, 99999, 54), 3).right());
        assertEquals(HudLayout.MIN_RIGHT, layout.fit(new HudLayout.Placement(HudLayout.Size.NORMAL, -99999, 54), 3).right());
        // the large panel shows at most 6 rows (all a sidebar has today), the others the configured 7
        assertEquals(6, layout.maxRows(HudLayout.Size.LARGE));
        assertEquals(7, layout.maxRows(HudLayout.Size.SMALL));
    }

    @Test
    void aPanelDrawsAtEveryPlaceItCanTake() {
        // every place of every size and length exists in the fonts (HudClient would throw on a missing glyph)
        List<HudLayout.Row> many = new ArrayList<>();
        for (int i = 0; i < 7; i++) many.add(new HudLayout.Row("coin", mm("<gray>줄 " + i + "\t<gold>" + i), i == 3));
        for (HudLayout.Size size : HudLayout.Size.values()) {
            for (int n = 1; n <= layout.maxRows(size); n++) {
                List<HudLayout.Row> r = many.subList(0, n);
                for (int top = layout.minTop(size); top <= layout.maxTop(size, n); top += size.row()) {
                    for (boolean edit : new boolean[]{false, true}) {
                        HudClient.Laid laid = client.lay(layout.panel(r, new HudLayout.Placement(size, 212, top), edit));
                        assertTrue(laid.draws().stream().allMatch(d -> d.y() + 3 >= -2), "nothing far above the screen");
                        assertEquals(top, find(laid, "panel_head" + size.style.suffix, "l").y() + 3);
                    }
                }
            }
        }
    }

    @Test
    void bracketsMarkThePanelBeingMoved() {
        for (HudLayout.Size size : HudLayout.Size.values()) {
            int k = size.scale;
            HudLayout.Placement at = layout.fit(new HudLayout.Placement(size, 150, 80), rows.size());
            HudClient.Laid plain = client.lay(layout.panel(rows, at, false));
            HudClient.Laid laid = client.lay(layout.panel(rows, at, true));
            int left = find(plain, "panel_head" + size.style.suffix, "l").x();
            int top = at.top();
            int bottom = plain.draws().stream().filter(d -> d.sheet().startsWith("panel_foot")).mapToInt(HudClient.Draw::y).max().orElseThrow() + 3 + 2 * k;
            HudClient.Draw tl = find(laid, "panel_edit", "tl"), tr = find(laid, "panel_edit", "tr");
            HudClient.Draw bl = find(laid, "panel_edit", "bl"), br = find(laid, "panel_edit", "br");
            // the bright corner of each bracket 2 px (times the scale) outside the panel's outline
            assertEquals(left - 2 * k, tl.x() + k);
            assertEquals(top - 2 * k, tl.y() + 3 + k);
            assertEquals(150 + k, tr.x() + 6 * k);
            assertEquals(tl.y(), tr.y());
            assertEquals(bottom + k, bl.y() + 3 + 6 * k, size + ": below the outline");
            assertEquals(bl.y(), br.y());
            assertEquals(plain.draws(), laid.draws().stream().filter(d -> !d.sheet().equals("panel_edit")).toList(), "the panel itself is unchanged");
        }
    }

    @Test
    void editPacketsStaySmall() {
        var gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        List<HudLayout.Row> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) seven.add(new HudLayout.Row("coin", mm("<gray>잔액\t<gold>12,345,678 G <white>농부 Lv" + i), i == 3));
        for (HudLayout.Size size : HudLayout.Size.values()) {
            int n = gson.serialize(layout.panel(seven, new HudLayout.Placement(size, -300, 54), true)).length();
            assertTrue(n < 16_000, size + " panel JSON " + n);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Moving it with the mouse
    // ------------------------------------------------------------------------------------------

    static HudEdit start(HudLayout.Placement p) {
        return new HudEdit(layout.fit(p, rows.size()), 10f, 0f, 1000);
    }

    @Test
    void turningMovesThePanel() {
        HudEdit e = start(layout.defaults());
        HudEdit.Fit fit = fit(rows);
        assertFalse(e.look(10f, 0f, fit), "no turn, no move");
        assertTrue(e.look(20f, 0f, fit));
        assertEquals(212 + (int) Math.round(10 * HudEdit.PX_PER_DEGREE), e.current().right(), "right when turning right");
        assertTrue(e.look(15f, 0f, fit));
        assertEquals(212 + (int) Math.round(5 * HudEdit.PX_PER_DEGREE), e.current().right(), "back left");
        // looking down moves it down, a row at a time
        int top = e.current().top();
        e.look(15f, 2f, fit);
        assertEquals(top, e.current().top(), "less than half a row: stays");
        e.look(15f, 2f + HudLayout.Size.NORMAL.row() / (float) HudEdit.PX_PER_DEGREE, fit);
        assertEquals(top + HudLayout.Size.NORMAL.row(), e.current().top(), "a row down");
        e.look(15f, -40f, fit);
        assertEquals(layout.minTop(HudLayout.Size.NORMAL), e.current().top(), "looking up: to the top");
        assertEquals(HudLayout.Size.NORMAL, e.current().size());
    }

    @Test
    void yawWrapsTheShortWay() {
        assertEquals(2.0, HudEdit.turn(179f, -179f), 1e-6);
        assertEquals(-2.0, HudEdit.turn(-179f, 179f), 1e-6);
        assertEquals(30.0, HudEdit.turn(350f, 20f), 1e-6);
        assertEquals(-90.0, HudEdit.turn(45f, -45f), 1e-6);
        HudEdit e = new HudEdit(layout.defaults(), 179f, 0f, 0);
        e.look(-179f, 0f, fit(rows));
        assertEquals(212 + (int) Math.round(2 * HudEdit.PX_PER_DEGREE), e.current().right(), "across south: a small turn, a small move");
    }

    @Test
    void edgesStopThePanelAndReversingMovesItAtOnce() {
        HudEdit e = start(layout.defaults());
        HudEdit.Fit fit = fit(rows);
        float yaw = 10f;
        for (int i = 0; i < 40; i++) e.look(yaw += 30f, 0f, fit);   // far past the right edge
        assertEquals(HudLayout.MAX_RIGHT, e.current().right());
        e.look(yaw -= 5f, 0f, fit);
        assertEquals(HudLayout.MAX_RIGHT - (int) Math.round(5 * HudEdit.PX_PER_DEGREE), e.current().right(), "no slack to wind back");
        e.look(yaw, 89f, fit);
        int bottom = layout.maxTop(HudLayout.Size.NORMAL, layout.lines(rows, HudLayout.Size.NORMAL));
        assertEquals(bottom, e.current().top());
        e.look(yaw, 89f - HudLayout.Size.NORMAL.row() / (float) HudEdit.PX_PER_DEGREE, fit);
        assertEquals(bottom - HudLayout.Size.NORMAL.row(), e.current().top(), "a row up at once");
    }

    @Test
    void theWheelStepsTheSize() {
        HudEdit e = start(layout.defaults());
        HudEdit.Fit fit = fit(rows);
        assertTrue(e.scroll(HudEdit.wheel(4, 3), fit), "wheel up: bigger");
        assertEquals(HudLayout.Size.LARGE, e.current().size());
        assertFalse(e.scroll(+1, fit), "no bigger than large");
        assertEquals(212, e.current().right(), "the right edge stays");
        assertTrue(e.scroll(HudEdit.wheel(4, 5), fit));
        assertTrue(e.scroll(-1, fit));
        assertEquals(HudLayout.Size.SMALL, e.current().size());
        assertFalse(e.scroll(-1, fit), "no smaller than small");
        assertEquals(0, (e.current().top() - layout.minTop(HudLayout.Size.SMALL)) % HudLayout.Size.SMALL.row(), "on the small rows");
        // the wheel: one notch either way, also across the ends of the hotbar
        assertEquals(1, HudEdit.wheel(0, 8));
        assertEquals(-1, HudEdit.wheel(8, 0));
        assertEquals(-1, HudEdit.wheel(3, 4));
        assertEquals(1, HudEdit.wheel(3, 1));
        assertEquals(0, HudEdit.wheel(3, 3));
    }

    @Test
    void savingKeepsTheNewPlaceCancellingTheOld() {
        HudLayout.Placement from = layout.fit(layout.defaults(), rows.size());
        HudEdit saved = start(layout.defaults());
        saved.look(40f, 10f, fit(rows));
        HudLayout.Placement moved = saved.current();
        assertFalse(moved.equals(from));
        assertFalse(saved.canConfirm(1000 + HudEdit.GRACE_TICKS - 1), "a click right at the start does not count");
        assertTrue(saved.canConfirm(1000 + HudEdit.GRACE_TICKS));
        assertEquals(HudEdit.Outcome.SAVED, saved.end(HudEdit.Outcome.SAVED));
        assertNull(saved.end(HudEdit.Outcome.CANCELLED), "only the first end counts");
        assertEquals(moved, saved.result());
        assertFalse(saved.look(80f, 0f, fit(rows)), "nothing moves after the end");
        assertFalse(saved.scroll(1, fit(rows)));

        HudEdit cancelled = start(layout.defaults());
        cancelled.look(40f, 10f, fit(rows));
        cancelled.scroll(1, fit(rows));
        cancelled.end(HudEdit.Outcome.CANCELLED);
        assertEquals(from, cancelled.result(), "cancelling keeps the place from before");

        HudEdit late = start(layout.defaults());
        assertFalse(late.timedOut(1000 + HudEdit.TIMEOUT_TICKS - 1));
        assertTrue(late.timedOut(1000 + HudEdit.TIMEOUT_TICKS));
        assertEquals(60, late.secondsLeft(1000));
        assertEquals(1, late.secondsLeft(1000 + HudEdit.TIMEOUT_TICKS - 1));
        assertEquals(0, late.secondsLeft(1000 + HudEdit.TIMEOUT_TICKS + 40));
        late.end(HudEdit.Outcome.TIMED_OUT);
        assertEquals(from, late.result());
    }

    @Test
    void theStoredPlaceSurvivesAndTheOldOneIsRead() {
        HudLayout.Placement p = new HudLayout.Placement(HudLayout.Size.LARGE, -120, 76);
        assertArrayEquals(new int[]{3, -120, 76}, HudEdit.store(p));
        assertEquals(p, HudEdit.stored(HudEdit.store(p), null, layout.defaults()));
        assertEquals(p, HudEdit.stored(HudEdit.store(p), 300, layout.defaults()), "the new form wins over the old");
        assertEquals(new HudLayout.Placement(HudLayout.Size.NORMAL, 318, 54), HudEdit.stored(null, 318, layout.defaults()),
                "/사이드바 위치 1920x1080 3 from before: its right edge, default size and height");
        assertEquals(layout.defaults(), HudEdit.stored(null, null, layout.defaults()));
        assertEquals(layout.defaults(), HudEdit.stored(new int[]{1}, null, layout.defaults()), "a broken value: the defaults");
    }
}
