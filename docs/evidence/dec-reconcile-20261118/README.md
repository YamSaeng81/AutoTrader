# 12월 준비 3 — 원문 연결·P7 v2 로컬 대조기·쿨다운/알림·성적표·금액/BTC 표 (총괄 v1.186 §3)

2026-11-18 KST, 개발 세션. [총괄 v1.186](../review-76387b25/README.md) §3의 1~6을 로컬에서 진행했다. **운영 접속·운영 DB·HTTP·배포·설치 스크립트/RUNBOOK/고정 F 변경은 하지 않았다.** DB 작업은 로컬 시험 컨테이너 `cat-rt-pg` 안의 새 격리 DB(`p7v2_base` = 로컬 `rt_ledger` 사본, 시나리오마다 복제 후 삭제)에서만 했다.

| 산출물 | 위치·SHA | 결과 |
|---|---|---|
| 원문 연결(§3-1) | [raw/](raw/) · [links/](links/) | 남아 있던 XML 8개·앱 로그 보존. red XML·Gradle 콘솔은 **미보존**. 시험 연결표 55행 → **고유 56클래스**로 정정 |
| P7 v2 대조기(§3-2·§3-3) | [p7v2/](p7v2/) | 격리 PG 시나리오 **35/35 기대대로**, 핵심 식 변형 **7/7 검출** |
| 쿨다운 후보 0e1d2a02 이식(§3-4) | 가지 `candidate/dynamic-cooldown-per-kind-0e1d2a02` **89336440**(작업 사본 `D:/tmp/cd-0e1d2a02`) | 제품 변경 patch-id가 81b0e7a7과 같음. 범위 5클래스 **53 성공·실패0·건너뜀0** |
| 알림·성적표·금액/BTC(§3-4·§3-5) | 이 문서 §4~§6 | 0e1d2a02 고정 Git 원문 대조만. 제품 변경 없음 |

## 1. 원문 연결 (§3-1)

### 1-1. 435차 시험 원문

같은 작업 사본에 남아 있던 파일만 그대로 복사했다. 다시 실행하지 않았다. 각 폴더의 `source.txt`(작업 사본 HEAD·추적 파일 변경0·차분 해시)와 `source-mtimes.txt`(원래 경로·수정 시각·크기)에 출처를 남겼다.

| 회차 | 원문 | 소스 | 대역 | 보존 상태 |
|---|---|---|---|---|
| 모드 red(수정 전, 0e1d2a02+새 시험만) | XML 없음 — 같은 사본의 green 실행이 덮어씀. 앱 로그 `system.log` 10:01:33~ 구간에 함께 기록 | 커밋 없는 중간 상태 | H2 | **XML·Gradle 콘솔 미보존.** 435차 [red-summary](../dec-parallel-20261118/mode/red-summary.txt)(10:01:50 UTC 01:01:50)만 남음. 재구성하지 않음 |
| 모드 green | [raw/mode-18f3a795/xml](raw/mode-18f3a795/xml/) 7개(10:03:00~10:03:19 KST) | **18f3a795** 그대로 | H2 | XML 보존. 7클래스 48건 = 성공48 — 435차 요약과 일치. Gradle 콘솔 미보존 |
| P1 첫 실행(rc1) | XML 없음(덮어씀). `trade.log` 10:07:02에 NET-GUARD 차단 1건 | 2302104c 이전 상태 | H2 | XML·콘솔 미보존, [attempt1 요약](../dec-parallel-20261118/p1/attempt1-netguard.txt)만 |
| P1 green | [raw/p1-2302104c/xml](raw/p1-2302104c/xml/) 1개(10:08:22) | **2302104c** 그대로 | H2·`UpbitWebSocketClient` 대역 | XML 보존 8 = 성공8. Gradle 콘솔 미보존 |

앱 로그(`system.log`·`trade.log`)는 `gzip -n`으로 압축 보존했고, 원본 해시는 각 `logs/original-logs.sha256`에 있다. 압축을 푼 해시가 원본과 같음을 확인했다. 로그의 `Secret` 일치는 서명 클래스 이름(`SecretKeySpec`) 스택뿐이다. XML의 `hostname`은 개발 PC 이름이다.

### 1-2. 초안 인용 시험 연결표 정정

[link_tests.py](links/link_tests.py)는 초안을 **Git 고정 블롭**(76387b25의 `dec-dynamic-path-20261118/README.md`, blob 1b5dbe77)에서 읽는다. `Prefix{A,B}Suffix`는 클래스별로 펼치고, `DynamicBlackSwan*(3)`은 고정 회귀 원문 목록으로 펼쳐 개수(3)와 맞는지 확인한다. 이름은 한 번만 센다. 결과는 [draft-test-links-v2.txt](links/draft-test-links-v2.txt)다.

- **고유 56클래스 = 성공49 · 전부 건너뜀7(PG)**, 시험 437건 = 성공394 · 건너뜀43 · 실패0. 원문은 417차 [runs/full-0e1d2a02](../v168-final-20261115/runs/full-0e1d2a02/)(0e1d2a02, H2 기본·PG 환경 변수 끔)다. 각 행에 원문 XML sha256 앞 12자리를 붙였다.
- 옛 표 55행과의 차이: 잘린 이름 `SessionCreationTest` 1행 삭제 → `WalkForwardGate{Disabled,Enabled}SessionCreationTest` **2클래스로 풀어 연결**(1·4건 성공).
- PG 7클래스는 여전히 **건너뜀**이며 성공으로 바꾸지 않았다. 0e1d2a02의 PG 실행은 SettlementRoundingPg(n04)만 있다는 435차 설명을 유지한다.
- `DynamicBlackSwanCooldownCrossKindTest` 4건 성공은 **간섭이 존재함을 확인한 재현 시험의 성공**이다. 안전 근거로 읽지 않는다(§3).

## 2. P7 v2 — 병행 대조 등록표·로컬 대조기 (§3-2·§3-3)

설치본 c669ccdc·RUNBOOK F1/F3/F5·SID203 F3의 ±1원 계약은 **그대로 둔다.** 아래는 별도 절차 번호로 판정받을 새 초안이다.

### 2-1. 파일

| 파일 | 역할 |
|---|---|
| [extract.sql](p7v2/extract.sql) | `REPEATABLE READ READ ONLY` 트랜잭션 하나에서 세션(LIVE·DYNAMIC 전부)·주문·열린/CLOSING 포지션·미해결 정산 불일치·DB 지문을 JSON 한 줄로 낸다. 모드/종류로 미리 거르지 않는다 |
| [fingerprint.sql](p7v2/fingerprint.sql) | 추출 앞뒤의 같은 지문(주문 상태·체결·반영·정산 버전, 열린 포지션, 세션 잔액/상태/모드) |
| [reconcile.py](p7v2/reconcile.py) | 파일 입력만 받는 대조기(네트워크·DB 접속 없음). 등록표 + 기준 관측 + 현재 관측 → `OK / HOLD / ANOMALY` |
| [registry.example.json](p7v2/registry.example.json) | 등록표 형식 예시(값은 로컬 가정) |
| [scenarios.py](p7v2/scenarios.py) · [mutate.py](p7v2/mutate.py) | 격리 PG 시나리오와 변형 검사 |

### 2-2. v1.186 §2 반영

1. **공통 기준 시점의 증감.** `Δ거래소(잔고+잠금) − 신고된 외부 이동 = 구간 순체결량(등록 주문) = Δ등록 열린 포지션 수량`을 코인별로 맞춘다. 주문은 기준·현재 스냅샷 사이에 바뀐 값만 센다. 기준 뒤 수수료 정정도 정정분만 들어간다(G4). 기초 보유는 `base_holdings`에 출처와 함께 낸다(B1: SID203 BTC 0.0002 보유, 구간 증감0 → 일치).
2. **배정과 입금 구분.** 계좌식은 주문 단위라 새 세션의 초기 배정이 들어가지 않는다. 배정은 `internal_allocations`로만 보인다(C1). 세션 장부 검사는 `Δavailable_krw = Σ주문 장부 증감`이고, 구간 안 새 세션의 기준은 초기 배정이다. 정지한 등록 세션도 그대로 센다(C2: 정지 뒤 잔액이 어긋나면 이상). 외부 입출금·수동 매매는 `external_flows`(DEPOSIT/WITHDRAW/MANUAL_TRADE, 근거 ref)로 받는다. **이력이 없으면 보류**한다(F3).
3. **허용 오차.** 원 단위 오차를 두지 않는다. 제품 규칙([BalanceAmounts](../../../web-api/src/main/java/com/cryptoautotrader/api/service/BalanceAmounts.java), 0e1d2a02와 같은 파일)대로 주문마다 계산한다. 매수는 `−예약액 + round2(요청 − 대금 − 수수료)`, 매도는 `+round2(대금 − 수수료)`가 세션 장부다. 거래소는 각각 `−(대금+수수료)`, `+(대금−수수료)`다. 따라서 설명 가능한 차이는 주문당 `x − round2(x)`(절댓값 0.005원 이하)다. 출력은 **원시 잔차·설명분·조정 잔차** 셋이다. 조정 잔차는 `--krw-quantum`(기본 0) 안이어야 일치다. 실제 거래소 잔고 표기 자릿수를 확인한 뒤 이 값을 판정받는다.
4. **이상 우선.** `anomalies`와 `holds`를 따로 낸다. 이상이 하나라도 있으면 보류가 있어도 `ANOMALY`다(H1 UNKNOWN, H5 진행 중 매도 + 확인된 정산 불일치). 보류 사유는 코드·주문/포지션 ID·관측 시각으로 낸다. 새 대기 시간 기준은 두지 않았다. 진행 중 주문은 경과 시간과 관계없이 보류이고, 오래됨 판정은 기존 N03 `stale_min`에 맡긴다.
5. **등록 키와 계약.** 키는 `(kind, id)`다. DB 행의 모드·전략·주기·초기 자본·(LIVE) 코인·생성 시각 ≥ 적용 시각이 모두 맞아야 등록 세션이다(D1·D2·D4). 등록표가 하나라도 잘못되면(중복 키·알 수 없는 규칙·형식) 전체를 믿지 않고 모든 실자금 주문을 등록 밖으로 본다(D3·D5). 세션 행이 없는 주문도 실자금으로 보고 잡는다(E2). 규칙은 세션별 등록값이다. SID203 `max_filled_buys_in_window=1`을 DYNAMIC에 복사하지 않는다(R2: DYNAMIC 구간 매수 2회 정상). DYNAMIC 예시 규칙은 `max_open_positions`(현행 단일 포지션 상태 머신)·`excluded_coins`·`max_buy_request_krw`이고 숫자는 가정이다. 같은 포지션에 체결 매수가 2건 이상이면 종류와 관계없이 이상이다.

**추가로 넣은 검사:** 증감이 맞아도 거래소 실보유가 등록 세션의 열린 포지션 수량·가용 현금 합을 덮지 못하면 `ACCOUNT_*_UNBACKED` 이상이다(F4: BTC 출금을 신고하면 증감식은 맞지만 SID203 포지션 0.0002를 거래소가 갖고 있지 않음). 거래소에만 있고 DB에 없는 주문 uuid도 이상이다(E3).

### 2-3. 일관된 관측안 (§3-3, 운영 조회는 승인 전)

한 회차 순서: `DB 지문(앞) → 거래소 잔고·미체결·최근 완료 주문(앞) → DB 추출(스냅샷 지문 포함) → 거래소(뒤) → DB 지문(뒤)`.

- **통과 조건:** 세 DB 지문이 같고, 거래소 앞뒤의 잔고·미체결 목록·완료 주문 목록이 같고, 거래소 미체결이 0이다. 하나라도 다르면 `UNSTABLE_DB`/`UNSTABLE_EXCHANGE`/`EXCHANGE_OPEN_ORDERS` 보류다(H7·H8·H2). 읽는 사이 주문이 생긴 입력은 통과하지 않는다.
- 조회 실패·관측 누락은 `QUERY_FAILED`/`OBSERVATION_MISSING` 보류다(H4). 보류 때 계산값은 참고로만 낸다.
- 기준을 얻으려고 세션을 정지·청산하거나 같은 조회를 반복하지 않는다. 안정 구간을 못 얻으면 그 회차를 보류로 기록하고 끝낸다. 재시도 간격·횟수는 새 기준이라 정하지 않았다.

### 2-4. 시나리오 결과 — [scenarios.out.txt](p7v2/scenarios.out.txt) · 회차별 입력/출력 [out/](p7v2/out/) · [run.txt](p7v2/run.txt)

거래소 잔고는 시나리오마다 손으로 계산한 고정 숫자다. 예: A1 = 100000 − 15991.987654 − 7.99599383 = 84000.01635217. 마지막 열은 초안식 `|원시 잔차| ≤ 세션 수×1원`을 참고로 계산한 값이다.

| 구분(지시 항목) | 시나리오 | 결과 |
|---|---|---|
| 정상 ETH 보유/왕복 | A1·A2 | OK. 원시 −0.00364783 / +0.00110217 = 설명분, 조정0 |
| 기존 BTC 보유 | B1·B2 | OK. 현재 보유0.0002 ≠ 구간 증감0을 증감으로 맞춤 |
| 서로 다른 시작/정지 | C1·C2 | OK(배정은 내부 이전) / 정지 세션 잔액 불일치는 ANOMALY |
| 같은 ID 다른 종류·잘못된 등록 | D1~D5 | 모두 ANOMALY(계약 불일치·행 없음·중복 키·소문자 `paper`·알 수 없는 규칙) |
| 미등록 주문 | E1~E4 | 미등록 세션/세션 행 없음/거래소에만 있는 주문 ANOMALY, 수동 매매 신고분은 OK |
| 외부 입출금 | F1~F4 | 신고 입금 OK, 미신고 ANOMALY, 이력 없음 HOLD, 코인 출금으로 보유 부족 ANOMALY |
| 부분 체결·수수료·반올림 | G1~G4 | 부분 체결 취소 OK. **2세션 원 단위 정확 + 설명 불가 1.50원 → ANOMALY**(초안식이면 통과). 정확한 경우 OK, 수수료 정정 OK |
| UNKNOWN/CLOSING/반영 대기/조회 실패/불안정 | H1~H8 | UNKNOWN·정산 불일치·미해결 불일치 행은 ANOMALY. CLOSING·반영 대기·조회 실패·읽는 사이 변경은 HOLD |
| 세션별 규칙 | R1~R4 | DYNAMIC BTC 진입·요청액 상한 초과·SID203 구간 매수2 ANOMALY, DYNAMIC 매수2 OK |

**변형 검사** — [mutation.out.txt](p7v2/mutation.out.txt). 사본에서 식 하나씩만 바꿨다. 반올림 설명 제거(9개 시나리오 실패)·보류가 이상을 가림(3)·증감 대신 현재 보유(4)·ID만으로 등록(2)·세션 행 없는 주문 무시(1)·관측 안정성 생략(4)·원 단위 2원 허용(1)이 **모두 시나리오에서 걸렸다.** 첫 기록 실행에서는 M1이 "잡힌 시나리오 0"으로 나왔다. 원인은 직전 실행의 시나리오 DB 연결이 남아 DB 삭제가 실패한 실행기 오류였다. `DROP … WITH (FORCE)`로 고친 뒤 다시 실행했다. 이 결과와 원문은 [run.txt](p7v2/run.txt)에 있다.

### 2-5. 한계

- 모든 입력은 로컬 사본·가정값이다. SID203 행은 로컬 `rt_ledger` 값(DAILY_SMA20·KRW-BTC·H1·20200)이며 운영 값이라고 주장하지 않는다. 대조기의 정상 판정은 운영 실측이 아니다.
- 미체결 매수 취소의 세션 장부 0(예약 전액 복원)은 0e1d2a02 `restorableKrw`·`restoreUnfilledBuyAtomically`를 읽어 세운 모형이다. 지정가 주문·매도 부분 체결 뒤 취소의 세부는 실제 PG 경로 시험으로 고정하지 않았다.
- 거래소 응답 형식(잔고 자릿수·완료 주문 목록 범위·입출금 이력 API)은 가정이다. 운영 조회 승인 전이다.
- 추출 SQL은 주문 전체를 읽는다. 운영 행 수에서의 비용은 측정하지 않았다.

## 3. 쿨다운 간섭 — 0e1d2a02 대비 81b0e7a7 (§3-4)

**0e1d2a02(운영) 경로:** `blackSwanBlockedAt/Price`는 코인 키 지도 하나를 REAL·PAPER가 공유한다(S:369~383). 기록(S:1163)·조회(S:926 회차 승인, S:1145 스캔)·만료 삭제(S:1153)가 세션 종류를 보지 않는다. 재기동 복원(S:405)은 `SESSION_KIND`(DYNAMIC) 로그만 읽는다.

| 경우(동시 DYN_PAPER + DYNAMIC REAL, 같은 코인) | 0e1d2a02 | 89336440(81b0e7a7 이식) |
|---|---|---|
| PAPER가 급락 봉 차단 → 회복 뒤 REAL BUY | **REAL이 'BLACK_SWAN 쿨다운'으로 막힘** | 막히지 않음(PAPER 지도에만 기록) |
| REAL 자체 급락 차단 → REAL 회복 틱 | 막힘 | 막힘(그대로) |
| REAL 차단 → PAPER | 막힘(PAPER 성과가 REAL 활동에 좌우) | 막히지 않음 |
| 재기동 뒤 PAPER 기원 쿨다운 | 사라짐 → 재기동 전후 REAL 판단이 다름 | PAPER 지도에 복원, REAL에 안 들어감 |
| 재기동 뒤 REAL 기원 쿨다운 | 복원 | 복원(같은 질의) |

**포함 필요성(판정 요청):**

- 12월에 DYNAMIC REAL과 DYN_PAPER를 **같은 코인 범위에서 동시에** 돌리면 REAL 진입 여부가 PAPER 활동과 재기동 시점에 따라 달라진다. 엔진 동등 원칙과 재현성에 어긋난다. 이 경우 REAL 시작 전 통합 범위에 넣는 것을 제안한다.
- **보호 쪽 변화도 있다.** 격리하면 PAPER만 본 급락 때문에 REAL이 막히던 경우가 사라진다. 이는 v1.145 결정 3의 방향과 같지만 실자금 보호가 줄어드는 방향이다. 그러므로 총괄 판정 대상이다.
- 동시 DYN_PAPER를 돌리지 않으면 영향은 재기동 복원 비대칭뿐이다. 현재 운영의 DYNAMIC PAPER 실행 여부는 이번에 읽지 않았다.

**이식·검증:** 0e1d2a02 위에 cherry-pick했다. 제품 파일은 자동 병합됐고 patch-id 414bf997…가 81b0e7a7 자체 변경과 같다. 시험 파일만 양쪽 추가 충돌이었다. 81b0e7a7의 격리 기대에 0e1d2a02 쪽 손실 관문 H2 대역(v1.158 결정 2) 블록만 더해 해소했다. 차분은 [candidate-89336440.diff](cooldown/candidate-89336440.diff)(sha256 d023648d…)다. 범위 시험 [run-89336440](cooldown/run-89336440/)의 명령·시각·종료값0·Gradle 콘솔(CP949 원문)·XML 5개 결과는 아래와 같다.

| 클래스 | 89336440 | 0e1d2a02 고정 회귀 원문 |
|---|---|---|
| DynamicBlackSwanCooldownCrossKindTest | 6 성공(격리 계약) | 4 성공(**간섭 재현** 계약), XML 130364d6ec91 |
| DynamicBlackSwanCooldownRestoreTest | 7 성공 | 7 성공, f8ae449d1331 |
| DynamicBlackSwanPriceGuardTest | 7 성공 | 7 성공, d6bca389f461 |
| DynamicPaperLiveRuleParityTest | 6 성공 | 6 성공, 4ab89e4d8b4e |
| EngineParityTest | 27 성공 | 27 성공, 320167eaf943 |

수정 전 실패 재현은 기존 3f6afb90·db2e026d(시험 가지)와 0e1d2a02의 재현 시험으로 갈음했다. 새로 돌리지 않았다. 전체 회귀·PG·jar는 하지 않았다. 원격 푸시·main 합침도 하지 않았다.

## 4. 매매 알림 경로 (§3-4, 0e1d2a02 원문)

| 단계 | 위치 | 동작 |
|---|---|---|
| 매수 체결 | `OrderExecutionEngine` S:2287 | `bufferTradeEvent("세션#"+sessionId, …, "BUY")` — **LIVE·DYNAMIC 모두 같은 라벨**(종류 구분 없음) |
| DYNAMIC 매도 정산 | `DynamicTradingService` S:3187 | 입금 반영(`credited`) 때만 `"동적#"+sessionId` SELL 적재 |
| LIVE 매도 정산 | `LiveTradingService` S:3363 | `"세션#"+sessionId` SELL 적재 |
| 전송 | `TelegramNotificationService` S:490·496 | **즉시 전송 없음.** 메모리 버퍼(`CopyOnWriteArrayList`)를 12:00·00:00 KST `TRADE_SUMMARY`로 라벨별 묶음 전송 → `telegram_notification_log` 기록. `telegram.enabled=false`면 버퍼를 비우지 않고 반환 |
| 즉시 전송되는 것 | 손절·시간 청산·세션 시작/정지·정리 실패·잔고 정합성 이상 등 | 체결 자체 알림은 아님 |

**12월 병행에서 남는 빈틈(제품 변경 안 함, 판정 요청):** ① 체결 즉시 알림이 없다. ② 재기동하면 버퍼가 사라져 그 반나절 요약에서 빠진다. ③ DYNAMIC 매수는 "세션#ID", 매도는 "동적#ID"로 갈려 한 세션의 왕복이 다른 묶음에 나온다. 같은 ID의 LIVE·DYNAMIC 매수는 한 묶음에 섞인다. ④ **휴대폰 실제 수신은 여전히 미확인**이다. 전송 이력 `success`는 텔레그램 API 응답까지만 뜻한다.

## 5. 12월 판단 기록 — 기존 자료 최소 연결표 (§3-4)

| 필요한 칸 | 기존 자료(테이블.열) | 상태 |
|---|---|---|
| 판단 흐름(신호·차단 사유) | `strategy_log`: signal·reason·blocked_reason·was_executed·session_type·session_id·ruleset_hash·market_regime·signal_price·return_4h/24h | 있음. 실자금/모의 구분은 `session_type` |
| 체결·비용 | `order`: applied_executed_funds·applied_paid_fee·filled_quantity / `dynamic_sell_settlement`: net_proceeds·realized_pnl | 있음. 주문 단위 비용 차감 손익 계산 가능 |
| 실행 차이 | `execution_drift_log`: signal_price·fill_price·slippage_pct·session_kind | 부분. 매수 기록 경로는 `!DYNAMIC` 조건(OrderExecutionEngine S:2296)이라 **DYNAMIC 매수 차이는 없음** |
| 시장 상황 | `position.market_regime`, `strategy_log.market_regime`, `regime_change_log` | 있음(분류 기준은 제품 정의 그대로) |
| 낙폭 | 세션 `mdd_peak_capital`(현재값만), `position.peak_value_krw`(포지션 단위), `pool_equity_snapshot`(풀 단위) | **세션 일별 자산 곡선 없음.** 월 낙폭은 주문·정산 시계열 재구성이나 풀 스냅샷으로만 가능 |
| 표본·판정 | `kill_criteria_judgment`: verdict·trade_count·return_pct·ruleset_hash | 있음 |
| 규칙 구분 | `ruleset_snapshot`, 각 행 `ruleset_hash` | 있음 |
| 계좌 대조 결과 | — | **없음.** P7 v2 출력 JSON을 회차별 근거 파일로 남기는 안(새 DB 열 만들지 않음) |

## 6. 매회 상한·BTC 제외 설계 고정안 (§3-5, 구현·설정 변경 없음)

| 항목 | 고정안 |
|---|---|
| 설정 | 새 세션 전용 `maxOrderKrw`(원, 명시 필수)·`excludeCoins`(명시 필수, 예 `["KRW-BTC"]`). 생성 때 `5000 ≤ maxOrderKrw ≤ 포지션당 상한`을 검사한다. 범위 밖이면 생성 거절 |
| 적용식 | 요청액 = `min(buyRequest(가용×비율×배수), maxOrderKrw)`. 위치는 `executeBuy`의 `buyRequest` 직후(0e1d2a02 S:1663)·최소 주문 보정 앞이다 |
| 최소 주문 보정 | 기존 5,000원 올림(S:1668~1671)은 `maxOrderKrw ≥ 5000`이므로 상한 안에 머문다. 보정 뒤에도 `≤ maxOrderKrw`인지 다시 확인한다 |
| REAL/PAPER | 같은 식을 두 모드에 적용한다(엔진 동등). 모의 비교군도 같은 값을 명시한다 |
| 미지정 기존 세션 | 값이 없으면 종전 계산 그대로다. 기존 행·기본값·DDL 기본값을 넣지 않는다 |
| 규칙 지문 | `RulesetFingerprint` 계약상 거동 변수는 지문에 **반드시** 넣는다. 다만 빌더가 `null`을 `"null"`로 넣으므로 **값이 있을 때만 키를 추가**해야 기존 세션 지문이 바뀌지 않는다. 별도 열/`strategy_params` 중 저장 위치만 판정 대상 |
| 저장·재기동 | 세션 행에 저장하고 재기동 때 그대로 읽는다. 메모리 전용 값으로 두지 않는다 |
| 보호선 | 예약 서비스의 포지션당/총 상한 검사(최종 보호선)·자본·풀 한도는 그대로 둔다. 상한은 그 앞의 감액일 뿐이다 |
| BTC 제외 적용점 | 감시 후보 산출·재필터·코인 선택·회차 승인·`executeBuy` 다섯 곳(435차 §4-3) |
| BTC 유지 | BTC 급락 관문·시장 국면 입력, 기존 보유 매도·보호 매도·정지 청산은 제외하지 않는다 |
| 기록 | 상한 적용 진입은 원래 요청액·적용 상한을 신호 기록에 남겨 성적표에서 구분한다(차단 사유와 별도) |
| 판단 경계 | 상한 초과 **거절**은 현행 보호 계약이다. 이것만으로 12월 운용 불가라고 단정하지 않는다. 숫자는 금액 결정 뒤 정한다 |

## 7. 후보 보존 (§3-6)

18f3a795(`candidate/dynamic-mode-strict-0e1d2a02`)와 2302104c(`test/p1-cap-boundary-0e1d2a02`)는 이 결과 커밋과 함께 원격 후보 가지로 올리고 원격 SHA를 대조했다(결과는 [PROGRESS](../../PROGRESS.md)). 89336440은 이번 지시에 원격 보존 허용이 없어 **로컬 가지로만** 둔다. main 합침·운영 핀 변경·전체 회귀는 하지 않았다.

## 8. 남은 것

- P7 v2 운영 적용(설치판·RUNBOOK 새 절차 번호·운영 조회)은 별도 판정·승인 전이다. 그 전에는 DYNAMIC REAL을 시작하지 않는다.
- 금액(총원금·월 손실 상한·maxOrderKrw 숫자)·DYNAMIC 전략/주기/시작은 미확정이다.
- 11-18 12:00 PAPER9 생성 기한은 개발 세션의 실행 범위가 아니다. 미승인 시 기존 날짜 파일은 실행하지 않는다.
