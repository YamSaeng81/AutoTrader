# 12월 준비 10 — P7 격리 PG 매도 현금흐름 보충 + v5 시계 메타데이터 첫 기록 (총괄 v1.189 §3-3·§2-4)

2026-11-18 KST, 개발 세션 제출(총괄 판정 전). 운영 접속·운영 DB·HTTP·배포·제품 수정은 하지 않았다. 수용된 매수 PG 시험([439차](../dec-pg-cashflow-20261118/README.md))은 다시 돌리지 않았다. PG는 로컬 격리 컨테이너 `cat-rt-pg`의 새 DB `p7_sell`(Flyway가 운영과 같은 마이그레이션 적용)만 썼다.

## 1. 실제 경로와 대역

| 구분 | 내용 |
|---|---|
| 제품 코드 | 운영 반영 커밋 **0e1d2a02** 작업 사본 `D:/tmp/p7pg-0e1d2a02`. 시험 클래스 [P7SellCashflowPgTest](source/P7SellCashflowPgTest.java) 1개만 추가했고 제품 파일 변경은 0이다 |
| 보유 만들기 | 실제 매수 경로(LIVE `executeSessionBuy`, DYNAMIC `executeBuy`)와 폴러로 전체 체결 1회만 했다(RND 25개, 대금 7,500, 수수료 3.75). 매수 쪽 부분 체결·취소·정정은 반복하지 않았다 |
| 매도 | **실제 매도 경로**: LIVE `executeSessionSell`, DYNAMIC `executeSell` → `markClosingWithSellIntent`(CLOSING과 매도 의도를 한 트랜잭션에서) → 제출. 부분 체결과 체결 있는 취소는 폴러 `checkOrderStatus`, 확정은 청산 확정 `reconcileClosingPositions`(LIVE)·`reconcileDynamicClosingPositions`(DYNAMIC)이 처리한다. 잔량은 같은 매도 경로로 다시 청산했다. 늦은 매도 수수료 정정과 같은 응답 반복은 정산 `settle`로 넣었다 |
| 대역 | **거래소 응답만** 대역이다. 대역 장부 = KRW 1,000,000.00 + 기존 XRP 5 + 응답별 체결 효과. 시험이 직접 만든 행은 세션 행 2개뿐이고 주문·포지션·잔액은 모두 엔진이 썼다. 보호 관문을 우회하지 않았다(풀 손실 가드 켬, 시세는 대역 실시간 1건, 공유 풀 총액·슬롯은 시험용으로 넓힘 — 매수 시험과 같은 처리) |
| 스냅샷 | 시점마다 P7 v2 `fingerprint.sql`·`extract.sql` 원문을 실행했다. 대조기 입력 밖의 보충 기록으로 포지션 평균가·투입액·실현손익·수수료, DYNAMIC 매도 정산 행, 주문 반영값을 남겼다(판정에는 쓰지 않음) |

흐름(LIVE·DYNAMIC 같음): 보유 25 → 매도 제출(CLOSING) → 매도 10 부분 체결(활성) → 체결 있는 취소 → 판 10만 반영하고 **잔량 15 OPEN 유지**(주문 상태 FILLED) → 잔량 15 재청산 → 포지션 CLOSED → 매도 수수료 정정 2회(APPLIED×2) → 같은 응답 반복(NO_CHANGE).

## 2. v5 대조 결과 — [run1](run1/pairs/pairs.out.txt) 24/24 기대대로

Gradle `:web-api:test --tests '*P7SellCashflowPgTest'` 1/1 성공([XML](run1/)). 대조기는 [v5](../dec-reconcile-v5-20261118/README.md)(`reconcile.py` 2ca03476…)이고 조립기는 [assemble_sell.py](assemble_sell.py)다.

| 구간(L·D 같은 값) | 판정 | 거래소 KRW 증감 | 세션 장부 증감 | 원시 잔차 | 설명된 반올림 | 조정 | RND 거래소/체결/포지션 |
|---|---|---|---|---|---|---|---|
| 1→2 매도 제출 | HOLD `ORDER_IN_FLIGHT`·`POSITION_CLOSING`·`EXCHANGE_OPEN_ORDERS` | 0 | 0 | (보류) | | | 0/0/0 |
| 2→3 부분 체결(활성) | HOLD | +3,098.45 | 0 | (보류) | | | −10/0/0 |
| **1→4 체결 있는 취소·잔량 유지** | **OK** | +3,098.45 | +3,098.45 | 0 | 0 | **0** | −10/−10/−10 |
| **4→5 재청산** | **OK** | +4,647.675 | +4,647.68 | −0.005 | −0.005 | **0** | −15/−15/−15 |
| **5→6 매도 수수료 정정 2회** | **OK** | −0.006 | −0.01 | 0.004 | 0.004 | **0** | 0 |
| 6→7 같은 응답 반복 | OK | 0 | 0 | 0 | 0 | 0 | — |
| 1→7 전체 | OK | +7,746.119 | +7,746.12 | −0.001 | −0.001 | 0 | −25/−25/−25 |

음성 대조는 종류마다 5개이고 모두 ANOMALY다: 거래소 KRW −1 → `ACCOUNT_KRW_MISMATCH`(조정 −1.00), 거래소 RND +0.1 → `ACCOUNT_COIN_MISMATCH`, DB만 매도 정정되고 거래소는 정정 전 → `ACCOUNT_KRW_MISMATCH`(조정 0.006), 세션 잔액 +0.01 → `SESSION_LEDGER_MISMATCH`, DB 잔량 포지션만 +1 → `ACCOUNT_COIN_MISMATCH`·`ACCOUNT_COIN_UNBACKED`. 대조기가 조정 잔차·포지션 수량·세션 잔액을 실제로 연결해 비교한다는 뜻이다.

## 3. 시계 메타데이터 — 격리 환경 첫 기록(§2-4)

수집기는 단계마다 `start/end`(벽시계)를 남기고, 단계 앞뒤에 같은 연결의 DB `clock_timestamp()` 표본 `{c0, d, c1, mono_rtt_ms}`(단조 시계 `System.nanoTime` 왕복 포함)를 남긴다. 마지막 단계 뒤에도 표본을 둔다. 이렇게 하면 저장 매수 스냅샷에서 남던 `COLLECTION_ORDER_UNCONFIRMED`가 사라진다.

| 관찰 | 값 |
|---|---|
| DB − 수집기 | 2차 실행: **−40.3 → −64.5 ms**로 스냅샷마다 계속 움직임([clock_summary](run1/clock_summary.json)). 1차 실행은 −160.7 ~ −164.9 ms. 같은 날 매수 시험은 +1,366 ~ +1,498 ms. 부호까지 바뀌므로 고정 상수로 쓸 수 없다 |
| 벽시계−단조 왕복 차 | 대부분 0.5ms 이하. 넘는 표본 4개는 시계 이동으로 버렸다(v5 `shift`). 단계 앞(첫 단계 전)·뒤(마지막 단계 뒤) 표본은 수집 구간 밖이라 v5가 쓰지 않는다(`stale` 집계, 보수적 처리) |
| **1차 실행 [attempt1](attempt1/pairs.out.txt)** | 22/24. D67·D17에서 **현재 관측의 DB 추출이 거래소 앞 읽기 뒤인지** 확정하지 못해 `CLOCK_AXIS_UNCONFIRMED`(경계 겹침)가 났다. 메모리 대역 거래소 읽기가 ~0ms라 두 단계가 측정 오차(≈1~2ms) 안에 붙었기 때문이다. 판정 규칙은 바꾸지 않았다. 수집기 단계 사이에 5ms 간격을 두는 쪽(매수 시험과 같음)으로 고쳐 2차 실행에서 24/24가 됐다. 운영 수집기도 단계 간격을 표본 오차보다 넓게 잡아야 한다는 근거다 |

## 4. 실현손익 정의 — 실제 PG 확인 (판정 아님, 제품 관찰)

[realized_check](run1/pairs/realized_check.json): 실자금 평균가는 **300.00000000**(매수 대금 7,500/25, 수수료 3.75 미포함)이다. 저장 `realized_pnl`은 부분 매도 뒤 98.45, 재청산 뒤 246.125, 정정 뒤 246.119다. 주문 원천(대금+수수료로 만든 평균가)으로 다시 계산하면 96.95 / 242.375 / 242.369다. **차이는 판 수량만큼의 매수 수수료(1.5, 3.75)**로 LIVE·DYNAMIC이 같다. DYNAMIC `dynamic_sell_settlement.realized_pnl`도 같은 정의다(98.45 + 147.675). 늦은 매도 수수료 정정 뒤에는 포지션만 246.119로 바뀌고 정산 행은 147.675 그대로 남는다. 반면 DYNAMIC 모의 매수는 수수료 포함 평균가를 쓴다(코드 읽기, [성적표 근거](../dec-scorecard-20261118/README.md#제품-관찰판정-아님-real과-paper의-저장-실현손익-정의-차이)). 따라서 저장 실현손익은 REAL과 PAPER의 정의가 다르다. 현금 장부(세션 잔액)는 맞으며 위 대조가 조정 잔차 0으로 보여 준다. 차이는 손익 표시 열에만 있다. 성적표는 저장값 대신 주문 원천으로 계산한다. 제품은 고치지 않았다.

## 5. 재현

```
docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS p7_sell" -c "CREATE DATABASE p7_sell"
SR_PG_URL=jdbc:postgresql://localhost:15432/p7_sell P7_SQL_DIR=<p7v2 폴더> P7_OUT=<run/snaps> ./gradlew :web-api:test --tests '*P7SellCashflowPgTest'
python -I assemble_sell.py --snaps <run/snaps> --impl ../dec-reconcile-v5-20261118/p7v5/reconcile.py --out <run/pairs>
```

환경: Windows, JDK 17.0.12, Python 3.13.14, `cat-rt-pg`(127.0.0.1:15432). 다른 PG 시험·전체 회귀·jar는 돌리지 않았다.

## 6. 한계

- 시장가 매도 한 흐름이고 LIVE·DYNAMIC 각 1세션이다. 지정가 매도, 여러 포지션, 정지 청산·보호 매도 경로는 이번 범위 밖이다.
- 거래소는 대역이다. 완료 목록은 대역이 기억하는 전부이고, 앱·거래소 시계는 같은 JVM이라는 선언(측정 아님)이다. 운영 시계 차이는 재지 않았다.
- 공유 풀 총액·슬롯을 넓혀 정산만 본 것이다. 운영 한도·부하·전략 수익성 검증이 아니다.
