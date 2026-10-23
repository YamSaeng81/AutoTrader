# 계획 정비 절차서 — 다음 정비(일시 미정) · 백엔드 `2eb4225` · 감시 간격

> 근거: `LIVE_RISK_IMPLEMENTATION_REVIEW.md` §6.16(PAPER 매수 충돌 수정·업비트 제한기 기술 검토 완료, 앱 코드 후보는 `895a471` — 리뷰 2차에서 감시 재조회 간격을 더해 후보 SHA 를 `2eb4225` 로 갱신).
> 🟢 **리뷰 확인(2026-10-23)**: 이번 범위 기술 검토 완료 — 후보 **`2eb4225`**, 조회·확인 도구 **`QS=d028f74`** 고정(리뷰가 감시 간격 시험 6/6·확인 도구 시험 18/18 직접 실행).
> 🔴 이 판정은 **배포 실행·STAGE_1 활성화 승인이 아니다.** 정비 일시가 정해질 때까지 운영 `de3fe3a`·STAGE_1 비활성 유지. 정비 전 `bar_audit.py` 등록·실행 확인(§0).
> 형식은 `MAINTENANCE_2026-10-23.md` 와 같다. **일시가 정해지면 제목·§0 시간대만 채운다.**
> 2026-10-23 리뷰 보완 4곳 반영: ① 제한기 클래스는 `app.jar` 안의 **중첩 모듈 jar** 까지 확인 ② 복구 뒤 확인 항목 분리 ③ 점검 조회는 **검토한 SHA** 의 것을 쓰고 Q8 기준 시각을 인자로
> ④ 첫 `freshness` 는 **프로세스 종료와 그 회차의 JSON 결과**로 판정. 기동 확인은 특정 로그 줄이 아니라 **재확인 대상마다 판정이 재개됐는지**로 본다.
> 2026-10-23 리뷰 2차 보완 4곳: ① 감시 **재조회**도 1초 간격(후보 `895a471` → `2eb4225`, 파일 이름도 바꿨다) ② 기동 뒤 로그도 **마지막 상태**로 판정 재개를 본다
> ③ `pgrep` 은 종료 코드 1 만 '없음' — 오류·도구 부재는 ⚪ 중단 ④ JSON `at` 은 **관측 시각** — 첫 실행 **시작 시각은 cron 기록**으로 적는다.
> 🔴 **이번 정비에서 하지 않는 것**: 프런트엔드 · Docker·OS 패키지 정비 · STAGE_1 등록·활성화 · DB 변경. 이 절차서는 STAGE_1·실거래 시작 승인이 아니다.

## 0. 전제와 보류 조건

- 운영자가 정비 시간 동안 **직접 대응할 수 있을 때만** 한다. 대응 시간을 확보하지 못하거나 §2 직전 조회가 기대와 다르면 **연기**한다(긴급 배포가 아니다).
- 배포 대상: **`2eb4225`** — ① DYNAMIC PAPER 매수 충돌 수정(`69321aa`) ② 앱 업비트 요청 제한기(`89be7ac`·`9b7130d`·`895a471`, 간격 110 → 125ms) ③ 감시 작업 `freshness` 요청 간격 0.5 → 1초
  (`895a471` 에는 1차 조회만 1초였다 — **재조회**까지 `2eb4225` 에서 `UPBIT_GAP_S` 하나로 맞췄다).
  `de3fe3a` 대비 바뀌는 파일 5개(§2 확인 — 감시 간격 시험 파일 포함) · **마이그레이션 없음**(V94 그대로) · compose·Dockerfile·프런트엔드 변경 없음.
- 스키마가 그대로이므로 복구는 **앱 이미지와 체크아웃만** `de3fe3a` 로 되돌리면 된다(§5).
- 🔴 **적용 시각이 셋이다**(리뷰 2026-10-23) — 각각 기록한다(§6 개입 기록):
  | 시각 | 뜻 | 어떻게 얻나 |
  |---|---|---|
  | **체크아웃 시각** | 감시 스크립트 **파일이 교체된** 시각 | §3 `t-checkout.txt` |
  | **새 버전 첫 `freshness` 실행 시각** | 1초 간격이 **실제 적용되기 시작한** 시각 — 이미 돌고 있던 프로세스는 옛 코드로 끝까지 돈다 | §4-2 **cron 기록**(시작) — JSON `at` 은 관측 시각이라 시작 시각으로 쓰지 않는다 |
  | **새 앱 기동 시각** | 앱 제한기 125ms 적용 시각 | §3 `t-start.txt` |
- 🔴 **체크아웃은 감시 작업이 돌지 않을 때** 한다 — `freshness` 는 cron 매시 **:19** 에 시작해 약 11초(옛 코드) 돈다 — 뒤처진 코인이 있으면 70초 대기와 재조회가 붙어 2분 가까이 돌 수 있다.
  체크아웃 직전 실행 중인 프로세스가 없는지 `pgrep` 으로 확인한다(§3) — 시각 창보다 `pgrep` 결과가 기준이다.
- 🔴 **정비 전 — `bar_audit.py` 등록·실행 확인**(리뷰 2026-10-23, 읽기 전용). 이 스크립트도 업비트를 따로 호출하고 요청 사이 대기가 **0.12초**다.
  저장소의 정기 점검·감시 스크립트는 이것을 부르지 않지만(사후 봉 대조용 수동 도구), **서버 등록 여부는 확인하지 않았다**:
  ```bash
  crontab -l 2>/dev/null | grep -n bar_audit; echo "사용자 crontab 종료 코드 $?"
  sudo crontab -l 2>/dev/null | grep -n bar_audit; echo "root crontab 종료 코드 $?"
  grep -rn bar_audit /etc/crontab /etc/cron.d /etc/cron.hourly /etc/cron.daily 2>/dev/null; echo "시스템 cron 종료 코드 $?"
  systemctl list-timers --all --no-pager | grep -i bar_audit; echo "timer 종료 코드 $?"
  grep -rln bar_audit /etc/systemd/system ~/.config/systemd/user 2>/dev/null; echo "systemd unit 종료 코드 $?"
  grep -rn bar_audit ~/ops-scripts 2>/dev/null; echo "ops-scripts 종료 코드 $?"
  pgrep -af bar_audit; echo "pgrep 종료 코드 $?"
  grep -h bar_audit /var/log/syslog /var/log/syslog.1 2>/dev/null | tail -5      # 최근 cron 실행 흔적
  ```
  | 결과 | 뜻 | 처리 |
  |---|---|---|
  | 모든 종료 코드 **1**, 마지막 줄 출력 없음 | 확인한 범위에서 등록·실행 흔적을 찾지 못함 | 진행 — 기록은 "확인한 범위에서 찾지 못함" 까지 |
  | 어느 하나라도 **0**(줄 출력) | 등록돼 있거나 실행 중 | **정비 전에 보고** — 요청량 분석 대상에 넣는다 |
  | **2** 등 그 밖 | 확인 실패 | ⚪ 보고 — '없음' 으로 세지 않는다 |

  🔴 `bar_audit.py` 가 돌고 있다면 현재 시험(제한기·감시 간격)이 그 요청까지 **합산해 보장한다고 쓰지 않는다** — 시험 범위는 앱 제한기와 `start_66.py freshness` 뿐이다.
  **확인 결과(2026-10-23 15:51 KST 무렵, 운영자 실행)**: 사용자·root crontab(`no crontab for root`)·시스템 cron·timer(목록 24줄 정상 출력)·`/etc/systemd/system`·ops-scripts·`pgrep` 모두 종료 코드 1,
  `~/.config/systemd/user` 폴더 없음, syslog(`syslog`·`syslog.1` = 대략 10-11~10-23, 읽기 성공) 종료 코드 1 → **확인한 범위에서 등록·실행 흔적을 찾지 못했다.**
  처음 묶음의 systemd unit 종료 코드 2 는 없는 폴더 때문이었고, root crontab 의 1 은 `sudo crontab -l` 을 따로 실행해 확인했다. 압축된 옛 syslog 는 보지 않았다.
  🔴 수동 도구라 사람이 직접 돌릴 수 있다 — 정비 중에는 돌리지 않고, 나중에 돌리면 실행 시각을 남겨 요청량 분석에 넣는다.
- 재기동 1회는 66세션에 영향을 줄 수 있다 → **개입 #11** 로 실제 시각과 내용을 남긴다(#9 = 10-23 정비, #10 = 10-23 KILL 3차).
- 🔴 **새 셸을 열 때마다 먼저** 아래 변수를 정한다:
  ```bash
  cd ~/crypto_trader/AutoTrader
  DC="docker compose -f docker-compose.prod.yml"; REPO=autotrader-backend; M=~/maint-$(date +%Y%m%d)
  QS=d028f74bbcaa2cb5c5ce8b281378167a40b805d9      # 🔴 검토한 점검 조회·확인 도구의 SHA — origin/main 을 쓰지 않는다
  mkdir -p "$M"
  qc() { git show $QS:scripts/ops/maint_checks.sql | $DC exec -T db psql -U trader -d crypto_auto_trader -v maint_start="$(cat "$M/maint_start.txt")" -f - ; }
  ```
  `qc` 는 점검 조회 실행 함수다(Q8 은 `$M/maint_start.txt` 의 정비 시작 시각 이후 실자금 신규 매수를 센다 — §2 에서 기록).
- 각 단계는 **실제 수행 시각**을 `date` 로 남긴다.

## 1. 정기 점검

자동 정기 점검 보고(10:00·22:00)를 평소대로 확인한다. 🔴·⚪ 가 있으면 내용을 보고 정비 진행 여부를 판단한다.

## 2. 직전 점검 — 기대와 다르면 연기

```bash
date '+%F %T %Z' | tee "$M/t-before.txt"; date '+%F %T%:z' > "$M/maint_start.txt"     # 🔴 정비 시작 시각 = Q8 기준
git fetch origin
git cat-file -e $QS && echo "검토 SHA 있음"
git show $QS:scripts/ops/maint_verify.py > "$M/maint_verify.py"          # 확인 도구(읽기 전용)
git rev-parse --short HEAD; git status --short | grep -v '^??'      # de3fe3a · 추적 파일 변경 없음(빈 출력)
qc > "$M/q-before.txt" 2>&1
grep -A3 '== Q1' "$M/q-before.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-before.txt" | grep -vE '^-+|^\s*$'
```
| 조회 | 기대 |
|---|---|
| HEAD | `de3fe3a`, 추적 파일 변경 없음 |
| Q1 | 94 |
| Q3·Q4·Q5·Q6 | 0 — 하나라도 아니면 **연기** |
| Q7 | RUNNING 66 |
| Q8 | 첫 줄에 기록한 정비 시작 시각 · 0 |
| Q9 | 0 |
| 마지막 줄 | `ROLLBACK` |

**배포 코드 확인**(멈춤 조건):
```bash
git diff --name-only de3fe3a 2eb4225 -- web-api/src/main core-engine exchange-adapter/src/main strategy-lib build.gradle settings.gradle gradle docker-compose.prod.yml web-api/Dockerfile crypto-trader-frontend scripts/prospective
git diff --quiet de3fe3a 2eb4225 -- web-api/src/main/resources && echo 마이그레이션·설정 변경 없음
```
기대: 첫 명령은 정확히 아래 5줄, 둘째 명령은 `마이그레이션·설정 변경 없음`.
```
exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRequestLimiter.java
exchange-adapter/src/main/java/com/cryptoautotrader/exchange/upbit/UpbitRestClient.java
scripts/prospective/start_66.py
scripts/prospective/test_freshness_spacing.py
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

**① 감시 작업이 돌고 있지 않은지 확인하고 체크아웃**(매시 :18~:22 는 피한다):
```bash
date '+%M'                                                  # 18~22 면 몇 분 기다린다
pgrep -af "start_66.py freshness"; echo "pgrep 종료 코드 $?"   # 🔴 1 = 없음(진행) · 0 = 실행 중(대기) · 그 밖 = 확인 실패(진행하지 않는다)
git checkout 2eb4225                                        # detached HEAD — 배포 버전 고정
date '+%F %T %Z' | tee "$M/t-checkout.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-checkout.utc"   # 🔴 체크아웃 시각 = 감시 스크립트 파일 교체 시각
git rev-parse --short HEAD                                  # 2eb4225
grep -n "UPBIT_GAP_S" scripts/prospective/start_66.py       # 3줄: 정의 = 1.0 · 1차 조회 · 재조회
grep -c "time.sleep(0.5)" scripts/prospective/start_66.py   # 0 — 0.5초 간격이 남아 있지 않다
```
`pgrep` 에 프로세스가 보이면(종료 코드 0) 체크아웃하지 않고 그 프로세스가 끝날 때까지 기다린다(옛 코드로 끝까지 돈다). 종료 코드가 0·1 이 아니면 실행 여부를 모르는 것이다 — 체크아웃하지 않고 보고한다.

**② 빌드·기동**:
```bash
date '+%F %T %Z' | tee "$M/t-build.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-build.utc"
$DC up --build -d backend                                   # backend 만
date '+%F %T %Z' | tee "$M/t-start.txt"                     # 🔴 새 앱 기동 시각 = 앱 제한기 125ms 적용 시각
docker inspect --format '{{.Image}}' $($DC ps -q backend) | tee "$M/image-2eb4225.txt"   # image-de3fe3a.txt 와 달라야 한다
$DC ps                                                      # frontend·db·redis 는 재시작되지 않았어야 한다
```
🔴 `up --build -d` 에 서비스 이름 `backend` 를 반드시 붙인다.

## 4. 기동·적용 확인

**4-1 공통 확인(기동 3~5분 뒤)** — §5 복구 뒤에도 같은 항목을 쓴다:
```bash
S=$(cat "$M/t-build.utc")
$DC ps backend
$DC logs --since $S backend | grep -E "Schema .* is up to date|Migrating schema|Successfully applied|매도 격리 점검|Started CryptoAutoTrader| ERROR " | grep -v "캔들 최신성" | head -20
$DC logs --no-color --since $S backend > "$M/app-after.log"
python3 "$M/maint_verify.py" candle "$M/app-after.log" "$M/backend-before.log"    # 재확인 대상마다 판정 재개 여부
qc > "$M/q-app.txt" 2>&1
grep -A3 '== Q1' "$M/q-app.txt" | head -4; sed -n '/== Q3/,$p' "$M/q-app.txt" | grep -vE '^-+|^\s*$'
```
기대: `Schema "public" is up to date`(마이그레이션 없음 — V94) · `Started` · 격리 점검 "없음" · ERROR 는 공유 풀 미등록뿐 ·
🔴 **재확인 구간**(리뷰 2026-10-22·23): 시간 경과나 특정 점검 공백 로그 줄로 정상 판정하지 않는다 — `candle` 결과로 **기동 뒤 판정 불가로 들어간 대상마다 판정이 재개됐는지** 본다.
도구는 기동 뒤 로그의 **마지막 상태**로 본다(재개됐다가 다시 판정 불가면 '재개 뒤 다시 판정 불가' 로 표시하고 실패). "아직 판정 불가" 가 남으면 그 대상이 **기동 직전에도 판정 불가**였는지(도구가 표시) 보고, 아니면 몇 분 뒤 다시 실행한다 · Q1 94·Q3~Q6 0·Q7 66·Q8 0·Q9 0·`ROLLBACK`.

**4-1b 새 이미지 확인(배포에서만 — 복구 뒤에는 하지 않는다)**: 제한기 클래스는 다른 모듈이라 `app.jar` 안의 `BOOT-INF/lib/exchange-adapter-*.jar` 에 **중첩**된다 — 바깥 목록만 보면 정상 이미지도 0 이다(리뷰).
호스트로 jar 를 복사해 중첩 jar 안까지 본다:
```bash
docker cp $($DC ps -q backend):/app/app.jar "$M/app-2eb4225.jar"
python3 "$M/maint_verify.py" jar "$M/app-2eb4225.jar" upbit/UpbitRequestLimiter.class
```
기대: `있음 upbit/UpbitRequestLimiter.class` · 위치가 `BOOT-INF/lib/exchange-adapter-….jar!/…`(로컬 bootJar 로 같은 결과 확인, `scripts/ops/test_maint_verify.py`).

**4-2 새 버전 첫 `freshness` 실행**(체크아웃 뒤 첫 회차). 🔴 **시각 두 개를 구분한다**(리뷰 2026-10-23):
- **시작 시각** = cron 기록(syslog 의 `CRON … start_66.py freshness` 줄) — 개입 기록의 '새 버전 첫 실행 시각' 은 이것이다.
- **JSON `at`** = 그 회차의 **관측 시각**(판정 직전) — 재확인이 붙으면 시작보다 70초 넘게 뒤(:20~:21)다. 시작 시각으로 쓰지 않는다.

**프로세스가 끝났고, 체크아웃 뒤 회차의 JSON 결과가 있을 때** 판정한다:
```bash
grep -E "CRON.*start_66.py freshness" /var/log/syslog | tail -3          # 체크아웃 시각 뒤 첫 줄 = 새 버전 첫 실행 '시작' 시각
python3 "$M/maint_verify.py" freshness "$(cat "$M/t-checkout.utc")" scripts/prospective/freshness_log.jsonl; echo "종료 코드 $?"
grep -h 'status=429' logs/backend/trade.log | tail -5                     # 그 회차 시작~관측 사이에 429 가 있었는지(있으면 그대로 보고 — 감시 유지)
```
`freshness` 결과 읽기: 종료 코드 2("아직 실행 중" · "실행 여부를 확인하지 못했다" · "체크아웃 뒤 회차가 아직 없다 — 마지막 줄은 이전 회차")면 **판정하지 않고** 몇 분 뒤 다시 실행한다.
종료 코드 0 이면 "✅ 체크아웃 뒤 첫 회차" 의 결과로 `green 22` 를 기대한다. 시작 시각은 위 cron 줄에서, 관측 시각은 도구 출력의 `at` 에서 각각 적는다 —
`at` 이 :19 가 아니어도(재확인) 이상이 아니다. 그 회차의 429 유무는 기록만 한다.

🔴 **되돌림 사유**: 기동 실패 · 재시작 반복 · 공유 풀 미등록 외 새 ERROR · Q3~Q6 이 0 이 아님 · Q7 이 66 이 아님 · 결측 재조회·판정 재개가 확인되지 않음.
429 가 다시 보이는 것만으로는 되돌리지 않는다(감시 분류 유지 — 보고).

## 5. 복구 — 스키마 그대로(V94), 앱과 체크아웃만 `de3fe3a`

```bash
git checkout de3fe3a                                        # 🔴 감시 스크립트도 0.5초 간격으로 돌아간다 — 이 시각도 기록
date '+%F %T %Z' | tee "$M/t-rollback-checkout.txt"
docker tag "$(cat "$M/image-de3fe3a.txt")" $REPO:latest
date '+%F %T %Z' | tee "$M/t-rollback.txt"; date -u '+%Y-%m-%dT%H:%M:%SZ' > "$M/t-rollback.utc"
$DC up -d --no-build --force-recreate backend
docker inspect --format '{{.Image}}' $($DC ps -q backend); cat "$M/image-de3fe3a.txt"   # 둘이 같아야 한다
```
**복구 뒤 확인**(배포 확인과 다르다 — 이전 이미지에는 새 제한기 클래스가 없으므로 4-1b 는 하지 않는다):
- 실행 이미지 ID 가 `image-de3fe3a.txt` 와 **같다**(위 마지막 명령) · `git rev-parse --short HEAD` = `de3fe3a`
- §4-1 공통 확인을 `S=$(cat "$M/t-rollback.utc")` 로 바꿔 다시 실행(`app-after.log` 도 복구 뒤 로그로 다시 만든다) —
  `Started` · `Schema "public" is up to date`(V94 그대로) · 판정 재개 대조 · 점검 조회(Q1 94·Q3~Q6 0·Q7 66·Q8 0·Q9 0·`ROLLBACK`)
- 감시 스크립트가 0.5초 간격으로 돌아간 시각(`t-rollback-checkout.txt`)과 그 뒤 첫 `freshness` 회차도 4-2 와 같은 방법으로 기록한다.
복구 시각들은 개입 #11 에 함께 적는다.

## 6. 30분 뒤 재점검과 기록

```bash
date '+%F %T %Z'
$DC ps backend
$DC logs --since 30m backend | grep " ERROR " | grep -v -E "캔들 최신성|공유 풀 한도" | tail -10
qc > "$M/q-after30.txt" 2>&1
sed -n '/== Q3/,$p' "$M/q-after30.txt" | grep -vE '^-+|^\s*$'
python3 scripts/prospective/start_66.py alerts --since "$(cat "$M/t-build.utc")" --no-mark 2>&1 | tail -8
```
기대: 재시작 없음 · 새 ERROR 없음(429 는 별도 보고) · Q3~Q6 0 · Q7 66 · Q8 0 · Q9 0 · `ROLLBACK` · 66세션 경보 없음(정비 구간 평가 공백 외).

**개입 #11 기록**(운영자 본인이 시각·결과를 채운다):
> 개입 #11 — (날짜) 계획 정비(백엔드 `2eb4225`). `de3fe3a` 이미지 보존(ID 기록) → **체크아웃 HH:MM:SS**(감시 스크립트 파일 교체) → 빌드 HH:MM → **새 앱 기동 HH:MM:SS**(앱 제한기 125ms 적용) →
> **새 버전 첫 `freshness` 실행 HH:19**(cron 시작 시각 · 관측 at HH:MM:SS · 감시 1초 간격 적용). 검증 66세션 입력 수집 간격이 바뀌었다(앱 110 → 125ms, 감시 0.5 → 1초). 재기동에 따른 평가 공백의 영향은 미확정이며 T0·성과 구간은 유지한다.
> 결과: (정상 / 복구 — 사유·복구 시각들) — 운영자 / 시각

결과 파일(`$M`)과 위 로그 줄을 보고하면 문서(§6.16)에 반영한다. 서버는 `2eb4225`(복구했다면 `de3fe3a`)에 고정해 두고, 무조건 `git checkout main` 하지 않는다.

## 7. 이번 정비에서 하지 않는 것

프런트엔드 빌드·배포 · Docker·OS 패키지 업그레이드·재부팅 · STAGE_1 등록·활성화 · `risk_config` 변경 · 세션 시작·정지·`invest_ratio` 변경 · 격리 해제 API 호출 ·
`cloudflared-tunnel` 정리 · 66세션·T0 변경 · DB 변경 · 정기 점검 자동화(`~/ops-scripts`, 설치 `fbd60d3`) 변경.
