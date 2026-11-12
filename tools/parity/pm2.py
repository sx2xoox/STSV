"""Parity model 2: balance2's job model (jobs_model.py) reading a content dir, with the corrections and the content mechanics of
the 2026-11 parity rebalance. Read only over the content. mk_parity.py runs it (BalanceTest R17 / R18).

Changes to the parity model (each stated in parity2.md):
  * supply grows with play: what a player can buy from others a day (ABUNDANT_DAY a common good, SCARCE_Q x q a scarce one) is
    x hours/3 (everyone in the world plays the scale's hours, so the suppliers make that much more);
  * farmer effects from jobs.yml: harvest-pct [Lv1, Lv30] (custom crops, a share more of the produce), harvest-bonus [Lv1, Lv30]
    (vanilla crops, items a harvest), row-harvest level (1x3) and area-harvest level (3x3);
  * special materials (jobs.yml specials): rolled per harvest / catch / ore block by the job's chance (anyone: outsider); the
    shop does not buy them: they are sold to other players at TRADE x value (the reference value) up to what the others buy, the
    rest kept; a processor buys them at TRADE x value up to one specialist's daily finds;
  * custom crop caps: whatever shop.yml says (none = the curve only);
  * farmer surplus: crops the field gives a day (every ripe plot harvested) minus what leaves the farmer (shop, mill, others);
  * the fisher (2026-11 통발·가두리): the rod fishes ROD_HOUR of an active hour (jobs_model: walking between the waters, the
    unstackable sized catch, lost casts; the miner's and farmer's hours already have their walking in) less the time the traps and
    pens take; TRAPS[lv] traps and PENS[lv] pens (x TRAP_SCALE for the scale) are emptied every active hour and once more at the
    start of the next session (what they made overnight, up to their storage); traps catch in the rod's three waters, pens farm the
    dearest commons / uncommons and eat 생선 사료 bought from others (Opt.traps = False: the rod only, as before).
"""
import json
import math
import os
import sys
from collections import defaultdict
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import model as _M  # noqa: E402,F401  (this folder's model.py: specials' reference values)
import jobs_model as J  # noqa: E402

WT = os.path.join(HERE, "..", "..", "economy", "content", "economy")
JOBS = ["miner", "farmer", "fisher", "blacksmith", "cook", "alchemist"]
KO = {"miner": "광부", "farmer": "농부", "fisher": "어부", "blacksmith": "대장장이", "cook": "요리사", "alchemist": "연금술사"}
LEVELS = (1, 10, 15, 20, 30)
J.LEVEL_TIER.update({10: 1, 20: 2})
GATHER = ("farmer", "fisher", "miner")
TRADE = 0.85          # specials between players, x the reference value
ORE_BLOCKS = sum(J.ORES.values())   # ore blocks a mining hour (119)


@dataclass
class Scale:
    name: str
    hours: float
    machines: int
    plots: dict


SCALES = {
    "casual": Scale("casual", 1, 1, {1: 300, 10: 400, 15: 500, 20: 700, 30: 1000}),
    "regular": Scale("regular", 3, 2, {1: 500, 10: 1200, 15: 2000, 20: 3000, 30: 5000}),
    "hardcore": Scale("hardcore", 6, 4, {1: 1000, 10: 3000, 15: 5000, 20: 7000, 30: 10000}),
}
# Parity scales (same effort = same hours and comparable investment): only the hours change; machines (2 of the job) and
# the field (the regular one of the level) are the level's investment, the same at 1, 3 and 6 hours.
PLOTS = {1: 500, 10: 1200, 15: 2000, 20: 3000, 30: 5000}
OLD_SCALES = dict(SCALES)
if os.environ.get("PARITY_FIXED") == "1":
    SCALES = {
        "casual": Scale("casual", 1, 2, PLOTS),
        "regular": Scale("regular", 3, 2, PLOTS),
        "hardcore": Scale("hardcore", 6, 2, PLOTS),
    }
SC = ("casual", "regular", "hardcore")
# traps and pens a fisher keeps at a scale, x the regular player's (jobs_model TRAPS / PENS): an hour a day tends half as many, six
# hours half as many again (like the field and the machines: more hours, a bigger investment)
TRAP_SCALE = {"casual": 0.5, "regular": 1.0, "hardcore": 1.5}


@dataclass
class Opt:
    root: str = WT
    supply_scale: bool = True        # purchases a day x hours / 3
    specials: bool = True            # roll the special materials
    p2p: float = None                # trade model: gatherer goods bought by others at p x base
    p2p_sell: dict = field(default_factory=dict)  # gatherer: item -> units a day other players buy from me
    special_demand: dict = field(default_factory=dict)  # gatherer: special item -> units a day others buy (None: unlimited at TRADE)
    selfgather: bool = False         # processor solo: gathers its own staple (no job perks), buys nothing from gatherer jobs
    no_buy_jobs: tuple = ()          # goods of these jobs cannot be bought (solo)
    traps: bool = True               # the fisher keeps traps and pens (jobs_model TRAPS / PENS); False: the rod only
    outsider: bool = False           # simulate("fisher") as another job's player fishing: the rod only, no fisher perks (outsider
    #                                  chances, vanilla wait), no traps, no machine, the catch sold raw (outsider_fishing)


_W = {}


def world(root):
    if root in _W:
        return _W[root]
    W_ = J.W(root)
    W_.jobof = job_of_map(W_)
    sp = (W_.jobs.get("specials") or {})
    W_.specials = {J.M.norm(k): v for k, v in sp.items()}
    for i, v in W_.specials.items():
        W_.jobof[i] = v["job"]
    _W[root] = W_
    return W_


def svalue(W_, i):
    s = W_.specials.get(i)
    return float(s["value"]) if s else W_.value(i)


def job_of_map(W_):
    out = {}
    for cat, cc in W_.shop["sell"].items():
        for k, v in cc["items"].items():
            i = J.M.norm(k)
            if v.get("job"):
                out[i] = v["job"]
            elif cat == "farm":
                out[i] = "farmer"
            elif cat == "fish":
                out[i] = "fisher"
    for o, mid in W_.w.made_on.items():
        j = W_.mach[mid].get("job")
        if j and o not in out:
            out[o] = j
    return out


def rng(v, lv):
    if isinstance(v, list):
        return v[0] + (v[1] - v[0]) * (lv - 1) / 29
    return float(v)


def special_chance(W_, item, lv, is_job):
    s = W_.specials[item]
    return rng(s["chance"], lv) if is_job else float(s.get("outsider", 0))


def specials_on(W_, on, lv, job):
    """{item: chance per event} of the specials rolled on an event kind for a player of `job`."""
    return {i: special_chance(W_, i, lv, s["job"] == job) for i, s in W_.specials.items() if s.get("from") == on}


# ----------------------------------------------------------------------------------------------- gathering
def farmer_cfg(W_, lv, farmer=True):
    jj = W_.jobs["jobs"]["farmer"]
    if not farmer:
        return 0.0, 0.0, 1
    pct = rng(jj["harvest-pct"], lv) if "harvest-pct" in jj else jj.get("harvest-pct-per-level", 0) * lv
    van = rng(jj["harvest-bonus"], lv) if "harvest-bonus" in jj else jj.get("harvest-per-level", 0) * lv
    area = 1
    if jj.get("row-harvest") and lv >= jj["row-harvest"]:
        area = 3
    if jj.get("area-harvest") and lv >= jj["area-harvest"]:
        area = 9
    return pct, van, area


def farm_supply(W_, lv, plots, farmer=True, job="farmer"):
    """Per harvest: units of each crop; harvests an active hour (labour or ripening binds); specials per harvest."""
    pct, van, area = farmer_cfg(W_, lv, farmer)
    supply, ripe = {}, {}
    for c in W_.w.crop_rows():
        supply[c["item"]] = c["net"] * (1 + pct) * 1.45
        ripe[c["item"]] = 17.5 / c["steps"]
    for item, (net, fort, ages) in J.VANILLA_CROPS.items():
        supply[item] = net + fort + van
        ripe[item] = 17.5 / ages
    harvests = min(J.CLICKS_PER_H * area, plots * max(ripe.values()))
    sp = specials_on(W_, "harvest", lv, job) if True else {}
    return supply, harvests, sp, ripe


def fishing(W_, lv, habitat, job):
    catch, n = J.fishing_hour(W_, lv, habitat, fisher=(job == "fisher"))
    sp = specials_on(W_, "catch", lv, job)
    return catch, n, sp


def mining(W_, lv, job):
    out = dict(J.mining_hour(W_, lv, miner=(job == "miner")))
    sp = specials_on(W_, "break", lv, job)
    return out, sp


# ----------------------------------------------------------------------------------------------- market
class Market(J.Market):
    def __init__(self, W_, job, opt, lv, scale):
        super().__init__(W_, job)
        self.opt, self.lv, self.scale = opt, lv, scale
        self.fs = scale.hours / 3 if opt.supply_scale else 1.0

    def price(self, i):
        if i in self.W.specials:
            return (self.opt.p2p or TRADE) * svalue(self.W, i)
        if self.opt.p2p and self.W.jobof.get(i) in GATHER and self.W.jobof.get(i) != self.job:
            return self.opt.p2p * self.W.value(i)
        return super().price(i)

    def left(self, i):
        j = self.W.jobof.get(i)
        if j in self.opt.no_buy_jobs and j != self.job:
            return 0.0
        if i in self.W.specials:
            if not self.opt.specials:
                return 0.0
            return max(0.0, specialist_supply(self.W, i, self.lv, self.scale) - self.bought[i])
        # the day's units are kept in value for goods whose price the rebalance lowered (cheap bulk trades in more units)
        k = max(1.0, orig_base(i) / self.W.base[i]) if self.W.base.get(i) else 1.0
        if i in self.abundant or i not in self.W.base:
            return max(0.0, J.ABUNDANT_DAY * self.fs * k - self.bought[i])
        return max(0.0, J.SCARCE_Q * self.W.q[i] * self.fs * k - self.bought[i])


_SUP = {}
ORIG_BASE = os.path.join(HERE, "orig_base.json")
_OB = {}


def orig_base(i):
    """Sell base before the 2026-11 rebalance (orig_base.json, frozen from the content it started from)."""
    if not _OB:
        with open(ORIG_BASE, encoding="utf-8") as f:
            _OB.update(json.load(f))
    return _OB.get(i, 0.0)


def specialist_supply(W_, item, lv, scale):
    """Units a day one specialist of the item's job finds at this level and scale."""
    key = (id(W_), item, lv, scale.name)
    if key in _SUP:
        return _SUP[key]
    s = W_.specials[item]
    job = s["job"]
    ch = special_chance(W_, item, lv, True)
    if s["from"] == "harvest":
        _, harvests, _, _ = farm_supply(W_, lv, scale.plots[lv], True, "farmer")
        per_h = harvests * ch
    elif s["from"] == "catch":
        _, n = J.fishing_hour(W_, lv, J.best_waters(W_, lv)[0], fisher=True)
        per_h = n * ch
    else:
        per_h = ORE_BLOCKS * ch
    _SUP[key] = per_h * scale.hours
    return _SUP[key]


class SLedger(J.Ledger):
    """A special material's worth to its holder is what another player pays for it (the shop does not buy it)."""

    def price(self, item, extra=0.0):
        if item in self.W.specials:
            return TRADE * svalue(self.W, item)
        return super().price(item, extra)


# ----------------------------------------------------------------------------------------------- one day
def outsider_fishing(lv, scale, root=WT):
    """Net G a day of a player of another job who only fishes for the scale's hours (Opt.outsider): no fisher perks, no traps."""
    return simulate("fisher", lv, scale, Opt(root=root, traps=False, outsider=True))


def simulate(job, lv, scale, opt=None, detail=False):
    opt = opt or Opt()
    W_ = world(opt.root)
    curve = J.curve_of(W_.shop)
    J.TAX_BRACKETS = J.tax_of(W_.shop)
    hours = scale.hours
    active = int(math.ceil(hours))
    L = SLedger(W_, curve)
    tier = J.LEVEL_TIER[lv]
    mid = J.JOB_MACHINE[job]
    routes = PRoutes(W_, mid, tier, job, lv)
    lanes_t = W_.mach[mid].get("lanes") or W_.up["lanes"]
    lanes = 0 if opt.outsider else lanes_t[tier] * scale.machines
    mk = Market(W_, job, opt, lv, scale)
    habitats = J.best_waters(W_, lv) if job == "fisher" else None
    plots = scale.plots[lv]
    traps = pens = 0
    tending = 0.0
    pen_fish = []
    if job == "fisher" and opt.traps and not opt.outsider:
        k = TRAP_SCALE.get(scale.name, 1.0)
        traps, pens = round(J.TRAPS[lv] * k), round(J.PENS[lv] * k)
        tending = (traps * J.TRAP_VISIT_S + pens * J.PEN_VISIT_S) / 3600
        trap_waters = habitats
        trap_h, trap_night = J.trap_hour(W_, lv)
        pen_fish = J.pen_species(W_, pens)
        pen_h, pen_night, pen_feed = J.pen_day(W_, lv, hours)
    overnight = {}
    last = None
    for d in range(J.DAYS):
        got = 0.0
        det = defaultdict(float)
        src = defaultdict(float)
        p2p_short = defaultdict(float)
        field_units = 0.0     # farmer: crops the whole ripe field gives today
        left_units = 0.0      # farmer: crops that leave (shop, mill, others)
        sp_have = defaultdict(float)
        surplus = 0.0         # farmer: harvested crops nobody takes today (they pile up)
        capacity = 0.0        # farmer: crops the whole field harvested by hand gives (labour and handling bound, an even mix)

        def sell(i, n, f=1.0, tag="raw"):
            nonlocal got
            v = L.sell(i, n, f)
            got += v
            det[i] += v
            src[tag] += v
        for g, n in overnight.items():
            sell(g, n, tag="overnight")
        for h in range(active):
            frac = min(1.0, hours - h)
            own, fac = defaultdict(float), {}
            harvests = 0.0
            supply = {}
            sp_rate = {}
            events = 0.0
            if job == "fisher":
                catch, n, sp_rate = fishing(W_, lv, habitats[h % len(habitats)], "outsider" if opt.outsider else job)
                rod = J.ROD_HOUR - tending       # the rod's share of the hour: walking, the catch, the traps and pens
                for i, (c, f_) in catch.items():
                    own[i] += c * frac * rod
                    fac[i] = f_
                events = n * frac * rod
                # traps and pens (no special materials: those come with a rod's catch), emptied every hour; their overnight
                # catch at the start of the session
                if traps:
                    for i, (c, f_) in J.trap_waters_catch(W_, lv, trap_waters, traps * (trap_h * frac + (trap_night if h == 0 else 0))).items():
                        fac[i] = (fac.get(i, 1.0) * own[i] + f_ * c) / (own[i] + c) if own[i] + c else f_
                        own[i] += c
                for i in pen_fish:
                    own[i] += pen_h * frac + (pen_night if h == 0 else 0)
                if h == 0 and pens:
                    cost = pens * pen_feed * mk.price(J.PEN_FEED)
                    mk.bought[J.PEN_FEED] += pens * pen_feed
                    got -= cost
                    det["(bought)"] -= cost
            elif job == "miner":
                ores, sp_rate = mining(W_, lv, job)
                for i, (c, f_) in ores.items():
                    own[i] += c * frac
                events = ORE_BLOCKS * frac
            elif job == "farmer":
                supply, harvests, sp_rate, ripe = farm_supply(W_, lv, plots, True, job)
                fld = Field(plots * frac, J.CLICKS_PER_H * frac * farmer_cfg(W_, lv)[2], J.HANDLE * frac, supply, ripe)
                cust = [i for i in supply if i.startswith("economy:")]
                capacity += min(J.HANDLE * frac, plots * frac * sum(ripe[i] * supply[i] for i in cust) / len(cust),
                                fld.C * sum(supply[i] for i in cust) / len(cust))
                for i in supply:
                    own[i] = fld.most(i)
            elif opt.selfgather and job == "blacksmith":
                # solo: mines its own ores (no miner perks) while the forge runs
                ores, sp_rate = mining(W_, lv, job)
                for i, (c, f_) in ores.items():
                    own[i] += c * frac
                events = ORE_BLOCKS * frac
            elif opt.selfgather and job in ("cook", "alchemist"):
                # solo: farms its own field (no farmer perks) while the machine runs; sells nothing raw (keeps it for the machine)
                supply, harvests, sp_rate, ripe = farm_supply(W_, lv, plots, False, job)
                fld = Field(plots * frac, J.CLICKS_PER_H * frac, J.HANDLE * frac, supply, ripe)
                for i in supply:
                    own[i] = fld.most(i) / len(supply)   # the field split evenly over the crops it grows
                supply = {}
            if opt.specials and job != "farmer":
                for i, c in sp_rate.items():
                    sp_have[i] += events * c
                    own[i] += events * c
            if opt.p2p_sell:
                for i, want in opt.p2p_sell.items():
                    need = want * frac / hours
                    if job == "farmer":
                        if i not in supply or supply[i] <= 0:
                            continue
                        take = min(need, fld.most(i))
                        fld.take(i, take)
                    else:
                        take = min(need, own.get(i, 0.0))
                        own[i] = own.get(i, 0.0) - take
                    cash = take * opt.p2p * W_.value(i) * fac.get(i, 1.0)
                    got += cash
                    src["p2p"] += cash
                    left_units += take if job == "farmer" else 0.0
                    p2p_short[i] += need - take
                if job == "farmer":
                    for i in supply:
                        own[i] = fld.most(i)
            gathered = sum(own.values()) if job not in J.CROP_JOBS else 0.0
            made, used, spent = J.run_machine(W_, L, routes, lanes * 3600 * frac, own, mk, defaultdict(float),
                                              handle=max(0.0, J.HANDLE * frac - gathered))
            got -= spent
            det["(bought)"] -= spent
            for g, n in made.items():
                sell(g, n, tag="machine")
            for i in sp_have:
                sp_have[i] -= used.get(i, 0.0)
                own[i] = 0.0
            if supply:
                for i in supply:
                    if used[i] > 0:
                        fld.take(i, used[i])
                fld.H = max(0.0, fld.H - sum(made.values()))
                g2, d2, units = sell_crops(L, fld)
                if opt.specials:
                    for i, c in sp_rate.items():
                        sp_have[i] += fld.events * c
                field_units += fld.units
                got += g2
                src["raw"] += g2
                left_units += units + sum(used[i] for i in supply)
                for k, v in d2.items():
                    det[k] += v
            else:
                for i, n in own.items():
                    if i in W_.specials or (opt.selfgather and job in ("cook", "alchemist")):
                        continue
                    if n - used[i] > 0:
                        sell(i, n - used[i], fac.get(i, 1.0))
            L.hours(frac)
        # specials found today: sold to other players at TRADE x value (up to their demand)
        for i, n in sp_have.items():
            if n <= 0:
                continue
            want = opt.special_demand.get(i, math.inf) if opt.special_demand is not None else math.inf
            take = min(n, want)
            cash = take * (opt.p2p or TRADE) * svalue(W_, i)
            got += cash
            src["specials"] += cash
        # away: lanes filled once more, each runs until its 64 output items are full (shared by the chain's steps)
        away = 24 - hours
        own = defaultdict(float)
        if job == "farmer":
            supply, _, _, _ = farm_supply(W_, lv, plots, True, job)
            own = {i: 1e9 for i in supply}
        elif opt.selfgather and job in ("cook", "alchemist"):
            sup2, _, _, _ = farm_supply(W_, lv, plots, False, job)
            own = {i: 1e9 for i in sup2}
        made, used, spent = J.run_machine(W_, L, routes, lanes * away * 3600, own, mk, defaultdict(float))
        tot = sum(n * routes.items(g) for g, n in made.items())
        scale_ = min(1.0, J.LANE_STACK * lanes / tot) if tot else 0.0
        overnight = {g: n * scale_ for g, n in made.items()}
        if job == "farmer":
            left_units += sum(used.get(i, 0) * scale_ for i in supply)
        got -= spent * scale_
        det["(bought)"] -= spent * scale_
        L.hours(away)
        bought_today = dict(mk.bought)
        sold_today = dict(L.sold_today)
        L.day()
        mk.day()
        gross = sum(v for k, v in det.items() if not k.startswith("("))
        tax = J.income_tax(gross)
        det["(sales tax)"] = -tax
        got -= tax
        last = dict(net=got, det=dict(det), src=dict(src), bought=bought_today, short=dict(p2p_short), sold=sold_today,
                    field=field_units, left=left_units, surplus=surplus, capacity=capacity, specials=dict(sp_have))
    if detail:
        return last
    return last["net"]


class PRoutes(J.Routes):
    def items(self, g, depth=0):
        r = self.makes[g]
        per = 1.0 / (int(r.get("amount", 1)) * self.out_mult)
        tot = 1.0
        for k, n in r["in"].items():
            k = J.M.norm(k)
            if k in self.W.w.groups:
                vals = [(self.W.value(x), x) for x in self.W.w.groups[k] if not x.startswith("#")]
                k = min(vals)[1] if vals else k
            if k in self.makes and depth < 6 and k != g:
                tot += n * (1 - self.save) * per * self.items(k, depth + 1)
        return tot


class Field:
    """An active hour of farming: P plot-hours (a plot of crop i gives ripe[i] harvests an hour), C harvest events (clicks x
    area), H items handled. One unit of crop i costs 1 / (supply x ripe) plot-hours, 1 / supply events and 1 item."""

    def __init__(self, P, C, H, supply, ripe):
        self.P, self.C, self.H, self.s, self.r = P, C, H, supply, ripe
        self.events = 0.0
        self.units = 0.0

    def most(self, i):
        return max(0.0, min(self.P * self.r[i] * self.s[i], self.C * self.s[i], self.H))

    def use(self, i):
        """Share of what is left one unit of i takes (the binding resource)."""
        return max(1 / (self.s[i] * self.r[i]) / max(self.P, 1e-9), 1 / self.s[i] / max(self.C, 1e-9), 1 / max(self.H, 1e-9))

    def take(self, i, n):
        self.P = max(0.0, self.P - n / (self.s[i] * self.r[i]))
        self.C = max(0.0, self.C - n / self.s[i])
        self.H = max(0.0, self.H - n)
        self.events += n / self.s[i]
        self.units += n


def sell_crops(L, fld, steps=400):
    """Raw crops: each step the crop with the best price per share of the binding resource, until the field (plot-hours,
    clicks or handling) is used up or nothing sells. Returns (G, {item: G}, units)."""
    got, det = 0.0, defaultdict(float)
    units = 0.0
    for _ in range(steps * 3):
        best = None
        for i in fld.s:
            if fld.most(i) <= 1e-6:
                continue
            p = L.price(i)
            if p <= 0:
                continue
            k = p / fld.use(i)
            if best is None or k > best[0]:
                best = (k, i)
        if best is None:
            break
        i = best[1]
        n = min(fld.most(i), max(1.0, 1.0 / fld.use(i) / steps * 4))
        v = L.sell(i, n)
        fld.take(i, n)
        got += v
        det[i] += v
        units += n
    return got, det, units


# ----------------------------------------------------------------------------------------------- tables
def _one(a):
    j, lv, s, opt = a
    return (s, lv, j), simulate(j, lv, SCALES[s], opt)


def table(opt=None, levels=LEVELS, scales=SC, pool=None):
    tasks = [(j, lv, s, opt or Opt()) for s in scales for lv in levels for j in JOBS]
    res = dict(pool.map(_one, tasks, chunksize=2)) if pool else dict(map(_one, tasks))
    return res


def score(res, levels=LEVELS, scales=SC):
    """Worst deviation from the job mean at a point, and the worst hours-scaling shortfall."""
    worst, pts = 0.0, {}
    for s in scales:
        for lv in levels:
            row = [res[(s, lv, j)] for j in JOBS]
            m = sum(row) / len(row)
            ds = [v / m - 1 for v in row]
            pts[(s, lv)] = ds
            worst = max(worst, max(abs(x) for x in ds))
    scal = {}
    for lv in levels:
        for j in JOBS:
            a, b, c = (res[(s, lv, j)] for s in SC)
            scal[(lv, j)] = (b / a if a > 0 else 0, c / b if b > 0 else 0)
    return worst, pts, scal


def show(res, levels=LEVELS, scales=SC, out=print):
    worst, pts, scal = score(res, levels, scales)
    for s in scales:
        for lv in levels:
            row = {j: res[(s, lv, j)] for j in JOBS}
            m = sum(row.values()) / 6
            out(f"{s:8} Lv{lv:2} " + " ".join(f"{j[:5]} {v/1000:6.1f}k({v/m-1:+4.0%})" for j, v in row.items()) + f"  mean {m/1000:6.1f}k")
    bad = [(k, v) for k, v in scal.items() if v[0] < 2 or v[1] < 1.5]
    out(f"worst {worst:.1%}; scaling fails: " + ", ".join(f"Lv{lv} {j[:5]} {a:.2f}/{b:.2f}" for (lv, j), (a, b) in bad))
    return worst, bad
