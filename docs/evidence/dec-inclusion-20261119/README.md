# 12월 준비 18 — 포함표·통합 충돌 확인·필수 검증 순서 (총괄 v1.191 §4)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 세 후보를 한 번에 판정받기 위한 표다. **최종 배포 SHA·전체 회귀·jar/이미지는 고정하지 않았다**(지시). 원래 후보 가지는 그대로 두고, 통합은 별도 로컬 사본에서만 확인했다. 운영 변경 없음.

## 1. 포함표

| # | 후보(기점) | 제품 차분 | 시험 차분 | 설정·DDL·빌드 | 지금까지의 근거 | 상태 |
|---|---|---|---|---|---|---|
| A | **aff357e2**(0e1d2a02 ← 18f3a795 모드 ← ac52715e 쿨다운 ← 금액/BTC) | 3파일 +253/−28: `DynamicSessionRequest`·`DynamicTradingService`·`DynamicEntryLimits`(신규) | 8파일 +643/−18 | 없음(`strategy_params` JSONB는 V96 기존 열) | [모드/쿨다운](../dec-modecd-20261118/README.md) 101성공 · [금액/BTC](../dec-amtbtc-20261118/README.md) H2 59/59 · [PG](../dec-amtbtc-pg-20261119/README.md) 1/1 | 로컬 범위 수용, 원격 보존 |
| B | **dfe3b9f7**(0e1d2a02) 최소 알림 | 5파일 +87/−23: `TelegramNotificationService`·호출 4파일 | 1파일 +146 | 없음 | [최소 알림](../dec-min-alert-20261119/README.md) XML 8개 81성공 | 로컬 범위 수용, 원격 보존 |
| C | **b861bdf9**(0e1d2a02) 비용 기준 손실 보호 | 6파일 +218/−22: `OrderRepository`·`RiskManagementService`·`CircuitBreakerResult`·`LiveTradingService`·`DynamicTradingService`·`PaperTradingService` | 8파일 +635/−13 | 없음(조회문만, DDL 없음) | [비용 기준](../dec-costbasis-20261119/README.md) PG 기점 15/48 → 48/48, 영향 H2 214/215+13/13, PG 35/35 | **제출, 판정 전**. 로컬만 |

12월 새 실자금 시작에 필요한 조합은 A+B+C다. 지시서는 C를 시작 전 필수로 두고 있다. A가 없으면 동적1은 BTC를 살 수 있어 SID203 장부와 섞인다([운용안](../dec-ops-plan-20261119/README.md) §1).

## 2. 통합 충돌 확인 (로컬 사본 `D:/tmp/integ-check`, 가지 `integ/check-aff357e2-dfe3b9f7-b861bdf9`, HEAD `9cbbda62`)

- aff357e2 위에 B(1커밋)와 C(5커밋)를 차례로 cherry-pick했다. **문자 충돌 0**이다. 6커밋 모두 원래 커밋과 patch-id가 같아, 옮기면서 바뀐 줄이 없다([integration-commits.txt](integration-commits.txt)).
- 합친 차분은 0e1d2a02 대비 제품 10파일 +558/−73([integration-product.diff](integration-product.diff)), 시험 17파일 +1,424/−31이다. `src/main/resources`·Flyway·Gradle·compose 변경은 0이다.
- `compileJava`·`compileTestJava` 성공([compile.log](compile.log), 경고는 기존 SecurityManager·deprecation). **시험은 이 통합 사본에서 돌리지 않았다.**
- 두 후보가 같이 손대는 곳은 `DynamicTradingService.executeBuy`(A 제외 코인·금액 상한, C 판정 불가 차단)와 `LiveTradingService`·`PaperTradingService`·`OrderExecutionEngine`(B 표시 함수, C 진입 차단·`riskPnl`)이다. 통합본 `executeBuy` 순서는 제외 코인(A) → 실자금 손실 보호식 → 판정 불가(C) → 금액 상한(A) → 최소 주문이다. 조회 없는 거절을 먼저 하고 손실 관문이 금액 계산 앞에 있어, 의미 충돌은 보이지 않는다(원문 읽기).

## 3. 필수 검증 순서 (제안, 판정 뒤 실행)

| 순 | 무엇을 | 어디서 | 통과 기준 |
|---|---|---|---|
| 1 | 포함 판정에 따라 통합 가지를 확정(A+B+C 또는 일부) | 로컬 | 원래 후보와 patch-id 같음 |
| 2 | 후보별 좁은 시험을 통합본에서 한 번: A 모드·쿨다운·금액/BTC H2, B 텔레그램 5클래스, C `CostBasisRiskPgTest`+영향 PG 4클래스 | 로컬 H2·격리 PG | 각 후보 단독 결과와 같은 수 |
| 3 | 교차 시험: DYNAMIC REAL 상한 세션의 비용 기준 손실 → 판정 불가/거절 순서, `종류#ID` 요약에 비용 기준 거절 사유가 섞이지 않음 | 격리 PG | 새 시험으로 고정(아직 없음) |
| 4 | 최종 SHA 고정 → 전체 회귀 1회(H2 전체 + PG 묶음) | 로컬 | 실패 0, 건너뜀 사유 목록 |
| 5 | jar/이미지 빌드, 리소스 LF·해시 대조(기존 배포 대조 규칙) | 로컬 | 기존 규칙 |
| 6 | 배포 전 읽기 [predeploy_read.sql](../dec-costbasis-20261119/predeploy_read.sql) R1~R3(판정 불가 수·손실 합 대조·RUNNING REAL 최근 청산) | 운영 읽기(별도 승인) | R1 = 0이 아니면 배포 전에 판단 |
| 7 | 운영 설치·F 핀 갱신·N03/F1/F3 새 계약 연결 | 운영(별도 판정) | 별도 판정 |

## 4. 판정이 필요한 것

1. 포함 범위(A·B·C 각각)와 C의 판정 불가 해제 경로(지금은 30일 창에서 빠질 때까지 신규 REAL 진입 차단)
2. 기존 손실률 0.01% 해상도를 이번 범위 밖으로 둘지
3. kill criteria 비용 기준 읽기(별도 포함안)를 12월에 넣을지 — 자동 정지 기본 OFF, 가중치는 선택 전략에 영향 없음([비용 기준 §5](../dec-costbasis-20261119/README.md#5-전략-중단가중치--소비-경로와-자동-적용-조건-별도-포함안-이번-후보에-안-넣음))

체크섬 [SHA256SUMS](SHA256SUMS).
