package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-09-01 — <b>"SL 미점검 3분 초과" 텔레그램이 끝없이 오는 문제.</b>
 *
 * <h3>두 가지가 겹쳐 있었다</h3>
 * <ol>
 *   <li><b>대상 선정이 LIVE 와 달랐다.</b> LIVE 워치독은 "OPEN 포지션이 실제로 있는 세션"만
 *       보는데 DYNAMIC 은 {@code scanState == "POSITION_MONITORING"} 이라는 <b>세션 필드</b>만
 *       봤다. 상태 필드와 실제 포지션이 어긋나면(08-25 세션 60) 실시간 핸들러는 감시할 포지션을
 *       못 찾아 점검 시각을 기록하지 못하고, 워치독은 그걸 미점검으로 읽어 <b>60초마다 영원히</b>
 *       경고했다. 그러면서 알림 문구는 매번 "감시를 재개했습니다"라고 <b>사실이 아닌 말</b>을 했다
 *       — REST 시세 조회는 성공하니까.</li>
 *   <li><b>자가복구 성공까지 알렸다.</b> 이 워치독은 미점검을 발견하면 REST 로 시세를 한 번
 *       끌어와 스스로 복구한다. 거래가 뜸한 알트코인은 WS 틱이 3분 넘게 안 오는 게 정상이라,
 *       그때마다 "이상 → 복구" 알림이 반복됐다. <b>상시 울리는 알림은 아무도 안 본다</b> —
 *       진짜 고장이 왔을 때 구별할 방법이 사라진다.</li>
 * </ol>
 *
 * <p>고친 뒤의 규칙: <b>자가복구가 실패했을 때만</b> 사람을 부른다.</p>
 */
class DynamicSlWatchdogNoiseTest extends IntegrationTestBase {

    private static final String COIN = "KRW-XRP";

    @Autowired private DynamicTradingService service;
    @Autowired private DynamicSessionRepository sessionRepo;
    @Autowired private PositionRepository positionRepository;

    @MockBean private TelegramNotificationService telegramService;
    @MockBean private UpbitRestClient upbitRestClient;

    @BeforeEach
    @AfterEach
    void cleanup() {
        positionRepository.deleteAll();
        sessionRepo.deleteAll();
    }

    /** REST 강제 갱신이 성공하는 상태 — 정상적인 거래소. */
    private void exchangeHealthy() throws Exception {
        when(upbitRestClient.getTicker(anyString())).thenReturn(List.of(
                Map.of("market", COIN, "trade_price", new BigDecimal("1000"))));
    }

    /** REST 강제 갱신이 실패하는 상태 — 거래소 API 장애. */
    private void exchangeDown() throws Exception {
        when(upbitRestClient.getTicker(anyString()))
                .thenThrow(new RuntimeException("Upbit 500"));
    }

    private DynamicSessionEntity monitoringSession(boolean paper) {
        return sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC").timeframe("H1")
                .initialCapital(new BigDecimal("100000.00"))
                .availableKrw(new BigDecimal("100000.00"))
                .totalAssetKrw(new BigDecimal("100000.00"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("POSITION_MONITORING")
                .currentCoinPair(COIN)
                .tradingMode(paper ? "PAPER" : "REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build());
    }

    private PositionEntity openPosition(DynamicSessionEntity s) {
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(COIN).side("BUY")
                .entryPrice(new BigDecimal("1000.00000000"))
                .avgPrice(new BigDecimal("1000.50000000"))
                .size(new BigDecimal("9.99500000"))
                .investedKrw(new BigDecimal("10000.00"))
                .positionFee(new BigDecimal("5.00"))
                .realizedPnl(BigDecimal.ZERO).unrealizedPnl(BigDecimal.ZERO)
                .stopLossPrice(new BigDecimal("950.00000000"))
                .status("OPEN")
                .sessionId(s.getId()).sessionKind(DynamicTradingService.sessionKind(s))
                .openedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                .build());
        s.setCurrentPositionId(pos.getId());
        sessionRepo.saveAndFlush(s);
        return pos;
    }

    // ── 🔴 오탐: 감시할 포지션이 없는데 경고하던 문제 ─────────────────────────

    @Test
    @DisplayName("🔴 POSITION_MONITORING 인데 OPEN 포지션이 없으면 알리지 않는다 — 이게 무한 반복의 원인이었다")
    void staleStateWithoutOpenPosition_doesNotNotify() throws Exception {
        exchangeHealthy();
        monitoringSession(true);   // 포지션을 만들지 않는다 = 상태 필드만 어긋난 상황

        for (int i = 0; i < 10; i++) service.warnStaleSlCheck();

        verify(telegramService, never()).sendCustomNotification(anyString());
    }

    @Test
    @DisplayName("실전 세션도 마찬가지다 — 상태 불일치는 자가복구 가드가 다음 틱에 되돌린다")
    void staleStateWithoutOpenPosition_realSessionAlsoQuiet() throws Exception {
        exchangeHealthy();
        monitoringSession(false);

        for (int i = 0; i < 10; i++) service.warnStaleSlCheck();

        verify(telegramService, never()).sendCustomNotification(anyString());
    }

    // ── 🔴 소음: 자가복구가 성공하는 동안은 조용해야 한다 ─────────────────────

    @Test
    @DisplayName("🔴 REST 자가복구가 되는 동안은 알리지 않는다 — 거래 뜸한 코인의 정상적인 틱 공백")
    void selfHealingSucceeds_staysQuietForAWhile() throws Exception {
        exchangeHealthy();
        DynamicSessionEntity s = monitoringSession(true);
        openPosition(s);

        // 연속 4회(=4분)까지는 자가복구에 맡기고 사람을 부르지 않는다.
        for (int i = 0; i < 4; i++) service.warnStaleSlCheck();

        verify(telegramService, never()).sendCustomNotification(anyString());
    }

    @Test
    @DisplayName("자가복구를 계속 시도했는데도 점검이 안 잡힐 때는 알린다 — 진짜 고장 신호")
    void selfHealingNeverLands_eventuallyNotifies() throws Exception {
        // REST 호출은 성공하지만 <b>다른 코인</b> 시세가 돌아오는 상황 — 이벤트는 발행되는데
        // 이 세션의 코인과 맞지 않아 점검이 기록되지 않는다. "강제 갱신은 되는데
        // 실시간 판정 경로가 죽은" 상태를 재현한다.
        when(upbitRestClient.getTicker(anyString())).thenReturn(List.of(
                Map.of("market", "KRW-BTC", "trade_price", new BigDecimal("1000"))));
        DynamicSessionEntity s = monitoringSession(true);
        openPosition(s);

        // 5회 연속(=5분) 미점검이면 실시간 이벤트 경로가 죽었다고 본다.
        for (int i = 0; i < 5; i++) service.warnStaleSlCheck();

        verify(telegramService, atLeastOnce()).sendCustomNotification(anyString());
    }

    @Test
    @DisplayName("REST 강제 갱신 자체가 실패하면 즉시 알린다 — 거래소 장애는 기다릴 일이 아니다")
    void forceRefreshFails_notifiesImmediately() throws Exception {
        exchangeDown();
        DynamicSessionEntity s = monitoringSession(false);
        openPosition(s);

        service.warnStaleSlCheck();

        verify(telegramService, atLeastOnce()).sendCustomNotification(anyString());
    }

    @Test
    @DisplayName("포지션이 없으면 거래소 장애 중이어도 조용하다 — 감시 대상이 아니기 때문")
    void noPosition_quietEvenWhenExchangeDown() throws Exception {
        exchangeDown();
        monitoringSession(true);

        for (int i = 0; i < 10; i++) service.warnStaleSlCheck();

        verify(telegramService, never()).sendCustomNotification(anyString());
    }
}
