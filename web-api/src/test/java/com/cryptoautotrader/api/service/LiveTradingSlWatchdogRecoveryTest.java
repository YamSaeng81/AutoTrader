package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-06 신규(P2) — §9 SL 미점검 워치독이 <b>경보만 보내던 것</b>에서
 * <b>그 코인 하나만 즉시 REST로 강제 복구를 시도</b>하도록 바꾼 변경의 회귀 테스트.
 *
 * <p>배경: {@code pollRestTickerFallback}의 전역 WS 폴백은 {@code isWsUnhealthy}(전역 틱
 * 신선도)로만 판단해, 다른 코인은 계속 틱이 오는데 <b>이 세션의 코인만</b> 조용히 구독이
 * 끊기면 발동하지 않는다. 그 사각지대에서 {@code warnStaleSlCheck}는 그동안 경보만 보내고
 * 사람이 조치할 때까지 SL 감시가 비어 있었다. 이제 {@link LiveTradingService#forceRefreshPrice}로
 * 즉시 그 코인만 REST 시세를 당겨와 {@code RealtimePriceEvent}를 발행한다(정상 WS 틱과
 * 동일 경로 — throttle·SL/TP 판정 로직을 그대로 탄다).</p>
 */
class LiveTradingSlWatchdogRecoveryTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @MockBean
    private UpbitRestClient upbitRestClient;

    @MockBean
    private TelegramNotificationService telegramService;

    @BeforeEach
    @AfterEach
    void cleanup() {
        positionRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    private LiveTradingSessionEntity newRunningSession() {
        LiveTradingSessionEntity s = LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .status("RUNNING")
                .maxHoldHours(0)
                .build();
        return sessionRepository.saveAndFlush(s);
    }

    private void openPosition(Long sessionId) {
        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair("KRW-BTC").side("BUY")
                .entryPrice(new BigDecimal("90000000")).avgPrice(new BigDecimal("90000000"))
                .size(new BigDecimal("0.00008889")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(sessionId).sessionKind("LIVE")
                .build());
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Instant> lastSlCheckAt() {
        return (Map<Long, Instant>) ReflectionTestUtils.getField(liveTradingService, "lastSlCheckAt");
    }

    // ── forceRefreshPrice 단위 동작 ──────────────────────────────────

    @Test
    @DisplayName("forceRefreshPrice: REST 시세 조회 성공 시 true 반환")
    void forceRefreshPrice_succeeds() throws Exception {
        when(upbitRestClient.getTicker("KRW-BTC")).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "trade_price", "91000000")));

        boolean result = liveTradingService.forceRefreshPrice("KRW-BTC");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("forceRefreshPrice: 거래소 API 오류 시 false 반환 (예외를 삼키고 다음 주기로 넘김)")
    void forceRefreshPrice_returnsFalse_onException() throws Exception {
        when(upbitRestClient.getTicker("KRW-BTC")).thenThrow(new RuntimeException("Upbit API timeout"));

        boolean result = liveTradingService.forceRefreshPrice("KRW-BTC");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("forceRefreshPrice: 응답이 비어있으면 false 반환")
    void forceRefreshPrice_returnsFalse_onEmptyResponse() throws Exception {
        when(upbitRestClient.getTicker("KRW-BTC")).thenReturn(List.of());

        boolean result = liveTradingService.forceRefreshPrice("KRW-BTC");

        assertThat(result).isFalse();
    }

    // ── warnStaleSlCheck 통합 — 알림 문구로 복구 성공/실패 구분 ──────────────

    @Test
    @DisplayName("warnStaleSlCheck: SL 미점검 감지 시 강제 복구를 시도하고 성공 문구를 보낸다")
    void warnStaleSlCheck_attemptsRecovery_andReportsSuccess() throws Exception {
        LiveTradingSessionEntity session = newRunningSession();
        openPosition(session.getId());
        lastSlCheckAt().put(session.getId(), Instant.now().minus(10, ChronoUnit.MINUTES));

        when(upbitRestClient.getTicker(anyString())).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "trade_price", "91000000")));

        liveTradingService.warnStaleSlCheck();

        verify(telegramService).sendCustomNotification(
                argThat(msg -> msg.contains("강제 갱신") && msg.contains("세션 " + session.getId())));
    }

    @Test
    @DisplayName("warnStaleSlCheck: 강제 복구 자체가 실패하면 사람 개입이 필요하다는 문구를 보낸다")
    void warnStaleSlCheck_reportsFailure_whenRecoveryFails() throws Exception {
        LiveTradingSessionEntity session = newRunningSession();
        openPosition(session.getId());
        lastSlCheckAt().put(session.getId(), Instant.now().minus(10, ChronoUnit.MINUTES));

        when(upbitRestClient.getTicker(anyString())).thenThrow(new RuntimeException("Upbit API timeout"));

        liveTradingService.warnStaleSlCheck();

        verify(telegramService).sendCustomNotification(
                argThat(msg -> msg.contains("자동 복구도 실패")));
    }

    @Test
    @DisplayName("warnStaleSlCheck: 방금 점검된 세션은 건드리지 않는다 (오탐 방지 회귀)")
    void warnStaleSlCheck_skipsFreshSession() {
        LiveTradingSessionEntity session = newRunningSession();
        openPosition(session.getId());
        lastSlCheckAt().put(session.getId(), Instant.now());

        liveTradingService.warnStaleSlCheck();

        verify(telegramService, org.mockito.Mockito.never()).sendCustomNotification(any());
    }
}
