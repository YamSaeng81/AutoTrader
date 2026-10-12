package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface LiveTradingSessionRepository extends JpaRepository<LiveTradingSessionEntity, Long> {

    /** 특정 상태의 세션 조회 */
    List<LiveTradingSessionEntity> findByStatus(String status);

    /** 전체 세션 최신순 조회 */
    List<LiveTradingSessionEntity> findAllByOrderByCreatedAtDesc();

    /** 특정 상태의 세션 수 */
    long countByStatus(String status);

    /** 활성 세션들의 initialCapital 합산 (Tier 2 §8 — 계좌 초과 배정 방지) */
    @Query("SELECT COALESCE(SUM(s.initialCapital), 0) FROM LiveTradingSessionEntity s WHERE s.status IN :statuses")
    BigDecimal sumInitialCapitalByStatusIn(@Param("statuses") List<String> statuses);

    /** 공유 풀 배정 검사 (2026-10-12) — 지정 세션을 뺀 활성 세션의 initialCapital 합. */
    @Query("SELECT COALESCE(SUM(s.initialCapital), 0) FROM LiveTradingSessionEntity s "
            + "WHERE s.status IN :statuses AND s.id <> :excludeId")
    BigDecimal sumInitialCapitalExcluding(@Param("statuses") List<String> statuses,
                                          @Param("excludeId") Long excludeId);

    /** 계좌 배정 검사 (2026-10-12) — 지정 세션을 뺀 활성 세션의 availableKrw 합. */
    @Query("SELECT COALESCE(SUM(s.availableKrw), 0) FROM LiveTradingSessionEntity s "
            + "WHERE s.status IN :statuses AND s.id <> :excludeId")
    BigDecimal sumAvailableKrwExcluding(@Param("statuses") List<String> statuses,
                                        @Param("excludeId") Long excludeId);

    /** 활성 세션들의 availableKrw 합산 (Tier 2 §8 — 거래소 잔고 drift 감지) */
    @Query("SELECT COALESCE(SUM(s.availableKrw), 0) FROM LiveTradingSessionEntity s WHERE s.status IN :statuses")
    BigDecimal sumAvailableKrwByStatusIn(@Param("statuses") List<String> statuses);

    /** 모의→실전 승격 검사용 — 특정 타입·상태의 세션 조회 */
    List<LiveTradingSessionEntity> findBySessionTypeAndStatus(String sessionType, String status);
    /**
     * 세션 행을 <b>배타 잠금</b>으로 걸고 <b>상태 문자열만</b> 읽는다 (2026-10-07 신설).
     *
     * <p>🔴 왜 필요한가 — <b>늦은 신규 제출</b>을 막는다. 평가 루프가 세션을 {@code RUNNING} 으로
     * 읽고 매수를 결정한 뒤, 그 제출이 <b>정지 커밋 이후에</b> 거래소에 닿는 일이 실제로 일어났다
     * ({@code LateOrderSubmissionAfterStopTest} 로 재현). 그러면 사람은 정지시켰다고 보는데 새
     * 포지션이 생기고, 정지된 세션에는 평가 루프가 없으므로 <b>그 포지션을 청산할 주체가 없다.</b></p>
     *
     * <p>🔴 <b>"제출 직전에 RUNNING 을 한 번 더 읽는" 것으로는 부족하다</b> — 재확인과 제출 사이에도
     * 정지가 끼어들 수 있다. 그래서 DB 행 잠금으로 <b>순서를 실제로 강제한다</b>: 제출 승인이 이
     * 잠금을 쥐고 있는 동안 정지의 상태 쓰기는 블록되고, 정지가 먼저 커밋되면 승인 쪽이 잠금을 얻은
     * 시점에 {@code STOPPED} 를 보게 된다. 둘 중 하나만 성립한다.</p>
     *
     * <p><b>엔티티가 아니라 상태 문자열만 돌려주는 이유</b>. 처음에는
     * {@code @Lock(PESSIMISTIC_WRITE)} 로 엔티티를 읽었고, 그 뒤 정지가
     * {@code ObjectOptimisticLockingFailureException} 으로 실패했다
     * ({@code SubmitResponseUnknownTest}). 🔴 <b>그때 "엔티티를 관리하면 버전이 올라간다" 고 적은
     * 것은 성립하지 않는다</b> — 조회·관리 자체는 버전을 올리지 않는다. 실제로 UPDATE 를 일으킨
     * 경로는 <b>아직 찾지 못했고</b>, 네이티브 잠금으로 바꾼 뒤에도 그 실패는 재현된다. 즉 이
     * 형태는 <b>불필요한 엔티티 관리를 없앤다는 점에서만</b> 정당하고, 정지 실패의 원인을 고친
     * 것이 아니다.</p>
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT status FROM live_trading_session WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<String> lockAndReadStatus(
            @org.springframework.data.repository.query.Param("id") Long id);
}
