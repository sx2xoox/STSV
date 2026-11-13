package dev.stsv.core.emote;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.emote.EmoteReturn.End;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 감정표현 (/감정표현, admin test feature): emotes the game has no animation for, played by a jointed puppet that
 * wears the player's skin. The server cannot pose a player's own joints, so while an emote plays the player is put
 * aside and a puppet of text displays ({@link Puppet}, {@link PuppetModel}) stands where they stood, facing where
 * their body faced, for everyone near to see. The emotes are data: contents/&lt;pack&gt;/emotes.yml
 * ({@link EmoteSpec}), README "감정표현".
 *
 * <p>The emoting player, the way the wardrobe does it ({@link dev.stsv.core.cosmetic.CosmeticModule}): a spectator
 * riding a camera (a display entity only they see) that looks at the puppet from a place walls do not block. A
 * spectator cannot be hurt, pushed or seen by players, so nothing else is changed on them: no invisibility effect,
 * no hiding from the tab list. What is taken is written on the player first ({@link EmoteReturn}) and given back
 * by {@link #finish} whatever ends the emote: sneaking, /감정표현 그만, its end or the time limit, quitting, dying,
 * being moved or put in another game mode by something else, the plugin going down; a crash is undone when the
 * player is next seen ({@link #restoreIfStuck}).
 */
public final class EmoteModule implements StsvModule, Listener, TabExecutor {

    public static final String PERMISSION = "stsv.emote";
    /** Words that stop an emote (/감정표현 그만) and that list them. */
    private static final Set<String> STOP = Set.of("그만", "중지", "stop", "off"), LIST = Set.of("목록", "list");

    private STSVPlugin plugin;
    private NamespacedKey keyEntity, keyReturn;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastEnd = new HashMap<>(), lastHurt = new HashMap<>();
    /** players waiting for their skin (one emote at a time) */
    private final Set<UUID> loading = new HashSet<>();
    /** when each player last opened the buttons with the quick key (ms) */
    private final Map<UUID, Long> lastQuick = new HashMap<>();
    /** by id, in the order of the files */
    private Map<String, EmoteSpec> emotes = Map.of();
    private SkinCache skins;
    private BukkitTask ticker;

    private static final class Session {
        EmoteSpec spec;
        EmoteReturn saved;
        Location origin;
        Puppet puppet;
        ItemDisplay camera;
        /** ticks since the emote began, and the emote's own clock (it starts again when the emote is changed) */
        long age, tick;
        /** the camera has been taken (two ticks after the start, when the client knows the camera entity) */
        boolean attached;
        /** above 0 while this module itself moves the player or changes their game mode */
        int ours;
    }

    @Override
    public String id() {
        return "emotes";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keyEntity = new NamespacedKey(plugin, "emote_entity");
        keyReturn = new NamespacedKey(plugin, "emote_return");
        skins = new SkinCache();
        // leftovers of a reload (the displays are not saved, so a crash leaves none; be safe)
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(keyEntity)) e.remove();
        }
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) restoreIfStuck(p);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) finish(p, End.DISABLE, null);
        }
        sessions.clear();
        if (ticker != null) ticker.cancel();
        ticker = null;
        if (skins != null) skins.close();
        HandlerList.unregisterAll(this);
    }

    /** Reads every pack's emotes.yml (a later pack's emote replaces an earlier one of the same id). */
    private void load() {
        Map<String, EmoteSpec> all = new LinkedHashMap<>();
        for (ContentPack pack : plugin.content().packs()) {
            Path f = pack.dir().resolve("emotes.yml");
            if (!Files.isRegularFile(f)) continue;
            all.putAll(EmoteSpec.parse(YamlConfiguration.loadConfiguration(f.toFile()),
                    problem -> plugin.getLogger().warning("[감정표현] " + pack.id() + "/emotes.yml: " + problem)));
        }
        emotes = java.util.Collections.unmodifiableMap(all);
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    public boolean enabled() {
        return plugin.getConfig().getBoolean("emotes.enabled");
    }

    /** The pack files (none when the feature is off). */
    public Map<String, byte[]> packFiles() {
        return enabled() ? EmotePack.files() : Map.of();
    }

    public boolean emoting(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /** An emote by the name players type or its id. */
    Optional<EmoteSpec> find(String word) {
        String w = word.trim().toLowerCase(Locale.ROOT);
        for (EmoteSpec e : emotes.values()) {
            if (e.id().equals(w) || e.name().toLowerCase(Locale.ROOT).equals(w)) return Optional.of(e);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------
    // /감정표현
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (!enabled()) {
            p.sendMessage(Lang.msg("emote.disabled"));
            return true;
        }
        String word = String.join(" ", args).trim();
        if (word.isEmpty() && !emotes.isEmpty()) {
            EmoteMenu.open(p, emotes.values(), emoting(p));          // the buttons: one click an emote
        } else if (word.isEmpty() || LIST.contains(word.toLowerCase(Locale.ROOT))) {
            List<String> names = emotes.values().stream().map(e -> Lang.raw("emote.list-entry", "name", e.name())).toList();
            p.sendMessage(Lang.msg("emote.list", "emotes", names.isEmpty() ? Lang.raw("common.none") : String.join(Lang.raw("emote.list-separator"), names)));
            p.sendMessage(Lang.msg("emote.usage"));
        } else if (STOP.contains(word.toLowerCase(Locale.ROOT))) {
            if (emoting(p)) finish(p, End.COMMAND, null);
            else p.sendMessage(Lang.msg("emote.not-playing"));
        } else {
            Optional<EmoteSpec> emote = find(word);
            if (emote.isEmpty()) p.sendMessage(Lang.msg("emote.unknown", "name", word));
            else play(p, emote.get());
        }
        return true;
    }

    /**
     * The quick key: Ctrl + Shift (sprint and sneak together) while standing still opens the emote buttons, for those
     * who may use emotes ({@code emotes.quick-key}). Not while riding, not while an emote plays (sneaking ends that).
     */
    @EventHandler
    public void onQuickKey(org.bukkit.event.player.PlayerInputEvent e) {
        org.bukkit.Input in = e.getInput();
        if (!EmoteMenu.quickKey(in.isSneak(), in.isSprint(), in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump())) return;
        Player p = e.getPlayer();
        if (!enabled() || emotes.isEmpty() || !plugin.getConfig().getBoolean("emotes.quick-key")) return;
        if (!p.hasPermission(PERMISSION) || emoting(p) || p.isInsideVehicle() || p.getGameMode() == GameMode.SPECTATOR) return;
        long now = System.currentTimeMillis();
        Long last = lastQuick.put(p.getUniqueId(), now);
        if (last != null && now - last < EmoteMenu.QUICK_KEY_GAP_MS) return;
        EmoteMenu.open(p, emotes.values(), false);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !enabled()) return List.of();
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (EmoteSpec e : emotes.values()) out.add(e.name());
        out.add("그만");
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------------------------------

    /** Plays an emote for a player if they may now (says why not otherwise); changes it if one is playing. */
    public void play(Player p, EmoteSpec spec) {
        Session playing = sessions.get(p.getUniqueId());
        if (playing != null) {
            playing.spec = spec;
            playing.tick = 0;
            playing.puppet.props(spec.props(), spec.poseAt(0), spec.planted());
            playing.puppet.pose(spec.poseAt(0), spec.planted(), 4);
            return;
        }
        if (loading.contains(p.getUniqueId())) return;
        long wait = EmoteRules.cooldownLeft(lastEnd.getOrDefault(p.getUniqueId(), 0L), System.currentTimeMillis(),
                plugin.getConfig().getInt("emotes.cooldown-seconds"));
        if (wait > 0) {
            p.sendMessage(Lang.msg("emote.cooldown", "seconds", wait));
            return;
        }
        String refusal = EmoteRules.refusal(situation(p));
        if (refusal != null) {
            p.sendMessage(Lang.msg(refusal));
            return;
        }
        PlayerTextures textures = p.getPlayerProfile().getTextures();
        String url = textures.getSkin() == null ? null : textures.getSkin().toString();
        boolean slim = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        Optional<PuppetSkin> have = skins.cached(url, slim);
        if (have.isPresent()) {
            begin(p, spec, have.get());
            return;
        }
        // the skin comes from the game's texture host, off the server thread; then back here
        loading.add(p.getUniqueId());
        skins.skin(url, slim).thenAccept(skin -> {
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> started(p, spec, skin));
        });
    }

    /** The skin has come (or not): the emote starts if the player still may. */
    private void started(Player p, EmoteSpec spec, Optional<PuppetSkin> skin) {
        loading.remove(p.getUniqueId());
        if (!p.isOnline() || emoting(p) || !enabled()) return;
        String refusal = EmoteRules.refusal(situation(p));
        if (refusal != null) {
            p.sendMessage(Lang.msg(refusal));
            return;
        }
        if (skin.isEmpty()) p.sendMessage(Lang.msg("emote.no-skin"));
        begin(p, spec, skin.orElseGet(PuppetSkin::placeholder));
    }

    private EmoteRules.Situation situation(Player p) {
        @SuppressWarnings("deprecation") boolean ground = p.isOnGround();
        Long hurt = lastHurt.get(p.getUniqueId());
        boolean busy = (plugin.cosmetics() != null && plugin.cosmetics().inWardrobe(p)) || (plugin.hud() != null && plugin.hud().editing(p));
        return new EmoteRules.Situation(p.isDead(), p.isInsideVehicle(), p.getGameMode() == GameMode.SPECTATOR, busy,
                p.isGliding() || p.isSleeping() || p.isSwimming() || p.isRiptiding(), p.isInWater() || p.isInLava(),
                p.getFireTicks() > 0, ground, p.getFallDistance(), p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY),
                hurt == null ? -1 : System.currentTimeMillis() - hurt);
    }

    private void begin(Player p, EmoteSpec spec, PuppetSkin skin) {
        Location feet = p.getLocation().clone();
        float bodyYaw = p.getBodyYaw();
        Location aim = feet.clone().add(0, EmoteRules.AIM_HEIGHT, 0);
        double reach = spec.camera().distance() + EmoteRules.WALL_MARGIN;
        Optional<EmoteRules.CameraSpot> spot = EmoteRules.camera(spec.camera(), bodyYaw, dir -> {
            RayTraceResult hit = aim.getWorld().rayTraceBlocks(aim, new Vector(dir.x, dir.y, dir.z), reach, FluidCollisionMode.NEVER, true);
            return hit == null ? reach : hit.getHitPosition().distance(aim.toVector());
        });
        if (spot.isEmpty()) {
            p.sendMessage(Lang.msg("emote.no-room"));
            return;
        }
        Location cam = aim.clone().add(spot.get().offset().x, spot.get().offset().y, spot.get().offset().z);
        cam.setDirection(aim.toVector().subtract(cam.toVector()));

        Session s = new Session();
        s.spec = spec;
        s.origin = feet;
        s.saved = new EmoteReturn(feet.getWorld().getUID().toString(), feet.getX(), feet.getY(), feet.getZ(), feet.getYaw(), feet.getPitch(),
                p.getGameMode(), p.getAllowFlight(), p.isFlying(), p.isInvisible());
        p.getPersistentDataContainer().set(keyReturn, PersistentDataType.STRING, s.saved.encode());
        // whoever cannot see the player now (hidden by another plugin) does not get to see their puppet either
        Set<UUID> never = new HashSet<>();
        for (Player v : Bukkit.getOnlinePlayers()) if (v != p && !v.canSee(p)) never.add(v.getUniqueId());
        s.puppet = new Puppet(plugin, keyEntity, feet, bodyYaw, skin, spec.poseAt(0), spec.planted(), spec.props(), never);
        s.camera = cam.getWorld().spawn(cam, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(keyEntity, PersistentDataType.BYTE, (byte) 1);
        });
        p.showEntity(plugin, s.camera);
        sessions.put(p.getUniqueId(), s);
        s.puppet.showTo(Bukkit.getOnlinePlayers(), plugin.getConfig().getInt("emotes.view-distance"));

        s.ours++;
        try {
            p.setGameMode(GameMode.SPECTATOR);
            if (p.getGameMode() == GameMode.SPECTATOR) p.teleport(cam);
        } finally {
            s.ours--;
        }
        if (p.getGameMode() != GameMode.SPECTATOR) {
            finish(p, End.FAILED, null);   // another plugin refused the game mode
            return;
        }
        // the camera a little later: the client must know the camera entity first (as the wardrobe does)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(p.getUniqueId()) != s) return;
            s.ours++;
            try {
                if (s.camera.isValid() && p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(s.camera);
            } finally {
                s.ours--;
            }
            if (p.getSpectatorTarget() != s.camera) {
                finish(p, End.LOST, null);
                return;
            }
            s.attached = true;
            dev.stsv.core.hud.HudModule.flash(p, Lang.mm("emote.hint", "emote", spec.name()));
        }, 2L);
    }

    // ------------------------------------------------------------------------------------------
    // Playing
    // ------------------------------------------------------------------------------------------

    private void tick() {
        if (sessions.isEmpty()) return;
        long limit = EmoteRules.maxTicks(plugin.getConfig().getInt("emotes.max-seconds"));
        int view = plugin.getConfig().getInt("emotes.view-distance");
        for (Map.Entry<UUID, Session> en : new ArrayList<>(sessions.entrySet())) {
            Session s = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (sessions.get(en.getKey()) != s) continue;
            if (p == null) {
                // gone without a quit event (should not happen): at least take the puppet away
                sessions.remove(en.getKey());
                discard(s);
                continue;
            }
            s.age++;
            if (!s.puppet.valid() || (s.attached && (!s.camera.isValid() || p.getGameMode() != GameMode.SPECTATOR || p.getSpectatorTarget() != s.camera))) {
                finish(p, End.LOST, null);
                continue;
            }
            if (s.age >= limit) {
                finish(p, End.TIME_UP, null);
                continue;
            }
            if (s.age % s.spec.step() == 0) {
                // a played-once emote ends a step after its last pose was sent: the clients have reached it then
                if (s.spec.finished(s.tick)) {
                    finish(p, End.FINISHED, null);
                    continue;
                }
                s.tick += s.spec.step();
                s.puppet.pose(s.spec.poseAt(s.tick), s.spec.planted(), s.spec.step());
            }
            if (s.age % 20 == 0) s.puppet.showTo(Bukkit.getOnlinePlayers(), view);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ending
    // ------------------------------------------------------------------------------------------

    private void discard(Session s) {
        if (s.puppet != null) s.puppet.remove();
        if (s.camera != null) s.camera.remove();
    }

    /**
     * Ends a player's emote and gives everything back ({@link EmoteReturn#plan}): off the camera, the game mode,
     * the place they stood (unless the ending says they stay), their flight; the puppet and the camera go. Safe to
     * call at any time and more than once: the session is taken out first, so nothing this does comes back here.
     *
     * @param other for {@link End#GAME_MODE}: the game mode something else gave them
     */
    private void finish(Player p, End end, GameMode other) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        EmoteReturn.Plan plan = s.saved.plan(end, other);
        // every step on its own: whatever goes wrong in one, the others still happen
        step(p, "카메라에서 내리기", () -> {
            if (p.getSpectatorTarget() == null) return;
            // only a spectator can leave what they ride (something else may have changed the mode meanwhile)
            if (p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.SPECTATOR);
            if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
        });
        step(p, "게임 모드 되돌리기", () -> p.setGameMode(plan.gameMode()));
        step(p, "인형 치우기", () -> discard(s));
        if (plan.back()) {
            step(p, "제자리로 돌려보내기", () -> {
                if (p.teleport(s.origin) || end == End.QUIT || end == End.DISABLE) return;
                // refused by something: once more on the next tick
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (p.isOnline() && !emoting(p)) p.teleport(s.origin);
                });
            });
        }
        step(p, "비행 상태 되돌리기", () -> restoreAbilities(p, plan));
        lastEnd.put(p.getUniqueId(), System.currentTimeMillis());
        if (p.getGameMode() == GameMode.SPECTATOR && plan.gameMode() != GameMode.SPECTATOR) {
            // the game mode did not come back (refused by another plugin?): the tag stays, so the next join or
            // /관리자 리로드 tries again (restoreIfStuck)
            plugin.getLogger().warning("[감정표현] " + p.getName() + " 의 게임 모드를 " + plan.gameMode() + " 로 되돌리지 못했습니다. 다음 접속 때 다시 시도합니다.");
        } else {
            p.getPersistentDataContainer().remove(keyReturn);
        }
        if (end == End.QUIT || end == End.DISABLE) return;
        if (plan.message() != null && p.isOnline()) p.sendMessage(Lang.msg(plan.message()));
        // again a little later: the game works out visibility on its own tick; the cosmetics come back on theirs
        for (long delay : new long[]{1, 10}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || emoting(p)) return;
                if (plan.visible()) makeVisible(p);
                if (plugin.cosmetics() != null && !plugin.cosmetics().inWardrobe(p)) plugin.cosmetics().refresh(p);
            }, delay);
        }
    }

    private void step(Player p, String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[감정표현] " + p.getName() + ": " + what + " 실패", e);
        }
    }

    private void restoreAbilities(Player p, EmoteReturn.Plan plan) {
        p.setAllowFlight(plan.allowFlight());
        if (plan.allowFlight()) p.setFlying(plan.flying());
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0, 0));
        if (plan.visible()) makeVisible(p);
    }

    /** Spectator mode sets the invisible flag; it must not outlive the emote on a player who was not invisible. */
    private static void makeVisible(Player p) {
        if (p.getGameMode() != GameMode.SPECTATOR && !p.hasPotionEffect(PotionEffectType.INVISIBILITY) && p.isInvisible()) p.setInvisible(false);
    }

    /**
     * A player who still carries the tag of an emote that never ended (a crash, a killed server): back to where
     * they stood, in their game mode. A tag that cannot be read only makes sure they are no spectator.
     */
    private void restoreIfStuck(Player p) {
        String raw = p.getPersistentDataContainer().get(keyReturn, PersistentDataType.STRING);
        if (raw == null || emoting(p)) return;
        p.getPersistentDataContainer().remove(keyReturn);
        Optional<EmoteReturn> saved = EmoteReturn.decode(raw);
        if (saved.isEmpty()) {
            GameMode mode = EmoteReturn.fallbackMode(p.getGameMode(), Bukkit.getDefaultGameMode());
            if (mode != p.getGameMode()) p.setGameMode(mode);
            return;
        }
        EmoteReturn r = saved.get();
        EmoteReturn.Plan plan = r.plan(End.DISABLE, null);
        step(p, "카메라에서 내리기", () -> {
            if (p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
        });
        step(p, "게임 모드 되돌리기", () -> p.setGameMode(plan.gameMode()));
        step(p, "제자리로 돌려보내기", () -> {
            World w = worldOf(r);
            if (w != null) p.teleport(new Location(w, r.x(), r.y(), r.z(), r.yaw(), r.pitch()));
        });
        step(p, "비행 상태 되돌리기", () -> restoreAbilities(p, plan));
        plugin.getLogger().info("[감정표현] " + p.getName() + ": 끝나지 못한 감정표현(서버 중단)에서 되돌렸습니다.");
    }

    private static World worldOf(EmoteReturn r) {
        try {
            return Bukkit.getWorld(UUID.fromString(r.world()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // What ends an emote, what must not happen during one
    // ------------------------------------------------------------------------------------------

    /** The session of a player unless this module itself is acting on them right now. */
    private Session foreign(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s == null || s.ours > 0 ? null : s;
    }

    private void finishLater(Player p, End end) {
        Bukkit.getScheduler().runTask(plugin, () -> finish(p, end, null));
    }

    /**
     * Sneaking takes a spectator off what they ride: that is how an emote is left. The camera is kept for this
     * tick (the event is cancelled) and everything is given back on the next, outside the game's own handling.
     */
    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        if (foreign(e.getPlayer()) == null) return;
        e.setCancelled(true);
        finishLater(e.getPlayer(), End.SNEAK);
    }

    /** A spectator's click on an entity would put them inside it: never anything but the camera. */
    @EventHandler
    public void onStartSpectating(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && e.getNewSpectatorTarget() != s.camera) e.setCancelled(true);
    }

    /** Sneaking before the camera is taken (the first two ticks). */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Session s = foreign(e.getPlayer());
        if (s != null && e.isSneaking() && !s.attached) finishLater(e.getPlayer(), End.SNEAK);
    }

    /** The spectator menu's "teleport to player" is no way to travel. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpectatorTeleport(PlayerTeleportEvent e) {
        if (foreign(e.getPlayer()) != null && e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) e.setCancelled(true);
    }

    /**
     * Something else moves the player (a command, a teleport request, another plugin): the emote ends here and now,
     * before the move, and the move goes on, so they arrive as themselves.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (foreign(e.getPlayer()) != null) finish(e.getPlayer(), End.TELEPORT, null);
    }

    /** Something else changes the game mode: that mode is theirs when the emote has ended, a tick later. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (foreign(e.getPlayer()) == null) return;
        Player p = e.getPlayer();
        GameMode mode = e.getNewGameMode();
        Bukkit.getScheduler().runTask(plugin, () -> finish(p, End.GAME_MODE, mode));
    }

    /** Before the camera is taken the player is a free spectator for two ticks: they stay at the camera's place. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Session s = foreign(e.getPlayer());
        if (s == null || s.attached || !e.hasChangedPosition()) return;
        Location to = s.camera.getLocation();
        to.setYaw(e.getTo().getYaw());
        to.setPitch(e.getTo().getPitch());
        e.setTo(to);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (emoting(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (emoting(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        finish(e.getPlayer(), End.DEATH, null);
    }

    /** First of all quit handlers: whatever else looks at the leaving player sees them where they stood. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        finish(e.getPlayer(), End.QUIT, null);
        loading.remove(id);
        lastHurt.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        restoreIfStuck(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
    }
}
