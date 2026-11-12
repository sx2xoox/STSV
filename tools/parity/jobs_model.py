"""Job income model at real scale (read-only over a content dir).

    python3 jobs_model.py <content_dir> [--curve old|new] ...

Every number the analysis quotes comes from here. Assumptions (the 'standard day', stated in analysis.md):
  * a day = 3 active hours in one evening session, then 21 hours away; 5 days are simulated and day 5 is reported
    (steady state of the per-player price curve: S carries over with hourly recovery and the daily decay);
  * each player owns M = 2 machines of their own job at the tier the level allows (Lv1 0강, Lv15 1강, Lv30 3강);
    lanes run through the 3 active hours (refilled), and once more after the player leaves: each lane runs until its
    output stack (64) is full or the night is over; that batch is sold at the start of the next session;
  * gathering per active hour: fishing (Lure III, the best 3 waters rotated one per hour), mining (Fortune III, a mixed
    branch-mining hour: ORES below), farming (FARM_PLOTS plots, hydrated rows, 7 ages per 24 min, a Lv30 farmer harvests
    3x3, the rest one plot a click); everything a player picks up in an hour is at most HANDLE items (about 300 stacks);
  * a player uses their own catch first (its value = what selling it would pay now); anything else a route needs (another
    job's raw goods, another machine's goods, vanilla extras) is bought from other players at MARKET x base (sellers at
    real scale are at their own floor, so a saturated seller takes about this);
  * a machine-job player (cook, alchemist, blacksmith) gathers its own staple during the active hours without job perks:
    the cook and the alchemist farm (FARM_PLOTS, no 3x3), the blacksmith mines (Fortune III, no miner perks);
  * every sale goes through the seller's own curve (per item), custom crops stop at their cap.
"""
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model as M  # noqa: E402

ACTIVE_H = 3
AWAY_H = 21
DAYS = 5
MACHINES = 2
HANDLE = 19200            # items picked up per active hour (300 stacks)
FARM_PLOTS = 5000
FARM_PLOTS_AT = {1: 500, 15: 2000, 30: 5000}   # plots a player of that level farms
CLICKS_PER_H = 3600       # harvest clicks an hour (one a second, walking included)
MARKET = 0.7              # price paid to other players for inputs, x base
LANE_STACK = 64
# a mixed branch-mining hour, Efficiency V + Fortune III: ore blocks broken
ORES = {"diamond": 10, "iron": 30, "gold": 8, "redstone": 25, "lapis": 6, "copper": 20, "coal": 20}
ORE_DROP = {  # (item, mean drop without Fortune, Fortune III multiplier kind)
    "diamond": ("minecraft:diamond", 1.0, "ore"), "iron": ("minecraft:iron_ingot", 1.0, "ore"),
    "gold": ("minecraft:gold_ingot", 1.0, "ore"), "redstone": ("minecraft:redstone", 4.5, "uniform"),
    "lapis": ("minecraft:lapis_lazuli", 6.5, "ore"), "copper": ("minecraft:copper_ingot", 3.5, "ore"),
    "coal": ("minecraft:coal", 1.0, "ore")}
FIND_ORES = {"iron", "redstone", "lapis", "diamond"}     # jobs.yml miner finds.drops
FIND_ITEM = {"iron": "economy:rough_ruby", "redstone": "economy:rough_ruby", "lapis": "economy:rough_sapphire",
             "diamond": "economy:rough_sapphire"}
SEA = {"sea", "deep", "warm", "cold", "mangrove", "mushroom"}
LEVEL_TIER = {1: 0, 15: 1, 30: 3}


# ------------------------------------------------------------------------------------------------ curves
class Curve:
    """kind 'kink': P = base max(F, e^(-S/Q)) (today); 'smooth': P = base (F + (1-F) e^(-S/Q))."""

    def __init__(self, floor, depth, hourly, daily, kind, a=0.5):
        self.F, self.depth, self.hourly, self.daily, self.kind, self.a = floor, depth, hourly, daily, kind, a

    def unit(self, base, s, q, F=None):
        F = self.F if F is None else F
        if self.kind == "pow":
            return base * max(F, (1 + s / (q * self.depth)) ** (-self.a))
        x = math.exp(-s / (q * self.depth))
        if self.kind == "kink":
            return base * max(F, x)
        return base * (F + (1 - F) * x)

    def total(self, base, s, q, n, F=None):
        """Closed form of the sum of n units from s (continuous; within a unit of the discrete sum). F: this item's floor
        (shop.yml sell line `floor`), else the shop's."""
        if n <= 0:
            return 0.0
        if F is not None and F != self.F:
            saved, self.F = self.F, F
            try:
                return self.total(base, s, q, n)
            finally:
                self.F = saved
        Q = q * self.depth
        if self.kind == "pow":
            a = self.a
            knee = Q * (self.F ** (-1 / a) - 1)
            lo, hi = s, s + n
            tot = 0.0
            if lo < knee:
                e = min(hi, knee)
                F_ = lambda x: Q * (1 + x / Q) ** (1 - a) / (1 - a)
                tot += base * (F_(e) - F_(lo))
                lo = e
            if hi > lo:
                tot += base * self.F * (hi - lo)
            return tot
        if self.kind == "smooth":
            return base * (self.F * n + (1 - self.F) * Q * (math.exp(-s / Q) - math.exp(-(s + n) / Q)))
        knee = Q * math.log(1 / self.F)
        a, b = s, s + n
        tot = 0.0
        if a < knee:
            e = min(b, knee)
            tot += base * Q * (math.exp(-a / Q) - math.exp(-e / Q))
            a = e
        if b > a:
            tot += base * self.F * (b - a)
        return tot


CANDIDATES = {
    "old": ("kink", 0.4, 3, 0.08, 0.5, 0.5),
    "new": ("pow", 0.25, 6, 0.12, 0.5, 0.5),
}


def curve_of(shop, kind=None):
    if kind in CANDIDATES:
        k, F, d, h, dd, a = CANDIDATES[kind]
        return Curve(F, d, h, dd, k, a)
    if "curve-exponent" in shop:
        return Curve(shop["floor"], shop["depth"], shop["hourly-recovery"], shop["daily-decay"], "pow", shop["curve-exponent"])
    return Curve(shop["floor"], shop["depth"], shop["hourly-recovery"], shop["daily-decay"], kind or "kink")


# ------------------------------------------------------------------------------------------------ world
class W:
    def __init__(self, root):
        self.root = Path(root)
        self.w = M.load(root)
        rd = lambda f: yaml.safe_load((self.root / f).read_text(encoding="utf-8"))
        self.shop = rd("shop.yml")
        self.fish = rd("fish.yml")
        self.jobs = self.w.jobs_raw
        self.mach = self.w.mach_raw
        self.base = dict(self.w.sell)
        self.q = {k: float(v) for k, v in self.w.q.items()}
        self.cap = {k: v for k, v in self.w.cap.items() if v}
        self.up = self.mach["upgrade"]
        # a sell line's own floor (shop.yml `floor` on the line), else the shop's
        self.floor = {}
        for cc in self.shop["sell"].values():
            for k, v in cc["items"].items():
                if isinstance(v, dict) and "floor" in v:
                    self.floor[M.norm(k)] = float(v["floor"])

    def value(self, item):
        return self.w.value(item)

    # ---- job bonuses (JobSpec)
    def lin(self, job, key, lv):
        v = self.jobs["jobs"][job].get(key)
        if v is None:
            return 0.0
        if isinstance(v, list):
            return v[0] + (v[1] - v[0]) * (lv - 1) / 29
        return float(v)

    def mb(self, job, key, lv):
        """A machine bonus (jobs.yml machine-bonus) at a level, as JobSpec.Job#bonus: 0 below its level; 'speed' is per level."""
        b = (self.jobs["jobs"][job].get("machine-bonus") or {}).get(key)
        if b is None:
            return 0.0
        if not isinstance(b, dict):
            return float(b) * lv if key == "speed" else float(b)
        v = float(b.get("chance", b.get("factor", b.get("bonus", 0.0))))
        return v if lv >= int(b.get("level", 1)) else 0.0


# ------------------------------------------------------------------------------------------------ gathering
def fishing_hour(W_, lv, habitat, fisher=True):
    """Expected catches of one active hour: {item: (count, mean price factor)}. Lure III."""
    f = W_.fish
    bite = f["bite"]
    cut = W_.lin("fisher", "bite", lv) if fisher else 0.0
    fac = max(0, 1 - bite["lure-per-level"] * 3) * (1 - cut)
    wait = (bite["min"] + bite["max"]) / 2 * fac
    rar = f["rarities"]

    def ring(r):
        R = rar[r]
        t = 0
        for h in range(R["hits"]):
            sp = R["speed"] + 0.22 * h
            zw = max(3, R["zone"] - h)
            t += 6 + (23 + zw / 2) / sp + 3
        return t / 20 + 1.0
    land = {"common": 1, "uncommon": 0.99, "rare": 0.93, "legend": 0.80} if lv < 15 else \
        {"common": 1, "uncommon": 1, "rare": 0.98, "legend": 0.92} if lv < 30 else \
        {"common": 1, "uncommon": 1, "rare": 1.0, "legend": 0.97}
    rare = W_.lin("fisher", "finds", lv) if False else None
    finds = W_.jobs["jobs"]["fisher"]["finds"]
    rare = (finds["chance"][0] + (finds["chance"][1] - finds["chance"][0]) * (lv - 1) / 29) if fisher else finds["outsider"]
    ls = f["legend-share"]
    p = {"legend": rare * ls, "rare": rare * (1 - ls), "uncommon": (1 - rare) * 0.35, "common": (1 - rare) * 0.65}
    order = ["common", "uncommon", "rare", "legend"]

    def pool(r):
        i = order.index(r)
        while i >= 0:
            ps = [(k, s) for k, s in f["species"].items() if s["rarity"] == order[i] and
                  (s["habitat"] == habitat or (s["habitat"] == "salt" and habitat in SEA) or
                   (s["habitat"] == "fresh" and habitat not in SEA)) and not s.get("when")]
            if ps:
                return order[i], ps
            i -= 1
        return None, []
    secs = sum(p[r] * ring(r) for r in order) + wait + 1.5   # + cast and the float settling
    n = 3600 / secs
    out = defaultdict(lambda: [0.0, 0.0])
    case = "lv20" if (fisher and lv >= 20) else "lv1"
    for r in order:
        rr, ps = pool(r)
        if not ps:
            continue
        cnt = n * p[r] * land[r]
        if r == "rare" and habitat in SEA:
            pe = cnt * f["pearl"]
            add(out, "economy:pearl", pe * (2 if fisher and lv >= 20 else 1), 1.0)
            cnt -= pe
        for k, s in ps:
            item = M.norm(s["item"]) if "item" in s else "economy:" + k
            c = cnt / len(ps)
            if fisher and lv >= 20 and k in ("bluefin_tuna", "sturgeon"):
                c *= 2
            fac_ = W_.w.factor(item, case) if W_.w.sized(item) else 1.0
            add(out, item, c, fac_)
    return {k: tuple(v) for k, v in out.items()}, n


def add(out, item, c, fac):
    o = out[item]
    tot = o[0] + c
    o[1] = (o[1] * o[0] + fac * c) / tot if tot else fac
    o[0] = tot


# ------------------------------------------------------------------------------------------------ traps and pens
# The rod (fishing_hour) is a stopwatch rate: a bite, the ring and a recast back to back, about 8-9.5 s a fish all hour. A real
# fishing hour also moves between the three waters (one an hour, often far apart: deep ocean, warm ocean, mushroom fields), takes
# the catch somewhere (rare and legend fish keep their size and do not stack: at Lv30 about 90 unstackable fish an hour) and loses
# casts to the shore, a mob or a boat. The miner's ORES and the farmer's CLICKS_PER_H are hours with the walking in; the rod's
# share of a fishing hour is ROD_HOUR (45 minutes of the 60).
ROD_HOUR = 0.75
# 통발 / 가두리 (fish.yml trap, pen; jobs.yml fisher trap-*): a fisher keeps TRAPS[lv] traps and PENS[lv] pens (bought or crafted,
# fish.yml per-chunk a chunk), visits every one once an active hour (TRAP_VISIT_S / PEN_VISIT_S each: walking the line, the click,
# topping up the feed; that time comes off the rod) and empties them once more at the start of the next session (what they made
# overnight, up to their storage). A trap a fisher tends hourly never stands full while they play (storage >= an hour of catches).
TRAP_VISIT_S = 6
PEN_VISIT_S = 20
TRAPS = {1: 14, 10: 16, 15: 16, 20: 24, 30: 32}
PENS = {1: 0, 10: 1, 15: 1, 20: 2, 30: 4}
TRAP_ORDER = ["common", "uncommon", "rare"]
PEN_FEED = "economy:fish_feed"   # 생선 사료 (the mill), one a growth cycle


def trap_cfg(W_, lv, fisher=True):
    """(minutes a catch, storage, uncommon share, rare chance) of a trap whose owner is a fisher of lv (else anyone)."""
    t = W_.fish["trap"]
    cut = min(0.8, W_.lin("fisher", "trap-speed", lv)) if fisher else 0.0
    unc = t["uncommon"] + (W_.lin("fisher", "trap-uncommon", lv) if fisher else 0.0)
    rare = t["rare"] + (W_.lin("fisher", "trap-rare", lv) if fisher else 0.0)
    return t["minutes"] * (1 - cut), int(t["storage"]), unc, rare


def trap_pool(W_, habitat):
    """{rarity: [(species id, spec)]} a trap in that water catches (no fish of a moment, no legend; the mushroom island's
    missing commons come from the sea around it, as FishSpec.pick)."""
    out = {}
    for r in TRAP_ORDER:
        for h in (habitat, "sea" if habitat == "mushroom" else None):
            if h is None:
                continue
            ps = [(k, s) for k, s in W_.fish["species"].items() if s["rarity"] == r and not s.get("when") and
                  (s["habitat"] == h or (s["habitat"] == "salt" and h in SEA) or (s["habitat"] == "fresh" and h not in SEA))]
            if ps:
                out[r] = ps
                break
    return out


def trap_catch(W_, lv, habitat, n, fisher=True):
    """{item: (count, mean price factor)} of n trap catches in a water: rare with the rare chance, else uncommon by its share; a
    rarity the water lacks falls back a rarity (FishSpec.pick)."""
    _, _, unc, rare = trap_cfg(W_, lv, fisher)
    pool = trap_pool(W_, habitat)
    p = {"rare": rare, "uncommon": (1 - rare) * unc, "common": (1 - rare) * (1 - unc)}
    out = defaultdict(lambda: [0.0, 0.0])
    for r in TRAP_ORDER:
        i = TRAP_ORDER.index(r)
        while i >= 0 and TRAP_ORDER[i] not in pool:
            i -= 1
        if i < 0:
            continue
        ps = pool[TRAP_ORDER[i]]
        for k, s in ps:
            item = M.norm(s["item"]) if "item" in s else "economy:" + k
            fac = W_.w.factor(item, "lv1") if W_.w.sized(item) else 1.0
            add(out, item, n * p[r] / len(ps), fac)
    return {k: tuple(v) for k, v in out.items()}


def trap_waters_catch(W_, lv, waters, n, fisher=True):
    """n trap catches spread evenly over the waters (the fisher's traps sit where they fish: the rod's best_waters)."""
    out = defaultdict(lambda: [0.0, 0.0])
    for h in waters:
        for i, (c, f) in trap_catch(W_, lv, h, n / len(waters), fisher).items():
            add(out, i, c, f)
    return {k: tuple(v) for k, v in out.items()}


def trap_hour(W_, lv, fisher=True):
    """Catches of one trap in an active hour (emptied every hour) and overnight (up to its storage)."""
    mins, storage, _, _ = trap_cfg(W_, lv, fisher)
    return min(storage, 60 / mins), storage


def pen_cfg(W_, lv, fisher=True):
    p = W_.fish["pen"]
    cut = min(0.8, W_.lin("fisher", "trap-speed", lv)) if fisher else 0.0
    return p["minutes"] * (1 - cut), int(p["capacity"]), int(p["feed"]), float(p["growth"]), int(p["keep"])


def pen_grow(count, cycles, capacity, growth, feed):
    """PenMath.grow: each cycle the pen grows by growth x count (at least one fish), up to its capacity, eating one feed; with no
    feed it waits. Returns (count, feed used). Fractional cycles grow by their share (the model's hours)."""
    used = 0
    k = 0.0
    while k < cycles - 1e-9 and count < capacity and used < feed:
        step = min(1.0, cycles - k)
        count = min(capacity, count + max(1, math.floor(count * growth)) * step)
        used += step
        k += step
    return count, used


def pen_day(W_, lv, hours, fisher=True):
    """One pen over a day (one session of `hours` active hours, harvested down to its keep every hour, then left overnight):
    (fish an active hour, fish waiting at the next session, feed a day)."""
    mins, cap, feed, growth, keep = pen_cfg(W_, lv, fisher)
    per_h = 60 / mins
    grown, used = pen_grow(keep, per_h, cap, growth, feed)
    night, used_n = pen_grow(keep, (24 - hours) * 60 / mins, cap, growth, feed)
    return grown - keep, night - keep, used * hours + used_n


def pen_species(W_, n):
    """The n species a fisher farms: the dearest commons and uncommons (no fish of a moment), each pen a different one."""
    ps = [(W_.base.get("economy:" + k if "item" not in s else M.norm(s["item"]), 0), "economy:" + k if "item" not in s else M.norm(s["item"]))
          for k, s in W_.fish["species"].items() if s["rarity"] in ("common", "uncommon") and not s.get("when")]
    ps.sort(reverse=True)
    return [i for _, i in ps[:n]]


def mining_hour(W_, lv, miner=True):
    out = defaultdict(lambda: [0.0, 1.0])
    extra = W_.lin("miner", "extra", lv) if miner else 0.0
    finds = W_.jobs["jobs"]["miner"]["finds"]
    fc = (finds["chance"][0] + (finds["chance"][1] - finds["chance"][0]) * (lv - 1) / 29) if miner else finds["outsider"]
    for ore, n in ORES.items():
        item, mean, kind = ORE_DROP[ore]
        d = mean * 2.2 if kind == "ore" else mean + 1.5        # Fortune III
        add(out, item, n * (d + extra), 1.0)
        if ore in FIND_ORES:
            add(out, FIND_ITEM[ore], n * fc, 1.0)
    return {k: tuple(v) for k, v in out.items()}


def crop_table(W_):
    """custom crops: item -> (units per plot-hour at base growth, per harvest, regrow)."""
    out = {}
    for c in W_.w.crop_rows():
        harvests_h = 2.5 * 7 / c["steps"] / 7   # 7 ages per 24 min -> 17.5 ages an hour
        harvests_h = 17.5 / c["steps"]
        out[c["item"]] = (c["net"] * harvests_h, c["net"], harvests_h, c["regrow"])
    return out


VANILLA_CROPS = {  # item: (net per harvest without fortune / job, fortune III add, ages to ripen)
    "minecraft:wheat": (1.0, 0.0, 7), "minecraft:potato": (1.71, 1.71, 7), "minecraft:carrot": (1.71, 1.71, 7),
    "minecraft:beetroot": (1.0, 0.0, 3),
}


def farm_supply(W_, lv, farmer=True):
    """Per active hour: how much of each crop can be had (harvest labour and handling bound the total, not the crop)."""
    pct = W_.jobs["jobs"]["farmer"]["harvest-pct-per-level"] * lv if farmer else 0.0
    perlv = W_.jobs["jobs"]["farmer"]["harvest-per-level"] * lv if farmer else 0.0
    area = 9 if (farmer and lv >= W_.jobs["jobs"]["farmer"]["area-harvest"]) else 1
    harvests = min(CLICKS_PER_H * area, FARM_PLOTS_AT.get(lv, FARM_PLOTS) * 2.5)   # 2.5 harvests a plot an hour at most (7 ages / 24 min)
    supply = {}
    for item, (per_plot_h, net, hh, rg) in crop_table(W_).items():
        per_h = (net + 0.0) * (1 + pct) * 1.45      # Fortune III ~ +45% on the produce of a custom crop (CropGrowth)
        supply[item] = per_h                        # per harvest
    for item, (net, fort, ages) in VANILLA_CROPS.items():
        supply[item] = net + fort + perlv
    return supply, harvests


# ------------------------------------------------------------------------------------------------ machines
class Routes:
    """How to make one unit of each good of a machine at a tier, for an operator's bonuses: lane seconds and bought/own
    inputs per unit (own-machine intermediates made in the same lanes, the cheapest group member used)."""

    def __init__(self, W_, mid, tier, job, lv):
        self.W, self.mid, self.tier = W_, mid, tier
        m = W_.mach[mid]
        self.recipes = [r for r in m["recipes"] if int(r.get("tier", 0)) <= tier]
        mbj = (lambda k: W_.mb(job, k, lv)) if job else (lambda k: 0.0)
        self.speed = W_.up["speed"][tier]
        self.t_mult = max(0.1, 1 - mbj("speed")) * (W_.mb(job, "fast", lv) if job and W_.mb(job, "fast", lv) else 1.0)
        ag = W_.mb(job, "aging", lv) if job else 0.0
        self.age_mult = ag if ag else 1.0
        more = min(1.0, mbj("extra") + mbj("yield"))
        self.out_mult = (1 + mbj("double")) * (1 + more)
        self.save = min(0.9, mbj("save"))
        self.cache = {}
        self.makes = {}
        for r in self.recipes:
            self.makes.setdefault(M.norm(r["out"]), r)
        # route keys the greedy machine loop can pick: every good through its first recipe at the cheapest group member (as
        # before), plus (2026-11, the 손질대's tier processing) every other member of a group input and every later recipe of the
        # same good that takes a group (any fish of a band -> its tier item): key "out|recipe|member" -> (recipe, member fill)
        self.routes = {o: (r, {}) for o, r in self.makes.items()}
        for i, r in enumerate(self.recipes):
            o = M.norm(r["out"])
            gk = [M.norm(k) for k in r["in"] if M.norm(k) in W_.w.groups]
            if len(gk) != 1:
                continue
            members = [x for x in W_.w.groups[gk[0]] if not x.startswith("#")]
            cheapest = min((W_.value(x), x) for x in members)[1] if members else None
            for m in members:
                if self.makes[o] is r and m == cheapest:
                    continue
                self.routes[f"{o}|{i}|{m}"] = (r, {gk[0]: m})

    def batch_secs(self, r):
        t = float(r.get("seconds", 10)) / (1 + self.speed) * self.t_mult
        if r.get("aging"):
            t *= self.age_mult
        return max(1.0, t)

    def real(self, key):
        """The good a route key makes."""
        return key.split("|", 1)[0]

    def unit(self, item, depth=0):
        """(lane seconds, {input: count}) for one unit of item (or of a route key, see routes) made here."""
        if item in self.cache:
            return self.cache[item]
        r, fill = self.routes[item] if "|" in item else (self.makes[item], {})
        per = 1.0 / (int(r.get("amount", 1)) * self.out_mult)
        secs = self.batch_secs(r) * per
        ins = defaultdict(float)
        for k, n in r["in"].items():
            k = M.norm(k)
            need = n * (1 - self.save) * per
            if k in fill:
                k = fill[k]
            elif k in self.W.w.groups:
                vals = [(self.W.value(x), x) for x in self.W.w.groups[k] if not x.startswith("#")]
                k = min(vals)[1] if vals else k
            if k in self.makes and depth < 6 and k != self.real(item):
                s2, i2 = self.unit(k, depth + 1)
                secs += s2 * need
                for a, b in i2.items():
                    ins[a] += b * need
            else:
                ins[k] += need
        self.cache[item] = (secs, dict(ins))
        return self.cache[item]


# ------------------------------------------------------------------------------------------------ one day
class Ledger:
    def __init__(self, W_, curve):
        self.W, self.c = W_, curve
        self.S = defaultdict(float)       # per item
        self.sold_today = defaultdict(float)

    def price(self, item, extra=0.0):
        b = self.W.base.get(item)
        if b is None:
            return 0.0
        if item in self.W.cap and self.sold_today[item] + extra >= self.W.cap[item]:
            return 0.0
        return self.c.unit(b, self.S[item] + extra, self.W.q[item], self.W.floor.get(item))

    def sell(self, item, n, fac=1.0):
        b = self.W.base.get(item)
        if b is None or n <= 0:
            return 0.0
        if item in self.W.cap:
            n = min(n, max(0.0, self.W.cap[item] - self.sold_today[item]))
        g = self.c.total(b, self.S[item], self.W.q[item], n, self.W.floor.get(item)) * fac
        self.S[item] += n
        self.sold_today[item] += n
        return g

    def hours(self, h):
        for k in self.S:
            self.S[k] *= (1 - self.c.hourly) ** h

    def day(self):
        for k in self.S:
            self.S[k] *= self.c.daily
        self.sold_today.clear()


JOB_MACHINE = {"miner": "gem_bench", "farmer": "mill", "fisher": "fish_bench", "blacksmith": "forge", "cook": "kitchen",
               "alchemist": "still"}


FARM_GOODS_MACHINE = "mill"


class Market:
    """What a player can buy from others in a day. Abundant (anyone farms 5,000 plots; vanilla extras): farm produce, the
    mill's goods and vanilla items the shop does not buy, unlimited at MARKET x base. Scarce (another job's catch or machine
    goods: ores, fish, gems, forge / bench / kitchen / still goods): up to SCARCE_Q x q a day each, at full base (their
    makers sell that much to the shop near base)."""

    def __init__(self, W_, job):
        self.W, self.job = W_, job
        self.bought = defaultdict(float)
        farm = set(W_.shop["sell"]["farm"]["items"]) | {"minecraft:melon_slice"}
        self.abundant = {M.norm(k) for k in farm}
        self.abundant |= {o for o, m in W_.w.made_on.items() if m == FARM_GOODS_MACHINE}
        self.abundant |= set(M.SHADOW) | {"#small_flowers", "#logs", "#saplings", "#leaves"}
        # vanilla goods that farms make without limit (iron / gold golem and piglin farms, tree farms, mob farms): every
        # vanilla item but the gems, netherite and the fish
        scarce_vanilla = {"minecraft:diamond", "minecraft:emerald", "minecraft:netherite_scrap", "minecraft:cod",
                          "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish"}
        self.abundant |= {i for i in W_.base if i.startswith("minecraft:") and i not in scarce_vanilla}
        # anyone can run any machine (without the job bonus): a good made only of abundant inputs is abundant too
        changed = True
        while changed:
            changed = False
            for r in W_.w.rows:
                if r["out"] in self.abundant:
                    continue
                if all(k in self.abundant or (k in W_.w.groups and any(x in self.abundant for x in W_.w.groups[k]))
                       for k in r["ins"]):
                    self.abundant.add(r["out"])
                    changed = True

    def price(self, i):
        if i in self.abundant or i not in self.W.base:
            return MARKET * self.W.value(i)
        return self.W.value(i)

    def left(self, i):
        if i in self.abundant or i not in self.W.base:
            return max(0.0, ABUNDANT_DAY - self.bought[i])
        return max(0.0, SCARCE_Q * self.W.q[i] - self.bought[i])

    def day(self):
        self.bought.clear()


SCARCE_Q = 2
ABUNDANT_DAY = 1000      # units of one abundant item a player buys from trading partners a day (about 16 stacks)


def run_machine(W_, L, routes, lanes_secs, own, mk, pending, steps=200, handle=math.inf):
    """Greedy: spend lane seconds on the good with the best marginal profit per lane second. own = {item: count} the
    player's own catch (cost: what selling it would pay now), the rest from the market mk. Returns made, used own, spent."""
    made = defaultdict(float)
    used = defaultdict(float)
    spent = 0.0
    budget = lanes_secs
    chunk = lanes_secs / steps
    for _ in range(steps * 3):
        if budget <= 1e-6:
            break
        best = None
        for key in routes.routes:
            g = routes.real(key)
            secs, ins = routes.unit(key)
            n = min(chunk, budget) / secs
            per_item = 1 + sum(ins.values())       # items handled a unit: its inputs and itself
            n = min(n, handle / per_item)
            for i, c in ins.items():        # how many units the inputs allow
                avail = max(0.0, own.get(i, 0.0) - used[i]) + mk.left(i)
                if c > 0:
                    n = min(n, avail / c)
            if n <= 1e-6:
                continue
            rev = n * L.price(g, pending[g] + made[g] + n / 2)
            cost = 0.0
            for i, c in ins.items():
                need = c * n
                take = min(max(0.0, own.get(i, 0.0) - used[i]), need)
                cost += take * L.price(i, pending[i] + used[i]) + (need - take) * mk.price(i)
            prof = (rev - cost) / (n * secs)
            if best is None or prof > best[0]:
                best = (prof, g, n, ins, secs)
        if best is None or best[0] <= 0:
            break
        _, g, n, ins, secs = best
        made[g] += n
        handle -= n * (1 + sum(ins.values()))
        budget -= n * secs
        for i, c in ins.items():
            need = c * n
            take = min(max(0.0, own.get(i, 0.0) - used[i]), need)
            used[i] += take
            mk.bought[i] += need - take
            spent += (need - take) * mk.price(i)
    return made, used, spent


TAX_BRACKETS = []       # [(over, marginal rate)] on a day's shop sales (shop.yml income-tax)


def income_tax(gross):
    t = 0.0
    for i, (over, rate) in enumerate(TAX_BRACKETS):
        top = TAX_BRACKETS[i + 1][0] if i + 1 < len(TAX_BRACKETS) else math.inf
        if gross > over:
            t += (min(gross, top) - over) * rate
    return t


CROP_JOBS = ("farmer", "cook", "alchemist")
SELF_FARM = False   # cook / alchemist farm their own crops (False: they buy them at MARKET)


def tax_of(shop):
    return [(int(b["over"]), float(b["rate"])) for b in shop.get("income-tax") or []]


def simulate(W_, curve, job, lv, verbose=False, tax=None):
    global TAX_BRACKETS
    TAX_BRACKETS = tax_of(W_.shop) if tax is None else tax
    L = Ledger(W_, curve)
    tier = LEVEL_TIER[lv]
    routes = Routes(W_, JOB_MACHINE[job], tier, job, lv)
    lanes = W_.up["lanes"][tier] * MACHINES
    mk = Market(W_, job)
    habitats = best_waters(W_, lv) if job == "fisher" else None
    days = []
    overnight = {}
    for d in range(DAYS):
        got = 0.0
        det = defaultdict(float)

        def sell(i, n, f=1.0):
            nonlocal got
            v = L.sell(i, n, f)
            got += v
            det[i] += v
        for g, n in overnight.items():
            sell(g, n)
        for h in range(ACTIVE_H):
            own, fac = defaultdict(float), {}
            harvests = 0.0
            supply = {}
            if job == "fisher":
                catch, _ = fishing_hour(W_, lv, habitats[h % len(habitats)])
                for i, (n, f_) in catch.items():
                    own[i] += n
                    fac[i] = f_
            elif job == "miner":
                for i, (n, f_) in mining_hour(W_, lv, miner=(job == "miner")).items():
                    own[i] += n
            elif job == "farmer" or SELF_FARM:
                supply, harvests = farm_supply(W_, lv, farmer=(job == "farmer"))
                for i, per in supply.items():
                    own[i] = harvests * per          # what the labour could bring in (handling checked below)
            gathered = sum(own.values()) if job not in CROP_JOBS else 0.0
            made, used, spent = run_machine(W_, L, routes, lanes * 3600, own, mk, defaultdict(float),
                                            handle=max(0.0, HANDLE - gathered))
            got -= spent
            det["(bought)"] -= spent
            for g, n in made.items():
                sell(g, n)
            if supply:
                handled = sum(used.values()) + sum(made.values())
                left = max(0.0, harvests - sum(used[i] / supply[i] for i in supply))
                g2, d2 = sell_crops(L, supply, max(0.0, HANDLE - handled), left)
                got += g2
                for k, v in d2.items():
                    det[k] += v
            else:
                for i, n in own.items():
                    if n - used[i] > 0:
                        sell(i, n - used[i], fac.get(i, 1.0))
            L.hours(1)
        # away: the lanes are filled once more and run until full (64 a lane) or the night ends
        own = defaultdict(float)
        if job == "farmer" or (job in CROP_JOBS and SELF_FARM):
            supply, _ = farm_supply(W_, lv, farmer=(job == "farmer"))
            own = {i: 1e9 for i in supply}
        made, used, spent = run_machine(W_, L, routes, lanes * AWAY_H * 3600, own, mk, defaultdict(float))
        tot = sum(made.values())
        scale = min(1.0, LANE_STACK * lanes / tot) if tot else 0.0
        overnight = {g: n * scale for g, n in made.items()}
        got -= spent * scale
        det["(bought)"] -= spent * scale
        L.hours(AWAY_H)
        L.day()
        mk.day()
        gross = sum(v for k, v in det.items() if not k.startswith("("))
        tax = income_tax(gross)
        det["(sales tax)"] = -tax
        got -= tax
        days.append((got, dict(det)))
    return days[-1]


def sell_crops(L, supply, room, harvests):
    """Sell raw crops with `room` items of handling and `harvests` harvests of labour, best value per item first."""
    got, det = 0.0, defaultdict(float)
    step = 50.0
    while room > 0 and harvests > 0:
        best = None
        for i, per in supply.items():
            p = L.price(i)
            if p <= 0:
                continue
            if best is None or p > best[0]:
                best = (p, i, per)
        if best is None:
            break
        _, i, per = best
        n = min(step, room)
        h = n / per
        if h > harvests:
            n, h = harvests * per, harvests
        v = L.sell(i, n)
        got += v
        det[i] += v
        room -= n
        harvests -= h
    return got, det


def best_waters(W_, lv):
    vals = []
    for h in sorted(SEA | {"river", "coldriver", "swamp", "jungle", "cave"}):
        catch, n = fishing_hour(W_, lv, h)
        v = sum(W_.base.get(i, 0) * c * f for i, (c, f) in catch.items())
        vals.append((v, h))
    vals.sort(reverse=True)
    return [h for _, h in vals[:3]]


def trap_report(W_, lv, hours=3):
    """One line per owner (a fisher of lv, anyone else): a trap's and a pen's day (catches, value at base)."""
    out = []
    waters = best_waters(W_, lv)
    for fisher in (True, False):
        h, night = trap_hour(W_, lv, fisher)
        per = trap_waters_catch(W_, lv, waters, 1.0, fisher)
        v = sum(W_.base.get(i, 0) * c * f for i, (c, f) in per.items())
        day = h * hours + night
        ph, pn, pf = pen_day(W_, lv, hours, fisher)
        out.append(f"Lv{lv:2} {'fisher' if fisher else 'anyone'}: trap {day:4.1f} fish/day ({v:4.1f} G each) = {day * v:5.0f} G; "
                   f"pen {ph * hours + pn:4.1f} fish/day, {pf:.0f} feed")
    return out


if __name__ == "__main__":
    # the job table now comes from pm2 (this file's own simulate predates the farmer's ranges); --traps adds what one trap and one
    # pen make a day, and the rod-only fisher (no traps, no pens) beside the table
    import pm2 as P
    root = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else P.WT
    opt = P.Opt(root=root)
    for s in P.SC:
        for lv in (1, 15, 30):
            row = {j: P.simulate(j, lv, P.SCALES[s], opt) for j in P.JOBS}
            m = sum(row.values()) / len(row)
            rod = P.simulate("fisher", lv, P.SCALES[s], P.Opt(root=root, traps=False))
            print(f"{s:8} Lv{lv:2} " + " ".join(f"{j[:5]} {v / 1000:6.1f}k({v / m - 1:+4.0%})" for j, v in row.items())
                  + f" | rod only: fisher {rod / 1000:6.1f}k({rod / m - 1:+4.0%})")
    if "--traps" in sys.argv:
        W_ = P.world(root)
        for lv in (1, 15, 30):
            print("\n".join(trap_report(W_, lv)))
