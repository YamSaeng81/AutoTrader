package com.cryptoautotrader.core.selector;

import java.util.Set;

/**
 * RANGE 레짐 게이트 — 횡보장에서 추세 추종 전략의 신규 BUY 진입을 차단한다.
 *
 * <h3>차단 대상</h3>
 * ATR 돌파·모멘텀·멀티타임프레임 등 추세 추종 계열 전략.
 * 횡보장(ADX&lt;20)에서 이 전략들은 오탐이 급증하며 손실을 유발한다.
 * (근거: 30일 신호품질 분석 2026-06-30 — RANGE 레짐 승률 18.8%, -10,981원)
 *
 * <h3>차단 예외</h3>
 * <ul>
 *   <li>{@code COMPOSITE_REGIME_ROUTER} — 내부에서 RANGE 시 자동 HOLD 처리</li>
 *   <li>횡보 친화 전략: VWAP, BOLLINGER, GRID, RSI, ORDERBOOK_IMBALANCE, STOCHASTIC_RSI</li>
 * </ul>
 *
 * <h3>SELL 신호 처리</h3>
 * SELL은 차단하지 않는다 — 기존 포지션 청산은 레짐과 무관하게 허용해야 한다.
 * (SL/TP는 별도 경로로 동작하므로 이 게이트 영향 없음)
 */
public final class RangeRegimeGate {

    private static final Set<String> RANGE_BLOCKED = Set.of(
            // 단일 추세 지표
            "ATR_BREAKOUT", "EMA_CROSS", "MACD", "SUPERTREND",
            // 복합 추세/돌파 전략
            "COMPOSITE", "COMPOSITE_BREAKOUT", "COMPOSITE_BREAKOUT_ICHIMOKU",
            "COMPOSITE_MOMENTUM", "COMPOSITE_ETH",
            "COMPOSITE_MOMENTUM_ICHIMOKU", "COMPOSITE_MOMENTUM_ICHIMOKU_V2",
            // 멀티타임프레임
            "COMPOSITE_MTF_BTC", "COMPOSITE_MTF_BTC_STRICT",
            "COMPOSITE_MTF_MOMENTUM", "COMPOSITE_MTF_CONFIRMED",
            "COMPOSITE_PULLBACK_MTF",
            // 헤이킨아시 추세 전략
            "HEIKIN_ASHI_STOCH"
    );

    private RangeRegimeGate() {}

    /**
     * 해당 전략이 RANGE 레짐에서 BUY 진입을 차단해야 하는지 반환한다.
     *
     * @param strategyType 전략 타입 문자열
     * @return true이면 RANGE 레짐에서 BUY 차단
     */
    public static boolean isBlocked(String strategyType) {
        return RANGE_BLOCKED.contains(strategyType);
    }
}
