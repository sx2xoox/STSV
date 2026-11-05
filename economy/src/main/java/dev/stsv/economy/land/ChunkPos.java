package dev.stsv.economy.land;

/** A 16x16 chunk column of a world (full height). */
public record ChunkPos(String world, int x, int z) {

    public static ChunkPos ofBlock(String world, int bx, int bz) {
        return new ChunkPos(world, bx >> 4, bz >> 4);
    }

    /** Its north-west block corner. */
    public int minX() {
        return x << 4;
    }

    public int minZ() {
        return z << 4;
    }

    public boolean touches(ChunkPos o) {
        return world.equals(o.world) && Math.abs(x - o.x) + Math.abs(z - o.z) == 1;
    }
}
