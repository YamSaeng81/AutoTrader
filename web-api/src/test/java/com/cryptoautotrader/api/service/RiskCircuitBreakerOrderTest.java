package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * REAL/DYNAMIC 세션 서킷 브레이커의 조회·조기 반환 순서 (2026-11-11, 총괄 v1.147 R6 — {@code RiskReview949} 와 같은 입력).
 * af7ffa58 순서: 설정 → 비활성이면 조회 없이 통과 → MDD 초과면 조회 없이 발동 → 그 뒤에만 CLOSED 조회. Spring·DB 없음.
 */
@DisplayName("서킷 브레이커 순서 — 비활성·MDD 는 CLOSED 조회 0, 연속 손실 경로는 종전 결과")
class RiskCircuitBreakerOrderTest {

    private final RiskConfigEntity config = RiskConfigEntity.codeDefaults();
    private final RiskConfigRepository cr = mock(RiskConfigRepository.class);
    private final PositionRepository pr = mock(PositionRepository.class);
    private final RiskManagementService svc = new RiskManagementService(cr, pr, mock(TradeLogRepository.class),
            mock(LiveTradingSessionRepository.class),
            mock(com.cryptoautotrader.api.repository.OrderRepository.class));   // v1.155 §4 필수 원천 — 통합 적응(이 시험은 서킷 브레이커만 본다)

    private LiveTradingSessionEntity session(String total) {
        return LiveTradingSessionEntity.builder().id(1L).initialCapital(new BigDecimal("20200"))
                .mddPeakCapital(new BigDecimal("20200")).totalAssetKrw(new BigDecimal(total)).build();
    }

    @Test
    @DisplayName("비활성 — 조회 없이 통과 / MDD 초과 — 조회 없이 발동 (조회가 예외여도)")
    void earlyReturnsDoNotQueryClosedPositions() {
        when(cr.findTopByOrderByIdDesc()).thenReturn(Optional.of(config));
        when(pr.findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc("LIVE", 1L, "CLOSED"))
                .thenThrow(new IllegalStateException("review repository failure"));
        config.setCircuitBreakerEnabled(false);
        assertThat(svc.checkCircuitBreaker(session("20200")).isTriggered()).isFalse();
        config.setCircuitBreakerEnabled(true);
        var mdd = svc.checkCircuitBreaker(session("0"));
        assertThat(mdd.isTriggered()).isTrue();
        assertThat(mdd.getReason()).contains("MDD");
        verify(pr, never()).findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("정상 연속 손실 경로 — 조회 1회, 5연속 손실이면 발동, 이익이 끼면 끊긴다")
    void consecutiveLossPathUnchanged() {
        when(cr.findTopByOrderByIdDesc()).thenReturn(Optional.of(config));
        config.setCircuitBreakerEnabled(true);
        config.setConsecutiveLossLimit(5);
        PositionEntity loss = PositionEntity.builder().realizedPnl(new BigDecimal("-1")).closedAt(Instant.now()).build();
        PositionEntity gain = PositionEntity.builder().realizedPnl(new BigDecimal("1")).closedAt(Instant.now()).build();
        when(pr.findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc("LIVE", 1L, "CLOSED"))
                .thenReturn(Collections.nCopies(5, loss));
        assertThat(svc.checkCircuitBreaker(session("20200")).isTriggered()).isTrue();
        when(pr.findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc("LIVE", 1L, "CLOSED"))
                .thenReturn(List.of(loss, loss, gain, loss, loss, loss));
        assertThat(svc.checkCircuitBreaker(session("20200")).isTriggered()).isFalse();
        verify(pr, times(2)).findBySessionKindAndSessionIdAndStatusOrderByClosedAtDesc("LIVE", 1L, "CLOSED");
    }
}
