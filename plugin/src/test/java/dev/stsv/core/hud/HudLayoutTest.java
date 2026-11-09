package dev.stsv.core.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pack HUD as the game would draw it (HudClient reads only the generated fonts). */
class HudLayoutTest {

    static HudSheets sheets;
    static HudLayout layout;
    static HudClient client;

    static final HudLayout.Health HEALTHY = new HudLayout.Health(18, 20, 0, 17, 5f, 12, HudLayout.Tint.NORMAL, false);

    static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    @BeforeAll
    static void load() throws Exception {
        HudSheets bundled = HudSheets.load();
        sheets = HudLayout.panelSheets(bundled, bundled.panelLetters());
        layout = new HudLayout(sheets, HudLayout.Settings.defaults());
        client = new HudClient(sheets, layout.fonts());
    }

    static HudClient.Draw find(HudClient.Laid laid, String sheet, String cell) {
        return laid.draws().stream().filter(d -> d.sheet().equals(sheet) && d.cell().equals(cell)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + sheet + ":" + cell + " drawn"));
    }

    static boolean drawn(HudClient.Laid laid, String sheet, String cellPrefix) {
        return laid.draws().stream().anyMatch(d -> d.sheet().equals(sheet) && d.cell().startsWith(cellPrefix));
    }

    /** Distance of a glyph top above the bottom of the screen (action bar pen line = guiHeight - 72). */
    static int fromBottom(HudClient.Draw d) {
        return 72 - d.y();
    }

    /** Visible width of a drawn glyph (its advance minus the game's one pixel). */
    static int width(HudClient.Draw d) {
        HudSheets.Sheet s = sheets.sheet(d.sheet());
        return s.advanceAt(s.index(d.cell()), d.scale()) - 1;
    }

    static String number(HudClient.Laid laid, int row, boolean left) {
        return laid.draws().stream().filter(d -> d.sheet().equals("digits") && fromBottom(d) == row - 1 && (d.x() < 0) == left)
                .sorted(java.util.Comparator.comparingInt(HudClient.Draw::x)).map(HudClient.Draw::cell).collect(Collectors.joining());
    }

    /** Pixels of the inside covered by one fill colour (pieces of 1 .. 32 px). */
    static int filled(HudClient.Laid laid, String colour, int row) {
        return laid.draws().stream().filter(d -> d.sheet().equals("fill") && d.cell().startsWith(colour + "/")
                        && !d.cell().endsWith("/edge") && fromBottom(d) == row - 1)
                .mapToInt(d -> Integer.parseInt(d.cell().substring(colour.length() + 1))).sum();
    }

    @Test
    void barsSitInTheGameRowsAndLeaveTheMiddleToTheLevel() {
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, null));
        assertEquals(0, laid.width(), "the bars alone take no width");
        HudClient.Draw heart = find(laid, "icons", "heart");
        assertEquals(-94, heart.x());
        assertEquals(39, fromBottom(heart));
        HudClient.Draw frame = laid.draws().stream().filter(d -> d.sheet().equals("frame") && d.x() < 0 && fromBottom(d) == 39).findFirst().orElseThrow();
        assertEquals(-84, frame.x());
        assertEquals(71, width(frame));
        HudClient.Draw food = find(laid, "icons", "food");
        assertEquals(86, food.x());
        assertEquals(39, fromBottom(food));
        HudClient.Draw armor = find(laid, "icons", "armor");
        assertEquals(-94, armor.x());
        assertEquals(49, fromBottom(armor));
        for (HudClient.Draw d : laid.draws()) {
            if (d.sheet().equals("digits")) {
                assertTrue(d.x() >= -94 - 1 - 9 && d.x() + width(d) <= 95 + 1 + 9, "numbers just past the icons: " + d);
            } else {
                // (pixels -94 .. 94, mirrored about the middle column as the level number is centred on it)
                assertTrue(d.x() >= -94 && d.x() + width(d) <= 95, "bars and icons a little past the hotbar's ends (91): " + d);
            }
            assertTrue(d.x() + width(d) <= -13 || d.x() >= 14, "the middle stays free for the level number: " + d);
            // (so above the hotbar and the off-hand slot beside it, whose tops are 22 and 23 above the bottom)
            assertTrue(fromBottom(d) <= 49 && fromBottom(d) - sheets.sheet(d.sheet()).cellH() >= 30, "between the XP bar and the item name: " + d);
        }
        assertEquals(HudLayout.fill(18, 20, HudLayout.INNER), filled(laid, "red", 39));
        assertEquals(HudLayout.fill(17, 20, HudLayout.INNER), filled(laid, "food", 39));
        assertEquals(HudLayout.fill(12, 20, HudLayout.INNER), filled(laid, "armor", 49));
        // the health fill starts at the frame's inside, next to the heart; the hunger fill grows from its drumstick
        int hpLeft = laid.draws().stream().filter(d -> d.cell().startsWith("red/")).mapToInt(HudClient.Draw::x).min().orElseThrow();
        assertEquals(-83, hpLeft);
        int foodRight = laid.draws().stream().filter(d -> d.cell().startsWith("food/") && !d.cell().endsWith("edge"))
                .mapToInt(d -> d.x() + width(d)).max().orElseThrow();
        assertEquals(84, foodRight);
    }

    @Test
    void barsSpanTheHotbarAndMirrorEachOther() {
        // armor and air too, so every bar of both rows is drawn
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY.withAir(new HudLayout.Air(300, 300)), null));
        for (int row : new int[]{39, 49}) {
            HudClient.Draw left = laid.draws().stream().filter(d -> d.sheet().equals("frame") && d.x() < 0 && fromBottom(d) == row).findFirst().orElseThrow();
            HudClient.Draw right = laid.draws().stream().filter(d -> d.sheet().equals("frame") && d.x() > 0 && fromBottom(d) == row).findFirst().orElseThrow();
            assertEquals(width(left), width(right));
            assertEquals(-(right.x() + width(right)), left.x() - 1, "frames mirrored about the middle: " + row);
            int lIcon = laid.draws().stream().filter(d -> d.sheet().equals("icons") && d.x() < 0 && fromBottom(d) == row).findFirst().orElseThrow().x();
            int rIcon = laid.draws().stream().filter(d -> d.sheet().equals("icons") && d.x() > 0 && fromBottom(d) == row).findFirst().orElseThrow().x();
            assertEquals(-(rIcon + HudLayout.ICON), lIcon - 1, "icons mirrored: " + row);
            // the frames with their icons take all of the hotbar's 182 px and a little more (3 px a side); the
            // frames start at the level's room
            assertEquals(-94, lIcon);
            assertEquals(95, rIcon + HudLayout.ICON, "a little past the hotbar: " + lIcon + " / " + rIcon);
            assertEquals(-13, left.x() + width(left));
            assertEquals(14, right.x());
        }
        // a two-digit number on either side ends as far from the middle as the other
        int leftStart = laid.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() < 0 && fromBottom(d) == 38).mapToInt(HudClient.Draw::x).min().orElseThrow();
        int rightEnd = laid.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() > 0 && fromBottom(d) == 38).mapToInt(d -> d.x() + width(d)).max().orElseThrow();
        assertEquals(-rightEnd, leftStart - 1);
        // every part is as wide as the others: (inside + 1) / parts, its fill and a tick
        assertEquals(70, HudLayout.INNER + 1);
        for (int n : new int[]{2, 5, 7, 10, 14}) {
            List<Integer> ticks = HudLayout.ticks(n, HudLayout.INNER);
            int prev = -1;
            for (int t : ticks) {
                assertEquals((HudLayout.INNER + 1) / n - 1, t - prev - 1, n + " parts: " + ticks);
                prev = t;
            }
            assertEquals((HudLayout.INNER + 1) / n - 1, HudLayout.INNER - prev - 1, n + " parts, the last: " + ticks);
        }
        // any other count (3, 4, 6, 8, 9, 11 .. 13 parts): a pixel apart at most
        for (int n = 2; n <= HudLayout.MAX_SEGMENTS; n++) {
            List<Integer> parts = new java.util.ArrayList<>();
            int prev = -1;
            for (int t : HudLayout.ticks(n, HudLayout.INNER)) {
                parts.add(t - prev - 1);
                prev = t;
            }
            parts.add(HudLayout.INNER - prev - 1);
            int lo = parts.stream().mapToInt(Integer::intValue).min().orElseThrow(), hi = parts.stream().mapToInt(Integer::intValue).max().orElseThrow();
            assertTrue(hi - lo <= 1, n + " parts: " + parts);
        }
    }

    @Test
    void numbersSitBesideTheBarsNeverOnThem() {
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, null));
        assertEquals("18", number(laid, 39, true));
        assertEquals("17", number(laid, 39, false));
        assertEquals("12", number(laid, 49, true));
        for (HudClient.Draw d : laid.draws()) {
            if (!d.sheet().equals("digits")) continue;
            boolean onFrame = d.x() + width(d) > -84 - 1 && d.x() < -84 + 71 + 1 || d.x() + width(d) > 13 && d.x() < 14 + 71 + 1;
            boolean onIcon = d.x() + width(d) > -94 && d.x() < -94 + 9 || d.x() + width(d) > 86 && d.x() < 86 + 9;
            assertFalse(onIcon, "a number on an icon: " + d);
            assertFalse(onFrame, "a number on a bar: " + d);
        }
        // right-aligned against the heart on the left, left-aligned against the drumstick on the right
        int leftEnd = laid.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() < 0 && fromBottom(d) == 38)
                .mapToInt(d -> d.x() + width(d)).max().orElseThrow();
        assertEquals(-95, leftEnd);
        int rightStart = laid.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() > 0).mapToInt(HudClient.Draw::x).min().orElseThrow();
        assertEquals(96, rightStart);
        HudClient.Laid big = client.lay(layout.actionBar(new HudLayout.Health(100, 100, 0, 20, 20f, 0, HudLayout.Tint.NORMAL, false), null));
        assertTrue(big.draws().stream().allMatch(d -> d.x() >= -94 - 1 - 13), "three digits still sit close to the hotbar");
        // on screen at GUI 4 of a 1280 x 720 window (320 GUI px wide: 160 each side of the middle)
        assertTrue(big.draws().stream().allMatch(d -> d.x() >= -160 + 40 && d.x() + width(d) <= 160 - 40), "well on screen");
    }

    @Test
    void aMessageOfAnyLengthLeavesTheBarsInPlaceAndIsCentred() {
        List<HudClient.Draw> alone = client.lay(layout.actionBar(HEALTHY, null)).draws();
        for (String msg : List.of("a", "<gray>잔액이 부족합니다", "<green>작물 <white>토마토</white> · 3/5단계 · 다음 물 2분",
                "<yellow>OK</yellow>", "<red>⚠ 이 땅의 주인만 쓸 수 있어요 (홍길동)", "엄청나게 긴 안내 문구가 화면 가운데에 길게 길게 나옵니다 1234567890")) {
            Component m = mm(msg);
            HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, m));
            int w = layout.messageWidth(m);
            assertEquals(w, laid.width(), "only the message takes width: " + msg);
            List<HudClient.Draw> text = laid.draws().stream().filter(d -> d.sheet().equals("text") || d.sheet().equals("hangul")).toList();
            assertFalse(text.isEmpty());
            assertEquals(-(w / 2), text.getFirst().x(), "message starts where the game centres it: " + msg);
            assertTrue(text.stream().allMatch(d -> d.y() == -2), "baseline where the game's own action bar text has it");
            List<HudClient.Draw> bars = laid.draws().stream().filter(d -> !text.contains(d)).toList();
            assertEquals(alone, bars, "bars unchanged with message: " + msg);
        }
    }

    @Test
    void creativeOrSpectatorShowsOnlyTheMessage() {
        HudClient.Laid laid = client.lay(layout.actionBar(null, mm("<gray>안녕")));
        assertTrue(laid.draws().stream().allMatch(d -> d.sheet().equals("hangul")));
    }

    @Test
    void tintsAbsorptionHungerAndSaturation() {
        var h = new HudLayout.Health(6.5, 20, 4, 3, 2f, 0, HudLayout.Tint.POISON, true);
        HudClient.Laid laid = client.lay(layout.actionBar(h, null));
        find(laid, "icons", "heart_poison");
        find(laid, "icons", "food_hunger");
        assertEquals(HudLayout.fill(6.5, 20, HudLayout.INNER), filled(laid, "poison", 39));
        assertEquals(HudLayout.fill(10.5, 20, HudLayout.INNER) - HudLayout.fill(6.5, 20, HudLayout.INNER), filled(laid, "abs", 39), "absorption follows the health");
        assertTrue(drawn(laid, "line", "sat/"), "saturation line on the hunger bar");
        assertFalse(drawn(laid, "icons", "armor"), "no armor, no armor bar");
        assertEquals("11", number(laid, 39, true), "health and absorption together");
        assertTrue(laid.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() < 0).allMatch(d -> d.color() == HudLayout.GOLD.value()));
        // more than the maximum: the bar is scaled to fit it all
        var over = client.lay(layout.actionBar(new HudLayout.Health(20, 20, 16, 20, 5f, 0, HudLayout.Tint.NORMAL, false), null));
        assertEquals(HudLayout.INNER, filled(over, "red", 39) + filled(over, "abs", 39));
        for (var tint : HudLayout.Tint.values()) {
            client.lay(layout.actionBar(new HudLayout.Health(3, 20, 0, 20, 0f, 0, tint, false), null));
        }
        var low = client.lay(layout.actionBar(new HudLayout.Health(3, 20, 0, 20, 0f, 0, HudLayout.Tint.NORMAL, false), null));
        assertTrue(low.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() < 0).allMatch(d -> d.color() == HudLayout.LOW.value()));
    }

    @Test
    void mountAndAir() {
        var h = HEALTHY.withMount(new HudLayout.Mount(21, 30)).withAir(new HudLayout.Air(140, 300));
        HudClient.Laid laid = client.lay(layout.actionBar(h, null));
        HudClient.Draw mount = find(laid, "icons", "mount");
        assertEquals(86, mount.x());
        assertEquals(39, fromBottom(mount));
        assertFalse(drawn(laid, "icons", "food"), "riding: the mount's bar takes the hunger bar's place");
        assertEquals("21", number(laid, 39, false));
        assertEquals(HudLayout.fill(21, 30, HudLayout.INNER), filled(laid, "mount", 39));
        HudClient.Draw air = find(laid, "icons", "air");
        assertEquals(86, air.x());
        assertEquals(49, fromBottom(air));
        assertEquals("5", number(laid, 49, false), "bubbles left, as the game counts them");
        assertEquals(HudLayout.fill(140, 300, HudLayout.INNER), filled(laid, "air", 49));
        assertEquals(8 - 1, laid.draws().stream().filter(d -> d.sheet().equals("tick") && fromBottom(d) == 38 && d.x() > 0).count(), "a tick per two mount hearts");
        var drowning = client.lay(layout.actionBar(HEALTHY.withAir(new HudLayout.Air(-12, 300)), null));
        assertEquals("0", number(drowning, 49, false));
        assertEquals(0, filled(drowning, "air", 49));
        assertEquals(0, HudLayout.bubbles(0, 300));
        assertEquals(1, HudLayout.bubbles(1, 300));
        assertEquals(10, HudLayout.bubbles(300, 300));
    }

    @Test
    void damageAndHealingFlash() {
        var hit = client.lay(layout.actionBar(HEALTHY.withFlash(HudLayout.Flash.LOST, 20), null));
        assertEquals(HudLayout.fill(20, 20, HudLayout.INNER) - HudLayout.fill(18, 20, HudLayout.INNER), filled(hit, "lost", 39), "the part just lost");
        find(hit, "frame", "frame_hit");
        var heal = client.lay(layout.actionBar(HEALTHY.withFlash(HudLayout.Flash.GAIN, 10), null));
        assertEquals(HudLayout.fill(18, 20, HudLayout.INNER) - HudLayout.fill(10, 20, HudLayout.INNER), filled(heal, "gain", 39), "the part just gained");
        find(heal, "frame", "frame_heal");
        var plain = client.lay(layout.actionBar(HEALTHY, null));
        assertFalse(drawn(plain, "fill", "lost/") || drawn(plain, "fill", "gain/"));
    }

    @Test
    void segmentTicks() {
        assertEquals(10, HudLayout.segments(20, 2));
        assertEquals(12, HudLayout.segments(24, 2));
        assertEquals(14, HudLayout.segments(28, 2), "up to 14 hearts: a part each (5 px)");
        assertEquals(8, HudLayout.segments(30, 2), "more than 14 hearts: a tick every two");
        assertEquals(10, HudLayout.segments(40, 2));
        assertEquals(10, HudLayout.segments(300, 30));
        assertEquals(1, HudLayout.segments(1, 2));
        assertEquals(List.of(6, 13, 20, 27, 34, 41, 48, 55, 62), HudLayout.ticks(10, 69));
        assertEquals(List.of(4, 9, 14, 19, 24, 29, 34, 39, 44, 49, 54, 59, 64), HudLayout.ticks(14, 69));
        assertEquals(List.of(), HudLayout.ticks(1, 69));
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, null));
        List<HudClient.Draw> hp = laid.draws().stream().filter(d -> d.sheet().equals("tick") && d.x() < 0 && fromBottom(d) == 38).toList();
        assertEquals(9, hp.size());
        int k = HudLayout.fill(18, 20, HudLayout.INNER);
        assertEquals(62, k, "nine hearts fill nine parts, up to the ninth tick");
        for (HudClient.Draw t : hp) assertEquals(t.x() + 83 < k ? "on" : "off", t.cell(), "dark over the fill, faint beyond it");
    }

    @Test
    void numberFormatting() {
        assertEquals("18", HudLayout.healthText(18));
        assertEquals("18", HudLayout.healthText(17.5));
        assertEquals("1", HudLayout.healthText(0.2));
        assertEquals("0", HudLayout.healthText(0));
        assertEquals("20", HudLayout.healthText(20.0000001));
        assertEquals(0, HudLayout.fill(0, 20, 69));
        assertEquals(1, HudLayout.fill(0.01, 20, 69));
        assertEquals(69, HudLayout.fill(40, 20, 69));
        assertEquals(69, HudLayout.fill(20, 20, 69));
        assertEquals(34, HudLayout.fill(10, 20, 69), "five of ten parts: up to the fifth tick");
        assertEquals(3, HudLayout.fill(1, 20, 69), "half a heart: half a part");
        assertEquals(9, HudLayout.numbersWidth(2));
        assertEquals(0, HudLayout.numbersWidth(0));
    }

    @Test
    void lettersAreGalmuriPixelForPixel() {
        HudFont g9 = new HudFont("t9");
        g9.text(sheets, 9);
        assertEquals(10, g9.textAdvance('가'), "Hangul: 9 px and a pixel apart");
        assertEquals(10, g9.textAdvance('이'), "narrow syllables keep the font's advance (marked)");
        assertEquals(10, g9.textAdvance('뛰'));
        assertEquals(6, g9.textAdvance('A'));
        assertEquals(2, g9.textAdvance('i'));
        assertEquals(4, g9.textAdvance('·'));
        assertEquals(4, g9.textAdvance(' '));
        assertEquals(-1, g9.textAdvance('漢'));
        assertTrue(sheets.sheet("hangul").names().size() >= 2350);
        // Galmuri7 (the small panel) and the same letters twice as big (the large one): the game's rounding
        HudFont g7 = new HudFont("t7");
        g7.text(sheets, "text7", "hangul7", 7, 1);
        assertEquals(8, g7.textAdvance('가'));
        assertEquals(5, g7.textAdvance('A'));
        assertEquals(3, g7.textAdvance(' '));
        HudFont g14 = new HudFont("t14");
        g14.text(sheets, "text7", "hangul7", 7, 2);
        assertEquals(15, g14.textAdvance('가'), "7 visible px twice as big, and the game's one pixel");
        assertEquals(9, g14.textAdvance('A'));
        assertEquals(6, g14.textAdvance(' '));
    }

    @Test
    void panelLettersAreTheServersOwn() {
        HudSheets.Sheet panel = sheets.sheet(HudLayout.PANEL_HANGUL), panel7 = sheets.sheet(HudLayout.PANEL_HANGUL7);
        assertEquals(sheets.panelLetters().size(), panel.names().size(), "every syllable of the repository's texts, no more");
        assertEquals(panel.names(), panel7.names());
        assertTrue(panel.names().containsAll(List.of("잔", "액", "직", "업", "농", "부", "사", "건")));
        assertTrue(panel.names().size() < sheets.sheet("hangul").names().size() / 2, "a light sheet: " + panel.names().size());
        // a syllable no text uses is drawn as ? in the panel (the action bar still has every syllable)
        assertFalse(sheets.panelLetters().contains((int) '꽥'));
        HudClient.Laid laid = client.lay(layout.panel(List.of(new HudLayout.Row(null, mm("꽥"), false)), 212));
        assertTrue(laid.draws().stream().anyMatch(d -> "?".equals(d.cell())));
        assertEquals(1, client.lay(layout.actionBar(null, mm("꽥"))).draws().stream().filter(d -> d.cell().equals("꽥")).count());
        // the server's own letters come first, then the most used of the rest, up to the limit
        Map<Integer, Integer> counts = new java.util.HashMap<>();
        HudModule.count("똠똠똠 뷁", counts);
        int[] dropped = {0};
        Set<Integer> picked = HudModule.pickLetters(Set.of((int) '가'), counts, 2, n -> dropped[0] = n);
        assertEquals(Set.of((int) '가', (int) '똠'), picked);
        assertEquals(1, dropped[0]);
    }

    @Test
    void panelHugsItsRightEdgeWithColumns() {
        List<HudLayout.Row> rows = List.of(
                new HudLayout.Row("coin", mm("<gray>잔액\t<gold>12,345 G"), false),
                new HudLayout.Row("job", mm("<gray>직업\t<#7ec850>농부 <white>Lv12 <dark_gray>34%"), false),
                new HudLayout.Row("event", mm("<gray>사건\t<gold>풍년 <dark_gray>2시간"), true));
        HudLayout.Settings s = layout.settings();
        for (int right : new int[]{s.sidebarRight(), 238, 478, 958}) {
            HudClient.Laid laid = client.lay(layout.panel(rows, right));
            assertEquals(0, laid.width());
            HudClient.Draw cap = find(laid, "panel_head_m", "r");
            assertEquals(right, cap.x() + HudLayout.CAP, "the outline ends at the right edge (the shadow just past it)");
            HudClient.Draw corner = find(laid, "panel_head_m", "l");
            int x = corner.x();
            assertEquals(s.sidebarY(), corner.y() + 3, "boss bar pen line is y 3");
            assertEquals(x + HudLayout.MARK_X, find(laid, "panel_mark", "mark").x());
            assertFalse(drawn(laid, "panel_edit", ""), "no brackets unless it is being moved");
            HudClient.Draw coin = find(laid, "icons", "coin"), job = find(laid, "icons", "job");
            assertEquals(x + HudLayout.PAD, coin.x());
            assertEquals(coin.x(), job.x());
            assertEquals(HudLayout.ROW, job.y() - coin.y());
            // labels start at one x; values end at one x, PAD from the right edge
            Map<Integer, List<HudClient.Draw>> byRow = laid.draws().stream().filter(d -> d.shadow())
                    .collect(Collectors.groupingBy(HudClient.Draw::y));
            assertEquals(3, byRow.size());
            for (List<HudClient.Draw> line : byRow.values()) {
                assertEquals(x + HudLayout.TEXT_X, line.stream().mapToInt(HudClient.Draw::x).min().orElseThrow());
                int end = line.stream().mapToInt(d -> d.x() + sheets.sheet(d.sheet()).advance(d.cell())).max().orElseThrow();
                assertEquals(right - HudLayout.PAD, end, "value against the right edge");
                assertEquals(0, (line.getFirst().y() - coin.y()) % HudLayout.ROW, "rows on the grid");
            }
            assertTrue(drawn(laid, "panel_div", ""), "a divider above the event");
            assertTrue(right - x <= s.sidebarMaxWidth());
        }
    }

    @Test
    void panelDefaultFitsEverySixteenByNineScreenAtAutoScale() {
        for (int[] w : new int[][]{{1280, 720}, {2560, 1440}, {3840, 2160}}) {
            assertEquals(HudLayout.Settings.defaults().sidebarRight(), HudScreen.rightFor(w[0], w[1], 0, 2), w[0] + "x" + w[1]);
        }
    }

    @Test
    void longRowsWrapEvenlyThenShorten() {
        HudFont f = new HudFont("t");
        f.text(sheets, 7);
        var cells = HudText.cells(mm("<white>작물 판매가 +20%, 비료 효과 두 배"));
        int max = 110;
        var lines = HudText.wrap(f, cells, max, 2);
        assertEquals(2, lines.size());
        int a = HudText.width(f, lines.get(0)), b = HudText.width(f, lines.get(1));
        assertTrue(a <= max && b <= max);
        assertTrue(Math.abs(a - b) <= 30, "even lines, no word left alone: " + a + " / " + b);
        var longer = HudText.wrap(f, HudText.cells(mm("<gray>사건 <gold>대풍년 축제와 함께하는 아주 긴 이름의 사건이 진행 중입니다 남은 시간 3시간")), 90, 2);
        assertEquals(2, longer.size());
        for (var l : longer) assertTrue(HudText.width(f, l) <= 90, "line fits");
        assertEquals(HudText.ELLIPSIS, longer.getLast().getLast().cp());
        assertEquals(1, HudText.wrap(f, HudText.cells(mm("짧은 줄")), 90, 2).size());
        var cut = HudText.wrap(f, HudText.cells(mm("가나다라마바사아자차카타파하가나다라마바사")), 40, 3);
        assertTrue(cut.size() >= 2);
        for (var l : cut) assertTrue(HudText.width(f, l) <= 40);
    }

    @Test
    void labelAndValueSplitAtTheTab() {
        var parts = HudText.split(HudText.cells(mm("<gray>잔액\t<gold>12,345 G")));
        assertNotNull(parts);
        assertEquals("잔액", text(parts.get(0)));
        assertEquals("12,345 G", text(parts.get(1)));
        assertNull(HudText.split(HudText.cells(mm("탭 없음"))));
        assertEquals("a b", text(HudText.untab(HudText.cells(mm("a\tb")))));
        // a row too wide for columns wraps as text, the tab a space
        HudLayout narrow = new HudLayout(sheets, new HudLayout.Settings(212, 54, 80, 4, true));
        HudClient c = new HudClient(sheets, narrow.fonts());
        HudClient.Laid laid = c.lay(narrow.panel(List.of(new HudLayout.Row("coin", mm("<gray>아주 긴 이름의 줄\t<gold>12,345,678 G"), false)), 212));
        assertEquals(2, laid.draws().stream().filter(HudClient.Draw::shadow).map(HudClient.Draw::y).distinct().count());
    }

    static String text(List<HudText.Cell> cells) {
        StringBuilder sb = new StringBuilder();
        for (var c : cells) sb.appendCodePoint(c.cp());
        return sb.toString();
    }

    @Test
    void panelHonoursRowLimitAndUnknownCharacters() {
        HudLayout small = new HudLayout(sheets, new HudLayout.Settings(212, 54, 132, 2, true));
        HudClient c = new HudClient(sheets, small.fonts());
        Component panel = small.panel(List.of(
                new HudLayout.Row("coin", mm("하나"), false), new HudLayout.Row("job", mm("둘 ✿ 漢"), false),
                new HudLayout.Row("star", mm("셋"), false)), 212);
        HudClient.Laid laid = c.lay(panel);   // would throw on a character the row font lacks
        assertEquals(2, laid.draws().stream().filter(d -> d.sheet().equals("icons")).count());
        assertTrue(laid.draws().stream().anyMatch(d -> "?".equals(d.cell())));
        assertNull(small.panel(List.of(), 212));
    }

    @Test
    void fontsAreValid() {
        Map<String, JsonObject> fonts = layout.fonts();
        Map<String, byte[]> files = HudPack.files(layout, "WHITE", true);
        for (Map.Entry<String, JsonObject> f : fonts.entrySet()) {
            Set<Integer> seen = new HashSet<>();
            for (JsonElement e : f.getValue().getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if (!p.get("type").getAsString().equals("bitmap")) continue;
                int ascent = p.get("ascent").getAsInt(), height = p.get("height").getAsInt();
                assertTrue(ascent <= height, f.getKey() + ": ascent " + ascent + " > height " + height);
                String file = p.get("file").getAsString();
                assertTrue(files.containsKey("assets/stsv/textures/" + file.substring(5)), "missing " + file);
                int len = -1;
                for (JsonElement row : p.getAsJsonArray("chars")) {
                    int[] cps = row.getAsString().codePoints().toArray();
                    if (len >= 0) assertEquals(len, cps.length, f.getKey() + ": rows of one provider are equally long");
                    len = cps.length;
                    for (int cp : cps) if (cp != 0) assertTrue(seen.add(cp), f.getKey() + " declares U+" + Integer.toHexString(cp) + " twice");
                }
            }
            assertTrue(files.containsKey(f.getKey()));
        }
        assertNotNull(files.get("assets/stsv/textures/font/hud/LICENSE-galmuri.txt"), "the font's licence travels with it");
        assertNull(files.get("assets/stsv/textures/font/hud/hangul7.png"), "the whole Galmuri7 sheet is only cut from, not shipped");
        assertNotNull(files.get("assets/stsv/textures/font/hud/" + HudLayout.PANEL_HANGUL7 + ".png"));
    }

    @Test
    void fontsStayLightOnClientMemory() {
        long bytes = HudPack.clientMemory(layout);
        // the earlier HUD loaded about 28 MB (ten Unifont sheets of 2.4 MB), the one with a single panel size and
        // place 7.2 MB; three sizes and a range of heights (30 rows of letters) now, on letter sheets cut to the
        // server's own syllables
        assertTrue(bytes < 10L * 1024 * 1024, "client memory " + bytes);
        // and with as many syllables as the panel ever keeps
        HudSheets bundled = sheets;
        List<Integer> most = bundled.sheet("hangul").names().stream().map(n -> n.codePointAt(0)).limit(HudModule.MAX_PANEL_LETTERS).toList();
        HudLayout full = new HudLayout(HudLayout.panelSheets(bundled, most), HudLayout.Settings.defaults());
        assertTrue(HudPack.clientMemory(full) < 11L * 1024 * 1024, "client memory with every letter the panel keeps " + HudPack.clientMemory(full));
    }

    @Test
    void packHidesTheVanillaHudItReplaces() {
        Map<String, byte[]> files = HudPack.files(layout, "WHITE", true);
        String s = "assets/minecraft/textures/gui/sprites/";
        for (String n : List.of("hud/heart/full", "hud/heart/container", "hud/heart/poisoned_hardcore_half_blinking",
                "hud/heart/absorbing_full", "hud/heart/vehicle_full", "hud/heart/vehicle_container", "hud/food_full",
                "hud/food_empty_hunger", "hud/armor_half", "hud/air", "hud/air_bursting", "hud/air_empty",
                "boss_bar/white_background", "boss_bar/white_progress")) {
            assertNotNull(files.get(s + n + ".png"), n);
        }
        assertNull(files.get(s + "boss_bar/red_background.png"));
        // 26.3 has 47 heart sprites
        assertEquals(47, files.keySet().stream().filter(k -> k.startsWith(s + "hud/heart/")).count());
        Map<String, byte[]> noHealth = HudPack.files(layout, null, false);
        assertTrue(noHealth.keySet().stream().noneMatch(k -> k.startsWith("assets/minecraft/")));
    }

    @Test
    void sidebarToggleIsKept() {
        Byte[] stored = {null};
        HudToggle.Store store = new HudToggle.Store() {
            @Override
            public Byte get() {
                return stored[0];
            }

            @Override
            public void set(Byte value) {
                stored[0] = value;
            }
        };
        assertTrue(HudToggle.shown(store), "on by default");
        assertEquals(Boolean.FALSE, HudToggle.apply(store, null));
        assertEquals((byte) 0, stored[0]);
        assertFalse(HudToggle.shown(store));
        assertEquals(Boolean.TRUE, HudToggle.apply(store, null));
        assertNull(stored[0], "on again = nothing stored");
        assertEquals(Boolean.FALSE, HudToggle.apply(store, "끄기"));
        assertEquals(Boolean.FALSE, HudToggle.apply(store, "OFF"));
        assertEquals(Boolean.TRUE, HudToggle.apply(store, "켜기"));
        assertNull(HudToggle.apply(store, "뭐지"));
        assertTrue(HudToggle.shown(store), "an unknown word changes nothing");
    }

    @Test
    void packetsStaySmall() {
        var gson = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();
        List<HudLayout.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) rows.add(new HudLayout.Row("coin", mm("<gray>잔액\t<gold>12,345,678 G <white>농부 Lv" + i), i == 3));
        int panel = gson.serialize(layout.panel(rows, 212)).length();
        var worst = HEALTHY.withMount(new HudLayout.Mount(20, 30)).withAir(new HudLayout.Air(100, 300)).withFlash(HudLayout.Flash.LOST, 20);
        int bars = gson.serialize(layout.actionBar(worst, mm("<green>도감 등록 <white>토마토 <dark_gray>· <gray>작물 12/40"))).length();
        assertTrue(panel < 16_000, "panel JSON " + panel);
        assertTrue(bars < 8_000, "action bar JSON " + bars);
    }

    @Test
    void shiftsAddUp() {
        for (int dx : new int[]{0, 1, -1, 7, -300, 1023, 2047, -2047, 4000}) {
            int sum = 0;
            for (char c : HudFont.shift(dx).toCharArray()) sum += new HudFont("x").advance(c);
            assertEquals(dx, sum);
        }
    }
}
