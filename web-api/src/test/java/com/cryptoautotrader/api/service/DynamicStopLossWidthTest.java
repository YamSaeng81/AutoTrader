package com.cryptoautotrader.api.service;

import com.cryptoautotrader.strategy.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-31 손절폭 ATR 기반 개편 회귀 테스트.
 *
 * <p>개편 전에는 세션 고정 {@code stopLossPct}(5%)를 그대로 SL로 썼고, 블랙스완 발동 시에는
 * 현재가 기준 1×ATR로 오히려 <b>좁혔다</b>. 운영 실측(07-29~31) 결과 청산 6건이 <b>전부</b>
 * SL 강제청산이었고(전략 SELL 청산 0건), 실현률이 SL 폭보다 수수료(0.07~0.24%p)만큼만
 * 나빴다 — 손실을 만든 것은 전략이 아니라 청산 규칙이었다. 같은 신호들의 사후 4h 수익률은
 * 평균 −0.17%(KAITO는 +1.23%)로 거의 중립이었다 = 휩쏘.</p>
 *
 * <p>개편 후: SL 폭 = {@code clamp(ATR(14)/가격 × 2, 세션 stopLossPct, 12%)}.
 * 세션 설정값은 상한이 아니라 <b>하한</b>이다. 이 테스트는 "고변동 종목일수록 SL이 넓어진다"는
 * 핵심 성질을 잠근다.</p>
 */
class DynamicStopLossWidthTest {

    /**
     * 지정한 진폭(고가-저가 비율)으로 일정한 캔들을 만든다.
     * 종가를 고정하고 폭만 바꾸면 ATR%만 달라지므로 SL 폭 비교가 깔끔하다.
     */
    private List<Candle> candlesWithRange(BigDecimal close, double rangePct, int count) {
        List<Candle> candles = new ArrayList<>();
        Instant t = Instant.now().minus(count, ChronoUnit.HOURS);
        BigDecimal half = close.multiply(BigDecimal.valueOf(rangePct / 200.0));
        for (int i = 0; i < count; i++) {
            candles.add(Candle.builder()
                    .time(t.plus(i, ChronoUnit.HOURS))
                    .open(close)
                    .high(close.add(half))
                    .low(close.subtract(half))
                    .close(close)
                    .volume(BigDecimal.valueOf(1000))
                    .build());
        }
        return candles;
    }

    @Test
    @DisplayName("고변동 종목일수록 SL이 넓어진다 — 세션 설정값은 하한으로만 작동")
    void highVolatility_widensStopLoss() {
        BigDecimal price = new BigDecimal("1000");
        BigDecimal floor = new BigDecimal("5.0");   // 세션 stopLossPct

        // 캔들 진폭 6% → ATR ≈ 6% → SL = 2 × 6% = 12% (상한에 걸림)
        BigDecimal wide = DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 6.0, 40), price);
        // 캔들 진폭 3% → ATR ≈ 3% → SL = 2 × 3% = 6%
        BigDecimal mid = DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 3.0, 40), price);
        // 캔들 진폭 0.5% → ATR ≈ 0.5% → 2 × 0.5% = 1% < 하한 5% → 5%
        BigDecimal narrow = DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 0.5, 40), price);

        assertThat(wide).as("고변동 종목의 SL이 가장 넓다")
                .isGreaterThan(mid);
        assertThat(mid).as("중변동 종목의 SL이 저변동보다 넓다")
                .isGreaterThan(narrow);

        // 핵심 회귀 지점: 개편 전이라면 셋 다 5%로 동일했다.
        assertThat(narrow).as("저변동은 세션 설정값(하한)으로 떨어진다")
                .isEqualByComparingTo(floor);
        assertThat(mid).as("ATR 2배가 하한을 넘으면 그 값이 채택된다")
                .isGreaterThan(floor);
    }

    @Test
    @DisplayName("SL 폭은 절대 세션 설정값보다 좁아지지 않는다 (휩쏘 방지 하한)")
    void neverNarrowerThanSessionFloor() {
        BigDecimal price = new BigDecimal("1000");
        BigDecimal floor = new BigDecimal("5.0");

        // 변동성이 거의 없는 종목(스테이블코인 등)도 하한 미만으로 좁아지면 안 된다
        BigDecimal sl = DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 0.05, 40), price);

        assertThat(sl).isGreaterThanOrEqualTo(floor);
    }

    @Test
    @DisplayName("SL 폭에 상한이 있다 — 비정상 ATR로 손실이 무한정 커지지 않는다")
    void clampedByUpperBound() {
        BigDecimal price = new BigDecimal("1000");

        // 진폭 50%짜리 비정상 캔들 → 2×ATR = 100% 지만 상한 12%로 잘린다
        BigDecimal sl = DynamicTradingService.resolveStopLossPct(
                new BigDecimal("5.0"), candlesWithRange(price, 50.0, 40), price);

        assertThat(sl).isEqualByComparingTo(new BigDecimal("12.0"));
    }

    @Test
    @DisplayName("ATR 계산 불가(캔들 부족)면 세션 설정값으로 폴백 — 진입을 막지 않는다")
    void fallsBackToSessionFloor_whenAtrUncomputable() {
        BigDecimal price = new BigDecimal("1000");
        BigDecimal floor = new BigDecimal("5.0");

        assertThat(DynamicTradingService.resolveStopLossPct(floor, List.of(), price))
                .as("빈 캔들")
                .isEqualByComparingTo(floor);
        assertThat(DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 3.0, 5), price))
                .as("ATR(14) 계산에 부족한 5개")
                .isEqualByComparingTo(floor);
        assertThat(DynamicTradingService.resolveStopLossPct(floor, null, price))
                .as("캔들 null")
                .isEqualByComparingTo(floor);
        assertThat(DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 3.0, 40), BigDecimal.ZERO))
                .as("현재가 0 — 0으로 나누기 방지")
                .isEqualByComparingTo(floor);
    }

    @Test
    @DisplayName("하한을 넘는 구간에서 SL 폭은 정확히 ATR의 2배다")
    void slWidthEqualsTwoAtr_aboveFloor() {
        // 핵심 불변식: ATR 2배가 하한보다 크면 그 값이 그대로 SL 폭이 된다.
        // 진폭이 일정한 캔들에서는 ATR ≈ 진폭이므로 SL ≈ 진폭 × 2 가 된다.
        BigDecimal price = new BigDecimal("1823");   // 2026-07-30 KAITO 실제 진입가
        BigDecimal floor = new BigDecimal("5.0");

        BigDecimal sl = DynamicTradingService.resolveStopLossPct(
                floor, candlesWithRange(price, 4.0, 40), price);

        // ATR 4% → SL 8% (허용오차는 ATR 워밍업으로 인한 근사 때문)
        assertThat(sl.doubleValue()).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.5));

        // 개편 전이라면 이 종목도 고정 5%였고, 블랙스완 조임이 걸리면 3%대까지 좁아졌다
        // (pos 2368 KAITO SL -3.54% / pos 2375 EDGE -2.96% — 둘 다 강제청산).
        // ⚠️ 조임 로직 제거 자체는 processMonitoringTick 안에 있어 이 단위 테스트가 잠그지 못한다.
        //    여기서 잠그는 것은 "진입 시점 SL이 변동성에 비례한다"는 성질뿐이다.
        assertThat(sl).isGreaterThan(floor);
    }
}
