package dev.stsv.economy.bus;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 노선도 판 (README "마을버스"): a small board on the inside of a shelter's back wall, the stops of every line through the
 * stop in order with this one marked ("○ 갈매기 쉼터 → ● 여기 → ○ 등대 앞"), a few each side. One text display per stop,
 * kept (saved with the chunk) like the sign, found again by its own key.
 */
final class StopBoard {

    static final NamespacedKey BOARD = new NamespacedKey("economy", "bus_stop_board");

    private StopBoard() {}

    /**
     * One line's row: its stops' names in order (already escaped for MiniMessage), the one at `here` marked, at most
     * `around` each side of it ("…" where some are cut off).
     */
    static String row(List<String> names, int here, int around) {
        if (names.isEmpty() || here < 0 || here >= names.size()) return "";
        int from = Math.max(0, here - around), to = Math.min(names.size() - 1, here + around);
        // near an end: the room left over goes to the other side (5 shown when there are 5)
        int want = 2 * around + 1;
        while (to - from + 1 < want && (from > 0 || to < names.size() - 1)) {
            if (from > 0) from--;
            else to++;
        }
        List<String> parts = new ArrayList<>();
        if (from > 0) parts.add("<dark_gray>…</dark_gray>");
        for (int i = from; i <= to; i++) {
            parts.add(i == here ? "<#ffd166><b>● 여기</b></#ffd166>" : "<gray>○ " + names.get(i) + "</gray>");
        }
        if (to < names.size() - 1) parts.add("<dark_gray>…</dark_gray>");
        return String.join(" <dark_gray>→</dark_gray> ", parts);
    }

    /** Where the board hangs: inside the shelter on its back wall, behind the middle, a little under the window's top. */
    static Location at(World w, BusStop s, BusSpec.Stops cfg) {
        double[] p = s.at(0, s.shelterOut(cfg.bayWidth()) + 1.96);
        return new Location(w, p[0], s.y() + 1 + 1.45, p[1], s.facingRoad(), 0);
    }

    /** Shows this text on the stop's board (made when it is not there and its place is loaded). */
    static void show(World w, BusStop s, BusSpec.Stops cfg, net.kyori.adventure.text.Component text) {
        TextDisplay t = find(w, s);
        if (t != null) {
            if (!text.equals(t.text())) t.text(text);
            return;
        }
        Location at = at(w, s, cfg);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        w.spawn(at, TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.FIXED);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(200, 24, 36, 52));
            d.setLineWidth(240);
            d.setViewRange(0.4f);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.4f, 0.4f, 0.4f), new AxisAngle4f()));
            d.getPersistentDataContainer().set(BOARD, PersistentDataType.INTEGER, s.id());
        });
    }

    static TextDisplay find(World w, BusStop s) {
        Location near = new Location(w, s.x(), s.y() + 2, s.z());
        if (!w.isChunkLoaded(near.getBlockX() >> 4, near.getBlockZ() >> 4)) return null;
        for (Entity e : w.getNearbyEntities(near, 16, 8, 16, e -> e instanceof TextDisplay)) {
            Integer id = e.getPersistentDataContainer().get(BOARD, PersistentDataType.INTEGER);
            if (id != null && id == s.id()) return (TextDisplay) e;
        }
        return null;
    }

    static void remove(World w, BusStop s) {
        TextDisplay t = find(w, s);
        if (t != null) t.remove();
    }
}
