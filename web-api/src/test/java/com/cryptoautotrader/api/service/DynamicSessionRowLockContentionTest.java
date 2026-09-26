package com.cryptoautotrader.api.service;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>test A — 잠금 메커니즘 확인</b> (2026-09-26).
 *
 * <p><b>이 테스트가 입증하는 것과 하지 않는 것</b>을 먼저 못 박는다.
 * <ul>
 *   <li>입증한다: <b>바깥 트랜잭션이 {@code dynamic_session} 행을 이미 UPDATE 해 잠금을 쥔 상태에서</b>
 *       {@link DynamicSessionBalanceUpdater}(REQUIRES_NEW, 별도 커넥션)가 같은 행을 갱신하려 하면
 *       <b>경합이 생기는가</b>.</li>
 *   <li>입증하지 않는다: 생산 경로가 <b>실제로</b> 그 순서를 만드는가. 여기서는 운영에 없는
 *       명시적 {@code flush()} 를 넣어 바깥 UPDATE 를 강제로 실행시킨다 — 그건 메커니즘을 보려는
 *       장치이며, 생산 경로의 증거가 아니다. 그쪽은 <b>test B</b> 가 따로 본다.</li>
 * </ul>
 *
 * <p><b>왜 명시적 flush 가 필요한가</b>: JPA 객체를 바꾸고 {@code save()} 를 불러도 SQL 은 아직
 * 나가지 않을 수 있다. UPDATE 가 실행되지 않으면 행 잠금이 없어 경합 자체가 생기지 않으므로,
 * 메커니즘 확인용 테스트에서는 flush 로 실행을 보장한다.
 *
 * <p><b>왜 실제 컴포넌트를 쓰는가</b>: {@code DynamicSessionBalanceUpdater} 는 어노테이션이 아니라
 * {@code TransactionTemplate(PROPAGATION_REQUIRES_NEW)} 를 직접 쓴다(`:42-51`). 따라서
 * self-invocation 으로 무시될 여지가 없고 <b>실제로 새 커넥션</b>을 쓴다 — 손으로 만든 대역이 아니라
 * 이 빈을 그대로 불러야 생산과 같은 경계가 재현된다.
 *
 * <p><b>H2 로 잡히는지는 가정하지 않는다.</b> 이 테스트의 결과 자체가 그 답이다. H2 는 잠금 제한이
 * 있어 예외로 끝나고 Postgres 는 제한이 없어 무한히 기다린다 — 같은 경합의 다른 표현이다.
 */
class DynamicSessionRowLockContentionTest extends IntegrationTestBase {

    @Autowired
    private DynamicSessionRepository sessionRepo;

    @Autowired
    private DynamicSessionBalanceUpdater balanceUpdater;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private Long newRunningRealSession() {
        return sessionRepo.saveAndFlush(DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MOMENTUM_ICHIMOKU_V2").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("REAL")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build()).getId();
    }

    @Test
    @DisplayName("🔴 바깥이 세션 행을 쓴 뒤 REQUIRES_NEW 가 같은 행을 갱신하면 경합한다")
    void requiresNewOnRowLockedByOuterTransaction() {
        Long id = newRunningRealSession();

        TransactionTemplate outer = new TransactionTemplate(txManager);
        Outcome outcome = outer.execute(status -> {
            DynamicSessionEntity s = sessionRepo.findById(id).orElseThrow();
            // 생산의 워치리스트 갱신과 같은 자리 — 바깥 트랜잭션이 이 행을 직접 쓴다.
            s.setWatchlistJson("[\"KRW-BTC\"]");
            sessionRepo.save(s);
            em.flush();   // 🔴 A 전용: UPDATE 를 실제로 실행시켜 잠금을 획득한다

            try {
                balanceUpdater.apply(id, x ->
                        x.setAvailableKrw(x.getAvailableKrw().subtract(new BigDecimal("5000.00"))));
                return new Outcome(null, null);
            } catch (Throwable t) {
                return new Outcome(t.getClass().getName(), String.valueOf(t.getMessage()));
            }
        });

        // 🔴 예외 유무만 보지 않는다 — 최종 상태까지 확인해야 "무엇이 일어났는지"가 정해진다.
        //    예외가 안 나고 차감이 반영됐다면 이 순서에 경합이 없다는 뜻이고,
        //    예외가 났다면 그 종류와 함께 차감이 남지 않았음을 확인한다.
        DynamicSessionEntity after = sessionRepo.findById(id).orElseThrow();

        // 🔴 2026-09-26 실측 (H2): 예외 종류는
        //    org.springframework.orm.jpa.JpaSystemException — "Unable to rollback against
        //    JDBC Connection" 이었다. **깔끔한 잠금 타임아웃 예외가 아니다** — 그래서 예외
        //    종류로 단정하지 않는다(엔진마다 다르게 표면화되고, Postgres 는 제한이 없으면
        //    애초에 예외 없이 무한히 기다린다).
        //    단정하는 것은 두 가지다: (1) 무언가 실패했다 (2) 차감이 남지 않았다.
        assertThat(outcome.type())
                .as("바깥이 쥔 행을 REQUIRES_NEW 가 갱신하려 하면 그대로 성공하지 못한다 "
                        + "— 성공했다면 이 순서에 경합이 없다는 뜻이므로 이 테스트의 전제가 무너진다 "
                        + "(관측 메시지: %s)", outcome.message())
                .isNotNull();

        assertThat(after.getAvailableKrw())
                .as("경합으로 실패했으므로 REQUIRES_NEW 의 차감은 남지 않아야 한다 "
                        + "— 예외만 보고 넘어가면 '실패했는데 일부는 남았다'를 놓친다")
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    private record Outcome(String type, String message) {}
}
