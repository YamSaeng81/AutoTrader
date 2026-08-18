package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.StrategyTypeEnabledRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code strategy_type_enabled} 기반 전략 활성 여부 게이트 — 2026-08-18 신설.
 *
 * <p><b>왜 별도 컴포넌트인가</b>: 이 검사는 원래 {@link DynamicTradingService#createSession}에만
 * 인라인으로 있었고 <b>LIVE({@link LiveTradingService})와 {@link PaperTradingService}는 검사하지
 * 않았다</b>. kill criteria(docs/KILL_CRITERIA.md §5)가 폐기 시 전략을 비활성화하는 이유는
 * "세션만 정지하면 같은 전략으로 새 세션을 만들어 그대로 재개할 수 있다"를 막기 위한 것인데,
 * 세 진입점 중 하나만 막혀 있으면 그 목적이 달성되지 않는다. 규칙을 한 곳에 모아 세 경로에
 * 동일하게 건다.
 *
 * <p><b>부재 = 활성</b>: 테이블에 행이 없으면 활성으로 본다({@code orElse(true)}).
 * 운영 DB에는 21개 행이 전부 {@code is_active=false}로 들어 있지만 현재 가동 중인
 * composite 전략 대부분은 아예 등재돼 있지 않다 — 즉 이 테이블은 "차단 목록"으로 동작하며,
 * 기본값을 false로 바꾸면 등재되지 않은 전략 전부가 즉시 막힌다. 기존
 * {@code StrategyController}·{@code DynamicTradingService}와 같은 규칙을 유지한다.
 */
@Component
@RequiredArgsConstructor
public class StrategyEnablementGate {

    private final StrategyTypeEnabledRepository strategyTypeEnabledRepository;

    /** 세션 생성이 허용되는 전략인지. 테이블에 없으면 허용. */
    public boolean isEnabled(String strategyType) {
        if (strategyType == null) return true;
        return strategyTypeEnabledRepository.findById(strategyType)
                .map(e -> Boolean.TRUE.equals(e.getIsActive()))
                .orElse(true);
    }

    /**
     * 비활성 전략이면 예외를 던진다. 세 세션 생성 경로(LIVE·DYNAMIC·PAPER) 공통.
     *
     * <p>PAPER 에도 거는 이유 — 페이퍼에서 죽은 전략을 페이퍼로 다시 돌릴 이유가 없고,
     * 부활 경로는 Walk Forward 재검증 하나뿐이다(문서 §6).</p>
     *
     * @throws IllegalArgumentException 비활성 전략인 경우
     */
    public void assertEnabled(String strategyType) {
        if (!isEnabled(strategyType)) {
            throw new IllegalArgumentException(String.format(
                    "전략 '%s'은(는) 비활성화되어 세션을 생성할 수 없습니다. "
                            + "폐기 기준으로 중단된 전략이라면 /backtest/walk-forward 재검증이 필요합니다.",
                    strategyType));
        }
    }
}
