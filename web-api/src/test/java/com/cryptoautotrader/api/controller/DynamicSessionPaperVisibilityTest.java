package com.cryptoautotrader.api.controller;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-09-01 P0 — <b>모의(PAPER) 동적 세션의 상세 화면이 통째로 비어 있었다.</b>
 *
 * <h3>무슨 일이 있었나</h3>
 * <p>{@code DynamicSessionController} 가 포지션을 조회할 때 {@code session_kind} 를
 * {@code "DYNAMIC"} 으로 <b>하드코딩</b>하고 있었다. 그런데 PAPER 세션의 포지션은
 * {@code "DYN_PAPER"} 로 저장된다(V67). 그 결과 페이퍼 세션에서는:</p>
 * <ul>
 *   <li>손익 분해 — 실현·미실현·승률·청산건수가 <b>전부 0</b></li>
 *   <li>보유 코인 이력 — <b>0건</b> (실제로는 거래가 있었는데도)</li>
 * </ul>
 *
 * <p>더 나쁜 건 <b>화면 안에서 서로 모순됐다</b>는 점이다. 현재 포지션 패널만
 * {@code currentPositionId} 로 직접 조회해서 정상 표시되니, "코인을 들고 있는데 이력은
 * 0건이고 손익은 0원"인 화면이 나왔다. 페이퍼 함대 9세션이 이 상태였다.</p>
 *
 * <p>이 테스트는 <b>PAPER 와 REAL 을 같은 방식으로</b> 만들어 두 세션 모두에서 같은 데이터가
 * 보이는지 확인한다. 한쪽만 보이면 실패한다.</p>
 */
class DynamicSessionPaperVisibilityTest extends IntegrationTestBase {

    private static final String COIN = "KRW-XRP";

    @Autowired private DynamicSessionController controller;
    @Autowired private DynamicSessionRepository sessionRepo;
    @Autowired private PositionRepository positionRepository;
    @Autowired private OrderRepository orderRepository;

    @BeforeEach
    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        positionRepository.deleteAll();
        sessionRepo.deleteAll();
    }

    private DynamicSessionEntity session(boolean paper) {
        return sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC").timeframe("H1")
                .initialCapital(new BigDecimal("100000.00"))
                .availableKrw(new BigDecimal("100000.00"))
                .totalAssetKrw(new BigDecimal("100000.00"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING")
                .tradingMode(paper ? "PAPER" : "REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build());
    }

    /**
     * 청산까지 끝난 포지션 1건 + 그 매수/매도 주문. 투자금 10,000원, 5% 손실로 청산.
     * 수수료는 실제 체결 경로와 같은 0.05% 를 쓴다.
     */
    private void closedPosition(DynamicSessionEntity s, String kind) {
        PositionEntity pos = positionRepository.saveAndFlush(PositionEntity.builder()
                .coinPair(COIN).side("BUY")
                .entryPrice(new BigDecimal("1000.00000000"))
                .avgPrice(new BigDecimal("1000.50000000"))
                .size(new BigDecimal("9.99500000"))
                .investedKrw(new BigDecimal("10000.00"))
                .positionFee(new BigDecimal("4.75"))
                .realizedPnl(new BigDecimal("-500.00"))
                .unrealizedPnl(BigDecimal.ZERO)
                .stopLossPrice(new BigDecimal("950.00000000"))
                .takeProfitPrice(new BigDecimal("1100.00000000"))
                .status("CLOSED")
                .exitReason(ExitReason.STOP_LOSS)
                .marketRegime("TREND")
                .sessionId(s.getId()).sessionKind(kind)
                .openedAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .closedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());

        orderRepository.saveAndFlush(OrderEntity.builder()
                .positionId(pos.getId()).coinPair(COIN).side("BUY").orderType("MARKET")
                .price(new BigDecimal("1000.00000000")).quantity(new BigDecimal("10000.00"))
                .filledQuantity(new BigDecimal("9.99500000")).state("FILLED")
                .signalReason("동적 세션 BUY — EMA 정배열 + RSI 반등")
                .sessionId(s.getId()).sessionKind(kind)
                .createdAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .filledAt(Instant.now().minus(3, ChronoUnit.HOURS))
                .build());
        orderRepository.saveAndFlush(OrderEntity.builder()
                .positionId(pos.getId()).coinPair(COIN).side("SELL").orderType("MARKET")
                .price(new BigDecimal("950.00000000")).quantity(new BigDecimal("9.99500000"))
                .filledQuantity(new BigDecimal("9.99500000")).state("FILLED")
                .signalReason("실시간 손절(WS) — pnl -5.00%")
                .sessionId(s.getId()).sessionKind(kind)
                .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .filledAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build());
    }

    // ── 🔴 회귀 방지: PAPER 가 REAL 과 똑같이 보여야 한다 ────────────────────

    @Test
    @DisplayName("🔴 모의 세션의 보유 코인 이력이 보인다 — 하드코딩된 'DYNAMIC' 이면 0건이 된다")
    void paperSessionHistoryIsVisible() {
        DynamicSessionEntity paper = session(true);
        closedPosition(paper, "DYN_PAPER");

        List<Map<String, Object>> history = controller.positions(paper.getId()).getData();

        assertThat(history)
                .as("페이퍼 포지션은 session_kind='DYN_PAPER' 라 'DYNAMIC' 으로 조회하면 안 잡힌다")
                .hasSize(1);
        assertThat(history.get(0).get("coinPair")).isEqualTo(COIN);
    }

    @Test
    @DisplayName("🔴 모의 세션의 손익 분해가 실제 값을 낸다 — 하드코딩이면 전부 0")
    void paperSessionPnlBreakdownIsVisible() {
        DynamicSessionEntity paper = session(true);
        closedPosition(paper, "DYN_PAPER");

        Map<String, Object> detail = controller.get(paper.getId()).getData();

        assertThat((BigDecimal) detail.get("realizedPnl"))
                .as("실현손익이 0이면 페이퍼 포지션을 못 찾은 것이다")
                .isEqualByComparingTo("-500.00");
        assertThat(detail.get("closedTradeCount")).isEqualTo(1);
        assertThat(detail.get("winCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("실전 세션도 그대로 보인다 — 페이퍼를 고치느라 실전을 깨뜨리지 않았다")
    void realSessionStillVisible() {
        DynamicSessionEntity real = session(false);
        closedPosition(real, "DYNAMIC");

        assertThat(controller.positions(real.getId()).getData()).hasSize(1);
        assertThat((BigDecimal) controller.get(real.getId()).getData().get("realizedPnl"))
                .isEqualByComparingTo("-500.00");
    }

    @Test
    @DisplayName("같은 id 의 REAL/PAPER 포지션이 섞이지 않는다 — kind 로 갈라야 하는 이유")
    void kindsDoNotLeakIntoEachOther() {
        DynamicSessionEntity paper = session(true);
        closedPosition(paper, "DYN_PAPER");
        // 같은 세션 id 로 REAL kind 포지션을 하나 더 심는다 — kind 를 안 보면 2건으로 합쳐진다.
        closedPosition(paper, "DYNAMIC");

        assertThat(controller.positions(paper.getId()).getData())
                .as("PAPER 세션은 DYN_PAPER 만 봐야 한다")
                .hasSize(1);
    }

    // ── 손익·수수료 표시 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("이력에 매수/매도 사유와 청산 사유가 함께 실린다")
    void historyCarriesReasons() {
        DynamicSessionEntity paper = session(true);
        closedPosition(paper, "DYN_PAPER");

        Map<String, Object> row = controller.positions(paper.getId()).getData().get(0);

        assertThat((String) row.get("buyReason")).contains("EMA 정배열");
        assertThat((String) row.get("sellReason")).contains("실시간 손절(WS)");
        assertThat(row.get("exitReason")).isEqualTo("STOP_LOSS");
        assertThat((BigDecimal) row.get("exitPrice")).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("수수료를 매수·매도로 나눠 싣고, 실현손익은 이미 그 둘을 뺀 값임을 표시한다")
    void historyCarriesFeeBreakdown() {
        DynamicSessionEntity paper = session(true);
        closedPosition(paper, "DYN_PAPER");

        Map<String, Object> row = controller.positions(paper.getId()).getData().get(0);

        // 매수 수수료 = 투자금 10,000 × 0.05% = 5.00
        assertThat((BigDecimal) row.get("entryFee")).isEqualByComparingTo("5.00");
        // 매도 수수료 = 9.995 × 950 × 0.05% = 4.7476… → 4.75
        assertThat((BigDecimal) row.get("exitFee")).isEqualByComparingTo("4.75");
        assertThat((BigDecimal) row.get("totalFee")).isEqualByComparingTo("9.75");
        // grossPnl = 순손익(-500) + 수수료(9.75) — "수수료가 없었다면" 의 가정값
        assertThat((BigDecimal) row.get("grossPnl")).isEqualByComparingTo("-490.25");
    }

    @Test
    @DisplayName("손익 분해가 수수료 합계와 청산 사유 분포를 함께 낸다")
    void breakdownCarriesFeesAndExitReasons() {
        DynamicSessionEntity paper = session(true);
        closedPosition(paper, "DYN_PAPER");

        Map<String, Object> detail = controller.get(paper.getId()).getData();

        assertThat((BigDecimal) detail.get("totalFee")).isEqualByComparingTo("9.75");
        assertThat((BigDecimal) detail.get("grossPnl")).isEqualByComparingTo("-490.25");
        @SuppressWarnings("unchecked")
        Map<String, Integer> exits = (Map<String, Integer>) detail.get("exitReasonCounts");
        assertThat(exits).containsEntry("STOP_LOSS", 1);
        assertThat(detail.get("worstCoin")).isEqualTo(COIN);
    }

    @Test
    @DisplayName("거래가 없으면 빈 값을 낸다 — 없는 것을 만들어내지 않는다")
    void emptySessionStaysEmpty() {
        DynamicSessionEntity paper = session(true);

        assertThat(controller.positions(paper.getId()).getData()).isEmpty();
        Map<String, Object> detail = controller.get(paper.getId()).getData();
        assertThat(detail.get("closedTradeCount")).isEqualTo(0);
        assertThat(detail.get("winRatePct")).isNull();
    }
}
