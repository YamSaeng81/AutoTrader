package com.cryptoautotrader.api.service;

import com.cryptoautotrader.core.backtest.BacktestConfig;
import com.cryptoautotrader.core.backtest.WalkForwardTestRunner;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF 결과의 저장 JSON 에 점수 계산 근거가 남는지 (2026-10-28 리뷰 12차).
 *
 * <p>{@code BacktestService.runWalkForward} 는 응답 맵을 그대로 {@code wfResultJson} 으로 저장한다.
 * 윈도우별 IS·OOS 평가 봉 수·예열 봉 수·봉당 로그 성장률이 빠지면 저장된 결과에서
 * {@code PER_BAR_LOG_V1} 점수를 다시 계산할 수 없다. 이 시험은 실제 WF 결과를 맵으로 바꾸고 JSON 으로
 * 직렬화·역직렬화한 뒤, <b>저장된 값만으로</b> 점수를 다시 계산해 원래 점수와 비교한다.
 */
class BacktestWalkForwardWindowMapTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PROBE = "WF_WINDOW_MAP_PROBE";

    /** 20시간마다 매수, 12시간 뒤 매도 — 가격을 보지 않는다. */
    private static class Probe implements Strategy {
        @Override public String getName() { return PROBE; }
        @Override public int getMinimumCandleCount() { return 20; }
        @Override public StrategySignal evaluate(List<Candle> c, Map<String, Object> p) {
            long h = c.get(c.size() - 1).getTime().getEpochSecond() / 3600;
            if (h % 20 == 0) return StrategySignal.buy(new BigDecimal("80"), "탐침 매수");
            if (h % 20 == 12) return StrategySignal.sell(new BigDecimal("80"), "탐침 매도");
            return StrategySignal.hold("탐침 대기");
        }
    }

    @Test
    @DisplayName("저장 JSON 의 윈도우별 IS·OOS 근거 값만으로 overfittingScore 를 다시 계산할 수 있다")
    void 저장_JSON에_점수_근거가_남는다() throws Exception {
        StrategyRegistry.register(new Probe());
        List<Candle> candles = new ArrayList<>();
        double price = 1000.0;
        for (int i = 0; i < 5000; i++) {
            // OOS 쪽(각 1000봉 윈도우의 뒤 300봉) 추세를 낮춰 하락률이 0 이 아닌 윈도우가 생기게 한다.
            // 0.0003 은 이 탐침의 체결 간격에서 OOS 봉당 성장률이 IS 보다 높게 나와 점수가 0 이었다(리뷰 12차 2차 실행).
            double drift = (i % 1000) < 700 ? 0.0005 : 0.0001;
            double open = price;
            double close = open * Math.exp(drift);
            candles.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(bd(open)).close(bd(close))
                    .high(bd(close * 1.0003)).low(bd(open * 0.9997))
                    .volume(new BigDecimal("1000"))
                    .build());
            price = close;
        }
        BacktestConfig config = BacktestConfig.builder()
                .strategyName(PROBE).coinPair("KRW-TEST").timeframe("H1")
                .startDate(candles.get(0).getTime()).endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000")).strategyParams(Map.of())
                .build();
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner().run(config, candles, 0.7, 5);
        assertThat(r.getOverfittingScore()).isNotNull();
        assertThat(r.getOverfittingScore().signum()).as("0 이 아닌 점수로 재계산을 확인한다").isPositive();

        // 서비스가 쓰는 변환 → JSON 저장·복원 (jsonb 컬럼과 같은 Jackson 경로)
        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(r.getWindows().stream().map(BacktestService::windowToMap).toList());
        // 윈도우 맵에는 숫자(windowIndex)·문자열 값도 있으므로 값은 Object 로 읽고 inSample·outSample 만 맵으로 다룬다
        List<Map<String, Object>> stored = om.readValue(json, new TypeReference<>() { });

        assertThat(stored).hasSize(r.getWindows().size());
        double total = 0;
        int samples = 0;
        for (int k = 0; k < stored.size(); k++) {
            assertThat(((Number) stored.get(k).get("windowIndex")).intValue()).isEqualTo(r.getWindows().get(k).getWindowIndex());
            Map<String, Object> in = asMap(stored.get(k).get("inSample"));
            Map<String, Object> out = asMap(stored.get(k).get("outSample"));
            WalkForwardTestRunner.WindowResult w = r.getWindows().get(k);
            for (String key : List.of("evaluatedBars", "warmupBars", "perBarLogGrowth")) {
                assertThat(in).as("IS 윈도우 %d", k).containsKey(key);
                assertThat(out).as("OOS 윈도우 %d", k).containsKey(key);
            }
            assertThat(((Number) in.get("evaluatedBars")).intValue()).isEqualTo(w.getInSampleEvaluatedBars());
            assertThat(((Number) out.get("evaluatedBars")).intValue()).isEqualTo(w.getOutSampleEvaluatedBars());
            assertThat(((Number) in.get("warmupBars")).intValue()).isEqualTo(w.getInSampleWarmupBars());
            assertThat(((Number) out.get("warmupBars")).intValue()).isEqualTo(w.getOutSampleWarmupBars());

            // 저장된 값만으로 재계산: IS 수익률 > 0 윈도우에서 max(0, (gIS − gOOS) / gIS) 평균
            double inReturn = ((Number) in.get("totalReturn")).doubleValue();
            if (inReturn <= 0) continue;
            double gIn = ((Number) in.get("perBarLogGrowth")).doubleValue();
            double gOut = ((Number) out.get("perBarLogGrowth")).doubleValue();
            // 성장률 자체도 저장된 수익률·평가 봉 수로 다시 나온다
            assertThat(gIn).isCloseTo(Math.log1p(inReturn / 100.0)
                    / ((Number) in.get("evaluatedBars")).intValue(), org.assertj.core.data.Offset.offset(1e-9));
            total += Math.max(0, (gIn - gOut) / gIn);
            samples++;
        }
        assertThat(samples).isPositive();
        BigDecimal recomputed = BigDecimal.valueOf(total / samples).setScale(4, RoundingMode.HALF_UP);
        assertThat(recomputed).isEqualByComparingTo(r.getOverfittingScore());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        assertThat(v).isInstanceOf(Map.class);
        return (Map<String, Object>) v;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }
}
