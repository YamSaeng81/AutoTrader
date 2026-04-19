package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.TradeLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;

@Repository
public interface TradeLogRepository extends JpaRepository<TradeLogEntity, Long> {

    /** 특정 주문의 로그 내역 (최신순) */
    Page<TradeLogEntity> findByOrderIdOrderByCreatedAtDesc(Long orderId, Pageable pageable);

    /** 전체 거래 로그 (최신순) */
    Page<TradeLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 특정 기간 내 FILL 이벤트의 실현 손익 합계 조회.
     * detail_json 에 realizedPnl 필드가 저장되어 있다고 가정.
     */
    @Query(value = "SELECT COALESCE(SUM(CAST(detail_json->>'realizedPnl' AS NUMERIC)), 0) " +
            "FROM trade_log " +
            "WHERE event_type = 'FILL' AND created_at >= :since",
            nativeQuery = true)
    BigDecimal sumRealizedPnlSince(@Param("since") Instant since);

    /**
     * 특정 기간 내 FILL 이벤트 중 손실(realizedPnl &lt; 0)만 합산.
     *
     * <p>리스크 체크에서 "이익이 손실을 상쇄해 손실률 0으로 보이는" 문제를 방지하기 위해
     * 순손익(net PnL) 대신 총손실(gross loss)로 구간 손실률을 계산하는 데 사용한다.
     * 반환값은 항상 0 또는 음수.</p>
     */
    @Query(value = "SELECT COALESCE(SUM(pnl), 0) FROM (" +
            "  SELECT CAST(detail_json->>'realizedPnl' AS NUMERIC) AS pnl " +
            "  FROM trade_log " +
            "  WHERE event_type = 'FILL' AND created_at >= :since" +
            ") t WHERE pnl < 0",
            nativeQuery = true)
    BigDecimal sumRealizedLossSince(@Param("since") Instant since);
}
