package com.cryptoautotrader.strategy.dailysma;

import com.cryptoautotrader.strategy.Candle;
import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 개발 자료 신호 대조용 출력 (docs/LIMITED_LIVE_TEST_PLAN.md §5-3) — {@code -Dsma20.h1} 이 없으면 돌지 않는다.
 *
 * <p>{@code sma20.h1}(시각,시가,고가,저가,종가,거래량 — 머리글 없음, UTC)의 23:00 UTC 봉마다 전략을 평가해
 * {@code sma20.out}/strategy_signals.csv 에 {@code 날짜,신호,사유}를 쓴다. 창은 운영 LIVE 와 같게 최근 499봉
 * (조회 500개 중 진행 중 봉 제외)이다. 계산기(scripts/limited_live/btc_sma20.py)의 일봉 신호와의 날짜별 대조는
 * scripts/limited_live/parity_check.py 가 한다.</p>
 */
class DailySmaParityRunTest {

    static final int LIVE_EVAL_WINDOW = 499;

    static List<Candle> loadH1(String path) throws IOException {
        List<Candle> candles = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.split(",");
            candles.add(Candle.builder().time(Instant.parse(f[0] + "Z"))
                    .open(new BigDecimal(f[1])).high(new BigDecimal(f[2])).low(new BigDecimal(f[3]))
                    .close(new BigDecimal(f[4])).volume(new BigDecimal(f[5])).build());
        }
        for (int i = 1; i < candles.size(); i++) {
            if (!candles.get(i).getTime().isAfter(candles.get(i - 1).getTime())) {
                throw new IllegalStateException("시간 오름차순 아님: " + candles.get(i).getTime());
            }
        }
        return candles;
    }

    @Test
    @EnabledIfSystemProperty(named = "sma20.h1", matches = ".+")
    void writeSignalsForParityCheck() throws IOException {
        List<Candle> candles = loadH1(System.getProperty("sma20.h1"));
        Path out = Path.of(System.getProperty("sma20.out", "d:/tmp/sma20_run"));
        Files.createDirectories(out);

        DailySmaTrendStrategy strategy = new DailySmaTrendStrategy();
        List<String> rows = new ArrayList<>();
        rows.add("date_utc,action,reason");
        for (int i = 0; i < candles.size(); i++) {
            Instant t = candles.get(i).getTime();
            if (t.atZone(ZoneOffset.UTC).getHour() != 23) continue;
            List<Candle> window = candles.subList(Math.max(0, i + 1 - LIVE_EVAL_WINDOW), i + 1);
            StrategySignal s = strategy.evaluate(window, Map.of("timeframe", "H1", "coinPair", "KRW-BTC"));
            rows.add(t.atZone(ZoneOffset.UTC).toLocalDate() + "," + s.getAction() + ",\"" + s.getReason().replace("\"", "'") + "\"");
        }
        Files.write(out.resolve("strategy_signals.csv"), rows, StandardCharsets.UTF_8);
        System.out.println("[DAILY_SMA20 대조 출력] " + (rows.size() - 1) + "일 → " + out.resolve("strategy_signals.csv"));
    }
}
