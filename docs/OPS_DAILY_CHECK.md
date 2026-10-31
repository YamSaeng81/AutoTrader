# 정기 점검 자동화 (10:00·22:00 KST)

> 2026-10-22 신설(리뷰 지시 — 정기 점검 자동화만, 배포 스크립트 제외). 근거·시험 기록: `LIVE_RISK_IMPLEMENTATION_REVIEW.md` §6.16.
> 🔴 자동화는 **수집·비교·보고를 대신**할 뿐이다. 이미 수락한 운영자의 **결과 확인과 정지 집행 책임은 그대로**다.
> 🔴 운영 설치는 시험 결과를 리뷰가 확인한 뒤에 한다(§3).

## 1. 무엇이 바뀌나

| 전 | 후 |
|---|---|
| 운영자가 10:00·22:00 에 명령을 붙여 넣고 출력을 보낸다 | 서버 cron 이 `scripts/ops/daily_check.sh` 를 돌려 결과를 텔레그램으로 보낸다 |
| 사람이 출력을 읽고 판정 | 스크립트가 기대값과 비교해 🟢/🔴/⚪ 로 요약, 전체는 서버 파일에 남긴다 |
| — | 운영자는 **보고가 왔는지**와 **🔴·⚪ 인지** 확인한다. 🔴·⚪ 면 결과 파일을 보내 판정을 받는다 |

**판정**(결과 첫 줄):
- `🟢 점검 항목 정상` — 아래 §2 항목이 모두 기대값이다. **그 항목에 한정된다**(시스템 전체가 정상이라는 뜻이 아니다).
- `🔴 이상 N건` — 기대값과 다른 항목이 있다. 줄마다 무엇이 다른지 적혀 있다.
- `⚪ 판정 불가 — 수집 실패 N건` — DB 조회 오류·시간 초과·출력 누락, 앱 상태·로그·66세션 조회 실패. **정상으로 보지 않는다.**
- `🟡` 줄은 참고(알려진 외부 통신 실패 건수 등) — 판정을 바꾸지 않지만 늘어나면 본다.

결과에는 **실행 시각(KST)·점검 구간(기본 직전 12시간)·운영 SHA** 가 항상 들어간다.

**텔레그램으로 오는 자동 보고는 두 가지다**(헷갈리지 않게):

| 첫 줄 | 언제 | 무엇 | 보내는 것 |
|---|---|---|---|
| `[정기 점검] 🟢/🔴/⚪ …` | 매일 10:00·22:00 | 이 문서의 점검(실자금·66세션 수·ERROR·텔레그램 이력·앱 상태) | `~/ops-scripts/daily_check.sh` |
| `🟢/🔴 운영 감시 …` | 매일 00:25 | 66세션 적재·세션·로그(동기화 실패·KILL 경보·잠금)·누수 | `scripts/prospective/start_66.py watch --notify`(기존) |

KILL 경보·잠금·누수는 WARN 이라 `[정기 점검]` 에는 나오지 않는다 — **운영 감시** 쪽에서 본다.

## 2. 점검 항목과 기대값

| 항목 | 출처 | 기대 | 다르면 |
|---|---|---|---|
| 서버 작업 트리 | `git status`(추적 파일) | 변경 없음 | 🔴 |
| backend 컨테이너 | `docker inspect` | running · 재시작 0 · 구간 안 기동 없음 | 🔴(없음·멈춤·재시작·구간 안 기동 — "계획된 재기동인지 확인") |
| SID 203 밖 실자금 보유(OPEN·CLOSING) | `daily_checks.sql` | 0 | 🔴 |
| SID 203 밖 실자금 미결 주문 | 〃 | 0 | 🔴 |
| 정리 대기 | 〃 | 0 | 🔴 |
| 격리 대조 대상 | 〃 | 0 | 🔴 |
| 구간 안 SID 203 밖 실자금 신규 매수 | 〃 (정비용 Q8 의 고정 날짜 대신 **점검 구간 시작**) | 0 (실자금 진입은 SID 203 하나만) | 🔴 |
| 미해결 정산 불일치(모든 세션, `order_settlement_discrepancy` `resolved_at` 없음) | 〃 | 0 | 🔴 |
| SID 203 상태(`SID_ID`, 기본 203) | 〃 | `SID_OK_STATUS` **하나** — 설치·시작 전 `CREATED`(기본), F2 시작 확인 뒤 `RUNNING`(§3-1). 둘을 함께 허용하지 않는다(리뷰 2026-10-31 보완 — 잘못 시작해도 정상으로 보던 것). 56일 종료로 정지한 뒤에는 리뷰 판단을 받아 `STOPPED` | 🔴(설정과 다른 상태 — 시작 전 `RUNNING`·시작 뒤 `CREATED`·비상정지·없음 등) |
| SID 203 시작 전(`CREATED`) 주문·보유 | 〃 | 열린 포지션·미결 주문·구간 주문 모두 0 | 🔴 |
| SID 203 열린 포지션 · 동시 미결 주문 | 〃 | 각각 **0~1** — 정상 매수·보유·청산·재진입은 허용 | 🔴 2 이상(중복 주문) |
| SID 203 미결 주문 지속 · UNKNOWN | 〃 | `STALE_MIN`(기본 15)분 넘는 미결 0 · UNKNOWN 0 | 🔴 |
| SID 203 중복 매수 | 〃 | 한 포지션의 체결 매수 1건 이하 · 구간 안 체결 매수 1건 이하(일봉 전략) | 🔴 |
| SID 203 체결 주문 정산 확인 불가 | 〃 — 체결 있는 주문의 거래소 보고값(`reported_executed_funds`·`reported_paid_fee`)·반영값(`applied_*`) | 넷 중 하나라도 없음(NULL — 0 으로 대체하지 않는다)·회계 반영 대기(`fill_accounting_pending`) 0. **경과 시간 조건 없음** — 오래된 주문에 방금 정정이 와도 그대로 센다(리뷰 2026-10-31 보완) | **⚪ 확인 불가**(주문 id 를 붙인다) — 재조회 대상. 결과 파일을 보내 판정(§5) |
| SID 203 체결 주문 보고값≠반영값 | 〃 | 반영 대기가 아닌데 보고값과 반영값이 다른 주문 0 | 🔴(주문 id 를 붙인다) |
| 격리 해제 기록 | 〃 | 0 | 🔴 |
| 검증 66세션(522~587) | 〃 — RUNNING 아닌 세션 `id:상태` 목록(`s66_not_running`) | 원래 66개 중 **지정 종료 세션(`S66_ENDED_IDS`, 기본 `576 577 578`)만 STOPPED**, 나머지 RUNNING | 🔴 지정 밖 세션이 RUNNING 아님(지정 세션이 STOPPED 아닌 다른 상태여도) · 세션 수 66 아님 · **지정 세션이 다시 RUNNING(의도치 않은 재시작)** — 정지 완료(10-27 13:05 KST) 뒤라 '정지 대기' 로 넘기지 않는다(리뷰 2026-10-27, 종전 판 `fa0691e` 의 🟡 를 고쳤다). 🔴 기대값을 63 으로 낮춘 것이 아니다 — 어느 세션인지로 비교한다(리뷰 2026-10-27) |
| 텔레그램 중요 경보 전송 실패(구간) | 〃 — `STOP_LOSS`·`SESSION_STOP`·`RISK_LIMIT`·`CLEANUP_NEEDS_HUMAN`·`CLEANUP_INCOMPLETE`·`EXCHANGE_DOWN`·`CANDLE_REFRESH_REPORT` | 0 | 🔴 (그 밖의 유형 실패는 🟡) |
| 텔레그램 큐 진입 실패(구간) | 〃 (본문 머리 `[큐 포화`) | 0 | 🔴 |
| ERROR 로그(구간) | `docker compose logs --since` | 아래 알려진 유형 외 0 | 🔴 미분류 N건(앞 5줄을 결과에 적는다) |
| 66세션 경보 | `start_66.py alerts --since <구간> --no-mark` | 🟢 경보 없음 · 실행 누락 없음 | 🔴(경보는 확정 아님 — 확인 필요, 종료 확인 마켓 밖 코인명을 붙인다) · 관측 없음은 ⚪ · 🟡 **적재 뒤처짐만 있고 그 회차들의 🔴 코인이 전부 거래지원 종료 확인 마켓(`ENDED_MARKETS`, 기본 `KRW-BLAST`)일 때만** |
| 서버 시간대 | `date +%z` | +0900 | 🟡(cron 시각이 어긋날 수 있다) |

**알려진 ERROR — 로거와 문구로 한정**(같은 로거라도 문구가 다르면 미분류 🔴):

| 분류 | 조건(정규식) | 처리 |
|---|---|---|
| 공유 풀 미등록 | `CapitalPoolLimits - .*공유 풀 한도가 사전 등록과 맞지 않는다.*한도 설정 누락` | 의도된 것(STAGE_1 비활성) — 건수만 |
| 캔들 판정 불가 | `CandleFreshnessGuard - .*\[캔들 최신성\].*판정 불가` | 🟡 건수 |
| 상장 폐지 코인 캔들 404 | `UpbitRestClient - Upbit API 오류: status=404, url=https://api.upbit.com/v1/candles/.*Code not found` | 건수 + **마켓별**(리뷰 2026-10-27 — BLAST 상장 폐지가 건수에 묻혔다): **검증 코인**(`s66_coins`)이면 코인명과 함께 — 거래지원 종료 확인 마켓(`ENDED_MARKETS`)은 🟡, 확인되지 않은 마켓 소실은 🔴(업비트 공지 확인). 검증 밖 마켓은 마켓명·건수만 |
| 외부 통신 실패 | `UpbitCandleCollector - 캔들 수집 실패` · `MarketDataSyncService - 시장 데이터 동기화 실패` · `UpbitOrderClient - 계좌 조회 실패` · `WatchlistFilterService - [Watchlist] 필터링 실패` · `TelegramNotificationService - [Telegram] 메시지 전송 중 오류(응답 미수신)` · `ClaudeProvider - [ClaudeProvider] 요청 실패` | 🟡 건수 |

**SID 203 판정(리뷰 2026-10-31)**: 종전 판은 실자금 보유·신규 매수가 모두 0 이어야 정상이라 SID 203 의 정상 매수도 🔴 였다. 지금은 SID 203 을 따로 세고(위 표), 그 밖의 실자금 세션은 종전대로 0 을 기대한다. 첫날 F3(§5-8)는 누적 매수 2건 이상을 이상으로 보므로 일상 점검에 다시 쓰지 않는다. 잔액·비용·순손익 대조는 이 점검의 범위 밖이다(별도 기록 — 리뷰 2026-10-31 2번).
정산 판정(리뷰 2026-10-31 보완): 반영값만 보던 종전 판은 거래소 보고값이 빠졌거나 보고값≠반영값인 주문을 놓쳤다. 지금은 넷을 함께 보고, 확인 불가는 발견 즉시 ⚪(시간 조건 없음), 대기 없는 불일치·미해결 불일치 기록은 🔴 이다. `STALE_MIN` 은 미결 주문 지속 검사에만 쓴다. UNKNOWN 은 즉시 🔴 그대로다.

이 목록에 넣는 것은 운영 기록으로 반복이 확인된 것만이다. 넣거나 빼려면 리뷰를 거친다.
🔴 스크립트는 로케일을 **`LC_ALL=C`(바이트 비교)로 고정**한다(2026-10-27) — 서버 대화형 셸(`C.UTF-8`)에서 GNU grep 3.7 이 공유 풀 기동 줄을 위 정규식으로 잡지 못해 미분류 🔴 로 셌다(cron 의 `C` 에서는 정상, 원인 미규명).
`S66_ENDED_IDS`·`ENDED_MARKETS`(스크립트 기본값)도 같다 — 검증 코인의 거래지원 종료가 확인되고 리뷰가 처리를 정한 뒤에만 넣는다(`SUPERTREND_VALIDATION_PREREG.md` 📌 KRW-BLAST).

## 3. 설치 (리뷰 확인 뒤, 1회)

```bash
cd ~/crypto_trader/AutoTrader
git fetch origin
timedatectl | grep 'Time zone'            # Asia/Seoul (+0900) 이어야 cron 의 10·22 시가 KST 다
date '+%F %T %z'
command -v flock python3 gzip curl        # 넷 다 있어야 한다(flock — 중복 실행 잠금, python3 — 보고 자르기·retry_after 해석)
# 🔴 움직이는 origin/main 이 아니라 **리뷰가 검토한 커밋 SHA** 에서 두 파일을 함께 꺼낸다(리뷰 2026-10-22). 서버 체크아웃(배포 SHA)과는 무관하다.
SHA=<검토한 커밋 SHA>
git cat-file -e "$SHA":scripts/ops/daily_check.sh && git cat-file -e "$SHA":scripts/ops/daily_checks.sql && echo 두 파일 있음
mkdir -p ~/daily-check ~/ops-scripts
git show "$SHA":scripts/ops/daily_check.sh   > ~/ops-scripts/daily_check.sh
git show "$SHA":scripts/ops/daily_checks.sql > ~/ops-scripts/daily_checks.sql
git rev-parse "$SHA" > ~/ops-scripts/INSTALLED_SHA      # 결과 첫머리에 "점검 스크립트 <SHA>" 로 나온다
chmod +x ~/ops-scripts/daily_check.sh
sha256sum ~/ops-scripts/daily_check.sh ~/ops-scripts/daily_checks.sql   # 설치 기록에 남긴다
```
스크립트는 `REPO_DIR`(저장소)와 SQL 위치를 알아야 한다. 설치본은 `~/ops-scripts` 에 두므로 둘 다 지정해 돌린다:
```bash
SID_OK_STATUS=CREATED REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql DRY_RUN=1 ~/ops-scripts/daily_check.sh
```
첫 실행은 **`DRY_RUN=1`(보고를 보내지 않고 화면에)** 로 하고 출력을 리뷰에 보낸다. 판정이 맞으면 `DRY_RUN` 없이 한 번 더 돌려 텔레그램 수신을 확인한 뒤 cron 에 넣는다:
```bash
crontab -e
# 정기 점검 자동화 (OPS_DAILY_CHECK.md) — 10:00·22:00 KST
0 10,22 * * * SID_OK_STATUS=CREATED REPO_DIR=$HOME/crypto_trader/AutoTrader SQL_FILE=$HOME/ops-scripts/daily_checks.sql $HOME/ops-scripts/daily_check.sh >> $HOME/daily-check/cron.log 2>&1
```
`SID_OK_STATUS=CREATED` 는 기본값과 같지만 **cron 줄에 적어 둔다** — 시작 뒤 바꿀 자리를 한 곳으로 두기 위해서다(§3-1).
중복 실행은 스크립트가 막는다 — 서버는 **`flock`**(`~/daily-check/.lock.flock`, 원자적, 프로세스가 끝나면 풀린다). 이미 돌고 있으면 종료 3.
(flock 이 없는 환경만 `mkdir` 잠금으로 대체 — 시험용.) 업데이트는 같은 순서로 새 SHA 를 꺼내 덮어쓰고 `INSTALLED_SHA` 를 바꾼다.
앱이 내려가 있어도 점검·보고는 돈다(앱 상태는 🔴, 66세션 경보는 앱 API 가 필요해 ⚪).

### 3-1. SID 203 시작 뒤 정상 상태 전환 (1회, F2 시작 확인 직후)

설치부터 시작 전까지는 `CREATED` 만 정상이다 — 계획 밖에서 시작되면 다음 점검이 🔴 로 잡는다.
§5-8 F2 의 시작 요청 뒤 **세션이 `RUNNING` 인 것을 확인한 직후**, 첫 정기 점검(10:00 KST) 전에 cron 줄의 값 하나만 바꾼다.
🔴 종전 명령(`crontab -l | sed … | crontab -`)은 조회가 실패하면 빈 입력을 그대로 적용해 **cron 전체를 비운다**(리뷰 2026-10-31 지적, 모의 명령으로 확인 — §6 `test_cron_switch.py`). 아래 블록은 조회 성공본을 파일로 저장 → 대상 줄 정확히 1개 확인 → 수정본에서 다른 줄 보존 확인 → 적용 → 적용 뒤 대조 순서다. 어느 확인이든 실패하면 **적용 전에 멈추고 cron 은 그대로**다.
```bash
(
set -euo pipefail
FROM=CREATED; TO=RUNNING                                    # 56일 종료 뒤에는 리뷰 판단을 받아 이 줄만 FROM=RUNNING; TO=<최종 상태>
D=~/daily-check; B="$D/crontab.before-$TO"; N="$D/crontab.new-$TO"
stop() { echo "🔴 멈춤: $* — cron 은 바꾸지 않았다"; exit 1; }
[ ! -e "$B" ] || stop "$B 가 이미 있다(전에 바꾼 적이 있다) — crontab -l 출력과 함께 보고"
crontab -l > "$B" || stop "crontab 조회 실패"
[ -s "$B" ] || stop "조회한 crontab 이 비었다"
[ "$(grep -c "SID_OK_STATUS=$FROM " "$B" || true)" = 1 ] || stop "SID_OK_STATUS=$FROM 줄이 정확히 1개가 아니다"
! grep -q "SID_OK_STATUS=$TO " "$B" || stop "SID_OK_STATUS=$TO 줄이 이미 있다"
sed "s/SID_OK_STATUS=$FROM /SID_OK_STATUS=$TO /" "$B" > "$N"
[ "$(wc -l < "$N")" = "$(wc -l < "$B")" ] || stop "수정본 줄 수가 다르다"
[ "$(grep -c "SID_OK_STATUS=$TO " "$N" || true)" = 1 ] || stop "수정본에서 바뀐 줄이 1개가 아니다"
diff <(grep -v "SID_OK_STATUS=$TO " "$N" || true) <(grep -v "SID_OK_STATUS=$FROM " "$B" || true) > /dev/null || stop "수정본에서 다른 줄이 달라졌다"
crontab "$N" || stop "crontab 적용 실패 — crontab -l 로 지금 상태를 확인해 보고"
crontab -l | cmp -s - "$N" || { echo "🔴 적용 뒤 대조 실패 — crontab \"$B\" 로 되돌리고 crontab -l 출력을 보고"; exit 1; }
echo "✅ cron 전환 $FROM → $TO · 보존본 $B · 적용본 $N"
)
SID_OK_STATUS=RUNNING REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql DRY_RUN=1 ~/ops-scripts/daily_check.sh
```
- 기대: `✅ cron 전환 CREATED → RUNNING` 한 줄, 그리고 마지막 명령의 결과에 `SID 203: RUNNING` 줄이 있고 `SID 203 상태` 🔴 줄이 없다.
- `🔴 멈춤` 이면 cron 은 바뀌지 않았다 — 출력 그대로와 `crontab -l` 출력을 보낸다. 블록을 고쳐 다시 돌리지 않는다.
- `🔴 적용 뒤 대조 실패` 면 안내대로 보존본으로 되돌리고 `crontab -l` 출력을 보낸다.
- 시작이 확인되지 않았으면(F2 실패·보류) 바꾸지 않는다 — `CREATED` 그대로가 맞다.
- 56일 종료 뒤 최종 상태(`STOPPED` 또는 `EMERGENCY_STOPPED` — 비상정지면 그 상태 그대로)로 바꾸는 것은 리뷰 판단을 받은 뒤 같은 블록으로, 첫 줄 `FROM=RUNNING; TO=<최종 상태>` 만 바꿔서 한다(§5-8 F5-4).

## 4. 수동 실행

```bash
SID_OK_STATUS=CREATED REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql ~/ops-scripts/daily_check.sh; echo "종료 $?"   # SID 203 시작 전
SID_OK_STATUS=RUNNING REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql ~/ops-scripts/daily_check.sh; echo "종료 $?"   # F2 시작 확인 뒤(§3-1)
```
- 🔴 `SID_OK_STATUS` 는 **늘 적는다** — cron 줄과 같은 값(`crontab -l | grep -o 'SID_OK_STATUS=[A-Z_]*'`). 빼면 기본값 `CREATED` 로 판정해, 시작 뒤에는 정상 운영을 🔴 로 낸다(리뷰 2026-10-31 보완).
- `DRY_RUN=1` — 보고를 보내지 않고 화면에만. `WINDOW_HOURS=24` — 구간을 늘린다(기본 12).
- 종료 코드: `0` 점검 항목 정상·보고 전송 · `1` 🔴 또는 ⚪·보고 전송 · `2` **보고 전송 실패** · `3` 이미 실행 중.

## 5. 결과 확인과 실패 확인

**보고 미수신 — 서버에 파일이 남는 것만으로는 운영자가 알 수 없다.** 운영자는 **10:05·22:05 무렵 보고 도착 여부**를 확인하고, 없으면 서버를 본다:
```bash
cat ~/daily-check/last_send                 # 마지막 보고 — "보고 전송 성공" / "🔴 보고 전송 실패(사유)"
ls -t ~/daily-check/2*.txt | head -3        # 최근 결과 파일(YYYYMMDD-HHMM.txt) — 이번 회차 파일이 있는가
tail -20 ~/daily-check/cron.log             # cron 실행 기록·오류(스크립트가 아예 안 돌았는지)
grep CRON /var/log/syslog | tail -5         # cron 자체가 돌았는지
```
| 보이는 것 | 뜻 | 할 일 |
|---|---|---|
| 이번 회차 결과 파일 없음 · cron.log 에도 없음 | cron 이 돌지 않았다 | `crontab -l`·시간대 확인, 수동 실행(§4) |
| 결과 파일 있음 · `last_send` 가 전송 실패 | 점검은 했고 보고만 못 갔다 | 결과 파일 첫 줄로 판정 확인 · 텔레그램 경로 확인 |
| `⚪ 판정 불가` | 수집 실패 — 정상 아님 | 결과 파일과 `.raw`(원자료)를 보내 판정 |
| `🔴` | 기대값과 다름 | 결과 파일을 보내 판정. 실자금 보유·미결·격리 대상·중요 경보 실패는 §6.5 절차에 따라 운영자가 집행 판단 |

결과 파일: `~/daily-check/YYYYMMDD-HHMM.txt`(요약) · `….txt.raw`(DB·66세션 원자료) · `….txt.log.gz`(**수집한 앱 로그 원문** — 장애 대조용) · `….txt.msg`(보낸 본문).
60일 지난 파일은 지운다. 보고 본문은 **문자 기준 3,500자**에서 자르고 "이하 생략 — 서버 결과 파일 …" 을 붙인다.

## 6. 시험 (로컬 — 운영에 닿지 않는다)

`python scripts/ops/test_cron_switch.py` — §3-1 전환 블록을 이 문서에서 그대로 꺼내 가짜 `crontab`·임시 HOME 으로 돌린다(리뷰 2026-10-31 보완 2): 종전 명령 재현(조회 실패 → cron 비움) · 정상 전환 · 조회 실패·crontab 없음·빈 crontab·대상 0줄·2줄·적용 실패·두 번째 실행은 멈추고 cron 그대로 · 적용 뒤 대조 실패는 되돌리기 안내 · 종료 전환.

`python scripts/ops/test_daily_check.py` — 로컬 시험 DB(`cat-rt-pg`, V94 사본 + 66세션 행)·모의 텔레그램 서버·깨끗한 임시 저장소(서버 체크아웃 흉내)로 **29건**:
정상(판정·시각·구간·운영 SHA·점검 스크립트 판·알려진 ERROR 분류·평문 보고) · 수치 이상(66세션 65/66·중요 경보 실패 🔴·`CUSTOM` 실패 🟡) · 미분류 ERROR(다른 로거·알려진 로거의 다른 문구) ·
DB 조회 실패 · 출력 누락 · 시간 초과 · 앱 없음(나머지 점검·보고 계속) · 구간 안 기동 · 작업 트리 변경 · 텔레그램 전송 실패(3회·종료 2·파일 유지) · 중복 실행 · 죽은 잠금 · 시간대 불일치 ·
**(리뷰 보완)** pid 기록 전 잠금은 실행 중 · 동시 시작 5회(로컬 mkdir 잠금) · **리눅스 `ubuntu:22.04` 컨테이너에서 flock 동시 시작 5회** · 66세션 종료 0 인데 출력 없음/알 수 없는 출력 → ⚪ ·
종료 1 인데 🔴 줄 없음 → ⚪ · 🔴 줄 → 🔴 · 공백 있는 `retry_after`(3초 대기 뒤 성공) · `retry_after` 20초 두 번(합계 상한으로 두 번째 대기 없이 중단) · 긴 한글 보고(7,600자 → 3,500자, UTF-8 엄격 해석 통과) ·
한글 한가운데 자르기(앞 41자 그대로) · 설치 SHA 표기 · 앱 로그 원문 `.log.gz` 보존.
