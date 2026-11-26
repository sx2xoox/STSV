package dev.stsv.core.painting;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Draws a painting's pixels on its map, the same for every viewer (not contextual). It redraws only when the painting
 * changed since it last drew (its version), so the server calling it for every viewer every few ticks costs nothing.
 */
final class PaintingRenderer extends MapRenderer {

    private final Painting painting;
    private int drawn = Integer.MIN_VALUE;

    PaintingRenderer(Painting painting) {
        super(false);
        this.painting = painting;
    }

    Painting painting() {
        return painting;
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})   // map colour bytes are exactly what we store
    public void render(MapView view, MapCanvas canvas, Player player) {
        int v = painting.version();
        if (v == drawn) return;
        drawn = v;
        byte[] px = painting.canvas().raw();
        for (int y = 0; y < PaintCanvas.SIZE; y++) {
            for (int x = 0; x < PaintCanvas.SIZE; x++) canvas.setPixel(x, y, px[y * PaintCanvas.SIZE + x]);
        }
    }
}
