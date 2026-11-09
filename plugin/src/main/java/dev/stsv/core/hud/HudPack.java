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
 * own HUD sprites the pack HUD replaces (hearts except the mount's, hunger, armor; the background and progress of
 * the boss bar colour the panel uses, so that bar shows its title only). Sprites are 26.3's names
 * (assets/minecraft/textures/gui/sprites/...), checked against the client.
 */
public final class HudPack {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final List<String> HEART_KINDS = List.of("", "absorbing_", "frozen_", "poisoned_", "withered_");

    private HudPack() {}

    /** Sprites the health bars replace: every heart except the mount's (riding still shows its hearts). */
    static List<String> healthSprites() {
        List<String> out = new java.util.ArrayList<>();
        for (String c : List.of("container", "container_blinking", "container_hardcore", "container_hardcore_blinking")) {
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
                "armor_empty", "armor_full", "armor_half")) {
            out.add("hud/" + f);
        }
        return out;
    }

    /**
     * Every file (pack path -> bytes).
     *
     * @param bossBarColour the BossBar.Color name whose bar the panel uses (its sprites become see-through), or
     *                      null when the panel is off
     * @param healthBars    whether the bars replace the hearts, hunger and armor
     */
    public static Map<String, byte[]> files(HudSheets sheets, HudLayout layout, String bossBarColour, boolean healthBars) {
        Map<String, byte[]> out = new TreeMap<>();
        for (HudSheets.Sheet s : sheets.all()) out.put("assets/stsv/textures/font/hud/" + s.name() + ".png", s.png());
        // the Hangul glyphs come from GNU Unifont (OFL 1.1 / GPL with font exception): its notice travels with them
        try (java.io.InputStream in = HudPack.class.getResourceAsStream(HudSheets.ROOT + "LICENSE-unifont.txt")) {
            if (in != null) out.put("assets/stsv/textures/font/hud/LICENSE-unifont.txt", in.readAllBytes());
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
