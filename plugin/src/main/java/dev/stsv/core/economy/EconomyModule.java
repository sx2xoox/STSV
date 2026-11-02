package dev.stsv.core.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Money for every content: balances, the ledger, cheques and the balance on the status line.
 *
 * Contents move money only through this API, always with a reason ("shop_sell", "land_tax", ...):
 * the reason is what the daily statistics and the admin history show. Every method here is for the
 * main thread. Kinds: FAUCET creates money, SINK destroys it, TRANSFER moves it between players.
 */
public final class EconomyModule implements StsvModule, Listener {

    private static final NamespacedKey CHEQUE = new NamespacedKey("stsv", "cheque");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private STSVPlugin plugin;
    private EconomyStore store;

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new EconomyStore(plugin.db());
        store.init();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) open(p.getUniqueId());
        plugin.hud().line("economy", 0, p -> plugin.getConfig().getBoolean("economy.hud")
                ? Lang.mm("economy.hud", "balance", format(balance(p.getUniqueId()))) : null);
        plugin.getLogger().info("경제: 계좌 " + store.balances().size() + "개");
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try {
                VaultBridge.register(plugin, this);
            } catch (LinkageError e) {
                plugin.getLogger().warning("Vault 연결 실패 (Vault 버전 확인): " + e);
            }
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try {
                VaultBridge.unregister(plugin);
            } catch (LinkageError ignored) {
                // Vault classes missing: nothing was registered
            }
        }
        if (plugin.hud() != null) plugin.hud().removeLine("economy");
    }

    public EconomyStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------------------------------

    public long balance(UUID player) {
        return store.balance(player);
    }

    public boolean has(UUID player, long amount) {
        return store.balance(player) >= amount;
    }

    public boolean hasAccount(UUID player) {
        return store.hasAccount(player);
    }

    /** Money from nowhere into a player (shop buying from them, rewards). */
    public boolean deposit(UUID player, long amount, String reason) {
        return move(null, player, amount, EconomyStore.Kind.FAUCET, reason, null);
    }

    /** Money from a player into nowhere (shop selling to them, taxes, fees). False if they cannot afford it. */
    public boolean withdraw(UUID player, long amount, String reason) {
        return move(player, null, amount, EconomyStore.Kind.SINK, reason, null);
    }

    /** Player to player. False if 'from' cannot afford it. */
    public boolean transfer(UUID from, UUID to, long amount, String reason) {
        return move(from, to, amount, EconomyStore.Kind.TRANSFER, reason, null);
    }

    /** The general form: from/to may be null (nowhere). */
    public boolean move(UUID from, UUID to, long amount, EconomyStore.Kind kind, String reason, String note) {
        long fromBefore = from == null ? 0 : store.balance(from), toBefore = to == null ? 0 : store.balance(to);
        if (!store.move(from, to, amount, kind, reason, note, System.currentTimeMillis())) return false;
        if (from != null) changed(from, fromBefore, kind, reason);
        if (to != null) changed(to, toBefore, kind, reason);
        return true;
    }

    /** Admin: exact balance. */
    public void set(UUID player, long value, String note) {
        long before = store.balance(player);
        store.set(player, value, note, System.currentTimeMillis());
        changed(player, before, EconomyStore.Kind.ADMIN, "admin_set");
    }

    private void changed(UUID player, long before, EconomyStore.Kind kind, String reason) {
        long after = store.balance(player);
        if (after == before) return;
        BalanceChangeEvent event = new BalanceChangeEvent(player, before, after, kind, reason);
        // other plugins (Vault) may move money from another thread; events belong on the main thread
        if (Bukkit.isPrimaryThread()) Bukkit.getPluginManager().callEvent(event);
        else Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
    }

    /** Transfer fee for an amount (at least 1 when the fee rate is above 0). */
    public long fee(long amount) {
        double rate = plugin.getConfig().getDouble("economy.transfer-fee");
        if (rate <= 0) return 0;
        return Math.max(1, (long) Math.ceil(amount * rate - 1e-9));   // 100 x 0.07 is 7.000000000000001 in floating point
    }

    /** "1,234 G" with the colours of messages.yml economy.amount. */
    public static String format(long amount) {
        return Lang.raw("economy.amount", "amount", String.format("%,d", amount));
    }

    // ------------------------------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------------------------------

    private void open(UUID player) {
        long start = plugin.getConfig().getLong("economy.start-balance");
        long before = store.balance(player);
        if (store.open(player, start, System.currentTimeMillis())) changed(player, before, EconomyStore.Kind.FAUCET, "start");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        open(e.getPlayer().getUniqueId());
    }

    /** Testing: wipes balances, ledger and cheques, then gives everyone online the start money again. */
    public CompletableFuture<Void> reset() {
        return store.reset().thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) open(p.getUniqueId());
        }));
    }

    // ------------------------------------------------------------------------------------------
    // Cheques
    // ------------------------------------------------------------------------------------------

    /** Takes the money and gives the cheque item. Empty if they cannot afford it. */
    public Optional<ItemStack> issueCheque(Player player, long amount) {
        long before = store.balance(player.getUniqueId());
        long now = System.currentTimeMillis();
        String id = store.issueCheque(player.getUniqueId(), amount, now);
        if (id == null) return Optional.empty();
        changed(player.getUniqueId(), before, EconomyStore.Kind.CHEQUE, "cheque_issue");
        ItemStack item = plugin.content().item("stsv:cheque").map(i -> i.create(1)).orElseGet(() -> new ItemStack(org.bukkit.Material.PAPER));
        ItemMeta meta = item.getItemMeta();
        Object[] args = {"amount", String.format("%,d G", amount), "issuer", player.getName(), "date", DATE.format(Instant.ofEpochMilli(now))};
        meta.itemName(Lang.item("economy.cheque-name", args));
        meta.lore(Lang.items("economy.cheque-lore", args));
        meta.getPersistentDataContainer().set(CHEQUE, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return Optional.of(item);
    }

    private static String chequeId(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(CHEQUE, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = chequeId(hand);
        if (id == null) return;
        e.setCancelled(true);
        // the item leaves the hand first: a second click while the database answers finds nothing
        ItemStack taken = hand.asOne();
        hand.subtract(1);
        UUID player = p.getUniqueId();
        store.claimCheque(id, player, System.currentTimeMillis()).whenComplete((amount, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.getLogger().warning("수표 입금 실패 (" + id + "): " + err.getMessage());
                giveBack(p, taken);
                if (p.isOnline()) p.sendMessage(Lang.msg("economy.cheque-error"));
                return;
            }
            if (amount < 0) {
                if (p.isOnline()) p.sendMessage(Lang.msg("economy.cheque-used"));
                plugin.getLogger().warning("사용된 수표를 다시 쓰려 함: " + p.getName() + " (" + id + ")");
                return;
            }
            move(null, player, amount, EconomyStore.Kind.CHEQUE, "cheque_cash", id);
            if (p.isOnline()) {
                HudModule.flash(p, Lang.mm("economy.cheque-cashed", "amount", format(amount)));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            }
        }));
    }

    private void giveBack(Player p, ItemStack item) {
        if (!p.isOnline()) {
            plugin.getLogger().warning("수표를 돌려주지 못함 (접속 종료): " + p.getName() + " " + chequeId(item));
            return;
        }
        for (ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** Is this the cheque custom item (for menus that must refuse it, e.g. a shop's sell slot)? */
    public boolean isCheque(ItemStack item) {
        return chequeId(item) != null;
    }

    public Map<UUID, Long> balances() {
        return store.balances();
    }
}
