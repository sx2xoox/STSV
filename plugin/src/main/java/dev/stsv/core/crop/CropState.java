package dev.stsv.core.crop;

import java.util.UUID;

/** One planted crop. Mutable; changed on the main thread only. */
public final class CropState {

    public final long id;
    public final String crop;
    public final String world;
    public final int x, y, z;
    public final UUID planter;
    public int age;

    public CropState(long id, String crop, String world, int x, int y, int z, UUID planter, int age) {
        this.id = id;
        this.crop = crop;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.planter = planter;
        this.age = age;
    }
}
