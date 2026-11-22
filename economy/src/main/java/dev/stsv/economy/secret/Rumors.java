package dev.stsv.economy.secret;

import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 소문: now and then one online player overhears a single cryptic line about a secret nobody has solved yet (common.yml
 * rumors.*), so the server feels that something is out there without anyone being told what. Never about a solved or
 * switched-off secret; never twice in a row about the same one.
 */
final class Rumors {

    private final SecretModule m;
    private long next;
    private String last = "";

    Rumors(SecretModule m) {
        this.m = m;
        schedule();
    }

    private ConfigurationSection cfg() {
        return m.eco().data("secrets/common.yml").getConfigurationSection("rumors");
    }

    private void schedule() {
        ConfigurationSection c = cfg();
        List<Integer> every = c == null ? List.of(40, 90) : c.getIntegerList("every-minutes");
        int lo = every.isEmpty() ? 40 : every.getFirst(), hi = every.size() > 1 ? every.get(1) : lo;
        next = System.currentTimeMillis() + (lo + ThreadLocalRandom.current().nextInt(Math.max(1, hi - lo + 1))) * 60_000L;
    }

    void tick() {
        if (System.currentTimeMillis() < next) return;
        schedule();
        ConfigurationSection c = cfg();
        if (c == null || !c.getBoolean("enabled", true)) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return;
        List<String> open = new ArrayList<>();
        ConfigurationSection lines = c.getConfigurationSection("lines");
        if (lines == null) return;
        for (String id : lines.getKeys(false)) {
            if (id.equals("bus")) {
                var bus = m.eco().bus() == null ? null : m.eco().bus().secretAgent();
                if (bus == null || !bus.on() || m.eco().kv().has("bus.secret.first")) continue;
            } else {
                Secret s = m.find(id).orElse(null);
                if (s == null || !m.enabled(s) || m.closed(s)) continue;
            }
            if (!lines.getStringList(id).isEmpty()) open.add(id);
        }
        String id = pick(open, last, ThreadLocalRandom.current());
        if (id == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> say = lines.getStringList(id);
        Player p = online.get(r.nextInt(online.size()));
        String fmt = c.getString("format", "<dark_gray><i>…{line}");
        p.sendMessage(Text.mm(fmt.replace("{line}", say.get(r.nextInt(say.size())))));
        last = id;
    }

    /**
     * Which secret this time: any open one but the last; when the last is the only one still open it may come again (else
     * the final unsolved secret would never be rumored about). Null when none is open.
     */
    static String pick(List<String> open, String last, java.util.random.RandomGenerator r) {
        if (open.isEmpty()) return null;
        List<String> others = open.stream().filter(id -> !id.equals(last)).toList();
        List<String> from = others.isEmpty() ? open : others;
        return from.get(r.nextInt(from.size()));
    }

    /** An admin's look: one rumor about this id to that player now. */
    boolean tell(Player p, String id) {
        ConfigurationSection c = cfg();
        List<String> say = c == null ? List.of() : c.getStringList("lines." + id);
        if (say.isEmpty()) return false;
        String fmt = c.getString("format", "<dark_gray><i>…{line}");
        p.sendMessage(Text.mm(fmt.replace("{line}", say.get(ThreadLocalRandom.current().nextInt(say.size())))));
        return true;
    }
}
