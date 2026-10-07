package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.OrderEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.OrderRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import com.cryptoautotrader.exchange.upbit.UpbitOrderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선행 조건 5번 — <b>정지가 롤백된 뒤 재시도해도 KRW 복원은 정확히 한 번이어야 한다.</b>
 *
 * <h3>막으려는 사고</h3>
 *
 * <p>미체결 매수 포지션({@code size=0} + `FAILED` 매수 주문)의 KRW 복원은 가드가 있어도
 * <b>원자적이지 않으면 두 번 일어난다</b>:</p>
 *
 * <pre>
 * closeIfOpen(바깥 트랜잭션 참여) → 복원(REQUIRES_NEW, 독립 커밋) → 바깥 롤백
 *   → 포지션이 OPEN 으로 복귀 → 다음 시도가 다시 복원  ← 같은 KRW 가 두 번
 * </pre>
 *
 * <p>DYNAMIC 세션 49 가 이 유형으로 {@code available_krw} 10,000 → 174,752 까지 불어난 전례가 있다
 * (2026-08-19). 가드는 <b>동시 실행</b>을 막지만 <b>롤백</b>에는 무력하다 — 전환과 잔액 반영을 같은
 * 트랜잭션에 넣어야 한다.</p>
 *
 * <h3>재현 방법</h3>
 *
 * <p>복원이 끝난 <b>뒤</b> 정지 트랜잭션을 실패시킨다. 정지는 마지막에
 * {@code balanceUpdater.apply} 로 상태를 {@code STOPPED} 로 바꾸므로, 그 호출의 <b>첫 번째</b>만
 * 던지게 하면 복원 이후 지점에서 바깥이 깨진다. 그다음 정지를 다시 부른다.</p>
 *
 * <p>🔴 확인하는 것은 <b>잔액 증가가 한 번</b>인지다. 두 번이면 쓰이지 않은 돈이 생긴다.</p>
 *
 * <h3>🔴 이 시험의 한계 — 빨강/초록 대조가 성립하지 않았다</h3>
 *
 * <p>수정 전 코드에 이 시험을 돌려 실패를 확인하려 했으나 <b>다른 이유로 실패했다.</b> 수정 전에는
 * 복원도 {@code balanceUpdater.apply} 를 거치므로, 실패를 주입하는 지점이 <b>복원 자체</b>를
 * 깨뜨려 "복원은 되고 바깥만 롤백" 이라는 시나리오가 만들어지지 않았다. 그래서 <b>이 시험은 수정 후
 * 불변식(잔액 증가 1회)을 지키는지 확인할 뿐이고, 수정 전 결함을 재현해 보인 것은 아니다.</b>
 * 중복 복원 가능성의 근거는 코드 구조(전환은 바깥 트랜잭션 참여, 복원은 독립 커밋)와 DYNAMIC
 * 세션 49 의 실측 전례이며, 이 시험이 그것을 대체하지 않는다.</p>
 */
@DisplayName("정지가 롤백된 뒤 재시도해도 KRW 복원은 한 번이다 (선행 조건 5)")
class KrwRestoreRollbackRetryTest extends IntegrationTestBase {

    @Autowired
    private LiveTradingService liveTradingService;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private UpbitOrderClient upbitOrderClient;

    /** 정지 마지막의 상태 쓰기 — 여기서 한 번 던져 바깥 트랜잭션을 롤백시킨다. */
    @SpyBean
    private SessionBalanceUpdater balanceUpdater;

    private final List<Long> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            orderRepository.findBySessionIdAndStateIn(id,
                    List.of("PENDING", "SUBMITTED", "PARTIAL_FILLED", "FILLED", "CANCELLED", "FAILED"))
                    .forEach(orderRepository::delete);
            positionRepository.findBySessionKindAndSessionId("LIVE", id)
                    .forEach(positionRepository::delete);
            sessionRepository.deleteById(id);
        });
        createdSessions.clear();
    }

    @Test
    @DisplayName("복원 뒤 정지가 롤백되고 다시 정지해도 잔액은 한 번만 늘어난다")
    void restoreHappensExactlyOnceAcrossRollbackAndRetry() {
        LiveTradingSessionEntity session = sessionRepository.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL")
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE").timeframe("M5")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("7000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .status("RUNNING").startedAt(Instant.now()).build());
        Long sessionId = session.getId();
        createdSessions.add(sessionId);

        PositionEntity pos = positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-DOGE").side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(BigDecimal.ZERO)                       // 매수 미체결
                .investedKrw(new BigDecimal("3000.00000000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sessionId)
                .openedAt(Instant.now()).build());

        orderRepository.save(OrderEntity.builder()
                .coinPair("KRW-DOGE").side("BUY").orderType("MARKET")
                .quantity(new BigDecimal("3000.00000000"))
                .state("FAILED")                             // 복원 대상이 되는 상태
                .sessionKind("LIVE").sessionId(sessionId).positionId(pos.getId())
                .createdAt(Instant.now()).build());

        final BigDecimal before = new BigDecimal("7000.00");
        final BigDecimal expectedAfter = before.add(new BigDecimal("3000.00000000"));

        // 복원 이후 지점(정지 상태 쓰기)에서 한 번 던져 바깥 트랜잭션을 롤백시킨다.
        AtomicInteger statusWrites = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            if (statusWrites.getAndIncrement() == 0) {
                throw new IllegalStateException("의도적 실패 — 복원 이후 바깥 트랜잭션을 롤백시킨다");
            }
            return inv.callRealMethod();
        }).when(balanceUpdater).apply(Mockito.anyLong(), Mockito.any());

        // 1회차: 복원은 일어나고 정지는 실패한다
        try {
            liveTradingService.stopSession(sessionId);
        } catch (Exception expected) {
            // 정지 실패는 이 시험의 전제다
        }

        assertThat(statusWrites.get())
                .as("상태 쓰기 지점을 지나야 재현이 성립한다 — 0 이면 복원 이전에 끝난 것이다")
                .isGreaterThan(0);

        // 2회차: 다시 정지한다. 🔴 여기서 복원이 또 일어나면 안 된다.
        liveTradingService.stopSession(sessionId);

        LiveTradingSessionEntity after = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(after.getStatus()).as("재시도로 정지는 성립해야 한다").isEqualTo("STOPPED");
        assertThat(after.getAvailableKrw())
                .as("🔴 복원이 두 번 일어나면 쓰이지 않은 돈이 생긴다 (기대 %s)", expectedAfter)
                .isEqualByComparingTo(expectedAfter);

        assertThat(positionRepository.findById(pos.getId()).orElseThrow().getStatus())
                .as("포지션은 정리된 상태로 남아야 한다")
                .isEqualTo("CLOSED");
    }
}
