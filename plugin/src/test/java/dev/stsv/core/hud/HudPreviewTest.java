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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes build/hud-preview.json: the pack HUD with the real messages, laid out by HudClient (from the generated
 * fonts), for tools/assetgen/hud.py --preview, which draws docs/previews/hud.png (1920 x 1080 at GUI 2, 3, 4),
 * hud_anchor.png (the panel's place on other screens) and hud_states.png (the bars in every state, enlarged).
 * Also writes build/hud-memory.txt (what the fonts hold in the client's memory).
 */
class HudPreviewTest {

    record Scene(int width, int height, int scale, String label, Integer right, HudLayout.Health health, Component message,
                 boolean panel, int level, int effects, boolean water, int[] crop, int zoom, int thumb) {}

    static Scene full(int w, int h, int scale, String label, Integer right, HudLayout.Health health, Component message, int level, int effects) {
        return new Scene(w, h, scale, label, right, health, message, true, level, effects, false, null, 1, 0);
    }

    static Scene state(String label, HudLayout.Health health, Component message, int level, boolean water) {
        return new Scene(1920, 1080, 3, label, null, health, message, false, level, 0, water, new int[]{-100, 82, 200, 82}, 1, 0);
    }

    @Test
    void writeDrawList() throws Exception {
        Lang.use(YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml")));
        File eco = new File("../economy/src/main/resources/messages.yml");
        if (eco.exists()) Lang.useExtra("STSV-Economy", YamlConfiguration.loadConfiguration(eco));
        HudSheets sheets = HudSheets.load();
        HudLayout layout = new HudLayout(sheets, HudLayout.Settings.defaults());
        HudClient client = new HudClient(sheets, layout.fonts());

        List<HudLayout.Row> rows = List.of(
                new HudLayout.Row("coin", Lang.mm("economy.hud", "balance", Lang.raw("economy.amount", "amount", "12,345")), false),
                new HudLayout.Row("job", Lang.mm("job.hud", "color", "#8bc34a", "job", "농부", "level", 12, "pct", "34%"), false),
                new HudLayout.Row("mission", Lang.mm("mission.hud", "daily", 2, "daily_count", 3, "weekly", 1, "weekly_count", 3), false),
                new HudLayout.Row("event", Lang.mm("event.sidebar", "event", "풍년", "left", "2시간 10분"), true),
                new HudLayout.Row(null, Lang.mm("event.sidebar-detail", "detail", "작물 판매가 +20%, 비료 효과 두 배"), false));

        HudLayout.Health normal = new HudLayout.Health(18, 20, 0, 17, 5f, 12, HudLayout.Tint.NORMAL, false);
        Component codex = Lang.mm("codex.registered", "item", "<white>토마토", "tab", "작물", "have", 12, "all", 40);
        Map<String, List<Scene>> pages = new LinkedHashMap<>();
        pages.put("hud", List.of(
                full(1920, 1080, 2, "/사이드바 위치 1920x1080 2 · 보통 · 방어 12 · 안내 문구", HudPosition.rightFor(1920, 1080, 2), normal, codex, 23, 2),
                full(1920, 1080, 3, "/사이드바 위치 1920x1080 3 · 독 · 허기 · 흡수 4 · 피해 직후",
                        HudPosition.rightFor(1920, 1080, 3), new HudLayout.Health(7, 20, 4, 6, 0f, 7, HudLayout.Tint.POISON, true)
                                .withFlash(HudLayout.Flash.LOST, 15), Lang.mm("tpa.arrived", "player", "Steve"), 5, 1),
                full(1920, 1080, 4, "자동 · 기본 위치 · 물속 · 말 타기",
                        null, new HudLayout.Health(20, 20, 0, 20, 12f, 0, HudLayout.Tint.NORMAL, false)
                                .withMount(new HudLayout.Mount(17, 26)).withAir(new HudLayout.Air(170, 300)), null, 30, 0)));
        pages.put("hud_anchor", List.of(
                anchor(1280, 720, 0, "자동 · 기본 위치 = 화면 오른쪽 끝", null, normal),
                anchor(2560, 1440, 0, "자동 · 기본 위치 = 화면 오른쪽 끝", null, normal),
                anchor(2560, 1440, 3, "/사이드바 위치 2560x1440 3", HudPosition.rightFor(2560, 1440, 3), normal),
                anchor(1920, 1080, 1, "/사이드바 위치 1920x1080 1", HudPosition.rightFor(1920, 1080, 1), normal),
                anchor(1920, 1080, 2, "위치를 알려 주지 않으면 (기본 위치)", null, normal)));
        pages.put("hud_states", List.of(
                state("보통 · 방어 12 · 포화 5", normal, null, 23, false),
                state("피해 직후 (잃은 만큼 하얗게, 테두리 빨강)", normal.withFlash(HudLayout.Flash.LOST, 20), null, 23, false),
                state("회복 직후 (황금 사과: 흡수 4)", new HudLayout.Health(20, 20, 4, 18, 9f, 12, HudLayout.Tint.NORMAL, false)
                        .withFlash(HudLayout.Flash.GAIN, 14), null, 23, false),
                state("독 · 허기 · 체력 낮음", new HudLayout.Health(3, 20, 0, 4, 0f, 0, HudLayout.Tint.POISON, true), null, 7, false),
                state("시듦", new HudLayout.Health(9, 20, 0, 14, 0f, 5, HudLayout.Tint.WITHER, false), null, 12, false),
                state("동상 (가루눈)", new HudLayout.Health(11, 20, 0, 20, 14f, 20, HudLayout.Tint.FROZEN, false), null, 0, false),
                state("물속 (공기 6칸)", normal.withAir(new HudLayout.Air(172, 300)), null, 23, true),
                state("말 타기 (말 체력 21 / 30)", normal.withMount(new HudLayout.Mount(21, 30)), null, 23, false),
                state("최대 체력 40 · 방어 30 (칸 = 하트 2개)", new HudLayout.Health(33, 40, 0, 20, 20f, 30, HudLayout.Tint.NORMAL, false), null, 147, false),
                state("안내 문구와 함께", normal, codex, 23, false)));

        JsonObject root = new JsonObject();
        JsonObject out = new JsonObject();
        for (var page : pages.entrySet()) {
            JsonArray list = new JsonArray();
            for (Scene s : page.getValue()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("width", s.width());
                entry.addProperty("height", s.height());
                entry.addProperty("scale", s.scale() == 0 ? HudPosition.guiScale(s.width(), s.height(), 0) : s.scale());
                entry.addProperty("label", s.label());
                entry.addProperty("level", s.level());
                entry.addProperty("effects", s.effects());
                entry.addProperty("water", s.water());
                if (s.crop() != null) {
                    JsonArray crop = new JsonArray();
                    for (int v : s.crop()) crop.add(v);
                    entry.add("crop", crop);
                    entry.addProperty("zoom", s.zoom());
                }
                if (s.thumb() > 0) entry.addProperty("thumb", s.thumb());
                JsonArray draws = new JsonArray();
                if (s.panel()) {
                    int right = s.right() == null ? layout.settings().sidebarRight() : s.right();
                    for (HudClient.Draw d : client.lay(layout.panel(rows, right)).draws()) draws.add(draw(d, "top", 3 + d.y()));
                }
                for (HudClient.Draw d : client.lay(layout.actionBar(s.health(), s.message())).draws()) draws.add(draw(d, "bottom", d.y() - 72));
                entry.add("draws", draws);
                list.add(entry);
            }
            out.add(page.getKey(), list);
        }
        root.add("pages", out);
        Path file = Path.of("build", "hud-preview.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        assertTrue(Files.size(file) > 0);
        String all = root.toString();
        assertTrue(!all.contains("[economy.") && !all.contains("[job.") && !all.contains("[mission.") && !all.contains("[event."),
                "every sample message exists");

        long pack = 0;
        for (byte[] b : HudPack.files(sheets, layout, "WHITE", true).values()) pack += b.length;
        Files.writeString(Path.of("build", "hud-memory.txt"), "client memory (font sheets as RGBA): "
                + HudPack.clientMemory(sheets, layout) + " bytes\nHUD files in the pack (uncompressed): " + pack + " bytes\n");
    }

    static Scene anchor(int w, int h, int scale, String label, Integer right, HudLayout.Health health) {
        int s = HudPosition.guiScale(w, h, scale);
        return new Scene(w, h, s, label, right, health, null, true, 23, 2, false, null, 1, 960);
    }

    private static JsonObject draw(HudClient.Draw d, String anchor, int y) {
        JsonObject o = new JsonObject();
        o.addProperty("x", d.x());
        o.addProperty("y", y);
        o.addProperty("anchor", anchor);
        o.addProperty("sheet", d.sheet());
        o.addProperty("cell", d.cell());
        o.addProperty("color", String.format("%06x", d.color()));
        o.addProperty("shadow", d.shadow());
        return o;
    }
}
