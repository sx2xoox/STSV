package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The end (docs/admin/숨은_비밀/새로_온_사람.md "되돌리기", "다음 밤"). Once his three lines are back, from the next night on
 * he stands at his stone for everyone to see (a default-skinned figure named as the server names him; still not in the tab
 * list), every night until someone signs: a bare hand on the stone beside him, twice within ten seconds. Then the world
 * goes quiet, he turns to them, the server says — dated that day — that he solved a secret, he whispers a few last lines,
 * the name over his head becomes his own, and for the only time the server says that he left. The stone is whole
 * (the witness on its last line), a lamp that does not go out stays beside it, and the night after, at the hour he used
 * to come, everyone who ever spoke to him finds a letter in their mailbox.
 */
final class Vigil {

    static final NamespacedKey FIGURE = new NamespacedKey("economy", "newcomer_figure"), LANTERN = new NamespacedKey("economy", "newcomer_lantern");
    static final NamespacedKey LANTERN_MODEL = new NamespacedKey("economy", "model/everlasting_lantern");
    static final List<String> LAST = List.of("…그 공지, 그날 보고 싶었어요.", "…고마워요, {player}님.", "…저는 이제 나가 볼게요. 처음으로.");
    static final String ANNOUNCE = "<dark_gray>〔{date}〕 {name}님이 숨은 비밀을 풀었습니다.";
    private static final String MAIL = "newcomer";

    private final Newcomer n;
    private Mannequin figure;
    /** a figure only one player sees (a tester's night, an admin's 되돌리기) */
    private final Map<UUID, Mannequin> own = new ConcurrentHashMap<>();
    /** first click on the stone with a bare hand (ms) */
    private final Map<UUID, Long> asked = new ConcurrentHashMap<>();
    private Signing signing;
    private final Map<UUID, Signing> previews = new ConcurrentHashMap<>();
    /** letters the model is still writing: whose, and what came of asking */
    private final Map<UUID, Voice.Thought> writing = new LinkedHashMap<>();
    private int ticks;

    Vigil(Newcomer n) {
        this.n = n;
    }

    // ------------------------------------------------------------------------------------------
    // pure
    // ------------------------------------------------------------------------------------------

    /** The line everyone is told, dated the day he solved it ("〔2026-03-13〕 rmama님이 숨은 비밀을 풀었습니다."). */
    static String announcement(String template, Record.Truth truth) {
        String t = template == null || template.isBlank() ? ANNOUNCE : template;
        return t.replace("{date}", truth.date()).replace("{name}", truth.name()).replace("{what}", truth.what());
    }

    /** Where he stands: a little in front of his stone on the monument's side (straight north of it when that is unknown): {x, z}. */
    static double[] spot(double sx, double sz, double cx, double cz, double out) {
        double dx = cx - sx, dz = cz - sz, len = Math.hypot(dx, dz);
        if (len < 1e-6) return new double[]{sx, sz - out};
        return new double[]{sx + dx / len * out, sz + dz / len * out};
    }

    // ------------------------------------------------------------------------------------------
    // he stands there
    // ------------------------------------------------------------------------------------------

    private Optional<Location> standing() {
        Optional<Location> st = n.stone();
        if (st.isEmpty()) return Optional.empty();
        Location s = st.get(), c = n.centre().orElse(s);
        double[] at = spot(s.getX(), s.getZ(), c.getX(), c.getZ(), n.cfg().getDouble("vigil.out", 1.3));
        Location l = new Location(s.getWorld(), at[0], s.getY(), at[1]);
        Vector to = s.toVector().subtract(l.toVector()).setY(0);
        if (to.lengthSquared() > 1e-6) l.setDirection(to);
        l.setPitch(0);
        return Optional.of(l);
    }

    private String shownName(String name) {
        return "<gray>" + MiniMessage.miniMessage().escapeTags(name);
    }

    /** Whether he waits at his stone for everyone now: all back, a later night, its window open, his join line said. */
    boolean waitingNow() {
        if (!n.isOn() || n.module().closed(n) || n.impostorNow()) return false;
        Optional<LocalDate> night = n.openNight();
        if (night.isEmpty()) return false;
        String key = night.get().toString();
        return n.record(null).waiting(key) && key.equals(n.kv().get(Newcomer.JOINED));
    }

    void tick() {
        ticks++;
        boolean want = waitingNow() || (signing != null && !signing.preview);
        if (!want) {
            if (figure != null && signing == null) gone();
            if (figure == null && ticks % 30 == 0) strays();
        } else if (figure == null || !figure.isValid()) {
            Optional<Location> at = standing();
            if (at.isPresent() && at.get().getWorld().isChunkLoaded(at.get().getBlockX() >> 4, at.get().getBlockZ() >> 4)) {
                for (Entity e : at.get().getWorld().getNearbyEntities(at.get(), 12, 6, 12)) {
                    if (e instanceof Mannequin && e.getPersistentDataContainer().has(FIGURE, PersistentDataType.BYTE)) e.remove();
                }
                figure = at.get().getWorld().spawn(at.get(), Mannequin.class, e -> {
                    e.setInvulnerable(true);
                    e.setGravity(false);
                    e.setSilent(true);
                    e.setImmovable(true);
                    e.setCollidable(false);
                    e.setRemoveWhenFarAway(false);
                    e.setPersistent(false);
                    e.customName(Text.mm(shownName(n.who())));
                    e.setCustomNameVisible(true);
                    e.setDescription(null);
                    e.getPersistentDataContainer().set(FIGURE, PersistentDataType.BYTE, (byte) 1);
                });
                n.plugin().getLogger().info("[비밀] 새로 온 사람 (" + n.who() + ") 이 비석 앞에 서 있습니다 (모두에게 보임, 증인을 기다림)");
            }
        }
        asked.values().removeIf(t -> System.currentTimeMillis() - t > 10_000L);
        if (ticks % 60 == 0) lantern();
        if (ticks % 2 == 0) letters();
    }

    /** A figure left standing by a reload (it is not saved, but its chunk may never have unloaded) goes. */
    private void strays() {
        Optional<Location> st = n.stone();
        if (st.isEmpty() || !st.get().getWorld().isChunkLoaded(st.get().getBlockX() >> 4, st.get().getBlockZ() >> 4)) return;
        for (Entity e : st.get().getWorld().getNearbyEntities(st.get(), 12, 6, 12)) {
            if (e instanceof Mannequin && e.getPersistentDataContainer().has(FIGURE, PersistentDataType.BYTE)) e.remove();
        }
    }

    private void gone() {
        if (figure != null && figure.isValid()) figure.remove();
        figure = null;
    }

    /** Takes away what one player was shown (they left, the secret was switched off). */
    void cleanup(UUID u) {
        asked.remove(u);
        Signing s = previews.remove(u);
        if (s != null) s.stop();
        if (signing != null && signing.who.equals(u)) {
            signing.stop();
            signing = null;
        }
        Mannequin f = own.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (f != null) {
            if (p != null) n.module().haunt().remove(p, f);
            else if (f.isValid()) f.remove();
        }
    }

    void stop() {
        gone();
        if (signing != null) signing.stop();
        signing = null;
        for (UUID u : List.copyOf(own.keySet())) cleanup(u);
    }

    /** He stands there for one player alone (a tester whose own record is whole; an admin's 되돌리기). */
    Mannequin showTo(Player p) {
        Mannequin f = own.get(p.getUniqueId());
        if (f != null && f.isValid()) return f;
        Location at = standing().filter(l -> l.getWorld().equals(p.getWorld()) && l.distanceSquared(p.getLocation()) < 48 * 48).orElseGet(() -> {
            Location l = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
            l.setDirection(p.getLocation().toVector().subtract(l.toVector()).setY(0));
            l.setPitch(0);
            return l;
        });
        f = n.module().haunt().figure(p, at, null, shownName(n.who()));
        own.put(p.getUniqueId(), f);
        return f;
    }

    private Mannequin figureFor(Player p) {
        Mannequin f = own.get(p.getUniqueId());
        if (f != null && f.isValid()) return f;
        return figure != null && figure.isValid() && !n.module().test(p.getUniqueId()) ? figure : null;
    }

    // ------------------------------------------------------------------------------------------
    // signing
    // ------------------------------------------------------------------------------------------

    /** A bare hand on the stone: beside him, and once more within ten seconds, it is signed. */
    void sign(Player p) {
        UUID u = p.getUniqueId();
        boolean test = n.module().test(u);
        if (test && n.record(u).restored() && own.get(u) == null) showTo(p);   // a tester need not wait for the next night
        Mannequin f = figureFor(p);
        if (f == null || signing != null || previews.containsKey(u)) return;
        double near = n.cfg().getDouble("vigil.range", 5);
        if (!f.getWorld().equals(p.getWorld()) || f.getLocation().distanceSquared(p.getLocation()) > near * near) return;
        long now = System.currentTimeMillis();
        Long first = asked.get(u);
        if (first == null || now - first > 10_000L) {
            asked.put(u, now);
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_STONE_HIT, 0.5f, 0.6f);
            p.sendActionBar(Text.mm(n.cfg().getString("vigil.confirm", "<gray>…증인 <dark_gray>▒▒▒")));
            return;
        }
        asked.remove(u);
        begin(p, f, false);
    }

    /** An admin's 되돌리기: the whole end for that player alone, nothing saved. */
    void preview(Player p) {
        cleanup(p.getUniqueId());
        begin(p, showTo(p), true);
    }

    private void begin(Player p, Mannequin f, boolean preview) {
        Signing s = new Signing(p, f, preview);
        if (preview || s.alone) previews.put(p.getUniqueId(), s);
        else signing = s;
        s.task = Bukkit.getScheduler().runTaskTimer(n.plugin(), s, 2L, 2L);
    }

    boolean signingNow() {
        return signing != null;
    }

    /** One signing, played to its end; nothing is saved before the last step (the signer leaving stops it). */
    private final class Signing implements Runnable {
        final UUID who;
        final Mannequin f;
        /** an admin's look */
        final boolean preview;
        /** said to that player alone (a preview, a tester) */
        final boolean alone;
        final String hearer;
        final Voice.Thought extra;
        BukkitTask task;
        int t;

        Signing(Player p, Mannequin f, boolean preview) {
            this.who = p.getUniqueId();
            this.f = f;
            this.preview = preview;
            this.alone = preview || n.module().test(who);
            this.hearer = n.nameOf(p);
            this.extra = n.farewellWords(p);
        }

        void stop() {
            if (task != null) task.cancel();
        }

        private void tell(Player p, Component c) {
            if (alone) p.sendMessage(c);
            else n.toAll(c);
        }

        @Override
        public void run() {
            Player p = Bukkit.getPlayer(who);
            if (p == null || !f.isValid() || !n.isOn()) {   // they left him there: nothing was signed
                stop();
                if (signing == this) signing = null;
                previews.remove(who, this);
                return;
            }
            try {
                step(p);
            } catch (RuntimeException e) {
                n.plugin().getLogger().log(java.util.logging.Level.WARNING, "[비밀] newcomer 되돌리기 오류 (멈춤: 다시 서명하면 처음부터)", e);
                stop();
                if (signing == this) signing = null;
                previews.remove(who, this);
            }
            t += 2;
        }

        private void step(Player p) {
            List<String> last = n.lastLines();
            if (t == 0) {
                // the world goes quiet, and for the first time he looks at someone
                if (alone) p.stopAllSounds();
                else for (Player q : f.getWorld().getPlayers()) if (q.getLocation().distanceSquared(f.getLocation()) < 24 * 24) q.stopAllSounds();
                Location l = f.getLocation();
                Vector to = p.getLocation().toVector().subtract(l.toVector()).setY(0);
                if (to.lengthSquared() > 1e-6) l.setDirection(to);
                l.setPitch(0);
                f.teleport(l);
            } else if (t == 40) {
                tell(p, Text.msg(announcement(n.cfg().getString("announce"), n.record(alone ? who : null).truth())));
            } else if (t == 110) {
                p.sendMessage(n.incoming(last.get(0)));
            } else if (t == 190) {
                String more = extra.or("");
                if (!more.isBlank()) p.sendMessage(n.incoming(more));
            } else if (t == 260) {
                p.sendMessage(n.incoming(last.get(Math.min(1, last.size() - 1)).replace("{player}", MiniMessage.miniMessage().escapeTags(hearer))));
            } else if (t == 340) {
                p.sendMessage(n.incoming(last.get(last.size() - 1)));
            } else if (t == 400) {
                f.customName(Text.mm(shownName(n.record(alone ? who : null).truth().name())));
            } else if (t >= 450) {
                stop();
                finish(p);
            }
            if (t % 20 == 0 && t < 400) {
                if (alone) p.stopAllSounds();
                p.spawnParticle(Particle.FALLING_DUST, f.getLocation().add(0, 2.1, 0), 3, 0.2, 0.1, 0.2, 0, Material.GRAVEL.createBlockData());
            }
        }

        private void finish(Player p) {
            Record r = n.record(alone ? who : null);
            Component left = n.lookFor(r.truth().name()).joinLine(false);
            previews.remove(who, this);
            if (preview) {
                p.sendMessage(left);
                cleanup(who);
                return;
            }
            int others = 0;
            if (!alone) {
                double near = n.cfg().getDouble("vigil.range", 5);
                for (Player q : f.getWorld().getPlayers()) {
                    if (!q.getUniqueId().equals(who) && q.getLocation().distanceSquared(f.getLocation()) <= near * near) others++;
                }
            }
            tell(p, left);
            r.witness(who, hearer, others);
            n.memories().update(who, hearer, note -> note.witnessed().note("증인으로 서명함"));
            if (alone) {
                cleanup(who);
            } else {
                signing = null;
                gone();
                n.plugin().getLogger().info("[비밀] 새로 온 사람 (" + r.truth().name() + ") 이 나갔다고 모두에게 알렸습니다 (처음이자 마지막 · 증인 "
                        + p.getName() + " 외 " + others + "명)");
            }
            n.signed(p, alone);
            if (!alone) {
                lantern();
                prepare();
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // the lamp that stays
    // ------------------------------------------------------------------------------------------

    /** Beside his stone once it is signed (and gone again when an admin opens the secret anew). */
    void lantern() {
        Optional<Location> st = n.stone();
        if (st.isEmpty()) return;
        Location s = st.get();
        if (!s.getWorld().isChunkLoaded(s.getBlockX() >> 4, s.getBlockZ() >> 4)) return;
        boolean want = n.module().closed(n) && n.cfg().getBoolean("lantern.enabled", true);
        Location c = n.centre().orElse(s);
        // beside the stone, along the row (a quarter turn from the way it faces the monument)
        double dx = c.getX() - s.getX(), dz = c.getZ() - s.getZ(), len = Math.max(1e-6, Math.hypot(dx, dz));
        double side = n.cfg().getDouble("lantern.side", 0.55), out = n.cfg().getDouble("lantern.out", 0.75);
        Location at = new Location(s.getWorld(), s.getX() + dx / len * out - dz / len * side, s.getY(), s.getZ() + dz / len * out + dx / len * side);
        List<Entity> have = new ArrayList<>();
        for (Entity e : s.getWorld().getNearbyEntities(s, 20, 8, 20)) {
            if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(LANTERN, PersistentDataType.BYTE)) have.add(e);
        }
        int light = Math.clamp(n.cfg().getInt("lantern.light", 12), 0, 15);
        org.bukkit.block.Block b = at.getBlock().getRelative(0, 1, 0);
        if (!want) {
            for (Entity e : have) e.remove();
            if (!have.isEmpty() && b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
            return;
        }
        boolean there = false;
        for (Entity e : have) {
            if (!there && e.getLocation().distanceSquared(at) < 0.01) there = true;
            else e.remove();
        }
        if (there) return;
        boolean model = java.nio.file.Files.exists(n.eco().pack().dir().resolve("models/everlasting_lantern.bbmodel"));
        ItemStack shown = new ItemStack(model ? Material.PAPER : Material.LANTERN);
        if (model) shown.editMeta(meta -> meta.setItemModel(LANTERN_MODEL));
        s.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(true);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(0, model ? 0.5f : 0.3f, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            d.getPersistentDataContainer().set(LANTERN, PersistentDataType.BYTE, (byte) 1);
        });
        if (light > 0 && b.getType().isAir()) {
            b.setType(Material.LIGHT, false);
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Light l) {
                l.setLevel(light);
                b.setBlockData(l, false);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // the letters of the night after
    // ------------------------------------------------------------------------------------------

    private static final String LETTER = Newcomer.K + "letter.", DUE = Newcomer.K + "letters.due", SENT = Newcomer.K + "letters.sent";

    /** The letter someone would get as things stand (scripted): kind, then its lines. */
    List<String> scripted(UUID u, Memories.Note note) {
        Memories.Kind kind = Memories.kind(note);
        String name = note.name() == null || note.name().isBlank() ? "거기" : note.name();
        return Letters.whole(Letters.body(kind, name, n.module().get(n, u, "shard.detail", "")));
    }

    /** The letter kept for someone (written when he left), or as it would be written now. */
    List<String> letter(UUID u) {
        String saved = n.kv().get(LETTER + u);
        if (saved != null) return List.of(saved.split("\n", -1));
        return scripted(u, n.memories().of(u));
    }

    /** Just after he left: everyone's letter is written (scripted at once; the model's, where it writes one, replaces it). */
    void prepare() {
        var kv = n.kv();
        LocalDateTime now = Newcomer.nowKst();
        int[] w = n.window();
        LocalDate last = Schedule.night(now, w[0], w[1]).orElse(Schedule.next(now, w[0], w[1]).minusDays(1));
        kv.set(DUE, last.plusDays(1).toString());
        kv.remove(SENT);
        writing.clear();
        for (Map.Entry<UUID, Memories.Note> e : n.memories().all().entrySet()) {
            UUID u = e.getKey();
            Memories.Kind kind = Memories.kind(e.getValue());
            kv.set(LETTER + u, String.join("\n", scripted(u, e.getValue())));
            if (!Letters.blank(kind) && n.cfg().getBoolean("ai.letters", true)) writing.put(u, null);
        }
        n.plugin().getLogger().info("[비밀] 새로 온 사람: 편지 " + n.memories().all().size() + "통을 써 두었습니다 (" + kv.get(DUE) + " 밤, 그가 오던 시각에 우편함으로)");
    }

    /** One letter at a time from the model; then, when the hour has come, they all go out. */
    private void letters() {
        var kv = n.kv();
        if (!writing.isEmpty()) {
            Map.Entry<UUID, Voice.Thought> e = writing.entrySet().iterator().next();
            UUID u = e.getKey();
            if (e.getValue() == null) {
                Memories.Note note = n.memories().of(u);
                e.setValue(n.letterWords(u, note, String.join(" ", Letters.body(Memories.kind(note), note.name(), n.module().get(n, u, "shard.detail", "")))));
                if (!e.getValue().asked()) writing.remove(u);
            } else if (e.getValue().done()) {
                List<String> body = e.getValue().lines(3, 6);
                if (body != null && kv.get(LETTER + u) != null) kv.set(LETTER + u, String.join("\n", Letters.whole(body)));
                writing.remove(u);
            }
        }
        String due = kv.get(DUE);
        if (due == null || kv.has(SENT) || !n.module().closed(n)) return;
        int[] w = n.window();
        LocalDateTime at;
        try {
            at = Schedule.planned(LocalDate.parse(due), w[0], w[1], n.seed());
        } catch (RuntimeException ex) {
            return;
        }
        if (Newcomer.nowKst().isBefore(at) || n.eco().mail() == null) return;
        kv.set(SENT, System.currentTimeMillis());
        int sent = 0;
        for (UUID u : n.memories().all().keySet()) {
            deliver(u);
            sent++;
        }
        n.plugin().getLogger().info("[비밀] 새로 온 사람: 편지 " + sent + "통을 우편함에 넣었습니다");
    }

    /** The letter as an item: a sheet from him, its lines as the lore (the witness's: nothing but the postscript). */
    ItemStack item(UUID u) {
        List<String> lines = letter(u);
        boolean blank = lines.size() <= 1;
        String from = n.record(null).truth().name();
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(meta -> {
            meta.itemName(Text.item(blank ? "<#d8d2c4>빈 편지지" : "<#d8d2c4>편지"));
            List<Component> lore = new ArrayList<>();
            lore.add(Text.item("<dark_gray>보낸 사람 " + MiniMessage.miniMessage().escapeTags(from)));
            lore.add(Text.item(""));
            for (String l : lines) lore.add(Text.item(l.isEmpty() ? "" : "<gray>" + MiniMessage.miniMessage().escapeTags(l)));
            meta.lore(lore);
            meta.setMaxStackSize(1);
        });
        return it;
    }

    void deliver(UUID u) {
        if (n.eco().mail() == null) return;
        ItemStack it = item(u);
        n.eco().mail().store().send(u, Items.bytes(it), MAIL, System.currentTimeMillis());
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.sendMessage(dev.stsv.core.util.Lang.msg("mail.arrived", "item", Items.mmName(it)));
    }

    /** An admin opened the secret anew: no letters wait, the lamp goes. */
    void reset() {
        var kv = n.kv();
        kv.removePrefix(LETTER);
        kv.remove(DUE);
        kv.remove(SENT);
        writing.clear();
        lantern();
    }

    String lettersState() {
        var kv = n.kv();
        String due = kv.get(DUE);
        return due == null ? "아직 (풀리면 써 둠)" : kv.has(SENT) ? "보냄" : due + " 밤에 보냄" + (writing.isEmpty() ? "" : " (AI 가 쓰는 중 " + writing.size() + "통)");
    }
}
