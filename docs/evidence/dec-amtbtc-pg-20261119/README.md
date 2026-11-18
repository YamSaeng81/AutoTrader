# 12월 준비 14 — 금액/BTC 통합 후보 aff357e2 좁은 PG 확인 (총괄 v1.190 §4-1)

2026-11-19 KST, 개발 세션 제출(총괄 판정 전). 대상은 통합 후보 **`aff357e2ae3fd9278373704460968dfca05cd240`**(금액/BTC + 모드/쿨다운, [12월 준비 8](../dec-amtbtc-20261118/README.md)) 그대로다. 그 커밋에서 분리한 작업 사본 `D:/tmp/amtpg-aff357e2`(detached, 미커밋)에 시험 파일 1개만 추가했고, 로컬 격리 PG `cat-rt-pg`의 새 DB `amtbtc_pg`(Flyway 운영 마이그레이션)에서 **1개 시험·1회** 실행했다. H2 59/59·수용된 매수/매도 PG는 반복하지 않았다. 운영·배포 없음. 금액은 가상값이다.

## 결과 ([run1/amount_btc_pg.json](run1/amount_btc_pg.json), XML 1/1 성공)

| 확인 | PG 결과 |
|---|---|
| JSONB 저장 표현 | 생성 경로 `createSession`으로 `{"maxOrderKrw": 20000.00, "excludeCoins": ["KRW-XRP","KRW-BTC"], ...}` → 저장 `{"maxOrderKrw": 20000, "excludeCoins": ["KRW-BTC", "KRW-XRP"], "slAtrMultiplier": 2}`(`jsonb_typeof` number/array). 키 순서·표기가 다른 입력도 같은 저장값. 두 키 없는 세션은 `strategy_params IS NULL` |
| 재조회·복원·지문 | 영속성 컨텍스트를 비운 DB 재조회 뒤 복원값 동일, 지문 `895ab6b8dc16` = 생성 시점 = 다른 순서 입력. 두 키 없는 고정 세션 지문 `ea1ed76bc345`(0e1d2a02 값) 유지 |
| 매회 상한 | REAL 가용 25,001×0.8 = 20,000.80 → 주문 행 `quantity` 10,000·`reserved_krw` 10,000·SUBMITTED, 가용 15,001. 키 없는 세션은 종전대로 `PER_POSITION_CAP` 거절·주문 0·가용 그대로 |
| BTC 신규 매수 제외 | 감시 목록 `[KRW-ETH]`, 최종 진입·회차 승인 모두 "제외 코인(excludeCoins) — KRW-BTC 신규 매수 안 함", BTC 주문 0, KRW-ETH는 매수 |
| BTC 급락 관문 입력 | 평가 회차(`processTick`)에서 BTC 봉이 없으면 제외 설정과 무관하게 매수 0, BTC 봉이 최신이면 KRW-IOTA 매수 |
| 제외 코인 기존 보유 매도(실자금) | KRW-BTC 보유(시험이 넣은 행) → 실제 매도 경로 제출·폴러 체결·청산 확정으로 CLOSED. H2는 PAPER였고 이번은 REAL |

## 구분·한계

- "다시 읽기"는 같은 JVM·스프링 문맥에서 영속성 컨텍스트를 비운 **DB 재조회**다. 앱 재기동 뒤 복원 증거가 아니다.
- 4번의 보유 행은 시험이 직접 넣었다. 엔진 매수 체결을 주장하지 않으며, 그래서 매수 차감이 없어 매도 뒤 가용이 20,000 + 9,095.45 = 29,095.45가 된다. 매도 경로가 제외 설정에 막히지 않는다는 것만 본다.
- 세션 생성 뒤 포지션당 상한 축소·최소 주문 보정·PAPER 같은 식·생성 거절 14가지는 H2에서 확인했고 PG에서 반복하지 않았다.
- 거래소 응답·시세는 대역이다. 운영 금액·총원금·월 손실 상한 승인 아님.

## 재현

```
docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS amtbtc_pg" -c "CREATE DATABASE amtbtc_pg"
SR_PG_URL=jdbc:postgresql://localhost:15432/amtbtc_pg AMT_OUT=<run 폴더> ./gradlew :web-api:test --tests '*AmountBtcPgTest'
```

Windows, JDK 17.0.12. 시험 원문 [source/AmountBtcPgTest.java](source/AmountBtcPgTest.java), 체크섬 [SHA256SUMS](SHA256SUMS).
