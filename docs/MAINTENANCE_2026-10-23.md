# 계획 정비 절차서 — 2026-10-23(금) 13:00~15:00 KST · 백엔드만

> 근거: `LIVE_RISK_IMPLEMENTATION_REVIEW.md` §6.16(경보 보완 `c078541`·일일 보고 재시도 `de3fe3a` 기술 검토 완료, 다음 정비 = 백엔드만·프런트엔드 보류).
> 🔴 **이번 정비에서 하지 않는 것**: 프런트엔드 배포 · Docker·OS 패키지 정비 · STAGE_1 등록·활성화 · DB 변경. 이 절차서는 STAGE_1·실거래 시작 승인이 아니다.

## 0. 전제와 보류 조건

- 운영자가 13:00~15:00 동안 **직접 대응할 수 있을 때만** 한다. 대응 시간을 확보하지 못하거나 §2 직전 조회가 기대와 다르면 **연기**한다(긴급 배포가 아니다).
- 배포 대상: **백엔드만 `de3fe3a`**(경보 보완 `c078541` 포함). `39f994c` 대비 바뀌는 앱 코드는 3개 파일 — `TelegramNotificationService`·`AsyncConfig`·
  `CandleDataFreshnessScheduler`. **마이그레이션 없음**(V94 그대로) · `docker-compose.prod.yml`·`web-api/Dockerfile` 변경 없음.
- 스키마가 그대로이므로 복구는 **앱 이미지만** `39f994c` 로 되돌리면 된다(§5).
- 재기동 1회는 66세션에 영향을 줄 수 있다 → **개입 #9** 로 실제 시각과 내용을 남긴다(§6).
- 🔴 **새 셸을 열 때마다 먼저** 아래 변수를 정한다(10-21 에 빠뜨려 명령이 엉뚱하게 실행된 적이 있다):
  ```bash
  cd ~/crypto_trader/AutoTrader
  DC="docker compose -f docker-compose.prod.yml"; REPO=autotrader-backend; M=~/maint-20261023
  mkdir -p "$M"
  ```
- 각 단계는 **실제 수행 시각**을 `date` 로 남긴다.

## 1. 10:00 정기 점검

평소대로. 이상이 있으면 정비를 보류한다.

## 2. 직전 점검 (12:50 무렵) — 기대와 다르면 연기

```bash
date '+%F %T %Z' | tee "$M/t-before.txt"
git fetch origin
git rev-parse --short HEAD; git status --short | grep -v '^??'      # 39f994c · 추적 파일 변경 없음(빈 출력)
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f - > "$M/q-before.txt" 2>&1
grep -A3 '== Q1' "$M/q-before.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-before.txt" | grep -vE '^-+|^\s*$'
```
| 조회 | 기대 |
|---|---|
| HEAD | `39f994c`, 추적 파일 변경 없음 |
| Q1 | 94 |
| Q3·Q4·Q5·Q6 | 0 — 하나라도 아니면 **연기** |
| Q7 | RUNNING 66 |
| Q9 | 0 |
| 마지막 줄 | `ROLLBACK`(아니면 조회가 중간에 멈춘 것 — 연기하고 오류 줄 보고) |

**배포 코드 확인**(멈춤 조건 — 기대와 다르면 연기):
```bash
git diff --name-only 39f994c de3fe3a -- web-api/src/main core-engine exchange-adapter strategy-lib build.gradle settings.gradle gradle docker-compose.prod.yml web-api/Dockerfile
git diff --quiet 39f994c de3fe3a -- web-api/src/main/resources && echo 마이그레이션·설정 변경 없음
```
기대: 첫 명령은 정확히 아래 3줄, 둘째 명령은 `마이그레이션·설정 변경 없음`.
```
web-api/src/main/java/com/cryptoautotrader/api/config/AsyncConfig.java
web-api/src/main/java/com/cryptoautotrader/api/service/CandleDataFreshnessScheduler.java
web-api/src/main/java/com/cryptoautotrader/api/service/TelegramNotificationService.java
```

**기존 `39f994c` 이미지 보존** — 실행 중인 이미지 ID 에 직접 태그한다(`:latest` 와 같다고 가정하지 않는다):
```bash
IMG=$(docker inspect --format '{{.Image}}' $($DC ps -q backend)); echo "$IMG" | tee "$M/image-39f994c.txt"
docker tag "$IMG" $REPO:39f994c
docker image inspect --format '{{.Id}} {{.Created}}' $REPO:39f994c      # ID 가 image-39f994c.txt 와 같아야 한다 · 생성 10-21 13:56 무렵
$DC logs --no-color backend > "$M/backend-before.log"; wc -l "$M/backend-before.log"
```
기대: 이미지 ID `sha256:8b1b8c78…`(10-21 정비 기록). 다르면 그 사실을 기록하고 보고한다(되돌릴 대상은 **지금 실행 중인** 이미지다).

## 3. 배포 (13:00~) — 개입 #9 재기동

```bash
git checkout de3fe3a                                   # detached HEAD — 배포 버전 고정
git rev-parse --short HEAD                             # de3fe3a
date '+%F %T %Z' | tee "$M/t-build.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-build.utc"    # 로그 조회 기준(UTC)
$DC up --build -d backend                              # backend 만. 빌드 동안 기존 앱은 계속 돈다
date '+%F %T %Z' | tee "$M/t-start.txt"
docker inspect --format '{{.Image}}' $($DC ps -q backend) | tee "$M/image-de3fe3a.txt"   # image-39f994c.txt 와 달라야 한다
$DC ps                                                 # frontend·db·redis 는 재시작되지 않았어야 한다(CREATED·STATUS 그대로)
```
🔴 `up --build -d` 에 서비스 이름 `backend` 를 반드시 붙인다 — 빼면 frontend 도 다시 빌드된다(이번 정비 범위 밖).

## 4. 기동·알림 이력 확인 (기동 2~3분 뒤)

```bash
S=$(cat "$M/t-build.utc")                              # 빌드 시작 시각(UTC) 부터 — 셸을 새로 열어도 같다
$DC logs --since $S backend | grep -E "Schema .* is up to date|Migrating schema|Successfully applied|매도 격리 점검|Started CryptoAutoTrader| ERROR " | grep -v "캔들 최신성" | head -20
$DC logs --since $S backend | grep -E "결측 재수집\].*거래소에도 봉이 없다|공식 점검 공백을 결측에서 뺀다|판정 재개" | head -5
$DC logs --since 4m backend | grep -E "캔들 최신성.*판정 불가" | tail -5
$DC logs --since $S backend | grep -E "\[Telegram\]" | tail -10
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f - > "$M/q-app.txt" 2>&1
grep -A3 '== Q1' "$M/q-app.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-app.txt" | grep -vE '^-+|^\s*$'
```
기대:
- `Schema "public" is up to date. No migration necessary.` · `Migrating schema`·`Successfully applied` **없음**(마이그레이션 없는 배포) · `Started CryptoAutoTraderApplication` ·
  `매도 격리 점검: … 열린 실자금 포지션 없음`.
- ERROR 는 공유 풀 한도 미등록(의도) 외 없음.
- 🔴 **재확인 구간(리뷰 2026-10-22)**: 재기동 뒤 약 2~3분의 재확인 구간이 관측됐다. 시간 경과만으로 정상 판정하지 않고, 결측 재조회와 판정 재개를 확인한다. 다른 ERROR는 별도로 판단한다.
  확인: 둘째 명령에 결측 재수집·점검 공백 제외 줄이 있고, 셋째 명령(최근 4분 판정 불가)이 비거나 기존 개별 코인(CAP·DOOD 등)뿐이다.
- 조회: Q1 94 · Q3~Q6 0 · Q7 RUNNING 66 · Q8 0 · Q9 0 · 마지막 줄 `ROLLBACK`. Q10 에 `queue_rejected` 0.

**알림 이력 확인 — 운영 수신 경로**(실제 손실·주문을 만들지 않는다): 시험 메시지 한 건을 보내 휴대폰 수신과 이력을 대조한다.
```bash
TOKEN=$(grep -m1 '^API_AUTH_TOKEN=' .env | cut -d= -f2-)      # 화면에 찍지 않는다
date '+%F %T %Z'; curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/settings/telegram/test
$DC exec -T db psql -U trader -d crypto_auto_trader -c "SELECT type, success, sent_at AT TIME ZONE 'Asia/Seoul' AS kst FROM telegram_notification_log ORDER BY id DESC LIMIT 3;"
unset TOKEN
```
기대: HTTP 200 · 맨 위 줄 `TEST | t` · **휴대폰에서 "텔레그램 알림 연동 테스트" 수신**(수신 시각 기록). 🔴 이 시험은 봇·채팅방·수신의 근거일 뿐, 재시도·큐 포화 기록의
동작 확인이 아니다 — 그 기능은 실제 실패가 생길 때 이력(`success`·`[큐 포화` 머리·ERROR "최종 실패")으로 본다.

**새 코드가 실린 것 확인**(선택 — `unzip` 이 컨테이너에 없으면 건너뛴다):
```bash
$DC exec -T backend sh -c 'unzip -l app.jar 2>/dev/null | grep -c "TelegramNotificationService\$Outcome"'   # 1 이상 = de3fe3a 코드
```

🔴 **되돌림 사유**: 기동 실패 · 재시작 반복 · 공유 풀 미등록 외 새 ERROR · Q3~Q6 이 0 이 아님 · Q7 이 66 이 아님 · 결측 재조회·판정 재개가 확인되지 않음(재확인 구간이 끝나지 않음).
시험 메시지 미수신만으로는 되돌리지 않는다 — 이력 `success` 와 로그를 함께 보고 보고한다(39f994c 도 같은 전송 경로다).

## 5. 복구 (§4 되돌림 사유가 있을 때) — 스키마는 그대로(V94), 앱만 `39f994c`

```bash
git checkout 39f994c
docker tag "$(cat "$M/image-39f994c.txt")" $REPO:latest      # 보존한 이미지 ID 를 compose 가 쓰는 :latest 로
date '+%F %T %Z' | tee "$M/t-rollback.txt"
$DC up -d --no-build --force-recreate backend
docker inspect --format '{{.Image}}' $($DC ps -q backend); cat "$M/image-39f994c.txt"   # 둘이 같아야 한다
```
그 뒤 §4 의 로그·재확인 구간·조회를 같은 기대로 다시 본다. 복구는 개입 #9 에 함께 적는다.

## 6. 30분 뒤 재점검과 기록

```bash
date '+%F %T %Z'
$DC ps backend                                          # 재시작 없음
$DC logs --since 30m backend | grep " ERROR " | grep -v -E "캔들 최신성|공유 풀 한도" | tail -10
git show origin/main:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -f - > "$M/q-after30.txt" 2>&1
sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
python3 scripts/prospective/start_66.py alerts --since "$(cat "$M/t-build.utc")" 2>&1 | tail -8
```
기대: 재시작 없음 · 새 ERROR 없음 · Q3~Q6 0 · Q7 66 · Q8 0 · Q9 0 · `ROLLBACK` · 66세션 경보 없음(정비 구간 평가 공백 외).

**개입 #9 기록**(운영자 본인이 시각·결과를 채운다 — 대신 채우지 않는다):
> 개입 #9 — 2026-10-23 계획 정비(백엔드만). `39f994c` 이미지 보존(ID 기록) → `de3fe3a` 빌드 HH:MM → 기동 HH:MM KST. 재기동에 따른 전략 메모리 초기화·평가 공백의 영향은 미확정이며
> T0·성과 구간은 유지한다. 결과: (정상 / 복구 — 사유·복구 시각) — 운영자 / 시각

결과 파일(`~/maint-20261023/`)과 위 로그 줄을 보고하면 문서(§6.16)에 반영한다. 서버는 `de3fe3a`(복구했다면 `39f994c`)에 고정해 두고, 다음 작업 때 무조건 `git checkout main` 하지 않는다.

**다음 날 확인**: 10-24 04:10 KST 캔들 갱신 보고가 처음으로 새 유형으로 나간다 — 10-24 10:00 점검 Q10 에서 `CANDLE_REFRESH_REPORT` 의 `success`(04시 `CUSTOM` 은 더 생기지 않아야 한다)를 본다.

## 7. 이번 정비에서 하지 않는 것

프런트엔드 빌드·배포(리뷰: 보류 — 이력 화면은 새 유형을 원래 문자열로 보여 준다) · Docker·OS 패키지 업그레이드·재부팅 · STAGE_1 등록·활성화 · `risk_config` 변경 ·
세션 시작·정지·`invest_ratio` 변경 · 격리 해제 API 호출 · `cloudflared-tunnel` 정리 · 66세션·T0 변경 · DB 변경.
