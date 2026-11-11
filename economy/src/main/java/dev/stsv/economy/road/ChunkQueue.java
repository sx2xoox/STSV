package dev.stsv.economy.road;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Chunks loaded (and generated where new) asynchronously, a few at a time, each handed to a callback on the main thread
 * as it arrives (Paper's World#getChunkAtAsync): the 하역장 scout and the 국도 survey read heightmaps this way without
 * stalling the server. {@link #pump} every tick starts more while fewer than `concurrent` are on the way.
 */
public final class ChunkQueue {

    private final World world;
    private final int concurrent;
    private final Consumer<Chunk> each;
    private final Deque<long[]> waiting = new ArrayDeque<>();
    private int inFlight, done, failed;
    private boolean stopped;

    public ChunkQueue(World world, int concurrent, Consumer<Chunk> each) {
        this.world = world;
        this.concurrent = Math.max(1, concurrent);
        this.each = each;
    }

    public void add(int cx, int cz) {
        waiting.add(new long[]{cx, cz});
    }

    /** Starts loads while there is room; call every tick. */
    public void pump() {
        pump(concurrent);
    }

    /**
     * Starts loads while fewer than max (at most the queue's own `concurrent`) are on the way: {@link LoadGate} lowers it
     * while the server is busy or players are online, 0 starts none (those on the way still arrive).
     */
    public void pump(int max) {
        int limit = Math.min(concurrent, max);
        while (!stopped && inFlight < limit && !waiting.isEmpty()) {
            long[] c = waiting.poll();
            inFlight++;
            world.getChunkAtAsync((int) c[0], (int) c[1], true).whenComplete((chunk, err) -> {
                inFlight--;
                if (stopped) return;
                if (err != null || chunk == null) {
                    failed++;
                    return;
                }
                done++;
                each.accept(chunk);
            });
        }
    }

    public void stop() {
        stopped = true;
        waiting.clear();
    }

    /** Nothing left to load and nothing on the way. */
    public boolean finished() {
        return waiting.isEmpty() && inFlight == 0;
    }

    public int done() {
        return done;
    }

    public int left() {
        return waiting.size() + inFlight;
    }

    public int failed() {
        return failed;
    }
}
