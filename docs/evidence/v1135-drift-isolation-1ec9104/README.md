# ExecutionDriftTrackerTest 기존 실패 — 원인 확정·시험 격리·최종 SHA 1ec9104 전체 시험 (총괄 v1.135 §3, 2026-11-08~09, 로컬)

**개발 결과다. 총괄 판정 전이다.** 서버·운영 DB에는 닿지 않았다. 제품 코드는 바꾸지 않았다. 바꾼 것은 시험 파일 1개다. 기존 r5·r6 실패 원문은 `o1o3-candidate-1899b49/runs/`에 그대로 있다.

## 결론

**원인은 시험의 행 선택과 데이터 격리였다. 저장 계산은 맞다.** 같은 Spring 컨텍스트와 H2를 쓰는 앞선 클래스가 drift 행을 남겼다. 이 클래스에서 가장 먼저 도는 `record_negativeSlippage`가 `findAll().get(0)`으로 그 행을 읽었다.

- `@AfterEach deleteAll` 때문에 같은 클래스의 나머지 시험은 빈 표에서 시작한다. 그래서 첫 시험만 실패했다.
- r5·r6·r13 모두 이 메서드가 클래스 안에서 첫 번째로 돌았다.

## 1. 실제 행 추적 — r13 (0d142aa + 진단 출력만, web-api 전체, `code/diag_patch.py`)

진단 출력은 행 덤프만 추가했다. 단언은 바꾸지 않았고 커밋하지 않았다. 실패는 기존과 같은 1건이다(1140건: 통과 1116·건너뜀 23·실패 1). 건너뜀이 4건 많은 것은 0d142aa의 N15 PG 시험 4건이 환경 변수 없이 건너뛰었기 때문이다.

`r13-drift-diag-lines.txt`의 내용:

| 시점 | 행 |
|---|---|
| 기록 전 | **id=1 LIVE sid=151 KRW-BTC DAILY_SMA20 SELL 신호 11800000.00 = 체결 11800000.00, slippage 0.000000**(14:50:29.295Z) — 앞선 클래스가 남긴 행 |
| 기록 뒤 | id=1(위와 같음) + **id=2 sid=2 KRW-ETH 신호 50000000.00 체결 49900000.00 slippage −0.200000**(방금 저장한 행) |
| 조회 | `get(0).id=1` → 단언이 0.000000을 읽음 |
| 나머지 5개 시험 직전 | 행 0 |

- 방금 저장한 행의 −0.200000은 `(체결−신호)/신호×100`과 같다. **저장 계산 결함은 없다.**
- 남은 행을 만든 클래스는 시각으로 추정했다. `DailySma20LiveOrderPathTest`가 14:50:26 시작·2.4초 실행이고 DAILY_SMA20 LIVE 매도 경로를 쓴다. 행의 시각은 클래스가 끝난 직후다. **확정은 아니다.** 그 클래스는 이번 범위가 아니어서 고치지 않았다.

## 2. 고정 오염 탐침 — 수정 전 실패 먼저, r14 (0d142aa 원래 클래스 + 탐침 1개, 이 클래스만, `code/ExecutionDriftTrackerTest.probe.java`)

탐침은 다른 세션의 slippage 0 행을 먼저 저장한다. 그다음 원래와 같은 음수 입력을 기록하고, 원래와 같은 `findAll().get(0)`으로 읽는다.

- **탐침 실패: `Expecting actual: 0.000000 to be less than: 0`.** 전체 실행의 실패 원문과 같다. 행은 id=9(sid 900, 0)와 id=10(sid 2, −0.200000)이었다.
- 같은 실행에서 원래 6개 시험은 모두 통과했다. 클래스만 따로 돌리면 앞선 행이 없으므로 원래 음수 시험도 통과한다. 순서와 오염에 따라 결과가 달라진다는 뜻이다.
- 실행 순서 고정, sleep, 반복 성공 선택, 실패 제외는 쓰지 않았다.

## 3. 수정 — 1ec9104 (`code/0d142aa-to-1ec9104.diff`, 시험 1파일 +40/−2)

- `@BeforeEach isolate()`: 각 시험 전에 drift 행을 비운다. `@AfterEach`는 그대로 둔다.
- `recorded(sessionId, coinPair)`: 방금 기록한 세션·코인의 행만 고르고 **정확히 1개**를 요구한다. 두 record 시험이 이것으로 읽는다.
- 새 시험 `record_negativeSlippage_withEarlierZeroRow`: 고정 오염 탐침과 같은 입력이다. 오염 행이 남아 있는 상태(`findAll` 2행)에서 대상 행이 음수임을 확인한다. 정리가 아니라 행 선택으로 구분한다는 대조다.
- 음수 단언(`< 0`), 양수와 1% 미만 단언, `hasSize(1)`은 그대로다. 0을 허용하지 않았다.
- 제품 코드(main) 차이는 0이다. 0d142aa→1ec9104는 시험 파일 1개뿐이다.

## 4. 결과

| 실행 | 사본 | 대상 | 결과 |
|---|---|---|---|
| r15 | 0d142aa + 수정 클래스(커밋 전, 커밋 파일과 바이트 같음) | 이 클래스 | **7/7** — 원래 6개 + 오염 탐침 |
| **r16** | **1ec9104 깨끗한 사본**(상태 0바이트) | **web-api 전체** | **1141건: 통과 1118·건너뜀 23·실패 0** (11-08 23:55~11-09 00:14 KST) |

`full-compare-r5-r16.txt`는 r5(b9f16a6, 1136건)와 r16을 이름 기준으로 비교한 것이다.

- 결과가 다른 것은 세 종류뿐이다.
  - N15 PG 4건 추가: 환경 변수가 없어 건너뜀. 실제 PG 4/4는 기존 r10·r11 근거다.
  - 오염 탐침 1건 추가: 통과.
  - `record_negativeSlippage`: FAIL에서 PASS로.
- 그 밖의 공통 시험은 결과 차이가 0이다.
- 건너뜀 23건의 구성은 다음과 같다. **실제 실행으로 세지 않는다.**
  - 기존 19건: SettlementRoundingPg 8, HoldBaselineSqlEquivalencePg 6, HoldBaselineLoopPg 4, PullbackMtfBacktestRunner 1. PG 환경 미설정이거나 수동 실행 대상이다.
  - N15 PG 환경 미설정 4건.

전체 시험은 최종 SHA에서 한 번만 돌렸다. 새 원인이 나오지 않았으므로 반복하지 않는다.

## 파일

- `runs/r13…r16/`: 실행마다 HEAD, 작업 사본 상태, 소스 사본과 해시, gradle.log, XML, 종료값. r13·r16은 콘솔 출력도 있다.
- `code/`
  - `ExecutionDriftTrackerTest.0d142aa.java`: 원래 파일.
  - `.probe.java`, `mkprobe.py`
  - `.fixed.java`: 1ec9104 커밋 파일과 바이트가 같다.
  - `diag_patch.py`
  - `commit-1ec9104.txt`, `0d142aa-to-1ec9104.diff`
- `r13-drift-diag-lines.txt`, `full-compare-r5-r16.txt`
