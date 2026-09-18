package com.cryptoautotrader.core.backtest;

import com.cryptoautotrader.core.model.TradeRecord;
import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BTC_MARKET_GUARD 가 <b>체결 시점에 아직 끝나지 않은 BTC 캔들</b>을 보지 않는지 고정한다.
 *
 * <h3>이전 동작</h3>
 * <p>{@code BacktestEngine} 은 BTC 포인터를 {@code btcCandle.time <= nextCandle.time} 까지
 * 전진시켰다. {@code Candle.time} 은 캔들 <b>시작</b> 시각이므로, 체결 순간({@code nextCandle.open})에
 * 시작하는 BTC 캔들이 윈도우에 들어갔고 Guard 는 그 봉의 <b>종가·고가</b>로 판정했다.
 * 그 값들은 체결 시점에 아직 정해지지 않았다 — 봉 후반의 BTC 급락을 미리 알고 진입을 피하는
 * look-ahead bias 다.
 *
 * <h3>검증 방법</h3>
 * <p>체결 봉과 같은 시각에 시작하는 BTC 캔들의 <b>내부만</b>(종가·고가) 급락으로 바꿔치기한다.
 * 그 봉이 시작하기 전까지 알 수 있었던 정보는 완전히 동일하므로, 체결 결과도 같아야 한다.
 * 이전 구현에서는 이 조작만으로 진입이 차단되어 거래 시퀀스가 달라졌다.
 */
class BtcGuardLookAheadTest {

    private final BacktestEngine engine = new BacktestEngine();

    /** 평온한 BTC 에서 GRID 가 매수를 체결하는 캔들 인덱스 — 실측값. */
    private static final int BUY_CANDLE_INDEX = 115;

    @Test
    void 체결봉과_같은_시각에_시작하는_BTC봉_내부는_진입판정에_영향을_주지_않는다() {
        List<Candle> candles = oscillatingCandles(400);

        // 평온한 BTC — Guard 미발동
        List<Candle> calmBtc = btcCandles(400, -1);
        // 같은 BTC 이지만, 중간의 한 봉만 내부(종가·고가)가 급락하도록 바꾼다.
        // 그 봉의 '시작 시각'과 그 이전 모든 정보는 calmBtc 와 동일하다.
        // 급락 봉을 **매수가 체결되는 바로 그 시각**에 놓는다. 이 정렬이 없으면 이전 구현에서도
        // 관측 가능한 차이가 생기지 않아 테스트가 아무것도 잡지 못한다(실제로 처음엔 그랬다).
        final int crashIndex = BUY_CANDLE_INDEX;
        List<Candle> crashInsideBtc = btcCandles(400, crashIndex);

        String withCalm  = tradeTrace(run(candles, calmBtc));
        String withCrash = tradeTrace(run(candles, crashInsideBtc));

        assertThat(withCalm)
                .as("체결이 없으면 이 테스트는 아무것도 검증하지 못한다")
                .isNotEmpty();
        assertThat(withCrash)
                .as("아직 끝나지 않은 BTC 봉의 내부가 진입 판정을 바꾸면 미래를 참조한 것이다")
                .isEqualTo(withCalm);
    }

    @Test
    void 이미_종료된_BTC봉의_급락은_정상적으로_진입을_차단한다() {
        // 반대 방향 고정 — Guard 자체가 무력화되지 않았는지 확인한다.
        // 급락을 충분히 이른 시점부터 지속시키면, 이후 체결 시점에는 이미 '종료된' 봉의
        // 정보이므로 Guard 가 발동해 신규 진입이 줄어야 한다.
        List<Candle> candles = oscillatingCandles(400);

        String withCalm  = tradeTrace(run(candles, btcCandles(400, -1)));
        String withCrash = tradeTrace(run(candles, crashingBtcCandles(400)));

        assertThat(withCalm).isNotEmpty();
        assertThat(withCrash)
                .as("종료된 BTC 봉의 급락은 Guard 가 잡아야 한다")
                .isNotEqualTo(withCalm);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private BacktestResult run(List<Candle> candles, List<Candle> btcCandles) {
        BacktestConfig config = BacktestConfig.builder()
                .strategyName("GRID")
                .coinPair("KRW-ETH")          // BTC 가 아니어야 별도 BTC 시리즈가 쓰인다
                .timeframe("H1")
                .startDate(candles.get(0).getTime())
                .endDate(candles.get(candles.size() - 1).getTime())
                .initialCapital(new BigDecimal("10000000"))
                .slippagePct(new BigDecimal("0.1"))
                .feePct(new BigDecimal("0.05"))
                .strategyParams(Map.of())
                .btcCandles(btcCandles)
                .build();
        return engine.run(config, candles);
    }

    private String tradeTrace(BacktestResult result) {
        StringBuilder sb = new StringBuilder();
        for (TradeRecord t : result.getTrades()) {
            sb.append(t.getSide()).append('@').append(t.getExecutedAt()).append(';');
        }
        return sb.toString();
    }

    /** 100~110 범위를 오가는 대상 코인 캔들 — GRID 가 반복 매매한다. */
    private List<Candle> oscillatingCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            BigDecimal p = BigDecimal.valueOf(105 + 5 * Math.sin(i * 2 * Math.PI / 24));
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(p).high(BigDecimal.valueOf(110)).low(BigDecimal.valueOf(100)).close(p)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }

    /**
     * 평온한 BTC 캔들. {@code crashIndex >= 0} 이면 그 <b>한 봉의 내부만</b> 급락시킨다
     * (시작 시각과 시가는 그대로, 종가·고가만 낮춘다).
     */
    private List<Candle> btcCandles(int count, int crashIndex) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            BigDecimal p = BigDecimal.valueOf(50_000_000);
            BigDecimal close = (i == crashIndex) ? BigDecimal.valueOf(40_000_000) : p;
            BigDecimal high  = (i == crashIndex) ? BigDecimal.valueOf(50_000_000) : p;
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(p).high(high).low(close).close(close)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }

    /** 중반부터 지속 하락하는 BTC — 체결 시점에는 이미 종료된 봉들의 정보로 Guard 가 발동한다. */
    private List<Candle> crashingBtcCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            double price = (i < 100) ? 50_000_000 : 50_000_000 * Math.pow(0.97, i - 100);
            BigDecimal p = BigDecimal.valueOf(price);
            candles.add(Candle.builder()
                    .time(base.plus(i, ChronoUnit.HOURS))
                    .open(p).high(p).low(p).close(p)
                    .volume(BigDecimal.valueOf(100))
                    .build());
        }
        return candles;
    }
}
