package dev.stsv.economy;

import dev.stsv.core.data.Db;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 노점 were removed in economy 2.1. On the first start after the update, whatever is still in a rented
 * stall chest goes to its renter's mailbox, then the stall tables are dropped. Runs once.
 */
final class StallRemoval {

    private StallRemoval() {}

    static void run(EcoPlugin eco, Db db) {
        if (eco.kv().has("removed.stalls")) return;
        List<Object[]> rented;
        try {
            // a server that never ran a version with stalls has no stall table: look before reading
            rented = db.sync(c -> !hasTable(c, "eco_stall") ? List.<Object[]>of()
                    : Db.query(c, "SELECT number, world, x, y, z, owner FROM eco_stall WHERE owner IS NOT NULL",
                    rs -> new Object[]{rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getString(6)}));
        } catch (RuntimeException e) {
            eco.getLogger().warning("노점 표를 읽지 못했습니다: " + e.getMessage());
            rented = List.of();
        }
        int items = 0;
        for (Object[] r : rented) {
            World w = Bukkit.getWorld((String) r[1]);
            if (w == null) continue;
            int x = (Integer) r[2], y = (Integer) r[3], z = (Integer) r[4];
            w.getChunkAt(x >> 4, z >> 4);   // loads it
            if (!(w.getBlockAt(x, y, z).getState(false) instanceof Chest chest)) continue;
            UUID owner = UUID.fromString((String) r[5]);
            for (ItemStack it : chest.getBlockInventory().getContents()) {
                if (it == null || it.isEmpty()) continue;
                eco.mail().deliver(owner, it, "stall_release");
                items++;
            }
            chest.getBlockInventory().clear();
        }
        try {
            db.sync(c -> {
                Db.update(c, "DROP TABLE IF EXISTS eco_stall_price");
                Db.update(c, "DROP TABLE IF EXISTS eco_stall");
                return null;
            });
        } catch (RuntimeException e) {
            eco.getLogger().warning("노점 표를 지우지 못했습니다: " + e.getMessage());
        }
        eco.kv().set("removed.stalls", "1");
        if (!rented.isEmpty()) eco.getLogger().info("노점 삭제: 빌린 노점 " + rented.size() + "곳의 물건 " + items + "묶음을 우편함으로 보냈습니다.");
    }

    /** SQLite and MySQL both answer this through JDBC metadata (MySQL: only this server's database). */
    static boolean hasTable(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, table, null)) {
            while (rs.next()) if (rs.getString("TABLE_NAME").equalsIgnoreCase(table)) return true;
            return false;
        }
    }
}
