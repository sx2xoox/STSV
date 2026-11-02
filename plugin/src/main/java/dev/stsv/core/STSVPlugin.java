package dev.stsv.core;

import dev.stsv.core.command.StsvCommand;
import dev.stsv.core.content.ContentModule;
import dev.stsv.core.furniture.FurnitureModule;
import dev.stsv.core.gui.ItemBrowser;
import dev.stsv.core.gui.RecipeEditor;
import dev.stsv.core.module.ModuleManager;
import dev.stsv.core.pack.PackModule;
import dev.stsv.core.recipe.RecipeModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * STSV core: the shared foundation every season/mode server builds on.
 * Mode plugins depend on this and register their own modules via {@link #modules()}.
 */
public final class STSVPlugin extends JavaPlugin {

    private static STSVPlugin instance;

    private ModuleManager modules;
    private ContentModule content;
    private RecipeModule recipes;
    private FurnitureModule furniture;
    private PackModule pack;
    private ItemBrowser itemBrowser;
    private RecipeEditor recipeEditor;

    public static STSVPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        modules = new ModuleManager(this);
        // Order matters: content first (others read it), resource pack last (it needs every item).
        modules.register(content = new ContentModule());
        modules.register(recipes = new RecipeModule());
        modules.register(furniture = new FurnitureModule());
        modules.register(pack = new PackModule());
        modules.enableAll();

        itemBrowser = new ItemBrowser(this);
        Bukkit.getPluginManager().registerEvents(itemBrowser, this);
        recipeEditor = new RecipeEditor(this);
        Bukkit.getPluginManager().registerEvents(recipeEditor, this);

        StsvCommand command = new StsvCommand(this);
        PluginCommand cmd = getCommand("stsv");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (modules != null) modules.disableAll();
        instance = null;
    }

    public File jarFile() {
        return getFile();
    }

    public ModuleManager modules() { return modules; }
    public ContentModule content() { return content; }
    public RecipeModule recipes() { return recipes; }
    public FurnitureModule furniture() { return furniture; }
    public PackModule pack() { return pack; }
    public ItemBrowser itemBrowser() { return itemBrowser; }
    public RecipeEditor recipeEditor() { return recipeEditor; }
}
