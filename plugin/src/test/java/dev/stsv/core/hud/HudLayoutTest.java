package dev.stsv.core.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
        layout = new HudLayout(bundled, HudLayout.Settings.defaults());
        sheets = layout.sheets();
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
        var hit = client.lay(layout.actionBar(HEALTHY.justChanged(20), null));
        assertEquals(HudLayout.fill(20, 20, HudLayout.INNER) - HudLayout.fill(18, 20, HudLayout.INNER), filled(hit, "lost", 39), "the part just lost");
        find(hit, "frame", "frame_hit");
        var heal = client.lay(layout.actionBar(HEALTHY.justChanged(10), null));
        assertEquals(HudLayout.fill(18, 20, HudLayout.INNER) - HudLayout.fill(10, 20, HudLayout.INNER), filled(heal, "gain", 39), "the part just gained");
        find(heal, "frame", "frame_heal");
        var plain = client.lay(layout.actionBar(HEALTHY, null));
        assertFalse(drawn(plain, "fill", "lost/") || drawn(plain, "fill", "gain/"));
        // a trail on the hunger and armor bars too, grown from the fill away from the icon; the armor bar stays
        // while its trail drains even when the armor is gone
        var anim = new HudLayout.Anim(0, 0, 9, 5);
        var both = client.lay(layout.actionBar(HEALTHY.withAnim(anim), null));
        assertEquals(9, filled(both, "lost", 39));
        assertEquals(5, filled(both, "lost", 49));
        int food = HudLayout.fill(17, 20, HudLayout.INNER);
        int trailLeft = both.draws().stream().filter(d -> d.cell().startsWith("lost/") && fromBottom(d) == 38).mapToInt(HudClient.Draw::x).min().orElseThrow();
        assertEquals(14 + HudLayout.INNER - (food + 9 - 1), trailLeft, "the hunger trail just left of its fill");
        var bare = client.lay(layout.actionBar(new HudLayout.Health(18, 20, 0, 17, 5f, 0, HudLayout.Tint.NORMAL, false).withAnim(new HudLayout.Anim(0, 0, 0, 20)), null));
        find(bare, "icons", "armor");
        assertEquals(20, filled(bare, "lost", 49));
        // never past the inside
        var over = client.lay(layout.actionBar(HEALTHY.withAnim(new HudLayout.Anim(500, 0, 0, 0)), null));
        assertEquals(HudLayout.INNER - HudLayout.fill(18, 20, HudLayout.INNER), filled(over, "lost", 39));
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

    /** The 2,350 syllables of KS X 1001 (EUC-KR's two-byte Hangul), in order. */
    static List<Integer> ksx1001() {
        java.nio.charset.Charset euc = java.nio.charset.Charset.forName("EUC-KR");
        return java.util.stream.IntStream.rangeClosed(0xAC00, 0xD7A3)
                .filter(cp -> new String(Character.toChars(cp)).getBytes(euc).length == 2).boxed().toList();
    }

    static String syllables(List<Integer> cps) {
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) sb.appendCodePoint(cp);
        return sb.toString();
    }

    /** The Hangul syllables a laid line draws, left to right, top to bottom ('?' for a letter drawn as '?'). */
    static String drawnSyllables(HudClient.Laid laid) {
        return laid.draws().stream().filter(d -> d.shadow() || d.sheet().startsWith("hangul") || "?".equals(d.cell()))
                .filter(d -> "?".equals(d.cell()) || (d.cell().codePointAt(0) >= 0xAC00 && d.cell().codePointAt(0) <= 0xD7A3))
                .sorted(java.util.Comparator.comparingInt(HudClient.Draw::y).thenComparingInt(HudClient.Draw::x))
                .map(HudClient.Draw::cell).collect(Collectors.joining());
    }

    @Test
    void everySyllableOfKsX1001IsDrawnEverywhere() {
        // the owner's report: 띵 (a bus passenger's line) and 컵 (a tournament nickname) were '?' / tofu, because the
        // panel's rows held only the syllables of the server's texts (1,024 at most)
        List<Integer> ks = ksx1001();
        assertEquals(2350, ks.size());
        assertTrue(ks.contains((int) '띵') && ks.contains((int) '컵'));
        // every font that draws text declares every one of them (and every syllable of its Hangul sheet)
        Set<Integer> sheetLetters = sheets.sheet("hangul").names().stream().map(n -> n.codePointAt(0)).collect(Collectors.toSet());
        assertTrue(sheetLetters.containsAll(ks));
        assertEquals(sheetLetters, sheets.sheet("hangul7").names().stream().map(n -> n.codePointAt(0)).collect(Collectors.toSet()));
        int textFonts = 0;
        for (var f : layout.fonts().entrySet()) {
            String name = f.getKey().substring(f.getKey().lastIndexOf('/') + 1);
            if (!name.startsWith("row_") && !name.startsWith("board_") && !name.equals("line.json")) continue;
            textFonts++;
            Set<Integer> declared = new HashSet<>();
            for (JsonElement e : f.getValue().getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if (p.get("type").getAsString().equals("bitmap")) {
                    for (JsonElement row : p.getAsJsonArray("chars")) row.getAsString().codePoints().forEach(declared::add);
                }
            }
            assertTrue(declared.containsAll(sheetLetters), f.getKey() + " lacks syllables");
        }
        assertEquals(1 + 12 + 11 + 8 + HudLayout.BOARD_SLOTS, textFonts, "the line, the panel's rows of three sizes, the board's");
        // and every one is drawn as itself through every path: 띵 and 컵 first, then all 2,350, eight to a row
        List<Integer> all = new java.util.ArrayList<>(List.of((int) '띵', (int) '컵'));
        all.addAll(ks);
        List<String> rows = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i += 8) rows.add(syllables(all.subList(i, Math.min(all.size(), i + 8))));
        for (HudLayout.Size size : HudLayout.Size.values()) {
            for (boolean low : new boolean[]{false, true}) {
                int n = layout.maxRows(size);
                int top = low ? layout.maxTop(size, n) : layout.minTop(size);
                for (int i = 0; i < rows.size(); i += n) {
                    List<String> chunk = rows.subList(i, Math.min(rows.size(), i + n));
                    List<HudLayout.Row> panel = chunk.stream().map(t -> new HudLayout.Row(null, Component.text(t), false)).toList();
                    HudClient.Laid laid = client.lay(layout.panel(panel, new HudLayout.Placement(size, 212, top), false));
                    assertEquals(String.join("", chunk), drawnSyllables(laid), size + " panel at " + top);
                }
            }
        }
        for (int i = 0; i < rows.size(); i += HudLayout.OVERLAY_ROWS) {
            List<String> chunk = rows.subList(i, Math.min(rows.size(), i + HudLayout.OVERLAY_ROWS));
            List<HudLayout.OverlayRow> overlay = chunk.stream().map(t -> new HudLayout.OverlayRow("fisher_1", null, Component.text(t))).toList();
            assertEquals(String.join("", chunk), drawnSyllables(client.lay(layout.overlay(overlay, -200))), "overlay");
        }
        for (int i = 0; i < rows.size(); i += HudLayout.BOARD_SLOTS) {
            List<String> chunk = rows.subList(i, Math.min(rows.size(), i + HudLayout.BOARD_SLOTS));
            HudLayout.Board b = new HudLayout.Board(Component.text(chunk.getFirst()),
                    chunk.subList(1, chunk.size()).stream().map(t -> new HudLayout.Board.Row(player(1), Component.text(t))).toList());
            assertEquals(String.join("", chunk), drawnSyllables(client.lay(layout.board(b, 240))), "board");
        }
        for (String r : rows) assertEquals(r, drawnSyllables(client.lay(layout.actionBar(null, Component.text(r)))), "action bar");
        // a nickname with them: name and score whole on the tournament board
        HudLayout.Board tournament = new HudLayout.Board(mm("<aqua>가장 큰 한 마리\t<white>12:34"), List.of(
                new HudLayout.Board.Row(player(1), mm("<gray>1 <white>띵컵\t<aqua>31.0cm"))));
        assertEquals("가장큰한마리띵컵", drawnSyllables(client.lay(layout.board(tournament, 240))));
    }

    @Test
    void aSyllableOutsideTheSheetsIsAQuestionMarkOfItsWidth() {
        // KS X 1001 lacks some of the 11,172 syllables (똠, 햏): they are '?' in the panel, the overlay and the board,
        // exactly as wide, so nothing after them moves
        assertFalse(sheets.sheet("hangul").has("똠"));
        HudClient.Laid panel = client.lay(layout.panel(List.of(new HudLayout.Row(null, mm("똠띵"), false)), 212));
        assertEquals("?띵", drawnSyllables(panel));
        assertEquals("?띵", drawnSyllables(client.lay(layout.overlay(List.of(new HudLayout.OverlayRow(null, null, mm("똠띵"))), -200))));
        HudLayout.Board odd = new HudLayout.Board(mm("똠\t1"), List.of()), plain = new HudLayout.Board(mm("?\t1"), List.of());
        assertEquals(layout.boardWidth(plain, 212), layout.boardWidth(odd, 212));
        assertEquals("?", drawnSyllables(client.lay(layout.board(odd, 212))));
        // the action bar's line has the game's font behind its own: there it is the game's glyph
        assertEquals(1, client.lay(layout.actionBar(null, mm("띵"))).draws().stream().filter(d -> d.cell().equals("띵")).count());
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
        assertNotNull(files.get("assets/stsv/textures/font/hud/hangul.png"));
        assertNotNull(files.get("assets/stsv/textures/font/hud/hangul7.png"), "every row draws from the whole sheets");
        assertTrue(files.keySet().stream().noneMatch(k -> k.contains("hangul_panel") || k.contains("board_faces")), "no subsets, no board faces");
    }

    @Test
    void fontsStayLightOnClientMemory() {
        long bytes = HudPack.clientMemory(layout);
        // the earlier HUD loaded about 28 MB (ten Unifont sheets of 2.4 MB), the one with a single panel size and
        // place 7.2 MB; three sizes and a range of heights (30 rows of letters) on letter sheets cut to the server's
        // own syllables (at most 1,024: 14.0 MB with the board) drew a syllable outside them as '?'. Every row of
        // letters now holds the whole sheet of its size (39 of them: the line, 11 + 12 + 8 panel rows, 7 board rows),
        // so every syllable of KS X 1001 shows everywhere: 18 Galmuri9 sheets of 0.77 MB, 20 Galmuri7 ones of 0.46 MB
        long g9 = 4L * 576 * 333, g7 = 4L * 448 * 259;
        assertEquals(g9, 4L * sheets.sheet("hangul").cols() * 9 * sheets.sheet("hangul").rows() * 9);
        assertEquals(g7, 4L * sheets.sheet("hangul7").cols() * 7 * sheets.sheet("hangul7").rows() * 7);
        assertTrue(bytes < 27L * 1024 * 1024 + 512 * 1024, "client memory " + bytes);
        long off = HudPack.clientMemory(new HudLayout(sheets, new HudLayout.Settings(212, 54, 132, 7, true)));
        assertTrue(off < 21L * 1024 * 1024 + 512 * 1024, "client memory without the board " + off);
        // the board: seven rows of Galmuri9 (Hangul 0.77 MB, ASCII 69 kB), the top one on cells 4 px (3 px) taller
        assertTrue(bytes - off < 7 * (g9 + 4L * 144 * 120) + 4L * 2350 * 9 * 4 + 4L * 184 * 9 * 3 + 16 * 1024, "the board costs " + (bytes - off));
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
        var worst = HEALTHY.withMount(new HudLayout.Mount(20, 30)).withAir(new HudLayout.Air(100, 300))
                .withAnim(new HudLayout.Anim(7, 0, 7, 7));
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

    @Test
    void theOverlayGoesOppositeTheSidebar() {
        // a sidebar against the right edge of a 427-wide GUI (right 213): the overlay starts 2 in from the left edge
        assertEquals(-211, HudLayout.overlayLeft(213, 132, 180, 2));
        // a sidebar against the left edge of a 480-wide GUI (its left edge at -240, 132 wide: right -108): the overlay
        // ends 2 in from the right edge (240), whatever its width
        assertEquals(240 - 2 - 180, HudLayout.overlayLeft(-108, 132, 180, 2));
        assertEquals(240 - 2 - 260, HudLayout.overlayLeft(-108, 132, 260, 2));
        int left = HudLayout.overlayLeft(-108, 132, 260, 2);
        assertTrue(left + 260 <= 240, "inside the screen");
    }

    /** A bus rider's overlay row, as BusModule makes it (bus.overlay-row / overlay-compact / overlay-talk + overlay-said). */
    static HudLayout.OverlayRow rider(String face, String mood, String color, String type, String dest, String left, String said) {
        if (said != null) return new HudLayout.OverlayRow(face, mood, mm("<" + color + ">" + type), mm("<#2a2630>" + said));
        return new HudLayout.OverlayRow(face, mood,
                mm("<" + color + ">" + type + "</" + color + "> <gray>→</gray> <white>" + dest + "</white> <gray>" + left),
                null, mm("<" + color + ">" + type + "</" + color + "> <gray>▶</gray><white>" + dest + "</white> <gray>" + left));
    }

    /** A full 16-seat bus (15 riders and the dog's seat taken too: 16 rows), the next to get off first, some long. */
    static List<HudLayout.OverlayRow> sixteenRiders() {
        return List.of(
                rider("grandma_2", "mood_great", "#e0a8ff", "할머니", null, null, "아이고 기사양반 천천히 가요"),
                rider("fisher_1", "mood_good", "#7cc4ff", "낚시꾼", "다음 정류장", "0:40", null),
                rider("haenyeo_1", "mood_good", "#5fd0c0", "해녀", "동항 앞", "1:12", null),
                rider("farmer_2", "mood_meh", "#a0d070", "농부", "동항 앞", "1:30", null),
                rider("courier_1", "mood_bad", "#ffb070", "택배기사", "시장 앞", "<red>지각</red>", null),
                rider("bride_1", "mood_great", "#ffd0e0", "신부", "북항 수산시장 입구", "2:05", null),
                rider("couple_1", "mood_good", "#ff9ab0", "커플 3팀", "북항 수산시장 입구", "2:05", null),
                rider("chatty_2", "mood_meh", "#c9d2dc", "수다쟁이", "동항선 2", "2:40", null),
                rider("drunk_1", "mood_bad", "#d8a060", "취객", "동항선 2", "2:40 <gray>Zz", null),
                rider("gentleman_1", "mood_good", "#c9d2dc", "신사", "동항 종점", "3:10", null),
                rider("ghost_1", "mood_great", "#b8f0ff", "★ 유령", "동항 종점", "3:10", null),
                rider("bungeo_1", "mood_good", "#ffcf80", "붕어빵 아저씨", "동항 종점 버스 회차장", "3:55", null),
                rider("camper_1", "mood_meh", "#90c080", "캠핑족", "동항 종점", "4:12", null),
                rider("dogwalker_1", "mood_good", "#d0b090", "강아지 할아버지", "동항 종점", "4:30", null),
                rider("fan_1", "mood_bad", "#ff80c0", "팬", "동항 종점", "5:00", null),
                rider("dealer", "mood_meh", "#888888", "선글라스 남자", "???", "???", null));
    }

    /** A sidebar like a player's (the preview's rows). */
    static List<HudLayout.Row> sidebarRows() {
        return List.of(
                new HudLayout.Row("coin", mm("<gray>잔액\t<gold>12,345 G"), false),
                new HudLayout.Row("job", mm("<#8bc34a>농부 <gray>Lv 12\t<white>34%"), false),
                new HudLayout.Row("mission", mm("<gray>미션\t<white>일일 2/3 · 주간 1/3"), false),
                new HudLayout.Row("event", mm("<gold>풍년\t<gray>2시간 10분"), true),
                new HudLayout.Row(null, mm("<gray>작물 판매가 +20%, 비료 효과 두 배"), false));
    }

    /** The overlay as HudModule places it for a sidebar at `at` (its width as drawn). */
    static HudClient.Laid busOverlay(List<HudLayout.OverlayRow> rows, HudLayout.Placement at, long now) {
        int sw = layout.panelWidth(sidebarRows(), at);
        int room = HudLayout.overlayRoom(at.right(), sw, 2);
        int left = HudLayout.overlayLeft(at.right(), sw, layout.overlayWidth(rows, room, now), 2);
        return client.lay(layout.overlay(rows, left, room, now));
    }

    /** The right edge of a drawn glyph (its last visible column + 1). */
    static int right(HudClient.Draw d) {
        return d.x() + width(d) + (d.shadow() ? 1 : 0);
    }

    @Test
    void sixteenRidersShowInTwoColumnsOnEveryScreen() {
        List<HudLayout.OverlayRow> bus = sixteenRiders();
        int w = 1920, h = 1080;
        for (int scale : new int[]{2, 3, 4}) {
            int gw = HudScreen.guiWidth(w, scale);
            int screenLeft = -(gw / 2), screenRight = gw - gw / 2;
            List<HudLayout.Placement> places = new java.util.ArrayList<>();
            places.add(layout.defaults());   // never moved (right 212)
            places.add(new HudLayout.Placement(HudLayout.Size.NORMAL, HudScreen.rightFor(w, h, scale, 2), 54));   // against the right edge
            HudClient.Laid probe = client.lay(layout.panel(sidebarRows(), new HudLayout.Placement(HudLayout.Size.NORMAL, 0, 54), false));
            int sw = -probe.draws().stream().mapToInt(HudClient.Draw::x).min().orElseThrow();
            places.add(new HudLayout.Placement(HudLayout.Size.NORMAL, screenLeft + 2 + sw, 54));   // against the left edge
            for (HudLayout.Placement at : places) {
                String where = "GUI " + scale + " sidebar at " + at.right();
                HudClient.Laid panel = client.lay(layout.panel(sidebarRows(), at, false));
                int panelLeft = panel.draws().stream().mapToInt(HudClient.Draw::x).min().orElseThrow();
                int panelRight = panel.draws().stream().mapToInt(HudLayoutTest::right).max().orElseThrow();
                HudClient.Laid laid = busOverlay(bus, at, 0);
                List<HudClient.Draw> faces = laid.draws().stream().filter(d -> d.sheet().equals("faces") && !d.cell().startsWith("mood_")).toList();
                assertEquals(16, faces.size(), where + ": every rider's face");
                assertEquals(16, faces.stream().map(d -> d.x() + "," + d.y()).distinct().count(), where + ": no two faces in one place");
                List<Integer> xs = faces.stream().map(HudClient.Draw::x).distinct().sorted().toList();
                assertEquals(2, xs.size(), where + ": two columns");
                List<Integer> ys = faces.stream().map(HudClient.Draw::y).distinct().sorted().toList();
                assertEquals(8, ys.size(), where + ": in the same 8 slots");
                assertEquals(8, faces.stream().filter(d -> d.x() == xs.getFirst()).count(), where + ": 8 in the first column");
                for (int i = 1; i < ys.size(); i++) assertEquals(HudLayout.ROW, ys.get(i) - ys.get(i - 1), where);
                // faces at the heights the first column's are drawn at (the pictures font holds them for slots 0..7 only)
                for (HudClient.Draw f : faces) assertTrue(ys.contains(f.y()), where);
                int split = xs.get(1);
                for (HudClient.Draw d : laid.draws()) {
                    assertTrue(d.x() >= screenLeft && right(d) <= screenRight, where + ": " + d + " on screen");
                    // the first column's glyphs end before the second column starts
                    if (d.x() < split) assertTrue(right(d) <= split - HudLayout.OVERLAY_COLUMN_GAP + 1, where + ": " + d + " runs into the second column");
                    // clear of the sidebar (it stays beside the overlay: no glyph of the overlay within its edges)
                    assertTrue(right(d) <= panelLeft || d.x() >= panelRight, where + ": " + d + " under the sidebar");
                }
                // on a narrow screen the long ones end in "…" rather than running on; on a wide one they are whole
                int room = HudLayout.overlayRoom(at.right(), layout.panelWidth(sidebarRows(), at), 2);
                if (layout.overlayWidth(bus) <= room) assertFalse(laid.draws().stream().anyMatch(d -> d.cell().equals("…")), where);
                else assertTrue(laid.draws().stream().anyMatch(d -> d.cell().equals("…")), where);
            }
        }
    }

    @Test
    void twoColumnsUseOnlyTheRowFontsThatAlreadyExist() {
        // no new fonts (each row font is a whole Hangul sheet, 0.77 MB): the second column draws with slots 0..7's
        Set<String> fonts = new HashSet<>();
        collectFonts(layout.overlay(sixteenRiders(), -211, 280, 0), fonts);
        Set<String> allowed = new HashSet<>(List.of("stsv:hud/panel_m"));
        for (int j = 0; j < HudLayout.OVERLAY_ROWS; j++) allowed.add("stsv:hud/row_m" + j);
        assertTrue(allowed.containsAll(fonts), "fonts " + fonts);
        assertTrue(fonts.contains("stsv:hud/row_m7"));
        // so the client memory is what it was: fontsStayLightOnClientMemory keeps its caps
    }

    static void collectFonts(Component c, Set<String> out) {
        if (c.style().font() != null) out.add(c.style().font().asString());
        for (Component ch : c.children()) collectFonts(ch, out);
    }

    @Test
    void eightOrFewerRidersLookAsBefore() {
        List<HudLayout.OverlayRow> five = sixteenRiders().subList(0, 5);
        assertEquals(layout.overlay(five, -211), layout.overlay(five, -211, 120, 0), "one column, not cut, whatever the room");
        assertEquals(layout.overlayWidth(five), layout.overlayWidth(five, 120, 0));
        HudClient.Laid laid = client.lay(layout.overlay(sixteenRiders().subList(0, 8), -211, 280, 0));
        assertEquals(1, laid.draws().stream().filter(d -> d.sheet().equals("faces")).map(HudClient.Draw::x).filter(x -> x == -211).distinct().count());
        assertEquals(8, laid.draws().stream().filter(d -> d.sheet().equals("faces") && !d.cell().startsWith("mood_")).count());
        assertTrue(drawn(laid, "bubble", "l"), "the bubble whole");
    }

    @Test
    void tooLittleRoomForTwoColumnsTakesTurns() {
        List<HudLayout.OverlayRow> bus = sixteenRiders();
        int room = 2 * HudLayout.OVERLAY_MIN_COLUMN + HudLayout.OVERLAY_COLUMN_GAP - 1;
        Set<String> seen = new HashSet<>();
        for (long now : new long[]{0, HudLayout.OVERLAY_PAGE_MILLIS}) {
            HudClient.Laid laid = client.lay(layout.overlay(bus, -211, room, now));
            List<HudClient.Draw> faces = laid.draws().stream().filter(d -> d.sheet().equals("faces") && !d.cell().startsWith("mood_")).toList();
            assertEquals(8, faces.size(), "a page of 8");
            assertEquals(1, faces.stream().map(HudClient.Draw::x).distinct().count(), "one column");
            for (HudClient.Draw d : laid.draws()) assertTrue(right(d) <= -211 + room + 1, d + " within the room");
            faces.forEach(f -> seen.add(f.cell()));
            assertTrue(layout.overlayWidth(bus, room, now) <= room);
        }
        assertEquals(16, seen.size(), "every rider within two pages");
        assertEquals(layout.overlay(bus, -211, room, 0), layout.overlay(bus, -211, room, 2 * HudLayout.OVERLAY_PAGE_MILLIS), "back to the first");
    }

    /** Top of a glyph from the top of the screen (the first boss bar's pen line is y 3). */
    static int fromTop(HudClient.Draw d) {
        return d.y() + 3;
    }

    static UUID player(int i) {
        return UUID.nameUUIDFromBytes(("player-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A tournament's board: the header, `rows` players and the viewer's own row. */
    static HudLayout.Board sampleBoard(int rows) {
        List<HudLayout.Board.Row> rs = new java.util.ArrayList<>();
        for (int i = 1; i <= rows; i++) rs.add(new HudLayout.Board.Row(player(i), mm("<gray>" + i + " <white>Steve\t<aqua>" + (60 - i) + ".5cm")));
        rs.add(new HudLayout.Board.Row(player(0), mm("<yellow>내 순위 8\t<aqua>31.0cm")));
        return new HudLayout.Board(mm("<aqua>가장 큰 한 마리\t<white>12:34"), rs);
    }

    static boolean face(HudClient.Draw d) {
        return d.sheet().startsWith("faces") || d.sheet().startsWith("board_faces");
    }

    static int boardRight() {
        return layout.settings().boardRight();
    }

    @Test
    void theBoardSitsInTheBottomRightCornerOnTheActionBar() {
        int right = 212, margin = layout.settings().boardMargin();
        assertEquals(2, margin);
        HudLayout.Board b = sampleBoard(5);
        Component board = layout.board(b, right);
        HudClient.Laid alone = client.lay(board);
        assertEquals(0, alone.width(), "zero net advance");
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, null, board));
        assertEquals(0, laid.width(), "the bars and the board take no width");
        int width = layout.boardWidth(b, right), x = right - width;
        assertTrue(x >= HudLayout.BOARD_CLEAR_X, "clear of the hotbar (91) and the bars' numbers (105): " + x);
        // the bars are where they were without the board
        Set<HudClient.Draw> bars = new HashSet<>(client.lay(layout.actionBar(HEALTHY, null)).draws());
        assertTrue(laid.draws().containsAll(bars), "the bars unchanged");
        List<HudClient.Draw> mine = laid.draws().stream().filter(d -> !bars.contains(d)).toList();
        assertTrue(mine.stream().noneMatch(HudLayoutTest::face), "no faces");
        // Galmuri9 (the normal panel's letters), a row every 11 px from the bottom up: the header the highest, every
        // line from the left edge, values against the right edge
        assertTrue(mine.stream().allMatch(d -> d.sheet().startsWith("text") || d.sheet().startsWith("hangul")), "the letters only");
        assertTrue(mine.stream().anyMatch(d -> d.sheet().startsWith("hangul") && !d.sheet().startsWith("hangul7")), "Galmuri9");
        Map<Integer, List<HudClient.Draw>> byRow = mine.stream().filter(HudClient.Draw::shadow).collect(Collectors.groupingBy(HudLayoutTest::fromBottom));
        assertEquals(List.of(12, 23, 34, 45, 56, 67, 78), byRow.keySet().stream().sorted().toList(), "header and six rows");
        assertEquals(margin, 12 - HudLayout.BOARD_CELL, "the lowest cell's foot margin px above the bottom");
        for (var e : byRow.entrySet()) {
            int start = e.getValue().stream().mapToInt(HudClient.Draw::x).min().orElseThrow();
            int end = e.getValue().stream().mapToInt(d -> d.x() + width(d)).max().orElseThrow();
            assertEquals(x, start, "row " + e.getKey());
            assertEquals(right - 1, end, "value against the right edge (its shadow column the last), row " + e.getKey());
        }
        String header = byRow.get(78).stream().sorted(java.util.Comparator.comparingInt(HudClient.Draw::x)).map(HudClient.Draw::cell).collect(Collectors.joining());
        assertTrue(header.startsWith("가장큰한") && header.endsWith("…12:34"), "the title cut, the time whole: " + header);
        String me = byRow.get(12).stream().sorted(java.util.Comparator.comparingInt(HudClient.Draw::x)).map(HudClient.Draw::cell).collect(Collectors.joining());
        assertTrue(me.startsWith("내순위8"), "the viewer's row the lowest: " + me);
        for (HudClient.Draw d : mine) {
            assertTrue(d.x() >= x && d.x() + width(d) <= right, "within the board: " + d);
            assertTrue(fromBottom(d) <= 78, "under the header's top: " + d);
        }
        // the lowest letters' shadow (a pixel under their 10 px cell) stays on the screen
        int lowestLetter = mine.stream().filter(HudClient.Draw::shadow).mapToInt(HudLayoutTest::fromBottom).min().orElseThrow();
        assertTrue(lowestLetter - HudLayout.BOARD_CELL - 1 >= 0, "on the screen: " + lowestLetter);
        assertNull(layout.board(null, right));
    }

    @Test
    void theBoardKeepsToTheBottomAtEveryGuiSize() {
        // the action bar's line is guiHeight - 72 whatever the GUI height: the board's lowest cell ends margin px over
        // the bottom on every window; against the screen's right edge (a sidebar the player put there) it ends there too
        HudLayout.Board b = sampleBoard(5);
        int[][] windows = {{1280, 720, 0}, {1920, 1080, 0}, {2560, 1440, 0}, {3840, 2160, 0}, {1920, 1080, 2}, {1920, 1080, 3}, {1366, 768, 0}, {2560, 1080, 0}};
        for (int[] w : windows) {
            int scale = HudScreen.guiScale(w[0], w[1], w[2]), gh = HudScreen.guiHeight(w[1], scale);
            int edge = HudScreen.rightFor(w[0], w[1], w[2], 0);
            int right = HudLayout.boardRight(212, edge, 132);
            HudClient.Laid laid = client.lay(layout.actionBar(null, null, layout.board(b, right)));
            int bottom = laid.draws().stream().filter(HudClient.Draw::shadow)
                    .mapToInt(d -> (gh - 72) + d.y() + HudLayout.BOARD_CELL).max().orElseThrow();
            assertEquals(gh - layout.settings().boardMargin(), bottom, "window " + w[0] + "x" + w[1] + " gui " + scale);
            int end = laid.draws().stream().mapToInt(d -> d.x() + width(d)).max().orElseThrow();
            assertEquals(right - 1, end, "the letters, their shadow column against the edge");
            assertTrue(end <= edge, "on the screen");
        }
    }

    @Test
    void theBoardsWidthRightEdgeAndCutNames() {
        // never left of x 106: at the default edge (212) at most 106 wide, against a 480 wide GUI's edge (240) the max width
        assertEquals(106, HudLayout.boardCap(212, 132));
        assertEquals(132, HudLayout.boardCap(240, 132));
        assertEquals(HudLayout.MIN_WIDTH, HudLayout.boardCap(150, 132), "never narrower than the panel's least");
        // the right edge: the configured one, or the screen's as the player's own sidebar place tells it
        assertEquals(212, HudLayout.boardRight(212, null, 132));
        assertEquals(240, HudLayout.boardRight(212, 240, 132), "a sidebar against the right edge of a 480 wide GUI");
        assertEquals(240, HudLayout.boardRight(212, -108, 132), "a sidebar against the left edge of a 480 wide GUI");
        assertEquals(HudLayout.BOARD_CLEAR_X + HudLayout.MIN_WIDTH, HudLayout.boardRight(212, 100, 132), "never so far in it covers the hotbar");
        // as wide as its widest row; a long name gives way to the score, which stays whole
        HudLayout.Board wide = new HudLayout.Board(mm("<aqua>가장 큰 한 마리\t<white>12:34"), List.of(
                new HudLayout.Board.Row(player(1), mm("<gray>1 <white>아주아주긴이름의낚시꾼님\t<aqua>123.4cm"))));
        assertEquals(106, layout.boardWidth(wide, 212));
        assertEquals(132, layout.boardWidth(wide, 240));
        HudClient.Laid laid = client.lay(layout.board(wide, 212));
        assertTrue(laid.draws().stream().anyMatch(d -> "…".equals(d.cell())), "the name shortened");
        String row = laid.draws().stream().filter(d -> d.shadow() && fromBottom(d) == 12)
                .sorted(java.util.Comparator.comparingInt(HudClient.Draw::x)).map(HudClient.Draw::cell).collect(Collectors.joining());
        assertTrue(row.endsWith("123.4cm"), "the score whole: " + row);
        for (HudClient.Draw d : laid.draws()) assertTrue(d.x() >= 212 - 106 && d.x() + width(d) <= 212, "inside: " + d);
        // a short board is only as wide as it needs
        HudLayout.Board small = new HudLayout.Board(mm("대회"), List.of(new HudLayout.Board.Row(null, mm("1위"))));
        assertTrue(layout.boardWidth(small, 212) < 30, "narrow: " + layout.boardWidth(small, 212));
    }

    @Test
    void theOlderBoardFormAndItsLimits() {
        // title, lines and a footer: the footer last; at most six rows
        List<Component> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) lines.add(mm("<gray>" + i + "위\t<aqua>" + i + "cm"));
        HudLayout.Board old = new HudLayout.Board(mm("제목"), lines, mm("남은 시간\t1:00"));
        assertEquals(HudLayout.BOARD_SLOTS - 1, old.rows().size());
        HudClient.Laid laid = client.lay(layout.board(old, 212));
        assertEquals(HudLayout.BOARD_SLOTS, laid.draws().stream().filter(HudClient.Draw::shadow).map(HudClient.Draw::y).distinct().count());
        int left = laid.draws().stream().mapToInt(HudClient.Draw::x).min().orElseThrow();
        assertEquals(212 - layout.boardWidth(old, 212), left);
        // the bars not drawn (creative): the board alone, the message still centred by its own width
        Component msg = mm("<white>안내");
        HudClient.Laid withMessage = client.lay(layout.actionBar(null, msg, layout.board(sampleBoard(5), 212)));
        assertEquals(client.lay(layout.actionBar(null, msg)).width(), withMessage.width());
        assertTrue(withMessage.draws().size() > client.lay(layout.actionBar(null, msg)).draws().size());
        // the highest margin keeps every row inside the screen's lower part, every cell as tall as it rises (fontsAreValid)
        HudLayout high = new HudLayout(sheets, new HudLayout.Settings(212, 54, 132, 7, true, true, 99, 212, 132));
        assertEquals(HudLayout.BOARD_MAX_MARGIN, high.settings().boardMargin());
        for (var f : high.fonts().entrySet()) {
            for (JsonElement e : f.getValue().getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if (p.get("type").getAsString().equals("bitmap")) assertTrue(p.get("ascent").getAsInt() <= p.get("height").getAsInt(), f.getKey());
            }
        }
        HudClient c = new HudClient(high.sheets(), high.fonts());
        int top = c.lay(high.board(sampleBoard(5), 212)).draws().stream().mapToInt(HudLayoutTest::fromBottom).max().orElseThrow();
        assertEquals(HudLayout.BOARD_MAX_MARGIN + 6 * HudLayout.BOARD_PITCH + HudLayout.BOARD_CELL, top);
        // with the board off (the old settings), no fonts and nothing drawn
        HudLayout off = new HudLayout(sheets, new HudLayout.Settings(212, 54, 132, 7, true));
        assertNull(off.board(sampleBoard(5), 212));
        assertTrue(off.fonts().keySet().stream().noneMatch(k -> k.contains("/board")));
    }
}
