package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.PaperTradingStartRequest;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.paper.PaperOrderRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.api.util.TradingConstants;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모의투자 세션별 슬리피지 (V95, B_VALIDATION_PREREG.md §7 ⓐ·ⓑ).
 *
 * <p>🔴 ⓑ 불변 시험이 핵심이다 — 세션 슬리피지가 없거나(V95 이전 세션 전부, 전향 검증 522~587 포함) 0.1% 이면
 * 체결가·잔액·실현 손익·수수료·규칙 지문이 도입 전과 <b>소수점까지 같아야</b> 한다. 도입 전 공식은 이 시험에
 * 직접 적어 둔다(상수 × 같은 반올림) — 서비스 코드에서 가져오면 함께 바뀌어 비교가 무의미해진다.</p>
 */
@DisplayName("모의투자 세션별 슬리피지 — 기존 세션 불변 + STRESS 팔 반영 (V95)")
class PaperSessionSlippageTest extends IntegrationTestBase {

    private static final BigDecimal LEGACY = new BigDecimal("0.001");   // 도입 전 상수 값 — 여기 고정

    @Autowired
    private PaperTradingService paperTradingService;

    @Autowired
    private RulesetRegistry rulesetRegistry;

    @Autowired
    private VirtualBalanceRepository balanceRepo;

    @Autowired
    private PaperPositionRepository positionRepo;

    @Autowired
    private PaperOrderRepository orderRepo;

    @SpyBean
    private MarketDataCacheRepository marketDataCacheRepo;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> {
            orderRepo.findBySessionIdOrderByCreatedAtDesc(id, Pageable.unpaged()).forEach(orderRepo::delete);
            positionRepo.findBySessionId(id).forEach(positionRepo::delete);
            balanceRepo.deleteById(id);
        });
        created.clear();
    }

    @Test
    @DisplayName("도입 전 상수가 0.001 그대로다 — 이 값이 바뀌면 아래 비교의 기준이 흔들린다")
    void legacyConstantUnchanged() {
        assertThat(TradingConstants.PAPER_SLIPPAGE_PCT).isEqualByComparingTo(LEGACY);
    }

    @Test
    @DisplayName("체결가: 세션값 없음·0.001·0.001000 은 도입 전 공식과 소수 8자리까지 같다")
    void fillPriceMatchesLegacyFormula() {
        for (String p : List.of("310", "0.00123456", "123456789.12345678", "1.99999999", "56918000")) {
            BigDecimal signal = new BigDecimal(p);
            BigDecimal legacyBuy = signal.multiply(BigDecimal.ONE.add(LEGACY)).setScale(8, RoundingMode.HALF_UP);
            BigDecimal legacySell = signal.multiply(BigDecimal.ONE.subtract(LEGACY)).setScale(8, RoundingMode.HALF_DOWN);
            for (BigDecimal rate : List.of(new VirtualBalanceEntity().slippageRateOrDefault(),
                    new BigDecimal("0.001"), new BigDecimal("0.001000"))) {
                assertThat(PaperTradingService.buyFillPrice(signal, rate)).isEqualTo(legacyBuy);   // scale 까지 같음
                assertThat(PaperTradingService.sellFillPrice(signal, rate)).isEqualTo(legacySell);
            }
            assertThat(PaperTradingService.buyFillPrice(signal, new BigDecimal("0.002")))
                    .isEqualTo(signal.multiply(new BigDecimal("1.002")).setScale(8, RoundingMode.HALF_UP));
            assertThat(PaperTradingService.sellFillPrice(signal, new BigDecimal("0.002")))
                    .isEqualTo(signal.multiply(new BigDecimal("0.998")).setScale(8, RoundingMode.HALF_DOWN));
        }
    }

    @Test
    @DisplayName("규칙 지문: 세션값 없음 = 0.001 = 0.001000, 0.002 는 다른 지문")
    void fingerprintUnchangedForLegacySessions() {
        String none = rulesetRegistry.hashFor(session(null));
        assertThat(rulesetRegistry.hashFor(session(new BigDecimal("0.001")))).isEqualTo(none);
        assertThat(rulesetRegistry.hashFor(session(new BigDecimal("0.001000")))).isEqualTo(none);
        assertThat(rulesetRegistry.hashFor(session(new BigDecimal("0.002"))))
                .as("STRESS 팔은 다른 체결 가정이므로 MAIN 과 한 표본에 섞이면 안 된다").isNotEqualTo(none);
    }

    @Test
    @DisplayName("정지 청산: 세션값 없음과 0.001 은 잔액·실현 손익·수수료가 같고, 0.002 는 매도가가 0.2% 낮다")
    void stopSettlementInvariantAndStressApplied() {
        Mockito.doReturn(cannedCandle()).when(marketDataCacheRepo)
                .findCandles(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any());

        VirtualBalanceEntity none = stopAndReload(createWithOpenPosition(null));
        VirtualBalanceEntity main = stopAndReload(createWithOpenPosition(new BigDecimal("0.001")));
        VirtualBalanceEntity stress = stopAndReload(createWithOpenPosition(new BigDecimal("0.002")));

        // 도입 전 공식으로 직접 계산한 기대값 — 수량 1000 × 매도가 310 × (1 − 0.001), 수수료 0.05%
        BigDecimal legacyProceeds = new BigDecimal("1000").multiply(
                new BigDecimal("310").multiply(BigDecimal.ONE.subtract(LEGACY)).setScale(8, RoundingMode.HALF_DOWN));
        BigDecimal legacyFee = legacyProceeds.multiply(TradingConstants.FEE_RATE);

        for (VirtualBalanceEntity s : List.of(none, main)) {
            assertThat(s.getStatus()).isEqualTo("STOPPED");
            assertThat(s.getAvailableKrw()).isEqualByComparingTo(none.getAvailableKrw());
            assertThat(s.getTotalKrw()).isEqualByComparingTo(none.getTotalKrw());
            assertThat(s.getRealizedPnl()).isEqualByComparingTo(none.getRealizedPnl());
            assertThat(s.getTotalFee()).isEqualByComparingTo(none.getTotalFee());
        }
        BigDecimal legacyNet = legacyProceeds.subtract(legacyFee);
        assertThat(none.getAvailableKrw().subtract(new BigDecimal("200000.00")))
                .as("세션값 없음: 도입 전 공식(310 × 0.999, 수수료 0.05%%)으로 청산 — 잔액 저장 자릿수(2) 안에서")
                .isBetween(legacyNet.subtract(new BigDecimal("0.01")), legacyNet.add(new BigDecimal("0.01")));
        BigDecimal stressProceeds = new BigDecimal("1000").multiply(new BigDecimal("309.38000000"));
        BigDecimal stressNet = stressProceeds.subtract(stressProceeds.multiply(TradingConstants.FEE_RATE));
        assertThat(stress.getAvailableKrw().subtract(new BigDecimal("200000.00")))
                .as("STRESS: 매도가 310 × 0.998 = 309.38 로 청산")
                .isBetween(stressNet.subtract(new BigDecimal("0.01")), stressNet.add(new BigDecimal("0.01")));
        assertThat(none.getAvailableKrw().subtract(stress.getAvailableKrw()))
                .as("0.1%p 차이 ≈ 310,000 × 0.001 × (1 − 0.0005)")
                .isBetween(new BigDecimal("309.80"), new BigDecimal("309.90"));
    }

    @Test
    @DisplayName("실제 매수 경로: 0.002 세션은 체결가 ×1.002 로 포지션·주문·잔액이 기록되고, 세션값 없음은 도입 전 공식 그대로")
    void buyPathAppliesSessionRate() {
        // 매수 호출부가 세션값 대신 상수를 쓰면 STRESS 쪽 기대값(310.62)이 깨진다 — 가격 함수 직접 호출로는 못 잡는 경우
        Object target = AopTestUtils.getUltimateTargetObject(paperTradingService);
        for (BigDecimal rate : java.util.Arrays.asList(null, new BigDecimal("0.002"))) {
            VirtualBalanceEntity s = balanceRepo.save(VirtualBalanceEntity.builder()
                    .totalKrw(new BigDecimal("1000000.00")).availableKrw(new BigDecimal("1000000.00"))
                    .initialCapital(new BigDecimal("1000000.00"))
                    .strategyName("COMPOSITE_MTF_MOMENTUM").coinPair("KRW-DOGE").timeframe("H1")
                    .status("RUNNING").startedAt(Instant.now())
                    .investRatio(new BigDecimal("0.5")).stopLossPct(new BigDecimal("5.0"))
                    .slippageRate(rate).build());
            created.add(s.getId());

            Boolean filled = ReflectionTestUtils.invokeMethod(target, "executeBuy", s.getId(), "KRW-DOGE",
                    new BigDecimal("310"), s, StrategySignal.buy(new BigDecimal("100"), "슬리피지 매수 시험"),
                    null, null);
            assertThat(filled).isTrue();

            // 기대값은 여기서 직접 계산 — 투자금 1,000,000 × 0.5, 수수료 0.05%, 수량은 소수 8자리 내림
            BigDecimal expectedPrice = rate == null
                    ? new BigDecimal("310").multiply(BigDecimal.ONE.add(LEGACY)).setScale(8, RoundingMode.HALF_UP)
                    : new BigDecimal("310.62000000");
            BigDecimal invest = new BigDecimal("500000.00");
            BigDecimal fee = invest.multiply(TradingConstants.FEE_RATE);
            BigDecimal expectedQty = invest.subtract(fee).divide(expectedPrice, 8, RoundingMode.DOWN);

            PaperPositionEntity pos = positionRepo.findBySessionId(s.getId()).get(0);
            assertThat(pos.getEntryPrice()).isEqualTo(expectedPrice);
            assertThat(pos.getSize()).isEqualTo(expectedQty);
            assertThat(pos.getInvestedKrw()).isEqualByComparingTo(invest);

            List<PaperOrderEntity> orders = orderRepo.findBySessionIdOrderByCreatedAtDesc(
                    s.getId(), Pageable.unpaged()).getContent();
            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getSide()).isEqualTo("BUY");
            assertThat(orders.get(0).getPrice()).isEqualByComparingTo(expectedPrice);
            assertThat(orders.get(0).getQuantity()).isEqualByComparingTo(expectedQty);
            assertThat(orders.get(0).getFilledQuantity()).isEqualByComparingTo(expectedQty);

            VirtualBalanceEntity after = balanceRepo.findById(s.getId()).orElseThrow();
            assertThat(after.getAvailableKrw()).isEqualByComparingTo("500000.00");
            assertThat(after.getTotalFee()).isEqualByComparingTo(fee);
        }
    }

    @Test
    @DisplayName("생성: slippagePct 0.2 → 저장 0.002, 미지정 → NULL(종전 상수)")
    void startStoresRateOrNull() {
        VirtualBalanceEntity stress = paperTradingService.start(request(new BigDecimal("0.2")));
        VirtualBalanceEntity legacy = paperTradingService.start(request(null));
        created.add(stress.getId());
        created.add(legacy.getId());
        assertThat(balanceRepo.findById(stress.getId()).orElseThrow().getSlippageRate()).isEqualByComparingTo("0.002");
        assertThat(balanceRepo.findById(legacy.getId()).orElseThrow().getSlippageRate()).isNull();
        assertThat(balanceRepo.findById(legacy.getId()).orElseThrow().slippageRateOrDefault()).isEqualByComparingTo(LEGACY);
    }

    // ── 보조 ─────────────────────────────────────────────

    private static VirtualBalanceEntity session(BigDecimal rate) {
        return VirtualBalanceEntity.builder()
                .totalKrw(new BigDecimal("1000000.00")).availableKrw(new BigDecimal("1000000.00"))
                .initialCapital(new BigDecimal("1000000.00"))
                .strategyName("COMPOSITE_MTF_MOMENTUM").coinPair("KRW-NEAR").timeframe("H1")
                .status("RUNNING").maxHoldHours(24).slippageRate(rate).build();
    }

    private static PaperTradingStartRequest request(BigDecimal slippagePct) {
        PaperTradingStartRequest r = new PaperTradingStartRequest();
        r.setStrategyType("COMPOSITE_MTF_MOMENTUM");
        r.setCoinPair("KRW-NEAR");
        r.setTimeframe("H1");
        r.setInitialCapital(new BigDecimal("1000000"));
        r.setSlippagePct(slippagePct);
        return r;
    }

    private Long createWithOpenPosition(BigDecimal rate) {
        VirtualBalanceEntity s = balanceRepo.save(VirtualBalanceEntity.builder()
                .totalKrw(new BigDecimal("1000000.00")).availableKrw(new BigDecimal("200000.00"))
                .initialCapital(new BigDecimal("1000000.00"))
                .strategyName("COMPOSITE_MTF_MOMENTUM").coinPair("KRW-DOGE").timeframe("M15")
                .status("RUNNING").startedAt(Instant.now()).slippageRate(rate).build());
        positionRepo.save(PaperPositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000")).avgPrice(new BigDecimal("300.00000000"))
                .size(new BigDecimal("1000.00000000")).investedKrw(new BigDecimal("300000.00000000"))
                .status("OPEN").sessionId(s.getId()).openedAt(Instant.now()).build());
        created.add(s.getId());
        return s.getId();
    }

    private VirtualBalanceEntity stopAndReload(Long id) {
        paperTradingService.stop(id);
        return balanceRepo.findById(id).orElseThrow();
    }

    /** 청산 기준가 310 인 캔들 하나 — 비면 업비트 폴백으로 외부를 호출한다(PaperStopUnderConcurrentWriteTest 와 같은 방식). */
    private List<MarketDataCacheEntity> cannedCandle() {
        return List.of(MarketDataCacheEntity.builder()
                .time(Instant.now().minusSeconds(60)).coinPair("KRW-DOGE").timeframe("M1")
                .open(new BigDecimal("310.00000000")).high(new BigDecimal("312.00000000"))
                .low(new BigDecimal("308.00000000")).close(new BigDecimal("310.00000000"))
                .volume(new BigDecimal("1000.00000000")).build());
    }
}
