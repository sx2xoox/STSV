package dev.stsv.core.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The resource-pack files of the HUD: the glyph sheets, the generated fonts, and see-through copies of the game's
 * own HUD sprites the pack HUD replaces (every heart including the mount's, hunger, armor, air bubbles; the
 * background and progress of the boss bar colour the panel uses, so that bar shows its title only). Sprites are
 * 26.3's names (assets/minecraft/textures/gui/sprites/...), checked against the client.
 */
public final class HudPack {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final List<String> HEART_KINDS = List.of("", "absorbing_", "frozen_", "poisoned_", "withered_");

    private HudPack() {}

    /** Sprites the bars replace: every heart (the mount's too: its bar takes the hunger bar's place), hunger, armor, air. */
    static List<String> healthSprites() {
        List<String> out = new java.util.ArrayList<>();
        for (String c : List.of("container", "container_blinking", "container_hardcore", "container_hardcore_blinking",
                "vehicle_container", "vehicle_full", "vehicle_half")) {
            out.add("hud/heart/" + c);
        }
        for (String kind : HEART_KINDS) {
            for (String hc : List.of("", "hardcore_")) {
                for (String size : List.of("full", "half")) {
                    for (String blink : List.of("", "_blinking")) out.add("hud/heart/" + kind + hc + size + blink);
                }
            }
        }
        for (String f : List.of("food_empty", "food_empty_hunger", "food_full", "food_full_hunger", "food_half", "food_half_hunger",
                "armor_empty", "armor_full", "armor_half", "air", "air_bursting", "air_empty")) {
            out.add("hud/" + f);
        }
        return out;
    }

    /**
     * Every file (pack path -> bytes): the sheets the fonts use (the panel's letter subsets among them), the fonts.
     *
     * @param bossBarColour the BossBar.Color name whose bar the panel uses (its sprites become see-through), or
     *                      null when the panel is off
     * @param healthBars    whether the bars replace the hearts, hunger, armor and air bubbles
     */
    public static Map<String, byte[]> files(HudLayout layout, String bossBarColour, boolean healthBars) {
        Map<String, byte[]> out = new TreeMap<>();
        HudSheets sheets = layout.sheets();
        for (String name : used(layout)) out.put("assets/stsv/textures/font/hud/" + name + ".png", sheets.sheet(name).png());
        // the letters are drawn from Galmuri9 (SIL OFL 1.1): its notice travels with them
        try (java.io.InputStream in = HudPack.class.getResourceAsStream(HudSheets.ROOT + "LICENSE-galmuri.txt")) {
            if (in != null) out.put("assets/stsv/textures/font/hud/LICENSE-galmuri.txt", in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        layout.fonts().forEach((path, json) -> out.put(path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8)));
        if (healthBars) {
            byte[] blank = blank(9, 9);
            for (String s : healthSprites()) out.put("assets/minecraft/textures/gui/sprites/" + s + ".png", blank);
        }
        if (bossBarColour != null) {
            byte[] blank = blank(182, 5);
            String c = bossBarColour.toLowerCase(java.util.Locale.ROOT);
            out.put("assets/minecraft/textures/gui/sprites/boss_bar/" + c + "_background.png", blank);
            out.put("assets/minecraft/textures/gui/sprites/boss_bar/" + c + "_progress.png", blank);
        }
        return out;
    }

    /**
     * What the fonts hold in the client's memory, in bytes: the game loads a bitmap provider's whole sheet as RGBA
     * (4 bytes a pixel) once per provider, so a sheet used at several heights is loaded that many times. (Glyphs
     * drawn are also copied into the font atlas, a few 256 x 256 pages shared by every font.)
     */
    public static long clientMemory(HudLayout layout) {
        long bytes = 0;
        for (var font : layout.fonts().values()) {
            for (var e : font.getAsJsonArray("providers")) {
                var p = e.getAsJsonObject();
                if (!p.get("type").getAsString().equals("bitmap")) continue;
                HudSheets.Sheet s = layout.sheets().sheet(sheetOf(p.get("file").getAsString()));
                bytes += 4L * s.cols() * s.cellW() * s.rows() * s.cellH();
            }
        }
        return bytes;
    }

    /** The sheets the fonts draw from (a sheet only cut from, like the whole hangul7, is not shipped). */
    static java.util.Set<String> used(HudLayout layout) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (var font : layout.fonts().values()) {
            for (var e : font.getAsJsonArray("providers")) {
                var p = e.getAsJsonObject();
                if (p.get("type").getAsString().equals("bitmap")) out.add(sheetOf(p.get("file").getAsString()));
            }
        }
        return out;
    }

    private static String sheetOf(String file) {
        return file.substring(file.lastIndexOf('/') + 1).replace(".png", "");
    }

    static byte[] blank(int w, int h) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
