package dev.stsv.economy.secret.newcomer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 새로 온 사람: his first questions spell what he really asks, the settings file says what the code says, the night's
 * minute is the same for the same date and always inside the window (also across midnight), which commands are whispers
 * to him, and which stone someone stands at.
 */
class NewcomerTest {

    static final Path FILE = Path.of(System.getProperty("stsv.content")).resolve("economy/secrets/newcomer.yml");

    static YamlConfiguration yml() {
        return YamlConfiguration.loadConfiguration(FILE.toFile());
    }

    // ---- the acrostic (the pools themselves: NewcomerTalkTest)

    /** The first question of each default pool, in order. */
    static List<String> firsts() {
        return Questions.DEFAULTS.values().stream().map(l -> l.get(0).text()).toList();
    }

    @Test
    void defaultQuestionsSpellHisQuestion() {
        assertEquals("내비석어디", Newcomer.DEFAULT_ACROSTIC);
        assertEquals(Questions.letters(Newcomer.DEFAULT_ACROSTIC), List.copyOf(Questions.DEFAULTS.keySet()));
        assertEquals(Newcomer.DEFAULT_ACROSTIC, Acrostic.of(firsts()));
        assertTrue(Acrostic.spells(firsts(), "내 비석 어디"), "spaces in the word do not count");
    }

    @Test
    void theSettingsFileSaysWhatTheCodeSays() {
        YamlConfiguration y = yml();
        List<String> problems = new java.util.ArrayList<>();
        assertEquals(Questions.DEFAULTS, Questions.read(y.getConfigurationSection("questions"), y.getString("acrostic"), problems),
                "the file's questions and the code's defaults say the same");
        assertEquals(List.of(), problems);
        assertFalse(y.contains("visit.questions"), "the old fixed five are gone");
        assertFalse(y.contains("react"), "the old two-line reaction is gone");
        assertFalse(y.contains("whisper.evasive"), "the old four-line cycle is gone");
        assertEquals(Newcomer.DEFAULT_ACROSTIC, y.getString("acrostic"));
        assertEquals(Newcomer.ENDING, y.getStringList("ending.lines"));
        assertEquals(Newcomer.FIXES, y.getStringList("typo.fix"));
        assertEquals(Newcomer.TIME_WORDS, y.getStringList("visit.time-words"));
        assertEquals(Talk.STRANGE, y.getStringList("whisper.strange.lines"));
        assertEquals(Newcomer.DEFAULT_NAME, y.getString("name"));
        assertTrue(Whispers.account(y.getString("name")), "his name looks like an account");
        assertTrue(y.getString("stone-name").endsWith(y.getString("name")), "the solved stone keeps his name");
        assertEquals("economy:thirteenth", y.getString("rewards.title"));
        assertEquals(0, y.getLong("rewards.money"));
        // the keys the code reads for an ending that stops and for the perk are in the file
        assertEquals("…아, 가시네요.", y.getString("ending.left"));
        assertTrue(y.getDouble("ending.leave-range") > y.getDouble("stone.range"), "standing at the stone is not leaving it");
        assertTrue(y.getBoolean("perk.no-leave-line"));
        assertTrue(y.getBoolean("perk.show-kick"), "a kick or a ban is not hidden");
    }

    @Test
    void hisWordsAreSpokenNotWritten() {
        // nobody says "당신" to a stranger in chat; the one he speaks to is called by name
        List<String> all = new java.util.ArrayList<>(Newcomer.ENDING);
        for (List<Questions.Entry> pool : Questions.DEFAULTS.values()) {
            for (Questions.Entry e : pool) {
                all.add(e.text());
                if (e.rain() != null) all.add(e.rain());
            }
        }
        for (Talk.Intent i : NewcomerTalkTest.all()) {
            all.addAll(i.lines);
            all.addAll(i.follow);
            all.addAll(i.slip);
        }
        all.addAll(Talk.STRANGE);
        all.addAll(Newcomer.FIXES);
        for (String line : all) {
            assertFalse(line.contains("당신"), line);
            assertFalse(line.contains("!"), line);
            assertEquals(line.strip(), line);
        }
        assertTrue(Newcomer.ENDING.stream().anyMatch(x -> x.contains("{player}님")));
        for (String line : Newcomer.ENDING) assertTrue(line.startsWith("…"), line);
        for (String line : Talk.STRANGE) assertTrue(line.startsWith("…"), line);
        for (List<Questions.Entry> pool : Questions.DEFAULTS.values()) {
            for (Questions.Entry e : pool) assertFalse(e.text().startsWith("…"), "in public he is only a new player: " + e.text());
        }
    }

    // ---- his chat line is a real player's chat line

    /** The core's own line for a player without a title (ProfileModule.chatLine, not public: read as it is now). */
    static Component coreLine(String format, String name, String words) throws Exception {
        Method m = Class.forName("dev.stsv.core.profile.ProfileModule").getDeclaredMethod("chatLine", String.class, Component.class, Component.class, Component.class);
        m.setAccessible(true);
        return (Component) m.invoke(null, format, null, Component.text(name), Component.text(words));
    }

    @Test
    void hisChatLineIsTheCoresChatLine() throws Exception {
        Field def = Class.forName("dev.stsv.core.profile.ProfileModule").getDeclaredField("DEFAULT_CHAT_FORMAT");
        def.setAccessible(true);
        assertEquals(def.get(null), Look.DEFAULT_CHAT_FORMAT);
        GsonComponentSerializer json = GsonComponentSerializer.gson();
        for (String f : new String[]{null, "", "  ", "{title}{name} <gray>»</gray> {message}", "{name} » {message}", "<gray>{name}: {message}",
                "<{name}> {message}", "{title}<white>{name}</white><dark_gray>:</dark_gray> {message}", "{name} {nothing} {message}", "{name} {message} {", "[{title}] {name}: {message} <gray>(…)"}) {
            for (String words : new String[]{"내일도 이 시간에 사람 많아요?", "<red>a</red> {name} {message}"}) {
                Component mine = Look.chatLine(f, "sipsam_", words), core = coreLine(f, "sipsam_", words);
                assertEquals(json.serialize(core), json.serialize(mine), "format " + f);
            }
        }
        assertEquals("sipsam_ » 석탄은 어디 가야 많이 나와요?",
                PlainTextComponentSerializer.plainText().serialize(Look.chatLine(null, "sipsam_", "석탄은 어디 가야 많이 나와요?")));
    }

    @Test
    void acrosticReadsFirstCharacters() {
        assertEquals("가나", Acrostic.of(List.of("  가방", "", "나무")));
        assertFalse(Acrostic.spells(List.of("내일", "비", "석탄", "어제"), "내비석어디"));       // a question taken out
        assertFalse(Acrostic.spells(List.of("내일", "석탄", "비", "어제", "디"), "내비석어디"));  // the order changed
        assertFalse(Acrostic.spells(firsts(), null));
        assertEquals("😀a", Acrostic.of(List.of("😀 hi", "a")));   // one character, not half of one
    }

    // ---- the night's minute

    @Test
    void theMinuteIsTheSameForTheSameNight() {
        LocalDate d = LocalDate.of(2026, 11, 22);
        assertEquals(Schedule.offset(d, 22, 2, 13), Schedule.offset(d, 22, 2, 13));
        assertEquals(Schedule.planned(d, 22, 2, 13), Schedule.planned(d, 22, 2, 13));
    }

    @Test
    void theMinuteIsInsideTheWindowAlsoAcrossMidnight() {
        Set<Integer> seen = new HashSet<>();
        Set<Integer> hours = new HashSet<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 800; i++, d = d.plusDays(1)) {
            int o = Schedule.offset(d, 22, 2, 13);
            assertTrue(o >= 0 && o < 240, "offset " + o);
            seen.add(o);
            LocalDateTime at = Schedule.planned(d, 22, 2, 13);
            hours.add(at.getHour());
            assertTrue(at.getHour() >= 22 || at.getHour() < 2, "at " + at);
            // the planned moment belongs to its own night, whichever side of midnight it falls on
            assertEquals(Optional.of(d), Schedule.night(at, 22, 2));
            assertEquals(at.getHour() >= 22 ? d : d.plusDays(1), at.toLocalDate());
        }
        assertEquals(Set.of(22, 23, 0, 1), hours, "every hour of the window gets its nights");
        assertTrue(seen.size() > 150, "nights differ: " + seen.size());
    }

    @Test
    void theMinuteInAWindowThatDoesNotCrossMidnight() {
        LocalDate d = LocalDate.of(2026, 3, 1);
        for (int i = 0; i < 200; i++, d = d.plusDays(1)) {
            LocalDateTime at = Schedule.planned(d, 1, 5, 7);
            assertEquals(d, at.toLocalDate());
            assertTrue(at.getHour() >= 1 && at.getHour() < 5);
        }
        assertEquals(240, Schedule.minutes(22, 2));
        assertEquals(240, Schedule.minutes(1, 5));
        assertEquals(60, Schedule.minutes(23, 0));
        assertEquals(1440, Schedule.minutes(3, 3));   // the whole day
        assertEquals(240, Schedule.minutes(22, 26));  // hours past 24 are read round the clock
    }

    @Test
    void anotherSeedAnotherMinute() {
        int differ = 0;
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 100; i++, d = d.plusDays(1)) if (Schedule.offset(d, 22, 2, 13) != Schedule.offset(d, 22, 2, 14)) differ++;
        assertTrue(differ > 90, "differ " + differ);
    }

    @Test
    void whichNightAMomentBelongsTo() {
        LocalDate d = LocalDate.of(2026, 11, 22);
        assertEquals(Optional.of(d), Schedule.night(d.atTime(22, 0), 22, 2));
        assertEquals(Optional.of(d), Schedule.night(d.atTime(23, 59), 22, 2));
        assertEquals(Optional.of(d), Schedule.night(d.plusDays(1).atTime(0, 30), 22, 2), "after midnight it is still last night");
        assertEquals(Optional.of(d), Schedule.night(d.plusDays(1).atTime(1, 59), 22, 2));
        assertEquals(Optional.empty(), Schedule.night(d.plusDays(1).atTime(2, 0), 22, 2));
        assertEquals(Optional.empty(), Schedule.night(d.atTime(21, 59), 22, 2));
        assertEquals(Optional.empty(), Schedule.night(d.atTime(12, 0), 22, 2));
        // a window inside one day
        assertEquals(Optional.of(d), Schedule.night(d.atTime(1, 0), 1, 5));
        assertEquals(Optional.empty(), Schedule.night(d.atTime(5, 0), 1, 5));
        assertEquals(Optional.empty(), Schedule.night(d.atTime(0, 59), 1, 5));
        // the whole day, opening at 3
        assertEquals(Optional.of(d), Schedule.night(d.atTime(3, 0), 3, 3));
        assertEquals(Optional.of(d.minusDays(1)), Schedule.night(d.atTime(2, 59), 3, 3));
    }

    @Test
    void theNextNight() {
        LocalDate d = LocalDate.of(2026, 11, 22);
        assertEquals(d, Schedule.next(d.atTime(12, 0), 22, 2), "this evening's");
        assertEquals(d, Schedule.next(d.atTime(23, 0), 22, 2));
        assertEquals(d, Schedule.next(d.plusDays(1).atTime(1, 0), 22, 2), "still inside last night's");
        assertEquals(d.plusDays(1), Schedule.next(d.plusDays(1).atTime(2, 0), 22, 2));
        assertEquals(d.plusDays(1), Schedule.next(d.atTime(6, 0), 1, 5), "today's is over");
        assertEquals(d, Schedule.next(d.atTime(0, 30), 1, 5));
    }

    @Test
    void oneVisitANightWhateverHappens() {
        LocalDate d = LocalDate.of(2026, 11, 22);
        String night = d.toString(), before = d.minusDays(1).toString();
        LocalDateTime at = Schedule.planned(d, 22, 2, 13);
        // nothing before the window, nothing before his minute, then he begins: at the minute or any time after it that night
        assertEquals(Schedule.Due.NOTHING, Schedule.due(d.atTime(15, 0), 22, 2, 13, null, 0, 5));
        assertEquals(Schedule.Due.NOTHING, Schedule.due(d.atTime(21, 59), 22, 2, 13, before, 5, 5));
        if (at.isAfter(d.atTime(22, 0))) assertEquals(Schedule.Due.NOTHING, Schedule.due(at.minusMinutes(1), 22, 2, 13, before, 5, 5));
        assertEquals(Schedule.Due.BEGIN, Schedule.due(at, 22, 2, 13, before, 5, 5));
        assertEquals(Schedule.Due.BEGIN, Schedule.due(at, 22, 2, 13, null, 0, 5), "the very first night");
        assertEquals(Schedule.Due.BEGIN, Schedule.due(d.plusDays(1).atTime(1, 59), 22, 2, 13, before, 2, 5), "nobody was here until the last minute");
        // once begun it is never begun again that night (a restart, everyone gone and back): it only goes on, until all is asked
        for (LocalDateTime t : List.of(at, at.plusMinutes(3), d.plusDays(1).atTime(1, 59))) {
            for (int asked = 0; asked < 5; asked++) assertEquals(Schedule.Due.GO_ON, Schedule.due(t, 22, 2, 13, night, asked, 5), t + " " + asked);
            assertEquals(Schedule.Due.NOTHING, Schedule.due(t, 22, 2, 13, night, 5, 5));
            assertEquals(Schedule.Due.NOTHING, Schedule.due(t, 22, 2, 13, night, 9, 5), "questions were taken out of the file meanwhile");
        }
        // the window closed on a broken visit: it is over; the next night is a new one, at its own minute
        assertEquals(Schedule.Due.NOTHING, Schedule.due(d.plusDays(1).atTime(2, 0), 22, 2, 13, night, 2, 5));
        assertEquals(Schedule.Due.NOTHING, Schedule.due(d.plusDays(1).atTime(12, 0), 22, 2, 13, night, 2, 5));
        assertEquals(Schedule.Due.BEGIN, Schedule.due(Schedule.planned(d.plusDays(1), 22, 2, 13), 22, 2, 13, night, 2, 5));
        // an admin called him in the afternoon: that is this coming night's visit. It goes on if broken off, and he does not come again
        assertEquals(Schedule.Due.GO_ON, Schedule.due(d.atTime(15, 5), 22, 2, 13, night, 1, 5));
        assertEquals(Schedule.Due.NOTHING, Schedule.due(d.atTime(15, 30), 22, 2, 13, night, 5, 5));
        assertEquals(Schedule.Due.NOTHING, Schedule.due(at, 22, 2, 13, night, 5, 5));
        assertEquals(Schedule.Due.NOTHING, Schedule.due(d.plusDays(1).atTime(1, 59), 22, 2, 13, night, 5, 5));
        // every minute of a whole night and day: exactly one moment asks to begin when each beginning is saved
        String saved = before;
        int begins = 0;
        for (LocalDateTime t = d.atTime(2, 0); t.isBefore(d.plusDays(1).atTime(2, 0)); t = t.plusMinutes(1)) {
            if (Schedule.due(t, 22, 2, 13, saved, 5, 5) == Schedule.Due.BEGIN) {
                begins++;
                assertEquals(at, t);
                saved = Schedule.next(t, 22, 2).toString();
            }
        }
        assertEquals(1, begins);
    }

    // ---- whispers

    @Test
    void whisperCommands() {
        List<String> l = Whispers.VANILLA;
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "안녕하세요")), Whispers.parse("/w sipsam_ 안녕하세요", l));
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "거기 있어요?")), Whispers.parse("/msg sipsam_ 거기 있어요?", l));
        assertEquals(Optional.of(new Whispers.Whisper("SIPSAM_", "hi there")), Whispers.parse("/minecraft:tell SIPSAM_ hi there", l));
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "a")), Whispers.parse("/TELL sipsam_ a", l), "labels in any case");
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "a  b")), Whispers.parse("/w sipsam_  a  b ", l));
        assertTrue(Whispers.to(Whispers.parse("/minecraft:tell SIPSAM_ hi", l).orElseThrow(), "sipsam_"), "names in any case");
        // a name in quotes is that name to the game, so it is to him
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "hi")), Whispers.parse("/w \"sipsam_\" hi", l));
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "hi")), Whispers.parse("/w 'sipsam_' hi", l));
        assertEquals(Optional.of(new Whispers.Whisper("\"sipsam_", "hi")), Whispers.parse("/w \"sipsam_ hi", l), "half a quote is no name");
        assertEquals(Optional.of(new Whispers.Whisper("\"\"", "hi")), Whispers.parse("/w \"\" hi", l));
        assertEquals(Optional.of(new Whispers.Whisper("\"", "hi")), Whispers.parse("/w \" hi", l));
    }

    @Test
    void notAWhisper() {
        List<String> l = Whispers.VANILLA;
        assertEquals(Optional.empty(), Whispers.parse("/w sipsam_", l), "no words: the game says how to use it");
        assertEquals(Optional.empty(), Whispers.parse("/w sipsam_ ", l));
        assertEquals(Optional.empty(), Whispers.parse("/w sipsam_    ", l));
        assertEquals(Optional.empty(), Whispers.parse("/w", l));
        assertEquals(Optional.empty(), Whispers.parse("/w  sipsam_ hi", l), "two spaces: the game does not read it either");
        assertEquals(Optional.empty(), Whispers.parse("/say sipsam_ hi", l));
        assertEquals(Optional.empty(), Whispers.parse("/tellraw sipsam_ hi", l));
        assertEquals(Optional.empty(), Whispers.parse("/essentials:msg sipsam_ hi", l), "only the game's own namespace");
        assertEquals(Optional.empty(), Whispers.parse("/관리자 비밀 목록", l));
        assertEquals(Optional.empty(), Whispers.parse(null, l));
        assertEquals(Optional.empty(), Whispers.parse("", l));
    }

    @Test
    void whispersToOthersAreNotHis() {
        List<String> l = Whispers.VANILLA;
        assertFalse(Whispers.to(Whispers.parse("/w Steve hi", l).orElseThrow(), "sipsam_"));
        assertFalse(Whispers.to(Whispers.parse("/w sipsam hi", l).orElseThrow(), "sipsam_"), "a part of his name is not his name");
        assertFalse(Whispers.to(Whispers.parse("/w sipsam_2 hi", l).orElseThrow(), "sipsam_"));
        assertFalse(Whispers.to(Whispers.parse("/w @a hi", l).orElseThrow(), "sipsam_"));
        assertFalse(Whispers.to(Whispers.parse("/w @p[name=sipsam_] hi", l).orElseThrow(), "sipsam_"), "a selector is the game's business");
        assertFalse(Whispers.to(Whispers.parse("/w \"Steve\" hi", l).orElseThrow(), "sipsam_"));
        assertFalse(Whispers.to(Whispers.parse("/w _sipsam_ hi", l).orElseThrow(), "sipsam_"));
        assertEquals(Optional.empty(), Whispers.parse("/execute as @a run w sipsam_ hi", l), "only a whisper typed as one");
        assertEquals(Optional.empty(), Whispers.parse("/teammsg sipsam_ hi", l));
        assertEquals(Optional.empty(), Whispers.parse("/me sipsam_ hi", l));
        assertFalse(Whispers.to(Whispers.parse("/w sipsam_ hi", l).orElseThrow(), null));
    }

    @Test
    void anOwnersAliasIsRead() {
        List<String> l = List.of("w", "msg", "tell", "귓");
        assertEquals(Optional.of(new Whispers.Whisper("sipsam_", "저기요")), Whispers.parse("/귓 sipsam_ 저기요", l));
        assertEquals(Optional.empty(), Whispers.parse("/귓 sipsam_ 저기요", Whispers.VANILLA));
    }

    @Test
    void namesThatLookLikeAccounts() {
        assertTrue(Whispers.account("sipsam_"));
        assertFalse(Whispers.account("십삼"));
        assertFalse(Whispers.account("ab"));
        assertFalse(Whispers.account("a_name_far_too_long"));
        assertFalse(Whispers.account(null));
    }

    // ---- the stones

    static final List<Stones.Spot> ROW = List.of(
            new Stones.Spot("bus", 0, 64, 0), new Stones.Spot("doll", 2, 64, 0), new Stones.Spot("newcomer", 4, 64, 0));

    @Test
    void nearestStoneWithinRange() {
        assertEquals("doll", Stones.nearest(ROW, 2.2, 64, 1, 3.5).orElseThrow().id());
        assertEquals("bus", Stones.nearest(ROW, -3, 64, 0, 3.5).orElseThrow().id());
        assertEquals("newcomer", Stones.nearest(ROW, 7.4, 64, 0, 3.5).orElseThrow().id());
        assertEquals(Optional.empty(), Stones.nearest(ROW, 7.6, 64, 0, 3.5), "just out of range");
        assertEquals(Optional.empty(), Stones.nearest(ROW, 2, 64, 30, 3.5));
        assertEquals(Optional.empty(), Stones.nearest(List.of(), 0, 64, 0, 3.5));
        assertEquals(Optional.empty(), Stones.nearest(ROW, 2, 80, 0, 3.5), "a floor far above is not at the stone");
        assertEquals("doll", Stones.nearest(ROW, 2, 65.5, 0, 3.5).orElseThrow().id(), "standing on a step is");
    }

    @Test
    void whichStoneSomeoneStandsAt() {
        assertEquals(Stones.Where.HIS, Stones.standing(ROW, 4, 64, 1, 3.5, "newcomer", 0.75));
        assertEquals(Stones.Where.HIS, Stones.standing(ROW, 6, 64, 0, 3.5, "newcomer", 0.75));
        assertEquals(Stones.Where.OTHER, Stones.standing(ROW, 0, 64, 1, 3.5, "newcomer", 0.75));
        assertEquals(Stones.Where.OTHER, Stones.standing(ROW, 2, 64, 1, 3.5, "newcomer", 0.75), "his is in range, but they stand at another");
        assertEquals(Stones.Where.NONE, Stones.standing(ROW, 40, 64, 0, 3.5, "newcomer", 0.75));
        assertEquals(Stones.Where.NONE, Stones.standing(List.of(), 4, 64, 0, 3.5, "newcomer", 0.75), "no monument");
        // between his and its neighbour, a little nearer the neighbour: still his, within the slack
        assertEquals(Stones.Where.HIS, Stones.standing(ROW, 2.8, 64, 0, 3.5, "newcomer", 0.75));
        assertEquals(Stones.Where.OTHER, Stones.standing(ROW, 2.8, 64, 0, 3.5, "newcomer", 0));
        // a monument without his stone
        assertEquals(Stones.Where.OTHER, Stones.standing(ROW.subList(0, 2), 2, 64, 0, 3.5, "newcomer", 0.75));
    }

    @Test
    void stonesStandingAsCloseAsTheMonumentSetsThem() {
        // thirteen stones on the monument's own arc: in front of the last one is his, in front of the others is not
        List<Stones.Spot> arc = new java.util.ArrayList<>();
        for (int i = 0; i < 13; i++) {
            double[] at = Stones.arc(0.5, 0.5, 0, i, 13);
            arc.add(new Stones.Spot(i == 12 ? "newcomer" : "s" + i, at[0], 64, at[1]));
        }
        Stones.Spot his = arc.get(12);
        double r = Stones.RADIUS;
        // a step in from his stone, towards the monument's middle; on it; a step behind it
        assertEquals(Stones.Where.HIS, Stones.standing(arc, his.x() + (0.5 - his.x()) / r, 64, his.z() + (0.5 - his.z()) / r, 3.5, "newcomer", 0.4));
        assertEquals(Stones.Where.HIS, Stones.standing(arc, his.x(), 64, his.z(), 3.5, "newcomer", 0.4));
        assertEquals(Stones.Where.HIS, Stones.standing(arc, his.x() - (0.5 - his.x()) / r, 64, his.z() - (0.5 - his.z()) / r, 3.5, "newcomer", 0.4));
        // a step in from every other stone, also from its neighbour: not his
        for (int i = 0; i <= 11; i++) {
            Stones.Spot s = arc.get(i);
            assertEquals(Stones.Where.OTHER, Stones.standing(arc, s.x() + (0.5 - s.x()) / r, 64, s.z() + (0.5 - s.z()) / r, 3.5, "newcomer", 0.4), "stone " + i);
        }
        // between his and its neighbour, a step in: his
        Stones.Spot nb = arc.get(11);
        double mx = (his.x() + nb.x()) / 2, mz = (his.z() + nb.z()) / 2;
        assertEquals(Stones.Where.HIS, Stones.standing(arc, mx + (0.5 - mx) / r, 64, mz + (0.5 - mz) / r, 3.5, "newcomer", 0.4));
        // the monument's own spot, in the middle of the arc, is at no stone
        assertEquals(Stones.Where.NONE, Stones.standing(arc, 0.5, 64, 0.5, 3.5, "newcomer", 0.4));
        assertEquals(Stones.Where.NONE, Stones.standing(arc, 0.5, 64, -20, 3.5, "newcomer", 0.4));
    }

    @Test
    void theArcIsTheMonuments() {
        // looking south (yaw 0): the middle stone straight ahead, eight blocks out; the ends 65 degrees to either side
        double r = Stones.RADIUS, half = Math.toRadians(Stones.SPREAD / 2);
        assertEquals(8, r);
        assertEquals(130, Stones.SPREAD);
        assertArrayEquals(new double[]{0.5, 0.5 + r}, Stones.arc(0.5, 0.5, 0, 6, 13), 1e-9);
        double[] first = Stones.arc(0.5, 0.5, 0, 0, 13), last = Stones.arc(0.5, 0.5, 0, 12, 13);
        assertEquals(r, Math.hypot(first[0] - 0.5, first[1] - 0.5), 1e-9);
        assertEquals(r, Math.hypot(last[0] - 0.5, last[1] - 0.5), 1e-9);
        assertEquals(first[1], last[1], 1e-9);
        assertEquals(-(first[0] - 0.5), last[0] - 0.5, 1e-9);
        assertEquals(0.5 + r * Math.sin(half), first[0], 1e-9);
        assertArrayEquals(new double[]{0.5, 0.5 + r}, Stones.arc(0.5, 0.5, 0, 0, 1), 1e-9);   // one stone: straight ahead
        // looking west (yaw 90): ahead is -x
        assertArrayEquals(new double[]{0.5 - r, 0.5}, Stones.arc(0.5, 0.5, 90, 6, 13), 1e-9);
    }

    /**
     * The arc, its numbers and the stones' tag are copies of secret/Monument's (which this package cannot see into): read
     * out of the monument as it is now, they must still be the same, for every stone and every way the monument can face.
     */
    @Test
    void theArcIsStillTheMonuments() throws Exception {
        Class<?> mon = Class.forName("dev.stsv.economy.secret.Monument");
        assertEquals(field(mon, "RADIUS"), Stones.RADIUS, "Monument.RADIUS changed: change Stones.RADIUS with it");
        assertEquals(field(mon, "SPREAD"), Stones.SPREAD, "Monument.SPREAD changed: change Stones.SPREAD with it");
        assertEquals(field(mon, "SLOTS"), Stones.SLOTS, "Monument.SLOTS changed: change Stones.SLOTS with it");
        assertEquals(13, Stones.SLOTS, "his is the thirteenth");
        assertEquals((NamespacedKey) field(mon, "TAG"), Stones.TAG);
        Method offset = mon.getDeclaredMethod("offset", int.class, int.class, float.class);
        offset.setAccessible(true);
        Method idOf = mon.getDeclaredMethod("idOf", String.class);
        idOf.setAccessible(true);
        Method key = mon.getDeclaredMethod("key", String.class, String.class, NamespacedKey.class);
        key.setAccessible(true);
        Method model = mon.getDeclaredMethod("model", String.class, boolean.class);
        model.setAccessible(true);
        double x = 100.5, z = -40.5;
        for (int n : new int[]{1, 2, 13, 14, 20}) {
            for (float yaw = -360; yaw <= 360; yaw += 45) {
                for (int i = 0; i < n; i++) {
                    double[] o = (double[]) offset.invoke(null, i, n, yaw);
                    assertArrayEquals(new double[]{x + o[0], z + o[1]}, Stones.arc(x, z, yaw, i, n), 1e-9, "stone " + i + "/" + n + " yaw " + yaw);
                }
            }
        }
        // a stone's tag as the monument writes it names his stone, solved or not
        for (boolean solved : new boolean[]{false, true}) {
            String tag = (String) key.invoke(null, "newcomer", "", model.invoke(null, "newcomer", solved));
            assertEquals(idOf.invoke(null, tag), Stones.idOf(tag));
            assertEquals("newcomer", Stones.idOf(tag));
        }
    }

    static Object field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    void theSavedPlaceAndTheStoneTags() {
        assertEquals(Optional.of(new Stones.Place("world", 10.5, 64.0, -3.5, 90f)), Stones.place("world 10.5 64.0 -3.5 90.0"));
        assertEquals(Optional.empty(), Stones.place(null));
        assertEquals(Optional.empty(), Stones.place("world 10.5 64.0"));
        assertEquals(Optional.empty(), Stones.place("world a b c d"));
        assertEquals("newcomer", Stones.idOf("newcomer|0|model/secret_stone"));
        assertEquals("bus", Stones.idOf("bus|-1234|model/secret_stone_solved_bus"));
        assertEquals("old", Stones.idOf("old"));
    }
}
