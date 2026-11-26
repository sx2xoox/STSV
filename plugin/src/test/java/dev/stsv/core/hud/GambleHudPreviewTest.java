package dev.stsv.core.hud;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes build/gamble-hud-preview.json for tools/assetgen/gamble_scene_preview.py (docs/previews/gamble_hud.png): what a
 * seated 도박장 player sees at the bottom of the screen (the hand on the action bar as one line, the game's 족보 / rules on
 * the bottom-right board) and the fishing tournament's board everyone else sees, laid out by the real HudLayout /
 * HudClient from the generated fonts, with the economy's own message templates (economy messages.yml). The rows and the
 * line are built the way HallTable.board / HallTable.actionLine and TournamentModule.board build them (those need a
 * server; the same keys, the same order, the same placeholders here; Jokbo.SUTDA's pages).
 *
 * <p>The 섯다 hand (초보방, ante 10, five players), the second street: 삥 10, 하프 +45, 하프 +120, now the viewer's turn: pot
 * 225, the viewer put 10, 120 to call, 38광땡 in hand (its row lit on the 족보's first page).
 */
class GambleHudPreviewTest {

    static final UUID ME = UUID.nameUUIDFromBytes("me".getBytes(StandardCharsets.UTF_8));

    /** Jokbo.SUTDA (economy): 섯다's pages. */
    static final List<List<String>> JOKBO = List.of(
            List.of("gwang38", "gwang18", "gwang13", "ttaeng10", "ttaeng", "ttaeng1"),
            List.of("ali", "doksa", "gupping", "jangpping", "jangsa", "seryuk"),
            List.of("kkeut", "ttaengjabi", "amhaeng", "gusa", "meong-gusa", "kkeut-note"));

    /** Jokbo.POKER (economy): 세븐 포커's pages. */
    static final List<List<String>> POKER = List.of(
            List.of("royal", "back-straight-flush", "straight-flush", "four-card", "full-house", "flush"),
            List.of("mountain", "back-straight", "straight", "triple", "two-pair", "one-pair"),
            List.of("top", "note-suit", "note-best", "note-open", "note-back", "note-limit"));

    /** HallTable.board for 세븐 포커: a page of its 족보, my made hand's row lit (null: none, the page's note instead). */
    static HudLayout.Board poker(int page, String mine, String ranks) {
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        for (String r : POKER.get(page))
            rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.poker-jokbo." + r, "c", Lang.raw(r.equals(mine) ? "hwatu.jokbo-mine" : "hwatu.jokbo-row"))));
        if (mine != null) return new HudLayout.Board(Lang.mm("hwatu.jokbo-title-mine", "hand", Lang.raw("hwatu.poker-hand." + mine, "ranks", ranks)), rows);
        String note = Lang.raw("hwatu.poker-jokbo-notes.p" + (page + 1));
        return new HudLayout.Board(Lang.mm("hwatu.jokbo-title", "page", page + 1, "pages", POKER.size(), "note", note), rows);
    }

    /** HallTable.board for 바카라: the payouts, a round's bets, what I may still bet today, the road. */
    static HudLayout.Board baccarat(long left, String road) {
        StringBuilder r = new StringBuilder();
        for (char c : road.toCharArray())
            r.append(Lang.raw("hwatu.bac.road." + (c == 'P' ? "player" : c == 'B' ? "banker" : "tie")));
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 6; i++)
            rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.rules.baccarat-" + i, "min", fmt(10), "max", fmt(1000), "cap", fmt(5000), "left", fmt(left),
                    "commission", 5, "tie", 8, "road", r.toString())));
        return new HudLayout.Board(Lang.mm("hwatu.rules-title", "game", "바카라", "room", "초보방"), rows);
    }

    /** 세븐 포커, the 5th card's round, my turn: pot 380, 60 to call, 투페어 K·7 in hand. */
    static Component pokerLine(long held) {
        return line(Lang.raw("hwatu.line.my-turn", "seconds", 12), Lang.raw("hwatu.line.pot", "pot", fmt(380)), Lang.raw("hwatu.line.to-call", "n", fmt(60)),
                Lang.raw("hwatu.line.hand", "hand", Lang.raw("hwatu.poker-hand.two-pair", "ranks", "K·7")), Lang.raw("hwatu.line.chips", "n", fmt(held)));
    }

    /** 바카라, the bets open: 100 on 플레이어, 10 on 타이, the 50 chip picked, 4,890 left today. */
    static Component baccaratLine(long held) {
        String bets = Lang.raw("hwatu.bac.line-bet", "spot", Lang.raw("hwatu.bac.spot-short.player"), "n", fmt(100)) + " "
                + Lang.raw("hwatu.bac.line-bet", "spot", Lang.raw("hwatu.bac.spot-short.tie"), "n", fmt(10));
        return line(Lang.raw("hwatu.bac.line-bets", "seconds", 9), bets, Lang.raw("hwatu.bac.line-left", "n", fmt(4890)),
                Lang.raw("hwatu.line.chips", "n", fmt(held)));
    }

    /** The board's widest at the default right edge (hud.board.right 212: up to x 106, past the hotbar and the bars). */
    static final int DEFAULT_CAP = 212 - HudLayout.BOARD_CLEAR_X;

    static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** HallTable.board for 섯다: a page of the 족보, my hand's row lit (null: none, the note of the page instead). */
    static HudLayout.Board jokbo(int page, String mineRow, String handCode) {
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        for (String r : JOKBO.get(page))
            rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.jokbo." + r, "c", Lang.raw(r.equals(mineRow) ? "hwatu.jokbo-mine" : "hwatu.jokbo-row"))));
        if (handCode != null) return new HudLayout.Board(Lang.mm("hwatu.jokbo-title-mine", "hand", Lang.raw("hwatu.hand." + handCode)), rows);
        String note = Lang.raw("hwatu.jokbo-notes.p" + (page + 1));
        return new HudLayout.Board(Lang.mm("hwatu.jokbo-title", "page", page + 1, "pages", JOKBO.size(), "note", note), rows);
    }

    /** HallTable.board for 주사위 / 홀짝: the rules. */
    static HudLayout.Board rules(String game, String name, long ante) {
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 6; i++) rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.rules." + game + "-" + i, "ante", fmt(ante), "rake", 5, "roll", 10, "dice", 2)));
        return new HudLayout.Board(Lang.mm("hwatu.rules-title", "game", name, "room", "초보방"), rows);
    }

    /** HallTable.actionLine: the parts joined by hwatu.line.sep. */
    static Component line(String... parts) {
        return Lang.mm("hwatu.line.all", "line", String.join(Lang.raw("hwatu.line.sep"), parts));
    }

    /** The 섯다 hand above, on the viewer's turn of the second street. */
    static Component sutdaLine(long seconds, long held) {
        return line(Lang.raw("hwatu.line.my-turn", "seconds", seconds), Lang.raw("hwatu.line.pot", "pot", fmt(225)),
                Lang.raw("hwatu.line.to-call", "n", fmt(120)),
                Lang.raw("hwatu.line.hand", "hand", Lang.raw("hwatu.hand.gwang38")), Lang.raw("hwatu.line.chips", "n", fmt(held - 10)));
    }

    /** TournamentModule.board: the top 5, then the viewer's own place (8th). */
    static HudLayout.Board tournament() {
        String[][] top = {{"띵작낚시꾼", "61.2"}, {"컵라면한사발", "58.9"}, {"월척헌터", "55.0"}, {"붕어빵", "49.7"}, {"바다곰띵", "47.3"}};
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        for (int i = 0; i < top.length; i++)
            rows.add(new HudLayout.Board.Row(UUID.randomUUID(), Lang.mm("tournament.board-line", "place", i + 1, "name", top[i][0],
                    "score", Lang.raw("tournament.unit.cm", "n", top[i][1]))));
        rows.add(new HudLayout.Board.Row(ME, Lang.mm("tournament.board-me", "place", 8, "score", Lang.raw("tournament.unit.cm", "n", "31.0"))));
        return new HudLayout.Board(Lang.mm("tournament.board-title", "title", Lang.raw("tournament.kind.big"), "time", "12:34"), rows);
    }

    @Test
    void writeGambleHud() throws Exception {
        Lang.use(YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml")));
        File eco = new File("../economy/src/main/resources/messages.yml");
        assertTrue(eco.exists(), "economy messages");
        Lang.useExtra("STSV-Economy", YamlConfiguration.loadConfiguration(eco));
        HudLayout layout = new HudLayout(HudSheets.load(), HudLayout.Settings.defaults());
        HudClient client = new HudClient(layout.sheets(), layout.fonts());

        List<HudLayout.Row> sidebar = List.of(
                new HudLayout.Row("coin", Lang.mm("economy.hud", "balance", Lang.raw("economy.amount", "amount", "12,345")), false),
                new HudLayout.Row("job", Lang.mm("job.hud", "color", "#8bc34a", "job", "어부", "level", 12, "pct", "34%"), false),
                new HudLayout.Row("mission", Lang.mm("mission.hud", "daily", 2, "daily_count", 3, "weekly", 1, "weekly_count", 3), false));
        HudLayout.Health health = new HudLayout.Health(18, 20, 0, 17, 5f, 0, HudLayout.Tint.NORMAL, false);

        int w = 1920, h = 1080, scale = 2;
        HudLayout.Placement atEdge = new HudLayout.Placement(HudLayout.Size.NORMAL, HudScreen.rightFor(w, h, scale, 2), 54);
        Component sutda = sutdaLine(14, 1530);
        Component firstStreet = line(Lang.raw("hwatu.line.turn", "seconds", 17), Lang.raw("hwatu.line.pot", "pot", fmt(60)),
                Lang.raw("hwatu.line.one-card", "card", "8광"), Lang.raw("hwatu.line.chips", "n", fmt(1520)));
        Component dice = line(Lang.raw("hwatu.line.my-roll", "seconds", 8), Lang.raw("hwatu.line.pot", "pot", fmt(40)), Lang.raw("hwatu.line.chips", "n", fmt(990)));
        Component holjjak = line(Lang.raw("hwatu.line.turn", "seconds", 6), Lang.raw("hwatu.line.pot", "pot", fmt(20)),
                Lang.raw("hwatu.line.call", "call", Lang.raw("hwatu.hand.even")), Lang.raw("hwatu.line.chips", "n", fmt(240)));
        JsonArray scenes = new JsonArray();
        scenes.add(scene(layout, client, "hwatu", "섯다 · 두 번째 장, 내 차례 (액션바 한 줄 · 보드 = 족보, 내 38광땡 줄)",
                w, h, scale, sidebar, null, health, jokbo(0, "gwang38", "gwang38"), sutda));
        scenes.add(scene(layout, client, "hwatu", "섯다 · 첫 장 (한 장: 족보는 몇 초마다 넘어감 · 2/3쪽)",
                w, h, scale, sidebar, null, health, jokbo(1, null, null), firstStreet));
        scenes.add(scene(layout, client, "hwatu", "섯다 · /사이드바 위치 로 사이드바를 오른쪽 끝에 붙인 사람 (보드도 화면 끝) · 3/3쪽",
                w, h, scale, sidebar, atEdge, health, jokbo(2, null, null), sutda));
        scenes.add(scene(layout, client, "hwatu", "주사위 · 내가 굴릴 차례 (보드 = 규칙)",
                w, h, scale, sidebar, null, health, rules("dice", "주사위", 10), dice));
        scenes.add(scene(layout, client, "hwatu", "홀짝 · 상대가 굴릴 차례",
                w, h, scale, sidebar, null, health, rules("holjjak", "홀짝", 10), holjjak));
        scenes.add(scene(layout, client, "hwatu", "세븐 포커 · 다섯 번째 장 베팅, 내 차례 (보드 = 포커 족보, 내 투페어 줄)",
                w, h, scale, sidebar, null, health, poker(1, "two-pair", "K·7"), pokerLine(1240)));
        scenes.add(scene(layout, client, "hwatu", "바카라 · 베팅 시간 (보드 = 배당 · 오늘 남은 베팅 · 최근 결과)",
                w, h, scale, sidebar, null, health, baccarat(4890, "PBBPTPBBPPBT"), baccaratLine(1520)));
        scenes.add(scene(layout, client, "fishing", "낚시대회 순위 · 테이블에 앉지 않은 사람 (앉은 사람은 족보: 보드 우선순위)",
                w, h, scale, sidebar, null, health, tournament(), null));

        // everything fits: each board row at the default right edge (no ellipsis), each line clear of the board's corner
        for (int p = 0; p < JOKBO.size(); p++) fits(layout, jokbo(p, null, null));
        fits(layout, jokbo(0, "gwang38", "gwang38"));
        fits(layout, jokbo(2, "meong-gusa", "meong-gusa"));
        fits(layout, rules("dice", "주사위", 500));
        fits(layout, rules("holjjak", "홀짝", 500));
        for (int p = 0; p < POKER.size(); p++) fits(layout, poker(p, null, null));
        fits(layout, poker(0, "full-house", "K·3"));
        fits(layout, poker(0, "flush", "♠ A"));
        fits(layout, poker(0, "back-straight-flush", ""));
        fits(layout, baccarat(5000, "PBBPTPBBPPBT"));
        // a long one: a 6-digit stack, 4-digit bets (큰손방), 13광땡: too wide with "판 12,500", which ActionLine drops first
        Component longest = line(Lang.raw("hwatu.line.my-turn", "seconds", 20),
                Lang.raw("hwatu.line.to-call", "n", fmt(5_000)),
                Lang.raw("hwatu.line.hand", "hand", Lang.raw("hwatu.hand.gwang13")), Lang.raw("hwatu.line.chips", "n", fmt(123_456)));
        Component pokerLong = line(Lang.raw("hwatu.line.my-turn", "seconds", 20), Lang.raw("hwatu.line.to-call", "n", fmt(5_000)),
                Lang.raw("hwatu.line.hand", "hand", Lang.raw("hwatu.poker-hand.back-straight-flush", "ranks", "")), Lang.raw("hwatu.line.chips", "n", fmt(123_456)));
        for (Component c : List.of(sutdaLine(20, 98_765), longest, firstStreet, dice, holjjak, pokerLine(1240), pokerLong, baccaratLine(1520)))
            assertTrue(layout.messageWidth(c) <= 2 * HudLayout.BOARD_CLEAR_X, "the action bar's line stays off the board's corner: "
                    + layout.messageWidth(c) + " px: " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c));

        JsonObject root = new JsonObject();
        root.add("scenes", scenes);
        Path file = Path.of("build", "gamble-hud-preview.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        String all = root.toString();
        assertFalse(all.contains("[hwatu.") || all.contains("[tournament."), "every template exists");
    }

    /** Every row of the board (and its title) as wide as it wants at the default right edge: nothing cut short. */
    static void fits(HudLayout layout, HudLayout.Board b) {
        List<HudLayout.Board.Row> all = new ArrayList<>(b.rows());
        all.add(new HudLayout.Board.Row(null, b.title()));
        for (HudLayout.Board.Row r : all) {
            int natural = layout.boardWidth(new HudLayout.Board(Component.empty(), List.of(r)), 1000);
            assertTrue(natural <= DEFAULT_CAP, natural + " px > " + DEFAULT_CAP + ": "
                    + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(r.text()));
        }
    }

    static JsonObject scene(HudLayout layout, HudClient client, String kind, String label, int w, int h, int scale,
                            List<HudLayout.Row> sidebar, HudLayout.Placement placed, HudLayout.Health health, HudLayout.Board board,
                            Component message) {
        HudLayout.Placement at = placed == null ? layout.defaults() : placed;
        // HudModule.boardFor: the configured right edge, or the edge the player's own sidebar is against
        int right = HudLayout.boardRight(layout.settings().boardRight(), placed == null ? null : placed.right(),
                layout.panelWidth(sidebar, at));
        Component drawn = layout.board(board, right);
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("label", label);
        o.addProperty("width", w);
        o.addProperty("height", h);
        o.addProperty("scale", scale);
        o.addProperty("boardRight", right);
        o.addProperty("boardWidth", layout.boardWidth(board, right));
        o.addProperty("rowsAsked", board.rows().size());
        JsonArray asked = new JsonArray();
        o.add("rowsKept", asked);
        for (HudLayout.Board.Row r : board.rows()) asked.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(r.text()));
        JsonArray draws = new JsonArray();
        for (HudClient.Draw d : client.lay(layout.panel(sidebar, at, false)).draws()) draws.add(draw(d, "top", 3 + d.y(), "panel"));
        Set<HudClient.Draw> bars = new HashSet<>(client.lay(layout.actionBar(health, null)).draws());
        Set<HudClient.Draw> line = message == null ? Set.of() : new HashSet<>(client.lay(layout.actionBar(null, message)).draws());
        for (HudClient.Draw d : client.lay(layout.actionBar(health, message, drawn)).draws())
            draws.add(draw(d, "bottom", d.y() - 72, bars.contains(d) ? "bars" : line.contains(d) ? "line" : "board"));
        o.addProperty("lineWidth", message == null ? 0 : layout.messageWidth(message));
        o.add("draws", draws);
        return o;
    }

    private static JsonObject draw(HudClient.Draw d, String anchor, int y, String part) {
        JsonObject o = new JsonObject();
        o.addProperty("x", d.x());
        o.addProperty("y", y);
        o.addProperty("anchor", anchor);
        o.addProperty("sheet", d.sheet().replaceFirst("_p\\d+$", ""));
        o.addProperty("scale", d.scale());
        o.addProperty("cell", d.cell());
        o.addProperty("color", String.format("%06x", d.color()));
        o.addProperty("shadow", d.shadow());
        o.addProperty("part", part);
        return o;
    }
}
