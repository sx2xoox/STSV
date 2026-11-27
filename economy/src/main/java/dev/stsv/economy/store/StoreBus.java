package dev.stsv.economy.store;

import dev.stsv.core.util.Lang;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The 편의점 and the 마을버스 (기획안 §9, store.yml bus-link): a store with a stop near it sends its customers to wait there
 * with their bag; "다음 버스 언제 와요?" is answered with the nearest running bus's driver; a driver who buys a 캔커피 keeps
 * their passengers awake a while; kinds served often in the store know the driver on the bus.
 */
final class StoreBus implements BusModule.Watcher {

    private final StoreModule m;

    StoreBus(StoreModule m) {
        this.m = m;
    }

    private BusModule bus() {
        return m.eco().bus();
    }

    /** The nearest stop within the radius of a store's middle (null: none). */
    BusStop near(StoreModule.Shop shop) {
        BusModule b = bus();
        if (b == null || m.life() == null) return null;
        double[] mid = shop.site.middle();
        BusStop best = null;
        double bestD = m.life().bus().radius();
        for (BusStop s : b.stops()) {
            if (!s.world().equals(shop.site.world())) continue;
            double d = Math.hypot(s.x() - mid[0], s.z() - mid[1]);
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** A customer who bought something walked out: now and then they go to wait at the stop, the bag (or what they bought) in hand. */
    void carry(StoreModule.Shop shop, String type, List<String> bought) {
        BusModule b = bus();
        if (b == null || ThreadLocalRandom.current().nextDouble() >= m.life().bus().carry()) return;
        BusStop s = near(shop);
        if (s == null) return;
        String hold = "economy:store_bag";
        if (bought.size() == 1 && m.spec().products().containsKey(bought.getFirst())) hold = m.spec().products().get(bought.getFirst()).item();
        b.fromStore(s.id(), type, hold, m.life().bus().most());
    }

    /** "다음 버스 언제 와요?": the line the customer says back (the nearest running bus's driver by name, or none). */
    String answer(StoreModule.Shop shop) {
        BusModule b = bus();
        StoreLife.BusLink link = m.life().bus();
        Player best = null;
        double bestD = Double.MAX_VALUE;
        if (b != null) {
            double[] mid = shop.site.middle();
            for (Player d : b.drivers()) {
                Location l = d.getLocation();
                double dist = l.getWorld().getName().equals(shop.site.world()) ? Math.hypot(l.getX() - mid[0], l.getZ() - mid[1]) : 1e9 + bestD;
                if (dist < bestD) {
                    bestD = dist;
                    best = d;
                }
            }
        }
        List<String> lines = link.lines(best == null ? "none" : "driver");
        if (lines.isEmpty()) return "";
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        return best == null ? line : line.replace("{driver}", best.getName());
    }

    /** A player customer bought these: a bus driver with a 캔커피 keeps their passengers awake a while. */
    void bought(Player p, List<String> items) {
        BusModule b = bus();
        if (b == null || !items.contains("coffee_can") || !b.driving(p.getUniqueId())) return;
        StoreLife.BusLink link = m.life().bus();
        b.coffee(p.getUniqueId(), System.currentTimeMillis() + link.coffeeMinutes() * 60_000L, link.coffeeDoze());
        p.sendMessage(Lang.msg("store.coffee-driver", "m", link.coffeeMinutes()));
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.6f, 1.2f);
    }

    /** On the bus: a kind this driver served often in a store knows them. */
    @Override
    public String boardLine(Player driver, String type) {
        if (m.life() == null || m.book() == null) return null;
        StoreLife.BusLink link = m.life().bus();
        if (m.book().kind(driver.getUniqueId(), type) < link.greetAt()) return null;
        if (ThreadLocalRandom.current().nextDouble() >= 0.35) return null;   // not every time
        List<String> lines = link.lines("greet");
        return lines.isEmpty() ? null : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }
}
