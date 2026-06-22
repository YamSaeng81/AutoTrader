package com.cryptoautotrader.api.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 20260415_analy.md Tier 3 §11 — 전략 19종 중 실전 후보 3개.
 *
 * <p>전략별 실전 투입 가능 여부를 4단계로 명시화한다.
 * 기존 {@code LiveTradingService.BLOCKED_LIVE_STRATEGIES} 하드코딩을
 * 이 레지스트리로 흡수해 단일 진실 소스(Single Source of Truth)로 관리한다.</p>
 *
 * <ul>
 *   <li>{@code ENABLED}      — 백테스트·운영 근거가 충분해 실전 투입 가능</li>
 *   <li>{@code EXPERIMENTAL} — 구현은 됐지만 충분한 백테스트 근거가 없어 실전 미권장</li>
 *   <li>{@code BLOCKED}      — 백테스트 기준 구조적 손실 확인 → 실전 세션 생성 차단</li>
 *   <li>{@code DEPRECATED}   — 테스트·구버전 전략; 일반 운영 대상 아님</li>
 * </ul>
 */
@Component
public class StrategyLiveStatusRegistry {

    public enum LiveReadiness {
        ENABLED,
        EXPERIMENTAL,
        BLOCKED,
        DEPRECATED
    }

    public record StatusEntry(LiveReadiness readiness, String reason) {}

    /** 전략명 → 운영 가능 여부 매트릭스 (등록 안 된 전략 → EXPERIMENTAL 기본) */
    private static final Map<String, StatusEntry> MATRIX = Map.ofEntries(

        // ── ENABLED: 백테스트 + 운영 근거 충분 ──────────────────────────────────
        entry("COMPOSITE_BREAKOUT",
                LiveReadiness.ENABLED,
                "BTC +104.2% MDD-15%, ETH +38.9%. ADX·EMA 이중 필터. 3년 H1 검증."),
        entry("COMPOSITE_MOMENTUM",
                LiveReadiness.ENABLED,
                "ETH +53.6%, SOL +59.8%. EMA 방향 필터. 3년 H1 검증."),
        entry("COMPOSITE_MOMENTUM_ICHIMOKU",
                LiveReadiness.ENABLED,
                "ETH +46.0%, SOL +62.9%, XRP +36.5%. EMA+Ichimoku 이중 필터."),
        entry("COMPOSITE_MOMENTUM_ICHIMOKU_V2",
                LiveReadiness.ENABLED,
                "SOL +131.1% MDD -12%, DOGE +134.4%, XRP +49.9%. VWAP→SUPERTREND 교체로 방향 일치성 개선."),
        entry("COMPOSITE_REGIME_ROUTER",
                LiveReadiness.ENABLED,
                "SOL +65.4% (CB 근소 상회, 레짐 자동 적응). 2026-04-30 MTF 백테스트 Tier 1. SOL 운영 전략."),
        entry("COMPOSITE_MTF_BTC",
                LiveReadiness.ENABLED,
                "BTC/ETH 특화 H1+H4 확인. ETH +127.7% MDD -7.2%(7전략 1위), AAVE·CHZ 흑자 전환. 2026-04-30 Tier 1. ETH 운영 전략."),
        entry("COMPOSITE_MTF_MOMENTUM",
                LiveReadiness.ENABLED,
                "DOGE/ETH 특화 CMI_V2(H1)+Supertrend(H4). BLUR +48.1% Sharpe 0.91. 2026-04-30 Tier 1/2."),

        // ── BLOCKED: 백테스트 구조적 손실 확인 → 세션 생성 불가 ──────────────
        entry("STOCHASTIC_RSI",
                LiveReadiness.BLOCKED,
                "BTC 3년 H1 -70.4%/-67.6%. 과매수/과매도 역추세가 추세 구간에서 반복 역행."),
        entry("MACD",
                LiveReadiness.BLOCKED,
                "BTC 3년 H1 -58.8%/-57.6%. 단독 크로스 신호는 잡음 과다; COMPOSITE 컴포넌트로만 허용."),
        entry("MACD_STOCH_BB",
                LiveReadiness.BLOCKED,
                "3년 H1 BTC 17건 -2.32%, XRP 3건 -2.02%. 6조건 AND 과도 필터로 신호 극희소·수익성 없음."),

        // ── EXPERIMENTAL: 구현 완료, 단독 실전 근거 미충분 ──────────────────────
        entry("VWAP",
                LiveReadiness.EXPERIMENTAL,
                "역추세 단독 전략. 장기 추세 구간에서 지속 역행 위험. COMPOSITE 컴포넌트로만 검증됨."),
        entry("EMA_CROSS",
                LiveReadiness.EXPERIMENTAL,
                "골든/데드크로스 지연. 단독 실전 백테스트 부재. COMPOSITE 컴포넌트로만 검증됨."),
        entry("BOLLINGER",
                LiveReadiness.EXPERIMENTAL,
                "%B 평균회귀. 강세장 추세 구간에서 과도 SELL 위험. 단독 실전 근거 없음."),
        entry("GRID",
                LiveReadiness.EXPERIMENTAL,
                "횡보장 특화. 추세 구간 대손실 위험. 단독 실전 근거 없음. COMPOSITE 컴포넌트로만 검증됨."),
        entry("RSI",
                LiveReadiness.EXPERIMENTAL,
                "과매수/과매도 역추세. 단독 실전 미검증. COMPOSITE 컴포넌트로만 검증됨."),
        entry("SUPERTREND",
                LiveReadiness.EXPERIMENTAL,
                "ATR 기반 동적 지지/저항. 단독 실전 미검증. COMPOSITE 컴포넌트로만 검증됨."),
        entry("ATR_BREAKOUT",
                LiveReadiness.EXPERIMENTAL,
                "변동성 돌파. 단독 실전 미검증. COMPOSITE 컴포넌트로만 검증됨."),
        entry("ORDERBOOK_IMBALANCE",
                LiveReadiness.EXPERIMENTAL,
                "호가 불균형. Phase 4 WebSocket 연동 필요. 단독 실전 미검증."),
        entry("VOLUME_DELTA",
                LiveReadiness.EXPERIMENTAL,
                "매수/매도 압력 다이버전스. 단독 실전 미검증. COMPOSITE 컴포넌트로만 검증됨."),
        entry("FAIR_VALUE_GAP",
                LiveReadiness.EXPERIMENTAL,
                "A단계(모멘텀 방식)만 구현. SOL 외 4코인 모두 열위. 실전 투입 전 추가 검증 필요."),
        entry("HEIKIN_ASHI_STOCH",
                LiveReadiness.EXPERIMENTAL,
                "하이키나시+200EMA+StochRSI, 고정 손익비 1:2. ~100일 H1 백테스트에서 BTC/ETH/SOL 흑자·XRP 소폭 손실(4코인 중 3흑자), " +
                "같은 구간 매수보유·EMA_CROSS 대비 우위. 단일 하락구간·소표본(8~15거래)으로 장기 검증 부족 → 실전 미권장."),
        entry("COMPOSITE",
                LiveReadiness.EXPERIMENTAL,
                "레짐 적응형. 레짐 분류기 정확도 미검증. 개별 레짐별 실전 근거 필요."),
        entry("COMPOSITE_ETH",
                LiveReadiness.EXPERIMENTAL,
                "구버전 ETH 프리셋. 평균 +48.7% 재검증 필요. ORDERBOOK_IMBALANCE 단독 구성 요소 실전 미검증."),
        entry("COMPOSITE_MTF_CONFIRMED",
                LiveReadiness.EXPERIMENTAL,
                "범용 CRR(H1)+Supertrend(H4). XRP +3.37%로 Tier 2(유일 흑자) 수준 — 소액 관찰 단계, ENABLED 근거 부족."),
        entry("COMPOSITE_BREAKOUT_ICHIMOKU",
                LiveReadiness.BLOCKED,
                "COMPOSITE_BREAKOUT의 ADX(14)<20 필터가 횡보장을 선차단하므로 Ichimoku 구름 필터가 추가로 막는 신호 없음. " +
                "백테스트 결과 COMPOSITE_BREAKOUT과 동일 → 신규 세션 생성 불가. 기존 세션은 만료 후 COMPOSITE_BREAKOUT으로 전환 권장."),

        // ── DEPRECATED: 테스트 전용 ──────────────────────────────────────────────
        entry("TEST_TIMED",
                LiveReadiness.DEPRECATED,
                "실전매매 동작 검증용 테스트 전략. 실제 시장 신호 없음.")
    );

    private static Map.Entry<String, StatusEntry> entry(String name, LiveReadiness r, String reason) {
        return Map.entry(name, new StatusEntry(r, reason));
    }

    // ── 공개 API ──────────────────────────────────────────────────────────────

    /** 전략의 실전 운영 단계를 반환한다. 등록되지 않은 전략은 EXPERIMENTAL 로 간주. */
    public LiveReadiness getReadiness(String strategyName) {
        return MATRIX.getOrDefault(strategyName,
                new StatusEntry(LiveReadiness.EXPERIMENTAL, "매트릭스 미등록 — 기본 EXPERIMENTAL")).readiness();
    }

    /** 전략의 차단 이유를 포함한 전체 항목을 반환한다. */
    public StatusEntry getStatus(String strategyName) {
        return MATRIX.getOrDefault(strategyName,
                new StatusEntry(LiveReadiness.EXPERIMENTAL, "매트릭스 미등록"));
    }

    /** 실전 세션 생성이 차단되는 전략인지 여부 */
    public boolean isBlocked(String strategyName) {
        return getReadiness(strategyName) == LiveReadiness.BLOCKED
                || getReadiness(strategyName) == LiveReadiness.DEPRECATED;
    }

    /** 전체 매트릭스 반환 (API 노출용) */
    public Map<String, StatusEntry> getAll() {
        return MATRIX;
    }
}
