package dev.stsv.economy.fishing;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.calendar.WeeklyListener;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
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
 * finds chance, more from a boat); rare and legend fish carry their size (the shop pays 0.7 - 1.5x by it). Every catch
 * goes in the /도감 codex; a full habitat gives a title, all fish [강태공]. The week's biggest catch is announced.
 * Treasure and junk stay vanilla.
 */
public final class FishingModule extends EcoModule implements WeeklyListener {

    public static final NamespacedKey SIZE = new NamespacedKey("economy", "fish_size");
    public static final NamespacedKey FACTOR = new NamespacedKey("economy", "fish_factor");
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
        eco.calendar().onWeekly(this);
        eco.onReset(() -> {
            store.reset();
            eco.kv().remove("fish.week");
        });
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
     * What a boat's net brings up (the core asks every net.seconds on average while it trawls): crates of catch of the sea
     * it is in ({@link NetSpec}: by how far the nearest harbour is, never beyond the sea that boat's net is made for), more
     * of them with more people aboard, each marked with the boat that hauled it ({@link #VESSEL}: only that boat's hold sells
     * it, at a harbour). A boat whose net hauls no crates, or a world without a harbour: one fish as before
     * ({@link #netCatch}). null = not the sea.
     */
    private ItemStack netHaul(Player p, Block water, dev.stsv.core.vehicle.NetHaul.Boat boat) {
        double far = net.enabled() && eco.docks() != null ? harbourDistance(water) : -1;
        NetSpec.Zone zone = far < 0 ? null : net.zoneFor(boat.item(), far);
        if (zone == null) return netCatch(p, water);
        boolean underground = water.getLightFromSky() == 0 && water.getY() < water.getWorld().getSeaLevel() - 8;
        if (!FishSpec.SALT.contains(FishSpec.habitat(water.getBiome().getKey().asString(), underground))) return null;
        int count = net.crates(boat.aboard(), rnd.nextDouble());
        ItemStack it = Items.create(zone.item(), count).orElse(null);
        if (it == null) return null;
        it.editMeta(m -> m.getPersistentDataContainer().set(VESSEL, PersistentDataType.LONG, boat.id()));
        return it;
    }

    /** The boat a crate of catch was hauled by, or 0 (not a crate, or one that was handed out). */
    public static long vessel(ItemStack crate) {
        if (crate == null || crate.isEmpty() || !crate.hasItemMeta()) return 0;
        Long id = crate.getPersistentDataContainer().get(VESSEL, PersistentDataType.LONG);
        return id == null ? 0 : id;
    }

    /**
     * One fish of the sea at this block for a driver trawling there (the core asks now and then, far less often than a rod
     * bites): a common or uncommon fish of that water at any time, as a 통발 catches them (never rare, legend, a pearl or a
     * special). A fisher gets their usual touch: more uncommons (jobs.yml trap-uncommon), two of the fish the job doubles at
     * its level, and the job's trap experience. null = not the sea (rivers, lakes, swamps, caves catch nothing).
     */
    private ItemStack netCatch(Player p, Block water) {
        boolean underground = water.getLightFromSky() == 0 && water.getY() < water.getWorld().getSeaLevel() - 8;
        String habitat = FishSpec.habitat(water.getBiome().getKey().asString(), underground);
        if (!FishSpec.SALT.contains(habitat)) return null;
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
            if (cut <= 0) return;
            ItemStack rod = p.getInventory().getItem(e.getHand() == null ? org.bukkit.inventory.EquipmentSlot.HAND : e.getHand());
            int lure = rod == null ? 0 : rod.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LURE);
            int[] wait = FishSpec.waitTicks(lure, cut);
            e.getHook().setApplyLure(false);   // Lure is in the range already
            e.getHook().setWaitTime(wait[0], wait[1]);
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
        double rare = rareChance(eco.jobs().findChance(u, "fisher"), eco.jobs().effect(u, "fisher", "boat-rare"), onBoat);
        FishSpec.Rarity tier = spec.tier(rare, rnd);
        FishSpec.Species s;
        if (force != null) {
            // a test bite: that fish wherever and whenever, or that rarity from this water
            if (force.left() <= 1) forced.remove(u);
            else forced.put(u, new Forced(force.rarity(), force.species(), force.left() - 1));
            if (force.species() != null) s = spec.species().get(force.species());
            else if (force.rarity().equals("pearl")) s = pearl();
            else s = spec.pick(habitat, m, spec.rarities().get(force.rarity()), rnd);
        } else if (tier.index() == 2 && FishSpec.SALT.contains(habitat) && rnd.nextDouble() < spec.pearl()) {
            s = pearl();
        } else {
            s = spec.pick(habitat, m, tier, rnd);
        }
        if (s == null) return;
        e.setCancelled(true);
        caught.remove();
        e.getHook().remove();
        EquipmentSlot hand = e.getHand() == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        land(p, s, hand);
    }

    /** A pearl comes up as a rare bite of the sea. */
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

    /** The fish on the line comes up: into the inventory, with what vanilla gives for a catch, the codex and the week's record. */
    private void land(Player p, FishSpec.Species s, EquipmentSlot hand) {
        UUID u = p.getUniqueId();
        FishSpec.Rarity r = spec.rarities().get(s.rarity());
        boolean fisher = "fisher".equals(eco.jobs().job(u));
        int level = fisher ? eco.jobs().level(u) : 0;
        boolean pearl = s.id().equals("pearl");
        double size = pearl ? 0 : FishSpec.size(s, rnd.nextDouble(), fisher && level >= 20 ? FishSpec.FISHER_SIZE_BONUS : 0);
        JobSpec.Job fj = eco.jobs().spec().jobs().get("fisher");
        int count = fisher && fj != null && fj.finds() != null && level >= fj.finds().doubleLevel() && fj.finds().doubleItems().contains(s.item()) ? 2 : 1;
        ItemStack it = Items.create(s.item(), count).orElse(new ItemStack(Material.COD, count));
        double factor = FishSpec.priceFactor(s, size);
        if (!pearl && r.index() >= 2) markSize(it, size, factor);   // rare and legend fish keep their size (they do not stack, the shop pays by it)
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        // what vanilla would have given: experience, rod wear, the statistic
        p.giveExp(1 + rnd.nextInt(6), true);   // applyMending: like the orb a vanilla catch drops, Mending gear takes it first
        ItemStack rod = p.getInventory().getItem(hand);
        if (rod.getType() == Material.FISHING_ROD) p.getInventory().setItem(hand, rod.damage(1, p));
        p.incrementStatistic(Statistic.FISH_CAUGHT);
        eco.jobs().rewardAction(p, "catch", s.item(), 1);
        if (r.index() >= 2) eco.jobs().rewardAction(p, "find", s.item(), 1);
        eco.jobs().rollSpecials(p, "catch", null);
        // the title: the fish's picture and name, its size below
        int idx = order.indexOf(s.id());
        String head = (idx >= 0 ? FishSpec.icon(idx) + " " : "") + "<" + r.color() + ">" + s.name();
        String sub = pearl ? "" : Lang.raw("fishing.result", "size", String.format("%.1f", size));
        p.showTitle(Title.title(Text.mm(head), Text.mm(sub), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2500), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f + r.index() * 0.1f);
        p.sendMessage(Lang.msg(pearl ? "fishing.caught-pearl" : "fishing.caught",
                "color", r.color(), "fish", s.name(), "size", String.format("%.1f", size), "count", count > 1 ? " ×" + count : ""));
        if (pearl) return;
        boolean first = store.record(u, s.id(), size, now());
        // the codex takes one of the fish to register it (/도감)
        if (first && !eco.codex().registered(u).contains(dev.stsv.economy.shop.ShopCatalog.norm(s.item()))) {
            p.sendMessage(Lang.msg("fishing.new-species", "fish", s.name()));
        }
        weeklyRecord(p, s, size);
    }

    /** A rare or legend fish's size on its item: the lore line and the size and price factor the shop reads. */
    public static void markSize(ItemStack it, double size, double factor) {
        it.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Lang.item("fishing.size-lore", "size", String.format("%.1f", size), "pct", Math.round(factor * 100)));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(SIZE, PersistentDataType.DOUBLE, size);
            meta.getPersistentDataContainer().set(FACTOR, PersistentDataType.DOUBLE, factor);
        });
    }

    /**
     * The chance of a rare or legend bite: the job's finds chance (anyone else: the outsider chance) and, while riding a boat
     * (통통배), the job's boat-rare on top (0 for anyone but a fisher: it is the fisher's effect).
     */
    public static double rareChance(double finds, double boatBonus, boolean onBoat) {
        return Math.clamp(finds + (onBoat ? Math.max(0, boatBonus) : 0), 0, 1);
    }

    /** kv fish.week = "week;player;species;size" of the week's biggest catch (by length). */
    private void weeklyRecord(Player p, FishSpec.Species s, double size) {
        int week = eco.calendar().week();
        String[] cur = eco.kv().get("fish.week", "").split(";");
        double best = cur.length == 4 && cur[0].equals(String.valueOf(week)) ? Double.parseDouble(cur[3]) : 0;
        if (size <= best) return;
        eco.kv().set("fish.week", week + ";" + p.getUniqueId() + ";" + s.id() + ";" + size);
        if (best > 0) Bukkit.broadcast(Lang.msg("fishing.week-record", "player", Names.mini(p), "fish", s.name(), "size", String.format("%.1f", size)));
    }

    @Override
    public void newWeek(int week, long weekStart) {
        String[] cur = eco.kv().get("fish.week", "").split(";");
        if (cur.length != 4 || !cur[0].equals(String.valueOf(week - 1))) return;
        FishSpec.Species s = spec.species().get(cur[2]);
        Bukkit.broadcast(Lang.msg("fishing.week-winner", "player", Players.name(UUID.fromString(cur[1])),
                "fish", s == null ? cur[2] : s.name(), "size", cur[3]));
    }
}
