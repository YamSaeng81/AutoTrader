# 계획 정비 절차서 — 백엔드 `98aaac4` (#5 정지 끝 정리 대기 등록) · 일시는 운영자 확정

> 근거: `LIVE_READINESS.md` §3-7·#5. **리뷰 30차(2026-10-29)**: `c120d62` 의 구현·시험은 기술 검토 완료. 배포 범위는 **#5 수정만**으로 동의.
> 배포 후보: **`98aaac49d5b811a9679e59e863cf546dca4a495f`** (브랜치 `deploy/5-stop-cleanup`)
> - 만든 방법: `2eb4225` 위에 운영 코드 세 파일과 회귀시험 한 개를 `c120d62` 판 그대로 올렸다.
> - 들어간 것: `LiveTradingService` · `DynamicTradingService` · `PositionRepository` · `StopVersusInFlightExitRaceTest`.
> - 빠진 것: **V95 · 세션 슬리피지 · WF·백테스트.**
>
> 🔴 **이 절차서는 배포 후보 준비까지다.** 배포 실행·STAGE_1 활성화·실거래 시작의 승인이 아니다. 정비 일시가 정해질 때까지 운영은 `2eb4225`, STAGE_1 은 비활성이다.
> 형식은 `MAINTENANCE_NEXT_2eb4225.md`(개입 #12, 10-27 수행)와 같다.
> **리뷰 31차 보완(2026-10-29)**
> - ① 검증 세션 기대값을 개수가 아니라 **ID별**로 본다: 원래 66개 유지, 576·577·578 만 STOPPED, 나머지 63 RUNNING. 직전·기동 후·복구·30분 점검 모두 같다.
> - ② 30분 경보 확인에서 기존 BLAST 반복 경보와 새 경보·⚪·실행 누락을 가른다.
> - ③ 배포·복구 모두 `--no-deps` 로 backend 만 재생성하고, db·redis·db-backup·frontend 의 이미지·기동 시각을 전후 대조한다.
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
- **검증 세션의 현재 상태**: 원래 66개(`paper_trading.virtual_balance` id 522~587)는 그대로다. 그중 **576·577·578(BLAST, 10-27 정지)만 STOPPED**, 나머지 63개가 RUNNING 이다.
  - 그래서 점검 조회 Q7 은 `RUNNING 63 · STOPPED 3` 이 정상이다.
  - 🔴 Q7 은 개수만 보므로, **어느 세션이 멈췄는지는 `sc` 로 ID별로 확인한다.**
- **66세션 영향**: 재기동 1회는 66세션에 영향을 줄 수 있다. **개입 #13** 으로 실제 시각과 내용을 남긴다(#12 = 10-27 `2eb4225` 정비).
- 🔴 **새 셸을 열 때마다 먼저** 아래 변수를 정한다.
  ```bash
  cd ~/crypto_trader/AutoTrader
  DC="docker compose -f docker-compose.prod.yml"; REPO=autotrader-backend; M=~/maint-$(date +%Y%m%d)
  NEW=98aaac49d5b811a9679e59e863cf546dca4a495f   # 🔴 배포 후보 — 브랜치 이름이 아니라 고정 SHA 로 체크아웃한다
  QS=fa0691e647b61f6df4a85bdf796996f50470185e    # 🔴 점검 조회·확인 도구 — 10-27 리뷰 반영판(정비 #12 에서 쓴 것), origin/main 을 쓰지 않는다
  mkdir -p "$M"
  qc() { git show $QS:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -v maint_start="$(cat "$M/maint_start.txt")" -f - ; }
  sc() { $DC exec -T db psql -U trader -d crypto_auto_trader -At -F ' | ' -c "
    SELECT count(*) AS total,
           count(*) FILTER (WHERE status = 'RUNNING' AND id NOT IN (576,577,578)) AS running_63,
           count(*) FILTER (WHERE status = 'STOPPED' AND id IN (576,577,578))     AS stopped_3,
           coalesce(string_agg(id || ':' || status, ' ' ORDER BY id) FILTER (WHERE NOT (
               (id IN (576,577,578) AND status = 'STOPPED') OR
               (id NOT IN (576,577,578) AND status = 'RUNNING'))), '-') AS mismatch
      FROM paper_trading.virtual_balance WHERE id BETWEEN 522 AND 587" ; }
  svc() { for s in backend db redis db-backup frontend; do docker inspect --format "$s {{.Image}} {{.State.StartedAt}}" "$($DC ps -q $s)"; done ; }
  ```
  - `qc` 는 점검 조회를 실행하는 함수다.
  - `sc` 는 검증 세션을 **ID별로** 확인한다(읽기 전용). 기대 출력은 정확히 `66 | 63 | 3 | -` 이다.
    - `mismatch` 에는 기대와 다른 세션이 `id:상태` 로 찍힌다. `-` 가 아니면 그 ID 를 기록한다.
    - 개수가 맞아도 `mismatch` 가 `-` 가 아니면 기대와 다르다(예: 576 이 RUNNING 이 되고 다른 세션 하나가 STOPPED 가 된 경우).
  - `svc` 는 서비스별 이미지·기동 시각을 찍는다. backend 외의 줄은 배포·복구 전후로 같아야 한다.
    - `db-backup` 은 `~/crypto_trader/backup_keep/` 의 compose 로 적용된 컨테이너다(리뷰 25·26차). 프로젝트가 같아서 `$DC ps -q db-backup` 으로 찾는다.
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
sc | tee "$M/s-before.txt"                                          # 66 | 63 | 3 | -
svc | tee "$M/svc-before.txt"                                       # 서비스별 이미지·기동 시각
python3 scripts/prospective/start_66.py alerts --days 1 --no-mark > "$M/alerts-before.txt" 2>&1; grep -E '^(🔴|⚪)' "$M/alerts-before.txt"
```
| 조회 | 기대 |
|---|---|
| `git cat-file` | `배포 후보 있음` · `검토 SHA 있음` |
| HEAD | `2eb4225`, 추적 파일 변경 없음 |
| Q1 | 94 |
| Q3·Q4·Q5·Q6 | 0 — 하나라도 아니면 **연기** |
| Q7 | `RUNNING 63` · `STOPPED 3` (다른 상태 줄 없음) |
| Q8 | 첫 줄에 기록한 정비 시작 시각 · 0 |
| Q9 | 0 |
| 마지막 줄 | `ROLLBACK` |
| `sc` | 정확히 `66 \| 63 \| 3 \| -`. 다르면 **연기**하고 `mismatch` 의 ID 를 보고한다 |
| `svc` | 다섯 줄(backend·db·redis·db-backup·frontend). 빈 ID 로 오류가 나면 그 서비스 이름을 확인하고 보고한다 |
| 지난 1일 경보 | 기준선으로 기록만 한다. 🔴 줄은 지금까지 **`KRW-BLAST` 한 종**이었다(상장 폐지, 매 회차 반복). 다른 코인의 🔴·⚪·실행 누락이 있으면 내용을 보고 진행 여부를 판단한다 |

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
$DC up --build -d --no-deps backend                         # 🔴 backend 만 — 의존 서비스(db·redis)는 손대지 않는다
date '+%F %T %Z' | tee "$M/t-start.txt"                     # 🔴 새 앱 기동 시각
docker inspect --format '{{.Image}}' $($DC ps -q backend) | tee "$M/image-98aaac4.txt"   # image-2eb4225.txt 와 달라야 한다
svc | tee "$M/svc-deploy.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-deploy.txt") && echo "backend 외 이미지·기동 시각 그대로"
```
- 🔴 `up --build -d` 에 **`--no-deps` 와 서비스 이름 `backend` 를 둘 다** 붙인다.
  - 서비스 이름만 주면 compose 는 그 서비스의 의존 서비스(db·redis)도 처리 대상에 넣는다. 그래서 설정이 달라 보이면 그것들도 다시 만들 수 있다.
  - `--no-deps` 를 주면 연결된 서비스를 시작하지 않는다([Docker 문서 `docker compose up`](https://docs.docker.com/reference/cli/docker/compose/up/)).
- 기대: 마지막 명령이 `backend 외 이미지·기동 시각 그대로` 를 출력한다.
  - diff 가 나오면 어느 서비스가 다시 만들어졌는지 기록하고 보고한다. 되돌림 사유는 아니고, 원인을 확인할 대상이다.
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
- 점검 조회: Q1 94 · Q3~Q6 0 · Q7 `RUNNING 63`·`STOPPED 3` · Q8 0 · Q9 0 · `ROLLBACK`.
- 검증 세션 ID별: 아래 명령이 정확히 `66 | 63 | 3 | -` 이고, 직전 기록과 같다.
  ```bash
  sc | tee "$M/s-app.txt"; diff "$M/s-before.txt" "$M/s-app.txt" && echo "검증 세션 ID별 상태 직전과 같음"
  ```

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
- Q3~Q6 이 0 이 아님.
- `sc` 가 `66 | 63 | 3 | -` 가 아님. 특히 576·577·578 외의 세션이 RUNNING 이 아니거나, 576·577·578 중 하나가 다시 RUNNING 이 됨.
- 판정 재개가 확인되지 않음.
- 4-1b 에 `없음` 이 있음.

## 5. 복구 — 스키마 그대로(V94), 앱과 체크아웃만 `2eb4225`

```bash
git checkout 2eb4225
date '+%F %T %Z' | tee "$M/t-rollback-checkout.txt"
docker tag "$(cat "$M/image-2eb4225.txt")" $REPO:latest
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
$DC up -d --no-build --no-deps --force-recreate backend      # 🔴 backend 만 — 의존 서비스는 손대지 않는다
docker inspect --format '{{.Image}}' $($DC ps -q backend); cat "$M/image-2eb4225.txt"   # 둘이 같아야 한다
svc | tee "$M/svc-rollback.txt"
diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-rollback.txt") && echo "backend 외 이미지·기동 시각 그대로"
```
**복구 뒤 확인** — 4-1b 는 하지 않는다. 이전 이미지에는 새 메서드가 없기 때문이다.
- 실행 이미지 ID 가 `image-2eb4225.txt` 와 **같다**(위 마지막 명령).
- `git rev-parse --short HEAD` 가 `2eb4225` 다.
- `backend 외 이미지·기동 시각 그대로` 가 출력된다.
- §4-1 공통 확인을 `S=$(cat "$M/t-rollback.utc")` 로 바꿔 다시 실행한다. `app-after.log` 도 복구 뒤 로그로 다시 만든다.
  - 그 안의 `sc` 도 다시 실행해 `66 | 63 | 3 | -` 이고 `s-before.txt` 와 같은지 본다(저장 파일 이름은 `s-rollback.txt`).
- 복구 시각들은 개입 #13 에 함께 적는다.

## 6. 30분 뒤 재점검과 기록

```bash
date '+%F %T %Z'
$DC ps backend
$DC logs --since 30m backend | grep " ERROR " | grep -v -E "캔들 최신성|공유 풀 한도" | tail -10
qc > "$M/q-after30.txt" 2>&1
sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
sc | tee "$M/s-after30.txt"; diff "$M/s-before.txt" "$M/s-after30.txt" && echo "검증 세션 ID별 상태 직전과 같음"
svc | tee "$M/svc-after30.txt"; diff <(grep -v '^backend ' "$M/svc-before.txt") <(grep -v '^backend ' "$M/svc-after30.txt") && echo "backend 외 이미지·기동 시각 그대로"
python3 scripts/prospective/start_66.py alerts --since "$(cat "$M/t-build.utc")" --no-mark > "$M/alerts-after30.txt" 2>&1
cat "$M/alerts-after30.txt"                                 # 🔴 tail 로 자르지 않는다 — 중간 줄이 가려진다
```
기대:
- 재시작 없음.
- 새 ERROR 없음. 429 는 별도로 보고한다.
- Q3~Q6 0 · Q7 `RUNNING 63`·`STOPPED 3` · Q8 0 · Q9 0 · `ROLLBACK`.
- `sc` 가 `66 | 63 | 3 | -` 이고 직전과 같다.
- backend 외 서비스의 이미지·기동 시각이 직전과 같다.
- **경보는 네 가지로 갈라 적는다.** `alerts` 의 마지막 요약 줄(`🔴 적재 뒤처짐 N회 / …`)은 기존 경보도 함께 세므로, 그 줄만 보고 판단하지 않는다.

  | 구분 | 알아보는 법 | 처리 |
  |---|---|---|
  | 기존 반복 경보 | `🔴 <시각>  1종: KRW-BLAST` — 직전 기준선(`alerts-before.txt`)과 같은 모양 | **기록만** 한다(회차 수·시각). 되돌림 사유가 아니다 |
  | 새 경보 | `🔴 <시각>` 줄에 **BLAST 외 코인**이 있거나, 종 수가 1 보다 크다 | 코인·시각을 기록하고 보고한다. 분봉·같은 시각 수집 로그로 오판인지 본 뒤 판단한다 |
  | ⚪ 판정 불가 | `⚪ <시각>  N종: …` 줄 | 코인·시각을 기록하고 보고한다. BLAST 가 🔴 대신 ⚪ 로 나와도 모양이 바뀐 것이므로 적는다 |
  | 실행 누락 | `🔴 실행 누락 A → B  N분` 줄 | 정비 구간과 겹치는지 시각으로 확인하고 기록한다. 정비와 무관한 누락이면 보고한다 |

  - 30분 구간에는 `freshness` 회차(매시 :19)가 0~1회 들어간다.
  - 0회면 `⚪ 이 구간에 관측이 없다` 가 나온다. 아직 확인하지 않았다는 뜻이므로, 다음 :19 회차 뒤 같은 명령을 다시 돌린다.

**개입 #13 기록**(운영자 본인이 시각·결과를 채운다):
> 개입 #13 — (날짜) 계획 정비(백엔드 `98aaac4`, #5 정지 끝 정리 대기 등록).
> 순서: `2eb4225` 이미지 보존(ID 기록) → 체크아웃 HH:MM:SS → 빌드 HH:MM → **새 앱 기동 HH:MM:SS**. 4-1b 는 세 줄 모두 `있음`.
> 검증 세션: `sc` 가 직전·기동 후·30분 모두 `66 | 63 | 3 | -`(576·577·578 만 STOPPED). backend 외 서비스의 이미지·기동 시각은 그대로.
> 경보: 기존 BLAST 반복 N회(시각) / 새 경보 (없음·내용) / ⚪ (없음·내용) / 실행 누락 (없음·내용).
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
