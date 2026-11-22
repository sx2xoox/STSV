package dev.stsv.economy.secret.jesa;

import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.fishing.FishSpec;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 주인 없는 제사 (docs/admin/숨은_비밀/주인_없는_제사.md; contents/economy/secrets/jesa.yml). A bus driver overhears a
 * grandmother on their bus: tomorrow is her husband's rite and there is nobody to set the table (stage 1). Where she gets
 * off, a thatched shelter stands for that driver only, with a bare rite table ({@link House}; stage 2). The driver lays it
 * with food made on this server — the window ({@link Setting}) only remembers what goes where, the food never leaves the
 * bag — and bows twice before it at night. The answer is not in the game: it is how a real 제사상 is set ({@link Table}).
 * Wrong: the wrong dishes roll off, the spoon taps what is owed, the incense goes out for a while; nothing is taken.
 * Right (stage 3): the dead come to eat — and there are two of them. Both tablets were on the screen all along: the
 * grandmother was dead too, and it was her rite as well. Dawn, grass, solved; the food is taken only then.
 * The solver keeps a family register with their own name inked in last and, for good, two old people who now and then
 * stand behind them where others can see ({@link Blessing}).
 * <p>
 * Saved (eco.kv "secret.jesa.&lt;uuid&gt;."): stage, since, day (the last day the chance was tried), heard (the stop where
 * she was overheard), house ("world x y z": the table's block), stop (where she got off), layout (what is laid:
 * {@link Table#encode}), retry (ms: no rite before it), tries, late (told that someone else was first), done.
 * The bus calls in through {@link BusModule.Watcher}; which passenger she is, is only remembered while the server runs.
 */
public final class Jesa extends Secret implements BusModule.Watcher {

    static final int HEARD = 1, HOUSE = 2, ACCEPTED = 3;
    static final String JOKBO = "economy:jesa_jokbo";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    /** The words as shipped (jesa.yml has the same; a line missing there falls back to these). */
    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("solved-line", "<dark_gray>누군가 주인 없는 제사를 대신 지냈다. 그 집에서는 이제 아무도 기다리지 않는다."),
            Map.entry("too-late", "<dark_gray>…상은 이미 물려져 있다. 누군가 먼저 차렸다."),
            Map.entry("lines.overhear", "…내일이 영감 제산데… 차려 줄 사람이 없네."),
            Map.entry("lines.house", "…저기, 감나무 아래가 우리 집이우."),
            Map.entry("lines.day", "<gray>…아직 해가 있다."),
            Map.entry("lines.incense-out", "<gray>…향이 꺼져 있다."),
            Map.entry("lines.relit", "<gray>…향이 다시 피어오른다."),
            Map.entry("lines.empty", "<gray>…빈 상에는 아무도 오지 않는다."),
            Map.entry("lines.not-in-hand", "<gray>…상에 올리기로 한 것이 손에 없다."),
            Map.entry("lines.laid", "<gray>…상은 차렸다. 이제, 절을."),
            Map.entry("lines.bow-once", "<gray>…한 번으로는 모자라다."),
            Map.entry("lines.out", "<gray>…향이 꺼졌다."),
            Map.entry("lines.no-more", "<gray>…그건 더 없다."),
            Map.entry("lines.not-this", "<gray>…이건 상에 올릴 수 없다."),
            Map.entry("lines.gone", "<gray>…상이 비어 있다. 아무도 먹지 못했다."),
            Map.entry("lines.meal-1", "…영감, 많이 드시우."),
            Map.entry("lines.meal-2", "…나도 한 술 뜨고."),
            Map.entry("lines.meal-3", "…고맙수. 길에서 만난 사람한테 얻어먹는 제삿밥이 제일 달다더니."),
            Map.entry("lines.meal-4", "…버스비는, 못 냈네."),
            Map.entry("lines.dawn", "<gray>…날이 밝았다. 감나무 아래에는 풀만 자라 있다."),
            Map.entry("tablets.father", "顯考學生府君神位"),
            Map.entry("tablets.mother", "顯妣孺人達城徐氏神位"),
            Map.entry("gui.title", "제사상 · 병풍 쪽이 북"),
            Map.entry("gui.screen", "<gray>병풍"),
            Map.entry("gui.hand", "<gray>빈 젓가락"),
            Map.entry("jokbo.name", "<#e8dcc0>족보"),
            Map.entry("jokbo.title", "감골 김씨 세보"),
            Map.entry("jokbo.author", "감골 김씨 문중"));

    private final House house;
    private final Blessing blessing;
    private BusModule watched;
    /** driver -> the passenger they overheard (while she rides their bus) */
    private final Map<UUID, UUID> riding = new HashMap<>();
    /** driver -> the stop their bus pulled into last */
    private final Map<UUID, Integer> lastStop = new HashMap<>();
    /** player -> when they bowed once (ms) */
    private final Map<UUID, Long> bowed = new HashMap<>();
    /** players whose house is going at dawn (it is not built again meanwhile), and those whose sky was changed */
    private final Set<UUID> concluding = new HashSet<>(), dawned = new HashSet<>();
    private Table.Rules rules;
    private long rulesAt;

    public Jesa(SecretModule m) {
        super(m);
        house = new House(this, m, eco);
        blessing = new Blessing(this);
        BusModule b = eco.bus();
        if (b != null) {
            b.watch(id(), this);
            watched = b;
        }
    }

    @Override
    public String id() {
        return "jesa";
    }

    @Override
    public String name() {
        return "주인없는제사";
    }

    @Override
    public String stoneTitle() {
        return "주인 없는 제사";
    }

    /** 1: overheard her; 2: the house stands, the table is to be laid; 3: the table was accepted (the meal). */
    @Override
    public int stages() {
        return ACCEPTED;
    }

    @Override
    protected void loaded() {
        rules = null;
    }

    org.bukkit.configuration.file.YamlConfiguration cfg() {
        return cfg;
    }

    org.bukkit.plugin.Plugin plugin() {
        return eco;
    }

    boolean testing(UUID u) {
        return m.test(u);
    }

    String text(String key) {
        return cfg.getString(key, WORDS.getOrDefault(key, ""));
    }

    void whisper(Player p, String key) {
        m.haunt().whisper(p, text(key));
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    private BusModule bus() {
        return eco.bus();
    }

    private boolean guide(Player p) {
        return eco.guide() != null && eco.guide().inGuide(p);
    }

    private void later(Player p, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) r.run();
        }, Math.max(1, ticks));
    }

    // ------------------------------------------------------------------------------------------
    // the rules of the table
    // ------------------------------------------------------------------------------------------

    /** jesa.yml's lists, and every fish whose name ends in 치 (read off the fish as they are now) as taboo. */
    Table.Rules rules() {
        long now = System.currentTimeMillis();
        if (rules != null && now - rulesAt < 60_000L) return rules;
        Table.Rules base = Table.Rules.read(cfg.getConfigurationSection("table"));
        List<String> chi = new ArrayList<>();
        String suffix = cfg.getString("table.taboo-name-suffix", "치");
        FishSpec fish = eco.fishing() == null ? null : eco.fishing().spec();
        if (fish != null) for (FishSpec.Species sp : fish.species().values())
            if (Table.endsIn(Text.plain(sp.name()), suffix)) chi.add(Table.norm(sp.item()));
        rules = base.alsoTaboo(chi);
        rulesAt = now;
        return rules;
    }

    Map<Integer, String> layout(UUID u) {
        return Table.decode(m.get(this, u, "layout"));
    }

    private void saveLayout(UUID u, Map<Integer, String> layout) {
        if (layout.isEmpty()) m.remove(this, u, "layout");
        else m.set(this, u, "layout", Table.encode(layout));
    }

    private static boolean real(ItemStack it, String key) {
        return it != null && !it.isEmpty() && !Items.trial(it) && !Setting.isGhost(it) && Items.key(it).equals(key);
    }

    /** How many of an item they carry (their bag, not what they wear; trial things of the 길라잡이 do not count). */
    int carried(Player p, String key) {
        return Items.count(p.getInventory(), it -> real(it, key));
    }

    private Map<String, Integer> lacking(Player p, Map<Integer, String> layout) {
        Map<String, Integer> have = new HashMap<>();
        for (String k : Table.needs(layout).keySet()) have.put(k, carried(p, k));
        return Table.lacking(layout, have);
    }

    // ------------------------------------------------------------------------------------------
    // stage 0 -> 1: overheard on the bus
    // ------------------------------------------------------------------------------------------

    private String kind() {
        return cfg.getString("start.type", "grandma");
    }

    /** BusModule: their bus pulled into a stop's bay. */
    @Override
    public void stopped(Player p, BusStop here) {
        UUID u = p.getUniqueId();
        lastStop.put(u, here.id());
        if (!on() || guide(p)) return;
        int st = stage(u);
        if (st == 0) {
            if (!open(u)) return;
            BusModule b = bus();
            List<Entity> aboard = b == null ? List.of() : b.ridersOf(p, kind());
            if (aboard.isEmpty()) return;
            String today = m.today(u);
            if (today.equals(m.get(this, u, "day"))) return;   // one try a day
            m.set(this, u, "day", today);
            if (!m.roll(u, cfg.getDouble("start.chance", 0.01))) return;
            begin(p, aboard.get(ThreadLocalRandom.current().nextInt(aboard.size())), here);
        } else if (st == HEARD) {
            if (!open(u)) late(p);
            else if (!riding.containsKey(u)) arrive(p, here, null);   // she was lost on the way (a restart, another bus): here, then
        }
    }

    /** She says it, to nobody (stage 1). */
    private void begin(Player p, Entity grandma, BusStop here) {
        UUID u = p.getUniqueId();
        m.stage(this, u, HEARD);
        m.remove(this, u, "house");
        m.remove(this, u, "stop");
        m.remove(this, u, "late");
        if (here != null) m.set(this, u, "heard", here.id());
        if (grandma != null) riding.put(u, grandma.getUniqueId());
        else riding.remove(u);
        later(p, 30, () -> say(p, grandma, ahead(p), text("lines.overhear")));
    }

    private static Location ahead(Player p) {
        Location l = p.getLocation();
        return l.clone().add(l.getDirection().setY(0).multiply(2.5)).add(0, 1.9, 0);
    }

    /** A line only they hear: a dark bubble over the speaker (else at a spot) and a dim chat line to read again. */
    private void say(Player p, Entity over, Location at, String line) {
        boolean rides = over != null && over.isValid();
        Location where = rides ? over.getLocation().add(0, 2.3, 0) : at;
        if (where.getWorld() != p.getWorld()) where = ahead(p);
        TextDisplay d = m.haunt().text(p, where, "<#d8d2c4><i>" + line);
        d.setBackgroundColor(Color.fromARGB(170, 16, 16, 22));
        d.setShadowed(false);
        d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, rides ? 0.55f : 0, 0), new org.joml.AxisAngle4f(),
                new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.AxisAngle4f()));
        if (rides && !over.addPassenger(d)) d.teleport(where);
        later(p, 90, () -> m.haunt().remove(p, d));
        p.sendMessage(Text.mm("<dark_gray><i>" + line));
        m.haunt().sound(p, where, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.15f, 0.5f);
    }

    @Override
    public void start(Player p) {
        cleanup(p.getUniqueId());
        BusModule b = bus();
        List<Entity> aboard = b == null ? List.of() : b.ridersOf(p, kind());
        Integer at = b == null ? null : b.stoppedAt(p);
        begin(p, aboard.isEmpty() ? null : aboard.getFirst(), at == null ? null : stop(at));
    }

    // ------------------------------------------------------------------------------------------
    // stage 1 -> 2: she gets off, and there is a house
    // ------------------------------------------------------------------------------------------

    private BusStop stop(int id) {
        BusModule b = bus();
        if (b == null) return null;
        for (BusStop s : b.stops()) if (s.id() == id) return s;
        return null;
    }

    private String stopName(String id) {
        try {
            BusStop s = id == null ? null : stop(Integer.parseInt(id));
            return s == null ? "?" : s.name();
        } catch (NumberFormatException e) {
            return "?";
        }
    }

    /** Each second at stage 1: is she still on their bus? */
    private void watchHer(Player p) {
        UUID u = p.getUniqueId();
        UUID her = riding.get(u);
        if (her == null) return;
        BusModule b = bus();
        if (b != null) for (Entity e : b.ridersOf(p, kind())) if (e.getUniqueId().equals(her)) return;
        riding.remove(u);
        if (b == null || b.ridersOf(p) < 0) return;   // the bus itself is gone: wherever they stop next
        Integer at = b.stoppedAt(p);
        if (at == null) at = lastStop.get(u);
        BusStop s = at == null ? null : stop(at);
        if (s != null) arrive(p, s, Bukkit.getEntity(her));
    }

    /** She got off here (or, lost on the way, this is as good as where she would have): her house is near (stage 2). */
    private void arrive(Player p, BusStop here, Entity her) {
        UUID u = p.getUniqueId();
        Location at = pick(p, here);
        if (at == null) return;   // no ground for it here: the next stop
        m.set(this, u, "house", Place.encode(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        m.set(this, u, "stop", here.id());
        m.remove(this, u, "retry");
        m.stage(this, u, HOUSE);
        World w = at.getWorld();
        Location sign = new Location(w, here.x(), here.y() + 2.6, here.z());
        later(p, 20, () -> say(p, her, sign, text("lines.house")));
        later(p, 70, () -> m.haunt().sound(p, at.clone().add(0.5, 1, 0.5), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.6f));
    }

    private Set<Material> ground() {
        Set<Material> out = EnumSet.noneOf(Material.class);
        List<String> names = cfg.getStringList("house.ground");
        if (names.isEmpty()) names = List.of("GRASS_BLOCK", "DIRT", "COARSE_DIRT", "PODZOL", "ROOTED_DIRT", "MOSS_BLOCK", "MYCELIUM", "SAND",
                "RED_SAND", "SNOW_BLOCK", "MUD");
        for (String n : names) {
            Material mat = Material.matchMaterial(n);
            if (mat != null) out.add(mat);
        }
        return out;
    }

    /** A free, flat spot of natural ground near a stop, off the road and off anyone's land; the same one for this player. */
    private Location pick(Player p, BusStop here) {
        World w = Bukkit.getWorld(here.world());
        if (w == null) return null;
        Set<Material> ground = ground();
        int tries = Math.max(1, cfg.getInt("house.tries", 160)), n = 0;
        for (int[] d : Place.candidates(Place.seed(p.getUniqueId(), here.id()), cfg.getInt("house.min", 14), cfg.getInt("house.max", 30),
                here.hx(), here.hz(), cfg.getDouble("house.road-clear", 10))) {
            if (++n > tries) break;
            Location at = fit(w, (int) Math.floor(here.x()) + d[0], (int) Math.floor(here.z()) + d[1], here.y(), ground);
            if (at != null) return at;
        }
        return null;
    }

    /** The table's block for a house whose middle is this column, or null when the ground there will not do. */
    private Location fit(World w, int x, int z, int nearY, Set<Material> ground) {
        int r = Place.REACH;
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) if (!w.isChunkLoaded(cx, cz)) return null;
        if (eco.land() != null) for (int dx = -r; dx <= r; dx += r)
            for (int dz = -r; dz <= r; dz += r) if (eco.land().playerLand(w.getName(), x + dx, z + dz)) return null;
        int y = Integer.MIN_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Block g = w.getHighestBlockAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (g.getType() == Material.SNOW) g = g.getRelative(0, -1, 0);
                if (!ground.contains(g.getType())) return null;
                if (y == Integer.MIN_VALUE) y = g.getY();
                else if (g.getY() != y) return null;
            }
        }
        if (Math.abs(y - nearY) > cfg.getInt("house.max-rise", 12)) return null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 1; dy <= 4; dy++) {
                    Block b = w.getBlockAt(x + dx, y + dy, z + dz);
                    if (!b.isPassable() || b.isLiquid() || Tag.RAILS.isTagged(b.getType())) return null;
                }
            }
        }
        return new Location(w, x, y + 1, z);
    }

    /** A spot for an admin's house: flat ground a few blocks from them, else simply four blocks ahead. */
    private Location beside(Player p) {
        Location me = p.getLocation();
        World w = me.getWorld();
        Set<Material> ground = ground();
        for (int[] d : Place.candidates(Place.seed(p.getUniqueId(), 0), 4, 9, 0, 0, 0)) {
            Location at = fit(w, me.getBlockX() + d[0], me.getBlockZ() + d[1], me.getBlockY(), ground);
            if (at != null && Math.abs(at.getBlockY() - me.getBlockY()) <= 3) return at;
        }
        Location at = me.clone().add(me.getDirection().setY(0).normalize().multiply(4));
        if (!Double.isFinite(at.getX())) at = me.clone().add(0, 0, -4);
        return settle(new Location(w, at.getBlockX(), me.getBlockY(), at.getBlockZ()));
    }

    /** A saved place as it is now: on the ground still, or lifted onto whatever was put there since. */
    private static Location settle(Location at) {
        Block b = at.getBlock();
        if (b.isPassable() && !b.getRelative(0, -1, 0).isPassable()) return at;
        for (int dy : new int[] {-1, 1, -2, 2, -3, 3}) {
            Block c = b.getRelative(0, dy, 0);
            if (c.isPassable() && !c.getRelative(0, -1, 0).isPassable()) return c.getLocation();
        }
        Block top = at.getWorld().getHighestBlockAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return Math.abs(top.getY() + 1 - at.getBlockY()) <= 8 ? top.getLocation().add(0, 1, 0) : at;
    }

    /** Where their house is (the table's block), or null (none yet, or its world is not there). */
    private Location home(UUID u) {
        String saved = m.get(this, u, "house");
        int[] at = Place.decode(saved);
        World w = at == null ? null : Bukkit.getWorld(Place.world(saved));
        return w == null ? null : new Location(w, at[0], at[1], at[2]);
    }

    // ------------------------------------------------------------------------------------------
    // the house, each second
    // ------------------------------------------------------------------------------------------

    private boolean night(UUID u, World w) {
        if (m.test(u) || !cfg.getBoolean("night.required", true)) return true;
        List<Integer> r = cfg.getIntegerList("night.time");
        return Place.within(w.getTime(), r.isEmpty() ? 13000 : r.getFirst(), r.size() < 2 ? 23000 : r.get(1));
    }

    /** How long (ms) until the incense burns again. */
    private long waiting(UUID u) {
        return Math.max(0, m.getLong(this, u, "retry", 0) - System.currentTimeMillis());
    }

    private static boolean within(Player p, Location at, double r) {
        return at.getWorld() != null && at.getWorld().equals(p.getWorld()) && at.distanceSquared(p.getLocation()) <= r * r;
    }

    @Override
    public void tick() {
        BusModule b = bus();
        if (b != null && b != watched) {
            b.watch(id(), this);
            watched = b;
        }
        Optional<UUID> first = m.solver(this);
        double show = cfg.getDouble("house.show-range", 48);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            int st = stage(u);
            House.Shown s = house.get(u);
            if (s != null && !s.valid()) {   // its chunk went away under it
                house.hide(u);
                s = null;
            }
            if (st > stages()) {
                if (s != null && !s.busy && (!s.preview || !within(p, s.base, show + 8))) house.hide(u);
                if (first.isPresent() && first.get().equals(u) || m.test(u)) blessing.tick(p);
                continue;
            }
            if (s != null && s.preview) {   // an admin's look: there until they walk off
                if (!within(p, s.base, show + 8)) house.hide(u);
                else if (!s.busy) house.light(s, night(u, s.base.getWorld()));
                continue;
            }
            if (st == HEARD && open(u)) watchHer(p);
            if (st != HOUSE && st != ACCEPTED || concluding.contains(u) || guide(p)) {
                if (s != null && !s.busy && !concluding.contains(u)) house.hide(u);
                continue;
            }
            boolean closed = !open(u);
            if (closed && m.get(this, u, "late") != null) {
                if (s != null && !s.busy) house.hide(u);
                continue;
            }
            Location h = home(u);
            if (h == null) continue;
            if (s == null) {
                if (!within(p, h, show) || !h.isChunkLoaded()) continue;
                s = house.show(p, settle(h), false, closed ? Map.of() : layout(u));
                house.light(s, !closed && night(u, h.getWorld()));
                house.smoke(s, !closed && waiting(u) == 0);
                continue;
            }
            if (!within(p, s.base, show + 8)) {
                house.hide(u);
                closeTable(p);
                continue;
            }
            if (s.busy) continue;
            house.light(s, !closed && night(u, s.base.getWorld()));
            boolean burning = !closed && waiting(u) == 0;
            if (burning && !s.smoke) {
                whisper(p, "lines.relit");
                m.haunt().sound(p, s.table(), Sound.ITEM_FLINTANDSTEEL_USE, 0.2f, 1.7f);
            }
            house.smoke(s, burning);
        }
    }

    /** Someone else set it first: said once, and the house is gone for them. */
    private void late(Player p) {
        UUID u = p.getUniqueId();
        closeTable(p);
        if (m.get(this, u, "late") != null) return;
        m.set(this, u, "late", 1);
        p.sendMessage(Text.msg(text("too-late")));
        House.Shown s = house.get(u);
        if (s != null && !s.preview) house.dissolve(p, s);
    }

    // ------------------------------------------------------------------------------------------
    // the table: the window
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onTouch(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction hit) || !hit.getPersistentDataContainer().has(House.TAG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        String tag = hit.getPersistentDataContainer().get(House.TAG, PersistentDataType.STRING);
        House.Shown s = house.get(u);
        if (tag == null || !tag.startsWith(u.toString()) || s == null || !hit.equals(s.hit) || s.busy || guide(p)) return;
        if (!s.preview) {
            int st = stage(u);
            if (!on() || st != HOUSE && st != ACCEPTED) return;
            if (!open(u)) {
                late(p);
                return;
            }
        }
        if (!night(u, s.base.getWorld())) {
            whisper(p, "lines.day");
            m.haunt().sound(p, s.table(), Sound.BLOCK_WOOD_HIT, 0.3f, 0.6f);
            return;
        }
        openTable(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction hit && hit.getPersistentDataContainer().has(House.TAG)) e.setCancelled(true);
    }

    private void openTable(Player p) {
        Setting g = new Setting(this, p, layout(p.getUniqueId()));
        p.openInventory(g.getInventory());
        m.haunt().sound(p, p.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.3f, 0.7f);
    }

    private static void closeTable(Player p) {
        if (p != null && p.getOpenInventory().getTopInventory().getHolder(false) instanceof Setting) p.closeInventory();
    }

    /** Setting: something was laid, lifted or taken off — kept at once, and shown on the table in the world. */
    void laid(Player p, Setting g) {
        saveLayout(p.getUniqueId(), g.layout);
        house.food(p, g.layout);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWindowClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Setting g)) return;
        e.setCancelled(true);   // nothing ever moves in this window, or out of the bag while it is open
        if (e.getWhoClicked() instanceof Player p && p.getUniqueId().equals(g.owner)) g.click(p, e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWindowDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Setting) e.setCancelled(true);
    }

    @EventHandler
    public void onWindowClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Setting g) || !(e.getPlayer() instanceof Player p)) return;
        g.closed(p);
        if (!g.changed || g.layout.isEmpty() || !p.isOnline()) return;
        int st = stage(p.getUniqueId());
        if (st == HOUSE || st == ACCEPTED) later(p, 10, () -> whisper(p, "lines.laid"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Setting.sweep(e.getPlayer());
    }

    // ------------------------------------------------------------------------------------------
    // the bows, and the judging
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onBow(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        House.Shown s = house.get(u);
        if (s == null || s.busy || !p.getWorld().equals(s.base.getWorld()) || guide(p)) return;
        Location l = p.getLocation();
        if (Math.abs(l.getY() - s.base.getY()) > 2.5
                || !Place.before(l.getX(), l.getZ(), s.base.getX() + 0.5, s.base.getZ() + 0.5, cfg.getDouble("bow.reach", 5))) return;
        long now = System.currentTimeMillis(), gap = Math.max(500, cfg.getLong("bow.seconds", 3) * 1000L);
        Long once = bowed.get(u);
        if (once != null && now - once <= gap) {
            bowed.remove(u);
            rite(p, s);
            return;
        }
        bowed.put(u, now);
        m.haunt().sound(p, l, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.3f, 0.7f);
        later(p, gap / 50 + 8, () -> {
            if (!Long.valueOf(now).equals(bowed.get(u))) return;
            bowed.remove(u);
            // one bow before a laid table at night: a word; anywhere else it is only someone crouching
            if (house.get(u) == s && !s.busy && s.lit && s.smoke && !layout(u).isEmpty()) whisper(p, "lines.bow-once");
        });
    }

    /** Two bows before the table. */
    private void rite(Player p, House.Shown s) {
        UUID u = p.getUniqueId();
        boolean real = !s.preview;
        if (real) {
            int st = stage(u);
            if (!on() || st != HOUSE && st != ACCEPTED) return;
            if (!open(u)) {
                late(p);
                return;
            }
        }
        if (!night(u, s.base.getWorld())) {
            whisper(p, "lines.day");
            return;
        }
        if (real && waiting(u) > 0) {
            whisper(p, "lines.incense-out");
            return;
        }
        Map<Integer, String> layout = layout(u);
        if (layout.isEmpty()) {
            whisper(p, "lines.empty");
            return;
        }
        if (real && !lacking(p, layout).isEmpty()) {
            whisper(p, "lines.not-in-hand");
            return;
        }
        closeTable(p);
        house.food(p, layout);
        Table.Verdict v = Table.judge(layout, rules());
        if (real) m.set(this, u, "tries", m.getLong(this, u, "tries", 0) + 1);
        if (v.ok()) {
            if (real) m.stage(this, u, ACCEPTED);
            meal(p, s, layout, !real);
        } else refuse(p, s, layout, v, !real);
    }

    /** Wrong: what is wrong leaves the table (and the layout), what is owed is tapped, and the incense is out for a while. */
    private void refuse(Player p, House.Shown s, Map<Integer, String> layout, Table.Verdict v, boolean preview) {
        UUID u = p.getUniqueId();
        if (!preview) {
            Map<Integer, String> left = new TreeMap<>(layout);
            left.keySet().removeAll(v.wrong());
            saveLayout(u, left);
            long wait = m.test(u) ? cfg.getLong("test-retry-seconds", 5) : cfg.getLong("retry-seconds", 300);
            m.set(this, u, "retry", System.currentTimeMillis() + Math.max(0, wait) * 1000L);
            if (stage(u) == ACCEPTED) m.stage(this, u, HOUSE);
        }
        house.refuse(p, s, v, () -> {
            whisper(p, "lines.out");
            house.food(p, layout(u));
            if (preview) later(p, 60, () -> {
                if (house.get(u) == s) house.smoke(s, true);
            });
        });
    }

    /** Right: they come and eat. */
    private void meal(Player p, House.Shown s, Map<Integer, String> layout, boolean preview) {
        UUID u = p.getUniqueId();
        house.meal(p, s, layout, rules(), (n, at) -> say(p, null, at, text("lines.meal-" + n)), () -> {
            concluding.add(u);
            dawn(p);
            house.dissolve(p, s);
            later(p, 60, () -> conclude(p, preview));
        });
    }

    /** Dawn for them alone: their sky runs on to morning and stays ahead of the world's for a while. */
    private void dawn(Player p) {
        UUID u = p.getUniqueId();
        long from = Math.floorMod(p.getWorld().getTime(), 24000L);
        long span = Math.floorMod(24000L + cfg.getLong("dawn.time", 500) - from, 24000L);
        dawned.add(u);
        int steps = 16;
        for (int i = 1; i <= steps; i++) {
            long ahead = span * i / steps;
            later(p, i * 3L, () -> {
                if (dawned.contains(u)) p.setPlayerTime(ahead, true);
            });
        }
        later(p, Math.max(5, cfg.getLong("dawn.seconds", 90)) * 20L, () -> {
            if (dawned.remove(u)) p.resetPlayerTime();
        });
    }

    /** The meal is over and the house is grass: the food is taken and the secret solved, in one go. */
    private void conclude(Player p, boolean preview) {
        UUID u = p.getUniqueId();
        concluding.remove(u);
        if (preview) {
            p.sendMessage(Text.mm("<yellow>[연출] 음복 끝 (아무것도 바뀌지 않음)"));
            return;
        }
        if (stage(u) != ACCEPTED) return;
        if (!open(u)) {
            late(p);
            return;
        }
        Map<Integer, String> layout = layout(u);
        if (layout.isEmpty() || !lacking(p, layout).isEmpty()) {   // the food left their hands while the dead ate: nothing was eaten
            m.stage(this, u, HOUSE);
            long wait = m.test(u) ? cfg.getLong("test-retry-seconds", 5) : cfg.getLong("retry-seconds", 300);
            m.set(this, u, "retry", System.currentTimeMillis() + Math.max(0, wait) * 1000L);
            whisper(p, "lines.gone");
            return;
        }
        for (Map.Entry<String, Integer> need : Table.needs(layout).entrySet()) Items.remove(p.getInventory(), it -> real(it, need.getKey()), need.getValue());
        m.remove(this, u, "layout");
        m.solve(this, p);
        if (stage(u) <= stages()) return;
        for (ItemStack left : p.getInventory().addItem(jokbo(p)).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.sendMessage(Text.mm(text("lines.dawn")));
    }

    /** The family register: an invented line that ends, in fresh ink, with the one who set the table. */
    private ItemStack jokbo(Player p) {
        ItemStack it = Items.create(JOKBO, 1).orElseGet(() -> new ItemStack(Material.WRITTEN_BOOK));
        String date = DAY.format(ZonedDateTime.now(SecretModule.KST));
        List<String> lore = new ArrayList<>();
        for (String l : cfg.getStringList("jokbo.lore")) lore.add(l.replace("{player}", p.getName()).replace("{date}", date));
        it.editMeta(meta -> {
            meta.itemName(Text.item(text("jokbo.name")));
            if (!lore.isEmpty()) meta.lore(Text.itemLines(lore));
        });
        if (it.getItemMeta() instanceof BookMeta bm) {
            List<Component> pages = new ArrayList<>();
            for (Object page : cfg.getList("jokbo.pages", List.of())) {
                if (!(page instanceof List<?> raw)) continue;
                List<String> lines = new ArrayList<>();
                for (Object o : raw) lines.add(String.valueOf(o).replace("{player}", p.getName()).replace("{date}", date));
                pages.add(Text.mm(String.join("\n", lines)));
            }
            if (pages.isEmpty()) pages.add(Text.mm("<black>…대신 상을 차린 이: " + p.getName() + "\n<dark_gray>" + date));
            String title = text("jokbo.title");
            bm.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
            bm.setAuthor(text("jokbo.author"));
            bm.setGeneration(BookMeta.Generation.ORIGINAL);
            bm.pages(pages);
            it.setItemMeta(bm);
        }
        return Items.markBought(it);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        cleanup(u);
        m.remove(this, u, "late");
        m.remove(this, u, "retry");
        if (stage <= HEARD) {
            m.remove(this, u, "house");
            m.remove(this, u, "stop");
            m.stage(this, u, Math.max(0, stage));   // 1: she is not on any bus, so the house comes at the next stop they pull into
            return;
        }
        Location at = beside(p);
        m.set(this, u, "house", Place.encode(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        if (stage >= ACCEPTED) {   // a right table, and the food for it in their bag
            Map<Integer, String> right = Table.sample(rules());
            saveLayout(u, right);
            for (Map.Entry<String, Integer> lack : lacking(p, right).entrySet())
                Items.create(lack.getKey(), lack.getValue()).ifPresent(it -> {
                    for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
                });
        }
        m.stage(this, u, Math.min(ACCEPTED, stage));
    }

    @Override
    public void cleanup(UUID u) {
        Player p = Bukkit.getPlayer(u);
        closeTable(p);
        house.hide(u);
        blessing.cleanup(u);
        riding.remove(u);
        lastStop.remove(u);
        bowed.remove(u);
        concluding.remove(u);
        if (dawned.remove(u) && p != null) p.resetPlayerTime();
    }

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        int st = stage(u);
        String day = m.get(this, u, "day");
        if (st == 0) out.add(m.today(u).equals(day) ? "오늘 확률은 이미 굴림" : "할머니 손님을 태우고 정류장에 서면 확률 " + cfg.getDouble("start.chance", 0.01));
        if (st == HEARD) out.add(riding.containsKey(u) ? "할머니가 타고 있음 (내리는 정류장에 집)" : "할머니를 놓침 → 다음에 서는 정류장에 집");
        String h = m.get(this, u, "house");
        if (h != null) out.add("집 " + h + " (" + stopName(m.get(this, u, "stop")) + " 정류장)" + (house.get(u) != null ? " · 보이는 중" : ""));
        if (st == HOUSE || st == ACCEPTED) {
            Map<Integer, String> layout = layout(u);
            Table.Verdict v = Table.judge(layout, rules());
            out.add("상 " + layout.size() + "가지" + (layout.isEmpty() ? "" : v.ok() ? " (맞음)" : " (틀린 것 " + v.wrong().size() + " · 빠진 것 " + v.missing().size() + ")"));
            long wait = waiting(u);
            if (wait > 0) out.add("향 꺼짐 " + (wait + 999) / 1000 + "초 남음");
            long tries = m.getLong(this, u, "tries", 0);
            if (tries > 0) out.add("절 " + tries + "번");
        }
        if (m.get(this, u, "late") != null) out.add("늦었다는 말을 들음");
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("할머니", "집", "상", "틀림", "음복", "음덕");
    }

    /** The house an admin's scene plays at: the one they see, else a look-only one beside them. */
    private House.Shown sceneHouse(Player p, Map<Integer, String> food) {
        UUID u = p.getUniqueId();
        House.Shown s = house.get(u);
        if (s == null || s.busy) s = house.show(p, beside(p), true, food);
        else house.food(p, food);
        house.light(s, true);
        house.smoke(s, true);
        return s;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "할머니" -> {
                BusModule b = bus();
                List<Entity> aboard = b == null ? List.of() : b.ridersOf(p, kind());
                say(p, aboard.isEmpty() ? null : aboard.getFirst(), ahead(p), text("lines.overhear"));
            }
            case "집" -> {
                House.Shown s = house.show(p, beside(p), true, layout(u));
                house.light(s, night(u, s.base.getWorld()));
                house.smoke(s, true);
                say(p, null, ahead(p), text("lines.house"));
            }
            case "상" -> openTable(p);
            case "틀림" -> {
                Table.Rules r = rules();
                Map<Integer, String> wrong = new LinkedHashMap<>(Table.sample(r));
                String meat = wrong.remove(Table.slot(1, Table.WEST_AT)), fish = wrong.remove(Table.slot(1, Table.EAST_AT));
                if (fish != null) wrong.put(Table.slot(1, Table.WEST_AT), fish);   // 어동육서, the wrong way round
                if (meat != null) wrong.put(Table.slot(1, Table.EAST_AT), meat);
                wrong.remove(Table.slot(0, Table.WEST_AT));   // no rice
                if (!r.taboo().isEmpty()) wrong.put(Table.slot(3, Table.MID), r.taboo().iterator().next());
                House.Shown s = sceneHouse(p, wrong);
                house.refuse(p, s, Table.judge(wrong, r), () -> {
                    whisper(p, "lines.out");
                    house.food(p, layout(u));
                    later(p, 60, () -> {
                        if (house.get(u) == s) house.smoke(s, true);
                    });
                });
            }
            case "음복" -> {
                Map<Integer, String> food = layout(u);
                if (food.isEmpty() || !Table.judge(food, rules()).ok()) food = Table.sample(rules());
                House.Shown s = sceneHouse(p, food);
                meal(p, s, food, true);
            }
            case "음덕" -> {
                if (!blessing.play(p, Set.of(p))) admin.sendMessage(Text.mm("<red>뒤에 설 자리가 없어요 (평평한 곳에서 다시)"));
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " → " + p.getName()));
        return true;
    }
}
