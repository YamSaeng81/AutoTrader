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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-27 P0 회귀 테스트 — <b>{@code transitionToScanning} 은 포지션을 두고 떠나지 않는다</b>.
 *
 * <p><b>사고 재현 (세션 60, 실시간 관측)</b>:</p>
 * <pre>
 *   00:58:42  포지션 2997 손절 청산 → transitionToScanning → SCANNING
 *             ↳ 같은 세션의 포지션 2852 가 그 순간 고아가 됨
 *   00:59:47  자가복구 가드가 잡아 정리 (FORCED_STOP, 보유 83.2시간)
 * </pre>
 *
 * <p>SL·TP·time stop 은 전부 {@code processMonitoringTick} 에서만 돈다. 그래서 세션이
 * SCANNING 으로 가버리면 남은 포지션은 <b>모든 청산 장치에서 통째로 빠진다</b> — 실제로 2852 는
 * {@code maxHoldHours=24} 인데도 83시간 방치됐고, 그사이 세션이 같은 코인을 재매수해
 * 중복 포지션까지 만들었다(그 중복이 다시 세션을 21시간 정지시켰다).</p>
 *
 * <p>이 테스트가 잠그는 불변식: <b>OPEN 포지션이 남아 있으면 SCANNING 으로 가지 않는다.</b></p>
 */
class DynamicScanTransitionGuardTest extends IntegrationTestBase {

    @Autowired
    private DynamicTradingService dynamicTradingService;

    @Autowired
    private DynamicSessionRepository dynamicSessionRepository;

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
        dynamicSessionRepository.deleteAll();
    }

    private DynamicSessionEntity newSession(boolean paper, String coinPair) {
        return dynamicSessionRepository.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING")
                .scanState("POSITION_MONITORING")
                .currentCoinPair(coinPair)
                .tradingMode(paper ? "PAPER" : "REAL")
                .maxCandidateSize(30)
                .targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000"))
                .maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build());
    }

    private PositionEntity openPosition(Long sessionId, String kind, String coinPair) {
        return positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(coinPair).side("BUY")
                .entryPrice(new BigDecimal("1500")).avgPrice(new BigDecimal("1500"))
                .size(new BigDecimal("5.33333333")).investedKrw(new BigDecimal("8000"))
                .status("OPEN").sessionId(sessionId).sessionKind(kind)
                .build());
    }

    @Test
    @DisplayName("포지션이 없으면 평소대로 SCANNING 으로 간다 — 정상 경로에 부작용 없음")
    void noPosition_goesToScanning() {
        DynamicSessionEntity s = newSession(true, "KRW-XRP");

        dynamicTradingService.transitionToScanning(s.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getScanState()).isEqualTo("SCANNING");
        assertThat(after.getCurrentCoinPair()).isNull();
        assertThat(after.getCurrentPositionId()).isNull();
    }

    @Test
    @DisplayName("🔴 OPEN 포지션이 남아 있으면 SCANNING 으로 가지 않고 그 포지션을 계속 감시한다 (PAPER)")
    void openPositionRemains_staysMonitoring_paper() {
        DynamicSessionEntity s = newSession(true, "KRW-STX");
        PositionEntity orphan = openPosition(s.getId(), "DYN_PAPER", "KRW-STX");

        dynamicTradingService.transitionToScanning(s.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getScanState())
                .as("SCANNING 으로 가면 SL·TP·time stop 이 전부 이 포지션을 못 본다")
                .isEqualTo("POSITION_MONITORING");
        assertThat(after.getCurrentCoinPair()).isEqualTo("KRW-STX");
        assertThat(after.getCurrentPositionId()).isEqualTo(orphan.getId());
    }

    @Test
    @DisplayName("REAL 세션에도 같은 불변식이 적용된다")
    void openPositionRemains_staysMonitoring_real() {
        DynamicSessionEntity s = newSession(false, "KRW-STX");
        PositionEntity orphan = openPosition(s.getId(), "DYNAMIC", "KRW-STX");

        dynamicTradingService.transitionToScanning(s.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getScanState()).isEqualTo("POSITION_MONITORING");
        assertThat(after.getCurrentPositionId()).isEqualTo(orphan.getId());
    }

    @Test
    @DisplayName("세션 60 재현 — 한 포지션을 청산해도 남은 포지션이 있으면 감시가 이어진다")
    void session60Scenario_secondPositionKeepsMonitoring() {
        DynamicSessionEntity s = newSession(true, "KRW-STX");
        PositionEntity older = openPosition(s.getId(), "DYN_PAPER", "KRW-STX");   // 2852 역할
        PositionEntity newer = openPosition(s.getId(), "DYN_PAPER", "KRW-STX");   // 2997 역할

        // 2997 청산 완료 상황
        newer.setStatus("CLOSED");
        positionRepository.saveAndFlush(newer);

        dynamicTradingService.transitionToScanning(s.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getScanState())
                .as("2852 가 고아가 되어 83시간 방치된 것이 이 경로였다")
                .isEqualTo("POSITION_MONITORING");
        assertThat(after.getCurrentPositionId()).isEqualTo(older.getId());
    }

    @Test
    @DisplayName("CLOSING 중인 포지션만 남았으면 SCANNING 으로 간다 — 매도 진행 중 고착 방지")
    void closingPositionOnly_goesToScanning() {
        DynamicSessionEntity s = newSession(true, "KRW-STX");
        PositionEntity closing = openPosition(s.getId(), "DYN_PAPER", "KRW-STX");
        closing.setStatus("CLOSING");
        positionRepository.saveAndFlush(closing);

        dynamicTradingService.transitionToScanning(s.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getScanState())
                .as("executeSell 은 CLOSING 전환 후 이 메서드를 부른다 — 기존 동작이 유지돼야 한다")
                .isEqualTo("SCANNING");
    }

    @Test
    @DisplayName("다른 세션의 포지션에는 반응하지 않는다 — 세션 격리")
    void otherSessionPosition_ignored() {
        DynamicSessionEntity mine = newSession(true, "KRW-XRP");
        DynamicSessionEntity other = newSession(true, "KRW-STX");
        openPosition(other.getId(), "DYN_PAPER", "KRW-STX");

        dynamicTradingService.transitionToScanning(mine.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(mine.getId()).orElseThrow();
        assertThat(after.getScanState()).isEqualTo("SCANNING");
    }

    @Test
    @DisplayName("PAPER 세션은 REAL 포지션(DYNAMIC)에 반응하지 않는다 — session_kind 격리")
    void paperSession_ignoresRealKindPosition() {
        DynamicSessionEntity paper = newSession(true, "KRW-STX");
        // 같은 sessionId 지만 kind 가 다른 행 (D-2 sessionId 충돌 시나리오)
        openPosition(paper.getId(), "DYNAMIC", "KRW-STX");

        dynamicTradingService.transitionToScanning(paper.getId());

        DynamicSessionEntity after = dynamicSessionRepository.findById(paper.getId()).orElseThrow();
        assertThat(after.getScanState()).isEqualTo("SCANNING");
    }
}
