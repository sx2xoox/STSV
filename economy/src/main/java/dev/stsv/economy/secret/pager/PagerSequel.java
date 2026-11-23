package dev.stsv.economy.secret.pager;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 후속 임무 「호출」 (docs/admin/숨은_비밀/울리는_삐삐.md "후속 임무"; pager.yml {@code sequel}). The pager went home and came
 * back alive; the daughter, a grown woman now, knows that someone carries her father's pager. About once a week a number
 * arrives on it ({@link Calls}): bread, something warm, come quickly, come before dark, come by, or a number that wants a
 * number back. As in the story itself the game never says what a number means. The errand ends where the blue gate stood:
 * for that week one gate post is left there with the basket on it ({@link Hook}), for the solver alone.
 * <p>
 * Steps: 0 = a call that was missed comes again after a while; 1 = the number is on the screen, the errand is open;
 * 2 = done, her answer is on its way (then the reward, and a short note from her in the mail).
 * Notes of the week ("sequel.x."): call (its id), anchor ("world x y z": where the post stands), deadline (rush), day
 * (dusk: the world's day it came on), tries (missed so far), at (when it comes again), took (what the basket took: given
 * back if the week ends before her answer), forced (an admin began it: no distance or hour is asked).
 * Kept ("sequel.keep."): recent (the last calls), notes (the last notes).
 * <p>
 * The live pager itself is never taken, copied or made here: only the first line of its lore (its screen) is written, on
 * the working copy while its owner carries it.
 */
public final class PagerSequel extends Sequel {

    static final List<String> NOTES = List.of(
            "오늘도 삐삐가 닿았네요.\n\n누구신지는 여전히 모릅니다. 묻지 않기로 했어요.\n\n고맙습니다.",
            "대문이 없는 자리에 기둥 하나를 남겨 두었습니다.\n\n바구니는 아버지가 달아 둔 것이에요.\n\n고맙습니다.",
            "번호를 누를 때마다, 받을 사람이 있다는 게 아직도 이상합니다.\n\n좋은 쪽으로요.");

    private final Pager s;
    private final Hook hook;
    private List<Calls.Call> calls = Calls.DEFAULTS;
    /** player -> where their post stands (read from the note once) */
    private final Map<UUID, Location> anchors = new HashMap<>();
    /** player -> when a rush call beeps again */
    private final Map<UUID, Long> nextBeep = new HashMap<>();

    PagerSequel(Pager s) {
        super(s);
        this.s = s;
        this.hook = new Hook(s);
    }

    @Override
    public String id() {
        return "호출";
    }

    @Override
    protected void loaded() {
        List<String> problems = new ArrayList<>();
        calls = Calls.read(cfg().getConfigurationSection("calls"), problems);
        for (String p : problems) eco.getLogger().warning("[비밀] pager.yml: " + p);
        hook.clearAll();
        anchors.clear();
    }

    // ------------------------------------------------------------------------------------------
    // small things
    // ------------------------------------------------------------------------------------------

    private Calls.Call call(UUID u) {
        String id = get(u, "call");
        return id == null ? null : Calls.byId(calls, id);
    }

    /** Whether the working copy of their live pager is on them. */
    private boolean carries(Player p) {
        UUID u = p.getUniqueId();
        for (ItemStack it : p.getInventory().getContents()) if (s.device().live(it, u)) return true;
        return s.device().live(p.getItemOnCursor(), u);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /**
     * Writes the first line of the live pager's lore (its screen) on the working copy they carry; the rest of the lore,
     * and the item, stay as they are. Nothing is touched when the line already says so.
     */
    private void screen(Player p, String mm) {
        UUID u = p.getUniqueId();
        Component line = Text.item(mm);
        String want = plain(line);
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) {
            if (!s.device().live(all[i], u) || !rewrite(all[i], line, want)) continue;
            inv.setItem(i, all[i]);
        }
        ItemStack cur = p.getItemOnCursor();
        if (s.device().live(cur, u) && rewrite(cur, line, want)) p.setItemOnCursor(cur);
    }

    private static boolean rewrite(ItemStack it, Component line, String want) {
        List<Component> old = it.lore();
        List<Component> lore = old == null ? new ArrayList<>() : new ArrayList<>(old);
        if (!lore.isEmpty() && plain(lore.getFirst()).equals(want)) return false;
        if (lore.isEmpty()) lore.add(line);
        else lore.set(0, line);
        it.lore(lore);
        return true;
    }

    private String codeLine(String code) {
        return line("screen", "<#b6f2a6>[오늘] {code}", "code", code);
    }

    /** The screen as the reward came: the first line of pager.live-lore. */
    private String restLine() {
        List<String> l = s.cfg().getStringList("pager.live-lore");
        return l.isEmpty() ? "<#b6f2a6>[오늘] 1004" : l.getFirst();
    }

    private void buzz(Player p, String code) {
        s.chirp(p, p.getLocation(), 1f);
        String mm = code == null ? s.text("lines.vibrate", "<#9fd8a0>삐삐삐— 삐삐삐—")
                : s.text("lines.again", "<#9fd8a0>삐삐삐— <white>{code}").replace("{code}", code);
        if (!mm.isBlank()) HudModule.flash(p, Text.mm(mm), 2500);
    }

    private static Location place(String v) {
        if (v == null) return null;
        String[] w = v.split(" ");
        World world = w.length < 4 ? null : Bukkit.getWorld(w[0]);
        if (world == null) return null;
        try {
            return new Location(world, Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Where the blue gate stood for this player (the story's saved house), or null (a tester who never had one). */
    private Location gate(UUID u) {
        Location at = place(s.kv(u, "house"));
        return at == null ? null : at.add(0.5, 0, 0.5);
    }

    /** A spot a few blocks ahead of them, on their own level. */
    private static Location ahead(Player p) {
        Location l = p.getLocation();
        org.bukkit.util.Vector d = l.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new org.bukkit.util.Vector(0, 0, 1);
        Location at = l.clone().add(d.normalize().multiply(4));
        return new Location(at.getWorld(), at.getBlockX() + 0.5, l.getBlockY(), at.getBlockZ() + 0.5);
    }

    /** Where this week's post stands (the note; null when its world is not loaded). */
    private Location anchor(UUID u) {
        Location a = anchors.get(u);
        if (a != null) return a;
        a = place(get(u, "anchor"));
        if (a != null) anchors.put(u, a);
        return a;
    }

    /** Flat distance to a spot; infinite in another world. */
    private static double far(Player p, Location at) {
        if (at == null || !at.getWorld().equals(p.getWorld())) return Double.POSITIVE_INFINITY;
        return Math.hypot(at.getX() - p.getLocation().getX(), at.getZ() - p.getLocation().getZ());
    }

    private double minDistance(UUID u) {
        return m.test(u) ? cfg().getDouble("test-rush-min-distance", 30) : cfg().getDouble("rush-min-distance", 300);
    }

    /** Whether this call can be asked of them right now (a run needs them far away, "come early" needs daylight left). */
    private boolean possible(Calls.Call c, Player p, Location at) {
        return switch (c.kind()) {
            case RUSH -> far(p, at) >= minDistance(p.getUniqueId());
            case DUSK -> at != null && at.getWorld().getEnvironment() == World.Environment.NORMAL
                    && Calls.dayEnough(at.getWorld().getTime(), cfg().getLong("dusk-latest-tick", 6000));
            default -> true;
        };
    }

    // ------------------------------------------------------------------------------------------
    // a call arrives
    // ------------------------------------------------------------------------------------------

    /** This week's call for them (always the same within a week while the same ones are possible), or null. */
    private Calls.Call choose(Player p, boolean anything) {
        UUID u = p.getUniqueId();
        if (calls.isEmpty()) return null;
        Location at = gate(u);
        List<String> recent = Calls.recent(kept(u, "recent", ""));
        long seed = Calls.seed(week(u), u.getMostSignificantBits(), u.getLeastSignificantBits());
        int i = Calls.pick(calls.size(), seed, x -> anything || possible(calls.get(x), p, at), x -> recent.contains(calls.get(x).id()));
        return i < 0 ? null : calls.get(i);
    }

    @Override
    protected boolean offer(Player p) {
        if (p.isDead() || !carries(p)) return false;   // it arrives on the pager: no pager on them, no call
        if (System.currentTimeMillis() - p.getLastLogin() < Math.max(0, cfg().getLong("settle-seconds", 60)) * 1000L) return false;
        if (!chance(p, "chance", 0.002)) return false;
        Calls.Call c = choose(p, false);
        if (c == null) return false;
        begin(p, c, false);
        return true;
    }

    @Override
    protected void start(Player p) {
        Calls.Call c = choose(p, true);
        if (c != null) begin(p, c, true);
    }

    private void begin(Player p, Calls.Call c, boolean forced) {
        UUID u = p.getUniqueId();
        hook.hide(u);
        anchors.remove(u);
        nextBeep.remove(u);
        for (String k : List.of("deadline", "day", "at", "took")) remove(u, k);
        set(u, "call", c.id());
        set(u, "tries", 0);
        if (forced) set(u, "forced", 1);
        else remove(u, "forced");
        keep(u, "recent", Calls.remember(kept(u, "recent", ""), c.id(), cfg().getInt("avoid-recent", 4)));
        Location a = gate(u);
        if (a == null) a = ahead(p);   // (a tester who never had the house: right in front of them)
        set(u, "anchor", a.getWorld().getName() + " " + a.getX() + " " + a.getBlockY() + " " + a.getZ());
        ring(p, c);
    }

    /** The number comes: on the screen, the beep, the shaking. Its clock (a run, an evening) starts now. */
    private void ring(Player p, Calls.Call c) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Location a = anchor(u);
        if (c.kind() == Calls.Kind.RUSH) {
            double min = minDistance(u);
            int secs = Codes.rushSeconds((int) seconds(u, "rush-seconds", 240), min, far(p, a), cfg().getInt("rush-max-seconds", 900));
            set(u, "deadline", now + secs * 1000L);
            nextBeep.put(u, now + Codes.repeatGap(secs) * 1000L);
        } else if (c.kind() == Calls.Kind.DUSK && a != null) {
            set(u, "day", Math.floorDiv(a.getWorld().getFullTime(), 24000L));
        }
        screen(p, codeLine(c.code()));
        buzz(p, null);
        step(u, 1);
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        Calls.Call c = call(u);
        if (c == null) {   // the call was taken out of the file this week
            hook.hide(u);
            fail(p, "missed");
            return;
        }
        if (p.isDead()) return;
        long now = System.currentTimeMillis();
        if (c.kind().there()) post(p);
        switch (step(u)) {
            case 0 -> {
                Location a = anchor(u);
                if (now < getLong(u, "at", 0) || !carries(p) || !(get(u, "forced") != null || possible(c, p, a))) return;
                ring(p, c);
            }
            case 1 -> asked(p, c, now);
            case 2 -> {
                if (elapsed(u) >= seconds(u, "reply-seconds", 6)) finish(p, c);
            }
            default -> {}
        }
    }

    /** The post is there for them while they are near it. */
    private void post(Player p) {
        UUID u = p.getUniqueId();
        Location a = anchor(u);
        if (a == null) return;
        double show = cfg().getDouble("show-range", 40), d = far(p, a);
        if (hook.showing(u)) {
            if (hook.preview(u)) {
                if (d <= show) hook.hide(u);   // an admin's look gives way to the real one
                return;
            }
            if (d > show + 8 || hook.gone(u)) hook.hide(u);
        } else if (d <= show && a.getWorld().isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)) {
            hook.show(p, Hook.settle(a), false);
            if (step(u) == 2 && get(u, "took") != null) Items.create(get(u, "took"), 1).ifPresent(it -> hook.fill(p, it));
        }
    }

    /** At the post, with the pager on them. */
    private boolean arrived(Player p) {
        UUID u = p.getUniqueId();
        Location at = hook.showing(u) && !hook.preview(u) ? hook.where(u) : null;
        if (at == null) return false;
        return far(p, at) <= cfg().getDouble("arrive-range", 4) && Math.abs(at.getY() - p.getLocation().getY()) <= 6 && carries(p);
    }

    private void asked(Player p, Calls.Call c, long now) {
        UUID u = p.getUniqueId();
        if (now / 1000 % 5 == 0) screen(p, codeLine(c.code()));   // (a pager just taken out of a chest shows it too)
        switch (c.kind()) {
            case RUSH -> {
                long deadline = getLong(u, "deadline", 0);
                if (arrived(p)) done(p);
                else if (now > deadline) miss(p);
                else {
                    long next = nextBeep.computeIfAbsent(u, x -> now + Codes.repeatGap((deadline - now) / 1000) * 1000L);
                    if (now < next || !carries(p)) return;
                    nextBeep.put(u, now + Codes.repeatGap((deadline - now) / 1000) * 1000L);
                    buzz(p, c.code());
                }
            }
            case DUSK -> {
                Location a = anchor(u);
                if (arrived(p)) done(p);
                else if (a != null && Calls.dusk(a.getWorld().getFullTime(), getLong(u, "day", 0))) miss(p);
            }
            case VISIT -> {
                if (arrived(p)) done(p);
            }
            default -> {}
        }
    }

    /** The errand is done: her answer is on its way. */
    private void done(Player p) {
        UUID u = p.getUniqueId();
        nextBeep.remove(u);
        step(u, 2);
        hook.latch(p);
    }

    /** Too late. It comes again another time, once or twice; after that the week is over (nothing is lost but this week). */
    private void miss(Player p) {
        UUID u = p.getUniqueId();
        nextBeep.remove(u);
        long tries = getLong(u, "tries", 0) + 1;
        set(u, "tries", tries);
        remove(u, "deadline");
        remove(u, "day");
        screen(p, line("screen-late", "<#648f6a>…"));
        s.sound(p, p.getLocation(), s.text("beep.sound", "block.note_block.bit"), 0.3f, 0.6f);
        if (tries > Math.max(0, cfg().getInt("retries", 1))) {
            hook.hide(u);
            fail(p, "missed");
            return;
        }
        set(u, "at", System.currentTimeMillis() + seconds(u, "retry-seconds", 900) * 1000L);
        step(u, 0);
    }

    /** Her answer: the number of thanks on the screen, the reward, and a little later a note in the mail. */
    private void finish(Player p, Calls.Call c) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        screen(p, codeLine(cfg().getString("thanks", s.text("codes.thanks", "1004"))));
        buzz(p, null);
        hook.empty(u);
        remove(u, "took");   // it was hers from the moment the basket took it; now it is answered
        complete(p);
        if (cfg().getBoolean("note.enabled", true)) {
            long wait = Math.max(1, seconds(u, "note-seconds", 120)) * 20L;
            long seed = Calls.seed(week(u), u.getLeastSignificantBits(), u.getMostSignificantBits());
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                ItemStack note = note(u, c, seed);
                if (note != null && eco.mail() != null) eco.mail().deliver(u, note, "pager");
                Player q = Bukkit.getPlayer(u);
                if (q != null) s.sound(q, q.getLocation(), "item.book.page_turn", 0.7f, 0.8f);
            }, wait);
        }
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!live(u)) hook.hide(u);
        }, 200L);
    }

    /** A short note from her (a one-page book): one of the pool for this kind of errand, not one of the last few. */
    private ItemStack note(UUID u, Calls.Call c, long seed) {
        List<String> pool = new ArrayList<>(cfg().getStringList("notes." + c.kind().name().toLowerCase(Locale.ROOT)));
        pool.addAll(cfg().getStringList("notes.any"));
        pool.removeIf(x -> x == null || x.isBlank());
        if (pool.isEmpty()) pool.addAll(NOTES);
        List<String> recent = Calls.recent(kept(u, "notes", ""));
        int i = Calls.pick(pool.size(), seed, x -> true, x -> recent.contains(Integer.toHexString(pool.get(x).hashCode())));
        String text = pool.get(Math.max(0, i));
        keep(u, "notes", Calls.remember(kept(u, "notes", ""), Integer.toHexString(text.hashCode()), cfg().getInt("note.avoid-recent", 5)));
        ItemStack it = Items.create("economy:pager_letter", 1).orElseGet(() -> new ItemStack(Material.WRITTEN_BOOK));
        if (!(it.getItemMeta() instanceof BookMeta bm)) return it;
        LocalDate today = LocalDate.now(SecretModule.KST);
        String date = today.getYear() + "년 " + today.getMonthValue() + "월 " + today.getDayOfMonth() + "일";
        String title = line("note.title", "파란 대문 집에서");
        bm.setAuthor(line("note.author", s.text("letter.author", "파란 대문 집")));
        bm.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
        bm.setGeneration(BookMeta.Generation.ORIGINAL);
        bm.pages(List.of(Text.mm(line("note.page", "<dark_gray>{date}</dark_gray>\n\n{text}\n\n<dark_gray>— 파란 대문 집 딸", "date", date, "text", text, "code", c.code()))));
        bm.itemName(Text.item(line("note.name", "<#d8d2c4>쪽지")));
        List<String> lore = new ArrayList<>();
        for (String l : cfg().isList("note.lore") ? cfg().getStringList("note.lore") : List.of("<gray>파란 대문 집에서 온 쪽지", "<dark_gray>{date}")) lore.add(l.replace("{date}", date));
        bm.lore(Text.itemLines(lore));
        it.setItemMeta(bm);
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // the basket, and paging her back
    // ------------------------------------------------------------------------------------------

    private static boolean wanted(ItemStack hand, List<String> ids) {
        if (hand == null || hand.isEmpty() || Items.trial(hand)) return false;
        String key = Items.key(hand);
        for (String id : ids) if (Items.normalize(id).equals(key)) return true;
        return false;
    }

    private void basket(Player p) {
        UUID u = p.getUniqueId();
        Calls.Call c = hook.preview(u) || !may(p) || !live(u) ? null : call(u);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (c == null || c.kind() != Calls.Kind.BASKET || step(u) != 1 || !wanted(hand, c.items())) {
            hook.swing(p);
            return;
        }
        // the outcome is saved before the item goes: one is taken, her answer is on its way (and if the week ends first, it comes back)
        set(u, "took", Items.key(hand));
        step(u, 2);
        hook.fill(p, hand);
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) hook.latch(p);
        }, 12L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction hit) || !hit.getPersistentDataContainer().has(Hook.TAG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || !hook.mine(e.getPlayer(), hit)) return;
        basket(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction hit && hit.getPersistentDataContainer().has(Hook.TAG)) e.setCancelled(true);
    }

    /**
     * The live pager paged a name that is no player online ({@link Paging}): while one of her calls is open, her names
     * ({@code sequel.reply.names}) are someone the signal reaches. True: it was sent to her (and, when the call wanted a
     * number back and this is it, that is the errand done). False: not hers to take — the pager says what it always says.
     */
    boolean paged(Player p, String name, String digits) {
        UUID u = p.getUniqueId();
        if (!may(p) || !live(u) || step(u) != 1) return false;
        List<String> names = cfg().isList("reply.names") ? cfg().getStringList("reply.names") : List.of("파란대문", "파란대문집", "딸");
        if (!Calls.her(name, names)) return false;
        Calls.Call c = call(u);
        if (Calls.answers(c, digits) && carries(p)) step(u, 2);
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // coming and going
    // ------------------------------------------------------------------------------------------

    @Override
    protected void resume(Player p) {
        UUID u = p.getUniqueId();
        Calls.Call c = call(u);
        if (c == null || step(u) != 1) return;
        nextBeep.put(u, System.currentTimeMillis() + 5000L);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline() || !live(u) || step(u) != 1 || !carries(p)) return;
            screen(p, codeLine(c.code()));
            s.sound(p, p.getLocation(), s.text("beep.sound", "block.note_block.bit"), 0.35f, (float) s.cfg().getDouble("beep.pitch", 1.9));
        }, 100L);
    }

    @Override
    protected void cleanup(UUID u) {
        hook.hide(u);
        anchors.remove(u);
        nextBeep.remove(u);
    }

    /** The week ended on an open call: what the basket took and she never answered goes back; the screen is as it was. */
    @Override
    protected void expired(UUID u) {
        String took = get(u, "took");
        if (took != null && step(u) == 2) Items.create(took, 1).ifPresent(it -> give(u, it, "pager"));
        Player p = Bukkit.getPlayer(u);
        if (p != null) screen(p, restLine());
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    private static String ago(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return sec >= 60 ? sec / 60 + "분 " + sec % 60 + "초" : sec + "초";
    }

    @Override
    public String status(UUID u) {
        if (!live(u)) return "호출 " + calls.size() + "가지 · 최근 " + kept(u, "recent", "-");
        Calls.Call c = call(u);
        if (c == null) return "호출 " + get(u, "call", "?") + " (설정에서 사라짐: 다음 틱에 끝남)";
        long now = System.currentTimeMillis();
        String a = get(u, "anchor", "-");
        String what = switch (step(u)) {
            case 0 -> "놓쳐서 다시 올 때까지 " + ago(getLong(u, "at", 0) - now) + (c.kind() == Calls.Kind.RUSH ? " (그때 기둥에서 "
                    + (int) minDistance(u) + "칸 이상 떨어져 있어야 옴)" : c.kind() == Calls.Kind.DUSK ? " (그때 월드가 낮이어야 옴)" : "") + " · 삐삐를 지니고 있어야 함";
            case 1 -> switch (c.kind()) {
                case BASKET -> "기둥의 바구니에 " + String.join(" · ", c.items()) + " 중 하나를 들고 우클릭";
                case RUSH -> "살아 있는 삐삐를 지니고 기둥 앞에 서기: 남은 " + ago(getLong(u, "deadline", 0) - now);
                case DUSK -> "살아 있는 삐삐를 지니고 그 월드의 해가 지기 전(13000 틱 전)에 기둥 앞에 서기";
                case VISIT -> "살아 있는 삐삐를 지니고 기둥 앞에 서기 (시간 제한 없음)";
                case REPLY -> "살아 있는 삐삐 우클릭 → 채팅에 \"파란대문 " + c.answers().getFirst() + "\" (받는 이름은 sequel.reply.names, 숫자는 " + String.join(" · ", c.answers()) + ")";
            };
            default -> "심부름 끝: 답(" + cfg().getString("thanks", "1004") + ")까지 " + ago(seconds(u, "reply-seconds", 6) * 1000L - (now - since(u)));
        };
        return "호출 " + c.code() + " (" + c.id() + ")" + (c.kind().there() ? " · 기둥 " + a + (hook.showing(u) ? " (보이는 중)" : "") : "")
                + " · 놓친 횟수 " + getLong(u, "tries", 0) + " · " + what;
    }

    @Override
    public List<String> scenes() {
        List<String> out = new ArrayList<>(List.of("호출", "기둥", "쪽지"));
        for (Calls.Call c : calls) out.add("호출:" + c.id());
        return out;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        String note = "";
        if (word.startsWith("호출:")) {
            Calls.Call c = Calls.byId(calls, word.substring(3));
            if (c == null) return false;
            if (!live(u)) {
                admin.sendMessage(Text.mm("<red>[호출] 진행 중인 후속 임무가 없어요. 먼저 /관리자 비밀 후속 삐삐 시작 " + p.getName()));
                return true;
            }
            begin(p, c, true);
            note = " (이번 주 호출을 " + c.code() + " 로 바꿔 처음부터: 거리 · 시간 조건 없음)";
        } else switch (word) {
            case "호출" -> {
                Calls.Call c = live(u) && call(u) != null ? call(u) : calls.get((int) Math.floorMod(System.nanoTime(), (long) calls.size()));
                buzz(p, c.code());
                note = " " + c.code() + " (소리와 화면 아래 글씨만; 아무것도 바뀌지 않음)";
            }
            case "기둥" -> {
                if (hook.showing(u) && !hook.preview(u)) {
                    admin.sendMessage(Text.mm("<red>[호출] 이 사람의 진짜 기둥이 지금 보이는 중이에요 (" + get(u, "anchor", "-") + ")"));
                    return true;
                }
                hook.show(p, Hook.settle(ahead(p)), true);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (hook.preview(u)) hook.hide(u);
                }, 1200L);
                note = " (보는 쪽 4칸 앞에 미리보기, 60초; 바구니는 흔들리기만)";
            }
            case "쪽지" -> {
                Calls.Call c = live(u) && call(u) != null ? call(u) : calls.getFirst();
                ItemStack it = note(u, c, System.nanoTime());
                if (it != null && eco.mail() != null) eco.mail().deliver(u, it, "pager");
                note = " (쪽지를 우편으로)";
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " → " + p.getName() + note));
        return true;
    }
}
