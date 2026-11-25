package dev.stsv.economy.stamp;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * contents/economy/stamps.yml: 도장 (README "도장"). A per-player count nobody can trade, earned by missions, 주민의 부탁, the
 * weekly community request, the fishing tournament and 행사; spent in the 도장 가게 on things money cannot buy and that earn
 * nothing (titles, a few limited cosmetics, mission reroll tickets). Every stamp earned also fills the next cell of this
 * month's 30-cell 도장판 (spending does not empty it); some cells give a small gift, the last one the month's title. Pure
 * (unit-tested).
 *
 * <pre>
 * earn:                       # sources counted here (missions.yml and requests.yml say their own)
 *   community: 3              # the weekly community request reached, each contributor paid
 *   tournament: 1             # a fishing tournament with at least one catch
 *   tournament-daily: 1       # at most this many tournament stamps a day
 *   event: 1                  # a 행사 (긴급 납품) delivered to
 *   event-daily: 1
 * shop:
 *   regular: { title: economy:stamp_regular, price: 20 }
 *   crown:   { cosmetic: stsv:crown, price: 600 }
 *   reroll:  { rerolls: 1, price: 3, icon: minecraft:paper, name: "미션 다시 뽑기권" }
 * board:
 *   cells: 30
 *   rewards: { 5: { items: ["minecraft:cake"] }, 10: { rerolls: 1 } }
 *   months: { 1: { title: economy:stamp_month_01 }, ... }
 * </pre>
 */
public record StampSpec(Map<String, Integer> earn, Map<String, ShopItem> shop, int cells, Map<Integer, Reward> cellRewards,
                        Map<Integer, Reward> months) {

    public static final String COMMUNITY = "community", TOURNAMENT = "tournament", EVENT = "event";

    /** What a cell, a month or a shop item gives: items ("id xN"), a title, a cosmetic (core id), mission reroll tickets. */
    public record Reward(List<String> items, String title, String cosmetic, int rerolls) {
        public static final Reward NONE = new Reward(List.of(), null, null, 0);

        public boolean isEmpty() {
            return items.isEmpty() && title == null && cosmetic == null && rerolls <= 0;
        }
    }

    /** One thing the 도장 가게 sells. A title or a cosmetic is sold once (refused if owned). */
    public record ShopItem(String id, String name, String icon, int price, Reward reward) {
        public boolean once() {
            return reward.title() != null || reward.cosmetic() != null;
        }
    }

    public int earn(String source) {
        return earn.getOrDefault(source, 0);
    }

    // ------------------------------------------------------------------------------------------ parse

    public static StampSpec parse(ConfigurationSection c, List<String> problems) {
        Map<String, Integer> earn = new LinkedHashMap<>();
        ConfigurationSection e = c.getConfigurationSection("earn");
        if (e != null) for (String k : e.getKeys(false)) {
            int v = e.getInt(k);
            if (v < 0) problems.add("earn." + k + ": 0 이상");
            earn.put(k, Math.max(0, v));
        }
        Map<String, ShopItem> shop = new LinkedHashMap<>();
        ConfigurationSection s = c.getConfigurationSection("shop");
        if (s != null) for (String id : s.getKeys(false)) {
            ConfigurationSection x = s.getConfigurationSection(id);
            if (x == null) continue;
            int price = x.getInt("price");
            if (price <= 0) problems.add("shop." + id + ": price 는 1 이상");
            Reward r = reward(x, "shop." + id, problems);
            if (r.isEmpty()) problems.add("shop." + id + ": 주는 것이 없음");
            if (r.title() != null && r.cosmetic() != null) problems.add("shop." + id + ": 칭호와 치장은 따로");
            shop.put(id, new ShopItem(id, x.getString("name"), x.getString("icon"), Math.max(1, price), r));
        }
        ConfigurationSection b = c.getConfigurationSection("board");
        int cells = b == null ? 30 : b.getInt("cells", 30);
        if (cells < 1) problems.add("board.cells: 1 이상");
        Map<Integer, Reward> cellRewards = new TreeMap<>();
        ConfigurationSection cr = b == null ? null : b.getConfigurationSection("rewards");
        if (cr != null) for (String k : cr.getKeys(false)) {
            int n = num(k);
            if (n < 1 || n > cells) {
                problems.add("board.rewards." + k + ": 1~" + cells + " 칸");
                continue;
            }
            ConfigurationSection x = cr.getConfigurationSection(k);
            if (x != null) cellRewards.put(n, reward(x, "board.rewards." + k, problems));
        }
        Map<Integer, Reward> months = new TreeMap<>();
        ConfigurationSection ms = b == null ? null : b.getConfigurationSection("months");
        if (ms != null) for (String k : ms.getKeys(false)) {
            int m = num(k);
            ConfigurationSection x = ms.getConfigurationSection(k);
            if (m < 1 || m > 12 || x == null) {
                problems.add("board.months." + k + ": 1~12월");
                continue;
            }
            months.put(m, reward(x, "board.months." + k, problems));
        }
        for (int m = 1; m <= 12; m++) if (!months.containsKey(m)) problems.add("board.months: " + m + "월이 없음");
        return new StampSpec(Map.copyOf(earn), shop, Math.max(1, cells), cellRewards, months);
    }

    static Reward reward(ConfigurationSection x, String at, List<String> problems) {
        List<String> items = new ArrayList<>();
        for (String i : x.getStringList("items")) {
            if (dev.stsv.economy.mission.MissionSpec.item(i) == null) problems.add(at + ": items '" + i + "'");
            else items.add(i.trim());
        }
        String title = blank(x.getString("title"));
        String cosmetic = blank(x.getString("cosmetic"));
        int rerolls = x.getInt("rerolls", 0);
        if (rerolls < 0) problems.add(at + ": rerolls 0 이상");
        return new Reward(List.copyOf(items), title, cosmetic == null ? null : cosmetic.toLowerCase(Locale.ROOT), Math.max(0, rerolls));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------ rules

    /** The board a moment falls in: "2026-11" (Korean calendar month in the server's zone). */
    public static String month(long millis, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(zone);
        return String.format(Locale.ROOT, "%04d-%02d", t.getYear(), t.getMonthValue());
    }

    /** The calendar month (1~12) of a board key. */
    public static int monthOf(String key) {
        return Integer.parseInt(key.substring(key.indexOf('-') + 1));
    }

    /** Cells filled after earning `add` more stamps on a board that had `filled` (never past the last cell). */
    public int fill(int filled, int add) {
        return Math.clamp((long) filled + Math.max(0, add), 0, cells);
    }

    /** The reward cells newly filled going from `before` to `after` cells, in order (the last cell is the month's reward). */
    public List<Integer> crossed(int before, int after) {
        List<Integer> out = new ArrayList<>();
        for (int n = before + 1; n <= Math.min(after, cells); n++) if (n == cells || cellRewards.containsKey(n)) out.add(n);
        return out;
    }

    /** What filling cell n gives in a month (1~12): the cell's gift, the month's reward on the last cell. */
    public Reward cellReward(int n, int month) {
        if (n == cells) return months.getOrDefault(month, Reward.NONE);
        return cellRewards.getOrDefault(n, Reward.NONE);
    }

    /** How many of `want` stamps a capped source may still give when `already` were given in the period (cap <= 0: no cap). */
    public static int capped(int want, int already, int cap) {
        if (cap <= 0) return Math.max(0, want);
        return Math.max(0, Math.min(want, cap - already));
    }

    /** Whether a balance buys an item, or the refusal key (owned: the title or cosmetic is already had). */
    public static String canBuy(ShopItem item, int balance, boolean owned) {
        if (item.once() && owned) return "stamp.shop-owned";
        if (balance < item.price()) return "stamp.shop-short";
        return null;
    }
}
