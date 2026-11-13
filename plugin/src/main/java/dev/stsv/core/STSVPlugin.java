package dev.stsv.core;

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
    private dev.stsv.core.cosmetic.GachaModule gacha;
    private dev.stsv.core.profile.ProfileModule profiles;
    private dev.stsv.core.hud.HudModule hud;
    private dev.stsv.core.economy.EconomyModule economy;
    private dev.stsv.core.crop.CropModule crops;
    private dev.stsv.core.achievement.AchievementModule achievements;
    private dev.stsv.core.gui.HubModule hub;
    private dev.stsv.core.world.WorldsModule worlds;
    private dev.stsv.core.tpa.TpaModule tpa;
    private dev.stsv.core.nickname.NicknameModule nicknames;
    private dev.stsv.core.banner.BannerModule banners;
    private dev.stsv.core.vehicle.VehicleModule vehicles;
    private ItemBrowser itemBrowser;
    private final dev.stsv.core.command.AdminRegistry admin = new dev.stsv.core.command.AdminRegistry();
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
        modules.register(new dev.stsv.core.account.AltModule());
        modules.register(content = new ContentModule());
        modules.register(menus = new dev.stsv.core.gui.menu.MenuModule());
        modules.register(worlds = new dev.stsv.core.world.WorldsModule());
        modules.register(new dev.stsv.core.world.ElevatorModule());
        modules.register(tpa = new dev.stsv.core.tpa.TpaModule());
        modules.register(hud = new dev.stsv.core.hud.HudModule());
        modules.register(recipes = new RecipeModule());
        modules.register(furniture = new FurnitureModule());
        modules.register(skins = new SkinModule());
        modules.register(crops = new dev.stsv.core.crop.CropModule());
        modules.register(vehicles = new dev.stsv.core.vehicle.VehicleModule());
        modules.register(cosmetics = new CosmeticModule());
        modules.register(new dev.stsv.core.vehicle.JigeModule());   // after the cosmetics: it draws in their back slot
        modules.register(profiles = new dev.stsv.core.profile.ProfileModule());
        modules.register(economy = new dev.stsv.core.economy.EconomyModule());
        modules.register(nicknames = new dev.stsv.core.nickname.NicknameModule());
        modules.register(banners = new dev.stsv.core.banner.BannerModule());
        modules.register(achievements = new dev.stsv.core.achievement.AchievementModule());
        modules.register(gacha = new dev.stsv.core.cosmetic.GachaModule());
        modules.register(hub = new dev.stsv.core.gui.HubModule());
        modules.register(new ItemEffectsModule());
        modules.register(pack = new PackModule());
        modules.enableAll();

        itemBrowser = new ItemBrowser(this);
        Bukkit.getPluginManager().registerEvents(itemBrowser, this);
        recipeEditor = new RecipeEditor(this);
        Bukkit.getPluginManager().registerEvents(recipeEditor, this);

        StsvCommand command = new StsvCommand(this);
        PluginCommand cmd = getCommand("관리자");
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
        PluginCommand wardrobe = getCommand("치장");
        if (wardrobe != null) wardrobe.setExecutor(new WardrobeCommand(this));
        PluginCommand gachaCommand = getCommand("뽑기");
        if (gachaCommand != null) gachaCommand.setExecutor(gacha);
        PluginCommand ach = getCommand("업적");
        if (ach != null) ach.setExecutor((sender, cmdObj, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player p) {
                if (args.length > 0 && (args[0].equals("순위") || args[0].equalsIgnoreCase("top"))) achievements.showRanking(p);
                else achievements.open(p);
            }
            else sender.sendMessage(dev.stsv.core.util.Lang.msg("common.players-only"));
            return true;
        });
        PluginCommand menu = getCommand("메뉴");
        if (menu != null) menu.setExecutor((sender, cmdObj, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player p) hub.open(p);
            else sender.sendMessage(dev.stsv.core.util.Lang.msg("common.players-only"));
            return true;
        });
        for (String name : new String[]{"건축월드", "야생월드"}) {
            PluginCommand travel = getCommand(name);
            if (travel != null) travel.setExecutor((sender, cmdObj, label, args) -> {
                if (sender instanceof org.bukkit.entity.Player p) {
                    worlds.travel(p, cmdObj.getName().equals("야생월드") ? dev.stsv.core.world.WorldRules.Kind.WILD : dev.stsv.core.world.WorldRules.Kind.BUILD);
                } else sender.sendMessage(dev.stsv.core.util.Lang.msg("common.players-only"));
                return true;
            });
        }
        for (String name : dev.stsv.core.tpa.TpaModule.COMMANDS) {
            PluginCommand tc = getCommand(name);
            if (tc != null) {
                tc.setExecutor(tpa);
                tc.setTabCompleter(tpa);
            }
        }
        PluginCommand nick = getCommand("닉네임");
        if (nick != null) {
            nick.setExecutor(nicknames);
            nick.setTabCompleter(nicknames);
        }
        PluginCommand nightVision = getCommand("야간투시");
        if (nightVision != null) {
            dev.stsv.core.command.NightVision nv = new dev.stsv.core.command.NightVision(this);
            nightVision.setExecutor(nv);
            getServer().getPluginManager().registerEvents(nv, this);
        }
        PluginCommand sidebar = getCommand("사이드바");
        if (sidebar != null) {
            sidebar.setExecutor(hud);
            sidebar.setTabCompleter(hud);
        }
        PluginCommand titles = getCommand("칭호");
        if (titles != null) {
            dev.stsv.core.command.TitleCommand titleCommand = new dev.stsv.core.command.TitleCommand(this);
            titles.setExecutor(titleCommand);
            titles.setTabCompleter(titleCommand);
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
    public Db db() { return database.db(); }
    public ContentModule content() { return content; }
    public dev.stsv.core.gui.menu.MenuModule menus() { return menus; }
    public RecipeModule recipes() { return recipes; }
    public FurnitureModule furniture() { return furniture; }
    public PackModule pack() { return pack; }
    public SkinModule skins() { return skins; }
    public CosmeticModule cosmetics() { return cosmetics; }
    /** 치장 뽑기 */
    public dev.stsv.core.cosmetic.GachaModule gacha() { return gacha; }
    public dev.stsv.core.profile.ProfileModule profiles() { return profiles; }
    public dev.stsv.core.hud.HudModule hud() { return hud; }
    public dev.stsv.core.economy.EconomyModule economy() { return economy; }
    public dev.stsv.core.crop.CropModule crops() { return crops; }
    public dev.stsv.core.achievement.AchievementModule achievements() { return achievements; }
    /** 통합 메뉴 (Shift+F, /메뉴). */
    public dev.stsv.core.gui.HubModule hub() { return hub; }
    /** 건축월드 / 야생월드. */
    public dev.stsv.core.world.WorldsModule worlds() { return worlds; }
    /** 텔레포트 요청 (/텔포, /텔포여기, /수락, /거절, /텔포취소, /텔포차단). */
    public dev.stsv.core.tpa.TpaModule tpa() { return tpa; }
    /** 닉네임 (/닉네임, /닉네임 색). Names of players for messages: {@link dev.stsv.core.nickname.Names}. */
    public dev.stsv.core.nickname.NicknameModule nicknames() { return nicknames; }
    /** Title banners (admin test feature); null if it failed to start. */
    public dev.stsv.core.banner.BannerModule banners() { return banners; }
    /** 탈것 (경찰차, 1톤 트럭, 골프 카트): content plugins set who may drive whose (trust) and where returned ones go (mailbox). */
    public dev.stsv.core.vehicle.VehicleModule vehicles() { return vehicles; }
    public ItemBrowser itemBrowser() { return itemBrowser; }
    /** What content plugins add to /관리자 (sub commands, 보고서 sections, 초기화 targets). */
    public dev.stsv.core.command.AdminRegistry admin() { return admin; }
    public RecipeEditor recipeEditor() { return recipeEditor; }
}
