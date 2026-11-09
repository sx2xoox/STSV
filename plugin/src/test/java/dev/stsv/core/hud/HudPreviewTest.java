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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes build/hud-preview.json: the pack HUD with the real messages, laid out by HudClient (from the generated
 * fonts), for tools/assetgen/hud.py --preview, which draws docs/previews/hud.png at GUI scales 2, 3 and 4.
 */
class HudPreviewTest {

    record Scene(int scale, String label, HudLayout.Health health, Component message) {}

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
        Component panel = layout.panel(Lang.mm("hud.sidebar-title"), rows);

        List<Scene> scenes = List.of(
                new Scene(2, "보통 · 방어 12 · 안내 문구", new HudLayout.Health(18, 20, 0, 17, 5f, 12, HudLayout.Tint.NORMAL, false, true),
                        Lang.mm("codex.registered", "item", "<white>토마토", "tab", "작물", "have", 12, "all", 40)),
                new Scene(3, "독 · 허기 · 흡수 4", new HudLayout.Health(7, 20, 4, 6, 0f, 7, HudLayout.Tint.POISON, true, true),
                        Lang.mm("tpa.arrived", "player", "Steve")),
                new Scene(4, "얼음 · 방어구 없음 · 문구 없음", new HudLayout.Health(11, 24, 0, 20, 12f, 0, HudLayout.Tint.FROZEN, false, true),
                        null));
        JsonArray out = new JsonArray();
        for (Scene s : scenes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("scale", s.scale());
            entry.addProperty("label", s.label());
            JsonArray draws = new JsonArray();
            for (HudClient.Draw d : client.lay(panel).draws()) draws.add(draw(d, "top", 3 + d.y()));
            for (HudClient.Draw d : client.lay(layout.actionBar(s.health(), s.message())).draws()) draws.add(draw(d, "bottom", d.y() - 72));
            entry.add("draws", draws);
            out.add(entry);
        }
        JsonObject root = new JsonObject();
        root.add("scales", out);
        Path file = Path.of("build", "hud-preview.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        assertTrue(Files.size(file) > 0);
        String all = root.toString();
        assertTrue(!all.contains("[economy.") && !all.contains("[job."), "every sample message exists");
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
