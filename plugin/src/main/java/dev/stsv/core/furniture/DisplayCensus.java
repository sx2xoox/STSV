package dev.stsv.core.furniture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.crop.CropModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * /관리자 보고서 주변 [반경]: the 3D models (display entities) and click boxes (interaction entities) around the admin, by
 * kind, with how many of them a client standing there draws (within their view distance, at Entity Distance 100%) and
 * about how many model faces that is. A client draws every display on its own each frame, so these are the numbers
 * behind the frame rate near a big base. Crops drawn as blocks (crops.render: blocks) are counted apart: they are part of
 * the chunk's mesh like any block, not entities.
 */
public final class DisplayCensus {

    /** What is counted. Click boxes (interactions) are never drawn; the client only keeps them for aiming. */
    public enum Group {
        CROP("crop", true), SMALL("small", true), LARGE("large", true), MACHINE("machine", true), PART("part", true),
        COSMETIC("cosmetic", true), BOARD("board", true), OTHER_DISPLAY("other-display", true),
        CROP_HIT("crop-hit", false), FURNITURE_HIT("furniture-hit", false), OTHER_HIT("other-hit", false),
        /** crops drawn as blocks (not entities) */
        CROP_BLOCK("crop-block", false);

        public final String key;
        public final boolean drawn;

        Group(String key, boolean drawn) {
            this.key = key;
            this.drawn = drawn;
        }
    }

    /** Per group: how many, how many within their view distance of the viewer, how many of those have no culling box, faces. */
    public static final class Count {
        public int all, inRange, noBox;
        public long faces;
    }

    /** The counts of one look around (pure: filled by {@link #add}). */
    public static final class Tally {
        final Map<Group, Count> counts = new EnumMap<>(Group.class);

        /**
         * @param distance  from the viewer's eyes, blocks
         * @param viewRange the display's view_range (drawn within view_range × 64 blocks at Entity Distance 100%); ignored for click boxes
         * @param box       has a culling box (the client skips it off screen)
         * @param faces     faces of its model (0: not known)
         */
        public void add(Group g, double distance, float viewRange, boolean box, int faces) {
            Count c = counts.computeIfAbsent(g, k -> new Count());
            c.all++;
            if (!g.drawn || distance > viewRange * DisplayView.BLOCKS_PER_RANGE) return;
            c.inRange++;
            if (!box) c.noBox++;
            c.faces += faces;
        }

        /** Things that are blocks, not entities (crops as blocks): only counted. */
        public void addBlocks(Group g, int n) {
            if (n > 0) counts.computeIfAbsent(g, k -> new Count()).all += n;
        }

        public Count get(Group g) {
            return counts.getOrDefault(g, new Count());
        }

        /** Sum over the drawn groups: {all, inRange, noBox, faces}. */
        public long[] drawnTotal() {
            long[] t = new long[4];
            for (Map.Entry<Group, Count> e : counts.entrySet()) {
                if (!e.getKey().drawn) continue;
                t[0] += e.getValue().all;
                t[1] += e.getValue().inRange;
                t[2] += e.getValue().noBox;
                t[3] += e.getValue().faces;
            }
            return t;
        }
    }

    private final STSVPlugin plugin;
    // the same keys the modules put on their entities (FurnitureModule, CosmeticModule)
    private final NamespacedKey piece, partOf, spin, owner, seat, cosmetic, wardrobe;

    public DisplayCensus(STSVPlugin plugin) {
        this.plugin = plugin;
        piece = new NamespacedKey(plugin, "furniture");
        partOf = new NamespacedKey(plugin, "furniture_part_of");
        spin = new NamespacedKey(plugin, "spin");
        owner = new NamespacedKey(plugin, "furniture_owner");
        seat = new NamespacedKey(plugin, "seat_of");
        cosmetic = new NamespacedKey(plugin, "cosmetic_attachment");
        wardrobe = new NamespacedKey(plugin, "wardrobe_entity");
    }

    /** The default and largest radius of a look around (blocks). */
    public static final int RADIUS = 64, MAX_RADIUS = 256;

    public Tally count(Location eye, double radius) {
        Tally t = new Tally();
        Map<String, Integer> facesOf = new HashMap<>();
        for (Entity e : eye.getWorld().getNearbyEntities(eye, radius, radius, radius)) {
            if (e.getLocation().distanceSquared(eye) > radius * radius) continue;
            Group g = groupOf(e);
            if (g == null) continue;
            double distance = e.getLocation().distance(eye);
            if (e instanceof Display d) {
                boolean box = d.getDisplayWidth() > 0 && d.getDisplayHeight() > 0;
                t.add(g, distance, d.getViewRange(), box, d instanceof ItemDisplay i ? faces(i.getItemStack(), facesOf) : 0);
            } else {
                t.add(g, distance, 0, false, 0);
            }
        }
        var crops = plugin.crops();
        if (crops != null && crops.running() && crops.blocks()) {
            t.addBlocks(Group.CROP_BLOCK, crops.countNear(eye, radius));
        }
        return t;
    }

    private Group groupOf(Entity e) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        if (e instanceof Interaction) {
            if (pdc.has(CropModule.TAG)) return Group.CROP_HIT;
            if (pdc.has(owner)) return Group.FURNITURE_HIT;
            return Group.OTHER_HIT;
        }
        if (!(e instanceof Display)) return null;
        if (pdc.has(CropModule.TAG)) return Group.CROP;
        if (pdc.has(piece)) {
            String id = pdc.get(piece, org.bukkit.persistence.PersistentDataType.STRING);
            var spec = id == null ? null : plugin.content().item(id).map(i -> i.furniture()).orElse(null);
            return switch (DisplayView.kindOf(spec)) {
                case SMALL -> Group.SMALL;
                case MACHINE -> Group.MACHINE;
                default -> Group.LARGE;
            };
        }
        if (pdc.has(partOf) || pdc.has(spin)) return Group.PART;
        if (pdc.has(cosmetic) || pdc.has(wardrobe)) return Group.COSMETIC;
        if (pdc.has(seat)) return null; // an empty seat entity while someone sits: nothing to draw
        for (String tag : e.getScoreboardTags()) if (tag.startsWith("stsv-")) return Group.BOARD;
        return Group.OTHER_DISPLAY;
    }

    /** Faces of the model an item display shows (from the built resource pack), 0 if not known. */
    private int faces(ItemStack it, Map<String, Integer> cache) {
        if (it == null || it.isEmpty() || !it.hasItemMeta() || it.getItemMeta().getItemModel() == null) return 0;
        String key = it.getItemMeta().getItemModel().asString();
        return cache.computeIfAbsent(key, k -> {
            var built = plugin.pack() == null ? null : plugin.pack().current();
            var model = built == null ? null : built.itemModel(k);
            return model == null ? 0 : faces(model.json());
        });
    }

    /** Faces of a block/item model: every face of every element. */
    static int faces(JsonObject model) {
        if (model == null || !model.has("elements") || !model.get("elements").isJsonArray()) return 0;
        int n = 0;
        for (JsonElement el : model.getAsJsonArray("elements")) {
            if (!el.isJsonObject()) continue;
            JsonObject faces = el.getAsJsonObject().getAsJsonObject("faces");
            if (faces != null) n += faces.size();
        }
        return n;
    }

    /** Writes the look around for the admin. */
    public void show(CommandSender to, Player at, double radius) {
        Location eye = at.getEyeLocation();
        Tally t = count(eye, radius);
        DisplayView.Settings v = plugin.furniture() == null ? DisplayView.Settings.of(plugin.getConfig()) : plugin.furniture().view();
        to.sendMessage(Lang.msg("models.title", "radius", DisplayView.blocksText(radius), "world", eye.getWorld().getName(),
                "x", eye.getBlockX(), "y", eye.getBlockY(), "z", eye.getBlockZ()));
        for (Group g : Group.values()) {
            Count c = t.get(g);
            if (c.all == 0) continue;
            String name = Lang.raw("models.group." + g.key);
            if (g == Group.CROP_BLOCK) {
                to.sendMessage(Lang.mm("models.line-block", "name", name, "count", c.all));
            } else if (g.drawn) {
                to.sendMessage(Lang.mm("models.line", "name", name, "count", c.all, "drawn", c.inRange, "faces", String.format("%,d", c.faces),
                        "nobox", c.noBox == 0 ? "" : Lang.raw("models.no-box", "count", c.noBox)));
            } else {
                to.sendMessage(Lang.mm("models.line-hit", "name", name, "count", c.all));
            }
        }
        long[] all = t.drawnTotal();
        if (all[0] == 0) to.sendMessage(Lang.mm("models.none"));
        else to.sendMessage(Lang.mm("models.total", "count", all[0], "drawn", all[1], "faces", String.format("%,d", all[3])));
        boolean cropBlocks = plugin.crops() != null && plugin.crops().blocks();
        to.sendMessage(Lang.mm("models.settings", "crops", cropBlocks ? Lang.raw("models.crops-as-blocks") : DisplayView.blocksText(v.blocks(DisplayView.Kind.CROP)),
                "small", DisplayView.blocksText(v.blocks(DisplayView.Kind.SMALL)), "large", DisplayView.blocksText(v.blocks(DisplayView.Kind.LARGE)),
                "machines", DisplayView.blocksText(v.blocks(DisplayView.Kind.MACHINE)),
                "culling", Lang.raw(v.culling() ? "models.culling-active" : "models.culling-inactive"),
                "animate", v.animateRange() > 0 ? Lang.raw("models.animate-range", "blocks", DisplayView.blocksText(v.animateRange()))
                        : Lang.raw("models.animate-always")));
        to.sendMessage(Lang.mm("models.hint"));
    }
}
