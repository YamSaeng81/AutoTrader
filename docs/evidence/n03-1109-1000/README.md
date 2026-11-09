# N03 2026-11-09 10:00 정기 보고 — 읽기 전용 확인 (주말 지시 W5)

**읽기만 했다.** 재실행·재전송·수집 호출을 하지 않았고 서버에 쓰지 않았다. 앱 API도 부르지 않았다. 실제 읽은 시각은 2026-11-09 10:12~10:14 KST다(`read.sh` 10:12, 보충 `observations-outage.txt`·`observations-estop.txt` 10:13~10:14). 보고 생성·전송과 09:30 장부 생성이 끝난 뒤에 읽었다.

## 확인 결과 — 🔴 이상 2건(새 🔴), P0 없음

이번 회차 머리줄은 **"🔴 이상 2건"**이다. 지난 세 회차(11-08 10:00·22:00 🟢)와 다르다. 두 🔴는 같은 사건에서 나왔다. **11-09 02:02~05:48 KST에 앱에서 Upbit로의 연결이 실패했다.** 거래소 상태가 두 번 DOWN이 됐고, 두 번 모두 전체 비상 정지가 실행됐다. 정지된 세션은 0개였고 취소된 주문도 0건이었다. 05:48:53 KST에 UP으로 돌아왔고, 그 뒤 ERROR는 0줄이다.

연결 실패의 원인(서버 쪽 망인지 Upbit 쪽인지)은 **확정하지 않았다.** 같은 시간에 텔레그램 전송 성공이 있었다(02:03:42·04:35:32 KST). 그래서 바깥 통신 전체가 끊긴 것은 아니다. 이것만으로 원인을 정하지 않는다.

| 항목 | 값 | 판단 |
|---|---|---|
| 보고 생성·전송 | `.txt` 10:00:05 생성. `last_send` "10:00:05 KST 보고 전송 성공 · 🔴 이상 2건". 내려받은 4파일 sha256이 서버 값과 같음 | 정상 생성·전송(휴대폰 수신은 미확인) |
| 머리줄 | 🔴 미분류 ERROR 377 · 🔴 66세션 경보(적재 뒤처짐 12·판정 불가 2·실행 누락 1구간) · 🟡 통신 실패 2335 · 🟡 BLAST 보충 3 | **새 🔴.** 원문 전체는 아래와 `20261109-1000.txt` |
| 앱·이미지 | running·재시작 0·기동 2026-11-06T04:36:38Z·이미지 `sha256:426a09a2…53a1`·HEAD 1899b49 | #26 그대로. 재시작 없음 |
| 로그 범위 | 요청 11-08 22:00 ~. 컨테이너 생성 11-06 13:36:36(구간 전, 재생성 공백 없음). log.gz 첫 줄 13:00:51.775Z, 끝 줄 00:59:53.815Z | 범위 확보 |
| 설치본·cron | INSTALLED_SHA c669ccd·daily_check.sh `ff0a5d7d…`·daily_checks.sql `653b22ba…`·SID_LEDGER_SHA 8bfe8a9·sid_ledger.sql `02a85850…`·crontab `f2dce280…` | 그대로 |
| DB | Flyway 94·SID 203 밖 실자금 보유/미결/정리/격리 0·66세션 RUNNING 63·576/577/578 STOPPED 3/3·구간 실자금 매수 0 | 정상. 비상 정지 뒤에도 세션 상태 그대로 |
| SID 203 | CREATED·20200.00·보유 0·미결 0·구간 주문 0·정산 불일치 0 | 정상. 비상 정지 대상 아님(RUNNING 아님) |
| BTC H1 | B1 true·최신 완료 봉 11-09 09:00·B3 20/20(관측 10:00:02) | 충족 |
| **P0 관측** | Hikari 누수 경고 0·unleaked 0·**DB 연결 획득 실패(Connection is not available) 0** | **P0 없음.** 아래 "connect timed out"은 Upbit HTTP 연결 시간 초과이고 DB 연결 풀 획득이 아니다 |
| 시세 보호 문구 | N17 0·N19 0·N20 건너뜀 0·N20 회차 오류 0·시세 처리 실패 0 | 0·음수 시세 없음 |
| 뉴스 | 수집 완료 76·미완료 32(CryptoPanic HTTP 403 24, UpbitNotice HTTP 403 7, **UpbitNotice HTTP 504 1**). DOCTYPE/`<html` 0 | 403은 기존(원인 가설). UpbitNotice 403·504는 지난 10:00 회차에 없던 것 |
| CryptoPanic `Connection reset` | `Connection reset` 줄 1(텔레그램 ERROR 1줄) | 미분류 🔴 기록은 **해소로 쓰지 않는다** |
| 09:30 장부 | `20261109.txt` 09:30:01 생성 `183da609…`. 11-08 장부와 차이는 L0 행의 생성 시각 1줄과 L4 행의 참고 BTC 가격 1줄(103700000 @11-08 09:00 → 102460000 @11-09 09:00)뿐(`ledger-diff-vs-1108.txt`). L0 CREATED·20200·init_ok t, L4 차이 0.00. 과거 장부 파일 그대로(maint-n17 기준) | 정상 |
| 토큰 형태 | 보존 파일 모두 0 | 비밀 없음 |

## 사건 시각표 (로그는 UTC, 괄호는 KST)

`observations-outage.txt`·`observations-estop.txt` 원문에서 옮겼다.

| UTC | KST | 줄 |
|---|---|---|
| 15:00:55 → 15:01:25 | 00:00 → 00:01 | UP → DEGRADED → UP(1회 실패 뒤 복귀) |
| 17:02:09 | 02:02 | 거래소 연결 실패 (1/3회): request timed out · UP → DEGRADED |
| 17:03:39 | 02:03 | (3/3회) · DEGRADED → DOWN · `거래소 DOWN 감지 — ExchangeDownEvent 발행` · `전체 비상 정지 실행! (dryRun=false)` · `전체 비상 정지: 0건 주문 취소` · `전체 비상 정지 완료: 0개 세션 정지` |
| 17:04~19:56 | 02:04~04:56 | `HTTP connect timed out` 반복(40초 간격 상태 확인) |
| 19:56:13 → 19:56:43 | 04:56 | DOWN → DEGRADED → UP · `ExchangeRecoveredEvent 발행` · `거래소 복구 감지 — 재시작 대상 세션 없음` |
| 19:58:53 → 20:00:23 | 04:58 → 05:00 | UP → DEGRADED → DOWN · 두 번째 전체 비상 정지(0건 취소·0개 정지) |
| 20:48:53 | 05:48 | DOWN → UP · `재시작 대상 세션 없음` |
| 20:48:34 | 05:48 | 마지막 ERROR(`MarketDataSyncService` KRW-BCH H1). 이후 ERROR 0 |

시간별 ERROR: 15시 1 · 17시 686 · 18시 697 · 19시 750 · 20시 581(UTC). 다른 시간대는 0.

## ERROR 2715줄 구성 (`error_lines.txt` 전문, sha256 `369524f0…` 서버 값과 같음)

| 건수 | 로거 — 문구(코인·시간틀·숫자는 정규화) |
|---|---|
| 1159 | `UpbitCandleCollector` — 캔들 수집 실패: HTTP connect timed out |
| 1113 | `MarketDataSyncService` — 시장 데이터 동기화 실패: KRW-X T - 캔들 데이터 수집 실패 |
| 332 | `ExchangeHealthMonitor` — 거래소 연속 N회 실패 — DOWN 선언(3회 ~ 75회) |
| 49 | `UpbitOrderClient` — 계좌 조회 실패: HTTP connect timed out |
| 34 | `DataCollectionService` — 캔들 수집 실패: KRW-X — 캔들 데이터 수집 실패(19:10~19:34Z) |
| 11 | `UpbitCandleCollector` — 캔들 수집 실패: request timed out |
| 6 | `AccountService` — 계좌 정보 조회 실패: 계좌 조회 실패 |
| 2+2+2 | `LiveTradingService` — 거래소 DOWN 이벤트 수신 / 전체 비상 정지 실행! / 전체 비상 정지 완료: 0개 세션 정지 |
| 2 | `ExchangeHealthMonitor` — 거래소 DOWN 감지 — ExchangeDownEvent 발행 |
| 1 | `UpbitOrderClient` — 계좌 조회 실패: request timed out |
| 1 | `ClaudeProvider` — 요청 실패(15:01:04Z = 00:01 KST, 연결 실패 구간 밖) |
| 1 | `TelegramNotificationService` — 메시지 전송 중 오류(응답 미수신): Connection reset(17:03:39Z) |

점검 스크립트는 2715를 통신 실패 2335·BLAST 보충 3·미분류 377로 나눴다. 위 표의 어느 줄이 미분류 377에 들어갔는지는 점검 스크립트 분류 규칙을 다시 돌리지 않았으므로 **줄 단위로 맞추지 않았다.** 다만 377은 DOWN 선언 332·DataCollectionService·AccountService·LiveTradingService·DOWN 감지·ClaudeProvider·Telegram 계열의 합(382)에서 BLAST 3 등을 뺀 범위와 맞는다.

## 66세션 경보 (`.raw` 원문, 시각은 UTC)

- 🔴 적재 뒤처짐 12회: 전부 KRW-BLAST 1종. 지난 회차와 같은 기존 분류(거래지원 종료 마켓)다.
- ⚪ 판정 불가 2회: 19:56 16종, 19:57 1종(KRW-IOTA). 거래소 복구 직후의 첫 관측이다.
- 🔴 실행 누락 1구간: 16:19 → 19:56, 218분. 17:19·18:19·19:19 관측이 빠졌다. 연결 실패 구간(17:02~19:56)과 겹친다.
- 현재(00:19): 🟢 21 / ⚪ 0 / 🔴 1(BLAST).
- 점검 스크립트 문구대로 경보이고 확정이 아니다. 분봉 확인은 하지 않았다(읽기 범위 밖 DB 조회).

## 코드 확인 (로컬, 읽기만)

`LiveTradingService.onExchangeDown`은 그 시각 RUNNING인 실전 세션만 비상 정지하고, 그 ID만 복구 때 다시 켠다. 이번에는 0개라 "재시작 대상 세션 없음"이 맞다. **SID 203은 CREATED라 대상이 아니었다.** SID 203이 RUNNING이었다면 비상 정지 대상이 되고, 복구 뒤 자동 재시작 경로를 탄다. 이 경로를 운영에서 실제로 거친 적은 없다. 이번 회차는 그것을 실증하지 않는다.

결론: 새 🔴 2건. 원인 후보는 02:02~05:48 KST의 Upbit 연결 실패이며 확정은 아니다. 손상 흔적은 없다(재시작 0, 세션·SID 203·실자금 변화 0, 주문 0, DB 연결 획득 실패 0). 이 판단은 개발 에이전트의 확인이며 총괄 판정 전이다.

## 파일

- `read.sh`: 실행한 읽기 스크립트 원문(11-08 10:00 회차와 같은 명령, 파일 이름·폴더·장부 날짜만 바꿈)
- 보고 `.txt`·`.msg`·`.raw`, `last_send`, `ledger-20261109.txt`, `ledger-files.txt`, `ledger-diff-vs-1108.txt`
- `server-files.txt`, `local-sha256.txt`, `install_state.txt`, `error_lines.txt`, `observations*.txt`, `token-check.txt`
- 보충 읽기(같은 log.gz를 zcat·grep만): `observations-outage.txt`(상태 변경·WARN 분포·텔레그램 성공 시각·00시 WARN), `observations-estop.txt`(비상 정지 주문 취소·복구 줄)
- 서버의 `.log.gz`는 내려받지 않았다(sha256 `5a2828bd…`만 기록).
- `read.sh` 종료값 1은 마지막 토큰 검사 `grep -c`가 0건일 때 내는 값이다.
