package dev.stsv.core.painting;

import java.util.List;

/**
 * The map palette (Minecraft's MapColor): 61 base colours, each in 4 shades. A map pixel is one byte, base * 4 + shade
 * (bytes over 127 are negative in Java). Shade 0 = 180/255 of the base, 1 = 220/255, 2 = the base itself, 3 = 135/255.
 * Base 0 is "nothing" (transparent): a painting never uses it.
 *
 * <p>The palette menu shows the bases in {@link #PAGES} order (9 a page, a column each) with the shades top to bottom
 * in {@link #SHADE_ROWS} (lightest first).
 */
public final class MapColors {

    private MapColors() {}

    /** RGB of the 62 bases (0 = none), as MapColor in 26.x. */
    static final int[] BASE = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
            0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19,
            0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919,
            0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A,
            0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464,
            0xD8AF93, 0x7FA796};

    /** Brightness of the four shades (out of 255). */
    static final int[] SHADE = {180, 220, 255, 135};

    public static final int BASES = BASE.length;            // 62, base 0 unused
    /** The canvas colour: snow white, its own shade (pure white). The eraser paints this. */
    public static final byte CANVAS = id(8, 2);
    /** A painter's first colour: black. */
    public static final byte BLACK = id(29, 2);

    /** The palette menu's rows: lightest shade at the top. */
    public static final int[] SHADE_ROWS = {2, 1, 0, 3};

    /** The bases a page of the palette shows (a column each): plain colours first, then earth, nether, terracotta. */
    public static final List<int[]> PAGES = List.of(
            new int[]{8, 22, 21, 29, 28, 4, 15, 18, 30},       // 흰 · 회색 · 검정 · 빨강 · 주황 · 노랑 · 금
            new int[]{19, 1, 7, 27, 33, 23, 31, 17, 12},       // 연두 · 풀 · 초록 · 에메랄드 · 청록 · 하늘 · 물
            new int[]{25, 32, 5, 24, 16, 20, 26, 13, 10},      // 파랑 · 청금석 · 얼음 · 보라 · 자홍 · 분홍 · 갈색 · 나무 · 흙
            new int[]{2, 14, 3, 6, 9, 11, 59, 60, 61},         // 모래 · 석영 · 양털 · 쇠 · 점토 · 돌 · 심층암 · 원석 · 이끼
            new int[]{34, 35, 52, 53, 54, 55, 56, 57, 58},     // 회백토 · 네더 · 진홍 · 뒤틀린
            new int[]{36, 37, 38, 39, 40, 41, 42, 43, 44},     // 테라코타
            new int[]{45, 46, 47, 48, 49, 50, 51});

    public static byte id(int base, int shade) {
        return (byte) (base * 4 + shade);
    }

    public static int base(byte id) {
        return (id & 0xFF) >> 2;
    }

    public static int shade(byte id) {
        return id & 3;
    }

    /** A colour a painting may hold: a real base (not "nothing"), any shade. */
    public static boolean valid(byte id) {
        int b = base(id);
        return b >= 1 && b < BASES;
    }

    /** 0xRRGGBB of a map colour (0 for an invalid one). */
    public static int rgb(byte id) {
        if (!valid(id)) return 0;
        int c = BASE[base(id)], m = SHADE[shade(id)];
        int r = (c >> 16 & 0xFF) * m / 255, g = (c >> 8 & 0xFF) * m / 255, b = (c & 0xFF) * m / 255;
        return r << 16 | g << 8 | b;
    }

    /** The map colour nearest to an RGB (weighted like Bukkit's MapPalette: the eye sees green best). */
    public static byte nearest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        byte best = CANVAS;
        double bestD = Double.MAX_VALUE;
        for (int base = 1; base < BASES; base++) {
            for (int shade = 0; shade < 4; shade++) {
                byte id = id(base, shade);
                int c = rgb(id);
                double d = distance(r, g, b, c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF);
                if (d < bestD) {
                    bestD = d;
                    best = id;
                }
            }
        }
        return best;
    }

    private static double distance(int r1, int g1, int b1, int r2, int g2, int b2) {
        double rmean = (r1 + r2) / 2.0;
        double dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        double wr = 2 + rmean / 256.0, wg = 4.0, wb = 2 + (255 - rmean) / 256.0;
        return wr * dr * dr + wg * dg * dg + wb * db * db;
    }

    /** How many colours the palette offers (every base in {@link #PAGES} times 4 shades). */
    public static int usable() {
        return PAGES.stream().mapToInt(p -> p.length).sum() * 4;
    }

    /** "#RRGGBB" of a map colour, for MiniMessage. */
    public static String hex(byte id) {
        return String.format("#%06x", rgb(id));
    }
}
