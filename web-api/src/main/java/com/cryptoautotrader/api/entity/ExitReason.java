package com.cryptoautotrader.api.entity;

import com.cryptoautotrader.core.risk.ExitRuleChecker.ExitType;

/**
 * 포지션이 <b>왜</b> 청산됐는가 (V73, 2026-08-19). 세 엔진이 공유하는 단일 어휘다.
 *
 * <p>이전에는 청산 사유가 {@code order.signal_reason} 자유 텍스트에만 있었고 손익률이
 * 문자열 안에 박혀 있어("시간 초과 청산 — 보유 259시간 ≥ 24시간 (pnl -1.87%)") 값이 전부
 * 유일했다. 대시 문자도 {@code —} 와 {@code --} 가 섞여 정규식조차 취약했다. 그 결과
 * <b>"손절 대 익절 대 시간초과 비율"</b> 이라는 가장 기본적인 질문에 답할 수 없었다.</p>
 *
 * <p>자유 텍스트 사유는 그대로 둔다 — 사람이 읽을 정보(구체적 가격·지표값)는 거기 있고,
 * 이 enum 은 <b>집계 가능한 축</b>을 따로 제공한다. 둘은 대체가 아니라 보완이다.</p>
 */
public enum ExitReason {

    /** 손절가 도달. 실시간(WS) 감시분과 틱 감시분을 구분하지 않는다 — 원인은 같다. */
    STOP_LOSS,

    /** 익절가 도달. */
    TAKE_PROFIT,

    /** 트레일링 스탑 래칫에 걸림. */
    TRAILING_STOP,

    /** 최대 보유시간 초과 — 손익과 무관한 강제 청산. */
    TIME_STOP,

    /** 전략이 SELL 신호를 냄 (SL/TP 와 무관한 정상 청산). */
    STRATEGY_SIGNAL,

    /** 급락 감지 등 시장 이상 상황 가드. */
    BLACK_SWAN,

    /** 세션 정지·비상정지·삭제 등 운영자 개입. 전략 성과로 집계하면 안 된다. */
    FORCED_STOP,

    /**
     * 분류되지 않은 청산. <b>이 값이 늘어나면 어딘가 사유를 안 넘기고 있다는 뜻</b>이므로
     * 0에 가깝게 유지되어야 한다.
     */
    UNKNOWN;

    /**
     * {@link ExitType} 을 옮긴다. core-engine 은 SL/TP 만 알고 나머지(시간초과·전략신호·
     * 강제정지)는 호출부만 아는 정보라, 그쪽은 각 청산 지점에서 직접 지정한다.
     */
    public static ExitReason from(ExitType type) {
        if (type == null) return UNKNOWN;
        return switch (type) {
            case STOP_LOSS   -> STOP_LOSS;
            case TAKE_PROFIT -> TAKE_PROFIT;
            case NONE        -> UNKNOWN;
        };
    }

    /**
     * 전략 성과 집계에 포함해도 되는가. {@link #FORCED_STOP} 은 운영자가 끊은 것이라
     * 청산가가 시장이 아니라 개입 시각으로 정해진다 — 성과로 세면 전략을 잘못 평가한다.
     *
     * <p>2026-08-18 일괄 정리에서 두 포지션이 <b>같은 초에</b> 청산됐고, 그 손익이
     * 전략 성과처럼 집계되고 있었다. 이 구분은 그 오독을 막기 위한 것이다.</p>
     */
    public boolean countsTowardStrategyPerformance() {
        return this != FORCED_STOP && this != UNKNOWN;
    }
}
