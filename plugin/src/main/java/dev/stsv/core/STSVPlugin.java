package dev.stsv.core;

import dev.stsv.core.boss.BossModule;
import dev.stsv.core.command.ProfileCommand;
import dev.stsv.core.command.StsvCommand;
import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.core.command.WardrobeCommand;
import dev.stsv.core.cosmetic.CosmeticModule;
import dev.stsv.core.effect.ItemEffectsModule;
import dev.stsv.core.skin.SkinModule;
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
    private DatabaseModule database;
    private dev.stsv.core.gui.menu.MenuModule menus;
    private ContentModule content;
    private RecipeModule recipes;
    private FurnitureModule furniture;
    private PackModule pack;
    private SkinModule skins;
    private CosmeticModule cosmetics;
    private BossModule bosses;
    private dev.stsv.core.profile.ProfileModule profiles;
    private dev.stsv.core.hud.HudModule hud;
    private dev.stsv.core.economy.EconomyModule economy;
    private ItemBrowser itemBrowser;
    private RecipeEditor recipeEditor;

    public static STSVPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        dev.stsv.core.util.Lang.load(this);

        modules = new ModuleManager(this);
        // Order matters: content first (others read it), resource pack last (it needs every item).
        modules.register(database = new DatabaseModule());
        modules.register(content = new ContentModule());
        modules.register(menus = new dev.stsv.core.gui.menu.MenuModule());
        modules.register(hud = new dev.stsv.core.hud.HudModule());
        modules.register(recipes = new RecipeModule());
        modules.register(furniture = new FurnitureModule());
        modules.register(skins = new SkinModule());
        modules.register(cosmetics = new CosmeticModule());
        modules.register(profiles = new dev.stsv.core.profile.ProfileModule());
        modules.register(economy = new dev.stsv.core.economy.EconomyModule());
        modules.register(new ItemEffectsModule());
        modules.register(bosses = new BossModule());
        modules.register(pack = new PackModule());
        modules.enableAll();

        itemBrowser = new ItemBrowser(this);
        Bukkit.getPluginManager().registerEvents(itemBrowser, this);
        recipeEditor = new RecipeEditor(this);
        Bukkit.getPluginManager().registerEvents(recipeEditor, this);

        StsvCommand command = new StsvCommand(this);
        PluginCommand cmd = getCommand("관리");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
        ProfileCommand profileCommand = new ProfileCommand(this);
        for (String name : new String[]{"프로필", "명예의전당"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(profileCommand);
                pc.setTabCompleter(profileCommand);
            }
        }
        dev.stsv.core.command.EconomyCommand economyCommand = new dev.stsv.core.command.EconomyCommand(this);
        for (String name : new String[]{"돈", "송금", "수표"}) {
            PluginCommand ec = getCommand(name);
            if (ec != null) {
                ec.setExecutor(economyCommand);
                ec.setTabCompleter(economyCommand);
            }
        }
        PluginCommand wardrobe = getCommand("옷장");
        if (wardrobe != null) wardrobe.setExecutor(new WardrobeCommand(this));
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
    public Db db() { return database.db(); }
    public ContentModule content() { return content; }
    public dev.stsv.core.gui.menu.MenuModule menus() { return menus; }
    public RecipeModule recipes() { return recipes; }
    public FurnitureModule furniture() { return furniture; }
    public PackModule pack() { return pack; }
    public SkinModule skins() { return skins; }
    public CosmeticModule cosmetics() { return cosmetics; }
    public BossModule bosses() { return bosses; }
    public dev.stsv.core.profile.ProfileModule profiles() { return profiles; }
    public dev.stsv.core.hud.HudModule hud() { return hud; }
    public dev.stsv.core.economy.EconomyModule economy() { return economy; }
    public ItemBrowser itemBrowser() { return itemBrowser; }
    public RecipeEditor recipeEditor() { return recipeEditor; }
}
