package com.cryptoautotrader.api.backtest;

import com.cryptoautotrader.core.backtest.BacktestConfig;
import com.cryptoautotrader.core.backtest.BacktestEngine;
import com.cryptoautotrader.core.backtest.BacktestResult;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.selector.CompositeRegimeRouter;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.Strategy;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-A / P1-B 변경의 실데이터 백테스트 검증 (2026-06-01).
 *
 * <p><b>실행 조건</b>: 로컬 PostgreSQL(crypto_auto_trader)에 candle_data 가 있어야 한다.
 * DB 미접속 시 {@link Assumptions}로 전체 스킵(빌드 실패 아님).
 *
 * <p>이 테스트는 <b>합격/불합격 단언이 목적이 아니라</b> 변경 전후 지표를 표로 출력해
 * 사람이 라이브 반영 여부를 판단하기 위한 측정 하니스다. 따라서 단언은 "엔진이 정상
 * 실행되어 결과가 나온다" 수준만 둔다.
 *
 * <p><b>P1-A</b>: CompositeRegimeRouter에서 TRANSITIONAL 위임 시 MACD adxThreshold를
 * 25→20으로 낮춰 주입한다. putIfAbsent 라서 호출자가 adxThreshold=25를 명시하면 옛 동작이
 * 그대로 재현된다 → 같은 라우터로 (a) params에 adxThreshold=25 주입(=변경 전) vs
 * (b) 미주입(=변경 후 자동 20)을 비교해 효과를 격리한다.
 *
 * <p><b>P1-B</b>: DOGE EMA200 게이트 면제 효과를 Ema200RegimeGate 로 직접 측정한다.
 */
class P1ChangesBacktestVerification {

    private static final String JDBC_URL =
            System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/crypto_auto_trader");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USERNAME", "trader");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASSWORD", "devpassword");

    private static final String[] COINS = {"KRW-BTC", "KRW-ETH", "KRW-SOL", "KRW-XRP", "KRW-DOGE"};
    private static final String TIMEFRAME = "H1";
    // 2023-01-01 ~ 2025-12-30 (최근 3년)
    private static final Instant FROM = Instant.parse("2023-01-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2025-12-30T00:00:00Z");

    private static boolean dbAvailable = false;

    @BeforeAll
    static void checkDb() {
        try (Connection c = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS)) {
            dbAvailable = c != null && !c.isClosed();
        } catch (Exception e) {
            dbAvailable = false;
            System.out.println("[P1Verify] DB 미접속 — 테스트 스킵: " + e.getMessage());
        }
    }

    @Test
    void p1a_transitional_adx_정합화_효과_측정() {
        Assumptions.assumeTrue(dbAvailable, "DB 미접속 — P1-A 검증 스킵");

        System.out.println("\n========== P1-A: TRANSITIONAL MACD adxThreshold 25→20 ==========");
        System.out.printf("%-10s | %-22s | %-22s%n", "coin", "변경前(adxTh=25 고정)", "변경後(TRANSITIONAL=20)");
        System.out.println("-".repeat(62));

        for (String coin : COINS) {
            List<Candle> candles = loadCandles(coin);
            if (candles.size() < 300) {
                System.out.printf("%-10s | 데이터 부족(%d)%n", coin, candles.size());
                continue;
            }
            // 변경 전: adxThreshold=25를 명시 주입 → router의 putIfAbsent가 덮어쓰지 않음 = 옛 동작
            BacktestResult before = runRouter(coin, candles, Map.of("adxThreshold", 25.0));
            // 변경 후: 아무것도 명시 안 함 → TRANSITIONAL에서 자동 20 주입
            BacktestResult after = runRouter(coin, candles, Map.of());

            System.out.printf("%-10s | %-22s | %-22s%n", coin, fmt(before), fmt(after));
        }
        System.out.println("=".repeat(62));
    }

    @Test
    void p1b_doge_ema200_예외_효과_측정() {
        Assumptions.assumeTrue(dbAvailable, "DB 미접속 — P1-B 검증 스킵");

        System.out.println("\n========== P1-B: EMA200 게이트 — DOGE 예외 효과 ==========");
        System.out.printf("%-10s | %-14s | %-14s | %s%n", "coin", "게이트적용", "게이트면제", "BUY신호 변화");
        System.out.println("-".repeat(62));

        for (String coin : COINS) {
            List<Candle> candles = loadCandles(coin);
            if (candles.size() < 300) continue;

            // 게이트를 코인 그대로 적용 vs coinPair=null(면제 없음)로 강제 — BUY 허용 캔들 수 비교
            int allowedActual = 0;   // 실제 coinPair (DOGE면 항상 허용)
            int allowedStrict = 0;   // EMA200 규칙 엄격 적용 (DOGE 예외 없음 가정 = null)
            int total = 0;
            for (int i = 200; i < candles.size(); i++) {
                List<Candle> window = candles.subList(0, i + 1);
                total++;
                if (Ema200RegimeGate.allowsBuy(window, coin)) allowedActual++;
                // null → DOGE 예외 미적용, 순수 EMA200 규칙
                if (Ema200RegimeGate.allowsBuy(window, null)) allowedStrict++;
            }
            String delta = coin.contains("DOGE")
                    ? String.format("DOGE예외 +%d캔들(%.1f%%p)", allowedActual - allowedStrict,
                        100.0 * (allowedActual - allowedStrict) / total)
                    : "동일(예외 대상 아님)";
            System.out.printf("%-10s | %5d/%-7d | %5d/%-7d | %s%n",
                    coin, allowedActual, total, allowedStrict, total, delta);
        }
        System.out.println("=".repeat(62));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private BacktestResult runRouter(String coin, List<Candle> candles, Map<String, Object> extraParams) {
        Strategy router = new CompositeRegimeRouter();
        Map<String, Object> params = new HashMap<>(extraParams);
        params.put("coinPair", coin);
        BacktestConfig config = BacktestConfig.builder()
                .strategyName("COMPOSITE_REGIME_ROUTER")
                .coinPair(coin)
                .timeframe(TIMEFRAME)
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .strategyParams(params)
                .build();
        return new BacktestEngine().run(config, candles, router);
    }

    private static String fmt(BacktestResult r) {
        var m = r.getMetrics();
        return String.format("R%+.1f%% W%.0f%% T%d MDD%.1f%%",
                m.getTotalReturnPct().doubleValue(),
                m.getWinRatePct().doubleValue(),
                m.getTotalTrades(),
                m.getMddPct().doubleValue());
    }

    private List<Candle> loadCandles(String coin) {
        List<Candle> candles = new ArrayList<>();
        String sql = "SELECT time, open, high, low, close, volume FROM candle_data " +
                "WHERE coin_pair = ? AND timeframe = ? AND time >= ? AND time <= ? ORDER BY time ASC";
        try (Connection c = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, coin);
            ps.setString(2, TIMEFRAME);
            ps.setObject(3, FROM.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(4, TO.atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candles.add(Candle.builder()
                            .time(rs.getObject("time", java.time.OffsetDateTime.class).toInstant())
                            .open(rs.getBigDecimal("open"))
                            .high(rs.getBigDecimal("high"))
                            .low(rs.getBigDecimal("low"))
                            .close(rs.getBigDecimal("close"))
                            .volume(rs.getBigDecimal("volume"))
                            .build());
                }
            }
        } catch (Exception e) {
            System.out.println("[P1Verify] 캔들 로드 실패 " + coin + ": " + e.getMessage());
        }
        return candles;
    }
}
