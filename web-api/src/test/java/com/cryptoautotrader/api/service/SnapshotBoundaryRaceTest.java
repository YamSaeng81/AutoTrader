package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.core.portfolio.PortfolioManager;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.UpbitRestClient;
import com.cryptoautotrader.exchange.upbit.dto.AccountResponse;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잔고 스냅숏과 로컬 약정의 <b>시간 경계</b> (2026-10-12).
 *
 * <p>계좌 여력 = 총잔고 스냅숏 − 열린 매수 약정 − 스냅숏 이후 로컬 종결 약정. 이 식은 <b>스냅숏이 어느
 * 시점의 잔고인가</b>와 <b>로컬 종결이 그 시점의 앞인가 뒤인가</b>에 달렸다. 식을 적은 것만으로는 이중
 * 차감·과대 승인이 모두 막혔다고 확정할 수 없다 — 그래서 경계마다 시험한다.</p>
 *
 * <table>
 *   <tr><th>경합</th><th>허용되는 오차</th></tr>
 *   <tr><td>① 거래소엔 체결 반영, 로컬은 열림</td><td>과소 승인만 (같은 돈을 두 번 뺀다)</td></tr>
 *   <tr><td>② 스냅숏 조회 중 체결·취소 + 로컬 종결</td><td>과소 승인만</td></tr>
 *   <tr><td>③ 부분 체결 후 취소</td><td>회계는 정확히 한 번, 약정은 다음 스냅숏까지 보수적으로</td></tr>
 *   <tr><td>④ 스냅숏 교체 순간</td><td>잔고와 시각이 섞이지 않는다 · 늦게 도착한 옛 응답이 새 것을 덮지 않는다</td></tr>
 * </table>
 *
 * <p>🔴 <b>과대 승인은 어느 경합에서도 0 이어야 한다.</b> 과소 승인은 다음 동기화까지 진입을 늦출 뿐이다.</p>
 *
 * <p>시험 기반의 DB 는 공유되므로 모든 잔고를 "다른 시험이 남긴 열린 약정" 기준선 위에 둔다.</p>
 */
@DisplayName("잔고 스냅숏 · 로컬 약정의 시간 경계")
class SnapshotBoundaryRaceTest extends IntegrationTestBase {

    @Autowired private OrderExecutionEngine orderExecutionEngine;
    @Autowired private LiveTradingService liveTradingService;
    @Autowired private PortfolioSyncService portfolioSyncService;
    @Autowired private PortfolioManager portfolioManager;
    @Autowired private CapitalPoolLimits limits;
    @Autowired private LiveTradingSessionRepository sessionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PositionRepository positionRepository;
    @Autowired private PlatformTransactionManager txManager;

    @MockBean private UpbitOrderClient upbitOrderClient;
    @MockBean private UpbitRestClient upbitRestClient;

    private final List<Long> sessions = new ArrayList<>();
    private final List<Long> orders = new ArrayList<>();

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void generousPool() {
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(jdbc);
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setPerPositionCapKrw(new BigDecimal("1000000000000"));
        limits.setMaxConcurrentPositions(100_000);
    }

    @AfterEach
    void cleanUp() {
        orders.forEach(id -> orderRepository.findById(id).ifPresent(orderRepository::delete));
        sessions.forEach(id -> positionRepository.findBySessionKindAndSessionId("LIVE", id)
                .forEach(positionRepository::delete));
        sessions.forEach(id -> sessionRepository.findById(id).ifPresent(sessionRepository::delete));
        portfolioManager.syncTotalCapital(new BigDecimal("1000000000000"));
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private Long session(BigDecimal available) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-DOGE").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(available)
                .totalAssetKrw(new BigDecimal("10000"))
                .status("RUNNING").startedAt(Instant.now()).build());
        sessions.add(s.getId());
        return s.getId();
    }

    private PositionEntity slot(Long sessionId, BigDecimal invested) {
        return positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(BigDecimal.ZERO).investedKrw(invested)
                .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                .openedAt(Instant.now()).build());
    }

    /** 접수돼 열린 매수 — 예약 3,000 이 이미 세션에서 차감된 상태. */
    private OrderEntity openBuy(Long sessionId, Long positionId, String uuid, BigDecimal reserved) {
        OrderEntity o = orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(reserved).reservedKrw(reserved)
                .state("SUBMITTED").exchangeOrderId(uuid)
                .clientOrderId("cat-" + uuid)
                .intentCommittedAt(Instant.now()).submittedAt(Instant.now())
                .feeSource(OrderExecutionEngine.FEE_SOURCE_PENDING)
                .settlementVersion(0L)
                .sessionKind("LIVE").sessionId(sessionId).positionId(positionId)
                .createdAt(Instant.now()).build());
        orders.add(o.getId());
        return o;
    }

    /** 운영 진입점으로 예약한다. @return 거절 사유 코드, 승인이면 {@code null} */
    private String reserve(BigDecimal amount) {
        Long sessionId = session(amount);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Long id = tx.execute(st -> {
                PositionEntity pos = slot(sessionId, amount);
                OrderRequest req = new OrderRequest();
                req.setCoinPair("KRW-DOGE");
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(amount);
                req.setReason("시간 경계 시험");
                req.setSignalPrice(new BigDecimal("300"));
                req.setSessionId(sessionId);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());
                return orderExecutionEngine.reserveAndPrepareIntent(req);
            });
            orders.add(id);
            return null;
        } catch (OrderExecutionEngine.ReservationRejected rejected) {
            return rejected.getCode();
        }
    }

    private BigDecimal baseline() {
        return orderRepository.sumOpenBuyCommitmentKrw();
    }

    private static AccountResponse krw(BigDecimal balance, BigDecimal locked) {
        AccountResponse a = new AccountResponse();
        a.setCurrency("KRW");
        a.setBalance(balance);
        a.setLocked(locked);
        return a;
    }

    private static OrderResponse bid(String uuid, String state, String volume, String funds, String fee) {
        OrderResponse r = new OrderResponse();
        r.setUuid(uuid);
        r.setSide("bid");
        r.setOrdType("price");
        r.setState(state);
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("3000"));
        r.setExecutedVolume(new BigDecimal(volume));
        r.setExecutedFunds(new BigDecimal(funds));
        r.setPaidFee(new BigDecimal(fee));
        return r;
    }

    // ── ① 거래소엔 체결 반영, 로컬은 열림 ───────────────────────────

    @Test
    @DisplayName("① 거래소 잔고엔 체결이 반영됐고 로컬 주문은 열려 있다 — 과소 승인만 생긴다")
    void exchangeFilledLocalStillOpen() {
        Long a = session(new BigDecimal("7000"));
        openBuy(a, slot(a, new BigDecimal("3000")).getId(), "uuid-b1", new BigDecimal("3000"));

        // 실제 계좌: 10,000 에서 3,000 이 이미 빠져 **7,000** 이 남았다. 스냅숏이 그것을 담는다.
        BigDecimal base = baseline().subtract(new BigDecimal("3000"));   // 이 시험의 주문을 뺀 기준선
        portfolioManager.syncTotalCapital(base.add(new BigDecimal("7000")));

        // 로컬은 그 3,000 을 아직 열린 약정으로 센다 → 여력 4,000 (실제는 7,000).
        assertThat(reserve(new BigDecimal("4001")))
                .as("같은 3,000 을 두 번 뺀다 — 과소 승인이고 과대 승인이 아니다")
                .isEqualTo(CapitalReservationService.ACCOUNT_HEADROOM);
        assertThat(reserve(new BigDecimal("4000"))).isNull();
        System.out.println("[① 체결 선반영] 실제 여유 7,000 / 승인 가능 4,000 — 과소 승인 3,000");
    }

    // ── ② 스냅숏 조회 중 체결·취소 + 로컬 종결 ──────────────────────

    @Test
    @DisplayName("② 스냅숏을 묻는 동안 체결·취소되고 로컬도 종결됐다 — 응답은 체결 전 잔고다")
    void fillAndCancelDuringSnapshotQuery() {
        Long a = session(new BigDecimal("7000"));
        Long b = session(new BigDecimal("8000"));
        OrderEntity filling = openBuy(a, slot(a, new BigDecimal("3000")).getId(), "uuid-f", new BigDecimal("3000"));
        OrderEntity cancelling = openBuy(b, slot(b, new BigDecimal("2000")).getId(), "uuid-c", new BigDecimal("2000"));
        BigDecimal base = baseline().subtract(new BigDecimal("5000"));

        // 거래소는 **체결 전**에 잔고를 계산했다: 가용 5,000 + 잠금 5,000(두 주문) = 10,000.
        // 그 응답이 돌아오는 사이에 ⓐ 3,000 매수가 체결돼 로컬도 FILLED 가 됐고 ⓑ 2,000 매수는 취소됐다.
        // 실제 계좌: 10,000 − 3,000 = 7,000 이고 묶인 돈은 없다 → **실제 여유 7,000**.
        Mockito.doAnswer(inv -> {
            OrderEntity f = orderRepository.findById(filling.getId()).orElseThrow();
            f.setState("FILLED");
            f.setFilledAt(Instant.now());
            orderRepository.save(f);
            OrderEntity c = orderRepository.findById(cancelling.getId()).orElseThrow();
            c.setState("CANCELLED");
            orderRepository.save(c);
            Thread.sleep(5);   // 응답이 돌아오는 시간
            return List.of(krw(base.add(new BigDecimal("5000")), new BigDecimal("5000")));
        }).when(upbitOrderClient).getAccounts();

        portfolioSyncService.syncBalance();

        // 🔴 기전을 직접 본다: 조회 중에 종결된 체결의 시각이 스냅숏 기준 시각 **이후**여야 약정으로 남는다.
        assertThat(orderRepository.findById(filling.getId()).orElseThrow().getFilledAt())
                .as("스냅숏 기준 시각은 조회 **시작** 시각이다 — 조회 중 종결은 그 뒤로 분류된다")
                .isAfterOrEqualTo(portfolioManager.getCapitalSnapshot().asOf());

        String over = reserve(new BigDecimal("7001"));
        System.out.println("[② 조회 중 체결] 실제 여유 7,000 / 7,001 요청 → " + (over == null ? "승인(과대)" : over));
        assertThat(over)
                .as("🔴 스냅숏 시각을 **응답 시각**으로 적으면, 조회 중 로컬에서 종결된 체결이 '스냅숏 이전' 으로 "
                        + "분류돼 빠진다 — 그런데 응답 잔고는 체결 전 값이다. 이미 쓴 3,000 을 다시 승인한다")
                .isEqualTo(CapitalReservationService.ACCOUNT_HEADROOM);
        assertThat(reserve(new BigDecimal("7000"))).as("실제 여유만큼은 승인한다").isNull();
    }

    @Test
    @DisplayName("② -b 거래소가 체결 후 잔고를 계산했다면 — 같은 체결을 두 번 빼서 과소 승인만 생긴다")
    void fillReflectedInSnapshotDuringQuery() {
        Long a = session(new BigDecimal("7000"));
        OrderEntity filling = openBuy(a, slot(a, new BigDecimal("3000")).getId(), "uuid-f2", new BigDecimal("3000"));
        BigDecimal base = baseline().subtract(new BigDecimal("3000"));

        Mockito.doAnswer(inv -> {
            OrderEntity f = orderRepository.findById(filling.getId()).orElseThrow();
            f.setState("FILLED");
            f.setFilledAt(Instant.now());
            orderRepository.save(f);
            // 이번엔 **체결 후** 잔고: 7,000.
            return List.of(krw(base.add(new BigDecimal("7000")), BigDecimal.ZERO));
        }).when(upbitOrderClient).getAccounts();

        portfolioSyncService.syncBalance();
        assertThat(orderRepository.findById(filling.getId()).orElseThrow().getFilledAt())
                .isAfterOrEqualTo(portfolioManager.getCapitalSnapshot().asOf());

        assertThat(reserve(new BigDecimal("4001")))
                .as("과소 승인 — 다음 동기화까지 3,000 이 묶인 것처럼 보인다")
                .isEqualTo(CapitalReservationService.ACCOUNT_HEADROOM);

        // 다음 동기화에서 풀린다.
        Mockito.doReturn(List.of(krw(base.add(new BigDecimal("7000")), BigDecimal.ZERO)))
                .when(upbitOrderClient).getAccounts();
        portfolioSyncService.syncBalance();
        assertThat(reserve(new BigDecimal("7000"))).as("새 스냅숏 이후로는 정확하다").isNull();
    }

    // ── ③ 부분 체결 후 취소 ─────────────────────────────────────────

    @Test
    @DisplayName("③ 시장가 매수가 부분 체결 후 취소로 끝난다 — 수수료는 보고값, 미사용 예약은 정확히 한 번 해제")
    void partialFillThenCancel() {
        Long a = session(new BigDecimal("7000"));
        PositionEntity pos = slot(a, new BigDecimal("3000"));
        OrderEntity order = openBuy(a, pos.getId(), "uuid-pc", new BigDecimal("3000"));

        // 업비트 시장가 매수는 남은 금액을 취소하며 `cancel` 로 끝나는 일이 흔하다.
        OrderResponse cancelWithFill = bid("uuid-pc", "cancel", "9.8", "2940", "1.47");
        Mockito.doReturn(cancelWithFill).when(upbitOrderClient).getOrder("uuid-pc");

        orderExecutionEngine.checkOrderStatus(order.getId());
        orderExecutionEngine.checkOrderStatus(order.getId());
        orderExecutionEngine.checkOrderStatus(order.getId());

        OrderEntity after = orderRepository.findById(order.getId()).orElseThrow();
        PositionEntity p = positionRepository.findById(pos.getId()).orElseThrow();
        BigDecimal krw = sessionRepository.findById(a).orElseThrow().getAvailableKrw();
        System.out.printf("[③ 부분 체결 후 취소] state=%s applied=%s fee=%s size=%s avg=%s posFee=%s 잔액=%s%n",
                after.getState(), after.getAppliedExecutedFunds(), after.getAppliedPaidFee(),
                p.getSize(), p.getAvgPrice(), p.getPositionFee(), krw);

        assertThat(after.getState()).isEqualTo("FILLED");
        assertThat(after.getAppliedExecutedFunds()).as("반영값은 실제 체결금액").isEqualByComparingTo("2940");
        assertThat(after.getAppliedPaidFee()).as("수수료는 거래소 보고값").isEqualByComparingTo("1.47");
        assertThat(p.getSize()).isEqualByComparingTo("9.8");
        assertThat(p.getAvgPrice()).as("평균단가 = 2940 / 9.8").isEqualByComparingTo("300");
        assertThat(p.getPositionFee()).isEqualByComparingTo("1.47");
        assertThat(krw)
                .as("🔴 미사용 예약 58.53(= 3000 − 2940 − 1.47)만 한 번 해제된다 — 세 번 조회해도 같다")
                .isEqualByComparingTo("7058.53");

        // 약정: 열린 약정에서는 빠지고, 다음 스냅숏 전까지는 '스냅숏 이후 종결' 로 3,000 전액을 센다(보수적).
        assertThat(orderRepository.sumBuyCommitmentFilledSince(portfolioManager.getLastSyncedAt()))
                .as("스냅숏이 체결 전 값이므로 아직 스냅숏 안의 돈으로 센다")
                .isGreaterThanOrEqualTo(new BigDecimal("3000"));
    }

    @Test
    @DisplayName("③ -c 생성 응답이 이미 '체결 있는 취소' 여도 회계는 한 경로에서 한 번만 반영된다")
    void creationResponseAlreadyCancelledWithFill() throws Exception {
        Long a = session(new BigDecimal("10000"));
        OrderResponse immediate = bid("uuid-imm", "cancel", "9.8", "2940", "1.47");
        Mockito.doReturn(new UpbitOrderClient.ExchangeResult(immediate, "{}"))
                .when(upbitOrderClient).createOrder(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(immediate).when(upbitOrderClient).getOrder("uuid-imm");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        OrderRequest req = new OrderRequest();
        req.setCoinPair("KRW-DOGE");
        req.setSide("BUY");
        req.setOrderType("MARKET");
        req.setQuantity(new BigDecimal("3000"));
        req.setReason("생성 응답 즉시 종결");
        req.setSignalPrice(new BigDecimal("300"));
        req.setSessionId(a);
        req.setSessionKind("LIVE");
        Long orderId = tx.execute(st -> {
            req.setPositionId(slot(a, new BigDecimal("3000")).getId());
            return orderExecutionEngine.reserveAndPrepareIntent(req);
        });
        orders.add(orderId);
        orderExecutionEngine.submitPreparedOrder(orderId, req);
        for (int i = 0; i < 100 && List.of("INTENT_READY", "SUBMITTING")
                .contains(orderRepository.findById(orderId).orElseThrow().getState()); i++) {
            Thread.sleep(50);
        }
        orderExecutionEngine.checkOrderStatus(orderId);
        orderExecutionEngine.checkOrderStatus(orderId);

        OrderEntity after = orderRepository.findById(orderId).orElseThrow();
        BigDecimal krw = sessionRepository.findById(a).orElseThrow().getAvailableKrw();
        System.out.printf("[③-c 생성 응답 즉시 종결] state=%s applied=%s fee=%s 잔액=%s%n",
                after.getState(), after.getAppliedExecutedFunds(), after.getAppliedPaidFee(), krw);
        assertThat(after.getState()).isEqualTo("FILLED");
        assertThat(after.getAppliedPaidFee()).isEqualByComparingTo("1.47");
        assertThat(krw).as("10,000 − 3,000 예약 + 미사용 58.53").isEqualByComparingTo("7058.53");
    }

    @Test
    @DisplayName("③ -b 체결 없이 취소 — 예약 전액 해제, 약정에도 남지 않는다 (돈이 가용으로 돌아왔다)")
    void cancelWithoutFillReleasesWholeReservation() {
        Long a = session(new BigDecimal("7000"));
        PositionEntity pos = slot(a, new BigDecimal("3000"));
        OrderEntity order = openBuy(a, pos.getId(), "uuid-c0", new BigDecimal("3000"));
        Mockito.doReturn(bid("uuid-c0", "cancel", "0", "0", "0")).when(upbitOrderClient).getOrder("uuid-c0");

        orderExecutionEngine.checkOrderStatus(order.getId());
        liveTradingService.reconcileOrphanBuyPositions();
        liveTradingService.reconcileOrphanBuyPositions();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getState()).isEqualTo("CANCELLED");
        assertThat(sessionRepository.findById(a).orElseThrow().getAvailableKrw())
                .as("예약 3,000 이 정확히 한 번 돌아온다")
                .isEqualByComparingTo("10000");
        // 체결 없는 취소는 어느 약정에도 남지 않아야 한다 — 돈이 가용으로 돌아왔으므로 스냅숏에 그대로 있다.
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getFilledAt())
                .as("체결 시각이 없어야 '스냅숏 이후 종결' 에 들어가지 않는다")
                .isNull();
    }

    // ── ④ 스냅숏 교체 순간 ─────────────────────────────────────────

    @Test
    @DisplayName("④ 늦게 도착한 옛 응답이 새 스냅숏을 덮지 않는다")
    void olderResponseDoesNotReplaceNewer() {
        // 기준 시각은 마이크로초로 내림해 저장된다 — DB 정밀도와 맞춘다.
        Instant t0 = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        portfolioManager.syncTotalCapital(new BigDecimal("7000"), t1);
        boolean replaced = portfolioManager.syncTotalCapital(new BigDecimal("10000"), t0);

        assertThat(replaced).as("t0 에 시작한 조회의 응답이 t1 스냅숏보다 늦게 도착했다").isFalse();
        assertThat(portfolioManager.getCapitalSnapshot().totalCapital()).isEqualByComparingTo("7000");
        assertThat(portfolioManager.getCapitalSnapshot().asOf()).isEqualTo(t1);
    }

    @Test
    @DisplayName("④ -b 교체 중에 읽어도 잔고와 시각이 섞이지 않는다")
    void snapshotIsReadAsOnePair() throws Exception {
        PortfolioManager pm = new PortfolioManager(BigDecimal.ZERO);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 200_000; i++) {
                // 잔고와 시각이 짝을 이룬다: 잔고 = 기준으로부터의 마이크로초
                Instant at = base.plus(i, ChronoUnit.MICROS);
                pm.syncTotalCapital(BigDecimal.valueOf(i), at);
            }
        });
        List<String> torn = new ArrayList<>();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 200_000; i++) {
                PortfolioManager.CapitalSnapshot s = pm.getCapitalSnapshot();
                if (s == null) continue;
                long expected = ChronoUnit.MICROS.between(base, s.asOf());
                if (s.totalCapital().longValue() != expected && torn.size() < 5) {
                    torn.add(s.totalCapital() + "@" + s.asOf());
                }
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        assertThat(torn).as("🔴 잔고와 시각을 따로 읽으면 다른 스냅숏의 짝이 섞일 수 있다").isEmpty();
    }
}
