package dev.stsv.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentModule;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.auction.AuctionModule;
import dev.stsv.economy.job.JobModule;
import dev.stsv.economy.job.MachineModule;
import dev.stsv.economy.land.LandModule;
import dev.stsv.economy.mail.MailModule;
import dev.stsv.economy.rank.RankModule;
import dev.stsv.economy.request.RequestModule;
import dev.stsv.economy.stats.MetricsModule;
import dev.stsv.economy.market.MarketModule;
import dev.stsv.economy.stall.StallModule;
import dev.stsv.economy.trade.TradeModule;
import dev.stsv.economy.season.SeasonModule;
import dev.stsv.economy.shop.ShopModule;
import dev.stsv.economy.stats.ActivityModule;
import dev.stsv.economy.util.Kv;
import dev.stsv.economy.vanity.VanityModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The economy server: a content plugin on top of the STSV core. Its content pack (items, recipes,
 * menus and the season/shop/job/request data) ships in this jar and is installed by the core.
 */
public final class EcoPlugin extends JavaPlugin {

    private static EcoPlugin instance;

    private final List<StsvModule> modules = new ArrayList<>();
    private final AdminCommand admin = new AdminCommand();
    private Kv kv;
    private SeasonModule season;
    private MailModule mail;
    private ActivityModule activity;
    private ShopModule shop;
    private MarketModule market;
    private AuctionModule auction;
    private TradeModule trade;
    private StallModule stalls;
    private VanityModule vanity;
    private JobModule jobs;
    private MachineModule machines;
    private LandModule land;
    private RequestModule requests;
    private RankModule rank;
    private MetricsModule metrics;
    private TradeRules rules;

    public static EcoPlugin get() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
        // before the core enables: our pack is installed together with the core's
        ContentModule.registerBundle(this, getFile());
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Lang.register(this);
        STSVPlugin core = STSVPlugin.get();
        rules = TradeRules.parse(data("trade.yml"));
        kv = new Kv(core.db());
        kv.init();
        add(season = new SeasonModule(this));
        add(mail = new MailModule(this));
        add(activity = new ActivityModule(this));
        add(jobs = new JobModule(this));
        add(machines = new MachineModule(this));
        add(shop = new ShopModule(this));
        add(stalls = new StallModule(this));
        add(market = new MarketModule(this));
        add(auction = new AuctionModule(this));
        add(trade = new TradeModule(this));
        add(land = new LandModule(this));
        add(requests = new RequestModule(this));
        add(vanity = new VanityModule(this));
        add(rank = new RankModule(this));
        add(metrics = new MetricsModule(this));   // last: its daily report reads what the others did
        Commands.register(this);
        getLogger().info("경제 서버 시작: " + season.day() + "일차" + (season.running() ? "" : " (시즌 시작 전)"));
    }

    private void add(StsvModule m) {
        STSVPlugin.get().modules().register(m);
        modules.add(m);
    }

    @Override
    public void onDisable() {
        STSVPlugin core = STSVPlugin.get();
        List<StsvModule> reversed = new ArrayList<>(modules);
        Collections.reverse(reversed);
        if (core != null) for (StsvModule m : reversed) core.modules().unregister(m.id());
        modules.clear();
    }

    void command(String name, TabExecutor ex) {
        PluginCommand c = getCommand(name);
        if (c == null) {
            getLogger().warning("plugin.yml 에 없는 명령어: " + name);
            return;
        }
        c.setExecutor(ex);
        c.setTabCompleter(ex);
    }

    /** Testing: wipes everything the economy server keeps (not the money: /관리 초기화 경제). */
    void resetAll(org.bukkit.command.CommandSender by) {
        season.reset();
        shop.reset();
        activity.store().reset();
        mail.store().reset();
        for (Runnable r : resets) r.run();
        for (String k : kv.all().keySet()) if (!k.startsWith("loc.")) kv.remove(k);   // admin-set places stay
        kv.set("season.last-reset", 0);
        by.sendMessage(dev.stsv.core.util.Text.msg("<green>경제 서버 기록을 초기화했습니다."));
        getLogger().warning(by.getName() + " 님이 경제 서버 기록을 초기화했습니다.");
    }

    private final List<Runnable> resets = new ArrayList<>();

    /** Modules add what /경제관리 초기화 wipes. */
    public void onReset(Runnable r) {
        resets.add(r);
    }

    // ------------------------------------------------------------------------------------------

    public AdminCommand admin() { return admin; }
    public Kv kv() { return kv; }
    public SeasonModule season() { return season; }
    public MailModule mail() { return mail; }
    public ActivityModule activity() { return activity; }
    public ShopModule shop() { return shop; }
    public MarketModule market() { return market; }
    public AuctionModule auction() { return auction; }
    public TradeModule trade() { return trade; }
    public StallModule stalls() { return stalls; }
    public TradeRules rules() { return rules; }
    public JobModule jobs() { return jobs; }
    public MachineModule machines() { return machines; }
    public LandModule land() { return land; }
    public RequestModule requests() { return requests; }
    public RankModule rank() { return rank; }
    public MetricsModule metrics() { return metrics; }

    /** Re-reads trade.yml (called by the season module's reload, which runs first). */
    public void reloadRules() {
        rules = TradeRules.parse(data("trade.yml"));
        Lang.register(this);
    }
    public VanityModule vanity() { return vanity; }

    /** The economy content pack (contents/economy). */
    public ContentPack pack() {
        for (ContentPack p : STSVPlugin.get().content().packs()) if (p.namespace().equals("economy")) return p;
        throw new IllegalStateException("contents/economy 콘텐츠 팩이 없습니다");
    }

    /** A data file of the pack (season.yml, shop.yml, ...); empty if missing. */
    public YamlConfiguration data(String file) {
        File f = pack().dir().resolve(file).toFile();
        if (!f.exists()) getLogger().warning("contents/economy/" + file + " 이 없습니다");
        return YamlConfiguration.loadConfiguration(f);
    }

    /** An admin-set place (/경제관리 위치 &lt;이름&gt;), or null. */
    public Location location(String name) {
        String s = kv.get("loc." + name);
        if (s == null) return null;
        String[] p = s.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                p.length > 4 ? Float.parseFloat(p[4]) : 0, p.length > 5 ? Float.parseFloat(p[5]) : 0);
    }

    public void setLocation(String name, Location l) {
        kv.set("loc." + name, l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
    }
}
