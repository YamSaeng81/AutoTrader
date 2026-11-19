# 12월 준비 16 — 비용 기준 손실 보호 독립 후보 (총괄 v1.191 §2)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 후보 가지 **`candidate/cost-basis-risk-0e1d2a02`**(작업 사본 `D:/tmp/costbasis-0e1d2a02`, 부모 0e1d2a02, **로컬 커밋만·푸시 안 함**). 운영 접속·DB·배포·설정 변경은 하지 않았다. 금액은 가상값이다.

| 커밋 | 내용 |
|---|---|
| `09bf2eb5` | 회귀 시험 [CostBasisRiskPgTest](source/CostBasisRiskPgTest.java) 먼저 추가(제품 변경 없음). 커밋 문구의 "통과 16"은 오기이며 실제는 통과 15·실패 33([base-fail](base-fail/)) |
| `d55fa916` | 시험 측정 보정: 단계마다 앞 세션 STOPPED(분모 고정), 측정 한도 입력 0.001. 손실률이 0.01% 단위 반올림이라 0.01% 한도는 세션 수에 따라 동률 승인이 됐다([attempt1-measure](attempt1-measure/) 45/48). **입력 0.001은 `risk_config` 열 자리수(소수 둘째)로 0.00%로 저장된다** — 실제 측정 기준은 "0이 아닌 손실이면 거절, 손실 0이면 승인"이다(시험 주석의 0.001% 표기는 이 뜻) |
| **`43017efa`** | 제품 후보 + 계약 시험 기대 개정 + 직접 행 PG 시험 3개 고정물 |
| `1744bb4b`·**`b861bdf9`** | 직접 행 시험 고정물 2개(PositionLimitTest·SettlementRoundingPgTest) — 제품 변경 없음 |

차분: 제품 6파일 +218/−22([candidate-product.diff](candidate-product.diff)), 시험 8파일 +635/−13([candidate-test.diff](candidate-test.diff)). 빌드 파일·`src/main/resources`(Flyway 포함)는 0e1d2a02와 같다. 전체 patch-id `1c5923bd…`.

## 1. 바꾼 것

| 대상 | 종전(0e1d2a02) | 후보 |
|---|---|---|
| 손실 합 SQL `sumLiveSessionRealizedLossSince`·`sumDynamicRealSessionRealizedLossSince` | 반영 대금 − 반영 수수료 − 체결 수량 × `p.avg_price`(대금/수량, 매수 수수료 제외) | 체결 수량 × **확정 매수 단가**. 단가 = 같은 포지션 BUY의 (반영 대금 + 반영 매수 수수료) 합 ÷ 체결 수량 합. 포지션당 한 행으로 먼저 모은 뒤 매도 행에 붙이므로 매도 행이 늘지 않고, 부분 매도는 자기 수량만큼만 원가를 받는다 |
| 판정 불가(신규) `countRealSessionCostUndeterminedSince` | 없음(원가 미상 매도는 평균가로 계산, 팬텀 추정분은 합에서 빠짐) | 30일 창의 ① 확정 매수 원가가 없는 확정 매도(체결 BUY 없음 또는 반영 대금/수수료 NULL), ② 확정 매도 수량 < 체결 매수 수량인 CLOSED(팬텀 등). 0이 아니면 `checkRisk`·`checkRealLossGuard`가 "[기존 보호식]/[DYNAMIC REAL 보호식] 판정 불가"로 거절 |
| 세션 연속 손실(LIVE·DYNAMIC) | `position.realized_pnl` | 주문 원천 비용 기준 손익 = 확정 매도 순대금 − 확정 매도 수량 × 확정 매수 단가. 주문 원천이 전혀 없는 행(과거·수동)은 저장 손익 그대로. 줄에 판정 불가 칸이 걸리면 `entryUndetermined`: **발동 아님(비상 정지·청산 없음)**, 신규 진입만 막는다(LIVE `executeSessionBuy`·DYNAMIC REAL `executeBuy` — 즉시·회차 승인 공통) |
| LIVE_ALIGNED `riskPnl` | v1.147 R5: 저장 손익 + (투입 − 수량×평균가) = 매수 수수료 되더함 | `realizedPnl` 그대로(매도 순수익 − 투입금, 양쪽 수수료). R5 관례를 이 후보에서 바꾼다 |
| DYN_PAPER | 저장 손익(수수료 포함 평균가) | 그대로 — 이미 비용 기준 |

유지한 것: 분모(RUNNING 초기자본·폴백), 한도 값, 일/7일/30일 창, 손실 매도만 합산·이익 미상계, RUNNING 자본 0의 판정 불가, 비세션 FILL과 세션 매도 비중복, 비활성 → MDD → 연속 손실 반환 순서, 매도 반영 수수료 NULL을 0으로 보는 종전 손실 합 규칙(매도 쪽). `avg_price`·저장 손익·잔액·수량·체결/정산 쓰기·SL/TP·전략 SELL·과거 행은 바꾸지 않았다.

## 2. 회귀 시험 — 같은 시험, 기점 실패 → 후보 통과

[CostBasisRiskPgTest](source/CostBasisRiskPgTest.java): LIVE·DYNAMIC REAL **실제 매수→매도 경로**(거래소 응답만 대역)와 격리 PG `cat-rt-pg`. 한 시험 안에 48개 단언 항목을 기록한다([base_vs_candidate.json](base_vs_candidate.json)).

| 단계 | 0e1d2a02 ([base-fail2](base-fail2/), XML 실패) | 후보 b861bdf9 ([run-final](run-final/), **XML 1/1 · 48/48**) |
|---|---|---|
| S1 부호: 매수 7,500+3.75 → 매도 7,504.75−3.75 | 손실 합 0/0, `checkRisk`·`checkRealLossGuard` 승인, 연속 손실 한도1 미발동 2/2. LIVE 회차 승인 관문은 손실 단계를 지나 BTC 캔들 없음에서 멈췄고, DYNAMIC `executeBuy`는 손실 보호식을 지나 공유 풀 예약의 RISK_CONFIG_MISMATCH(측정 한도 0.00이 사전 등록과 다름)에서 거절됐다 — 둘 다 손실로 막히지 않음 | 손실 합 **−2.75/−2.75**, 둘 다 거절, 발동 2/2, 회차 승인 관문 "리스크 한도", DYNAMIC 진입 "실자금 손실 보호식" |
| S2 크기: 매도 7,400−3.70 | −103.70 | **−107.45** |
| S3 실제 손익 0(매도 7,507.50−3.75) | 0·승인·미발동 | 같음(0은 손실 아님) |
| S4 연속 손실 한도2 (LIVE·DYNAMIC) | 1회 미발동, **2회 미발동**, 손실→이익→손실 미발동 | 1회 미발동, **2회 발동**, 손실→이익→손실 미발동(이익이 끊음) |
| S5 창: 한 건을 25시간·8일·31일 앞으로 | 주/월 창 안에서도 승인(손실 0) | 25시간: 일 밖·**주 거절**, 8일: 주 밖·**월 거절**, 31일: 모두 밖 |
| S6 부분 매도 10 + 잔여 15 → 매수 수수료 정정 3.75→3.80 → 매도 수수료 정정 → 같은 응답 반복 → 롤백 (LIVE·DYNAMIC) | −0.5005에서 고정(잔여 매도는 +1.748로 빠짐, 매수 정정 무시) | −2.0005 → **−2.5025**(두 매도 각각) → **−2.5525**(정정 반영) → −2.5555 → 반복 변화 없음(NO_CHANGE) → 롤백 변화 없음 |
| S7 판정 불가: 매수 반영 수수료 NULL(LIVE·DYNAMIC), 팬텀 추정 청산(`reconcilePhantomPosition`) | 모두 승인·미발동·같은 세션 매수 진행 | `checkRisk`/`checkRealLossGuard` "판정 불가" 거절, 연속 손실 "판정 불가"(발동 false), 같은 세션 신규 매수 거절, **세션 RUNNING 유지** |

S6 롤백과 S7 NULL 수수료는 **행 직접 쓰기**다. 롤백은 같은 열을 쓴 트랜잭션을 롤백해 커밋 안 된 원가가 읽히지 않음을 보고, NULL은 미보고 상태를 재현한다. 나머지는 엔진 경로다.

## 3. 영향 시험 (한 번)

| 묶음 | 결과 | 비고 |
|---|---|---|
| H2 29클래스([affected-h2](affected-h2/)) — 진입·서킷 브레이커·위험 관문·엔진 동등성·R5·전략 중단 | 31 XML(중첩 포함) **214/215** → 실패 1건은 PositionLimitTest의 직접 닫은 포지션(매도 없음)이 판정 불가로 먼저 막힌 것. 고정물(확정 매도, 손익 0)을 넣어 그 클래스만 다시 **13/13**([rerun](affected-h2/rerun-PositionLimitTest/)) | AlignedV147·R5 기대 개정분 포함 |
| PG 4클래스([affected-pg](affected-pg/)) | Phantom **4/4**, RealLossThreeFormula **8/8**, PartialSell **7/7**, SettlementRounding **16/16** | 앞 3개는 직접 만든 포지션에 BUY 행이 없어 처음 13건 실패. 확정 매수 행(수수료 0, 원가 = 기존 평균가×수량)을 넣어 기존 기대 숫자 그대로 통과. SettlementRounding은 2회 실패를 보존([attempt1](affected-pg/attempt1-SettlementRounding/)·[attempt2](affected-pg/attempt2-SettlementRounding/)): 직접 행 매도와 ⑦(나)의 인위 CLOSED가 같은 DB 뒤 시험을 30일 판정 불가로 막았다 |

계약 변경으로 기대를 바꾼 시험: `AlignedV147FixesTest` R5(+5 미발동 → −5 발동), `R5RealCashflowRiskTest`(LIVE 위험 입력을 비용 기준으로, 수수료 경계 +0.20%가 두 엔진 모두 손실·한도1 발동, 앞선 손실+수수료 경계 2회 발동). 수수료 경계의 일 손실 관문은 손실 0.076원/2만 = 0.0004%가 0.01% 단위 반올림으로 0이 되어 **승인**이다. 위험 입력의 부호 문제가 아닌 기존 해상도다(§6).

전체 회귀·jar·이미지 빌드는 돌리지 않았다.

## 4. 같은 체결 입력의 네 엔진 — 보호 판단과 가격 판단 분리

| 엔진 | 위험 입력(후보) | 실행 근거 | 가격 판단 평균가(바꾸지 않음) |
|---|---|---|---|
| LIVE | 비용 기준(주문 원천) | 본 PG S1~S7 | 체결가(대금/수량) |
| DYNAMIC REAL | 비용 기준(주문 원천) | 본 PG S1~S7 | 체결가 |
| LIVE_ALIGNED | `realizedPnl`(비용 기준) | R5 H2 실제 매수→매도 7/7 — 수수료 경계에서 LIVE와 부호·연속 손실 일치 | **체결가** — 고정 0e1d2a02 `PaperTradingService` S:1867 `alignedMode ? price : avgPriceWithFee` |
| DYN_PAPER | 저장 손익(이미 비용 기준) | [손익 정의](../dec-pnl-definition-20261119/README.md) A: −2.40·연속 손실 발동(0e1d2a02 그대로, 후보가 건드리지 않는 경로) | 수수료 포함 평균가 |

보호 판단(손실 합·연속 손실)은 네 엔진이 모두 비용 기준으로 같아졌다. 남는 차이는 **가격 판단**이다. DYN_PAPER만 SL/TP 손익률을 수수료 포함 평균가로 보므로, 같은 가격에서 손절이 REAL보다 약 0.05% 먼저 나간다([손익 정의 C](../dec-pnl-definition-20261119/README.md) 285.10원). 이것은 DYN_PAPER를 REAL의 리허설로 볼 때 생기는 가격 기준 차이이며, 보호식과 따로 판정할 문제로 보고한다(이 후보는 바꾸지 않음).

**정정:** 손익 정의 README §2 표의 "LIVE_ALIGNED 손절/익절 — 원문 확인 필요(S:1860 수수료 포함 평균가)"는 틀렸다. 고정 원문은 선택 모드에서 체결가를 판단 평균가로 쓴다(S:1867). 같은 README의 C는 실행 경로가 아니라 **평균가 두 값을 넣은 정적 함수(`RealtimeProtectLines.stopHit`) 관측**이다.

## 5. 전략 중단·가중치 — 소비 경로와 자동 적용 조건 (별도 포함안, 이번 후보에 안 넣음)

| 소비 | 읽는 값 | 자동 실행·적용 | 고정1(SID203 DAILY_SMA20)·동적1(BREAKOUT H1)에 영향 |
|---|---|---|---|
| `StrategyKillCriteriaService` (09:00 KST 매일) | `aggregateClosedTradesPerSession`: 세션별 `SUM(realized_pnl)`, 승 = `realized_pnl > 0` | `kill-criteria.auto-stop` 기본 **false** → 판정·경보·기록만. Git의 yml·compose에 켜는 값 없음. 운영 환경 변수는 미확인 | 판정 문구·경보에 영향(REAL 승수·합 과대). 자동 정지가 켜져 있지 않으면 세션을 멈추지 않는다 |
| `StrategyWeightOptimizer` (기동 + 21:00 매일) | `findClosedPositionsForWeighting(ByCoin)`의 `realized_pnl`·`invested_krw` | `WeightOverrideStore`에 즉시 저장 → `StrategySelector` → `RegimeAdaptiveStrategy`(`COMPOSITE` 계열)만 조회 | **없음** — DAILY_SMA20과 COMPOSITE_BREAKOUT(고정 가중 ATR 0.5·VD 0.3·MACD 0.2)은 가중치 저장소를 읽지 않는다 |

별도 포함안(제출만): kill criteria의 REAL 행 합·승을 비용 기준(저장 손익 − (투입 − 수량 × 평균가))으로 읽는 조회 변경. 판정 표본과 과거 지문 비교가 바뀌므로 자동 정지를 켜기 전에만 의미가 있다. 가중치는 선택한 두 전략에 영향이 없으므로 12월 범위에서 제외를 제안한다.

## 6. 정의 대조·과거 원가 누락·배포 전 읽기·복구

| 항목 | 종전 정의 | 후보 정의 | 과거 원가 누락 시 | 배포 전 읽기 | 복구(되돌림) 시 |
|---|---|---|---|---|---|
| REAL 손실 합 | 가격 기준(매수 수수료 제외) | 비용 기준 | 30일 창에 있으면 **신규 REAL 진입이 판정 불가로 30일까지 막힌다** | [predeploy_read.sql](predeploy_read.sql) R1(판정 불가 수)·R2(종전/후보 손실 합) | 이전 jar로 되돌리면 종전 식. 데이터 변경이 없어 되돌림에 DDL·행 복구 없음 |
| 연속 손실 | 저장 손익 | 주문 원천 비용 기준, 원천 없는 행은 저장 손익 | 줄에 걸리면 그 세션 신규 진입만 막힘(정지 없음) | R3(RUNNING REAL 세션 최근 청산의 저장/비용 기준) | 같음 |
| LIVE_ALIGNED 위험 입력 | R5 가격 관례 | 비용 기준 | 해당 없음(모의 표 값) | `riskPnl`은 선택 모드 함수(`alignedCircuitBreaker`·`alignedGrossLossPct`)에서만 쓰인다 — 키 없는 PAPER(기존 66 포함)의 판정은 바뀌지 않는다. 운영에 선택 모드 세션이 있는지는 운영 읽기 범위에서 확인 | 같음 |

**열린 판단 2건:**

1. **판정 불가 해제 경로** — 팬텀 1건이나 매수 수수료 미보고 1건이 30일 동안 모든 신규 REAL 진입을 막는다. 지시서 2항은 노출을 요구하지만, 진입이 상시 막히는 것도 결함이다. 해제 수단(운영자 확인 기록, 정정 후 자동 해제만 허용 등)은 정하지 않았다.
2. **손실률 해상도** — 기존 `pct()`는 비율을 소수 넷째 자리로 반올림한다. 그래서 자본 대비 0.005% 미만의 손실은 0%가 된다(R5 수수료 경계 0.0004%). 이번 범위(분모·한도 유지) 밖이라 바꾸지 않았다.

운영 조회문·F 핀은 바꾸지 않았다(새 출처는 검토 뒤).

## 7. 재현

```
docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS costb_x" -c "CREATE DATABASE costb_x"
SR_PG_URL=jdbc:postgresql://localhost:15432/costb_x COSTB_OUT=<폴더> ./gradlew :web-api:test --tests '*CostBasisRiskPgTest'
# 기점: 0e1d2a02 분리 사본(D:/tmp/costb-base)에 같은 시험 파일만 복사해 같은 명령
# 영향 PG: PH_/RL_/PP_PG_URL=jdbc:postgresql://127.0.0.1:15437/<db> (v152-pg, 신뢰 인증) · SR_PG_URL=…15432/<db>
```

Windows, JDK 17.0.12. 체크섬 [SHA256SUMS](SHA256SUMS).
