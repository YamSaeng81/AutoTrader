package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20260415_analy.md Tier 2 §7 — LiveTradingSession 낙관적 락 회귀 방지.
 *
 * <p>검증: 동시에 여러 스레드가 같은 세션의 {@code availableKrw} 를
 * {@code read-modify-write} 패턴으로 수정할 때 last-write-wins 덮어쓰기가 발생하지 않는다.</p>
 *
 * <p>@Version 이 없으면 {@code N * delta} 가 아닌 임의의 덮어쓰인 값이 남는다 (실제 잔고 드리프트).
 * @Version 이 있으면 {@link SessionBalanceUpdater#apply} 가 충돌 재시도로 모든 증분을 적용한다.</p>
 *
 * <p>{@link IntegrationTestBase} 를 상속하지만 {@code @Transactional} 은 쓰지 않는다 —
 * 병렬 스레드에서 동일 세션을 보도록 해야 하므로 각자 자체 트랜잭션으로 커밋·재조회해야 한다.
 * 테스트 말미에 수동 정리.</p>
 */
class SessionBalanceUpdaterTest extends IntegrationTestBase {

    @Autowired
    private SessionBalanceUpdater balanceUpdater;

    @Autowired
    private LiveTradingSessionRepository sessionRepository;

    @Test
    @DisplayName("동시 increment 10회 → 모든 증분이 보존된다 (last-write-wins 없음)")
    void 동시_잔고_증분이_모두_반영된다() throws Exception {
        LiveTradingSessionEntity session = sessionRepository.save(createSession());
        final Long sessionId = session.getId();
        final BigDecimal initial = session.getAvailableKrw();
        final BigDecimal delta = new BigDecimal("10000");
        final int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    balanceUpdater.apply(sessionId,
                            s -> s.setAvailableKrw(s.getAvailableKrw().add(delta)));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads finish in time").isTrue();
        pool.shutdownNow();

        LiveTradingSessionEntity reloaded = sessionRepository.findById(sessionId).orElseThrow();
        BigDecimal expected = initial.add(delta.multiply(BigDecimal.valueOf(threads)));

        assertThat(errors.get()).as("재시도 한도 내에서 모두 성공").isZero();
        assertThat(reloaded.getAvailableKrw())
                .as("증분 %d회가 모두 누적되어야 함 (기대=%s 실제=%s)",
                        threads, expected, reloaded.getAvailableKrw())
                .isEqualByComparingTo(expected);
        assertThat(reloaded.getVersion())
                .as("@Version 이 최소 %d 번 증가했어야 함", threads)
                .isGreaterThanOrEqualTo((long) threads);

        // 정리
        sessionRepository.deleteById(sessionId);
    }

    @Test
    @DisplayName("단일 스레드 mutation: version 증가 + 값 반영")
    void 단일_mutation_이_정상_반영된다() {
        LiveTradingSessionEntity session = sessionRepository.save(createSession());
        Long initialVersion = session.getVersion();
        Long id = session.getId();

        balanceUpdater.apply(id,
                s -> s.setAvailableKrw(s.getAvailableKrw().subtract(new BigDecimal("50000"))));

        LiveTradingSessionEntity reloaded = sessionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getAvailableKrw()).isEqualByComparingTo(new BigDecimal("950000"));
        assertThat(reloaded.getVersion()).isGreaterThan(initialVersion == null ? 0L : initialVersion);

        sessionRepository.deleteById(id);
    }

    private LiveTradingSessionEntity createSession() {
        return LiveTradingSessionEntity.builder()
                .strategyType("COMPOSITE_BREAKOUT")
                .coinPair("KRW-BTC")
                .timeframe("H1")
                .initialCapital(new BigDecimal("1000000"))
                .availableKrw(new BigDecimal("1000000"))
                .totalAssetKrw(new BigDecimal("1000000"))
                .status("RUNNING")
                .investRatio(new BigDecimal("0.8000"))
                .stopLossPct(new BigDecimal("5.0"))
                .startedAt(Instant.now())
                .build();
    }
}
