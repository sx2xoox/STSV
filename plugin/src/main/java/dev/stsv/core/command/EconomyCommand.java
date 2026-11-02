package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** /돈 [플레이어|순위], /송금 <플레이어> <금액>, /수표 <금액>. */
public final class EconomyCommand implements TabExecutor {

    private static final long CONFIRM_MILLIS = 15_000;

    private final STSVPlugin plugin;
    /** player -> (the command line waiting for its second entry, until when) */
    private final Map<UUID, Map.Entry<String, Long>> pending = new ConcurrentHashMap<>();

    public EconomyCommand(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    private EconomyModule eco() {
        return plugin.economy();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        switch (cmd.getName()) {
            case "돈" -> money(sender, args);
            case "송금" -> pay(sender, args);
            case "수표" -> cheque(sender, args);
            default -> { }
        }
        return true;
    }

    private void money(CommandSender sender, String[] args) {
        if (args.length > 0 && (args[0].equals("순위") || args[0].equalsIgnoreCase("top"))) {
            sender.sendMessage(Lang.msg("economy.top-header"));
            int rank = 1;
            for (Map.Entry<UUID, Long> e : eco().store().top(10)) {
                String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                sender.sendMessage(Lang.mm("economy.top-line", "rank", rank++, "player", name == null ? "?" : name,
                        "balance", EconomyModule.format(e.getValue())));
            }
            return;
        }
        if (args.length > 0) {
            find(args[0], found -> {
                if (found.isEmpty() || !eco().hasAccount(found.get())) sender.sendMessage(Lang.msg("economy.not-found"));
                else sender.sendMessage(Lang.msg("economy.balance-other", "player", args[0], "balance", EconomyModule.format(eco().balance(found.get()))));
            });
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        p.sendMessage(Lang.msg("economy.balance", "balance", EconomyModule.format(eco().balance(p.getUniqueId()))));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Lang.msg("economy.usage-pay", "fee", trim(plugin.getConfig().getDouble("economy.transfer-fee") * 100)));
            return;
        }
        long amount = amount(p, args[1]);
        if (amount <= 0) return;
        find(args[0], found -> {
            if (found.isEmpty() || !eco().hasAccount(found.get())) {
                p.sendMessage(Lang.msg("economy.not-found"));
                return;
            }
            UUID to = found.get();
            if (to.equals(p.getUniqueId())) {
                p.sendMessage(Lang.msg("economy.self"));
                return;
            }
            long fee = eco().fee(amount);
            if (!eco().has(p.getUniqueId(), amount + fee)) {
                p.sendMessage(Lang.msg("economy.not-enough", "need", EconomyModule.format(amount + fee), "balance", EconomyModule.format(eco().balance(p.getUniqueId()))));
                return;
            }
            if (!confirmed(p, "pay " + to + " " + amount, amount, args[0] + " " + EconomyModule.format(amount))) return;
            // checked above for both: neither can fail now (same tick)
            eco().transfer(p.getUniqueId(), to, amount, "pay");
            if (fee > 0) eco().withdraw(p.getUniqueId(), fee, "transfer_fee");
            p.sendMessage(Lang.msg("economy.paid", "player", args[0], "amount", EconomyModule.format(amount), "fee", EconomyModule.format(fee)));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
            Player target = Bukkit.getPlayer(to);
            if (target != null) {
                target.sendMessage(Lang.msg("economy.received", "player", p.getName(), "amount", EconomyModule.format(amount)));
                target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            }
        });
    }

    private void cheque(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        long min = plugin.getConfig().getLong("economy.cheque-min");
        if (args.length < 1) {
            p.sendMessage(Lang.msg("economy.usage-cheque", "min", EconomyModule.format(min)));
            return;
        }
        long amount = amount(p, args[0]);
        if (amount <= 0) return;
        if (amount < min) {
            p.sendMessage(Lang.msg("economy.cheque-min", "min", EconomyModule.format(min)));
            return;
        }
        if (p.getInventory().firstEmpty() < 0) {
            p.sendMessage(Lang.msg("economy.inventory-full"));
            return;
        }
        if (!eco().has(p.getUniqueId(), amount)) {
            p.sendMessage(Lang.msg("economy.not-enough", "need", EconomyModule.format(amount), "balance", EconomyModule.format(eco().balance(p.getUniqueId()))));
            return;
        }
        if (!confirmed(p, "cheque " + amount, amount, "수표 " + EconomyModule.format(amount))) return;
        Optional<ItemStack> item = eco().issueCheque(p, amount);
        if (item.isEmpty()) return;
        p.getInventory().addItem(item.get());
        p.sendMessage(Lang.msg("economy.cheque-issued", "amount", EconomyModule.format(amount)));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------------------------------

    /** Big amounts run only when the same command comes again within 15 seconds. */
    private boolean confirmed(Player p, String key, long amount, String what) {
        if (amount < plugin.getConfig().getLong("economy.confirm-over")) return true;
        long now = System.currentTimeMillis();
        Map.Entry<String, Long> last = pending.get(p.getUniqueId());
        if (last != null && last.getKey().equals(key) && last.getValue() > now) {
            pending.remove(p.getUniqueId());
            return true;
        }
        pending.put(p.getUniqueId(), Map.entry(key, now + CONFIRM_MILLIS));
        p.sendMessage(Lang.msg("economy.confirm", "what", what));
        return false;
    }

    /** Positive whole number ("1,000" and "1000" both work), or -1 with a message. */
    static long parseAmount(String s) {
        try {
            long v = Long.parseLong(s.replace(",", "").replace("_", ""));
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long amount(Player p, String s) {
        long v = parseAmount(s);
        if (v <= 0) p.sendMessage(Lang.msg("economy.not-number"));
        return v;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Online player by exact name, otherwise anyone who ever joined (callback on the main thread). */
    private void find(String name, Consumer<Optional<UUID>> then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(Optional.of(online.getUniqueId()));
            return;
        }
        CompletableFuture<Optional<UUID>> f = plugin.profiles().store().findByName(name);
        f.thenAccept(found -> Bukkit.getScheduler().runTask(plugin, () -> then.accept(found)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length != 1 || cmd.getName().equals("수표")) return List.of();
        List<String> out = new ArrayList<>();
        if (cmd.getName().equals("돈")) out.add("순위");
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out.stream().filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).toList();
    }
}
