"""Trade (납품) vs solo, one player of each job at the same level and scale.

solo:   gatherers sell only to the shop (a special material is worth nothing to them but in their own machine);
        processors gather their own staple without job perks (blacksmith mines, cook / alchemist farm) and buy nothing from a
        gatherer job (no fish, no gems, no crops, no specials).
trade:  pass 1: every job buys what it needs; gatherer goods (crops, fish, ores, gems, specials) from the matching player at p x
        base (the reference value for a special); pass 2: each gatherer fills those orders from its own gathering (cash, no shop
        curve, no sales tax) and sells the rest to the shop as before.
python3 trade2.py <content dir> [p ...] -> trade.json"""
import copy
import json
import sys
from collections import defaultdict
from multiprocessing import Pool

import pm2 as P

PROC = ("blacksmith", "cook", "alchemist")


def _sim(a):
    job, lv, s, opt = a
    return (job, lv, s), P.simulate(job, lv, P.SCALES[s], opt, detail=True)


def run(root, ps=(0.7, 1.0, 1.3), levels=(30,), scales=("regular",), pool=None):
    W = P.world(root)
    out = {}
    # solo
    tasks = []
    for s in scales:
        for lv in levels:
            for j in P.JOBS:
                if j in P.GATHER:
                    o = P.Opt(root=root, special_demand={i: 0.0 for i in W.specials})
                else:
                    o = P.Opt(root=root, selfgather=True, no_buy_jobs=P.GATHER)
                tasks.append((j, lv, s, o))
    solo = dict(pool.map(_sim, tasks))
    for p in ps:
        o1 = P.Opt(root=root, p2p=p)
        t1 = [(j, lv, s, o1) for s in scales for lv in levels for j in P.JOBS]
        r1 = dict(pool.map(_sim, t1))
        t2, dem = [], {}
        for s in scales:
            for lv in levels:
                for g in P.GATHER:
                    want = defaultdict(float)
                    for b in P.JOBS:
                        if b == g:
                            continue
                        for i, n in r1[(b, lv, s)]["bought"].items():
                            if W.jobof.get(i) == g:
                                want[i] += n
                    sp = {i: n for i, n in want.items() if i in W.specials}
                    goods = {i: n for i, n in want.items() if i not in W.specials}
                    dem[(g, lv, s)] = (goods, sp)
                    t2.append((g, lv, s, P.Opt(root=root, p2p=p, p2p_sell=goods,
                                               special_demand={i: sp.get(i, 0.0) for i in W.specials})))
        r2 = dict(pool.map(_sim, t2))
        for s in scales:
            for lv in levels:
                row = {}
                for j in P.JOBS:
                    if j in P.GATHER:
                        d = r2[(j, lv, s)]
                        goods, sp = dem[(j, lv, s)]
                        wv = sum(n * W.value(i) for i, n in goods.items())
                        sv = sum(n * W.value(i) for i, n in d["short"].items())
                        row[j] = dict(solo=round(solo[(j, lv, s)]["net"]), trade=round(d["net"]), p2p=round(d["src"].get("p2p", 0)),
                                      specials=round(d["src"].get("specials", 0)), short=round(sv / wv, 3) if wv else 0.0)
                    else:
                        d = r1[(j, lv, s)]
                        paid = sum(n * W.value(i) * p for i, n in d["bought"].items()
                                   if W.jobof.get(i) in P.GATHER and i not in W.specials)
                        paid_sp = sum(n * P.svalue(W, i) * p for i, n in d["bought"].items() if i in W.specials)
                        row[j] = dict(solo=round(solo[(j, lv, s)]["net"]), trade=round(d["net"]), paid=round(paid),
                                      paid_specials=round(paid_sp))
                out[f"{p}|{s}|{lv}"] = row
    return out


if __name__ == "__main__":
    root = sys.argv[1]
    ps = tuple(float(x) for x in sys.argv[2:]) or (0.7, 1.0, 1.3)
    with Pool(4) as pool:
        res = run(root, ps, pool=pool)
    json.dump(res, open("trade.json", "w"), ensure_ascii=False, indent=1)
    for k, row in res.items():
        print(k)
        for j, v in row.items():
            print(f"  {j:10} {v}")
