package dev.stsv.economy.market;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The price picker shared by 판매 등록 (economy:market_sell), 경매 등록 (economy:auction_sell) and 입찰 (economy:auction_bid):
 * step buttons (left click adds, right click takes away, shift = ten steps), a typed amount (chat), the shop's price, the least
 * bid and quick bids, then 확인. The price never goes below {@link #min}. The handler keeps its state, so the menu can close for
 * a typed amount and open again with the same choice.
 */
public abstract class PriceMenu implements MenuHandler {

    /** Every action the price menus' buttons may use (besides close): see menus/market.yml. */
    public static final Set<String> ACTIONS = Set.of("price:step:1", "price:step:10", "price:step:100", "price:step:1000", "price:step:10000",
            "price:type", "price:shop", "price:min", "price:raise:5", "price:raise:10", "price:confirm", "price:back");

    protected final org.bukkit.plugin.Plugin owner;
    protected long price;
    /** The player chose a price (steps, typed); until then the price follows the suggestion. */
    protected boolean edited;

    protected PriceMenu(org.bukkit.plugin.Plugin owner, long price) {
        this.owner = owner;
        this.price = price;
    }

    public long price() {
        return price;
    }

    /** The least price allowed now (1, the least start price, the least next bid). */
    protected abstract long min(MenuView v);

    /** The menu id this handler opens (to come back after a typed amount). */
    protected abstract String menuId();

    /** The values of this menu's {placeholders} besides {price}. */
    protected abstract Map<String, String> values(MenuView v);

    /** 확인 was clicked. */
    protected abstract void confirm(MenuView v);

    /** 돌아가기 was clicked. */
    protected abstract void back(Player p);

    /** The shop's value of what is being sold (0 = none): the "시세로 맞추기" button. */
    protected long shopPrice(MenuView v) {
        return 0;
    }

    /** The 확인 button while it cannot go ahead: greyed, named after the reason (a message key). */
    public static org.bukkit.inventory.ItemStack blocked(org.bukkit.inventory.ItemStack base, String reason, Object... pairs) {
        base.editMeta(meta -> {
            meta.setItemModel(new org.bukkit.NamespacedKey("economy", "gui/confirm_off"));
            meta.itemName(Lang.item(reason, pairs));
            meta.lore(Lang.items("price.blocked-lore"));
        });
        return base;
    }

    public void open(Player p) {
        STSVPlugin.get().menus().open(p, menuId(), this);
    }

    @Override
    public Map<String, String> placeholders(MenuView v) {
        price = PriceInput.clamp(price, min(v));
        Map<String, String> m = new HashMap<>(values(v));
        m.put("price", EconomyModule.format(price));
        return m;
    }

    @Override
    public void onAction(MenuView v, String action, ClickType click) {
        Player p = v.player();
        long min = min(v);
        if (action.startsWith("price:step:")) {
            long step = Long.parseLong(action.substring(11));
            boolean up = !click.isRightClick();
            price = PriceInput.step(price, step, up, click.isShiftClick(), min);
            edited = true;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, up ? 1.5f : 1.1f);
            v.refresh();
            return;
        }
        switch (action) {
            case "price:type" -> Bukkit.getScheduler().runTask(owner, () -> askTyped(p));
            case "price:shop" -> {
                long shop = shopPrice(v);
                if (shop <= 0) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                    return;
                }
                price = PriceInput.clamp(shop, min);
                edited = true;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
                v.refresh();
            }
            case "price:min" -> set(v, min);
            case "price:raise:5" -> set(v, PriceInput.raise(min, 5));
            case "price:raise:10" -> set(v, PriceInput.raise(min, 10));
            case "price:confirm" -> confirm(v);
            case "price:back" -> Bukkit.getScheduler().runTask(owner, () -> back(p));
            default -> { }
        }
    }

    private void set(MenuView v, long to) {
        price = PriceInput.clamp(to, min(v));
        edited = true;
        v.player().playSound(v.player().getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
        v.refresh();
    }

    /** Closes the menu and takes the next chat line as the price; the menu opens again either way. */
    private void askTyped(Player p) {
        STSVPlugin.get().menus().prompt(p, Lang.msg("price.ask"), text -> {
            if (text != null) {
                long typed = PriceInput.parse(text);
                if (typed < 0) {
                    p.sendMessage(Lang.msg("price.bad", "text", text));
                } else {
                    price = typed;
                    edited = true;
                }
            }
            if (p.isOnline()) open(p);
        });
    }
}
