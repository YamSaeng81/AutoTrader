package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.risk.ExitRuleFormula;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백테스트가 <b>실전과 같은 청산 규칙</b>으로 도는지 — 2026-09-08 신설.
 *
 * <h3>무엇이 문제였나</h3>
 * <p>{@code BacktestEngine} 만 다른 SL/TP 공식을 썼다. 원인은 "잊었다" 가 아니라
 * <b>패키지 배치가 공유를 물리적으로 막고 있었다</b>는 것이다 — 공식이 web-api 의
 * {@code ExitRuleCalculator} 에 있었고 백테스트는 core-engine 이라, 모듈 의존 방향상
 * 호출 자체가 불가능했다.</p>
 *
 * <pre>
 *                  BACKTEST(수정 전)        LIVE · DYNAMIC · PAPER
 *   SL 폭          항상 5.0% 고정            clamp(ATR/가격 × 1.5, floor, 8%)
 *   TP 폭          SL × 2 = 항상 10%         min(SL × 2, 8%)
 *   time stop      없음                      maxHoldHours (운영 24h)
 * </pre>
 *
 * <p>판정이 <b>양방향으로</b> 왜곡됐다: 백테스트가 실전보다 훨씬 자주 손절되고(SL 5% 고정),
 * 실전이 결코 설정하지 않는 TP 10% 를 노렸으며, 운영 청산의 47%(83건 중 39건)를 차지하는
 * TIME_STOP 경로가 아예 없어 <b>거래 모집단 자체가 달랐다.</b></p>
 *
 * <p>이 테스트는 공식이 다시 갈라지는 것과, 규칙 버전 표시가 사라지는 것을 막는다.</p>
 */
class BacktestExitRuleParityTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 첫 평가 시점에 한 번만 BUY 를 내고 이후 HOLD — 진입 후 청산 경로만 보기 위한 스텁. */
    private static class BuyOnceStrategy implements Strategy {
        private boolean fired = false;

        @Override public String getName() { return "BUY_ONCE"; }
        @Override public int getMinimumCandleCount() { return 20; }

        @Override
        public StrategySignal evaluate(List<Candle> candles, Map<String, Object> params) {
            if (fired) return StrategySignal.hold("보유");
            fired = true;
            return StrategySignal.buy(new BigDecimal("80"), "진입");
        }
    }

    /**
     * 가격이 거의 움직이지 않는 H1 캔들 — SL 에도 TP 에도 도달하지 않는다.
     * 저변동 종목(스테이블코인 등)이 실제로 이렇게 움직이며, time stop 이 없으면 영원히 물린다.
     */
    private static List<Candle> flatCandles(int count) {
        List<Candle> out = new ArrayList<>();
        BigDecimal base = new BigDecimal("1000");
        for (int i = 0; i < count; i++) {
            // ±0.02% 수준의 미세한 진동 — ATR 이 0 이 되지 않게만 하고 SL/TP 는 건드리지 않는다
            BigDecimal wiggle = new BigDecimal(i % 2 == 0 ? "0.2" : "-0.2");
            BigDecimal close = base.add(wiggle);
            out.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(base)
                    .high(base.add(new BigDecimal("0.3")))
                    .low(base.subtract(new BigDecimal("0.3")))
                    .close(close)
                    .volume(new BigDecimal("1000"))
                    .build());
        }
        return out;
    }

    private static BacktestConfig.BacktestConfigBuilder baseConfig() {
        return BacktestConfig.builder()
                .strategyName("BUY_ONCE")
                .coinPair("KRW-TEST")
                .timeframe("H1")
                .startDate(T0)
                .endDate(T0.plus(Duration.ofDays(30)))
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of());
    }

    private static List<TradeRecord> sells(BacktestResult r) {
        return r.getTrades().stream().filter(t -> t.getSide() == OrderSide.SELL).toList();
    }

    @Test
    @DisplayName("time stop 이 저변동 포지션을 청산한다 — 운영 청산의 47%를 차지하는 경로")
    void 시간초과청산이_동작한다() {
        List<Candle> candles = flatCandles(200);

        BacktestResult withStop = new BacktestEngine()
                .run(baseConfig().maxHoldHours(24).build(), candles, new BuyOnceStrategy());

        List<TradeRecord> exits = sells(withStop);
        assertThat(exits)
                .as("SL/TP 어디에도 닿지 않는 저변동 구간이다 — time stop 이 없으면 청산이 0건이고, "
                        + "그동안 자본이 묶여 다른 진입을 막는 비용도 재현되지 않는다")
                .isNotEmpty();
        assertThat(exits.get(0).getSignalReason()).contains("시간 초과 청산");
    }

    @Test
    @DisplayName("maxHoldHours 가 없으면 종전대로 청산되지 않는다 — 09-08 이전 동작 재현")
    void 시간초과청산은_설정이_있어야_동작한다() {
        List<Candle> candles = flatCandles(200);

        BacktestResult noStop = new BacktestEngine()
                .run(baseConfig().build(), candles, new BuyOnceStrategy());

        assertThat(sells(noStop))
                .as("maxHoldHours 미설정은 비활성이어야 한다 — 과거 결과와 비교할 수 있어야 하므로")
                .isEmpty();
    }

    @Test
    @DisplayName("보유시간이 기준에 못 미치면 청산하지 않는다 — 무조건 파는 게 아니다")
    void 기준_미달이면_청산하지_않는다() {
        // 캔들 30개 = 진입 후 최대 ~10시간. 24시간 기준에는 닿지 않는다.
        List<Candle> candles = flatCandles(30);

        BacktestResult r = new BacktestEngine()
                .run(baseConfig().maxHoldHours(24).build(), candles, new BuyOnceStrategy());

        assertThat(sells(r))
                .as("보유 10시간 < 24시간인데 청산되면 time stop 이 아니라 그냥 강제 매도다")
                .isEmpty();
    }

    @Test
    @DisplayName("TP 는 8% 를 넘지 않는다 — 백테스트가 실전이 안 쓰는 TP 10% 를 노리면 안 된다")
    void tp가_상한을_넘지_않는다() {
        BigDecimal price = new BigDecimal("1000");
        // SL 8% → 손익비 2:1 이면 TP 16% 가 되지만 상한 8% 로 잘려야 한다
        BigDecimal sl = new BigDecimal("920");

        BigDecimal tp = ExitRuleFormula.resolveTakeProfitPrice(price, sl, null, null);

        assertThat(tp)
                .as("넓은 SL 은 반드시 맞고 넓은 TP 는 사실상 안 맞는다 — 07-31 개편 후 5일간 "
                        + "익절 0건 / 손절 3건이 그 결과다")
                .isLessThanOrEqualTo(new BigDecimal("1080.00000000"));
    }

    @Test
    @DisplayName("SL 은 ATR 을 반영한다 — 고정 5% 로 떨어지지 않는다")
    void sl이_atr을_반영한다() {
        // 변동성이 큰 캔들: 진폭 4% → ATR/가격 × 1.5 가 floor(5%)를 넘어선다
        List<Candle> volatile_ = new ArrayList<>();
        BigDecimal base = new BigDecimal("1000");
        for (int i = 0; i < 60; i++) {
            volatile_.add(Candle.builder()
                    .time(T0.plus(Duration.ofHours(i)))
                    .open(base)
                    .high(base.multiply(new BigDecimal("1.04")))
                    .low(base.multiply(new BigDecimal("0.96")))
                    .close(base)
                    .volume(new BigDecimal("1000"))
                    .build());
        }

        BigDecimal slPct = ExitRuleFormula.resolveStopLossPct(
                new BigDecimal("5.0"), volatile_, base, null);

        assertThat(slPct)
                .as("워치리스트는 ATR 하한을 통과한 고변동 알트다. 여기서 5%% 고정이 나오면 "
                        + "백테스트가 실전보다 훨씬 자주 손절된다 — 수정 전 상태 그대로다")
                .isGreaterThan(new BigDecimal("5.0"))
                .isLessThanOrEqualTo(new BigDecimal("8.0"));
    }

    @Test
    @DisplayName("가드: 백테스트가 공용 공식을 호출한다 — 자체 공식으로 되돌아가면 깨진다")
    void 백테스트가_공용_공식을_쓴다() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/com/cryptoautotrader/core/backtest/BacktestEngine.java"));

        assertThat(src)
                .as("BacktestEngine 이 ExitRuleFormula.resolveStopLossPct 를 쓰지 않는다 — "
                        + "ExitRuleChecker.resolveStopLossPct 는 이름만 비슷한 다른 함수이고, "
                        + "atrStopLossEnabled 기본값 false 때문에 항상 5%% 고정으로 떨어진다")
                .contains("ExitRuleFormula.resolveStopLossPct")
                .contains("ExitRuleFormula.resolveTakeProfitPrice")
                .contains("ExitRuleFormula.shouldTimeStop");
    }

    @Test
    @DisplayName("가드: 규칙을 바꾸면 EXIT_RULES_VERSION 을 올려야 한다")
    void 규칙버전이_기록된다() {
        assertThat(ExitRuleFormula.EXIT_RULES_VERSION)
                .as("이 값은 backtest_run.exit_rules_version 에 기록되고 WalkForwardValidationGate 가 "
                        + "이보다 낮은 실행을 근거로 인정하지 않는다. 게이트는 조합별 '최신' 실행만 보므로, "
                        + "이 장치가 없으면 재실행되지 않은 조합이 수정 전 판정으로 실자본을 계속 승인한다.")
                .isGreaterThanOrEqualTo(2);
    }
}
