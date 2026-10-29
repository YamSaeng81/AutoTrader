# 계획 정비 절차서 — 백엔드 `98aaac4` (#5 정지 끝 정리 대기 등록) · 일시는 운영자 확정

> 근거: `LIVE_READINESS.md` §3-7·#5. **리뷰 30차(2026-10-29)**: `c120d62` 의 구현·시험은 기술 검토 완료. 배포 범위는 **#5 수정만**으로 동의.
> 배포 후보: **`98aaac49d5b811a9679e59e863cf546dca4a495f`** (브랜치 `deploy/5-stop-cleanup`)
> - 만든 방법: `2eb4225` 위에 운영 코드 세 파일과 회귀시험 한 개를 `c120d62` 판 그대로 올렸다.
> - 들어간 것: `LiveTradingService` · `DynamicTradingService` · `PositionRepository` · `StopVersusInFlightExitRaceTest`.
> - 빠진 것: **V95 · 세션 슬리피지 · WF·백테스트.**
>
> 🔴 **이 절차서는 배포 후보 준비까지다.** 배포 실행·STAGE_1 활성화·실거래 시작의 승인이 아니다. 정비 일시가 정해질 때까지 운영은 `2eb4225`, STAGE_1 은 비활성이다.
> 형식은 `MAINTENANCE_NEXT_2eb4225.md`(개입 #12, 10-27 수행)와 같다.
>
> **지난 정비와 다른 점**
> - 감시 스크립트(`scripts/`)가 바뀌지 않는다. 그래서 `freshness` 회차를 피할 필요가 없고, 4-2 첫 회차 확인도 하지 않는다.
> - 바뀐 클래스는 모두 `web-api` 모듈이라 `app.jar` 의 `BOOT-INF/classes` 에 바로 들어간다(중첩 jar 가 아니다).
> - 새 클래스는 없다. 새 이미지 확인은 **새 저장소 메서드 이름이 클래스 파일에 있는지**로 한다(§4-1b).
>
> 🔴 **이번 정비에서 하지 않는 것**: 프런트엔드 · Docker·OS 패키지 정비 · STAGE_1 등록·활성화 · DB 변경(마이그레이션 없음, V94 그대로).

## 0. 전제와 보류 조건

- **일시**: 🔴 운영자가 정비 시간 내내 **직접 대응할 수 있을 때** 확정한다. 긴급 배포가 아니다.
  - 정기 점검 자동 보고(10:00·22:00)를 확인한 뒤 시작하기를 권한다.
  - §2 직전 조회가 기대와 다르거나, 대응 시간을 확보하지 못하면 **연기**한다.
- **배포 대상 `98aaac4` — 바뀌는 것**
  - 정지(수동·비상) 청산이 끝날 때 닫히지 않은 포지션을 한 SQL(`status IN ('OPEN','CLOSING')`)로 읽는다. 읽은 포지션은 모두 정리 대기에 등록한다(LIVE·DYNAMIC).
  - DYNAMIC 의 등록 종류는 포지션 자신의 종류(`DYNAMIC`·`DYN_PAPER`)를 쓴다.
- **배포 대상 `98aaac4` — `2eb4225` 와 같은 것**
  - 마이그레이션 없음(V94 그대로).
  - compose·Dockerfile·프런트엔드·감시 스크립트 변경 없음.
- **실제로 이 경로를 확인할 수 있는가**
  - 지금 운영에는 실자금 보유가 0 이다(Q3). 그래서 배포 뒤 바뀐 경로가 실제로 돌지는 않는다.
  - 운영 적용 확인은 다음 셋으로 한다: 새 코드가 실행 중인지(§4-1b), 기동이 정상인지, 점검 조회 결과.
- **복구**: 스키마가 그대로이므로 **앱 이미지와 체크아웃만** `2eb4225` 로 되돌리면 된다(§5).
- **66세션 영향**: 재기동 1회는 66세션에 영향을 줄 수 있다. **개입 #13** 으로 실제 시각과 내용을 남긴다(#12 = 10-27 `2eb4225` 정비).
- 🔴 **새 셸을 열 때마다 먼저** 아래 변수를 정한다.
  ```bash
  cd ~/crypto_trader/AutoTrader
  DC="docker compose -f docker-compose.prod.yml"; REPO=autotrader-backend; M=~/maint-$(date +%Y%m%d)
  NEW=98aaac49d5b811a9679e59e863cf546dca4a495f   # 🔴 배포 후보 — 브랜치 이름이 아니라 고정 SHA 로 체크아웃한다
  QS=fa0691e647b61f6df4a85bdf796996f50470185e    # 🔴 점검 조회·확인 도구 — 10-27 리뷰 반영판(정비 #12 에서 쓴 것), origin/main 을 쓰지 않는다
  mkdir -p "$M"
  qc() { git show $QS:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -v maint_start="$(cat "$M/maint_start.txt")" -f - ; }
  ```
  - `qc` 는 점검 조회를 실행하는 함수다.
  - Q8 은 `$M/maint_start.txt` 의 정비 시작 시각 뒤에 생긴 실자금 신규 매수를 센다. 이 시각은 §2 에서 기록한다.
- 각 단계의 **실제 수행 시각**을 `date` 로 남긴다.

## 1. 정기 점검

- 자동 정기 점검 보고를 평소대로 확인한다.
- 🔴·⚪ 가 있으면 내용을 보고 정비 진행 여부를 판단한다.

## 2. 직전 점검 — 기대와 다르면 연기

```bash
date '+%F %T %Z' | tee "$M/t-before.txt"; date '+%F %T%:z' > "$M/maint_start.txt"     # 🔴 정비 시작 시각 = Q8 기준
git fetch origin
git cat-file -e $NEW && echo "배포 후보 있음"
git cat-file -e $QS && echo "검토 SHA 있음"
git show $QS:scripts/ops/maint_verify.py > "$M/maint_verify.py"          # 확인 도구(읽기 전용)
git rev-parse --short HEAD; git status --short | grep -v '^??'      # 2eb4225 · 추적 파일 변경 없음(빈 출력)
qc > "$M/q-before.txt" 2>&1
grep -A3 '== Q1' "$M/q-before.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-before.txt" | grep -vE '^-+|^\s*$'
```
| 조회 | 기대 |
|---|---|
| `git cat-file` | `배포 후보 있음` · `검토 SHA 있음` |
| HEAD | `2eb4225`, 추적 파일 변경 없음 |
| Q1 | 94 |
| Q3·Q4·Q5·Q6 | 0 — 하나라도 아니면 **연기** |
| Q7 | RUNNING 66 |
| Q8 | 첫 줄에 기록한 정비 시작 시각 · 0 |
| Q9 | 0 |
| 마지막 줄 | `ROLLBACK` |

**배포 코드 확인**(기대와 다르면 멈춘다):
```bash
git diff --name-only 2eb4225 $NEW
git diff --quiet 2eb4225 $NEW -- web-api/src/main/resources scripts docker-compose.prod.yml web-api/Dockerfile crypto-trader-frontend && echo 마이그레이션·설정·스크립트 변경 없음
git log --oneline 2eb4225..$NEW                                  # 커밋 1개
```
기대:
- 첫 명령은 정확히 아래 4줄이다(마지막 줄은 시험 파일이라 이미지에 들어가지 않는다).
- 둘째 명령은 `마이그레이션·설정·스크립트 변경 없음` 을 출력한다.
- 셋째 명령은 `98aaac4 배포 후보 #5 …` 한 줄이다.
```
web-api/src/main/java/com/cryptoautotrader/api/repository/PositionRepository.java
web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java
web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java
web-api/src/test/java/com/cryptoautotrader/api/service/StopVersusInFlightExitRaceTest.java
```

**기존 `2eb4225` 이미지 보존** — 실행 중인 이미지 ID 에 직접 태그한다.
```bash
IMG=$(docker inspect --format '{{.Image}}' $($DC ps -q backend)); echo "$IMG" | tee "$M/image-2eb4225.txt"
docker tag "$IMG" $REPO:2eb4225
docker image inspect --format '{{.Id}} {{.Created}}' $REPO:2eb4225      # ID 가 image-2eb4225.txt 와 같아야 한다
$DC logs --no-color backend > "$M/backend-before.log"; wc -l "$M/backend-before.log"
```
- 기대 이미지 ID는 `sha256:7516eaf6…` 이다(개입 #12 의 `2eb4225` 빌드).
- 다르면 기록하고 보고한다. 그 뒤 서버 재기동이 있었어도 되돌릴 대상은 **지금 실행 중인** 이미지다.

## 3. 체크아웃·배포 — 개입 #13 재기동

```bash
git checkout $NEW                                           # detached HEAD — 배포 버전 고정
date '+%F %T %Z' | tee "$M/t-checkout.txt"
git rev-parse --short HEAD                                  # 98aaac4
date '+%F %T %Z' | tee "$M/t-build.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-build.utc"
$DC up --build -d backend                                   # backend 만
date '+%F %T %Z' | tee "$M/t-start.txt"                     # 🔴 새 앱 기동 시각
docker inspect --format '{{.Image}}' $($DC ps -q backend) | tee "$M/image-98aaac4.txt"   # image-2eb4225.txt 와 달라야 한다
$DC ps                                                      # frontend·db·redis 는 재시작되지 않았어야 한다
```
- 🔴 `up --build -d` 에 서비스 이름 `backend` 를 반드시 붙인다.
- 감시 스크립트는 `2eb4225` 와 같다. 그래서 체크아웃이 `freshness` 회차와 겹쳐도 바뀌는 파일이 없다.

## 4. 기동·적용 확인

**4-1 공통 확인(기동 3~5분 뒤)** — §5 복구 뒤에도 같은 항목을 쓴다.
```bash
S=$(cat "$M/t-build.utc")
$DC ps backend
$DC logs --since $S backend | grep -E "Schema .* is up to date|Migrating schema|Successfully applied|매도 격리 점검|Started CryptoAutoTrader| ERROR " | grep -v "캔들 최신성" | head -20
$DC logs --no-color --since $S backend > "$M/app-after.log"
python3 "$M/maint_verify.py" candle "$M/app-after.log" "$M/backend-before.log"    # 재확인 대상마다 판정 재개 여부
qc > "$M/q-app.txt" 2>&1
grep -A3 '== Q1' "$M/q-app.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-app.txt" | grep -vE '^-+|^\s*$'
```
기대:
- `Schema "public" is up to date` — 마이그레이션 없음, V94 그대로.
- `Started` 가 보인다.
- 격리 점검 결과가 "없음" 이다.
- ERROR 는 공유 풀 미등록뿐이다.
- `candle` 결과에서 기동 뒤 판정 불가로 들어간 대상마다 **판정이 재개됐다.**
  - "아직 판정 불가" 가 남으면, 그 대상이 기동 직전에도 판정 불가였는지 본다(도구가 표시한다).
  - 직전에는 판정 가능했다면 몇 분 뒤 다시 실행한다.
- 점검 조회: Q1 94 · Q3~Q6 0 · Q7 66 · Q8 0 · Q9 0 · `ROLLBACK`.

**4-1b 새 이미지 확인(배포에서만 — 복구 뒤에는 하지 않는다)**
- 새 메서드 이름 `findBySessionKindAndSessionIdAndStatusIn` 이 있는지 본다. 이 이름은 `2eb4225` 에는 없고 `98aaac4` 에만 있다.
- 볼 곳은 두 군데다: 저장소 인터페이스, 그리고 그것을 부르는 LIVE·DYNAMIC 서비스 클래스.
```bash
docker cp $($DC ps -q backend):/app/app.jar "$M/app-98aaac4.jar"
python3 - "$M/app-98aaac4.jar" <<'EOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); key = b"findBySessionKindAndSessionIdAndStatusIn"
for c in ("repository/PositionRepository.class", "service/LiveTradingService.class", "service/DynamicTradingService.class"):
    p = "BOOT-INF/classes/com/cryptoautotrader/api/" + c
    print("있음" if key in z.read(p) else "없음", p)
EOF
```
- 기대: 세 줄 모두 `있음` 이다. 로컬에서 `98aaac4` 로 만든 `bootJar` 로 같은 결과를 확인했다. `2eb4225` 소스에는 이 이름이 없다.
- 하나라도 `없음` 이거나 파일을 못 찾는 오류가 나면, 새 코드가 들어가지 않은 것이다. §5 로 되돌리고 보고한다.

🔴 **되돌림 사유**
- 기동 실패, 또는 재시작 반복.
- 공유 풀 미등록 외의 새 ERROR.
- Q3~Q6 이 0 이 아니거나, Q7 이 66 이 아님.
- 판정 재개가 확인되지 않음.
- 4-1b 에 `없음` 이 있음.

## 5. 복구 — 스키마 그대로(V94), 앱과 체크아웃만 `2eb4225`

```bash
git checkout 2eb4225
date '+%F %T %Z' | tee "$M/t-rollback-checkout.txt"
docker tag "$(cat "$M/image-2eb4225.txt")" $REPO:latest
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
$DC up -d --no-build --force-recreate backend
docker inspect --format '{{.Image}}' $($DC ps -q backend); cat "$M/image-2eb4225.txt"   # 둘이 같아야 한다
```
**복구 뒤 확인** — 4-1b 는 하지 않는다. 이전 이미지에는 새 메서드가 없기 때문이다.
- 실행 이미지 ID 가 `image-2eb4225.txt` 와 **같다**(위 마지막 명령).
- `git rev-parse --short HEAD` 가 `2eb4225` 다.
- §4-1 공통 확인을 `S=$(cat "$M/t-rollback.utc")` 로 바꿔 다시 실행한다. `app-after.log` 도 복구 뒤 로그로 다시 만든다.
- 복구 시각들은 개입 #13 에 함께 적는다.

## 6. 30분 뒤 재점검과 기록

```bash
date '+%F %T %Z'
$DC ps backend
$DC logs --since 30m backend | grep " ERROR " | grep -v -E "캔들 최신성|공유 풀 한도" | tail -10
qc > "$M/q-after30.txt" 2>&1
sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
python3 scripts/prospective/start_66.py alerts --since "$(cat "$M/t-build.utc")" --no-mark 2>&1 | tail -8
```
기대:
- 재시작 없음.
- 새 ERROR 없음. 429 는 별도로 보고한다.
- Q3~Q6 0 · Q7 66 · Q8 0 · Q9 0 · `ROLLBACK`.
- 66세션 경보 없음. 정비 구간의 평가 공백은 예외다.

**개입 #13 기록**(운영자 본인이 시각·결과를 채운다):
> 개입 #13 — (날짜) 계획 정비(백엔드 `98aaac4`, #5 정지 끝 정리 대기 등록).
> 순서: `2eb4225` 이미지 보존(ID 기록) → 체크아웃 HH:MM:SS → 빌드 HH:MM → **새 앱 기동 HH:MM:SS**. 4-1b 는 세 줄 모두 `있음`.
> 66세션의 입력 수집과 판정 규칙은 바뀌지 않았다. 바뀐 것은 실자금 세션 정지 경로뿐이다. 재기동에 따른 평가 공백의 영향은 미확정이며, T0·성과 구간은 유지한다.
> 결과: (정상 / 복구 — 사유·복구 시각들) — 운영자 / 시각

- 결과 파일(`$M`)과 위 로그 줄을 보고하면 문서(`LIVE_READINESS.md` #5)에 반영하고, #5 를 닫을지 리뷰에 판단을 요청한다.
- 서버는 `98aaac4`(복구했다면 `2eb4225`)에 고정해 둔다. 무조건 `git checkout main` 하지 않는다. `main` 에는 V95 가 있다.

## 7. 이번 정비에서 하지 않는 것

- V95·세션 슬리피지·WF 배포.
- 프런트엔드 빌드·배포.
- Docker·OS 패키지 업그레이드, 재부팅.
- STAGE_1 등록·활성화, `risk_config` 변경.
- 세션 시작·정지, `invest_ratio` 변경.
- 격리 해제 API 호출.
- 66세션·T0 변경, DB 변경.
- 정기 점검 자동화(`~/ops-scripts`) 변경.
