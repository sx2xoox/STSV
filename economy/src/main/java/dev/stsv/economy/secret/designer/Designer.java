package dev.stsv.economy.secret.designer;

import dev.stsv.core.util.Text;
import dev.stsv.economy.job.MachineModule;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 설계자 (docs/admin/숨은_비밀/설계자.md; contents/economy/secrets/designer.yml). The one who designed every machine on the
 * server is gone; he wanted his last machine to live.
 * <p>
 * Now and then (any hour; a very low chance per product), a product of the player's machine comes out signed "설계자 —" instead of their name (stage 1).
 * From then on the machines' recipe books have his notes at the back, for that player only, one more for each part made:
 * the rib frame (bone + iron at a forge), the vein hose (redstone + vines at a still), the eye lens (spider eye + glass at a
 * gem bench) and the heart gear (the three parts in one lane of a fourth kind of machine, with something the player made
 * left in that lane's output slot). Each lane runs backwards a few seconds, breathes, and gives the part (stages 2..4). With
 * the heart the four machines stop together, the book's last page thanks them and the 13th machine stands up beside the
 * last one ({@link Thirteenth}) — solved. He never vanished: he went into his machines, and the parts are the body he wants
 * back; only the heart needs something of a living person. Afterwards every product of the solver's machines carries the
 * maker line "제작: 이름 · 설계자의 후계" ({@link #makerLine}).
 * <p>
 * Saved (eco.kv "secret.designer.&lt;uuid&gt;."): stage, marked (the day it first signed), read (notes seen), &lt;part&gt;.machine
 * and &lt;part&gt;.kind (where each part was made), owed (solved while offline). The 13th machine: {@link Thirteenth#KEY}.
 */
public final class Designer extends Secret {

    /** On a product he signed: the player whose machine it came from. */
    public static final NamespacedKey MARK = new NamespacedKey("economy", "designer_mark");
    /** On a part: the one who made it (a part works only for them). */
    public static final NamespacedKey OWNER = new NamespacedKey("economy", "designer_owner");
    /** On a preview's click box. */
    static final NamespacedKey PREVIEW = new NamespacedKey("economy", "designer_preview");

    /** A lane of a machine as the designer sees it (MachineModule hands it over every second while it holds something). */
    public record Lane(UUID machine, String kind, UUID operator, Inventory inv, int[] inputs, int output) {}

    private record Part(String id, String item, String machine, Map<String, Integer> in, List<String> lore, String made) {}

    private final Map<String, Part> parts = new LinkedHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /** Players with a part on its way (one at a time: two lanes at once must not both count for one stage). */
    private final Set<UUID> making = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> hinted = new ConcurrentHashMap<>();
    private final Thirteenth thirteenth;
    private final Mover mover;

    public Designer(SecretModule m) {
        super(m);
        thirteenth = new Thirteenth(this, eco);
        mover = new Mover(this, eco, thirteenth);
    }

    @Override
    public String id() {
        return "designer";
    }

    @Override
    public String name() {
        return "설계자";
    }

    @Override
    public int stages() {
        return 4;
    }

    YamlConfiguration cfg() {
        return cfg;
    }

    @Override
    protected void loaded() {
        parts.clear();
        for (String id : Parts.ORDER) {
            ConfigurationSection s = cfg.getConfigurationSection("parts." + id);
            if (s == null) {
                eco.getLogger().warning("[비밀] designer.yml: parts." + id + " 없음");
                continue;
            }
            Map<String, Integer> in = new LinkedHashMap<>();
            ConfigurationSection is = s.getConfigurationSection("in");
            if (is != null) for (String k : is.getKeys(false)) in.put(Items.normalize(k), Math.max(1, is.getInt(k, 1)));
            parts.put(id, new Part(id, s.getString("item", "economy:designer_" + id), s.getString("machine", Parts.ANY), in,
                    s.getStringList("lore"), s.getString("made", "")));
        }
    }

    // ------------------------------------------------------------------------------------------
    // small things
    // ------------------------------------------------------------------------------------------

    private String text(String key) {
        return cfg.getString(key, "");
    }

    /** A sound from designer.yml's sounds (breath, heartbeat, page, assemble) at a spot, for everyone near. */
    void sound(Location at, String key) {
        String s = cfg.getString("sounds." + key + ".sound");
        if (s == null || at.getWorld() == null) return;
        at.getWorld().playSound(at, s, SoundCategory.BLOCKS, (float) cfg.getDouble("sounds." + key + ".volume", 0.5),
                (float) cfg.getDouble("sounds." + key + ".pitch", 1));
    }

    /** The same, only for one player. */
    private void sound(Player p, Location at, String key) {
        String s = cfg.getString("sounds." + key + ".sound");
        if (s == null) return;
        p.playSound(at, s, SoundCategory.AMBIENT, (float) cfg.getDouble("sounds." + key + ".volume", 0.5),
                (float) cfg.getDouble("sounds." + key + ".pitch", 1));
    }

    /** A whisper above the hotbar at most every 30 s (the hints a lane gives while it waits). */
    private void hint(Player p, String key) {
        long now = System.currentTimeMillis();
        Long last = hinted.get(p.getUniqueId());
        if (last != null && now - last < 30_000) return;
        hinted.put(p.getUniqueId(), now);
        String t = text(key);
        if (!t.isEmpty()) m.haunt().whisper(p, t);
    }

    /** A designer whisper to one player (Mover). */
    void whisper(Player p, String line) {
        m.haunt().whisper(p, line);
    }

    private MachineModule machines() {
        return eco.machines();
    }

    private String kindName(String kind) {
        MachineSpec s = kind == null ? null : machines().specs().get(kind);
        return s == null ? String.valueOf(kind) : s.name();
    }

    /** The level that counts for the first sign: in that machine's job, else (the press) in the job they have now. */
    private int level(UUID u, String job) {
        return job == null ? eco.jobs().level(u) : eco.jobs().level(u, job);
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    // ------------------------------------------------------------------------------------------
    // the first sign: a product signed by someone else
    // ------------------------------------------------------------------------------------------

    /** MachineModule: a lane of op's just made this (into an empty output slot); he may sign it. */
    public void made(UUID op, String job, ItemStack product) {
        if (!on()) return;
        Player p = Bukkit.getPlayer(op);
        if (p == null || !open(op)) return;
        int st = stage(op);
        // no clock condition: machines run all day here, so the chance per product is kept very low instead
        if (st == 0) {
            if (!m.test(op) && level(op, job) < cfg.getInt("start.min-level", 20)) return;
            if (m.today(op).equals(m.get(this, op, "marked"))) return;
            if (!m.roll(op, cfg.getDouble("start.chance", 0.0002))) return;
            sign(product, op);
            begin(p);
        } else if (st <= stages() && !m.test(op)
                && ThreadLocalRandom.current().nextDouble() < cfg.getDouble("echo-chance", 0.002)) {
            sign(product, op);   // now and then, again: he is still there
        }
    }

    /** His name instead of the maker's on a stack (and it no longer counts as the player's own work). */
    private void sign(ItemStack it, UUID u) {
        it.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            Component line = Text.item(text("mark-line").isEmpty() ? "<dark_gray>제작: 설계자 —" : text("mark-line"));
            boolean done = false;
            for (int i = lore.size() - 1; i >= 0 && !done; i--) {
                if (PlainTextComponentSerializer.plainText().serialize(lore.get(i)).startsWith("제작: ")) {
                    lore.set(i, line);
                    done = true;
                }
            }
            if (!done) lore.add(line);
            meta.lore(lore);
            meta.getPersistentDataContainer().remove(MachineModule.MAKER);
            meta.getPersistentDataContainer().set(MARK, PersistentDataType.STRING, u.toString());
        });
    }

    /**
     * Whether this player's machines carry his name beside theirs: they solved it — the one real solver, or a tester while
     * test mode is on. Admin 초기화 (stage gone) takes it away. Not while the secret is switched off.
     */
    boolean heir(UUID u) {
        if (!on() || stage(u) <= stages()) return false;
        return m.test(u) || m.solver(this).map(u::equals).orElse(false);
    }

    /**
     * MachineModule: the maker line for a product of op's machine — "제작: 이름 · 설계자의 후계" for the heir, else null (the
     * usual line). Asked for every product and every lane's stacking check, so the two always agree.
     */
    public Component makerLine(UUID op, String name) {
        if (op == null || !heir(op)) return null;
        String t = Parts.heirLine(text("heir-line"), name);
        return t == null ? null : Text.item(t);
    }

    private void begin(Player p) {
        UUID u = p.getUniqueId();
        m.stage(this, u, 1);
        m.set(this, u, "marked", m.today(u));
        m.set(this, u, "read", 0);
    }

    @Override
    public void start(Player p) {
        begin(p);
        ItemStack sample = Items.create(cfg.getString("start.sample-item", "minecraft:iron_ingot"), 1).orElse(new ItemStack(Material.IRON_INGOT));
        sign(sample, p.getUniqueId());
        for (ItemStack left : p.getInventory().addItem(sample).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    // ------------------------------------------------------------------------------------------
    // the notes at the back of the recipe book
    // ------------------------------------------------------------------------------------------

    /** How many notes this player has: one per stage reached (4 at most), and the last page once it is done. */
    private int notes(UUID u) {
        int st = stage(u);
        if (st <= 0) return 0;
        return Math.min(st, 4) + (st > stages() ? 1 : 0);
    }

    @SuppressWarnings("unchecked")
    private List<String> page(int i) {
        List<?> pages = cfg.getList("memo.pages", List.of());
        if (i < pages.size() && pages.get(i) instanceof List<?> l) return (List<String>) l.stream().map(String::valueOf).toList();
        return List.of();
    }

    /** MachineModule: a machine's window opened. A note not read yet rustles. */
    public void opened(Player p) {
        UUID u = p.getUniqueId();
        if (!on() || notes(u) <= m.getLong(this, u, "read", 0)) return;
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            sound(p, p.getLocation(), "page");
            m.haunt().whisper(p, text("unread-whisper"));
        }, 12L);
    }

    /** MachineModule: the entries at the back of the book (lane-less recipe list) for this player. */
    public List<ItemStack> memos(Player p) {
        UUID u = p.getUniqueId();
        if (!on()) return List.of();
        int n = notes(u);
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(memoIcon(i, i == 4));
        if (n > 0 && m.getLong(this, u, "read", 0) != n) m.set(this, u, "read", n);
        return out;
    }

    private ItemStack memoIcon(int i, boolean last) {
        ItemStack it = new ItemStack(Material.PAPER);
        List<String> lines = last ? cfg.getStringList("memo.final") : page(i);
        it.editMeta(meta -> {
            meta.itemName(Text.item(last ? text("memo.final-name") : text("memo.name").replace("{n}", String.valueOf(i + 1))));
            List<Component> lore = new ArrayList<>();
            for (String l : lines) if (!Parts.PAGE_BREAK.equals(l)) lore.add(Text.item("<#8a8170><i>" + l));
            lore.add(Component.empty());
            lore.add(Text.item(text("memo.click")));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    /** MachineModule: a note was clicked: the notes so far open as a book (the clicked one first). */
    public void memoClick(Player p, int index) {
        int n = notes(p.getUniqueId());
        if (index < 0 || index >= n) return;
        Bukkit.getScheduler().runTask(eco, () -> {
            if (!p.isOnline()) return;
            p.closeInventory();
            p.openBook(book(n, index));
            sound(p, p.getLocation(), "page");
        });
    }

    private Book book(int n, int first) {
        List<Component> pages = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = (first + k) % n;
            List<String> lines = i == 4 ? cfg.getStringList("memo.final") : page(i);
            // a note too long for one book page goes on over the next at its "---" line
            for (List<String> part : Parts.bookPages(lines)) pages.add(Text.mm("<#3b3326>" + String.join("\n", part)));
        }
        return Book.book(Text.mm(text("memo.book-title")), Text.mm(text("memo.book-author")), pages);
    }

    // ------------------------------------------------------------------------------------------
    // the parts: a lane with exactly the right things runs backwards
    // ------------------------------------------------------------------------------------------

    private static String key(ItemStack it) {
        return it == null || it.isEmpty() || Items.shopBought(it) ? null : Items.key(it);
    }

    private boolean mine(ItemStack it, UUID u) {
        return it != null && it.hasItemMeta() && u.toString().equals(it.getItemMeta().getPersistentDataContainer().get(OWNER, PersistentDataType.STRING));
    }

    /** The kinds of machine the earlier parts were made in. */
    private Set<String> usedKinds(UUID u) {
        Set<String> out = new HashSet<>();
        for (String id : Parts.ORDER) {
            String k = m.get(this, u, id + ".kind");
            if (k != null) out.add(k);
        }
        return out;
    }

    /**
     * MachineModule, every second for a lane that holds something: true when the designer takes it (it then makes a part and
     * the machine does nothing else meanwhile).
     */
    public boolean lane(Lane l) {
        if (busy.contains(l.machine())) return true;
        if (!on()) return false;
        UUID u = l.operator();
        String id = Parts.forStage(stage(u));
        Part part = id == null ? null : parts.get(id);
        Player p = Bukkit.getPlayer(u);
        if (part == null || p == null || !open(u) || making.contains(u)) return false;
        List<String> keys = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        for (int s : l.inputs()) {
            ItemStack it = l.inv().getItem(s);
            String k = key(it);
            // a part is only a part for the one who made it
            if (k != null && k.startsWith("economy:designer_") && !mine(it, u)) return false;
            keys.add(k);
            amounts.add(it == null ? 0 : it.getAmount());
        }
        Map<String, Integer> want = part.id().equals("heart") ? heartInputs() : part.in();
        if (!Parts.exactly(keys, amounts, want)) return false;
        if (!Parts.kindFits(part.machine(), l.kind(), usedKinds(u))) {
            hint(p, part.id().equals("heart") ? "heart-used" : "wrong-machine");
            return false;
        }
        ItemStack out = l.inv().getItem(l.output());
        boolean heart = part.id().equals("heart");
        if (heart ? !ownWork(out, u) : out != null && !out.isEmpty()) {
            hint(p, heart ? "heart-empty" : "no-room");
            return false;
        }
        Location where = machines().machineAt(l.machine());
        if (where == null) return false;
        // it goes in: the things are taken now, the part comes out at the end
        take(l, want);
        if (heart) {
            l.inv().setItem(l.output(), null);
            if (out.getAmount() > 1) give(p, out.asQuantity(out.getAmount() - 1));   // one is enough; the rest go back
        }
        machines().touched(l.machine());
        busy.add(l.machine());
        making.add(u);
        int seconds = cfg.getInt(heart ? "heart-reverse-seconds" : "reverse-seconds", heart ? 6 : 4);
        reverse(l.machine(), seconds, heart, () -> {
            busy.remove(l.machine());
            making.remove(u);
            made(l, u, part, where);
        });
        return true;
    }

    private Map<String, Integer> heartInputs() {
        Map<String, Integer> want = new LinkedHashMap<>();
        for (String id : List.of("rib", "vein", "lens")) {
            Part q = parts.get(id);
            if (q != null) want.put(Items.normalize(q.item()), 1);
        }
        return want;
    }

    /** Something this player made (their own maker tag, not one he signed). */
    private static boolean ownWork(ItemStack it, UUID u) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return false;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        return u.toString().equals(pdc.get(MachineModule.MAKER, PersistentDataType.STRING)) && !pdc.has(MARK);
    }

    private static void take(Lane l, Map<String, Integer> want) {
        Map<String, Integer> left = new LinkedHashMap<>(want);
        for (int s : l.inputs()) {
            ItemStack it = l.inv().getItem(s);
            String k = key(it);
            if (k == null || !left.containsKey(k)) continue;
            int t = Math.min(left.get(k), it.getAmount());
            left.merge(k, -t, Integer::sum);
            l.inv().setItem(s, it.getAmount() - t <= 0 ? null : it.asQuantity(it.getAmount() - t));
        }
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** A part, bound to its maker. */
    private ItemStack partItem(Part part, UUID u) {
        ItemStack it = Items.create(part.item(), 1).orElse(new ItemStack(Material.PAPER));
        it.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            for (String l : part.lore()) lore.add(Text.item(l));
            String bound = text("bound-line");
            if (!bound.isEmpty()) lore.add(Text.item(bound.replace("{player}", Players.name(u))));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, u.toString());
        });
        return it;
    }

    /** The lane ran backwards: the part lies in its output slot (or in their hands, if the machine is gone). */
    private void made(Lane l, UUID u, Part part, Location where) {
        Location at = machines().machineAt(l.machine());
        ItemStack it = partItem(part, u);
        ItemStack slot = l.inv().getItem(l.output());
        Player p = Bukkit.getPlayer(u);
        // the machine still stands (its chunk may have unloaded since they left): the part waits in its output slot
        if (machines().machineKind(l.machine()) != null && (slot == null || slot.isEmpty())) {
            l.inv().setItem(l.output(), it);
            machines().touched(l.machine());
        } else if (p != null) give(p, it);
        else where.getWorld().dropItem(where.clone().add(0, 0.5, 0), it);   // broken meanwhile and they are gone: where it stood
        m.set(this, u, part.id() + ".machine", l.machine().toString());
        m.set(this, u, part.id() + ".kind", l.kind());
        if (at != null) sound(at.clone().add(0, 0.8, 0), part.id().equals("heart") ? "heartbeat" : "breath");
        if (p != null && at != null && p.getWorld() == at.getWorld() && p.getLocation().distanceSquared(at) < 32 * 32 && !part.made().isEmpty())
            m.haunt().whisper(p, part.made());
        // only the stage this part was for moves on (not again after an admin reset or jump meanwhile)
        int st = Parts.ORDER.indexOf(part.id()) + 1;
        if (stage(u) != st) return;
        if (!part.id().equals("heart")) {
            m.stage(this, u, st + 1);
            return;
        }
        Bukkit.getScheduler().runTaskLater(eco, () -> finale(u, l), 30L);
    }

    // ------------------------------------------------------------------------------------------
    // a machine running backwards
    // ------------------------------------------------------------------------------------------

    /**
     * Holds the machine and turns its parts (wheels) backwards for a while; things are pulled into it rather than thrown out,
     * its own working sound plays low, and halfway it breathes in. Then end runs.
     */
    private void reverse(UUID machine, int seconds, boolean heart, Runnable end) {
        machines().hold(machine, seconds * 1000L + 1500);
        Location at = machines().machineAt(machine);
        if (at == null) {
            end.run();
            return;
        }
        Location mid = at.clone().add(0, 0.8, 0);
        List<ItemDisplay> wheels = machines().machineParts(machine);
        Map<ItemDisplay, String[]> spins = new LinkedHashMap<>();
        for (ItemDisplay w : wheels) {
            String[] s = spin(w);
            if (s != null) spins.put(w, s);
        }
        String kind = machines().machineKind(machine);
        MachineSpec spec = kind == null ? null : machines().specs().get(kind);
        long start = at.getWorld().getGameTime();
        int total = seconds * 20;
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (t >= total) {
                    cancel();
                    end.run();
                    return;
                }
                for (Map.Entry<ItemDisplay, String[]> e : spins.entrySet()) {
                    ItemDisplay w = e.getKey();
                    if (!w.isValid()) continue;
                    float rpm;
                    try {
                        rpm = Float.parseFloat(e.getValue()[1]);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    // the furniture's angle comes from the world time; here the time runs the other way
                    double turns = (start - t - 2) * (double) rpm / 1200.0;
                    double angle = ((turns % 2.0) + 2.0) % 2.0 * 2 * Math.PI;
                    Quaternionf q = switch (e.getValue()[0]) {
                        case "x" -> new Quaternionf().rotateX((float) angle);
                        case "z" -> new Quaternionf().rotateZ((float) angle);
                        default -> new Quaternionf().rotateY((float) angle);
                    };
                    Transformation tr = w.getTransformation();
                    w.setInterpolationDelay(0);
                    w.setInterpolationDuration(2);
                    w.setTransformation(new Transformation(tr.getTranslation(), q, tr.getScale(), tr.getRightRotation()));
                }
                if (t % 10 == 0) {
                    inward(mid, heart);
                    if (spec != null && spec.effect() != null && spec.effect().sound() != null)
                        mid.getWorld().playSound(mid, spec.effect().sound(), SoundCategory.BLOCKS, Math.max(0.2f, spec.effect().volume()), 0.5f);
                }
                if (t == total / 2) sound(mid, "breath");
                t += 2;
            }
        }.runTaskTimer(eco, 0L, 2L);
    }

    /** A spinning part's axis and speed ("y", "12"), from the core's furniture mark on it. */
    private static String[] spin(ItemDisplay d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            if (!k.getKey().equals("spin")) continue;
            String v = pdc.get(k, PersistentDataType.STRING);
            if (v == null || !v.contains(":")) return null;
            return v.split(":", 2);
        }
        return null;
    }

    /** Smoke (or, for the heart, red dust) drawn into the machine from around it. */
    private static void inward(Location mid, boolean heart) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            Vector dir = new Vector(r.nextGaussian(), r.nextGaussian() * 0.6, r.nextGaussian()).normalize();
            Location from = mid.clone().add(dir.clone().multiply(1.3));
            if (heart) mid.getWorld().spawnParticle(Particle.DUST, from, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(110, 18, 22), 0.9f));
            Vector v = dir.multiply(-1);
            mid.getWorld().spawnParticle(Particle.SMOKE, from, 0, v.getX(), v.getY(), v.getZ(), 0.09);
        }
    }

    // ------------------------------------------------------------------------------------------
    // the end: the four machines stop, the last page, the 13th machine
    // ------------------------------------------------------------------------------------------

    private List<UUID> four(UUID u) {
        List<UUID> out = new ArrayList<>();
        for (String id : Parts.ORDER) {
            String s = m.get(this, u, id + ".machine");
            try {
                if (s != null && !s.isEmpty()) out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // a hand-edited value
            }
        }
        return out;
    }

    /** The four machines stop together and beat like one heart; then the heart is gone and the 13th machine stands up. */
    private void finale(UUID u, Lane last) {
        List<UUID> four = four(u);
        int pause = Math.max(2, cfg.getInt("pause-seconds", 5));
        pauseAll(four, pause);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player p = Bukkit.getPlayer(u);
            takeHeart(u, last);
            if (!m.test(u) && m.closed(this)) {
                if (p != null) p.sendMessage(Text.mm(cfg.getString("too-late", "<dark_gray>…누군가 먼저였다.")));
                return;
            }
            Location at = machines().machineAt(last.machine());
            Location spot = spot(at != null ? at : p != null ? p.getLocation() : null, p);
            if (spot != null && !thirteenth.place(spot, m.test(u), true, u) && p != null)
                p.sendMessage(Text.mm("<yellow>[테스트] 13번째 기계가 이미 서 있어 옮기지 않았어요 (진짜 주인의 것)"));
            // owed until solveNow runs: gone now, or leaving (or a restart) in the seconds before it does → solved on their next join
            m.set(this, u, "owed", 1);
            if (p == null) return;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) solveNow(p);
            }, 150L);
        }, pause * 20L + 10);
    }

    private void solveNow(Player p) {
        m.remove(this, p.getUniqueId(), "owed");
        m.set(this, p.getUniqueId(), "read", 4);   // the last page is new
        m.haunt().whisper(p, text("born-whisper"));
        sound(p, p.getLocation(), "page");
        m.solve(this, p);
        if (!heir(p.getUniqueId())) return;   // too late after all
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && !text("heir-whisper").isEmpty()) m.haunt().whisper(p, text("heir-whisper"));
        }, 80L);
    }

    /** Machines stop for a while, their heartbeat slowing. */
    private void pauseAll(List<UUID> ids, int seconds) {
        List<Location> spots = new ArrayList<>();
        for (UUID id : ids) {
            machines().hold(id, seconds * 1000L);
            Location at = machines().machineAt(id);
            if (at != null) spots.add(at.clone().add(0, 0.8, 0));
        }
        for (int i = 0; i < seconds; i++) {
            float pitch = 0.85f - 0.08f * i;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                for (Location s : spots) s.getWorld().playSound(s, cfg.getString("sounds.heartbeat.sound", "entity.warden.heartbeat"),
                        SoundCategory.BLOCKS, (float) cfg.getDouble("sounds.heartbeat.volume", 0.9), Math.max(0.4f, pitch));
            }, i * 20L + (long) (i * i * 2));
        }
    }

    /** The heart goes into the 13th machine: from the output slot, or from wherever they put it. */
    private void takeHeart(UUID u, Lane last) {
        Part heart = parts.get("heart");
        if (heart == null) return;
        java.util.function.Predicate<ItemStack> is = it -> it != null && !it.isEmpty() && Items.key(it).equals(Items.normalize(heart.item())) && mine(it, u);
        if (is.test(last.inv().getItem(last.output()))) {
            last.inv().setItem(last.output(), null);
            machines().touched(last.machine());
            return;
        }
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;
        if (is.test(p.getItemOnCursor())) {
            p.setItemOnCursor(null);
            return;
        }
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            if (is.test(p.getInventory().getItem(i))) {
                p.getInventory().setItem(i, null);
                return;
            }
        }
    }

    /** A free spot (air on solid ground) beside a machine, the one nearest the player; else where the player stands. */
    private static Location spot(Location near, Player p) {
        if (near == null) return null;
        Block c = near.getBlock();
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int r = 2; r <= 4 && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        Block b = c.getRelative(dx, dy, dz);
                        if (!b.isEmpty() || !b.getRelative(0, 1, 0).isEmpty() || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
                        double d = p != null && p.getWorld() == b.getWorld() ? b.getLocation().distanceSquared(p.getLocation()) : dx * dx + dz * dz;
                        if (d < bd) {
                            bd = d;
                            best = b;
                        }
                    }
                }
            }
        }
        return best != null ? best.getLocation() : p != null ? p.getLocation() : near;
    }

    @Override
    public void tick() {
        thirteenth.tick();
    }

    /** Who may move the 13th machine: the one it was made for, else (older ones) whoever solved it first. */
    private Optional<UUID> machineOwner() {
        return thirteenth.owner().or(() -> m.solver(this));
    }

    private boolean mayMove(Player p) {
        return machineOwner().map(p.getUniqueId()::equals).orElse(false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTouch(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (mover.moving(p)) {   // moving it: any entity clicked counts as a click on the spot
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) mover.click(p);
            return;
        }
        if (!(e.getRightClicked() instanceof Interaction x)) return;
        PersistentDataContainer pdc = x.getPersistentDataContainer();
        if (!pdc.has(Thirteenth.TAG) && !pdc.has(PREVIEW)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        boolean owner = pdc.has(Thirteenth.TAG) && on() && mayMove(p);
        if (owner && p.isSneaking()) {
            mover.start(p);
            return;
        }
        List<String> lines = cfg.getStringList("touch");
        if (!lines.isEmpty()) m.haunt().whisper(p, lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
        sound(p, x.getLocation().add(0, 1, 0), "breath");
        if (owner && !cfg.getString("thirteenth.move.tip", "").isEmpty()) p.sendActionBar(Text.mm(cfg.getString("thirteenth.move.tip")));
    }

    /** Moving the 13th machine: right-click puts it down (sneaking: cancels), left-click turns it; nothing else happens. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveClick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL || !mover.moving(p)) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        if (e.getHand() == EquipmentSlot.OFF_HAND) return;
        switch (e.getAction()) {
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> mover.click(p);
            case LEFT_CLICK_BLOCK, LEFT_CLICK_AIR -> mover.rotate(p);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveSwap(PlayerSwapHandItemsEvent e) {
        if (!mover.moving(e.getPlayer())) return;
        e.setCancelled(true);
        mover.rotate(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMoveHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !mover.moving(p)) return;
        e.setCancelled(true);
        mover.rotate(p);
    }

    /** Pieces of the 13th machine left behind where it no longer stands (moved or removed while unloaded) go. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        thirteenth.sweep(e.getEntities());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (m.get(this, p.getUniqueId(), "owed") == null) return;
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) solveNow(p);
        }, 100L);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /** Stage n: the parts before it in their bag (made at the usual kinds of machine), the notes up to it. */
    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        for (String id : Parts.ORDER) {
            m.remove(this, u, id + ".machine");
            m.remove(this, u, id + ".kind");
        }
        m.remove(this, u, "owed");
        super.jump(p, stage);
        if (stage <= 0) return;
        m.set(this, u, "marked", m.today(u));
        m.set(this, u, "read", 0);
        for (int i = 0; i < stage - 1 && i < 3; i++) {
            Part part = parts.get(Parts.ORDER.get(i));
            if (part == null) continue;
            if (!Parts.ANY.equals(part.machine())) m.set(this, u, part.id() + ".kind", part.machine());
            boolean has = false;
            for (ItemStack it : p.getInventory().getContents())
                if (it != null && Items.key(it).equals(Items.normalize(part.item())) && mine(it, u)) has = true;
            if (!has) give(p, partItem(part, u));
        }
    }

    @Override
    public void cleanup(UUID player) {
        hinted.remove(player);
        mover.end(player, null);
        thirteenth.clearPreviews();
    }

    @Override
    public String status(UUID u) {
        StringBuilder b = new StringBuilder("부품");
        Map<String, String> names = Map.of("rib", "갈비뼈", "vein", "핏줄", "lens", "눈", "heart", "심장");
        for (String id : Parts.ORDER) {
            String k = m.get(this, u, id + ".kind");
            b.append(' ').append(names.get(id)).append(k == null ? " <dark_gray>·</dark_gray>" : " <green>✔</green><dark_gray>(" + kindName(k) + ")</dark_gray>");
        }
        if (heir(u)) b.append(" <gray>· <#c9a46b>후계 표시</#c9a46b>");
        b.append(" <gray>· 오늘 표시 ").append(m.today(u).equals(m.get(this, u, "marked")) ? "있음" : "없음");
        b.append(" · 메모 ").append(m.getLong(this, u, "read", 0)).append('/').append(notes(u));
        b.append(" · 13번째 기계 ").append(thirteenth.where().map(w -> w + " " + facingName(thirteenth.facing()) + (thirteenth.isTest() ? " (테스트)" : "")
                + " 주인 " + machineOwner().map(Players::name).orElse("-")).orElse("없음"));
        return b.toString();
    }

    @Override
    public List<String> scenes() {
        return List.of("설계자표시", "후계표시", "메모", "종이", "거꾸로", "숨소리", "멈춤", "탄생", "13번째기계", "13번째기계이동", "13번째기계회전", "13번째기계제거");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        switch (word) {
            case "설계자표시" -> {
                ItemStack sample = Items.create(cfg.getString("start.sample-item", "minecraft:iron_ingot"), 1).orElse(new ItemStack(Material.IRON_INGOT));
                sign(sample, p.getUniqueId());
                give(p, sample);
            }
            case "후계표시" -> {
                ItemStack sample = Items.create(cfg.getString("start.sample-item", "minecraft:iron_ingot"), 1).orElse(new ItemStack(Material.IRON_INGOT));
                String t = Parts.heirLine(text("heir-line"), Players.accountName(p.getUniqueId()));
                if (t != null) sample.editMeta(meta -> meta.lore(List.of(Text.item(t))));
                give(p, sample);
                admin.sendMessage(Text.msg("<gray>후계 표시가 찍힌 견본을 줬어요 (모양만, 진행 · 후계 여부는 그대로). 지금 후계: "
                        + (heir(p.getUniqueId()) ? "<green>예" : "<white>아니오")));
            }
            case "메모" -> p.openBook(book(5, 0));
            case "종이" -> {
                sound(p, p.getLocation(), "page");
                m.haunt().whisper(p, text("unread-whisper"));
            }
            case "거꾸로" -> {
                UUID id = nearestMachine(admin, p, 8);
                if (id == null) return true;
                if (!busy.add(id)) return true;
                reverse(id, cfg.getInt("reverse-seconds", 4), false, () -> {
                    busy.remove(id);
                    Location at = machines().machineAt(id);
                    if (at != null) sound(at.clone().add(0, 0.8, 0), "breath");
                });
            }
            case "숨소리" -> {
                Location l = p.getLocation();
                l.subtract(l.getDirection().setY(0).normalize().multiply(2));
                sound(p, l, "breath");
            }
            case "멈춤" -> {
                List<UUID> near = machines().machinesNear(p.getLocation(), 16);
                if (near.isEmpty()) {
                    admin.sendMessage(Text.msg("<red>16칸 안에 기계가 없어요"));
                    return true;
                }
                pauseAll(near.subList(0, Math.min(4, near.size())), Math.max(2, cfg.getInt("pause-seconds", 5)));
            }
            case "탄생" -> {
                List<UUID> near = machines().machinesNear(p.getLocation(), 16);
                int pause = Math.max(2, cfg.getInt("pause-seconds", 5));
                pauseAll(near.subList(0, Math.min(4, near.size())), pause);
                Location at = near.isEmpty() ? p.getLocation() : machines().machineAt(near.getFirst());
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (!p.isOnline()) return;
                    thirteenth.preview(spot(at, p), 60);
                    Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (p.isOnline()) m.haunt().whisper(p, text("born-whisper"));
                    }, 150L);
                }, pause * 20L + 10);
                admin.sendMessage(Text.msg("<gray>미리보기: 60초 뒤 사라지는 13번째 기계 (저장 안 됨)"));
            }
            case "13번째기계" -> {
                thirteenth.place(p.getLocation(), false, true, null);
                admin.sendMessage(Text.msg("<green>13번째 기계를 " + thirteenth.where().orElse("?") + " 에 세웠어요 (서버에 하나, 있던 것은 옮김)"));
            }
            case "13번째기계이동" -> {
                int f = Parts.quarter(p.getLocation().getYaw());
                if (!thirteenth.move(p.getLocation(), f)) {
                    admin.sendMessage(Text.msg("<red>서 있는 13번째 기계가 없어요 (세우기: 13번째기계)"));
                    return true;
                }
                admin.sendMessage(Text.msg("<green>13번째 기계를 " + thirteenth.where().orElse("?") + " 로 옮겼어요 (앞: " + facingName(f) + ", 조립 연출 없음)"));
            }
            case "13번째기계회전" -> {
                if (!thirteenth.turn(1)) {
                    admin.sendMessage(Text.msg("<red>서 있는 13번째 기계가 없어요"));
                    return true;
                }
                admin.sendMessage(Text.msg("<green>13번째 기계를 90° 돌렸어요 (앞: " + facingName(thirteenth.facing()) + ")"));
            }
            case "13번째기계제거" -> {
                String was = thirteenth.where().orElse(null);
                thirteenth.remove();
                admin.sendMessage(Text.msg(was == null ? "<gray>서 있는 13번째 기계가 없어요" : "<green>13번째 기계를 치웠어요 (" + was + ")"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private String facingName(int quarter) {
        List<String> names = cfg.getStringList("thirteenth.move.facings");
        return names.size() == 4 ? names.get(Math.floorMod(quarter, 4)) : String.valueOf(quarter);
    }

    private UUID nearestMachine(CommandSender admin, Player p, double range) {
        List<UUID> near = machines().machinesNear(p.getLocation(), range);
        if (near.isEmpty()) {
            admin.sendMessage(Text.msg("<red>" + (int) range + "칸 안에 기계가 없어요"));
            return null;
        }
        return near.getFirst();
    }
}
