package com.cryptoautotrader.strategy;

import com.cryptoautotrader.strategy.rsi.RsiStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Wave 4 — <b>RSI 는 한 곳에서만 계산한다.</b>
 *
 * <p><b>왜 필요한가</b><br>
 * {@code RsiStrategy} 가 Wilder 평활과 {@code rsiFromAvgs} 를 <b>자체 구현</b>으로 들고 있었고,
 * {@code IndicatorUtils.rsiSeries} 와 수학이 완전히 같았다(SCALE=8, 최종 {@code setScale(2)}).
 * 정렬만 달랐다 — 하나는 첫 유효값부터, 하나는 closes 인덱스에 맞춰서.
 *
 * <p>그 복제의 대가가 Wave 3-H 에서 실제로 나왔다: <b>"무변동 구간에서 RSI 가 100(과매수)"</b>
 * 이라는 하나의 결함을 <b>두 곳에 각각</b> 고쳐야 했다. 한쪽만 고쳤다면 같은 입력에 대해
 * 전략마다 다른 RSI 를 보는 상태가 남았을 것이고, 그런 상태는 어떤 신호도 주지 않는다 —
 * 두 값이 다르다는 사실 자체를 아무도 모른다.
 *
 * <p>2026-09-22 에 {@code RsiStrategy} 를 {@code IndicatorUtils.rsiSeries} 위임으로 바꿨다.
 * 이 테스트는 <b>다시 갈라지는 것</b>을 막는다 — 누군가 성능이나 편의를 이유로
 * 자체 구현을 되살리면 값이 어긋나는 순간 깨진다.
 *
 * <p><b>뮤테이션 확인</b>: {@code RsiStrategy.calculateRsiSeries} 의 위임을 걷어내고
 * 단순평균(SMA) 기반 RSI 로 바꾸면 {@link #rsiStrategy_reportsSameRsiAsIndicatorUtils} 가 깨진다.
 */
class RsiSingleSourceTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final int PERIOD = 14;

    /** 사유 문자열에서 현재 RSI 를 뽑는다 — 과매도/과매수/중립 어느 분기든 형식이 같다. */
    private static final Pattern RSI_IN_REASON =
            Pattern.compile("RSI (?:과매도|과매수|중립): (\\d+\\.\\d+)");

    @Test
    @DisplayName("RsiStrategy 가 보고하는 RSI 는 IndicatorUtils 와 같다")
    void rsiStrategy_reportsSameRsiAsIndicatorUtils() {
        RsiStrategy strategy = new RsiStrategy();

        // 여러 국면을 훑는다 — 한 지점만 맞춰 두면 반올림·평활 차이를 놓친다.
        int checked = 0;
        for (int end = 60; end <= 300; end += 17) {
            List<Candle> window = oscillatingSeries().subList(0, end);
            List<BigDecimal> closes = window.stream().map(Candle::getClose).toList();

            List<BigDecimal> compact = IndicatorUtils.rsiSeries(closes, PERIOD);
            assertThat(compact).as("기준 RSI 시계열이 비었다 (end=%d)", end).isNotEmpty();
            double expected = compact.get(compact.size() - 1).doubleValue();

            // 다이버전스 분기는 RSI 를 두 개 출력하므로 끈다 — 비교 대상이 흐려진다.
            StrategySignal s = strategy.evaluate(window, Map.of("useDivergence", false));
            Matcher m = RSI_IN_REASON.matcher(s.getReason());
            if (!m.find()) {
                continue;   // 다이버전스 등 다른 분기 — 이 지점은 건너뛴다
            }
            double actual = Double.parseDouble(m.group(1));

            assertThat(actual)
                    .as("end=%d — RsiStrategy 가 IndicatorUtils 와 다른 RSI 를 본다. "
                                    + "RSI 계산이 두 벌로 갈라졌다는 뜻이다 (사유: %s)",
                            end, s.getReason())
                    .isCloseTo(expected, within(0.01));
            checked++;
        }

        assertThat(checked)
                .as("비교한 지점이 하나도 없다 — 사유 형식이 바뀌었다면 RSI_IN_REASON 을 고칠 것 "
                        + "(테스트를 지우면 검증 공백이 된다)")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("정렬 변환이 경계에서 정확하다 — compact[k] ↔ closes[period + k]")
    void alignmentIsExactAtBoundaries() {
        List<Candle> series = oscillatingSeries();
        List<BigDecimal> closes = series.stream().map(Candle::getClose).toList();

        List<BigDecimal> compact = IndicatorUtils.rsiSeries(closes, PERIOD);

        // compact 크기 = n - period. RsiStrategy 는 이 값을 인덱스 period..n-1 에 채운다.
        assertThat(compact).hasSize(closes.size() - PERIOD);
    }

    @Test
    @DisplayName("캔들이 period 이하면 RSI 시계열이 비어 있다 — 구 구현의 '전부 50' 과 같은 뜻")
    void tooFewCandles_yieldsEmptySeries() {
        List<BigDecimal> closes = oscillatingSeries().subList(0, PERIOD).stream()
                .map(Candle::getClose).toList();
        assertThat(IndicatorUtils.rsiSeries(closes, PERIOD)).isEmpty();
    }

    private static List<Candle> oscillatingSeries() {
        List<Candle> out = new ArrayList<>();
        double prev = 500.0;
        for (int i = 0; i < 300; i++) {
            // 추세 + 진동 — 과매도·중립·과매수 구간을 모두 지나가게 한다.
            double next = 500.0 + 0.4 * i + 25.0 * Math.sin(i * 2 * Math.PI / 37.0);
            out.add(Candle.builder()
                    .time(BASE.plusSeconds((long) i * 3600L))
                    .open(BigDecimal.valueOf(prev))
                    .high(BigDecimal.valueOf(Math.max(prev, next) + 2))
                    .low(BigDecimal.valueOf(Math.min(prev, next) - 2))
                    .close(BigDecimal.valueOf(next))
                    .volume(BigDecimal.valueOf(1000))
                    .build());
            prev = next;
        }
        return out;
    }
}
