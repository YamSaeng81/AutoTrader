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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 2026-08-27 — <b>불변식 ②: 포지션이 있으면 원금만큼은 가용 잔고에서 빠져 있어야 한다</b>.
 *
 * <p><b>왜 신설했나</b>: 세션 60이 포지션 2852(7,108원)를 든 채
 * {@code available_krw == total_asset_krw} 상태였다. 같은 돈이 두 번 쓸 수 있는 상태였고,
 * 실제로 그 돈으로 같은 코인을 재매수해 <b>중복 포지션 → 세션 21시간 정지</b>까지 갔다.</p>
 *
 * <p>기존 {@code reconcileDynamicSessionBalance} 는 <b>이 상태를 구조적으로 못 잡았다</b> —
 * 첫 줄이 {@code if (cmp == 0) continue;} 라서 {@code available == total} 을 항상 정상으로
 * 취급했기 때문이다. PAPER 제외가 없었더라도 못 잡았을 것이다. 불변식이
 * "포지션 없으면 available == total" 한쪽만 검사하고 있었다.</p>
 *
 * <p>세션 60은 <b>나흘간 아무 경고 없이</b> 지나갔고 A/B 점검 중 우연히 발견됐다.
 * 그래서 이 안전망의 목적은 교정이 아니라 <b>감지</b>다.</p>
 */
class DynamicUnlockedCapitalTest extends IntegrationTestBase {

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
        warnedSet().clear();
    }

    @SuppressWarnings("unchecked")
    private Set<Long> warnedSet() {
        return (Set<Long>) ReflectionTestUtils.getField(dynamicTradingService, "unlockedCapitalWarned");
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * {@code updated_at} 을 유예시간({@code BALANCE_RECONCILE_GRACE_MIN}=3분) 밖으로 밀어둔 세션.
     *
     * <p>안전망은 최근 갱신된 세션을 일부러 건드리지 않는다 — 매수 경로가 KRW 차감(선커밋) →
     * 부모 커밋 순서라 그 사이 짧은 구간에는 "차감됐지만 포지션이 아직 안 보이는" 정상 상태가
     * 존재하기 때문이다. 테스트도 그 조건을 실제로 통과해야 하므로 네이티브로 과거로 민다
     * ({@code updated_at} 은 {@code @UpdateTimestamp} 라 엔티티로는 못 넣는다).</p>
     */
    private DynamicSessionEntity newSession(boolean paper, String avail, String total, String coin) {
        DynamicSessionEntity s = dynamicSessionRepository.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal(avail))
                .totalAssetKrw(new BigDecimal(total))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("POSITION_MONITORING").currentCoinPair(coin)
                .tradingMode(paper ? "PAPER" : "REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build());
        jdbcTemplate.update("UPDATE dynamic_session SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.MINUTES)), s.getId());
        return s;
    }

    private void openPosition(Long sessionId, String kind, String coin, String invested) {
        positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(coin).side("BUY")
                .entryPrice(new BigDecimal("1500")).avgPrice(new BigDecimal("1500"))
                .size(new BigDecimal("5.33333333")).investedKrw(new BigDecimal(invested))
                .status("OPEN").sessionId(sessionId).sessionKind(kind)
                .build());
    }

    @Test
    @DisplayName("🔴 세션 60 재현 — 포지션 보유 중 available == total 이면 감지·알림한다 (PAPER)")
    void paper_positionHeldButCapitalUnlocked_alerts() {
        DynamicSessionEntity s = newSession(true, "8875.00", "8875.00", "KRW-STX");
        openPosition(s.getId(), "DYN_PAPER", "KRW-STX", "7108.00");

        dynamicTradingService.reconcileDynamicSessionBalance();

        verify(telegramService).sendCustomNotification(contains("잔고 정합성 이상"));
        assertThat(warnedSet()).contains(s.getId());
    }

    @Test
    @DisplayName("available > total 이어도(더 심한 경우) 감지한다")
    void availableGreaterThanTotal_alerts() {
        DynamicSessionEntity s = newSession(true, "9000.00", "8875.00", "KRW-STX");
        openPosition(s.getId(), "DYN_PAPER", "KRW-STX", "7108.00");

        dynamicTradingService.reconcileDynamicSessionBalance();

        verify(telegramService).sendCustomNotification(contains("잔고 정합성 이상"));
    }

    @Test
    @DisplayName("REAL 세션에도 같은 불변식이 적용된다")
    void real_positionHeldButCapitalUnlocked_alerts() {
        DynamicSessionEntity s = newSession(false, "8875.00", "8875.00", "KRW-STX");
        openPosition(s.getId(), "DYNAMIC", "KRW-STX", "7108.00");

        dynamicTradingService.reconcileDynamicSessionBalance();

        verify(telegramService).sendCustomNotification(contains("잔고 정합성 이상"));
    }

    @Test
    @DisplayName("정상 보유(available < total)는 알리지 않는다 — 오탐 없음")
    void normalHolding_silent() {
        DynamicSessionEntity s = newSession(true, "1775.00", "8875.00", "KRW-STX");
        openPosition(s.getId(), "DYN_PAPER", "KRW-STX", "7108.00");

        dynamicTradingService.reconcileDynamicSessionBalance();

        verify(telegramService, never()).sendCustomNotification(contains("잔고 정합성 이상"));
        assertThat(warnedSet()).doesNotContain(s.getId());
    }

    @Test
    @DisplayName("포지션이 없으면 이 불변식의 대상이 아니다 — 기존 고아 잔고 경로가 담당")
    void noPosition_notThisInvariant() {
        DynamicSessionEntity s = newSession(true, "10000.00", "10000.00", null);

        dynamicTradingService.reconcileDynamicSessionBalance();

        verify(telegramService, never()).sendCustomNotification(contains("잔고 정합성 이상"));
    }

    @Test
    @DisplayName("같은 이상을 60초마다 반복 알리지 않는다 — 스케줄러 스팸 방지")
    void repeatedRuns_alertOnce() {
        DynamicSessionEntity s = newSession(true, "8875.00", "8875.00", "KRW-STX");
        openPosition(s.getId(), "DYN_PAPER", "KRW-STX", "7108.00");

        dynamicTradingService.reconcileDynamicSessionBalance();
        dynamicTradingService.reconcileDynamicSessionBalance();
        dynamicTradingService.reconcileDynamicSessionBalance();

        verify(telegramService).sendCustomNotification(contains("잔고 정합성 이상"));
    }

    @Test
    @DisplayName("PAPER 세션은 REAL 포지션(DYNAMIC)을 자기 것으로 세지 않는다 — 08-07 오판 재발 방지")
    void paperSession_ignoresRealKindPosition() {
        // 08-07: 포지션을 SESSION_KIND(REAL)로만 조회해 PAPER 가 "포지션 없음"으로 오판됐다.
        // 이제 세션의 실제 kind 로 조회하므로, kind 가 다른 행은 안 보여야 한다.
        DynamicSessionEntity s = newSession(true, "1775.00", "8875.00", "KRW-STX");
        openPosition(s.getId(), "DYNAMIC", "KRW-STX", "7108.00");

        dynamicTradingService.reconcileDynamicSessionBalance();

        // PAPER 관점에선 포지션이 없고 available < total → 기존 "고아 잔고" 경로로 간다.
        // 불변식 ② 알림은 나오면 안 된다.
        verify(telegramService, never()).sendCustomNotification(contains("잔고 정합성 이상"));
    }
}
