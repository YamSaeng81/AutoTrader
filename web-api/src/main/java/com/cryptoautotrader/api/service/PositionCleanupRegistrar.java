package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.repository.PositionCleanupRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 정지 후 정리 대기 등록 (V87) — <b>자기 트랜잭션에서 바로 커밋한다.</b>
 *
 * <ul>
 *   <li>🔴 정지의 바깥 트랜잭션에서 넣으면 그 행 잠금을 정지가 끝날 때까지 쥔다. 같은 포지션을 등록하려는
 *       체결 회계는 세션 행을 쥔 채 그 잠금을 기다리고, 정지의 안쪽 갱신(세션 상태)은 그 세션 행을
 *       기다린다 — 같은 스레드의 바깥·안쪽 사이에 걸린 대기라 DB 가 교착으로 잡지 못한다(§8).</li>
 *   <li>🔴 동시 등록의 키 충돌은 "이미 등록됨" 이다. 그 실패가 호출자(정지·체결 회계)의 트랜잭션을 롤백
 *       전용으로 만들지 않도록 여기서 흡수한다.</li>
 *   <li>체결 회계 트랜잭션과 따로 커밋되므로, 회계가 롤백돼도 등록은 남을 수 있다. 무해하다 — 정리 작업은
 *       포지션 수량이 0 이고 매수가 확인 전이면 기다린다.</li>
 * </ul>
 */
@Component
@Slf4j
public class PositionCleanupRegistrar {

    private final PositionCleanupRepository repository;
    private final TransactionTemplate requiresNew;

    public PositionCleanupRegistrar(PositionCleanupRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 이번 호출이 새로 등록했으면 {@code true} */
    public boolean register(Long positionId, String sessionKind, Long sessionId, String reason) {
        try {
            Integer n = requiresNew.execute(st ->
                    repository.registerIfAbsent(positionId, sessionKind, sessionId, reason, Instant.now()));
            return n != null && n == 1;
        } catch (DataIntegrityViolationException e) {
            log.debug("정리 대기 동시 등록 — 이미 등록됐다 (posId={})", positionId);
            return false;
        }
    }
}
