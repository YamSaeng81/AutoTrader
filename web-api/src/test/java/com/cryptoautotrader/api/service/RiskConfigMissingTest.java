package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.RiskConfigEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/** risk_config 행이 없을 때 (2026-10-14) — 읽기 경로는 쓰지 않고, 신규 진입은 막고, 청산 규칙은 코드 기본값으로. */
class RiskConfigMissingTest {

    private final RiskConfigRepository repo = mock(RiskConfigRepository.class);
    private final RiskManagementService service = new RiskManagementService(repo, mock(PositionRepository.class),
            mock(TradeLogRepository.class), mock(LiveTradingSessionRepository.class));

    @Test
    void 행이_없으면_INSERT_하지_않고_코드_기본값을_돌려준다() {
        when(repo.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        RiskConfigEntity cfg = service.getRiskConfig();
        verify(repo, never()).save(any());
        assertThat(cfg.getId()).isNull();
        assertThat(cfg.getMaxDailyLossPct()).isEqualByComparingTo("1.5");
        assertThat(cfg.getInvestRatioPct()).isEqualByComparingTo("25.0");
        assertThat(service.getExitRuleConfig()).as("청산 규칙은 계속 만든다").isNotNull();
    }

    @Test
    void 행이_없으면_신규_진입_위험_판단을_거부한다() {
        when(repo.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        assertThat(service.hasRiskConfig()).isFalse();
        assertThat(service.checkRisk().isApproved()).isFalse();
        assertThat(service.checkRisk().getReason()).contains("risk_config");
    }

    @Test
    void 코드_기본값은_V88_기본_행과_같다() {
        RiskConfigEntity d = RiskConfigEntity.codeDefaults();
        assertThat(d.getMaxDailyLossPct()).isEqualByComparingTo("1.5");
        assertThat(d.getMaxWeeklyLossPct()).isEqualByComparingTo("4.0");
        assertThat(d.getMaxMonthlyLossPct()).isEqualByComparingTo("8.0");
        assertThat(d.getMaxPositions()).isEqualTo(8);
        assertThat(d.getMaxCapitalUtilizationPct()).isEqualByComparingTo("50.0");
        assertThat(d.getCooldownMinutes()).isEqualTo(60);
        assertThat(d.getMaxPortfolioDrawdownPct()).isEqualByComparingTo("15.0");
        assertThat(d.getMddThresholdPct()).isEqualByComparingTo("10.0");
        assertThat(d.getConsecutiveLossLimit()).isEqualTo(5);
        assertThat(d.getCircuitBreakerEnabled()).isTrue();
        assertThat(d.getStopLossPct()).isEqualByComparingTo("5.0");
        assertThat(d.getTakeProfitMultiplier()).isEqualByComparingTo("2.0");
        assertThat(d.getTrailingEnabled()).isTrue();
        assertThat(d.getTrailingTpMarginPct()).isEqualByComparingTo("0.5");
        assertThat(d.getTrailingSlMarginPct()).isEqualByComparingTo("0.3");
        assertThat(d.getInvestRatioPct()).isEqualByComparingTo(new BigDecimal("25.0"));
    }
}
