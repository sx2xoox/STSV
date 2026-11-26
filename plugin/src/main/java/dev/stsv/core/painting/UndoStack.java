package dev.stsv.core.painting;

import java.util.ArrayDeque;
import java.util.Deque;

/** The last strokes of a painting, newest on top; the oldest falls off past the limit. Empty strokes are not kept. */
public final class UndoStack {

    public static final int DEFAULT_LIMIT = 20;
    private final Deque<PaintCanvas.Stroke> strokes = new ArrayDeque<>();
    private final int limit;

    public UndoStack() {
        this(DEFAULT_LIMIT);
    }

    public UndoStack(int limit) {
        this.limit = Math.max(1, limit);
    }

    public void push(PaintCanvas.Stroke stroke) {
        if (stroke == null || stroke.isEmpty()) return;
        strokes.push(stroke);
        while (strokes.size() > limit) strokes.removeLast();
    }

    /** Undoes the newest stroke on the canvas; false when there is none. */
    public boolean undo(PaintCanvas canvas) {
        PaintCanvas.Stroke s = strokes.poll();
        if (s == null) return false;
        canvas.undo(s);
        return true;
    }

    public int size() {
        return strokes.size();
    }

    public void clear() {
        strokes.clear();
    }
}
