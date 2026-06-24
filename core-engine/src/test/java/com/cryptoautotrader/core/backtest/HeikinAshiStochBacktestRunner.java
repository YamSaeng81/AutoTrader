package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEIKIN_ASHI_STOCH 전략 수동 백테스트 러너.
 * 실데이터(Upbit KRW-BTC H1)를 CSV에서 읽어 백테스트를 실행하고 결과를 출력한다.
 *
 * <p>실행: {@code -Dha.backtest.csv=d:/tmp/ha_backtest_btc_h1.csv} 시스템 프로퍼티로 활성화.
 * CSV 형식(헤더 없음): {@code utc,open,high,low,close,volume} (시간 내림차순/오름차순 무관 — 내부에서 정렬).
 */
class HeikinAshiStochBacktestRunner {

    private final BacktestEngine engine = new BacktestEngine();
    private String coinPair = "KRW-BTC";

    @Test
    @EnabledIfSystemProperty(named = "ha.backtest.csv", matches = ".+")
    void runHeikinAshiStochBacktest() throws Exception {
        String csvPath = System.getProperty("ha.backtest.csv");
        String lower = csvPath.toLowerCase();
        this.coinPair = lower.contains("eth") ? "KRW-ETH"
                : lower.contains("sol") ? "KRW-SOL"
                : lower.contains("xrp") ? "KRW-XRP"
                : "KRW-BTC";
        List<Candle> candles = loadCsv(csvPath);
        System.out.println("\n==================== HEIKIN_ASHI_STOCH 백테스트 ====================");
        System.out.printf("데이터: %s (%s) | 캔들 %d개 | %s ~ %s%n",
                csvPath, coinPair, candles.size(),
                candles.get(0).getTime(), candles.get(candles.size() - 1).getTime());

        // 보완안 1·4·8 A/B 비교 — 토글 파라미터로 동일 데이터에서 직접 대조.
        // 베이스라인(원작 엄격 룰): 꼬리 0% + 몸통증가 필수 + 거래량 필터 없음.
        runOne("① baseline(원작 엄격: 꼬리0/몸통필수/거래량X)", candles, Map.ofEntries(
                Map.entry("maxWickRatio", 0.0),
                Map.entry("requireBodyGrowth", true),
                Map.entry("volumeFilterRatio", 0.0)));
        // 1+4+8 완화(신규 기본값): 꼬리 25% + 몸통증가 가산점 + 거래량 0.8배 필터.
        runOne("② 완화 1+4+8(꼬리0.25/몸통가산/거래량0.8)", candles, Map.ofEntries(
                Map.entry("maxWickRatio", 0.25),
                Map.entry("requireBodyGrowth", false),
                Map.entry("volumeFilterRatio", 0.8),
                Map.entry("volumeAvgPeriod", 20)));
        // 완화 요소 기여도 분리 — 1만 / 1+4만 / 1+4+8 누적.
        runOne("③ 1만(꼬리0.25, 몸통필수, 거래량X)", candles, Map.ofEntries(
                Map.entry("maxWickRatio", 0.25),
                Map.entry("requireBodyGrowth", true),
                Map.entry("volumeFilterRatio", 0.0)));
        runOne("④ 1+4(꼬리0.25/몸통가산, 거래량X)", candles, Map.ofEntries(
                Map.entry("maxWickRatio", 0.25),
                Map.entry("requireBodyGrowth", false),
                Map.entry("volumeFilterRatio", 0.0)));
        runOne("⑤ 1+8(꼬리0.25/몸통필수/거래량0.8)", candles, Map.ofEntries(
                Map.entry("maxWickRatio", 0.25),
                Map.entry("requireBodyGrowth", true),
                Map.entry("volumeFilterRatio", 0.8),
                Map.entry("volumeAvgPeriod", 20)));

        // 베이스라인 비교용 (EMA_CROSS 기본)
        runRaw("[참고] EMA_CROSS 베이스라인", candles, "EMA_CROSS",
                Map.of("fastPeriod", 9, "slowPeriod", 21));
    }

    private void runOne(String label, List<Candle> candles, Map<String, Object> params) {
        runRaw(label, candles, "HEIKIN_ASHI_STOCH", params);
    }

    private void runRaw(String label, List<Candle> candles, String strategy, Map<String, Object> params) {
        BacktestConfig config = BacktestConfig.builder()
                .strategyName(strategy)
                .coinPair(coinPair)
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.05"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(params)
                .build();

        BacktestResult result = engine.run(config, candles);
        PerformanceReport m = result.getMetrics();
        assertThat(result).isNotNull();

        System.out.printf("%n── %s ──%n", label);
        System.out.printf("  총수익률 %s%% | 거래 %d회 (승 %d / 패 %d) | 승률 %s%%%n",
                m.getTotalReturnPct(), m.getTotalTrades(), m.getWinningTrades(), m.getLosingTrades(), m.getWinRatePct());
        System.out.printf("  MDD %s%% | Sharpe %s | Sortino %s | 손익비 %s%n",
                m.getMddPct(), m.getSharpeRatio(), m.getSortinoRatio(), m.getWinLossRatio());
        System.out.printf("  평균이익 %s%% | 평균손실 %s%% | 최대연속손실 %d%n",
                m.getAvgProfitPct(), m.getAvgLossPct(), m.getMaxConsecutiveLoss());
    }

    private static List<Candle> loadCsv(String path) throws Exception {
        List<Candle> candles = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",");
                if (p.length < 6) continue;
                LocalDateTime ldt = LocalDateTime.parse(p[0].replace(" ", "T"));
                Instant time = ldt.toInstant(ZoneOffset.UTC);
                candles.add(Candle.builder()
                        .time(time)
                        .open(new BigDecimal(p[1]))
                        .high(new BigDecimal(p[2]))
                        .low(new BigDecimal(p[3]))
                        .close(new BigDecimal(p[4]))
                        .volume(new BigDecimal(p[5]))
                        .build());
            }
        }
        candles.sort(Comparator.comparing(Candle::getTime));
        return candles;
    }
}
