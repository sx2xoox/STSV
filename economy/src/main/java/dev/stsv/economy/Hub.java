package dev.stsv.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.land.Land;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The economy's part of the 통합 메뉴 (/메뉴, Shift+F): while the economy runs, the core's stsv:hub opens
 * economy:hub (contents/economy/menus/hub.yml: every core button plus the economy's features), and these
 * {placeholders} fill it. All in memory (no database reads): the hub is drawn on every open.
 */
final class Hub {

    static final String MENU = "economy:hub";

    private Hub() {}

    static void register(EcoPlugin eco) {
        STSVPlugin core = STSVPlugin.get();
        core.menus().override(dev.stsv.core.gui.HubModule.MENU, MENU);
        // the minigame pages (/미니게임) with the economy's dock
        core.menus().override("stsv:hub_minigame", "economy:hub_minigame");
        core.menus().override("stsv:hub_circuit", "economy:hub_circuit");
        core.hub().provide(EcoPlugin.ADMIN_OWNER, p -> values(eco, p));
    }

    static void unregister() {
        STSVPlugin core = STSVPlugin.get();
        if (core == null) return;
        if (core.menus() != null) {
            core.menus().override(dev.stsv.core.gui.HubModule.MENU, null);
            core.menus().override("stsv:hub_minigame", null);
            core.menus().override("stsv:hub_circuit", null);
        }
        if (core.hub() != null) core.hub().unprovide(EcoPlugin.ADMIN_OWNER);
    }

    private static Map<String, String> values(EcoPlugin eco, Player p) {
        UUID u = p.getUniqueId();
        String jobId = eco.jobs().job(u);
        var job = jobId == null ? null : eco.jobs().spec().jobs().get(jobId);
        Land land = eco.land().store().index().home(u);
        Set<String> events = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        for (ActiveEvent e : eco.events().active()) if (e.runningAt(now)) events.add(Text.plain(eco.events().name(e.type())));
        return Map.of(
                "job", job == null ? Lang.raw("hub.no-job") : job.color() + job.name(),
                "level", String.valueOf(eco.jobs().level(u)),
                "land", land == null ? Lang.raw("hub.no-land") : Lang.raw("hub.land", "chunks", land.size()),
                "free", String.valueOf(eco.land().rules().free(1)),
                "events", events.isEmpty() ? Lang.raw("hub.none") : String.join(", ", events));
    }
}
