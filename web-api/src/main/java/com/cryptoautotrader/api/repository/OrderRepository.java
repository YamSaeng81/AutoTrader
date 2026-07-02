package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
