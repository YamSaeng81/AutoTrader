package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.metrics.PerformanceReport;
import com.cryptoautotrader.core.risk.ExitRuleConfig;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계 선별 러너({@link BWalkForwardRunner#runAll}) 가짜 자료 시험 (2026-10-28 리뷰 12차 P2).
 *
 * <p>실제 자료로 483 조합을 돌리기 전에 러너의 배선만 확인한다 — 가짜 자료의 성과·판정 값은 보지 않는다.</p>
 * <ul>
 *   <li>21코인 폴더 → 코인 집합 검사 → 전략 × 코인 행 순서·열·전략별 요약 집계</li>
 *   <li>한 행이 같은 입력의 {@link WalkForwardTestRunner} 직접 실행과 같은지</li>
 *   <li>풀 23개 전략이 모두 예외 없이 돌고, 같은 입력을 두 번 돌리면 결과 행이 같은지</li>
 * </ul>
 */
class SelectionRunnerFakeDataTest {

    private static final int BARS = 2000;
    private static final LocalDateTime T0 = LocalDateTime.of(2024, 1, 1, 0, 0);

    @TempDir
    static Path dir;
    static List<Path> csvs;
    static List<Candle> btc;
    static ExitRuleConfig exit;

    @BeforeAll
    static void fakeData() throws Exception {
        for (int k = 0; k < BWalkForwardRunner.COINS.size(); k++) {
            String coin = BWalkForwardRunner.COINS.get(k);
            write(dir.resolve("st_" + coin.substring(4).toLowerCase() + "_h1.csv"), 20261028L + k, 0);
        }
        Path btcFile = dir.resolve("btc").resolve("btc_h1.csv");
        Files.createDirectories(btcFile.getParent());
        write(btcFile, 7L, 0.0002);
        csvs = BWalkForwardRunner.selectCoinCsvs(dir);
        btc = BWalkForwardRunner.loadCsv(btcFile.toString());
        exit = BWalkForwardRunner.exitRuleFromRaw(Map.of("sl", "NULL", "tpMult", "NULL", "trailing", "NULL",
                "trailTpPct", "NULL", "trailSlPct", "NULL", "investPct", "NULL"));
    }

    /** 300봉마다 추세가 바뀌는 로그 랜덤워크 — 신호가 나도록 등락을 섞는다. */
    private static void write(Path file, long seed, double bias) throws IOException {
        Random rnd = new Random(seed);
        List<String> lines = new ArrayList<>();
        double price = 1000.0;
        for (int i = 0; i < BARS; i++) {
            double drift = ((i / 300) % 2 == 0 ? 0.0015 : -0.0012) + bias;
            double open = price;
            double close = open * Math.exp(drift + 0.008 * rnd.nextGaussian());
            double high = Math.max(open, close) * (1 + 0.004 * rnd.nextDouble());
            double low = Math.min(open, close) * (1 - 0.004 * rnd.nextDouble());
            double vol = 1000 + 500 * rnd.nextDouble();
            lines.add(String.format("%s,%.8f,%.8f,%.8f,%.8f,%.4f",
                    T0.plusHours(i).toString().replace('T', ' ') + ":00", open, high, low, close, vol));
            price = close;
        }
        Files.write(file, lines);
    }

    private static String[] cols(String row) {
        return row.split(",", -1);
    }

    @Test
    @DisplayName("21코인 × 전략 2개: 행 순서·열·요약 집계, 한 행은 WF 직접 실행과 같다")
    void 코인_21개_배선() throws Exception {
        assertThat(csvs).hasSize(21);
        List<String> strategies = List.of("EMA_CROSS", "GRID");
        BWalkForwardRunner.Result res = BWalkForwardRunner.runAll(strategies, csvs, btc, exit);

        assertThat(res.rows()).hasSize(1 + 2 * 21);
        List<String> header = List.of(cols(res.rows().get(0)));
        assertThat(header).containsExactly("strategy", "coin", "candles", "start", "end", "btcCandles", "oosTrades",
                "oosExpectancyPct", "oosTotalReturnPct", "scoreMethod", "overfittingScore", "rawOverfittingScore",
                "verdict", "codeGate", "preregGate", "preregReason");
        for (int s = 0; s < 2; s++) {
            int pass = 0, code = 0;
            for (int k = 0; k < 21; k++) {
                String[] c = cols(res.rows().get(1 + s * 21 + k));
                assertThat(c).hasSize(header.size());
                assertThat(c[0]).isEqualTo(strategies.get(s));
                assertThat(c[1]).isEqualTo(BWalkForwardRunner.COINS.get(k));   // 사전 등록 목록 순(운영체제 무관)
                assertThat(c[2]).isEqualTo(String.valueOf(BARS));
                assertThat(c[9]).isEqualTo(WalkForwardTestRunner.SCORE_METHOD);
                assertThat(c[11]).as("이전 식 점수도 남는다").isNotEmpty();
                pass += "충족".equals(c[14]) ? 1 : 0;
                code += "PASS".equals(c[13]) ? 1 : 0;
            }
            String[] sum = cols(res.summary().get(1 + s));
            assertThat(sum).containsExactly(strategies.get(s), "21", String.valueOf(code), String.valueOf(pass),
                    pass >= BWalkForwardRunner.ELIGIBLE_MIN_COINS ? "적격" : "미적격");
        }

        // 한 행(GRID × KRW-NEAR)을 같은 입력의 WF 직접 실행과 대조
        int k = BWalkForwardRunner.COINS.indexOf("KRW-NEAR");
        List<Candle> candles = BWalkForwardRunner.loadCsv(csvs.get(k).toString());
        List<Candle> slice = btc.stream()
                .filter(c -> !c.getTime().isBefore(candles.get(0).getTime())
                        && !c.getTime().isAfter(candles.get(candles.size() - 1).getTime())).toList();
        WalkForwardTestRunner.WalkForwardResult r = new WalkForwardTestRunner()
                .run(BWalkForwardRunner.config("GRID", "KRW-NEAR", candles, slice, exit), candles, 0.7, 5);
        PerformanceReport agg = r.getAggregatedOutSampleMetrics();
        String[] row = cols(res.rows().get(1 + 21 + k));
        assertThat(row[5]).isEqualTo(String.valueOf(slice.size()));
        assertThat(agg).as("가짜 자료에서 GRID 의 OOS 거래가 있어야 대조가 의미 있다").isNotNull();
        assertThat(agg.getTotalTrades()).isPositive();
        assertThat(row[6]).isEqualTo(String.valueOf(agg.getTotalTrades()));
        assertThat(row[7]).isEqualTo(agg.getExpectancyPct() == null ? "" : agg.getExpectancyPct().toPlainString());
        assertThat(row[10]).isEqualTo(r.getOverfittingScore() == null ? "" : r.getOverfittingScore().toPlainString());
        assertThat(row[11]).isEqualTo(r.getRawOverfittingScore().toPlainString());
        assertThat(row[12]).isEqualTo(r.getVerdict());
    }

    @Test
    @DisplayName("풀 23개 × 코인 2개: 모든 전략이 예외 없이 돌고, 두 번 돌려도 결과 행이 같다")
    void 풀_23개_전부_실행_재현() throws Exception {
        List<Path> two = List.of(csvs.get(0), csvs.get(1));
        BWalkForwardRunner.Result a = BWalkForwardRunner.runAll(BWalkForwardRunner.SELECTION_POOL, two, btc, exit);
        BWalkForwardRunner.Result b = BWalkForwardRunner.runAll(BWalkForwardRunner.SELECTION_POOL, two, btc, exit);

        assertThat(a.rows()).hasSize(1 + 23 * 2);
        assertThat(a.summary()).hasSize(1 + 23);
        for (int s = 0; s < 23; s++) {
            String name = BWalkForwardRunner.SELECTION_POOL.get(s);
            for (int k = 0; k < 2; k++) {
                String[] c = cols(a.rows().get(1 + s * 2 + k));
                assertThat(c[0]).isEqualTo(name);
                assertThat(c[9]).isEqualTo(WalkForwardTestRunner.SCORE_METHOD);
                assertThat(c[12]).as("%s verdict", name).isNotEmpty();
            }
            assertThat(cols(a.summary().get(1 + s))[0]).isEqualTo(name);
        }
        assertThat(b.rows()).as("같은 입력 → 같은 결과 (결과 해시 보존의 전제)").isEqualTo(a.rows());
        assertThat(b.summary()).isEqualTo(a.summary());
    }
}
