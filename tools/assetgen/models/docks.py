"""
하역장 props (economy: /관리자 하역장 설치 builds the dock from real blocks, plugin dev.stsv.economy.dock.DockLayout; these
display models add the detail blocks cannot): the gantry crane's hook block, a forklift, a pallet of goods and the
shipping containers (every one in the yard: one model in six colours, stacked 2.5 blocks apart, barrier blocks inside). Each is shown by an item display (FIXED, scale 2 in the layout) standing on its base:

    drawn at real size (16 px a block) round x = z = 8, its floor at y = 0, front = north (-z);
    BModel(scale=2, shift=(0, -8, 0)) shrinks it by 2 so the model's floor is model y 0 and it fits -16..32;
    the plugin's display scale 2 brings it back to real size (a texel stays 1/16 block like everything else).

Painted in the pack's style (docs/ART_STYLE.md): ribbed steel, worn paint, rubber, oak.
"""

from stsv_assets import presets
from stsv_assets.bake import BModel, Paint, cyl


def _model(name):
    m = BModel(name, floor=0, shift=(0, -8, 0), scale=2)
    m.display = presets.furniture(gui_scale=0.4)
    return m


STEEL = Paint("steel", 2, "metal")
DARK = Paint("iron", 1, "metal")
RUBBER = Paint("rubber", 1, noise=0.03)
YELLOW = Paint("hazard", 1, "metal")


def container(name, ramp):
    """A 20-foot shipping container: 6 long, 2 wide, 2.5 high (so they stack on whole and half blocks); corrugated sides
    and roof, a corner-post frame with cast corner fittings, two doors at the back with lock bars, a pale code panel."""
    m = _model(name)
    x0, x1, z0, z1, top = -8, 24, -39, 55, 40
    rib = Paint(ramp, 2, "planks", axis="y", width=2, rim=0.6)
    plain = Paint(ramp, 2, "metal")
    m.group("box")
    m.box("side", (x0 + 1, 1, z0 + 1), (x1 - 1, top - 1, z1 - 1), rib, faces={"north": plain, "south": plain, "up": plain})
    m.group("frame")
    for (x, z) in ((x0, z0), (x1 - 2, z0), (x0, z1 - 2), (x1 - 2, z1 - 2)):
        m.box("post", (x, 0, z), (x + 2, top, z + 2), DARK)
    for y in (0, top - 2):
        m.box("rail", (x0, y, z0 + 2), (x0 + 1.5, y + 2, z1 - 2), DARK)
        m.box("rail", (x1 - 1.5, y, z0 + 2), (x1, y + 2, z1 - 2), DARK)
        m.box("rail", (x0 + 2, y, z0), (x1 - 2, y + 2, z0 + 1.5), DARK)
        m.box("rail", (x0 + 2, y, z1 - 1.5), (x1 - 2, y + 2, z1), DARK)
    m.group("doors")
    for x in (x0 + 5, x0 + 11, x1 - 12, x1 - 6):
        m.box("lockbar", (x, 3, z1 - 1), (x + 1, top - 3, z1 + 0.5), STEEL)
        m.box("handle", (x - 1, 14, z1 - 0.5), (x + 2, 15, z1 + 1), STEEL)
    m.box("seam", (7.5, 2, z1 - 1), (8.5, top - 2, z1 + 0.25), DARK)
    m.group("castings")   # the corner fittings that lock a stack together: a little proud of the frame, darker
    for (x, z) in ((x0, z0), (x1 - 3, z0), (x0, z1 - 3), (x1 - 3, z1 - 3)):
        for y in (-0.25, top - 3.25):
            m.box("casting", (x - 0.25, y, z - 0.25), (x + 3.25, y + 3.5, z + 3.25), Paint("iron", 0, "metal"))
    m.box("code", (x0 + 3, top - 9, z0 + 0.5), (x0 + 14, top - 4, z0 + 1.25), Paint("paper", 1, noise=0.02))
    return m


def crane_hook():
    """The gantry crane's hook block: a yellow pulley block on its cables, a steel hook with its safety latch."""
    m = _model("dock_crane_hook")
    m.group("block")
    m.box("sheave", (1, 14, 3), (15, 26, 13), YELLOW)
    cyl(m, "pulley", (8, 20), 9, 1.5, 14.5, STEEL, axis="z")
    m.box("cheek", (0, 15, 2), (16, 25, 3), Paint("hazard", 0, "metal"))
    m.box("cheek", (0, 15, 13), (16, 25, 14), Paint("hazard", 0, "metal"))
    m.box("stripe", (0.5, 21, 1.75), (15.5, 23, 2.25), Paint("tint", 1, noise=0))
    m.group("cables")
    for x in (4, 11):
        m.box("cable", (x, 26, 7), (x + 1, 40, 8), DARK)
    m.group("hook")
    m.box("shank", (7, 8, 7), (9, 14, 9), STEEL)
    m.box("swivel", (6, 11, 6), (10, 13, 10), DARK)
    m.box("bowl", (6, 2, 7), (10, 4, 9), STEEL)
    m.box("back", (4, 2, 7), (6, 9, 9), STEEL)
    m.box("tip", (10, 2, 7), (12, 6, 9), STEEL)
    m.box("latch", (6, 6, 7.5), (11, 7, 8.5), Paint("hazard", 1, noise=0))
    return m


def forklift():
    """A small counterbalance forklift: yellow body and counterweight, overhead guard, mast and forks at the front, a
    black seat, four wheels."""
    m = _model("dock_forklift")
    m.group("body")
    m.box("chassis", (-2, 4, -6), (18, 14, 22), YELLOW)
    m.box("counterweight", (-2, 4, 22), (18, 18, 28), Paint("hazard", 0, "metal"))
    m.box("hood", (0, 14, 10), (16, 19, 24), YELLOW)
    m.box("seat", (3, 14, 4), (13, 17, 10), Paint("tint", 1, noise=0.02))
    m.box("seatback", (3, 17, 9), (13, 24, 11), Paint("tint", 1, noise=0.02))
    m.box("wheelpost", (7, 14, -4), (9, 22, -2), DARK)
    cyl(m, "steering", (8, -3), 6, 22, 23, Paint("tint", 2, noise=0))
    m.group("guard")
    for (x, z) in ((-1, -5), (15, -5), (-1, 20), (15, 20)):
        m.box("post", (x, 14, z), (x + 2, 36, z + 2), DARK)
    m.box("roof", (-1, 36, -5), (17, 38, 22), DARK)
    m.group("mast")
    for x in (0, 13):
        m.box("rail", (x, 2, -10), (x + 3, 40, -7), STEEL)
    m.box("crossbar", (3, 36, -9.5), (13, 38, -7.5), STEEL)
    m.box("carriage", (0, 2, -12), (16, 10, -10), DARK)
    for x in (2, 11):
        m.box("fork", (x, 1, -36), (x + 3, 2.5, -12), STEEL)
        m.box("fork", (x, 2.5, -14), (x + 3, 9, -12), STEEL)
    m.group("wheels")
    for (x0, z, d) in ((-3, -1, 9), (-3, 21, 8)):
        cyl(m, "tyre", (d / 2, z), d, x0, x0 + 3, RUBBER, axis="x")
        cyl(m, "tyre", (d / 2, z), d, 16, 19, RUBBER, axis="x")
    m.box("lamp", (1, 18, -6.5), (4, 21, -6), Paint("lamp", 3, noise=0))
    m.box("lamp", (12, 18, -6.5), (15, 21, -6), Paint("lamp", 3, noise=0))
    return m


def pallet():
    """An oak pallet with crates and wrapped sacks waiting to be loaded."""
    m = _model("dock_pallet")
    oak = Paint("wood", 2, "planks", axis="x", width=3)
    m.group("pallet")
    for z in (-6, 6.5, 19):
        m.box("stringer", (-6, 0, z), (22, 3, z + 3), Paint("wood_dark", 1, "grain", axis="x"))
    m.box("deck", (-6, 3, -6), (22, 5, 22), oak)
    m.group("load")
    crate = Paint("wood", 1, "planks", axis="y", width=4)
    m.box("crate", (-5, 5, -5), (7, 17, 7), crate)
    m.box("crate", (9, 5, -5), (21, 17, 7), crate)
    m.box("crate", (-5, 17, -5), (7, 29, 7), Paint("wood", 2, "planks", axis="y", width=4))
    m.box("sack", (-5, 5, 9), (21, 13, 21), Paint("burlap", 2, "weave"))
    m.box("sack", (-3, 13, 10), (19, 19, 20), Paint("burlap", 1, "weave"))
    m.box("strap", (4, 5, 8.5), (6, 19.5, 21.5), Paint("fabric_red", 2, noise=0))
    return m


# the containers' colours (DockLayout.CONTAINER_COLOURS) and their paint ramps
CONTAINER_RAMPS = {"red": "vermilion", "blue": "police_blue", "green": "sign_green", "yellow": "hazard", "white": "car_white",
                   "teal": "celadon"}

ALL = {
    **{f"dock_container_{c}": (lambda c=c, r=r: container(f"dock_container_{c}", r)) for c, r in CONTAINER_RAMPS.items()},
    "dock_crane_hook": crane_hook,
    "dock_forklift": forklift,
    "dock_pallet": pallet,
}
