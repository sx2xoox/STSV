"""Voucher icons of cosmetics batch B (content/stsv/items/cosmetics_batch_b.yml): the shared generated ticket of
voucher_art.py (the rarity's stub and a peek of the cosmetic's model), one per voucher of that file."""

from pathlib import Path

import yaml

import voucher_art as VA

FILE = Path(__file__).resolve().parents[2] / "content" / "stsv" / "items" / "cosmetics_batch_b.yml"


def _icons():
    every = yaml.safe_load(FILE.read_text(encoding="utf-8")) or {}
    out = {}
    for key, sec in every.items():
        target = (sec.get("voucher") or {}).get("cosmetic") if isinstance(sec, dict) else None
        if target:
            cosmetic = every[target.split(":", 1)[-1]]
            out[key] = lambda c=cosmetic: VA.voucher(c)
    return out


ICONS = _icons()
