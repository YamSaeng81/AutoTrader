package com.cryptoautotrader.strategy.macdstochbb;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUY 쿨다운이 <b>고정 길이 창</b>에서도 경과를 올바르게 세는지 검증한다.
 *
 * <p>이전 구현은 경과 봉 수를 {@code candles.size() - lastBuyCandleCount}로 계산했다.
 * BacktestEngine처럼 창 길이가 최대 500으로 고정된 경로에서는 매 평가마다 size가 같아
 * 경과가 항상 0이 되고, 쿨다운이 영구히 만료되지 않아 최초 BUY 이후 모든 BUY가 차단됐다.
 *
 * <p>아래 두 테스트는 <b>창 길이를 89로 고정</b>한 채 한 봉씩 슬라이딩한다. 구 구현이라면
 * 경과가 0이므로 cooldown=1에서도 차단되어 첫 번째 테스트가 실패한다. 두 번째 테스트는
 * 시각 기준으로 바꾼 뒤에도 쿨다운이 여전히 제 역할을 하는지(과도하게 열리지 않는지) 확인한다.
 */
class MacdStochBbStrategyCooldownTest {

    /** 창 길이 — 이 값으로 고정해 슬라이딩하면 size는 변하지 않는다. */
    private static final int WINDOW = 89;

    /**
     * 상승 추세 80봉 → 눌림 8봉 → 반등 8봉.
     * 이 배열에서 창이 89·90번째 봉에서 끝날 때 BUY 조건(MACD>0, 히스토그램 확대,
     * StochRSI %K<20, %K>%D, 거래량 충족)이 연속으로 성립한다.
     */
    private static List<Candle> pullbackThenRebound() {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        double price = 100;
        int i = 0;

        for (int n = 0; n < 80; n++) { price *= 1.004; candles.add(candle(base, i++, price, 100)); }
        for (int n = 0; n < 8;  n++) { price *= 0.996; candles.add(candle(base, i++, price, 100)); }
        for (int n = 0; n < 8;  n++) { price *= 1.006; candles.add(candle(base, i++, price, 300)); }

        return candles;
    }

    private static Candle candle(Instant base, int index, double close, double volume) {
        BigDecimal p = BigDecimal.valueOf(close);
        return Candle.builder()
                .time(base.plus(index, ChronoUnit.HOURS))
                .open(p)
                .high(p.multiply(BigDecimal.valueOf(1.002)))
                .low(p.multiply(BigDecimal.valueOf(0.998)))
                .close(p)
                .volume(BigDecimal.valueOf(volume))
                .build();
    }

    @Test
    void 고정_길이_창에서_쿨다운이_만료된다() {
        List<Candle> all = pullbackThenRebound();
        MacdStochBbStrategy strategy = new MacdStochBbStrategy();

        // 1봉 쿨다운: 첫 BUY 다음 봉이면 이미 1봉 경과 → 재진입 허용
        Map<String, Object> params = Map.of("cooldownCandles", 1);

        StrategySignal first  = strategy.evaluate(all.subList(0, WINDOW), params);
        StrategySignal second = strategy.evaluate(all.subList(1, WINDOW + 1), params);

        assertThat(first.getAction()).isEqualTo(StrategySignal.Action.BUY);
        // 구 구현: size가 89로 동일 → 경과 0 → 쿨다운 미만료로 HOLD
        assertThat(second.getAction())
                .as("창 길이가 89로 같아도 봉 시각은 1시간 진행했으므로 1봉 쿨다운은 만료되어야 한다")
                .isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    void 쿨다운_미경과시_여전히_차단된다() {
        List<Candle> all = pullbackThenRebound();
        MacdStochBbStrategy strategy = new MacdStochBbStrategy();

        // 2봉 쿨다운: 첫 BUY 다음 봉은 1봉만 경과 → 차단
        Map<String, Object> params = Map.of("cooldownCandles", 2);

        StrategySignal first  = strategy.evaluate(all.subList(0, WINDOW), params);
        StrategySignal second = strategy.evaluate(all.subList(1, WINDOW + 1), params);

        assertThat(first.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(second.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(second.getReason()).contains("쿨다운");
    }

    @Test
    void resetState_후_쿨다운이_해제된다() {
        List<Candle> all = pullbackThenRebound();
        MacdStochBbStrategy strategy = new MacdStochBbStrategy();
        Map<String, Object> params = Map.of("cooldownCandles", 2);

        assertThat(strategy.evaluate(all.subList(0, WINDOW), params).getAction())
                .isEqualTo(StrategySignal.Action.BUY);
        assertThat(strategy.evaluate(all.subList(1, WINDOW + 1), params).getAction())
                .isEqualTo(StrategySignal.Action.HOLD);

        // 세션 재시작 상당 — 쿨다운 상태가 남아 있으면 안 된다
        strategy.resetState();

        assertThat(strategy.evaluate(all.subList(1, WINDOW + 1), params).getAction())
                .isEqualTo(StrategySignal.Action.BUY);
    }
}
