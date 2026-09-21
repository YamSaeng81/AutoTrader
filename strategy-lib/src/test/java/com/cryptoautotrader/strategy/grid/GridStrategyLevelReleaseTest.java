package com.cryptoautotrader.strategy.grid;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GridStrategy} 레벨 해제 — Wave 3-I 회귀.
 *
 * <p>매수는 하위 30%(레벨 0~3)에서만 일어나고 해제는 상위 30%(레벨 7~10)에서만 시도된다.
 * <b>두 집합이 구조적으로 겹치지 않아</b> {@code activeLevels.remove(levelIndex)} 가 실제로
 * 지우는 것이 하나도 없다. 한 번 잡은 레벨은 범위 리셋 전까지 영원히 잠긴다.
 */
@DisplayName("GridStrategy — 레벨 해제")
class GridStrategyLevelReleaseTest {

    private static final long H1 = 3600L;
    private static final long BASE = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();

    /**
     * 100봉. 0번이 범위를 고정(high=200 · low=100 → gridSize=10)하고 마지막 봉의 종가만 바꾼다.
     * 매 호출에서 범위가 같으므로 {@code isRangeChanged} 로 인한 우발적 초기화가 없다 —
     * 레벨 해제 경로만 격리해서 잰다.
     */
    private static List<Candle> at(double lastClose) {
        List<Candle> l = new ArrayList<>(100);
        l.add(bar(0, 150, 200, 100, 150));
        for (int i = 1; i < 99; i++) l.add(bar(i, 150, 151, 149, 150));
        l.add(bar(99, lastClose, lastClose, lastClose, lastClose));
        return l;
    }

    private static Candle bar(int i, double o, double h, double lo, double c) {
        return Candle.builder()
                .time(Instant.ofEpochSecond(BASE + i * H1))
                .open(BigDecimal.valueOf(o)).high(BigDecimal.valueOf(h))
                .low(BigDecimal.valueOf(lo)).close(BigDecimal.valueOf(c))
                .volume(BigDecimal.TEN)
                .build();
    }

    /** 110 → 그리드 위치 1.0 (하위 10%, 레벨 1) · 180 → 8.0 (상위 80%, 레벨 8) */
    private static final Map<String, Object> P = Map.of();

    @Test
    @DisplayName("상단에서 매도하면 하단에서 잡아둔 레벨이 풀린다")
    void sellInUpperZone_releasesLowerLevels() {
        GridStrategy g = new GridStrategy();

        assertThat(g.evaluate(at(110), P).getAction())
                .as("레벨 1 첫 진입")
                .isEqualTo(StrategySignal.Action.BUY);

        assertThat(g.evaluate(at(110), P).getAction())
                .as("같은 레벨 재진입은 막혀야 한다 — 중복 방지는 의도된 동작이다")
                .isEqualTo(StrategySignal.Action.HOLD);

        assertThat(g.evaluate(at(180), P).getAction())
                .as("상단 도달 → 매도")
                .isEqualTo(StrategySignal.Action.SELL);

        assertThat(g.evaluate(at(110), P).getAction())
                .as("매도로 청산했으니 레벨 1 은 다시 살 수 있어야 한다. "
                  + "풀리지 않으면 그리드가 한 바퀴만 돌고 영구히 침묵한다")
                .isEqualTo(StrategySignal.Action.BUY);
    }

    @Test
    @DisplayName("여러 레벨을 잡아뒀어도 상단 매도 한 번에 모두 풀린다")
    void sell_releasesEveryHeldLevel() {
        GridStrategy g = new GridStrategy();
        // gridCount=20 → 매수 상한 레벨 6, 매도 하한 레벨 14.
        Map<String, Object> p = Map.of("gridCount", 20);

        // 105 → 위치 1.0, 115 → 3.0 (둘 다 하위 30%)
        assertThat(g.evaluate(at(105), p).getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(g.evaluate(at(115), p).getAction()).isEqualTo(StrategySignal.Action.BUY);

        // 175 → 위치 15.0 (상위 30%) → 매도
        assertThat(g.evaluate(at(175), p).getAction()).isEqualTo(StrategySignal.Action.SELL);

        assertThat(g.evaluate(at(105), p).getAction())
                .as("레벨 1 해제 확인").isEqualTo(StrategySignal.Action.BUY);
        assertThat(g.evaluate(at(115), p).getAction())
                .as("레벨 3 도 함께 풀려야 한다 — 가격이 그 위로 올라갔다")
                .isEqualTo(StrategySignal.Action.BUY);
    }

    // ⚠️ "매도 레벨보다 **위에** 잡아둔 레벨은 남긴다" 는 성질은 여기서 잴 수 없다.
    //    매수는 언제나 하위 30%, 매도 인덱스는 언제나 상위 30% 라 그런 상태를 공개 API 로
    //    만들 수 없기 때문이다. 즉 지금 구현에서 removeIf(l <= levelIndex) 와 clear() 는
    //    관측상 동일하다. removeIf 를 쓰는 이유는 의도를 코드에 남기고 구역 임계값이
    //    바뀌어도 맞게 동작하게 하려는 것이지, 오늘 그 차이를 잴 수 있어서가 아니다.

    @Test
    @DisplayName("levelDedupEnabled=false 면 상태 추적 없이 순수 위치 신호만 낸다")
    void dedupDisabled_alwaysSignals() {
        GridStrategy g = new GridStrategy();
        Map<String, Object> p = Map.of("levelDedupEnabled", false);

        for (int i = 0; i < 3; i++) {
            assertThat(g.evaluate(at(110), p).getAction())
                    .as("%d 번째 호출", i + 1)
                    .isEqualTo(StrategySignal.Action.BUY);
        }
    }

    @Test
    @DisplayName("resetState 는 잡아둔 레벨을 전부 비운다")
    void resetState_clearsLevels() {
        GridStrategy g = new GridStrategy();

        assertThat(g.evaluate(at(110), P).getAction()).isEqualTo(StrategySignal.Action.BUY);
        assertThat(g.evaluate(at(110), P).getAction()).isEqualTo(StrategySignal.Action.HOLD);

        g.resetState();

        assertThat(g.evaluate(at(110), P).getAction())
                .as("상태를 비웠으면 처음처럼 진입할 수 있어야 한다")
                .isEqualTo(StrategySignal.Action.BUY);
    }
}
