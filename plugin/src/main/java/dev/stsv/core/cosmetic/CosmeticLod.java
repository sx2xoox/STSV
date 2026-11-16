package dev.stsv.core.cosmetic;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Distance LOD for worn cosmetics (README "치장 거리별 단순화"): each viewer sees the full model only for wearers
 * near them; farther away the same display shows a light "&lt;model&gt;_lod" model with the same outline; beyond
 * {@code far} the client does not draw it at all (Display#setViewRange). The wearer always sees their own
 * full model.
 *
 * The swap is per viewer: the display's item travels in its entity metadata, which CosmeticPackets rewrites
 * on the way out to a viewer in the light band. This class holds the pure parts: the settings, the band
 * decision (with hysteresis, so a viewer walking along the edge does not make the model flicker) and which
 * model is the light one.
 */
public final class CosmeticLod {

    /** Light models are named after the full one with this suffix (models/&lt;name&gt;_lod.bbmodel). */
    static final String SUFFIX = "_lod";

    /**
     * @param near   full model within this many blocks
     * @param margin the band edge moves this far out going light, this far in coming back (no flicker)
     * @param far    not drawn at all beyond this (a client with a larger "entity distance" sees a bit more)
     * @param every  ticks between evaluations of every wearer-viewer pair
     */
    record Settings(double near, double margin, double far, int every) {
        Settings {
            if (!(near > 0) || !(far > near) || margin < 0 || margin >= near || every < 1) {
                throw new IllegalArgumentException("cosmetics.lod: near > 0, far > near, 0 <= margin < near, every >= 1 이어야 함 (near="
                        + near + ", margin=" + margin + ", far=" + far + ", every=" + every + ")");
            }
        }

        static Settings of(ConfigurationSection c) {
            return new Settings(c.getDouble("near"), c.getDouble("margin"), c.getDouble("far"), c.getInt("every"));
        }

        /** Display#setViewRange: the client draws up to range x 64 blocks (x its entity distance setting). */
        float viewRange() {
            return (float) (far / 64.0);
        }
    }

    private CosmeticLod() {}

    /**
     * Whether a viewer sees the light model of a wearer this far away (blocks), given what they saw so far.
     * Going light needs near + margin, coming back needs near - margin.
     */
    static boolean light(boolean wasLight, double distance, Settings s) {
        return wasLight ? distance > s.near() - s.margin() : distance > s.near() + s.margin();
    }

    /** {@link #light} on squared distances (no square root per pair). */
    static boolean lightSquared(boolean wasLight, double distanceSquared, Settings s) {
        double edge = wasLight ? s.near() - s.margin() : s.near() + s.margin();
        return distanceSquared > edge * edge;
    }

    /**
     * The light model of every model that has one, as item model ids: "ns:model/x" -> "ns:model/x_lod" for
     * each models/x_lod.bbmodel next to an x.bbmodel, and a cosmetic item's own id (a gacha cosmetic shows
     * its item, "ns:id", drawn with models/x.bbmodel) -> "ns:model/x_lod".
     */
    public static Map<String, String> table(Collection<ContentPack> packs, Collection<CustomItem> items) throws IOException {
        Map<String, String> out = new HashMap<>();
        Map<String, java.util.Set<String>> lodNames = new HashMap<>();
        for (ContentPack pack : packs) {
            if (!Files.isDirectory(pack.models())) continue;
            java.util.Set<String> names = new java.util.HashSet<>();
            try (Stream<Path> s = Files.list(pack.models())) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(SUFFIX + ".bbmodel")).forEach(n -> {
                    String full = n.substring(0, n.length() - (SUFFIX + ".bbmodel").length());
                    if (Files.exists(pack.models().resolve(full + ".bbmodel"))) names.add(full);
                });
            }
            for (String full : names) out.put(pack.namespace() + ":model/" + full, pack.namespace() + ":model/" + full + SUFFIX);
            lodNames.computeIfAbsent(pack.namespace(), k -> new java.util.HashSet<>()).addAll(names);
        }
        for (CustomItem c : items) {
            if (c.cosmetic() == null || c.model() == null || c.model().kind() != ModelRef.Kind.BBMODEL) continue;
            NamespacedKey key = c.itemModelKey();
            if (key == null) continue;
            String ns = key.getNamespace();
            if (lodNames.getOrDefault(ns, java.util.Set.of()).contains(c.model().value())) {
                out.put(key.asString(), ns + ":model/" + c.model().value() + SUFFIX);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Whether what a wearer shows goes to this viewer as the light model: the wearer is in the viewer's light band,
     * and never on the wearer's own screen.
     *
     * @param owner the wearer of the display (null: not a worn display or pet)
     */
    static boolean lightFor(int viewer, Integer owner, java.util.Set<Integer> lightOwners) {
        return owner != null && owner != viewer && lightOwners.contains(owner);
    }

    /**
     * The item model one viewer is sent for a cosmetic display: its near-fade band and its detail, picked together.
     *
     * @param sent  the model as the server sends it to everyone: a near-fade key of band OTHERS
     *              ("stsv:near_fade/near/ns/path", NearFade) or a plain model when the pack has no near-fade definitions
     * @param band  the viewer's near-fade band (SELF / PET for the owner, OTHERS for anyone else); null: keep the sent one
     * @param light whether the viewer is in the owner's light band (never the owner)
     * @param table the light models (see {@link #table})
     * @return the model to send (the same string when nothing changes); a marked model stays marked, in the given band:
     * a far viewer gets "stsv:near_fade/near/ns/path_lod"
     */
    /**
     * What one viewer is sent for a cosmetic display (CosmeticPackets asks this for every entity data packet of one):
     * the owner gets their own near-fade band (PET for their pet, SELF for what they wear) and always the full model;
     * anyone else the others' band, as the light model while they are in the owner's light band.
     *
     * @param wearer   who wears the display (null: not a worn display)
     * @param petOwner whose pet it is (null: not a pet)
     * @return the model to send, or the same string
     */
    static String forViewer(String sent, int viewer, Integer wearer, Integer petOwner, java.util.Set<Integer> lightOwners,
                            Map<String, String> table) {
        Integer owner = wearer != null ? wearer : petOwner;
        if (owner == null || sent == null) return sent;
        if (owner == viewer) return view(sent, petOwner != null ? NearFade.Band.PET : NearFade.Band.SELF, false, table);
        return view(sent, NearFade.Band.OTHERS, lightFor(viewer, owner, lightOwners), table);
    }

    static String view(String sent, NearFade.Band band, boolean light, Map<String, String> table) {
        NearFade.Marked marked = NearFade.unwrap(sent);
        String model = marked == null ? sent : marked.model();
        String detail = light ? table.getOrDefault(model, model) : model;
        if (marked == null) return detail;
        String out = NearFade.key(band == null ? marked.band() : band, detail);
        return out.equals(sent) ? sent : out;
    }
}
