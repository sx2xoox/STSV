package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /ㅆ (쓰레기통): an empty chest that is the player's own for as long as it is open. What is in it when it closes is
 * gone, except what must never be lost that way: a vehicle or 지게 item (bound to its owner and kept in the vehicle
 * store) and a box or bundle holding one go back to the player (dropped at their feet if their inventory is full).
 * Nothing is kept anywhere: a bin that was closed cannot be opened again.
 */
public final class TrashCan implements CommandExecutor, Listener {

    /** Slots of the bin (a double chest). */
    public static final int SIZE = 54;

    private final STSVPlugin plugin;

    public TrashCan(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Bin implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player p) open(p);
        else sender.sendMessage(Lang.msg("common.players-only"));
        return true;
    }

    public void open(Player p) {
        Bin bin = new Bin();
        bin.inventory = Bukkit.createInventory(bin, SIZE, Lang.mm("trash.title"));
        p.openInventory(bin.inventory);
    }

    /** What may not be thrown away: it goes back to the player. */
    private boolean kept(ItemStack item) {
        return plugin.vehicles() != null && plugin.vehicles().noTrade(item);
    }

    /**
     * Sorts a bin's contents: {@code back} gets what is kept, and the number of items thrown away is returned.
     *
     * @param kept what may not be thrown away
     */
    public static int sort(ItemStack[] contents, java.util.function.Predicate<ItemStack> kept, List<ItemStack> back) {
        int thrown = 0;
        for (ItemStack item : contents) {
            if (item == null || item.isEmpty()) continue;
            if (kept.test(item)) back.add(item);
            else thrown += item.getAmount();
        }
        return thrown;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Bin) || !(e.getPlayer() instanceof Player p)) return;
        List<ItemStack> back = new ArrayList<>();
        int thrown = sort(e.getInventory().getContents(), this::kept, back);
        e.getInventory().clear();
        for (ItemStack left : p.getInventory().addItem(back.toArray(ItemStack[]::new)).values()) {
            p.getWorld().dropItem(p.getLocation(), left);
        }
        if (!back.isEmpty()) p.sendMessage(Lang.msg("trash.kept"));
        if (thrown > 0) {
            p.sendMessage(Lang.msg("trash.thrown", "count", thrown));
            p.playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.4f, 1.4f);
        }
    }
}
