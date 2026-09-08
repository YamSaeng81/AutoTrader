package com.cryptoautotrader.api.util;

import java.math.BigDecimal;
import java.util.List;

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

    // ── 2026-09-08 통합: 세 엔진에 복제돼 있던 것들 ──────────────────────────
    //
    // EngineConstantParityTest 가 "두 엔진 이상에 같은 이름의 상수가 있으면 값이 같아야 한다"를
    // 리플렉션으로 감사하다 드러난 중복이다. 값은 전부 일치했지만, 검사로 드리프트를 **잡는** 것보다
    // 한 곳에만 둬서 드리프트가 **불가능하게** 하는 쪽이 낫다.

    /**
     * 거래소(Upbit) 편도 수수료율 — 0.05%.
     *
     * <p>LIVE·DYNAMIC·PAPER 세 서비스와 {@code DynamicSessionController} 까지 네 곳에
     * 복제돼 있었다. 수수료는 모든 실현손익에 곱해지므로, 한 곳만 갈리면 그 엔진의 성적 전체가
     * 조용히 어긋난다 — 페이퍼로 실전을 예측한다는 전제가 깨지는 가장 직접적인 경로다.</p>
     *
     * <p>왕복 기준 임계는 위 {@link #FEE_THRESHOLD}(0.10%)를 쓴다.</p>
     */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.0005");

    /**
     * 아직 체결이 끝나지 않아 정리 대상으로 봐야 하는 주문 상태.
     *
     * <p>여기서 상태 하나가 빠지면 그 주문은 미체결인 채로 조회에서 사라져 유령 포지션이 된다.
     * LIVE·DYNAMIC 이 각자 목록을 들고 있었다.</p>
     */
    public static final List<String> ACTIVE_ORDER_STATES =
            List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED");

    /**
     * CLOSING 상태 체류 한도(분) — 초과하면 reconciler 가 OPEN 으로 롤백한다.
     *
     * <p>{@code OrderExecutionEngine.ORDER_TIMEOUT}(5분)보다 <b>반드시 길어야 한다</b> —
     * 짧으면 아직 살아 있는 매도 주문을 두고 포지션을 OPEN 으로 되돌려 중복 매도가 난다
     * (2026-07-02 감사 D-5).</p>
     */
    public static final long CLOSING_TIMEOUT_MINUTES = 8;

    /** SL 점검이 이 시간(분) 이상 끊기면 경보 — 감시 경로가 죽은 것을 조용히 넘기지 않는다. */
    public static final long SL_STALE_WARN_MINUTES = 3;
}
