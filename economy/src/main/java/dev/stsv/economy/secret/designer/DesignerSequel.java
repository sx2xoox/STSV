package dev.stsv.economy.secret.designer;

import dev.stsv.core.util.Text;
import dev.stsv.economy.job.MachineModule;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState.State;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 설계도 의뢰: the 후속 임무 of 설계자 (docs/admin/숨은_비밀/설계자.md "후속 임무"). The 13th machine lives, and a living thing
 * wears: once a week, when its heir comes near, it puts out a blueprint — a riddle for so many of a good one of the heir's
 * machines makes ({@code sequel.orders}, real recipes of machines.yml). The heir makes them and feeds them to it:
 * right-click the 13th machine with the goods in hand. It takes what is still missing and nothing else (a wrong thing,
 * a bought thing, someone else's work stays in the hand); an empty hand opens the blueprint. Filled: it breathes deep, a
 * word of thanks, the reward.
 * <p>
 * Notes of the week: {@code order}, {@code item}, {@code need}, {@code got}. What it took is the heir's until the order is
 * filled: an unfinished order's goods go back ({@link #expired}: the bag, else the mailbox). Kept: {@code recent}.
 */
public final class DesignerSequel extends Sequel {

    private record Tag(TextDisplay text, Location origin, String shown) {}

    private final Designer d;
    private final Map<UUID, Tag> tags = new HashMap<>();
    private final Map<UUID, Long> said = new HashMap<>();

    DesignerSequel(Designer d) {
        super(d);
        this.d = d;
    }

    @Override
    public String id() {
        return "설계도 의뢰";
    }

    // ------------------------------------------------------------------------------------------
    // the orders
    // ------------------------------------------------------------------------------------------

    /** sequel.orders as written (id → order); ones without an item or an amount are left out. */
    static Map<String, Blueprint.Order> orders(ConfigurationSection sequel) {
        Map<String, Blueprint.Order> out = new LinkedHashMap<>();
        ConfigurationSection all = sequel == null ? null : sequel.getConfigurationSection("orders");
        if (all == null) return out;
        for (String id : all.getKeys(false)) {
            ConfigurationSection o = all.getConfigurationSection(id);
            if (o == null || o.getString("item", "").isBlank() || o.getInt("amount", 0) <= 0) continue;
            out.put(id, new Blueprint.Order(id, Items.normalize(o.getString("item", "")), o.getInt("amount"), o.getString("machine", ""), o.getStringList("lines")));
        }
        return out;
    }

    /** Whether a plain recipe (no upgrade, no ageing) of the order's machine makes its good. */
    private boolean makeable(Blueprint.Order o) {
        MachineModule mm = eco.machines();
        if (mm == null) return false;
        MachineSpec spec = mm.specs().get(o.machine());
        if (spec == null) return false;
        for (MachineSpec.Recipe r : spec.recipes()) if (r.tier() == 0 && !r.aging() && Items.normalize(r.output()).equals(o.item())) return true;
        return false;
    }

    @Override
    protected void loaded() {
        if (eco.machines() == null || eco.machines().specs().isEmpty()) return;   // not read yet: nothing to check against
        for (Blueprint.Order o : orders(cfg()).values())
            if (!makeable(o)) eco.getLogger().warning("[비밀] 설계자 후속: 의뢰 " + o.id() + " 의 " + o.item() + " 은(는) " + o.machine() + " 의 레시피에 없어요 (안 나옴)");
    }

    /**
     * The orders this player may get: makeable ones of machines whose job they have worked at ({@code min-job-level}; the
     * press has no job and always counts). When that leaves fewer than {@code min-choices}, every makeable one.
     */
    private List<String> eligible(UUID u) {
        List<String> all = new ArrayList<>(), mine = new ArrayList<>();
        int min = cfg().getInt("min-job-level", 1);
        for (Blueprint.Order o : orders(cfg()).values()) {
            if (!makeable(o)) continue;
            all.add(o.id());
            String job = eco.machines().specs().get(o.machine()).job();
            if (job == null || job.isBlank() || min <= 0 || eco.jobs() == null || eco.jobs().level(u, job) >= min) mine.add(o.id());
        }
        return mine.size() >= Math.max(1, cfg().getInt("min-choices", 4)) ? mine : all;
    }

    private static long seed(UUID u) {
        return u.getMostSignificantBits() ^ Long.rotateLeft(u.getLeastSignificantBits(), 17);
    }

    // ------------------------------------------------------------------------------------------
    // the blueprint comes out
    // ------------------------------------------------------------------------------------------

    private static boolean within(Player p, Location origin, double r) {
        return origin != null && origin.getWorld() == p.getWorld() && origin.clone().add(0.5, 1, 0.5).distanceSquared(p.getLocation()) <= r * r;
    }

    @Override
    protected boolean offer(Player p) {
        if (!within(p, d.machine13(), cfg().getDouble("near", 10))) return false;
        if (eligible(p.getUniqueId()).isEmpty()) return false;
        start(p);
        return true;
    }

    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        hide(u);
        List<String> ids = eligible(u);
        List<String> recent = Blueprint.words(kept(u, "recent", ""));
        String id = Blueprint.pick(seed(u), week(u), ids, recent);
        Blueprint.Order o = id == null ? null : orders(cfg()).get(id);
        if (o == null) {
            eco.getLogger().warning("[비밀] 설계자 후속: 낼 수 있는 의뢰가 없어요 (designer.yml sequel.orders, machines.yml 확인)");
            return;
        }
        keep(u, "recent", String.join(",", Blueprint.remember(recent, id, cfg().getInt("avoid-recent", 5))));
        set(u, "order", o.id());
        set(u, "item", o.item());
        set(u, "need", o.need());
        set(u, "got", 0);
        Location at = d.machine13();
        if (at != null && within(p, at, 48)) {
            Location mid = at.clone().add(0.5, 1, 0.5);
            d.sound(p, mid, "breath");
            d.sound(p, mid, "page");
            p.spawnParticle(Particle.WHITE_ASH, mid, 20, 0.3, 0.5, 0.3, 0.01);
        } else {
            d.sound(p, p.getLocation(), "page");
        }
        tell(p, "out", "<dark_gray>…열세 번째 기계가 종이 한 장을 밀어냈다. <gray>(빈손으로 기계를 우클릭)");
    }

    private Blueprint.Order order(UUID u) {
        String id = get(u, "order");
        if (id == null) return null;
        Blueprint.Order o = orders(cfg()).get(id);
        // the yml changed under a going order: what was saved still counts
        return o != null ? new Blueprint.Order(id, get(u, "item", o.item()), (int) getLong(u, "need", o.need()), o.machine(), o.lines())
                : new Blueprint.Order(id, get(u, "item", ""), (int) getLong(u, "need", 1), "", List.of());
    }

    private Book book(Blueprint.Order o, long got) {
        List<String> lines = o.lines().isEmpty() ? List.of(Items.mmName(o.item())) : o.lines();
        List<Component> pages = new ArrayList<>();
        List<List<String>> parts = Parts.bookPages(lines);
        for (int i = 0; i < parts.size(); i++) {
            String body = String.join("\n", parts.get(i));
            if (i == parts.size() - 1) body = line("book.page", "{lines}\n\n<#7a2e1f>{got} / {need}</#7a2e1f>\n\n<#8a8170>다 되면 손에 들고\n나를 눌러라.")
                    .replace("{lines}", body).replace("{got}", String.valueOf(Math.min(got, o.need()))).replace("{need}", String.valueOf(o.need()));
            pages.add(Text.mm("<#3b3326>" + body));
        }
        return Book.book(Text.mm(line("book.title", "설계도")), Text.mm(line("book.author", "설계자")), pages);
    }

    // ------------------------------------------------------------------------------------------
    // feeding it
    // ------------------------------------------------------------------------------------------

    /**
     * The heir right-clicked the 13th machine ({@link Designer#onTouch}). True when this mission answered (the usual touch
     * line is then left out). Only what is counted is taken from the hand.
     */
    boolean touched(Player p) {
        UUID u = p.getUniqueId();
        if (!may(p) || !live(u)) return false;
        Blueprint.Order o = order(u);
        if (o == null) return false;
        long got = getLong(u, "got", 0);
        Location mid = d.machine13() == null ? p.getLocation() : d.machine13().add(0.5, 1, 0.5);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            accept(p);
            d.sound(p, mid, "page");
            p.openBook(book(o, got));
            return true;
        }
        if (!Items.normalize(Items.key(hand)).equals(o.item())) {
            m.haunt().whisper(p, line("wrong", "<dark_gray>…그건 설계도에 없다."));
            return true;
        }
        if (Items.shopBought(hand) || (cfg().getBoolean("own-only", true) && !Designer.ownWork(hand, u))) {
            m.haunt().whisper(p, line("not-yours", "<dark_gray>…네 기계에서, 네 이름으로 나온 것만."));
            return true;
        }
        int take = Blueprint.take(hand.getAmount(), got, o.need());
        if (take <= 0) return true;
        int left = hand.getAmount() - take;
        p.getInventory().setItemInMainHand(left <= 0 ? null : hand.asQuantity(left));
        set(u, "got", got + take);
        accept(p);
        d.sound(p, mid, "heartbeat");
        p.spawnParticle(Particle.DUST, mid, 8, 0.25, 0.3, 0.25, new Particle.DustOptions(org.bukkit.Color.fromRGB(0x9a2b1e), 0.9f));
        if (Blueprint.filled(got + take, o.need())) {
            finish(p, mid);
        } else {
            m.haunt().whisper(p, line("progress", "<dark_gray>…{got} / {need}. 더.").replace("{got}", String.valueOf(got + take)).replace("{need}", String.valueOf(o.need())));
        }
        return true;
    }

    /** Filled: it breathes deep (only the heir hears and sees it), says a word, and the week is done. */
    private void finish(Player p, Location mid) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        long done = count(u);
        hide(u);
        breathe(p, mid, done);
        complete(p);
    }

    private void breathe(Player p, Location mid, long done) {
        d.sound(p, mid, "breath");
        p.spawnParticle(Particle.WHITE_ASH, mid, 40, 0.4, 0.7, 0.4, 0.02);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            d.sound(p, mid, "heartbeat");
            p.spawnParticle(Particle.DUST, mid, 14, 0.3, 0.4, 0.3, new Particle.DustOptions(org.bukkit.Color.fromRGB(0x9a2b1e), 1.1f));
        }, 25L);
        List<String> thanks = cfg().getStringList("thanks");
        int i = Blueprint.turn(done, thanks.size());
        String word = i < 0 ? "<dark_gray>…맞는다. 다시 돈다." : thanks.get(i);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Text.msg(word));
            d.sound(p, mid, "breath");
        }, 50L);
    }

    // ------------------------------------------------------------------------------------------
    // the count over the machine (only the heir sees it)
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        Blueprint.Order o = order(u);
        if (o == null) return;
        Location origin = d.machine13();
        Tag t = tags.get(u);
        if (!within(p, origin, cfg().getDouble("tag-radius", 24))) {
            if (t != null) hide(u);
            return;
        }
        String text = line("tag", "<#8a8170>설계도 <white>{got}</white><gray> / {need}").replace("{got}", String.valueOf(getLong(u, "got", 0)))
                .replace("{need}", String.valueOf(o.need()));
        if (t != null && t.text.isValid() && t.origin.equals(origin)) {
            if (!t.shown.equals(text)) {
                t.text.text(Text.mm(text));
                tags.put(u, new Tag(t.text, t.origin, text));
            }
        } else {
            hide(u);
            TextDisplay td = m.haunt().text(p, origin.clone().add(0.5, cfg().getDouble("tag-height", 2.35), 0.5), text);
            td.setViewRange(0.4f);
            tags.put(u, new Tag(td, origin, text));
        }
        // now and then, near it with an open order: it reminds them
        long now = System.currentTimeMillis();
        if (state(u) == State.OFFERED && now >= said.getOrDefault(u, 0L)) {
            said.put(u, now + Math.max(20, cfg().getLong("remind-seconds", 90)) * 1000L);
            m.haunt().whisper(p, line("remind", "<dark_gray>…종이가 나와 있다."));
        }
    }

    private void hide(UUID u) {
        Tag t = tags.remove(u);
        if (t == null) return;
        Player p = Bukkit.getPlayer(u);
        if (p != null) m.haunt().remove(p, t.text);
        else if (t.text.isValid()) t.text.remove();
    }

    // ------------------------------------------------------------------------------------------
    // the framework's side
    // ------------------------------------------------------------------------------------------

    @Override
    protected void cleanup(UUID u) {
        hide(u);
        said.remove(u);
    }

    /** An order that was never filled: what the machine took goes back (the bag, else the mailbox), as the heir's own work. */
    @Override
    protected void expired(UUID u) {
        long got = getLong(u, "got", 0);
        String item = get(u, "item");
        if (got <= 0 || item == null) return;
        ItemStack one = Items.create(item, 1).orElse(null);
        if (one == null) {
            eco.getLogger().warning("[비밀] 설계자 후속: 돌려줄 아이템 " + item + " 이 없어요 (" + u + ", " + got + "개)");
            return;
        }
        one.editMeta(meta -> meta.getPersistentDataContainer().set(MachineModule.MAKER, PersistentDataType.STRING, u.toString()));
        for (int n : Blueprint.stacks(got, one.getMaxStackSize())) give(u, one.asQuantity(n), "designer");
        set(u, "got", 0);
        Player p = Bukkit.getPlayer(u);
        if (p != null) tell(p, "returned", "<dark_gray>…설계도가 바랬다. 기계가 받아 둔 것을 도로 내놓았다.");
        eco.getLogger().info("[비밀] 후속 설계자: " + u + " 에게 " + item + " " + got + "개를 돌려줌");
    }

    @Override
    public String status(UUID u) {
        Location at = d.machine13();
        String where = at == null ? "<red>13번째 기계가 서 있지 않음</red>" : "기계 " + at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ();
        Blueprint.Order o = live(u) ? order(u) : null;
        if (o == null) return where + " · 받은 설계도 없음 (기계 " + cfg().getInt("near", 10) + "칸 안에 오면 나옴)";
        long got = getLong(u, "got", 0);
        return where + " · 의뢰 " + o.id() + ": " + o.item() + " " + got + "/" + o.need() + " (" + o.machine() + " 에서 만듦)"
                + " · 다음: " + (state(u) == State.OFFERED ? "빈손으로 기계를 우클릭해 설계도를 읽고, " : "") + "남은 " + Math.max(0, o.need() - got)
                + "개를 자기 기계로 만들어 손에 들고 13번째 기계를 우클릭";
    }

    @Override
    public List<String> scenes() {
        return List.of("설계도", "납품", "의뢰목록");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "설계도" -> {
                Blueprint.Order o = live(u) ? order(u) : null;
                if (o == null) o = orders(cfg()).values().stream().findFirst().orElse(null);
                if (o == null) {
                    admin.sendMessage(Text.msg("<red>designer.yml 의 sequel.orders 가 비어 있어요"));
                    return true;
                }
                p.openBook(book(o, live(u) ? getLong(u, "got", 0) : 0));
            }
            case "납품" -> {
                Location at = d.machine13();
                breathe(p, at != null && within(p, at, 48) ? at.add(0.5, 1, 0.5) : p.getLocation().add(0, 1, 0), count(u));
            }
            case "의뢰목록" -> {
                List<String> mine = eligible(u);
                for (Blueprint.Order o : orders(cfg()).values()) {
                    admin.sendMessage(Text.msg("<gray>" + o.id() + " <white>" + o.item() + " x" + o.need() + " <gray>(" + o.machine() + ")"
                            + (!makeable(o) ? " <red>레시피 없음" : mine.contains(o.id()) ? " <green>" + p.getName() + " 에게 나올 수 있음" : " <dark_gray>직업 레벨 부족")));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
