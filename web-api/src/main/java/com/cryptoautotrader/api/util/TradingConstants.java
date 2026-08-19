package com.cryptoautotrader.api.util;

import java.math.BigDecimal;

/**
 * 매매 관련 공통 상수 — <b>세 엔진이 반드시 같은 값을 써야 하는 것들.</b>
 *
 * <p>여기 없이 각 서비스에 복제해 두면 한 곳만 바뀌어도 아무도 모른다. 실제로 그랬다:
 * {@code CANDLE_LOOKBACK} 은 LIVE·DYNAMIC·PAPER·BacktestEngine 네 곳에 각각 500 으로 박혀 있었고,
 * PAPER 쪽 주석에는 "백테스트·실거래와 동일하게 맞춰야 한다" 는 <b>수동 동기화 지시</b>만 있었다.
 * {@code SLIPPAGE_PCT} 도 세 곳에 0.001 로 복제돼 있었다.</p>
 *
 * <p>정합성은 {@code EngineParityTest} 가 기계적으로 검증한다.</p>
 */
public final class TradingConstants {

    private TradingConstants() {}

    /**
     * 업비트 왕복 수수료 임계값 (매수 0.05% + 매도 0.05% = 0.10%).
     * 이 값을 초과해야 수수료 차감 후 실질 수익으로 판정한다.
     */
    public static final BigDecimal FEE_THRESHOLD = new BigDecimal("0.10");

    /**
     * 전략 평가에 쓰는 캔들 조회 개수.
     *
     * <p>백테스트({@code BacktestEngine.MAX_LOOKBACK})와 같아야 한다 — 다르면 같은 전략이
     * 백테스트와 실거래에서 다른 지표값을 보고 판단하게 되어 "백테스트로 검증하고 실전에 올린다"
     * 는 절차가 성립하지 않는다. EMA200 산출에 200개가 필요하므로 하한이 있다.</p>
     */
    public static final int CANDLE_LOOKBACK = 500;

    /**
     * 모의 체결 슬리피지 (<b>비율</b>, 0.001 = 0.1%) — 매수는 불리하게 높게, 매도는 낮게.
     *
     * <p>페이퍼가 캔들 종가에 정확히 체결되면 실거래에 없는 이점을 누려 성과가 부풀려진다.
     * 실측 슬리피지는 LIVE BTC 기준 0.1% 수준이었다.</p>
     *
     * <p>⚠️ {@code BacktestConfig.slippagePct} 는 같은 0.1% 를 <b>퍼센트 단위</b>(0.1)로 쓴다.
     * 단위가 달라 그대로 합치면 100배 오차가 나므로 통합하지 않았다 — 값을 바꿀 때 양쪽을 볼 것.</p>
     */
    public static final BigDecimal PAPER_SLIPPAGE_PCT = new BigDecimal("0.001");

    /**
     * 호가 스프레드 필터가 허용하는 최소 호가 단위(틱) 배수.
     *
     * <p>퍼센트 임계만으로는 저가 코인이 1틱만으로 기준을 넘어 구조적으로 배제된다
     * (2026-08-19 실측: 상위 30 중 23개 탈락, 감시 목록 1~2개로 붕괴).
     * 자세한 근거는 {@code WatchlistFilterService.passesSpreadFilter} javadoc 참조.</p>
     */
    public static final BigDecimal WATCHLIST_ALLOWED_SPREAD_TICKS = new BigDecimal("2");
}
