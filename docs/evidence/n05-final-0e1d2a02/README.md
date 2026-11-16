# N05 최종판 — K14~K23을 운영 SHA 0e1d2a02에 대조 (2026-11-16, 로컬, 총괄 판정 전)

총괄 [v1.176 §4-2](../review-7fea1b89/README.md)에 따라, 후보판 연결표를 운영 반영이 수용된 **0e1d2a02**(backend 2c5d8427)의 코드 위치에 다시 대조했다. 후보판 연결표는 [K14~K19](../n05-links-88f06345/README.md)와 [K20~K23](../g1-submit-boundary-20261113/README.md) §7이다.

- Git 블롭만 읽었다. 작업 사본과 빌드 산출물은 쓰지 않았다.
- 운영 서버·DB는 조회하지 않았다.
- 같은 입력의 회귀·PG·jar 확인은 반복하지 않았다. 시험 근거는 이미 수용된 결과를 연결했다.

## 1. 방법

`n05map.py`가 다음 순서로 대조한다.

1. 후보판 표의 39개 위치 `(번호, 클래스, 줄)`에 대해 88f06345와 0e1d2a02의 같은 경로 블롭을 읽는다.
2. 두 블롭을 difflib로 정렬한다. **같은 줄 구간 안에서만** 줄 번호를 옮긴다. 바뀐 구간의 줄은 '대응 없음'으로 처리한다.
3. 옮긴 운영 줄의 원문이 후보판 줄과 바이트로 같은지 확인한다.
4. 후보판 표에 적힌 메서드 이름이 그 줄 또는 ±5줄 안에 있는지 확인한다. 떨어진 거리도 출력에 남긴다.

종료값 0은 39개 위치가 모두 대응하고 토큰도 확인됐다는 뜻이다.

| 실행 | 결과 | 원문 |
|---|---|---|
| 1차 | `-I`가 `PYTHONIOENCODING`을 무시해 cp949 출력 오류가 났다. 표는 K20까지만 출력됐다(rc1). 판정 로직은 바꾸지 않았다 | [runs/n05map-0e1d2a02.run1-cp949.txt](runs/n05map-0e1d2a02.run1-cp949.txt) |
| 2차 | 표준 출력을 UTF-8로 고정했다. 토큰은 같은 줄만 보던 것을 ±5줄로 넓혔다. 1차에서 `OEE:563`·`OEE:664`가 선언 줄 바로 옆 줄을 가리킨 것을 확인했기 때문이다. **39/39 대응, 불일치 0, rc0** | [runs/n05map-0e1d2a02.txt](runs/n05map-0e1d2a02.txt) |

## 2. 결과

| 파일 | 88f06345 → 0e1d2a02 |
|---|---|
| LiveTradingService·DynamicTradingService·PaperTradingService·PaperTradingController·RealtimeProtectionDispatcher·RiskManagementService·SessionBalanceUpdater·DynamicSessionBalanceUpdater | **블롭 같음**(8파일) |
| OrderExecutionEngine | 블롭 ebd2b2ba → 033eeed2, 2647 → 2697줄(19d1cc61) |
| OrderRepository(표 위치 없음) | 메서드 1개 추가(19d1cc61) |

| 번호 | 운영 0e1d2a02 위치 | 후보판과 차이 |
|---|---|---|
| K14 청산 대기·매도 의도 | `OEE:597`, 호출 6곳 `LTS:1757`·`DTS:2020`·`LTS:1822`·`DTS:2558`·`LTS:2142`·`DTS:2753` | 없음 |
| K15 커밋 뒤 제출 | `OEE:563`(`submitPreparedOrderAfterCommit` 선언 다음 줄), `OEE:664`(`submitPreparedOrder`의 `@Async` 줄) | 줄 번호는 같다. 후보판 표기가 선언 줄에서 1줄 떨어진 것을 이번에 명시했다. 제출 결과 기록은 아래 §3에서 바뀌었다 |
| K16 재기동·작업 유실 복원 | `OEE:1884` `pollActiveOrders`, `OEE:2001` `resolveUnresolvedSubmission`, `OEE:2059` DISPATCHING 경보. 청산 확인 `LTS:2833`·`DTS:2835`, 정리 `LTS:2035`·`DTS:2646`, 시작 대조 `LTS:2469`·`DTS:404` | **OEE 세 위치가 +37줄 이동**. 원문은 같다. 식별자 조회 뒤 갱신은 §3에서 바뀌었다 |
| K17 시세 전달·보호선 | `RPD:76`·`RPD:85`, `LTS:3393`·`DTS:3464` | 없음 |
| K18 실현손익·phantom | `SessionBalanceUpdater:89`·`DynamicSessionBalanceUpdater:61`, `LTS:2810` | 없음 |
| K19 실자금 손실 보호식 | `RMS:211` | 없음 |
| K20 정지 → 청산 대기 | `PTS:344`·`PTS:331`, `PaperTradingController:133` | 없음 |
| K21 재기동 복원 | `PTS:1406` | 없음 |
| K22 첫 유효 가격 정산 | `PTS:1666`·`1249`·`763`·`798`·`1418`·`1368` | 없음 |
| K23 삭제 거절 | `PTS:479`·`487`·`502` | 없음 |

## 3. 운영판에서 바뀐 안쪽 동작 — 19d1cc61 (K15·K16)

연결 경로와 호출 위치는 그대로다. 같은 경로 안의 상태 갱신 세 곳에 행 잠금 재읽기와 조건부 갱신이 더해졌다. 차분 원문은 [runs/product-diff-88f06345-0e1d2a02.patch](runs/product-diff-88f06345-0e1d2a02.patch)이고, 제품 차분은 이 2파일뿐이다.

| 위치(0e1d2a02) | 전 | 후 |
|---|---|---|
| 제출 오류 경로(K15, `OEE:1023` 근처) | `findById` 뒤 무조건 UNKNOWN | `findByIdForUpdate` 뒤 **SUBMITTING일 때만** UNKNOWN. 다른 경로가 먼저 갱신했으면 경고만 남긴다 |
| 제출 결과 기록 `recordSubmitOutcome`(K15) | `findById` 뒤 무조건 주문번호·결과 반영 | 잠금 재읽기 뒤 **미해결(SUBMITTING·UNKNOWN)이고 같은 주문번호일 때만** 반영한다. 사람이 조정한 UNKNOWN(V91)은 관측만 남긴다 |
| 식별자 조회 뒤(K16, `resolveUnresolvedSubmission` 안) | 조회 전에 읽은 엔티티를 조건 없이 저장 | `touchUnknownProbeIfUnresolved`가 1행일 때만 다시 읽고 전이한다. 0행이면 조회 결과를 쓰지 않는다 |

정지·비상정지·정산과의 연결은 바뀌지 않았다.

- 정지 뒤 BUY 철회(`WITHDRAWN_STOPPED`)와 SELL 우선은 claim 경로이고, 이번 변경 밖이다.
- 철회 → 청산 확인 → OPEN → 정리의 회복 경로도 그대로다.
- 바뀐 것은 제출 응답과 폴러가 같은 주문 행을 동시에 갱신할 때 **나중에 온 쪽이 앞선 결과를 덮지 않는 것**이다.

이 변경의 시험 근거는 이미 수용된 결과다. 이번에 다시 실행하지 않았다.

- [415차 v167-race](../v167-race-20261115/README.md): SubmitPollRaceTest 11/11
- [417차 v168-final](../v168-final-20261115/README.md): H2 `SubmitPollRaceTest` 13/13(A~E·H×2·종류 6), 실제 PG `SubmitPollRacePgTest` 16/16(상속 13 + PG 전제·F·G), 0e1d2a02 전체 1,814 = 성공 1,725·건너뜀 89·실패 0

## 4. 남은 공백(G2~G4)

| 번호 | 상태 |
|---|---|
| G1 | 종결(수용 그대로) |
| G2 과거 정리 시험 60초 지연 | **미확정 그대로.** 이번 대조는 코드 위치 대응이며 원인 근거가 아니다 |
| G3 K15·K16 실제 주기의 회복 시간 | **미확정 그대로.** 운영의 5초·10초 주기 회복은 관측 근거가 없다. 운영 관측 칸(정리 대기·`STALE_INTENT_ALERT`·`WITHDRAWN_*` 건수)에서 실제 발생을 읽기 전에는 닫지 않는다. 이번 세션은 그 값을 읽지 않았다 |
| G4 최종 운영판 | **줄 대응 확인을 제출한다.** 39/39 대응, OEE 세 위치 +37줄, 안쪽 변경은 §3. 판정은 총괄 몫이다 |

11-23~27 최종판 확인 일정에서 위 결과를 재사용하려면, 그때 운영 SHA가 여전히 0e1d2a02여야 한다. 바뀌었으면 같은 도구로 새 SHA를 대조한다.
