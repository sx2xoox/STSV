"""
Fishing (economy server): the 98 fish, their 16x16 icons, the ring minigame font and the content files
made from this one table.

    FISH: id -> (name, habitat, rarity 0..3, min cm, max cm, base price, shape, main colour, second colour, extra)

shape: f fish, l long (eels, catfish, needle fish), p flat or round (flatfish, rays, sunfish, turtles),
c crab / shrimp, q octopus / squid / jellyfish. extra: a few marks (stripes, spots, bill, whisker, lure,
glow, shark, when=night|dawn|dusk|rain|thunder|fullmoon). habitat "fresh" / "salt" = any water of that kind.

Writes: icons (ICONS, into economy_icons), economy/content/economy/fish.yml, items/fish.yml, the fish part
of shop.yml (between markers), the codex titles, the ring font (economy:fishring) and the fish icon
font (economy:fishicon) used in the catch title.
"""

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

from stsv_assets import palette as P
from stsv_assets.color import rgb_to_oklch
from stsv_assets.pixelart import Canvas

E = "economy:"
RARITY = [  # name, colour, successes, needle speed (cells per tick), zone cells
    ("common", "흔함", "#ffffff", 2, 1.0, 8),
    ("uncommon", "보통", "#55ff55", 3, 1.35, 7),
    ("rare", "희귀", "#5aa8ff", 4, 1.75, 9),
    ("legend", "전설", "#ffaa00", 6, 2.2, 13),
]
HABITATS = {  # id: (name, codex title id, title text)
    "sea": ("바다", "angler_sea", "[바다 낚시꾼]"),
    "deep": ("깊은 바다", "angler_deep", "[심해 사냥꾼]"),
    "warm": ("따뜻한 바다", "angler_warm", "[산호초 탐험가]"),
    "cold": ("차가운 바다", "angler_cold", "[북해의 어부]"),
    "river": ("강", "angler_river", "[강태공의 제자]"),
    "coldriver": ("차가운 강·호수", "angler_coldriver", "[얼음낚시 달인]"),
    "swamp": ("늪", "angler_swamp", "[늪지 사냥꾼]"),
    "mangrove": ("맹그로브", "angler_mangrove", "[갯벌 탐험가]"),
    "jungle": ("정글", "angler_jungle", "[밀림의 낚시꾼]"),
    "cave": ("동굴 지하수", "angler_cave", "[심연의 낚시꾼]"),
    "mushroom": ("버섯 섬", "angler_mushroom", "[포자의 낚시꾼]"),
    "special": ("시간·날씨", "angler_special", "[때를 아는 자]"),
}

FISH = {
    # 바다
    "mackerel": ("고등어", "sea", 0, 25, 45, 3, "f", "#3f6f8a", "#c9d6dc", "stripes"),
    "horse_mackerel": ("전갱이", "sea", 0, 15, 35, 3, "f", "#7f97a3", "#dfe6e8", ""),
    "sardine": ("정어리", "sea", 0, 12, 25, 3, "f", "#4c6f95", "#e0e8ee", "spots"),
    "saury": ("꽁치", "sea", 0, 25, 40, 3, "l", "#3a5f86", "#d8e2ea", "bill"),
    "rockfish": ("우럭", "sea", 1, 20, 45, 9, "f", "#4a4a44", "#8a8678", "spots"),
    "flatfish": ("광어", "sea", 1, 30, 80, 12, "p", "#7a6a50", "#e8e0d0", "spots"),
    "greenling": ("노래미", "sea", 1, 20, 40, 7, "f", "#7a5a3a", "#c8a878", ""),
    "filefish": ("쥐치", "sea", 1, 15, 30, 8, "f", "#a8a070", "#e6e0b8", ""),
    "red_seabream": ("참돔", "sea", 2, 30, 90, 40, "f", "#e0707a", "#f6d0d0", "spots"),
    "yellowtail": ("방어", "sea", 2, 50, 100, 40, "f", "#4a6aa0", "#e8eef4", "stripe1"),
    "hairtail": ("갈치", "sea", 2, 60, 150, 35, "l", "#d6dde4", "#ffffff", ""),
    "bluefin_tuna": ("참다랑어", "sea", 3, 120, 300, 250, "f", "#243a66", "#c8d2dc", ""),
    # 깊은 바다
    "cod": ("대구", "deep", 0, 40, 90, 3, "f", "#8a7a5a", "#e6dcc6", "whisker"),
    "pollack": ("명태", "deep", 0, 30, 60, 3, "f", "#7a7c6a", "#dcdcd0", ""),
    "monkfish": ("아귀", "deep", 1, 40, 100, 14, "p", "#5a4636", "#8a7258", "lure"),
    "black_rockfish": ("볼락", "deep", 1, 15, 30, 8, "f", "#8a5a4a", "#d0a090", ""),
    "skate": ("홍어", "deep", 1, 50, 120, 15, "p", "#8a6a5a", "#e8d8d0", "ray"),
    "croaker": ("민어", "deep", 2, 50, 110, 60, "f", "#9a8a6a", "#ece4d0", ""),
    "octopus": ("문어", "deep", 2, 40, 150, 55, "q", "#b0563a", "#e8a080", ""),
    "giant_squid": ("대왕오징어", "deep", 2, 200, 600, 80, "q", "#c86a5a", "#f0c0b0", "squid"),
    "marlin": ("청새치", "deep", 3, 180, 400, 520, "f", "#2a3a7a", "#d8e0ee", "bill"),
    "lantern_angler": ("초롱아귀", "deep", 3, 20, 60, 480, "p", "#2a2a3a", "#7ae0ff", "lure glow"),
    # 따뜻한 바다 (복어는 손질대 재료라 더함: 98종)
    "damselfish": ("자리돔", "warm", 0, 8, 18, 3, "f", "#4a5a7a", "#a0b0c8", ""),
    "clownfish": ("흰동가리", "warm", 0, 6, 12, 4, "f", "#ff7a20", "#ffffff", "bands"),
    "pufferfish": ("복어", "warm", 0, 10, 30, 4, "p", "#d8b84a", "#f4ecd0", "spots"),
    "butterflyfish": ("나비고기", "warm", 1, 10, 20, 8, "p", "#ffd84a", "#ffffff", "bands"),
    "tang": ("쥐돔", "warm", 1, 15, 35, 9, "p", "#2a5ad8", "#ffd84a", ""),
    "lionfish": ("쏠배감펭", "warm", 1, 20, 38, 13, "f", "#c84a3a", "#f6e6d6", "bands"),
    "mahi_mahi": ("만새기", "warm", 2, 60, 150, 50, "f", "#2aa86a", "#f0d84a", "spots"),
    "sunfish": ("개복치", "warm", 2, 100, 300, 75, "p", "#9aa4ae", "#dfe4e8", ""),
    "yellowfin_tuna": ("황다랑어", "warm", 2, 80, 200, 65, "f", "#2a4a7a", "#f0d040", "stripe1"),
    "sailfish": ("돛새치", "warm", 3, 150, 340, 500, "f", "#1a3a8a", "#8ac0ff", "bill sail"),
    "whale_shark": ("고래상어", "warm", 3, 500, 1200, 600, "f", "#3a4a5a", "#e8ecf0", "shark spots"),
    # 차가운 바다 (연어는 바닐라 연어: 손질대 재료라 흔함)
    "herring": ("청어", "cold", 0, 20, 40, 3, "f", "#4a6a8a", "#e0e8f0", ""),
    "sandfish": ("도루묵", "cold", 0, 15, 26, 3, "f", "#8a8270", "#e8e2d0", ""),
    "salmon": ("연어", "cold", 0, 50, 100, 3, "f", "#7a8a9a", "#f09a7a", ""),
    "atka_mackerel": ("임연수어", "cold", 1, 30, 50, 8, "f", "#6a6a4a", "#d8d0a8", "bands"),
    "sand_lance": ("양미리", "cold", 1, 10, 20, 6, "l", "#8aa0a8", "#e8f0f2", ""),
    "snow_crab": ("대게", "cold", 2, 20, 40, 60, "c", "#c86a3a", "#f0d0a0", ""),
    "king_crab": ("킹크랩", "cold", 2, 40, 90, 80, "c", "#b03a2a", "#f0a080", "spots"),
    "greenland_shark": ("그린란드상어", "cold", 3, 250, 600, 550, "f", "#4a5058", "#9aa0a8", "shark"),
    # 강
    "pale_chub": ("피라미", "river", 0, 8, 15, 2, "f", "#8a9aa8", "#e8eef2", "stripe1"),
    "crucian_carp": ("붕어", "river", 0, 10, 35, 3, "f", "#8a7a4a", "#d8c890", ""),
    "carp": ("잉어", "river", 1, 30, 90, 10, "f", "#9a7a3a", "#e0c070", "whisker"),
    "catfish": ("메기", "river", 1, 30, 100, 11, "l", "#4a4a40", "#a8a490", "whisker"),
    "bitterling": ("납자루", "river", 1, 5, 10, 6, "f", "#7a8aa0", "#f0a0a0", ""),
    "mandarin_fish": ("쏘가리", "river", 2, 20, 50, 45, "f", "#a89a5a", "#e8dca0", "spots"),
    "kkeokji": ("꺽지", "river", 2, 10, 20, 30, "f", "#5a6a4a", "#c0c890", "bands"),
    "bagrid_catfish": ("동자개", "river", 2, 15, 30, 28, "l", "#a08a3a", "#e8d890", "whisker"),
    "sturgeon": ("철갑상어", "river", 3, 100, 300, 300, "l", "#5a6470", "#c8ccd0", "plates"),
    "dragon_carp": ("용잉어", "river", 3, 90, 140, 450, "f", "#e8b020", "#fff0a0", "whisker glow"),
    # 차가운 강·호수
    "smelt": ("빙어", "coldriver", 0, 7, 15, 2, "f", "#b0c8d8", "#f4fafc", ""),
    "lenok": ("열목어", "coldriver", 1, 30, 70, 12, "f", "#7a6a5a", "#e0b0a0", "spots"),
    "dace": ("황어", "coldriver", 1, 25, 45, 8, "f", "#8a8a5a", "#f0a040", "stripe1"),
    "masu_salmon": ("산천어", "coldriver", 2, 20, 40, 38, "f", "#6a7a6a", "#e8c0c8", "spots"),
    "trout": ("송어", "coldriver", 2, 30, 70, 40, "f", "#6a8a6a", "#f0b8a0", "spots"),
    "char": ("곤들매기", "coldriver", 2, 30, 70, 48, "f", "#5a6a7a", "#f08a60", "spots"),
    "taimen": ("타이멘", "coldriver", 3, 100, 200, 520, "f", "#6a3a2a", "#d88a6a", "spots"),
    # 늪
    "loach": ("미꾸라지", "swamp", 0, 8, 18, 2, "l", "#7a6a4a", "#c8b890", "whisker"),
    "paradise_fish": ("버들붕어", "swamp", 0, 6, 12, 3, "f", "#6a7a5a", "#c8d0a0", "bands"),
    "snakehead": ("가물치", "swamp", 1, 40, 90, 13, "l", "#3a3a2a", "#8a8a6a", "spots"),
    "swamp_eel": ("드렁허리", "swamp", 1, 30, 60, 9, "l", "#7a5a2a", "#d0a860", ""),
    "softshell_turtle": ("자라", "swamp", 2, 15, 40, 55, "p", "#5a6a3a", "#d8d0a0", "turtle"),
    "eel": ("뱀장어", "swamp", 2, 40, 100, 50, "l", "#3a4a3a", "#c0c8a0", ""),
    "giant_snakehead": ("거대 가물치", "swamp", 3, 100, 180, 480, "l", "#2a2a1a", "#6a6a4a", "spots"),
    # 맹그로브
    "goby": ("망둥어", "mangrove", 0, 8, 20, 3, "f", "#8a8060", "#d8d0b0", "spots"),
    "mudskipper": ("짱뚱어", "mangrove", 1, 10, 20, 9, "f", "#6a6a5a", "#8ad0ff", "spots"),
    "mullet": ("숭어", "mangrove", 1, 30, 70, 8, "f", "#7a8a9a", "#e8eef2", ""),
    "mud_crab": ("머드크랩", "mangrove", 2, 15, 25, 48, "c", "#4a5a3a", "#b0a070", ""),
    "barramundi": ("바라문디", "mangrove", 2, 60, 150, 55, "f", "#8a9aa0", "#e8eef0", ""),
    "king_mudskipper": ("왕짱뚱어", "mangrove", 3, 30, 50, 420, "f", "#5a5a4a", "#5ad0ff", "spots glow"),
    # 정글
    "guppy": ("구피", "jungle", 0, 3, 6, 3, "f", "#ff8a4a", "#6ac0ff", ""),
    "neon_tetra": ("네온테트라", "jungle", 0, 3, 4, 4, "f", "#2a8aff", "#ff3a3a", "stripe1"),
    "tilapia": ("틸라피아", "jungle", 1, 20, 45, 7, "f", "#8a8a7a", "#d8d8c8", "bands"),
    "angelfish": ("엔젤피시", "jungle", 1, 10, 20, 12, "p", "#e8e0c8", "#3a3a3a", "bands"),
    "piranha": ("피라냐", "jungle", 2, 20, 35, 45, "f", "#7a8a9a", "#e84a3a", ""),
    "discus": ("디스커스", "jungle", 2, 15, 25, 60, "p", "#e86a3a", "#4aa0e0", "bands"),
    "arowana": ("아로와나", "jungle", 3, 60, 120, 560, "l", "#d8b040", "#f8e8a0", "whisker"),
    "arapaima": ("피라루쿠", "jungle", 3, 200, 450, 600, "l", "#5a5a4a", "#c84a3a", "spots"),
    # 동굴 지하수
    "cavefish": ("장님물고기", "cave", 0, 6, 12, 3, "f", "#e8d0d0", "#ffffff", "blind"),
    "cave_shrimp": ("동굴 새우", "cave", 0, 4, 8, 3, "c", "#e8c8c0", "#ffffff", "shrimp"),
    "cave_crayfish": ("동굴 가재", "cave", 1, 8, 15, 10, "c", "#c8d8e0", "#ffffff", "shrimp"),
    "moss_minnow": ("이끼 피라미", "cave", 1, 8, 15, 8, "f", "#5a8a3a", "#b8e070", "spots"),
    "crystal_trout": ("수정 송어", "cave", 2, 25, 50, 70, "f", "#a0e0ff", "#f0ffff", "glow"),
    "glow_jellyfish": ("발광 해파리", "cave", 2, 10, 30, 65, "q", "#7ae8ff", "#e0ffff", "jelly glow"),
    "abyssfish": ("심연어", "cave", 3, 80, 160, 580, "l", "#1a1a2a", "#a050ff", "glow"),
    "petal_carp": ("꽃잎 잉어", "cave", 3, 40, 80, 500, "f", "#f0a0c8", "#ffffff", "spots"),
    # 버섯 섬
    "spore_goby": ("포자 망둥어", "mushroom", 1, 10, 20, 15, "f", "#9a6a8a", "#d8b8c8", "spots"),
    "mushroom_puffer": ("버섯 복어", "mushroom", 2, 15, 30, 70, "p", "#c83a3a", "#f0e0d0", "spots"),
    "mycelium_ray": ("균사 가오리", "mushroom", 3, 80, 160, 520, "p", "#8a7a8a", "#d0c0d0", "ray"),
    # 시간·날씨 (fresh / salt = 어느 민물 / 바다에서나)
    "sweetfish": ("은어", "fresh", 1, 15, 30, 10, "f", "#a8b8a0", "#eef4e8", "when=night"),
    "pike_conger": ("갯장어", "salt", 1, 60, 150, 12, "l", "#6a6a5a", "#d8d0c0", "when=night"),
    "golden_crucian": ("황금 붕어", "fresh", 2, 20, 40, 60, "f", "#f0c030", "#fff4b0", "when=dawn glow"),
    "sunset_bream": ("노을 도미", "salt", 2, 30, 70, 55, "f", "#f07a4a", "#ffd0a0", "when=dusk"),
    "rainbow_trout": ("무지개송어", "fresh", 2, 30, 70, 50, "f", "#6a8a7a", "#f08ab0", "when=rain stripe1"),
    "moonlight_ray": ("달빛 가오리", "salt", 3, 80, 200, 480, "p", "#5a6ab0", "#e0e8ff", "when=night ray glow"),
    "thunder_catfish": ("천둥 메기", "fresh", 3, 100, 250, 520, "l", "#4a4a60", "#f0e04a", "when=thunder whisker"),
    "moon_goldfish": ("달 금붕어", "fresh", 3, 15, 40, 500, "f", "#f8e0a0", "#ffffff", "when=fullmoon glow"),
}
VANILLA = {"cod": "minecraft:cod", "salmon": "minecraft:salmon", "pufferfish": "minecraft:pufferfish"}
FINDS = {"red_seabream", "bluefin_tuna", "sturgeon"}   # items of the jobs step (finds, 손질대 inputs): their icons stay
SHAPES = "flpcq"


def q_for(base):
    """A sell line's q from its base price: the one ladder shop.yml uses for every line (BalanceTest R10)."""
    for limit, q in ((3, 128), (6, 64), (15, 32), (50, 16), (150, 8), (500, 4), (1000, 2)):
        if base < limit:
            return q
    return 1


def extra(fid):
    return set(FISH[fid][9].split())


def when(fid):
    for e in extra(fid):
        if e.startswith("when="):
            return e[5:]
    return None


def group(fid):
    """Codex group: the habitat, or "special" for the time and weather fish."""
    return "special" if when(fid) else FISH[fid][1]


# ------------------------------------------------------------------------------------------------
# Icons
# ------------------------------------------------------------------------------------------------

def mat(hexcol, lift=0.0):
    """A palette material made from a colour (its lightness in the middle of the ramp)."""
    name = f"hx_{hexcol[1:]}_{int(lift * 100)}"
    if name not in P.MATERIALS:
        rgb = tuple(int(hexcol[i:i + 2], 16) for i in (1, 3, 5))
        L, C, h = rgb_to_oklch(rgb)
        P.MATERIALS[name] = (h, C / 1.5, min(0.9, max(0.3, L + lift)), 0.34, 5, False)
    return name


def _fish(c, fid, a, b, x):
    """Standard fish lying corner to corner like vanilla cod: tail top right, head bottom left."""
    stout = 3.6 if "sail" not in x else 3.0
    c.poly([(9.5, 6.5), (14.6, 1.0), (15, 5.2), (12.6, 7.2), (14.8, 10.2), (11, 8.8)], a, 0, normal=(0.35, 0.1, 1), lo=1, hi=3)
    if "sail" in x:
        c.poly([(4, 10), (6, 2.5), (11, 3), (8, 7)], a, 1, normal=(-0.2, -0.7, 0.7), lo=1, hi=3)
    else:
        c.poly([(4.5, 8.5), (7.5, 4), (9.5, 4.5), (6, 9)], a, 1, normal=(-0.3, -0.7, 0.6), lo=1, hi=3)
    c.tube([(3.4, 12.4), (6.6, 9.2), (10.4, 5.4)], [2.2, stout, 2.1], b, 2, flat=0.9, lo=1, hi=4)
    c.tube([(2.9, 11.7), (6.0, 8.5), (9.8, 4.8)], [1.4, 2.4, 1.3], a, 3, flat=0.9, lo=1, hi=4)
    c.poly([(6, 12.6), (8.5, 14.5), (9.4, 12.4)], a, 1, normal=(0.4, 0.6, 0.7), lo=1, hi=2)
    if "bill" in x:
        c.line(0, 15, 3, 12, a, 1, z=4)
    if "stripes" in x:
        c.dots([(5, 8), (6, 7), (7, 6), (8, 5), (6, 9), (8, 7)], a, 1, z=4)
    if "stripe1" in x:
        c.line(4, 11, 10, 5, b, 4, z=4)
    if "bands" in x:
        for (x0, y0) in ((4, 9), (7, 6)):
            c.line(x0, y0, x0 + 3, y0 + 3, "snow", 2, z=4)
    if "spots" in x:
        c.dots([(5, 8), (8, 6), (7, 9), (9, 7)], a, 1, z=4)
    if "shark" in x:
        c.poly([(6, 7), (8, 2), (9, 6)], a, 4, normal=(0, -0.6, 0.8), lo=1, hi=2)
    if "whisker" in x:
        c.dots([(1, 13), (2, 14)], a, 1, z=5)
    if "blind" not in x:
        c.put(3, 11, "roe", 0).put(4, 10, "snow", 2)


def _long(c, fid, a, b, x):
    c.poly([(12, 4), (15, 0.6), (14.6, 3.6), (13.4, 5.2)], a, 0, normal=(0.4, -0.2, 1), lo=1, hi=3)
    c.tube([(1.8, 14.0), (4.2, 11.6), (8, 7.8), (12.4, 3.6)], [0.9, 1.9, 2.1, 1.0], b, 1, flat=0.95, lo=1, hi=4)
    c.tube([(1.4, 13.4), (3.8, 11.0), (7.6, 7.2), (12.0, 3.0)], [0.6, 1.2, 1.4, 0.6], a, 2, flat=0.95, lo=1, hi=4)
    if "plates" in x:
        c.dots([(3, 10), (5, 8), (7, 6), (9, 4), (11, 2)], "pearl", 3)
    if "spots" in x:
        c.dots([(5, 9), (8, 6), (10, 4)], a, 1, z=4)
    if "whisker" in x:
        c.dots([(0, 13), (1, 15)], a, 1, z=5)
    if "bill" in x:
        c.line(0, 15, 1, 14, a, 1, z=5)
    c.put(3, 12, "roe", 0)


def _flat(c, fid, a, b, x):
    if "ray" in x:
        c.poly([(1, 7), (8, 1), (15, 7), (8, 12)], a, 1, normal=(-0.3, -0.5, 0.9), lo=1, hi=4)
        c.line(8, 12, 12, 15, a, 1, z=2)
        c.dots([(6, 6), (10, 6)], b, 3, z=3)
        return
    if "turtle" in x:
        c.ellipse(8, 8.5, 5.6, 5.0, a, 1, lo=1, hi=4, gloss=0.7)
        c.ellipse(8, 2.6, 1.6, 1.6, b, 0, lo=1, hi=3)
        for (px, py) in ((2.5, 5), (13.5, 5), (3, 12.5), (13, 12.5)):
            c.ellipse(px, py, 1.4, 1.2, b, 0, lo=1, hi=3)
        c.dots([(6, 7), (10, 7), (8, 10)], b, 2, z=3)
        return
    if "lure" in x:   # anglers: big round head, a light on a stalk
        c.ellipse(7.5, 9.5, 5.6, 4.6, a, 1, lo=1, hi=4, gloss=0.6)
        c.poly([(12, 9), (15.4, 6.5), (15.4, 12.5)], a, 0, normal=(0.4, 0, 1), lo=1, hi=3)
        c.line(5, 5, 6, 2, a, 1, z=2)
        c.put(7, 1, b, 4).put(6, 1, b, 3).put(7, 2, b, 3)
        c.line(3, 11, 8, 11, "snow", 3, z=3)
        c.put(5, 8, "roe", 0)
        return
    # round fish seen from the side: tall body, small tail
    c.poly([(11.5, 8), (15.4, 4.5), (15.4, 11.5)], a, 0, normal=(0.4, 0, 1), lo=1, hi=3)
    c.ellipse(7.5, 8, 5.4, 5.8 if "bands" in x or fid in ("sunfish", "discus", "angelfish", "tang") else 4.4, b, 1, lo=1, hi=4, gloss=0.75)
    c.ellipse(7, 6.6, 4.2, 3.0, a, 2, lo=1, hi=4, gloss=0.75)
    if "bands" in x:
        c.line(6, 3, 6, 13, a, 1, z=3)
        c.line(9, 3, 9, 13, a, 1, z=3)
    if "spots" in x:
        c.dots([(5, 9), (8, 10), (10, 7), (7, 5)], a, 1, z=3)
    c.put(3, 7, "roe", 0).put(4, 6, "snow", 2)


def _crab(c, fid, a, b, x):
    if "shrimp" in x:
        c.tube([(3, 4), (7, 7.5), (9.5, 11), (12.5, 13)], [1.4, 2.4, 2.0, 1.0], a, 1, flat=0.8, lo=1, hi=4)
        for k in range(3):
            c.line(6 + k * 2, 9 + k, 4 + k * 2, 13 + k // 2, a, 1, z=0)
        c.line(3, 4, 0, 1, a, 1, z=2)
        c.line(4, 3, 3, 0, a, 1, z=2)
        if fid == "cave_crayfish":
            c.ellipse(2.5, 7, 1.6, 1.2, a, 2, lo=1, hi=3)
            c.ellipse(6.5, 2.5, 1.6, 1.2, a, 2, lo=1, hi=3)
        c.put(4, 5, "roe", 0)
        return
    for k in range(3):
        c.line(3 - k, 9 + k * 2, 0, 9 + k * 2 + 2, a, 1, z=0)
        c.line(12 + k, 9 + k * 2, 15, 9 + k * 2 + 2, a, 1, z=0)
    c.ellipse(8, 9.5, 5.4, 3.8, a, 1, lo=1, hi=4, gloss=0.7)
    c.ellipse(8, 10.6, 3.6, 2.0, b, 2, lo=1, hi=3)
    for (cx, cy) in ((3, 4.5), (13, 4.5)):
        c.ellipse(cx, cy, 2.2, 1.8, a, 2, lo=1, hi=4, gloss=0.7)
    if "spots" in x:
        c.dots([(6, 8), (10, 8), (8, 7)], b, 3, z=3)
    c.put(6, 7, "roe", 0).put(10, 7, "roe", 0)


def _squid(c, fid, a, b, x):
    if "jelly" in x:
        c.ellipse(8, 5.5, 5.4, 4.2, a, 2, lo=1, hi=4, gloss=0.6, dome=1.2)
        for k in range(5):
            xx = 4 + k * 2
            c.line(xx, 9, xx + (1 if k % 2 else -1), 15, b, 2, z=1)
        return
    if "squid" in x:
        c.poly([(5, 1), (11, 1), (8, -1.5)], a, 2, normal=(0, -0.6, 0.8), lo=1, hi=3)
        c.tube([(8, 1.5), (8, 6.5)], [3.0, 3.2], a, 2, flat=0.8, lo=1, hi=4)
        for k in range(5):
            xx = 4.5 + k * 1.75
            c.line(int(xx), 9, int(xx + (k - 2) * 0.6), 15, a, 1, z=1)
        c.put(6, 6, "roe", 0).put(10, 6, "roe", 0)
        return
    c.ellipse(8, 5.2, 5.0, 4.4, a, 2, lo=1, hi=4, gloss=0.7)
    for k in range(6):
        xx = 3 + k * 2
        c.line(xx, 9, xx + (k - 2.5) * 0.8, 14.5, a, 1, z=1)
    c.dots([(5, 4), (11, 4), (8, 2)], b, 3, z=3)
    c.put(6, 7, "roe", 0).put(10, 7, "roe", 0)


DRAW = {"f": _fish, "l": _long, "p": _flat, "c": _crab, "q": _squid}


def icon(fid):
    name, hab, rar, lo, hi, price, shape, c1, c2, x = FISH[fid]
    ex = set(x.split())
    c = Canvas()
    a, b = mat(c1), mat(c2)
    # one dark outline in the fish's own hue for every part: a pale belly's own darkest step is about
    # slot grey, so the lower edge of the fish would vanish in the inventory
    ink = (mat(c1, -0.45), 0)
    for m in (a, b, "snow", "pearl"):
        c.ink[m] = ink
    DRAW[shape](c, fid, a, b, ex)
    im = c.render()
    if "glow" in ex:   # a faint pale rim of light around glowing fish
        px = im.load()
        out = Image.new("RGBA", im.size, (0, 0, 0, 0))
        for y in range(16):
            for xx in range(16):
                if px[xx, y][3] == 0 and any(0 <= xx + dx < 16 and 0 <= y + dy < 16 and px[xx + dx, y + dy][3] > 0
                                              for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    r, g, b2 = (int(c2[i:i + 2], 16) for i in (1, 3, 5))
                    out.putpixel((xx, y), (r, g, b2, 110))
        out.alpha_composite(im)
        im = out
    return im


def picture(fid):
    """The fish's icon: hand-drawn in fish_art.py for species the templates cannot give, else the template."""
    import fish_art
    art = fish_art.ICONS.get(fid)
    return art() if art else icon(fid)


ICONS = {fid: (lambda f=fid: picture(f)) for fid in FISH if fid not in VANILLA and fid not in FINDS}


def all_icons():
    """Every fish's picture: the finds keep their goods icons, the vanilla three are drawn here too (for the title font)."""
    import economy_goods as GO
    return {fid: (GO.ICONS[fid]() if fid in FINDS else picture(fid)) for fid in FISH}


# ------------------------------------------------------------------------------------------------
# The ring (economy:fishring): 56 px glyphs shown at a quarter (height 14) in the title, which the game
# draws 4x: one glyph pixel = one GUI pixel. Base rings, fish silhouettes, 48 zone cells in green, gold
# and red, 48 needle directions, and two spaces to stack them.
# ------------------------------------------------------------------------------------------------

RING, RC, SEG = 56, 28, 48
# shown at half size (height 28): one glyph pixel = two GUI pixels in the title. The ascent keeps the
# ring's bottom where the first, smaller ring had it (above the subtitle): it grew upwards
RING_HEIGHT, RING_ASCENT = 28, 23
BASE_CH = 0xE000            # base rings: + rim (0 normal, 1 white flash, 2 gold, 3 red)
CENTER_CH = 0xE010          # silhouettes: + shape * 4 + rarity
GREEN_CH, GOLD_CH, RED_CH, NEEDLE_CH = 0xE100, 0xE140, 0xE180, 0xE1C0
BACK_CH, FORWARD_CH = 0xF001, 0xF002
SILHOUETTE = {   # the preview's 16x16 shapes
    "f": ['................', '................', '................', '......ooooo.....', '....ooaaaaaoo...', '...oaaaaaaaaao.o', '..oaeaaaaaaaaaoo',
          '..oaaaaaaaaaaaao', '..obbbbbbbaaaaoo', '...obbbbbbbbao.o', '....oobbbbboo...', '......ooooo.....', '................', '................',
          '................', '................'],
    "l": ['................', '................', '................', '................', '................', '..ooo...........', '.oaeaoooooooo...',
          '.oaaaaaaaaaaaooo', '..obbbbbbbbbbaao', '...oooooooooooo.', '................', '................', '................', '................',
          '................', '................'],
    "p": ['................', '................', '......oooo......', '....ooaaaaoo....', '...oaaaaaaaao...', '..oaaeaaeaaaao..', '.oaaaaaaaaaaaao.',
          '..oaaaaaaaaaao..', '...oaaaaaaaao...', '....ooaaaaoo....', '......oaao......', '.......oo.......', '.......oo.......', '........o.......',
          '................', '................'],
    "c": ['................', '................', '..oo........oo..', '.oaao......oaao.', '.oa.o......o.ao.', '..oao.e..e.oao..', '...oaoooooooao..',
          '...oaaaaaaaaao..', '..oaaaaaaaaaaao.', '..obbbbbbbbbbbo.', '...oooooooooooo.', '...o.o.o..o.o.o.', '................', '................',
          '................', '................'],
    "q": ['................', '.....oooooo.....', '....oaaaaaao....', '...oaaaaaaaao...', '...oaeaaaaeao...', '...oaaaaaaaao...', '....oaaaaaao....',
          '....oaoaoaoao...', '...oa.oa.oa.ao..', '...o..o..o...o..', '................', '................', '................', '................',
          '................', '................'],
}


def _hex(h, a=255):
    return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), a)


def _ring_pixels():
    for y in range(RING):
        for x in range(RING):
            dx, dy = x + 0.5 - RC, y + 0.5 - RC
            r = math.hypot(dx, dy)
            a = math.atan2(dx, -dy) % (2 * math.pi)
            yield x, y, r, int(a / (2 * math.pi) * SEG) % SEG


def _sheet(cells, cols=8):
    rows = (len(cells) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * RING, rows * RING), (0, 0, 0, 0))
    for k, im in enumerate(cells):
        sheet.alpha_composite(im, ((k % cols) * RING, (k // cols) * RING))
    return sheet, rows


def ring_font(assets: Path):
    font_dir, tex = assets / "economy/font", assets / "economy/textures/font"
    font_dir.mkdir(parents=True, exist_ok=True)
    tex.mkdir(parents=True, exist_ok=True)
    pix = list(_ring_pixels())
    base = []
    for rim in ("#141414", "#ffffff", "#ffcc33", "#ff4040"):
        im = Image.new("RGBA", (RING, RING), (0, 0, 0, 0))
        for x, y, r, seg in pix:
            if 23 <= r < 25 or 15.5 <= r < 17:
                im.putpixel((x, y), _hex(rim))
            elif 17 <= r < 23:
                im.putpixel((x, y), _hex("#5a5a5a" if seg % 6 == 0 and r >= 21 else "#3c3c3c", 230))
            elif r < 15.5:
                im.putpixel((x, y), (0, 0, 0, 110))
        base.append(im)
    centers = []
    for shape in SHAPES:
        for (_, _, colour, _, _, _) in RARITY:
            im = Image.new("RGBA", (RING, RING), (0, 0, 0, 0))
            dark = tuple(int(v * 0.55) for v in _hex(colour)[:3]) + (255,)
            for y, row in enumerate(SILHOUETTE[shape]):
                for x, ch in enumerate(row):
                    if ch != ".":
                        im.putpixel((RC - 8 + x, RC - 8 + y), dark if ch in "oe" else _hex(colour))
            centers.append(im)
    cells = {}
    for key, colour in (("green", "#3fbf3f"), ("gold", "#ffc933"), ("red", "#c42a2a")):
        cells[key] = []
        for k in range(SEG):
            im = Image.new("RGBA", (RING, RING), (0, 0, 0, 0))
            for x, y, r, seg in pix:
                if seg == k and 17 <= r < 23:
                    im.putpixel((x, y), _hex(colour))
            cells[key].append(im)
    needles = []
    for k in range(SEG):
        im = Image.new("RGBA", (RING, RING), (0, 0, 0, 0))
        a = (k + 0.5) / SEG * 2 * math.pi
        for w, length, colour in ((3, 24, "#141414"), (1, 23.5, "#ffffff")):
            r = 7.0
            while r <= length:
                x0 = round(RC + math.sin(a) * r - w / 2)
                y0 = round(RC - math.cos(a) * r - w / 2)
                for dx in range(w):
                    for dy in range(w):
                        if 0 <= x0 + dx < RING and 0 <= y0 + dy < RING:
                            im.putpixel((x0 + dx, y0 + dy), _hex(colour))
                r += 0.5
        needles.append(im)

    providers = [{"type": "space", "advances": {chr(BACK_CH): -(RING_HEIGHT + 1), chr(FORWARD_CH): RING_HEIGHT}}]

    def add(name, images, first):
        # the game measures a bitmap glyph's advance from its rightmost non-transparent column; a pixel of
        # alpha 1 (the text shader drops anything under 0.1, so it never shows) in the last column gives
        # every glyph the full width: advance 0.5 + 56 x 14/56 + 1 = 15, which the back space (-15) undoes
        for im in images:
            if im.getpixel((RING - 1, 0))[3] == 0:
                im.putpixel((RING - 1, 0), (0, 0, 0, 1))
        sheet, rows = _sheet(images)
        sheet.save(tex / f"fishring_{name}.png")
        chars = []
        for r in range(rows):
            row = ""
            for c in range(8):
                k = r * 8 + c
                row += chr(first + k) if k < len(images) else "\u0000"
            chars.append(row)
        providers.append({"type": "bitmap", "file": f"economy:font/fishring_{name}.png", "ascent": RING_ASCENT,
                          "height": RING_HEIGHT, "chars": chars})

    add("base", base, BASE_CH)
    add("center", centers, CENTER_CH)
    add("green", cells["green"], GREEN_CH)
    add("gold", cells["gold"], GOLD_CH)
    add("red", cells["red"], RED_CH)
    add("needle", needles, NEEDLE_CH)
    (font_dir / "fishring.json").write_text(json.dumps({"providers": providers}, ensure_ascii=True))


RING_STATES = [  # label, rarity index, hits so far, rim, centre shape, zone start cell, needle cell, legend trap
    ("흔함 · 시작", 0, 0, 0, "f", 14, 3, False),
    ("보통 · 성공 직후", 1, 1, 1, "l", 30, 22, False),
    ("희귀 · 완벽", 2, 1, 2, "c", 40, None, False),      # needle None: on the last gold (perfect) cell
    ("전설 · 함정 2칸", 3, 0, 0, "q", 20, 12, True),
    ("놓침 (빨간 테)", 1, 0, 3, "p", 5, 30, False),
]


def ring_preview(assets: Path, path: Path, rarity=None):
    """docs/previews/fishing_ring.png: the ring in five states, drawn from the ring font (ring_font) with the zones of RARITY as
    Ring.newRound makes them (zone = max(3, zone - hits), 2 gold cells from 5 cells up, a legend's 2 red cells behind its zone)."""
    rarity = rarity or RARITY
    font = assets / "economy" / "textures" / "font"
    R = 56

    def cell(name, k):
        im = Image.open(font / f"fishring_{name}.png").convert("RGBA")
        return im.crop(((k % 8) * R, (k // 8) * R, (k % 8) * R + R, (k // 8) * R + R))

    def ring(rim, shape, rar, zone, zw, perf, trap, needle):
        out = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        out.alpha_composite(cell("base", rim))
        out.alpha_composite(cell("center", "flpcq".index(shape) * 4 + rar))
        off = (zw - perf) // 2
        for i in range(zw):
            out.alpha_composite(cell("gold" if off <= i < off + perf else "green", (zone + i) % 48))
        for t in trap:
            out.alpha_composite(cell("red", t))
        out.alpha_composite(cell("needle", needle))
        return out

    W, H = 260, 330
    img = Image.new("RGBA", (W * len(RING_STATES), H), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    for n, (label, rar, hits, rim, shape, zone, needle, legend) in enumerate(RING_STATES):
        zw = max(3, rarity[rar][5] - hits)
        perf = 2 if zw >= 5 else 1
        if needle is None:
            needle = (zone + (zw - perf) // 2 + perf - 1) % 48
        trap = [(zone - 1) % 48, (zone - 2) % 48] if legend else []
        x0 = n * W
        for y in range(H):   # a sky-and-sea backdrop
            c = (70 + y // 6, 120 + y // 5, 190) if y < 200 else (30, 70 + (y - 200) // 4, 120)
            d.line([(x0, y), (x0 + W - 1, y)], fill=c)
        r = ring(rim, shape, rar, zone, zw, perf, trap, needle)
        img.alpha_composite(r.resize((R * 3, R * 3), Image.NEAREST), (x0 + (W - R * 3) // 2, 40))
    img.save(path)


def icon_font(assets: Path, icons):
    """economy:fishicon: every fish icon as a glyph (16 px shown at 8, so 32 GUI px in a title)."""
    ids = list(FISH)
    cols = 16
    rows = (len(ids) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 16, rows * 16), (0, 0, 0, 0))
    chars = []
    for r in range(rows):
        row = ""
        for c in range(cols):
            k = r * cols + c
            if k < len(ids):
                sheet.alpha_composite(icons[ids[k]], (c * 16, r * 16))
                row += chr(0xE000 + k)
            else:
                row += "\u0000"
        chars.append(row)
    sheet.save(assets / "economy/textures/font/fishicon.png")
    font = {"providers": [{"type": "bitmap", "file": "economy:font/fishicon.png", "ascent": 7, "height": 8, "chars": chars}]}
    (assets / "economy/font/fishicon.json").write_text(json.dumps(font, ensure_ascii=True))


# ------------------------------------------------------------------------------------------------
# Content files
# ------------------------------------------------------------------------------------------------

def write_content(pack: Path, icons):
    rar_ids = [r[0] for r in RARITY]
    out = ["# 낚시. 찌가 잠기기 전까지는 바닐라와 같고, 걸리면 화면 가운데 고리에서 바늘이 돕니다: 초록 칸에 오면 우클릭.",
           "# 흔함 2번 · 보통 3번 · 희귀 4번 · 전설 6번 성공하면 잡힘. 빈 곳을 누르거나 바늘이 두 바퀴 돌도록 안 누르면 하트 하나,",
           "# 빨간 함정은 둘. 성공할 때마다 빨라지고 칸이 좁아짐. 금색 칸 = 완벽 (크기 +3%, 전부 완벽이면 +10% 더).",
           "# 희귀·전설이 걸릴 확률은 jobs.yml 어부의 finds.chance (직업이 아니면 outsider), 그중 전설이 legend-share.",
           "# 크기는 size 범위 안에서 정해지고 희귀·전설은 아이템에 크기가 붙어 매입가가 0.7 ~ 1.5배 (흔함·보통은 쌓이게 크기 없음).",
           "# 물고기 그림·이 파일은 tools/assetgen/economy_fish.py 가 만듭니다.",
           "",
           "legend-share: 0.05     # 희귀·전설 입질 중 전설 비율 (어부 Lv30 이 낚는 전설 수가 링을 고치기 전과 같게)",
           "pearl: 0.15            # 바다에서 희귀가 걸렸을 때 진주가 나올 확률",
           "",
           "rarities:"]
    for (rid, name, colour, hits, speed, zone) in RARITY:
        out.append(f'  {rid}: {{ name: "{name}", color: "{colour}", hits: {hits}, speed: {speed}, zone: {zone} }}')
    out += ["", "# 도감 묶음: 다 잡으면 칭호", "groups:"]
    for gid, (name, tid, _) in HABITATS.items():
        out.append(f'  {gid}: {{ name: "{name}", title: economy:{tid} }}')
    out += ["", "# habitat: sea, deep, warm, cold, river, coldriver, swamp, mangrove, jungle, cave, mushroom, fresh (민물 어디나), salt (바다 어디나)",
            "# when: night, dawn, dusk, rain, thunder, fullmoon",
            "species:"]
    for fid, (name, hab, rar, lo, hi, price, shape, c1, c2, x) in FISH.items():
        parts = [f'name: "{name}"', f"habitat: {hab}", f"rarity: {rar_ids[rar]}", f"size: [{lo}, {hi}]", f"price: {price}", f"shape: {shape}"]
        if fid in VANILLA:
            parts.append(f"item: {VANILLA[fid]}")
        w = when(fid)
        if w:
            parts.append(f"when: {w}")
        out.append(f"  {fid}: {{ {', '.join(parts)} }}")
    (pack / "fish.yml").write_text("\n".join(out) + "\n", encoding="utf-8")

    items = ["# 낚시로 잡는 물고기 (fish.yml). 참돔·참다랑어·철갑상어는 items/jobs.yml, 대구·연어·복어는 바닐라.", ""]
    for fid, (name, hab, rar, lo, hi, price, shape, c1, c2, x) in FISH.items():
        if fid in VANILLA or fid in FINDS:
            continue
        hname = HABITATS[group(fid)][0] if group(fid) in HABITATS else hab
        items += [f"{fid}:", "  material: paper", f'  name: "<{RARITY[rar][2]}>{name}"',
                  f'  lore: ["<gray>{hname} · {RARITY[rar][1]}"]', f"  texture: {fid}", ""]
    (pack / "items" / "fish.yml").write_text("\n".join(items).rstrip() + "\n", encoding="utf-8")

    shop = (pack / "shop.yml").read_text(encoding="utf-8")
    # the 수산물 category of the 판매 side (sell.fish.items)
    start, end = "      # ── 낚시 (fish.yml, economy_fish.py 가 씀)\n", "      # ── 낚시 끝\n"
    lines = [start]
    for fid, (name, hab, rar, lo, hi, price, shape, c1, c2, x) in FISH.items():
        if fid in VANILLA or fid in FINDS:
            continue
        q = q_for(price)
        lines.append(f"      economy:{fid}: {{ base: {price}, q: {q}, job: fisher }}   # {name}\n")
    lines.append(end)
    block = "".join(lines)
    if start not in shop:
        raise ValueError("shop.yml: the 낚시 markers are missing from sell.fish.items")
    shop = shop[:shop.index(start)] + block + shop[shop.index(end) + len(end):]
    (pack / "shop.yml").write_text(shop, encoding="utf-8")

    titles = (pack / "titles" / "titles.yml").read_text(encoding="utf-8")
    marker = "# ── 도감 (economy_fish.py 가 씀)\n"
    if marker in titles:
        titles = titles[:titles.index(marker)]
    titles += marker
    for gid, (name, tid, text) in HABITATS.items():
        titles += f'{tid}:\n  name: "<#7fd8ff>{text}"\n  description: "도감: {name} 물고기를 모두 잡음"\n'
    titles += 'master_angler:\n  name: "<gradient:#7fd8ff:#ffd36a><b>[강태공]</b></gradient>"\n  description: "도감: 물고기를 모두 잡음"\n'
    (pack / "titles" / "titles.yml").write_text(titles, encoding="utf-8")


def sheet(path, icons, cols=14, scale=4):
    from PIL import ImageDraw, ImageFont
    font = ImageFont.truetype("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 13)
    ids = list(FISH)
    cw, ch = 16 * scale + 22, 16 * scale + 22
    rows = (len(ids) + cols - 1) // cols
    out = Image.new("RGBA", (cols * cw + 10, rows * ch + 10), (46, 48, 56, 255))
    d = ImageDraw.Draw(out)
    for k, fid in enumerate(ids):
        x, y = 5 + (k % cols) * cw, 5 + (k // cols) * ch
        d.rectangle([x, y, x + 16 * scale, y + 16 * scale], fill=(139, 139, 139, 255))
        out.alpha_composite(icons[fid].resize((16 * scale, 16 * scale), Image.NEAREST), (x, y))
        d.text((x, y + 16 * scale + 2), FISH[fid][0], fill=_hex(RARITY[FISH[fid][2]][2])[:3], font=font)
    out.save(path)
