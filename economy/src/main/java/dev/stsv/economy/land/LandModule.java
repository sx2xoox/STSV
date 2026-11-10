package dev.stsv.economy.land;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.world.WorldRules;
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
import org.bukkit.entity.Enemy;
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
 *
 * Worlds (core config.yml "worlds", rules in {@link LandAccess}): land is bought only in the 건축월드, where
 * nobody changes blocks outside a land they live on; the 야생월드 and its 지옥 and 엔드 are free (claims there
 * are dormant); claims made earlier in other worlds keep protecting.
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
        // 탈것: the people of the owner's land (where the owner lives) may drive it and open its bed
        if (core.vehicles() != null) core.vehicles().trust((owner, player) -> {
            Land home = store.index().home(owner);
            return home != null && home.allows(player);
        });
        eco.calendar().onDaily(this);
        eco.onReset(store::reset);
        // once: the chunks every owner has now count as claimed by them (the land achievements count only those)
        if (!eco.kv().has("land.claims-counted")) {
            for (Land l : store.index().all()) if (l.owner != null) eco.kv().set(CLAIMED + l.owner, l.size());
            eco.kv().set("land.claims-counted", 1);
        }
    }

    @Override
    protected void stop() {
        if (core.vehicles() != null) core.vehicles().trust(null);
    }

    private static final String CLAIMED = "land.claimed.";

    /**
     * Chunks a player owns that they claimed themselves (the land achievements): a land handed over with /땅 양도
     * does not count for the new owner, so passing one land around does not earn the achievement for everyone.
     */
    public int ownChunks(UUID player) {
        Land l = store.index().ownedBy(player);
        return l == null ? 0 : (int) Math.min(l.size(), eco.kv().getLong(CLAIMED + player, 0));
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

    /** The land here; none in the wild world, its nether or its end (claims there are dormant). */
    public Land at(Location l) {
        if (l.getWorld() == null || !LandAccess.claimsCount(kind(l))) return null;
        return store.index().at(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    private WorldRules.Kind kind(Location l) {
        return core.worlds() == null ? WorldRules.Kind.OTHER : core.worlds().kind(l.getWorld());
    }

    /** Unclaimed build world: no land, but protected all the same. */
    private static final Object UNCLAIMED_BUILD = new Object();

    /** What owns the blocks here as far as nature is concerned: a land, the unclaimed build world, or null (free). */
    private Object zone(Location l) {
        Land land = at(l);
        if (land != null) return land;
        return LandAccess.sheltered(kind(l), null) ? UNCLAIMED_BUILD : null;
    }

    /** "No land here": the wild world, or its nether / end (where dying keeps items and XP when that is on). */
    private Component freeWorld(org.bukkit.World w, WorldRules.Kind kind) {
        if (kind == WorldRules.Kind.WILD) return Lang.msg("land.here-wild-world");
        return Lang.msg(core.worlds() != null && core.worlds().keepsInventory(w) ? "land.here-wild-dimension-keep" : "land.here-wild-dimension",
                "world", core.worlds() == null ? w.getName() : core.worlds().displayName(w));
    }

    private static ChunkPos chunk(Location l) {
        return ChunkPos.ofBlock(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    /** May this player change blocks here? */
    public boolean allowed(Player p, Location l) {
        return verdict(p, l, true) == LandAccess.Verdict.ALLOW;
    }

    private LandAccess.Verdict verdict(Player p, Location l, boolean changes) {
        return LandAccess.decide(kind(l), at(l), p.getUniqueId(), p.hasPermission("stsv.economy.land.bypass"), changes);
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
        if (!LandAccess.claimable(kind(p.getLocation()))) {
            p.sendMessage(Lang.msg("land.build-world-only", "free", rules.free(1)));
            return;
        }
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
        eco.kv().set(CLAIMED + u, eco.kv().getLong(CLAIMED + u, 0) + 1);
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
            core.economy().move(null, l.owner, l.vault, EconomyStore.Kind.ESCROW, "land_vault_refund", null);
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
                p.sendMessage(Lang.msg("land.already-member", "player", Players.name(t)));
                return;
            }
            if (store.index().memberOf(t) != null) {
                p.sendMessage(Lang.msg("land.member-elsewhere", "player", Players.name(t)));
                return;
            }
            invites.put(t, new Invite(l.id, p.getUniqueId(), now() + rules.inviteMinutes() * 60_000L));
            p.sendMessage(Lang.msg("land.invited", "player", Players.name(t), "minutes", rules.inviteMinutes()));
            Player tp = Bukkit.getPlayer(t);
            if (tp != null) {
                tp.sendMessage(Lang.msg("land.invite", "owner", Names.mini(p), "size", l.size(), "members", l.members.size(), "minutes", rules.inviteMinutes()));
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
            p.sendMessage(Lang.msg("land.member-elsewhere", "player", Names.mini(p)));
            return;
        }
        store.member(l, u, true, now());
        tell(l, Lang.msg("land.joined", "player", Names.mini(p), "owner", ownerName(l), "free", free(l)));
    }

    public void decline(Player p) {
        Invite inv = invites.remove(p.getUniqueId());
        p.sendMessage(Lang.msg(inv == null ? "land.no-invite" : "land.declined"));
        Player by = inv == null ? null : Bukkit.getPlayer(inv.by());
        if (by != null) by.sendMessage(Lang.msg("land.declined-by", "player", Names.mini(p)));
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
            tell(l, Lang.msg("land.kicked", "player", Players.name(found.get()), "free", free(l)));
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
        tell(l, Lang.msg("land.left-notice", "player", Names.mini(p), "free", free(l)));
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
                p.sendMessage(Lang.msg("land.owns-another", "player", Players.name(t)));
                return;
            }
            UUID old = l.owner;
            store.member(l, t, false, now());
            store.setOwner(l, t);
            if (l.vault > 0) eco.rank().handOver(old, t, l.vault);   // the 금고 is now the new owner's
            if (store.index().memberOf(old) == null) store.member(l, old, true, now());
            tell(l, Lang.msg("land.transferred", "from", Players.name(old), "to", Players.name(t)));
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
        // held, not destroyed (ESCROW): it comes back out, or is destroyed when tax is paid from it (pay)
        if (!core.economy().move(p.getUniqueId(), null, amount, EconomyStore.Kind.ESCROW, "land_vault", null)) {
            p.sendMessage(Lang.msg("land.not-enough", "price", EconomyModule.format(amount)));
            return;
        }
        store.vault(l, p.getUniqueId(), amount, "deposit", now());
        if (!l.isOwner(p.getUniqueId()) && l.owner != null) eco.rank().handOver(p.getUniqueId(), l.owner, amount);   // a member gave it
        tell(l, Lang.msg("land.deposited", "player", Names.mini(p), "amount", EconomyModule.format(amount), "vault", EconomyModule.format(l.vault)));
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
        core.economy().move(null, p.getUniqueId(), amount, EconomyStore.Kind.ESCROW, "land_vault", null);
        tell(l, Lang.msg("land.withdrew", "player", Names.mini(p), "amount", EconomyModule.format(amount), "vault", EconomyModule.format(l.vault)));
    }

    // ------------------------------------------------------------------------------------------
    // Looking
    // ------------------------------------------------------------------------------------------

    public void info(Player p) {
        UUID u = p.getUniqueId();
        Land here = at(p.getLocation());
        ChunkPos c = chunk(p.getLocation());
        WorldRules.Kind world = kind(p.getLocation());
        p.sendMessage(here != null ? Lang.msg("land.here", "owner", ownerName(here), "x", c.x(), "z", c.z())
                : !LandAccess.claimsCount(world) ? freeWorld(p.getWorld(), world)
                : world == WorldRules.Kind.BUILD ? Lang.msg("land.here-wild", "x", c.x(), "z", c.z())
                : Lang.msg("land.here-other-world"));
        Land l = mine(p);
        if (l == null) {
            p.sendMessage(Lang.msg("land.none-yet", "free", rules.free(1)));
            return;
        }
        long tax = tax(l);
        p.sendMessage(Lang.msg("land.info", "owner", ownerName(l), "role", Lang.raw(l.isOwner(u) ? "land.role-owner" : "land.role-member"),
                "size", l.size(), "free", free(l), "residents", store.index().residents(l),
                "next", EconomyModule.format(rules.price(l.size(), free(l))), "tax", EconomyModule.format(tax),
                "limit", rules.progressiveLimit(l.members.size()), "limit2", rules.progressiveLimit2(l.members.size()),
                "factor", fmt(rules.progressiveFactor()), "factor2", fmt(rules.progressiveFactor2()), "vault", EconomyModule.format(l.vault),
                "warnings", l.warnings, "warn-days", rules.warnDays()));
        List<String> names = new ArrayList<>();
        for (UUID m : l.members.keySet()) names.add(Players.name(m));
        p.sendMessage(Lang.msg("land.info-members", "count", names.size(), "list", names.isEmpty() ? "-" : String.join(", ", names)));
        Land own = store.index().ownedBy(u);
        if (own != null && own != l) p.sendMessage(Lang.msg("land.info-own-too", "size", own.size(), "tax", EconomyModule.format(tax(own))));
    }

    /** Borders of the lands around you for 10 seconds: yours green, others red, the town yellow. */
    public void show(Player p) {
        if (!LandAccess.claimsCount(kind(p.getLocation()))) {
            p.sendMessage(freeWorld(p.getWorld(), kind(p.getLocation())));
            return;
        }
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

    /** Releases only the server's land (spawn town) within radius; players' land is left alone. */
    public int releaseServerAround(Player p, int radius) {
        String w = p.getWorld().getName();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4, n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Land l = store.index().at(new ChunkPos(w, cx + dx, cz + dz));
                if (l == null || !l.server()) continue;
                store.release(l, new ChunkPos(w, cx + dx, cz + dz));
                n++;
                if (l.size() == 0) store.delete(l);
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

    /** 2 -> "2", 2.5 -> "2.5" (the progressive factors in messages). */
    private static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
    }

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

    /** The 금고 first, the owner's wallet for the rest. (금고 money was held, not destroyed: it is destroyed now.) */
    private void pay(Land l, long tax) {
        long fromVault = Math.min(l.vault, tax);
        if (fromVault > 0) {
            store.vault(l, null, -fromVault, "tax", now());
            core.economy().burn(fromVault, "land_tax", "vault #" + l.id);
        }
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

    /** Changing blocks (break, place, pour, ignite, till...): others' land and the unclaimed build world say no. */
    private boolean deny(Player p, Location l, Cancellable e) {
        return deny(p, l, e, true);
    }

    /** changes = false: only using something (a door, a chest, an animal), fine outside lands. */
    private boolean deny(Player p, Location l, Cancellable e, boolean changes) {
        LandAccess.Verdict v = verdict(p, l, changes);
        if (v == LandAccess.Verdict.ALLOW) return false;
        e.setCancelled(true);
        long now = now();
        Long last = warned.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            warned.put(p.getUniqueId(), now);
            HudModule.flash(p, v == LandAccess.Verdict.UNCLAIMED_BUILD ? Lang.mm("land.build-own-only", "free", rules.free(1))
                    : Lang.mm("land.protected", "owner", ownerName(at(l))));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    /** Placing blocks, and through it furniture and machines (the core asks with a place event per cell). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        deny(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    /** Scooping water or lava changes blocks; milking a cow or goat (the event names the block it stands on) only uses it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        boolean milk = e.getItemStack() != null && e.getItemStack().getType() == Material.MILK_BUCKET;
        deny(e.getPlayer(), e.getBlock().getLocation(), e, !milk);
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
        boolean townPassage = here != null && here.server() && passage(b.getType());
        if (usesBlock && townPassage) usesBlock = false;
        Material item = e.getItem() == null ? Material.AIR : e.getItem().getType();
        String n = item.name();
        // tools that till, strip, flatten or wax; on a town door only an axe or honeycomb on copper (scrape, wax) changes it
        boolean toolChanges = townPassage
                ? (n.endsWith("_AXE") || item == Material.HONEYCOMB) && b.getType().name().contains("COPPER")
                : !usesBlock && (n.endsWith("_HOE") || n.endsWith("_SHOVEL") || n.endsWith("_AXE") || item == Material.HONEYCOMB);
        // items that change blocks or hang things on them: fire, bone meal, frames, stands
        boolean changes = item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE || item == Material.BONE_MEAL
                || item == Material.ARMOR_STAND || item == Material.ITEM_FRAME || item == Material.GLOW_ITEM_FRAME || item == Material.PAINTING
                || toolChanges;
        // items that only bring something out (mobs, boats, carts)
        boolean usesItem = n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("MINECART");
        if (changes) deny(e.getPlayer(), b.getLocation(), e, true);
        else if (usesBlock || usesItem) deny(e.getPlayer(), b.getLocation(), e, false);
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

    /**
     * Right-clicking these is fine anywhere (players, villagers: trading is the villager rule's business, hostile mobs,
     * vehicles: anyone may drive through any land, and the core's vehicle rules say who may drive, open or pick up one).
     */
    private static boolean freeToTouch(Entity en) {
        return en instanceof Player || en instanceof Villager || en instanceof WanderingTrader || en instanceof Enemy
                || dev.stsv.core.vehicle.VehicleModule.isVehiclePart(en);
    }

    /**
     * Hitting these is fine anywhere: players (PvP has its own rule) and hostile mobs, phantoms, slimes, ghasts,
     * shulkers and hoglins included. Not villagers or wandering traders: on someone's land or in the town only its
     * people may hurt them.
     */
    private static boolean freeToHit(Entity en) {
        return en instanceof Player || en instanceof Enemy;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (freeToTouch(e.getRightClicked())) return;
        deny(e.getPlayer(), e.getRightClicked().getLocation(), e, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteractAt(org.bukkit.event.player.PlayerInteractAtEntityEvent e) {
        if (freeToTouch(e.getRightClicked())) return;
        deny(e.getPlayer(), e.getRightClicked().getLocation(), e, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) {
        deny(e.getPlayer(), e.getRightClicked().getLocation(), e, false);
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
        deny(e.getPlayer(), e.getFurniture().location(), e, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent e) {
        deny(e.getPlayer(), e.getFurniture().location(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (freeToHit(e.getEntity())) return;
        Player by = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        // hitting animals and the like is using, not building (others' land still says no)
        if (by != null) deny(by, e.getEntity().getLocation(), e, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Entity r = e.getRemover();
        Player by = r instanceof Player p ? p : r instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (by != null) deny(by, e.getEntity().getLocation(), e);
        else if (zone(e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicle(VehicleDestroyEvent e) {
        if (e.getAttacker() instanceof Player p) deny(p, e.getVehicle().getLocation(), e, false);
    }

    // nature (explosions, spreading fire, liquids and pistons from elsewhere) changes nothing in a land or in the
    // build world outside lands; the wild world, its nether and end (and unclaimed other worlds) are free

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> zone(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> zone(b.getLocation()) != null);
    }

    /**
     * Mobs changing blocks: endermen taking blocks, ravagers breaking crops and leaves, withers and the dragon breaking
     * blocks, silverfish hiding in stone, zombies and vindicators breaking doors. Not in a land or the build world.
     * Falling blocks (sand, gravel) may land where they started (in the same land); a player's arrow or trident (a
     * pot, a chorus flower) asks as that player. Animals (sheep eating grass, villagers farming, rabbits, foxes) and
     * golems keep their vanilla ways.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        Entity en = e.getEntity();
        if (en instanceof Player) return;   // a player's own change comes through the block events
        Location at = e.getBlock().getLocation();
        Object z = zone(at);
        if (z == null) return;
        if (en instanceof org.bukkit.entity.FallingBlock fb) {
            Location from = fb.getOrigin();
            if (from != null && from.getWorld() != null && zone(from) != z) e.setCancelled(true);
            return;
        }
        if (en instanceof Projectile pr) {
            if (pr.getShooter() instanceof Player p) deny(p, at, e);
            else e.setCancelled(true);
            return;
        }
        if (en instanceof Enemy || en instanceof org.bukkit.entity.Wither || en instanceof org.bukkit.entity.EnderDragon) e.setCancelled(true);
    }

    /** A dispenser does not pour, place or shoot into another land (or from outside into one). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.DISPENSER || !(b.getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        Object to = zone(b.getRelative(d.getFacing()).getLocation());
        if (to != null && to != zone(b.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (zone(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) deny(e.getPlayer(), e.getBlock().getLocation(), e);
        else if (zone(e.getBlock().getLocation()) != null
                && (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD || e.getCause() == BlockIgniteEvent.IgniteCause.LAVA)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE && zone(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Object to = zone(e.getToBlock().getLocation());
        if (to != null && to != zone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    private boolean crosses(Block piston, List<Block> moved, BlockFace dir) {
        Object home = zone(piston.getLocation());
        List<Block> touched = new ArrayList<>(moved);
        for (Block b : moved) touched.add(b.getRelative(dir));
        touched.add(piston.getRelative(dir));
        for (Block b : touched) {
            Object c = zone(b.getLocation());
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
            HudModule.flash(e.getPlayer(), c != null ? Lang.mm("land.enter", "owner", ownerName(c))
                    : kind(e.getTo()) == WorldRules.Kind.BUILD ? Lang.mm("land.leave-build") : Lang.mm("land.leave"));
        }
    }
}
