package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.dto.OrderRequest;
import com.cryptoautotrader.api.entity.PositionEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 11 — <b>서로 다른 세션의 동시 진입 요청</b> (2026-10-12). 선행 조건 3번의 닫는 조건.
 *
 * <h3>경계</h3>
 *
 * <p>{@code LiveTradingService.executeSessionBuy} 의 §8 검사는 이렇게 생겼다:</p>
 *
 * <pre>
 * BigDecimal totalAvailableKrw = sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES);
 * if (totalAvailableKrw.subtract(investAmount).compareTo(BigDecimal.ZERO) &lt; 0) {
 *     return;   // 차단
 * }
 * ...
 * balanceUpdater.apply(sessionId, s -&gt; s.setAvailableKrw(s.getAvailableKrw().subtract(investAmount)));
 * </pre>
 *
 * <p>🔴 <b>읽고 → 판단하고 → 쓴다.</b> 그 사이에 아무 직렬화도 없다. 게다가 차감은
 * {@code submitOrderAfterCommit} <b>뒤에</b> 일어나므로, 두 세션이 같은 합계를 읽고 둘 다 통과한
 * 다음 각자 제출하는 순서가 가능하다 — <b>예약 합계가 상한을 넘는다.</b></p>
 *
 * <h3>🔴 이 시험이 증명하는 것과 증명하지 않는 것</h3>
 *
 * <table>
 *   <tr><th></th><th>내용</th></tr>
 *   <tr><td>증명한다</td><td>§8 의 <b>읽기-판단-쓰기 경계 자체</b>에 직렬화가 없다는 것. 같은 협력
 *       객체({@code sumAvailableKrwByStatusIn} · {@code balanceUpdater})를 같은 순서로 쓴다</td></tr>
 *   <tr><td>🔴 증명하지 않는다</td><td>{@code executeSessionBuy} 를 끝까지 구동한 것이 아니다 —
 *       그 메서드는 {@code private} 이고 캔들·신호·레짐 준비가 필요하다. 그래서 이 시험은
 *       <b>경계의 재현</b>이며 운영 경로의 발생 빈도를 말하지 않는다</td></tr>
 * </table>
 *
 * <h3>측정 이력</h3>
 *
 * <p>🔴 <b>2026-10-12 1차 — 재현됐다</b> (종전 구조):</p>
 *
 * <pre>
 * 승인 = 2건 (기대 1건)
 * 예약 합계 = 6,000  &gt;  상한 5,000
 * 잔여 합계 = −1,000
 * </pre>
 *
 * <p>두 작업자가 <b>같은 합계를 읽고 둘 다 통과</b>했다.</p>
 *
 * <p>🟢 <b>2026-10-12 2차 — V85 로 닫았다.</b> 계좌 단위 잠금 행 하나를 직렬화 지점으로 두고,
 * 한도 확인·자금 예약·슬롯·주문 의도를 <b>한 트랜잭션</b>으로 묶었다. 잠금을 쥔 뒤에 합계를 읽고
 * 차감까지 끝내므로 불변식 <b>{@code 활성 세션 가용 KRW 합 ≥ 0}</b> 이 유지된다.</p>
 *
 * <p>🔴 <b>왜 세션 행 잠금으로는 부족했는가</b>: 한도는 <b>세션 사이에 공유된 풀</b>이다. 각자 자기
 * 세션 행만 잠그면 서로를 막지 못한다. 공유 자원에는 공유 잠금이 필요하다.</p>
 *
 * <p>🔴 <b>거래소의 잔고 부족 거절은 안전장치가 아니다.</b> 거래소 계좌에 여유 자금이 있으면 로컬
 * 상한을 넘은 주문도 그대로 접수된다. 그리고 접수된 주문의 응답이 유실되면 그 초과분이 <b>불명
 * 상태로 남는다</b> — 그래서 초과 승인은 로컬에서 막아야 한다.</p>
 *
 * <p>🟢 <b>2026-10-12 3차 — 한도를 둘로 나눴다.</b> V85 의 상한은 <b>계좌 잔고</b> 하나였다. 그러나
 * 계좌 잔고와 <b>사전 등록한 공유 풀 한도</b>는 별개다 — 계좌에 다른 자금이 있어도 단계 자본·포지션당
 * 금액·동시 보유를 넘으면 안 된다. 그래서 같은 잠금 아래에서 <b>등록 풀 한도</b>({@code CapitalPoolLimits})
 * 와 <b>계좌 여력</b>을 각각 보고, 이 클래스는 세 축을 <b>따로</b> 동시성 시험한다:</p>
 *
 * <ul>
 *   <li>계좌 여력 — 배정 합이 계좌 잔고를 넘은 drift (2차의 시험)</li>
 *   <li>자금 한도 — 계좌에는 넉넉하지만 등록 총 자본 상한을 넘는다</li>
 *   <li>슬롯 한도 — 자금은 넉넉하지만 동시 보유 상한을 넘는다</li>
 * </ul>
 *
 * <p>🔴 <b>이것이 선행 조건 3번 전체를 닫지는 않는다.</b> 세션 생성·재시작은 이제 같은 잠금을 지나지만,
 * <b>DYNAMIC 의 매수</b>는 여전히 예약 없이 제출한다(노출·슬롯 계산에는 세어진다).</p>
 */
@DisplayName("시험 11 — 서로 다른 세션의 동시 진입과 예약 상한")
class ConcurrentEntryCapTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private OrderExecutionEngine orderExecutionEngine;

    @Autowired
    private PositionRepository positionRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.cryptoautotrader.exchange.upbit.UpbitOrderClient upbitOrderClient;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.cryptoautotrader.exchange.upbit.UpbitRestClient upbitRestClient;

    @Autowired
    private PlatformTransactionManager txManager;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    /** 운영 코드가 §8 에서 쓰는 것과 같은 상태 목록. */
    private static final List<String> ACTIVE_SESSION_STATUSES = List.of("RUNNING", "PAUSED");

    @Autowired
    private OrderRepository orderRepository;

    /**
     * 공유 풀의 상한 = <b>거래소 계좌 KRW 잔고</b>.
     *
     * <p>🔴 시험에서 고정해야 하는 값이다 — 실제 잔고에 의존하면 시험이 환경을 측정하게 된다.</p>
     */
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.cryptoautotrader.core.portfolio.PortfolioManager portfolioManager;

    private final List<Long> createdOrders = new ArrayList<>();

    /** 등록 풀 한도 — 시험마다 바꾸고 끝나면 되돌린다. */
    @Autowired
    private CapitalPoolLimits limits;

    private BigDecimal savedTotalCap;
    private BigDecimal savedPerPosition;
    private Integer savedMaxPositions;

    /** 거절 사유 — 어느 한도에 걸렸는지 확인한다. */
    private final List<String> rejectionCodes = new CopyOnWriteArrayList<>();

    @Autowired private org.springframework.jdbc.core.JdbcTemplate quiesceJdbc;

    @org.junit.jupiter.api.BeforeEach
    void saveLimits() {
        // 계좌 여력은 전역 약정 합을 쓴다 — 앞선 시험이 남긴 열린 매수가 시험 도중 움직이지 않게 닫는다.
        com.cryptoautotrader.api.support.OpenBuyLedger.quiesce(quiesceJdbc);
        savedTotalCap = limits.getTotalCapKrw();
        savedPerPosition = limits.getPerPositionCapKrw();
        savedMaxPositions = limits.getMaxConcurrentPositions();
        rejectionCodes.clear();
        // 🔴 스냅숏은 신선해야 한다 — 없거나 낡으면 계좌 검사가 먼저 거절해 다른 축을 시험할 수 없다.
        Mockito.doReturn(Instant.now()).when(portfolioManager).getLastSyncedAt();
    }

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        limits.setTotalCapKrw(savedTotalCap);
        limits.setPerPositionCapKrw(savedPerPosition);
        limits.setMaxConcurrentPositions(savedMaxPositions);
        createdOrders.forEach(id -> orderRepository.findById(id).ifPresent(orderRepository::delete));
        createdOrders.clear();
        createdSessions.forEach(id -> positionRepository.findBySessionKindAndSessionId("LIVE", id)
                .forEach(positionRepository::delete));
        createdSessions.forEach(sessionRepository::deleteById);
        createdSessions.clear();
    }

    private Long session(String coinPair, BigDecimal availableKrw) {
        LiveTradingSessionEntity s = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair(coinPair).timeframe("M5")
                .initialCapital(new BigDecimal("5000.00"))
                .availableKrw(availableKrw)
                .totalAssetKrw(new BigDecimal("5000.00"))
                .status("RUNNING").startedAt(Instant.now()).build());
        createdSessions.add(s.getId());
        return s.getId();
    }

    /**
     * 🟢 <b>운영이 쓰는 예약 경계를 그대로 부른다</b> (V85).
     *
     * <p>{@code CapitalReservationService.reserveWithinCurrentTransaction} 은
     * {@code executeSessionBuy} 가 쓰는 바로 그 메서드다. 계좌 잠금을 쥔 채 한도 확인과 차감을
     * 끝내므로, 읽기와 쓰기 사이에 다른 예약이 끼어들 수 없다.</p>
     *
     * <p>🔴 <b>간극을 일부러 벌려 둔다.</b> 잠금이 없다면 이 지연 때문에 두 쪽이 모두 통과한다 —
     * 종전 구조에서 실제로 그렇게 재현됐다. 잠금이 있으면 한쪽이 여기서 <b>기다린다.</b></p>
     *
     * @return 진입을 승인하고 예약했으면 {@code true}
     */
    private boolean checkThenReserve(Long sessionId, String coinPair, BigDecimal investAmount,
                                      CountDownLatch go) {
        try {
            go.await(5, TimeUnit.SECONDS);   // 두 쪽이 **동시에** 들어간다
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            Long orderId = tx.execute(st -> {
                // 🔴 H2 의 잠금 대기 시간을 넉넉히 둔다. 기본값은 짧아서, 잠금이 **제대로 걸려도**
                //    기다리는 쪽이 타임아웃으로 죽는다 — 1차 시도에서 실제로 그래서 승인 0건 +
                //    "Unable to rollback" 이 났다. 직렬화 실패가 아니라 시험 환경의 제한이다.
                em.createNativeQuery("SET LOCK_TIMEOUT 15000").executeUpdate();

                // 🔴 슬롯(포지션)도 **같은 트랜잭션**에서 만든다 — 운영 경로와 같다.
                PositionEntity pos = positionRepository.save(PositionEntity.builder()
                        .coinPair(coinPair).side("BUY")
                        .entryPrice(new BigDecimal("300.00000000"))
                        .avgPrice(new BigDecimal("300.00000000"))
                        .size(BigDecimal.ZERO)
                        .investedKrw(investAmount)
                        .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                        .openedAt(Instant.now()).build());

                OrderRequest req = new OrderRequest();
                req.setCoinPair(coinPair);
                req.setSide("BUY");
                req.setOrderType("MARKET");
                req.setQuantity(investAmount);
                req.setReason("시험 11 — 동시 진입");
                req.setSignalPrice(new BigDecimal("300.00000000"));
                req.setSessionId(sessionId);
                req.setSessionKind("LIVE");
                req.setPositionId(pos.getId());

                // 🟢 **운영이 쓰는 진입점을 그대로 부른다.** 계좌 잠금 아래에서 한도 확인·자금
                //    예약·슬롯·주문 의도가 함께 커밋된다.
                Long id = orderExecutionEngine.reserveAndPrepareIntent(req);
                if (id == null) {
                    st.setRollbackOnly();   // 한도 거절 — 슬롯도 되돌린다
                    return null;
                }
                // 커밋 전에 짧은 간극을 둔다 — 잠금이 없으면 상대가 이 사이에 같은 원장을 읽는다.
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return id;
            });
            if (orderId != null) {
                createdOrders.add(orderId);
                return true;
            }
            return false;
        } catch (OrderExecutionEngine.ReservationRejected rejected) {
            rejectionCodes.add(rejected.getCode());
            return false;
        }
    }

    /** 두 세션이 동시에 진입한다. @return 승인 건수 */
    private long raceTwo(Long a, String coinA, Long b, String coinB, BigDecimal invest, List<Throwable> errors)
            throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> approvals = new CopyOnWriteArrayList<>();
        Thread t1 = new Thread(() -> {
            try {
                approvals.add(checkThenReserve(a, coinA, invest, go));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                approvals.add(checkThenReserve(b, coinB, invest, go));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join(40_000);
        t2.join(40_000);
        return approvals.stream().filter(Boolean::booleanValue).count();
    }

    /** 다른 시험이 남긴 열린 매수 약정 — 계좌 여력의 기준선. */
    private BigDecimal openCommitmentBaseline() {
        BigDecimal v = orderRepository.sumOpenBuyCommitmentKrw();
        return v != null ? v : BigDecimal.ZERO;
    }

    @Test
    @DisplayName("자금 한도 — 계좌에 넉넉해도 등록 총 자본 상한을 넘는 동시 진입은 하나만 승인한다")
    void concurrentEntriesAgainstRegisteredPoolCap() throws Exception {
        // 🔴 계좌에는 **충분한 돈이 있다**(1조). 거래소는 두 주문을 모두 받아 줄 것이다 — 그래서
        //    거래소 거절은 이 한도의 안전장치가 될 수 없다. 막는 것은 등록 한도뿐이다.
        Mockito.doReturn(new com.cryptoautotrader.core.portfolio.PortfolioManager.CapitalSnapshot(new BigDecimal("1000000000000"), Instant.now()))
                .when(portfolioManager).getCapitalSnapshot();

        Long a = session("KRW-DOGE", new BigDecimal("3000.00"));
        Long b = session("KRW-ADA", new BigDecimal("3000.00"));
        BigDecimal invest = new BigDecimal("3000.00");

        // 다른 시험이 남긴 열린 포지션 노출 위에 5,000 만 남긴다 — 3,000 두 건은 들어가지 않는다.
        BigDecimal baseline = positionRepository.sumPoolExposureKrw(-1L);
        limits.setTotalCapKrw(baseline.add(new BigDecimal("5000.00")));
        limits.setMaxConcurrentPositions(100_000);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        long approved = raceTwo(a, "KRW-DOGE", b, "KRW-ADA", invest, errors);

        System.out.printf("[자금 한도] 승인=%d건 남은 풀=5000 신규=%s×2 거절사유=%s 오류=%s%n",
                approved, invest, rejectionCodes, errors);
        assertThat(errors).isEmpty();
        assertThat(approved)
                .as("🔴 계좌에 돈이 있어도 등록 자본 상한(남은 5,000)을 넘는 두 번째 진입은 승인하지 않는다")
                .isEqualTo(1L);
        assertThat(rejectionCodes).containsExactly(CapitalReservationService.POOL_CAP);
        assertThat(positionRepository.sumPoolExposureKrw(-1L))
                .as("노출 합이 등록 상한 안에 있다")
                .isLessThanOrEqualTo(limits.getTotalCapKrw());
    }

    @Test
    @DisplayName("슬롯 한도 — 자금이 넉넉해도 동시 보유 상한을 넘는 동시 진입은 하나만 승인한다")
    void concurrentEntriesAgainstSlotLimit() throws Exception {
        Mockito.doReturn(new com.cryptoautotrader.core.portfolio.PortfolioManager.CapitalSnapshot(new BigDecimal("1000000000000"), Instant.now()))
                .when(portfolioManager).getCapitalSnapshot();

        Long a = session("KRW-DOGE", new BigDecimal("3000.00"));
        Long b = session("KRW-ADA", new BigDecimal("3000.00"));
        BigDecimal invest = new BigDecimal("3000.00");

        // 자금 한도는 넉넉하게, 슬롯은 **하나만** 남긴다.
        long baselineSlots = positionRepository.countPoolSlots(-1L);
        limits.setTotalCapKrw(new BigDecimal("1000000000000"));
        limits.setMaxConcurrentPositions((int) baselineSlots + 1);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        long approved = raceTwo(a, "KRW-DOGE", b, "KRW-ADA", invest, errors);

        System.out.printf("[슬롯 한도] 승인=%d건 기존슬롯=%d 상한=%d 거절사유=%s 오류=%s%n",
                approved, baselineSlots, baselineSlots + 1, rejectionCodes, errors);
        assertThat(errors).isEmpty();
        assertThat(approved)
                .as("🔴 슬롯이 하나 남았으면 하나만 승인한다 — 체결 전 포지션(size=0)도 슬롯을 차지한다")
                .isEqualTo(1L);
        assertThat(rejectionCodes).containsExactly(CapitalReservationService.SLOT_LIMIT);
        assertThat(positionRepository.countPoolSlots(-1L))
                .as("점유 슬롯이 상한을 넘지 않는다")
                .isLessThanOrEqualTo(baselineSlots + 1);
    }

    @Test
    @DisplayName("배정 합이 계좌 잔고를 넘은 상태에서 두 세션이 동시에 진입해도 초과 승인이 없다")
    void concurrentEntriesAgainstSharedCap() throws Exception {
        // 🔴 **실제 위험 시나리오.** 세션별 가용 KRW 검사는 각자 통과한다(각 3,000 보유, 3,000 매수).
        //    그런데 **배정 합(6,000)이 거래소 계좌 잔고(5,000)를 넘은 상태**다 — drift 다. 이때
        //    두 세션이 동시에 들어오면 계좌를 초과해 예약한다.
        //
        //    🔴 1차 시도에서 쓴 시나리오(각 2,500 보유, 3,000 매수)는 **실제 경로에서 재현되지
        //    않는다.** `executeSessionBuy` 가 그 앞에서 "가용 자금 부족" 으로 막는다. 그 시나리오로
        //    잡은 '승인 2건' 은 세션별 검사를 우회해 만든 것이었고, 공유 풀 초과의 증거가 아니다.
        //    고쳐 적는다.
        Long a = session("KRW-DOGE", new BigDecimal("3000.00"));
        Long b = session("KRW-ADA", new BigDecimal("3000.00"));
        BigDecimal invest = new BigDecimal("3000.00");

        BigDecimal capBefore = new BigDecimal("5000.00");
        // 🔴 계좌 여력 = 총잔고 − 열린 매수 약정. 다른 시험이 남긴 약정 위에 5,000 을 남긴다.
        Mockito.doReturn(new com.cryptoautotrader.core.portfolio.PortfolioManager.CapitalSnapshot(openCommitmentBaseline().add(capBefore), Instant.now()))
                .when(portfolioManager).getCapitalSnapshot();

        assertThat(sessionRepository.sumAvailableKrwByStatusIn(ACTIVE_SESSION_STATUSES))
                .as("배정 합은 6,000 이고 계좌 잔고 5,000 을 넘는다 — 이것이 drift 다")
                .isEqualByComparingTo(new BigDecimal("6000.00"));

        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> approvals = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Thread t1 = new Thread(() -> {
            try {
                approvals.add(checkThenReserve(a, "KRW-DOGE", invest, go));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                approvals.add(checkThenReserve(b, "KRW-ADA", invest, go));
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join(40_000);
        t2.join(40_000);

        long approved = approvals.stream().filter(Boolean::booleanValue).count();
        BigDecimal reserved = invest.multiply(BigDecimal.valueOf(approved));

        System.out.printf("[시험 11] 승인=%d건 예약합계=%s 상한=%s 오류=%s%n",
                approved, reserved, capBefore, errors);

        assertThat(errors).as("예약 경로에서 예외가 나지 않아야 한다").isEmpty();
        assertThat(rejectionCodes)
                .as("거절은 계좌 여력에서 나와야 한다 — 등록 한도는 넉넉하다")
                .containsExactly(CapitalReservationService.ACCOUNT_HEADROOM);

        // 🔴 **닫는 조건**: 예약 합계가 상한을 넘지 않고, 초과 승인이 0건이어야 한다.
        assertThat(reserved)
                .as("🔴 예약 합계가 계좌 잔고를 넘으면 거래소에 있는 돈보다 많이 주문하는 것이다. "
                        + "🔴 한쪽이 잔고 부족으로 거절되기를 기대할 수 없다 — 계좌에 여유가 있으면 "
                        + "초과 주문도 접수되고, 그 응답이 유실되면 초과분이 **불명으로 남는다**"
                        + "(선행 조건 3·5 가 맞물리는 지점이다)")
                .isLessThanOrEqualTo(capBefore);
        assertThat(approved)
                .as("🔴 초과 승인 0건 — 6,000 은 계좌 잔고 5,000 을 넘으므로 **한쪽만** 승인돼야 한다. "
                        + "두 쪽 모두 승인되면 예약 경계에 직렬화가 없다는 뜻이다")
                .isEqualTo(1L);
    }
}
