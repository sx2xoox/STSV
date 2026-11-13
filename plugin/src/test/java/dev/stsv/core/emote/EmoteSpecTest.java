package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Channel;
import dev.stsv.core.emote.EmoteRig.Pose;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** emotes.yml: how it is read, how a pose is found between keyframes, and the shipped file. */
class EmoteSpecTest {

    static Map<String, EmoteSpec> parse(String yaml, List<String> problems) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return EmoteSpec.parse(y, problems::add);
    }

    static final String WAVE = """
            emotes:
              wave:
                name: "손 흔들기"
                length: 20
                step: 2
                loop: false
                camera: { around: 20, up: 10, distance: 3 }
                pose:
                  chest: [5, 0, 0]
                  arms: [-10, 20, 30]
                  feet: [1, 0, 2]
                keyframes:
                  - { at: 0 }
                  - { at: 10, ease: sine, arm_r: [-160, 0, 20], head: [0, 30, 0] }
                  - { at: 15, ease: in, arm_r: [-160, 0, -20], root: [0, 2, 0] }
              sway:
                length: 8
                legs: planted
                keyframes:
                  - { at: 2, ease: out, hips: [0, 0, 10] }
                  - { at: 6, hips: [0, 0, -10], knees: [0.5, 0, 1] }
            """;

    @Test
    void readsAnEmote() throws Exception {
        List<String> problems = new ArrayList<>();
        Map<String, EmoteSpec> emotes = parse(WAVE, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("wave", "sway"), List.copyOf(emotes.keySet()), "in the file's order");
        EmoteSpec wave = emotes.get("wave");
        assertEquals("손 흔들기", wave.name());
        assertEquals(20, wave.length());
        assertEquals(2, wave.step());
        assertFalse(wave.loop());
        assertFalse(wave.planted());
        assertEquals(new EmoteSpec.Camera(20, 10, 3), wave.camera());
        assertEquals(3, wave.keyframes().size());

        // the base pose is under every keyframe; both sides at once: the right one mirrored
        Pose first = wave.keyframes().get(0).pose();
        assertArrayEquals(new double[]{5, 0, 0}, first.get(Channel.CHEST));
        assertArrayEquals(new double[]{-10, 20, 30}, first.get(Channel.ARM_L));
        assertArrayEquals(new double[]{-10, -20, -30}, first.get(Channel.ARM_R), "a rotation mirrors its yaw and roll");
        assertArrayEquals(new double[]{1, 0, 2}, first.get(Channel.FOOT_L));
        assertArrayEquals(new double[]{-1, 0, 2}, first.get(Channel.FOOT_R), "a place mirrors its x");
        assertArrayEquals(new double[]{0, 0, 1}, first.get(Channel.KNEE_L), "knees point forward unless told");
        assertArrayEquals(new double[]{0, 0, 0}, first.get(Channel.HEAD));
        // a keyframe changes only what it names
        EmoteSpec.Keyframe second = wave.keyframes().get(1);
        assertEquals(10, second.at());
        assertEquals(EmoteSpec.Ease.SINE, second.ease());
        assertArrayEquals(new double[]{-160, 0, 20}, second.pose().get(Channel.ARM_R));
        assertArrayEquals(new double[]{-10, 20, 30}, second.pose().get(Channel.ARM_L));
        assertArrayEquals(new double[]{0, 30, 0}, second.pose().get(Channel.HEAD));
        assertArrayEquals(new double[]{5, 0, 0}, second.pose().get(Channel.CHEST));
        assertArrayEquals(new double[]{0, 2, 0}, wave.keyframes().get(2).pose().get(Channel.ROOT));

        // what is left out
        EmoteSpec sway = emotes.get("sway");
        assertEquals("sway", sway.name());
        assertEquals(2, sway.step());
        assertTrue(sway.loop());
        assertTrue(sway.planted());
        assertEquals(EmoteSpec.Camera.DEFAULT, sway.camera());
        assertArrayEquals(new double[]{-0.5, 0, 1}, sway.keyframes().get(1).pose().get(Channel.KNEE_R));
    }

    @Test
    void reportsWhatItCannotRead() throws Exception {
        List<String> problems = new ArrayList<>();
        Map<String, EmoteSpec> emotes = parse("""
                emotes:
                  Bad Id: { length: 4 }
                  odd:
                    length: 10
                    legs: glued
                    pose:
                      tail: [1, 2, 3]
                      head: [1, 2]
                      chest: [a, 0, 0]
                    keyframes:
                      - { at: 4, ease: bouncy, hips: [1, 0, 0] }
                      - { at: 2 }
                      - { at: 10 }
                      - { at: 6, hips: [3, 0, 0] }
                  empty:
                    length: 5
                """, problems);
        assertEquals(List.of("odd", "empty"), List.copyOf(emotes.keySet()));
        assertEquals(8, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("Bad Id")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("tail")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("head")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("chest")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("bouncy")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("at 2")), "out of order");
        assertTrue(problems.stream().anyMatch(p -> p.contains("at 10")), "at the length or later");
        assertTrue(problems.stream().anyMatch(p -> p.contains("legs")));
        EmoteSpec odd = emotes.get("odd");
        assertFalse(odd.planted());
        assertEquals(List.of(4, 6), odd.keyframes().stream().map(EmoteSpec.Keyframe::at).toList(), "the keyframes that could be read");
        assertEquals(EmoteSpec.Ease.LINEAR, odd.keyframes().get(0).ease());
        // no keyframes: the base pose, held
        EmoteSpec empty = emotes.get("empty");
        assertEquals(1, empty.keyframes().size());
        assertEquals(Pose.REST, empty.poseAt(3));
        assertTrue(parse("items: {}", problems).isEmpty(), "a file without emotes");
    }

    @Test
    void easing() {
        for (EmoteSpec.Ease e : EmoteSpec.Ease.values()) {
            assertEquals(0, e.at(0), 1e-12, e.name());
            assertEquals(1, e.at(1), 1e-12, e.name());
            for (double u = 0; u < 1; u += 0.05) assertTrue(e.at(u + 0.05) > e.at(u), e + " goes on");
        }
        assertEquals(0.5, EmoteSpec.Ease.LINEAR.at(0.5), 1e-12);
        assertEquals(0.5, EmoteSpec.Ease.SINE.at(0.5), 1e-12);
        assertEquals(0.25, EmoteSpec.Ease.SINE.at(1 / 3.0), 1e-12);
        assertEquals(0.25, EmoteSpec.Ease.IN.at(0.5), 1e-12);
        assertEquals(0.75, EmoteSpec.Ease.OUT.at(0.5), 1e-12);
    }

    @Test
    void posesBetweenKeyframes() throws Exception {
        Map<String, EmoteSpec> emotes = parse(WAVE, new ArrayList<>());
        EmoteSpec wave = emotes.get("wave"), sway = emotes.get("sway");

        // played once: on a keyframe, between two (eased by the later one), held before the first and after the last
        assertEquals(wave.keyframes().get(0).pose(), wave.poseAt(-3));
        assertEquals(wave.keyframes().get(0).pose(), wave.poseAt(0));
        assertEquals(wave.keyframes().get(1).pose(), wave.poseAt(10));
        assertArrayEquals(new double[]{-85, -10, -5}, wave.poseAt(5).get(Channel.ARM_R), 1e-9, "sine, half way: half");
        assertArrayEquals(new double[]{0, 7.5, 0}, wave.poseAt(10 / 3.0).get(Channel.HEAD), 1e-9, "sine, a third of the way: a quarter");
        assertArrayEquals(new double[]{-160, 0, 20 - 40 * 0.16}, wave.poseAt(12).get(Channel.ARM_R), 1e-9, "in: (2/5)^2");
        assertArrayEquals(new double[]{0, 2 * 0.16, 0}, wave.poseAt(12).get(Channel.ROOT), 1e-9);
        assertSame(wave.keyframes().get(2).pose(), wave.poseAt(15));
        assertSame(wave.keyframes().get(2).pose(), wave.poseAt(19.5));
        assertSame(wave.keyframes().get(2).pose(), wave.poseAt(400));
        assertFalse(wave.finished(19));
        assertTrue(wave.finished(20));
        assertTrue(wave.finished(21));

        // a loop: from the last keyframe on to the first, round and round
        assertFalse(sway.finished(1_000_000));
        assertArrayEquals(new double[]{0, 0, 10}, sway.poseAt(2).get(Channel.HIPS), 1e-9);
        assertArrayEquals(new double[]{0, 0, 0}, sway.poseAt(4).get(Channel.HIPS), 1e-9, "linear to the keyframe at 6");
        assertArrayEquals(new double[]{0, 0, -10}, sway.poseAt(6).get(Channel.HIPS), 1e-9);
        // 6 -> 2 of the next round is four ticks, eased "out" (the first keyframe's): at 0, half way: 0.75
        assertArrayEquals(new double[]{0, 0, -10 + 20 * 0.75}, sway.poseAt(0).get(Channel.HIPS), 1e-9);
        assertArrayEquals(new double[]{0, 0, -10 + 20 * 0.4375}, sway.poseAt(7).get(Channel.HIPS), 1e-9, "a quarter of the way: 1 - 0.75^2");
        assertArrayEquals(new double[]{0.5 - 0.5 * 0.75, 0, 1}, sway.poseAt(0).get(Channel.KNEE_L), 1e-9, "every channel blends");
        for (double t : new double[]{0, 1.5, 2, 5.25, 7.875}) {
            assertEquals(sway.poseAt(t), sway.poseAt(t + 8), "one round later");
            assertEquals(sway.poseAt(t), sway.poseAt(t + 8 * 1000), "a thousand rounds later");
        }
        // no jump where the round closes
        double before = sway.poseAt(7.999).get(Channel.HIPS)[2], after = sway.poseAt(8).get(Channel.HIPS)[2];
        assertEquals(before, after, 0.01);
    }

    @Test
    void theShippedEmotes() {
        Map<String, EmoteSpec> emotes = EmoteRigTest.shipped();
        EmoteSpec twerk = emotes.get("twerk");
        assertTrue(twerk != null, "the first emote");
        assertEquals("트월킹", twerk.name());
        assertTrue(twerk.loop() && twerk.planted());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (EmoteSpec e : emotes.values()) {
            assertTrue(names.add(e.name().toLowerCase(java.util.Locale.ROOT)), "two emotes named " + e.name());
            assertFalse(e.name().isBlank() || e.name().equals("그만") || e.name().equals("목록"), e.id() + ": a name that is a command word");
            assertEquals(0, e.length() % e.step(), e.id() + ": the length is whole steps (the round closes on a sent pose)");
            assertTrue(e.step() >= 1 && e.step() <= 4, e.id() + ": step");
        }
        // the twerk: the hips go up and down about three times a second, the upper body keeps still (hands on knees)
        int bounces = 0;
        for (int t = 0; t < twerk.length(); t++) {
            double a = twerk.poseAt(t - 1).get(Channel.HIPS)[0], b = twerk.poseAt(t).get(Channel.HIPS)[0], c = twerk.poseAt(t + 1).get(Channel.HIPS)[0];
            if (b > a && b >= c) bounces++;
            for (Channel still : new Channel[]{Channel.ROOT, Channel.CHEST, Channel.HEAD, Channel.ARM_L, Channel.ARM_R, Channel.FOOT_L, Channel.FOOT_R}) {
                assertArrayEquals(twerk.poseAt(0).get(still), twerk.poseAt(t).get(still), 1e-9, still + " keeps still (only what moves is sent)");
            }
        }
        double perSecond = bounces * 20.0 / twerk.length();
        assertTrue(perSecond >= 2.5 && perSecond <= 3.5, perSecond + " bounces a second");
        Pose stance = twerk.poseAt(0);
        assertTrue(stance.get(Channel.CHEST)[0] >= 40, "bent far forward");
        assertTrue(stance.get(Channel.CHEST)[0] + stance.get(Channel.HEAD)[0] < 0, "the head looks ahead, a little up");
        assertTrue(stance.get(Channel.ROOT)[1] < -2, "knees bent");
        assertTrue(stance.get(Channel.FOOT_L)[0] > 2 && stance.get(Channel.FOOT_R)[0] < -2, "feet apart");
    }
}
