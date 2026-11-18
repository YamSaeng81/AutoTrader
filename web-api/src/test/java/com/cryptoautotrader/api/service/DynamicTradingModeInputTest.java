package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.DynamicSessionRequest;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.repository.RiskConfigRepository;
import com.cryptoautotrader.api.repository.TradeLogRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 총괄 v1.185 §3-3 — DYNAMIC 생성 요청의 매매 모드는 정확한 {@code REAL}/{@code PAPER} 두 값만 받는다.
 *
 * <p>0e1d2a02 에서는 {@code "PAPER".equals(mode)} 가 아니면 모두 실자금(REAL) 세션이 됐다 — 누락·null·빈 값·소문자
 * {@code paper}·오타가 모의 의도였어도 실자금 세션으로 저장되고 공유 풀 배정까지 받았다. 이 시험은 그런 입력이
 * <b>저장·계좌 동기화·자본 배정 전에</b> 거절되는지(행 0·배정 호출 0·계좌 동기화 0) 와 정상 두 값이 그대로
 * 동작하는지를 본다. 기존 행·재기동 복원(엔티티 기본값)은 이 시험의 범위가 아니다.</p>
 */
class DynamicTradingModeInputTest extends IntegrationTestBase {

    private static final String STRATEGY = "COMPOSITE_BREAKOUT"; // StrategyLiveStatusRegistry: ENABLED

    @Autowired private DynamicTradingService dynamicTradingService;
    @Autowired private DynamicSessionRepository dynamicSessionRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RiskConfigRepository riskConfigRepository;

    @MockBean private TradeLogRepository tradeLogRepository;
    @SpyBean private CapitalReservationService capitalReservationService;
    @SpyBean private PortfolioSyncService portfolioSyncService;

    @BeforeEach
    void setUp() {
        when(tradeLogRepository.sumRealizedPnlSince(any())).thenReturn(BigDecimal.ZERO);
        when(tradeLogRepository.sumRealizedLossSince(any())).thenReturn(BigDecimal.ZERO);
        cleanup();
        clearInvocations(capitalReservationService, portfolioSyncService);
    }

    @AfterEach
    void tearDown() { cleanup(); }

    private void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        dynamicSessionRepository.deleteAll();
        riskConfigRepository.deleteAll();
        riskConfigRepository.save(com.cryptoautotrader.api.entity.RiskConfigEntity.builder().build());
    }

    private static DynamicSessionRequest request(String mode) {
        DynamicSessionRequest req = new DynamicSessionRequest();
        req.setStrategyType(STRATEGY);
        req.setTimeframe("H1");
        req.setInitialCapital(new BigDecimal("10000"));
        req.setTradingMode(mode);
        return req;
    }

    @ParameterizedTest(name = "[{index}] tradingMode=''{0}''")
    @NullSource
    @ValueSource(strings = {"", " ", "paper", "Paper", "real", "Real", "PAPPER", "REAL ", " PAPER", "LIVE"})
    @DisplayName("서비스 직접 호출: 정확한 REAL/PAPER 가 아니면 저장·계좌 동기화·배정 전에 거절한다")
    void rejectsInvalidModeBeforeAnySideEffect(String mode) {
        assertThatThrownBy(() -> dynamicTradingService.createSession(request(mode)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tradingMode");

        assertThat(dynamicSessionRepository.count()).as("생성 행 0").isZero();
        verifyNoInteractions(capitalReservationService);   // 배정 검사 0
        verifyNoInteractions(portfolioSyncService);        // 거래소 계좌 동기화 0
    }

    @Test
    @DisplayName("정확한 PAPER 는 모의 세션으로 생성되고 실자금 배정·계좌 동기화를 거치지 않는다")
    void exactPaperCreatesPaper() {
        var s = dynamicTradingService.createSession(request("PAPER"));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getTradingMode()).isEqualTo("PAPER");
        assertThat(s.getStatus()).isEqualTo("CREATED");
        verifyNoInteractions(capitalReservationService);
        verifyNoInteractions(portfolioSyncService);
    }

    @Test
    @DisplayName("정확한 REAL 은 실자금 세션으로 생성되고 기존대로 배정 검사를 지난다")
    void exactRealCreatesRealWithAllocationCheck() {
        var s = dynamicTradingService.createSession(request("REAL"));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getTradingMode()).isEqualTo("REAL");
        assertThat(s.getStatus()).isEqualTo("CREATED");
        verify(capitalReservationService, atLeastOnce())
                .checkSessionAllocation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("API: 모드 누락 요청은 기존 오류 형식(INVALID_REQUEST)으로 거절되고 행이 생기지 않는다")
    void apiRejectsMissingMode() throws Exception {
        String body = "{\"strategyType\":\"" + STRATEGY + "\",\"timeframe\":\"H1\",\"initialCapital\":10000}";

        mockMvc.perform(post("/api/v1/dynamic-sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(dynamicSessionRepository.count()).isZero();
        verifyNoInteractions(capitalReservationService);
        verifyNoInteractions(portfolioSyncService);
    }

    @Test
    @DisplayName("API: 소문자 paper 는 실자금 세션이 되지 않고 거절된다")
    void apiRejectsLowercasePaper() throws Exception {
        String body = "{\"strategyType\":\"" + STRATEGY + "\",\"timeframe\":\"H1\",\"initialCapital\":10000,"
                + "\"tradingMode\":\"paper\"}";

        mockMvc.perform(post("/api/v1/dynamic-sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        assertThat(dynamicSessionRepository.count()).isZero();
    }
}
