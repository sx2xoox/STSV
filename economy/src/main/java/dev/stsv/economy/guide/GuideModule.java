package dev.stsv.economy.guide;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.crop.CropEvent;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurniturePlaceEvent;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.shop.ShopModule;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 길라잡이: a hands-on tutorial in its own world. /길라잡이 (or the guide book, or a 안내원) opens the topic picker; a topic takes
 * the player to its station (their things are kept on them and handed back afterwards, {@link GuideReturn}), gives only trial
 * things ({@link Items#TRIAL}: worth nothing anywhere, taken away at the end) and walks through steps that advance only when the
 * player really does the thing (the real menus, commands, machines, crops, ores, fish, shop and vehicles). Each topic pays
 * once; all of them give the title [새싹] and a common cosmetic.
 */
public final class GuideModule extends EcoModule {

    public static final String MENU = "economy:guide";
    /** On a 안내원 mannequin (right-click opens the picker). */
    static final NamespacedKey NPC = new NamespacedKey("economy", "guide_npc");
    /** On the first-join guide book. */
    static final NamespacedKey BOOK = new NamespacedKey("economy", "guide_book");
    /** On a session's own entities (helper, bubble, passenger): the player's uuid. */
    static final NamespacedKey MINE = new NamespacedKey("economy", "guide_mine");
    static final String TITLE = "economy:sprout";

    private Map<String, GuideSpec.Topic> topics = Map.of();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Trial machines (furniture display ids) of every session. */
    private final Set<UUID> trialMachines = ConcurrentHashMap.newKeySet();
    /** Players this module is taking into the tutorial world right now (the world's door lets them in). */
    private final Set<UUID> entering = new HashSet<>();
    private GuideWorld world;
    private long ticks;

    public GuideModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-guide";
    }

    /** A player's walk through one topic and what it put in the world. */
    static final class Session {
        final UUID player;
        GuideProgress progress;
        GuideLayout.Station station;
        final Set<UUID> machines = new LinkedHashSet<>();
        final List<Block> placed = new ArrayList<>();
        final List<Block> tilled = new ArrayList<>();
        final List<Block> planted = new ArrayList<>();
        long vehicle;
        Mannequin helper, passenger;
        TextDisplay bubble;
        long stepStarted, lastSay;
        boolean finishing;

        Session(UUID player) {
            this.player = player;
        }
    }

    @Override
    protected void start() {
        load();
        world = new GuideWorld(eco, eco.getConfig().getString("guide.world"));
        core.hud().line("eco-guide", -5, this::hud);
        every(10L, this::tick);
        Bukkit.getScheduler().runTask(eco, () -> {
            World w = world.load();
            if (w == null) {
                eco.getLogger().warning("[길라잡이] 체험관 월드를 만들지 못했습니다: 길라잡이를 쓸 수 없음");
                return;
            }
            if (eco.kv().getLong("guide.built", 0) != GuideWorld.VERSION) {
                eco.getLogger().info("[길라잡이] 체험관을 짓습니다 (처음 또는 새 배치)");
                world.build(false, () -> {}, err -> {
                    if (err == null) eco.kv().set("guide.built", GuideWorld.VERSION);
                });
            } else world.tidy(core.crops());
            for (Player p : Bukkit.getOnlinePlayers()) welcome(p);
        });
    }

    @Override
    protected void stop() {
        for (UUID u : List.copyOf(sessions.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) end(p, true, false);
        }
        sessions.clear();
        trialMachines.clear();
        if (core.hud() != null) core.hud().removeLine("eco-guide");
    }

    @Override
    public void reload() {
        load();
    }

    /** Reads guide/topics/*.yml. */
    private void load() {
        File dir = eco.pack().dir().resolve("guide/topics").toFile();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        List<String> problems = new ArrayList<>();
        List<GuideSpec.Topic> list = new ArrayList<>();
        Set<String> stations = new HashSet<>();
        for (GuideLayout.Station s : GuideLayout.stations()) stations.add(s.id());
        if (files != null) for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            GuideSpec.Topic t = GuideSpec.parse(id, YamlConfiguration.loadConfiguration(f), stations, problems);
            if (t != null) list.add(t);
        }
        for (String p : problems) eco.getLogger().warning("[길라잡이] " + p);
        Map<String, GuideSpec.Topic> m = new LinkedHashMap<>();
        for (GuideSpec.Topic t : GuideSpec.sorted(list)) m.put(t.id(), t);
        topics = m;
        if (m.isEmpty()) eco.getLogger().warning("[길라잡이] contents/economy/guide/topics/ 에 주제가 없습니다");
    }

    public Map<String, GuideSpec.Topic> topics() {
        return topics;
    }

    // ------------------------------------------------------------------------------------------
    // What the other modules ask
    // ------------------------------------------------------------------------------------------

    public boolean active(Player p) {
        return p != null && sessions.containsKey(p.getUniqueId());
    }

    public boolean active(UUID u) {
        return u != null && sessions.containsKey(u);
    }

    /** Is this machine (furniture display id) a tutorial one? */
    public boolean trialMachine(UUID machine) {
        return machine != null && trialMachines.contains(machine);
    }

    /** A job action (break, harvest, catch, find, ...): true = it was done in the tutorial (it counts for nothing else). */
    public boolean jobAction(Player p, String action, String thing, double times) {
        if (!active(p)) return false;
        switch (action) {
            case "break" -> signal(p, "break_ore", Map.of("item", thing));
            case "harvest" -> signal(p, "harvest", Map.of("crop", thing));
            case "catch" -> signal(p, "catch", Map.of("item", thing));
            default -> { }
        }
        return true;
    }

    /** The shop's sale of a trial thing: removed, its price shown, nothing paid. */
    public Object practiceSell(Player p, ItemStack sample, int count, int first) {
        String id = Items.key(sample);
        ShopModule shop = eco.shop();
        var entry = shop.catalog().sell().get(id);
        if (entry == null) return "shop.not-buying";
        java.util.function.Predicate<ItemStack> same = it -> Items.trial(it) && Items.key(it).equals(id);
        int n = Math.min(count, Items.count(p.getInventory(), same));
        if (n <= 0) return "shop.not-buying";
        double unit = shop.price(p.getUniqueId(), id);
        Items.remove(p.getInventory(), same, n, first);
        p.sendMessage(Lang.msg("guide.practice-sold", "item", Items.mmName(sample), "count", n,
                "amount", EconomyModule.format((long) Math.floor(unit * n))));
        signal(p, "practice_sell", Map.of("item", id));
        return new ShopModule.Sale(n, 0, unit, 0);
    }

    /** /버스 렌트: true = handled here (the tutorial's bus). */
    public boolean busRent(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        GuideSpec.Step st = s.progress.current();
        if (st == null || !st.goal().type().equals("bus_rent") || !s.station.id().equals("bus")) {
            p.sendMessage(Lang.msg("guide.not-now"));
            return true;
        }
        VehicleModule vm = core.vehicles();
        if (vm != null && s.vehicle != 0 && vm.temporaryAlive(s.vehicle)) {
            vm.seatTemporary(s.vehicle, p);
        } else if (!spawnVehicle(p, s, eco.getConfig().getString("guide.bus"), "bus", true)) {
            p.sendMessage(Lang.msg("guide.vehicle-failed"));
            return true;
        }
        signal(p, "bus_rent", Map.of());
        return true;
    }

    /** /버스 반납: true = handled here. */
    public boolean busReturn(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        removeVehicle(s);
        signal(p, "bus_return", Map.of());
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Starting, switching and ending
    // ------------------------------------------------------------------------------------------

    public GuideSpec.Topic topic(String word) {
        if (word == null) return null;
        String w = word.replace(" ", "").toLowerCase(Locale.ROOT);
        for (GuideSpec.Topic t : topics.values()) {
            if (t.id().equals(w) || t.name().replace(" ", "").replace("·", "").equals(w.replace("·", ""))) return t;
        }
        for (GuideSpec.Topic t : topics.values()) if (t.name().replace(" ", "").contains(w)) return t;
        return null;
    }

    /** Takes the player into this topic (from anywhere, or from another topic). */
    public void start(Player p, GuideSpec.Topic t) {
        World w = world == null ? null : world.world();
        GuideLayout.Station st = GuideLayout.station(t.station());
        if (w == null || st == null || world.building()) {
            p.sendMessage(Lang.msg("guide.not-ready"));
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            if (dev.stsv.core.minigame.ReturnPoint.has(p) || GuideReturn.has(p)) {
                p.sendMessage(Lang.msg("guide.busy"));
                return;
            }
            if (p.isInsideVehicle()) {
                p.sendMessage(Lang.msg("guide.get-out"));
                return;
            }
            if (core.cosmetics() != null && core.cosmetics().inWardrobe(p)) {
                p.sendMessage(Lang.msg("guide.busy"));
                return;
            }
            p.closeInventory();
            GuideReturn.save(p);
            p.getInventory().clear();
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.setFoodLevel(20);
            p.setLevel(0);
            p.setExp(0);
            s = new Session(p.getUniqueId());
            sessions.put(p.getUniqueId(), s);
        } else {
            if (p.isInsideVehicle()) p.leaveVehicle();
            clearTopic(p, s);
        }
        s.progress = new GuideProgress(t);
        s.station = st;
        s.finishing = false;
        double[] at = st.start();
        entering.add(p.getUniqueId());
        boolean there;
        try {
            there = p.teleport(new Location(w, at[0], GuideLayout.FLOOR_Y, at[1], st.yaw(), 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            entering.remove(p.getUniqueId());
        }
        if (!there) {   // another plugin held them back: nothing happened
            end(p, false, false);
            p.sendMessage(Lang.msg("guide.not-ready"));
            return;
        }
        give(p, t.items());
        spawnHelper(p, s);
        p.sendMessage(Lang.msg("guide.started", "topic", t.name(), "steps", t.steps().size(), "minutes", t.minutes()));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);
        beginStep(p, s);
    }

    /** /길라잡이 그만: back with their things. */
    public void leave(Player p) {
        if (!active(p) && visiting(p)) {   // walked in by the 안내원 secret
            unvisit(p, true);
            p.sendMessage(Lang.msg("guide.left"));
            return;
        }
        if (!active(p)) {
            p.sendMessage(Lang.msg("guide.not-in"));
            return;
        }
        end(p, true, true);
        p.sendMessage(Lang.msg("guide.left"));
    }

    // ------------------------------------------------------------------------------------------
    // Visitors (the 안내원 secret, secret/usher): in the tutorial world without a topic
    // ------------------------------------------------------------------------------------------

    /**
     * Takes someone into the tutorial world without a topic. Their things are kept as for a topic ({@link GuideReturn}) and
     * they walk in with empty hands; a topic they are in ends first (its record is kept, so they still go back to where they
     * came from). {@link #unvisit} or /길라잡이 그만 puts them back; a crash or a quit puts them back on their next join.
     */
    public boolean visit(Player p, Location to) {
        World w = world == null ? null : world.world();
        if (w == null || world.building() || to.getWorld() != w) return false;
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) {
            if (p.isInsideVehicle()) p.leaveVehicle();
            eco.kv().set("guide.last." + p.getUniqueId(), s.progress.topic().id());
            clearTopic(p, s);
            removeHelper(s);
            HudModule.clearFlash(p);
        } else if (!GuideReturn.has(p)) {
            if (dev.stsv.core.minigame.ReturnPoint.has(p) || p.isInsideVehicle()
                    || core.cosmetics() != null && core.cosmetics().inWardrobe(p)) return false;
            p.closeInventory();
            GuideReturn.save(p);
            p.getInventory().clear();
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
        if (inWorld(p)) return p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        entering.add(p.getUniqueId());
        try {
            return p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            entering.remove(p.getUniqueId());
        }
    }

    /** A visitor (see {@link #visit}): in the tutorial world, no topic, their things kept aside. */
    public boolean visiting(Player p) {
        return !active(p) && GuideReturn.has(p) && inWorld(p);
    }

    /** Puts a visitor back (back = to where they came from too). Nothing for someone in a topic. */
    public void unvisit(Player p, boolean back) {
        if (!active(p) && GuideReturn.has(p)) restore(p, back);
    }

    /** The tutorial world, when it is loaded. */
    public World guideWorld() {
        return world == null ? null : world.world();
    }

    /** /길라잡이 다시: the same topic from its first step. */
    public void restart(Player p) {
        Session s = sessions.get(p.getUniqueId());
        String last = s != null ? s.progress.topic().id() : eco.kv().get("guide.last." + p.getUniqueId());
        GuideSpec.Topic t = last == null ? null : topics.get(last);
        if (t == null) {
            open(p);
            return;
        }
        start(p, t);
    }

    /**
     * Ends the session: its things are taken away and the player gets back what they had. back = to where they were (not
     * when they left the world on their own); quiet = no sound.
     */
    void end(Player p, boolean back, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) {
            eco.kv().set("guide.last." + p.getUniqueId(), s.progress.topic().id());
            clearTopic(p, s);
            removeHelper(s);
        }
        restore(p, back);
        if (sound) p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.4f);
    }

    private void restore(Player p, boolean back) {
        HudModule.clearFlash(p);
        if (eco.accessories() != null) eco.accessories().purgeTrial(p.getUniqueId());
        GuideReturn.restore(p, back, fallback(), (q, it) -> eco.mail().deliver(q.getUniqueId(), it, "guide"));
        purgeTrialItems(p);
    }

    /** Where someone goes when their world is no more: the build world's spawn. */
    private Location fallback() {
        World w = core.worlds() != null ? core.worlds().buildWorld() : Bukkit.getWorlds().getFirst();
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** Takes away what the topic put in the world and every trial thing the player holds. */
    private void clearTopic(Player p, Session s) {
        removeVehicle(s);
        removePassenger(s);
        breakMachines(p, s.machines);
        trialMachines.removeAll(s.machines);
        s.machines.clear();
        forgetFurniture(p.getUniqueId());
        for (Block b : s.placed) if (!b.getType().isAir()) b.setType(Material.AIR, false);
        s.placed.clear();
        for (Block b : s.planted) if (core.crops() != null) core.crops().clear(b);
        s.planted.clear();
        for (Block b : s.tilled) if (b.getType() != Material.DIRT) b.setType(Material.DIRT, false);
        s.tilled.clear();
        if (eco.accessories() != null) eco.accessories().purgeTrial(p.getUniqueId());
        p.setItemOnCursor(Items.trial(p.getItemOnCursor()) ? null : p.getItemOnCursor());
        purgeTrialItems(p);
    }

    /** Every trial thing off the player (inventory and ender chest). */
    private void purgeTrialItems(Player p) {
        ItemStack[] inv = p.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < inv.length; i++) if (Items.trial(inv[i])) {
            inv[i] = null;
            changed = true;
        }
        if (changed) p.getInventory().setContents(inv);
        ItemStack[] ender = p.getEnderChest().getContents();
        changed = false;
        for (int i = 0; i < ender.length; i++) if (Items.trial(ender[i])) {
            ender[i] = null;
            changed = true;
        }
        if (changed) p.getEnderChest().setContents(ender);
    }

    private void breakMachines(Player p, java.util.Collection<UUID> ids) {
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof ItemDisplay d)) continue;
            Inventory inv = eco.machines().inventory(id);
            if (inv != null) {
                for (var v : List.copyOf(inv.getViewers())) v.closeInventory();
                inv.clear();
            }
            core.furniture().fromDisplay(d).ifPresent(f -> core.furniture().breakFurniture(p, f, false));
        }
    }

    /** kv guide.furn.&lt;uuid&gt;: trial machines left in the world by a crash, taken away when their owner comes back. */
    private void rememberFurniture(UUID owner, UUID display, Location at) {
        String k = "guide.furn." + owner;
        String old = eco.kv().get(k, "");
        eco.kv().set(k, (old.isEmpty() ? "" : old + ";") + display + "," + at.getBlockX() + "," + at.getBlockZ());
    }

    private void forgetFurniture(UUID owner) {
        if (eco.kv().has("guide.furn." + owner)) eco.kv().remove("guide.furn." + owner);
    }

    /** On join: a crash left trial machines of theirs: load their chunks, break them a moment later (with the owner as breaker). */
    private void sweepFurniture(Player p) {
        String raw = eco.kv().get("guide.furn." + p.getUniqueId());
        World w = world == null ? null : world.world();
        if (raw == null || w == null) return;
        List<UUID> ids = new ArrayList<>();
        List<int[]> chunks = new ArrayList<>();
        for (String part : raw.split(";")) {
            String[] f = part.split(",");
            if (f.length != 3) continue;
            try {
                ids.add(UUID.fromString(f[0]));
                int cx = Integer.parseInt(f[1]) >> 4, cz = Integer.parseInt(f[2]) >> 4;
                w.addPluginChunkTicket(cx, cz, eco);
                chunks.add(new int[]{cx, cz});
            } catch (IllegalArgumentException ignored) {
                // a broken record: forgotten below
            }
        }
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player q = Bukkit.getPlayer(p.getUniqueId());
            if (q != null && !active(q)) {
                breakMachines(q, ids);
                forgetFurniture(q.getUniqueId());
            }
            for (int[] c : chunks) w.removePluginChunkTicket(c[0], c[1], eco);
        }, 60L);
    }

    private void give(Player p, List<GuideSpec.Item> items) {
        for (GuideSpec.Item i : items) {
            ItemStack it;
            if (i.id().equals("economy:accessory") && eco.accessories() != null) it = eco.accessories().trialAccessory();
            else it = Items.create(i.id(), i.amount()).orElse(null);
            if (it == null) {
                eco.getLogger().warning("[길라잡이] 없는 아이템: " + i.id());
                continue;
            }
            Items.markTrial(it);
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------------------------------

    private void beginStep(Player p, Session s) {
        GuideSpec.Step st = s.progress.current();
        if (st == null) return;
        s.stepStarted = now();
        s.lastSay = 0;
        give(p, st.give());
        if (st.detail() != null) p.sendMessage(Lang.msg("guide.detail", "text", st.detail()));
        say(p, s);
    }

    /** Something happened for a player in a session. */
    void signal(Player p, String type, Map<String, String> data) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.finishing) return;
        GuideSpec.Step st = s.progress.current();
        GuideProgress.Result r = s.progress.signal(type, data);
        switch (r) {
            case NONE -> { }
            case COUNTED -> p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
            case STEP_DONE, TOPIC_DONE -> {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                if (st.done() != null) p.sendMessage(Lang.msg("guide.step-done", "text", st.done()));
                if (st.then() != null) then(p, s, st.then());
                if (r == GuideProgress.Result.TOPIC_DONE) finish(p, s);
                else {
                    p.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), Lang.mm("guide.step-title", "pos", s.progress.position()),
                            net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1200), java.time.Duration.ofMillis(300))));
                    beginStep(p, s);
                }
            }
        }
    }

    private void then(Player p, Session s, String what) {
        switch (what) {
            case "truck" -> {
                VehicleModule vm = core.vehicles();
                if (vm != null && s.vehicle != 0 && vm.temporaryAlive(s.vehicle)) vm.seatTemporary(s.vehicle, p);
                else if (!spawnVehicle(p, s, eco.getConfig().getString("guide.truck"), "truck", false)) p.sendMessage(Lang.msg("guide.vehicle-failed"));
            }
            case "board" -> board(p, s);
            case "alight" -> alight(p, s);
            case "remove-vehicle" -> removeVehicle(s);
            default -> { }
        }
    }

    /** The topic is done: rewards (first time), then back after a moment. */
    private void finish(Player p, Session s) {
        s.finishing = true;
        GuideSpec.Topic t = s.progress.topic();
        UUID u = p.getUniqueId();
        Set<String> done = done(u);
        boolean first = done.add(t.id());
        p.showTitle(net.kyori.adventure.title.Title.title(Lang.mm("guide.done-title"), Lang.mm("guide.done-subtitle", "topic", t.name())));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
        if (first) {
            eco.kv().set("guide.done." + u, GuideRewards.formatDone(done));
            if (t.reward().money() > 0) core.economy().deposit(u, t.reward().money(), "guide_reward");
            String job = eco.jobs().job(u);
            if (t.reward().xp() > 0 && job != null) eco.jobs().addXp(u, job, t.reward().xp());
            p.sendMessage(Lang.msg("guide.reward", "topic", t.name(), "amount", EconomyModule.format(t.reward().money()),
                    "xp", t.reward().xp() > 0 && job != null ? Lang.raw("guide.reward-xp", "xp", (long) t.reward().xp()) : ""));
        } else p.sendMessage(Lang.msg("guide.again-done", "topic", t.name()));
        p.sendMessage(Lang.msg("guide.progress", "done", done.stream().filter(topics::containsKey).count(), "all", topics.size()));
        if (first && GuideRewards.allDone(topics.keySet(), done) && !eco.kv().has("guide.all." + u)) allDone(p);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player q = Bukkit.getPlayer(u);
            Session now = sessions.get(u);
            if (q == null || now != s) return;
            end(q, true, true);
            q.sendMessage(Lang.msg("guide.next").clickEvent(ClickEvent.runCommand("/길라잡이")));
        }, 60L);
    }

    /** Every topic done: [새싹], one common cosmetic they do not own, and the news. */
    private void allDone(Player p) {
        UUID u = p.getUniqueId();
        eco.kv().set("guide.all." + u, System.currentTimeMillis());
        String title = eco.getConfig().getString("guide.title");
        if (core.profiles() != null && !title.isEmpty()) core.profiles().grantTitle(u, title);
        String got = "";
        if (core.cosmetics() != null) {
            List<String> commons = new ArrayList<>();
            for (CustomItem c : core.cosmetics().cosmetics())
                if (c.cosmetic().rarity() == CustomItem.Rarity.COMMON) commons.add(c.key().asString());
            Optional<String> pick = GuideRewards.pickCosmetic(commons, core.cosmetics().owned(u), ThreadLocalRandom.current());
            if (pick.isPresent()) {
                Optional<CustomItem> c = core.content().item(pick.get());
                if (c.isPresent()) {
                    core.cosmetics().grant(p, c.get(), true);
                    core.cosmetics().celebrate(p, c.get());
                    got = c.get().name() == null ? pick.get() : c.get().name();
                }
            }
        }
        String titleName = core.profiles() == null ? title : core.profiles().title(title).map(t -> t.name()).orElse(title);
        p.sendMessage(Lang.msg("guide.all-done", "title", titleName, "cosmetic", got.isEmpty() ? Lang.raw("guide.all-done-none") : got));
        Bukkit.broadcast(Lang.msg("guide.all-done-broadcast", "player", Names.mini(p)));
    }

    public Set<String> done(UUID u) {
        return GuideRewards.parseDone(eco.kv().get("guide.done." + u));
    }

    // ------------------------------------------------------------------------------------------
    // Every half second: polled goals, the arrow, the helper, the farm, the vehicles
    // ------------------------------------------------------------------------------------------

    private void tick() {
        ticks++;
        for (Session s : List.copyOf(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null || s.finishing) continue;
            GuideSpec.Step st = s.progress.current();
            if (st == null) continue;
            try {
                poll(p, s, st);
                arrow(p, s, st);
                helper(p, s, st);
            } catch (RuntimeException e) {
                eco.getLogger().warning("[길라잡이] " + p.getName() + ": " + e);
            }
        }
    }

    private void poll(Player p, Session s, GuideSpec.Step st) {
        UUID u = p.getUniqueId();
        GuideSpec.Goal g = st.goal();
        switch (g.type()) {
            case "has_job" -> {
                if (eco.jobs().job(u) != null) signal(p, "has_job", Map.of());
            }
            case "machine_input" -> {
                for (UUID id : s.machines) {
                    Inventory inv = eco.machines().inventory(id);
                    if (inv == null) continue;
                    boolean any = false;
                    for (int lane = 0; lane < 5 && !any; lane++) for (int i = 0; i < 3; i++) {
                        ItemStack it = inv.getItem(9 * (lane + 1) + 1 + i);
                        if (it != null && !it.isEmpty()) any = true;
                    }
                    String type = eco.machines().typeOf(id);
                    if (any && type != null) signal(p, "machine_input", Map.of("machine", type));
                }
            }
            case "have_item" -> {
                String want = g.params().get("item");
                for (ItemStack it : p.getInventory().getStorageContents()) {
                    if (Items.trial(it) && GuideSpec.normalize(Items.key(it)).equals(GuideSpec.normalize(want))) {
                        signal(p, "have_item", Map.of("item", want));
                        break;
                    }
                }
            }
            case "crop_ripe" -> {
                for (Block b : s.planted) if (core.crops() != null && core.crops().ripeAt(b)) {
                    signal(p, "crop_ripe", Map.of());
                    break;
                }
            }
            case "accessory_worn" -> {
                if (eco.accessories() != null && eco.accessories().wearsTrial(u)) signal(p, "accessory_worn", Map.of());
            }
            case "accessory_off" -> {
                boolean holding = false;
                for (ItemStack it : p.getInventory().getStorageContents())
                    if (Items.trial(it) && dev.stsv.economy.accessory.AccessoryModule.isAccessory(it)) holding = true;
                if (eco.accessories() != null && !eco.accessories().wearsTrial(u) && holding) signal(p, "accessory_off", Map.of());
            }
            case "drive_stop" -> {
                VehicleModule vm = core.vehicles();
                VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
                if (d == null || d.id() != s.vehicle || Math.abs(d.speed()) > 0.05) break;
                int[] zone = s.station.regions().get(g.params().get("zone"));
                if (s.station.inside(zone, d.x(), d.z())) signal(p, "drive_stop", Map.of("zone", g.params().get("zone")));
            }
            default -> { }
        }
        // the field grows fast for whoever farms in it (a step a second)
        if (!s.planted.isEmpty() && core.crops() != null && ticks % 2 == 0) {
            for (Block b : s.planted) core.crops().growAround(b.getWorld(), b.getX(), b.getY(), b.getZ(), 0, 0, 0, 1.0);
        }
    }

    /** Particles toward where to go: a bobbing arrow over the target and a short trail from the player. */
    private void arrow(Player p, Session s, GuideSpec.Step st) {
        if (st.at() == null || !inWorld(p)) return;
        double[] xz = s.station.point(st.at()[0], st.at()[2]);
        double y = GuideLayout.FLOOR_Y + st.at()[1] + 1.4 + Math.sin(ticks * 0.6) * 0.15;
        Location tip = new Location(p.getWorld(), xz[0], y, xz[1]);
        for (int i = 0; i <= 4; i++) {
            p.spawnParticle(Particle.END_ROD, tip.clone().add(0, 0.18 * i, 0), 1, 0, 0, 0, 0);
            if (i > 0 && i <= 2) {
                p.spawnParticle(Particle.END_ROD, tip.clone().add(0.14 * i, 0.16 * i, 0), 1, 0, 0, 0, 0);
                p.spawnParticle(Particle.END_ROD, tip.clone().add(-0.14 * i, 0.16 * i, 0), 1, 0, 0, 0, 0);
            }
        }
        Location eye = p.getEyeLocation().subtract(0, 0.4, 0);
        org.bukkit.util.Vector dir = tip.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 3) return;
        dir.normalize();
        for (int i = 1; i <= 4; i++) {
            Location at = eye.clone().add(dir.clone().multiply(0.6 + i * 0.45));
            p.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The helper (a mannequin only its player sees) and its speech bubble
    // ------------------------------------------------------------------------------------------

    private Mannequin mannequin(Location at, String skin, Player viewer, Component name) {
        return at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.setCollidable(false);
            m.setRemoveWhenFarAway(false);
            m.getPersistentDataContainer().set(MINE, PersistentDataType.STRING, viewer == null ? "" : viewer.getUniqueId().toString());
            if (viewer != null) m.setVisibleByDefault(false);
            if (name != null) {
                m.customName(name);
                m.setCustomNameVisible(true);
            }
            if (skin != null && !skin.isBlank()) {
                boolean slim = skin.endsWith(" slim");
                String key = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
                try {
                    m.setProfile(ResolvableProfile.resolvableProfile().name("guide").skinPatch(sp -> sp.body(Key.key(key))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
                } catch (RuntimeException e) {
                    eco.getLogger().warning("[길라잡이] 스킨 키가 잘못됨: " + skin);
                }
            }
        });
    }

    private void spawnHelper(Player p, Session s) {
        removeHelper(s);
        double[] h = s.station.helper();
        Location at = new Location(p.getWorld(), h[0], GuideLayout.FLOOR_Y, h[1], s.station.yaw() + 180, 0);
        s.helper = mannequin(at, eco.getConfig().getString("guide.helper-skin"), p, Lang.mm("guide.helper-name"));
        p.showEntity(eco, s.helper);
    }

    private void removeHelper(Session s) {
        if (s.bubble != null) s.bubble.remove();
        if (s.helper != null) s.helper.remove();
        s.bubble = null;
        s.helper = null;
    }

    private void helper(Player p, Session s, GuideSpec.Step st) {
        Mannequin m = s.helper;
        if (m == null || !m.isValid() || m.getWorld() != p.getWorld()) return;
        // looks at the player
        Location l = m.getLocation();
        org.bukkit.util.Vector to = p.getLocation().toVector().subtract(l.toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        m.setRotation(yaw, 0);
        long every = eco.getConfig().getLong("guide.say-seconds") * 1000;
        if (st.say() != null && now() - s.lastSay > every) say(p, s);
    }

    private void say(Player p, Session s) {
        GuideSpec.Step st = s.progress.current();
        if (st == null || st.say() == null || s.helper == null || !s.helper.isValid()) return;
        s.lastSay = now();
        if (s.bubble != null) s.bubble.remove();
        s.bubble = bubble(p, s.helper, st.say(), 100L);
    }

    private TextDisplay bubble(Player viewer, Entity over, String text, long life) {
        TextDisplay d = over.getWorld().spawn(over.getLocation().add(0, 2.2, 0), TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.text(Text.mm("<black>" + text));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(230, 255, 255, 255));
            t.setLineWidth(180);
            t.getPersistentDataContainer().set(MINE, PersistentDataType.STRING, viewer.getUniqueId().toString());
        });
        viewer.showEntity(eco, d);
        if (!over.addPassenger(d)) d.teleport(over.getLocation().add(0, 2.2, 0));
        Bukkit.getScheduler().runTaskLater(eco, d::remove, life);
        return d;
    }

    // ------------------------------------------------------------------------------------------
    // Vehicles and the bus passenger
    // ------------------------------------------------------------------------------------------

    /** A temporary vehicle at the station's feature (spot), the player at its wheel. */
    private boolean spawnVehicle(Player p, Session s, String itemId, String spot, boolean alongFront) {
        VehicleModule vm = core.vehicles();
        Optional<CustomItem> item = core.content().item(itemId);
        int[] f = s.station.features().get(spot);
        if (vm == null || item.isEmpty() || f == null) return false;
        removeVehicle(s);
        if (p.isInsideVehicle()) p.leaveVehicle();
        double[] xz = s.station.point(f[0], f[2]);
        float yaw = alongFront ? s.station.yawRight() : s.station.yaw();
        long id = vm.spawnTemporary(item.get(), p.getWorld(), xz[0], GuideLayout.FLOOR_Y, xz[1], yaw, null, p);
        if (id == 0) return false;
        vm.letOutOfTemporary(id);
        s.vehicle = id;
        return true;
    }

    private void removeVehicle(Session s) {
        removePassenger(s);
        VehicleModule vm = core.vehicles();
        if (vm != null && s.vehicle != 0) vm.removeTemporary(s.vehicle);
        s.vehicle = 0;
    }

    private void board(Player p, Session s) {
        VehicleModule vm = core.vehicles();
        if (vm == null || s.vehicle == 0) return;
        List<Integer> free = vm.freeSeats(s.vehicle);
        if (free.isEmpty()) return;
        removePassenger(s);
        Location at = p.getLocation().clone().add(2, 0, 0);
        s.passenger = mannequin(at, eco.getConfig().getString("guide.passenger-skin"), p, Lang.mm("guide.passenger-name"));
        p.showEntity(eco, s.passenger);
        if (!vm.seatGuest(s.vehicle, free.getFirst(), s.passenger)) {
            removePassenger(s);
            return;
        }
        bubble(p, s.passenger, Lang.raw("guide.passenger-hello"), 80L);
    }

    private void alight(Player p, Session s) {
        VehicleModule vm = core.vehicles();
        Mannequin m = s.passenger;
        if (m == null) return;
        if (vm != null && s.vehicle != 0) vm.unseatGuest(s.vehicle, m);
        bubble(p, m, Lang.raw("guide.passenger-thanks"), 60L);
        s.passenger = null;
        Bukkit.getScheduler().runTaskLater(eco, m::remove, 60L);
    }

    private void removePassenger(Session s) {
        if (s.passenger == null) return;
        VehicleModule vm = core.vehicles();
        if (vm != null && s.vehicle != 0) vm.unseatGuest(s.vehicle, s.passenger);
        s.passenger.remove();
        s.passenger = null;
    }

    // ------------------------------------------------------------------------------------------
    // The HUD line
    // ------------------------------------------------------------------------------------------

    private Component hud(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return null;
        GuideSpec.Step st = s.progress.current();
        if (st == null) return Lang.mm("guide.hud-done", "topic", s.progress.topic().name());
        String count = st.goal().count() > 1 ? " (" + s.progress.count() + "/" + st.goal().count() + ")" : "";
        return Lang.mm("guide.hud", "topic", s.progress.topic().name(), "pos", s.progress.position(), "text", st.text() + count);
    }

    // ------------------------------------------------------------------------------------------
    // The picker (economy:guide) and the command
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, MENU, new Picker());
    }

    private final class Picker implements MenuHandler {
        private final List<GuideSpec.Topic> shown = new ArrayList<>(topics.values());

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Set<String> done = done(v.player().getUniqueId());
            long n = shown.stream().filter(t -> done.contains(t.id())).count();
            long left = 0;
            for (GuideSpec.Topic t : shown) if (!done.contains(t.id())) left += t.reward().money();
            Session s = sessions.get(v.player().getUniqueId());
            return Map.of("count", n + "/" + shown.size(), "left", EconomyModule.format(left),
                    "now", s == null ? Lang.raw("guide.menu-none") : s.progress.topic().name());
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Set<String> done = done(v.player().getUniqueId());
            Session s = sessions.get(v.player().getUniqueId());
            List<ItemStack> out = new ArrayList<>();
            for (GuideSpec.Topic t : shown) {
                boolean ok = done.contains(t.id());
                boolean now = s != null && s.progress.topic().id().equals(t.id());
                ItemStack it = Items.create(t.icon(), 1).orElse(new ItemStack(Material.BOOK));
                it.editMeta(m -> {
                    m.itemName(Lang.item("guide.menu-topic", "topic", t.name(), "done", ok ? Lang.raw("guide.menu-done") : "",
                            "now", now ? Lang.raw("guide.menu-now") : ""));
                    List<Component> lore = new ArrayList<>();
                    if (!t.about().isEmpty()) lore.add(Text.item("<gray>" + t.about()));
                    lore.addAll(Lang.items("guide.menu-lore", "minutes", t.minutes(), "steps", t.steps().size()));
                    lore.addAll(ok ? Lang.items("guide.menu-reward-done") : Lang.items("guide.menu-reward", "amount", EconomyModule.format(t.reward().money())));
                    lore.addAll(Lang.items("guide.menu-click"));
                    m.lore(lore);
                    m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                    if (ok || now) m.setEnchantmentGlintOverride(true);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index < 0 || index >= shown.size()) return;
            Player p = v.player();
            GuideSpec.Topic t = shown.get(index);
            v.close();
            Bukkit.getScheduler().runTask(eco, () -> start(p, t));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (!action.equals("guide:leave")) return;
            Player p = v.player();
            v.close();
            // left: back with their things; right: this topic again from the start
            Bukkit.getScheduler().runTask(eco, () -> {
                if (click.isRightClick()) restart(p);
                else leave(p);
            });
        }
    }

    /** /길라잡이 [주제 | 그만 | 다시 | 목록] */
    public void command(Player p, String[] a) {
        if (a.length == 0 || a[0].equals("목록")) {
            open(p);
            return;
        }
        switch (a[0]) {
            case "그만", "나가기", "종료" -> leave(p);
            case "다시" -> restart(p);
            default -> {
                GuideSpec.Topic t = topic(String.join("", a));
                if (t == null) p.sendMessage(Lang.msg("guide.no-such", "topics", String.join(", ", topics.values().stream().map(GuideSpec.Topic::name).toList())));
                else start(p, t);
            }
        }
    }

    public List<String> complete(String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("그만", "다시", "목록"));
        for (GuideSpec.Topic t : topics.values()) out.add(t.name().replace(" ", ""));
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Admin: /관리자 길라잡이 ...
    // ------------------------------------------------------------------------------------------

    public void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0];
        switch (sub) {
            case "체험관" -> {
                String what = a.length > 1 ? a[1] : "";
                if (!what.equals("짓기") && !what.equals("지우기")) {
                    s.sendMessage(Text.msg("<red>/관리자 길라잡이 체험관 <짓기|지우기>"));
                    return;
                }
                if (world == null || world.world() == null) {
                    s.sendMessage(Text.msg("<red>체험관 월드가 없습니다"));
                    return;
                }
                boolean clear = what.equals("지우기");
                s.sendMessage(Text.msg("<gray>체험관 " + (clear ? "지우는" : "짓는") + " 중... (진행 중인 길라잡이는 끝냄)"));
                world.build(clear, () -> {
                    for (UUID u : List.copyOf(sessions.keySet())) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) {
                            end(p, true, true);
                            p.sendMessage(Lang.msg("guide.rebuilt"));
                        }
                    }
                }, err -> {
                    if (err == null && !clear) eco.kv().set("guide.built", GuideWorld.VERSION);
                    if (err == null && clear) eco.kv().remove("guide.built");
                    s.sendMessage(Text.msg(err == null ? "<green>체험관 " + (clear ? "지움" : "지음") + " (월드 " + world.name() + ")" : "<red>" + err));
                });
            }
            case "초기화", "진행" -> {
                if (a.length < 2) {
                    s.sendMessage(Text.msg("<red>/관리자 길라잡이 " + sub + " <플레이어>"));
                    return;
                }
                Players.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>없는 플레이어"));
                        return;
                    }
                    UUID u = found.get();
                    if (sub.equals("초기화")) {
                        Player on = Bukkit.getPlayer(u);
                        if (on != null && active(on)) end(on, true, true);
                        eco.kv().remove("guide.done." + u);
                        eco.kv().remove("guide.all." + u);
                        eco.kv().remove("guide.book." + u);
                        eco.kv().remove("guide.last." + u);
                        s.sendMessage(Text.msg("<green>" + a[1] + ": 길라잡이 기록을 지웠습니다 (보상을 다시 받을 수 있고, 다음 접속 때 안내 책을 다시 받음)"));
                    } else {
                        Set<String> done = done(u);
                        s.sendMessage(Text.msg("<white>" + a[1] + " <gray>길라잡이 " + done.stream().filter(topics::containsKey).count() + "/" + topics.size()
                                + (eco.kv().has("guide.all." + u) ? " <gold>(모두 마침, 보상 받음)" : "")));
                        for (GuideSpec.Topic t : topics.values())
                            s.sendMessage(Text.msg(" " + (done.contains(t.id()) ? "<green>✔ " : "<dark_gray>· ") + t.name() + " <gray>"
                                    + EconomyModule.format(t.reward().money())));
                        Session now = sessions.get(u);
                        if (now != null) s.sendMessage(Text.msg("<yellow>지금: " + now.progress.topic().name() + " " + now.progress.position()));
                    }
                });
            }
            case "안내원" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(Text.msg("<red>게임 안에서만"));
                    return;
                }
                if (a.length > 1 && a[1].equals("제거")) {
                    int n = 0;
                    for (Entity e : p.getNearbyEntities(5, 3, 5)) if (e.getPersistentDataContainer().has(NPC)) {
                        e.remove();
                        n++;
                    }
                    s.sendMessage(Text.msg("<gray>안내원 " + n + "명을 치웠습니다"));
                    return;
                }
                Location l = p.getLocation();
                l.setPitch(0);
                spawnNpc(l);
                s.sendMessage(Text.msg("<green>안내원을 세웠습니다 (우클릭 = 길라잡이). 치우기: 옆에서 /관리자 길라잡이 안내원 제거"));
            }
            default -> s.sendMessage(Text.msg("<gray>/관리자 길라잡이 체험관 <짓기|지우기> · 초기화 <플레이어> · 진행 <플레이어> · 안내원 [제거]"
                    + " <dark_gray>(주제 " + topics.size() + "개, 첫 완료 보상 합계 " + EconomyModule.format(GuideSpec.totalMoney(topics.values())) + ")"));
        }
    }

    public List<String> adminComplete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("체험관", "초기화", "진행", "안내원");
        if (a.length == 2 && a[0].equals("체험관")) return List.of("짓기", "지우기");
        if (a.length == 2 && a[0].equals("안내원")) return List.of("제거");
        if (a.length == 2 && (a[0].equals("초기화") || a[0].equals("진행"))) return Names.complete(s, a[1]);
        return List.of();
    }

    /** A 안내원: a persistent mannequin; right-click opens the picker. */
    static void spawnNpc(Location at) {
        EcoPlugin eco = EcoPlugin.get();
        at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setPersistent(true);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.setRemoveWhenFarAway(false);
            m.customName(Lang.mm("guide.npc-name"));
            m.setCustomNameVisible(true);
            m.setDescription(Lang.mm("guide.npc-description"));
            m.getPersistentDataContainer().set(NPC, PersistentDataType.BYTE, (byte) 1);
            String skin = eco.getConfig().getString("guide.npc-skin");
            if (!skin.isBlank()) {
                boolean slim = skin.endsWith(" slim");
                String key = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
                try {
                    m.setProfile(ResolvableProfile.resolvableProfile().name("guide_npc").skinPatch(sp -> sp.body(Key.key(key))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
                } catch (RuntimeException e) {
                    eco.getLogger().warning("[길라잡이] 안내원 스킨 키가 잘못됨: " + skin);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Joining, leaving, the first-join book
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent e) {
        welcome(e.getPlayer());
    }

    private void welcome(Player p) {
        // a crash or a stop while in the tutorial: their things and place come back now
        if (GuideReturn.has(p) && !active(p)) restore(p, true);
        else if (inWorld(p) && !active(p) && !p.hasPermission("stsv.economy.admin")) p.teleport(fallback());
        if (!active(p)) purgeTrialItems(p);
        if (eco.accessories() != null && !active(p)) eco.accessories().purgeTrial(p.getUniqueId());
        sweepFurniture(p);
        String key = "guide.book." + p.getUniqueId();
        if (!eco.kv().has(key) && eco.getConfig().getBoolean("guide.first-join-book")) {
            eco.kv().set(key, System.currentTimeMillis());
            ItemStack book = book();
            for (ItemStack left : p.getInventory().addItem(book).values()) eco.mail().deliver(p.getUniqueId(), left, "guide");
            p.sendMessage(Lang.msg("guide.first-join").clickEvent(ClickEvent.runCommand("/길라잡이")));
        }
    }

    private static ItemStack book() {
        ItemStack it = new ItemStack(Material.BOOK);
        it.editMeta(m -> {
            m.itemName(Lang.item("guide.book-name"));
            m.lore(Lang.items("guide.book-lore"));
            m.getPersistentDataContainer().set(BOOK, PersistentDataType.BYTE, (byte) 1);
            m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        entering.remove(p.getUniqueId());
        if (active(p)) end(p, true, false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBook(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(BOOK)) return;
        e.setCancelled(true);
        open(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNpc(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getRightClicked().getPersistentDataContainer().has(NPC)) return;
        e.setCancelled(true);
        open(e.getPlayer());
    }

    // ------------------------------------------------------------------------------------------
    // The world's door and its rules
    // ------------------------------------------------------------------------------------------

    private boolean inWorld(Player p) {
        return world != null && p.getWorld().getName().equals(world.name());
    }

    private boolean guideWorld(World w) {
        return world != null && w != null && w.getName().equals(world.name());
    }

    /** May this player do anything here (an admin outside a session: building and looking around). */
    private boolean free(Player p) {
        return !active(p) && p.hasPermission("stsv.economy.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnter(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        boolean into = guideWorld(e.getTo().getWorld()) && !guideWorld(e.getFrom().getWorld());
        if (into && !entering.contains(p.getUniqueId()) && !free(p)) {
            e.setCancelled(true);
            p.sendMessage(Lang.msg("guide.closed-door"));
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // left the tutorial world some other way (a teleport, a respawn): the session ends where they are
        if (guideWorld(e.getFrom()) && active(p)) {
            end(p, false, false);
            p.sendMessage(Lang.msg("guide.left"));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommandGuard(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!active(p)) return;
        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        Command c = Bukkit.getCommandMap().getCommand(label);
        String name = c == null ? label : c.getName();
        if (eco.getConfig().getStringList("guide.blocked-commands").contains(name)) {
            e.setCancelled(true);
            p.sendMessage(Lang.msg("guide.not-now"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!active(p)) return;
        String msg = e.getMessage().substring(1).trim();
        int sp = msg.indexOf(' ');
        String label = (sp < 0 ? msg : msg.substring(0, sp)).toLowerCase(Locale.ROOT);
        String args = sp < 0 ? "" : msg.substring(sp + 1).trim();
        Command c = Bukkit.getCommandMap().getCommand(label);
        String name = c == null ? label : c.getName();
        if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
        signal(p, "command", Map.of("command", name, "args", args));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMenu(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !active(p)) return;
        if (e.getInventory().getHolder(false) instanceof MenuView v) signal(p, "menu", Map.of("menu", v.spec().id()));
    }

    /** In the tutorial world only menus and the player's own trial machines open (no chest, ender chest, vehicle bed ...). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !inWorld(p) || free(p)) return;
        Inventory inv = e.getInventory();
        if (inv.getHolder(false) instanceof MenuView || inv.getType() == InventoryType.CRAFTING || inv.getType() == InventoryType.PLAYER) return;
        UUID machine = eco.machines().machineOf(inv);
        Session s = sessions.get(p.getUniqueId());
        if (machine != null && s != null && s.machines.contains(machine)) return;
        e.setCancelled(true);
        HudModule.flash(p, Lang.mm("guide.cannot-open"));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!guideWorld(e.getBlock().getWorld()) || free(p)) return;
        Session s = sessions.get(p.getUniqueId());
        String region = s == null ? null : placeRegion(s.station.id());
        Block b = e.getBlock();
        // the farm field: a hoe tilling dirt (the server reports that as placing farmland) and planting the trial seeds
        if (s != null && s.station.id().equals("farming") && s.station.inside(s.station.regions().get("field"), b.getX() + 0.5, b.getZ() + 0.5)
                && (Tag.ITEMS_HOES.isTagged(e.getItemInHand().getType()) || Items.trial(e.getItemInHand()))) return;
        if (s == null || region == null || !Items.trial(e.getItemInHand()) || !s.station.inside(s.station.regions().get(region), b.getX() + 0.5, b.getZ() + 0.5)
                || b.getY() < GuideLayout.FLOOR_Y || b.getY() > GuideLayout.FLOOR_Y + 8) {
            e.setCancelled(true);
            HudModule.flash(p, Lang.mm("guide.cannot-place"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !inWorld(p) || core.furniture().probing()) return;
        if (Items.furniture(e.getItemInHand())) return;   // a machine's cells: the furniture event tells
        // a hoe tilling the field reports as placing farmland: that soil is the field's own, put back as dirt at the end
        if (Tag.ITEMS_HOES.isTagged(e.getItemInHand().getType())) return;
        s.placed.add(e.getBlock());
        String region = placeRegion(s.station.id());
        if (region != null) signal(p, "place_block", Map.of("region", region));
    }

    /** Where a station lets trial things be placed. */
    private static String placeRegion(String station) {
        return switch (station) {
            case "machines" -> "machine";
            case "mining" -> "bench";
            case "land" -> "plot";
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!guideWorld(b.getWorld()) || free(p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.placed.contains(b)) {
            s.placed.remove(b);
            return;
        }
        if (s != null && s.station.id().equals("mining") && ore(b)) {
            // the wall grows its ore back
            Material m = b.getType();
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (b.getType().isAir()) b.setType(m, false);
            }, eco.getConfig().getLong("guide.ore-regrow-seconds") * 20);
            return;
        }
        if (core.crops() != null && core.crops().at(b).isPresent() && s != null && s.station.id().equals("farming")) return;
        e.setCancelled(true);
    }

    private boolean ore(Block b) {
        GuideLayout.Station st = GuideLayout.station("mining");
        int y = b.getY() - GuideLayout.FLOOR_Y;
        return y >= 0 && y <= 2 && st.inside(st.regions().get("ores"), b.getX() + 0.5, b.getZ() + 0.5) && b.getType().name().endsWith("_ORE");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent e) {
        Player p = e.getPlayer();
        if (!guideWorld(e.getFurniture().location().getWorld()) || free(p)) return;
        Session s = sessions.get(p.getUniqueId());
        ItemStack hand = p.getInventory().getItemInMainHand();
        String region = s == null ? null : placeRegion(s.station.id());
        Location l = e.getFurniture().location();
        if (s == null || region == null || !(Items.trial(hand) || Items.trial(p.getInventory().getItemInOffHand()))
                || !s.station.inside(s.station.regions().get(region), l.getX(), l.getZ())) {
            e.setCancelled(true);
            HudModule.flash(p, Lang.mm("guide.cannot-place"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlaced(FurniturePlaceEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !guideWorld(e.getFurniture().location().getWorld())) return;
        Furniture f = e.getFurniture();
        UUID id = f.display().getUniqueId();
        s.machines.add(id);
        trialMachines.add(id);
        rememberFurniture(p.getUniqueId(), id, f.location());
        String behavior = f.item().furniture() == null ? null : f.item().furniture().behavior();
        if (behavior != null && behavior.startsWith("economy:machine/"))
            signal(p, "place_machine", Map.of("machine", behavior.substring("economy:machine/".length())));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent e) {
        Player p = e.getPlayer();
        if (p == null || !guideWorld(e.getFurniture().location().getWorld()) || free(p)) return;
        Session s = sessions.get(p.getUniqueId());
        UUID id = e.getFurniture().display().getUniqueId();
        if (s != null && s.machines.contains(id)) return;
        if (eco.kv().get("guide.furn." + p.getUniqueId(), "").contains(id.toString())) return;   // their own leftover
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnitureBroken(FurnitureBreakEvent e) {
        if (e.isCancelled() || e.getPlayer() == null) return;
        Session s = sessions.get(e.getPlayer().getUniqueId());
        UUID id = e.getFurniture().display().getUniqueId();
        trialMachines.remove(id);
        if (s != null) s.machines.remove(id);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inWorld(p) || free(p)) return;
        Block b = e.getClickedBlock();
        if (e.getAction() == Action.PHYSICAL) {
            if (b != null && b.getType() == Material.FARMLAND) e.setCancelled(true);
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s == null || b == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        // a station's desk
        for (Map.Entry<String, int[]> f : s.station.features().entrySet()) {
            int[] c = s.station.block(f.getValue()[0], f.getValue()[2]);
            if (b.getX() == c[0] && b.getZ() == c[1] && b.getY() == GuideLayout.FLOOR_Y + f.getValue()[1] && b.getType() != Material.AIR) {
                e.setCancelled(true);
                signal(p, "interact", Map.of("feature", f.getKey()));
                // the truck again (got out, drove it off the pad): the garage gives it back
                GuideSpec.Step now = s.progress.current();
                if (f.getKey().equals("garage") && now != null && now.goal().type().equals("drive_stop")) then(p, s, "truck");
                return;
            }
        }
        // tilling in the field
        ItemStack hand = e.getItem();
        if (hand != null && Tag.ITEMS_HOES.isTagged(hand.getType()) && (b.getType() == Material.DIRT || b.getType() == Material.GRASS_BLOCK)
                && s.station.id().equals("farming") && s.station.inside(s.station.regions().get("field"), b.getX() + 0.5, b.getZ() + 0.5)) {
            Bukkit.getScheduler().runTask(eco, () -> {
                if (b.getType() != Material.FARMLAND) return;
                s.tilled.add(b);
                signal(p, "till", Map.of());
            });
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCrop(CropEvent e) {
        Player p = e.player();
        if (p == null || !guideWorld(e.location().getWorld()) || free(p)) return;
        Session s = sessions.get(p.getUniqueId());
        boolean field = s != null && s.station.id().equals("farming")
                && s.station.inside(s.station.regions().get("field"), e.location().getX() + 0.5, e.location().getZ() + 0.5);
        if (!field) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCropDone(CropEvent e) {
        Player p = e.player();
        if (p == null || !active(p) || e.action() != CropEvent.Action.PLANT) return;
        Session s = sessions.get(p.getUniqueId());
        s.planted.add(e.location().getBlock());
        signal(p, "plant", Map.of("crop", e.crop().id()));
    }

    /** The tutorial pond bites quickly. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.FISHING || !active(e.getPlayer()) || !inWorld(e.getPlayer())) return;
        e.getHook().setWaitTime(40, 100);
    }

    /** Getting back into the tutorial's vehicle: right-click it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onVehicle(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        VehicleModule vm = core.vehicles();
        if (s == null || vm == null || s.vehicle == 0 || p.isInsideVehicle() || !VehicleModule.isVehiclePart(e.getRightClicked())) return;
        var st = vm.temporaryState(s.vehicle);
        if (st == null || Math.hypot(st.x - e.getRightClicked().getX(), st.z - e.getRightClicked().getZ()) > 8) return;
        e.setCancelled(true);
        vm.seatTemporary(s.vehicle, p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!inWorld(e.getPlayer()) || free(e.getPlayer())) return;
        e.setCancelled(true);
        HudModule.flash(e.getPlayer(), Lang.mm("guide.cannot-drop"));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && inWorld(p) && !free(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (inWorld(e.getPlayer()) && !free(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketFillEvent e) {
        if (inWorld(e.getPlayer()) && !free(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inWorld(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inWorld(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (guideWorld(e.getLocation().getWorld()) && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.COMMAND) e.setCancelled(true);
    }

    /** Everything that drops in the tutorial world is a trial thing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (!guideWorld(e.getLocation().getWorld())) return;
        ItemStack it = e.getEntity().getItemStack();
        if (!Items.trial(it)) e.getEntity().setItemStack(Items.markTrial(it));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent e) {
        if (!guideWorld(e.getBlock().getWorld())) return;
        for (var item : e.getItems()) if (!Items.trial(item.getItemStack())) item.setItemStack(Items.markTrial(item.getItemStack()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player) || !guideWorld(e.getItem().getWorld())) return;
        ItemStack it = e.getItem().getItemStack();
        if (!Items.trial(it)) e.getItem().setItemStack(Items.markTrial(it));
    }

    /** Off the edge: back to the station (or the plaza). */
    @EventHandler(ignoreCancelled = true)
    public void onFall(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e.getTo().getY() > GuideLayout.FLOOR_Y - 12 || !inWorld(p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            p.teleport(world.plaza());
            return;
        }
        double[] at = s.station.start();
        p.teleport(new Location(p.getWorld(), at[0], GuideLayout.FLOOR_Y, at[1], s.station.yaw(), 0));
    }

    /** Things nobody should keep seeing after a restart (mannequins and bubbles are not saved; this is for safety). */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        if (!guideWorld(e.getWorld())) return;
        for (Entity x : e.getEntities()) {
            String owner = x.getPersistentDataContainer().get(MINE, PersistentDataType.STRING);
            if (owner == null) continue;
            try {
                if (!sessions.containsKey(UUID.fromString(owner))) x.remove();
            } catch (IllegalArgumentException ex) {
                x.remove();
            }
        }
    }

    /** Admin view: who is in the tutorial now (for /관리자 보고서 and tests). */
    public Map<UUID, String> running() {
        Map<UUID, String> out = new HashMap<>();
        sessions.forEach((u, s) -> out.put(u, s.progress.topic().id() + " " + s.progress.position()));
        return out;
    }
}
