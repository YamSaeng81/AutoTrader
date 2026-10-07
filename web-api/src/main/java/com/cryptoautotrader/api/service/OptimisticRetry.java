package com.cryptoautotrader.api.service;

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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 낙관적 잠금 충돌이 나면 <b>트랜잭션 단위로 다시 시도</b>한다 (2026-10-07 신설).
 *
 * <h3>왜 필요한가 — 사람이 부른 정지가 실패했다</h3>
 *
 * <p>2026-10-02 01:53Z, {@code PAPER#331} 정지가 <b>매도 체결과 세션 중단까지 실행된 뒤</b>
 * 커밋에서 {@code ObjectOptimisticLockingFailureException}
 * ({@code VirtualBalanceEntity#331})으로 <b>전부 롤백</b>됐다. HTTP 500 이 돌아왔고 사람이 같은
 * 호출을 한 번 더 해서 01:57:11Z 에 풀렸다(개입 #3).</p>
 *
 * <p>구조는 이렇다. 정지는 {@code @Transactional} 안에서 세션을 <b>읽어둔 상태로</b> 포지션 청산 →
 * {@code STOPPED} 전환 → 저장을 수행한다. 그 트랜잭션이 열려 있는 동안 60초 평가 루프가 같은 행에
 * 쓰면 {@code @Version} 이 올라가고 정지 쪽 커밋이 터진다.</p>
 *
 * <p>🔴 <b>자동정지가 OFF 이므로 위험 경보 대응은 사람이 정지를 부른다.</b> 그 호출이 실패하면
 * 실전에서는 <b>"정지시켰다고 생각했는데 안 된" 상태</b>가 된다. 사람이 재시도를 기억해야 하는
 * 설계는 신뢰할 수 없으므로 호출 자체가 충돌을 흡수해야 한다
 * ({@code LIVE_ENTRY_PREREG.md} 선행 조건 5번).</p>
 *
 * <h3>경계를 트랜잭션 밖에 두는 이유</h3>
 *
 * <p>재시도는 <b>롤백된 트랜잭션 바깥에서</b> 일어나야 한다. 같은 트랜잭션 안에서 다시 시도하면
 * 이미 rollback-only 로 표시돼 있어 무엇을 해도 커밋되지 않는다. 그래서 이 클래스가
 * {@link TransactionTemplate}(REQUIRES_NEW)로 <b>매 시도마다 새 트랜잭션</b>을 열고, 호출부는
 * {@code @Transactional} 을 떼고 이 메서드를 쓴다.</p>
 *
 * <p>🔴 호출부 메서드에 {@code @Transactional} 을 남긴 채 같은 클래스에서 자기호출하면 Spring
 * 프록시를 타지 않아 애노테이션이 <b>조용히 무시된다</b>. {@code RulesetRegistry}·
 * {@link PositionStateTransition} 이 같은 이유로 {@code TransactionTemplate} 을 쓴다.</p>
 *
 * <h3>호출부가 지켜야 할 것</h3>
 * <ul>
 *   <li>작업은 <b>재시도해도 안전</b>해야 한다. 롤백으로 DB 상태는 되돌아가지만, 트랜잭션에
 *       참여하지 않는 부수 효과(텔레그램 발송, 인메모리 캐시 제거)는 되돌아가지 않는다 —
 *       <b>그런 부수 효과는 성공 뒤로 빼야 한다.</b></li>
 *   <li>재시도를 모두 소진하면 마지막 예외를 그대로 던진다. 조용히 삼키면 호출부가 정지에
 *       실패한 것을 모른다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OptimisticRetry {

    /** `SessionBalanceUpdater` 와 같은 값 — 정지는 드물게 일어나므로 넉넉히 둔다. */
    static final int MAX_ATTEMPTS = 8;
    private static final long INITIAL_BACKOFF_MS = 10;

    private final PlatformTransactionManager txManager;

    private TransactionTemplate requiresNewTx;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @PostConstruct
    void initTxTemplate() {
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * {@code work} 를 새 트랜잭션에서 실행하고, 낙관적 잠금 충돌이면 다시 시도한다.
     *
     * @param what 로그·메트릭에 쓸 작업 이름 (예: {@code "모의투자 정지 #331"})
     * @return 성공한 시도의 반환값
     * @throws ConcurrencyFailureException 재시도를 모두 소진했을 때 마지막 예외
     */
    public <T> T inNewTransaction(String what, TransactionCallback<T> work) {
        ConcurrencyFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T result = requiresNewTx.execute(work);
                if (attempt > 1) {
                    log.info("[OptimisticRetry] {} — {}회째 시도에서 성공", what, attempt);
                }
                return result;
            } catch (ConcurrencyFailureException e) {
                last = e;
                if (meterRegistry != null) {
                    Counter.builder("optimistic.retry")
                            .description("낙관적 잠금 충돌로 인한 재시도 횟수")
                            .register(meterRegistry)
                            .increment();
                }
                if (attempt == MAX_ATTEMPTS) break;
                // 지수 backoff + jitter — jitter 가 없으면 충돌한 스레드들이 동시에 깨어나 재충돌한다.
                long base = INITIAL_BACKOFF_MS << Math.min(attempt - 1, 6);
                long backoff = base + ThreadLocalRandom.current().nextLong(base + 1);
                log.warn("[OptimisticRetry] {} — 낙관적 잠금 충돌, {}회째 재시도까지 {}ms 대기",
                        what, attempt + 1, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("재시도 대기 중 인터럽트: " + what, ie);
                }
            }
        }
        log.error("[OptimisticRetry] {} — {}회 재시도 실패", what, MAX_ATTEMPTS);
        throw last;
    }
}
