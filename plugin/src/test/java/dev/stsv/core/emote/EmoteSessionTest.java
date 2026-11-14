package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteReturn.End;
import dev.stsv.core.emote.EmoteRules.Situation;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emoting player without a server: what they get back for every way an emote ends (nobody is left a spectator
 * or away from where they stood), the tag a crash leaves, when an emote may start, where the camera goes; and that
 * the command, its permission, the settings and the messages are all there.
 */
class EmoteSessionTest {

    static final EmoteReturn SURVIVAL = new EmoteReturn("3b1f6b1e-8c1d-4f58-9b61-0a1f2c3d4e5f", 12.5, 64, -7.25, 91.5f, 12f, GameMode.SURVIVAL, false, false, false);

    // ------------------------------------------------------------------------------------------
    // Giving everything back
    // ------------------------------------------------------------------------------------------

    @Test
    void everyEndingGivesTheGameModeBack() {
        for (GameMode mode : new GameMode[]{GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.CREATIVE}) {
            EmoteReturn saved = SURVIVAL.withGameMode(mode);
            for (End end : End.values()) {
                EmoteReturn.Plan plan = saved.plan(end, null);
                assertEquals(mode, plan.gameMode(), end + ": the game mode they had");
                assertTrue(plan.gameMode() != GameMode.SPECTATOR, end + ": never left a spectator");
                assertTrue(plan.visible(), end + ": the invisible flag of spectator mode goes");
                assertEquals(mode == GameMode.CREATIVE, plan.allowFlight(), end + " " + mode + ": flight as the mode has it");
                assertFalse(plan.flying(), end + ": they stood on the ground");
                // back to where they stood, unless they died or something else is moving them
                assertEquals(end != End.DEATH && end != End.TELEPORT, plan.back(), end + ": back");
            }
        }
    }

    @Test
    void flightAndInvisibilityAreTheirs() {
        // a survival player who may fly (a perk of another plugin) keeps that, and keeps flying if they were
        EmoteReturn flyer = new EmoteReturn(SURVIVAL.world(), 0, 80, 0, 0, 0, GameMode.SURVIVAL, true, true, false);
        EmoteReturn.Plan plan = flyer.plan(End.SNEAK, null);
        assertTrue(plan.allowFlight() && plan.flying());
        assertTrue(new EmoteReturn(SURVIVAL.world(), 0, 80, 0, 0, 0, GameMode.SURVIVAL, true, false, false).plan(End.SNEAK, null).allowFlight());
        assertFalse(new EmoteReturn(SURVIVAL.world(), 0, 80, 0, 0, 0, GameMode.SURVIVAL, true, false, false).plan(End.SNEAK, null).flying());
        // flying without being allowed to cannot be: no flight
        assertFalse(new EmoteReturn(SURVIVAL.world(), 0, 80, 0, 0, 0, GameMode.SURVIVAL, false, true, false).plan(End.SNEAK, null).flying());
        // creative: allowed again (spectator mode leaves the player flying: not on the ground they stood on)
        EmoteReturn creative = SURVIVAL.withGameMode(GameMode.CREATIVE);
        assertTrue(creative.plan(End.TIME_UP, null).allowFlight());
        assertFalse(creative.plan(End.TIME_UP, null).flying());
        // a player made invisible by something else before the emote stays so
        EmoteReturn hidden = new EmoteReturn(SURVIVAL.world(), 0, 80, 0, 0, 0, GameMode.SURVIVAL, false, false, true);
        assertFalse(hidden.plan(End.SNEAK, null).visible());
    }

    @Test
    void aGameModeSomeoneElseSetIsKept() {
        EmoteReturn.Plan plan = SURVIVAL.plan(End.GAME_MODE, GameMode.CREATIVE);
        assertEquals(GameMode.CREATIVE, plan.gameMode());
        assertTrue(plan.back() && plan.allowFlight());
        assertFalse(plan.flying());
        // an admin made them a spectator on purpose: so be it, a free one where they stood
        plan = SURVIVAL.plan(End.GAME_MODE, GameMode.SPECTATOR);
        assertEquals(GameMode.SPECTATOR, plan.gameMode());
        assertTrue(plan.back() && plan.allowFlight() && plan.flying());
        assertFalse(plan.visible(), "a spectator is invisible: nothing to clear");
        // not known: theirs
        assertEquals(GameMode.SURVIVAL, SURVIVAL.plan(End.GAME_MODE, null).gameMode());
        // any other ending ignores it
        assertEquals(GameMode.SURVIVAL, SURVIVAL.plan(End.SNEAK, GameMode.CREATIVE).gameMode());
        assertEquals(GameMode.ADVENTURE, SURVIVAL.withGameMode(GameMode.ADVENTURE).gameMode());
        assertEquals(SURVIVAL.x(), SURVIVAL.withGameMode(GameMode.ADVENTURE).x());
    }

    @Test
    void theTagACrashLeaves() {
        for (EmoteReturn r : new EmoteReturn[]{SURVIVAL, SURVIVAL.withGameMode(GameMode.CREATIVE),
                new EmoteReturn("w", -1e7 + 0.123456789, 319.99, 2.5e-9, -179.99f, 89.5f, GameMode.ADVENTURE, true, true, true)}) {
            assertEquals(Optional.of(r), EmoteReturn.decode(r.encode()), r.encode());
        }
        assertFalse(SURVIVAL.encode().contains("\n"));
        // anything else: nothing (and the player is then only made sure to be no spectator)
        String good = SURVIVAL.encode();
        for (String bad : new String[]{null, "", "1", good + ";extra", good.substring(2), "2" + good.substring(1),
                good.replace("SURVIVAL", "FLYING"), good.replace("SURVIVAL", "SPECTATOR"), good.replace("12.5", "NaN"),
                good.replace("12.5", "Infinity"), good.replace("12.5", "east"), good.replace("false;false;false", "false;false;maybe"),
                good.replace("3b1f6b1e-8c1d-4f58-9b61-0a1f2c3d4e5f", ""), "world;1;2;3;4;5;true;SURVIVAL"}) {
            assertTrue(EmoteReturn.decode(bad).isEmpty(), String.valueOf(bad));
        }
        assertEquals(GameMode.SURVIVAL, EmoteReturn.fallbackMode(GameMode.SPECTATOR, GameMode.SURVIVAL));
        assertEquals(GameMode.ADVENTURE, EmoteReturn.fallbackMode(GameMode.SPECTATOR, GameMode.ADVENTURE));
        assertEquals(GameMode.SURVIVAL, EmoteReturn.fallbackMode(GameMode.SPECTATOR, GameMode.SPECTATOR), "never spectator");
        assertEquals(GameMode.SURVIVAL, EmoteReturn.fallbackMode(GameMode.SPECTATOR, null));
        assertEquals(GameMode.CREATIVE, EmoteReturn.fallbackMode(GameMode.CREATIVE, GameMode.SURVIVAL), "whoever is no spectator keeps their mode");
        // put back after a crash: as when the plugin goes down
        EmoteReturn.Plan plan = EmoteReturn.decode(good).orElseThrow().plan(End.DISABLE, null);
        assertTrue(plan.back());
        assertEquals(GameMode.SURVIVAL, plan.gameMode());
        assertNull(plan.message());
    }

    // ------------------------------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------------------------------

    static Situation standing() {
        return new Situation(false, false, false, false, false, false, false, true, 0f, false, -1);
    }

    @Test
    void whenAnEmoteMayStart() {
        assertNull(EmoteRules.refusal(standing()));
        assertNull(EmoteRules.refusal(new Situation(false, false, false, false, false, false, false, true, 0.4f, false, EmoteRules.HURT_MILLIS)), "ten seconds after a hit");
        assertEquals("emote.not-now", EmoteRules.refusal(new Situation(true, false, false, false, false, false, false, true, 0f, false, -1)), "dead");
        assertEquals("emote.dismount", EmoteRules.refusal(new Situation(false, true, false, false, false, false, false, true, 0f, false, -1)));
        assertEquals("emote.not-now", EmoteRules.refusal(new Situation(false, false, true, false, false, false, false, true, 0f, false, -1)), "a spectator already");
        assertEquals("emote.not-now", EmoteRules.refusal(new Situation(false, false, false, true, false, false, false, true, 0f, false, -1)), "in the wardrobe");
        assertEquals("emote.not-now", EmoteRules.refusal(new Situation(false, false, false, false, true, false, false, true, 0f, false, -1)), "gliding, swimming, asleep");
        assertEquals("emote.in-water", EmoteRules.refusal(new Situation(false, false, false, false, false, true, false, true, 0f, false, -1)));
        assertEquals("emote.burning", EmoteRules.refusal(new Situation(false, false, false, false, false, false, true, true, 0f, false, -1)));
        assertEquals("emote.not-ground", EmoteRules.refusal(new Situation(false, false, false, false, false, false, false, false, 0f, false, -1)));
        assertEquals("emote.not-ground", EmoteRules.refusal(new Situation(false, false, false, false, false, false, false, true, 3f, false, -1)), "landing from a fall");
        assertEquals("emote.not-now", EmoteRules.refusal(new Situation(false, false, false, false, false, false, false, true, 0f, true, -1)), "invisible: a puppet would give them away");
        assertEquals("emote.in-combat", EmoteRules.refusal(new Situation(false, false, false, false, false, false, false, true, 0f, false, 0)));
        assertEquals("emote.in-combat", EmoteRules.refusal(new Situation(false, false, false, false, false, false, false, true, 0f, false, EmoteRules.HURT_MILLIS - 1)));
    }

    @Test
    void waitingAndTimeLimit() {
        assertEquals(0, EmoteRules.cooldownLeft(0, 1_000_000, 3), "never emoted");
        assertEquals(3, EmoteRules.cooldownLeft(1_000_000, 1_000_000, 3));
        assertEquals(3, EmoteRules.cooldownLeft(1_000_000, 1_000_001, 3), "rounded up: never '0 seconds'");
        assertEquals(1, EmoteRules.cooldownLeft(1_000_000, 1_002_999, 3));
        assertEquals(0, EmoteRules.cooldownLeft(1_000_000, 1_003_000, 3));
        assertEquals(0, EmoteRules.cooldownLeft(1_000_000, 9_000_000, 3));
        assertEquals(0, EmoteRules.cooldownLeft(1_000_000, 1_000_000, 0), "no cooldown set");
        assertEquals(0, EmoteRules.cooldownLeft(1_000_000, 1_000_000, -5));
        assertEquals(600, EmoteRules.maxTicks(30));
        assertEquals(20, EmoteRules.maxTicks(0), "at least a second");
    }

    // ------------------------------------------------------------------------------------------
    // The camera
    // ------------------------------------------------------------------------------------------

    @Test
    void aroundThePuppet() {
        // a puppet looking at +z (yaw 0): in front of it is +z, its left is +x, behind it -z
        EmoteRigTest.assertNear(new Vector3d(0, 0, 1), EmoteRules.direction(0, 0, 0), 1e-9, "in front");
        EmoteRigTest.assertNear(new Vector3d(1, 0, 0), EmoteRules.direction(0, 90, 0), 1e-9, "at its left");
        EmoteRigTest.assertNear(new Vector3d(0, 0, -1), EmoteRules.direction(0, 180, 0), 1e-9, "behind");
        EmoteRigTest.assertNear(new Vector3d(0, 1, 0), EmoteRules.direction(0, 33, 90), 1e-9, "above");
        // the game's yaw 90 looks at -x: in front of that puppet is -x, its left is +z
        EmoteRigTest.assertNear(new Vector3d(-1, 0, 0), EmoteRules.direction(90, 0, 0), 1e-9, "yaw 90: in front");
        EmoteRigTest.assertNear(new Vector3d(0, 0, 1), EmoteRules.direction(90, 90, 0), 1e-9, "yaw 90: at its left");
        EmoteRigTest.assertNear(new Vector3d(0, 0, -1), EmoteRules.direction(-180, 0, 0), 1e-9, "yaw 180: in front");
        assertEquals(Math.sin(Math.toRadians(14)), EmoteRules.direction(77, 150, 14).y, 1e-9);
        assertEquals(1, EmoteRules.direction(77, 150, 14).length(), 1e-9);
        // the same turn as the puppet's own: its front face looks where "in front" is
        double facing = -Math.toRadians(77);
        EmoteRigTest.assertNear(new Vector3d(0, 0, 1).rotateY(facing), EmoteRules.direction(77, 0, 0), 1e-9, "the puppet's turn");
    }

    @Test
    void theCameraGoesWhereThereIsRoom() {
        EmoteSpec.Camera camera = new EmoteSpec.Camera(150, 14, 3.6);
        double reach = camera.distance() + EmoteRules.WALL_MARGIN;
        // open ground: the emote's own place, the whole distance away
        Vector3d open = EmoteRules.camera(camera, 30, d -> reach).orElseThrow().offset();
        EmoteRigTest.assertNear(EmoteRules.direction(30, 150, 14).mul(3.6), open, 1e-9, "the emote's place");
        // a wall there: the next try with the whole distance free
        Vector3d own = EmoteRules.direction(30, 150, 14);
        Vector3d second = EmoteRules.camera(camera, 30, d -> d.distance(own) < 1e-6 ? 1.0 : reach).orElseThrow().offset();
        EmoteRigTest.assertNear(EmoteRules.direction(30, 180, 14).mul(3.6), second, 1e-9, "30 degrees further round");
        // a wall a little short of the distance counts as a wall (the camera keeps off it)
        Vector3d near = EmoteRules.camera(camera, 30, d -> d.distance(own) < 1e-6 ? 3.7 : reach).orElseThrow().offset();
        EmoteRigTest.assertNear(EmoteRules.direction(30, 180, 14).mul(3.6), near, 1e-9, "3.7 free: 0.35 off the wall is nearer than 3.6");
        // walls all around, most room straight overhead-ish: the roomiest try, in front of its wall
        Vector3d up = EmoteRules.direction(30, 150, 59);
        Vector3d boxed = EmoteRules.camera(camera, 30, d -> d.distance(up) < 1e-6 ? 3.0 : 2.0).orElseThrow().offset();
        EmoteRigTest.assertNear(new Vector3d(up).mul(3.0 - EmoteRules.WALL_MARGIN), boxed, 1e-9, "the try with the most room");
        // a hole in the ground: no camera, no emote
        assertTrue(EmoteRules.camera(camera, 30, d -> 1.5).isEmpty());
        assertTrue(EmoteRules.camera(camera, 30, d -> 0).isEmpty());
        assertTrue(EmoteRules.camera(camera, 30, d -> EmoteRules.MIN_DISTANCE + EmoteRules.WALL_MARGIN + 0.01).isPresent());
        // never asked for more than the distance and the margin, never looks from under the ground at 85 degrees or more
        EmoteRules.camera(new EmoteSpec.Camera(0, 60, 3), 0, d -> {
            assertEquals(1, d.length(), 1e-9);
            assertTrue(d.y <= Math.sin(Math.toRadians(85)) + 1e-9);
            return 0.5;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------------------------------

    static YamlConfiguration resource(String name) throws Exception {
        try (var in = EmoteSessionTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyMessageExists() throws Exception {
        YamlConfiguration messages = resource("messages.yml");
        for (End end : End.values()) if (end.message != null) assertTrue(messages.isString(end.message), end + ": " + end.message);
        // every key in the emote package's sources, also those not written as Lang.msg("...")
        Pattern key = Pattern.compile("\"(emote\\.[a-z-]+)\"");
        int found = 0;
        try (var files = Files.list(Path.of("src/main/java/dev/stsv/core/emote"))) {
            for (Path f : files.toList()) {
                Matcher m = key.matcher(Files.readString(f));
                while (m.find()) {
                    found++;
                    assertTrue(messages.isString(m.group(1)) || messages.isList(m.group(1)), f.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertTrue(found >= 15, "found " + found);
        // and nothing in messages.yml that the code never says
        String sources = "";
        try (var files = Files.list(Path.of("src/main/java/dev/stsv/core/emote"))) {
            for (Path f : files.toList()) sources += Files.readString(f);
        }
        for (String k : messages.getConfigurationSection("emote").getKeys(false)) assertTrue(sources.contains("\"emote." + k + "\""), "emote." + k + " is not used");
    }

    /** The emote in the player's own body: what it is, what ends it, when the puppet turns with its player. */
    @Test
    void inTheirOwnBody() {
        assertEquals(EmoteRules.View.BODY, EmoteRules.View.of("body"));
        assertEquals(EmoteRules.View.CAMERA, EmoteRules.View.of(" Camera "));
        assertEquals(EmoteRules.View.BODY, EmoteRules.View.of(null), "the body unless the camera is asked for");
        assertEquals(EmoteRules.View.BODY, EmoteRules.View.of("something"));

        // dead, spectator, vehicle, sneaking, lying, hidden
        assertEquals(null, EmoteRules.bodyEnd(new EmoteRules.Body(false, false, false, false, false, true)), "walking about hidden: it goes on");
        assertEquals(End.SNEAK, EmoteRules.bodyEnd(new EmoteRules.Body(false, false, false, true, false, true)), "sneaking ends it, as from the camera");
        assertEquals(End.DEATH, EmoteRules.bodyEnd(new EmoteRules.Body(true, false, false, true, false, true)));
        assertEquals(End.LOST, EmoteRules.bodyEnd(new EmoteRules.Body(false, true, false, false, false, true)));
        assertEquals(End.LOST, EmoteRules.bodyEnd(new EmoteRules.Body(false, false, true, false, false, true)), "a seat: the puppet stands");
        assertEquals(End.LOST, EmoteRules.bodyEnd(new EmoteRules.Body(false, false, false, false, true, true)), "swimming, gliding, crawling");
        assertEquals(End.LOST, EmoteRules.bodyEnd(new EmoteRules.Body(false, false, false, false, false, false)),
                "the invisibility went (milk, another plugin): the player's own body would show inside the puppet");
        // every one of these endings leaves the player where they are and says something (but death)
        assertFalse(End.DEATH.back);
        assertTrue(End.SNEAK.message != null && End.LOST.message != null);

        // the puppet turns when its player has looked 2 degrees away, also across the seam at +-180
        assertFalse(EmoteRules.turned(10f, 11.5f, Puppet.TURN_STEP));
        assertTrue(EmoteRules.turned(10f, 12f, Puppet.TURN_STEP));
        assertTrue(EmoteRules.turned(10f, 7f, Puppet.TURN_STEP));
        assertFalse(EmoteRules.turned(179.5f, -179.5f, Puppet.TURN_STEP), "one degree across the seam");
        assertTrue(EmoteRules.turned(179f, -178f, Puppet.TURN_STEP));
        assertFalse(EmoteRules.turned(10f, 370.5f, Puppet.TURN_STEP), "a full turn more is the same way");
    }

    /** The quick key (Ctrl + Shift while standing still) and what the buttons run. */
    @Test
    void theQuickKeyAndTheButtons() {
        assertTrue(EmoteMenu.quickKey(true, true, false), "sneak and sprint together, standing still");
        assertFalse(EmoteMenu.quickKey(true, false, false), "sneaking alone (that ends an emote)");
        assertFalse(EmoteMenu.quickKey(false, true, false), "sprinting alone");
        assertFalse(EmoteMenu.quickKey(true, true, true), "not while walking or jumping (a sprint cut short by a sneak)");
        assertEquals("/감정표현 트월킹", EmoteMenu.command("트월킹"));
        assertEquals("/감정표현 L을 가져가", EmoteMenu.command("L을 가져가"), "a name of several words is the rest of the command");
        assertTrue(EmoteMenu.QUICK_KEY_GAP_MS >= 500);
    }

    @Test
    void commandPermissionAndSettings() throws Exception {
        // plugin.yml is a template (the version): read it as text
        String plugin;
        try (var in = getClass().getResourceAsStream("/plugin.yml")) {
            plugin = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(plugin.replace("${version}", "0"));
        assertEquals(EmoteModule.PERMISSION, y.getString("commands.감정표현.permission"));
        assertTrue(y.getStringList("commands.감정표현.aliases").contains("emote"));
        assertEquals("true", y.getString("permissions." + EmoteModule.PERMISSION + ".default"), "the wheel is everybody's: what they play is what they own");
        assertEquals("op", y.getString("permissions." + EmoteModule.PERMISSION_ALL + ".default"), "only admins play what they do not own");
        YamlConfiguration config = resource("config.yml");
        assertTrue(config.getBoolean("emotes.enabled"));
        assertTrue(config.getBoolean("emotes.quick-key"), "the quick key is on unless it is turned off");
        assertEquals(EmoteRules.View.BODY, EmoteRules.View.of(config.getString("emotes.view")), "in the player's own body unless set otherwise");
        assertEquals("body", config.getString("emotes.view"));
        assertTrue(config.getInt("emotes.max-seconds") >= 5);
        assertTrue(config.getInt("emotes.cooldown-seconds") >= 0);
        assertTrue(config.getInt("emotes.view-distance") >= 16);
        // one emote is everybody's, so the wheel is never empty; it is one of the emotes there are
        YamlConfiguration emotes = YamlConfiguration.loadConfiguration(Path.of("../content/stsv/emotes.yml").toFile());
        assertFalse(config.getStringList("emotes.free").isEmpty());
        for (String free : config.getStringList("emotes.free")) assertTrue(emotes.contains("emotes." + free), free);
        // every emote of the pack has an icon of its own for the wheel
        for (String id : emotes.getConfigurationSection("emotes").getKeys(false)) {
            assertTrue(EmoteWheel.DRAWN.contains(id), id + " has no wheel icon (tools/assetgen/stsv_assets/ui_icons.py donation_icons, EmoteWheel.DRAWN)");
            assertTrue(Files.isRegularFile(Path.of("../content/stsv/assets/stsv/items/gui/" + EmoteWheel.icon(id) + ".json")), id);
        }
        for (String icon : List.of(EmoteWheel.ICON_ANY, EmoteWheel.ICON_EMPTY)) {
            assertTrue(Files.isRegularFile(Path.of("../content/stsv/assets/stsv/items/gui/" + icon + ".json")), icon);
        }
    }

    /** The wheel: what it shows for what a player arranged and owns. */
    @Test
    void theWheel() {
        List<String> owned = List.of("bow", "twerk", "disco");
        // never arranged: what the player owns, from the top
        String[] fresh = EmoteWheel.shown(null, owned);
        assertEquals(EmoteWheel.SLOTS, fresh.length);
        assertEquals(List.of("bow", "twerk", "disco"), java.util.Arrays.asList(fresh).subList(0, 3));
        for (int k = 3; k < fresh.length; k++) assertNull(fresh[k]);
        // more owned than places: the first six
        assertEquals(6, EmoteWheel.SLOTS);
        assertEquals("f", EmoteWheel.shown(null, List.of("a", "b", "c", "d", "e", "f", "g", "h"))[5]);
        // arranged: as arranged; what is no longer owned leaves its place empty
        String[] put = EmoteWheel.placed(fresh, 5, "twerk");
        assertEquals("twerk", put[5]);
        assertNull(put[1], "an emote sits in one place only");
        assertEquals("bow", put[0]);
        String[] shown = EmoteWheel.shown(put, List.of("bow", "disco"));
        assertNull(shown[5], "twerk was taken back");
        assertEquals("disco", shown[2]);
        // emptied: stays empty (an arranged wheel is not filled again)
        String[] none = EmoteWheel.placed(EmoteWheel.placed(EmoteWheel.placed(put, 0, null), 2, null), 5, null);
        for (String s : EmoteWheel.shown(none, owned)) assertNull(s);
        // a place that does not exist changes nothing
        assertEquals(java.util.Arrays.asList(put), java.util.Arrays.asList(EmoteWheel.placed(put, 9, "bow")));
        assertEquals("emote_twerk", EmoteWheel.icon("twerk"));
        assertEquals(EmoteWheel.ICON_ANY, EmoteWheel.icon("moonwalk"));
    }

    /** The wheel on the player's own screen: where its displays go, what a look picks, the font its pictures are in. */
    @Test
    void theWheelOnTheScreen() throws Exception {
        // a display sits at the passenger point, above the eyes: looking level, a point straight ahead is that much lower
        double above = 1.8 - 1.62;
        double[] level = EmoteHud.onScreen(0, 0, 0.25, above, 0);
        assertEquals(0, level[0], 1e-9);
        assertEquals(-above, level[1], 1e-9);
        assertEquals(-0.25, level[2], 1e-9);
        // looking straight down, ahead is down: the point is that much further ahead, not lower on the screen
        double[] down = EmoteHud.onScreen(0, 0, 0.25, above, 90);
        assertEquals(0, down[1], 1e-9);
        assertEquals(-(0.25 + above), down[2], 1e-9);
        // looking straight up, the passenger point is ahead of the eyes
        assertEquals(-(0.25 - above), EmoteHud.onScreen(0, 0, 0.25, above, -90)[2], 1e-9);
        // in every case the point is where it should be: back in the world, eye + ahead along the look + the screen's offset
        for (double pitch : new double[]{-80, -30, 0, 25, 60, 89}) {
            double t = Math.toRadians(pitch);
            double[] at = EmoteHud.onScreen(0.03, -0.02, 0.25, above, pitch);
            // the camera's axes in a vertical plane (yaw 0): forward (cos t, -sin t), up (sin t, cos t); the display's -z is forward
            double forward = -at[2], up = at[1];
            double worldAhead = forward * Math.cos(t) + up * Math.sin(t), worldUp = -forward * Math.sin(t) + up * Math.cos(t);
            double wantAhead = 0.25 * Math.cos(t) + -0.02 * Math.sin(t), wantUp = -0.25 * Math.sin(t) + -0.02 * Math.cos(t) - above;
            assertEquals(wantAhead, worldAhead, 1e-9, "pitch " + pitch);
            assertEquals(wantUp, worldUp, 1e-9, "pitch " + pitch);
            assertEquals(0.03, at[0], 1e-9);
        }
        // sneaking (the key is held): the hitbox and the eyes are lower, the difference a little larger
        assertTrue(1.5 - 1.27 > above);

        // the look picks a place: nothing in the middle, then the nearest place's direction
        assertEquals(-1, EmoteHud.pick(0, 0));
        assertEquals(-1, EmoteHud.pick(2, -2));
        assertEquals(0, EmoteHud.pick(0, 10), "up");
        assertEquals(3, EmoteHud.pick(0, -10), "down");
        assertEquals(1, EmoteHud.pick(10, 5), "right and up");
        assertEquals(2, EmoteHud.pick(10, -5), "right and down");
        assertEquals(4, EmoteHud.pick(-10, -5));
        assertEquals(5, EmoteHud.pick(-10, 5));
        assertEquals(1, EmoteHud.pick(40, 20), "however far");
        assertEquals(EmoteWheel.SLOTS, EmoteHud.PLACES.length);
        // the pointer follows the look and stops at the places
        assertEquals(0, EmoteHud.pointer(0, 0)[0], 1e-9);
        double[] far = EmoteHud.pointer(90, 0);
        assertEquals(40, far[0], 1e-9);
        assertEquals(0, far[1], 1e-9);
        assertTrue(EmoteHud.pointer(0, 7)[1] < 0, "looking up moves it up the picture");
        // turning: the short way round
        assertEquals(20, EmoteHud.turned(170, -170), 1e-9);
        assertEquals(-20, EmoteHud.turned(-170, 170), 1e-9);
        assertEquals(5, EmoteHud.turned(10, 15), 1e-9);
        // nearer than a wall a player can stand against (half a hitbox: 0.3), further than the near plane
        assertTrue(EmoteHud.AHEAD < 0.3 && EmoteHud.AHEAD > 0.06);
        // the dial fills less than half the screen's height at a field of view of 70 degrees
        double screen = 2 * EmoteHud.AHEAD * Math.tan(Math.toRadians(35));
        double dial = EmoteHud.DIAL_PX * EmoteHud.PIXEL;
        assertTrue(dial / screen > 0.3 && dial / screen < 0.5, "the dial is " + dial / screen + " of the screen's height");

        // the font: a glyph a picture, every picture a file of the pack, every icon of the wheel among them
        String json = EmotePack.wheelFont();
        var font = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("providers");
        java.util.Set<String> glyphs = new java.util.HashSet<>();
        for (var e : font) {
            var o = e.getAsJsonObject();
            assertEquals("bitmap", o.get("type").getAsString());
            String file = o.get("file").getAsString();
            assertTrue(file.startsWith("stsv:"));
            Path png = Path.of("../content/stsv/assets/stsv/textures/" + file.substring(5));
            assertTrue(Files.isRegularFile(png), "no picture " + png);
            var im = javax.imageio.ImageIO.read(png.toFile());
            int height = o.get("height").getAsInt();
            assertEquals(im.getHeight(), height, file + ": drawn pixel for pixel");
            assertEquals(im.getWidth(), im.getHeight(), file + ": square, so its middle is the glyph's");
            assertEquals(height / 2 - 3, o.get("ascent").getAsInt(), file + ": its middle on the display's origin");
            assertTrue(glyphs.add(o.getAsJsonArray("chars").get(0).getAsString()), "a glyph twice");
        }
        assertEquals(3 + EmoteHud.ICONS.size(), glyphs.size());
        assertTrue(glyphs.contains(String.valueOf(EmoteHud.DIAL)) && glyphs.contains(String.valueOf(EmoteHud.RING)) && glyphs.contains(String.valueOf(EmoteHud.POINTER)));
        for (String id : EmoteWheel.DRAWN) assertTrue(glyphs.contains(String.valueOf(EmoteHud.glyph(EmoteWheel.icon(id)))), id);
        assertEquals(EmoteHud.glyph(EmoteWheel.ICON_ANY), EmoteHud.glyph("emote_nobody_drew"), "an icon the font lacks shows the plain one");
        assertEquals(EmoteHud.DIAL_PX, javax.imageio.ImageIO.read(Path.of("../content/stsv/assets/stsv/textures/font/emote_wheel/dial.png").toFile()).getWidth());
        assertTrue(EmotePack.files().containsKey(EmotePack.WHEEL_FONT_JSON));
        // the setting: the wheel on the screen unless it is switched to the menu
        assertEquals("hud", resource("config.yml").getString("emotes.quick-wheel"));
    }
}
