package dev.stsv.economy.secret.fish;

import dev.stsv.core.util.Text;
import dev.stsv.economy.fishing.FishingModule;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState.State;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 후속 임무 「물이 부르는 날」 (docs/admin/숨은_비밀/눈_없는_물고기.md "후속 임무"; fish.yml {@code sequel:}). 어려움.
 * <p>
 * Once a week, on a rainy day (or late in a dry week), when the one who gave the name back casts a line, the float is
 * quietly pulled one way: toward a water some way off — a real pond one can stand by (still water, {@link Drowned#pool},
 * with dry ground at its edge), found among the chunks that already exist. No coordinates; the pull at every cast and a
 * faint word of how near. Fishing there, three clean catches in a row (reeled within perfect-ticks of the bite) within
 * the window bring up 이름을 잃은 것: one of the pool, another each week. Given back to the water (dropped in, or
 * right-clicked onto water) it says what it still remembered: the reward, and for the rest of the week a small chance
 * more of a rare bite. Too slow, and it sinks until next week.
 * <p>
 * The fishing module is untouched: there is no timing game in it to make harder, so the difficulty is this mission's own
 * (the streak and the window); the catch is replaced as the secret replaces one (the event cancelled before the module's
 * handler); the week's perk uses the module's public {@code force} (one rare bite), rolled per catch.
 * <p>
 * Offered: called, looking for the water. Active step 1: at the water, the window runs. Step 2: the nameless thing in
 * hand. Step 3: given back, its memory being told. Notes: water ("world x y z"), variant, week, began (ms), streak,
 * warned. Kept: recent (the last weeks' variants), perk (the week the perk is for).
 */
public final class FishSequel extends Sequel {

    /** On 이름을 잃은 것: whose mission, and which week (another week's is only a husk). */
    static final NamespacedKey NAMELESS = new NamespacedKey("economy", "nameless_owner");
    static final NamespacedKey NAMELESS_WEEK = new NamespacedKey("economy", "nameless_week");

    /** One nameless thing of the pool. */
    record Thing(String id, String name, List<String> lore, String memory) {}

    /** A search for the water going on for one player. */
    private static final class Search {
        BukkitTask task;
    }

    private final EyelessFish fish;
    private final Map<UUID, Long> bites = new ConcurrentHashMap<>();
    private final Map<UUID, Search> searches = new ConcurrentHashMap<>();
    /** not looked for again before (ms): a search that found nothing */
    private final Map<UUID, Long> searchAfter = new ConcurrentHashMap<>();
    private final Map<UUID, Long> said = new ConcurrentHashMap<>(), without = new ConcurrentHashMap<>();
    /** nameless things lying about as items: entity -> owner */
    private final Map<UUID, UUID> dropped = new ConcurrentHashMap<>();
    /** players whose next bite was made rare by the perk (so only ours is taken back) */
    private final Set<UUID> forced = ConcurrentHashMap.newKeySet();
    private long beats;

    FishSequel(EyelessFish fish) {
        super(fish);
        this.fish = fish;
    }

    @Override
    public String id() {
        return "물이 부르는 날";
    }

    // ------------------------------------------------------------------------------------------
    // small things
    // ------------------------------------------------------------------------------------------

    List<Thing> things() {
        List<Thing> out = new ArrayList<>();
        for (Map<?, ?> t : cfg().getMapList("things")) {
            if (t.get("id") == null) continue;
            List<String> lore = t.get("lore") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            Object name = t.get("name"), memory = t.get("memory");
            out.add(new Thing(String.valueOf(t.get("id")), name == null ? "<#9fb8c7>이름을 잃은 것" : String.valueOf(name), lore,
                    memory == null ? "…기억나지 않아." : String.valueOf(memory)));
        }
        if (out.isEmpty()) out.add(new Thing("nameless", "<#9fb8c7>이름을 잃은 것", List.of("<gray>눈도, 이름도 없다."), "…기억나지 않아. 그래도 고마워."));
        return out;
    }

    private Thing thing(UUID u) {
        List<Thing> all = things();
        return all.get(Math.clamp(getLong(u, "variant", 0), 0, all.size() - 1));
    }

    private void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, r, ticks);
    }

    private static void sound(Player p, Location at, String key, float volume, float pitch) {
        p.playSound(at, key, SoundCategory.AMBIENT, volume, pitch);
    }

    /** A whisper, not more often than every gap seconds (0: always). */
    private void whisper(Player p, String key, String def, int gap) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (gap > 0 && now - said.getOrDefault(u, 0L) < gap * 1000L) return;
        said.put(u, now);
        String l = line(key, def);
        if (!l.isEmpty()) m.haunt().whisper(p, l);
    }

    /** The water of this player's mission, or null (not found yet, or its world is gone). */
    private Location water(UUID u) {
        Object[] d = Waters.decode(get(u, "water"));
        World w = d == null ? null : Bukkit.getWorld((String) d[0]);
        return w == null ? null : new Location(w, (Integer) d[1] + 0.5, (Integer) d[2], (Integer) d[3] + 0.5);
    }

    private static double flat(Location a, Location b) {
        return a.getWorld() != b.getWorld() ? -1 : Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private Waters.Near near(Location at, Location water) {
        return Waters.near(flat(at, water), cfg().getDouble("radius", 12), cfg().getDouble("close", 48), cfg().getDouble("near", 160));
    }

    // ------------------------------------------------------------------------------------------
    // the nameless thing
    // ------------------------------------------------------------------------------------------

    private static String pdc(ItemStack it, NamespacedKey k) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    static boolean isNameless(ItemStack it) {
        return pdc(it, NAMELESS) != null;
    }

    /** The one this player should be holding now (this week's, while it is in their hands). */
    private boolean mine(ItemStack it, UUID u) {
        if (!u.toString().equals(pdc(it, NAMELESS))) return false;
        String week = get(u, "week");
        return week != null && week.equals(pdc(it, NAMELESS_WEEK)) && state(u) == State.ACTIVE && step(u) == 2;
    }

    private ItemStack make(UUID u, Thing t) {
        ItemStack it = Items.create("economy:eyeless_fish", 1).orElseGet(() -> new ItemStack(Material.COD));
        String week = get(u, "week", week(u));
        it.editMeta(meta -> {
            meta.setMaxStackSize(1);
            meta.itemName(Text.item(t.name()));
            List<Component> lore = new ArrayList<>();
            for (String l : t.lore()) lore.add(Text.item(l));
            for (String l : cfg().getStringList("item.lore")) lore.add(Text.item(l));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(NAMELESS, PersistentDataType.STRING, u.toString());
            meta.getPersistentDataContainer().set(NAMELESS_WEEK, PersistentDataType.STRING, week);
        });
        return Items.markBought(it);   // the shop, requests and machines refuse it
    }

    private boolean has(Player p) {
        UUID u = p.getUniqueId();
        for (ItemStack it : p.getInventory().getContents()) if (mine(it, u)) return true;
        if (mine(p.getItemOnCursor(), u)) return true;
        for (Map.Entry<UUID, UUID> en : dropped.entrySet()) {
            if (!en.getValue().equals(u)) continue;
            Entity x = Bukkit.getEntity(en.getKey());
            if (x instanceof Item i && i.isValid() && mine(i.getItemStack(), u)) return true;
        }
        return false;
    }

    /** Takes the nameless things a player carries: every one, or only the husks (another week's, or not in hand now). */
    private void take(Player p, boolean all) {
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isNameless(it) && (all || !mine(it, u))) inv.setItem(i, null);
        }
        ItemStack cur = p.getItemOnCursor();
        if (isNameless(cur) && (all || !mine(cur, u))) p.setItemOnCursor(null);
    }

    /** Their nameless things lying about go too. */
    private void takeDropped(UUID u) {
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(dropped.entrySet())) {
            if (!en.getValue().equals(u)) continue;
            dropped.remove(en.getKey());
            Entity x = Bukkit.getEntity(en.getKey());
            if (x != null) x.remove();
        }
    }

    // ------------------------------------------------------------------------------------------
    // looking for the water
    // ------------------------------------------------------------------------------------------

    /** Dry ground to stand on within five blocks of a pond's middle, about level with the water. */
    static boolean shore(World w, int x, int top, int z) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) continue;
                int y = w.getHighestBlockYAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (y < top - 1 || y > top + 2) continue;
                Block g = w.getBlockAt(x + dx, y, z + dz);
                Material mat = g.getType();
                if (!mat.isSolid() || !mat.isOccluding() || g.isLiquid()) continue;   // not ice, glass, leaves, lily pads
                Block a = g.getRelative(0, 1, 0), b = g.getRelative(0, 2, 0);
                if (a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid()) return true;
            }
        }
        return false;
    }

    /** A pond in this chunk one can fish and stand by; only the chunk's own blocks are read (its middle 6x6 columns). */
    private static Location pond(World w, Chunk ch) {
        if (!ch.isLoaded()) return null;
        int bx = ch.getX() << 4, bz = ch.getZ() << 4;
        for (int dx = 5; dx <= 10; dx++) {
            for (int dz = 5; dz <= 10; dz++) {
                int x = bx + dx, z = bz + dz;
                int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                if (Drowned.pool(w, x, top, z) && shore(w, x, top, z)) return new Location(w, x + 0.5, top, z + 0.5);
            }
        }
        return null;
    }

    /**
     * Looks for a pond some way from the player, among chunks that exist already (none is generated), a few a tick; done
     * gets the spot or null. One search per player at a time.
     */
    private void search(Player p, Consumer<Location> done) {
        UUID u = p.getUniqueId();
        if (searches.containsKey(u)) return;
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) {
            done.accept(null);
            return;
        }
        Location at = p.getLocation();
        Deque<int[]> chunks = new ArrayDeque<>(Calling.ring(at.getBlockX(), at.getBlockZ(), cfg().getInt("search.min", 120), cfg().getInt("search.max", 360),
                cfg().getInt("search.step", 24), cfg().getInt("search.directions", 12), Calling.hash(week(u), u)));
        Search s = new Search();
        searches.put(u, s);
        int[] flying = {0};
        Location[] found = {null};
        s.task = Bukkit.getScheduler().runTaskTimer(eco, () -> {
            while (found[0] == null && flying[0] < 4 && !chunks.isEmpty()) {
                int[] c = chunks.poll();
                flying[0]++;
                w.getChunkAtAsync(c[0], c[1], false).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(eco, () -> {
                    flying[0]--;
                    if (ch != null && found[0] == null && searches.get(u) == s) found[0] = pond(w, ch);
                }));
            }
            if (found[0] == null && (!chunks.isEmpty() || flying[0] > 0)) return;
            s.task.cancel();
            if (searches.remove(u, s)) done.accept(found[0]);
        }, 1L, 1L);
    }

    private void stopSearch(UUID u) {
        Search s = searches.remove(u);
        if (s != null && s.task != null) s.task.cancel();
    }

    /** When nothing was found far off: the place the first fisher drowned (if it is in this world), else the water at their feet. */
    private Location fallback(Player p) {
        Location d = fish.drowned().place().flatMap(Drowned.Place::location).orElse(null);
        if (d != null && d.getWorld() == p.getWorld()) return d;
        Location near = Dread.waterNear(p, 8);
        return near == null ? null : near.getBlock().getLocation().add(0.5, 0, 0.5);
    }

    private void setWater(UUID u, Location at) {
        set(u, "water", Waters.encode(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), 0));
    }

    // ------------------------------------------------------------------------------------------
    // the call
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        if (++beats % 30 == 0) take(p, false);   // a husk from a week that ended while they were away
        return false;   // it begins at a cast: cast()
    }

    private boolean rainy(Player p) {
        UUID u = p.getUniqueId();
        int day = ZonedDateTime.now(SecretModule.KST).getDayOfWeek().getValue();
        return Calling.mayCall(p.getWorld().hasStorm(), day, cfg().getInt("dry-from-day", 5), m.test(u));
    }

    /** A cast while nothing was offered this week: on a rainy day the water is looked for, and once found it calls. */
    private void call(Player p, FishHook hook) {
        UUID u = p.getUniqueId();
        if (!due(p) || !rainy(p) || searches.containsKey(u) || System.currentTimeMillis() < searchAfter.getOrDefault(u, 0L)) return;
        if (!chance(p, "chance", 1.0)) return;
        search(p, at -> {
            if (!p.isOnline()) return;
            if (at == null) {   // no pond among the chunks around here: another cast, another place, later
                searchAfter.put(u, System.currentTimeMillis() + cfg().getLong("search.retry", 300) * 1000L);
                return;
            }
            if (!offered(p)) return;
            setWater(u, at);
            decide(p);
            if (hook.isValid() && hook.isInWater()) pull(p, hook, at);
        });
    }

    /** The mission is offered and its water known: this week's nameless thing is decided, and the first word is said. */
    private void decide(Player p) {
        UUID u = p.getUniqueId();
        int n = things().size();
        int v = Calling.variant(week(u), u, n, Calling.recent(kept(u, "recent", "")));
        keep(u, "recent", Calling.remember(kept(u, "recent", ""), v, cfg().getInt("no-repeat", 4), n));
        set(u, "variant", v);
        set(u, "week", week(u));
        callScene(p);
    }

    private void callScene(Player p) {
        List<String> calls = cfg().getStringList("call");
        String l = calls.isEmpty() ? "<dark_gray><i>…찌가 당겨진다. 물고기가 아니다. 물이 부른다." : calls.get(Math.floorMod(Calling.hash(week(p.getUniqueId()), p.getUniqueId()), calls.size()));
        p.sendMessage(Text.mm(l));
        sound(p, p.getLocation(), "ambient.underwater.loop.additions.rare", 0.7f, 0.6f);
        said.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        cleanup(u);
        take(p, true);
        takeDropped(u);
        decide(p);
        find(p);
    }

    /** An offered mission without its water (an admin's start, a restart during the search): looked for now. */
    private void find(Player p) {
        UUID u = p.getUniqueId();
        search(p, at -> {
            if (!p.isOnline() || state(u) != State.OFFERED || get(u, "water") != null) return;
            Location w = at != null ? at : fallback(p);
            if (w == null) {
                searchAfter.put(u, System.currentTimeMillis() + 30_000L);
                return;
            }
            setWater(u, w);
        });
    }

    // ------------------------------------------------------------------------------------------
    // fishing
    // ------------------------------------------------------------------------------------------

    /** Before the secret's and the fishing module's handlers; a catch this replaces is cancelled for them. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        if (!may(p)) return;
        UUID u = p.getUniqueId();
        switch (e.getState()) {
            case FISHING -> {
                bites.remove(u);
                State st = state(u);
                if (st == State.NONE) call(p, e.getHook());
                else if (st.live() && step(u) <= 1) follow(p, e.getHook());
            }
            case BITE -> bites.put(u, (long) Bukkit.getCurrentTick());
            case CAUGHT_FISH -> {
                Long bite = bites.remove(u);
                if (!(e.getCaught() instanceof Item)) return;
                if (state(u) == State.ACTIVE && step(u) == 1) caught(p, e, bite == null ? 0 : bite);
                if (!e.isCancelled()) perk(p);
            }
            case FAILED_ATTEMPT -> {
                if (bites.remove(u) != null && state(u) == State.ACTIVE && step(u) == 1 && getLong(u, "streak", 0) > 0) slip(p);
            }
            default -> { }
        }
    }

    /** The float is out: once it lies in the water it is pulled toward the water that calls, or (there) the window begins. */
    private void follow(Player p, FishHook hook) {
        UUID u = p.getUniqueId();
        int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(eco, task -> {
            if (!hook.isValid() || !p.isOnline() || n[0]++ > 60) {
                task.cancel();
                return;
            }
            if (!hook.isInWater()) return;
            task.cancel();
            Location water = water(u);
            if (water == null || !live(u) || step(u) > 1) return;
            switch (near(hook.getLocation(), water)) {
                case HERE -> here(p);
                case CLOSE -> {
                    pull(p, hook, water);
                    whisper(p, "where.close", "<dark_gray><i>…바로 근처다. 물소리가 다르다.", 8);
                }
                case NEAR -> {
                    pull(p, hook, water);
                    whisper(p, "where.near", "<dark_gray><i>…가까워졌다. 찌가 끌리는 쪽으로.", 8);
                }
                default -> {
                    pull(p, hook, water);
                    whisper(p, "where.far", "<dark_gray><i>…멀다. 찌가 끌리는 쪽으로.", 8);
                }
            }
        }, 2L, 2L);
    }

    /** The float drifts a little toward the water, with a thin line of bubbles only they see. */
    private void pull(Player p, FishHook hook, Location water) {
        if (hook.getWorld() != water.getWorld()) {
            whisper(p, "where.elsewhere", "<dark_gray><i>…이 세계의 물이 아니다.", 8);
            return;
        }
        double strength = cfg().getDouble("pull.strength", 0.07);
        int times = Math.clamp(cfg().getInt("pull.times", 10), 1, 40);
        sound(p, hook.getLocation(), "entity.fishing_bobber.splash", 0.25f, 0.5f);
        for (int i = 0; i < times; i++) {
            int k = i;
            later(4L * i, () -> {
                if (!hook.isValid() || !p.isOnline() || !hook.isInWater()) return;
                Location h = hook.getLocation();
                double[] v = Calling.pull(h.getX(), h.getZ(), water.getX(), water.getZ(), strength);
                hook.setVelocity(hook.getVelocity().add(new Vector(v[0], 0, v[1])));
                double f = 0.6 + k * 0.25;
                p.spawnParticle(Particle.FISHING, h.clone().add(v[0] / strength * f, 0.1, v[1] / strength * f), 2, 0.05, 0, 0.05, 0);
            });
        }
    }

    /** The float lies in the water that called: the window begins (once), and it says so. */
    private void here(Player p) {
        UUID u = p.getUniqueId();
        if (state(u) == State.OFFERED) {
            if (!accept(p)) return;
            step(u, 1);
            set(u, "began", System.currentTimeMillis());
            set(u, "streak", 0);
            remove(u, "warned");
            long window = seconds(u, "window", 420);
            p.sendMessage(Text.mm(line("here", "<#8fa9b8><i>…여기야. 세 번. 늦지 않게, 연달아 올려 줘.")));
            tell(p, "here-how", "<dark_gray>찌가 잠기는 순간 바로 당긴다. 연달아 {need}번, {minutes}분 안에.",
                    "need", String.valueOf(need()), "minutes", String.valueOf(Math.max(1, window / 60)));
            sound(p, p.getLocation(), "ambient.underwater.loop.additions", 0.7f, 0.7f);
        } else {
            whisper(p, "here-again", "<dark_gray><i>…여기야.", 20);
        }
    }

    private int need() {
        return Math.max(1, cfg().getInt("need", 3));
    }

    /** A catch at the water while the window runs: clean or late; the last clean one brings it up instead of the fish. */
    private void caught(Player p, PlayerFishEvent e, long bite) {
        UUID u = p.getUniqueId();
        Location water = water(u);
        if (water == null || near(e.getHook().getLocation(), water) != Waters.Near.HERE) return;
        boolean clean = Waters.perfect(bite, Bukkit.getCurrentTick(), cfg().getInt("perfect-ticks", 10));
        if (!clean) {
            slip(p);
            return;
        }
        int streak = Calling.streak((int) getLong(u, "streak", 0), true);
        set(u, "streak", streak);
        if (streak < need()) {
            List<String> count = cfg().getStringList("count");
            String l = count.isEmpty() ? "<#8fa9b8><i>…" + streak + "." : count.get(Math.min(streak, count.size()) - 1);
            m.haunt().whisper(p, l);
            sound(p, p.getLocation(), "block.amethyst_block.chime", 0.5f, 0.6f + 0.15f * streak);
            return;
        }
        // what was on the line goes; this comes up instead (as the secret's own catches do)
        Location at = e.getHook().getLocation();
        e.setCancelled(true);
        if (e.getCaught() != null) e.getCaught().remove();
        e.getHook().remove();
        step(u, 2);
        without.remove(u);
        Thing t = thing(u);
        give(p, make(u, t));
        riseScene(p, t, at);
        eco.getLogger().info("[비밀] 후속 눈 없는 물고기: " + p.getName() + " 이(가) 이름을 잃은 것을 낚음 (" + t.id() + ")");
    }

    private void slip(Player p) {
        UUID u = p.getUniqueId();
        if (getLong(u, "streak", 0) > 0) set(u, "streak", 0);
        m.haunt().whisper(p, line("slip", "<dark_gray><i>…늦었어. 처음부터."));
        sound(p, p.getLocation(), "entity.fish.swim", 0.4f, 0.5f);
    }

    private void riseScene(Player p, Thing t, Location at) {
        p.showTitle(Title.title(Text.mm(t.name()), Text.mm(line("rise-subtitle", "<dark_gray>이름이 있어야 할 자리가 비어 있다")),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(1200))));
        sound(p, p.getLocation(), "entity.cod.flop", 0.7f, 0.45f);
        p.spawnParticle(Particle.BUBBLE_POP, at, 14, 0.3, 0.1, 0.3, 0.01);
        later(50, () -> {
            if (p.isOnline()) p.sendMessage(Text.mm(line("rise", "<dark_gray>…물고기가 아니다. 물로 돌려보내 달라는 것 같다. <gray>(물에 던지거나, 든 채 물을 우클릭)")));
        });
    }

    // ------------------------------------------------------------------------------------------
    // the week's perk: now and then a rare bite
    // ------------------------------------------------------------------------------------------

    private boolean perkOn(UUID u) {
        return cfg().getBoolean("perk.enabled", true) && week(u).equals(kept(u, "perk", ""));
    }

    /** A catch of theirs in the week they gave it back: by perk.rare-chance the fishing module makes this bite a rare one. */
    private void perk(Player p) {
        UUID u = p.getUniqueId();
        FishingModule f = eco.fishing();
        if (f == null || !perkOn(u) || f.forced(u) != null) return;
        double chance = m.test(u) ? cfg().getDouble("perk.test-rare-chance", 0.5) : cfg().getDouble("perk.rare-chance", 0.03);
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        List<String> tiers = new ArrayList<>(f.spec().rarities().keySet());
        if (tiers.size() < 3) return;
        f.force(u, tiers.get(2), null, 1);
        forced.add(u);
        m.haunt().whisper(p, line("perk.bite", "<dark_gray><i>…물이 하나 올려 보낸다."));
        // not used by this catch (junk on the line, another plugin took it): it is not left for later
        later(1, () -> {
            if (!forced.remove(u)) return;
            FishingModule.Forced now = f.forced(u);
            if (now != null && now.species() == null && tiers.get(2).equals(now.rarity()) && now.left() == 1) f.unforce(u);
        });
    }

    // ------------------------------------------------------------------------------------------
    // giving it back to the water
    // ------------------------------------------------------------------------------------------

    /** Right-click water (within reach) with it in hand: it goes into the water. A husk just goes. */
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (!isNameless(it)) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        if (!mine(it, u) || !may(p)) {
            if (u.toString().equals(pdc(it, NAMELESS)) && !mine(it, u) && on()) p.getInventory().setItem(hand, null);
            return;
        }
        RayTraceResult r = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 5, FluidCollisionMode.ALWAYS, true);
        if (r == null || r.getHitBlock() == null || r.getHitBlock().getType() != Material.WATER) return;
        e.setCancelled(true);
        release(p, r.getHitPosition().toLocation(p.getWorld()));
    }

    private void watch(Item ent) {
        String owner = pdc(ent.getItemStack(), NAMELESS);
        if (owner == null) return;
        try {
            UUID u = UUID.fromString(owner);
            ent.setOwner(u);   // nobody else picks it up
            dropped.put(ent.getUniqueId(), u);
        } catch (IllegalArgumentException ignored) {
            // not ours after all
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        watch(e.getEntity());
    }

    /** Only its owner picks it up, and only while it is theirs to hold; a husk is gone when touched. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (!isNameless(it)) return;
        if (e.getEntity() instanceof Player p && mine(it, p.getUniqueId())) {
            dropped.remove(e.getItem().getUniqueId());
            return;
        }
        e.setCancelled(true);
        String owner = pdc(it, NAMELESS);
        if (on() && e.getEntity() instanceof Player p && p.getUniqueId().toString().equals(owner)) {
            dropped.remove(e.getItem().getUniqueId());
            e.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent e) {
        if (isNameless(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        later(60, () -> {
            if (p.isOnline() && on()) take(p, false);
        });
    }

    /** It is in the water again: the splash, what it remembered, and then the week is done. */
    private void release(Player p, Location at) {
        UUID u = p.getUniqueId();
        if (state(u) != State.ACTIVE || step(u) != 2) return;
        Thing t = thing(u);
        step(u, 3);
        take(p, true);
        takeDropped(u);
        fish.dread().splash(p, at);
        later(40, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Text.mm(line("memory-format", "<#8fa9b8><i>{memory}", "memory", t.memory())));
            sound(p, p.getLocation(), "ambient.underwater.loop.additions", 0.7f, 0.7f);
        });
        later(110, () -> {
            if (!p.isOnline()) return;
            p.spawnParticle(Particle.BUBBLE_COLUMN_UP, at, 20, 0.3, 0.2, 0.3, 0.02);
            finish(p);
        });
    }

    private void finish(Player p) {
        UUID u = p.getUniqueId();
        if (state(u) != State.ACTIVE || step(u) < 3) return;
        take(p, true);
        String week = week(u);
        if (complete(p).isEmpty() && state(u) != State.DONE) return;
        if (!cfg().getBoolean("perk.enabled", true) || eco.fishing() == null) return;
        keep(u, "perk", week);
        tell(p, "perk.line", "<dark_gray>이번 주에는, 물이 당신의 줄을 조금 더 알아본다.");
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        beats++;
        long now = System.currentTimeMillis();
        State st = state(u);
        int step = step(u);
        if (st == State.OFFERED) {
            if (get(u, "water") == null) {
                if (!searches.containsKey(u) && now >= searchAfter.getOrDefault(u, 0L)) find(p);
            } else if (beats % 3 == 0) {
                mark(p, water(u));
            }
            return;
        }
        if (step <= 1) {
            long left = Calling.left(getLong(u, "began", since(u)), now, seconds(u, "window", 420));
            if (left <= 0) {
                sound(p, p.getLocation(), "entity.generic.splash", 0.4f, 0.5f);
                fail(p, "sunk");
                return;
            }
            if (left <= seconds(u, "window", 420) / 4 && get(u, "warned") == null) {
                set(u, "warned", 1);
                m.haunt().whisper(p, line("hurry", "<dark_gray><i>…물이 식어 간다. 얼마 남지 않았다."));
            }
            if (beats % 3 == 0) mark(p, water(u));
            return;
        }
        if (step >= 3) {   // given back, but the words were cut short (a quit, a reload): it is done all the same
            if (elapsed(u) >= 10) finish(p);
            return;
        }
        // step 2: it is in their hands, or lying where they dropped it
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(dropped.entrySet())) {
            if (!en.getValue().equals(u)) continue;
            Entity x = Bukkit.getEntity(en.getKey());
            if (!(x instanceof Item ent) || !ent.isValid()) {
                dropped.remove(en.getKey());
            } else if (ent.isInWater() && mine(ent.getItemStack(), u)) {
                Location at = ent.getLocation();
                dropped.remove(en.getKey());
                ent.remove();
                release(p, at);
                return;
            }
        }
        if (beats % 5 != 0) return;
        take(p, false);
        if (has(p)) {
            without.remove(u);
            ItemStack main = p.getInventory().getItemInMainHand(), off = p.getInventory().getItemInOffHand();
            if (mine(main, u) || mine(off, u)) whisper(p, "hold", "<dark_gray><i>…이름이 없어. 물로 돌려보내 줘.", cfg().getInt("hold-every", 12));
            return;
        }
        long since = without.computeIfAbsent(u, x -> now);
        if (now - since < seconds(u, "return-after", 30) * 1000L) return;
        without.remove(u);
        give(p, make(u, thing(u)));
        m.haunt().whisper(p, line("back", "<dark_gray><i>…손이 젖어 있다. 그것이 다시 쥐여 있다."));
    }

    /** Near the water that called: now and then a small ring on it, only for them. */
    private void mark(Player p, Location water) {
        if (water == null) return;
        double d = flat(p.getLocation(), water);
        if (d < 0 || d > cfg().getDouble("close", 48)) return;
        Location at = water.clone().add(0, 0.95, 0);
        p.spawnParticle(Particle.BUBBLE_POP, at, 3, 0.4, 0, 0.4, 0.01);
        p.spawnParticle(Particle.FISHING, at, 2, 0.5, 0, 0.5, 0.01);
    }

    @Override
    protected void cleanup(UUID u) {
        stopSearch(u);
        bites.remove(u);
        said.remove(u);
        without.remove(u);
        searchAfter.remove(u);
        forced.remove(u);
    }

    @Override
    protected void expired(UUID u) {
        takeDropped(u);
        Player p = Bukkit.getPlayer(u);
        if (p != null) take(p, true);   // offline: what they carry is another week's by then, a husk taken when next seen
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public String status(UUID u) {
        String perk = perkOn(u) ? " · 이번 주 희귀 입질 보너스 켜짐" : "";
        if (!live(u)) return "다음: 다음 주, 비 오는 날(또는 " + cfg().getInt("dry-from-day", 5) + "번째 요일부터) 낚싯대 던지기" + perk;
        List<String> out = new ArrayList<>();
        Thing t = thing(u);
        out.add("이름을 잃은 것 <white>" + t.id() + "</white>");
        Location w = water(u);
        Player p = Bukkit.getPlayer(u);
        if (w == null) {
            out.add(searches.containsKey(u) ? "물 찾는 중" : "물 못 찾음 (다시 찾는 중: 물가에서 기다리거나 다른 곳에서 시작)");
        } else {
            double d = p == null ? -1 : flat(p.getLocation(), w);
            out.add("부르는 물 <white>" + w.getWorld().getName() + " " + w.getBlockX() + " " + w.getBlockY() + " " + w.getBlockZ() + "</white>"
                    + (d >= 0 ? " (거리 " + Math.round(d) + ")" : ""));
        }
        int step = step(u);
        if (state(u) == State.OFFERED) out.add("다음: 찌가 끌리는 쪽으로 가서 그 물 (반경 " + cfg().getInt("radius", 12) + "칸) 에 낚싯대 던지기");
        else if (step <= 1) out.add("연속 " + getLong(u, "streak", 0) + "/" + need() + " · 남은 "
                + Calling.left(getLong(u, "began", since(u)), System.currentTimeMillis(), seconds(u, "window", 420)) + "초 · 다음: 그 물에서 입질 뒤 "
                + cfg().getInt("perfect-ticks", 10) + "틱 안에 연달아 낚기");
        else if (step == 2) out.add("다음: 이름을 잃은 것을 물에 던지거나 든 채 물을 우클릭");
        else out.add("놓아줌 (곧 보상)");
        return String.join(" · ", out) + perk;
    }

    @Override
    public List<String> scenes() {
        return List.of("부름", "끌림", "여기", "하나", "놓침", "떠오름", "놓아줌", "가라앉음");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        List<Thing> pool = things();
        Thing t = get(u, "variant") != null ? thing(u) : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        switch (word) {
            case "부름" -> callScene(p);
            case "끌림" -> {
                FishHook h = p.getFishHook();
                Location w = water(u);
                if (h == null || !h.isValid() || !h.isInWater()) {
                    admin.sendMessage(Text.mm("<red>찌가 물에 떠 있을 때 쓰세요"));
                    return true;
                }
                if (w == null) w = p.getLocation().add(p.getLocation().getDirection().setY(0).multiply(40));
                pull(p, h, w);
            }
            case "여기" -> p.sendMessage(Text.mm(line("here", "<#8fa9b8><i>…여기야. 세 번. 늦지 않게, 연달아 올려 줘.")));
            case "하나" -> {
                List<String> count = cfg().getStringList("count");
                m.haunt().whisper(p, count.isEmpty() ? "<#8fa9b8><i>…하나." : count.getFirst());
                sound(p, p.getLocation(), "block.amethyst_block.chime", 0.5f, 0.75f);
            }
            case "놓침" -> {
                m.haunt().whisper(p, line("slip", "<dark_gray><i>…늦었어. 처음부터."));
                sound(p, p.getLocation(), "entity.fish.swim", 0.4f, 0.5f);
            }
            case "떠오름" -> riseScene(p, t, p.getLocation());
            case "놓아줌" -> {
                Location at = Dread.waterNear(p, 8);
                if (at == null) at = p.getLocation();
                fish.dread().splash(p, at);
                later(40, () -> {
                    if (p.isOnline()) p.sendMessage(Text.mm(line("memory-format", "<#8fa9b8><i>{memory}", "memory", t.memory())));
                });
            }
            case "가라앉음" -> {
                sound(p, p.getLocation(), "entity.generic.splash", 0.4f, 0.5f);
                p.sendMessage(Text.msg(line("sunk", "<dark_gray>…그것은 다시 가라앉았다. 다음 비에 또 부를 것이다.")));
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " (" + t.id() + ") → " + p.getName()));
        return true;
    }
}
