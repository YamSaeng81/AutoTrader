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

    // -- V84 : 주문 의도 · claim · 응답 불명 ---------------------------------

    /**
     * claim — {@code INTENT_READY} → {@code SUBMITTING} <b>조건부</b> 전환 (V84).
     *
     * <p>🔴 <b>1행을 돌려받은 작업자만 외부로 한 번 제출한다.</b> 상태를 <b>읽은</b> 것과 이 전환에
     * <b>성공한</b> 것은 다르다 — 상태는 재기동 후에도 보이지만 전환 성공은 한 번만 일어난다.
     * 복구 작업자가 {@code SUBMITTING} 을 보고 재제출하면 같은 의도가 두 번 나간다.</p>
     *
     * <p>🔴 <b>1행을 돌려받았다고 제출해도 되는 것은 아니다.</b> 이 UPDATE 를 담은 트랜잭션은 이후
     * 단계 실패·커밋 실패로 롤백될 수 있고, 그때 제출하면 아무도 claim 하지 않은 주문이 나간다.
     * 그래서 외부 호출은 <b>이 트랜잭션이 커밋된 뒤에</b> 한다.</p>
     *
     * @return 전환된 행 수 — {@code 1} 이면 이 호출이 claim 을 얻었다
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.state = 'SUBMITTING', o.claimedAt = :now "
            + "WHERE o.id = :id AND o.state = 'INTENT_READY'")
    int claimForSubmission(@Param("id") Long id, @Param("now") Instant now);

    /** 식별자로 주문을 찾는다 — 응답 유실 주문의 추적 경로다. */
    java.util.Optional<OrderEntity> findByClientOrderId(String clientOrderId);

    /**
     * 미해결 제출 — {@code INTENT_READY} · {@code SUBMITTING} · {@code UNKNOWN}.
     *
     * <p>🔴 <b>재제출 대상이 아니라 조회 대상이다.</b> 복구는 식별자로 묻기만 한다.</p>
     */
    @Query("SELECT o FROM OrderEntity o WHERE o.state IN ('INTENT_READY', 'SUBMITTING', 'UNKNOWN') "
            + "AND (o.unknownProbedAt IS NULL OR o.unknownProbedAt <= :before) "
            + "ORDER BY o.id")
    List<OrderEntity> findUnresolvedSubmissions(@Param("before") Instant before);

    /**
     * 세션의 미해결 제출 건수 — 정지가 <b>정리 완료</b>를 표시할 수 있는지 판단한다.
     *
     * <p>🔴 하나라도 남아 있으면 정리 완료가 아니다. 그 주문은 접수됐을 수 있고, 그렇다면 장부에
     * 없는 포지션이 거래소에 있다.</p>
     */
    @Query("SELECT COUNT(o) FROM OrderEntity o WHERE o.sessionKind = :kind AND o.sessionId = :sessionId "
            + "AND o.state IN ('INTENT_READY', 'SUBMITTING', 'UNKNOWN')")
    long countUnresolvedSubmissions(@Param("kind") String kind, @Param("sessionId") Long sessionId);

    /**
     * 철회 — {@code INTENT_READY} → {@code FAILED} <b>조건부</b> 전환 (V84).
     *
     * <p>🔴 <b>claim 과 같은 행·같은 조건을 다툰다.</b> 철회를 무조건 쓰기로 하면 claim 과 철회가
     * <b>둘 다 성공</b>할 수 있다 — 제출은 나갔는데 주문은 철회된 상태가 된다. 두 전환이 모두
     * {@code WHERE state = 'INTENT_READY'} 를 걸므로 <b>정확히 한쪽만 1행을 돌려받는다.</b></p>
     *
     * <p>🔴 <b>시간은 경합을 해결하지 않는다.</b> 유예는 "작업 중일 가능성이 높은 의도를 건드리지
     * 말자" 는 예절이고, 작업이 유예보다 오래 지연될 수 있다. 중복을 막는 근거는 <b>이 조건부
     * 전환</b>이며, 유예는 불필요한 철회를 줄이는 것뿐이다.</p>
     *
     * @return 전환된 행 수 — {@code 1} 이면 이 호출이 철회에 성공했다(= claim 은 실패한다)
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.state = 'FAILED', o.failedReason = :reason "
            + "WHERE o.id = :id AND o.state = 'INTENT_READY'")
    int withdrawIntentIfStillReady(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 예약 원장 — <b>아직 결말이 나지 않은 주문이 붙잡고 있는 KRW 합계</b> (V85).
     *
     * <p>🔴 상한은 <b>거래소 계좌 잔고</b>이고, 이 합계가 그 상한을 넘지 않아야 한다. 세션별
     * 가용 KRW 검사는 "한 세션이 자기 배정을 넘지 않는가" 만 보므로, <b>여러 세션의 배정 합이
     * 계좌 잔고를 넘은 상태</b>(drift)에서는 각자 통과하고 계좌를 초과한다.</p>
     *
     * <p>🔴 <b>불명 주문도 센다.</b> 접수됐을 수 있으므로 그 돈은 묶인 것으로 본다.</p>
     */
    @Query("SELECT COALESCE(SUM(o.reservedKrw), 0) FROM OrderEntity o "
            + "WHERE o.reservedKrw IS NOT NULL AND o.state IN "
            + "('INTENT_READY', 'SUBMITTING', 'UNKNOWN', 'SUBMITTED', 'PARTIAL_FILLED')")
    java.math.BigDecimal sumOpenReservedKrw();

    /** 불명 조회 시도를 기록한다 — 🔴 상태는 바꾸지 않는다(자동 종결 금지). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderEntity o SET o.unknownProbeCount = o.unknownProbeCount + 1, "
            + "o.unknownProbedAt = :now WHERE o.id = :id")
    int touchUnknownProbe(@Param("id") Long id, @Param("now") Instant now);

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
