"""
The game's own text font (minecraft:default: chat, item names and lore, menu titles, signs, the scoreboard) with
its Korean drawn from Neo둥근모, the 16 px Korean pixel font (tools/assetgen/neodgm.py; SIL OFL 1.1).

The game draws Hangul from Unifont the "unihex" way: 16 px glyphs in its 8 px line (one font pixel = half a GUI
pixel; sharp at GUI scale 2 and 4, uneven at 3 like all of the game's Korean), shadow and bold half a GUI pixel
to the side. The pack gives the same provider Neo둥근모's glyphs instead, so they are drawn exactly as the game
draws its own Korean, only in a clearer, rounder design: every Hangul syllable (11,172), the compatibility jamo
(ㅋㅋ, ㅠㅠ) and CJK symbols and punctuation (「」『』【】、。). The em square goes in as it is: Hangul sits on
the game's baseline, half a GUI pixel lower at the top than the game's capitals.

Latin, digits, punctuation and the signs the game has 8 px pictures for keep the Minecraft look and widths, and
everything else is still Unifont:

    space (game) -> game bitmaps (ascii, accented, nonlatin_european) -> Neo둥근모 (unihex) -> Unifont

With "Force Unicode Font" on, the player gets the game's Unifont as before (same filter as the game's bitmaps).
The pack's glyphs are a Modified Version of Neo둥근모 under the OFL, so they never carry its (reserved) name.

    python3 default_font.py        # writes plugin/src/main/resources/pack/default_font/
"""

import io
import json
import zipfile
from pathlib import Path

import neodgm

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "plugin" / "src" / "main" / "resources" / "pack" / "default_font"
HEX_FILE = "stsv:font/default/korean.zip"      # assets/stsv/font/default/korean.zip


def build():
    font = neodgm.load()
    lines, spaces = [], {}
    for cp in sorted(font):
        advance, rows = font[cp]
        if not any(any(r) for r in rows):
            spaces[chr(cp)] = advance // 2            # U+3000 ideographic space, U+3164 Hangul filler
            continue
        hexrows = "".join(format(int("".join("1" if b else "0" for b in r), 2), f"0{advance // 4}X") for r in rows)
        lines.append(f"{cp:04X}:{hexrows}\n")
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        info = zipfile.ZipInfo("korean.hex", date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, "".join(lines))
    uniform_off = {"uniform": False}
    providers = [
        {"type": "reference", "id": "minecraft:include/space"},
        {"type": "reference", "id": "minecraft:include/default", "filter": uniform_off},
        {"type": "space", "advances": spaces, "filter": uniform_off},
        {"type": "unihex", "hex_file": HEX_FILE, "size_overrides": [], "filter": uniform_off},
        {"type": "reference", "id": "minecraft:include/unifont"},
    ]
    return data.getvalue(), {"providers": providers}, len(lines)


def write(out_dir: Path = OUT):
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in ("hangul.png", "signs.png", "LICENSE-galmuri.txt"):
        if (out_dir / old).exists():
            (out_dir / old).unlink()
    zipped, font, glyphs = build()
    (out_dir / "korean.zip").write_bytes(zipped)
    (out_dir / "default.json").write_text(json.dumps(font, ensure_ascii=False, separators=(",", ":")) + "\n",
                                          encoding="utf-8")
    (out_dir / "LICENSE-neodgm.txt").write_text(
        "korean.zip (assets/stsv/font/default/) holds glyphs converted from Neo둥근모 1.600\n"
        "(https://neodgm.dalgona.dev): its Hangul syllables, compatibility jamo and CJK symbols and punctuation,\n"
        "in the game's .hex format, for the game's default font in the STSV resource pack. It is a Modified Version\n"
        "under the license below and does not use the Reserved Font Names.\n\n"
        + neodgm.LICENSE.read_text(encoding="utf-8"), encoding="utf-8")
    return {"glyphs": glyphs, "korean.zip": len(zipped)}


if __name__ == "__main__":
    print(write())
