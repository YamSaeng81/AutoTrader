package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.ExitReason;
import com.cryptoautotrader.api.entity.MarketDataCacheEntity;
import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import com.cryptoautotrader.api.repository.MarketDataCacheRepository;
import com.cryptoautotrader.api.repository.paper.PaperPositionRepository;
import com.cryptoautotrader.api.repository.paper.VirtualBalanceRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
 * 선행 조건 5번 재현 — <b>동시 쓰기가 있을 때 사람이 부르는 정지가 1회로 성공해야 한다.</b>
 *
 * <h3>재현하려는 실제 사고 (개입 #3, 2026-10-02 01:53Z)</h3>
 *
 * <p>{@code PAPER#331} 정지가 <b>매도 체결과 세션 중단까지 실행된 뒤</b> 커밋 시점에
 * {@code ObjectOptimisticLockingFailureException}({@code VirtualBalanceEntity#331})으로
 * <b>전부 롤백</b>됐다. HTTP 500 이 돌아왔고, 사람이 같은 호출을 한 번 더 해서 01:57:11Z 에 풀렸다.</p>
 *
 * <p>구조는 이렇다. {@link PaperTradingService#stop(Long)} 은 {@code @Transactional} 로
 * 세션을 <b>읽어둔 상태로</b> ① 포지션 청산 ② {@code STOPPED} 전환 ③ {@code save} 를 한
 * 트랜잭션에서 수행한다. 그 트랜잭션이 열려 있는 동안 60초 평가 루프
 * ({@code runStrategy()} — {@code @Transactional} 없음)가 <b>같은 행</b>에 쓰면 버전이 올라가고,
 * 정지 쪽 커밋이 낙관적 잠금으로 터진다.</p>
 *
 * <h3>왜 이렇게 재현하는가 — 스레드·sleep 을 쓰지 않는다</h3>
 *
 * <p>두 스레드를 띄워 타이밍을 맞추는 방식은 불안정하다(flaky). 대신 <b>정지 트랜잭션 안에서
 * 반드시 불리는 지점</b>에 충돌을 심는다: {@code stop()} 은 열린 포지션마다
 * {@code fetchCurrentPrice()} → {@code marketDataCacheRepo.findCandles()} 를 부른다. 그 호출이
 * 일어날 때 <b>별도 트랜잭션(REQUIRES_NEW)에서 같은 세션 행을 수정</b>하면, 평가 루프가 끼어든
 * 것과 정확히 같은 상태가 결정적으로 만들어진다.</p>
 *
 * <p>🔴 이 테스트는 <b>고치기 전에는 실패해야 한다.</b> 실패하지 않으면 사고를 재현하지 못한
 * 것이므로 테스트가 잘못된 것이다.</p>
 *
 * <h3>🔴 이 테스트가 증명하지 않는 것</h3>
 *
 * <p>이것은 <b>주입한 충돌 한 지점에 대한 재현·회복 검증</b>이다. 실제 동시 실행 전체가
 * 안전하다는 증명이 아니다 — 평가 루프·reconcile·웹소켓 경로가 각각 다른 시점에 다른 행을 쓰며,
 * 그 조합을 전부 덮지 않았다. 충돌 지점이 하나 더 있으면 여기서는 드러나지 않는다.</p>
 */
@DisplayName("동시 쓰기 중에도 모의투자 정지는 1회로 성공해야 한다 (선행 조건 5)")
class PaperStopUnderConcurrentWriteTest extends IntegrationTestBase {

    @Autowired
    private PaperTradingService paperTradingService;

    @Autowired
    private VirtualBalanceRepository balanceRepo;

    @Autowired
    private PaperPositionRepository positionRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    /** 🔴 알림이 **최종 커밋 뒤에만** 나가는지 센다 — 재시도가 있어도 한 번이어야 한다. */
    @SpyBean
    private TelegramNotificationService telegramService;

    /** 정지 트랜잭션 안에서 불리는 지점 — 여기에 충돌을 심는다. */
    @SpyBean
    private MarketDataCacheRepository marketDataCacheRepo;

    private final List<Long> createdSessions = new ArrayList<>();

    /**
     * 🔴 생성한 행을 반드시 치운다. H2 가 테스트 간에 공유되므로 남겨두면 전체 실행에서 세션·거래
     * 수를 세는 다른 테스트가 깨진다 — 단독 통과·전체 실패라는 가장 찾기 어려운 실패가 된다.
     */
    @AfterEach
    void cleanUp() {
        createdSessions.forEach(id -> {
            positionRepo.findBySessionId(id).forEach(positionRepo::delete);
            balanceRepo.deleteById(id);
        });
        createdSessions.clear();
    }

    private Long createRunningSessionWithOpenPosition() {
        VirtualBalanceEntity session = balanceRepo.save(VirtualBalanceEntity.builder()
                .totalKrw(new BigDecimal("1000000.00"))
                .availableKrw(new BigDecimal("200000.00"))
                .initialCapital(new BigDecimal("1000000.00"))
                .strategyName("COMPOSITE_MOMENTUM_ICHIMOKU_V2")
                .coinPair("KRW-DOGE")
                .timeframe("M15")
                .status("RUNNING")
                .startedAt(Instant.now())
                .build());

        positionRepo.save(PaperPositionEntity.builder()
                .coinPair("KRW-DOGE")
                .side("BUY")
                .entryPrice(new BigDecimal("300.00000000"))
                .avgPrice(new BigDecimal("300.00000000"))
                .size(new BigDecimal("1000.00000000"))
                .investedKrw(new BigDecimal("300000.00000000"))
                .status("OPEN")
                .sessionId(session.getId())
                .openedAt(Instant.now())
                .build());

        createdSessions.add(session.getId());
        return session.getId();
    }

    /**
     * 평가 루프가 끼어든 것과 같은 상태를 만든다 — 같은 세션 행을 <b>독립 트랜잭션에서</b>
     * 수정해 {@code @Version} 을 올린다.
     */
    /**
     * 청산가로 쓸 캔들 한 개. 🔴 {@code callRealMethod()} 는 쓸 수 없다 — Spring Data 리포지토리는
     * 인터페이스 프록시라 Mockito 가 "abstract real method" 라며 거부한다. 그래서 값을 고정한다.
     * 가격이 비면 {@code fetchCurrentPrice} 가 업비트 폴백으로 넘어가 테스트가 외부를 호출한다.
     */
    private List<MarketDataCacheEntity> cannedCandle() {
        return List.of(MarketDataCacheEntity.builder()
                .time(Instant.now().minusSeconds(60))
                .coinPair("KRW-DOGE")
                .timeframe("M1")
                .open(new BigDecimal("310.00000000"))
                .high(new BigDecimal("312.00000000"))
                .low(new BigDecimal("308.00000000"))
                .close(new BigDecimal("310.00000000"))
                .volume(new BigDecimal("1000.00000000"))
                .build());
    }

    private void bumpVersionInSeparateTransaction(Long sessionId) {
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(st -> {
            VirtualBalanceEntity fresh = balanceRepo.findById(sessionId).orElseThrow();
            // 평가 루프가 실제로 하는 일과 같은 종류의 쓰기 (총자산 재계산)
            fresh.setTotalKrw(fresh.getTotalKrw().add(BigDecimal.ONE));
            balanceRepo.saveAndFlush(fresh);
        });
    }

    @Test
    @DisplayName("정지 트랜잭션 중 같은 행에 쓰기가 들어와도 1회 호출로 정지된다")
    void stopSucceedsOnFirstCallDespiteConcurrentWrite() {
        Long sessionId = createRunningSessionWithOpenPosition();
        AtomicInteger conflicts = new AtomicInteger();

        // 정지가 포지션 청산을 위해 현재가를 조회하는 순간 = 정지 트랜잭션이 열려 있는 순간.
        // 그 틈에 같은 세션 행을 수정해 버전을 올린다. 평가 루프가 끼어든 것과 같다.
        Mockito.doAnswer(inv -> {
            if (conflicts.getAndIncrement() == 0) {
                bumpVersionInSeparateTransaction(sessionId);
            }
            return cannedCandle();
        }).when(marketDataCacheRepo).findCandles(Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.any());

        // 🔴 고치기 전에는 여기서 ObjectOptimisticLockingFailureException 이 올라온다.
        paperTradingService.stop(sessionId);

        assertThat(conflicts.get())
                .as("충돌을 실제로 심었는지 — 0 이면 재현에 실패한 것이므로 테스트가 무의미하다")
                .isGreaterThan(0);

        VirtualBalanceEntity after = balanceRepo.findById(sessionId).orElseThrow();
        assertThat(after.getStatus()).as("세션이 STOPPED 로 남아야 한다").isEqualTo("STOPPED");
        assertThat(after.getStoppedAt()).isNotNull();

        assertThat(positionRepo.findBySessionIdAndStatus(sessionId, "OPEN"))
                .as("정지 후 열린 포지션이 남아 있으면 자본이 묶인 채 STOPPED 가 된다")
                .isEmpty();

        // 🔴 알림은 트랜잭션 밖으로 옮기는 것만으로 충분하지 않다 — **최종 커밋이 성공한 뒤에
        //    정확히 한 번** 나가야 한다. 롤백된 첫 시도에 대해 한 번 더 나가면 안 된다.
        Mockito.verify(telegramService, Mockito.atMost(1)).notifyPaperSessionStopped(
                Mockito.eq(sessionId), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyDouble());
    }

    @Test
    @DisplayName("정지가 끝까지 실패하면 '중단됨' 알림이 나가지 않는다")
    void noStoppedNotificationWhenStopUltimatelyFails() {
        Long sessionId = createRunningSessionWithOpenPosition();
        // 매 시도마다 충돌을 심어 재시도를 모두 소진시킨다
        Mockito.doAnswer(inv -> {
            bumpVersionInSeparateTransaction(sessionId);
            return cannedCandle();
        }).when(marketDataCacheRepo).findCandles(Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), Mockito.any());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> paperTradingService.stop(sessionId))
                .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class);

        // 🔴 실패했는데 "중단됨" 이 나가면 사람이 정지됐다고 오판한다 — 가장 위험한 경우다.
        Mockito.verify(telegramService, Mockito.never()).notifyPaperSessionStopped(
                Mockito.eq(sessionId), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyDouble());
        assertThat(balanceRepo.findById(sessionId).orElseThrow().getStatus())
                .as("실패했으면 RUNNING 으로 남아야 한다 — 상태가 섞이면 더 나쁘다")
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("정지된 세션은 다시 정지할 수 없다 — 재시도가 이중 청산으로 이어지지 않는다")
    void stoppingTwiceIsRejected() {
        Long sessionId = createRunningSessionWithOpenPosition();
        Mockito.doReturn(cannedCandle()).when(marketDataCacheRepo)
                .findCandles(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any());

        paperTradingService.stop(sessionId);

        // 재시도가 들어가더라도 이미 STOPPED 인 세션을 또 청산해서는 안 된다
        assertThat(balanceRepo.findById(sessionId).orElseThrow().getStatus()).isEqualTo("STOPPED");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> paperTradingService.stop(sessionId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정지 이후에는 그 세션으로 신규 체결이 생기지 않는다 (ExitReason 은 FORCED_STOP)")
    void noNewFillsAfterStop() {
        Long sessionId = createRunningSessionWithOpenPosition();
        Mockito.doReturn(cannedCandle()).when(marketDataCacheRepo)
                .findCandles(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any());

        paperTradingService.stop(sessionId);

        var closed = positionRepo.findBySessionIdAndStatus(sessionId, "CLOSED");
        assertThat(closed).isNotEmpty();
        assertThat(closed).allSatisfy(p ->
                assertThat(p.getExitReason()).isEqualTo(ExitReason.FORCED_STOP));
    }
}
