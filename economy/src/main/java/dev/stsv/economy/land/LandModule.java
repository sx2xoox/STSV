package dev.stsv.economy.land;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.season.ActiveEvent;
import dev.stsv.economy.util.Players;
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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 땅: claims protect a rectangle (full height). 400㎡ are free; more area is bought at the week's price
 * (1G, 2G, 3G, 4G per ㎡: early investment pays). Property tax every day at 05:00 (area x 0.05G, double
 * above 2,500㎡); three days unpaid and the newest claim is released. Server claims protect the town.
 */
public final class LandModule extends EcoModule implements DailyListener {

    private LandStore store;
    private LandRules rules;
    private final Map<UUID, int[]> corners = new ConcurrentHashMap<>();   // [x1, z1, x2, z2] (Integer.MIN_VALUE = unset)
    private final Map<UUID, String> cornerWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Long> where = new ConcurrentHashMap<>();
    private final Map<UUID, Long> warned = new ConcurrentHashMap<>();
    private final Set<UUID> showing = ConcurrentHashMap.newKeySet();

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
        store.init();
        rules = LandRules.parse(eco.data("land.yml"));
        eco.season().onDaily(this);
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

    public Claim at(Location l) {
        return l.getWorld() == null ? null : store.index().at(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    public long quota(UUID player) {
        return rules.freeArea() + store.account(player).bought;
    }

    public long used(UUID player) {
        return store.index().area(player);
    }

    /** May this player change things here? */
    public boolean allowed(Player p, Location l) {
        Claim c = at(l);
        return c == null || c.allows(p.getUniqueId()) || p.hasPermission("stsv.economy.land.bypass");
    }

    // ------------------------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------------------------

    public void corner(Player p, int which) {
        int[] c = corners.computeIfAbsent(p.getUniqueId(), k -> new int[]{Integer.MIN_VALUE, 0, Integer.MIN_VALUE, 0});
        if (!p.getWorld().getName().equals(cornerWorld.put(p.getUniqueId(), p.getWorld().getName()))) {
            c[0] = c[2] = Integer.MIN_VALUE;
        }
        c[which * 2] = p.getLocation().getBlockX();
        c[which * 2 + 1] = p.getLocation().getBlockZ();
        p.sendMessage(Lang.msg("land.corner", "n", which + 1, "x", c[which * 2], "z", c[which * 2 + 1]));
        if (c[0] != Integer.MIN_VALUE && c[2] != Integer.MIN_VALUE) {
            long area = (long) (Math.abs(c[2] - c[0]) + 1) * (Math.abs(c[3] - c[1]) + 1);
            p.sendMessage(Lang.msg("land.selection", "w", Math.abs(c[2] - c[0]) + 1, "d", Math.abs(c[3] - c[1]) + 1, "area", area,
                    "left", quota(p.getUniqueId()) - used(p.getUniqueId())));
            outline(p, p.getWorld().getName(), Math.min(c[0], c[2]), Math.min(c[1], c[3]), Math.max(c[0], c[2]), Math.max(c[1], c[3]), true);
        }
    }

    /** From the two corners, or a square of radius r around the player when r >= 0. */
    public void create(Player p, int radius, boolean server) {
        int x1, z1, x2, z2;
        if (radius >= 0) {
            x1 = p.getLocation().getBlockX() - radius;
            z1 = p.getLocation().getBlockZ() - radius;
            x2 = p.getLocation().getBlockX() + radius;
            z2 = p.getLocation().getBlockZ() + radius;
        } else {
            int[] c = corners.get(p.getUniqueId());
            if (c == null || c[0] == Integer.MIN_VALUE || c[2] == Integer.MIN_VALUE || !p.getWorld().getName().equals(cornerWorld.get(p.getUniqueId()))) {
                p.sendMessage(Lang.msg("land.no-selection"));
                return;
            }
            x1 = c[0];
            z1 = c[1];
            x2 = c[2];
            z2 = c[3];
        }
        Object made = tryCreate(p.getUniqueId(), p.getWorld().getName(), x1, z1, x2, z2, server);
        if (made instanceof String refused) {
            p.sendMessage(Lang.msg(refused, "min", rules.minSide(), "left", quota(p.getUniqueId()) - used(p.getUniqueId()),
                    "area", (long) (Math.abs(x2 - x1) + 1) * (Math.abs(z2 - z1) + 1), "max", rules.maxClaims()));
            return;
        }
        Claim c = (Claim) made;
        corners.remove(p.getUniqueId());
        p.sendMessage(Lang.msg(server ? "land.created-server" : "land.created", "area", c.area(),
                "left", quota(p.getUniqueId()) - used(p.getUniqueId())));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1f, 1f);
        outline(p, c.world, c.x1, c.z1, c.x2, c.z2, false);
    }

    /** The new claim, or the message key of the refusal. */
    public Object tryCreate(UUID owner, String world, int x1, int z1, int x2, int z2, boolean server) {
        int w = Math.abs(x2 - x1) + 1, d = Math.abs(z2 - z1) + 1;
        if (w < rules.minSide() || d < rules.minSide()) return "land.too-small";
        if (store.index().overlapping(world, x1, z1, x2, z2, null) != null) return "land.overlap";
        long area = (long) w * d;
        if (!server) {
            if (store.index().of(owner).size() >= rules.maxClaims()) return "land.too-many";
            if (used(owner) + area > quota(owner)) return "land.no-quota";
        }
        return store.create(server ? null : owner, world, x1, z1, x2, z2, now());
    }

    public void expand(Player p, int blocks) {
        Claim c = at(p.getLocation());
        if (c == null || !p.getUniqueId().equals(c.owner)) {
            p.sendMessage(Lang.msg("land.not-yours"));
            return;
        }
        BlockFace face = p.getFacing();
        int x1 = c.x1, z1 = c.z1, x2 = c.x2, z2 = c.z2;
        switch (face) {
            case NORTH -> z1 -= blocks;
            case SOUTH -> z2 += blocks;
            case WEST -> x1 -= blocks;
            case EAST -> x2 += blocks;
            default -> { }
        }
        long added = (long) (x2 - x1 + 1) * (z2 - z1 + 1) - c.area();
        if (blocks <= 0 || added <= 0) {
            p.sendMessage(Lang.msg("land.usage"));
            return;
        }
        if (used(p.getUniqueId()) + added > quota(p.getUniqueId())) {
            p.sendMessage(Lang.msg("land.no-quota", "area", added, "left", quota(p.getUniqueId()) - used(p.getUniqueId())));
            return;
        }
        if (store.index().overlapping(c.world, x1, z1, x2, z2, c) != null) {
            p.sendMessage(Lang.msg("land.overlap"));
            return;
        }
        store.resize(c, x1, z1, x2, z2);
        p.sendMessage(Lang.msg("land.expanded", "area", c.area(), "left", quota(p.getUniqueId()) - used(p.getUniqueId())));
        outline(p, c.world, c.x1, c.z1, c.x2, c.z2, false);
    }

    public void delete(Player p) {
        Claim c = at(p.getLocation());
        if (c == null || (!p.getUniqueId().equals(c.owner) && !p.hasPermission("stsv.economy.admin"))) {
            p.sendMessage(Lang.msg("land.not-yours"));
            return;
        }
        store.delete(c);
        p.sendMessage(Lang.msg("land.deleted", "area", c.area()));
    }

    /** Buys more area at the week's price. */
    public void buy(Player p, long area) {
        if (area <= 0) {
            p.sendMessage(Lang.msg("land.usage"));
            return;
        }
        long price = area * eco.season().landPrice();
        if (!core.economy().withdraw(p.getUniqueId(), price, "land_buy")) {
            p.sendMessage(Lang.msg("land.not-enough", "price", EconomyModule.format(price)));
            return;
        }
        LandStore.Account a = store.account(p.getUniqueId());
        a.bought += area;
        a.spent += price;
        store.save(p.getUniqueId());
        p.sendMessage(Lang.msg("land.bought", "area", area, "price", EconomyModule.format(price), "total", quota(p.getUniqueId())));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1f);
    }

    public void trust(Player p, String name, boolean add) {
        Claim c = at(p.getLocation());
        if (c == null || !p.getUniqueId().equals(c.owner)) {
            p.sendMessage(Lang.msg("land.not-yours"));
            return;
        }
        Players.find(name, found -> {
            if (found.isEmpty()) {
                p.sendMessage(Lang.msg("land.no-player"));
                return;
            }
            store.member(c, found.get(), add);
            p.sendMessage(Lang.msg(add ? "land.trusted" : "land.untrusted", "player", name));
        });
    }

    public void info(Player p) {
        UUID u = p.getUniqueId();
        LandStore.Account a = store.account(u);
        long area = used(u);
        p.sendMessage(Lang.msg("land.info", "used", area, "quota", quota(u), "free", rules.freeArea(), "bought", a.bought,
                "tax", EconomyModule.format(rules.tax(area, taxMultiplier())), "price", eco.season().landPrice(), "warnings", a.warnings, "warn-days", rules.warnDays()));
        for (Claim c : store.index().of(u)) {
            p.sendMessage(Lang.mm("land.info-line", "x1", c.x1, "z1", c.z1, "x2", c.x2, "z2", c.z2, "area", c.area(), "members", c.members.size()));
        }
        Claim here = at(p.getLocation());
        if (here != null) p.sendMessage(Lang.msg("land.here", "owner", here.server() ? Lang.raw("land.server") : Players.name(here.owner)));
    }

    public void show(Player p) {
        int n = 0;
        for (Claim c : store.index().all()) {
            if (!c.world.equals(p.getWorld().getName())) continue;
            double cx = (c.x1 + c.x2) / 2.0, cz = (c.z1 + c.z2) / 2.0;
            if (Math.hypot(cx - p.getX(), cz - p.getZ()) > 120) continue;
            outline(p, c.world, c.x1, c.z1, c.x2, c.z2, false);
            n++;
        }
        p.sendMessage(Lang.msg("land.showing", "count", n));
    }

    /** Particles along the edges for 10 seconds. */
    private void outline(Player p, String world, int x1, int z1, int x2, int z2, boolean selection) {
        if (!showing.add(p.getUniqueId())) showing.remove(p.getUniqueId());
        org.bukkit.Color color = selection ? org.bukkit.Color.fromRGB(80, 200, 255) : org.bukkit.Color.fromRGB(255, 210, 60);
        for (int t = 0; t < 10; t++) {
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (!p.isOnline() || !p.getWorld().getName().equals(world)) return;
                double y = p.getY() + 0.5;
                Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
                for (double x = x1; x <= x2 + 1; x += 1) {
                    p.spawnParticle(Particle.DUST, x, y, z1, 1, dust);
                    p.spawnParticle(Particle.DUST, x, y, z2 + 1, 1, dust);
                }
                for (double z = z1; z <= z2 + 1; z += 1) {
                    p.spawnParticle(Particle.DUST, x1, y, z, 1, dust);
                    p.spawnParticle(Particle.DUST, x2 + 1, y, z, 1, dust);
                }
            }, t * 20L);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property tax
    // ------------------------------------------------------------------------------------------

    private double taxMultiplier() {
        double m = 1;
        for (ActiveEvent e : eco.season().active("tax_audit")) m *= e.number("tax-multiplier", 2);
        return m;
    }

    @Override
    public void newDay(int day, long dayStart) {
        if (!eco.season().running()) return;
        double mult = taxMultiplier();
        java.util.Set<UUID> owners = new java.util.HashSet<>();
        for (Claim c : store.index().all()) if (!c.server()) owners.add(c.owner);
        for (UUID u : owners) {
            long area = used(u);
            long tax = rules.tax(area, mult);
            LandStore.Account a = store.account(u);
            Player p = Bukkit.getPlayer(u);
            if (tax <= 0 || core.economy().withdraw(u, tax, "land_tax")) {
                if (a.warnings != 0) {
                    a.warnings = 0;
                    store.save(u);
                }
                continue;
            }
            a.warnings++;
            if (a.warnings >= rules.warnDays()) {
                List<Claim> mine = store.index().of(u);
                Claim newest = mine.isEmpty() ? null : mine.getLast();
                if (newest != null) {
                    store.delete(newest);
                    eco.getLogger().info("재산세 " + rules.warnDays() + "일 미납: " + Players.name(u) + " 의 구역 해제 (" + newest.area() + "㎡)");
                    if (p != null) p.sendMessage(Lang.msg("land.released", "area", newest.area(), "x", newest.x1, "z", newest.z1));
                }
                a.warnings = 0;
            } else if (p != null) {
                p.sendMessage(Lang.msg("land.tax-warning", "tax", EconomyModule.format(tax), "n", a.warnings, "max", rules.warnDays()));
            }
            store.save(u);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Protection
    // ------------------------------------------------------------------------------------------

    private boolean deny(Player p, Location l, Cancellable e) {
        if (allowed(p, l)) return false;
        e.setCancelled(true);
        long now = now();
        Long last = warned.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            warned.put(p.getUniqueId(), now);
            Claim c = at(l);
            HudModule.flash(p, Lang.mm("land.protected", "owner", c == null || c.server() ? Lang.raw("land.server") : Players.name(c.owner)));
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (eco.stalls() != null && eco.stalls().store().at(e.getBlock().getWorld().getName(), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ()) != null) return;
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
        if (eco.stalls() != null && eco.stalls().store().at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null) return;
        boolean usesBlock = interactive(b) && !harmless(b.getType()) && !(e.getPlayer().isSneaking() && e.getItem() != null);
        // in the town (server claims) doors, gates and buttons work for everyone
        Claim here = at(b.getLocation());
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
        Claim to = at(e.getToBlock().getLocation());
        if (to != null && to != at(e.getBlock().getLocation())) e.setCancelled(true);
    }

    private boolean crosses(Block piston, List<Block> moved, BlockFace dir) {
        Claim home = at(piston.getLocation());
        List<Block> touched = new ArrayList<>(moved);
        for (Block b : moved) touched.add(b.getRelative(dir));
        touched.add(piston.getRelative(dir));
        for (Block b : touched) {
            Claim c = at(b.getLocation());
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
        Claim c = at(e.getTo());
        long id = c == null ? -1 : c.id;
        Long before = where.put(e.getPlayer().getUniqueId(), id);
        if (before != null && before != id) {
            HudModule.flash(e.getPlayer(), c == null ? Lang.mm("land.leave")
                    : Lang.mm("land.enter", "owner", c.server() ? Lang.raw("land.server") : Players.name(c.owner)));
        }
    }
}
