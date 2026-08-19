# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 이 파일은 **최신 작업 이력(최근 세션 몇 개) + 보류/결정 대기 항목 + 프로젝트 참조 정보**만 담는다. 오래된 상세 이력은 [`docs/old_progress.md`](old_progress.md)(2026-08-06 이전 전체 백업)와 [`docs/CHANGELOG.md`](CHANGELOG.md)를 참조.
> **2026-08-06 / 2026-08-19**: 파일이 비대해질 때마다 날짜별 상세 이력을 `old_progress.md` 로 이관하고 이 파일에는 요약만 남긴다. 상세 근거·재현 과정이 필요하면 `old_progress.md` 에서 날짜로 검색할 것.
> **마지막 갱신**: 2026-08-19 — V71~V74 배포 완료(규칙 지문 · 매도 정산 멱등화 · 청산 사유 구조화 · 세션별 A/B 파라미터).

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

---

## 🟢 2026-08-19 지문 구멍 마감 (2) — `SignalQualityDampenGate` (`dampen.*`)

`CompositeStrategy` 와 같은 처리를 감쇠 게이트에도 적용했다. 상수 3개
(`nightDampenStartHourKst` 20, `defaultNightDampenFactor` 0.6,
`defaultTransitionalDampenFactor` 0.5)가 `dampen.*` 로 지문에 실린다.

- `SignalQualityDampenGate.behaviorParams()` + `RulesetRegistry.base()` 배선
- 리플렉션 가드 — **뮤테이션 검증**: 더미 상수 `MUTATION_PROBE` 추가 시 그 이름으로 실패
- 노출값이 실제 상수와 일치하는지도 검사 (지문이 거짓말하지 않도록)
- `RulesetRegistryCompositionTest` 필수 키에 `dampen.*` 3종 추가
- `:web-api:test` **356건**, `:core-engine:test` **211건** 전부 통과

> 같은 가드가 이제 세 곳이다 — `ExitRuleCalculator`(`exitcalc.*`),
> `CompositeStrategy`(`composite.*`), `SignalQualityDampenGate`(`dampen.*`).
> **"동작을 바꾸는 코드 상수는 지문에 싣는다"** 가 이 코드베이스의 규칙이다.

### ⚠️ 배포 순서 — A/B 세션보다 **먼저** 배포할 것

이 배포는 지문에 `dampen.*` 3키를 더해 **모든 해시를 다시 가른다**(현재 DYN_PAPER 56키 → 59키).
A/B 세션을 먼저 만들면 실험 도중에 표본이 쪼개진다:

```
[실험 시작] ─── 2주 ─── [배포] ─── 계속
          해시 X            해시 Y     ← 합쳐지지 않음
```

동적 세션 거래 빈도가 12일 7건이다. 이미 부족한 표본을 실험 중에 반으로 쪼개면 결론이 안 난다.

**올바른 순서**: ① 이 수정 배포 → ② A/B 세션 4개 생성 → ③ 수 주간 배포 없이 관찰.

---

## 📌 내일 오전(2026-08-20) 할 일

> 어제(08-19) 배포가 네 번 있었고 지문이 그때마다 갈렸다. **깨끗한 데이터 구간은 08-19 15:41부터**다.

### 1. 🔴 09:00 kill criteria 첫 판정 — **이번 한 번뿐인 검증 기회**

지문 도입으로 지문별 표본(`tradeCount`)이 전 세션 0으로 리셋됐다. `tradeCountAllRulesets`
수정이 없었다면 오늘 아침 다수 세션이 `NO_SIGNAL` WARN 으로 떴을 것이다.

- [ ] **안 뜨면 수정이 검증된 것, 뜨면 수정이 안 먹은 것.** 다음 리셋이 없으니 놓치면 재현 불가.
- [ ] 예상: 전부 KEEP → `kill_criteria_judgment` **0행 유지**. `persist()` 는 KEEP 이 아닌 것만
      저장하므로 **빈 테이블을 실패로 오독하지 말 것.**
- [ ] `noSignalDays = 30` 이고 H1 함대는 08-07 시작(13일차)이라 유휴 93세션도 아직 안 걸린다.

```sql
SELECT * FROM kill_criteria_judgment ORDER BY evaluated_at DESC LIMIT 20;
```

### 2. ~~DYN_PAPER 지문 갱신 확인~~ ✅ 08-19 15:42 완료

PAPER 53키(`ad42b3c26851`/`b0747bc1f7b1`), DYN_PAPER **56키**
(`dd5e0639a9c8`/`f5e7e4bf4f50`) 로 재등록 확인. `composite.*` 12키 포함.
아래 쿼리는 다음 배포 때 재사용.

```sql
SELECT ruleset_hash, engine, first_seen_at,
       (SELECT count(*) FROM regexp_split_to_table(params_text, E'\n') k WHERE k <> '') AS keys,
       params_text LIKE '%composite.%' AS has_composite
FROM ruleset_snapshot ORDER BY first_seen_at DESC LIMIT 6;
```

### 3. `exit_reason` 이 쌓이기 시작했는지

V73 의 핵심 산출물. 며칠 뒤 **익절 미작동(P1-b)** 판단의 근거가 된다.

- [ ] 청산 건에 `exit_reason` 이 100% 붙는지 (NULL 이면 어느 경로가 빠졌는지)
- [ ] `UNKNOWN` 이 늘면 사유를 안 넘기는 경로가 있다는 뜻

```sql
SELECT session_kind, exit_reason, count(*) FROM position
WHERE closed_at > TIMESTAMPTZ '2026-08-19 15:02:00+09' GROUP BY 1,2 ORDER BY 1,2;
```

### 4. ⚠️ 감쇠 A/B — 실험군 세션 4개 생성 (미실행)

`strategy_params` 가 설정된 세션이 **0개**다. 기다린다고 A/B 데이터는 쌓이지 않는다.
스크립트: [`scripts/create_ab_dampen_sessions.sh`](../scripts/create_ab_dampen_sessions.sh)
— 저장소에 커밋돼 있으므로 배포하면 서버에서 바로 실행할 수 있다. 인증 토큰(`API_AUTH_TOKEN`)과
부하 사전점검이 들어 있다.

대조군은 이미 돌고 있다 — **세션 56(H1) / 63(M15)**, `strategy_params = NULL`.

| 팔 | 전략 | TF | strategyParams | 대조군 기본값 |
|---|---|---|---|---|
| A1 | PULLBACK_MTF | H1 | `{"emaFilterDampenFactor":1.0}` | 0.0 (역추세 BUY 전량 차단) |
| A2 | PULLBACK_MTF | M15 | `{"emaFilterDampenFactor":1.0}` | 0.0 |
| B1 | PULLBACK_MTF | H1 | `{"transitionalDampenFactor":1.0}` | 0.5 (절반 감쇠) |
| B2 | PULLBACK_MTF | M15 | `{"transitionalDampenFactor":1.0}` | 0.5 |

- [ ] **선행: `dampen.*` 지문 수정 배포** — 이 배포가 해시를 마지막으로 가른다. 세션보다 먼저.
- [ ] **선행: API 부하 확인** — 백엔드 로그에 rate limit(429) 오류가 없는지. 아래 참조.
- [ ] 4개 생성 후 지문이 **대조군과 다르게** 찍히는지 확인 (같으면 A/B 가 성립하지 않는다)
- [ ] 설정은 대조군과 완전히 동일해야 한다 — **M15 는 `watchlistRefreshMin=30`**(H1 은 60)

> **⚠️ API 부하가 제약일 수 있다.** 두 엔진의 비용 구조가 다르다:
>
> | 엔진 | 비용 | 근거 |
> |---|---|---|
> | PAPER 함대 | (코인×TF)×3 = **48/분**, 세션 수 무관 | `tickCandleCache` 공유 |
> | DYNAMIC | **33/분/세션** | 워치리스트를 세션마다 각자 조회 (공유 캐시 없음) |
>
> 문서 수치대로면 현재 동적 14세션 ≈ **462/분**으로 한도 420 을 이미 넘는 계산이다.
> 4개를 더하면 594. 다만 워치리스트 갱신은 정상 주기로 돌고 있어(H1 60분·M15 30분 준수)
> 심한 스로틀링 징후는 없다 — **DB 로는 확인이 안 되므로 백엔드 로그를 봐야 한다.**
>
> 여유가 없으면 대안:
> - 신호가 안 나오는 동적 전략 세션을 줄여 예산 확보. 단 동적은 코인을 고정하지 않으므로
>   **함대에서 조용하던 전략이 여기서도 조용하리라 단정할 수 없다** — 며칠 관측 후 판단.
> - PAPER 함대에서 A/B — API 비용이 세션 수와 무관해 사실상 공짜. 단
>   `MAX_CONCURRENT_SESSIONS=120` 이고 현재 112 라 슬롯이 8개뿐이다.

> **왜 TRANSITIONAL 도 넣었나**: 2일치 로그에서 임계값(0.3)을 넘겼을 매수 점수를 죽인 건수가
> TRANSITIONAL **45건** / EMA **21건**이다. EMA 만 실험하면 더 큰 레버를 놓친다.
>
> **왜 전량 off(1.0)인가**: 동적 세션 거래 빈도가 12일 7건으로 표본이 귀하다. 대비를 최대로 줘야
> 적은 표본에서도 차이가 보인다. 효과가 확인되면 그때 중간값을 찾는다.
>
> **왜 PULLBACK_MTF 만**: 유일하게 신호가 나오는 전략이다(BUY 비율 3.14%, 나머지 6개는 0.05~0.26%).

**판정은 2단계다 — 기간을 혼동하지 말 것:**

| 단계 | 질문 | 지표 | 걸리는 시간 |
|---|---|---|---|
| 1 | 감쇠를 끄면 **진입이 실제로 늘어나는가** | BUY 신호 수 / 진입 수 (n = 수천 틱) | **1~2일** |
| 2 | 그 추가 진입이 **돈이 되는가** | 실현손익 · `exit_reason` 분포 (n = 수 건) | **수 주** |

1단계가 "차이 없음"으로 나오면 실험을 접으면 된다. 1단계가 양수여도 **2단계 전에는 기본값을
바꾸지 말 것** — 지금 아는 것은 감쇠가 죽이는 신호의 양이지 그 신호의 질이 아니다.
함대 승률이 22% 인 걸 보면 감쇠가 손실을 막고 있었을 가능성도 충분하다.

---

## 🗓️ 2026-08-18 ~ 08-19 작업 요약

> 상세 근거·재현 과정은 [`docs/old_progress.md`](old_progress.md) 하단 아카이브를 날짜로 검색.

이 구간의 주제는 **"데이터를 믿을 수 있게 만들기"** 다. 매매 성과를 개선한 게 아니라,
성과를 **측정할 수 있는 상태**를 만들었다.

### 마이그레이션

| 버전 | 내용 | 배포(KST) |
|---|---|---|
| V71 | 규칙 지문 (`ruleset_snapshot` + 3개 테이블에 `ruleset_hash`) | 08-19 12:37 |
| V72 | 매도 정산 멱등 표식 (`dynamic_sell_settlement`) | 08-19 15:02 |
| V73 | 청산 사유(`exit_reason`) + 페이퍼 포지션 컬럼 정렬 + 판정 지문 | 08-19 15:02 |
| V74 | 세션별 전략 파라미터 (`strategy_params`) — A/B 기반 | 08-19 15:33 |

### 핵심 성과

- **규칙 지문** — 규칙이 바뀌면 표본을 **무효화하는 대신 분할한다.** 현재 53키
  (engine 2 / exitcalc 5 / composite 12 / exit 16 / gate 11 / scan 4 / session 4 + strategy.params).
- **P0: 매도대금 21회 중복 지급** — 동적 세션 49 가 정지 실패 루프로 `available_krw`
  10,000 → 174,752. 원인은 `REQUIRES_NEW` 선커밋과 낡은 엔티티 `save()` 의 낙관적 락 충돌.
  멱등 표식을 **대금 반영과 같은 트랜잭션**에 두어 구조적으로 해결.
- **청산 사유 구조화** — 이전에는 자유 텍스트에 손익률이 박혀 있어 `GROUP BY` 불가.
  `ExitRuleChecker.ExitType` 이 이미 SL/TP 를 구분했는데 호출부가 버리고 있었다.
- **EMA 데드밴드** — 격차 0에 가까워도 "하락추세" 로 판정해 매수 점수를 0으로 만들던 문제.

### 데이터가 말하는 것 (판단 대기)

- **익절이 사실상 작동하지 않는다** — 전체 SELL 6,944건 중 `TAKE_PROFIT` **1건**, `STOP_LOSS` 6,702건.
  `take_profit_price` 는 종료 313건 중 260건에 설정돼 있는데 발동은 1건.
  → `TP_RR_MULTIPLIER = 2.0` 이 이 변동성에서 너무 먼가? **`exit_reason` 데이터로 판단.**
- **왕복 비용 0.3%** (슬리피지 0.1%×2 + 수수료 0.05%×2) 대비 **BUY 신호 기대값은 4h +0.076%**.
  기대값이 비용의 1/4 이다. 24h 에는 −0.499% 로 뒤집힌다.
  → `max_hold_hours = 24` 는 **감쇠 구간을 통과해 보유**하도록 설정돼 있다.
- **페이퍼 함대 112세션 중 93개가 12일간 거래 0건** — 게이트가 아니라 신호 생성이 병목.
  2일치 HOLD 7,033건 중 **88%가 `buy=0.00`**(지표가 하나도 안 켜짐). 임계값을 낮춰도 안 바뀐다.
  다만 감쇠 2종(TRANSITIONAL 45 + EMA 21 = 66건)이 같은 기간 통과분(51건)보다 많이 죽이고 있다.
  → **A/B 로만 판단 가능.** 죽인 신호가 손실이었을 수도 있다(함대 승률 22%).
- **60일 실적**: 24승 85패(22%), 평균 −153.91원. 단 지문 이전이라 규칙 세대가 섞였다 — 방향성 근거일 뿐.

### 미해결 / 의도적 보류

- **익절 파라미터(P1-b)** — 데이터 확보 후 판단. 지금 바꾸면 근거 없는 변경이 된다.
- **감쇠 기본값** — 전역으로 바꾸면 모든 세션이 한쪽 팔이 되어 A/B 가 불가능해진다.
- **`kill-criteria.auto-stop = false` 유지** — 지문별 표본이 재축적될 때까지.
- **페이퍼 함대 재생성 불필요** — 자본 오염 평균 −0.20%(최악 −6.50%, −10% 미만 0개),
  거래 귀속은 지문이 이미 해결. 재생성해도 유휴 93세션 문제는 안 고쳐진다.
- **LIVE 사실상 정지** — 14일간 BUY 신호 1건.
- **`public.position.exit_reason` 실측 미완** — LIVE/DYNAMIC 이 포지션을 안 열어 표본이 없다.

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
