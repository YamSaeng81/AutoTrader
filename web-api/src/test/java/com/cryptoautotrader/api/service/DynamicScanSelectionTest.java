package com.cryptoautotrader.api.service;

import com.cryptoautotrader.strategy.StrategySignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-07-02 종합분석 DM-1 — 동적 멀티코인 SCANNING 단계의 "최고 신호 강도 선택" 순수 로직 검증.
 *
 * <p>이전에는 워치리스트를 거래대금 내림차순으로 순회하다 첫 BUY 신호에서 즉시 진입해,
 * 실제로는 신호 품질이 아니라 "거래대금 순위"가 진입 코인을 결정하고 있었다.
 * {@link DynamicTradingService#pickBestBuyCandidate} 로 전체 평가 후 최고 confidence 선택으로 개선됨.
 * 네트워크·전략 평가·DB 없이 선택 알고리즘만 격리해서 검증한다.</p>
 */
class DynamicScanSelectionTest {

    private DynamicTradingService.BuyCandidate candidate(String coinPair, double strength) {
        StrategySignal signal = StrategySignal.buy(BigDecimal.valueOf(strength), "테스트 신호 " + coinPair);
        return new DynamicTradingService.BuyCandidate(coinPair, List.of(), signal, null);
    }

    @Test
    @DisplayName("여러 BUY 후보 중 신호 강도가 가장 높은 코인이 선택된다 (거래대금 순서 무관)")
    void picksHighestStrengthCandidate_regardlessOfWatchlistOrder() {
        // 워치리스트 순서(거래대금 내림차순)상으로는 BTC가 1순위지만 신호 강도는 SOL이 가장 높다.
        List<DynamicTradingService.BuyCandidate> candidates = List.of(
                candidate("KRW-BTC", 40.0),
                candidate("KRW-ETH", 55.0),
                candidate("KRW-SOL", 82.0),
                candidate("KRW-XRP", 60.0)
        );

        DynamicTradingService.BuyCandidate best = DynamicTradingService.pickBestBuyCandidate(candidates);

        assertThat(best.coinPair()).isEqualTo("KRW-SOL");
        assertThat(best.signal().getStrength()).isEqualByComparingTo("82.0");
    }

    @Test
    @DisplayName("후보가 1개면 그대로 선택된다")
    void singleCandidate_isSelected() {
        List<DynamicTradingService.BuyCandidate> candidates = List.of(candidate("KRW-BTC", 50.0));

        DynamicTradingService.BuyCandidate best = DynamicTradingService.pickBestBuyCandidate(candidates);

        assertThat(best.coinPair()).isEqualTo("KRW-BTC");
    }

    @Test
    @DisplayName("동률이면 워치리스트(리스트 순서)상 먼저 평가된 코인이 유지된다")
    void tie_keepsFirstEvaluated() {
        List<DynamicTradingService.BuyCandidate> candidates = List.of(
                candidate("KRW-BTC", 70.0),
                candidate("KRW-ETH", 70.0)
        );

        DynamicTradingService.BuyCandidate best = DynamicTradingService.pickBestBuyCandidate(candidates);

        assertThat(best.coinPair()).isEqualTo("KRW-BTC");
    }
}
