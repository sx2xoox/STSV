package dev.stsv.economy.secret.letter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The pure parts of the letters' riddles: which way and how far a place lies from the world spawn ("북동쪽", "약 400걸음"),
 * a Korean date and time a letter can say ("2026년 3월 4일 밤 11시 12분"), the initial sounds of a name ("ㄹㅂ ㅇㅅ"), and
 * the silence's escalation (how often and by whom their name is called, as the five minutes run out). No Bukkit here.
 */
final class Riddle {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String[] DIRS = {"동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽", "북쪽", "북동쪽"};
    private static final char[] CHO = {'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'};

    private Riddle() {}

    /**
     * The compass way from (0, 0) to (dx, dz) in Minecraft terms: +x is east, +z is south. Eight ways; null when there is
     * no way to speak of (the same spot).
     */
    static String direction(double dx, double dz) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return null;
        double deg = Math.toDegrees(Math.atan2(dz, dx));   // 0 = east, 90 = south
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8);
        return DIRS[i];
    }

    /** A distance as a letter says it: "약 400걸음", to the nearest 50 (the nearest 10 under 100). */
    static String steps(double dist) {
        long d = Math.round(dist);
        long r = d < 100 ? Math.max(10, Math.round(d / 10.0) * 10) : Math.round(d / 50.0) * 50;
        return "약 " + r + "걸음";
    }

    /** "2026년 3월 4일" in Korea, from ms. */
    static String date(long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(KST);
        return t.getYear() + "년 " + t.getMonthValue() + "월 " + t.getDayOfMonth() + "일";
    }

    /** "2026년 3월 4일 밤 11시 12분" in Korea, from ms: the part of the day the way people say it. */
    static String dateTime(long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(KST);
        int h = t.getHour();
        String part = h < 5 ? "새벽" : h < 12 ? "아침" : h < 18 ? "오후" : "밤";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return date(ms) + " " + part + " " + h12 + "시 " + t.getMinute() + "분";
    }

    /** The initial sounds of a Korean name ("루비 원석" → "ㄹㅂ ㅇㅅ"); other letters stay as they are. */
    static String initials(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c >= 0xAC00 && c <= 0xD7A3) b.append(CHO[(c - 0xAC00) / (21 * 28)]);
            else b.append(c);
        }
        return b.toString().trim();
    }

    /**
     * Who calls their name, by how far the silence has gone (0..1): 0 = anyone (players they barely know), 1 = the people
     * they are closest to, 2 = themselves — their own name typing to them.
     */
    static int callTier(double progress) {
        return progress < 0.4 ? 0 : progress < 0.75 ? 1 : 2;
    }

    /** The wait to the next call (ms) as the silence goes on: from the start range down to the end range. */
    static long callGap(double progress, long startMs, long endMs) {
        double f = Math.max(0, Math.min(1, progress));
        return Math.max(1000, Math.round(startMs + (endMs - startMs) * Math.pow(f, 0.8)));
    }

    /** One pace factor per letter (n = 1..5) from a list; the last one repeats, nothing given means 1. */
    static double pace(java.util.List<Double> paces, int n) {
        if (paces == null || paces.isEmpty()) return 1.0;
        double v = paces.get(Math.max(0, Math.min(paces.size() - 1, n - 1)));
        return v <= 0 ? 1.0 : v;
    }
}
