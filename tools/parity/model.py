"""Balance model of the economy content (read-only).

load(content_dir) reads <content_dir>/{shop.yml, machines.yml, crops/crops.yml, fish.yml, jobs.yml} and returns a World with
one row per machine recipe and per crop. Every number the rules use is computed here, so rules.py stays a list of small
named checks (the Java BalanceTest ports both files 1:1).

Valuation (the framework's "what a player sees"):
  * an item is worth its shop sell base;
  * an item the shop does not buy is worth SHADOW (fixed table, part of the spec);
  * a group input ('#economy:x') is worth its median member (lo / hi = cheapest / dearest member);
  * a vanilla tag is worth TAG_VALUE (fixed table).
World.valued(r, pick, case) values one recipe again under a scenario: every group input at its typical (median), cheapest or
dearest member (pick = "typ" / "lo" / "hi"), and every sized fish (rare, legend: it keeps its size and the shop pays base x
FishSpec.priceFactor) at the mean price factor of a typical catch (case = None: base, "lv1": any player with no perfect hit,
"lv20": a Lv20 fisher with 2 perfect hits and +10% size). The rules compare recipes under each scenario (R4d, R5c, R9a, R9b).
"""
import statistics
from pathlib import Path

import yaml

# ---------------------------------------------------------------------------------------------------------- constants
SHADOW = {  # vanilla items the shop does not buy (G each)
    "minecraft:beef": 3, "minecraft:porkchop": 3, "minecraft:chicken": 2, "minecraft:sugar": 1, "minecraft:apple": 2,
    "minecraft:honey_bottle": 4, "minecraft:sweet_berries": 0.5, "minecraft:kelp": 0.2, "minecraft:brown_mushroom": 1,
    "minecraft:amethyst_shard": 1, "minecraft:glowstone_dust": 1, "minecraft:torch": 0.3, "minecraft:bone_meal": 0.2,
    "minecraft:clay_ball": 0.5, "minecraft:stick": 0.1, "minecraft:wheat_seeds": 0.05, "minecraft:beetroot_seeds": 0.05,
}
TAG_VALUE = {"#small_flowers": 0.2, "#saplings": 0.2, "#leaves": 0.05, "#logs": 1.0, "#stone_crafting_materials": 0.0}
# sized fish (FishSpec.size / priceFactor): the mean price factor of a typical catch, over u = (i + 0.5) / SIZE_SAMPLES
SIZED_RARITIES = {"rare", "legend"}      # fish.yml rarities whose catch keeps its size (FishingModule)
SIZE_SAMPLES = 1000
SIZE_CASES = {                           # case: (perfect hits, all perfect, size bonus)
    "lv1": (0, False, 0.0),              # any player: no perfect hit, no fisher bonus
    "lv20": (2, False, 0.10),            # a Lv20 fisher's typical catch: 2 perfect hits, +10% size (R5b)
}
PICKS = ("typ", "lo", "hi")              # a group input at its median / cheapest / dearest member
JOB_MACHINES = ["gem_bench", "mill", "fish_bench", "forge", "kitchen", "still"]
CAPSTONE = {  # the top good of each machine (full-chain multiple)
    "gem_bench": "economy:jeweled_crown", "mill": "economy:jang_set", "fish_bench": "economy:seafood_feast",
    "forge": "economy:ornate_armor", "kitchen": "economy:hanjeongsik", "still": "economy:vintage_wine",
}


def norm(i):
    i = str(i).strip().lower()
    return i if i.startswith("#") or ":" in i else "minecraft:" + i


def r2(x):
    """Every ratio / G per minute the rules compare is rounded to 2 decimals first (as a player reads it), half up exactly like
    Java's Math.round(x * 100) / 100.0."""
    import math
    return x if not math.isfinite(x) else math.floor(x * 100 + 0.5) / 100


def size_of(v, u, perfect, all_perfect, bonus):
    """FishSpec.size: towards the small end (u^1.4), +3% per perfect hit, +10% all perfect, + the fisher bonus, at most 115% of
    the biggest, rounded half up to 0.1."""
    import math
    lo, hi = v["size"]
    x = lo + (hi - lo) * u ** 1.4
    x *= 1 + 0.03 * perfect
    if all_perfect:
        x *= 1.10
    x *= 1 + bonus
    return math.floor(min(x, hi * 1.15) * 10 + 0.5) / 10


def price_factor(v, size):
    """FishSpec.priceFactor: 0.7 at the smallest, 1.5 at the biggest (and past it)."""
    lo, hi = v["size"]
    k = 1.0 if hi <= lo else min(1.0, max(0.0, (size - lo) / (hi - lo)))
    return 0.7 + 0.8 * k


def mean_factor(v, case):
    """Mean FishSpec.priceFactor of a typical catch of a species in a SIZE_CASES case."""
    perfect, allp, bonus = SIZE_CASES[case]
    n = SIZE_SAMPLES
    return sum(price_factor(v, size_of(v, (i + 0.5) / n, perfect, allp, bonus)) for i in range(n)) / n


def r1(x):
    """Half-up to 1 decimal (Java: Math.round(x * 10) / 10.0)."""
    import math
    return x if not math.isfinite(x) else math.floor(x * 10 + 0.5) / 10


# ---------------------------------------------------------------------------------------------------------- world
class World:
    def __init__(self, root):
        self.root = Path(root)
        rd = lambda f: yaml.safe_load((self.root / f).read_text(encoding="utf-8"))
        self.shop_raw = rd("shop.yml")
        self.mach_raw = rd("machines.yml")
        self.crops_raw = rd("crops/crops.yml")
        self.fish_raw = rd("fish.yml")
        self.jobs_raw = rd("jobs.yml")
        self.req_raw = rd("requests.yml")
        # special materials (jobs.yml specials): the shop does not buy them; their reference value prices the recipes that take them
        self.special = {norm(k): float(v.get("value", 0)) for k, v in (self.jobs_raw.get("specials") or {}).items()}
        # sell lines
        self.sell, self.q, self.cap, self.cat = {}, {}, {}, {}
        for c, cc in self.shop_raw["sell"].items():
            for k, v in cc["items"].items():
                i = norm(k)
                self.sell[i] = float(v["base"])
                self.q[i] = v.get("q")
                self.cap[i] = v.get("cap")
                self.cat[i] = c
        # groups ("#economy:x" -> members)
        self.groups = {"#economy:" + k: [norm(x) for x in v["items"]] for k, v in self.mach_raw["groups"].items()}
        # machines
        self.machines = {k: v for k, v in self.mach_raw.items() if isinstance(v, dict) and "recipes" in v}
        self.upgrade = self.mach_raw["upgrade"]
        self.made_on = {}
        for mid, m in self.machines.items():
            for r in m["recipes"]:
                self.made_on.setdefault(norm(r["out"]), mid)
        # fish species (fish.yml): an 'item' key names a vanilla fish, else economy:<key>
        self.species = {}
        for k, v in (self.fish_raw.get("species") or {}).items():
            self.species[norm(v["item"]) if "item" in v else "economy:" + k] = v
        self._factor, self._valued = {}, {}
        self.rows = [self._row(mid, i, r) for mid, m in self.machines.items() for i, r in enumerate(m["recipes"])]
        self.by_out = {}
        for r in self.rows:
            self.by_out.setdefault(r["out"], []).append(r)

    # ---- values
    def value(self, item):
        if item in self.groups:
            return statistics.median(self.members(item))
        if item.startswith("#"):
            return TAG_VALUE.get(item, 0.0)
        return self.sell.get(item, SHADOW.get(item, self.special.get(item, 0.0)))

    def members(self, key):
        """Values of everything a key takes (a group's members, nested tags by their table value)."""
        if key in self.groups:
            out = []
            for m in self.groups[key]:
                out += self.members(m) if m in self.groups else [self.value(m)]
            return out or [0.0]
        return [self.value(key)]

    def sized(self, item):
        s = self.species.get(item)
        return bool(s) and s["rarity"] in SIZED_RARITIES

    def factor(self, item, case):
        """The size price factor an item is worth in a case: 1.0 for anything unsized or case None."""
        if case is None or not self.sized(item):
            return 1.0
        key = (item, case)
        if key not in self._factor:
            self._factor[key] = mean_factor(self.species[item], case)
        return self._factor[key]

    def member_values(self, key, case=None):
        """Values of everything an input key takes, each sized fish at its typical size in the case."""
        if key in self.groups:
            out = []
            for m in self.groups[key]:
                out += self.member_values(m, case) if m in self.groups else [self.value(m) * self.factor(m, case)]
            return out or [0.0]
        return [self.value(key) * self.factor(key, case)]

    def valued(self, r, pick="typ", case=None):
        """A recipe valued under a scenario (see the module doc): input value, ratio, G/min and per-main (its main input stays the
        one of the median valuation)."""
        key = (r["m"], r["idx"], pick, case)
        if key in self._valued:
            return self._valued[key]
        cost, part = 0.0, {}
        for k, n in r["ins"].items():
            vals = self.member_values(k, case)
            v = statistics.median(vals) if pick == "typ" else min(vals) if pick == "lo" else max(vals)
            part[k] = n * v
            cost += n * v
        mins = r["sec"] / 60.0
        self._valued[key] = dict(cost=cost, ratio=r["ov"] / cost if cost else float("inf"), gmin=(r["ov"] - cost) / mins,
                                 per_main=(r["ov"] - (cost - part[r["main"]])) / r["ins"][r["main"]], pick=pick, case=case)
        return self._valued[key]

    def fits(self, key, item):
        """Can an item id go into an input key (same id, or a member of the group)."""
        if key == item:
            return True
        return key in self.groups and item in self.groups[key]

    def overlap(self, k1, k2):
        """Do two input keys take a common item."""
        a = set(self.groups.get(k1, [k1]))
        b = set(self.groups.get(k2, [k2]))
        return bool(a & b)

    # ---- one recipe
    def _row(self, mid, idx, r):
        ins = {norm(k): int(v) for k, v in r["in"].items()}
        out = norm(r["out"])
        amt = int(r.get("amount", 1))
        sec = float(r.get("seconds", 10))
        tier = int(r.get("tier", 0))
        aging = bool(r.get("aging", False))
        typ = lo = hi = 0.0
        part = {}
        for k, n in ins.items():
            vals = self.members(k)
            v = statistics.median(vals)
            typ += n * v
            lo += n * min(vals)
            hi += n * max(vals)
            part[k] = n * v
        made = [k for k in ins if k in self.made_on]
        ov = amt * self.sell.get(out, 0.0)
        main = max(ins, key=lambda k: (part[k], k))  # the input with the most value (ties: by id)
        others = typ - part[main]
        cls = "R" if len(made) == 0 else "C1" if len(made) == 1 else "C2"
        mins = sec / 60.0
        return dict(
            m=mid, idx=idx, out=out, name=out.split(":")[-1], amt=amt, sec=sec, tier=tier, aging=aging, ins=ins, part=part,
            typ=typ, lo=lo, hi=hi, ov=ov, unit=self.sell.get(out, 0.0),
            ratio=ov / typ if typ else float("inf"),
            worst=ov / hi if hi else float("inf"),
            best=ov / lo if lo else float("inf"),
            gmin=(ov - typ) / mins,
            cls=cls, made=made, job=self.machines[mid].get("job"),
            main=main, main_n=ins[main], per_main=(ov - others) / ins[main],
            groups=[k for k in ins if k in self.groups],
        )

    def kinds(self, r):
        return set(r["ins"])

    def row(self, out, m=None, nth=0):
        hits = [r for r in self.rows if r["out"] == norm(out) and (m is None or r["m"] == m)]
        return hits[nth]

    # ---- auto-pick (MachineSpec.auto): which recipes a lane holding these items could make
    def lane_items(self, r, pick):
        """A lane content that satisfies r: for each key, `pick` chooses a member (default: the key itself / first member)."""
        have = {}
        for k, n in r["ins"].items():
            item = pick(k)
            have[item] = have.get(item, 0) + n
        return have

    def enough(self, r, have):
        for k, n in r["ins"].items():
            if sum(c for i, c in have.items() if self.fits(k, i)) < n:
                return False
        return True

    def only(self, r, have):
        return all(any(self.fits(k, i) for k in r["ins"]) for i in have)

    def auto(self, mid, have, tier):
        cands = [r for r in self.rows if r["m"] == mid and r["tier"] <= tier and self.enough(r, have) and self.only(r, have)]
        return min(cands, key=lambda r: (-r["tier"], r["idx"])) if cands else None

    # ---- crops
    def crop_rows(self):
        out = []
        for cid, c in self.crops_raw.items():
            if not isinstance(c, dict) or "harvest" not in c:
                continue
            h = c["harvest"][0]
            item = norm(h["item"]) if ":" in str(h["item"]) else "economy:" + h["item"]
            lo, hi = int(h.get("min", 1)), int(h.get("max", h.get("min", 1)))
            avg = (lo + hi) / 2
            seed = c["seed"]
            net = avg - (1 if seed == h["item"] else 0)
            ma = int(c.get("max-age", 7))
            rg = c.get("regrow")
            steps = ma - int(rg) if rg is not None else ma
            base = self.sell.get(item, 0.0)
            out.append(dict(crop=cid, item=item, base=base, net=net, steps=steps, regrow=rg is not None, max_age=ma,
                            regrow_to=rg, per_harvest=net * base, plot24=net * base * 7 / steps, lo=lo, hi=hi))
        return out


def load(root):
    return World(root)
