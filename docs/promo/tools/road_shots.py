"""Driver's-eye shots of the real 국도 for the vehicles promo.

Uses the same pipeline as docs/previews/road.png (tools/assetgen/road_preview.py): RoadPreviewTest lays the road with the
plugin's own RoadProfile / RoadSection over a strip of land; here the camera is put low on the road instead of 3/4 above.

    cd economy && ./gradlew test --tests dev.stsv.economy.road.RoadPreviewTest   # economy/build/roads/strip.json (JDK 25)
    (cd tools/assetgen/render && npm install)
    python3 docs/promo/tools/road_shots.py [name ...]                              # docs/promo/vehicles/assets/drive_*.png
"""
import json, shutil, sys
from pathlib import Path
REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'tools' / 'assetgen'))
import road_preview as R  # noqa: E402

OUT = REPO / 'docs' / 'promo' / 'vehicles' / 'assets'
SIZE = tuple(map(int, __import__('os').environ.get('SHOT_SIZE', '2400x1350').split('x')))
def along(strip, x_at, ahead, eye=2.4, lane=1.3, look=1.4, margin=34, up=24, down=8):
    """A view from the driver's seat: on the right lane at the road point nearest x_at, looking `ahead` points on."""
    import math
    pts = strip.points
    i = min(range(len(pts)), key=lambda n: abs(pts[n][0] - x_at) + abs(pts[n][1] - 0.5) * 0.01)
    j = min(len(pts) - 1, i + ahead)
    (x0, z0, y0, _), (x1, z1, y1, _) = pts[i], pts[j]
    dx, dz = x1 - x0, z1 - z0; n = math.hypot(dx, dz) or 1; dx, dz = dx / n, dz / n
    rx, rz = -dz, dx                                   # 오른쪽 차로
    P = (x0 + rx * lane, y0 + eye, z0 + rz * lane)
    T = (x1 + rx * lane, y1 + look, z1 + rz * lane)
    vx, vy, vz = P[0] - T[0], P[1] - T[1], P[2] - T[2]
    d = math.sqrt(vx * vx + vy * vy + vz * vz); el = math.degrees(math.asin(vy / d))
    az = math.degrees(math.atan2(vx, -vz))
    xs, zs = (x0, x1), (z0, z1)
    box = (int(min(xs)) - margin, int(max(xs)) + margin, int(min(zs)) - margin, int(max(zs)) + margin, int(min(y0, y1)) - down, int(max(y0, y1)) + up)
    return box, {'az': az, 'el': el, 'k': 1.0, 'radius': d, 'centre': T, 'fov': 62}

# name: 어디서 어디를 보나. 고가는 아래 길에서 올려다보도록 손으로 둔 자리
SHOTS = {
    'drive_flat':     lambda s: along(s, 1008, 46),
    'drive_bridge':   lambda s: along(s, 1186, 30, lane=0.8, eye=2.0),
    'drive_tunnel':   lambda s: along(s, 1262, 44, up=32, eye=2.6, lane=2.2, look=2.0),
    'drive_forest':   lambda s: along(s, 1338, 30, margin=40),
    'drive_overpass': lambda s: ((900, 990, -44, 30, 48, 80), {'az': 175, 'el': 6, 'k': 1.0, 'radius': 26, 'centre': (955, 58, 0), 'fov': 58}),
}

def main(only):
    strip = R.Strip(json.loads(R.DATA.read_text()))
    for name, shot in SHOTS.items():
        if only and name not in only:
            continue
        box, view = shot(strip)
        im = R.render(strip, name, box, view, SIZE)
        im.save(OUT / f'{name}.png')
        print(name, im.size)

if __name__ == '__main__':
    main(sys.argv[1:])
