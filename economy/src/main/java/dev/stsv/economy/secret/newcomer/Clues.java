package dev.stsv.economy.secret.newcomer;

import java.util.Locale;

/**
 * When the broken traces (and the shards they leave) show themselves, and to whom. A trace and its shard are for a player
 * who has whispered to him at his stone themselves ({@code clues.gate: player}; with {@code server}: for everyone once
 * anyone has). The things everyone sees — his wrong-keyboard slip in public chat and the broken writing on the stone —
 * begin when the first player on the server has whispered there. Shards are few: one a day for a player, a handful a day
 * for the server. Pure.
 */
final class Clues {

    private Clues() {}

    enum Gate {
        PLAYER, SERVER;

        static Gate of(String s) {
            return s != null && s.strip().toLowerCase(Locale.ROOT).equals("server") ? SERVER : PLAYER;
        }
    }

    /** Whether traces and shards are there for a player. */
    static boolean open(Gate gate, boolean playerWhisperedAtStone, boolean serverFound) {
        return gate == Gate.SERVER ? serverFound : playerWhisperedAtStone && serverFound;
    }

    /** His wrong-keyboard slip, and the broken writing on the stone: for everyone, once anyone has found the stone. */
    static boolean forAll(boolean serverFound) {
        return serverFound;
    }

    /** Whether one more shard may be found: under the player's count for the day and the server's. */
    static boolean room(String day, String playerDay, long playerCount, int perPlayer, String serverDay, long serverCount, int perServer) {
        return Awakening.under(day, playerDay, playerCount, perPlayer) && Awakening.under(day, serverDay, serverCount, perServer);
    }
}
