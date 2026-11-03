package dev.stsv.core.crop;

import java.util.UUID;

/** One planted crop. Mutable; changed on the main thread only. */
public final class CropState {

    public long id;
    public final String crop;
    public final String world;
    public final int x, y, z;
    /** the pot furniture holding it, or null (planted in the ground) */
    public final UUID pot;
    public final UUID planter;
    public long progress;        // watered, pest-free growth time (ms)
    public long waterUntil;      // a watering keeps it wet until then
    public long drySince;        // 0 = wet now
    public boolean neverDry = true;
    public String fertilizer;    // item id, or null
    public long pestSince;       // 0 = healthy
    public boolean withered;
    public long last;            // growth computed up to here
    public int rolledStage;      // highest stage already rolled for pests

    public CropState(long id, String crop, String world, int x, int y, int z, UUID pot, UUID planter, long now) {
        this.id = id;
        this.crop = crop;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.pot = pot;
        this.planter = planter;
        this.last = now;
    }

    public boolean pests() {
        return pestSince > 0;
    }
}
