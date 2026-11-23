package dev.stsv.economy.secret;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.SequelState.State;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * 후속 임무 (docs/admin/숨은_비밀.md "후속 임무"): once a secret is solved, its one solver gets a small private mission about
 * once a week that goes on with the secret's story, like the bus secret's weekly 비밀 의뢰. A secret returns one from
 * {@link Secret#sequel()} (always the same object); the module registers it as a listener and calls {@link #tick()}
 * each second while the secret is switched on.
 * <p>
 * This class does everything that is the same for all of them: who may play ({@link #may}), the week
 * ({@link #week}), the saved state ({@link SequelState}), expiry when the week turns, resuming after a join or a reload,
 * the reward and the admin command. An author writes the story in the hooks: {@link #offer}, {@link #tick(Player)},
 * {@link #resume}, {@link #cleanup}, {@link #expired}, {@link #start}, {@link #status}.
 * <p>
 * Settings are the {@code sequel:} section of the secret's own yml ({@link #cfg()}); {@code sequel.enabled} and
 * {@code sequel.reward} are read here. Test mode: a "week" lasts {@code sequel.test-week-seconds} of common.yml, chances
 * are 1, waits are short ({@link #seconds}), money is not paid; a tester who finished the secret in test mode plays too.
 */
public abstract class Sequel implements Listener {

    protected final Secret secret;
    protected final SecretModule m;
    protected final EcoPlugin eco;
    /** Players whose going mission has been shown since they joined (or since the reload): {@link #resume} ran. */
    private final Set<UUID> shown = new HashSet<>();

    protected Sequel(Secret secret) {
        this.secret = secret;
        this.m = secret.m;
        this.eco = secret.eco;
    }

    // ------------------------------------------------------------------------------------------
    // what an author writes
    // ------------------------------------------------------------------------------------------

    /** Korean name of the mission for admins and the console ("마지막 항해 일지"). */
    public abstract String id();

    /**
     * Called each second for a player who may play, while nothing was offered to them this week. Decide the moment (a place,
     * an hour, something they are doing) and the chance ({@link #chance}), begin the mission (give the note, show the figure,
     * save its notes with {@link #set}) and return true: the state becomes offered and the week is used up. Return false to
     * wait. A mission that begins from an event instead (a catch, a bus stop) calls {@link #offered(Player)} there and
     * simply returns false here.
     */
    protected abstract boolean offer(Player p);

    /** Called each second while this player's mission is offered or active ({@link #state}). */
    protected void tick(Player p) {}

    /** The player's mission is going on and they just joined (or the plugin reloaded, or the secret was switched on): show its things again. */
    protected void resume(Player p) {}

    /** Takes away what it shows this player and forgets in-memory state. Saved state stays. Called on quit, reload, 끄기, and before {@link #expired}. */
    protected void cleanup(UUID player) {}

    /**
     * This player's mission ended without an outcome: the week turned, an admin reset it, or they are no longer the solver.
     * Take back the mission's own items and give back anything of theirs it holds (the player may be offline: use the
     * mailbox then). The saved notes ({@link #get}) are still readable here and are deleted right after.
     */
    protected void expired(UUID player) {}

    /**
     * Admin 시작: begin the mission for this player now, whatever the week, hour, place or chance. The state is already
     * offered (anything going on before was stopped through {@link #expired}); set up what {@link #offer} would have.
     */
    protected abstract void start(Player p);

    /** One line for /관리자 비밀 후속 … 상태 (after the state, week and count): places, what is expected next. */
    public String status(UUID player) {
        return "";
    }

    /** Scene words for /관리자 비밀 후속 &lt;비밀&gt; 연출 &lt;장면&gt;. */
    public List<String> scenes() {
        return List.of();
    }

    /** Plays one scene on a player; false when the word is unknown. */
    public boolean scene(CommandSender admin, Player p, String word) {
        return false;
    }

    /** The secret's settings were (re)read. */
    protected void loaded() {}

    // ------------------------------------------------------------------------------------------
    // who and when
    // ------------------------------------------------------------------------------------------

    /** On: common.yml {@code sequel.enabled}, this secret's {@code sequel.enabled} and the secret itself (켜기/끄기). */
    public final boolean on() {
        return m.sequelOn() && secret.cfg.getBoolean("sequel.enabled", true) && m.enabled(secret);
    }

    /** The secret's real solver, when they are online and may play right now. */
    public final Optional<Player> player() {
        return m.solver(secret).map(Bukkit::getPlayer).filter(this::may);
    }

    /** Everyone online who may play right now: the solver, and testers (in test mode) who finished the secret in test mode. */
    public final List<Player> players() {
        List<Player> out = new ArrayList<>();
        if (!on()) return out;
        UUID solver = m.solver(secret).orElse(null);
        for (Player p : Bukkit.getOnlinePlayers()) if (may(p, solver)) out.add(p);
        return out;
    }

    /**
     * Whether this player may play now: it is on, they are not in the guide (체험관), and they are the solver, or a tester with
     * test mode on who finished the secret in test mode (or was let in by the admin 시작).
     */
    public final boolean may(Player p) {
        return p != null && on() && may(p, m.solver(secret).orElse(null));
    }

    private boolean may(Player p, UUID solver) {
        if (!p.isOnline() || (eco.guide() != null && eco.guide().inGuide(p))) return false;
        return member(p.getUniqueId(), solver);
    }

    /** The solver, or a tester (see {@link #may}); says nothing about being online or switched on. */
    public final boolean member(UUID u) {
        return member(u, m.solver(secret).orElse(null));
    }

    private boolean member(UUID u, UUID solver) {
        if (u.equals(solver)) return true;
        return m.test(u) && (m.stage(secret, u) > secret.stages() || m.get(secret, u, "sequel.tester") != null);
    }

    /** This player's week now: "2026-W48" (ISO week, Korean time), or a short test week in test mode. */
    public final String week(UUID u) {
        return SequelState.week(System.currentTimeMillis(), SecretModule.KST, m.test(u), m.sequelTestWeek());
    }

    /** Whether a mission may be offered to this player now: they may play, nothing is going on, none this week. */
    public final boolean due(Player p) {
        return may(p) && SequelState.due(store(p.getUniqueId()), week(p.getUniqueId()));
    }

    /** A chance from the settings ({@code sequel.<key>}, 0..1): always true in test mode. */
    protected final boolean chance(Player p, String key, double def) {
        return m.roll(p.getUniqueId(), cfg().getDouble(key, def));
    }

    /**
     * A wait in seconds from the settings ({@code sequel.<key>}). In test mode: {@code sequel.test-<key>} when it is there,
     * else a sixtieth of it (a minute becomes a second, at least 2).
     */
    protected final long seconds(UUID u, String key, long def) {
        ConfigurationSection c = cfg();
        long v = Math.max(0, c.getLong(key, def));
        if (!m.test(u)) return v;
        return c.contains("test-" + key) ? Math.max(0, c.getLong("test-" + key)) : Math.max(2, v / 60);
    }

    // ------------------------------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------------------------------

    /** The {@code sequel:} section of the secret's yml (never null). */
    protected final ConfigurationSection cfg() {
        ConfigurationSection c = secret.cfg.getConfigurationSection("sequel");
        return c != null ? c : new YamlConfiguration();
    }

    /** A line of the secret's yml ({@code sequel.<key>}) with a Java default; "{name}" pairs are replaced. */
    protected final String line(String key, String def, String... pairs) {
        String v = cfg().getString(key, def);
        for (int i = 0; i + 1 < pairs.length; i += 2) v = v.replace("{" + pairs[i] + "}", pairs[i + 1]);
        return v;
    }

    /** Sends a line of the secret's yml to the player (nothing when it is empty). */
    protected final void tell(Player p, String key, String def, String... pairs) {
        String v = line(key, def, pairs);
        if (!v.isEmpty()) p.sendMessage(Text.msg(v));
    }

    // ------------------------------------------------------------------------------------------
    // saved state
    // ------------------------------------------------------------------------------------------

    final SequelState.Store store(UUID u) {
        return new SequelState.Store() {
            @Override
            public String get(String key) {
                return m.get(secret, u, "sequel." + key);
            }

            @Override
            public void set(String key, String value) {
                m.set(secret, u, "sequel." + key, value);
            }

            @Override
            public void remove(String key) {
                m.remove(secret, u, "sequel." + key);
            }

            @Override
            public void removePrefix(String prefix) {
                eco.kv().removePrefix("secret." + secret.id() + "." + u + ".sequel." + prefix);
            }
        };
    }

    /** The saved state: NONE, OFFERED, ACTIVE, DONE, FAILED (done/failed stay until the week turns). */
    public final State state(UUID u) {
        return SequelState.state(store(u));
    }

    /** Whether this player's mission is going on (offered or active). */
    public final boolean live(UUID u) {
        return state(u).live();
    }

    public final int step(UUID u) {
        return SequelState.step(store(u));
    }

    /** Sets the step (and when it began: {@link #since}). */
    protected final void step(UUID u, int step) {
        SequelState.step(store(u), step, System.currentTimeMillis());
    }

    /** When the state or step last changed (ms). */
    public final long since(UUID u) {
        return SequelState.since(store(u));
    }

    /** Seconds since the state or step last changed. */
    public final long elapsed(UUID u) {
        return Math.max(0, System.currentTimeMillis() - since(u)) / 1000L;
    }

    /** How many missions this player completed overall. */
    public final long count(UUID u) {
        return SequelState.count(store(u));
    }

    /** A note of the going mission ({@code sequel.x.<key>}): deleted when the week turns, on a reset and when the next one begins. */
    protected final String get(UUID u, String key) {
        return m.get(secret, u, "sequel.x." + key);
    }

    protected final String get(UUID u, String key, String def) {
        return m.get(secret, u, "sequel.x." + key, def);
    }

    protected final long getLong(UUID u, String key, long def) {
        return m.getLong(secret, u, "sequel.x." + key, def);
    }

    protected final void set(UUID u, String key, Object value) {
        m.set(secret, u, "sequel.x." + key, value);
    }

    protected final void remove(UUID u, String key) {
        m.remove(secret, u, "sequel.x." + key);
    }

    /** A note that outlives the mission ({@code sequel.keep.<key>}: which story came last, …); only a reset deletes it. */
    protected final String kept(UUID u, String key, String def) {
        return m.get(secret, u, "sequel.keep." + key, def);
    }

    protected final void keep(UUID u, String key, Object value) {
        m.set(secret, u, "sequel.keep." + key, value);
    }

    // ------------------------------------------------------------------------------------------
    // beginning and ending
    // ------------------------------------------------------------------------------------------

    /**
     * A mission begun from an event (not from {@link #offer}): marks it offered for this week. False when the player may not
     * play or one was already offered this week, and then nothing must be given. Check before handing anything out:
     * {@code if (!offered(p)) return;}
     */
    protected final boolean offered(Player p) {
        if (!due(p)) return false;
        mark(p);
        return true;
    }

    private void mark(Player p) {
        UUID u = p.getUniqueId();
        if (SequelState.offer(store(u), week(u), System.currentTimeMillis())) {
            shown.add(u);
            eco.getLogger().info("[비밀] 후속 " + secret.name() + " · " + id() + ": " + p.getName() + " 에게 시작 (" + week(u) + ")");
        }
    }

    /** Offered → active: the player took it up. False when it was not in the offered state. */
    protected final boolean accept(Player p) {
        return SequelState.accept(store(p.getUniqueId()), System.currentTimeMillis());
    }

    /**
     * The mission is done: state done, count + 1, the reward of {@code sequel.reward} is paid, the player (only they) reads
     * {@code sequel.done}, the console gets a line. Returns what they received ("150,000 G"), or "" when no mission was going
     * on (nothing is paid twice). Take the mission's items back BEFORE calling this; your visuals are yours to remove.
     */
    protected final String complete(Player p) {
        UUID u = p.getUniqueId();
        if (!SequelState.finish(store(u), true, System.currentTimeMillis())) return "";
        String got = reward(p);
        String line = cfg().getString("done", m.sequelLine("done", "<dark_gray>…이번 주의 일은 끝났다. <gray>({reward})"));
        if (!line.isEmpty()) p.sendMessage(Text.msg(line.replace("{reward}", got.isEmpty() ? "-" : got)));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.6f);
        eco.getLogger().info("[비밀] 후속 " + secret.name() + " · " + id() + ": " + p.getName() + " 완료 (" + (got.isEmpty() ? "보상 없음" : plain(got))
                + ", " + count(u) + "번째)" + (m.test(u) ? " [테스트]" : ""));
        return got;
    }

    /**
     * The mission failed: state failed until the week turns (nothing is lost but this week's reward). The player reads the
     * secret's {@code sequel.<reasonLineKey>} when there is one, else {@code sequel.failed} (the secret's, else common.yml's).
     * False when no mission was going on. Take the mission's items back before calling this.
     */
    protected final boolean fail(Player p, String reasonLineKey) {
        UUID u = p.getUniqueId();
        if (!SequelState.finish(store(u), false, System.currentTimeMillis())) return false;
        ConfigurationSection c = cfg();
        String line = reasonLineKey != null && c.isString(reasonLineKey) ? c.getString(reasonLineKey, "")
                : c.getString("failed", m.sequelLine("failed", "<dark_gray>…이번 주는 여기까지다. 다음에 또 온다."));
        if (!line.isEmpty()) p.sendMessage(Text.msg(line));
        eco.getLogger().info("[비밀] 후속 " + secret.name() + " · " + id() + ": " + p.getName() + " 실패"
                + (reasonLineKey == null ? "" : " (" + reasonLineKey + ")") + (m.test(u) ? " [테스트]" : ""));
        return true;
    }

    /**
     * Pays {@code sequel.reward}: {@code money: [min, max]} (reason "secret_sequel"; not paid in test mode) and
     * {@code items: ["id amount"]}. Returns what was given, MiniMessage ("150,000 G, 낡은 단추"). {@link #complete} calls it.
     */
    protected final String reward(Player p) {
        ConfigurationSection r = cfg().getConfigurationSection("reward");
        if (r == null) return "";
        UUID u = p.getUniqueId();
        List<String> got = new ArrayList<>();
        long[] range = SequelState.range(r.get("money"));
        long money = SequelState.pick(range, ThreadLocalRandom.current().nextDouble(), Math.max(1, r.getLong("round", m.sequelRound())));
        if (money > 0) {
            if (m.test(u)) {
                got.add(String.format("%,d G <yellow>[테스트: 지급 안 함]</yellow>", money));
            } else {
                m.core().economy().deposit(u, money, "secret_sequel");
                got.add(String.format("%,d G", money));
            }
        }
        for (String line : r.getStringList("items")) {
            String[] w = line.trim().split("\\s+");
            if (w[0].isEmpty()) continue;
            int n = 1;
            try {
                if (w.length > 1) n = Math.max(1, Integer.parseInt(w[1]));
            } catch (NumberFormatException e) {
                eco.getLogger().warning("[비밀] 후속 " + secret.id() + " 보상 아이템 수량이 잘못됨: " + line);
            }
            Items.create(w[0], n).ifPresentOrElse(it -> {
                got.add(Items.mmName(it));
                give(p, it);
            }, () -> eco.getLogger().warning("[비밀] 후속 " + secret.id() + " 없는 아이템: " + w[0]));
        }
        return String.join("<gray>, </gray>", got);
    }

    /** Gives an item safely: the inventory, and what does not fit drops at their feet. */
    protected final void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** Gives an item to someone who may be offline: {@link #give} when online, else their mailbox. */
    protected final void give(UUID u, ItemStack it, String reason) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) give(p, it);
        else if (eco.mail() != null) eco.mail().deliver(u, it, reason);
    }

    private static String plain(String mm) {
        return mm.replaceAll("<[^>]*>", "");
    }

    // ------------------------------------------------------------------------------------------
    // the module's side
    // ------------------------------------------------------------------------------------------

    /** Each second (by the module, while the secret is switched on): expiry, resuming, {@link #offer} and {@link #tick(Player)}. */
    final void tick() {
        List<Player> players = players();
        if (!shown.isEmpty()) {
            // someone who was playing and no longer may (test mode off, the guide, the sequel switched off): their things go
            Set<UUID> now = new HashSet<>();
            for (Player p : players) now.add(p.getUniqueId());
            for (UUID u : List.copyOf(shown)) if (!now.contains(u)) quiet(u);
        }
        for (Player p : players) {
            try {
                tick1(p);
            } catch (RuntimeException e) {
                eco.getLogger().log(Level.WARNING, "[비밀] 후속 " + secret.id() + " 오류 (" + p.getName() + ")", e);
            }
        }
    }

    private void tick1(Player p) {
        UUID u = p.getUniqueId();
        SequelState.Store s = store(u);
        String week = week(u);
        if (SequelState.expiring(s, week)) {
            // the week turned on a going mission: its notes are still there for expired(), then everything of it goes
            quiet(u);
            expired(u);
            eco.getLogger().info("[비밀] 후속 " + secret.name() + " · " + id() + ": " + p.getName() + " 의 임무가 주가 바뀌어 사라짐");
        }
        SequelState.turn(s, week);
        State st = SequelState.state(s);
        if (st.live()) {
            if (shown.add(u)) resume(p);
            tick(p);
        } else if (SequelState.due(s, week)) {
            if (offer(p) && SequelState.state(s) == State.NONE) mark(p);
        }
    }

    /** Takes this player's visuals away (quit, reload, 끄기); the saved state stays and {@link #resume} runs again later. */
    final void quiet(UUID u) {
        shown.remove(u);
        try {
            cleanup(u);
        } catch (RuntimeException e) {
            eco.getLogger().log(Level.WARNING, "[비밀] 후속 " + secret.id() + " 정리 오류", e);
        }
    }

    /** Stops a going mission without an outcome ({@link #expired}); the week and the count stay. True when one was going on. */
    public final boolean stop(UUID u) {
        quiet(u);
        boolean live = live(u);
        if (live) {
            try {
                expired(u);
            } catch (RuntimeException e) {
                eco.getLogger().log(Level.WARNING, "[비밀] 후속 " + secret.id() + " 만료 정리 오류", e);
            }
        }
        SequelState.stop(store(u));
        return live;
    }

    /** Admin 초기화: stops a going mission ({@link #expired}) and forgets everything of the sequel for this player (count too). */
    public final void reset(UUID u) {
        stop(u);
        eco.kv().removePrefix("secret." + secret.id() + "." + u + ".sequel.");
    }

    /**
     * Admin 시작: begins it now for this player whatever the week or chance (a going one is stopped first). A tester (test
     * mode on) who has not finished the secret is let in. Returns a Korean reason when it cannot begin, else null.
     */
    public final String begin(Player p) {
        UUID u = p.getUniqueId();
        if (!on()) return "꺼져 있어요 (비밀 끄기, " + secret.id() + ".yml 의 sequel.enabled, 또는 common.yml 의 sequel.enabled)";
        if (eco.guide() != null && eco.guide().inGuide(p)) return "체험관 안에 있는 사람에게는 시작할 수 없어요";
        if (!member(u)) {
            if (!m.test(u)) return "이 비밀을 푼 사람이 아니에요 (확인만 하려면: /관리자 비밀 테스트 켜기 " + p.getName() + " 뒤 다시)";
            m.set(secret, u, "sequel.tester", 1);
        }
        stop(u);
        SequelState.offer(store(u), week(u), System.currentTimeMillis());
        shown.add(u);
        start(p);
        eco.getLogger().info("[비밀] 후속 " + secret.name() + " · " + id() + ": " + p.getName() + " 에게 관리자가 시작");
        return null;
    }

    /** The admin's status line for a player (online or not). */
    public final String describe(UUID u) {
        SequelState.Store s = store(u);
        String week = week(u);
        State st = SequelState.shown(s, week);
        String extra = status(u);
        return "<white>" + id() + " <gray>· " + (member(u) ? "" : "<red>대상 아님</red> · ") + "이번 주(" + week + ") <white>" + st.word()
                + (st.live() ? " <gray>단계 <white>" + SequelState.step(s) + " <gray>(" + elapsed(u) + "초째)" : "")
                + " <gray>· 마지막으로 받은 주 <white>" + Optional.ofNullable(s.get("week")).orElse("-")
                + " <gray>· 완료 <white>" + SequelState.count(s) + "번" + (extra == null || extra.isEmpty() ? "" : " <gray>· " + extra);
    }
}
