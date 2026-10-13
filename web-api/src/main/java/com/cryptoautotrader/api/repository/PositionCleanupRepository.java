package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.PositionCleanupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 정지 후 정리 대기 (V87). 모든 쓰기는 열 단위 조건부 문장이다 — 엔티티 저장으로 덮어쓰지 않는다. */
@Repository
public interface PositionCleanupRepository extends JpaRepository<PositionCleanupEntity, Long> {

    /**
     * 정리 대기로 등록한다 — 없을 때만. 🔴 직접 부르지 말고 {@code PositionCleanupRegistrar} 를 거친다
     * (동시 등록의 키 충돌을 자기 트랜잭션 안에서 흡수한다).
     *
     * <p>{@code ON CONFLICT} 대신 {@code NOT EXISTS} 다 — 시험 기반(H2)이 그 구문을 받지 않는다. 그래서
     * 동시 등록 둘이 모두 "없다" 를 보면 한쪽은 키 충돌로 실패한다. 그 실패는 "이미 등록됨" 과 같다.</p>
     *
     * @return 새로 등록했으면 1
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO position_cleanup (position_id, session_kind, session_id, reason, pending_at, attempts) "
            + "SELECT :positionId, :kind, :sessionId, :reason, :now, 0 "
            + "WHERE NOT EXISTS (SELECT 1 FROM position_cleanup WHERE position_id = :positionId)",
            nativeQuery = true)
    int registerIfAbsent(@Param("positionId") Long positionId, @Param("kind") String kind,
                         @Param("sessionId") Long sessionId, @Param("reason") String reason,
                         @Param("now") Instant now);

    @Query("SELECT c FROM PositionCleanupEntity c WHERE c.sessionKind = :kind AND c.doneAt IS NULL "
            + "ORDER BY c.pendingAt")
    List<PositionCleanupEntity> findPending(@Param("kind") String kind);

    @Query("SELECT COUNT(c) FROM PositionCleanupEntity c WHERE c.sessionKind = :kind "
            + "AND c.sessionId = :sessionId AND c.doneAt IS NULL")
    long countPending(@Param("kind") String kind, @Param("sessionId") Long sessionId);

    /** 시도를 기록한다. 완료가 아니다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PositionCleanupEntity c SET c.attempts = c.attempts + 1, c.lastAttemptAt = :now, "
            + "c.lastNote = :note WHERE c.positionId = :positionId AND c.doneAt IS NULL")
    int recordAttempt(@Param("positionId") Long positionId, @Param("now") Instant now,
                      @Param("note") String note);

    /** 대기 사유만 남긴다(시도 횟수는 올리지 않는다). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PositionCleanupEntity c SET c.lastNote = :note WHERE c.positionId = :positionId "
            + "AND c.doneAt IS NULL")
    int note(@Param("positionId") Long positionId, @Param("note") String note);

    /** 🔴 포지션이 CLOSED 로 확인됐을 때만 부른다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PositionCleanupEntity c SET c.doneAt = :now, c.lastNote = :note "
            + "WHERE c.positionId = :positionId AND c.doneAt IS NULL")
    int markDone(@Param("positionId") Long positionId, @Param("now") Instant now,
                 @Param("note") String note);
}
