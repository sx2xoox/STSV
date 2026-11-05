package dev.stsv.rpg.stat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatBlockTest {

    @Test
    void addsAndCaps() {
        StatBlock b = new StatBlock().add(Stat.CRIT_CHANCE, 0.4).add(new StatBlock().add(Stat.CRIT_CHANCE, 0.4));
        assertEquals(0.8, b.raw(Stat.CRIT_CHANCE), 1e-9);
        assertEquals(0.6, b.get(Stat.CRIT_CHANCE), 1e-9);
        assertEquals(0, new StatBlock().add(Stat.DEFENSE, -5).get(Stat.DEFENSE));
        assertEquals(1e6, new StatBlock().add(Stat.ATTACK, 1e6).get(Stat.ATTACK), "no cap on attack");
    }

    @Test
    void serializeRoundTrip() {
        StatBlock b = new StatBlock().add(Stat.ATTACK, 12).add(Stat.CRIT_CHANCE, 0.05).add(Stat.RESIST_FIRE, 0.1);
        String s = b.serialize();
        assertEquals("attack=12.0;crit_chance=0.05;resist_fire=0.1", s);
        StatBlock back = StatBlock.parse(s);
        for (Stat st : Stat.values()) assertEquals(b.raw(st), back.raw(st), 1e-12, st.id());
    }

    @Test
    void parseSkipsUnknownAndBadValues() {
        StatBlock b = StatBlock.parse("attack=5;old_stat=9;defense=x;;max_health=50");
        assertEquals(5, b.raw(Stat.ATTACK));
        assertEquals(50, b.raw(Stat.MAX_HEALTH));
        assertEquals(0, b.raw(Stat.DEFENSE));
        assertTrue(StatBlock.parse(null).isEmpty());
    }

    @Test
    void fourteenStatRowsWithResistAsOne() {
        Set<String> rows = new HashSet<>();
        for (Stat s : Stat.values()) rows.add(s.name().startsWith("RESIST_") ? "RESIST" : s.name());
        assertEquals(14, rows.size(), "the plan's stat table has 14 rows");
    }

    @Test
    void formats() {
        assertEquals("1,234", Stat.ATTACK.format(1234.4));
        assertEquals("5%", Stat.CRIT_CHANCE.format(0.05));
        assertEquals("12.5%", Stat.ATTACK_SPEED.format(0.125));
        assertEquals("150%", Stat.CRIT_DAMAGE.format(1.5));
        assertEquals("3/초", Stat.HEALTH_REGEN.format(3));
    }
}
