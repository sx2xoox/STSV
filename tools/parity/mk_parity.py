"""The parity table of a content dir: net G a day per job, level and hours (pm2.py), the hours ratios and the trade table (trade2.py).

    python3 tools/parity/mk_parity.py                 # regenerate economy/src/test/resources/balance/parity.json
    python3 tools/parity/mk_parity.py --add           # ... and list new cells outside 15% as exceptions (give each a real reason!)
    python3 tools/parity/mk_parity.py --json <dir>    # print the table of a content dir as JSON (BalanceTest R17 / R18 run this)

Scales: casual = 1h a day, 1 machine, small field; regular = 3h, 2 machines; hardcore = 6h, 4 machines, big field (Lv1 0강, Lv10/15
1강, Lv20 2강, Lv30 3강). parity.json keeps its curated `exceptions` (cells allowed outside 15%, each with its reason, up to 35%);
regenerating never adds one unless --add is given. `content_sha1` = sha1 over CONTENT_FILES in this order (BalanceTest computes the
same to tell whether the stored table is current when it cannot run Python). Needs python3 and PyYAML.
"""
import hashlib
import json
import os
import sys
from multiprocessing import Pool
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import pm2 as P  # noqa: E402
import trade2  # noqa: E402

REPO = HERE.parent.parent
CONTENT = REPO / "economy/content/economy"
REPO_JSON = REPO / "economy/src/test/resources/balance/parity.json"
CONTENT_FILES = ("shop.yml", "machines.yml", "jobs.yml", "crops/crops.yml", "fish.yml", "requests.yml")
BAND = 0.15
ABOUT = ("Parity model (tools/parity: pm2.py + trade2.py, mk_parity.py writes this file) on this content: net G a day per job, level "
         "and hours (casual 1h 1 machine, regular 3h 2 machines, hardcore 6h 4 machines); hours = [3h/1h, 6h/3h before the sales "
         "tax, the same after it]; trade = one player a job, gatherer goods at p x base; exceptions = curated cells allowed outside "
         "15% (up to 35%), each with its reason. BalanceTest R17 / R18 recompute it from the content and check it.")


def _one(a):
    root, j, lv, s = a
    d = P.simulate(j, lv, P.SCALES[s], P.Opt(root=root), detail=True)
    return (s, lv, j), {"net": d["net"], "tax": -d["det"].get("(sales tax)", 0.0)}


def table(root, pool):
    tasks = [(root, j, lv, s) for s in P.SC for lv in P.LEVELS for j in P.JOBS]
    return dict(pool.map(_one, tasks))


def content_hash(root):
    h = hashlib.sha1()
    for f in CONTENT_FILES:
        h.update((Path(root) / f).read_bytes())
    return h.hexdigest()


def hours(res):
    out = {}
    for lv in P.LEVELS:
        out[str(lv)] = {}
        for j in P.JOBS:
            a, b, c = (res[(s, lv, j)] for s in P.SC)
            pre = [x["net"] + x["tax"] for x in (a, b, c)]
            out[str(lv)][j] = [round(pre[1] / pre[0], 3), round(pre[2] / pre[1], 3), round(b["net"] / a["net"], 3),
                               round(c["net"] / b["net"], 3)]
    return out


def compute(root):
    with Pool(min(8, os.cpu_count() or 2)) as pool:
        res = table(root, pool)
        tr = trade2.run(root, (0.7, 1.0, 1.3), levels=(15, 30), scales=("regular",), pool=pool)
    return {
        "content_sha1": content_hash(root),
        "net": {f"{s}|{lv}": {j: round(res[(s, lv, j)]["net"]) for j in P.JOBS} for s in P.SC for lv in P.LEVELS},
        "hours": hours(res),
        "trade": {k: {j: {"solo": v["solo"], "trade": v["trade"]} for j, v in row.items()} for k, row in tr.items()},
    }


def outside(net, band=BAND):
    """{point|job: deviation from the jobs' mean} of every cell outside the band."""
    out = {}
    for point, row in net.items():
        m = sum(row.values()) / len(row)
        for j, v in row.items():
            d = v / m - 1
            if abs(d) > band:
                out[f"{point}|{j}"] = round(d, 3)
    return out


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--json":
        json.dump(compute(sys.argv[2]), sys.stdout, ensure_ascii=False)
        sys.exit(0)
    t = compute(str(CONTENT))
    old = json.loads(REPO_JSON.read_text(encoding="utf-8")) if REPO_JSON.exists() else {}
    reasons = {e["point"]: e["reason"] for e in old.get("exceptions", [])}
    out = outside(t["net"])
    exc = []
    for point, d in out.items():
        if point in reasons or "--add" in sys.argv:
            exc.append({"point": point, "dev": d, "reason": reasons.get(point, "TODO: why this cell may sit outside 15%")})
        else:
            print(f"outside 15% and not an exception (R17 fails): {point} {d:+.1%}")
    for point in sorted(set(reasons) - set(out)):
        print(f"exception now inside 15% (dropped): {point}")
    repo = {"about": ABOUT, **t, "exceptions": exc}
    REPO_JSON.write_text(json.dumps(repo, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("wrote", REPO_JSON)
