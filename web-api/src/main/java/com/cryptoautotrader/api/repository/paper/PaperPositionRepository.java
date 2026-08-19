package com.cryptoautotrader.api.repository.paper;

import com.cryptoautotrader.api.entity.paper.PaperPositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaperPositionRepository extends JpaRepository<PaperPositionEntity, Long> {

    List<PaperPositionEntity> findBySessionId(Long sessionId);

    List<PaperPositionEntity> findBySessionIdAndStatus(Long sessionId, String status);

    Optional<PaperPositionEntity> findBySessionIdAndCoinPairAndStatus(Long sessionId, String coinPair, String status);

    @Modifying
    @Query("DELETE FROM PaperPositionEntity p WHERE p.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("DELETE FROM PaperPositionEntity p WHERE p.sessionId IN :sessionIds")
    void deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);

    /**
     * 세션별 실현 거래 집계 — kill criteria 판정용 (docs/KILL_CRITERIA.md §4.B).
     *
     * <p>실전/동적은 {@code public.position} 을 쓰지만 모의투자는 {@code paper_trading.position}
     * 이라 별도 집계가 필요하다. 2026-08-19 에 이 쿼리가 없어 <b>페이퍼 112세션이 판정 대상에서
     * 통째로 빠져 있었다</b> — 데이터를 만드는 곳과 판정하는 곳이 분리돼 있었다.</p>
     *
     * <p>{@code size > 0} 은 체결되지 않은 고아 포지션을 표본에서 제외한다
     * (public.position 의 {@code invested_krw > 0} 필터에 대응 — 페이퍼 테이블에는
     * invested_krw 컬럼이 없다).</p>
     *
     * <p>규칙 지문별로 나눠 집계한다(V71) — 규칙이 다르면 다른 표본이다.</p>
     *
     * <p>각 행: [sessionId(Long), tradeCount(Long), sumRealizedPnl(BigDecimal), winCount(Long),
     * rulesetHash(String, V71 이전 데이터는 null)]</p>
     */
    @Query("SELECT p.sessionId, COUNT(p), " +
           "       COALESCE(SUM(p.realizedPnl), 0), " +
           "       SUM(CASE WHEN p.realizedPnl > 0 THEN 1L ELSE 0L END), " +
           "       p.rulesetHash " +
           "FROM PaperPositionEntity p " +
           "WHERE p.status = 'CLOSED' AND p.size > 0 AND p.sessionId IS NOT NULL " +
           "GROUP BY p.sessionId, p.rulesetHash")
    List<Object[]> aggregateClosedTradesPerSession();
}
