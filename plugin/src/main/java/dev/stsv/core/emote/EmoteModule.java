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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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

    /** Uses /감정표현 and the wheel (everybody by default: what they may play is what they own). */
    public static final String PERMISSION = "stsv.emote";
    /** Plays every emote without owning it (admins). */
    public static final String PERMISSION_ALL = "stsv.emote.all";
    /** The kind of thing an emote is in the book of what players own. */
    public static final String KIND = "emote";
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
    private EmoteStore store;
    private EmoteScreens screens;
    /** the wheels of the online players who arranged theirs (the others' are filled with what they own) */
    private final Map<UUID, String[]> wheels = new HashMap<>();
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
        /**
         * The emote is in the player's own body: the puppet rides them and they are hidden (an invisibility effect
         * that runs out by itself, so nothing has to be given back after a crash); no camera, no spectator mode,
         * nothing saved.
         */
        boolean body;
        /** body: the clients carry the puppet on the player (else it is moved to them every tick) */
        boolean carried;
    }

    /** What others must not see on a player whose puppet stands in for them, and what they must not see themselves. */
    private static final EquipmentSlot[] GEAR = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET}, ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** The hidden gear is sent again this often (ticks): the game sends the real one whenever it changes. */
    private static final int GEAR_TICKS = 5;

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
        store = new EmoteStore(plugin.db());
        store.init();
        screens = new EmoteScreens(plugin, this);
        if (plugin.unlocks() != null) {
            plugin.unlocks().register(new dev.stsv.core.unlock.UnlockModule.Kind(KIND, "감정표현",
                    id -> emotes.containsKey(id) ? emotes.get(id).name() : id, () -> emotes.keySet(), EmoteScreens::icon, null, null));
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreIfStuck(p);
            readWheel(p);
        }
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

    /** The emote a player is playing now. */
    public Optional<EmoteSpec> playing(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s == null ? Optional.empty() : Optional.of(s.spec);
    }

    /** Every emote there is, by its id, in the file's order. */
    public Map<String, EmoteSpec> all() {
        return emotes;
    }

    // ------------------------------------------------------------------------------------------
    // Whose emotes, and the wheel
    // ------------------------------------------------------------------------------------------

    /** Whether a player may play an emote: it is free ({@code emotes.free}), it is theirs, or they are an admin. */
    public boolean may(Player p, EmoteSpec e) {
        return p.hasPermission(PERMISSION_ALL) || plugin.getConfig().getStringList("emotes.free").contains(e.id())
                || (plugin.unlocks() != null && plugin.unlocks().has(p.getUniqueId(), KIND, e.id()));
    }

    /** The emotes a player may play, in the file's order. */
    public List<EmoteSpec> owned(Player p) {
        return emotes.values().stream().filter(e -> may(p, e)).toList();
    }

    /** A player's wheel as it is shown: an emote's id a place, null for an empty one. */
    public String[] wheel(Player p) {
        return EmoteWheel.shown(wheels.get(p.getUniqueId()), owned(p).stream().map(EmoteSpec::id).toList());
    }

    /** Puts an emote into a place of a player's wheel (null: empties it) and keeps the wheel. */
    void place(Player p, int slot, String emote) {
        String[] next = EmoteWheel.placed(wheel(p), slot, emote);
        wheels.put(p.getUniqueId(), next);
        store.save(p.getUniqueId(), next);
    }

    private void readWheel(Player p) {
        UUID u = p.getUniqueId();
        store.wheel(u).thenAccept(wheel -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && wheel.isPresent()) wheels.put(u, wheel.get());
        }));
    }

    /** Ends a player's emote as the command does. */
    void stop(Player p) {
        if (emoting(p)) finish(p, End.COMMAND, null);
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
            screens.openWheel(p);                                    // the wheel: one click an emote
        } else if (word.isEmpty() || LIST.contains(word.toLowerCase(Locale.ROOT))) {
            List<String> names = owned(p).stream().map(e -> Lang.raw("emote.list-entry", "name", e.name())).toList();
            p.sendMessage(Lang.msg("emote.list", "emotes", names.isEmpty() ? Lang.raw("common.none") : String.join(Lang.raw("emote.list-separator"), names)));
            p.sendMessage(Lang.msg("emote.usage"));
        } else if (STOP.contains(word.toLowerCase(Locale.ROOT))) {
            if (emoting(p)) finish(p, End.COMMAND, null);
            else p.sendMessage(Lang.msg("emote.not-playing"));
        } else {
            Optional<EmoteSpec> emote = find(word);
            if (emote.isEmpty()) p.sendMessage(Lang.msg("emote.unknown", "name", word));
            else if (!may(p, emote.get())) p.sendMessage(Lang.msg("emote.locked", "name", emote.get().name()));
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
        screens.openWheel(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !enabled()) return List.of();
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (EmoteSpec e : emotes.values()) if (!(sender instanceof Player p) || may(p, e)) out.add(e.name());
        out.add("그만");
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------------------------------

    /** Plays an emote for a player if they may now (says why not otherwise); changes it if one is playing. */
    public void play(Player p, EmoteSpec spec) {
        if (!may(p, spec)) {
            p.sendMessage(Lang.msg("emote.locked", "name", spec.name()));
            return;
        }
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
        if (EmoteRules.View.of(plugin.getConfig().getString("emotes.view")) == EmoteRules.View.BODY) {
            beginBody(p, spec, skin);
            return;
        }
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
    // In the player's own body
    // ------------------------------------------------------------------------------------------

    /**
     * The emote in the player's own body: nothing about the player changes but that they are hidden (an
     * invisibility effect without particles or icon, and their gear sent as nothing) while a puppet rides them. They
     * walk, jump and look around as they like; in their own third-person view they see the puppet where they are.
     */
    private void beginBody(Player p, EmoteSpec spec, PuppetSkin skin) {
        Session s = new Session();
        s.body = true;
        s.spec = spec;
        s.origin = p.getLocation().clone();
        Set<UUID> never = new HashSet<>();
        for (Player v : Bukkit.getOnlinePlayers()) if (v != p && !v.canSee(p)) never.add(v.getUniqueId());
        s.puppet = Puppet.riding(plugin, keyEntity, s.origin, p.getHeight(), skin, spec.poseAt(0), spec.planted(), spec.props(), never);
        sessions.put(p.getUniqueId(), s);
        long limit = EmoteRules.maxTicks(plugin.getConfig().getInt("emotes.max-seconds"));
        // ambient, no particles, no icon: that is how the end knows the effect as its own
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, (int) Math.min(limit + 100, Integer.MAX_VALUE), 0, true, false, false));
        if (!p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            finish(p, End.FAILED, null);   // another plugin refused the effect
            return;
        }
        // the worn models go now (no cosmetics on an invisible player), then the puppet is linked, then shown:
        // the spawn packets carry the link
        if (plugin.cosmetics() != null) {
            plugin.cosmetics().refresh(p);
            s.carried = plugin.cosmetics().carry(p, s.puppet.carrier());
        }
        s.puppet.showTo(Bukkit.getOnlinePlayers(), plugin.getConfig().getInt("emotes.view-distance"));
        hideGear(p);
        s.attached = true;
        dev.stsv.core.hud.HudModule.flash(p, Lang.mm("emote.hint-body", "emote", spec.name()));
    }

    /** The emote's own invisibility (not a potion the player drank meanwhile, which has particles and an icon). */
    private static boolean ownEffect(Player p) {
        PotionEffect e = p.getPotionEffect(PotionEffectType.INVISIBILITY);
        return e != null && e.isAmbient() && !e.hasParticles() && !e.hasIcon();
    }

    /** Others see no armour and nothing in the hands of the hidden player; the player sees no armour on themselves. */
    private void hideGear(Player p) {
        Map<EquipmentSlot, ItemStack> none = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : GEAR) none.put(slot, ItemStack.empty());
        for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, none);
        Map<EquipmentSlot, ItemStack> armour = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : ARMOUR) armour.put(slot, ItemStack.empty());
        p.sendEquipmentChange(p, armour);
    }

    /** The real gear again, for everyone. */
    private void showGear(Player p) {
        Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : GEAR) {
            ItemStack item = p.getInventory().getItem(slot);
            real.put(slot, item == null ? ItemStack.empty() : item);
        }
        for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, real);
        p.updateInventory();
    }

    /** One tick of an emote in the player's own body: false when it ended. */
    private boolean tickBody(Player p, Session s) {
        End end = EmoteRules.bodyEnd(new EmoteRules.Body(p.isDead(), p.getGameMode() == GameMode.SPECTATOR, p.isInsideVehicle(), p.isSneaking(),
                dev.stsv.core.cosmetic.CosmeticModule.lying(p), ownEffect(p)));
        if (end == null && !s.puppet.valid()) end = End.LOST;
        if (end != null) {
            finish(p, end, null);
            return false;
        }
        Location top = p.getLocation();
        s.puppet.turn(top.getYaw());
        s.puppet.follow(top.add(0, p.getHeight(), 0), s.carried);
        // every tick at first: the worn models' own tick sends the real helmet once more when it sees the player hidden
        if (s.age <= 2 * GEAR_TICKS || s.age % GEAR_TICKS == 0) hideGear(p);
        return true;
    }

    /** The end of an emote in the player's own body: the puppet goes, the player shows again as they are. */
    private void finishBody(Player p, Session s, End end) {
        step(p, "인형 치우기", () -> {
            if (plugin.cosmetics() != null) plugin.cosmetics().carry(p, null);
            discard(s);
        });
        step(p, "모습 되돌리기", () -> {
            if (ownEffect(p)) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        });
        lastEnd.put(p.getUniqueId(), System.currentTimeMillis());
        if (end == End.QUIT || end == End.DISABLE && !p.isOnline()) return;
        step(p, "장비 다시 보이기", () -> showGear(p));
        if (end == End.DISABLE) return;
        if (end.message != null && p.isOnline()) p.sendMessage(Lang.msg(end.message));
        // the worn models come back when the game has made the player visible again
        for (long delay : new long[]{1, 10}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || emoting(p)) return;
                if (plugin.cosmetics() != null && !plugin.cosmetics().inWardrobe(p)) plugin.cosmetics().refresh(p);
                showGear(p);
            }, delay);
        }
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
            if (s.body) {
                if (!tickBody(p, s)) continue;
            } else if (!s.puppet.valid() || (s.attached && (!s.camera.isValid() || p.getGameMode() != GameMode.SPECTATOR || p.getSpectatorTarget() != s.camera))) {
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
        if (s.body) {
            finishBody(p, s, end);
            return;
        }
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
        Session s = foreign(e.getPlayer());
        if (s == null || s.body) return;
        e.setCancelled(true);
        finishLater(e.getPlayer(), End.SNEAK);
    }

    /** A spectator's click on an entity would put them inside it: never anything but the camera. */
    @EventHandler
    public void onStartSpectating(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && !s.body && e.getNewSpectatorTarget() != s.camera) e.setCancelled(true);
    }

    /** Sneaking before the camera is taken (the first two ticks). */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Session s = foreign(e.getPlayer());
        if (s != null && e.isSneaking() && (s.body || !s.attached)) finishLater(e.getPlayer(), End.SNEAK);
    }

    /** The spectator menu's "teleport to player" is no way to travel. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpectatorTeleport(PlayerTeleportEvent e) {
        Session s = foreign(e.getPlayer());
        if (s != null && !s.body && e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) e.setCancelled(true);
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
        if (s == null || s.body || s.attached || !e.hasChangedPosition()) return;
        Location to = s.camera.getLocation();
        to.setYaw(e.getTo().getYaw());
        to.setPitch(e.getTo().getPitch());
        e.setTo(to);
    }

    /** From the camera nothing is touched; in their own body the player does what they like. */
    private boolean watching(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s != null && !s.body;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (watching(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (watching(e.getPlayer())) e.setCancelled(true);
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
        wheels.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        restoreIfStuck(e.getPlayer());
        readWheel(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
            endBody(p);
        }
        // a hidden player does not fight: whoever is hurt or hits shows again
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player attacker) endBody(attacker);
    }

    private void endBody(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.body) finishLater(p, End.LOST);
    }
}
