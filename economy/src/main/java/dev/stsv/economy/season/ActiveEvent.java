package dev.stsv.economy.season;

import java.util.Map;

/** A running event (품귀, 무법 지대, ...): what, since when, until when, with which details. */
public record ActiveEvent(long id, String type, long started, long ends, Map<String, String> params) {

    public String param(String k) {
        return params.get(k);
    }

    public double number(String k, double def) {
        try {
            return params.containsKey(k) ? Double.parseDouble(params.get(k)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean runningAt(long t) {
        return t >= started && t < ends;
    }

    static String encode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue().replace("\n", " "));
        }
        return sb.toString();
    }

    static Map<String, String> decode(String s) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }
}
