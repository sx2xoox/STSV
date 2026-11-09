package dev.stsv.core.hud;

/**
 * The game's GUI size for a window (for the tests and the previews: the server is never told it). Scale "auto"
 * (0) is the largest whole scale that keeps the GUI at least 320 x 240 (a larger chosen scale is cut down to that
 * too); the GUI is ceil(window / scale) wide; a boss bar title's middle is at floor(width / 2), so ceil(width / 2)
 * px are right of it.
 */
final class HudScreen {

    private HudScreen() {}

    static int guiScale(int width, int height, int wanted) {
        int max = 1;
        while (max < width && max < height && width / (max + 1) >= 320 && height / (max + 1) >= 240) max++;
        return wanted <= 0 ? max : Math.min(wanted, max);
    }

    static int guiWidth(int width, int scale) {
        return -Math.floorDiv(-width, scale);
    }

    static int guiHeight(int height, int scale) {
        return -Math.floorDiv(-height, scale);
    }

    /** The panel's right edge (px right of the middle) that puts it `margin` px from the right of this window. */
    static int rightFor(int width, int height, int wanted, int margin) {
        int gw = guiWidth(width, guiScale(width, height, wanted));
        return gw - gw / 2 - margin;
    }
}
