# 12월 준비 12 — 월 성적표 v2: 네 반례 보완 (총괄 v1.190 §3)

2026-11-18~19 KST, 개발 세션 제출(총괄 판정 전). 파일 도구·합성 사례만이다. 운영 DB·예약·DB 열·운영 조회는 추가하지 않았다. v1 [dec-scorecard-20261118](../dec-scorecard-20261118/README.md) 원문·체크섬은 바꾸지 않았고, 이 폴더의 새 사본에서 고쳤다. 금액은 가상값이다.

## 1. 순서

1. **실패 먼저** — [red_v1.py](scorecard/red_v1.py)가 v1 원문(sha256 `89fe3fee…7598d8f`)에 총괄 [observations.json](../review-f14ff757/observations.json)의 S1~S4 입력을 그대로 넣고 v2 계약으로 검사했다: **4/4 실패 재현**([run0-v1-red/red.out.txt](scorecard/run0-v1-red/red.out.txt)).
2. **보완** — [scorecard.py](scorecard/scorecard.py) v2(입력 `scorecard-input-2`, 출력 `scorecard-2`).
3. **계약 회귀 1회** — [cases_scorecard.py](scorecard/cases_scorecard.py) **52/52 통과**([run1/cases.out.txt](scorecard/run1/cases.out.txt)). v1 사례21개(S01~S13·C01~C03·D01)는 기대 금액을 바꾸지 않고 입력 형식·칸 이름만 옮겼다. 반례·경계 31개를 추가했다.
4. **변형 검출** — [mutate.py](scorecard/mutate.py)가 결함 10개를 하나씩 넣은 임시 사본으로 사례를 돌려 **10/10 검출**([run1/mutate.out.txt](scorecard/run1/mutate.out.txt)). 원본은 바꾸지 않는다.

## 2. 반례별 계약

| 반례 | v1 출력 | v2 계약 | 사례 |
|---|---|---|---|
| S1 이월 미실현 | `unrealized_derived = −10` | 칸을 없애고 **기초/기말 미실현**(스냅숏 보유의 원가 `cost_krw`가 있을 때만, 없으면 미확인)과 **기간 중 미실현 증감**(= 월손익 − 기간 실현)을 따로 둔다. 셋이 확정이면 실현+증감=월손익 항등식을 대조한다. 왕복은 **생애 손익**과 **이 달 몫**을 따로 두고 이월을 표시, 승패는 이 달 종료 왕복의 생애 손익 기준 | R1(기말0·증감−10·기초 미확인), R1b(원가100 → 항등식 일치), R1c(11월 일부·12월 나머지 매도: 실현30·생애50 분리) |
| S2 월 밖 입출금 | 1월 배정을 12월에서 빼 −50 확정 | 입출금 항목은 `kind`(ALLOCATE/RECOVER)·양수 금액·시각 필수. 기간 밖·세션 생성 전·부호 오류는 **계약 위반(rc2)**. 이력 자체가 없으면 미확정(0으로 채우지 않음). 계좌 입출금도 시각 있는 목록만 받는다 | R2·R2b~R2h, C04(CLI rc2) |
| S3 실자금/모의 혼합 | PAPER를 계좌 대조에 넣어 거짓 −10 | 돈 종류(실자금 = LIVE·DYNAMIC REAL, 모의 = PAPER·DYNAMIC PAPER)를 섞은 전체 합을 내지 않는다. **계좌 대조는 실자금 합과만**. 묶음은 돈 종류 안에서 종류·모드·전략/주기·코드 버전별. 모드 없음·LIVE+PAPER 모순은 rc2 | R3·R3b~R3f |
| S4 낙폭 비율 | 금액 최대 지점의 −40%만 | **최대 금액 낙폭**과 **최대 비율 낙폭**을 각각(구간도 각각) 낸다. 입출금은 금액에서 누적 순입금을 빼고, 비율에서는 입출금이 직전 표본 직후라고 본 연쇄 지수로 뺀다 → 단순 출금·배정은 손익 아님. 표본 역순·중복은 rc2, 25시간 넘는 간격은 결측 구간으로 표시하고 채우지 않음. 입출금 이력이 없으면 낙폭 미확정 | R4(−400/−50%), R4b(회수500 → 0), R4c(배정900 → −400/−40%), R4d~R4h |

## 3. 함께 점검한 경계 (§3 본문)

| 점검 | 결과 |
|---|---|
| 월 귀속 시각 | 주문 `created_at`은 체결/정산 시각이 아니다. v2는 `settled_at`만 받고 v1 이름 `at`은 거절한다. 시각이 없으면 이 달 실현을 미확정으로 둔다(R1d, R1h). 기존 출처: `order.filled_at`·`order.sell_applied_at`(LIVE·DYNAMIC 매도 반영)·`dynamic_sell_settlement.settled_at`. 표본 칸 이름도 `date` → 시간대 있는 `at`(R4h) |
| 매수 예약만 있고 포지션 없음 | 0e1d2a02는 매수 예약 때 세션 `available_krw`에서 먼저 빼고(`CapitalReservationService` S:253·283) 금액을 `order.reserved_krw`에 남긴다. 그래서 가용 KRW만으로는 총자산이 모자란다. v2 스냅숏은 `pending_buys` 목록을 필수로 받아 예약 금액을 더한다(R1e). 예약 금액이 없거나 일부 체결된 예약은 그 시점 평가를 미확정으로 둔다(R1f). 목록이 없으면 0으로 보지 않고 rc2(R1g) |
| 공통 경제 손익 | 실현은 양쪽 수수료를 포함한 주문 원천이다(S01·S13 유지). REAL 저장 손익과의 차이는 불일치로 표시만 한다. 위험 관문 영향은 [dec-pnl-definition-20261119](../dec-pnl-definition-20261119/README.md) |

## 4. 바뀐 입력·출력 이름 (v1 → v2)

입력: `version` `scorecard-input-2` · 매수/매도 `at` → `settled_at`(null 허용 = 미확정) · 스냅숏 `pending_buys` 필수 · 보유 `cost_krw` 선택 · `flows[].kind` 필수·금액 양수 · `created_at` · `daily_equity[].date` → `at` · 계좌 `net_deposits_krw` → `deposits[{at, amount_krw}]` · `mode` 필수.
출력: `realized_from_orders` → `realized_in_period` · `unrealized_derived` 삭제 → `unrealized_start/end/change`·`identity_check` · `round_trips` → `round_trips_closed`(개수)와 `round_trips`(생애/이 달 몫) · `drawdown.value_krw/pct` → `max_amount_krw/max_pct`(+구간·결측) · 묶음 `all` 삭제 → `money` 등 · `account_minus_sessions` → `account_minus_real_sessions`.

## 5. 재현·한계

```
python -I scorecard/red_v1.py --out scorecard/run0-v1-red
python -I scorecard/cases_scorecard.py --out scorecard/run1
python -I scorecard/mutate.py --out scorecard/run1
```

Windows Python 3.13.14. 산출물은 LF·UTF-8, [SHA256SUMS](SHA256SUMS).

- 모두 합성 입력이다. 실제 입력 추출 SQL·월 경계 시세 출처·일별 평가 수집은 승인된 읽기 범위가 정해진 뒤다.
- 비율 낙폭의 입출금 시점 가정(직전 표본 직후)은 표본 사이 가격 경로를 모르는 데서 오는 관례다. 장중 낙폭이 아니다.
- 원가 `cost_krw`를 만들 운영 출처(포지션 `invested_krw`의 부분 매도 뒤 의미)는 아직 확인하지 않았다. 없으면 미실현은 미확인으로 남는다.
