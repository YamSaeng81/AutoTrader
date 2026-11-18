# 12월 준비 11 — 실현손익 정의의 소비 경로 영향 (총괄 v1.190 §2)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 운영 접속·운영 DB·배포·제품 수정은 하지 않았다. 시험 파일 1개만 0e1d2a02 기점 작업 사본 `D:/tmp/pnldef-0e1d2a02`(가지 `test/pnl-definition-impact-0e1d2a02`, 미커밋)에 추가했고, 로컬 격리 PG `cat-rt-pg`의 새 DB `pnl_def`(Flyway 운영 마이그레이션)에서 **1개 시험·1회** 실행했다. 다른 PG 시험·전체 회귀·jar는 돌리지 않았다. 금액은 가상값이다.

**정정 메모:** [매도 PG 보충 §4](../dec-pg-sell-20261118/README.md#4-실현손익-정의--실제-pg-확인-판정-아님-제품-관찰)·[성적표 §1](../dec-scorecard-20261118/README.md)의 "차이는 손익 표시 열에만 있다"는 서술을 철회한다(원문은 보존). 아래처럼 위험 관문·연속 손실·손절·전략 판정이 같은 값을 읽는다.

## 1. 실제 호출 결과 ([run1/pnl_consumers.json](run1/pnl_consumers.json), XML 1/1 성공)

같은 경제 입력을 실제 매수/매도 경로(거래소 응답만 대역)에 넣었다: 매수 25개 = 대금 7,500 + 수수료 3.75, 매도 25개 = 대금 7,504.75 − 수수료 3.75 → 순 7,501.

| 단계 | 대상 | 저장 `realized_pnl` | 비용 기준(양쪽 수수료) | 실제 호출 결과 (한도 0.01%·연속 손실 1회로 좁혀 측정) |
|---|---|---|---|---|
| A 부호 반전 | LIVE·DYNAMIC REAL | **+1.00** (평균가 300 = 대금/수량) | **−2.75** | 손실 합 SQL 둘 다 **0**(손실 행만 세므로 통째로 빠짐) → `checkRealLossGuard`·`checkRisk` **승인**, 연속 손실 **미발동** |
| A 같은 구간 | DYN_PAPER(제품 모의 경로) | **−2.40** (평균가 = 수수료 포함 투입/수량) | −2.40 | 연속 손실 **발동**("연속 손실 1회 초과"). 같은 왕복을 REAL 관례로 계산하면 +1.60 |
| B 실제 손실 | LIVE·DYNAMIC REAL (매도 7,400−3.70) | −103.70 | −107.45 | 손실 합 −103.70(매수 수수료 3.75만큼 과소), 관문 거절(0.32%), 연속 손실 발동 |
| C 손절선 5% | `RealtimeProtectLines.stopHit` 가격 285.10 | 평균 300 → −4.9667% **미발동** | 평균 300.15 → −5.0142% **발동** | 같은 가격에서 판정이 갈림(가격 기준 정의 차이) |

즉 영향은 크기(매수 수수료만큼)뿐 아니라 **포함 여부**다. 비용 뒤 손실인데 매수 수수료 이하의 가격 이익이 난 매도는 REAL의 일·주·월 손실 합과 연속 손실에서 빠진다. 같은 거래를 DYN_PAPER는 손실로 세므로 DYNAMIC REAL/DYN_PAPER 위험 판정이 엔진마다 다르다.

## 2. 소비 경로 표 (0e1d2a02 원문 읽기 + 위 실행)

| 소비 지점 | 읽는 값 | LIVE | DYNAMIC REAL | DYN_PAPER | LIVE_ALIGNED(모의) | 확인 방법 |
|---|---|---|---|---|---|---|
| 손실 합 SQL `sumLiveSessionRealizedLossSince`·`sumDynamicRealSessionRealizedLossSince` (OrderRepository S:483·497) | 반영 대금 − 반영 수수료 − 체결 수량 × `p.avg_price`, `pnl < 0` 행만 | 수수료 제외 | 수수료 제외 | 범위 밖 | 범위 밖 | **실행** A·B |
| 기존·DYNAMIC REAL·전체 REAL 보호식 `checkRisk`/`checkRealLossGuard` (RiskManagementService S:163·211·238) | 위 SQL + 비세션 `trade_log` FILL | 수수료 제외 | 수수료 제외 | 해당 없음 | 별도 엔진(R5 관례) | **실행** A·B |
| 연속 손실 `evaluateCircuitBreaker` (S:416, `pnl < 0` 연속) | `position.realized_pnl` | 수수료 제외 | 수수료 제외 | **수수료 포함** | `riskPnl` = LIVE 관례(v1.147 R5, PaperTradingService S:1548) | **실행** A(LIVE·DYN·DYN_PAPER), LIVE_ALIGNED는 원문 |
| MDD | 세션 총자산 = 가용 + 수량×현재가 | 영향 없음 | 영향 없음 | 영향 없음 | 영향 없음 | 원문(Dynamic S:1390, Live S:1784) |
| 손절/익절 손익률·실시간 손절선 | `avg_price` | 체결가 | 체결가 | **수수료 포함** | 원문 확인 필요(S:1860 수수료 포함 평균가) | **실행** C(정적 함수), 엔진별 값은 원문 |
| 정정 후 정산 | 매수 정정은 평균가 = 정정 대금/수량(OrderSettlementService S:511), 매도 정정은 SQL이 반영값을 다시 읽음 | 같은 관례 유지 | 같은 관례 유지 | 해당 없음 | 해당 없음 | 원문 + 매도 PG L6/D6(저장−원천 = 3.75 유지) |
| 전략 중단 판정 `StrategyKillCriteriaService` (S:178) | 세션별 `SUM(realized_pnl)`, `realized_pnl > 0` 승수 | 승·손익 과대 | 승·손익 과대 | 수수료 포함 | 별도 표 | 원문 |
| 전략 가중치 `StrategyWeightOptimizer` | `findClosedPositionsForWeighting` 의 `realized_pnl`·`invested_krw` | 수익률 과대 | 수익률 과대 | 수수료 포함 | — | 원문 |
| 화면·CSV·알림 | `realized_pnl` | 표시 | 표시 | 표시 | 표시 | 원문 |

## 3. 종전 계약과의 차이

| 계약 | 당시 결정 | 이번 관측과의 관계 |
|---|---|---|
| [v1.147 R5](../aligned-v147-20261111/README.md) | LIVE_ALIGNED 위험 입력을 **LIVE 관례**(매수 수수료 제외)로 맞춰 LIVE와 같은 판정을 내게 함. 성적표 손익은 그대로 | 엔진 일치는 "둘 다 수수료 제외" 쪽으로 맞춘 것이다. 위 A처럼 비용 뒤 손실이 위험 입력에서 빠지는 성질을 LIVE_ALIGNED도 물려받았다. DYN_PAPER는 이 정렬 밖이라 DYNAMIC REAL과 다르다 |
| v1.158 결정 2 보호식 | 확정 매도 한 건씩 주문 원천으로 다시 계산, 손실 행만, 이익과 상쇄 없음 | 식 자체가 `p.avg_price`(수수료 제외)를 쓴다. "이익으로 상쇄하지 않는다"는 목적이 매수 수수료 이하 구간에서는 지켜지지 않는다(+1로 분류) |
| 성적표 공통 경제 손익(v1.190 §2) | 양쪽 수수료 포함, 주문 원천과 잔액 | 성적표 v2는 이미 이 정의다. 위험 입력과 성적표가 다른 수를 낸다 |

## 4. 최소 수정안 (제출만, 구현 안 함)

원칙: **가격 기준**(손절/익절·시작 계약이 쓰는 체결 평균가)과 **비용 기준**(손실 한도·연속 손실·전략 판정이 쓰는 손익)을 분리한다. `avg_price`·과거 행·DDL은 바꾸지 않는다.

1. **손실 합 SQL 2개**: `o.filled_quantity * p.avg_price` 를 같은 포지션 BUY 주문의 `(applied_executed_funds + applied_paid_fee) / filled_quantity` 단가로 바꾼 비용 기준 식(새 열 없음, 조인만). 예상 효과: A의 −2.75가 손실 합에 들어간다.
2. **연속 손실(REAL)**: `position.realized_pnl` 대신 같은 포지션의 주문 원천 비용 기준 손익(매도 순대금 합 − 매수 대금+수수료 합)으로 판정. DYN_PAPER는 이미 비용 기준이라 변화 없음 → DYNAMIC REAL/DYN_PAPER 일치.
3. **LIVE_ALIGNED `riskPnl`**: R5를 뒤집어 비용 기준으로 맞춘다(LIVE가 1·2로 바뀌므로 같이 바뀌어야 일치 유지).
4. **가격 기준 정렬(별도 판단)**: DYN_PAPER/LIVE_ALIGNED 손절선이 수수료 포함 평균가를 쓰는 차이는 비용이 아니라 가격 기준 문제다. 모의 쪽 판정 평균을 체결가로 맞추는 안과 그대로 두는 안을 따로 판정받는다(SL/TP·시작 계약 변경).
5. **전략 중단·가중치**: 1·2와 같은 비용 기준 손익을 쓰는 읽기로 바꾸는 후보. 판정 표본 수·과거 지문 비교가 바뀌므로 별도 후보.

영향: 1~3은 한도를 더 엄격하게 만든다(왕복당 매수 수수료 0.05%만큼). 운영 한도 값·F 핀·전역 손실 SQL은 이번에 바꾸지 않았다. 후보 구현은 총괄 판정 뒤 별도 가지에서 하고, 그때 위 A·B·C를 회귀 입력으로 쓴다.

## 5. 재현·환경

```
docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS pnl_def" -c "CREATE DATABASE pnl_def"
SR_PG_URL=jdbc:postgresql://localhost:15432/pnl_def PNL_OUT=<run 폴더> ./gradlew :web-api:test --tests '*RealizedPnlDefinitionPgTest'
```

Windows, JDK 17.0.12, `cat-rt-pg`(127.0.0.1:15432). 시험 원문 [source/RealizedPnlDefinitionPgTest.java](source/RealizedPnlDefinitionPgTest.java), 결과 XML·네트워크 차단 요약은 [run1](run1/). 체크섬은 [SHA256SUMS](SHA256SUMS).

## 6. 한계

- 실행은 LIVE·DYNAMIC REAL·DYN_PAPER 한 왕복씩과 정적 손절 함수다. LIVE_ALIGNED 경로·전략 중단/가중치·화면은 원문 읽기다.
- 측정 때만 한도를 0.01%·연속 손실 1회로 좁혔다(거래 중에는 50%·5회). 운영 한도 값의 판정이 아니다.
- 비용 기준 값은 산술 비교값이며 제품 실행 결과가 아니다.
- DYN_PAPER 왕복은 모의 슬리피지 때문에 가격·수량이 REAL과 다르다. 같은 "비용 뒤 손실·수수료 제외 이익" 구간에 들도록 매도가를 계산했다.
