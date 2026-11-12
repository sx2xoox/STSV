package dev.stsv.economy.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The job menu's description lines: titles, values at the viewer's level, unlocks reached or not. */
class JobDescribeTest {

    @Test
    void valuesFollowTheLevel() {
        assertEquals("<gray>· 광석 추가 드롭 <white>5</white>%", JobModule.describe("광석 추가 드롭 {5~80}%", "#fff", 1, 30));
        assertEquals("<gray>· 광석 추가 드롭 <white>80</white>%", JobModule.describe("광석 추가 드롭 {5~80}%", "#fff", 30, 30));
        assertEquals("<gray>· 희귀 <white>19.7</white>%", JobModule.describe("희귀 {5~35.5}%", "#fff", 15, 30));
    }

    @Test
    void titlesAndUnlocks() {
        assertEquals("<#4fc3f7><b>낚시", JobModule.describe("# 낚시", "#4fc3f7", 5, 30));
        assertEquals("<green>✔</green> <gray>크기 +17%", JobModule.describe("@20 크기 +17%", "#fff", 20, 30));
        assertEquals("<dark_gray>Lv20 크기 +17%", JobModule.describe("@20 크기 +17%", "#fff", 19, 30));
        assertEquals("<dark_gray>Lv20 희귀 7%", JobModule.describe("@20 희귀 {7~7}%", "#fff", 3, 30));
    }
}
