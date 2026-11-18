# 12월 준비 7 — 모드 18f3a795 + 쿨다운 89336440 로컬 통합 (총괄 v1.187 §3-3, v1.188 §3-4)

2026-11-18 KST, 개발 세션 제출(총괄 판정 전). 원본 가지(18f3a795·89336440)·main 제품·운영 핀은 바꾸지 않았다. 운영 접속·배포·PG·전체 회귀·jar는 하지 않았다.

## 1. 통합

| 항목 | 내용 |
|---|---|
| 새 가지 | `candidate/dynamic-mode-cooldown-0e1d2a02` = **ac52715e**(작업 사본 `D:/tmp/modecd-0e1d2a02`, 원격 푸시 안 함) |
| 방법 | 0e1d2a02 → 18f3a795(모드 입력 엄격화) 위에 89336440(BLACK_SWAN 쿨다운 종류별 격리)을 `cherry-pick`. **충돌 없이 자동 병합** |
| 변경 동일성 | 통합 커밋의 patch-id `da9b0c8c…` = 89336440의 patch-id. 18f3a795는 그대로 부모 |
| 차분 | [candidate-ac52715e.diff](candidate-ac52715e.diff)(0e1d2a02..ac52715e, 9파일 +306/−44, sha256 7f99b20a…) |

두 변경은 모두 `DynamicTradingService.java`를 고치지만 위치가 다르다: 모드는 `createSession` 첫 단계(정확한 REAL/PAPER만), 쿨다운은 `blackSwanBlockedAt/Price` 지도와 기록·조회·만료·복원(세션 종류 키). 쿨다운은 세션 종류(`DYNAMIC`=REAL / `DYN_PAPER`=PAPER)로 지도를 고르고, 그 종류는 모드가 정확히 PAPER인지로 정해진다. 종전에는 `paper` 같은 오타가 실자금(DYNAMIC 지도)으로 들어갈 수 있었으나, 모드 엄격화로 그런 입력은 생성 단계에서 거절된다.

## 2. 범위 시험 (H2, PG 환경 변수 없음)

`./gradlew :web-api:test --tests` 12클래스, 종료값 0, 56초. [XML 12개](xml/) · [콘솔(CP949→UTF-8 변환)](gradle-console.utf8.txt)

| 묶음 | 클래스 | 통합 ac52715e | 단독 결과 |
|---|---|---|---|
| 모드(7) | DynamicTradingModeInputTest 15 · DynamicSessionPaperVisibilityTest 8 · CapitalPoolLimitTest 8 · SessionKindIsolationTest 8 · StrategyEnablementGateTest 4 · WalkForwardGateDisabledSessionCreationTest 1 · WalkForwardGateEnabledSessionCreationTest 4 | **48 성공** | 18f3a795 48 성공 |
| 쿨다운(5) | DynamicBlackSwanCooldownCrossKindTest 6(격리 계약) · DynamicBlackSwanCooldownRestoreTest 7 · DynamicBlackSwanPriceGuardTest 7 · DynamicPaperLiveRuleParityTest 6 · EngineParityTest 27 | **53 성공** | 89336440 53 성공 |
| 합계 | 12클래스 | **101 = 성공101·실패0·건너뜀0** | |

수정 전 실패 재현은 각 단독 후보의 기존 근거(모드 red 13실패, 쿨다운 0e1d2a02 간섭 재현)로 갈음했고 다시 돌리지 않았다.

## 3. 한계·남은 것

- 범위 시험만 돌렸다. 전체 회귀·PG·jar·운영 판정 전이다. 통합 가지는 로컬에만 있다.
- 기존 DYNAMIC 행의 모드 값·재기동 복원은 18f3a795와 같이 바꾸지 않았다.
- 금액(maxOrderKrw)/BTC 제외 독립 후보·월 성적표·최소 알림 보완안은 다음 결과로 남긴다.
