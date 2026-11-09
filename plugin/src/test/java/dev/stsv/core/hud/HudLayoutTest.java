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

    static final HudLayout.Health HEALTHY = new HudLayout.Health(18, 20, 0, 17, 5f, 12, HudLayout.Tint.NORMAL, false, true);

    static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    @BeforeAll
    static void load() throws Exception {
        sheets = HudSheets.load();
        layout = new HudLayout(sheets, HudLayout.Settings.defaults());
        client = new HudClient(sheets, layout.fonts());
    }

    static HudClient.Draw find(HudClient.Laid laid, String sheet, String cell) {
        return laid.draws().stream().filter(d -> d.sheet().equals(sheet) && d.cell().equals(cell)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + sheet + ":" + cell + " drawn"));
    }

    /** Distance of a glyph top above the bottom of the screen (action bar pen line = guiHeight - 72). */
    static int fromBottom(HudClient.Draw d) {
        return 72 - d.y();
    }

    @Test
    void barsSitWhereTheGameDrewHeartsAndHunger() {
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, null));
        assertEquals(0, laid.width(), "the bars alone take no width");
        HudClient.Draw heart = find(laid, "icons", "heart");
        assertEquals(-91, heart.x());
        assertEquals(39, fromBottom(heart));
        HudClient.Draw frame = laid.draws().stream().filter(d -> d.cell().equals("frame")).toList().getFirst();
        assertEquals(-82, frame.x());
        assertEquals(39, fromBottom(frame));
        HudClient.Draw food = find(laid, "icons", "food");
        assertEquals(82, food.x(), "the drumstick ends where the game's last one did (x 90)");
        assertEquals(39, fromBottom(food));
        HudClient.Draw armor = find(laid, "icons", "armor");
        assertEquals(-91, armor.x());
        assertEquals(49, fromBottom(armor));
        HudClient.Draw fill = find(laid, "fill", "red/" + HudLayout.fill(18, 20, HudLayout.INNER));
        assertEquals(-81, fill.x());
        assertEquals(38, fromBottom(fill));
        HudClient.Draw foodFill = laid.draws().stream().filter(d -> d.cell().startsWith("food/")).findFirst().orElseThrow();
        int k = Integer.parseInt(foodFill.cell().substring(5));
        assertEquals(81, foodFill.x() + k, "hunger fills from the right, like the drumsticks");
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
            List<HudClient.Draw> text = laid.draws().stream().filter(d -> d.y() == 0).toList();
            assertFalse(text.isEmpty());
            assertEquals(-(w / 2), text.getFirst().x(), "message starts where the game centres it: " + msg);
            List<HudClient.Draw> bars = laid.draws().stream().filter(d -> d.y() != 0).toList();
            assertEquals(alone, bars, "bars unchanged with message: " + msg);
        }
    }

    @Test
    void creativeOrSpectatorShowsOnlyTheMessage() {
        HudClient.Laid laid = client.lay(layout.actionBar(null, mm("<gray>안녕")));
        assertTrue(laid.draws().stream().allMatch(d -> d.y() == 0));
    }

    @Test
    void tintsAbsorptionHungerAndMount() {
        var h = new HudLayout.Health(6.5, 20, 4, 3, 0f, 0, HudLayout.Tint.POISON, true, false);
        HudClient.Laid laid = client.lay(layout.actionBar(h, null));
        find(laid, "icons", "heart_poison");
        find(laid, "stripe", "abs/" + HudLayout.fill(4, 20, HudLayout.INNER));
        assertTrue(laid.draws().stream().noneMatch(d -> d.cell().startsWith("food")), "riding: the mount's hearts, no hunger bar");
        assertTrue(laid.draws().stream().noneMatch(d -> d.cell().equals("armor")), "no armor, no armor bar");
        // digits: "7+4 / 20" (+4 in gold)
        List<HudClient.Draw> digits = laid.draws().stream().filter(d -> d.sheet().equals("digits") && 72 - d.y() == 38).toList();
        assertEquals("7+4/20", digits.stream().map(HudClient.Draw::cell).reduce("", String::concat));
        assertTrue(digits.stream().filter(d -> d.cell().equals("+")).allMatch(d -> d.color() != 0xFFFFFF));
    }

    @Test
    void numbersAreCentredInTheBar() {
        HudClient.Laid laid = client.lay(layout.actionBar(HEALTHY, null));
        List<HudClient.Draw> hp = laid.draws().stream().filter(d -> d.sheet().equals("digits") && d.x() < 0 && 72 - d.y() == 38).toList();
        int left = hp.getFirst().x(), right = hp.getLast().x() + 5;
        int innerLeft = -81, innerRight = -81 + HudLayout.INNER;
        assertTrue(Math.abs((left - innerLeft) - (innerRight - right)) <= 1, "centred: " + left + ".." + right);
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
        assertEquals(35, HudLayout.fill(10, 20, 69));
        assertEquals(5 * 4 + 2 * 2 + 1, HudLayout.numbersWidth(HudLayout.digits("18 / 20", null)));
        assertEquals(0, HudLayout.numbersWidth(List.of()));
    }

    @Test
    void panelIsLeftAlignedAtItsPlaceAndTakesNoWidth() {
        List<HudLayout.Row> rows = List.of(
                new HudLayout.Row("coin", mm("<gray>잔액 <gold>12,345 G"), false),
                new HudLayout.Row("job", mm("<gray>직업 <#7ec850>농부 <white>Lv12 <dark_gray>34%"), false),
                new HudLayout.Row("event", mm("<gray>사건 <gold>풍년 <dark_gray>2시간"), true));
        Component panel = layout.panel(mm("<gradient:#ffb35c:#ff6a3d><b>STSV</b></gradient>"), rows);
        HudClient.Laid laid = client.lay(panel);
        assertEquals(0, laid.width());
        HudLayout.Settings s = layout.settings();
        HudClient.Draw corner = find(laid, "panel_head", "l");
        assertEquals(s.sidebarX(), corner.x());
        assertEquals(s.sidebarY(), corner.y() + 3, "boss bar pen line is y 3");
        // icons in a column, rows 11 px apart
        HudClient.Draw coin = find(laid, "icons", "coin"), job = find(laid, "icons", "job");
        assertEquals(coin.x(), job.x());
        assertEquals(11, job.y() - coin.y());
        assertEquals(s.sidebarX() + HudLayout.PAD, coin.x());
        // text of every row starts at the same x, right of its icon
        List<Integer> starts = laid.draws().stream().filter(d -> d.shadow() && d.y() + 3 > s.sidebarY() + HudLayout.HEAD - 1)
                .collect(java.util.stream.Collectors.groupingBy(HudClient.Draw::y)).values().stream()
                .map(l -> l.stream().mapToInt(HudClient.Draw::x).min().orElseThrow()).distinct().toList();
        assertEquals(List.of(s.sidebarX() + HudLayout.PAD + HudLayout.ICON + HudLayout.ICON_GAP), starts);
        // the event row has a divider above it
        find(laid, "panel_div", "l");
        // the right edge stays inside the configured width
        int right = laid.draws().stream().filter(d -> d.sheet().startsWith("panel_") && d.cell().equals("r")).mapToInt(HudClient.Draw::x).max().orElseThrow() + 4;
        assertTrue(right <= s.sidebarX() + s.sidebarMaxWidth());
        assertTrue(right <= 213, "fits a 16:9 screen at automatic GUI scale (guiWidth/2 >= 213)");
    }

    @Test
    void longRowsWrapThenShorten() {
        HudFont f = new HudFont("t");
        f.text(sheets, 7);
        var cells = HudText.cells(mm("<gray>사건 <gold>대풍년 축제와 함께하는 아주 긴 이름의 사건이 진행 중입니다 남은 시간 3시간"));
        var lines = HudText.wrap(f, cells, 90, 2);
        assertEquals(2, lines.size());
        for (var l : lines) assertTrue(HudText.width(f, l) <= 90, "line fits");
        assertEquals(HudText.ELLIPSIS, lines.getLast().getLast().cp());
        assertEquals(1, HudText.wrap(f, HudText.cells(mm("짧은 줄")), 90, 2).size());
        // a word longer than the line is cut, not lost
        var cut = HudText.wrap(f, HudText.cells(mm("가나다라마바사아자차카타파하가나다라마바사")), 40, 3);
        assertTrue(cut.size() >= 2);
        for (var l : cut) assertTrue(HudText.width(f, l) <= 40);
    }

    @Test
    void panelHonoursRowLimitAndUnknownCharacters() {
        HudLayout small = new HudLayout(sheets, new HudLayout.Settings(96, 54, 116, 2, true));
        HudClient c = new HudClient(sheets, small.fonts());
        Component panel = small.panel(mm("STSV"), List.of(
                new HudLayout.Row("coin", mm("하나"), false), new HudLayout.Row("job", mm("둘 ✿ 漢"), false),
                new HudLayout.Row("star", mm("셋"), false)));
        HudClient.Laid laid = c.lay(panel);   // would throw on a character the row font lacks
        assertEquals(2, laid.draws().stream().filter(d -> d.sheet().equals("icons")).count());
        assertTrue(laid.draws().stream().anyMatch(d -> "?".equals(d.cell())));
        assertNull(small.panel(mm("STSV"), List.of()));
    }

    @Test
    void fontsAreValid() {
        Map<String, JsonObject> fonts = layout.fonts();
        Map<String, byte[]> files = HudPack.files(sheets, layout, "WHITE", true);
        for (Map.Entry<String, JsonObject> f : fonts.entrySet()) {
            Set<Integer> seen = new HashSet<>();
            for (JsonElement e : f.getValue().getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if (!p.get("type").getAsString().equals("bitmap")) continue;
                int ascent = p.get("ascent").getAsInt(), height = p.get("height").getAsInt();
                assertTrue(ascent <= height, f.getKey() + ": ascent " + ascent + " > height " + height);
                String file = p.get("file").getAsString();
                if (file.startsWith("stsv:")) {
                    assertTrue(files.containsKey("assets/stsv/textures/" + file.substring(5)), "missing " + file);
                }
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
    }

    @Test
    void packHidesTheVanillaHudItReplaces() {
        Map<String, byte[]> files = HudPack.files(sheets, layout, "WHITE", true);
        String s = "assets/minecraft/textures/gui/sprites/";
        for (String n : List.of("hud/heart/full", "hud/heart/container", "hud/heart/poisoned_hardcore_half_blinking",
                "hud/heart/absorbing_full", "hud/food_full", "hud/food_empty_hunger", "hud/armor_half",
                "boss_bar/white_background", "boss_bar/white_progress")) {
            assertNotNull(files.get(s + n + ".png"), n);
        }
        assertNull(files.get(s + "hud/heart/vehicle_full.png"), "the mount's hearts stay");
        assertNull(files.get(s + "boss_bar/red_background.png"));
        // 26.3 has 47 heart sprites, 3 of them the mount's
        assertEquals(44, files.keySet().stream().filter(k -> k.startsWith(s + "hud/heart/")).count());
        Map<String, byte[]> noHealth = HudPack.files(sheets, layout, null, false);
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
        for (int i = 0; i < 8; i++) rows.add(new HudLayout.Row("coin", mm("<gray>잔액 <gold>12,345,678 G <white>직업 농부 Lv" + i), i == 3));
        int panel = gson.serialize(layout.panel(mm("<gradient:#ffb35c:#ff6a3d>STSV"), rows)).length();
        int bars = gson.serialize(layout.actionBar(HEALTHY, mm("<green>도감 등록 <white>토마토 <dark_gray>· <gray>작물 12/40"))).length();
        assertTrue(panel < 16_000, "panel JSON " + panel);
        assertTrue(bars < 6_000, "action bar JSON " + bars);
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
