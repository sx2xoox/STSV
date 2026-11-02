package dev.stsv.core.content;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One folder under plugins/STSV/contents/. Layout:
 * <pre>
 * contents/&lt;pack&gt;/
 *   pack.yml          namespace: stsv   (optional, defaults to the folder name)
 *   items/*.yml       custom items and furniture
 *   recipes/*.yml     crafting / furnace / stonecutting recipes
 *   models/*.bbmodel  3D models (Blockbench), converted automatically
 *   textures/*.png    2D item textures, wrapped in an item/generated model automatically
 *   assets/...        raw resource-pack files merged as-is (fonts, sounds, overrides, ...)
 * </pre>
 */
public record ContentPack(String id, String namespace, Path dir) {

    public Path items() { return dir.resolve("items"); }
    public Path recipes() { return dir.resolve("recipes"); }
    public Path models() { return dir.resolve("models"); }
    public Path textures() { return dir.resolve("textures"); }
    public Path assets() { return dir.resolve("assets"); }

    public boolean has(Path p) { return Files.isDirectory(p); }
}
