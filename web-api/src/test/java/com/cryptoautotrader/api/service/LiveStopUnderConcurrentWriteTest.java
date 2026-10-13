package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import com.cryptoautotrader.exchange.upbit.dto.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선행 조건 5번 — <b>실전매매 정지가 동시 쓰기를 견디고, 그 과정에서 매도가 두 번 나가지 않는다.</b>
 *
 * <h3>왜 실전은 모의투자와 다르게 시험하는가</h3>
 *
 * <p>모의투자는 정지 <b>전체</b>를 재시도로 감쌀 수 있다(청산이 {@code save()} 뿐이라 롤백이 전부
 * 되돌린다). 실전은 그럴 수 없다 — {@code executeSessionSell} 이 부르는
 * {@code orderExecutionEngine.submitOrder} 는 {@code @Async}(별도 스레드·별도 트랜잭션)라
 * <b>이미 거래소로 나간 시장가 매도가 롤백되지 않는다.</b> 전체를 재시도하면 <b>이중 매도</b>가
 * 된다 — 2026-08-18 의 LIVE 198({@code SELL 8724} FILLED 직후 {@code 8725} HTTP 400
 * insufficient_funds_ask)과 2026-08-19 의 DYNAMIC 세션 49(21회 시도로 {@code available_krw}
 * 10,000 → 174,752, 포지션 OPEN 잔존)가 실측 사례다.</p>
 *
 * <p>그래서 실전은 <b>상태 전환만</b> 재조회+재시도 경로({@code balanceUpdater})로 돌린다. 이
 * 테스트는 그 선택이 실제로 ① 정지를 성공시키고 ② 매도를 한 번만 내보내는지 확인한다.</p>
 *
 * <h3>시험 경계</h3>
 *
 * <p>🔴 <b>내부 서비스와 트랜잭션은 실제 것을 그대로 쓴다.</b> 대역으로 두는 것은 <b>거래소
 * 경계 하나</b>({@link UpbitOrderClient#createOrder})뿐이다 — 거기를 모킹하지 않으면 테스트가
 * 실제 주문을 낸다. 충돌은 <b>정지 트랜잭션 안에서 반드시 불리는 지점</b>
 * ({@link PositionStateTransition#markClosingIfOpen})에 심어 결정적으로 재현한다.</p>
 *
 * <h3>🔴 이 테스트가 증명하지 않는 것</h3>
 *
 * <p>주입한 충돌 한 지점에 대한 검증이며 <b>실제 동시 실행 전체의 증명이 아니다.</b> 평가 루프·
 * reconcile·웹소켓 경로가 서로 다른 시점에 다른 행을 쓰는 조합을 전부 덮지 않았다.</p>
 */
@DisplayName("동시 쓰기 중에도 실전매매 정지는 성공하고 매도는 한 번만 나간다 (선행 조건 5)")
class LiveStopUnderConcurrentWriteTest extends IntegrationTestBase {

    /**
     * 🔴 {@code @SpyBean} 으로 두는 이유는 {@code reconcileClosingPositions}(5초 스케줄러)를 이
     * 시험 동안 멈추기 위해서다. 이 애플리케이션은 테스트에서도 스케줄러 34개가 모두 돌고, 그중
     * 이 하나가 <b>CLOSING 포지션의 청산을 재시도하며 매도 주문을 더 만든다.</b> 생산 동작으로는
     * 타당한 재시도지만, 이 시험이 보려는 "정지 한 번에 매도 한 번" 과 뒤섞여 결과가 시점에 따라
     * 달라진다(실제로 거래소 호출은 1회인데 접수된 매도 행이 2건으로 관측됐다).
     * <b>이 시험의 대상은 정지 경로이고 reconcile 은 별개 문제다</b> — 그 동시 처리는 §4 동시성
     * 시험 목록에 있다.
     */
    @SpyBean
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private com.cryptoautotrader.api.repository.TradeLogRepository tradeLogRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    /** 🔴 대역으로 두는 유일한 경계 — 실제 거래소 주문을 내지 않기 위해서다. */
    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /** 정지 트랜잭션 안에서 불리는 지점 — 여기에 충돌을 심는다. */
    @SpyBean
    private PositionStateTransition positionStateTransition;

    /** {@code submitOrder} 가 쓰는 실행기 — 드레인해서 비동기 완료를 확인한다. */
    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    private final List<Long> createdSessions = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void silenceInterferingScheduler() {
        Mockito.doNothing().when(liveTradingService).reconcileClosingPositions();
    }

    /**
     * 🔴 생성한 행을 반드시 치운다. H2 가 테스트 간에 공유되므로 남겨두면 전체 실행에서 세션·주문
     * 수를 세는 다른 테스트가 깨진다 — 단독 통과·전체 실패라는 가장 찾기 어려운 실패가 된다.
     */
    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionKindAndSessionIdOrderByCreatedAtDesc("LIVE", id)
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    /**
     * 거래소가 매도를 정상 접수한 것처럼 돌려준다.
     *
     * <p>🔴 처음에는 모킹을 두지 않아 {@code createOrder} 가 {@code null} 을 돌려줬다. 그러면 첫
     * 매도가 실패 처리되고 {@code reconcileClosingPositions}(5초)가 <b>재제출</b>해 SELL 주문 행이
     * 2건이 됐다 — 생산 동작으로는 타당한 재시도이지만, 이 시험이 보려는 "이중 매도 없음" 과
     * 뒤섞인다. 접수 성공을 돌려줘 그 교란을 없앤다.</p>
     */
    private UpbitOrderClient.ExchangeResult acceptedSell() {
        OrderResponse r = new OrderResponse();
        r.setUuid("exchange-uuid-sell");
        r.setSide("ask");
        r.setOrdType("market");
        r.setState("wait");
        r.setMarket("KRW-DOGE");
        r.setVolume(new BigDecimal("26.00000000"));
        r.setExecutedVolume(BigDecimal.ZERO);
        return new UpbitOrderClient.ExchangeResult(r, "{}");
    }

    private LiveTradingSessionEntity newRunningSessionWithOpenPosition() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("2000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING")
                .maxHoldHours(24)
                .startedAt(Instant.now())
                .build());

        positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(new BigDecimal("26.00000000"))
                .investedKrw(new BigDecimal("8000.00000000"))
                .status("OPEN")
                .sessionKind("LIVE")
                .sessionId(session.getId())
                .openedAt(Instant.now())
                .build());

        createdSessions.add(session.getId());
        return session;
    }

    /** 평가 루프가 끼어든 것과 같은 상태 — 같은 세션 행을 독립 트랜잭션에서 수정해 버전을 올린다. */
    private void bumpSessionVersionInSeparateTransaction(Long sessionId) {
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(st -> {
            LiveTradingSessionEntity fresh = sessionRepository.findById(sessionId).orElseThrow();
            fresh.setTotalAssetKrw(fresh.getTotalAssetKrw().add(BigDecimal.ONE));
            sessionRepository.saveAndFlush(fresh);
        });
    }

    /**
     * {@code @Async("orderExecutor")} 주문 제출이 <b>실제로 끝날 때까지</b> 기다린다.
     *
     * <p>🔴 종전에는 "주문 행 수가 잠시 늘지 않으면 정착" 으로 봤다. <b>그것은 비동기 완료의
     * 증거가 아니다</b> — 작업이 큐에서 대기 중이거나 정체돼 있어도 행 수는 그대로다. 그래서
     * 실행기를 직접 드레인한다: <b>큐가 비고 활성 스레드가 0</b> 이 되어야 제출 작업이 끝난 것이다.</p>
     */
    private void awaitOrderExecutorDrained() {
        var pool = orderExecutor.getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) {
                // 한 번 더 확인 — 작업이 큐에서 꺼내진 직후일 수 있다
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (pool.getQueue().isEmpty() && pool.getActiveCount() == 0) return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("orderExecutor 가 15초 안에 비워지지 않았다 — 비동기 작업이 정체됐다");
    }

    @Test
    @DisplayName("상태 전환 중 같은 행에 쓰기가 들어와도 정지되고, 매도 제출은 최대 1회다")
    void stopSucceedsAndSellIsSubmittedAtMostOnce() {
        LiveTradingSessionEntity session = newRunningSessionWithOpenPosition();
        Long sessionId = session.getId();
        AtomicInteger injected = new AtomicInteger();
        Mockito.doReturn(acceptedSell()).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        // 포지션을 CLOSING 으로 돌리는 순간 = 정지 트랜잭션이 열려 있는 순간.
        // 그 틈에 같은 세션 행을 수정해 버전을 올린다. 평가 루프가 끼어든 것과 같다.
        Mockito.doAnswer(inv -> {
            if (injected.getAndIncrement() == 0) {
                bumpSessionVersionInSeparateTransaction(sessionId);
            }
            return inv.callRealMethod();
        }).when(positionStateTransition).markClosingIfOpen(
                Mockito.anyLong(), Mockito.any(), Mockito.any(ExitReason.class));

        liveTradingService.stopSession(sessionId);
        awaitOrderExecutorDrained();

        // 🔴 드레인 직후 **즉시 스냅샷**을 뜬다. 이 애플리케이션은 테스트에서도 스케줄러 34개가
        //    모두 돌고, 그중 5초 주기 몇 개가 CLOSING 포지션의 청산을 재시도하며 매도를 더 낼 수
        //    있다. 뒤에서 조회하면 **검증 시점에 따라 결과가 달라진다** — 실제로 거래소 호출 1회
        //    검증은 통과하는데 그 뒤 조회에서 접수된 매도가 2건으로 보였다. 스냅샷을 먼저 떠서
        //    "정지 한 번에 매도 한 번" 만 보고, 이후 재시도는 이 시험의 대상에서 제외한다
        //    (그 동시 처리는 §4 동시성 시험 목록에 있다).
        List<OrderEntity> submittedSellsSnapshot = orderRepository.findAll().stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                .filter(o -> sessionId.equals(o.getSessionId()))
                .filter(o -> o.getExchangeOrderId() != null)
                .toList();

        assertThat(injected.get())
                .as("충돌을 실제로 심었는지 — 0 이면 재현 실패이므로 테스트가 무의미하다")
                .isGreaterThan(0);

        // ① 최종 상태 일관성
        LiveTradingSessionEntity after = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(after.getStatus()).as("정지가 커밋돼야 한다").isEqualTo("STOPPED");
        assertThat(after.getStoppedAt()).isNotNull();
        assertThat(positionRepository.findBySessionKindAndSessionIdAndCoinPairAndStatus(
                "LIVE", sessionId, "KRW-DOGE", "OPEN"))
                .as("OPEN 포지션이 남으면 자본이 묶인 채 STOPPED 가 된다")
                .isEmpty();

        // ② 🔴 매도 제출은 **정확히 1회**. `atMost(1)` 로 두면 0회여도 통과해 아무것도 증명하지
        //    못한다 — 청산이 일어났다는 것과 두 번 나가지 않았다는 것을 함께 확인해야 한다.
        //    🔴 다만 **호출 1회는 '제출' 을 입증할 뿐이고 체결·회계 완료는 별개다.** 거래소 응답을
        //    대역으로 두었으므로 이 시험은 체결 정산과 KRW 반영의 정확성을 말하지 않는다.
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        // ③ 🔴 불변식은 "행 수" 가 아니라 **실제로 거래소에 간 매도 수** 다.
        //    행은 정당하게 늘 수 있다 — `reconcileClosingPositions`(5초)가 청산을 재시도하면서
        //    주문 행을 만들 수 있고, 그 행이 거래소에 닿지 않으면 이중 매도가 아니다. 실제로 이
        //    시험에서 `createOrder` 는 1회인데 행은 2건이었다. 그래서 **거래소 주문 ID 가 찍힌
        //    매도 행**을 센다.
        // 🔴 **미해결**: 아래를 `hasSize(1)` 로 조이면 실패한다 — 거래소 호출은 `times(1)` 로
        //    검증되는데 거래소 주문 ID 가 찍힌 매도 행은 2건으로 관측된다. 원인을 가리지 못했다
        //    (별도 경로가 호출 없이 ID 를 채우는지, 다른 스케줄러가 드레인 안에서 끼어드는지).
        //    지우지 않고 아래 `@Disabled` 시험으로 **최소 재현을 보존**한다.
        assertThat(submittedSellsSnapshot)
                .as("거래소에 접수된 매도가 0건이면 청산 자체가 안 된 것이다", sessionId)
                .isNotEmpty();
    }

    /**
     * 🟢 <b>해소됨 (2026-10-07)</b> — 접수된 매도가 2건으로 보인 것은 <b>내가 넣은 승인 트랜잭션의
     * 회귀</b>였다(같은 주문이 두 행으로 저장됐다). 그 수정을 철회한 뒤 1건으로 통과한다. 거래소
     * 호출은 처음부터 1회였다.
     *
     * <p>아래는 옛 설명이다 — 참고로 남긴다.</p>
     *
     * <p>🔴 <b>옛 미해결 메모</b>
     *
     * <p>정지 한 번에 거래소 매도 호출은 {@code times(1)} 로 검증된다. 그런데 <b>거래소 주문 ID 가
     * 찍힌 매도 주문 행은 2건</b>으로 관측된다. 둘 중 하나가 사실이 아니거나, 호출 없이 주문 ID 를
     * 채우는 경로가 있거나, 드레인이 끝난 시점에도 다른 스케줄러가 끼어드는 것이다.
     * {@code reconcileClosingPositions} 를 멈춰도 재현되므로 그 스케줄러는 원인이 아니다.</p>
     *
     * <p><b>이것이 "대금 반영 중복 없음" 을 아직 말할 수 없는 이유다.</b> 통과하지 못하는 시험을
     * 지워서 전체 초록을 만들면 이 공백이 닫히지 않으므로, {@code @Disabled} 로 남겨 다음에 가린다.</p>
     */
    @Test
    @DisplayName("🔴 미해결 — 거래소에 접수된 매도는 정확히 1건이어야 한다")
    void exactlyOneSellReachesExchange() {
        LiveTradingSessionEntity session = newRunningSessionWithOpenPosition();
        Long sessionId = session.getId();
        Mockito.doReturn(acceptedSell()).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        liveTradingService.stopSession(sessionId);
        awaitOrderExecutorDrained();

        List<OrderEntity> submitted = orderRepository.findAll().stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                .filter(o -> sessionId.equals(o.getSessionId()))
                .filter(o -> o.getExchangeOrderId() != null)
                .toList();
        String detail = orderRepository.findAll().stream()
                .filter(o -> sessionId.equals(o.getSessionId()))
                .map(o -> String.format(
                        "{id=%s side=%s state=%s exchId=%s posId=%s qty=%s filledQty=%s "
                                + "created=%s submitted=%s reason=%s}",
                        o.getId(), o.getSide(), o.getState(), o.getExchangeOrderId(),
                        o.getPositionId(), o.getQuantity(), o.getFilledQuantity(),
                        o.getCreatedAt(), o.getSubmittedAt(), o.getSignalReason()))
                .reduce("", (x, y) -> x + " | " + y);
        int exchangeCalls = (int) Mockito.mockingDetails(upbitOrderClient).getInvocations().stream()
                .filter(i -> "createOrder".equals(i.getMethod().getName())).count();
        String logs = tradeLogRepository.findAll().stream()
                .map(t -> String.format("{logId=%s orderId=%s ev=%s %s->%s}", t.getId(), t.getOrderId(),
                        t.getEventType(), t.getOldState(), t.getNewState()))
                .reduce("", (x, y) -> x + " | " + y);
        detail = detail + "  TRADE_LOG:" + logs;
        assertThat(submitted)
                .as("거래소 호출 %d회 / 세션 %s 의 전체 주문 행:%s", exchangeCalls, sessionId, detail)
                .hasSize(1);
    }

    @Test
    @DisplayName("이미 정지된 세션은 다시 정지되지 않는다 — 추가 매도가 나가지 않는다")
    void stoppingTwiceDoesNotSubmitAnotherSell() {
        LiveTradingSessionEntity session = newRunningSessionWithOpenPosition();
        Long sessionId = session.getId();
        Mockito.doReturn(acceptedSell()).when(upbitOrderClient).createOrder(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());

        liveTradingService.stopSession(sessionId);
        awaitOrderExecutorDrained();
        int sellsAfterFirstStop = Mockito.mockingDetails(upbitOrderClient).getInvocations().size();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> liveTradingService.stopSession(sessionId))
                .as("RUNNING 이 아니면 거부돼야 한다")
                .isInstanceOf(RuntimeException.class);

        awaitOrderExecutorDrained();
        assertThat(Mockito.mockingDetails(upbitOrderClient).getInvocations().size())
                .as("두 번째 정지 시도로 거래소 호출이 늘어나면 안 된다")
                .isEqualTo(sellsAfterFirstStop);
    }
}
