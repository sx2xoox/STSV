package dev.stsv.core.hud;

import org.junit.jupiter.api.Test;

class MemProbeTest {
    @Test
    void probe() throws Exception {
        HudSheets bundled = HudSheets.load();
        for (var cfg : new Object[][]{{"bundled", bundled.panelLetters()}, {"1024", bundled.sheet("hangul").names().stream().map(n -> n.codePointAt(0)).limit(1024).toList()}}) {
            @SuppressWarnings("unchecked") java.util.Collection<Integer> l = (java.util.Collection<Integer>) cfg[1];
            HudLayout layout = new HudLayout(HudLayout.panelSheets(bundled, l), HudLayout.Settings.defaults());
            java.util.Map<String, Long> by = new java.util.TreeMap<>();
            int providers = 0;
            for (var font : layout.fonts().values()) for (var e : font.getAsJsonArray("providers")) {
                var p = e.getAsJsonObject();
                if (!p.get("type").getAsString().equals("bitmap")) continue;
                String f = p.get("file").getAsString(); f = f.substring(f.lastIndexOf('/') + 1).replace(".png", "");
                HudSheets.Sheet s = layout.sheets().sheet(f);
                by.merge(f, 4L * s.cols() * s.cellW() * s.rows() * s.cellH(), Long::sum);
                providers++;
            }
            System.out.println("PROBE " + cfg[0] + " total=" + HudPack.clientMemory(layout) + " providers=" + providers + " " + by);
        }
    }
}
