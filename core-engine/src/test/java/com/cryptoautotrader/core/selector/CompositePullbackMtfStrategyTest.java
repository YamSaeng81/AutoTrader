package com.cryptoautotrader.core.selector;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.StrategySignal.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompositePullbackMtfStrategy — 눌림목 회복 진입 전략")
class CompositePullbackMtfStrategyTest {

    private final CompositePullbackMtfStrategy strategy = new CompositePullbackMtfStrategy();

    /** close 배열로 캔들 생성 (high=close+15, low=close-15, open=직전 close, volume=100). */
    private static List<Candle> fromCloses(double[] closes) {
        List<Candle> list = new ArrayList<>(closes.length);
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            double open = i == 0 ? c : closes[i - 1];
            list.add(Candle.builder()
                    .time(Instant.ofEpochSecond(i * 3600L))
                    .open(BigDecimal.valueOf(open))
                    .high(BigDecimal.valueOf(c + 15))
                    .low(BigDecimal.valueOf(c - 15))
                    .close(BigDecimal.valueOf(c))
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("기본 식별자 — 이름/최소 캔들 수")
    void identity() {
        assertThat(strategy.getName()).isEqualTo("COMPOSITE_PULLBACK_MTF");
        assertThat(strategy.getMinimumCandleCount()).isEqualTo(200);
    }

    @Test
    @DisplayName("데이터 부족(200 미만) → HOLD")
    void insufficientData() {
        StrategySignal s = strategy.evaluate(fromCloses(linearUptrend(100)), Map.of());
        assertThat(s.getAction()).isEqualTo(Action.HOLD);
        assertThat(s.getReason()).contains("데이터 부족");
    }

    @Test
    @DisplayName("상승 추세 중 EMA20/VWAP 눌림 후 회복 → BUY (RSI/ADX 게이트 완화)")
    void pullbackRecoveryBuy() {
        // RSI·ADX 게이트를 넓혀 눌림목→회복 구조 자체만 검증한다.
        Map<String, Object> params = new HashMap<>();
        params.put("rsiLow", 0.0);
        params.put("rsiHigh", 100.0);
        params.put("adxMin", 0.0);

        StrategySignal s = strategy.evaluate(fromCloses(uptrendThenPullbackRecovery()), params);

        assertThat(s.getAction()).isEqualTo(Action.BUY);
        assertThat(s.getReason()).contains("눌림목 회복");
    }

    @Test
    @DisplayName("횡보장(ADX 미달) → HOLD (진입 금지)")
    void rangeBlocked() {
        // adxMin을 비현실적으로 높여 횡보장 차단 경로를 강제한다.
        Map<String, Object> params = new HashMap<>();
        params.put("rsiLow", 0.0);
        params.put("rsiHigh", 100.0);
        params.put("adxMin", 999.0);

        StrategySignal s = strategy.evaluate(fromCloses(uptrendThenPullbackRecovery()), params);

        assertThat(s.getAction()).isEqualTo(Action.HOLD);
        assertThat(s.getReason()).contains("횡보장");
    }

    @Test
    @DisplayName("종가가 EMA20 아래로 이탈 → SELL")
    void ema20BreakSell() {
        StrategySignal s = strategy.evaluate(fromCloses(uptrendThenSharpDrop()), Map.of());
        assertThat(s.getAction()).isEqualTo(Action.SELL);
        // EMA20 이탈 또는 H4 하락 전환 중 하나의 청산 사유
        assertThat(s.getReason()).containsAnyOf("EMA20 이탈", "Supertrend 하락");
    }

    // ── 합성 시계열 생성기 ──────────────────────────────────────────────────────

    private static double[] linearUptrend(int n) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = 10000 + 30.0 * i;
        return c;
    }

    /** 0~213 선형 상승 → 214~217 눌림 → 218~219 회복 (총 220봉). */
    private static double[] uptrendThenPullbackRecovery() {
        double[] c = new double[220];
        for (int i = 0; i <= 213; i++) c[i] = 10000 + 30.0 * i;   // i=213 → 16390
        c[214] = 16330; c[215] = 16270; c[216] = 16210; c[217] = 16170; // 눌림
        c[218] = 16260; c[219] = 16360;                                  // 회복 (직전 상회 + EMA20 위)
        return c;
    }

    /** 0~213 선형 상승 → 214~219 급락 (종가가 EMA20 아래로 이탈). */
    private static double[] uptrendThenSharpDrop() {
        double[] c = new double[220];
        for (int i = 0; i <= 213; i++) c[i] = 10000 + 30.0 * i;   // i=213 → 16390
        double last = c[213];
        for (int i = 214; i < 220; i++) { last -= 250; c[i] = last; } // 급락 → 15390 부근
        return c;
    }
}
