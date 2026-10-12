package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
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
 * 20260415_analy.md Tier 2 §7 — LiveTradingSession 잔고 안전 업데이트 헬퍼.
 *
 * <p>Scheduler / WebSocket / reconcile / finalize 경로가 동시 실행될 때, 각 경로가
 * 제각기 {@code sessionRepository.findById → setAvailableKrw → save} 패턴으로 세션을 수정해
 * last-write-wins 덮어쓰기로 잔고가 드리프트하던 것을 차단한다.
 *
 * <h3>사용법</h3>
 * <pre>
 *   balanceUpdater.apply(sessionId, s -&gt; {
 *       s.setAvailableKrw(s.getAvailableKrw().subtract(investAmount));
 *   });
 * </pre>
 *
 * <h3>보장</h3>
 * <ul>
 *   <li>매 시도마다 세션을 DB에서 다시 읽어 최신 상태 위에서 mutation 을 적용한다 — 즉,
 *       mutator 람다 안에서 {@code session.getAvailableKrw()} 를 호출하면 항상 최신 값이 반환된다.
 *       호출부는 stale 스냅샷을 캡처하지 말 것.</li>
 *   <li>{@code @Version} 충돌 시 최대 {@value #MAX_RETRIES} 회까지 재시도한다.</li>
 *   <li>재시도 초과 시 마지막 예외를 그대로 던져 호출부가 실패를 감지하도록 한다 —
 *       조용히 삼키면 잔고 정합성 깨질 수 있음.</li>
 *   <li>각 시도는 REQUIRES_NEW 트랜잭션 — 호출부 트랜잭션과 독립적이므로 재시도 시 이전 시도의
 *       dirty state 가 남지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionBalanceUpdater {

    private static final int MAX_RETRIES = 12;
    private static final long INITIAL_BACKOFF_MS = 5;

    private final LiveTradingSessionRepository sessionRepository;
    private final PlatformTransactionManager txManager;
    private TransactionTemplate requiresNewTx;

    /** §16 — Micrometer 메트릭 */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @PostConstruct
    void initTxTemplate() {
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param sessionId 수정할 세션 ID
     * @param mutator   최신 세션 인스턴스를 받아 필드를 수정하는 람다
     * @return 저장 후 세션 (새로운 version 포함) — 호출부가 이어서 사용할 수 있음
     */
    /**
     * <b>현재 트랜잭션 안에서</b> 잔고를 갱신한다 (2026-10-12 신설).
     *
     * <p>🔴 {@link #apply} 는 REQUIRES_NEW 라 <b>독립 커밋</b>된다. 그것이 필요한 곳도 있지만,
     * <b>정산처럼 "주문·잔액·포지션이 전부 함께 커밋되거나 함께 롤백돼야" 하는 작업에서는 쓸 수
     * 없다</b> — 잔액만 커밋되면 바깥이 롤백돼도 되돌아가지 않아 재처리에서 같은 차액이 두 번
     * 들어간다. 2026-10-07 에 KRW 복원이 정확히 그 구조였고(가드가 롤백에는 무력했다) 그래서
     * 전환과 복원을 한 트랜잭션으로 묶었다.</p>
     *
     * <p>🔴 <b>재시도하지 않는다.</b> 현재 트랜잭션 안에서는 재시도해도 같은 스냅샷을 보므로
     * 의미가 없다. 낙관적 잠금 충돌이 나면 <b>그 트랜잭션 전체를 롤백시켜</b> 호출부가 다시
     * 시도하게 한다 — 그때 차분이 다시 계산되므로 중복 반영이 생기지 않는다.</p>
     *
     * <p>엔티티는 <b>이 트랜잭션 안에서 다시 읽는다</b>. 바깥에서 들고 온 인스턴스를 저장하면
     * 분리 엔티티 문제가 생긴다.</p>
     */
    public LiveTradingSessionEntity applyWithin(Long sessionId,
                                                 Consumer<LiveTradingSessionEntity> mutator) {
        LiveTradingSessionEntity fresh = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("세션을 찾을 수 없습니다: id=" + sessionId));
        mutator.accept(fresh);
        return sessionRepository.save(fresh);
    }

    public LiveTradingSessionEntity apply(Long sessionId, Consumer<LiveTradingSessionEntity> mutator) {
        ConcurrencyFailureException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return applyOnce(sessionId, mutator);
            } catch (ConcurrencyFailureException e) {
                last = e;
                // 지수 backoff + jitter — jitter 가 없으면 충돌 스레드들이 동시에 깨어나 재충돌한다.
                long base = INITIAL_BACKOFF_MS << Math.min(attempt, 8);
                long backoff = base + ThreadLocalRandom.current().nextLong(base + 1);
                log.warn("[SessionBalanceUpdater] 낙관적 락 충돌 — sessionId={} attempt={} backoff={}ms",
                        sessionId, attempt + 1, backoff);
                // §16 — race condition 재시도 카운터 (Prometheus: session_balance_race_retry_total)
                if (meterRegistry != null) {
                    Counter.builder("session.balance.race.retry")
                            .description("낙관적 락 충돌로 인한 잔고 업데이트 재시도 횟수")
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
        log.error("[SessionBalanceUpdater] {}회 재시도 실패 — sessionId={}", MAX_RETRIES, sessionId);
        throw last;
    }

    private LiveTradingSessionEntity applyOnce(Long sessionId, Consumer<LiveTradingSessionEntity> mutator) {
        // TransactionTemplate 을 써야 self-invocation 으로 @Transactional 이 우회되는 문제를 피할 수 있다.
        return requiresNewTx.execute(status -> {
            LiveTradingSessionEntity session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new IllegalStateException("세션을 찾을 수 없음 id=" + sessionId));
            mutator.accept(session);
            return sessionRepository.saveAndFlush(session);
        });
    }
}
