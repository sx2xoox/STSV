package dev.stsv.economy.secret.usher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 「윤해담의 부탁」 ({@link UsherSequel}): which of the thirteen pictures is wrong this week, and which memories were heard.
 * Pure (unit-tested). A week's picture is fixed by the week and the player, so a restart asks for the same one; a picture
 * whose memory was heard is not asked for again until all were heard, then they go round again.
 */
final class UsherAsk {

    private UsherAsk() {}

    /** The same number for a week and a player, every time. */
    static int hash(String week, UUID u) {
        return (week + "/" + u).hashCode();
    }

    /** The stations heard so far ("a,b,c"), in the order they were heard. */
    static List<String> heard(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String w : raw.split(",")) if (!w.isBlank() && !out.contains(w.trim())) out.add(w.trim());
        return out;
    }

    /**
     * This week's station: one of those not heard yet, by the week and the player; when all were heard, any but the one
     * heard last. Null when there are no stations.
     */
    static String pick(String week, UUID u, List<String> stations, Collection<String> heard) {
        if (stations.isEmpty()) return null;
        List<String> left = new ArrayList<>();
        for (String s : stations) if (!heard.contains(s)) left.add(s);
        if (left.isEmpty()) {
            left.addAll(stations);
            String last = null;
            for (String h : heard) if (stations.contains(h)) last = h;
            if (left.size() > 1 && last != null) left.remove(last);
        }
        return left.get(Math.floorMod(hash(week, u), left.size()));
    }

    /**
     * The heard list after this station's memory was told. Once every station is on it the round is over: only the last
     * one stays (so the next round does not begin with it).
     */
    static String addHeard(String raw, String station, List<String> stations) {
        List<String> h = heard(raw);
        h.remove(station);
        h.add(station);
        if (h.containsAll(stations)) return station;
        return String.join(",", h);
    }

    /** How many of the stations were heard in this round. */
    static int count(String raw, List<String> stations) {
        int n = 0;
        for (String h : heard(raw)) if (stations.contains(h)) n++;
        return n;
    }
}
