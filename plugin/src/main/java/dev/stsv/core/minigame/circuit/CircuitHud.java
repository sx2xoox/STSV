package dev.stsv.core.minigame.circuit;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.time.Duration;

/**
 * The race's pictures on the screen, from the resource pack's fonts (tools/assetgen/circuit_art.py draws them into
 * content/stsv/assets/stsv): the big title pictures (3, 2, 1, GO!, FINISH!, places, the wrong-way sign, a new record)
 * and the small icons of the boss bar (laps, place, clock, the boost gauge's cells). The characters here and the
 * script's must agree (CircuitArtTest).
 */
public final class CircuitHud {

    public static final Key TITLE_FONT = Key.key("stsv", "circuit_title");
    public static final Key ICON_FONT = Key.key("stsv", "circuit");

    // the title pictures
    public static final char THREE = '', TWO = '', ONE = '', GO = '', FINISH = '', WRONG = '',
            RECORD = '', LAP = '';
    /** 1위 .. 12위: PLACE + place - 1 */
    public static final char PLACE = '';
    // the boss bar's icons
    public static final char FLAG = '', CUP = '', CLOCK = '', CELL = '', CELL_EMPTY = '', SEG = '',
            SEG_EMPTY = '', KART = '', BOLT = '';
    public static final int SEGMENTS = 10;

    private CircuitHud() {}

    public static Component big(char c) {
        return Component.text(String.valueOf(c)).font(TITLE_FONT).color(NamedTextColor.WHITE);
    }

    public static Component icon(char c) {
        return Component.text(String.valueOf(c)).font(ICON_FONT).color(NamedTextColor.WHITE);
    }

    public static Title title(char picture, Component sub, int fadeIn, int stay, int fadeOut) {
        return Title.title(big(picture), sub == null ? Component.empty() : sub,
                Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L)));
    }

    /** The place picture (1위 .. 12위; past 12 the last). */
    public static char place(int place) {
        return (char) (PLACE + Math.clamp(place, 1, 12) - 1);
    }

    /** The boss bar line: laps, place, the clock, kept boosts and the gauge. */
    public static Component bar(int lap, int laps, int place, int racers, String clock, int stored, double gauge, boolean boosting) {
        Component sep = Component.text("   ");
        Component out = icon(FLAG).append(Component.text(" " + Math.max(1, Math.min(lap, laps)) + "/" + laps, NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, true));
        out = out.append(sep).append(icon(CUP)).append(Component.text(" " + place + "위", place == 1 ? TextColor.color(0xffd36b) : NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, true)).append(Component.text("/" + racers, NamedTextColor.GRAY));
        out = out.append(sep).append(icon(CLOCK)).append(Component.text(" " + clock, NamedTextColor.WHITE));
        out = out.append(sep).append(icon(BOLT)).append(Component.space());
        for (int k = 0; k < BoostGauge.MAX_STORED; k++) out = out.append(icon(k < stored ? CELL : CELL_EMPTY));
        out = out.append(Component.space());
        int filled = (int) Math.floor(Math.clamp(gauge, 0, 1) * SEGMENTS);
        StringBuilder seg = new StringBuilder();
        for (int k = 0; k < SEGMENTS; k++) seg.append(k < filled ? SEG : SEG_EMPTY);
        out = out.append(Component.text(seg.toString()).font(ICON_FONT).color(boosting ? TextColor.color(0xff9a3c) : NamedTextColor.WHITE));
        return out;
    }
}
