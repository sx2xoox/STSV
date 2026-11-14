package dev.stsv.core.donation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * What the donation shop sells (content/&lt;pack&gt;/donation_shop.yml). Pure: no server.
 *
 * <p>A product has a price in the donation currency and what buying it gives: an item (a custom item's id: a
 * vehicle item is bound to its buyer) and/or commands the console runs for the buyer ({player} is their name:
 * whatever an administrator could hand out with a command can be sold this way). Products stand in categories, in
 * the file's order.
 *
 * @param currency the currency's name ("별")
 * @param symbol   and its sign behind a number ("★")
 * @param notice   lines shown at the top of the shop (how to donate, that this is no official Minecraft product)
 */
public record DonationShop(String currency, String symbol, List<String> notice, Map<String, Category> categories, Map<String, Product> products) {

    public static final DonationShop EMPTY = new DonationShop("별", "★", List.of(), Map.of(), Map.of());

    /**
     * @param once     can be bought once only (something a player has or has not: a vehicle can be bought again)
     * @param item     a custom item's id ("stsv:hyper_car") given into the buyer's inventory, or null
     * @param commands console commands, {player} their name; may be empty when there is an item
     */
    public record Product(String id, String name, String description, long price, boolean once, String item, List<String> commands) {}

    public record Category(String id, String name, List<String> products) {}

    /** "300★" */
    public String amount(long n) {
        return String.format("%,d", n) + symbol;
    }

    /** Whether a balance buys a product. */
    public static boolean affords(long balance, Product p) {
        return balance >= p.price();
    }

    /**
     * Reads the file.
     *
     * @param itemExists is there a custom item of this id?
     * @param problems   one line for each thing left out
     */
    public static DonationShop parse(ConfigurationSection file, Predicate<String> itemExists, Consumer<String> problems) {
        String currency = file.getString("currency.name", "별").trim(), symbol = file.getString("currency.symbol", "★").trim();
        // the game's own currency must not be imitated (Minecraft Usage Guidelines)
        if (currency.isEmpty() || currency.toLowerCase(java.util.Locale.ROOT).contains("minecoin") || currency.contains("마인코인")) {
            problems.accept("currency.name: 비어 있거나 마인코인을 떠올리게 하는 이름은 쓸 수 없음 (\"별\" 로 둠)");
            currency = "별";
        }
        Map<String, Product> products = new LinkedHashMap<>();
        ConfigurationSection ps = file.getConfigurationSection("products");
        for (String id : ps == null ? List.<String>of() : ps.getKeys(false)) {
            ConfigurationSection p = ps.getConfigurationSection(id);
            if (p == null || !id.matches("[a-z0-9_]{1,48}")) {
                problems.accept("products." + id + ": id 는 영문 소문자·숫자·_ (48자까지), 값은 { name, price, ... }");
                continue;
            }
            long price = p.getLong("price", -1);
            String item = p.getString("item");
            List<String> commands = p.getStringList("commands").stream().map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> s.startsWith("/") ? s.substring(1) : s).toList();
            if (price < 1) {
                problems.accept("products." + id + ": price 는 1 이상");
                continue;
            }
            if (item == null && commands.isEmpty()) {
                problems.accept("products." + id + ": item 이나 commands 중 하나는 있어야 함 (사도 받는 것이 없음)");
                continue;
            }
            if (item != null && !itemExists.test(item)) {
                problems.accept("products." + id + ": 아이템 " + item + " 이 없음");
                continue;
            }
            products.put(id, new Product(id, p.getString("name", id), p.getString("description", ""), price, p.getBoolean("once"), item, commands));
        }
        Map<String, Category> categories = new LinkedHashMap<>();
        java.util.Set<String> placed = new java.util.HashSet<>();
        ConfigurationSection cs = file.getConfigurationSection("categories");
        for (String id : cs == null ? List.<String>of() : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null || !id.matches("[a-z0-9_]{1,32}")) {
                problems.accept("categories." + id + ": id 는 영문 소문자·숫자·_, 값은 { name, products: [...] }");
                continue;
            }
            List<String> in = new ArrayList<>();
            for (String product : c.getStringList("products")) {
                if (!products.containsKey(product)) problems.accept("categories." + id + ": 모르는 상품 " + product);
                else if (!placed.add(product)) problems.accept("categories." + id + ": " + product + " 는 이미 다른 분류에 있음");
                else in.add(product);
            }
            if (!in.isEmpty()) categories.put(id, new Category(id, c.getString("name", id), List.copyOf(in)));
        }
        for (String id : products.keySet()) if (!placed.contains(id)) problems.accept("products." + id + ": 어느 분류에도 없어 상점에 보이지 않음");
        return new DonationShop(currency, symbol, List.copyOf(file.getStringList("notice")), Collections.unmodifiableMap(categories),
                Collections.unmodifiableMap(products));
    }
}
