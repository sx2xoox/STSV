package dev.stsv.economy.secret.jesa;

import dev.stsv.core.util.Lang;
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
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
 * off, a thatched shelter stands for that driver only, with a rite table and its empty vessels ({@link House},
 * {@link Vessel}; stage 2). The driver puts food made on this server on the vessels — really: one of it leaves the hand and
 * lies on the vessel, saved at once — and bows twice before it at night. The answer is not in the game: it is how a real
 * 제사상 is set ({@link Table}). Wrong: what is wrong rolls off and comes back to them, the spoon taps the empty vessels
 * that are owed, the incense goes out for a while. Right (stage 3): the dead come to eat — and there are two of them. Both
 * tablets were on the screen all along: the grandmother was dead too, and it was her rite as well. Dawn, grass, solved;
 * what was on the table is eaten.
 * The solver keeps a family register with their own name inked in last and, for good, two old people who now and then
 * stand behind them where others can see ({@link Blessing}).
 * <p>
 * Saved (eco.kv "secret.jesa.&lt;uuid&gt;."): stage, since, heard (the stop where
 * she was overheard), house ("world x y z": the table's block), stop (where she got off), plate.&lt;0..14&gt; (what lies on
 * that vessel: the item's bytes in Base64, {@link Vessel#pack}), retry (ms: no rite before it), tries, late (told that
 * someone else was first; what was on the table went back), done. An old "layout" (the window of an earlier version, which
 * never took anything) is deleted on load.
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
            Map.entry("lines.placed", ""),
            Map.entry("lines.taken", ""),
            Map.entry("lines.bow-once", "<gray>…한 번으로는 모자라다."),
            Map.entry("lines.out", "<gray>…향이 꺼졌다."),
            Map.entry("lines.not-this", "<gray>…이건 상에 올릴 수 없다."),
            Map.entry("lines.not-now", "<gray>…지금은 상에 손대지 않는다."),
            Map.entry("lines.gone", "<gray>…상이 비어 있다. 아무도 먹지 못했다."),
            Map.entry("lines.returned", "<gray>…상에 올렸던 것은 돌아왔다."),
            Map.entry("mail-reason", "감나무 아래 그 집에서"),
            Map.entry("lines.meal-1", "…영감, 많이 드시우."),
            Map.entry("lines.meal-2", "…나도 한술 뜨고."),
            Map.entry("lines.meal-3", "…고맙수. 길에서 만난 사람한테 얻어먹는 제삿밥이 제일 달다더니."),
            Map.entry("lines.meal-4", "…차비는 냈수. 밥값은, 이걸로 받으우."),
            Map.entry("lines.dawn", "<gray>…날이 밝았다. 감나무 아래에는 풀만 자라 있다."),
            Map.entry("tablets.father", "顯考學生府君神位"),
            Map.entry("tablets.mother", "顯妣孺人達城徐氏神位"),
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
    /** player -> when they last touched a vessel (ms): one touch per click, however the client sends it */
    private final Map<UUID, Long> touched = new HashMap<>();
    /** house places already looked at for water this run (player -> saved house) */
    private final Map<UUID, String> dryChecked = new HashMap<>();
    static final String MAIL = "jesa";
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
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("mail.reason." + MAIL, text("mail-reason"));
        Lang.useExtra("STSV-Economy-secret-jesa", lang);
        // the window of an earlier version only remembered a layout (nothing was ever taken for it): gone
        String prefix = "secret." + id() + ".";
        for (String k : eco.kv().all().keySet()) if (k.startsWith(prefix) && k.endsWith(".layout")) eco.kv().remove(k);
    }

    YamlConfiguration cfg() {
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

    // ------------------------------------------------------------------------------------------
    // what lies on the vessels (kv "plate.<n>": the item itself)
    // ------------------------------------------------------------------------------------------

    /** vessel -> saved value, for every vessel that has something on it. */
    private Map<Integer, String> saved(UUID u) {
        Map<Integer, String> out = new TreeMap<>();
        for (int v = 0; v < Vessel.COUNT; v++) {
            String raw = m.get(this, u, Vessel.key(v));
            if (raw != null) out.put(v, raw);
        }
        return out;
    }

    /** A saved value as the item (one of it), or null when it does not read. */
    private ItemStack unpack(String raw) {
        byte[] bytes = Vessel.unpack(raw);
        if (bytes == null) return null;
        try {
            ItemStack it = Items.fromBytes(bytes);
            return it == null || it.isEmpty() ? null : it;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** vessel -> the item on it (one), for every vessel whose item reads. */
    Map<Integer, ItemStack> plates(UUID u) {
        Map<Integer, ItemStack> out = new TreeMap<>();
        for (Map.Entry<Integer, String> e : saved(u).entrySet()) {
            ItemStack it = unpack(e.getValue());
            if (it != null) out.put(e.getKey(), it);
            else eco.getLogger().warning("[비밀] 주인없는제사: " + u + " 의 제기 " + e.getKey() + " 에 놓인 것을 읽을 수 없음 (그대로 둠)");
        }
        return out;
    }

    /** vessel -> item id ({@link Items#key}), for the judge. */
    private static Map<Integer, String> ids(Map<Integer, ItemStack> plates) {
        Map<Integer, String> out = new TreeMap<>();
        for (Map.Entry<Integer, ItemStack> e : plates.entrySet()) out.put(e.getKey(), Items.key(e.getValue()));
        return out;
    }

    /** Forgets a vessel's item in kv (the only way one is taken off: given back, eaten, or replaced). */
    private void dropPlate(UUID u, int v) {
        m.remove(this, u, Vessel.key(v));
    }

    /** Back to them: their pockets, else the mailbox — never the ground. False when there is no mailbox to send it by. */
    private boolean giveBack(UUID u, ItemStack it) {
        if (eco.mail() == null) return false;
        eco.mail().deliver(u, it, MAIL);
        return true;
    }

    /**
     * Takes what lies on a vessel off and gives it back. expected: the saved value it must still be (null: whatever is
     * there). Taken off kv first, given back in the same tick; if giving fails it stays on the vessel.
     */
    private boolean takeOff(UUID u, int v, String expected) {
        String raw = m.get(this, u, Vessel.key(v));
        if (raw == null || expected != null && !expected.equals(raw)) return false;
        ItemStack it = unpack(raw);
        if (it == null || eco.mail() == null) return false;
        dropPlate(u, v);
        try {
            giveBack(u, it);
            return true;
        } catch (RuntimeException e) {
            m.set(this, u, Vessel.key(v), raw);   // still on the vessel
            eco.getLogger().warning("[비밀] 주인없는제사: 돌려주지 못해 제기 " + v + " 에 그대로 둠 (" + u + "): " + e);
            return false;
        }
    }

    /** Everything on their vessels back to them (pockets, else the mailbox). How many went back. */
    private int returnAll(UUID u) {
        int n = 0;
        for (int v : saved(u).keySet()) if (takeOff(u, v, null)) n++;
        return n;
    }

    /** Things that may not lie on a vessel: 길라잡이 trial things, a secret's own things, anything that holds other things. */
    private boolean refused(ItemStack it) {
        if (it == null || it.isEmpty() || Items.trial(it) || m.refuseListing(it) != null || Items.key(it).equals(JOKBO)) return true;
        if (Tag.SHULKER_BOXES.isTagged(it.getType())) return true;
        ItemMeta meta = it.getItemMeta();
        return meta instanceof BundleMeta || meta instanceof BlockStateMeta b && b.hasBlockState() && b.getBlockState() instanceof Container;
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
        d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, rides ? 0.95f : 0, 0), new org.joml.AxisAngle4f(),
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
        // she may get off at the very stop where she spoke: her second line waits until the first has been read
        long said = System.currentTimeMillis() - m.since(this, u);
        long wait = said >= 0 && said < 7000 ? (7000 - said) / 50 + 20 : 20;
        m.set(this, u, "house", Place.encode(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        m.set(this, u, "stop", here.id());
        m.remove(this, u, "retry");
        m.stage(this, u, HOUSE);
        World w = at.getWorld();
        Location sign = new Location(w, here.x(), here.y() + 2.6, here.z());
        later(p, wait, () -> say(p, her, sign, text("lines.house")));
        later(p, wait + 50, () -> m.haunt().sound(p, at.clone().add(0.5, 1, 0.5), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.6f));
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
        Location at = new Location(w, x, y + 1, z);
        return Boolean.TRUE.equals(dry(at)) ? at : null;
    }

    private static boolean wet(Block b) {
        return Place.wet(b.getType().name(), b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged(), b.isLiquid());
    }

    /**
     * Whether a house whose table's block is `at` stands on dry ground: no column of its 5×5 has water (or ice, kelp,
     * seagrass, anything waterlogged) at, just under or just over its ground. null: not all of it is loaded.
     */
    private static Boolean dry(Location at) {
        World w = at.getWorld();
        if (w == null) return null;
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ(), r = Place.REACH;
        for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++)
            for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) if (!w.isChunkLoaded(cx, cz)) return null;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -1 - Place.WET_BELOW; dy <= Place.WET_ABOVE; dy++)
                    if (wet(w.getBlockAt(x + dx, y + dy, z + dz))) return false;
        return true;
    }

    /**
     * A saved house turned out to stand on water (chosen before this rule, or the water came since). One by a stop is
     * chosen again near that stop; if there is no dry spot, the next stop they pull into will do (stage 1 again; what lies
     * on its vessels stays saved and comes with the new house). An admin's house is kept, and logged. Whether to show a
     * house now.
     */
    private boolean resited(Player p, String where) {
        UUID u = p.getUniqueId();
        String stopId = m.get(this, u, "stop");
        BusStop st = null;
        try {
            st = stopId == null ? null : stop(Integer.parseInt(stopId));
        } catch (NumberFormatException ignored) {
            // an admin's house
        }
        if (stopId == null) {
            eco.getLogger().warning("[비밀] 주인없는제사: " + p.getName() + " 의 집 (관리자가 세운 것) 이 물가에 있음: " + where + " — 그대로 둠");
            return true;
        }
        Location again = st == null ? null : pick(p, st);
        if (again != null) {
            m.set(this, u, "house", Place.encode(again.getWorld().getName(), again.getBlockX(), again.getBlockY(), again.getBlockZ()));
            eco.getLogger().info("[비밀] 주인없는제사: " + p.getName() + " 의 집이 물 위였음 (" + where + ") → 다시 고름: " + m.get(this, u, "house"));
            return true;
        }
        m.remove(this, u, "house");
        m.remove(this, u, "stop");
        m.stage(this, u, HEARD);
        eco.getLogger().info("[비밀] 주인없는제사: " + p.getName() + " 의 집이 물 위였음 (" + where + "), 근처에 마른 땅이 없어 다음 정류장에서 다시");
        return false;
    }

    /**
     * A spot for an admin's house: flat ground a few blocks from them, else simply four blocks ahead — never on water
     * (null then: the admin is told).
     */
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
        Location settled = settle(new Location(w, at.getBlockX(), me.getBlockY(), at.getBlockZ()));
        return Boolean.TRUE.equals(dry(settled)) ? settled : null;
    }

    /** Tells the admins online (and the log) that no dry spot was found for a house beside this player. */
    private void noDryGround(Player p, CommandSender admin) {
        String line = "<red>[비밀] 주인없는제사: " + p.getName() + " 옆에 물이 없는 평평한 땅이 없어요 (집을 세우지 않음). 마른 땅에서 다시";
        eco.getLogger().warning("[비밀] 주인없는제사: " + p.getName() + " 옆에 마른 땅이 없어 집을 세우지 않음");
        if (admin != null) {
            admin.sendMessage(Text.mm(line));
            return;
        }
        for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("stsv.economy.admin")) o.sendMessage(Text.mm(line));
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
            if (st == HEARD) {
                if (open(u)) watchHer(p);
                else if (m.get(this, u, "late") == null && !saved(u).isEmpty()) late(p);   // things saved on a table that moved
            }
            if (st != HOUSE && st != ACCEPTED || concluding.contains(u) || guide(p)) {
                if (s != null && !s.busy && !concluding.contains(u)) house.hide(u);
                continue;
            }
            boolean closed = !open(u);
            if (closed) {   // someone else set it first: what is on their table goes back to them, said once
                if (m.get(this, u, "late") == null) late(p);
                else {
                    if (s != null && !s.busy) house.hide(u);
                    if (!saved(u).isEmpty()) returnAll(u);   // put back by an admin since: it goes back too
                }
                continue;
            }
            Location h = home(u);
            if (h == null) continue;
            if (s == null) {
                if (!within(p, h, show) || !h.isChunkLoaded()) continue;
                Location at = settle(h);
                String where = m.get(this, u, "house");
                if (!where.equals(dryChecked.get(u))) {   // never on water: looked at once per place and run
                    Boolean dry = dry(at);
                    if (dry == null) continue;   // not all of it loaded yet
                    // moved (shown from the next second, where it now is), or gone back to stage 1: not here
                    if (!dry && (!resited(p, where) || !where.equals(m.get(this, u, "house")))) continue;
                    dryChecked.put(u, where);
                }
                s = house.show(p, at, false, plates(u));
                house.light(s, night(u, h.getWorld()));
                house.smoke(s, waiting(u) == 0);
                continue;
            }
            if (!within(p, s.base, show + 8)) {
                house.hide(u);
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

    /** Someone else set it first: said once, everything on their table goes back to them, and the house is gone for them. */
    private void late(Player p) {
        UUID u = p.getUniqueId();
        int back = returnAll(u);   // always (idempotent): nothing stays on a table nobody will eat from
        if (m.get(this, u, "late") != null) return;
        m.set(this, u, "late", 1);
        p.sendMessage(Text.msg(text("too-late")));
        if (back > 0) p.sendMessage(Text.msg(text("lines.returned")));
        House.Shown s = house.get(u);
        if (s != null && !s.preview) house.dissolve(p, s);
    }

    // ------------------------------------------------------------------------------------------
    // the vessels: putting food on, taking it back
    // ------------------------------------------------------------------------------------------

    /**
     * The "at" half of the same click (a separate event with its own listeners): cancelled too and otherwise ignored, so
     * one press acts once (in {@link #onTouch}) and nothing else acts on a vessel's click box.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTouchAt(PlayerInteractAtEntityEvent e) {
        if (e.getRightClicked() instanceof Interaction hit && hit.getPersistentDataContainer().has(House.TAG)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTouch(PlayerInteractEntityEvent e) {
        if (e instanceof PlayerInteractAtEntityEvent) return;   // its own handler list; never acted on twice
        if (!(e.getRightClicked() instanceof Interaction hit) || !hit.getPersistentDataContainer().has(House.TAG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;   // the off hand's click of the same press does nothing
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        String tag = hit.getPersistentDataContainer().get(House.TAG, PersistentDataType.STRING);
        House.Shown s = house.get(u);
        int v = House.vesselOf(hit);
        if (tag == null || !tag.startsWith(u.toString()) || s == null || v < 0 || !hit.equals(s.hits.get(v)) || guide(p)) return;
        // only within an arm's reach of that very vessel (the client's word is not taken for it)
        if (!hit.getWorld().equals(p.getWorld()) || !Vessel.inReach(p.getEyeLocation().distanceSquared(hit.getLocation()))) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        long now = System.currentTimeMillis();
        Long last = touched.get(u);
        if (last != null && now - last < 250) return;
        touched.put(u, now);
        if (s.busy || concluding.contains(u)) {
            whisper(p, "lines.not-now");
            return;
        }
        if (s.preview) {   // an admin's look: the vessels take nothing
            m.haunt().sound(p, house.dishAt(s, v), Sound.BLOCK_WOOD_HIT, 0.3f, 1.4f);
            return;
        }
        int st = stage(u);
        if (!on() || st != HOUSE && st != ACCEPTED) return;
        if (!open(u)) {
            late(p);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) take(p, s, v);
        else if (p.getGameMode() == GameMode.CREATIVE) whisper(p, "lines.not-this");   // a creative hand is not a real one
        else put(p, s, v, hand);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction hit && hit.getPersistentDataContainer().has(House.TAG)) e.setCancelled(true);
    }

    /**
     * Puts one of what is in the main hand on the vessel, in one tick: saved first, then taken from the hand (if that fails
     * the save is undone). Something already on it is swapped: it comes back once the new one is down.
     */
    private void put(Player p, House.Shown s, int v, ItemStack hand) {
        UUID u = p.getUniqueId();
        if (refused(hand) || eco.mail() == null) {   // no mailbox: nothing could ever come back off the vessel
            whisper(p, "lines.not-this");
            m.haunt().sound(p, house.dishAt(s, v), Sound.BLOCK_WOOD_HIT, 0.3f, 0.6f);
            return;
        }
        String key = Vessel.key(v);
        String old = m.get(this, u, key);
        ItemStack before = old == null ? null : unpack(old);
        if (old != null && (before == null || eco.mail() == null)) {   // what is there can not be given back: leave it
            whisper(p, "lines.not-now");
            return;
        }
        ItemStack one = hand.asOne();
        String packed;
        try {
            packed = Vessel.pack(Items.bytes(one));
        } catch (RuntimeException ex) {
            whisper(p, "lines.not-this");
            return;
        }
        m.set(this, u, key, packed);
        // now out of the hand: exactly one, the same thing (else the save is undone and nothing moved)
        PlayerInventory inv = p.getInventory();
        ItemStack now = inv.getItemInMainHand();
        if (now.isEmpty() || !now.isSimilar(one)) {
            if (old == null) m.remove(this, u, key);
            else m.set(this, u, key, old);
            return;
        }
        int left = now.getAmount() - 1;
        if (left <= 0) inv.setItemInMainHand(null);
        else {
            ItemStack rest = now.clone();
            rest.setAmount(left);
            inv.setItemInMainHand(rest);
        }
        if (before != null) {   // the one it replaced
            try {
                giveBack(u, before);
            } catch (RuntimeException ex) {
                eco.getLogger().severe("[비밀] 주인없는제사: 바꿔 놓은 것을 돌려주지 못함 (" + u + ", 제기 " + v + "), 복구용 값: " + old + " — " + ex);
            }
        }
        house.food(p, plates(u));
        Location at = house.dishAt(s, v);
        m.haunt().sound(p, at, Vessel.kind(v) == Vessel.Kind.PLATE || Vessel.kind(v) == Vessel.Kind.DEEP ? Sound.BLOCK_WOOD_PLACE : Sound.BLOCK_DECORATED_POT_INSERT,
                0.35f, 1.3f);
        String line = text("lines.placed");
        if (!line.isBlank()) whisper(p, "lines.placed");
    }

    /** Takes what lies on the vessel back into the (empty) hand's inventory, else the mailbox. */
    private void take(Player p, House.Shown s, int v) {
        UUID u = p.getUniqueId();
        if (m.get(this, u, Vessel.key(v)) == null) return;
        if (!takeOff(u, v, null)) {
            whisper(p, "lines.not-now");
            return;
        }
        house.food(p, plates(u));
        m.haunt().sound(p, house.dishAt(s, v), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.35f, 1.1f);
        String line = text("lines.taken");
        if (!line.isBlank()) whisper(p, "lines.taken");
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
            if (house.get(u) == s && !s.busy && s.lit && s.smoke && !saved(u).isEmpty()) whisper(p, "lines.bow-once");
        });
    }

    /** Two bows before the table: what lies on the vessels is judged. */
    private void rite(Player p, House.Shown s) {
        UUID u = p.getUniqueId();
        boolean real = !s.preview;
        if (real) {
            int st = stage(u);
            if (!on() || st != HOUSE && st != ACCEPTED || concluding.contains(u)) return;
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
        Map<Integer, ItemStack> food = real ? plates(u) : house.shownFood(s);
        if (food.isEmpty()) {
            whisper(p, "lines.empty");
            return;
        }
        if (real) house.food(p, food);
        Map<Integer, String> layout = ids(food);
        Table.Verdict v = Table.judge(layout, rules());
        if (real) m.set(this, u, "tries", m.getLong(this, u, "tries", 0) + 1);
        if (v.ok()) {
            if (real) m.stage(this, u, ACCEPTED);
            meal(p, s, layout, !real);
        } else refuse(p, s, v, !real);
    }

    /**
     * Wrong: what is wrong rolls off its vessel and comes back to them (as it reaches the ground: until then it is still
     * saved on the vessel, so a quit or a reload in between leaves it there), what is owed is tapped, the incense is out.
     */
    private void refuse(Player p, House.Shown s, Table.Verdict v, boolean preview) {
        UUID u = p.getUniqueId();
        Map<Integer, String> judged = preview ? Map.of() : saved(u);
        if (!preview) {
            m.set(this, u, "retry", retryAt(u));
            if (stage(u) == ACCEPTED) m.stage(this, u, HOUSE);
        }
        house.refuse(p, s, v, slot -> {
            if (!preview) takeOff(u, slot, judged.get(slot));
        }, () -> {
            whisper(p, "lines.out");
            if (!preview) house.food(p, plates(u));
            else later(p, 60, () -> {
                if (house.get(u) == s) house.smoke(s, true);
            });
        });
    }

    /** Right: they come and eat. */
    private void meal(Player p, House.Shown s, Map<Integer, String> layout, boolean preview) {
        UUID u = p.getUniqueId();
        house.meal(p, s, layout, rules(), (n, at) -> say(p, null, at, text("lines.meal-" + n)), () -> {
            concluding.add(u);
            if (preview || open(u)) dawn(p);   // no morning for a table someone else was first to set
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

    /** Their own sky goes back to the world's (the end of the dawn, or anything that cuts it short). */
    private void undawn(Player p) {
        if (dawned.remove(p.getUniqueId())) p.resetPlayerTime();
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        undawn(e.getPlayer());   // the offset was counted on the other world's clock
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        undawn(e.getEntity());
    }

    /** What the table costs when the incense goes out (ms from now). */
    private long retryAt(UUID u) {
        long wait = m.test(u) ? cfg.getLong("test-retry-seconds", 5) : cfg.getLong("retry-seconds", 300);
        return System.currentTimeMillis() + Math.max(0, wait) * 1000L;
    }

    /** The meal is over and the house is grass: the food is taken and the secret solved, in one go. */
    private void conclude(Player p, boolean preview) {
        UUID u = p.getUniqueId();
        // only the end of a meal that is still theirs: a quit (cleanup) or a reload in the last seconds leaves stage 3 to
        // be bowed for again — `p` may be the player of an earlier login by now, whose bag is not the one they carry
        if (!concluding.remove(u) || Bukkit.getPlayer(u) != p) return;
        if (preview) {
            p.sendMessage(Text.mm("<yellow>[연출] 음복 끝 (아무것도 바뀌지 않음)"));
            return;
        }
        if (!on() || stage(u) != ACCEPTED) {
            undawn(p);
            return;
        }
        if (!open(u)) {
            undawn(p);
            late(p);
            return;
        }
        Map<Integer, ItemStack> food = plates(u);
        // the table is no longer the one that was accepted (an admin took things off meanwhile): nothing was eaten
        if (food.isEmpty() || saved(u).size() != food.size() || !Table.judge(ids(food), rules()).ok()) {
            m.stage(this, u, HOUSE);
            m.set(this, u, "retry", retryAt(u));
            undawn(p);
            whisper(p, "lines.gone");
            return;
        }
        // solved first, what lay on the table eaten right after in the same tick: nothing goes for an end that did not come
        m.solve(this, p);
        if (stage(u) <= stages()) {
            undawn(p);
            return;
        }
        for (int v : food.keySet()) dropPlate(u, v);   // already out of their bag since it was put down: eaten now
        ItemStack book = jokbo(p);
        if (eco.mail() != null) eco.mail().deliver(u, book, MAIL);
        else for (ItemStack left : p.getInventory().addItem(book).values()) p.getWorld().dropItem(p.getLocation(), left);
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
        dryChecked.remove(u);
        if (stage <= HEARD) {
            returnAll(u);   // no table any more: what was on it goes back
            m.remove(this, u, "house");
            m.remove(this, u, "stop");
            m.stage(this, u, Math.max(0, stage));   // 1: she is not on any bus, so the house comes at the next stop they pull into
            return;
        }
        Location at = beside(p);
        if (at == null) {
            noDryGround(p, null);
            return;
        }
        m.set(this, u, "house", Place.encode(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        m.remove(this, u, "stop");   // this house is beside them, not by a stop
        if (stage >= ACCEPTED) {   // a right table: what was on it goes back, and newly made things are put on the vessels
            returnAll(u);
            for (Map.Entry<Integer, String> e : Table.sample(rules()).entrySet())
                Items.create(e.getValue(), 1).ifPresent(it -> m.set(this, u, Vessel.key(e.getKey()), Vessel.pack(Items.bytes(it))));
        }
        m.stage(this, u, Math.min(ACCEPTED, stage));
    }

    @Override
    public void cleanup(UUID u) {
        Player p = Bukkit.getPlayer(u);
        house.hide(u);
        blessing.cleanup(u);
        riding.remove(u);
        lastStop.remove(u);
        bowed.remove(u);
        touched.remove(u);
        concluding.remove(u);
        if (p != null) undawn(p);
        else dawned.remove(u);
    }

    /** An admin's 초기화: everything still on their vessels goes back to them first (pockets, else the mailbox). */
    @Override
    public void resetting(UUID u) {
        int n = returnAll(u);
        if (n > 0) eco.getLogger().info("[비밀] 주인없는제사: 초기화 전에 제기 위의 것 " + n + "개를 돌려줌 (" + u + ")");
        // what could not be given back (unreadable, or no mailbox) is about to be deleted: its saved value, to restore by hand
        for (Map.Entry<Integer, String> e : saved(u).entrySet())
            eco.getLogger().severe("[비밀] 주인없는제사: 초기화로 지워지는 제기 " + e.getKey() + " 의 것을 돌려주지 못함 (" + u + "), 복구용 값 (serializeAsBytes, Base64): " + e.getValue());
    }

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        int st = stage(u);
        if (st == 0) out.add("할머니 손님을 태우고 정류장에 설 때마다 확률 " + cfg.getDouble("start.chance", 0.01));
        if (st == HEARD) out.add(riding.containsKey(u) ? "할머니가 타고 있음 (내리는 정류장에 집)" : "할머니를 놓침 → 다음에 서는 정류장에 집");
        String h = m.get(this, u, "house");
        String stop = m.get(this, u, "stop");
        if (h != null) out.add("집 " + h + (stop == null ? "" : " (" + stopName(stop) + " 정류장)") + (house.get(u) != null ? " · 보이는 중" : ""));
        Map<Integer, String> laid = ids(plates(u));
        if (!laid.isEmpty() || st == HOUSE || st == ACCEPTED) {
            Table.Verdict v = Table.judge(laid, rules());
            out.add("제기 " + laid.size() + "/" + Vessel.COUNT + (laid.isEmpty() ? "" : v.ok() ? " (맞음)" : " (틀린 것 " + v.wrong().size()
                    + " · 빠진 자리 " + v.missing().size() + ")"));
            for (int row = 0; row < Table.ROWS; row++) {
                List<String> cells = new ArrayList<>();
                for (int col = 0; col < Table.COLS; col++) {
                    int slot = Table.slot(row, col);
                    String id = laid.get(slot);
                    cells.add(Vessel.side(slot) + " " + (id == null ? "-" : id.replace("economy:", "")) + (v.wrong().contains(slot) ? "✗" : ""));
                }
                out.add((row + 1) + "열[" + String.join(", ", cells) + "]");
            }
        }
        if (st == HOUSE || st == ACCEPTED) {
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
        return List.of("할머니", "집", "제기", "틀림", "음복", "음덕");
    }

    /** Newly made things for a look (nothing of anyone's): vessel -> item. */
    private static Map<Integer, ItemStack> made(Map<Integer, String> ids) {
        Map<Integer, ItemStack> out = new TreeMap<>();
        for (Map.Entry<Integer, String> e : ids.entrySet()) Items.create(e.getValue(), 1).ifPresent(it -> out.put(e.getKey(), it));
        return out;
    }

    /** A look-only house beside them for an admin's scene (its vessels take nothing); null when there is no dry ground. */
    private House.Shown sceneHouse(Player p, CommandSender admin, Map<Integer, ItemStack> food) {
        Location at = beside(p);
        if (at == null) {
            noDryGround(p, admin);
            return null;
        }
        House.Shown s = house.show(p, at, true, food);
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
            case "집", "제기" -> {
                Map<Integer, ItemStack> look = new TreeMap<>();
                for (Map.Entry<Integer, ItemStack> e : plates(u).entrySet()) look.put(e.getKey(), e.getValue().clone());
                House.Shown s = sceneHouse(p, admin, look);
                if (s == null) return true;
                house.light(s, night(u, s.base.getWorld()));
                if (word.equals("집")) say(p, null, ahead(p), text("lines.house"));
            }
            case "틀림" -> {
                Table.Rules r = rules();
                Map<Integer, String> wrong = new TreeMap<>(Table.sample(r));
                String meat = wrong.remove(Table.slot(1, Table.WEST_AT)), fish = wrong.remove(Table.slot(1, Table.EAST_AT));
                if (fish != null) wrong.put(Table.slot(1, Table.WEST_AT), fish);   // 어동육서, the wrong way round
                if (meat != null) wrong.put(Table.slot(1, Table.EAST_AT), meat);
                wrong.remove(Table.slot(0, Table.WEST_AT));   // no rice
                if (!r.taboo().isEmpty()) wrong.put(Table.slot(3, Table.MID), r.taboo().iterator().next());
                House.Shown s = sceneHouse(p, admin, made(wrong));
                if (s == null) return true;
                house.refuse(p, s, Table.judge(wrong, r), slot -> {}, () -> {
                    whisper(p, "lines.out");
                    later(p, 60, () -> {
                        if (house.get(u) == s) house.smoke(s, true);
                    });
                });
            }
            case "음복" -> {
                Map<Integer, ItemStack> food = new TreeMap<>();
                for (Map.Entry<Integer, ItemStack> e : plates(u).entrySet()) food.put(e.getKey(), e.getValue().clone());
                if (food.isEmpty() || !Table.judge(ids(food), rules()).ok()) food = made(Table.sample(rules()));
                House.Shown s = sceneHouse(p, admin, food);
                if (s == null) return true;
                meal(p, s, ids(food), true);
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
