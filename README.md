# STSV

스트리머 시즌 서버의 공통 기반입니다. 대상 버전은 마인크래프트 26.3(Paper), Java 25입니다.

![smelter](docs/previews/smelter.png)

## 폴더 구성

| 경로 | 내용 |
|---|---|
| `plugin/` | **STSV 코어 플러그인**. 커스텀 아이템, 가구, 레시피, 리소스팩 자동 배포, 모듈 시스템 |
| `content/stsv/` | 기본 콘텐츠 팩 (플러그인 jar에 포함되고, 첫 실행 때 서버에 설치됨) |
| `tools/assetgen/` | 3D 모델과 텍스처를 **코드로** 만드는 도구 (공통 팔레트로 일관성 유지) |
| `docs/ART_STYLE.md` | 아트 스타일 규칙 |
| `docs/CORE_DESIGN.md` | 코어 구조와 공통 기능 가안, 개발 순서 |

## 빌드

플러그인 (JDK 25 필요):
```bash
cd plugin && ./gradlew build
```
결과물: `plugin/build/libs/stsv-core-<버전>.jar` → 서버의 `plugins/` 폴더에 넣으면 됩니다.

에셋 (Python 3 + Pillow, 미리보기 렌더링은 Node + Playwright):
```bash
cd tools/assetgen && python3 build.py --render
```

전용 동작 아이템이 바닐라와 같은 구조인지 확인 (마인크래프트 클라이언트 jar에서 `assets/minecraft`를 풀어둔 경로):
```bash
python3 tools/assetgen/check_vanilla.py <경로>/assets/minecraft
```

## 업데이트

새 jar로 바꾸고 서버를 재시작하면 기본 콘텐츠(`contents/stsv`)도 자동으로 새 버전이 됩니다.
**직접 수정한 파일은 덮어쓰지 않고** 콘솔에 알려줍니다.

배포 전에 (콘텐츠 변경을 커밋한 다음) 아래를 실행해 이전 버전 기록을 갱신합니다:
```bash
python3 tools/content_history.py
```

## 서버에서 콘텐츠 추가하기

`plugins/STSV/contents/<팩 이름>/` 아래에 파일을 넣고 `/stsv reload` 하면 리소스팩까지 다시 만들어져 접속자에게 전송됩니다.

```
contents/mypack/
  pack.yml            namespace: mypack
  items/*.yml         아이템, 가구
  recipes/*.yml       레시피
  models/*.bbmodel    3D 모델 (Blockbench 파일 그대로)
  textures/*.png      2D 아이템 텍스처
  bosses/*.yml        보스 (관절 모델, 애니메이션, 패턴, 페이즈, 드롭)
  menus/*.yml         메뉴 (칸 배치, 버튼, 배경 그림) - content/stsv/menus/wardrobe.yml 참고
  titles/*.yml        칭호
  assets/...          그 밖의 리소스팩 파일 (폰트, 사운드 등) 그대로 병합
```

아이템과 레시피 작성 예시는 `content/stsv/` 의 YAML 파일을 참고하세요.

### 게임 안에서
| 명령어 | 하는 일 |
|---|---|
| `/stsv browse` | 아이템 목록 GUI (클릭해서 받기) |
| `/stsv recipe create <id> <종류> [시간] [경험치]` | 레시피 편집기. 종류: shaped, shapeless, furnace, blasting, smoking, campfire, stonecutting |
| `/stsv recipe delete <id>` / `list` | 편집기로 만든 레시피 삭제 / 목록 |
| `/stsv fuel set <틱>` / `remove` / `list` | 손에 든 아이템의 화로 연료 시간 (석탄 = 1600틱) |
| `/stsv skin remove` | 손에 든 아이템의 스킨 해제 (스킨북 반환) |
| `/stsv wardrobe create <이름> [반경]` | 지금 위치에 옷장 구역 만들기 |
| `/stsv cosmetic give\|take <플레이어> <치장>` | 치장 지급/회수 (교환권 없이 바로 보유 목록에 넣기) |
| `/옷장` | (플레이어) 옷장 구역에서 입력 → 메뉴(큰 상자, 전용 UI, 치장이 28개를 넘으면 페이지). 치장 클릭 → 메뉴가 닫히고 마네킹 미리보기(관전 카메라). 웅크리기 = 메뉴 다시 열기, 나가기 버튼 = 나가기 |
| `/stsv cosmetic info [플레이어]` | 착용 중인 치장, 권한 등 상태 확인 |
| `/stsv boss spawn <보스>` / `list` / `remove` | 바라보는 곳에 보스 소환 / 목록 / 살아있는 보스 모두 제거 |
| `/stsv boss force <패턴>` / `debug` / `spawnat <보스> <x> <y> <z> [월드]` | (테스트용) 패턴 바로 실행 / 관절 상태 출력 / 좌표에 소환(콘솔 가능) |
| `/stsv period start <id> <이름>` / `end` / `info` | 콘텐츠 기간 시작 / 종료(명예의 전당 기록) / 상태 |
| `/stsv title give\|take <플레이어> <칭호>` / `list` | 칭호 지급, 회수, 목록 |
| `/stsv stat add <플레이어> <통계> <값>` | 기록 보정 |
| `/프로필 [플레이어]` | (플레이어) 기록과 칭호. 내 칭호는 클릭해서 장착 |
| `/명예의전당 [기간]` | (플레이어) 지난 콘텐츠의 부문별 순위 |
| `/stsv reload` | 콘텐츠, 레시피, 메뉴, 칭호, 문구, 리소스팩 다시 읽기 |

### 메뉴 만들기 / 바꾸기

메뉴는 `contents/<팩>/menus/*.yml` 하나와 (선택) 배경 PNG 하나입니다. 형식 설명은 `content/stsv/menus/wardrobe.yml` 맨 위에 있습니다.
배경을 쓰면 리소스팩을 빌드할 때 폰트가 자동으로 만들어집니다(직접 폰트 파일을 쓸 필요 없음).

### 옷장 UI 바꾸기

| 파일 | 내용 |
|---|---|
| `content/stsv/menus/wardrobe.yml` | 칸 배치, 버튼, 문구, 배경 지정 |
| `content/stsv/assets/stsv/textures/gui/wardrobe.png` | 배경 그림 176×222 (큰 상자 화면 크기 그대로) |
| `content/stsv/assets/stsv/textures/item/gui/*.png` | 버튼 아이콘 16×16 (이전, 다음, 벗기, 정보, 나가기, 비활성 화살표) |

그림을 직접 그릴 때: 상자 칸 (줄 r, 열 c)의 18×18 테두리는 x = 7 + 18c, y = 17 + 18r 입니다.
`Inventory` 글자는 y = 128, 플레이어 인벤토리는 y = 139부터, 단축바는 y = 197 입니다.
칸 배치를 바꾸면 그림도 같이 바꾸세요(`WardrobeGuiTest` 가 어긋남을 잡습니다).
자동 생성은 `tools/assetgen/gui.py` (칸 위치를 wardrobe.yml 에서 읽음, 미리보기: `docs/previews/wardrobe_ui.png`).

### 보스 만들기

예시: `content/stsv/bosses/test.yml` (테스트 골렘, 입문용, `/stsv boss spawn test_golem`), `executioner.yml` (처형자, 고난도, `/stsv boss spawn executioner`). 파일 맨 위 주석에 좌표와 회전 방향 규칙이 있습니다.

- **겉모습**: 관절(bone)마다 `.bbmodel` 하나. 회전 중심(pivot)이 모델의 (8, 8, 8)에 오게 만듭니다.
  관절 하나는 중심에서 1.5블록까지 뻗을 수 있으므로, 큰 보스는 실제 관절(어깨, 팔꿈치, 엉덩이...)에서 나눕니다.
- **애니메이션**: `{ 틱: [x, y, z] }` 키프레임 (회전 = 도, 위치 = 모델 단위). `idle`, `walk` 는 자동으로 재생되고,
  `spawn`(등장), `punch`(기본 주먹), `death`(쓰러짐)가 있으면 해당 순간에 재생됩니다. 키프레임에 `sounds` 로 효과음.
- **패턴**: `slam`(앞쪽 원형 내려찍기), `charge`(직선 돌진), `throw`(손에 든 것을 포물선으로 던짐).
  모든 패턴은 바닥에 빨간 경고 표시가 먼저 나오고, 주황 원이 가장자리에 닿는 순간 맞습니다.
- **연계 패턴** `sequence`: 애니메이션 단계를 이어 붙이는 범용 패턴. 단계마다 판정(부채꼴, 원, 직선), 확률로 이어지기,
  지연(무작위로 멈췄다가 번쩍이면 공격), 이동, 도약(공중에서도 착지점이 따라옴), 잡기와 구출, 점프로 넘는 충격파,
  안전 구역 외우기(처형장)를 조합합니다. 조건부 패턴: `when: behind`(등 뒤), `above`(높은 곳으로 도망), `heal`(회복 시도).
  키 설명은 `content/stsv/bosses/executioner.yml` 의 `skills:` 위 주석에 있습니다.
- **페이즈**: 체력이 `below` 아래로 내려가면 애니메이션, 모델 교체(예: 화면이 빨갛게), 이동 속도와 패턴 주기 강화.
- **판정**: 보이지 않는 철 골렘(크기 `scale`)이 이동, 충돌, 체력을 맡습니다. 처치하면 드롭과 함께 피해량 순위가 표시됩니다.
- 보스는 저장되지 않습니다(서버 재시작, 청크 언로드 시 사라짐). 소환 위치에서 `leash` 블록 이상 끌려가면 돌아가서 체력을 회복합니다.

## 서버 설정

`plugins/STSV/config.yml` 의 `resource-pack.host.public-url` 은 **플레이어가 접속할 수 있는 주소**여야 합니다.
예: `http://서버IP:8163`. 방화벽에서 해당 포트를 열어야 합니다.
