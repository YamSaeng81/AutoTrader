package com.cryptoautotrader.core;

import com.cryptoautotrader.core.selector.CompositePresets;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategyRegistry;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4-N — <b>{@code getMinimumCandleCount()} 가 선언한 값이면 실제로 평가할 수 있어야 한다.</b>
 *
 * <p><b>왜 필요한가</b><br>
 * 이 값은 세 엔진이 "평가에 들어갈지 말지"를 정하는 유일한 기준이다
 * ({@code BacktestEngine:92}, {@code DynamicTradingService:844}).
 * 그런데 {@link Strategy} 인터페이스에는 <b>javadoc 이 한 줄도 없고</b>,
 * 선언값은 <b>상수</b>인 반면 전략 내부 가드는 <b>params 로 계산</b>한다.
 * 둘이 어긋나면 호출자는 "충분하다"고 판단해 평가에 들여보내는데 전략은
 * "데이터 부족" HOLD 만 돌려준다 — <b>세션이 조용히 아무것도 하지 않는다.</b>
 * 예외도 경고도 없고 {@code log.debug} 한 줄이 전부다.
 *
 * <p>2026-08-31 에 {@code DynamicTradingService} 에서 하드코딩 15 를 걷어낼 때
 * 적은 근거가 정확히 이것이다 — "장기 지표가 조용히 비활성된 채로 도는 것을 아무도 모른다".
 *
 * <p><b>이 테스트가 강제하는 계약</b>: 선언한 최소 캔들 수를 기본 params 와 함께 주면,
 * 전략은 <b>데이터 부족을 이유로 HOLD 하지 않는다.</b> 방향은 묻지 않는다 —
 * BUY/SELL/HOLD 무엇이든 좋고, 다만 "못 세겠다"는 아니어야 한다.
 *
 * <p>⚠️ 기본 params 기준이다. params 로 기간을 늘리면(예: {@code emaPeriod: 300})
 * 실제 요구가 선언값을 넘어서는데, {@code getMinimumCandleCount()} 는 params 를 받지 않아
 * 그 경우를 표현할 수 없다. <b>이것은 인터페이스의 한계이지 테스트의 한계가 아니다</b> —
 * 아래 클래스 주석에 남겨 둔다.
 */
class MinimumCandleContractTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final long H1 = 3600L;

    /** 테스트 전용 더미는 계약 대상이 아니다. */
    private static final List<String> EXCLUDED = List.of("TEST_TIMED");

    static Stream<Strategy> allStrategies() {
        CompositePresets.ensureRegistered();
        return StrategyRegistry.getAll().entrySet().stream()
                .filter(e -> !EXCLUDED.contains(e.getKey()))
                .map(Map.Entry::getValue);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStrategies")
    @DisplayName("선언한 최소 캔들 수로 평가하면 '데이터 부족'이 나오지 않는다")
    void declaredMinimum_isEnoughToEvaluate(Strategy strategy) {
        int declared = strategy.getMinimumCandleCount();
        assertThat(declared)
                .as("%s — 최소 캔들 수는 양수여야 한다", strategy.getName())
                .isPositive();

        StrategySignal signal = strategy.evaluate(series(declared), Map.of());

        assertThat(signal).as("%s — 신호가 null", strategy.getName()).isNotNull();
        assertThat(signal.getReason())
                .as("%s — 선언값 %d개를 줬는데 데이터가 부족하다고 한다. "
                                + "선언값이 실제 요구보다 작다는 뜻이다 (사유: %s)",
                        strategy.getName(), declared, signal.getReason())
                .doesNotContain("데이터 부족")
                .doesNotContain("시계열 부족")
                .doesNotContain("계산 실패");
    }

    /**
     * 완만한 상승 + 진동. 지표가 수렴할 수 있으면서 무변동 경계(Wave 3-H)도 건드리지 않는다.
     *
     * <p>일부러 방향성 없는 밋밋한 데이터를 쓰지 않는다 — 무변동이면 여러 전략이
     * 다른 사유로 HOLD 해서 "데이터 부족"과 구별이 흐려진다.
     */
    private static List<Candle> series(int n) {
        List<Candle> out = new ArrayList<>();
        double prev = 1000.0;
        for (int i = 0; i < n; i++) {
            double next = 1000.0 + 0.7 * i + 12.0 * Math.sin(i * 2 * Math.PI / 30.0);
            double open = prev;
            double close = next;
            out.add(Candle.builder()
                    .time(BASE.plusSeconds((long) i * H1))
                    .open(BigDecimal.valueOf(open))
                    .high(BigDecimal.valueOf(Math.max(open, close) + 1.5))
                    .low(BigDecimal.valueOf(Math.min(open, close) - 1.5))
                    .close(BigDecimal.valueOf(close))
                    .volume(BigDecimal.valueOf(1000 + (i % 7) * 30))
                    .build());
            prev = next;
        }
        return out;
    }
}
