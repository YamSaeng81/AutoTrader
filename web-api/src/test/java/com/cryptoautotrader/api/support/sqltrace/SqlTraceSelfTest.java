package com.cryptoautotrader.api.support.sqltrace;

import com.cryptoautotrader.api.entity.DynamicSessionEntity;
import com.cryptoautotrader.api.repository.DynamicSessionRepository;
import com.cryptoautotrader.api.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기록 장치 자기 시험 — <b>test B 는 이 장치를 신뢰할 수 있어야 성립한다.</b>
 *
 * <p>확인하는 것 셋:
 * <ol>
 *   <li>문장이 {@code START} / {@code END} <b>두 단계</b>로 기록되는가 — 시작만 보고 완료를
 *       단정하지 않기 위한 전제다.</li>
 *   <li>REQUIRES_NEW 안쪽이 <b>바깥과 다른 커넥션</b>으로 기록되는가 — 같은 SQL 이 어디서
 *       나왔는지 가르는 유일한 수단이다.</li>
 *   <li>관측이 <b>거동을 바꾸지 않는가</b> — 같은 시나리오를 켜고/끄고 돌려 결과가 같아야 한다.</li>
 * </ol>
 */
@Import(SqlTraceConfig.class)
class SqlTraceSelfTest extends IntegrationTestBase {

    @Autowired
    private DynamicSessionRepository sessionRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    @AfterEach
    void off() {
        SqlTrace.stop();
    }

    private DynamicSessionEntity sample() {
        return DynamicSessionEntity.builder()
                .strategyType("COMPOSITE_MTF_BTC").timeframe("H1")
                .initialCapital(new BigDecimal("10000.00"))
                .availableKrw(new BigDecimal("10000.00"))
                .totalAssetKrw(new BigDecimal("10000.00"))
                .investRatio(new BigDecimal("0.8000")).stopLossPct(new BigDecimal("5.00"))
                .status("RUNNING").scanState("SCANNING").tradingMode("PAPER")
                .maxCandidateSize(30).targetWatchSize(10)
                .minAtrPct(new BigDecimal("0.5000")).maxSpreadPct(new BigDecimal("0.1000"))
                .watchlistRefreshMin(60)
                .build();
    }

    @Test
    @DisplayName("문장은 START·END 두 단계로 기록되고 트랜잭션·스레드가 함께 남는다")
    void recordsStartAndEndWithContext() {
        SqlTrace.start();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long id = tx.execute(s -> sessionRepo.saveAndFlush(sample()).getId());
        SqlTrace.stop();

        List<SqlTrace.Row> inserts = SqlTrace.rows().stream()
                .filter(r -> r.isInsertInto("dynamic_session")).toList();

        assertThat(inserts)
                .as("insert 가 기록돼야 한다. 전체 덤프:%n%s", SqlTrace.dump())
                .isNotEmpty();
        assertThat(inserts.stream().map(SqlTrace.Row::phase).collect(Collectors.toSet()))
                .as("🔴 START 만 있으면 '실행됐다'를 말할 수 없다 — END 또는 FAIL 이 함께 있어야 한다")
                .contains(SqlTrace.Phase.START, SqlTrace.Phase.END);
        assertThat(inserts.get(0).txActive())
                .as("트랜잭션 안에서 실행됐음이 기록돼야 한다")
                .isTrue();
        assertThat(inserts.get(0).thread()).isNotBlank();
        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("🔴 REQUIRES_NEW 안쪽은 바깥과 다른 커넥션으로 기록된다 — B 의 전제")
    void innerRequiresNewUsesDifferentConnection() {
        Long id = sessionRepo.saveAndFlush(sample()).getId();

        SqlTrace.start();
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.execute(s -> {
            // 바깥에서 한 번 읽어 커넥션을 실제로 쓰게 만든다 (쓰기는 하지 않는다 —
            // 이 시험의 목적은 '커넥션이 갈리는가' 뿐이고 잠금 경합을 만들 필요가 없다).
            sessionRepo.findById(id).orElseThrow();
            inner.execute(s2 -> sessionRepo.findById(id).orElseThrow());
            return null;
        });
        SqlTrace.stop();

        Set<Integer> conns = SqlTrace.rows().stream()
                .filter(r -> r.phase() == SqlTrace.Phase.START)
                .map(SqlTrace.Row::connTag).collect(Collectors.toSet());

        assertThat(conns)
                .as("바깥과 REQUIRES_NEW 안쪽이 서로 다른 커넥션으로 기록돼야 한다 — "
                        + "하나로 합쳐 보이면 같은 SQL 의 출처를 가릴 수 없다. 덤프:%n%s",
                        SqlTrace.dump())
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("관측은 거동을 바꾸지 않는다 — 켜고/끄고 같은 결과")
    void tracingDoesNotChangeBehaviour() {
        Long a = runScenario(false);
        Long b = runScenario(true);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();

        DynamicSessionEntity sa = sessionRepo.findById(a).orElseThrow();
        DynamicSessionEntity sb = sessionRepo.findById(b).orElseThrow();
        assertThat(sb.getAvailableKrw())
                .as("🔴 관측이 켜졌다고 상태가 달라지면 그 관측으로는 아무것도 말할 수 없다")
                .isEqualByComparingTo(sa.getAvailableKrw());
        assertThat(sb.getWatchlistJson()).isEqualTo(sa.getWatchlistJson());
    }

    private Long runScenario(boolean traced) {
        if (traced) SqlTrace.start();
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            return tx.execute(s -> {
                DynamicSessionEntity e = sessionRepo.saveAndFlush(sample());
                e.setWatchlistJson("[\"KRW-BTC\"]");
                e.setAvailableKrw(new BigDecimal("9000.00"));
                return sessionRepo.saveAndFlush(e).getId();
            });
        } finally {
            if (traced) SqlTrace.stop();
        }
    }
}
