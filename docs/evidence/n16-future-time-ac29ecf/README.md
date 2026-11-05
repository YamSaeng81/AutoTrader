# N16 보완 — 시세 장부 미래 관측 시각 제한 (총괄 v1.106 지시, 11-05 오후)

- 기점: N16 후보 `3385f70`(부모 운영 `fe01207`). 작업 사본 `D:\Claude Code\projects\cat-n15`.
- **후보 `ac29ecf11da9529fd3d83a6b28ae8756c4e89c9a`**(브랜치 `candidate/n16-invalid-price-fe01207`, 원격 반영). 부모는 3385f70이고, 운영 코드 1파일(`MarkPriceBook`)과 시험 1파일을 바꿨다. fe01207 대비 누적은 운영 코드 3파일 +52/−8(`diff-fe01207-ac29ecf-main.txt`)이다. N15 시험 파일은 이 커밋에 없다(작업 사본에 미추적으로만 있음). 미배포 V95는 없다.
- 시간(KST): 착수 13:33:11, 정적 대조 ~13:35, 수정 전 1차 13:35:53~13:36:22(시험 설계 실패 포함), 수정 전 2차 13:36:39~13:37:10, 수정 후 1차 13:37:46~13:38:44(시험 코드 실패), 수정 후 2차 13:39:05~13:40:03(80/80), 확정 시험 파일로 수정 전 재실행 13:40:10~13:40:46(4/13 실패), 커밋 13:40:55, 정리 ~13:45.
- 운영 서버·DB·외부 출처 조회와 배포·재빌드는 없었다.

## 1. 변경 (`diff-3385f70-ac29ecf-main.txt`)

- `Mark` 레코드에 `observedAt`(원래 관측 시각, 기록용) 필드를 더했다. `at`은 **장부 기준 시각 = min(원래 관측 시각, 서버 수신 시각)** 으로 저장한다. 두 인자 생성자(시험·`record`)는 `observedAt = at`이다.
- 체결 `observe`와 호가 `observeBook` 모두 같은 보정을 쓴다. 수신 시각이 `null`이면 `Instant.now()`를 한 번 읽어 수신 시각과 보정 기준에 같이 쓴다.
  - 운영 발행 지점은 모두 수신 시각을 넘긴다. `onPrice`는 `now`를 한 번 읽어 넘기고, `HeldMarkRefresher`는 회차 `now`를 넘긴다.
- 과거 관측은 올리지 않는다. 낡은 체결가를 다시 받아도 원래 체결 시각 그대로라서 낡은 값으로 남는다.
- 정렬(`put`의 merge)은 새 값과 기존 값 모두 보정된 `at`으로 비교한다. 같은 유효 시각이면 기존처럼 나중 값이 이긴다.
- 소비자(`freshPriceFor`, `HeldMarkRefresher` 대상 선정, `PoolLossGuard.markFor`의 `PriceMark.at`, `PositionLimitGuard`의 나이 판정, DYNAMIC 표시)는 모두 `Mark.at()`을 읽는다. 그래서 소비자 코드는 바꾸지 않았고, 모두 보정 시각을 본다.
- 바꾸지 않은 것:
  - 120초 기준과 초 단위 경계
  - 실시간 처리기의 N02 낡음 판정(이벤트의 원래 관측 시각 사용)
  - 이벤트 객체
  - 웹소켓 파서
  - 주문 경로
  - 마이그레이션
- 새 허용 오차는 없고, 미래 값을 거절하지도 않는다. 이 보정이 OS 시계 역행·시계 동기화 장애 전부를 해결하지는 않는다(클래스 주석에 적음).

## 2. 시험 (`tests/N16InvalidPriceReproTest.java`, 13건 = 기존 ①②③⑤ 8건 + 새 ④a~④e 5건)

기존 ④ `futureObservationFreezesBook`은 고정 현상을 정상으로 단언하던 **현재 동작 기록**이었다. 새 계약에서는 그 기대가 반대가 되므로 ④a~④e로 바꿨다. 옛 ④의 원문 결과는 `../n16-invalid-price-fe01207/before`·`after` XML에 그대로 있다.

| 사례 | 확인 | 수정 전 3385f70 (`before/`) | 수정 후 ac29ecf (`after/`) |
|---|---|---|---|
| ④a 체결 회복 | 미래(+1일) 100 → 정상 90(+1초) | **장부 시각 = 미래, 90 버려짐, 2초 뒤 100** | 장부 시각 = 수신 시각, 원래 관측은 `observedAt`, 90이 덮음 → 90 |
| ④b 체결 만료·평가·보강 | 후속 관측 없음 | **121초·23시간 뒤에도 100, 포지션 판정 정상(null), 보강 대상 [], 조회 0회** | 120.999초 100, 121초 null, 23시간 null, 풀 평가 시각 = 수신 시각, 포지션 `MARK_STALE`, 보강 대상 포함, 시세 조회 1회 |
| ④c 과거·순서(보존 확인) | 10분 전 체결 재수신, 더 이른 관측, 같은 유효 시각 | 통과(기존 동작) | 통과. 10분 전 값 낡음, 더 이른 1 거절, 같은 유효 시각 281이 덮음 |
| ④d 호가 | 미래 호가 → 121초 → 정상 호가 | **장부 시각 = 미래, 121초 뒤 100, 정상 호가 버려짐** | 장부 시각 = 수신 시각, 121초 뒤 null, 정상 호가가 덮음 → 90 |
| ④e 생산 이벤트·수신 없음 | 애플리케이션 이벤트로 미래 시각 290 발행, `receivedAt=null` 직접 기록 | **장부 시각 = 미래, 수신 null** | 장부 시각 = 수신 시각(호출 구간 안), 매도 0, 수신 없는 기록은 한 번 읽은 서버 시각 |
| ①②③⑤ | 가격 null·0·음수, EPOCH·null 시각 | 통과 | 통과. 0·−1 매도 0, null 뒤 정상 280 매도 1, `tp` 없는 ticker 매도 0, EPOCH 버림 |

- 수정 전: 13건 중 4건 실패(④a·b·d·e). 원문은 `before/TEST-…N16InvalidPriceReproTest.xml`, `before/gradle-before.log`, 실패 줄 발췌 `before/failures.txt`에 있다. 확정 시험 파일로 운영 코드만 3385f70 상태로 되돌려 실행했다.
- 수정 후: 관련 10클래스 80/80. N16 13건이 그 안에 포함된다(80 = 이전 76 − 옛 ④ 1 + 새 ④ 5). 클래스 목록은 3385f70 때와 같다(`after/TEST-*.xml` 10개).
- 실행 명령(작업 사본, 표준 출력 UTF-8 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 …`):
  - 수정 전: `./gradlew :web-api:test --tests "com.cryptoautotrader.api.service.N16InvalidPriceReproTest" --offline -i`
  - 수정 후: 같은 명령에 `CandleGapExitTest`, `DynamicPaperLiveRuleParityTest`, `DynamicTradingSlWatchdogRecoveryTest`, `LiveTradingSlWatchdogRecoveryTest`, `PoolLossLimitTest`, `PositionLimitTest`, `SlWatchdogFreshnessTest`, `StaleRealtimeExitTest`, `StopVersusInFlightExitRaceTest`를 `--tests`로 더했다.

## 3. 실패 원문 (`fail-runs/`)

- `run1-event-path-design.log`(수정 전 1차): ④e가 `send()`로 처리기를 직접 불렀다. 그래서 장부 `@EventListener onPrice`에 이벤트가 닿지 않았다(`이벤트 장부 null`). 시험 설계 실수이고, 운영 발행처럼 `ApplicationEventPublisher.publishEvent`로 고쳤다. 나머지 3건 실패 내용은 2차와 같다.
- `run3-valueof-overload.log`(수정 후 1차, 79/80): ④b의 `String.valueOf(i.getArgument(0))`가 제네릭 추론으로 `valueOf(char[])`에 묶였다. 수정 후 처음으로 실제 시세 조회가 일어나면서 `ClassCastException`이 났다. 시험 코드 결함이고 `(Object)` 형변환으로 고쳤다. 옛 ④에도 같은 식이 있었지만 조회가 0회라 드러나지 않았다.

## 4. 남은 것

- 운영 fe01207에는 null·0·음수 처리기 결함과 미래 시각 장부 고정이 그대로 있다. 이 후보는 배포하지 않았다.
- N15 후보와의 통합, 배포·복구 절차, jar 대조 기대값은 두 후보 수용 뒤의 별도 판정 사항이다.
- 실시간 처리기는 미래 시각 이벤트라도 가격이 정상이면 그 틱 한 번은 그 가격으로 판정한다. 이것은 기존 N02 정의 그대로이고, 고정 문제는 장부에만 있었다.

## 5. 파일

| 파일 | 내용 |
|---|---|
| `candidate-sha.txt` | 후보 SHA·부모·제목, fe01207 대비 변경 통계 |
| `diff-3385f70-ac29ecf-main.txt` / `diff-fe01207-ac29ecf-main.txt` | 운영 코드 diff(이번 보완 / 누적) |
| `tests/N16InvalidPriceReproTest.java` | 후보 커밋의 시험 원본 사본 |
| `before/` | 수정 전 XML·gradle 원문·`[N16]` 출력 요약·실패 발췌 |
| `after/` | 수정 후 10클래스 XML·gradle 원문·요약 |
| `fail-runs/` | 시험 설계/코드 실패 원문 2개 |
