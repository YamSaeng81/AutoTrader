# 계획 정비 절차서 — 다음 정비(일시 미정) · 백엔드 `895a471` · 감시 간격

> 근거: `LIVE_RISK_IMPLEMENTATION_REVIEW.md` §6.16(PAPER 매수 충돌 수정·업비트 제한기 기술 검토 완료, 다음 배포 후보 = `895a471` 한 SHA).
> 형식은 `MAINTENANCE_2026-10-23.md` 와 같다. **일시가 정해지면 제목·§0 시간대·기록 폴더 날짜만 채운다.**
> 🔴 **이번 정비에서 하지 않는 것**: 프런트엔드 · Docker·OS 패키지 정비 · STAGE_1 등록·활성화 · DB 변경. 이 절차서는 STAGE_1·실거래 시작 승인이 아니다.

## 0. 전제와 보류 조건

- 운영자가 정비 시간 동안 **직접 대응할 수 있을 때만** 한다. 대응 시간을 확보하지 못하거나 §2 직전 조회가 기대와 다르면 **연기**한다(긴급 배포가 아니다).
- 배포 대상: **`895a471`** — ① DYNAMIC PAPER 매수 충돌 수정(`69321aa`) ② 앱 업비트 요청 제한기(`89be7ac`·`9b7130d`·`895a471`, 간격 110 → 125ms) ③ 감시 작업 `freshness` 요청 간격 0.5 → 1초.
  `de3fe3a` 대비 바뀌는 파일 4개(§2 확인) · **마이그레이션 없음**(V94 그대로) · compose·Dockerfile·프런트엔드 변경 없음.
- 스키마가 그대로이므로 복구는 **앱 이미지와 체크아웃만** `de3fe3a` 로 되돌리면 된다(§5).
- 🔴 **적용 시각이 셋이다**(리뷰 2026-10-23) — 각각 기록한다(§6 개입 기록):
  | 시각 | 뜻 | 어떻게 얻나 |
  |---|---|---|
  | **체크아웃 시각** | 감시 스크립트 **파일이 교체된** 시각 | §3 `t-checkout.txt` |
  | **새 버전 첫 `freshness` 실행 시각** | 1초 간격이 **실제 적용되기 시작한** 시각 — 이미 돌고 있던 프로세스는 옛 코드로 끝까지 돈다 | §4-2 cron 기록 |
  | **새 앱 기동 시각** | 앱 제한기 125ms 적용 시각 | §3 `t-start.txt` |
- 🔴 **체크아웃은 감시 작업이 돌지 않을 때** 한다 — `freshness` 는 cron 매시 **:19** 에 시작해 약 11초(옛 코드) 돈다. 체크아웃 직전 실행 중인 프로세스가 없는지 확인한다(§3).
- 재기동 1회는 66세션에 영향을 줄 수 있다 → **개입 #11** 로 실제 시각과 내용을 남긴다(#9 = 10-23 정비, #10 = 10-23 KILL 3차).
- 🔴 **새 셸을 열 때마다 먼저** 아래 변수를 정한다:
  ```bash
  cd ~/crypto_trader/AutoTrader
  DC="docker compose -f docker-compose.prod.yml"; REPO=autotrader-backend; M=~/maint-$(date +%Y%m%d)
  mkdir -p "$M"
  ```
- 각 단계는 **실제 수행 시각**을 `date` 로 남긴다.

## 1. 정기 점검

자동 정기 점검 보고(10:00·22:00)를 평소대로 확인한다. 🔴·⚪ 가 있으면 내용을 보고 정비 진행 여부를 판단한다.

## 2. 직전 점검 — 기대와 다르면 연기

```bash
date '+%F %T %Z' | tee "$M/t-before.txt"
git fetch origin
git rev-parse --short HEAD; git status --short | grep -v '^??'      # de3fe3a · 추적 파일 변경 없음(빈 출력)
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f - > "$M/q-before.txt" 2>&1
grep -A3 '== Q1' "$M/q-before.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-before.txt" | grep -vE '^-+|^\s*$'
```
| 조회 | 기대 |
|---|---|
| HEAD | `de3fe3a`, 추적 파일 변경 없음 |
| Q1 | 94 |
| Q3·Q4·Q5·Q6 | 0 — 하나라도 아니면 **연기** |
| Q7 | RUNNING 66 |
| Q9 | 0 |
| 마지막 줄 | `ROLLBACK` |

**배포 코드 확인**(멈춤 조건):
```bash
git diff --name-only de3fe3a 895a471 -- web-api/src/main core-engine exchange-adapter/src/main strategy-lib build.gradle settings.gradle gradle docker-compose.prod.yml web-api/Dockerfile crypto-trader-frontend scripts/prospective
git diff --quiet de3fe3a 895a471 -- web-api/src/main/resources && echo 마이그레이션·설정 변경 없음
```
기대: 첫 명령은 정확히 아래 4줄, 둘째 명령은 `마이그레이션·설정 변경 없음`.
```
exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRequestLimiter.java
exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java
scripts/prospective/start_66.py
web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java
```

**기존 `de3fe3a` 이미지 보존** — 실행 중인 이미지 ID 에 직접 태그한다:
```bash
IMG=$(docker inspect --format '{{.Image}}' $($DC ps -q backend)); echo "$IMG" | tee "$M/image-de3fe3a.txt"
docker tag "$IMG" $REPO:de3fe3a
docker image inspect --format '{{.Id}} {{.Created}}' $REPO:de3fe3a      # ID 가 image-de3fe3a.txt 와 같아야 한다 · 생성 10-23 13:11 무렵
$DC logs --no-color backend > "$M/backend-before.log"; wc -l "$M/backend-before.log"
```
기대: 이미지 ID `sha256:9a458bb1…`(10-23 정비 기록). 다르면 기록하고 보고한다(되돌릴 대상은 **지금 실행 중인** 이미지다).

## 3. 체크아웃·배포 — 개입 #11 재기동

**① 감시 작업이 돌고 있지 않은지 확인하고 체크아웃**(매시 :18~:21 은 피한다):
```bash
date '+%M'                                                  # 18~21 이면 몇 분 기다린다
pgrep -af "start_66.py freshness" || echo "실행 중인 freshness 없음"
git checkout 895a471                                        # detached HEAD — 배포 버전 고정
date '+%F %T %Z' | tee "$M/t-checkout.txt"                  # 🔴 체크아웃 시각 = 감시 스크립트 파일 교체 시각
git rev-parse --short HEAD                                  # 895a471
grep -n "time.sleep(1.0)" scripts/prospective/start_66.py   # 새 감시 간격이 파일에 들어왔는지
```
`pgrep` 에 프로세스가 보이면 체크아웃하지 않고 그 프로세스가 끝날 때까지 기다린다(옛 코드로 끝까지 돈다).

**② 빌드·기동**:
```bash
date '+%F %T %Z' | tee "$M/t-build.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-build.utc"
$DC up --build -d backend                                   # backend 만
date '+%F %T %Z' | tee "$M/t-start.txt"                     # 🔴 새 앱 기동 시각 = 앱 제한기 125ms 적용 시각
docker inspect --format '{{.Image}}' $($DC ps -q backend) | tee "$M/image-895a471.txt"   # image-de3fe3a.txt 와 달라야 한다
$DC ps                                                      # frontend·db·redis 는 재시작되지 않았어야 한다
```
🔴 `up --build -d` 에 서비스 이름 `backend` 를 반드시 붙인다.

## 4. 기동·적용 확인

**4-1 기동 2~3분 뒤** — 10-23 절차서 §4 와 같은 확인:
```bash
S=$(cat "$M/t-build.utc")
$DC logs --since $S backend | grep -E "Schema .* is up to date|Migrating schema|Successfully applied|매도 격리 점검|Started CryptoAutoTrader| ERROR " | grep -v "캔들 최신성" | head -20
$DC logs --since $S backend | grep -E "결측 재수집\].*거래소에도 봉이 없다|공식 점검 공백을 결측에서 뺀다|판정 재개" | head -5
$DC logs --since 4m backend | grep -E "캔들 최신성.*판정 불가" | tail -5
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f - > "$M/q-app.txt" 2>&1
grep -A3 '== Q1' "$M/q-app.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-app.txt" | grep -vE '^-+|^\s*$'
$DC exec -T backend sh -c 'unzip -l app.jar 2>/dev/null | grep -c "UpbitRequestLimiter"'   # 1 이상 = 895a471 의 제한기 클래스가 실림
```
기대: `Schema "public" is up to date`(마이그레이션 없음) · `Started` · 격리 점검 "없음" · ERROR 는 공유 풀 미등록뿐 ·
🔴 **재확인 구간**(리뷰 2026-10-22): 시간 경과만으로 정상 판정하지 않고 결측 재조회·판정 재개 줄을 확인 · Q1 94·Q3~Q6 0·Q7 66·Q8 0·Q9 0·`ROLLBACK` · 제한기 클래스 1 이상.

**4-2 새 버전 첫 `freshness` 실행**(체크아웃 뒤 첫 :19 회차 — 그 회차가 끝난 :20 이후):
```bash
grep -E "CRON.*start_66.py freshness" /var/log/syslog | tail -3          # 🔴 체크아웃 시각 뒤 첫 줄의 시각 = 새 버전 첫 실행 시각
tail -1 scripts/prospective/freshness_log.jsonl | cut -c1-120           # 그 회차 결과(green 22 기대)
grep -h 'status=429' logs/backend/trade.log | tail -5                     # 그 :19 회차에 429 가 있었는지(있으면 그대로 보고 — 감시 유지)
```
기대: 체크아웃 시각 뒤 첫 CRON 줄이 있고 그 회차 `green 22`. 그 회차의 429 유무는 기록만 한다(완충책 효과는 이후 정기 점검으로 계속 본다).

🔴 **되돌림 사유**: 기동 실패 · 재시작 반복 · 공유 풀 미등록 외 새 ERROR · Q3~Q6 이 0 이 아님 · Q7 이 66 이 아님 · 결측 재조회·판정 재개가 확인되지 않음.
429 가 다시 보이는 것만으로는 되돌리지 않는다(감시 분류 유지 — 보고).

## 5. 복구 — 스키마 그대로(V94), 앱과 체크아웃만 `de3fe3a`

```bash
git checkout de3fe3a                                        # 🔴 감시 스크립트도 0.5초 간격으로 돌아간다 — 이 시각도 기록
date '+%F %T %Z' | tee "$M/t-rollback-checkout.txt"
docker tag "$(cat "$M/image-de3fe3a.txt")" $REPO:latest
date '+%F %T %Z' | tee "$M/t-rollback.txt"
$DC up -d --no-build --force-recreate backend
docker inspect --format '{{.Image}}' $($DC ps -q backend); cat "$M/image-de3fe3a.txt"   # 둘이 같아야 한다
```
그 뒤 §4-1 을 같은 기대로 다시 본다. 복구 시각들은 개입 #11 에 함께 적는다.

## 6. 30분 뒤 재점검과 기록

```bash
date '+%F %T %Z'
$DC ps backend
$DC logs --since 30m backend | grep " ERROR " | grep -v -E "캔들 최신성|공유 풀 한도" | tail -10
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f - > "$M/q-after30.txt" 2>&1
sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
python3 scripts/prospective/start_66.py alerts --since "$(cat "$M/t-build.utc")" --no-mark 2>&1 | tail -8
```
기대: 재시작 없음 · 새 ERROR 없음(429 는 별도 보고) · Q3~Q6 0 · Q7 66 · Q8 0 · Q9 0 · `ROLLBACK` · 66세션 경보 없음(정비 구간 평가 공백 외).

**개입 #11 기록**(운영자 본인이 시각·결과를 채운다):
> 개입 #11 — (날짜) 계획 정비(백엔드 `895a471`). `de3fe3a` 이미지 보존(ID 기록) → **체크아웃 HH:MM:SS**(감시 스크립트 파일 교체) → 빌드 HH:MM → **새 앱 기동 HH:MM:SS**(앱 제한기 125ms 적용) →
> **새 버전 첫 `freshness` 실행 HH:19**(감시 1초 간격 적용). 검증 66세션 입력 수집 간격이 바뀌었다(앱 110 → 125ms, 감시 0.5 → 1초). 재기동에 따른 평가 공백의 영향은 미확정이며 T0·성과 구간은 유지한다.
> 결과: (정상 / 복구 — 사유·복구 시각들) — 운영자 / 시각

결과 파일(`$M`)과 위 로그 줄을 보고하면 문서(§6.16)에 반영한다. 서버는 `895a471`(복구했다면 `de3fe3a`)에 고정해 두고, 무조건 `git checkout main` 하지 않는다.

## 7. 이번 정비에서 하지 않는 것

프런트엔드 빌드·배포 · Docker·OS 패키지 업그레이드·재부팅 · STAGE_1 등록·활성화 · `risk_config` 변경 · 세션 시작·정지·`invest_ratio` 변경 · 격리 해제 API 호출 ·
`cloudflared-tunnel` 정리 · 66세션·T0 변경 · DB 변경 · 정기 점검 자동화(`~/ops-scripts`, 설치 `fbd60d3`) 변경.
