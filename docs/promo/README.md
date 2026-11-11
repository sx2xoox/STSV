# STSV 소개 모션그래픽

1920×1080 · 60초 · 30fps · 소리 없음.

- `stsv_promo.mp4` — 완성 영상
- `stsv_promo.html` — 같은 영상을 캔버스로 재생하는 한 페이지 (클릭/스페이스: 일시정지, 아래 막대로 탐색, `?t=12.5` 로 특정 시점)
  - `file://` 로 열면 글꼴·이미지가 막힐 수 있으니 `python3 -m http.server -d docs/promo` 처럼 띄워서 여세요.
- `assets/` — `docs/previews/` 미리보기에서 잘라낸 기계·가구·치장 그림, 게임 아이템 텍스처 복사본
- `fonts/` — Neo둥근모(`neodgm.woff2`), 갈무리11 Bold(숫자용). 둘 다 SIL OFL 1.1

| 시간 | 장면 |
| --- | --- |
| 0–5.5s | 복셀 블록이 모여 STSV → "많이 하면 많이 번다" |
| 5.5–16s | 직업 6종 카드 뒤집기 · 기계가 기본→3강으로 강화 |
| 16–26.5s | 협업 경제: 원재료 → 기계 → 완성품 → 금화 · 특별 재료 |
| 26.5–37s | 농사 타임랩스 / 낚시 링 / 주식 캔들 3분할 |
| 37–46.5s | 청크 땅 · 건축/야생월드 · 일본풍 가구 · 치장 뽑기·닉네임 |
| 46.5–55s | 업적·미션·사건 알림 몽타주 · 주간 순위 · HUD |
| 55–60s | "지금 STSV에서" |

장면 사이 전환은 블록이 쌓였다가 부서지는 복셀 전환입니다. 가격·레시피·사건 이름은 `economy/content/economy/*.yml` 값을 따랐습니다.

## 다시 만들기

```bash
python3 docs/promo/tools/prep_assets.py              # 에셋 다시 자르기 (저장소 루트에서)
node docs/promo/tools/render.js stills /tmp/st 12 24 # 특정 시점 PNG
node docs/promo/tools/render.js video docs/promo/stsv_promo.mp4   # Playwright + ffmpeg
```
