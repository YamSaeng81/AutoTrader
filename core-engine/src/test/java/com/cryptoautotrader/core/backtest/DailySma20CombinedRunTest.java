package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.model.OrderSide;
import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.core.regime.MarketRegime;
import com.cryptoautotrader.core.regime.MarketRegimeDetector;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.core.selector.BlackSwanGuard;
import com.cryptoautotrader.core.selector.BtcMarketGuard;
import com.cryptoautotrader.core.selector.Ema200RegimeGate;
import com.cryptoautotrader.core.selector.RangeRegimeGate;
import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import com.cryptoautotrader.strategy.dailysma.DailySmaTrendStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "DAILY_SMA20 신호 + 운영 보호 규칙" 결합 — 개발 자료 1회 실행 (docs/LIMITED_LIVE_TEST_PLAN.md §5-3, 실행 전 고정).
 *
 * <p>{@code -Dsma20.h1=<BTC H1 CSV> -Dsma20.out=<폴더>} 가 있을 때만 돈다. 운영 엔진과 같은 판정 함수를 쓰는
 * {@link BacktestEngine} 으로 거래를 만들고(EMA200·RANGE·BTC 급락 관문, ATR 손절·익절·추적, 시간 청산, 전략 SELL 차단),
 * 관문 진단은 일봉 마감마다 같은 클래스로 따로 센다. BLACK_SWAN 관문은 백테스트 엔진에 없어 진단에서만 센다.</p>
 *
 * <p>팔(고정): 비용 주(수수료 0.05% + 슬리피지 0.10%)·스트레스(0.05% + 0.20%) × 시간 청산 A 24h(운영 기본)·B 끔(참고).
 * B 는 운영 보호 해제라 리뷰 판단 전에는 채택하지 않는다. 결과로 규칙·설정을 바꾸지 않는다.</p>
 */
class DailySma20CombinedRunTest {

    private static final BigDecimal SESSION_CAPITAL = new BigDecimal("20000");
    private static final BigDecimal POOL = new BigDecimal("100000");

    record Arm(String name, BigDecimal slippagePct, int maxHoldHours) {}

    static final List<Arm> ARMS = List.of(
            new Arm("A_main_24h", new BigDecimal("0.10"), 24),
            new Arm("A_stress_24h", new BigDecimal("0.20"), 24),
            new Arm("B_main_off", new BigDecimal("0.10"), 0),
            new Arm("B_stress_off", new BigDecimal("0.20"), 0));

    static List<Candle> loadH1(String path) throws IOException {
        List<Candle> candles = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.split(",");
            candles.add(Candle.builder().time(Instant.parse(f[0] + "Z"))
                    .open(new BigDecimal(f[1])).high(new BigDecimal(f[2])).low(new BigDecimal(f[3]))
                    .close(new BigDecimal(f[4])).volume(new BigDecimal(f[5])).build());
        }
        return candles;
    }

    static String exitKind(String reason) {
        if (reason == null) return "기타";
        if (reason.startsWith("[AMBIGUOUS") || reason.startsWith("손절") || reason.startsWith("갭 손절")) return "손절";
        if (reason.startsWith("익절") || reason.startsWith("갭 익절")) return "익절";
        if (reason.startsWith("시간 초과")) return "시간 청산";
        if (reason.startsWith("기간 종료")) return "기간 종료";
        return "전략 SELL";
    }

    @Test
    @EnabledIfSystemProperty(named = "sma20.h1", matches = ".+")
    void runCombined() throws IOException {
        List<Candle> candles = loadH1(System.getProperty("sma20.h1"));
        Path out = Path.of(System.getProperty("sma20.out", "d:/tmp/sma20_run"));
        Files.createDirectories(out);
        DailySmaTrendStrategy strategy = new DailySmaTrendStrategy();

        List<String> summary = new ArrayList<>();
        summary.add("arm,entries,exit_stop_loss,exit_take_profit,exit_time_stop,exit_strategy_sell,exit_period_end,"
                + "avg_hold_h,in_position_ratio,total_pnl_krw,session_return,pool_return,fees_krw,slippage_krw_approx,"
                + "first_trade,last_trade,evaluated_bars");
        for (Arm arm : ARMS) {
            ExitRuleConfig exit = ExitRuleConfig.builder()
                    .stopLossPct(new BigDecimal("5.0"))
                    .investRatio(new BigDecimal("0.995"))
                    .build();
            BacktestConfig cfg = BacktestConfig.builder()
                    .strategyName(DailySmaTrendStrategy.NAME).coinPair("KRW-BTC").timeframe("H1")
                    .initialCapital(SESSION_CAPITAL).feePct(new BigDecimal("0.05")).slippagePct(arm.slippagePct())
                    .strategyParams(Map.of()).exitRuleConfig(exit).maxHoldHours(arm.maxHoldHours())
                    .btcCandles(candles)
                    .build();
            BacktestResult r = new BacktestEngine().run(cfg, candles, strategy);

            List<String> rows = new ArrayList<>();
            rows.add("side,executed_at,price,quantity,fee,slippage_pct,pnl,cumulative_pnl,exit_kind,reason");
            Map<String, Integer> exits = new LinkedHashMap<>();
            for (String k : List.of("손절", "익절", "시간 청산", "전략 SELL", "기간 종료")) exits.put(k, 0);
            int entries = 0;
            long heldMinutes = 0;
            Instant openAt = null;
            BigDecimal fees = BigDecimal.ZERO, slip = BigDecimal.ZERO, pnl = BigDecimal.ZERO;
            for (TradeRecord t : r.getTrades()) {
                String kind = "";
                if (t.getSide() == OrderSide.BUY) {
                    entries++;
                    openAt = t.getExecutedAt();
                } else {
                    kind = exitKind(t.getSignalReason());
                    exits.merge(kind, 1, Integer::sum);
                    if (openAt != null) heldMinutes += Duration.between(openAt, t.getExecutedAt()).toMinutes();
                    openAt = null;
                    pnl = pnl.add(t.getPnl());
                }
                fees = fees.add(t.getFee());
                slip = slip.add(t.getPrice().multiply(t.getQuantity()).multiply(t.getSlippage())
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                rows.add(String.join(",", t.getSide().name(), t.getExecutedAt().toString(), t.getPrice().toPlainString(),
                        t.getQuantity().toPlainString(), t.getFee().toPlainString(), t.getSlippage().toPlainString(),
                        t.getPnl().toPlainString(), t.getCumulativePnl().toPlainString(), kind,
                        "\"" + (t.getSignalReason() == null ? "" : t.getSignalReason().replace("\"", "'")) + "\""));
            }
            Files.write(out.resolve("combined_trades_" + arm.name() + ".csv"), rows, StandardCharsets.UTF_8);

            int exitCount = exits.values().stream().mapToInt(Integer::intValue).sum();
            Instant first = r.getTrades().isEmpty() ? null : r.getTrades().get(0).getExecutedAt();
            Instant last = candles.get(candles.size() - 1).getTime();
            long spanMin = first == null ? 1 : Duration.between(first, last).toMinutes();
            summary.add(String.join(",", arm.name(), Integer.toString(entries),
                    exits.get("손절").toString(), exits.get("익절").toString(), exits.get("시간 청산").toString(),
                    exits.get("전략 SELL").toString(), exits.get("기간 종료").toString(),
                    exitCount == 0 ? "" : String.format("%.1f", heldMinutes / 60.0 / exitCount),
                    String.format("%.4f", heldMinutes / (double) spanMin),
                    pnl.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                    pnl.divide(SESSION_CAPITAL, 6, RoundingMode.HALF_UP).toPlainString(),
                    pnl.divide(POOL, 6, RoundingMode.HALF_UP).toPlainString(),
                    fees.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                    slip.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                    String.valueOf(first), r.getTrades().isEmpty() ? "" : r.getTrades().get(r.getTrades().size() - 1).getExecutedAt().toString(),
                    Integer.toString(r.getEvaluatedBars())));
        }
        Files.write(out.resolve("combined_summary.csv"), summary, StandardCharsets.UTF_8);
        summary.forEach(s -> System.out.println("[SMA20 결합] " + s));

        // 관문 진단 — 일봉 마감마다, 보유 여부와 무관하게. LIVE 의 관문 순서(EMA200 → RANGE → BLACK_SWAN → BTC)로 첫 차단을 적는다.
        List<String> gates = new ArrayList<>();
        gates.add("date_utc,signal,ema200_pass,regime,range_block,black_swan,btc_guard,first_block");
        MarketRegimeDetector detector = new MarketRegimeDetector();
        int buyDays = 0;
        Map<String, Integer> firstBlock = new LinkedHashMap<>();
        for (String k : List.of("없음", "EMA200", "RANGE", "BLACK_SWAN", "BTC_MARKET_GUARD")) firstBlock.put(k, 0);
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).getTime().atZone(ZoneOffset.UTC).getHour() != 23) continue;
            List<Candle> window = candles.subList(Math.max(0, i + 1 - BacktestEngine.MAX_LOOKBACK), i + 1);
            if (window.size() < strategy.getMinimumCandleCount()) continue;
            StrategySignal s = strategy.evaluate(window, Map.of("timeframe", "H1"));
            boolean ema = Ema200RegimeGate.allowsBuy(window, "KRW-BTC");
            MarketRegime regime = detector.detectRaw(window); // LIVE 는 detectRaw(이력 없음)를 쓴다
            boolean range = regime == MarketRegime.RANGE && RangeRegimeGate.isBlocked(DailySmaTrendStrategy.NAME);
            boolean swan = BlackSwanGuard.check(window).triggered();
            boolean btc = BtcMarketGuard.check(window.subList(Math.max(0, window.size() - 200), window.size())).triggered();
            String block = !ema ? "EMA200" : range ? "RANGE" : swan ? "BLACK_SWAN" : btc ? "BTC_MARKET_GUARD" : "없음";
            if (s.getAction() == StrategySignal.Action.BUY) {
                buyDays++;
                firstBlock.merge(block, 1, Integer::sum);
            }
            gates.add(String.join(",", candles.get(i).getTime().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                    s.getAction().name(), Boolean.toString(ema), regime.name(), Boolean.toString(range),
                    Boolean.toString(swan), Boolean.toString(btc), s.getAction() == StrategySignal.Action.BUY ? block : ""));
        }
        Files.write(out.resolve("gate_diagnostic.csv"), gates, StandardCharsets.UTF_8);
        System.out.println("[SMA20 관문 진단] BUY 마감 " + buyDays + "일, 첫 차단 " + firstBlock);
    }
}
