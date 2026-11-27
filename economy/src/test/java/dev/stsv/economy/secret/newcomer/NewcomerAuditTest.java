package dev.stsv.economy.secret.newcomer;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 새로 온 사람, the final inspection (docs/admin/숨은_비밀/새로_온_사람_점검.md): one test per thing that was found and fixed.
 * No test asks a model or the network.
 */
class NewcomerAuditTest {

    static final Record.Truth TRUTH = NewcomerRestoreTest.TRUTH;

    static Filter.Rules chat(boolean lamp) {
        return Filter.Rules.chat(TRUTH, lamp, false, s -> false);
    }

    // ---- 1. the SDK is never a hard dependency of the plugin

    @Test
    void pluginYmlHasNoLibraries() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        assertFalse(yml.contains("libraries:"), "a failed library download stops the WHOLE plugin from loading: the SDK is fetched fail-soft (Sdk)");
        assertFalse(yml.contains("anthropic"));
    }

    @Test
    void onlyClaudeCallNamesTheSdk() throws Exception {
        Path classes = Path.of(Newcomer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> naming = new ArrayList<>();
        try (var walk = Files.walk(classes)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                String bytes = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                if (bytes.contains("com/anthropic")) naming.add(classes.relativize(f).toString());
            }
        }
        assertEquals(List.of("dev/stsv/economy/secret/newcomer/ClaudeCall.class"), naming.stream().map(s -> s.replace('\\', '/')).toList(),
                "every other class loads without the SDK");
    }

    /** The application class loader without the SDK (as a server that could not download it). */
    static final class NoSdk extends ClassLoader {
        NoSdk(ClassLoader parent) {
            super("no-sdk", parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.anthropic.") || name.startsWith("okhttp3.") || name.startsWith("kotlin.")) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void withoutTheSdkHeSpeaksFromHisScripts() throws Exception {
        ClassLoader libs = new URLClassLoader(new URL[0], new NoSdk(getClass().getClassLoader()));
        ClassLoader bridge = Sdk.bridge(libs);
        List<String> problems = new ArrayList<>();
        Optional<NewcomerMind> mind = Sdk.mind(bridge, "claude-opus-5-5", 16000, Duration.ofSeconds(8), problems::add);
        assertTrue(mind.isEmpty(), "no SDK: no mind, and nothing thrown");
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("NoClassDefFoundError") || problems.getFirst().contains("ClassNotFound"), problems.getFirst());
        // and the voice falls back to the scripted mind: he still answers
        Voice v = new Voice(new NewcomerRestoreTest.MapKeep(), "ai.");
        v.set(mind.orElse(null), NewcomerRestoreTest.ON);
        assertFalse(v.active());
        assertEquals("아 네네", v.think(NewcomerRestoreTest.ask("아 네네"), chat(false), 5000, LocalDate.of(2026, 11, 27), () -> 0).or("아 네네"));
        // not ready (nothing fetched in this JVM): no mind either
        assertTrue(Sdk.mind("m", 1024, Duration.ofSeconds(1), s -> {}).isEmpty());
    }

    @Test
    void withTheSdkTheBridgeIsItsOwnClass() throws Exception {
        ClassLoader bridge = Sdk.bridge(getClass().getClassLoader());
        Class<?> c = Class.forName(Sdk.BRIDGE, false, bridge);
        assertNotSame(ClaudeCall.class, c, "defined apart from the plugin's own loader");
        assertEquals(bridge, c.getClassLoader());
        assertTrue(BiFunction.class.isAssignableFrom(c) && AutoCloseable.class.isAssignableFrom(c));
        assertNotNull(c.getConstructor(String.class, long.class, Duration.class, java.util.function.Consumer.class));
    }

    // ---- 2. a call that would come too late is not made at all

    @Test
    void aCallStillQueuedAtItsDeadlineIsNotSent() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        BiFunction<String, String, String> slow = (sys, user) -> {
            sent.incrementAndGet();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "아 네네";
        };
        List<String> problems = new ArrayList<>();
        ClaudeMind m = new ClaudeMind(slow, () -> {}, problems::add);
        NewcomerMind.Ask a = NewcomerRestoreTest.ask("아 네네");
        var f1 = m.think(a, Duration.ofMillis(400));
        var f2 = m.think(a, Duration.ofMillis(400));
        var f3 = m.think(a, Duration.ofMillis(400));   // the pool has two threads: this one waits in the queue past its deadline
        assertNull(f3.get(3, TimeUnit.SECONDS));
        assertNull(f1.get(3, TimeUnit.SECONDS), "too slow");
        assertNull(f2.get(3, TimeUnit.SECONDS));
        hold.countDown();
        Thread.sleep(300);
        assertEquals(2, sent.get(), "the third was never sent");
        m.close();
        // the persona is the system part and the call gets exactly the prompt's two parts
        List<String> got = new ArrayList<>();
        ClaudeMind echo = new ClaudeMind((sys, user) -> {
            got.add(sys);
            got.add(user);
            return "오";
        }, () -> {}, s -> {});
        assertEquals("오", echo.think(a, Duration.ofSeconds(2)).get(2, TimeUnit.SECONDS));
        assertEquals(Prompts.SYSTEM, got.get(0));
        assertEquals(Prompts.user(a), got.get(1));
        echo.close();
    }

    // ---- 3. the filter: no way round it

    @Test
    void hisNameInAnyDisguise() {
        Filter.Rules r = chat(false);
        for (String line : List.of("rmama", "RMAMA 님", "ｒｍａｍａ", "гмама", "rмаmа", "r m a m a", "r.m.a.m.a", "r​mama", "그믐", "그 믐", "ㄱㅡㅁㅡㅁ",
                "그​믐", "rㅡ믐", "그믐달 같아요", "geumeum", "geu meum", "르마마", "알엠에이엠에이")) {
            assertNotNull(Filter.refuse(line, r), line);
        }
        assertEquals("rmama", Filter.keys("그믐"));
        assertEquals("rmama", Filter.keys("ｒｍａｍａ"));
        assertEquals("rmama", Filter.keys("гмама"));
        assertEquals("emdqnf", Filter.keys("등 불"));
        assertEquals("emdqnf", Filter.keys("ㄷㅡㅇㅂㅜㄹ"));
        // and plain lines are not caught by it
        for (String ok : List.of("아 감사합니다 ㅎㅎ", "그만 할게요", "그 마을 가봤어요", "음 잘 모르겠어요", "이 월드 좋네요", "삼일 동안 했어요", "그냥 구경중이에요")) {
            assertNull(Filter.refuse(ok, r), ok);
        }
    }

    @Test
    void theDayInAnyDisguise() {
        Filter.Rules r = chat(false);
        for (String line : List.of("2026", "２０", "①②", "3 월", "13 일", "삼월이었어요", "십삼일째 밤", "이천이십육년", "십일월")) {
            assertEquals("날짜", Filter.refuse(line, r), line);
        }
        assertNull(Filter.refuse("저 3번 물어봤네요 ㅋㅋ", r), "one digit is no day");
    }

    @Test
    void theLampAndTheStoneInAnyDisguise() {
        Filter.Rules r = chat(false);
        for (String line : List.of("등 불", "등.불", "ㄷㅡㅇㅂㅜㄹ", "랜턴 하나", "lamp 하나", "안 꺼지는 거")) {
            assertEquals("등불", Filter.refuse(line, r), line);
        }
        assertNotNull(Filter.refuse("deungbul", r), "romanised (and English-looking: thrown away either way)");
        assertNull(Filter.refuse("등 불 하나 켰어요", chat(true)), "once that line is back");
        assertEquals("비석", Filter.refuse("제 비 석", r));
        assertEquals("보이지 않는 글자", Filter.refuse("안녕‍하세요", r));
        assertEquals("물음표", Filter.refuse("그래요﹖", r), "a small question mark is still one");
        assertEquals("꾸밈", Filter.refuse("‹할일› 이름", r), "the brackets the prompt puts round players' words");
        assertEquals("여러 줄", Filter.refuse("안녕 하세요", r));
    }

    @Test
    void aGuessEchoedBackIsStillCaught() {
        // a player guesses in chat; a model that repeats the guess is thrown away and the scripted line is said
        for (String echoed : List.of("ㅋㅋ 그믐이요", "r m a m a 아니에요", "2026-03 은 아니에요", "등불은 모르겠어요", "ㄱㅡㅁㅡㅁ 이요")) {
            assertEquals("아 네네", NewcomerRestoreTest.said(new NewcomerRestoreTest.Fake(echoed), NewcomerRestoreTest.ON, "아 네네",
                    "혹시 그믐 아니에요? 2026-03-13 등불"), echoed);
        }
    }

    // ---- 4. costs: one player cannot use up the day, the letters have a budget of their own

    @Test
    void onePlayerCannotUseUpTheDay() {
        Voice v = new Voice(new NewcomerRestoreTest.MapKeep(), "ai.");
        NewcomerRestoreTest.Fake f = new NewcomerRestoreTest.Fake("오 그렇군요");
        v.set(f, new Voice.Settings(true, 1, 0, 30, 40, 13, 400, 8000, 3, 300));
        UUID spammer = UUID.randomUUID(), other = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 11, 27);
        NewcomerMind.Ask whisper = new NewcomerMind.Ask(NewcomerMind.Kind.WHISPER, "넵", List.of(), "", List.of(), List.of(), "x");
        for (int i = 0; i < 3; i++) assertTrue(v.think(whisper, Filter.Rules.whisper(TRUTH, false, s -> false), 5000, today, () -> 0, spammer).asked());
        assertFalse(v.think(whisper, Filter.Rules.whisper(TRUTH, false, s -> false), 5000, today, () -> 0, spammer).asked(), "their share is used");
        assertTrue(v.think(whisper, Filter.Rules.whisper(TRUTH, false, s -> false), 5000, today, () -> 0, other).asked(), "someone else's is not");
        assertTrue(v.think(whisper, Filter.Rules.whisper(TRUTH, false, s -> false), 5000, today.plusDays(1), () -> 0, spammer).asked(), "a new day");
    }

    @Test
    void theLettersHaveABudgetOfTheirOwn() {
        NewcomerRestoreTest.MapKeep keep = new NewcomerRestoreTest.MapKeep();
        Voice v = new Voice(keep, "ai.");
        v.set(new NewcomerRestoreTest.Fake("첫 줄\n둘째 줄\n셋째 줄"), new Voice.Settings(true, 1, 0, 30, 40, 13, 1, 8000, 40, 2));
        LocalDate today = LocalDate.of(2026, 11, 27);
        Filter.Rules lr = new Filter.Rules("rmama", List.of("그믐"), true, true, false, Filter.MAX, s -> false);
        NewcomerMind.Ask letter = new NewcomerMind.Ask(NewcomerMind.Kind.LETTER, "", List.of(), "", List.of(), List.of(), "x");
        assertTrue(v.think(NewcomerRestoreTest.ask("넵"), chat(false), 5000, today, () -> 0).asked(), "the day's one call");
        assertTrue(v.think(letter, lr, 5000, today, () -> 0).asked(), "a letter after the day's calls are used");
        assertTrue(v.think(letter, lr, 5000, today, () -> 0).asked());
        assertFalse(v.think(letter, lr, 5000, today, () -> 0).asked(), "past the letters' budget: the scripted letter");
        assertFalse(v.think(letter, lr, 5000, today.plusDays(1), () -> 0).asked(), "the budget is the whole round's, not a day's");
        assertEquals("2", keep.map.get("ai.letters"));
    }

    // ---- 5. the bus: the sign first, then the ticket at that stop

    @Test
    void theSignReadsBrokenAsTheBusComesUp() {
        assertEquals(0, Restore.comingUp(3, 30, null), "no second before: not known to be coming up yet");
        assertEquals(1, Restore.comingUp(3, 30, new double[]{3, 31, 0}), "nearer than a second ago: ahead");
        assertEquals(0, Restore.comingUp(3, 31, new double[]{3, 30, 0}), "going away");
        assertEquals(0, Restore.comingUp(3, 30, new double[]{3, 31, 1}), "one roll an approach");
        assertEquals(0, Restore.comingUp(3, 30, new double[]{4, 31, 0}), "another stop a second ago");
        assertEquals(0, Restore.comingUp(3, Restore.SIGN_NEAR + 1, new double[]{3, Restore.SIGN_NEAR + 2, 0}), "too far to read");
        assertEquals(0, Restore.comingUp(3, Restore.SIGN_FAR - 1, new double[]{3, Restore.SIGN_FAR, 0}), "already there: too late for the sign");
        assertEquals(0, Restore.comingUp(-1, 10, null));
    }

    // ---- 6. the click box is there only while there is something to do

    @Test
    void theStoneTakesClicksOnlyWhenThereIsSomethingToDo() {
        assertFalse(Restore.clickable(false, false, false), "the bare stone: nothing in it eats a click");
        assertTrue(Restore.clickable(true, false, false));
        assertFalse(Restore.clickable(true, true, false), "signed: nothing left to do");
        assertTrue(Restore.clickable(false, false, true), "a tester's own record");
    }

    // ---- 7. a rehearsal leaves nothing for the real night

    @Test
    void openedAnewForgetsWhatWasDoneForTheRecord() {
        Memories m = new Memories(null);
        assertTrue(m.ready(), "no table: nothing to wait for");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        m.update(a, "별하나", n -> n.plusAnswer().plusShard().engraved().witnessed().note("조각을 넣어 줌 (그날)").note("이름을 새겨 줌")
                .note("증인으로 서명함").note("비석을 찾아 줌"));
        m.update(b, "달둘", n -> n.plusTease().plusWhisper());
        m.forgetRecord();
        Memories.Note na = m.of(a);
        assertEquals(1, na.answered(), "that they answered him stays");
        assertEquals(0, na.shards());
        assertFalse(na.named());
        assertFalse(na.signed());
        assertEquals("", na.notes());
        assertEquals(Memories.Kind.ANSWERER, Memories.kind(na), "no longer the witness's empty sheet");
        assertEquals(Memories.Kind.TEASER, Memories.kind(m.of(b)));
        assertEquals("다른 메모", Memories.strip("조각을 넣어 줌 (푼것) / 다른 메모 / 증인으로 서명함"));
    }

    @Test
    void aChangeBeforeTheTableIsReadNeverWipesARow() throws Exception {
        Path file = Files.createTempFile("newcomer-memory", ".db");
        CountDownLatch gate = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean hold = new java.util.concurrent.atomic.AtomicBoolean();
        dev.stsv.core.data.Db db = new dev.stsv.core.data.Db(() -> {
            if (hold.get()) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
        }, dev.stsv.core.data.Db.Dialect.SQLITE, 1, java.util.logging.Logger.getAnonymousLogger(), null);
        try {
            UUID u = UUID.randomUUID();
            Memories before = new Memories(db);
            before.init();
            for (int i = 0; i < 50 && !before.ready(); i++) Thread.sleep(20);
            before.update(u, "별하나", n -> n.plusAnswer().plusAnswer().plusShard());
            // a restart: the table is being read (slowly) when he is whispered to
            Memories after = new Memories(db);
            hold.set(true);
            after.load();
            after.update(u, "별하나", Memories.Note::plusWhisper);
            assertFalse(after.ready(), "not read yet");
            hold.set(false);
            gate.countDown();
            for (int i = 0; i < 100 && !after.ready(); i++) Thread.sleep(20);
            assertTrue(after.ready());
            Memories.Note n = after.of(u);
            assertEquals(2, n.answered(), "what was saved is kept");
            assertEquals(1, n.shards());
            assertEquals(1, n.whispers(), "and the whisper is added on top");
            assertEquals(Memories.Kind.GIVER, Memories.kind(n));
            Memories third = new Memories(db);
            third.load();
            for (int i = 0; i < 100 && !third.ready(); i++) Thread.sleep(20);
            assertEquals(2, third.of(u).answered(), "and so is the row written");
            assertEquals(1, third.of(u).whispers());
        } finally {
            db.close();
            Files.deleteIfExists(file);
        }
    }

    // ---- 8. the letters never go out to nobody

    @Test
    void theLettersWaitUntilEveryoneIsKnown() {
        assertTrue(Vigil.deliverNow(true, false, true, true, true, true, true));
        assertFalse(Vigil.deliverNow(true, false, true, true, true, false, true), "right after a start the table is not read yet");
        assertFalse(Vigil.deliverNow(true, false, true, true, true, true, false), "not written yet");
        assertFalse(Vigil.deliverNow(true, true, true, true, true, true, true), "sent");
        assertFalse(Vigil.deliverNow(true, false, false, true, true, true, true), "opened anew");
        assertFalse(Vigil.deliverNow(true, false, true, false, true, true, true), "not the hour yet");
        assertFalse(Vigil.deliverNow(true, false, true, true, false, true, true), "no mailbox");
    }

    // ---- 9. the signing after the announcement is kept and finished

    @Test
    void aSigningPastItsAnnouncementIsKept() {
        UUID u = UUID.randomUUID();
        Vigil.Pending p = new Vigil.Pending(u, "별\n하나", 2, "Steve");
        Vigil.Pending back = Vigil.Pending.parse(p.text()).orElseThrow();
        assertEquals(u, back.who());
        assertEquals("별 하나", back.hearer(), "one line each");
        assertEquals(2, back.others());
        assertEquals("Steve", back.account());
        assertTrue(Vigil.Pending.parse("not a uuid\nx").isEmpty());
        assertTrue(Vigil.Pending.parse(null).isEmpty());
        assertEquals(0, Vigil.Pending.parse(u + "\nx\n-3").orElseThrow().others());
        assertEquals("x", Vigil.Pending.parse(u + "\nx").orElseThrow().account());
    }

    // ---- 10. what he owes the chat: the lines that matter are never pushed out

    @Test
    void theLinesThatMatterAreNeverPushedOut() {
        String owed = Newcomer.owe(null, "어.. 방금 뭔가 돌아왔어요", false);
        for (int i = 0; i < 20; i++) owed = Newcomer.owe(owed, "어 방금 뭐가 돌아온 것 같아요", false);
        assertEquals("어.. 방금 뭔가 돌아왔어요", owed, "twenty shards while he was away are one passing line");
        owed = Newcomer.owe(owed, Newcomer.WHOLE_DATE, true);
        owed = Newcomer.owe(owed, Newcomer.WHOLE_DATE, true);
        owed = Newcomer.owe(owed, Newcomer.TWO_LINES, true);
        assertEquals(List.of("어.. 방금 뭔가 돌아왔어요", Newcomer.WHOLE_DATE, Newcomer.TWO_LINES), List.of(owed.split("\n")));
    }

    // ---- 11. the 한영키 slip looks like a real one

    @Test
    void theSlipComesBeforeAWordOfThatKey() {
        Random r = new Random(3);
        for (int i = 0; i < 30; i++) {
            assertEquals("저 아까부터 r구경만 하고 있어요", Hanyoung.slip("저 아까부터 구경만 하고 있어요", 'r', r));
            assertEquals("아 오늘도 사람 a많네요", Hanyoung.slip("아 오늘도 사람 많네요", 'a', r));
        }
        for (char c : "rma".toCharArray()) {
            for (int i = 0; i < 30; i++) {
                String line = Hanyoung.line(Hanyoung.LINES, c, List.of(), r);
                assertTrue(Hanyoung.LINES.contains(line));
                if (c != 'm') {
                    String typed = Hanyoung.slip(line, c, r);
                    int at = typed.indexOf(c);
                    assertEquals(c, Hanyoung.key(typed.substring(at + 1)), typed);
                }
            }
        }
        assertEquals('r', Hanyoung.key("구경"));
        assertEquals('a', Hanyoung.key("많네요"));
    }

    @Test
    void theModelNeverSeesHisSlips() {
        assertEquals(List.of("오 그렇구나"), Newcomer.forModel(List.of("오 그렇구나", "아 오늘도 r사람 많네요", "아 한영키", "한영키 ㅋㅋ", "저 a많네요")));
    }

    // ---- 12. the shards: six kinds a line, and what makes a line whole

    @Test
    void sixKindsALineAndWhatCompletesIt() {
        for (String full : List.of(TRUTH.date(), TRUTH.what())) {
            int n = Shards.glyphs(full);
            int whole = 0, sets = 0;
            for (int a = 0; a < 6; a++) {
                for (int b = a + 1; b < 6; b++) {
                    for (int c = b + 1; c < 6; c++) {
                        boolean[] have = new boolean[n];
                        Shards.fill(have, a);
                        Shards.fill(have, b);
                        Shards.fill(have, c);
                        sets++;
                        if (Shards.whole(have)) whole++;
                    }
                }
            }
            assertEquals(20, sets);
            assertTrue(whole >= 2, "at least the two cuts (0 1 2, 3 4 5) of three: " + whole);
            // four of six never leave more than a glyph or two missing, and five always make it whole
            for (int skip = 0; skip < 6; skip++) {
                boolean[] have = new boolean[n];
                for (int k = 0; k < 6; k++) if (k != skip) Shards.fill(have, k);
                assertTrue(Shards.whole(have), "any five of six: " + full + " without " + skip);
            }
        }
        // what a shard says is the truth with gaps: the stone's text and the yml's truth agree with the code
        assertEquals(Record.Truth.WHAT, NewcomerTest.yml().getString("truth.what"));
        assertEquals(Record.Truth.NAME, NewcomerTest.yml().getString("truth.name"));
        assertEquals(Record.Truth.ALIAS, NewcomerTest.yml().getStringList("truth.alias"));
    }

    @Test
    void nothingOfItInTheContentPlayersRead() throws Exception {
        // the content pack (menus, guide, messages, item and title texts players see): only his own files name him
        List<Path> roots = List.of(Path.of("content"), Path.of("src/main/resources"));
        for (Path root : roots) {
            try (var walk = Files.walk(root)) {
                for (Path f : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".md")).toList()) {
                    String rel = root.relativize(f).toString().replace('\\', '/');
                    if (rel.endsWith("secrets/newcomer.yml") || rel.endsWith("items/newcomer.yml")) continue;
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    for (String word : List.of("rmama", "그믐", "꺼지지 않는 등불", "sipsam")) {
                        assertFalse(text.contains(word), f + " names " + word);
                    }
                }
            }
        }
    }

    // ---- 13. every line he may say aloud passes his own rules

    @Test
    void everyLineHeMaySayPassesHisRules() {
        var y = NewcomerTest.yml();
        Filter.Rules found = Filter.Rules.chat(TRUTH, true, true, s -> false);
        List<String> pub = new ArrayList<>();
        for (String k : List.of("ai.sleepy", "ai.goodnight", "hanyoung.lines", "hanyoung.fix", "restored.back", "chat.tired", "sulk.sorry", "typo.fix")) {
            pub.addAll(y.getStringList(k));
        }
        for (String k : List.of("restored.date", "restored.what", "restored.two", "vigil.arrive")) pub.add(y.getString(k));
        pub.add(y.getString("restored.name").replace("{player}", "별하나"));
        for (String l : pub) {
            assertFalse(l.contains("?"), "no \"?\" outside the five questions: " + l);
            assertNull(Filter.refuse(l.replace("{word}", "캐요"), found), l);
        }
        // whispers and letters: never the name or the day
        Filter.Rules w = Filter.Rules.whisper(TRUTH, true, s -> false);
        List<String> wh = new ArrayList<>(y.getStringList("stone.found-lines"));
        wh.addAll(y.getStringList("stone.again-lines"));
        wh.addAll(y.getStringList("vigil.lines").stream().map(s -> s.replace("{player}", "별하나")).toList());
        for (String l : wh) assertNull(Filter.refuse(l, w), l);
        for (Memories.Kind k : Memories.Kind.values()) {
            for (String l : Letters.body(k, "별하나", "날짜 끝자리가")) assertNull(Filter.refuse(l, w), k + ": " + l);
        }
        assertNull(Filter.refuse(Letters.PS, w));
        // the announcement is the other secrets' shape (the server prefix, dark gray), dated that day
        assertEquals("<dark_gray>〔2026-03-13〕 rmama님이 숨은 비밀을 풀었습니다.", Vigil.announcement(y.getString("announce"), TRUTH));
        assertTrue(y.getString("announce").startsWith("<dark_gray>"));
        for (Map.Entry<String, Object> e : Map.<String, Object>of("ai.max-calls-per-player-day", 40, "ai.max-letter-calls", 300).entrySet()) {
            assertEquals(e.getValue(), y.getInt(e.getKey()), e.getKey());
        }
    }

    /** 키 파일 (호스팅 패널처럼 환경 변수를 못 넣는 서버): 첫 줄 (빈 줄 · # 주석 건너뜀), 없거나 비면 null. */
    @Test
    void theKeyFileIsReadFromTheServersFolderOnly(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        assertNull(ClaudeMind.keyFile(dir));
        assertNull(ClaudeMind.keyFile(null));
        java.nio.file.Files.writeString(dir.resolve(ClaudeMind.KEY_FILE), "# 여기에 키 한 줄\n\n   \n");
        assertNull(ClaudeMind.keyFile(dir));
        java.nio.file.Files.writeString(dir.resolve(ClaudeMind.KEY_FILE), "\uFEFF# 주석\n\n  sk-test-not-a-real-key  \n다른 줄\n");
        assertEquals("sk-test-not-a-real-key", ClaudeMind.keyFile(dir));
    }
}
