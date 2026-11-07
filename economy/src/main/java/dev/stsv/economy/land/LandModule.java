package dev.stsv.economy.land;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 땅, counted in chunks (16x16, full height). Everyone who lives on a land brings 2 free chunks; more
 * are bought, each dearer than the last. A land has one owner and any number of members who live
 * there together: all of them may build and use everything, and they share a 금고. The daily property
 * tax (05:00) is paid from the 금고 first, then from the owner's wallet; three days unpaid and the
 * newest chunks are released until the tax can be paid. The server's land protects the town.
 */
public final class LandModule extends EcoModule implements DailyListener {

    private record Invite(long land, UUID by, long expires) {}

    private LandStore store;
    private LandRules rules;
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> where = new ConcurrentHashMap<>();
    private final Map<UUID, Long> warned = new ConcurrentHashMap<>();

    public LandModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-land";
    }

    @Override
    protected void start() {
        store = new LandStore(core.db());
        store.init(now());
        rules = LandRules.parse(eco.data("land.yml"));
        eco.calendar().onDaily(this);
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        rules = LandRules.parse(eco.data("land.yml"));
    }

    public LandStore store() {
        return store;
    }

    public LandRules rules() {
        return rules;
    }

    public Land at(Location l) {
        return l.getWorld() == null ? null : store.index().at(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    private static ChunkPos chunk(Location l) {
        return ChunkPos.ofBlock(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    /** May this player change things here? */
    public boolean allowed(Player p, Location l) {
        Land c = at(l);
        return c == null || c.allows(p.getUniqueId()) || p.hasPermission("stsv.economy.land.bypass");
    }

    public int free(Land l) {
        return rules.free(store.index().residents(l));
    }

    public long tax(Land l) {
        return l.server() ? 0 : rules.tax(l.size(), free(l), l.members.size(), taxMultiplier());
    }

    private String ownerName(Land l) {
        return l == null || l.server() ? Lang.raw("land.server") : Players.name(l.owner);
    }

    /** The land a command is about: the one you stand in if you live there, else where you live. */
    private Land mine(Player p) {
        Land here = at(p.getLocation());
        if (here != null && !here.server() && here.allows(p.getUniqueId())) return here;
        return store.index().home(p.getUniqueId());
    }

    private void tell(Land l, Component msg) {
        for (UUID u : l.people()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(msg);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Chunks
    // ------------------------------------------------------------------------------------------

    /** Claims the chunk you stand in: free while you have free chunks, else for a price (twice: 확인). */
    public void buy(Player p, boolean confirmed) {
        UUID u = p.getUniqueId();
        ChunkPos c = chunk(p.getLocation());
        Land taken = store.index().at(c);
        if (taken != null) {
            p.sendMessage(Lang.msg(taken.isOwner(u) ? "land.already-mine" : "land.taken", "owner", ownerName(taken)));
            return;
        }
        Land land = store.index().ownedBy(u);
        int free = land != null ? free(land) : store.index().memberOf(u) == null ? rules.free(1) : 0;
        int size = land == null ? 0 : land.size();
        long price = rules.price(size, free);
        if (price > 0 && !confirmed) {
            p.sendMessage(Lang.msg("land.buy-confirm", "x", c.x(), "z", c.z(), "price", EconomyModule.format(price),
                    "tax", EconomyModule.format(rules.tax(size + 1, free, land == null ? 0 : land.members.size(), taxMultiplier()))));
            return;
        }
        if (price > 0 && !core.economy().withdraw(u, price, "land_buy")) {
            p.sendMessage(Lang.msg("land.not-enough", "price", EconomyModule.format(price)));
            return;
        }
        boolean first = land == null;
        if (first) land = store.create(u, now());
        store.claim(land, c, now());
        if (price > 0) store.addSpent(u, price);
        p.sendMessage(Lang.msg(price > 0 ? "land.bought" : "land.claimed-free", "x", c.x(), "z", c.z(), "price", EconomyModule.format(price),
                "size", land.size(), "free", free(land), "next", EconomyModule.format(rules.price(land.size(), free(land)))));
        if (first) p.sendMessage(Lang.msg("land.first"));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1f, 1f);
        outline(p, List.of(c), true);
    }

    /** Gives up the chunk you stand in (no refund). The last chunk ends the land. */
    public void abandon(Player p) {
        Land l = at(p.getLocation());
        if (l == null || !l.isOwner(p.getUniqueId())) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        ChunkPos c = chunk(p.getLocation());
        store.release(l, c);
        p.sendMessage(Lang.msg("land.abandoned", "x", c.x(), "z", c.z(), "size", l.size()));
        if (l.size() == 0) disband(l, "land.disbanded-empty");
    }

    /** /땅 해체 확인: the whole land goes; the 금고 goes back to the owner. */
    public void disband(Player p, boolean confirmed) {
        Land l = store.index().ownedBy(p.getUniqueId());
        if (l == null) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        if (!confirmed) {
            p.sendMessage(Lang.msg("land.disband-confirm", "size", l.size(), "vault", EconomyModule.format(l.vault), "members", l.members.size()));
            return;
        }
        disband(l, "land.disbanded");
    }

    private void disband(Land l, String key) {
        if (l.vault > 0 && l.owner != null) {
            core.economy().deposit(l.owner, l.vault, "land_vault_refund");
        }
        tell(l, Lang.msg(key, "vault", EconomyModule.format(l.vault)));
        store.delete(l);
    }

    // ------------------------------------------------------------------------------------------
    // Living together
    // ------------------------------------------------------------------------------------------

    public void invite(Player p, String name) {
        Land l = store.index().ownedBy(p.getUniqueId());
        if (l == null) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        Players.find(name, found -> {
            if (found.isEmpty()) {
                p.sendMessage(Lang.msg("land.no-player"));
                return;
            }
            UUID t = found.get();
            if (l.allows(t)) {
                p.sendMessage(Lang.msg("land.already-member", "player", name));
                return;
            }
            if (store.index().memberOf(t) != null) {
                p.sendMessage(Lang.msg("land.member-elsewhere", "player", name));
                return;
            }
            invites.put(t, new Invite(l.id, p.getUniqueId(), now() + rules.inviteMinutes() * 60_000L));
            p.sendMessage(Lang.msg("land.invited", "player", name, "minutes", rules.inviteMinutes()));
            Player tp = Bukkit.getPlayer(t);
            if (tp != null) {
                tp.sendMessage(Lang.msg("land.invite", "owner", p.getName(), "size", l.size(), "members", l.members.size(), "minutes", rules.inviteMinutes()));
                if (store.index().ownedBy(t) != null) tp.sendMessage(Lang.msg("land.invite-own-land", "free", rules.freePerPerson()));
                tp.playSound(tp.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.4f);
            }
        });
    }

    public void accept(Player p) {
        UUID u = p.getUniqueId();
        Invite inv = invites.remove(u);
        Land l = inv == null || now() > inv.expires() ? null : store.index().get(inv.land());
        if (l == null) {
            p.sendMessage(Lang.msg("land.no-invite"));
            return;
        }
        if (store.index().memberOf(u) != null) {
            p.sendMessage(Lang.msg("land.member-elsewhere", "player", p.getName()));
            return;
        }
        store.member(l, u, true, now());
        tell(l, Lang.msg("land.joined", "player", p.getName(), "owner", ownerName(l), "free", free(l)));
    }

    public void decline(Player p) {
        Invite inv = invites.remove(p.getUniqueId());
        p.sendMessage(Lang.msg(inv == null ? "land.no-invite" : "land.declined"));
        Player by = inv == null ? null : Bukkit.getPlayer(inv.by());
        if (by != null) by.sendMessage(Lang.msg("land.declined-by", "player", p.getName()));
    }

    public void kick(Player p, String name) {
        Land l = store.index().ownedBy(p.getUniqueId());
        if (l == null) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        Players.find(name, found -> {
            if (found.isEmpty() || !l.members.containsKey(found.get())) {
                p.sendMessage(Lang.msg("land.not-member", "player", name));
                return;
            }
            store.member(l, found.get(), false, now());
            tell(l, Lang.msg("land.kicked", "player", name, "free", free(l)));
            Player tp = Bukkit.getPlayer(found.get());
            if (tp != null) tp.sendMessage(Lang.msg("land.kicked-you", "owner", ownerName(l)));
        });
    }

    public void leave(Player p) {
        Land l = store.index().memberOf(p.getUniqueId());
        if (l == null) {
            p.sendMessage(Lang.msg("land.not-a-member"));
            return;
        }
        store.member(l, p.getUniqueId(), false, now());
        p.sendMessage(Lang.msg("land.left", "owner", ownerName(l)));
        tell(l, Lang.msg("land.left-notice", "player", p.getName(), "free", free(l)));
    }

    /** Hands the land to a member; the old owner stays on as a member (unless they live elsewhere). */
    public void transfer(Player p, String name) {
        Land l = store.index().ownedBy(p.getUniqueId());
        if (l == null) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        Players.find(name, found -> {
            UUID t = found.orElse(null);
            if (t == null || !l.members.containsKey(t)) {
                p.sendMessage(Lang.msg("land.not-member", "player", name));
                return;
            }
            if (store.index().ownedBy(t) != null) {
                p.sendMessage(Lang.msg("land.owns-another", "player", name));
                return;
            }
            UUID old = l.owner;
            store.member(l, t, false, now());
            store.setOwner(l, t);
            if (store.index().memberOf(old) == null) store.member(l, old, true, now());
            tell(l, Lang.msg("land.transferred", "from", Players.name(old), "to", name));
        });
    }

    // ------------------------------------------------------------------------------------------
    // 금고
    // ------------------------------------------------------------------------------------------

    public void vaultInfo(Player p) {
        Land l = mine(p);
        if (l == null) {
            p.sendMessage(Lang.msg("land.no-land"));
            return;
        }
        long tax = tax(l);
        p.sendMessage(Lang.msg("land.vault", "owner", ownerName(l), "vault", EconomyModule.format(l.vault), "tax", EconomyModule.format(tax),
                "days", tax <= 0 ? "-" : String.valueOf(l.vault / tax)));
        Map<UUID, Long> by = store.contributions(l);
        if (!by.isEmpty()) {
            List<String> parts = new ArrayList<>();
            by.forEach((u, v) -> parts.add(Players.name(u) + " " + EconomyModule.format(v)));
            p.sendMessage(Lang.msg("land.vault-shares", "list", String.join(", ", parts)));
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.of(eco.getConfig().getString("time.zone")));
        for (LandStore.VaultEntry e : store.history(l, 8)) {
            p.sendMessage(Lang.mm("land.vault-line", "at", f.format(Instant.ofEpochMilli(e.at())), "kind", Lang.raw("land.vault-kind." + e.kind()),
                    "player", e.player() == null ? "-" : Players.name(e.player()),
                    "amount", (e.amount() >= 0 ? "<green>+" : "<red>") + EconomyModule.format(e.amount())));
        }
    }

    public void deposit(Player p, long amount) {
        Land l = mine(p);
        if (l == null) {
            p.sendMessage(Lang.msg("land.no-land"));
            return;
        }
        if (amount <= 0) {
            p.sendMessage(Lang.msg("land.usage"));
            return;
        }
        if (!core.economy().withdraw(p.getUniqueId(), amount, "land_vault")) {
            p.sendMessage(Lang.msg("land.not-enough", "price", EconomyModule.format(amount)));
            return;
        }
        store.vault(l, p.getUniqueId(), amount, "deposit", now());
        tell(l, Lang.msg("land.deposited", "player", p.getName(), "amount", EconomyModule.format(amount), "vault", EconomyModule.format(l.vault)));
    }

    public void withdraw(Player p, long amount) {
        Land l = mine(p);
        if (l == null || !l.isOwner(p.getUniqueId())) {
            p.sendMessage(Lang.msg("land.withdraw-owner"));
            return;
        }
        if (amount <= 0 || amount > l.vault) {
            p.sendMessage(Lang.msg("land.vault-short", "vault", EconomyModule.format(l.vault)));
            return;
        }
        store.vault(l, p.getUniqueId(), -amount, "withdraw", now());
        core.economy().deposit(p.getUniqueId(), amount, "land_vault");
        tell(l, Lang.msg("land.withdrew", "player", p.getName(), "amount", EconomyModule.format(amount), "vault", EconomyModule.format(l.vault)));
    }

    // ------------------------------------------------------------------------------------------
    // Looking
    // ------------------------------------------------------------------------------------------

    public void info(Player p) {
        UUID u = p.getUniqueId();
        Land here = at(p.getLocation());
        ChunkPos c = chunk(p.getLocation());
        p.sendMessage(here == null ? Lang.msg("land.here-wild", "x", c.x(), "z", c.z())
                : Lang.msg("land.here", "owner", ownerName(here), "x", c.x(), "z", c.z()));
        Land l = mine(p);
        if (l == null) {
            p.sendMessage(Lang.msg("land.none-yet", "free", rules.free(1)));
            return;
        }
        long tax = tax(l);
        p.sendMessage(Lang.msg("land.info", "owner", ownerName(l), "role", Lang.raw(l.isOwner(u) ? "land.role-owner" : "land.role-member"),
                "size", l.size(), "free", free(l), "residents", store.index().residents(l),
                "next", EconomyModule.format(rules.price(l.size(), free(l))), "tax", EconomyModule.format(tax),
                "limit", rules.progressiveLimit(l.members.size()), "vault", EconomyModule.format(l.vault),
                "warnings", l.warnings, "warn-days", rules.warnDays()));
        List<String> names = new ArrayList<>();
        for (UUID m : l.members.keySet()) names.add(Players.name(m));
        p.sendMessage(Lang.msg("land.info-members", "count", names.size(), "list", names.isEmpty() ? "-" : String.join(", ", names)));
        Land own = store.index().ownedBy(u);
        if (own != null && own != l) p.sendMessage(Lang.msg("land.info-own-too", "size", own.size(), "tax", EconomyModule.format(tax(own))));
    }

    /** Borders of the lands around you for 10 seconds: yours green, others red, the town yellow. */
    public void show(Player p) {
        String w = p.getWorld().getName();
        int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
        List<ChunkPos> around = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                ChunkPos c = new ChunkPos(w, pcx + dx, pcz + dz);
                if (store.index().at(c) != null) around.add(c);
            }
        }
        outline(p, around, false);
        p.sendMessage(Lang.msg("land.showing", "count", around.size()));
    }

    /** Particles along the outer edges of these chunks (where the next chunk belongs to someone else). */
    private void outline(Player p, List<ChunkPos> chunks, boolean justClaimed) {
        UUID u = p.getUniqueId();
        for (int t = 0; t < 10; t++) {
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (!p.isOnline()) return;
                double y = p.getY() + 0.5;
                for (ChunkPos c : chunks) {
                    if (!c.world().equals(p.getWorld().getName())) continue;
                    Land l = store.index().at(c);
                    org.bukkit.Color color = justClaimed ? org.bukkit.Color.fromRGB(80, 200, 255)
                            : l == null || l.server() ? org.bukkit.Color.fromRGB(255, 210, 60)
                            : l.allows(u) ? org.bukkit.Color.fromRGB(90, 220, 110) : org.bukkit.Color.fromRGB(235, 70, 60);
                    Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
                    int x0 = c.minX(), z0 = c.minZ();
                    boolean n = store.index().at(new ChunkPos(c.world(), c.x(), c.z() - 1)) != l || justClaimed;
                    boolean s = store.index().at(new ChunkPos(c.world(), c.x(), c.z() + 1)) != l || justClaimed;
                    boolean we = store.index().at(new ChunkPos(c.world(), c.x() - 1, c.z())) != l || justClaimed;
                    boolean e = store.index().at(new ChunkPos(c.world(), c.x() + 1, c.z())) != l || justClaimed;
                    for (int i = 0; i <= 16; i++) {
                        if (n) p.spawnParticle(Particle.DUST, x0 + i, y, z0, 1, dust);
                        if (s) p.spawnParticle(Particle.DUST, x0 + i, y, z0 + 16, 1, dust);
                        if (we) p.spawnParticle(Particle.DUST, x0, y, z0 + i, 1, dust);
                        if (e) p.spawnParticle(Particle.DUST, x0 + 16, y, z0 + i, 1, dust);
                    }
                }
            }, t * 20L);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Admin: the server's land
    // ------------------------------------------------------------------------------------------

    /** Claims every free chunk within radius (in chunks) for the server. Returns how many. */
    public int claimServer(Player p, int radius) {
        Land server = store.index().server();
        if (server == null) server = store.create(null, now());
        String w = p.getWorld().getName();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4, n = 0;
        List<ChunkPos> done = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos c = new ChunkPos(w, cx + dx, cz + dz);
                if (store.index().at(c) != null) continue;
                store.claim(server, c, now());
                done.add(c);
                n++;
            }
        }
        outline(p, done, true);
        return n;
    }

    /** Releases every chunk within radius, whoever owns it. Lands left empty end (금고 back to the owner). */
    public int releaseAround(Player p, int radius) {
        String w = p.getWorld().getName();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4, n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos c = new ChunkPos(w, cx + dx, cz + dz);
                Land l = store.index().at(c);
                if (l == null) continue;
                store.release(l, c);
                n++;
                if (l.size() == 0) {
                    if (l.server()) store.delete(l);
                    else disband(l, "land.disbanded-empty");
                }
            }
        }
        return n;
    }

    /** /관리자 땅 정보: everything about the land you stand in. */
    public void adminInfo(Player p) {
        Land l = at(p.getLocation());
        if (l == null) {
            p.sendMessage(Lang.msg("land.here-wild", "x", p.getLocation().getBlockX() >> 4, "z", p.getLocation().getBlockZ() >> 4));
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID m : l.members.keySet()) names.add(Players.name(m));
        p.sendMessage(dev.stsv.core.util.Text.msg("<gray>땅 #" + l.id + " <white>" + ownerName(l) + "</white> 청크 " + l.size()
                + " (무료 " + free(l) + ") · 구성원 " + (names.isEmpty() ? "-" : String.join(", ", names))
                + " · 금고 " + EconomyModule.format(l.vault) + " · 하루 세금 " + EconomyModule.format(tax(l)) + " · 미납 " + l.warnings + "일"));
    }

    // ------------------------------------------------------------------------------------------
    // Property tax
    // ------------------------------------------------------------------------------------------

    private double taxMultiplier() {
        double m = 1;
        for (ActiveEvent e : eco.events().active("tax_audit")) m *= e.number("tax-multiplier", 2);
        return m;
    }

    @Override
    public void newDay(int day, long dayStart) {
        for (Land l : List.copyOf(store.index().players())) {
            if (l.size() == 0) continue;
            long tax = tax(l);
            if (tax <= 0) {
                if (l.warnings != 0) {
                    l.warnings = 0;
                    store.save(l);
                }
                continue;
            }
            if (!canPay(l, tax)) {
                l.warnings++;
                if (l.warnings < rules.warnDays()) {
                    store.save(l);
                    tell(l, Lang.msg("land.tax-warning", "tax", EconomyModule.format(tax), "n", l.warnings, "max", rules.warnDays()));
                    continue;
                }
                // the newest chunks go until what is left can be paid
                int released = 0;
                while (l.size() > 0 && !canPay(l, tax(l))) {
                    store.release(l, l.newest());
                    released++;
                }
                eco.getLogger().info("재산세 " + rules.warnDays() + "일 미납: " + ownerName(l) + " 의 땅에서 청크 " + released + "개 해제");
                tell(l, Lang.msg("land.released", "count", released, "size", l.size()));
                if (l.size() == 0) {
                    disband(l, "land.disbanded-empty");
                    continue;
                }
                tax = tax(l);
            }
            pay(l, tax);
            l.warnings = 0;
            store.save(l);
            if (rules.lowVaultDays() > 0 && l.vault < tax * rules.lowVaultDays()) {
                tell(l, Lang.msg("land.vault-low", "vault", EconomyModule.format(l.vault), "tax", EconomyModule.format(tax), "days", l.vault / tax));
            }
        }
    }

    private boolean canPay(Land l, long tax) {
        return tax <= 0 || l.vault + core.economy().balance(l.owner) >= tax;
    }

    /** The 금고 first, the owner's wallet for the rest. (금고 money already left circulation when it was put in.) */
    private void pay(Land l, long tax) {
        long fromVault = Math.min(l.vault, tax);
        if (fromVault > 0) store.vault(l, null, -fromVault, "tax", now());
        long rest = tax - fromVault;
        if (rest > 0) core.economy().withdraw(l.owner, rest, "land_tax");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Land l = store.index().home(e.getPlayer().getUniqueId());
        if (l == null || l.size() == 0) return;
        long tax = tax(l);
        if (l.warnings > 0) e.getPlayer().sendMessage(Lang.msg("land.tax-warning", "tax", EconomyModule.format(tax), "n", l.warnings, "max", rules.warnDays()));
        else if (tax > 0 && rules.lowVaultDays() > 0 && l.vault < tax * rules.lowVaultDays()) {
            e.getPlayer().sendMessage(Lang.msg("land.vault-low", "vault", EconomyModule.format(l.vault), "tax", EconomyModule.format(tax), "days", l.vault / tax));
        }
    }

    // Protection
    // ------------------------------------------------------------------------------------------

    private boolean deny(Player p, Location l, Cancellable e) {
        if (allowed(p, l)) return false;
        e.setCancelled(true);
        long now = now();
        Long last = warned.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            warned.put(p.getUniqueId(), now);
            Land c = at(l);
            HudModule.flash(p, Lang.mm("land.protected", "owner", ownerName(c)));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    private static boolean harmless(Material m) {
        return m == Material.CRAFTING_TABLE || m == Material.ENDER_CHEST || m == Material.ENCHANTING_TABLE || m == Material.STONECUTTER
                || m == Material.CARTOGRAPHY_TABLE || m == Material.LOOM || m == Material.GRINDSTONE || m == Material.SMITHING_TABLE;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() == Action.PHYSICAL) {
            if (b.getType() == Material.FARMLAND || b.getType() == Material.TURTLE_EGG) deny(e.getPlayer(), b.getLocation(), e);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        boolean usesBlock = interactive(b) && !harmless(b.getType()) && !(e.getPlayer().isSneaking() && e.getItem() != null);
        // in the town (server claims) doors, gates and buttons work for everyone
        Land here = at(b.getLocation());
        if (usesBlock && here != null && here.server() && passage(b.getType())) usesBlock = false;
        boolean usesItem = e.getItem() != null && (e.getItem().getType() == Material.FLINT_AND_STEEL || e.getItem().getType() == Material.FIRE_CHARGE
                || e.getItem().getType().name().endsWith("_SPAWN_EGG") || e.getItem().getType() == Material.ARMOR_STAND
                || e.getItem().getType() == Material.ITEM_FRAME || e.getItem().getType() == Material.GLOW_ITEM_FRAME || e.getItem().getType() == Material.PAINTING
                || e.getItem().getType() == Material.BONE_MEAL || e.getItem().getType().name().endsWith("_BOAT") || e.getItem().getType().name().endsWith("MINECART"));
        if (usesBlock || usesItem) deny(e.getPlayer(), b.getLocation(), e);
    }

    /** Blocks that do something on right-click (containers, doors, redstone parts, ...). */
    private static boolean interactive(Block b) {
        if (b.getState(false) instanceof org.bukkit.block.Container) return true;
        Material m = b.getType();
        String n = m.name();
        return passage(m) || n.endsWith("_BED") || n.endsWith("ANVIL") || n.endsWith("_SIGN") || n.endsWith("CANDLE") || n.endsWith("CAKE")
                || n.startsWith("POTTED_") || n.endsWith("CAMPFIRE") || m == Material.REPEATER || m == Material.COMPARATOR
                || m == Material.DAYLIGHT_DETECTOR || m == Material.NOTE_BLOCK || m == Material.JUKEBOX || m == Material.BEACON
                || m == Material.COMPOSTER || m == Material.LECTERN || m == Material.RESPAWN_ANCHOR || m == Material.CHISELED_BOOKSHELF
                || m == Material.DECORATED_POT || m == Material.FLOWER_POT || m == Material.CAULDRON || m == Material.WATER_CAULDRON
                || m == Material.LAVA_CAULDRON || m == Material.BEEHIVE || m == Material.BEE_NEST || m == Material.DRAGON_EGG
                || m == Material.SWEET_BERRY_BUSH || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT || m == Material.REDSTONE_WIRE
                || m == Material.CRAFTER || m == Material.VAULT || m == Material.TRIAL_SPAWNER || m == Material.SPAWNER || m == Material.COMMAND_BLOCK;
    }

    private static boolean passage(Material m) {
        String n = m.name();
        return n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.endsWith("_FENCE_GATE") || n.endsWith("_BUTTON") || m == Material.LEVER || m == Material.BELL;
    }

    private static boolean freeToTouch(Entity en) {
        return en instanceof Player || en instanceof Villager || en instanceof WanderingTrader || en instanceof Monster;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (freeToTouch(e.getRightClicked())) return;
        deny(e.getPlayer(), e.getRightClicked().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteractAt(org.bukkit.event.player.PlayerInteractAtEntityEvent e) {
        if (freeToTouch(e.getRightClicked())) return;
        deny(e.getPlayer(), e.getRightClicked().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        deny(e.getPlayer(), e.getRightClicked().getLocation(), e);
    }

    /**
     * Planting, watering, harvesting or breaking crops on someone else's land. Harvests too: most reach here already
     * stopped (the click or the hit), but a farmer's 3x3 harvest calls the crop's harvest for the neighbours directly.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCrop(dev.stsv.core.crop.CropEvent e) {
        deny(e.player(), e.location(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFurnitureUse(FurnitureInteractEvent e) {
        deny(e.getPlayer(), e.getFurniture().location(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent e) {
        deny(e.getPlayer(), e.getFurniture().location(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (freeToTouch(e.getEntity())) return;
        Player by = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (by != null) deny(by, e.getEntity().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Entity r = e.getRemover();
        Player by = r instanceof Player p ? p : r instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (by != null) deny(by, e.getEntity().getLocation(), e);
        else if (at(e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicle(VehicleDestroyEvent e) {
        if (e.getAttacker() instanceof Player p) deny(p, e.getVehicle().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> at(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> at(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (at(e.getBlock().getLocation()) == null) return;
        if (e.getPlayer() != null) deny(e.getPlayer(), e.getBlock().getLocation(), e);
        else if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD || e.getCause() == BlockIgniteEvent.IgniteCause.LAVA) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE && at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Land to = at(e.getToBlock().getLocation());
        if (to != null && to != at(e.getBlock().getLocation())) e.setCancelled(true);
    }

    private boolean crosses(Block piston, List<Block> moved, BlockFace dir) {
        Land home = at(piston.getLocation());
        List<Block> touched = new ArrayList<>(moved);
        for (Block b : moved) touched.add(b.getRelative(dir));
        touched.add(piston.getRelative(dir));
        for (Block b : touched) {
            Land c = at(b.getLocation());
            if (c != null && c != home) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        if (crosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent e) {
        if (crosses(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        Land c = at(e.getTo());
        long id = c == null ? -1 : c.id;
        Long before = where.put(e.getPlayer().getUniqueId(), id);
        if (before != null && before != id) {
            HudModule.flash(e.getPlayer(), c == null ? Lang.mm("land.leave")
                    : Lang.mm("land.enter", "owner", ownerName(c)));
        }
    }
}
