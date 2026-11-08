package dev.stsv.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentModule;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.auction.AuctionModule;
import dev.stsv.economy.calendar.CalendarModule;
import dev.stsv.economy.event.EventModule;
import dev.stsv.economy.job.JobModule;
import dev.stsv.economy.job.MachineModule;
import dev.stsv.economy.land.LandModule;
import dev.stsv.economy.mail.MailModule;
import dev.stsv.economy.rank.RankModule;
import dev.stsv.economy.request.RequestModule;
import dev.stsv.economy.stats.MetricsModule;
import dev.stsv.economy.market.MarketModule;
import dev.stsv.economy.trade.TradeModule;
import dev.stsv.economy.shop.ShopModule;
import dev.stsv.economy.stock.StockModule;
import dev.stsv.economy.fishing.FishingModule;
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
import java.util.Map;

/**
 * The economy server: a content plugin on top of the STSV core. It runs without end (no seasons). Its
 * content pack (items, recipes, menus and the shop/job/event/request data) ships in this jar and is
 * installed by the core. Its admin tools are sub commands of the core's /관리자.
 */
public final class EcoPlugin extends JavaPlugin {

    private static EcoPlugin instance;

    private final List<StsvModule> modules = new ArrayList<>();
    /** How the economy server's part of /관리자 is labelled. */
    public static final String ADMIN_OWNER = "경제 서버";

    private Kv kv;
    private CalendarModule calendar;
    private EventModule events;
    private MailModule mail;
    private ActivityModule activity;
    private ShopModule shop;
    private MarketModule market;
    private AuctionModule auction;
    private TradeModule trade;
    private VanityModule vanity;
    private JobModule jobs;
    private MachineModule machines;
    private LandModule land;
    private RequestModule requests;
    private dev.stsv.economy.mission.MissionModule missions;
    private RankModule rank;
    private MetricsModule metrics;
    private StockModule stocks;
    private FishingModule fishing;
    private dev.stsv.economy.codex.CodexModule codex;
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
        add(calendar = new CalendarModule(this));
        add(events = new EventModule(this));
        add(mail = new MailModule(this));
        add(activity = new ActivityModule(this));
        add(jobs = new JobModule(this));
        add(machines = new MachineModule(this));
        add(shop = new ShopModule(this));
        add(market = new MarketModule(this));
        add(auction = new AuctionModule(this));
        add(trade = new TradeModule(this));
        add(land = new LandModule(this));
        add(requests = new RequestModule(this));
        add(missions = new dev.stsv.economy.mission.MissionModule(this));
        add(vanity = new VanityModule(this));
        add(stocks = new StockModule(this));
        add(fishing = new FishingModule(this));
        add(codex = new dev.stsv.economy.codex.CodexModule(this));
        add(rank = new RankModule(this));
        add(metrics = new MetricsModule(this));   // last: its daily report reads what the others did
        StallRemoval.run(this, core.db());
        core.economy().feeWaiver(this::feesWaived);   // 수수료 면제 covers /송금 too
        getServer().getPluginManager().registerEvents(new PvpRule(this), this);
        getServer().getPluginManager().registerEvents(new VillagerRule(this), this);
        Commands.register(this);
        AdminCommands.register(this);
        Hub.register(this);
        provideAchievementValues(core);
        Bukkit.getScheduler().runTask(this, () -> rescaleCrops(core));   // first tick: the core has its crop specs and plants
        getLogger().info("경제 서버 시작: " + calendar.day() + "일차 (" + calendar.week() + "주차)");
    }

    /** The old max-age of the crops whose max-age changed (7 -> 8 for the first seven, grape 9 -> 14, muskmelon 9 -> 10, cabbage 8 -> 6). */
    private static final Map<String, Integer> OLD_MAX_AGES = Map.of(
            "economy:tomato", 7, "economy:chili", 7, "economy:strawberry", 7, "economy:lettuce", 7, "economy:cucumber", 7,
            "economy:eggplant", 7, "economy:zucchini", 7, "economy:grape", 9, "economy:muskmelon", 9, "economy:cabbage", 8);

    /** Once: planted crops keep their ripeness across those max-age changes (ages are stored numbers, ripe = age >= max-age). */
    private void rescaleCrops(STSVPlugin core) {
        if (kv.getLong("crops.max-age-1", 0) != 0) return;
        // the crop module did not start (its definitions may be loaded while reading the planted crops failed) or has no
        // crops at all: try again next start, the plants must not miss it
        if (!core.crops().running() || core.crops().specs().isEmpty()) return;
        int n = 0;
        for (Map.Entry<String, Integer> e : OLD_MAX_AGES.entrySet()) n += core.crops().rescale(e.getKey(), e.getValue());
        kv.set("crops.max-age-1", 1);
        if (n > 0) getLogger().info("작물: 성장 단계 수가 바뀐 작물 " + n + "포기의 나이를 맞췄습니다.");
    }

    /** What the 업적 in contents/economy/achievements measure besides stats (value: economy:...). */
    private void provideAchievementValues(STSVPlugin core) {
        var a = core.achievements();
        a.provide("economy:job_level", (p, job) -> job == null ? jobs.level(p.getUniqueId()) : jobs.level(p.getUniqueId(), job));
        a.provide("economy:job_level_max", (p, x) -> jobs.spec().jobs().keySet().stream().mapToInt(j -> jobs.level(p.getUniqueId(), j)).max().orElse(0));
        a.provide("economy:jobs_total", (p, x) -> jobs.spec().jobs().keySet().stream().mapToInt(j -> jobs.level(p.getUniqueId(), j)).sum());
        a.provide("economy:net_worth", (p, x) -> rank.worth(p.getUniqueId()));
        a.provide("economy:land_chunks", (p, x) -> {
            var l = land.store().index().ownedBy(p.getUniqueId());
            return l == null ? 0 : l.chunks().size();
        });
        a.provide("economy:fish_species", (p, x) -> codex.fishSpecies(p.getUniqueId()));
        a.provide("economy:codex", (p, tab) -> codex.count(p.getUniqueId(), tab));
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
        Hub.unregister();
        if (core != null) {
            core.admin().removeAll(ADMIN_OWNER);
            core.achievements().unprovide("economy:");
            core.economy().feeWaiver(null);
            for (StsvModule m : reversed) core.modules().unregister(m.id());
        }
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

    /** Testing: wipes everything the economy server keeps (not the money: /관리자 초기화 경제). Day 1 starts again. */
    void resetAll() {
        shop.reset();
        activity.store().reset();
        mail.store().reset();
        for (Runnable r : resets) r.run();
        // admin-set places stay; so do the crop-age migrations (the planted crops are the core's and stay too) and the
        // one-time press refund (machines upgraded after a reset paid today's prices: a next start must not refund them)
        for (String k : kv.all().keySet()) if (!k.startsWith("loc.") && !k.startsWith("crops.") && !k.startsWith("machines.")) kv.remove(k);
        calendar.reset();
    }

    private final List<Runnable> resets = new ArrayList<>();

    /** Modules add what /관리자 초기화 경제서버 wipes. */
    public void onReset(Runnable r) {
        resets.add(r);
    }

    // ------------------------------------------------------------------------------------------

    public Kv kv() { return kv; }
    public CalendarModule calendar() { return calendar; }
    public EventModule events() { return events; }
    public MailModule mail() { return mail; }
    public ActivityModule activity() { return activity; }
    public ShopModule shop() { return shop; }
    public StockModule stocks() { return stocks; }
    public FishingModule fishing() { return fishing; }
    public dev.stsv.economy.codex.CodexModule codex() { return codex; }
    public MarketModule market() { return market; }
    public AuctionModule auction() { return auction; }
    public TradeModule trade() { return trade; }
    public TradeRules rules() { return rules; }
    public JobModule jobs() { return jobs; }

    /** 수수료 면제: no fees on the 거래소, 경매 or 송금 while it runs. */
    public boolean feesWaived() {
        return events != null && events.isActive("fee_free");
    }
    public MachineModule machines() { return machines; }
    public LandModule land() { return land; }
    public RequestModule requests() { return requests; }
    public dev.stsv.economy.mission.MissionModule missions() { return missions; }
    public RankModule rank() { return rank; }
    public MetricsModule metrics() { return metrics; }

    /** Re-reads trade.yml and the messages (called by the calendar module's reload, which runs first). */
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

    /** A data file of the pack (events.yml, shop.yml, ...); empty if missing. */
    public YamlConfiguration data(String file) {
        File f = pack().dir().resolve(file).toFile();
        if (!f.exists()) getLogger().warning("contents/economy/" + file + " 이 없습니다");
        return YamlConfiguration.loadConfiguration(f);
    }

    /** An admin-set place (/관리자 위치 &lt;이름&gt;), or null. */
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
