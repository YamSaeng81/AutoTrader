package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
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

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    /** 🔴 대역으로 두는 유일한 경계 — 실제 거래소 주문을 내지 않기 위해서다. */
    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /** 정지 트랜잭션 안에서 불리는 지점 — 여기에 충돌을 심는다. */
    @SpyBean
    private PositionStateTransition positionStateTransition;

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

    /** {@code @Async} 주문 제출이 끝날 때까지 기다린다 — sleep 고정이 아니라 조건 폴링이다. */
    private void awaitAsyncSettled() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            // 주문 행이 생겼고 더 이상 늘지 않으면 정착된 것으로 본다
            long before = orderRepository.count();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (orderRepository.count() == before && before > 0) return;
        }
    }

    @Test
    @DisplayName("상태 전환 중 같은 행에 쓰기가 들어와도 정지되고, 매도 제출은 최대 1회다")
    void stopSucceedsAndSellIsSubmittedAtMostOnce() {
        LiveTradingSessionEntity session = newRunningSessionWithOpenPosition();
        Long sessionId = session.getId();
        AtomicInteger injected = new AtomicInteger();

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
        awaitAsyncSettled();

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
        Mockito.verify(upbitOrderClient, Mockito.times(1)).createOrder(
                Mockito.eq("KRW-DOGE"), Mockito.eq("ask"),
                Mockito.any(), Mockito.any(), Mockito.any());

        // ③ 대금 반영 중복 없음 — 같은 포지션에 SELL 주문 행이 둘 이상 생기면 중복 청산이다
        List<?> sellOrders = orderRepository.findAll().stream()
                .filter(o -> "SELL".equalsIgnoreCase(o.getSide()))
                .filter(o -> sessionId.equals(o.getSessionId()))
                .toList();
        assertThat(sellOrders)
                .as("세션 %s 의 SELL 주문 행 — 0건이면 청산이 안 된 것이고 2건 이상이면 이중 매도다", sessionId)
                .hasSize(1);
    }

    @Test
    @DisplayName("이미 정지된 세션은 다시 정지되지 않는다 — 추가 매도가 나가지 않는다")
    void stoppingTwiceDoesNotSubmitAnotherSell() {
        LiveTradingSessionEntity session = newRunningSessionWithOpenPosition();
        Long sessionId = session.getId();

        liveTradingService.stopSession(sessionId);
        awaitAsyncSettled();
        int sellsAfterFirstStop = Mockito.mockingDetails(upbitOrderClient).getInvocations().size();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> liveTradingService.stopSession(sessionId))
                .as("RUNNING 이 아니면 거부돼야 한다")
                .isInstanceOf(RuntimeException.class);

        awaitAsyncSettled();
        assertThat(Mockito.mockingDetails(upbitOrderClient).getInvocations().size())
                .as("두 번째 정지 시도로 거래소 호출이 늘어나면 안 된다")
                .isEqualTo(sellsAfterFirstStop);
    }
}
