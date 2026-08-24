# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 이 파일은 **최신 작업 이력(최근 세션 몇 개) + 보류/결정 대기 항목 + 프로젝트 참조 정보**만 담는다. 오래된 상세 이력은 [`docs/old_progress.md`](old_progress.md)(2026-08-06 이전 전체 백업)와 [`docs/CHANGELOG.md`](CHANGELOG.md)를 참조.
> **2026-08-06 / 2026-08-19**: 파일이 비대해질 때마다 날짜별 상세 이력을 `old_progress.md` 로 이관하고 이 파일에는 요약만 남긴다. 상세 근거·재현 과정이 필요하면 `old_progress.md` 에서 날짜로 검색할 것.
> **마지막 갱신**: 2026-08-24 — 운영DB DYN_PAPER/PAPER 로그 분석(아래 섹션). 직전: 2026-08-20 — 오전 점검(kill criteria KEEP · exit_reason 100% · A/B 1단계 진행) + 프런트 정비(e2e 44통과 · 타입가드 복구 · 죽은 코드 제거 · 테마 페인트).

---

## 🟠 2026-08-24 운영DB 로그 분석 — DYN_PAPER(동적 페이퍼) / PAPER(고정코인 페이퍼)

> 대상: 운영 `yhpapa.iptime.org:8432`, 읽기전용. 기간 2026-08-19 재기동 ~ 08-23 24:00 UTC (5일).
> **주의**: 페이퍼 데이터는 두 곳에 나뉘어 있다 — 동적 세션은 `public`(`dynamic_session`/`position.session_kind='DYN_PAPER'`),
> 고정코인 세션은 **`paper_trading` 스키마**(`virtual_balance`/`position`/`order`). `public` 만 보면 PAPER는 로그만 있고
> 세션·포지션이 없는 것처럼 보인다(초판 분석의 오독 원인).
>
> 가동 현황: 동적 18세션(id 54~71, 전부 PAPER, 각 10,000원) + 고정코인 133세션(`virtual_balance` RUNNING, 각 1,000만원).
> REAL 동적/LIVE 세션은 08-18 05:13 전량 중지.

### 1. 핵심 결론 — `COMPOSITE_PULLBACK_MTF` 하나가 전체 손실을 만든다

PAPER 청산 354건(08-19~23) 기준:

| 그룹 | 청산 | 승률 | 평균이익 | 평균손실 | 누적손익 | 손절/익절 |
|---|---|---|---|---|---|---|
| **COMPOSITE_PULLBACK_MTF** | 163 | **11.7%** | +214,155 | -61,828 | **-4,834,211** | 135 / 18 |
| 나머지 6전략 | 191 | 25.7% | +229,163 | -73,307 | **+819,443** | 135 / 49 |

R:R 3.46 이면 손익분기 승률이 22.4%인데 PULLBACK은 11.7%다. **이 전략만 빼면 나머지 시스템은 흑자**이며,
전체 -401만원은 사실상 PULLBACK 단독 -483만원이다. n=163으로 표본도 충분하고 M15(-349만, 12.8%)·H1(-135만, 6.7%)
양쪽 타임프레임에서 동일하게 무너진다.

### 2. 타임프레임 — M15 > H1 (백테스트 결론과 반대)

| 소스 | M15 | H1 |
|---|---|---|
| DYN_PAPER (n=87) | +7,611 / 승률 58.5% | **-4,600** / 승률 17.6% |
| PAPER (n=354) | -1,375,998 / 승률 21.9% | **-1,859,122** / 승률 8.6% |

PAPER는 양쪽 다 적자지만(PULLBACK 포함) 전략별로 보면 7개 중 5개가 M15에서 더 낫고, H1에서 승률 0%인 전략이
3개(MOMENTUM_ICHIMOKU, ICHIMOKU_V2, MTF_CONFIRMED)다. **방향성은 두 소스에서 일치한다.**
→ 「🚨 배포 금지 — 전 코인 × M15(오버트레이딩 -99%)」 항목과 실측이 정면 충돌. 재검토 필요.

### 3. 청산 사유

| exit_reason | PAPER 건수 | 승률 | 누적 | DYN_PAPER 건수 | 누적 |
|---|---|---|---|---|---|
| STOP_LOSS | 262 | 0% | -17,544,562 | 19 | -9,595 |
| TAKE_PROFIT | 67 | 100% | +15,269,620 | 23 | +15,379 |
| STRATEGY_SIGNAL | 1 | 100% | +28,330 | 28 | **-2,661** |
| TIME_STOP | 0 | — | — | 10 | +717 |

손절이 익절의 3.9배(262:67). DYN_PAPER의 STRATEGY_SIGNAL 청산 28건은 승률 32%로 순손실 — 지표 기반 조기 청산이
익절 도달 전에 이익을 깎는 정황. 다만 n=28이라 단독 근거로는 약하다(PAPER에는 이 경로가 1건뿐).

### 4. 필터 효과 검증 (DYN_PAPER BUY 신호의 24시간 후 수익률)

| 분류 | 건수 | 평균 24h 수익률 | 상승비율 |
|---|---|---|---|
| 실행됨 | 73 | **-0.23%** | 49.3% |
| 동일코인 노출상한 차단 | 233 | **+5.75%** | 70.8% |
| 기타 차단 | 94 | +2.31% | 60.6% |
| BTC_MARKET_GUARD 차단 | 18 | -1.73% | 27.8% |
| BLACK_SWAN 쿨다운 차단 | 15 | **-9.57%** | 6.7% |

- **BTC_MARKET_GUARD / BLACK_SWAN 은 확실히 작동한다** — 차단 신호의 사후 수익률이 크게 음수.
- 동일코인 노출상한(`MAX_SESSIONS_PER_COIN=1`) 차단분의 사후 수익률이 +5.75%로 높지만, **이미 상승 중인 코인이
  반복 BUY를 내는 생존편향**이 섞여 있다. 게다가 이 상한은 수익 최적화가 아니라 2026-08-06 단일코인 23% 집중
  사고를 막으려 넣은 리스크 장치다(`DynamicTradingService` javadoc). 성급히 완화하면 안 된다 — A/B 전용 항목.
- 실행된 신호가 차단된 신호보다 나쁘다는 점 자체는 **진입 선별 로직의 역선택** 신호로 남는다.

### 5. 🐛 `order.quantity` 이중 단위 — 조치 완료

`public."order"` 의 **시장가 매수** 행은 `quantity` 에 코인 수량이 아니라 **투입 KRW 금액**이 들어간다.
Upbit `price` 타입 주문 파라미터가 KRW 총액이라서 생긴 <b>의도된</b> 오버로드지만
(`OrderExecutionEngine#submitToExchange`), 읽는 쪽이 이를 모르면 `price × quantity` 가 최대 10^8 배로 튄다.

실측: BUY 행의 `quantity = position.invested_krw` 일치율 DYN_PAPER 98/98, DYNAMIC 24/24, LIVE 195/271.
`quantity = position.size` 는 0건. 예) order 9609 KRW-TRAC — `quantity=10334.152`(투입금), 실제 `size=20.114`,
`price × quantity = 5,306,721`(실제의 513배).

**과거 데이터 보정은 불필요하다** — `filled_quantity` 가 전 구간에서 실제 코인 수량을 정확히 갖고 있다
(`filled_quantity = position.size` 일치: DYN_PAPER 98/98, DYNAMIC 24/24, LIVE 264/271). 읽는 쪽만 고치면 된다.
참고로 `paper_trading."order"` 는 이 문제가 없다(BUY/SELL 모두 코인 수량, 404/404).

조치:
- `web-api/util/OrderAmounts` 신설 — `coinQuantity()` / `krwAmount()` 로 단위 판정을 한 곳에 모음 (+단위 테스트 6건)
- `OrderEntity.quantity` 에 이중 단위 경고 javadoc
- `TradingController.toOrderMap` — 수수료를 `price × quantity` → `krwAmount × feeRate` 로 교정, `quantity` 는
  코인 수량으로 내보내고 `krwAmount` 필드 추가 (차트 툴팁이 시장가 매수에서 KRW를 "수량"으로 표시하던 버그)
- 프런트 `lib/utils.fmtOrderQuantity()` 신설 후 대시보드·세션 상세·업비트 로그 3곳 통일
  (업비트 로그 페이지에만 있던 임시 분기를 공용 헬퍼로 흡수)
- CSV 내보내기 헤더 `주문수량` → `주문수량(시장가매수=KRW)`

### 6. 세션 49 잔고 오염 — 이미 조치된 건 (재발 없음 확인)

세션 49는 청산 2건의 실현손익 합계가 -168원인데 `available_krw` 가 221,876원(+2,118%)이다.
원인은 **2026-08-19 P0로 이미 규명·수정된 매도대금 중복 지급**이다 — 매도대금 반영이 `REQUIRES_NEW` 라
바깥 트랜잭션이 롤백돼도 살아남는데 포지션 CLOSED 저장은 함께 롤백돼, "대금은 남고 포지션은 OPEN 복귀"가
반복되며 21회 중복 지급됐다(`DynamicTradingService#finalizeDynamicSell` 주석).
수정: `dynamic_sell_settlement` 정산 표식을 대금과 **같은 트랜잭션**에 기록해 포지션당 1회만 반영
(Flyway V72, 운영 적용 2026-08-19 06:02 — 신규 세션 54~71 기동 5분 전).

재발 없음 확인 — RUNNING 18세션 전부 `available_krw + 미청산 invested_krw = 10,000 + 누적 실현손익` 이
**정확히** 일치한다(오차 0원). §5 의 `quantity` 단위 문제와는 무관한 건이었다(초판 분석의 추정은 틀렸다).

### 7. 오독 정정 (초판 분석에서 잘못 보고한 것)

- ❌ ~~「PAPER 로그 고아 — 세션·포지션 0건」~~ → **사실 아님**. `paper_trading` 스키마를 조회하지 않아서 생긴 오독.
  `virtual_balance` 에 세션 133건, `position`/`order` 정상 기록 중.
- ❌ ~~「exit_reason NULL 7건 — 08-20 점검의 100%와 불일치」~~ → **이미 해결된 건**. NULL 행의 `closed_at` 최댓값이
  DYN_PAPER 2026-08-19 05:51, paper_trading 2026-08-19 05:57로 **전부 08-19 06:00 재기동 이전**이다.
  재기동 이후 NULL은 0건 — 08-20 점검 결과가 맞았다.
- ❌ ~~「세션 49 오염은 quantity KRW 단위를 코인 수량으로 오인한 강제청산 탓」~~ → **원인 오추정**.
  실제 원인은 매도대금 중복 지급이며 V72로 이미 수정됨(§6). 강제청산 경로는 `pos.getSize()` 를 쓰고 있어 정상이다.
- ❌ ~~「MEANREV_BB 중단 1순위」~~ → **표본 부족으로 철회**. DYN_PAPER n=9(-1,858)였으나 PAPER n=18에서는 +136,077.
  두 소스가 반대다. 진짜 중단 후보는 §1 의 PULLBACK_MTF(n=163).

### 8. ✅ PULLBACK_MTF 고정코인 세션 중지 완료 (2026-08-24)

`scripts/stop_pullback_paper_fleet.sh` 로 16세션 중지, 실패 0건. DB 확인:
`paper_trading.virtual_balance` 의 PULLBACK_MTF **RUNNING 0건 / STOPPED 19건**, 미청산 포지션 0건.

강제청산 3건은 모두 이익으로 닫혔다(207 KRW-BTC +4,738 / 143 KRW-DOGE +38,063 / 122 KRW-BTC +4,840,
합 +47,641) — 08-23 시점 평가손(-17,695)보다 개선된 값으로 청산됐다.
다만 분석 시점과 중지 사이(08-24 00:04~00:17)에 STOP_LOSS 4건이 추가로 터져 **-567,770** 을 더 잃었다.

**동적 PULLBACK 6세션(56·63·68·69·70·71)은 유지했다** — 근거 표본 163건이 전부 고정코인 쪽이고,
동적에서는 같은 전략이 n=42에 +1,865(7전략 중 2위)로 정반대다. 게다가 이 6세션은 08-19부터 돌고 있는
감쇠 A/B 그 자체다(56·63 대조군 / 68·69 EMA군 / 70·71 TRANS군).

> 스크립트 주의: 백엔드 API 는 **외부 미개방**이라 운영 서버에서 `localhost:8080` 으로만 접근되고
> `Authorization: Bearer $API_AUTH_TOKEN`(`.env`) 이 필요하다. 대상 ID는 하드코딩하지 않고 API 응답에서 뽑는다.

### 9. H1 축소 판단 근거 — PULLBACK 제거 후에도 H1은 적자

PULLBACK_MTF 를 뺀 잔여 6전략의 08-19~23 청산 191건:

| timeframe | 청산 | 승률 | 누적손익 | 평균이익 | 평균손실 | 손익분기 승률 |
|---|---|---|---|---|---|---|
| **M15** | 160 | 28.8% | **+1,766,303** | +218,806 | -72,796 | 25.0% |
| **H1** | 31 | 9.7% | **-946,860** | +387,979 | -75,386 | 16.3% |

→ **H1 부진은 PULLBACK 탓이 아니다.** 순수 타임프레임 효과로 남는다. H1은 이기면 크게(+387,979) 이기지만
빈도가 손익분기(16.3%)에 못 미친다(9.7%).

단 **H1 표본은 31건으로 얇다**(6전략 5일). 전략별로 보면 승률 0%가 3개로 갈리고, 나머지 3개는 n=4에
승률 25%(각 1승)라 사실상 운의 영역이다:

| H1 전략 | n | 승률 | 누적 |
|---|---|---|---|
| MOMENTUM_ICHIMOKU_V2 | 7 | **0.0%** | -579,744 |
| MTF_CONFIRMED | 6 | **0.0%** | -495,660 |
| MOMENTUM_ICHIMOKU | 6 | **0.0%** | -495,660 |
| MEANREV_BB | 4 | 25.0% | +361,495 |
| MTF_BTC / MTF_BTC_STRICT | 4 / 4 | 25.0% | +131,355 / +131,355 |

### 10. 🔴 벤치마크 대비 — 시스템은 **어느 장에서도 수익을 못 낸다**

지금까지 성적을 **같은 기간 BTC 단순보유**와 나란히 놓으면 성격이 드러난다.

| 구간 | BTC 단순보유 | 시스템 | 격차 |
|---|---|---|---|
| LIVE 실전 2026-03-17~08-17 | **-17.9%** (109.6M→90.0M) | -2.98% (44세션 110만원) | **+14.9%p 우위** |
| 동적 REAL 07~08월 | 하락장 | -0.54% (49세션 105만원) | 우위 |
| PAPER 함대 08-18~08-24 | **+17.3%** (90.6M→106.2M) | **-0.40%** (91세션 9.1억) | **-17.7%p 열위** |

→ **하락장에서는 잘 지키고, 상승장에서는 못 먹는다.** 방향과 무관하게 대략 0% 근처에 붙어 있는
"자본 보존 기계"다. 지금 상태로 실전에 넣으면 하락장 방어 가치는 있지만 상승장 기회비용이 그보다 크다.

누적 표본: 실전 307건(LIVE 277 + 동적 30) 전부 적자, 페이퍼 404건 적자. **700건 넘게 흑자 구간이 없다.**

### 11. 🔧 근본 원인 — 손절폭이 노이즈보다 좁다

PAPER 청산 360건(08-19~)을 청산 사유별로 보면 진단이 명확하다.

| exit_reason | n | 평균 보유 | 평균 수익률 |
|---|---|---|---|
| **STOP_LOSS** | 273 | **36분** | **-0.88%** |
| TAKE_PROFIT | 67 | 153분 | +2.87% |

손절이 **평균 36분 만에 -0.88%** 에서 걸린다. 암호화폐 15분봉의 일상적 노이즈 폭이 그 정도다 —
논지가 전개될 시간을 갖기 전에 잡음에 털린다. 이긴 거래는 4배 오래(153분) 들고 +2.87%를 낸다.

`ExitRuleCalculator`: SL폭 = `clamp(ATR(14) × 1.5, floorPct, 8%)`, TP = SL × 2.
설계 의도(변동성 적응)는 맞지만 **배수 1.5가 캔들 주기 기준이라 보유 horizon에 비해 너무 좁다.**

산술적으로는 아깝다 — 실측 R:R = 2.87/0.88 = **3.26**, 손익분기 승률 **23.5%**, 실제 승률 **19.7%**.
**승률 4%p 차이로 적자**다. 손절폭을 넓히면(SL_ATR_MULTIPLIER 1.5 → 2.5~3.0) 노이즈 손절이 줄어
승률이 올라가는 방향이라, 이게 단일 최대 레버다. 단 TP도 함께 멀어지므로 A/B 없이는 확언 불가.

### 12. 🐛 MTF_BTC 와 MTF_BTC_STRICT 는 **같은 전략이다**

두 전략의 청산 43건이 **코인·진입시각·손익까지 전부 동일**하다(진입 시각 차이 30ms — 같은 tick).
`STRICT` 변형이 신호를 전혀 좁히지 못하고 있다. 7전략을 돌린다고 믿고 있지만 실제로는 6개 이하다.
H1 구간에서는 MOMENTUM_ICHIMOKU 와 MTF_CONFIRMED 도 성적이 완전히 겹친다(n=6, -495,660 동일).

### 13. 전략별 활용가치 (paper_trading 전체 이력, 거래당 기대수익률 기준)

| 전략 | n | 승률 | 거래당 기대 | 누적 | 판정 |
|---|---|---|---|---|---|
| MTF_CONFIRMED | 23 | 21.7% | **+0.287%** | +346,326 | 후보 (n 부족) |
| MTF_BTC | 43 | 30.2% | **+0.160%** | +222,972 | 후보 |
| MTF_BTC_STRICT | 43 | 30.2% | +0.160% | +222,972 | **MTF_BTC 중복 — 제거 대상** |
| MOMENTUM_ICHIMOKU | 27 | 18.5% | +0.102% | +80,866 | 판단 보류 |
| MEANREV_BB | 20 | 20.0% | +0.098% | +104,594 | 판단 보류 |
| MOMENTUM_ICHIMOKU_V2 | 48 | 20.8% | -0.119% | -598,045 | 열위 |
| PULLBACK_MTF | 200 | 12.0% | **-0.427%** | -6,488,440 | ✅ 중지 완료 |

> 주의: 흑자 5개는 전부 **n<50** 이고 측정 구간이 BTC +17.3% 상승장이다. 통계적 유의성 없음 —
> "이 순서로 유망하다"까지는 말할 수 있어도 "수익성이 검증됐다"고는 못 한다.

### 14. 전략 구현 감사 — 실제로는 3개 신호원뿐 (2026-08-24)

프리셋 정의(`CompositePresetRegistrar`)를 코드로 훑은 결과, 활성 6전략이 **독립적이지 않다**.

| 신호 코어 | 이 코어를 쓰는 전략 |
|---|---|
| **MACD + VWAP + GRID** (CMI_V1) | MOMENTUM_ICHIMOKU · **MTF_CONFIRMED**(레짐 4개 중 3개를 CMI_V1로 위임) |
| **ATR + VD + MACD** (CB) | MTF_BTC · ~~MTF_BTC_STRICT~~ · **MTF_CONFIRMED**(VOLATILITY 레짐) |
| **MACD + SUPERTREND + GRID** | MOMENTUM_ICHIMOKU_V2 |
| **BB + RSI + VWAP** | MEANREV_BB ← **유일하게 직교** |

- `CompositeRegimeRouter` 는 TREND·TRANSITIONAL·RANGE(4중 3)를 전부 CMI_V1 에 위임한다.
  즉 **MTF_CONFIRMED ≈ MOMENTUM_ICHIMOKU + H4 Supertrend 필터** 다 — 별개 전략이 아니라 파생형.
- **MACD 가 6개 중 5개에 들어간다.** 6전략 분산 운용이라 믿고 있지만 실제 분산 효과는 훨씬 작고,
  §10의 "어느 장에서도 0% 근처" 성질도 이걸로 설명된다 — 다 같이 켜지고 다 같이 꺼진다.
- 이건 버그가 아니라 설계상 계층 구조다. 다만 **성과 비교 시 독립 표본으로 취급하면 안 된다.**

### 15. 🐛 `strictHtf` 구조적 무효 — MTF_BTC_STRICT 는 MTF_BTC 의 완전 중복

운영 실측에서 두 전략 청산 43건이 **코인·진입시각·실현손익까지 전부 동일**했다(진입 차 30ms = 같은 tick).
두 프리셋의 유일한 차이가 `strictHtf` 인데 결과가 같다 = 이 플래그가 아무것도 막지 못했다.

**원인**: `MtfConfirmedStrategy` 에서 `strictHtf` 가 동작을 바꾸는 분기는 둘뿐이다 —
① HTF 캔들 부족 ② HTF 신호가 HOLD. 그런데 HTF 확인자인 `SupertrendStrategy` 는
**데이터만 있으면 절대 HOLD 를 반환하지 않는다**(추세선 위=BUY / 아래=SELL 이분법).
게다가 `getMinimumCandleCount() = max(ltf, htfFactor×12)` 이라 호출 시점에 HTF 캔들 12개가
이미 보장된다. **두 분기 모두 도달 불가** → strict 는 no-op.

이 문제는 MTF_BTC_STRICT 만이 아니라 **HTF 확인자로 Supertrend 를 쓰는 모든 MTF 프리셋에 해당**한다.

- 회귀 고정: `core-engine/.../SupertrendStrictHtfNoOpTest` (300 시나리오 × BUY/SELL 동일성 + 도달불가 계약)
- 코드 표시: `CompositePresetRegistrar` 에 DEPRECATED 주석. **등록은 남긴다** — 과거 세션 조회와
  재활성화 경로를 깨지 않기 위해서다.
- 비활성화: `scripts/disable_duplicate_strategies.sh` (세션 정지 + `strategy_type_enabled` is_active=false)
- 되살리려면 HTF 확인자를 **HOLD 를 낼 수 있는 전략**으로 교체해야 한다(예: ADX 임계 미달 시 HOLD).

> `StrategyEnablementGate` 는 **신규 세션 생성만** 막는다(LIVE·DYNAMIC·PAPER 공통). 이미 RUNNING 인
> 세션은 계속 돌기 때문에 스크립트가 세션 정지까지 함께 처리한다.

### 16. 이미 비활성인 전략 (참고)

`strategy_type_enabled` 는 **차단 목록**으로 동작한다(행이 없으면 활성). 현재 21개가 false:

- 단일 지표 전부 — RSI · MACD · BOLLINGER · EMA_CROSS · VWAP · SUPERTREND · ATR_BREAKOUT ·
  STOCHASTIC_RSI · GRID · VOLUME_DELTA · ORDERBOOK_IMBALANCE · FAIR_VALUE_GAP · MACD_STOCH_BB
- 복합 — COMPOSITE · COMPOSITE_ETH · COMPOSITE_MOMENTUM · COMPOSITE_BREAKOUT ·
  **COMPOSITE_BREAKOUT_ICHIMOKU**(2026-04-12, "ADX 필터가 이미 다 막아 Ichimoku 가 무의미") ·
  COMPOSITE_REGIME_ROUTER · COMPOSITE_MTF_MOMENTUM · HEIKIN_ASHI_STOCH

→ COMPOSITE_BREAKOUT_ICHIMOKU 는 이번 건과 **같은 유형의 중복**(필터가 이미 걸러 추가 필터가 무효)이며
이미 4월에 처리돼 있었다. 같은 패턴이 반복되고 있으니, **새 필터 레이어를 추가할 때는
"이 필터가 실제로 막은 신호 수"를 먼저 세는 검증을 붙일 것.**

### 17. ⚠️ 손절폭 확대는 이미 한 번 실패했다 — A/B 설계 시 필수 고려

`ExitRuleCalculator.TP_PCT_MAX` javadoc에 남아 있는 2026-08-05 실측 기록:

> TP를 SL 폭의 2배로 따라 키우다 보니 KRW-META2는 TP가 **+14.10%** 로 잡혔다.
> 넓은 SL은 반드시 맞고 넓은 TP는 사실상 안 맞는다 — 07-31 개편 이후 5일간 **익절 0건 / 손절 3건**.

즉 §11의 "손절폭을 넓히자"는 처방은 **그대로 실행하면 07-31을 반복한다.** 원인은
`TP = SL × TP_RR_MULTIPLIER(2.0)` 연동이라 SL을 넓히면 TP가 같이 멀어져 도달 불가가 되는 것.

**따라서 A/B 실험군은 두 상수를 함께 움직여야 한다:**
- `SL_ATR_MULTIPLIER` 1.5 → 2.5 (노이즈 손절 감소)
- `TP_RR_MULTIPLIER` 2.0 → 1.2~1.5 (TP 절대거리를 현재 수준으로 유지)

목표는 R:R을 키우는 게 아니라 **승률을 손익분기 위로 올리는 것**이다. 현재 실측 R:R 3.26은
이미 충분하고 부족한 건 승률(19.7% vs 손익분기 23.5%)이다.

**구현 제약**: 이 값들은 `private static final` 상수이지 세션 파라미터가 아니다. A/B 하려면
세션별 오버라이드 경로를 만들고 **`behaviorParams()` 지문에도 반영해야 한다** —
안 하면 서로 다른 규칙의 거래가 한 표본에 섞인다(`RulesetFingerprintTest` 가 강제).
감쇠 A/B가 `strategy_params` jsonb를 쓴 것과 같은 방식이 선례다.

### 18. 감쇠 A/B 진행 상황 — 판정까지 1~2개월

08-19 시작, 5일 경과 시점의 arm별 청산 건수:

| arm | H1 | M15 |
|---|---|---|
| 대조군 (56·63) | 3건 (-717) | 9건 (+1,308) |
| EMA off (68·69) | 5건 (-489) | 9건 (+263) |
| TRANS off (70·71) | 3건 (+285) | 11건 (+1,510) |

**arm당 3~11건** — 통계적 판정에 필요한 50건 수준까지 현재 속도로 1~2개월 걸린다.
지금 어느 arm이 낫다고 말할 수 없다. 그대로 두고 기다리는 것 외에 할 일이 없다.

### 19. ✅ 손절폭 A/B 구현 완료 (2026-08-24) — 배포 후 실행 대기

**가설**: 손절이 평균 36분 만에 -0.88% 에서 걸린다(§11). 15분봉 노이즈 폭이 그 정도라
논지가 전개되기 전에 잡음에 털린다. 실측 R:R 3.26 은 이미 충분하고 부족한 건 승률뿐이다
(19.7% vs 손익분기 23.5% — **3.8%p 차이**).

**실험군 파라미터** — ⚠️ 두 값을 반드시 **함께** 움직인다(§17 의 07-31 실패 전례):
```json
{"slAtrMultiplier": 2.5, "tpRrMultiplier": 1.2}
```
SL 1.5→2.5 ATR (손절폭 1.67배), TP_RR 2.0→1.2 로 **TP 절대거리 유지**(2.5×1.2 = 3.0 ≒ 1.5×2.0).
목표는 R:R 확대가 아니라 승률을 손익분기 위로 올리는 것.

**구현**
- `ExitRuleOverrides` 신설 — `strategy_params` 에서 두 배수를 읽고 범위 검증([0.5,6.0] / [1.0,5.0]).
  잘못된 값은 **그 항목만** 무시하고 기본값 사용 + 경고 로그(조용히 기본값으로 도는 A/B 는 표본을 오염시킨다).
- `ExitRuleCalculator` 에 오버라이드 오버로드 추가. 기존 3인자 시그니처는 `NONE` 위임으로 보존 — **회귀 0**.
- 세 엔진(DYNAMIC·LIVE·PAPER) 호출부에서 `session.getStrategyParams()` 전달.
- `PaperTradingController.toSessionSummaryMap` 에 `strategyParams` 노출 — 없으면 어느 세션이
  실험군인지 API 로 구분할 수 없어 대조군만 골라 복제하는 게 불가능했다.
- 테스트 `ExitRuleOverridesTest` 8건 — 무오버라이드 동등성 / 배수 반영 / SL 상한 유지 /
  **07-31 실패 패턴 재현** / TP 거리 유지 설계 / 잘못된 값 방어 / JSONB 타입 혼재.
- 전체 `:web-api:test` `:core-engine:test` 통과.

**지문**: 별도 등록 불필요 — `RulesetRegistry` 가 세 엔진 모두 `strategy.params` 를 이미 담고 있어
arm 이 자동으로 갈린다. (`exitcalc.*` 키는 계속 코드 기본값을 가리킨다.)

**실행**: `scripts/create_ab_stoploss_sessions.sh` — 대조군 M15 40세션(5전략 × 8코인)을
같은 조건으로 복제한다. M15 만 쓰는 이유는 청산 속도다(5일간 M15 122건 vs H1 27건).
arm 당 주 ~170건 → **1주면 판정 가능**(감쇠 A/B 는 arm 당 3~11건으로 1~2개월, §18).

> ⚠️ **반드시 배포 후에 실행**할 것. `ExitRuleOverrides` 가 없는 백엔드에 실험군을 만들면
> 파라미터가 무시된 채 지문만 갈려 A/B 가 아니라 오염된 표본이 된다. 스크립트가 확인을 받는다.

**판정 기준** (스크립트가 쿼리까지 출력한다)
1. 실험군 승률 > 대조군 승률 — 가설의 직접 검증
2. 실험군 평균 보유시간 > 36분 — 실제로 덜 털렸는지
3. 실험군 거래당 기대수익률 > 0 — 최종 목표

1·2만 개선되고 3이 음수면 배수가 부족한 것(→ 3.0~3.5 재실험). **1부터 개선이 없으면 가설이 틀렸고,
원인은 손절폭이 아니라 진입 선별이다**(§4 "실행된 신호가 차단된 신호보다 나쁘다" 와 연결).

### 20. ✅ H1 승률 0% 3전략 중지 스크립트 (2026-08-24) — 실행 대기

`scripts/stop_h1_zero_winrate.sh` — 고정코인 PAPER 의 **H1 세션만** 정지한다.

| H1 전략 | n | 승률 | 누적 | 조치 |
|---|---|---|---|---|
| MOMENTUM_ICHIMOKU_V2 | 7 | **0.0%** | -579,744 | 중지 |
| MTF_CONFIRMED | 6 | **0.0%** | -495,660 | 중지 |
| MOMENTUM_ICHIMOKU | 6 | **0.0%** | -495,660 | 중지 |
| MEANREV_BB / MTF_BTC | 4 / 4 | 25.0% | +361,495 / +131,355 | 유지 (n=4, 각 1승) |

**13건 연속 전패**라 우연으로 보기 어렵다(승률 25% 가정 시 13연패 확률 ≒ 2.4%).
H1 전면 중지는 n=31로 근거가 얇아 하지 않는다.

**전략 자체는 비활성화하지 않는다** — 같은 전략의 M15 세션은 흑자다.
`strategy_type_enabled` 를 끄면 M15 신규 세션까지 막힌다.

### 21. ✅ H1 승률 0% 3전략 중지 완료 (2026-08-24)

DB 확인 — 대상 3전략의 H1 RUNNING 세션이 전부 0건이 됐다.

| H1 전략 | RUNNING |
|---|---|
| MOMENTUM_ICHIMOKU / ICHIMOKU_V2 / MTF_CONFIRMED | **0** (중지 완료) |
| MEANREV_BB / MTF_BTC | 8 / 8 (유지 — 의도대로) |

### 22. 🔴 손절폭 A/B 1차 시도 실패 — `strategyParams` 조용한 유실

**증상**: 실험군 40세션(id 250~289)이 생성됐는데 `strategy_params` 가 전부 NULL.
API 는 200 을 돌려줬고 세션도 정상 생성됐다.

**원인**: `PaperTradingService.createSession` 의 빌더에서 `strategyParams` 가 **빠져 있었다.**
`LiveTradingService:370` 과 `DynamicTradingService:415` 는 처음부터 넘기고 있었는데 **PAPER 만 누락**이었다.
감쇠 A/B 가 동적 세션이라 정상 동작했고, 이 경로는 아무도 쓴 적이 없어 드러나지 않았다.

**왜 위험한가**: 실패가 조용하다. 파라미터만 사라지고 세션은 멀쩡히 돈다 —
실험군 40개가 대조군과 **완전히 같은 규칙**으로 돌았고, 지문(`strategy.params`)까지 같아져
사후에 "이건 오염된 표본"이라고 구분할 방법조차 없었다. A/B 가 실패하는 게 아니라
**틀린 결론을 준다.**

**조치**
- `PaperTradingService.createSession` 에 `.strategyParams(req.getStrategyParams())` 추가
- 회귀 가드 `PaperSessionStrategyParamsTest` — 빌더 호출 존재 확인 + 세 엔진 엔티티 필드 계약
- `create_ab_stoploss_sessions.sh` 에 **생성 직후 사후 검증** 추가.
  저장된 개수가 생성 개수와 다르면 즉시 실패하고 원인을 안내한다. 이제 조용히 지나갈 수 없다.
- `cleanup_failed_ab_sessions.sh` — 오염된 40세션 정지 (ID 하드코딩 없이
  "01:00 이후 생성 + params 없음 + M15 + RUNNING" 조건으로 선별, 원래 대조군은 제외)

**교훈**: A/B 스크립트는 "만들었다"가 아니라 **"의도한 파라미터로 돌고 있다"까지 확인**해야 한다.
§12(strictHtf 무효)·§16(BREAKOUT_ICHIMOKU) 과 같은 계열의 문제다 —
설정한 것이 실제로 거동을 바꿨는지 세지 않으면 없는 실험을 했다고 믿게 된다.

### 23. ✅ 손절폭 A/B 재구성 완료 (2026-08-24) — 관측 시작

| 구분 | 세션 ID | 건수 | strategy_params |
|---|---|---|---|
| 1차 오염분 | 250~289 | 40 | — (전부 **STOPPED**, 정리 완료) |
| **대조군** | 08-18 생성분 | **40** | NULL |
| **실험군** | **290~329** | **40** | `{"slAtrMultiplier":2.5,"tpRrMultiplier":1.2}` |

5전략 × 8코인이 대조군·실험군 **8:8 로 정확히 짝지어져** 있다(MEANREV_BB · MOMENTUM_ICHIMOKU ·
ICHIMOKU_V2 · MTF_BTC · MTF_CONFIRMED). 같은 코인·같은 기간을 보므로 시장 국면 차이가 상쇄된다.

**아직 검증되지 않은 것 — 오버라이드가 실제로 SL/TP 를 바꾸는가**

`strategy_params` 가 DB 에 저장된 것까지는 확인했지만(§22 의 유실 재발 없음), 그 값이 런타임에서
실제로 손절폭을 바꾸는지는 **실험군이 첫 포지션을 열어야** 알 수 있다. 생성 직후라 아직 0건이다.

첫 진입이 생기면 아래로 확인할 것 — 기대값은 **실험군 SL 거리가 대조군의 약 1.67배**(1.5→2.5 ATR),
**TP 거리는 거의 동일**(2.5×1.2 = 3.0 ≒ 1.5×2.0). 대조군 실측 SL 거리가 0.426% 였으니 실험군은 ~0.71% 여야 한다.

```sql
SELECT CASE WHEN v.strategy_params IS NULL THEN '대조군' ELSE '실험군' END AS arm,
       count(*) AS n,
       round(avg(100.0*(1 - p.stop_loss_price/nullif(p.entry_price,0))),3) AS sl_dist_pct,
       round(avg(100.0*(p.take_profit_price/nullif(p.entry_price,0) - 1)),3) AS tp_dist_pct
FROM paper_trading.position p
JOIN paper_trading.virtual_balance v ON v.id = p.session_id
WHERE v.timeframe='M15' AND p.opened_at >= '2026-08-24 02:00'
GROUP BY 1;
```

**SL 거리가 두 arm 에서 같게 나오면 오버라이드가 안 먹은 것이다** — 그 경우 즉시 실험을 멈춘다.
파라미터 저장(§22)과 파라미터 적용은 별개 문제이고, 지금 확인된 건 저장까지다.

### 다음 액션

1. ✅ ~~PULLBACK_MTF 고정코인 세션 중지~~ — 완료 (§8)
2. 🔜 **손절폭 A/B 관측 중** — §23. 대조군 40 / 실험군 40(id 290~329) 가동.
   ① **첫 진입 시 SL 거리 확인**(오버라이드가 실제로 먹는지 — 아직 미검증)
   ② 1주 후 승률·보유시간·기대수익률 판정.
3. ✅ ~~MTF_BTC_STRICT 비활성화~~ — 완료 (2026-08-24 00:58, `is_active=false` · RUNNING 세션 0건 확인).
4. ✅ ~~H1 승률 0% 3전략 중지~~ — 완료 (§21, RUNNING 0건 확인).
5. 동일코인 노출상한 1 → 2 A/B — 리스크 장치라 보류(§4).
6. 감쇠 A/B(동적 56·63·68·69·70·71) — 성적과 무관하게 실험 종료까지 유지.

### 🚦 실전 투입 게이트 (시간이 아니라 지표로 판단할 것)

관찰 기간을 늘리는 것으로는 결론이 바뀌지 않는다 — 이미 700건 넘게 봤고 전부 적자다.
아래 3개를 **동시에** 만족하는 전략이 나오면 그때 소액 실전을 붙인다.

1. **승률 > 27%** (손익분기 23.5% + 마진 3.5%p) — n ≥ 100 청산 기준
2. **같은 기간 BTC 단순보유 대비 초과수익 > 0** — 상승·하락 구간을 **모두** 포함해서
3. **최대 낙폭(MDD) < 10%** — 실전 심리·서킷브레이커 여유

현재 이 기준을 통과하는 전략은 **없다**(최다 표본 흑자 전략이 MTF_BTC n=43).

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
- ~~**e2e 스위트(`@playwright/test`) 미설치**~~ ✅ **2026-08-20 완료** — 설치·수정 후 **42통과/0실패/1스킵**. 아래 08-20 섹션 참조. `Header.tsx` 등 죽은 코드는 08-20 정비에서 삭제 — 현재 **44통과/0실패/0스킵**.
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

## 🟢 2026-08-20 프런트엔드 정비 — 타입 가드 복구 · 죽은 코드 제거 · 테마 페인트

> 배포 무관(프런트 전용). 백엔드를 안 건드리므로 감쇠 A/B 표본에 영향 없음.

### 1. 🔴 `ignoreBuildErrors: true` 를 껐다 — 타입 오류 8건이 그대로 배포되고 있었다

`next.config.ts` 가 타입 검사를 통째로 무시하고 있었다. 그 그늘에서 자란 것들:

| 위치 | 내용 | 처리 |
|---|---|---|
| `components/ui/Badge.tsx` | `BadgeVariant` **26종 중 13종만** `variantClasses` 에 존재. `PENDING`·`FILLED`·`UP`·`LONG`·`MARKET` 등은 `undefined` → 색 없는 배지 | 파일 자체가 미사용이라 **삭제** |
| `hooks/useBacktest.ts` | `useMutation` 에 **존재하지 않는 `select` 옵션** 을 넘김 → 조용히 무시됨 | 훅 미사용이라 **삭제** |
| `admin/news-sources` | `ApiResponse` 에 없는 `.message` 접근 → 항상 `undefined` | `res.data?.['message']` 로 수정 |
| `account`·`backtest/walk-forward` | Recharts `formatter` 파라미터가 `number | undefined` | 시그니처 수정 |
| `admin/llm-config` | `unknown && JSX` → ReactNode 아님 | `Boolean(...)` 로 감쌈 |
| `settings/upbit-status` | `UpbitHolding[]` 을 `Record<string, unknown>[]` 로 캐스팅 | 캐스팅 제거, 타입 필드 직접 접근 |

**결과: 타입 오류 8 → 0.** `npm run build` 가 타입 검사를 켠 채로 통과한다(35 페이지).

### 2. 죽은 코드 제거

```
src/components/ui/{Badge,Button,Card,Spinner,index}   ← 디렉터리 통째로 참조 0
src/components/layout/Header.tsx                       ← 참조 0
hooks: useWalkForward · useToggleStrategy · useStrategyDetail
       · useCreateStrategy · useUpdateStrategy         ← 전부 참조 0
```

`ui/` 는 공용 디자인 시스템으로 만들어졌으나 **34개 페이지가 전부 각자 스타일을 손으로 짜고 있었다.**
`upbit-status` 는 같은 이름의 로컬 `Badge` 를 따로 정의해 쓴다. `useWalkForward` 도
해당 페이지가 `backtestApi.walkForward` 를 직접 호출하고 있어 잉여였다.

> 되살릴 일이 생기면 git 이력에서 꺼내면 된다. 지금 남겨두면 "있는 줄 알고 쓰다가
> 스타일 없는 배지를 만나는" 함정으로 남는다.

### 3. 테마 첫 페인트 — 다크 사용자가 보던 흰 깜빡임

SSR HTML 의 `<html>` 에는 `dark` 클래스가 없다. `body` 는 `bg-slate-50 dark:bg-slate-950` 이라
**React 가 마운트되기 전까지 흰 배경이 칠해진다.** 기본 테마가 dark 이므로 대부분의 사용자가 겪는다.

- `app/layout.tsx` 에 **블로킹 인라인 스크립트** 추가 — 파싱 중에 `localStorage` 를 읽어
  `<html>` 클래스를 확정한다.
- `ThemeProvider` 는 `useState` 초기화 함수에서 값을 읽도록 변경(렌더 1회 절약 +
  `react-hooks/set-state-in-effect` 해소).

**e2e 2건 신규 + 뮤테이션 검증:**

| 테스트 | 뮤테이션 | 결과 |
|---|---|---|
| 다크는 하이드레이션 **전에** 적용돼 있다 | 블로킹 스크립트 제거 | ✅ **실패함**(회귀를 잡는다) |
| 라이트 재방문 시 dark 가 한 순간도 안 보인다 | ThemeProvider 를 옛 effect 방식으로 되돌림 | ⚠️ **통과함**(아래) |

> ⚠️ **정정**: 처음에는 "라이트 사용자가 다크 플래시를 본다" 고 진단했으나 **React 19 에서는
> 재현되지 않는다.** effect 안 `setTheme` 이 뒤 effect 의 클래스 적용보다 먼저 반영된다.
> `ThemeProvider` 변경의 근거는 깜빡임이 아니라 **불필요한 렌더 1회 + lint 규칙** 이다.
> 실재하는 깜빡임은 **다크 사용자의 흰 화면** 쪽이고, 그건 블로킹 스크립트가 고쳤다.

### 검증

- e2e **44 통과 / 0 실패 / 0 스킵** (Header 삭제로 스킵 1건도 소멸)
- `tsc --noEmit` **0 오류**, `npm run build` 타입 검사 켠 채 통과
- lint 109 → **108**(선행 문제, `no-explicit-any` 86건이 대부분 — 별건)

### 검토에서 나온 미처리 항목

- 🟡 **MSW GET 목 14/61** — 누락 46개(`admin` 11 · `settings` 9 · `backtest` 7 ·
  `paper-trading` 6 · `logs` 4 …). mock 모드에서 해당 화면이 빈 상태라 **e2e 확장의 천장**이다.
  `/strategies` 와 같은 상태의 화면이 아직 많다.
- 🟡 **e2e 라우트 6/34**, 프런트 단위 테스트 **0건**.
- 🟢 lint `no-explicit-any` 86건 — 범위가 넓고 회귀 위험이 있어 별도 작업.

---

## 🟢 2026-08-20 e2e 스위트 가동 (`@playwright/test`) — 보류 항목 해소

> **배포 무관**: devDependency + 로컬 실행. 백엔드를 안 건드리므로 지문이 안 갈리고
> A/B 표본에도 영향이 없다. 감쇠 A/B 관찰 기간 중에 안전하게 할 수 있는 작업이라 먼저 처리.

`@playwright/test ^1.62.1` 설치 + chromium 다운로드. `playwright.config.ts`·`e2e/*` 는
이미 작성돼 있었고 **실행기만 없어 한 번도 안 돌아본 상태**였다.

```
npm run test:e2e      # 42 tests
```

### 결과

| | 통과 | 실패 | 스킵 | 소요 |
|---|---|---|---|---|
| 최초 실행 | 32 | **7** | 3 | 1.1분 |
| 수정 후 | **38** | **0** | 4 | 16.5초 |

### 실패 7건의 정체 — **제품 버그는 0건**

한 번도 실행된 적 없는 테스트라 애초에 통과 불가능한 셀렉터가 섞여 있었다.

| # | 테스트 | 원인 | 조치 |
|---|---|---|---|
| 1 | `/backtest` 타이틀 | `getByText('백테스트 이력')` 이 **4개** 매칭(사이드바 링크·모바일 앱바·라우트 어나운서·h1) → strict mode 위반 | `getByRole('heading')` 로 축소 |
| 2 | 새 백테스트 버튼 | 같은 이름 링크가 사이드바·본문 **2개** | `getByRole('main')` 으로 스코프 |
| 3 | 타임프레임 select | `locator('select').nth(2)` — `BacktestForm` 의 `<select>` 는 **1개뿐**(코인·전략은 커스텀 컴포넌트) | `option[value="H1"]` 보유 select 로 특정 |
| 4 | `/backtest/compare` 타이틀 | Next 의 `#__next-route-announcer__` 에도 같은 문구가 들어가 **2개** 매칭 | `getByRole('heading')` |
| 5 | `/strategies` 타이틀 | 1번과 동일(**4개** 매칭) | `getByRole('heading')` |
| 6 | Sidebar 펼치기 | **`<nextjs-portal>`(Next dev 오버레이)가 좌하단 고정으로 떠서 접힌 사이드바(w-16) 하단 버튼의 클릭을 가로챔.** 운영 빌드엔 없는 개발 전용 요소 | `addInitScript` 로 e2e 에서만 `display:none` |
| 7 | Header 테마 토글 | **`src/components/layout/Header.tsx` 가 어디에서도 import 되지 않는 미사용 컴포넌트** — 존재하지 않는 UI를 검증하던 테스트 | `test.skip()` + 사유 주석 |

### 새로 드러난 것

- 🟡 **`Header.tsx` 는 죽은 코드다.** `grep -rn "layout/Header\|<Header" src` 결과 0건.
  붙일지 지울지 결정 필요 — 지우면 `theme.spec.ts` 의 skip 도 함께 삭제.
- ✅ **`GET /api/v1/strategies` 목 부재 → 같은 날 해소**(아래 후속 섹션).
- 🟡 ~~**`GET /api/v1/strategies` 목이 없다.**~~ `src/mocks/handlers.ts` 에는 POST/PUT/PATCH 가
  "실서버 직접 연결" 이라는 주석만 있고 목록 조회 핸들러가 없다. 그래서 mock 모드에서
  전략 카드가 0개 → `strategies.spec.ts` 의 **3건이 조용히 self-skip**, 1건은
  "빈 상태 메시지" 로 **공허하게 통과**한다.
  > **08-06 MSW 사고와 같은 실패 양식이다** — 화면이 비어도 검증이 통과한다.
  > 목 핸들러를 추가해야 이 4건이 실제로 뭔가를 검증하게 된다.
- `.gitignore` 에 `test-results/`·`playwright-report/` 추가(`e2e/.auth/` 는 이미 있었음).

### 후속 — MSW 전략 목록 목 추가 + self-skip 제거

| | 통과 | 실패 | 스킵 |
|---|---|---|---|
| 앞 단계 | 38 | 0 | 4 |
| **후속 후** | **42** | **0** | **1** |

- **원인**: `strategyInfosMock` 은 `src/mocks/data.ts` 에 **있었는데** ① 핸들러가 없어 아무도
  안 쓰고 ② `isActive`·`isComposite` 가 타입에 추가된 뒤로 목만 낡아 있었다.
- `handlers.ts` 에 `GET /strategies`, `GET /strategies/:name`,
  `PATCH /strategies/:name/active` 3종 추가. 목록을 뺄 때 GET 까지 같이 빠졌던 경위를
  주석으로 박아 재발을 막았다.
- `strategyInfosMock` 에 `: StrategyInfo[]` 타입 명시 — **백엔드 스키마가 바뀌면 목이
  조용히 낡는 대신 컴파일에서 걸린다.** 복합 전략 3종도 추가해 "복합 전략" 탭을 채웠다.
- `strategies.spec.ts` 의 `if (cardCount > 0) ... else test.skip()` **3곳 제거** →
  `expect(...).not.toHaveCount(0)` 로 전환. "빈 상태 메시지도 통과" 였던 1건도 하드 단언으로
  바꿨다. 탭 전환 테스트 1건 신규 추가.
  > **목이 죽으면 초록불이 아니라 빨간불이 떠야 한다.** 08-06 사고의 교훈을 테스트에 박은 것.
- `npx tsc --noEmit`: `src/mocks` 오류 **0건**(저장소 전체 선행 오류 8건은 무관).

> 남은 스킵 1건은 `Header.tsx` 죽은 코드 건 — 붙일지 지울지 **결정 대기**.

### 참고 — 도커 빌드 영향 없음

`crypto-trader-frontend/Dockerfile` 이 `rm -f playwright.config.ts && npm run build` 로
이미 대비돼 있고, 런타임 스테이지는 `.next/standalone` 만 복사하므로 **운영 이미지에
playwright 가 안 들어간다.** chromium 바이너리도 `npx playwright install` 로 따로 받는 것이라
도커 빌드에는 포함되지 않는다.

> `npm run lint` 는 기존 문제 109건(89 에러)이 있으나 **e2e 디렉터리 기여분은 0건**이며
> 이번 작업과 무관한 선행 상태다.

---

## ✅ 2026-08-20 오전 점검 결과 (09:05 KST 실측)

> 운영 DB(`yhpapa.iptime.org:8432`) 직접 조회. 어제 계획한 4개 항목 전부 확인.

### 1. 09:00 kill criteria 첫 판정 — 🟢 예상대로 전부 KEEP

`kill_criteria_judgment` **0행**(09:02 조회). `persist()` 는 KEEP 이 아닌 것만 저장하므로
**빈 테이블 = 전 세션 KEEP** 이다. 지문 도입으로 `tradeCount` 가 전 세션 0 으로 리셋됐는데도
`NO_SIGNAL` WARN 이 하나도 안 떴다 → **`tradeCountAllRulesets` 수정이 의도대로 먹었다.**

- 스케줄러 생존 근거: `daily_health_snapshot` id **15** 가 오늘 08:30 에 정상 기록됨.
- ⚠️ **한계**: 0행만으로는 "전부 KEEP" 과 "09:00 잡이 아예 안 돎" 을 구별할 수 없다.
  확정하려면 백엔드 로그의 `[KillCriteria] 폐기 기준 판정 시작` 한 줄을 봐야 하는데,
  운영은 DB 포트만 외부 개방돼 있어 **이 작업 환경에서는 확인 불가**. 08:30 잡이 돈 것이
  간접 증거일 뿐이다. 서버 접속 기회가 있으면 로그로 한 번 확정할 것.

### 2. 지문 갱신 — 🟢 정상

DYN_PAPER **59키** / PAPER **56키**, `dampen.*`·`composite.*` 전부 포함. 6개 팔 해시가
08-19 16:04~16:12 에 서로 다르게 등록된 상태 그대로 유지.

### 3. `exit_reason` — 🟢 100% 기록, 그리고 **익절이 돌기 시작했다**

08-19 15:02 이후 청산 **13건 중 NULL 0 · UNKNOWN 0**. 사유를 안 넘기는 경로는 없다.

| session_kind | exit_reason | 건수 |
|---|---|---|
| DYN_PAPER | STOP_LOSS | 5 |
| DYN_PAPER | STRATEGY_SIGNAL | 5 |
| DYN_PAPER | **TAKE_PROFIT** | **3** |

> 🔄 **P1-b 재검토 필요**: 전체 이력 SELL 6,944건 중 `TAKE_PROFIT` 이 **1건**이었는데,
> 어제 오후 이후로는 13건 중 3건(23%)이 익절이다. "익절이 사실상 작동하지 않는다" 는
> 진단이 흔들린다 — 옛 수치가 **자유 텍스트 파싱 기반이라 익절을 놓치고 있었을** 가능성.
> 단 n=13 이라 결론은 이르다. **`TP_RR_MULTIPLIER` 는 건드리지 말고 며칠 더 볼 것.**

### 4. 감쇠 A/B — 1단계 진행 중 (16시간 경과, 판정 불가)

6개 팔 전부 RUNNING, `strategy_params` 의도대로 적재. 동적 세션 18 RUNNING / 53 DELETED.

| 세션 | TF | 팔 | 진입 | 청산 | 실현손익 |
|---|---|---|---|---|---|
| 56 | H1 | 대조군 | 0 | 0 | 0 |
| 68 | H1 | EMA off | 1 | 1 | −252 |
| 70 | H1 | TRANS off | 0 | 0 | 0 |
| 63 | M15 | 대조군 | 2 | 2 | +371 |
| 69 | M15 | EMA off | **3** | 2 | −466 |
| 71 | M15 | TRANS off | 2 | 2 | +695 |

- **H1 은 표본이 사실상 없다**(3팔 합쳐 1건). H1 결론은 훨씬 오래 걸린다.
- M15 에서 EMA off 가 진입을 늘리는 방향(3 vs 2)이 보이나 **n=3, 노이즈와 구분 불가**.
- ⛔ **손익 열은 아직 보지 말 것.** 2단계는 수 주짜리다. 지금 숫자로 판단하면 오독한다.
- 세션 69 `available_krw` 1,907원 — 포지션 보유 중이라 자본이 코인에 묶인 것(정상).

### 부수 관찰

- `daily_health_snapshot`: 잔고 불일치 0 · 유령 포지션 0 · 고착 포지션 0.
  `order_sequence_gap` 은 **16(08-17) → 7(08-18) → 1(08-19)** 로 감소 중.

### 다음 확인 시점

- [ ] **08-21~22**: A/B 1단계 판정 (진입 수 차이). M15 우선, H1 은 표본 부족으로 보류.
- [ ] **매일 09:00**: `kill_criteria_judgment` 신규 행 — 지금부터는 행이 생기면 그게 신호다.
- [ ] **수 주 뒤**: A/B 2단계(실현손익·`exit_reason` 분포). 그 전에는 감쇠 기본값 변경 금지.
- [ ] 백엔드 로그에 rate limit 이 뜨는지 (동적 18세션 ≈ 594/분 추정, 한도 420).

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
