package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.DynamicSellSettlementEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.util.IndicatorSnapshot;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
import com.cryptoautotrader.api.util.TimeframeUtils;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.BlackSwanGuard;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.core.selector.RangeRegimeGate;
import com.cryptoautotrader.exchange.upbit.UpbitCandleCollector;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cryptoautotrader.api.util.TradingConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동적 멀티코인 세션 서비스.
 *
 * <h3>동작 흐름</h3>
 * <pre>
 * [SCANNING]
 *   매 60초: 워치리스트 코인들에 전략 평가 → BUY 신호 첫 번째 코인 매수
 *   → scanState = POSITION_MONITORING, currentCoinPair = 매수 코인
 *
 * [POSITION_MONITORING]
 *   매 60초: currentCoinPair만 평가 → SL/TP/SELL 신호 시 매도
 *   → scanState = SCANNING, currentCoinPair = null
 * </pre>
 */
@Service
@Slf4j
public class DynamicTradingService {

    // HEIKIN_ASHI_STOCH 등 EMA(200) 기반 전략은 최소 201개 닫힌 캔들이 필요하다. 200개만 가져오면
    // closedCandleSlice()가 미마감 캔들을 하나 더 잘라내 최대 199개만 남아 구조적으로 절대 신호를
    // 낼 수 없었다(2026-07-01 실전 로그 분석 — 해당 전략 세션 100% "데이터 부족"). 라이브 매매
    // (LiveTradingService.CANDLE_LOOKBACK)·백테스트(BacktestEngine.MAX_LOOKBACK)와 동일하게
    // 500으로 맞춰 백테스트·실거래 신호 괴리를 줄인다.
    private static final int CANDLE_LOOKBACK = TradingConstants.CANDLE_LOOKBACK;
    private static final List<String> ACTIVE_ORDER_STATES = List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");
    private static final long MIN_HOLD_MINUTES = 180;

    /**
     * {@link #reconcileDynamicSessionBalance} 유예 시간(분) — 매수 KRW 차감(선커밋)과
     * 포지션 커밋 사이의 정상 구간을 오탐하지 않기 위한 최소 경과 시간.
     * 매수 1회는 초 단위로 끝나므로 3분이면 정상 매매를 절대 건드리지 않는다.
     */
    private static final long BALANCE_RECONCILE_GRACE_MIN = 3;

    /**
     * {@link #reconcileDynamicGhostPositions} 유예 시간(분) — 매도 체결 후 정상 후처리가
     * 끝날 시간을 준다. 정상 경로는 CLOSING reconcile(5초 주기)이 즉시 처리하므로,
     * 2분이 지나도 OPEN이면 후처리가 롤백된 것으로 본다.
     */
    private static final long SELL_FINALIZE_GRACE_MIN = 2;

    /**
     * BLACK_SWAN_GUARD 차단 후 해당 코인의 신규 진입을 계속 막는 시간(분).
     *
     * <p><b>근거 (2026-08-03 실측)</b>: KRW-ELSA가 01:00·01:14·01:42·01:45 <b>4회 차단</b>
     * (거래량 11.2배 급증 + 1시간 −2.13%)됐는데, <b>02:00에 가드가 풀리자마자</b> 세션 43이
     * 매수해 2.6시간 만에 <b>−8.33%</b>로 손절됐다. 가드의 판단은 정확했고 유지 시간만 짧았다.
     * 급등락 종목은 가드 해제 직후가 가장 위험한 구간이다.</p>
     *
     * <p>4시간 근거: 손실 확정까지 2.6시간이 걸렸고, 실측 평균 보유가 7.6시간이므로
     * 4시간이면 급등락 여진 구간을 덮으면서 정상 종목의 기회를 과하게 빼앗지 않는다.</p>
     */
    private static final long BLACK_SWAN_COOLDOWN_MIN = 240;

    /**
     * BLACK_SWAN_GUARD 차단가 초과 진입을 막는 기간(분) — 쿨다운이 끝난 뒤에도 유지된다.
     *
     * <p><b>왜 필요한가 (2026-08-05 실측, 동일 패턴 2건)</b>: 쿨다운은 진입을 <b>지연</b>시킬 뿐
     * 가격을 보지 않는다. 그 결과 "차단 가격에 안 사고 기다렸다가 더 비싸게 사서 차단 가격
     * 아래로 손절"이 그대로 재현됐다.</p>
     * <ul>
     *   <li>KRW-META2: 08-04 01:06 가드 차단 시점가 <b>8,630</b> → 06:00 <b>9,150</b>(+6.0%)
     *       진입 → 18:32 <b>8,495</b> 손절. 차단가보다 낮은 값에 팔았다.</li>
     *   <li>KRW-ELSA: 08-03 01:00~01:45 4회 차단 → 02:00 80.90 진입 → 04:33 74.20 손절.</li>
     * </ul>
     * <p>가드가 "이 가격은 위험하다"고 판단했다면 <b>그보다 비싼 가격은 더 위험하다</b>.
     * 쿨다운 해제 후에도 차단 시점가 이하로 내려온 경우에만 재진입을 허용한다.
     * 24시간이 지나면 가드 판단의 유효기간이 끝난 것으로 보고 기준가를 폐기한다 —
     * 그렇지 않으면 상승 추세로 전환된 종목이 영구 차단된다.</p>
     */
    private static final long BLACK_SWAN_PRICE_GUARD_MIN = 1440;

    /**
     * 한 코인을 동시에 보유할 수 있는 동적 세션 수의 상한.
     *
     * <p><b>왜 필요한가 (2026-08-06 운영 DB 실측)</b>: 세션 39와 45가 <b>4초 간격</b>으로
     * 같은 가격(101)·같은 수량의 KRW-DOGE를 매수해 단일 코인에 16,000원 — <b>동적 자본의
     * 23%</b> — 가 몰렸다. 두 세션은 전략이 다른데도(ICHIMOKU_V2 / ICHIMOKU) 워치리스트가
     * 겹치면 같은 tick·같은 신호에 동시에 반응한다. 24시간 평가 기준 DOGE는 6세션, XRP는
     * 5세션이 동시에 보고 있어 수렴은 예외가 아니라 상시 조건이다.</p>
     *
     * <p>7세션을 분산 운용하는 목적 자체가 전략·종목 분산인데, 노출 상한이 없으면 분산된
     * 것은 세션 수뿐이고 리스크는 한 종목에 합쳐진다. 1로 두면 "한 코인은 한 세션만"이라
     * 세션 수만큼의 종목 분산이 실제로 보장된다.</p>
     */
    private static final long MAX_SESSIONS_PER_COIN = 1;

    // ── 손절폭/익절가 — 2026-08-06, ExitRuleCalculator로 이전 ───────────────────
    // 2026-07-31 ATR 기반 개편 + 2026-08-05 재조정 이력은 그 클래스 javadoc 참조.
    // LIVE(LiveTradingService)도 이제 같은 계산을 호출한다 — 전엔 LIVE만 고정
    // stopLossPct를 쓰고 있어 07-31 개편이 반쪽만 적용된 상태였다(LIVE 세션 194 BTC
    // 136시간 고착의 원인).
    private static final BigDecimal MIN_PNL_PCT_FOR_SELL = new BigDecimal("0.30");
    /**
     * 손실 탈출 임계(%) — 2026-08-18 −1.00 → −0.30. 단일 출처는 {@link ExitRuleConfig}
     * (근거는 그 필드 javadoc — 운영 실측 4/4 게이트 손해, 평균 0.797%p).
     */
    private static final BigDecimal LOSS_ESCAPE_THRESHOLD =
            ExitRuleConfig.defaults().getLossEscapeThresholdPct();
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    /**
     * CLOSING 상태 진입 시각 — 이 시간 초과 시 reconcileClosingPositions()에서 OPEN 롤백.
     * OrderExecutionEngine.ORDER_TIMEOUT(5분)보다 반드시 길어야 한다 — LiveTradingService와
     * 동일한 race 방지 이유 (2026-07-02 감사 D-5).
     */
    private static final long CLOSING_TIMEOUT_MINUTES = 8;
    private static final String SESSION_KIND = "DYNAMIC";

    /**
     * PAPER(모의) 동적 세션의 session_kind (2026-08-06, V67).
     *
     * <p>{@code position}/{@code "order"}.session_kind 컬럼이 VARCHAR(10)이라 "DYNAMIC_PAPER"(13자)는
     * 들어가지 않는다 — 이 값으로 실거래("DYNAMIC")와 완전히 분리해, 실거래 reconcile 스케줄러 4종
     * ({@link #reconcileDynamicClosingPositions}·{@link #reconcileDynamicGhostPositions}·
     * {@link #reconcileDynamicOrphanBuyPositions}·{@link #reconcileDynamicSessionBalance})이
     * PAPER 데이터를 절대 건드리지 않게 한다(전부 {@code SESSION_KIND} 상수로 하드필터돼 있음).</p>
     */
    private static final String SESSION_KIND_PAPER = "DYN_PAPER";

    /**
     * PAPER 체결 슬리피지(0.1%) — {@code PaperTradingService}와 동일 값으로 세 엔진
     * (백테스트·페이퍼·실전)의 체결 가정을 통일한다. 매수는 불리하게 높게, 매도는 낮게 체결시킨다.
     */
    private static final BigDecimal PAPER_SLIPPAGE_PCT = TradingConstants.PAPER_SLIPPAGE_PCT;

    /**
     * 진입(SCANNING) 완화 파라미터 — SCANNING(신규 진입) 경로에만 적용하고,
     * POSITION_MONITORING(청산) 경로는 기본 임계값을 유지한다.
     *
     * <p>1차 완화(2026-07-09, weak 0.25 + EMA200 마진 1%)로도 6일간 매수 0건
     * (2026-07-15 운영 DB 분석, 평가 35,456건 분해): CompositeStrategy 내부 EMA20/50
     * 역추세 필터가 BUY 점수를 완전 소멸(5,218건, VWAP:BUY(100) 0.30→0.00 패턴 다수)시키고,
     * 이를 통과해 BUY로 확정된 ~323건도 EMA200 게이트(283)·BLACK_SWAN(40)이 전량 차단.
     * 2차 완화(2026-07-15, 사용자 결정 — 관망 대신 거래 빈도 확보):</p>
     * <ul>
     *   <li>weak 0.25→0.20 (strong 0.40 유지)</li>
     *   <li>EMA 역추세 감쇠 0.0(완전 소멸)→0.7 — VWAP:BUY(100) 단독 0.30→0.21로 임계 통과
     *       가능해지되, 약신호(SUPERTREND:BUY(50)=0.15→0.105)는 여전히 걸러진다</li>
     *   <li>EMA200 마진 1%→3% — 마진 1%에도 하락장에서 일 23~71건씩 전량 차단됐음</li>
     * </ul>
     * <p>ADX 필터는 건드리지 않는다 — adxThreshold 완화(15.0)는 횡보장 손실 확대로
     * 2026-06-30 제거된 전력 (LiveTradingService 주석 참조).</p>
     *
     * <p><b>설정화 (2026-07-15)</b>: 아래 상수는 코드 기본값(폴백)이다. risk_config의
     * scan_weak_threshold / scan_strong_threshold / scan_ema_dampen_factor /
     * scan_ema200_buy_margin_pct 가 NOT NULL이면 그 값이 우선한다 (V56) — 재빌드 없이
     * SQL/API(PUT /api/v1/trading/risk/config)로 조정 가능.</p>
     */
    private static final BigDecimal EMA200_BUY_MARGIN_PCT = new BigDecimal("3.0");
    private static final double SCAN_WEAK_THRESHOLD   = 0.20;  // CompositeStrategy 기본 0.3
    private static final double SCAN_STRONG_THRESHOLD = 0.40;  // CompositeStrategy 기본 0.5
    private static final double SCAN_EMA_DAMPEN_FACTOR = 0.7;  // CompositeStrategy 기본 0.0(완전 소멸)

    /**
     * 워치리스트 품질 큐레이션 기본값 (2026-07-24) — risk_config의 scan_min_trade_value_krw /
     * scan_max_atr_pct / scan_require_uptrend / scan_exclude_crashing가 NOT NULL이면 그 값이
     * 우선한다(V57). "거래대금 상위" 원시 유니버스가 펌프-덤프 잡코인으로 채워져 진입 게이트와
     * 신호가 상쇄되던(2주 실거래 0건) 문제 대응. {@link WatchlistQualityGate} 참조.
     */
    private static final BigDecimal SCAN_MIN_TRADE_VALUE_KRW = new BigDecimal("5000000000"); // 50억
    private static final BigDecimal SCAN_MAX_ATR_PCT = new BigDecimal("4.0");  // H1 기준 ATR% 상한
    private static final boolean SCAN_REQUIRE_UPTREND = true;
    private static final boolean SCAN_EXCLUDE_CRASHING = true;

    private final DynamicSessionRepository dynamicSessionRepo;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final WatchlistFilterService watchlistFilterService;
    private final OrderExecutionEngine orderExecutionEngine;
    private final TelegramNotificationService telegramService;
    private final RulesetRegistry rulesetRegistry;
    private final ObjectMapper objectMapper;
    private final DynamicSessionBalanceUpdater balanceUpdater;
    private final StrategyLogRepository strategyLogRepository;
    private final WsSubscriptionManager wsSubscriptionManager;
    private final StrategyLiveStatusRegistry strategyLiveStatusRegistry;
    private final StrategyEnablementGate strategyEnablementGate;
    private final RiskManagementService riskManagementService;
    private final WalkForwardValidationGate walkForwardValidationGate;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private UpbitRestClient upbitRestClient;

    /** 코인별 마지막 실시간(WS) SL/TP 점검 시각 — 5초 throttle */
    private final Map<String, Long> rtCheckLastMs = new ConcurrentHashMap<>();
    private static final long RT_CHECK_INTERVAL_MS = 5_000;

    /**
     * 세션별 마지막 실시간(WS) SL/TP 점검 시각 — {@link #warnStaleSlCheck} 미점검 경고용.
     * LIVE의 {@code §9 warnStaleSlCheck}와 동일 목적이나, 2026-08-06 이전엔 DYNAMIC에
     * 대응물 자체가 없었다(2026-08-05 ELSA 2.1%p SL 이탈 사고 당시에도 감시 공백을
     * 아무도 알아채지 못한 원인). {@link #doOnRealtimePriceEvent}에서만 갱신한다 — 60초
     * 폴링(processMonitoringTick)은 항상 돌므로 이 워치독의 관심사가 아니다.
     */
    private final Map<Long, Instant> lastSlCheckAt = new ConcurrentHashMap<>();
    private static final long SL_STALE_WARN_MINUTES = 3;

    /**
     * self-invocation 문제 해결용 — tick()이 @Scheduled(비-프록시 경유)로 직접 호출되면
     * processTick() 내부의 @Transactional이 Spring 프록시를 우회해 무시된다.
     * @Lazy 자기 참조로 프록시를 경유시켜 트랜잭션이 실제로 적용되도록 한다.
     */
    @Lazy
    @Autowired
    private DynamicTradingService self;

    /** 세션+코인 조합별 stateful 전략 인스턴스 (MarketRegimeDetector 상태 격리) */
    private final Map<String, Strategy> strategyInstances = new ConcurrentHashMap<>();

    /** 세션+코인 조합별 마지막으로 평가한 닫힌 캔들 시각 */
    private final Map<String, Instant> lastEvaluatedCandle = new ConcurrentHashMap<>();

    /**
     * BLACK_SWAN_GUARD가 진입을 차단한 코인의 마지막 차단 시각 — 코인 단위(세션 무관).
     * 급등락은 종목의 성질이지 세션의 성질이 아니므로 전 세션이 쿨다운을 공유한다.
     *
     * <p>인메모리지만 재기동 시 {@link #restoreBlackSwanCooldown()}이 {@code strategy_log}에서
     * 복원한다 — 차단 사실 자체는 DB에 남으므로 별도 저장소가 필요 없다.</p>
     */
    // 복원 결과를 검증하기 위해 package-private (resolveStopLossPct·pickBestBuyCandidate와 동일 방침)
    final Map<String, Instant> blackSwanBlockedAt = new ConcurrentHashMap<>();

    /**
     * BLACK_SWAN_GUARD가 차단한 시점의 가격 — {@link #BLACK_SWAN_PRICE_GUARD_MIN} 진입가 가드 기준.
     * {@link #blackSwanBlockedAt}와 같은 생애주기로 넣고 지운다.
     */
    final Map<String, BigDecimal> blackSwanBlockedPrice = new ConcurrentHashMap<>();

    /** 쿨다운 복원 대상을 가려내는 blocked_reason 접두어 (가드 발동분만 — 아래 주석 참조) */
    private static final String BLACK_SWAN_BLOCK_PREFIX = "BLACK_SWAN_GUARD 발동";

    /**
     * 재기동 시 BLACK_SWAN 쿨다운을 {@code strategy_log}에서 복원한다.
     *
     * <p><b>왜 필요한가 (2026-08-04 실측)</b>: 쿨다운 맵은 인메모리라 재기동으로 사라진다.
     * 08-04 13:0x 배포 재기동 때 KRW-META2가 10:00에 가드로 차단돼 쿨다운이 약 40분 남아
     * 있었는데, 그 잔여분이 통째로 소실됐다. 하필 재기동 직후는 급락이 이미 지나가
     * <b>가드 본체는 안 걸리고 쿨다운만 필요한 구간</b>이라, 1차 방어가 있으니 괜찮다는
     * 기존 판단(위 필드 주석)이 실제로는 성립하지 않는다. 08-03 ELSA 사고(가드 4회 차단 →
     * 해제 직후 진입 → −8.33%)와 같은 창이 재기동마다 다시 열린다.</p>
     *
     * <p>복원 대상을 {@code BLACK_SWAN_GUARD 발동}으로 한정하는 것이 핵심이다. 쿨다운이
     * 남긴 차단 로그({@code BLACK_SWAN 쿨다운 …})까지 포함하면 쿨다운이 스스로를 갱신해
     * <b>영구 차단으로 굳는다</b>.</p>
     *
     * <p>실패해도 기동을 막지 않는다 — 복원은 2차 방어의 연장이고, 가드 본체는 독립적으로 돈다.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreBlackSwanCooldown() {
        try {
            // 진입가 가드(24시간)가 쿨다운(4시간)보다 길므로 더 긴 쪽을 조회 구간으로 잡는다.
            Instant from = Instant.now().minus(
                    Math.max(BLACK_SWAN_COOLDOWN_MIN, BLACK_SWAN_PRICE_GUARD_MIN), ChronoUnit.MINUTES);
            List<Object[]> rows = strategyLogRepository.findRecentBlockedCoins(
                    SESSION_KIND, from, BLACK_SWAN_BLOCK_PREFIX);
            // 오래된 순으로 오므로 순서대로 덮어쓰면 코인별 가장 최근 차단이 남는다.
            for (Object[] row : rows) {
                if (row[0] == null || row[1] == null) continue;
                String coinPair = (String) row[0];
                blackSwanBlockedAt.put(coinPair, (Instant) row[1]);
                // signalPrice는 과거 로그에 없을 수 있다 — 그 경우 쿨다운만 복원하고
                // 진입가 가드는 기준가가 없으므로 자동으로 비활성이 된다.
                if (row[2] != null) {
                    blackSwanBlockedPrice.put(coinPair, (BigDecimal) row[2]);
                } else {
                    blackSwanBlockedPrice.remove(coinPair);
                }
            }
            if (!rows.isEmpty()) {
                log.info("[Dynamic] BLACK_SWAN 쿨다운 복원: {}종 {} (진입가 가드 기준가 {}종)",
                        blackSwanBlockedAt.size(), blackSwanBlockedAt.keySet(),
                        blackSwanBlockedPrice.size());
            }
        } catch (Exception e) {
            log.warn("[Dynamic] BLACK_SWAN 쿨다운 복원 실패 — 가드 본체로 진행: {}", e.getMessage());
        }
    }

    public DynamicTradingService(DynamicSessionRepository dynamicSessionRepo,
                                  PositionRepository positionRepository,
                                  OrderRepository orderRepository,
                                  WatchlistFilterService watchlistFilterService,
                                  OrderExecutionEngine orderExecutionEngine,
                                  TelegramNotificationService telegramService,
                                  RulesetRegistry rulesetRegistry,
                                  ObjectMapper objectMapper,
                                  DynamicSessionBalanceUpdater balanceUpdater,
                                  StrategyLogRepository strategyLogRepository,
                                  WsSubscriptionManager wsSubscriptionManager,
                                  StrategyLiveStatusRegistry strategyLiveStatusRegistry,
                                  StrategyEnablementGate strategyEnablementGate,
                                  RiskManagementService riskManagementService,
                                  WalkForwardValidationGate walkForwardValidationGate,
                                  ApplicationEventPublisher eventPublisher) {
        this.dynamicSessionRepo   = dynamicSessionRepo;
        this.positionRepository   = positionRepository;
        this.orderRepository      = orderRepository;
        this.watchlistFilterService = watchlistFilterService;
        this.orderExecutionEngine = orderExecutionEngine;
        this.telegramService      = telegramService;
        this.rulesetRegistry      = rulesetRegistry;
        this.objectMapper         = objectMapper;
        this.balanceUpdater       = balanceUpdater;
        this.strategyLogRepository = strategyLogRepository;
        this.wsSubscriptionManager = wsSubscriptionManager;
        this.strategyLiveStatusRegistry = strategyLiveStatusRegistry;
        this.strategyEnablementGate = strategyEnablementGate;
        this.riskManagementService = riskManagementService;
        this.walkForwardValidationGate = walkForwardValidationGate;
        this.eventPublisher = eventPublisher;
    }

    // ── 세션 생성 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity createSession(DynamicSessionRequest req) {
        StrategyRegistry.get(req.getStrategyType()); // 유효성 검증

        boolean isPaper = "PAPER".equals(req.getTradingMode());

        // 비활성 전략 차단 — strategy_type_enabled에서 꺼진 전략은 세션 생성 거부.
        // (UI 드롭다운 필터만으로는 select 표시/상태 불일치 등으로 우회될 수 있어 서버에서 강제)
        // 2026-08-18: 규칙을 StrategyEnablementGate로 추출 — LIVE·PAPER 경로도 같은 검사를 받는다.
        strategyEnablementGate.assertEnabled(req.getStrategyType());

        // 자본 배정 게이트 2종은 PAPER에 적용하지 않는다 — 이 두 게이트는 "실자본을 쓸 자격이
        // 있는가"를 묻는 것이고, 페이퍼는 정확히 그 자격을 얻기 전에 검증하는 도구다.
        // (2026-08-06, PaperTradingService LIVE 정렬 때와 동일한 판단)
        if (!isPaper) {
            // 전략 거버넌스 검증 — BLOCKED/DEPRECATED 전략은 동적 세션 생성도 차단한다.
            // 기존에는 이 검사가 라이브 세션에도 동적 세션에도 강제되지 않아, 두 경로 모두
            // StrategyLiveStatusRegistry 라벨을 우회해 BLOCKED 전략으로 실돈 세션 생성이 가능했다.
            if (strategyLiveStatusRegistry.isBlocked(req.getStrategyType())) {
                StrategyLiveStatusRegistry.StatusEntry status = strategyLiveStatusRegistry.getStatus(req.getStrategyType());
                throw new IllegalArgumentException(String.format(
                        "전략 '%s'은(는) 동적 세션 생성이 차단되었습니다 (%s): %s",
                        req.getStrategyType(), status.readiness(), status.reason()));
            }

            // 신호 기대값 검증 게이트 — Walk Forward로 out-of-sample 기대값>0이 증명된 전략만 통과.
            // 기본은 비활성(플래그 off)이라 당장은 강제하지 않는다.
            walkForwardValidationGate.throwIfBlocked(req.getStrategyType());
        }

        BigDecimal investRatio = normalizeRatio(req.getInvestRatio(), new BigDecimal("0.80"));
        BigDecimal stopLoss    = req.getStopLossPct() != null ? req.getStopLossPct() : new BigDecimal("5.0");

        DynamicSessionEntity session = DynamicSessionEntity.builder()
                .strategyType(req.getStrategyType())
                .timeframe(req.getTimeframe())
                .initialCapital(req.getInitialCapital())
                .availableKrw(req.getInitialCapital())
                .totalAssetKrw(req.getInitialCapital())
                .investRatio(investRatio)
                .stopLossPct(stopLoss)
                .strategyParams(req.getStrategyParams())
                .status("CREATED")
                .scanState("SCANNING")
                .tradingMode(isPaper ? "PAPER" : DynamicSessionEntity.DEFAULT_TRADING_MODE)
                                .maxCandidateSize(firstNonNull(req.getMaxCandidateSize(),
                        scanDefaults().getScanMaxCandidateSize(), 30))
                .targetWatchSize(req.getTargetWatchSize() != null ? req.getTargetWatchSize() : 10)
                // 워치리스트 필터 기본값: 요청 > risk_config > 코드 하드코딩 (V71).
                // risk_config 를 중간에 둔 이유 — 08-07 세션 재생성 때 7월의 튜닝
                // (ATR 0.30 / 스프레드 0.15)이 코드 기본값으로 조용히 되돌아가 감시 코인이
                // 주당 62종 → 10종으로 붕괴했다. 전역 설정은 재생성에도 살아남는다.
                .minAtrPct(firstNonNull(req.getMinAtrPct(),
                        scanDefaults().getScanMinAtrPct(), new BigDecimal("0.5")))
                .maxSpreadPct(firstNonNull(req.getMaxSpreadPct(),
                        scanDefaults().getScanMaxSpreadPct(), new BigDecimal("0.1")))
                .watchlistRefreshMin(req.getWatchlistRefreshMin() != null ? req.getWatchlistRefreshMin() : 60)
                .maxHoldHours(req.getMaxHoldHours() != null
                        ? req.getMaxHoldHours()
                        : DynamicSessionEntity.DEFAULT_MAX_HOLD_HOURS)
                .build();

        session = dynamicSessionRepo.save(session);
        log.info("[Dynamic] 세션 생성: id={} strategy={} timeframe={} capital={} mode={}",
                session.getId(), session.getStrategyType(), session.getTimeframe(),
                session.getInitialCapital(), session.getTradingMode());
        return session;
    }

    // ── 세션 시작 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity startSession(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        if ("RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("이미 실행 중입니다: id=" + sessionId);
        }
        session.setStatus("RUNNING");
        session.setStartedAt(Instant.now());
        session.setStoppedAt(null);
        session = dynamicSessionRepo.save(session);
        log.info("[Dynamic] 세션 시작: id={} {} {}", sessionId, session.getStrategyType(), session.getTimeframe());
        telegramService.notifySessionStarted(sessionId, session.getStrategyType(),
                "멀티코인-동적", session.getTimeframe(), session.getInitialCapital().longValue());
        refreshWsSubscription();
        return session;
    }

    // ── 세션 정지 ──────────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity stopSession(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        if (!"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("실행 중이 아닙니다: id=" + sessionId);
        }
        closeOpenPositions(session, "세션 정지 — 포지션 청산");
        clearSessionState(sessionId);
        // ⚠️ 2026-08-19 P0: closeOpenPositions() 안에서 balanceUpdater(REQUIRES_NEW)가 세션을
        //    이미 두 번 커밋했다 — 매도대금 반영(finalizeDynamicSell)과 SCANNING 복귀
        //    (transitionToScanning). 여기서 위에서 읽은 **낡은 엔티티**를 save() 하면 @Version 이
        //    어긋나 OptimisticLockingFailure 가 나고 **바깥 트랜잭션만** 롤백된다. 별도 트랜잭션으로
        //    커밋된 매도대금은 롤백되지 않으므로, 정지를 누를 때마다 대금이 중복 지급되고
        //    포지션은 OPEN 으로 되돌아온다 → 정지가 영구히 실패하는 무한 증식 루프.
        //    (세션 49: 21회 시도로 available_krw 10,000 → 174,752, 포지션 2458 은 OPEN 잔존)
        //    반드시 재조회+낙관적 락 재시도 경로(balanceUpdater)로 상태를 바꾼다.
        session = balanceUpdater.apply(sessionId, s -> {
            s.setStatus("STOPPED");
            s.setStoppedAt(Instant.now());
        });
        log.info("[Dynamic] 세션 정지: id={}", sessionId);
        refreshWsSubscription();
        return session;
    }

    // ── 세션 비상 정지 ─────────────────────────────────────────────

    @Transactional
    public DynamicSessionEntity emergencyStop(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        closeOpenPositions(session, "비상 정지 — 강제 청산");
        clearSessionState(sessionId);
        // stopSession() 과 동일한 이유로 재조회 경로를 쓴다 — 위 stopSession() 주석 참조.
        session = balanceUpdater.apply(sessionId, s -> {
            s.setStatus("EMERGENCY_STOPPED");
            s.setStoppedAt(Instant.now());
        });
        log.error("[Dynamic] 세션 비상 정지 완료: id={}", sessionId);
        refreshWsSubscription();
        return session;
    }

    // ── 세션 삭제 (soft) ───────────────────────────────────────────

    @Transactional
    public void deleteSession(Long sessionId) {
        DynamicSessionEntity session = getOrThrow(sessionId);
        if ("RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("실행 중인 세션은 삭제할 수 없습니다. 먼저 정지하세요.");
        }

        // 정지 시 청산이 누락된 orphan OPEN 포지션 정리 (LiveTradingService.deleteSession과 동일 정책)
        List<PositionEntity> openPositions =
                positionRepository.findBySessionKindAndSessionIdAndStatus(sessionKind(session), sessionId, "OPEN");
        for (PositionEntity pos : openPositions) {
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
            positionRepository.save(pos);
            log.warn("[Dynamic] 세션 삭제 시 미청산 포지션 강제 종료: posId={} {} (sessionId={})",
                    pos.getId(), pos.getCoinPair(), sessionId);
        }

        // soft-delete — 행/링크 보존, 상태만 DELETED (전략로그/주문로그 조회 유지)
        session.setStatus("DELETED");
        if (session.getStoppedAt() == null) session.setStoppedAt(Instant.now());
        dynamicSessionRepo.save(session);
        clearSessionState(sessionId);
        log.info("[Dynamic] 세션 삭제(soft) 완료: id={} → status=DELETED", sessionId);
    }

    // ── 조회 ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DynamicSessionEntity> listSessions() {
        // DELETED 세션은 목록에서 제외 (로그 조회용 getSessionIndex()에는 유지)
        return dynamicSessionRepo.findAllByOrderByCreatedAtDesc().stream()
                .filter(s -> !"DELETED".equals(s.getStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DynamicSessionEntity getSession(Long sessionId) {
        return getOrThrow(sessionId);
    }

    /**
     * 세션 인덱스 — 전략로그/주문로그 선택 UI용. {@code TradingController.sessionIndex()}가
     * 라이브 세션 인덱스에 이 목록을 합쳐 반환한다. dynamic_session 은 hard/soft delete가 없으므로
     * 테이블 전체를 그대로 반환한다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSessionIndex() {
        return dynamicSessionRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("sessionId", s.getId());
                    m.put("strategyType", s.getStrategyType());
                    m.put("coinPair", s.getCurrentCoinPair() != null ? s.getCurrentCoinPair() : "멀티코인");
                    m.put("status", s.getStatus());
                    m.put("sessionType", sessionKind(s));
                    return m;
                })
                .toList();
    }

    // ── 스케줄: 60초마다 실행 ─────────────────────────────────────

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void tick() {
        List<DynamicSessionEntity> running = dynamicSessionRepo.findByStatus("RUNNING");
        if (running.isEmpty()) return;

        for (DynamicSessionEntity session : running) {
            try {
                // self 프록시를 경유해야 @Transactional이 실제로 적용된다 (self-invocation 우회 방지)
                self.processTick(session);
            } catch (Exception e) {
                log.error("[Dynamic] 세션 tick 오류 (id={}): {}", session.getId(), e.getMessage(), e);
            }
        }
    }

    // ── 내부: tick 분기 ────────────────────────────────────────────

    @Transactional
    public void processTick(DynamicSessionEntity session) {
        Long sid = session.getId();
        // tick()에서 넘어온 엔티티는 트랜잭션 밖에서 읽은 스냅샷 — 그 사이 WS 매도 등으로
        // scanState가 바뀌었을 수 있으므로 최신 상태를 다시 읽어 분기한다.
        DynamicSessionEntity fresh = dynamicSessionRepo.findById(sid).orElse(null);
        if (fresh == null || !"RUNNING".equals(fresh.getStatus())) return;

        // ── 서킷 브레이커 — MDD 초과 / 연속 손실 한도 초과 시 비상 정지 ──
        // LiveTradingService와 동일 정책. 2026-07-15 진입 2차 완화로 동적 세션이 실제 매매를
        // 시작하므로, 라이브 191(-208원, 5연속 손절)을 멈춰준 안전장치를 동적 경로에도 적용한다.
        // 연속 손실은 이번 가동(startedAt) 이후 청산분만 집계 — 재시작 시 즉시 재발동 방지.
        CircuitBreakerResult cbResult = riskManagementService.checkCircuitBreaker(fresh);
        if (cbResult.isTriggered()) {
            log.error("[Dynamic] 서킷 브레이커 발동 (id={}): {}", sid, cbResult.getReason());
            fresh.setCircuitBreakerTriggeredAt(Instant.now());
            fresh.setCircuitBreakerReason(cbResult.getReason());
            // 누적 횟수 — LiveTradingService 와 동일 (kill criteria CB_REPEAT 판정)
            fresh.setCircuitBreakerTripCount(fresh.getCircuitBreakerTripCount() + 1);
            dynamicSessionRepo.save(fresh);
            emergencyStop(sid);
            telegramService.sendCustomNotification(String.format(
                    "🚨 [동적#%d] 서킷 브레이커 발동 — 비상 정지: %s", sid, cbResult.getReason()));
            return;
        }

        if ("SCANNING".equals(fresh.getScanState())) {
            processScanningTick(fresh);
        } else {
            processMonitoringTick(fresh);
        }
    }

    /**
     * SCANNING 단계에서 BUY 신호를 낸 코인 후보 — 전체 워치리스트 평가 후 최고 강도 신호를
     * 선택하기 위해 즉시 실행하지 않고 임시 보관한다.
     */
    // package-private (private 아님) — DynamicScanSelectionTest에서 선택 로직 단위 테스트를 위해 필요
    record BuyCandidate(String coinPair, List<Candle> evalCandles,
                         StrategySignal signal, StrategyLogEntity signalLog,
                         BigDecimal sizeMultiplier) {}

    /**
     * BUY 후보 중 신호 강도(strength)가 가장 높은 것을 선택한다. 동률이면 먼저 평가된(워치리스트
     * 순서상 앞선) 코인을 유지한다(스트림 max()는 첫 최댓값을 보존).
     */
    static BuyCandidate pickBestBuyCandidate(List<BuyCandidate> candidates) {
        return candidates.stream()
                .max(java.util.Comparator.comparing(c -> c.signal().getStrength()))
                .orElseThrow();
    }

    /**
     * SCANNING: 워치리스트 전체 코인을 평가한 뒤, BUY 신호를 낸 코인 중 신호 강도(strength)가
     * 가장 높은 코인 하나에만 진입한다.
     *
     * <p>이전에는 워치리스트를 거래대금 내림차순으로 순회하다 첫 BUY 신호에서 즉시 진입해,
     * 실제로는 신호 품질이 아니라 "거래대금 순위"가 진입 코인을 결정하고 있었다
     * (2026-07-02 종합분석 DM-1). 전체 평가 후 최고 confidence 선택으로 개선.</p>
     */
    @Transactional
    public void processScanningTick(DynamicSessionEntity session) {
        Long sid = session.getId();
        List<String> watchlist = resolveWatchlist(session);

        if (watchlist.isEmpty()) {
            // 진단용 — 워치리스트가 비는 것은 ATR/스프레드 필터가 너무 빡빡하거나 거래소 응답
            // 문제일 수 있다. 어떤 설정으로 비었는지 바로 알 수 있도록 INFO 로 남긴다.
            log.info("[Dynamic] 워치리스트 비어 있음 — 이번 틱 스킵 (id={}, maxCandidate={} target={} "
                            + "minAtrPct={}% maxSpreadPct={} timeframe={})",
                    sid, session.getMaxCandidateSize(), session.getTargetWatchSize(),
                    session.getMinAtrPct(), session.getMaxSpreadPct(), session.getTimeframe());
            return;
        }

        log.info("[Dynamic] SCANNING 시작: id={} 감시목록({})={}", sid, watchlist.size(), watchlist);

        // 진단용 게이트 차단 집계 — 매수가 막힐 때 어느 단계에서 막히는지 한눈에 보기 위함
        int insufficientCandles = 0;
        int staleCandle = 0;
        int holdCount = 0;
        int sellCount = 0;
        int ema200Blocked = 0;
        int rangeBlocked = 0;
        int blackSwanBlocked = 0;
        int btcMarketGuardBlocked = 0;
        int lossCooldownBlocked = 0;
        int crossSessionBlocked = 0;
        List<BuyCandidate> buyCandidates = new java.util.ArrayList<>();

        // SCANNING 진입 파라미터 — risk_config에서 읽고, NULL이면 코드 기본값(상수) 사용.
        // 2026-07-15 설정화: 완화 폭 튜닝 반복 중이라 재빌드 없이 SQL/API로 조정 가능해야 한다.
        com.cryptoautotrader.api.entity.RiskConfigEntity riskConfig = riskManagementService.getRiskConfig();
        // 손실 청산 쿨다운(분) — 라이브 191 패턴(같은 코인 반복 진입→손절 5연속) 방지:
        // 직전 청산이 손실이면 쿨다운 동안 같은 코인 재진입을 차단한다.
        int lossCooldownMinutes = riskConfig.getCooldownMinutes() != null
                ? riskConfig.getCooldownMinutes() : 60;
        double scanWeakThreshold = riskConfig.getScanWeakThreshold() != null
                ? riskConfig.getScanWeakThreshold().doubleValue() : SCAN_WEAK_THRESHOLD;
        double scanStrongThreshold = riskConfig.getScanStrongThreshold() != null
                ? riskConfig.getScanStrongThreshold().doubleValue() : SCAN_STRONG_THRESHOLD;
        double scanEmaDampenFactor = riskConfig.getScanEmaDampenFactor() != null
                ? riskConfig.getScanEmaDampenFactor().doubleValue() : SCAN_EMA_DAMPEN_FACTOR;
        BigDecimal ema200BuyMarginPct = riskConfig.getScanEma200BuyMarginPct() != null
                ? riskConfig.getScanEma200BuyMarginPct() : EMA200_BUY_MARGIN_PCT;

        // BTC_MARKET_GUARD — 워치리스트 전체가 같은 timeframe을 쓰므로 틱당 한 번만 조회한다
        // (2026-07-02 codex 분석 §6, BTC 1시간 -1.5% 급락 시 코인 무관 신규 진입 차단).
        List<Candle> btcCandles = fetchCandles("KRW-BTC", session.getTimeframe());
        BtcMarketGuard.Result btcMarketGuard = BtcMarketGuard.check(
                closedCandleSlice(btcCandles, session.getTimeframe()));

        for (String coinPair : watchlist) {
            List<Candle> candles = fetchCandles(coinPair, session.getTimeframe());
            if (candles.size() < 15) {
                insufficientCandles++;
                log.debug("[Dynamic] 캔들 부족 스킵: {} ({}개)", coinPair, candles.size());
                continue;
            }

            List<Candle> evalCandles = closedCandleSlice(candles, session.getTimeframe());
            String candleKey = sid + ":" + coinPair;
            Instant closedTime = evalCandles.get(evalCandles.size() - 1).getTime();
            Instant prevEval = lastEvaluatedCandle.get(candleKey);
            if (prevEval != null && !closedTime.isAfter(prevEval)) {
                staleCandle++;
                log.debug("[Dynamic] 닫힌 캔들 미갱신 스킵: {}", coinPair);
                continue;
            }
            lastEvaluatedCandle.put(candleKey, closedTime);

            Strategy strategy = resolveStrategy(sid, coinPair, session.getStrategyType());
            // 전역 risk_config 값을 깔고, 세션 오버라이드(V74)가 있으면 덮는다.
            // 세션값이 지문에 실리므로 같은 시간대에 두 파라미터를 나란히 돌려 비교할 수 있다.
            Map<String, Object> evalParams = new HashMap<>();
            evalParams.put("coinPair", coinPair);
            evalParams.put("weakThreshold", scanWeakThreshold);
            evalParams.put("strongThreshold", scanStrongThreshold);
            evalParams.put("emaFilterDampenFactor", scanEmaDampenFactor);
            if (session.getStrategyParams() != null) {
                evalParams.putAll(session.getStrategyParams());
            }
            StrategySignal signal = strategy.evaluate(evalCandles, evalParams);
            BigDecimal evalPrice = evalCandles.get(evalCandles.size() - 1).getClose();

            // ── 진입 게이트 — BUY 실행만 차단하고 신호는 BUY로 보존한다 ─────────
            // 이전에는 게이트가 signal 자체를 HOLD로 덮어써서, 차단된 BUY가 사후수익률
            // 평가(SignalQualityService — BUY/SELL만 대상)에서 영구 제외됐다. 신호는 BUY로
            // 저장하고 blockedReason에 게이트 사유를 남겨 "차단이 방어였는지(하락) 기회비용
            // 이었는지(상승)"를 4h/24h 수익률로 측정 가능하게 한다 (2026-07-15).
            String gateBlockReason = null;
            // EMA200 게이트 사이즈 배수 — 1.0(정상)/0.5(감액)/0.0(차단). 하드 차단 대신
            // 근접 하회 구간을 감액 진입으로 살린다 (2026-07-21, "너무 보수적이지 않은 거래").
            BigDecimal ema200SizeMultiplier = BigDecimal.ONE;

            if (signal.getAction() == StrategySignal.Action.BUY
                    && !Ema200RegimeGate.isExempt(session.getStrategyType())) {
                ema200SizeMultiplier = Ema200RegimeGate.buySizeMultiplier(evalCandles, ema200BuyMarginPct);
                if (ema200SizeMultiplier.signum() == 0) {
                    ema200Blocked++;
                    log.info("[Dynamic] EMA200 BUY 차단(딥 하회): {} (id={})", coinPair, sid);
                    gateBlockReason = String.format(
                            "EMA200 레짐 필터 — 현재가 EMA200(-%s%%×2) 이하 딥 하락", ema200BuyMarginPct);
                } else if (ema200SizeMultiplier.compareTo(BigDecimal.ONE) < 0) {
                    log.info("[Dynamic] EMA200 근접 하회 — 감액 진입({}배): {} (id={})",
                            ema200SizeMultiplier, coinPair, sid);
                }
            }

            if (gateBlockReason == null && signal.getAction() == StrategySignal.Action.BUY
                    && RangeRegimeGate.isBlocked(session.getStrategyType())) {
                try {
                    MarketRegime regime = new MarketRegimeDetector().detectRaw(evalCandles);
                    if (regime == MarketRegime.RANGE) {
                        rangeBlocked++;
                        log.info("[Dynamic] RANGE 레짐 BUY 차단: {} (id={})", coinPair, sid);
                        gateBlockReason = "RANGE 레짐 — 추세 추종 전략 횡보장 신규 진입 차단";
                    }
                } catch (Exception ignored) {}
            }

            // BLACK_SWAN_GUARD: 코인별 서킷 브레이커 — 3단계 진입 게이트 (2026-07-22 완화).
            // 1시간 낙폭 -5%~-8%는 감액 진입, -8% 이하·거래량 급증 조기경보는 하드 차단 유지.
            // 이전 하드 차단(check)은 BUY 63건 중 46건을 전량 차단해 13일간 실거래 0건의 주 원인이었다.
            // 쿨다운 — 가드가 한 번 차단한 코인은 해제 직후가 가장 위험하다 (2026-08-03 실측).
            // 가드는 판단이 옳았고 **유지 시간이 짧았을 뿐**이므로, 해제 후에도 일정 시간 막는다.
            if (gateBlockReason == null && signal.getAction() == StrategySignal.Action.BUY) {
                BlackSwanGateDecision decision = evaluateBlackSwanGate(
                        blackSwanBlockedAt.get(coinPair), blackSwanBlockedPrice.get(coinPair),
                        evalPrice, Instant.now());
                if (decision.blockReason() != null) {
                    blackSwanBlocked++;
                    gateBlockReason = decision.blockReason();
                    log.warn("[Dynamic] BUY 차단: {} (id={}) — {}", coinPair, sid, gateBlockReason);
                } else if (decision.expired()) {
                    blackSwanBlockedAt.remove(coinPair);
                    blackSwanBlockedPrice.remove(coinPair);
                }
            }

            BigDecimal blackSwanSizeMultiplier = BigDecimal.ONE;
            if (gateBlockReason == null && signal.getAction() == StrategySignal.Action.BUY) {
                BlackSwanGuard.EntryGate guard = BlackSwanGuard.entryGate(evalCandles);
                if (guard.blocked()) {
                    blackSwanBlocked++;
                    blackSwanBlockedAt.put(coinPair, Instant.now());   // 쿨다운 시작
                    blackSwanBlockedPrice.put(coinPair, evalPrice);    // 진입가 가드 기준가
                    log.warn("[Dynamic] BLACK_SWAN_GUARD 발동 — BUY 차단: {} (id={}): {}",
                            coinPair, sid, guard.reason());
                    gateBlockReason = "BLACK_SWAN_GUARD 발동 — " + guard.reason();
                } else if (guard.reduced()) {
                    blackSwanSizeMultiplier = guard.sizeMultiplier();
                    log.info("[Dynamic] BLACK_SWAN 완충 구간 — 감액 진입({}배): {} (id={}): {}",
                            blackSwanSizeMultiplier, coinPair, sid, guard.reason());
                }
            }

            // BTC_MARKET_GUARD: BTC 1시간 -1.5% 급락 시 코인 무관 전체 신규 진입 차단.
            if (gateBlockReason == null && signal.getAction() == StrategySignal.Action.BUY
                    && btcMarketGuard.triggered()) {
                btcMarketGuardBlocked++;
                log.warn("[Dynamic] BTC_MARKET_GUARD 발동 — BUY 차단: {} (id={}): {}",
                        coinPair, sid, btcMarketGuard.reason());
                gateBlockReason = "BTC_MARKET_GUARD 발동 — " + btcMarketGuard.reason();
            }

            // 손실 청산 쿨다운: 이 세션에서 직전에 손실로 청산한 코인은 쿨다운 동안 재진입 차단.
            if (gateBlockReason == null && signal.getAction() == StrategySignal.Action.BUY
                    && isInLossCooldown(sessionKind(session), sid, coinPair, lossCooldownMinutes)) {
                lossCooldownBlocked++;
                log.info("[Dynamic] 손실 쿨다운 BUY 차단: {} (id={}, 쿨다운 {}분)",
                        coinPair, sid, lossCooldownMinutes);
                gateBlockReason = String.format(
                        "손실 청산 쿨다운 — 직전 손실 청산 후 %d분 내 동일 코인 재진입 차단", lossCooldownMinutes);
            }

            // 세션 간 동일코인 노출 상한: 다른 동적 세션이 이미 같은 코인을 들고 있으면 차단.
            if (gateBlockReason == null && signal.getAction() == StrategySignal.Action.BUY) {
                long heldElsewhere = positionRepository
                        .countBySessionKindAndCoinPairAndStatusAndSessionIdNot(
                                sessionKind(session), coinPair, "OPEN", sid);
                String crossReason = crossSessionExposureBlockReason(heldElsewhere);
                if (crossReason != null) {
                    crossSessionBlocked++;
                    log.info("[Dynamic] 동일코인 노출 상한 BUY 차단: {} (id={}, 타 세션 보유 {}건)",
                            coinPair, sid, heldElsewhere);
                    gateBlockReason = crossReason;
                }
            }

            // 코인별 평가 결과 로그 (BUY/SELL은 INFO, HOLD는 DEBUG) — 전략로그 페이지 노출용으로 DB에도 저장
            if (signal.getAction() != StrategySignal.Action.HOLD) {
                log.info("[Dynamic] 평가결과: {} → {}{} ({})", coinPair, signal.getAction(),
                        gateBlockReason != null ? "(차단)" : "", signal.getReason());
            } else {
                holdCount++;
                log.debug("[Dynamic] 평가결과: {} → HOLD ({})", coinPair, signal.getReason());
            }
            if (signal.getAction() == StrategySignal.Action.SELL) sellCount++;

            StrategyLogEntity signalLog = saveStrategyLog(session, session.getStrategyType(), coinPair, signal, evalPrice, evalCandles);

            if (signal.getAction() == StrategySignal.Action.BUY) {
                if (gateBlockReason != null) {
                    // 게이트 차단 — 실행 후보에서 제외하되 신호품질 기록은 남긴다
                    updateSignalQuality(signalLog, false, gateBlockReason);
                } else {
                    // EMA200·블랙스완 감액이 겹치면 곱으로 중첩 적용 (0.5×0.5=0.25) — 최소주문
                    // 미달 시 executeBuy의 최소주문액 보정이 진입을 살린다.
                    BigDecimal sizeMultiplier = ema200SizeMultiplier.multiply(blackSwanSizeMultiplier);
                    buyCandidates.add(new BuyCandidate(coinPair, evalCandles, signal, signalLog, sizeMultiplier));
                }
            } else if (signal.getAction() == StrategySignal.Action.SELL) {
                // SCANNING 중 SELL은 실행 대상이 아님(보유 포지션 없음) — blockedReason 없이 쌓이면
                // 신호품질 통계가 "실행 안 된 SELL"로 오염된다 (2026-07-15 운영 DB 분석: 3,391건).
                updateSignalQuality(signalLog, false, "SCANNING — 보유 포지션 없음(청산 대상 아님)");
            }
        }

        if (buyCandidates.isEmpty()) {
            log.info("[Dynamic] SCANNING 완료: 진입 조건 없음 (id={}, 감시 {}개) — "
                            + "HOLD={} SELL={} EMA200차단={} RANGE차단={} 블랙스완차단={} BTC급락차단={} 손실쿨다운차단={} 동일코인차단={} 캔들부족={} 캔들미갱신={}",
                    sid, watchlist.size(), holdCount, sellCount, ema200Blocked, rangeBlocked,
                    blackSwanBlocked, btcMarketGuardBlocked, lossCooldownBlocked, crossSessionBlocked,
                    insufficientCandles, staleCandle);
            return;
        }

        // 전체 워치리스트 평가 완료 — BUY 후보 중 신호 강도(strength)가 가장 높은 코인 하나만 진입.
        BuyCandidate best = pickBestBuyCandidate(buyCandidates);

        log.info("[Dynamic] BUY 신호 진입: {} {} strength={} (id={}, 후보 {}개 중 최고)",
                best.coinPair(), best.signal().getReason(), best.signal().getStrength(), sid, buyCandidates.size());
        String blockedReason = executeBuy(session, best.coinPair(), best.evalCandles(), best.signal(), best.sizeMultiplier());
        updateSignalQuality(best.signalLog(), blockedReason == null, blockedReason);

        for (BuyCandidate other : buyCandidates) {
            if (other == best) continue;
            String reason = String.format("다른 코인 신호가 더 강함 — %s(strength=%s) 선택, 본 신호(strength=%s) 미선택",
                    best.coinPair(), best.signal().getStrength(), other.signal().getStrength());
            updateSignalQuality(other.signalLog(), false, reason);
        }
    }

    /** POSITION_MONITORING: 보유 코인만 평가 → SL/TP/SELL 처리 */
    @Transactional
    public void processMonitoringTick(DynamicSessionEntity session) {
        Long sid = session.getId();
        String coinPair = session.getCurrentCoinPair();

        if (coinPair == null) {
            transitionToScanning(sid);
            return;
        }

        List<Candle> candles = fetchCandles(coinPair, session.getTimeframe());
        if (candles.isEmpty()) return;

        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

        Optional<PositionEntity> posOpt = positionRepository
                .findBySessionKindAndSessionIdAndCoinPairAndStatus(sessionKind(session), sid, coinPair, "OPEN");

        if (posOpt.isEmpty()) {
            // ⚠️ 2026-08-03 P0: 이 분기가 무증상으로 지나가면서 세션 39·40·44의 KRW 누수를
            //    3일간 가렸다. 매수 tx가 롤백되면 포지션은 사라지고 KRW 차감만 남는데,
            //    여기서 상태만 SCANNING으로 되돌리고 잔고는 손대지 않기 때문이다.
            //    복원은 reconcileDynamicSessionBalance 가 맡고, 여기서는 흔적을 남긴다.
            if (session.getAvailableKrw() != null && session.getTotalAssetKrw() != null
                    && session.getAvailableKrw().compareTo(session.getTotalAssetKrw()) < 0) {
                log.error("[Dynamic] 🔴 포지션 없음인데 KRW가 묶여 있음 — 매수 tx 롤백 의심 "
                                + "(id={}, {}, available={}, total={}). reconcile 대기.",
                        sid, coinPair, session.getAvailableKrw(), session.getTotalAssetKrw());
            } else {
                log.info("[Dynamic] 포지션 없음 — SCANNING 복귀 (id={}, {})", sid, coinPair);
            }
            transitionToScanning(sid);
            return;
        }

        PositionEntity pos = posOpt.get();

        BigDecimal pnlPct = currentPrice.subtract(pos.getAvgPrice())
                .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // 미실현손익 갱신 — 2026-08-03: 동적 포지션의 unrealized_pnl 이 전 이력 0이었다.
        // PositionService.updateUnrealizedPnl 은 호출부가 없는 죽은 코드였고, 비-0 값을 쓰는 곳은
        // LiveTradingService 하나뿐(LIVE 전용)이라 동적 세션 화면은 보유 중 항상 0을 표시했다.
        // SL/TP 판정은 아래에서 currentPrice 로 직접 하므로 매매 안전성과는 무관한 표시용 값이다.
        BigDecimal unrealized = currentPrice.subtract(pos.getAvgPrice()).multiply(pos.getSize());
        if (pos.getUnrealizedPnl() == null || pos.getUnrealizedPnl().compareTo(unrealized) != 0) {
            pos.setUnrealizedPnl(unrealized);
            positionRepository.save(pos);
        }

        // 세션 총자산 시가 평가 — 2026-08-06 신설 (LiveTradingService.updateSessionUnrealizedPnl 이식).
        //
        // <b>왜 필요한가 (2026-08-06 운영 DB 실측)</b>: 동적 세션의 totalAssetKrw 는 매도 시에만
        // 갱신돼 보유 중에는 <b>취득원가</b>에 고정돼 있었다. 세션 39·40·45가 각각 −1.3~−2.3%
        // 평가손 상태인데 셋 다 정확히 10,000.00 으로 기록됐다. 그 결과 {@link #updateMddPeak}가
        // 읽는 값도 원가라 <b>mddPeakCapital 이 절대 내려가지 않고</b>, risk_config 의
        // mdd_threshold_pct(20%)·max_portfolio_drawdown_pct(15%) 서킷 브레이커가
        // <b>포지션을 들고 있는 동안에는 원리상 발동할 수 없었다</b> — 손실이 실현되는 순간에만
        // 인식된다. 대시보드 총자산도 같은 이유로 실제보다 낙관적으로 표시됐다.
        //
        // size=0(매수 미체결)일 때 건드리지 않는 것은 LIVE와 동일하다 — 아직 코인이 없는데
        // 평가액을 더하면 KRW가 이중 계상된다.
        if (pos.getSize() != null && pos.getSize().compareTo(BigDecimal.ZERO) > 0) {
            final BigDecimal posValue = pos.getSize().multiply(currentPrice);
            // 넘어온 엔티티를 직접 save() 하지 않는다 — reconcile(5초)과의 @Version 충돌 회피.
            balanceUpdater.apply(sid, s -> s.setTotalAssetKrw(s.getAvailableKrw().add(posValue)));
            // 갱신된 총자산을 다시 읽어 MDD 피크가 시가 기준으로 움직이게 한다.
            session = dynamicSessionRepo.findById(sid).orElse(session);
        }

        // MDD 피크 갱신 — 위 시가 평가 이후에 호출해야 의미가 있다.
        updateMddPeak(session);

        // BLACK_SWAN_GUARD — 발동 시 알림만 보내고 **SL은 건드리지 않는다**.
        //
        // 2026-07-31 개편: 이전에는 발동 시 현재가 기준 1×ATR로 SL을 단방향 조임(ratchet)했다.
        // 이는 방향이 정반대인 구조적 오류였다 — 변동성이 폭증할 때 SL을 **좁히면** 정상 등락에
        // 확실히 걸린다. 실측으로 확인된 피해: pos 2368(KAITO, 조임 후 SL -3.54%)과
        // 2375(EDGE, -2.96%)가 둘 다 강제청산됐고, KAITO는 4시간 뒤 **+1.23%**로 회복했다.
        // 즉 조임이 없었다면 이익이었을 포지션을 조임이 손실로 확정시켰다.
        //
        // 블랙스완 방어는 신규 진입 차단(SCANNING 게이트)이 담당하며 그쪽은 실제로 유효하다
        // (차단된 신호의 사후 24h -7.04% — 차단이 옳았음). 보유 포지션의 변동성 방어는
        // 이제 진입 시점의 ATR 기반 SL(2 ATR)이 담당한다.
        BlackSwanGuard.Result blackSwanGuard = BlackSwanGuard.check(candles);
        if (blackSwanGuard.triggered()) {
            log.warn("[Dynamic] BLACK_SWAN_GUARD 발동 (id={}, {}): {} — SL 유지 {} (조임 없음)",
                    sid, coinPair, blackSwanGuard.reason(), pos.getStopLossPrice());
        }

        // 익절
        if (pos.getTakeProfitPrice() != null
                && currentPrice.compareTo(pos.getTakeProfitPrice()) >= 0) {
            log.info("[Dynamic] 익절: {} @ {} pnl={}% (id={})", coinPair, currentPrice, pnlPct, sid);
            executeSell(session, pos, currentPrice,
                    "익절 — 현재가 " + currentPrice + " ≥ " + pos.getTakeProfitPrice(),
                    ExitReason.TAKE_PROFIT);
            return;
        }

        // 손절
        BigDecimal slNeg = session.getStopLossPct().negate();
        boolean slTriggered = pos.getStopLossPrice() != null
                ? currentPrice.compareTo(pos.getStopLossPrice()) <= 0
                : pnlPct.compareTo(slNeg) <= 0;
        if (slTriggered) {
            // SL 이탈폭 계측 — 2026-08-03 ELSA 건에서 SL −5.82%인데 −7.911%에 잡혔다(2.1%p 초과).
            // 07-29~31 청산 6건은 초과폭이 수수료 수준(0.07~0.24%p)이었으므로 양상이 다르다.
            // 갭(60초 안에 뚫림)인지 감시 지연(WS 미구독 등)인지는 표본이 쌓여야 판정할 수 있어,
            // 여기서는 **고치지 않고 재기만 한다**. 초과폭이 반복적으로 크면 감시 경로를 손봐야 한다.
            if (pos.getStopLossPrice() != null && pos.getStopLossPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal overshootPct = pos.getStopLossPrice().subtract(currentPrice)
                        .divide(pos.getStopLossPrice(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                log.warn("[Dynamic] SL 이탈폭: {} SL={} 감지가={} 초과={}%p (id={}, posId={}) "
                                + "— 0에 가까우면 정상, 1%p 이상 반복되면 감시 지연 의심",
                        coinPair, pos.getStopLossPrice(), currentPrice,
                        overshootPct.setScale(3, RoundingMode.HALF_UP), sid, pos.getId());
            }
            log.warn("[Dynamic] 손절: {} @ {} pnl={}% (id={})", coinPair, currentPrice, pnlPct, sid);
            telegramService.notifyStopLoss(coinPair, pnlPct.doubleValue(), sid);
            executeSell(session, pos, currentPrice, "손절 — pnl " + pnlPct + "%", ExitReason.STOP_LOSS);
            return;
        }

        // 시간 초과 청산 (time stop) — SL/TP 는 가격 기반이라 저변동 종목에서는 영원히 도달하지
        // 않는다. 2026-07-31 세션 38 KRW-RLUSD(스테이블코인)가 42시간 고착돼 자본이 잠긴 사례.
        // 전략 SELL 경로도 "수익 0.3% 이상" 조건 때문에 구제책이 되지 못한다.
        // 손익과 무관하게 청산하며, 자본 회전을 되찾는 것이 목적이다.
        // 판정 로직은 ExitRuleCalculator로 이전(2026-08-06) — LIVE와 동일한 함수를 쓴다.
        Integer maxHoldHours = session.getMaxHoldHours();
        if (ExitRuleCalculator.shouldTimeStop(maxHoldHours, pos.getOpenedAt(), Instant.now())) {
            long heldHours = Duration.between(pos.getOpenedAt(), Instant.now()).toHours();
            log.warn("[Dynamic] 시간 초과 청산: {} 보유 {}h ≥ {}h pnl={}% (id={})",
                    coinPair, heldHours, maxHoldHours, pnlPct, sid);
            telegramService.notifyTimeStop(coinPair, heldHours, maxHoldHours, pnlPct.doubleValue(), sid);
            executeSell(session, pos, currentPrice, String.format(
                    "시간 초과 청산 — 보유 %d시간 ≥ %d시간 (pnl %s%%)",
                    heldHours, maxHoldHours, pnlPct.setScale(2, RoundingMode.HALF_UP)),
                    ExitReason.TIME_STOP);
            return;
        }

        // 닫힌 캔들 게이팅 후 전략 SELL 평가
        List<Candle> evalCandles = closedCandleSlice(candles, session.getTimeframe());
        String candleKey = sid + ":" + coinPair;
        Instant closedTime = evalCandles.get(evalCandles.size() - 1).getTime();
        Instant prevEval = lastEvaluatedCandle.get(candleKey);
        if (prevEval != null && !closedTime.isAfter(prevEval)) return;
        lastEvaluatedCandle.put(candleKey, closedTime);

        Strategy strategy = resolveStrategy(sid, coinPair, session.getStrategyType());
        StrategySignal signal = strategy.evaluate(evalCandles, Map.of("coinPair", coinPair));
        StrategyLogEntity signalLog = saveStrategyLog(session, session.getStrategyType(), coinPair, signal, currentPrice, evalCandles);

        if (signal.getAction() == StrategySignal.Action.SELL) {
            long heldMin = pos.getOpenedAt() != null
                    ? Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes() : Long.MAX_VALUE;
            if (heldMin < MIN_HOLD_MINUTES) {
                String blockReason = String.format("보유시간 미달: %d분 < %d분", heldMin, MIN_HOLD_MINUTES);
                log.debug("[Dynamic] SELL 차단: {} ({})", blockReason, coinPair);
                updateSignalQuality(signalLog, false, blockReason);
                return;
            }
            if (pnlPct.compareTo(MIN_PNL_PCT_FOR_SELL) < 0
                    && pnlPct.compareTo(LOSS_ESCAPE_THRESHOLD) >= 0) {
                String blockReason = String.format("본전 근처 pnl=%s%%", pnlPct);
                log.debug("[Dynamic] SELL 차단: {} ({})", blockReason, coinPair);
                updateSignalQuality(signalLog, false, blockReason);
                return;
            }
            executeSell(session, pos, currentPrice,
                    String.format("전략 SELL — %s (pnl=%s%%)", signal.getReason(), pnlPct),
                    ExitReason.STRATEGY_SIGNAL);
            updateSignalQuality(signalLog, true, null);
        } else if (signal.getAction() == StrategySignal.Action.BUY) {
            // 보유 중 BUY는 실행 대상이 아니다(추가 매수 미지원) — 그런데 여기서 기록을 남기지
            // 않으면 `was_executed=false, blocked_reason=null` 로 저장돼, "차단된 신호"인지
            // "그냥 평가만 된 신호"인지 DB만으로 구분할 수 없다.
            //
            // 2026-08-04 운영 확인: 세션 44가 KRW-SHIB 보유 중이던 08-04 01:00에 BUY 신호
            // (strategy_log 2038997)를 냈는데 사유가 비어 있어, 분석 시 "보유 중엔 신호가
            // 산출되지 않는다"는 잘못된 결론으로 이어졌다. 실제로는 산출됐고 조용히 버려졌다.
            // SCANNING 쪽 SELL 처리("SCANNING — 보유 포지션 없음")와 대칭을 맞춘다.
            updateSignalQuality(signalLog, false,
                    "POSITION_MONITORING — 이미 보유 중(신규 진입 대상 아님)");
        }
    }

    // ── 내부: 매수 실행 ────────────────────────────────────────────

    /**
     * BLACK_SWAN 재진입 게이트 판정 결과.
     *
     * @param blockReason 차단 사유 — {@code null}이면 통과
     * @param expired     가드 이력의 유효기간이 끝나 기록을 폐기해도 되는지
     */
    /**
     * 세션 간 동일코인 노출 상한 판정 — {@link #MAX_SESSIONS_PER_COIN} 초과 시 차단 사유를 준다.
     *
     * @param heldElsewhere 같은 코인을 들고 있는 <b>다른</b> 동적 세션 수
     * @return 차단 사유, 통과면 {@code null}
     */
    static String crossSessionExposureBlockReason(long heldElsewhere) {
        if (heldElsewhere < MAX_SESSIONS_PER_COIN) return null;
        return String.format("동일코인 노출 상한 — 다른 동적 세션이 이미 %d건 보유(상한 %d)",
                heldElsewhere, MAX_SESSIONS_PER_COIN);
    }

    record BlackSwanGateDecision(String blockReason, boolean expired) {}

    private static final BlackSwanGateDecision GATE_PASS = new BlackSwanGateDecision(null, false);
    private static final BlackSwanGateDecision GATE_EXPIRED = new BlackSwanGateDecision(null, true);

    /**
     * BLACK_SWAN_GUARD 차단 이력이 있는 코인의 재진입 가부를 판정한다 — 2단 게이트.
     *
     * <ol>
     *   <li><b>쿨다운</b>({@link #BLACK_SWAN_COOLDOWN_MIN}): 차단 직후는 무조건 막는다.
     *       가드 해제 직후가 가장 위험하다는 08-03 ELSA 실측 근거.</li>
     *   <li><b>진입가 가드</b>({@link #BLACK_SWAN_PRICE_GUARD_MIN}): 쿨다운이 끝나도
     *       <b>차단 시점가보다 비싸면</b> 계속 막는다. 가드가 위험하다고 본 가격보다 높은
     *       가격은 더 위험하다. 08-04 META2(차단가 8,630 → 9,150 진입 → 8,495 손절) 재발 방지.</li>
     * </ol>
     *
     * <p>기준가가 없으면(구 로그 복원 등) 진입가 가드는 자동으로 비활성이며 쿨다운만 작동한다.
     * 두 기간이 모두 지나면 {@code expired=true}로 알려 호출부가 이력을 지우게 한다 — 그래야
     * 상승 추세로 돌아선 종목이 영구 차단되지 않는다.</p>
     */
    static BlackSwanGateDecision evaluateBlackSwanGate(Instant blockedAt, BigDecimal blockedPrice,
                                                       BigDecimal currentPrice, Instant now) {
        if (blockedAt == null) return GATE_PASS;

        long elapsedMin = Duration.between(blockedAt, now).toMinutes();
        if (elapsedMin < BLACK_SWAN_COOLDOWN_MIN) {
            return new BlackSwanGateDecision(String.format(
                    "BLACK_SWAN 쿨다운 — %d분 전 차단된 종목 (해제까지 %d분)",
                    elapsedMin, BLACK_SWAN_COOLDOWN_MIN - elapsedMin), false);
        }
        if (elapsedMin >= BLACK_SWAN_PRICE_GUARD_MIN) {
            return GATE_EXPIRED;   // 가드 판단의 유효기간 종료 — 이력 폐기
        }
        boolean priceGuardActive = blockedPrice != null
                && blockedPrice.compareTo(BigDecimal.ZERO) > 0
                && currentPrice != null
                && currentPrice.compareTo(blockedPrice) > 0;
        if (priceGuardActive) {
            return new BlackSwanGateDecision(String.format(
                    "BLACK_SWAN 진입가 가드 — 차단 시점가 %s 초과 (현재 %s, 경과 %d분)",
                    blockedPrice.toPlainString(), currentPrice.toPlainString(), elapsedMin), false);
        }
        // 쿨다운은 끝났고 가격도 차단가 이하로 내려왔다 — 진입 허용하고 이력도 폐기한다.
        return GATE_EXPIRED;
    }

    /**
     * @return {@code null}이면 매수 성공(REAL은 주문 제출, PAPER는 즉시 체결), 아니면 차단 사유(신호품질 로그용)
     */
    @Transactional
    public String executeBuy(DynamicSessionEntity session, String coinPair,
                            List<Candle> evalCandles, StrategySignal signal, BigDecimal sizeMultiplier) {
        Long sid = session.getId();
        String kind = sessionKind(session);
        BigDecimal currentPrice = evalCandles.get(evalCandles.size() - 1).getClose();

        // EMA200 게이트 사이즈 배수 적용 — 정상 1.0, 근접 하회 감액 0.5. null은 방어적으로 1.0 취급.
        BigDecimal effectiveRatio = session.getInvestRatio()
                .multiply(sizeMultiplier != null ? sizeMultiplier : BigDecimal.ONE);
        BigDecimal investAmount = session.getAvailableKrw().multiply(effectiveRatio);
        BigDecimal minOrder = BigDecimal.valueOf(5000);
        // 감액 진입이 최소주문(5,000원) 미만이면, 정상 사이즈로는 최소주문을 넘는 한 최소주문액으로
        // 올려 진입을 살린다 — 소액 세션(자본 1만원)에서 절반 사이즈(4,000원)가 최소주문에 걸려
        // "가용 KRW 부족"으로 헛차단되는 것을 막는다 (2026-07-21).
        if (investAmount.compareTo(minOrder) < 0
                && session.getAvailableKrw().multiply(session.getInvestRatio()).compareTo(minOrder) >= 0
                && session.getAvailableKrw().compareTo(minOrder) >= 0) {
            investAmount = minOrder;
        }
        if (investAmount.compareTo(minOrder) < 0) {
            log.warn("[Dynamic] 매수 불가: 가용 KRW 부족 (id={})", sid);
            return String.format("가용 KRW 부족: 투자가능 %s원 < 최소 5,000원", investAmount.setScale(0, RoundingMode.DOWN));
        }

        boolean hasPendingBuy = orderRepository.existsBySessionKindAndSessionIdAndCoinPairAndSideAndStateIn(
                kind, sid, coinPair, "BUY", ACTIVE_ORDER_STATES);
        if (hasPendingBuy) return "미체결 BUY 주문 존재 — 중복 매수 차단";

        // SL / TP 계산 — ATR 기반 (2026-07-31 개편, 상단 상수 주석 참조). REAL/PAPER 100% 공유.
        // 세션 strategy_params 의 slAtrMultiplier/tpRrMultiplier 오버라이드를 적용한다(손절폭 A/B).
        ExitRuleOverrides exitOverrides = ExitRuleOverrides.from(session.getStrategyParams());
        BigDecimal slPct = ExitRuleCalculator.resolveStopLossPct(
                session.getStopLossPct(), evalCandles, currentPrice, exitOverrides);
        BigDecimal atrStopLossPrice = currentPrice.multiply(BigDecimal.ONE.subtract(
                        slPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(8, RoundingMode.HALF_DOWN);

        // 전략 제안 SL은 존중하되 **더 넓은 쪽을 채택**한다. 제안값이 ATR 기준보다 타이트하면
        // 그대로 휩쏘로 이어지므로(개편 전 실패 패턴), 전략의 의도는 방향에만 반영한다.
        // exitOverrides 가 걸린 세션(손절폭 A/B)은 예외 — suggestedStopLoss 가 세션 오버라이드를
        // 모른 채 arm 과 무관하게 동일해서, min() 을 타면 오버라이드가 조용히 무효화된다(2026-08-24).
        BigDecimal stopLossPrice = (!exitOverrides.isPresent() && signal.getSuggestedStopLoss() != null)
                ? signal.getSuggestedStopLoss().min(atrStopLossPrice)
                : atrStopLossPrice;

        BigDecimal takeProfitPrice = ExitRuleCalculator.resolveTakeProfitPrice(
                currentPrice, stopLossPrice, signal.getSuggestedTakeProfit(), exitOverrides);

        String rulesetHash = rulesetRegistry.hashFor(session);
        // V73: 진입 시점 레짐 — 이게 없으면 "이 전략은 횡보장에서만 되는가" 를 물을 수 없다.
        // 그동안 동적 세션 포지션은 레짐이 전부 NULL 이었다(0/36). 실패해도 진입은 막지 않는다.
        String entryRegime = null;
        try {
            MarketRegime detected = new MarketRegimeDetector().detectRaw(evalCandles);
            entryRegime = detected != null ? detected.name() : null;
        } catch (Exception e) {
            log.debug("[Dynamic] 레짐 판정 실패 — 레짐 없이 진입 (coin={}): {}", coinPair, e.toString());
        }
        PositionEntity posTemplate = PositionEntity.builder()
                .rulesetHash(rulesetHash)
                .marketRegime(entryRegime)
                .coinPair(coinPair)
                .side("BUY")
                .entryPrice(currentPrice)
                .avgPrice(currentPrice)
                .size(BigDecimal.ZERO)
                .investedKrw(investAmount)
                .status("OPEN")
                .sessionId(sid)
                .sessionKind(kind)
                .stopLossPrice(stopLossPrice)
                .takeProfitPrice(takeProfitPrice)
                .build();

        if (session.isPaper()) {
            return executePaperBuy(session, posTemplate, investAmount, coinPair, signal, kind);
        }

        PositionEntity pos = positionRepository.save(posTemplate);
        Long posId = pos.getId();

        OrderRequest order = new OrderRequest();
        order.setCoinPair(coinPair);
        order.setSide("BUY");
        order.setOrderType("MARKET");
        order.setQuantity(investAmount);
        order.setReason("동적 세션 BUY — " + signal.getReason());
        order.setSignalPrice(currentPrice);  // §14 drift 측정 기준가 보존
        order.setSessionId(sid);
        order.setSessionKind(kind);
        order.setPositionId(posId);
        // ⚠️ 커밋 이후 제출 — 위 pos 는 아직 미커밋이라 @Async 주문 스레드에서 보이지 않는다.
        //    즉시 제출하면 order_position_id_fkey 대기 → 타임아웃/데드락으로 주문 INSERT가
        //    통째로 롤백된다 (2026-07-29 P0: 동적 주문 0건의 원인).
        orderExecutionEngine.submitOrderAfterCommit(order);

        // KRW 차감 및 상태 전환 — 낙관적 락 + 재시도 (reconcile 스케줄러와의 동시 쓰기 race 차단)
        final BigDecimal deductAmount = investAmount;   // 람다 캡처용 (최소주문 보정으로 재할당됐을 수 있음)
        balanceUpdater.apply(sid, s -> {
            s.setAvailableKrw(s.getAvailableKrw().subtract(deductAmount));
            s.setScanState("POSITION_MONITORING");
            s.setCurrentCoinPair(coinPair);
            s.setCurrentPositionId(posId);
        });
        registerBuyDeductionCompensation(sid, deductAmount);

        log.info("[Dynamic] 매수: id={} {} amount={} SL={} TP={}",
                sid, coinPair, investAmount, stopLossPrice, takeProfitPrice);

        // 실시간(WS) 손절/익절 감시 대상에 즉시 반영 — 폴링(60초) 대기 없이 다음 tick 전에도 방어
        refreshWsSubscription();
        return null;
    }

    /**
     * PAPER 매수 — 실거래소(OrderExecutionEngine)를 전혀 거치지 않고, 슬리피지·수수료를 반영해
     * 같은 트랜잭션 안에서 동기적으로 체결을 시뮬레이션한다.
     *
     * <p>REAL 경로가 async 제출 + 별도 콜백(체결 시 size/avgPrice 갱신) + REQUIRES_NEW 잔고
     * 차감 + 롤백 보상을 쓰는 이유는 전부 "실거래소 응답을 기다려야 한다"는 제약 때문이다.
     * PAPER는 그 제약이 없으므로 이 비동기 기계장치 전체를 우회한다 — REAL의 회귀 위험을
     * 조금도 늘리지 않으면서, 07-29/07-31/08-03 P0(주문 롤백·유령 포지션·잔고 누수)가 났던
     * 바로 그 경로 자체를 PAPER가 절대 타지 않게 하는 설계다.</p>
     */
    private String executePaperBuy(DynamicSessionEntity session, PositionEntity posTemplate,
                                    BigDecimal investAmount, String coinPair,
                                    StrategySignal signal, String kind) {
        Long sid = session.getId();
        BigDecimal signalPrice = posTemplate.getEntryPrice();

        // 슬리피지 — 매수는 신호가보다 불리하게(높게) 체결된다 (PaperTradingService와 동일 값).
        BigDecimal fillPrice = signalPrice.multiply(BigDecimal.ONE.add(PAPER_SLIPPAGE_PCT))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal fee = investAmount.multiply(FEE_RATE);
        BigDecimal netAmount = investAmount.subtract(fee);
        BigDecimal quantity = netAmount.divide(fillPrice, 8, RoundingMode.DOWN);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return "체결 수량 0 — 투자금 대비 가격 과다";
        }
        // avgPrice = 수수료 포함 실제 취득단가 (investAmount / quantity) — costBasis = investAmount가
        // 되어 청산 시(finalizeDynamicSell) 실현손익이 정확히 계산된다. LIVE 정렬 PaperTradingService와 동일 공식.
        BigDecimal avgPriceWithFee = investAmount.divide(quantity, 8, RoundingMode.HALF_UP);

        posTemplate.setEntryPrice(fillPrice);
        posTemplate.setAvgPrice(avgPriceWithFee);
        posTemplate.setSize(quantity);
        posTemplate.setPositionFee(fee);
        PositionEntity pos = positionRepository.save(posTemplate);

        OrderEntity order = OrderEntity.builder()
                .positionId(pos.getId())
                .coinPair(coinPair)
                .side("BUY")
                .orderType("MARKET")
                .price(fillPrice)
                .quantity(investAmount)
                .filledQuantity(quantity)
                .state("FILLED")
                .exchangeOrderId("PAPER-DYNAMIC-" + pos.getId())
                .signalReason("동적 세션 BUY(PAPER) — " + signal.getReason())
                .signalPrice(signalPrice)
                .sessionId(sid)
                .sessionKind(kind)
                .filledAt(Instant.now())
                .build();
        orderRepository.save(order);

        // 비동기 갭이 없으므로 REQUIRES_NEW·롤백 보상 없이 세션을 직접 갱신한다 — 트랜잭션이
        // 롤백되면 position/order/session 변경 전부가 함께 롤백되어 REAL이 겪던 "차감만
        // 살아남는" 부분 롤백 자체가 구조적으로 발생할 수 없다.
        session.setAvailableKrw(session.getAvailableKrw().subtract(investAmount));
        session.setScanState("POSITION_MONITORING");
        session.setCurrentCoinPair(coinPair);
        session.setCurrentPositionId(pos.getId());
        dynamicSessionRepo.save(session);

        log.info("[Dynamic][PAPER] 매수 체결: id={} {} {}개 @ {} SL={} TP={} (수수료={})",
                sid, coinPair, quantity, fillPrice, pos.getStopLossPrice(), pos.getTakeProfitPrice(), fee);
        return null;
    }

    /**
     * 매수 KRW 차감의 <b>롤백 보상</b>을 등록한다 — 부모 트랜잭션이 롤백되면 차감을 되돌린다.
     *
     * <p><b>왜 필요한가 (2026-08-03 P0)</b>: {@link #executeBuy}의 KRW 차감은
     * {@link DynamicSessionBalanceUpdater#apply}를 통하는데, 이는 낙관적 락 재시도를 위해
     * {@code REQUIRES_NEW} 별도 트랜잭션이라 <b>부모보다 먼저 커밋</b>된다. 이후 부모 tx
     * ({@code processScanningTick})가 롤백되면:</p>
     * <ul>
     *   <li>position INSERT 소멸 → 포지션 없음</li>
     *   <li>{@code submitOrderAfterCommit}는 afterCommit 훅이라 미발화 → 주문 행조차 없음</li>
     *   <li>{@code strategy_log} 신호 로그도 같은 tx라 함께 소멸 → <b>흔적이 전혀 남지 않는다</b></li>
     *   <li>그런데 <b>KRW 차감만 살아남는다</b></li>
     * </ul>
     *
     * <p>운영 증거(2026-08-03): 동적 세션 39·40·44가 포지션·주문·신호로그 0건인 채
     * {@code available_krw}만 10,000 → 2,000으로 줄어 있었다(각 8,000원, 합 24,000원).
     * 투자가능액이 {@code 2,000 × 0.8 = 1,600원}이 되어 최소주문 5,000원에 걸려
     * <b>세 세션이 영구 매수 불능</b>이 됐다(세션 44는 07-31 12:00 유일한 BUY 신호를
     * "가용 KRW 부족"으로 차단당함).</p>
     *
     * <p>{@link #reconcileDynamicOrphanBuyPositions}가 못 잡는 이유: 그 안전망은
     * {@code position} 행을 기준으로 순회하는데, 이 경우 포지션 자체가 롤백돼 없다.
     * 세션 기준 안전망은 {@link #reconcileDynamicSessionBalance}가 담당한다(2차 방어).</p>
     *
     * <p>차감을 afterCommit으로 미루지 <b>않은</b> 이유: 그러면 커밋 후 차감이 실패했을 때
     * 포지션·주문은 살아있는데 KRW가 안 줄어 <b>이중 매수</b>가 가능해진다. 미차감(과투자)보다
     * 과차감(보상 가능)이 안전하므로, 선차감 + 롤백 보상 구조를 택했다.</p>
     */
    // 테스트에서 롤백 시나리오를 직접 구동하기 위해 package-private (resolveStopLossPct와 동일 방침)
    void registerBuyDeductionCompensation(Long sessionId, BigDecimal deductAmount) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;   // 트랜잭션 밖 호출 — 롤백될 부모가 없으므로 보상 대상도 없다
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try {
                    balanceUpdater.apply(sessionId, s -> {
                        s.setAvailableKrw(s.getAvailableKrw().add(deductAmount));
                        s.setScanState("SCANNING");
                        s.setCurrentCoinPair(null);
                        s.setCurrentPositionId(null);
                    });
                    log.error("[Dynamic] 매수 tx 롤백 — KRW 차감 보상 완료 (id={}, 복원금액={})",
                            sessionId, deductAmount);
                } catch (Exception e) {
                    // 보상까지 실패하면 reconcileDynamicSessionBalance 가 다음 주기에 잡는다
                    log.error("[Dynamic] 🔴 매수 tx 롤백 보상 실패 — 세션 잔고 누수 (id={}, 금액={})",
                            sessionId, deductAmount, e);
                }
            }
        });
    }

    // ── 내부: 매도 실행 ────────────────────────────────────────────

    @Transactional
    /**
     * @param exitReason 집계용 청산 사유 (V73). CLOSING 전환과 같은 UPDATE 로 기록되므로
     *                   실거래의 비동기 매도에서도 reconcile 시점까지 보존된다.
     */
    public void executeSell(DynamicSessionEntity session, PositionEntity pos,
                             BigDecimal currentPrice, String reason, ExitReason exitReason) {
        Long sid = session.getId();

        if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Dynamic] 매도 건너뜀: size=0 (posId={}, id={})", pos.getId(), sid);
            return;
        }

        // 원자적 CLOSING 전환 — WS 실시간 SL/TP와 60초 tick이 동시에 같은 포지션을 팔려는
        // race에서 한쪽만 매도 주문을 제출하도록 보장 (시장가 이중 매도 방지)
        int marked = positionRepository.markClosingIfOpen(pos.getId(), Instant.now(),
                exitReason != null ? exitReason : ExitReason.UNKNOWN);
        if (marked == 0) {
            log.debug("[Dynamic] 매도 건너뜀: 이미 CLOSING/CLOSED (posId={}, id={})", pos.getId(), sid);
            return;
        }

        if (session.isPaper()) {
            executePaperSell(session, pos, currentPrice, reason, exitReason);
            return;
        }

        OrderRequest order = new OrderRequest();
        order.setCoinPair(pos.getCoinPair());
        order.setSide("SELL");
        order.setOrderType("MARKET");
        order.setQuantity(pos.getSize());
        order.setReason(reason);
        order.setSignalPrice(currentPrice);  // §14 drift 측정 기준가 보존
        order.setSessionId(sid);
        order.setSessionKind(sessionKind(session));
        order.setPositionId(pos.getId());
        orderExecutionEngine.submitOrder(order);

        // KRW 복원은 reconcile 에서 처리 — 여기서는 상태만 전환
        transitionToScanning(sid);
        log.info("[Dynamic] 매도 주문: id={} {} size={}", sid, pos.getCoinPair(), pos.getSize());
    }

    /**
     * PAPER 매도 — {@code markClosingIfOpen}까지는 REAL과 동일하게 거친 뒤, 실거래소 제출 대신
     * 슬리피지를 반영한 체결 주문을 즉시 만들어 {@link #finalizeDynamicSell}에 넘긴다.
     *
     * <p>{@code finalizeDynamicSell}을 그대로 재사용하는 것이 핵심이다 — 손익·수수료·부분체결·
     * 세션 노출(hasRemainingExposure) 계산 로직을 중복 구현하지 않고, REAL과 <b>완전히 같은
     * 코드로</b> 검증한다(이번 정렬 작업의 목적 그 자체).</p>
     */
    private void executePaperSell(DynamicSessionEntity session, PositionEntity pos,
                                   BigDecimal signalPrice, String reason, ExitReason exitReason) {
        // 슬리피지 — 매도는 신호가보다 불리하게(낮게) 체결된다.
        BigDecimal fillPrice = signalPrice.multiply(BigDecimal.ONE.subtract(PAPER_SLIPPAGE_PCT))
                .setScale(8, RoundingMode.HALF_DOWN);

        OrderEntity order = OrderEntity.builder()
                .positionId(pos.getId())
                .coinPair(pos.getCoinPair())
                .side("SELL")
                .orderType("MARKET")
                .price(fillPrice)
                .quantity(pos.getSize())
                .filledQuantity(pos.getSize())
                .state("FILLED")
                .exchangeOrderId("PAPER-DYNAMIC-SELL-" + pos.getId())
                .signalReason(reason)
                .signalPrice(signalPrice)
                .sessionId(session.getId())
                .sessionKind(sessionKind(session))
                .filledAt(Instant.now())
                .build();
        order = orderRepository.save(order);

        pos.setExitReason(exitReason != null ? exitReason : ExitReason.UNKNOWN);
        finalizeDynamicSell(pos, order);
        transitionToScanning(session.getId());
        log.info("[Dynamic][PAPER] 매도 체결: id={} {} size={} @ {}",
                session.getId(), pos.getCoinPair(), pos.getSize(), fillPrice);
    }

    // ── 내부: 손실 청산 쿨다운 ────────────────────────────────────

    /**
     * 이 세션에서 해당 코인의 가장 최근 청산이 손실이고, 청산 후 {@code cooldownMinutes}가
     * 지나지 않았으면 true — SCANNING 재진입을 차단한다 (라이브 191 반복 손절 패턴 방지).
     */
    private boolean isInLossCooldown(String kind, Long sessionId, String coinPair, int cooldownMinutes) {
        if (cooldownMinutes <= 0) return false;
        return positionRepository
                .findTopBySessionKindAndSessionIdAndCoinPairAndStatusOrderByClosedAtDesc(
                        kind, sessionId, coinPair, "CLOSED")
                .filter(p -> p.getRealizedPnl() != null
                        && p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0)
                .filter(p -> p.getClosedAt() != null
                        && Duration.between(p.getClosedAt(), Instant.now()).toMinutes() < cooldownMinutes)
                .isPresent();
    }

    // ── 내부: 워치리스트 관리 ─────────────────────────────────────

    @Transactional
    public List<String> resolveWatchlist(DynamicSessionEntity session) {
        boolean needsRefresh = session.getWatchlistRefreshedAt() == null
                || Duration.between(session.getWatchlistRefreshedAt(), Instant.now()).toMinutes()
                        >= session.getWatchlistRefreshMin();

        if (!needsRefresh && session.getWatchlistJson() != null) {
            return parseWatchlistJson(session.getWatchlistJson());
        }

        log.info("[Dynamic] 워치리스트 갱신 (id={})", session.getId());

        // 품질 큐레이션 기준 — risk_config override 우선, NULL이면 코드 기본값. 원시 유니버스
        // (거래대금 상위)를 유동성·변동성 상한·상승추세·비급락으로 걸러 진입 게이트와 상쇄되는
        // 잡코인을 앞단에서 배제한다 (2026-07-24). WatchlistQualityGate 참조.
        com.cryptoautotrader.api.entity.RiskConfigEntity riskConfig = riskManagementService.getRiskConfig();
        BigDecimal minTradeValueKrw = riskConfig.getScanMinTradeValueKrw() != null
                ? riskConfig.getScanMinTradeValueKrw() : SCAN_MIN_TRADE_VALUE_KRW;
        BigDecimal maxAtrPct = riskConfig.getScanMaxAtrPct() != null
                ? riskConfig.getScanMaxAtrPct() : SCAN_MAX_ATR_PCT;
        boolean requireUptrend = riskConfig.getScanRequireUptrend() != null
                ? riskConfig.getScanRequireUptrend() : SCAN_REQUIRE_UPTREND;
        boolean excludeCrashing = riskConfig.getScanExcludeCrashing() != null
                ? riskConfig.getScanExcludeCrashing() : SCAN_EXCLUDE_CRASHING;
        WatchlistFilterService.QualityCriteria criteria = new WatchlistFilterService.QualityCriteria(
                minTradeValueKrw, maxAtrPct, requireUptrend, excludeCrashing);

        List<String> fresh = watchlistFilterService.buildWatchlist(
                session.getMaxCandidateSize(),
                session.getTargetWatchSize(),
                session.getMinAtrPct(),
                session.getMaxSpreadPct(),
                session.getTimeframe(),
                criteria);

        try {
            DynamicSessionEntity toUpdate = getOrThrow(session.getId());
            toUpdate.setWatchlistJson(objectMapper.writeValueAsString(fresh));
            toUpdate.setWatchlistRefreshedAt(Instant.now());
            dynamicSessionRepo.save(toUpdate);
        } catch (Exception e) {
            log.warn("[Dynamic] 워치리스트 저장 실패: {}", e.getMessage());
        }

        return fresh;
    }

    private List<String> parseWatchlistJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── 내부: 전략 인스턴스 ────────────────────────────────────────

    private Strategy resolveStrategy(Long sessionId, String coinPair, String strategyType) {
        if (!StrategyRegistry.isStateful(strategyType)) {
            return StrategyRegistry.get(strategyType);
        }
        String key = sessionId + ":" + coinPair;
        return strategyInstances.computeIfAbsent(key, k -> StrategyRegistry.createNew(strategyType));
    }

    // ── 내부: 전략 로그 (전략로그 페이지 노출용) ──────────────────

    /**
     * 평가된 신호를 {@code strategy_log} 테이블에 저장한다 — {@code sessionType="DYNAMIC"}.
     * 라이브 매매({@link LiveTradingService})와 동일하게 모든 평가(HOLD 포함)를 저장해야
     * 전략로그 화면과 신호 품질 통계(/api/v1/logs/signal-stats)에서 동적 세션도 보인다.
     * 이전까지는 application log 에만 남고 DB에는 전혀 기록되지 않아 전략로그 화면이 비어 있었다.
     */
    private StrategyLogEntity saveStrategyLog(DynamicSessionEntity session, String strategyName, String coinPair,
                                               StrategySignal signal, BigDecimal signalPrice,
                                               List<Candle> evalCandles) {
        try {
            BigDecimal conf = (signal.getAction() != StrategySignal.Action.HOLD && signal.getStrength() != null)
                    ? signal.getStrength().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                    : null;
            StrategyLogEntity entity = StrategyLogEntity.builder()
                    .rulesetHash(rulesetRegistry.hashFor(session))
                    .strategyName(strategyName)
                    .coinPair(coinPair)
                    .signal(signal.getAction().name())
                    .reason(signal.getReason())
                    .sessionType(sessionKind(session))
                    .sessionId(session.getId())
                    .signalPrice(signalPrice)
                    .confidenceScore(conf)
                    // 2026-08-19: DYNAMIC 은 레짐도 지표 스냅샷도 안 남기고 있었다.
                    // 사후 분석이 reason 문자열 파싱에 의존하던 원인이다.
                    .marketRegime(detectRegimeQuietly(evalCandles))
                    .indicatorsJson(IndicatorSnapshot.of(evalCandles, true,
                            evalCandles != null && !evalCandles.isEmpty()
                                    ? evalCandles.get(evalCandles.size() - 1).getTime() : null,
                            null))
                    .build();
            return strategyLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("[Dynamic] 전략 로그 저장 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 신호 품질 로그 실행 결과 업데이트 (null-safe) */
    private void updateSignalQuality(StrategyLogEntity logEntity, boolean wasExecuted, String blockedReason) {
        if (logEntity == null) return;
        try {
            logEntity.setWasExecuted(wasExecuted);
            logEntity.setBlockedReason(blockedReason);
            strategyLogRepository.save(logEntity);
        } catch (Exception e) {
            log.warn("[Dynamic] 신호 품질 로그 업데이트 실패: {}", e.getMessage());
        }
    }

    // ── 내부: 캔들 조회 ────────────────────────────────────────────

    private List<Candle> fetchCandles(String coinPair, String timeframe) {
        if (upbitRestClient == null) return List.of();
        try {
            Instant to = Instant.now();
            Instant from = to.minus(CANDLE_LOOKBACK * TimeframeUtils.toMinutes(timeframe), ChronoUnit.MINUTES);
            return new UpbitCandleCollector(upbitRestClient).fetchCandles(coinPair, timeframe, from, to);
        } catch (Exception e) {
            log.warn("[Dynamic] 캔들 조회 실패 ({} {}): {}", coinPair, timeframe, e.getMessage());
            return List.of();
        }
    }

    private List<Candle> closedCandleSlice(List<Candle> candles, String timeframe) {
        if (candles.size() < 2) return candles;
        long periodMin = TimeframeUtils.toMinutes(timeframe);
        Instant lastTime = candles.get(candles.size() - 1).getTime();
        boolean closed = !lastTime.plus(periodMin, ChronoUnit.MINUTES).isAfter(Instant.now());
        return closed ? candles : candles.subList(0, candles.size() - 1);
    }

    // ── 내부: 상태 전환 ────────────────────────────────────────────

    public void transitionToScanning(Long sessionId) {
        balanceUpdater.apply(sessionId, s -> {
            s.setScanState("SCANNING");
            s.setCurrentCoinPair(null);
            s.setCurrentPositionId(null);
            s.setWatchlistRefreshedAt(null); // 다음 스캔에서 즉시 재필터링
        });
        log.info("[Dynamic] SCANNING 복귀 (id={})", sessionId);
        refreshWsSubscription();
    }

    private void updateMddPeak(DynamicSessionEntity session) {
        if (session.getMddPeakCapital() == null
                || session.getTotalAssetKrw().compareTo(session.getMddPeakCapital()) > 0) {
            // 넘어온 엔티티를 직접 save()하면 reconcile(5초)과의 @Version 충돌로
            // 같은 tick의 후속 SL/TP 검사까지 통째로 실패할 수 있다 — 낙관적 락 재시도 경유
            balanceUpdater.apply(session.getId(), s -> {
                if (s.getMddPeakCapital() == null
                        || s.getTotalAssetKrw().compareTo(s.getMddPeakCapital()) > 0) {
                    s.setMddPeakCapital(s.getTotalAssetKrw());
                }
            });
        }
    }

    // ── 내부: 청산 / 정리 ──────────────────────────────────────────

    private void closeOpenPositions(DynamicSessionEntity session, String reason) {
        List<PositionEntity> opens = positionRepository
                .findBySessionKindAndSessionIdAndStatus(sessionKind(session), session.getId(), "OPEN");
        for (PositionEntity pos : opens) {
            if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
                pos.setStatus("CLOSED");
                pos.setClosedAt(Instant.now());
                positionRepository.save(pos);
                continue;
            }
            // WS 실시간 SL/TP 매도와의 race 방지 — 이미 CLOSING이면 매도 주문 중복 제출 스킵
            // 운영자 개입 청산 — 청산가가 시장이 아니라 정지 시각으로 정해지므로 전략 성과와 섞으면 안 된다.
            if (positionRepository.markClosingIfOpen(pos.getId(), Instant.now(),
                    ExitReason.FORCED_STOP) == 0) {
                continue;
            }

            if (session.isPaper()) {
                // 현재가 정보가 없는 강제 정지 경로 — 진입가를 신호가로 대체(슬리피지만 반영).
                // 정지/비상정지는 드문 경로이고, 정확한 청산가보다 "확실히 닫힌다"가 중요하다.
                executePaperSell(session, pos, pos.getAvgPrice(), reason, ExitReason.FORCED_STOP);
                continue;
            }

            OrderRequest order = new OrderRequest();
            order.setCoinPair(pos.getCoinPair());
            order.setSide("SELL");
            order.setOrderType("MARKET");
            order.setQuantity(pos.getSize());
            order.setReason(reason);
            order.setSessionId(session.getId());
            order.setSessionKind(sessionKind(session));
            order.setPositionId(pos.getId());
            orderExecutionEngine.submitOrder(order);
        }
    }

    // ── 스케줄: CLOSING 포지션 정리 (5초 주기) ──────────────────────

    /**
     * CLOSING 상태의 동적 포지션을 SELL 주문 체결/실패 결과에 따라 확정/롤백한다.
     *
     * <p>{@link #executeSell}은 포지션을 CLOSING으로만 표시하고 비동기 주문을 제출한 뒤
     * 즉시 SCANNING으로 전환한다. 실제 KRW 복원과 손익 확정은 이 스케줄러가 전담한다.
     * 이 처리가 없으면 동적 세션은 매도할 때마다 {@code availableKrw}가 복원되지 않아
     * 몇 차례 매매 후 투자 가능 금액이 5,000원 미만으로 줄어 영구적으로 매수가 멈춘다
     * (2026-07-01 동적 멀티코인 로직 분석 — session_kind 컬럼 부재로 라이브 reconcile이
     * 동적 세션 KRW를 복원하지 못하던 근본 원인).</p>
     */
    @Scheduled(fixedDelay = 5_000)
    @Transactional
    public void reconcileDynamicClosingPositions() {
        List<PositionEntity> closingPositions =
                positionRepository.findBySessionKindAndStatus(SESSION_KIND, "CLOSING");
        if (closingPositions.isEmpty()) return;

        for (PositionEntity pos : closingPositions) {
            List<OrderEntity> sellOrders = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                    .toList();

            if (sellOrders.isEmpty()) {
                log.warn("[Dynamic] CLOSING 포지션에 SELL 주문 없음 — OPEN 롤백 (posId={})", pos.getId());
                pos.setStatus("OPEN");
                pos.setClosingAt(null);
                positionRepository.save(pos);
                reattachRolledBackPosition(pos);
                continue;
            }

            // ⚠️ 2026-08-03: 최신 주문이 아니라 **FILLED 주문을 먼저 본다**.
            //    07-31 P0에서 매도 8610이 FILLED된 뒤 후처리가 롤백돼 8611~8613이 연속 FAILED로
            //    쌓였는데, `sellOrders.get(0)`(최신순)이 FAILED를 집어 **OPEN 롤백 분기**를 탔다.
            //    코인은 이미 팔렸으므로 이 롤백은 유령 포지션을 만들고 매도 재시도 루프를 낳는다.
            //    체결은 되돌릴 수 없는 사실이므로, FILLED가 하나라도 있으면 그것이 진실이다.
            OrderEntity filledSell = sellOrders.stream()
                    .filter(o -> "FILLED".equals(o.getState()))
                    .findFirst()   // sellOrders는 createdAt DESC — 가장 최근 체결분
                    .orElse(null);
            if (filledSell != null) {
                if (sellOrders.indexOf(filledSell) > 0) {
                    log.warn("[Dynamic] 매도 주문 다건 — FAILED 재시도 뒤의 FILLED를 채택 "
                                    + "(posId={}, 채택 orderId={}, 후속 주문 {}건)",
                            pos.getId(), filledSell.getId(), sellOrders.indexOf(filledSell));
                }
                finalizeDynamicSell(pos, filledSell);
                continue;
            }

            OrderEntity latestSell = sellOrders.get(0);
            switch (latestSell.getState()) {
                case "FILLED" -> finalizeDynamicSell(pos, latestSell);
                case "FAILED", "CANCELLED" -> {
                    log.warn("[Dynamic] 매도 주문 {} — 포지션 OPEN 롤백 (orderId={}, posId={}, sessionId={})",
                            latestSell.getState(), latestSell.getId(), pos.getId(), pos.getSessionId());
                    pos.setStatus("OPEN");
                    pos.setClosingAt(null);
                    positionRepository.save(pos);
                    reattachRolledBackPosition(pos);
                }
                default -> {
                    Instant closingAt = pos.getClosingAt();
                    if (closingAt != null
                            && Duration.between(closingAt, Instant.now()).toMinutes() >= CLOSING_TIMEOUT_MINUTES) {
                        log.warn("[Dynamic] CLOSING 타임아웃 ({}분 초과) — OPEN 롤백 (posId={}, sessionId={})",
                                CLOSING_TIMEOUT_MINUTES, pos.getId(), pos.getSessionId());
                        pos.setStatus("OPEN");
                        pos.setClosingAt(null);
                        positionRepository.save(pos);
                        reattachRolledBackPosition(pos);
                    }
                }
            }
        }
    }

    /**
     * <b>유령 포지션 안전망</b> — 매도가 FILLED인데 포지션이 여전히 {@code OPEN}인 상태를 정리한다.
     *
     * <p><b>왜 필요한가 (2026-07-31 P0)</b>: {@code executeSell}은
     * {@code markClosingIfOpen}(CLOSING 전환) → {@code submitOrder}(@Async, 별도 tx) →
     * {@code transitionToScanning} 순서다. 부모 tx가 롤백되면 <b>주문만 살아남고</b>
     * CLOSING 전환이 사라져 포지션이 OPEN으로 되돌아간다. 코인은 이미 팔렸는데 DB는 보유 중이다.</p>
     *
     * <p>운영 피해(07-31): 세션 38 KRW-RLUSD가 이 상태에 빠져 매 틱 time stop이 재발동,
     * 주문 8611·8612·8613이 <b>69초 간격으로 FAILED</b>(업비트 HTTP 400 — 이미 판 코인)를
     * 4분간 반복했다. 결국 손으로 2행을 UPDATE해 정리해야 했다.</p>
     *
     * <p>{@link #reconcileDynamicClosingPositions}가 못 잡는 이유: 그쪽은 <b>CLOSING</b> 상태만
     * 순회한다. 롤백으로 OPEN이 되어버린 포지션은 그 그물에 애초에 걸리지 않는다.</p>
     *
     * <p><b>부분 체결과의 구분</b>: 부분 체결분을 정산하면 포지션은 {@code OPEN}으로 남고
     * FILLED 주문도 그대로 남는다 — 이를 다시 정산하면 KRW가 이중 복원된다. 그래서
     * ①{@code realizedPnl == 0}(한 번도 정산된 적 없음) ②{@code filledQuantity == size}(전량 체결)
     * 두 조건을 모두 요구한다. 부분 정산 후에는 두 조건이 <b>동시에</b> 깨지므로 재진입이 불가능하다.</p>
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void reconcileDynamicGhostPositions() {
        for (PositionEntity pos : positionRepository.findBySessionKindAndStatus(SESSION_KIND, "OPEN")) {
            if (pos.getSize() == null || pos.getSize().compareTo(BigDecimal.ZERO) <= 0) {
                continue;   // 매수 고아 — reconcileDynamicOrphanBuyPositions 담당
            }
            BigDecimal realized = pos.getRealizedPnl();
            if (realized != null && realized.compareTo(BigDecimal.ZERO) != 0) {
                continue;   // 이미 한 번 정산됨(부분 체결) — 이중 복원 방지
            }

            OrderEntity filledSell = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "SELL".equalsIgnoreCase(o.getSide()) && "FILLED".equals(o.getState()))
                    .findFirst()
                    .orElse(null);
            if (filledSell == null) continue;   // 정상 보유 중

            if (filledSell.getFilledQuantity() == null
                    || filledSell.getFilledQuantity().compareTo(pos.getSize()) != 0) {
                continue;   // 전량 체결이 아님 — 부분 체결 경로가 처리
            }
            if (filledSell.getFilledAt() == null
                    || Duration.between(filledSell.getFilledAt(), Instant.now()).toMinutes()
                       < SELL_FINALIZE_GRACE_MIN) {
                continue;   // 정상 매도 진행 중일 수 있는 구간 — 건드리지 않는다
            }

            log.error("[Dynamic] 🔴 유령 포지션 감지: 매도 FILLED인데 포지션 OPEN — 자동 정산 "
                            + "(posId={}, sessionId={}, {}, orderId={}). 매도 후처리 tx 롤백 의심.",
                    pos.getId(), pos.getSessionId(), pos.getCoinPair(), filledSell.getId());

            finalizeDynamicSell(pos, filledSell);

            // 롤백으로 세션이 POSITION_MONITORING에 고착됐을 수 있다 — executeSell의
            // transitionToScanning도 같은 tx에서 함께 사라졌기 때문. 이 포지션을 보고 있으면 풀어준다.
            Long sessionId = pos.getSessionId();
            if (sessionId != null) {
                DynamicSessionEntity session = dynamicSessionRepo.findById(sessionId).orElse(null);
                boolean watchingThisPosition = session != null
                        && pos.getId().equals(session.getCurrentPositionId());
                // currentPositionId 까지 유실된 경우(롤백 범위가 더 넓었던 경우)에도 풀어준다.
                // 단 코인만 같고 positionId 가 **다른** 값이면 그 사이 재매수한 것이므로 건드리지 않는다.
                boolean watchingThisCoinOrphaned = session != null
                        && session.getCurrentPositionId() == null
                        && pos.getCoinPair().equals(session.getCurrentCoinPair());
                if (watchingThisPosition || watchingThisCoinOrphaned) {
                    transitionToScanning(sessionId);
                }
            }
        }
    }

    /**
     * 매도 실패/타임아웃으로 OPEN 롤백된 포지션을 세션 감시 대상에 재결속한다.
     *
     * <p>{@link #executeSell}은 매도 주문 제출과 동시에 세션을 SCANNING으로 전환하므로,
     * 매도가 FAILED/CANCELLED로 끝나 포지션이 OPEN으로 돌아와도 세션은 이미 다른 곳을
     * 보고 있다. 이 재결속이 없으면 롤백된 포지션은 SL/TP 감시·매도 재시도 없이 영구
     * 방치되고(WS 구독에서도 빠짐), 세션이 남은 KRW로 두 번째 코인을 사버릴 수도 있다.</p>
     */
    private void reattachRolledBackPosition(PositionEntity pos) {
        Long sessionId = pos.getSessionId();
        if (sessionId == null) return;

        DynamicSessionEntity session = dynamicSessionRepo.findById(sessionId).orElse(null);
        if (session == null) return;

        if (!"RUNNING".equals(session.getStatus())) {
            log.error("[Dynamic] 매도 롤백 포지션 재결속 불가 — 세션 비가동 (posId={}, sessionId={}, status={}). 수동 조치 필요",
                    pos.getId(), sessionId, session.getStatus());
            telegramService.sendCustomNotification(String.format(
                    "⚠️ [동적#%d] 매도 실패 포지션 방치 위험: %s (posId=%d) — 세션이 %s 상태라 자동 재결속 불가. 수동 청산 필요",
                    sessionId, pos.getCoinPair(), pos.getId(), session.getStatus()));
            return;
        }

        if (pos.getCoinPair().equals(session.getCurrentCoinPair())) {
            return; // 이미 이 코인을 감시 중 — 다음 tick에서 SL/TP·매도 재시도됨
        }

        if ("SCANNING".equals(session.getScanState()) && session.getCurrentCoinPair() == null) {
            balanceUpdater.apply(sessionId, s -> {
                s.setScanState("POSITION_MONITORING");
                s.setCurrentCoinPair(pos.getCoinPair());
                s.setCurrentPositionId(pos.getId());
            });
            refreshWsSubscription();
            log.warn("[Dynamic] 매도 롤백 포지션 재결속: {} → POSITION_MONITORING 복귀 (posId={}, sessionId={})",
                    pos.getCoinPair(), pos.getId(), sessionId);
        } else {
            // 세션이 이미 다른 코인을 매수한 경우 — 단일 포지션 상태 머신으로는 자동 복구 불가
            log.error("[Dynamic] 매도 롤백 포지션 재결속 불가 — 세션이 다른 코인 감시 중 (posId={}, coin={}, sessionId={}, current={})",
                    pos.getId(), pos.getCoinPair(), sessionId, session.getCurrentCoinPair());
            telegramService.sendCustomNotification(String.format(
                    "🚨 [동적#%d] 매도 실패 포지션 방치: %s (posId=%d) — 세션은 이미 %s 감시 중이라 자동 재결속 불가. 수동 청산 필요",
                    sessionId, pos.getCoinPair(), pos.getId(), session.getCurrentCoinPair()));
        }
    }

    /**
     * 매도 체결 확정 — 실제 체결가 기반 손익/수수료 계산 + 동적 세션 KRW 복원.
     * 멱등성 보장: 이미 CLOSED인 포지션은 중복 처리하지 않음.
     */
    /**
     * 매도 정산 멱등 키. <b>롤백 후 재시도해도 같은 값이어야</b> 중복 지급을 막을 수 있다.
     *
     * <p>거래소 주문 ID 를 쓴다. 페이퍼는 {@code executePaperSell} 이
     * {@code "PAPER-DYNAMIC-SELL-{positionId}"} 로 포지션에서 결정해 붙이므로 재시도에도 동일하다.
     * 실거래는 거래소가 준 ID 라 부분체결 건마다 달라 각각 한 번씩 정산된다.</p>
     *
     * <p>{@code order.id} 로 폴백하는 경우는 실거래 경로뿐이다 — 거기서는 주문이
     * {@code OrderExecutionEngine} 의 별도 트랜잭션에서 이미 커밋된 뒤라 ID 가 안정적이다.
     * (페이퍼 경로에서 폴백하면 주문 행이 롤백에 휩쓸려 사라진 뒤 재시도마다 새 시퀀스 값이
     * 나오므로 멱등성이 깨진다. 그래서 페이퍼는 항상 exchangeOrderId 를 채운다.)</p>
     */
    /** 로그용 레짐 판정 — 실패해도 매매를 막지 않는다. */
    private String detectRegimeQuietly(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return null;
        try {
            MarketRegime r = new MarketRegimeDetector().detectRaw(candles);
            return r != null ? r.name() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String settlementRef(PositionEntity pos, OrderEntity filledOrder) {
        String exchangeRef = filledOrder.getExchangeOrderId();
        if (exchangeRef != null && !exchangeRef.isBlank()) return exchangeRef;
        return "ORD-" + filledOrder.getId() + "-POS-" + pos.getId();
    }

    private void finalizeDynamicSell(PositionEntity pos, OrderEntity filledOrder) {
        if ("CLOSED".equals(pos.getStatus())) {
            log.debug("[Dynamic] finalizeDynamicSell 스킵: 이미 CLOSED (posId={})", pos.getId());
            return;
        }
        // 체결가 미확정 시 보류 — 다음 reconcile(5초)에서 재시도 (가짜 본전 방지)
        BigDecimal fillPrice = filledOrder.getPrice();
        if (fillPrice == null) {
            log.warn("[Dynamic] 매도 체결가 미확정 — finalize 보류, 다음 reconcile 재시도 (posId={}, orderId={})",
                    pos.getId(), filledOrder.getId());
            return;
        }
        BigDecimal soldQty = filledOrder.getFilledQuantity() != null
                ? filledOrder.getFilledQuantity() : pos.getSize();

        BigDecimal proceeds = soldQty.multiply(fillPrice);
        BigDecimal fee = proceeds.multiply(FEE_RATE);
        BigDecimal netProceeds = proceeds.subtract(fee);
        BigDecimal realizedPnl = netProceeds.subtract(soldQty.multiply(pos.getAvgPrice()));

        // 부분 체결 후 취소(D-3) — 판 수량이 전체보다 적으면 잔여분은 여전히 보유 중이므로
        // 전체 CLOSED 대신 잔여 수량만 남기고 OPEN 유지한다 (LiveTradingService.finalizeSellPosition 동일 원칙).
        boolean isPartial = soldQty.compareTo(pos.getSize()) < 0;
        if (isPartial) {
            pos.setSize(pos.getSize().subtract(soldQty));
            pos.setRealizedPnl(pos.getRealizedPnl().add(realizedPnl));
            pos.setPositionFee(pos.getPositionFee().add(fee));
            pos.setStatus("OPEN");
            pos.setClosingAt(null);
        } else {
            pos.setRealizedPnl(realizedPnl);
            pos.setPositionFee(fee);
            pos.setUnrealizedPnl(BigDecimal.ZERO);
            pos.setStatus("CLOSED");
            pos.setClosedAt(Instant.now());
        }
        positionRepository.save(pos);

        if (pos.getSessionId() != null) {
            Long sessionId = pos.getSessionId();
            // 같은 코인만이 아니라 세션 전체 노출을 확인 — 이전 매도 정산 지연 중 세션이 다른
            // 코인을 이미 매수했을 수 있고, 그때 totalAssetKrw=availableKrw로 덮으면 그 코인의
            // 평가액이 총자산에서 통째로 사라진다.
            final Long finalizedPosId = pos.getId();
            boolean hasRemainingExposure = isPartial || positionRepository
                    .findBySessionKindAndSessionId(pos.getSessionKind(), sessionId).stream()
                    .anyMatch(p -> !p.getId().equals(finalizedPosId)
                            && ("OPEN".equals(p.getStatus()) || "CLOSING".equals(p.getStatus())));

            // ⚠️ 2026-08-19 P0: 이 잔고 반영은 REQUIRES_NEW 라 **바깥 트랜잭션이 롤백돼도
            //    살아남는다**. 반면 위의 포지션 CLOSED 저장은 바깥 트랜잭션에 있어 롤백과 함께
            //    사라진다. 그 비대칭 때문에 "대금은 남고 포지션은 OPEN 으로 복귀" 하는 상태가
            //    만들어지고, 다음 시도가 대금을 또 지급한다 (세션 49: 21회, 10,000 → 174,752).
            //    → 정산 표식을 대금과 **같은 트랜잭션**에 써서 포지션당 한 번만 반영한다.
            DynamicSellSettlementEntity settlement = DynamicSellSettlementEntity.builder()
                    .orderRef(settlementRef(pos, filledOrder))
                    .positionId(pos.getId())
                    .sessionId(sessionId)
                    .sessionKind(pos.getSessionKind())
                    .soldQty(soldQty)
                    .netProceeds(netProceeds)
                    .realizedPnl(realizedPnl)
                    .build();

            boolean credited = balanceUpdater.applySettlementOnce(settlement, s -> {
                BigDecimal newAvailableKrw = s.getAvailableKrw().add(netProceeds);
                s.setAvailableKrw(newAvailableKrw);
                if (!hasRemainingExposure) {
                    s.setTotalAssetKrw(newAvailableKrw);
                } else {
                    s.setTotalAssetKrw(s.getTotalAssetKrw().subtract(fee));
                }
            });
            if (!credited) {
                // 대금은 이전 시도에서 이미 반영됐다. 포지션 CLOSED 확정은 그 시도에서 롤백됐을
                // 수 있으므로 위의 저장은 그대로 진행한다 — 돈은 한 번, 청산은 성공할 때까지.
                log.warn("[Dynamic] 매도대금 중복 지급 차단 (sessionId={}, posId={}) — "
                                + "이전 시도의 잔고 반영이 이미 커밋돼 있다. 포지션 청산만 확정한다.",
                        sessionId, pos.getId());
            }
            if (isPartial) {
                // executeSell()이 매도 제출과 동시에 세션을 이미 SCANNING으로 돌려놓았으므로,
                // 팔리지 않은 잔여분을 계속 감시하도록 재결속한다 (전체 롤백과 동일 원칙).
                reattachRolledBackPosition(pos);
            }
            log.info("[Dynamic] 매도 체결 확정 (sessionId={}, posId={}, partial={}): {} {}개 @ {} 손익={} 수수료={}",
                    sessionId, pos.getId(), isPartial, pos.getCoinPair(), soldQty, fillPrice, realizedPnl, fee);
            if (credited) {
                telegramService.bufferTradeEvent(
                        "동적#" + sessionId, pos.getCoinPair(), "SELL",
                        fillPrice, soldQty, fee, realizedPnl, "동적 세션 매도");
            }
        }
    }

    // ── 스케줄: 고아 매수 포지션 정리 (30초 주기) ───────────────────

    /**
     * OPEN + size=0 포지션 중 BUY 주문이 FAILED/CANCELLED로 확정된 경우를 정리한다.
     *
     * <p>이 처리가 없으면 거래소 오류 등으로 BUY가 실패했을 때 포지션이 size=0 OPEN 상태로
     * 영구 고착되고, {@link #executeSell}의 size&le;0 가드 때문에 세션이 POSITION_MONITORING에
     * 멈춰 다시는 스캔/매수하지 않는다.</p>
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void reconcileDynamicOrphanBuyPositions() {
        List<PositionEntity> orphanPositions = positionRepository
                .findBySessionKindAndStatus(SESSION_KIND, "OPEN")
                .stream()
                .filter(pos -> pos.getSize() != null && pos.getSize().compareTo(BigDecimal.ZERO) <= 0)
                .toList();
        if (orphanPositions.isEmpty()) return;

        for (PositionEntity pos : orphanPositions) {
            List<OrderEntity> buyOrders = orderRepository
                    .findByPositionIdOrderByCreatedAtDesc(pos.getId())
                    .stream()
                    .filter(o -> "BUY".equalsIgnoreCase(o.getSide()))
                    .toList();

            boolean hasCancelledBuy = buyOrders.stream()
                    .anyMatch(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()));
            boolean hasActiveBuy = buyOrders.stream()
                    .anyMatch(o -> ACTIVE_ORDER_STATES.contains(o.getState()));

            if (hasCancelledBuy && !hasActiveBuy) {
                // 원자적 CLOSE — 동시 실행 시 이중 KRW 복원 방지
                int closed = positionRepository.closeIfOpen(pos.getId(), Instant.now());
                if (closed == 0) {
                    log.debug("[Dynamic] 고아 포지션 이미 정리됨, KRW 복원 스킵 (posId={})", pos.getId());
                    continue;
                }

                if (pos.getSessionId() != null) {
                    Long sessionId = pos.getSessionId();
                    BigDecimal toRestore = buyOrders.stream()
                            .filter(o -> "CANCELLED".equals(o.getState()) || "FAILED".equals(o.getState()))
                            .findFirst()
                            .map(OrderEntity::getQuantity)
                            .orElse(pos.getInvestedKrw());
                    if (toRestore != null) {
                        final BigDecimal restoreAmount = toRestore;
                        balanceUpdater.apply(sessionId, s -> s.setAvailableKrw(s.getAvailableKrw().add(restoreAmount)));
                        log.info("[Dynamic] 고아 포지션 정리: KRW 복원 (posId={}, sessionId={}, 복원금액={})",
                                pos.getId(), sessionId, restoreAmount);
                    }
                    // 세션이 POSITION_MONITORING에 고착되지 않도록 SCANNING 복귀
                    transitionToScanning(sessionId);
                }
                log.warn("[Dynamic] 고아 포지션 정리 완료 (posId={}, coinPair={})", pos.getId(), pos.getCoinPair());

            } else if (!hasActiveBuy && buyOrders.isEmpty() && pos.getSessionId() != null) {
                // 예외 경로: 주문 엔티티가 아예 없는 경우 (async 스레드 DB 오류 등)
                boolean isOldEnough = pos.getOpenedAt() != null
                        && Duration.between(pos.getOpenedAt(), Instant.now()).toMinutes() >= 5;
                if (isOldEnough) {
                    int closed = positionRepository.closeIfOpen(pos.getId(), Instant.now());
                    if (closed == 0) continue;

                    Long sessionId = pos.getSessionId();
                    if (pos.getInvestedKrw() != null) {
                        final BigDecimal investedKrw = pos.getInvestedKrw();
                        balanceUpdater.apply(sessionId, s -> s.setAvailableKrw(s.getAvailableKrw().add(investedKrw)));
                        log.warn("[Dynamic] 고아 포지션 정리(주문 없음): KRW 복원 (posId={}, sessionId={}, 복원금액={})",
                                pos.getId(), sessionId, investedKrw);
                    } else {
                        log.error("[Dynamic] 고아 포지션 정리 실패: investedKrw 없음 — 수동 확인 필요 (posId={})", pos.getId());
                    }
                    transitionToScanning(sessionId);
                }
            }
        }
    }

    /**
     * <b>세션 기준</b> 잔고 정합성 안전망 — 포지션이 없는데 KRW가 묶여 있는 세션을 복원한다.
     *
     * <p><b>왜 별도로 필요한가 (2026-08-03 P0)</b>: {@link #reconcileDynamicOrphanBuyPositions}는
     * {@code position} 행을 기준으로 순회하므로, <b>포지션 자체가 롤백돼 사라진</b> 누수를
     * 구조적으로 발견할 수 없다. 실제로 세션 39·40·44가 3일간 이 사각지대에 방치돼
     * 각 8,000원(합 24,000원)이 묶인 채 영구 매수 불능 상태였다.</p>
     *
     * <p><b>불변식</b>: 보유 포지션이 없는 동적 세션은 {@code available_krw == total_asset_krw}.
     * (매도 완료 시 {@code finalizeDynamicSell}이 둘을 같이 맞춘다) 이 등식이 깨졌는데
     * 열린 포지션도 활성 주문도 없다면 그 차액은 <b>어디에도 대응물이 없는 묶인 돈</b>이다.</p>
     *
     * <p><b>오탐 방지</b>: 매수 경로는 KRW 차감(REQUIRES_NEW, 선커밋) → 부모 커밋 순서라,
     * 그 사이 짧은 구간에는 "차감됐지만 포지션이 아직 안 보이는" 정상 상태가 존재한다.
     * {@code updated_at}이 {@link #BALANCE_RECONCILE_GRACE_MIN}분 이상 지난 세션만 손대
     * 이 구간을 건드리지 않는다(차감이 {@code updated_at}을 갱신하므로 유효한 가드).</p>
     *
     * <p>복원 방향은 <b>항상 available를 total에 맞추는 쪽</b>(증액)뿐이다. 반대 방향(감액)은
     * 실제 코인 보유를 DB가 놓친 경우일 수 있어 자동 처리하지 않고 경고만 남긴다.</p>
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void reconcileDynamicSessionBalance() {
        for (DynamicSessionEntity session : dynamicSessionRepo.findByStatus("RUNNING")) {
            // ⚠️ PAPER는 이 안전망의 대상이 아니다 — 이 메서드는 세션을 먼저 순회한 뒤 포지션/주문을
            // SESSION_KIND(REAL)로만 조회하므로, 필터링 없이 두면 PAPER 세션은 "자기 포지션이
            // 하나도 안 보이는" 것으로 오판되어(실제로는 DYN_PAPER로 있을 뿐인데) 정상적인
            // 보유 중 잔고 차이가 고아 잔고로 오인되어 강제로 되돌아갈 뻔했다(2026-08-07 발견).
            // PAPER는 REQUIRES_NEW·비동기 갭이 없어 이런 사후 안전망 자체가 필요하지 않다.
            if (session.isPaper()) continue;
            Long sid = session.getId();
            BigDecimal available = session.getAvailableKrw();
            BigDecimal total = session.getTotalAssetKrw();
            if (available == null || total == null) continue;

            int cmp = available.compareTo(total);
            if (cmp == 0) continue;

            if (session.getUpdatedAt() == null
                    || Duration.between(session.getUpdatedAt(), Instant.now()).toMinutes()
                       < BALANCE_RECONCILE_GRACE_MIN) {
                continue;   // 매수 진행 중일 수 있는 구간 — 건드리지 않는다
            }

            boolean hasOpenPosition = !positionRepository
                    .findBySessionKindAndSessionId(SESSION_KIND, sid).stream()
                    .allMatch(p -> "CLOSED".equals(p.getStatus()));
            if (hasOpenPosition) continue;   // 정상 보유 중 — 차이는 미실현손익

            boolean hasActiveOrder = orderRepository
                    .findBySessionKindAndSessionIdOrderByCreatedAtDesc(
                            SESSION_KIND, sid, org.springframework.data.domain.PageRequest.of(0, 20))
                    .stream()
                    .anyMatch(o -> ACTIVE_ORDER_STATES.contains(o.getState()));
            if (hasActiveOrder) continue;    // 체결 대기 중 — 아직 결론 낼 수 없다

            if (cmp > 0) {
                // available > total: 코인을 들고 있는데 DB가 놓쳤을 수 있어 자동 조정하지 않는다
                log.warn("[Dynamic] 잔고 정합성 이상(available>total) — 수동 확인 필요 "
                                + "(id={}, available={}, total={})", sid, available, total);
                continue;
            }

            final BigDecimal restoreAmount = total.subtract(available);
            balanceUpdater.apply(sid, s -> {
                // 재확인 — 스케줄러 중복 실행/동시 매수와의 race에서 이중 복원 방지
                if (s.getAvailableKrw().compareTo(s.getTotalAssetKrw()) >= 0) return;
                if (s.getCurrentPositionId() != null) return;
                s.setAvailableKrw(s.getTotalAssetKrw());
                s.setScanState("SCANNING");
                s.setCurrentCoinPair(null);
            });
            log.error("[Dynamic] 🔴 고아 잔고 복원: 포지션·활성주문 없이 KRW가 묶여 있었음 "
                            + "(id={}, {}원 복원 → available={}). 매수 tx 롤백 의심 — 서버 로그 확인 필요.",
                    sid, restoreAmount, total);
        }
    }

    // ── 실시간(WS) 손절/익절 ────────────────────────────────────────

    /**
     * 현재 RUNNING 중인 동적 세션들의 보유 코인(POSITION_MONITORING 상태) 목록을 다시 계산해
     * {@link WsSubscriptionManager}에 반영한다. LIVE 세션과 구독을 공유하므로 직접
     * {@code UpbitWebSocketClient}를 호출하지 않는다.
     *
     * <p>매수/매도/세션 시작·정지 시점에 호출해 지연 없이 구독을 갱신한다 — 60초 폴링(tick)을
     * 기다리면 그사이 실시간 손절/익절 보호가 비는 구간이 생긴다.</p>
     */
    private void refreshWsSubscription() {
        // PAPER 세션은 WS 실시간 감시 대상이 아니다 — 60초 폴링(processMonitoringTick)만으로
        // SL/TP를 감시한다. 실거래에 없는 새 구독 경로를 열지 않기 위한 의도적 제외.
        List<String> coins = dynamicSessionRepo.findByStatus("RUNNING").stream()
                .filter(s -> !s.isPaper())
                .filter(s -> "POSITION_MONITORING".equals(s.getScanState()))
                .map(DynamicSessionEntity::getCurrentCoinPair)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        wsSubscriptionManager.updateSource(SESSION_KIND, coins);
    }

    /**
     * WebSocket 실시간 시세 이벤트 핸들러 — 동적 세션 보유 포지션의 손절/익절을 폴링(60초)보다
     * 즉시 반응하도록 처리한다. 라이브 매매의 급등락 SL/TP 트레일링(ratchet)은 이번 범위에
     * 포함하지 않고, 기본 SL/TP 트리거만 실시간화한다.
     */
    @EventListener
    @Async("marketDataExecutor")
    @Transactional
    public void onRealtimePriceEvent(RealtimePriceEvent event) {
        try {
            doOnRealtimePriceEvent(event);
        } catch (Exception e) {
            log.error("[Dynamic] 실시간 시세 이벤트 처리 오류 — coinCode={}, price={}",
                    event.getCoinCode(), event.getPrice(), e);
        }
    }

    private void doOnRealtimePriceEvent(RealtimePriceEvent event) {
        String coinCode = event.getCoinCode();
        BigDecimal price = event.getPrice();
        long now = System.currentTimeMillis();

        Long lastMs = rtCheckLastMs.get(coinCode);
        if (lastMs != null && now - lastMs < RT_CHECK_INTERVAL_MS) return;
        rtCheckLastMs.put(coinCode, now);

        List<DynamicSessionEntity> sessions = dynamicSessionRepo.findByStatus("RUNNING");
        for (DynamicSessionEntity session : sessions) {
            if (session.isPaper()) continue; // PAPER는 WS 실시간 감시 대상이 아니다(60초 폴링만)
            if (!coinCode.equals(session.getCurrentCoinPair())) continue;

            Optional<PositionEntity> openPos = positionRepository
                    .findBySessionKindAndSessionIdAndCoinPairAndStatus(SESSION_KIND, session.getId(), coinCode, "OPEN");
            if (openPos.isEmpty()) continue;

            PositionEntity pos = openPos.get();
            if (pos.getAvgPrice() == null || pos.getAvgPrice().compareTo(BigDecimal.ZERO) <= 0) continue;

            recordSlCheck(session.getId());

            BigDecimal pnlPct = price.subtract(pos.getAvgPrice())
                    .divide(pos.getAvgPrice(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // 익절
            if (pos.getTakeProfitPrice() != null && price.compareTo(pos.getTakeProfitPrice()) >= 0) {
                log.info("[Dynamic] 실시간 익절(WS): {} @ {} pnl={}% (id={})",
                        coinCode, price, pnlPct, session.getId());
                executeSell(session, pos, price,
                        "실시간 익절(WS) — 현재가 " + price + " ≥ " + pos.getTakeProfitPrice(),
                        ExitReason.TAKE_PROFIT);
                continue;
            }

            // 손절
            BigDecimal slNeg = session.getStopLossPct().negate();
            boolean slTriggered = pos.getStopLossPrice() != null
                    ? price.compareTo(pos.getStopLossPrice()) <= 0
                    : pnlPct.compareTo(slNeg) <= 0;
            if (slTriggered) {
                log.warn("[Dynamic] 실시간 손절(WS): {} @ {} pnl={}% (id={})",
                        coinCode, price, pnlPct, session.getId());
                telegramService.notifyStopLoss(coinCode, pnlPct.doubleValue(), session.getId());
                executeSell(session, pos, price, "실시간 손절(WS) — pnl " + pnlPct + "%", ExitReason.STOP_LOSS);
            }
        }
    }

    /** §9 — SL 점검 시각 기록 ({@link #doOnRealtimePriceEvent} 내부에서만 호출). */
    private void recordSlCheck(Long sessionId) {
        lastSlCheckAt.put(sessionId, Instant.now());
    }

    /**
     * §9 — SL 미점검 세션 감시 (2026-08-06 신규, LIVE {@code warnStaleSlCheck}와 동일 패턴).
     *
     * <p>DYNAMIC은 이제껏 이런 워치독 자체가 없었다. WS 실시간 SL/TP 판정({@link #doOnRealtimePriceEvent})이
     * 조용히 멈춰도 아무도 알아채지 못한 채 60초 폴링만 남는 상태가 될 수 있었다 — 2026-08-03
     * ELSA가 SL을 2.1%p 지나쳐서야 체결된 사고가 이 사각지대와 무관하지 않다. 보유 중(POSITION_MONITORING)
     * 세션만 대상이며, 미점검 발견 시 그 코인 하나만 REST로 즉시 강제 갱신을 시도한다.</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void warnStaleSlCheck() {
        List<DynamicSessionEntity> sessions = dynamicSessionRepo.findByStatus("RUNNING").stream()
                .filter(s -> "POSITION_MONITORING".equals(s.getScanState()) && s.getCurrentCoinPair() != null)
                .toList();
        if (sessions.isEmpty()) return;

        Instant threshold = Instant.now().minus(SL_STALE_WARN_MINUTES, ChronoUnit.MINUTES);
        for (DynamicSessionEntity s : sessions) {
            String coin = s.getCurrentCoinPair();
            Instant last = lastSlCheckAt.get(s.getId());
            if (last == null || last.isBefore(threshold)) {
                log.warn("[Dynamic][§9] SL 미점검 경고: sessionId={} coin={} 마지막체크={} ({}분 초과)",
                        s.getId(), coin, last != null ? last : "기록없음", SL_STALE_WARN_MINUTES);

                boolean recovered = forceRefreshPrice(coin);
                telegramService.sendCustomNotification(String.format(
                        "⚠️ [동적#%d] SL 미점검 %d분 초과: %s. %s",
                        s.getId(), SL_STALE_WARN_MINUTES, coin,
                        recovered
                                ? "REST로 해당 코인 시세를 즉시 강제 갱신해 SL 감시를 재개했습니다."
                                : "자동 복구도 실패 — WS/거래소 상태를 직접 확인하세요."));
            }
        }
    }

    /**
     * 특정 코인 하나만 REST로 즉시 시세를 가져와 {@link RealtimePriceEvent}를 발행한다.
     * {@link #warnStaleSlCheck}가 개별 세션의 SL 미점검을 발견했을 때 즉시 복구를 시도하는 용도 —
     * LIVE {@link LiveTradingService#forceRefreshPrice}와 동일 패턴.
     */
    boolean forceRefreshPrice(String coinPair) {
        if (upbitRestClient == null) return false;
        try {
            List<Map<String, Object>> tickers = upbitRestClient.getTicker(coinPair);
            boolean published = false;
            for (Map<String, Object> ticker : tickers) {
                String market = (String) ticker.get("market");
                Object tradePriceObj = ticker.get("trade_price");
                if (market == null || tradePriceObj == null) continue;
                BigDecimal tradePrice = new BigDecimal(tradePriceObj.toString());
                eventPublisher.publishEvent(new RealtimePriceEvent(market, tradePrice));
                published = true;
            }
            return published;
        } catch (Exception e) {
            log.error("[Dynamic][§9] SL 미점검 세션 강제 복구 실패 (coin={}): {}", coinPair, e.getMessage());
            return false;
        }
    }

    private void clearSessionState(Long sessionId) {
        strategyInstances.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + ":"));
        lastEvaluatedCandle.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + ":"));
    }

    private DynamicSessionEntity getOrThrow(Long sessionId) {
        return dynamicSessionRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("동적 세션 없음: id=" + sessionId));
    }

    /**
     * 세션의 실제 session_kind — REAL이면 {@code "DYNAMIC"}, PAPER면 {@code "DYN_PAPER"}.
     * position/order 저장·조회는 전부 이 값을 써야 REAL/PAPER 데이터가 섞이지 않는다.
     */
    // 테스트에서 직접 검증하기 위해 package-private (resolveStopLossPct와 동일 방침)
    static String sessionKind(DynamicSessionEntity session) {
        return session.isPaper() ? SESSION_KIND_PAPER : SESSION_KIND;
    }

    private static BigDecimal normalizeRatio(BigDecimal raw, BigDecimal defaultVal) {
        if (raw == null) return defaultVal;
        if (raw.compareTo(BigDecimal.ONE) > 0) {
            raw = raw.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
        return raw.max(new BigDecimal("0.01")).min(BigDecimal.ONE);
    }

    /** 워치리스트 기본값 조회용 — risk_config 가 없으면 빈 엔티티(전부 null)를 돌려준다. */
    private com.cryptoautotrader.api.entity.RiskConfigEntity scanDefaults() {
        try {
            return riskManagementService.getRiskConfig();
        } catch (Exception e) {
            log.debug("[Dynamic] risk_config 조회 실패, 코드 기본값 사용: {}", e.getMessage());
            return new com.cryptoautotrader.api.entity.RiskConfigEntity();
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

}
