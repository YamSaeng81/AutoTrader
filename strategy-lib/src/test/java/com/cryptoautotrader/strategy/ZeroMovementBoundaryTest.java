package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import com.cryptoautotrader.strategy.volumedelta.VolumeDeltaStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무변동 구간 경계 처리 — Wave 3-H 회귀.
 *
 * <p>가격이나 거래 방향이 <b>전혀 움직이지 않는</b> 구간에서 지표가 중립이 아니라
 * <b>극단값</b>을 내고 있었다. 둘 다 같은 모양이다:
 *
 * <ul>
 *   <li>RSI — 상승분도 하락분도 0 이면 {@code avgLoss == 0} 가지에 걸려 <b>100</b>(최고 과매수)을
 *       돌려줬다. 완전 횡보에서 RSI 전략은 과매수로 읽고 매도한다.</li>
 *   <li>VolumeDelta — {@code high == low} 면 {@code buyRatio = 0/ε = 0} 이 되어 그 봉의 거래량
 *       <b>전부가 매도</b>로 계산됐다. 움직이지 않은 봉이 최대 매도 압력이 된다.</li>
 * </ul>
 *
 * <p>같은 파일의 {@code stochasticKSeries} 는 range==0 을 이미 50(중립)으로 처리한다 —
 * 코드베이스가 이미 "무변동 = 중립" 에 합의해 놓고 이 두 곳만 빠져 있었다.
 *
 * <p>저유동 신규 상장이 워치리스트에 계속 들어오는 이 시스템에서 무변동 봉은 드문 일이 아니다.
 */
@DisplayName("무변동 구간 경계")
class ZeroMovementBoundaryTest {

    private static final long H1 = 3600L;
    private static final long BASE = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();

    private static Candle flat(int i, double price, double volume) {
        BigDecimal p = BigDecimal.valueOf(price);
        return Candle.builder()
                .time(Instant.ofEpochSecond(BASE + i * H1))
                .open(p).high(p).low(p).close(p)
                .volume(BigDecimal.valueOf(volume))
                .build();
    }

    private static List<Candle> flatSeries(int n) {
        List<Candle> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(flat(i, 100, 50));
        return l;
    }

    @Test
    @DisplayName("RSI — 완전 횡보는 100 이 아니라 50 이다")
    void rsi_flatSeries_isNeutral() {
        List<BigDecimal> closes = new ArrayList<>();
        for (int i = 0; i < 60; i++) closes.add(BigDecimal.valueOf(100));

        List<BigDecimal> rsi = IndicatorUtils.rsiSeries(closes, 14);

        assertThat(rsi).isNotEmpty();
        assertThat(rsi)
                .as("상승분도 하락분도 0 이면 방향이 없다 — 최고 과매수가 아니다")
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo(BigDecimal.valueOf(50)));
    }

    @Test
    @DisplayName("RSI — 하락이 전혀 없고 상승만 있으면 여전히 100 이다")
    void rsi_onlyGains_stays100() {
        List<BigDecimal> closes = new ArrayList<>();
        for (int i = 0; i < 60; i++) closes.add(BigDecimal.valueOf(100 + i));

        List<BigDecimal> rsi = IndicatorUtils.rsiSeries(closes, 14);

        assertThat(rsi.get(rsi.size() - 1))
                .as("무변동 처리가 정상적인 100 까지 삼키면 안 된다")
                .isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("RSI — RsiStrategy 의 자체 구현도 같아야 한다 (같은 결함이 복제돼 있었다)")
    void rsiStrategy_flatSeries_isNeutral() {
        // RSI 50 은 과매수(70)도 과매도(30)도 아니므로 HOLD 여야 한다.
        StrategySignal s = new RsiStrategy().evaluate(flatSeries(60), Map.of());

        assertThat(s.getAction())
                .as("완전 횡보에서 RSI=100 으로 읽으면 과매수 매도가 나온다. 사유=%s", s.getReason())
                .isEqualTo(StrategySignal.Action.HOLD);
    }

    @Test
    @DisplayName("VolumeDelta — high==low 봉은 매도가 아니라 방향 없음으로 센다")
    void volumeDelta_flatCandles_countAsNeutral() {
        // 완전 횡보에서도 신호는 HOLD 로 나온다 — 하지만 그건 **우연이다.**
        // 모든 봉의 Delta 가 같아 추세 필터(secondAvg < firstAvg)가 SELL 을 막았을 뿐,
        // 비율 자체는 -1.0000(최대 매도 압력)이었다. 사유 문자열이 그 값을 드러낸다.
        StrategySignal s = new VolumeDeltaStrategy().evaluate(flatSeries(60), Map.of());

        assertThat(s.getReason())
                .as("움직이지 않은 봉은 매수도 매도도 아니다 — 비율이 0 이어야 한다")
                .contains("ratio=0.0000");
    }

    @Test
    @DisplayName("VolumeDelta — 오른 뒤 완전히 멎은 시장을 강한 매도로 읽으면 안 된다")
    void volumeDelta_rallyThenStillness_isNotSell() {
        // lookback 기본값이 20 이므로 **마지막 20봉** 안에서 시나리오를 만든다.
        //   창 = 무변동 1 + 매수우위 5(종가=고가) + 무변동 14
        //   창 첫 종가 == 창 끝 종가(110) 라 다이버전스 필터가 개입하지 않는다.
        //
        // 고치기 전: 무변동 15봉이 전부 매도로 계산 → ratio=-0.5 → Delta 약화 → SELL.
        // 고친 뒤:  무변동은 0 → ratio=+0.25 → 매도가 아니다.
        List<Candle> l = new ArrayList<>();
        for (int i = 0; i < 21; i++) l.add(flat(i, 110, 50));
        for (int i = 21; i < 26; i++) {
            l.add(Candle.builder()
                    .time(Instant.ofEpochSecond(BASE + i * H1))
                    .open(BigDecimal.valueOf(100)).high(BigDecimal.valueOf(110))
                    .low(BigDecimal.valueOf(100)).close(BigDecimal.valueOf(110))
                    .volume(BigDecimal.valueOf(50))
                    .build());
        }
        for (int i = 26; i < 40; i++) l.add(flat(i, 110, 50));

        StrategySignal s = new VolumeDeltaStrategy().evaluate(l, Map.of());

        assertThat(s.getAction())
                .as("거래가 멎은 것은 팔린 것이 아니다. 사유=%s", s.getReason())
                .isNotEqualTo(StrategySignal.Action.SELL);
    }

    @Test
    @DisplayName("VolumeDelta — 종가가 고가면 여전히 매수 우위다")
    void volumeDelta_closeAtHigh_stillBullish() {
        // 무변동 처리가 정상 봉의 계산을 건드리지 않는지 고정한다.
        List<Candle> l = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            l.add(Candle.builder()
                    .time(Instant.ofEpochSecond(BASE + i * H1))
                    .open(BigDecimal.valueOf(100)).high(BigDecimal.valueOf(110))
                    .low(BigDecimal.valueOf(100)).close(BigDecimal.valueOf(110))
                    .volume(BigDecimal.valueOf(50))
                    .build());
        }
        StrategySignal s = new VolumeDeltaStrategy().evaluate(l, Map.of());

        assertThat(s.getReason()).as("사유=%s", s.getReason()).isNotNull();
        assertThat(s.getAction()).isNotEqualTo(StrategySignal.Action.SELL);
    }
}
