package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
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
}
