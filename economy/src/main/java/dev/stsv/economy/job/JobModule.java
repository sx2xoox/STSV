package dev.stsv.economy.job;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 직업: one job per person (change once a week for 1,000G), Lv1-3 with the cap set by the season week,
 * XP only from the job's own work (placed blocks give nothing), passives, and products only a job at a
 * level can craft. Cook and alchemist products carry a quality that lengthens their effects.
 */
public final class JobModule extends EcoModule {

    public static final NamespacedKey QUALITY = new NamespacedKey("economy", "quality");
    private static final NamespacedKey PLACED = new NamespacedKey("economy", "placed");

    private JobSpec spec;
    private JobStore store;
    private final Map<Location, Boolean> natural = new HashMap<>();
    private final Map<Location, UUID> brewers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lockWarned = new ConcurrentHashMap<>();

    public JobModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-jobs";
    }

    @Override
    protected void start() {
        store = new JobStore(core.db());
        store.init();
        load();
        every(200L, this::growCrops);
        every(1200L, store::flush);
        core.hud().line("eco-job", 20, p -> {
            if (!eco.getConfig().getBoolean("hud.job")) return null;
            JobStore.Row r = store.get(p.getUniqueId());
            if (r == null) return Lang.mm("job.hud-none");
            JobSpec.Job j = spec.jobs().get(r.job);
            return Lang.mm("job.hud", "color", j == null ? "white" : j.color(), "job", j == null ? r.job : j.name(), "level", level(p.getUniqueId()));
        });
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        store.flush();
        core.hud().removeLine("eco-job");
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = JobSpec.parse(eco.data("jobs.yml"), problems);
        for (String id : spec.products().keySet()) if (Items.create(id, 1).isEmpty()) problems.add("products: 없는 아이템 " + id);
        problems.forEach(p -> eco.getLogger().warning("[jobs.yml] " + p));
    }

    public JobSpec spec() {
        return spec;
    }

    public JobStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // Levels and XP
    // ------------------------------------------------------------------------------------------

    public String job(UUID player) {
        return store.job(player);
    }

    /** 0 = no job. */
    public int level(UUID player) {
        JobStore.Row r = store.get(player);
        if (r == null) return 0;
        return Math.min(eco.season().levelCap(), spec.levelFor(r.xp));
    }

    /** The passive value of a job for a player (0 if they do not have that job). 풍년 doubles farmer/fisher ones. */
    public double passive(UUID player, String job, String key) {
        if (!job.equals(job(player))) return 0;
        double v = spec.jobs().get(job).passive(key, level(player));
        if ((job.equals("farmer") || job.equals("fisher")) && eco.season().isActive("harvest")) {
            v *= eco.season().active("harvest").getFirst().number("multiplier", 2);
        }
        return v;
    }

    public void addXp(UUID player, String job, double xp) {
        if (xp <= 0 || !job.equals(job(player))) return;
        JobStore.Row r = store.get(player);
        int before = level(player);
        double[] g = spec.gain(r.xp, xp, eco.season().levelCap());
        r.xp = g[0];
        r.mastery += g[1];
        store.touch(r);
        int after = level(player);
        Player p = Bukkit.getPlayer(player);
        if (p != null && after > before) {
            JobSpec.Job j = spec.jobs().get(job);
            p.showTitle(net.kyori.adventure.title.Title.title(Lang.mm("job.levelup-title", "level", after),
                    Lang.mm("job.levelup-subtitle", "color", j.color(), "job", j.name(), "level", after)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
            eco.season().broadcast(Lang.msg("job.levelup-broadcast", "player", p.getName(), "color", j.color(), "job", j.name(), "level", after));
        }
    }

    /** XP for doing something, by the action table of the player's job. */
    private void reward(Player p, String action, String thing, double times) {
        String job = job(p.getUniqueId());
        if (job == null) return;
        double xp = spec.jobs().get(job).xp(action, thing) * times;
        if (xp > 0) addXp(p.getUniqueId(), job, xp);
    }

    /** May this player make this product? */
    public boolean can(UUID player, JobSpec.Product p) {
        return p.job().equals(job(player)) && level(player) >= p.level();
    }

    // ------------------------------------------------------------------------------------------
    // Choosing a job
    // ------------------------------------------------------------------------------------------

    /** Returns null on success, else the message key of the refusal. */
    public String choose(Player p, String jobId) {
        JobSpec.Job j = spec.jobs().get(jobId);
        if (j == null) return "job.no-such";
        JobStore.Row r = store.get(p.getUniqueId());
        if (r != null && r.job.equals(jobId)) return "job.same";
        if (r != null) {
            long wait = r.changed + spec.changeDays() * 86_400_000L - now();
            if (wait > 0 && !p.hasPermission("stsv.economy.admin")) return "job.too-soon";
            if (!core.economy().withdraw(p.getUniqueId(), spec.changeCost(), "job_change")) return "job.not-enough";
        }
        store.choose(p.getUniqueId(), jobId, now());
        p.sendMessage(Lang.msg("job.chosen", "color", j.color(), "job", j.name()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        return null;
    }

    public void open(Player p) {
        core.menus().open(p, "economy:jobs", new JobsMenu());
    }

    private final class JobsMenu implements MenuHandler {
        private final List<String> ids = new ArrayList<>(spec.jobs().keySet());
        private String armed;
        private long armedAt;

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            JobStore.Row r = store.get(u);
            JobSpec.Job j = r == null ? null : spec.jobs().get(r.job);
            int lv = level(u);
            return Map.of("job", j == null ? Lang.raw("job.none") : "<" + j.color() + ">" + j.name(),
                    "level", String.valueOf(lv), "cap", String.valueOf(eco.season().levelCap()),
                    "xp", r == null ? "0" : String.format("%,.0f", r.xp),
                    "next", lv >= spec.maxLevel() ? "-" : String.format("%,.0f", spec.xpFor(lv + 1)),
                    "mastery", r == null ? "0" : String.format("%,.0f", r.mastery),
                    "cost", EconomyModule.format(spec.changeCost()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            String mine = job(v.player().getUniqueId());
            for (String id : ids) {
                JobSpec.Job j = spec.jobs().get(id);
                ItemStack it = Items.create(j.icon(), 1).orElse(new ItemStack(Material.PAPER));
                boolean isArmed = id.equals(armed) && now() - armedAt < 5000;
                it.editMeta(meta -> {
                    meta.itemName(Text.item("<" + j.color() + "><b>" + j.name()));
                    List<Component> lore = new ArrayList<>();
                    for (String line : j.description()) lore.add(Text.item("<gray>" + line));
                    lore.add(Component.empty());
                    lore.addAll(Lang.items(id.equals(mine) ? "job.lore-current" : isArmed ? "job.lore-confirm" : mine == null ? "job.lore-pick" : "job.lore-change",
                            "cost", EconomyModule.format(spec.changeCost())));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(id.equals(mine) || isArmed);
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= ids.size()) return;
            String id = ids.get(index);
            Player p = v.player();
            if (id.equals(job(p.getUniqueId()))) return;
            if (!id.equals(armed) || now() - armedAt > 5000) {
                armed = id;
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = null;
            String refused = choose(p, id);
            if (refused != null) {
                JobStore.Row r = store.get(p.getUniqueId());
                long wait = r == null ? 0 : r.changed + spec.changeDays() * 86_400_000L - now();
                HudModule.flash(p, Lang.mm(refused, "cost", EconomyModule.format(spec.changeCost()), "left", dev.stsv.economy.season.SeasonModule.hoursText(wait)));
            }
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Placed blocks (no XP or bonus for blocks a player put down)
    // ------------------------------------------------------------------------------------------

    private static boolean tracked(Material m) {
        return m.name().endsWith("_ORE") || m == Material.MELON || m == Material.PUMPKIN
                || m == Material.ANCIENT_DEBRIS || m == Material.AMETHYST_CLUSTER;
    }

    private static int pack(Block b) {
        return (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8);
    }

    private static boolean placed(Block b) {
        int[] arr = b.getChunk().getPersistentDataContainer().get(PLACED, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return false;
        int k = pack(b);
        for (int v : arr) if (v == k) return true;
        return false;
    }

    private static void mark(Block b, boolean on) {
        Chunk c = b.getChunk();
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        int[] arr = pdc.getOrDefault(PLACED, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int k = pack(b);
        int[] next = on ? (Arrays.stream(arr).anyMatch(v -> v == k) ? arr : Arrays.copyOf(arr, arr.length + 1))
                : Arrays.stream(arr).filter(v -> v != k).toArray();
        if (on && next != arr) next[next.length - 1] = k;
        if (next.length == 0) pdc.remove(PLACED);
        else pdc.set(PLACED, PersistentDataType.INTEGER_ARRAY, next);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (tracked(e.getBlockPlaced().getType())) mark(e.getBlockPlaced(), true);
    }

    /** A pushed placed ore stays "placed" at its new spot (no XP loop by piston). */
    private void movePlaced(List<Block> moved, org.bukkit.block.BlockFace dir) {
        List<Block> marked = new ArrayList<>();
        for (Block b : moved) if (tracked(b.getType()) && placed(b)) marked.add(b);
        if (marked.isEmpty()) return;
        for (Block b : marked) mark(b, false);
        for (Block b : marked) mark(b.getRelative(dir), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPush(org.bukkit.event.block.BlockPistonExtendEvent e) {
        movePlaced(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPull(org.bukkit.event.block.BlockPistonRetractEvent e) {
        movePlaced(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlast(org.bukkit.event.entity.EntityExplodeEvent e) {
        for (Block b : e.blockList()) if (tracked(b.getType()) && placed(b)) mark(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBlast(org.bukkit.event.block.BlockExplodeEvent e) {
        for (Block b : e.blockList()) if (tracked(b.getType()) && placed(b)) mark(b, false);
    }

    /** Crafter blocks have no player: job products can only be made by hand (or in job machines). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (spec.products().containsKey(Items.key(e.getResult()))) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------
    // Miner and farmer: breaking
    // ------------------------------------------------------------------------------------------

    private static boolean mature(BlockState s) {
        return s.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() && Tag.CROPS.isTagged(s.getType())
                || s.getType() == Material.NETHER_WART && s.getBlockData() instanceof Ageable w && w.getAge() >= w.getMaximumAge()
                || s.getType() == Material.COCOA && s.getBlockData() instanceof Ageable c && c.getAge() >= c.getMaximumAge();
    }

    private static Material produce(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case NETHER_WART -> Material.NETHER_WART;
            case COCOA -> Material.COCOA_BEANS;
            case MELON -> Material.MELON_SLICE;
            case PUMPKIN -> Material.PUMPKIN;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        boolean wasPlaced = tracked(m) && placed(b);
        if (wasPlaced) mark(b, false);
        Player p = e.getPlayer();
        String key = m.getKey().asString();
        if (!wasPlaced) {
            if (tracked(m) && m != Material.MELON && m != Material.PUMPKIN) reward(p, "break", key, 1);
            else if (mature(b.getState()) || m == Material.MELON || m == Material.PUMPKIN) reward(p, "harvest", key, 1);
        }
        natural.put(b.getLocation(), !wasPlaced);
        Bukkit.getScheduler().runTask(eco, () -> natural.remove(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        BlockState s = e.getBlockState();
        Material m = s.getType();
        if (!Boolean.TRUE.equals(natural.get(e.getBlock().getLocation())) || e.getItems().isEmpty()) return;
        UUID u = p.getUniqueId();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String key = m.getKey().asString();
        if ("miner".equals(job(u)) && tracked(m) && m != Material.MELON && m != Material.PUMPKIN) {
            boolean silk = p.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH);
            if (!silk && r.nextDouble() < passive(u, "miner", "extra")) {
                Item first = e.getItems().getFirst();
                ItemStack it = first.getItemStack();
                it.setAmount(it.getAmount() + 1);
                first.setItemStack(it);
                p.spawnParticle(Particle.HAPPY_VILLAGER, e.getBlock().getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3);
            }
            List<String> gems = spec.jobs().get("miner").drops(key);
            if (!silk && !gems.isEmpty() && r.nextDouble() < passive(u, "miner", "gem")) {
                Items.create(gems.get(r.nextInt(gems.size())), 1).ifPresent(g -> {
                    e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), g);
                    HudModule.flash(p, Lang.mm("job.found", "item", Items.plainName(g)));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
                });
            }
        }
        if ("farmer".equals(job(u)) && (mature(s) || m == Material.MELON || m == Material.PUMPKIN)) {
            Material crop = produce(m);
            int bonus = (int) Math.round(passive(u, "farmer", "bonus"));
            if (crop != null && bonus > 0) {
                e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.3, 0.5), new ItemStack(crop, bonus));
            }
            List<String> premium = spec.jobs().get("farmer").drops(key);
            if (!premium.isEmpty() && r.nextDouble() < passive(u, "farmer", "premium")) {
                Items.create(premium.get(r.nextInt(premium.size())), 1).ifPresent(g -> {
                    e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.3, 0.5), g);
                    HudModule.flash(p, Lang.mm("job.found", "item", Items.plainName(g)));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
                });
            }
        }
    }

    /** 성장 가속: crops around online farmers grow a step now and then. */
    private void growCrops() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            double chance = passive(p.getUniqueId(), "farmer", "growth");
            if (chance <= 0) continue;
            Block c = p.getLocation().getBlock();
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -2; dy <= 1; dy++) {
                Block b = c.getRelative(dx, dy, dz);
                if (!(b.getBlockData() instanceof Ageable a) || !Tag.CROPS.isTagged(b.getType()) || a.getAge() >= a.getMaximumAge()) continue;
                if (r.nextDouble() >= chance) continue;
                a.setAge(a.getAge() + 1);
                b.setBlockData(a, false);
                if (r.nextInt(4) == 0) b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 2, 0.2, 0.1, 0.2);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p) reward(p, "breed", e.getEntityType().getKey().asString(), 1);
    }

    @EventHandler
    public void onAnimalDeath(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null || !"farmer".equals(job(p.getUniqueId()))) return;
        List<String> drops = spec.jobs().get("farmer").drops(e.getEntityType().getKey().asString());
        if (drops.isEmpty() || ThreadLocalRandom.current().nextDouble() >= passive(p.getUniqueId(), "farmer", "animal")) return;
        Items.create(drops.get(ThreadLocalRandom.current().nextInt(drops.size())), 1).ifPresent(g -> {
            e.getDrops().add(g);
            HudModule.flash(p, Lang.mm("job.found", "item", Items.plainName(g)));
        });
    }

    // ------------------------------------------------------------------------------------------
    // Custom crops: who may plant them, and the farmer's touch at harvest
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCrop(dev.stsv.core.crop.CropEvent e) {
        Player p = e.player();
        UUID u = p.getUniqueId();
        switch (e.action()) {
            case PLANT -> {
                String job = e.crop().extra().get("job");
                if (job == null || p.hasPermission("stsv.economy.admin")) return;
                int need = Integer.parseInt(e.crop().extra().getOrDefault("level", "1"));
                if (!job.equals(job(u)) || level(u) < need) {
                    e.setCancelled(true);
                    JobSpec.Job j = spec.jobs().get(job);
                    HudModule.flash(p, Lang.mm("job.locked-plant", "color", j == null ? "white" : j.color(), "job", j == null ? job : j.name(), "level", need));
                }
            }
            case HARVEST -> {
                reward(p, "harvest", e.crop().id(), 1);
                if (!"farmer".equals(job(u))) return;
                e.qualityBonus(e.qualityBonus() + passive(u, "farmer", "crop-quality"));
                if (ThreadLocalRandom.current().nextDouble() < passive(u, "farmer", "crop-bonus")) {
                    for (dev.stsv.core.crop.CropSpec.Drop d : e.crop().harvest()) {
                        if (!d.quality()) continue;
                        for (ItemStack it : e.drops()) {
                            if (Items.key(it).equals(d.item())) {
                                it.setAmount(it.getAmount() + 1);
                                return;
                            }
                        }
                    }
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fisher
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (!"fisher".equals(job(u))) return;
        reward(p, "catch", Items.key(caught.getItemStack()), 1);
        double chance = passive(u, "fisher", "rare");
        ItemStack off = p.getInventory().getItemInOffHand();
        boolean bait = Items.key(off).equals("economy:bait");
        if (bait) {
            chance += spec.jobs().get("fisher").passive("bait", Math.max(1, level(u)));
            off.subtract(1);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= chance) return;
        // higher levels can land the rarer tiers
        int lv = Math.max(1, level(u));
        int tier = 1;
        for (int t = lv; t >= 2; t--) if (r.nextDouble() < 0.35) { tier = t; break; }
        List<String> pool = spec.jobs().get("fisher").drops("tier" + tier);
        if (pool.isEmpty()) return;
        Optional<ItemStack> rare = Items.create(pool.get(r.nextInt(pool.size())), 1);
        if (rare.isEmpty()) return;
        caught.setItemStack(rare.get());
        reward(p, "rare", Items.key(rare.get()), 1);
        HudModule.flash(p, Lang.mm("job.rare-catch", "item", Items.plainName(rare.get())));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
    }

    // ------------------------------------------------------------------------------------------
    // Crafting: job-only products, quality, blacksmith savings
    // ------------------------------------------------------------------------------------------

    /** Applies a product's enchantments and (for cook/alchemist goods) the maker's quality. */
    public ItemStack decorate(ItemStack item, UUID maker) {
        JobSpec.Product pr = spec.products().get(Items.key(item));
        if (pr == null) return item;
        ItemStack out = item.clone();
        out.editMeta(meta -> {
            for (Map.Entry<String, Integer> en : pr.enchants().entrySet()) {
                Enchantment ench = io.papermc.paper.registry.RegistryAccess.registryAccess()
                        .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(en.getKey()));
                if (ench != null) meta.addEnchant(ench, en.getValue(), true);
            }
            if (pr.quality() && maker != null) {
                double q = passive(maker, pr.job(), "quality");
                if (q > 0) {
                    meta.getPersistentDataContainer().set(QUALITY, PersistentDataType.DOUBLE, q);
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    JobSpec.Job j = spec.jobs().get(pr.job());
                    lore.add(Lang.item("job.quality-lore", "pct", Math.round(q * 100), "color", j.color(), "job", j.name(), "level", level(maker)));
                    meta.lore(lore);
                }
            }
        });
        return out;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        if (result == null || result.isEmpty() || !(e.getView().getPlayer() instanceof Player p)) return;
        JobSpec.Product pr = spec.products().get(Items.key(result));
        if (pr == null) return;
        if (!can(p.getUniqueId(), pr)) {
            e.getInventory().setResult(null);
            Long last = lockWarned.get(p.getUniqueId());
            if (last == null || now() - last > 3000) {
                lockWarned.put(p.getUniqueId(), now());
                JobSpec.Job j = spec.jobs().get(pr.job());
                HudModule.flash(p, Lang.mm("job.locked", "color", j.color(), "job", j.name(), "level", pr.level()));
            }
            return;
        }
        e.getInventory().setResult(decorate(result, p.getUniqueId()));
    }

    private static boolean gear(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SWORD")
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || m == Material.FISHING_ROD || m == Material.SHIELD || m == Material.BOW || m == Material.CROSSBOW;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        String key = Items.key(e.getInventory().getResult() == null ? result : e.getInventory().getResult());
        int times = 1;
        if (e.isShiftClick()) {
            times = Integer.MAX_VALUE;
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.isEmpty()) times = Math.min(times, it.getAmount());
            if (times == Integer.MAX_VALUE) times = 1;
        }
        JobSpec.Product pr = spec.products().get(key);
        if (pr != null && can(p.getUniqueId(), pr)) addXp(p.getUniqueId(), pr.job(), pr.xp() * times);
        else reward(p, "craft", key, times);
        // 대장장이: sometimes one ingredient comes back
        if ("blacksmith".equals(job(p.getUniqueId())) && (gear(result.getType()) || (pr != null && pr.job().equals("blacksmith")))) {
            double chance = passive(p.getUniqueId(), "blacksmith", "save");
            List<ItemStack> used = new ArrayList<>();
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.isEmpty()) used.add(it.asOne());
            if (!used.isEmpty() && ThreadLocalRandom.current().nextDouble() < chance) {
                ItemStack back = used.get(ThreadLocalRandom.current().nextInt(used.size()));
                Bukkit.getScheduler().runTask(eco, () -> {
                    for (ItemStack left : p.getInventory().addItem(back).values()) p.getWorld().dropItem(p.getLocation(), left);
                    HudModule.flash(p, Lang.mm("job.saved", "item", Items.plainName(back)));
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && e.getInventory().getResult() != null) reward(p, "smith", Items.key(e.getInventory().getResult()), 1);
    }

    @EventHandler
    public void onSmelt(FurnaceExtractEvent e) {
        reward(e.getPlayer(), "smelt", e.getItemType().getKey().asString(), e.getItemAmount());
    }

    // ------------------------------------------------------------------------------------------
    // Alchemist: brewing quality; anyone: quality lengthens effects when eaten or drunk
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onOpenStand(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.BREWING && e.getInventory().getLocation() != null) {
            brewers.put(e.getInventory().getLocation().toBlockLocation(), e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        UUID u = brewers.get(e.getBlock().getLocation().toBlockLocation());
        if (u == null || !"alchemist".equals(job(u))) return;
        double q = passive(u, "alchemist", "quality");
        int count = 0;
        for (ItemStack it : e.getResults()) {
            if (it == null || it.isEmpty() || !(it.getItemMeta() instanceof PotionMeta)) continue;
            count++;
            if (q <= 0) continue;
            it.editMeta(meta -> {
                meta.getPersistentDataContainer().set(QUALITY, PersistentDataType.DOUBLE, q);
                List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                JobSpec.Job j = spec.jobs().get("alchemist");
                lore.add(Lang.item("job.quality-lore", "pct", Math.round(q * 100), "color", j.color(), "job", j.name(), "level", level(u)));
                meta.lore(lore);
            });
        }
        if (count > 0) addXp(u, "alchemist", spec.jobs().get("alchemist").xp("brew", "*") * count);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (!it.hasItemMeta()) return;
        Double q = it.getItemMeta().getPersistentDataContainer().get(QUALITY, PersistentDataType.DOUBLE);
        if (q == null || q <= 0) return;
        List<PotionEffect> effects = new ArrayList<>();
        if (it.getItemMeta() instanceof PotionMeta pm) {
            if (pm.getBasePotionType() != null) effects.addAll(pm.getBasePotionType().getPotionEffects());
            effects.addAll(pm.getCustomEffects());
        }
        Optional<CustomItem> custom = core.content().itemOf(it);
        if (custom.isPresent() && custom.get().food() != null) {
            for (CustomItem.FoodEffect fx : custom.get().food().effects()) effects.add(fx.effect());
        }
        Player p = e.getPlayer();
        double bonus = q;
        Bukkit.getScheduler().runTask(eco, () -> {
            for (PotionEffect fx : effects) {
                PotionEffect now = p.getPotionEffect(fx.getType());
                if (now == null || now.getAmplifier() != fx.getAmplifier() || fx.getDuration() <= 1) continue;
                p.addPotionEffect(new PotionEffect(fx.getType(), (int) Math.round(fx.getDuration() * (1 + bonus)), fx.getAmplifier(),
                        fx.isAmbient(), fx.hasParticles(), fx.hasIcon()));
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Fertilizer: anyone can use it, the alchemist makes it
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        String key = Items.key(hand);
        int radius = switch (key) {
            case "economy:fertilizer" -> 1;
            case "economy:super_fertilizer" -> 2;
            default -> -1;
        };
        if (radius < 0) return;
        e.setCancelled(true);
        Block c = e.getClickedBlock();
        int grown = 0;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) for (int dy = -1; dy <= 1; dy++) {
            Block b = c.getRelative(dx, dy, dz);
            if (b.getBlockData() instanceof Ageable a && (Tag.CROPS.isTagged(b.getType()) || b.getType() == Material.NETHER_WART) && a.getAge() < a.getMaximumAge()) {
                a.setAge(a.getMaximumAge());
                b.setBlockData(a);
                b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 5, 0.3, 0.2, 0.3);
                grown++;
            }
        }
        if (grown == 0) {
            HudModule.flash(e.getPlayer(), Lang.mm("job.fertilizer-nothing"));
            return;
        }
        hand.subtract(1);
        e.getPlayer().playSound(c.getLocation(), Sound.ITEM_BONE_MEAL_USE, 1f, 1f);
    }

    // ------------------------------------------------------------------------------------------

    public List<Component> rankingLines(String job) {
        List<Component> out = new ArrayList<>();
        JobSpec.Job j = job == null ? null : spec.jobs().get(job);
        out.add(Lang.msg("job.ranking-header", "job", j == null ? Lang.raw("job.all") : "<" + j.color() + ">" + j.name()));
        int rank = 1;
        for (JobStore.Row r : store.ranking(job, 10)) {
            JobSpec.Job rj = spec.jobs().get(r.job);
            out.add(Lang.mm("job.ranking-line", "rank", rank++, "player", Players.name(r.player), "color", rj == null ? "white" : rj.color(),
                    "job", rj == null ? r.job : rj.name(), "level", Math.min(eco.season().levelCap(), spec.levelFor(r.xp)),
                    "mastery", String.format("%,.0f", r.mastery), "xp", String.format("%,.0f", r.xp)));
        }
        return out;
    }

    /** Admin: set XP directly (testing). */
    public void setXp(UUID player, double xp) {
        JobStore.Row r = store.get(player);
        if (r == null) return;
        r.xp = Math.max(0, xp);
        store.touch(r);
    }
}
