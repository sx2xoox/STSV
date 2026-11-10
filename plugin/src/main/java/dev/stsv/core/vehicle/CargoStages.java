package dev.stsv.core.vehicle;

/**
 * Which load model the truck's bed shows: by how many of its storage slots hold something. Pure (unit-tested).
 * Nothing stored shows nothing; otherwise the stages split the bed evenly: with four, up to a quarter = stage 1
 * (a couple of crates and a sack), up to half = 2, up to three quarters = 3, more = 4 (a full, tarped load).
 */
public final class CargoStages {

    private CargoStages() {}

    /** 0 = no load shown, 1..stages. */
    public static int stage(int used, int capacity, int stages) {
        if (used <= 0 || capacity <= 0 || stages <= 0) return 0;
        return Math.clamp((int) Math.ceil(used * (double) stages / capacity - 1e-9), 1, stages);
    }
}
