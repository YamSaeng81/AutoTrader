package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.LiveTradingSessionEntity;
import com.cryptoautotrader.api.entity.PositionEntity;
import com.cryptoautotrader.api.repository.LiveTradingSessionRepository;
import com.cryptoautotrader.api.repository.PositionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포지션 행의 <b>갱신 유실</b> (2026-10-16, 재시작 시험 S18b 이중 매도의 원인).
 *
 * <p>평가 루프가 회차 시작에 읽은 포지션 엔티티를 평가손익(표시용)만 바꿔 {@code save} 했다. 버전 열이 없어 행 전체가 그 시점 값으로 덮였고,
 * 그 사이 유령 정산이 커밋한 CLOSED·청산 시각이 OPEN·NULL 로 되돌아갔다 — 다음 정지가 그 포지션을 다시 팔았다(DB 문장 기록으로 확인: 정산의
 * UPDATE 뒤 다른 연결이 {@code closed_at = NULL} 로 행 전체를 다시 썼다).</p>
 */
@DisplayName("포지션 갱신 유실 — 낡은 엔티티 저장·평가손익 기록이 다른 경로가 커밋한 청산을 되돌리지 않는다")
class PositionLostUpdateTest extends IntegrationTestBase {

    @Autowired private PositionRepository positionRepository;
    @Autowired private LiveTradingSessionRepository liveRepo;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : sessions) {
            positionRepository.findBySessionKindAndSessionId("LIVE", id).forEach(positionRepository::delete);
            liveRepo.findById(id).ifPresent(liveRepo::delete);
        }
    }

    private PositionEntity openPosition() {
        Long sid = liveRepo.save(LiveTradingSessionEntity.builder()
                .sessionType("REAL").strategyType("COMPOSITE_BREAKOUT").coinPair("KRW-LUP").timeframe("H1")
                .initialCapital(new BigDecimal("10000")).availableKrw(new BigDecimal("7058.53"))
                .totalAssetKrw(new BigDecimal("10000")).status("RUNNING").startedAt(Instant.now()).build()).getId();
        sessions.add(sid);
        return positionRepository.save(PositionEntity.builder()
                .coinPair("KRW-LUP").side("BUY").entryPrice(new BigDecimal("300")).avgPrice(new BigDecimal("300"))
                .size(new BigDecimal("9.8")).investedKrw(new BigDecimal("2941.47")).entryAllocationKrw(new BigDecimal("3000"))
                .status("OPEN").sessionKind("LIVE").sessionId(sid).openedAt(Instant.now()).build());
    }

    /** 다른 연결에서 커밋 — 유령 정산이 포지션을 닫은 것과 같다. */
    private void closeElsewhere(Long positionId) {
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        other.executeWithoutResult(st -> jdbc.update("UPDATE position SET status = 'CLOSED', closed_at = ?, realized_pnl = -1.176 WHERE id = ?",
                Timestamp.from(Instant.now()), positionId));
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT status, closed_at, unrealized_pnl, realized_pnl FROM position WHERE id = ?", id);
    }

    @Test
    @DisplayName("🔴 낡은 엔티티의 다른 열만 바꿔 저장해도 그 사이 커밋된 CLOSED·청산 시각·실현손익은 그대로다(@DynamicUpdate)")
    void staleEntitySaveDoesNotRevertClose() {
        PositionEntity p = openPosition();
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            PositionEntity stale = positionRepository.findById(p.getId()).orElseThrow();   // 평가 루프가 회차 시작에 읽음(OPEN)
            closeElsewhere(p.getId());                                                     // 그 사이 정산이 닫고 커밋
            stale.setUnrealizedPnl(new BigDecimal("98"));                                  // 종전 평가 루프의 저장
            positionRepository.save(stale);
        });
        Map<String, Object> r = row(p.getId());
        assertThat(r.get("status")).isEqualTo("CLOSED");
        assertThat(r.get("closed_at")).isNotNull();
        assertThat((BigDecimal) r.get("realized_pnl")).isEqualByComparingTo("-1.176");
    }

    @Test
    @DisplayName("평가 루프의 평가손익 기록은 그 열만, OPEN 일 때만 쓴다 — 닫힌 포지션은 건드리지 않는다")
    void unrealizedWriteOnlyWhenOpen() {
        PositionEntity p = openPosition();
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            PositionEntity loaded = positionRepository.findById(p.getId()).orElseThrow();
            assertThat(positionRepository.updateUnrealizedPnlIfOpen(loaded.getId(), new BigDecimal("98"))).isEqualTo(1);
        });
        assertThat((BigDecimal) row(p.getId()).get("unrealized_pnl")).isEqualByComparingTo("98");

        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            PositionEntity stale = positionRepository.findById(p.getId()).orElseThrow();
            closeElsewhere(p.getId());
            assertThat(positionRepository.updateUnrealizedPnlIfOpen(stale.getId(), new BigDecimal("120"))).as("닫힌 뒤에는 0행").isZero();
        });
        Map<String, Object> r = row(p.getId());
        assertThat(r.get("status")).isEqualTo("CLOSED");
        assertThat(r.get("closed_at")).isNotNull();
        assertThat((BigDecimal) r.get("unrealized_pnl")).isEqualByComparingTo("98");
    }
}
