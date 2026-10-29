package com.cryptoautotrader.strategy.dailysma;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 자료 시험 — 규칙(docs/LIMITED_LIVE_TEST_PLAN.md §2-2)을 하나씩 잠근다.
 * 실제 개발 자료와의 날짜별 대조는 {@link DailySmaParityRunTest}.
 */
class DailySmaTrendStrategyTest {

    private static final Instant DAY0 = Instant.parse("2026-01-01T00:00:00Z");
    private final DailySmaTrendStrategy strategy = new DailySmaTrendStrategy();

    /** {@code days} 일치 H1 봉. d일의 모든 봉 종가 = closeOfDay(d). 마지막 봉은 마지막 날 23:00 UTC. */
    private static List<Candle> h1(int days, IntFunction<BigDecimal> closeOfDay) {
        List<Candle> out = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                BigDecimal p = closeOfDay.apply(d);
                out.add(Candle.builder().time(DAY0.plus(d, ChronoUnit.DAYS).plus(h, ChronoUnit.HOURS))
                        .open(p).high(p).low(p).close(p).volume(BigDecimal.ONE).build());
            }
        }
        return out;
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    @DisplayName("등록 이름과 최소 봉 수 — 19×24+1 = 457, 운영 조회 한도 500 안")
    void registration() {
        assertThat(StrategyRegistry.get("DAILY_SMA20")).isInstanceOf(DailySmaTrendStrategy.class);
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(457).isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("마감 종가 > 20일 평균 → BUY, 같거나 낮으면 SELL (엄격 부등호)")
    void aboveBuysAtOrBelowSells() {
        // 19일 100, 마지막 날 121 → 평균 101.05 < 121 → BUY
        assertThat(strategy.evaluate(h1(20, d -> d == 19 ? bd(121) : bd(100)), Map.of()).getAction())
                .isEqualTo(StrategySignal.Action.BUY);
        // 모두 100 → 종가 = 평균 → 보유 아님
        assertThat(strategy.evaluate(h1(20, d -> bd(100)), Map.of()).getAction())
                .isEqualTo(StrategySignal.Action.SELL);
        // 마지막 날 하락
        assertThat(strategy.evaluate(h1(20, d -> d == 19 ? bd(90) : bd(100)), Map.of()).getAction())
                .isEqualTo(StrategySignal.Action.SELL);
    }

    @Test
    @DisplayName("평균은 오늘 포함 최근 20일만 — 21일 전 값은 영향 없음")
    void windowIsTwentyDaysIncludingToday() {
        // 0일에 큰 값(1,000,000)을 두고 21일치를 주면 0일은 창 밖. 1~20일 = 100, 20일(오늘) = 101 → BUY
        List<Candle> cs = h1(21, d -> d == 0 ? bd(1_000_000) : d == 20 ? bd(101) : bd(100));
        assertThat(strategy.evaluate(cs, Map.of()).getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    @DisplayName("일봉 종가는 23:00 UTC 봉의 종가 — 다른 시각 봉 값은 쓰지 않는다")
    void dailyCloseIs2300Bar() {
        List<Candle> cs = new ArrayList<>();
        for (int d = 0; d < 20; d++) {
            for (int h = 0; h < 24; h++) {
                // 23시 봉만 100, 마지막 날 23시 봉만 120. 나머지 시각은 1 (쓰이면 평균이 크게 내려가 결과가 같아도 이유가 달라진다)
                long v = h == 23 ? (d == 19 ? 120 : 100) : 1;
                cs.add(Candle.builder().time(DAY0.plus(d, ChronoUnit.DAYS).plus(h, ChronoUnit.HOURS))
                        .open(bd(v)).high(bd(v)).low(bd(v)).close(bd(v)).volume(BigDecimal.ONE).build());
            }
        }
        StrategySignal s = strategy.evaluate(cs, Map.of());
        assertThat(s.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(s.getReason()).contains("SMA20 101"); // (19×100 + 120) / 20 = 101
    }

    @Test
    @DisplayName("마지막 닫힌 봉이 23:00 UTC 가 아니면 HOLD — 하루 한 번만 신호")
    void onlyAtDailyClose() {
        List<Candle> cs = h1(21, d -> d == 19 ? bd(121) : bd(100));
        for (int drop = 1; drop < 24; drop++) {
            List<Candle> partial = cs.subList(0, cs.size() - drop); // 마지막 날 (23 − drop)시 봉으로 끝난다
            assertThat(strategy.evaluate(partial, Map.of()).getAction())
                    .as("마지막 봉 %s", partial.get(partial.size() - 1).getTime())
                    .isEqualTo(StrategySignal.Action.HOLD);
        }
    }

    @Test
    @DisplayName("20일 중 하루라도 마감 봉이 없으면 HOLD — 메우지 않는다")
    void missingCloseHolds() {
        List<Candle> cs = new ArrayList<>(h1(20, d -> d == 19 ? bd(121) : bd(100)));
        Instant missing = DAY0.plus(5, ChronoUnit.DAYS).plus(23, ChronoUnit.HOURS);
        cs.removeIf(c -> c.getTime().equals(missing));
        StrategySignal s = strategy.evaluate(cs, Map.of());
        assertThat(s.getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(s.getReason()).contains("일봉 종가 누락").contains("2026-01-06");
    }

    @Test
    @DisplayName("일봉 19개뿐이면 HOLD")
    void tooFewDays() {
        assertThat(strategy.evaluate(h1(19, d -> bd(100 + d)), Map.of()).getAction())
                .isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    @DisplayName("H1 이 아닌 타임프레임이면 HOLD")
    void h1Only() {
        List<Candle> cs = h1(20, d -> d == 19 ? bd(121) : bd(100));
        assertThat(strategy.evaluate(cs, Map.of("timeframe", "M15")).getAction()).isEqualTo(StrategySignal.Action.HOLD);
        assertThat(strategy.evaluate(cs, Map.of("timeframe", "H1")).getAction()).isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    @DisplayName("손절·익절 제안값을 내지 않는다 — 엔진 기본 ATR 규칙을 쓴다")
    void noSuggestedLevels() {
        StrategySignal s = strategy.evaluate(h1(20, d -> d == 19 ? bd(121) : bd(100)), Map.of());
        assertThat(s.getSuggestedStopLoss()).isNull();
        assertThat(s.getSuggestedTakeProfit()).isNull();
    }

    @Test
    @DisplayName("시험용 마감 시각 이동은 경계만 옮긴다 — 같은 자료를 옮기면 같은 신호")
    void shiftedCloseHourIsSameRule() {
        List<Candle> base = h1(21, d -> d == 19 ? bd(121) : bd(100)).subList(0, 20 * 24);
        StrategySignal expected = strategy.evaluate(base, Map.of());
        // 모든 봉을 −9시간 옮기면 마감 봉은 14:00 UTC
        List<Candle> moved = base.stream().map(c -> Candle.builder().time(c.getTime().minus(9, ChronoUnit.HOURS))
                .open(c.getOpen()).high(c.getHigh()).low(c.getLow()).close(c.getClose()).volume(c.getVolume()).build()).toList();
        StrategySignal actual = new DailySmaTrendStrategy(14).evaluate(moved, Map.of());
        assertThat(actual.getAction()).isEqualTo(expected.getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(new DailySmaTrendStrategy().evaluate(moved, Map.of()).getAction()).isEqualTo(StrategySignal.Action.HOLD);
    }
}
