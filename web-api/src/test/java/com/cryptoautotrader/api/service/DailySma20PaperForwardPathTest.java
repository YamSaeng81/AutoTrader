package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.StrategyLogEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.StrategyLogRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DAILY_SMA20 전향 모의 — PAPER 의 1회 매수 상한({@code strategyParams.paperMaxInvestment})이 운영 평가 경로에서 실제로 걸리는지
 * (2026-11-10, 총괄 v1.142 §110 판단 2).
 *
 * <p>세션은 {@link PaperTradingService#start} 로 만들고(운영 생성 경로·전략 활성 관문 포함), 평가는 운영 스케줄러가 부르는
 * {@link PaperTradingService#runStrategy} 를 그대로 부른다. 캔들은 {@code market_data_cache} H1 행이며, 엔진이 벽시계로
 * "닫힌 최신 봉"을 판단하므로 일봉 경계만 옮긴 같은 규칙의 전략 인스턴스를 같은 이름으로 잠시 등록한다
 * ({@code DailySma20LiveOrderPathTest} 와 같은 방식). 끝나면 운영 인스턴스로 되돌린다.</p>
 *
 * <p>세션 조건은 SID 203 과 같은 숫자다 — 초기 20,200 · 투자 비율 0.995 · 1회 상한 20,000 · 손절 하한 5% · 시간 청산 0(끔).</p>
 */
@DisplayName("DAILY_SMA20 전향 모의 — PAPER 1회 매수 상한·최소 원금")
class DailySma20PaperForwardPathTest extends IntegrationTestBase {

    private static final String COIN = "KRW-BTC";

    @Autowired private PaperTradingService paperTradingService;
    @Autowired private VirtualBalanceRepository balanceRepo;
    @Autowired private PaperPositionRepository positionRepo;
    @Autowired private PaperOrderRepository orderRepo;
    @Autowired private StrategyLogRepository strategyLogRepository;
    @Autowired private RulesetRegistry rulesetRegistry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private com.cryptoautotrader.api.repository.MarketDataCacheRepository cacheRepository;

    @MockBean private MarketDataSyncService marketDataSyncService;
    @MockBean private UpbitRestClient upbitRestClient;
    @MockBean private TelegramNotificationService telegramService;

    private final List<Long> sessionIds = new ArrayList<>();
    private Instant lastOpen;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", COIN);
        // 닫힌 최신 봉 = 61분 전에 시작한 봉. 그 봉을 일봉 마감 봉으로 둔다.
        lastOpen = Instant.now().minus(61, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        StrategyRegistry.register(new DailySmaTrendStrategy(lastOpen.atZone(ZoneOffset.UTC).getHour()));
        insertCache(uptrend());
    }

    @AfterEach
    void tearDown() {
        StrategyRegistry.register(new DailySmaTrendStrategy());
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (Long id : sessionIds) {
            tx.executeWithoutResult(st -> orderRepo.deleteBySessionId(id));
            positionRepo.findBySessionId(id).forEach(positionRepo::delete);
            strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", id).forEach(strategyLogRepository::delete);
            balanceRepo.findById(id).ifPresent(balanceRepo::delete);
        }
        sessionIds.clear();
        jdbc.update("DELETE FROM market_data_cache WHERE coin_pair = ? AND timeframe = 'H1'", COIN);
    }

    // ── 자료 ────────────────────────────────────────────────────────

    /** 완만한 상승(시간당 +5,000원) 닫힌 H1 500봉 — 마감 종가 > SMA20, EMA200 위, 급락 없음. */
    private List<Candle> uptrend() {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            BigDecimal c = BigDecimal.valueOf(10_000_000L + 5_000L * i);
            BigDecimal o = BigDecimal.valueOf(10_000_000L + 5_000L * Math.max(0, i - 1));
            out.add(Candle.builder().time(lastOpen.minus(499 - i, ChronoUnit.HOURS))
                    .open(o).high(c.max(o).add(BigDecimal.valueOf(1000))).low(c.min(o).subtract(BigDecimal.valueOf(1000)))
                    .close(c).volume(new BigDecimal("100")).build());
        }
        return out;
    }

    private void insertCache(List<Candle> cs) {
        cacheRepository.saveAll(cs.stream().map(c -> com.cryptoautotrader.api.entity.MarketDataCacheEntity.builder()
                .time(c.getTime()).coinPair(COIN).timeframe("H1")
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume())
                .build()).toList());
    }

    private static PaperTradingStartRequest request(String initialCapital, Object maxInvestment) {
        PaperTradingStartRequest req = new PaperTradingStartRequest();
        req.setStrategyType(DailySmaTrendStrategy.NAME);
        req.setCoinPair(COIN);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal(initialCapital));
        req.setInvestRatio(new BigDecimal("0.995"));
        req.setStopLossPct(new BigDecimal("5.00"));
        req.setMaxHoldHours(0);
        if (maxInvestment != null) {
            Map<String, Object> p = new HashMap<>();
            p.put(PaperMaxInvestment.KEY, maxInvestment);
            req.setStrategyParams(p);
        }
        return req;
    }

    private VirtualBalanceEntity startAndEvaluate(PaperTradingStartRequest req) {
        VirtualBalanceEntity s = paperTradingService.start(req);
        sessionIds.add(s.getId());
        paperTradingService.runStrategy();
        return balanceRepo.findById(s.getId()).orElseThrow();
    }

    private PaperPositionEntity onlyOpenPosition(Long sessionId) {
        List<PaperPositionEntity> open = positionRepo.findBySessionIdAndStatus(sessionId, "OPEN");
        assertThat(open).as("일봉 마감 봉 상승 추세에서 매수 1건이어야 한다").hasSize(1);
        return open.get(0);
    }

    // ── 시험 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SID 203 조건(20,200·0.995·상한 20,000) — 매수금은 20,099 가 아니라 20,000, 남는 현금 200")
    void capLimitsBuyAmount() {
        VirtualBalanceEntity s = startAndEvaluate(request("20200", 20000));

        PaperPositionEntity p = onlyOpenPosition(s.getId());
        assertThat(p.getInvestedKrw()).isEqualByComparingTo("20000.00");
        assertThat(s.getAvailableKrw()).isEqualByComparingTo("200.00");
        // 손절 하한 5% — 진입가 × 0.95 이하(전략 제안이 더 넓으면 그쪽). 상한 키는 청산 해석을 바꾸지 않는다.
        assertThat(p.getStopLossPrice()).isLessThanOrEqualTo(
                p.getEntryPrice().multiply(new BigDecimal("0.95")).setScale(8, java.math.RoundingMode.HALF_DOWN));
        assertThat(ExitRuleOverrides.from(s.getStrategyParams()).isPresent()).isFalse();
        // 신호 기록 — 실행됨
        List<StrategyLogEntity> logs = strategyLogRepository.findAllBySessionTypeAndSessionIdOrderByCreatedAtDesc("PAPER", s.getId());
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getSignal()).isEqualTo("BUY");
            assertThat(l.isWasExecuted()).isTrue();
        });
        // 규칙 지문 — 상한 키가 strategy.params 로 실려 키 없는 세션과 다른 표본이 된다.
        assertThat(p.getRulesetHash()).isNotBlank();
        VirtualBalanceEntity noKey = VirtualBalanceEntity.builder()
                .strategyName(s.getStrategyName()).coinPair(COIN).timeframe("H1")
                .initialCapital(s.getInitialCapital()).investRatio(s.getInvestRatio())
                .stopLossPct(s.getStopLossPct()).maxHoldHours(s.getMaxHoldHours()).build();
        assertThat(rulesetRegistry.hashFor(noKey)).isNotEqualTo(p.getRulesetHash());
    }

    @Test
    @DisplayName("상한이 가용 × 비율보다 크면 상한은 걸리지 않는다 — 20,200 × 0.995 = 20,099")
    void capAboveBaseIsInert() {
        VirtualBalanceEntity s = startAndEvaluate(request("20200", "50000"));
        assertThat(onlyOpenPosition(s.getId()).getInvestedKrw()).isEqualByComparingTo("20099.00");
    }

    @Test
    @DisplayName("키가 없는 세션은 종전과 같다 — 100,000 × 0.995 = 99,500")
    void noKeyUnchanged() {
        VirtualBalanceEntity s = startAndEvaluate(request("100000", null));
        assertThat(onlyOpenPosition(s.getId()).getInvestedKrw()).isEqualByComparingTo("99500.00");
    }

    @Test
    @DisplayName("서비스 생성 경로 — 키 없이 10만원 미만이거나 상한이 잘못되면 세션을 만들지 않는다")
    void serviceRejectsWithoutCreating() {
        long before = balanceRepo.count();
        assertThatThrownBy(() -> paperTradingService.start(request("20200", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("100000");
        assertThatThrownBy(() -> paperTradingService.start(request("20200", "4999")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PaperMaxInvestment.KEY);
        assertThatThrownBy(() -> paperTradingService.start(request("20200", "abc")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(PaperMaxInvestment.KEY);
        assertThat(balanceRepo.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("요청 검증 — 키 없으면 최소 100,000, 키가 있으면 5,000 이상의 숫자 상한과 원금 5,000 이상")
    void requestValidation() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(v.validate(request("100000", null))).isEmpty();
        assertThat(v.validate(request("99999", null))).isNotEmpty();
        assertThat(v.validate(request("20200", null))).isNotEmpty();
        assertThat(v.validate(request("20200", 20000))).isEmpty();
        assertThat(v.validate(request("20200", "4999"))).isNotEmpty();
        assertThat(v.validate(request("20200", "abc"))).isNotEmpty();
        assertThat(v.validate(request("4999", 20000))).isNotEmpty();
    }

    @Test
    @DisplayName("HTTP 생성 경로 — 키 없는 20,200 요청은 검증 오류로 거절되고 세션이 생기지 않는다")
    void httpRejectsSmallCapitalWithoutKey() throws Exception {
        long before = balanceRepo.count();
        String body = objectMapper.writeValueAsString(request("20200", null));
        int status = mockMvc.perform(post("/api/v1/paper-trading/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
        assertThat(balanceRepo.count()).isEqualTo(before);
    }
}
