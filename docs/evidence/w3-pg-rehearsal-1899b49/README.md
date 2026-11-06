# W3 — 현재판(1899b49) 로컬 PostgreSQL 리허설 (주말 지시 §5, 2026-11-06, 로컬)

[주말 작업 지시](../../WEEKEND_20261106.md) §5 W3의 결과다. 운영 서버·DB·계좌·토큰은 쓰지 않았다. 외부 인터넷은 하네스 프록시로 막았다.

## 1. 환경

| 항목 | 값 |
|---|---|
| 제품 코드 | 격리 작업 사본 `D:/tmp/w3-1899b49`, detached `1899b49f633f8aefdc66b15c42debdaba8b3852c`. main 소스셋(web-api·core-engine·strategy-lib·exchange-adapter)은 1899b49와 차이 0 |
| 하네스 | main에서 4파일만 가져옴(`harness-overlay.diff`·`.sha256`): exchange_double.py·rt.py·rehearse_sma20.py·RestartHarnessController.java(시험 빌드 전용 소스셋). 그 뒤 rehearse_sma20.py 수정·rehearse_w3.py 신규. 최종본은 `harness-final/`, 같은 내용을 main `scripts/restart-test/`에 반영 |
| DB | `cat-rt-pg`(postgres, :15432), 실행마다 새 컨테이너. Flyway 최고 V94 |
| 거래소 | 별도 프로세스 대역(:18080). 앱 :18081 |
| 계획 문서 | 6298f5b의 `docs/LIMITED_LIVE_TEST_PLAN.md` `40fef702…`(커밋 블롭과 같아 사본을 두지 않음) |
| F5-3 | W4 발급 실행 사본 `../w4-n06-prep/runs/ok-6298f5b/f53.exec.sh` `2d5e5a9e…` |
| F5-4 장부 | 8bfe8a9의 `scripts/ops/sid_ledger.sql` `02a85850…`(운영 설치본과 같은 해시, 사본 없음) |

실행마다 하네스 해시를 `runN-harness.sha256`에 남겼다.

## 2. 실행

| 실행 | 시나리오 | 결과 | 분류 |
|---|---|---|---|
| run1 | rehearse_sma20.py | 앱 기동 실패 `NoClassDefFoundError MarketRegimeDetector` | **시험 환경 오류**: core-engine 등 모듈 jar 미빌드. `gradle-jars.log` 빌드 뒤 재실행 |
| **run2** | rehearse_sma20.py (17:00, 마감 봉 18:00) | **41/41 통과** | 10-31 4차와 같은 경로를 현재판에서 다시 봄 + 새 F1 줄 반영 |
| run3 | rehearse_w3.py (마감 봉 19:00) | 15/16, ③에서 중단 | **하네스 결함**: 체결 직후 `PositionLimitGuard`의 정기 평가가 최고 평가액(`peak_value_krw/at`)을 처음 기록(늘어날 때만 갱신, PositionLimitGuard.java 202행)한 것이 비교 구간에 들어옴. 재시작 요청은 409로 거절, 그 밖의 차이 0. 첫 기록을 기다린 뒤 비교하도록 고침(비교 항목은 줄이지 않음) |
| run4 | rehearse_w3.py | 시작 직후 개발자 중단 | run3 로그로 ④ "새 ERROR 0줄" 검사가 하네스의 외부 차단 때문에 생기는 웹소켓 재연결 ERROR(분당 약 2줄)·뉴스 수집 실패 ERROR를 세어 반드시 실패함을 확인. 이 두 종류만 따로 세고 나머지 ERROR는 1줄이라도 실패로 고침 |
| **run5** | rehearse_w3.py (마감 봉 20:00) | **41/41 통과** | 아래 3절 |

실패 실행의 출력은 모두 보존했다. 기대값을 바꿔 통과시킨 곳은 없다.

### run2 (rehearse_sma20.py)

- ① 새 DB·SID 203 생성(문서 본문 그대로) → F1(문서 DB·API 줄, EG=`1|0|1|true|-|-|0`) → F2 시작 1회 → RUNNING.
- 매수 접수 직후 강제 종료(halt 137) → 재기동 뒤 매수 정산 FILLED·잔액 590.2 → F3 ✅ 보유.
- **새로 넣은 확인**
  - F1 BTC 입력 줄: `F1-dropped.txt`에 현재판 F1에서 운영 전용으로 뺀 줄을 모두 남김.
  - F3 입력 판정: `✅ 현재 입력 충족·일봉 판단 있음`.
  - RUNNING 세션 재시작 요청: HTTP 409 `세션이 이미 실행 중입니다`, 기록 차이 0.
- 정지 → FORCED_STOP 청산, 잔액 20,182.36, F3 ⛔ STOPPED 판정 줄 일치, 강제 종료·재기동 뒤 기록 차이 0.

## 3. run5 (rehearse_w3.py) — 4차와 겹치지 않는 차이

| 단계 | 확인 | 결과 |
|---|---|---|
| ⓪ | W4 실행 사본·장부 SQL 해시 | `2d5e5a9e…`, `02a85850…` 같음 |
| ① | 생성·F1·F2·시작 확인 | 통과. F1 BTC 입력 `true\|11-06 18:00\|11-06 18:00\|20/20` |
| ② | 마감 봉 BUY → 회차 마감 뒤 매수 체결(강제 종료 없이) | FILLED 0.0016 · 19,600 · 수수료 9.8 · 잔액 590.2, 손절가 11,642,250 |
| ③ | RUNNING 재시작 요청 | 409, 차이 0(최고 평가액 첫 기록 19,590.2 뒤 비교) |
| ④ | 대역 ticker 0 → −5 각 130초 | 매도 0·포지션 OPEN·주문 1건. **N19 폴백 건너뜀 52 · N17 강제 갱신 가격 아님 1 · N20 보강 건너뜀 4 · SL 미점검 경고 1**. 연결 획득 시간 초과 0·누수 0. 환경 ERROR 9줄 외 새 ERROR 0 |
| ⑤ | 손절선 아래 정상 가격 11,600,000 | 보호 매도 1회 STOP_LOSS·FILLED, 세션 RUNNING, 거래소 bid 1·ask 1(중복 매도 없음), F3 ✅ 정상 사유 청산·BTC 증감 0 |
| ⑥ | 56일 종료(시계 주입) | 아래 |
| ⑦ | 강제 종료·재기동 | 거래소 호출 bid 1·ask 1, 기록 차이 0, 실행 로그 연결 획득 시간 초과 0 |

**⑥ 56일 종료** — 시계 주입은 F5 조회 psql 인자의 `now()`만 `(now() + interval '56 days 1 minute')`로 바꾼다(`run5/psql_clock.sh`). 데이터·서버 시각·앱은 그대로다.

- 주입 전 F5-1 `due f`, F5-2 "🔴 멈춤: 아직 실제 시작 + 56일 전"(정지 요청 없음, RUNNING).
- 주입 뒤 F5-1 `due t`, end_kst 세 줄 모두 `2027-01-01 19:50:00.656152`(F2 시작 확인 기록 + 56일).
- F5-2 정지 요청 1회 → STOPPED. 두 번째 실행은 "이미 정지 요청을 보냈다"로 멈춤.
- F5-3: **W4 실행 사본**을 썼다. 바꾼 곳은 다음 세 가지뿐이며 `run5/F5-3-dropped.txt`에 남겼다.
  - `git fetch origin && git show` → 로컬 `git -C <저장소> show`
  - `E` 경로(F5 변수 쪽 치환, `run5/F5-vars-dropped.txt`·`fvars-dropped.txt`)
  - 꺼낸 F3의 주소·토큰만 바꾼 사본 `f3-block.local.sh`로 실행(원문 `f3-block.sh`와 그 해시 출력은 그대로)
- F5-3 결과: F3 추출 해시 `bb86fb39…`, ⛔ STOPPED, 거래소 BTC 증감 0, `DB 정리 조건 충족`. KRW 줄 `거래소 KRW 증감 -1057 · 세션 증감 -1057.22 · 차이 0.22 (±1원 = 반올림)`.
- F5-4 장부(8bfe8a9판) 종료코드 0, ERROR 없음.
- 정지 전후 차이는 session status·stopped_at·updated_at·version 네 개뿐.

## 4. 관찰 (결함 아님, 총괄 참고)

- **O1 재확인**: N17 WARN이 REST 원문을 그대로 출력한다. `[§9] REST 강제 갱신 시세가 가격이 아님 — 발행하지 않음 (market=KRW-BTC, trade_price=-5.0)`(run5 app-02 20:08:54). W2 O1과 같은 판단 대기이며, 수정 후보는 만들지 않았다.
- **O2**: run2 정지 매도가 체결된 뒤 반영되기 전 약 0.4초 동안 `PoolLossGuard`가 `🔴 체결 수량이 있는데 반영값이 없는 실자금 주문 1건 — 순자산을 계산하지 않는다` ERROR 1줄을 남겼다(18:07:46.840, 반영 18:07:47.269). 순자산 계산을 거절하는 막는 쪽 설계다. 다만 운영 N03의 ERROR 줄 계수에 정지·청산 직후 1줄로 나타날 수 있다. run5 손절 경로에서는 나오지 않았다.
- **O3**: 대역 REST 시세로 발동한 손절이 `실시간 손절 발동 (WS)` 문구로 찍힌다(market-data 스레드). REST 전역 폴백도 같은 처리기로 시세를 발행하기 때문이다. 문구만의 문제이고 경로는 정상이다.
- 생성 시점 `PoolLossGuard` 위험 한도 불일치 ERROR 1줄(run2~run5 app-01)은 하네스가 운영 위험 한도 행을 넣기 전 상태 때문이다. 이후 최신 행 3.0·15.0 확인.

## 5. 하지 않은 것

- 운영 서버·DB·cron·세션 조작. 제품 코드 수정(결함을 찾지 못해 W6 착수 없음).
- 시계 주입을 운영 F5에 쓰는 것. 이 방식은 리허설 기록용이다.
