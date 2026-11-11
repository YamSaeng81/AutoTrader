package com.cryptoautotrader.api.repository.paper;

import com.cryptoautotrader.api.entity.paper.VirtualBalanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import java.util.List;

public interface VirtualBalanceRepository extends JpaRepository<VirtualBalanceEntity, Long> {

    List<VirtualBalanceEntity> findAllByOrderByIdDesc();

    long countByStatus(String status);

    List<VirtualBalanceEntity> findByStatusOrderByStartedAtAsc(String status);

    /** 저장된 MDD 피크(선택 모드 런타임 상태, V96) — 엔티티 캐시를 거치지 않고 커밋된 값을 읽는다. 없으면 null. */
    @Query(value = "SELECT mdd_peak_krw FROM paper_trading.virtual_balance WHERE id = :id", nativeQuery = true)
    BigDecimal findMddPeakKrw(@Param("id") Long id);

    /**
     * MDD 피크를 올리기만 한다 — 저장값보다 클 때만 쓴다(동시 갱신·늦은 낮은 값이 피크를 내리지 못한다). version 은 건드리지 않는다
     * (세션 행의 낙관적 잠금과 무관한 단조 증가 열). 별도 트랜잭션 — 호출자의 롤백이 이미 관측한 피크를 되돌리지 않는다.
     */
    @Modifying
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Query(value = "UPDATE paper_trading.virtual_balance SET mdd_peak_krw = :peak "
            + "WHERE id = :id AND (mdd_peak_krw IS NULL OR mdd_peak_krw < :peak)", nativeQuery = true)
    int raiseMddPeakKrw(@Param("id") Long id, @Param("peak") BigDecimal peak);
}
