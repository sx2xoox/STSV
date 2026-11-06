"""
Economy menu backgrounds (176 x (114 + 18 x rows)), drawn from each menu's own layout in
economy/content/economy/menus/*.yml in the one look every STSV menu shares (stsv_assets/menu_theme.py):
charcoal panel, recessed slots for the paged list and `inset` cells, raised tiles for buttons, a chart
screen for `graph: true`, lane arrows for `flow: true`.
"""

from pathlib import Path

import yaml

from stsv_assets import menu_theme


def background(menu):
    return menu_theme.background(menu)


def menus(pack: Path):
    out = {}
    for f in sorted((pack / "menus").glob("*.yml")):
        for key, spec in yaml.safe_load(f.read_text(encoding="utf-8")).items():
            out[key] = spec
    return out
