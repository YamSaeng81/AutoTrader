package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** 특정 상태들에 해당하는 주문 조회 (활성 주문 폴링용) */
    List<OrderEntity> findByStateIn(List<String> states);

    /** 특정 포지션의 주문 내역 (최신순) */
    List<OrderEntity> findByPositionIdOrderByCreatedAtDesc(Long positionId);

    /** 전체 주문 페이징 조회 (최신순) */
    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 거래소 주문 ID로 조회 */
    Optional<OrderEntity> findByExchangeOrderId(String exchangeOrderId);

    /** 특정 코인+방향+상태의 주문 존재 여부 (중복 주문 방지) */
    boolean existsByCoinPairAndSideAndStateIn(String coinPair, String side, List<String> states);

    /** 특정 세션의 주문 내역 (최신순, 페이징) */
    Page<OrderEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    /** 특정 세션의 특정 상태 주문 조회 */
    List<OrderEntity> findBySessionIdAndStateIn(Long sessionId, List<String> states);

    /** 실전매매 세션에 연결된 활성 주문 수 카운트 (session_id가 있는 것만) */
    long countBySessionIdIsNotNullAndStateIn(List<String> states);
}
