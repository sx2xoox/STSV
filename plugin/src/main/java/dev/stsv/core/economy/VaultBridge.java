package dev.stsv.core.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.UUID;

/**
 * Lets plugins written for Vault (shops, auction houses, job plugins) use STSV money. Loaded only when
 * Vault is installed (this class must not be touched otherwise). Money is whole G: withdrawals round
 * up, deposits round down. Every movement is in the STSV ledger with the reason "vault".
 */
@SuppressWarnings("deprecation")   // the name-based methods are part of the Vault interface
public final class VaultBridge implements Economy {

    private final EconomyModule eco;

    private VaultBridge(EconomyModule eco) {
        this.eco = eco;
    }

    public static void register(Plugin plugin, EconomyModule eco) {
        Bukkit.getServicesManager().register(Economy.class, new VaultBridge(eco), plugin, ServicePriority.Highest);
        plugin.getLogger().info("Vault 연결: 다른 플러그인이 STSV 돈을 씁니다");
    }

    public static void unregister(Plugin plugin) {
        Bukkit.getServicesManager().unregisterAll(plugin);
    }

    private static UUID id(String name) {
        OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(name);
        return p == null ? null : p.getUniqueId();
    }

    private EconomyResponse fail(double amount, String why) {
        return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, why);
    }

    private EconomyResponse withdraw(UUID u, double amount) {
        if (u == null || !eco.hasAccount(u)) return fail(amount, "계좌 없음");
        if (amount < 0) return fail(amount, "음수 금액");
        long g = (long) Math.ceil(amount - 1e-9);
        if (g == 0) return new EconomyResponse(0, eco.balance(u), EconomyResponse.ResponseType.SUCCESS, null);
        if (!eco.withdraw(u, g, "vault")) return fail(amount, "잔액 부족");
        return new EconomyResponse(g, eco.balance(u), EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse deposit(UUID u, double amount) {
        if (u == null || !eco.hasAccount(u)) return fail(amount, "계좌 없음");
        if (amount < 0) return fail(amount, "음수 금액");
        long g = (long) Math.floor(amount + 1e-9);
        if (g > 0) eco.deposit(u, g, "vault");
        return new EconomyResponse(g, eco.balance(u), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "STSV"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 0; }
    @Override public String format(double amount) { return String.format("%,d G", (long) amount); }
    @Override public String currencyNamePlural() { return "G"; }
    @Override public String currencyNameSingular() { return "G"; }

    @Override public boolean hasAccount(String name) { UUID u = id(name); return u != null && eco.hasAccount(u); }
    @Override public boolean hasAccount(OfflinePlayer p) { return eco.hasAccount(p.getUniqueId()); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public double getBalance(String name) { UUID u = id(name); return u == null ? 0 : eco.balance(u); }
    @Override public double getBalance(OfflinePlayer p) { return eco.balance(p.getUniqueId()); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) >= amount; }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(id(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return withdraw(p.getUniqueId(), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(id(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return deposit(p.getUniqueId(), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "은행 없음");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    // accounts are opened by STSV on first join (with the start money)
    @Override public boolean createPlayerAccount(String name) { return hasAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return hasAccount(p); }
    @Override public boolean createPlayerAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return hasAccount(p); }
}
