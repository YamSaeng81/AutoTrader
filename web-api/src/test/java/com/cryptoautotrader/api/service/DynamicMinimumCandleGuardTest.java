package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 2026-08-31 — <b>스캔 루프의 최소 캔들 기준은 전략이 선언한 값이어야 한다</b>.
 *
 * <p><b>왜 바꿨나</b>: {@code processScanningTick} 이 {@code candles.size() < 15} 로 하드코딩돼
 * 있었다. 15 는 <b>어떤 전략의 요구량도 아니다</b> — {@code COMPOSITE_PULLBACK_MTF} 는
 * EMA200 산출에 200개가 필요한데 15개만 있어도 평가에 들어갔다. 전략 내부 가드가
 * "데이터 부족" HOLD 를 돌려주므로 잘못된 신호가 나오진 않지만, <b>장기 지표가 조용히
 * 비활성된 채 도는 것을 아무도 모른다</b>. 백테스트({@code BacktestEngine})는 진작부터
 * {@code getMinimumCandleCount()} 를 쓰고 있어서 실거래 경로만 기준이 달랐다.</p>
 *
 * <p><b>운영 근거 (08-31 캔들 수집)</b>: 워치리스트에 이력이 거의 없는 신규 상장 코인이
 * 계속 들어온다 — META2 770 · PROM 276 · LIT 155 · NCT 99개. WF 검증도 불가능하다(2,000행 미만).
 * 그리고 08-30 손익 분석의 손실 상위가 정확히 이 부류였다 — RE −5.85% · ONT −5.74% ·
 * BEAM −5.48%. <b>"지표를 못 채우는 코인"과 "돈을 잃는 코인"이 같은 집합이었다.</b></p>
 *
 * <p>평가된 코인은 {@code strategy_log} 에 행을 남기고(HOLD 포함), 건너뛴 코인은 남기지
 * 않는다 — 그 차이로 스킵 여부를 관측한다.</p>
 */
class DynamicMinimumCandleGuardTest extends IntegrationTestBase {

    private static final String COIN = "KRW-XRP";
    private static final String STRATEGY = "COMPOSITE_PULLBACK_MTF";

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private StrategyLogRepository strategyLogRepository;

    /** 캔들 공급원 — 개수를 테스트에서 직접 통제한다. */
    @MockBean
    private MarketDataSyncService marketDataSyncService;

    @MockBean
    private UpbitRestClient upbitRestClient;

    @MockBean
    private TelegramNotificationService telegramService;

    @BeforeEach
    @AfterEach
    void cleanup() {
        strategyLogRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
    }

    /** 닫힌 H1 캔들 n개 — 마지막 캔들이 확실히 닫혀 있도록 한 시간 전까지만 만든다. */
    private static List<Candle> candles(int n) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        List<Candle> list = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal price = BigDecimal.valueOf(1000 + (i % 7));
            list.add(Candle.builder()
                    .time(end.minus(i, ChronoUnit.HOURS))
                    .open(price).high(price.add(BigDecimal.TEN))
                    .low(price.subtract(BigDecimal.TEN)).close(price)
                    .volume(BigDecimal.valueOf(1000))
                    .build());
        }
        return list;
    }

    private DynamicSessionEntity scanningSession() {
        return dynamicSessionRepository.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType(STRATEGY).timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING")
                .tradingMode("PAPER")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                // 워치리스트를 직접 주입 — 갱신 시각이 최근이면 필터를 타지 않고 이 값을 쓴다.
                .watchlistJson("[\"" + COIN + "\"]")
                .watchlistRefreshedAt(Instant.now())
                .build());
    }

    private long evaluatedRows() {
        return strategyLogRepository.findAll().stream()
                .filter(l -> COIN.equals(l.getCoinPair()))
                .count();
    }

    // ── 배선 검증: 스캔 루프가 전략의 요구량을 실제로 쓰는가 ──────────────────

    @Test
    @DisplayName("🔴 전략 요구량 미만이면 평가조차 하지 않는다 — 100개 < PULLBACK_MTF 200개")
    void belowStrategyMinimum_isSkippedEntirely() {
        DynamicSessionEntity s = scanningSession();
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt()))
                .thenReturn(candles(100));

        dynamicTradingService.processScanningTick(s);

        assertThat(evaluatedRows())
                .as("15로 하드코딩돼 있으면 100개도 평가에 들어가 HOLD 로그가 남는다")
                .isZero();
    }

    @Test
    @DisplayName("전략 요구량을 넘으면 정상 평가된다 — 250개 ≥ 200개")
    void aboveStrategyMinimum_isEvaluated() {
        DynamicSessionEntity s = scanningSession();
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt()))
                .thenReturn(candles(250));

        dynamicTradingService.processScanningTick(s);

        assertThat(evaluatedRows())
                .as("요구량을 채웠으므로 평가되어 strategy_log 에 남아야 한다")
                .isPositive();
    }

    @Test
    @DisplayName("경계 — 요구량 바로 아래(199)는 막히고 딱 맞으면(200) 통과한다")
    void boundaryIsExact() {
        int need = StrategyRegistry.get(STRATEGY).getMinimumCandleCount();

        DynamicSessionEntity below = scanningSession();
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt()))
                .thenReturn(candles(need - 1));
        dynamicTradingService.processScanningTick(below);
        assertThat(evaluatedRows()).as("요구량 −1 은 막힌다").isZero();

        cleanup();
        DynamicSessionEntity exact = scanningSession();
        when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt()))
                .thenReturn(candles(need));
        dynamicTradingService.processScanningTick(exact);
        assertThat(evaluatedRows()).as("요구량과 같으면 통과한다").isPositive();
    }

    @Test
    @DisplayName("운영 실측 재현 — KRW-NCT(99) · KRW-LIT(155) 수준이면 평가 대상이 아니다")
    void realNewlyListedCoinCounts_areSkipped() {
        for (int rows : new int[]{99, 155}) {
            cleanup();
            DynamicSessionEntity s = scanningSession();
            when(marketDataSyncService.fetchWithCache(anyString(), anyString(), anyInt()))
                    .thenReturn(candles(rows));
            dynamicTradingService.processScanningTick(s);
            assertThat(evaluatedRows())
                    .as("%d개짜리 신규 상장 코인 — EMA200 을 못 채운다", rows)
                    .isZero();
        }
    }

    // ── 문서화: 15는 근거 없는 값이었다 ──────────────────────────────────────

    @Test
    @DisplayName("모든 운영 전략이 옛 하드코딩 값(15)보다 많은 캔들을 요구한다")
    void everyActiveStrategyNeedsMoreThanOldHardcodedFloor() {
        for (String name : new String[]{
                "COMPOSITE_PULLBACK_MTF", "COMPOSITE_MEANREV_BB", "COMPOSITE_MTF_BTC",
                "COMPOSITE_MTF_CONFIRMED", "COMPOSITE_MOMENTUM_ICHIMOKU",
                "COMPOSITE_MOMENTUM_ICHIMOKU_V2"}) {
            Strategy s = StrategyRegistry.get(name);
            assertThat(s.getMinimumCandleCount())
                    .as("%s 의 최소 캔들 요구량 — 15로 평가하면 지표가 조용히 비활성된다", name)
                    .isGreaterThan(15);
        }
    }
}
