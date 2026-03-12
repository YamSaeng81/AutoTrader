package com.cryptoautotrader.api.repository.paper;

import com.cryptoautotrader.api.entity.paper.PaperOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaperOrderRepository extends JpaRepository<PaperOrderEntity, Long> {

    Page<PaperOrderEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM PaperOrderEntity o WHERE o.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM PaperOrderEntity o WHERE o.sessionId IN :sessionIds")
    void deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}
