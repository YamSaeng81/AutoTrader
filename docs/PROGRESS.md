# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 이 파일은 **최신 작업 이력(최근 세션 몇 개) + 보류/결정 대기 항목 + 프로젝트 참조 정보**만 담는다. 오래된 상세 이력은 [`docs/old_progress.md`](old_progress.md)(2026-08-06 이전 전체 백업)와 [`docs/CHANGELOG.md`](CHANGELOG.md)를 참조.
> **2026-08-06**: 파일이 2,000줄+ 로 비대해져 `old_progress.md`로 전체 백업 후 이 파일을 정리했다. 상세 근거·재현 과정이 필요하면 `old_progress.md`에서 날짜로 검색할 것.

---

## 🔴 보류·결정 대기 항목

> **최우선 (2026-08-06 벤치마크 측정 결과 — 아래 별도 섹션 참조)**
>
> 1. **실자본 매매 중단 → 페이퍼 전환 검토** — 시스템이 시장 대비 알파 음수(동적 −3.30% vs 알트 −1.63%), 누적 실현손실 −21,854원, 승률 8.9~11.1%. 지금 속도(6일 8거래)로는 통계적 유의성에 도달하기 전에 계속 잃는다. 페이퍼로 전환하면 **잃지 않으면서 같은 데이터를 얻는다**. ✅ **2026-08-06 페이퍼를 LIVE와 동일 로직으로 정렬 완료**(위 별도 섹션) — 이제 페이퍼 결과가 실전 예측에 유효하다. `PaperTradingService`는 07-01 이후 미가동 상태이므로 재가동만 하면 된다.
> 2. **자본 증액 금지** — 기대값이 음수인 상태에서 금액을 늘리면 손실 속도만 빨라진다. 증액 조건은 "벤치마크 대비 알파가 양수임이 통계적으로 증명될 때" 하나뿐.
> 3. ~~**벤치마크 비교를 시스템에 내장**~~ ✅ **2026-08-07 완료** — `BenchmarkAlphaService` + 대시보드 상단 고정 노출(위 별도 섹션).
> 4. ~~**신호 반전 가설 검증**~~ ✅ **2026-08-07 기각** — 반전이 오히려 더 나쁘다(평균 −4.85% → −6.01%). 신호는 거꾸로인 게 아니라 **방향성이 없다**. 손실의 정체는 무작위 진입 + 마찰비용(위 별도 섹션).
> 5. ~~**중단 기준(kill criteria)을 미리 문서화**~~ ✅ **2026-08-18 완료** — [`docs/KILL_CRITERIA.md`](KILL_CRITERIA.md) 제정 + `StrategyKillCriteriaService`로 집행(매일 09:00 KST). 자본 보호 3종은 표본 무관 즉시 발동, 엣지 2종은 n≥20. 아래 08-18 섹션 참조. ⚠️ **자동 정지는 아직 꺼져 있다**(`kill-criteria.auto-stop=false`) — 판정이 며칠간 옳게 나오는지 관찰 후 켤 것.
> 6. **LIVE 세션을 H1로 재생성 필요** — 실전(M5)과 페이퍼(H1)의 타임프레임이 달라 A/B 비교가 성립하지 않는다. 세션 생성 후 타임프레임 변경 API가 없으므로 **정지 후 H1로 재생성**해야 한다(현재 포지션 0건이라 안전). 동적 멀티코인은 REAL/PAPER 쌍이 이미 H1로 맞춰져 있어 문제없다.

> 아래는 코드가 준비돼 있고 실행 여부·시점만 운영 판단이 필요한 것들.

- **Walk Forward 게이트 활성화** (`REQUIRE_WALK_FORWARD_GATE`) — `WalkForwardValidationGate` 구현 완료, 기본값 비활성. 대부분 전략이 Walk Forward 이력이 없어 켜는 즉시 신규 세션 생성이 전면 중단된다. 켜기 전에 `GET /api/v1/strategies/walk-forward-gate-status`로 어떤 전략이 막히는지 먼저 확인하고, 운영 중인 전략들(COMPOSITE_MOMENTUM_ICHIMOKU_V2 등)에 Walk Forward부터 돌려 통과시킬지 판단할 것.
- ~~**LIVE time stop 활성화** (`maxHoldHours`)~~ ✅ **2026-08-18 완료** — 기본값 0 → 24 (V68, LIVE·DYNAMIC·PAPER 3종 동시). 운영 RUNNING 10세션에도 소급 적용. 아래 08-18 섹션 참조. ⚠️ 켜자마자 LIVE 경로에서 중복 SELL 1건이 재현됐다(같은 섹션 "새로 드러난 것" 참조) — 미해결.
- **신호 기대값 자체가 음수인 문제** — 최근 7일 동적 세션 BUY 신호 사후수익률 4h −2.17%/24h −4.47%(n=50). Walk Forward 게이트는 "검증 안 된 전략을 막는" 것이지 "전략 자체를 고치는" 게 아니라서, 게이트를 켜도 이 문제는 해결되지 않는다. 전략/신호 모델 자체를 봐야 하는 별도 과제.
- ~~**시간 초과 청산(time stop) 텔레그램 알림 부재**~~ ✅ **2026-08-18 완료** — `notifyTimeStop` 신설, LIVE·DYNAMIC 배선. 08-18 오전 XRP 4건 청산에 알림이 안 간 것이 실측 사례였다.
- **LIVE 유령 포지션 자동 정산** — 헬스체크(`OperationalHealthCheckService`)는 감지·알림까지만 하고 자동 정산은 안 한다. DYNAMIC의 `reconcileDynamicGhostPositions`에 대응하는 LIVE용 자동 정산을 추가할지는 실거래 자금에 직접 손대는 범위라 별도 검토 필요.
- **e2e 스위트(`@playwright/test`) 미설치** — `navigation.spec.ts`·`global-setup.ts`·`auth-fixtures.ts`는 작성돼 있으나 의존성이 없어 실행 불가. `npm i -D @playwright/test && npx playwright install chromium` 필요.
- **StrategyDegradationWatchdog을 DYNAMIC까지 확장할지** — 현재 LIVE(`sessionType=REAL`)만 감시. 저하 발견 시 Discord 알림에 그치지 않고 Walk Forward 게이트와 연동해 자동 재차단할지도 별건.
- **Walk Forward 미리보기 API를 `/strategies` 페이지에 노출** — 현재 API만 있고 프런트 표시 없음.

---

## 🔴 2026-08-18 운영 DB 세션 분석 (08-07 재기동 후 11일차)

> 운영 DB(`yhpapa.iptime.org:8432`) 직접 조회. 기준시각 2026-08-18 08:30 KST.
> 대상: LIVE 2세션(198·199) + 동적 8세션(46~53, REAL/PAPER 4쌍). 전부 H1, 세션당 초기자본 10,000원.

### 성적 요약 (11일)

| 세션 | 전략 | 모드 | 총자산 | 손익 | 거래 | 승 |
|---|---|---|---|---|---|---|
| 46 / 47 | MTF_CONFIRMED | REAL / PAPER | 10,000 / 10,000 | 0.00% / 0.00% | 0 / 0 | — |
| 48 / 49 | MEANREV_BB | REAL / PAPER | 9,850 / 9,844 | −1.50% / −1.56% | 0(보유1) | — |
| 50 / 51 | CMI_V2 | REAL / PAPER | 10,000 / 9,906 | 0.00% / −0.94% | 0 / 0(보유1) | — |
| 52 / 53 | PULLBACK_MTF | REAL / PAPER | 9,623 / 9,709 | −3.77% / −2.91% | 4 / 3 | **0 / 0** |
| 198 | MEANREV_BB (XRP) | LIVE | 9,850 | −1.50% | 0(보유1) | — |
| 199 | MTF_BTC_STRICT (BTC) | LIVE | 10,000 | 0.00% | 0 | — |

동적 REAL 합계 39,467.73원(−1.33%), 동적 PAPER 합계 39,452.60원(−1.37%).
**11일간 청산된 8거래 전부 손실**(−1.07 ~ −1.47%). 큰 손실은 없고 −1% 내외 손실만 반복 — 마찰비용형 출혈.

### 발견 (심각도순)

1. **XRP 3중 포지션 259시간(10.8일) 고착** — 2026-08-07 13:00 같은 분에 LIVE 198(pos 2396)·DYNAMIC 48(pos 2395)·DYN_PAPER 49(pos 2394)가 각각 8,000원씩 KRW-XRP 매수. 실자금만 16,000원. SL 1,368.95 / TP 1,556.28 어느 쪽도 미도달, 현재 −1.87~−2.02%. 세 세션 모두 `max_hold_hours=0`(비활성)이라 **V62가 막으려던 저변동 고착이 그대로 재현**. 헬스체크는 08-09부터 매일 `stuck_position_count=2`로 감지만 하고 조치 없음. 세션당 가용 KRW가 10,000 → 2,000으로 11일째 묶여 있다.
2. **동일코인 노출 상한이 LIVE↔DYNAMIC 사이에 적용되지 않음** — 상한 1건이라는 차단 로그가 존재하는데도 LIVE 198과 DYNAMIC 48이 같은 코인을 같은 시각에 잡았다. 실자금 기준 XRP 편중 16,000원 = 두 실거래 세션 합산 자본의 80%.
3. **BLACK_SWAN_GUARD 오차단** — 세션 46의 11일간 유일한 BUY(KRW-PIEVERSE, 08-08 20:00, confidence 0.44)가 "1시간 내 −11.17%" 가드로 차단. 사후수익률 **4h +4.81% / 24h +2.13%** — 11일 중 유일하게 돈이 됐을 신호였다. 이후 진입가 가드가 720분 추가 차단. 저유동 신규코인에 −8%/1h 임계가 과도한지 재검토 필요.
4. **"본전 근처" SELL 차단이 손실을 확정시킨다** — 52/53에 `본전 근처 pnl=−0.09~−0.93%` 차단이 40건+ 누적. 그 상태로 버티다 최종 −1.07~−1.47%에 청산. 청산된 8거래 전부 이 밴드에 있었다. 수수료 왕복 + 슬리피지를 감안하면 이 게이트가 순손실 요인일 가능성이 높다 — A/B 검증 대상.
5. **자본 절반이 11일간 유휴** — 46·47·50·199는 거래 0건(199는 263회 평가에 BUY/SELL 신호 0). 배치된 59,850원 중 40,000원이 아무 일도 하지 않았다.
6. **REAL/PAPER 정렬은 대체로 성공, 단 50↔51 이탈** — 52↔53(−3.77% vs −2.91%), 48↔49(−1.50% vs −1.56%)는 근접. 반면 50(REAL)은 BUY 0건인데 51(PAPER)은 08-16 02:00 LINK 진입. 신호 자체가 갈렸다 — 워치리스트/평가시점 차이 의심, 로그 확인 필요.
7. **신호 기대값 여전히 음수** — 52/53 신호 161·152건, 평균 confidence 0.658인데 사후수익률 4h −0.18% / 24h −0.40%. confidence가 높다고 수익이 나지 않는다(가장 좋았던 신호는 confidence 0.44).
8. **`order_sequence_gap` 증가** — 08-16 0 → 08-17 12 → 08-18 16. 잔고 불일치·유령 포지션은 0이라 즉시 위험은 아니지만 원인 확인 필요.

### 정상 확인된 것

`balance_mismatch_count=0`, `ghost_position_count=0` 11일 연속. 서킷브레이커 46~53 전부 미발동. LIVE 세션은 H1로 재생성 완료(보류 항목 6번 해소).

---

## 🟢 2026-08-18 조치 — time stop 기본 활성(V68) + 고착 4건 청산 + 본전 게이트 판정

### 1. `max_hold_hours` 기본값 0 → 24 (V63 되돌리기 완료)

V63이 문서로 남겨둔 되돌리기 절차를 그대로 수행했다. 껐던 사유(매도 후처리 롤백 P0)는 08-03에
해소됐고, `ghost_position_count`가 11일 연속 0으로 재발 징후도 없었다.

| 파일 | 변경 |
|---|---|
| [`DynamicSessionEntity`](../web-api/src/main/java/com/cryptoautotrader/api/entity/DynamicSessionEntity.java) | `DEFAULT_MAX_HOLD_HOURS` 0 → **24**, 스테일 주석(07-31) 현행화 |
| [`LiveTradingSessionEntity`](../web-api/src/main/java/com/cryptoautotrader/api/entity/LiveTradingSessionEntity.java) | `DEFAULT_MAX_HOLD_HOURS` 신설(동적 값 참조) + `prePersist` 폴백 |
| [`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) | `createSession` 하드코딩 0 → 상수 |
| [`PaperTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java) / [`VirtualBalanceEntity`](../web-api/src/main/java/com/cryptoautotrader/api/entity/paper/VirtualBalanceEntity.java) | NULL 폴백 제거 — 페이퍼만 time stop 없이 도는 비대칭 차단 |
| [`V68`](../web-api/src/main/resources/db/migration/V68__default_max_hold_hours_on.sql) | 3개 테이블 `ALTER COLUMN ... SET DEFAULT 24` + COMMENT. **기존 행은 건드리지 않음**(V63 정책 준수) |
| `schema-h2.sql` | 동일 기본값 동기화 |
| 테스트 3종 | "기본값 0" 을 못박던 assertion 갱신 + `maxHoldHours=0` 명시 옵트아웃 경로 테스트 신설. `:web-api:test` 255건 그린 |

**24시간 근거**: 08-07~08-18 실측 청산 8건의 보유시간 중앙값 16시간(초과 2건은 66h/70h, 둘 다 손실 마감).
V62가 처음 의도했던 값과도 같다.

### 2. 운영 RUNNING 10세션 소급 적용 → 고착 4건 자동 청산

마이그레이션은 기존 행을 건드리지 않으므로 운영자 UPDATE로 별도 적용(46~53, 198, 199 = `max_hold_hours` 0 → 24).
`tick()`이 매 60초 세션을 DB에서 다시 읽으므로 재배포 없이 다음 틱에 발동했고, **정규 매도 경로**로
청산됐다(DB에서 포지션을 직접 CLOSED로 바꾸는 방식은 거래소와 어긋나므로 쓰지 않았다).

| 포지션 | 세션 | 코인 | 보유 | 실현손익 | 청산시각(KST) |
|---|---|---|---|---|---|
| 2396 | LIVE 198 | XRP | 259.9h | −154원 (−1.92%) | 08:56:54 |
| 2395 | DYNAMIC 48 | XRP | 259.9h | −154원 (−1.92%) | 08:55:29 |
| 2394 | DYN_PAPER 49 | XRP | 259.9h | −168원 (−2.10%) | 08:55:20 |
| 2425 | DYN_PAPER 51 | LINK | 54.9h | −106원 (−1.33%) | 08:55:20 |

11일간 잠겨 있던 자본이 풀렸다 — 세션 48·198의 가용 KRW가 각각 2,000 → 9,846원. 전체 OPEN 포지션 0건.

### 3. 🔴 새로 드러난 것 — LIVE 중복 SELL (미해결)

LIVE 198이 **같은 포지션에 SELL을 두 번 제출**했다. 두 번째(주문 8725)는 거래소가
`insufficient_funds_ask`로 거절했다.

```
08:55:54  주문 8724 생성·제출  → 08:55:59 FILLED
08:56:54  주문 8725 생성(정확히 다음 틱) → HTTP 400 거절
08:56:54  position 2396 closing_at 기록 → CLOSED
```

[`executeSessionSell`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L1316)은
주문 제출 **전에** 포지션을 CLOSING으로 표시하는데, `closing_at`이 첫 시도(08:55:54)가 아니라
두 번째 시도 시각으로 찍혔다 — **첫 틱의 CLOSING 쓰기가 커밋되지 않았다**는 뜻이다. 주문은 이미
비동기로 나간 뒤라 포지션만 OPEN으로 남았고, 60초 뒤 같은 time stop이 다시 발동했다.
07-31 세션 38 RLUSD 사고(V63이 기본값을 0으로 내린 바로 그 사유)와 동일한 시그니처다.

**다만 그때만큼 나쁘지는 않다** — 07-31은 86분간 4회 실패 루프였고, 오늘은 1회 중복 후
`reconcileClosingPositions`가 정상 종료시켰다. 08-03 수정이 "폭주 루프 → 1틱 중복"까지는 줄인 셈이다.
DYNAMIC 48·PAPER 2종은 재현되지 않았다(LIVE 경로 한정).

**결과 자체는 정합** — 포지션 CLOSED, 실현손익 정확, KRW 복원 정상, `ghost_position_count=0`, OPEN 0건.

**⚠️ 남은 위험**: LIVE 198과 DYNAMIC 48이 같은 업비트 계정에서 동시에 XRP를 들고 있었다. 오늘은
둘 다 이미 팔린 뒤라 거절됐지만, 타이밍이 어긋났다면 **LIVE의 중복 매도가 DYNAMIC 포지션의 코인을
팔았을 수 있다.** LIVE 매도의 틱 간 멱등성 확보가 필요하다(다음 우선 과제).

### 4. 본전 청산 게이트 — 운영 데이터로 판정: **손해**

[`SignalExitGateAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/SignalExitGateAbBacktestRunner.java)를
BTC/ETH/SOL/XRP H1로 돌렸으나 **게이트 ON/OFF가 8개 조합 전부 완전히 동일**했다 — 이 하네스에서는
전략 SELL 경로가 아예 안 타고 전부 SL/TP로 청산된다. 즉 07-02 L-2가 "영향 없음"으로 판정했던 근거는
게이트가 무해해서가 아니라 **측정되지 않았기 때문**이다. 이 러너는 게이트 검증에 쓸 수 없다.

대신 운영 데이터로 반사실 비교를 했다 — 청산된 포지션마다 "게이트가 막은 첫 SELL 신호 시점의 pnl"과
"실제 청산 pnl"을 대조:

| 포지션 | 코인 | 게이트가 막은 첫 SELL | 실제 청산 | 게이트 비용 |
|---|---|---|---|---|
| 2404 | SOL | −0.428% | −1.225% | 0.797%p |
| 2405 | SOL | −0.371% | −1.070% | 0.699%p |
| 2412 | SOL | −0.430% | −1.230% | 0.800%p |
| 2413 | SOL | −0.280% | −1.170% | 0.890%p |
| **평균** | | **−0.377%** | **−1.174%** | **0.797%p** |

**4건 전부 게이트가 손해였다.** 기전도 구조적으로 설명된다 — `allowsSignalExit`는
`minPnlPctForSignalExit=+0.30%` 미만이면 SELL을 막고, `lossEscapeThresholdPct=−1.00%` 아래로
떨어져야 다시 풀어준다. 즉 **−1.00% ~ +0.30% 구간에 갇힌 포지션은 −1%를 뚫어야만 나갈 수 있다** —
게이트가 작은 손실을 1% 이상 손실로 확정시키는 구조다. 실제로 11일간 청산된 8건 전부가 이 밴드였다.

n=4로 표본은 작지만 방향이 4/4이고 기전이 결정론적이라, 재현이 아니라 설계 문제로 본다.

→ **2026-08-18 조치 완료**. 아래 섹션 참조.

---

## 🟢 2026-08-18 후속 조치 — LIVE 매도 멱등성 + 본전 게이트 대칭화

> ⚠️ **둘 다 코드 변경이라 배포해야 반영된다.** 오늘 오전의 time stop 소급 적용(DB UPDATE)과 달리
> 운영에 자동으로 적용되지 않는다.

### 1. LIVE 중복 SELL — DYNAMIC 방어 2종 이식

원인을 DYNAMIC과 대조해 특정했다. DYNAMIC이 07-31/08-03 P0에서 받은 방어가 **LIVE에는 이식되지 않은
상태**였다.

| 방어 | DYNAMIC | LIVE (수정 전) |
|---|---|---|
| 원자적 CLOSING 전환 | `markClosingIfOpen` (07-02 감사 #2) | `setStatus + save` — 중복 감지 불가 |
| CLOSING reconcile 주문 선택 | **FILLED 우선** (08-03) | `sellOrders.get(0)` 최신순 — FAILED에 가려 OPEN 롤백 |
| 롤백된 OPEN 포지션 정산 | `reconcileDynamicGhostPositions` | 없음(거래소 대조형 `reconcilePhantomPositions`만) |

- [`executeSessionSell`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) / `closeSessionPositions` → `markClosingIfOpen` 사용, 0이면 주문 제출 스킵.
- [`reconcileClosingPositions`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java) → FILLED 우선 선택 이식. 체결은 되돌릴 수 없는 사실이므로 FILLED가 있으면 그것이 진실이다.
- 신규 [`LiveSellIdempotencyTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/LiveSellIdempotencyTest.java) 3종 — 원자적 가드 / FILLED 우선 / 체결 없을 때는 기존대로 OPEN 롤백.

**남은 갭(미조치)**: 부모 tx가 롤백돼 포지션이 OPEN으로 되돌아간 경우는 위 두 수정으로도 안 잡힌다
(CLOSING만 순회하므로). LIVE는 `reconcilePhantomPositions`(거래소 실잔고 대조, 3회 연속 ≈3분, API 키 필요)가
느리게 잡아준다. DYNAMIC처럼 주문 기반 즉시 정산(`reconcileLiveGhostPositions`)을 추가할지는 실거래 자금에
직접 손대는 범위라 여전히 별도 판단 — 기존 보류 항목 그대로다.

**정확한 트리거 분기는 미확정** — 운영 서버 로그가 있어야 `sellOrders.isEmpty()` 롤백(@Async 주문 INSERT
경합)인지 부모 tx 롤백인지 갈린다. 로컬 `logs/`는 3월치라 쓸 수 없었다. 다만 위 수정은 두 경로 모두에
유효하다.

### 2. 본전 게이트 대칭화 — `lossEscapeThresholdPct` −1.00 → −0.30

데드밴드가 **−1.00% ~ +0.30%** 로 비대칭이라, 전략이 SELL을 내도 손실이 1%를 넘기 전에는 못 나갔다.
−0.30 은 `minPnlPctForSignalExit`(+0.30%)와 대칭이고 왕복 수수료(0.1%)+슬리피지를 덮는다 — churn 방지라는
원래 목적은 유지하면서 손실 구간에 갇히는 비대칭만 제거한다.

**부수 발견 — 이 상수가 네 군데에 복제돼 있었다.** `LiveTradingService`·`PaperTradingService`·
`DynamicTradingService`·`ExitRuleConfig` 각각 `new BigDecimal("-1.00")`. `PaperLiveAlignmentTest`가
리플렉션으로 LIVE↔PAPER만 비교하고 있어 **DYNAMIC과 백테스트는 감시망 밖**이었다.
→ 세 서비스가 [`ExitRuleConfig`](../core-engine/src/main/java/com/cryptoautotrader/core/risk/ExitRuleConfig.java)를
단일 출처로 참조하도록 통합하고, 정합성 테스트를 DYNAMIC·백테스트까지 확장했다.

신규 테스트: 데드밴드 대칭성, 운영에서 막혔던 실제 pnl 값(−0.371/−0.428/−1.174)이 이제 빠져나오는지,
본전 근처(±0.10%) churn 차단은 유지되는지, 최소 보유시간 미달은 여전히 무시되는지.

### 검증

`:web-api:test` **262건 그린**(실패 0, 스킵 3), `:core-engine:test` 그린.

기존 [`SignalExitGateAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/SignalExitGateAbBacktestRunner.java)는
**게이트 검증에 쓸 수 없다** — ON/OFF 결과가 8개 조합 전부 동일하다(전략 SELL 경로를 안 타고 전부 SL/TP 청산).
07-02 L-2의 "영향 없음" 판정이 이 러너 근거였다면 재검토 대상이다.

### 배포 확인 (2026-08-18 10:15)

두 작업 모두 반영됐다. 작업 1은 V68이 09:19:55 KST에 적용됐고(`column_default=24` × 3테이블,
RUNNING 10세션 소급 완료), 작업 2는 마이그레이션이 없어 Flyway로 확인되지 않지만 **10:09 재기동**으로 들어갔다.

재기동 판정 근거 — 시간당 `strategy_log` 건수가 평소 ~49건인데 두 시간대만 두 배다:

| 시간대 | 로그 | 사유 |
|---|---|---|
| 03~08시 | 48~54 | 정상 (H1 캔들당 1스윕) |
| **09시** | **97** | 09:19:55 재기동 + 정기 스윕 |
| **10시** | **98** | 10:00 캔들 스윕 + **10:09 추가 스윕** |

10:09 스윕은 10:00에 이미 평가한 47개 세션 전부를 **같은 H1 캔들로** 재평가했다(세션 50 KRW-PROM이
`signal_price=2710`으로 두 번 동일 기록). 같은 닫힌 캔들을 다시 평가하는 건 인메모리 `lastEvaluatedCandle`
맵이 비었을 때뿐 — JVM 재기동 신호이고, 09:19:55 재기동과 같은 패턴이다. 커밋 89b5a4c(10:07:32) 이후다.

⚠️ **두 변경 모두 아직 실제 경로를 타지 않았다** — 전 세션 SCANNING, OPEN 포지션 0건이라
멱등성 가드와 −0.30 게이트는 다음 진입이 있어야 검증된다. 확인 지점: ① SELL 1건당 주문 1건
② 본전 차단이 −0.30% 위에서만 발생.

---

## 🟢 2026-08-18 전략 폐기 기준(kill criteria) 제정 — [`docs/KILL_CRITERIA.md`](KILL_CRITERIA.md)

> 보류 항목 최우선 5번("중단 기준을 미리 문서화") 해소. **문서가 본체이고 코드는 집행부다.**

### 왜 지금인가

08-18 오전까지의 작업(time stop, 멱등성, 청산 게이트)은 전부 **"잘 지는 시스템을 더 안전하게"** 만드는
것이었다. 정작 실전 검증 통과 전략은 22개 중 0개, 11일 승률 0/7, 알파 음수인데 **폐기 조건이 없어서**
나쁜 전략을 무한히 고쳐 쓰는 루프에 있었다. 기준이 없으면 손실은 항상 "표본이 부족해서"로 설명되고,
표본은 영원히 부족하다.

### 핵심 구분 — 두 종류를 섞지 않는다

| | A. 자본 보호 | B. 엣지 검증 |
|---|---|---|
| 묻는 것 | "더 잃어도 되는가?" | "우위가 있는가?" |
| 통계적 유의성 | **불필요** | 필요 |
| 오판 비용 | 좋은 전략 조기 종료(회복 가능) | 나쁜 전략에 계속 자본 투입(회복 불가) |

"n이 적으니 판단 불가"는 B의 올바른 태도인데 이걸 A에 적용하면 **표본을 모으는 동안 자본이 소진된다** —
지금이 정확히 그 상태였다.

### 정직한 인정

현재 속도는 10세션 11일에 실현 거래 7건(세션당 연 23건). EV 부호를 신뢰할 표본은 실전에서 수년이 걸린다.
→ **실자본 운영으로는 엣지를 통계적으로 검증할 수 없다.** 엣지 검증은 Walk Forward가 맡고, 실전 kill
criteria는 자본 보호가 주력이며 B 기준은 "백테스트를 명백히 배신했을 때 잡는 안전망"으로만 쓴다.

### 발동 기준 (판정 단위 = 세션, PAPER 포함)

| 코드 | 조건 | 표본 |
|---|---|---|
| `CAPITAL_LOSS` | 초기자본 대비 ≤ −15% | 무관 |
| `MAX_DRAWDOWN` | **고점** 대비 ≤ −20% | 무관 |
| `CB_REPEAT` | 서킷브레이커 누적 ≥ 3회 | 무관 |
| `NEGATIVE_EV` | 누적 실현손익 ≤ 0 | n ≥ 20 |
| `NEGATIVE_ALPHA` | 세션 수익률 − 동일기간 알트 보유 < 0 | n ≥ 20 |
| `NO_SIGNAL` | 30일 운영 & 종료거래 < 5 → **경보만** | — |

승률 단독 폐기 기준은 두지 않는다 — 추세추종은 승률 30%대가 정상이라 승률로 죽이면 옳은 전략이 먼저 죽는다.

### 발동 시

세션 정지(정상 매도 경로 청산) → **전략 타입 비활성화** → Discord 경보. 두 번째가 핵심이다 — 세션만
정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수 있다. 부활은 Walk Forward 재검증 → PAPER
재투입 → n≥20 누적을 거쳐야 한다. "파라미터를 고쳤으니 다시 켜자"는 부활이 아니라 **새 전략**이다.

**자동 정지는 기본 꺼져 있다** (`kill-criteria.auto-stop=false`) — 판정·경보는 항상 동작.
`WalkForwardValidationGate`와 같은 방식으로, 판정이 며칠간 옳게 나오는지 본 뒤 켤 것.

### 구현

- [`KillCriteriaConfig`](../core-engine/src/main/java/com/cryptoautotrader/core/risk/KillCriteriaConfig.java) — 임계값 단일 출처
- [`StrategyKillCriteriaService`](../web-api/src/main/java/com/cryptoautotrader/api/service/StrategyKillCriteriaService.java) — 매일 09:00 KST, 순수 판정 함수 `decide()` 분리
- [`BenchmarkAlphaService.altAvgHoldReturnPct`](../web-api/src/main/java/com/cryptoautotrader/api/service/BenchmarkAlphaService.java) — 세션 **자기 기간**의 벤치마크(기존 `getAlphaSummary()`는 전체 세션 중 가장 이른 시작일 하나로 집계해 개별 세션 알파를 못 낸다)
- `PositionRepository.aggregateClosedTradesPerSession()` — 판정 1회당 쿼리 1회
- **V69** `circuit_breaker_trip_count` — 기존엔 `circuit_breaker_triggered_at` 한 칸뿐이라 발동할 때마다 덮어써져 반복 횟수가 남지 않았다
- [`StrategyKillCriteriaDecisionTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/StrategyKillCriteriaDecisionTest.java) 15종 — 경계값을 못박아 **임계값이 조용히 완화되는 것을 막는다**(문서 §7 강제)

`StrategyDegradationWatchdog`와의 차이: 워치독은 신호 품질(사후 4h 수익률)을 6시간마다 보고 경보만 —
조기 경보. 이쪽은 실현 손익·자본을 하루 한 번 보고 정지까지 — 최종 판정.

### 같이 처리 — time stop 텔레그램 알림 (보류 항목 해소)

time stop은 손절도 익절도 아니라 `STOP_LOSS` 유형에 안 잡혔고, **자본 회수 이벤트가 통지되지 않았다.**
08-18 오전 259시간 고착 XRP 4건 청산에 알림이 한 건도 안 간 것이 실측 사례다.
`notifyTimeStop` 신설 → LIVE·DYNAMIC 두 경로에 배선.

### 검증

`:web-api:test` **277건 그린**(실패 0, 스킵 3 — 이전 262건에서 +15).

### 다음 단계 — 실자본 전면 중단 + 페이퍼 9세션 재구성 (사용자 실행 대기)

실행 스크립트: [`scripts/rebuild_paper_fleet.sh`](../scripts/rebuild_paper_fleet.sh)

10:47 KST 운영 DB 확인 결과 **OPEN/CLOSING 포지션 0건** — 청산 없이 안전하게 정지 가능한 창이다.
RUNNING 10세션(DYN 46~53, LIVE 198·199)을 전부 정지하고 DYN_PAPER 9세션으로 재구성한다.

**⚠️ 백엔드 API가 외부에 열려 있지 않다** — 8080은 공유기 관리페이지(`Server: Httpd/1.0`, 2017년 파일),
8081·3000·80·8443·9090·8090·8888 전부 닫힘. DB 8432만 포워딩돼 있어 **서버에서 직접 실행**해야 한다.

**DB 직접 UPDATE로 status를 바꾸지 않는다.** 포지션이 0건이라 08-18 오전 `max_hold_hours` 소급 적용처럼
보이지만 위험이 다르다 — 틱이 도는 중에 status를 STOPPED로 내리면 그 틱이 방금 연 실포지션이
**SL/TP 평가를 받지 못하는 고아 상태**로 남는다(틱 루프는 RUNNING만 순회하고 reconciler 4종은
CLOSING/유령만 다룬다). `stopSession`은 활성 주문 취소 → 포지션 청산 → 상태 전환을 한 트랜잭션에서 처리한다.

#### 세션 수의 실제 상한 — API 예산 (2026-08-18 측정)

세션을 늘려 표본 생성을 가속하려 했으나, **rate limit이 전략 수보다 먼저 걸린다.**

`DynamicTradingService.fetchCandles`는 **캐시를 쓰지 않고 Upbit REST를 직접 호출**한다. 세션마다 독립적으로:

```
CANDLE_LOOKBACK=500, MAX_CANDLES_PER_REQUEST=200      → 코인당 3 요청
SCANNING 세션 1개 = (targetWatchSize 10 + BTC가드 1) × 3 = 33 요청 / 60초 틱
UpbitApiRateLimiter.PERMITS_PER_SECOND = 7             → 420 요청/분
```

| 세션 | 요청/분 | 한도 대비 | 60초 틱 중 소요 |
|---|---|---|---|
| 8 (08-18 현재) | 264 | 63% | 38초 |
| **9 (목표)** | **297** | **71%** | 42초 |
| 12 | 396 | 94% | 57초 — 틱 초과 직전 |
| 14 | 462 | 초과 | ❌ |

**PAPER도 이 예산을 동일하게 쓴다** — DYN_PAPER가 REAL과 코드 경로를 100% 공유하는 것의 대가다.
"페이퍼는 공짜"는 자본 얘기지 rate limit 얘기가 아니다.

> **더 늘리려면**: `CANDLE_LOOKBACK` 500→400(요청 2회)이면 22 요청/세션 → 13세션까지 가능. 다만 MTF 계열이
> H1을 상위 타임프레임으로 리샘플링하므로(500 H1 → 125 H4) 400으로 충분한지 **검증 필요 — 미확인**.
> 근본 해결은 `market_data_cache` 기반 공유 캔들 캐시다(코인×타임프레임당 1회 조회로 O(세션) → O(코인)).

#### 세션 구성 (9종, 전부 DYN_PAPER · 초기자본 10,000 · maxHoldHours 24)

최근 60일 가동 이력이 있는 전략 11종 중, `strategy_type_enabled`에서 07-07에 일괄 비활성화된 4종
(BREAKOUT / MTF_MOMENTUM / REGIME_ROUTER / HEIKIN_ASHI_STOCH)을 제외한 **7종**:

| TF | 전략 |
|---|---|
| H1 (7) | MEANREV_BB · MOMENTUM_ICHIMOKU · MOMENTUM_ICHIMOKU_V2 · MTF_BTC · MTF_BTC_STRICT · MTF_CONFIRMED · PULLBACK_MTF |
| M15 (2) | PULLBACK_MTF · MTF_CONFIRMED (이력상 세션 생성 최다 = 신호 빈도 높음) |

- 비활성 4종은 그대로 둔다 — 새 정책상 부활 경로는 Walk Forward 재검증뿐이다(KILL_CRITERIA §6).
- **LIVE 198/199 단일코인 페이퍼 쌍은 만들지 않는다.** MEANREV_BB@H1·MTF_BTC_STRICT@H1이 위 목록에
  포함되고, `PaperTradingService` 경로는 07-01 이후 운영 가동 이력이 없어 9세션을 한꺼번에 올리기엔
  검증이 부족하다.
- **기존 페이퍼 4종(47/49/51/53)도 재시작**한다. 실현 거래 4건을 잃는 대신 9세션 시작일이 통일되어
  `BenchmarkAlphaService` 알파 비교가 깨끗해진다.

> ⚠️ **재시작은 kill criteria 시계를 0으로 되돌린다**(n, `mddPeak`, `startedAt`, NO_SIGNAL 30일 카운터).
> 지금은 n이 0~4라 실손해가 없지만, "성적이 나빠지면 재시작"이 습관이 되면 폐기 기준은 영구히 무력화된다.
> **이번이 마지막 리셋**이라는 전제로 실행한다.

---

## 🟢 2026-08-18 kill criteria 후속 — 폐기 우회 경로 차단 + 비활성화 범위 교정

> 위 세션 계획(7전략 × 2타임프레임)을 짜다 드러난 결함 두 가지. 단일 타임프레임에서는 노출되지 않는다.

### 1. 폐기 판정 우회 경로 2개 — `StrategyEnablementGate` 신설

`strategy_type_enabled` 검사가 `DynamicTradingService.createSession`에만 인라인으로 있었고
**LIVE와 `PaperTradingService`는 무검사**였다. kill criteria가 폐기 시 전략을 비활성화하는 목적은
"세션만 정지하면 같은 전략으로 새 세션을 만들어 재개할 수 있다"를 막는 것인데, 세 진입점 중 하나만
막혀 있으면 달성되지 않는다 — 우회로가 둘 열려 있었다.

→ [`StrategyEnablementGate`](../web-api/src/main/java/com/cryptoautotrader/api/service/StrategyEnablementGate.java)로
규칙을 추출해 LIVE·DYNAMIC·PAPER 세 경로에 동일 적용. "행이 없으면 허용"(차단 목록) 규칙은 유지.

운영 DB 실측: `strategy_type_enabled` 21행이 **전부 `is_active=false`**인데, 가동 중인 composite 전략
대부분은 아예 등재돼 있지 않다. 즉 이 테이블은 차단 목록으로 동작 중이며 기본값을 false로 바꾸면
미등재 전략 전부가 즉시 막힌다.

### 2. 비활성화 범위 과잉 — "전부 죽었을 때만" 으로 교정

판정 단위는 세션(= 전략 × 타임프레임)인데 `strategy_type_enabled`는 **전략명만** 키로 쓴다.
비활성화가 판정보다 한 단계 거칠어서, MEANREV_BB@M15 하나가 죽으면 **멀쩡한 MEANREV_BB@H1까지 막혔다.**

→ `applyKill`을 `stopKilledSession`(세션 정지) + `disableFullyKilledStrategies`(전략 비활성화)로 분리하고,
후자는 **그 전략의 운영 세션이 전부 KILL일 때만** 실행한다. 살아 있는 변형이 하나라도 있으면
세션 정지에서 멈춘다. WARN(NO_SIGNAL)도 "살아 있음"으로 친다 — 경보는 폐기가 아니다.

### 검증

`:web-api:test` **285건 그린**(실패 0, 스킵 3). 신규
[`StrategyEnablementGateTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/StrategyEnablementGateTest.java) 4종 ·
[`KillCriteriaStrategyDisableTest`](../web-api/src/test/java/com/cryptoautotrader/api/service/KillCriteriaStrategyDisableTest.java) 4종.

> `RateLimiterEmergencyStopTest`의 동시 acquire 테스트가 1회 실패했다(7 기대 → 8 획득). 리필 데몬 타이밍에
> 민감한 기존 테스트로, 재실행 시 통과하며 이번 변경과 무관하다. 간헐 실패가 반복되면 별도 처리 대상.

---

## 🔴 2026-08-06 모의투자(PAPER)가 실전(LIVE)과 완전히 다른 시스템이었음 — 정렬 완료

> 사용자 질문("같은 전략·같은 코인으로 실전과 페이퍼를 동시에 돌리면 결과가 같다고 볼 수 있나?")에서 출발한 검증. **답은 아니오였다.** 벤치마크 측정 직후 "페이퍼로 전환해 안전하게 학습하자"는 권고를 냈었는데, 그 권고의 전제가 성립하지 않았다.

### 발견된 차이 (정렬 전)

| 항목 | 실전(LIVE/DYNAMIC) | 페이퍼(정렬 전) |
|---|---|---|
| **진입 게이트** | EMA200 · BlackSwan · Range · BTC가드 | **전부 없음(0개)** |
| SL/TP 산정 | `ExitRuleCalculator` ATR 기반 clamp | `ExitRuleChecker` 고정 % |
| Time stop | 있음 | 없음 |
| 전략 SELL 게이트 | 최소보유 180분 + 본전청산 차단(+0.30%) | **없음 — 신호 즉시 청산** |
| `evaluate()` 파라미터 | coinPair·sessionStartedAt 등 주입 | **`emptyMap()`** |
| 캔들당 1회 평가 게이팅 | 있음 | **없음 — 60초마다 재평가** |
| 미마감 캔들 | `closedCandleSlice()`로 제외 | **그대로 사용** |
| 슬리피지 | 실측 −0.03~+0.42% | **0 (종가 정확 체결)** |
| 최소주문 5,000원 | 있음 | 없음 |

**가장 치명적인 건 진입 게이트 부재다.** 실전 로그에는 *"BUY 신호 86건 전량 진입 게이트 차단"*, *"54건 전량 차단 → 실행 0건"* 이 반복된다. 즉 실전은 BUY 신호 대부분을 걸러내는데 페이퍼는 그걸 전부 체결했다 — **거래 모집단 자체가 달랐다.** 이 상태의 페이퍼 성적은 실전 예측에 쓸 수 없다.

여기에 미마감 캔들 + 60초 재평가가 겹쳐, 실전에 없는 미세한 정보 이점까지 있었다.

### 정렬 작업 — [PaperTradingService](../web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java)

`runSessionStrategy`를 `LiveTradingService.processSessionTick`과 동일한 순서·게이트로 재작성했다.

- **진입 게이트 4종 이식** — EMA200(면제 전략 포함) → RANGE → BLACK_SWAN → BTC_MARKET_GUARD, LIVE와 같은 순서.
- **닫힌 캔들 게이팅** — `lastEvaluatedClosedCandle` 도입, 미마감 캔들은 직전 닫힌 캔들로 평가.
- **SL/TP를 `ExitRuleCalculator`로 교체** — 세 서비스(LIVE·DYNAMIC·PAPER)가 같은 ATR 공식을 공유.
- **time stop 추가** — `ExitRuleCalculator.shouldTimeStop`, 기본 비활성.
- **전략 SELL 게이트 3종** — 최소보유 180분 / 본전청산 차단 / 손실탈출 예외.
- **슬리피지 0.1%** — 매수는 높게, 매도는 낮게 체결. 백테스트와 같은 값으로 세 엔진 체결 가정 통일.
- **최소주문 5,000원** — 실거래에서 못 넣는 주문은 페이퍼도 넣지 않는다.
- **신호품질 기록**(`wasExecuted`/`blockedReason`) — LIVE와 동일하게 남겨 기대값 분석이 가능해졌다.
- **[V66](../web-api/src/main/resources/db/migration/V66__align_paper_trading_with_live.sql)** — `virtual_balance`에 `stop_loss_pct`·`invest_ratio`·`max_hold_hours` 추가. NULL이면 `risk_config` 기본값 폴백(LIVE와 동일 경로)이라 **LIVE 세션 설정을 그대로 복제해 돌릴 수 있다.**

### 의도적으로 적용하지 않은 LIVE 로직

자본 배정 게이트라 페이퍼의 목적과 상충하므로 제외했다 — `StrategyLiveStatusRegistry.isBlocked`, `WalkForwardValidationGate`, `§8 cross-session 잔고 가드`. **페이퍼는 미검증 전략을 검증하는 도구인데 "검증되지 않아 차단" 규칙을 적용하면 존재 이유가 사라진다.**

### 검증

- [PaperLiveAlignmentTest](../web-api/src/test/java/com/cryptoautotrader/api/service/PaperLiveAlignmentTest.java) 9건 — 청산 게이트 상수 3종 + `CANDLE_LOOKBACK`이 **LIVE와 같은 값인지 리플렉션으로 대조**, 데이터 공급 불변식 3건(`SYNC_CANDLE_COUNT ≥ lookback`, `> 201`, 세션 한도 ≥ 100), 세션 설정 배선 2건.
- **무력화 검증 2회 완료** — ① 페이퍼 `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT`를 180 → 60으로 바꾸면 1건 실패 ② `SYNC_CANDLE_COUNT`를 520 → 120(구 값)으로 되돌리면 2건 실패. 둘 다 확인 후 복원했다. **한쪽만 튜닝하거나 동기화 수를 낮추는 순간 CI가 잡는다** — 이번 사태의 근본 원인이 "조용히 벌어진 것"이었으므로 이 가드가 핵심이다.

### 격자 실험(코인 N × 전략 M) 대응 — 세션 한도 + 데이터 공급

사용자 요청("10코인 × 10전략 = 100세션은 돌려야 한다")을 검토하니, **세션 한도만 올리면 조용히 실패하는 상태**였다.

- **🔴 진짜 블로커는 캔들 데이터였다.** 페이퍼는 `market_data_cache`만 읽는데 H1 실측 현황이 BTC·ETH만 2,305건 최신이고, XRP 1,700건(44일 전), DOGE 523건(100일 전), SOL 407건(98일 전), 나머지는 **0건**이었다. 즉 지금 H1로 돌릴 수 있는 코인은 **2개뿐**.
- **더 나쁜 건 `SYNC_CANDLE_COUNT = 120`** — 소비 측 `CANDLE_LOOKBACK`은 500이고 EMA200 계열 전략은 닫힌 캔들 201개 이상을 요구한다. 이력 없는 신규 코인으로 세션을 만들면 캔들이 120개만 쌓여 **EMA200 전략이 구조적으로 신호를 낼 수 없다.** 그대로 100세션을 띄웠으면 "표본 0"이 또 반복됐을 것이다.

조치 3건:

- **`SYNC_CANDLE_COUNT` 120 → 520** — 소비 측은 항상 "최근 500개 구간"만 조회하므로, 이 값이 500 이상이면 과거 데이터에 갭이 있어도 평가 구간은 연속으로 채워진다. `UpbitCandleCollector`가 200개 단위로 페이지네이션하므로 pair당 3회 호출(10코인 = 30회/분, 업비트 공개 API 한도에 여유).
- **`MAX_CONCURRENT_SESSIONS` 20 → 120** — 10×10 격자에 여유분.
- **틱 단위 캔들 캐시 신설** — 같은 `(코인, 타임프레임)`을 세션마다 다시 조회하던 낭비 제거. 100세션이 10코인을 쓰면 500행 쿼리가 **200회 → 11회**(코인 10 + BTC 가드 1)로 줄어든다. 틱마다 새로 만들므로 stale 위험 없음.

**동기화 대상은 자동 확장된다** — `MarketDataSyncService`가 RUNNING 세션(페이퍼+실전)에서 `(coinPair, timeframe)`를 추출해 중복 제거하므로, 원하는 코인으로 세션을 만들기만 하면 다음 틱부터 수집이 시작된다. 다만 **500개가 쌓이기까지 시간이 걸리므로**(H1 기준 첫 sync에 520개 일괄 수집 → 즉시 충족) 세션 생성 직후 1~2틱은 "캔들 부족" 경고가 날 수 있다.

### ⚠️ 남은 차이 (문서화)

- 페이퍼는 서킷 브레이커(`riskManagementService.checkCircuitBreaker`)를 적용하지 않는다 — LIVE/DYNAMIC 전용 엔티티 오버로드라 `VirtualBalanceEntity`에 바로 쓸 수 없다. 연속 손실 중단이 필요하면 별도 작업.
- 페이퍼에는 WS 실시간 SL 감시가 없다(60초 폴링만). 실전은 WS로 초 단위 감지하므로 **급락 시 페이퍼가 실전보다 늦게 손절**될 수 있다.

---

## 🔴 2026-08-07 신호 반전 가설 — **기각**. 신호는 "거꾸로"가 아니라 그냥 예측력이 없다

> 08-06 측정에서 BUY 후 24h −4.81%, SELL 후 +1.06%가 나와 "신호 방향만 반대인 것 아닌가"라는 가설이 섰다. 맞다면 진입만 뒤집어 즉시 수익 전환이 가능하므로 **가장 먼저 확인해야 할 가설**이었다. 결론은 아니오다.

### 검증 방법 — [BacktestConfig.invertSignals](../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestConfig.java)

전략이 낸 신호의 방향만 뒤집고(BUY↔SELL), **손절·익절·time stop 등 리스크 규칙과 수수료·슬리피지는 그대로** 둔다(청산 규칙까지 뒤집으면 손절이 익절이 되어 실험이 무의미해진다). 운영 중인 7개 전략 × 4개 코인(BTC·XRP·ETH·SOL) H1 8,000캔들(2025-04-30 ~ 2026-03-30, 약 11개월)로 정방향/반전을 각각 돌려 비교했다.

### 결과 — 28개 조합

| | 평균 수익률 | 총 거래 |
|---|---|---|
| 정방향 | **−4.85%** | 815 |
| 반전 | **−6.01%** | 965 |

- **반전이 오히려 더 나쁘다.** "반전이 더 나은 조합 16/28(57%)"은 동전던지기 수준이고, 그나마도 대부분 **반전 시 거래가 거의 안 일어나서**(0~4건) "안 잃은 것"에 불과하다. 진입은 무포지션일 때만, 청산은 보유 중일 때만 가능하므로 반전은 대칭이 아니다 — BUY를 SELL로 바꾸면 진입 자체가 사라진다.
- **표본이 충분한 조합에서는 반전이 명백히 더 나쁘다.** `COMPOSITE_PULLBACK_MTF`는 정방향 95~123거래 / 반전 174~241거래로 양쪽 다 표본이 큰데, 4개 코인 전부 반전이 −8.7 ~ **−55.3%p** 악화됐다.
- 유일하게 눈에 띈 건 `COMPOSITE_MEANREV_BB` XRP(정방향 −3.52% → 반전 +14.83%, 31거래)뿐인데 단일 조합이라 노이즈로 봐야 한다.

**판정: 신호는 체계적으로 반대인 게 아니라 방향성 자체가 없다.** 손실의 정체는 예측 실패가 아니라 **무작위 진입 + 마찰비용(수수료 0.05% + 슬리피지 0.1%)**이다. PROGRESS에 미리 적어둔 두 갈래("양수면 중대한 발견, 아니면 무작위 + 마찰비용") 중 후자로 확정됐다.

### 더 중요한 부수 발견 — 백테스트도 이제 마이너스다

같은 기간 매수후보유는 BTC −24.43%, XRP −35.56%, SOL −39.16%, ETH +21.63%였다. 즉 **하락장이었고**, 이 구간에서 전략들의 정방향 평균이 −4.85%인 건 "시장보다는 덜 잃었다"로 읽을 여지가 있다. 다만 승률이 대부분 **0~23%**로 극단적으로 낮아, 소수의 큰 이익으로 다수의 손실을 메우는 구조인데 그 이익이 마찰비용을 못 넘고 있다.

과거 기록된 "백테스트 +106~127%"와 지금 측정이 크게 다른 건 전략·구간·게이트 구성이 달라졌기 때문이다. **지금은 백테스트와 실전이 같은 방향(마이너스)을 가리킨다** — 측정 정합성 자체는 오히려 건강해졌다는 뜻이다.

### 남은 것

반전이 답이 아니므로 **엣지는 신호 모델 자체에서 찾아야 한다.** 현재 22개 전략 중 실전 검증 통과 0개라는 사실은 그대로다. 다음 후보는 ① 마찰비용을 줄이는 방향(거래 빈도 축소·지정가 진입) ② 승률이 아니라 손익비를 키우는 방향 ③ 전략 폐기 기준(kill criteria) 확정 후 대량 폐기.

---

## 2026-08-07 벤치마크 대비 알파를 대시보드에 내장

> "이 시스템이 잘하고 있는가"를 수개월간 판정하지 못한 근본 원인 — 수익률을 **절대값**으로만 봤다. 시장이 −10%일 때의 −3%는 좋은 성적이고, 시장이 +10%일 때의 −3%는 나쁜 성적이다. 08-06엔 수동 SQL로 계산했는데, 이걸 매번 손으로 하지 않도록 시스템에 넣었다.

- **[BenchmarkAlphaService](../web-api/src/main/java/com/cryptoautotrader/api/service/BenchmarkAlphaService.java)** — 운영 중 실자본 세션의 수익률을 같은 기간 BTC·ETH·XRP·SOL·DOGE 매수후보유와 비교. `GET /api/v1/trading/benchmark-alpha`.
- **PAPER 세션은 집계에서 제외** — 실자본 성과가 아니므로 섞이면 알파가 왜곡된다.
- **시세는 `market_data_cache`에서 읽는다** — `candle_data`는 백테스트용 과거 저장소라 최신 구간이 비어 있다(실측: H1 최신이 2026-03-30에서 멈춰 있었다). 이걸 모르고 `candle_data`를 썼다면 알파가 조용히 엉뚱하게 계산됐을 것이다.
- **데이터 없는 코인은 0%가 아니라 `null`(판정 불가)** — 0%로 세면 알파가 희석돼 실제보다 좋아 보인다.
- **대시보드 상단 고정 노출** — 시장을 이기는 중이면 초록, 못하면 빨강 테두리.

**검증**: [BenchmarkAlphaServiceTest](../web-api/src/test/java/com/cryptoautotrader/api/service/BenchmarkAlphaServiceTest.java) 5건. 무력화 2회 — ① PAPER 제외 가드 제거 → 1건 실패 ② 알파 뺄셈 제거 → 처음엔 1건만 실패했는데, 확인해보니 첫 테스트가 **알트 평균을 0%로 잡아 뺄셈을 빼먹어도 통과하는 약한 테스트**였다. 평균을 +2%로 바꿔 네 값이 모두 다르게 만든 뒤 2건 실패로 정상화. 무력화 검증이 없었으면 그냥 통과하는 테스트로 남았을 것이다.

---

## 2026-08-07 DYNAMIC(멀티코인) PAPER 모드 추가

> 배경: 페이퍼 정렬(위 섹션) 직후 "실전과 페이퍼를 동시에 돌리면 같다고 볼 수 있나?"는 질문에서, 벤치마크 열세를 검증하려면 결국 **동적 멀티코인 엔진**(운영 중인 세션 대부분이 여기 있음)에서 코인×전략 조합을 대량으로 실험할 수 있어야 한다는 결론에 도달. `PaperTradingService`(단일 코인) 확장이 아니라 `DynamicTradingService` 자체에 PAPER 모드를 추가하는 쪽으로 재설계.

### 설계 원칙

전략·게이트·SL/TP·time stop·워치리스트 로직은 REAL과 **100% 공유**하고, 체결 한 곳만 분기한다.

- `dynamic_session.trading_mode` (`REAL`|`PAPER`, [V67](../web-api/src/main/resources/db/migration/V67__add_trading_mode_to_dynamic_session.sql)) 로 세션 단위 구분.
- `position`/`order.session_kind`를 REAL="DYNAMIC", PAPER="DYN_PAPER"로 분리 — 기존 REAL 전용 정산 스케줄러 4종 중 3종(`reconcileDynamicClosingPositions`/`GhostPositions`/`OrphanBuyPositions`)은 이미 `SESSION_KIND`로 먼저 필터링하고 있어 **자동으로 격리**된다.
- PAPER 체결은 `OrderExecutionEngine`(실거래소 연동)을 아예 거치지 않고, 같은 `@Transactional` 메서드 안에서 슬리피지(0.1%)·수수료(0.05%)를 반영해 동기 시뮬레이션한다 — REAL의 비동기 체결(주문 제출 → 콜백으로 사이즈 확정) 구조에서 오는 부분 롤백 위험(과거 P0 패턴)이 PAPER에는 애초에 존재하지 않는다.
- 매도 후처리(`finalizeDynamicSell`)는 REAL 정산 경로와 완전히 동일한 함수를 그대로 재사용 — 손익·수수료·부분체결·세션잔고 복원 로직이 두 모드에서 갈라지지 않는다.

### 발견 및 수정한 버그 — `reconcileDynamicSessionBalance`가 PAPER를 감지하지 못함

나머지 안전망 3종과 달리 이 스케줄러는 **세션을 먼저 순회한 뒤** 포지션/주문을 `SESSION_KIND`(REAL 고정)로 조회한다. PAPER 세션은 자기 포지션이 `DYN_PAPER`로만 존재하므로, 이 스케줄러 입장에서는 "포지션 없음"으로 보여 정상적인 보유 잔고 차이가 고아 잔고로 오인되어 강제 롤백될 뻔했다. `if (session.isPaper()) continue;` 가드 추가로 수정.

이 버그는 **테스트를 sabotage-then-restore로 검증하다가** 발견했다 — 처음 작성한 `balanceReconcile_ignoresPaperSessions` 테스트는 grace-period(3분) 가드에 막혀 통과했지만, 실제로는 kind 체크 로직에 도달하지도 못한 "우연히 통과"였다. `backdateUpdatedAt` 헬퍼로 grace period를 우회하도록 테스트를 다시 짜자 진짜 버그가 드러났다.

### 검증

- [DynamicPaperTradingTest](../web-api/src/test/java/com/cryptoautotrader/api/service/DynamicPaperTradingTest.java) 8건 — 세션 생성 기본값/PAPER 지정, PAPER 매수가 `OrderExecutionEngine`을 호출하지 않고 즉시 체결되는지, REAL 매수는 여전히 호출하는지, PAPER 매도 즉시 정산, 고아매수/잔고 정산 스케줄러가 PAPER를 무시하는지, REAL·PAPER 간 교차세션 노출 격리.
- **무력화 검증 2회** — ① `SESSION_KIND_PAPER`를 `"DYN_PAPER"` → `"DYNAMIC"`(REAL과 충돌)로 바꾸면 격리 의존 테스트 3건 실패 ② `reconcileDynamicSessionBalance`의 `isPaper()` 가드 제거 시 정확히 1건 실패. 둘 다 복원 후 8/8 통과 재확인.
- `session_kind` 컬럼이 `VARCHAR(10)`이라 `"DYNAMIC_PAPER"`(13자)는 잘림 위험 — `"DYN_PAPER"`(9자)로 명명, 코드 작성 전에 컬럼 정의를 먼저 확인해 사전에 회피.

### 의도적으로 REAL 전용으로 남긴 것

- `StrategyLiveStatusRegistry.isBlocked`/`WalkForwardValidationGate` — 자본 배정 게이트라 페이퍼(미검증 전략 검증용)의 목적과 상충, PAPER 세션 생성 시 건너뜀.
- WS 실시간 SL/TP 감시(`doOnRealtimePriceEvent`, `refreshWsSubscription`) — PAPER는 60초 폴링만 사용, 앞서 정렬한 단일코인 페이퍼와 동일한 제약.
- `restoreBlackSwanCooldown` 등 REAL 전용 쿨다운 복원 — 변경 없음.

### 미완료

- 프런트에서 DYNAMIC 세션 생성 시 `tradingMode` 선택 UI 없음(API 응답 필드는 노출됨) — 아직 요청 없음.
- 전체 회귀 테스트(`./gradlew :web-api:test`) 최종 확인 진행 중.

---

## 🔴 2026-08-06 벤치마크 대비 성과 측정 — **시장보다 못하고 있음(알파 음수)**

> 사용자 질문("이 시스템이 잘하고 있는가")에 답하기 위해 운영 DB에서 처음으로 **벤치마크 대비 측정**을 수행. 그동안 이 비교가 없어서 판정 자체가 불가능했다.

### 시스템 vs 매수후보유 (07-31 세션 재구성 ~ 08-06, 약 6일)

| 구분 | 시작 | 현재 | 수익률 |
|---|---|---|---|
| **동적 7세션** | 70,000 | 67,687 | **−3.30%** |
| **LIVE 2세션** | 20,000 | 20,068 | **+0.34%** |
| **전체** | 90,000 | 87,756 | **−2.49%** |

| 벤치마크(매수후보유) | 시작가 | 현재가 | 수익률 |
|---|---|---|---|
| KRW-DOGE | 101 | 99 | −1.98% |
| KRW-ETH | 2,721,000 | 2,708,000 | −0.48% |
| KRW-SOL | 105,800 | 104,800 | −0.95% |
| KRW-XRP | 1,537 | 1,489 | −3.12% |
| **알트 평균** | | | **−1.63%** |
| KRW-BTC ⚠️ | 90,890,000 | 91,900,000 | +1.11% (08-03 기준, 기간 짧음) |

- **동적 −3.30% vs 알트 평균 −1.63% → 알파 약 −1.67%p (6일)**. 그냥 들고 있는 것보다 못하다.
- **LIVE +0.34%는 전략 성과가 아니다** — 194가 BTC를 들고 있었고 그 기간 BTC가 +1.11%였을 뿐. 투자비율 0.8을 감안하면 오히려 홀딩보다 못 따라갔다. 195는 6일간 거래 0건.

### 누적 실현손익 — **−21,854원** (전 기간)

| 세션종류 | 청산건수 | 승 | 승률 | 실현손익 | 수수료 |
|---|---|---|---|---|---|
| LIVE | 237 | 21 | **8.9%** | **−16,901** | 1,204 |
| DYNAMIC | 18 | 2 | **11.1%** | **−4,953** | 68 |

**LIVE 월별 분해가 결정적이다:**

| 월 | 건수 | 승 | 실현손익 | 수수료 |
|---|---|---|---|---|
| 2026-03 | 43 | **0** | −138.46 | 138.46 |
| 2026-04 | 89 | **0** | −347.83 | 347.92 |
| 2026-05 | 21 | **0** | −83.95 | 83.98 |
| 2026-06 | 45 | 14 | **−13,883.47** | 456.40 |
| 2026-07 | 39 | 7 | −2,447.46 | 177.69 |

- 3~5월은 **손실 = 수수료 전액**(0승) — 이건 성과가 아니라 05-31에 규명한 "가짜 본전" 버그(매도 체결가 미산출 → `realizedPnl`이 −수수료로만 기록)의 흔적이다. **그 기간은 측정 자체가 안 되고 있었다.**
- 06-23 P0 수정으로 **제대로 측정되기 시작하자마자 6월 −13,883원**. 즉 "2개월 본전권"은 착시였고, 실제 실력이 드러난 6~7월에 −16,331원이 나왔다.

### 신호 기대값 — BUY·SELL 양쪽 다 역예측

| 구간 | 신호 | n | 4h | 24h | 4h 승률 |
|---|---|---|---|---|---|
| DYNAMIC 최근 14일 | BUY | 89 | **−1.585%** | **−4.811%** | 29.2% |
| DYNAMIC 최근 14일 | SELL | 3,672 | +0.161% | +1.055% | 48.4% |
| 실제 체결분 전기간 | DYNAMIC BUY | 25 | −0.614% | **−4.350%** | |
| 실제 체결분 전기간 | LIVE BUY | 173 | −0.080% | −0.149% | |

- **BUY 신호 24h −4.81%(n=89)** — 표본이 50 → 89로 늘었는데 오히려 더 나빠졌다. 같은 기간 시장이 −1.6%였으니 **시장 대비로도 −3.2%p 언더퍼폼**.
- **SELL 후에는 오른다(+1.055%)** — 팔지 말았어야 했다는 뜻. BUY도 SELL도 방향이 반대다. ⚠️ 단 SELL 3,672건은 대부분 SCANNING 중 포지션 없을 때의 노이즈라 해석에 주의 필요.
- LIVE BUY(n=173)는 −0.08%로 사실상 **무작위** — 예측력이 0에 가깝다.

### 07-31 이후 청산 8건 — 1승 7패

| 사유 | 건수 | 손익 |
|---|---|---|
| 실시간 손절(WS) −7.006% | 2 | −1,092.68 |
| 손절 −7.911% | 1 | −666.22 |
| 시간 초과 청산 | 2 | −250.96 |
| 전략 SELL (손실) | 2 | −403.84 |
| 전략 SELL (이익) | 1 | **+87.78** |

### 판정

**배관은 고쳤지만 엣지가 없다.** 지난 한 달간 잡은 버그(FK·롤백·유령포지션·잔고누수·SL이탈)는 전부 실재했고 잘 고쳤지만, 그걸 다 고친 지금도 시장보다 못하다. 문제는 코드가 아니라 **신호 자체에 예측력이 없다**는 것이고, 이건 백테스트(+106~127%) → Walk Forward(+3~4%) → 실전(마이너스)의 3단계 붕괴가 이미 예고한 결과다.

**→ 조치 권고는 아래 "보류·결정 대기 항목" 최상단 참조.**

---

## ✅ 최근 작업 이력 (요약)

### 2026-08-06 [P2 후속] DYNAMIC SL 워치독 + 헬스체크 이력 화면 + MSW 미들웨어 버그

- **DYNAMIC SL 워치독 신설**(`DynamicTradingService.warnStaleSlCheck`) — LIVE에만 있던 §9 워치독(3분 미점검 시 경보 + 그 코인만 REST 강제 갱신)을 DYNAMIC에도 동일 패턴으로 추가. 이제껏 DYNAMIC엔 이런 안전망 자체가 없었다.
- **헬스체크 이력 화면**(`/admin/health-check`) — `GET /api/v1/admin/health-check/history` + Next.js 페이지 신설, 브라우저 실검증(Playwright, mock 모드) 완료.
- **부수 발견·수정**: `proxy.ts` 인증 미들웨어가 `/mockServiceWorker.js`를 제외 목록에서 빠뜨려 로그인 전 MSW 등록 자체가 리다이렉트로 막히던 버그 1줄 수정.
- 신규 테스트 6건 + 전체 스위트 **233건(스킵 1) 전부 통과**. 미배포·미커밋.

### 2026-08-06 [P2] 운영 헬스체크 자동화 + SL 워치독 대응 조치

- **`OperationalHealthCheckService`** 신설 — 세션 잔고 정합성·주문 시퀀스 갭·유령 포지션·무출구 고착 포지션(24h+) 4대 점검을 매일 08:30 KST 자동 실행, `daily_health_snapshot`(V65)에 기록, 이상 시 Discord 알림. **감지·알림만, 자동 정산은 안 함**(LIVE 유령 포지션 자동 정산은 실거래 자금에 직접 손대는 범위 확장이라 스코프 밖).
- **LIVE SL 워치독에 대응 조치 추가** — 그동안 `warnStaleSlCheck`가 경보만 보내던 것을, 그 코인 하나만 REST로 즉시 강제 갱신해 SL 감시를 스스로 복구 시도하도록 확장. 전역 WS 폴백(`isWsUnhealthy`)이 놓치는 "다른 코인은 정상인데 이 코인만 조용히 끊긴" 사각지대를 메운다.
- 신규 테스트 18건 + 전체 스위트 227건(스킵 1) 통과.

### 2026-08-06 [P1] LIVE/DYNAMIC 청산 엔진 통합 (2/2) — SL/TP 공식 + time stop 공유

- **`ExitRuleCalculator`** 신설 — DYNAMIC의 ATR 기반 SL/TP 공식(`resolveStopLossPct`/`resolveTakeProfitPrice`)과 time stop 판정(`shouldTimeStop`)을 공유 클래스로 추출. **LIVE는 이제껏 고정 `stopLossPct`만 썼는데**(세션 194 BTC 136시간 고착의 원인), 신규 진입부터 DYNAMIC과 동일한 ATR 기반 SL/TP를 쓴다.
- V64 마이그레이션 — `live_trading_session.max_hold_hours` 컬럼 신설, 기본값 0(비활성) — time stop은 기본값이 꺼져 있어 배포해도 트리거되지 않지만, **SL/TP 공식 자체는 배포 즉시 바뀐다**(플래그 없음 — 07-31 개편을 뒤늦게 이식하는 것뿐이라 DYNAMIC에서 이미 검증된 변경으로 간주).
- 기존 회귀 테스트 10건 이름만 바꿔 통과(계산 결과 불변 확인) + 신규 9건 + 전체 스위트 무회귀.

### 2026-08-06 [P1] 신호 기대값 검증 게이트 신설

- **`WalkForwardValidationGate`** 신설 — 전략별 최근 Walk Forward 결과(verdict·OOS 기대값)를 판정해 세션 생성에 배선. 기본값 비활성(`REQUIRE_WALK_FORWARD_GATE=false`) — 위 보류 항목 참조.
- 미리보기 API `GET /api/v1/strategies/walk-forward-gate-status` 신설.
- 신규 테스트 12건 + 전체 스위트 200건 통과.

### 2026-08-06 세션 카드 모바일 레이아웃 깨짐 수정 + MSW 목이 통째로 죽어 있던 문제

- **근본 원인**: MSW 핸들러 22개가 `/api/v1/...`에 등록됐는데 클라이언트는 `axios baseURL='/api/proxy'`를 거쳐 `/api/proxy/api/v1/...`로 호출 → 프록시 도입 이후 목이 전부 매칭 실패, 앞선 "28라우트 이상 없음" 검증이 사실 빈 화면만 훑은 것이었음.
- 경로 접두사 수정 + 동적 세션 픽스처 추가 후 재검증(28라우트 × 6폭 전부 통과). 세션 카드 `flex-wrap`/`shrink-0` 누락으로 좁은 화면에서 글자 단위 세로 배열되던 버그도 함께 수정.

### 2026-08-06 [P0 보안] DB 초기화 비밀번호 소스 하드코딩 제거

- `DbResetService`의 하드코딩된 비밀번호를 `DB_RESET_PASSWORD` 환경변수로 이관(fail-closed + 상수시간 비교 + 503 분리 응답). **사용자가 운영 `.env`에 새 값 적용 확인 완료.**

### 2026-08-06 FE 네비게이션 5개 대분류 재편 + 모바일 대응

- 사이드바 브레이크포인트 부재로 모바일에서 화면 65%를 먹던 구조를 `navConfig.ts` 단일 소스 + 데스크톱 사이드바/모바일 상단앱바·하단탭바·바텀시트·드로어로 교체. 그룹: 백테스트·모의투자 / 실전매매 / 전략관리 / 분석 / 설정.

### 2026-08-06 전 세션(동적 7 + 실전 2) 운영 DB 점검

- 기반 건전성 전부 정상(9/9 RUNNING·36h 무결손 틱·잔고 정합성 0원·주문 갭 0). 이 점검에서 나온 문제들(무출구 고착, 동적 세션 시가평가, LIVE time stop 부재, SL 워치독 알림 유실 등)은 이후 세션들(위 P1/P2)에서 대부분 해소 — 남은 것은 위 "보류·결정 대기 항목" 참조.

### 2026-08-05 이전 (요약만 — 상세는 `old_progress.md`)

- 08-05: "손절 5%인데 7~8%" 원인 규명(설계대로의 동작, 하한이었음) — SL 배수·상한 축소, TP 상한 신설, REST 폴백 DYNAMIC 코인 누락 수정, BLACK_SWAN 진입가 가드 추가. 배포 완료.
- 08-04: 멀티코인 24h 운영 분석, BLACK_SWAN 쿨다운 재기동 소실 버그 발견·수정.
- 08-03: 매도 롤백 P0(유령 포지션) 해소, 블랙스완 쿨다운, 미실현손익 복구.
- 07-31 이전: 세션 격리 버그, 청산 규칙 전면 개편(ATR 기반 SL 최초 도입), FK 위반 수정, 워치리스트 큐레이션 등 — DYNAMIC 시스템의 근간을 잡은 다수의 P0/P1 수정. 상세는 `old_progress.md` 07-02~07-31 구간 참조.

---

## 프로젝트 개요

- **서비스**: 업비트 기반 가상화폐 자동매매 시스템
- **운영 환경**: Ubuntu 서버, Docker Compose (`docker-compose.prod.yml`)
- **기술 스택**: Spring Boot 3.2 (Java 17) + Next.js 16.1.6 / React 19.2.3 (TypeScript) + TimescaleDB + Redis

### 모듈 구조

```
crypto-auto-trader/
├── web-api/          # Spring Boot 백엔드 (Gradle 멀티모듈)
│   ├── core-engine/      # 백테스팅 엔진, 리스크, 포트폴리오
│   ├── strategy-lib/     # 전략 22종 (단일 11 + 복합 11)
│   ├── exchange-adapter/ # Upbit REST/WebSocket
│   └── web-api/          # REST API, 스케줄러, 서비스
├── crypto-trader-frontend/  # Next.js 16.1.6 / React 19.2.3 프론트엔드
├── docs/                    # 설계 문서 및 진행 기록
└── docker-compose.prod.yml  # 운영용 (backend + frontend + db + redis + db-backup)
```

### 구현된 전략 22종

**단일 전략 (11종)**: VWAP / EMA Cross / Bollinger Band / Grid / RSI / MACD / Supertrend / ATR Breakout / Orderbook Imbalance / Stochastic RSI / Volume Delta

**복합 전략 (11종)**:

| 전략 | 구성 | 실적합 코인 | 요약 |
|------|------|------------|------|
| COMPOSITE | Regime 자동 선택 | 범용 | — |
| COMPOSITE_MOMENTUM | MACD×0.5 + VWAP×0.3 + Grid×0.2, EMA 필터 | ETH·SOL | ETH +53.6%, SOL +59.8% |
| COMPOSITE_ETH | ATR×0.5 + OB×0.3 + EMA×0.2 | ETH | 구버전 평균 +48.7% (재검증 필요) |
| COMPOSITE_BREAKOUT (CB) | ATR×0.5 + VD×0.3 + MACD×0.2, EMA+ADX+RSI Veto 필터 | **BTC·ADA** | BTC **+106.71%**, ADA **+86.98%** |
| COMPOSITE_MOMENTUM_ICHIMOKU (CMI_V1) | CB_MOMENTUM + Ichimoku 필터 | XRP | XRP +1.04% (유일 양수) |
| COMPOSITE_MOMENTUM_ICHIMOKU_V2 (CMI_V2) | MACD×0.5 + SUPERTREND×0.3 + Grid×0.2 + Ichimoku 필터 | **DOGE** | DOGE **+124.77%** |
| COMPOSITE_BREAKOUT_ICHIMOKU | CB + Ichimoku 필터 | — | ⚠ CB와 동일 (ADX 중복) |
| COMPOSITE_REGIME_ROUTER (CRR) | ADX/ATR 레짐 → CB/V1/V2 자동 위임 | **SOL·ETH** | SOL **+65.38%**, ETH +65.09% |
| COMPOSITE_MTF_BTC | CB(H1) + Supertrend(H4) | **ETH·AAVE·CHZ** | ETH **+127.70%**, AAVE +28.15% |
| COMPOSITE_MTF_MOMENTUM | CMI_V2(H1) + Supertrend(H4) | **BLUR·DOGE** | BLUR **+48.06%**, DOGE +83.40% |
| COMPOSITE_MTF_CONFIRMED | CRR(H1) + Supertrend(H4) | **XRP** 범용 | XRP **+3.37%** (유일 흑자) |
| MACD_STOCH_BB | MACD + StochRSI + 볼린저 6조건 AND | ❌ 비활성화 | BTC -2.32%, 거래 극희소 |

전체 코인별 백테스트 수치(2026-04-30 H1 FULL, 17코인 × 7전략)와 Walk-Forward 결과는 `old_progress.md` 참조.

---

## 🟢 배포 권고 / 🚨 배포 금지 (2026-04-30 MTF 백테스트 기준, 마지막 갱신)

> H1 FULL 2022~2026-04-30 백테스트 결과 기반. 이후 재검증 없음 — 오래된 기준이니 신규 배포 판단 시 최신 Walk Forward로 재확인 권장.

### Tier 1 — 백테스트 검증 통과

| 코인 | 권장 전략 | FULL 수익률 | MDD | Sharpe |
|------|-----------|------------|-----|--------|
| **BTC** | COMPOSITE_BREAKOUT | +106.71% | -8.88% | 1.24 |
| **ETH** | COMPOSITE_MTF_BTC | +127.70% | -7.24% | 1.35 |
| **SOL** | COMPOSITE_REGIME_ROUTER | +65.38% | -14.93% | 0.76 |
| **DOGE** | CMI_V2 | +124.77% | -30.75% | 0.87 |
| **ADA** | COMPOSITE_BREAKOUT | +86.98% | -14.14% | 0.96 |

### Tier 2 — 흑자 전환·신규 발굴, 관찰 후 투입

| 코인 | 권장 전략 | FULL 수익률 | MDD |
|------|-----------|------------|-----|
| **XRP** | COMPOSITE_MTF_CONFIRMED | +3.37% | -15.67% |
| **AAVE** | COMPOSITE_MTF_BTC | +28.15% | -31.01% |
| **BLUR** | COMPOSITE_MTF_MOMENTUM | +48.06% | -11.91% |
| **CHZ** | COMPOSITE_MTF_BTC | +14.09% | -14.88% |

### 🚨 배포 금지

- 전 코인 × M15 타임프레임(오버트레이딩 -99%), 전 코인 × FAIR_VALUE_GAP(구조 결함)
- ETH/XRP/ADA/CHZ/AAVE × 특정 전략 조합 다수 — 상세는 `old_progress.md` 2026-04-30 섹션 참조
- MOVE/SUPER/FLOCK/AXL/BIO/KERNEL — 거래수 부족으로 통계 신뢰성 없음

---

## Dev Workflow Orchestrator

`.claude/` 6개 서브에이전트(SparkAI → PLAN → Design → Do → Check → Report) 파이프라인 — 상세는 `.claude/ORCHESTRATOR.md` 및 프로젝트 루트 `CLAUDE.md` 참조.
