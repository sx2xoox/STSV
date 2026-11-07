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
    /** Ages grown by itself (random ticks, a farmer's aura) and bone meals used, over the plant's whole life. */
    public int natural, meals;
    /** A mark (namespaced PDC key) of the seed it was planted from; a young plant broken out gives a seed with it. */
    public String carry;

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
