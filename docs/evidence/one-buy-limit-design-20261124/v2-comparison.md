# 1회 제한 — 두 안 비교와 최소안 권고 (총괄 v1.240 §3-2)

2026-11-24 KST, 로컬 Git 판독만 했다(d81c3f2f 블롭). 구현·제품 시험·PG·빌드는 하지 않았다. 앞 [설계](README.md) §2의 "새 열은 지문에서 뺀다"는 **철회한다.** RulesetRegistry는 "진입 규칙을 바꾸는 상수는 반드시 여기 실려야 한다"고 적고 있다(DYNAMIC hashFor 주석). 진입 횟수 상한도 진입 규칙이므로, 제한이 **있는** 세션은 지문에 반영해야 한다. 제한이 **없는** 세션은 지문 입력이 바뀌지 않아야 한다. 두 요구는 함께 지킬 수 있다.

## 1. 새로 확인한 사실

| 확인 | 결과(d81c3f2f) |
|---|---|
| strategy_params 추가 키 | DailySmaTrendStrategy.evaluate는 `params.get("timeframe")`만 읽는다(66행). ExitRuleOverrides.from(79~)은 정해진 네 키만 읽고 나머지는 무시한다. 그래서 `maxEntries` 키가 있어도 전략·청산 해석에 영향이 없다 |
| 지문 | LIVE hashFor(121)는 `canonicalParams(strategyParams)`를 넣는다. 키가 없으면 입력이 그대로이고, 있으면 그 세션의 해시만 달라진다 |
| 옵션 변경 | `setStrategyParams`는 엔티티 setter뿐이다. 세션 생성 뒤 바꾸는 서비스·API 호출이 없다 |
| LIVE BUY 생성 경로 | LiveTradingService 1615/1630 하나뿐이다(executeSessionBuy). 반드시 reserveAndPrepareIntent → reserveForBuy를 지난다 |
| 잠금 | `SELECT id FROM account_capital_lock WHERE id = 1 FOR UPDATE`(CRS 510~512) 행 잠금이다. 잠금을 얻은 뒤의 문장은 READ COMMITTED 새 스냅숏이므로, 앞선 트랜잭션이 커밋한 의도를 본다 |
| 주문 행 삭제 | 제품의 삭제는 DbResetService.resetLiveTrading뿐이다. 이것은 LIVE 주문과 **live_trading_session 전체**를 함께 지운다. 세션이 사라지므로 "세션 수명 안의 이력"이 남은 채 줄어드는 경우는 없다. 수동 DB 편집은 범위 밖이다(두 안 모두 같은 한계) |

## 2. 두 안 비교

| | A. 선택 열 + 카운터 (V97) | **B. 스키마 없음: 선택 옵션 + 기존 BUY 의도 이력** |
|---|---|---|
| 저장 | `max_entries` NULL·`entry_count` 0 기본. CHECK: max ≥ 1, count ≥ 0, count ≤ max | 새 세션의 `strategy_params.maxEntries = 1`. 없으면 제한 없음 |
| 지문 | 제한 있는 세션만 hashFor에 열을 추가해야 한다(hashFor 변경: 값이 NULL일 때 키를 넣지 않는 분기 필요) | **자동이다.** strategy.params가 이미 입력이라 키가 있는 세션만 해시가 달라진다. hashFor 변경 없음 |
| 차단 위치 | reserveForBuy LIVE 분기, 잠금 뒤·차감 전 | 같다. 차감 전에 `SELECT count(*) FROM "order" WHERE session_kind='LIVE' AND session_id=:id AND side='BUY'`(상태 무관, 커밋된 모든 의도) ≥ maxEntries이면 `ENTRY_LIMIT_REACHED` |
| 매도 후 재진입 | 카운터 1 → 거절 | 이력에 BUY 1행 → 거절 |
| 재기동 | 열에 남는다 | 주문 행에 남는다 |
| UNKNOWN·철회 뒤 | 의도 생성 때 증가 → 거절 | UNKNOWN·FAILED(철회) 행도 센다 → 거절(실제 매수 0이어도 다시 사지 않는 보수 쪽) |
| 중간 롤백 | 증가가 같은 트랜잭션이라 함께 롤백 → 0 유지 | 롤백되면 의도 행이 없다 → 세지 않는다. 보상 코드가 필요 없다 |
| 동시성 | 잠금 + CHECK(마지막 방어) | 잠금으로 직렬화된다. DB 수준 방어는 없다(코드 경로가 하나뿐이라는 판독에 기댄다) |
| 배포·복구 영향 | **Flyway 97.** U 블록 v96h·검사기 Flyway 96·복구 관문 4판·D81 복구 핀이 모두 96 전제다. V96 판으로 되돌리면 열이 남거나 이관 되돌림이 필요하다 | **스키마 영향 없음.** Flyway 96 그대로. 복구도 앱 이미지 교체만으로 된다(옵션 키는 구판에서 무시돼 **제한 없이** 돈다 — 아래 한계) |
| 대상 한정 | LIVE 분기만 | 같다. DYNAMIC·PAPER는 읽지 않는다 |
| 변경 규모 | 이관 1 + 엔티티 + 예약 분기 + hashFor + 생성 API | 예약 분기 약 10줄 + 생성 시 값 검증(1만 허용) |

**B의 한계:** ① 구판(옵션을 모르는 판)으로 되돌린 상태에서 시험 세션이 RUNNING이면 제한 없이 돈다. 그래서 시험 중에는 복구 전에 세션을 먼저 정지하는 순서가 필요하다. ② DB CHECK 같은 마지막 방어가 없다. ③ 주문 행을 손으로 지우면 무력화된다(A의 카운터도 손으로 고치면 같다).

## 3. 권고 — B

한 번의 체결 확인 시험을 위해 스키마를 바꿀 이유가 약하다. B는 Flyway 96과 기존 지문 계약을 그대로 두고, 제한 있는 세션만 지문이 달라진다. 1만원 시험은 선택적 인수 시험이므로, D81 비용 보호 배포와 묶지 않고 **d81 다음의 작은 후보**로 둔다. A는 B의 한계 ②가 받아들여지지 않을 때의 대안이다.

**선택할 최소 시험 계약**(구현 수용 뒤 정함, 지금 실행하지 않음):
1. 옵션 없는 세션: 기존 예약 시험 그대로 통과, hashFor 값 불변
2. 옵션 1 세션: 매수 → 매도 정산 → 다음 신호에서 ENTRY_LIMIT_REACHED. 주문 1 · 가용액 불변(PG)
3. 같은 세션 동시 예약 2: 승인 1 · 거절 1(PG)
4. 거절·만료 롤백 뒤에는 이력이 0이라 다음 예약 승인(PG)
5. UNKNOWN·철회 행이 있으면 거절(PG)
6. 생성 요청 maxEntries가 1 밖이면 400, 옵션 키는 DailySma20·청산 해석에 영향 없음(순수)

## 4. 이 설계로 해결하지 않는 것

- **정지 경합:** stopSession(611)은 계좌 잠금을 쓰지 않는다. 잠금 안에서 RUNNING을 다시 읽어도 정지 커밋과의 경합이 없어졌다고 할 수 없다. "1회 제한"과 "정지 뒤 제출 없음"은 별개다. 이 시험의 계약은 **누적 매수 1회**까지로 두고, 정지 뒤 제출 0은 보장 대상에서 뺀다.
- 금액·수수료 상한(9,950원 요청 → ≤ 9,954.98원)과 실제 계좌 여유(A3)는 별도 관문이다.
- 구현·생성·start·실주문은 하지 않았다.
