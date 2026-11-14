"""
Core menu backgrounds and button icons. The plugin shows a background behind a chest menu through a
generated font glyph in the title (see MenuSpec.java); this script only draws the PNGs (one look for
every menu: stsv_assets/menu_theme.py, cells read from each menu's own layout) and the button icons
(stsv_assets/ui_icons.py). The menus themselves are content/stsv/menus/*.yml.
"""

import json
from pathlib import Path

import yaml
from PIL import Image

from stsv_assets import menu_theme as T
from stsv_assets import ui_icons as U

MENUS_DIR = Path(__file__).resolve().parents[2] / "content/stsv/menus"
# the core's own buttons (stsv:gui/<name>); contents use them too (pages, close, info)
CORE_ICONS = ("prev", "next", "prev_off", "next_off", "exit", "info", "take_off", "clear", "back", "chest",
              # the hub (/메뉴): worlds, teleport requests, titles, rankings, money
              "house", "pine", "tag", "podium", "send", "coins", "crown", "trophy", "pearl",
              # the hub's 치장 (a mannequin: the hanger is the wardrobe's "take off"), 야간 투시, 치장 뽑기
              "dress_up", "night_vision", "gacha",
              # the cosmetics' category tabs (옷장, 치장 뽑기) and the 옷장's 보기 filter
              "tab_all", "tab_head", "tab_above", "tab_shoulder", "tab_back", "tab_tail", "tab_pet", "tab_trail",
              "tab_kill", "filter", "self_view",
              # the tuning shop (/튜닝): a tab a kind of tuning (and its dimmed twin), pay and undo switched off
              *(f"tune_{k}{off}" for k in ("paint", "wheel", "underglow", "headlamp", "skid", "smoke", "flame", "crackle", "plate") for off in ("", "_off")),
              "coins_off", "clear_off",
              # the donation shop (/후원): the currency, history, claim, confirm, category tabs
              "star", "ledger", "gift", "confirm", "don_car", "don_toy", "flag", "pickaxe",
              # the emote wheel (/감정표현): an icon an emote, the plain one, an empty place, stop
              "emote_any", "emote_empty", "emote_cheer", "emote_bow", "emote_disco", "emote_take_l", "emote_twerk", "cancel")
# a cosmetic the player does not have: a dark card whose rim and question mark the item tints with its
# rarity's colour (custom_model_data colors[0], CosmeticBrowser.locked); one model for every cosmetic
LOCKED = "locked"
LOCKED_DEFAULT_TINT = 0xAAAAAA
# a nickname colour tile (/닉네임 색, NicknameModule.swatch): custom_model_data colors[0] = the colour (a gradient's
# start), colors[1] = the lower-right half (a gradient's end, else the same colour)
SWATCH = "swatch"
SWATCH_FILES = (f"{SWATCH}.png", f"{SWATCH}_half.png", f"{SWATCH}_rim.png")


def menus():
    out = {}
    for f in sorted(MENUS_DIR.glob("*.yml")):
        for key, spec in yaml.safe_load(f.read_text(encoding="utf-8")).items():
            out[key] = spec
    return out


def icons():
    every = U.icons()
    return {n: every[n] for n in CORE_ICONS}


def write(content_pack: Path):
    """Backgrounds of the menus that name one, button items (texture + model + definition) and the
    language files that blank the inventory label. Item textures must live under textures/item/."""
    assets = content_pack / "assets" / "stsv"
    folders = {k: assets / v for k, v in {"bg": "textures/gui", "tex": "textures/item/gui",
                                          "model": "models/item/gui", "def": "items/gui"}.items()}
    for f in folders.values():
        f.mkdir(parents=True, exist_ok=True)
    backgrounds = {}
    for key, spec in menus().items():
        bg = spec.get("background")
        if bg and bg.startswith("stsv:gui/"):
            name = bg.split("/", 1)[1]
            backgrounds[name] = T.background(spec)
    for f in folders["bg"].iterdir():
        if f.is_file() and f.stem not in backgrounds:
            f.unlink()
    for name, im in backgrounds.items():
        im.save(folders["bg"] / f"{name}.png")

    ics = icons()
    keep = {"tex": {f"{n}.png" for n in ics} | {f"{LOCKED}.png", f"{LOCKED}_mark.png"} | set(SWATCH_FILES),
            "model": {f"{n}.json" for n in ics} | {f"{LOCKED}.json", f"{SWATCH}.json"},
            "def": {f"{n}.json" for n in ics} | {f"{LOCKED}.json", f"{SWATCH}.json"}}
    for k, names in keep.items():
        for f in folders[k].iterdir():
            if f.is_file() and f.name not in names:
                f.unlink()
    for name, im in ics.items():
        im.save(folders["tex"] / f"{name}.png")
        model = {"parent": "minecraft:item/generated", "textures": {"layer0": f"stsv:item/gui/{name}"}}
        (folders["model"] / f"{name}.json").write_text(json.dumps(model, indent=2))
        definition = {"model": {"type": "minecraft:model", "model": f"stsv:item/gui/{name}"}}
        (folders["def"] / f"{name}.json").write_text(json.dumps(definition, indent=2))
    card, mark = U.locked_layers()
    card.save(folders["tex"] / f"{LOCKED}.png")
    mark.save(folders["tex"] / f"{LOCKED}_mark.png")
    model = {"parent": "minecraft:item/generated",
             "textures": {"layer0": f"stsv:item/gui/{LOCKED}", "layer1": f"stsv:item/gui/{LOCKED}_mark"}}
    (folders["model"] / f"{LOCKED}.json").write_text(json.dumps(model, indent=2))
    definition = {"model": {"type": "minecraft:model", "model": f"stsv:item/gui/{LOCKED}", "tints": [
        {"type": "minecraft:constant", "value": -1},
        {"type": "minecraft:custom_model_data", "index": 0, "default": LOCKED_DEFAULT_TINT}]}}
    (folders["def"] / f"{LOCKED}.json").write_text(json.dumps(definition, indent=2))
    for im, file in zip(U.swatch_layers(), SWATCH_FILES):
        im.save(folders["tex"] / file)
    model = {"parent": "minecraft:item/generated", "textures": {
        f"layer{k}": f"stsv:item/gui/{file[:-4]}" for k, file in enumerate(SWATCH_FILES)}}
    (folders["model"] / f"{SWATCH}.json").write_text(json.dumps(model, indent=2))
    definition = {"model": {"type": "minecraft:model", "model": f"stsv:item/gui/{SWATCH}", "tints": [
        {"type": "minecraft:custom_model_data", "index": 0, "default": 0xFFFFFF},
        {"type": "minecraft:custom_model_data", "index": 1, "default": 0xFFFFFF},
        {"type": "minecraft:constant", "value": -1}]}}
    (folders["def"] / f"{SWATCH}.json").write_text(json.dumps(definition, indent=2))
    emote_hud(assets)
    T.lang_files(content_pack / "assets")
    return backgrounds, ics


def emote_hud(assets: Path):
    """The pictures of the emote wheel on the screen (the quick key; the plugin's EmoteHud and its font stsv:emote_wheel):
    the dial as the emote menu draws it (the same places), the ring round the picked place, the pointer."""
    folder = assets / "textures" / "font" / "emote_wheel"
    folder.mkdir(parents=True, exist_ok=True)
    size = 112
    # the menu's wheel: six places round the hub, on a canvas of its own (the hub at the picture's middle)
    canvas = Image.new("RGBA", (176, 222), (0, 0, 0, 0))
    cells = [(1, 4), (2, 6), (4, 6), (5, 4), (4, 2), (2, 2), (3, 4)]
    T.wheel(canvas, cells)
    cx, cy = 7 + 18 * 4 + 9, 17 + 18 * 3 + 9
    dial = canvas.crop((cx - size // 2, cy - size // 2, cx + size // 2, cy + size // 2))
    dial.save(folder / "dial.png")
    # the ring round the picked place: gold, a soft edge outside it
    ring = Image.new("RGBA", (26, 26), (0, 0, 0, 0))
    for y in range(26):
        for x in range(26):
            d = ((x + 0.5 - 13) ** 2 + (y + 0.5 - 13) ** 2) ** 0.5
            if 10.2 <= d <= 12.2:
                ring.putpixel((x, y), T.GOLD + (255,))
            elif 12.2 < d <= 13:
                ring.putpixel((x, y), T.GOLD_DIM + (150,))
    ring.save(folder / "ring.png")
    # the pointer: a small bright dot with a dark rim
    dot = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    for y in range(8):
        for x in range(8):
            d = ((x + 0.5 - 4) ** 2 + (y + 0.5 - 4) ** 2) ** 0.5
            if d <= 2.4:
                dot.putpixel((x, y), (255, 253, 246, 255))
            elif d <= 3.6:
                dot.putpixel((x, y), T.EDGE_OUT + (255,))
    dot.save(folder / "pointer.png")


def preview(path: Path, scale=3):
    """The core menus side by side with their button icons, the player's head and sample items in the list cells
    (stsv_assets/menu_preview.py; no title text: there is no Minecraft font here)."""
    from stsv_assets import menu_preview as P
    specs = {k: s for k, s in menus().items() if s.get("background")}
    textures = MENUS_DIR.parent / "textures"
    samples = [Image.open(p).convert("RGBA").resize((16, 16), Image.NEAREST) for p in sorted(textures.glob("*.png"))]
    find = P.lookup({"stsv": U.icons()}, {"stsv": textures})
    ims = [P.render(spec, find, samples) for spec in specs.values()]
    canvas = Image.new("RGBA", (len(ims) * (T.W + 8) + 8, max(i.height for i in ims) + 16), (32, 34, 40, 255))
    for k, im in enumerate(ims):
        canvas.alpha_composite(im, (8 + k * (T.W + 8), 8))
    canvas.resize((canvas.width * scale, canvas.height * scale), Image.NEAREST).save(path)


# ------------------------------------------------------------------------------------------------
# docs/previews/wardrobe.png: the 옷장 on one tab with what the player sees (owned cosmetics as their models,
# the others as the '?' card of their rarity), the open tab glowing, the title, and the tooltips of a tab and
# of a not-owned cosmetic. The cosmetics are drawn with stsv_assets/minirender.py (the game's GUI view).
# ------------------------------------------------------------------------------------------------

RARITY_RGB = {"common": (170, 170, 170), "rare": (90, 176, 255), "epic": (197, 140, 255), "legendary": (255, 211, 107)}
RARITY_KO = {"common": "일반", "rare": "희귀", "epic": "영웅", "legendary": "전설"}
SLOT_KO = {"head": "머리", "above": "머리 위", "shoulder": "어깨", "back": "등", "tail": "꼬리", "pet": "펫",
           "trail": "발자취", "kill": "처치 효과"}
FONT = "/usr/share/fonts/opentype/unifont/unifont.otf"


def _glint(im):
    """The enchantment glint, roughly: a purple sheen over the opaque pixels."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a and (x + y) % 6 < 3:
                px[x, y] = (min(255, r + 70), min(255, g + 30), min(255, b + 110), a)
    return out


def _tooltip(lines, font, scale):
    """A Minecraft-like tooltip: dark violet box, purple frame; lines = [(text, rgb)]."""
    from PIL import ImageDraw
    pad, lh = 4 * scale, 10 * scale
    w = max(int(font.getlength(t)) for t, _ in lines) + 2 * pad
    h = lh * len(lines) + 2 * pad
    im = Image.new("RGBA", (w + 2 * scale, h + 2 * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([scale, 0, w, h + 2 * scale - 1], fill=(16, 0, 16, 240))
    d.rectangle([0, scale, w + 2 * scale - 1, h], fill=(16, 0, 16, 240))
    d.rectangle([scale, scale, w, h], outline=(80, 0, 255, 200), width=scale)
    for k, (t, c) in enumerate(lines):
        d.text((pad + scale, pad + k * lh), t, font=font, fill=c)
    return im


def wardrobe_preview(path: Path, tab="pet", owned=("puppy", "cat", "penguin", "ghost", "haetae", "mole"), scale=3):
    import re
    from PIL import ImageDraw, ImageFont
    from stsv_assets import menu_preview as P
    from stsv_assets import minirender
    import voucher_art
    spec = menus()["wardrobe"]
    every = voucher_art.items()
    cos = {k: v for k, v in every.items() if v.get("cosmetic")}
    plain = lambda s: re.sub(r"<[^>]+>", "", s)       # noqa: E731
    order = list(SLOT_KO)
    rar = list(RARITY_KO)
    shown = sorted((k for k, v in cos.items() if tab == "all" or v["cosmetic"]["slot"] == tab),
                   key=lambda k: (order.index(cos[k]["cosmetic"]["slot"]), rar.index(cos[k]["cosmetic"]["rarity"]), plain(cos[k]["name"])))
    find = P.lookup({"stsv": U.icons()}, {"stsv": MENUS_DIR.parent / "textures"})
    base = P.render(spec, find, [])
    S = scale
    im = base.resize((base.width * S, base.height * S), Image.NEAREST)
    buttons = spec["buttons"]
    cells = {}
    k = 0
    for r, row in enumerate(spec["layout"]):
        for c, ch in enumerate(row):
            x, y = (8 + 18 * c) * S, (18 + 18 * r) * S
            if ch == spec["list"]:
                cells[k] = (x, y)
                k += 1
            elif buttons.get(ch, {}).get("action") == f"wardrobe:tab:{tab}":
                pic = find(buttons[ch])
                if pic is not None:
                    im.alpha_composite(_glint(pic).resize((16 * S, 16 * S), Image.NEAREST), (x, y))
                tab_at = (x, y)
    for i, key in enumerate(shown[:len(cells)]):
        x, y = cells[i]
        c = cos[key]
        if key in owned:
            pic = minirender.render(minirender.load(MENUS_DIR.parent / "models" / f"{c['model']}.bbmodel"), 16 * S) \
                if c.get("model") else None
        else:
            pic = U.locked_preview(RARITY_RGB[c["cosmetic"]["rarity"]]).resize((16 * S, 16 * S), Image.NEAREST)
        if pic is not None:
            im.alpha_composite(pic, (x, y))
    have = sum(1 for k in shown if k in owned)
    font = ImageFont.truetype(FONT, 8 * S)
    d = ImageDraw.Draw(im)
    d.text((8 * S, 5 * S), "치장", font=font, fill=(240, 226, 176))
    w = font.getlength("치장 ")
    d.text((8 * S + w, 5 * S), f"· {SLOT_KO.get(tab, '전체')} ", font=font, fill=(185, 166, 214))
    w += font.getlength(f"· {SLOT_KO.get(tab, '전체')} ")
    d.text((8 * S + w, 5 * S), f"· {have}/{len(shown)}", font=font, fill=(143, 134, 166))

    # tooltips: the open tab, and the first cosmetic not owned yet
    out = Image.new("RGBA", (im.width + 150 * S, im.height + 16 * S), (32, 34, 40, 255))
    out.alpha_composite(im, (8 * S, 8 * S))
    tip = _tooltip([(f"{SLOT_KO[tab]} {have}/{len(shown)}", (255, 255, 255)), ("옆에서 따라다니는 친구", (170, 170, 170))], font, S)
    out.alpha_composite(tip, (8 * S + T.W * S + 6 * S, 8 * S + tab_at[1]))
    missing = [k for k in shown[:len(cells)] if k not in owned]
    if missing:
        c = cos[missing[0]]
        r = c["cosmetic"]["rarity"]
        tip = _tooltip([
            (plain(c["name"]), (85, 85, 85)),
            (f"◆ {RARITY_KO[r]} · {SLOT_KO[c['cosmetic']['slot']]}", RARITY_RGB[r]),
            ("", (0, 0, 0)),
            ("아직 없음", (255, 85, 85)),
            ("얻는 곳: 치장 뽑기 (/뽑기)" if "뽑기" in c["cosmetic"].get("obtain", "") else
             "얻는 곳: 업적 보상 (/업적)" if "업적" in c["cosmetic"].get("obtain", "") else "얻는 곳: 한정", (255, 255, 255)),
            ("클릭: 마네킹에 입혀 보기", (170, 170, 170)),
            ("Shift+클릭: 치장 뽑기 열기", (170, 170, 170)),
        ], font, S)
        i = shown.index(missing[0])
        out.alpha_composite(tip, (8 * S + T.W * S + 6 * S, 8 * S + cells[i][1] + 18 * S))
    out.save(path)
