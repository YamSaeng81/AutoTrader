package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 호가 단위(틱) 추론 검증 — 2026-08-19.
 *
 * <p><b>사고</b>: 스프레드 필터가 {@code (ask−bid)/ask ≤ 0.1%} 만 봐서 거래대금 상위 30개 중
 * 23~24개를 탈락시켰다. 동적 세션 4개의 감시 목록이 목표 10개 대비 1~2개로 붕괴해
 * 사실상 단일코인 매매가 되고 있었다.</p>
 *
 * <p>원인은 유동성이 아니라 업비트 호가 단위의 입자도였다 — 저가 코인은 1틱만으로 0.1% 를 넘는다.
 * 2026-08-19 실측(거래대금 상위 30):</p>
 * <pre>
 *   KRW-BTC   90,361,000원  6틱    0.0066%  통과  ← 6틱이나 벌어졌는데 통과
 *   KRW-RED          129원  1.4틱  0.7752%  탈락  ← 물리적으로 가장 좁은 호가인데 탈락
 *   KRW-XLM          217원  10틱   0.4608%  탈락  ← 이건 진짜로 넓어서 탈락이 맞다
 * </pre>
 *
 * <p>단순히 임계값을 0.5% 로 올렸다면 RED 를 버리고 XLM 을 받는 <b>정반대 판정</b>이 됐다.
 * 그래서 틱 상대 하한을 도입했다: {@code 허용 = max(maxSpreadPct, 2 × 1틱%)}.</p>
 */
class WatchlistSpreadFilterTest {

    private static Map<String, Object> unit(double ask, double bid) {
        return Map.of("ask_price", ask, "bid_price", bid);
    }

    /** 실측 재현: 허용 임계 = max(설정%, 2틱%) */
    private static boolean passes(double ask, double bid, List<?> units, String maxSpreadPct) {
        BigDecimal tick = WatchlistFilterService.inferTickSize(units);
        BigDecimal a = BigDecimal.valueOf(ask);
        BigDecimal spreadPct = a.subtract(BigDecimal.valueOf(bid))
                .divide(a, 8, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        BigDecimal max = new BigDecimal(maxSpreadPct);
        if (tick != null) {
            BigDecimal floor = tick.divide(a, 8, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).multiply(new BigDecimal("2"));
            if (floor.compareTo(max) > 0) max = floor;
        }
        return spreadPct.compareTo(max) <= 0;
    }

    @Test
    @DisplayName("틱 추론: 인접 호가 간 최소 양수 간격")
    void infersTickFromAdjacentLevels() {
        // 매도 217.0/217.1/217.3, 매수 216.9/216.8 → 최소 간격 0.1
        List<Map<String, Object>> units = List.of(
                unit(217.0, 216.9), unit(217.1, 216.8), unit(217.3, 216.6));

        assertThat(WatchlistFilterService.inferTickSize(units))
                .isEqualByComparingTo("0.1");
    }

    @Test
    @DisplayName("틱 추론: 호가 단계가 1개뿐이면 추론 불가 → null")
    void singleLevelCannotInferTick() {
        assertThat(WatchlistFilterService.inferTickSize(List.of(unit(100.0, 99.0)))).isNull();
    }

    @Test
    @DisplayName("틱 테이블을 하드코딩하지 않는다 — 실측에서 공개 표와 달랐다")
    void tickIsInferredNotAssumed() {
        // DOGE 98원의 실측 틱은 0.04원이었다. 공개 표(10~100원 → 0.1)와 다르다.
        List<Map<String, Object>> doge = List.of(
                unit(98.10, 98.06), unit(98.14, 98.02), unit(98.18, 97.98));

        assertThat(WatchlistFilterService.inferTickSize(doge)).isEqualByComparingTo("0.04");
    }

    @Test
    @DisplayName("저가·호가밀착 코인을 구제한다 — RED 129원 1틱(0.775%)")
    void lowPricedTightBookIsAccepted() {
        // 1틱 = 0.7원, 스프레드 1틱 → 0.5426% × ... 퍼센트로는 0.1% 를 한참 넘지만
        // 물리적으로 가능한 가장 좁은 호가다.
        List<Map<String, Object>> red = List.of(
                unit(129.0, 128.3), unit(129.7, 127.6), unit(130.4, 126.9));

        assertThat(passes(129.0, 128.3, red, "0.10"))
                .as("호가가 붙어 있는데 가격이 낮다는 이유로 버리면 유동성 상위 종목이 통째로 빠진다")
                .isTrue();
    }

    @Test
    @DisplayName("진짜로 넓은 호가는 저가여도 탈락한다 — XLM 217원 10틱(0.461%)")
    void genuinelyWideBookIsRejected() {
        // 틱 0.1, 스프레드 1.0원 = 10틱. 허용은 2틱까지.
        List<Map<String, Object>> xlm = List.of(
                unit(217.0, 216.0), unit(217.1, 215.9), unit(217.2, 215.8));

        assertThat(passes(217.0, 216.0, xlm, "0.10"))
                .as("틱 상대 기준이 무조건 통과시키는 완화 장치가 되면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("고가 코인은 퍼센트 기준이 그대로 유효하다 — BTC 6틱이어도 0.0066%")
    void highPricedCoinStillJudgedByPercent() {
        List<Map<String, Object>> btc = List.of(
                unit(90_361_000.0, 90_355_000.0),
                unit(90_362_000.0, 90_354_000.0),
                unit(90_363_000.0, 90_353_000.0));

        assertThat(passes(90_361_000.0, 90_355_000.0, btc, "0.10"))
                .as("6틱이나 벌어졌지만 비용으로는 0.0066% — 퍼센트가 옳은 척도다")
                .isTrue();
    }

    @Test
    @DisplayName("틱을 못 구하면 퍼센트 기준만 적용한다 (기존 동작 유지)")
    void fallsBackToPercentWhenTickUnknown() {
        List<Map<String, Object>> one = List.of(unit(1000.0, 995.0));   // 0.5%

        assertThat(WatchlistFilterService.inferTickSize(one)).isNull();
        assertThat(passes(1000.0, 995.0, one, "0.10")).isFalse();
        assertThat(passes(1000.0, 999.5, one, "0.10")).isTrue();        // 0.05%
    }
}
