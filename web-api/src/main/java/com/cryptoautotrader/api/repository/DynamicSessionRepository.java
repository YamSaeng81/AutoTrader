package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface DynamicSessionRepository extends JpaRepository<DynamicSessionEntity, Long> {

    List<DynamicSessionEntity> findByStatus(String status);

    List<DynamicSessionEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT COUNT(s) FROM DynamicSessionEntity s WHERE s.status = 'RUNNING'")
    long countRunning();

    /**
     * 공유 풀 배정 검사 (2026-10-12) — 지정 세션을 뺀 활성 <b>실자금</b> 동적 세션의 initialCapital 합.
     * 🔴 PAPER 는 실자금이 아니므로 세지 않는다.
     */
    @Query("SELECT COALESCE(SUM(s.initialCapital), 0) FROM DynamicSessionEntity s "
            + "WHERE s.status IN :statuses AND s.tradingMode <> 'PAPER' AND s.id <> :excludeId")
    BigDecimal sumRealInitialCapitalExcluding(@Param("statuses") List<String> statuses,
                                              @Param("excludeId") Long excludeId);

    /** 계좌 배정 검사 (2026-10-12) — 지정 세션을 뺀 활성 실자금 동적 세션의 availableKrw 합. */
    @Query("SELECT COALESCE(SUM(s.availableKrw), 0) FROM DynamicSessionEntity s "
            + "WHERE s.status IN :statuses AND s.tradingMode <> 'PAPER' AND s.id <> :excludeId")
    BigDecimal sumRealAvailableKrwExcluding(@Param("statuses") List<String> statuses,
                                            @Param("excludeId") Long excludeId);

    /**
     * 신규 진입 승인·claim 의 정지 판정 (2026-10-12) — {@code LiveTradingSessionRepository.lockAndReadStatus}
     * 의 동적 세션판. 행을 배타 잠금으로 읽어 정지 쓰기와 직렬화한다.
     *
     * <p>엔티티가 아니라 상태 문자열만 돌려준다 — 틱 트랜잭션이 이미 같은 엔티티를 관리하고 있을 수
     * 있고, 잠금만 필요하다.</p>
     */
    @Query(value = "SELECT status FROM dynamic_session WHERE id = :id FOR UPDATE", nativeQuery = true)
    java.util.Optional<String> lockAndReadStatus(@Param("id") Long id);
}
