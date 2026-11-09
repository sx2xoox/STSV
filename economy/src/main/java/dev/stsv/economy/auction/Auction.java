package dev.stsv.economy.auction;

import java.util.UUID;

/**
 * One auction. Mutable while it runs (main thread only). seller == null: a server auction (대경매)
 * whose money disappears from the economy and which counts toward the weekly rare-win limit.
 */
public final class Auction {

    public final long id;
    public final UUID seller;
    public final UUID creator;
    public final byte[] item;
    public final String itemName;
    public final int amount;
    public final long startPrice;
    public final long deposit;
    public final boolean rare;
    public final int week;
    public long bid;
    public UUID bidder;
    public long ends;
    public int bids;

    public Auction(long id, UUID seller, UUID creator, byte[] item, String itemName, int amount, long startPrice, long deposit,
                   boolean rare, int week, long bid, UUID bidder, long ends, int bids) {
        this.id = id;
        this.seller = seller;
        this.creator = creator;
        this.item = item;
        this.itemName = itemName;
        this.amount = amount;
        this.startPrice = startPrice;
        this.deposit = deposit;
        this.rare = rare;
        this.week = week;
        this.bid = bid;
        this.bidder = bidder;
        this.ends = ends;
        this.bids = bids;
    }

    /** The least the next bid must be: the start price, then the current bid plus the minimum raise (at least 1). */
    public long minNext(double raise) {
        if (bidder == null) return startPrice;
        return bid + Math.max(1, (long) Math.ceil(bid * raise - 1e-9));
    }

    /** A bid in the last window pushes the end back so the others can answer (no sniping). */
    public boolean extendIfLate(long now, long windowMillis) {
        if (ends - now < windowMillis) {
            ends = now + windowMillis;
            return true;
        }
        return false;
    }
}
