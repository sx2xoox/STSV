package dev.stsv.economy.secret.doll;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.auction.AuctionModule;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.trade.TradeModule;
import dev.stsv.economy.util.Items;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주인 없는 경매품 (docs/admin/숨은_비밀/주인_없는_경매품.md; contents/economy/secrets/doll.yml). Very rarely a listing
 * with no seller ("—") shows up in the auction house: a 낡은 인형 for 1 G, its description written backwards, ten minutes.
 * Whoever wins it is its first owner. Lost long ago, it passed through seven hands before it reached the auction house and
 * can only sleep once it has gone back the way it came (hence the backwards writing and "in the order they held me").
 * It cannot go home alone: it has to pass through seven different pairs of hands
 * (직거래 or anything else that puts it in someone's inventory). Each time it changes hands it whispers a riddle naming
 * the next one, from what the server really knows ({@link Riddles}); the wrong person makes it giggle and does not count.
 * Thirty minutes with one hand and it goes home to the first owner on its own, and the run starts again. The seventh
 * riddle sends it back to the first owner, who must carry it to where it was born and say the seven hands' first letters
 * ({@link Chain}). Then it sleeps; the solver keeps a small doll on their shoulder ({@link Companion}).
 * <p>
 * One doll on the server: its saved state is server-wide (eco.kv "secret.doll.*"). Every doll item carries a generation
 * number; when the doll is given again (it went home, came back from a chest, …) the old copies are dead and crumble.
 */
public final class Doll extends Secret {

    static final String ITEM = "economy:old_doll";
    static final NamespacedKey GEN = new NamespacedKey("economy", "doll_gen");
    static final NamespacedKey VER = new NamespacedKey("economy", "doll_v");
    private static final String K = "secret.doll.";
    private static final String REFUSE = "secret-doll.refuse";
    private static final String MAIL = "doll";

    private final Random rnd = new Random();
    private final Companion companion = new Companion(this);
    private Chain chain;
    /** First owners being asked the last question: until when (ms). Read on the chat thread. */
    private final Map<UUID, Long> asking = new ConcurrentHashMap<>();
    private final Map<UUID, Long> repeatAt = new HashMap<>();
    private final Map<UUID, Long> crumbled = new HashMap<>();
    private final Map<UUID, Long> swings = new HashMap<>();
    /** Displays shown during the lay-down scene, by viewer. */
    private final Map<UUID, List<Entity>> props = new HashMap<>();
    /** Since when (ms) the doll has been in nobody's inventory while its holder is online. */
    private long absentSince;
    /** The holder, online and with the doll in their inventory (this second's scan), else null. */
    private Player holding;
    private long warned, warnedLast;
    private boolean laying;

    public Doll(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "doll";
    }

    @Override
    public String name() {
        return "낡은인형";
    }

    /** Stage of the first owner = hands so far (1 … 7); 8 = solved. */
    @Override
    public int stages() {
        return Chain.HANDS;
    }

    // ------------------------------------------------------------------------------------------
    // settings and saved state
    // ------------------------------------------------------------------------------------------

    org.bukkit.plugin.Plugin plugin() {
        return eco;
    }

    YamlConfiguration cfg() {
        return cfg;
    }

    String kvGet(String k) {
        return eco.kv().get(K + k);
    }

    long kvLong(String k, long def) {
        return eco.kv().getLong(K + k, def);
    }

    void kvSet(String k, Object v) {
        eco.kv().set(K + k, v);
    }

    void kvRemove(String k) {
        eco.kv().remove(K + k);
    }

    private String line(String path) {
        return cfg.getString(path, "");
    }

    private int gen() {
        return (int) kvLong("gen", 0);
    }

    /** A new doll: every copy made before is dead from now on. */
    private int newGen() {
        int g = gen() + 1;
        kvSet("gen", g);
        return g;
    }

    private boolean told() {
        return kvGet("told") != null;
    }

    private void told(boolean v) {
        if (v) kvSet("told", 1);
        else kvRemove("told");
    }

    private long deadline() {
        return kvLong("deadline", 0);
    }

    private void deadline(long at) {
        if (at <= 0) kvRemove("deadline");
        else kvSet("deadline", at);
    }

    private void save() {
        if (chain == null) kvRemove("chain");
        else kvSet("chain", chain.encode());
    }

    private long chainMillis() {
        return Math.max(1, cfg.getLong("chain.minutes", 30)) * 60_000L;
    }

    private boolean relaxed() {
        return chain != null && m.test(chain.owner);
    }

    @Override
    protected void loaded() {
        chain = Chain.decode(kvGet("chain"));
        // texts other modules look up by key: the auction's refusal, the mailbox reason
        YamlConfiguration lang = new YamlConfiguration();
        lang.set(REFUSE, cfg.getString("auction.refuse", "<dark_gray>…"));
        lang.set("mail.reason." + MAIL, cfg.getString("mail-reason", "발신인 없음"));
        Lang.useExtra("STSV-Economy-secret-doll", lang);
        AuctionModule auction = eco.auction();
        if (auction != null) auction.ghost(new AuctionModule.Ghost() {
            @Override
            public boolean owns(Auction a) {
                return a.id == kvLong("auction", -1);
            }

            @Override
            public String seller(Auction a) {
                return cfg.getString("auction.seller", "—");
            }

            @Override
            public String refuse(ItemStack it) {
                return isDoll(it) ? REFUSE : null;
            }

            @Override
            public void ended(Auction a) {
                auctionEnded(a);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // the doll item
    // ------------------------------------------------------------------------------------------

    static boolean isDoll(ItemStack it) {
        return it != null && !it.isEmpty() && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(GEN, PersistentDataType.INTEGER);
    }

    private static int genOf(ItemStack it) {
        Integer g = it.getItemMeta().getPersistentDataContainer().get(GEN, PersistentDataType.INTEGER);
        return g == null ? -1 : g;
    }

    /** A doll of this generation; the auction's copy has its description backwards. */
    private ItemStack make(int gen, boolean listing) {
        ItemStack it = Items.create(ITEM, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        Items.markBought(it);   // never to the shop, requests, buy orders or machines
        it.editMeta(meta -> {
            meta.getPersistentDataContainer().set(GEN, PersistentDataType.INTEGER, gen);
            String name = cfg.getString("auction.name", "");
            if (!name.isEmpty()) meta.itemName(Text.item(name));
            List<String> lore = new ArrayList<>();
            if (listing) for (String l : cfg.getStringList("auction.lore")) lore.add(Chain.backwards(l));
            else lore.addAll(lore());
            meta.lore(Text.itemLines(lore));
            if (!listing) meta.getPersistentDataContainer().set(VER, PersistentDataType.INTEGER, version());
        });
        return it;
    }

    private int version() {
        return Objects.hash(gen(), chain == null ? "" : chain.encode());
    }

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s == null ? "?" : s);
    }

    /** The description while it travels: the hands it went through (wrong ones crossed out), the riddle standing. */
    private List<String> lore() {
        List<String> out = new ArrayList<>(cfg.getStringList("lore.head"));
        if (chain == null) return out;
        boolean home = chain.phase == Chain.Phase.HOME;
        out.add(line("lore.hands"));
        int n = 0;
        for (Chain.Hand h : chain.trail) {
            if (h.ok()) {
                n++;
                out.add(line(home && n > 1 ? "lore.smudged" : "lore.hand").replace("{n}", String.valueOf(n)).replace("{name}", esc(h.name())));
            } else {
                out.add(line("lore.wrong").replace("{name}", esc(h.name())));
            }
        }
        if (home) {
            out.add("");
            out.addAll(cfg.getStringList("lore.home"));
        } else if (chain.riddle != null) {
            out.add("");
            out.add(line("lore.riddle").replace("{riddle}", riddleText(chain.riddle)));
        }
        if (!chain.lost.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String l : chain.lost) names.add("<st>" + esc(l) + "</st>");
            out.add("");
            out.add(line("lore.lost").replace("{names}", String.join("<dark_gray>, </dark_gray>", names)));
        }
        return out;
    }

    /** Rewrites a doll stack's description when the journey changed since it was written. */
    private void refresh(ItemStack it) {
        if (chain == null || !isDoll(it)) return;
        int v = version();
        Integer had = it.getItemMeta().getPersistentDataContainer().get(VER, PersistentDataType.INTEGER);
        if (had != null && had == v) return;
        List<String> l = lore();
        it.editMeta(meta -> {
            meta.lore(Text.itemLines(l));
            meta.getPersistentDataContainer().set(VER, PersistentDataType.INTEGER, v);
        });
    }

    /** Gives the doll (this generation) to a player: their inventory when they are online with room, else the mailbox. */
    private void give(UUID u) {
        ItemStack it = make(gen(), false);
        Player p = Bukkit.getPlayer(u);
        if (p != null && !p.isDead() && p.getInventory().firstEmpty() >= 0) p.getInventory().addItem(it);
        else eco.mail().deliver(u, it, MAIL);
    }

    /** Takes every doll (any generation) off a player. */
    private void strip(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isDoll(inv.getItem(i))) inv.setItem(i, null);
        if (isDoll(p.getItemOnCursor())) p.setItemOnCursor(null);
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) for (int i = 0; i < top.getSize(); i++) if (isDoll(top.getItem(i))) top.setItem(i, null);
    }

    // ------------------------------------------------------------------------------------------
    // talk
    // ------------------------------------------------------------------------------------------

    private String riddleText(Riddles.Kind k) {
        return cfg.getString("riddles." + k.id(), "…");
    }

    private String fill(String s, Player p) {
        return s.replace("{place}", line("final.place-hint")).replace("{minutes}", String.valueOf(cfg.getLong("final.minutes", 5)))
                .replace("{tries}", String.valueOf(tries())).replace("{player}", esc(p == null ? "" : Names.plain(p)));
    }

    private void say(Player p, String path) {
        String s = line(path);
        if (!s.isEmpty()) p.sendMessage(Text.mm(fill(s, p)));
    }

    private void lines(Player p, String path) {
        for (String s : cfg.getStringList(path)) if (!s.isEmpty()) p.sendMessage(Text.mm(fill(s, p)));
    }

    private void whisper(Player p, String mm) {
        m.haunt().whisper(p, mm);
    }

    private void chime(Player p, float pitch) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.AMBIENT, 0.35f, pitch);
    }

    private void giggle(Player p) {
        say(p, "talk.giggle");
        for (int i = 0; i < 3; i++) {
            int d = i;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) m.haunt().behind(p, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.35f, 1.6f + d * 0.1f);
            }, 3L + i * 5L);
        }
    }

    private void steps(Player p) {
        for (int i = 0; i < 3; i++) {
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) m.haunt().behind(p, Sound.BLOCK_WOOL_STEP, 0.35f, 1.8f);
            }, i * 6L);
        }
    }

    private void turn(Player p) {
        whisper(p, line("haunt.turn"));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, SoundCategory.AMBIENT, 0.5f, 0.5f);
    }

    /** Tells the holder the riddle standing (choosing it if there is none yet); a told riddle is asked again. */
    private void riddle(Player p) {
        if (chain.last()) {
            chain.riddle = Riddles.Kind.HOME;
        } else {
            List<Riddles.Fact> facts = facts(p);
            if (chain.riddle == null || chain.riddle == Riddles.Kind.HOME) chain.riddle = Riddles.choose(seed(), chain.used, facts);
            chain.snapshot.addAll(Riddles.answers(chain.riddle, facts));
        }
        save();
        say(p, "talk.whisper");
        p.sendMessage(Text.mm(line("talk.riddle").replace("{riddle}", riddleText(chain.riddle))
                .replace("{n}", String.valueOf(Math.min(Chain.HANDS, chain.index())))));
        chime(p, 0.5f);
    }

    private long seed() {
        return chain.owner.getMostSignificantBits() * 31 + gen() * 1_000_003L + chain.index() * 7919L + kvLong("run", 0) * 104_729L;
    }

    /** First sight of the doll in its holder's inventory (bought, came home, given): it starts talking and the clock runs. */
    private void tell(Player p) {
        told(true);
        if (deadline() <= 0 && chain.phase == Chain.Phase.CHAIN) deadline(System.currentTimeMillis() + chainMillis());
        if (kvGet("intro") != null) {
            kvRemove("intro");
            lines(p, "talk.first");
        }
        if (kvGet("back") != null) {
            kvRemove("back");
            lines(p, "talk.came-home");
        }
        if (chain.phase == Chain.Phase.HOME) home(p);
        else riddle(p);
    }

    private void home(Player p) {
        lines(p, "talk.home");
        for (int i = 0; i < 3; i++) {
            float pitch = 0.8f - i * 0.1f;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) chime(p, pitch);
            }, i * 8L);
        }
    }

    // ------------------------------------------------------------------------------------------
    // the riddles' facts
    // ------------------------------------------------------------------------------------------

    /** Every online player who may receive it next (not the holder; not a counted hand unless testing), with their facts. */
    private List<Riddles.Fact> facts(Player ref) {
        List<Riddles.Fact> out = new ArrayList<>();
        UUID holder = chain == null ? null : chain.holder;
        List<String> friends = chain == null ? List.of() : m.memory().friends(chain.owner);
        for (Player o : Bukkit.getOnlinePlayers()) {
            UUID u = o.getUniqueId();
            if (o == ref || u.equals(holder)) continue;
            if (chain != null && !relaxed() && chain.held(u)) continue;
            out.add(fact(o, ref, friends));
        }
        return out;
    }

    private Riddles.Fact fact(Player o, Player ref, List<String> friends) {
        UUID u = o.getUniqueId();
        int land = 0;
        if (eco.land() != null) {
            var l = eco.land().store().index().ownedBy(u);
            land = l == null ? 0 : l.size();
        }
        double fish = 0;
        if (eco.fishing() != null) for (var e : eco.fishing().store().of(u).values()) fish = Math.max(fish, e.best);
        double dist = -1;
        if (ref != null) dist = ref.getWorld().equals(o.getWorld()) ? ref.getLocation().distance(o.getLocation()) : Double.POSITIVE_INFINITY;
        return new Riddles.Fact(u, Names.plain(o), land, m.memory().sessionStart(u), o.getFirstPlayed(), fish, o.getLocation().getBlockY(), dist,
                m.core().economy().balance(u), friends.indexOf(o.getName()));
    }

    /** Whether the riddle standing names this receiver (when it was told, or now). */
    private boolean matches(Player y, Player giver) {
        if (chain.last() || chain.riddle == null || chain.riddle == Riddles.Kind.HOME) return false;
        UUID u = y.getUniqueId();
        if (!relaxed() && chain.held(u)) return false;
        if (chain.riddle == Riddles.Kind.STRANGER || chain.snapshot.contains(u)) return true;
        return Riddles.answers(chain.riddle, facts(giver)).contains(u);
    }

    // ------------------------------------------------------------------------------------------
    // the second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        daily(now);
        watchAuction();
        String pend = laying ? null : kvGet("pending");
        if (pend != null) {
            Player p = Bukkit.getPlayer(UUID.fromString(pend));
            if (p != null) {
                kvRemove("pending");
                solved(p);
            }
        }
        holding = null;
        if (!laying) {
            scan(now);
            if (chain != null) clock(now);
            if (chain != null) haunt(now);
            if (chain != null) last(now);
        }
        companion.tick();
    }

    /** One place a doll of this generation was found in someone's own inventory. slot -1 = cursor, -2 - i = crafting grid i. */
    private record Seen(Player p, int slot) {
        ItemStack item() {
            if (slot == -1) return p.getItemOnCursor();
            if (slot <= -2) return p.getOpenInventory().getTopInventory().getItem(-2 - slot);
            return p.getInventory().getItem(slot);
        }

        void clear() {
            if (slot == -1) p.setItemOnCursor(null);
            else if (slot <= -2) p.getOpenInventory().getTopInventory().setItem(-2 - slot, null);
            else p.getInventory().setItem(slot, null);
        }
    }

    /** Finds the doll: who has it now (a new hand?), dead copies crumble, extra copies go, and a doll left out comes back. */
    private void scan(long now) {
        int gen = gen();
        List<Seen> own = new ArrayList<>();
        boolean inTrade = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (!isDoll(it)) continue;
                if (genOf(it) != gen) {
                    inv.setItem(i, null);
                    crumble(p);
                } else {
                    own.add(new Seen(p, i));
                }
            }
            if (isDoll(p.getItemOnCursor())) {
                if (genOf(p.getItemOnCursor()) != gen) {
                    p.setItemOnCursor(null);
                    crumble(p);
                } else {
                    own.add(new Seen(p, -1));
                }
            }
            Inventory top = p.getOpenInventory().getTopInventory();
            boolean grid = top.getType() == InventoryType.CRAFTING;
            for (int i = 0; i < top.getSize(); i++) {
                ItemStack it = top.getItem(i);
                if (!isDoll(it)) continue;
                if (genOf(it) != gen) {
                    top.setItem(i, null);
                    crumble(p);
                } else if (grid) {
                    own.add(new Seen(p, -2 - i));
                } else if (TradeModule.window(top)) {
                    inTrade = true;
                }
            }
        }
        if (chain == null) {
            // no doll in play: whatever is left (an admin took the listing down, …) is not it
            for (Seen s : own) s.clear();
            return;
        }
        Seen keep = null;
        for (Seen s : own) if (s.p().getUniqueId().equals(chain.holder)) {
            keep = s;
            break;
        }
        if (keep == null && !own.isEmpty()) keep = own.getFirst();
        for (Seen s : own) if (s != keep) s.clear();   // a copied doll is not the doll
        if (keep != null) {
            absentSince = 0;
            Player y = keep.p();
            if (!y.getUniqueId().equals(chain.holder)) handoff(y);
            else if (!told()) tell(y);
            if (chain == null) return;
            ItemStack it = keep.item();
            refresh(it);
            if (keep.slot() >= 0) y.getInventory().setItem(keep.slot(), it);
            if (y.getUniqueId().equals(chain.holder)) holding = y;
            return;
        }
        Player hp = Bukkit.getPlayer(chain.holder);
        if (inTrade || hp == null || !told()) {
            absentSince = 0;
            return;
        }
        // in a chest, on the 거래소, in a bin … : back to the holder's pocket after a while
        if (absentSince == 0) absentSince = now;
        else if (now - absentSince >= Math.max(5, cfg.getLong("chain.container-seconds", 60)) * 1000L) {
            absentSince = 0;
            newGen();
            give(hp.getUniqueId());
            told(hasDoll(hp));   // in the mailbox (no room): wait until they take it out
            say(hp, "talk.pocket");
            m.haunt().behind(hp, Sound.BLOCK_WOOL_STEP, 0.5f, 1.6f);
        }
    }

    private boolean hasDoll(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isDoll(it) && genOf(it) == gen()) return true;
        return false;
    }

    private void crumble(Player p) {
        long now = System.currentTimeMillis();
        Long last = crumbled.get(p.getUniqueId());
        if (last != null && now - last < 10_000L) return;
        crumbled.put(p.getUniqueId(), now);
        whisper(p, line("talk.dead"));
        p.playSound(p.getLocation(), Sound.BLOCK_SAND_BREAK, SoundCategory.AMBIENT, 0.4f, 1.4f);
    }

    /** It is now in another player's inventory. */
    private void handoff(Player y) {
        Player from = Bukkit.getPlayer(chain.holder);
        boolean ok = matches(y, from);
        after(chain.pass(y.getUniqueId(), Names.plain(y), ok), from, y);
    }

    private void after(Chain.Result r, Player from, Player y) {
        save();
        absentSince = 0;
        UUID owner = chain.owner;
        switch (r) {
            case NEXT -> {
                deadline(System.currentTimeMillis() + chainMillis());
                told(true);
                m.stage(this, owner, chain.index());
                if (from != null && from != y) whisper(from, line("talk.bye"));
                say(y, "talk.taken");
                riddle(y);
                steps(y);
            }
            case WRONG -> {
                if (deadline() <= 0) deadline(System.currentTimeMillis() + chainMillis());
                told(true);
                giggle(y);
                if (from != null && from != y) say(from, "talk.giggle-giver");
                riddle(y);
            }
            case HOME -> {
                deadline(0);
                told(true);
                m.stage(this, owner, Chain.HANDS);
                home(y);
            }
            case NONE -> {}
        }
    }

    /** Thirty minutes with one hand: it goes home to the first owner on its own, and the run starts again. */
    private void clock(long now) {
        long d = deadline();
        if (d <= 0 || chain.phase != Chain.Phase.CHAIN) return;
        long left = d - now;
        Player hp = Bukkit.getPlayer(chain.holder);
        if (left <= 0) {
            goHome();
            return;
        }
        if (hp == null) return;
        if (left <= 10 * 60_000L && warned != d) {
            warned = d;
            say(hp, "talk.warn");
        }
        if (left <= 3 * 60_000L && warnedLast != d) {
            warnedLast = d;
            say(hp, "talk.warn-last");
            steps(hp);
        }
    }

    private void goHome() {
        UUID old = chain.holder;
        UUID owner = chain.owner;
        chain.restart(old.equals(owner) ? null : Names.plain(old));
        save();
        newGen();
        told(false);
        deadline(0);
        kvRemove("tries");
        kvSet("back", 1);
        asking.remove(owner);
        Player op = Bukkit.getPlayer(old);
        if (op != null) {
            strip(op);
            if (!old.equals(owner)) {
                say(op, "talk.went-home");
                steps(op);
            }
        }
        give(owner);
        m.stage(this, owner, 1);
    }

    // ------------------------------------------------------------------------------------------
    // dread
    // ------------------------------------------------------------------------------------------

    private void haunt(long now) {
        Player p = holding;
        if (p == null) return;
        double sec = 1 / 60.0;
        if (rnd.nextDouble() < cfg.getDouble("haunt.steps-per-minute", 0.25) * sec) steps(p);
        if (rnd.nextDouble() < cfg.getDouble("haunt.whisper-per-minute", 0.12) * sec) {
            List<String> w = cfg.getStringList("haunt.whispers");
            if (!w.isEmpty()) whisper(p, w.get(rnd.nextInt(w.size())));
        }
        if (night(p) && rnd.nextDouble() < cfg.getDouble("haunt.night-per-minute", 0.2) * sec) nightWhisper(p);
        if (isDoll(p.getInventory().getItemInMainHand()) && rnd.nextDouble() < cfg.getDouble("haunt.turn-per-minute", 0.3) * sec) turn(p);
    }

    private static boolean night(Player p) {
        long t = p.getWorld().getTime();
        return p.getWorld().getEnvironment() == World.Environment.NORMAL && t >= 13_000 && t <= 23_000;
    }

    /** At night it names someone nearby (or no one, which is worse). */
    private void nightWhisper(Player p) {
        List<Player> near = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= 32 * 32) near.add(o);
        List<String> l = cfg.getStringList(near.isEmpty() ? "haunt.night-alone" : "haunt.night");
        if (l.isEmpty()) return;
        String name = near.isEmpty() ? "" : esc(Names.plain(near.get(rnd.nextInt(near.size()))));
        p.sendMessage(Text.mm(l.get(rnd.nextInt(l.size())).replace("{name}", name)));
        m.haunt().behind(p, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.2f, 0.6f);
    }

    // ------------------------------------------------------------------------------------------
    // the end: where it was born
    // ------------------------------------------------------------------------------------------

    private int tries() {
        return (int) kvLong("tries", cfg.getInt("final.tries", 3));
    }

    /** The auction's place: set by admin (연출 자리), else final.place, else the first world's spawn. */
    Location place() {
        String s = kvGet("place");
        if (s == null || s.isBlank()) s = cfg.getString("final.place", "");
        if (s != null && !s.isBlank()) {
            String[] w = s.trim().split("\\s+");
            World world = w.length >= 4 ? Bukkit.getWorld(w[0]) : null;
            if (world != null) {
                try {
                    return new Location(world, Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]));
                } catch (NumberFormatException ignored) {
                    // fall back to the spawn
                }
            }
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private boolean atPlace(Player p) {
        Location at = place();
        if (!at.getWorld().equals(p.getWorld())) return false;
        double r = cfg.getDouble("final.radius", 8);
        Location l = p.getLocation();
        double dx = l.getX() - at.getX(), dz = l.getZ() - at.getZ();
        return dx * dx + dz * dz <= r * r && Math.abs(l.getY() - at.getY()) <= 8;
    }

    /** Back with the first owner: at the place it asks; the answer is typed in chat within the minutes. */
    private void last(long now) {
        if (chain.phase != Chain.Phase.HOME) {
            asking.clear();
            return;
        }
        Player op = holding;
        if (op == null || !op.getUniqueId().equals(chain.owner)) return;
        Long until = asking.get(chain.owner);
        if (until == null) {
            if (atPlace(op)) ask(op);
            return;
        }
        if (now > until) {
            asking.remove(chain.owner);
            miss(op, true);
        }
    }

    private void ask(Player p) {
        lines(p, "talk.ask");
        asking.put(p.getUniqueId(), System.currentTimeMillis() + Math.max(1, cfg.getLong("final.minutes", 5)) * 60_000L);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, SoundCategory.AMBIENT, 0.6f, 0.5f);
    }

    /** A wrong answer (or none in time): a try gone; the last one lost starts the run again. */
    private void miss(Player p, boolean late) {
        int left = tries() - 1;
        if (left > 0) {
            kvSet("tries", left);
            say(p, late ? "talk.late" : "talk.wrong");
            p.playSound(p.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.AMBIENT, 0.3f, 0.5f);
            if (!late) asking.put(p.getUniqueId(), System.currentTimeMillis() + Math.max(1, cfg.getLong("final.minutes", 5)) * 60_000L);
            return;
        }
        asking.remove(p.getUniqueId());
        kvRemove("tries");
        lines(p, "talk.forgot");
        kvSet("run", kvLong("run", 0) + 1);
        chain.restart(null);
        save();
        told(false);
        deadline(0);
        m.stage(this, chain.owner, 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAnswer(AsyncChatEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        if (!asking.containsKey(u)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(eco, () -> judge(u, text));
    }

    private void judge(UUID u, String text) {
        Player p = Bukkit.getPlayer(u);
        if (p == null || chain == null || chain.phase != Chain.Phase.HOME || !asking.containsKey(u) || laying) return;
        p.sendMessage(Text.mm("<dark_gray><i>(작게) " + esc(text)));
        if (!hasDoll(p)) return;
        if (!atPlace(p)) {
            say(p, "talk.not-here");
            return;
        }
        if (Chain.check(text, chain.answer())) finish(p);
        else miss(p, false);
    }

    /** The right answer: it is laid down, turns its head once, thanks them and sleeps. Solved. */
    private void finish(Player p) {
        asking.remove(p.getUniqueId());
        strip(p);
        chain = null;
        save();
        newGen();
        told(false);
        deadline(0);
        kvRemove("tries");
        // saved first: if they leave (or the server stops) during the scene, it is solved when they are next online
        kvSet("pending", p.getUniqueId().toString());
        laying = true;
        layDown(p, () -> {
            laying = false;
            if (p.isOnline() && p.getUniqueId().toString().equals(kvGet("pending"))) {
                kvRemove("pending");
                solved(p);
            }
        });
    }

    private void solved(Player p) {
        m.solve(this, p);
        kvSet("companion", p.getUniqueId().toString());
        kvRemove("companion-off");
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) lines(p, "companion.given");
        }, 80L);
    }

    /** The doll on the ground before them, lying; after a moment its head turns once. Only they see it. */
    private void layDown(Player p, Runnable after) {
        Location base = p.getLocation();
        Vector dir = base.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        Location at = base.clone().add(dir.normalize().multiply(1.4));
        at.setY(base.getY() + 0.04);
        at.setYaw(base.getYaw() + 180);
        at.setPitch(0);
        ItemStack it = Items.create(ITEM, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        float lie = (float) -Math.PI / 2;
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.setItemStack(it);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX(lie), new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
        });
        p.showEntity(eco, d);
        props.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(d);
        say(p, "talk.lay");
        p.playSound(at, Sound.BLOCK_WOOL_PLACE, SoundCategory.AMBIENT, 0.6f, 1.2f);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(14);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.toRadians(75)).rotateX(lie),
                    new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
            if (p.isOnline()) p.playSound(at, Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, SoundCategory.AMBIENT, 0.8f, 0.45f);
        }, 50L);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) {
                say(p, "talk.thanks");
                chime(p, 0.6f);
            }
        }, 90L);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            List<Entity> l = props.get(p.getUniqueId());
            if (l != null) l.remove(d);
            if (d.isValid()) d.remove();
            if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_WOOL_STEP, SoundCategory.AMBIENT, 0.2f, 1.9f);
            if (after != null) after.run();
        }, 150L);
    }

    // ------------------------------------------------------------------------------------------
    // the listing
    // ------------------------------------------------------------------------------------------

    /** Once a Korean day, server-wide: chance it comes; then at a random time in the hours, once enough are online. */
    private void daily(long now) {
        String today = LocalDate.now(SecretModule.KST).toString();
        if (!today.equals(kvGet("day"))) {
            kvSet("day", today);
            ownerGone(now);
            long at = -1;
            if (!m.closed(this) && chain == null && rnd.nextDouble() < cfg.getDouble("start.chance", 0.02)) at = pickTime(now);
            kvSet("at", at);
        }
        long at = kvLong("at", -1);
        if (at <= 0 || now < at || m.closed(this) || chain != null || kvGet("auction") != null) return;
        if (Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("start.min-players", 3))) return;
        kvSet("at", -1);
        list();
    }

    private long pickTime(long now) {
        List<Integer> h = cfg.getIntegerList("start.hours");
        int from = h.size() > 0 ? h.get(0) : 12, to = h.size() > 1 ? h.get(1) : 24;
        ZonedDateTime day = ZonedDateTime.now(SecretModule.KST).toLocalDate().atStartOfDay(SecretModule.KST);
        long start = day.plusHours(from).toInstant().toEpochMilli(), end = day.plusHours(to).toInstant().toEpochMilli();
        long lo = Math.max(start, now);
        if (lo >= end) return -1;
        return lo + (long) (rnd.nextDouble() * (end - lo));
    }

    /** A first owner gone too long: the doll leaves them (and may be listed again one day). */
    private void ownerGone(long now) {
        if (chain == null) return;
        OfflinePlayer o = Bukkit.getOfflinePlayer(chain.owner);
        if (o.isOnline()) return;
        long days = cfg.getLong("start.owner-gone-days", 14);
        long seen = o.getLastSeen();
        if (days <= 0 || seen <= 0 || now - seen < days * 86_400_000L) return;
        eco.getLogger().info("[비밀] 낡은 인형: 처음 주인 " + Names.plain(chain.owner) + " 이(가) " + days + "일 넘게 오지 않아 인형이 떠남");
        m.stage(this, chain.owner, 0);
        chain = null;
        save();
        newGen();
        told(false);
        deadline(0);
    }

    /** Puts the doll up in the auction house now. */
    private Auction list() {
        AuctionModule au = eco.auction();
        if (au == null) return null;
        if (chain != null) m.stage(this, chain.owner, 0);
        chain = null;
        save();
        told(false);
        deadline(0);
        int g = newGen();
        Auction a = au.startGhost(make(g, true), Math.max(1, cfg.getLong("auction.start-price", 1)), Math.max(1, cfg.getDouble("auction.minutes", 10)));
        kvSet("auction", a.id);
        eco.getLogger().info("[비밀] 낡은 인형: 경매 #" + a.id + " 에 올라옴");
        return a;
    }

    private void auctionEnded(Auction a) {
        kvRemove("auction");
        if (a.bidder == null) {
            newGen();
            String s = line("auction.unsold");
            if (!s.isEmpty()) Bukkit.getScheduler().runTaskLater(eco, () -> Bukkit.broadcast(Text.mm(s)), 50L);
            return;
        }
        UUID u = a.bidder;
        chain = new Chain(u, Names.plain(u));
        save();
        told(false);
        deadline(0);
        kvRemove("tries");
        kvSet("intro", 1);
        m.stage(this, u, 1);
        eco.getLogger().info("[비밀] 낡은 인형: 처음 주인 " + Names.plain(u));
    }

    /** The listing went away without ending (an admin took it down): that doll is no more. */
    private void watchAuction() {
        long id = kvLong("auction", -1);
        if (id < 0 || eco.auction() == null || eco.auction().store().get(id) != null) return;
        kvRemove("auction");
        newGen();
    }

    // ------------------------------------------------------------------------------------------
    // keeping hold of it
    // ------------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!isDoll(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        whisper(e.getPlayer(), line("talk.no-drop"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        for (Iterator<ItemStack> i = e.getDrops().iterator(); i.hasNext(); ) {
            ItemStack it = i.next();
            if (!isDoll(it)) continue;
            i.remove();
            if (genOf(it) == gen()) e.getItemsToKeep().add(it);
        }
    }

    /** Anything that would leave it lying on the ground (a broken chest, …): back to the holder. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (!isDoll(it)) return;
        e.setCancelled(true);
        if (chain == null || genOf(it) != gen()) return;
        UUID to = chain.holder;
        ItemStack back = it.clone();
        Bukkit.getScheduler().runTask(eco, () -> {
            Player p = Bukkit.getPlayer(to);
            if (p != null && p.getInventory().firstEmpty() >= 0) p.getInventory().addItem(back);
            else eco.mail().deliver(to, back, MAIL);
        });
    }

    /** Not into a bundle (it would be out of sight there). */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem(), cursor = e.getCursor();
        if (isDoll(cursor) && bundle(cur) || bundle(cursor) && isDoll(cur)) e.setCancelled(true);
    }

    private static boolean bundle(ItemStack it) {
        return it != null && it.getType().name().endsWith("BUNDLE");
    }

    /** Not into a frame, onto a stand, into an allay's hands … */
    @EventHandler(ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Player) return;
        ItemStack it = e.getPlayer().getInventory().getItem(e.getHand());
        if (isDoll(it)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (isDoll(e.getPlayerItem())) e.setCancelled(true);
    }

    /** Right-click with it: it says its riddle again. Sneaking, looking straight up, two empty-handed swings: the companion sleeps or wakes. */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() == Action.LEFT_CLICK_AIR && p.isSneaking() && p.getLocation().getPitch() < -70 && p.getInventory().getItemInMainHand().isEmpty()
                && p.getUniqueId().equals(companion.owner())) {
            long now = System.currentTimeMillis();
            Long prev = swings.put(p.getUniqueId(), now);
            if (prev != null && now - prev < 1200) {
                swings.remove(p.getUniqueId());
                boolean awake = companion.toggle();
                whisper(p, line(awake ? "companion.wake" : "companion.sleep"));
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, SoundCategory.AMBIENT, 0.5f, awake ? 0.8f : 0.5f);
            }
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || !(e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) return;
        if (chain == null || laying || !on() || !isDoll(e.getItem()) || !p.getUniqueId().equals(chain.holder) || genOf(e.getItem()) != gen()) return;
        long now = System.currentTimeMillis();
        Long at = repeatAt.get(p.getUniqueId());
        if (at != null && now - at < 4000) return;
        repeatAt.put(p.getUniqueId(), now);
        if (!told()) return;
        if (chain.phase == Chain.Phase.HOME) home(p);
        else riddle(p);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /** Admin 시작: the listing goes up now (whatever the chance, hour or players online). A doll already out is gone. */
    @Override
    public void start(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) strip(o);
        if (kvGet("auction") != null && eco.auction() != null && eco.auction().store().get(kvLong("auction", -1)) != null) {
            p.sendMessage(Text.mm("<yellow>[낡은인형] 경매 #" + kvGet("auction") + " 이(가) 이미 진행 중 (/관리자 경매 종료 " + kvGet("auction") + " 로 바로 끝내기)"));
            return;
        }
        Auction a = list();
        if (a != null && (m.test(p.getUniqueId()) || p.hasPermission("stsv.economy.admin")))
            p.sendMessage(Text.mm("<yellow>[낡은인형] 경매 #" + a.id + " 에 올렸어요. 1 G 입찰 → (" + Math.round(cfg.getDouble("auction.minutes", 10))
                    + "분 또는 /관리자 경매 종료 " + a.id + ") → 낙찰자가 처음 주인"));
    }

    /** Admin 단계: p becomes the first owner with n hands so far (made-up earlier hands from recent players), doll in hand. */
    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        for (Player o : Bukkit.getOnlinePlayers()) strip(o);
        asking.clear();
        kvRemove("tries");
        kvRemove("back");
        kvRemove("intro");
        if (stage <= 0) {
            if (chain != null) m.stage(this, chain.owner, 0);
            chain = null;
            save();
            newGen();
            told(false);
            deadline(0);
            m.stage(this, u, 0);
            return;
        }
        int n = Math.min(Chain.HANDS, stage);
        chain = new Chain(u, Names.plain(p));
        List<OfflinePlayer> recent = new ArrayList<>(Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(o -> !o.getUniqueId().equals(u) && o.getName() != null).sorted(Comparator.comparingLong(OfflinePlayer::getLastSeen).reversed())
                .limit(Chain.HANDS).toList());
        String[] madeUp = {"누리", "하람", "Alex", "도담", "소이", "Steve"};
        List<Riddles.Kind> pool = new ArrayList<>(Riddles.POOL);
        java.util.Collections.shuffle(pool, rnd);
        for (int i = 1; i < n; i++) {
            UUID id;
            String name;
            if (i - 1 < recent.size()) {
                id = recent.get(i - 1).getUniqueId();
                name = Names.plain(id);
            } else {
                name = madeUp[(i - 1) % madeUp.length];
                id = UUID.nameUUIDFromBytes(("doll-made-up-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            chain.trail.add(new Chain.Hand(id, name, true));
            chain.used.add(pool.get(i - 1));
        }
        if (n >= Chain.HANDS) chain.phase = Chain.Phase.HOME;
        save();
        newGen();
        told(false);
        deadline(0);
        give(u);
        m.stage(this, u, n);
        p.sendMessage(Text.mm("<yellow>[낡은인형] 손 " + n + "/7 · 정답(지금까지) <white>" + esc(chain.answer())));
    }

    @Override
    public void cleanup(UUID player) {
        asking.remove(player);
        companion.cleanup(player);
        List<Entity> l = props.remove(player);
        if (l != null) for (Entity e : l) if (e.isValid()) e.remove();
    }

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        String au = kvGet("auction");
        if (au != null) out.add("<white>경매 #" + au + "</white> 진행 중");
        if (u.equals(companion.owner())) out.add("동반자 " + (companion.awake() ? "<green>깨어 있음" : "<gray>잠듦") + "</gray>");
        if (chain == null) {
            long at = kvLong("at", -1);
            if (out.isEmpty() && at > 0) out.add("오늘 경매 예정 " + java.time.Instant.ofEpochMilli(at).atZone(SecretModule.KST).toLocalTime().withNano(0));
            return String.join(" · ", out);
        }
        boolean owner = u.equals(chain.owner), holder = u.equals(chain.holder);
        if (!owner && !holder) {
            if (chain.held(u)) out.add("인형을 안아 줬음 (" + esc(Names.plain(chain.owner)) + " 의 인형)");
            return String.join(" · ", out);
        }
        out.add("처음 주인 <white>" + esc(Names.plain(chain.owner)) + "</white>");
        out.add("손 <white>" + chain.index() + "/" + Chain.HANDS + "</white>");
        Player hp = Bukkit.getPlayer(chain.holder);
        out.add("지금 <white>" + esc(Names.plain(chain.holder)) + "</white>" + (hp == null ? " (접속 안 함)" : "") + (told() ? "" : " (아직 못 받음 / 우편함)"));
        if (chain.phase == Chain.Phase.HOME) {
            Location at = place();
            out.add("돌아옴 → 경매장 " + at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                    + (asking.containsKey(chain.owner) ? " <yellow>묻는 중</yellow>" : "") + " · 기회 " + tries());
        } else if (chain.riddle == null) {
            out.add("수수께끼 아직 안 함");
        } else {
            String text = Text.plain(riddleText(chain.riddle));
            List<String> then = new ArrayList<>(), now = new ArrayList<>();
            for (UUID x : chain.snapshot) then.add(esc(Names.plain(x)));
            if (chain.riddle != Riddles.Kind.HOME) for (UUID x : Riddles.answers(chain.riddle, facts(hp))) now.add(esc(Names.plain(x)));
            out.add("수수께끼 " + chain.index() + " <white>" + chain.riddle.id() + "</white> \"" + esc(text) + "\"");
            out.add("맞는 사람: 말할 때 [" + String.join(", ", then) + "] 지금 [" + String.join(", ", now) + "]");
        }
        long d = deadline();
        if (d > 0) {
            long s = Math.max(0, (d - System.currentTimeMillis()) / 1000);
            out.add("귀가까지 " + s / 60 + ":" + String.format("%02d", s % 60));
        }
        if (!chain.lost.isEmpty()) out.add("놓친 손 " + chain.lost.size());
        out.add("<yellow>첫 글자 " + esc(chain.answer()) + "</yellow>");
        if (relaxed()) out.add("<yellow>테스트 (같은 사람 여러 번 됨)</yellow>");
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("경매", "속삭임", "다음사람", "웃음", "귀가", "마지막질문", "잠듦", "동반자", "건네기", "자리", "치우기");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        switch (word) {
            case "경매" -> {
                long price = Math.max(1, cfg.getLong("auction.start-price", 1));
                p.sendMessage(Lang.msg("auction.started", "id", "?", "item", cfg.getString("auction.name", "낡은 인형"), "count", 1,
                        "seller", cfg.getString("auction.seller", "—"), "price", EconomyModule.format(price), "minutes",
                        Math.round(cfg.getDouble("auction.minutes", 10))));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
                for (String l : cfg.getStringList("auction.lore")) p.sendMessage(Text.mm("<dark_gray>  │ </dark_gray>" + Chain.backwards(l)));
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) p.sendMessage(Text.mm(line("auction.unsold")));
                }, 80L);
                admin.sendMessage(Text.mm("<gray>(목록 설명은 거꾸로. 진짜 경매는 /관리자 비밀 시작 낡은인형)"));
            }
            case "속삭임" -> {
                steps(p);
                List<String> w = cfg.getStringList("haunt.whispers");
                if (!w.isEmpty()) whisper(p, w.get(rnd.nextInt(w.size())));
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) nightWhisper(p);
                }, 60L);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) turn(p);
                }, 140L);
            }
            case "다음사람" -> {
                if (chain != null && chain.riddle != null && !chain.last()) {
                    say(p, "talk.whisper");
                    p.sendMessage(Text.mm(line("talk.riddle").replace("{riddle}", riddleText(chain.riddle)).replace("{n}", String.valueOf(chain.index()))));
                    admin.sendMessage(Text.mm("<gray>지금 맞는 사람: " + names(Riddles.answers(chain.riddle, facts(Bukkit.getPlayer(chain.holder))))
                            + " · 말할 때: " + names(chain.snapshot)));
                } else {
                    Chain keep = chain;
                    chain = null;
                    List<Riddles.Fact> facts = facts(p);
                    chain = keep;
                    Riddles.Kind k = Riddles.choose(rnd.nextLong(), java.util.EnumSet.noneOf(Riddles.Kind.class), facts);
                    say(p, "talk.whisper");
                    p.sendMessage(Text.mm(line("talk.riddle").replace("{riddle}", riddleText(k)).replace("{n}", "1")));
                    admin.sendMessage(Text.mm("<gray>(예시) " + k.id() + " → " + names(Riddles.answers(k, facts))));
                }
                chime(p, 0.5f);
            }
            case "웃음" -> giggle(p);
            case "귀가" -> {
                say(p, "talk.went-home");
                steps(p);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) lines(p, "talk.came-home");
                }, 60L);
            }
            case "마지막질문" -> {
                home(p);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) lines(p, "talk.ask");
                }, 80L);
                if (chain != null) admin.sendMessage(Text.mm("<gray>정답: <yellow>" + esc(chain.answer())));
            }
            case "잠듦" -> layDown(p, null);
            case "동반자" -> {
                companion.preview(p, 30);
                admin.sendMessage(Text.mm("<gray>30초 동안 " + esc(p.getName()) + " 의 어깨에 (주인: "
                        + (companion.owner() == null ? "없음" : esc(Names.plain(companion.owner()))) + ")"));
            }
            case "건네기" -> {
                if (chain == null) {
                    admin.sendMessage(Text.mm("<red>지금 도는 인형이 없어요 (단계 <플레이어> 1 또는 시작)"));
                    return true;
                }
                Player from = Bukkit.getPlayer(chain.holder);
                if (from != null && from != p) {
                    strip(from);
                    give(p.getUniqueId());
                } else if (from == null) {
                    give(p.getUniqueId());
                }
                after(chain.force(p.getUniqueId(), Names.plain(p)), from, p);
                admin.sendMessage(Text.mm("<gray>" + esc(p.getName()) + " 에게 건넴 · 손 " + chain.index() + "/7 · 첫 글자 <yellow>" + esc(chain.answer())));
            }
            case "자리" -> {
                Location l = p.getLocation();
                kvSet("place", l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                admin.sendMessage(Text.mm("<green>경매장 자리 = " + kvGet("place") + " <gray>(doll.yml final.place-hint 문구도 맞게 바꿀 것)"));
            }
            case "치우기" -> {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    strip(o);
                    cleanup(o.getUniqueId());
                }
                if (chain != null) m.stage(this, chain.owner, 0);
                chain = null;
                save();
                newGen();
                for (String k : List.of("told", "deadline", "tries", "back", "intro", "pending", "at", "companion", "companion-off")) kvRemove(k);
                companion.remove();
                String live = kvGet("auction");
                admin.sendMessage(Text.mm("<green>낡은 인형을 모두 치웠어요 (돌던 인형 · 동반자)" + (live == null ? ""
                        : " <yellow>경매 #" + live + " 은(는) 아직 진행 중: /관리자 경매 취소 " + live)));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String names(Set<UUID> ids) {
        List<String> out = new ArrayList<>();
        for (UUID u : new LinkedHashSet<>(ids)) out.add(esc(Names.plain(u)));
        return out.isEmpty() ? "(없음)" : String.join(", ", out);
    }
}
