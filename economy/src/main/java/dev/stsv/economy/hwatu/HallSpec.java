package dev.stsv.economy.hwatu;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * hwatu.yml hall: the 도박장's tables as things in the world (README "도박장"). Pure (unit-tested).
 *
 * <pre>
 * hall:
 *   top: { round: 0.88, duel: 0.77, poker: 0.88, baccarat: 0.88 }   # table top height above the furniture's floor block (blocks)
 *   big-pot: 1000                      # a pot this big (chips) or more lights the hall up and goes on the whiteboard
 *   board-lines: 5                     # 오늘의 큰 판 lines on the whiteboard
 *   radius: 40                         # how far the hall's players hear and see a big win
 *   bots: true                         # 시험: bots sit as mannequins (false: a name tag over the seat)
 *   dealer-skin: "economy:entity/bus/dealer"   # 바카라's dealer (a mannequin)
 * </pre>
 */
public record HallSpec(double topRound, double topDuel, double topPoker, double topBaccarat, long bigPot, int boardLines, int radius,
                       boolean mannequins, String dealerSkin) {

    public static final String DEALER_SKIN = "economy:entity/bus/dealer";

    public double top(TableKind k) {
        return switch (k) {
            case DUEL -> topDuel;
            case POKER -> topPoker;
            case BACCARAT -> topBaccarat;
            default -> topRound;
        };
    }

    public static HallSpec parse(ConfigurationSection c, List<String> problems) {
        if (c == null) {
            problems.add("hall 이 없음 (기본값을 씀)");
            return new HallSpec(0.88, 0.77, 0.88, 0.88, 1000, 5, 40, true, DEALER_SKIN);
        }
        double round = c.getDouble("top.round", 0.88), duel = c.getDouble("top.duel", 0.77), poker = c.getDouble("top.poker", round),
                baccarat = c.getDouble("top.baccarat", round);
        for (double v : new double[]{round, duel, poker, baccarat}) if (v <= 0 || v > 2) problems.add("hall.top: 0 초과 2 이하 (블록)");
        long big = c.getLong("big-pot", 1000);
        if (big < 1) problems.add("hall.big-pot: 1 이상");
        int lines = c.getInt("board-lines", 5);
        if (lines < 1 || lines > 10) problems.add("hall.board-lines: 1~10");
        int radius = c.getInt("radius", 40);
        if (radius < 8 || radius > 128) problems.add("hall.radius: 8~128");
        return new HallSpec(Math.clamp(round, 0.1, 2), Math.clamp(duel, 0.1, 2), Math.clamp(poker, 0.1, 2), Math.clamp(baccarat, 0.1, 2),
                Math.max(1, big), Math.clamp(lines, 1, 10), Math.clamp(radius, 8, 128), c.getBoolean("bots", true),
                c.getString("dealer-skin", DEALER_SKIN));
    }
}
