"""
Menu previews for review (docs/previews): a menu's background with what its buttons really show drawn in their cells, so a
preview can be checked like the game screen (no title text: there is no Minecraft font here).
  - a button with an `icon` (stsv:gui/<name>, economy:gui/<name> or a content item's texture): that picture
  - a PLAYER_HEAD button without an icon (the hub's and the profile's 'my info'): a player's head
  - an `inset` button that shows a chosen item (the price menus' 팔 물건): a sample item
  - the paged list area: sample items
  - a button with only a vanilla `material`: nothing (there are no game textures here)
"""

from pathlib import Path

from PIL import Image

from . import menu_theme as T

COLOURS = {"h": (74, 52, 34), "H": (56, 38, 24), "s": (214, 160, 120), "S": (184, 130, 96), "e": (255, 255, 255),
           "b": (62, 90, 180), "m": (140, 86, 64)}
HEAD = [    # a default player's face, front view, 16 x 16 (8 x 8 skin pixels doubled), with a dark outline
    "................",
    "..hhhhhhhhhhhh..",
    ".hhhhhhhhhhhhhh.",
    ".hHhhhhhhhhhhHh.",
    ".hssssssssssssh.",
    ".hssssssssssssh.",
    ".hsseebbssbbeeS.",
    ".sseebbssbbeeSS.",
    ".sssssmmsssssSS.",
    ".sssssmmsssssSS.",
    ".sssmmmmmmmsssS.",
    ".sssmhhhhhmsssS.",
    ".ssssssssssssSS.",
    "..SSSSSSSSSSSS..",
    "................",
    "................",
]


def head():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(HEAD):
        for x, ch in enumerate(row):
            if ch != ".":
                im.putpixel((x, y), COLOURS[ch] + (255,))
    filled = {(x, y) for y, row in enumerate(HEAD) for x, ch in enumerate(row) if ch != "."}
    for (x, y) in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if q not in filled and 0 <= q[0] < 16 and 0 <= q[1] < 16:
                im.putpixel(q, (16, 18, 22, 255))
    return im


def lookup(gui_icons, textures):
    """gui_icons: namespace -> {name: image}; textures: namespace -> folder of item textures. Returns button -> image or None."""
    def find(b):
        icon = (b or {}).get("icon")
        material = (b or {}).get("material", "")
        if not icon:
            return head() if material == "PLAYER_HEAD" else None
        ns, path = icon.split(":", 1)
        if path.startswith("gui/"):
            return gui_icons.get(ns, {}).get(path[4:])
        folder = textures.get(ns)
        f = folder / f"{path}.png" if folder else None
        return Image.open(f).convert("RGBA").resize((16, 16), Image.NEAREST) if f and f.exists() else None
    return find


def render(spec, find, samples=()):
    im = T.background(spec)
    lst = spec.get("list")
    inset = set(spec.get("inset", "") or "")
    buttons = spec.get("buttons") or {}
    k = 0
    for r, row in enumerate(spec["layout"]):
        for c, ch in enumerate(row):
            x, y = 8 + 18 * c, 18 + 18 * r
            pic = None
            if lst and ch == lst:
                if samples and k < len(samples) * 2:
                    pic = samples[k % len(samples)]
                    k += 1
            elif ch in buttons:
                b = buttons[ch] or {}
                if ch in inset and b.get("material", "AIR") != "AIR" and samples:
                    pic = samples[0]
                else:
                    pic = find(b)
            if pic is not None:
                im.alpha_composite(pic, (x, y))
    return im


def sheet(ims, path: Path, cols=4, scale=1, gap=8, bg=(32, 34, 40, 255)):
    """Menus in rows of cols, each at its own height, scaled up without smoothing."""
    rows = [ims[i:i + cols] for i in range(0, len(ims), cols)]
    width = gap + min(cols, len(ims)) * (T.W + gap)
    height = gap + sum(max(i.height for i in row) + gap for row in rows)
    out = Image.new("RGBA", (width, height), bg)
    y = gap
    for row in rows:
        for k, im in enumerate(row):
            out.alpha_composite(im, (gap + k * (T.W + gap), y))
        y += max(i.height for i in row) + gap
    out.resize((out.width * scale, out.height * scale), Image.NEAREST).save(path)
