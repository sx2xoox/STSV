#!/usr/bin/env bash
# Subset Pretendard to the glyphs used in vehicles/stsv_vehicles.html.
# Needs: pip install fonttools brotli. Full fonts: https://cdn.jsdelivr.net/npm/pretendard@1.3.9/dist/web/static/woff2/
# Usage (repo root): docs/promo/tools/subset_fonts.sh <dir-with-full-Pretendard-*.woff2>
set -euo pipefail
src="$1"; out=docs/promo/vehicles/fonts; chars=$(mktemp)
python3 -c "
import sys
s=open('docs/promo/vehicles/stsv_vehicles.html',encoding='utf-8').read()
extra='0123456789,.+-%&·→  '+''.join(chr(c) for c in range(0x21,0x7f))
print(''.join(sorted({c for c in s+extra if ord(c)>=32})),end='')" > "$chars"
for w in Black ExtraBold Bold Medium; do
  pyftsubset "$src/Pretendard-$w.woff2" --text-file="$chars" --layout-features='*' --flavor=woff2 --output-file="$out/Pretendard-$w.woff2"
done
rm -f "$chars"
