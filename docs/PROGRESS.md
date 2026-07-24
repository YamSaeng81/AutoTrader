# CryptoAutoTrader — PROGRESS.md

> **목적**: `/clear` 후 새 세션에서 이 파일을 먼저 읽어 현재 상태를 파악한다.
> **갱신 규칙**: 작업이 끝나면 완료 내용을 [`docs/CHANGELOG.md`](CHANGELOG.md)에 추가하고, 이 파일의 해당 항목은 삭제한다.
> **변경 이력**: [`docs/CHANGELOG.md`](CHANGELOG.md)
> **마지막 갱신**: 2026-07-24 (동적 워치리스트 품질 큐레이션 — 유니버스 근본원인, 미커밋/미배포)

---

## 🆕 2026-07-24 동적 워치리스트 품질 큐레이션 (무거래 근본원인 — 유니버스 측)

> 운영 DB 재분석(07-24): 동적 세션 여전히 실체결 0건. BUY 신호 86건 전량 진입 게이트 차단 — **BLACK_SWAN_GUARD 79%·EMA200 게이트 17%**. 원인 분해: ① 대형주(BTC/ETH/XRP/SOL)는 BUY 신호를 **한 번도** 안 냄(HOLD/SELL만) ② BUY 신호는 전부 급락 중인 소형·신규상장 잡코인(NEO/BIRB/SOPH/ZKC/BONK…)에서 발생 → "거래대금 상위" 원시 유니버스가 펌프-덤프 잡코인으로 채워져 **신호 발생 조건(급락 후 이격)과 급락 가드가 구조적으로 상쇄**. 이전 세션들이 손댄 스코어 모델(07-23 conf 재스케일)·게이트 티어링(07-21)과 **직교하는 유니버스 측 수정**.

- **처방 #1(진입 방향)+#2(유니버스 큐레이션)을 워치리스트 앞단 큐레이션으로 통합 구현.** 진입 게이트(사후 차단)가 아니라 유니버스 자체를 걸러 상쇄 구조를 해소.
- **신규 순수 게이트** [`WatchlistQualityGate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WatchlistQualityGate.java): 4기준 AND — ① 유동성(24h 거래대금 ≥ 하한) ② 변동성 상한(ATR% ≤ 상한) ③ 상승추세(종가 > EMA200, `Ema200RegimeGate` 재사용) ④ 비급락(`BlackSwanGuard.check` 미발동). BlackSwanGuard/Ema200RegimeGate와 동일한 순수 정적 클래스, 단위 테스트 7건.
- **워치리스트 통합** [`WatchlistFilterService`](../web-api/src/main/java/com/cryptoautotrader/api/service/WatchlistFilterService.java): 기존 파이프라인(거래대금상위→스프레드→ATR하한) 뒤에 품질 게이트 추가. 캔들 1회 조회(EMA200용 210개)로 ATR·EMA200·급락 공용. 기존 시그니처는 `QualityCriteria.disabled()`로 위임(하위호환).
- **설정화** [`risk_config`](../web-api/src/main/resources/db/migration/V57__add_watchlist_quality_to_risk_config.sql) V57: `scan_min_trade_value_krw`(기본 50억)·`scan_max_atr_pct`(기본 4.0)·`scan_require_uptrend`(기본 true)·`scan_exclude_crashing`(기본 true). NULL이면 `DynamicTradingService` 코드 기본값. 재빌드 없이 SQL/API로 조정 가능(V56 패턴).
- **효과 예측**: 유니버스가 상승추세·정상변동성 대형/중형주로 좁혀져 dip 전략은 "상승추세 눌림목", 모멘텀 전략은 트렌드 코인을 잡음 → 급락 가드와의 상쇄 급감 → 진입 발생. 유니버스가 비면 기존 "워치리스트 empty → 틱 스킵"이 처리.
- **테스트**: `WatchlistQualityGateTest` 7건 + `:web-api:test` 전체 통과, `:core-engine`·`:web-api` 컴파일 통과 ✅. **코드는 미커밋/미배포.**
- **[ ] 배포 필요** — 미배포 시 구 유니버스(잡코인 포함) 유지. 배포 후에야 큐레이션 발효.
- **[ ] 소액 실전 관찰** — 배포 후 워치리스트 구성 코인 변화 / 일일 BUY 실집행수 / 진입 코인 / 4h·24h 사후수익률 추적. 유니버스가 과도하게 비면 `scan_min_trade_value_krw` 하향 또는 `scan_max_atr_pct` 상향(SQL 1줄). 여전히 무거래면 `scan_require_uptrend=false`로 완화 실험.
- **[ ] 배포 검증 병행** — 07-23 conf 재스케일·07-21 게이트 티어링도 미배포 상태. 운영 로그의 게이트 사유 wording으로 실제 배포 버전 확인 필요(entryGate 3단계 vs 구 check 2단계).

---

## 🆕 2026-07-23 VOLUME_DELTA·BOLLINGER confidence 재스케일 (동적 세션 무거래 근본원인 수정)

> 운영 DB 재분석(07-23): 동적 세션 7개가 여전히 실체결 0건. 퍼널 정량화 결과 **HOLD의 79.6%가 "점수 미달 buy/sell=0"** — 게이트가 아니라 **스코어가 임계(0.20)를 못 넘는 것**이 병목. 서브전략 신호율 집계로 원인 특정: ① 가중 0.5 앵커 **MACD 신호율 0.6%**(크로스 순간만 투표하는 이벤트형 → 연속형 스코어 모델과 불일치), ② **VOLUME_DELTA(conf 15.2)·BOLLINGER(conf 14.5)** 의 strength 정규화가 도달 불가능한 극값(비율 1.0 / %B −0.8) 기준이라 발화해도 스코어 기여 0.05~0.08의 "무의미 표". MEANREV_BB는 BOLLINGER+RSI 동시 발화해도 0.08+0.09=0.17 < 0.20이라 수학적으로 진입 불가였음.

- **이번 범위 = ②만 수정 (가장 레버리지 크고 되돌리기 쉬운 것). ①(MACD 로직 변경)은 휩쏘 위험 커 보류.**
- **VOLUME_DELTA** [`VolumeDeltaStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaStrategy.java): strength를 도달 가능한 포화점 `strengthSaturationRatio`(기본 **0.40**) 기준으로 재정규화. `(ratio−threshold)/(saturation−threshold)×100`. 포화점≤임계 시 구 동작(1.0 기준)으로 안전 폴백. 비율 0.25 → conf 5.5 → **50**으로 상승.
- **BOLLINGER** [`BollingerStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerStrategy.java): `strengthSaturationDepth`(기본 **0.35**) 도입. `(buyThreshold−%B)/depth×100`. 하단 밴드 터치(%B≈0)가 conf 20 → **약 57**로 상승. SELL 대칭.
- 두 파라미터는 [`VolumeDeltaConfig`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/volumedelta/VolumeDeltaConfig.java)·[`BollingerConfig`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/bollinger/BollingerConfig.java)에 노출(params override 가능).
- **효과 예측**: MEANREV_BB의 BOLLINGER+RSI 합의가 0.20 돌파(진입 가능화), 추세추종 프리셋의 VD 확인표도 유효화. **scan_weak_threshold(0.20)는 그대로 — 재스케일만으로 2전략 합의가 임계를 넘어 임계 완화 불필요.**
- **테스트**: `VolumeDeltaStrategyTest`·`BollingerStrategyTest`에 포화점 단조성 락 테스트 각 1건 추가. `:strategy-lib:test`+`:core-engine:test` 통과, `:web-api` 컴파일 통과 ✅. **코드는 미커밋/미배포.**
- **[ ] 배포 필요** — 미배포 시 구 저conf 로직 유지. 배포 후에야 동적 세션 진입 발생.
- **[ ] 소액 실전 관찰** — 배포 후 일일 BUY 실집행수 / 진입 코인 / 4h·24h 사후수익률 추적. 진입이 과하거나(노이즈) 순손실이면 포화점 상향(0.40→0.55 / 0.35→0.50)으로 보수화, 되돌리기는 params 1줄.
- **[ ] 잔여 근본원인 ①(MACD 앵커 0.6%)** — 미해결. 추세 지속 투표 모드 or 내부 adxThreshold 25→20은 별도 작업(휩쏘 검증 필요).

---

## 🆕 2026-07-21 EMA200 게이트: 하드 차단 → 사이즈 감액 진입 (동적 세션, "너무 보수적이지 않은 거래")

> 운영 DB 재분석(07-21): 동적 세션 7개 12일간 실체결 0건 — BUY 신호는 나오나 EMA200 게이트(마진 3%)가 전량 하드 차단. 07-20 결정("게이트 완화 대신 직교 전략 추가")과 달리, 사용자가 명시적으로 "덜 보수적인 거래 + 소액 데이터 확보"를 요청. **전량 제거가 아니라 리스크를 사이즈로 통제하는 감액 진입**으로 절충 — 트레이더 원칙 "나쁜 레짐에선 끊지 말고 줄여라".

- **게이트 3단계화**: [`Ema200RegimeGate.buySizeMultiplier`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/Ema200RegimeGate.java)(신규) — 기존 마진 하나(`scan_ema200_buy_margin_pct`)로 밴드 파생. 종가 > EMA200×(1-margin%) → **1.0(정상)**, EMA200×(1-margin%)~(1-2·margin%) → **0.5(감액)**, 그 아래 딥 하락 → **0.0(차단, 나이프 캐칭 방어)**. 기존 `allowsBuy`(하드 차단) 대비 **단조 완화** — margin~2·margin 구간만 차단→감액으로 바뀜.
- **적용 범위 = 동적 스캐닝만**: [`DynamicTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java) 게이트→`BuyCandidate`→`executeBuy` 경로에 `sizeMultiplier` 전달, `investAmount = availableKrw × investRatio × multiplier`. **라이브([`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java):918)·백테스트([`BacktestEngine`](../core-engine/src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java):227)는 기존 `allowsBuy` 유지 — 실돈 블래스트 반경 최소화.** MEANREV_BB는 여전히 게이트 면제(변화 없음).
- **최소주문 보정**: 자본 1만원·investRatio 0.8 세션은 감액(0.5) 시 4,000원 < 업비트 최소주문 5,000원이라 "가용 KRW 부족"으로 헛차단됨. 정상 사이즈가 최소주문을 넘는 한 감액분을 **5,000원으로 올려** 진입을 살림.
- **테스트**: `Ema200RegimeGateTest`(사이즈 티어 4건: 정상/감액/딥차단/캔들부족 추가) + `DynamicScanSelectionTest`(레코드 시그니처 갱신). `:core-engine:test`+`:web-api:test` 컴파일·통과 ✅. **코드는 미커밋/미배포.**
- **[ ] 배포 필요** — 미배포 상태에선 구 하드 차단 로직이 계속 작동. 배포 후에야 감액 진입 발생.
- **[ ] 선택적 설정 다이얼**: 더 공격적으로 원하면 `risk_config.scan_ema200_buy_margin_pct`(현재 NULL=기본 3.0)를 4.0~5.0으로 상향 → 감액 밴드 확대. `PUT /api/v1/trading/risk/config` 또는 SQL로 재배포 없이 조정, 되돌리기도 1줄.
- **[ ] 2~4주 후 재평가** — 감액 진입분의 4h/24h 사후수익률로 EV 확인. 감액 진입이 순손실이면 밴드 축소 또는 회수. (07-20 note의 "차단이 사후평가상 옳았다"와 상충 가능 — 실측으로 판정.)
- **[ ] 미해결(이번 범위 밖, 별도 판단 필요)**: ① 세션 33(COMPOSITE_PULLBACK_MTF)이 보유 포지션 없이 SELL 543건 남발(전량 정상 차단이나 로직 스멜) ② 라이브 세션 188·190(KRW-XRP)이 13일 신호 0건 — 좀비 세션 정리 검토.

---

## 🆕 2026-07-20 신호품질 분석 후속 — 야간/TRANSITIONAL 감쇠 + 차단사유 그룹핑 버그 수정

> 신호품질 페이지(30일·전 세션) 직접 DB 분석 결과 3건 반영. **표본의 88%(9,070/10,295)가 동적 세션의 "SCANNING — 청산 대상 아님" SELL 로깅 노이즈**라 완전한 실전 인과 검증은 아직 아님 — 그래서 하드 차단이 아닌 EMA필터와 동일한 점수 감쇠 방식으로 반영(사용자 확인 후 결정).

- **차단사유 그룹핑 버그 수정** — [`BlockedReasonNormalizer`](../web-api/src/main/java/com/cryptoautotrader/api/report/BlockedReasonNormalizer.java)(신규) + [`LogController.buildBlockedVsExecuted`](../web-api/src/main/java/com/cryptoautotrader/api/controller/LogController.java). `BLACK_SWAN_GUARD 발동 — 1시간 내 급락 -6.80%(현재 6.72)`처럼 급락률·현재가가 본문에 그대로 박힌 사유는 콜론이 없어 기존 `split(":")[0]` 그룹핑이 전혀 안 먹혀, 화면의 "차단 사유별" 표가 거의 전부 1건짜리 행으로 수십 줄 늘어졌던 원인. 괄호 제거 + %/배 수치 제거로 정규화.
- **야간(KST 20~23시) + TRANSITIONAL 레짐 신호 감쇠** — [`SignalQualityDampenGate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/SignalQualityDampenGate.java)(신규) + [`CompositeStrategy.evaluate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java) 통합.
  - 근거(30일): KST 20~23시 4h 승률 31~38%·평균 -0.25~-0.59% (06~09시는 승률 56~62%·평균 +0.36~+0.58%). TRANSITIONAL 레짐 24h 승률 17.4%·평균 -1.21% (TREND +1.05%·RANGE +0.05% 대비 최악).
  - 기존 EMA 방향 필터(`emaFilterDampenFactor`)와 동일한 패턴 — buy/sellScore를 threshold 비교 **전에** 비례 감쇠(기본 야간 0.6배·TRANSITIONAL 0.5배)시켜 강신호는 통과 여지를 남김. `nightDampenFactor`/`transitionalDampenFactor` params로 override 가능(1.0=무감쇠). 모든 `CompositeStrategy` 기반 프리셋(동적·라이브 공용)에 자동 적용 — 별도 옵트인 불필요.
  - TRANSITIONAL 감지는 `RangeRegimeGate`와 동일하게 `MarketRegimeDetector.detectRaw`(stateless) 재사용, 캔들 50개 미만이면 스킵(무감쇠).
- **테스트**: `SignalQualityDampenGateTest`(6, 시간 경계·레짐 분기) + `BlockedReasonNormalizerTest`(6, 그룹핑 키 정규화) + `CompositeStrategyTest`(야간 감쇠 통합 3건 추가). `:core-engine:test`+`:web-api:test` 전체 통과 ✅. **코드는 미커밋/미배포.**
- [ ] 배포 후 2~4주 뒤 신호품질 재분석 — 동적 세션 SCANNING 노이즈를 제외한 표본으로 야간/TRANSITIONAL 패턴이 재현되는지, 감쇠 계수(0.6/0.5)가 적정한지 재검증.
- [ ] 필요 시 계수를 risk_config로 이관해 재배포 없이 튜닝 가능하게 (현재는 코드 상수 + params override만 지원).

---

## 🆕 2026-07-20 COMPOSITE_MEANREV_BB 평균회귀 프리셋 추가 (하락·횡보장 표본 확보용)

> 같은 날 운영 DB 분석(아래 섹션)의 후속 조치: 동적 세션 6개가 전부 추세추종 계열이라 하락·횡보장에서 동시 침묵(11일 매수 0건). 게이트 완화 대신(사후평가상 차단이 옳았음) **직교(평균회귀) 전략 1종 추가**로 구성 빈틈을 메운다.

- **프리셋**: [`CompositePresetRegistrar`](../web-api/src/main/java/com/cryptoautotrader/api/config/CompositePresetRegistrar.java) — `COMPOSITE_MEANREV_BB` = BOLLINGER(0.55) + RSI(0.30) + VWAP(0.15), stateless.
  - BOLLINGER: %B 하단 이탈 매수 (자체 ADX '상한' 필터 + Squeeze HOLD 내장), RSI: 과매도+피봇 다이버전스, VWAP: 할인 매수.
  - **VWAP 가중 0.15는 의도된 설계** — 단독 만점(100)으로 weak 임계(0.19~0.20) 미달, BOLLINGER/RSI와 합의해야 진입 (추세추종 프리셋의 "VWAP(100) 단독 BUY 0.21~0.30" 남발 패턴 차단).
  - EMA 방향 필터 OFF(역추세 매수가 전제) / Composite ADX '하한' 필터 OFF(BOLLINGER 상한 필터와 정반대).
- **게이트 계약**: [`Ema200RegimeGate`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/Ema200RegimeGate.java)에 `isExempt()` + `EXEMPT_STRATEGIES={COMPOSITE_MEANREV_BB}` 신설 — EMA200 아래 과매도 진입이 전제인 평균회귀와 게이트가 논리 상충. 동적([`DynamicTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java))·라이브([`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java)) 양쪽 호출부에 면제 반영. RangeRegimeGate는 비차단(횡보장이 주 무대). **BLACK_SWAN·BTC_MARKET_GUARD·손실쿨다운·SL/TP는 그대로 적용** — 나이프 캐칭 방어 유지.
- **전략 목록 API 노출 수정**: [`StrategyController`](../web-api/src/main/java/com/cryptoautotrader/api/controller/StrategyController.java)의 `isStrategyImplemented` 하드코딩 목록에 없는 전략은 `SKELETON` 상태 → 프론트 동적 세션 콤보박스(`AVAILABLE && isActive` 필터)에서 안 보임. 4개 스위치(`isStrategyImplemented`/`isCompositeStrategy`/`getDescription`/`getRecommendedCoins`)에 MEANREV 추가. **신규 프리셋 추가 시 이 4곳 누락 주의** — 통합 테스트로 회귀 방지 추가.
- **테스트**: Ema200RegimeGateTest(면제 계약) + CompositeStrategyTest(가중 불변식 2건: VWAP 단독 미달 / BOLLINGER+RSI 합의 진입) + CompositeMeanRevPresetTest(등록·게이트·스모크 3건) + StrategyControllerIntegrationTest(AVAILABLE 노출 회귀 1건). `:strategy-lib:test`+`:core-engine:test`+`:web-api:test` 전체 통과 ✅. **코드는 미커밋/미배포.**
- [x] 배포 후 동적 세션에 COMPOSITE_MEANREV_BB 1개 생성(M15, 10,000원)해 추세추종 6개와 병행 관찰 — **07-20 09:49 KST 세션 38 가동 확인.** 첫 스캔부터 서브전략 3종 개별 투표 정상(BOLLINGER:BUY(8)/SELL(33) 등, 점수 미달 HOLD). 워치리스트 4코인(XRP/ETH/SOL/NEO)만 통과 — 시장 필터 결과로 정상이나 표본 적으면 min_atr_pct 완화 검토.
- [ ] 2주 후 신호품질(was_executed·4h/24h 사후수익률)로 평균회귀 게이트 면제가 옳았는지 재평가 — 나이프 캐칭 손실 패턴 보이면 EMA200 면제 회수 검토.

## 🆕 2026-07-20 운영 DB 멀티코인(동적) 로그 분석 — 신호 생성 회복, 실행은 게이트가 전량 차단(정당)

> 동적 세션 6개(32~37, M15) 07-09부터 RUNNING, **11일째 매수 체결 0건**, 자본 6×10,000원 그대로. 전 세션 SCANNING, 워치리스트 갱신 정상(30분 주기).

- **BUY 신호 생성은 07-15 완화 + 07-16 ①② 수정 이후 뚜렷이 회복**: 07-15 1건 → 16 4 → 17 7 → 18 12 → **19 30건**. 07-09~14는 6일 연속 0건이었음.
- **그러나 54건 전량 진입 게이트 차단 → 실행 0건**: BLACK_SWAN_GUARD 39건 + EMA200 레짐 필터 15건. (RANGE/BTC급락/쿨다운/KRW부족 차단은 0건.)
- **사후 수익률 기준 차단은 정당 (24h 방어 판정)**: 블랙스완 차단분 avg 4h **-3.96% / 24h -6.79%**, EMA200 차단분 4h +1.00% / **24h -4.22%**. 하락장에서 게이트가 자본 보호 — 완화 롤백/게이트 완화 불필요, 시장 전환 대기 유지.
- 신호 구조는 여전히 얕음: BUY 대부분 VWAP(100) 단독 0.30(EMA 감쇠 시 0.21), MACD/GRID 무투표. 07-19 30건 중 20건이 세션 34(ICHIMOKU)의 KRW-BIRB 반복(EMA200 차단 반복).
- HOLD 주 사유 변함없이 "점수 미달 buy=0.00 sell=0.00" — 3일간 HOLD 1.4만+건.
- ⚠️ **risk_config.scan_weak_threshold 현재 NULL** (updated_at 07-16 08:33 KST) — 07-16 스탑갭 0.19가 남아있지 않고 코드 기본 0.20 폴백 중. ①② 수정(`>=` 비교)은 커밋됨 — **운영 배포 여부 확인 필요** (BUY 급증 추세로 보아 반영됐을 가능성 높으나 서버에서 미확인).
- 참고: 고정코인 LIVE 세션은 정상 거래 중 (drift log: 192 BTC / 193 ADA, 슬리피지 -0.03~+0.42%).
- [ ] 서버에서 07-16 코드(①② 수정) 배포 여부 확인 — 미배포면 재빌드·재시작.
- [ ] 시장 전환(상승 추세 복귀) 후 게이트 차단률·진입 재개 여부 재점검.

## 🆕 2026-07-16 24h 운영 로그 분석 — 07-15 배포분 검증 완료

> V55/V56 마이그레이션 07-15 13:54 KST 적용 확인. 실전 4개(188/190/192/193)·동적 6개(32~37) 세션 가동 중.

- **실전 24h 거래: 익절 2건, 합계 +257.5원. 손실 0건.** 현재 오픈 포지션 없음.
  - 192(BTC): 07-14 21:25 진입 93,259,000 → 07-15 21:25 신호 청산(H4 SELL 0.30, ATR_BREAKOUT), **+165.0원 (+2.05%)**
  - 193(ADA): 07-14 21:35 진입 238 → 07-16 05:45 신호 청산(H4 SELL 0.40, ATR_BREAKOUT), **+92.5원 (+1.2%)**
- **07-15 배포분 검증 결과 (전 항목 정상)**:
  - ✅ **차단 BUY 저장 방식 전환** — VANA BUY(score 0.283)가 HOLD 덮어쓰기 없이 BUY+was_executed=f+blockedReason으로 저장, signal_price 포함. **4h 사후수익률 -1.73% 자동 평가됨 → 첫 사례부터 게이트 방어 성공 판정.**
  - ✅ **SCANNING SELL 사유 명시** — 24h 184건 전부 "SCANNING — 보유 포지션 없음(청산 대상 아님)" + signal_price 저장 (145건 4h 평가 완료). 통계 오염 종료.
  - ✅ **서킷 브레이커/손실 쿨다운(V55)** — 스키마 적용 확인. 발동 0건 (동적 손실 자체가 0건이라 정상 대기).
  - ✅ **V56 설정화** — risk_config 4컬럼 생성, 전부 NULL → 코드 기본값(0.20/0.40/0.70/3.0%) 폴백 작동.
  - ⚠️ **PAPER signal_price 저장** — 검증 불가: PAPER 세션이 07-01 이후 미가동. 다음 페이퍼 세션 가동 시 확인.
  - ✅ **DOGE EMA200 면제 제거** — 배포 후 DOGE 로그 0건(워치리스트 자체에 미포함), 부작용 없음.
- **BLACK_SWAN_GUARD 오탐 수정(07-08 배포) 잔여 관찰 종료** — 07-08 이후 발동 전건이 실제 급락(-5%~-17%) 동반. 거래량 버스트형은 07-14 VANA "16.9배 + 하락 -2.63%" AND 조건으로만 발동. **SL 강화 텔레그램 알림 0건**(보유 중 발동 사례 없음). 오탐 수정 확정.
- **동적 세션 진입 여전히 0건 (완화 후 만 1일)** — 24h 스캔 32코인/5,705로그 중 BUY 후보 1건(VANA)뿐, 그마저 블랙스완 차단(사후 -1.73%로 정당). HOLD 5,429건의 주 사유는 "점수 미달 buy=0.00"(서브전략 무투표) — 하락~전환장에서 신호 자체가 없는 상태. 완화 롤백 판단은 시장 전환 후로 유보.
- 🔴 **동적 매수 0건 정밀 퍼널 분석 (07-16) — 완화가 안 먹히는 구체 원인 2건 확정**:
  - 가동 전체(07-09~) 41,172건 평가 → buy점수>0: 4,421건 → **BUY 신호: 단 1건** → 실행 0건.
  - 완화 이후 임계(0.20) 도달 13건의 사망 경로: **① 경계값 버그 8건** — [`CompositeStrategy.finalSignal`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java)이 `buyScore > weakThreshold` **strict 비교**라 MACD 단독 BUY(100)의 정확히 0.20이 전부 "점수 미달" 처리 (ETH/HBAR/B3 반복). **② 상충 신호 규칙의 완화 역효과 4건** — weak 0.25→0.20 인하로 감쇠된 SELL 0.21이 상충 판정 범위에 들어와 **buy=0.50 STRONG_BUY 2건(B3/VIRTUAL)을 HOLD로 사살** (완화 전 기준이면 sell 0.21<0.25로 상충 아님 → STRONG_BUY 통과였음). ③ 나머지 1건(VANA)만 BUY → 블랙스완 차단(사후 -1.73%, 정당).
  - 구조 관찰: 세션 33(PULLBACK_MTF)은 buy점수>0이 **0건**(하락장에서 완전 침묵), 37(ICHIMOKU_V2)은 348건 있으나 max 0.17로 구조적 임계 미달. 6전략 전부 추세추종 계열이라 하락장 관망 자체는 정상 — 문제는 위 ①②로 "정상적으로 나온 후보"까지 죽는 것.
  - [x] **①② 수정 완료 (07-16, 상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-16)** — `CompositeStrategy.finalSignal` 임계 비교 `>`→`>=` + 상충 판정에 강도 등급 비교 추가(한쪽만 strong이면 그쪽 우세). 테스트 2건 신규, `:core-engine:test`+`:web-api:test` 전체 통과. **코드는 미커밋/미배포.** 운영엔 스탑갭으로 `scan_weak_threshold=0.19` SQL 적용됨(07-16 08:27 KST, 무재시작 즉시 효력).
  - [x] ~~배포 후 `scan_weak_threshold` NULL 원복~~ — **완료 (07-16 08:34 KST)**: 배포 재기동 확인(08:30~08:34 DYNAMIC 전 코인 일괄 재평가 버스트 = 캐시 리셋) 후 NULL 원복, SELECT로 확인. 이제 `>=` 코드 + 기본값 0.20이 유효.
  - [ ] 관찰: ①② 효과로 일 ~10건 후보가 게이트(블랙스완/EMA200/쿨다운)에 도달할 전망 — 진입 발생 여부와 품질(사후수익률) 확인.
  - [ ] (c) 하락/횡보장용 평균회귀 전략 편입은 별도 검토 (33/37은 이 장세에서 구조적 침묵).
- ⚠️ **신규 관찰: "SL 미점검 3분 초과" 경고 증가 추세** — 07-13 4건 → 07-15 8건 → 07-16 오전에만 17건 (192/193, 특히 07-16 02:17~05:29 ADA 보유 중 연속 발생). WS 티커 수신 불안정 의심. 포지션 청산엔 지장 없었으나 SL 실시간 감시 공백이므로 WS 재연결 로직/헬스체크 점검 필요.

---

## 🆕 2026-07-15 운영 DB 분석 — 동적 세션 6일째 매수 0건 근본 원인 확정 + 실전 현황

> 동적 세션 32~37(M15, 07-09 10:06 시작) **6일간 매수 0건** (DYNAMIC 주문/포지션 0, BUY 신호가 실행 경로 도달 0회).
> 07-09 완화(weak 0.25/strong 0.40 + EMA200 마진 1%)는 **배포·작동 확인됨** (SELL score 0.25~0.29 로그 존재 = 완화 임계 적용 증거).
> 완화에도 매수가 0인 이유 = **동일 방향 추세필터 4겹이 하락장(BTC 96.4M→93.2M)에서 전부 닫힘**:

- **정량 분해 (총 평가 35,456건, 6세션 × ~10코인 × 15분)**:
  - 서브전략이 BUY를 내도 CompositeStrategy **내부 EMA20<EMA50 필터가 BUY 점수 0화: 5,218건** (VWAP:BUY(100)→0.30→0.00 패턴 다수).
  - **ADX<25 횡보 차단: 1,976건** (24.0~24.9 근소 미달 다수).
  - 단독 SUPERTREND:BUY(50) → buyScore 0.15 < 0.25: **1,415건** — MACD(가중 0.4 앵커)가 전체 평가 중 BUY 147건(0.4%)만 투표해 복합점수가 구조적으로 임계 미달.
  - 필터를 뚫고 BUY로 확정된 ~323건은 **EMA200 게이트 283건(마진 1%에도) + BLACK_SWAN 40건이 전량 차단**. 매일 23~71건씩 꾸준히 차단됨.
- **부수 발견**: SCANNING 중 SELL 신호 3,391건이 was_executed=f·blocked_reason=NULL로 저장(포지션 없어 무의미) → 신호품질 통계 오염. 세션 33(PULLBACK_MTF)은 로그의 65%가 SELL(3,257 vs HOLD 1,749).
- **실전 라이브(188~193, 07-08 시작) 대조**: 거래한 세션은 대부분 손실 — 189(DOGE) 5전 5패 **-591원, 07-14 비상정지** (DOGE만 EMA200 게이트 면제라 하락장에 유일하게 계속 진입한 것이 직접 원인), 191(ETH) -208원 07-10 비상정지(기록됨), 193(ADA) -407원 + ADA 재보유 중(평가손), 192(BTC) +51원 + BTC 보유 중(평가익 ~+240). 188/190(XRP)은 7일간 거래 0 (동적과 동일 사유). **→ 동적 세션의 관망은 이번 주 하락장에선 결과적으로 손실 회피였음.**
- [x] **필터 스택 구조 결정 — 거래 빈도 확보로 2차 완화 (2026-07-15, 사용자 결정: 관망 대신 빈도↑)** — SCANNING 진입 경로 한정, 청산 경로 불변. **07-15 배포 완료.**
  - `DynamicTradingService`: `SCAN_EMA_DAMPEN_FACTOR=0.7` 신설 → SCANNING evaluate params에 `emaFilterDampenFactor` 주입 (역추세 BUY 점수 완전 소멸 → 30% 감쇠. VWAP:BUY(100) 0.30→0.21 통과 가능, 약신호 0.15→0.105는 여전히 차단).
  - `SCAN_WEAK_THRESHOLD` 0.25→0.20 (strong 0.40 유지). `EMA200_BUY_MARGIN_PCT` 1.0→3.0.
  - **ADX 필터는 의도적으로 유지** — adxThreshold 완화(15.0)가 횡보장 손실 확대로 2026-06-30 제거된 전력 존중.
  - `:web-api:compileJava` ✅, selector 테스트 + `DynamicScanSelectionTest`/`SessionKindIsolationTest` ✅.
  - [x] **07-15 배포 완료** — 재기동 후 운영 DB 로그로 적용 확인 (감쇠 로그 "BUY 0.30→0.21", score 0.21~0.50 BUY 후보 생성 시작). 새 병목: 전략 내부 Ichimoku TK/Chikou 확인 게이트·RSIVeto가 후보 차단 중(STRONG_BUY 0.50도 차단) — 전략 정체성이라 미완화, 관찰.
  - [ ] 배포 후 관찰(진행 중): 진입 발생 여부 + 진입 품질. 완화로 189/191/193 손실 패턴(하락장 역추세 진입 반복 손절) 재현 시 감쇠 0.7→0.5 또는 EMA200 마진 3%→2% 롤백 검토. **07-16 1일차: 진입 0건 — BUY 후보 1건(VANA, 블랙스완 차단·사후 -1.73% 방어 성공). 하락장에서 서브전략 무투표(buy=0.00)가 지배적, 롤백 사유 미발생.**
- [x] **동적 세션 서킷 브레이커 + 손실 쿨다운 (2026-07-15)** — 진입 완화의 안전장치 세트. **07-15 13:54 배포 완료.**
  - **공백 발견**: 연속 손실 서킷 브레이커(`RiskManagementService.checkCircuitBreaker`)가 라이브 전용이었음 — 동적 세션은 완화 후 무제한 반복 손실 가능했다. 손실 후 재진입 쿨다운도 없었음 (risk_config.cooldown_minutes는 존재하나 어디서도 미사용).
  - `RiskManagementService.checkCircuitBreaker(DynamicSessionEntity)` 오버로드 — MDD·연속손실 공통 로직 추출. 동적은 **이번 가동(startedAt) 이후 청산분만** 연속 손실 집계(재시작 시 과거 이력으로 즉시 재발동 방지).
  - **LIVE 잠복 버그 수정**: 기존 연속 손실 집계가 sessionId만으로 조회해 LIVE/DYNAMIC id 충돌 시 교차 오염 가능 → kind-aware 쿼리(`findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc`)로 교체.
  - `DynamicTradingService.processTick`: CB 발동 시 `circuit_breaker_triggered_at/reason` 기록(V55 + entity) + `emergencyStop` + 텔레그램 🚨 알림.
  - `processScanningTick`: **손실 청산 쿨다운** — 직전 청산이 손실이면 `cooldown_minutes`(기본 60분) 동안 동일 코인 재진입 차단 (191 반복 손절 패턴 방지). 진단 카운터 `손실쿨다운차단` 추가.
  - 테스트: `DynamicCircuitBreakerTest` 4건 신규 (5연속 손실 발동 / 재시작 리셋 / LIVE-DYNAMIC kind 격리 / MDD 발동). `:web-api:test` 전체 통과.
  - [x] ~~배포 필요~~ — **07-15 13:54 KST 배포 완료 (V55 적용 확인). 07-16 기준 CB/쿨다운 발동 0건 (동적 손실 0건이라 정상 대기).**
- [x] **신호품질 사후수익률 측정 공백 수리 + SCANNING SELL 로그 정리 (2026-07-15, P1-2/P1-3)** — **07-15 13:54 배포 완료.**
  - **진단 (운영 DB)**: 백필 스케줄러(`SignalQualityService`) 자체는 정상 가동 중(미평가 잔량 11건, 전부 최신). "거의 null"의 실체는 ① PAPER 경로가 signal_price를 아예 저장 안 함(24,820건 전량 NULL — 평가 쿼리에서 영구 제외), ② 4/10 이전 LIVE 10,597건 NULL(과거분), ③ **게이트 차단 BUY가 HOLD로 덮여 저장**되어 BUY/SELL만 평가하는 백필 대상에서 원천 제외 — 차단의 방어/기회비용을 측정할 수 없던 구조.
  - `PaperTradingService`: 전략 로그에 signalPrice(평가 캔들 종가) 저장 추가.
  - `DynamicTradingService` SCANNING: 게이트(EMA200/RANGE/블랙스완/BTC가드/손실쿨다운) 차단 시 signal을 HOLD로 덮지 않고 **BUY 그대로 저장 + wasExecuted=false + blockedReason=게이트 사유** — 이후 4h/24h 사후수익률이 자동 평가되어 "차단된 BUY가 실제로 올랐나(기회비용) 떨어졌나(방어)"를 신호품질 통계로 판정 가능. 실행 후보 선정 로직은 불변(차단 BUY는 후보 제외). ※ 전략로그 화면에서 차단 BUY가 이제 HOLD가 아닌 BUY(미실행+사유)로 보임 — 리포트/신호통계의 BUY 집계도 이에 맞게 증가.
  - SCANNING 중 SELL 신호(보유 없음, 3,391건 오염 원인)에 blockedReason="SCANNING — 보유 포지션 없음" 명시 (P1-3).
  - `SignalQualityService.evaluateLoop`: 실패 행(상장폐지 코인 등)이 정렬 헤드에 쌓이면 같은 배치를 무한 재조회하고 뒤의 정상 행이 영영 평가 안 되던 잠복 버그 → 실패 존재 시 페이지 전진으로 수정.
  - `:web-api:test` 전체 통과. 라이브(`LiveTradingService`) 경로의 게이트 차단 BUY도 같은 방식(HOLD 덮어쓰기)이나, 실돈 매수 실행 분기와 얽혀 있어 이번 범위에서 제외 — 후속 검토.
  - [ ] (선택) 과거 NULL signal_price 1회성 백필 — LIVE 4/10 이전 1.1만 건은 신호 시각 캔들 종가로 보정 가능하나 분석 가치 낮아 보류.
- [x] **P2 일괄 처리 (2026-07-15)** — **07-15 13:54 배포 완료.**
  - **DOGE EMA200 게이트 면제 제거** (`Ema200RegimeGate`) — 면제 근거("EMA200 아래 수익 패턴" PoC)가 실전 반증됨: 세션 189(DOGE)가 하락장에서 유일하게 면제 덕에 계속 진입, 5연속 손절 -591원(-5.9%) 후 07-14 비상 정지. 라이브·백테스트·동적 모든 경로에 적용(단일 진실 소스). `Ema200RegimeGateTest` 계약 반전.
  - **SCANNING 진입 완화 파라미터 설정화** (V56) — `risk_config`에 scan_weak_threshold / scan_strong_threshold / scan_ema_dampen_factor / scan_ema200_buy_margin_pct 추가. NULL이면 코드 기본값(0.20/0.40/0.70/3.0%) 폴백. `PUT /api/v1/trading/risk/config` 또는 SQL로 재빌드 없이 조정. 손실 쿨다운(cooldown_minutes)도 같은 조회로 일원화.
  - PROGRESS 잔무: 포지션 1232 SL 원복 항목 폐기(이미 CLOSED 확인).
  - [x] **라이브 188/190(XRP, 7일 거래 0) 처리 결정** — 사용자 결정(07-15): **유지(관망)**. 비용 0, 상승 전환 시 자연 진입 기대. 라이브 경로 완화는 미적용.
- [x] ~~DOGE EMA200 게이트 면제 재검토~~ — P2에서 면제 제거로 완료, 07-15 배포됨.
- [x] ~~SCANNING SELL 로그 정리~~ — P1-3에서 완료, 07-15 배포·작동 확인(07-16).

---

## 🆕 2026-07-10 운영 DB 로그 분석 — 세션 191 비상정지 (5연속 손절 서킷브레이커)

> 실전 세션 188~193(07-08 10:21 KST 시작, 각 10,000원) 중 **191(ETH, COMPOSITE_PULLBACK_MTF)이
> 07-10 08:16 KST EMERGENCY_STOPPED** — 5거래 전부 손절(-40.7/-49.7/-52.5/-31.1/-34.2원, 합 -208원, -2.08%),
> `consecutive_loss_limit=5` 서킷브레이커 정상 발동. 패턴: ETH 261만 원 부근 횡보(TRANSITIONAL)에서
> "눌림목 회복 BUY"(RSI 51~54, ADX 22 내외, H4:상승) 반복 진입 → ATR 기반 SL이 진입가 -0.4~-0.6%로 매우
> 타이트해 30분~2.5h 내 실시간 손절(WS) 반복. 쿨다운(60분)은 지켜졌으나 같은 가격대 재진입을 막지 못함.

- [ ] **세션 191 재기동 여부 결정** — 재기동 전 검토: ① 횡보(ADX<25)에서 눌림목 진입 차단 또는 ADX 임계 상향,
  ② SL 하한(예: 진입가 -1% 미만으로 조여지지 않게 클램프 — BLACK_SWAN_GUARD의 ATR 클램프와 동일 발상),
  ③ 동일 가격대(±0.5%) 재진입 차단. 동적 세션 33도 같은 전략이므로 결과 공유.
- 기타 세션 현황(07-10 09:15 KST 기준): 188(XRP)·190(XRP)·193(ADA) 거래 0건 대기 중,
  189(DOGE CMI_V2) -2.23%(1패 -150원 + DOGE 109원 재진입 보유 중), 192(BTC STRICT) +0.64%(BTC 보유 중).
  189는 MACD:BUY(100) 단독 + EMA필터의 SELL점수 0화로 score 0.50 진입 — 단일지표 진입 편향 재확인
  (CMI_V2→V1 교체 결정 항목과 연계).

---

## 🆕 2026-07-09 동적 멀티코인 진입 완화 (매수 0건 문제)

> **운영 DB 분석 결과**: 동적 세션(id 26~31, H1)은 가동 이후 **매수 0건** (position에 DYNAMIC 레코드 전무).
> 재기동(07-08 10:21 KST) 후 DYNAMIC 로그 783건 중 BUY 0 — HOLD 대부분이 buy=0.00~0.15 점수 미달,
> 드물게 나온 BUY(전 기간 ~21건)는 EMA200 게이트(0.2~0.7% 근소 차단)·BLACK_SWAN 거래량 오탐(수정 전)·
> RANGE 레짐이 전부 차단. 워치리스트도 목표 10개 대비 3~6개만 통과.

- [x] **코드 완화 (SCANNING 진입 경로 한정, 청산 경로는 기본값 유지)** — **미커밋 / 운영 미배포.**
  - `Ema200RegimeGate.allowsBuy(candles, coinPair, marginPct)` 오버로드 신설 — `DynamicTradingService`
    SCANNING에서 마진 1% 적용 (EMA200의 -1%까지 BUY 허용). 라이브·백테스트는 기존 시그니처(마진 0) 유지.
  - `DynamicTradingService` SCANNING evaluate params에 `weakThreshold=0.25`(기본 0.3),
    `strongThreshold=0.40`(기본 0.5) 주입.
  - `CompositeStrategy.finalSignal()` 버그 수정 — STRONG_SELL/BUY(weak)/SELL(weak) 분기가 params
    override 대신 상수를 참조해 `weakThreshold`/`strongThreshold` override가 절반만 적용되던 문제.
  - 테스트: `Ema200RegimeGateTest` 마진 2건 추가, `:core-engine` selector 테스트·`:web-api` Dynamic 테스트 통과.
- [x] **세션 설정 변경 (사용자가 UI에서 직접)** — 완료. 신규 동적 세션 32~37(M15, 6전략) 07-09 10:06 KST 시작.
- [ ] **배포 후 관찰** — 진입 발생 여부·진입 품질(완화로 인한 저품질 진입 손실) 1주 관찰.
  약세장에서 거래 빈도 증가는 손실 횟수 증가와 동행할 수 있음(실전매매 최근 48h 3전 3패 참고).
  - **2026-07-10 운영 DB 관찰 (가동 ~23h)**: 세션 32~37 전부 SCANNING 유지·**매수 0건**(DYNAMIC 주문/포지션 0).
    신호 로그 세션당 900~1,000건(10코인 워치리스트, 15분 주기 스캔 정상). BUY 신호 자체가 0 — 최근 4h 표본에서
    최고 buyScore 0.20(MACD 단독 BUY)으로 완화 임계값 0.25에도 미달. ATR_BREAKOUT/VOLUME_DELTA가 거의 전부
    HOLD(0). 세션 33(COMPOSITE_PULLBACK_MTF)은 SELL 562 vs HOLD 275로 SELL 신호 과다 성향(포지션 없어 전부 무시)
    — 라이브 191의 SELL 399건과 동일 패턴, 전략 자체의 SELL 편향.

---

## 🆕 2026-07-02 전략/실전매매/동적멀티코인 종합분석 — P0(N-1/N-2) + DM-1 + L-2 + S-1 + BLACK_SWAN_GUARD 완료

> 신규 발견 결함(N-1~N-3) 중 P0 2건 + DM-1 개선 + L-2 백테스트 게이트 추가 + S-1 검증 +
> BLACK_SWAN_GUARD 신규 구현 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 항목 5건).
> `:web-api:test` 116건, `:strategy-lib:test`+`:core-engine:test` 231건 전체 통과. **미커밋 / 운영 미배포.**

- [ ] **DOGE 전략 교체 운영 결정** — CMI_V2 → CRR/V1 교체 여부 (90일 분석에서 V1이 전 레짐 압도 확인, 병행 비교 목적 종료).
- [ ] **DM-1 배포 후 관찰** — 워치리스트 전체 평가로 SCANNING 틱당 REST 호출이 늘어난다(기존엔 첫 BUY에서 조기 종료). DM-4(rate limit 여유) 항목과 연계 모니터링.
  - **2026-07-08 운영 DB 관찰**: 동적 세션 5개(id 26~30, 전부 H1·REAL, 07-06 23:57 UTC 시작) 약 24h 경과. 전 세션 SCANNING 유지·포지션/주문 0건·자본 10,000원 그대로. 시간당 5~7회 스캔·워치리스트 60분 갱신 정상(큰 공백 없음). BUY 신호 0건 — 최고 buyScore 0.30(세션29 JTO)·0.28(28 SOL)·0.18(26 NEAR)으로 전부 점수 미달, EMA 하락추세 필터·H4 Supertrend 하락이 진입을 막는 중(하락장 정상 방어로 판단). 참고: 27·30이 동일 전략(COMPOSITE_PULLBACK_MTF) 중복 — 워치리스트만 미세 차이(NEAR vs JTO), 신호 거의 동일하여 병행 실익 재검토 필요.
- [ ] **L-2 후속 — CB/CRR 외 다른 전략·기간에서도 게이트 무영향인지 추가 검증** — 아래 결과 참조. 100일 BTC/ETH/SOL/XRP에서 CB·CRR은 게이트 ON/OFF 완전 동일(= SL/TP가 모든 청산을 선점, 전략SELL 경로 자체가 발동 안 함). 다른 전략·기간·타임프레임에서도 동일한지는 미검증 — 배포 전 최소 1~2개 추가 조합 확인 권장.
- [ ] **S-1 후속 — 더 긴 기간·더 많은 코인으로 재검증 권장** — 아래 결과 참조. 100일 4코인 표본에서 WEAK 0.3/0.4가 완전 동일 결과를 냈으나, 거래수가 코인당 2~11건으로 극히 적어 경계값 통과 사례 자체가 없었을 가능성. 3년 전체기간 다코인으로 재실행하면 다른 결론이 나올 수 있음.
- [x] **BLACK_SWAN_GUARD 오탐 수정 배포 + 잔여 확인 — 완료 (07-08 배포, 07-16 관찰 종료: 발동 전건 실제 급락 동반, SL 강화 알림 0건)** — 07-08 오탐 2건(세션 186/187 조기 손절, 거래량 5배 단독 발동 → SL 0.3% 조임)의 수정 완료: 거래량 조건 AND화(-2% 하락 동반 필수) + SL 강화 폭 ATR[1.2%, 5%] 클램프 + 텔레그램 알림. **같은 배포에 DriftAlert 오탐 수정 포함** — SELL drift 기준가 버그(매수 평균단가 사용 → slippage가 포지션 손익률로 기록, 매시간 반복 알림) 수정 + V54(order.signal_price 신설, 잘못 측정된 SELL drift 레코드 삭제) + 알림 쿨다운 24h + BUY drift 기록 신설. 상세 근거·정량 측정은 [`CHANGELOG.md`](CHANGELOG.md) 2026-07-08 항목 2건.
  - [ ] 배포 후 `[BlackSwanGuard] SL 강화` 텔레그램 알림 빈도 관찰 (AND 조건으로 대폭 감소 예상 — 잦으면 -2% 임계 재조정).
  - [x] ~~포지션 1232(BTC, 세션 186) SL 원복 여부 결정~~ — **폐기 (2026-07-15)**: 운영 DB 확인 결과 1232는 07-08 세션 186 정지 시 이미 CLOSED(-22.6원). 원복 대상 없음.
- [ ] **BLACK_SWAN_GUARD 백테스트 미반영** — 이번 구현은 라이브/동적 세션에만 적용. `BacktestEngine`에는 넣지 않았다(L-2처럼 기존 전략 검증 수치 전체를 다시 흔들 수 있어 범위 확대를 보류). 필요 시 별도 A/B로 영향 측정 후 반영 검토.
- [ ] **BLACK_SWAN_GUARD 시스템 전체 차단 버전 (설계 보류)** — 현재는 코인별 게이트만 구현(사용자 확인). "임의의 한 코인 급락 시 전 세션 진입 차단" 버전은 기준자산 선정·세션 간 상태 공유 등 더 큰 설계가 필요해 이번 범위에서 제외.

### S-1 검증 결과 — WEAK_THRESHOLD 0.3 하향, 이 표본에서는 무영향

`CompositeStrategy`의 `WEAK_THRESHOLD`(0.4→0.3, 코드는 이미 0.3으로 반영돼 있었으나 검증 이력 없음)를
`weakThreshold`/`strongThreshold` params로 override 가능하게 만든 뒤(기존 `adxThreshold` override 패턴과
동일), `WeakThresholdAbBacktestRunner`로 CMI_V1(CRR 주력 delegate)·COMPOSITE_BREAKOUT을 BTC/ETH/SOL/XRP
100일 H1에서 A(0.3)/B(0.4) 비교. **8개 조합(2전략×4코인) 전부 수익률·거래수·MDD·Sharpe 완전 동일** — 이
표본에서는 하향이 매매빈도에 아무 영향을 주지 않았다. 거래수가 코인당 2~11건으로 매우 적어(레짐 필터·
ADX 필터·EMA 필터 등 상위 게이트가 대부분을 이미 걸러냄), buyScore/sellScore가 0.3~0.4 구간에 걸리는
경계 사례 자체가 이 표본에는 없었던 것으로 보인다. "매매빈도 부족을 해소하려는 조정"이라는 원래 의도를
이 표본은 뒷받침도 반박도 하지 못한다 — 결론을 내리려면 더 긴 기간·더 많은 코인의 표본이 필요하다.

### L-2 검증 결과 — BacktestEngine에 전략 SELL 게이트 부재 확인 및 수정

`ExitRuleConfig`(백테스트·실전 공통 리스크 설정 클래스, "실전매매 현행 설정 기준"이라 명시돼 있음)에
`minHoldMinutesForSignalExit`/`minPnlPctForSignalExit`/`lossEscapeThresholdPct` 필드가 **아예 없었고**,
`BacktestEngine`의 전략 SELL 처리(구 250번째 줄)는 SL/TP와 달리 신호가 나오면 즉시 체결했다. 반면
`LiveTradingService`/`DynamicTradingService`는 최소보유 180분 + 본전청산차단(-1.00%~+0.30%)으로 전략 SELL을
적극적으로 억제한다 — 즉 지금까지의 모든 백테스트 수치(BTC +106.71% 등)는 실전에 없는 조기 청산 타이밍으로
산출된 것이었다.

`ExitRuleConfig`/`ExitRuleChecker.allowsSignalExit()`/`BacktestEngine`에 게이트를 추가해 수정했다.
**실제 영향 측정** (`SignalExitGateAbBacktestRunner`, BTC/ETH/SOL/XRP 100일 H1, COMPOSITE_BREAKOUT +
COMPOSITE_REGIME_ROUTER): 게이트 ON/OFF 결과가 **전 코인·전 전략에서 완전히 동일**했다. 즉 이 두 전략은
SL/TP가 이미 모든 포지션을 청산해버려 전략 SELL 분기 자체에 도달하지 못하고 있었다 — 청산 경로 분포 계측
(S-2, 이미 배포됨)이 실전에서 예측했던 "SL/TP 지배" 가설을 백테스트 쪽에서도 뒷받침하는 결과. 좋은 소식은
이번 수정이 기존 Tier1 배포 권고 수치(CB/CRR 계열)를 무너뜨리지 않는다는 것이지만, 다른 전략(특히 SL/TP
폭이 넓거나 트레일링에 덜 의존하는 전략)에서는 영향이 다를 수 있다.

### 이번에 수정한 것 (N-1, N-2, DM-1)

1. **N-1 (🔴): live_trading_session ↔ dynamic_session 별도 BIGSERIAL sessionId 충돌 시 포지션 교차 오염** —
   `position.session_kind`(V51)가 있음에도 `DynamicTradingService`·`LiveTradingService`의 포지션 조회 12곳이
   전부 `sessionId`만으로 조회해, ID가 우연히 겹치면 동적 세션이 라이브 포지션을 매도하거나
   세션 정지 시 다른 종류 세션 포지션까지 청산될 수 있는 결함. `PositionRepository`에
   `findBySessionKindAndSessionId(AndStatus/AndCoinPairAndStatus)` 신규 추가, 두 서비스의 전체 조회 경로 교체.
2. **N-2 (🔴): 전략 거버넌스(`StrategyLiveStatusRegistry.isBlocked()`)가 실제로는 어디에도 강제되지 않던 결함** —
   `LiveTradingService`는 필드만 주입하고 미사용, `DynamicTradingService`는 필드조차 없었음(2026-06-01 감사
   §11 갭1이 "완화된 미검증 단독지표 허용"만 지적했지만, 실제로는 BLOCKED 전략조차 라이브·동적 양쪽 다
   실돈 세션 생성이 가능했던 더 심각한 상태). 양쪽 `createSession`에 `isBlocked()` 검사 추가.
3. **부수 발견**: H2 테스트 스키마(`schema-h2.sql`)에 `dynamic_session` 테이블 자체가 없어 동적 세션 관련
   통합 테스트가 원천적으로 불가능했음(§ 2026-07-02 동적 시스템 보완 항목의 "동적 세션 전용 테스트 부재"의
   근본 원인). V50 마이그레이션 기준으로 테이블 추가.
4. **DM-1 (🟡): 동적 세션 "첫 BUY 승자독식" 개선** — `DynamicTradingService.processScanningTick`이 워치리스트를
   거래대금 내림차순으로 순회하다 첫 BUY 신호에서 즉시 진입해, 실제로는 신호 품질이 아니라 "거래대금 순위"가
   진입 코인을 결정하고 있었다. 워치리스트 전체를 평가(게이트·로그 포함)한 뒤 BUY 후보 중 신호 강도
   (`StrategySignal.getStrength()`)가 가장 높은 코인 하나만 진입하도록 변경. 선택되지 않은 후보들은
   신호품질 로그에 "다른 코인 신호가 더 강함" 사유로 기록. 선택 로직은 `pickBestBuyCandidate()`로 분리해
   네트워크·전략 평가 없이 순수 단위 테스트 가능하게 함.
5. **신규 회귀 테스트**: `SessionKindIsolationTest`(4건) — 같은 sessionId를 가진 LIVE/DYNAMIC 포지션이
   kind-aware 조회로 격리됨을 확인 + BLOCKED 전략의 라이브/동적 세션 생성 거부 + ENABLED 전략 정상 생성.
   `DynamicScanSelectionTest`(3건) — 여러 BUY 후보 중 최고 강도 선택, 단일 후보, 동률 시 첫 후보 유지.

---

## 🔬 2026-07-02 전략/실전매매/손익대시보드 종합 감사 — 후속 과제

> D-1~D-5(부분체결 평균단가·session_kind·부분매도 취소·타임아웃 race·매수수수료), P-1(DELETED 세션 제외),
> P-3(closedSince 필터), S-2(청산 경로 분포 계측)는 수정 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 종합감사 항목).
> `:web-api:test` 109건 전체 통과. **미커밋 / 운영 미배포 — V52 마이그레이션 포함, 배포 전 DB 반영 필요.**

- [ ] **S-1 CompositeStrategy WEAK_THRESHOLD 검증 이력 확인** — 코드가 `0.4`가 아닌 `0.3`으로 이미 낮춰져 있음. 사용자 확인상 매매빈도 부족으로 의도된 조정. 백테스트 검증(다코인 H1) 이력이 있는지 확인하고 없으면 사후 검증 권장.
- [ ] **오염된 CLOSED 손익 레코드 자체 보정 (보류 판단)** — P0(2026-06-23) 이전 청산 포지션은 원 체결가가 애초에 저장되지 않아 소급 재계산이 불가능. `closedSince` 기간 필터로 회피만 가능한 상태 — 완전한 보정은 불가로 결론.
- [ ] **DYNAMIC 세션 성과를 손익 대시보드에 노출** — 현재 `performance` 페이지는 live/paper 탭만 존재. 동적 멀티코인 세션 운영이 시작됐으므로 세 번째 탭 또는 통합 뷰 필요.
- [ ] **전역 리스크 지표(MDD/Sharpe) 분모 왜곡 검토** — `getPerformanceSummary`의 글로벌 지표가 시기가 겹치지 않는 과거 세션 원금까지 동시 투자로 가정해 계산됨. 활성 세션 한정 또는 시점별 실투입자본 기준으로 재정의 검토.
- [ ] **Stateful 전략(CRR 등) 재시작 시 상태 소실** — `sessionStatefulStrategies`가 인메모리 맵이라 배포/재시작마다 레짐 hysteresis(3캔들)가 리셋됨. 장기적으로 상태 스냅샷 저장 검토.

---

## 🔍 2026-07-02 실전 4대 전략 검토 (CRR / CB / HAS / CMI_V2) — 반영 완료

> ATR 거래량 필터 결함 수정 + 관찰 항목 일괄 반영 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02 항목 2건).
> A/B 백테스트 러너: [`StrategyReviewAbBacktestRunner`](../core-engine/src/test/java/com/cryptoautotrader/core/backtest/StrategyReviewAbBacktestRunner.java)
> (`-Dreview.backtest.dir=d:/tmp`, 100일 H1 × BTC/ETH/SOL/XRP). **미커밋 / 운영 미배포.**

- [ ] **CRR RANGE/TRANSITIONAL(비화이트리스트) 진입 수학적 희소 — 모니터링 유지** — RANGE(ADX<20)에서 V1 위임 시 MACD(0.5)는 자체 ADX(25) 필터로 항상 HOLD → 진입은 VWAP(0.3)+GRID(0.2) 동시 고신뢰 필요. 90일 분석의 RANGE WR 66~69%가 이 희소·이중확인 구조 덕일 수 있으므로 **완화는 백테스트 검증 전 금지**. 발화 빈도만 모니터링.
- [ ] **CMI_V2 존속 판단 (운영 결정 필요)** — 2026-06-30 90일 분석에서 V1이 전 레짐 V2 압도 확인(CRR도 V1로 개편됨). V1 병행 비교 목적이 끝났으면 V2 단독 실전 세션을 V1 또는 CRR로 교체 권장 — 세션 교체는 운영자 판단 사항.
- [ ] **배포 후 관찰** — HEIKIN_ASHI_STOCH 강도 게이트 해제(기본 0)로 신호 빈도가 늘어난다. 실전 신호품질 로그로 승률·빈도 재확인 (구 기본 70은 `strategyParams.minStrengthPct=70`으로 복원 가능).

---

## 🔄 2026-07-02 동적 멀티코인 시스템 보완 — 후속 관찰/과제

> 결함 6건 수정 완료(상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-07-02). 컴파일·mock 테스트 통과, **미커밋 / 운영 미배포**.

- [ ] **배포 후 관찰**: 매도 FAILED 시 "매도 롤백 포지션 재결속" 로그 + 세션이 POSITION_MONITORING으로 복귀해 재매도하는지. 텔레그램 "재결속 불가" 경고가 오면 수동 청산.
- [ ] **동적 세션 전용 테스트 부재 (부분 해소)** — H2 테스트 스키마에 `dynamic_session` 테이블이 아예 없어 통합
      테스트가 원천 불가능했던 근본 원인은 해소(2026-07-02, `schema-h2.sql`에 V50 기준 테이블 추가 — 상세는
      위 §"P0 수정 완료" 참조). 단, `reconcileDynamicClosingPositions`/재결속/이중매도 가드 자체의 단위 테스트는
      여전히 미작성.
- [ ] (기존 설계 한계, 미변경) 보유 중 `totalAssetKrw`가 미실현 손익을 반영하지 않아 MDD 피크가 실현 기준으로만 추적됨 — 필요 시 모니터링 tick에서 시가평가 갱신 검토.
- [ ] (기존 설계 한계, 미변경) SCANNING 60초 tick마다 워치리스트 10코인 × 캔들 250개 REST 조회 — Upbit rate limit 여유 모니터링, 필요 시 캔들 캐시 도입.

---

## 🚨 2026-05-31 실전 로그 분석 (docs/logs/ 3종 교차 분석)

> `live_trading_sessions/positions_20260531.csv` + `signal_quality_30d_20260531.csv` 분석.
> LIVE 세션 4개(143 ETH / 144 SOL / 145 XRP / 148 BTC) 모두 ~2개월 운영했으나 사실상 본전.
> **P0는 코드 수정 적용 + 테스트 통과. P1은 조사만 완료(코드 변경은 백테스트 검증 후).**

### 현황 수치
- **세션 수익률**: BTC -0.04% / ETH -0.12% / SOL -0.16% / XRP +0.65% → 전부 본전권.
- **포지션 192건**: 실이익 2건 / 수수료만 손실(-4원≈-0.05%) 146건 / 미체결·size0 44건.
- **신호 568,338건/30일**: HOLD 99.6%(566,027) / SELL 1,351 / BUY 960 / **실제 체결 42건**.

### 🔴 P0 — 청산/PnL 정확성 (돈 직결) — ✅ 핵심 수정 완료 (2026-06-23, CHANGELOG 참조)
근본 원인 확정: 매도 체결가 미산출 → `realizedPnl`이 -매도수수료로만 기록되던 "가짜 본전".
당초 가설(ord_type/side 문자열 매칭 실패)이 아니라, **Upbit `GET /v1/order` 응답이 체결 금액을
최상위 `executed_funds`가 아닌 `trades[]` 배열로 내려주는데 DTO가 이를 파싱하지 않아
`executedFunds`가 영원히 null**이던 것이 진짜 원인. 결과적으로 시장가 매도가 `FILLED`로
전이되지 못하고 `SUBMITTED`에 무한 정체(실전 로그 다수 주문 확인).
- [x] **수정 완료** — `OrderResponse.resolveExecutedFunds()`(trades 합산 폴백) 추가 + `applyFillPrice`/`syncOrderState`에서 사용. 회귀 테스트 통과. 상세: [`CHANGELOG.md`](CHANGELOG.md) 2026-06-23 항목.
- [ ] **운영 관찰** — 배포 후 정체됐던 매도 주문들이 FILLED로 정리되고 신규 매도가 정상 체결·기록되는지 확인. PnL 재수집.
- [ ] **청산 정책 표준화 검증** — SL/TP는 DB(`ExitRuleConfig`) 동적 설정(현 SL 5%/TP +10%). P0 수정 반영된 실거래 재수집 후 조정.

### 🟠 P1 — 신호 발생률 (⚠️ 실거래 진입 빈도 변경 = 백테스트 검증 필수)

#### ✅ 죽은 하위지표 조사 완료 (2026-05-31, 읽기 전용)
결론: **MACD/VWAP/GRID는 "죽은" 게 아니라, 합산 임계와 가중치·필터가 수학적으로 어긋나
"거의 항상 HOLD"가 강제되는 구조다.** 핵심 메커니즘 3가지:

1. **단일 보조지표로는 임계 돌파가 수학적으로 불가능.**
   [`CompositeStrategy.finalSignal`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeStrategy.java#L182) 임계 `WEAK=0.4`. `score=Σ(weight×confidence)`, `confidence=strength/100` ([StrategySignal](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/StrategySignal.java#L46)).
   - SUPERTREND 가중 0.3 → 추세전환 strength 70(conf 0.7)이어도 기여 **0.3×0.7=0.21 < 0.4**. 지속 신호(≤50)면 ≤0.15.
   - VWAP 0.3 / GRID 0.2 도 동일 — **단독 최대 기여가 임계 미만.**
   - 따라서 진입하려면 **반드시 MACD(0.5)가 동반 발화**해야 함 = 사실상 MACD 단일 의존.

2. **그 MACD가 4중 필터로 거의 침묵한다.** [`MacdStrategy`](../strategy-lib/src/main/java/com/cryptoautotrader/strategy/macd/MacdStrategy.java#L66): (a) 골든/데드 **크로스 순간**에만, (b) ADX≥25, (c) 제로라인, (d) 히스토그램 확대 — 4조건 동시 충족 캔들만 발화. 그 외 전부 HOLD(0).

3. **레짐↔임계 불일치 (설계 결함, 가장 치명적).**
   [`CompositeRegimeRouter`](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeRegimeRouter.java#L100)는 **TRANSITIONAL(ADX 20~25)** 구간을 CMI_V1(MACD0.5+VWAP0.3+GRID0.2)에 위임한다. 그런데 MACD의 ADX 필터 임계가 **25.0** → TRANSITIONAL에서는 **주력 MACD가 100% 차단됨.** 남은 VWAP+GRID=0.5는 둘 다 풀강도 동방향이어야만 0.4 돌파인데 (역추세+평균회귀라) 거의 불가 → **TRANSITIONAL은 구조적으로 진입 거의 불가능.** 로그의 `[TRANSITIONAL] buy=0.00 sell=0.00 [MACD:HOLD(0) VWAP:HOLD(0) GRID:HOLD(0)]` 14.8만 건이 이 경로. `[TREND] sell=0.15 [SUPERTREND:SELL(50)]` 5.4만 건은 메커니즘 1(보조지표 단독 0.4 미달).

#### 권고 (백테스트 검증 후 적용 — 코드 미변경)
- [ ] **MACD ADX 임계를 레짐별로 정합화** — TRANSITIONAL 위임 시 MACD `adxThreshold`를 20 이하로 내리거나(params override), TRANSITIONAL→CMI_V1 위임 자체 재고. (라우터가 params로 MACD adxThreshold를 낮춰 주입하는 방식이 영향 최소)
- [ ] **보조지표 단독 진입 가능하도록 가중치 또는 임계 조정** — 예: WEAK_THRESHOLD 0.4→0.3, 또는 SUPERTREND/VWAP 가중 0.3→0.4. 단 오탐↑ 위험 → 반드시 `BacktestEngine` 다코인 검증.
- [ ] **SUPERTREND 추세지속 strength 상향 검토** — 현재 지속 신호 ≤50(conf≤0.5)이라 단독 기여 미미.
- [ ] **SELL/RANGE 편중 점검** — 롱 전용 구조, RANGE(ADX<20) 무조건 HOLD 분류 비율 점검.

### 🟡 P2 — 측정 인프라 (재진단: 일부는 이미 정상)
- [x] ~~4h/24h 백필 / CSV 따옴표~~ — 확인 결과 [`SignalQualityService`](../web-api/src/main/java/com/cryptoautotrader/api/service/SignalQualityService.java)는 이미 과거 시점 캔들(`getCandles(targetTime)`)로 정확히 백필하고, [`CsvExportService`](../web-api/src/main/java/com/cryptoautotrader/api/service/CsvExportService.java)도 이미 `q()`로 RFC4180 인용 처리 중. **기존 115MB 파일의 손상 행은 과거 버전 산출물**로 추정(현재 코드 버그 아님).
- [ ] **HOLD 로그 비대 — 실제 원인** = [`LiveTradingService`](../web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java#L804) 가 매 평가마다 `StrategyLogEntity`를 HOLD 포함 전량 저장(30일 56만 행). HOLD 제외/요약 적재 검토(단, HOLD 비율 리포트 의존성 확인 후).
- [ ] **미라벨 신호 재조사** — PAPER 신호 `signalPrice=null`이면 백필 스킵되는 점 등.

---

## 📝 2026-04-30 주요 전략 분석 문서 작성

[docs/주요전략분석_v20260430.md](./주요전략분석_v20260430.md) — `COMPOSITE_BREAKOUT`,
`COMPOSITE_MOMENTUM_ICHIMOKU` (V1), `COMPOSITE_MOMENTUM_ICHIMOKU_V2` 3종 종합 분석.
구조·필터(ADX/EMA/Ichimoku)·하위 전략 가중치·V1↔V2 차이(VWAP→SUPERTREND)·
2026-04-24 백테스트 비교(KRW H1, ETH/SOL/XRP/MOVE/USDT/IP/FLOCK)·
코인별 전략 선택 의사결정 트리 포함.

---

## 🧨 2026-04-30 전략 분석 비판 기반 개선 로드맵

> 분석 문서([docs/주요전략분석_v20260430.md](./주요전략분석_v20260430.md))의 한계를
> 신랄하게 재검토한 결과, 다음 갭들이 식별됨. 우선순위별로 정리.

### 식별된 핵심 갭

1. **승률 11~19%, 백테스트 vs WF 13배 격차** → 사실상 long-tail 운에 베팅하는 lottery 구조. 통계적 검증 부재.
2. **MDD 미개선 (V1 = base, -25.62%)** → Ichimoku 필터는 위험관리가 아닌 노이즈 필터에 불과. 문서가 이 한계를 약하게 다룸.
3. **V1→V2 동기 미검증** → 거래수↑ + 손실 코인↑ 패턴이 "구조적 개선"이 아닌 단순 진입 빈도 증가일 가능성. HOLD 비율, 평균 보유시간, 익/손 분포 비교 미수행.
4. **RSI(0.2) 수학적 무의미** → 단독 sellScore>0.4 만들려면 confidence>2.0 필요(불가). "반쯤 죽은 가중치"를 그대로 둠.
5. **EMA 이중 카운팅** → EMA 방향 필터(EMA20/50) ↔ 하위 EMA_CROSS(EMA20/50) 동일 지표 중복. 가중치 0.1이 사실상 더 큰 영향.
6. **청산 정책 통째로 누락** → 14% 승률이면 SL/TP·trailing이 PnL 거의 전부를 결정하나 분석에 빠짐.
7. **ADX 필터의 자기모순** → 4/27 핫픽스 기록상 BREAKOUT 4개 세션 100% 차단됨. 그런데 분석은 ADX를 핵심 무기로 칭송.
8. **Ichimoku 절반만 사용** → 가격↔구름만 사용. Tenkan/Kijun 크로스, **Chikou Span**, 구름 두께/twist 모두 미사용.
9. **Regime 엔진 3중화** → BREAKOUT(자체 ADX), V1/V2(Ichimoku), `RegimeAdaptiveStrategy` 따로 작동. 통합 평가 부재.
10. **통계 유의성 검정 부재** — Sharpe CI, Profit Factor CI, t-test 한 줄도 없이 거래수 6~10건짜리를 결론에 사용.

### 🔴 P0 — 즉시 (검증 데이터 보강, 결론 재해석)

- [ ] **거래수 30건 미만 결과 본문 결론에서 분리** — 분석 문서 v2026-04-30 의 KRW-SUPER(6건), KRW-IP(7건) 등을 "참고" 섹션으로 이동. v2 개정.
- [ ] **MDD / Sharpe / Profit Factor / Calmar 컬럼 추가** — 수익률 단일 지표 결론 탈피. backtest_history 컬럼은 이미 존재 → 분석 문서 표 보강만 필요.
- [ ] **연도별 분리 백테스트** (2022/2023/2024/2025) — 어느 해에 어느 전략이 실제로 망하는지 노출. 현재 시장 사이클 통합 수치만 존재.
- [ ] **HOLD 비율 / 평균 보유시간 / 평균 익절·손절 비율 측정** — V1 vs V2 의 "진짜" 차이 정량화. BacktestEngine 결과 객체에 해당 메트릭 추가 또는 trade-level CSV로 후처리.
- [ ] **백테스트-WF 격차 95% CI 산출** — Bootstrap 1000회로 격차 신뢰구간 제시. 13배 격차가 우연 가능성 평가.

### 🟠 P1 — 단기 (전략 자체 개선)

- [x] **EMA 이중 카운팅 제거** — EMA_CROSS(0.1) → MACD(0.2) 교체. 가중치 ATR 0.5 / VD 0.3 / MACD 0.2 재조정. `CompositePresetRegistrar` 반영 완료.
- [x] **RSI(0.2) 재설계** — RSI 가중치 제거 + `RsiVetoStrategy` 래퍼 신규 구현. RSI>75 BUY 강제차단 / RSI<25 SELL 강제차단. `COMPOSITE_BREAKOUT` 및 `COMPOSITE_BREAKOUT_ICHIMOKU` 적용 완료.
- [x] **ADX 임계값 동적화** — `IndicatorUtils.adxList()` + `adxPercentileThreshold()` 신규. 최근 60캔들 ADX 30th percentile, [15, 25] 클램프. `CompositeStrategy` 적용 완료.
- [x] **Ichimoku 5요소 사용 확장** — `IchimokuFilteredStrategy` 3-레이어로 확장: (1) 구름 내부 차단, (2) Chikou Span vs 26봉전 가격, (3) Tenkan/Kijun 방향. 최소 캔들 52→78. 완료.
- [ ] **청산 정책 표준화** — 진입가 -3% 손절 / +6% 익절 후 ATR×2 trailing stop 으로 통일. 현재 `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT=30분` 만 존재 → 분석 문서·실전 모두 명시.
- [x] **분석 문서 v2 개정** — P0 결과 반영, "Ichimoku = 노이즈 필터 (위험관리 아님)" 명시, MDD 미개선을 메인 평가 섹션에 못박기. 완료.

### 🟡 P2 — 중기 (구조 통합)

- [ ] **Regime 엔진 통합** — `MarketRegimeDetector` 를 단일 진입점으로 만들어 BREAKOUT / V1 / V2 모두 동일 regime 신호를 입력으로 사용. 3중화 해소.
- [ ] **Walk-Forward 자동 재최적화 활성화** — `StrategyWeightOptimizer` 인프라 이미 구축됨 ([WeightOptimizerSnapshotEntity](../web-api/src/main/java/com/cryptoautotrader/api/entity/WeightOptimizerSnapshotEntity.java)). 90일마다 가중치 자동 재조정 스케줄러 활성화.
- [ ] **앙상블 메타 전략** — BREAKOUT / V1 / V2 출력 시그널을 voter 로 묶어 majority + confidence-weighted 최종 신호. (§ 새 전략 §1 `COMPOSITE_REGIME_ROUTER` 또는 그 상위 ensemble.)
- [ ] **Deflated Sharpe / PBO** — 다전략 튜닝 선택 편향 보정 (장기 검토 항목 승격).

---

## 🆕 2026-04-30 새로운 전략 / 기능 제안

> 비판 분석에서 도출된 신규 전략 7종 + 보호 메커니즘. ROI 우선순위 ★표시.

### ★ 1. `COMPOSITE_REGIME_ROUTER` (메타 전략) ✅ 구현 완료

단일 시점에서 ADX/ATR 변동성에 따라 BREAKOUT vs MOMENTUM 자동 위임.

```
VOLATILITY  (ATR > SMA×1.5, ADX < 25) → COMPOSITE_BREAKOUT  (ATR spike 돌파)
TREND       (ADX > 25)                 → CMI_V2              (강한 추세 모멘텀)
TRANSITIONAL (ADX 20~25)               → CMI_V1              (전환 구간 보수적)
RANGE       (ADX < 20)                 → HOLD                (횡보 진입 금지)
```

- Hysteresis 3회 연속 감지 시 전환 (MarketRegimeDetector 재사용).
- GRID stateful + RegimeDetector stateful → `registerStateful` 등록.
- 구현: [CompositeRegimeRouter.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/CompositeRegimeRouter.java)

### ★ 2. `COMPOSITE_MTF_CONFIRMED` / `COMPOSITE_MTF_BTC` / `COMPOSITE_MTF_MOMENTUM` (멀티 타임프레임) ✅ 구현 완료

H1 진입 신호 + H4 Supertrend 추세 동의 시에만 진입. `CandleDownsampler.java` 재사용.
- `COMPOSITE_MTF_BTC`: CB(H1) + Supertrend(H4) — **ETH +127.70%**, DOGE +82%, AAVE/CHZ 흑자 전환
- `COMPOSITE_MTF_MOMENTUM`: CMI_V2(H1) + Supertrend(H4) — **BLUR +48.06%**, DOGE +83%
- `COMPOSITE_MTF_CONFIRMED`: CRR(H1) + Supertrend(H4) — 범용, **XRP +3.37%** (유일 흑자)
- 구현: [MtfConfirmedStrategy.java](../core-engine/src/main/java/com/cryptoautotrader/core/selector/MtfConfirmedStrategy.java)

### ★ 3. `BLACK_SWAN_GUARD` (전 전략 공통 서킷 브레이커)

1시간 내 -5% 하락 또는 거래량 평균×5 초과 시 **전 신규 진입 차단 + 보유 trailing stop 0.3% 강화**.
LUNA/FTX 류 사건 방어 — 어떤 모멘텀/돌파 전략도 단독 방어 불가.

### 4. `COMPOSITE_BREAKOUT_VOL_ADAPTIVE`

ATR multiplier 1.5 고정 → 코인별 변동성 분포로 적응:
```
multiplier = 1.0 + (현재 ATR / ATR 90일 평균)
ADX threshold = ADX 90일 30th percentile
```
4/27 핫픽스(ADX 20→15) 의 영구 자동화.

### 5. `BAYESIAN_WEIGHT_TUNER`

정적 0.5/0.3/0.2 → 베이지안 사후확률 갱신:
```
매 100거래마다: weight_i ← weight_i × (실제 승률_i) / (예측 confidence 평균_i)
                재정규화 (합 1.0)
```
[WeightOverrideStore](../core-engine/src/main/java/com/cryptoautotrader/core/selector/WeightOverrideStore.java) 인프라 활용. 코인별 자동 가중치 분화.

### 6. `CVD_DIVERGENCE`

기존 VolumeDeltaStrategy 의 다이버전스 모드를 *진입 신호 격하* → *역방향 진입 신호로 승격*.
가격 신고점 + CVD 신저점 = 약세 다이버전스 → 적극적 SELL. 횡보장에서도 매매 가능.

### 7. `KELLY_SIZED_COMPOSITE`

전략 신호 동일, 포지션 크기를 Kelly Criterion 으로:
```
Kelly% = W − (1−W)/R    (W=최근 30거래 승률, R=평균 익/손 비율)
실제 베팅 = Kelly% × 0.25  (Half-Kelly)
```
14% 승률 + R=8 이면 Half-Kelly ≈ 1.6%. 현재 동일 비중 베팅의 통계적 비효율 해소.

### 우선순위 권고

| 우선순위 | 전략 | 사유 |
|---------|------|------|
| ⭐⭐⭐ | `COMPOSITE_REGIME_ROUTER` | 기존 3전략 자산 재활용, 코인별 분리 운영 단순화 |
| ⭐⭐⭐ | `COMPOSITE_MTF_CONFIRMED` | 14% 승률 → 25%+ 가능, 인프라 존재 |
| ⭐⭐⭐ | `BLACK_SWAN_GUARD` | 모든 전략 공통 안전망. 비용 낮고 효과 큼 |
| ⭐⭐ | `COMPOSITE_BREAKOUT_VOL_ADAPTIVE` | 핫픽스 영구화 |
| ⭐⭐ | `KELLY_SIZED_COMPOSITE` | 자금 효율 개선 |
| ⭐ | `BAYESIAN_WEIGHT_TUNER` | 인프라 있으나 검증 필요 |
| ⭐ | `CVD_DIVERGENCE` | 기존 VD 보강 수준 |

---

## 🔧 2026-04-27 라이브 분석 기반 핫픽스

30일 라이브 데이터 분석 결과 승률 1.63%, 121건이 "동가 청산 → 수수료만 손실" 패턴. 다음 3건 적용:

1. **SELL 신호 최소 보유시간 가드** (`LiveTradingService`)
   - `MIN_HOLD_MINUTES_FOR_SIGNAL_EXIT = 30분`
   - 진입 30분 이내의 전략 SELL 신호는 차단 (SL/TP는 항상 작동)
   - 신호품질 로그에 차단 사유 기록
2. **CompositeStrategy ADX 필터 파라미터화** (`CompositeStrategy`)
   - `adxThreshold`, `adxPeriod`, `skipAdxFilter` params로 override 가능
   - LiveTradingService가 RANGE 레짐 자동 감지 시 `adxThreshold=15.0`으로 완화
   - 4월 24일 시작 BREAKOUT 세션 4개(SOL/XRP/ETH/BTC)가 ADX(16~18)<20 으로 100% 차단되던 문제 해소
3. **BUY 차단(이미 보유) 시 신호 강도/보유 손익 비교 로깅**
   - 향후 피라미딩/교체 정책 설계용 데이터 수집
   - blockedReason: "이미 포지션 보유 중 (신규신호강도=X, 보유포지션 pnl=Y%, 보유시간=Z분)"

후순위(미적용): MACD_STOCH_BB SELL 조건(72% SELL 편향) — 현재 미사용 전략이라 보류.

---

## 📊 graphify 코드베이스 분석 결과 (2026-04-21)

> `graphify` 지식 그래프 파이프라인으로 프로젝트 전체를 분석한 결과. 산출물: `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.html`, `graphify-out/obsidian/`

### 분석 규모

| 항목 | 수치 |
|------|------|
| 전체 파일 | 414개 |
| 전체 단어 | ~283,501 words |
| 추출 노드 | 2,518개 |
| 추출 엣지 | 5,700개 |
| 커뮤니티 | 228개 |

### ⚠️ God Node — 단일 장애점 식별

| 노드 | 엣지 수 | 의미 | 권고 |
|------|---------|------|------|
| `of()` (CoinPair.of) | 206개 | 전체 시스템이 단 하나의 팩토리 메서드에 집중 | 불변 값 객체로 충분하나 테스트/모킹 시 취약. 향후 코인 추가 시 파급력 큼 |
| `LiveTradingService` | 48개 | 실전매매 로직 전체 집중 | OrderExecution / SessionLifecycle / RiskMonitor 3분리 권고 (§7로 부분 완화됨) |
| `BacktestEngine` | ~40개 | 백테스트 핵심 엔진 | Tier1-3 수정으로 개선 완료. 현재 수준 수용 가능 |

### 🏘️ 주요 커뮤니티 현황

| ID | 이름 | 핵심 노드 | 상태 |
|----|------|----------|------|
| C0 | Walk-Forward Validation | WalkForwardTestRunner, OOS metrics | ✅ §2 완료 |
| C1 | Live Trading Core | LiveTradingService, SessionBalanceUpdater | ✅ §7-10 완료 |
| C2 | Strategy Registry & Routing | StrategyLiveStatusRegistry, StrategySelector | ✅ §11 완료 |
| C3 | Risk Management | RiskManagementService, PortfolioSyncService | ✅ §5,8 완료 |
| C4 | Exchange Adapter | UpbitWebSocketClient, ExchangeHealthMonitor | ✅ §9 완료 |
| C5 | Backtest Metrics | MetricsCalculator, PerformanceReport | ✅ §1,13 완료 |
| C14 | Backtest Performance Results | 복합전략 5코인 × 8전략 백테스트 결과 | ✅ DB 저장 완료 |
| C18 | AI Pipeline & News Feed | LLM Task Router, NewsCollector | 🔵 구현 확인됨 |
| C21 | Discord Morning Briefing | DiscordNotificationService, MorningBriefingScheduler | 🔵 구현 확인됨 |
| C25 | Spring Security Config | SecurityConfig, JWT Filter | ⚠️ Bearer 토큰 인증 있으나 완전한 Security 구성 미검증 |

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

### 2026-04-30 신규 전략 H1 FULL 백테스트 비교 (7전략 × 17코인)

> **조건**: 2022-01-01 ~ 2026-04-30, 초기자금 1,000만, 슬리피지 0.1% + 수수료 0.05%, H1.
> CB=COMPOSITE_BREAKOUT, V1=CMI_V1, V2=CMI_V2, CRR=COMPOSITE_REGIME_ROUTER,
> MTF_B=COMPOSITE_MTF_BTC, MTF_M=COMPOSITE_MTF_MOMENTUM, MTF_C=COMPOSITE_MTF_CONFIRMED.
> **굵게** = 코인별 1위 전략.

| 코인 | CB | V1 | V2 | CRR | MTF_B | MTF_M | MTF_C |
|------|-----|-----|-----|-----|-------|-------|-------|
| **BTC** | **+106.71%** | +5.68% | +13.80% | +14.33% | +29.66% | +14.26% | +14.26% |
| **ETH** | +30.90% | +50.73% | +58.00% | +65.09% | **+127.70%** | +75.79% | +75.79% |
| **SOL** | +62.79% | +17.30% | +42.32% | **+65.38%** | +43.40% | +60.20% | +60.92% |
| **XRP** | -1.60% | +1.04% | -10.35% | -0.48% | -21.74% | +2.71% | **+3.37%** |
| **DOGE** | +17.75% | +48.86% | **+124.77%** | +59.89% | +82.06% | +83.40% | +83.40% |
| **ADA** | **+86.98%** | +5.89% | +12.52% | +8.85% | -25.78% | +10.03% | +11.05% |
| **AAVE** | -24.52% | -48.90% | -40.71% | -3.80% | **+28.15%** | +11.34% | +12.10% |
| **BLUR** | -17.23% | +23.24% | +10.52% | +33.19% | +38.69%⚠ | **+48.06%** | **+48.06%** |
| **CHZ** | -23.77% | -12.77% | -18.38% | -26.94% | **+14.09%** | -23.28% | -23.28% |
| MOVE | -2.88% | — | — | -2.88% | -2.18% | -7.19% | -7.19% |
| SUPER | — | — | — | -4.16% | +5.61%⚠ | -4.16% | -4.16% |
| IP | — | — | — | +12.99%⚠ | +11.77%⚠ | +13.94%⚠ | +13.94%⚠ |
| FLOCK | — | — | — | -8.49% | -9.18% | -8.49% | -8.49% |
| AXL | — | — | — | -11.37% | -13.41% | -8.18% | -8.18% |
| BIO | — | — | — | -3.49% | -4.00% | -3.49% | -3.49% |
| KERNEL | — | — | — | -5.78% | -8.62% | -4.38% | -4.38% |
| USDT | — | — | — | +0.55% | -6.73% | +1.10% | +1.10% |

> ⚠ 거래 수 15건 미만 — 통계적 신뢰성 부족.
> SUPER/IP/FLOCK/AXL/BIO/KERNEL: 모든 전략 거래수 1~6건으로 결론 도출 불가 (참고만).

### 2026-04-30 신규 MTF 전략 — 코인별 MDD 비교

| 코인 | 1위 전략 | 수익률 | MDD | Sharpe | 거래수 | 이전 대비 |
|------|---------|--------|-----|--------|--------|---------|
| **BTC** | COMPOSITE_BREAKOUT | **+106.71%** | -8.88% | 1.24 | 79 | 유지 |
| **ETH** | COMPOSITE_MTF_BTC | **+127.70%** | **-7.24%** | 1.35 | 61 | ↑ 대폭 개선 (CB +30% → MTF_B +127%) |
| **SOL** | COMPOSITE_REGIME_ROUTER | **+65.38%** | -14.93% | 0.76 | 101 | ↑ 소폭 개선 (CB → CRR) |
| **XRP** | COMPOSITE_MTF_CONFIRMED | **+3.37%** | -15.67% | 0.13 | 62 | ↑ 유일 흑자 코인 |
| **DOGE** | CMI_V2 | **+124.77%** | -30.75% | 0.87 | 173 | 유지 (MTF 근접하나 MDD 더 나쁨) |
| **ADA** | COMPOSITE_BREAKOUT | **+86.98%** | -14.14% | 0.96 | 46 | 유지 (MTF_BTC -25.78%로 역효과) |
| **AAVE** | COMPOSITE_MTF_BTC | **+28.15%** | -31.01% | 0.48 | 62 | ↑ 흑자 전환 (기존 -24.52%) |
| **BLUR** | MTF_MOMENTUM/CONFIRMED | **+48.06%** | -11.91% | 0.91 | 39 | ↑ CRR +33% → MTF_M +48% |
| **CHZ** | COMPOSITE_MTF_BTC | **+14.09%** | -14.88% | 0.32 | 48 | ↑ 흑자 전환 (기존 -23.77%) |

---

### 2026-04-24 백테스트 & Walk-Forward 재실행

> **소스**: `docs/backtest_history_20260424.csv` (H1 FULL, 필터 없음), `docs/backtest_history_20260424_local.csv` (H1 FULL, **EMA200 필터 적용**), `docs/walk_forward_history_20260424_local.csv` + `(3).csv` (EMA200 필터 WF).
> **공통 조건**: 2022-01-01 ~ 2026-04-24, 초기자금 1,000만 (WF는 100만), 슬리피지 0.1% + 수수료 0.05%.
> **선행 조치**: M15 결과는 전면 폐기 (오버트레이딩으로 -99% 속출). 모든 후속 분석은 H1 기준.
> **EMA200 레짐 필터**: `BacktestEngine.isAboveEma200()` 구현 완료. 현재가 > EMA200일 때만 BUY 진입 허용. SELL(청산)은 레짐 무관.

#### H1 FULL 백테스트 — EMA200 필터 적용 후 코인별 최고 성과

| 코인 | 최고 전략 | 수익률 | MDD | Sharpe | 거래수 | 변화 |
|------|-----------|--------|-----|--------|--------|------|
| **BTC** | COMPOSITE_BREAKOUT | **+106.71%** | -8.88% | 1.24 | 79 | ↑ (+7%, MDD 개선) |
| **ETH** | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | +58.00% | -13.31% | 0.75 | 150 | ↑ 소폭 개선 |
| **SOL** | COMPOSITE_BREAKOUT | +62.79% | -20.45% | 0.66 | 58 | ↑ (전략 교체) |
| **XRP** | COMPOSITE_MOMENTUM_ICHIMOKU | +1.04% | -24.22% | 0.09 | 104 | ↓ **EMA200 역효과** |
| **DOGE** | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | +124.77% | -30.75% | 0.87 | 173 | ↓ 소폭 감소 |
| **ADA** | COMPOSITE_BREAKOUT | **+86.98%** | -14.14% | 0.96 | 46 | 🆕 신규 발굴 |

> FAIR_VALUE_GAP은 H1에서도 BTC -69%, ETH -82%, ADA -77% 등 메이저 코인 전부 대파. **전략 자체 구조 문제로 판단, 배포 금지.**
> XRP는 EMA200 아래 구간에서도 수익 패턴이 존재 → EMA200 필터가 역효과. XRP는 CB 전략 자체 엣지로 운영.

#### Walk-Forward AGG_OUT — EMA200 필터 적용 (2022-01-01 ~ 2026-04-24)

| 코인 | CB | CM | CMI | CMI_V2 | 최고 | 비고 |
|------|-----|-----|------|--------|------|------|
| **BTC** | +3.68% | +1.86% | +1.99% | +1.99% | CB | 필터 후 WF 감소 (2026 기간 차이) |
| **ETH** | **+4.17%** | -4.63% | -4.63% | -5.64% | CB | CB만 양수 |
| **SOL** | +24.30% (MDD -8.2%) | +26.25% | **+26.64%** | +20.30% | CMI | 전략 모두 양수 ✅ |
| **XRP** | **+25.98%** | +1.37% | -5.70% | -7.97% | CB | CB만 양수 |
| **DOGE** | -22.44% | -5.19% | -11.96% | **+2.57%** | CMI_V2 | 필터 역효과 전반적 |
| **ADA** | **+34.76%** (MDD -4.0%) | -8.32% | -8.32% | -12.54% | CB | ⚠️ 거래수 12건, 신뢰성 낮음 |

#### 시장 레짐별 윈도우 패턴 (전 전략 공통)

| 윈도우 | 기간 | Out-Sample 경향 |
|--------|------|----------------|
| W0 | 2022 하반기 (하락장 끝) | 대부분 손실 (-5~-15%) |
| W1 | 2023 여름~가을 (횡보·약세) | **전 전략 손실** (-2~-10%) |
| W2 | 2024 여름 (회복 초입) | 혼재 |
| W3 | 2025 상반기 (강세장) | **전 전략 수익** (+5~+29%) |
| W4 | 2025 Q4~2026 Q1 (변동성 확대) | 코인별 혼재 |

> EMA200 필터로 SOL은 전 전략 WF 양수 전환. DOGE·XRP 일부 전략은 필터 역효과 — 코인별 특성 고려 필요.

---

## 🟢 배포 권고 / 🚨 배포 금지 (2026-04-30 MTF 백테스트 기준)

> H1 FULL 2022~2026-04-30 백테스트 결과 기반 (WF 재검증 미수행). MTF 3종 신규 전략 반영.

### Tier 1 — 백테스트 검증 통과, 소액 투입 가능

| 코인 | 권장 전략 | FULL 수익률 | MDD | Sharpe | 근거 |
|------|-----------|------------|-----|--------|------|
| **BTC** | **COMPOSITE_BREAKOUT** | **+106.71%** | -8.88% | 1.24 | 7전략 중 압도적 1위. MDD 최저 수준. |
| **ETH** | **COMPOSITE_MTF_BTC** | **+127.70%** | -7.24% | 1.35 | 7전략 중 1위 + MDD 최저. 기존 CB +30%에서 대폭 개선. |
| **SOL** | **COMPOSITE_REGIME_ROUTER** | **+65.38%** | -14.93% | 0.76 | CB +62.79%를 근소 상회, 레짐 자동 적응. |
| **DOGE** | **CMI_V2** | **+124.77%** | -30.75% | 0.87 | MTF 근접(+83%)하나 MDD 더 나쁨. 기존 전략 유지. |
| **ADA** | **COMPOSITE_BREAKOUT** | **+86.98%** | -14.14% | 0.96 | MTF_BTC -25%로 역효과. CB 압도적. |

### Tier 2 — 흑자 전환·신규 발굴, 관찰 후 투입

| 코인 | 권장 전략 | FULL 수익률 | MDD | 판단 |
|------|-----------|------------|-----|------|
| **XRP** | **COMPOSITE_MTF_CONFIRMED** | **+3.37%** | -15.67% | 모든 전략 손실 또는 근0 중 유일 흑자. 소액 관찰. |
| **AAVE** | **COMPOSITE_MTF_BTC** | **+28.15%** | -31.01% | 기존 -24.52% → 흑자 전환. MDD -31% 주의. |
| **BLUR** | **COMPOSITE_MTF_MOMENTUM** | **+48.06%** | -11.91% | Sharpe 0.91 양호. 거래수 39건 수용 수준. |
| **CHZ** | **COMPOSITE_MTF_BTC** | **+14.09%** | -14.88% | 기존 -23.77% → 흑자 전환. 소액 관찰. |

### 🚨 배포 금지

| 조합 | 사유 |
|------|------|
| **전 코인 × M15 타임프레임** | 오버트레이딩 + 수수료 잠식으로 -99% 속출. M15 전면 비활성화 |
| **전 코인 × FAIR_VALUE_GAP** | H1에서도 메이저 코인 -69~-82%. 전략 로직 자체 구조 문제 |
| **ETH × CB / V1 / V2** | CB +30%, V1/V2 +50~58%. MTF_BTC +127%에 크게 열위 |
| **XRP × MTF_BTC** | -21.74% — 가장 나쁜 조합. 절대 금지 |
| **ADA × MTF_BTC** | -25.78% — ADA에는 역효과 큼 |
| **CHZ × CRR / MTF_M / MTF_C** | -23~-27%. MTF_BTC만 흑자 |
| **AAVE × CB / V1 / V2** | -24~-48%. MTF_BTC만 흑자 전환 |
| **MOVE/SUPER/FLOCK/AXL/BIO/KERNEL** | 거래수 1~17건으로 통계 신뢰성 없음. 배포 금지 |

### 운영 세션 조치 사항 (2026-04-30 갱신)

- 🆙 **ETH 전환**: CB → **COMPOSITE_MTF_BTC** (+127.70%, MDD -7.24% — 7전략 최고)
- 🆙 **SOL 전환**: CB → **COMPOSITE_REGIME_ROUTER** (+65.38%, 자동 레짐 적응)
- 🟢 **BTC**: CB 유지 (+106.71%)
- 🟢 **DOGE**: CMI_V2 유지 (+124.77%)
- 🟢 **ADA**: CB 유지 (+86.98%)
- 🆕 **XRP**: MTF_CONFIRMED 소액 시작 (+3.37%, 유일 흑자)
- 🆕 **AAVE**: MTF_BTC 소액 시작 (+28.15%, 흑자 전환)
- 🆕 **BLUR**: MTF_MOMENTUM 소액 시작 (+48.06%, Sharpe 0.91)
- 🆕 **CHZ**: MTF_BTC 소액 관찰 (+14.09%, 흑자 전환)

---

## 다음 할 일

### 🔴 P1-1 — 전략 고도화

- [x] **FVG A단계 구현 완료**: `FairValueGapStrategy` + `FairValueGapConfig`. EMA 필터·최소 공백 크기 필터 포함.
- [x] **FVG A단계 5코인 × H1 3년 백테스트**: SOL +224% MDD -34% 유일 유의미. BTC/DOGE/XRP/ETH 모두 현재 전략 대비 열위.
- [ ] **FVG 전략 — B단계**: 평균 회귀 방식. FVG 존(상·하한) 상태 관리 → 이후 가격이 공백 구간 재진입 시 신호 발생. 오래된 존 만료 처리 포함.
- [ ] **STOCHASTIC_RSI 구조적 개선** — StochRSI + RSI 다이버전스 결합. RSI 다이버전스 발생 + StochRSI 과매도 탈출 동시 충족 시 고신뢰 매수 신호.
- [x] **VOLUME_DELTA 테스트 작성** (13개 전체 통과)

---

### 🔴 P1-2 — Self-Audit 미완 서브항목 (`docs/20260415_analy.md` 기반)

> Tier1~4 구현은 완료됐으나 각 항목의 세부 서브태스크 중 미구현 항목.

- [x] **SL/TP intra-H1 path 정확도 향상** (§3) — `ExitRuleChecker.checkCandleExitWithPath()` 신규 구현. OHLC 4-point 경로 재구성으로 H1 캔들 내 SL/TP 도달 순서를 결정. BacktestEngine에서 `checkCandleExit` 대체.
- [x] **SL/TP 동시 터치 Monte Carlo** (§3) — `resolveByMonteCarlo()` 구현. 경로 재구성으로도 순서 불확정 시(Doji 등) Monte Carlo 200회 시뮬레이션으로 SL/TP 선도 확률 결정.
- [x] **리스크 구간 손실 재정의** (§5) — 글로벌 포트폴리오 드로우다운 체크 추가. `RiskEngine.check()` 6-파라미터 오버로드, `RiskManagementService.calculatePortfolioDrawdownPct()`, V48 마이그레이션, `RiskConfigEntity` 필드 추가.
- [x] **WeightOverrideStore DB 이력 저장** (§6) — `weight_optimizer_snapshot` 테이블(V49), `WeightOptimizerSnapshotEntity`, `WeightOptimizerSnapshotRepository`, `StrategyWeightOptimizer.saveSnapshot()` + `restoreFromSnapshot()` 구현.
- [~] **단일 전략 백테스트 기간 분리 문서화** (§12) — 복합 전략은 2026-04-24 Walk-Forward(In-Sample 학습/Out-of-Sample 검증 5윈도우)로 과적합 여부 정량 평가 완료 (SOL/CMI_V2 -98% 저하 등 식별). **단일 전략 11종에 대한 동일 WF 실행은 미진행** — 필요 시 별도 태스크.
- [x] **2022 약세장 데이터 수집 + 재백테스트** (§13) — 2026-04-24 FULL 백테스트 및 WF 모두 **2022-01-01 시작**. W0(2022 하반기 하락장 말미) OOS 구간에서 전 전략 손실(-5~-15%) 확인 → 레짐 필터 필요성으로 연결. 크립토 Winter 견고성 평가 완료.
- [ ] **테스트 커버리지 보강** (§15) — `BacktestJobService` · `PaperTradingService` · `SignalQualityService` 전용 테스트 작성.
- [ ] **세션별 에러 카운트 대시보드** (§16) — Prometheus Counter 기존 구성됨. Grafana 대시보드 패널 추가 필요.
- [ ] **로그 중앙화** (§16) — Loki 또는 CloudWatch Logs 연동. 현재 Docker logs grep 수준 → 운영 스케일 부족.
- [ ] **API key rotation 정책 수립** (§18) — Upbit Access/Secret Key 주기적 교체 프로세스 + IP 화이트리스팅 적용 여부 재확인.

---

### 🟡 P2-0 — 실전 테스트 및 전략 검증 (2026-04-24 EMA200 필터 WF 재검증 반영)

> EMA200 레짐 필터 적용 후 WF 재검증 결과 기반. 이전 가이드 폐기.

- [ ] **ETH 전략 전환: CB → COMPOSITE_MTF_BTC** — FULL +127.70%, MDD -7.24%, Sharpe 1.35. 7전략 중 압도적 1위.
- [ ] **SOL 전략 전환: CB → COMPOSITE_REGIME_ROUTER** — FULL +65.38%, 레짐 자동 적응. CB +62.79% 근소 상회.
- [x] **XRP COMPOSITE_BREAKOUT 유지** — 필터 후에도 CB +25.98% 유지. 운영 변경 없음.
- [ ] **DOGE CMI_V2 유지 + EMA200 예외 처리 검토** — DOGE는 EMA200 아래에서도 수익 패턴 존재. 코인별 필터 on/off 설정 기능 또는 DOGE 전용 예외 로직 필요.
- [ ] **ADA COMPOSITE_BREAKOUT 소액 관찰** — FULL +87%, WF +34.76% 우수하나 WF 거래수 12건으로 신뢰성 부족. 소액 세션 시작 후 거래 누적 관찰.
- [ ] **FAIR_VALUE_GAP 전략 코드 리뷰 또는 폐기 결정** — 모든 타임프레임 × 모든 메이저 코인에서 구조적 손실. B단계 구현 전에 A단계 로직 방향성 재검증 필수.
- [ ] **M15 타임프레임 전 세션 비활성화** — H1 전용으로 운영 표준화.
- [x] **EMA200 레짐 필터 PoC (백테스트)** — `BacktestEngine.isAboveEma200()` 구현 완료. SOL 전 전략 WF 양수 전환 확인. DOGE 역효과 확인 → 코인별 예외 처리 과제로 분리.
- [x] **EMA200 레짐 필터 실전 적용** — `LiveTradingService.isAboveEma200Live()` 구현 완료. CANDLE_LOOKBACK 100→250 증가. DOGE 예외 처리 포함 (coinPair.contains("DOGE") 조건). SELL 신호 영향 없음.
- [ ] **실전매매 금액 증액** — 소액 1만원 → 5만원 → 10만원 단계적 증액. 기준: 2주 이상 운영 + 승률 ≥ 50% + MDD < 10%

---

### ⏳ 장기 검토

**전략·엔진 고도화**
- [ ] **멀티 타임프레임** — 1H 방향 + 15M 진입. 아키텍처 변경 큰 편.
- [ ] **동적 가중치 완성** — 인프라(`WeightOverrideStore` + `StrategySelector`) 구축 완료. 100거래 이상 샘플 기반, 하한선 0.05, 스무딩 70/30 적용 예정.
- [ ] **칼만 필터 스캘핑 전략 (5m/15m)** — H1은 노이즈 적어 효용 낮음. 선행 조건: 수수료 시뮬레이션 + FVG A/B 완료 후.
- [ ] **LiveTradingService 분리** (graphify God Node) — OrderExecutionService / SessionLifecycleService / RiskMonitorService 3분리.

**통계·검증 고도화** (analy.md Tier5-A)
- [ ] **Deflated Sharpe / PBO** — 다전략 튜닝 선택 편향 보정.
- [ ] **Bootstrap 신뢰구간** — 3년 백테스트 결과 95% CI 산출.
- [ ] **Benchmark 비교** — HODL BTC·ETH 대비 alpha·beta 분리.

**포트폴리오 확장** (analy.md Tier5-B)
- [ ] **포트폴리오 알로케이션** — 다중 코인/전략 correlation 기반 자금 분배 (현재 코인당 독립).
- [ ] **Risk Parity / Kelly Fractional** — 현재 고정 `investRatio` 개선.
- [ ] **라이브 A/B 테스트 프레임워크** — 새 전략 소액 병행 + 통계적 차이 자동 판정.

**데이터·인프라** (analy.md Tier5-C/D)
- [ ] **Historical data 2018~2022** — 약세장 데이터 적재 (최소 BTC·ETH).
- [ ] **Auto re-optimization** — 주간 walk-forward 재실행 → StrategyRegistry 자동 업데이트 제안.
- [ ] **Telegram/Discord 명령어** — `/stop ETH`, `/pnl today`, `/emergency` 원격 제어.

---

## 서버 명령어

### 로컬 (Windows)

```bash
docker compose up -d                                                       # DB + Redis 시작 (로컬은 비밀번호 없음)
./gradlew :web-api:bootRun --args='--spring.profiles.active=local'        # 백엔드 (포트 8080)
cd crypto-trader-frontend && npm run dev                                   # 프론트엔드 (포트 3000)
```

### 운영 (Ubuntu)

```bash
cd ~/crypto-auto-trader

# 재빌드 & 재시작
docker compose -f docker-compose.prod.yml up -d --build           # 전체
docker compose -f docker-compose.prod.yml up -d --build backend   # 백엔드만
docker compose -f docker-compose.prod.yml up -d --build frontend  # 프론트엔드만

# 로그 실시간 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f frontend

# 오류 원인 분석 (ERROR/Exception 필터링)
docker compose -f docker-compose.prod.yml logs backend > /tmp/backend.log 2>&1
grep -n "ERROR\|Caused by\|Exception" /tmp/backend.log | tail -30
```

---

## 🔬 2026-06-01 전략 전체 분석 (Strategy-wide Audit)

> 범위: 기본 지표 14종 + 복합 프리셋 11종 + 라이브 신호 파이프라인 전체.
> 목적: 실전 ~본전/약손실 + 99.6% HOLD의 구조적 원인 규명 및 우선순위 도출.

### 1. 인벤토리 (실제 등록 기준)
- **기본 지표 14종** (`StrategyRegistry`): VWAP, EMA_CROSS, BOLLINGER, GRID*, RSI, MACD, SUPERTREND, ATR_BREAKOUT, ORDERBOOK_IMBALANCE, VOLUME_DELTA, STOCHASTIC_RSI, FAIR_VALUE_GAP, MACD_STOCH_BB*, TEST_TIMED  (*=stateful)
- **복합 프리셋 11종** (`CompositePresetRegistrar` @PostConstruct): COMPOSITE, COMPOSITE_REGIME_ROUTER, COMPOSITE_MOMENTUM, COMPOSITE_ETH, COMPOSITE_BREAKOUT, COMPOSITE_MOMENTUM_ICHIMOKU(_V2), COMPOSITE_BREAKOUT_ICHIMOKU, COMPOSITE_MTF_CONFIRMED/_BTC/_MOMENTUM
- ⚠️ 이전 메모상의 `StrategyFactory`/`CMI_V1`/`TADA`는 **실제 코드에 없음** (오기억 정정). CompositeRegimeRouter는 내부에서 delegate를 직접 생성.

### 2. 거버넌스 갭 (StrategyLiveStatusRegistry)
- ENABLED(4): COMPOSITE_BREAKOUT, COMPOSITE_MOMENTUM, COMPOSITE_MOMENTUM_ICHIMOKU, _V2
- BLOCKED(4): STOCHASTIC_RSI, MACD, MACD_STOCH_BB, COMPOSITE_BREAKOUT_ICHIMOKU / DEPRECATED(1): TEST_TIMED
- **갭1**: `isBlocked()=BLOCKED||DEPRECATED` 만 검사 → "단독 미검증(EXPERIMENTAL)" 단일지표(VWAP·RSI·BOLLINGER·GRID 등)도 라이브 세션 생성 **그대로 허용**. 라벨이 강제력 없음.
- **갭2**: 주력 메타전략 `COMPOSITE_REGIME_ROUTER` + MTF 3종이 매트릭스 **미등록** → 기본 EXPERIMENTAL.

### 3. 신호 파이프라인 — 앙상블 아님
- 라이브(`LiveTradingService.evaluateAndExecuteSession` ~775행)는 세션의 단일 `strategyType` 하나만 `evaluate()`.
- **`StrategySelector`의 레짐별 가중 앙상블(BREAKOUT 0.65+MOMENTUM 0.35 등)은 라이브 실행 경로에서 호출되지 않음** (가중치 최적화용일 뿐). 문서/설계 ↔ 실행 불일치.

### 4. 구조적 HOLD 편향 (처리량 킬러)
라이브 BUY 1건에 필요한 직렬 게이트(곱셈적 누적):
1. 레짐: RANGE→즉시 HOLD / TRANSITIONAL→V1인데 MACD adxThreshold=25라 MACD 무조건 침묵 → 남은 0.5로 0.4 임계 돌파 불가
2. CompositeStrategy 동적 ADX 필터(15~25)
3. 하위지표 합산 score>0.4 (단일 보조지표 단독 돌파 수학적 불가 — P1 기확인)
4. EMA20/50 방향 필터
5. Ichimoku 구름 필터(V1/V2)
6. RSI Veto(>75, BREAKOUT)
7. LiveTradingService EMA200 필터 (**BUY만** 차단 → 매수 비대칭)

중복: **EMA 3중**(EMA_CROSS 하위 / EMA20-50 / EMA200), **ADX 2중**(Composite / MACD내부).

### 5. 죽은/휴면 코드
- 단일지표 5종(BOLLINGER, STOCHASTIC_RSI, FVG, MACD_STOCH_BB, ORDERBOOK_IMBALANCE)은 어떤 ENABLED 복합의 하위지표도 아님 → 실질 휴면
- `SignalEvaluationService` 참조 0건 → 데드코드 후보
- StrategySelector(RANGE 매매) ↔ CompositeRegimeRouter(RANGE 진입금지) 모순 공존

### 6. 작업 우선순위 (BacktestEngine 검증 후 적용 원칙)
- [x] **P1-A**: TRANSITIONAL 위임 시 MACD adxThreshold를 **코인 선택적(BTC/SOL만 25→20)** 으로 주입(CompositeRegimeRouter, putIfAbsent·원본 불변). 전역 20은 XRP를 망가뜨려(검증), 검증서 개선 확인된 코인만 화이트리스트. 3년 H1 다코인 검증 통과 — BTC·SOL 개선·나머지 무변동(§7). ✅ 라이브 반영 가능.
- [x] **P1-B**: EMA200 게이트를 core-engine `Ema200RegimeGate` 단일 진실 소스로 통합. LiveTradingService·BacktestEngine 중복 제거, DOGE 예외를 게이트에 명문화 → 백테스트↔라이브 정합(이전엔 라이브에만 DOGE 예외 존재). 회귀 테스트 5건 통과, 전체 빌드 그린(2026-06-01). ⚠️ 백테스트에 DOGE 예외가 새로 반영되므로 DOGE 백테스트 수치 재확인 필요
- [x] **P2-A** (등재만): ROUTER/MTF_BTC/MTF_MOMENTUM를 배포 티어1 근거로 ENABLED 등재, MTF_CONFIRMED는 티어2라 EXPERIMENTAL 명시. isBlocked()는 현행 유지(EXPERIMENTAL 미차단 → 운영 리스크 회피). 회귀테스트 보강·전체 빌드 그린(2026-06-01)
- [x] **P2-B** (문서화): StrategySelector는 데드코드 아님 — `COMPOSITE`(RegimeAdaptiveStrategy) 전략·COMPOSITE 백테스트(BacktestService)가 실사용. 실제 사실은 **레짐 앙상블 2중 구현 공존**: ① StrategySelector 기반 `COMPOSITE`(가중 투표, WeightOverrideStore 동적가중) ② `CompositeRegimeRouter`(레짐별 단일 delegate 위임). 세션 단일 strategyType 평가 경로는 어느 앙상블도 안 거치고 지정 전략만 evaluate. 삭제는 빌드 파손 → 보류. 향후 통합 과제로 남김(범위 큼, 백테스트 재검증 필요).
- [x] **P3** (기록만, 코드 미변경): 휴면 단일지표 5종(BOLLINGER·STOCHASTIC_RSI·FVG·MACD_STOCH_BB·ORDERBOOK_IMBALANCE)은 어떤 ENABLED 복합의 하위지표도 아님 = 실질 휴면이나, 단독 EXPERIMENTAL로 라이브·백테스트 생성은 가능. 향후 복합전략 재료가 될 수 있어 **제거하지 않고 보존**. `SignalEvaluationService`는 코드 전체 참조 0건 확인 — 별도 데드코드 정리 후보로 기록(이번엔 미변경).

### 7. 백테스트 검증 결과 (2026-06-01, 실DB H1 2023~2025, 5코인)
> 하니스: `web-api/.../backtest/P1ChangesBacktestVerification.java` (JDBC 직접 로드, DB 미접속 시 자동 skip). CompositeRegimeRouter를 adxThreshold=25 명시(=변경전) vs 미주입(=변경후 자동20)으로 비교.

**P1-A (TRANSITIONAL adxThreshold 25→20) — 코인 선택적 효과, 전역 적용 부적절:**
| 코인 | 변경전(25) | 변경후(20) | 판정 |
|---|---|---|---|
| BTC | +13.7% T72 MDD-12.7% | +20.0% T96 MDD-13.0% | ✅ 개선 |
| ETH | +42.3% T81 MDD-13.9% | +45.3% T94 MDD-17.0% | ⚠️ 수익↑/MDD악화 |
| SOL | +70.5% T77 MDD-13.6% | +91.3% T91 MDD-12.1% | ✅ 수익↑+MDD↓ |
| XRP | +0.6% T56 MDD-14.3% | **-14.4%** T73 MDD-18.5% | ❌ 명확 악화 |
| DOGE | +54.5% T106 MDD-25.3% | +52.2% T116 MDD-26.5% | ⚠️ 소폭 악화 |
- 진입 빈도는 전 코인 증가(예상대로). BTC·SOL 명확 개선, **XRP는 망가짐**(추세 모호 코인).
- → **코인별 차등 적용으로 수정 완료** (`ADX_RELAX_COINS = [BTC, SOL]` 화이트리스트). 화이트리스트만 20, 그 외 25 유지.

**P1-A 차등 재검증 (2026-06-01, 동일 하니스):**
| 코인 | 변경전(25) | 차등적용후 | 적용 |
|---|---|---|---|
| BTC | +13.7% T72 MDD-12.7% | +20.0% T96 MDD-13.0% | 20(완화) ✅개선 |
| SOL | +70.5% T77 MDD-13.6% | +91.3% T91 MDD-12.1% | 20(완화) ✅수익↑MDD↓ |
| ETH | +42.3% T81 MDD-13.9% | +42.3% T81 MDD-13.9% | 25(유지) =무변동 |
| XRP | +0.6% T56 MDD-14.3% | +0.6% T56 MDD-14.3% | 25(유지) =무변동(보호) |
| DOGE | +54.5% T106 MDD-25.3% | +54.5% T106 MDD-25.3% | 25(유지) =무변동 |
- BTC·SOL 개선 유지 + ETH/XRP/DOGE는 변경전과 **완전 동일**(XRP -15% 악화 차단 확인). 순개선만 남고 악화 0. ✅ **라이브 반영 가능 상태.**

**P1-B (EMA200 게이트 DOGE 예외) — 정합 달성, 회귀 없음:**
- DOGE: BUY허용 26006/26006(100%) vs 순수규칙 11336(43.6%) → 예외 +56.4%p 정상 작동.
- BTC/ETH/SOL/XRP: 적용=면제 완전 동일 → 비-DOGE **무영향(회귀 없음)** 확인. ✅ **그대로 유지 권장.**

### 8. 🟢 운영 반영 & 관찰 중 (2026-06-01 ~, 2~3주)
> 코드 운영서버 빌드·반영 완료. 사용자가 5코인 소액 실거래 세션을 수동 생성·가동. H1 고정.

**가동 라인업:**
| 코인 | 전략 | 원금 | 이번 변경 발동 |
|---|---|---|---|
| KRW-BTC | COMPOSITE_BREAKOUT | 10만 | — (ROUTER 미사용) |
| KRW-ETH | COMPOSITE_MTF_BTC | 10만 | — |
| KRW-SOL | COMPOSITE_REGIME_ROUTER | 10만 | ✅ **P1-A 발동** (SOL 화이트리스트) |
| KRW-DOGE | COMPOSITE_MOMENTUM_ICHIMOKU_V2 | 5만 | ✅ **P1-B 발동** (EMA200 예외) |
| KRW-ADA | COMPOSITE_BREAKOUT | 10만 | — |
- XRP는 의도적 제외(P1-A 검증서 -15% 악화). DOGE는 MDD-30.8% 최악 → 원금 절반.

**3주 후 판단 체크포인트:**
- [ ] **P0 체결가 재발 점검** — 청산 PnL이 또 -4원(수수료만)이면 P0 수정 미반영. 청산 1건이라도 나오면 즉시 확인.
- [ ] **SOL P1-A 작동 증거** — 신호 로그 `[TRANSITIONAL]` BUY가 이전보다 발생하는지.
- [ ] **DOGE P1-B 작동 증거** — EMA200 아래 구간 BUY 진입 + MDD 추이.
- [ ] **백테스트↔실거래 괴리** — 승률·평균손익 (과거 13배 괴리 이력).
- 기준 충족 시 → 나머지(BTC/ETH/ADA) 확대. `docs/logs/` CSV 수집해두면 교차분석 가능.

**미커밋:** 이번 작업분(P1-A/P1-B/P2-A + 하니스 + 이 문서)은 운영 반영됐으나 **git 미커밋** 상태. 작업 브랜치 생성 후 커밋 권장.

**후속 과제 (이번 미적용):**
- P1-A 화이트리스트 ETH 추가 검토(수익↑/MDD악화 트레이드오프).
- P2-B StrategySelector↔CompositeRegimeRouter 레짐 앙상블 2중 구현 통합.
- `SignalEvaluationService` 데드코드(참조 0건) 정리.

### 9. 🔎 실전 이력 분석 + 주문 로그 조회 개선 (2026-06-15)

**분석 (docs/anal_data CSV 3종):**
- **KRW-ADA(포지션879, 세션153) 허위 미실현 손익**: 6/9 매도가 **계속 FAILED** → `reconcileClosingPositions`가 OPEN 롤백 → 무한 재시도. 포지션이 OPEN으로 남아 `updateSessionUnrealizedPnl` 시가평가가 멈추지 않음(세션 허위 +5.31%). **실제 FAILED 사유는 주문 `failedReason`/Upbit 응답 확인 필요** (유력: `resolveAskVolume` 잔고 잠김 / invalid_volume_ask).
- **손익 데이터 오염**: CLOSED 193건 중 143건 realizedPnl = -4원(매도수수료만) "가짜 본전", 44건 0원 → 성과지표 신뢰 불가. (P0 방어코드는 추가됐으나 과거 오염 레코드 미복구.)
- **신호 품질**: 561,800건 중 99.6% HOLD, LIVE 실체결 BUY 15·SELL 9건뿐. 4h/24h 사후수익률 컬럼 거의 null(백필 미동작).

**구현 — Upbit 주문 로그 화면(`settings/upbit-logs`) 조회 개선:**
- 날짜 **"직접 지정" 프리셋 + from~to 날짜 입력** (특정일 조회).
- 페이지네이션 **처음/끝 버튼 + 페이지 번호 직접 입력** 점프.
- **CSV(Excel) 내보내기** "엑셀로 받기" 버튼 — 현재 날짜·세션 필터 반영.
  - BE: `GET /api/v1/export/csv/live-trading/orders?sessionId&dateFrom&dateTo`, `CsvExportService.exportLiveTradingOrders`, `OrderRepository` non-paged 조회 3종. FE: `csvExportApi.liveTradingOrders`.
- web-api 컴파일 ✅ / 프론트 tsc(변경파일) ✅.

**후속:**
- [x] ADA FAILED 사유 확인 후 매도 실패 근본 원인 수정. → §10
- [ ] 상태/방향 필터 **서버측 쿼리화**(현재 클라이언트 측 = 현재 페이지 내에서만 필터).
- [ ] 오염된 CLOSED 손익 1회성 보정 스크립트.

### 10. 🛠 ADA 팬텀 포지션(체결을 취소로 오기록) 버그 수정 — 3중 방어 (2026-06-15)

**근본 원인 (주문 3803 기준 확정):** 시장가 손절 매도가 거래소에서 실제 체결됐는데, **주문 5분 타임아웃 자동취소**가 동작 → 이미 `done`이라 거래소 취소 API가 실패하지만 `cancelOrder`가 **로컬 상태를 무조건 CANCELLED로 박음** → `reconcileClosingPositions`가 포지션 OPEN 롤백 → DB는 보유 중인데 거래소엔 코인 없음 → 무한 재매도(잔고없음 FAILED) + 허위 미실현 손익.

**수정 (3중 방어):**
- **(A) 취소 직전 체결 재확인** — `OrderExecutionEngine.pollActiveOrders` 타임아웃 분기에서 취소 전 거래소 상태 재조회, `done`이면 취소 대신 `syncOrderState`로 체결 처리.
- **(B) 취소 실패 시 체결 의심** — `OrderExecutionEngine.cancelOrder` catch에서 거래소 재조회, FILLED/executed>0이면 CANCELLED 대신 체결 처리(체결을 취소로 오기록하는 경로 차단).
- **(C) 팬텀 포지션 안전망** — `LiveTradingService.reconcilePhantomPositions`(60초): OPEN인데 거래소 보유량(free+locked)이 DB 기대량의 5% 미만이면 팬텀으로 간주, **3회 연속(≈3분)·보유 10분↑** 확인 후 CLOSED 확정 + 세션 KRW 복원 + 텔레그램 경고. 추정 체결가 = 최근 FILLED 매도가 → 손절가 → 최신 캔들 종가 → 평균단가 순. **현재 멈춰있는 ADA 879도 가동 시 자동 정리됨.**
- **(C-역) 추적 안 되는 잔고 감지(경고만)** — `detectUntrackedBalances`: 거래소 보유량이 DB 추적량(OPEN size>0 + CLOSING)의 110% 초과 + **최근 24h FAILED/CANCELLED 매수 주문 존재**(dust·수동입금 구분)일 때, 3회 연속·6시간 쿨다운으로 텔레그램 경고. **매수 체결이 실패로 오기록된 거울 케이스 대응. 자동 청산/매도/포지션 생성은 안 함**(사용자 선택: 경고만).
- 진행 중 매도는 코인이 locked → 보유량>0 이라 (C)에서 자연 제외. 매수/매도 양방향 = A·B(주문 단위) 대칭 + C(잔고 대조)는 매도방향 자동청산·매수방향 경고. web-api 컴파일 ✅.

**후속:**
- [ ] 배포 후 ADA 879 자동 청산 로그/텔레그램 확인 (추정 손익 실제값 대조).
- [ ] 시장가 청산(SELL) 주문을 5분 타임아웃 자동취소 대상에서 제외하는 옵션 검토(추가 안전).
- [ ] (C-역) 경고 빈발 시 → 포지션 자동 복구 또는 자동 청산으로 승격 검토.

### 11. 📥 실전매매 세션/포지션 CSV — 세션별·다중 선택 다운로드 (2026-06-15)

분석용으로 **운영 여부 무관 과거 세션 포함** 세션별/다중 선택 export 지원.
- BE: `exportLiveTradingSessions(Collection<Long>)` / `exportLiveTradingPositions(Collection<Long>)` — `sessionIds` 지정 시 해당 세션만, 미지정 시 전체(기존 동작). 컨트롤러 `?sessionIds=1&sessionIds=2` 반복 파라미터. (세션 export는 원래도 전 상태 포함이었음 — 빠진 건 **선택 필터**였음.)
- FE: `trading/history` 테이블에 **체크박스 컬럼 + 전체선택** 추가. "세션 CSV (전체/N)" · "포지션 CSV (전체/N)" 버튼이 선택분만/전체 다운로드. `csvExportApi.liveTradingSessions/Positions(sessionIds?)`.
- **Upbit 주문 로그(`settings/upbit-logs`)도 다중 세션화**: 세션 필터를 단일 `<select>` → **체크박스 다중 선택 팝오버**로 교체. 선택분이 목록 조회·CSV 양쪽에 반영(미선택=전체). BE `getOrders`/`exportLiveTradingOrders`가 `sessionIds`(List) 수용, `OrderRepository`에 `...SessionIdIn...` 페이징/비페이징 쿼리 4종 추가. FE `tradingApi.getOrders(…, sessionIds[], …)`·`csvExportApi.liveTradingOrders(sessionIds[], …)`는 `sessionId=3&sessionId=5` 반복 파라미터로 직렬화.
- web-api 컴파일 ✅ / 변경 파일 tsc ✅ (그 외 tsc 에러는 기존 무관 파일).

### 12. 🗂 세션 soft-delete + 통합 세션 인덱스 + 전략로그 CSV/콤보박스 (2026-06-15)

**근본 원인:** `deleteSession`이 행을 hard-delete하고 **주문·포지션의 session_id를 NULL로** 만들어, 삭제된 세션이 이력·선택지에서 사라지고 주문이 미귀속됐음. (전략로그 session_id는 보존되고 있었음.)

- **soft-delete 전환** — `LiveTradingService.deleteSession`: `deleteById`+session_id NULL 처리 제거 → `status="DELETED"`로만 표시(링크 보존). 앞으로 삭제 세션도 이력·주문로그·전략로그에서 번호로 선택·조회·CSV 가능. 리컨실러는 RUNNING/CREATED/OPEN만 조회하므로 DELETED 무시(안전). **이미 hard-delete된 과거 세션은 주문이 NULL이라 주문로그엔 안 뜨지만, 전략로그는 session_id 보존돼 /logs·콤보박스에 DELETED로 노출됨.**
- **통합 세션 인덱스** — `LiveTradingService.getSessionIndex()` → `GET /api/v1/trading/sessions/index`. 라이브 세션 테이블(DELETED 포함) + `StrategyLogRepository.findDistinctSessionRefs()`(로그에만 있는 삭제/모의 세션) 병합, sessionId 내림차순. 항목: `{sessionId,strategyType,coinPair,status,sessionType}`.
- **upbit-logs**: 세션 팝오버 소스를 `listSessions` → `sessionIndex`(모의 제외)로 교체. `삭제됨` 배지 추가.
- **/logs(전략로그)**: 세션ID 텍스트 입력 → **콤보박스**(`#156 STRATEGY(COIN) 운영중` 형식, sessionIndex 기반, 구분 필터 연동) + **CSV 다운로드** 버튼 추가. BE `CsvExportService.exportStrategyLogs(sessionType, sessionId)` + `GET /api/v1/export/csv/strategy-logs`, `StrategyLogRepository` 비페이징 finder 3종.
- **trading/history**: `DELETED` 상태 라벨/스타일 추가, 삭제 세션은 재삭제 버튼 비활성. (soft-delete 후 종료 세션이 이력에 잔존.)
- web-api 컴파일 ✅ / 변경 파일 tsc ✅.

**한계/후속:**
- [ ] 이미 hard-delete된 과거 세션의 **주문 로그**는 session_id가 NULL이라 세션별 복구 불가(전략로그는 조회 가능). 필요 시 1회성 보정 검토.

### 13. 🔎 동적 멀티코인(DYNAMIC) 운영 로그 분석 — 매수 실행 0건 지속 (2026-07-22)

**운영 DB 조회 결과 (strategy_log session_type='DYNAMIC' 107,130건, 세션 38개 중 RUNNING 7개=id 32~38, M15, 자본 1만원):**
- 신호 분포: HOLD 97,531 / SELL 9,536 / **BUY 63 (0.06%)** — `was_executed`는 전량 false, **DYNAMIC 주문·포지션 0건**. 7/9 가동 이후 13일간 실거래 없음(available_krw=초기자본 그대로).
- **BUY 63건 전부 진입 게이트에서 차단**: BLACK_SWAN_GUARD 46 · EMA200(-3%) 15 · RANGE 레짐 2. 2026-07-15 2차 완화(weak 0.20, EMA 감쇠 0.7, EMA200 마진 3%) + 2026-07-21 감액 진입에도 결과 동일 — 병목이 점수 임계에서 **게이트로 이동**했을 뿐.
- **구조적 충돌**: 통과 가능한 BUY는 대부분 VWAP:BUY(100) 단독(=급락 후 이격) 패턴인데, 이 상황이 곧 BLACK_SWAN(1h -5%) 발동 조건이라 서로 상쇄. 사후수익률 검증: BLACK_SWAN 차단분 avg 4h -2.47% / 24h -5.60% (**차단이 대체로 옳았음**, 단 7/21 KRW-LA 8건은 +4~+6.5%로 기회손실). EMA200 차단분은 4h +0.50%/24h -0.32%로 중립.
- SELL 9,536건은 포지션 없는 SCANNING 중 발생한 실행 불능 신호(세션33 PULLBACK_MTF가 6,576건으로 로그의 63%) — 노이즈.
- 세션 38(MEANREV_BB, 7/20~)은 워치리스트가 KRW-XRP 1개뿐 + 781건 전량 '점수 미달' — 후보 필터(min_atr 0.5/spread 0.1)가 구형 기본값이라 후보 고갈 의심.

**조치 — BLACK_SWAN 3단계 진입 게이트 완화 (2026-07-22 구현):**
- `BlackSwanGuard.entryGate()` 신설 (하드 차단 `check()`는 보유 포지션 SL 강화 등 기존 경로용으로 유지):
  - 1시간 낙폭 > -5% → **정상 사이즈(1.0)**
  - **-8% < 낙폭 ≤ -5% → 감액 진입(0.5배)** ← 신규 완충 구간(기존엔 전량 차단)
  - 낙폭 ≤ -8% → **하드 차단** (나이프 캐칭·LUNA/FTX류 방어 유지)
  - 거래량 급증 조기경보(5배 + -2%)는 낙폭 무관 하드 차단 유지
- `DynamicTradingService` SCANNING 루프: 블랙스완 감액 배수를 EMA200 감액 배수와 **곱으로 중첩**(0.5×0.5=0.25), executeBuy의 최소주문액(5,000원) 보정이 소액 세션 진입을 살림.
- `EntryGate`는 기존 2단계 `check` 대비 **단조 완화**(−5~−8% 구간만 차단→감액). 회귀 테스트 6종 추가, `core-engine:test` + `web-api:compileJava` ✅.
- **주의**: 차단분 24h 사후수익률 -5.6%로 완충 구간도 손실 기대값 구간임 — 감액(0.5배)으로 노출을 절반 제한하되, 배포 후 실제 진입분의 손익을 관찰해 완충 폭(-8%)·감액 배수 재조정 필요.

**후속 검토:**
- [ ] 배포 후 완충 구간 감액 진입분의 실제 손익 관찰 → -8% 경계·0.5배 배수 튜닝.
- [ ] 진입 신호원이 사실상 VWAP 단독 — MACD/GRID가 M15에서 거의 0점인 원인(파라미터 스케일) 점검.
- [ ] 세션 38 워치리스트 1개 문제: min_atr/max_spread 파라미터 신·구 기본값 불일치 확인.

### 14. 🔎 실전매매(LIVE) 세션 운영 로그 분석 (2026-07-22)

**전체 현황 (live_trading_session 38개):** RUNNING 4 · EMERGENCY_STOPPED 12 · STOPPED 7 · DELETED 15.
- **RUNNING 4개 모두 정상 가동 중**(마지막 로그 0시간 전): 188(MTF_CONFIRMED/XRP), 190(MOMENTUM_ICHIMOKU/XRP), 192(MTF_BTC_STRICT/BTC), 193(MTF_BTC/ADA). 전부 자본 1만원.
- **최근 30일 실현손익 합계 -9,221원** (청산 62건, 승 19 / 패 43 = **승률 31%**, 평균 이익 +250 / 평균 손실 -333). 손실 우위 시스템.

**세부 관찰:**
- **DOGE 반복 손실**: 세션 177(MTF_MOMENTUM/DOGE) -3,807원(5연패, 각 -3.4~3.6%)이 단일 최대 손실. DOGE 누계 177+181(-1,740)+189(-591)+175(-20). CMI_V2/DOGE 세션 189는 EMA200 게이트 면제였다가 5연속 손절 후 -5.91% 비상정지(§Ema200RegimeGate 주석의 DOGE 예외 제거 근거와 일치). **DOGE는 화이트리스트 제외 검토 필요.**
- **비상정지 12건 중 다수는 실손실 아님**: 2026-07-06 `EXCHANGE_DOWN`(Upbit API 연속 3회 실패) 캐스케이드로 세션 184/185/187 등이 +0.00~-0.9%에서 일괄 비상정지됨. 실제 리스크로 멈춘 건 189(-5.91%)·191(-2.08%)·177(-12.69%) 등 소수.
- **세션 188·190 동시 중복 진입**: 두 세션이 **같은 초(2026-07-21 05:05:38)에 KRW-XRP를 동일가(1,655)·동일수량(4.83)** 매수. 서로 다른 전략이지만 같은 코인·타이밍이라 사실상 XRP 2배 노출. 현재 각 +0.91% 미실현. 다전략 세션이 같은 코인에 몰릴 때 **포트폴리오 상관 리스크** 관리 부재.
- **손실 청산이 대부분 SL(-1~-5%)**: 청산 상세에서 이익 청산은 소액(+250 평균)인데 손실은 SL 풀히트(-3~-5%)가 많음 — 익절이 손절보다 빨라 **손익비 역전**(평균손실 > 평균이익). 손절 -5%는 소액(1만원) 세션에 과대.
- SELL 신호 차단 사유 1위 "청산할 포지션 없음" 880건(노이즈), 2위 "본전 청산 차단"(pnl < +0.30%) — 손실 포지션을 SL까지 끌고 가는 정책이 평균손실을 키우는 구조와 연동됨.

**후속 검토 (사용자 결정 필요):**
- [ ] DOGE 화이트리스트 제외 (누적 최대 손실원, EMA200 면제 반증 이력).
- [ ] 다전략 세션의 동일코인 중복 진입 상한(포트폴리오 상관 리스크) — 코인당 총 노출 한도.
- [ ] 손익비 역전 개선: 손절 -5% 축소 또는 익절 목표 상향 / 트레일링 조정 (승률 31%에선 손익비 2:1+ 필요).
- [ ] `EXCHANGE_DOWN` 캐스케이드로 멈춘 정상 세션 자동 재개 정책 검토(현재 수동 재시작 필요).
