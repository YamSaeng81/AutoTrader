# 12월 준비 18 — 월 성적표 입력의 원천·as-of 계약 (총괄 v1.201 §3-1)

2026-11-20 KST, 개발 세션 제출(총괄 판정 전). 기준은 제품 **d81c3f2f** Git 원문(엔티티·마이그레이션·서비스)과 [성적표 v3](../dec-scorecard-v3-20261119/README.md) 입력 `scorecard-input-2`다. 운영 조회·DB 접속·새 제품 열·수집기는 없다. 예시는 가상 주문 행이다.

## 1. 결론

- **월말(T) 값은 T 직후 '경계 포착'이 있어야만 확정한다.** 세션 잔액·포지션·주문 금액 열은 모두 현재값을 덮어쓰는 열이고, 세션별 이력 테이블이 없다. 나중에 현재 행을 읽어 과거 월말 값을 만들 수 없으므로 그 경우는 미확정으로 둔다.
- **월 귀속 시각은 로컬 관측 시각이다.** `order.filled_at`은 `OrderExecutionEngine`이 체결을 확인한 순간의 `Instant.now()`(1209·1223·1378·2536행)로 기록한다. 거래소 체결 시각이 아니다. 월 경계 몇 초 안의 체결은 관측 지연만큼 다음 달로 갈 수 있으며, 이 점을 성적표 규칙에 명시한다.
- **입출금(ALLOCATE/RECOVER) 원천이 제품 안에 없다.** `initial_capital`은 생성 때만 쓰고, 운영 중 증액/회수 경로나 이벤트 테이블이 없다. 12월 flows는 [병행 등록표](../dec-parallel-registry-20261120/README.md)의 외부 기록에서만 받는다. 없으면 v3 규칙대로 미확정이다.
- 이 계약으로 v3의 출력 규칙은 바뀌지 않는다. 새로 정한 것은 "어떤 원천을 언제 읽어야 확정인가"다.

## 2. 입력 필드별 원천·시각 축·관측 마감·확정 조건

T = 2027-01-01 00:00 KST(12월 마감). M = 관측 마감이며, 예시로 1월 1일 10:00 보고를 회수한 뒤(약 10:30)를 쓴다. 실제 M과 읽기 명령은 승인된 읽기 범위가 정해질 때 고정한다.

| 입력(`scorecard-input-2`) | 제품 원천(d81c3f2f) | 시각 축 | 확정 조건 | 아니면 |
|---|---|---|---|---|
| `period.from/to` | 성적표 설정 | KST 월 경계 `[from,to)` | 고정값 | — |
| `sessions[].kind/id/mode/strategy_type/timeframe` | `live_trading_session`(LIVE) · `dynamic_session`(`trading_mode`) | 생성 시 고정 | 등록표 행과 일치 | 계약 위반(rc2) 또는 미등록 표시 |
| `status_end` | 세션 `status`·`stopped_at` | 포착 시각 | [T, M] 포착에서 `stopped_at`이 없거나 T보다 앞 | 미확정 |
| `start`/`end.available_krw` | 세션 `available_krw`(현재값, 덮어씀) | 포착 시각 | **T 경계 포착** 1회. 앞 달의 end 포착을 다음 달 start로 재사용 | 미확정(R4). 현재 값으로 소급하지 않음 |
| `start`/`end.positions[]` (size·status·cost) | `position`의 `size`·`status`(OPEN/CLOSING)·`invested_krw`·`position_fee` | 포착 시각 | 포착 안에서 OPEN. CLOSING은 v3대로 미확정 | 미확정 |
| `positions[].price` | `candle_data`의 T 직전 마감 봉 종가(코인별) | 봉 마감 시각 | T 이전에 마감한 봉이 있고 원천 표시 | 미확인(평가 미확정) |
| `pending_buys[]` | `order` BUY 중 미체결 상태·`reserved_krw`·`filled_quantity` | 포착 시각 | `reserved_krw` 있음·체결0 | 미확정(v3 규칙) |
| `flows[]` | **제품 원천 없음** → 등록표의 배정/회수 기록 | 등록 시각 | 등록표 행 + 세션 생성 이후 + 기간 안 | `null` → 미확정 |
| `trades[].buy.settled_at` | `order.filled_at` (BUY) | 로컬 관측 | 포착이 R3 충족 | 미확정 |
| `trades[].sells[].settled_at` | `order.sell_applied_at` | 로컬 반영 | 포착이 R3 충족 | 미확정 |
| `qty/funds/fee` | `filled_quantity`·`applied_executed_funds`·`applied_paid_fee` | 누적, 정정 때 덮어씀 | 포착 안에서 `settlement_final=true`·`fill_accounting_pending=false`·`reported_observed_at < T` | 미확정(월 경계 걸침) |
| `stored_realized_pnl` | `position.realized_pnl` · DYNAMIC은 `dynamic_sell_settlement.realized_pnl`(`settled_at`) | 확정 시점 값 | 대조용. v3가 주문 원천으로 다시 계산 | 불일치 표시 |
| `provenance.ruleset_hash` | `position.ruleset_hash`·`ruleset_snapshot` | 진입 시 | 행에 값 있음 | 미확인 |
| `provenance.code_sha` | **제품 원천 없음** → 등록표·배포 기록(N03/F1의 운영 SHA) | 배포 시각 | 기간 내 SHA 변화가 등록표에 모두 기록 | 미확인 |
| `market_regime` | `position.market_regime` | 진입 시 | 표시만 | — |
| 일별 낙폭 표본 | **세션별 원천 없음**. `pool_equity_snapshot`은 공유 풀 단위·60초·**8일 보존**(PoolLossGuard 75·437·463행) | `taken_at` | 세션별 낙폭에는 쓰지 않음 | v3대로 "미확인(복원하지 않음)" |
| 계좌 기초/기말 | 거래소 계좌 조회(N03 장부) | 조회 시각 | T 경계 포착과 같은 회차 | 미확인 |

**정정 원천의 한계.** `order_settlement_discrepancy`는 감소·역행만 남긴다. 정상적인 증가 정정은 행이 남지 않으므로, 정정이 있었는지는 **포착 값과 현재 값의 차이**로만 안다(R5).

## 3. 규칙과 좁은 예시

[asof_examples.py](asof_examples.py)가 규칙 R1~R5를 가상 행에 적용한다. 실행 결과는 [run1.out.txt](run1.out.txt)이며 **6/6 통과(rc0)**, Python 3.13이다.

| 예시 | 상황 | 판정 |
|---|---|---|
| E0 | 12-30 매도, 관측도 T 이전, 포착=현재 | 확정 |
| E1 | **부분 체결 월 경계**: 12-31 23:59 첫 관측, 01-01 00:01 잔량 관측. `filled_at`에는 첫 관측만 남음 | 미확정(R3) |
| E2 | **늦은 수수료 정정**: 확정 뒤 01-03 수수료 +0.01(증가라 discrepancy 없음) | 12월은 포착 값으로 확정, +0.01은 1월 정정(R5) |
| E3 | **포착 없이** 01-05 현재 행만 읽음 | 미확정(R4) |
| E4 | 포착이 관측 마감 뒤(01-02) | 미확정(R2) |
| E5 | `settlement_final=false` 상태로 포착 | 미확정(R3) |

E1에서 현재 행만 보면 `filled_at` 12-31의 최종 수량 전체가 12월로 간다. 이렇게 귀속하지 않는다. 잔량이 1월에 관측된 사실은 `reported_observed_at`이 T보다 뒤라는 것으로만 알 수 있다.

## 4. 남은 결정과 하지 않은 것

- **필요한 운영 행동은 하나다.** 매월 [T, M] 안에 해당 세션의 세션 행·포지션·주문(기간 거래)·계좌를 읽기 전용으로 포착해야 한다. N03 장부 회수와 같은 회차로 묶는 것이 자연스럽다. 이 읽기의 명령·범위는 아직 승인 전이다. 그래서 이번에는 계약만 제출했고 실행용 블록은 만들지 않았다.
- **12-01 기초 값도 같은 이유로 12-01 00:00 직후의 포착이 필요하다.** 12월 세션이 12월 중에 생성되면 start는 생성 시 `initial_capital`을 쓰고 flows ALLOCATE로 넣는다(v3 R2f 규칙).
- DB → `scorecard-input-2` 변환기, 새 제품 열·이벤트 테이블·상시 수집은 만들지 않았다(지시 범위).
- 관측 지연(R1) 때문에 경계 근처 몇 초의 거래가 다음 달로 갈 수 있다. 거래소 체결 시각(`responseJson`의 trades)으로 바꿀지는 총괄이 판단할 사항이며, 지금은 로컬 관측 기준을 명시하는 데서 그친다.

체크섬은 [SHA256SUMS](SHA256SUMS)에 있다.
