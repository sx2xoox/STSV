package dev.stsv.economy.secret.usher;

import dev.stsv.core.util.Text;
import dev.stsv.economy.guide.GuideLayout;
import dev.stsv.economy.guide.GuideModule;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState.State;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 후속 임무 「윤해담의 부탁」 (docs/admin/숨은_비밀/안내원.md "후속 임무"; usher.yml {@code sequel:}). 쉬움.
 * <p>
 * Once a week, when the one who found him talks to a 안내원 (any hour; a line more at dawn), he asks: one picture in the
 * 체험관 has gone wrong again. Talked to once more he lets them in — the same way in as the secret ({@link Usher#enter}:
 * {@code GuideModule.visit}, their things kept aside by the guide), and exactly one of the thirteen wrong pictures
 * ({@link Pictures}) is there, only for them. Clicked, he tells one line of his own memory tied to that picture (a new
 * one each week until all thirteen were heard, then round again), lets them out ({@code GuideModule.unvisit}) and, once
 * they are outside, the week's reward is paid (never inside: what is carried in the 체험관 does not come out).
 * <p>
 * Step 0: asked (offered), or let in and looking (active). Step 2: found, the reward waits for them to be outside.
 * Notes: station (this week's picture), week (the week it was asked in). Kept: heard (stations whose memory was told).
 * <p>
 * The framework does not tick a player inside the 체험관 (they are in the guide), so the picture is shown from
 * {@link #beat()}, which the secret calls each second.
 */
public final class UsherSequel extends Sequel {

    /** The one picture as one player sees it now. */
    private static final class Seen {
        Pictures.Picture pic;
        int frame, seconds;
    }

    private final Usher usher;
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();
    /** he is speaking to them: clicks wait */
    private final Set<UUID> talking = ConcurrentHashMap.newKeySet();
    /** being let out because their week ran out inside: once */
    private final Set<UUID> late = ConcurrentHashMap.newKeySet();
    private BukkitTask frames;

    UsherSequel(Usher usher) {
        super(usher);
        this.usher = usher;
    }

    @Override
    public String id() {
        return "윤해담의 부탁";
    }

    // ------------------------------------------------------------------------------------------
    // small things
    // ------------------------------------------------------------------------------------------

    /** The stations that have a wrong picture, in the layout's order. */
    static List<String> stations() {
        List<String> out = new ArrayList<>();
        for (GuideLayout.Station s : GuideLayout.stations()) if (Pictures.WHAT.containsKey(s.id())) out.add(s.id());
        return out;
    }

    private List<String> lines(String key, List<String> def) {
        List<String> l = cfg().getStringList(key);
        return l.isEmpty() ? def : l;
    }

    private static String title(String station) {
        GuideLayout.Station s = station == null ? null : GuideLayout.station(station);
        return s == null ? String.valueOf(station) : s.title();
    }

    /** His lines one by one (2.5 s apart), then next; clicks wait meanwhile. */
    private void say(Player p, List<String> all, Location from, Runnable next) {
        UUID u = p.getUniqueId();
        talking.add(u);
        for (int i = 0; i <= all.size(); i++) {
            int n = i;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                Player q = Bukkit.getPlayer(u);
                if (n < all.size()) {
                    if (q != null) usher.speak(q, usher.named(all.get(n)), from);
                    return;
                }
                talking.remove(u);
                if (q != null && next != null) next.run();
            }, 10L + i * 50L);
        }
    }

    /** Whether this player's mission is theirs to play in the 체험관 too (where {@link #may} is false: they are in the guide). */
    private boolean playing(UUID u) {
        return on() && member(u) && live(u) && week(u).equals(get(u, "week"));
    }

    // ------------------------------------------------------------------------------------------
    // talking to him
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        return false;   // it begins when they talk to him
    }

    /** Before the secret's and the guide's own handlers: the picture's click box, and the 안내원 for the one who found him. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Entity c = e.getRightClicked();
        if (c instanceof Interaction hit) {
            Seen s = seen.get(u);
            if (s == null || s.pic == null || !hit.equals(s.pic.answer)) return;
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) found(p, s);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || !c.getPersistentDataContainer().has(Usher.NPC)) return;
        if (!may(p) || p.isSneaking()) return;   // sneaking: the usual guide screen
        if (talking.contains(u)) {
            e.setCancelled(true);
            return;
        }
        State st = state(u);
        if (st == State.NONE) {
            if (!offered(p)) return;
            e.setCancelled(true);
            ask(p, c.getLocation());
        } else if (st.live() && step(u) < 2) {
            e.setCancelled(true);
            go(p, c.getLocation());
        }
    }

    /** He asks (the week's picture is decided now and kept). */
    private void ask(Player p, Location npc) {
        UUID u = p.getUniqueId();
        List<String> all = stations();
        String station = UsherAsk.pick(week(u), u, all, UsherAsk.heard(kept(u, "heard", "")));
        if (station == null) station = "jobs";
        set(u, "station", station);
        set(u, "week", week(u));
        List<String> say = new ArrayList<>(lines("ask", List.of("…아, 오셨네요.", "…체험관 그림이 또 하나 틀어졌어요.", "하나만요. 찾아 주실래요?")));
        if (m.hours(u, usher.cfgInt("hours.from", 1), usher.cfgInt("hours.to", 4)))
            say.addAll(lines("ask-dawn", List.of("…이 시간에는 저도 조금 흐려져요. 그래서 더 잘 틀어지나 봐요.")));
        say.addAll(lines("ask-end", List.of("다시 말을 걸어 주시면 데려다 드릴게요.")));
        say(p, say, npc, null);
    }

    /** Talked to again: in they go (their things kept aside by the guide, as in the secret). */
    private void go(Player p, Location npc) {
        accept(p);
        say(p, lines("go", List.of("…가요. 하나만 찾으면 돼요.")), npc,
                () -> usher.enter(p, line("arrive", "<gray>…체험관이다. 어딘가 하나가 틀어져 있다.")));
    }

    @Override
    protected void start(Player p) {
        cleanup(p.getUniqueId());
        ask(p, null);
    }

    // ------------------------------------------------------------------------------------------
    // the one picture
    // ------------------------------------------------------------------------------------------

    /** Each second, from the secret's own tick: this week's picture for those of them inside the 체험관. */
    void beat() {
        World gw = usher.guideWorld();
        if (gw == null || !on()) {
            for (UUID u : List.copyOf(seen.keySet())) hide(u);
            return;
        }
        for (UUID u : List.copyOf(seen.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.getWorld().equals(gw)) hide(u);
        }
        for (Player p : gw.getPlayers()) {
            UUID u = p.getUniqueId();
            try {
                if (!playing(u) || step(u) >= 2) {
                    hide(u);
                    // the week ran out while they were looking: nothing is left to find, so they are let out (once)
                    if (on() && member(u) && state(u) == State.ACTIVE && step(u) < 2 && get(u, "week") != null
                            && !talking.contains(u) && eco.guide() != null && eco.guide().visiting(p) && late.add(u)) {
                        m.haunt().whisper(p, line("late", "<gray>…그림이 제자리로 돌아가 있다. 이번 주는 지나갔다."));
                        leave(u);
                    }
                } else show(p, gw);
            } catch (RuntimeException e) {
                eco.getLogger().warning("[비밀] 후속 안내원 " + p.getName() + ": " + e);
            }
        }
        if (seen.isEmpty()) {
            if (frames != null) frames.cancel();
            frames = null;
        } else if (frames == null || frames.isCancelled()) {
            frames = Bukkit.getScheduler().runTaskTimer(eco, this::frame, 2L, 2L);
        }
    }

    private void show(Player p, World gw) {
        UUID u = p.getUniqueId();
        GuideLayout.Station s = GuideLayout.station(get(u, "station", ""));
        if (s == null) return;
        Seen x = seen.computeIfAbsent(u, k -> new Seen());
        double[] mid = s.point(0, s.depth() / 2.0);
        boolean near = Math.hypot(p.getX() - mid[0], p.getZ() - mid[1]) < 70;
        if (near && (x.pic == null || !x.pic.props.intact())) {
            if (x.pic != null) x.pic.props.clear();
            x.pic = usher.pictures().build(p, s, gw);
        } else if (!near && x.pic != null) {
            x.pic.props.clear();
            x.pic = null;
        }
        // looking a long while: he says roughly where
        long after = seconds(u, "hint-after", 300);
        if (++x.seconds == after && after > 0)
            m.haunt().whisper(p, line("hint", "<gray>…{station} 쪽이었던 것 같아요.", "station", title(s.id())));
    }

    private void hide(UUID u) {
        Seen x = seen.remove(u);
        if (x != null && x.pic != null) x.pic.props.clear();
    }

    /** Every 2 ticks while a picture is shown: the pictures that move. */
    private void frame() {
        for (Map.Entry<UUID, Seen> en : seen.entrySet()) {
            Seen x = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || x.pic == null || x.pic.frame == null) continue;
            try {
                x.pic.frame.run(p, ++x.frame);
            } catch (RuntimeException e) {
                eco.getLogger().warning("[비밀] 후속 안내원 " + p.getName() + ": " + e);
            }
        }
    }

    /** They pointed at it: the picture goes, he tells its memory, and they are let out. The reward waits outside. */
    private void found(Player p, Seen x) {
        UUID u = p.getUniqueId();
        if (!playing(u) || step(u) >= 2) return;
        String station = x.pic.station;
        Location at = x.pic.answer.getLocation();
        hide(u);
        step(u, 2);
        keep(u, "heard", UsherAsk.addHeard(kept(u, "heard", ""), station, stations()));
        p.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.AMBIENT, 0.7f, 0.5f);
        m.haunt().behind(p, Sound.BLOCK_STONE_STEP, 0.5f, 0.8f);
        m.haunt().whisper(p, line("found", "<gray>…거기였네요."));
        List<String> say = new ArrayList<>();
        say.add(memory(station));
        say.addAll(lines("thanks", List.of("고마워요. 이번 주는 괜찮을 것 같아요.")));
        say(p, say, null, () -> leave(u));
    }

    /** The memory tied to a picture ({@code sequel.memories.<station>}). */
    private String memory(String station) {
        return line("memories." + station, "…그것도, 제가 기억하던 거였어요.");
    }

    /** Out again, the same way the secret lets a visitor out (nothing for someone who is in a guide topic). */
    private void leave(UUID u) {
        Player q = Bukkit.getPlayer(u);
        GuideModule g = eco.guide();
        if (q == null || g == null || !g.visiting(q)) return;
        m.haunt().black(q, 2);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player r = Bukkit.getPlayer(u);
            if (r == null || !g.visiting(r)) return;
            tell(r, "goodbye", "<dark_gray>멀리서 안내원이 손을 흔든다.");
            g.unvisit(r, true);
        }, 20L);
    }

    /** Outside the 체험관 only (the framework does not call this inside): the reward of a picture found. */
    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        if (step(u) >= 2 && elapsed(u) >= 2 && !talking.contains(u)) complete(p);
    }

    @Override
    protected void cleanup(UUID u) {
        hide(u);
        talking.remove(u);
        late.remove(u);
        if (seen.isEmpty() && frames != null) {   // a reload leaves no task behind
            frames.cancel();
            frames = null;
        }
    }

    /**
     * The mission ended without an outcome. Nothing of theirs is held by it (the guide keeps their things while they are
     * inside and hands them back on the way out), so there is nothing to give back. Two things only: a picture already
     * found whose reward was still waiting for them to come out when the week turned is paid now (outside), and someone
     * still inside looking (an admin reset) is let out.
     */
    @Override
    protected void expired(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;
        if (step(u) >= 2) {
            String asked = get(u, "week");
            if (asked != null && !asked.equals(week(u)) && may(p)) complete(p);
            return;
        }
        GuideModule g = eco.guide();
        if (state(u) == State.ACTIVE && g != null && g.visiting(p)) leave(u);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public String status(UUID u) {
        List<String> all = stations();
        String heard = "들은 기억 " + UsherAsk.count(kept(u, "heard", ""), all) + "/" + all.size();
        if (!live(u)) return heard + " · 다음: 다음 주에 안내원에게 말 걸기 (웅크리고 누르면 평소 화면)";
        String st = get(u, "station", "?");
        String what = "이번 주 그림 <white>" + title(st) + "</white> (" + st + ": " + Pictures.WHAT.getOrDefault(st, "?") + ")";
        String next = step(u) >= 2 ? "찾음 → 체험관 밖으로 나오면 보상"
                : state(u) == State.OFFERED ? "안내원에게 다시 말 걸기 (체험관으로 들어감)"
                : seen.containsKey(u) ? "체험관 안: 그 그림을 우클릭" : "안내원에게 말 걸어 체험관에 들어가 그 그림을 우클릭";
        return what + " · " + heard + " · 다음: " + next;
    }

    @Override
    public List<String> scenes() {
        return List.of("부탁", "새벽", "힌트", "기억", "배웅");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        String station = get(u, "station");
        if (station == null) station = UsherAsk.pick(week(u), u, stations(), UsherAsk.heard(kept(u, "heard", "")));
        switch (word) {
            case "부탁" -> {
                List<String> say = new ArrayList<>(lines("ask", List.of("…체험관 그림이 또 하나 틀어졌어요.")));
                say.addAll(lines("ask-end", List.of()));
                say(p, say, null, null);
            }
            case "새벽" -> say(p, lines("ask-dawn", List.of("…이 시간에는 저도 조금 흐려져요.")), null, null);
            case "힌트" -> m.haunt().whisper(p, line("hint", "<gray>…{station} 쪽이었던 것 같아요.", "station", title(station)));
            case "기억" -> {
                List<String> say = new ArrayList<>(List.of(memory(station)));
                say.addAll(lines("thanks", List.of()));
                say(p, say, null, null);
                admin.sendMessage(Text.mm("<gray>[연출] 기억: " + title(station) + " (" + station + ")"));
            }
            case "배웅" -> tell(p, "goodbye", "<dark_gray>멀리서 안내원이 손을 흔든다.");
            default -> {
                return false;
            }
        }
        return true;
    }
}
