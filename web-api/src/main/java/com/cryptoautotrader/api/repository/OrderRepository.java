package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** 특정 상태들에 해당하는 주문 조회 (활성 주문 폴링용) */
    List<OrderEntity> findByStateIn(List<String> states);

    /** 특정 포지션의 주문 내역 (최신순) */
    List<OrderEntity> findByPositionIdOrderByCreatedAtDesc(Long positionId);

    /** 다중 포지션의 주문 내역 일괄 조회 (청산 경로 분류용 — N+1 방지) */
    List<OrderEntity> findByPositionIdIn(List<Long> positionIds);

    /** 전체 주문 페이징 조회 (최신순) */
    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 거래소 주문 ID로 조회 */
    Optional<OrderEntity> findByExchangeOrderId(String exchangeOrderId);

    /** 특정 코인+방향+상태의 주문 존재 여부 (중복 주문 방지 — 전역) */
    boolean existsByCoinPairAndSideAndStateIn(String coinPair, String side, List<String> states);

    /** 특정 세션+코인+방향+상태의 주문 존재 여부 (중복 주문 방지 — 세션 단위) */
    boolean existsBySessionIdAndCoinPairAndSideAndStateIn(Long sessionId, String coinPair, String side, List<String> states);

    /**
     * 특정 세션 종류+세션+코인+방향+상태의 주문 존재 여부 (중복 주문 방지 — 세션 단위, kind 구분).
     * live_trading_session과 dynamic_session의 sessionId가 우연히 같을 때 서로의 주문을
     * 활성 주문으로 오인해 상호 차단하는 것을 방지한다 (2026-07-02).
     */
    boolean existsBySessionKindAndSessionIdAndCoinPairAndSideAndStateIn(
            String sessionKind, Long sessionId, String coinPair, String side, List<String> states);

    /** 특정 세션의 주문 내역 (최신순, 페이징) */
    Page<OrderEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    /** 세션 종류 + ID의 주문 내역 (최신순, 페이징) — D-2 sessionId 충돌 방지 */
    Page<OrderEntity> findBySessionKindAndSessionIdOrderByCreatedAtDesc(
            String sessionKind, Long sessionId, Pageable pageable);

    /** 세션 종류 + ID의 전체 주문 내역 (최신순) — D-2 sessionId 충돌 방지 */
    List<OrderEntity> findBySessionKindAndSessionIdOrderByCreatedAtDesc(String sessionKind, Long sessionId);

    /** 날짜 범위 주문 조회 (최신순, 페이징) */
    Page<OrderEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant from, Instant to, Pageable pageable);

    /** 세션 + 날짜 범위 주문 조회 (최신순, 페이징) */
    Page<OrderEntity> findBySessionIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long sessionId, Instant from, Instant to, Pageable pageable);

    /** 다중 세션 주문 조회 (최신순, 페이징) */
    Page<OrderEntity> findBySessionIdInOrderByCreatedAtDesc(List<Long> sessionIds, Pageable pageable);

    /** 다중 세션 + 날짜 범위 주문 조회 (최신순, 페이징) */
    Page<OrderEntity> findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtDesc(List<Long> sessionIds, Instant from, Instant to, Pageable pageable);

    /** 특정 세션의 특정 상태 주문 조회 */
    List<OrderEntity> findBySessionIdAndStateIn(Long sessionId, List<String> states);

    // ── CSV export 용 (페이징 없이 전체 조회) ──────────────────────────────

    /** 세션 전체 주문 (최신순) */
    List<OrderEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    /** 날짜 범위 주문 (최신순) */
    List<OrderEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant from, Instant to);

    /** 세션 + 날짜 범위 주문 (최신순) */
    List<OrderEntity> findBySessionIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long sessionId, Instant from, Instant to);

    /** 다중 세션 주문 (최신순) */
    List<OrderEntity> findBySessionIdInOrderByCreatedAtDesc(List<Long> sessionIds);

    /** 다중 세션 + 날짜 범위 주문 (최신순) */
    List<OrderEntity> findBySessionIdInAndCreatedAtBetweenOrderByCreatedAtDesc(List<Long> sessionIds, Instant from, Instant to);

    /** 실전매매 세션에 연결된 활성 주문 수 카운트 (session_id가 있는 것만) */
    long countBySessionIdIsNotNullAndStateIn(List<String> states);

    /**
     * 세션 종류별 활성 주문 수 카운트 — 실전매매(LIVE) 요약에 동적 세션(DYNAMIC) 주문이
     * 섞여 집계되는 것을 막는다. session_id가 없는 수동/전역 주문은 제외.
     */
    long countBySessionKindAndSessionIdIsNotNullAndStateIn(String sessionKind, List<String> states);

    /**
     * 운영 건전성 점검용 — {@code order_id_seq.last_value}와 실제 {@code MAX(id)}의 차이.
     * 정상은 0. 양수면 INSERT 후 트랜잭션 롤백으로 id만 소비되고 행이 사라진 경우
     * (2026-07-29·2026-07-31 P0가 이 패턴이었다) — 매도/매수 후처리가 조용히 실패했다는 신호다.
     *
     * <p>⚠️ Postgres 전용(named sequence 의존) — H2 테스트 환경에서는 예외가 발생하므로
     * 호출부({@code OperationalHealthCheckService})가 try-catch로 감싸 "확인 불가"로 처리한다.</p>
     */
    @Query(value = "SELECT (SELECT last_value FROM order_id_seq) - COALESCE((SELECT MAX(id) FROM \"order\"), 0)",
            nativeQuery = true)
    Long findOrderSequenceGap();
    /**
     * 정산 반영 — <b>정산 버전이 일치할 때만</b> 한 UPDATE 로 누적값과 출처를 함께 갱신한다
     * (V81·V82, 2026-10-12).
     *
     * <p>🔴 <b>동시 반영을 한쪽만 통과시키는 가드다.</b> 0 을 돌려받으면 그사이 다른 경로가 이미
     * 반영한 것이므로 <b>호출부는 아무것도 하지 않아야 한다</b> — 그렇지 않으면 같은 차액이 두 번
     * 잔액·포지션에 들어간다.</p>
     *
     * <p>🔴 이 가드는 <b>동시 쓰기만</b> 막는다. <b>응답의 최신성은 판단하지 못한다</b> — 오래된
     * 응답이 최신 버전을 읽고 통과할 수 있으므로, 누적 단조성·종결 역행 금지를 호출부에서 먼저 건다.</p>
     *
     * @return 1 이면 이 호출이 반영 권한을 얻었다. 0 이면 얻지 못했다.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET "
            + "o.reportedExecutedFunds = :reportedFunds, "
            + "o.reportedPaidFee = :reportedFee, "
            + "o.appliedExecutedFunds = :appliedFunds, "
            + "o.appliedPaidFee = :appliedFee, "
            + "o.feeSource = :feeSource, "
            + "o.settlementVersion = o.settlementVersion + 1, "
            + "o.settlementAttempts = o.settlementAttempts + 1, "
            + "o.settlementNextCheckAt = :nextCheckAt, "
            + "o.settlementFinal = :settlementFinal "
            + "WHERE o.id = :id AND o.settlementVersion = :expectedVersion")
    int applySettlementIfVersionMatches(@Param("id") Long id,
                                        @Param("expectedVersion") Long expectedVersion,
                                        @Param("reportedFunds") java.math.BigDecimal reportedFunds,
                                        @Param("reportedFee") java.math.BigDecimal reportedFee,
                                        @Param("appliedFunds") java.math.BigDecimal appliedFunds,
                                        @Param("appliedFee") java.math.BigDecimal appliedFee,
                                        @Param("feeSource") String feeSource,
                                        @Param("nextCheckAt") java.time.Instant nextCheckAt,
                                        @Param("settlementFinal") Boolean settlementFinal);

    /**
     * 정산 <b>일정만</b> 갱신한다 — 반영할 차분이 없을 때 쓴다 (2026-10-12).
     *
     * <p>🔴 <b>정산 버전을 올리지 않는다.</b> 버전은 "반영이 일어났다" 는 가드이므로, 차분이 없는
     * 회차에 올리면 동시에 들어온 <b>진짜 반영</b>이 이유 없이 경합에서 밀린다. 올리는 것은 조회
     * 횟수와 다음 조회 시각, 그리고 확정 여부뿐이다.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET "
            + "o.settlementAttempts = o.settlementAttempts + 1, "
            + "o.settlementNextCheckAt = :nextCheckAt, "
            + "o.settlementFinal = :settlementFinal "
            + "WHERE o.id = :id AND o.settlementVersion = :expectedVersion")
    int touchSettlementSchedule(@Param("id") Long id,
                                 @Param("expectedVersion") Long expectedVersion,
                                 @Param("nextCheckAt") java.time.Instant nextCheckAt,
                                 @Param("settlementFinal") Boolean settlementFinal);

    /**
     * 정산 재조회 대상 — <b>"정산이 아직 확정되지 않았다"</b> 가 조건이다.
     *
     * <p>🔴 "보고값 ≠ 반영값" 으로 고르지 않는다. 둘이 같거나 둘 다 {@code null} 이어도 나중에
     * 체결금액·수수료가 도착할 수 있다. {@code fee_source IS NULL}(전환 이전 데이터)은 제외한다.</p>
     */
    @Query("SELECT o FROM OrderEntity o WHERE o.settlementFinal = false AND o.feeSource IS NOT NULL "
            + "AND (o.settlementNextCheckAt IS NULL OR o.settlementNextCheckAt <= :now) "
            + "AND o.exchangeOrderId IS NOT NULL")
    List<OrderEntity> findSettlementDue(@Param("now") java.time.Instant now);
    /**
     * 체결 회계 반영 — 주문 쪽 갱신을 <b>한 UPDATE</b> 로, <b>정산 버전이 일치할 때만</b> 수행한다
     * (V83, 2026-10-12).
     *
     * <p>상태·체결수량·체결가(평균단가)·보고값·반영값·출처를 함께 바꾼다. 🔴 0 을 돌려받으면 그사이
     * 다른 경로가 반영한 것이므로 <b>호출부는 포지션·잔액을 건드리지 않아야 한다.</b></p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET "
            + "o.state = 'FILLED', "
            + "o.filledQuantity = :volume, "
            + "o.price = :avgPrice, "
            + "o.filledAt = CASE WHEN o.filledAt IS NULL THEN :now ELSE o.filledAt END, "
            + "o.reportedExecutedFunds = :funds, "
            + "o.reportedPaidFee = :fee, "
            + "o.appliedExecutedFunds = :funds, "
            + "o.appliedPaidFee = :fee, "
            + "o.feeSource = :feeSource, "
            + "o.settlementVersion = o.settlementVersion + 1, "
            + "o.settlementAttempts = o.settlementAttempts + 1, "
            + "o.settlementNextCheckAt = :nextCheckAt "
            + "WHERE o.id = :id AND o.settlementVersion = :expectedVersion")
    int applyBuyFillIfVersionMatchesInternal(@Param("id") Long id,
                                             @Param("expectedVersion") Long expectedVersion,
                                             @Param("funds") java.math.BigDecimal funds,
                                             @Param("fee") java.math.BigDecimal fee,
                                             @Param("volume") java.math.BigDecimal volume,
                                             @Param("avgPrice") java.math.BigDecimal avgPrice,
                                             @Param("feeSource") String feeSource,
                                             @Param("nextCheckAt") Instant nextCheckAt,
                                             @Param("now") Instant now);

    default int applyBuyFillIfVersionMatches(Long id, Long expectedVersion,
                                              java.math.BigDecimal funds, java.math.BigDecimal fee,
                                              java.math.BigDecimal volume, java.math.BigDecimal avgPrice,
                                              String feeSource, Instant nextCheckAt) {
        return applyBuyFillIfVersionMatchesInternal(id, expectedVersion, funds, fee, volume,
                avgPrice, feeSource, nextCheckAt, Instant.now());
    }

    /** 회계 반영 대기 해제. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.fillAccountingPending = false WHERE o.id = :id")
    int clearFillAccountingPending(@Param("id") Long id);

    /**
     * 🔴 그 세션에 <b>회계 미반영 체결</b>이 있는가 — 있으면 신규 매수를 막는다 (V83).
     *
     * <p>포지션 수량이 아직 0 이라 슬롯 점유로 보이지 않으므로, 평가 루프가 "포지션 없음" 으로 보고
     * 재진입하는 것을 이 조건으로 막는다. <b>추적과 위험 축소(청산·손절)는 막지 않는다.</b></p>
     */
    @Query("SELECT COUNT(o) FROM OrderEntity o WHERE o.sessionKind = :kind AND o.sessionId = :sessionId "
            + "AND o.fillAccountingPending = true")
    long countFillAccountingPending(@Param("kind") String kind, @Param("sessionId") Long sessionId);
}
