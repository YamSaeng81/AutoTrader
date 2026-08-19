package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSellSettlementEntity;
import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSellSettlementRepository;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * DynamicSessionEntity 잔고 안전 업데이트 헬퍼 — {@link SessionBalanceUpdater}의 동적 세션판.
 *
 * <p>스케줄러 풀(8 threads)에서 tick/reconcile 경로가 동시에 같은 동적 세션을 수정할 때
 * last-write-wins 덮어쓰기로 잔고가 드리프트하는 것을 낙관적 락(@Version) + 재시도로 방지한다.
 * DynamicSessionEntity 와 LiveTradingSessionEntity 는 별도 테이블(별도 BIGSERIAL)이라
 * SessionBalanceUpdater 를 그대로 재사용할 수 없다.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DynamicSessionBalanceUpdater {

    private static final int MAX_RETRIES = 12;
    private static final long INITIAL_BACKOFF_MS = 5;

    private final DynamicSessionRepository dynamicSessionRepository;
    private final DynamicSellSettlementRepository settlementRepository;
    private final PlatformTransactionManager txManager;
    private TransactionTemplate requiresNewTx;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @PostConstruct
    void initTxTemplate() {
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DynamicSessionEntity apply(Long sessionId, Consumer<DynamicSessionEntity> mutator) {
        ConcurrencyFailureException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return applyOnce(sessionId, mutator);
            } catch (ConcurrencyFailureException e) {
                last = e;
                long base = INITIAL_BACKOFF_MS << Math.min(attempt, 8);
                long backoff = base + ThreadLocalRandom.current().nextLong(base + 1);
                log.warn("[DynamicSessionBalanceUpdater] 낙관적 락 충돌 — sessionId={} attempt={} backoff={}ms",
                        sessionId, attempt + 1, backoff);
                if (meterRegistry != null) {
                    Counter.builder("dynamic.session.balance.race.retry")
                            .description("동적 세션 낙관적 락 충돌로 인한 잔고 업데이트 재시도 횟수")
                            .register(meterRegistry)
                            .increment();
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("재시도 대기 중 인터럽트", ie);
                }
            }
        }
        log.error("[DynamicSessionBalanceUpdater] {}회 재시도 실패 — sessionId={}", MAX_RETRIES, sessionId);
        throw last;
    }

    private DynamicSessionEntity applyOnce(Long sessionId, Consumer<DynamicSessionEntity> mutator) {
        return requiresNewTx.execute(status -> {
            DynamicSessionEntity session = dynamicSessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalStateException("동적 세션을 찾을 수 없음 id=" + sessionId));
            mutator.accept(session);
            return dynamicSessionRepository.saveAndFlush(session);
        });
    }

    // ── 매도 정산: 포지션당 정확히 한 번 ──────────────────────────────────────

    /**
     * 매도 정산을 <b>멱등하게</b> 반영한다 — 같은 {@code settlement.orderRef} 로 다시 불러도
     * 잔고는 한 번만 바뀐다.
     *
     * <p>{@link #apply}는 {@code REQUIRES_NEW} 라 <b>바깥 트랜잭션이 롤백돼도 살아남는다</b>.
     * 매도 경로에서는 이게 곧 결함이 된다: 포지션을 CLOSED 로 바꾸는 일은 바깥 트랜잭션에 있어
     * 롤백과 함께 사라지는데 대금만 남기 때문이다. 포지션이 OPEN 으로 되돌아오면 다음 시도가
     * 또 대금을 지급한다 (2026-08-19 운영 세션 49: 21회 중복, 10,000 → 174,752).</p>
     *
     * <p>그래서 "이미 정산했다" 는 표식을 <b>대금 반영과 같은 트랜잭션</b>에 쓴다. 둘이 함께
     * 커밋되므로 표식은 대금과 정확히 같은 수명을 갖는다 — 대금이 남으면 표식도 남고,
     * 표식이 롤백되면 대금도 롤백된다. 이 동일 수명이 멱등성의 근거다.</p>
     *
     * @return 이번 호출이 실제로 반영했으면 {@code true}, 이미 정산돼 있어 건너뛰었으면 {@code false}
     */
    public boolean applySettlementOnce(DynamicSellSettlementEntity settlement,
                                       Consumer<DynamicSessionEntity> mutator) {
        if (settlement.getOrderRef() == null || settlement.getOrderRef().isBlank()) {
            throw new IllegalArgumentException(
                    "매도 정산 키가 없다 — 멱등성을 보장할 수 없으므로 거부한다 (posId="
                            + settlement.getPositionId() + ")");
        }
        ConcurrencyFailureException lastRace = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return applySettlementOnceInTx(settlement, mutator);
            } catch (DataIntegrityViolationException dup) {
                // PK 충돌 — 다른 스레드가 방금 같은 매도를 정산했다. 이 REQUIRES_NEW 트랜잭션만
                // 롤백되므로 대금은 반영되지 않았다. 중복 방지가 의도대로 동작한 것이다.
                log.warn("[DynamicSessionBalanceUpdater] 매도 정산 중복 차단 (orderRef={}, posId={})",
                        settlement.getOrderRef(), settlement.getPositionId());
                countDuplicate();
                return false;
            } catch (ConcurrencyFailureException e) {
                lastRace = e;
                backoff(settlement.getSessionId(), attempt);
            }
        }
        log.error("[DynamicSessionBalanceUpdater] 매도 정산 {}회 재시도 실패 — orderRef={}",
                MAX_RETRIES, settlement.getOrderRef());
        throw lastRace;
    }

    private boolean applySettlementOnceInTx(DynamicSellSettlementEntity settlement,
                                            Consumer<DynamicSessionEntity> mutator) {
        return Boolean.TRUE.equals(requiresNewTx.execute(status -> {
            if (settlementRepository.existsById(settlement.getOrderRef())) {
                log.warn("[DynamicSessionBalanceUpdater] 이미 정산된 매도 — 잔고 반영 생략 "
                                + "(orderRef={}, posId={}). 바깥 트랜잭션이 롤백된 뒤의 재시도로 보인다.",
                        settlement.getOrderRef(), settlement.getPositionId());
                countDuplicate();
                return false;
            }
            // 표식을 먼저 flush 한다 — 같은 트랜잭션 안이라 아래 잔고 변경과 함께 커밋되고,
            // 동시 진입한 스레드는 여기서 PK 충돌로 걸린다.
            settlementRepository.saveAndFlush(settlement);

            DynamicSessionEntity session = dynamicSessionRepository.findById(settlement.getSessionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "동적 세션을 찾을 수 없음 id=" + settlement.getSessionId()));
            mutator.accept(session);
            dynamicSessionRepository.saveAndFlush(session);
            return true;
        }));
    }

    private void backoff(Long sessionId, int attempt) {
        long base = INITIAL_BACKOFF_MS << Math.min(attempt, 8);
        long wait = base + ThreadLocalRandom.current().nextLong(base + 1);
        log.warn("[DynamicSessionBalanceUpdater] 낙관적 락 충돌 — sessionId={} attempt={} backoff={}ms",
                sessionId, attempt + 1, wait);
        if (meterRegistry != null) {
            Counter.builder("dynamic.session.balance.race.retry")
                    .description("동적 세션 낙관적 락 충돌로 인한 잔고 업데이트 재시도 횟수")
                    .register(meterRegistry)
                    .increment();
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", ie);
        }
    }

    private void countDuplicate() {
        if (meterRegistry != null) {
            Counter.builder("dynamic.session.sell.settlement.duplicate")
                    .description("이미 정산된 매도의 중복 반영을 막은 횟수 — 0이 아니면 롤백 재시도가 일어나고 있다")
                    .register(meterRegistry)
                    .increment();
        }
    }
}
