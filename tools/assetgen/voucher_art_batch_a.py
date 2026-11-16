"""Voucher icons of cosmetics batch A (content/stsv/items/cosmetics_batch_a.yml): the shared generated ticket
(voucher_art.voucher: rarity stub, a peek of the cosmetic's model), one per voucher of that file."""

from pathlib import Path

import yaml

import voucher_art

ITEMS = Path(__file__).resolve().parent.parent.parent / "content/stsv/items/cosmetics_batch_a.yml"


def _icons():
    data = yaml.safe_load(ITEMS.read_text(encoding="utf-8")) or {}
    out = {}
    for key, sec in data.items():
        target = (sec.get("voucher") or {}).get("cosmetic") if isinstance(sec, dict) else None
        if target:
            cos = data[target.split(":", 1)[-1]]
            out[key] = lambda cos=cos: voucher_art.voucher(cos)
    return out


ICONS = _icons()
