package dev.stsv.economy.bus;

import dev.stsv.economy.people.PeopleSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 합승 대화 (bus_scenes.yml): the file, who plays a scene, how often, what it does to the ride; and the 건달's ways. */
class BusScenesTest {

    static BusScenes scenes() {
        List<String> problems = new ArrayList<>();
        BusScenes s = BusScenes.parse(YamlConfiguration.loadConfiguration(new File("content/economy/bus_scenes.yml")), BusTest.spec(), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    static BusScenes parse(String yaml, List<String> problems) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(yaml);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return BusScenes.parse(y, BusTest.spec(), problems);
    }

    @Test
    void theFileIsBigAndSound() {
        BusSpec bus = BusTest.spec();
        BusScenes s = scenes();
        assertTrue(s.scenes().size() >= 120, "scenes: " + s.scenes().size());
        Set<String> titles = new HashSet<>(), cast = new HashSet<>(), shapes = new HashSet<>();
        int threes = 0, callbacks = 0, weather = 0;
        for (BusScenes.Scene x : s.scenes().values()) {
            assertTrue(titles.add(x.title()), "a title twice: " + x.title());
            assertTrue(x.lines().size() >= 4, x.id() + ": a scene of a few lines");
            for (BusScenes.Line l : x.lines()) {
                assertFalse(l.text().isBlank(), x.id());
                assertTrue(l.who() >= 0 && l.who() < x.cast().size(), x.id());
                assertTrue(l.text().length() <= 70, x.id() + ": a bubble line too long: " + l.text());
            }
            for (String t : x.cast()) assertTrue(bus.types().get(t).weight() > 0, x.id() + " " + t);
            assertTrue(x.effect().any(), x.id());
            cast.addAll(x.cast());
            if (x.cast().size() == 3) threes++;
            if (!x.when().seen().isEmpty()) callbacks++;
            if (x.when().rain() || !x.when().months().isEmpty() || x.when().market()) weather++;
            // no near-duplicates: the same cast in the same order says something else every time
            shapes.add(String.join("+", x.cast()) + "|" + x.lines().getFirst().text());
        }
        assertEquals(s.scenes().size(), shapes.size());
        // everyone who rides plays a part somewhere (the secret ??? never)
        for (BusSpec.Type t : bus.types().values()) assertEquals(t.weight() > 0, cast.contains(t.id()), t.id());
        assertTrue(threes >= 10, "three-person scenes: " + threes);
        assertTrue(callbacks >= 8, "running gags: " + callbacks);
        assertTrue(weather >= 8, "rain, season, market day: " + weather);
        // the 건달 is the new star: many scenes, and his ways can be quieted and made to pay
        assertTrue(s.with("thug").size() >= 20, "thug scenes: " + s.with("thug").size());
        assertTrue(s.with("thug").stream().anyMatch(x -> x.effect().pay().contains(x.cast().indexOf("thug"))));
        // the asked-for ones
        for (String id : List.of("thug-wrestler", "thug-police", "rude-complainer", "fan-fan", "drunk-complainer", "grandma-spread", "complainer-phone",
                "police-prankster", "monk-youtuber", "gentleman-crumbs", "student-grandma", "soldier-mom", "interview-uncle", "bride-grandma",
                "santa-kinder", "dogwalker-kid", "shaman-ghost", "magician-prankster", "traveler-market", "thug-chicken", "kid-yeot",
                "youtuber-wrestler", "fisher-haenyeo", "rude-complainer-gentleman")) assertTrue(s.scenes().containsKey(id), id);
        // every kind of effect is used
        boolean[] used = new boolean[10];
        for (BusScenes.Scene x : s.scenes().values()) {
            BusScenes.Effect e = x.effect();
            used[0] |= !e.quiet().isEmpty();
            used[1] |= !e.pay().isEmpty();
            used[2] |= !e.mood().isEmpty();
            used[3] |= !e.leave().isEmpty();
            used[4] |= !e.doze().isEmpty();
            used[5] |= !e.wake().isEmpty();
            used[6] |= !e.tip().isEmpty();
            used[7] |= !e.fare().isEmpty();
            used[8] |= e.calm() > 0;
            used[9] |= e.gift() != null;
        }
        for (int i = 0; i < used.length; i++) assertTrue(used[i], "effect " + i);
        assertEquals(0.12, s.rules().chance(), 1e-9);
        assertEquals(600, s.rules().cooldown());
    }

    @Test
    void badScenesAreCaught() {
        List<String> p = new ArrayList<>();
        parse("""
                scenes:
                  a1: {title: "x", cast: [grandma, nobody], lines: [{a: "안녕"}, {b: "응"}], effect: {mood: {a: 1}}}
                  a2: {title: "x", cast: [grandma], lines: [{a: "안녕"}, {a: "응"}], effect: {mood: {a: 1}}}
                  a3: {title: "x", cast: [grandma, student], lines: [{a: ""}, {c: "응"}], effect: {mood: {a: 1}}}
                  a4: {title: "x", cast: [grandma, student], lines: [{a: "안녕"}, {b: "응"}], effect: {quiet: a}}
                  a5: {title: "x", cast: [grandma, student], lines: [{a: "안녕"}, {b: "응"}], effect: {pay: b}}
                  a6: {title: "x", cast: [grandma, student], lines: [{a: "안녕"}, {b: "응"}], effect: {fly: a}}
                  a7: {title: "x", cast: [grandma, student], lines: [{a: "안녕"}, {b: "응"}]}
                  a8: {title: "x", cast: [ghost, nurse], lines: [{a: "안녕"}, {b: "응"}], effect: {mood: {a: 1}}}
                  a9: {title: "x", cast: [grandma, student], when: {seen: nope}, lines: [{a: "안녕"}, {b: "응"}], effect: {mood: {a: 1}}}
                  b1: {title: "x", cast: [grandma, shades], lines: [{a: "안녕"}, {b: "응"}], effect: {mood: {a: 1}}}
                  b2: {title: "x", cast: [grandma, student], lines: [{a: "안녕"}, {b: "응"}], effect: {mood: {z: 1}}}
                  b3: {title: "x", cast: [grandma, student], when: {foggy: true}, lines: [{a: "안녕"}, {b: "응"}], effect: {mood: {a: 1}}}
                  b4: {title: "x", cast: [grandma, student], lines: [{a: "안녕"}], effect: {mood: {a: 1}}}
                """, p);
        String all = String.join("\n", p);
        for (String k : List.of("a1.cast: 없는 손님 종류 nobody", "a2.cast: 손님 2~3명", "a3.lines: 빈 대사", "a3.lines: 없는 역할 c",
                "a4.effect.quiet: grandma", "a5.effect.pay: student", "a6.effect: 모르는 효과 fly", "a7.effect: 효과가 없음", "a8: 함께 탈 수 없는",
                "a9.when.seen: 없는 장면 nope", "b1.cast: 보통 손님으로 안 나오는 종류 shades", "b2.effect.mood: 없는 역할 z", "b3.when: 모르는 조건 foggy",
                "b4.lines: 대사가 둘 넘게", "b4.lines: b 의 대사가 없음")) assertTrue(all.contains(k), k + "\n" + all);
    }

    static BusScenes.Seat seat(String type) {
        return new BusScenes.Seat(type, 80, true, false, true);
    }

    static final BusScenes.Now NOON = new BusScenes.Now(12, 6, false, false, false);

    @Test
    void aSceneNeedsItsWholeCastAboard() {
        BusScenes s = scenes();
        BusScenes.Scene sc = s.scenes().get("grandma-spread");
        assertNull(BusScenes.cast(sc, List.of(seat("grandma"), seat("student"))));
        assertArrayEquals(new int[]{2, 0}, BusScenes.cast(sc, List.of(seat("spread"), seat("student"), seat("grandma"))));
        // one already in a scene this ride (not free) is not cast again
        assertNull(BusScenes.cast(sc, List.of(new BusScenes.Seat("spread", 80, true, false, false), seat("grandma"))));
        // the scene quiets the 쩍벌남: one already quieted (not loud) does not play it again
        assertNull(BusScenes.cast(sc, List.of(new BusScenes.Seat("spread", 80, false, false, true), seat("grandma"))));
        // two of a kind: two riders of it (a couple boards as two)
        BusScenes.Scene fans = s.scenes().get("fan-fan");
        assertNull(BusScenes.cast(fans, List.of(seat("fan"))));
        assertArrayEquals(new int[]{0, 1}, BusScenes.cast(fans, List.of(seat("fan"), seat("fan"))));
        // a mood condition, a sleeper to wake
        BusScenes.Scene sick = s.scenes().get("nurse-motionsick");
        assertNull(BusScenes.cast(sick, List.of(seat("nurse"), new BusScenes.Seat("motionsick", 80, false, false, true))));
        assertNotNull(BusScenes.cast(sick, List.of(seat("nurse"), new BusScenes.Seat("motionsick", 40, false, false, true))));
        BusScenes.Scene wake = s.scenes().get("student-uncle-wake");
        assertNull(BusScenes.cast(wake, List.of(seat("uncle"), seat("student"))));
        assertNotNull(BusScenes.cast(wake, List.of(seat("uncle"), new BusScenes.Seat("student", 80, false, true, true))));
        // conditions of the world: hours, months, rain, a scene seen before
        List<BusScenes.Seat> santa = List.of(seat("santa"), seat("thug"));
        assertTrue(s.candidates(santa, NOON, Set.of()).isEmpty(), "산타 only in December");
        assertTrue(s.candidates(santa, new BusScenes.Now(12, 12, false, false, false), Set.of()).stream().anyMatch(p -> p.scene().id().equals("thug-santa")));
        assertFalse(s.candidates(santa, new BusScenes.Now(12, 12, false, false, false), Set.of()).stream().anyMatch(p -> p.scene().id().equals("santa-thug-gift")));
        assertTrue(s.candidates(santa, new BusScenes.Now(12, 12, false, false, false), Set.of("thug-santa")).stream()
                .anyMatch(p -> p.scene().id().equals("santa-thug-gift")), "the callback once the first was seen");
        List<BusScenes.Seat> rain = List.of(seat("student"), seat("grandma"));
        assertFalse(s.candidates(rain, NOON, Set.of()).stream().anyMatch(p -> p.scene().id().equals("student-grandma-rain")));
        assertTrue(s.candidates(rain, new BusScenes.Now(12, 6, true, false, false), Set.of()).stream().anyMatch(p -> p.scene().id().equals("student-grandma-rain")));
    }

    @Test
    void unseenScenesComeFirstAndTheRollPicks() {
        BusScenes s = scenes();
        List<BusScenes.Seat> aboard = List.of(seat("student"), seat("grandma"), seat("spread"), seat("crumbs"), seat("phone"));
        List<BusScenes.Pick> all = s.candidates(aboard, NOON, Set.of());
        assertTrue(all.size() >= 4, all.toString());
        String a = all.get(0).scene().id(), b = all.get(1).scene().id();
        assertEquals(s.rules().unseen() * s.weight(s.scenes().get(a), Set.of(a)), s.weight(s.scenes().get(a), Set.of()), 1e-9);
        // with all but one seen, the unseen one is picked far more often
        Set<String> seen = new HashSet<>();
        for (BusScenes.Pick p : all) if (!p.scene().id().equals(b)) seen.add(p.scene().id());
        int hits = 0, n = 2000;
        Random r = new Random(1);
        for (int i = 0; i < n; i++) if (s.pick(aboard, NOON, seen, r.nextDouble()).scene().id().equals(b)) hits++;
        double expect = s.rules().unseen() / (s.rules().unseen() + seen.stream().mapToDouble(id -> s.scenes().get(id).weight()).sum());
        assertEquals(expect, hits / (double) n, 0.05);
        assertNull(s.pick(List.of(seat("shades")), NOON, Set.of(), 0.5));
    }

    @Test
    void aSceneIsAChanceOnceALegWithADriversCooldown() {
        BusScenes s = scenes();
        assertTrue(s.due(-1, 0.1));
        assertFalse(s.due(-1, 0.15));
        assertFalse(s.due(500, 0.0), "within the cooldown");
        assertTrue(s.due(601, 0.0));
    }

    /**
     * How often: 100 rides (a ride = a passenger's usual trip of 3 stop legs of 2 minutes; always a full bus of 8, three
     * riders changing at each stop, kinds as they come at noon on a weekday): a scene now and then, about one in every three
     * or four rides, never every ride; a few an hour at most even driving without a break.
     */
    @Test
    void scenesStayRare() {
        BusSpec bus = BusTest.spec();
        BusScenes s = scenes();
        Random r = new Random(7);
        List<BusSpec.Type> kinds = new ArrayList<>(bus.types().values());
        double[] w = new double[kinds.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) total += w[i] = kinds.get(i).weightAt(Set.of(), 12, false, false, false, 6, 10);
        Set<String> seen = new HashSet<>();
        double last = -1e9, now = 0;
        int played = 0, ridesWith = 0, eligible = 0, legs = 0;
        for (int ride = 0; ride < 100; ride++) {
            List<BusScenes.Seat> aboard = new ArrayList<>();
            for (int i = 0; i < 8; i++) aboard.add(new BusScenes.Seat(draw(kinds, w, total, r).id(), 80, true, false, true));
            boolean any = false;
            for (int leg = 0; leg < 3; leg++) {
                now += 120;
                legs++;
                if (!s.candidates(aboard, NOON, seen).isEmpty()) eligible++;
                if (s.due(now - last, r.nextDouble())) {
                    BusScenes.Pick p = s.pick(aboard, NOON, seen, r.nextDouble());
                    if (p != null) {
                        played++;
                        any = true;
                        last = now;
                        seen.add(p.scene().id());
                        for (int i : p.cast()) aboard.set(i, new BusScenes.Seat(aboard.get(i).type(), 80, false, false, false));
                    }
                }
                for (int k = 0; k < 3; k++) aboard.set(r.nextInt(8), new BusScenes.Seat(draw(kinds, w, total, r).id(), 80, true, false, true));
            }
            if (any) ridesWith++;
        }
        assertTrue(eligible > legs / 3, "a full bus usually has some cast aboard: " + eligible + "/" + legs);
        assertTrue(played >= 15 && played <= 40, "scenes in 100 rides: " + played);
        assertTrue(ridesWith <= 40, "rides with a scene: " + ridesWith);
        double perHour = played / (legs * 2 / 60.0);
        assertTrue(perHour <= 5, "scenes an hour: " + perHour);
    }

    private static BusSpec.Type draw(List<BusSpec.Type> kinds, double[] w, double total, Random r) {
        double at = r.nextDouble() * total;
        for (int i = 0; i < w.length; i++) if ((at -= w[i]) < 0) return kinds.get(i);
        return kinds.getLast();
    }

    @Test
    void linesTakeTheirTime() {
        BusScenes.Rules r = scenes().rules();
        assertEquals(r.min(), r.pause("응"), 1e-9);
        assertEquals(r.max(), r.pause("가".repeat(200)), 1e-9);
        assertTrue(r.pause("아이고 총각, 그 다리 좀 오므리소. 내 앉을 데가 없다") > r.pause("…네"));
    }

    // ------------------------------------------------------------------------------------------
    // What a scene does to the ride (BusModule's static parts)
    // ------------------------------------------------------------------------------------------

    static BusModule.Rider rider(String type) {
        return new BusModule.Rider(null, BusTest.spec().types().get(type), 1, 5, 500, 70);
    }

    @Test
    void quietStopsChatterPranksAndCrumbs() {
        BusScenes s = scenes();
        BusModule.Rider grandma = rider("grandma"), spread = rider("spread");
        List<BusModule.Rider> all = new ArrayList<>(List.of(grandma, spread));
        assertTrue(BusModule.chatters(spread, all, false));
        assertFalse(BusModule.chatters(spread, all, true), "nobody chatters while a scene plays");
        List<String> did = BusModule.applyScene(s.scenes().get("grandma-spread").effect(), new BusModule.Rider[]{grandma, spread}, all);
        assertTrue(did.contains("quiet"));
        assertFalse(BusModule.chatters(spread, all, false), "the 쩍벌남 closed his legs for the rest of the ride");
        assertFalse(BusModule.loud(spread));
        assertEquals(75, grandma.mood, 1e-9);
        // the bell prank: quieted = treated as rung already
        BusModule.Rider police = rider("police"), kid = rider("prankster");
        BusModule.applyScene(s.scenes().get("police-prankster").effect(), new BusModule.Rider[]{police, kid}, List.of(police, kid));
        assertTrue(kid.quiet && kid.pranked);
        // crumbs: the student who cleaned up is quiet (alightTraits leaves no litter for a quiet one)
        BusModule.Rider gent = rider("gentleman"), crumbs = rider("crumbs");
        assertTrue(BusModule.loud(crumbs));
        BusModule.applyScene(s.scenes().get("gentleman-crumbs").effect(), new BusModule.Rider[]{gent, crumbs}, List.of(gent, crumbs));
        assertTrue(crumbs.quiet);
        assertFalse(BusModule.loud(crumbs));
        // the phone man hangs up
        BusModule.Rider comp = rider("complainer"), phone = rider("phone");
        BusModule.applyScene(s.scenes().get("complainer-phone").effect(), new BusModule.Rider[]{comp, phone}, List.of(comp, phone));
        assertFalse(BusModule.chatters(phone, List.of(comp, phone), false));
    }

    @Test
    void leaveMoodCalmTipFareDozeWakeAndPay() {
        BusScenes s = scenes();
        // leave: off at the next stop
        BusModule.Rider ghost = rider("ghost"), thug = rider("thug"), other = rider("resident");
        BusModule.applyScene(s.scenes().get("thug-ghost").effect(), new BusModule.Rider[]{ghost, thug}, List.of(ghost, thug, other));
        assertEquals(-1, thug.dest);
        assertEquals(5, ghost.dest);
        assertEquals(74, other.mood, 1e-9, "others +4");
        // calm: everyone at least that mood; pay
        BusModule.Rider gent = rider("gentleman"), rude = rider("rude"), comp = rider("complainer"), low = rider("student");
        rude.mood = 20;
        low.mood = 10;
        BusModule.applyScene(s.scenes().get("rude-complainer-gentleman").effect(), new BusModule.Rider[]{gent, rude, comp}, List.of(gent, rude, comp, low));
        assertEquals(70, rude.mood, 1e-9);
        assertEquals(70, low.mood, 1e-9);
        assertTrue(rude.paysFull && comp.paysFull);
        assertFalse(gent.paysFull);
        // tip and fare: the 신사 pays for the couple
        BusModule.Rider b1 = rider("bride"), b2 = rider("bride"), g = rider("gentleman");
        BusModule.applyScene(s.scenes().get("bride-gentleman").effect(), new BusModule.Rider[]{b1, b2, g}, List.of(b1, b2, g));
        assertEquals(0, b1.fareX, 1e-9);
        assertEquals(0, b2.fareX, 1e-9);
        assertEquals(3, g.fareX, 1e-9);
        BusModule.Rider soldier = rider("soldier"), mom = rider("mom");
        BusModule.applyScene(s.scenes().get("soldier-mom").effect(), new BusModule.Rider[]{soldier, mom}, List.of(soldier, mom));
        assertEquals(1.5, soldier.tipX, 1e-9);
        // doze and wake
        BusModule.Rider drunk = rider("drunk"), c2 = rider("complainer");
        BusModule.applyScene(s.scenes().get("drunk-complainer").effect(), new BusModule.Rider[]{drunk, c2}, List.of(drunk, c2));
        assertTrue(drunk.dozing && drunk.dozeChecked);
        BusModule.Rider uncle = rider("uncle"), student = rider("student");
        student.dozing = true;
        student.sleepThrough = true;
        BusModule.applyScene(s.scenes().get("student-uncle-wake").effect(), new BusModule.Rider[]{uncle, student}, List.of(uncle, student));
        assertFalse(student.dozing || student.sleepThrough);
        // a gift is told back (the module hands it to the driver)
        BusModule.Rider st = rider("student"), gm = rider("grandma");
        assertTrue(BusModule.applyScene(s.scenes().get("student-grandma").effect(), new BusModule.Rider[]{st, gm}, List.of(st, gm)).contains("gift"));
        // mood stays within a kind's floor and 0..100
        BusModule.Rider vip = rider("vip"), hen = rider("chicken");
        vip.mood = 5;
        BusModule.applyScene(s.scenes().get("vip-chicken").effect(), new BusModule.Rider[]{vip, hen}, List.of(vip, hen));
        assertEquals(0, vip.mood, 1e-9);
        assertEquals(0.5, vip.tipX, 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // 건달
    // ------------------------------------------------------------------------------------------

    @Test
    void theThug() {
        BusSpec bus = BusTest.spec();
        BusSpec.Type t = bus.types().get("thug");
        assertEquals("건달", t.name());
        assertEquals(3.4, t.weight(), 1e-9);
        assertEquals(-4, t.x().scare(), 1e-9);
        assertEquals(0.5, t.x().freeload(), 1e-9);
        assertEquals(Set.of("police", "wrestler"), t.x().freeloadUnless());
        assertEquals(3, t.skins().size());
        for (String k : List.of("board", "chat", "freeload", "behave", "jolt", "bump", "turn", "smooth", "late", "waited", "give-up", "view", "missed", "bell", "thanks"))
            assertFalse(t.talk().getOrDefault(k, List.of()).isEmpty(), k);
        assertTrue(bus.types().get("police").x().brave());
        assertTrue(bus.types().get("wrestler").x().brave());
        assertEquals(List.of("board", "freeload", "thanks"), bus.previewSequence(t));
        assertTrue(BusScenes.nuisance(t) && BusScenes.payTrouble(t));
    }

    @Test
    void theThugScaresAndHushes() {
        BusModule.Rider chatty = rider("chatty"), student = rider("student"), police = rider("police"), thug = rider("thug");
        List<BusModule.Rider> all = new ArrayList<>(List.of(chatty, student, police));
        assertTrue(BusModule.chatters(chatty, all, false));
        all.add(thug);
        assertEquals(2, BusModule.scare(all, thug));
        assertEquals(80, chatty.mood, 1e-9, "the 수다쟁이 아줌마 never below her floor");
        assertEquals(68, student.mood, 1e-9, "-4 × 학생's mood 0.5");
        assertEquals(70, police.mood, 1e-9, "the brave 경찰 is not scared");
        assertFalse(BusModule.chatters(chatty, all, false), "hushed while he glares");
        assertTrue(BusModule.chatters(police, all, false), "the 경찰 keeps talking");
        assertTrue(BusModule.chatters(thug, all, false), "he talks himself");
        // someone boarding while he glares is uneasy too
        BusModule.Rider office = rider("office");
        all.add(office);
        assertEquals(1, BusModule.scare(all, office));
        assertEquals(66, office.mood, 1e-9);
        // a scene quiets him: nobody hushed, nobody scared
        thug.quiet = true;
        assertTrue(BusModule.chatters(chatty, all, false));
        BusModule.Rider late = rider("office");
        all.add(late);
        assertEquals(0, BusModule.scare(all, late));
    }

    @Test
    void theThugPaysOnlyWhenHeMinds() {
        BusModule.Rider thug = rider("thug"), student = rider("student");
        assertTrue(BusModule.freeloads(thug, List.of(student), 0.1));
        assertFalse(BusModule.freeloads(thug, List.of(student), 0.9));
        assertFalse(BusModule.behaves(thug, List.of(student)));
        // a 경찰 or 씨름 선수 aboard: he pays, all polite
        assertFalse(BusModule.freeloads(thug, List.of(student, rider("police")), 0.0));
        assertTrue(BusModule.behaves(thug, List.of(rider("wrestler"))));
        // a scene made him pay up
        thug.paysFull = true;
        assertFalse(BusModule.freeloads(thug, List.of(student), 0.0));
        assertFalse(BusModule.freeloads(student, List.of(thug), 0.0), "nobody else freeloads");
    }

    // ------------------------------------------------------------------------------------------
    // 인물 도감: 장면
    // ------------------------------------------------------------------------------------------

    @Test
    void theCodexListsTheScenesSeenOfAPerson() {
        BusScenes s = scenes();
        PeopleSpec.SceneRows none = PeopleSpec.sceneRows(s, "thug", Set.of(), 5);
        assertEquals(s.with("thug").size(), none.all());
        assertEquals(0, none.seen());
        assertTrue(none.titles().isEmpty());
        List<String> ids = s.with("thug").stream().map(BusScenes.Scene::id).toList();
        PeopleSpec.SceneRows some = PeopleSpec.sceneRows(s, "thug", Set.of(ids.get(0), ids.get(1), "student-grandma"), 5);
        assertEquals(2, some.seen());
        assertEquals(List.of(s.scenes().get(ids.get(0)).title(), s.scenes().get(ids.get(1)).title()), some.titles());
        PeopleSpec.SceneRows many = PeopleSpec.sceneRows(s, "thug", new HashSet<>(ids), 5);
        assertEquals(5, many.titles().size());
        assertEquals(ids.size() - 5, many.more());
        assertTrue(PeopleSpec.SCENES_TABLE.contains("eco_people_scenes"));
        // every 장면 title fits a lore line, the scene ids fit the table
        for (BusScenes.Scene x : s.scenes().values()) {
            assertTrue(x.id().length() <= 64, x.id());
            assertTrue(x.title().length() <= 24, x.title());
        }
    }
}
