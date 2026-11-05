package dev.stsv.rpg.player;

import dev.stsv.rpg.stat.StatBlock;

import java.util.UUID;

/** What the RPG keeps per online player. Main thread only. */
public final class RpgPlayer {

    public final UUID id;
    int level;
    long exp;
    double stamina;
    StatBlock stats = new StatBlock();
    boolean statsDirty = true;
    /** time (ms) the next basic attack is allowed */
    public long nextAttackAt;
    /** last time (ms) this player hit or was hit */
    public long lastCombatAt;

    RpgPlayer(UUID id, int level, long exp) {
        this.id = id;
        this.level = level;
        this.exp = exp;
    }

    public int level() {
        return level;
    }

    public long exp() {
        return exp;
    }

    public double stamina() {
        return stamina;
    }

    /** The player's total stats (base for the level + equipment). Do not modify. */
    public StatBlock stats() {
        return stats;
    }

    public void markStatsDirty() {
        statsDirty = true;
    }
}
