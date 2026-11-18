# UI 한옥 테마 전환 작업안 (상점 · 직업 기계 → 나머지 메뉴)

작성 2026-11-18. 코드·콘텐츠는 아직 안 바꿈. 이 문서는 계획만.

---

## 1. 지금 한옥 통합 메뉴는 어떻게 만들어졌나

관련 커밋 (오래된 순):

| 커밋 | 내용 | 손댄 곳 |
|---|---|---|
| `154e7c8` | 통합 메뉴 새 디자인: 큰 카드 3개 + 아래 바 9칸 (`theme: hub`, 아직 숯색) | `menu_theme.py`, `hub.yml` 두 개, `HubMenuTest`·`HubTest`·`MenuActionsTest`·`ContentTest` |
| `7a04524` | **한옥 테마**: PixelLab 그림, Sunflower 글씨, 패널 밖까지 2배 해상도 | `tools/assetgen/hub_hanok.py`(새 파일), `hub_art/*`, `fonts/Sunflower-Medium.ttf`, `MenuSpec.java`(frame), `menu_theme.py`·`menu_preview.py`·`gui.py`, hub 배경 PNG 9장, 테스트 3개 |
| `9f0075e` | 카드·마루 아이콘을 PixelLab 으로 (작은 카드 24px, 마루 16px) | `hub_art/icons/*.png`, `gui.py`(`hub:` 아이콘), `economy_gui.py` |
| `976df2a` | 카드 여백 대칭, 글씨 칸 맞춤 축소, 지붕 좌우 대칭 | `hub_hanok.py` |
| `ee0ab7e` | 배경이 안 그려지던 것 수정: **256×256 글리프 제한** → 조각(tile)으로 나눔 | `MenuSpec.java`(`tiles()`), `WardrobeGuiTest` |

만드는 방식:

- **메뉴 yml 키** (`economy/content/economy/menus/hub.yml`, `hub_minigame.yml`, 코어 `content/stsv/menus/hub.yml`·`hub_minigame.yml`)
  - `theme: hanok` → `menu_theme.background()` 가 `hub_hanok.background()` 로 넘김
  - `frame: {left: 30, right: 30, top: 20, scale: 2}` → 그림이 패널(176px) 밖으로 좌우 30, 위 20 더 나가고 2배 해상도. **이 숫자는 `hub_hanok.py` 의 상수(LEFT/RIGHT/TOP/SCALE)와 같아야 함**
  - `title: ""` + `plaque: "거래"` → 화면 이름은 지붕의 현판에 **배경으로 구워짐** (제목 줄은 비움)
  - `dock: 5` → 맨 아래 줄은 마루(나무 틀 + 한지 소켓 9칸). `art` 없는 칸은 금색 소켓(첫 화면은 내 머리)
  - 버튼: `scene` + `label` = 큰 그림 카드(4줄 이상), `art` + `label` = 작은 카드(24px 아이콘 + 이름판), `socket: true` = 마루 밖 소켓 하나. 칸에는 `icon: stsv:gui/blank`(안 보이는 아이템) → 설명만 뜸
- **생성기**: `tools/assetgen/hub_hanok.py` (그림), `stsv_assets/menu_theme.py` (테마 분기, 슬롯·인벤토리 칸), `economy_gui.py`/`gui.py` 의 `art()` (`hub:<이름>` → `hub_art/icons/<이름>.png`). 실행은 `economy_build.py`(경제) / `build.py`(코어)
- **그림 부품** (`tools/assetgen/hub_art/`): `roof.png` 236×32 (왼쪽 반을 뒤집어 대칭), `lantern.png` 10×51, `pillar_mid.png` 12×40, `pillar_base.png` 17×22, `scenes/*.png` 48×64 (11장), `icons/*.png` 24×24(카드) / 16×16(마루) 43장
- **코드로 그리는 것**: 패널(어두운 한지 + 창살 격자), 나무 틀(`_frame_box`), 금색 모서리(`_gold_corners`), 이름판(`_board`), 단청 띠(`_dancheong`), 마루 소켓, 인벤토리 칸(`T.slot`)
- **글꼴**: `tools/assetgen/fonts/Sunflower-Medium.ttf` (SIL OFL). 그림 안에 2배 격자로 1텍셀 테두리와 함께 씀(`_write`/`_layer`). 칸보다 크면 줄임. Sunflower 에 `·` 가 없어 점을 직접 그림
- **게임에 보이는 방법** (`plugin/.../gui/menu/MenuSpec.java`): 메뉴마다 `stsv:menus` 폰트 글자 하나(U+E000+n). frame 메뉴는 그림을 **256×256 이하 조각**으로 잘라 U+E800+16n 부터, 메뉴별 칸 띄우기 글자 U+F100+4n. 6줄 메뉴 = 472×484 텍셀 = 2×2 조각
- 결과 PNG: 장당 약 22~36KB (예전 숯색은 1.3~1.6KB)

**재사용 가능한 부품**: 지붕+현판, 기둥+초롱, 창살 패널, 나무 틀 카드, 큰 그림 카드, 작은 아이콘 카드, 마루(dock), 단독 소켓, 단청 띠, 인벤토리 칸, Sunflower 글씨 함수. **없는 것**: 목록 칸(`list`), 물건 칸(`inset`), 흐름 화살표(`flow`), 탭(`tabs`), 바뀌는 제목(카테고리·기계 이름), 칸 사이가 빈 마루.

---

## 2. 전환 대상 전체 목록

테마: 숯색 = `menu_theme.py` 기본, 한옥 = `theme: hanok`, 서킷 = `theme: circuit`(유지), 없음 = 배경 없는 바닐라 상자.
난이도: 하(yml + 배경만) / 중(생성기 부품 추가 또는 코드 약간) / 상(코드 구조·특수 그림).

### 2-1. 이번 범위: 상점 · 직업 기계

| 화면 | 파일 | 지금 | 줄 | 특이점 | 난이도 |
|---|---|---|---|---|---|
| 상점 첫 화면 `economy:shop` | `menus/shop.yml`, `ShopModule.FrontMenu` | 숯색 | 3 | 잔액(툴팁), 구매/판매 2버튼 | 하 |
| 구매 카테고리 `shop_buy` | `shop.yml`, `ShopModule.BuyCategories` | 숯색 | 3 | 목록 9칸 = `MAX_BUY_CATEGORIES`(상인 행렬은 사건 때만), `ShopCatalog.row` 로 가운데 정렬 | 중 |
| 판매 카테고리 `shop_sell` | `shop.yml`, `ShopModule.SellCategories` | 숯색 | 3 | 목록 7칸 = `MAX_SELL_CATEGORIES`, 인벤토리 클릭 판매, 전부 팔기 | 중 |
| 구매 품목 `shop_buy_items` | `shop.yml`, `BuyItems`·`MerchantItems` | 숯색 | 6 | 목록 36칸, 페이지, 제목에 `{category}`, 가구는 빈칸 섞인 배치(`category.cells`) | 중 |
| 판매 품목 `shop_sell_items` | `shop.yml`, `SellItems` | 숯색 | 6 | 목록 36칸, 페이지, `{category}`, 카테고리 전부 팔기 | 중 |
| (구매 → 치장 뽑기) | 코어 `stsv:gacha` | 숯색 | 6 | 상점에서 넘어가는 화면. 2-2 에서 처리 | — |
| 기계 본 화면 `economy:machine` | `menus/machine.yml`, `job/MachineModule.java` (직접 `createInventory`) | 숯색 | 6 | **칸 위치가 코드 고정**(재료 9(k+1)+1~3, 상태 +5, 결과 +7, 버튼 0/4/8/48/50), 기계 하나에 인벤토리 하나(여러 명이 같이 봄), 제목은 만들 때 한 번만 정해짐, `inset`+`flow` | 상 |
| 기계 강화 | `MachineModule.draw()` 의 칸 8 버튼 | — | — | 따로 화면 없음. 두 번 눌러 확정(반짝임), 최고 단계는 왕관 | 중 |
| 레시피 고르기 / 레시피 책 `machine_recipes` | `machine.yml`, `MachineModule.RecipeMenu` | 숯색 | 6 | 같은 메뉴를 줄 고르기(lane≥0)와 책(lane<0)이 같이 씀, 목록 36칸, 페이지, 제목 `{machine} 레시피{lane_text}`. **단계 탭 · 분류 개편은 별도 기획과 맞춤** | 상 |

기계별 레시피 수 (`machines.yml`): 세공대 22 (기본10/1강7/2강4/3강1), 가공대 21 (12/6/2/1), 손질대 14 (5/5/4/0), 대장간 22 (9/8/3/2), 조리대 49 (23/19/6/1), 증류기 23 (7/6/6/4), 압착기 9 (4/1/2/2).

### 2-2. 나머지 YAML 메뉴 (아직 숯색)

| 화면 | 파일 | 줄 | 특이점 | 난이도 |
|---|---|---|---|---|
| 거래소 `market`, `market_orders` | `economy/.../menus/market.yml` | 6 | 탭 2(`tabs: AB`), 목록 36, `{mode}` | 중 |
| 거래소 판매 등록 `market_sell`, 주문 `market_order` | `market.yml` | 4, 5 | `inset` 물건 칸, 가격 고르기 버튼 많음 | 중 |
| 살 물건 고르기 `market_order_pick` | `market.yml` | 6 | 목록 36 | 하 |
| 경매 `auction`, `auction_sell`, `auction_bid` | `auction.yml` | 4 | 목록 14 / `inset`, 입찰 버튼 | 중 |
| 우편함 `mail` | `mail.yml` | 6 | 목록 36 | 하 |
| 직거래 `trade` | `trade.yml`, `trade/TradeModule.java`(직접 `createInventory`) | 6 | 양쪽 칸(`list A` + `inset B`), 대칭 아님, `{a} ⇄ {b}` | 상 |
| 주식 목록 `stocks` | `stocks.yml` | 6 | 목록 36, `{tab}` `{sort}` | 중 |
| 주식 한 종목 `stock` | `stocks.yml` | 6 | **`graph: true`: 차트가 제목 글꼴(stockgraph)로 그려짐** → 좌표 맞춤 필요 | 상 |
| 직업 `jobs` | `jobs.yml` | 4 | 목록 6 | 하 |
| 미션 `missions` | `missions.yml` | 3 | 목록 6 | 하 |
| 의뢰 게시판 `requests` | `requests.yml` | 3 | 목록 8 | 하 |
| 도감 `codex` | `codex.yml` | 6 | 목록 36, 탭은 코드 | 중 |
| 치장 `wardrobe` | 코어 `content/stsv/menus/wardrobe.yml` | 6 | 탭 12, 목록 21 | 중 |
| 치장 뽑기 `gacha` | `gacha.yml` | 6 | 탭 12, 목록 21, 뽑기 연출 | 중 |
| 칭호 `titles` | `titles.yml` | 6 | 탭 7, 목록 36 | 중 |
| 닉네임 색 `nick_colors` | `nick_colors.yml` | 6 | 목록 45 | 하 |
| 감정표현 `emote_wheel` / `emote_pick` | `emote_wheel.yml` | 6 / 4 | **`wheel`(원판)** 전용 그림 | 상 |
| 후원 상점 `donation` / `donation_buy` | `donation.yml` | 6 / 3 | 탭 9, 목록 36 / `inset` | 중 |
| 탈것 튜닝 `tuning` | `tuning.yml` | 6 | 탭 9, 목록 36 | 중 |
| 프로필 `profile` | `profile.yml` | 6 | 목록 14, 제목 `{title}`(칭호 배너) | 중 |
| 업적 `achievements` / `achievement_list` | `achievements.yml` | 4 / 6 | 목록 14 / 28 | 하 |
| 서킷 대기실·관전 | `circuit.yml` | 6 / 4 | `theme: circuit` 전용 — **바꾸지 않음** | — |

합계: 경제 23개(상점 5 + 기계 2 포함), 코어 12개가 숯색. 한옥 13개(허브), 서킷 2개.

### 2-3. 배경 없는 코드 화면 (바닐라 상자)

| 화면 | 파일 | 크기 | 비고 |
|---|---|---|---|
| 부두 짐 · 터미널 · 봉인 터미널 | `economy/.../dock/DockModule.java` | 54 / 54 / 27 | 나중에 `trade` 처럼 메뉴 스펙의 `backgroundPrefix()` 를 빌려 쓰면 됨 |
| 생선 경매 | `dock/FishAuction.java` | 27 | 〃 |
| 쓰레기통 | `core/command/TrashCan.java` | — | 〃 |
| 스킨 적용 | `core/skin/SkinModule.java` | 27 | 〃 |
| 탈것 짐칸 · 지게 | `core/vehicle/VehicleModule.java`, `JigeModule.java` | 가변 | 크기가 바뀌어 줄 수별 그림 필요 |
| 아이템 목록 · 레시피 편집 | `core/gui/ItemBrowser.java`, `RecipeEditor.java` | 54 | 관리자용 — 제외 |

---

## 3. 공통 부품 (모든 화면이 같은 모양이 되게)

생성기 `hub_hanok.py` 에 부품을 더하고, yml 키로 고름. 이름은 제안.

| 부품 | 모양 | yml / 코드 | 상태 표시 |
|---|---|---|---|
| **지붕 현판 (고정 이름)** | 지금 그대로. `plaque: "상점"` | 배경에 구움 | — |
| **현판 글자 (바뀌는 이름)** | 카테고리·기계 이름을 Sunflower 로 미리 그린 **글자 글리프**. 현판 크기(146×12px, 2배 텍셀)로 가운데 정렬까지 구워 둠 | 새 폰트 `economy:plaque`(따로 폰트 파일 → 메뉴 폰트와 글자 충돌 없음). 생성기가 `shop.yml` 카테고리 이름, `machines.yml` 기계 이름을 읽어 PNG + `font/plaque.json`. 코드: `Plaque.title(key)` → `<font:economy:plaque>` 칸 띄우기 + 글자 | 없는 이름이면 지금처럼 기본 글꼴 제목으로 |
| **마루 (dock)** | 지금 마루. **칸 사이 빈 곳은 소켓 없이 나무판**으로 (지금은 먹색) | `dock: <줄>` | — |
| **뒤로가기** | 마루 소켓, `hub:back` 아이콘(이미 있음) | 배경에 구움 + blank 아이템 | — |
| **닫기** | 마루 소켓, 새 16px 아이콘(문) | 〃 | — |
| **돈 표시** | 마루 가운데 **금색 소켓**(통합 메뉴의 내 머리 자리와 같은 위치·모양) + 엽전 주머니 아이콘. 잔액은 툴팁 `{balance}` | art 없이 두면 금색 소켓 → 아이템(`economy:gui/hanok_purse`)을 얹음 | 제목은 열 때 한 번만 정해지므로 잔액을 제목에 넣지 않음 |
| **페이지 버튼 ◀ ▶** | 마루 양 끝 소켓. **아이콘은 아이템 모델**(배경에 굽지 않음) | `icon: economy:gui/hanok_prev`, `off.icon: …_prev_off` | 첫/끝 페이지에서 흐린 아이콘(지금 `off` 그대로) |
| **목록 칸** | 한지색 들어간 칸(어두운 테두리 + 연한 안쪽), 6×n 이면 나무 진열장 틀로 묶음 | `list: L` 를 생성기가 그림 (새로 추가) | — |
| **물건 칸 (inset)** | 목록 칸과 같고, 결과 칸은 금색 테두리 | `inset: "IO"` + 새 키 `gold: "O"` | — |
| **흐름 화살표** | 먹으로 그은 붓 화살표 | `flow: true` (한옥판 새로) | — |
| **탭** | 단청 띠를 얹은 소켓, 칸 안에 Sunflower 짧은 글자(예: `1강`) 또는 16px 아이콘 | `tabs: "abcd"` + 버튼 `tab_label:` | **선택 표시 = 금테 오버레이 아이템**(`economy:gui/hanok_tab_on`, 가운데가 비어 구운 글자가 보임). 지금처럼 반짝임도 가능 |
| **작은 카드 / 큰 그림 카드** | 지금 그대로 | `art`+`label` / `scene`+`label` | — |
| **안내 띠** | 목록 위 빈 줄에 Sunflower 한 줄(예: "살 것을 고르세요") | 새 키 `note: {row, text}` | — |

원칙: **누를 때 모양이 바뀌는 것(페이지, 탭 선택, 강화 확정, 잠긴 줄, 진행 상태)은 아이템 아이콘**, 안 바뀌는 것(뒤로, 닫기, 카드)은 **배경에 굽기 + `stsv:gui/blank`**. 16px 아이템 아이콘은 2배 배경 위에서도 마루에 구운 16px 아이콘과 같은 크기로 보임.

공통 마루 배치(대칭 검사 통과하도록):

```
페이지 있는 화면:  P . B . $ . X . N     (P ◀, B 뒤로, $ 돈, X 닫기, N ▶)
페이지 없는 화면:  . . B . $ . X . .
```

---

## 4. 상점 화면별 변경안

모든 상점 화면: `title: ""`, `theme: hanok`, `frame: {left: 30, right: 30, top: 20, scale: 2}`. 3줄 화면도 frame 조건(가로 236, 세로 188 → 2×2 조각, 나누어떨어짐) 통과.

### 4-1. 상점 첫 화면 `shop` (3줄 → 6줄)

```
. B B B . S S S .     B = 구매 큰 그림 카드 (scene: market 재사용, label "사기")
. B B B . S S S .     S = 판매 큰 그림 카드 (scene: money 재사용 또는 새 '저울' 장면, label "팔기")
. B B B . S S S .
. B B B . S S S .
. B B B . S S S .
. . H . $ . X . .     dock: H 통합 메뉴로(hub:back), $ 잔액, X 닫기
```
- 현판: `plaque: "상점"`. 카드 이름판 아래 짧은 설명은 툴팁(lore) 그대로
- 바뀌는 것: `shop.yml` 만. 코드는 그대로(`FrontMenu` 는 `{balance}` 만 줌)
- 6줄로 키우는 이유: 큰 그림 카드는 4줄 이상이어야 함(장면 48×64). 통합 메뉴 "거래 → 상점" 에서 넘어올 때 화면 높이가 같아 덜 튐

### 4-2. 구매 카테고리 `shop_buy` (3줄 유지)

```
. . . . . . . . .     안내 띠 note: "무엇을 살까요"
L L L L L L L L L     목록 9칸 = 나무 진열대(한지 칸 9개)
. . B . $ . X . .     dock
```
- 현판 `plaque: "구매"`. 카테고리 아이콘은 지금처럼 아이템(상인 행렬은 사건 때만 보이므로 굽지 않음)
- `ContentTest` 의 "목록 칸 수 = MAX_BUY_CATEGORIES(9)" 유지
- 코드 변경 없음(`BuyCategories` 는 `{balance}` 만 쓰고, 정보 버튼 I 를 dock 의 $ 로 옮김)

### 4-3. 판매 카테고리 `shop_sell` (3줄 유지)

```
. . A . . . T . .     A = 전부 팔기 소켓(새 아이콘 '보따리'), T = 시세 보는 법 소켓(hub:chart 16px판)
. L L L L L L L .     목록 7칸
. . B . $ . X . .     $ 의 툴팁에 "인벤토리 클릭: 팔기" 안내를 그대로 옮김
```
- 현판 `plaque: "판매"`. 코드 변경 없음

### 4-4. 구매 품목 `shop_buy_items` / 판매 품목 `shop_sell_items` (6줄)

```
구매                        판매
. . . . . . . . .           . . A . . . T . .      (A 이 카테고리 전부 팔기, T 시세)
L L L L L L L L L           L L L L L L L L L
L L L L L L L L L           L L L L L L L L L      목록 36칸 (진열장 틀 하나로 묶음)
L L L L L L L L L           L L L L L L L L L
L L L L L L L L L           L L L L L L L L L
P . B . $ . X . N           P . B . $ . X . N
```
- **목록 36칸은 그대로** (가구 `category.cells` 의 "30 + 빈칸 6" 배치와 `ContentTest` 가 36칸 기준)
- 현판: 바뀌는 이름 → **현판 글자 글리프**. "구매 · 도구", "판매 · 광물" … (구매 8 + 상인 행렬 + 판매 7 = 16개). 구매 화면 첫 줄의 `{hint}`(I 버튼 툴팁)는 $ 툴팁으로
- 코드: `MenuModule.open()` 이 제목을 만들 때 `{category}` 대신 글리프를 넣을 수 있게 → `MenuHandler` 에 `default String plaque(MenuView v)` 하나 추가(없으면 지금처럼 title). `BuyItems`·`MerchantItems`·`SellItems` 가 카테고리 id 를 돌려줌. 약 30줄

### 4-5. 상점에서 바뀌는 것 정리

| 무엇 | 콘텐츠(yml·그림) | 코드 |
|---|---|---|
| 5개 화면 레이아웃·버튼 | `shop.yml` 다시 씀 | — |
| 배경 5장 | `economy/content/economy/assets/economy/textures/gui/shop*.png` (생성) | — |
| 바뀌는 현판 | `font/plaque.json` + PNG (생성) | `MenuHandler.plaque()`, `MenuModule.open()`, `ShopModule` 3곳 |
| 새 아이콘 | `hub_art/icons/*.png` + `economy:gui/hanok_*` 아이템 | `economy_icons.py` 에 등록 |

---

## 5. 기계 화면별 변경안

### 5-1. 기계 본 화면 `machine` (6줄, 칸 위치는 코드 고정 유지)

```
R . . . N . . . U     R 레시피 책 소켓, N 기계 명패(작은 카드: 기계 아이콘 + "정보"), U 강화 소켓
. I I I → S → O .     줄 1   I 재료 한지 칸, → 붓 화살표, S 상태 둥근 화로 소켓, O 결과 금테 칸
. I I I → S → O .     줄 2
. I I I → S → O .     줄 3
. I I I → S → O .     줄 4   (네 줄을 각각 나무 작업대 띠로 묶음)
. . . C . X . . .     dock: C 결과 전부 받기(궤짝), X 닫기, 가운데 칸은 소켓 없는 나무판(기계는 돈 표시 없음)
```
- 칸 번호 `BOOK 0, INFO 4, UPGRADE 8, COLLECT 48, CLOSE 50`, 재료·상태·결과 위치는 **바꾸지 않음** → `MachineClickTest` 그대로. `ContentTest` 는 기계만 대칭 검사 예외라 그대로 둬도 됨
- 현판: 기계 이름 7개를 **현판 글자 글리프**로. `Machine` 생성자(`MachineModule.java:351`)에서 `{machine}` 대신 글리프. 인벤토리를 기계마다 한 번 만들기 때문에 **단계(1강 등)는 현판에 넣지 않음**(강화해도 제목이 안 바뀜) → N 툴팁
- 상태가 바뀌는 칸은 아이템 아이콘으로 한옥풍 새 아이콘:
  - U: 강화 가능 `hanok_hammer`(확정 대기 = 반짝임 그대로), 최고 단계 `hanok_crown`
  - S: 쉬는 줄 `hanok_kiln_idle`, 잠긴 줄 `hanok_lock`(지금 BARRIER), 만드는 중은 지금처럼 결과물 아이템(이름에 진행 막대)
  - R `hanok_book`, C `hanok_chest`, X 문 아이콘
- 기계마다 다른 그림(세공대 보석, 대장간 불 …)은 **2차로 미룸**: 하려면 `economy:machine_<id>` 메뉴 7개 + `Machine` 생성자에서 있으면 그것, 없으면 `economy:machine`. 배경 7장 ≈ 250KB
- 생성기 추가: `inset`, `flow`, 결과 칸 금테(`gold: "O"`), 줄 띠

### 5-2. 강화

- 따로 화면을 만들지 않음. U 소켓 + 툴팁(비용·레벨·줄 수·속도·새 레시피 수)은 지금 그대로
- 나중에 확인 화면이 필요하면 `machine_upgrade`(3줄: 지금 단계 카드 → 다음 단계 카드, 아래 dock 에 확정/취소)를 같은 부품으로. 이번 범위 밖

### 5-3. 레시피 고르기 / 레시피 책 `machine_recipes` (6줄)

**별도 기획(단계가 기본~3강 한데 섞여 보기 어려운 문제)과 맞춤**: 이 문서는 자리만 잡아 두고, 탭이 어떻게 동작하는지는 그 기획을 따름.

```
. . a b $ c d . .     단계 탭 4개: a 기본, b 1강, c 2강, d 3강 (가운데 $ 자리는 정보 소켓 I: 기계·지금 단계·고르는 줄)
k L L L L L L L L     k 분류 탭(왼쪽 세로 4칸, 예: 조리대 밥·국 / 반찬 / 술 / 기타). 분류가 없는 기계는 k 칸 숨김
k L L L L L L L L     목록 8×4 = 32칸
k L L L L L L L L
k L L L L L L L L
P . B . . . X . N     dock: ◀, 기계로 돌아가기, 닫기, ▶
```
- 대칭 검사: 0줄 `..#####..` 대칭, 1~4줄은 9칸 모두 사용 → 대칭. 통과
- 32칸이면 가장 많은 "조리대 기본" 23 + 자동 1 = 24 → **탭 하나는 한 페이지**. 탭 없이 전부 보는 경우(조리대 49+1)는 페이지가 있어 `theRecipeBookShowsEveryRecipe` 통과
- 탭 글자(`기본` `1강` `2강` `3강`)는 배경에 Sunflower 로 굽고, 선택 탭은 금테 오버레이 아이템, 잠긴 단계 탭은 자물쇠 오버레이
- 현판: "세공대 레시피" 7개 글리프. 줄 번호(`{lane_text}`)는 I 툴팁으로 → 제목 자리 걱정 없음
- 코드: `RecipeMenu` 에 탭 상태(단계·분류) + 버튼 동작 `machine:tier:<n>`, `machine:group:<id>`. 분류 정의는 `machines.yml` 에 `group:` 같은 키(별도 기획에서 정함)
- 별도 기획이 늦어지면: 1차는 탭 칸을 소켓만 그려 두고 `I` 하나만 쓰는 지금 동작 그대로 둘 수 있음(배경에 탭 글자를 안 굽는 판)

---

## 6. 필요한 그림 목록

크기 제한(코드에서 찾은 것):
- 글리프 하나 **256×256 텍셀 이하** (`MenuSpec.GLYPH_MAX`), 메뉴 하나 조각 **16개 이하**, `scale` 1~4. 가로 `(176+left+right)`, 세로 `(114+18×줄+top)` 이 조각 수로 나누어떨어져야 함 → 30/30/20/2 이면 3~6줄 모두 2×2 조각(최대 236×242 텍셀/조각)
- 비트맵 폰트는 `ascent ≤ height` → 현판 글리프는 위쪽으로 올리려면 아래를 투명하게 늘린 그림으로
- 제목 폭 `MenuSpec.TITLE_ROOM` = 160px (현판 글리프는 폭 0 으로 치게 테스트 손봐야 함)
- 아이템 아이콘은 16×16 으로 그려짐 → 마루·소켓 아이콘 16px, 작은 카드 24px, 장면 48×64

### PixelLab 으로 뽑을 것 (장면 그림과 같은 색감, `market.png` 스타일 참고, 여러 장 뽑아 고름)

| 이름 | 크기 | 쓰는 곳 | 비고 |
|---|---|---|---|
| `scenes/scale.png` (저울·엽전) | 48×64 | 상점 첫 화면 "팔기" | `money.png` 재사용이면 생략 |
| `icons/close.png` (한옥 문) | 16 | 모든 화면 닫기 | |
| `icons/purse.png` (엽전 주머니) | 16 | 돈 표시 | |
| `icons/prev.png`, `next.png` (+ 흐린 판은 코드로) | 16 | 페이지 | 흐린 판 = 채도·밝기 낮춤 |
| `icons/bundle.png` (보따리) | 16 | 전부 팔기 / 이 카테고리 전부 | |
| `icons/chart16.png` | 16 | 시세 보는 법 | 24px `chart.png` 축소보다 새로 뽑기 권장 |
| `icons/book16.png` (책) | 16 | 레시피 책 | |
| `icons/hammer.png`, `crown.png` | 16 | 강화 / 최고 단계 | |
| `icons/chest.png` (궤짝) | 16 | 결과 전부 받기 | |
| `icons/lock.png` (자물쇠) | 16 | 잠긴 줄·잠긴 탭 | |
| `icons/kiln.png` (빈 화로) | 16 | 쉬는 줄 | |
| `icons/machine_<id>.png` ×7 | 24 | 기계 명패 N | 2차: 기계 장면 48×64 ×7 |

합계 1차 약 13~14장(+기계 아이콘 7).

### 코드로 만드는 것 (`hub_hanok.py`)

목록 칸 / 물건 칸 / 금테 결과 칸 / 진열장 틀 / 작업대 줄 띠 / 붓 화살표 / 탭 소켓과 탭 글자 / 마루 빈 칸 나무판 / 안내 띠 글씨 / 현판 글자 글리프(Sunflower) / 흐린 페이지 아이콘 / 탭 선택 금테 오버레이(16px, 가운데 투명).

---

## 7. 작업 순서 · 위험 · 규모

### 7-1. 단계

| 단계 | 할 일 | 파일 |
|---|---|---|
| 0. 부품 | `hub_hanok.py` 에 list / inset / gold / flow / tabs / note / 마루 빈칸 추가. 3·4·5줄에서도 지붕·기둥·인벤토리 칸 자리 확인. 미리보기(`menu_preview.py`) 시트로 눈 확인 | `tools/assetgen/hub_hanok.py`, `stsv_assets/menu_theme.py`, `menu_preview.py` |
| 1. 현판 글자 | 글리프 폰트 생성(파이썬) + `MenuHandler.plaque()`·`MenuModule.open()` | `tools/assetgen/`(새 함수), `plugin/.../gui/menu/MenuHandler.java`, `MenuModule.java` |
| 2. 그림 | PixelLab 아이콘 13장 뽑고 고르기 → `hub_art/icons`, `economy_icons.py` 에 `hanok_*` 아이템 등록 | `tools/assetgen/hub_art/icons/`, `economy_icons.py` |
| 3. 상점 | `shop.yml` 5화면 다시 쓰기, `ShopModule` 현판 3곳 | `economy/content/economy/menus/shop.yml`, `ShopModule.java` |
| 4. 기계 | `machine.yml` 2화면, `MachineModule` 아이콘·현판. 레시피 화면은 별도 기획의 탭 동작과 같이 넣거나, 탭 자리만 먼저 | `machine.yml`, `MachineModule.java`, (`machines.yml` 분류) |
| 5. 테스트·문서 | 아래 테스트 수정, `economy/README.md`, 버전 올림(`economy/build.gradle.kts`, 코어 바뀌면 `plugin/build.gradle.kts`) | |
| 6. 나머지 | 아래 순서 | |

나머지 메뉴 순서 제안 (통합 메뉴 "거래 → 일·땅 → 꾸미기" 흐름, 많이 쓰는 것 먼저):

1. **거래 묶음**: `mail` → `market_order_pick` → `market`/`market_orders`(탭) → `market_sell`/`market_order`/`auction*`(inset, 가격 버튼) → `trade`(코드 화면, 대칭 아님)
2. **일·땅 묶음**: `jobs`, `missions`, `requests`(작아서 쉬움) → `codex` → `stocks` → `stock`(차트 글꼴 좌표 다시 맞춤, 마지막)
3. **꾸미기 묶음(코어)**: `nick_colors` → `titles` → `wardrobe`·`gacha`(탭 12개: 탭 부품 검증용) → `donation*` → `tuning` → `profile`·`achievements*` → `emote_wheel`(원판 그림 따로)
4. **코드 화면**: 부두·터미널·생선 경매·쓰레기통·스킨은 `trade` 처럼 메뉴 스펙을 빌려 배경만 → 짐칸·지게는 크기가 바뀌어 마지막
5. 서킷 화면은 그대로. 관리자 화면(ItemBrowser, RecipeEditor)은 제외

### 7-2. 위험

| 위험 | 내용 | 대응 |
|---|---|---|
| 리소스팩 용량 | 한옥 배경 1장 ≈ 22~36KB (숯색 1.5KB). 상점 5 + 기계 2 ≈ +0.2MB, 나머지 33개까지 ≈ +1.1MB. 지금 콘텐츠 에셋 합 ≈ 8MB(코어 2.7 + 경제 1.6 + 플러그인 리소스 3.9) | 감당 가능. PNG 최적화(팔레트) 한 번. 기계별 그림(2차)은 +0.25MB 따로 판단 |
| 글리프 충돌 | 메뉴 글자 U+E000+n, 조각 U+E800+16n, 칸 띄우기 U+F100+4n. **메뉴 144개부터 조각이 칸 띄우기 영역과 겹침**(지금 49개) | 지금은 여유. 현판 글자는 **다른 폰트 파일**(`economy:plaque`)이라 겹치지 않음. 메뉴가 100개를 넘으면 MenuSpec 범위 재배치 |
| 256 텍셀 제한 | 넘으면 빈 네모만 보임(`ee0ab7e` 사례) | frame 30/30/20/2 고정이면 3~6줄 모두 안전. 다른 값을 쓰면 `MenuSpec.loadAll` 경고 확인 |
| 생성기 상수 | `hub_hanok.py` 의 LEFT/RIGHT/TOP/SCALE 이 하드코딩 | yml frame 과 다르면 크기 오류. 생성기가 yml frame 을 읽게 바꾸거나 다르면 예외 |
| 상태 표시 | 배경은 고정 → 선택·잠김·페이지 끝을 못 그림 | 3장 원칙: 바뀌는 건 아이템 아이콘 |
| 기계 제목 | 인벤토리를 기계마다 한 번 만듦 → 제목에 바뀌는 값 금지 | 단계는 툴팁 |
| 차트(`stock`) | 차트가 제목 글꼴로 그려져 frame 바꾸면 위치가 어긋날 수 있음 | 마지막에, 따로 확인 |

테스트 (바뀌면 고칠 곳):

| 테스트 | 영향 |
|---|---|
| `economy/src/test/.../ContentTest.menusLoadAndTheirBackgroundsMatchTheLayout` | 메뉴 수 `30`(기계별 메뉴 추가 시), 줄 대칭(기계 본 화면만 예외), 목록 칸 수 9/7, **"쓰는 칸은 그려져 있음" 검사** → 생성기가 list/inset 칸을 꼭 그려야 함 |
| `ContentTest.theRecipeBookShowsEveryRecipe` | 목록 32칸이면 페이지 버튼 유지해야 통과 |
| `economy/src/test/.../PackTest.bothPacksBuildIntoOneResourcePack` | 경제 배경 수 `30`, `gui/shop.png` 존재(이름 유지), 현판 폰트 추가 시 확인 줄 추가 |
| `economy/src/test/.../MenuActionsTest` | `everyButtonActionResolves`(새 동작 `machine:tier:*` 등록), `everyTitleFitsThePanel`(SAMPLES, 현판 글리프 폭) |
| `economy/src/test/.../job/MachineClickTest` | 칸 번호 안 바꾸면 그대로 |
| `economy/src/test/.../HubMenuTest`, 코어 `HubTest` | 통합 메뉴를 안 건드리면 그대로. 상점 첫 화면 6줄로 바뀌어도 `command:상점` 만 확인 |
| 코어 `plugin/src/test/.../gui/MenuActionsTest` (인벤토리 칸 y = 30+18줄) | 한옥 생성기가 같은 자리에 그리므로 통과해야 함 — 3·4·5줄에서 꼭 돌려 볼 것 |
| 코어 `WardrobeGuiTest`(조각 배치) | 허브 기준. 다른 줄 수용 사례 하나 더하면 좋음 |

### 7-3. 예상 규모

| 단계 | 규모 |
|---|---|
| 0 부품 | 파이썬 ≈ 250~350줄 |
| 1 현판 글자 | 파이썬 ≈ 80줄, 자바 ≈ 40줄 + 테스트 |
| 2 그림 | PixelLab 13~14장(+기계 7), 고르기 포함 반나절 |
| 3 상점 | `shop.yml` ≈ 200줄 다시 씀, 자바 ≈ 30줄, 배경 5장 |
| 4 기계 | `machine.yml` ≈ 90줄, 자바 ≈ 60줄(아이콘·현판) + 레시피 탭은 별도 기획 몫, 배경 2장 |
| 5 테스트·문서 | 테스트 5~6개 손봄 |
| 상점+기계 합 | 커밋 3~4개(부품 → 상점 → 기계 → jar) |
| 6 나머지 33개 | 묶음별 커밋, 대부분 yml + 배경. 상(trade, stock, emote_wheel) 3개는 따로 |
