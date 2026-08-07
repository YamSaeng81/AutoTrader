package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-07 신규 — 벤치마크(매수 후 보유) 대비 알파 측정 회귀 테스트.
 *
 * <p><b>배경</b>: 수개월간 "이 시스템이 잘하고 있는가"를 판정하지 못한 근본 원인은 수익률을
 * 절대값으로만 봤기 때문이다. 시장이 −10%일 때의 −3%는 좋은 성적이고 시장이 +10%일 때의
 * −3%는 나쁜 성적인데, 이 비교가 코드에 없어 매번 손으로 SQL을 돌려야 했다.
 *
 * <p>이 테스트가 지키는 것: ① 알파 = 시스템 수익률 − 벤치마크 수익률이 실제로 계산된다
 * ② PAPER 세션은 실자본 성과가 아니므로 집계에서 제외된다 ③ 시세 데이터가 없으면 0%로
 * 조용히 왜곡되는 게 아니라 "판정 불가(null)"로 구분된다.
 */
class BenchmarkAlphaServiceTest extends IntegrationTestBase {

    @Autowired
    private BenchmarkAlphaService benchmarkAlphaService;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

    @Autowired
    private MarketDataCacheRepository marketDataCacheRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        dynamicSessionRepository.deleteAll();
        marketDataCacheRepository.deleteAll();
    }

    /** 시작가 → 종료가로 움직인 코인의 H1 캔들 2개를 심는다. */
    private void seedPrice(String coinPair, Instant start, double from, double to) {
        marketDataCacheRepository.saveAndFlush(candle(coinPair, start, from));
        marketDataCacheRepository.saveAndFlush(candle(coinPair, start.plus(1, ChronoUnit.HOURS), to));
    }

    private MarketDataCacheEntity candle(String coinPair, Instant time, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        return MarketDataCacheEntity.builder()
                .time(time)
                .coinPair(coinPair)
                .timeframe("H1")
                .open(c).high(c).low(c).close(c)
                .volume(BigDecimal.ONE)
                .build();
    }

    private DynamicSessionEntity newSession(String tradingMode, Instant startedAt,
                                            String initialCapital, String totalAsset) {
        DynamicSessionEntity s = DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal(initialCapital))
                .availableKrw(new BigDecimal(totalAsset))
                .totalAssetKrw(new BigDecimal(totalAsset))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("SCANNING")
                .tradingMode(tradingMode)
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build();
        s.setStartedAt(startedAt);
        return dynamicSessionRepository.saveAndFlush(s);
    }

    @Test
    @DisplayName("실자본 세션이 없으면 판정 불가로 응답한다 — 0%로 왜곡하지 않는다")
    void noRealSessions_reportsUnavailable() {
        Map<String, Object> r = benchmarkAlphaService.getAlphaSummary();
        assertThat(r.get("available")).isEqualTo(false);
        assertThat((String) r.get("reason")).contains("세션이 없어");
    }

    @Test
    @DisplayName("알파 = 시스템 수익률 − 알트 평균 보유 수익률로 계산된다")
    @SuppressWarnings("unchecked")
    void computesAlphaAgainstBuyAndHold() {
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS);

        // 시스템: 10,000 → 10,500 = +5.00%
        newSession("REAL", start, "10000.00", "10500.00");

        // 알트 보유: XRP +10%, SOL −20%, ETH 0%, DOGE +18% → 알트 평균 +2.00%
        // (평균을 일부러 0이 아닌 값으로 둔다 — 0이면 뺄셈을 빼먹어도 통과해버린다)
        seedPrice("KRW-XRP", start, 1000, 1100);
        seedPrice("KRW-SOL", start, 1000, 800);
        seedPrice("KRW-ETH", start, 1000, 1000);
        seedPrice("KRW-DOGE", start, 1000, 1180);
        seedPrice("KRW-BTC", start, 1000, 1010);   // BTC +1%

        Map<String, Object> r = benchmarkAlphaService.getAlphaSummary();
        assertThat(r.get("available")).isEqualTo(true);

        Map<String, Object> system = (Map<String, Object>) r.get("system");
        assertThat((BigDecimal) system.get("totalReturnPct")).isEqualByComparingTo("5.00");

        Map<String, Object> alpha = (Map<String, Object>) r.get("alpha");
        assertThat((BigDecimal) alpha.get("altAvgReturnPct")).isEqualByComparingTo("2.00");
        assertThat((BigDecimal) alpha.get("btcReturnPct")).isEqualByComparingTo("1.00");

        // 핵심: 알파가 실제 뺄셈으로 나온다 (5.00 − 2.00 = 3.00, 5.00 − 1.00 = 4.00)
        // 네 값이 모두 달라야 뺄셈 누락·좌우 반전이 전부 잡힌다.
        assertThat((BigDecimal) alpha.get("vsAltAvgPct")).isEqualByComparingTo("3.00");
        assertThat((BigDecimal) alpha.get("vsBtcPct")).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("시장보다 못하면 알파가 음수로 나온다 — 절대수익이 양수여도 마찬가지")
    @SuppressWarnings("unchecked")
    void positiveReturnCanStillBeNegativeAlpha() {
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS);

        // 시스템 +2% — 절대값만 보면 "벌었다"
        newSession("REAL", start, "10000.00", "10200.00");

        // 그런데 그냥 들고만 있었으면 +10%였다
        seedPrice("KRW-XRP", start, 1000, 1100);
        seedPrice("KRW-SOL", start, 1000, 1100);
        seedPrice("KRW-ETH", start, 1000, 1100);
        seedPrice("KRW-DOGE", start, 1000, 1100);
        seedPrice("KRW-BTC", start, 1000, 1100);

        Map<String, Object> alpha =
                (Map<String, Object>) benchmarkAlphaService.getAlphaSummary().get("alpha");

        // 수익이 났는데도 알파는 −8%p — 이 판정이 이 기능의 존재 이유다
        assertThat((BigDecimal) alpha.get("vsAltAvgPct")).isEqualByComparingTo("-8.00");
    }

    @Test
    @DisplayName("PAPER 세션은 실자본 성과가 아니므로 알파 집계에서 제외된다")
    @SuppressWarnings("unchecked")
    void paperSessionsExcluded() {
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS);

        newSession("REAL", start, "10000.00", "10500.00");    // +5%
        newSession("PAPER", start, "10000.00", "20000.00");   // +100% — 섞이면 안 된다

        seedPrice("KRW-XRP", start, 1000, 1000);
        seedPrice("KRW-SOL", start, 1000, 1000);
        seedPrice("KRW-ETH", start, 1000, 1000);
        seedPrice("KRW-DOGE", start, 1000, 1000);
        seedPrice("KRW-BTC", start, 1000, 1000);

        Map<String, Object> r = benchmarkAlphaService.getAlphaSummary();
        Map<String, Object> system = (Map<String, Object>) r.get("system");

        assertThat(system.get("dynamicSessions")).isEqualTo(1);
        // PAPER가 섞였다면 +52.5%가 됐을 것
        assertThat((BigDecimal) system.get("totalReturnPct")).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("시세 데이터가 없는 코인은 0%가 아니라 판정 불가(null)로 구분된다")
    @SuppressWarnings("unchecked")
    void missingPriceDataIsNullNotZero() {
        Instant start = Instant.now().minus(3, ChronoUnit.HOURS);
        newSession("REAL", start, "10000.00", "10500.00");

        // XRP만 시세를 넣고 나머지는 비워둔다
        seedPrice("KRW-XRP", start, 1000, 1200);   // +20%

        Map<String, Object> r = benchmarkAlphaService.getAlphaSummary();

        var benchmark = (java.util.List<Map<String, Object>>) r.get("benchmark");
        Map<String, Object> xrp = benchmark.stream()
                .filter(b -> "KRW-XRP".equals(b.get("coinPair"))).findFirst().orElseThrow();
        Map<String, Object> sol = benchmark.stream()
                .filter(b -> "KRW-SOL".equals(b.get("coinPair"))).findFirst().orElseThrow();

        assertThat(xrp.get("available")).isEqualTo(true);
        assertThat(sol.get("available")).isEqualTo(false);
        assertThat(sol.get("returnPct")).isNull();

        // 데이터 있는 XRP만으로 평균 — 없는 코인을 0%로 세어 알파를 희석하면 안 된다
        Map<String, Object> alpha = (Map<String, Object>) r.get("alpha");
        assertThat((BigDecimal) alpha.get("altAvgReturnPct")).isEqualByComparingTo("20.00");
        assertThat(alpha.get("btcReturnPct")).isNull();
        assertThat(alpha.get("vsBtcPct")).isNull();
    }
}
