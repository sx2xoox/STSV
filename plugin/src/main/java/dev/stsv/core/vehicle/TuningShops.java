package dev.stsv.core.vehicle;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Tuning shops that are buildings (the economy server builds them, as it builds its docks and terminals: /관리자
 * 튜닝샵 설치). The core only knows a tuning shop as a place where /튜닝 works; a content plugin that can build one
 * registers itself here ({@link VehicleModule#tuningShops}) and the core's /관리자 튜닝샵 hands 설치, 제거 and 목록 on to it.
 */
public interface TuningShops {

    /** Is this point inside a built shop (where a vehicle may be tuned)? */
    boolean inside(Location at);

    /** Builds a shop of this name where the sender stands (it tells them how it went). */
    void install(CommandSender sender, String name);

    /** Removes the shop of this name; false when there is none (nothing was said). */
    boolean remove(CommandSender sender, String name);

    /** Puts a shop's blocks and decorations again; false when there is none. */
    boolean redecorate(CommandSender sender, String name);

    /** The built shops' names. */
    List<String> names();

    /** Tells the sender the built shops, a line each. */
    void list(CommandSender sender);
}
