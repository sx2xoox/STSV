package dev.stsv.economy;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.auction.AuctionStore;
import dev.stsv.economy.job.JobStore;
import dev.stsv.economy.land.ChunkPos;
import dev.stsv.economy.land.Land;
import dev.stsv.economy.land.LandStore;
import dev.stsv.economy.mail.MailStore;
import dev.stsv.economy.market.MarketStore;
import dev.stsv.economy.request.RequestStore;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.event.EventStore;
import dev.stsv.economy.shop.ShopStore;
import dev.stsv.economy.stats.ActivityStore;
import dev.stsv.economy.util.Kv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Everything the economy server keeps survives a restart, and nothing can be taken twice. SQLite. */
class StoresTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final byte[] ITEM = {1, 2, 3, 4};

    private static Db db(Path dir) throws Exception {
        return DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
    }

    @Test
    void aListingIsSoldOnceEvenWithTwoBuyersAtOnce(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        MarketStore s = new MarketStore(db);
        s.init();
        UUID seller = UUID.randomUUID(), b1 = UUID.randomUUID(), b2 = UUID.randomUUID();
        MarketStore.Listing l = s.add(seller, ITEM, "minecraft:diamond", "다이아몬드", 3, 150, 0, 1000);
        assertEquals(1, s.count(seller));
        CompletableFuture<Boolean> first = s.close(l.id(), MarketStore.SOLD, b1, 10), second = s.close(l.id(), MarketStore.SOLD, b2, 10);
        assertTrue(first.get() ^ second.get(), "exactly one buyer gets it");
        assertTrue(s.active().isEmpty());
        // a buyer who could not pay puts it back, and it can be bought again
        s.reopen(l).get();
        assertEquals(1, s.active().size());
        assertTrue(s.close(l.id(), MarketStore.SOLD, b2, 20).get());
        // the seller was offline: told once on joining
        List<MarketStore.Sold> told = s.untold(seller).get();
        assertEquals(1, told.size());
        assertEquals(150, told.getFirst().price());
        assertTrue(s.untold(seller).get().isEmpty());
        // expiry, and a restart keeps the active ones
        MarketStore.Listing l2 = s.add(seller, ITEM, "minecraft:coal", "석탄", 64, 100, 0, 500);
        assertEquals(List.of(l2.id()), s.expired(600).stream().map(MarketStore.Listing::id).toList());
        MarketStore again = new MarketStore(db);
        again.init();
        assertEquals(1, again.active().size());
        assertArrayEquals(ITEM, again.active().getFirst().item());
        assertTrue(again.active().getFirst().unit() > 1.5);
        db.close();
    }

    @Test
    void mailIsTakenOnce(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        MailStore s = new MailStore(db);
        s.init();
        UUID p = UUID.randomUUID();
        s.send(p, ITEM, "auction_win", 1).get();
        s.send(p, ITEM, "trade", 2).get();
        List<MailStore.Mail> mail = s.list(p).get();
        assertEquals(2, mail.size());
        assertEquals(2, s.count(p).get());
        assertTrue(s.take(mail.getFirst().id(), p).get());
        assertFalse(s.take(mail.getFirst().id(), p).get(), "a double click cannot duplicate it");
        assertFalse(s.take(mail.get(1).id(), UUID.randomUUID()).get(), "not someone else's");
        assertEquals(1, s.count(p).get());
        db.close();
    }

    @Test
    void auctionsAndTheRareWinLimit(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        AuctionStore s = new AuctionStore(db);
        s.init();
        UUID seller = UUID.randomUUID(), bidder = UUID.randomUUID();
        Auction a = s.create(seller, seller, ITEM, "검", 1, 100, 100, false, 1, 5000, 0);
        Auction rare = s.create(null, null, ITEM, "보스 전리품", 1, 1000, 0, true, 2, 5000, 0);
        assertEquals(1, s.playerRunning(), "server auctions do not take a player slot");
        a.bid = 150;
        a.bidder = bidder;
        a.bids = 1;
        s.saveBid(a);
        AuctionStore again = new AuctionStore(db);
        again.init();
        assertEquals(2, again.running().size());
        Auction loaded = again.get(a.id);
        assertEquals(150, loaded.bid);
        assertEquals(bidder, loaded.bidder);
        rare.bid = 2000;
        rare.bidder = bidder;
        again.finish(again.get(rare.id), AuctionStore.SOLD);
        again.get(a.id);
        // the bid lives on the in-memory object of the store that settles it
        Auction r2 = again.running().stream().filter(x -> x.rare).findFirst().orElse(null);
        assertNull(r2, "finished auctions leave the running list");
        db.sync(c -> null);
        assertEquals(0, again.rareWinsSync(bidder, 2), "the stored bidder is the one saved before finish");
        Auction rare2 = again.create(null, null, ITEM, "보스 전리품 2", 1, 1000, 0, true, 2, 5000, 0);
        rare2.bid = 3000;
        rare2.bidder = bidder;
        again.finish(rare2, AuctionStore.SOLD);
        db.sync(c -> null);
        assertEquals(1, again.rareWinsSync(bidder, 2));
        assertEquals(0, again.rareWinsSync(bidder, 3), "counted per week");
        db.close();
    }

    @Test
    void shopSalesDecayAndPricesAreRemembered(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        ShopStore s = new ShopStore(db);
        s.init();
        UUID p = UUID.randomUUID();
        s.record(p, "minecraft:iron_ingot", 64);
        s.record(p, "minecraft:iron_ingot", 36);
        assertEquals(100, s.sold("minecraft:iron_ingot"), 1e-9);
        assertEquals(100, s.soldBy(p, "minecraft:iron_ingot"));
        long now = System.currentTimeMillis();
        s.snapshot("minecraft:iron_ingot", now - 25 * 3_600_000L, 9.0);
        s.snapshot("minecraft:iron_ingot", now - 2 * 3_600_000L, 6.0);
        assertEquals(9.0, s.priceAgo("minecraft:iron_ingot", now, 86_400_000L));
        assertNull(s.priceAgo("minecraft:coal", now, 86_400_000L));
        db.sync(c -> null);
        ShopStore again = new ShopStore(db);
        again.init();
        assertEquals(100, again.sold("minecraft:iron_ingot"), 1e-9, "survives a restart");
        again.decay(0.5);
        assertEquals(50, again.sold("minecraft:iron_ingot"), 1e-9);
        assertEquals(0, again.soldBy(p, "minecraft:iron_ingot"), "daily caps start over");
        db.sync(c -> null);
        ShopStore third = new ShopStore(db);
        third.init();
        assertEquals(50, third.sold("minecraft:iron_ingot"), 1e-9);
        assertEquals(9.0, third.priceAgo("minecraft:iron_ingot", now, 86_400_000L));
        db.close();
    }

    @Test
    void stocksPersist(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        UUID p = UUID.randomUUID();
        dev.stsv.economy.stock.StockStore st = new dev.stsv.economy.stock.StockStore(db);
        st.init();
        var c = st.create("hanseong");
        c.name = "한성제철";
        c.industry = "steel";
        c.risk = 1;
        c.shares = 10000;
        c.start = c.base = c.price = c.prevClose = c.dayHigh = c.dayLow = 2000;
        c.volatility = 0.0017;
        c.sensitivity = 0.8;
        c.dividend = 1;
        c.override("volatility");
        st.save(c);
        var h = st.hold(p, "hanseong");
        h.shares = 30;
        h.cost = 60000;
        h.locked = 10;
        h.lockUntil = 5000;
        st.save(h);
        st.candle("hanseong", 'h', 3_600_000L, 2000, 48, 0);
        st.candle("hanseong", 'h', 3_600_000L, 2100, 48, 0);
        st.candle("hanseong", 'h', 3_600_000L, 1950, 48, 0);
        st.candle("hanseong", 'd', 3, 2050, 60, 0);
        // 10-minute candles: a new one opens at the close of the one just before, not at its own price
        st.candle("hanseong", 'm', 600_000L, 2000, 32, 600_000L);
        st.candle("hanseong", 'm', 1_200_000L, 2080, 32, 600_000L);
        st.candle("hanseong", 'm', 3_000_000L, 2060, 32, 600_000L);   // after a gap: opens at its own price
        st.addNews(10, "hanseong", "[속보] 한성제철, 신규 용광로 가동… 주가 껑충 ▲7.1%");
        st.addActivity(4, "steel", 120);
        st.addActivity(4, "steel", 30);
        var w = st.week(p, 2);
        w.startValue = 50000;
        w.flow = -1000;
        w.invested = 51000;
        st.save(w);
        db.sync(x -> null);

        dev.stsv.economy.stock.StockStore st2 = new dev.stsv.economy.stock.StockStore(db);
        st2.init();
        var c2 = st2.company("hanseong");
        assertEquals("한성제철", c2.name);
        assertEquals(2000, c2.price, 1e-9);
        assertTrue(c2.overridden("volatility") && !c2.overridden("dividend"));
        var h2 = st2.holdings(p).get("hanseong");
        assertEquals(30, h2.shares);
        assertEquals(20, h2.sellable(4000), "10 bought in the last 10 minutes are locked");
        assertEquals(30, h2.sellable(6000));
        assertEquals(30, st2.held("hanseong"));
        var k = st2.hourly("hanseong").getFirst();
        assertEquals(2000, k.open(), 1e-9);
        assertEquals(2100, k.high(), 1e-9);
        assertEquals(1950, k.low(), 1e-9);
        assertEquals(1950, k.close(), 1e-9);
        assertEquals(1, st2.daily("hanseong").size());
        var m = st2.tenMinute("hanseong");
        assertEquals(3, m.size());
        assertEquals(2000, m.get(1).open(), 1e-9);
        assertEquals(2080, m.get(1).high(), 1e-9);
        assertEquals(2000, m.get(1).low(), 1e-9);
        assertEquals(2080, m.get(1).close(), 1e-9);
        assertEquals(2060, m.get(2).open(), 1e-9);
        assertEquals(1, st2.news().size());
        assertEquals(150, st2.activity(4).get("steel"), 1e-9);
        assertEquals(51000, st2.week(p, 2).invested, 1e-9);
        assertEquals(0, st2.week(p, 3).invested, 1e-9, "a new week starts empty");
        h2.shares = 0;
        st2.save(h2);
        db.sync(x -> null);
        dev.stsv.economy.stock.StockStore st3 = new dev.stsv.economy.stock.StockStore(db);
        st3.init();
        assertTrue(st3.holdings(p).isEmpty(), "a sold-out holding is gone");
        db.close();
    }

    @Test
    void jobsLandRequestsEventsAndSettingsPersist(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        UUID p = UUID.randomUUID(), friend = UUID.randomUUID();

        // economy 2.x: one XP and 숙련 per person; it moves to the job they have (eco_job v2)
        db.migrate("eco_job", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_job (player CHAR(36) NOT NULL PRIMARY KEY, job VARCHAR(32) NOT NULL, xp DOUBLE NOT NULL, "
                        + "mastery DOUBLE NOT NULL, changed BIGINT NOT NULL, changes INT NOT NULL)")));
        db.execute("INSERT INTO eco_job (player, job, xp, mastery, changed, changes) VALUES (?, 'miner', 1500, 40, 1, 1)", p.toString());
        JobStore jobs = new JobStore(db);
        jobs.init();
        assertEquals(1540, jobs.xp(p, "miner"), 1e-9, "old XP and 숙련 stay on the job they had");
        JobStore.Row r = jobs.get(p);
        jobs.addXp(r, "miner", 60);
        jobs.flush();
        JobStore.Row f = jobs.choose(friend, "cook", 1);
        jobs.addXp(f, "cook", 10);
        jobs.flush();
        // an old rectangle (economy 2.0) becomes chunks on the first start
        db.migrate("eco_land", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_claim (id INTEGER PRIMARY KEY AUTOINCREMENT, owner CHAR(36), world VARCHAR(64) NOT NULL, "
                        + "x1 INT NOT NULL, z1 INT NOT NULL, x2 INT NOT NULL, z2 INT NOT NULL, created BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS eco_claim_member (claim BIGINT NOT NULL, player CHAR(36) NOT NULL, PRIMARY KEY (claim, player))",
                "CREATE TABLE IF NOT EXISTS eco_land (player CHAR(36) NOT NULL PRIMARY KEY, bought BIGINT NOT NULL, spent BIGINT NOT NULL, warnings INT NOT NULL)")));
        db.execute("INSERT INTO eco_claim (owner, world, x1, z1, x2, z2, created) VALUES (?, 'world', 100, 100, 140, 120, 2)", (Object) null);
        db.execute("INSERT INTO eco_land (player, bought, spent, warnings) VALUES (?, 200, 400, 0)", p.toString());
        LandStore land = new LandStore(db);
        land.init(1);
        Land town = land.index().at("world", 130, 110);
        assertNotNull(town, "the old town rectangle became chunks");
        assertTrue(town.server());
        assertEquals(3 * 2, town.size(), "x 100..140 = chunks 6..8, z 100..120 = chunks 6..7");
        Land mine = land.create(p, 1);
        land.claim(mine, new ChunkPos("world", 0, 0), 1);
        land.claim(mine, new ChunkPos("world", 1, 0), 2);
        land.member(mine, friend, true, 3);
        land.vault(mine, friend, 300, "deposit", 4);
        land.vault(mine, null, -26, "tax", 5);
        land.addSpent(p, 500);
        RequestStore req = new RequestStore(db);
        req.init(3);
        req.complete(p, 2);
        req.contribute(1, p, 500);
        req.contribute(1, friend, 250);
        req.markCompleted(1, 99);
        EventStore events = new EventStore(db);
        events.init();
        ActiveEvent e = events.start("shortage", 0, Long.MAX_VALUE, Map.of("item", "minecraft:iron_ingot", "multiplier", "3"));
        events.start("crash", 0, 50, Map.of());
        Kv kv = new Kv(db);
        kv.init();
        kv.set("calendar.offset", 4);
        kv.set("calendar.last-day", "1");
        kv.set("loc.price-board", "world;0;64;0");
        ActivityStore act = new ActivityStore(db);
        act.init(3);
        act.seen(p, 500);
        act.seen(p, 900);
        act.addSeconds(p, 3600);
        act.addIncome(p, 1200);
        act.flush();
        db.sync(c -> null);

        // --- restart ---
        JobStore jobs2 = new JobStore(db);
        jobs2.init();
        assertEquals("miner", jobs2.job(p));
        assertEquals(1600, jobs2.xp(p, "miner"), 1e-9);
        assertEquals(List.of(p, friend), jobs2.ranking(null, 5).stream().map(JobStore.Entry::player).toList());
        assertEquals(List.of(p), jobs2.ranking("miner", 5).stream().map(JobStore.Entry::player).toList());
        JobStore.Row changed = jobs2.choose(p, "fisher", 5);
        assertEquals(0, changed.xp("fisher"), 1e-9, "a new job starts from zero");
        assertEquals(1600, changed.xp("miner"), 1e-9, "the old job keeps its level");
        assertEquals(2, changed.changes);
        jobs2.addXp(changed, "fisher", 5);
        jobs2.flush();
        db.sync(c -> null);
        JobStore jobs3 = new JobStore(db);
        jobs3.init();
        assertEquals("fisher", jobs3.job(p));
        assertEquals(5, jobs3.xp(p, "fisher"), 1e-9);
        assertEquals(1600, jobs3.xp(p, "miner"), 1e-9);

        LandStore land2 = new LandStore(db);
        land2.init(9);
        Land m2 = land2.index().at("world", 25, 5);
        assertNotNull(m2, "chunks restored");
        assertEquals(mine.id, m2.id);
        assertEquals(2, m2.size());
        assertEquals(new ChunkPos("world", 1, 0), m2.newest());
        assertTrue(m2.allows(friend));
        assertFalse(m2.allows(UUID.randomUUID()));
        assertEquals(274, m2.vault);
        assertEquals(Map.of(friend, 300L), land2.contributions(m2), "tax is not a contribution");
        assertEquals(2, land2.history(m2, 10).size());
        assertEquals("tax", land2.history(m2, 10).getFirst().kind(), "newest first");
        assertEquals(900, land2.spent(p), "old land money is kept");
        assertEquals(town.id, land2.index().at("world", 130, 110).id, "converted once, not again");
        assertEquals(2, land2.index().all().size());
        land2.reset();
        db.sync(c -> null);
        LandStore land3 = new LandStore(db);
        land3.init(10);
        assertNull(land3.index().at("world", 5, 5), "player lands wiped by a reset");
        assertNotNull(land3.index().at("world", 130, 110), "the town (server land) stays");
        assertEquals(town.id, land3.index().at("world", 130, 110).id);
        assertEquals(0, land3.spent(p));

        RequestStore req2 = new RequestStore(db);
        req2.init(3);
        assertTrue(req2.done(p).contains(2));
        assertEquals(750, req2.progress(1));
        assertEquals(500, req2.contributors(1).get(p));
        assertTrue(req2.completed(1));
        req2.newDay(4);
        assertTrue(req2.done(p).isEmpty(), "a new day, a new board");

        EventStore ev2 = new EventStore(db);
        ev2.init();
        assertEquals(2, ev2.active().size());
        assertEquals(1, ev2.active("shortage", 10).size());
        assertEquals("minecraft:iron_ingot", ev2.active("shortage", 10).getFirst().param("item"));
        assertEquals(3, ev2.active("shortage", 10).getFirst().number("multiplier", 0), 1e-9);
        assertEquals(List.of("crash"), ev2.end(60, null).stream().map(ActiveEvent::type).toList(), "time up");
        assertEquals(1, ev2.end(60, "shortage").size(), "ended by hand");
        assertEquals(e.id(), e.id());
        db.sync(c -> null);
        EventStore ev3 = new EventStore(db);
        ev3.init();
        assertTrue(ev3.active().isEmpty());

        Kv kv2 = new Kv(db);
        kv2.init();
        assertEquals(4, kv2.getLong("calendar.offset", 0));
        kv2.removePrefix("calendar.");
        db.sync(c -> null);
        Kv kv3 = new Kv(db);
        kv3.init();
        assertFalse(kv3.has("calendar.offset"));
        assertFalse(kv3.has("calendar.last-day"));
        assertEquals("world;0;64;0", kv3.get("loc.price-board"));

        ActivityStore act2 = new ActivityStore(db);
        act2.init(3);
        assertEquals(500, act2.startBalance(p, 0), "the first sighting of the day counts");
        ActivityStore.Day d = act2.day(3).get().getFirst();
        assertEquals(3600, d.seconds());
        assertEquals(1200, d.income());
        act2.newDay(4);
        assertEquals(1, act2.players(), "at least 1 (prices divide by it)");
        assertFalse(act2.seenToday(p));
        db.close();
    }
    @Test
    void stallRemovalOnlyReadsTheStallTableWhenThereIsOne(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        assertFalse(db.<Boolean>sync(c -> StallRemoval.hasTable(c, "eco_stall")), "a new server never had stalls");
        db.execute("CREATE TABLE eco_stall_price (number INTEGER)").get();
        db.execute("CREATE TABLE ecoxstall (number INTEGER)").get();
        assertFalse(db.<Boolean>sync(c -> StallRemoval.hasTable(c, "eco_stall")), "no other table counts, '_' is not a wildcard here");
        db.execute("CREATE TABLE eco_stall (number INTEGER)").get();
        assertTrue(db.<Boolean>sync(c -> StallRemoval.hasTable(c, "eco_stall")));
        db.close();
    }
}
