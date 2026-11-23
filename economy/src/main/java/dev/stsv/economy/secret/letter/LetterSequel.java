package dev.stsv.economy.secret.letter;

import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Memory;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState.State;
import dev.stsv.economy.secret.letter.Yesterday.Kind;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 어제의 편지: the 후속 임무 of 내일의 나 (docs/admin/숨은_비밀/내일의_나.md "후속 임무"). The thing that could not take the
 * solver's place walks one second behind them now, and once a week it writes: a letter in the mailbox with one riddle made
 * of the solver's own records ({@link Memory}: where they last left, where they first came in, their first land, what they
 * sold, whom they stay near). Read it (offered → active), do it, and an answer comes — a little warmer every few weeks: it
 * is learning to be a person by watching one.
 * <p>
 * Notes of the week: {@code kind}, {@code t1}/{@code t2} (places, {@link Target}), {@code item}, {@code friend},
 * {@code pages} (the letter as written, so it can be sent again), {@code read} (when it was read: sales count from then).
 * Kept: {@code recent} (the last riddles, so the next differs). Step 1 = read; step 2 = the walk's second place.
 * The letters are books that do nothing by themselves (a mark of their week): nothing is taken from the player and nothing
 * needs to be taken back.
 */
public final class LetterSequel extends Sequel {

    /** On a letter of this mission: "&lt;week&gt;:q" (the riddle) or ":r" (the answer). */
    static final NamespacedKey SEQ = new NamespacedKey("economy", "letter_seq");
    private static final String PAGE_BREAK = "\n@@page@@\n";

    private static final Map<Kind, String> RIDDLES = Map.of(
            Kind.QUIT, "{when},\n네가 눈을 감은 자리.\n\n{where}\n\n거기 다시 서 봐.\n나는 그때 거기 남아 있었어.",
            Kind.BIRTH, "네가 처음 눈을 뜬 자리.\n{joined}\n\n{where}\n\n나는 그 자리를 끝내 못 찾았지.\n네가 서서 보여 줘.",
            Kind.LAND, "네가 처음으로\n'내 것'이라고 한 땅.\n\n{where}\n\n거기 잠깐 서 있어 줘.\n내 것이 있다는 게 뭔지 보고 싶어.",
            Kind.FRIEND, "네가 가장 오래\n곁에 있던 사람.\n\n{friendhint}\n\n그 사람 옆에 서 봐.\n말은 안 걸어도 돼.",
            Kind.CARRY_MOST_LAND, "네가 가장 많이 떠나보낸 것.\n{itemhint}\n\n그걸 하나 손에 들고,\n네 첫 땅으로 가.\n{where}\n\n팔지는 마. 들고만 있어.",
            Kind.CARRY_WEEK_BIRTH, "이번 주에 네 손을\n가장 많이 떠난 것.\n{itemhint}\n\n그걸 들고, 네가 처음\n눈을 뜬 자리로.\n{where}",
            Kind.CARRY_LAST_QUIT, "네가 마지막으로 판 것.\n{itemhint}\n\n하나만 더 구해서,\n네가 지난번 눈을 감은 자리로.\n{where}",
            Kind.SELL_MOST, "네가 늘 떠나보내던 것.\n{itemhint}\n\n한 번만 더 팔아 줘.\n이번엔 내가 옆에서 볼게.",
            Kind.SELL_OLDEST, "장부가 기억하는\n가장 오래된 것.\n{itemhint}\n\n너는 잊었겠지만 나는 기억해.\n그걸 한 번 더 팔아 줘.",
            Kind.WALK, "네가 눈을 감은 자리에서\n{where}\n\n네가 눈을 뜬 자리까지.\n{wheretwo}\n\n그 순서로 걸어 줘.\n나는 그 길을 거꾸로만 걸어 봤어.");

    private final Letters s;
    private final Set<UUID> composing = new HashSet<>();
    private final Map<UUID, Integer> stay = new HashMap<>();
    private final Map<UUID, Long> nextPoll = new HashMap<>();
    private final Map<UUID, Long> nextWhisper = new HashMap<>();
    private final Map<UUID, Long> wrongSale = new HashMap<>();
    private final Map<UUID, List<Entity>> props = new HashMap<>();

    LetterSequel(Letters s) {
        super(s);
        this.s = s;
    }

    @Override
    public String id() {
        return "어제의 편지";
    }

    // ------------------------------------------------------------------------------------------
    // the letter comes
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        UUID u = p.getUniqueId();
        long online = (System.currentTimeMillis() - m.memory().sessionStart(u)) / 1000L;
        if (online < seconds(u, "after-online", 300)) return false;
        start(p);
        return true;
    }

    @Override
    protected void start(Player p) {
        compose(p);
    }

    /** Reads their records (the ledger and the land, off the main thread) and then writes this week's letter. */
    private void compose(Player p) {
        UUID u = p.getUniqueId();
        if (!composing.add(u)) return;
        m.memory().sales(u, sales -> m.memory().firstLand(u, land -> {
            composing.remove(u);
            if (!p.isOnline() || !live(u) || get(u, "kind") != null) return;
            write(p, sales, land.orElse(null));
        }));
    }

    private static Memory.Place known(Optional<Memory.Place> at) {
        return at.filter(x -> Bukkit.getWorld(x.world()) != null).orElse(null);
    }

    private void write(Player p, List<Memory.Sale> sales, Memory.Place land) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Memory.Place quit = known(m.memory().lastQuit(u));
        Memory.Place first = known(m.memory().firstPlace(u));
        if (first == null) {
            Location sp = Bukkit.getWorlds().getFirst().getSpawnLocation();
            first = new Memory.Place(sp.getWorld().getName(), sp.getBlockX(), sp.getBlockY(), sp.getBlockZ());
        }
        if (land != null && Bukkit.getWorld(land.world()) == null) land = null;
        Yesterday.Sold sold = Yesterday.sold(sales, now - 7L * 86_400_000L);
        String friend = m.memory().friends(u).stream().filter(f -> !f.equalsIgnoreCase(p.getName())).findFirst().orElse(null);
        Yesterday.Facts facts = new Yesterday.Facts(quit != null, true, land != null,
                Yesterday.apart(quit, first, cfg().getInt("walk-apart", 48)), friend != null, sold.most(), sold.weekMost(), sold.last(), sold.oldest());

        List<Kind> allowed = new ArrayList<>();
        List<String> names = cfg().getStringList("kinds");
        if (names.isEmpty()) allowed.addAll(List.of(Kind.values()));
        else for (String n : names) Kind.of(n).ifPresent(allowed::add);
        List<Kind> can = Yesterday.possible(facts, allowed);
        if (can.isEmpty()) can = List.of(Kind.BIRTH);
        List<Kind> recent = new ArrayList<>();
        for (String w : Yesterday.words(kept(u, "recent", ""))) Kind.of(w).ifPresent(recent::add);
        Kind k = Yesterday.pick(s.seed(u), week(u), can, recent);
        keep(u, "recent", String.join(",", Yesterday.remember(Yesterday.words(kept(u, "recent", "")), k.key, cfg().getInt("avoid-recent", 4))));

        int r = Math.max(1, cfg().getInt("radius", 5));
        Target quitAt = quit == null ? Target.none() : Target.near(quit.world(), quit.x(), quit.y(), quit.z(), r);
        Target firstAt = Target.near(first.world(), first.x(), first.y(), first.z(), r);
        Target landAt = land == null ? Target.none() : Target.chunk(land.world(), Math.floorDiv(land.x(), 16), Math.floorDiv(land.z(), 16));
        Target t1 = Target.none(), t2 = Target.none();
        String item = null;
        int count = 0;
        switch (k) {
            case QUIT -> t1 = quitAt;
            case BIRTH -> t1 = firstAt;
            case LAND -> t1 = landAt;
            case FRIEND -> { }
            case CARRY_MOST_LAND -> {
                t1 = landAt;
                item = sold.most();
                count = sold.mostCount();
            }
            case CARRY_WEEK_BIRTH -> {
                t1 = firstAt;
                item = sold.weekMost();
                count = sold.weekCount();
            }
            case CARRY_LAST_QUIT -> {
                t1 = quitAt;
                item = sold.last();
            }
            case SELL_MOST -> {
                item = sold.most();
                count = sold.mostCount();
            }
            case SELL_OLDEST -> item = sold.oldest();
            case WALK -> {
                t1 = quitAt;
                t2 = firstAt;
            }
        }

        Map<String, String> v = new HashMap<>();
        v.put("name", p.getName());
        v.put("date", date(LocalDate.now(dev.stsv.economy.secret.SecretModule.KST).minusDays(1)));
        long quitWhen = m.memory().lastQuitAt(u);
        v.put("when", quitWhen > 0 ? Riddle.dateTime(quitWhen) : line("letter.when-unknown", "지난번에"));
        long joined = Bukkit.getOfflinePlayer(u).getFirstPlayed();
        v.put("joined", joined > 0 ? Riddle.dateTime(joined) : null);
        v.put("where", where(t1));
        v.put("wheretwo", where(t2));
        v.put("itemhint", item == null ? null : itemHint(item, count));
        v.put("friendhint", friend == null ? null : LetterText.fill(line("letter.friend-hint", "이름의 첫소리는 <white>{initials}</white>, 글자는 {length}개."),
                Map.of("initials", initials(friend), "length", String.valueOf(friend.length()))));
        long done = count(u);
        List<String> greetings = cfg().getStringList("letter.greetings");
        int g = Yesterday.turn(done, greetings.size());
        v.put("greeting", g < 0 ? "어제도 네 뒤에 있었어." : greetings.get(g));
        List<String> variants = cfg().getStringList("riddles." + k.key);
        String riddle = variants.isEmpty() ? RIDDLES.get(k) : variants.get((int) Math.floorMod(Yesterday.hash(s.seed(u), week(u)) >>> 8, (long) variants.size()));
        v.put("riddle", LetterText.fill(riddle, v));
        List<String> templates = cfg().getStringList("letter.pages");
        if (templates.isEmpty()) templates = List.of("<dark_gray>{date}</dark_gray>\n\n{name}에게.\n\n{greeting}\n\n<dark_gray>(다음 장)", "{riddle}\n\n<gray>— 1초 뒤의 나</gray>");
        List<String> pages = new ArrayList<>();
        for (String t : templates) pages.add(LetterText.fill(t, v));

        set(u, "kind", k.key);
        set(u, "t1", t1.encode());
        set(u, "t2", t2.encode());
        if (item != null) set(u, "item", Items.normalize(item));
        if (k == Kind.FRIEND && friend != null) set(u, "friend", friend);
        set(u, "pages", String.join(PAGE_BREAK, pages));
        send(p, book(p, pages, week(u) + ":q", line("letter.title", "어제")), "letter.notice", "<gray>우편함에 편지가 한 통 와 있습니다. <white>/우편함");
    }

    private String date(LocalDate d) {
        try {
            return d.format(DateTimeFormatter.ofPattern(line("letter.date-format", "yyyy년 M월 d일")));
        } catch (IllegalArgumentException e) {
            return d.toString();
        }
    }

    /** "From that world's first place, which way and how far" (no coordinates); null when there is no place. */
    private String where(Target t) {
        if (t.kind() != Target.Kind.NEAR && t.kind() != Target.Kind.CHUNK) return null;
        World w = Bukkit.getWorld(t.world());
        if (w == null) return null;
        Location sp = w.getSpawnLocation();
        double dx = t.x() + 0.5 - sp.getX(), dz = t.z() + 0.5 - sp.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        Map<String, String> v = new HashMap<>();
        v.put("world", m.core().worlds() != null ? m.core().worlds().displayName(w) : w.getName());
        v.put("dir", Riddle.direction(dx, dz));
        v.put("steps", Riddle.steps(d));
        return d < 24 ? LetterText.fill(line("letter.where-near", "<white>{world}</white>의 첫 자리 바로 곁."), v)
                : LetterText.fill(line("letter.where-far", "<white>{world}</white>의 첫 자리에서\n<white>{dir}</white>으로 <white>{steps}</white>."), v);
    }

    /** What a letter says of a thing without naming it: how long its name is, how many went, the first sounds of a name of ours. */
    private String itemHint(String id, int count) {
        String norm = Items.normalize(id);
        Map<String, String> v = new HashMap<>();
        v.put("shape", "<obf>" + Items.mmName(norm) + "</obf>");
        v.put("count", count > 0 ? String.format("%,d", count) : null);
        String ini = null;
        if (!norm.startsWith("minecraft:")) {
            var custom = m.core().content().item(norm).orElse(null);
            if (custom != null) ini = Riddle.initials(Text.plain(custom.name()));
        }
        v.put("initials", ini == null || ini.isBlank() ? null : ini);
        return LetterText.fill(line("letter.item-hint", "이름은 <white>{shape}</white>.\n첫소리는 <white>{initials}</white>.\n지금까지 <white>{count}</white>개."), v);
    }

    private static String initials(String name) {
        String k = Riddle.initials(name);
        // a name in Latin letters: only its first letter shows
        return k.equals(name) && !name.isEmpty() ? name.charAt(0) + "…" : k;
    }

    private ItemStack book(Player p, List<String> pages, String mark, String title) {
        ItemStack it = Items.create(LetterBook.ITEM, 1).orElse(null);
        if (it == null) {
            eco.getLogger().warning("[비밀] 아이템 " + LetterBook.ITEM + " 이 없습니다");
            return null;
        }
        if (!(it.getItemMeta() instanceof BookMeta bm)) return it;
        List<Component> out = new ArrayList<>();
        for (String page : pages) out.add(Text.mm(page));
        bm.setAuthor(line("letter.author", "1초 뒤의 나"));
        bm.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
        bm.setGeneration(BookMeta.Generation.ORIGINAL);
        bm.pages(out);
        List<String> lore = new ArrayList<>();
        for (String l : cfg().isList("letter.lore") ? cfg().getStringList("letter.lore") : List.of("<gray>보낸 사람: <white>1초 뒤의 {name}"))
            lore.add(l.replace("{name}", p.getName()));
        bm.lore(Text.itemLines(lore));
        bm.getPersistentDataContainer().set(SEQ, PersistentDataType.STRING, mark);
        it.setItemMeta(bm);
        return it;
    }

    /** Into the mailbox (a letter is not handed over), with a line only they read. */
    private void send(Player p, ItemStack item, String noticeKey, String noticeDef) {
        if (item == null) return;
        if (eco.mail() != null) eco.mail().store().send(p.getUniqueId(), Items.bytes(item), "secret_letter", System.currentTimeMillis());
        else give(p, item);
        tell(p, noticeKey, noticeDef);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 0.9f);
    }

    // ------------------------------------------------------------------------------------------
    // doing it
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRead(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String mark = it.getItemMeta().getPersistentDataContainer().get(SEQ, PersistentDataType.STRING);
        if (mark == null) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (!may(p) || state(u) != State.OFFERED || !mark.equals(week(u) + ":q") || get(u, "kind") == null) return;
        if (!accept(p)) return;
        step(u, 1);
        set(u, "read", System.currentTimeMillis());
    }

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        String kind = get(u, "kind");
        if (kind == null) {
            if (!composing.contains(u)) compose(p);   // the server stopped while the letter was being written
            return;
        }
        if (state(u) != State.ACTIVE) return;
        Kind k = Kind.of(kind).orElse(null);
        if (k == null) return;
        if (k.sells()) {
            sales(p);
            return;
        }
        boolean there;
        Target t = Target.none();
        if (k == Kind.FRIEND) {
            Player f = Bukkit.getPlayerExact(get(u, "friend", ""));
            double r = cfg().getDouble("friend-radius", 4);
            there = f != null && f != p && f.getWorld() == p.getWorld() && f.getLocation().distanceSquared(p.getLocation()) <= r * r;
        } else {
            t = Target.decode(get(u, step(u) >= 2 ? "t2" : "t1"));
            Location l = p.getLocation();
            there = t.contains(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
        if (!there) {
            stay.remove(u);
            if (t.kind() != Target.Kind.NONE) near(p, t);
            return;
        }
        String item = get(u, "item");
        if (item != null && !holds(p, item)) {
            stay.remove(u);
            whisper(p, "whispers.empty-handed", "<dark_gray>…빈손이네.");
            return;
        }
        if (stay.merge(u, 1, Integer::sum) < Math.max(1, cfg().getInt("stay-seconds", 5))) return;
        stay.remove(u);
        if (k == Kind.WALK && step(u) < 2) {
            step(u, 2);
            m.haunt().whisper(p, line("whispers.walk-half", "<dark_gray>…여기서 눈을 감았지. 이제 눈을 뜬 곳으로."));
            m.haunt().behind(p, Sound.BLOCK_GRAVEL_STEP, 0.6f, 0.7f);
            return;
        }
        finish(p);
    }

    private static boolean holds(Player p, String item) {
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()})
            if (it != null && !it.isEmpty() && Items.normalize(Items.key(it)).equals(item)) return true;
        return false;
    }

    /** A whisper at most every {@code near-every-seconds}. */
    private void whisper(Player p, String key, String def) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < nextWhisper.getOrDefault(u, 0L)) return;
        nextWhisper.put(u, now + Math.max(5, cfg().getLong("near-every-seconds", 25)) * 1000L);
        m.haunt().whisper(p, line(key, def));
    }

    /** Close to the place, not on it: now and then, a word that they are warm. */
    private void near(Player p, Target t) {
        Location l = p.getLocation();
        if (t.world() == null || !t.world().equals(l.getWorld().getName())) return;
        double dx = l.getX() - t.x(), dz = l.getZ() - t.z(), r = cfg().getDouble("near-blocks", 40);
        if (dx * dx + dz * dz > r * r) return;
        whisper(p, "whispers.near", "<dark_gray>…발소리가 겹친다. 가깝다.");
    }

    /** The ledger, now and then: a sale of that thing since the letter was read. */
    private void sales(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < nextPoll.getOrDefault(u, 0L)) return;
        nextPoll.put(u, now + Math.max(3, cfg().getLong("poll-seconds", 15)) * 1000L);
        String item = get(u, "item");
        long since = getLong(u, "read", now);
        String week = week(u);
        if (item == null) return;
        m.memory().sales(u, list -> {
            if (!p.isOnline() || state(u) != State.ACTIVE || !week.equals(week(u))) return;
            long newest = 0;
            for (Memory.Sale sale : list) {
                if (sale.at() <= since) break;   // newest first
                newest = Math.max(newest, sale.at());
                if (Items.normalize(sale.item()).equals(item)) {
                    finish(p);
                    return;
                }
            }
            if (newest > wrongSale.getOrDefault(u, since)) {
                wrongSale.put(u, newest);
                m.haunt().whisper(p, line("whispers.wrong", "<dark_gray>…그건 아니야."));
            }
        });
    }

    /** Done: the reward, the shape one step behind for a breath, and its answer in the mailbox. */
    private void finish(Player p) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        long done = count(u);
        String week = week(u);
        complete(p);
        behind(p);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) send(p, reply(p, done, week), "reply.notice", "<gray>우편함에 답장이 와 있습니다. <white>/우편함");
        }, 20L * Math.max(1, cfg().getLong("reply.after-seconds", 6)));
    }

    /** The answer after {@code done} finished weeks: its pool by how warm it is by now, one line after the other. */
    private ItemStack reply(Player p, long done, String week) {
        int tier = Yesterday.warmth(done, cfg().isList("reply.warm-after") ? cfg().getIntegerList("reply.warm-after") : List.of(4, 10));
        List<String> pool = cfg().getStringList("reply.lines." + tier);
        if (pool.isEmpty()) pool = List.of(switch (tier) {
            case 0 -> "봤어.\n네가 서 있는 걸.\n\n나도 1초 뒤에 그렇게 서 봤어.";
            case 1 -> "오늘은 네가 웃는 걸 봤어.\n1초 뒤에 따라 웃어 봤는데,\n\n조금 늦었지만 비슷했어.";
            default -> "고마워.\n\n이제는 자리를 뺏고 싶지 않아.\n네 뒤가 내 자리야.";
        });
        List<String> signs = cfg().getStringList("reply.signs");
        String sign = signs.isEmpty() ? "<gray>— 1초 뒤의 나</gray>" : signs.get(Math.min(tier, signs.size() - 1));
        Map<String, String> v = new HashMap<>();
        v.put("name", p.getName());
        v.put("date", date(LocalDate.now(dev.stsv.economy.secret.SecretModule.KST)));
        v.put("reply", pool.get(Yesterday.turn(done, pool.size())));
        v.put("sign", sign);
        String page = LetterText.fill(line("reply.page", "<dark_gray>{date}</dark_gray>\n\n{reply}\n\n{sign}"), v);
        return book(p, List.of(page), week + ":r", line("reply.title", "답장"));
    }

    /** One step behind them, their own shape, for a breath; then smoke. Only they see it. */
    private void behind(Player p) {
        UUID u = p.getUniqueId();
        Location l = p.getLocation();
        Vector f = l.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) f = new Vector(0, 0, 1);
        Location at = l.clone().subtract(f.normalize().multiply(cfg().getDouble("shadow.behind-blocks", 1.6)));
        Location g = Omens.ground(p.getWorld(), at.getX(), l.getBlockY(), at.getZ());
        if (g != null) at = g;
        at.setYaw(l.getYaw());
        at.setPitch(0);
        Mannequin twin = m.haunt().likeness(p, p, at, null);
        twin.setBodyYaw(l.getYaw());
        props.computeIfAbsent(u, x -> new ArrayList<>()).add(twin);
        m.haunt().behind(p, Sound.BLOCK_GRAVEL_STEP, 0.7f, 0.7f);
        m.haunt().whisper(p, line("whispers.done", "<dark_gray>…봤어."));
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!twin.isValid()) return;
            if (p.isOnline()) p.spawnParticle(Particle.SMOKE, twin.getLocation().add(0, 1, 0), 24, 0.25, 0.6, 0.25, 0.01);
            drop(u, twin);
        }, 20L * Math.max(1, cfg().getLong("shadow.seconds", 3)));
    }

    private void drop(UUID u, Entity e) {
        List<Entity> l = props.get(u);
        if (l != null) l.remove(e);
        Player p = Bukkit.getPlayer(u);
        if (p != null) m.haunt().remove(p, e);
        else if (e.isValid()) e.remove();
    }

    // ------------------------------------------------------------------------------------------
    // the framework's side
    // ------------------------------------------------------------------------------------------

    @Override
    protected void cleanup(UUID u) {
        List<Entity> l = props.remove(u);
        if (l != null) {
            Player p = Bukkit.getPlayer(u);
            for (Entity e : l) {
                if (p != null) m.haunt().remove(p, e);
                else if (e.isValid()) e.remove();
            }
        }
        composing.remove(u);
        stay.remove(u);
        nextPoll.remove(u);
        nextWhisper.remove(u);
        wrongSale.remove(u);
    }

    @Override
    public String status(UUID u) {
        String kind = get(u, "kind");
        if (!live(u)) return "받은 편지 없음 (접속 " + cfg().getLong("after-online", 300) + "초 뒤에 옴)";
        if (kind == null) return "편지를 쓰는 중 (기록 읽기)";
        Kind k = Kind.of(kind).orElse(null);
        if (k == null) return "알 수 없는 수수께끼 " + kind;
        String item = get(u, "item");
        String what = switch (k) {
            case FRIEND -> get(u, "friend", "?") + " 옆 " + cfg().getInt("friend-radius", 4) + "칸 안에 서 있기";
            case SELL_MOST, SELL_OLDEST -> item + " 을(를) 상점에 한 번 팔기";
            case WALK -> (step(u) >= 2 ? "둘째 자리 " + Target.decode(get(u, "t2")).describe() : "첫째 자리 " + Target.decode(get(u, "t1")).describe()
                    + " → 둘째 " + Target.decode(get(u, "t2")).describe()) + " 에 서 있기";
            default -> Target.decode(get(u, "t1")).describe() + " 에 " + (item == null ? "" : item + " 을(를) 손에 들고 ") + "서 있기";
        };
        return "수수께끼 " + k.key + " · " + (state(u) == State.OFFERED ? "먼저 우편함의 편지를 손에 들고 우클릭 (읽기) → " : "") + what
                + " (" + cfg().getInt("stay-seconds", 5) + "초)";
    }

    @Override
    public List<String> scenes() {
        return List.of("편지", "답장", "그림자");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "편지" -> {
                String pages = get(u, "pages");
                if (pages == null) {
                    admin.sendMessage(Text.msg("<gray>이번 주 편지가 아직 없어요. 먼저: /관리자 비밀 후속 내일의나 시작 " + p.getName()));
                    return true;
                }
                ItemStack it = book(p, List.of(pages.split(PAGE_BREAK, -1)), week(u) + ":q", line("letter.title", "어제"));
                if (it != null) give(p, it);
            }
            case "답장" -> {
                ItemStack it = reply(p, count(u), "연출");
                if (it != null) give(p, it);
            }
            case "그림자" -> behind(p);
            default -> {
                return false;
            }
        }
        return true;
    }
}
