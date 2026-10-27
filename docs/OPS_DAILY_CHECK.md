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
| 실자금 보유(OPEN·CLOSING) | `daily_checks.sql` | 0 | 🔴 |
| 실자금 미결 주문 | 〃 | 0 | 🔴 |
| 정리 대기 | 〃 | 0 | 🔴 |
| 격리 대조 대상 | 〃 | 0 | 🔴 |
| 구간 안 실자금 신규 매수 | 〃 (정비용 Q8 의 고정 날짜 대신 **점검 구간 시작**) | 0 (STAGE_1 비활성) | 🔴 |
| 격리 해제 기록 | 〃 | 0 | 🔴 |
| 검증 66세션(522~587) | 〃 — RUNNING 아닌 세션 `id:상태` 목록(`s66_not_running`) | 원래 66개 중 **지정 종료 세션(`S66_ENDED_IDS`, 기본 `576 577 578`)만 STOPPED**, 나머지 RUNNING | 🔴 지정 밖 세션이 RUNNING 아님(지정 세션이 STOPPED 아닌 다른 상태여도) · 세션 수 66 아님 · 🟡 지정 세션이 아직 STOPPED 아님(정지 대기). 🔴 기대값을 63 으로 낮춘 것이 아니다 — 어느 세션인지로 비교한다(리뷰 2026-10-27) |
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

이 목록에 넣는 것은 운영 기록으로 반복이 확인된 것만이다. 넣거나 빼려면 리뷰를 거친다.
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
REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql DRY_RUN=1 ~/ops-scripts/daily_check.sh
```
첫 실행은 **`DRY_RUN=1`(보고를 보내지 않고 화면에)** 로 하고 출력을 리뷰에 보낸다. 판정이 맞으면 `DRY_RUN` 없이 한 번 더 돌려 텔레그램 수신을 확인한 뒤 cron 에 넣는다:
```bash
crontab -e
# 정기 점검 자동화 (OPS_DAILY_CHECK.md) — 10:00·22:00 KST
0 10,22 * * * REPO_DIR=$HOME/crypto_trader/AutoTrader SQL_FILE=$HOME/ops-scripts/daily_checks.sql $HOME/ops-scripts/daily_check.sh >> $HOME/daily-check/cron.log 2>&1
```
중복 실행은 스크립트가 막는다 — 서버는 **`flock`**(`~/daily-check/.lock.flock`, 원자적, 프로세스가 끝나면 풀린다). 이미 돌고 있으면 종료 3.
(flock 이 없는 환경만 `mkdir` 잠금으로 대체 — 시험용.) 업데이트는 같은 순서로 새 SHA 를 꺼내 덮어쓰고 `INSTALLED_SHA` 를 바꾼다.
앱이 내려가 있어도 점검·보고는 돈다(앱 상태는 🔴, 66세션 경보는 앱 API 가 필요해 ⚪).

## 4. 수동 실행

```bash
REPO_DIR=~/crypto_trader/AutoTrader SQL_FILE=~/ops-scripts/daily_checks.sql ~/ops-scripts/daily_check.sh; echo "종료 $?"
```
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

`python scripts/ops/test_daily_check.py` — 로컬 시험 DB(`cat-rt-pg`, V94 사본 + 66세션 행)·모의 텔레그램 서버·깨끗한 임시 저장소(서버 체크아웃 흉내)로 **29건**:
정상(판정·시각·구간·운영 SHA·점검 스크립트 판·알려진 ERROR 분류·평문 보고) · 수치 이상(66세션 65/66·중요 경보 실패 🔴·`CUSTOM` 실패 🟡) · 미분류 ERROR(다른 로거·알려진 로거의 다른 문구) ·
DB 조회 실패 · 출력 누락 · 시간 초과 · 앱 없음(나머지 점검·보고 계속) · 구간 안 기동 · 작업 트리 변경 · 텔레그램 전송 실패(3회·종료 2·파일 유지) · 중복 실행 · 죽은 잠금 · 시간대 불일치 ·
**(리뷰 보완)** pid 기록 전 잠금은 실행 중 · 동시 시작 5회(로컬 mkdir 잠금) · **리눅스 `ubuntu:22.04` 컨테이너에서 flock 동시 시작 5회** · 66세션 종료 0 인데 출력 없음/알 수 없는 출력 → ⚪ ·
종료 1 인데 🔴 줄 없음 → ⚪ · 🔴 줄 → 🔴 · 공백 있는 `retry_after`(3초 대기 뒤 성공) · `retry_after` 20초 두 번(합계 상한으로 두 번째 대기 없이 중단) · 긴 한글 보고(7,600자 → 3,500자, UTF-8 엄격 해석 통과) ·
한글 한가운데 자르기(앞 41자 그대로) · 설치 SHA 표기 · 앱 로그 원문 `.log.gz` 보존.
