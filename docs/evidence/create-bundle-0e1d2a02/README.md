# 새 모의 9세션 최종 생성 묶음 — SMA20 전향 1 + 청산 A/B 8 (2026-11-16, 준비만, 총괄 판정 전)

총괄 [v1.176 §4-4](../review-7fea1b89/README.md)에 따른 **구체 묶음 제출**이다.

- **실제 생성 호출·T0·운영 DB 읽기는 하지 않았다.**
- 로컬에서 한 것은 세 가지다: v4 생성기의 계획만 모드(보낸 것 없음), 요청 규칙 대조, 사전 확인 SQL 문법 검사(로컬 시험 PG).
- 수용된 설계·상한·팔 계약·도구 v4는 바꾸지 않았다.

## 1. 고정값

| 항목 | 값 | 근거 |
|---|---|---|
| 코드 | 운영 **0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d**, backend 2c5d8427, DB V96 | [v1.176](../review-7fea1b89/README.md) §2 |
| 실행 모드 | `strategyParams.paperExecutionMode="LIVE_ALIGNED"` | 0e1d2a02 `PaperExecutionMode.violation`: 허용 값은 이것뿐, null 거절 |
| 1회 상한 | `strategyParams.paperMaxInvestment` | 0e1d2a02 `PaperMaxInvestment`: 5,000 이상 숫자. 지정 세션만 자본 5,000원부터(키가 없으면 100,000) |
| 생성 관문 | 동시 RUNNING **120** 상한, `strategyEnablementGate.assertEnabled(전략, 주기)`, 위 두 규칙 | `PaperTradingService.start` 143~192행(0e1d2a02) |
| A/B 설계 | protocol.json `8d787fe9…`: 4쌍(MEANREV_BB·MTF_CONFIRMED × BTC·ETH) M15, 100,000원·비율 0.8·상한 20,000·maxHold 24, A 손절 null / B 5, **42일** | [v1.143 판단 3](../scorecard-v2-review-1aa890f2/README.md), [도구 v4](../ab-create-v4-20261111/README.md) |
| 도구 해시 | ab_create `31591216…`·ab_complete `33be6600…`·ab_arms `a51c13b9…`·chain_driver_v4 `31709f5c…`·protocol `8d787fe9…` | v4 SHA256SUMS와 일치(이번 확인). 집계기 v3 `ab_aggregate.py` `334b62e3…` |

## 2. 요청문 9개

모두 `POST /api/v1/paper-trading/sessions` 1회씩이다. 재전송은 없다.

**A/B 8개** — [ab-plan/plan.json](ab-plan/plan.json)

- v4 생성기를 계획만 모드로 실행해 만들었다(rc2, 보낸 것 없음).
- v157 실제 연결판 `runs/ab4-main/out/create/plan.json`과 **바이트가 같다**(plan_sha `278b4c65…`). 코드 SHA는 계획 파일에 실리지 않는다.
- 본문: P1~P4 × A/B. 공통 키는 `strategyType`·`coinPair`·`timeframe:M15`·`initialCapital:100000`·`investRatio:0.8`·`maxHoldHours:24`·`strategyParams{paperMaxInvestment:20000, paperExecutionMode:LIVE_ALIGNED}`이다. B 팔만 `stopLossPct:5`를 더한다.

**SMA20 전향 1개** — [sma20/body.json](sma20/body.json)

```json
{"strategyType":"DAILY_SMA20","coinPair":"KRW-BTC","timeframe":"H1","initialCapital":20200,"investRatio":0.995,"stopLossPct":5,"maxHoldHours":0,
 "strategyParams":{"paperMaxInvestment":20000,"paperExecutionMode":"LIVE_ALIGNED","forwardTag":"SMA20-FWD-1"},"enableTelegram":false}
```

- SID 203과 같은 값이다: 20,200원, 1회 20,000원, 손절 5%, 시간 청산 끔, 비율 0.995.
- 원 설계([SMA20_FORWARD_PAPER](../scorecard-v2-20261110/SMA20_FORWARD_PAPER.md) §2)에 LIVE_ALIGNED 키만 더했다([야간 검토 af7ffa58](../overnight-review-af7ffa58/README.md) "SMA20 전향 세션도 같다").
- `forwardTag`는 `DailySmaTrendStrategy.evaluate`가 읽지 않는다(그 메서드는 `timeframe`만 읽는다). 규칙 지문에만 실린다.

요청 규칙 대조(0e1d2a02 원문 기준):

- 9개 모두 `paperMaxInvestment` 숫자 ≥ 5,000이다. 그래서 자본 하한은 5,000원이고, 20,200·100,000 모두 통과한다.
- `paperExecutionMode`는 문자열 `LIVE_ALIGNED`다.
- 전략·주기 활성은 생성 직전 DB로 확인한다(§3).

## 3. 생성 순서(실행안 — 승인 전 실행 금지)

| 단계 | 내용 | 멈춤 조건 |
|---|---|---|
| 0. 승인·준비 | 총괄 승인 범위, 실행 시각, §6 판단 결과를 고정한다. 운영 DB 비밀번호는 운영자에게 그때 받는다. API 토큰 파일은 운영자가 서버에서 600 권한으로 만든다(개발은 읽거나 출력하지 않는다) | 승인 범위 밖이면 시작하지 않는다 |
| 1. 사전 읽기 | [preflight/preflight.sql](preflight/preflight.sql)을 읽기 전용 트랜잭션으로 1회 실행한다. 522~587은 **개수만** 센다. 중복 서명 후보는 522~587을 뺀 열 제한 질의다 | V96 이력 `95,96,t,-424836241` 아님 · 세 전략 중 비활성 행 있음 · RUNNING+9 > 120 · LIVE_ALIGNED 서명의 RUNNING 중복 있음 · BTC H1 봉 공백 → 보내지 않고 보고 |
| 2. A/B 8 | `ab_create.py --execute --code-sha 0e1d2a02… --protocol …/protocol.json --token-file <운영자 파일> --base-url <backend 내부 주소>`. M15 경계 C의 [C+6분, C+12분] 창에서만 요청마다 다시 확인하고 보낸다. T0 = C+15분 | 창 밖(rc2), 결과 불명·HTTP 이상 → 남은 본문을 보내지 않고 재전송하지 않는다(rc3). 기록부·잠금 이상(rc4). 어느 경우든 사람 판단 |
| 3. 완성 | `ab_complete.py`(v4)로 8행 저장값과 `started_at`을 psql 출력과 나노초 정수로 정확히 대조한다. 그다음 `ab_arms.py`로 arms.csv를 만든다 | 한 행이라도 다르면 완성본 0개 · 집계기 설정 검사 실패 |
| 4. SMA20 1 | A/B 완성 뒤 별도 창에서 POST 1회 → 응답 ID의 `GET /sessions/{id}` 1회(새 ID는 522~587 밖) → DB 행 1회(열 제한) 대조 | 응답 불명·4xx/5xx → 재전송 금지, 읽기로만 확인하고 보고 |
| 5. 기록 | 세션 ID·생성 시각·운영 HEAD/이미지를 [VERSION_LOG_TEMPLATE](../exit-ab-protocol-20261110/VERSION_LOG_TEMPLATE.md) 형식으로 남긴다. 이후 배포의 시각·SHA·이미지는 F1·N06 핀 기록과 같은 출처로 잇는다 | — |
| 6. 집계 | 42일 뒤 v3 집계기(`ab_aggregate.py`)로 한 번 집계한다. 중간 성과로 설계를 바꾸지 않는다. SMA20은 LIVE 203 실제 started_at부터를 쌍 비교 구간으로 하고, 그 전은 선행 구간이다 | — |

## 4. 세션 수·평가 루프 영향

- **11-10 운영 읽기**(같은 메타 질의): RUNNING 76(사전 등록 63 + 그 밖 13). 427차 목록 요약 개수도 RUNNING 76이다.
- 9개를 더하면 85로, 상한 120 안이다. 새 (코인, 주기) 조합은 BTC·ETH M15(이미 RUNNING 있음)와 BTC H1(SID 203·수집 대상)이다. 새 시세 구독 코인은 없다.
- 로컬 간섭 근거는 수용된 결과다. 이번에 반복하지 않았다.
  - [shared-loop-20261110](../shared-loop-20261110/README.md): 76+9에서 기존 76의 결정 변화 0
  - [overnight-gaps](../overnight-gaps-20261110/README.md): 열린 포지션 51에서도 변화 0
  - [rounds-active-20261111](../rounds-active-20261111/README.md): 회차를 켠 76+9
  - [aligned2-followups](../aligned2-followups-20261111/README.md): 보유 51·M15 실제 승인 후보, REAL 1위, 기존 76 변화 0
  - [aligned-gaps](../aligned-gaps-20261111/README.md): `SharedPaperLoopIsolationTest`
- 위 결과는 합성 입력·H2/로컬이다. **운영 부하 실측은 아니다.** 생성 뒤 운영 관측 항목: 틱 처리 시간, 승인 대기, REAL 처리 지연, 연결 획득 시간 초과(P0).

## 5. 비열람 계약과의 관계

- 사전 읽기 SQL은 522~587의 성과·포지션·신호·파라미터를 읽지 않는다. 상태별 개수와 (코인, 주기) 개수만 읽는다.
- SMA20 확인은 새 ID 단건 GET과 열 제한 DB 행이다.

## 6. 판단이 필요한 것

| # | 내용 | 개발 제안 |
|---|---|---|
| D1 | **v4 생성기는 사전 확인·사후 대조에서 `GET /api/v1/paper-trading/sessions` 전체 목록을 부른다**(`ab_create.py` 431·331행). 응답에는 522~587의 성과 필드가 함께 온다. 도구는 id·status·서명·startedAt만 쓰고 기록·출력하지 않는다. 하지만 v1.176 §3 "제외할 수 없는 목록은 재조회하지 않는다"와 충돌할 수 있다 | (가) v4를 그대로 쓰되 "응답 메모리 수신, 미기록·미출력"을 편차로 사전 등록한다. 또는 (나) 목록 조회만 522~587을 뺀 열 제한 DB 읽기로 바꾼 v4.1을 만들고 v4 시험 21/21·22/22를 다시 실행한다. **판정 전에는 어느 쪽도 실행하지 않는다** |
| D2 | 인증 경로: v1.145 결정은 backend 직접 `Authorization: Bearer`다. 토큰 원문은 운영 `.env` 비밀값이다 | 운영자가 서버에서 토큰 파일을 만들고 생성 뒤 지운다. 개발은 파일 경로만 넘긴다 |
| D3 | SMA20 단건은 v4 도구 범위(8팔) 밖이다. 기록부·잠금·불명 중단을 갖춘 단건 도구가 없다 | 승인되면 v4의 분류·기록부 규칙을 따르는 단건 호출기를 만들고, 대역(standin_server)으로 시험한 뒤 제출한다 |
| D4 | SMA20의 T0: 생성 직후 H1 경계, 또는 첫 UTC 일봉 마감(09:00 KST) | 일봉 전략이라 **첫 09:00 KST**를 제안한다. 쌍 비교는 LIVE 203 실제 시작부터다 |
| D5 | `forwardTag` 유지 여부 | 원 설계대로 유지(동작 영향 없음, 지문 구분) |

## 7. 이번에 한 확인

| 확인 | 결과 | 원문 |
|---|---|---|
| v4 계획만 실행 | rc2, 8본문, v157 plan.json과 바이트 같음 | [ab-plan/plan.json](ab-plan/plan.json) |
| 도구 해시 | 5개 모두 v4 SHA256SUMS와 같음 | 위 §1 |
| 사전 SQL 문법 | 로컬 시험 PG `v152-pg`(V96 적용, 운영 아님) rc0. 오래된 로컬 사본 DB는 V80 표가 없어 rc3(예상된 차이, 원문 보존) | [preflight/runs/](preflight/runs/) |

12-01 전에 42일 결과가 나오지 않는다. 생성은 수익성 검증 완료가 아니며, 결과로 실전 승격·증액을 자동 결정하지 않는다.
