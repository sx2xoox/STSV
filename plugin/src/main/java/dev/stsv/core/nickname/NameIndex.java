package dev.stsv.core.nickname;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player's nickname and colour in memory (all of them, online or not: a few bytes each), with the lookups
 * commands need. No Bukkit types (unit-tested).
 *
 * <p>Finding a player from what someone typed ({@link #resolve}): an online player's real name first (real names
 * are the accounts, a nickname can never take one), then a nickname (anyone's, online or not), then a unique
 * start of an online player's real name or nickname.
 */
public final class NameIndex {

    /**
     * One player's entry.
     *
     * @param nickname   null: no nickname (the real name is shown), the colour may still be set
     * @param changedAt  when the player last changed their nickname themselves (cooldown), 0 never
     * @param selfSets   how many times they set a nickname themselves (the first is free)
     * @param colorSets  how many times they chose a colour themselves (the first single colour is free)
     */
    public record Entry(UUID player, String nickname, NickColor color, long changedAt, int selfSets, int colorSets) {

        public String key() {
            return nickname == null ? null : NickRules.key(nickname);
        }

        public Entry withNickname(String nick) {
            return new Entry(player, nick, color, changedAt, selfSets, colorSets);
        }

        public Entry withColor(NickColor c) {
            return new Entry(player, nickname, c, changedAt, selfSets, colorSets);
        }

        public boolean empty() {
            return nickname == null && color == null && changedAt == 0 && selfSets == 0 && colorSets == 0;
        }
    }

    private final Map<UUID, Entry> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, UUID> byKey = new ConcurrentHashMap<>();

    public void clear() {
        byPlayer.clear();
        byKey.clear();
    }

    /** Replaces the player's entry (a null nickname frees their old one). */
    public synchronized void put(Entry e) {
        Entry old = byPlayer.put(e.player(), e);
        if (old != null && old.key() != null) byKey.remove(old.key(), old.player());
        if (e.key() != null) {
            UUID before = byKey.put(e.key(), e.player());
            // one key, one player: whoever had it loses it (only an admin's forced set gets here)
            if (before != null && !before.equals(e.player())) {
                Entry theirs = byPlayer.get(before);
                if (theirs != null) byPlayer.put(before, theirs.withNickname(null));
            }
        }
    }

    public synchronized void remove(UUID player) {
        Entry old = byPlayer.remove(player);
        if (old != null && old.key() != null) byKey.remove(old.key(), player);
    }

    public Optional<Entry> get(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public String nickname(UUID player) {
        Entry e = byPlayer.get(player);
        return e == null ? null : e.nickname();
    }

    public NickColor color(UUID player) {
        Entry e = byPlayer.get(player);
        return e == null ? null : e.color();
    }

    /** Who has this nickname (by key). */
    public Optional<UUID> owner(String nickname) {
        return Optional.ofNullable(byKey.get(NickRules.key(nickname)));
    }

    /** Whether someone other than {@code self} has this nickname. */
    public boolean takenByOther(String nickname, UUID self) {
        UUID o = byKey.get(NickRules.key(nickname));
        return o != null && !o.equals(self);
    }

    public Collection<Entry> all() {
        return List.copyOf(byPlayer.values());
    }

    public int count() {
        return byKey.size();
    }

    /** What a player is shown as: the nickname, else the real name. */
    public String display(UUID player, String realName) {
        String n = nickname(player);
        return n != null ? n : realName;
    }

    /**
     * The player someone means.
     *
     * @param online the online players: uuid -> real name
     */
    public Optional<UUID> resolve(String typed, Map<UUID, String> online) {
        if (typed == null || typed.isBlank()) return Optional.empty();
        String t = typed.strip();
        for (Map.Entry<UUID, String> e : online.entrySet()) if (e.getValue().equalsIgnoreCase(t)) return Optional.of(e.getKey());
        UUID byNick = byKey.get(NickRules.key(t));
        if (byNick != null) return Optional.of(byNick);
        // a unique start of an online player's name or nickname
        String low = NickRules.key(t);
        UUID found = null;
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            String nick = nickname(e.getKey());
            boolean match = e.getValue().toLowerCase(Locale.ROOT).startsWith(low) || (nick != null && NickRules.key(nick).startsWith(low));
            if (!match) continue;
            if (found != null && !found.equals(e.getKey())) return Optional.empty();
            found = e.getKey();
        }
        return Optional.ofNullable(found);
    }

    /** Tab completion: the online players' nicknames and real names that start with what was typed (any case). */
    public List<String> complete(String typed, Map<UUID, String> online) {
        String low = typed == null ? "" : NickRules.key(typed);
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            String nick = nickname(e.getKey());
            if (nick != null && NickRules.key(nick).startsWith(low)) out.add(nick);
            if (e.getValue().toLowerCase(Locale.ROOT).startsWith(low)) out.add(e.getValue());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
