"""
Economy menu backgrounds (176 x (114 + 18 x rows)), drawn from each menu's own layout in
economy/content/economy/menus/*.yml in the one look every STSV menu shares (stsv_assets/menu_theme.py):
charcoal panel, recessed slots for the paged list and `inset` cells, raised tiles for buttons, a chart
screen for `graph: true`, lane arrows for `flow: true`.
"""

from pathlib import Path

import yaml

from stsv_assets import menu_theme


def art(icon_id):
    """A big button's picture: stsv:gui/<icon> or stsv:<item> (core), economy:gui/<icon> or economy:<item> (economy)."""
    import gui
    ns, path = icon_id.split(":", 1)
    if ns == "stsv":
        return gui.art(icon_id)
    if path.startswith("gui/"):
        import economy_icons as E
        return E.GUI[path[4:]]()
    from PIL import Image
    return Image.open(Path(__file__).resolve().parents[2] / "economy/content/economy/textures" / f"{path}.png").convert("RGBA")


def background(menu):
    return menu_theme.background(menu, art)


def menus(pack: Path):
    out = {}
    for f in sorted((pack / "menus").glob("*.yml")):
        for key, spec in yaml.safe_load(f.read_text(encoding="utf-8")).items():
            out[key] = spec
    return out
