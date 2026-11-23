package dev.stsv.economy.secret.fish;

import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Text;
import dev.stsv.economy.fishing.FishSpec;
import dev.stsv.economy.fishing.FishStore;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 눈 없는 물고기 (docs/admin/숨은_비밀/눈_없는_물고기.md; contents/economy/secrets/fish.yml). Long ago the server's first
 * fisher fell into deep water and never came up; since then an eyeless fish bites in the rain. The seven waters are the
 * last waters he remembered. What comes up at the end is the name of the next one to drown (the one who caught it); given
 * back to the water, it is traded for his own: the first fisher's name surfaces a moment and sinks ({@link #firstFisher}).
 * After that the codex's fish tab shows everyone a hidden line with the solver's name ({@link #codexEntry}).
 * <p>
 * Start: in the rain, a reel within start.perfect-ticks of the bite has a tiny chance (once a Korean day) to bring it up
 * instead of the catch (stage 1). Given to the water (dropped in, or right-clicked onto water) it speaks memory s, a riddle
 * for water s ({@link Waters#ORDER}), and the next catch there brings it back, more human (stage s + 1). The seventh water
 * is where it drowned ({@link Drowned}); the first syllables of the seven memories spell it (처음 온 곳 깊은 물), so from
 * final.shortcut-from on a catch there ends it early. There the line brings up a wet name tag with the player's own name
 * and their likeness rises out of the water; given back to the water it is solved, kept final.dry-seconds it dries (a
 * failed day). Thrown away — left on land, burnt, despawned, binned — the fish is lost for good.
 * <p>
 * The fishing module is untouched: this listens to the same PlayerFishEvent at LOW and cancels the catch it replaces (the
 * module's own handler ignores cancelled ones); where a hook is, which water it is, is read as FishSpec reads it.
 * <p>
 * Saved (eco.kv "secret.fish.&lt;uuid&gt;."): stage, since, loose (given to water, waiting in water stage), told (memories
 * spoken), met (the day it was first caught), lost (thrown away: never again), lost.pending (to show at the next join),
 * dry (the day the tag dried). The drowned place: {@link Drowned#KEY}. The first fisher's name, once given back:
 * {@link #FIRST_KEY}.
 */
public final class EyelessFish extends Secret {

    /** On the fish: whose it is, and the stage it was caught for (an older one is only a husk). */
    public static final NamespacedKey FISH_OWNER = new NamespacedKey("economy", "eyeless_owner");
    public static final NamespacedKey FISH_STAGE = new NamespacedKey("economy", "eyeless_stage");
    /** On the wet name tag: whose name, and which pull it came up on (a tag from an earlier pull is only paper). */
    public static final NamespacedKey TAG_OWNER = new NamespacedKey("economy", "wet_name_owner");
    public static final NamespacedKey TAG_NONCE = new NamespacedKey("economy", "wet_name_nonce");
    /** eco.kv: the first fisher's name as it surfaced for the solver (the codex line keeps it) */
    static final String FIRST_KEY = "secret.fish.first-fisher";

    private record Water(String id, String name, List<String> habitats, String memory) {}

    /** A fish or tag lying about as an item: whose, which, since when on dry land (0 = not on land). */
    private static final class Watch {
        final UUID owner;
        final boolean tag;
        long land;

        Watch(UUID owner, boolean tag) {
            this.owner = owner;
            this.tag = tag;
        }
    }

    /** A tag on the line: which pull, since when. */
    private record Tag(String nonce, long at) {}

    private final List<Water> waters = new ArrayList<>();
    private final Map<UUID, Long> bites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> held = new ConcurrentHashMap<>();
    private final Map<UUID, Watch> watched = new ConcurrentHashMap<>();
    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();
    /** the line-gone-heavy omen a release brought, owed at the next bite */
    private final Set<UUID> heavyOwed = ConcurrentHashMap.newKeySet();
    private final Drowned drowned;
    private final Dread dread;
    private final long born = System.currentTimeMillis();

    /** 후속 임무 「물이 부르는 날」 */
    private final FishSequel sequel = new FishSequel(this);

    @Override
    public Sequel sequel() {
        return sequel;
    }

    Drowned drowned() {
        return drowned;
    }

    Dread dread() {
        return dread;
    }

    public EyelessFish(SecretModule m) {
        super(m);
        drowned = new Drowned(this, eco);
        dread = new Dread(this, m, eco);
        if (eco.codex() != null) eco.codex().extra("fish", "secret.fish", this::codexEntry);
    }

    @Override
    public String id() {
        return "fish";
    }

    @Override
    public String name() {
        return "눈없는물고기";
    }

    @Override
    public int stages() {
        return Waters.ORDER.size();
    }

    YamlConfiguration cfg() {
        return cfg;
    }

    @Override
    protected void loaded() {
        waters.clear();
        for (Map<?, ?> w : cfg.getMapList("waters")) {
            Object hs = w.get("habitats");
            List<String> habitats = hs instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            waters.add(new Water(String.valueOf(w.get("id")), String.valueOf(w.get("name")), habitats, String.valueOf(w.get("memory"))));
        }
        if (waters.size() != Waters.ORDER.size()) eco.getLogger().warning("[비밀] fish.yml: waters 는 " + Waters.ORDER.size() + "개여야 함 (지금 " + waters.size() + ")");
        List<String> mem = waters.stream().map(Water::memory).toList();
        if (!Waters.ACROSTIC.equals(Waters.acrostic(mem)))
            eco.getLogger().warning("[비밀] fish.yml: 기억 첫 글자가 \"" + Waters.acrostic(mem) + "\" (마지막 물의 힌트 \"" + Waters.ACROSTIC + "\" 와 다름)");
    }

    // ------------------------------------------------------------------------------------------
    // small things
    // ------------------------------------------------------------------------------------------

    private String text(String key) {
        return cfg.getString(key, "");
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    private boolean loose(UUID u) {
        return m.getLong(this, u, "loose", 0) == 1;
    }

    /** On its way: caught, not yet solved. */
    private boolean going(UUID u) {
        int st = stage(u);
        return st >= 1 && st <= stages();
    }

    private Water water(int stage) {
        return stage >= 1 && stage <= waters.size() ? waters.get(stage - 1) : null;
    }

    private void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, r, ticks);
    }

    private static void sound(Player p, Location at, String key, float volume, float pitch) {
        p.playSound(at, key, SoundCategory.AMBIENT, volume, pitch);
    }

    /** The water a hook is in, as the fishing module reads it (FishSpec.habitat). */
    static String habitat(Block b) {
        boolean underground = b.getLightFromSky() == 0 && b.getY() < b.getWorld().getSeaLevel() - 8;
        return FishSpec.habitat(b.getBiome().getKey().asString(), underground);
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    private void title(Player p, String head, String sub) {
        p.showTitle(Title.title(Text.mm(head), Text.mm(sub), Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(1200))));
    }

    /** "[seconds, line]" pairs (final.rise, final.given). */
    private List<Object[]> timed(String key) {
        List<Object[]> out = new ArrayList<>();
        for (Object o : cfg.getList(key, List.of())) {
            if (!(o instanceof List<?> l) || l.size() < 2) continue;
            try {
                out.add(new Object[]{Double.parseDouble(String.valueOf(l.get(0))), String.valueOf(l.get(1))});
            } catch (NumberFormatException ignored) {
                // a hand-edited line
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // the fish and the tag
    // ------------------------------------------------------------------------------------------

    /** The fish as it is at a stage: its name and look, the memories it spoke so far, bound to its owner. */
    ItemStack fish(UUID u, String owner, int stage) {
        ItemStack it = Items.create("economy:eyeless_fish", 1).orElseGet(() -> new ItemStack(Material.COD));
        List<Map<?, ?>> stages = cfg.getMapList("item.stages");
        Map<?, ?> look = stages.isEmpty() ? Map.of() : stages.get(Math.clamp(stage - 1, 0, stages.size() - 1));
        String name = look.get("name") == null ? "<#9fb8c7>눈 없는 물고기" : String.valueOf(look.get("name"));
        List<String> desc = look.get("lore") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        String color = cfg.getString("item.memory-color", "<#8fa9b8><i>");
        it.editMeta(meta -> {
            meta.itemName(Text.item(name));
            List<Component> lore = new ArrayList<>();
            for (String d : desc) lore.add(Text.item(d));
            if (stage > 1) {
                lore.add(Component.empty());
                for (int i = 0; i < stage - 1 && i < waters.size(); i++) lore.add(Text.item(color + waters.get(i).memory()));
            }
            String bound = text("item.bound-line");
            if (!bound.isEmpty()) {
                lore.add(Component.empty());
                lore.add(Text.item(bound.replace("{player}", owner)));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(FISH_OWNER, PersistentDataType.STRING, u.toString());
            meta.getPersistentDataContainer().set(FISH_STAGE, PersistentDataType.INTEGER, stage);
        });
        return Items.markBought(it);   // the shop, requests and machines refuse it
    }

    /** The wet name tag with their own name, from one pull. */
    ItemStack tag(Player p, String nonce) {
        ItemStack it = Items.create("economy:wet_nametag", 1).orElseGet(() -> new ItemStack(Material.NAME_TAG));
        it.editMeta(meta -> {
            meta.itemName(Text.item(cfg.getString("final.tag-name", "<#7fa0b0>젖은 이름표")));
            List<Component> lore = new ArrayList<>();
            for (String l : cfg.getStringList("final.tag-lore")) lore.add(Text.item(l.replace("{player}", p.getName())));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(TAG_OWNER, PersistentDataType.STRING, p.getUniqueId().toString());
            meta.getPersistentDataContainer().set(TAG_NONCE, PersistentDataType.STRING, nonce);
        });
        return Items.markBought(it);
    }

    private static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    private static String pdc(ItemStack it, NamespacedKey k) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static UUID fishOwner(ItemStack it) {
        return uuid(pdc(it, FISH_OWNER));
    }

    static UUID tagOwner(ItemStack it) {
        return uuid(pdc(it, TAG_OWNER));
    }

    private static int fishStage(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer s = it.getItemMeta().getPersistentDataContainer().get(FISH_STAGE, PersistentDataType.INTEGER);
        return s == null ? 0 : s;
    }

    /** The fish its owner should be holding now (not an old husk, not while it is in the water). */
    private boolean current(ItemStack it) {
        UUID u = fishOwner(it);
        return u != null && going(u) && !loose(u) && fishStage(it) == stage(u);
    }

    /** The tag of the pull going on now. */
    private boolean liveTag(ItemStack it) {
        UUID u = tagOwner(it);
        Tag t = u == null ? null : tags.get(u);
        return t != null && t.nonce().equals(pdc(it, TAG_NONCE));
    }

    /** Takes every copy matching from a player's inventory (and cursor). */
    private static void take(Player p, java.util.function.Predicate<ItemStack> match) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && match.test(it)) inv.setItem(i, null);
        }
        ItemStack cur = p.getItemOnCursor();
        if (match.test(cur)) p.setItemOnCursor(null);
    }

    // ------------------------------------------------------------------------------------------
    // fishing: the start, the fish coming back, the tag
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        switch (e.getState()) {
            case FISHING -> cast(p, e.getHook());
            case BITE -> bite(p, e);
            case CAUGHT_FISH -> caught(p, e);
            default -> { }
        }
    }

    /** The float is out: the solver's dark ripples where it lands; an omen now and then for one on the way. */
    private void cast(Player p, FishHook hook) {
        UUID u = p.getUniqueId();
        if (stage(u) > stages() && cfg.getBoolean("wet-line", true)) {
            int[] n = {0};
            Bukkit.getScheduler().runTaskTimer(eco, task -> {
                if (!hook.isValid() || n[0]++ > 60) {
                    task.cancel();
                } else if (hook.isInWater()) {
                    task.cancel();
                    dread.ripples(hook.getLocation());
                }
            }, 2L, 2L);
            return;
        }
        if (!on() || !open(u) || !going(u) || !dread.ready(u)) return;
        List<String> earned = Waters.omens(Waters.told(stage(u), loose(u)));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (String o : List.of("figure", "name", "bubbles")) {
            if (!earned.contains(o) || r.nextDouble() >= cfg.getDouble("dread." + o, 0.1)) continue;
            dread.used(u);
            later(o.equals("bubbles") ? 50 : 30, () -> {
                if (!p.isOnline()) return;
                if (o.equals("bubbles")) dread.bubbles(p, hook.isValid() ? hook.getLocation() : null);
                else dread.play(p, o);
            });
            return;
        }
    }

    /** The float pulled under: remember when (a perfect reel); now and then the line goes heavy and snaps instead. */
    private void bite(Player p, PlayerFishEvent e) {
        UUID u = p.getUniqueId();
        bites.put(u, (long) Bukkit.getCurrentTick());
        if (!on() || !open(u) || !going(u) || !loose(u) || tags.containsKey(u)) return;
        if (Waters.told(stage(u), true) < 2 || progresses(u, e.getHook().getLocation().getBlock())) return;
        boolean owed = heavyOwed.contains(u);
        if (!owed && (!dread.ready(u) || ThreadLocalRandom.current().nextDouble() >= cfg.getDouble("dread.heavy", 0.15))) return;
        heavyOwed.remove(u);
        dread.used(u);
        e.setCancelled(true);
        dread.heavy(p, e.getHook());
    }

    /** Whether a catch from this block would bring the fish (or the tag) up for them now. */
    private boolean progresses(UUID u, Block b) {
        int st = stage(u);
        if (!going(u) || !loose(u)) return false;
        if (place(b) == Waters.Near.HERE && Waters.mayEnd(st, true, cfg.getInt("final.shortcut-from", 4))) return true;
        Water w = water(st);
        return st < stages() && w != null && Waters.matches(w.habitats(), habitat(b));
    }

    private Waters.Near place(Block b) {
        Optional<Drowned.Place> pl = drowned.place();
        if (pl.isEmpty()) return Waters.Near.FAR;
        return Waters.near(pl.get().distance(b), cfg.getDouble("final.radius", 20), cfg.getDouble("final.close", 64), cfg.getDouble("final.near", 300));
    }

    private void caught(Player p, PlayerFishEvent e) {
        UUID u = p.getUniqueId();
        Long bite = bites.remove(u);
        if (!on() || !open(u) || !(e.getCaught() instanceof Item)) return;
        Block b = e.getHook().getLocation().getBlock();
        int st = stage(u);
        if (st == 0) {
            begin(p, e, bite == null ? 0 : bite);
            return;
        }
        if (st > stages() || !loose(u) || tags.containsKey(u)) return;
        Waters.Near near = place(b);
        int from = cfg.getInt("final.shortcut-from", 4);
        if (near == Waters.Near.HERE && Waters.mayEnd(st, true, from)) {
            if (m.today(u).equals(m.get(this, u, "dry"))) {
                m.haunt().whisper(p, text("final.dried-today"));
                return;
            }
            pullTag(p, e);
            return;
        }
        Water w = water(st);
        if (st < stages() && w != null && Waters.matches(w.habitats(), habitat(b))) {
            back(p, e, st + 1);
            return;
        }
        if (drowned.place().isEmpty()) drowned.find(null);
        // where it drowned: how near they are (all the way at the last stage; only close by for the ones who read ahead)
        if (st == stages()) {
            switch (near) {
                case CLOSE -> m.haunt().whisper(p, text("final.near-close"));
                case NEAR -> m.haunt().whisper(p, text("final.near-far"));
                default -> m.haunt().whisper(p, text("final.away"));
            }
        } else if (st >= from && near == Waters.Near.CLOSE) {
            m.haunt().whisper(p, text("final.near-close"));
        }
    }

    /** What was on the line goes; this comes up instead (into the inventory, as a catch does). */
    private void replace(Player p, PlayerFishEvent e, ItemStack it) {
        e.setCancelled(true);
        if (e.getCaught() != null) e.getCaught().remove();
        e.getHook().remove();
        give(p, it);
    }

    /** The very rare first one: raining, a perfect reel, once a day. */
    private void begin(Player p, PlayerFishEvent e, long bite) {
        UUID u = p.getUniqueId();
        if (m.get(this, u, "lost") != null || m.today(u).equals(m.get(this, u, "met"))) return;
        if (!m.test(u) && cfg.getBoolean("start.need-rain", true) && !p.getWorld().hasStorm()) return;
        if (!Waters.perfect(bite, Bukkit.getCurrentTick(), cfg.getInt("start.perfect-ticks", 8))) return;
        if (!m.roll(u, cfg.getDouble("start.chance", 0.0005))) return;
        first(p);
        replace(p, e, fish(u, p.getName(), 1));
        caughtScene(p);
    }

    private void first(Player p) {
        UUID u = p.getUniqueId();
        m.stage(this, u, 1);
        m.set(this, u, "loose", 0);
        m.set(this, u, "told", 0);
        m.set(this, u, "met", m.today(u));
        m.remove(this, u, "lost");
        m.remove(this, u, "lost.pending");
        m.remove(this, u, "dry");
        eco.getLogger().info("[비밀] 눈 없는 물고기: " + p.getName() + " 이(가) 낚음");
    }

    private void caughtScene(Player p) {
        title(p, cfg.getString("start.title", "<#9fb8c7>???"), cfg.getString("start.subtitle", ""));
        sound(p, p.getLocation(), "entity.cod.flop", 0.7f, 0.5f);
        later(20, () -> {
            if (p.isOnline()) sound(p, p.getLocation(), "ambient.underwater.loop.additions.rare", 0.8f, 0.6f);
        });
    }

    /** The fish again, in the water its memory named: a little more human. */
    private void back(Player p, PlayerFishEvent e, int stage) {
        UUID u = p.getUniqueId();
        m.stage(this, u, stage);
        m.set(this, u, "loose", 0);
        ItemStack it = fish(u, p.getName(), stage);
        replace(p, e, it);
        title(p, cfg.getString("returned.title", "{name}").replace("{name}", Items.mmName(it)), text("returned.subtitle"));
        sound(p, p.getLocation(), "entity.cod.flop", 0.7f, 0.45f);
        sound(p, p.getLocation(), "entity.player.breath", 0.35f, 0.5f);
    }

    /** The last water: their own name comes up on the line, and they see themselves rise from the water. */
    private void pullTag(Player p, PlayerFishEvent e) {
        UUID u = p.getUniqueId();
        Location at = e.getHook().getLocation();
        String nonce = UUID.randomUUID().toString();
        tags.put(u, new Tag(nonce, System.currentTimeMillis()));
        replace(p, e, tag(p, nonce));
        title(p, cfg.getString("final.tag-name", "<#7fa0b0>젖은 이름표"), "<gray>" + p.getName());
        dread.rise(p, at);
        for (Object[] l : timed("final.rise")) {
            later(Math.round((Double) l[0] * 20), () -> {
                if (p.isOnline() && tags.containsKey(u)) m.haunt().whisper(p, ((String) l[1]).replace("{player}", p.getName()));
            });
        }
        eco.getLogger().info("[비밀] 눈 없는 물고기: " + p.getName() + " 이(가) 젖은 이름표를 낚음");
    }

    // ------------------------------------------------------------------------------------------
    // giving it to the water
    // ------------------------------------------------------------------------------------------

    /** Right-click water (within reach) with the fish or the tag in hand: it goes into the water. */
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (!on() || e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || fishOwner(it) == null && tagOwner(it) == null) return;
        Player p = e.getPlayer();
        RayTraceResult r = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 5, FluidCollisionMode.ALWAYS, true);
        if (r == null || r.getHitBlock() == null || r.getHitBlock().getType() != Material.WATER) return;
        e.setCancelled(true);
        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        ItemStack copy = it.clone();
        p.getInventory().setItem(hand, null);
        intoWater(copy, r.getHitPosition().toLocation(p.getWorld()));
    }

    /** A fish or tag reached water (thrown in, or lying in it). */
    private void intoWater(ItemStack it, Location at) {
        UUID fo = fishOwner(it);
        if (fo != null) {
            Player o = Bukkit.getPlayer(fo);
            if (current(it) && on()) {
                release(fo, o, at);
            } else if (o != null && o.getWorld() == at.getWorld() && o.getLocation().distanceSquared(at) < 64 * 64) {
                dread.splash(o, at);
                m.haunt().whisper(o, text("release.stale"));
            }
            return;
        }
        UUID to = tagOwner(it);
        Player o = to == null ? null : Bukkit.getPlayer(to);
        if (o == null) return;
        if (liveTag(it) && on()) {
            given(o, at);
        } else if (o.getWorld() == at.getWorld()) {
            dread.splash(o, at);
        }
    }

    /** Given back to the water: it speaks this stage's memory and waits in the water it names; a new omen comes. */
    private void release(UUID u, Player p, Location at) {
        int st = stage(u);
        m.set(this, u, "loose", 1);
        if (p == null || !p.isOnline()) return;   // told at the next join
        dread.splash(p, at);
        later(40, () -> speak(p));
        omenFor(p, st, at);
    }

    /** Says the memory of the stage they are at (once loose), and keeps that it was told. */
    private void speak(Player p) {
        UUID u = p.getUniqueId();
        if (!p.isOnline() || !going(u) || !loose(u)) return;
        int st = stage(u);
        Water w = water(st);
        if (w == null) return;
        p.sendMessage(Text.mm(cfg.getString("release.memory-format", "<#8fa9b8><i>…{memory}").replace("{memory}", w.memory())));
        sound(p, p.getLocation(), "ambient.underwater.loop.additions", 0.7f, 0.7f);
        m.set(this, u, "told", st);
        if (st == stages()) later(60, () -> {
            if (p.isOnline()) p.sendMessage(Text.mm(text("release.after-last")));
        });
    }

    /** The omen this release brings (one more each time), shown once now; the heavy line waits for the next bite. */
    private void omenFor(Player p, int told, Location at) {
        UUID u = p.getUniqueId();
        String o = Waters.newOmen(told);
        later(120, () -> {
            if (!p.isOnline() || !going(u)) return;
            dread.used(u);
            if (o == null) {
                // past the fifth: the figure nearer, then only bubbles where it went down
                if (told == stages() - 1) {
                    if (!dread.figure(p, 8, 16)) dread.figure(p, 6, 30);
                    later(60, () -> {
                        if (p.isOnline()) dread.name(p);
                    });
                } else {
                    dread.bubbles(p, at);
                }
            } else if (o.equals("heavy")) {
                heavyOwed.add(u);
            } else if (o.equals("bubbles")) {
                dread.bubbles(p, at);
            } else {
                dread.play(p, o);
            }
        });
    }

    /** The name given to the water: solved. In its place the first fisher's own name comes up a moment, and sinks. */
    private void given(Player p, Location at) {
        UUID u = p.getUniqueId();
        tags.remove(u);
        dread.splash(p, at);
        dread.dry(u);
        String first = firstFisher();
        if (!m.test(u)) eco.kv().set(FIRST_KEY, first);
        surfaces(p, at, first);
        double end = 0;
        for (Object[] l : timed("final.given")) {
            end = Math.max(end, (Double) l[0]);
            later(Math.round((Double) l[0] * 20), () -> {
                if (p.isOnline()) m.haunt().whisper(p, ((String) l[1]).replace("{player}", p.getName()).replace("{first}", first));
            });
        }
        later(Math.round(end * 20) + 40, () -> {
            if (!p.isOnline()) return;
            p.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, at, 30, 0.4, 0.2, 0.4, 0.02);
            m.solve(this, p);
            m.remove(this, u, "loose");
        });
        eco.getLogger().info("[비밀] 눈 없는 물고기: " + p.getName() + " 이(가) 이름을 물에 줌");
    }

    /** The first fisher's name on the water, final.surface-delay seconds after the tag went in. */
    private void surfaces(Player p, Location at, String first) {
        String mm = cfg.getString("final.surface-name", "<#c4ab9b>{first}").replace("{first}", esc(first));
        later(Math.round(cfg.getDouble("final.surface-delay", 2) * 20), () -> {
            if (p.isOnline()) dread.nameSurfaces(p, at, mm);
        });
    }

    /**
     * The first fisher: whoever has the earliest catch in the fishing records (the codex's catch log), else
     * final.first-fisher. Once given back to the water it is kept ({@link #FIRST_KEY}), so a records reset does not change it.
     */
    String firstFisher() {
        String kept = eco.kv().get(FIRST_KEY);
        if (kept != null && !kept.isBlank()) return kept;
        UUID u = null;
        try {
            FishStore store = eco.fishing() == null ? null : eco.fishing().store();
            if (store != null) {
                Map<UUID, Long> firsts = new java.util.HashMap<>();
                for (UUID x : store.players()) {
                    long f = Long.MAX_VALUE;
                    for (FishStore.Entry en : store.of(x).values()) if (en.first > 0) f = Math.min(f, en.first);
                    if (f != Long.MAX_VALUE) firsts.put(x, f);
                }
                u = earliest(firsts);
            }
        } catch (RuntimeException ex) {
            eco.getLogger().warning("[비밀] 눈 없는 물고기: 낚시 기록을 읽지 못함 " + ex);
        }
        String name = u == null ? null : Names.plain(u);
        return name == null || name.isBlank() || name.equals("?") ? cfg.getString("final.first-fisher", "이름 모를 낚시꾼") : name;
    }

    /** Who caught first (player -> their first catch's time); ties go to the smaller id. Null if nobody. */
    static UUID earliest(Map<UUID, Long> firsts) {
        UUID best = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<UUID, Long> e : firsts.entrySet()) {
            long t = e.getValue();
            if (t < at || t == at && best != null && e.getKey().compareTo(best) < 0) {
                best = e.getKey();
                at = t;
            }
        }
        return best;
    }

    /**
     * The codex's fish tab, for everyone once it is solved: "눈 없는 물고기 — 이름을 돌려준 자 ○○" (after the tab's own
     * entries; never counted). Null (hidden) until then.
     */
    private ItemStack codexEntry(Player viewer) {
        Optional<UUID> solver = m.solver(this);
        if (solver.isEmpty()) return null;
        String who = esc(Names.plain(solver.get()));
        String first = esc(Optional.ofNullable(eco.kv().get(FIRST_KEY)).orElse(cfg.getString("final.first-fisher", "이름 모를 낚시꾼")));
        ItemStack it = Items.create("economy:eyeless_fish", 1).orElseGet(() -> new ItemStack(Material.COD));
        it.editMeta(meta -> {
            meta.itemName(Text.item(cfg.getString("codex.name", "<#c4ab9b>눈 없는 물고기 <dark_gray>— <gray>이름을 돌려준 자 <white>{player}")
                    .replace("{player}", who).replace("{first}", first)));
            List<Component> lore = new ArrayList<>();
            for (String l : cfg.getStringList("codex.lore")) lore.add(Text.item(l.replace("{player}", who).replace("{first}", first)));
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(false);
        });
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // the fish (or tag) as an item lying about
    // ------------------------------------------------------------------------------------------

    private void watch(Item ent) {
        ItemStack it = ent.getItemStack();
        UUID fo = fishOwner(it), to = tagOwner(it);
        if (fo == null && to == null) return;
        ent.setOwner(fo != null ? fo : to);   // nobody else picks it up
        watched.putIfAbsent(ent.getUniqueId(), new Watch(fo != null ? fo : to, fo == null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        watch(e.getEntity());
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) if (x instanceof Item i) watch(i);
    }

    /** Left on land where nobody is (the chunk goes): thrown away. */
    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity x : e.getEntities()) {
            if (!(x instanceof Item i) || watched.remove(i.getUniqueId()) == null) continue;
            if (current(i.getItemStack()) && !i.isInWater()) lose(fishOwner(i.getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        Watch w = watched.get(e.getItem().getUniqueId());
        if (w == null) return;
        if (!(e.getEntity() instanceof Player p) || !p.getUniqueId().equals(w.owner)) {
            e.setCancelled(true);
            return;
        }
        watched.remove(e.getItem().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent e) {
        if (watched.containsKey(e.getItem().getUniqueId())) e.setCancelled(true);
    }

    /** Dying is not throwing it away: the owner keeps the fish they carry (else it would lie on land while they respawn). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        UUID u = e.getPlayer().getUniqueId();
        for (var it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack d = it.next();
            if (!u.equals(fishOwner(d)) || !current(d)) continue;
            it.remove();
            e.getItemsToKeep().add(d);
        }
    }

    /** /쓰레기통 (the core's bin): the fish thrown in is thrown away. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBin(InventoryCloseEvent e) {
        var holder = e.getInventory().getHolder(false);
        if (holder == null || !holder.getClass().getName().endsWith("TrashCan$Bin")) return;
        for (ItemStack it : e.getInventory().getContents()) if (it != null && current(it)) lose(fishOwner(it));
    }

    /** The codex will not take it: "??? · 등록할 수 없음" instead of its usual line. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCodex(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder(false) instanceof MenuView v)) return;
        if (!"economy:codex".equals(v.spec().id()) || fishOwner(e.getCurrentItem()) == null) return;
        String line = cfg.getString("codex-line", "<gray>??? · 등록할 수 없음");
        Bukkit.getScheduler().runTask(eco, () -> {
            if (p.isOnline()) HudModule.flash(p, Text.mm(line));
        });
    }

    /** Each second: the fish and tags lying about. */
    private void items() {
        long now = System.currentTimeMillis();
        long landMs = cfg.getLong("lose.land-seconds", 15) * 1000L;
        for (Map.Entry<UUID, Watch> en : new ArrayList<>(watched.entrySet())) {
            Watch w = en.getValue();
            Entity x = Bukkit.getEntity(en.getKey());
            if (!(x instanceof Item ent) || !ent.isValid()) {
                // gone without being picked up: burnt, despawned, destroyed
                watched.remove(en.getKey());
                if (!w.tag && x == null && going(w.owner) && !loose(w.owner) && lostFish(w.owner)) lose(w.owner);
                continue;
            }
            ItemStack it = ent.getItemStack();
            if (ent.isInWater()) {
                watched.remove(en.getKey());
                Location at = ent.getLocation();
                ent.remove();
                intoWater(it, at);
                continue;
            }
            if (!ent.isOnGround()) {
                w.land = 0;
                continue;
            }
            if (w.tag || !current(it)) continue;
            if (w.land == 0) {
                w.land = now;
                Player o = Bukkit.getPlayer(w.owner);
                if (o != null) m.haunt().whisper(o, text("lose.land-whisper"));
                ent.getWorld().playSound(ent.getLocation(), "entity.cod.flop", SoundCategory.AMBIENT, 0.5f, 0.6f);
            } else if (now - w.land >= landMs) {
                watched.remove(en.getKey());
                ent.getWorld().spawnParticle(Particle.FALLING_WATER, ent.getLocation().add(0, 0.3, 0), 10, 0.2, 0.1, 0.2, 0);
                ent.remove();
                lose(w.owner);
            }
        }
    }

    /** Whether the owner (if online) no longer has the current fish (an item gone missing was it). */
    private boolean lostFish(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return true;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && current(it)) return false;
        return !current(p.getItemOnCursor());
    }

    /** Thrown away: the water rises around them, and it never comes again for them. */
    private void lose(UUID u) {
        if (u == null || !on() || !going(u)) return;
        m.stage(this, u, 0);
        m.set(this, u, "lost", 1);
        m.remove(this, u, "loose");
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            take(p, it -> fishOwner(it) != null && u.equals(fishOwner(it)));
            dread.rising(p);
        } else {
            m.set(this, u, "lost.pending", 1);
        }
        eco.getLogger().info("[비밀] 눈 없는 물고기: " + (p == null ? u : p.getName()) + " 이(가) 물고기를 버림 (영영 실패)");
    }

    /** The tag kept too long (or carried away): it dries; "…다음엔 네 차례야."; no more today. */
    private void dryOut(Player p, boolean scene) {
        UUID u = p.getUniqueId();
        Tag t = tags.remove(u);
        if (t == null) return;
        take(p, it -> t.nonce().equals(pdc(it, TAG_NONCE)));
        m.set(this, u, "dry", m.today(u));
        dread.dry(u);
        if (!scene) return;
        String line = text("final.dry");
        p.sendMessage(Text.mm(line));
        m.haunt().whisper(p, line);
        Location back = p.getLocation();
        back.subtract(back.getDirection().setY(0).normalize().multiply(2));
        sound(p, back, "entity.drowned.ambient_water", 0.7f, 0.5f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        items();
        long now = System.currentTimeMillis();
        long dryMs = cfg.getLong("final.dry-seconds", 60) * 1000L;
        for (Map.Entry<UUID, Tag> en : new ArrayList<>(tags.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                tags.remove(en.getKey());
                continue;
            }
            long age = now - en.getValue().at();
            if (age >= dryMs) {
                dryOut(p, true);
                continue;
            }
            if (age / 1000 == dryMs / 2000) m.haunt().whisper(p, text("final.drying"));
            p.spawnParticle(Particle.FALLING_WATER, p.getLocation().add(0, 1.1, 0), 2, 0.25, 0.1, 0.25, 0);
        }
        int every = Math.max(2, cfg.getInt("hold-every", 9));
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            boolean going = going(u);
            if (going) dread.tick(p);
            ItemStack main = p.getInventory().getItemInMainHand(), off = p.getInventory().getItemInOffHand();
            UUID owner = fishOwner(main) != null ? fishOwner(main) : fishOwner(off);
            if (owner != null && now - held.getOrDefault(u, 0L) >= every * 1000L) {
                held.put(u, now);
                if (!owner.equals(u)) {
                    m.haunt().whisper(p, text("not-yours"));
                } else if (current(fishOwner(main) != null ? main : off)) {
                    m.haunt().whisper(p, text("hold-whisper"));
                    if (ThreadLocalRandom.current().nextInt(3) == 0) sound(p, p.getLocation(), "entity.cod.flop", 0.25f, 0.5f);
                }
            }
            // the wet footprints: once earned, now and then while they walk in the rain (or dripping from the water)
            if (going && open(u) && Waters.omens(Waters.told(stage(u), loose(u))).contains("footprints") && dread.ready(u)
                    && (p.isInRain() || p.isInWater() || m.test(u))
                    && ThreadLocalRandom.current().nextDouble() < cfg.getDouble("dread.footprints", 0.004)) {
                dread.used(u);
                dread.footprints(p);
            }
        }
        // the drowned place: found once, a minute after start (a known place costs nothing)
        if (now - born > 60_000 && !drowned.searching() && drowned.place().isEmpty()) drowned.find(null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (m.get(this, u, "lost.pending") != null) {
            m.remove(this, u, "lost.pending");
            later(100, () -> {
                if (p.isOnline()) dread.rising(p);
            });
        } else if (going(u) && loose(u) && m.getLong(this, u, "told", 0) < stage(u)) {
            later(100, () -> speak(p));   // given to the water while they were away
        }
    }

    /** Leaving with the tag on the line: it dries (today is over). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        if (tags.containsKey(e.getPlayer().getUniqueId())) dryOut(e.getPlayer(), false);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public void start(Player p) {
        first(p);
        UUID u = p.getUniqueId();
        take(p, it -> u.equals(fishOwner(it)) || u.equals(tagOwner(it)));
        give(p, fish(u, p.getName(), 1));
        caughtScene(p);
    }

    /** Stage n: the fish of that stage in hand (its name and the memories before it), not yet given to the water. */
    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        take(p, it -> u.equals(fishOwner(it)) || u.equals(tagOwner(it)));
        if (stage <= 0) {
            super.jump(p, 0);
            for (String k : List.of("loose", "told", "lost", "lost.pending", "dry", "met")) m.remove(this, u, k);
            return;
        }
        first(p);
        super.jump(p, stage);
        m.set(this, u, "told", stage - 1);
        give(p, fish(u, p.getName(), stage));
        if (stage >= cfg.getInt("final.shortcut-from", 4)) drowned.find(null);
    }

    @Override
    public void cleanup(UUID player) {
        dread.cleanup(player);
        bites.remove(player);
        held.remove(player);
        heavyOwed.remove(player);
        Tag t = tags.remove(player);
        Player p = Bukkit.getPlayer(player);
        if (t != null && p != null) take(p, it -> t.nonce().equals(pdc(it, TAG_NONCE)));
    }

    @Override
    public String status(UUID u) {
        StringBuilder b = new StringBuilder();
        int st = stage(u);
        if (m.get(this, u, "lost") != null) b.append("<red>버림 (영영 실패)</red> · ");
        if (going(u)) {
            Water w = water(st);
            String wn = w == null ? "?" : w.name() + (Waters.DROWNED.equals(w.id()) ? "" : " " + w.habitats());
            b.append(loose(u) ? "놓아줌 → 찾는 물 <white>" + wn + "</white>" : "물고기 손에 → 놓아주면 기억 " + st + " (" + wn + ")");
            b.append(" · 들은 기억 ").append(m.getLong(this, u, "told", 0)).append('/').append(stages());
            if (tags.containsKey(u)) b.append(" · <yellow>이름표 마르는 중");
            if (m.today(u).equals(m.get(this, u, "dry"))) b.append(" · 오늘 마름");
        }
        b.append(" · 오늘 낚음 ").append(m.today(u).equals(m.get(this, u, "met")) ? "있음" : "없음");
        b.append(" · 빠진 곳 ").append(drowned.place().map(Drowned.Place::toString).orElse(drowned.searching() ? "찾는 중" : "모름"));
        return b.toString();
    }

    @Override
    public List<String> scenes() {
        return List.of("시작", "속삭임", "놓아주기", "거품", "무거운줄", "발자국", "형체", "이름표", "떠오름", "돌려줌", "실패", "빠진곳", "빠진곳다시", "빠진곳여기");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "시작" -> caughtScene(p);
            case "속삭임" -> m.haunt().whisper(p, text("hold-whisper"));
            case "놓아주기" -> {
                Location at = Dread.waterNear(p, 8);
                if (at == null) at = p.getLocation().add(p.getLocation().getDirection().multiply(2));
                dread.splash(p, at);
                int st = Math.clamp(going(u) ? stage(u) : 1, 1, stages());
                Water w = water(st);
                later(40, () -> {
                    if (p.isOnline() && w != null)
                        p.sendMessage(Text.mm(cfg.getString("release.memory-format", "<#8fa9b8><i>…{memory}").replace("{memory}", w.memory())));
                });
            }
            case "거품" -> dread.bubbles(p, null);
            case "무거운줄" -> dread.heavy(p, p.getFishHook());
            case "발자국" -> dread.footprints(p);
            case "형체" -> {
                if (!dread.figure(p, 18, 34) && !dread.figure(p, 6, 48)) admin.sendMessage(Text.msg("<red>6~48칸 안에 서 있을 만한 물이 없어요 (깊이 2칸 이상)"));
            }
            case "이름표" -> {
                give(p, tag(p, "scene"));
                title(p, cfg.getString("final.tag-name", "<#7fa0b0>젖은 이름표"), "<gray>" + p.getName());
                admin.sendMessage(Text.msg("<gray>보기용 이름표 (진행과 상관없음, 물에 줘도 아무 일 없음)"));
            }
            case "떠오름" -> {
                Location at = Dread.waterNear(p, 10);
                if (at == null) {
                    admin.sendMessage(Text.msg("<red>10칸 안에 물이 없어요"));
                    return true;
                }
                dread.rise(p, at);
                for (Object[] l : timed("final.rise"))
                    later(Math.round((Double) l[0] * 20), () -> {
                        if (p.isOnline()) m.haunt().whisper(p, ((String) l[1]).replace("{player}", p.getName()));
                    });
            }
            case "돌려줌" -> {
                Location at = Dread.waterNear(p, 10);
                if (at == null) {
                    admin.sendMessage(Text.msg("<red>10칸 안에 물이 없어요"));
                    return true;
                }
                String first = firstFisher();
                dread.splash(p, at);
                surfaces(p, at, first);
                for (Object[] l : timed("final.given"))
                    later(Math.round((Double) l[0] * 20), () -> {
                        if (p.isOnline()) m.haunt().whisper(p, ((String) l[1]).replace("{player}", p.getName()).replace("{first}", first));
                    });
                admin.sendMessage(Text.msg("<gray>첫 낚시꾼: <white>" + esc(first) + "</white> (진행은 안 바뀜)"));
            }
            case "실패" -> dread.rising(p);
            case "빠진곳여기" -> {
                drowned.setHere(p.getLocation());
                admin.sendMessage(Text.msg("<green>내가 빠진 곳을 " + p.getName() + " 의 자리로 정했어요: <white>" + drowned.place().map(Drowned.Place::toString).orElse("?")
                        + "</white> <gray>(반경 " + cfg.getInt("final.radius", 20) + "칸 안에서 낚으면 돼요)"));
            }
            case "빠진곳", "빠진곳다시" -> {
                if (word.equals("빠진곳다시")) {
                    if (drowned.searching()) {
                        admin.sendMessage(Text.msg("<gray>이미 찾는 중"));
                        return true;
                    }
                    drowned.forget();
                }
                Optional<Drowned.Place> pl = drowned.place();
                if (pl.isPresent()) {
                    admin.sendMessage(Text.msg("<green>내가 빠진 곳: <white>" + pl.get() + "</white> <gray>(반경 " + cfg.getInt("final.radius", 20) + "칸)"));
                    return true;
                }
                admin.sendMessage(Text.msg("<gray>스폰 둘레에서 가장 깊은 물을 찾는 중… (몇 초~1분)"));
                drowned.find(() -> admin.sendMessage(Text.msg("<green>내가 빠진 곳: <white>" + drowned.place().map(Drowned.Place::toString).orElse("?"))));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The settings' keys the code reads (tests). */
    static Set<String> keys() {
        return new HashSet<>(List.of("start.chance", "start.perfect-ticks", "hold-whisper", "codex-line", "item.stages", "waters",
                "lose.line", "final.radius", "final.shortcut-from", "final.dry-seconds", "final.rise", "final.given", "rewards.money",
                "rewards.title", "solved-line", "final.first-fisher", "final.surface-name", "codex.name"));
    }
}
