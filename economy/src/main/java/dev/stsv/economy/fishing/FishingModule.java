package dev.stsv.economy.fishing;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 낚시: as in vanilla, right click while the float is pulled under to reel in (too late and the fish is gone). What
 * comes up is one of 98 fish by habitat, time and weather, its rarity rolled by the job (rare and legend: the fisher's
 * finds chance, more from a boat, x{@link FishSpec#FISHER_RARE_BONUS} for a fisher from Lv20). A fish has no size (2026-11):
 * they all stack and sell alike; the 낚시대회 ({@link TournamentModule}) gives a catch a size in its message only. Fish caught
 * before carry their old size: {@link #tidy} turns them into the plain fish (when the owner joins, opens a container, picks
 * one up or takes it out of the mailbox) and pays once what the size was worth above base. Every catch goes in the /도감
 * codex; a full habitat gives a title, all fish [강태공]. Treasure and junk stay vanilla.
 */
public final class FishingModule extends EcoModule {
    private static final Set<Material> FISH = Set.of(Material.COD, Material.SALMON, Material.PUFFERFISH, Material.TROPICAL_FISH);

    /** the boat (its vehicle number) a crate of catch was hauled by: only that boat's hold sells it at a harbour */
    public static final NamespacedKey VESSEL = new NamespacedKey("economy", "vessel");

    private FishSpec spec;
    private NetSpec net = NetSpec.NONE;
    private FishStore store;
    private List<String> order = List.of();
    /** testing (/관리자 낚시): the next bites of a player, by rarity id ("pearl" too) or by fish */
    private final Map<UUID, Forced> forced = new HashMap<>();

    /** rarity: a rarity id or "pearl" (null when a fish is set); species: a fish id; left: bites to go. */
    public record Forced(String rarity, String species, int left) {}
    private final Random rnd = new Random();

    public FishingModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-fishing";
    }

    @Override
    protected void start() {
        store = new FishStore(core.db());
        store.init();
        load();
        eco.kv().remove("fish.week");   // the week's biggest catch (until 2026-11): no longer kept
        eco.onReset(store::reset);
        if (core.vehicles() != null) core.vehicles().netHaul(this::netHaul);   // 배의 그물 (코어 작업기): 어획 상자, 없으면 낱마리
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        forced.clear();
        if (core.vehicles() != null) core.vehicles().netHaul(null);
    }

    // ------------------------------------------------------------------------------------------
    // The boats' nets (the core's 작업기: trawling slowly on the sea)
    // ------------------------------------------------------------------------------------------

    /** 원양 조업 (fish.yml net:): the seas, the crates, what each boat's net is made for. */
    public NetSpec net() {
        return net;
    }

    /**
     * How far this water is from the nearest harbour (a 항구 하역장 in the same world), in blocks across the map; -1 = the
     * world has no harbour (nowhere to sell a crate: the net brings up single fish as before).
     */
    double harbourDistance(Block water) {
        double best = -1;
        String world = water.getWorld().getName();
        for (dev.stsv.economy.dock.Dock d : eco.docks().docks()) {
            if (d.kind() != dev.stsv.economy.dock.DockLayout.Kind.PORT || !d.world().equals(world)) continue;
            double dist = Math.hypot(d.x() - water.getX(), d.z() - water.getZ());
            if (best < 0 || dist < best) best = dist;
        }
        return best;
    }

    /**
     * What a boat's net brings up (the core asks, every net.seconds on average, while the net is down): one fish of that
     * water ({@link #netCatch}). Nothing else: the crates of catch a net hauled in 3.66.0 (원양 조업, sold at a harbour's
     * fish auction) were more than a fishing boat needs, and are no longer hauled; the ones still in a hold sell as before.
     */
    private ItemStack netHaul(Player p, Block water, dev.stsv.core.vehicle.NetHaul.Boat boat) {
        return netCatch(p, water);
    }

    /** The boat a crate of catch was hauled by, or 0 (not a crate, or one that was handed out). */
    public static long vessel(ItemStack crate) {
        if (crate == null || crate.isEmpty() || !crate.hasItemMeta()) return 0;
        Long id = crate.getPersistentDataContainer().get(VESSEL, PersistentDataType.LONG);
        return id == null ? 0 : id;
    }

    /**
     * One fish of the water at this block for the one at the helm of a boat whose net is down: a common or uncommon fish
     * of that water at any time, as a 통발 catches them (never rare, legend, a pearl or a special). The sea, a river, a lake
     * or a swamp alike (a raft fishes a river). A fisher gets their usual touch: more uncommons (jobs.yml trap-uncommon),
     * two of the fish the job doubles at its level, and the job's trap experience. null = a cave's water (no boat's place).
     */
    private ItemStack netCatch(Player p, Block water) {
        boolean underground = water.getLightFromSky() == 0 && water.getY() < water.getWorld().getSeaLevel() - 8;
        String habitat = FishSpec.habitat(water.getBiome().getKey().asString(), underground);
        if (habitat.equals("cave")) return null;
        UUID u = p.getUniqueId();
        List<FishSpec.Rarity> rarities = new ArrayList<>(spec.rarities().values());
        if (rarities.size() < 2) return null;
        double uncommon = netUncommon(eco.jobs().effect(u, "fisher", "trap-uncommon"));
        FishSpec.Species s = spec.pick(habitat, TrapSpec.ANY_TIME, rarities.get(rnd.nextDouble() < uncommon ? 1 : 0), rnd);
        if (s == null || spec.rarities().get(s.rarity()).index() >= 2) return null;
        boolean fisher = "fisher".equals(eco.jobs().job(u));
        JobSpec.Job fj = eco.jobs().spec().jobs().get("fisher");
        int count = fisher && fj != null && fj.finds() != null && eco.jobs().level(u) >= fj.finds().doubleLevel()
                && fj.finds().doubleItems().contains(s.item()) ? 2 : 1;
        ItemStack it = Items.create(s.item(), count).orElse(null);
        if (it == null) return null;
        eco.jobs().rewardAction(p, "trap", s.item(), count);
        return it;
    }

    /** The net's uncommon share: a rod's 35 % (common 65 : uncommon 35), more for a fisher (their trap-uncommon). */
    static double netUncommon(double fisherBonus) {
        return Math.clamp(0.35 + Math.max(0, fisherBonus), 0, 1);
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = FishSpec.parse(eco.data("fish.yml"), problems);
        net = NetSpec.parse(eco.data("fish.yml").getConfigurationSection("net"), problems);
        for (NetSpec.Zone z : net.zones()) if (Items.create(z.item(), 1).isEmpty()) problems.add("net.zones." + z.id() + ": 없는 아이템 " + z.item());
        for (FishSpec.Species s : spec.species().values()) if (Items.create(s.item(), 1).isEmpty()) problems.add(s.id() + ": 없는 아이템 " + s.item());
        problems.forEach(p -> eco.getLogger().warning("[fish.yml] " + p));
        order = new ArrayList<>(spec.species().keySet());
    }

    public FishSpec spec() {
        return spec;
    }

    public FishStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // The bite
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (e.getState() == PlayerFishEvent.State.FISHING) {
            // the wait for a bite is vanilla's (Lure as vanilla); a fisher's is shorter by the job's "bite" cut
            double cut = eco.jobs().effect(u, "fisher", "bite");
            double bite = eco.accessories() == null ? 0 : Math.clamp(eco.accessories().value(u, "bite"), 0, 0.9);   // 장신구 손맛 (after the job's)
            if (cut <= 0 && bite <= 0) return;
            ItemStack rod = p.getInventory().getItem(e.getHand() == null ? org.bukkit.inventory.EquipmentSlot.HAND : e.getHand());
            int lure = rod == null ? 0 : rod.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LURE);
            int[] wait = FishSpec.waitTicks(lure, Math.max(0, cut));
            e.getHook().setApplyLure(false);   // Lure is in the range already
            e.getHook().setWaitTime(Math.max(1, (int) Math.round(wait[0] * (1 - bite))), Math.max(1, (int) Math.round(wait[1] * (1 - bite))));
            return;
        }
        // reeled in while the float was under (vanilla's catch): ours to fill
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) return;
        Forced force = forced.get(u);
        if (force == null && !FISH.contains(caught.getItemStack().getType())) return;   // treasure and junk: vanilla
        Location at = e.getHook().getLocation();
        Block b = at.getBlock();
        World w = at.getWorld();
        boolean underground = b.getLightFromSky() == 0 && b.getY() < w.getSeaLevel() - 8;
        String habitat = FishSpec.habitat(b.getBiome().getKey().asString(), underground);
        FishSpec.Moment m = new FishSpec.Moment(w.getTime(), w.hasStorm(), w.isThundering(), (int) ((w.getFullTime() / 24000) % 8));
        // a fisher fishing from a boat (sitting in a 통통배) gets the job's boat-rare on top
        boolean onBoat = core.vehicles() != null && core.vehicles().ridingBoat(p);
        boolean isFisher = "fisher".equals(eco.jobs().job(u));
        boolean lv20 = isFisher && eco.jobs().level(u) >= FISHER_RARE_LEVEL;
        double rare = rareChance(eco.jobs().findChance(u, "fisher"), eco.jobs().effect(u, "fisher", "boat-rare"), onBoat,
                lv20 ? FishSpec.FISHER_RARE_BONUS : isFisher ? FishSpec.FISHER_RARE_BONUS_LOW : 1);
        FishSpec.Rarity tier = spec.tier(rare, rnd);
        FishSpec.Species s;
        if (force != null) {
            // a test bite: that fish wherever and whenever, or that rarity from this water
            if (force.left() <= 1) forced.remove(u);
            else forced.put(u, new Forced(force.rarity(), force.species(), force.left() - 1));
            if (force.species() != null) s = spec.species().get(force.species());
            else if (force.rarity().equals("pearl")) s = pearl();
            else s = spec.pick(habitat, m, spec.rarities().get(force.rarity()), rnd);
        } else if (tier.index() == 2 && rnd.nextDouble() < spec.pearlChance(habitat)) {   // the sea's and the rivers' rare bites
            s = pearl();
        } else {
            s = spec.pick(habitat, m, tier, rnd);
        }
        if (s == null) return;
        e.setCancelled(true);
        caught.remove();
        e.getHook().remove();
        EquipmentSlot hand = e.getHand() == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        land(p, s, hand, habitat, true);
        var acc = eco.accessories();
        if (acc != null) {
            acc.activity(u, "fish", 1);
            acc.drop(p, "fish");
            // 장신구 만선: a common or uncommon fish comes up twice
            if (spec.rarities().get(s.rarity()).index() <= 1 && !s.id().equals("pearl") && acc.roll(u, "full_net")) land(p, s, hand, habitat, false);
            // 장신구 용궁 보물: a 용궁 상자 with the catch
            if (acc.roll(u, "dragon_treasure")) Items.create(acc.spec().chestItem(), 1).ifPresent(c -> {
                for (ItemStack left : p.getInventory().addItem(c).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("accessory.chest-caught"));
                p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CONDUIT_ACTIVATE, 0.6f, 1.6f);
            });
        }
    }

    /** A pearl comes up as a rare bite of the sea or of a river. */
    private FishSpec.Species pearl() {
        String rare = new ArrayList<>(spec.rarities().keySet()).get(2);
        return new FishSpec.Species("pearl", Lang.raw("fishing.pearl"), "salt", rare, 0, 0, 80, "f", "economy:pearl", null);
    }

    /** Testing: the player's next bites are of that rarity ("pearl" too) or that fish; times = how many. */
    public void force(UUID player, String rarity, String species, int times) {
        forced.put(player, new Forced(rarity, species, Math.max(1, times)));
    }

    public Forced forced(UUID player) {
        return forced.get(player);
    }

    public boolean unforce(UUID player) {
        return forced.remove(player) != null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        forced.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // The catch
    // ------------------------------------------------------------------------------------------

    /**
     * The fish on the line comes up: into the inventory, with what vanilla gives for a catch, and the codex. water = the hook's
     * habitat; bite = the bite itself (not the 장신구 만선's second fish): only that one counts in a 낚시대회.
     */
    private void land(Player p, FishSpec.Species s, EquipmentSlot hand, String water, boolean bite) {
        UUID u = p.getUniqueId();
        FishSpec.Rarity r = spec.rarities().get(s.rarity());
        boolean fisher = "fisher".equals(eco.jobs().job(u));
        int level = fisher ? eco.jobs().level(u) : 0;
        boolean pearl = s.id().equals("pearl");
        JobSpec.Job fj = eco.jobs().spec().jobs().get("fisher");
        int count = fisher && fj != null && fj.finds() != null && level >= fj.finds().doubleLevel() && fj.finds().doubleItems().contains(s.item()) ? 2 : 1;
        ItemStack it = Items.create(s.item(), count).orElse(new ItemStack(Material.COD, count));
        boolean trial = eco.guide() != null && eco.guide().active(p);   // 길라잡이: a trial fish, and no records
        if (trial) Items.markTrial(it);
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        // what vanilla would have given: experience, rod wear, the statistic
        p.giveExp(1 + rnd.nextInt(6), true);   // applyMending: like the orb a vanilla catch drops, Mending gear takes it first
        ItemStack rod = p.getInventory().getItem(hand);
        if (rod.getType() == Material.FISHING_ROD) p.getInventory().setItem(hand, rod.damage(1, p));
        p.incrementStatistic(Statistic.FISH_CAUGHT);
        eco.jobs().rewardAction(p, "catch", s.item(), 1);
        if (r.index() >= 2) eco.jobs().rewardAction(p, "find", s.item(), 1);
        eco.jobs().rollSpecials(p, "catch", null);
        // a 낚시대회 running: a rod's catch scores (a trial fish never), and gets a size in its message only
        TournamentModule.Scored scored = !pearl && bite && !trial && eco.tournament() != null ? eco.tournament().caught(p, s, water) : null;
        // the title: the fish's picture and name, its rarity below (and the tournament's size grade)
        int idx = order.indexOf(s.id());
        String head = (idx >= 0 ? FishSpec.icon(idx) + " " : "") + "<" + r.color() + ">" + s.name();
        String sub = pearl ? "" : scored != null ? Lang.raw("fishing.result-tournament", "color", r.color(), "rarity", r.name(), "grade", scored.grade())
                : Lang.raw("fishing.result", "color", r.color(), "rarity", r.name());
        p.showTitle(Title.title(Text.mm(head), Text.mm(sub), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2500), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f + r.index() * 0.1f);
        p.sendMessage(Lang.msg(pearl ? "fishing.caught-pearl" : "fishing.caught",
                "color", r.color(), "fish", s.name(), "rarity", r.name(), "count", count > 1 ? " ×" + count : ""));
        if (scored != null) p.sendMessage(scored.line());
        if (pearl || trial) return;
        boolean first = store.record(u, s.id(), 0, now());   // no size: the codex's biggest catch stays what it was
        // the codex takes one of the fish to register it (/도감)
        if (first && !eco.codex().registered(u).contains(dev.stsv.economy.shop.ShopCatalog.norm(s.item()))) {
            p.sendMessage(Lang.msg("fishing.new-species", "fish", s.name()));
        }
    }

    /** The fisher's level from which rare and legend bites are {@link FishSpec#FISHER_RARE_BONUS} times as likely. */
    public static final int FISHER_RARE_LEVEL = 20;

    /**
     * The chance of a rare or legend bite: the job's finds chance (anyone else: the outsider chance) and, while riding a boat
     * (통통배), the job's boat-rare on top (0 for anyone but a fisher: it is the fisher's effect).
     */
    public static double rareChance(double finds, double boatBonus, boolean onBoat) {
        return rareChance(finds, boatBonus, onBoat, 1);
    }

    /** The same, times multiplier (a fisher from Lv20: {@link FishSpec#FISHER_RARE_BONUS}; anyone else 1), at most 1. */
    public static double rareChance(double finds, double boatBonus, boolean onBoat, double multiplier) {
        return Math.clamp((finds + (onBoat ? Math.max(0, boatBonus) : 0)) * Math.max(0, multiplier), 0, 1);
    }

    // ------------------------------------------------------------------------------------------
    // Fish caught before 2026-11 still carry their size: turned into the plain fish, the size's worth paid once
    // ------------------------------------------------------------------------------------------

    /** What one tidy-up turned and owes: fish = how many, owed = G (not rounded). */
    private static final class Tally {
        int fish;
        double owed;
    }

    /** The plain fish for a sized stack (the same stack otherwise), adding what it owes to t. */
    private ItemStack plain(ItemStack it, Tally t) {
        if (!Items.sized(it)) return it;
        ItemStack plain = Items.stripSize(it);
        if (plain == it) return it;
        double base = 0;
        if (!Items.trial(it) && !Items.shopBought(it) && eco.shop() != null) {
            var e = eco.shop().catalog().sell().get(dev.stsv.economy.shop.ShopCatalog.norm(Items.key(it)));
            if (e != null) base = e.base();
        }
        t.fish += it.getAmount();
        t.owed += OldSizes.refund(Items.oldSizeFactor(it), base, it.getAmount());
        return plain;
    }

    /** Every sized stack of these inventories replaced in place by its plain fish; p is paid and told (once for all of them). */
    public void tidy(Player p, org.bukkit.inventory.Inventory... invs) {
        Tally t = new Tally();
        for (org.bukkit.inventory.Inventory inv : invs) {
            if (inv == null) continue;
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) {
                if (!Items.sized(c[i])) continue;
                inv.setItem(i, plain(c[i], t));
            }
        }
        settle(p, t);
    }

    /** The same for a list of stacks (a 통발's catch), replaced in place. */
    public void tidy(Player p, List<ItemStack> items) {
        Tally t = new Tally();
        items.replaceAll(it -> plain(it, t));
        settle(p, t);
    }

    /** One stack on its way to p (out of the mailbox): the plain fish if it was sized, p paid and told. */
    public ItemStack tidy(Player p, ItemStack it) {
        Tally t = new Tally();
        ItemStack out = plain(it, t);
        settle(p, t);
        return out;
    }

    private void settle(Player p, Tally t) {
        if (t.fish <= 0) return;
        long pay = OldSizes.payout(t.owed);
        if (pay > 0) core.economy().move(null, p.getUniqueId(), pay, dev.stsv.core.economy.EconomyStore.Kind.FAUCET, "fish_size_refund",
                t.fish + " fish");
        p.sendMessage(pay > 0 ? Lang.msg("fishing.tidied-paid", "count", t.fish, "amount", dev.stsv.core.economy.EconomyModule.format(pay))
                : Lang.msg("fishing.tidied", "count", t.fish));
    }

    /** A real container the opener may take from (a chest, barrel, shulker box, a minecart's or a donkey's, their own ender chest): never a menu. */
    private static boolean storage(org.bukkit.inventory.Inventory top, Player p) {
        org.bukkit.inventory.InventoryHolder h = top.getHolder(false);
        if (top.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) return h == p;
        if (h instanceof Player) return false;
        return h instanceof org.bukkit.block.Container || h instanceof org.bukkit.block.DoubleChest || h instanceof org.bukkit.entity.Entity;
    }

    @EventHandler
    public void onJoinTidy(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) tidy(p, p.getInventory(), p.getEnderChest());
        }, 40L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpenTidy(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        org.bukkit.inventory.Inventory top = e.getView().getTopInventory();
        tidy(p, p.getInventory(), storage(top, p) ? top : null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupTidy(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !Items.sized(e.getItem().getItemStack())) return;
        Bukkit.getScheduler().runTask(eco, () -> {   // once it is in the inventory
            if (p.isOnline()) tidy(p, p.getInventory());
        });
    }
}
