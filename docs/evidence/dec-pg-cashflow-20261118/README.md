# 12월 준비 6 — P7 격리 PG 실제 현금흐름 대조 + 시계 축 사례·메타데이터안 (총괄 v1.188 §3-1·§3-2)

2026-11-18 KST, 개발 세션 제출(총괄 판정 전). 운영 접속·운영 DB·HTTP·배포·제품 수정은 하지 않았다. PG는 기존 로컬 격리 컨테이너 `cat-rt-pg` 안의 새 DB `p7_cashflow`(Flyway가 운영과 같은 마이그레이션 적용)만 썼다.

## 1. 무엇을 실제 경로로 돌렸나

| 구분 | 내용 |
|---|---|
| 제품 코드 | 운영 반영 커밋 **0e1d2a02**의 작업 사본 `D:/tmp/p7pg-0e1d2a02`(main의 조상이 아니므로 main이 아닌 이 커밋에서). 시험 클래스 1개만 추가([source/P7CashflowPgTest.java](source/P7CashflowPgTest.java)), 제품 파일 변경 0(`git status`에 새 시험 파일 하나) |
| 엔진 경로 | 예약·제출 = 실제 매수 경로(LIVE `executeSessionBuy`, DYNAMIC `executeBuy`) · 부분 체결/취소 종결 = 폴러 진입점 `checkOrderStatus` · 늦은 수수료 정정·같은 응답 반복 = 정산 `settle`. 풀 손실 가드는 켠 채 대역 실시간 시세 1건만 넣었다(다른 시험과 같은 방식) |
| 대역 | **거래소 응답만** 대역(`UpbitOrderClient`). 대역 거래소 계좌 = KRW 1,000,000.00 + 기존 보유 XRP 5(어느 세션 것도 아님) + 돌려준 응답별 체결 효과. 시험이 직접 만든 행은 **세션 행 2개(새 배정)**뿐이며 주문·포지션·잔액 변경은 모두 엔진이 썼다 |
| 스냅샷 | 시점마다 P7 v2 [fingerprint.sql·extract.sql](../dec-reconcile-20261118/p7v2/) 원문을 그대로 실행(앞 지문 → 거래소 앞 → 추출 → 거래소 뒤 → 뒤 지문). 15개 [run1/snaps](run1/snaps/), 앞·뒤 지문 모두 같음 |
| 판정 | [assemble.py](assemble.py)가 스냅샷을 v4 대조기([4706dc50…](../dec-reconcile-v4-20261118/p7v4/reconcile.py)) 입력으로 조립. 등록표는 스냅샷 세션 행에서 만들고 규칙은 `max_open_positions 1`·`max_buy_request_krw 20000` |
| 실행 | Gradle `:web-api:test --tests '*P7CashflowPgTest'` **1/1 성공**([XML](run1/gradle/)), 대조 **30/30 기대대로**([pairs.out.txt](run1/pairs/pairs.out.txt)) |

## 2. 현금흐름 결과 (LIVE·DYNAMIC 같은 값)

시점별 주문·세션·포지션·거래소 상태는 [states.txt](run1/states.txt). 요청액은 LIVE 16,159.16 / DYNAMIC 8,000.29(실제 경로가 둘째 자리 버림), 체결 25.5개·대금 7,650.0037·수수료 3.82500185 → 정정 3회(+0.003씩) 3.83400185.

| 구간 | 판정 | 거래소 KRW 증감 | 세션 장부 증감 | 원시 잔차 | 설명된 반올림 | 조정 잔차 |
|---|---|---|---|---|---|---|
| 01 새 배정(세션 행 생성) | OK | 0 | 0(배정은 `internal_allocations`) | 0 | 0 | 0 |
| 02 예약·제출(진행 중) | HOLD `ORDER_IN_FLIGHT`·`EXCHANGE_OPEN_ORDERS` | 0 | −요청액 | (그 코인·KRW 보류) | | |
| 03 부분 체결(활성) | HOLD | −6,003.004002 | 중간 환급 반영 | (보류) | | |
| **04 예약→부분 체결→취소 종결·환급** | **OK** | −7,653.82870185 | −7,653.83 | 0.00129815 | 0.00129815 | **0** |
| **05 늦은 수수료 정정 3회**(APPLIED×3) | **OK** | −0.009 | −0.01 | 0.001 | 0.001 | **0** |
| 06 같은 응답 반복(NO_CHANGE) | OK | 0 | 0 | 0 | 0 | 0 |
| 07 거래0·기존 보유(포지션·XRP) | OK | 0 | 0 | 0 | 0 | 0 |
| 08 새 배정부터 끝까지 | OK | −7,653.83770185 | −7,653.84 | 0.00229815 | 0.00229815 | 0 |

음성 대조(종류마다 5개, 모두 ANOMALY로 잡힘): 거래소 KRW −1 → `ACCOUNT_KRW_MISMATCH`(조정 −1.00) · 거래소 RND +0.1 → `ACCOUNT_COIN_MISMATCH` · DB만 정정되고 거래소는 정정 전 → `ACCOUNT_KRW_MISMATCH`(조정 0.009) · 세션 잔액 +0.01 → `SESSION_LEDGER_MISMATCH` · 등록표에서 그 세션 제외 → `UNREGISTERED_REAL_*`. 비교가 형식만 통과하는 것이 아님을 보인다.

**경계 구분:** 거래0(06·07)은 지문·잔액·주문이 그대로이고 증감 0. 기존 보유는 거래소의 XRP 5(어느 세션 것도 아님)와 앞 세션의 열린 포지션(D 구간에서 L#1)이 `base_holdings`·담보 대조로만 쓰이고 증감에 섞이지 않는다. 새 배정(01)은 거래소 유입이 아니라 내부 배정으로 기록된다.

**제품 동작 관찰(판정 아님):** 부분 체결(활성) 중에는 주문 `filled_quantity`·포지션 수량이 0인 채 반영 대금/수수료와 세션 중간 환급만 들어간다(L3: 10,237.37). 대조기는 진행 중 주문으로 그 코인·KRW를 보류하므로 이 중간 상태를 일치로 쓰지 않는다. 취소 종결은 체결량이 있으면 주문 상태가 `FILLED`가 된다.

## 3. 시계 축 — 실제 로컬 사례 (§3-2)

수집 단계마다 수집기(JVM) 시각과 DB `clock_timestamp()`를 함께 남겼다([clock.json](run1/pairs/clock.json)).

| 관측 | 값 |
|---|---|
| DB 시계 − 수집기 시계 | **+1,366 ~ +1,498 ms**(15 스냅샷 75단계), 단계별 왕복 최대 5.7 ms |
| 원인(추정) | Docker Desktop 가상 머신 시계와 Windows 시계의 차이. 운영 Linux에서 같은 차이가 있는지는 **미확인** |

이 차이가 판정에 들어오는 곳이 두 군데 확인됐다.

1. **수집 시각 ↔ DB 추출 시각(09):** 수집기 시계로 단계를 적으면 DB `observed_at`이 거래소 앞·뒤 읽기 밖으로 나가 `DB_TIME_OUTSIDE_COLLECTION` 보류가 된다(계산값은 같음). 같은 단계를 DB 시계로 적으면 사이에 들어온다. 현재 v4의 보류 동작은 맞다.
2. **앱이 쓴 시각 ↔ DB 시각(01b, 새 발견):** 세션 `created_at`은 앱이 `Instant.now()`로 쓴다(앱 시계). 등록 적용 시작을 DB 시계(기준 `observed_at`)로 두면, 실제로는 기준 뒤에 만든 세션이 1.4초 앞서 보여 `REGISTRY_CONTRACT_MISMATCH` **거짓 이상**이 된다(01b·D01b가 이 한계를 재현). 적용 시작을 앱 시계 축(이 시험은 수집기와 같은 JVM)으로 두면 OK. 주문 `created_at`도 앱 시계이므로 거래소 조회 기간 경계 비교(v4 U1)에도 같은 축 문제가 있다.

**메타데이터안(제출, 미구현):**

- 관측 `meta.steps[]`의 각 단계에 `at`(수집기)과 함께 `at_db`(같은 연결의 `clock_timestamp()`)·`rtt_ms`를 남긴다. 단계 순서·추출 시각 검사는 DB 축으로 한다.
- 관측마다 `meta.app_clock`을 둔다: 앱이 쓴 가장 최근 행의 앱 시각과 DB `now()`를 같은 트랜잭션에서 읽은 쌍, 또는 앱 상태 응답의 서버 시각과 그 직후 DB 시각. 앱 시각 열(`created_at` 등)과 DB 시각을 비교할 때 이 차이 범위를 쓴다.
- 등록표 `effective_from`에는 축(`clock: app|db`)을 적는다.
- 차이 범위가 없거나 비교 여유보다 크면 **보류**(새 코드 예: `CLOCK_AXIS_UNCONFIRMED`)이며 임의 유예 시간을 두지 않는다. v4는 아직 축을 몰라 01b에서 이상을 낸다 — 다음 보완 후보로 남기고 이번 제출에서 v4를 바꾸지 않았다.
- 운영 DB 비밀번호나 추가 접속을 요구하지 않는다. 실제 운영 차이는 승인된 읽기 범위가 정해진 뒤 위 메타데이터로 잰다.

## 4. 파일·재현

| 파일 | 역할 |
|---|---|
| [source/P7CashflowPgTest.java](source/P7CashflowPgTest.java) | 작업 사본에 추가한 시험(0e1d2a02 위). 제품 변경 없음 |
| [run1/snaps/](run1/snaps/) | 15 스냅샷(DB 추출·지문·대역 거래소·단계 시각 두 축) |
| [assemble.py](assemble.py) · [run1/pairs/](run1/pairs/) | 조립·판정 30쌍과 입력/결과, 시계 요약 |
| [run1/gradle/](run1/gradle/) | Gradle 결과 XML(표준 출력 포함) |

```
docker exec cat-rt-pg psql -U trader -d postgres -c "DROP DATABASE IF EXISTS p7_cashflow" -c "CREATE DATABASE p7_cashflow"
SR_PG_URL=jdbc:postgresql://localhost:15432/p7_cashflow P7_SQL_DIR=<p7v2 폴더> P7_OUT=<run/snaps> ./gradlew :web-api:test --tests '*P7CashflowPgTest'
python -I assemble.py --snaps <run/snaps> --impl ../dec-reconcile-v4-20261118/p7v4/reconcile.py --out <run/pairs>
```

환경: Windows, JDK 17.0.12, Python 3.13.14, `cat-rt-pg`(127.0.0.1:15432). 다른 PG 시험·전체 회귀·jar는 돌리지 않았다.

## 5. 한계

- 매수 한 건(시장가)·LIVE/DYNAMIC 각 1세션만 다뤘다. 매도 정산·지정가·매도 부분 체결 뒤 취소는 이번 범위 밖이다(v2 한계 유지).
- 거래소는 대역이다. 완료 목록 조회는 대역이 기억하는 전부라 `complete=true`이며 실제 API 조회 증거가 아니다.
- 시계 차이는 이 PC의 Docker Desktop 값이다. 운영 서버 앱·DB·수집 PC의 차이는 재지 않았다.
- 01b(앱 시계 축 거짓 이상)는 v4 대조기의 남은 한계로 기록만 했다.
