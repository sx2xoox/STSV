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
 * Writes build/gamble-hud-preview.json for tools/assetgen/gamble_scene_preview.py (docs/previews/gamble_hud.png): the
 * bottom-right board of a seated 섯다 player mid-hand and of a fishing tournament, laid out by the real HudLayout /
 * HudClient from the generated fonts, with the economy's own message templates (economy messages.yml). The rows are
 * built the way HallTable.board and TournamentModule.board build them (those need a server; the same keys, the same
 * order, the same placeholders here).
 *
 * <p>The 섯다 hand (초보방, ante 10, five players): 삥 10, 하프 +45, 하프 +120 (Betting.amount: 45 + max(10, (105 + 45) / 2)),
 * now the viewer's turn: pot 225, the viewer put the ante 10, 120 to call, 38광땡 in hand.
 */
class GambleHudPreviewTest {

    static final UUID ME = UUID.nameUUIDFromBytes("me".getBytes(StandardCharsets.UTF_8));

    static String fmt(long n) {
        return String.format("%,d", n);
    }

    /** HallTable.board (PLAYING, 섯다, the viewer in the hand and on turn), row for row. */
    static HudLayout.Board sutda(long seconds, String bettor, long held) {
        long pot = 225, ante = 10, put = 10, toCall = 120;
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        rows.add(new HudLayout.Board.Row(ME, Lang.mm("hwatu.board-my-turn", "player", "나", "seconds", seconds)));
        rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-pot", "pot", fmt(pot), "ante", fmt(ante))));
        rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-put", "n", fmt(put))));
        rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-hand", "hand", Lang.raw("hwatu.hand.gwang38"))));
        String act = Lang.raw("hwatu.act.half", "n", fmt(120));   // HallTable.act
        rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-bet", "player", bettor, "act", act)));
        rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-to-call", "n", fmt(toCall))));
        rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-chips", "n", fmt(held - put))));
        return new HudLayout.Board(Lang.mm("hwatu.board-title", "game", "섯다", "room", "초보방"), rows);
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
        JsonArray scenes = new JsonArray();
        scenes.add(scene(layout, client, "hwatu", "섯다 · 기본 자리 (사이드바를 옮긴 적 없음: 보드 오른쪽 끝 = 가운데 +212)",
                w, h, scale, sidebar, null, health, sutda(14, "달빛여우", 1530)));
        scenes.add(scene(layout, client, "hwatu", "섯다 · /사이드바 위치 로 사이드바를 오른쪽 끝에 붙인 사람 (보드도 화면 끝)",
                w, h, scale, sidebar, atEdge, health, sutda(14, "달빛여우", 1530)));
        scenes.add(scene(layout, client, "fishing", "낚시대회 순위 · 기본 자리",
                w, h, scale, sidebar, null, health, tournament()));

        JsonObject root = new JsonObject();
        root.add("scenes", scenes);
        Path file = Path.of("build", "gamble-hud-preview.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        String all = root.toString();
        assertFalse(all.contains("[hwatu.") || all.contains("[tournament."), "every template exists");
    }

    static JsonObject scene(HudLayout layout, HudClient client, String kind, String label, int w, int h, int scale,
                            List<HudLayout.Row> sidebar, HudLayout.Placement placed, HudLayout.Health health, HudLayout.Board board) {
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
        for (HudClient.Draw d : client.lay(layout.actionBar(health, null, drawn)).draws())
            draws.add(draw(d, "bottom", d.y() - 72, bars.contains(d) ? "bars" : "board"));
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
